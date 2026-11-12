package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelAdaptiveDeviceClass
import ai.arena.arkernel.ArKernelCampaignSceneKind
import ai.arena.arkernel.ArKernelCorpusEntry
import ai.arena.arkernel.ArKernelDeviceFarmCorpus
import ai.arena.arkernel.ArKernelDeviceFarmCorpusAdapter
import ai.arena.arkernel.ArKernelMeasurementCondition
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
import java.util.Locale

/**
 * Indexes a device-farm corpus and asks the question that decides whether a
 * cross-device comparison is possible at all: is the same cell present on more
 * than one device?
 *
 * A corpus can hold thousands of samples and still be unable to compare
 * anything, because every (condition, scene) cell in it was captured on a
 * single phone. `matchedCells` counts the cells at least two devices share;
 * `cross_device_ready` gates on them.
 *
 * The second question is dominance. A "device farm" in which one phone
 * supplies nine samples in ten is a single-device corpus with extra steps, so
 * the largest share any one device contributes is recomputed against a
 * ceiling the operator declared.
 *
 * This screen records which captures belong to a corpus and what the corpus
 * can support. It does not collect anything, does not run a comparison and
 * says nothing about whether the corpus is *good* — coverage and balance are
 * preconditions for a comparison, not evidence that one means anything.
 */
class DeviceFarmCorpusActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_CORPUS = "farm.corpus"
        const val STATE_REPORT = "farm.report"
        const val STATE_STATUS = "farm.status"
        const val MAX_ENTRIES = 64
        const val MAX_SAMPLES = 1_000_000L
        const val ID_PATTERN = "[A-Za-z0-9._:-]{1,64}"
        val entries = ArrayList<ArKernelCorpusEntry>()
    }

    private lateinit var corpusInput: EditText
    private lateinit var revisionInput: EditText
    private lateinit var requiredDevicesInput: EditText
    private lateinit var requiredConditionsInput: EditText
    private lateinit var requiredScenesInput: EditText
    private lateinit var requiredCellsInput: EditText
    private lateinit var maxShareInput: EditText
    private lateinit var captureInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var classSpinner: Spinner
    private lateinit var conditionSpinner: Spinner
    private lateinit var sceneSpinner: Spinner
    private lateinit var sampleInput: EditText
    private lateinit var listText: TextView
    private lateinit var reportText: TextView
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private var lastCorpus: ArKernelDeviceFarmCorpus? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) entries.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "Device-Farm-Korpus (ARKCORPUS1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für Device-Farm-Korpora"
        }
        val version = TextView(this).apply {
            text = "Version 2.17-corpus"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "1) Korpus benennen und festlegen, was er können " +
                "muss: wie viele Geräte, Bedingungen, Szenen und " +
                "vergleichbare Zellen, und wie viel Anteil ein " +
                "einzelnes Gerät höchstens haben darf. 2) Jede " +
                "vorhandene Aufnahme als Eintrag hinzufügen. " +
                "3) „Korpus auswerten“. 4) Datei speichern und " +
                "selbst hochladen."
            textSize = 13f
            contentDescription = "Bedienreihenfolge des Korpus-Index"
        }
        val safety = TextView(this).apply {
            text = "Dieser Bildschirm erfasst nichts – er führt nur " +
                "Buch über Aufnahmen, die es schon gibt. Die " +
                "entscheidende Frage ist die nach der " +
                "vergleichbaren Zelle: Eine Kombination aus " +
                "Bedingung und Szene, die nur ein einziges Gerät " +
                "aufgenommen hat, trägt keinen Gerätevergleich, " +
                "egal wie viele Samples sie enthält. Ebenso ist " +
                "eine „Farm“, in der ein Gerät fast alles " +
                "beisteuert, in Wahrheit ein Ein-Gerät-Korpus. " +
                "Abdeckung und Balance sind Voraussetzungen für " +
                "einen Vergleich – nicht der Beweis, dass er etwas " +
                "aussagt. Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Grenzen des Korpus-Index"
        }
        statusText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_STATUS)
                ?: "Noch kein Eintrag hinzugefügt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }

        corpusInput = textEdit(
            savedInstanceState?.getString(STATE_CORPUS) ?: "corpus.run1",
            "Name des Korpus",
        )
        revisionInput = intEdit("1", "Korpus-Revision", 7)
        requiredDevicesInput = intEdit("3", "Nötige Geräte", 2)
        requiredConditionsInput = intEdit("2", "Nötige Bedingungen", 1)
        requiredScenesInput = intEdit("2", "Nötige Szenen", 1)
        requiredCellsInput = intEdit("3", "Nötige vergleichbare Zellen", 2)
        maxShareInput = intEdit("500", "Maximaler Anteil in Promille", 4)

        captureInput = textEdit("", "Name der Aufnahme")
        deviceInput = textEdit("phone.a", "Geräte-Name")
        classSpinner = spinnerOf(
            ArKernelAdaptiveDeviceClass.entries
                .map { it.wireName() }
                .toTypedArray(),
            "Geräteklasse",
        )
        conditionSpinner = spinnerOf(
            ArKernelMeasurementCondition.entries
                .map { it.wireName() }
                .toTypedArray(),
            "Bedingung",
        )
        sceneSpinner = spinnerOf(
            ArKernelCampaignSceneKind.entries
                .map { it.wireName() }
                .toTypedArray(),
            "Szenenart",
        )
        sampleInput = intEdit("100", "Anzahl der Samples", 7)

        listText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F5F3FF"))
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
            contentDescription = "Hinzugefügte Korpus-Einträge"
        }
        reportText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_REPORT)
                ?: "Noch keine Auswertung erstellt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
            contentDescription = "Ergebnis der Korpus-Auswertung"
        }

        val addButton = Button(this).apply {
            text = "Eintrag hinzufügen"
            textSize = 15f
            setAllCaps(false)
        }
        val buildButton = Button(this).apply {
            text = "Korpus auswerten"
            textSize = 15f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "ARKCORPUS1-Datei speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        shareButton = Button(this).apply {
            text = "ARKCORPUS1 teilen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        val resetButton = Button(this).apply {
            text = "Einträge zurücksetzen"
            textSize = 15f
            setAllCaps(false)
        }

        root.addView(title)
        root.addView(version)
        root.addView(intro)
        root.addView(safety)
        root.addView(statusText)
        addLabelled(root, "Name des Korpus:", corpusInput)
        addLabelled(root, "Revision (1-1000000):", revisionInput)
        addLabelled(root, "Nötige Geräte (1-16):", requiredDevicesInput)
        addLabelled(
            root, "Nötige Bedingungen (1-6):", requiredConditionsInput)
        addLabelled(root, "Nötige Szenen (1-9):", requiredScenesInput)
        addLabelled(
            root, "Nötige vergleichbare Zellen (0-64):", requiredCellsInput)
        addLabelled(
            root, "Maximaler Geräte-Anteil in Promille (1-1000):",
            maxShareInput)
        addLabelled(root, "Name der Aufnahme:", captureInput)
        addLabelled(root, "Gerät:", deviceInput)
        addLabelled(root, "Geräteklasse:", classSpinner)
        addLabelled(root, "Bedingung:", conditionSpinner)
        addLabelled(root, "Szenenart:", sceneSpinner)
        addLabelled(root, "Samples (1-1000000):", sampleInput)
        root.addView(addButton, fullWidthParams())
        root.addView(listText, fullWidthParams())
        root.addView(buildButton, fullWidthParams())
        root.addView(reportText)
        root.addView(saveButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(resetButton, fullWidthParams())

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        addButton.setOnClickListener { addEntry() }
        buildButton.setOnClickListener { buildCorpus() }
        saveButton.setOnClickListener { saveCorpus() }
        shareButton.setOnClickListener { shareCorpus() }
        resetButton.setOnClickListener { resetEntries() }
        renderList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CORPUS, corpusInput.text.toString())
        outState.putString(STATE_REPORT, reportText.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    private fun addEntry() {
        val captureId = captureInput.text.toString().trim()
        val deviceToken = deviceInput.text.toString().trim()
        if (!captureId.matches(Regex(ID_PATTERN))) {
            status("Ungültiger Aufnahme-Name: nur A-Z, a-z, 0-9, Punkt, " +
                "Unterstrich, Doppelpunkt, Bindestrich.")
            return
        }
        if (!deviceToken.matches(Regex(ID_PATTERN))) {
            status("Ungültiger Geräte-Name.")
            return
        }
        if (entries.any { it.captureId == captureId }) {
            status("Diesen Aufnahme-Namen gibt es schon.")
            return
        }
        if (entries.size >= MAX_ENTRIES) {
            status("Maximal $MAX_ENTRIES Einträge pro Korpus.")
            return
        }
        val samples = sampleInput.text.toString().trim().toLongOrNull()
        if (samples == null || samples < 1L || samples > MAX_SAMPLES) {
            status("Samples müssen eine Zahl zwischen 1 und " +
                "$MAX_SAMPLES sein.")
            return
        }
        val deviceClass = ArKernelAdaptiveDeviceClass.fromWire(
            classSpinner.selectedItem.toString())
            ?: ArKernelAdaptiveDeviceClass.UNKNOWN
        val condition = ArKernelMeasurementCondition.fromWire(
            conditionSpinner.selectedItem.toString())
            ?: ArKernelMeasurementCondition.INDOOR
        val sceneKind = ArKernelCampaignSceneKind.fromWire(
            sceneSpinner.selectedItem.toString())
            ?: ArKernelCampaignSceneKind.INDOOR_SCALE
        entries += ArKernelCorpusEntry(
            index = entries.size,
            captureId = captureId,
            deviceToken = deviceToken,
            deviceClass = deviceClass,
            condition = condition,
            sceneKind = sceneKind,
            sampleCount = samples,
        )
        lastCorpus = null
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        captureInput.setText("")
        renderList()
        status("Eintrag $captureId hinzugefügt.")
    }

    private fun buildCorpus() {
        if (entries.isEmpty()) {
            status("Bitte zuerst mindestens einen Eintrag hinzufügen.")
            return
        }
        val revision = readInt(revisionInput, "Revision", 1_000_000, 1)
            ?: return
        val devices = readInt(requiredDevicesInput, "Nötige Geräte", 16, 1)
            ?: return
        val conditions =
            readInt(requiredConditionsInput, "Nötige Bedingungen", 6, 1)
                ?: return
        val scenes = readInt(requiredScenesInput, "Nötige Szenen", 9, 1)
            ?: return
        val cells = readInt(
            requiredCellsInput, "Nötige vergleichbare Zellen", 64, 0)
            ?: return
        val share = readInt(
            maxShareInput, "Maximaler Geräte-Anteil", 1_000, 1)
            ?: return
        val corpusId = corpusInput.text.toString().trim()
        if (!corpusId.matches(Regex(ID_PATTERN))) {
            status("Ungültiger Korpus-Name.")
            return
        }
        val result = ArKernelDeviceFarmCorpusAdapter.create(
            corpusId = corpusId,
            corpusRevision = revision,
            requiredDevices = devices,
            requiredConditions = conditions,
            requiredScenes = scenes,
            requiredMatchedCells = cells,
            maxDeviceSharePermille = share,
            entries = entries.toList(),
        )
        if (!result.accepted) {
            lastCorpus = null
            saveButton.isEnabled = false
            shareButton.isEnabled = false
            status("Abgelehnt: ${result.rejection} – ${result.detail}")
            return
        }
        val corpus = result.corpus ?: return
        lastCorpus = corpus
        saveButton.isEnabled = true
        shareButton.isEnabled = true
        reportText.text = formatCorpus(corpus)
        status(if (corpus.corpusSufficient) {
            "Korpus ausgewertet und ausreichend."
        } else {
            "Korpus ausgewertet, aber nicht ausreichend."
        })
    }

    private fun formatCorpus(corpus: ArKernelDeviceFarmCorpus): String {
        val lines = ArrayList<String>()
        lines += "Einträge: ${corpus.entryCount}   Geräte: " +
            "${corpus.deviceCount}   Klassen: " +
            "${corpus.deviceClassCount}"
        lines += "Bedingungen: ${corpus.conditionCount}   Szenen: " +
            "${corpus.sceneCount}   Samples insgesamt: " +
            "${corpus.sampleTotal}"
        lines += "Zellen: ${corpus.totalCells}   davon auf mindestens " +
            "zwei Geräten: ${corpus.matchedCells}"
        lines += "Größter Geräte-Anteil: " +
            "${corpus.observedMaxDeviceSharePermille} ‰ " +
            "(erlaubt: ${corpus.maxDeviceSharePermille} ‰) von " +
            corpus.dominantDeviceTokens().joinToString(", ")
        lines += if (corpus.deviceDiversityMet) {
            "+ Genug verschiedene Geräte."
        } else {
            "- Nur ${corpus.deviceCount} Geräte, nötig sind " +
                "${corpus.requiredDevices}."
        }
        lines += if (corpus.conditionsCovered) {
            "+ Genug verschiedene Bedingungen."
        } else {
            "- Nur ${corpus.conditionCount} Bedingungen, nötig sind " +
                "${corpus.requiredConditions}."
        }
        lines += if (corpus.scenesCovered) {
            "+ Genug verschiedene Szenen."
        } else {
            "- Nur ${corpus.sceneCount} Szenen, nötig sind " +
                "${corpus.requiredScenes}."
        }
        lines += if (corpus.balanceOk) {
            "+ Kein Gerät dominiert über der erlaubten Schwelle."
        } else {
            "- Ein Gerät trägt " +
                "${corpus.observedMaxDeviceSharePermille} ‰ bei, " +
                "erlaubt sind ${corpus.maxDeviceSharePermille} ‰."
        }
        val unmatched = corpus.unmatchedCells()
        lines += if (corpus.crossDeviceReady) {
            "+ Genug Zellen sind auf mindestens zwei Geräten belegt."
        } else {
            "- Nur ${corpus.matchedCells} vergleichbare Zellen, " +
                "nötig sind ${corpus.requiredMatchedCells}. " +
                "Allein erfasst: " +
                (if (unmatched.isEmpty()) {
                    "keine weiteren"
                } else {
                    unmatched.joinToString(", ") {
                        it.first.wireName() + "/" + it.second.wireName()
                    }
                })
        }
        lines += if (corpus.corpusSufficient) {
            "Korpus: ausreichend."
        } else {
            "Korpus: NICHT ausreichend."
        }
        lines += "Das sagt, dass ein Gerätevergleich überhaupt möglich " +
            "wäre – nicht, dass er etwas über Genauigkeit aussagt. " +
            "Status: unverified."
        return lines.joinToString("\n")
    }

    private fun renderList() {
        if (entries.isEmpty()) {
            listText.text = "Noch kein Eintrag hinzugefügt."
            return
        }
        val lines = ArrayList<String>(entries.size + 1)
        lines += String.format(
            Locale.US,
            "%-3s %-12s %-12s %-8s %-8s %-16s %8s",
            "Nr", "Aufnahme", "Gerät", "Klasse", "Bedingung", "Szene",
            "Samples")
        for (entry in entries) {
            lines += String.format(
                Locale.US,
                "%-3d %-12s %-12s %-8s %-8s %-16s %8d",
                entry.index,
                entry.captureId,
                entry.deviceToken,
                entry.deviceClass.wireName(),
                entry.condition.wireName(),
                entry.sceneKind.wireName(),
                entry.sampleCount,
            )
        }
        listText.text = lines.joinToString("\n")
    }

    private fun readInt(
        input: EditText,
        label: String,
        maximum: Int,
        minimum: Int,
    ): Int? {
        val value = input.text.toString().trim().toIntOrNull()
        if (value == null || value < minimum || value > maximum) {
            status("$label muss eine Zahl zwischen $minimum und " +
                "$maximum sein.")
            return null
        }
        return value
    }

    private fun resetEntries() {
        entries.clear()
        lastCorpus = null
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        reportText.text = "Noch keine Auswertung erstellt."
        renderList()
        status("Einträge zurückgesetzt.")
    }

    private fun saveCorpus() {
        val corpus = lastCorpus
        if (corpus == null) {
            status("Bitte zuerst auswerten.")
            return
        }
        val name = "ARKCORPUS1_${System.currentTimeMillis()}.txt"
        val path = writeTextToDownloads(
            name,
            ArKernelDeviceFarmCorpusAdapter.encode(corpus),
        )
        status(path)
        Toast.makeText(this, "Korpus gespeichert", Toast.LENGTH_SHORT)
            .show()
    }

    private fun shareCorpus() {
        val corpus = lastCorpus ?: return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKCORPUS1")
                putExtra(
                    Intent.EXTRA_TEXT,
                    ArKernelDeviceFarmCorpusAdapter.encode(corpus),
                )
            }
            startActivity(
                Intent.createChooser(send, "ARKCORPUS1 teilen"))
        } catch (error: Exception) {
            status("Teilen fehlgeschlagen: ${error.message}")
        }
    }

    private fun status(message: String) {
        statusText.text = message
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    private fun addLabelled(
        root: LinearLayout,
        label: String,
        view: View,
    ) {
        root.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(0, 8, 0, 4)
        })
        root.addView(view)
    }

    private fun textEdit(initial: String, description: String): EditText =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            isSingleLine = true
            setText(initial)
            textSize = 13f
            contentDescription = description
        }

    private fun intEdit(
        initial: String,
        description: String,
        maximum: Int = 9,
    ): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(maximum))
        isSingleLine = true
        setText(initial)
        textSize = 13f
        contentDescription = description
    }

    private fun spinnerOf(
        values: Array<String>,
        description: String,
    ): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@DeviceFarmCorpusActivity,
            android.R.layout.simple_spinner_dropdown_item,
            values,
        )
        contentDescription = description
    }

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
}
