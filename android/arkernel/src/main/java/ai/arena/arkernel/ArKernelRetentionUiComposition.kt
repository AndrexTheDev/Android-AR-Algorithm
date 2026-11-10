package ai.arena.arkernel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Callbacks for one bounded host-owned retention UX composition. */
data class ArKernelRetentionUiCallbacks(
    val onPolicyConfirmed: (ReconstructionFrameRetentionPolicy) -> Unit = {},
    val onCancelled: () -> Unit = {},
    val onInvalid: (IllegalArgumentException) -> Unit = {},
    val onDismissed: () -> Unit = {},
)

/**
 * Bounded host-owned composition of the localized retention dialog, fail-closed
 * selector, retention controller and result text. It never applies an action
 * by itself: hosts explicitly call [apply] and own navigation and
 * physical-device lifecycle validation.
 */
class ArKernelRetentionUiComposition(
    private val sink: ArKernelRetentionActionSink,
    initialPolicy: ReconstructionFrameRetentionPolicy = ReconstructionFrameRetentionPolicy(),
) {
    private val policyRef = AtomicReference(
        ArKernelRetentionStrings.select(
            initialPolicy.mode,
            initialPolicy.deleteEncryptionKey,
            sink.authenticatedEncryption))
    private val dialogActive = AtomicBoolean(false)

    /** Current fail-closed policy; no deletion is implied. */
    fun policy(): ReconstructionFrameRetentionPolicy = policyRef.get()

    /** Fail-closed replacement of the explicit host policy. */
    fun confirm(policy: ReconstructionFrameRetentionPolicy): ReconstructionFrameRetentionPolicy {
        val validated = ArKernelRetentionStrings.select(
            policy.mode, policy.deleteEncryptionKey, sink.authenticatedEncryption)
        policyRef.set(validated)
        return validated
    }

    /**
     * Shows exactly one localized dialog. Returns its [AlertDialog] so the host
     * owns navigation; [apply] is never called implicitly.
     */
    fun showDialog(
        activity: Activity,
        locale: Locale = Locale.getDefault(),
        callbacks: ArKernelRetentionUiCallbacks = ArKernelRetentionUiCallbacks(),
    ): AlertDialog {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Retention dialog must be shown on the main thread"
        }
        check(dialogActive.compareAndSet(false, true)) {
            "A retention dialog is already active"
        }
        try {
            val dialog = ArKernelRetentionDialog.show(
                activity,
                encryptionEnabled = sink.authenticatedEncryption,
                locale = locale,
                selectedPolicy = policyRef.get(),
                onConfirm = { policy ->
                    try {
                        val validated = confirm(policy)
                        callbacks.onPolicyConfirmed(validated)
                    } catch (error: IllegalArgumentException) {
                        callbacks.onInvalid(error)
                    }
                },
                onCancel = { callbacks.onCancelled() },
                onInvalid = { error -> callbacks.onInvalid(error) })
            dialog.setOnDismissListener {
                dialogActive.set(false)
                callbacks.onDismissed()
            }
            return dialog
        } catch (error: Throwable) {
            dialogActive.set(false)
            throw error
        }
    }

    /** Terminal publication outcome; the host still decides whether to apply. */
    fun afterPublication(complete: Boolean): ReconstructionFrameRetentionAction =
        ReconstructionFrameRetentionController(sink, policyRef.get())
            .afterPublication(complete)

    /** Explicit host release; the host still decides whether to apply. */
    fun release(): ReconstructionFrameRetentionAction =
        ReconstructionFrameRetentionController(sink, policyRef.get()).release()

    /** Explicit host action application; returns false on sink failure. */
    fun apply(action: ReconstructionFrameRetentionAction): Boolean =
        ReconstructionFrameRetentionController(sink, policyRef.get()).apply(action)

    /** Localized result text after the host already applied an action. */
    fun actionMessage(
        context: Context,
        locale: Locale = Locale.getDefault(),
        action: ReconstructionFrameRetentionAction,
    ): String = ArKernelRetentionStrings.actionMessage(context, locale, action)
}
