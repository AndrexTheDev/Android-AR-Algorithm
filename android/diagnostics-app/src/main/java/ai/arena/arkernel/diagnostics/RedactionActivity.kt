package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelFieldDiagnosticsRedactionBundle
import ai.arena.arkernel.ArKernelFieldDiagnosticsRedactionParseResult
import ai.arena.arkernel.ArKernelRedactionInput
import ai.arena.arkernel.ArKernelRedactionRun
import ai.arena.arkernel.ArKernelRedactionRunAdapter
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException

/**
 * Applies a bounded redaction plan to the artefacts before they are uploaded.
 *
 * The user pastes an `ARKREDACT1` plan (the built-in default covers all six
 * detectors), adds one artefact at a time and runs the redaction. The app
 * reports which detector fired how often, whether the artefact changed, and
 * whether anything the plan did not cover is still in the redacted text. Only
 * then may the redacted files be written out — still for a **manual** upload,
 * never automatic.
 *
 * `upload_admitted` is a gate, not a promise: it means none of the six
 * built-in detectors still matches. A value the catalogue does not recognise
 * passes straight through, and nothing here inspects the meaning of a number.
 */
class RedactionActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_PLAN = "redact.plan"
        const val STATE_NAME = "redact.name"
        const val STATE_ARTEFACT = "redact.artefact"
        const val STATE_REPORT = "redact.report"
        const val STATE_STATUS = "redact.status"
        const val MAX_ARTEFACT_CHARACTERS = 16_384
        const val MAX_ARTEFACTS = 16
        val inputs = ArrayList<ArKernelRedactionInput>()
    }

    private lateinit var planInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var artefactInput: EditText
    private lateinit var listText: TextView
    private lateinit var reportText: TextView
    private lateinit var statusText: TextView
    private lateinit var runButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private var lastRun: ArKernelRedactionRun? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) inputs.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "Redaktion & Upload-Vorbereitung (ARKREDRUN1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für Redaktion und Upload-Vorbereitung"
        }
        val version = TextView(this).apply {
            text = "Version 2.14-redact"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "1) Plan prüfen (Standard deckt alle sechs Erkenner " +
                "ab). 2) Pro Artefakt einen Namen eintragen und den " +
                "Text einfügen, dann „Artefakt hinzufügen“. " +
                "3) „Redaktion ausführen“. 4) Nur wenn " +
                "„freigegeben“ steht: „Redigierte Dateien " +
                "speichern“ und die Dateien selbst hier im Chat " +
                "hochladen."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der Redaktion"
        }
        val safety = TextView(this).apply {
            text = "Die App erkennt sechs Muster: MAC-Adresse, IPv4, " +
                "16-stellige Hex-Kennung, 15-stellige Gerätenummer, " +
                "absoluter Speicherpfad, E-Mail-Adresse. Alles andere " +
                "bleibt unangetastet – auch ein Name, den du in eine " +
                "Szenen-ID getippt hast, oder eine Zahl, die nur im " +
                "Zusammenhang ein Gerät verrät. „freigegeben“ " +
                "heißt nur: keiner dieser sechs Erkenner schlägt noch " +
                "an. Kein automatischer Upload, keine Inferenz, keine " +
                "Qualitätsaussage. Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Grenzen der Redaktion"
        }
        statusText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_STATUS)
                ?: "Noch kein Artefakt hinzugefügt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }

        val planLabel = TextView(this).apply {
            text = "ARKREDACT1-Plan (Standard deckt alle Erkenner ab):"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        val defaultPlan = ArKernelFieldDiagnosticsRedactionBundle
            .encode(ArKernelRedactionRunAdapter.defaultPlan())
        planInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(8_192))
            minLines = 6
            maxLines = 10
            isSingleLine = false
            setText(savedInstanceState?.getString(STATE_PLAN)
                ?: defaultPlan)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(8, 8, 8, 8)
            contentDescription = "Redaktionsplan"
        }
        val resetPlanButton = Button(this).apply {
            text = "Standardplan wiederherstellen"
            textSize = 14f
            setAllCaps(false)
        }

        val nameLabel = TextView(this).apply {
            text = "Artefakt-Name (z. B. ARKFEED1_1700000000000.txt):"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        nameInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            isSingleLine = true
            setText(savedInstanceState?.getString(STATE_NAME) ?: "")
            textSize = 13f
            contentDescription = "Name des Artefakts"
        }
        val artefactLabel = TextView(this).apply {
            text = "Artefakt-Text (ARKFDPOL1, ARKVALID1, …) einfügen:"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        artefactInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(
                InputFilter.LengthFilter(MAX_ARTEFACT_CHARACTERS))
            minLines = 5
            maxLines = 8
            isSingleLine = false
            setText(savedInstanceState?.getString(STATE_ARTEFACT) ?: "")
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(8, 8, 8, 8)
            contentDescription = "Eingefügter Artefakt-Text"
        }
        listText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#FFF7ED"))
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
            contentDescription = "Hinzugefügte Artefakte"
        }
        reportText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_REPORT)
                ?: "Noch keine Redaktion ausgeführt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
            contentDescription = "Ergebnis der Redaktion"
        }

        val addButton = Button(this).apply {
            text = "Artefakt hinzufügen"
            textSize = 15f
            setAllCaps(false)
        }
        runButton = Button(this).apply {
            text = "Redaktion ausführen"
            textSize = 15f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "Redigierte Dateien speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        shareButton = Button(this).apply {
            text = "ARKREDRUN1 teilen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        val resetButton = Button(this).apply {
            text = "Artefakte zurücksetzen"
            textSize = 15f
            setAllCaps(false)
        }

        root.addView(title)
        root.addView(version)
        root.addView(intro)
        root.addView(safety)
        root.addView(statusText)
        root.addView(planLabel)
        root.addView(planInput)
        root.addView(resetPlanButton, fullWidthParams())
        root.addView(nameLabel)
        root.addView(nameInput)
        root.addView(artefactLabel)
        root.addView(artefactInput)
        root.addView(addButton, fullWidthParams())
        root.addView(listText, fullWidthParams())
        root.addView(runButton, fullWidthParams())
        root.addView(reportText)
        root.addView(saveButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(resetButton, fullWidthParams())

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        resetPlanButton.setOnClickListener { planInput.setText(defaultPlan) }
        addButton.setOnClickListener { addArtefact() }
        runButton.setOnClickListener { runRedaction() }
        saveButton.setOnClickListener { saveRedacted() }
        shareButton.setOnClickListener { shareRun() }
        resetButton.setOnClickListener { resetArtefacts() }
        renderList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PLAN, planInput.text.toString())
        outState.putString(STATE_NAME, nameInput.text.toString())
        outState.putString(STATE_ARTEFACT, artefactInput.text.toString())
        outState.putString(STATE_REPORT, reportText.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    private fun addArtefact() {
        val name = nameInput.text.toString().trim()
        val text = artefactInput.text.toString()
        if (!name.matches(Regex("[A-Za-z0-9._:-]{1,64}"))) {
            status("Ungültiger Name: nur A-Z, a-z, 0-9, Punkt, " +
                "Unterstrich, Doppelpunkt, Bindestrich, maximal 64 Zeichen.")
            return
        }
        if (text.isBlank()) {
            status("Bitte zuerst einen Artefakt-Text einfügen.")
            return
        }
        if (inputs.size >= MAX_ARTEFACTS) {
            status("Maximal $MAX_ARTEFACTS Artefakte pro Lauf.")
            return
        }
        if (inputs.any { it.artifactName == name }) {
            status("Dieser Artefakt-Name ist schon hinzugefügt.")
            return
        }
        inputs += ArKernelRedactionInput(
            artifactName = name,
            sourceText = text,
        )
        lastRun = null
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        artefactInput.setText("")
        nameInput.setText("")
        renderList()
        status("Artefakt $name hinzugefügt.")
    }

    private fun runRedaction() {
        if (inputs.isEmpty()) {
            status("Bitte zuerst mindestens ein Artefakt hinzufügen.")
            return
        }
        val parsed: ArKernelFieldDiagnosticsRedactionParseResult =
            ArKernelFieldDiagnosticsRedactionBundle
                .parse(planInput.text.toString())
        if (!parsed.accepted) {
            status("Plan abgelehnt: ${parsed.rejection} " +
                "(Zeile ${parsed.lineNumber}: ${parsed.detail})")
            return
        }
        val plan = parsed.plan ?: return
        val result = ArKernelRedactionRunAdapter.apply(
            plan = plan,
            runId = "redact.${System.currentTimeMillis()}",
            inputs = inputs.toList(),
        )
        if (!result.accepted) {
            lastRun = null
            saveButton.isEnabled = false
            shareButton.isEnabled = false
            status("Redaktion abgelehnt: ${result.rejection} – " +
                result.detail)
            return
        }
        val run = result.run ?: return
        lastRun = run
        saveButton.isEnabled = true
        shareButton.isEnabled = true
        reportText.text = formatRun(run)
        status(if (run.uploadAdmitted) {
            "Redaktion fertig und freigegeben."
        } else {
            "Redaktion fertig, aber NICHT freigegeben: " +
                "${run.residualFindings} Restfunde."
        })
    }

    private fun formatRun(run: ArKernelRedactionRun): String {
        val lines = ArrayList<String>()
        lines += "Artefakte: ${run.artifacts.size}   " +
            "Treffer: ${run.totalMatches}   " +
            "Restfunde: ${run.residualFindings}"
        for (artifact in run.artifacts) {
            lines += if (artifact.changed) {
                "${artifact.artifactName}: geändert " +
                    "(${artifact.digestBefore.take(8)}… -> " +
                    "${artifact.digestAfter.take(8)}…)"
            } else {
                "${artifact.artifactName}: unverändert"
            }
        }
        for (finding in run.findings) {
            lines += "Treffer ${finding.detector.wireName()} " +
                "(${finding.action.wire}) x${finding.count} " +
                "in Artefakt ${finding.artifactIndex}"
        }
        for (residual in run.residuals) {
            lines += "RESTFUND ${residual.detector.wireName()} " +
                "x${residual.count} in Artefakt " +
                "${residual.artifactIndex}"
        }
        lines += if (run.uploadAdmitted) {
            "Freigabe: freigegeben (keiner der sechs Erkenner " +
                "schlägt mehr an)."
        } else {
            "Freigabe: NICHT freigegeben. Bitte den Plan um die " +
                "genannten Erkenner erweitern."
        }
        lines += "Hochladen musst du die Dateien selbst – " +
            "nie automatisch. Status: unverified."
        return lines.joinToString("\n")
    }

    private fun renderList() {
        if (inputs.isEmpty()) {
            listText.text = "Noch kein Artefakt hinzugefügt."
            return
        }
        val lines = ArrayList<String>(inputs.size + 1)
        lines += "Nr  Artefakt                     Zeichen"
        for ((index, input) in inputs.withIndex()) {
            lines += String.format(
                java.util.Locale.US,
                "%-3d %-28s %7d",
                index,
                input.artifactName,
                input.sourceText.length,
            )
        }
        listText.text = lines.joinToString("\n")
    }

    private fun resetArtefacts() {
        inputs.clear()
        lastRun = null
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        reportText.text = "Noch keine Redaktion ausgeführt."
        renderList()
        status("Artefakte zurückgesetzt.")
    }

    private fun saveRedacted() {
        val run = lastRun
        if (run == null) {
            status("Bitte zuerst die Redaktion ausführen.")
            return
        }
        val results = ArrayList<String>(run.artifacts.size + 1)
        for (artifact in run.artifacts) {
            results += writeTextToDownloads(
                "redacted_${artifact.artifactName}",
                artifact.redactedText,
            )
        }
        val stamp = System.currentTimeMillis()
        val ledgerName = "ARKREDRUN1_$stamp.txt"
        results += writeTextToDownloads(
            ledgerName,
            ArKernelRedactionRunAdapter.encode(run),
        )
        status(results.joinToString("\n"))
        Toast.makeText(this, "Redaktion gespeichert", Toast.LENGTH_SHORT)
            .show()
    }

    private fun shareRun() {
        val run = lastRun ?: return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKREDRUN1")
                putExtra(
                    Intent.EXTRA_TEXT,
                    ArKernelRedactionRunAdapter.encode(run),
                )
            }
            startActivity(Intent.createChooser(send, "ARKREDRUN1 teilen"))
        } catch (error: Exception) {
            status("Teilen fehlgeschlagen: ${error.message}")
        }
    }

    private fun status(message: String) {
        statusText.text = message
    }

    private fun writeTextToDownloads(fileName: String, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ARKernelDiagnostics",
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
}
