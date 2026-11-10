package ai.arena.arkernel

import java.util.Locale

/**
 * Bounded German/English host copy for guided motion compatibility runs.
 *
 * It reuses the existing UI-neutral prompt envelope and turns only exact
 * [GuidedMotionGuidanceState] values or stable [GuidedMotionAdmissionReason]
 * values into short text. It does not render UI, provide accessibility or
 * cancellation UX, actuate hardware, finish the probe, grant admission or
 * assert a dimensional/quality grade. Accepted reports remain `unverified`.
 */
object ArKernelGuidedMotionPrompts {
    fun format(
        state: GuidedMotionGuidanceState,
        locale: Locale = Locale.getDefault(),
    ): ArKernelLocalizedPrompt = format(state, ArKernelPromptLocale.from(locale))

    fun format(
        state: GuidedMotionGuidanceState,
        promptLocale: ArKernelPromptLocale,
    ): ArKernelLocalizedPrompt = when (state) {
        GuidedMotionGuidanceState.NOT_STARTED -> prompt(promptLocale,
            ArKernelPromptTone.INFO,
            text(promptLocale, "Bereit für den geführten Bewegungstest.",
                "Ready for the guided motion test."), guidanceDetail(promptLocale), true)
        GuidedMotionGuidanceState.HOLD_STILL -> prompt(promptLocale,
            ArKernelPromptTone.ACTION,
            text(promptLocale, "Kamera ruhig halten.", "Hold the camera still."),
            guidanceDetail(promptLocale), true)
        GuidedMotionGuidanceState.ROTATE_MULTI_AXIS -> prompt(promptLocale,
            ArKernelPromptTone.ACTION,
            text(promptLocale, "Kamera langsam um mehrere Achsen drehen.",
                "Slowly rotate the camera around multiple axes."),
            guidanceDetail(promptLocale), true)
        GuidedMotionGuidanceState.TRANSLATE_MULTI_DIRECTION -> prompt(promptLocale,
            ArKernelPromptTone.ACTION,
            text(promptLocale, "Kamera langsam in mehrere Richtungen bewegen.",
                "Slowly move the camera in multiple directions."),
            guidanceDetail(promptLocale), true)
        GuidedMotionGuidanceState.MEASURE_KNOWN_DISTANCE -> prompt(promptLocale,
            ArKernelPromptTone.ACTION,
            text(promptLocale,
                "Bekannte Referenzstrecke mit unabhängigem Target messen.",
                "Measure a known reference distance with an independent target."),
            guidanceDetail(promptLocale), true)
        GuidedMotionGuidanceState.COMPLETED -> prompt(promptLocale,
            ArKernelPromptTone.SUCCESS,
            text(promptLocale, "Geführter Test abgeschlossen.",
                "Guided test complete."), guidanceDetail(promptLocale), false)
    }

    fun format(
        report: GuidedMotionAdmissionReport,
        locale: Locale = Locale.getDefault(),
    ): ArKernelLocalizedPrompt = format(report, ArKernelPromptLocale.from(locale))

    fun format(
        report: GuidedMotionAdmissionReport,
        promptLocale: ArKernelPromptLocale,
    ): ArKernelLocalizedPrompt {
        val quality = text(promptLocale, "Qualität: ungeprüft", "Quality: unverified")
        val reasons = report.blockingReasons
        val detail = if (reasons.isEmpty()) {
            "$quality; " + text(promptLocale, "Laufzeitprüfung: bestanden",
                "Runtime checks: passed")
        } else {
            "$quality; " + reasons.joinToString("; ") {
                reasonText(it, promptLocale)
            }
        }
        return if (reasons.isEmpty()) {
            prompt(promptLocale, ArKernelPromptTone.SUCCESS,
                text(promptLocale, "Laufzeit-Messung freigegeben.",
                    "Runtime metric measurement admitted."), detail, false)
        } else {
            prompt(promptLocale, ArKernelPromptTone.BLOCKED,
                text(promptLocale,
                    "Geführter Test nicht freigegeben. Bitte erneut versuchen.",
                    "Guided test not admitted. Please try again."), detail, true)
        }
    }

    private fun guidanceDetail(locale: ArKernelPromptLocale): String =
        text(locale, "Geführter Bewegungstest", "Guided motion test")

    private fun reasonText(
        reason: GuidedMotionAdmissionReason,
        locale: ArKernelPromptLocale,
    ): String = when (reason) {
        GuidedMotionAdmissionReason.ACTIVE_COMPATIBILITY_REJECTED ->
            text(locale, "Aktive Kompatibilitätsprüfung nicht bestanden.",
                "Active compatibility check failed.")
        GuidedMotionAdmissionReason.ROTATION_UNOBSERVABLE ->
            text(locale, "Rotation nicht beobachtbar.", "Rotation not observable.")
        GuidedMotionAdmissionReason.TIME_OFFSET_UNOBSERVABLE ->
            text(locale, "Zeitversatz nicht beobachtbar.", "Time offset not observable.")
        GuidedMotionAdmissionReason.METRIC_RUNTIME_INCONSISTENT ->
            text(locale, "Metrische Laufzeit inkonsistent.",
                "Metric runtime inconsistent.")
    }

    private fun text(
        locale: ArKernelPromptLocale,
        german: String,
        english: String,
    ): String = if (locale == ArKernelPromptLocale.DE) german else english

    private fun prompt(
        locale: ArKernelPromptLocale,
        tone: ArKernelPromptTone,
        message: String,
        detail: String,
        actionable: Boolean,
    ): ArKernelLocalizedPrompt =
        ArKernelLocalizedPrompt(locale, tone, message, detail, actionable)
}
