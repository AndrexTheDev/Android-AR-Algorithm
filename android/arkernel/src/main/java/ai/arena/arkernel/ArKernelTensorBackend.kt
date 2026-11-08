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
