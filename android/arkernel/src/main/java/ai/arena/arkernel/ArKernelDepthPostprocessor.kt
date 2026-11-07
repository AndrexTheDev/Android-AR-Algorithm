package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DepthTensorEncoding(val bytesPerElement: Int) { FLOAT32(4), INT8(1), UINT8(1) }
enum class DepthTensorTransform { DIRECT, INVERSE }

data class DepthTensorSpec(
    val width: Int,
    val height: Int,
    val rowStrideElements: Int = width,
    val encoding: DepthTensorEncoding = DepthTensorEncoding.FLOAT32,
    val quantizationScale: Double = 1.0,
    val quantizationZeroPoint: Int = 0,
) {
    init {
        require(width > 0 && height > 0 && rowStrideElements >= width)
        if (encoding != DepthTensorEncoding.FLOAT32) {
            require(quantizationScale.isFinite() && quantizationScale > 0.0)
            if (encoding == DepthTensorEncoding.INT8) require(quantizationZeroPoint in -128..127)
            else require(quantizationZeroPoint in 0..255)
        }
    }
}

data class DepthPostprocessOptions(
    val transform: DepthTensorTransform = DepthTensorTransform.DIRECT,
    val unitToMeters: Double = 1.0,
    val minDepthMeters: Double = 0.05,
    val maxDepthMeters: Double = 20.0,
    val maxRelativeDiscontinuity: Double = 0.10,
) {
    init {
        require(unitToMeters.isFinite() && unitToMeters > 0.0)
        require(minDepthMeters.isFinite() && maxDepthMeters.isFinite() &&
            minDepthMeters > 0.0 && maxDepthMeters > minDepthMeters)
        require(maxRelativeDiscontinuity.isFinite() && maxRelativeDiscontinuity in 0.0..10.0)
    }
}

/** Converts a direct one-channel model tensor into camera-sized metric depth and confidence. */
object ArKernelDepthPostprocessor {
    private const val RESULT_OK = 0
    private const val RESULT_INVALID_ARGUMENT = 1
    private const val MAX_OUTPUT_ELEMENTS = 16_000_000L

    init { System.loadLibrary("arkernel_jni") }

    fun postprocessForFrame(
        tensor: ByteBuffer,
        spec: DepthTensorSpec,
        frame: PerceptionFrame,
        manifest: ModelManifest,
        transform: DepthTensorTransform = DepthTensorTransform.DIRECT,
        minDepthMeters: Double = 0.05,
        maxDepthMeters: Double = 20.0,
    ): MetricDepthBuffer {
        require(InferenceTask.METRIC_DEPTH in manifest.tasks)
        val unitToMeters = requireNotNull(manifest.depthUnitToMeters)
        return postprocess(tensor, spec, frame.calibration.width, frame.calibration.height,
            frame.timestampNs, DepthPostprocessOptions(transform, unitToMeters,
                minDepthMeters, maxDepthMeters))
    }

    fun postprocess(
        tensor: ByteBuffer,
        spec: DepthTensorSpec,
        outputWidth: Int,
        outputHeight: Int,
        timestampNs: Long,
        options: DepthPostprocessOptions = DepthPostprocessOptions(),
    ): MetricDepthBuffer {
        require(tensor.isDirect && tensor.order() == ByteOrder.nativeOrder())
        require(timestampNs > 0 && outputWidth > 0 && outputHeight > 0)
        val inputElements = (spec.height.toLong() - 1L) * spec.rowStrideElements + spec.width
        val inputBytes = inputElements * spec.encoding.bytesPerElement
        require(inputBytes <= tensor.remaining().toLong()) { "Depth tensor is truncated" }
        val outputElements = outputWidth.toLong() * outputHeight
        require(outputElements in 1L..MAX_OUTPUT_ELEMENTS)
        val outputBytes = outputElements * 4L
        require(outputBytes <= Int.MAX_VALUE)
        val depthBytes = ByteBuffer.allocateDirect(outputBytes.toInt()).order(ByteOrder.nativeOrder())
        val confidenceBytes = ByteBuffer.allocateDirect(outputBytes.toInt()).order(ByteOrder.nativeOrder())
        val metadata = LongArray(3)
        val status = nativePostprocess(tensor, tensor.position(), tensor.remaining(), spec.width,
            spec.height, spec.rowStrideElements, spec.encoding.ordinal, spec.quantizationScale,
            spec.quantizationZeroPoint, outputWidth, outputHeight, options.transform.ordinal,
            options.unitToMeters, options.minDepthMeters, options.maxDepthMeters,
            options.maxRelativeDiscontinuity, MAX_OUTPUT_ELEMENTS, depthBytes, confidenceBytes, outputElements.toInt(), metadata)
        require(status != RESULT_INVALID_ARGUMENT) { "Invalid depth tensor or output configuration" }
        check(status == RESULT_OK) { "Native depth postprocessing failed: $status" }
        check(metadata[2] == outputElements)
        return MetricDepthBuffer(metadata[0].toInt(), metadata[1].toInt(), metadata[0].toInt(),
            depthBytes.asFloatBuffer(), confidenceBytes.asFloatBuffer(), metadata[0].toInt(), timestampNs)
    }

    private external fun nativePostprocess(
        tensor: ByteBuffer,
        tensorOffsetBytes: Int,
        tensorSizeBytes: Int,
        width: Int,
        height: Int,
        rowStrideElements: Int,
        elementType: Int,
        quantizationScale: Double,
        quantizationZeroPoint: Int,
        outputWidth: Int,
        outputHeight: Int,
        transform: Int,
        unitToMeters: Double,
        minDepthMeters: Double,
        maxDepthMeters: Double,
        maxRelativeDiscontinuity: Double,
        maxOutputElements: Long,
        depthOutput: ByteBuffer,
        confidenceOutput: ByteBuffer,
        outputCapacityElements: Int,
        metadata: LongArray,
    ): Int
}
