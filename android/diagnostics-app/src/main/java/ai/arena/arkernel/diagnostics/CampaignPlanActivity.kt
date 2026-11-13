package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelAdaptiveDeviceClass
import ai.arena.arkernel.ArKernelAdaptiveDeviceFacts
import ai.arena.arkernel.ArKernelCampaignBundle
import ai.arena.arkernel.ArKernelCampaignPlanAdapter
import ai.arena.arkernel.ArKernelCampaignQuestionRow
import ai.arena.arkernel.ArKernelCampaignQuestionStatus
import ai.arena.arkernel.ArKernelCampaignScene
import ai.arena.arkernel.ArKernelCampaignSceneStatus
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
 * Systematic, portable measurement campaign screen (`ARKCAMPAIGN1`).
 *
 * The screen derives the device class from bounded host-read device facts and
 * builds a canonical campaign plan: fixed scenes, fixed systematic questions,
 * fixed evidence depths and a held-out gate. The same protocol runs on every
 * Android device; weaker/unknown devices only receive fewer scenes, never
 * different questions or a different quality rule. Calibration values are
 * never transferred between devices.
 *
 * Scene completions and question confirmations are host reports, not
 * measurements. The screen captures no images, pixels, poses or trajectories,
 * measures no physical target, infers no intrinsics or metric scale, performs
 * no automatic/universal calibration, verifies no signature, uploads nothing
 * and grants no quality grade. Quality stays `unverified`.
 */
class CampaignPlanActivity : DiagnosticsActivity() {

    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var sceneConfirmButton: Button
    private lateinit var sceneSkipButton: Button
    private lateinit var questionConfirmButton: Button
    private lateinit var questionSkipButton: Button
    private lateinit var adaptiveButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private val runId = "campaign." + System.currentTimeMillis()
    private val scenes = ArrayList<ArKernelCampaignScene>()
    private val questions = ArrayList<ArKernelCampaignQuestionRow>()
    private var deviceToken: String = "device.unknown"
    private var deviceClass: ArKernelAdaptiveDeviceClass =
        ArKernelAdaptiveDeviceClass.UNKNOWN
    private var sceneIndex = 0
    private var questionIndex = 0
    private var currentPlan: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceToken = opaqueDeviceToken()
        val selection =
            ArKernelDeviceAdaptiveProfileAdapter.selectProfile(
                readDeviceFacts())
        deviceClass = selection.deviceClass
        val plan = ArKernelCampaignPlanAdapter.canonicalPlan(
            runId, deviceToken, deviceClass)
        scenes.addAll(plan.scenes)
        questions.addAll(plan.questions)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "Systematische Messkampagne (ARKCAMPAIGN1)"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
        }
        val intro = TextView(this).apply {
            text = "Fester, geräteunabhängiger Ablauf: neun Szenenarten " +
                "(Maßstab drinnen/draußen, rechte Winkel, Stillstand, " +
                "Thermik-Wiederholung, Tag/Nacht, Held-out, Sitzungen, " +
                "Gerätevergleich) und zehn kanonische Fragen mit festen " +
                "Wiederholungstiefen. Schwächere oder unbekannte Geräte " +
                "bekommen weniger Szenen, aber nie andere Fragen. " +
                "Übertragbar ist das Verfahren, nicht ein Kalibrierwert."
            textSize = 13f
        }
        val safetyText = TextView(this).apply {
            text = "Maßband, Baustellen-Targets und unabhängige Held-out-" +
                "Maße musst du selbst messen. Keine Bilder, Posen, " +
                "Trajektorien, keine automatische oder universelle " +
                "Kalibrierung, keine Signaturprüfung, kein Upload, keine " +
                "Qualitätsaufstufung. Vollständige Evidenz ist keine " +
                "Genauigkeitsaussage; Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
        }
        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }
        sceneConfirmButton = Button(this).apply {
            text = "1. Szene als durchgeführt markieren"
            textSize = 15f
            setAllCaps(false)
        }
        sceneSkipButton = Button(this).apply {
            text = "Szene überspringen (protokolliert)"
            textSize = 15f
            setAllCaps(false)
        }
        questionConfirmButton = Button(this).apply {
            text = "2. Frage mit Evidenz bestätigen"
            textSize = 15f
            setAllCaps(false)
        }
        questionSkipButton = Button(this).apply {
            text = "Frage überspringen (protokolliert)"
            textSize = 15f
            setAllCaps(false)
        }
        adaptiveButton = Button(this).apply {
            text = "Geräteprofil öffnen (ARKADAPT1)"
            textSize = 15f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "3. ARKCAMPAIGN1 prüfen & speichern"
            textSize = 15f
            setAllCaps(false)
        }
        shareButton = Button(this).apply {
            text = "ARKCAMPAIGN1 teilen / hier hochladen"
            textSize = 15f
            setAllCaps(false)
        }
        previewText = TextView(this).apply {
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
        root.addView(sceneConfirmButton, fullWidthParams())
        root.addView(sceneSkipButton, fullWidthParams())
        root.addView(questionConfirmButton, fullWidthParams())
        root.addView(questionSkipButton, fullWidthParams())
        root.addView(adaptiveButton, fullWidthParams())
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

        sceneConfirmButton.setOnClickListener {
            markScene(ArKernelCampaignSceneStatus.HOST_COMPLETED)
        }
        sceneSkipButton.setOnClickListener {
            markScene(ArKernelCampaignSceneStatus.SKIPPED)
        }
        questionConfirmButton.setOnClickListener {
            markQuestion(
                ArKernelCampaignQuestionStatus.HOST_CONFIRMED)
        }
        questionSkipButton.setOnClickListener {
            markQuestion(ArKernelCampaignQuestionStatus.SKIPPED)
        }
        adaptiveButton.setOnClickListener {
            startActivity(
                Intent(this, AdaptiveCalibrationActivity::class.java),
            )
        }
        saveButton.setOnClickListener { buildAndSave() }
        shareButton.setOnClickListener { sharePlan() }
        updateUi()
    }

    /** Bounded host-read facts; no raw identifiers are stored or exported. */
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
            val ids = cameraManager?.getCameraIdList()
                ?: arrayOf<String>()
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
        return ArKernelAdaptiveDeviceFacts(
            cameraHardwareLevel = hardwareLevel.coerceIn(-1, 3),
            cameraCount = cameraCount.coerceIn(0, 64),
            gyroAvailable = gyro,
            rotationVectorAvailable = rotationVector,
            thermalMonitoringAvailable = thermal,
            intrinsicsTrusted = false,
            sensorCount = sensorCount.coerceIn(0, 64),
            lowRam = activityManager?.isLowRamDevice() == true,
            apiLevel = Build.VERSION.SDK_INT.coerceIn(0, 100),
        )
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

    private fun markScene(status: ArKernelCampaignSceneStatus) {
        if (sceneIndex >= scenes.size) return
        scenes[sceneIndex] = scenes[sceneIndex].copy(status = status)
        sceneIndex += 1
        currentPlan = ""
        updateUi()
    }

    private fun markQuestion(status: ArKernelCampaignQuestionStatus) {
        if (questionIndex >= questions.size) return
        questions[questionIndex] =
            questions[questionIndex].copy(status = status)
        questionIndex += 1
        currentPlan = ""
        updateUi()
    }

    private fun updateUi() {
        val sceneDone = sceneIndex >= scenes.size
        val questionDone = questionIndex >= questions.size
        sceneConfirmButton.isEnabled = !sceneDone
        sceneSkipButton.isEnabled = !sceneDone
        questionConfirmButton.isEnabled = !questionDone
        questionSkipButton.isEnabled = !questionDone
        saveButton.isEnabled = scenes.isNotEmpty()
        shareButton.isEnabled = currentPlan.isNotEmpty()
        val header = "Gerät: $deviceToken | Klasse: " +
            "${deviceClass.wireName()} | Szenen " +
            "${minOf(sceneIndex + 1, scenes.size)}/${scenes.size} | " +
            "Fragen " +
            "${minOf(questionIndex + 1, questions.size)}/${questions.size}"
        val sceneLine = if (sceneDone) {
            "Alle Szenen adressiert."
        } else {
            val scene = scenes[sceneIndex]
            "Nächste Szene: ${scene.kind.wireName()} → " +
                "${scene.targetToken} (${scene.repeats} Wiederholungen)"
        }
        val questionLine = if (questionDone) {
            "Alle Fragen adressiert."
        } else {
            val row = questions[questionIndex]
            "Nächste Frage: ${row.question.wireId} " +
                "(mind. ${row.question.requiredRepeats} Wiederholungen " +
                "aus ${row.question.kind.wireName()})"
        }
        val next = if (sceneDone && questionDone) {
            "\nJetzt Plan erzeugen und hier im Chat hochladen. " +
                "Qualität: unverified."
        } else {
            ""
        }
        statusText.text = "$header\n$sceneLine\n$questionLine$next"
        if (currentPlan.isEmpty()) {
            previewText.text = buildString {
                append("Kanonische Fragen:\n")
                for (row in questions) {
                    append(row.question.wireId)
                    append(" <- ")
                    append(row.question.kind.wireName())
                    append(" x")
                    append(row.question.requiredRepeats)
                    append(" | ")
                    append(row.status.wireName())
                    append('\n')
                }
                append("\nSzenen:\n")
                for (scene in scenes) {
                    append(scene.kind.wireName())
                    append(" | ")
                    append(scene.targetToken)
                    append(" | ")
                    append(scene.status.wireName())
                    append('\n')
                }
            }
        }
    }

    private fun buildAndSave() {
        val bundle = try {
            ArKernelCampaignBundle(
                campaignId = runId,
                deviceToken = deviceToken,
                deviceClass = deviceClass,
                scenes = scenes.toList(),
                questions = questions.toList(),
            )
        } catch (error: IllegalArgumentException) {
            statusText.text = "Plan abgelehnt: ${error.message}"
            return
        }
        val text = try {
            ArKernelCampaignPlanAdapter.encode(bundle)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val parsed = ArKernelCampaignPlanAdapter.parse(text)
        if (!parsed.accepted || parsed.bundle == null) {
            statusText.text = "Fail-closed: ${parsed.rejection} – " +
                parsed.detail
            return
        }
        val report =
            ArKernelCampaignPlanAdapter.summarize(parsed.bundle!!)
        currentPlan = text
        previewText.text = text
        val readyText = if (report.campaignEvidenceComplete) {
            "Alle Fragen mit Evidenz adressiert – bitte Daten zur " +
                "Auswertung hochladen."
        } else {
            "Noch ${report.pendingQuestionCount} offene Fragen, " +
                "${report.evidenceCompleteQuestionCount} mit Evidenz, " +
                "${report.unsupportedQuestionCount} ohne Geräteszene."
        }
        statusText.text = statusText.text.toString() + "\n" +
            readyText + " Vollständigkeit ist keine Qualitätsnote; " +
            "unverified."
        Thread {
            val result = writeTextToDownloads(
                "ARKCAMPAIGN1_${runId}.txt", text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $result"
            }
        }.start()
        Toast.makeText(
            this,
            "ARKCAMPAIGN1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        shareButton.isEnabled = true
    }

    private fun sharePlan() {
        if (currentPlan.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentPlan)
        }
        startActivity(Intent.createChooser(intent, "ARKCAMPAIGN1 teilen"))
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
