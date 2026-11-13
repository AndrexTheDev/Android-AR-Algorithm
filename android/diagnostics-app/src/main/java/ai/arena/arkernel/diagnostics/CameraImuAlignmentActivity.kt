package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ARK_CAMIMU_MIN_CORRELATION_PERMILLE
import ai.arena.arkernel.ARK_CAMIMU_MIN_FRAMES
import ai.arena.arkernel.ARK_CAMIMU_MIN_GYRO_RATE_HZ
import ai.arena.arkernel.ARK_CAMIMU_MIN_MOTION_RANGE_DPS
import ai.arena.arkernel.ArKernelCameraFacing
import ai.arena.arkernel.ArKernelCameraImuAdapter
import ai.arena.arkernel.ArKernelCameraImuAlignment
import ai.arena.arkernel.ArKernelCameraImuAxis
import ai.arena.arkernel.ArKernelCameraImuCorrelator
import ai.arena.arkernel.ArKernelCameraImuCurveRow
import ai.arena.arkernel.ArKernelCameraLevel
import ai.arena.arkernel.CameraTimestampDomain
import ai.arena.arkernel.CameraTimestampNormalizer
import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Measures the time offset between the camera and the gyroscope, and finds
 * which gyroscope axis corresponds to a horizontal pan.
 *
 * The procedure the user is asked to perform is deliberately specific: hold
 * the phone steady, aim it at something with **texture** (a bookshelf, a
 * street, a patterned curtain - not a blank wall, not the sky), and pan it
 * slowly from side to side like shaking your head. Panning horizontally puts
 * the whole image shift into one direction, which is the only direction this
 * measurement compares.
 *
 * Nothing is fitted to the scene and no focal length is used: the estimate
 * comes from the *shape* of two curves over time, so a wrong scale cancels
 * out. What is measured is when one curve has to be slid to line up with the
 * other.
 *
 * No frames are kept. Each frame is reduced to a 128x8 grid of brightness
 * samples in memory and dropped immediately; only those small grids survive
 * until the correlation is done, and they are discarded with the result.
 * No AI model, no inference, no network. The grade stays `unverified`.
 */
class CameraImuAlignmentActivity : DiagnosticsActivity() {

    private companion object {
        const val REQUEST_CAMERA = 1024
        const val STATE_OPT_IN = "camimu.opt_in"
        const val STATE_DURATION = "camimu.duration"
        const val STATE_STATUS = "camimu.status"
        const val STATE_RECORD = "camimu.record"
        const val STATE_CAPTURE_INDEX = "camimu.capture_index"

        const val READER_WIDTH = 1_280
        const val READER_HEIGHT = 720

        val DURATION_MS = longArrayOf(4_000L, 6_000L, 10_000L)
    }

    /** One frame: a sparse brightness grid plus when it was captured. */
    private class ProfileFrame(val timeNs: Long, val profile: ByteArray)

    private lateinit var optInBox: CheckBox
    private lateinit var durationSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button
    private lateinit var gateText: TextView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var curveView: AlignmentCurveView
    private lateinit var textureView: TextureView
    private lateinit var previewFrame: FrameLayout

    private val runId = "camimu." + System.currentTimeMillis()
    private val deviceToken = opaqueDeviceToken()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var sensorDegrees = 0
    private var cameraLevel = ArKernelCameraLevel.UNKNOWN
    private var cameraFacing = ArKernelCameraFacing.UNKNOWN
    private var cameraIdText = "unknown"
    private var normalizer = CameraTimestampNormalizer()

    private var imuThread: HandlerThread? = null
    private var imuHandler: Handler? = null
    private var imuListener: SensorEventListener? = null

    private val framesLock = Any()
    private val frames = ArrayList<ProfileFrame>()
    private val pendingTimes = HashMap<Long, Long>()
    private val gyroTime = ArrayList<Long>()
    private val gyroX = ArrayList<Double>()
    private val gyroY = ArrayList<Double>()
    private val gyroZ = ArrayList<Double>()

    private var capturing = false
    private var captureIndex = 0
    private var currentRecord = ""
    private var lastCurve: List<ArKernelCameraImuCurveRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Kamera-IMU-Zeitversatz (t_d)"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = "Bestimmt, um wie viele Millisekunden die Zeitstempel " +
                "der Kamera gegenueber dem Gyroskop verschoben sind, " +
                "und welche Gyroskop-Achse zum horizontalen Schwenken " +
                "gehoert.\n\n" +
                "So wird gemessen: Die Kamera liefert die Helligkeit " +
                "eines Bildstreifens, das Gyroskop die Drehrate. Beide " +
                "werden ueber die Zeit aufgetragen und gegeneinander " +
                "verschoben, bis sie am besten uebereinander liegen. " +
                "Dieser Versatz ist t_d.\n\n" +
                "Es wird keine Brennweite benoetigt und kein " +
                "Herstellerwert verwendet: Nicht die Groesse der " +
                "Bewegung zaehlt, sondern wann sie passiert."
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = "Ablauf\n" +
                "1. Geraet auf ein strukturreiches Ziel richten " +
                "(Buecherregal, Straße, gemustertes Tuch). " +
                "Nicht auf eine leere Wand und nicht in den Himmel.\n" +
                "2. Handy ruhig halten und dann langsam und " +
                "gleichmaessig von links nach rechts schwenken, " +
                "wie beim Kopfschuetteln.\n" +
                "3. Waehrend der Aufnahme die Anzeige unten " +
                "beobachten. Sie sagt sofort, ob genug Bewegung " +
                "dabei ist."
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = "Es wird kein Bild gespeichert. Jedes Bild wird im " +
                "Speicher auf ein Raster von 128 x 8 " +
                "Helligkeitswerten reduziert und sofort verworfen."
            setPadding(0, 0, 0, 16)
        })

        optInBox = CheckBox(this).apply {
            text = "Ich habe die Hinweise gelesen und stimme der " +
                "Messung zu."
        }
        root.addView(optInBox)

        durationSpinner = Spinner(this).apply {
            contentDescription = "Dauer der IMU-Kalibrierung auswhlen"
            adapter = ArrayAdapter(
                this@CameraImuAlignmentActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("4 Sekunden", "6 Sekunden (empfohlen)",
                    "10 Sekunden"),
            )
            setSelection(1)
        }
        root.addView(durationSpinner)

        startButton = Button(this).apply {
            text = "Messung starten"
            setOnClickListener { startAlignmentRun() }
        }
        root.addView(startButton)

        previewFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 640)
        }
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
        }
        previewFrame.addView(textureView)
        root.addView(previewFrame)

        gateText = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 4)
        }
        root.addView(gateText)

        curveView = AlignmentCurveView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 360)
        }
        root.addView(curveView)

        resultText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }
        root.addView(resultText)

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        root.addView(statusText)

        shareButton = Button(this).apply {
            text = "ARKCAMIMU1 teilen"
            isEnabled = false
            setOnClickListener { shareRecord() }
        }
        root.addView(shareButton)

        copyButton = Button(this).apply {
            text = "In die Zwischenablage"
            isEnabled = false
            setOnClickListener { copyRecord() }
        }
        root.addView(copyButton)

        setContentView(scroll)
        title = "Kamera-IMU-Zeitversatz"

        if (savedInstanceState != null) {
            optInBox.isChecked =
                savedInstanceState.getBoolean(STATE_OPT_IN, false)
            durationSpinner.setSelection(
                savedInstanceState.getInt(STATE_DURATION, 1)
                    .coerceIn(0, DURATION_MS.size - 1))
            statusText.text =
                savedInstanceState.getString(STATE_STATUS, "") ?: ""
            currentRecord =
                savedInstanceState.getString(STATE_RECORD, "") ?: ""
            captureIndex =
                savedInstanceState.getInt(STATE_CAPTURE_INDEX, 0)
            if (currentRecord.isNotEmpty()) {
                shareButton.isEnabled = true
                copyButton.isEnabled = true
            }
        }
        refreshGates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPT_IN, optInBox.isChecked)
        outState.putInt(STATE_DURATION, durationSpinner.selectedItemPosition)
        outState.putString(STATE_STATUS, statusText.text.toString())
        outState.putString(STATE_RECORD, currentRecord)
        outState.putInt(STATE_CAPTURE_INDEX, captureIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        stopImu()
    }

    /**
     * The camera is released when the screen goes away, not when the process
     * does.
     *
     * A capture session holds the camera hardware, and onDestroy is not
     * guaranteed to arrive - the system may drop the whole process to reclaim
     * memory, and a backgrounded screen may sit alive for a long time. Until
     * release happens, no other app can open the camera and this one keeps
     * burning battery for a picture nobody is taking. So the release is bound
     * to the moment the operator can no longer see the preview. Coming back
     * starts a fresh session rather than resuming a stale one: the frames in
     * flight are discarded, and anything that was mid-measurement has to be
     * taken again, which is stated on the screen instead of being left to be
     * discovered.
     */
    override fun onStop() {
        stopCamera()
        stopImu()
        super.onStop()
    }

    // ---------------------------------------------------------------- gates

    private fun refreshGates() {
        val frameCount = synchronized(framesLock) { frames.size }
        val gyroCount = synchronized(framesLock) { gyroTime.size }
        val range = synchronized(framesLock) {
            if (gyroTime.size >= 2) {
                maxOf(
                    ArKernelCameraImuCorrelator.rangeOf(gyroX.toDoubleArray()),
                    ArKernelCameraImuCorrelator.rangeOf(gyroY.toDoubleArray()),
                    ArKernelCameraImuCorrelator.rangeOf(gyroZ.toDoubleArray()))
            } else {
                0.0
            }
        }
        val hz = synchronized(framesLock) {
            if (gyroTime.size >= 2) {
                val span = (gyroTime[gyroTime.size - 1] - gyroTime[0]) / 1e9
                if (span > 0.0) (gyroTime.size / span).toLong() else 0L
            } else {
                0L
            }
        }
        gateText.text = gateLine("Bilder", frameCount.toLong(),
            ARK_CAMIMU_MIN_FRAMES.toLong(), "")
        gateText.text = gateText.text.toString() + "\n" +
            gateLine("Gyroskop", hz, ARK_CAMIMU_MIN_GYRO_RATE_HZ, " Hz")
        gateText.text = gateText.text.toString() + "\n" +
            gateLine("Bewegung", range.toLong(),
                ARK_CAMIMU_MIN_MOTION_RANGE_DPS.toLong(), " °/s")
    }

    private fun gateLine(
        label: String,
        value: Long,
        minimum: Long,
        unit: String,
    ): String = "$label: $value$unit (mindestens $minimum$unit) " +
        if (value >= minimum) "OK" else "zu wenig"

    // ------------------------------------------------------------ capture

    private fun startAlignmentRun() {
        if (capturing) return
        if (!optInBox.isChecked) {
            Toast.makeText(this,
                "Bitte die Hinweise bestaetigen.", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA)
            return
        }
        val durationMs = DURATION_MS[durationSpinner.selectedItemPosition
            .coerceIn(0, DURATION_MS.size - 1)]
        synchronized(framesLock) {
            frames.clear()
            pendingTimes.clear()
            gyroTime.clear()
            gyroX.clear()
            gyroY.clear()
            gyroZ.clear()
        }
        normalizer = CameraTimestampNormalizer()
        capturing = true
        startButton.isEnabled = false
        shareButton.isEnabled = false
        copyButton.isEnabled = false
        lastCurve = emptyList()
        resultText.text = ""
        curveView.setCurve(emptyList())
        statusText.text = "Vorschau startet ..."
        openCamera {
            startImu()
            statusText.text = "Aufnahme laeuft: jetzt langsam von links " +
                "nach rechts schwenken."
            val handler = Handler(mainLooper)
            val startedAt = SystemClock.elapsedRealtime()
            val ticker = object : Runnable {
                override fun run() {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val remaining =
                        kotlin.math.max(0L, durationMs - elapsed) / 1_000L
                    if (capturing && remaining > 0L) {
                        statusText.text = "Aufnahme laeuft - noch " +
                            "$remaining s. Weiter schwenken."
                        refreshGates()
                        handler.postDelayed(this, 250L)
                    } else if (capturing) {
                        statusText.text = "Aufnahme beendet. " +
                            "Korrelation wird berechnet ..."
                        finishAlignmentRun(durationMs)
                    }
                }
            }
            handler.postDelayed(ticker, 250L)
        }
    }

    private fun finishAlignmentRun(durationMs: Long) {
        capturing = false
        stopImu()
        stopCamera()
        val duration = durationMs
        Thread {
            val record = buildRecord(duration)
            runOnUiThread {
                if (record == null) {
                    statusText.text = "Messung unvollstaendig. " +
                        "Bitte erneut versuchen."
                    startButton.isEnabled = true
                    return@runOnUiThread
                }
                currentRecord = ArKernelCameraImuAdapter.encode(record)
                val parsed = ArKernelCameraImuAdapter.parse(currentRecord)
                if (parsed.record == null) {
                    statusText.text = "Datensatz abgelehnt: " +
                        "${parsed.rejection} (${parsed.detail})"
                    startButton.isEnabled = true
                    return@runOnUiThread
                }
                lastCurve = record.curve
                curveView.setCurve(record.curve)
                resultText.text = describe(record)
                shareButton.isEnabled = true
                copyButton.isEnabled = true
                startButton.isEnabled = true
                val written = writeTextToDownloads(
                    "ARKCAMIMU1_$runId-${captureIndex + 1}.txt",
                    currentRecord)
                statusText.text = statusText.text.toString() +
                    "\nDatei: $written"
                captureIndex++
                Toast.makeText(this,
                    "ARKCAMIMU1 erzeugt. Bitte Text hier im Chat hochladen.",
                    Toast.LENGTH_LONG).show()
                announce("Kamera-IMU-Zeitversatz gespeichert")
            }
        }.start()
    }

    private fun buildRecord(durationMs: Long): ArKernelCameraImuAlignment? {
        val snapshot: List<ProfileFrame>
        val time: LongArray
        val x: DoubleArray
        val y: DoubleArray
        val z: DoubleArray
        synchronized(framesLock) {
            snapshot = frames.toList()
            time = gyroTime.toLongArray()
            x = gyroX.toDoubleArray()
            y = gyroY.toDoubleArray()
            z = gyroZ.toDoubleArray()
        }
        if (snapshot.size < 3 || time.size < 4) return null

        val count = snapshot.size - 1
        val mid = LongArray(count)
        val interval = LongArray(count)
        val shift = DoubleArray(count)
        val columns = ArKernelCameraImuCorrelator.PROFILE_COLUMNS
        val rows = ArKernelCameraImuCorrelator.PROFILE_ROWS
        for (index in 0 until count) {
            val low = snapshot[index].timeNs
            val high = snapshot[index + 1].timeNs
            interval[index] = high - low
            mid[index] = low + (high - low) / 2L
            shift[index] = ArKernelCameraImuCorrelator.shiftOf(
                snapshot[index].profile,
                snapshot[index + 1].profile,
                columns, rows)
        }
        val curve = ArKernelCameraImuCorrelator.correlate(
            frameMidNs = mid,
            frameIntervalNs = interval,
            imageShift = shift,
            gyroTimeNs = time,
            gyroX = x, gyroY = y, gyroZ = z)

        val intervals = interval.filter { it > 0L }.toLongArray()
        val frameIntervalMedianUs =
            ArKernelCameraImuCorrelator.medianOf(intervals) / 1_000L
        val gyroHz = if (time.size >= 2) {
            val span = (time[time.size - 1] - time[0]) / 1_000_000L
            if (span > 0L) time.size * 1_000L / span else 0L
        } else {
            0L
        }
        val rate = DoubleArray(count)
        for (index in 0 until count) {
            rate[index] = if (interval[index] > 0L) {
                shift[index] * 1e9 / interval[index].toDouble()
            } else {
                0.0
            }
        }
        // Per axis, never on the magnitude: the magnitude of a pan rate is
        // always positive, so its peak-to-peak span is about half the real
        // one and would fail the motion gate on a perfectly good run.
        val axisRange = doubleArrayOf(
            ArKernelCameraImuCorrelator.rangeOf(x),
            ArKernelCameraImuCorrelator.rangeOf(y),
            ArKernelCameraImuCorrelator.rangeOf(z))
        val axisRms = doubleArrayOf(
            ArKernelCameraImuCorrelator.rmsOf(x),
            ArKernelCameraImuCorrelator.rmsOf(y),
            ArKernelCameraImuCorrelator.rmsOf(z))
        val domain = normalizer.domain ?: CameraTimestampDomain.EQUIVALENT
        val offsetMs = normalizer.offsetNs / 1_000_000L

        return ArKernelCameraImuAlignment(
            runId = runId,
            deviceToken = deviceToken,
            cameraId = cameraIdText,
            cameraLevel = cameraLevel,
            cameraFacing = cameraFacing,
            sensorOrientationDeg = sensorDegrees,
            profileWidth = columns,
            profileHeight = rows,
            frameCount = snapshot.size,
            gyroSampleCount = time.size,
            durationMs = durationMs,
            frameIntervalMedianUs = frameIntervalMedianUs,
            gyroRateMedianHz = gyroHz,
            imageRateRms = ArKernelCameraImuCorrelator.rmsOf(rate),
            gyroRateRms = axisRms.maxOrNull() ?: 0.0,
            motionRangeDps = axisRange.maxOrNull() ?: 0.0,
            timestampDomain = domain,
            domainOffsetMs = offsetMs,
            lagMinMs = ArKernelCameraImuCorrelator.LAG_MIN_MS,
            lagStepMs = ArKernelCameraImuCorrelator.LAG_STEP_MS,
            curve = curve,
        )
    }

    private fun describe(record: ArKernelCameraImuAlignment): String {
        val builder = StringBuilder()
        val ms = record.estimatedOffsetUs / 1_000.0
        builder.append("Ergebnis\n")
        builder.append("Zeitversatz t_d: ").append(String.format(
            Locale.US, "%.1f", ms)).append(" ms\n")
        builder.append(if (record.estimatedOffsetUs > 0L) {
            "Die Bild-Zeitstempel sind zu spaet. Der Kernel sollte " +
                "t_d von jedem Bild-Zeitstempel abziehen.\n"
        } else if (record.estimatedOffsetUs < 0L) {
            "Die Bild-Zeitstempel sind zu frueh. Der Kernel sollte " +
                "|t_d| zu jedem Bild-Zeitstempel addieren.\n"
        } else {
            "Kein messbarer Versatz.\n"
        })
        builder.append("Achse: ").append(axisName(record.bestAxis))
        if (record.bestAxisSign < 0) builder.append(", invertiert")
        builder.append(" (Korrelation ")
            .append(record.bestCorrelationPermille / 10.0)
            .append(" %, mindestens ")
            .append(ARK_CAMIMU_MIN_CORRELATION_PERMILLE / 10.0)
            .append(" %)\n")
        builder.append("Guete: ")
            .append(ArKernelCameraImuAdapter.report(record).qualityGrade)
            .append("\n")
        val steps = record.outstandingSteps()
        if (steps.isEmpty()) {
            builder.append("Alle Pruefungen bestanden.\n")
        } else {
            builder.append("Noch nicht ausreichend: ")
                .append(steps.map { stepName(it) }.joinToString(", "))
                .append("\n")
        }
        builder.append("\nDie Kurve oben zeigt die Uebereinstimmung " +
            "ueber den Verschiebungsbereich. Ein schmaler, deutlicher " +
            "Berg bedeutet ein klares Ergebnis; ein flacher Verlauf " +
            "bedeutet, dass die Bewegung nicht ausgewertet werden " +
            "konnte.")
        return builder.toString()
    }

    private fun axisName(axis: ArKernelCameraImuAxis): String = when (axis) {
        ArKernelCameraImuAxis.X -> "X"
        ArKernelCameraImuAxis.Y -> "Y"
        ArKernelCameraImuAxis.Z -> "Z"
    }

    private fun stepName(token: String): String = when (token) {
        "frames" -> "zu wenig Bilder"
        "gyro" -> "Gyroskop-Rate zu niedrig"
        "motion" -> "zu wenig Bewegung"
        "scene" -> "Ziel zu strukturlos"
        "range" -> "Maximum am Fensterrand"
        else -> token
    }

    // ------------------------------------------------------------ camera

    private fun openCamera(onReady: () -> Unit) {
        val manager = getSystemService(CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            statusText.text = "Kein Kamera-Dienst verfuegbar."
            capturing = false
            startButton.isEnabled = true
            return
        }
        val cameraId = backCameraId(manager)
        if (cameraId == null) {
            statusText.text = "Keine Rueckkamera gefunden."
            capturing = false
            startButton.isEnabled = true
            return
        }
        cameraIdText = cameraId
        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (error: Exception) {
            null
        }
        sensorDegrees = characteristics
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        cameraLevel = levelOf(characteristics)
        cameraFacing = facingOf(characteristics)
        val previewSize = previewSizeOf(characteristics)
        val thread = HandlerThread("arkcamimu-camera").apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler
        val surfaceTexture = textureView.surfaceTexture
            ?: run {
                statusText.text = "Vorschau noch nicht bereit."
                capturing = false
                startButton.isEnabled = true
                return
            }
        try {
            surfaceTexture.setDefaultBufferSize(
                previewSize.width, previewSize.height)
            val surface = Surface(surfaceTexture)
            previewSurface = surface
            val imageReader = ImageReader.newInstance(
                READER_WIDTH, READER_HEIGHT,
                android.graphics.ImageFormat.YUV_420_888, 4)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ readerValue ->
                onFrame(readerValue)
            }, handler)
            manager.openCamera(cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val builder = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW)
                            builder.addTarget(surface)
                            builder.addTarget(imageReader.surface)
                            builder.set(CaptureRequest.CONTROL_MODE,
                                CameraMetadata.CONTROL_MODE_AUTO)
                            camera.createCaptureSession(
                                listOf(surface, imageReader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(
                                        session: CameraCaptureSession,
                                    ) {
                                        captureSession = session
                                        try {
                                            session.setRepeatingRequest(
                                                builder.build(),
                                                timestampCallback(), handler)
                                            runOnUiThread(onReady)
                                        } catch (error: Exception) {
                                            fail("Vorschau-Anfrage " +
                                                "fehlgeschlagen: " +
                                                error.message)
                                        }
                                    }

                                    override fun onConfigureFailed(
                                        session: CameraCaptureSession,
                                    ) {
                                        fail("Vorschau konnte nicht " +
                                            "konfiguriert werden.")
                                    }
                                }, handler)
                        } catch (error: Exception) {
                            fail("Kamera-Session fehlgeschlagen: " +
                                error.message)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        fail("Kamera getrennt.")
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int,
                    ) {
                        fail("Kamera-Fehler $error.")
                    }
                }, handler)
        } catch (error: SecurityException) {
            fail("Kamera-Erlaubnis fehlt.")
        } catch (error: Exception) {
            fail("Kamera konnte nicht geoeffnet werden: ${error.message}")
        }
    }

    private fun fail(message: String) {
        runOnUiThread {
            statusText.text = message
            capturing = false
            startButton.isEnabled = true
            stopImu()
            stopCamera()
        }
    }

    /**
     * Pairs each frame with a timestamp already normalised onto the
     * gyroscope's clock. The two callbacks are separate, so the raw sensor
     * stamp is used as the key between them.
     */
    private fun timestampCallback(): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val raw = result.get(TotalCaptureResult.SENSOR_TIMESTAMP)
                    ?: return
                val normalized = normalizer.observeAndNormalize(
                    raw,
                    SystemClock.elapsedRealtimeNanos(),
                    SystemClock.uptimeMillis() * 1_000_000L)
                    ?: return
                synchronized(framesLock) {
                    pendingTimes[raw] = normalized.normalizedTimestampNs
                }
            }
        }

    private fun onFrame(readerValue: ImageReader) {
        val image: Image = try {
            readerValue.acquireNextImage()
        } catch (error: Exception) {
            return
        } ?: return
        try {
            if (!capturing) return
            val timeNs = synchronized(framesLock) {
                pendingTimes.remove(image.timestamp)
            } ?: return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val compact = ByteArray(width * height)
            for (row in 0 until height) {
                val source = row * rowStride
                val target = row * width
                for (column in 0 until width) {
                    compact[target + column] =
                        buffer.get(source + column * pixelStride)
                }
            }
            val profile = ArKernelCameraImuCorrelator.extractProfile(
                plane = compact,
                width = width,
                height = height,
                rowStride = width,
                pixelStride = 1)
            synchronized(framesLock) {
                if (frames.size < 4_096) {
                    frames.add(ProfileFrame(timeNs, profile))
                }
            }
        } catch (error: Exception) {
            // One unreadable frame is dropped; nothing is persisted.
        } finally {
            image.close()
        }
    }

    private fun previewSizeOf(characteristics: CameraCharacteristics?): Size =
        try {
            val map = characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            sizes?.filter {
                it.width <= 1_920 && it.height <= 1_080
            }?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: Size(1_280, 720)
        } catch (error: Exception) {
            Size(1_280, 720)
        }

    private fun levelOf(
        characteristics: CameraCharacteristics?,
    ): ArKernelCameraLevel = when (
        runCatching {
            characteristics?.get(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        }.getOrNull()
    ) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ->
            ArKernelCameraLevel.LEGACY
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED ->
            ArKernelCameraLevel.LIMITED
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ->
            ArKernelCameraLevel.FULL
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 ->
            ArKernelCameraLevel.LEVEL_3
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL ->
            ArKernelCameraLevel.EXTERNAL
        else -> ArKernelCameraLevel.UNKNOWN
    }

    private fun facingOf(
        characteristics: CameraCharacteristics?,
    ): ArKernelCameraFacing = when (
        runCatching {
            characteristics?.get(CameraCharacteristics.LENS_FACING)
        }.getOrNull()
    ) {
        CameraMetadata.LENS_FACING_BACK -> ArKernelCameraFacing.BACK
        CameraMetadata.LENS_FACING_FRONT -> ArKernelCameraFacing.FRONT
        CameraMetadata.LENS_FACING_EXTERNAL -> ArKernelCameraFacing.EXTERNAL
        else -> ArKernelCameraFacing.UNKNOWN
    }

    private fun backCameraId(manager: CameraManager): String? = try {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) ==
                CameraMetadata.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    } catch (error: Exception) {
        null
    }

    private fun stopCamera() {
        try {
            captureSession?.stopRepeating()
        } catch (error: Exception) {
        }
        try {
            captureSession?.close()
        } catch (error: Exception) {
        }
        try {
            cameraDevice?.close()
        } catch (error: Exception) {
        }
        try {
            previewSurface?.release()
        } catch (error: Exception) {
        }
        try {
            reader?.close()
        } catch (error: Exception) {
        }
        captureSession = null
        cameraDevice = null
        previewSurface = null
        reader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    // --------------------------------------------------------------- imu

    private fun startImu() {
        val manager = getSystemService(SENSOR_SERVICE) as? SensorManager
            ?: return
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        val thread = HandlerThread("arkcamimu-imu").apply { start() }
        val handler = Handler(thread.looper)
        imuThread = thread
        imuHandler = handler
        val listener: SensorEventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!capturing) return
                synchronized(framesLock) {
                    if (gyroTime.size >= 200_000) return
                    gyroTime.add(event.timestamp)
                    gyroX.add(event.values[0].toDouble())
                    gyroY.add(event.values[1].toDouble())
                    gyroZ.add(event.values[2].toDouble())
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        imuListener = listener
        try {
            manager.registerListener(
                listener, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } catch (error: Exception) {
            fail("Gyroskop konnte nicht registriert werden.")
        }
    }

    private fun stopImu() {
        // Unregistered explicitly first: quitting the thread alone leaves the
        // sensor manager holding the listener on some devices, which keeps
        // the gyroscope running after the screen has gone.
        val listener = imuListener
        if (listener != null) {
            try {
                val manager =
                    getSystemService(SENSOR_SERVICE) as? SensorManager
                manager?.unregisterListener(listener)
            } catch (error: Exception) {
            }
            imuListener = null
        }
        imuHandler?.removeCallbacksAndMessages(null)
        imuThread?.quitSafely()
        imuThread = null
        imuHandler = null
    }

    // ------------------------------------------------------------- output

    private fun shareRecord() {
        if (currentRecord.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentRecord)
        }
        startActivity(Intent.createChooser(intent, "ARKCAMIMU1 teilen"))
    }

    private fun copyRecord() {
        if (currentRecord.isEmpty()) return
        val manager = getSystemService(CLIPBOARD_SERVICE) as?
            android.content.ClipboardManager ?: return
        manager.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "ARKCAMIMU1", currentRecord))
        Toast.makeText(this, "Kopiert.", Toast.LENGTH_SHORT).show()
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
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ARKernelDiagnostics")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Downloads: Einfuegen fehlgeschlagen"
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
            val directory = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS),
                "ARKernelDiagnostics")
            if (!directory.exists() && !directory.mkdirs()) {
                return "Downloads: Ordner nicht anlegbar"
            }
            try {
                File(directory, fileName).writeBytes(bytes)
                "Downloads/ARKernelDiagnostics/$fileName"
            } catch (error: Exception) {
                "Downloads: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions,
            grantResults)
        if (requestCode == REQUEST_CAMERA &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startAlignmentRun()
        }
    }

    private fun announce(text: String) {
        statusText.announceForAccessibility(text)
    }

    /**
     * A one-way digest of the device's build fields. It lets runs from the
     * same handset be grouped without recording which handset it is.
     */
    private fun opaqueDeviceToken(): String = try {
        val raw = listOf(
            Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
            Build.BOARD, Build.HARDWARE,
        ).joinToString("|")
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
            }.take(16)
    } catch (error: Exception) {
        "device"
    }

    // -------------------------------------------------------------- chart

    /** Draws the correlation curve so a sharp peak is visible at a glance. */
    private class AlignmentCurveView(context: android.content.Context) :
        View(context) {

        private var rows: List<ArKernelCameraImuCurveRow> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 24f
        }

        private companion object {
            val COLORS = intArrayOf(
                Color.rgb(0x2b, 0x7a, 0xba),
                Color.rgb(0xd8, 0x11, 0x62),
                Color.rgb(0x00, 0x96, 0x18),
            )
            val NAMES = arrayOf("X", "Y", "Z")
        }

        fun setCurve(next: List<ArKernelCameraImuCurveRow>) {
            rows = next
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            if (rows.isEmpty()) {
                paint.color = Color.GRAY
                paint.textSize = 26f
                canvas.drawText("Noch keine Messung", 24f, height / 2f, paint)
                return
            }
            val left = 60f
            val right = width - 24f
            val top = 30f
            val bottom = height - 40f
            paint.strokeWidth = 2f
            paint.color = Color.LTGRAY
            canvas.drawLine(left, (top + bottom) / 2f, right,
                (top + bottom) / 2f, paint)
            val minLag = rows.minOf { it.lagMs }
            val maxLag = rows.maxOf { it.lagMs }
            val xOf = { lag: Int ->
                if (maxLag == minLag) {
                    (left + right) / 2f
                } else {
                    left + (lag - minLag).toFloat() /
                        (maxLag - minLag).toFloat() * (right - left)
                }
            }
            val yOf = { permille: Int ->
                bottom - (permille + 1_000) / 2_000f * (bottom - top)
            }
            for (axis in 0..2) {
                val axisRows = rows.filter {
                    it.axis.ordinal == axis
                }.sortedBy { it.index }
                if (axisRows.size < 2) continue
                paint.color = COLORS[axis]
                for (index in 1 until axisRows.size) {
                    val a = axisRows[index - 1]
                    val b = axisRows[index]
                    canvas.drawLine(
                        xOf(a.lagMs), yOf(a.correlationPermille),
                        xOf(b.lagMs), yOf(b.correlationPermille), paint)
                }
            }
            axisPaint.color = Color.GRAY
            canvas.drawText("${minLag} ms", left, bottom + 30f, axisPaint)
            val zeroLabel = "0 ms"
            canvas.drawText(zeroLabel, xOf(0) - 30f, bottom + 30f, axisPaint)
            canvas.drawText("${maxLag} ms", right - 90f, bottom + 30f,
                axisPaint)
            val legendX = left + 8f
            for (axis in 0..2) {
                paint.color = COLORS[axis]
                canvas.drawText(NAMES[axis], legendX + axis * 60f, top - 8f,
                    paint)
            }
        }
    }
}
