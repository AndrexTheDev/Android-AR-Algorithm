package ai.arena.arkernel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionSessionStateCodecTest {
    @Test
    fun roundTripsCompleteAndFailedStatesWithExactTelemetry() {
        val complete = stateRecord()
        val encoded = ReconstructionSessionStateCodec.encode(complete)
        val decoded = ReconstructionSessionStateCodec.decode(encoded)
        assertEquals(complete.sessionId, decoded.sessionId)
        assertEquals(complete.assetId, decoded.assetId)
        assertEquals(complete.generation, decoded.generation)
        assertEquals(complete.stage, decoded.stage)
        assertTrue(decoded.complete && decoded.error.isEmpty())
        assertEquals(complete.qualityGrade, decoded.qualityGrade)
        assertEquals(complete.poseManifestGeneration, decoded.poseManifestGeneration)
        assertEquals(complete.archivedBytes, decoded.archivedBytes)
        assertEquals(complete.canonicalBytes, decoded.canonicalBytes)
        assertEquals(complete.decodedCompressedSources, decoded.decodedCompressedSources)
        assertEquals(complete.visibilityDepthBytes, decoded.visibilityDepthBytes)
        assertEquals(complete.loadedVisibilityDepthSources, decoded.loadedVisibilityDepthSources)
        assertEquals(complete.frames.size, decoded.frames.size)
        assertEquals(complete.frames[0].frameId, decoded.frames[0].frameId)
        assertEquals(complete.frames[0].timestampNs, decoded.frames[0].timestampNs)
        assertEquals(complete.frames[0].reference, decoded.frames[0].reference)
        assertEquals(complete.frames[0].calibration, decoded.frames[0].calibration)
        assertEquals(complete.frames[0].qualityScore, decoded.frames[0].qualityScore, 0.0)
        assertTrue(complete.frames[0].worldFromCamera.contentEquals(decoded.frames[0].worldFromCamera))
        assertEquals(complete.frames[0].translationSigmaMeters,
            decoded.frames[0].translationSigmaMeters, 0.0)
        assertEquals(complete.frames[0].rotationSigmaDegrees,
            decoded.frames[0].rotationSigmaDegrees, 0.0)
        assertEquals(complete.fusionUpdates, decoded.fusionUpdates)
        assertEquals(complete.sourceMesh, decoded.sourceMesh)
        assertEquals(complete.textureStage, decoded.textureStage)
        assertTrue(decoded.textureInputValid && !decoded.textureTruncated &&
            decoded.textureError.isEmpty())
        assertArrayEquals(complete.textureTelemetry.values, decoded.textureTelemetry.values)
        assertEquals(complete.atlasWidth, decoded.atlasWidth)
        assertEquals(complete.atlasHeight, decoded.atlasHeight)
        assertEquals(complete.atlasBytes, decoded.atlasBytes)
        assertEquals(complete.asset, decoded.asset)

        val failed = complete.copy(
            stage = ReconstructionTexturedSessionStage.SOURCES,
            complete = false, error = "missing visibility depth",
            textureStage = ReconstructionTexturePipelineStage.NONE,
            textureInputValid = false, asset = null)
        val failedDecoded = ReconstructionSessionStateCodec.decode(
            ReconstructionSessionStateCodec.encode(failed))
        assertEquals(ReconstructionTexturedSessionStage.SOURCES, failedDecoded.stage)
        assertTrue(!failedDecoded.complete && failedDecoded.error == "missing visibility depth")
        assertNull(failedDecoded.asset)
    }

    @Test
    fun rejectsCorruptionAndInvalidCompletionInvariants() {
        val encoded = ReconstructionSessionStateCodec.encode(stateRecord())
        val corrupted = encoded.copyOf()
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        assertFails { ReconstructionSessionStateCodec.decode(corrupted) }
        assertFails { stateRecord().copy(asset = null) }
        assertFails { stateRecord().copy(error = "x".repeat(5_000)) }
    }

    private fun stateRecord() = ReconstructionSessionStateRecord(
        sessionId = "scan-a", assetId = "session", generation = 1L,
        stage = ReconstructionTexturedSessionStage.COMPLETE, complete = true, error = "",
        qualityGrade = "unverified", poseManifestGeneration = 2L,
        frames = listOf(ReconstructionTexturedSessionFrameEvidence(
            frameId = 1L, timestampNs = 1_000L, reference = "arkf://state/1",
            width = 2, height = 2,
            calibration = CameraCalibration(2, 2, 1.0, 1.0, 0.5, 0.5),
            qualityScore = 1.0,
            worldFromCamera = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            translationSigmaMeters = 0.01, rotationSigmaDegrees = 0.25,
            canonicalBytes = 16, visibilityDepthBytes = 48L)),
        archivedBytes = 32L, canonicalBytes = 16L, decodedCompressedSources = 0,
        visibilityDepthBytes = 48L, loadedVisibilityDepthSources = 1,
        fusionUpdates = listOf(ReconstructionFusionUpdate(
            true, ReconstructionIntegrationRejection.NONE, 1L, 0L, 4L, 8L, 4L,
            4L, 0L, 8L, 8L, 1L, 0L, false)),
        sourceMesh = ReconstructionTexturedSessionMeshTelemetry(
            complete = true, sourceVertices = 4L, sourceTriangles = 2L,
            rawMeshBytes = 128L, error = ""),
        textureStage = ReconstructionTexturePipelineStage.COMPLETE,
        textureInputValid = true, textureTruncated = false, textureError = "",
        textureTelemetry = ReconstructionTexturePipelineTelemetry(
            LongArray(ReconstructionTexturePipelineTelemetry.FIELD_COUNT) { it.toLong() }),
        atlasWidth = 2, atlasHeight = 2, atlasBytes = 16,
        asset = PublishedTexturedReconstructionAsset(
            "arkglb://state/0000000000000000001-session.glb", 4, 2, 100, 200))

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
