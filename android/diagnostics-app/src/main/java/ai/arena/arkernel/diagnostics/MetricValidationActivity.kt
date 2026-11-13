package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelCampaignSceneKind
import ai.arena.arkernel.ArKernelMeasurementCondition
import ai.arena.arkernel.ArKernelMeasurementFeedAdapter
import ai.arena.arkernel.ArKernelMetricValidationAdapter
import ai.arena.arkernel.ArKernelMetricValidationEntry
import ai.arena.arkernel.ArKernelMetricValidationReport
import ai.arena.arkernel.ArKernelRulerScaleAdapter
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException

private data class MetricValidationRecord(
    val entry: ArKernelMetricValidationEntry,
    val digest: String,
)

private data class ParsedEstimate(
    val estimateMm: Long,
    val sceneKind: ArKernelCampaignSceneKind,
    val condition: ArKernelMeasurementCondition,
    val heldOut: Boolean,
    val suggestedId: String,
)

/**
 * Metric validation: the app compares its own estimate with the length the
 * user measured with a tape measure, per scene, in whole millimetres.
 *
 * The user pastes one `ARKFEED1` or `ARKSCALE1` artefact per scene. The app
 * parses it, takes `estimated_target_m` from it and binds the scene to the
 * lower-case SHA-256 digest of that text. The reference length is typed in
 * manually; it is never measured by the app and never re-verified.
 *
 * The result is the **measured deviation**, not a promise of accuracy: mean
 * absolute error, signed bias, maximum error, p95 and the number of scenes
 * inside the declared threshold. `metric_accuracy_claimed` stays false and
 * the grade stays `unverified`. No image, frame, pixel, pose or trajectory is
 * read or written; no upload, no signature verification, no automatic or
 * universal calibration.
 */
class MetricValidationActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_PASTE = "valid.paste"
        const val STATE_SCENE = "valid.scene"
        const val STATE_TRUTH = "valid.truth"
        const val STATE_THRESHOLD = "valid.threshold"
        const val STATE_REPORT = "valid.report"
        const val STATE_ARTEFACT = "valid.artefact"
        const val STATE_FILE = "valid.file"
        const val STATE_STATUS = "valid.status"
        const val MAX_SOURCE_CHARACTERS = 16_384
        const val MIN_MILLIMETRES = 1L
        const val MAX_MILLIMETRES = 100_000L
        const val MIN_THRESHOLD = 1L
        const val MAX_THRESHOLD = 10_000L
        val KIND_NAMES = listOf(
            "indoor_scale", "outdoor_scale", "right_angle",
            "stillness", "thermal_repeat", "day_night",
            "held_out", "cross_session", "cross_device",
        )
        val CONDITION_NAMES = listOf(
            "indoor", "outdoor", "day", "night", "thermal", "still",
        )
        val records = ArrayList<MetricValidationRecord>()
    }

    private lateinit var pasteInput: EditText
    private lateinit var sceneInput: EditText
    private lateinit var truthInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var kindSpinner: Spinner
    private lateinit var conditionSpinner: Spinner
    private lateinit var heldOutBox: CheckBox
    private lateinit var readButton: Button
    private lateinit var addButton: Button
    private lateinit var computeButton: Button
    private lateinit var resetButton: Button
    private lateinit var writeButton: Button
    private lateinit var shareButton: Button
    private lateinit var statusText: TextView
    private lateinit var rowsText: TextView
    private lateinit var reportText: TextView

    private var currentArtefact: String = ""
    private var currentFileName: String = ""
    private var pendingEstimate: ParsedEstimate? = null
    private var pendingSource: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) records.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "Metrische Validierung (ARKVALID1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm zur metrischen Validierung"
        }
        val version = TextView(this).apply {
            text = "Version 2.12-valid"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "1) Mit Knopf 12 oder 13 messen und das Artefakt teilen. " +
                "2) Text hier einfügen. 3) „Artefakt lesen“ – die App " +
                "zeigt die Schätzung in Millimetern und füllt Szenenart " +
                "und Bedingung. 4) Dasselbe Ziel mit dem Maßband messen " +
                "und in Millimetern eintragen (z. B. 1500). " +
                "5) „Szene übernehmen“. 6) Für alle Szenen wiederholen, " +
                "dabei mindestens eine Szene als zurückgehalten " +
                "markieren. 7) „Validierung berechnen“."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der metrischen Validierung"
        }
        val safety = TextView(this).apply {
            text = "Das Ergebnis ist deine gemessene Abweichung, kein " +
                "Genauigkeitsversprechen: das Referenzmaß gibst du selbst " +
                "ein, die App prüft es nicht nach. Tiefe, Perspektive, " +
                "Rolling-Shutter und Verzeichnung bleiben unberücksichtigt. " +
                "Kein KI-Modell, keine Inferenz, kein Netz, keine " +
                "automatische/universelle Kalibrierung, keine " +
                "Signaturprüfung, kein automatischer Upload. " +
                "Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Grenzen der metrischen Validierung"
        }
        statusText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_STATUS)
                ?: "Noch keine Szene übernommen."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }

        val pasteLabel = TextView(this).apply {
            text = "ARKFEED1- oder ARKSCALE1-Artefakt (einfügen):"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        pasteInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(MAX_SOURCE_CHARACTERS))
            minLines = 6
            maxLines = 10
            isSingleLine = false
            setText(savedInstanceState?.getString(STATE_PASTE) ?: "")
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(8, 8, 8, 8)
            contentDescription = "Eingefügtes Messartefakt"
        }

        val sceneLabel = TextView(this).apply {
            text = "Szenen-ID (eindeutig, z. B. szene.wohnzimmer.1):"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        sceneInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            isSingleLine = true
            setText(savedInstanceState?.getString(STATE_SCENE) ?: "")
            textSize = 13f
            contentDescription = "Eindeutige Szenen-ID"
        }

        val truthLabel = TextView(this).apply {
            text = "Dein Maßband-Referenzmaß in Millimetern (1 – 100000):"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        truthInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            isSingleLine = true
            setText(savedInstanceState?.getString(STATE_TRUTH) ?: "")
            textSize = 13f
            contentDescription = "Referenzmaß in Millimetern"
        }

        val thresholdLabel = TextView(this).apply {
            text = "Toleranz in Millimetern (1 – 10000, Standard 10):"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        thresholdInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            isSingleLine = true
            setText(savedInstanceState?.getString(STATE_THRESHOLD) ?: "10")
            textSize = 13f
            contentDescription = "Toleranz in Millimetern"
        }

        val kindLabel = TextView(this).apply {
            text = "Szenenart:"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MetricValidationActivity,
                android.R.layout.simple_spinner_item,
                KIND_NAMES,
            ).apply {
                setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item)
            }
            contentDescription = "Szenenart"
        }
        val conditionLabel = TextView(this).apply {
            text = "Bedingung:"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        conditionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MetricValidationActivity,
                android.R.layout.simple_spinner_item,
                CONDITION_NAMES,
            ).apply {
                setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item)
            }
            contentDescription = "Bedingung"
        }

        readButton = Button(this).apply {
            text = "Artefakt lesen"
            textSize = 15f
            setAllCaps(false)
        }
        heldOutBox = CheckBox(this).apply {
            text = "Zurückgehaltene Szene (mindestens eine ist Pflicht)"
            textSize = 13f
            contentDescription = "Zurückgehaltene Szene"
        }

        addButton = Button(this).apply {
            text = "Szene übernehmen"
            textSize = 15f
            setAllCaps(false)
        }
        computeButton = Button(this).apply {
            text = "Validierung berechnen"
            textSize = 15f
            setAllCaps(false)
        }
        resetButton = Button(this).apply {
            text = "Szenen zurücksetzen"
            textSize = 15f
            setAllCaps(false)
        }
        writeButton = Button(this).apply {
            text = "ARKVALID1 speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = savedInstanceState
                ?.getString(STATE_ARTEFACT)?.isNotEmpty() ?: false
        }
        shareButton = Button(this).apply {
            text = "ARKVALID1 teilen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = writeButton.isEnabled
        }

        rowsText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#FFF7ED"))
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
            contentDescription = "Übernommene Szenen"
        }
        reportText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_REPORT)
                ?: "Noch keine Auswertung."
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
            contentDescription = "Ergebnis der Validierung"
        }

        currentArtefact = savedInstanceState?.getString(STATE_ARTEFACT) ?: ""
        currentFileName = savedInstanceState?.getString(STATE_FILE) ?: ""

        root.addView(title)
        root.addView(version)
        root.addView(intro)
        root.addView(safety)
        root.addView(statusText)
        root.addView(pasteLabel)
        root.addView(pasteInput)
        root.addView(readButton, fullWidthParams())
        root.addView(sceneLabel)
        root.addView(sceneInput)
        root.addView(truthLabel)
        root.addView(truthInput)
        root.addView(thresholdLabel)
        root.addView(thresholdInput)
        root.addView(kindLabel)
        root.addView(kindSpinner)
        root.addView(conditionLabel)
        root.addView(conditionSpinner)
        root.addView(heldOutBox)
        root.addView(addButton)
        root.addView(rowsText)
        root.addView(computeButton)
        root.addView(reportText)
        root.addView(resetButton)
        root.addView(writeButton)
        root.addView(shareButton)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        readButton.setOnClickListener { readArtefact() }
        addButton.setOnClickListener { addScene() }
        computeButton.setOnClickListener { computeValidation() }
        resetButton.setOnClickListener { resetScenes() }
        writeButton.setOnClickListener { writeArtefact() }
        shareButton.setOnClickListener { shareArtefact() }
        renderRows()
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PASTE, pasteInput.text.toString())
        outState.putString(STATE_SCENE, sceneInput.text.toString())
        outState.putString(STATE_TRUTH, truthInput.text.toString())
        outState.putString(STATE_THRESHOLD, thresholdInput.text.toString())
        outState.putString(STATE_REPORT, reportText.text.toString())
        outState.putString(STATE_ARTEFACT, currentArtefact)
        outState.putString(STATE_FILE, currentFileName)
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    private fun readArtefact() {
        val source = pasteInput.text.toString()
        if (source.isBlank()) {
            status("Bitte zuerst einen ARKFEED1- oder " +
                "ARKSCALE1-Text einfügen.")
            return
        }
        val parsed = parseEstimate(source) ?: return
        pendingEstimate = parsed
        pendingSource = source
        sceneInput.setText(parsed.suggestedId)
        kindSpinner.setSelection(
            KIND_NAMES.indexOf(parsed.sceneKind.wireName())
                .coerceAtLeast(0))
        conditionSpinner.setSelection(
            CONDITION_NAMES.indexOf(parsed.condition.wireName())
                .coerceAtLeast(0))
        heldOutBox.isChecked = parsed.heldOut
        val kind = if (source.trimStart().startsWith("ARKSCALE1")) {
            "ARKSCALE1"
        } else {
            "ARKFEED1"
        }
        status("$kind gelesen: Schätzung ${parsed.estimateMm} mm. " +
            "Bitte dasselbe Ziel mit dem Maßband messen und in " +
            "Millimetern eintragen.")
    }

    private fun addScene() {
        val source = pasteInput.text.toString()
        if (source.isBlank()) {
            status("Bitte zuerst einen ARKFEED1- oder " +
                "ARKSCALE1-Text einfügen.")
            return
        }
        if (pendingEstimate == null || pendingSource != source) {
            status("Bitte zuerst „Artefakt lesen“ drücken.")
            return
        }
        val parsed = pendingEstimate ?: return
        if (parsed.estimateMm < MIN_MILLIMETRES ||
            parsed.estimateMm > MAX_MILLIMETRES
        ) {
            status("Die Schätzung liegt außerhalb 1 – 100000 mm.")
            return
        }
        val typedScene = sceneInput.text.toString().trim()
        val sceneId = if (typedScene.isEmpty()) {
            parsed.suggestedId
        } else {
            typedScene
        }
        if (!sceneId.matches(Regex("[A-Za-z0-9._:-]{1,64}"))) {
            status("Ungültige Szenen-ID: nur A-Z, a-z, 0-9, Punkt, " +
                "Unterstrich, Doppelpunkt, Bindestrich, maximal 64 Zeichen.")
            return
        }
        val truthText = truthInput.text.toString().trim()
        val truthMm = truthText.toLongOrNull()
        if (truthMm == null || truthMm < MIN_MILLIMETRES ||
            truthMm > MAX_MILLIMETRES
        ) {
            status("Bitte das Referenzmaß in ganzen Millimetern " +
                "eintragen (1 – 100000).")
            return
        }
        if (records.any { it.entry.sceneId == sceneId }) {
            status("Diese Szenen-ID ist schon übernommen.")
            return
        }
        val digest = ArKernelMetricValidationAdapter.digestOf(source)
        if (records.any { it.digest == digest }) {
            status("Genau dieser Artefakt-Text ist schon übernommen. " +
                "Bitte eine neue Messung einfügen.")
            return
        }
        val kind = selectedKind()
        val condition = selectedCondition()
        records += MetricValidationRecord(
            entry = ArKernelMetricValidationEntry(
                sceneId = sceneId,
                kind = kind,
                condition = condition,
                heldOut = heldOutBox.isChecked,
                groundTruthMm = truthMm,
                estimateMm = parsed.estimateMm,
                sourceText = source,
            ),
            digest = digest,
        )
        currentArtefact = ""
        reportText.text = "Noch keine Auswertung."
        writeButton.isEnabled = false
        shareButton.isEnabled = false
        pasteInput.setText("")
        truthInput.setText("")
        sceneInput.setText("")
        pendingEstimate = null
        pendingSource = ""
        renderRows()
        status("Szene $sceneId übernommen: Schätzung " +
            "${parsed.estimateMm} mm, Referenz $truthMm mm.")
    }

    private fun computeValidation() {
        if (records.isEmpty()) {
            status("Bitte zuerst mindestens eine Szene übernehmen.")
            return
        }
        val thresholdText = thresholdInput.text.toString().trim()
        val threshold = thresholdText.toLongOrNull()
        if (threshold == null || threshold < MIN_THRESHOLD ||
            threshold > MAX_THRESHOLD
        ) {
            status("Bitte eine Toleranz in ganzen Millimetern eintragen " +
                "(1 – 10000).")
            return
        }
        if (!records.any { it.entry.heldOut }) {
            status("Mindestens eine Szene muss als zurückgehalten " +
                "markiert sein.")
            return
        }
        val result = ArKernelMetricValidationAdapter.create(
            validationId = "valid.${System.currentTimeMillis()}",
            deviceToken = "phone.diagnostics",
            campaignId = "campaign.local",
            thresholdMm = threshold,
            entries = records.map { it.entry },
        )
        if (!result.accepted) {
            currentArtefact = ""
            writeButton.isEnabled = false
            shareButton.isEnabled = false
            status("Abgelehnt: ${result.rejection} – ${result.detail}")
            return
        }
        val bundle = result.bundle ?: return
        val report = ArKernelMetricValidationAdapter.summarize(bundle)
        currentArtefact = ArKernelMetricValidationAdapter.encode(bundle)
        currentFileName = "ARKVALID1_${System.currentTimeMillis()}.txt"
        reportText.text = formatReport(report, threshold)
        writeButton.isEnabled = true
        shareButton.isEnabled = true
        status("ARKVALID1 berechnet. Bitte speichern und hier im Chat " +
            "hochladen.")
    }

    private fun resetScenes() {
        records.clear()
        currentArtefact = ""
        currentFileName = ""
        reportText.text = "Noch keine Auswertung."
        writeButton.isEnabled = false
        shareButton.isEnabled = false
        renderRows()
        status("Szenen zurückgesetzt.")
    }

    private fun renderRows() {
        if (records.isEmpty()) {
            rowsText.text = "Noch keine Szene übernommen."
            return
        }
        val lines = ArrayList<String>(records.size + 1)
        lines += "Nr  Szene                      Referenz  Schätzung  Fehler"
        for ((index, record) in records.withIndex()) {
            val entry = record.entry
            val error = entry.estimateMm - entry.groundTruthMm
            lines += String.format(
                java.util.Locale.US,
                "%-3d %-26s %8d %10d %7d",
                index,
                entry.sceneId,
                entry.groundTruthMm,
                entry.estimateMm,
                error,
            )
        }
        rowsText.text = lines.joinToString("\n")
    }

    private fun formatReport(
        report: ArKernelMetricValidationReport,
        thresholdMm: Long,
    ): String {
        val verdict = if (report.withinThreshold) {
            "Alle ${report.sceneCount} Szenen liegen innerhalb der " +
                "Toleranz von $thresholdMm mm."
        } else {
            "${report.withinThresholdCount} von ${report.sceneCount} " +
                "Szenen liegen innerhalb der Toleranz von $thresholdMm mm."
        }
        val direction = if (report.biasMm < 0L) {
            "im Mittel zu kurz"
        } else if (report.biasMm > 0L) {
            "im Mittel zu lang"
        } else {
            "im Mittel ohne systematische Abweichung"
        }
        return "Szenen: ${report.sceneCount}\n" +
            "Mittlere absolute Abweichung: ${report.meanAbsErrorMm} mm\n" +
            "Systematische Abweichung: ${report.biasMm} mm ($direction)\n" +
            "Größte Abweichung: ${report.maxAbsErrorMm} mm\n" +
            "95-Prozent-Abweichung: ${report.p95AbsErrorMm} mm\n" +
            "$verdict\n" +
            "Zurückgehaltene Szenen: ${report.heldOutCount}\n" +
            "Das ist die gemessene Abweichung gegen dein Maßband, " +
            "kein Genauigkeitsversprechen. Status: unverified."
    }

    private fun parseEstimate(source: String): ParsedEstimate? {
        val feed = ArKernelMeasurementFeedAdapter.parse(source)
        if (feed.accepted) {
            val bundle = feed.bundle ?: return null
            return ParsedEstimate(
                estimateMm = millimetresOf(bundle.estimatedTargetM),
                sceneKind = bundle.sceneKind,
                condition = bundle.condition,
                heldOut = bundle.heldOut,
                suggestedId = "${bundle.sessionId}.c${bundle.captureIndex}",
            )
        }
        val ruler = ArKernelRulerScaleAdapter.parse(source)
        if (ruler.accepted) {
            val bundle = ruler.bundle ?: return null
            return ParsedEstimate(
                estimateMm = millimetresOf(bundle.estimatedTargetM),
                sceneKind = bundle.sceneKind,
                condition = bundle.condition,
                heldOut = bundle.heldOut,
                suggestedId = "${bundle.sessionId}.c${bundle.captureIndex}",
            )
        }
        status("Der Text ist kein gültiges ARKFEED1- oder " +
            "ARKSCALE1-Artefakt: ${ruler.rejection} " +
            "(Zeile ${ruler.lineNumber}: ${ruler.detail})")
        return null
    }

    private fun millimetresOf(meters: Double): Long =
        java.lang.Math.round(meters * 1000.0)

    private fun selectedKind(): ArKernelCampaignSceneKind {
        val value = kindSpinner.selectedItem?.toString() ?: "indoor_scale"
        return ArKernelCampaignSceneKind.fromWire(value)
            ?: ArKernelCampaignSceneKind.INDOOR_SCALE
    }

    private fun selectedCondition(): ArKernelMeasurementCondition {
        val value = conditionSpinner.selectedItem?.toString() ?: "indoor"
        return ArKernelMeasurementCondition.fromWire(value)
            ?: ArKernelMeasurementCondition.INDOOR
    }

    private fun status(message: String) {
        statusText.text = message
    }

    private fun writeArtefact() {
        if (currentArtefact.isEmpty()) {
            status("Bitte zuerst die Validierung berechnen.")
            return
        }
        val fileName = if (currentFileName.isEmpty()) {
            "ARKVALID1_${System.currentTimeMillis()}.txt"
        } else {
            currentFileName
        }
        status(writeTextToDownloads(fileName, currentArtefact))
        Toast.makeText(this, "ARKVALID1 gespeichert", Toast.LENGTH_SHORT)
            .show()
    }

    private fun shareArtefact() {
        if (currentArtefact.isEmpty()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKVALID1")
                putExtra(Intent.EXTRA_TEXT, currentArtefact)
            }
            startActivity(Intent.createChooser(send, "ARKVALID1 teilen"))
        } catch (error: Exception) {
            status("Teilen fehlgeschlagen: ${error.message}")
        }
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
