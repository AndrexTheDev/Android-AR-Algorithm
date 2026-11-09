package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionTextureSourceLoaderInstrumentedTest {
    @Test
    fun resolvesUniqueViewTableToCanonicalOwnedSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = ReconstructionFrameArchive(context, "texture-loader-test",
            maxFrames = 4, maxTotalBytes = 1024 * 1024)
        archive.clear()
        try {
            val rgba = byteArrayOf(
                255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(),
                0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(),
                255.toByte())
            val png = ReconstructionTexturePngEncoder.encodeRgba8(2, 2, rgba)
            val pngReference = archive.save(ArchivedReconstructionFrame(
                metadata(1, 10, ReconstructionImageEncoding.PNG), png.bytes))
            archive.saveVisibilityDepth(pngReference, MetricDepthBuffer(2, 2, 2,
                direct(floatArrayOf(2f, 2f, 2f, 2f)),
                direct(floatArrayOf(1f, 1f, 1f, 1f)), 2, 10,
                direct(floatArrayOf(0.01f, 0.01f, 0.01f, 0.01f)), 2))
            val i420 = byteArrayOf(82, 82, 82, 82, 90, 240.toByte())
            val i420Reference = archive.save(ArchivedReconstructionFrame(
                metadata(2, 20, ReconstructionImageEncoding.I420), i420))
            val requests = listOf(
                ReconstructionTextureSourceRequest(1, pngReference, 2, 2),
                ReconstructionTextureSourceRequest(2, i420Reference, 2, 2))
            val loaded = ReconstructionTextureSourceLoader(archive,
                ReconstructionTextureSourceLoadOptions(maxSources = 2,
                    maxCanonicalBytes = 64)).load(requests)
            assertEquals(2, loaded.sources.size)
            val sourceMetadata = requireNotNull(loaded.sources[0].frameMetadata)
            assertEquals(1L, sourceMetadata.frameId)
            assertEquals(10L, sourceMetadata.timestampNs)
            assertEquals(ReconstructionImageEncoding.PNG, sourceMetadata.encoding)
            assertEquals(null, loaded.poseManifestGeneration)
            assertEquals(10L, loaded.sources[0].timestampNs)
            assertEquals(20L, loaded.sources[1].timestampNs)
            assertEquals(1, loaded.decodedCompressedSources)
            assertEquals(1, loaded.loadedVisibilityDepthSources)
            assertEquals(48L, loaded.visibilityDepthBytes)
            assertEquals(22L, loaded.canonicalBytes)
            val loadedDepth = requireNotNull(loaded.sources[0].visibilityDepth)
            assertTrue(loadedDepth.depthMeters.isDirect)
            assertEquals(2f, loadedDepth.depthMeters.get(0))
            assertEquals(0.01f, requireNotNull(loadedDepth.uncertaintyMeters).get(3))
            assertTrue(loaded.sources[0].bytes.contentEquals(rgba))
            assertEquals(CanonicalTexturePixelFormat.RGBA8, loaded.sources[0].format)
            assertEquals(8, loaded.sources[0].rowStrideBytes)
            assertTrue(loaded.sources[1].bytes.contentEquals(i420))
            assertEquals(CanonicalTexturePixelFormat.I420, loaded.sources[1].format)
            assertEquals(2, loaded.sources[1].rowStrideBytes)
            assertFails { ReconstructionTextureSourceLoader(archive,
                ReconstructionTextureSourceLoadOptions(maxVisibilityDepthBytes = 47)).load(requests) }
            assertTrue(archive.delete(pngReference))
            assertEquals(null, archive.loadVisibilityDepth(pngReference))
        } finally {
            archive.clear()
        }
    }

    @Test
    fun failsClosedOnProvenanceOrderAndAggregateBudgetMismatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = ReconstructionFrameArchive(context, "texture-loader-invalid-test",
            maxFrames = 2, maxTotalBytes = 1024 * 1024)
        archive.clear()
        try {
            val grayReference = archive.save(ArchivedReconstructionFrame(
                metadata(1, 10, ReconstructionImageEncoding.GRAY8), byteArrayOf(1, 2, 3, 4)))
            val valid = ReconstructionTextureSourceRequest(1, grayReference, 2, 2)
            assertFails { ReconstructionTextureSourceLoader(archive).load(
                listOf(valid.copy(width = 3))) }
            assertFails { ReconstructionTextureSourceLoader(archive).load(
                listOf(valid.copy(frameId = 2), valid)) }
            assertFails { ReconstructionTextureSourceLoader(archive,
                ReconstructionTextureSourceLoadOptions(maxCanonicalBytes = 3)).load(listOf(valid)) }
        } finally {
            archive.clear()
        }
    }

    private fun direct(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); flip() }

    private fun metadata(id: Long, timestamp: Long, encoding: ReconstructionImageEncoding) =
        ReconstructionFrameMetadata(id, timestamp, 2, 2, 2, encoding,
            worldFromCamera = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            translationSigmaMeters = 0.01, rotationSigmaDegrees = 0.1)

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
