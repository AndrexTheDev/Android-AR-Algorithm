package ai.arena.arkernel

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraFrameQualityTest {
    @Test
    fun sharpPatternScoresHigherThanFlatImage() {
        val flat = ByteBuffer.wrap(ByteArray(64 * 48) { 128.toByte() })
        val checker = ByteBuffer.wrap(ByteArray(64 * 48) { index ->
            val x = index % 64; val y = index / 64
            if ((x / 2 + y / 2) % 2 == 0) 20.toByte() else 235.toByte()
        })
        val flatQuality = CameraFrameQualityEvaluator.evaluate(flat, 64, 48, 64, sampleStep = 2)
        val sharpQuality = CameraFrameQualityEvaluator.evaluate(checker, 64, 48, 64, sampleStep = 2)
        assertTrue(sharpQuality.sharpness > flatQuality.sharpness)
        assertEquals(0.0, flatQuality.clippedPixelRatio, 0.0)
        assertEquals(0, flat.position())
    }

    @Test
    fun reportsClippedPixelsAndRespectsPaddingAndBufferPosition() {
        val bytes = ByteArray(6 * 5 + 3) { 100.toByte() }
        for (row in 0 until 5) for (column in 0 until 4)
            bytes[3 + row * 6 + column] = if (column < 2) 0 else 255.toByte()
        val buffer = ByteBuffer.wrap(bytes).apply { position(3) }
        val quality = CameraFrameQualityEvaluator.evaluate(buffer, 4, 5, 6, sampleStep = 1)
        assertEquals(1.0, quality.clippedPixelRatio, 0.0)
        assertEquals(3, buffer.position())
    }

    @Test
    fun rejectsTruncatedPlane() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraFrameQualityEvaluator.evaluate(ByteBuffer.allocate(8), 4, 4, 4)
        }
    }
}
