package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class FeedRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelMeasurementFeedRejection,
)

class ArKernelMeasurementFeedTest {
    companion object {
        private const val FIRST_MARKER_INDEX = 28
        private const val END_INDEX = 32
        private const val SCALE = "668.333333"
        private const val ESTIMATE = "0.448878"
        private val EXPECTED_LINES = listOf(
            "ARKFEED1",
            "version=1",
            "session_id=feed.session.v1",
            "device_token=phone.redmi.v1",
            "capture_index=0",
            "frames_observed=120",
            "frames_exported=0",
            "camera_preview_opt_in=true",
            "image_export_disabled=true",
            "automatic_calibration_disabled=true",
            "automatic_upload_disabled=true",
            "signature_verification_disabled=true",
            "physical_validation_disabled=true",
            "metric_accuracy_claimed=false",
            "per_device_calibration=true",
            "universal_calibration_disabled=true",
            "manual_upload_only=true",
            "user_confirmed=true",
            "scene_kind=indoor_scale",
            "condition=indoor",
            "held_out=false",
            "reference_m=0.3",
            "reference_uncertainty_m=0.001",
            "reference_pixel_distance=200.5",
            "target_pixel_distance=300",
            "scale_px_per_m=668.333333",
            "estimated_target_m=0.448878",
            "quality_grade=unverified",
            "reference_marker=0|100.25|200.5",
            "reference_marker=1|300.75|200.5",
            "target_marker=0|120|400",
            "target_marker=1|420|400",
            "END",
        )
        private val EXPECTED_TEXT = EXPECTED_LINES.joinToString("\n")
        private val MARKERS = listOf(
            ArKernelMeasurementFeedMarker(100.25, 200.5),
            ArKernelMeasurementFeedMarker(300.75, 200.5),
            ArKernelMeasurementFeedMarker(120.0, 400.0),
            ArKernelMeasurementFeedMarker(420.0, 400.0),
        )
    }

    @Test
    fun canonicalRecordRoundTripsWithOnlyStructuralFacts() {
        val parsed = ArKernelMeasurementFeedAdapter.parse(EXPECTED_TEXT)
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(ArKernelMeasurementFeedRejection.NONE,
            parsed.rejection)
        assertEquals(EXPECTED_TEXT,
            ArKernelMeasurementFeedAdapter.encode(parsed.bundle!!))
        val report = ArKernelMeasurementFeedAdapter.summarize(
            parsed.bundle!!)
        assertEquals(4, report.markerCount)
        assertEquals(120L, report.framesObserved)
        assertEquals(0, report.framesExported)
        assertTrue(report.invariantsHeld)
        assertTrue(report.geometryConsistent)
        assertTrue(report.captureReady)
        assertFalse(report.heldOut)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", parsed.bundle!!.qualityGrade)
        assertFalse(parsed.bundle!!.metricAccuracyClaimed)
        assertEquals(ArKernelMeasurementFeedRejection.NONE,
            ArKernelMeasurementFeedAdapter.verify(EXPECTED_TEXT)
                .rejection)
    }

    @Test
    fun createComputesScreenRatioWithoutClaimingMetricAccuracy() {
        val created = ArKernelMeasurementFeedAdapter.create(
            sessionId = "feed.session.v1",
            deviceToken = "phone.redmi.v1",
            captureIndex = 0,
            framesObserved = 120L,
            sceneKind = ArKernelCampaignSceneKind.INDOOR_SCALE,
            condition = ArKernelMeasurementCondition.INDOOR,
            heldOut = false,
            referenceMeters = 0.3,
            referenceUncertaintyMeters = 0.001,
            markers = MARKERS,
            userConfirmed = true,
        )
        assertTrue(created.detail, created.accepted)
        assertEquals(EXPECTED_TEXT,
            ArKernelMeasurementFeedAdapter.encode(created.bundle!!))
        val report = ArKernelMeasurementFeedAdapter.summarize(
            created.bundle!!)
        assertEquals(200.5, report.referencePixelDistance, 0.000001)
        assertEquals(300.0, report.targetPixelDistance, 0.000001)
        assertEquals(668.333333, report.scalePxPerM, 0.000001)
        assertEquals(0.448878, report.estimatedTargetM, 0.000001)
        assertTrue(report.captureReady)
    }

    @Test
    fun createRejectsUnsafeOrUnconfirmedCaptures() {
        fun create(
            markers: List<ArKernelMeasurementFeedMarker> = MARKERS,
            referenceMeters: Double = 0.3,
            userConfirmed: Boolean = true,
            captureIndex: Int = 0,
            framesObserved: Long = 120L,
        ) = ArKernelMeasurementFeedAdapter.create(
            "feed.session.v1", "phone.redmi.v1",
            captureIndex, framesObserved,
            ArKernelCampaignSceneKind.INDOOR_SCALE,
            ArKernelMeasurementCondition.INDOOR,
            false, referenceMeters, 0.001, markers,
            userConfirmed)
        assertEquals(
            ArKernelMeasurementFeedRejection.UNCONFIRMED_CAPTURE,
            create(userConfirmed = false).rejection)
        assertEquals(
            ArKernelMeasurementFeedRejection.INVALID_MARKER,
            create(markers = MARKERS.take(3)).rejection)
        assertEquals(
            ArKernelMeasurementFeedRejection.INVALID_GEOMETRY,
            create(markers = listOf(
                MARKERS[0], MARKERS[0], MARKERS[2], MARKERS[3],
            )).rejection)
        assertEquals(
            ArKernelMeasurementFeedRejection.INVALID_RANGE,
            create(referenceMeters = 0.001).rejection)
        assertEquals(
            ArKernelMeasurementFeedRejection.INVALID_COUNT,
            create(captureIndex = 10_000).rejection)
        assertEquals(
            ArKernelMeasurementFeedRejection.INVALID_COUNT,
            create(framesObserved = 10_000_001L).rejection)
        assertEquals(
            ArKernelMeasurementFeedRejection.INVALID_TOKEN,
            ArKernelMeasurementFeedAdapter.create(
                "bad!id", "phone.redmi.v1", 0, 120L,
                ArKernelCampaignSceneKind.INDOOR_SCALE,
                ArKernelMeasurementCondition.INDOOR,
                false, 0.3, 0.001, MARKERS, true).rejection)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val variants = listOf(
            EXPECTED_TEXT,
            EXPECTED_TEXT + "\n",
            "\uFEFF" + EXPECTED_TEXT,
            "\uFEFF" + EXPECTED_TEXT.replace("\n", "\r\n") +
                "\r\n",
            EXPECTED_TEXT.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result = ArKernelMeasurementFeedAdapter.parse(variant)
            assertTrue(result.detail, result.accepted)
            assertEquals(EXPECTED_TEXT,
                ArKernelMeasurementFeedAdapter.encode(result.bundle!!))
        }
        assertEquals(ArKernelMeasurementFeedRejection.EMPTY_LINE,
            ArKernelMeasurementFeedAdapter.parse(
                EXPECTED_TEXT + "\n\n").rejection)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = EXPECTED_TEXT
        val row0 = text.split("\n")[FIRST_MARKER_INDEX]
        val cases = listOf(
            FeedRejectionCase(
                "inputTooLarge",
                "x".repeat(16_385),
                ArKernelMeasurementFeedRejection.INPUT_TOO_LARGE,
            ),
            FeedRejectionCase(
                "empty",
                "",
                ArKernelMeasurementFeedRejection.EMPTY_RECORD,
            ),
            FeedRejectionCase(
                "badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelMeasurementFeedRejection.INVALID_HEADER,
            ),
            FeedRejectionCase(
                "headerWithSpace",
                replaceLine(text, 0, "ARKFEED1 x"),
                ArKernelMeasurementFeedRejection.INVALID_HEADER,
            ),
            FeedRejectionCase(
                "badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
            ),
            FeedRejectionCase(
                "assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified", "quality_grade=verified"),
                ArKernelMeasurementFeedRejection.INVALID_QUALITY,
            ),
            FeedRejectionCase(
                "invalidBoolean",
                replaceOnce(text, "image_export_disabled=true", "image_export_disabled=maybe"),
                ArKernelMeasurementFeedRejection.INVALID_BOOLEAN,
            ),
            FeedRejectionCase(
                "cameraOptInFalse",
                replaceOnce(text, "camera_preview_opt_in=true", "camera_preview_opt_in=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "imageExportEnabled",
                replaceOnce(text, "image_export_disabled=true", "image_export_disabled=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "autoCalibrationEnabled",
                replaceOnce(text, "automatic_calibration_disabled=true",
                    "automatic_calibration_disabled=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "autoUploadEnabled",
                replaceOnce(text, "automatic_upload_disabled=true",
                    "automatic_upload_disabled=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "signatureEnabled",
                replaceOnce(text, "signature_verification_disabled=true",
                    "signature_verification_disabled=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "physicalValidationEnabled",
                replaceOnce(text, "physical_validation_disabled=true",
                    "physical_validation_disabled=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "metricAccuracyClaimed",
                replaceOnce(text, "metric_accuracy_claimed=false", "metric_accuracy_claimed=true"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "perDeviceFalse",
                replaceOnce(text, "per_device_calibration=true", "per_device_calibration=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "universalEnabled",
                replaceOnce(text, "universal_calibration_disabled=true",
                    "universal_calibration_disabled=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "manualUploadFalse",
                replaceOnce(text, "manual_upload_only=true", "manual_upload_only=false"),
                ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
            ),
            FeedRejectionCase(
                "unconfirmedCapture",
                replaceOnce(text, "user_confirmed=true", "user_confirmed=false"),
                ArKernelMeasurementFeedRejection.UNCONFIRMED_CAPTURE,
            ),
            FeedRejectionCase(
                "framesExported",
                replaceOnce(text, "frames_exported=0", "frames_exported=1"),
                ArKernelMeasurementFeedRejection.FRAME_EXPORT_NONZERO,
            ),
            FeedRejectionCase(
                "invalidSessionToken",
                replaceOnce(text, "session_id=feed.session.v1", "session_id=bad!id"),
                ArKernelMeasurementFeedRejection.INVALID_TOKEN,
            ),
            FeedRejectionCase(
                "invalidDeviceToken",
                replaceOnce(text, "device_token=phone.redmi.v1", "device_token=bad!id"),
                ArKernelMeasurementFeedRejection.INVALID_TOKEN,
            ),
            FeedRejectionCase(
                "invalidCaptureIndex",
                replaceOnce(text, "capture_index=0", "capture_index=maybe"),
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
            ),
            FeedRejectionCase(
                "captureIndexTooLarge",
                replaceOnce(text, "capture_index=0", "capture_index=10000"),
                ArKernelMeasurementFeedRejection.INVALID_COUNT,
            ),
            FeedRejectionCase(
                "invalidFrameCount",
                replaceOnce(text, "frames_observed=120", "frames_observed=maybe"),
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
            ),
            FeedRejectionCase(
                "frameCountTooLarge",
                replaceOnce(text, "frames_observed=120", "frames_observed=10000001"),
                ArKernelMeasurementFeedRejection.INVALID_COUNT,
            ),
            FeedRejectionCase(
                "invalidSceneKind",
                replaceOnce(text, "scene_kind=indoor_scale", "scene_kind=magic"),
                ArKernelMeasurementFeedRejection.INVALID_KIND,
            ),
            FeedRejectionCase(
                "invalidCondition",
                replaceOnce(text, "condition=indoor", "condition=rainy"),
                ArKernelMeasurementFeedRejection.INVALID_CONDITION,
            ),
            FeedRejectionCase(
                "referenceTooSmall",
                replaceOnce(text, "reference_m=0.3", "reference_m=0.001"),
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
            ),
            FeedRejectionCase(
                "uncertaintyTooLarge",
                replaceOnce(text, "reference_uncertainty_m=0.001", "reference_uncertainty_m=2"),
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
            ),
            FeedRejectionCase(
                "invalidDerivedDecimal",
                replaceOnce(text, SCALE, "1.2.3"),
                ArKernelMeasurementFeedRejection.INVALID_DECIMAL,
            ),
            FeedRejectionCase(
                "derivedNonPositive",
                replaceOnce(text, SCALE, "0"),
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
            ),
            FeedRejectionCase(
                "geometryMismatch",
                replaceOnce(text, ESTIMATE, "9"),
                ArKernelMeasurementFeedRejection.INVALID_GEOMETRY,
            ),
            FeedRejectionCase(
                "keyOrder",
                replaceLine(text, 1, "session_id=feed.session.v1"),
                ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
            ),
            FeedRejectionCase(
                "spaceInMetadata",
                replaceOnce(text, "version=1", "version=1 x"),
                ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
            ),
            FeedRejectionCase(
                "lineTooLong",
                replaceLine(text, 5, "x".repeat(257)),
                ArKernelMeasurementFeedRejection.LINE_TOO_LONG,
            ),
            FeedRejectionCase(
                "blankLine",
                replaceLine(text, 5, ""),
                ArKernelMeasurementFeedRejection.EMPTY_LINE,
            ),
            FeedRejectionCase(
                "markerOrder",
                replaceLine(text, FIRST_MARKER_INDEX, "target_marker=0|1|2"),
                ArKernelMeasurementFeedRejection.INVALID_ORDER,
            ),
            FeedRejectionCase(
                "markerFieldCount",
                replaceLine(text, FIRST_MARKER_INDEX, row0 + "|3"),
                ArKernelMeasurementFeedRejection.INVALID_MARKER,
            ),
            FeedRejectionCase(
                "markerOutOfBounds",
                replaceOnce(text, "reference_marker=0|100.25|200.5",
                    "reference_marker=0|999999|200.5"),
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
            ),
            FeedRejectionCase(
                "missingEnd",
                replaceLine(text, END_INDEX, "STOP"),
                ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
            ),
            FeedRejectionCase(
                "extraLine",
                text + "\nextra=1",
                ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
            ),
            FeedRejectionCase(
                "extraFinalSeparator",
                text + "\n\n",
                ArKernelMeasurementFeedRejection.EMPTY_LINE,
            ),
        )
        assertEquals(42, cases.size)
        for (case in cases) {
            val result = ArKernelMeasurementFeedAdapter.parse(case.text)
            assertFalse("${case.name} unexpectedly accepted",
                result.accepted)
            assertNull("${case.name} leaked a partial bundle",
                result.bundle)
            assertEquals(case.name, case.reason,
                result.rejection)
            assertTrue("${case.name} lacked a rejection detail",
                result.detail.isNotBlank())
            assertTrue("${case.name} had a negative line number",
                result.lineNumber >= 0)
        }
    }

    @Test
    fun constructorsRejectUnsafeShapes() {
        val bundle = ArKernelMeasurementFeedAdapter.parse(
            EXPECTED_TEXT).bundle!!
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(imageExportDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(metricAccuracyClaimed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(userConfirmed = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(framesExported = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(estimatedTargetM = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelMeasurementFeedMarker(-1.0, 10.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelMeasurementFeedMarker(
                Double.NaN, 10.0)
        }
    }

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
}
