package ai.arena.arkernel

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelObjectCaptureGuidancePromptsTest {
    @Test
    fun fallsBackToEnglishForUnsupportedLocales() {
        assertEquals(ArKernelPromptLocale.DE, ArKernelPromptLocale.from(Locale.GERMAN))
        assertEquals(ArKernelPromptLocale.DE, ArKernelPromptLocale.from(Locale.GERMANY))
        assertEquals(ArKernelPromptLocale.EN, ArKernelPromptLocale.from(Locale.ENGLISH))
        assertEquals(ArKernelPromptLocale.EN, ArKernelPromptLocale.from(Locale.FRENCH))
        val prompt = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.ORBIT, ObjectCaptureInstruction.ORBIT_TO_NEXT_VIEW,
                coverageRatio = 0.5), Locale.FRENCH)
        assertEquals(ArKernelPromptLocale.EN, prompt.locale)
        assertTrue(prompt.message.contains("orbit", ignoreCase = true))
    }

    @Test
    fun formatsGermanAndEnglishPhasePromptsWithMeasuredCoverage() {
        val german = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.ORBIT, ObjectCaptureInstruction.ORBIT_TO_NEXT_VIEW,
                coverageRatio = 0.5), Locale.GERMAN)
        assertEquals(ArKernelPromptLocale.DE, german.locale)
        assertEquals(ArKernelPromptTone.ACTION, german.tone)
        assertTrue(german.message.contains("herumdrehen"))
        assertEquals("Abdeckung: 50 %", german.detail)
        assertTrue(german.actionable)

        val english = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.SET_DISTANCE, ObjectCaptureInstruction.MOVE_CLOSER,
                coverageRatio = 0.25), Locale.ENGLISH)
        assertEquals(ArKernelPromptTone.ACTION, english.tone)
        assertTrue(english.message.contains("closer", ignoreCase = true))
        assertEquals("Coverage: 25 %", english.detail)
    }

    @Test
    fun formatsCompleteAndRejectionTones() {
        val complete = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.COMPLETE, ObjectCaptureInstruction.COMPLETE,
                complete = true, coverageRatio = 1.0), Locale.ENGLISH)
        assertEquals(ArKernelPromptTone.SUCCESS, complete.tone)
        assertEquals("Scan complete.", complete.message)
        assertEquals("Coverage: 100 %", complete.detail)
        assertFalse(complete.actionable)

        val tracking = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.BLOCKED, ObjectCaptureInstruction.RETRY_TRACKING,
                rejection = ObjectCaptureRejection.TRACKING_UNRELIABLE), Locale.GERMAN)
        assertEquals(ArKernelPromptTone.WARNING, tracking.tone)
        assertTrue(tracking.message.contains("Tracking"))
        assertTrue(tracking.actionable)

        val capacity = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.BLOCKED, ObjectCaptureInstruction.STOP,
                rejection = ObjectCaptureRejection.CAPACITY_REACHED), Locale.GERMAN)
        assertEquals(ArKernelPromptTone.BLOCKED, capacity.tone)
        assertFalse(capacity.actionable)
        assertTrue(capacity.message.contains("Erfassungslimit"))
    }

    @Test
    fun clampsNonFiniteCoverageToZero() {
        val prompt = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.ORBIT, ObjectCaptureInstruction.ORBIT_TO_NEXT_VIEW,
                coverageRatio = Double.NaN), Locale.ENGLISH)
        assertEquals("Coverage: 0 %", prompt.detail)
    }

    @Test
    fun mapsElevationAndDistanceInstructions() {
        val raise = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.ORBIT, ObjectCaptureInstruction.RAISE_CAMERA),
            Locale.GERMAN)
        assertTrue(raise.message.contains("anheben"))
        val farther = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.SET_DISTANCE, ObjectCaptureInstruction.MOVE_FARTHER),
            Locale.GERMAN)
        assertTrue(farther.message.contains("weiter"))
        val centre = ArKernelObjectCaptureGuidancePrompts.format(
            result(ObjectCapturePhase.ACQUIRE_OBJECT, ObjectCaptureInstruction.CENTER_OBJECT),
            Locale.ENGLISH)
        assertTrue(centre.message.contains("Center", ignoreCase = true))
    }

    private fun result(
        phase: ObjectCapturePhase,
        instruction: ObjectCaptureInstruction,
        rejection: ObjectCaptureRejection = ObjectCaptureRejection.NONE,
        complete: Boolean = false,
        coverageRatio: Double = 0.0,
    ): ObjectCaptureGuidanceResult = ObjectCaptureGuidanceResult(
        phase = phase,
        instruction = instruction,
        rejection = rejection,
        complete = complete,
        observations = 0,
        stableCenterObservations = 0,
        coveredBins = 0,
        totalBins = 8,
        coveredRings = 0,
        coverageRatio = coverageRatio,
        currentAzimuthDegrees = 0.0,
        currentElevationDegrees = 0.0,
        targetAzimuthDegrees = 0.0,
        targetElevationDegrees = 0.0,
        turntableStepDegrees = 0.0,
        measuredDistanceMeters = 0.0,
    )
}
