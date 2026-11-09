package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionTexturedSessionPipelineInstrumentedTest {
    @Test
    fun publishesEmbeddedGlbFromArchivedFrameAndDirectFusedMesh() {
        val (frames, assets) = archives("complete")
        try {
            val reference = saveFrame(frames, withDepth = true)
            frames.publishOptimizedPoses(ReconstructionPoseManifest(
                generation = 2L,
                poses = listOf(OptimizedReconstructionPose(
                    frameId = 1L, timestampNs = 1_000L,
                    worldFromCamera = identityPose(),
                    translationSigmaMeters = 0.01,
                    rotationSigmaDegrees = 0.25))))
            val result = ReconstructionTexturedSessionPipeline(frames, assets, sessionOptions())
                .run(sessionRequest(reference))

            assertTrue("stage=${result.stage} error=${result.error}", result.complete)
            assertEquals(ReconstructionTexturedSessionStage.COMPLETE, result.stage)
            assertTrue(result.error.isEmpty())
            assertEquals("unverified", result.qualityGrade)
            assertEquals(1, result.frames.size)
            assertEquals(2L, requireNotNull(result.poseManifestGeneration))
            assertEquals(1L, result.frames[0].frameId)
            assertEquals(1_000L, result.frames[0].timestampNs)
            assertEquals(reference, result.frames[0].reference)
            assertTrue(result.frames[0].worldFromCamera.contentEquals(identityPose()))
            assertEquals(0.01, result.frames[0].translationSigmaMeters, 1e-12)
            assertEquals(0.25, result.frames[0].rotationSigmaDegrees, 1e-12)
            assertTrue(result.frames[0].canonicalBytes > 0)
            assertTrue(result.frames[0].visibilityDepthBytes > 0)
            assertTrue(result.archivedBytes > 0 && result.canonicalBytes > 0 &&
                result.visibilityDepthBytes > 0)
            assertEquals(0, result.decodedCompressedSources)
            assertEquals(1, result.loadedVisibilityDepthSources)
            assertEquals(1, result.fusionUpdates.size)
            assertTrue(result.fusionUpdates[0].accepted)
            assertTrue(result.sourceMesh.complete && result.sourceMesh.error.isEmpty())
            assertTrue(result.sourceMesh.sourceVertices > 0 &&
                result.sourceMesh.sourceTriangles > 0 && result.sourceMesh.rawMeshBytes > 0)
            assertEquals(ReconstructionTexturePipelineStage.COMPLETE, result.textureStage)
            assertTrue(result.textureInputValid && !result.textureTruncated &&
                result.textureError.isEmpty())
            assertEquals(result.sourceMesh.sourceTriangles,
                result.textureTelemetry.assignedTriangles)
            assertEquals(0L, result.textureTelemetry.unassignedTriangles)
            assertEquals(64, result.atlasWidth)
            assertEquals(64, result.atlasHeight)
            assertEquals(64 * 64 * 4, result.atlasBytes)

            val asset = requireNotNull(result.asset)
            assertTrue(asset.vertices > 0 && asset.triangles > 0 &&
                asset.pngBytes > 0 && asset.glbBytes > 0)
            val glb = requireNotNull(assets.load(asset.reference))
            assertTrue(glb.size > 12)
            assertEquals(listOf(103, 108, 84, 70), glb.take(4).map { it.toInt() and 255 })
            val text = glb.toString(Charsets.ISO_8859_1)
            assertTrue(text.contains("image/png"))
            assertTrue(text.contains("\"accuracy\":\"unverified\""))
        } finally {
            frames.clear()
            assets.clear()
        }
    }

    @Test
    fun failsClosedWithoutVisibilityDepthAndPublishesNothing() {
        val (frames, assets) = archives("missing-depth")
        try {
            val reference = saveFrame(frames, withDepth = false)
            val result = ReconstructionTexturedSessionPipeline(frames, assets, sessionOptions())
                .run(sessionRequest(reference))
            assertEquals(ReconstructionTexturedSessionStage.SOURCES, result.stage)
            assertTrue(!result.complete && result.error.contains("visibility"))
            assertNull(result.asset)
            assertTrue(assets.references().isEmpty())
        } finally {
            frames.clear()
            assets.clear()
        }
    }

    private fun archives(suffix: String): Pair<ReconstructionFrameArchive, ReconstructionAssetArchive> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frames = ReconstructionFrameArchive(context,
            "textured-session-$suffix", maxFrames = 4,
            maxTotalBytes = 16L * 1024L * 1024L)
        val assets = ReconstructionAssetArchive(context,
            "textured-session-$suffix", maxAssets = 2,
            maxAssetBytes = 4L * 1024L * 1024L,
            maxTotalBytes = 8L * 1024L * 1024L)
        frames.clear()
        assets.clear()
        return frames to assets
    }

    private fun saveFrame(
        archive: ReconstructionFrameArchive,
        withDepth: Boolean,
    ): String {
        val width = 128
        val height = 128
        val timestampNs = 1_000L
        val frameId = 1L
        val bytes = ByteArray(width * height) { (it % 256).toByte() }
        val metadata = ReconstructionFrameMetadata(
            frameId = frameId, timestampNs = timestampNs,
            width = width, height = height, rowStrideBytes = width,
            encoding = ReconstructionImageEncoding.GRAY8,
            worldFromCamera = identityPose(),
            translationSigmaMeters = 0.0, rotationSigmaDegrees = 0.0)
        val reference = archive.save(ArchivedReconstructionFrame(metadata, bytes))
        if (withDepth) {
            val pixels = width * height
            val depth = MetricDepthBuffer(width, height, width,
                direct(FloatArray(pixels) { 2f }),
                direct(FloatArray(pixels) { 1f }), width, timestampNs,
                direct(FloatArray(pixels) { 0.005f }), width)
            archive.saveVisibilityDepth(reference, depth)
        }
        return reference
    }

    private fun sessionRequest(reference: String) = ReconstructionTexturedSessionRequest(
        assetId = "session", generation = 1L,
        frames = listOf(ReconstructionTexturedSessionFrame(
            frameId = 1L, reference = reference,
            calibration = CameraCalibration(128, 128, 160.0, 160.0, 63.5, 63.5),
            qualityScore = 1.0)))

    private fun sessionOptions() = ReconstructionTexturedSessionOptions(
        maxFrames = 1,
        maxSessionBytes = 64L * 1024L * 1024L,
        fusion = ReconstructionFusionOptions(
            voxelSizeMeters = 0.05, truncationDistanceMeters = 0.10,
            pixelStride = 1, maxBlocks = 512, maxIntegratedFrames = 1,
            minComponentTriangles = 2, minComponentAreaSquareMeters = 0.0,
            simplificationCellSizeMeters = 0.15, maxSimplificationErrorMeters = 0.12,
            maxRawMeshBytes = 8 * 1024 * 1024),
        sourceLoad = ReconstructionTextureSourceLoadOptions(
            maxSources = 1, maxCanonicalBytes = 1L * 1024L * 1024L,
            maxVisibilityDepthBytes = 1L * 1024L * 1024L),
        texture = ReconstructionTexturePipelineOptions(
            maxVertices = 1_000_000, maxTriangles = 2_000_000,
            maxCandidates = 1, maxResultBytes = 4 * 1024 * 1024,
            atlasWidth = 64, atlasHeight = 64,
            requireVisibilityDepth = true, enablePhotometry = false,
            enableSeamBlending = false),
        publisher = ReconstructionTexturedAssetOptions(
            maxVertices = 1_000_000, maxTriangles = 2_000_000,
            maxGlbBytes = 4 * 1024 * 1024,
            png = ReconstructionTexturePngOptions(
                maxPixels = 64 * 64, maxEncodedBytes = 1 * 1024 * 1024)))

    private fun identityPose() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun direct(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); flip() }
}
