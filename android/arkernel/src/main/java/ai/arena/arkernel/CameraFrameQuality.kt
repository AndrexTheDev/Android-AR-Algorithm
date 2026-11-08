package ai.arena.arkernel

import java.nio.ByteBuffer
import kotlin.math.min

/** Cheap pre-copy quality metrics in normalized [0,1] ranges. */
data class CameraFrameQuality(
    val sharpness: Double,
    val clippedPixelRatio: Double,
    val sampledPixels: Int,
)

object CameraFrameQualityEvaluator {
    fun evaluate(
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        pixelStrideBytes: Int = 1,
        sampleStep: Int = 4,
        maxSamples: Int = 250_000,
    ): CameraFrameQuality {
        require(width >= 3 && height >= 3 && rowStrideBytes > 0 && pixelStrideBytes > 0)
        require(sampleStep > 0 && maxSamples > 0)
        val required = Math.addExact(Math.multiplyExact(height - 1, rowStrideBytes),
            Math.addExact(Math.multiplyExact(width - 1, pixelStrideBytes), 1))
        require(required <= yPlane.remaining()) { "Y plane is truncated" }
        val source = yPlane.duplicate()
        val start = source.position()
        var samples = 0
        var clipped = 0
        var laplacianEnergy = 0.0
        val adaptiveStep = maxOf(sampleStep,
            kotlin.math.sqrt((width.toLong() * height / maxSamples.toDouble())).toInt())
        var y = 1
        while (y < height - 1 && samples < maxSamples) {
            var x = 1
            while (x < width - 1 && samples < maxSamples) {
                fun value(px: Int, py: Int): Int = source.get(
                    start + py * rowStrideBytes + px * pixelStrideBytes).toInt() and 0xff
                val center = value(x, y)
                if (center <= CLIP_LOW || center >= CLIP_HIGH) clipped++
                val laplacian = 4 * center - value(x - 1, y) - value(x + 1, y) -
                    value(x, y - 1) - value(x, y + 1)
                laplacianEnergy += laplacian.toDouble() * laplacian
                samples++; x += adaptiveStep
            }
            y += adaptiveStep
        }
        require(samples > 0) { "No image quality samples" }
        // The scale maps a useful 8-bit Laplacian RMS range to [0,1]. It is a
        // deterministic capture gate, not a calibrated optical MTF measurement.
        val rms = kotlin.math.sqrt(laplacianEnergy / samples)
        return CameraFrameQuality(min(1.0, rms / SHARPNESS_RMS_FULL_SCALE),
            clipped.toDouble() / samples, samples)
    }

    private const val CLIP_LOW = 5
    private const val CLIP_HIGH = 250
    private const val SHARPNESS_RMS_FULL_SCALE = 64.0
}
