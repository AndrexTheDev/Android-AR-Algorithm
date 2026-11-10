package ai.arena.arkernel

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** One localized, UI-neutral retention choice. The host owns rendering. */
data class ArKernelRetentionChoiceText(
    val mode: ReconstructionFrameRetentionMode,
    val label: String,
    val description: String,
    val destructive: Boolean,
)

/**
 * Bounded localized strings for a host retention dialog. This is not a visual
 * screen and never deletes frames or keys: [explicitConfirmationRequired] is
 * always true. The exact policy and action remain in the separate retention
 * controller; physical-device lifecycle validation remains outside this layer.
 */
data class ArKernelRetentionDialogText(
    val localeTag: String,
    val title: String,
    val explanation: String,
    val warning: String?,
    val choices: List<ArKernelRetentionChoiceText>,
    val selectedMode: ReconstructionFrameRetentionMode,
    val selectedDeleteEncryptionKey: Boolean,
    val deleteKeyLabel: String,
    val deleteKeyDescription: String,
    val deleteKeyAvailable: Boolean,
    val confirmLabel: String,
    val cancelLabel: String,
    val destructive: Boolean,
    val explicitConfirmationRequired: Boolean,
)

object ArKernelRetentionStrings {
    fun dialog(
        context: Context,
        locale: Locale = Locale.getDefault(),
        encryptionEnabled: Boolean,
        selectedPolicy: ReconstructionFrameRetentionPolicy = ReconstructionFrameRetentionPolicy(),
    ): ArKernelRetentionDialogText {
        val localized = localized(context, locale)
        val destructive = selectedPolicy.mode != ReconstructionFrameRetentionMode.KEEP
        val deleteKeyAvailable = encryptionEnabled && destructive
        require(!selectedPolicy.deleteEncryptionKey || deleteKeyAvailable) {
            "Key deletion requires authenticated encryption and a deleting retention mode"
        }
        val resources = localized.resources
        val choices = listOf(
            ArKernelRetentionChoiceText(
                ReconstructionFrameRetentionMode.KEEP,
                resources.getString(R.string.arkernel_retention_mode_keep_label),
                resources.getString(R.string.arkernel_retention_mode_keep_description),
                false),
            ArKernelRetentionChoiceText(
                ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                resources.getString(R.string.arkernel_retention_mode_after_publication_label),
                resources.getString(R.string.arkernel_retention_mode_after_publication_description),
                true),
            ArKernelRetentionChoiceText(
                ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE,
                resources.getString(R.string.arkernel_retention_mode_explicit_release_label),
                resources.getString(R.string.arkernel_retention_mode_explicit_release_description),
                true),
        )
        return ArKernelRetentionDialogText(
            localeTag = locale.toLanguageTag(),
            title = resources.getString(R.string.arkernel_retention_title),
            explanation = resources.getString(R.string.arkernel_retention_explanation),
            warning = if (destructive) {
                resources.getString(R.string.arkernel_retention_warning)
            } else {
                null
            },
            choices = choices,
            selectedMode = selectedPolicy.mode,
            selectedDeleteEncryptionKey = selectedPolicy.deleteEncryptionKey,
            deleteKeyLabel = resources.getString(R.string.arkernel_retention_delete_key_label),
            deleteKeyDescription = resources.getString(
                if (deleteKeyAvailable) {
                    R.string.arkernel_retention_delete_key_description
                } else {
                    R.string.arkernel_retention_delete_key_unavailable
                }),
            deleteKeyAvailable = deleteKeyAvailable,
            confirmLabel = resources.getString(R.string.arkernel_retention_confirm_label),
            cancelLabel = resources.getString(R.string.arkernel_retention_cancel_label),
            destructive = destructive,
            explicitConfirmationRequired = true,
        )
    }

    /** Fail-closed conversion of host UI selection into the archive policy. */
    fun select(
        mode: ReconstructionFrameRetentionMode,
        deleteEncryptionKey: Boolean,
        encryptionEnabled: Boolean,
    ): ReconstructionFrameRetentionPolicy {
        require(mode != ReconstructionFrameRetentionMode.KEEP || !deleteEncryptionKey) {
            "Keeping frames while deleting their encryption key would make them unreadable"
        }
        require(!deleteEncryptionKey || encryptionEnabled) {
            "Key deletion requires authenticated encryption"
        }
        return ReconstructionFrameRetentionPolicy(mode, deleteEncryptionKey)
    }

    /**
     * Localized result text after the host has already applied an action. The
     * internal technical reason is deliberately not exposed as user text.
     */
    fun actionMessage(
        context: Context,
        locale: Locale = Locale.getDefault(),
        action: ReconstructionFrameRetentionAction,
    ): String {
        val resources = localized(context, locale).resources
        return resources.getString(
            when {
                action.deleteFrames && action.deleteEncryptionKey ->
                    R.string.arkernel_retention_action_frames_and_key_deleted
                action.deleteFrames ->
                    R.string.arkernel_retention_action_frames_deleted
                action.deleteEncryptionKey ->
                    R.string.arkernel_retention_action_key_deleted_only
                else ->
                    R.string.arkernel_retention_action_kept
            })
    }

    private fun localized(context: Context, locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(configuration)
    }
}
