package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelFieldDiagnosticsTestPlanTest {
    @Test
    fun defaultPlanRoundTripsAndCoversEveryOperator() {
        val text = ArKernelFieldDiagnosticsTestPlanBundle.defaultPlanText()
        assertTrue(text.length <= 4_096)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.startsWith("ARKFDPLAN1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("quality_grade=unverified"))
        assertTrue(text.contains("collection_opt_in").not())

        val parsed = ArKernelFieldDiagnosticsTestPlanBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.NONE, parsed.rejection)
        val plan = parsed.plan!!
        assertEquals("arkernel.ultimate.v2", plan.planId)
        assertEquals(1, plan.revision)
        assertEquals(128, plan.maxEvents)
        assertEquals(20, plan.steps.size)
        assertEquals("unverified", plan.qualityGrade)
        val ops = plan.steps.map { it.op }.toSet()
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanBundle.supportedOps(), ops)
        assertEquals(
            text,
            ArKernelFieldDiagnosticsTestPlanBundle.encode(plan),
        )

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelFieldDiagnosticsTestPlanBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(plan, crlfParsed.plan)
        assertTrue(ArKernelFieldDiagnosticsTestPlanBundle.parse("$text\n").accepted)
    }

    @Test
    fun planRejectsUnsafeAndMalformedInputs() {
        val text = ArKernelFieldDiagnosticsTestPlanBundle.defaultPlanText()
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INPUT_TOO_LARGE,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                "x".repeat(4_097)).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.EMPTY_PLAN,
            ArKernelFieldDiagnosticsTestPlanBundle.parse("").rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.EMPTY_PLAN,
            ArKernelFieldDiagnosticsTestPlanBundle.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_HEADER,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                "WRONG\n" + text.lines().drop(1).joinToString("\n")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsTestPlanBundle.parse("$text\nextra=1").rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsTestPlanBundle.parse("$text\n\n").rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "version=", "ver=1")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_QUALITY,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "quality_grade=", "quality_grade=assessed")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.UNSAFE_INVARIANT,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "terminal_text_only=",
                    "terminal_text_only=false")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "revision=", "revision=0")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "max_events=", "max_events=1")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.TOO_MANY_STEPS,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "step_count=", "step_count=25")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "step_count=", "step_count=21")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_TOKEN,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "plan_id=", "plan_id=bad/id")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.EMPTY_LINE,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceStepLine(text, 0, "")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceStepLine(text, 0, "step=CAPABILITY_CATALOG|0|0|0| base")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_STEP,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceStepLine(text, 0, "step=CAPABILITY_CATALOG|0|0|0")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.UNKNOWN_OP,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceStepLine(text, 0, "step=FUTURE_OP|0|0|0|base")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceStepLine(text, 0, "step=CAPABILITY_CATALOG|-1|0|0|base")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceStepLine(text, 0, "step=CAPABILITY_CATALOG|120001|0|0|base")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_TOKEN,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceStepLine(text, 0, "step=CAPABILITY_CATALOG|0|0|0|bad/id")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsTestPlanBundle.parse(
                replaceLine(text, "END", "STOP")).rejection)
    }

    @Test
    fun planCannotClaimAssessedQualityOrUnsupportedOps() {
        assertFalse(SUPPORTED_TEST_PLAN_OPS.contains("FUTURE_OP"))
        val step = ArKernelFieldDiagnosticsTestPlanStep(
            "CAPABILITY_CATALOG", 0L, 0, 0, "base")
        val plan = ArKernelFieldDiagnosticsTestPlan(
            "custom.plan", steps = listOf(step))
        val text = ArKernelFieldDiagnosticsTestPlanBundle.encode(plan)
        assertTrue(ArKernelFieldDiagnosticsTestPlanBundle.parse(text).accepted)
        assertEquals("unverified", plan.qualityGrade)
        assertTrue(text.lines().all { !it.contains(' ') })
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsTestPlanStep(
                "FUTURE_OP", 0L, 0, 0, "base")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsTestPlan(
                "too.many.steps", steps = List(25) { step })
        }
    }

    @Test
    fun hostCanLowerSlowLightSensorThresholdAsText() {
        val text = ArKernelFieldDiagnosticsTestPlanBundle.defaultPlanText()
        val tuned = replaceStepLine(
            text, 13, "step=SENSOR_LIVE|5000|2000|2|light")
        val parsed = ArKernelFieldDiagnosticsTestPlanBundle.parse(tuned)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelFieldDiagnosticsTestPlanRejection.NONE, parsed.rejection)
        val plan = parsed.plan!!
        val light = plan.steps.single {
            it.op == "SENSOR_LIVE" && it.label == "light"
        }
        assertEquals(2, light.expectedMin)
        assertEquals(tuned, ArKernelFieldDiagnosticsTestPlanBundle.encode(plan))
    }

    private fun replaceStepLine(
        text: String,
        index: Int,
        line: String,
    ): String {
        val lines = text.lines().toMutableList()
        val stepStart = 12
        lines[stepStart + index] = line
        return lines.joinToString("\n")
    }

    private fun replaceLine(
        text: String,
        prefix: String,
        line: String,
    ): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith(prefix) }
        require(index >= 0) { "Unknown test plan prefix" }
        lines[index] = line
        return lines.joinToString("\n")
    }
}
