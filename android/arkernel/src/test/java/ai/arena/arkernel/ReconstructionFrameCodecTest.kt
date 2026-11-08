package ai.arena.arkernel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconstructionFrameCodecTest {
    private fun metadata() = ReconstructionFrameMetadata(
        frameId = 7,
        timestampNs = 123_456_789,
        width = 3,
        height = 2,
        rowStrideBytes = 4,
        encoding = ReconstructionImageEncoding.GRAY8,
        exposureTimeNs = 8_000_000,
        sensitivityIso = 200,
        worldFromCamera = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 2.0, 3.0),
        translationSigmaMeters = 0.004,
        rotationSigmaDegrees = 0.2,
    )

    @Test
    fun roundTripsFrameAndReconstructionMetadata() {
        val image = byteArrayOf(1, 2, 3, 99, 4, 5, 6)
        val decoded = ReconstructionFrameCodec.decode(
            ReconstructionFrameCodec.encode(ArchivedReconstructionFrame(metadata(), image)))
        assertEquals(7, decoded.metadata.frameId)
        assertEquals(123_456_789, decoded.metadata.timestampNs)
        assertEquals(8_000_000, decoded.metadata.exposureTimeNs)
        assertEquals(200, decoded.metadata.sensitivityIso)
        assertEquals(0.004, decoded.metadata.translationSigmaMeters, 0.0)
        assertArrayEquals(metadata().worldFromCamera, decoded.metadata.worldFromCamera, 0.0)
        assertArrayEquals(image, decoded.imageBytes)
    }

    @Test
    fun detectsPayloadCorruption() {
        val encoded = ReconstructionFrameCodec.encode(
            ArchivedReconstructionFrame(metadata(), byteArrayOf(1, 2, 3, 0, 4, 5, 6)))
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionFrameCodec.decode(encoded)
        }
    }

    @Test
    fun rejectsTruncatedGrayImageAndInvalidPose() {
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionFrameCodec.encode(
                ArchivedReconstructionFrame(metadata(), byteArrayOf(1, 2, 3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            metadata().copy(worldFromCamera = DoubleArray(7))
        }
    }

    @Test
    fun rejectsForgedLengthBeforeAllocatingPayload() {
        val encoded = ReconstructionFrameCodec.encode(
            ArchivedReconstructionFrame(metadata(), byteArrayOf(1, 2, 3, 0, 4, 5, 6)))
        // Payload length is the penultimate Int in the fixed-size header.
        val payloadLengthOffset = 124
        encoded[payloadLengthOffset] = 0x7f
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionFrameCodec.decode(encoded)
        }
    }
}
