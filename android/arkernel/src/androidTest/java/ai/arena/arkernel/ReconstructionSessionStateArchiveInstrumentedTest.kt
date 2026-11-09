package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionSessionStateArchiveInstrumentedTest {
    @Test
    fun persistsGenerationsAndEvictsSessionsDeterministically() {
        val archive = archive("generations")
        try {
            val first = archive.save(stateRecord("scan-a", 1L))
            val loaded = requireNotNull(archive.load(first))
            assertEquals("scan-a", loaded.sessionId)
            assertEquals("session", loaded.assetId)
            assertEquals(1L, loaded.generation)
            assertEquals(ReconstructionTexturedSessionStage.COMPLETE, loaded.stage)
            assertTrue(loaded.complete && loaded.error.isEmpty())
            assertEquals("unverified", loaded.qualityGrade)
            assertEquals(2L, requireNotNull(loaded.poseManifestGeneration))
            assertEquals(1, loaded.frames.size)
            assertEquals("arkf://state/1", loaded.frames[0].reference)
            assertTrue(loaded.frames[0].worldFromCamera.contentEquals(identityPose()))
            assertArrayEquals(LongArray(ReconstructionTexturePipelineTelemetry.FIELD_COUNT) {
                it.toLong()
            }, loaded.textureTelemetry.values)
            assertEquals("arkglb://state/0000000000000000001-session.glb",
                requireNotNull(loaded.asset).reference)
            assertEquals(first, archive.generations("scan-a").single())
            assertEquals(1L, requireNotNull(archive.latest("scan-a")).generation)

            assertFails { archive.save(stateRecord("scan-a", 1L)) }
            val second = archive.save(stateRecord("scan-a", 2L))
            assertEquals(2L, requireNotNull(archive.latest("scan-a")).generation)
            val third = archive.save(stateRecord("scan-a", 3L))
            assertNull(archive.load(first))
            assertNotNull(archive.load(second))
            assertNotNull(archive.load(third))
            assertEquals(3L, requireNotNull(archive.latest("scan-a")).generation)
            assertEquals(2, archive.generations("scan-a").size)

            val other = archive.save(stateRecord("scan-b", 4L))
            val newest = archive.save(stateRecord("scan-c", 5L))
            assertNotNull(archive.load(other))
            assertNotNull(archive.load(newest))
            assertTrue(archive.generations("scan-a").isEmpty())
            assertEquals(1, archive.generations("scan-b").size)
            assertEquals(1, archive.generations("scan-c").size)
            assertTrue(archive.delete(other))
            assertNull(archive.load(other))
        } finally {
            archive.clear()
        }
    }

    @Test
    fun recordsCompleteAndFailedTerminalResults() {
        val archive = archive("terminal")
        try {
            val record = stateRecord("scan-record", 7L)
            val request = requestFromRecord(record)
            val result = resultFromRecord(record)
            val completeReference = archive.record("scan-record", request, result)
            val complete = requireNotNull(archive.load(completeReference))
            assertEquals("scan-record", complete.sessionId)
            assertEquals("session", complete.assetId)
            assertEquals(7L, complete.generation)
            assertEquals(ReconstructionTexturedSessionStage.COMPLETE, complete.stage)
            assertTrue(complete.complete && complete.error.isEmpty())
            assertEquals("unverified", complete.qualityGrade)
            assertEquals(1, complete.frames.size)
            assertEquals(1, complete.fusionUpdates.size)
            assertEquals(result.asset, complete.asset)

            val failedResult = result.copy(
                stage = ReconstructionTexturedSessionStage.SOURCES,
                complete = false, error = "missing visibility depth",
                poseManifestGeneration = null, frames = emptyList(),
                archivedBytes = 0L, canonicalBytes = 0L,
                visibilityDepthBytes = 0L, loadedVisibilityDepthSources = 0,
                fusionUpdates = emptyList(),
                sourceMesh = ReconstructionTexturedSessionMeshTelemetry(),
                textureStage = ReconstructionTexturePipelineStage.NONE,
                textureInputValid = false,
                textureTelemetry = ReconstructionTexturePipelineTelemetry(
                    LongArray(ReconstructionTexturePipelineTelemetry.FIELD_COUNT)),
                atlasWidth = 0, atlasHeight = 0, atlasBytes = 0, asset = null)
            val failedReference = archive.record("scan-failed",
                request.copy(generation = 8L), failedResult)
            val failed = requireNotNull(archive.load(failedReference))
            assertEquals("scan-failed", failed.sessionId)
            assertEquals(8L, failed.generation)
            assertEquals(ReconstructionTexturedSessionStage.SOURCES, failed.stage)
            assertTrue(!failed.complete && failed.error == "missing visibility depth")
            assertNull(failed.asset)
        } finally {
            archive.clear()
        }
    }

    private fun archive(suffix: String): ReconstructionSessionStateArchive {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = ReconstructionSessionStateArchive(context,
            "state-$suffix", maxSessions = 2, maxGenerationsPerSession = 2,
            maxStateBytes = 64L * 1024L, maxTotalBytes = 256L * 1024L)
        archive.clear()
        return archive
    }

    private fun stateRecord(sessionId: String, generation: Long) =
        ReconstructionSessionStateRecord(
            sessionId = sessionId, assetId = "session", generation = generation,
            stage = ReconstructionTexturedSessionStage.COMPLETE, complete = true, error = "",
            qualityGrade = "unverified", poseManifestGeneration = 2L,
            frames = listOf(ReconstructionTexturedSessionFrameEvidence(
                frameId = 1L, timestampNs = 1_000L, reference = "arkf://state/1",
                width = 2, height = 2,
                calibration = CameraCalibration(2, 2, 1.0, 1.0, 0.5, 0.5),
                qualityScore = 1.0, worldFromCamera = identityPose(),
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

    private fun requestFromRecord(record: ReconstructionSessionStateRecord) =
        ReconstructionTexturedSessionRequest(
            assetId = record.assetId, generation = record.generation,
            frames = record.frames.map { frame ->
                ReconstructionTexturedSessionFrame(frame.frameId, frame.reference,
                    frame.calibration, frame.qualityScore)
            })

    private fun resultFromRecord(record: ReconstructionSessionStateRecord) =
        ReconstructionTexturedSessionResult(
            stage = record.stage, complete = record.complete, error = record.error,
            frames = record.frames,
            poseManifestGeneration = record.poseManifestGeneration,
            archivedBytes = record.archivedBytes, canonicalBytes = record.canonicalBytes,
            decodedCompressedSources = record.decodedCompressedSources,
            visibilityDepthBytes = record.visibilityDepthBytes,
            loadedVisibilityDepthSources = record.loadedVisibilityDepthSources,
            fusionUpdates = record.fusionUpdates, sourceMesh = record.sourceMesh,
            textureStage = record.textureStage,
            textureInputValid = record.textureInputValid,
            textureTruncated = record.textureTruncated, textureError = record.textureError,
            textureTelemetry = record.textureTelemetry,
            atlasWidth = record.atlasWidth, atlasHeight = record.atlasHeight,
            atlasBytes = record.atlasBytes, asset = record.asset,
            qualityGrade = record.qualityGrade)

    private fun identityPose() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
