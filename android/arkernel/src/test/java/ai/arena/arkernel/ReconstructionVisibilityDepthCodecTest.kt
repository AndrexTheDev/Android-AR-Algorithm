package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionVisibilityDepthCodecTest {
    @Test
    fun roundTripsCanonicalOptionalPlanesAndDetectsCorruption() {
        val depth = ArchivedReconstructionVisibilityDepth(7, 70, 2, 2,
            floatArrayOf(1f, Float.NaN, 2f, 3f), floatArrayOf(1f, 0f, 0.5f, 1f),
            floatArrayOf(0.01f, 0.02f, 0.03f, 0.04f))
        val encoded = ReconstructionVisibilityDepthCodec.encode(depth)
        val decoded = ReconstructionVisibilityDepthCodec.decode(encoded)
        assertEquals(7L, decoded.frameId)
        assertTrue(decoded.depthMeters[1].isNaN())
        assertArrayEquals(requireNotNull(depth.confidence), requireNotNull(decoded.confidence), 0f)
        assertArrayEquals(requireNotNull(depth.uncertaintyMeters),
            requireNotNull(decoded.uncertaintyMeters), 0f)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertFails { ReconstructionVisibilityDepthCodec.decode(encoded) }
    }

    @Test
    fun copiesStridedBuffersFromTheirCurrentPositions() {
        fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(1) }
        val depth = MetricDepthBuffer(2, 2, 3,
            buffer(floatArrayOf(99f, 1f, 2f, 50f, 3f, 4f)), timestampNs = 80)
        val copied = ReconstructionVisibilityDepthCodec.copyFrom(8, depth)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), copied.depthMeters, 0f)
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
