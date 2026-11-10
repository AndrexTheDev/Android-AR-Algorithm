package ai.arena.arkernel

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Bounded localized visual retention dialog built from platform widgets.
 *
 * It renders the three host choices and an optional encryption-key checkbox,
 * requires an explicit confirm/cancel, and converts the selection through the
 * fail-closed [ArKernelRetentionStrings.select] contract. It never deletes
 * frames or keys, never controls storage and does not validate a physical
 * encrypted keyframe lifecycle. Hosts own navigation and the actual policy
 * application.
 */
object ArKernelRetentionDialog {
    const val MODE_GROUP_TAG = "ai.arena.arkernel.retention.modes"
    const val DELETE_KEY_TAG = "ai.arena.arkernel.retention.delete_key"
    const val DELETE_KEY_DESCRIPTION_TAG =
        "ai.arena.arkernel.retention.delete_key_description"

    fun show(
        context: Context,
        encryptionEnabled: Boolean,
        locale: Locale = Locale.getDefault(),
        selectedPolicy: ReconstructionFrameRetentionPolicy = ReconstructionFrameRetentionPolicy(),
        onConfirm: (ReconstructionFrameRetentionPolicy) -> Unit,
        onCancel: () -> Unit = {},
        onInvalid: (IllegalArgumentException) -> Unit = {},
    ): AlertDialog {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Retention dialog must be shown on the main thread"
        }
        val strings = ArKernelRetentionStrings.dialog(
            context, locale, encryptionEnabled, selectedPolicy)
        val localized = ArKernelRetentionStrings.localizedContext(context, locale)
        val themed = ContextThemeWrapper(
            localized, android.R.style.Theme_Material_Light_Dialog_Alert)
        val density = themed.resources.displayMetrics.density
        val padding = (16f * density).toInt()
        val smallPadding = (4f * density).toInt()

        fun label(text: String, color: Int? = null, sizeSp: Float = 14f): TextView =
            TextView(themed).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                if (color != null) setTextColor(color)
            }

        fun contentParams(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)

        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, smallPadding, padding, smallPadding)
        }
        content.addView(label(strings.explanation), contentParams())
        strings.warning?.let {
            content.addView(label(it, Color.rgb(180, 40, 40)), contentParams())
        }

        var selectedMode = strings.selectedMode
        val modeById = HashMap<Int, ReconstructionFrameRetentionMode>()
        val radioGroup = RadioGroup(themed).apply {
            orientation = RadioGroup.VERTICAL
            tag = MODE_GROUP_TAG
        }
        for (choice in strings.choices) {
            val button = RadioButton(themed).apply {
                id = View.generateViewId()
                text = "${choice.label}\n${choice.description}"
                isChecked = choice.mode == selectedMode
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, smallPadding, 0, smallPadding)
            }
            modeById[button.id] = choice.mode
            radioGroup.addView(button)
        }
        content.addView(radioGroup, contentParams())

        val keyCheckBox = CheckBox(themed).apply {
            tag = DELETE_KEY_TAG
            text = strings.deleteKeyLabel
            isChecked = strings.selectedDeleteEncryptionKey
            isEnabled = strings.deleteKeyAvailable
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        content.addView(keyCheckBox, contentParams())

        val keyDescription = label(
            if (strings.deleteKeyAvailable) {
                strings.deleteKeyAvailableDescription
            } else {
                strings.deleteKeyUnavailableDescription
            }, sizeSp = 12f).apply {
            tag = DELETE_KEY_DESCRIPTION_TAG
        }
        content.addView(keyDescription, contentParams())

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedMode = modeById[checkedId] ?: selectedMode
            val available = encryptionEnabled &&
                selectedMode != ReconstructionFrameRetentionMode.KEEP
            keyCheckBox.isEnabled = available
            if (!available) keyCheckBox.isChecked = false
            keyDescription.text = if (available) {
                strings.deleteKeyAvailableDescription
            } else {
                strings.deleteKeyUnavailableDescription
            }
        }

        val scrollView = ScrollView(themed).apply {
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(themed)
            .setTitle(strings.title)
            .setView(scrollView)
            .setPositiveButton(strings.confirmLabel) { d, _ ->
                val policy = try {
                    ArKernelRetentionStrings.select(
                        selectedMode, keyCheckBox.isChecked, encryptionEnabled)
                } catch (error: IllegalArgumentException) {
                    onInvalid(error)
                    return@setPositiveButton
                }
                d.dismiss()
                onConfirm(policy)
            }
            .setNegativeButton(strings.cancelLabel) { d, _ -> d.cancel() }
            .setOnCancelListener { onCancel() }
            .create()
        dialog.show()
        return dialog
    }
}
