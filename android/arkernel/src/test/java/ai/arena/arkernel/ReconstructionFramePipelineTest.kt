package ai.arena.arkernel

import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionFramePipelineTest {
    @Test
    fun packsPaddedAndInterleavedYuvPlanesToI420WithoutChangingPositions() {
        val yBuffer = ByteBuffer.wrap(byteArrayOf(
            1, 2, 3, 4, 99, 99,
            5, 6, 7, 8, 99, 99,
        )).apply { position(0); limit(10) }
        val uBuffer = ByteBuffer.wrap(byteArrayOf(9, 77, 10))
        val vBuffer = ByteBuffer.wrap(byteArrayOf(11, 88, 12))
        val packed = ReconstructionI420Packer.pack(4, 2,
            ReconstructionYuvPlane(yBuffer, 6, 1),
            ReconstructionYuvPlane(uBuffer, 4, 2),
            ReconstructionYuvPlane(vBuffer, 4, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), packed)
        assertEquals(0, yBuffer.position())
        assertEquals(0, uBuffer.position())
        val decoded = ReconstructionFrameCodec.decode(
            ReconstructionFrameCodec.encode(frame(9, packed)))
        assertEquals(ReconstructionImageEncoding.I420, decoded.metadata.encoding)
        assertArrayEquals(packed, decoded.imageBytes)
    }

    @Test
    fun rejectsOddDimensionsTruncatedPlanesAndOutputBudget() {
        val plane = ReconstructionYuvPlane(ByteBuffer.allocate(16), 4, 1)
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionI420Packer.pack(3, 2, plane, plane, plane)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionI420Packer.pack(4, 4,
                ReconstructionYuvPlane(ByteBuffer.allocate(4), 4, 1), plane, plane)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionI420Packer.pack(4, 2, plane, plane, plane, maxOutputBytes = 11)
        }
    }

    @Test
    fun boundedWriterCopiesOwnershipDropsOnBackpressureAndDrains() {
        val enteredSink = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val stored = Collections.synchronizedList(mutableListOf<ArchivedReconstructionFrame>())
        val writer = BoundedReconstructionFrameWriter(capacity = 1, sink = { frame ->
            enteredSink.countDown()
            assertTrue(releaseSink.await(5, TimeUnit.SECONDS))
            stored += frame
            "arkf://test/${frame.metadata.frameId}"
        })
        val bytes = ByteArray(12) { it.toByte() }
        assertTrue(writer.trySubmit(frame(1, bytes)))
        assertTrue(enteredSink.await(5, TimeUnit.SECONDS))
        bytes[0] = 99
        assertTrue(writer.trySubmit(frame(2, ByteArray(12) { 2 })))
        assertFalse(writer.trySubmit(frame(3, ByteArray(12) { 3 })))
        releaseSink.countDown()
        writer.close()
        assertEquals(2, stored.size)
        assertEquals(0, stored[0].imageBytes[0].toInt())
        assertFalse(writer.trySubmit(frame(4, ByteArray(12))))
    }

    private fun frame(id: Long, bytes: ByteArray): ArchivedReconstructionFrame =
        ArchivedReconstructionFrame(ReconstructionFrameMetadata(
            frameId = id,
            timestampNs = 1000 + id,
            width = 4,
            height = 2,
            rowStrideBytes = 4,
            encoding = ReconstructionImageEncoding.I420,
            worldFromCamera = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            translationSigmaMeters = 0.01,
            rotationSigmaDegrees = 0.2,
        ), bytes)
}
