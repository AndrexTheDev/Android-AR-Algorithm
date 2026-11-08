package ai.arena.arkernel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconstructionPoseManifestCodecTest {
    private fun pose(id: Long, timestamp: Long) = OptimizedReconstructionPose(
        frameId = id,
        timestampNs = timestamp,
        worldFromCamera = doubleArrayOf(1.0, 0.0, 0.0, 0.0, id.toDouble(), 2.0, 3.0),
        translationSigmaMeters = 0.003,
        rotationSigmaDegrees = 0.15,
    )

    @Test
    fun roundTripsDeterministicallyAndSortsByFrameId() {
        val manifest = ReconstructionPoseManifest(4, listOf(pose(9, 900), pose(2, 200)))
        val encoded = ReconstructionPoseManifestCodec.encode(manifest)
        assertArrayEquals(encoded, ReconstructionPoseManifestCodec.encode(manifest))
        val decoded = ReconstructionPoseManifestCodec.decode(encoded)
        assertEquals(4, decoded.generation)
        assertEquals(listOf(2L, 9L), decoded.poses.map { it.frameId })
        assertEquals(0.003, decoded.poses.first().translationSigmaMeters, 0.0)
        assertArrayEquals(pose(2, 200).worldFromCamera, decoded.poses.first().worldFromCamera, 0.0)
    }

    @Test
    fun rejectsCorruptionDuplicateIdentityAndInvalidPose() {
        val encoded = ReconstructionPoseManifestCodec.encode(
            ReconstructionPoseManifest(1, listOf(pose(1, 100))))
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionPoseManifestCodec.decode(encoded)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionPoseManifest(2, listOf(pose(1, 100), pose(1, 200)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            pose(3, 300).copy(worldFromCamera = DoubleArray(7))
        }
    }

    @Test
    fun rejectsTruncationAndUnversionedManifest() {
        val encoded = ReconstructionPoseManifestCodec.encode(
            ReconstructionPoseManifest(1, listOf(pose(1, 100), pose(2, 200))))
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionPoseManifestCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconstructionPoseManifest(0, listOf(pose(1, 100)))
        }
    }
}
