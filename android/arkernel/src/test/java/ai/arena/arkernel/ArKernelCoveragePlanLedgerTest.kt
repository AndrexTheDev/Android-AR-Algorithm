package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class CoverageRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelCoveragePlanRejection,
)

class ArKernelCoveragePlanLedgerTest {
    @Test
    fun sampleLedgerRoundTripsAndReportsOnlyStructuralFacts() {
        val ledger = sampleLedger()
        val text = ArKernelCoveragePlanBundle.encode(ledger)
        val result = ArKernelCoveragePlanBundle.parse(text)
        assertTrue(result.accepted)
        assertEquals(ArKernelCoveragePlanRejection.NONE,
            result.rejection)
        assertEquals(ledger, result.ledger)
        assertEquals(text, ArKernelCoveragePlanBundle.encode(
            result.ledger!!))
        val report = ArKernelCoveragePlanBundle.summarize(
            result.ledger!!)
        assertEquals(2, report.selectionCount)
        assertEquals(2, report.uncoveredCount)
        assertEquals(2, report.distinctCandidateIds)
        assertEquals(3, report.coveredRequirements)
        assertEquals(5, report.totalRequirements)
        assertEquals(12, report.coveredWeight)
        assertEquals(20, report.totalWeight)
        assertEquals(1, report.singleFeatureUncoveredCount)
        assertEquals(1, report.pairwiseFeatureUncoveredCount)
        assertEquals(3, report.distinctDimensions)
        assertFalse(report.complete)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.ledger!!.qualityGrade)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val ledger = sampleLedger()
        val text = ArKernelCoveragePlanBundle.encode(ledger)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result = ArKernelCoveragePlanBundle.parse(variant)
            assertTrue("variant was rejected: ${result.detail}",
                result.accepted)
            assertEquals(ledger, result.ledger)
        }
        assertEquals(ArKernelCoveragePlanRejection.EMPTY_LINE,
            ArKernelCoveragePlanBundle.parse(text + "\n\n")
                .rejection)
    }

    @Test
    fun fromPlanAndToFormPlanRoundTripExactly() {
        val plan = hostPlan()
        val ledger = ArKernelCoveragePlanBundle.fromPlan(
            plan, "lab.inventory.v1")
        assertEquals(sampleLedger(), ledger)
        val text = ArKernelCoveragePlanBundle.encode(ledger)
        val parsed = ArKernelCoveragePlanBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(plan, parsed.ledger!!.toPlan())
        assertFalse(parsed.ledger!!.toPlan().complete)
        assertEquals(
            listOf("lab-risky", "lab-normal"),
            parsed.ledger!!.toPlan().selections.map {
                it.candidateId
            })
        assertEquals(2,
            parsed.ledger!!.toPlan().uncoveredRequirements.size)
        assertEquals(1,
            parsed.ledger!!.toPlan()
                .uncoveredRequirements[0].features.size)
        assertEquals(2,
            parsed.ledger!!.toPlan()
                .uncoveredRequirements[1].features.size)
    }

    @Test
    fun maximalBoundedEnvelopeStaysCoherent() {
        val selections = buildList<ArKernelCoverageSelection> {
            repeat(64) { index ->
                add(
                    ArKernelCoverageSelection(
                        index,
                        "candidate-" +
                            index.toString().padStart(2, '0'),
                        255,
                        15_000,
                    ))
            }
        }
        val uncovered = buildList<ArKernelCoverageUncovered> {
            repeat(64) { index ->
                val dimensions = CapabilityCoverageDimension.entries
                val first = dimensions[index % 9]
                val second = dimensions[(index % 9) + 1]
                add(
                    ArKernelCoverageUncovered(
                        index,
                        2,
                        first,
                        "value-" + index.toString().padStart(2, '0'),
                        second,
                        "pair-" + index.toString().padStart(2, '0'),
                        100,
                    ))
            }
        }
        val ledger = ArKernelCoveragePlanLedger(
            inventoryId = "maximal.envelope.v1",
            selections = selections,
            uncovered = uncovered,
            coveredRequirements = 64 * 255,
            totalRequirements = 64 * 255 + 64,
            coveredWeight = 64 * 15_000,
            totalWeight = 64 * 15_000 + 6_400,
        )
        val text = ArKernelCoveragePlanBundle.encode(ledger)
        assertTrue(text.length <= 32_768)
        assertTrue(text.lines().all { it.length <= 256 })
        assertEquals(ledger,
            ArKernelCoveragePlanBundle.parse(text).ledger)
        val report = ArKernelCoveragePlanBundle.summarize(ledger)
        assertEquals(64, report.selectionCount)
        assertEquals(64, report.uncoveredCount)
        assertEquals(64, report.pairwiseFeatureUncoveredCount)
        assertEquals(0, report.singleFeatureUncoveredCount)
        assertEquals(16_384, report.totalRequirements)
        assertFalse(report.complete)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialLedger() {
        val text = ArKernelCoveragePlanBundle.encode(sampleLedger())
        val duplicateCandidate = selectionField(
            text, 16, 1, "lab-risky")
        val duplicateRequirement = replaceLine(
            text, 18,
            "uncovered=1|1|API_CLASS|api-ge-34|none|none|1")
        val cases = listOf(
            CoverageRejectionCase("inputTooLarge",
                "x".repeat(32_769),
                ArKernelCoveragePlanRejection.INPUT_TOO_LARGE),
            CoverageRejectionCase("empty",
                "",
                ArKernelCoveragePlanRejection.EMPTY_LEDGER),
            CoverageRejectionCase("badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelCoveragePlanRejection.INVALID_HEADER),
            CoverageRejectionCase("badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelCoveragePlanRejection.INVALID_VALUE),
            CoverageRejectionCase("assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified",
                    "quality_grade=verified"),
                ArKernelCoveragePlanRejection.INVALID_QUALITY),
            CoverageRejectionCase("providerBindingEnabled",
                replaceOnce(text, "provider_binding_disabled=true",
                    "provider_binding_disabled=false"),
                ArKernelCoveragePlanRejection.UNSAFE_INVARIANT),
            CoverageRejectionCase("moblyRigEnabled",
                replaceOnce(text, "mobly_rig_control_disabled=true",
                    "mobly_rig_control_disabled=false"),
                ArKernelCoveragePlanRejection.UNSAFE_INVARIANT),
            CoverageRejectionCase("thermalRunEnabled",
                replaceOnce(text,
                    "thermal_run_execution_disabled=true",
                    "thermal_run_execution_disabled=false"),
                ArKernelCoveragePlanRejection.UNSAFE_INVARIANT),
            CoverageRejectionCase("sceneCaptureEnabled",
                replaceOnce(text,
                    "physical_scene_capture_disabled=true",
                    "physical_scene_capture_disabled=false"),
                ArKernelCoveragePlanRejection.UNSAFE_INVARIANT),
            CoverageRejectionCase("uploadEnabled",
                replaceOnce(text, "automatic_upload_disabled=true",
                    "automatic_upload_disabled=false"),
                ArKernelCoveragePlanRejection.UNSAFE_INVARIANT),
            CoverageRejectionCase("invalidInventoryId",
                replaceOnce(text,
                    "inventory_id=lab.inventory.v1",
                    "inventory_id=bad!id"),
                ArKernelCoveragePlanRejection.INVALID_TOKEN),
            CoverageRejectionCase("selectionCountTooLarge",
                replaceOnce(text, "selection_count=2",
                    "selection_count=65"),
                ArKernelCoveragePlanRejection.INVALID_COUNT),
            CoverageRejectionCase("uncoveredCountTooLarge",
                replaceOnce(text, "uncovered_count=2",
                    "uncovered_count=65"),
                ArKernelCoveragePlanRejection.INVALID_COUNT),
            CoverageRejectionCase("coveredRequirementsTooLarge",
                replaceOnce(text, "covered_requirements=3",
                    "covered_requirements=16385"),
                ArKernelCoveragePlanRejection.INVALID_VALUE),
            CoverageRejectionCase("totalWeightTooLarge",
                replaceOnce(text, "total_weight=20",
                    "total_weight=1000001"),
                ArKernelCoveragePlanRejection.INVALID_VALUE),
            CoverageRejectionCase("nonNumericCovered",
                replaceOnce(text, "covered_requirements=3",
                    "covered_requirements=x"),
                ArKernelCoveragePlanRejection.INVALID_VALUE),
            CoverageRejectionCase("lineCountMismatch",
                replaceOnce(text, "selection_count=2",
                    "selection_count=1"),
                ArKernelCoveragePlanRejection.INVALID_SCHEMA),
            CoverageRejectionCase("keyOrder",
                replaceLine(text, 1,
                    "inventory_id=lab.inventory.v1"),
                ArKernelCoveragePlanRejection.INVALID_SCHEMA),
            CoverageRejectionCase("lineTooLong",
                replaceLine(text, 15, "x".repeat(257)),
                ArKernelCoveragePlanRejection.LINE_TOO_LONG),
            CoverageRejectionCase("blankLine",
                replaceLine(text, 15, ""),
                ArKernelCoveragePlanRejection.EMPTY_LINE),
            CoverageRejectionCase("extraFinalSeparator",
                text + "\n\n",
                ArKernelCoveragePlanRejection.EMPTY_LINE),
            CoverageRejectionCase("missingEnd",
                replaceLine(text, 19, "STOP"),
                ArKernelCoveragePlanRejection.INVALID_SCHEMA),
            CoverageRejectionCase("selectionOrder",
                replaceOnce(text, "selection=0|", "selection=1|"),
                ArKernelCoveragePlanRejection.INVALID_ORDER),
            CoverageRejectionCase("uncoveredOrder",
                replaceOnce(text, "uncovered=0|", "uncovered=1|"),
                ArKernelCoveragePlanRejection.INVALID_ORDER),
            CoverageRejectionCase("duplicateCandidate",
                duplicateCandidate,
                ArKernelCoveragePlanRejection.DUPLICATE_CANDIDATE),
            CoverageRejectionCase("duplicateRequirement",
                duplicateRequirement,
                ArKernelCoveragePlanRejection
                    .DUPLICATE_REQUIREMENT),
            CoverageRejectionCase("selectionFieldCount",
                replaceLine(text, 15, "selection=0|only"),
                ArKernelCoveragePlanRejection.INVALID_SCHEMA),
            CoverageRejectionCase("uncoveredFieldCount",
                replaceLine(text, 17, "uncovered=0|only"),
                ArKernelCoveragePlanRejection.INVALID_SCHEMA),
            CoverageRejectionCase("wrongSelectionPrefix",
                replaceLine(text, 15, "uncovered=0|lab-risky|2|8"),
                ArKernelCoveragePlanRejection.INVALID_SCHEMA),
            CoverageRejectionCase("wrongUncoveredPrefix",
                replaceLine(text, 17,
                    "selection=0|1|API_CLASS|api-ge-34|none|none|1"),
                ArKernelCoveragePlanRejection.INVALID_SCHEMA),
            CoverageRejectionCase("invalidCandidateToken",
                selectionField(text, 15, 1, "bad!id"),
                ArKernelCoveragePlanRejection.INVALID_TOKEN),
            CoverageRejectionCase("selectionRequirementAboveBound",
                selectionField(text, 15, 2, "16385"),
                ArKernelCoveragePlanRejection.INVALID_VALUE),
            CoverageRejectionCase("selectionWeightAboveBound",
                selectionField(text, 15, 3, "1000001"),
                ArKernelCoveragePlanRejection.INVALID_VALUE),
            CoverageRejectionCase("nonNumericSelectionIndex",
                selectionField(text, 15, 0, "x"),
                ArKernelCoveragePlanRejection.INVALID_VALUE),
            CoverageRejectionCase("unknownDimension",
                uncoveredField(text, 17, 2, "NO_DIM"),
                ArKernelCoveragePlanRejection.INVALID_DIMENSION),
            CoverageRejectionCase("nonAscendingDimensions",
                replaceLine(text, 18,
                    "uncovered=1|2|IMU_CLASS|accel-gyro|" +
                        "ABI_CLASS|arm64-v8a|2"),
                ArKernelCoveragePlanRejection.INVALID_DIMENSION),
            CoverageRejectionCase("missingSecondDimension",
                uncoveredField(text, 18, 4, "none"),
                ArKernelCoveragePlanRejection.INVALID_DIMENSION),
            CoverageRejectionCase("zeroFeatureCount",
                uncoveredField(text, 17, 1, "0"),
                ArKernelCoveragePlanRejection
                    .INVALID_FEATURE_COUNT),
            CoverageRejectionCase("threeFeatureCount",
                uncoveredField(text, 17, 1, "3"),
                ArKernelCoveragePlanRejection
                    .INVALID_FEATURE_COUNT),
            CoverageRejectionCase("singleFeatureWithPlaceholders",
                uncoveredField(text, 17, 4, "ABI_CLASS"),
                ArKernelCoveragePlanRejection
                    .INVALID_FEATURE_COUNT),
            CoverageRejectionCase("invalidFirstValue",
                uncoveredField(text, 17, 3, "bad!id"),
                ArKernelCoveragePlanRejection.INVALID_TOKEN),
            CoverageRejectionCase("invalidSecondValue",
                uncoveredField(text, 18, 5, "bad!id"),
                ArKernelCoveragePlanRejection.INVALID_TOKEN),
            CoverageRejectionCase("zeroWeight",
                uncoveredField(text, 17, 6, "0"),
                ArKernelCoveragePlanRejection.INVALID_WEIGHT),
            CoverageRejectionCase("weightAboveBound",
                uncoveredField(text, 17, 6, "101"),
                ArKernelCoveragePlanRejection.INVALID_WEIGHT),
            CoverageRejectionCase("coveredSumMismatch",
                selectionField(text, 16, 2, "2"),
                ArKernelCoveragePlanRejection.INVALID_CONSISTENCY),
            CoverageRejectionCase("weightSumMismatch",
                selectionField(text, 16, 3, "5"),
                ArKernelCoveragePlanRejection.INVALID_CONSISTENCY),
            CoverageRejectionCase("requirementTotalMismatch",
                replaceOnce(text, "covered_requirements=3",
                    "covered_requirements=4"),
                ArKernelCoveragePlanRejection.INVALID_CONSISTENCY),
            CoverageRejectionCase("coveredWeightAboveTotal",
                replaceOnce(text, "covered_weight=12",
                    "covered_weight=21"),
                ArKernelCoveragePlanRejection.INVALID_CONSISTENCY),
        )
        assertEquals(48, cases.size)
        for (case in cases) {
            val result = ArKernelCoveragePlanBundle.parse(case.text)
            assertFalse("${case.name} unexpectedly accepted",
                result.accepted)
            assertNull("${case.name} leaked a partial ledger",
                result.ledger)
            assertEquals(case.name, case.reason, result.rejection)
            assertTrue("${case.name} lacked a rejection detail",
                result.detail.isNotBlank())
            assertTrue("${case.name} had a negative line number",
                result.lineNumber >= 0)
        }
    }

    @Test
    fun ledgerAndRowConstructorsRejectUnsafeShapes() {
        val selections = sampleLedger().selections
        val uncovered = sampleLedger().uncovered
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoveragePlanLedger(
                inventoryId = "doubled.v1",
                selections = listOf(selections[0], selections[0]),
                uncovered = uncovered,
                coveredRequirements = 3,
                totalRequirements = 5,
                coveredWeight = 12,
                totalWeight = 20,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoveragePlanLedger(
                inventoryId = "gapped.v1",
                selections = listOf(
                    selections[0],
                    selections[1].copy(index = 2),
                ),
                uncovered = uncovered,
                coveredRequirements = 3,
                totalRequirements = 5,
                coveredWeight = 12,
                totalWeight = 20,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoveragePlanLedger(
                inventoryId = "inconsistent.v1",
                selections = selections,
                uncovered = uncovered,
                coveredRequirements = 4,
                totalRequirements = 5,
                coveredWeight = 12,
                totalWeight = 20,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoveragePlanLedger(
                inventoryId = "unsafe.v1",
                selections = selections,
                uncovered = uncovered,
                coveredRequirements = 3,
                totalRequirements = 5,
                coveredWeight = 12,
                totalWeight = 20,
                qualityGrade = "verified",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoverageSelection(-1, "lab-a", 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoverageSelection(0, "bad!id", 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoverageUncovered(
                0, 1,
                CapabilityCoverageDimension.API_CLASS, "api-ge-34",
                CapabilityCoverageDimension.ABI_CLASS, "arm64-v8a", 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoverageUncovered(
                0, 2,
                CapabilityCoverageDimension.IMU_CLASS, "accel-gyro",
                CapabilityCoverageDimension.ABI_CLASS, "arm64-v8a", 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoverageUncovered(
                0, 2,
                CapabilityCoverageDimension.API_CLASS, "api-ge-34",
                null, null, 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoverageUncovered(
                0, 1,
                CapabilityCoverageDimension.API_CLASS, "bad!id",
                null, null, 101,
            )
        }
    }

    @Test
    fun fromPlanRejectsPlansOutsideTheBoundedLedger() {
        val base = hostPlan()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoveragePlanBundle.fromPlan(
                base.copy(selections = List(65) {
                    CapabilityCoverageSelection("candidate-$it", 0, 0)
                }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoveragePlanBundle.fromPlan(
                base.copy(totalRequirements = 16_385))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoveragePlanBundle.fromPlan(
                base.copy(
                    uncoveredRequirements = listOf(
                        CapabilityCoverageRequirement(
                            listOf(
                                CapabilityCoverageFeature(
                                    CapabilityCoverageDimension.API_CLASS,
                                    "x".repeat(33),
                                )),
                            1,
                        ),
                    ),
                    coveredRequirements = base.coveredRequirements,
                    totalRequirements = base.coveredRequirements + 1,
                ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCoveragePlanBundle.fromPlan(
                base.copy(coveredWeight = base.totalWeight + 1))
        }
    }

    private fun hostPlan(): CapabilityCoveragePlan =
        CapabilityCoveragePlan(
            selections = listOf(
                CapabilityCoverageSelection("lab-risky", 2, 8),
                CapabilityCoverageSelection("lab-normal", 1, 4),
            ),
            coveredRequirements = 3,
            totalRequirements = 5,
            coveredWeight = 12,
            totalWeight = 20,
            uncoveredRequirements = listOf(
                CapabilityCoverageRequirement(
                    listOf(
                        CapabilityCoverageFeature(
                            CapabilityCoverageDimension.API_CLASS,
                            "api-ge-34",
                        )),
                    1,
                ),
                CapabilityCoverageRequirement(
                    listOf(
                        CapabilityCoverageFeature(
                            CapabilityCoverageDimension.ABI_CLASS,
                            "arm64-v8a",
                        ),
                        CapabilityCoverageFeature(
                            CapabilityCoverageDimension.IMU_CLASS,
                            "accel-gyro",
                        )),
                    2,
                ),
            ),
        )

    private fun sampleLedger(): ArKernelCoveragePlanLedger =
        ArKernelCoveragePlanLedger(
            inventoryId = "lab.inventory.v1",
            selections = listOf(
                ArKernelCoverageSelection(0, "lab-risky", 2, 8),
                ArKernelCoverageSelection(1, "lab-normal", 1, 4),
            ),
            uncovered = listOf(
                ArKernelCoverageUncovered(
                    0, 1,
                    CapabilityCoverageDimension.API_CLASS,
                    "api-ge-34",
                    null,
                    null,
                    1,
                ),
                ArKernelCoverageUncovered(
                    1, 2,
                    CapabilityCoverageDimension.ABI_CLASS,
                    "arm64-v8a",
                    CapabilityCoverageDimension.IMU_CLASS,
                    "accel-gyro",
                    2,
                ),
            ),
            coveredRequirements = 3,
            totalRequirements = 5,
            coveredWeight = 12,
            totalWeight = 20,
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

    private fun selectionField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String = replaceField(text, lineIndex, fieldIndex,
        value, "selection=")

    private fun uncoveredField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String = replaceField(text, lineIndex, fieldIndex,
        value, "uncovered=")

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
