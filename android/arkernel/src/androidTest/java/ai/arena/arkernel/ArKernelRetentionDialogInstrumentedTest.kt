package ai.arena.arkernel

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArKernelRetentionDialogInstrumentedTest {
    @Test
    fun showsLocalizedDialogAndReturnsExplicitPolicy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val confirmed = AtomicReference<ReconstructionFrameRetentionPolicy?>()
        val cancelled = AtomicBoolean(false)
        val invalid = AtomicReference<IllegalArgumentException?>()
        val dialogRef = AtomicReference<AlertDialog>()
        val scenario = ActivityScenario.launch(
            ArKernelRetentionDialogTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                dialogRef.set(ArKernelRetentionDialog.show(
                    activity,
                    encryptionEnabled = true,
                    locale = Locale.GERMAN,
                    selectedPolicy = ReconstructionFrameRetentionPolicy(
                        ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                        deleteEncryptionKey = false),
                    onConfirm = { confirmed.set(it) },
                    onCancel = { cancelled.set(true) },
                    onInvalid = { invalid.set(it) }))
            }
            val dialog = dialogRef.get()
            assertTrue(dialog.isShowing)
            val root = requireNotNull(dialog.window).decorView
            val radioGroup =
                root.findViewWithTag(ArKernelRetentionDialog.MODE_GROUP_TAG) as RadioGroup
            val keyCheckBox =
                root.findViewWithTag(ArKernelRetentionDialog.DELETE_KEY_TAG) as CheckBox
            val keyDescription = root.findViewWithTag(
                ArKernelRetentionDialog.DELETE_KEY_DESCRIPTION_TAG) as TextView
            assertEquals(3, radioGroup.childCount)
            assertTrue(keyCheckBox.isEnabled)
            assertFalse(keyCheckBox.isChecked)
            assertTrue(keyDescription.text.contains("Verschlüsselte Frames"))

            instrumentation.runOnMainSync {
                radioGroup.check((radioGroup.getChildAt(0) as RadioButton).id)
            }
            assertFalse(keyCheckBox.isEnabled)
            assertFalse(keyCheckBox.isChecked)
            assertTrue(keyDescription.text.contains("nicht verfügbar"))

            instrumentation.runOnMainSync {
                radioGroup.check((radioGroup.getChildAt(2) as RadioButton).id)
                keyCheckBox.isChecked = true
            }
            assertTrue(keyCheckBox.isEnabled)
            instrumentation.runOnMainSync {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            val policy = requireNotNull(confirmed.get())
            assertEquals(ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE, policy.mode)
            assertTrue(policy.deleteEncryptionKey)
            assertFalse(cancelled.get())
            assertNull(invalid.get())
            assertFalse(dialog.isShowing)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun cancelInvokesCancelCallbackWithoutPolicy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val confirmed = AtomicReference<ReconstructionFrameRetentionPolicy?>()
        val cancelled = AtomicBoolean(false)
        val dialogRef = AtomicReference<AlertDialog>()
        val scenario = ActivityScenario.launch(
            ArKernelRetentionDialogTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                dialogRef.set(ArKernelRetentionDialog.show(
                    activity,
                    encryptionEnabled = true,
                    locale = Locale.ENGLISH,
                    onConfirm = { confirmed.set(it) },
                    onCancel = { cancelled.set(true) }))
            }
            val dialog = dialogRef.get()
            instrumentation.runOnMainSync {
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            }
            assertNull(confirmed.get())
            assertTrue(cancelled.get())
            assertFalse(dialog.isShowing)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun invalidSelectionKeepsDialogOpenAndReportsError() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val confirmed = AtomicReference<ReconstructionFrameRetentionPolicy?>()
        val invalid = AtomicReference<IllegalArgumentException?>()
        val dialogRef = AtomicReference<AlertDialog>()
        val scenario = ActivityScenario.launch(
            ArKernelRetentionDialogTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                dialogRef.set(ArKernelRetentionDialog.show(
                    activity,
                    encryptionEnabled = true,
                    locale = Locale.ENGLISH,
                    selectedPolicy = ReconstructionFrameRetentionPolicy(),
                    onConfirm = { confirmed.set(it) },
                    onInvalid = { invalid.set(it) }))
            }
            val dialog = dialogRef.get()
            val root = requireNotNull(dialog.window).decorView
            val keyCheckBox =
                root.findViewWithTag(ArKernelRetentionDialog.DELETE_KEY_TAG) as CheckBox
            instrumentation.runOnMainSync {
                keyCheckBox.isChecked = true
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            assertNull(confirmed.get())
            assertNotNull(invalid.get())
            assertTrue(dialog.isShowing)
            instrumentation.runOnMainSync { dialog.dismiss() }
        } finally {
            scenario.close()
        }
    }
}
