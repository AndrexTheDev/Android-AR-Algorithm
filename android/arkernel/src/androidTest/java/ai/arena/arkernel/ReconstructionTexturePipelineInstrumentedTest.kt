package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionTexturePipelineInstrumentedTest {
    @Test
    fun buildsAtlasAndPublishesOneFileGlb() {
        val source = LoadedReconstructionTextureSource(7, 70, "arkf://texture-pipeline/7", 100, 100,
            400, CanonicalTexturePixelFormat.RGBA8, ByteArray(100 * 100 * 4).also { bytes ->
                for (pixel in 0 until 100 * 100) {
                    bytes[pixel * 4] = 210.toByte(); bytes[pixel * 4 + 1] = 80
                    bytes[pixel * 4 + 2] = 40; bytes[pixel * 4 + 3] = 255.toByte()
                }
            }, ReconstructionImageEncoding.PNG)
        val candidate = ReconstructionTextureCandidate(source,
            CameraCalibration(100, 100, 80.0, 80.0, 49.5, 49.5),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.8)
        val pipeline = ReconstructionTexturePipeline(ReconstructionTexturePipelineOptions(
            maxVertices = 16, maxTriangles = 2, maxCandidates = 1,
            maxResultBytes = 2 * 1024 * 1024, atlasWidth = 256, atlasHeight = 256))
        val result = pipeline.build(mesh(), listOf(candidate))
        assertEquals(ReconstructionTexturePipelineStage.COMPLETE, result.stage)
        assertTrue(result.inputValid && !result.truncated && result.error.isEmpty())
        assertEquals(2, result.telemetry.assignedTriangles)
        assertTrue(result.telemetry.rasterizedPixels > 0)
        assertEquals(256 * 256 * 4, result.atlasRgba8.size)
        assertNotNull(result.mesh)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = ReconstructionAssetArchive(context, "texture-pipeline-publish-test",
            maxAssets = 1, maxAssetBytes = 2 * 1024 * 1024,
            maxTotalBytes = 2L * 1024 * 1024)
        archive.clear()
        try {
            val published = ReconstructionTexturedAssetPublisher(archive,
                ReconstructionTexturedAssetOptions(maxVertices = 16, maxTriangles = 2,
                    maxGlbBytes = 2 * 1024 * 1024,
                    png = ReconstructionTexturePngOptions(maxPixels = 256 * 256,
                        maxEncodedBytes = 1024 * 1024))).publish("pipeline", 1,
                requireNotNull(result.mesh), result.atlasWidth, result.atlasHeight,
                result.atlasRgba8)
            val glb = requireNotNull(archive.load(published.reference))
            assertTrue(glb.size > 12)
            assertTrue(glb.take(4).map { it.toInt() and 255 } == listOf(103, 108, 84, 70))
            assertTrue(glb.toString(Charsets.ISO_8859_1).contains("image/png"))
        } finally {
            archive.clear()
        }
    }

    @Test
    fun requiresAlignedDepthAndFailsClosedForOccludedMesh() {
        val source = LoadedReconstructionTextureSource(8, 80, "arkf://texture-depth/8", 32, 32,
            128, CanonicalTexturePixelFormat.RGBA8,
            ByteArray(32 * 32 * 4).also { bytes ->
                for (pixel in 0 until 32 * 32) {
                    bytes[pixel * 4] = 100; bytes[pixel * 4 + 1] = 140.toByte()
                    bytes[pixel * 4 + 2] = 180.toByte(); bytes[pixel * 4 + 3] = 255.toByte()
                }
            }, ReconstructionImageEncoding.PNG)
        fun candidate(depthMeters: Float): ReconstructionTextureCandidate {
            val depth = MetricDepthBuffer(32, 32, 32,
                directFloats(FloatArray(32 * 32) { depthMeters }),
                directFloats(FloatArray(32 * 32) { 1f }), 32, 80,
                directFloats(FloatArray(32 * 32) { 0.005f }), 32)
            return ReconstructionTextureCandidate(source,
                CameraCalibration(32, 32, 20.0, 20.0, 15.5, 15.5),
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 1.0,
                ReconstructionTextureVisibilityDepth(depth))
        }
        val pipeline = ReconstructionTexturePipeline(ReconstructionTexturePipelineOptions(
            maxVertices = 16, maxTriangles = 2, maxCandidates = 1,
            maxResultBytes = 256 * 1024, atlasWidth = 64, atlasHeight = 64,
            requireVisibilityDepth = true, enablePhotometry = false,
            enableSeamBlending = false))
        val visible = pipeline.build(mesh(), listOf(candidate(2f)))
        assertEquals(ReconstructionTexturePipelineStage.COMPLETE, visible.stage)
        assertNotNull(visible.mesh)
        assertTrue(visible.atlasRgba8.isNotEmpty())

        val occluded = pipeline.build(mesh(), listOf(candidate(1f)))
        assertEquals(ReconstructionTexturePipelineStage.ATLAS_LAYOUT, occluded.stage)
        assertTrue(occluded.inputValid && !occluded.truncated && occluded.error.isNotEmpty())
        assertEquals(0, occluded.telemetry.assignedTriangles)
        assertEquals(null, occluded.mesh)
        assertTrue(occluded.atlasRgba8.isEmpty())
    }

    private fun directFloats(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); flip() }

    private fun mesh(): ReconstructionSourceMeshBuffers {
        val positions = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(floatArrayOf(-0.5f, -0.5f, 2f, 0.5f, -0.5f, 2f,
                    0.5f, 0.5f, 2f, -0.5f, 0.5f, 2f)); flip()
            }
        val indices = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
            .asIntBuffer().apply { put(intArrayOf(0, 2, 1, 0, 3, 2)); flip() }
        return ReconstructionSourceMeshBuffers(positions, null, indices)
    }
}
