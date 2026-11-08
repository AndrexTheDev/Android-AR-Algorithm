package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import kotlin.math.floor
import kotlin.math.round

enum class ClassMaskTensorEncoding(val bytesPerElement: Int) { FLOAT32(4), INT8(1), UINT8(1) }

data class ClassMaskTensorSpec(
    val width: Int,
    val height: Int,
    val rowStrideElements: Int = width,
    val encoding: ClassMaskTensorEncoding = ClassMaskTensorEncoding.UINT8,
    val quantizationScale: Double = 1.0,
    val quantizationZeroPoint: Int = 0,
) {
    init {
        require(width in 1..4096 && height in 1..4096 && rowStrideElements >= width)
        if (encoding != ClassMaskTensorEncoding.FLOAT32) {
            require(quantizationScale.isFinite() && quantizationScale > 0.0)
            if (encoding == ClassMaskTensorEncoding.INT8)
                require(quantizationZeroPoint in -128..127)
            else require(quantizationZeroPoint in 0..255)
        }
    }
}

data class ClassMaskResult(val maskedPixels: Int, val outputPixels: Int)

/** Applies a nearest-resized class-index mask by zeroing metric depth and confidence in place. */
object ArKernelClassMaskPostprocessor {
    private const val MAX_OUTPUT_PIXELS = 16_000_000

    fun maskDepth(
        classTensor: ByteBuffer,
        spec: ClassMaskTensorSpec,
        dynamicClassIds: Set<Long>,
        depth: MetricDepthBuffer,
        maxMaskedPixels: Int = 4_194_304,
    ): ClassMaskResult {
        require(classTensor.isDirect && classTensor.order() == ByteOrder.nativeOrder())
        require(dynamicClassIds.isNotEmpty() && dynamicClassIds.size <= 256)
        require(dynamicClassIds.all { it in 0..0xffff_ffffL })
        require(maxMaskedPixels > 0)
        require(depth.width > 0 && depth.height > 0 &&
            depth.rowStrideElements >= depth.width && depth.depthMeters.isDirect)
        require(depth.depthMeters.order() == ByteOrder.nativeOrder())
        depth.confidence?.let {
            require(it.isDirect && it.order() == ByteOrder.nativeOrder() &&
                depth.confidenceRowStrideElements >= depth.width)
        }
        val tensorElements = (spec.height.toLong() - 1L) * spec.rowStrideElements + spec.width
        val tensorBytes = Math.multiplyExact(tensorElements, spec.encoding.bytesPerElement.toLong())
        require(tensorBytes <= classTensor.remaining().toLong()) { "Class mask tensor is truncated" }
        val outputPixelsLong = depth.width.toLong() * depth.height
        require(outputPixelsLong in 1L..MAX_OUTPUT_PIXELS.toLong())
        val requiredDepth = (depth.height.toLong() - 1L) * depth.rowStrideElements + depth.width
        require(requiredDepth <= depth.depthMeters.remaining().toLong())
        depth.confidence?.let {
            val requiredConfidence = (depth.height.toLong() - 1L) *
                depth.confidenceRowStrideElements + depth.width
            require(requiredConfidence <= it.remaining().toLong())
        }
        val outputPixels = outputPixelsLong.toInt()
        val mask = BitSet(outputPixels)
        val tensorOffset = classTensor.position()
        for (y in 0 until depth.height) {
            val sourceY = floor((y + 0.5) * spec.height / depth.height).toInt()
                .coerceIn(0, spec.height - 1)
            for (x in 0 until depth.width) {
                val sourceX = floor((x + 0.5) * spec.width / depth.width).toInt()
                    .coerceIn(0, spec.width - 1)
                val element = sourceY * spec.rowStrideElements + sourceX
                val rawClass = when (spec.encoding) {
                    ClassMaskTensorEncoding.FLOAT32 ->
                        classTensor.getFloat(tensorOffset + element * 4).toDouble()
                    ClassMaskTensorEncoding.INT8 ->
                        (classTensor.get(tensorOffset + element).toInt() -
                            spec.quantizationZeroPoint) * spec.quantizationScale
                    ClassMaskTensorEncoding.UINT8 ->
                        ((classTensor.get(tensorOffset + element).toInt() and 0xff) -
                            spec.quantizationZeroPoint) * spec.quantizationScale
                }
                if (!rawClass.isFinite()) continue
                val rounded = round(rawClass)
                if (kotlin.math.abs(rawClass - rounded) > 1e-4 ||
                    rounded < 0.0 || rounded > 0xffff_ffffL.toDouble()) continue
                if (rounded.toLong() in dynamicClassIds) mask.set(y * depth.width + x)
            }
        }
        val maskedPixels = mask.cardinality()
        require(maskedPixels <= maxMaskedPixels) { "Class mask exceeds pixel budget" }
        val depthOffset = depth.depthMeters.position()
        val confidenceOffset = depth.confidence?.position() ?: 0
        var pixel = mask.nextSetBit(0)
        while (pixel >= 0) {
            val x = pixel % depth.width
            val y = pixel / depth.width
            depth.depthMeters.put(depthOffset + y * depth.rowStrideElements + x, 0.0F)
            depth.confidence?.put(confidenceOffset + y * depth.confidenceRowStrideElements + x, 0.0F)
            pixel = mask.nextSetBit(pixel + 1)
        }
        return ClassMaskResult(maskedPixels, outputPixels)
    }
}
