package ai.arena.arkernel

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Bounds the temporary ARGB conversion and encoded output allocation. */
data class ReconstructionTexturePngOptions(
    val maxPixels: Int = 16_777_216,
    val maxEncodedBytes: Int = 128 * 1024 * 1024,
) {
    init {
        require(maxPixels in 1..67_108_864)
        require(maxEncodedBytes in 1..512 * 1024 * 1024)
    }
}

data class EncodedReconstructionTexture(
    val width: Int,
    val height: Int,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Android platform adapter from the core's tightly packed, top-left-origin,
 * straight-alpha RGBA8 atlas to lossless PNG. The core deliberately owns no
 * mandatory image-codec dependency.
 */
object ReconstructionTexturePngEncoder {
    fun encodeRgba8(
        width: Int,
        height: Int,
        rgba8: ByteArray,
        options: ReconstructionTexturePngOptions = ReconstructionTexturePngOptions(),
    ): EncodedReconstructionTexture {
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height.toLong()
        require(pixels <= options.maxPixels)
        require(pixels * 4L == rgba8.size.toLong()) {
            "RGBA8 payload size does not match dimensions"
        }
        val argb = IntArray(pixels.toInt())
        var source = 0
        for (index in argb.indices) {
            val red = rgba8[source].toInt() and 0xff
            val green = rgba8[source + 1].toInt() and 0xff
            val blue = rgba8[source + 2].toInt() and 0xff
            val alpha = rgba8[source + 3].toInt() and 0xff
            argb[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            source += 4
        }
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        try {
            val output = BoundedByteArrayOutputStream(options.maxEncodedBytes)
            val encoded = try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } catch (limit: EncodingLimitExceededException) {
                throw IllegalStateException("Encoded PNG exceeds byte budget", limit)
            }
            check(encoded) { "Android PNG encoder rejected texture" }
            return EncodedReconstructionTexture(width, height, "image/png", output.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    private class EncodingLimitExceededException : RuntimeException()

    private class BoundedByteArrayOutputStream(private val maximumBytes: Int) : OutputStream() {
        private val delegate = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
        private var count = 0

        override fun write(value: Int) {
            ensureCapacity(1)
            delegate.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            ensureCapacity(length)
            delegate.write(bytes, offset, length)
            count += length
        }

        fun toByteArray(): ByteArray = delegate.toByteArray()

        private fun ensureCapacity(additionalBytes: Int) {
            if (additionalBytes > maximumBytes - count) throw EncodingLimitExceededException()
        }
    }
}
