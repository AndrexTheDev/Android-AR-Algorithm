package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class RulerRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelRulerScaleRejection,
)

class ArKernelRulerScaleTest {
    companion object {
        private const val FIRST_MARKER_INDEX = 36
        private const val FIRST_TICK_INDEX = 40
        private const val END_INDEX = 140
        private const val SCALE = "1980"
        private const val SPACING = "20"
        private val EXPECTED_LINES = listOf(
            "ARKSCALE1",
            "version=1",
            "session_id=scale.session.v1",
            "device_token=phone.redmi.v1",
            "capture_index=0",
            "frames_observed=240",
            "frames_analyzed=1",
            "frames_exported=0",
            "sample_count=512",
            "frame_analysis_opt_in=true",
            "frame_persistence_disabled=true",
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
            "ruler_detection_confirmed=true",
            "scene_kind=indoor_scale",
            "condition=indoor",
            "held_out=false",
            "stated_folded_m=1",
            "tick_count=100",
            "expected_tick_count=100",
            "tick_spacing_px=20",
            "tick_regularity=1",
            "tick_count_consistent=true",
            "reference_pixel_distance=1980",
            "target_pixel_distance=1000",
            "scale_px_per_m=1980",
            "estimated_target_m=0.505051",
            "quality_grade=unverified",
            "reference_marker=0|100|300",
            "reference_marker=1|2080|300",
            "target_marker=0|120|500",
            "target_marker=1|1120|500",
            "tick=0|100|300",
            "tick=1|120|300",
            "tick=2|140|300",
            "tick=3|160|300",
            "tick=4|180|300",
            "tick=5|200|300",
            "tick=6|220|300",
            "tick=7|240|300",
            "tick=8|260|300",
            "tick=9|280|300",
            "tick=10|300|300",
            "tick=11|320|300",
            "tick=12|340|300",
            "tick=13|360|300",
            "tick=14|380|300",
            "tick=15|400|300",
            "tick=16|420|300",
            "tick=17|440|300",
            "tick=18|460|300",
            "tick=19|480|300",
            "tick=20|500|300",
            "tick=21|520|300",
            "tick=22|540|300",
            "tick=23|560|300",
            "tick=24|580|300",
            "tick=25|600|300",
            "tick=26|620|300",
            "tick=27|640|300",
            "tick=28|660|300",
            "tick=29|680|300",
            "tick=30|700|300",
            "tick=31|720|300",
            "tick=32|740|300",
            "tick=33|760|300",
            "tick=34|780|300",
            "tick=35|800|300",
            "tick=36|820|300",
            "tick=37|840|300",
            "tick=38|860|300",
            "tick=39|880|300",
            "tick=40|900|300",
            "tick=41|920|300",
            "tick=42|940|300",
            "tick=43|960|300",
            "tick=44|980|300",
            "tick=45|1000|300",
            "tick=46|1020|300",
            "tick=47|1040|300",
            "tick=48|1060|300",
            "tick=49|1080|300",
            "tick=50|1100|300",
            "tick=51|1120|300",
            "tick=52|1140|300",
            "tick=53|1160|300",
            "tick=54|1180|300",
            "tick=55|1200|300",
            "tick=56|1220|300",
            "tick=57|1240|300",
            "tick=58|1260|300",
            "tick=59|1280|300",
            "tick=60|1300|300",
            "tick=61|1320|300",
            "tick=62|1340|300",
            "tick=63|1360|300",
            "tick=64|1380|300",
            "tick=65|1400|300",
            "tick=66|1420|300",
            "tick=67|1440|300",
            "tick=68|1460|300",
            "tick=69|1480|300",
            "tick=70|1500|300",
            "tick=71|1520|300",
            "tick=72|1540|300",
            "tick=73|1560|300",
            "tick=74|1580|300",
            "tick=75|1600|300",
            "tick=76|1620|300",
            "tick=77|1640|300",
            "tick=78|1660|300",
            "tick=79|1680|300",
            "tick=80|1700|300",
            "tick=81|1720|300",
            "tick=82|1740|300",
            "tick=83|1760|300",
            "tick=84|1780|300",
            "tick=85|1800|300",
            "tick=86|1820|300",
            "tick=87|1840|300",
            "tick=88|1860|300",
            "tick=89|1880|300",
            "tick=90|1900|300",
            "tick=91|1920|300",
            "tick=92|1940|300",
            "tick=93|1960|300",
            "tick=94|1980|300",
            "tick=95|2000|300",
            "tick=96|2020|300",
            "tick=97|2040|300",
            "tick=98|2060|300",
            "tick=99|2080|300",
            "END",
        )
        private val EXPECTED_TEXT = EXPECTED_LINES.joinToString("\n")
        private const val TICK_COUNT = 100
        private const val TICK_SPACING = 20.0
        private val TICKS = (0 until TICK_COUNT).map { index ->
            ArKernelRulerTickRow(
                index, 100.0 + index * TICK_SPACING, 300.0)
        }
        private val REFERENCE_START =
            ArKernelMeasurementFeedMarker(100.0, 300.0)
        private val REFERENCE_END =
            ArKernelMeasurementFeedMarker(
                100.0 + (TICK_COUNT - 1) * TICK_SPACING, 300.0)
        private val TARGET_START =
            ArKernelMeasurementFeedMarker(120.0, 500.0)
        private val TARGET_END =
            ArKernelMeasurementFeedMarker(1120.0, 500.0)
    }

    @Test
    fun canonicalRecordRoundTripsWithOnlyStructuralFacts() {
        val parsed = ArKernelRulerScaleAdapter.parse(EXPECTED_TEXT)
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(ArKernelRulerScaleRejection.NONE,
            parsed.rejection)
        assertEquals(EXPECTED_TEXT,
            ArKernelRulerScaleAdapter.encode(parsed.bundle!!))
        val report = ArKernelRulerScaleAdapter.summarize(
            parsed.bundle!!)
        assertEquals(100, report.tickCount)
        assertEquals(100, report.expectedTickCount)
        assertTrue(report.tickCountConsistent)
        assertEquals(20.0, report.tickSpacingPx, 0.000001)
        assertEquals(1.0, report.tickRegularity, 0.000001)
        assertEquals(1980.0, report.scalePxPerM, 0.000001)
        assertEquals(0.505051, report.estimatedTargetM, 0.000001)
        assertEquals(1L, report.framesAnalyzed)
        assertEquals(0, report.framesExported)
        assertTrue(report.invariantsHeld)
        assertTrue(report.geometryConsistent)
        assertTrue(report.captureReady)
        assertEquals("unverified", report.qualityGrade)
        assertFalse(parsed.bundle!!.metricAccuracyClaimed)
        assertEquals(ArKernelRulerScaleRejection.NONE,
            ArKernelRulerScaleAdapter.verify(EXPECTED_TEXT)
                .rejection)
    }

    @Test
    fun detectedTicksMatchTheStatedMetreExactly() {
        val created = ArKernelRulerScaleAdapter.create(
            sessionId = "scale.session.v1",
            deviceToken = "phone.redmi.v1",
            captureIndex = 0,
            framesObserved = 240L,
            framesAnalyzed = 1L,
            sampleCount = 512,
            sceneKind = ArKernelCampaignSceneKind.INDOOR_SCALE,
            condition = ArKernelMeasurementCondition.INDOOR,
            heldOut = false,
            statedFoldedMeters = 1.0,
            ticks = TICKS,
            referenceStart = REFERENCE_START,
            referenceEnd = REFERENCE_END,
            targetStart = TARGET_START,
            targetEnd = TARGET_END,
            userConfirmed = true,
            rulerDetectionConfirmed = true,
        )
        assertTrue(created.detail, created.accepted)
        assertEquals(EXPECTED_TEXT,
            ArKernelRulerScaleAdapter.encode(created.bundle!!))
    }

    @Test
    fun halfMetreStatementYieldsFiftyConsistentTicks() {
        val half = TICKS.take(50)
        val created = ArKernelRulerScaleAdapter.create(
            sessionId = "scale.session.v1",
            deviceToken = "phone.redmi.v1",
            captureIndex = 1,
            framesObserved = 240L,
            framesAnalyzed = 1L,
            sampleCount = 512,
            sceneKind = ArKernelCampaignSceneKind.INDOOR_SCALE,
            condition = ArKernelMeasurementCondition.INDOOR,
            heldOut = true,
            statedFoldedMeters = 0.5,
            ticks = half,
            referenceStart = REFERENCE_START,
            referenceEnd = ArKernelMeasurementFeedMarker(
                100.0 + 49 * TICK_SPACING, 300.0),
            targetStart = TARGET_START,
            targetEnd = TARGET_END,
            userConfirmed = true,
            rulerDetectionConfirmed = true,
        )
        assertTrue(created.detail, created.accepted)
        val report = ArKernelRulerScaleAdapter.summarize(
            created.bundle!!)
        assertEquals(50, report.tickCount)
        assertEquals(50, report.expectedTickCount)
        assertTrue(report.tickCountConsistent)
        assertTrue(report.heldOut)
        assertTrue(report.captureReady)
    }

    @Test
    fun tickDetectorFindsEvenlySpacedDarkMarks() {
        val size = 512
        val luma = IntArray(size) { 220 }
        var index = 16
        repeat(40) {
            if (index + 1 < size) {
                luma[index] = 40
                luma[index + 1] = 40
            }
            index += 8
        }
        val detection = ArKernelRulerTickDetector.detect(luma)
        assertTrue(detection.detected)
        assertEquals(40, detection.indices.size)
        assertEquals(8.0, detection.spacing, 0.000001)
        assertEquals(1.0, detection.regularity, 0.000001)
        assertEquals(512, detection.sampleCount)
        val flat = ArKernelRulerTickDetector.detect(
            IntArray(size) { 200 })
        assertFalse(flat.detected)
        assertEquals(0.0, flat.regularity, 0.000001)
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRulerTickDetector.detect(IntArray(8) { 100 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRulerTickDetector.detect(
                IntArray(512) { if (it == 3) 300 else 100 })
        }
    }

    @Test
    fun createRejectsUnconfirmedOrUndetectedRulers() {
        fun create(
            ticks: List<ArKernelRulerTickRow> = TICKS,
            userConfirmed: Boolean = true,
            rulerDetectionConfirmed: Boolean = true,
            folded: Double = 1.0,
            analyzed: Long = 1L,
            samples: Int = 512,
            referenceEnd: ArKernelMeasurementFeedMarker =
                REFERENCE_END,
        ) = ArKernelRulerScaleAdapter.create(
            sessionId = "scale.session.v1",
            deviceToken = "phone.redmi.v1",
            captureIndex = 0,
            framesObserved = 240L,
            framesAnalyzed = analyzed,
            sampleCount = samples,
            sceneKind = ArKernelCampaignSceneKind.INDOOR_SCALE,
            condition = ArKernelMeasurementCondition.INDOOR,
            heldOut = false,
            statedFoldedMeters = folded,
            ticks = ticks,
            referenceStart = REFERENCE_START,
            referenceEnd = referenceEnd,
            targetStart = TARGET_START,
            targetEnd = TARGET_END,
            userConfirmed = userConfirmed,
            rulerDetectionConfirmed = rulerDetectionConfirmed,
        )
        assertEquals(
            ArKernelRulerScaleRejection.UNCONFIRMED_CAPTURE,
            create(userConfirmed = false).rejection)
        assertEquals(
            ArKernelRulerScaleRejection.RULER_NOT_CONFIRMED,
            create(rulerDetectionConfirmed = false).rejection)
        assertEquals(
            ArKernelRulerScaleRejection.RULER_NOT_DETECTED,
            create(ticks = TICKS.take(1)).rejection)
        assertEquals(
            ArKernelRulerScaleRejection.INVALID_RANGE,
            create(folded = 0.01).rejection)
        assertEquals(
            ArKernelRulerScaleRejection
                .FRAMES_ANALYZED_EXCEEDS_OBSERVED,
            create(analyzed = 241L).rejection)
        assertEquals(
            ArKernelRulerScaleRejection.INVALID_SAMPLE_COUNT,
            create(samples = 31).rejection)
        assertEquals(
            ArKernelRulerScaleRejection.INVALID_GEOMETRY,
            create(referenceEnd = REFERENCE_START).rejection)
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
            val result = ArKernelRulerScaleAdapter.parse(variant)
            assertTrue(result.detail, result.accepted)
            assertEquals(EXPECTED_TEXT,
                ArKernelRulerScaleAdapter.encode(result.bundle!!))
        }
        assertEquals(ArKernelRulerScaleRejection.EMPTY_LINE,
            ArKernelRulerScaleAdapter.parse(
                EXPECTED_TEXT + "\n\n").rejection)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = EXPECTED_TEXT
        val row0 = text.split("\n")[FIRST_MARKER_INDEX]
        val cases = listOf(
            RulerRejectionCase(
                "inputTooLarge",
                "x".repeat(16_385),
                ArKernelRulerScaleRejection.INPUT_TOO_LARGE,
            ),
            RulerRejectionCase(
                "empty",
                "",
                ArKernelRulerScaleRejection.EMPTY_RECORD,
            ),
            RulerRejectionCase(
                "badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelRulerScaleRejection.INVALID_HEADER,
            ),
            RulerRejectionCase(
                "badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelRulerScaleRejection.INVALID_VALUE,
            ),
            RulerRejectionCase(
                "assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified", "quality_grade=verified"),
                ArKernelRulerScaleRejection.INVALID_QUALITY,
            ),
            RulerRejectionCase(
                "invalidBoolean",
                replaceOnce(text, "image_export_disabled=true", "image_export_disabled=maybe"),
                ArKernelRulerScaleRejection.INVALID_BOOLEAN,
            ),
            RulerRejectionCase(
                "frameAnalysisOff",
                replaceOnce(text, "frame_analysis_opt_in=true", "frame_analysis_opt_in=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "framePersistenceEnabled",
                replaceOnce(text, "frame_persistence_disabled=true",
                    "frame_persistence_disabled=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "imageExportEnabled",
                replaceOnce(text, "image_export_disabled=true", "image_export_disabled=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "autoCalibrationEnabled",
                replaceOnce(text, "automatic_calibration_disabled=true",
                    "automatic_calibration_disabled=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "autoUploadEnabled",
                replaceOnce(text, "automatic_upload_disabled=true",
                    "automatic_upload_disabled=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "signatureEnabled",
                replaceOnce(text, "signature_verification_disabled=true",
                    "signature_verification_disabled=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "physicalValidationEnabled",
                replaceOnce(text, "physical_validation_disabled=true",
                    "physical_validation_disabled=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "metricAccuracyClaimed",
                replaceOnce(text, "metric_accuracy_claimed=false", "metric_accuracy_claimed=true"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "perDeviceFalse",
                replaceOnce(text, "per_device_calibration=true", "per_device_calibration=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "universalEnabled",
                replaceOnce(text, "universal_calibration_disabled=true",
                    "universal_calibration_disabled=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "manualUploadFalse",
                replaceOnce(text, "manual_upload_only=true", "manual_upload_only=false"),
                ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
            ),
            RulerRejectionCase(
                "unconfirmedCapture",
                replaceOnce(text, "user_confirmed=true", "user_confirmed=false"),
                ArKernelRulerScaleRejection.UNCONFIRMED_CAPTURE,
            ),
            RulerRejectionCase(
                "rulerNotConfirmed",
                replaceOnce(text, "ruler_detection_confirmed=true",
                    "ruler_detection_confirmed=false"),
                ArKernelRulerScaleRejection.RULER_NOT_CONFIRMED,
            ),
            RulerRejectionCase(
                "framesExported",
                replaceOnce(text, "frames_exported=0", "frames_exported=1"),
                ArKernelRulerScaleRejection.FRAMES_EXPORTED_NONZERO,
            ),
            RulerRejectionCase(
                "analyzedExceedsObserved",
                replaceOnce(text, "frames_analyzed=1", "frames_analyzed=241"),
                ArKernelRulerScaleRejection.FRAMES_ANALYZED_EXCEEDS_OBSERVED,
            ),
            RulerRejectionCase(
                "invalidSessionToken",
                replaceOnce(text, "session_id=scale.session.v1", "session_id=bad!id"),
                ArKernelRulerScaleRejection.INVALID_TOKEN,
            ),
            RulerRejectionCase(
                "invalidDeviceToken",
                replaceOnce(text, "device_token=phone.redmi.v1", "device_token=bad!id"),
                ArKernelRulerScaleRejection.INVALID_TOKEN,
            ),
            RulerRejectionCase(
                "captureIndexTooLarge",
                replaceOnce(text, "capture_index=0", "capture_index=10000"),
                ArKernelRulerScaleRejection.INVALID_COUNT,
            ),
            RulerRejectionCase(
                "sampleCountTooSmall",
                replaceOnce(text, "sample_count=512", "sample_count=31"),
                ArKernelRulerScaleRejection.INVALID_SAMPLE_COUNT,
            ),
            RulerRejectionCase(
                "sampleCountTooLarge",
                replaceOnce(text, "sample_count=512", "sample_count=1025"),
                ArKernelRulerScaleRejection.INVALID_SAMPLE_COUNT,
            ),
            RulerRejectionCase(
                "invalidSceneKind",
                replaceOnce(text, "scene_kind=indoor_scale", "scene_kind=magic"),
                ArKernelRulerScaleRejection.INVALID_KIND,
            ),
            RulerRejectionCase(
                "invalidCondition",
                replaceOnce(text, "condition=indoor", "condition=rainy"),
                ArKernelRulerScaleRejection.INVALID_CONDITION,
            ),
            RulerRejectionCase(
                "foldedTooSmall",
                replaceOnce(text, "stated_folded_m=1", "stated_folded_m=0.01"),
                ArKernelRulerScaleRejection.INVALID_RANGE,
            ),
            RulerRejectionCase(
                "expectedTickMismatch",
                replaceOnce(text, "expected_tick_count=100", "expected_tick_count=50"),
                ArKernelRulerScaleRejection.INVALID_VALUE,
            ),
            RulerRejectionCase(
                "lineCountMismatch",
                replaceOnce(text, "tick_count=100", "tick_count=10"),
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
            ),
            RulerRejectionCase(
                "tickCountOne",
                withTickCount(text, 1),
                ArKernelRulerScaleRejection.RULER_NOT_DETECTED,
            ),
            RulerRejectionCase(
                "tickCountTooLarge",
                withTickCount(text, 201),
                ArKernelRulerScaleRejection.TICK_COUNT_TOO_LARGE,
            ),
            RulerRejectionCase(
                "regularityOutOfRange",
                replaceOnce(text, "tick_regularity=1", "tick_regularity=2"),
                ArKernelRulerScaleRejection.INVALID_RANGE,
            ),
            RulerRejectionCase(
                "consistencyFlagWrong",
                replaceOnce(text, "tick_count_consistent=true", "tick_count_consistent=false"),
                ArKernelRulerScaleRejection.INVALID_VALUE,
            ),
            RulerRejectionCase(
                "spacingMismatch",
                replaceOnce(text, SPACING, "7"),
                ArKernelRulerScaleRejection.INVALID_GEOMETRY,
            ),
            RulerRejectionCase(
                "scaleMismatch",
                replaceOnce(text, SCALE, "400"),
                ArKernelRulerScaleRejection.INVALID_GEOMETRY,
            ),
            RulerRejectionCase(
                "keyOrder",
                replaceLine(text, 1, "session_id=scale.session.v1"),
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
            ),
            RulerRejectionCase(
                "spaceInMetadata",
                replaceOnce(text, "version=1", "version=1 x"),
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
            ),
            RulerRejectionCase(
                "lineTooLong",
                replaceLine(text, 5, "x".repeat(257)),
                ArKernelRulerScaleRejection.LINE_TOO_LONG,
            ),
            RulerRejectionCase(
                "blankLine",
                replaceLine(text, 5, ""),
                ArKernelRulerScaleRejection.EMPTY_LINE,
            ),
            RulerRejectionCase(
                "markerOrder",
                replaceLine(text, FIRST_MARKER_INDEX, "target_marker=0|1|2"),
                ArKernelRulerScaleRejection.INVALID_MARKER,
            ),
            RulerRejectionCase(
                "markerFieldCount",
                replaceLine(text, FIRST_MARKER_INDEX, row0 + "|3"),
                ArKernelRulerScaleRejection.INVALID_MARKER,
            ),
            RulerRejectionCase(
                "tickNotTick",
                replaceLine(text, FIRST_TICK_INDEX, "other=0|1|2"),
                ArKernelRulerScaleRejection.INVALID_TICK,
            ),
            RulerRejectionCase(
                "tickIndexSkip",
                replaceOnce(text, "tick=1|", "tick=9|"),
                ArKernelRulerScaleRejection.INVALID_TICK,
            ),
            RulerRejectionCase(
                "missingEnd",
                replaceLine(text, END_INDEX, "STOP"),
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
            ),
            RulerRejectionCase(
                "extraLine",
                text + "\nextra=1",
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
            ),
            RulerRejectionCase(
                "extraFinalSeparator",
                text + "\n\n",
                ArKernelRulerScaleRejection.EMPTY_LINE,
            ),
        )
        assertEquals(48, cases.size)
        for (case in cases) {
            val result = ArKernelRulerScaleAdapter.parse(case.text)
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
        val bundle = ArKernelRulerScaleAdapter.parse(
            EXPECTED_TEXT).bundle!!
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(imageExportDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(framePersistenceDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(metricAccuracyClaimed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(userConfirmed = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(rulerDetectionConfirmed = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(framesExported = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRulerTickRow(0, -1.0, 10.0)
        }
    }

    private fun withTickCount(
        text: String,
        count: Int,
    ): String {
        val parsed = ArKernelRulerScaleAdapter.parse(text)
        check(parsed.accepted) { "Fixture text no longer parses" }
        val rows = (0 until count).map { index ->
            if (index < TICK_COUNT) TICKS[index]
            else TICKS[TICK_COUNT - 1]
        }
        val builder = StringBuilder()
        for (line in text.split("\n")) {
            if (line.startsWith("tick=")) break
            builder.append(
                if (line.startsWith("tick_count=")) "tick_count=$count"
                else line)
            builder.append("\n")
        }
        for (row in rows) {
            val x = row.x
            val trimmed = if (x % 1.0 == 0.0) {
                x.toLong().toString()
            } else {
                x.toString()
            }
            builder.append("tick=${row.index}|$trimmed|300\n")
        }
        builder.append("END")
        return builder.toString()
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
