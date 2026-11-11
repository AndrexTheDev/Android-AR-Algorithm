package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class GoldenSuiteRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelGoldenSuitePlanRejection,
)

class ArKernelGoldenSuitePlanTest {
    @Test
    fun samplePlanRoundTripsAndReportsOnlyStructuralFacts() {
        val plan = samplePlan()
        val text = ArKernelGoldenSuitePlanBundle.encode(plan)
        val result = ArKernelGoldenSuitePlanBundle.parse(text)
        assertTrue(result.accepted)
        assertEquals(ArKernelGoldenSuitePlanRejection.NONE,
            result.rejection)
        assertEquals(plan, result.plan)
        assertEquals(text, ArKernelGoldenSuitePlanBundle.encode(
            result.plan!!))
        val report = ArKernelGoldenSuitePlanBundle.summarize(
            result.plan!!)
        assertEquals(2, report.caseCount)
        assertEquals(2, report.distinctCaseIds)
        assertEquals(2, report.distinctFrameDigests)
        assertEquals(1, report.semanticDetectionCount)
        assertEquals(1, report.metricDepthCount)
        assertEquals(1, report.sentinelCount)
        assertEquals(2, report.totalDetectionBudget)
        assertEquals(76_800L, report.totalDepthPixelBudget)
        assertEquals(307_200L, report.maxFramePixels)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.plan!!.qualityGrade)
        assertEquals(InferenceTask.SEMANTIC_DETECTION,
            result.plan!!.cases[0].task)
        assertEquals(ArKernelGoldenSuiteCaseRole.SENTINEL,
            result.plan!!.cases[0].role)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val plan = samplePlan()
        val text = ArKernelGoldenSuitePlanBundle.encode(plan)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result = ArKernelGoldenSuitePlanBundle.parse(variant)
            assertTrue("variant was rejected: ${result.detail}",
                result.accepted)
            assertEquals(plan, result.plan)
        }
        assertEquals(ArKernelGoldenSuitePlanRejection.EMPTY_LINE,
            ArKernelGoldenSuitePlanBundle.parse(text + "\n\n")
                .rejection)
    }

    @Test
    fun toleranceOptionsBuildOnlyTheExistingInertPolicy() {
        val plan = samplePlan()
        val options = plan.tolerances.options()
        assertEquals(1.0, options.boxTolerancePixels, 1e-12)
        assertEquals(1.0, options.anchorTolerancePixels, 1e-12)
        assertEquals(0.01, options.confidenceTolerance, 1e-12)
        assertEquals(0.02, options.depthAbsoluteToleranceMeters, 1e-12)
        assertEquals(0.02, options.depthRelativeTolerance, 1e-12)
        assertEquals(0.02, options.depthConfidenceTolerance, 1e-12)
        val custom = ArKernelGoldenSuiteTolerances(
            boxTolerancePixels = "2.5",
            anchorTolerancePixels = "0",
            confidenceTolerance = "0.125",
            depthAbsoluteToleranceMeters = "0.5",
            depthRelativeTolerance = "1",
            depthConfidenceTolerance = "999.999999",
        )
        assertEquals("2.5", custom.canonicalValues()[0])
        assertEquals(6, custom.canonicalValues().size)
        assertEquals(2.5, custom.options().boxTolerancePixels, 1e-12)
        assertEquals(0.0, custom.options().anchorTolerancePixels, 1e-12)
        assertEquals(999.999999,
            custom.options().depthConfidenceTolerance, 1e-12)
    }

    @Test
    fun maximalBoundedEnvelopeStaysCoherent() {
        val cases = buildList<ArKernelGoldenSuiteCase> {
            repeat(16) { index ->
                add(
                    ArKernelGoldenSuiteCase(
                        index,
                        "case-" + index.toString().padStart(2, '0'),
                        if (index % 2 == 0)
                            InferenceTask.SEMANTIC_DETECTION
                        else InferenceTask.METRIC_DEPTH,
                        index.toString(16).padStart(64, '0'),
                        4_096,
                        4_096,
                        256,
                        4_194_304,
                        if (index == 0)
                            ArKernelGoldenSuiteCaseRole.SENTINEL
                        else ArKernelGoldenSuiteCaseRole.GOLDEN,
                    ))
            }
        }
        val plan = ArKernelGoldenSuitePlan(
            suiteId = "maximal.envelope.v1",
            goldenSuiteSha256 = "d".repeat(64),
            tolerances = ArKernelGoldenSuiteTolerances(
                "999.999999", "999.999999", "999.999999",
                "999.999999", "999.999999", "999.999999"),
            cases = cases,
        )
        val text = ArKernelGoldenSuitePlanBundle.encode(plan)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        val result = ArKernelGoldenSuitePlanBundle.parse(text)
        assertEquals(plan, result.plan)
        val report = ArKernelGoldenSuitePlanBundle.summarize(
            result.plan!!)
        assertEquals(16, report.caseCount)
        assertEquals(8, report.semanticDetectionCount)
        assertEquals(8, report.metricDepthCount)
        assertEquals(1, report.sentinelCount)
        assertEquals(16 * 256, report.totalDetectionBudget)
        assertEquals(16L * 4_194_304L, report.totalDepthPixelBudget)
        assertEquals(16_777_216L, report.maxFramePixels)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialPlan() {
        val text = ArKernelGoldenSuitePlanBundle.encode(samplePlan())
        val duplicateId = replaceField(text, 18, 1, "semantic-01")
        val duplicateDigest = replaceField(text, 18, 3, "a".repeat(64))
        val noSentinel = replaceField(text, 17, 8, "golden")
        val twoSentinels = replaceField(text, 18, 8, "sentinel")
        val cases = listOf(
            GoldenSuiteRejectionCase("inputTooLarge",
                "x".repeat(8_193),
                ArKernelGoldenSuitePlanRejection.INPUT_TOO_LARGE),
            GoldenSuiteRejectionCase("empty",
                "",
                ArKernelGoldenSuitePlanRejection.EMPTY_PLAN),
            GoldenSuiteRejectionCase("badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelGoldenSuitePlanRejection.INVALID_HEADER),
            GoldenSuiteRejectionCase("badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelGoldenSuitePlanRejection.INVALID_VALUE),
            GoldenSuiteRejectionCase("assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified",
                    "quality_grade=verified"),
                ArKernelGoldenSuitePlanRejection.INVALID_QUALITY),
            GoldenSuiteRejectionCase("modelLoadingEnabled",
                replaceOnce(text, "model_loading_disabled=true",
                    "model_loading_disabled=false"),
                ArKernelGoldenSuitePlanRejection.UNSAFE_INVARIANT),
            GoldenSuiteRejectionCase("delegateExecutionEnabled",
                replaceOnce(text, "delegate_execution_disabled=true",
                    "delegate_execution_disabled=false"),
                ArKernelGoldenSuitePlanRejection.UNSAFE_INVARIANT),
            GoldenSuiteRejectionCase("evidenceWriteEnabled",
                replaceOnce(text, "evidence_cache_write_disabled=true",
                    "evidence_cache_write_disabled=false"),
                ArKernelGoldenSuitePlanRejection.UNSAFE_INVARIANT),
            GoldenSuiteRejectionCase("uploadEnabled",
                replaceOnce(text, "automatic_upload_disabled=true",
                    "automatic_upload_disabled=false"),
                ArKernelGoldenSuitePlanRejection.UNSAFE_INVARIANT),
            GoldenSuiteRejectionCase("corporaEnabled",
                replaceOnce(text, "device_farm_corpora_disabled=true",
                    "device_farm_corpora_disabled=false"),
                ArKernelGoldenSuitePlanRejection.UNSAFE_INVARIANT),
            GoldenSuiteRejectionCase("invalidSuiteId",
                replaceOnce(text, "suite_id=golden.semantic.v1",
                    "suite_id=bad!id"),
                ArKernelGoldenSuitePlanRejection.INVALID_TOKEN),
            GoldenSuiteRejectionCase("invalidSuiteDigest",
                replaceOnce(text,
                    "golden_suite_sha256=" + "c".repeat(64),
                    "golden_suite_sha256=" + "C".repeat(64)),
                ArKernelGoldenSuitePlanRejection.INVALID_DIGEST),
            GoldenSuiteRejectionCase("shortSuiteDigest",
                replaceOnce(text,
                    "golden_suite_sha256=" + "c".repeat(64),
                    "golden_suite_sha256=" + "c".repeat(63)),
                ArKernelGoldenSuitePlanRejection.INVALID_DIGEST),
            GoldenSuiteRejectionCase("countTooLarge",
                replaceOnce(text, "case_count=2", "case_count=17"),
                ArKernelGoldenSuitePlanRejection.INVALID_CASE_COUNT),
            GoldenSuiteRejectionCase("countZero",
                replaceOnce(text, "case_count=2", "case_count=0"),
                ArKernelGoldenSuitePlanRejection.INVALID_CASE_COUNT),
            GoldenSuiteRejectionCase("lineCountMismatch",
                replaceOnce(text, "case_count=2", "case_count=1"),
                ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA),
            GoldenSuiteRejectionCase("keyOrder",
                replaceLine(text, 1, "suite_id=golden.semantic.v1"),
                ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA),
            GoldenSuiteRejectionCase("lineTooLong",
                replaceLine(text, 17, "x".repeat(257)),
                ArKernelGoldenSuitePlanRejection.LINE_TOO_LONG),
            GoldenSuiteRejectionCase("blankLine",
                replaceLine(text, 17, ""),
                ArKernelGoldenSuitePlanRejection.EMPTY_LINE),
            GoldenSuiteRejectionCase("extraFinalSeparator",
                text + "\n\n",
                ArKernelGoldenSuitePlanRejection.EMPTY_LINE),
            GoldenSuiteRejectionCase("missingEnd",
                replaceLine(text, 19, "STOP"),
                ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA),
            GoldenSuiteRejectionCase("nonContiguousIndex",
                replaceOnce(text, "case=0|", "case=1|"),
                ArKernelGoldenSuitePlanRejection.INVALID_ORDER),
            GoldenSuiteRejectionCase("duplicateCaseId",
                duplicateId,
                ArKernelGoldenSuitePlanRejection.DUPLICATE_CASE_ID),
            GoldenSuiteRejectionCase("duplicateFrameDigest",
                duplicateDigest,
                ArKernelGoldenSuitePlanRejection
                    .DUPLICATE_FRAME_DIGEST),
            GoldenSuiteRejectionCase("noSentinel",
                noSentinel,
                ArKernelGoldenSuitePlanRejection
                    .INVALID_SENTINEL_COUNT),
            GoldenSuiteRejectionCase("twoSentinels",
                twoSentinels,
                ArKernelGoldenSuitePlanRejection
                    .INVALID_SENTINEL_COUNT),
            GoldenSuiteRejectionCase("relativeDepthTask",
                replaceField(text, 17, 2, "RELATIVE_DEPTH"),
                ArKernelGoldenSuitePlanRejection.INVALID_TASK),
            GoldenSuiteRejectionCase("segmentationTask",
                replaceField(text, 17, 2, "SEGMENTATION"),
                ArKernelGoldenSuitePlanRejection.INVALID_TASK),
            GoldenSuiteRejectionCase("unknownTask",
                replaceField(text, 17, 2, "NO_SUCH_TASK"),
                ArKernelGoldenSuitePlanRejection.INVALID_TASK),
            GoldenSuiteRejectionCase("invalidRole",
                replaceField(text, 17, 8, "reference"),
                ArKernelGoldenSuitePlanRejection.INVALID_ROLE),
            GoldenSuiteRejectionCase("invalidCaseToken",
                replaceField(text, 17, 1, "bad!id"),
                ArKernelGoldenSuitePlanRejection.INVALID_TOKEN),
            GoldenSuiteRejectionCase("invalidFrameDigest",
                replaceField(text, 17, 3, "A".repeat(64)),
                ArKernelGoldenSuitePlanRejection.INVALID_DIGEST),
            GoldenSuiteRejectionCase("zeroWidth",
                replaceField(text, 17, 4, "0"),
                ArKernelGoldenSuitePlanRejection.INVALID_FRAME_SIZE),
            GoldenSuiteRejectionCase("widthAboveBound",
                replaceField(text, 17, 4, "4097"),
                ArKernelGoldenSuitePlanRejection.INVALID_FRAME_SIZE),
            GoldenSuiteRejectionCase("heightAboveBound",
                replaceField(text, 17, 5, "4097"),
                ArKernelGoldenSuitePlanRejection.INVALID_FRAME_SIZE),
            GoldenSuiteRejectionCase("detectionsAboveBound",
                replaceField(text, 17, 6, "257"),
                ArKernelGoldenSuitePlanRejection.INVALID_BUDGET),
            GoldenSuiteRejectionCase("depthPixelsAboveBound",
                replaceField(text, 17, 7, "4194305"),
                ArKernelGoldenSuitePlanRejection.INVALID_BUDGET),
            GoldenSuiteRejectionCase("negativeTolerance",
                replaceOnce(text, "box_tolerance_px=1",
                    "box_tolerance_px=-1"),
                ArKernelGoldenSuitePlanRejection.INVALID_TOLERANCE),
            GoldenSuiteRejectionCase("exponentTolerance",
                replaceOnce(text, "box_tolerance_px=1",
                    "box_tolerance_px=1e-3"),
                ArKernelGoldenSuitePlanRejection.INVALID_TOLERANCE),
            GoldenSuiteRejectionCase("leadingZeroTolerance",
                replaceOnce(text, "box_tolerance_px=1",
                    "box_tolerance_px=01"),
                ArKernelGoldenSuitePlanRejection.INVALID_TOLERANCE),
            GoldenSuiteRejectionCase("tooPreciseTolerance",
                replaceOnce(text, "box_tolerance_px=1",
                    "box_tolerance_px=1.1234567"),
                ArKernelGoldenSuitePlanRejection.INVALID_TOLERANCE),
            GoldenSuiteRejectionCase("tooLargeTolerance",
                replaceOnce(text, "box_tolerance_px=1",
                    "box_tolerance_px=1000"),
                ArKernelGoldenSuitePlanRejection.INVALID_TOLERANCE),
            GoldenSuiteRejectionCase("trailingDotTolerance",
                replaceOnce(text, "confidence_tolerance=0.01",
                    "confidence_tolerance=0."),
                ArKernelGoldenSuitePlanRejection.INVALID_TOLERANCE),
            GoldenSuiteRejectionCase("fieldCount",
                replaceLine(text, 17, "case=0|only-three"),
                ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA),
            GoldenSuiteRejectionCase("nonNumericIndex",
                replaceField(text, 17, 0, "x"),
                ArKernelGoldenSuitePlanRejection.INVALID_VALUE),
            GoldenSuiteRejectionCase("nonNumericBudget",
                replaceField(text, 17, 6, "x"),
                ArKernelGoldenSuitePlanRejection.INVALID_VALUE),
        )
        assertEquals(46, cases.size)
        for (case in cases) {
            val result = ArKernelGoldenSuitePlanBundle.parse(case.text)
            assertFalse("${case.name} unexpectedly accepted",
                result.accepted)
            assertNull("${case.name} leaked a partial plan",
                result.plan)
            assertEquals(case.name, case.reason, result.rejection)
            assertTrue("${case.name} lacked a rejection detail",
                result.detail.isNotBlank())
            assertTrue("${case.name} had a negative line number",
                result.lineNumber >= 0)
        }
    }

    @Test
    fun planAndCaseConstructorsRejectUnsafeShapes() {
        val cases = samplePlan().cases
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuitePlan(
                suiteId = "unsafe.v1",
                goldenSuiteSha256 = "c".repeat(64),
                modelLoadingDisabled = false,
                cases = cases,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuitePlan(
                suiteId = "unsafe.v1",
                goldenSuiteSha256 = "c".repeat(64),
                automaticUploadDisabled = false,
                cases = cases,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuitePlan(
                suiteId = "unsafe.v1",
                goldenSuiteSha256 = "c".repeat(64),
                qualityGrade = "verified",
                cases = cases,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuitePlan(
                suiteId = "doubled.v1",
                goldenSuiteSha256 = "c".repeat(64),
                cases = listOf(cases[0], cases[0]),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuitePlan(
                suiteId = "gapped.v1",
                goldenSuiteSha256 = "c".repeat(64),
                cases = listOf(cases[0], cases[1].copy(index = 2)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuitePlan(
                suiteId = "no.sentinel.v1",
                goldenSuiteSha256 = "c".repeat(64),
                cases = listOf(
                    cases[0].copy(
                        role = ArKernelGoldenSuiteCaseRole.GOLDEN),
                    cases[1],
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuitePlan(
                suiteId = "two.sentinels.v1",
                goldenSuiteSha256 = "c".repeat(64),
                cases = listOf(
                    cases[0],
                    cases[1].copy(
                        role = ArKernelGoldenSuiteCaseRole.SENTINEL),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuiteTolerances(
                boxTolerancePixels = "-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuiteCase(
                0, "relative-01",
                InferenceTask.RELATIVE_DEPTH,
                "a".repeat(64), 640, 480, 0, 0,
                ArKernelGoldenSuiteCaseRole.GOLDEN,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuiteCase(
                0, "segmentation-01",
                InferenceTask.SEGMENTATION,
                "a".repeat(64), 640, 480, 0, 0,
                ArKernelGoldenSuiteCaseRole.GOLDEN,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuiteCase(
                0, "oversize-01",
                InferenceTask.METRIC_DEPTH,
                "a".repeat(64), 4_097, 480, 0, 0,
                ArKernelGoldenSuiteCaseRole.GOLDEN,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelGoldenSuiteCase(
                0, "budget-01",
                InferenceTask.METRIC_DEPTH,
                "a".repeat(64), 640, 480, 0, 4_194_305,
                ArKernelGoldenSuiteCaseRole.GOLDEN,
            )
        }
    }

    private fun samplePlan(): ArKernelGoldenSuitePlan =
        ArKernelGoldenSuitePlan(
            suiteId = "golden.semantic.v1",
            goldenSuiteSha256 = "c".repeat(64),
            cases = listOf(
                ArKernelGoldenSuiteCase(
                    0,
                    "semantic-01",
                    InferenceTask.SEMANTIC_DETECTION,
                    "a".repeat(64),
                    640,
                    480,
                    2,
                    0,
                    ArKernelGoldenSuiteCaseRole.SENTINEL,
                ),
                ArKernelGoldenSuiteCase(
                    1,
                    "depth-01",
                    InferenceTask.METRIC_DEPTH,
                    "b".repeat(64),
                    320,
                    240,
                    0,
                    76_800,
                    ArKernelGoldenSuiteCaseRole.GOLDEN,
                ),
            ),
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

    private fun replaceField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.split("\n").toMutableList()
        val prefix = "case="
        val fields = lines[lineIndex].removePrefix(prefix)
            .split("|").toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
