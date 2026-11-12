package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelAdaptiveBundle
import ai.arena.arkernel.ArKernelAdaptiveDeviceFacts
import ai.arena.arkernel.ArKernelAdaptiveStep
import ai.arena.arkernel.ArKernelAdaptiveStepStatus
import ai.arena.arkernel.ArKernelDeviceAdaptiveProfileAdapter
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.view.Gravity
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
 * Device-adaptive, systematic calibration onboarding screen.
 *
 * On every start the app reads bounded, non-personal device facts (Camera2
 * hardware level, camera count, gyro/rotation-vector presence, thermal
 * monitoring availability, RAM class, API level) and deterministically selects
 * a bounded `ARKADAPT1` profile. The same procedure therefore runs on every
 * Android device; the resulting calibration values remain device-specific.
 *
 * Automatic: device recognition and profile selection. Not automatic: metric
 * calibration. Android offers no install-time calibration hook, and no
 * universal calibration exists: real independent targets and the user's tape
 * measure are required. Step statuses are host-reported confirmations only.
 * The screen captures no images, pixels, poses or trajectories, infers no
 * intrinsics or metric scale, performs no automatic calibration, verifies no
 * signature, uploads nothing and grants no quality grade. Quality stays
 * `unverified`.
 */
class AdaptiveCalibrationActivity : DiagnosticsActivity() {

    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var confirmButton: Button
    private lateinit var skipButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private val runId = "adapt." + System.currentTimeMillis()
    private val steps = ArrayList<ArKernelAdaptiveStep>()
    private var currentIndex = 0
    private var currentEvidence: String = ""
    private var deviceToken: String = "device.unknown"
    private var facts: ArKernelAdaptiveDeviceFacts? = null
    private var selectionReason: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceToken = opaqueDeviceToken()
        val readFacts = readDeviceFacts()
        facts = readFacts
        val selection =
            ArKernelDeviceAdaptiveProfileAdapter.selectProfile(readFacts)
        selectionReason = selection.reasonToken
        steps.addAll(selection.steps)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "Geräteadaptive Kalibrierung (ARKADAPT1)"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
        }

        val intro = TextView(this).apply {
            text = "Beim Öffnen liest die App selbst, was dieses Handy kann " +
                "(Kamera-Hardwareklasse, Gyroskop, Rotationssensor, Thermik, " +
                "RAM-Klasse, Android-Version) und wählt daraus automatisch " +
                "ein festes, systematisches Profil. Dasselbe Verfahren läuft " +
                "auf jedem Android-Gerät – übertragbar ist das Protokoll, " +
                "nicht ein Kalibrierwert. Die Kalibrierung selbst gilt immer " +
                "nur für dieses Gerät und braucht deine echten Maße."
            textSize = 13f
        }

        val safetyText = TextView(this).apply {
            text = "Automatisch: Geräteerkennung + Profilwahl. Nicht " +
                "automatisch: metrische Kalibrierung. Android erlaubt keine " +
                "Kalibrierung schon bei der Installation, und eine universelle " +
                "Kalibrierung für alle Modelle gibt es nicht. Du brauchst " +
                "Zollstock, physische Targets und die Bestätigung pro Schritt. " +
                "Keine Bilder, Posen, Trajektorien, kein Upload, keine " +
                "Qualitätsaufstufung; Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
        }

        statusText = TextView(this).apply {
            text = "Profil wird vorbereitet …"
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }

        confirmButton = Button(this).apply {
            text = "1. Schritt bestätigen (ich habe ihn durchgeführt)"
            textSize = 15f
            setAllCaps(false)
        }
        skipButton = Button(this).apply {
            text = "Schritt überspringen (wird protokolliert)"
            textSize = 15f
            setAllCaps(false)
        }
        calibrationButton = Button(this).apply {
            text = "Physische Kalibrierung öffnen (ARKCALIB1)"
            textSize = 15f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "2. ARKADAPT1 prüfen & speichern"
            textSize = 15f
            setAllCaps(false)
        }
        shareButton = Button(this).apply {
            text = "ARKADAPT1 teilen / hier im Chat hochladen"
            textSize = 15f
            setAllCaps(false)
        }

        previewText = TextView(this).apply {
            text = "Noch keine Datei erzeugt."
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
        root.addView(confirmButton, fullWidthParams())
        root.addView(skipButton, fullWidthParams())
        root.addView(calibrationButton, fullWidthParams())
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

        confirmButton.setOnClickListener { markCurrent(true) }
        skipButton.setOnClickListener { markCurrent(false) }
        calibrationButton.setOnClickListener {
            startActivity(
                Intent(this, CalibrationCaptureActivity::class.java),
            )
        }
        saveButton.setOnClickListener { buildAndSave() }
        shareButton.setOnClickListener { shareEvidence() }
        updateUi()
    }

    /**
     * Bounded host-read device facts. A present
     * `LENS_INTRINSIC_CALIBRATION` field is deliberately NOT treated as a
     * trusted calibration: only a verified signed profile could be trusted,
     * and this screen verifies no signature.
     */
    private fun readDeviceFacts(): ArKernelAdaptiveDeviceFacts {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val sensorCount = sensorManager
            ?.getSensorList(Sensor.TYPE_ALL)?.size ?: 0
        val gyro = sensorManager
            ?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val rotationVector = sensorManager
            ?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        var cameraCount = 0
        var hardwareLevel = -1
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE)
                as? CameraManager
            val ids = cameraManager?.getCameraIdList() ?: arrayOf<String>()
            cameraCount = ids.size
            if (ids.isNotEmpty()) {
                val characteristics = cameraManager
                    ?.getCameraCharacteristics(ids[0])
                hardwareLevel = characteristics?.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                ) ?: -1
            }
        } catch (error: Exception) {
            hardwareLevel = -1
        }
        val powerManager = getSystemService(Context.POWER_SERVICE)
            as? PowerManager
        val thermal = powerManager != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE)
            as? ActivityManager
        val lowRam = activityManager?.isLowRamDevice() == true
        return ArKernelAdaptiveDeviceFacts(
            cameraHardwareLevel = hardwareLevel.coerceIn(-1, 3),
            cameraCount = cameraCount.coerceIn(0, 64),
            gyroAvailable = gyro,
            rotationVectorAvailable = rotationVector,
            thermalMonitoringAvailable = thermal,
            intrinsicsTrusted = false,
            sensorCount = sensorCount.coerceIn(0, 64),
            lowRam = lowRam,
            apiLevel = Build.VERSION.SDK_INT.coerceIn(0, 100),
        )
    }

    /** Opaque SHA-256 prefix of model identifiers; no free text, no PII. */
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

    private fun markCurrent(confirmed: Boolean) {
        if (currentIndex >= steps.size) return
        val status = if (confirmed) {
            ArKernelAdaptiveStepStatus.HOST_CONFIRMED
        } else {
            ArKernelAdaptiveStepStatus.SKIPPED
        }
        steps[currentIndex] = steps[currentIndex].copy(status = status)
        currentIndex += 1
        currentEvidence = ""
        updateUi()
    }

    private fun updateUi() {
        val done = currentIndex >= steps.size
        confirmButton.isEnabled = !done
        skipButton.isEnabled = !done
        saveButton.isEnabled = steps.isNotEmpty()
        shareButton.isEnabled = currentEvidence.isNotEmpty()
        val header = "Gerät: $deviceToken | " +
            "Klasse: ${selectionReason.ifEmpty { "unknown" }}\n" +
            "Schritt ${minOf(currentIndex + 1, steps.size)} von ${steps.size}"
        statusText.text = if (done) {
            "$header\nAlle Schritte adressiert. Bitte Datei erzeugen und " +
                "hier im Chat hochladen. Qualität: unverified."
        } else {
            val step = steps[currentIndex]
            "$header\nNächster Schritt: ${step.operator.wireName()} " +
                "→ ${step.targetToken}\n" +
                "Bestätige nur, was du wirklich auf diesem Gerät getan hast."
        }
        if (currentEvidence.isEmpty()) {
            previewText.text = steps.joinToString("\n") { step ->
                "${step.index}|${step.operator.wireName()}|" +
                    "${step.targetToken}|${step.status.wireName()}"
            }
        }
    }

    private fun buildAndSave() {
        val readFacts = facts ?: return
        val selection =
            ArKernelDeviceAdaptiveProfileAdapter.selectProfile(readFacts)
        val bundle = try {
            ArKernelAdaptiveBundle(
                sessionId = runId,
                deviceToken = deviceToken,
                deviceClass = selection.deviceClass,
                intrinsicsState = selection.intrinsicsState,
                gyroAvailable = readFacts.gyroAvailable,
                rotationVectorAvailable =
                    readFacts.rotationVectorAvailable,
                thermalMonitoringAvailable =
                    readFacts.thermalMonitoringAvailable,
                steps = steps.toList(),
            )
        } catch (error: IllegalArgumentException) {
            statusText.text = "Profil abgelehnt: ${error.message}"
            return
        }
        val text = try {
            ArKernelDeviceAdaptiveProfileAdapter.encode(bundle)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val parsed = ArKernelDeviceAdaptiveProfileAdapter.parse(text)
        if (!parsed.accepted || parsed.bundle == null) {
            statusText.text = "Fail-closed: ${parsed.rejection} – " +
                parsed.detail
            return
        }
        val report = ArKernelDeviceAdaptiveProfileAdapter
            .summarize(parsed.bundle!!)
        currentEvidence = text
        previewText.text = text
        val readyText = if (report.profileComplete) {
            "Alle Schritte adressiert – bitte Daten zur Auswertung hochladen."
        } else {
            "Noch offene Schritte: ${report.pendingCount} offen, " +
                "${report.skippedCount} übersprungen."
        }
        val previous = statusText.text.toString()
        statusText.text = previous + "\n" + readyText +
            " Vorprüfung ist keine Qualitätsnote; unverified."
        Thread {
            val result = writeTextToDownloads(
                "ARKADAPT1_${runId}.txt", text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $result"
            }
        }.start()
        Toast.makeText(
            this,
            "ARKADAPT1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        shareButton.isEnabled = true
    }

    private fun shareEvidence() {
        if (currentEvidence.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentEvidence)
        }
        startActivity(Intent.createChooser(intent, "ARKADAPT1 teilen"))
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

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
