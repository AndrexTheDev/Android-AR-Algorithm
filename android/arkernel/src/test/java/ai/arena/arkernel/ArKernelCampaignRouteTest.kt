package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing table is checked for the failure it exists to prevent: an
 * operator completing every campaign scene without ever being told which
 * measurement to run, so a "confirmed" question rests on nothing.
 */
class ArKernelCampaignRouteTest {

    companion object {
        private val questions get() = ArKernelCampaignPlanAdapter.canonicalQuestions()
    }

    @Test
    fun everyCanonicalQuestionHasAWayToAnswerIt() {
        // The whole point. A question with no evidence steps is a question the
        // operator can only answer by guessing.
        for (question in questions) {
            val steps = ArKernelCampaignRoute.evidenceFor(question)
            assertTrue("${question.wireId} has no measurement route",
                steps.isNotEmpty())
        }
    }

    @Test
    fun theCanonicalQuestionsAreNotEmpty() {
        assertTrue(questions.isNotEmpty())
    }

    @Test
    fun theTimeOffsetIsMeasuredBeforeTheRotation() {
        // Physical, not stylistic: the rotation is fitted against a gyroscope
        // window centred on each frame, and an uncorrected t_d displaces that
        // window, attenuates the fit and biases the focal length low.
        val order = ArKernelCampaignRoute.prerequisites()
        val offset = order.indexOf(ArKernelCampaignStep.CAMERA_IMU_OFFSET)
        val rotation = order.indexOf(ArKernelCampaignStep.CAMERA_IMU_ROTATION)
        assertTrue("time offset must precede rotation", offset < rotation)
    }

    @Test
    fun theDeviceIsCheckedBeforeItsSensors() {
        // A sixty-second stillness run on a throttling phone measures the
        // throttling, and nothing in the file says so afterwards.
        val order = ArKernelCampaignRoute.prerequisites()
        val device = order.indexOf(ArKernelCampaignStep.DEVICE_RUNTIME)
        val imu = order.indexOf(ArKernelCampaignStep.IMU_SERIES)
        val camera = order.indexOf(ArKernelCampaignStep.CAMERA_TIMING)
        assertTrue(device < imu)
        assertTrue(device < camera)
    }

    @Test
    fun theRotationComesAfterBothSensorsItRelates() {
        val order = ArKernelCampaignRoute.prerequisites()
        val rotation = order.indexOf(ArKernelCampaignStep.CAMERA_IMU_ROTATION)
        assertTrue(rotation > order.indexOf(ArKernelCampaignStep.IMU_SERIES))
        assertTrue(rotation > order.indexOf(ArKernelCampaignStep.CAMERA_TIMING))
    }

    @Test
    fun redactionComesBeforeSignature() {
        // Redaction writes new files, and only those are uploaded. Signing
        // first would cover bytes nobody uploads.
        val closing = ArKernelCampaignRoute.closingSteps()
        val redaction = closing.indexOf(ArKernelCampaignStep.REDACTION)
        val signature = closing.indexOf(ArKernelCampaignStep.SIGNATURE)
        assertTrue("redact before sign", redaction < signature)
    }

    @Test
    fun theRunOrderHasNoDuplicates() {
        val order = ArKernelCampaignRoute.runOrder(questions)
        assertEquals("a step must not be run twice",
            order.size, order.toSet().size)
    }

    @Test
    fun theRunOrderContainsEveryPrerequisiteAndClosingStep() {
        val order = ArKernelCampaignRoute.runOrder(questions)
        for (step in ArKernelCampaignRoute.prerequisites()) {
            assertTrue("missing prerequisite ${step.record}", step in order)
        }
        for (step in ArKernelCampaignRoute.closingSteps()) {
            assertTrue("missing closing step ${step.record}", step in order)
        }
    }

    @Test
    fun everyStepNamesADistinctButtonAndRecord() {
        val buttons = ArKernelCampaignStep.entries.map { it.button }
        val records = ArKernelCampaignStep.entries.map { it.record }
        assertEquals(buttons.size, buttons.toSet().size)
        assertEquals(records.size, records.toSet().size)
        for (step in ArKernelCampaignStep.entries) {
            assertTrue("${step.record} is not an ARK record",
                step.record.startsWith("ARK"))
            assertTrue("button must be positive", step.button > 0)
        }
    }

    @Test
    fun everyStepHasACadenceAndARationale() {
        for (step in ArKernelCampaignStep.entries) {
            assertTrue(step.establishes.isNotBlank())
            assertTrue(step.cadence.token.isNotBlank())
        }
    }

    @Test
    fun instructionsAreNonEmptyAndNameButtons() {
        for (question in questions) {
            val text = ArKernelCampaignRoute.instructionFor(question)
            assertTrue(text.isNotBlank())
            assertTrue("${question.wireId}: no button named in \"$text\"",
                text.contains("Knopf"))
        }
    }

    @Test
    fun anUnknownQuestionYieldsNoEvidenceRatherThanAGuess() {
        val unknown = ArKernelCampaignQuestion(
            wireId = "Q_NOT_A_QUESTION",
            kind = ArKernelCampaignSceneKind.STILLNESS,
            requiredRepeats = 1)
        assertTrue(ArKernelCampaignRoute.evidenceFor(unknown).isEmpty())
        assertTrue(ArKernelCampaignRoute.directEvidenceFor(unknown)
            .isEmpty())
        // And it must not sneak into the run order either.
        val withUnknown = ArKernelCampaignRoute.runOrder(listOf(unknown))
        val without = ArKernelCampaignRoute.runOrder(emptyList())
        assertEquals(without, withUnknown)
        assertTrue(ArKernelCampaignRoute.instructionFor(unknown)
            .contains("kein Messweg"))
    }

    @Test
    fun theScaleQuestionsNeedTheLadderBeforeTheValidation() {
        // The ladder says whether one factor suffices; the validation says
        // whether the scale is right at all. Asking the second first yields a
        // number with no interpretation.
        for (id in listOf("Q_SCALE_SHORT", "Q_SCALE_LONG")) {
            val question = questions.first { it.wireId == id }
            val steps = ArKernelCampaignRoute.evidenceFor(question)
            assertTrue(
                steps.indexOf(ArKernelCampaignStep.SCALE_LADDER) <
                    steps.indexOf(ArKernelCampaignStep.METRIC_VALIDATION))
        }
    }

    @Test
    fun cadenceTokensRoundTrip() {
        for (cadence in ArKernelStepCadence.entries) {
            assertEquals(cadence,
                ArKernelStepCadence.ofToken(cadence.token))
        }
        assertEquals(null, ArKernelStepCadence.ofToken("nonsense"))
    }

    @Test
    fun theRouteDoesNotGradeAnything() {
        // Answerable is not answered correctly. The table must not carry a
        // notion of sufficiency that could be read as quality.
        for (question in questions) {
            val steps = ArKernelCampaignRoute.evidenceFor(question)
            for (step in steps) {
                assertFalse(step.establishes.contains("Guete") ||
                    step.establishes.contains("Qualitaet"))
            }
        }
    }
}
