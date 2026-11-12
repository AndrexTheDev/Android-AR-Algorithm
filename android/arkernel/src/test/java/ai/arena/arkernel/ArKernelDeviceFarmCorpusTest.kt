package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelDeviceFarmCorpusTest {

    companion object {
        private const val CORPUS_ID = "corpus.test.v1"

        private fun entry(
            captureId: String,
            deviceToken: String,
            deviceClass: ArKernelAdaptiveDeviceClass,
            condition: ArKernelMeasurementCondition,
            sceneKind: ArKernelCampaignSceneKind,
            sampleCount: Long = 100L,
        ): ArKernelCorpusEntry = ArKernelCorpusEntry(
            index = 0,
            captureId = captureId,
            deviceToken = deviceToken,
            deviceClass = deviceClass,
            condition = condition,
            sceneKind = sceneKind,
            sampleCount = sampleCount,
        )

        private fun build(
            vararg rows: ArKernelCorpusEntry,
        ): List<ArKernelCorpusEntry> =
            rows.mapIndexed { index, row -> row.copy(index = index) }

        private val DAY = ArKernelMeasurementCondition.DAY
        private val NIGHT = ArKernelMeasurementCondition.NIGHT
        private val SCALE = ArKernelCampaignSceneKind.INDOOR_SCALE
        private val ANGLE = ArKernelCampaignSceneKind.RIGHT_ANGLE
        private val FULL = ArKernelAdaptiveDeviceClass.FULL
        private val LIMITED = ArKernelAdaptiveDeviceClass.LIMITED
        private val LEGACY = ArKernelAdaptiveDeviceClass.LEGACY

        /** Three devices, two conditions, two scenes, every cell matched. */
        private fun balanced(): List<ArKernelCorpusEntry> = build(
            entry("cap.1", "phone.a", FULL, DAY, SCALE),
            entry("cap.2", "phone.b", LIMITED, DAY, SCALE),
            entry("cap.3", "phone.a", FULL, NIGHT, SCALE),
            entry("cap.4", "phone.b", LIMITED, NIGHT, SCALE),
            entry("cap.5", "phone.a", FULL, DAY, ANGLE),
            entry("cap.6", "phone.c", LEGACY, DAY, ANGLE),
        )

        private fun corpus(
            entries: List<ArKernelCorpusEntry> = balanced(),
            requiredDevices: Int = 3,
            requiredConditions: Int = 2,
            requiredScenes: Int = 2,
            requiredMatchedCells: Int = 3,
            maxShare: Int = 500,
        ): ArKernelDeviceFarmCorpus {
            val result = ArKernelDeviceFarmCorpusAdapter.create(
                corpusId = CORPUS_ID,
                corpusRevision = 1,
                requiredDevices = requiredDevices,
                requiredConditions = requiredConditions,
                requiredScenes = requiredScenes,
                requiredMatchedCells = requiredMatchedCells,
                maxDeviceSharePermille = maxShare,
                entries = entries,
            )
            check(result.accepted) { result.detail }
            return checkNotNull(result.corpus)
        }
    }

    @Test
    fun roundTripPreservesEveryField() {
        val source = corpus()
        val parsed = ArKernelDeviceFarmCorpusAdapter
            .parse(ArKernelDeviceFarmCorpusAdapter.encode(source))
        assertTrue(parsed.detail, parsed.accepted)
        val rebuilt = checkNotNull(parsed.corpus)
        assertEquals(source.entries, rebuilt.entries)
        assertEquals(source.matchedCells, rebuilt.matchedCells)
        assertEquals(
            source.observedMaxDeviceSharePermille,
            rebuilt.observedMaxDeviceSharePermille,
        )
        assertEquals(source.corpusSufficient, rebuilt.corpusSufficient)
    }

    @Test
    fun aBalancedCorpusReportsItsNumbers() {
        val source = corpus()
        assertEquals(6, source.entryCount)
        assertEquals(3, source.deviceCount)
        assertEquals(3, source.deviceClassCount)
        assertEquals(2, source.conditionCount)
        assertEquals(2, source.sceneCount)
        assertEquals(600L, source.sampleTotal)
        assertEquals(500, source.observedMaxDeviceSharePermille)
        assertEquals(3, source.totalCells)
        assertEquals(3, source.matchedCells)
        assertTrue(source.deviceDiversityMet)
        assertTrue(source.conditionsCovered)
        assertTrue(source.scenesCovered)
        assertTrue(source.balanceOk)
        assertTrue(source.crossDeviceReady)
        assertTrue(source.corpusSufficient)
    }

    @Test
    fun dominanceAboveTheCeilingBlocksBalance() {
        val source = corpus()
        assertTrue(source.balanceOk)
        val skewed = corpus(
            build(
                entry("cap.1", "phone.a", FULL, DAY, SCALE, 400L),
                entry("cap.2", "phone.b", LIMITED, DAY, SCALE),
                entry("cap.3", "phone.a", FULL, NIGHT, SCALE),
                entry("cap.4", "phone.b", LIMITED, NIGHT, SCALE),
                entry("cap.5", "phone.a", FULL, DAY, ANGLE),
                entry("cap.6", "phone.c", LEGACY, DAY, ANGLE),
            )
        )
        assertEquals(900L, skewed.sampleTotal)
        assertEquals(666, skewed.observedMaxDeviceSharePermille)
        assertFalse(skewed.balanceOk)
        assertFalse(skewed.corpusSufficient)
    }

    @Test
    fun aCellOnlyOneDeviceCapturedDoesNotMatch() {
        val source = corpus(
            build(
                entry("cap.1", "phone.a", FULL, DAY, SCALE),
                entry("cap.2", "phone.b", LIMITED, DAY, SCALE),
                entry("cap.3", "phone.a", FULL, NIGHT, SCALE),
                entry("cap.4", "phone.a", FULL, NIGHT, SCALE),
                entry("cap.5", "phone.a", FULL, DAY, ANGLE),
                entry("cap.6", "phone.c", LEGACY, DAY, ANGLE),
            )
        )
        assertEquals(3, source.totalCells)
        assertEquals(2, source.matchedCells)
        assertFalse(source.crossDeviceReady)
        assertFalse(source.corpusSufficient)
        assertEquals(
            listOf(NIGHT to SCALE),
            source.unmatchedCells(),
        )
    }

    @Test
    fun moreSamplesDoNotTurnOneDeviceIntoAMatch() {
        val source = corpus(
            build(
                entry("cap.1", "phone.a", FULL, DAY, SCALE),
                entry("cap.2", "phone.a", FULL, DAY, SCALE, 5_000L),
            ),
            requiredDevices = 1,
            requiredConditions = 1,
            requiredScenes = 1,
            requiredMatchedCells = 1,
        )
        assertEquals(5_100L, source.sampleTotal)
        assertEquals(1, source.totalCells)
        assertEquals(0, source.matchedCells)
        assertTrue(source.deviceDiversityMet)
        assertFalse(source.crossDeviceReady)
        assertFalse(source.corpusSufficient)
    }

    @Test
    fun deviceDiversityNeedsDistinctDevices() {
        val single = corpus(
            build(
                entry("cap.1", "phone.a", FULL, DAY, SCALE),
                entry("cap.2", "phone.a", FULL, NIGHT, SCALE),
                entry("cap.3", "phone.a", FULL, DAY, ANGLE),
            )
        )
        assertEquals(1, single.deviceCount)
        assertEquals(0, single.matchedCells)
        assertFalse(single.deviceDiversityMet)
        assertFalse(single.corpusSufficient)
    }

    @Test
    fun flatCoverageBlocksTheVerdicts() {
        val flat = corpus(
            build(
                entry("cap.1", "phone.a", FULL, DAY, SCALE),
                entry("cap.2", "phone.b", LIMITED, DAY, SCALE),
                entry("cap.3", "phone.c", LEGACY, DAY, SCALE),
            )
        )
        assertEquals(3, flat.deviceCount)
        assertEquals(1, flat.conditionCount)
        assertEquals(1, flat.sceneCount)
        assertTrue(flat.deviceDiversityMet)
        assertFalse(flat.conditionsCovered)
        assertFalse(flat.scenesCovered)
        assertFalse(flat.corpusSufficient)
    }

    @Test
    fun dominantDevicesAndUnmatchedCellsAreReported() {
        val source = corpus()
        assertEquals(listOf("phone.a"), source.dominantDeviceTokens())
        assertTrue(source.unmatchedCells().isEmpty())
        val skewed = corpus(
            build(
                entry("cap.1", "phone.a", FULL, DAY, SCALE, 400L),
                entry("cap.2", "phone.b", LIMITED, DAY, SCALE),
                entry("cap.3", "phone.a", FULL, NIGHT, SCALE),
                entry("cap.4", "phone.b", LIMITED, NIGHT, SCALE),
                entry("cap.5", "phone.a", FULL, DAY, ANGLE),
                entry("cap.6", "phone.c", LEGACY, DAY, ANGLE),
            )
        )
        assertEquals(listOf("phone.a"), skewed.dominantDeviceTokens())
    }

    @Test
    fun duplicateCaptureIdsAreRejected() {
        val result = ArKernelDeviceFarmCorpusAdapter.create(
            corpusId = CORPUS_ID,
            corpusRevision = 1,
            requiredDevices = 1,
            requiredConditions = 1,
            requiredScenes = 1,
            requiredMatchedCells = 0,
            maxDeviceSharePermille = 1_000,
            entries = build(
                entry("cap.1", "phone.a", FULL, DAY, SCALE),
                entry("cap.1", "phone.b", LIMITED, DAY, SCALE),
            ),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.DUPLICATE_ENTRY,
            result.rejection,
        )
    }

    @Test
    fun nonContiguousEntriesAreRejected() {
        val result = ArKernelDeviceFarmCorpusAdapter.create(
            corpusId = CORPUS_ID,
            corpusRevision = 1,
            requiredDevices = 1,
            requiredConditions = 1,
            requiredScenes = 1,
            requiredMatchedCells = 0,
            maxDeviceSharePermille = 1_000,
            entries = listOf(
                entry("cap.1", "phone.a", FULL, DAY, SCALE),
                entry("cap.2", "phone.b", LIMITED, DAY, SCALE)
                    .copy(index = 2),
            ),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_ORDER,
            result.rejection,
        )
    }

    @Test
    fun unknownClassConditionAndSceneAreRejected() {
        val text = ArKernelDeviceFarmCorpusAdapter.encode(corpus())
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_CLASS,
            ArKernelDeviceFarmCorpusAdapter.parse(
                text.replace("|full|", "|quantum|")
            ).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_CONDITION,
            ArKernelDeviceFarmCorpusAdapter.parse(
                text.replace("|day|", "|dusk|")
            ).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_SCENE,
            ArKernelDeviceFarmCorpusAdapter.parse(
                text.replace("|indoor_scale|", "|void|")
            ).rejection,
        )
    }

    @Test
    fun outOfBoundsValuesAreRejected() {
        val base = { rows: List<ArKernelCorpusEntry>,
            devices: Int, conditions: Int, scenes: Int,
            cells: Int, share: Int ->
            ArKernelDeviceFarmCorpusAdapter.create(
                corpusId = CORPUS_ID,
                corpusRevision = 1,
                requiredDevices = devices,
                requiredConditions = conditions,
                requiredScenes = scenes,
                requiredMatchedCells = cells,
                maxDeviceSharePermille = share,
                entries = rows,
            )
        }
        val one = build(entry("cap.1", "phone.a", FULL, DAY, SCALE))
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
            base(
                build(entry("cap.1", "phone.a", FULL, DAY, SCALE, 0L)),
                1, 1, 1, 0, 1_000,
            ).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
            base(
                build(
                    entry(
                        "cap.1", "phone.a", FULL, DAY, SCALE,
                        1_000_001L,
                    )
                ),
                1, 1, 1, 0, 1_000,
            ).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
            base(one, 1, 1, 1, 0, 0).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
            base(one, 1, 1, 1, 0, 1_001).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
            base(one, 17, 1, 1, 0, 1_000).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
            base(one, 1, 7, 1, 0, 1_000).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
            base(one, 1, 1, 10, 0, 1_000).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
            base(one, 1, 1, 1, 65, 1_000).rejection,
        )
    }

    @Test
    fun emptyAndOversizedCorporaAreRejected() {
        val base = { rows: List<ArKernelCorpusEntry> ->
            ArKernelDeviceFarmCorpusAdapter.create(
                corpusId = CORPUS_ID,
                corpusRevision = 1,
                requiredDevices = 1,
                requiredConditions = 1,
                requiredScenes = 1,
                requiredMatchedCells = 0,
                maxDeviceSharePermille = 1_000,
                entries = rows,
            )
        }
        assertFalse(base(emptyList()).accepted)
        assertFalse(
            base(
                build(
                    *(0..64).map {
                        entry("cap.$it", "phone.a", FULL, DAY, SCALE)
                    }.toTypedArray()
                )
            ).accepted
        )
    }

    @Test
    fun tamperingWithADeclaredStatisticIsRejected() {
        val text = ArKernelDeviceFarmCorpusAdapter.encode(corpus())
        assertTrue(text.contains("matched_cells=3"))
        val parsed = ArKernelDeviceFarmCorpusAdapter.parse(
            text.replace("matched_cells=3", "matched_cells=4")
        )
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.STATISTICS_MISMATCH,
            parsed.rejection,
        )
    }

    @Test
    fun tamperingWithAVerdictIsRejected() {
        val flat = corpus(
            build(
                entry("cap.1", "phone.a", FULL, DAY, SCALE),
                entry("cap.2", "phone.b", LIMITED, DAY, SCALE),
                entry("cap.3", "phone.c", LEGACY, DAY, SCALE),
            )
        )
        assertFalse(flat.corpusSufficient)
        val text = ArKernelDeviceFarmCorpusAdapter.encode(flat)
        val parsed = ArKernelDeviceFarmCorpusAdapter.parse(
            text.replace(
                "corpus_sufficient=false",
                "corpus_sufficient=true",
            )
        )
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.CORPUS_MISMATCH,
            parsed.rejection,
        )
    }

    @Test
    fun safetyInvariantsCannotBeRelaxed() {
        val text = ArKernelDeviceFarmCorpusAdapter.encode(corpus())
        for (key in listOf(
            "automatic_capture_disabled",
            "automatic_upload_disabled",
            "manual_upload_only",
            "per_device_calibration",
            "images_disabled",
        )) {
            assertTrue(text.contains("$key=true"))
            val parsed = ArKernelDeviceFarmCorpusAdapter.parse(
                text.replace("$key=true", "$key=false")
            )
            assertFalse(parsed.accepted)
            assertEquals(
                ArKernelDeviceFarmCorpusRejection.UNSAFE_INVARIANT,
                parsed.rejection,
            )
        }
        assertTrue(text.contains("metric_accuracy_claimed=false"))
        assertFalse(
            ArKernelDeviceFarmCorpusAdapter.parse(
                text.replace(
                    "metric_accuracy_claimed=false",
                    "metric_accuracy_claimed=true",
                )
            ).accepted
        )
    }

    @Test
    fun theGradeCanNeverBeUpgraded() {
        val text = ArKernelDeviceFarmCorpusAdapter.encode(corpus())
        assertTrue(text.contains("quality_grade=unverified"))
        val parsed = ArKernelDeviceFarmCorpusAdapter.parse(
            text.replace(
                "quality_grade=unverified",
                "quality_grade=centimeter-validated",
            )
        )
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_QUALITY,
            parsed.rejection,
        )
    }

    @Test
    fun malformedInputIsRejectedWithoutThrowing() {
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.EMPTY_CORPUS,
            ArKernelDeviceFarmCorpusAdapter.parse("   ").rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_HEADER,
            ArKernelDeviceFarmCorpusAdapter.parse("WRONG\nEND").rejection,
        )
        val text = ArKernelDeviceFarmCorpusAdapter.encode(corpus())
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INVALID_SCHEMA,
            ArKernelDeviceFarmCorpusAdapter.parse(
                text.replace("END", "DONE")
            ).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.LINE_TOO_LONG,
            ArKernelDeviceFarmCorpusAdapter.parse(
                text.replace(
                    "entry=0|cap.1|",
                    "entry=0|" + "x".repeat(300) + "|",
                )
            ).rejection,
        )
        assertEquals(
            ArKernelDeviceFarmCorpusRejection.INPUT_TOO_LARGE,
            ArKernelDeviceFarmCorpusAdapter.parse(
                "ARKCORPUS1\n" + "x\n".repeat(40_000)
            ).rejection,
        )
    }

    @Test
    fun aByteOrderMarkAndCarriageReturnsAreTolerated() {
        val source = corpus()
        val parsed = ArKernelDeviceFarmCorpusAdapter.parse(
            "\uFEFF" +
                ArKernelDeviceFarmCorpusAdapter.encode(source)
                    .replace("\n", "\r\n")
        )
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(source.entries, checkNotNull(parsed.corpus).entries)
    }

    @Test
    fun summarizeReportsStructureAndNothingAboutQuality() {
        val source = corpus()
        val summary = ArKernelDeviceFarmCorpusAdapter.summarize(source)
        assertEquals(6, summary.entryCount)
        assertEquals(3, summary.deviceCount)
        assertEquals(3, summary.deviceClassCount)
        assertEquals(2, summary.conditionCount)
        assertEquals(2, summary.sceneCount)
        assertEquals(600L, summary.sampleTotal)
        assertEquals(500, summary.observedMaxDeviceSharePermille)
        assertEquals(3, summary.totalCells)
        assertEquals(3, summary.matchedCells)
        assertTrue(summary.deviceDiversityMet)
        assertTrue(summary.conditionsCovered)
        assertTrue(summary.scenesCovered)
        assertTrue(summary.balanceOk)
        assertTrue(summary.crossDeviceReady)
        assertTrue(summary.corpusSufficient)
    }
}
