package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassMaskPostprocessorTest {
    @Test
    fun nearestResizesAndHonorsBufferPositionsAndStrides() {
        val tensor = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder())
        tensor.put(0, 99.toByte())
        tensor.put(1, 1.toByte())
        tensor.put(2, 2.toByte())
        tensor.position(1)

        val depthStorage = ByteBuffer.allocateDirect(10 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val confidenceStorage = ByteBuffer.allocateDirect(10 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(10) { depthStorage.put(it, 3.0F); confidenceStorage.put(it, 1.0F) }
        depthStorage.position(1)
        confidenceStorage.position(1)
        val depth = MetricDepthBuffer(4, 2, 5, depthStorage, confidenceStorage, 5, 10)

        val result = ArKernelClassMaskPostprocessor.maskDepth(tensor,
            ClassMaskTensorSpec(2, 1), setOf(2), depth, maxMaskedPixels = 4)

        assertEquals(4, result.maskedPixels)
        assertEquals(8, result.outputPixels)
        for (y in 0 until 2) for (x in 0 until 4) {
            val index = 1 + y * 5 + x
            val expectedDepth = if (x < 2) 3.0F else 0.0F
            val expectedConfidence = if (x < 2) 1.0F else 0.0F
            assertEquals(expectedDepth, depthStorage.get(index), 0.0F)
            assertEquals(expectedConfidence, confidenceStorage.get(index), 0.0F)
        }
        assertEquals(1, tensor.position())
        assertEquals(1, depthStorage.position())
        assertEquals(1, confidenceStorage.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMaskBeyondBudgetBeforeMutatingDepth() {
        val tensor = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
        tensor.put(0, 7.toByte())
        val storage = ByteBuffer.allocateDirect(4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(4) { storage.put(it, 2.0F) }
        val depth = MetricDepthBuffer(2, 2, 2, storage, timestampNs = 1)
        ArKernelClassMaskPostprocessor.maskDepth(tensor, ClassMaskTensorSpec(1, 1),
            setOf(7), depth, maxMaskedPixels = 3)
    }
}
