package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelConditionEvidenceTest {

    companion object {
        private const val LEDGER_ID = "cond.test.v1"
        private const val DEVICE_TOKEN = "phone.one"
        private const val CAMPAIGN_ID = "campaign.test.v1"

        /** 307.8 lux — a lit indoor room. */
        private const val DAY_LUX = 307_800L

        /** 6.9 lux — a street-lit night scene. */
        private const val NIGHT_LUX = 6_900L

        /** The operator's own thresholds: day >= 50 lux, night <= 10 lux. */
        private const val DAY_MIN_LUX = 50_000L
        private const val NIGHT_MAX_LUX = 10_000L

        private fun sample(
            condition: ArKernelMeasurementCondition =
                ArKernelMeasurementCondition.DAY,
            rigId: String = "tripod.1",
            rigKind: ArKernelRigKind = ArKernelRigKind.TRIPOD,
            poseIndex: Int = 0,
            repeatIndex: Int = 0,
            thermalStatus: Int = 0,
            headroomPermille: Int = 900,
            luxMilli: Long = DAY_LUX,
        ): ArKernelConditionSample = ArKernelConditionSample(
            index = 0,
            condition = condition,
            rigId = rigId,
            rigKind = rigKind,
            poseIndex = poseIndex,
            repeatIndex = repeatIndex,
            thermalStatus = thermalStatus,
            headroomPermille = headroomPermille,
            luxMilli = luxMilli,
            timestampMillis = 0L,
        )

        /** Assigns contiguous indices and strictly increasing timestamps. */
        private fun build(
            vararg rows: ArKernelConditionSample,
        ): List<ArKernelConditionSample> =
            rows.mapIndexed { index, row ->
                row.copy(
                    index = index,
                    timestampMillis = 1_000L * (index + 1),
                )
            }

        /** Two day samples and two night samples, one pose repeated twice. */
        private fun full(): List<ArKernelConditionSample> = build(
            sample(),
            sample(repeatIndex = 1, headroomPermille = 880,
                luxMilli = 305_000L),
            sample(
                condition = ArKernelMeasurementCondition.NIGHT,
                poseIndex = 1,
                thermalStatus = 1,
                headroomPermille = 850,
                luxMilli = NIGHT_LUX,
            ),
            sample(
                condition = ArKernelMeasurementCondition.NIGHT,
                poseIndex = 1,
                repeatIndex = 1,
                thermalStatus = 1,
                headroomPermille = 840,
                luxMilli = 7_100L,
            ),
        )

        private fun ledger(
            samples: List<ArKernelConditionSample> = full(),
            required: Int = 2,
            minSamples: Int = 2,
            maxThermal: Int = 2,
            dayMin: Long = DAY_MIN_LUX,
            nightMax: Long = NIGHT_MAX_LUX,
        ): ArKernelConditionLedger {
            val result = ArKernelConditionEvidenceAdapter.create(
                ledgerId = LEDGER_ID,
                deviceToken = DEVICE_TOKEN,
                campaignId = CAMPAIGN_ID,
                requiredConditions = required,
                minSamplesPerCondition = minSamples,
                maxThermalStatus = maxThermal,
                dayMinLuxMilli = dayMin,
                nightMaxLuxMilli = nightMax,
                samples = samples,
            )
            check(result.accepted) { result.detail }
            return checkNotNull(result.ledger)
        }
    }

    @Test
    fun roundTripPreservesEveryField() {
        val source = ledger()
        val parsed = ArKernelConditionEvidenceAdapter
            .parse(ArKernelConditionEvidenceAdapter.encode(source))
        assertTrue(parsed.detail, parsed.accepted)
        val rebuilt = checkNotNull(parsed.ledger)
        assertEquals(source.samples, rebuilt.samples)
        assertEquals(source.evidenceSufficient, rebuilt.evidenceSufficient)
        assertEquals(source.labelConflicts, rebuilt.labelConflicts)
        assertEquals(source.maxLuxMilli, rebuilt.maxLuxMilli)
        assertEquals(
            source.observedMaxThermalStatus,
            rebuilt.observedMaxThermalStatus,
        )
    }

    @Test
    fun aWellBehavedLedgerReportsItsNumbers() {
        val source = ledger()
        assertEquals(4, source.sampleCount)
        assertEquals(2, source.conditionCount)
        assertEquals(2, source.coveredConditions)
        assertEquals(2, source.dayCount)
        assertEquals(2, source.nightCount)
        assertEquals(1, source.rigCount)
        assertEquals(0, source.handheldCount)
        assertEquals(2, source.repeatCount)
        assertEquals(0, source.labelConflicts)
        assertEquals(NIGHT_LUX, source.minLuxMilli)
        assertEquals(DAY_LUX, source.maxLuxMilli)
        assertEquals(1, source.observedMaxThermalStatus)
        assertTrue(source.dayNightCovered)
        assertTrue(source.labelsCoherent)
        assertTrue(source.thermalStable)
        assertTrue(source.repeatabilityDocumented)
        assertTrue(source.evidenceSufficient)
    }

    @Test
    fun aDaySampleBelowTheDayThresholdIsAConflict() {
        val source = ledger(
            build(
                sample(luxMilli = 3_000L),
                sample(repeatIndex = 1, luxMilli = DAY_LUX),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    luxMilli = NIGHT_LUX,
                ),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    repeatIndex = 1,
                    luxMilli = NIGHT_LUX,
                ),
            )
        )
        assertEquals(1, source.labelConflicts)
        assertFalse(source.labelsCoherent)
        assertFalse(source.evidenceSufficient)
    }

    @Test
    fun aNightSampleAboveTheNightThresholdIsAConflict() {
        val source = ledger(
            build(
                sample(),
                sample(repeatIndex = 1),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    luxMilli = 200_000L,
                ),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    repeatIndex = 1,
                    luxMilli = NIGHT_LUX,
                ),
            )
        )
        assertEquals(1, source.labelConflicts)
        assertFalse(source.labelsCoherent)
        assertTrue(source.dayNightCovered)
    }

    @Test
    fun onlyDayAndNightLabelsAreCheckedAgainstTheThresholds() {
        val source = ledger(
            build(
                sample(
                    condition = ArKernelMeasurementCondition.INDOOR,
                    luxMilli = 0L,
                ),
                sample(
                    condition = ArKernelMeasurementCondition.INDOOR,
                    repeatIndex = 1,
                    luxMilli = 0L,
                ),
            )
        )
        assertEquals(0, source.labelConflicts)
        assertTrue(source.labelsCoherent)
        assertFalse(source.dayNightCovered)
        assertFalse(source.evidenceSufficient)
    }

    @Test
    fun thermalDriftAboveTheDeclaredBandBlocksStability() {
        val source = ledger(maxThermal = 2)
        assertTrue(source.thermalStable)
        val drifted = ledger(
            build(
                sample(),
                sample(repeatIndex = 1),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    luxMilli = NIGHT_LUX,
                ),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    repeatIndex = 1,
                    thermalStatus = 4,
                    luxMilli = NIGHT_LUX,
                ),
            )
        )
        assertEquals(4, drifted.observedMaxThermalStatus)
        assertFalse(drifted.thermalStable)
        assertFalse(drifted.evidenceSufficient)
    }

    @Test
    fun aRepeatedPoseIsWhatDocumentsRepeatability() {
        val repeated = ledger()
        assertTrue(repeated.repeatabilityDocumented)
        val single = ledger(
            build(
                sample(poseIndex = 0),
                sample(poseIndex = 1),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 2,
                    luxMilli = NIGHT_LUX,
                ),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 3,
                    luxMilli = NIGHT_LUX,
                ),
            )
        )
        assertTrue(single.dayNightCovered)
        assertTrue(single.labelsCoherent)
        assertFalse(single.repeatabilityDocumented)
        assertFalse(single.evidenceSufficient)
    }

    @Test
    fun aRepeatOnADifferentRigDoesNotDocumentRepeatability() {
        val twoRigs = ledger(
            build(
                sample(),
                sample(rigId = "tripod.2", repeatIndex = 1),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    luxMilli = NIGHT_LUX,
                ),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    rigId = "tripod.2",
                    poseIndex = 1,
                    repeatIndex = 1,
                    luxMilli = NIGHT_LUX,
                ),
            )
        )
        assertEquals(2, twoRigs.rigCount)
        assertFalse(twoRigs.repeatabilityDocumented)
    }

    @Test
    fun coverageCountsOnlyConditionsWithEnoughSamples() {
        val thin = ledger(
            build(
                sample(),
                sample(repeatIndex = 1),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    luxMilli = NIGHT_LUX,
                ),
                sample(
                    condition = ArKernelMeasurementCondition.OUTDOOR,
                    poseIndex = 2,
                    luxMilli = DAY_LUX,
                ),
            )
        )
        assertEquals(3, thin.conditionCount)
        assertEquals(1, thin.coveredConditions)
        assertFalse(thin.dayNightCovered)
        assertFalse(thin.evidenceSufficient)
    }

    @Test
    fun handheldSamplesAreCountedButNotForbidden() {
        val source = ledger(
            build(
                sample(rigKind = ArKernelRigKind.HANDHELD),
                sample(repeatIndex = 1,
                    rigKind = ArKernelRigKind.HANDHELD),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    rigKind = ArKernelRigKind.HANDHELD,
                    luxMilli = NIGHT_LUX,
                ),
                sample(
                    condition = ArKernelMeasurementCondition.NIGHT,
                    poseIndex = 1,
                    repeatIndex = 1,
                    rigKind = ArKernelRigKind.HANDHELD,
                    luxMilli = NIGHT_LUX,
                ),
            )
        )
        assertEquals(4, source.handheldCount)
        assertTrue(source.evidenceSufficient)
    }

    @Test
    fun timestampsMustIncrease() {
        val result = ArKernelConditionEvidenceAdapter.create(
            ledgerId = LEDGER_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredConditions = 2,
            minSamplesPerCondition = 1,
            maxThermalStatus = 2,
            dayMinLuxMilli = DAY_MIN_LUX,
            nightMaxLuxMilli = NIGHT_MAX_LUX,
            samples = listOf(
                sample().copy(index = 0, timestampMillis = 5_000L),
                sample(repeatIndex = 1)
                    .copy(index = 1, timestampMillis = 4_000L),
            ),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelConditionEvidenceRejection.NON_MONOTONIC,
            result.rejection,
        )
    }

    @Test
    fun theSamePoseAndRepeatTwiceIsADuplicate() {
        val result = ArKernelConditionEvidenceAdapter.create(
            ledgerId = LEDGER_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredConditions = 2,
            minSamplesPerCondition = 1,
            maxThermalStatus = 2,
            dayMinLuxMilli = DAY_MIN_LUX,
            nightMaxLuxMilli = NIGHT_MAX_LUX,
            samples = build(sample(), sample()),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelConditionEvidenceRejection.DUPLICATE_SAMPLE,
            result.rejection,
        )
    }

    @Test
    fun unknownConditionAndRigAreRejected() {
        val source = ledger()
        val text = ArKernelConditionEvidenceAdapter.encode(source)
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_CONDITION,
            ArKernelConditionEvidenceAdapter.parse(
                text.replace("sample=0|day|", "sample=0|dusk|")
            ).rejection,
        )
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_RIG,
            ArKernelConditionEvidenceAdapter.parse(
                text.replace("|tripod|", "|drone|")
            ).rejection,
        )
    }

    @Test
    fun outOfBoundsValuesAreRejected() {
        val base = { row: ArKernelConditionSample ->
            ArKernelConditionEvidenceAdapter.create(
                ledgerId = LEDGER_ID,
                deviceToken = DEVICE_TOKEN,
                campaignId = CAMPAIGN_ID,
                requiredConditions = 1,
                minSamplesPerCondition = 1,
                maxThermalStatus = 2,
                dayMinLuxMilli = DAY_MIN_LUX,
                nightMaxLuxMilli = NIGHT_MAX_LUX,
                samples = build(row),
            )
        }
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_VALUE,
            base(sample(thermalStatus = 7)).rejection,
        )
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_VALUE,
            base(sample(headroomPermille = 1_001)).rejection,
        )
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_VALUE,
            base(sample(poseIndex = 1_024)).rejection,
        )
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_VALUE,
            base(sample(repeatIndex = 64)).rejection,
        )
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_VALUE,
            base(sample(luxMilli = 200_000_001L)).rejection,
        )
    }

    @Test
    fun emptyAndOversizedLedgersAreRejected() {
        val empty = ArKernelConditionEvidenceAdapter.create(
            ledgerId = LEDGER_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredConditions = 1,
            minSamplesPerCondition = 1,
            maxThermalStatus = 2,
            dayMinLuxMilli = DAY_MIN_LUX,
            nightMaxLuxMilli = NIGHT_MAX_LUX,
            samples = emptyList(),
        )
        assertFalse(empty.accepted)
        val tooMany = ArKernelConditionEvidenceAdapter.create(
            ledgerId = LEDGER_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredConditions = 1,
            minSamplesPerCondition = 1,
            maxThermalStatus = 2,
            dayMinLuxMilli = DAY_MIN_LUX,
            nightMaxLuxMilli = NIGHT_MAX_LUX,
            samples = build(
                *(0..64).map {
                    sample(poseIndex = it, luxMilli = DAY_LUX)
                }.toTypedArray()
            ),
        )
        assertFalse(tooMany.accepted)
    }

    @Test
    fun tamperingWithADeclaredStatisticIsRejected() {
        val source = ledger()
        val text = ArKernelConditionEvidenceAdapter.encode(source)
        assertTrue(text.contains("day_count=2"))
        val parsed = ArKernelConditionEvidenceAdapter.parse(
            text.replace("day_count=2", "day_count=3")
        )
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelConditionEvidenceRejection.STATISTICS_MISMATCH,
            parsed.rejection,
        )
    }

    @Test
    fun tamperingWithAVerdictIsRejected() {
        val weak = ledger(
            build(
                sample(),
                sample(repeatIndex = 1),
            )
        )
        assertFalse(weak.evidenceSufficient)
        val text = ArKernelConditionEvidenceAdapter.encode(weak)
        val parsed = ArKernelConditionEvidenceAdapter.parse(
            text.replace(
                "evidence_sufficient=false",
                "evidence_sufficient=true",
            )
        )
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelConditionEvidenceRejection.EVIDENCE_MISMATCH,
            parsed.rejection,
        )
    }

    @Test
    fun safetyInvariantsCannotBeRelaxed() {
        val source = ledger()
        val text = ArKernelConditionEvidenceAdapter.encode(source)
        for (key in listOf(
            "automatic_capture_disabled",
            "automatic_upload_disabled",
            "manual_upload_only",
            "images_disabled",
        )) {
            assertTrue(text.contains("$key=true"))
            val parsed = ArKernelConditionEvidenceAdapter.parse(
                text.replace("$key=true", "$key=false")
            )
            assertFalse(parsed.accepted)
            assertEquals(
                ArKernelConditionEvidenceRejection.UNSAFE_INVARIANT,
                parsed.rejection,
            )
        }
        assertTrue(text.contains("metric_accuracy_claimed=false"))
        assertFalse(
            ArKernelConditionEvidenceAdapter.parse(
                text.replace(
                    "metric_accuracy_claimed=false",
                    "metric_accuracy_claimed=true",
                )
            ).accepted
        )
    }

    @Test
    fun theGradeCanNeverBeUpgraded() {
        val source = ledger()
        val text = ArKernelConditionEvidenceAdapter.encode(source)
        assertTrue(text.contains("quality_grade=unverified"))
        val parsed = ArKernelConditionEvidenceAdapter.parse(
            text.replace(
                "quality_grade=unverified",
                "quality_grade=centimeter-validated",
            )
        )
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_QUALITY,
            parsed.rejection,
        )
    }

    @Test
    fun malformedInputIsRejectedWithoutThrowing() {
        assertEquals(
            ArKernelConditionEvidenceRejection.EMPTY_LEDGER,
            ArKernelConditionEvidenceAdapter.parse("   ").rejection,
        )
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_HEADER,
            ArKernelConditionEvidenceAdapter.parse("WRONG\nEND").rejection,
        )
        val text = ArKernelConditionEvidenceAdapter.encode(ledger())
        assertEquals(
            ArKernelConditionEvidenceRejection.INVALID_SCHEMA,
            ArKernelConditionEvidenceAdapter.parse(
                text.replace("END", "DONE")
            ).rejection,
        )
        assertEquals(
            ArKernelConditionEvidenceRejection.LINE_TOO_LONG,
            ArKernelConditionEvidenceAdapter.parse(
                text.replace(
                    "sample=0|day|",
                    "sample=0|" + "x".repeat(300) + "|",
                )
            ).rejection,
        )
        assertEquals(
            ArKernelConditionEvidenceRejection.INPUT_TOO_LARGE,
            ArKernelConditionEvidenceAdapter.parse(
                "ARKCOND1\n" + "x\n".repeat(40_000)
            ).rejection,
        )
    }

    @Test
    fun aByteOrderMarkAndCarriageReturnsAreTolerated() {
        val source = ledger()
        val parsed = ArKernelConditionEvidenceAdapter.parse(
            "\uFEFF" +
                ArKernelConditionEvidenceAdapter.encode(source)
                    .replace("\n", "\r\n")
        )
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(
            source.samples,
            checkNotNull(parsed.ledger).samples,
        )
    }

    @Test
    fun summarizeReportsStructureAndNothingAboutAccuracy() {
        val source = ledger()
        val summary = ArKernelConditionEvidenceAdapter.summarize(source)
        assertEquals(4, summary.sampleCount)
        assertEquals(2, summary.conditionCount)
        assertEquals(2, summary.coveredConditions)
        assertEquals(2, summary.dayCount)
        assertEquals(2, summary.nightCount)
        assertEquals(1, summary.rigCount)
        assertEquals(0, summary.handheldCount)
        assertEquals(2, summary.repeatCount)
        assertEquals(0, summary.labelConflicts)
        assertEquals(NIGHT_LUX, summary.minLuxMilli)
        assertEquals(DAY_LUX, summary.maxLuxMilli)
        assertEquals(1, summary.observedMaxThermalStatus)
        assertTrue(summary.dayNightCovered)
        assertTrue(summary.labelsCoherent)
        assertTrue(summary.thermalStable)
        assertTrue(summary.repeatabilityDocumented)
        assertTrue(summary.evidenceSufficient)
        assertEquals(listOf("tripod.1"), source.rigIds())
    }
}
