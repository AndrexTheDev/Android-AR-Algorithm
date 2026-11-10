package ai.arena.arkernel

import java.util.Locale
import kotlin.math.roundToInt

enum class ArKernelPromptLocale {
    EN,
    DE;

    companion object {
        fun from(locale: Locale): ArKernelPromptLocale =
            if (locale.language.equals("de", ignoreCase = true)) DE else EN
    }
}

enum class ArKernelPromptTone {
    INFO,
    ACTION,
    WARNING,
    SUCCESS,
    BLOCKED,
}

/**
 * UI-neutral localized text for hosts. The exact native telemetry remains in
 * [ObjectCaptureGuidanceResult]; this formatter only turns it into a short
 * prompt and a measured coverage detail. It does not render UI, actuate a
 * turntable, infer reconstruction quality or alter the `unverified` contract.
 */
data class ArKernelLocalizedPrompt(
    val locale: ArKernelPromptLocale,
    val tone: ArKernelPromptTone,
    val message: String,
    val detail: String,
    val actionable: Boolean,
)

object ArKernelObjectCaptureGuidancePrompts {
    fun format(
        result: ObjectCaptureGuidanceResult,
        locale: Locale = Locale.getDefault(),
    ): ArKernelLocalizedPrompt = format(result, ArKernelPromptLocale.from(locale))

    fun format(
        result: ObjectCaptureGuidanceResult,
        promptLocale: ArKernelPromptLocale,
    ): ArKernelLocalizedPrompt {
        val ratio = if (result.coverageRatio.isFinite()) {
            result.coverageRatio.coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val percent = (ratio * 100.0).roundToInt().coerceIn(0, 100)
        val detail = if (promptLocale == ArKernelPromptLocale.DE) {
            "Abdeckung: $percent %"
        } else {
            "Coverage: $percent %"
        }
        if (result.complete || result.phase == ObjectCapturePhase.COMPLETE) {
            return prompt(promptLocale, ArKernelPromptTone.SUCCESS,
                text(promptLocale, "Scan abgeschlossen.", "Scan complete."), detail, false)
        }
        if (result.rejection != ObjectCaptureRejection.NONE) {
            return rejectionPrompt(result.rejection, promptLocale, detail)
        }
        return when (result.phase) {
            ObjectCapturePhase.ACQUIRE_OBJECT -> prompt(promptLocale,
                ArKernelPromptTone.ACTION,
                text(promptLocale, "Objekt mittig im Bild platzieren.",
                    "Center the object in the frame."), detail, true)
            ObjectCapturePhase.STABILIZE_CENTER -> prompt(promptLocale,
                ArKernelPromptTone.ACTION,
                text(promptLocale, "Kamera kurz ruhig halten.",
                    "Hold the camera still briefly."), detail, true)
            ObjectCapturePhase.SET_DISTANCE -> when (result.instruction) {
                ObjectCaptureInstruction.MOVE_CLOSER -> prompt(promptLocale,
                    ArKernelPromptTone.ACTION,
                    text(promptLocale, "Etwas näher an das Objekt herangehen.",
                        "Move a little closer to the object."), detail, true)
                ObjectCaptureInstruction.MOVE_FARTHER -> prompt(promptLocale,
                    ArKernelPromptTone.ACTION,
                    text(promptLocale, "Etwas weiter vom Objekt entfernen.",
                        "Move a little farther from the object."), detail, true)
                else -> prompt(promptLocale, ArKernelPromptTone.ACTION,
                    text(promptLocale, "Abstand zum Objekt anpassen.",
                        "Adjust the distance to the object."), detail, true)
            }
            ObjectCapturePhase.ORBIT -> when (result.instruction) {
                ObjectCaptureInstruction.RAISE_CAMERA -> prompt(promptLocale,
                    ArKernelPromptTone.ACTION,
                    text(promptLocale, "Kamera etwas anheben.",
                        "Raise the camera slightly."), detail, true)
                ObjectCaptureInstruction.LOWER_CAMERA -> prompt(promptLocale,
                    ArKernelPromptTone.ACTION,
                    text(promptLocale, "Kamera etwas absenken.",
                        "Lower the camera slightly."), detail, true)
                ObjectCaptureInstruction.ORBIT_TO_NEXT_VIEW -> prompt(promptLocale,
                    ArKernelPromptTone.ACTION,
                    text(promptLocale, "Langsam um das Objekt herumdrehen.",
                        "Slowly orbit around the object."), detail, true)
                else -> prompt(promptLocale, ArKernelPromptTone.INFO,
                    text(promptLocale, "Nächste Ansicht aufnehmen.",
                        "Capture the next view."), detail, true)
            }
            ObjectCapturePhase.BLOCKED -> prompt(promptLocale,
                ArKernelPromptTone.BLOCKED,
                text(promptLocale, "Erfassung angehalten.", "Capture stopped."),
                detail, false)
            ObjectCapturePhase.COMPLETE -> prompt(promptLocale,
                ArKernelPromptTone.SUCCESS,
                text(promptLocale, "Scan abgeschlossen.", "Scan complete."),
                detail, false)
        }
    }

    private fun rejectionPrompt(
        rejection: ObjectCaptureRejection,
        locale: ArKernelPromptLocale,
        detail: String,
    ): ArKernelLocalizedPrompt = when (rejection) {
        ObjectCaptureRejection.INVALID,
        ObjectCaptureRejection.NON_MONOTONIC_TIMESTAMP,
        ObjectCaptureRejection.TRACKING_UNRELIABLE -> prompt(locale,
            ArKernelPromptTone.WARNING,
            text(locale, "Tracking kurz unterbrochen. Kamera ruhig halten und erneut versuchen.",
                "Tracking briefly interrupted. Hold the camera still and try again."),
            detail, true)
        ObjectCaptureRejection.FOREGROUND_INVALID,
        ObjectCaptureRejection.OBJECT_CENTER_MISSING -> prompt(locale,
            ArKernelPromptTone.ACTION,
            text(locale, "Objekt nicht sicher erkannt. Bitte mittig platzieren.",
                "Object not reliably detected. Please center it."), detail, true)
        ObjectCaptureRejection.CENTER_UNSTABLE -> prompt(locale,
            ArKernelPromptTone.ACTION,
            text(locale, "Objektmitte noch nicht stabil. Kurz stillhalten.",
                "Object centre not stable yet. Hold still briefly."), detail, true)
        ObjectCaptureRejection.DISTANCE_INVALID -> prompt(locale,
            ArKernelPromptTone.ACTION,
            text(locale, "Abstand zum Objekt anpassen.",
                "Adjust the distance to the object."), detail, true)
        ObjectCaptureRejection.CAPACITY_REACHED,
        ObjectCaptureRejection.COVERAGE_INCOMPLETE -> prompt(locale,
            ArKernelPromptTone.BLOCKED,
            text(locale, "Erfassungslimit erreicht. Scan bei Bedarf neu starten.",
                "Capture limit reached. Restart the scan if needed."), detail, false)
        ObjectCaptureRejection.NONE -> prompt(locale, ArKernelPromptTone.INFO,
            text(locale, "Erfassung läuft.", "Capture in progress."), detail, true)
    }

    private fun text(locale: ArKernelPromptLocale, german: String, english: String): String =
        if (locale == ArKernelPromptLocale.DE) german else english

    private fun prompt(
        locale: ArKernelPromptLocale,
        tone: ArKernelPromptTone,
        message: String,
        detail: String,
        actionable: Boolean,
    ): ArKernelLocalizedPrompt =
        ArKernelLocalizedPrompt(locale, tone, message, detail, actionable)
}
