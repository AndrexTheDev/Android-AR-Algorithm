package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal runtime boundary for one-input/one-output tensor engines. Implementations may wrap
 * LiteRT, ONNX Runtime, NNAPI, or a host engine without exposing that dependency to ARKernel.
 */
interface SingleInputTensorRuntime : AutoCloseable {
    /**
     * Consumes the complete input and writes the complete output from each buffer's current
     * position. Buffers are direct, native-order, and valid only for this call. Returns the
     * number of output bytes written; partial output is rejected by the backend.
     */
    fun execute(input: ByteBuffer, output: ByteBuffer): Int
    override fun close() = Unit
}

/**
 * Multi-head equivalent. Buffers are direct, native-order, valid only for the call, and must not
 * be retained. Returned byte counts correspond exactly to outputs by index.
 */
interface MultiOutputTensorRuntime : AutoCloseable {
    fun execute(input: ByteBuffer, outputs: List<ByteBuffer>): IntArray
    override fun close() = Unit
}

/** Dependency-free composition of preprocessing, a runtime adapter, and metric-depth decoding. */
class MetricDepthTensorBackend private constructor(
    override val manifest: ModelManifest,
    private val runtime: SingleInputTensorRuntime,
    private val outputSpec: DepthTensorSpec,
    private val outputTransform: DepthTensorTransform = DepthTensorTransform.DIRECT,
    private val minDepthMeters: Double = 0.05,
    private val maxDepthMeters: Double = 20.0,
    private val maxRelativeDiscontinuity: Double = 0.10,
    private val swapRedBlue: Boolean = false,
) : OnDeviceInferenceBackend {
    private val closed = AtomicBoolean(false)
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val outputBytes: Int

    init {
        require(InferenceTask.METRIC_DEPTH in manifest.tasks)
        require(manifest.quantization != TensorQuantization.FLOAT16) {
            "Shared tensor preprocessing does not encode FLOAT16"
        }
        require(manifest.inputChannels == 1 || manifest.inputChannels == 3)
        require(minDepthMeters.isFinite() && maxDepthMeters.isFinite() &&
            minDepthMeters > 0.0 && maxDepthMeters > minDepthMeters)
        require(maxRelativeDiscontinuity.isFinite() && maxRelativeDiscontinuity in 0.0..10.0)
        val inputElements = manifest.inputWidth.toLong() * manifest.inputHeight *
            manifest.inputChannels
        val inputElementBytes = if (manifest.quantization == TensorQuantization.FLOAT32) 4L else 1L
        val inputBytes = Math.multiplyExact(inputElements, inputElementBytes)
        require(inputBytes in 1L..MAX_BACKEND_TENSOR_BYTES.toLong())
        val outputElementCount = Math.multiplyExact(outputSpec.rowStrideElements.toLong(),
            outputSpec.height.toLong())
        val calculatedOutputBytes = Math.multiplyExact(outputElementCount,
            outputSpec.encoding.bytesPerElement.toLong())
        require(calculatedOutputBytes in 1L..MAX_BACKEND_TENSOR_BYTES.toLong())
        outputBytes = calculatedOutputBytes.toInt()
        inputBuffer = ByteBuffer.allocateDirect(inputBytes.toInt()).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
    }

    @Synchronized
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed.get()) { "Metric-depth backend is closed" }
        val preprocessed = ArKernelTensorPreprocessor.preprocessInto(frame, manifest,
            inputBuffer, swapRedBlue)
        val runtimeInput = preprocessed.bytes.duplicate().order(ByteOrder.nativeOrder())
        runtimeInput.position(0)
        val runtimeOutput = outputBuffer.duplicate().order(ByteOrder.nativeOrder())
        runtimeOutput.clear()
        runtimeOutput.limit(outputBytes)
        val written = runtime.execute(runtimeInput, runtimeOutput)
        require(written == outputBytes) {
            "Tensor runtime returned $written bytes; expected $outputBytes"
        }
        val depthTensor = outputBuffer.duplicate().order(ByteOrder.nativeOrder())
        depthTensor.position(0)
        depthTensor.limit(outputBytes)
        val metricDepth = ArKernelDepthPostprocessor.postprocessForFrame(depthTensor,
            outputSpec, frame, manifest, outputTransform, minDepthMeters, maxDepthMeters,
            maxRelativeDiscontinuity)
        return OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera,
            metricDepthBuffer = metricDepth)
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.close()
    }

    companion object {
        private const val MAX_BACKEND_TENSOR_BYTES = 64 * 1024 * 1024

        /** Verifies the exact model bytes before constructing a runtime-backed adapter. */
        fun createVerified(
            manifest: ModelManifest,
            modelBytes: ByteBuffer,
            runtimeFactory: (ByteBuffer) -> SingleInputTensorRuntime,
            outputSpec: DepthTensorSpec,
            outputTransform: DepthTensorTransform = DepthTensorTransform.DIRECT,
            minDepthMeters: Double = 0.05,
            maxDepthMeters: Double = 20.0,
            maxRelativeDiscontinuity: Double = 0.10,
            swapRedBlue: Boolean = false,
        ): MetricDepthTensorBackend {
            require(manifest.verifyModelBytes(modelBytes)) { "Model byte size or SHA-256 mismatch" }
            val runtimeBytes = modelBytes.duplicate()
            val runtime = runtimeFactory(runtimeBytes)
            return try {
                MetricDepthTensorBackend(manifest, runtime, outputSpec, outputTransform,
                    minDepthMeters, maxDepthMeters, maxRelativeDiscontinuity, swapRedBlue)
            } catch (error: Exception) {
                try {
                    runtime.close()
                } catch (closeError: Exception) {
                    error.addSuppressed(closeError)
                }
                throw error
            }
        }
    }
}


/** Verified single-output semantic detector with bounded reusable raw tensors. */
class SemanticDetectionTensorBackend private constructor(
    override val manifest: ModelManifest,
    private val runtime: SingleInputTensorRuntime,
    private val outputSpec: DetectionTensorSpec,
    private val postprocessOptions: DetectionPostprocessOptions,
    private val dynamicClassIds: Set<Long>,
    private val projectionSurfaces: Map<Long, SemanticProjectionSurface>,
    private val swapRedBlue: Boolean,
) : OnDeviceInferenceBackend {
    private val closed = AtomicBoolean(false)
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val outputBytes: Int

    init {
        require(InferenceTask.SEMANTIC_DETECTION in manifest.tasks)
        require(manifest.quantization != TensorQuantization.FLOAT16)
        require(manifest.inputChannels == 1 || manifest.inputChannels == 3)
        require(dynamicClassIds.all { manifest.labels[it] != null })
        require(projectionSurfaces.keys.all { manifest.labels[it] != null })
        val inputElements = manifest.inputWidth.toLong() * manifest.inputHeight *
            manifest.inputChannels
        val inputElementBytes = if (manifest.quantization == TensorQuantization.FLOAT32) 4L else 1L
        val inputBytes = Math.multiplyExact(inputElements, inputElementBytes)
        require(inputBytes in 1L..MAX_TENSOR_BYTES.toLong())
        val outputElements = Math.multiplyExact(outputSpec.rowStrideElements.toLong(),
            outputSpec.rows.toLong())
        val calculatedOutputBytes = Math.multiplyExact(outputElements,
            outputSpec.encoding.bytesPerElement.toLong())
        require(calculatedOutputBytes in 1L..MAX_TENSOR_BYTES.toLong())
        outputBytes = calculatedOutputBytes.toInt()
        inputBuffer = ByteBuffer.allocateDirect(inputBytes.toInt()).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
    }

    @Synchronized
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed.get()) { "Semantic-detection backend is closed" }
        val preprocessed = ArKernelTensorPreprocessor.preprocessInto(frame, manifest,
            inputBuffer, swapRedBlue)
        val runtimeInput = preprocessed.bytes.duplicate().order(ByteOrder.nativeOrder())
        runtimeInput.position(0)
        val runtimeOutput = outputBuffer.duplicate().order(ByteOrder.nativeOrder())
        runtimeOutput.clear()
        runtimeOutput.limit(outputBytes)
        val written = runtime.execute(runtimeInput, runtimeOutput)
        require(written == outputBytes) {
            "Tensor runtime returned $written bytes; expected $outputBytes"
        }
        val tensor = outputBuffer.duplicate().order(ByteOrder.nativeOrder())
        tensor.position(0)
        tensor.limit(outputBytes)
        val detections = ArKernelDetectionPostprocessor.decodeForFrame(tensor, outputSpec,
            frame, manifest, postprocessOptions).map { detection ->
            detection.copy(dynamic = detection.classId in dynamicClassIds,
                projectionSurface = projectionSurfaces[detection.classId] ?:
                    SemanticProjectionSurface.ANY)
        }
        return OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera,
            detections = detections)
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.close()
    }

    companion object {
        private const val MAX_TENSOR_BYTES = 64 * 1024 * 1024

        fun createVerified(
            manifest: ModelManifest,
            modelBytes: ByteBuffer,
            runtimeFactory: (ByteBuffer) -> SingleInputTensorRuntime,
            outputSpec: DetectionTensorSpec,
            postprocessOptions: DetectionPostprocessOptions = DetectionPostprocessOptions(),
            dynamicClassIds: Set<Long> = emptySet(),
            projectionSurfaces: Map<Long, SemanticProjectionSurface> = emptyMap(),
            swapRedBlue: Boolean = false,
        ): SemanticDetectionTensorBackend {
            require(manifest.verifyModelBytes(modelBytes)) { "Model byte size or SHA-256 mismatch" }
            val runtime = runtimeFactory(modelBytes.duplicate())
            return try {
                SemanticDetectionTensorBackend(manifest, runtime, outputSpec,
                    postprocessOptions, dynamicClassIds.toSet(), projectionSurfaces.toMap(),
                    swapRedBlue)
            } catch (error: Exception) {
                try {
                    runtime.close()
                } catch (closeError: Exception) {
                    error.addSuppressed(closeError)
                }
                throw error
            }
        }
    }
}


/** One verified multi-head invocation producing aligned metric depth and semantic detections. */
class DepthAndDetectionTensorBackend private constructor(
    override val manifest: ModelManifest,
    private val runtime: MultiOutputTensorRuntime,
    private val depthOutputSpec: DepthTensorSpec,
    private val detectionOutputSpec: DetectionTensorSpec,
    private val detectionOptions: DetectionPostprocessOptions,
    private val depthTransform: DepthTensorTransform,
    private val minDepthMeters: Double,
    private val maxDepthMeters: Double,
    private val maxRelativeDiscontinuity: Double,
    private val dynamicClassIds: Set<Long>,
    private val projectionSurfaces: Map<Long, SemanticProjectionSurface>,
    private val swapRedBlue: Boolean,
) : OnDeviceInferenceBackend {
    private val closed = AtomicBoolean(false)
    private val inputBuffer: ByteBuffer
    private val depthOutputBuffer: ByteBuffer
    private val detectionOutputBuffer: ByteBuffer
    private val depthOutputBytes: Int
    private val detectionOutputBytes: Int

    init {
        require(InferenceTask.METRIC_DEPTH in manifest.tasks)
        require(InferenceTask.SEMANTIC_DETECTION in manifest.tasks)
        require(manifest.quantization != TensorQuantization.FLOAT16)
        require(manifest.inputChannels == 1 || manifest.inputChannels == 3)
        require(minDepthMeters.isFinite() && maxDepthMeters.isFinite() &&
            minDepthMeters > 0.0 && maxDepthMeters > minDepthMeters)
        require(maxRelativeDiscontinuity.isFinite() && maxRelativeDiscontinuity in 0.0..10.0)
        require(dynamicClassIds.all { manifest.labels[it] != null })
        require(projectionSurfaces.keys.all { manifest.labels[it] != null })
        val inputElements = manifest.inputWidth.toLong() * manifest.inputHeight *
            manifest.inputChannels
        val inputElementBytes = if (manifest.quantization == TensorQuantization.FLOAT32) 4L else 1L
        val inputBytes = Math.multiplyExact(inputElements, inputElementBytes)
        depthOutputBytes = checkedTensorBytes(depthOutputSpec.rowStrideElements,
            depthOutputSpec.height, depthOutputSpec.encoding.bytesPerElement)
        detectionOutputBytes = checkedTensorBytes(detectionOutputSpec.rowStrideElements,
            detectionOutputSpec.rows, detectionOutputSpec.encoding.bytesPerElement)
        require(inputBytes in 1L..MAX_TENSOR_BYTES.toLong())
        require(depthOutputBytes.toLong() + detectionOutputBytes <= MAX_TOTAL_OUTPUT_BYTES)
        inputBuffer = ByteBuffer.allocateDirect(inputBytes.toInt()).order(ByteOrder.nativeOrder())
        depthOutputBuffer = ByteBuffer.allocateDirect(depthOutputBytes).order(ByteOrder.nativeOrder())
        detectionOutputBuffer = ByteBuffer.allocateDirect(detectionOutputBytes)
            .order(ByteOrder.nativeOrder())
    }

    @Synchronized
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed.get()) { "Combined tensor backend is closed" }
        val preprocessed = ArKernelTensorPreprocessor.preprocessInto(frame, manifest,
            inputBuffer, swapRedBlue)
        val runtimeInput = preprocessed.bytes.duplicate().order(ByteOrder.nativeOrder())
        runtimeInput.position(0)
        val depthOutput = depthOutputBuffer.duplicate().order(ByteOrder.nativeOrder())
        depthOutput.clear()
        depthOutput.limit(depthOutputBytes)
        val detectionOutput = detectionOutputBuffer.duplicate().order(ByteOrder.nativeOrder())
        detectionOutput.clear()
        detectionOutput.limit(detectionOutputBytes)
        val written = runtime.execute(runtimeInput, listOf(depthOutput, detectionOutput))
        require(written.size == 2 && written[0] == depthOutputBytes &&
            written[1] == detectionOutputBytes) {
            "Multi-output runtime returned incomplete or mismatched outputs"
        }
        val depthTensor = depthOutputBuffer.duplicate().order(ByteOrder.nativeOrder())
        depthTensor.position(0)
        depthTensor.limit(depthOutputBytes)
        val metricDepth = ArKernelDepthPostprocessor.postprocessForFrame(depthTensor,
            depthOutputSpec, frame, manifest, depthTransform, minDepthMeters, maxDepthMeters,
            maxRelativeDiscontinuity)
        val detectionTensor = detectionOutputBuffer.duplicate().order(ByteOrder.nativeOrder())
        detectionTensor.position(0)
        detectionTensor.limit(detectionOutputBytes)
        val detections = ArKernelDetectionPostprocessor.decodeForFrame(detectionTensor,
            detectionOutputSpec, frame, manifest, detectionOptions).map { detection ->
            detection.copy(dynamic = detection.classId in dynamicClassIds,
                projectionSurface = projectionSurfaces[detection.classId] ?:
                    SemanticProjectionSurface.ANY)
        }
        return OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera,
            detections, metricDepthBuffer = metricDepth)
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.close()
    }

    companion object {
        private const val MAX_TENSOR_BYTES = 64 * 1024 * 1024
        private const val MAX_TOTAL_OUTPUT_BYTES = 96L * 1024L * 1024L

        private fun checkedTensorBytes(stride: Int, rows: Int, elementBytes: Int): Int {
            val elements = Math.multiplyExact(stride.toLong(), rows.toLong())
            val bytes = Math.multiplyExact(elements, elementBytes.toLong())
            require(bytes in 1L..MAX_TENSOR_BYTES.toLong())
            return bytes.toInt()
        }

        fun createVerified(
            manifest: ModelManifest,
            modelBytes: ByteBuffer,
            runtimeFactory: (ByteBuffer) -> MultiOutputTensorRuntime,
            depthOutputSpec: DepthTensorSpec,
            detectionOutputSpec: DetectionTensorSpec,
            detectionOptions: DetectionPostprocessOptions = DetectionPostprocessOptions(),
            depthTransform: DepthTensorTransform = DepthTensorTransform.DIRECT,
            minDepthMeters: Double = 0.05,
            maxDepthMeters: Double = 20.0,
            maxRelativeDiscontinuity: Double = 0.10,
            dynamicClassIds: Set<Long> = emptySet(),
            projectionSurfaces: Map<Long, SemanticProjectionSurface> = emptyMap(),
            swapRedBlue: Boolean = false,
        ): DepthAndDetectionTensorBackend {
            require(manifest.verifyModelBytes(modelBytes)) { "Model byte size or SHA-256 mismatch" }
            val runtime = runtimeFactory(modelBytes.duplicate())
            return try {
                DepthAndDetectionTensorBackend(manifest, runtime, depthOutputSpec,
                    detectionOutputSpec, detectionOptions, depthTransform, minDepthMeters,
                    maxDepthMeters, maxRelativeDiscontinuity, dynamicClassIds.toSet(),
                    projectionSurfaces.toMap(), swapRedBlue)
            } catch (error: Exception) {
                try {
                    runtime.close()
                } catch (closeError: Exception) {
                    error.addSuppressed(closeError)
                }
                throw error
            }
        }
    }
}
