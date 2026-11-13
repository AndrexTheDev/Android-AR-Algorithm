package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class FaultCatalogRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelSyntheticFaultCatalogRejection,
)

class ArKernelSyntheticFaultCatalogTest {
    @Test
    fun sampleCatalogRoundTripsAndReportsOnlyStructuralFacts() {
        val catalog = sampleCatalog()
        val text = ArKernelSyntheticFaultCatalogBundle.encode(catalog)
        val result = ArKernelSyntheticFaultCatalogBundle.parse(text)
        assertTrue(result.accepted)
        assertEquals(ArKernelSyntheticFaultCatalogRejection.NONE,
            result.rejection)
        assertEquals(catalog, result.catalog)
        assertEquals(text, ArKernelSyntheticFaultCatalogBundle.encode(
            result.catalog!!))
        val report = ArKernelSyntheticFaultCatalogBundle.summarize(
            result.catalog!!)
        assertEquals(2, report.scenarioCount)
        assertEquals(3, report.distinctFaults)
        assertEquals(2, report.distinctFaultPairs)
        assertEquals(60, report.minCameraSamples)
        assertEquals(60, report.maxCameraSamples)
        assertEquals(200, report.minImuSamplesPerSensor)
        assertEquals(200, report.maxImuSamplesPerSensor)
        assertEquals(2, report.dryRunCount)
        assertEquals(1L, report.maxSeed)
        assertFalse(report.pairwiseComplete)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.catalog!!.qualityGrade)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val catalog = sampleCatalog()
        val text = ArKernelSyntheticFaultCatalogBundle.encode(catalog)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result = ArKernelSyntheticFaultCatalogBundle.parse(variant)
            assertTrue("variant was rejected: ${result.detail}",
                result.accepted)
            assertEquals(catalog, result.catalog)
        }
        assertEquals(ArKernelSyntheticFaultCatalogRejection.EMPTY_LINE,
            ArKernelSyntheticFaultCatalogBundle.parse(text + "\n\n")
                .rejection)
    }

    @Test
    fun pairwiseFactoryMatchesTheExistingScalarReplayCatalogExactly() {
        val pairwise = SyntheticCompatibilityScenarioCatalog.pairwiseFaults()
        assertEquals(45, pairwise.size)
        val catalog = ArKernelSyntheticFaultCatalogBundle
            .fromPairwiseCatalog()
        assertEquals(45, catalog.scenarios.size)
        assertEquals(pairwise.map { it.scenarioId },
            catalog.scenarios.map { it.scenarioId })
        for (index in pairwise.indices) {
            val ordered = pairwise[index].faults.sortedBy { it.ordinal }
            assertEquals("fault A at $index", ordered[0],
                catalog.scenarios[index].faultA)
            assertEquals("fault B at $index", ordered[1],
                catalog.scenarios[index].faultB)
            assertEquals(index, catalog.scenarios[index].index)
        }
        val text = ArKernelSyntheticFaultCatalogBundle.encode(catalog)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        val parsed = ArKernelSyntheticFaultCatalogBundle.parse(text)
        assertEquals(catalog, parsed.catalog)
        val report = ArKernelSyntheticFaultCatalogBundle.summarize(
            parsed.catalog!!)
        assertEquals(45, report.scenarioCount)
        assertEquals(10, report.distinctFaults)
        assertEquals(45, report.distinctFaultPairs)
        assertEquals(45, report.dryRunCount)
        assertEquals(1L, report.maxSeed)
        assertTrue(report.pairwiseComplete)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun maximalBoundedEnvelopeStaysCoherent() {
        val catalog = ArKernelSyntheticFaultCatalogBundle
            .fromPairwiseCatalog(
                seed = Long.MAX_VALUE,
                cameraSamples = 512,
                imuSamplesPerSensor = 2_048,
                dryRun = true,
            )
        val text = ArKernelSyntheticFaultCatalogBundle.encode(catalog)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        val result = ArKernelSyntheticFaultCatalogBundle.parse(text)
        assertEquals(catalog, result.catalog)
        val report = ArKernelSyntheticFaultCatalogBundle.summarize(
            result.catalog!!)
        assertEquals(45, report.scenarioCount)
        assertEquals(512, report.minCameraSamples)
        assertEquals(512, report.maxCameraSamples)
        assertEquals(2_048, report.minImuSamplesPerSensor)
        assertEquals(2_048, report.maxImuSamplesPerSensor)
        assertEquals(Long.MAX_VALUE, report.maxSeed)
        assertTrue(report.pairwiseComplete)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun mutateDryRunAndBudgetFieldsStayWithinTheContract() {
        val text = ArKernelSyntheticFaultCatalogBundle.encode(
            sampleCatalog())
        val mutated = replaceField(text, 10, 7, "0")
        val result = ArKernelSyntheticFaultCatalogBundle.parse(mutated)
        assertEquals(1, ArKernelSyntheticFaultCatalogBundle
            .summarize(result.catalog!!).dryRunCount)
        assertFalse(result.catalog!!.scenarios[0].dryRun)
        assertTrue(result.catalog!!.scenarios[1].dryRun)
        val varied = replaceField(mutated, 11, 4, "512")
        val variedResult = ArKernelSyntheticFaultCatalogBundle.parse(varied)
        val report = ArKernelSyntheticFaultCatalogBundle.summarize(
            variedResult.catalog!!)
        assertEquals(60, report.minCameraSamples)
        assertEquals(512, report.maxCameraSamples)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialCatalog() {
        val text = ArKernelSyntheticFaultCatalogBundle.encode(
            sampleCatalog())
        val faultA = SyntheticCompatibilityFault.entries[0].name
        val faultB = SyntheticCompatibilityFault.entries[1].name
        val firstScenarioLine = text.lines()[10]
        val indexAnchor = "scenario=0|"
        check(firstScenarioLine.startsWith(indexAnchor))
        val duplicate = replaceLine(
            text, 11,
            "scenario=1|" +
                firstScenarioLine.substring(indexAnchor.length))
        val swapped = replaceField(
            replaceField(text, 10, 2, faultB), 10, 3, faultA)
        val cases = listOf(
            FaultCatalogRejectionCase("inputTooLarge",
                "x".repeat(8_193),
                ArKernelSyntheticFaultCatalogRejection.INPUT_TOO_LARGE),
            FaultCatalogRejectionCase("empty",
                "",
                ArKernelSyntheticFaultCatalogRejection.EMPTY_CATALOG),
            FaultCatalogRejectionCase("badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_HEADER),
            FaultCatalogRejectionCase("badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE),
            FaultCatalogRejectionCase("assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified",
                    "quality_grade=verified"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_QUALITY),
            FaultCatalogRejectionCase("frameworkInjectionEnabled",
                replaceOnce(text,
                    "framework_injection_disabled=true",
                    "framework_injection_disabled=false"),
                ArKernelSyntheticFaultCatalogRejection.UNSAFE_INVARIANT),
            FaultCatalogRejectionCase("binderDeathEnabled",
                replaceOnce(text,
                    "binder_service_death_disabled=true",
                    "binder_service_death_disabled=false"),
                ArKernelSyntheticFaultCatalogRejection.UNSAFE_INVARIANT),
            FaultCatalogRejectionCase("guidedFuzzingEnabled",
                replaceOnce(text,
                    "coverage_guided_fuzzing_disabled=true",
                    "coverage_guided_fuzzing_disabled=false"),
                ArKernelSyntheticFaultCatalogRejection.UNSAFE_INVARIANT),
            FaultCatalogRejectionCase("deviceFarmCorporaEnabled",
                replaceOnce(text,
                    "device_farm_corpora_disabled=true",
                    "device_farm_corpora_disabled=false"),
                ArKernelSyntheticFaultCatalogRejection.UNSAFE_INVARIANT),
            FaultCatalogRejectionCase("invalidCatalogId",
                replaceOnce(text, "catalog_id=synthetic.example.v1",
                    "catalog_id=bad!id"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_TOKEN),
            FaultCatalogRejectionCase("revisionTooLarge",
                replaceOnce(text, "revision=1", "revision=1000001"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE),
            FaultCatalogRejectionCase("countTooLarge",
                replaceOnce(text, "scenario_count=2",
                    "scenario_count=46"),
                ArKernelSyntheticFaultCatalogRejection
                    .INVALID_SCENARIO_COUNT),
            FaultCatalogRejectionCase("lineCountMismatch",
                replaceOnce(text, "scenario_count=2",
                    "scenario_count=1"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA),
            FaultCatalogRejectionCase("keyOrder",
                replaceLine(text, 1, "catalog_id=synthetic.example.v1"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA),
            FaultCatalogRejectionCase("lineTooLong",
                replaceLine(text, 10, "x".repeat(257)),
                ArKernelSyntheticFaultCatalogRejection.LINE_TOO_LONG),
            FaultCatalogRejectionCase("blankLine",
                replaceLine(text, 10, ""),
                ArKernelSyntheticFaultCatalogRejection.EMPTY_LINE),
            FaultCatalogRejectionCase("extraFinalSeparator",
                text + "\n\n",
                ArKernelSyntheticFaultCatalogRejection.EMPTY_LINE),
            FaultCatalogRejectionCase("missingEnd",
                replaceLine(text, 12, "STOP"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA),
            FaultCatalogRejectionCase("nonContiguousIndex",
                replaceOnce(text, "scenario=0|", "scenario=1|"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_ORDER),
            FaultCatalogRejectionCase("duplicatePair",
                duplicate,
                ArKernelSyntheticFaultCatalogRejection
                    .DUPLICATE_FAULT_PAIR),
            FaultCatalogRejectionCase("nonCanonicalScenarioId",
                replaceField(text, 10, 1, "custom.pair"),
                ArKernelSyntheticFaultCatalogRejection
                    .INVALID_SCENARIO_ID),
            FaultCatalogRejectionCase("invalidScenarioToken",
                replaceField(text, 10, 1, "bad!id"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_TOKEN),
            FaultCatalogRejectionCase("unknownFault",
                replaceField(text, 10, 2, "NO_SUCH_FAULT"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_FAULT),
            FaultCatalogRejectionCase("faultOrder",
                swapped,
                ArKernelSyntheticFaultCatalogRejection
                    .INVALID_FAULT_ORDER),
            FaultCatalogRejectionCase("cameraBelowBudget",
                replaceField(text, 10, 4, "19"),
                ArKernelSyntheticFaultCatalogRejection
                    .INVALID_SAMPLE_BUDGET),
            FaultCatalogRejectionCase("imuBelowBudget",
                replaceField(text, 10, 5, "49"),
                ArKernelSyntheticFaultCatalogRejection
                    .INVALID_SAMPLE_BUDGET),
            FaultCatalogRejectionCase("cameraAboveBudget",
                replaceField(text, 10, 4, "513"),
                ArKernelSyntheticFaultCatalogRejection
                    .INVALID_SAMPLE_BUDGET),
            FaultCatalogRejectionCase("imuAboveBudget",
                replaceField(text, 10, 5, "2049"),
                ArKernelSyntheticFaultCatalogRejection
                    .INVALID_SAMPLE_BUDGET),
            FaultCatalogRejectionCase("seedOverflow",
                replaceField(text, 10, 6,
                    "9223372036854775808"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_SEED),
            FaultCatalogRejectionCase("negativeSeed",
                replaceField(text, 10, 6, "-1"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_SEED),
            FaultCatalogRejectionCase("invalidDryRun",
                replaceField(text, 10, 7, "true"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_BOOLEAN),
            FaultCatalogRejectionCase("fieldCount",
                replaceLine(text, 10, "scenario=0|only-three"),
                ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA),
        )
        assertEquals(32, cases.size)
        for (case in cases) {
            val result = ArKernelSyntheticFaultCatalogBundle.parse(
                case.text)
            assertFalse("${case.name} unexpectedly accepted",
                result.accepted)
            assertNull("${case.name} leaked a partial catalog",
                result.catalog)
            assertEquals(case.name, case.reason, result.rejection)
            assertTrue("${case.name} lacked a rejection detail",
                result.detail.isNotBlank())
            assertTrue("${case.name} had a negative line number",
                result.lineNumber >= 0)
        }
    }

    @Test
    fun catalogAndScenarioConstructorsRejectUnsafeShapes() {
        val scenarios = sampleCatalog().scenarios
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSyntheticFaultCatalog(
                catalogId = "unsafe.v1",
                frameworkInjectionDisabled = false,
                scenarios = scenarios,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSyntheticFaultCatalog(
                catalogId = "unsafe.v1",
                coverageGuidedFuzzingDisabled = false,
                scenarios = scenarios,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSyntheticFaultCatalog(
                catalogId = "unsafe.v1",
                qualityGrade = "verified",
                scenarios = scenarios,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSyntheticFaultCatalog(
                catalogId = "doubled.v1",
                scenarios = listOf(scenarios[0], scenarios[0]),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSyntheticFaultCatalog(
                catalogId = "gapped.v1",
                scenarios = listOf(
                    scenarios[0],
                    scenarios[1].copy(index = 2),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSyntheticFaultScenario(
                0,
                "custom.pair",
                SyntheticCompatibilityFault.entries[0],
                SyntheticCompatibilityFault.entries[1],
                60,
                200,
                1L,
                true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSyntheticFaultScenario(
                0,
                "pair-camera_timestamp_regression-camera_frame_gaps",
                SyntheticCompatibilityFault.entries[1],
                SyntheticCompatibilityFault.entries[0],
                60,
                200,
                1L,
                true,
            )
        }
    }

    private fun sampleCatalog(): ArKernelSyntheticFaultCatalog {
        val a = SyntheticCompatibilityFault.entries[0]
        val b = SyntheticCompatibilityFault.entries[1]
        val c = SyntheticCompatibilityFault.entries[2]
        return ArKernelSyntheticFaultCatalog(
            catalogId = "synthetic.example.v1",
            revision = 1,
            scenarios = listOf(
                scenario(0, a, b),
                scenario(1, a, c),
            ),
        )
    }

    private fun scenario(
        index: Int,
        a: SyntheticCompatibilityFault,
        b: SyntheticCompatibilityFault,
    ): ArKernelSyntheticFaultScenario =
        ArKernelSyntheticFaultScenario(
            index,
            "pair-${a.name.lowercase()}-${b.name.lowercase()}",
            a,
            b,
            60,
            200,
            1L,
            true,
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
        val prefix = "scenario="
        val fields = lines[lineIndex].removePrefix(prefix)
            .split("|").toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
