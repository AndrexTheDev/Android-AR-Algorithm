package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelMetricValidationTest {

    companion object {
        private const val SOURCE_INDOOR = "ARKFEED1-source-indoor"
        private const val SOURCE_OUTDOOR = "ARKFEED1-source-outdoor"
        private const val SOURCE_HELD_OUT = "ARKFEED1-source-held-out"
        private const val THRESHOLD_MM = 10L

        private fun digest(text: String): String =
            ArKernelMetricValidationAdapter.digestOf(text)

        private fun entries(): List<ArKernelMetricValidationEntry> = listOf(
            ArKernelMetricValidationEntry(
                sceneId = "scene.indoor.1",
                kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                condition = ArKernelMeasurementCondition.INDOOR,
                heldOut = false,
                groundTruthMm = 450L,
                estimateMm = 449L,
                sourceText = SOURCE_INDOOR,
            ),
            ArKernelMetricValidationEntry(
                sceneId = "scene.outdoor.1",
                kind = ArKernelCampaignSceneKind.OUTDOOR_SCALE,
                condition = ArKernelMeasurementCondition.OUTDOOR,
                heldOut = false,
                groundTruthMm = 900L,
                estimateMm = 898L,
                sourceText = SOURCE_OUTDOOR,
            ),
            ArKernelMetricValidationEntry(
                sceneId = "scene.heldout.1",
                kind = ArKernelCampaignSceneKind.HELD_OUT,
                condition = ArKernelMeasurementCondition.STILL,
                heldOut = true,
                groundTruthMm = 1_500L,
                estimateMm = 1_480L,
                sourceText = SOURCE_HELD_OUT,
            ),
        )

        private fun createBundle(): ArKernelMetricValidationBundle {
            val result = ArKernelMetricValidationAdapter.create(
                validationId = "valid.session.v1",
                deviceToken = "phone.redmi.v1",
                campaignId = "campaign.v1",
                thresholdMm = THRESHOLD_MM,
                entries = entries(),
            )
            check(result.accepted) { result.detail }
            return checkNotNull(result.bundle)
        }

        private fun expectedLines(): List<String> = listOf(
            "ARKVALID1",
            "version=1",
            "validation_id=valid.session.v1",
            "device_token=phone.redmi.v1",
            "campaign_id=campaign.v1",
            "scene_count=3",
            "threshold_mm=10",
            "ground_truth_unit=mm",
            "estimate_unit=mm",
            "ground_truth_source=operator_tape_measure",
            "ground_truth_manual=true",
            "automatic_calibration_disabled=true",
            "automatic_upload_disabled=true",
            "signature_verification_disabled=true",
            "universal_calibration_disabled=true",
            "per_device_calibration=true",
            "manual_upload_only=true",
            "images_disabled=true",
            "held_out_required=true",
            "metric_accuracy_claimed=false",
            "quality_grade=unverified",
            "mean_abs_error_mm=7",
            "bias_mm=-7",
            "max_abs_error_mm=20",
            "p95_abs_error_mm=18",
            "within_threshold_count=2",
            "within_threshold=false",
            "scene=0|scene.indoor.1|indoor_scale|indoor|false|450|449|" +
                digest(SOURCE_INDOOR),
            "scene=1|scene.outdoor.1|outdoor_scale|outdoor|false|900|898|" +
                digest(SOURCE_OUTDOOR),
            "scene=2|scene.heldout.1|held_out|still|true|1500|1480|" +
                digest(SOURCE_HELD_OUT),
            "END",
        )

        private fun canonicalText(): String =
            expectedLines().joinToString("\n") + "\n"

        private fun withValue(
            text: String,
            key: String,
            value: String,
        ): String {
            val lines = text.trimEnd('\n').split('\n').toMutableList()
            val index = lines.indexOfFirst { it.startsWith("$key=") }
            check(index >= 0) { "missing key $key" }
            lines[index] = "$key=$value"
            return lines.joinToString("\n") + "\n"
        }

        private fun rowLine(
            index: Int,
            sceneId: String,
            truthMm: Long,
            estimateMm: Long,
            source: String,
            heldOut: Boolean = false,
        ): String = "scene=$index|$sceneId|indoor_scale|indoor|$heldOut|" +
            "$truthMm|$estimateMm|${digest(source)}"
    }

    @Test
    fun encodeProducesCanonicalText() {
        assertEquals(canonicalText(), ArKernelMetricValidationAdapter
            .encode(createBundle()))
    }

    @Test
    fun parseAcceptsCanonicalText() {
        val result = ArKernelMetricValidationAdapter.parse(canonicalText())
        assertTrue(result.detail, result.accepted)
        val bundle = checkNotNull(result.bundle)
        assertEquals(3, bundle.rows.size)
        assertEquals("valid.session.v1", bundle.validationId)
        assertEquals(THRESHOLD_MM, bundle.thresholdMm)
        assertEquals("unverified", bundle.qualityGrade)
    }

    @Test
    fun parseRejectsMissingTrailingEnd() {
        val text = canonicalText().trimEnd('\n')
            .substringBeforeLast("\nEND") + "\n"
        val result = ArKernelMetricValidationAdapter.parse(text)
        assertEquals(
            ArKernelMetricValidationRejection.INVALID_SCHEMA,
            result.rejection)
    }

    @Test
    fun summarizeReportsIntegerStatistics() {
        val report = ArKernelMetricValidationAdapter
            .summarize(createBundle())
        assertEquals(3, report.sceneCount)
        assertEquals(7L, report.meanAbsErrorMm)
        assertEquals(-7L, report.biasMm)
        assertEquals(20L, report.maxAbsErrorMm)
        assertEquals(18L, report.p95AbsErrorMm)
        assertEquals(2, report.withinThresholdCount)
        assertFalse(report.withinThreshold)
        assertEquals(1, report.heldOutCount)
        assertEquals(3, report.distinctKinds)
        assertFalse(report.metricAccuracyClaimed)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun biasIsTruncatedTowardZero() {
        val negative = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v2",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = THRESHOLD_MM,
            entries = listOf(
                ArKernelMetricValidationEntry(
                    sceneId = "scene.a",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = true,
                    groundTruthMm = 1_000L,
                    estimateMm = 998L,
                    sourceText = "source-a",
                ),
                ArKernelMetricValidationEntry(
                    sceneId = "scene.b",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = false,
                    groundTruthMm = 1_000L,
                    estimateMm = 998L,
                    sourceText = "source-b",
                ),
                ArKernelMetricValidationEntry(
                    sceneId = "scene.c",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = false,
                    groundTruthMm = 1_000L,
                    estimateMm = 999L,
                    sourceText = "source-c",
                ),
            ),
        )
        check(negative.accepted) { negative.detail }
        val summary = ArKernelMetricValidationAdapter
            .summarize(checkNotNull(negative.bundle))
        assertEquals(-1L, summary.biasMm)
        assertEquals(1L, summary.meanAbsErrorMm)
        assertTrue(summary.withinThreshold)
    }

    @Test
    fun p95InterpolatesBetweenNeighbours() {
        val sceneCount = 16
        val entries = (0 until sceneCount).map { index ->
            ArKernelMetricValidationEntry(
                sceneId = "scene.$index",
                kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                condition = ArKernelMeasurementCondition.INDOOR,
                heldOut = index == 0,
                groundTruthMm = 1_000L,
                estimateMm = 1_000L + index + 1L,
                sourceText = "source-$index",
            )
        }
        val result = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v3",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = 100L,
            entries = entries,
        )
        check(result.accepted) { result.detail }
        val summary = ArKernelMetricValidationAdapter
            .summarize(checkNotNull(result.bundle))
        assertEquals(sceneCount, summary.sceneCount)
        assertEquals(16L, summary.maxAbsErrorMm)
        assertEquals(15L, summary.p95AbsErrorMm)
        assertEquals(8L, summary.meanAbsErrorMm)
        assertEquals(16, summary.withinThresholdCount)
    }

    @Test
    fun createRejectsDuplicateSceneId() {
        val result = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v4",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = THRESHOLD_MM,
            entries = listOf(
                ArKernelMetricValidationEntry(
                    sceneId = "scene.dup",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = true,
                    groundTruthMm = 500L,
                    estimateMm = 505L,
                    sourceText = "source-one",
                ),
                ArKernelMetricValidationEntry(
                    sceneId = "scene.dup",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = false,
                    groundTruthMm = 700L,
                    estimateMm = 706L,
                    sourceText = "source-two",
                ),
            ),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelMetricValidationRejection.DUPLICATE_SCENE,
            result.rejection)
    }

    @Test
    fun createRejectsDuplicateSourceDigest() {
        val result = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v5",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = THRESHOLD_MM,
            entries = listOf(
                ArKernelMetricValidationEntry(
                    sceneId = "scene.one",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = true,
                    groundTruthMm = 500L,
                    estimateMm = 505L,
                    sourceText = "same-source",
                ),
                ArKernelMetricValidationEntry(
                    sceneId = "scene.two",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = false,
                    groundTruthMm = 700L,
                    estimateMm = 706L,
                    sourceText = "same-source",
                ),
            ),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelMetricValidationRejection.DUPLICATE_DIGEST,
            result.rejection)
    }

    @Test
    fun createRejectsOutOfRangeValues() {
        val zeroTruth = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v6",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = THRESHOLD_MM,
            entries = listOf(
                ArKernelMetricValidationEntry(
                    sceneId = "scene.zero",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = true,
                    groundTruthMm = 0L,
                    estimateMm = 505L,
                    sourceText = "source-zero",
                ),
            ),
        )
        assertEquals(
            ArKernelMetricValidationRejection.GROUND_TRUTH_OUT_OF_RANGE,
            zeroTruth.rejection)

        val zeroEstimate = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v7",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = THRESHOLD_MM,
            entries = listOf(
                ArKernelMetricValidationEntry(
                    sceneId = "scene.zero.estimate",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = true,
                    groundTruthMm = 500L,
                    estimateMm = 0L,
                    sourceText = "source-zero-estimate",
                ),
            ),
        )
        assertEquals(
            ArKernelMetricValidationRejection.ESTIMATE_OUT_OF_RANGE,
            zeroEstimate.rejection)

        val blankSource = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v8",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = THRESHOLD_MM,
            entries = listOf(
                ArKernelMetricValidationEntry(
                    sceneId = "scene.blank",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = true,
                    groundTruthMm = 500L,
                    estimateMm = 505L,
                    sourceText = "   ",
                ),
            ),
        )
        assertEquals(
            ArKernelMetricValidationRejection.EMPTY_SOURCE,
            blankSource.rejection)
    }

    @Test
    fun createRejectsZeroThreshold() {
        val result = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v9",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = 0L,
            entries = entries(),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelMetricValidationRejection.THRESHOLD_OUT_OF_RANGE,
            result.rejection)
    }

    @Test
    fun createRejectsLedgerWithoutHeldOutScene() {
        val result = ArKernelMetricValidationAdapter.create(
            validationId = "valid.session.v10",
            deviceToken = "phone.redmi.v1",
            campaignId = "campaign.v1",
            thresholdMm = THRESHOLD_MM,
            entries = listOf(
                ArKernelMetricValidationEntry(
                    sceneId = "scene.only",
                    kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                    condition = ArKernelMeasurementCondition.INDOOR,
                    heldOut = false,
                    groundTruthMm = 500L,
                    estimateMm = 505L,
                    sourceText = "source-only",
                ),
            ),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelMetricValidationRejection.HELD_OUT_REQUIRED,
            result.rejection)
    }

    @Test
    fun parseRejectsTamperedStatistic() {
        val result = ArKernelMetricValidationAdapter.parse(
            withValue(canonicalText(), "mean_abs_error_mm", "6"))
        assertFalse(result.accepted)
        assertEquals(
            ArKernelMetricValidationRejection.STATISTICS_MISMATCH,
            result.rejection)
    }

    @Test
    fun parseRejectsClaimedMetricAccuracy() {
        val result = ArKernelMetricValidationAdapter.parse(
            withValue(canonicalText(), "metric_accuracy_claimed", "true"))
        assertEquals(
            ArKernelMetricValidationRejection.UNSAFE_INVARIANT,
            result.rejection)
    }

    @Test
    fun parseRejectsRelaxedInvariant() {
        val result = ArKernelMetricValidationAdapter.parse(
            withValue(canonicalText(), "images_disabled", "false"))
        assertEquals(
            ArKernelMetricValidationRejection.UNSAFE_INVARIANT,
            result.rejection)
    }

    @Test
    fun parseRejectsWrongUnit() {
        val result = ArKernelMetricValidationAdapter.parse(
            withValue(canonicalText(), "estimate_unit", "cm"))
        assertEquals(
            ArKernelMetricValidationRejection.INVALID_UNIT,
            result.rejection)
    }

    @Test
    fun parseRejectsEmptyAndMalformedInput() {
        assertEquals(
            ArKernelMetricValidationRejection.EMPTY_LEDGER,
            ArKernelMetricValidationAdapter.parse("   ").rejection)
        assertEquals(
            ArKernelMetricValidationRejection.INVALID_HEADER,
            ArKernelMetricValidationAdapter
                .parse("ARKVALID0\nEND\n").rejection)
    }

    @Test
    fun parseRejectsTamperedSceneRow() {
        val lines = canonicalText().trimEnd('\n').split('\n').toMutableList()
        val rowIndex = lines.indexOfFirst { it.startsWith("scene=2|") }
        check(rowIndex >= 0)
        lines[rowIndex] = rowLine(
            index = 2,
            sceneId = "scene.heldout.1",
            truthMm = 1_500L,
            estimateMm = 1_499L,
            source = SOURCE_HELD_OUT,
            heldOut = true,
        )
        val result = ArKernelMetricValidationAdapter
            .parse(lines.joinToString("\n") + "\n")
        assertEquals(
            ArKernelMetricValidationRejection.STATISTICS_MISMATCH,
            result.rejection)
    }

    @Test
    fun parseRejectsDuplicateSourceDigestInRows() {
        val lines = canonicalText().trimEnd('\n').split('\n').toMutableList()
        val rowIndex = lines.indexOfFirst { it.startsWith("scene=2|") }
        check(rowIndex >= 0)
        lines[rowIndex] = rowLine(
            index = 2,
            sceneId = "scene.heldout.1",
            truthMm = 1_480L,
            estimateMm = 1_480L,
            source = SOURCE_INDOOR,
            heldOut = true,
        )
        val result = ArKernelMetricValidationAdapter
            .parse(lines.joinToString("\n") + "\n")
        assertEquals(
            ArKernelMetricValidationRejection.DUPLICATE_DIGEST,
            result.rejection)
    }

    @Test
    fun verifyAcceptsCompleteLedger() {
        assertTrue(ArKernelMetricValidationAdapter.verify(createBundle()))
    }

    @Test
    fun bundleRejectsNonContiguousRows() {
        val rows = createBundle().rows.toMutableList()
        rows[1] = rows[1].copy(index = 5)
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelMetricValidationBundle(
                validationId = "valid.session.v11",
                deviceToken = "phone.redmi.v1",
                campaignId = "campaign.v1",
                thresholdMm = THRESHOLD_MM,
                rows = rows,
            )
        }
    }

    @Test
    fun rowRejectsInvalidDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelMetricValidationRow(
                index = 0,
                sceneId = "scene.x",
                kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                condition = ArKernelMeasurementCondition.INDOOR,
                heldOut = true,
                groundTruthMm = 500L,
                estimateMm = 500L,
                sourceDigest = "not-a-digest",
            )
        }
    }

    @Test
    fun digestOfIsStableLowercaseHex() {
        val first = ArKernelMetricValidationAdapter.digestOf("abc")
        assertEquals(first, ArKernelMetricValidationAdapter.digestOf("abc"))
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
