package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelFieldDiagnosticsBundleTest {
    @Test
    fun roundTripsImageFreeReportAndAcceptsBomCrLfAndOneFinalSeparator() {
        val record = ArKernelFieldDiagnosticsBundle.fromState(
            stateRecord(),
            capabilitySignature = "a".repeat(64),
            calibrationSignature = "b".repeat(64),
            heldOutTargetReference = "target-01",
        )
        val text = ArKernelFieldDiagnosticsBundle.encode(record)
        assertEquals(59, text.lines().size)
        val parsed = ArKernelFieldDiagnosticsBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelFieldDiagnosticsRejection.NONE, parsed.rejection)
        assertEquals(59, parsed.lineNumber)
        assertNotNull(parsed.record)
        assertEquals(record, parsed.record)
        assertEquals("unverified", parsed.record!!.qualityGrade)
        assertEquals(17, parsed.record.textureTelemetry.size)
        assertEquals("a".repeat(64), parsed.record.capabilitySignature)
        assertEquals("b".repeat(64), parsed.record.calibrationSignature)
        assertEquals("target-01", parsed.record.heldOutTargetReference)
        listOf(
            "world_from_camera", "frame_reference", "timestamp_ns", "image_bytes",
            "trajectory", "arkf://", "arkglb://",
        ).forEach { forbidden ->
            assertFalse("report must not contain $forbidden", text.contains(forbidden))
        }

        assertTrue(ArKernelFieldDiagnosticsBundle.parse("\uFEFF$text").accepted)
        assertTrue(ArKernelFieldDiagnosticsBundle.parse(
            text.replace("\n", "\r\n")).accepted)
        for (separator in listOf("\n", "\r", "\r\n")) {
            val terminated = ArKernelFieldDiagnosticsBundle.parse("$text$separator")
            assertTrue("terminal separator $separator must be accepted", terminated.accepted)
            assertEquals(59, terminated.lineNumber)
            assertEquals(record, terminated.record)
        }
        assertTrue(ArKernelFieldDiagnosticsBundle.parse("\uFEFF$text\n").accepted)
    }

    @Test
    fun roundTripsFailedStateWithoutOpaqueAssetOrPoseEvidence() {
        val failed = stateRecord().copy(
            stage = ReconstructionTexturedSessionStage.SOURCES,
            complete = false,
            error = "missing visibility depth",
            textureStage = ReconstructionTexturePipelineStage.NONE,
            textureInputValid = false,
            asset = null,
        )
        val record = ArKernelFieldDiagnosticsBundle.fromState(failed)
        val text = ArKernelFieldDiagnosticsBundle.encode(record)
        val parsed = ArKernelFieldDiagnosticsBundle.parse(text)
        assertTrue(parsed.accepted)
        assertFalse(parsed.record!!.complete)
        assertTrue(parsed.record.error.contains("missing visibility depth"))
        assertFalse(parsed.record.assetPresent)
        assertEquals("", parsed.record.assetReference)
        assertFalse(text.contains("world_from_camera"))
        assertFalse(text.contains("frame_reference"))
        assertFalse(text.contains("arkf://"))
    }

    @Test
    fun rejectsMalformedDocumentsAndBudgets() {
        val text = ArKernelFieldDiagnosticsBundle.encode(
            ArKernelFieldDiagnosticsBundle.fromState(stateRecord()))
        assertEquals(ArKernelFieldDiagnosticsRejection.EMPTY_DATASET,
            ArKernelFieldDiagnosticsBundle.parse("").rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.EMPTY_DATASET,
            ArKernelFieldDiagnosticsBundle.parse("\uFEFF").rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.EMPTY_DATASET,
            ArKernelFieldDiagnosticsBundle.parse("\n").rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.EMPTY_DATASET,
            ArKernelFieldDiagnosticsBundle.parse("\uFEFF\r\n").rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INPUT_TOO_LARGE,
            ArKernelFieldDiagnosticsBundle.parse("x".repeat(65_537)).rejection)

        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_HEADER,
            ArKernelFieldDiagnosticsBundle.parse(
                "WRONG\n" + text.lines().drop(1).joinToString("\n")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "version", "2")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceKey(text, "session_id", "id")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceLine(text, "session_id", " session_id=scan-a")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.LINE_TOO_LONG,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceLine(text, "session_id", "x".repeat(8_385))).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.EMPTY_LINE,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceLine(text, "session_id", "")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsBundle.parse("$text\nextra=1").rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsBundle.parse("$text\n\n").rejection)
    }

    @Test
    fun rejectsInvalidValuesSignaturesAndInvariants() {
        val text = ArKernelFieldDiagnosticsBundle.encode(
            ArKernelFieldDiagnosticsBundle.fromState(stateRecord()))
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_QUALITY,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "quality_grade", "assessed")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "generation", "-1")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "complete", "yes")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "stage", "UNKNOWN")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "capability_signature_sha256", "A".repeat(64))).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "held_out_target_ref", "bad/id")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_HEX,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "error_hex", "zz")).rejection)
        assertEquals(ArKernelFieldDiagnosticsRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsBundle.parse(
                replaceValue(text, "texture_telemetry_00", "-1")).rejection)
    }

    @Test
    fun rejectsInvalidRecordConstruction() {
        val record = ArKernelFieldDiagnosticsBundle.fromState(stateRecord())
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(qualityGrade = "assessed")
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(capabilitySignature = "A".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(heldOutTargetReference = "bad/id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(textureTelemetry = List(17) { -1L })
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(error = "x".repeat(4_097))
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(assetPresent = false)
        }
    }

    private fun replaceValue(text: String, key: String, value: String): String =
        replaceLine(text, key, "$key=$value")

    private fun replaceKey(text: String, oldKey: String, newKey: String): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("$oldKey=") }
        require(index >= 0) { "Unknown diagnostics key" }
        lines[index] = "$newKey=" + lines[index].substringAfter('=')
        return lines.joinToString("\n")
    }

    private fun replaceLine(text: String, key: String, line: String): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("$key=") }
        require(index >= 0) { "Unknown diagnostics key" }
        lines[index] = line
        return lines.joinToString("\n")
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
}
