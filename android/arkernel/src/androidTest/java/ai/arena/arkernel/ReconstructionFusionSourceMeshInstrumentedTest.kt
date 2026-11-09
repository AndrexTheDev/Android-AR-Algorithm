package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionFusionSourceMeshInstrumentedTest {
    @Test
    fun buildsDirectMetricMeshAndFeedsTexturePipeline() {
        val options = ReconstructionFusionOptions(voxelSizeMeters = 0.05,
            truncationDistanceMeters = 0.10, pixelStride = 1, maxBlocks = 512,
            maxIntegratedFrames = 2, minComponentTriangles = 2,
            minComponentAreaSquareMeters = 0.0, simplificationCellSizeMeters = 0.15,
            maxSimplificationErrorMeters = 0.12, maxRawMeshBytes = 4 * 1024 * 1024)
        ReconstructionFusionPipeline(options).use { fusion ->
            val empty = fusion.buildSourceMesh()
            assertTrue(!empty.complete && empty.mesh == null && empty.error.isNotEmpty())
            val calibration = CameraCalibration(32, 32, 40.0, 40.0, 15.5, 15.5)
            val depthValues = direct(FloatArray(32 * 32) { 2f })
            val confidence = direct(FloatArray(32 * 32) { 1f })
            val uncertainty = direct(FloatArray(32 * 32) { 0.005f })
            fun depth(timestamp: Long) = MetricDepthBuffer(32, 32, 32,
                depthValues.duplicate(), confidence.duplicate(), 32, timestamp,
                uncertainty.duplicate(), 32)
            fun pose(timestamp: Long) = WorldPose(timestamp, Rotation(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0))
            assertTrue(fusion.integrate(depth(1_000), calibration, pose(1_000)).accepted)
            assertTrue(fusion.integrate(depth(2_000), calibration, pose(2_000)).accepted)
            val built = fusion.buildSourceMesh()
            assertTrue(built.complete && built.error.isEmpty())
            assertTrue(!built.fusionTruncated && !built.meshingTruncated &&
                !built.filteringTruncated && !built.simplificationTruncated)
            val mesh = requireNotNull(built.mesh)
            assertTrue(mesh.positionsMeters.isDirect && mesh.triangleIndices.isDirect)
            assertTrue(built.sourceVertices > 0 && built.sourceTriangles > 0)
            assertEquals(built.sourceVertices * 3, mesh.positionsMeters.remaining().toLong())
            assertEquals(built.sourceTriangles * 3, mesh.triangleIndices.remaining().toLong())

            val rgba = ByteArray(64 * 64 * 4).also { bytes ->
                for (pixel in 0 until 64 * 64) {
                    bytes[pixel * 4] = 70; bytes[pixel * 4 + 1] = 130.toByte()
                    bytes[pixel * 4 + 2] = 190.toByte(); bytes[pixel * 4 + 3] = 255.toByte()
                }
            }
            val textureDepth = MetricDepthBuffer(64, 64, 64,
                direct(FloatArray(64 * 64) { 2f }),
                direct(FloatArray(64 * 64) { 1f }), 64, 1_000,
                direct(FloatArray(64 * 64) { 0.005f }), 64)
            val source = LoadedReconstructionTextureSource(1, 1_000, "arkf://fusion-source/1",
                64, 64, 256, CanonicalTexturePixelFormat.RGBA8, rgba,
                ReconstructionImageEncoding.PNG, textureDepth)
            val textureCalibration = CameraCalibration(64, 64, 40.0, 40.0, 31.5, 31.5)
            val candidate = ReconstructionTextureCandidate(source, textureCalibration,
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 1.0)
            val textured = ReconstructionTexturePipeline(ReconstructionTexturePipelineOptions(
                maxVertices = options.maxMeshVertices.toInt(),
                maxTriangles = options.maxMeshTriangles.toInt(), maxCandidates = 1,
                maxResultBytes = 4 * 1024 * 1024, atlasWidth = 64, atlasHeight = 64,
                requireVisibilityDepth = true, enablePhotometry = false,
                enableSeamBlending = false)).build(mesh, listOf(candidate))
            assertTrue("stage=${textured.stage} error=${textured.error} assigned=" +
                "${textured.telemetry.assignedTriangles}/${built.sourceTriangles}",
                textured.stage == ReconstructionTexturePipelineStage.COMPLETE)
            assertNotNull(textured.mesh)
            assertTrue(textured.atlasRgba8.isNotEmpty())
        }
    }

    private fun direct(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); flip() }
}
