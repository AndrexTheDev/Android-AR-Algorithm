package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ARK_ARTIFACT_MANIFEST_MAX_ENTRIES
import ai.arena.arkernel.ArKernelArtifactCatalogue
import ai.arena.arkernel.ArKernelArtifactContract
import ai.arena.arkernel.ArKernelArtifactFile
import ai.arena.arkernel.ArKernelArtifactInventory
import ai.arena.arkernel.ArKernelDatasetManifestBundle
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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

        /** More files than this is a different problem; the rest are reported. */
        const val MAX_FILES = 200

        /** Text kept per file for verification and display. */
        const val MAX_TEXT_PER_FILE = 65_536

        /** Total text held in memory across the whole scan. */
        const val MAX_TOTAL_TEXT = 2_097_152

        /** How much of one file the viewer shows. */
        const val VIEW_CHARS = 20_000

        val DATE_FORMAT = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY)
    }

    /** One file on disk, with as much of its text as was kept. */
    private data class Scanned(
        val file: ArKernelArtifactFile,
        val text: String,
        val truncated: Boolean,
    )

    /**
     * What was read from one file. The digest always covers the **whole**
     * file: a manifest that bound the digest of a truncated prefix could not
     * be checked by anyone who hashes the file themselves, which is the one
     * thing a digest list is for. Only the text is capped, because that is
     * what is held in memory.
     */
    private data class Payload(
        val text: String,
        val sizeBytes: Long,
        val sha256Hex: String,
        val textTruncated: Boolean,
    )

    /**
     * How much text the scan may hold in total. A folder of large files would
     * otherwise be read into memory whole; past the budget the scan stops and
     * says so rather than failing later.
     */
    private class Budget(var remaining: Int) {
        fun take(size: Int): Boolean {
            if (size > remaining) return false
            remaining -= size
            return true
        }
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

    private var scanned: List<Scanned> = emptyList()
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

    private fun runScan() {
        scanButton.isEnabled = false
        statusText.text = "Lese Bestand …"
        Thread {
            val found = ArrayList<Scanned>()
            val notes = ArrayList<String>()
            val budget = Budget(MAX_TOTAL_TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                scanMediaStore(found, notes, budget)
            }
            scanPrivateDirectory(found, notes, budget)
            found.sortWith(
                compareByDescending<Scanned> { it.file.modifiedMs }
                    .thenBy { it.file.name },
            )
            val overflow = if (found.size > MAX_FILES) {
                found.subList(MAX_FILES, found.size).map { it.file.name }
            } else {
                emptyList()
            }
            val kept = found.take(MAX_FILES)
            runOnUiThread {
                scanned = kept
                inventory = ArKernelArtifactCatalogue.inventory(
                    kept.map { ArKernelArtifactCatalogue.read(it.file, it.text) })
                render(notes, overflow)
                scanButton.isEnabled = true
            }
        }.start()
    }

    /** Files the app published through MediaStore on Android 10 and later. */
    private fun scanMediaStore(
        found: ArrayList<Scanned>,
        notes: ArrayList<String>,
        budget: Budget,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%ARKernelDiagnostics%")
        try {
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    if (found.size >= MAX_FILES) break
                    val name = cursor.getString(nameColumn) ?: continue
                    val size = cursor.getLong(sizeColumn)
                    val seconds = cursor.getLong(dateColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn),
                    )
                    val payload = readPayload(uri.open(), notes) ?: continue
                    add(found, notes, budget, name, seconds * 1000L, payload, size)
                }
            }
        } catch (error: Exception) {
            notes.add("MediaStore: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Older Android versions, and the app-private fallback the other screens
     * use when MediaStore is unavailable.
     */
    private fun scanPrivateDirectory(
        found: ArrayList<Scanned>,
        notes: ArrayList<String>,
        budget: Budget,
    ) {
        val dirs = ArrayList<File>()
        getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { dirs.add(it) }
        getExternalFilesDir(null)?.let { dirs.add(it) }
        for (dir in dirs.distinctBy { it.absolutePath }) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (found.size >= MAX_FILES) return
                if (!file.isFile) continue
                if (!file.name.endsWith(".txt")) continue
                if (found.any { it.file.name == file.name }) continue
                val payload = readPayload(
                    try {
                        file.inputStream()
                    } catch (error: Exception) {
                        notes.add("${file.name}: unlesbar")
                        continue
                    },
                    notes,
                ) ?: continue
                add(
                    found,
                    notes,
                    budget,
                    file.name,
                    file.lastModified(),
                    payload,
                    file.length(),
                )
            }
        }
    }

    private fun Uri.open(): java.io.InputStream? =
        try {
            contentResolver.openInputStream(this)
        } catch (error: Exception) {
            null
        }

    /**
     * Hashes the whole stream and keeps only the beginning as text.
     *
     * `declaredSize` is what the directory listing claimed; the digest is
     * taken over what was actually read, and a mismatch is reported rather
     * than silently bound.
     */
    private fun readPayload(
        stream: java.io.InputStream?,
        notes: ArrayList<String>,
    ): Payload? {
        if (stream == null) {
            notes.add("Lesefehler: keinen Stream bekommen")
            return null
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val head = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            stream.use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    digest.update(chunk, 0, read)
                    total += read
                    val room = MAX_TEXT_PER_FILE - head.size()
                    if (room > 0) head.write(chunk, 0, minOf(read, room))
                }
            }
            Payload(
                text = String(head.toByteArray(), Charsets.UTF_8),
                sizeBytes = total.toLong(),
                sha256Hex = hex(digest.digest()),
                textTruncated = total > MAX_TEXT_PER_FILE,
            )
        } catch (error: Exception) {
            notes.add("Lesefehler: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
        }

    private fun add(
        found: ArrayList<Scanned>,
        notes: ArrayList<String>,
        budget: Budget,
        name: String,
        modifiedMs: Long,
        payload: Payload,
        declaredSize: Long,
    ) {
        if (declaredSize > 0L && declaredSize != payload.sizeBytes) {
            notes.add(
                "$name: Größe weicht ab " +
                    "($declaredSize/${payload.sizeBytes})"
            )
        }
        if (!budget.take(payload.text.length)) {
            notes.add("$name: Textbudget erschöpft")
            return
        }
        try {
            found.add(
                Scanned(
                    file = ArKernelArtifactFile(
                        name = name,
                        sizeBytes = payload.sizeBytes,
                        modifiedMs = modifiedMs,
                        sha256Hex = payload.sha256Hex,
                    ),
                    text = payload.text,
                    truncated = payload.textTruncated,
                ),
            )
        } catch (error: IllegalArgumentException) {
            notes.add("$name: übersprungen (${error.message})")
        }
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
            ArKernelArtifactContract.DEVHW -> "Gerät & Laufzeit"
            ArKernelArtifactContract.MEAS -> "Geführte Messung"
            ArKernelArtifactContract.FEED -> "Kamerafeed-Marken"
            ArKernelArtifactContract.SCALE -> "Zollstock-Erkennung"
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
