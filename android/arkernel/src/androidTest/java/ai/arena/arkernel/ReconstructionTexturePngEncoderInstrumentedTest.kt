package ai.arena.arkernel

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionTexturePngEncoderInstrumentedTest {
    @Test
    fun encodesTopLeftRgbaAsBoundedLosslessPng() {
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 128.toByte(),
            0, 0, 255.toByte(), 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(), 0,
        )
        val encoded = ReconstructionTexturePngEncoder.encodeRgba8(2, 2, rgba)
        assertEquals("image/png", encoded.mimeType)
        assertTrue(encoded.bytes.take(8).map { it.toInt() and 0xff } ==
            listOf(137, 80, 78, 71, 13, 10, 26, 10))
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(
            encoded.bytes, 0, encoded.bytes.size))
        try {
            assertEquals(2, decoded.width)
            assertEquals(2, decoded.height)
            assertEquals(0xffff0000.toInt(), decoded.getPixel(0, 0))
            assertEquals(0xff0000ff.toInt(), decoded.getPixel(0, 1))
            assertEquals(128, decoded.getPixel(1, 0).ushr(24))
            assertEquals(0, decoded.getPixel(1, 1).ushr(24))
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun rejectsMismatchedInputAndEncodedOutputOverBudget() {
        var mismatched = false
        try {
            ReconstructionTexturePngEncoder.encodeRgba8(2, 2, ByteArray(15))
        } catch (_: IllegalArgumentException) {
            mismatched = true
        }
        assertTrue(mismatched)

        var exceeded = false
        try {
            ReconstructionTexturePngEncoder.encodeRgba8(2, 2, ByteArray(16),
                ReconstructionTexturePngOptions(maxPixels = 4, maxEncodedBytes = 8))
        } catch (_: IllegalStateException) {
            exceeded = true
        }
        assertTrue(exceeded)
    }
}
