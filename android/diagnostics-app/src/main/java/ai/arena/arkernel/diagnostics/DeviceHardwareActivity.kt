package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ARK_DEVICE_MAX_THERMAL_STATUS
import ai.arena.arkernel.ARK_DEVICE_MIN_AVAILABLE_MEMORY_MB
import ai.arena.arkernel.ARK_DEVICE_MIN_FREE_STORAGE_MB
import ai.arena.arkernel.ARK_DEVICE_MIN_SUSTAINED_PERMILLE
import ai.arena.arkernel.ArKernelDeviceHardware
import ai.arena.arkernel.ArKernelDeviceHardwareAdapter
import ai.arena.arkernel.ArKernelDeviceSensorRow
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Asks the device the four questions that decide whether a long run on it
 * measures anything at all.
 *
 * A sixty-second static run measures the gyroscope only if the device does not
 * throttle halfway through it - otherwise it measures the throttling and
 * reports it as sensor noise, plausibly and quietly. Nothing in an inertial
 * log distinguishes the two after the fact, so this screen distinguishes them
 * beforehand: it puts a controlled CPU load on the device and compares the
 * work rate in the first quarter of the run against the last. A device that
 * collapses in the last quarter cannot be trusted with a long capture.
 *
 * Alongside it, the same window samples both monotonic clocks.
 * `elapsedRealtime` and `uptimeMillis` differ by everything the device slept
 * through; that offset is expected and harmless. What is not harmless is the
 * offset *growing during a run*, because that means the device slept
 * mid-capture, and neither clock moving backwards, because fusion assumes
 * monotonicity and silently corrupts when it does not hold.
 *
 * The inventory is what the platform reports about its sensors - not a
 * measurement of them. A device can advertise a gyroscope and deliver nothing.
 * This screen reports the advertisement and leaves the measurement to the
 * inertial screen; it deliberately does not conflate them.
 *
 * No sensor sample, no frame and no pixel is retained. The only stored output
 * is a bounded `ARKDEVHW1` text record written to `Downloads` on demand.
 */
class DeviceHardwareActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_REPORT = "devhw.report"
        const val STATE_STATUS = "devhw.status"
        const val STATE_TEXT = "devhw.text"
        const val STATE_OPT_IN = "devhw.optin"
        const val STATE_DURATION = "devhw.duration"

        /** Ten seconds is the shortest run that resolves a throttle. */
        val DURATIONS = listOf(10, 30, 60)

        /** Work per slice; small enough to resolve the quarters. */
        const val SLICE_ITERATIONS = 4_000_000L

        /** The contract carries at most 64 sensors. */
        const val MAX_SENSORS = 64
    }

    /** Everything the platform says about the device, read before the load. */
    private data class Inventory(
        val sensors: List<ArKernelDeviceSensorRow>,
        val totalMemoryMb: Long,
        val availableMemoryMb: Long,
        val lowRamDevice: Boolean,
        val freeStorageMb: Long,
        val totalStorageMb: Long,
        val batteryPercent: Int,
        val batteryCharging: Boolean,
        val thermalStatus: Int,
    )

    /** What the sustained-load window observed. */
    private data class Probe(
        val durationMs: Long,
        val rateEarly: Long,
        val rateLate: Long,
        val clockOffsetMs: Long,
        val clockDriftPpm: Long,
        val monotonicViolations: Int,
        val thermalStatus: Int,
    )

    private lateinit var optInBox: CheckBox
    private lateinit var durationSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var reportText: TextView
    private lateinit var statusText: TextView

    @Volatile
    private var probeRunning = false

    /** Sink for the load probe's result, so the work cannot be eliminated. */
    @Volatile
    private var sink = 0L
    /** Two's complement of 0x9E3779B97F4A7C15, the golden-ratio odd. */
    private var seed = -7046029254386353131L

    private var lastText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        root.addView(TextView(this).apply {
            text = "Gerät & Laufzeit (ARKDEVHW1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für Gerät und Laufzeit"
        })
        root.addView(TextView(this).apply {
            text = "Version 2.21-devhw"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        })
        root.addView(TextView(this).apply {
            text = "1) Dauer wählen. 2) Opt-in anhaken. " +
                "3) „Prüfung starten“ – das Gerät wird für " +
                "die gewählte Zeit absichtlich belastet und " +
                "warm, Akku und Lüfter arbeiten; nichts " +
                "passiert, solange du nicht startest. " +
                "4) „Datei speichern“ und selbst hochladen."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der Geräteprüfung"
        })
        root.addView(TextView(this).apply {
            text = "Die Prüfung drosselt das Gerät mit " +
                "Absicht: sie rechnet ununterbrochen, damit " +
                "sichtbar wird, ob der Takt einbricht. Das " +
                "ist der Punkt der Messung, kein Fehler. " +
                "Kein Sensorwert, kein Bild und kein Pixel " +
                "wird behalten."
            textSize = 12f
            setTextColor(Color.parseColor("#B45309"))
            setPadding(0, 8, 0, 0)
            contentDescription =
                "Hinweis, dass das Gerät absichtlich belastet wird"
        })

        durationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@DeviceHardwareActivity,
                android.R.layout.simple_spinner_dropdown_item,
                DURATIONS.map { "$it Sekunden" },
            )
        }
        root.addView(labelled("Dauer", durationSpinner))

        optInBox = CheckBox(this).apply {
            text = "Opt-in: Gerät für diese Prüfung belasten"
            textSize = 14f
            contentDescription = "Zustimmung zur Lastprüfung des Geräts"
        }
        root.addView(optInBox, fullWidthParams())

        startButton = Button(this).apply {
            text = "Prüfung starten"
            textSize = 15f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "Datei speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        shareButton = Button(this).apply {
            text = "Datei teilen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        root.addView(startButton, fullWidthParams())
        root.addView(saveButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())

        reportText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 12, 0, 0)
            text = "Noch keine Prüfung ausgewertet."
        }
        root.addView(reportText)

        statusText = TextView(this).apply {
            text = "Bereit."
            textSize = 12f
            setPadding(0, 12, 0, 0)
        }
        root.addView(statusText)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        startButton.setOnClickListener { startRun() }
        saveButton.setOnClickListener { save() }
        shareButton.setOnClickListener { share() }

        restore(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_REPORT, reportText.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
        outState.putString(STATE_TEXT, lastText)
        outState.putBoolean(STATE_OPT_IN, optInBox.isChecked)
        outState.putInt(STATE_DURATION, durationSpinner.selectedItemPosition)
    }

    private fun restore(state: Bundle?) {
        if (state == null) return
        reportText.text = state.getString(STATE_REPORT) ?: ""
        statusText.text = state.getString(STATE_STATUS) ?: ""
        lastText = state.getString(STATE_TEXT) ?: ""
        optInBox.isChecked = state.getBoolean(STATE_OPT_IN)
        durationSpinner.setSelection(state.getInt(STATE_DURATION, 0))
        saveButton.isEnabled = lastText.isNotEmpty()
        shareButton.isEnabled = lastText.isNotEmpty()
    }

    override fun onDestroy() {
        probeRunning = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------- run

    private fun startRun() {
        if (probeRunning) return
        if (!optInBox.isChecked) {
            statusText.text = "Bitte Opt-in anhaken – ohne Zustimmung " +
                "wird das Gerät nicht belastet."
            return
        }
        val durationMs = DURATIONS[durationSpinner.selectedItemPosition] *
            1_000L
        val inventory = readInventory()
        if (inventory.sensors.isEmpty()) {
            statusText.text = "Kein Sensor gemeldet – ohne " +
                "Sensorliste ist die Prüfung wertlos."
            return
        }
        busy(true, "Prüfung läuft – Gerät nicht benutzen " +
            "(noch ${durationMs / 1_000L} s) …")
        probeRunning = true
        Thread {
            val probe = runProbe(durationMs) { remainingMs ->
                runOnUiThread {
                    if (probeRunning) {
                        statusText.text = "Prüfung läuft – Gerät nicht " +
                            "benutzen (noch " +
                            "${remainingMs / 1_000L} s) …"
                    }
                }
            }
            runOnUiThread {
                probeRunning = false
                busy(false, "")
                finish(inventory, probe)
            }
        }.start()
    }

    /**
     * Runs back-to-back slices of CPU work for the whole window, bucketing the
     * measured rate into the first and the last quarter of the run. No sleep
     * between slices: the point is sustained pressure, not a sampled estimate.
     */
    private fun runProbe(
        durationMs: Long,
        onProgress: (Long) -> Unit,
    ): Probe {
        val startNs = SystemClock.elapsedRealtimeNanos()
        var lastElapsedNs = startNs
        var lastUptimeMs = SystemClock.uptimeMillis()
        val firstOffsetMs = lastElapsedNs / 1_000_000L - lastUptimeMs
        var lastOffsetMs = firstOffsetMs
        var violations = 0
        var earlyUnits = 0L
        var earlyNs = 0L
        var lateUnits = 0L
        var lateNs = 0L
        var nextReportMs = 1_000L
        while (probeRunning) {
            val sliceStartNs = SystemClock.elapsedRealtimeNanos()
            val elapsedMs = (sliceStartNs - startNs) / 1_000_000L
            if (elapsedMs >= durationMs) break
            work(SLICE_ITERATIONS)
            val sliceNs = SystemClock.elapsedRealtimeNanos() - sliceStartNs
            if (sliceNs > 0L) {
                if (elapsedMs * 4L < durationMs) {
                    earlyUnits += SLICE_ITERATIONS
                    earlyNs += sliceNs
                } else if (elapsedMs * 4L > durationMs * 3L) {
                    lateUnits += SLICE_ITERATIONS
                    lateNs += sliceNs
                }
            }
            val elapsedNs = SystemClock.elapsedRealtimeNanos()
            val uptimeMs = SystemClock.uptimeMillis()
            if (elapsedNs < lastElapsedNs || uptimeMs < lastUptimeMs) {
                violations++
            }
            lastElapsedNs = elapsedNs
            lastUptimeMs = uptimeMs
            lastOffsetMs = elapsedNs / 1_000_000L - uptimeMs
            if (elapsedMs >= nextReportMs) {
                nextReportMs += 1_000L
                onProgress(durationMs - elapsedMs)
            }
        }
        val wallMs = (SystemClock.elapsedRealtimeNanos() - startNs) /
            1_000_000L
        val driftPpm = if (wallMs > 0L) {
            (lastOffsetMs - firstOffsetMs) * 1_000_000L / wallMs
        } else {
            0L
        }
        return Probe(
            durationMs = wallMs,
            rateEarly = ratePerSecond(earlyUnits, earlyNs),
            rateLate = ratePerSecond(lateUnits, lateNs),
            clockOffsetMs = lastOffsetMs,
            clockDriftPpm = driftPpm,
            monotonicViolations = violations,
            thermalStatus = readThermalStatus(),
        )
    }

    /**
     * A deterministic multiply-add chain whose result lands in [sink], so the
     * compiler cannot eliminate it. Integer overflow is intentional and wraps.
     */
    private fun work(iterations: Long) {
        var acc = 0L
        var state = seed
        var index = 0L
        while (index < iterations) {
            state = state * 6364136223846793005L + 1442695040888963407L
            acc += state
            index++
        }
        seed = state
        sink += acc
    }

    private fun ratePerSecond(units: Long, nanos: Long): Long = when {
        units <= 0L || nanos <= 0L -> 0L
        units > Long.MAX_VALUE / 1_000_000_000L -> units / nanos * 1_000_000_000L
        else -> units * 1_000_000_000L / nanos
    }

    private fun finish(inventory: Inventory, probe: Probe) {
        if (probe.rateEarly <= 0L || probe.rateLate <= 0L) {
            statusText.text = "Prüfung lieferte keine Rate – " +
                "abgebrochen oder zu kurz. Bitte erneut starten."
            return
        }
        val record = try {
            ArKernelDeviceHardware(
                runId = "devhw.${System.currentTimeMillis()}",
                deviceToken = opaqueDeviceToken(),
                apiLevel = Build.VERSION.SDK_INT,
                manufacturer =
                    ArKernelDeviceHardwareAdapter.name(Build.MANUFACTURER),
                model = ArKernelDeviceHardwareAdapter.name(Build.MODEL),
                device = ArKernelDeviceHardwareAdapter.name(Build.DEVICE),
                board = ArKernelDeviceHardwareAdapter.name(Build.BOARD),
                hardware = ArKernelDeviceHardwareAdapter.name(Build.HARDWARE),
                totalMemoryMb = inventory.totalMemoryMb,
                availableMemoryMb = inventory.availableMemoryMb,
                lowRamDevice = inventory.lowRamDevice,
                freeStorageMb = inventory.freeStorageMb,
                totalStorageMb = inventory.totalStorageMb,
                batteryPercent = inventory.batteryPercent,
                batteryCharging = inventory.batteryCharging,
                thermalStatusStart = inventory.thermalStatus,
                thermalStatusEnd = probe.thermalStatus,
                loadDurationMs = probe.durationMs,
                loadRateEarly = probe.rateEarly,
                loadRateLate = probe.rateLate,
                clockOffsetMs = probe.clockOffsetMs,
                clockDriftPpm = probe.clockDriftPpm,
                monotonicViolations = probe.monotonicViolations,
                sensors = inventory.sensors,
            )
        } catch (error: IllegalArgumentException) {
            statusText.text = "Prüfung nicht verwertbar: ${error.message}"
            return
        }
        lastText = ArKernelDeviceHardwareAdapter.encode(record)
        val parsed = ArKernelDeviceHardwareAdapter.parse(lastText)
        saveButton.isEnabled = parsed.accepted
        shareButton.isEnabled = parsed.accepted
        reportText.text = render(record)
        statusText.text = if (parsed.accepted) {
            "Prüfung abgeschlossen – ${record.sensorCount} Sensoren " +
                "und ${probe.durationMs / 1_000L} s Last ausgewertet."
        } else {
            "WARNUNG: Rücklesen scheitert – ${parsed.detail}"
        }
    }

    // ------------------------------------------------------------- inventory

    private fun readInventory(): Inventory {
        var totalMemoryMb = 0L
        var availableMemoryMb = 0L
        var lowRamDevice = false
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE)
                as? ActivityManager
            if (manager != null) {
                val info = ActivityManager.MemoryInfo()
                manager.getMemoryInfo(info)
                totalMemoryMb = info.totalMem / (1024L * 1024L)
                availableMemoryMb = info.availMem / (1024L * 1024L)
                lowRamDevice = manager.isLowRamDevice()
            }
        } catch (error: Exception) {
            statusText.text = "Arbeitsspeicher nicht lesbar."
        }
        var freeStorageMb = 0L
        var totalStorageMb = 0L
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val stat = StatFs(dir.absolutePath)
            freeStorageMb = stat.availableBytes / (1024L * 1024L)
            totalStorageMb = stat.totalBytes / (1024L * 1024L)
        } catch (error: Exception) {
            statusText.text = "Massenspeicher nicht lesbar."
        }
        var batteryPercent = -1
        var batteryCharging = false
        try {
            val intent = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                ?: -1
            if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                ?: -1
            batteryCharging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } catch (error: Exception) {
            // An unreadable battery is reported as unknown, not as empty.
        }
        return Inventory(
            sensors = enumerateSensors(),
            totalMemoryMb = totalMemoryMb,
            availableMemoryMb = availableMemoryMb,
            lowRamDevice = lowRamDevice,
            freeStorageMb = freeStorageMb,
            totalStorageMb = totalStorageMb,
            batteryPercent = batteryPercent,
            batteryCharging = batteryCharging,
            thermalStatus = readThermalStatus(),
        )
    }

    private fun enumerateSensors(): List<ArKernelDeviceSensorRow> {
        val manager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager ?: return emptyList()
        val all = try {
            manager.getSensorList(Sensor.TYPE_ALL)
        } catch (error: Exception) {
            return emptyList()
        }
        val rows = ArrayList<ArKernelDeviceSensorRow>(all.size)
        for (sensor in all.sortedWith(
            compareBy({ it.type }, { it.name })
        )) {
            if (rows.size >= MAX_SENSORS) break
            val row = try {
                ArKernelDeviceSensorRow(
                    index = rows.size,
                    kind = kindOf(sensor.type),
                    name = ArKernelDeviceHardwareAdapter.name(sensor.name),
                    vendor = ArKernelDeviceHardwareAdapter.name(sensor.vendor),
                    maxRange = sensor.maximumRange.toDouble(),
                    resolution = sensor.resolution.toDouble(),
                    minDelayUs = if (sensor.minDelay < 0) 0 else sensor.minDelay,
                    maxDelayUs = if (sensor.maxDelay < 0) 0 else sensor.maxDelay,
                    powerMa = sensor.power.toDouble(),
                    reportingMode = modeOf(sensor.reportingMode),
                    wakeUp = sensor.isWakeUpSensor,
                )
            } catch (error: IllegalArgumentException) {
                continue
            }
            rows.add(row)
        }
        return rows
    }

    private fun kindOf(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ->
            "accelerometer_uncalibrated"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyroscope_uncalibrated"
        Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED ->
            "magnetometer_uncalibrated"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ->
            "geomagnetic_rotation_vector"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
        Sensor.TYPE_ORIENTATION -> "orientation"
        Sensor.TYPE_PRESSURE -> "pressure"
        Sensor.TYPE_LIGHT -> "light"
        Sensor.TYPE_PROXIMITY -> "proximity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "relative_humidity"
        Sensor.TYPE_STEP_COUNTER -> "step_counter"
        Sensor.TYPE_STEP_DETECTOR -> "step_detector"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "significant_motion"
        Sensor.TYPE_STATIONARY_DETECT -> "stationary_detect"
        Sensor.TYPE_MOTION_DETECT -> "motion_detect"
        Sensor.TYPE_POSE_6DOF -> "pose_6dof"
        Sensor.TYPE_HEART_RATE -> "heart_rate"
        Sensor.TYPE_HEART_BEAT -> "heart_beat"
        Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT ->
            "low_latency_offbody_detect"
        // Everything the public SDK hides - tilt and wake gestures, wrist
        // tilt, device orientation, dynamic-sensor metadata - falls through
        // to `type_<n>` below. That is honest and sufficient: the kernel
        // needs an accelerometer and a gyroscope, and both are public.
        else -> "type_$type"
    }

    private fun modeOf(mode: Int): String = when (mode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "on_change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "one_shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special_trigger"
        else -> "unknown"
    }

    private fun readThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return try {
            val manager = getSystemService(Context.POWER_SERVICE)
                as? PowerManager
            manager?.getCurrentThermalStatus() ?: -1
        } catch (error: Exception) {
            -1
        }
    }

    // ---------------------------------------------------------------- report

    private fun render(record: ArKernelDeviceHardware): String {
        val lines = ArrayList<String>()
        lines.add("Gerät ${record.manufacturer} ${record.model} · " +
            "API ${record.apiLevel}")
        lines.add("  ${record.device} · ${record.board} · " +
            "${record.hardware}")
        lines.add("")
        lines.add("Sensoren: ${record.sensorCount} gemeldet")
        for (sensor in record.sensors) {
            lines.add("  ${sensor.kind}: Bereich ${num(sensor.maxRange)}, " +
                "Auflösung ${num(sensor.resolution)}, " +
                "${sensor.minDelayUs}-${sensor.maxDelayUs} µs Verzug, " +
                "${num(sensor.powerMa)} mA, ${sensor.reportingMode}" +
                if (sensor.wakeUp) ", weckt das Gerät" else "")
        }
        lines.add("")
        lines.add("Arbeitsspeicher: ${record.availableMemoryMb} von " +
            "${record.totalMemoryMb} MB frei" +
            if (record.lowRamDevice) " (Low-RAM-Gerät)" else "")
        lines.add("  ab $ARK_DEVICE_MIN_AVAILABLE_MEMORY_MB MB frei " +
            "ausreichend")
        lines.add("")
        lines.add("Massenspeicher: ${record.freeStorageMb} von " +
            "${record.totalStorageMb} MB frei")
        lines.add("  ab $ARK_DEVICE_MIN_FREE_STORAGE_MB MB frei " +
            "ausreichend")
        lines.add("")
        lines.add("Akku ${record.batteryPercent} %" +
            if (record.batteryCharging) " (lädt)" else "")
        lines.add("Thermik ${thermal(record.thermalStatusStart)} -> " +
            "${thermal(record.thermalStatusEnd)}")
        lines.add("")
        lines.add("Dauerlast ${record.loadDurationMs / 1_000L} s: " +
            "${record.loadRateEarly} -> ${record.loadRateLate} " +
            "Einheiten/s")
        lines.add("  gehalten ${record.loadRateRatioPermille} ‰ " +
            "(ab $ARK_DEVICE_MIN_SUSTAINED_PERMILLE ‰ ausreichend)")
        lines.add("")
        lines.add("Uhren: Abstand ${record.clockOffsetMs} ms, Drift " +
            "${record.clockDriftPpm} ppm, Rückschritte " +
            "${record.monotonicViolations}")
        lines.add("")
        lines.add("Urteile")
        lines.add("  Sensoren vorhanden   ${mark(record.sensorsOk)}")
        lines.add("  Uhren monoton        ${mark(record.clockOk)}")
        lines.add("  drosselt nicht       ${mark(record.throttlingOk)}")
        lines.add("  thermisch ruhig      ${mark(record.thermalOk)}")
        lines.add("  genug Arbeitsspeicher ${mark(record.memoryOk)}")
        lines.add("  genug Platz          ${mark(record.storageOk)}")
        lines.add("  insgesamt            ${mark(record.deviceSufficient)}")
        val steps = record.outstandingSteps()
        if (steps.isNotEmpty()) {
            lines.add("  offen: ${steps.joinToString(", ")}")
        }
        lines.add("")
        lines.add("Qualität: ${ArKernelDeviceHardwareAdapter
            .report(record).qualityGrade}")
        return lines.joinToString("\n")
    }

    private fun thermal(status: Int): String = when {
        status < 0 -> "nicht gemeldet"
        status > ARK_DEVICE_MAX_THERMAL_STATUS -> "kritisch ($status)"
        else -> status.toString()
    }

    private fun mark(value: Boolean): String =
        if (value) "ok" else "NEIN"

    private fun num(value: Double): String {
        val text = String.format(Locale.US, "%.3f", value)
        return if (text.contains('.')) {
            text.trimEnd('0').trimEnd('.')
        } else {
            text
        }
    }

    // -------------------------------------------------------------- plumbing

    private fun busy(running: Boolean, message: String) {
        startButton.isEnabled = !running
        durationSpinner.isEnabled = !running
        optInBox.isEnabled = !running
        saveButton.isEnabled = !running && lastText.isNotEmpty()
        shareButton.isEnabled = !running && lastText.isNotEmpty()
        if (running) statusText.text = message
    }

    private fun save() {
        if (lastText.isEmpty()) return
        val name = "ARKDEVHW1_${System.currentTimeMillis()}.txt"
        val path = writeTextToDownloads(name, lastText)
        statusText.text = path
        Toast.makeText(this, "Geräteprüfung gespeichert", Toast.LENGTH_SHORT)
            .show()
    }

    private fun share() {
        if (lastText.isEmpty()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKDEVHW1")
                putExtra(Intent.EXTRA_TEXT, lastText)
            }
            startActivity(Intent.createChooser(send, "ARKDEVHW1 teilen"))
        } catch (error: Exception) {
            statusText.text = "Teilen fehlgeschlagen: ${error.message}"
        }
    }

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

    private fun labelled(label: String, view: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DeviceHardwareActivity).apply {
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
