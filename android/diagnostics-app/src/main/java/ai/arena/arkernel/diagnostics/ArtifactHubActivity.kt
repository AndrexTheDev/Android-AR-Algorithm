package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ARK_ARTIFACT_MANIFEST_MAX_ENTRIES
import ai.arena.arkernel.ArKernelArtifactCatalogue
import ai.arena.arkernel.ArKernelArtifactContract
import ai.arena.arkernel.ArKernelArtifactFile
import ai.arena.arkernel.ArKernelArtifactInventory
import ai.arena.arkernel.ArKernelDatasetManifestBundle
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers the question twenty screens could not: what is already here?
 *
 * Every other screen writes one file and forgets about it. This one lists the
 * files the app produced, hands each to its own contract's parser so a
 * truncated file is not counted as present, names the verdicts each file
 * reports as false, and says which contract to collect next.
 *
 * Three honest limits, shown on the screen rather than buried here. The scan
 * sees files this app wrote and, on Android 10 and later, only what the system
 * lets it see - a file copied in over USB may not appear. A present and
 * accepted file has been *collected*, which is not the same as being good. And
 * the manifest binds digests, which detect a change afterwards, not a forgery:
 * signing the manifest (Knopf 15) is what ties the bytes to a key.
 *
 * Nothing is uploaded, nothing is deleted, and no camera or sensor is opened.
 */
class ArtifactHubActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_STATUS = "hub.status"
        const val STATE_BOARD = "hub.board"
        const val STATE_LIST = "hub.list"
        const val STATE_MANIFEST = "hub.manifest"
        const val STATE_ID = "hub.id"
        const val STATE_VIEW = "hub.view"

        /** How much of one file the viewer shows. */
        const val VIEW_CHARS = 20_000

        val DATE_FORMAT = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY)
    }

    private lateinit var idInput: EditText
    private lateinit var boardText: TextView
    private lateinit var nextText: TextView
    private lateinit var listText: TextView
    private lateinit var viewText: TextView
    private lateinit var statusText: TextView
    private lateinit var fileSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var bindButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private var scanned: List<ArKernelScannedFile> = emptyList()
    private var inventory: ArKernelArtifactInventory? = null
    private var manifestText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        root.addView(TextView(this).apply {
            text = "Zentrale: Bestand & Manifest (ARKDSMAN1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für den Artefakt-Bestand"
        })
        root.addView(TextView(this).apply {
            text = "Version 2.19-hub"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        })
        root.addView(TextView(this).apply {
            text = "1) „Bestand aktualisieren“ – die App liest, was " +
                "sie selbst in Downloads/ARKernelDiagnostics " +
                "geschrieben hat. 2) „Manifest binden“ fasst " +
                "alle Dateien mit SHA-256 in eine " +
                "ARKDSMAN1-Datei. 3) Speichern und selbst " +
                "hochladen."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der Zentrale"
        })
        root.addView(TextView(this).apply {
            text = "Der Bestand ist eine Bestandsaufnahme, kein " +
                "Urteil: eine vorhandene und lesbare Datei ist " +
                "„gesammelt“, nicht „gut“. Offene Urteile " +
                "nennt die Tafel so, wie die Datei sie selbst " +
                "meldet – sie bedeuten Arbeit, nicht " +
                "zwangsläufig einen Fehler."
            textSize = 12f
            setTextColor(Color.parseColor("#B45309"))
            setPadding(0, 8, 0, 0)
            contentDescription = "Hinweis zur Bedeutung des Bestands"
        })

        idInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            isSingleLine = true
            setText(defaultManifestId())
            textSize = 13f
            contentDescription = "Kennung des Manifests"
        }
        root.addView(labelled("Manifest-Kennung", idInput))

        scanButton = Button(this).apply {
            text = "1) Bestand aktualisieren"
            textSize = 15f
            setAllCaps(false)
        }
        bindButton = Button(this).apply {
            text = "2) Manifest binden"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        saveButton = Button(this).apply {
            text = "3) Datei speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        shareButton = Button(this).apply {
            text = "4) Datei teilen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        root.addView(scanButton, fullWidthParams())
        root.addView(bindButton, fullWidthParams())
        root.addView(saveButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())

        boardText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 8, 0, 0)
        }
        root.addView(labelled("Bestand je Vertrag", boardText))

        nextText = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 0)
        }
        root.addView(nextText)

        listText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 8, 0, 0)
        }
        root.addView(labelled("Dateien", listText))

        fileSpinner = Spinner(this)
        root.addView(fileSpinner, fullWidthParams())

        root.addView(Button(this).apply {
            text = "Datei anzeigen"
            textSize = 15f
            setAllCaps(false)
            setOnClickListener { showSelectedFile() }
        }, fullWidthParams())

        viewText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 8, 0, 0)
        }
        root.addView(labelled("Inhalt", viewText))

        statusText = TextView(this).apply {
            text = "Noch kein Bestand gelesen."
            textSize = 12f
            setPadding(0, 12, 0, 0)
        }
        root.addView(statusText)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        scanButton.setOnClickListener { runScan() }
        bindButton.setOnClickListener { bindManifest() }
        saveButton.setOnClickListener { saveManifest() }
        shareButton.setOnClickListener { shareManifest() }

        restore(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STATUS, statusText.text.toString())
        outState.putString(STATE_BOARD, boardText.text.toString())
        outState.putString(STATE_LIST, listText.text.toString())
        outState.putString(STATE_MANIFEST, manifestText)
        outState.putString(STATE_ID, idInput.text.toString())
        outState.putString(STATE_VIEW, viewText.text.toString())
    }

    private fun restore(state: Bundle?) {
        if (state == null) return
        statusText.text = state.getString(STATE_STATUS) ?: ""
        boardText.text = state.getString(STATE_BOARD) ?: ""
        listText.text = state.getString(STATE_LIST) ?: ""
        manifestText = state.getString(STATE_MANIFEST) ?: ""
        val id = state.getString(STATE_ID)
        if (!id.isNullOrEmpty()) idInput.setText(id)
        viewText.text = state.getString(STATE_VIEW) ?: ""
        saveButton.isEnabled = manifestText.isNotEmpty()
        shareButton.isEnabled = manifestText.isNotEmpty()
    }

    // -------------------------------------------------------------- scanning

    /**
     * The scan itself lives in `ArKernelArtifactScanner`, shared with the
     * wizard: two screens that disagreed about which records exist would be
     * worse than one that was wrong, because the operator could not tell which
     * to believe.
     */
    private fun runScan() {
        scanButton.isEnabled = false
        statusText.text = "Lese Bestand …"
        Thread {
            val scan = ArKernelArtifactScanner(this).scan()
            runOnUiThread {
                scanned = scan.files
                inventory = ArKernelArtifactCatalogue.inventory(
                    scan.files.map { ArKernelArtifactCatalogue.read(it.file, it.text) })
                render(scan.notes, scan.overflow)
                scanButton.isEnabled = true
            }
        }.start()
    }

    // ------------------------------------------------------------- rendering

    private fun render(notes: List<String>, overflow: List<String>) {
        val inventory = this.inventory
        if (inventory == null) {
            statusText.text = "Bestand konnte nicht gelesen werden."
            return
        }
        boardText.text = renderBoard(inventory)
        listText.text = renderFiles(inventory)
        nextText.text = renderNext(inventory)
        val names = scanned.map { it.file.name }
        fileSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            names,
        )
        bindButton.isEnabled = scanned.isNotEmpty()
        val tail = ArrayList<String>()
        if (notes.isNotEmpty()) tail.add("Hinweise: ${notes.distinct().joinToString(", ")}")
        if (overflow.isNotEmpty()) {
            tail.add("${overflow.size} weitere Dateien nicht gelesen")
        }
        statusText.text = buildString {
            append("${scanned.size} Dateien, ")
            append("${inventory.totalBytes} Bytes, ")
            append("${inventory.missing.size} Verträge fehlen")
            if (inventory.rejectedCount > 0) {
                append(", ${inventory.rejectedCount} unlesbar")
            }
            append(".")
            if (tail.isNotEmpty()) append("\n").append(tail.joinToString("\n"))
        }
    }

    private fun renderBoard(inventory: ArKernelArtifactInventory): String {
        val lines = ArrayList<String>()
        lines.add("                      Vertrag      Kn  Dateien  Zuletzt")
        for (state in inventory.states) {
            val contract = state.contract
            val mark = when {
                !state.present -> "."
                state.rejectedCount > 0 -> "X"
                state.openVerdicts.isNotEmpty() -> "!"
                else -> "ok"
            }
            val count = if (state.present) "${state.fileCount}x" else "-"
            val whenText = if (state.present) {
                DATE_FORMAT.format(Date(state.newestModifiedMs))
            } else {
                "fehlt"
            }
            lines.add(
                "${mark.padEnd(3)}${contract.token.padEnd(13)}" +
                    "${contract.button.toString().padStart(2)}  " +
                    "${count.padEnd(8)}$whenText"
            )
            val detail = ArrayList<String>()
            if (state.rejectedCount > 0) {
                detail.add("${state.rejectedCount} unlesbar")
            }
            if (state.openVerdicts.isNotEmpty()) {
                detail.add(state.openVerdicts.joinToString(", "))
            }
            if (detail.isNotEmpty()) {
                lines.add("      ${germanPurpose(contract)}: " +
                    detail.joinToString("; "))
            }
        }
        return lines.joinToString("\n")
    }

    private fun renderFiles(inventory: ArKernelArtifactInventory): String {
        if (scanned.isEmpty()) {
            return "Keine Datei gefunden. Die Zentrale sieht nur, was " +
                "diese App geschrieben hat – auf Android 9 und " +
                "älter zusätzlich nur im App-Verzeichnis."
        }
        val lines = ArrayList<String>()
        for (item in scanned) {
            val reading = ArKernelArtifactCatalogue.read(item.file, item.text)
            val mark = when {
                !reading.accepted -> "X"
                reading.openVerdicts.isNotEmpty() -> "!"
                else -> "ok"
            }
            lines.add(
                "$mark ${item.file.name}  ${item.file.sizeBytes} B  " +
                    "${item.file.sha256Hex.take(12)}…"
            )
            if (!reading.accepted) {
                lines.add("    abgelehnt: ${reading.rejection}")
            } else if (reading.openVerdicts.isNotEmpty()) {
                lines.add("    offen: ${reading.openVerdicts.joinToString(", ")}")
            }
        }
        if (inventory.publicKeys.isNotEmpty()) {
            lines.add(
                "Schlüsselexporte (kein Vertrag, absichtlich nicht " +
                    "geprüft): ${inventory.publicKeys.joinToString(", ")}"
            )
        }
        val unrecognised = inventory.unrecognised
        if (unrecognised.isNotEmpty()) {
            lines.add("Ohne bekannten Vertrag: ${unrecognised.joinToString(", ")}")
        }
        return lines.joinToString("\n")
    }

    private fun renderNext(inventory: ArKernelArtifactInventory): String {
        val next = inventory.next
        if (next == null) {
            return "Alles gesammelt – als Nächstes: Manifest binden."
        }
        val state = inventory.states.first { it.contract == next }
        val reason = when {
            !state.present -> "noch keine Datei"
            state.rejectedCount > 0 -> "Datei unlesbar"
            else -> state.openVerdicts.joinToString(", ")
        }
        return "Nächster Schritt: Knopf ${next.button} – " +
            "${germanPurpose(next)} ($reason)"
    }

    private fun showSelectedFile() {
        val name = fileSpinner.selectedItem as? String ?: return
        val item = scanned.firstOrNull { it.file.name == name } ?: return
        val body = if (item.text.length > VIEW_CHARS) {
            item.text.substring(0, VIEW_CHARS) +
                "\n… ${item.text.length - VIEW_CHARS} weitere Zeichen"
        } else {
            item.text
        }
        val reading = ArKernelArtifactCatalogue.read(item.file, item.text)
        viewText.text = buildString {
            append(item.file.name)
            append("\n${item.file.sizeBytes} Bytes  ")
            append("SHA-256 ${item.file.sha256Hex}")
            append("\nVertrag: ${reading.contract?.token ?: "unbekannt"}")
            append("\nStatus: ")
            append(if (reading.accepted) "angenommen" else "abgelehnt (${reading.rejection})")
            if (item.truncated) append("\nAchtung: nur die ersten Zeichen gelesen")
            append("\n\n")
            append(body)
        }
        statusText.text = "${item.file.name} angezeigt."
    }

    // -------------------------------------------------------------- manifest

    private fun bindManifest() {
        if (scanned.isEmpty()) {
            statusText.text = "Bitte zuerst Bestand aktualisieren."
            return
        }
        val files = scanned.map { it.file }
        val result = ArKernelArtifactCatalogue.manifest(
            manifestId = idInput.text.toString(),
            files = files,
        )
        if (!result.accepted) {
            manifestText = ""
            saveButton.isEnabled = false
            shareButton.isEnabled = false
            statusText.text = "Manifest abgelehnt: ${result.error}"
            return
        }
        val manifest = result.manifest!!
        manifestText = ArKernelDatasetManifestBundle.encode(manifest)
        val parsed = ArKernelDatasetManifestBundle.parse(manifestText)
        saveButton.isEnabled = parsed.accepted
        shareButton.isEnabled = parsed.accepted
        statusText.text = buildString {
            append("Manifest mit ${manifest.entries.size} Einträgen, ")
            append("${files.sumOf { it.sizeBytes }} Bytes gebunden.")
            if (!parsed.accepted) {
                append(" WARNUNG: Rücklesen scheitert – ${parsed.detail}")
            }
            if (result.droppedEmpty > 0) {
                append(" ${result.droppedEmpty} leere Dateien übergangen.")
            }
            if (result.omitted.isNotEmpty()) {
                append(" Nicht aufgenommen (älteste zuerst): ")
                append(result.omitted.take(3).joinToString(", "))
                if (result.omitted.size > 3) {
                    append(" … ${result.omitted.size} insgesamt")
                }
                append(".")
            }
            if (files.size > ARK_ARTIFACT_MANIFEST_MAX_ENTRIES) {
                append(" Manifest fasst höchstens ")
                append(ARK_ARTIFACT_MANIFEST_MAX_ENTRIES)
                append(" Dateien.")
            }
        }
    }

    private fun saveManifest() {
        if (manifestText.isEmpty()) return
        val name = "ARKDSMAN1_${System.currentTimeMillis()}.txt"
        val path = writeTextToDownloads(name, manifestText)
        statusText.text = path
        Toast.makeText(this, "Manifest gespeichert", Toast.LENGTH_SHORT).show()
    }

    private fun shareManifest() {
        if (manifestText.isEmpty()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKDSMAN1")
                putExtra(Intent.EXTRA_TEXT, manifestText)
            }
            startActivity(Intent.createChooser(send, "ARKDSMAN1 teilen"))
        } catch (error: Exception) {
            statusText.text = "Teilen fehlgeschlagen: ${error.message}"
        }
    }

    // --------------------------------------------------------------- helpers

    private fun labelled(label: String, view: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ArtifactHubActivity).apply {
                text = label
                textSize = 13f
                setPadding(0, 12, 0, 4)
                setTypeface(null, Typeface.BOLD)
            })
            addView(view)
        }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    /**
     * Same opaque device token the other screens use: a digest over stable
     * build fields, so two runs on one phone share an id and the id says
     * nothing to anyone who reads it.
     */
    private fun opaqueDeviceToken(): String = try {
        val raw = listOf(
            Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
            Build.BOARD, Build.HARDWARE,
        ).joinToString("|")
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
            }.take(16)
    } catch (error: Exception) {
        "device"
    }

    private fun defaultManifestId(): String =
        ArKernelArtifactCatalogue.token("hub.${opaqueDeviceToken()}")

    /** Same opt-in publish path the other screens use; no upload anywhere. */
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
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return "Downloads: Einfügen fehlgeschlagen"
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
                "Downloads: ${error.javaClass.simpleName}: ${error.message}"
            }
        } else {
            try {
                val dir = getExternalFilesDir(
                    Environment.DIRECTORY_DOCUMENTS)
                    ?: getExternalFilesDir(null)
                    ?: filesDir
                if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
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

    private fun germanPurpose(contract: ArKernelArtifactContract): String =
        when (contract) {
            ArKernelArtifactContract.FDPOL -> "Diagnose-Richtlinie"
            ArKernelArtifactContract.FDPLAN -> "Testplan"
            ArKernelArtifactContract.FDLOG -> "Lauflog"
            ArKernelArtifactContract.ADAPT -> "Geräteklasse"
            ArKernelArtifactContract.CAMPAIGN -> "Messkampagne"
            ArKernelArtifactContract.CALIB -> "Physische Kalibrierung"
            ArKernelArtifactContract.IMUCAL -> "Trägheitskalibrierung"
            ArKernelArtifactContract.CAMTIM -> "Kameratakt & Fähigkeiten"
            ArKernelArtifactContract.CAMIMU ->
                "Kamera-IMU-Zeitversatz"
            ArKernelArtifactContract.CAMEXT ->
                "Kamera-IMU-Drehung"
            ArKernelArtifactContract.DEVHW -> "Gerät & Laufzeit"
            ArKernelArtifactContract.MEAS -> "Geführte Messung"
            ArKernelArtifactContract.FEED -> "Kamerafeed-Marken"
            ArKernelArtifactContract.SCALE -> "Zollstock-Erkennung"
            ArKernelArtifactContract.LADDER ->
                "Maßstabsleiter"
            ArKernelArtifactContract.COND -> "Bedingungen"
            ArKernelArtifactContract.TARGET -> "Unabhängige Targets"
            ArKernelArtifactContract.VALID -> "Metrische Validierung"
            ArKernelArtifactContract.CROSS -> "Gerätevergleich"
            ArKernelArtifactContract.CORPUS -> "Korpus-Index"
            ArKernelArtifactContract.BIND -> "Sitzungsbindung"
            ArKernelArtifactContract.RETENT -> "Aufbewahrung"
            ArKernelArtifactContract.SIGN -> "Signatur"
            ArKernelArtifactContract.REDRUN -> "Redaktion"
            ArKernelArtifactContract.DSMAN -> "Artefakt-Manifest"
        }
}
