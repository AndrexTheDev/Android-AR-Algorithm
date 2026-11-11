package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArKernelRetentionStringsInstrumentedTest {
    @Test
    fun providesBoundedGermanAndEnglishDialogStrings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val german = ArKernelRetentionStrings.dialog(
            context,
            Locale.GERMAN,
            encryptionEnabled = true,
            selectedPolicy = ReconstructionFrameRetentionPolicy(
                ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                deleteEncryptionKey = true),
        )
        assertEquals("de", german.localeTag)
        assertEquals("Aufbewahrung temporärer Scandaten", german.title)
        assertTrue(german.explanation.contains("ausdrückliche"))
        assertEquals(3, german.choices.size)
        assertEquals("Nach erfolgreicher Veröffentlichung löschen", german.choices[1].label)
        assertTrue(german.choices[1].destructive)
        assertTrue(german.deleteKeyAvailable)
        assertTrue(german.selectedDeleteEncryptionKey)
        assertTrue(german.deleteKeyAvailableDescription.contains("Verschlüsselte Frames"))
        assertTrue(german.deleteKeyUnavailableDescription.contains("nicht verfügbar"))
        assertEquals("Löschen kann nicht rückgängig gemacht werden.", german.warning)
        assertEquals("Auswahl speichern", german.confirmLabel)
        assertEquals("Abbrechen", german.cancelLabel)
        assertTrue(german.destructive)
        assertTrue(german.explicitConfirmationRequired)

        val english = ArKernelRetentionStrings.dialog(
            context,
            Locale.ENGLISH,
            encryptionEnabled = false,
        )
        assertEquals("en", english.localeTag)
        assertEquals("Temporary scan retention", english.title)
        assertTrue(english.explanation.contains("explicit confirmation"))
        assertEquals(3, english.choices.size)
        assertEquals("Keep frames", english.choices[0].label)
        assertFalse(english.choices[0].destructive)
        assertFalse(english.deleteKeyAvailable)
        assertTrue(english.deleteKeyDescription.contains("unavailable"))
        assertTrue(english.deleteKeyAvailableDescription.contains("Encrypted frames"))
        assertTrue(english.deleteKeyUnavailableDescription.contains("unavailable"))
        assertNull(english.warning)
        assertFalse(english.destructive)
        assertTrue(english.explicitConfirmationRequired)
    }

    @Test
    fun selectionFailsClosedForUnavailableOrIncompatibleKeyDeletion() {
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRetentionStrings.select(
                ReconstructionFrameRetentionMode.KEEP,
                deleteEncryptionKey = true,
                encryptionEnabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRetentionStrings.select(
                ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                deleteEncryptionKey = true,
                encryptionEnabled = false)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRetentionStrings.dialog(
                context,
                Locale.ENGLISH,
                encryptionEnabled = false,
                selectedPolicy = ReconstructionFrameRetentionPolicy(
                    ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION,
                    deleteEncryptionKey = true))
        }
        val policy = ArKernelRetentionStrings.select(
            ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE,
            deleteEncryptionKey = true,
            encryptionEnabled = true)
        assertEquals(ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE, policy.mode)
        assertTrue(policy.deleteEncryptionKey)
        val keep = ArKernelRetentionStrings.select(
            ReconstructionFrameRetentionMode.KEEP,
            deleteEncryptionKey = false,
            encryptionEnabled = false)
        assertFalse(keep.deleteEncryptionKey)
    }

    @Test
    fun actionMessagesUseExactDeletionOutcomeWithoutInternalReason() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internalReason = "internal technical reason must not leak"
        val kept = ArKernelRetentionStrings.actionMessage(
            context, Locale.GERMAN,
            ReconstructionFrameRetentionAction(false, false, internalReason))
        assertEquals("Zwischengespeicherte Frames bleiben auf diesem Gerät.", kept)
        assertFalse(kept.contains(internalReason))

        val frames = ArKernelRetentionStrings.actionMessage(
            context, Locale.GERMAN,
            ReconstructionFrameRetentionAction(true, false, internalReason))
        assertEquals("Zwischengespeicherte Frames wurden gelöscht.", frames)

        val both = ArKernelRetentionStrings.actionMessage(
            context, Locale.ENGLISH,
            ReconstructionFrameRetentionAction(true, true, internalReason))
        assertEquals("Retained frames and the encryption key were deleted.", both)

        val keyOnly = ArKernelRetentionStrings.actionMessage(
            context, Locale.GERMAN,
            ReconstructionFrameRetentionAction(false, true, internalReason))
        assertTrue(keyOnly.contains("Verschlüsselungsschlüssel"))
        assertTrue(keyOnly.contains("nicht mehr gelesen"))
        assertFalse(keyOnly.contains(internalReason))
    }
}
