package ai.arena.arkernel

import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val MAX_INFERENCE_DIMENSION = 16_384
private const val MAX_INFERENCE_FRAME_BYTES = 256L * 1024L * 1024L
private const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L

/** Capabilities are explicit: relative depth must never enter the metric map. */
enum class InferenceTask { SEMANTIC_DETECTION, METRIC_DEPTH, RELATIVE_DEPTH, SEGMENTATION }
enum class TensorQuantization { FLOAT32, FLOAT16, INT8, UINT8 }
enum class InferencePixelFormat(val channels: Int) { GRAY8(1), RGB888(3), RGBA8888(4) }

data class InferenceDeviceProfile(
    val devicePattern: String,
    val backendName: String,
    val averageLatencyMs: Double,
    val p95LatencyMs: Double,
    val peakWorkingMemoryBytes: Long,
    val sampleCount: Int,
) {
    init {
        require(devicePattern.isNotBlank() && backendName.isNotBlank())
        require(averageLatencyMs > 0.0 && p95LatencyMs >= averageLatencyMs)
        require(peakWorkingMemoryBytes > 0 && sampleCount >= 10)
    }
}

class ModelManifest(
    val schemaVersion: Int = 1,
    val modelId: String,
    val modelVersion: String,
    val sha256: String,
    val licenseSpdx: String,
    val sourceUrl: String,
    tasks: Set<InferenceTask>,
    val inputWidth: Int,
    val inputHeight: Int,
    val inputChannels: Int,
    inputMean: List<Double>,
    inputStd: List<Double>,
    val quantization: TensorQuantization,
    labels: Map<Long, String> = emptyMap(),
    val depthUnitToMeters: Double? = null,
    val modelByteSize: Long,
    deviceProfiles: List<InferenceDeviceProfile> = emptyList(),
    val quantizationScale: Double? = null,
    val quantizationZeroPoint: Int? = null,
) {
    val tasks: Set<InferenceTask> = tasks.toSet()
    val inputMean: List<Double> = inputMean.toList()
    val inputStd: List<Double> = inputStd.toList()
    val labels: Map<Long, String> = labels.toMap()
    val deviceProfiles: List<InferenceDeviceProfile> = deviceProfiles.toList()

    init {
        require(schemaVersion == 1) { "Unsupported model-manifest schema" }
        require(modelId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(modelVersion.isNotBlank() && modelVersion.length <= 128)
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 must be lowercase hexadecimal" }
        require(licenseSpdx.matches(Regex("[A-Za-z0-9.+()\\-: ]{1,128}")))
        val source = URI(sourceUrl)
        require(source.scheme == "https" && !source.host.isNullOrBlank() && source.userInfo == null)
        require(tasks.isNotEmpty())
        require(inputWidth in 1..MAX_INFERENCE_DIMENSION &&
            inputHeight in 1..MAX_INFERENCE_DIMENSION && inputChannels in setOf(1, 3, 4))
        require(inputMean.size == inputChannels && inputStd.size == inputChannels)
        require(inputMean.all { it.isFinite() })
        require(inputStd.all { it.isFinite() && it > 0.0 })
        if (quantization == TensorQuantization.INT8 || quantization == TensorQuantization.UINT8) {
            require(quantizationScale != null && quantizationScale.isFinite() &&
                quantizationScale > 0.0 && quantizationZeroPoint != null)
            if (quantization == TensorQuantization.INT8)
                require(quantizationZeroPoint in -128..127)
            else require(quantizationZeroPoint in 0..255)
        } else {
            require(quantizationScale == null && quantizationZeroPoint == null)
        }
        require(modelByteSize in 1L..MAX_MODEL_BYTES)
        require(labels.keys.all { it in 0..0xffff_ffffL })
        require(labels.values.all { it.isNotBlank() && it.length <= 256 })
        if (InferenceTask.SEMANTIC_DETECTION in tasks) {
            require(labels[0] != null) { "Semantic manifests must define class 0 as Unknown" }
        }
        if (InferenceTask.METRIC_DEPTH in tasks) {
            require(depthUnitToMeters != null && depthUnitToMeters.isFinite() &&
                depthUnitToMeters > 0.0)
        } else {
            require(depthUnitToMeters == null) {
                "Only metric-depth manifests may define a metric conversion"
            }
        }
        require(!(InferenceTask.METRIC_DEPTH in tasks && InferenceTask.RELATIVE_DEPTH in tasks))
    }

    /** Hashes remaining bytes without changing the caller's buffer position. */
    fun verifyModelBytes(modelBytes: ByteBuffer): Boolean {
        if (modelBytes.remaining().toLong() != modelByteSize) return false
        val bytes = modelBytes.duplicate()
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(minOf(8192, bytes.remaining()))
        while (bytes.hasRemaining()) {
            val count = minOf(chunk.size, bytes.remaining())
            bytes.get(chunk, 0, count)
            digest.update(chunk, 0, count)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual == sha256
    }
}

/**
 * Immutable view contract for asynchronous inference. The underlying direct
 * memory remains host-owned and must not be modified until the callback.
 */
class PerceptionFrame private constructor(
    val timestampNs: Long,
    val calibration: CameraCalibration,
    val worldFromCamera: WorldPose,
    val format: InferencePixelFormat,
    val rowStrideBytes: Int,
    val image: ByteBuffer,
) {
    companion object {
        fun wrap(
            timestampNs: Long,
            calibration: CameraCalibration,
            worldFromCamera: WorldPose,
            format: InferencePixelFormat,
            rowStrideBytes: Int,
            image: ByteBuffer,
        ): PerceptionFrame {
            require(timestampNs > 0 && timestampNs == worldFromCamera.timestampNs)
            require(calibration.width in 1..MAX_INFERENCE_DIMENSION &&
                calibration.height in 1..MAX_INFERENCE_DIMENSION)
            require(image.isDirect) { "Inference image must be a direct buffer" }
            require(rowStrideBytes.toLong() >=
                calibration.width.toLong() * format.channels.toLong())
            val required = (calibration.height.toLong() - 1L) * rowStrideBytes.toLong() +
                calibration.width.toLong() * format.channels
            require(required <= MAX_INFERENCE_FRAME_BYTES)
            require(required <= image.remaining().toLong()) { "Inference image is truncated" }
            return PerceptionFrame(timestampNs, calibration, worldFromCamera, format,
                rowStrideBytes, image.slice().asReadOnlyBuffer())
        }
    }
}

data class SemanticDetection2d(
    val classId: Long,
    val sourceTrackId: Long = -1,
    val confidence: Double,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val anchorPixelX: Double = (left + right) * 0.5,
    val anchorPixelY: Double = bottom,
    val dynamic: Boolean = false,
    val projectionSurface: SemanticProjectionSurface = SemanticProjectionSurface.ANY,
) {
    init {
        require(classId in 0..0xffff_ffffL && sourceTrackId >= -1 && confidence in 0.0..1.0)
        require(listOf(left, top, right, bottom, anchorPixelX, anchorPixelY).all { it.isFinite() })
        require(right > left && bottom > top)
    }
}

data class OnDeviceInferenceOutput(
    val timestampNs: Long,
    val worldFromCamera: WorldPose,
    val detections: List<SemanticDetection2d> = emptyList(),
    val metricDepth: MetricDepthImage? = null,
    val metricDepthBuffer: MetricDepthBuffer? = null,
) {
    init { require(metricDepth == null || metricDepthBuffer == null) }
}

interface OnDeviceInferenceBackend : AutoCloseable {
    val manifest: ModelManifest
    /** Called serially by BoundedInferenceScheduler, never concurrently. */
    fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput
    override fun close() = Unit
}

enum class InferenceSubmitResult { ACCEPTED, RATE_LIMITED, BUSY, OUT_OF_ORDER, CLOSED }

/** One in-flight request, no hidden queue, explicit drops and monotonic frames. */
class BoundedInferenceScheduler(
    private val backend: OnDeviceInferenceBackend,
    private val minimumIntervalNs: Long = 100_000_000L,
    threadName: String = "ArKernelInference",
) : AutoCloseable {
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }
    private var inFlight = false
    private var closed = false
    private var backendClosed = false
    private var lastAcceptedTimestampNs = 0L

    init { require(minimumIntervalNs > 0 && threadName.isNotBlank()) }

    fun submit(
        frame: PerceptionFrame,
        onResult: (OnDeviceInferenceOutput) -> Unit,
        onError: (Exception) -> Unit = {},
    ): InferenceSubmitResult {
        synchronized(lock) {
            if (closed) return InferenceSubmitResult.CLOSED
            if (frame.timestampNs <= lastAcceptedTimestampNs)
                return InferenceSubmitResult.OUT_OF_ORDER
            if (inFlight) return InferenceSubmitResult.BUSY
            if (lastAcceptedTimestampNs > 0 &&
                frame.timestampNs - lastAcceptedTimestampNs < minimumIntervalNs)
                return InferenceSubmitResult.RATE_LIMITED
            inFlight = true
            lastAcceptedTimestampNs = frame.timestampNs
        }
        try {
            executor.execute {
                try {
                    val output = backend.infer(frame)
                    validateOutput(frame, output)
                    onResult(output)
                } catch (error: Exception) {
                    onError(error)
                } finally {
                    var closeBackend = false
                    synchronized(lock) {
                        inFlight = false
                        if (closed && !backendClosed) {
                            backendClosed = true
                            closeBackend = true
                        }
                    }
                    if (closeBackend) backend.close()
                }
            }
        } catch (rejected: RejectedExecutionException) {
            var closeBackend = false
            synchronized(lock) {
                inFlight = false
                if (closed && !backendClosed) {
                    backendClosed = true
                    closeBackend = true
                }
            }
            if (closeBackend) backend.close()
            return InferenceSubmitResult.CLOSED
        }
        return InferenceSubmitResult.ACCEPTED
    }

    private fun validateOutput(frame: PerceptionFrame, output: OnDeviceInferenceOutput) {
        require(output.timestampNs == frame.timestampNs)
        require(output.worldFromCamera == frame.worldFromCamera) {
            "Inference backends may not replace the frame-associated pose"
        }
        require(output.detections.size <= 256)
        require(output.detections.all {
            (it.classId == 0L || backend.manifest.labels.containsKey(it.classId)) &&
                it.left >= 0.0 && it.top >= 0.0 &&
                it.right <= frame.calibration.width && it.bottom <= frame.calibration.height &&
                it.anchorPixelX >= 0.0 && it.anchorPixelX < frame.calibration.width &&
                it.anchorPixelY >= 0.0 && it.anchorPixelY < frame.calibration.height
        })
        if (output.detections.isNotEmpty())
            require(InferenceTask.SEMANTIC_DETECTION in backend.manifest.tasks)
        output.metricDepth?.let {
            require(InferenceTask.METRIC_DEPTH in backend.manifest.tasks)
            require(it.timestampNs == frame.timestampNs)
            require(it.width == frame.calibration.width && it.height == frame.calibration.height)
        }
        output.metricDepthBuffer?.let {
            require(InferenceTask.METRIC_DEPTH in backend.manifest.tasks)
            require(it.timestampNs == frame.timestampNs)
            require(it.width == frame.calibration.width && it.height == frame.calibration.height)
        }
    }

    override fun close() {
        var closeBackend = false
        synchronized(lock) {
            if (closed) return
            closed = true
            if (!inFlight && !backendClosed) {
                backendClosed = true
                closeBackend = true
            }
        }
        executor.shutdown()
        if (closeBackend) backend.close()
    }
}

data class PerceptionIntegrationResult(
    val depth: SurfelMapUpdate?,
    val semanticUpdates: Int,
    val semanticMisses: Int,
)

/** Applies only metric depth and bounded 2D-to-plane semantic projections. */
fun ArKernelSession.integrateInferenceOutput(
    output: OnDeviceInferenceOutput,
    depthOptions: DepthSamplingOptions = DepthSamplingOptions(),
): PerceptionIntegrationResult {
    val depthUpdate = when {
        output.metricDepth != null ->
            integrateMetricDepthImage(output.metricDepth, output.worldFromCamera, depthOptions)
        output.metricDepthBuffer != null ->
            integrateMetricDepthBuffer(output.metricDepthBuffer, output.worldFromCamera, depthOptions)
        else -> null
    }
    var semanticUpdates = 0
    var semanticMisses = 0
    output.detections.forEach { detection ->
        val update = projectSemanticDetection(
            SemanticPixelProjection(detection.classId, detection.sourceTrackId,
                detection.anchorPixelX, detection.anchorPixelY, detection.confidence,
                output.timestampNs, detection.dynamic, detection.projectionSurface),
            output.worldFromCamera)
        if (update == null) ++semanticMisses else ++semanticUpdates
    }
    return PerceptionIntegrationResult(depthUpdate, semanticUpdates, semanticMisses)
}
