package ai.arena.arkernel

import java.util.Locale

/** Short localized action labels; hosts still apply real accessibility semantics. */
data class ArKernelGuidedMotionActionLabels(
    val locale: ArKernelPromptLocale,
    val cancel: String,
    val finish: String,
    val retry: String,
) {
    init {
        require(cancel.isNotBlank() && finish.isNotBlank() && retry.isNotBlank())
    }
}

/**
 * Bounded German/English host copy for guided motion compatibility runs.
 *
 * It reuses the existing UI-neutral prompt envelope and turns only exact
 * [GuidedMotionGuidanceState] values, stable [GuidedMotionAdmissionReason]
 * values or an explicit cancellation into short text and action labels. The
 * accessibility description is host-supplied text only: this object does not
 * render UI, provide full accessibility semantics, actuate hardware, finish the
 * probe, grant admission or assert a dimensional/quality grade. Accepted
 * reports remain `unverified`.
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

    fun cancelled(
        locale: Locale = Locale.getDefault(),
    ): ArKernelLocalizedPrompt = cancelled(ArKernelPromptLocale.from(locale))

    fun cancelled(promptLocale: ArKernelPromptLocale): ArKernelLocalizedPrompt =
        prompt(promptLocale, ArKernelPromptTone.BLOCKED,
            text(promptLocale, "Geführter Test abgebrochen.",
                "Guided test cancelled."),
            text(promptLocale, "Qualität: ungeprüft; Geführter Bewegungstest",
                "Quality: unverified; Guided motion test"), true)

    fun actionLabels(
        locale: Locale = Locale.getDefault(),
    ): ArKernelGuidedMotionActionLabels =
        actionLabels(ArKernelPromptLocale.from(locale))

    fun actionLabels(
        promptLocale: ArKernelPromptLocale,
    ): ArKernelGuidedMotionActionLabels = ArKernelGuidedMotionActionLabels(
        promptLocale,
        text(promptLocale, "Abbrechen", "Cancel"),
        text(promptLocale, "Jetzt abschließen", "Finish now"),
        text(promptLocale, "Erneut versuchen", "Try again"),
    )

    /** Text-only content description; it is not a platform accessibility node. */
    fun accessibilityDescription(
        prompt: ArKernelLocalizedPrompt,
        labels: ArKernelGuidedMotionActionLabels,
        cancelAvailable: Boolean,
        finishAvailable: Boolean,
        retryAvailable: Boolean,
    ): String {
        require(prompt.locale == labels.locale) {
            "Prompt and action labels must use the same locale"
        }
        val parts = mutableListOf(
            prompt.message,
            prompt.detail,
            text(prompt.locale, "Qualität: ungeprüft", "Quality: unverified"),
        )
        if (cancelAvailable) parts += labels.cancel
        if (finishAvailable) parts += labels.finish
        if (retryAvailable) parts += labels.retry
        return parts.joinToString(". ") {
            it.trim().trimEnd('.', ';')
        } + "."
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
