package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionFrameRetentionPolicyTest {
    @Test
    fun defaultPolicyKeepsFramesAndKey() {
        val policy = ReconstructionFrameRetentionPolicy()
        assertEquals(ReconstructionFrameRetentionMode.KEEP, policy.mode)
        assertFalse(policy.deleteEncryptionKey)
    }

    @Test
    fun rejectsKeepingFramesWhileDeletingTheirKey() {
        assertFails {
            ReconstructionFrameRetentionPolicy(
                mode = ReconstructionFrameRetentionMode.KEEP,
                deleteEncryptionKey = true)
        }
    }

    @Test
    fun aadBindsSessionFileAndPayloadKind() {
        val colour = ReconstructionFrameAeadCodec.aad("session", "0000000000000000010-0000000000000000001.arkf",
            ReconstructionFrameAeadCodec.KIND_COLOUR)
        val same = ReconstructionFrameAeadCodec.aad("session", "0000000000000000010-0000000000000000001.arkf",
            ReconstructionFrameAeadCodec.KIND_COLOUR)
        val depth = ReconstructionFrameAeadCodec.aad("session", "0000000000000000010-0000000000000000001.arkd",
            ReconstructionFrameAeadCodec.KIND_DEPTH)
        val otherSession = ReconstructionFrameAeadCodec.aad("other", "0000000000000000010-0000000000000000001.arkf",
            ReconstructionFrameAeadCodec.KIND_COLOUR)
        assertTrue(colour.contentEquals(same))
        assertFalse(colour.contentEquals(depth))
        assertFalse(colour.contentEquals(otherSession))
    }

    @Test
    fun explicitModesAreRepresented() {
        assertEquals(ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
            ReconstructionFrameRetentionPolicy(
                mode = ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                deleteEncryptionKey = true).mode)
        assertTrue(ReconstructionFrameRetentionPolicy(
            mode = ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE,
            deleteEncryptionKey = true).deleteEncryptionKey)
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
