package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelRegressionTrendBundleTest {

    @Test
    fun canonicalLedgerRoundTripsAndReportsUnverifiedTrend() {
        val ledger = ledger(
            runs = listOf(
                run(0, "build-100", 1.000000, 0.010000),
                run(1, "build-101", 0.900000, 0.010000),
                run(2, "build-102", 0.800000, 0.010000),
            ),
        )
        val text = ArKernelRegressionTrendBundle.encode(ledger)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.startsWith("ARKREGR1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("quality_grade=unverified"))
        assertFalse(text.contains("quality_grade=verified"))

        val parsed = ArKernelRegressionTrendBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelRegressionTrendRejection.NONE, parsed.rejection)
        assertEquals(ledger, parsed.ledger)
        assertEquals(
            text,
            ArKernelRegressionTrendBundle.encode(parsed.ledger!!),
        )

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelRegressionTrendBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(ledger, crlfParsed.ledger)
        assertTrue(ArKernelRegressionTrendBundle.parse("$text\n").accepted)

        val report = ArKernelRegressionTrendBundle.evaluate(parsed.ledger)
        assertEquals("ate.rmse", report.metric)
        assertEquals(3, report.sampleCount)
        assertEquals(1.000000, report.firstValue, 0.0)
        assertEquals(0.800000, report.lastValue, 0.0)
        assertEquals(-0.200000, report.delta, 0.0)
        assertEquals(0.0, report.degradation, 0.0)
        assertEquals(0.200000, report.improvement, 0.0)
        assertTrue(report.budgetPass)
        assertEquals(ArKernelRegressionTrend.IMPROVING, report.trend)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun evaluatorClassifiesDirectionBudgetAndNoiseWithoutQualityUpgrade() {
        val lowerDegrading = ledger(
            runs = listOf(
                run(0, "a", 1.000000, 0.010000),
                run(1, "b", 1.100000, 0.010000),
                run(2, "c", 1.200000, 0.010000),
            ),
        )
        val degrading = ArKernelRegressionTrendBundle.evaluate(lowerDegrading)
        assertEquals(0.200000, degrading.degradation, 0.0)
        assertTrue(degrading.budgetPass)
        assertEquals(ArKernelRegressionTrend.DEGRADING, degrading.trend)

        val violating = ledger(
            budgetAbsolute = 0.050000,
            budgetRelative = 0.050000,
            runs = listOf(
                run(0, "a", 1.000000, 0.010000),
                run(1, "b", 1.500000, 0.010000),
            ),
        )
        val violation = ArKernelRegressionTrendBundle.evaluate(violating)
        assertFalse(violation.budgetPass)
        assertEquals(
            ArKernelRegressionTrend.BUDGET_VIOLATION, violation.trend)

        val higherImproving = ledger(
            direction = ArKernelRegressionTrendDirection.HIGHER_IS_BETTER,
            runs = listOf(
                run(0, "a", 1.000000, 0.010000),
                run(1, "b", 1.100000, 0.010000),
                run(2, "c", 1.200000, 0.010000),
            ),
        )
        val improving = ArKernelRegressionTrendBundle.evaluate(higherImproving)
        assertEquals(0.200000, improving.improvement, 0.0)
        assertEquals(0.0, improving.degradation, 0.0)
        assertEquals(ArKernelRegressionTrend.IMPROVING, improving.trend)

        val noisy = ledger(
            runs = listOf(
                run(0, "a", 1.000000, 0.500000),
                run(1, "b", 1.100000, 0.500000),
            ),
        )
        val stable = ArKernelRegressionTrendBundle.evaluate(noisy)
        assertEquals(0.100000, stable.degradation, 0.0)
        assertTrue(stable.budgetPass)
        assertEquals(ArKernelRegressionTrend.STABLE, stable.trend)
        assertEquals("unverified", stable.qualityGrade)
    }

    @Test
    fun parserRejectsMalformedUnsafeAndOutOfBudgetInputs() {
        val text = ArKernelRegressionTrendBundle.encode(ledger())
        assertEquals(
            ArKernelRegressionTrendRejection.INPUT_TOO_LARGE,
            ArKernelRegressionTrendBundle.parse("x".repeat(8_193)).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.EMPTY_LEDGER,
            ArKernelRegressionTrendBundle.parse("").rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.EMPTY_LEDGER,
            ArKernelRegressionTrendBundle.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_HEADER,
            ArKernelRegressionTrendBundle.parse(
                "WRONG\n" + text.lines().drop(1).joinToString("\n")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_SCHEMA,
            ArKernelRegressionTrendBundle.parse("$text\nextra=1").rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_SCHEMA,
            ArKernelRegressionTrendBundle.parse("$text\n\n").rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_SCHEMA,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "version=", "ver=1")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_QUALITY,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "quality_grade=",
                    "quality_grade=assessed")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_VALUE,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "direction=", "direction=SIDEWAYS")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_VALUE,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "budget_abs=", "budget_abs=-1.000000")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_VALUE,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "min_samples=", "min_samples=1")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.TOO_MANY_RUNS,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "run_count=", "run_count=65")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INSUFFICIENT_SAMPLES,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "min_samples=", "min_samples=64")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_TOKEN,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "metric=", "metric=bad/id")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_SCHEMA,
            ArKernelRegressionTrendBundle.parse(
                replaceLine(text, "END", "STOP")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.EMPTY_LINE,
            ArKernelRegressionTrendBundle.parse(
                replaceRunLine(text, 0, "")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_SCHEMA,
            ArKernelRegressionTrendBundle.parse(
                replaceRunLine(text, 0, "run=0| a|1.000000|0.010000")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_OBSERVATION,
            ArKernelRegressionTrendBundle.parse(
                replaceRunLine(text, 0, "run=0|a|1.000000")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_ORDER,
            ArKernelRegressionTrendBundle.parse(
                replaceRunLine(text, 1, "run=0|b|0.900000|0.010000")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.DUPLICATE_RUN_ID,
            ArKernelRegressionTrendBundle.parse(
                replaceRunLine(text, 1, "run=1|a|0.900000|0.010000")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_OBSERVATION,
            ArKernelRegressionTrendBundle.parse(
                replaceRunLine(text, 0, "run=0|a|NaN|0.010000")).rejection)
        assertEquals(
            ArKernelRegressionTrendRejection.INVALID_OBSERVATION,
            ArKernelRegressionTrendBundle.parse(
                replaceRunLine(text, 0,
                    "run=0|a|1.000000|-0.010000")).rejection)
    }

    @Test
    fun constructorsRejectUnsafeConfigurations() {
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRegressionTrendLedger(
                "ate.rmse",
                ArKernelRegressionTrendDirection.LOWER_IS_BETTER,
                1.0,
                0.5,
                0.5,
                2,
                listOf(
                    ArKernelRegressionTrendRun(0, "a", 1.0, 0.01),
                    ArKernelRegressionTrendRun(1, "a", 1.1, 0.01),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRegressionTrendLedger(
                "ate.rmse",
                ArKernelRegressionTrendDirection.LOWER_IS_BETTER,
                1.0,
                0.5,
                0.5,
                3,
                listOf(
                    ArKernelRegressionTrendRun(0, "a", 1.0, 0.01),
                    ArKernelRegressionTrendRun(1, "b", 1.1, 0.01),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRegressionTrendReport(
                "ate.rmse",
                ArKernelRegressionTrendDirection.LOWER_IS_BETTER,
                2,
                1.0,
                1.1,
                0.1,
                0.1,
                0.0,
                0.1,
                0.5,
                0.5,
                0.01,
                true,
                ArKernelRegressionTrend.STABLE,
                "verified",
            )
        }
    }

    private fun ledger(
        metric: String = "ate.rmse",
        direction: ArKernelRegressionTrendDirection =
            ArKernelRegressionTrendDirection.LOWER_IS_BETTER,
        baseline: Double = 1.000000,
        budgetAbsolute: Double = 0.500000,
        budgetRelative: Double = 0.500000,
        minSamples: Int = 2,
        runs: List<ArKernelRegressionTrendRun> = listOf(
            run(0, "build-100", 1.000000, 0.010000),
            run(1, "build-101", 0.900000, 0.010000),
            run(2, "build-102", 0.800000, 0.010000),
        ),
    ): ArKernelRegressionTrendLedger = ArKernelRegressionTrendLedger(
        metric,
        direction,
        baseline,
        budgetAbsolute,
        budgetRelative,
        minSamples,
        runs,
    )

    private fun run(
        index: Int,
        runId: String,
        value: Double,
        uncertainty: Double,
    ): ArKernelRegressionTrendRun = ArKernelRegressionTrendRun(
        index, runId, value, uncertainty)

    private fun replaceRunLine(
        text: String,
        index: Int,
        line: String,
    ): String {
        val lines = text.lines().toMutableList()
        lines[10 + index] = line
        return lines.joinToString("\n")
    }

    private fun replaceLine(
        text: String,
        prefix: String,
        line: String,
    ): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith(prefix) }
        require(index >= 0) { "Unknown regression ledger prefix" }
        lines[index] = line
        return lines.joinToString("\n")
    }
}
