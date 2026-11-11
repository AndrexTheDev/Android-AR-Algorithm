package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArKernelRetentionUiCompositionTest {
    private class FakeSink(
        override val authenticatedEncryption: Boolean,
        var clearResult: Boolean = true,
        var keyResult: Boolean = true,
    ) : ArKernelRetentionActionSink {
        val clearCalls = mutableListOf<Boolean>()
        var keyDeletions = 0

        override fun clearFrames(deleteKey: Boolean): Boolean {
            clearCalls.add(deleteKey)
            return clearResult
        }

        override fun deleteEncryptionKey(): Boolean {
            keyDeletions += 1
            return keyResult
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun defaultPolicyKeepsFramesAndDoesNotTouchSink() {
        val sink = FakeSink(authenticatedEncryption = true)
        val composition = ArKernelRetentionUiComposition(sink)
        assertEquals(ReconstructionFrameRetentionMode.KEEP, composition.policy().mode)

        val published = composition.afterPublication(complete = true)
        assertFalse(published.deleteFrames)
        assertFalse(published.deleteEncryptionKey)
        assertTrue(composition.apply(published))

        val release = composition.release()
        assertFalse(release.deleteFrames)
        assertFalse(release.deleteEncryptionKey)
        assertTrue(composition.apply(release))

        assertEquals(emptyList<Boolean>(), sink.clearCalls)
        assertEquals(0, sink.keyDeletions)
    }

    @Test
    fun confirmedPublicationPolicyDeletesFramesAndKeyExplicitly() {
        val sink = FakeSink(authenticatedEncryption = true)
        val composition = ArKernelRetentionUiComposition(sink)
        val confirmed = composition.confirm(
            ReconstructionFrameRetentionPolicy(
                ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                deleteEncryptionKey = true))
        assertEquals(ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION, confirmed.mode)
        assertTrue(confirmed.deleteEncryptionKey)

        val successful = composition.afterPublication(complete = true)
        assertTrue(successful.deleteFrames)
        assertTrue(successful.deleteEncryptionKey)
        assertTrue(composition.apply(successful))
        assertEquals(listOf(true), sink.clearCalls)

        val incomplete = composition.afterPublication(complete = false)
        assertFalse(incomplete.deleteFrames)
        assertFalse(incomplete.deleteEncryptionKey)
        assertTrue(composition.apply(incomplete))
        assertEquals(listOf(true), sink.clearCalls)
    }

    @Test
    fun releaseDeletesFramesForDestructiveModes() {
        val sink = FakeSink(authenticatedEncryption = true)
        val composition = ArKernelRetentionUiComposition(sink)
        composition.confirm(
            ReconstructionFrameRetentionPolicy(
                ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE,
                deleteEncryptionKey = false))

        assertFalse(composition.afterPublication(complete = true).deleteFrames)
        val release = composition.release()
        assertTrue(release.deleteFrames)
        assertFalse(release.deleteEncryptionKey)
        assertTrue(composition.apply(release))
        assertEquals(listOf(false), sink.clearCalls)
    }

    @Test
    fun keyOnlyActionUsesExplicitKeyDeletion() {
        val sink = FakeSink(authenticatedEncryption = true)
        val composition = ArKernelRetentionUiComposition(sink)
        val action = ReconstructionFrameRetentionAction(
            deleteFrames = false,
            deleteEncryptionKey = true,
            reason = "host explicitly deleted the key")
        assertTrue(composition.apply(action))
        assertEquals(emptyList<Boolean>(), sink.clearCalls)
        assertEquals(1, sink.keyDeletions)
    }

    @Test
    fun sinkFailurePropagatesWithoutSilentSuccess() {
        val sink = FakeSink(authenticatedEncryption = true, clearResult = false)
        val composition = ArKernelRetentionUiComposition(sink)
        composition.confirm(
            ReconstructionFrameRetentionPolicy(
                ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                deleteEncryptionKey = true))
        assertFalse(composition.apply(composition.afterPublication(complete = true)))
        assertEquals(listOf(true), sink.clearCalls)
    }

    @Test
    fun keyDeletionWithoutEncryptionFailsClosed() {
        val sink = FakeSink(authenticatedEncryption = false)
        val composition = ArKernelRetentionUiComposition(sink)
        assertFails {
            composition.confirm(
                ReconstructionFrameRetentionPolicy(
                    ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE,
                    deleteEncryptionKey = true))
        }
        assertEquals(ReconstructionFrameRetentionMode.KEEP, composition.policy().mode)
        assertFalse(composition.afterPublication(complete = true).deleteFrames)
        assertEquals(emptyList<Boolean>(), sink.clearCalls)
    }

    @Test
    fun keepWithKeyDeletionFailsClosed() {
        assertFails {
            ReconstructionFrameRetentionPolicy(
                ReconstructionFrameRetentionMode.KEEP,
                deleteEncryptionKey = true)
        }
    }

    @Test
    fun initialPolicyIsFailClosed() {
        assertFails {
            ArKernelRetentionUiComposition(
                FakeSink(authenticatedEncryption = false),
                ReconstructionFrameRetentionPolicy(
                    ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE,
                    deleteEncryptionKey = true))
        }
    }
}
