package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ARK_CAMIMU_MIN_FRAMES
import ai.arena.arkernel.ARK_CAMIMU_MIN_GYRO_RATE_HZ
import ai.arena.arkernel.ARK_CAMIMU_MIN_MOTION_RANGE_DPS
import ai.arena.arkernel.ArKernelCameraFacing
import ai.arena.arkernel.ArKernelCameraImuCorrelator
import ai.arena.arkernel.ArKernelCameraImuExtrinsics
import ai.arena.arkernel.ArKernelCameraImuExtrinsicsAdapter
import ai.arena.arkernel.ArKernelCameraImuMatrix
import ai.arena.arkernel.ArKernelCameraImuRotation
import ai.arena.arkernel.ArKernelCameraLevel
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
 * Measures the rotation between the camera and the gyroscope, and gets a
 * focal length out of the same motion for free.
 *
 * The measurement is the one thing here that is not obvious, so it is worth
 * saying plainly. Turning an image shift into an angle normally needs a focal
 * length, which is an intrinsic this app does not have. It works anyway
 * because the unknown scale cancels against a constraint: near the optical
 * axis a rotating camera moves content at `x-dot = -f * w_y` and
 * `y-dot = +f * w_x`, so regressing the horizontal image rate onto the three
 * gyroscope axes returns minus f times one row of the rotation matrix, and
 * the vertical regression returns plus f times another. Every row of a
 * rotation matrix is a unit vector, so the *length* of either coefficient
 * vector is the focal length, and dividing by it recovers the row. Nothing is
 * assumed about the lens.
 *
 * Two consequences are worth knowing before trusting a result. The two
 * regressions give two independent estimates of the same focal length, and
 * how far they disagree is the main gate - if they disagree, the motion was
 * not a pure rotation and the whole estimate is suspect. And the third row is
 * inferred as a cross product rather than measured, so it carries whatever
 * error the first two have.
 *
 * The translation between the camera and the IMU is *not* recovered. It needs
 * position, position needs metric scale, and nothing here supplies either.
 * The record says so explicitly rather than leaving a blank.
 *
 * The procedure the user is asked to perform matters: the motion has to
 * excite all three gyroscope axes, or the components along the unexcited ones
 * are invisible no matter how clean the data is. That is a property of the
 * regression, not of the scene, which is why the gate is on conditioning and
 * not on correlation.
 *
 * No frames are kept. Each frame is reduced to a grid of brightness samples
 * in memory and dropped immediately. No AI model, no inference, no network.
 * The grade stays `unverified`.
 */
class CameraExtrinsicsActivity : DiagnosticsActivity() {

    private companion object {
        const val REQUEST_CAMERA = 1026
        const val STATE_OPT_IN = "camext.opt_in"
        const val STATE_DURATION = "camext.duration"
        const val STATE_STATUS = "camext.status"
        const val STATE_RECORD = "camext.record"
        const val STATE_CAPTURE_INDEX = "camext.capture_index"

        const val READER_WIDTH = 1_280
        const val READER_HEIGHT = 720

        // Enough rows that the vertical shift is measurable at all. The
        // alignment activity gets away with eight rows because it only ever
        // looks sideways; here the vertical axis carries half the answer.
        const val GRID_COLUMNS = 128
        const val GRID_ROWS = 72

        val DURATION_MS = longArrayOf(6_000L, 10_000L, 15_000L)
    }

    /** One frame: a brightness grid plus when it was captured. */
    private class ProfileFrame(val timeNs: Long, val profile: ByteArray)

    /**
     * Either a record, or a German sentence saying which gate stopped it.
     *
     * A run that never became observable must not be written out with an
     * identity matrix standing in for the rotation: that would report the
     * camera and the gyroscope as perfectly aligned, which is precisely the
     * claim this measurement does not have.
     */
    private class Outcome(
        val record: ArKernelCameraImuExtrinsics?,
        val reason: String,
    )

    private lateinit var optInBox: CheckBox
    private lateinit var durationSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button
    private lateinit var gateText: TextView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var focalView: FocalSplitView
    private lateinit var textureView: TextureView
    private lateinit var previewFrame: FrameLayout

    private val runId = "camext." + System.currentTimeMillis()
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
    private var lastRotation: ArKernelCameraImuMatrix? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            setText("Kamera-IMU-Drehung (R)")
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            setText(
                "Bestimmt, wie die Kamera gegenueber dem Gyroskop " +
                    "gedreht ist - und daraus die Brennweite, ohne sie " +
                    "vorher zu kennen.\n\n" +
                    "So wird gemessen: Bei einer reinen Drehung " +
                    "verschiebt sich der Bildinhalt mit " +
                    "x-punkt = -f * w_y und y-punkt = +f * w_x. Das " +
                    "Bild liefert die Verschiebung, das Gyroskop die " +
                    "Drehraten w. Beides wird ueber dieselben " +
                    "Zeitintervalle gemittelt und gegeneinander " +
                    "aufgetragen.\n\n" +
                    "Der Trick: Jede Zeile einer Drehmatrix hat die " +
                    "Laenge 1. Die Laenge der gefundenen Steigung ist " +
                    "daher genau die Brennweite, und durch sie zu " +
                    "teilen liefert die Zeile selbst. Es wird kein " +
                    "Herstellerwert und keine Brennweite benoetigt.")
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            setText(
                "Ablauf\n" +
                    "1. Geraet auf ein strukturreiches Ziel richten " +
                    "(Buecherregal, Straße, gemustertes Tuch). " +
                    "Nicht auf eine leere Wand und nicht in den " +
                    "Himmel.\n" +
                    "2. Das Geraet im Handgelenk drehen - " +
                    "abwechselnd kippen, neigen und schwenken, in " +
                    "kleinen Kreisen. Wichtig ist, dass alle drei " +
                    "Drehachsen bewegt werden.\n" +
                    "3. Nicht dabei gehen. Gehen ist eine " +
                    "Verschiebung, und eine Verschiebung wird hier " +
                    "nicht modelliert; sie verfaelscht das Ergebnis.\n" +
                    "4. Waehrend der Aufnahme die Anzeige unten " +
                    "beobachten. Sie sagt sofort, ob die Bewegung " +
                    "ausgewertet werden kann.")
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            setText(
                "Es wird kein Bild gespeichert. Jedes Bild wird im " +
                    "Speicher auf ein Raster von " +
                    "$GRID_COLUMNS x $GRID_ROWS Helligkeitswerten " +
                    "reduziert und sofort verworfen.\n\n" +
                    "Nicht bestimmt wird der Versatz zwischen Kamera " +
                    "und Gyroskop als Strecke. Dafuer brauchte es " +
                    "eine Position, und fuer eine Position einen " +
                    "metrischen Maßstab. Beides fehlt hier.")
            setPadding(0, 0, 0, 16)
        })

        optInBox = CheckBox(this).apply {
            setText("Ich habe die Hinweise gelesen und stimme der " +
                "Messung zu.")
        }
        root.addView(optInBox)

        durationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CameraExtrinsicsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("6 Sekunden", "10 Sekunden (empfohlen)",
                    "15 Sekunden"),
            )
            setSelection(1)
        }
        root.addView(durationSpinner)

        startButton = Button(this).apply {
            setText("Messung starten")
            setOnClickListener { startExtrinsicsRun() }
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

        focalView = FocalSplitView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300)
        }
        root.addView(focalView)

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
            setText("ARKCAMEXT1 teilen")
            isEnabled = false
            setOnClickListener { shareRecord() }
        }
        root.addView(shareButton)

        copyButton = Button(this).apply {
            setText("In die Zwischenablage")
            isEnabled = false
            setOnClickListener { copyRecord() }
        }
        root.addView(copyButton)

        setContentView(scroll)
        title = "Kamera-IMU-Drehung"

        if (savedInstanceState != null) {
            optInBox.isChecked =
                savedInstanceState.getBoolean(STATE_OPT_IN, false)
            durationSpinner.setSelection(
                savedInstanceState.getInt(STATE_DURATION, 1)
                    .coerceIn(0, DURATION_MS.size - 1))
            statusText.setText(
                savedInstanceState.getString(STATE_STATUS, "") ?: "")
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
        val spread = synchronized(framesLock) {
            if (gyroTime.size >= 2) {
                axisSpreadDps()
            } else {
                0.0
            }
        }
        gateText.setText(gateLine("Bilder", frameCount.toLong(),
            ARK_CAMIMU_MIN_FRAMES.toLong(), ""))
        gateText.setText(gateText.text.toString() + "\n" +
            gateLine("Gyroskop", hz, ARK_CAMIMU_MIN_GYRO_RATE_HZ, " Hz"))
        gateText.setText(gateText.text.toString() + "\n" +
            gateLine("Bewegung", range.toLong(),
                ARK_CAMIMU_MIN_MOTION_RANGE_DPS.toLong(), " °/s"))
        // Specific to this measurement, and the one people get wrong: a run
        // can have plenty of motion and still be unusable if it only ever
        // moved about one axis.
        gateText.setText(gateText.text.toString() + "\n" +
            "Achsen getrennt bewegt: " + String.format(
                Locale.US, "%.0f", spread) +
            " °/s (alle drei Achsen brauchen Bewegung)")
    }

    /**
     * The smallest per-axis motion range, in degrees per second.
     *
     * The regression cannot see a component along an axis that was never
     * rotated, and no amount of motion on the other two makes up for it. So
     * the gate is the *weakest* axis, not the strongest and not the total.
     */
    private fun axisSpreadDps(): Double {
        if (gyroTime.size < 2) return 0.0
        return minOf(
            ArKernelCameraImuCorrelator.rangeOf(gyroX.toDoubleArray()),
            ArKernelCameraImuCorrelator.rangeOf(gyroY.toDoubleArray()),
            ArKernelCameraImuCorrelator.rangeOf(gyroZ.toDoubleArray()))
    }

    private fun gateLine(
        label: String,
        value: Long,
        minimum: Long,
        unit: String,
    ): String = "$label: $value$unit (mindestens $minimum$unit) " +
        if (value >= minimum) "OK" else "zu wenig"

    // ------------------------------------------------------------ capture

    private fun startExtrinsicsRun() {
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
        lastRotation = null
        resultText.setText("")
        focalView.setFocals(0.0, 0.0, false)
        statusText.setText("Vorschau startet ...")
        openCamera {
            startImu()
            statusText.setText("Aufnahme laeuft: jetzt in kleinen " +
                "Kreisen drehen und dabei kippen und neigen.")
            val handler = Handler(mainLooper)
            val startedAt = SystemClock.elapsedRealtime()
            val ticker = object : Runnable {
                override fun run() {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val remaining =
                        kotlin.math.max(0L, durationMs - elapsed) / 1_000L
                    if (capturing && remaining > 0L) {
                        statusText.setText("Aufnahme laeuft - noch " +
                            "$remaining s. Weiter drehen, und bitte " +
                            "alle drei Achsen bewegen.")
                        refreshGates()
                        handler.postDelayed(this, 250L)
                    } else if (capturing) {
                        statusText.setText("Aufnahme beendet. " +
                            "Drehung wird berechnet ...")
                        finishExtrinsicsRun(durationMs)
                    }
                }
            }
            handler.postDelayed(ticker, 250L)
        }
    }

    private fun finishExtrinsicsRun(durationMs: Long) {
        capturing = false
        stopImu()
        stopCamera()
        val duration = durationMs
        Thread {
            val outcome = buildRecord(duration)
            runOnUiThread {
                val record = outcome.record
                if (record == null) {
                    statusText.setText(outcome.reason)
                    startButton.isEnabled = true
                    return@runOnUiThread
                }
                currentRecord =
                    ArKernelCameraImuExtrinsicsAdapter.encode(record)
                val parsed =
                    ArKernelCameraImuExtrinsicsAdapter.parse(currentRecord)
                if (parsed.record == null) {
                    statusText.setText("Datensatz abgelehnt: " +
                        "${parsed.rejection} (${parsed.detail})")
                    startButton.isEnabled = true
                    return@runOnUiThread
                }
                lastRotation = record.matrix
                focalView.setFocals(
                    record.focalPxHorizontal, record.focalPxVertical,
                    record.focalOk)
                resultText.setText(describe(record))
                shareButton.isEnabled = true
                copyButton.isEnabled = true
                startButton.isEnabled = true
                val written = writeTextToDownloads(
                    "camext-$runId-${captureIndex + 1}.txt", currentRecord)
                statusText.setText(statusText.text.toString() +
                    "\nDatei: $written")
                captureIndex++
                Toast.makeText(this,
                    "ARKCAMEXT1 erzeugt. Bitte Text hier im Chat hochladen.",
                    Toast.LENGTH_LONG).show()
                announce("Kamera-IMU-Drehung gespeichert")
            }
        }.start()
    }

    private fun buildRecord(durationMs: Long): Outcome {
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
        if (snapshot.size < 3 || time.size < 4) {
            return Outcome(null, "Zu wenige Bilder oder Gyroskop-Werte. " +
                "Bitte erneut versuchen.")
        }

        val count = snapshot.size - 1
        val columns = GRID_COLUMNS
        val rows = GRID_ROWS
        // Pixels per grid sample. The shift is measured in grid samples, but
        // the focal length has to come out in pixels of the image the grid
        // was taken from - otherwise the number is in a unit that belongs to
        // no camera.
        val scaleX = (READER_WIDTH - 1).toDouble() / (columns - 1)
        val scaleY = (READER_HEIGHT - 1).toDouble() / (rows - 1)

        val mid = LongArray(count)
        val interval = LongArray(count)
        val shiftX = DoubleArray(count)
        val shiftY = DoubleArray(count)
        for (index in 0 until count) {
            val low = snapshot[index].timeNs
            val high = snapshot[index + 1].timeNs
            interval[index] = high - low
            mid[index] = low + (high - low) / 2L
            val shift = ArKernelCameraImuCorrelator.shift2d(
                snapshot[index].profile,
                snapshot[index + 1].profile,
                columns, rows)
            shiftX[index] = shift[0] * scaleX
            shiftY[index] = shift[1] * scaleY
        }

        val series = ArKernelCameraImuCorrelator.seriesForRotation(
            frameMidNs = mid,
            frameIntervalNs = interval,
            shiftX = shiftX,
            shiftY = shiftY,
            gyroTimeNs = time,
            gyroX = x, gyroY = y, gyroZ = z)
        val result = ArKernelCameraImuRotation.recover(
            rateX = series.rateX,
            rateY = series.rateY,
            gyroX = series.gyroX,
            gyroY = series.gyroY,
            gyroZ = series.gyroZ)
        val rotation = result.rotation
        if (rotation == null) {
            // Unobservable means the motion did not excite the axes, not that
            // the data was noisy - so say that, and say which way to fix it.
            val seen = result.conditioningPermille / 10.0
            val needed =
                ArKernelCameraImuRotation.MIN_CONDITIONING_PERMILLE / 10.0
            return Outcome(null,
                "Die Bewegung war zu einseitig: die Drehung ist daraus " +
                    "nicht bestimmbar (Guete " + seen + " %, " +
                    "mindestens " + needed + " %). Bitte alle drei " +
                    "Achsen bewegen - kippen, neigen und schwenken - " +
                    "und nicht dabei gehen.")
        }

        val intervals = interval.filter { it > 0L }.toLongArray()
        val frameIntervalMedianUs =
            ArKernelCameraImuCorrelator.medianOf(intervals) / 1_000L
        val gyroHz = if (time.size >= 2) {
            val span = (time[time.size - 1] - time[0]) / 1_000_000L
            if (span > 0L) time.size * 1_000L / span else 0L
        } else {
            0L
        }
        // Per axis, never on the magnitude: the magnitude of a rotation rate
        // is always positive, so its peak-to-peak span is about half the real
        // one and would pass the gate on a run that never reversed.
        val axisRange = doubleArrayOf(
            ArKernelCameraImuCorrelator.rangeOf(x),
            ArKernelCameraImuCorrelator.rangeOf(y),
            ArKernelCameraImuCorrelator.rangeOf(z))

        return Outcome(
            ArKernelCameraImuExtrinsics(
                runId = runId,
                deviceToken = deviceToken,
                cameraId = cameraIdText,
                cameraLevel = cameraLevel,
                cameraFacing = cameraFacing,
                sensorOrientationDeg = sensorDegrees,
                profileWidth = columns,
                profileHeight = rows,
                sourceWidth = READER_WIDTH,
                sourceHeight = READER_HEIGHT,
                frameCount = snapshot.size,
                gyroSampleCount = time.size,
                durationMs = durationMs,
                frameIntervalMedianUs = frameIntervalMedianUs,
                gyroRateMedianHz = gyroHz,
                motionRangeDps = axisRange.maxOrNull() ?: 0.0,
                matrix = rotation,
                focalPxHorizontal = result.focalPxHorizontal,
                focalPxVertical = result.focalPxVertical,
                focalSplitPermille = result.focalSplitPermille,
                conditioningPermille = result.conditioningPermille,
                residualPermille = result.residualPermille,
                correlationPermille = result.correlationPermille,
            ), "")
    }

    private fun describe(record: ArKernelCameraImuExtrinsics): String {
        val builder = StringBuilder()
        builder.append("Ergebnis\n")
        builder.append("Brennweite (aus waagerechter Bewegung): ")
            .append(String.format(Locale.US, "%.0f",
                record.focalPxHorizontal)).append(" px\n")
        builder.append("Brennweite (aus senkrechter Bewegung): ")
            .append(String.format(Locale.US, "%.0f",
                record.focalPxVertical)).append(" px\n")
        builder.append("Abweichung: ")
            .append(record.focalSplitPermille / 10.0)
            .append(" % (hoechstens ")
            .append(ArKernelCameraImuRotation.MAX_FOCAL_SPLIT_PERMILLE / 10.0)
            .append(" %)\n")
        builder.append("Guete der Bewegung: ")
            .append(record.conditioningPermille / 10.0)
            .append(" % (mindestens ")
            .append(ArKernelCameraImuRotation.MIN_CONDITIONING_PERMILLE / 10.0)
            .append(" %)\n")
        val euler = record.matrix.eulerDegrees()
        builder.append("Drehung als Winkel: Rollen ")
            .append(String.format(Locale.US, "%.1f", euler[0]))
            .append("°, Nicken ")
            .append(String.format(Locale.US, "%.1f", euler[1]))
            .append("°, Gieren ")
            .append(String.format(Locale.US, "%.1f", euler[2]))
            .append("°\n")
        builder.append("Guete: ")
            .append(ArKernelCameraImuExtrinsicsAdapter.report(record)
                .qualityGrade).append("\n")
        val steps = record.outstandingSteps()
        if (steps.isEmpty()) {
            builder.append("Alle Pruefungen bestanden.\n")
        } else {
            builder.append("Noch nicht ausreichend: ")
                .append(steps.map { stepName(it) }.joinToString(", "))
                .append("\n")
        }
        builder.append("\nDie Balken oben zeigen die beiden " +
            "Brennweiten. Sie messen dieselbe Groesse auf zwei " +
            "verschiedenen Wegen; liegen sie weit auseinander, war die " +
            "Bewegung keine reine Drehung - etwa weil das Geraet dabei " +
            "verschoben wurde - und das Ergebnis sollte verworfen werden.")
        return builder.toString()
    }

    private fun stepName(token: String): String = when (token) {
        "motion" -> "Bewegung zu einseitig"
        "rotation" -> "keine gueltige Drehmatrix"
        "focal" -> "Brennweiten widersprechen sich"
        else -> token
    }

    // ------------------------------------------------------------ camera

    private fun openCamera(onReady: () -> Unit) {
        val manager = getSystemService(CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            statusText.setText("Kein Kamera-Dienst verfuegbar.")
            capturing = false
            startButton.isEnabled = true
            return
        }
        val cameraId = backCameraId(manager)
        if (cameraId == null) {
            statusText.setText("Keine Rueckkamera gefunden.")
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
        val thread = HandlerThread("arkcamext-camera").apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler
        val surfaceTexture = textureView.surfaceTexture
            ?: run {
                statusText.setText("Vorschau noch nicht bereit.")
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
            statusText.setText(message)
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
                pixelStride = 1,
                columns = GRID_COLUMNS,
                rows = GRID_ROWS)
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
        val thread = HandlerThread("arkcamext-imu").apply { start() }
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
        startActivity(Intent.createChooser(intent, "ARKCAMEXT1 teilen"))
    }

    private fun copyRecord() {
        if (currentRecord.isEmpty()) return
        val manager = getSystemService(CLIPBOARD_SERVICE) as?
            android.content.ClipboardManager ?: return
        manager.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "ARKCAMEXT1", currentRecord))
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
            startExtrinsicsRun()
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

    /**
     * Draws the two focal estimates as bars.
     *
     * They are two routes to the same quantity - the length of a unit vector
     * - so the picture is the gate: bars that disagree mean the motion was
     * not a pure rotation, and no amount of internal consistency will notice.
     */
    private class FocalSplitView(context: android.content.Context) :
        View(context) {

        private var horizontal = 0.0
        private var vertical = 0.0
        private var ok = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 26f
        }

        private companion object {
            val NAMES = arrayOf("waagerecht", "senkrecht")
            val COLORS = intArrayOf(
                Color.rgb(0x2b, 0x7a, 0xba),
                Color.rgb(0xd8, 0x11, 0x62),
            )
        }

        fun setFocals(horizontalPx: Double, verticalPx: Double, ok: Boolean) {
            horizontal = horizontalPx
            vertical = verticalPx
            this.ok = ok
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            if (horizontal <= 0.0 || vertical <= 0.0) {
                paint.color = Color.GRAY
                paint.textSize = 28f
                canvas.drawText("Noch keine Messung", 24f, height / 2f, paint)
                return
            }
            val left = 30f
            val right = width - 24f
            val top = 60f
            val bottom = height - 60f
            val peak = maxOf(horizontal, vertical) * 1.15
            val values = doubleArrayOf(horizontal, vertical)
            val barWidth = (right - left) / 4f
            for (index in 0..1) {
                val barLeft = left + index * barWidth * 1.5f
                val barHeight = (values[index] / peak *
                    (bottom - top)).toFloat()
                paint.color = COLORS[index]
                canvas.drawRect(barLeft, bottom - barHeight,
                    barLeft + barWidth, bottom, paint)
                paint.color = Color.DKGRAY
                paint.textSize = 28f
                canvas.drawText(
                    String.format(Locale.US, "%.0f px", values[index]),
                    barLeft, bottom - barHeight - 12f, paint)
                canvas.drawText(NAMES[index], barLeft, bottom + 34f, paint)
            }
            paint.color = Color.LTGRAY
            paint.strokeWidth = 2f
            canvas.drawLine(left, bottom, right, bottom, paint)
            val split = kotlin.math.abs(horizontal - vertical) /
                ((horizontal + vertical) / 2.0) * 1_000.0
            axisPaint.color = if (ok) Color.rgb(0x00, 0x80, 0x00)
            else Color.rgb(0xc0, 0x00, 0x00)
            canvas.drawText(
                "Abweichung " + String.format(Locale.US, "%.1f",
                    split / 10.0) + " % - " +
                    if (ok) "stimmt ueberein" else "zu gross",
                left, top - 16f, axisPaint)
        }
    }
}
