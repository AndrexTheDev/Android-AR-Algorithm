package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionFrameAeadInstrumentedTest {
    @Test
    fun encryptsKeyframesAndFailsClosedOnTamperingOrKeyDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = "aead-test"
        val archive = encryptedArchive(context, sessionId)
        archive.clear(deleteKey = true)
        try {
            assertTrue(archive.authenticatedEncryption)
            val image = byteArrayOf(1, 2, 3, 4)
            val reference = archive.save(ArchivedReconstructionFrame(
                metadata(1, 10), image))
            archive.saveVisibilityDepth(reference, depth(10))
            assertTrue(requireNotNull(archive.load(reference)).imageBytes.contentEquals(image))
            val loadedDepth = requireNotNull(archive.loadVisibilityDepth(reference))
            assertEquals(2f, loadedDepth.depthMeters.get(0))

            val directory = File(context.noBackupFilesDir, "arkernel-reconstruction/$sessionId")
            val frameFile = requireNotNull(directory.listFiles { file ->
                file.isFile && file.name.endsWith(".arkf")
            }?.singleOrNull())
            val original = frameFile.readBytes()
            assertFalse(original.copyOfRange(0, 8).contentEquals(ARKFRM01_MAGIC))
            assertTrue(original.size > 4)

            val corrupted = original.copyOf()
            corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 1).toByte()
            frameFile.writeBytes(corrupted)
            assertSecurityFailure { archive.load(reference) }
            frameFile.writeBytes(original)
            assertTrue(requireNotNull(archive.load(reference)).imageBytes.contentEquals(image))

            archive.clear(deleteKey = true)
            frameFile.writeBytes(original)
            assertSecurityFailure { archive.load(reference) }
        } finally {
            archive.clear(deleteKey = true)
        }
    }

    @Test
    fun retentionControllerExposesExplicitDeletionActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = encryptedArchive(context, "aead-retention-test")
        archive.clear(deleteKey = true)
        try {
            val controller = ReconstructionFrameRetentionController(archive,
                ReconstructionFrameRetentionPolicy(
                    mode = ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                    deleteEncryptionKey = true))
            val published = controller.afterPublication(true)
            assertTrue(published.deleteFrames && published.deleteEncryptionKey)
            val failed = controller.afterPublication(false)
            assertFalse(failed.deleteFrames || failed.deleteEncryptionKey)
            assertTrue(controller.apply(published))

            val releaseController = ReconstructionFrameRetentionController(archive,
                ReconstructionFrameRetentionPolicy(
                    mode = ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE,
                    deleteEncryptionKey = true))
            assertTrue(releaseController.release().deleteFrames)
            assertFalse(releaseController.afterPublication(true).deleteFrames)
        } finally {
            archive.clear(deleteKey = true)
        }
    }

    private fun encryptedArchive(context: android.content.Context, sessionId: String) =
        ReconstructionFrameArchive(context, sessionId, maxFrames = 4,
            maxTotalBytes = 1024L * 1024L, authenticatedEncryption = true)

    private fun metadata(id: Long, timestamp: Long) = ReconstructionFrameMetadata(
        id, timestamp, 2, 2, 2, ReconstructionImageEncoding.GRAY8,
        worldFromCamera = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        translationSigmaMeters = 0.01, rotationSigmaDegrees = 0.1)

    private fun depth(timestamp: Long) = MetricDepthBuffer(2, 2, 2,
        direct(floatArrayOf(2f, 2f, 2f, 2f)),
        direct(floatArrayOf(1f, 1f, 1f, 1f)), 2, timestamp,
        direct(floatArrayOf(0.01f, 0.01f, 0.01f, 0.01f)), 2)

    private fun direct(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); flip() }

    private fun assertSecurityFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: SecurityException) { failed = true }
        assertTrue("Expected authenticated decryption to fail closed", failed)
    }

    private companion object {
        val ARKFRM01_MAGIC = byteArrayOf(0x41, 0x52, 0x4b, 0x46, 0x52, 0x4d, 0x30, 0x31)
    }
}
