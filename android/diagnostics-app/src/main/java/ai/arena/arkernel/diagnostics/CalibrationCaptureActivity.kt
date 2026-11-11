package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelCalibrationEvidenceAdapter
import ai.arena.arkernel.ArKernelCalibrationEvidenceBundle
import ai.arena.arkernel.ArKernelCalibrationObservation
import ai.arena.arkernel.ArKernelCalibrationPose
import ai.arena.arkernel.ArKernelCalibrationProvenance
import android.content.ContentValues
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import java.util.Locale
import kotlin.math.sqrt

/**
 * Guided, explicitly opt-in physical calibration capture screen.
 *
 * The user performs the measurement: a known independent length (scale bar,
 * surveyed target or sensor reference) is compared with a length reported by
 * a reconstruction, and an optional device rotation-vector sample records a
 * real orientation. Everything is written as a bounded `ARKCALIB1` text file.
 *
 * This screen does not capture images, pixels, frames or dense geometry, does
 * not infer camera intrinsics or extrinsics, does not compute a metric pose
 * from an orientation, performs no automatic calibration, verifies nothing,
 * uploads nothing and grants no quality grade. A passing readiness pre-check
 * is not a quality assessment; the grade stays `unverified` until an
 * independent evaluator runs with real evidence.
 */
class CalibrationCaptureActivity : android.app.Activity(), SensorEventListener {

    private lateinit var referenceInput: EditText
    private lateinit var measuredInput: EditText
    private lateinit var uncertaintyInput: EditText
    private lateinit var extentInput: EditText
    private lateinit var poseUncertaintyInput: EditText
    private lateinit var provenanceSpinner: Spinner
    private lateinit var heldOutBox: CheckBox
    private lateinit var optInBox: CheckBox
    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var addButton: Button
    private lateinit var poseButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private val observations =
        ArrayList<ArKernelCalibrationObservation>()
    private val poses = ArrayList<ArKernelCalibrationPose>()
    private val runId = "calib." + System.currentTimeMillis()

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var listening = false
    private var sensorAttempts = 0
    private var lastTimestampNs = 0L
    private var currentEvidence: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(
            android.content.Context.SENSOR_SERVICE,
        ) as? SensorManager
        rotationSensor = sensorManager
            ?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "Physische Kalibrierung (ARKCALIB1)"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val intro = TextView(this).apply {
            text = "Du führst die Messung selbst durch: Eine unabhängig bekannte " +
                "Länge (Maßstab, vermessenes Target, Sensor-Referenz) wird mit " +
                "einer vom Kern gemessenen Länge verglichen. Zusätzlich kann " +
                "eine echte Geräteorientierung aus dem Rotationssensor erfasst " +
                "werden. Daraus entsteht eine reine Textdatei. Es gibt keine " +
                "automatische oder universelle Kalibrierung: Kalibrierung gilt " +
                "immer nur für dieses Gerät und diese echten Messwerte."
            textSize = 13f
        }

        val safetyText = TextView(this).apply {
            text = "Keine Bilder, Pixel, Trajektorien oder dichte Geometrie. " +
                "Orientierungs-Proben sind keine metrischen 6-DoF-Posen und " +
                "begründen keinen Maßstab. Die Vorprüfung ist keine " +
                "Qualitätsnote; die Note bleibt unverified, bis echte " +
                "unabhängige Evidenz im Kern bewertet wurde. Kein Upload."
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#555555"))
        }

        optInBox = CheckBox(this).apply {
            text = "Ich messe selbst und stimme der begrenzten Erfassung zu."
            textSize = 14f
        }

        statusText = TextView(this).apply {
            text = "Bitte Opt-in anhaken. Noch keine Messung erfasst."
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }

        referenceInput = numberField("Unabhängige Referenzlänge (m), z. B. 0.297", "0.297")
        measuredInput = numberField("Vom Kern gemessene Länge (m), z. B. 0.299", "0.299")
        uncertaintyInput = numberField("Unsicherheit der Referenz (m), z. B. 0.001", "0.001")
        extentInput = numberField("Größte Ausdehnung des Objekts (m), z. B. 2", "2")
        poseUncertaintyInput = numberField(
            "Orientierungs-Unsicherheit (host-angegeben, 0–1), z. B. 0.05", "0.05")

        provenanceSpinner = Spinner(this)
        val provenanceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                "Maßstab (scale_bar)",
                "Vermessenes Target (survey)",
                "Sensor-Referenz (sensor_ground_truth)",
            ),
        )
        provenanceAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item)
        provenanceSpinner.adapter = provenanceAdapter

        heldOutBox = CheckBox(this).apply {
            text = "Held-out: Diese Messung wurde NICHT zum Kalibrieren benutzt."
            textSize = 13f
            isChecked = true
        }

        addButton = Button(this).apply {
            text = "1. Messung hinzufügen"
            textSize = 15f
            setAllCaps(false)
        }
        poseButton = Button(this).apply {
            text = "2. Orientierungs-Probe erfassen (optional)"
            textSize = 15f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "3. ARKCALIB1 prüfen & speichern"
            textSize = 15f
            setAllCaps(false)
        }
        shareButton = Button(this).apply {
            text = "ARKCALIB1 teilen / hier im Chat hochladen"
            textSize = 15f
            setAllCaps(false)
        }

        previewText = TextView(this).apply {
            text = "Noch keine Evidenz erzeugt."
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setBackgroundColor(android.graphics.Color.parseColor("#F2F2F2"))
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
        root.addView(optInBox, fullWidthParams())
        root.addView(statusText, fullWidthParams())
        root.addView(label("Herkunft des unabhängigen Wertes"), fullWidthParams())
        root.addView(provenanceSpinner, fullWidthParams())
        root.addView(referenceInput, fullWidthParams())
        root.addView(measuredInput, fullWidthParams())
        root.addView(uncertaintyInput, fullWidthParams())
        root.addView(extentInput, fullWidthParams())
        root.addView(poseUncertaintyInput, fullWidthParams())
        root.addView(heldOutBox, fullWidthParams())
        root.addView(addButton, fullWidthParams())
        root.addView(poseButton, fullWidthParams())
        root.addView(saveButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)

        optInBox.setOnCheckedChangeListener { _, _ -> updateButtons() }
        addButton.setOnClickListener { addObservation() }
        poseButton.setOnClickListener { captureOrientation() }
        saveButton.setOnClickListener { buildAndSave() }
        shareButton.setOnClickListener { shareEvidence() }
        updateButtons()
    }

    override fun onPause() {
        stopListening()
        super.onPause()
    }

    private fun numberField(hint: String, initial: String): EditText =
        EditText(this).apply {
            setHint(hint)
            setText(initial)
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(InputFilter.LengthFilter(16))
            textSize = 13f
        }

    private fun label(text: String): TextView = TextView(this).apply {
        setText(text)
        textSize = 13f
        setPadding(0, 12, 0, 4)
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    private fun updateButtons() {
        val allowed = optInBox.isChecked
        provenanceSpinner.isEnabled = allowed
        referenceInput.isEnabled = allowed
        measuredInput.isEnabled = allowed
        uncertaintyInput.isEnabled = allowed
        extentInput.isEnabled = allowed
        poseUncertaintyInput.isEnabled = allowed
        heldOutBox.isEnabled = allowed
        addButton.isEnabled = allowed &&
            observations.size < 16
        poseButton.isEnabled = allowed &&
            poses.size < 32 && rotationSensor != null
        saveButton.isEnabled = allowed &&
            observations.isNotEmpty()
        val hasEvidence = allowed && currentEvidence.isNotEmpty()
        shareButton.isEnabled = hasEvidence
    }

    private fun decimalField(field: EditText, name: String): Double? {
        val raw = field.text.toString().trim()
        if (!raw.matches(Regex("(0|[1-9][0-9]{0,3})(\\.[0-9]{1,6})?"))) {
            statusText.text = "$name: nur kanonische Dezimalzahlen (max. 6 Stellen)."
            return null
        }
        val parsed = raw.toDoubleOrNull()
        if (parsed == null || parsed.isNaN() || parsed.isInfinite()) {
            statusText.text = "$name: ungültige Zahl."
            return null
        }
        return parsed
    }

    private fun addObservation() {
        if (!optInBox.isChecked) return
        val reference = decimalField(referenceInput, "Referenzlänge") ?: return
        val measured = decimalField(measuredInput, "Gemessene Länge") ?: return
        val uncertainty = decimalField(uncertaintyInput, "Unsicherheit") ?: return
        if (!heldOutBox.isChecked) {
            statusText.text = "Held-out muss aktiv bleiben: sonst ist die Evidenz wertlos."
            return
        }
        if (reference <= 0.0 || reference > 100.0 ||
            measured > 100.0 || uncertainty > 1.0) {
            statusText.text = "Werte außerhalb der Grenzen " +
                "(0–100 m, Unsicherheit 0–1)."
            return
        }
        val provenance = when (provenanceSpinner.selectedItemPosition) {
            0 -> ArKernelCalibrationProvenance.SCALE_BAR
            1 -> ArKernelCalibrationProvenance.SURVEY
            else -> ArKernelCalibrationProvenance.SENSOR_GROUND_TRUTH
        }
        val index = observations.size
        val observation = try {
            ArKernelCalibrationObservation(
                index,
                "obs-" + (index + 1).toString().padStart(2, '0'),
                provenance,
                reference,
                measured,
                uncertainty,
                true,
            )
        } catch (error: IllegalArgumentException) {
            statusText.text = "Messung abgelehnt: ${error.message}"
            return
        }
        observations += observation
        currentEvidence = ""
        statusText.text = "Messung ${index + 1} erfasst (${provenance.wireName()}). " +
            "unverified; noch keine Bewertung."
        previewText.text = observations.joinToString("\n") {
            "${it.evidenceId}: ${it.referenceMeters} m → " +
                "${it.measuredMeters} m (${it.provenance.wireName()})"
        }
        updateButtons()
    }

    private fun captureOrientation() {
        if (!optInBox.isChecked) return
        val uncertainty = decimalField(poseUncertaintyInput, "Orientierungs-Unsicherheit")
            ?: return
        if (uncertainty > 1.0) {
            statusText.text = "Orientierungs-Unsicherheit muss 0–1 sein."
            return
        }
        val manager = sensorManager
        val sensor = rotationSensor
        if (manager == null || sensor == null) {
            statusText.text = "Kein Rotationssensor auf diesem Gerät verfügbar."
            return
        }
        sensorAttempts = 0
        listening = true
        poseButton.isEnabled = false
        statusText.text = "Warte auf eine echte Orientierungs-Probe …"
        if (!manager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            listening = false
            statusText.text = "Rotationssensor konnte nicht registriert werden."
            updateButtons()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) {
            return
        }
        sensorAttempts += 1
        val timestamp = event.timestamp
        if (timestamp <= lastTimestampNs ||
            poses.size >= 32) {
            if (sensorAttempts >= 8 || poses.size >= 32) {
                stopListening()
                statusText.text = "Keine verwertbare Orientierungs-Probe erhalten."
                updateButtons()
            }
            return
        }
        val values = event.values
        if (values.size < 3) return
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        val w = if (values.size >= 4) {
            values[3].toDouble()
        } else {
            sqrt(maxOf(0.0, 1.0 - x * x - y * y - z * z))
        }
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (norm <= 0.0 || norm.isNaN() || norm.isInfinite()) {
            return
        }
        val uncertainty = poseUncertaintyInput.text.toString()
            .trim().toDoubleOrNull() ?: return
        val pose = try {
            ArKernelCalibrationPose(
                poses.size,
                timestamp,
                0.0, 0.0, 0.0,
                w / norm, x / norm, y / norm, z / norm,
                uncertainty,
                false,
            )
        } catch (error: IllegalArgumentException) {
            stopListening()
            statusText.text = "Orientierungs-Probe abgelehnt: ${error.message}"
            updateButtons()
            return
        }
        poses += pose
        lastTimestampNs = timestamp
        stopListening()
        statusText.text = "Orientierungs-Probe ${poses.size} erfasst " +
            "(nur Orientierung, keine metrische Position). unverified."
        updateButtons()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Intentionally ignored: accuracy is not a quality grade.
    }

    private fun stopListening() {
        if (listening) {
            try {
                sensorManager?.unregisterListener(this)
            } catch (ignored: Exception) {
            }
            listening = false
        }
    }

    private fun buildAndSave() {
        if (!optInBox.isChecked || observations.isEmpty()) return
        val extent = decimalField(extentInput, "Objekt-Ausdehnung") ?: return
        if (extent <= 0.0 || extent > 100.0) {
            statusText.text = "Objekt-Ausdehnung muss 0–100 m sein."
            return
        }
        val bundle = try {
            ArKernelCalibrationEvidenceBundle(
                sessionId = runId,
                deviceToken = "device.host.v1",
                observations = observations.toList(),
                poses = poses.toList(),
                assetExtentMeters = extent,
            )
        } catch (error: IllegalArgumentException) {
            statusText.text = "Evidenz abgelehnt: ${error.message}"
            return
        }
        val text = try {
            ArKernelCalibrationEvidenceAdapter.encode(bundle)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val parsed = ArKernelCalibrationEvidenceAdapter.parse(text)
        if (!parsed.accepted || parsed.bundle == null) {
            statusText.text = "Fail-closed: ${parsed.rejection} – ${parsed.detail}"
            return
        }
        val report = ArKernelCalibrationEvidenceAdapter.summarize(
            parsed.bundle!!)
        currentEvidence = text
        val readyText = if (report.evaluationReady) {
            "Vorprüfung bestanden – Daten zur Auswertung hochladen."
        } else {
            "Vorprüfung nicht bestanden: weitere unabhängige Messungen nötig."
        }
        previewText.text = text
        statusText.text = String.format(
            Locale.US,
            "%d Messungen, %d Orientierungs-Proben, mittlerer Fehler %.6f m. " +
                "%s Qualität: unverified (keine Aufstufung).",
            report.observationCount,
            report.poseCount,
            report.meanAbsoluteErrorMeters,
            readyText,
        )
        Thread {
            val result = writeTextToDownloads(
                "ARKCALIB1_${runId}.txt", text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $result"
            }
        }.start()
        Toast.makeText(
            this,
            "ARKCALIB1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        updateButtons()
    }

    private fun shareEvidence() {
        if (currentEvidence.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentEvidence)
        }
        startActivity(Intent.createChooser(intent, "ARKCALIB1 teilen"))
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
                    Environment.DIRECTORY_DOWNLOADS + "/ARKernelDiagnostics",
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
                "Downloads: ${error.javaClass.simpleName}: ${error.message}"
            }
        } else {
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: getExternalFilesDir(null)
                    ?: filesDir
                if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                    return "App-Dateien: Verzeichnis nicht verfügbar"
                }
                File(dir, fileName).writeText(text, Charsets.UTF_8)
                File(dir, fileName).absolutePath
            } catch (error: Exception) {
                "App-Dateien: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
    }
}
