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

            // A higher-resolution view with the same angular field of view
            // keeps the complete mesh inside the image while projecting each
            // voxel-scale triangle above the calibrated selection area gate.
            val rgba = ByteArray(128 * 128 * 4).also { bytes ->
                for (pixel in 0 until 128 * 128) {
                    bytes[pixel * 4] = 70; bytes[pixel * 4 + 1] = 130.toByte()
                    bytes[pixel * 4 + 2] = 190.toByte(); bytes[pixel * 4 + 3] = 255.toByte()
                }
            }
            val textureDepth = MetricDepthBuffer(128, 128, 128,
                direct(FloatArray(128 * 128) { 2f }),
                direct(FloatArray(128 * 128) { 1f }), 128, 1_000,
                direct(FloatArray(128 * 128) { 0.005f }), 128)
            val source = LoadedReconstructionTextureSource(1, 1_000, "arkf://fusion-source/1",
                128, 128, 512, CanonicalTexturePixelFormat.RGBA8, rgba,
                ReconstructionImageEncoding.PNG, textureDepth)
            val textureCalibration = CameraCalibration(128, 128, 160.0, 160.0, 63.5, 63.5)
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

    @Test
    fun buildsDeliveryMeshWithMeasuredQuadricErrorLod() {
        val options = ReconstructionFusionOptions(voxelSizeMeters = 0.05,
            truncationDistanceMeters = 0.10, pixelStride = 1, maxBlocks = 512,
            maxIntegratedFrames = 2, minComponentTriangles = 2,
            minComponentAreaSquareMeters = 0.0, simplificationCellSizeMeters = 0.15,
            maxSimplificationErrorMeters = 0.12, maxRawMeshBytes = 4 * 1024 * 1024,
            enableQuadricErrorLod = true, maxQuadricErrorSquareMeters = 1e-3,
            maxQuadricLodDisplacementMeters = 0.05, maxQuadricEvaluations = 100_000L)
        ReconstructionFusionPipeline(options).use { fusion ->
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
            val built = fusion.build()
            assertTrue("complete=${built.complete} truncated=${built.quadricLodTruncated}",
                built.complete)
            assertTrue(built.quadricLodEnabled && !built.quadricLodTruncated)
            assertTrue(built.quadricLodInitialErrorSquareMeters >= 0.0)
            assertTrue(built.quadricLodFinalErrorSquareMeters >=
                built.quadricLodInitialErrorSquareMeters)
            assertTrue(built.quadricLodMaxDisplacementMeters <= 0.05 + 1e-6)
            assertEquals(built.simplifiedVertices, built.quadricLodOutputVertices)
            assertTrue(built.sourceGlb.isNotEmpty() && built.simplifiedGlb.isNotEmpty())
        }
    }

    private fun direct(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); flip() }
}
