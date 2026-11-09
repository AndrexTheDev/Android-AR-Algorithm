package ai.arena.arkernel

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Bounds compressed-image decoding before pixels cross into the portable baker. */
data class ReconstructionTextureDecodeOptions(
    val maxPixels: Int = 16_777_216,
    val maxCompressedBytes: Int = 64 * 1024 * 1024,
    val maxRgbaBytes: Int = 64 * 1024 * 1024,
) {
    init {
        require(maxPixels in 1..67_108_864)
        require(maxCompressedBytes in 1..256 * 1024 * 1024)
        require(maxRgbaBytes in 4..256 * 1024 * 1024)
    }
}

data class DecodedReconstructionTextureSource(
    val frameId: Long,
    val width: Int,
    val height: Int,
    /** Tightly packed top-left-origin straight-alpha RGBA8. */
    val rgba8: ByteArray,
    val sourceEncoding: ReconstructionImageEncoding,
    val compressedBytes: Int,
)

/** Android codec adapter for archived JPEG/PNG texture sources. */
object ReconstructionTextureSourceDecoder {
    fun decodeCompressed(
        frame: ArchivedReconstructionFrame,
        options: ReconstructionTextureDecodeOptions = ReconstructionTextureDecodeOptions(),
    ): DecodedReconstructionTextureSource {
        val metadata = frame.metadata
        require(metadata.encoding == ReconstructionImageEncoding.JPEG ||
            metadata.encoding == ReconstructionImageEncoding.PNG) {
            "Only archived JPEG/PNG frames require platform decoding"
        }
        require(frame.imageBytes.isNotEmpty() &&
            frame.imageBytes.size <= options.maxCompressedBytes) {
            "Compressed texture source exceeds byte budget"
        }
        val expectedPixels = metadata.width.toLong() * metadata.height.toLong()
        require(expectedPixels <= options.maxPixels)
        require(expectedPixels * 4L <= options.maxRgbaBytes)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.imageBytes, 0, frame.imageBytes.size, bounds)
        require(bounds.outWidth == metadata.width && bounds.outHeight == metadata.height) {
            "Compressed texture dimensions do not match archived metadata"
        }
        val expectedMime = if (metadata.encoding == ReconstructionImageEncoding.PNG) {
            "image/png"
        } else {
            "image/jpeg"
        }
        require(bounds.outMimeType == expectedMime) {
            "Compressed texture encoding does not match archived metadata"
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(
            frame.imageBytes, 0, frame.imageBytes.size, decodeOptions)) {
            "Android could not decode archived texture source"
        }
        try {
            require(bitmap.width == metadata.width && bitmap.height == metadata.height) {
                "Decoded texture dimensions changed unexpectedly"
            }
            val pixels = IntArray(expectedPixels.toInt())
            bitmap.getPixels(pixels, 0, metadata.width, 0, 0, metadata.width, metadata.height)
            val rgba = ByteArray(Math.multiplyExact(pixels.size, 4))
            var output = 0
            pixels.forEach { argb ->
                rgba[output] = (argb ushr 16).toByte()
                rgba[output + 1] = (argb ushr 8).toByte()
                rgba[output + 2] = argb.toByte()
                rgba[output + 3] = (argb ushr 24).toByte()
                output += 4
            }
            return DecodedReconstructionTextureSource(metadata.frameId, metadata.width,
                metadata.height, rgba, metadata.encoding, frame.imageBytes.size)
        } finally {
            bitmap.recycle()
        }
    }
}
