package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelRetentionActionSink
import ai.arena.arkernel.ArKernelRetentionLedgerAction
import ai.arena.arkernel.ArKernelRetentionLedgerMode
import ai.arena.arkernel.ArKernelRetentionRunBundle
import ai.arena.arkernel.ArKernelRetentionRunLedgerAdapter
import ai.arena.arkernel.ArKernelRetentionStrings
import ai.arena.arkernel.ArKernelRetentionUiCallbacks
import ai.arena.arkernel.ArKernelRetentionUiComposition
import ai.arena.arkernel.ReconstructionFrameRetentionAction
import ai.arena.arkernel.ReconstructionFrameRetentionMode
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Real Android View integration of the bounded host-owned retention UX.
 *
 * This screen adds navigation, lifecycle state restoration, explicit
 * accessibility descriptions and announcements to the existing UI-neutral
 * `ArKernelRetentionUiComposition`. It uses a dry-run action sink: no frame,
 * key or storage is touched. The host explicitly opens the dialog, previews a
 * publication/release action and applies it only to the dry-run sink. The
 * resulting `ARKRETENT1_<run>.txt` records structural facts only.
 *
 * No images, pixels, poses or trajectories are captured, no physical target is
 * measured, no automatic or universal calibration is performed, no signature
 * is verified, nothing is uploaded automatically and no quality grade is
 * granted. Physical encrypted keyframe lifecycle validation on a real device
 * remains open; the grade stays `unverified`.
 */
class RetentionActivity : android.app.Activity() {

    private companion object {
        const val STATE_RUN_ID = "retention.run_id"
        const val STATE_MODE = "retention.mode"
        const val STATE_DELETE_KEY = "retention.delete_key"
        const val STATE_DIALOG_SHOWN = "retention.dialog_shown"
        const val STATE_ACTION_KIND = "retention.action_kind"
        const val STATE_ACTION_APPLIED = "retention.action_applied"
        const val STATE_LEDGER = "retention.ledger"
        const val STATE_STATUS = "retention.status"
    }

    /** Records dry-run calls; deletes nothing. */
    private class DryRunSink : ArKernelRetentionActionSink {
        override val authenticatedEncryption: Boolean = true
        var clearCalls: Int = 0
            private set
        var keyCalls: Int = 0
            private set

        override fun clearFrames(deleteKey: Boolean): Boolean {
            clearCalls += 1
            if (deleteKey) keyCalls += 1
            return true
        }

        override fun deleteEncryptionKey(): Boolean {
            keyCalls += 1
            return true
        }
    }

    private lateinit var composition: ArKernelRetentionUiComposition
    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var applyButton: Button
    private lateinit var shareButton: Button

    private val sink = DryRunSink()
    private var runId: String = "retention." + System.currentTimeMillis()
    private var deviceToken: String = "device.unknown"
    private var selectedMode: ReconstructionFrameRetentionMode =
        ReconstructionFrameRetentionMode.KEEP
    private var deleteKeySelected: Boolean = false
    private var dialogShown: Boolean = false
    private var actionKind: ArKernelRetentionLedgerAction =
        ArKernelRetentionLedgerAction.NONE
    private var actionApplied: Boolean = false
    private var currentAction: ReconstructionFrameRetentionAction? = null
    private var currentLedger: String = ""
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceToken = opaqueDeviceToken()
        if (savedInstanceState != null) {
            runId = savedInstanceState.getString(
                STATE_RUN_ID, runId)!!
            selectedMode = try {
                ReconstructionFrameRetentionMode.valueOf(
                    savedInstanceState.getString(
                        STATE_MODE, selectedMode.name)!!)
            } catch (error: IllegalArgumentException) {
                ReconstructionFrameRetentionMode.KEEP
            }
            deleteKeySelected = savedInstanceState.getBoolean(
                STATE_DELETE_KEY, false)
            dialogShown = savedInstanceState.getBoolean(
                STATE_DIALOG_SHOWN, false)
            actionKind = try {
                ArKernelRetentionLedgerAction.valueOf(
                    savedInstanceState.getString(
                        STATE_ACTION_KIND, actionKind.name)!!)
            } catch (error: IllegalArgumentException) {
                ArKernelRetentionLedgerAction.NONE
            }
            actionApplied = savedInstanceState.getBoolean(
                STATE_ACTION_APPLIED, false)
            currentLedger = savedInstanceState.getString(
                STATE_LEDGER, "")!!
        }
        val policy = ArKernelRetentionStrings.select(
            selectedMode, deleteKeySelected,
            sink.authenticatedEncryption)
        composition = ArKernelRetentionUiComposition(sink, policy)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "Retention-UX (ARKRETENT1)"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für die Aufbewahrungs-Vorschau"
        }
        val intro = TextView(this).apply {
            text = "Echte Android-Ansicht mit lokalisiertem Dialog, " +
                "Navigation, Lifecycle-Wiederherstellung und " +
                "Accessibility-Beschreibungen. Alles hier ist ein " +
                "Trockenlauf: es werden keine Frames oder Schlüssel " +
                "gelöscht. Der Host bestätigt jeden Schritt explizit."
            textSize = 13f
            contentDescription = "Erklärung: Trockenlauf ohne Löschung"
        }
        val safetyText = TextView(this).apply {
            text = "Keine Bilder, Posen oder Trajektorien, keine " +
                "Signaturprüfung, kein automatischer Upload, keine " +
                "physische Gerätevalidierung, keine " +
                "Qualitätsaufstufung; Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
            contentDescription = "Grenzen des Retention-Trockenlaufs"
        }
        statusText = TextView(this).apply {
            text = if (savedInstanceState == null) {
                "Noch kein Dialog geöffnet."
            } else {
                savedInstanceState.getString(
                    STATE_STATUS, "Zustand wiederhergestellt.")
            }
            textSize = 13f
            setPadding(0, 12, 0, 12)
            importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val dialogButton = labeledButton(
            "1. Lokalisierten Retention-Dialog öffnen",
            "Öffnet den lokalisierten Aufbewahrungs-Dialog")
        val publicationButton = labeledButton(
            "2. Publikation erfolgreich (Vorschau)",
            "Zeigt eine Veröffentlichungs-Aktion als Vorschau")
        val releaseButton = labeledButton(
            "3. Explizite Freigabe (Vorschau)",
            "Zeigt eine explizite Freigabe-Aktion als Vorschau")
        applyButton = labeledButton(
            "4. Vorschau trocken anwenden",
            "Wendet die Vorschau nur im Trockenlauf an")
        val writeButton = labeledButton(
            "5. ARKRETENT1 prüfen & speichern",
            "Erzeugt die ARKRETENT1-Laufakte")
        shareButton = labeledButton(
            "6. ARKRETENT1 teilen / hier hochladen",
            "Teilt die ARKRETENT1-Laufakte")
        val backButton = labeledButton(
            "Zurück zur Übersicht",
            "Schließt diesen Bildschirm und kehrt zur Übersicht zurück")
        previewText = TextView(this).apply {
            text = if (currentLedger.isEmpty()) {
                "Noch keine Laufakte erzeugt."
            } else {
                currentLedger
            }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
        }
        val scroll = ScrollView(this).apply {
            addView(
                previewText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(title, fullWidthParams())
        root.addView(intro, fullWidthParams())
        root.addView(safetyText, fullWidthParams())
        root.addView(statusText, fullWidthParams())
        root.addView(dialogButton, fullWidthParams())
        root.addView(publicationButton, fullWidthParams())
        root.addView(releaseButton, fullWidthParams())
        root.addView(applyButton, fullWidthParams())
        root.addView(writeButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(backButton, fullWidthParams())
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)

        dialogButton.setOnClickListener { showRetentionDialog() }
        publicationButton.setOnClickListener {
            previewAction(
                ArKernelRetentionLedgerAction.AFTER_PUBLICATION)
        }
        releaseButton.setOnClickListener {
            previewAction(
                ArKernelRetentionLedgerAction.EXPLICIT_RELEASE)
        }
        applyButton.setOnClickListener { applyPreview() }
        writeButton.setOnClickListener { writeLedger() }
        shareButton.setOnClickListener { shareLedger() }
        backButton.setOnClickListener { finish() }
        shareButton.isEnabled = currentLedger.isNotEmpty()
        applyButton.isEnabled = currentAction != null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_RUN_ID, runId)
        outState.putString(STATE_MODE, selectedMode.name)
        outState.putBoolean(STATE_DELETE_KEY, deleteKeySelected)
        outState.putBoolean(STATE_DIALOG_SHOWN, dialogShown)
        outState.putString(STATE_ACTION_KIND, actionKind.name)
        outState.putBoolean(STATE_ACTION_APPLIED, actionApplied)
        outState.putString(STATE_LEDGER, currentLedger)
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun showRetentionDialog() {
        dialog?.dismiss()
        dialog = composition.showDialog(
            this,
            Locale.getDefault(),
            ArKernelRetentionUiCallbacks(
                onPolicyConfirmed = { policy ->
                    selectedMode = policy.mode
                    deleteKeySelected = policy.deleteEncryptionKey
                    dialogShown = true
                    announce(
                        "Aufbewahrungsmodus bestätigt: " +
                            policy.mode.name)
                },
                onCancelled = {
                    announce("Aufbewahrungs-Dialog abgebrochen")
                },
                onInvalid = { error ->
                    announce(
                        "Ungültige Auswahl: " +
                            (error.message ?: "unbekannt"))
                },
                onDismissed = {
                    dialog = null
                },
            ),
        )
        statusText.text = "Dialog geöffnet. Bitte Auswahl explizit " +
            "bestätigen oder abbrechen."
        announce("Aufbewahrungs-Dialog geöffnet")
    }

    private fun previewAction(kind: ArKernelRetentionLedgerAction) {
        val action = when (kind) {
            ArKernelRetentionLedgerAction.AFTER_PUBLICATION ->
                composition.afterPublication(true)
            ArKernelRetentionLedgerAction.EXPLICIT_RELEASE ->
                composition.release()
            ArKernelRetentionLedgerAction.NONE -> return
        }
        currentAction = action
        actionKind = kind
        actionApplied = false
        val message = composition.actionMessage(
            this, Locale.getDefault(), action)
        statusText.text = "Vorschau: $message\nNoch nicht angewendet; " +
            "Trockenlauf, unverified."
        announce("Retention-Vorschau erzeugt")
        applyButton.isEnabled = true
        currentLedger = ""
        previewText.text = "Vorschau erzeugt; bitte Laufakte schreiben."
        shareButton.isEnabled = false
    }

    private fun applyPreview() {
        val action = currentAction
        if (action == null) {
            statusText.text = "Zuerst eine Vorschau erzeugen."
            announce("Keine Retention-Vorschau vorhanden")
            return
        }
        val applied = composition.apply(action)
        actionApplied = applied
        statusText.text = "Trocken angewendet: $applied | " +
            "clearFrames=${sink.clearCalls}, " +
            "keyCalls=${sink.keyCalls}. Keine echten Daten gelöscht; " +
            "unverified."
        announce("Retention-Vorschau trocken angewendet")
        currentLedger = ""
        previewText.text = "Aktion trocken angewendet; " +
            "bitte Laufakte schreiben."
        shareButton.isEnabled = false
    }

    private fun writeLedger() {
        val bundle = try {
            ArKernelRetentionRunBundle(
                runId = runId,
                deviceToken = deviceToken,
                localeTag = Locale.getDefault().toLanguageTag(),
                selectedMode = ledgerMode(),
                deleteKeyAvailable =
                    sink.authenticatedEncryption,
                deleteKeySelected = deleteKeySelected,
                dialogShown = dialogShown,
                actionKind = actionKind,
                actionApplied = actionApplied,
            )
        } catch (error: IllegalArgumentException) {
            statusText.text = "Laufakte abgelehnt: ${error.message}"
            return
        }
        val text = try {
            ArKernelRetentionRunLedgerAdapter.encode(bundle)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val parsed = ArKernelRetentionRunLedgerAdapter.parse(text)
        if (!parsed.accepted || parsed.bundle == null) {
            statusText.text = "Fail-closed: ${parsed.rejection} – " +
                parsed.detail
            return
        }
        val report = ArKernelRetentionRunLedgerAdapter
            .summarize(parsed.bundle!!)
        currentLedger = text
        previewText.text = text
        statusText.text = "Laufakte geprüft | Modus: " +
            "${bundle.selectedMode.wireName()} | Aktion: " +
            "${report.actionKind.wireName()} | angewendet: " +
            "${report.actionApplied}\nTrockenlauf: " +
            "${report.dryRun}, explizite Bestätigung: " +
            "${report.explicitConfirmationRequired}, Gerätevalidierung " +
            "aus: ${report.physicalValidationDisabled}. " +
            "unverified."
        Thread {
            val written = writeTextToDownloads(
                "ARKRETENT1_${runId}.txt", text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $written"
            }
        }.start()
        Toast.makeText(
            this,
            "ARKRETENT1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        shareButton.isEnabled = true
        announce("ARKRETENT1-Laufakte gespeichert")
    }

    private fun shareLedger() {
        if (currentLedger.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentLedger)
        }
        startActivity(
            Intent.createChooser(intent, "ARKRETENT1 teilen"))
    }

    private fun ledgerMode(): ArKernelRetentionLedgerMode =
        when (selectedMode) {
            ReconstructionFrameRetentionMode.KEEP ->
                ArKernelRetentionLedgerMode.KEEP
            ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION ->
                ArKernelRetentionLedgerMode.DELETE_AFTER_PUBLICATION
            ReconstructionFrameRetentionMode.DELETE_ON_EXPLICIT_RELEASE ->
                ArKernelRetentionLedgerMode.DELETE_ON_EXPLICIT_RELEASE
        }

    private fun opaqueDeviceToken(): String = try {
        val raw = listOf(
            Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
            Build.BOARD, Build.HARDWARE,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        digest.take(8).joinToString("") { byte ->
            String.format(
                Locale.US, "%02x", byte.toInt() and 0xFF)
        }
    } catch (error: Exception) {
        "device.unknown"
    }

    private fun announce(text: String) {
        statusText.announceForAccessibility(text)
    }

    private fun labeledButton(
        label: String,
        description: String,
    ): Button = Button(this).apply {
        text = label
        textSize = 14f
        setAllCaps(false)
        contentDescription = description
        importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 6, 0, 0) }

    private fun writeTextToDownloads(
        fileName: String,
        text: String,
    ): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS +
                        "/ARKernelDiagnostics",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Downloads: Einfügen fehlgeschlagen"
            try {
                val stream = contentResolver.openOutputStream(uri)
                    ?: throw IOException("Kein Ausgabestream")
                stream.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                "Downloads/ARKernelDiagnostics/$fileName"
            } catch (error: Exception) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (ignored: Exception) {
                }
                "Downloads: ${error.javaClass.simpleName}: " +
                    error.message
            }
        } else {
            try {
                val dir = getExternalFilesDir(
                    Environment.DIRECTORY_DOCUMENTS)
                    ?: getExternalFilesDir(null)
                    ?: filesDir
                if (!dir.exists() && !dir.mkdirs() &&
                    !dir.isDirectory) {
                    return "App-Dateien: Verzeichnis nicht verfügbar"
                }
                val file = File(dir, fileName)
                file.writeText(text, Charsets.UTF_8)
                file.absolutePath
            } catch (error: Exception) {
                "App-Dateien: ${error.javaClass.simpleName}: " +
                    error.message
            }
        }
    }
}
