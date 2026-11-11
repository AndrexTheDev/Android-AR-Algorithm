package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class CampaignRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelCampaignRejection,
)

class ArKernelCampaignPlanTest {
    @Test
    fun samplePlanRoundTripsAndReportsOnlyStructuralFacts() {
        val bundle = sampleBundle()
        val text = ArKernelCampaignPlanAdapter.encode(bundle)
        val result = ArKernelCampaignPlanAdapter.parse(text)
        assertTrue(result.accepted)
        assertEquals(ArKernelCampaignRejection.NONE, result.rejection)
        assertEquals(bundle, result.bundle)
        assertEquals(text,
            ArKernelCampaignPlanAdapter.encode(result.bundle!!))
        val report =
            ArKernelCampaignPlanAdapter.summarize(result.bundle!!)
        assertEquals(9, report.sceneCount)
        assertEquals(9, report.distinctSceneTargets)
        assertEquals(9, report.plannedSceneCount)
        assertEquals(10, report.questionCount)
        assertEquals(10, report.pendingQuestionCount)
        assertEquals(4, report.maxSceneRepeats)
        assertEquals(0, report.evidenceCompleteQuestionCount)
        assertEquals(0, report.unsupportedQuestionCount)
        assertFalse(report.allQuestionsAddressed)
        assertFalse(report.campaignEvidenceComplete)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.bundle!!.qualityGrade)
    }

    @Test
    fun evidenceCompletenessIsNotAnAnswerOrQualityGrade() {
        val bundle = sampleBundle()
        val complete = bundle.copy(
            scenes = bundle.scenes.map {
                it.copy(status =
                    ArKernelCampaignSceneStatus.HOST_COMPLETED)
            },
            questions = bundle.questions.map {
                it.copy(status =
                    ArKernelCampaignQuestionStatus.HOST_CONFIRMED)
            },
        )
        val report = ArKernelCampaignPlanAdapter.summarize(complete)
        assertTrue(report.allQuestionsAddressed)
        assertEquals(10, report.evidenceCompleteQuestionCount)
        assertEquals(0, report.unsupportedQuestionCount)
        assertTrue(report.campaignEvidenceComplete)
        assertEquals("unverified", report.qualityGrade)
        val weak = bundle.copy(
            questions = bundle.questions.map {
                if (it.index == 0) {
                    it.copy(status =
                        ArKernelCampaignQuestionStatus.HOST_CONFIRMED)
                } else {
                    it
                }
            },
        )
        assertFalse(
            ArKernelCampaignPlanAdapter.summarize(weak)
                .campaignEvidenceComplete)
    }

    @Test
    fun portableProtocolKeepsCanonicalQuestionsAndShortensWeakDevices() {
        val expectedScenes = mapOf(
            ArKernelAdaptiveDeviceClass.FULL to 9,
            ArKernelAdaptiveDeviceClass.LIMITED to 8,
            ArKernelAdaptiveDeviceClass.LEGACY to 6,
            ArKernelAdaptiveDeviceClass.UNKNOWN to 5,
        )
        val expectedUnsupported = mapOf(
            ArKernelAdaptiveDeviceClass.FULL to 0,
            ArKernelAdaptiveDeviceClass.LIMITED to 1,
            ArKernelAdaptiveDeviceClass.LEGACY to 3,
            ArKernelAdaptiveDeviceClass.UNKNOWN to 4,
        )
        for (deviceClass in ArKernelAdaptiveDeviceClass.entries) {
            val bundle = ArKernelCampaignPlanAdapter.canonicalPlan(
                "campaign.host.v1", "device.host.v1", deviceClass)
            val text = ArKernelCampaignPlanAdapter.encode(bundle)
            assertTrue(ArKernelCampaignPlanAdapter.parse(text).accepted)
            val report =
                ArKernelCampaignPlanAdapter.summarize(bundle)
            assertEquals(expectedScenes.getValue(deviceClass),
                report.sceneCount)
            assertEquals(10, report.questionCount)
            assertEquals(expectedUnsupported.getValue(deviceClass),
                report.unsupportedQuestionCount)
            assertFalse(report.campaignEvidenceComplete)
            assertEquals("unverified", report.qualityGrade)
            assertEquals(bundle,
                ArKernelCampaignPlanAdapter.canonicalPlan(
                    "campaign.host.v1", "device.host.v1",
                    deviceClass))
        }
        assertEquals(10,
            ArKernelCampaignPlanAdapter.canonicalQuestions().size)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val bundle = sampleBundle()
        val text = ArKernelCampaignPlanAdapter.encode(bundle)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result = ArKernelCampaignPlanAdapter.parse(variant)
            assertTrue("variant rejected: ${result.detail}",
                result.accepted)
            assertEquals(bundle, result.bundle)
        }
        assertEquals(ArKernelCampaignRejection.EMPTY_LINE,
            ArKernelCampaignPlanAdapter.parse(text + "\n\n")
                .rejection)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = ArKernelCampaignPlanAdapter.encode(sampleBundle())
        val cases = listOf(
            CampaignRejectionCase(
                "inputTooLarge",
                "x".repeat(16_385),
                ArKernelCampaignRejection.INPUT_TOO_LARGE,
            ),
            CampaignRejectionCase(
                "empty",
                "",
                ArKernelCampaignRejection.EMPTY_PLAN,
            ),
            CampaignRejectionCase(
                "badHeader",
                replaceLine(
                        text,
                        0,
                        "OTHER1",
                    ),
                ArKernelCampaignRejection.INVALID_HEADER,
            ),
            CampaignRejectionCase(
                "headerWithSpace",
                replaceLine(
                        text,
                        0,
                        "ARKCAMPAIGN1 x",
                    ),
                ArKernelCampaignRejection.INVALID_HEADER,
            ),
            CampaignRejectionCase(
                "badVersion",
                replaceOnce(
                        text,
                        "version=1",
                        "version=2",
                    ),
                ArKernelCampaignRejection.INVALID_VALUE,
            ),
            CampaignRejectionCase(
                "badProtocol",
                replaceOnce(
                        text,
                        "protocol_id=arkadapt1",
                        "protocol_id=other",
                    ),
                ArKernelCampaignRejection.INVALID_PROTOCOL,
            ),
            CampaignRejectionCase(
                "assessedQualityInput",
                replaceOnce(
                        text,
                        "quality_grade=unverified",
                        "quality_grade=verified",
                    ),
                ArKernelCampaignRejection.INVALID_QUALITY,
            ),
            CampaignRejectionCase(
                "invalidBoolean",
                replaceOnce(
                        text,
                        "images_disabled=true",
                        "images_disabled=maybe",
                    ),
                ArKernelCampaignRejection.INVALID_BOOLEAN,
            ),
            CampaignRejectionCase(
                "scaleBarNotRequired",
                replaceOnce(
                        text,
                        "scale_bar_required=true",
                        "scale_bar_required=false",
                    ),
                ArKernelCampaignRejection.UNSAFE_INVARIANT,
            ),
            CampaignRejectionCase(
                "independentTargetNotRequired",
                replaceOnce(
                        text,
                        "independent_target_required=true",
                        "independent_target_required=false",
                    ),
                ArKernelCampaignRejection.UNSAFE_INVARIANT,
            ),
            CampaignRejectionCase(
                "perDeviceCalibrationFalse",
                replaceOnce(
                        text,
                        "per_device_calibration=true",
                        "per_device_calibration=false",
                    ),
                ArKernelCampaignRejection.UNSAFE_INVARIANT,
            ),
            CampaignRejectionCase(
                "universalCalibrationEnabled",
                replaceOnce(
                        text,
                        "universal_calibration_disabled=true",
                        "universal_calibration_disabled=false",
                    ),
                ArKernelCampaignRejection.UNSAFE_INVARIANT,
            ),
            CampaignRejectionCase(
                "imagesEnabled",
                replaceOnce(
                        text,
                        "images_disabled=true",
                        "images_disabled=false",
                    ),
                ArKernelCampaignRejection.UNSAFE_INVARIANT,
            ),
            CampaignRejectionCase(
                "automaticCalibrationEnabled",
                replaceOnce(
                        text,
                        "automatic_calibration_disabled=true",
                        "automatic_calibration_disabled=false",
                    ),
                ArKernelCampaignRejection.UNSAFE_INVARIANT,
            ),
            CampaignRejectionCase(
                "automaticUploadEnabled",
                replaceOnce(
                        text,
                        "automatic_upload_disabled=true",
                        "automatic_upload_disabled=false",
                    ),
                ArKernelCampaignRejection.UNSAFE_INVARIANT,
            ),
            CampaignRejectionCase(
                "heldOutNotRequired",
                replaceOnce(
                        text,
                        "held_out_required=true",
                        "held_out_required=false",
                    ),
                ArKernelCampaignRejection.UNSAFE_INVARIANT,
            ),
            CampaignRejectionCase(
                "invalidCampaignToken",
                replaceOnce(
                        text,
                        "campaign_id=campaign.host.v1",
                        "campaign_id=bad!id",
                    ),
                ArKernelCampaignRejection.INVALID_TOKEN,
            ),
            CampaignRejectionCase(
                "invalidDeviceToken",
                replaceOnce(
                        text,
                        "device_token=device.host.v1",
                        "device_token=bad!id",
                    ),
                ArKernelCampaignRejection.INVALID_TOKEN,
            ),
            CampaignRejectionCase(
                "invalidDeviceClass",
                replaceOnce(
                        text,
                        "device_class=full",
                        "device_class=premium",
                    ),
                ArKernelCampaignRejection.INVALID_DEVICE_CLASS,
            ),
            CampaignRejectionCase(
                "zeroScenes",
                replaceOnce(
                        text,
                        "scene_count=9",
                        "scene_count=0",
                    ),
                ArKernelCampaignRejection.INVALID_COUNT,
            ),
            CampaignRejectionCase(
                "tooManyScenes",
                replaceOnce(
                        text,
                        "scene_count=9",
                        "scene_count=17",
                    ),
                ArKernelCampaignRejection.INVALID_COUNT,
            ),
            CampaignRejectionCase(
                "nonCanonicalQuestionCount",
                replaceOnce(
                        text,
                        "question_count=10",
                        "question_count=9",
                    ),
                ArKernelCampaignRejection.INVALID_COUNT,
            ),
            CampaignRejectionCase(
                "invalidRepeatBudget",
                replaceOnce(
                        text,
                        "repeat_budget=16",
                        "repeat_budget=x",
                    ),
                ArKernelCampaignRejection.INVALID_REPEAT,
            ),
            CampaignRejectionCase(
                "zeroRepeatBudget",
                replaceOnce(
                        text,
                        "repeat_budget=16",
                        "repeat_budget=0",
                    ),
                ArKernelCampaignRejection.INVALID_REPEAT,
            ),
            CampaignRejectionCase(
                "lineCountMismatch",
                replaceOnce(
                        text,
                        "scene_count=9",
                        "scene_count=8",
                    ),
                ArKernelCampaignRejection.INVALID_SCHEMA,
            ),
            CampaignRejectionCase(
                "keyOrder",
                replaceLine(
                        text,
                        1,
                        "campaign_id=campaign.host.v1",
                    ),
                ArKernelCampaignRejection.INVALID_SCHEMA,
            ),
            CampaignRejectionCase(
                "spaceInMetadata",
                replaceOnce(
                        text,
                        "version=1",
                        "version=1 x",
                    ),
                ArKernelCampaignRejection.INVALID_SCHEMA,
            ),
            CampaignRejectionCase(
                "lineTooLong",
                replaceLine(
                        text,
                        18,
                        "x".repeat(257),
                    ),
                ArKernelCampaignRejection.LINE_TOO_LONG,
            ),
            CampaignRejectionCase(
                "blankLine",
                replaceLine(
                        text,
                        18,
                        "",
                    ),
                ArKernelCampaignRejection.EMPTY_LINE,
            ),
            CampaignRejectionCase(
                "extraFinalSeparator",
                text + "\n\n",
                ArKernelCampaignRejection.EMPTY_LINE,
            ),
            CampaignRejectionCase(
                "missingEnd",
                replaceLine(
                        text,
                        37,
                        "STOP",
                    ),
                ArKernelCampaignRejection.INVALID_SCHEMA,
            ),
            CampaignRejectionCase(
                "wrongScenePrefix",
                replaceLine(
                        text,
                        18,
                        "scenex=0|only",
                    ),
                ArKernelCampaignRejection.INVALID_SCHEMA,
            ),
            CampaignRejectionCase(
                "wrongQuestionPrefix",
                replaceLine(
                        text,
                        27,
                        "questionx=0|only",
                    ),
                ArKernelCampaignRejection.INVALID_SCHEMA,
            ),
            CampaignRejectionCase(
                "sceneFieldCount",
                replaceLine(
                        text,
                        18,
                        "scene=0|only",
                    ),
                ArKernelCampaignRejection.INVALID_ROW_FIELDS,
            ),
            CampaignRejectionCase(
                "questionFieldCount",
                replaceLine(
                        text,
                        27,
                        "question=0|only",
                    ),
                ArKernelCampaignRejection.INVALID_ROW_FIELDS,
            ),
            CampaignRejectionCase(
                "invalidSceneIndex",
                rowField(
                        text,
                        18,
                        0,
                        "x",
                        "scene=",
                    ),
                ArKernelCampaignRejection.INVALID_VALUE,
            ),
            CampaignRejectionCase(
                "sceneOrder",
                replaceOnce(
                        text,
                        "scene=0|",
                        "scene=1|",
                    ),
                ArKernelCampaignRejection.INVALID_ORDER,
            ),
            CampaignRejectionCase(
                "invalidSceneKind",
                rowField(
                        text,
                        18,
                        1,
                        "auto_calibrate",
                        "scene=",
                    ),
                ArKernelCampaignRejection.INVALID_SCENE_KIND,
            ),
            CampaignRejectionCase(
                "invalidSceneStatus",
                rowField(
                        text,
                        18,
                        4,
                        "verified",
                        "scene=",
                    ),
                ArKernelCampaignRejection.INVALID_SCENE_STATUS,
            ),
            CampaignRejectionCase(
                "invalidSceneRepeats",
                rowField(
                        text,
                        18,
                        3,
                        "0",
                        "scene=",
                    ),
                ArKernelCampaignRejection.INVALID_REPEAT,
            ),
            CampaignRejectionCase(
                "sceneRepeatsOverBudget",
                rowField(
                        text,
                        18,
                        3,
                        "17",
                        "scene=",
                    ),
                ArKernelCampaignRejection.INVALID_REPEAT,
            ),
            CampaignRejectionCase(
                "invalidSceneTarget",
                rowField(
                        text,
                        18,
                        2,
                        "bad!id",
                        "scene=",
                    ),
                ArKernelCampaignRejection.INVALID_TOKEN,
            ),
            CampaignRejectionCase(
                "duplicateSceneTarget",
                rowField(
                        text,
                        19,
                        2,
                        "scene.indoor_scale.r4",
                        "scene=",
                    ),
                ArKernelCampaignRejection.DUPLICATE_TARGET,
            ),
            CampaignRejectionCase(
                "invalidQuestionIndex",
                rowField(
                        text,
                        27,
                        0,
                        "x",
                        "question=",
                    ),
                ArKernelCampaignRejection.INVALID_VALUE,
            ),
            CampaignRejectionCase(
                "questionOrder",
                replaceOnce(
                        text,
                        "question=0|",
                        "question=1|",
                    ),
                ArKernelCampaignRejection.INVALID_ORDER,
            ),
            CampaignRejectionCase(
                "invalidQuestionId",
                rowField(
                        text,
                        27,
                        1,
                        "Q_UNKNOWN",
                        "question=",
                    ),
                ArKernelCampaignRejection.INVALID_QUESTION,
            ),
            CampaignRejectionCase(
                "invalidQuestionKind",
                rowField(
                        text,
                        27,
                        2,
                        "stillness",
                        "question=",
                    ),
                ArKernelCampaignRejection.INVALID_QUESTION,
            ),
            CampaignRejectionCase(
                "invalidQuestionDepth",
                rowField(
                        text,
                        27,
                        3,
                        "9",
                        "question=",
                    ),
                ArKernelCampaignRejection.INVALID_QUESTION,
            ),
            CampaignRejectionCase(
                "invalidQuestionStatus",
                rowField(
                        text,
                        27,
                        4,
                        "verified",
                        "question=",
                    ),
                ArKernelCampaignRejection.INVALID_QUESTION_STATUS,
            ),
        )
        assertEquals(49, cases.size)
        for (case in cases) {
            val result = ArKernelCampaignPlanAdapter.parse(case.text)
            assertFalse("${case.name} unexpectedly accepted",
                result.accepted)
            assertNull("${case.name} leaked a partial bundle",
                result.bundle)
            assertEquals(case.name, case.reason, result.rejection)
            assertTrue("${case.name} lacked a rejection detail",
                result.detail.isNotBlank())
            assertTrue("${case.name} had a negative line number",
                result.lineNumber >= 0)
        }
    }

    @Test
    fun constructorsRejectUnsafeShapes() {
        val bundle = sampleBundle()
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(perDeviceCalibration = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(universalCalibrationDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(independentTargetRequired = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(scenes = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(scenes = listOf(
                bundle.scenes[0],
                bundle.scenes[1].copy(targetToken =
                    bundle.scenes[0].targetToken),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(questions = bundle.questions.take(9))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCampaignScene(
                0, ArKernelCampaignSceneKind.INDOOR_SCALE,
                "scene.ok", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCampaignScene(
                0, ArKernelCampaignSceneKind.INDOOR_SCALE,
                "bad!id", 2)
        }
    }

    private fun sampleBundle(): ArKernelCampaignBundle =
        ArKernelCampaignPlanAdapter.canonicalPlan(
            "campaign.host.v1", "device.host.v1",
            ArKernelAdaptiveDeviceClass.FULL)

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

    private fun rowField(
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
