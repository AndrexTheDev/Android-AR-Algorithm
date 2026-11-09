package ai.arena.arkernel

import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max

private const val MAX_SEMANTIC_GOLDEN_CASES = 16
private const val MAX_SEMANTIC_GOLDEN_INPUT_BYTES = 32L * 1024L * 1024L
private const val MAX_SEMANTIC_DETECTIONS = 256
private const val MAX_SEMANTIC_DEPTH_PIXELS_PER_CASE = 4_194_304L
private const val MAX_SEMANTIC_DEPTH_PIXELS_TOTAL = 16_777_216L

class InferenceSemanticGoldenCase(
    val caseId: String,
    val frame: PerceptionFrame,
) {
    init { require(caseId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}

data class InferenceSemanticConformanceOptions(
    val boxTolerancePixels: Double = 1.0,
    val anchorTolerancePixels: Double = 1.0,
    val confidenceTolerance: Double = 0.01,
    val depthAbsoluteToleranceMeters: Double = 0.02,
    val depthRelativeTolerance: Double = 0.02,
    val depthConfidenceTolerance: Double = 0.02,
) {
    init {
        require(boxTolerancePixels.isFinite() && boxTolerancePixels >= 0.0)
        require(anchorTolerancePixels.isFinite() && anchorTolerancePixels >= 0.0)
        require(confidenceTolerance.isFinite() && confidenceTolerance >= 0.0)
        require(depthAbsoluteToleranceMeters.isFinite() &&
            depthAbsoluteToleranceMeters >= 0.0)
        require(depthRelativeTolerance.isFinite() && depthRelativeTolerance >= 0.0)
        require(depthConfidenceTolerance.isFinite() && depthConfidenceTolerance >= 0.0)
    }
}

enum class InferenceSemanticConformanceFailure {
    DELEGATE_MANIFEST_MISMATCH,
    DELEGATE_EXECUTION_FAILED,
    OUTPUT_FRAME_MISMATCH,
    DETECTION_COUNT_MISMATCH,
    DETECTION_SEMANTIC_MISMATCH,
    DETECTION_VALUE_MISMATCH,
    DEPTH_OUTPUT_MISMATCH,
    DEPTH_SHAPE_MISMATCH,
    DEPTH_NON_FINITE,
    DEPTH_VALUE_MISMATCH,
    DEPTH_CONFIDENCE_MISMATCH,
}

data class InferenceSemanticConformanceReport(
    val delegateAccepted: Boolean,
    val evaluatedCases: Int,
    val comparedDetections: Long,
    val comparedDepthPixels: Long,
    val maximumDetectionCoordinateErrorPixels: Double,
    val maximumDetectionConfidenceError: Double,
    val maximumDepthAbsoluteErrorMeters: Double,
    val maximumDepthRelativeError: Double,
    val maximumDepthConfidenceError: Double,
    val failure: InferenceSemanticConformanceFailure?,
    val failedCaseId: String?,
) {
    init {
        require(evaluatedCases >= 0 && comparedDetections >= 0 && comparedDepthPixels >= 0)
        require(listOf(maximumDetectionCoordinateErrorPixels,
            maximumDetectionConfidenceError, maximumDepthAbsoluteErrorMeters,
            maximumDepthRelativeError, maximumDepthConfidenceError).all {
                it.isFinite() && it >= 0.0
            })
        require(delegateAccepted == (failure == null && failedCaseId == null))
    }
}

data class SemanticallyConformingInferenceBackendSelection(
    val backend: OnDeviceInferenceBackend,
    val usingDelegate: Boolean,
    val report: InferenceSemanticConformanceReport,
)

/** Exact identity plus bounded decoded detection/metric-depth CPU/delegate conformance. */
object InferenceBackendSemanticConformanceSelector {
    fun select(
        cpuReference: OnDeviceInferenceBackend,
        delegate: OnDeviceInferenceBackend,
        expected: VerifiedInferenceIdentity,
        cases: List<InferenceSemanticGoldenCase>,
        options: InferenceSemanticConformanceOptions = InferenceSemanticConformanceOptions(),
    ): SemanticallyConformingInferenceBackendSelection {
        val identity = expected.manifest
        try {
            require(cases.isNotEmpty() && cases.size <= MAX_SEMANTIC_GOLDEN_CASES)
            require(cases.map { it.caseId }.distinct().size == cases.size)
            require(cases.sumOf { it.frame.image.remaining().toLong() } <=
                MAX_SEMANTIC_GOLDEN_INPUT_BYTES)
            require(identity.tasks.isNotEmpty() && identity.tasks.all {
                it == InferenceTask.SEMANTIC_DETECTION || it == InferenceTask.METRIC_DEPTH
            }) { "Semantic gate supports decoded detection and metric-depth tasks only" }
            require(cpuReference.manifest.exactIdentity() == identity) {
                "CPU backend identity does not match verified model artifact"
            }
        } catch (error: RuntimeException) {
            closeQuietly(delegate); closeQuietly(cpuReference); throw error
        }
        if (delegate.manifest.exactIdentity() != identity) {
            closeQuietly(delegate)
            return selection(cpuReference, false, Metrics(), 0,
                InferenceSemanticConformanceFailure.DELEGATE_MANIFEST_MISMATCH, null)
        }
        val metrics = Metrics()
        try {
            cases.forEachIndexed { index, golden ->
                val reference = cpuReference.infer(golden.frame)
                check(validateOutput(reference, golden.frame, identity) == null)
                if (InferenceTask.METRIC_DEPTH in identity.tasks) {
                    check(metrics.comparedDepthPixels + depthView(reference).pixelCount <=
                        MAX_SEMANTIC_DEPTH_PIXELS_TOTAL)
                }
                val candidate = try {
                    delegate.infer(golden.frame)
                } catch (error: Throwable) {
                    if (error is Error) throw error
                    closeQuietly(delegate)
                    return selection(cpuReference, false, metrics, index,
                        InferenceSemanticConformanceFailure.DELEGATE_EXECUTION_FAILED,
                        golden.caseId)
                }
                val failure = validateOutput(candidate, golden.frame, identity) ?:
                    compare(reference, candidate, identity, options, metrics)
                if (failure != null) {
                    closeQuietly(delegate)
                    return selection(cpuReference, false, metrics, index + 1,
                        failure, golden.caseId)
                }
            }
        } catch (error: Throwable) {
            closeQuietly(delegate); closeQuietly(cpuReference)
            if (error is Error) throw error
            throw IllegalStateException("CPU semantic golden reference is invalid", error)
        }
        closeQuietly(cpuReference)
        return selection(delegate, true, metrics, cases.size, null, null)
    }

    private fun validateOutput(output: OnDeviceInferenceOutput, frame: PerceptionFrame,
        identity: InferenceManifestIdentity): InferenceSemanticConformanceFailure? {
        if (output.timestampNs != frame.timestampNs ||
            output.worldFromCamera != frame.worldFromCamera)
            return InferenceSemanticConformanceFailure.OUTPUT_FRAME_MISMATCH
        val detectionTask = InferenceTask.SEMANTIC_DETECTION in identity.tasks
        if (if (detectionTask) output.detections.size > MAX_SEMANTIC_DETECTIONS
            else output.detections.isNotEmpty())
            return InferenceSemanticConformanceFailure.DETECTION_COUNT_MISMATCH
        if (output.detections.any { identity.labels[it.classId] == null })
            return InferenceSemanticConformanceFailure.DETECTION_SEMANTIC_MISMATCH
        val depthTask = InferenceTask.METRIC_DEPTH in identity.tasks
        val depthCount = (if (output.metricDepth != null) 1 else 0) +
            (if (output.metricDepthBuffer != null) 1 else 0)
        if (depthCount != if (depthTask) 1 else 0)
            return InferenceSemanticConformanceFailure.DEPTH_OUTPUT_MISMATCH
        return if (depthTask) depthView(output).validate() else null
    }

    private fun compare(reference: OnDeviceInferenceOutput,
        candidate: OnDeviceInferenceOutput, identity: InferenceManifestIdentity,
        options: InferenceSemanticConformanceOptions,
        metrics: Metrics): InferenceSemanticConformanceFailure? {
        if (reference.timestampNs != candidate.timestampNs ||
            reference.worldFromCamera != candidate.worldFromCamera)
            return InferenceSemanticConformanceFailure.OUTPUT_FRAME_MISMATCH
        if (InferenceTask.SEMANTIC_DETECTION in identity.tasks) {
            val detectionFailure = compareDetections(reference.detections,
                candidate.detections, options, metrics)
            if (detectionFailure != null) return detectionFailure
        }
        if (InferenceTask.METRIC_DEPTH in identity.tasks)
            return compareDepth(depthView(reference), depthView(candidate), options, metrics)
        return null
    }

    private fun compareDetections(reference: List<SemanticDetection2d>,
        candidate: List<SemanticDetection2d>, options: InferenceSemanticConformanceOptions,
        metrics: Metrics): InferenceSemanticConformanceFailure? {
        if (reference.size != candidate.size)
            return InferenceSemanticConformanceFailure.DETECTION_COUNT_MISMATCH
        val expected = reference.sortedWith(detectionComparator)
        val actual = candidate.sortedWith(detectionComparator)
        for (index in expected.indices) {
            val a = expected[index]
            val b = actual[index]
            if (a.classId != b.classId || a.sourceTrackId != b.sourceTrackId ||
                a.dynamic != b.dynamic || a.projectionSurface != b.projectionSurface)
                return InferenceSemanticConformanceFailure.DETECTION_SEMANTIC_MISMATCH
            val coordinateError = listOf(abs(a.left - b.left), abs(a.top - b.top),
                abs(a.right - b.right), abs(a.bottom - b.bottom))
            val anchorError = max(abs(a.anchorPixelX - b.anchorPixelX),
                abs(a.anchorPixelY - b.anchorPixelY))
            val confidenceError = abs(a.confidence - b.confidence)
            metrics.maximumDetectionCoordinateError = max(
                metrics.maximumDetectionCoordinateError,
                max(coordinateError.maxOrNull() ?: 0.0, anchorError))
            metrics.maximumDetectionConfidenceError = max(
                metrics.maximumDetectionConfidenceError, confidenceError)
            ++metrics.comparedDetections
            if (coordinateError.any { it > options.boxTolerancePixels } ||
                anchorError > options.anchorTolerancePixels ||
                confidenceError > options.confidenceTolerance)
                return InferenceSemanticConformanceFailure.DETECTION_VALUE_MISMATCH
        }
        return null
    }

    private fun compareDepth(reference: DepthView, candidate: DepthView,
        options: InferenceSemanticConformanceOptions,
        metrics: Metrics): InferenceSemanticConformanceFailure? {
        if (reference.width != candidate.width || reference.height != candidate.height ||
            reference.timestampNs != candidate.timestampNs ||
            reference.hasConfidence != candidate.hasConfidence)
            return InferenceSemanticConformanceFailure.DEPTH_SHAPE_MISMATCH
        for (y in 0 until reference.height) for (x in 0 until reference.width) {
            ++metrics.comparedDepthPixels
            val a = reference.depth(x, y).toDouble()
            val b = candidate.depth(x, y).toDouble()
            if (!a.isFinite() || !b.isFinite())
                return InferenceSemanticConformanceFailure.DEPTH_NON_FINITE
            val absolute = abs(a - b)
            val relative = absolute / max(abs(a), 1.0e-12)
            metrics.maximumDepthAbsoluteError = max(metrics.maximumDepthAbsoluteError, absolute)
            metrics.maximumDepthRelativeError = max(metrics.maximumDepthRelativeError, relative)
            if (absolute > options.depthAbsoluteToleranceMeters &&
                relative > options.depthRelativeTolerance)
                return InferenceSemanticConformanceFailure.DEPTH_VALUE_MISMATCH
            if (reference.hasConfidence) {
                val confidenceError = abs(reference.confidence(x, y).toDouble() -
                    candidate.confidence(x, y).toDouble())
                metrics.maximumDepthConfidenceError = max(
                    metrics.maximumDepthConfidenceError, confidenceError)
                if (!confidenceError.isFinite() ||
                    confidenceError > options.depthConfidenceTolerance)
                    return InferenceSemanticConformanceFailure.DEPTH_CONFIDENCE_MISMATCH
            }
        }
        return null
    }

    private val detectionComparator = compareBy<SemanticDetection2d>(
        { it.classId }, { it.sourceTrackId }, { it.dynamic }, { it.projectionSurface.ordinal },
        { it.left }, { it.top }, { it.right }, { it.bottom }, { it.anchorPixelX },
        { it.anchorPixelY }, { it.confidence })

    private fun depthView(output: OnDeviceInferenceOutput): DepthView =
        output.metricDepth?.let { ArrayDepthView(it) } ?:
        output.metricDepthBuffer?.let { BufferDepthView(it) } ?:
        throw IllegalArgumentException("Metric-depth output is missing")

    private fun selection(backend: OnDeviceInferenceBackend, usingDelegate: Boolean,
        metrics: Metrics, evaluatedCases: Int,
        failure: InferenceSemanticConformanceFailure?, caseId: String?) =
        SemanticallyConformingInferenceBackendSelection(backend, usingDelegate,
            InferenceSemanticConformanceReport(failure == null, evaluatedCases,
                metrics.comparedDetections, metrics.comparedDepthPixels,
                metrics.maximumDetectionCoordinateError,
                metrics.maximumDetectionConfidenceError,
                metrics.maximumDepthAbsoluteError, metrics.maximumDepthRelativeError,
                metrics.maximumDepthConfidenceError, failure, caseId))

    private fun closeQuietly(backend: OnDeviceInferenceBackend) {
        try { backend.close() } catch (_: Throwable) { }
    }

    private class Metrics {
        var comparedDetections = 0L
        var comparedDepthPixels = 0L
        var maximumDetectionCoordinateError = 0.0
        var maximumDetectionConfidenceError = 0.0
        var maximumDepthAbsoluteError = 0.0
        var maximumDepthRelativeError = 0.0
        var maximumDepthConfidenceError = 0.0
    }

    private abstract class DepthView {
        abstract val width: Int
        abstract val height: Int
        abstract val rowStride: Int
        abstract val confidenceRowStride: Int
        abstract val timestampNs: Long
        abstract val hasConfidence: Boolean
        val pixelCount: Long get() = width.toLong() * height
        abstract fun depth(x: Int, y: Int): Float
        abstract fun confidence(x: Int, y: Int): Float
        abstract fun storageValid(): Boolean

        fun validate(): InferenceSemanticConformanceFailure? {
            if (width <= 0 || height <= 0 || rowStride < width ||
                pixelCount !in 1..MAX_SEMANTIC_DEPTH_PIXELS_PER_CASE ||
                hasConfidence && confidenceRowStride < width ||
                !hasConfidence && confidenceRowStride != 0 || !storageValid())
                return InferenceSemanticConformanceFailure.DEPTH_OUTPUT_MISMATCH
            for (y in 0 until height) for (x in 0 until width) {
                val depth = depth(x, y)
                if (!depth.isFinite() || depth < 0f)
                    return InferenceSemanticConformanceFailure.DEPTH_NON_FINITE
                if (hasConfidence) {
                    val confidence = confidence(x, y)
                    if (!confidence.isFinite() || confidence !in 0f..1f)
                        return InferenceSemanticConformanceFailure.DEPTH_CONFIDENCE_MISMATCH
                }
            }
            return null
        }
    }

    private class ArrayDepthView(private val image: MetricDepthImage) : DepthView() {
        override val width = image.width
        override val height = image.height
        override val rowStride = image.rowStrideElements
        override val confidenceRowStride = image.confidenceRowStrideElements
        override val timestampNs = image.timestampNs
        override val hasConfidence = image.confidence != null
        override fun depth(x: Int, y: Int) = image.depthMeters[y * rowStride + x]
        override fun confidence(x: Int, y: Int) =
            requireNotNull(image.confidence)[y * confidenceRowStride + x]
        override fun storageValid(): Boolean {
            val depthRequired = (height.toLong() - 1L) * rowStride + width
            val confidenceRequired = (height.toLong() - 1L) * confidenceRowStride + width
            return depthRequired <= image.depthMeters.size && (!hasConfidence ||
                confidenceRequired <= requireNotNull(image.confidence).size)
        }
    }

    private class BufferDepthView(image: MetricDepthBuffer) : DepthView() {
        override val width = image.width
        override val height = image.height
        override val rowStride = image.rowStrideElements
        override val confidenceRowStride = image.confidenceRowStrideElements
        override val timestampNs = image.timestampNs
        override val hasConfidence = image.confidence != null
        private val depth: FloatBuffer = image.depthMeters.duplicate()
        private val depthStart = depth.position()
        private val confidence: FloatBuffer? = image.confidence?.duplicate()
        private val confidenceStart = confidence?.position() ?: 0
        private val nativeBuffers = depth.isDirect &&
            depth.order() == ByteOrder.nativeOrder() &&
            (confidence == null || confidence.isDirect &&
                confidence.order() == ByteOrder.nativeOrder())
        override fun depth(x: Int, y: Int) = depth.get(depthStart + y * rowStride + x)
        override fun confidence(x: Int, y: Int) =
            requireNotNull(confidence).get(confidenceStart + y * confidenceRowStride + x)
        override fun storageValid(): Boolean {
            val depthRequired = (height.toLong() - 1L) * rowStride + width
            val confidenceRequired = (height.toLong() - 1L) * confidenceRowStride + width
            return nativeBuffers && depthRequired <= depth.remaining() && (!hasConfidence ||
                confidenceRequired <= requireNotNull(confidence).remaining())
        }
    }
}
