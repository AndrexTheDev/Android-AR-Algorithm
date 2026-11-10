package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelDeviceFarmScenePlanTest {
    @Test
    fun scenePlanRoundTripsAndReportsBoundedFacts() {
        val plan = samplePlan()
        val text = ArKernelDeviceFarmScenePlanBundle.encode(plan)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.startsWith("ARKSCENE1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("quality_grade=unverified"))
        assertTrue(text.contains("provider_binding_disabled=true"))
        assertTrue(text.contains("rig_evidence_required=true"))

        val parsed = ArKernelDeviceFarmScenePlanBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.NONE, parsed.rejection)
        val parsedPlan = parsed.plan!!
        assertEquals(plan, parsedPlan)
        assertEquals(
            text, ArKernelDeviceFarmScenePlanBundle.encode(parsedPlan))

        val report = ArKernelDeviceFarmScenePlanBundle.summarize(parsedPlan)
        assertEquals(2, report.sceneCount)
        assertEquals(60L, report.totalMinutes)
        assertEquals(1, report.thermalRunCount)
        assertEquals(2, report.distinctKinds)
        assertEquals(2, report.distinctCapabilitySignatures)
        assertEquals("unverified", report.qualityGrade)

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelDeviceFarmScenePlanBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(plan, crlfParsed.plan)
        assertTrue(
            ArKernelDeviceFarmScenePlanBundle.parse("$text\n").accepted)
    }

    @Test
    fun parserRejectsMalformedUnsafeAndOutOfBudgetInputs() {
        val text = ArKernelDeviceFarmScenePlanBundle.encode(samplePlan())
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INPUT_TOO_LARGE,
            ArKernelDeviceFarmScenePlanBundle.parse(
                "x".repeat(8_193)).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.EMPTY_PLAN,
            ArKernelDeviceFarmScenePlanBundle.parse("").rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.EMPTY_PLAN,
            ArKernelDeviceFarmScenePlanBundle.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_HEADER,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("ARKSCENE1", "OTHER")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("version=1", "version=2")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_QUALITY,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("quality_grade=unverified",
                    "quality_grade=verified")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.UNSAFE_INVARIANT,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("provider_binding_disabled=true",
                    "provider_binding_disabled=false")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.UNSAFE_INVARIANT,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("rig_evidence_required=true",
                    "rig_evidence_required=false")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.UNSAFE_INVARIANT,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("thermal_run_evidence_required=true",
                    "thermal_run_evidence_required=false")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.UNSAFE_INVARIANT,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("independent_targets_required=true",
                    "independent_targets_required=false")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("revision=1", "revision=0")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.TOO_MANY_SCENES,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("scene_count=2", "scene_count=25")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("scene_count=2", "scene_count=3")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("END", "STOP")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.EMPTY_LINE,
            ArKernelDeviceFarmScenePlanBundle.parse(
                blankLine(text, 11)).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("scene=0|", "row=0|")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("scene=0|day.01|day|",
                    "scene=0|day.01|")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.DUPLICATE_SCENE_ID,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 1, 1, "day.01")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_ORDER,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 1, 0, "2")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 0, 3, "0")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 0, 3, "181")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 0, 3, "abc")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 0, 5, "2")).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_TOKEN,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 0, 1, "x".repeat(65))).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_TOKEN,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 0, 2, "x".repeat(65))).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_TOKEN,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 0, 4, "x".repeat(65))).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.INVALID_TOKEN,
            ArKernelDeviceFarmScenePlanBundle.parse(
                text.replace("plan_id=redmi.farm.scenes.v1",
                    "plan_id=" + "x".repeat(65))).rejection)
        assertEquals(
            ArKernelDeviceFarmSceneRejection.LINE_TOO_LONG,
            ArKernelDeviceFarmScenePlanBundle.parse(
                replaceSceneField(text, 0, 1, "x".repeat(300))).rejection)
    }

    @Test
    fun scenePlanConstructionRejectsUnsafeAndMalformedScenes() {
        val scenes = sampleScenes()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDeviceFarmScenePlan("ok.id", scenes = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDeviceFarmScenePlan(
                "ok.id",
                scenes = List(25) { index ->
                    scenes[0].copy(index = index, sceneId = "s.$index")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDeviceFarmScenePlan(
                "ok.id",
                providerBindingDisabled = false,
                scenes = scenes,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDeviceFarmScenePlan(
                "ok.id",
                scenes = listOf(scenes[0], scenes[0].copy(index = 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDeviceFarmScenePlan(
                "ok.id",
                scenes = listOf(scenes[0].copy(index = 1), scenes[1]),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDeviceFarmScene(
                0, "day.01", "day", 0, "api35.arm64", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDeviceFarmScene(
                0, "day.01", "day", 181, "api35.arm64", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDeviceFarmScene(
                0, "day.01", "x".repeat(65), 30, "api35.arm64", false)
        }
    }

    private fun samplePlan(): ArKernelDeviceFarmScenePlan =
        ArKernelDeviceFarmScenePlan(
            planId = "redmi.farm.scenes.v1",
            revision = 1,
            scenes = sampleScenes(),
        )

    private fun sampleScenes(): List<ArKernelDeviceFarmScene> = listOf(
        ArKernelDeviceFarmScene(
            0, "day.01", "day", 30, "api35.arm64", false),
        ArKernelDeviceFarmScene(
            1, "night.rain.01", "night.rain", 30,
            "api35.arm64.gyro", true),
    )

    private fun blankLine(text: String, lineIndex: Int): String {
        val lines = text.lines().toMutableList()
        lines[lineIndex] = ""
        return lines.joinToString("\n")
    }

    private fun replaceSceneField(
        text: String,
        sceneIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        val lineIndex = 10 + sceneIndex
        val prefix = "scene="
        val fields = lines[lineIndex].substringAfter(prefix).split('|')
            .toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
