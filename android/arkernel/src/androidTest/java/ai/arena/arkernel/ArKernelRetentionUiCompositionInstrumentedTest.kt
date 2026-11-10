package ai.arena.arkernel

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArKernelRetentionUiCompositionInstrumentedTest {
    private class FakeSink(
        override val authenticatedEncryption: Boolean,
    ) : ArKernelRetentionActionSink {
        val clearCalls = mutableListOf<Boolean>()
        var keyDeletions = 0

        override fun clearFrames(deleteKey: Boolean): Boolean {
            clearCalls.add(deleteKey)
            return true
        }

        override fun deleteEncryptionKey(): Boolean {
            keyDeletions += 1
            return true
        }
    }

    @Test
    fun dialogConfirmationUpdatesPolicyAndExplicitActionIsApplied() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = FakeSink(authenticatedEncryption = true)
        val composition = ArKernelRetentionUiComposition(sink)
        val dialogRef = AtomicReference<AlertDialog>()
        val scenario = ActivityScenario.launch(
            ArKernelRetentionDialogTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                dialogRef.set(composition.showDialog(activity, locale = Locale.ENGLISH))
            }
            val dialog = dialogRef.get()
            val root = requireNotNull(dialog.window).decorView
            val radioGroup =
                root.findViewWithTag(ArKernelRetentionDialog.MODE_GROUP_TAG) as RadioGroup
            val keyCheckBox =
                root.findViewWithTag(ArKernelRetentionDialog.DELETE_KEY_TAG) as CheckBox
            instrumentation.runOnMainSync {
                radioGroup.check((radioGroup.getChildAt(1) as RadioButton).id)
                keyCheckBox.isChecked = true
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()

            assertEquals(
                ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                composition.policy().mode)
            assertTrue(composition.policy().deleteEncryptionKey)
            assertFalse(dialog.isShowing)

            val published = composition.afterPublication(complete = true)
            assertTrue(published.deleteFrames)
            assertTrue(published.deleteEncryptionKey)
            assertTrue(composition.apply(published))
            assertEquals(listOf(true), sink.clearCalls)
            assertEquals(0, sink.keyDeletions)
            assertEquals(
                "Retained frames and the encryption key were deleted.",
                composition.actionMessage(
                    instrumentation.targetContext, Locale.ENGLISH, published))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun onlyOneDialogCanBeActiveAtATime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val composition = ArKernelRetentionUiComposition(
            FakeSink(authenticatedEncryption = true))
        val dialogRef = AtomicReference<AlertDialog>()
        val scenario = ActivityScenario.launch(
            ArKernelRetentionDialogTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                dialogRef.set(composition.showDialog(activity, locale = Locale.ENGLISH))
                try {
                    composition.showDialog(activity, locale = Locale.ENGLISH)
                    fail("Expected IllegalStateException")
                } catch (error: IllegalStateException) {
                    // expected
                }
            }
            instrumentation.runOnMainSync { dialogRef.get().dismiss() }
            instrumentation.waitForIdleSync()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun dismissedDialogAllowsANewCompositionDialog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val composition = ArKernelRetentionUiComposition(
            FakeSink(authenticatedEncryption = true))
        val firstRef = AtomicReference<AlertDialog>()
        val secondRef = AtomicReference<AlertDialog>()
        val scenario = ActivityScenario.launch(
            ArKernelRetentionDialogTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                firstRef.set(composition.showDialog(activity, locale = Locale.ENGLISH))
            }
            instrumentation.runOnMainSync { firstRef.get().dismiss() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                secondRef.set(composition.showDialog(activity, locale = Locale.ENGLISH))
            }
            assertTrue(secondRef.get().isShowing)
            instrumentation.runOnMainSync { secondRef.get().dismiss() }
            instrumentation.waitForIdleSync()
        } finally {
            scenario.close()
        }
    }
}
