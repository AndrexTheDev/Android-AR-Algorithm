package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelFieldDiagnosticsLogEvent
import ai.arena.arkernel.ArKernelFieldDiagnosticsLogEventKind
import ai.arena.arkernel.ArKernelFieldDiagnosticsPolicy
import ai.arena.arkernel.ArKernelFieldDiagnosticsPolicyBundle
import ai.arena.arkernel.ArKernelFieldDiagnosticsRunLog
import ai.arena.arkernel.ArKernelPhysicalSelfTestMetrics
import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ConfigurationInfo
import android.content.pm.PackageManager
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
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sideloadable host diagnostic app. It writes an opted-in ARKFDPOL1 policy and
 * a bounded ARKFDLOG1 run log as text files, never images, poses, trajectories,
 * calibration arrays or pixels. A guided self-test mode adds only
 * model-independent structural measurements from the device's own sensors,
 * camera lifecycle, storage and thermal API; it performs no automatic upload,
 * signature verification or physical ground-truth measurement and stays
 * explicitly unverified.
 */
class MainActivity : android.app.Activity() {

    private lateinit var statusText: TextView
    private lateinit var logPreview: TextView
    private lateinit var optIn: CheckBox
    private lateinit var createButton: Button
    private lateinit var physicalButton: Button
    private lateinit var writeButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button

    private val diagnosticsPolicy = ArKernelFieldDiagnosticsPolicy(
        policyId = "diagnostics.apk.v1",
        collectionOptIn = true,
    )
    private val policyText: String =
        ArKernelFieldDiagnosticsPolicyBundle.encode(diagnosticsPolicy)
    private val runCounter = AtomicInteger(0)
    private val sessionStartMillis = System.currentTimeMillis()

    private var currentLog: String = ""
    private var currentRunId: String = "run.initial"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "AR-Kern Diagnose"
            textSize = 22f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
        }

        val intro = TextView(this).apply {
            text = "Zwei Modi nach Opt-in: kurzer Kapabilitäts-Log oder geführte " +
                "physische Selbsttests mit deiner Hilfe. Beide schreiben eine " +
                "ARKFDLOG1-Textdatei (Downloads/ARKernelDiagnostics bzw. App-Dateien), " +
                "die du hier im Chat hochladen kannst."
            textSize = 14f
        }

        val safetyText = TextView(this).apply {
            text = "Nur opake Skalare: Gerätekataloge, IMU-Stillstand, Camera2-Lifecycle, " +
                "Storage-Roundtrip, Kern-Selbsttest, Thermal-Zeitreihe. Keine Bilder, " +
                "Posen, Trajektorien, Kalibrierarrays oder Pixel; Nutzerbestätigungen " +
                "sind nur host-reported. Keine externen Targets, kein Bodentruth, " +
                "kein Upload, Qualität: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
        }

        optIn = CheckBox(this).apply {
            text = "Ich stimme der begrenzten Text-Diagnose zu (Opt-in)."
            textSize = 15f
        }

        statusText = TextView(this).apply {
            text = "Bitte Opt-in anhaken. Kamera-Berechtigung wird ggf. nur für " +
                "Camera2-Metadaten abgefragt."
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 12, 0, 12)
        }

        createButton = Button(this).apply {
            text = "1. Kurzen Kapabilitäts-Log erzeugen"
            textSize = 15f
            setAllCaps(false)
        }
        physicalButton = Button(this).apply {
            text = "2. Physische Selbsttests starten (mit deiner Hilfe)"
            textSize = 15f
            setAllCaps(false)
        }
        writeButton = Button(this).apply {
            text = "3. Dateien speichern"
            textSize = 15f
            setAllCaps(false)
        }
        shareButton = Button(this).apply {
            text = "4. Log teilen"
            textSize = 15f
            setAllCaps(false)
        }
        copyButton = Button(this).apply {
            text = "Log in Zwischenablage kopieren"
            textSize = 15f
            setAllCaps(false)
        }

        logPreview = TextView(this).apply {
            text = "Noch kein Log erzeugt."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
        }

        val scroll = ScrollView(this).apply {
            addView(
                logPreview,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        root.addView(title, fullWidthParams())
        root.addView(intro, fullWidthParams())
        root.addView(safetyText, fullWidthParams())
        root.addView(optIn, fullWidthParams())
        root.addView(statusText, fullWidthParams())
        root.addView(createButton, fullWidthParams())
        root.addView(physicalButton, fullWidthParams())
        root.addView(writeButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(copyButton, fullWidthParams())
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)

        optIn.setOnCheckedChangeListener { _, _ -> updateButtons() }
        createButton.setOnClickListener { createLog() }
        physicalButton.setOnClickListener { runPhysicalTests() }
        writeButton.setOnClickListener {
            Thread {
                val result = writeDiagnosticsFiles()
                runOnUiThread { statusText.text = result }
            }.start()
        }
        shareButton.setOnClickListener { shareLog() }
        copyButton.setOnClickListener { copyLog() }

        updateButtons()
        ensureCameraPermission()
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    private fun updateButtons() {
        val allowed = optIn.isChecked
        createButton.isEnabled = allowed
        physicalButton.isEnabled = allowed
        val hasLog = allowed && currentLog.isNotEmpty()
        writeButton.isEnabled = hasLog
        shareButton.isEnabled = hasLog
        copyButton.isEnabled = hasLog
    }

    private fun createLog() {
        if (!optIn.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        try {
            val text = buildLogText()
            publishLog(text, "Kapabilitäts-Log erzeugt.")
        } catch (error: Exception) {
            currentLog = ""
            currentRunId = "run.error"
            statusText.text = "Log konnte nicht erzeugt werden: ${error.message}"
            updateButtons()
        }
    }

    private fun runPhysicalTests() {
        if (!optIn.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        physicalButton.isEnabled = false
        Thread {
            val runner = PhysicalSelfTestRunner(this)
            val result = runner.run { progress ->
                runOnUiThread { statusText.text = progress }
            }
            runOnUiThread {
                try {
                    val text = buildLogText(
                        result.events, result.passed, result.total)
                    publishLog(text, result.summary)
                } catch (error: Exception) {
                    currentLog = ""
                    currentRunId = "run.error"
                    statusText.text =
                        "Selbsttests konnten nicht protokolliert werden: " +
                            error.message
                } finally {
                    updateButtons()
                }
            }
        }.start()
    }

    private fun publishLog(text: String, status: String) {
        val parsed = ArKernelFieldDiagnosticsRunLog.parse(text)
        logPreview.text = text
        if (!parsed.accepted) {
            currentLog = ""
            currentRunId = "run.invalid"
            statusText.text = "ARKFDLOG1 abgelehnt: ${parsed.rejection.name} " +
                "(Zeile ${parsed.lineNumber}). $status"
            updateButtons()
            return
        }
        currentLog = text
        val eventCount = parsed.record?.events?.size ?: 0
        statusText.text = "$status ARKFDLOG1 akzeptiert ($eventCount Events, " +
            "${text.length} Zeichen). Run: $currentRunId"
        updateButtons()
    }

    private fun buildLogText(
        extraEvents: List<PendingLogEvent> = emptyList(),
        passed: Int = 0,
        total: Int = 0,
    ): String {
        require(extraEvents.size <= 100) {
            "Too many physical self-test events"
        }
        require(extraEvents.none {
            it.kind == ArKernelFieldDiagnosticsLogEventKind.RUN_START ||
                it.kind == ArKernelFieldDiagnosticsLogEventKind.RUN_END
        }) { "Physical self-test events cannot contain terminal events" }
        val sessionId = "session-" + sessionStartMillis
        val runId = "run-" + System.currentTimeMillis() + "." +
            runCounter.incrementAndGet()
        currentRunId = runId
        val baseNs = SystemClock.elapsedRealtimeNanos()
        val events = mutableListOf<ArKernelFieldDiagnosticsLogEvent>()

        fun add(
            kind: ArKernelFieldDiagnosticsLogEventKind,
            code: String?,
            value: Long?,
            detail: String?,
        ) {
            val sequence = events.size
            events += ArKernelFieldDiagnosticsLogEvent(
                sequence,
                baseNs + sequence.toLong() * 1_000_000L,
                kind,
                code,
                value,
                detail,
            )
        }

        val (cores, memoryToken) = memoryFacts()
        val (glMajor, glToken) = glEsFacts()
        val (sensorCount, gyroToken) = sensorFacts()
        val (cameraCount, cameraToken) = cameraFacts()
        val (thermalStatus, thermalToken) = thermalFacts()
        val (uptimeSeconds, storageToken) = timingFacts()

        add(
            ArKernelFieldDiagnosticsLogEventKind.RUN_START,
            "apk.open",
            null,
            "opt.in.apk",
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "android.api",
            Build.VERSION.SDK_INT.toLong(),
            opaqueToken("release.${Build.VERSION.RELEASE}"),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "abi",
            null,
            opaqueToken(Build.SUPPORTED_ABIS.firstOrNull() ?: "abi.unknown"),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "device",
            null,
            opaqueToken("${Build.MANUFACTURER}.${Build.MODEL}.${Build.DEVICE}"),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "build.fingerprint",
            null,
            opaqueToken(Build.FINGERPRINT),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "cpu.cores",
            cores,
            memoryToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "gl.es",
            glMajor.toLong(),
            glToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "sensors",
            sensorCount,
            gyroToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "arcore",
            arcorePresent(),
            "package.query",
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "cameras",
            cameraCount,
            cameraToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAMERA_LIFECYCLE,
            "camera.permission",
            cameraPermissionGranted(),
            "characteristics.query",
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.THERMAL,
            "thermal.status",
            thermalStatus,
            thermalToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.TIMING,
            "uptime.s",
            uptimeSeconds,
            storageToken,
        )
        for (pending in extraEvents) {
            add(pending.kind, pending.code, pending.value, pending.detailToken)
        }
        add(
            ArKernelFieldDiagnosticsLogEventKind.SESSION_TERMINAL,
            "structural.summary",
            passed.toLong(),
            ArKernelPhysicalSelfTestMetrics.opaqueToken(
                "pass.$passed.of.$total.unverified",
            ),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.RUN_END,
            null,
            (events.size + 1).toLong(),
            null,
        )

        return ArKernelFieldDiagnosticsRunLog.encode(
            diagnosticsPolicy,
            sessionId,
            runId,
            events,
        )
    }

    private fun memoryFacts(): Pair<Long, String> {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024L * 1024L)
        val lowRam = if (manager?.isLowRamDevice() == true) 1 else 0
        val cores = Runtime.getRuntime().availableProcessors().toLong()
        return cores to opaqueToken("ram.mb.$totalMb.low.ram.$lowRam.cores.$cores")
    }

    private fun glEsFacts(): Pair<Int, String> {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info: ConfigurationInfo? = manager?.getDeviceConfigurationInfo()
        val raw = info?.reqGlEsVersion ?: 0
        val major = raw shr 16
        val minor = raw and 0xFFFF
        return major to opaqueToken("gl.es.$major.$minor")
    }

    private fun sensorFacts(): Pair<Long, String> {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val count = manager?.getSensorList(Sensor.TYPE_ALL)?.size?.toLong() ?: 0L
        val gyro = if (manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
            "gyro.present"
        } else {
            "gyro.absent"
        }
        return count to gyro
    }

    private fun arcorePresent(): Long = try {
        packageManager.getPackageInfo("com.google.ar.core", 0)
        1L
    } catch (error: Exception) {
        0L
    }

    private fun cameraFacts(): Pair<Long, String> = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return 0L to "camera.manager.unavailable"
        val ids = manager.getCameraIdList()
        val parts = ids.mapIndexed { index, id ->
            val level = try {
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                ) ?: -1
            } catch (error: Exception) {
                -1
            }
            "c$index.hw.$level"
        }
        ids.size.toLong() to opaqueToken(parts.joinToString("."))
    } catch (error: Exception) {
        0L to "camera.query.failed"
    }

    private fun thermalFacts(): Pair<Long, String> {
        var status = 0L
        var detail = "thermal.unavailable"
        try {
            val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                status = manager.getCurrentThermalStatus().toLong()
                detail = "thermal.status.$status"
            }
            if (manager != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                val headroom = manager.getThermalHeadroom(30)
                detail = if (!headroom.isNaN() && !headroom.isInfinite() &&
                    headroom >= 0f
                ) {
                    val formatted = String.format(Locale.US, "%.2f", headroom)
                    "thermal.status.$status.headroom.$formatted"
                } else {
                    "thermal.status.$status.headroom.unknown"
                }
            }
        } catch (error: Exception) {
            detail = "thermal.query.failed"
        }
        return status to opaqueToken(detail)
    }

    private fun timingFacts(): Pair<Long, String> {
        val uptimeSeconds = SystemClock.elapsedRealtime() / 1000L
        val freeMb = try {
            StatFs(filesDir.absolutePath).getAvailableBytes() / (1024L * 1024L)
        } catch (error: Exception) {
            0L
        }
        return uptimeSeconds to opaqueToken("storage.mb.$freeMb")
    }

    private fun cameraPermissionGranted(): Long =
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) 1L else 0L

    /** ASCII-only opaque token; no whitespace, no sensitive free text. */
    private fun opaqueToken(raw: String): String {
        val mapped = raw.lowercase(Locale.ROOT).map { char ->
            when {
                char in 'a'..'z' || char in '0'..'9' || char == '.' ||
                    char == '-' || char == ':' -> char
                else -> '.'
            }
        }.joinToString("")
        val collapsed = mapped.replace(Regex("\\.+"), ".").trim('.', '-', ':')
        return collapsed.take(128).ifEmpty { "unknown" }
    }

    private fun writeDiagnosticsFiles(): String {
        val policyName = "ARKFDPOL1_${currentRunId}.txt"
        val logName = "ARKFDLOG1_${currentRunId}.txt"
        val policyResult = writeTextToDownloads(policyName, policyText)
        val logResult = writeTextToDownloads(logName, currentLog)
        return "Richtlinie: $policyResult\nLauf-Log: $logResult\n\n" +
            "Bitte $logName hier im Chat hochladen oder über 'Log teilen' senden."
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
                "Downloads/ARKernelDiagnostics/$fileName (MediaStore)"
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
                val file = File(dir, fileName)
                file.writeText(text, Charsets.UTF_8)
                file.absolutePath
            } catch (error: Exception) {
                "App-Dateien: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
    }

    private fun shareLog() {
        if (currentLog.isEmpty()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKFDLOG1 $currentRunId")
                putExtra(Intent.EXTRA_TEXT, currentLog)
            }
            startActivity(Intent.createChooser(send, "ARKFDLOG1 teilen"))
        } catch (error: Exception) {
            statusText.text = "Teilen fehlgeschlagen: ${error.message}"
        }
    }

    private fun copyLog() {
        if (currentLog.isEmpty()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            statusText.text = "Zwischenablage nicht verfügbar."
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("ARKFDLOG1", currentLog))
        Toast.makeText(this, "Log kopiert", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        _permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, _permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            statusText.text = if (granted) {
                "Kamera-Berechtigung erteilt; bitte Opt-in anhaken und Log erzeugen."
            } else {
                "Kamera-Berechtigung verweigert; Camera2-Details bleiben begrenzt."
            }
        }
    }

    private companion object {
        const val REQUEST_CAMERA = 1001
    }
}
