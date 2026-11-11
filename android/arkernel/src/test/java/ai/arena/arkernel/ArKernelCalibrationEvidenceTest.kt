package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class CalibrationRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelCalibrationRejection,
)

class ArKernelCalibrationEvidenceTest {
    @Test
    fun sampleEvidenceRoundTripsAndReportsOnlyHostSuppliedFacts() {
        val bundle = sampleBundle()
        val text = ArKernelCalibrationEvidenceAdapter.encode(bundle)
        val result = ArKernelCalibrationEvidenceAdapter.parse(text)
        assertTrue(result.accepted)
        assertEquals(ArKernelCalibrationRejection.NONE, result.rejection)
        assertEquals(bundle, result.bundle)
        assertEquals(text,
            ArKernelCalibrationEvidenceAdapter.encode(result.bundle!!))
        val report = ArKernelCalibrationEvidenceAdapter
            .summarize(result.bundle!!)
        assertEquals(3, report.observationCount)
        assertEquals(2, report.poseCount)
        assertEquals(3, report.distinctEvidenceIds)
        assertEquals(1, report.scaleBarCount)
        assertEquals(1, report.surveyCount)
        assertEquals(1, report.sensorGroundTruthCount)
        assertEquals(3, report.heldOutCount)
        assertEquals(3, report.dimensionBinCount)
        assertEquals(0, report.metricPoseCount)
        assertEquals(2, report.orientationOnlyPoseCount)
        assertTrue(report.meanAbsoluteErrorMeters <= 0.01)
        assertTrue(report.maxUncertaintyAdjustedErrorMeters <= 0.01)
        assertTrue(report.maxRelativeError <= 0.02)
        assertTrue(report.evaluationReady)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.bundle!!.qualityGrade)
    }

    @Test
    fun readinessIsNotAQualityGradeAndFailsOnWeakEvidence() {
        val weak = sampleBundle().copy(
            observations = listOf(
                ArKernelCalibrationObservation(
                    0, "obs-01",
                    ArKernelCalibrationProvenance.SCALE_BAR,
                    0.5, 0.9, 0.001, true),
            ),
        )
        val report = ArKernelCalibrationEvidenceAdapter.summarize(weak)
        assertFalse(report.evaluationReady)
        assertFalse(report.minObservationsReady)
        assertFalse(report.minDimensionBinsReady)
        assertFalse(report.meanErrorGateReady)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val bundle = sampleBundle()
        val text = ArKernelCalibrationEvidenceAdapter.encode(bundle)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result = ArKernelCalibrationEvidenceAdapter.parse(variant)
            assertTrue("variant rejected: ${result.detail}",
                result.accepted)
            assertEquals(bundle, result.bundle)
        }
        assertEquals(ArKernelCalibrationRejection.EMPTY_LINE,
            ArKernelCalibrationEvidenceAdapter
                .parse(text + "\n\n").rejection)
    }

    @Test
    fun maximalBoundedEnvelopeStaysCoherent() {
        val observations =
            ArrayList<ArKernelCalibrationObservation>()
        repeat(16) { index ->
            observations += ArKernelCalibrationObservation(
                index,
                "obs-" + index.toString().padStart(2, '0'),
                ArKernelCalibrationProvenance.entries[index % 3],
                minOf(0.25 * (index + 1), 100.0),
                minOf(0.25 * (index + 1), 100.0),
                0.0,
                true,
            )
        }
        val poses = ArrayList<ArKernelCalibrationPose>()
        repeat(32) { index ->
            poses += ArKernelCalibrationPose(
                index,
                (index + 1) * 1_000_000_000L,
                0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0,
                0.05,
                false,
            )
        }
        val bundle = ArKernelCalibrationEvidenceBundle(
            sessionId = "maximal.session.v1",
            deviceToken = "maximal.device.v1",
            observations = observations,
            poses = poses,
            assetExtentMeters = 4.0,
        )
        val text = ArKernelCalibrationEvidenceAdapter.encode(bundle)
        assertTrue(text.length <= 16_384)
        assertTrue(text.lines().all { it.length <= 256 })
        assertEquals(bundle,
            ArKernelCalibrationEvidenceAdapter.parse(text).bundle)
        val report = ArKernelCalibrationEvidenceAdapter.summarize(bundle)
        assertEquals(16, report.observationCount)
        assertEquals(32, report.poseCount)
        assertEquals(32, report.orientationOnlyPoseCount)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = ArKernelCalibrationEvidenceAdapter.encode(sampleBundle())
        val cases = listOf(
            CalibrationRejectionCase("inputTooLarge",
                "x".repeat(16_385),
                ArKernelCalibrationRejection.INPUT_TOO_LARGE),
            CalibrationRejectionCase("empty",
                "",
                ArKernelCalibrationRejection.EMPTY_EVIDENCE),
            CalibrationRejectionCase("badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelCalibrationRejection.INVALID_HEADER),
            CalibrationRejectionCase("badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelCalibrationRejection.INVALID_VALUE),
            CalibrationRejectionCase("assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified",
                    "quality_grade=verified"),
                ArKernelCalibrationRejection.INVALID_QUALITY),
            CalibrationRejectionCase("captureDisabled",
                replaceOnce(text, "capture_opt_in=true",
                    "capture_opt_in=false"),
                ArKernelCalibrationRejection.UNSAFE_INVARIANT),
            CalibrationRejectionCase("poseCaptureDisabled",
                replaceOnce(text, "pose_capture_opt_in=true",
                    "pose_capture_opt_in=false"),
                ArKernelCalibrationRejection.UNSAFE_INVARIANT),
            CalibrationRejectionCase("imagesEnabled",
                replaceOnce(text, "images_disabled=true",
                    "images_disabled=false"),
                ArKernelCalibrationRejection.UNSAFE_INVARIANT),
            CalibrationRejectionCase("automaticCalibrationEnabled",
                replaceOnce(text,
                    "automatic_calibration_disabled=true",
                    "automatic_calibration_disabled=false"),
                ArKernelCalibrationRejection.UNSAFE_INVARIANT),
            CalibrationRejectionCase("automaticUploadEnabled",
                replaceOnce(text,
                    "automatic_upload_disabled=true",
                    "automatic_upload_disabled=false"),
                ArKernelCalibrationRejection.UNSAFE_INVARIANT),
            CalibrationRejectionCase("invalidBoolean",
                replaceOnce(text, "images_disabled=true",
                    "images_disabled=maybe"),
                ArKernelCalibrationRejection.INVALID_BOOLEAN),
            CalibrationRejectionCase("invalidSession",
                replaceOnce(text,
                    "session_id=calib.session.v1", "session_id=bad!id"),
                ArKernelCalibrationRejection.INVALID_TOKEN),
            CalibrationRejectionCase("invalidAssetExtent",
                replaceOnce(text, "asset_extent_m=2",
                    "asset_extent_m=0"),
                ArKernelCalibrationRejection.INVALID_RANGE),
            CalibrationRejectionCase("zeroObservations",
                replaceOnce(text, "observation_count=3",
                    "observation_count=0"),
                ArKernelCalibrationRejection.INVALID_COUNT),
            CalibrationRejectionCase("tooManyObservations",
                replaceOnce(text, "observation_count=3",
                    "observation_count=17"),
                ArKernelCalibrationRejection.INVALID_COUNT),
            CalibrationRejectionCase("tooManyPoses",
                replaceOnce(text, "pose_count=2", "pose_count=33"),
                ArKernelCalibrationRejection.INVALID_COUNT),
            CalibrationRejectionCase("lineCountMismatch",
                replaceOnce(text, "pose_count=2", "pose_count=0"),
                ArKernelCalibrationRejection.INVALID_SCHEMA),
            CalibrationRejectionCase("keyOrder",
                replaceLine(text, 1,
                    "session_id=calib.session.v1"),
                ArKernelCalibrationRejection.INVALID_SCHEMA),
            CalibrationRejectionCase("lineTooLong",
                replaceLine(text, 13, "x".repeat(257)),
                ArKernelCalibrationRejection.LINE_TOO_LONG),
            CalibrationRejectionCase("blankLine",
                replaceLine(text, 13, ""),
                ArKernelCalibrationRejection.EMPTY_LINE),
            CalibrationRejectionCase("extraFinalSeparator",
                text + "\n\n",
                ArKernelCalibrationRejection.EMPTY_LINE),
            CalibrationRejectionCase("missingEnd",
                replaceLine(text, 18, "STOP"),
                ArKernelCalibrationRejection.INVALID_SCHEMA),
            CalibrationRejectionCase("observationOrder",
                replaceOnce(text, "observation=0|",
                    "observation=1|"),
                ArKernelCalibrationRejection.INVALID_ORDER),
            CalibrationRejectionCase("poseOrder",
                replaceOnce(text, "pose=0|", "pose=1|"),
                ArKernelCalibrationRejection.INVALID_ORDER),
            CalibrationRejectionCase("wrongObservationPrefix",
                replaceLine(text, 13, "pose=0|only"),
                ArKernelCalibrationRejection.INVALID_SCHEMA),
            CalibrationRejectionCase("wrongPosePrefix",
                replaceLine(text, 16, "observation=0|only"),
                ArKernelCalibrationRejection.INVALID_SCHEMA),
            CalibrationRejectionCase("observationFieldCount",
                replaceLine(text, 13, "observation=0|only"),
                ArKernelCalibrationRejection.INVALID_SCHEMA),
            CalibrationRejectionCase("poseFieldCount",
                replaceLine(text, 16, "pose=0|only"),
                ArKernelCalibrationRejection.INVALID_SCHEMA),
            CalibrationRejectionCase("invalidObservationToken",
                observationField(text, 13, 1, "bad!id"),
                ArKernelCalibrationRejection.INVALID_TOKEN),
            CalibrationRejectionCase("invalidProvenance",
                observationField(text, 13, 2, "guess"),
                ArKernelCalibrationRejection.INVALID_PROVENANCE),
            CalibrationRejectionCase("invalidReferenceDecimal",
                observationField(text, 13, 3, "0.1.2"),
                ArKernelCalibrationRejection.INVALID_DECIMAL),
            CalibrationRejectionCase("zeroReference",
                observationField(text, 13, 3, "0"),
                ArKernelCalibrationRejection.INVALID_RANGE),
            CalibrationRejectionCase("measuredOutOfBounds",
                observationField(text, 13, 4, "101"),
                ArKernelCalibrationRejection.INVALID_RANGE),
            CalibrationRejectionCase("uncertaintyOutOfBounds",
                observationField(text, 13, 5, "1.1"),
                ArKernelCalibrationRejection.INVALID_RANGE),
            CalibrationRejectionCase("heldOutFalse",
                observationField(text, 13, 6, "false"),
                ArKernelCalibrationRejection.HELD_OUT_REQUIRED),
            CalibrationRejectionCase("invalidHeldOutBoolean",
                observationField(text, 13, 6, "maybe"),
                ArKernelCalibrationRejection.INVALID_BOOLEAN),
            CalibrationRejectionCase("duplicateEvidenceId",
                observationField(text, 14, 1, "obs-01"),
                ArKernelCalibrationRejection.DUPLICATE_EVIDENCE_ID),
            CalibrationRejectionCase("invalidPoseTimestamp",
                poseField(text, 16, 1, "x"),
                ArKernelCalibrationRejection.INVALID_TIMESTAMP),
            CalibrationRejectionCase("nonIncreasingPoseTimestamp",
                poseField(text, 17, 1, "1000000000"),
                ArKernelCalibrationRejection.INVALID_TIMESTAMP),
            CalibrationRejectionCase("invalidPoseDecimal",
                poseField(text, 16, 2, "0.1.2"),
                ArKernelCalibrationRejection.INVALID_DECIMAL),
            CalibrationRejectionCase("negativeZeroPoseDecimal",
                poseField(text, 16, 2, "-0"),
                ArKernelCalibrationRejection.INVALID_DECIMAL),
            CalibrationRejectionCase("nonUnitQuaternion",
                poseField(text, 16, 5, "0.9"),
                ArKernelCalibrationRejection.INVALID_QUATERNION),
            CalibrationRejectionCase("quaternionOutOfBounds",
                poseField(text, 16, 5, "1.5"),
                ArKernelCalibrationRejection.INVALID_QUATERNION),
            CalibrationRejectionCase("orientationOnlyWithTranslation",
                poseField(text, 16, 2, "0.5"),
                ArKernelCalibrationRejection
                    .METRIC_POSITION_REQUIRED_ZERO),
            CalibrationRejectionCase("invalidMetricPositionBoolean",
                poseField(text, 16, 10, "maybe"),
                ArKernelCalibrationRejection.INVALID_BOOLEAN),
        )
        assertEquals(45, cases.size)
        for (case in cases) {
            val result = ArKernelCalibrationEvidenceAdapter.parse(case.text)
            assertFalse("${case.name} unexpectedly accepted",
                result.accepted)
            assertNull("${case.name} leaked a partial bundle",
                result.bundle)
            assertEquals(case.name, case.reason, result.rejection)
            assertTrue("${case.name} lacked a rejection detail",
                result.detail.isNotBlank())
            assertTrue("${case.name} had a negative line number",
                result.lineNumber >= 0)
        }
    }

    @Test
    fun constructorsRejectUnsafeShapesAndNeverInventEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCalibrationObservation(
                0, "obs-01",
                ArKernelCalibrationProvenance.SCALE_BAR,
                0.0, 0.5, 0.001, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCalibrationObservation(
                0, "obs-01",
                ArKernelCalibrationProvenance.SCALE_BAR,
                0.5, 0.5, 0.001, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCalibrationPose(
                0, 1L, 0.5, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0,
                0.05, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCalibrationPose(
                0, 1L, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0,
                0.05, false)
        }
        val bundle = sampleBundle()
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(automaticCalibrationDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(observations = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(poses = listOf(
                bundle.poses[0],
                bundle.poses[1].copy(timestampNs = 1_000_000_000L),
            ))
        }
    }

    private fun sampleBundle(): ArKernelCalibrationEvidenceBundle =
        ArKernelCalibrationEvidenceBundle(
            sessionId = "calib.session.v1",
            deviceToken = "phone.redmi.v1",
            observations = listOf(
                ArKernelCalibrationObservation(
                    0, "obs-01",
                    ArKernelCalibrationProvenance.SCALE_BAR,
                    0.297, 0.299, 0.001, true),
                ArKernelCalibrationObservation(
                    1, "obs-02",
                    ArKernelCalibrationProvenance.SURVEY,
                    0.5, 0.502, 0.001, true),
                ArKernelCalibrationObservation(
                    2, "obs-03",
                    ArKernelCalibrationProvenance.SENSOR_GROUND_TRUTH,
                    2.0, 2.003, 0.002, true),
            ),
            poses = listOf(
                ArKernelCalibrationPose(
                    0, 1_000_000_000L,
                    0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0,
                    0.05, false),
                ArKernelCalibrationPose(
                    1, 2_000_000_000L,
                    0.0, 0.0, 0.0, 0.707107, 0.707107, 0.0, 0.0,
                    0.05, false),
            ),
            assetExtentMeters = 2.0,
        )

    private fun replaceLine(
        text: String,
        index: Int,
        value: String,
    ): String {
        val lines = text.split("\n").toMutableList()
        lines[index] = value
        return lines.joinToString("\n")
    }

    private fun replaceOnce(
        text: String,
        old: String,
        new: String,
    ): String {
        val at = text.indexOf(old)
        check(at >= 0) { "Missing replacement anchor: $old" }
        return text.substring(0, at) + new +
            text.substring(at + old.length)
    }

    private fun observationField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String = replaceField(text, lineIndex, fieldIndex,
        value, "observation=")

    private fun poseField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String = replaceField(text, lineIndex, fieldIndex,
        value, "pose=")

    private fun replaceField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
        prefix: String,
    ): String {
        val lines = text.split("\n").toMutableList()
        val fields = lines[lineIndex].removePrefix(prefix)
            .split("|").toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
