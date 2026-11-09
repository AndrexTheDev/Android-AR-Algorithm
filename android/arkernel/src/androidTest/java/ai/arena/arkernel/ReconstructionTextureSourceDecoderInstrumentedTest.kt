package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionTextureSourceDecoderInstrumentedTest {
    @Test
    fun decodesArchivedPngToCanonicalTopLeftRgba() {
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
            0, 0, 255.toByte(), 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(),
        )
        val png = ReconstructionTexturePngEncoder.encodeRgba8(2, 2, rgba)
        val decoded = ReconstructionTextureSourceDecoder.decodeCompressed(
            ArchivedReconstructionFrame(metadata(), png.bytes))
        assertEquals(1L, decoded.frameId)
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(ReconstructionImageEncoding.PNG, decoded.sourceEncoding)
        assertEquals(png.bytes.size, decoded.compressedBytes)
        assertTrue(decoded.rgba8.contentEquals(rgba))
    }

    @Test
    fun rejectsDimensionEncodingAndPixelBudgetMismatch() {
        val png = ReconstructionTexturePngEncoder.encodeRgba8(2, 2, ByteArray(16))
        assertFails {
            ReconstructionTextureSourceDecoder.decodeCompressed(
                ArchivedReconstructionFrame(metadata(width = 3), png.bytes))
        }
        assertFails {
            ReconstructionTextureSourceDecoder.decodeCompressed(
                ArchivedReconstructionFrame(metadata(encoding = ReconstructionImageEncoding.JPEG),
                    png.bytes))
        }
        assertFails {
            ReconstructionTextureSourceDecoder.decodeCompressed(
                ArchivedReconstructionFrame(metadata(), png.bytes),
                ReconstructionTextureDecodeOptions(maxPixels = 3, maxRgbaBytes = 64))
        }
        assertFails {
            ReconstructionTextureSourceDecoder.decodeCompressed(
                ArchivedReconstructionFrame(metadata(), png.bytes),
                ReconstructionTextureDecodeOptions(maxCompressedBytes = png.bytes.size - 1,
                    maxRgbaBytes = 64))
        }
    }

    private fun metadata(
        width: Int = 2,
        encoding: ReconstructionImageEncoding = ReconstructionImageEncoding.PNG,
    ) = ReconstructionFrameMetadata(
        frameId = 1,
        timestampNs = 2,
        width = width,
        height = 2,
        rowStrideBytes = width,
        encoding = encoding,
        worldFromCamera = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        translationSigmaMeters = 0.01,
        rotationSigmaDegrees = 0.1,
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
