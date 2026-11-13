package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelImuCalibration
import ai.arena.arkernel.ArKernelImuCalibrationAdapter
import ai.arena.arkernel.ArKernelImuCalibrationSummary
import ai.arena.arkernel.ArKernelImuPoseDirection
import ai.arena.arkernel.ArKernelImuPoseRow
import ai.arena.arkernel.ArKernelImuStaticRow
import ai.arena.arkernel.ArKernelImuStreamRow
import ai.arena.arkernel.ArKernelImuTimestampDomain
import ai.arena.arkernel.ArKernelImuTurnAxis
import ai.arena.arkernel.ArKernelImuTurnRow
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private data class ImuCalSample(
    val t: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

private data class ImuCapture(
    val accel: List<ImuCalSample>,
    val gyro: List<ImuCalSample>,
)

private data class CadenceStats(
    val count: Int,
    val measuredHz: Double,
    val minDtUs: Long,
    val maxDtUs: Long,
    val gapCount: Int,
    val duplicateCount: Int,
    val monotonicViolations: Int,
)

private data class StaticsBundle(
    val sampleCount: Long,
    val gyroSampleCount: Long,
    val meanGyroX: Double,
    val meanGyroY: Double,
    val meanGyroZ: Double,
    val rmsAcc: Double,
    val rmsGyro: Double,
    val stabAcc: Double,
    val stabGyro: Double,
    val maxGapMs: Long,
    val temperatureC: Double,
)

private data class TurnBundle(
    val integratedAngleDeg: Double,
    val offAxisAngleDeg: Double,
    val durationMs: Long,
    val sampleCount: Long,
    val peakRateDps: Double,
)

/**
 * The physical inertial calibration of one device, run by hand.
 *
 * Four measurement phases, in the order they have to be run:
 *
 * 1. **Cadence** — three seconds of raw sensor delivery. Nothing about physics,
 *    just whether the platform hands over what it advertises: the rate, the
 *    gaps, the duplicate timestamps and which clock the timestamps use.
 * 2. **Static** — the device at rest. From this come the gyroscope bias, the
 *    noise of both triads and how much the averages wander (bias stability).
 * 3. **Tumble** — six orientations, each axis once up and once down. Gravity
 *    cancels between opposite poses, so the mean of the six gives the
 *    accelerometer bias and each pair's difference gives one column of its
 *    scale-and-misalignment matrix.
 * 4. **Turn** — rotations through an angle the operator imposes with a jig.
 *    The gyroscope is never asked how large its own angle was; the ratio to
 *    the jig's angle is its scale factor.
 *
 * This screen measures the inertial sensors and nothing else. It captures no
 * image, estimates no camera extrinsic, searches no time offset and raises no
 * quality grade: a good inertial calibration is a precondition for a working
 * visual-inertial system, not evidence that one works.
 */
class ImuCalibrationActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_SESSION = "imucal.session"
        const val STATE_DEVICE = "imucal.device"
        const val STATE_GRAVITY = "imucal.gravity"
        const val STATE_REPORT = "imucal.report"
        const val STATE_STATUS = "imucal.status"
        const val MAX_STREAMS = 4
        const val MAX_STATICS = 8
        const val MAX_POSES = 12
        const val MAX_TURNS = 16
        const val MAX_SAMPLES = 400_000
        const val CADENCE_MS = 3_000L
        const val POSE_MS = 3_000L
        const val STABILITY_BLOCKS = 10
        const val DOMAIN_TOLERANCE_NS = 2_000_000_000L
        const val FALLBACK_TEMPERATURE_C = 25.0
        const val ID_PATTERN = "[A-Za-z0-9._:-]{1,64}"

        val streams = ArrayList<ArKernelImuStreamRow>()
        val statics = ArrayList<ArKernelImuStaticRow>()
        val poses = ArrayList<ArKernelImuPoseRow>()
        val turns = ArrayList<ArKernelImuTurnRow>()

        val DIRECTIONS = arrayOf(
            "px · +X oben (auf die linke Kante stellen)",
            "nx · -X oben (auf die rechte Kante stellen)",
            "py · +Y oben (aufrecht hinstellen)",
            "ny · -Y oben (auf den Kopf stellen)",
            "pz · +Z oben (flach, Display nach oben)",
            "nz · -Z oben (flach, Display nach unten)",
        )
    }

    private lateinit var sessionInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var gravityInput: EditText
    private lateinit var gapInput: EditText
    private lateinit var rateInput: EditText
    private lateinit var accelBiasInput: EditText
    private lateinit var gyroBiasInput: EditText
    private lateinit var gyroScaleInput: EditText

    private lateinit var durationSpinner: Spinner
    private lateinit var directionSpinner: Spinner
    private lateinit var axisSpinner: Spinner
    private lateinit var referenceInput: EditText

    private lateinit var cadenceButton: Button
    private lateinit var staticButton: Button
    private lateinit var poseButton: Button
    private lateinit var turnButton: Button
    private lateinit var resetButton: Button
    private lateinit var evaluateButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button

    private lateinit var listText: TextView
    private lateinit var reportText: TextView
    private lateinit var statusText: TextView

    private var lastCalibration: ArKernelImuCalibration? = null
    private var turnRunning = false
    private var turnAxisIndex = 0
    private var turnStartMs = 0L
    private var turnThread: HandlerThread? = null
    private var turnHandler: Handler? = null
    private var turnListener: SensorEventListener? = null
    private val turnSamples =
        Collections.synchronizedList(ArrayList<ImuCalSample>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            streams.clear()
            statics.clear()
            poses.clear()
            turns.clear()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "IMU-Kalibrierung (ARKIMUCAL1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für die inertialen Testreihen"
        }
        val version = TextView(this).apply {
            text = "Version 2.18-imucal"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "Vier Testreihen in dieser Reihenfolge: " +
                "1) Kadenz prüfen (3 s, Handy liegt). " +
                "2) Statik aufnehmen (Handy liegt still). " +
                "3) Sechs Lagen (jede Achse einmal nach oben, " +
                "einmal nach unten – am besten zwei Durchgänge). " +
                "4) Drehungen um einen bekannten Winkel, z. B. " +
                "90 Grad an einer Gehrungslade oder einem " +
                "rechtwinkligen Karton. Danach „Auswerten“."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der inertialen Testreihen"
        }
        val safety = TextView(this).apply {
            text = "Dieser Bildschirm macht keine Bilder und lädt " +
                "nichts hoch. Er rechnet nur aus den Sensordaten, " +
                "die du gerade aufnimmst. Die Bewertung bleibt " +
                "unverified – eine gute Trägheitskalibrierung ist " +
                "Voraussetzung für ein funktionierendes System, " +
                "kein Beweis dafür."
            textSize = 12f
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Hinweis zu Aufnahme und Bewertung"
        }

        sessionInput = textEdit("imu.001", "Sitzungskennung")
        deviceInput = textEdit("phone.a", "Gerätekennung")
        gravityInput = decimalEdit("9.80665", "Örtliche Fallbeschleunigung")
        gapInput = intEdit("50", "Erlaubte größte Lücke in ms")
        rateInput = intEdit("100", "Erlaubte Abweichung in Promille")
        accelBiasInput = decimalEdit("0.100", "Grenze Beschleuniger-Bias")
        gyroBiasInput = decimalEdit("0.500", "Grenze Gyroskop-Bias")
        gyroScaleInput = intEdit("50", "Grenze Maßstabsfehler in Promille")

        durationSpinner = spinnerOf(
            arrayOf("10 s Statik", "30 s Statik", "60 s Statik"),
            "Dauer der Statik-Aufnahme",
        )
        directionSpinner = spinnerOf(DIRECTIONS, "Lage des Geräts")
        axisSpinner = spinnerOf(
            arrayOf("x · um die Querachse", "y · um die Längsachse",
                "z · um die Blickachse"),
            "Drehachse",
        )
        referenceInput = decimalEdit("90.0", "Sollwinkel der Drehung")

        cadenceButton = Button(this).apply {
            text = "1) Kadenz prüfen (3 s)"
            textSize = 15f
            setAllCaps(false)
        }
        staticButton = Button(this).apply {
            text = "2) Statik aufnehmen"
            textSize = 15f
            setAllCaps(false)
        }
        poseButton = Button(this).apply {
            text = "3) Lage aufnehmen (3 s)"
            textSize = 15f
            setAllCaps(false)
        }
        turnButton = Button(this).apply {
            text = "4) Drehung: START"
            textSize = 15f
            setAllCaps(false)
        }
        resetButton = Button(this).apply {
            text = "Alles zurücksetzen"
            textSize = 14f
            setAllCaps(false)
        }
        evaluateButton = Button(this).apply {
            text = "Kalibrierung auswerten"
            textSize = 16f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "Datei speichern"
            textSize = 15f
            setAllCaps(false)
        }
        shareButton = Button(this).apply {
            text = "Datei teilen"
            textSize = 15f
            setAllCaps(false)
        }
        copyButton = Button(this).apply {
            text = "In die Zwischenablage"
            textSize = 15f
            setAllCaps(false)
        }

        listText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        reportText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#FFF7ED"))
        }
        statusText = TextView(this).apply { textSize = 12f }

        root.addView(title)
        root.addView(version)
        root.addView(intro)
        root.addView(safety)
        labelled(root, "Sitzung (frei wählbar)", sessionInput)
        labelled(root, "Gerät (frei wählbar)", deviceInput)
        labelled(
            root,
            "Örtliche Fallbeschleunigung in m/s² " +
                "(9.80665 ist der Normwert; wer es genauer weiß, " +
                "trägt den Wert für den eigenen Ort ein)",
            gravityInput,
        )
        labelled(root, "Größte erlaubte Lücke in der Sensorfolge (ms)",
            gapInput)
        labelled(
            root,
            "Größte erlaubte Abweichung der Abtastrate (Promille)",
            rateInput,
        )
        labelled(root, "Grenze Beschleuniger-Bias (m/s²)", accelBiasInput)
        labelled(root, "Grenze Gyroskop-Bias (°/s)", gyroBiasInput)
        labelled(root, "Grenze Maßstabsfehler Gyroskop (Promille)",
            gyroScaleInput)
        root.addView(TextView(this).apply {
            text = "Reihe 1 · Kadenz"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 4)
        })
        root.addView(cadenceButton)
        root.addView(TextView(this).apply {
            text = "Reihe 2 · Statik"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 4)
        })
        labelled(root, "Dauer – das Gerät muss die ganze Zeit liegen",
            durationSpinner)
        root.addView(staticButton)
        root.addView(TextView(this).apply {
            text = "Reihe 3 · Sechs Lagen"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 4)
        })
        labelled(root, "Welche Achse zeigt nach oben?", directionSpinner)
        root.addView(poseButton)
        root.addView(TextView(this).apply {
            text = "Reihe 4 · Drehung um einen bekannten Winkel"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 4)
        })
        labelled(root, "Achse der Drehung", axisSpinner)
        labelled(
            root,
            "Sollwinkel in Grad, mit Vorzeichen: positiv ist eine " +
                "Drehung rechtsherum um die Achse (rechte-Hand-Regel, " +
                "Daumen in Achsrichtung). Wer unsicher ist, dreht in " +
                "beide Richtungen und trägt +90 bzw. -90 ein.",
            referenceInput,
        )
        root.addView(turnButton)
        root.addView(TextView(this).apply {
            text = "Aufgenommen"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 4)
        })
        root.addView(listText)
        root.addView(evaluateButton)
        root.addView(reportText)
        root.addView(saveButton)
        root.addView(shareButton)
        root.addView(copyButton)
        root.addView(resetButton)
        root.addView(statusText)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        cadenceButton.setOnClickListener { runCadence() }
        staticButton.setOnClickListener { runStatic() }
        poseButton.setOnClickListener { runPose() }
        turnButton.setOnClickListener { toggleTurn() }
        resetButton.setOnClickListener { resetAll() }
        evaluateButton.setOnClickListener { evaluate() }
        saveButton.setOnClickListener { saveFile() }
        shareButton.setOnClickListener { shareFile() }
        copyButton.setOnClickListener { copyFile() }

        refreshList()
    }

    // ---- measurement phases ----------------------------------------------

    private fun runCadence() {
        if (streams.size + 2 > MAX_STREAMS) {
            statusText.text = "Nur $MAX_STREAMS Datenströme möglich."
            return
        }
        val manager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val accelSensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (manager == null || accelSensor == null || gyroSensor == null) {
            statusText.text = "Kein Beschleuniger oder Gyroskop gefunden."
            return
        }
        busy(true, "Kadenz wird aufgezeichnet – 3 s nicht bewegen …")
        Thread {
            val pair = capture(CADENCE_MS)
            val accelStats = cadenceOf(pair.accel)
            val gyroStats = cadenceOf(pair.gyro)
            val accelDomain = domainOf(pair.accel)
            val gyroDomain = domainOf(pair.gyro)
            runOnUiThread {
                busy(false, "")
                if (accelStats == null || gyroStats == null) {
                    statusText.text = "Zu wenige Messwerte aufgezeichnet."
                    return@runOnUiThread
                }
                streams += ArKernelImuStreamRow(
                    index = streams.size,
                    label = "accelerometer",
                    nominalHz = nominalHzOf(accelSensor, accelStats),
                    measuredHz = accelStats.measuredHz,
                    minDtUs = accelStats.minDtUs,
                    maxDtUs = accelStats.maxDtUs,
                    gapCount = accelStats.gapCount,
                    duplicateCount = accelStats.duplicateCount,
                    monotonicViolations = accelStats.monotonicViolations,
                    domain = accelDomain,
                )
                streams += ArKernelImuStreamRow(
                    index = streams.size,
                    label = "gyroscope",
                    nominalHz = nominalHzOf(gyroSensor, gyroStats),
                    measuredHz = gyroStats.measuredHz,
                    minDtUs = gyroStats.minDtUs,
                    maxDtUs = gyroStats.maxDtUs,
                    gapCount = gyroStats.gapCount,
                    duplicateCount = gyroStats.duplicateCount,
                    monotonicViolations = gyroStats.monotonicViolations,
                    domain = gyroDomain,
                )
                refreshList()
                statusText.text = "Kadenz aufgezeichnet."
            }
        }.start()
    }

    private fun runStatic() {
        if (statics.size >= MAX_STATICS) {
            statusText.text = "Nur $MAX_STATICS Statik-Aufnahmen möglich."
            return
        }
        val durationMs = staticDurationMs()
        val temperature = temperatureC()
        busy(
            true,
            "Statik läuft – Gerät liegt still, nicht berühren " +
                "(noch ${durationMs / 1000} s) …",
        )
        Thread {
            val pair = capture(durationMs)
            val bundle = staticsOf(pair.accel, pair.gyro, temperature)
            runOnUiThread {
                busy(false, "")
                if (bundle == null) {
                    statusText.text = "Zu wenige Messwerte aufgezeichnet."
                    return@runOnUiThread
                }
                statics += ArKernelImuStaticRow(
                    index = statics.size,
                    label = "statik-${statics.size + 1}",
                    durationMs = durationMs,
                    sampleCount = bundle.sampleCount,
                    gyroSampleCount = bundle.gyroSampleCount,
                    meanGyroXDps = bundle.meanGyroX,
                    meanGyroYDps = bundle.meanGyroY,
                    meanGyroZDps = bundle.meanGyroZ,
                    rmsAccMps2 = bundle.rmsAcc,
                    rmsGyroDps = bundle.rmsGyro,
                    stabilityAccMps2 = bundle.stabAcc,
                    stabilityGyroDps = bundle.stabGyro,
                    maxGapMs = bundle.maxGapMs,
                    temperatureC = bundle.temperatureC,
                )
                refreshList()
                statusText.text = "Statik aufgezeichnet."
            }
        }.start()
    }

    private fun runPose() {
        if (poses.size >= MAX_POSES) {
            statusText.text = "Nur $MAX_POSES Lagen möglich."
            return
        }
        val direction = directionFromSpinner()
        val temperature = temperatureC()
        busy(true, "Lage wird gehalten – 3 s ruhig halten …")
        Thread {
            val pair = capture(POSE_MS)
            val accel = pair.accel
            val gyro = pair.gyro
            runOnUiThread {
                busy(false, "")
                if (accel.size < 2 || gyro.size < 2) {
                    statusText.text = "Zu wenige Messwerte aufgezeichnet."
                    return@runOnUiThread
                }
                poses += ArKernelImuPoseRow(
                    index = poses.size,
                    label = poseLabel(direction),
                    direction = direction,
                    sampleCount = accel.size.toLong(),
                    meanXMps2 = meanOf(accel, 0),
                    meanYMps2 = meanOf(accel, 1),
                    meanZMps2 = meanOf(accel, 2),
                    meanGyroXDps = meanOf(gyro, 0),
                    meanGyroYDps = meanOf(gyro, 1),
                    meanGyroZDps = meanOf(gyro, 2),
                    temperatureC = temperature,
                )
                refreshList()
                statusText.text = "Lage aufgezeichnet: " +
                    direction.wireName()
            }
        }.start()
    }

    private fun toggleTurn() {
        if (turnRunning) stopTurn() else startTurn()
    }

    private fun startTurn() {
        if (turns.size >= MAX_TURNS) {
            statusText.text = "Nur $MAX_TURNS Drehungen möglich."
            return
        }
        val reference = referenceInput.text.toString().trim().toDoubleOrNull()
        if (reference == null || reference == 0.0) {
            statusText.text = "Sollwinkel fehlt oder ist 0."
            return
        }
        val manager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val gyroSensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (manager == null || gyroSensor == null) {
            statusText.text = "Kein Gyroskop gefunden."
            return
        }
        turnAxisIndex = axisSpinner.selectedItemPosition
        synchronized(turnSamples) { turnSamples.clear() }
        val thread = HandlerThread("arkernel-imucal-turn").apply { start() }
        val handler = Handler(thread.looper)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                synchronized(turnSamples) {
                    if (turnSamples.size < MAX_SAMPLES) {
                        turnSamples.add(
                            ImuCalSample(
                                event.timestamp,
                                event.values[0],
                                event.values[1],
                                event.values[2],
                            ),
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) =
                Unit
        }
        manager.registerListener(
            listener, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        turnThread = thread
        turnHandler = handler
        turnListener = listener
        turnStartMs = SystemClock.elapsedRealtime()
        turnRunning = true
        turnButton.text = "4) Drehung: STOP"
        setCaptureButtons(false)
        statusText.text = "Drehung läuft – jetzt um $reference° drehen …"
    }

    private fun stopTurn() {
        val manager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val listener = turnListener
        if (manager != null && listener != null) {
            manager.unregisterListener(listener)
        }
        turnHandler?.removeCallbacksAndMessages(null)
        turnThread?.quitSafely()
        turnThread = null
        turnHandler = null
        turnListener = null
        turnRunning = false
        turnButton.text = "4) Drehung: START"
        setCaptureButtons(true)
        val durationMs = SystemClock.elapsedRealtime() - turnStartMs
        val samples = synchronized(turnSamples) { turnSamples.toList() }
        val reference = referenceInput.text.toString().trim()
            .toDoubleOrNull() ?: 0.0
        val axis = axisFromIndex(turnAxisIndex)
        val bundle = turnOf(samples, turnAxisIndex)
        if (bundle == null || reference == 0.0) {
            statusText.text = "Zu wenige Messwerte für die Drehung."
            return
        }
        turns += ArKernelImuTurnRow(
            index = turns.size,
            label = "turn-${axis.wireName()}-${reference.toInt()}",
            axis = axis,
            referenceAngleDeg = reference,
            integratedAngleDeg = bundle.integratedAngleDeg,
            offAxisAngleDeg = bundle.offAxisAngleDeg,
            durationMs = durationMs,
            sampleCount = bundle.sampleCount,
            peakRateDps = bundle.peakRateDps,
        )
        refreshList()
        statusText.text = "Drehung aufgezeichnet: integriert " +
            String.format(Locale.US, "%.2f", bundle.integratedAngleDeg) +
            "° vs. Soll " +
            String.format(Locale.US, "%.2f", reference) + "°."
    }

    // ---- evaluation ---------------------------------------------------------

    private fun evaluate() {
        val session = sessionInput.text.toString().trim()
        val device = deviceInput.text.toString().trim()
        if (!session.matches(Regex(ID_PATTERN)) ||
            !device.matches(Regex(ID_PATTERN))
        ) {
            statusText.text = "Sitzung und Gerät dürfen nur aus " +
                "Buchstaben, Zahlen, Punkt, Bindestrich, " +
                "Unterstrich, Doppelpunkt bestehen."
            return
        }
        val created = ArKernelImuCalibrationAdapter.create(
            sessionId = session,
            deviceToken = device,
            referenceGravityMps2 = doubleOf(gravityInput, 9.80665),
            requiredTemperatureSpanC = 0.0,
            minRepeatCycles = 2,
            minStaticDurationMs = 10_000L,
            maxGapMs = longOf(gapInput, 50L),
            maxRateErrorPermille = intOf(rateInput, 100),
            maxTurnRateDps = 2_000.0,
            maxGravityErrorMps2 = 0.050,
            maxAccelBiasMps2 = doubleOf(accelBiasInput, 0.100),
            maxAccelScaleErrorPermille = 50,
            maxAccelMisalignmentPermille = 50,
            maxAccelResidualMps2 = 0.030,
            maxAccelNoiseMgSqrtHz = 20.0,
            maxGyroBiasDps = doubleOf(gyroBiasInput, 0.500),
            maxGyroScaleErrorPermille = intOf(gyroScaleInput, 50),
            maxCrossAxisPermille = 100,
            maxGyroArwDegSqrtHr = 5.0,
            maxAccelBiasStabilityMg = 5.0,
            maxGyroBiasStabilityDph = 30.0,
            streams = streams.toList(),
            statics = statics.toList(),
            poses = poses.toList(),
            turns = turns.toList(),
        )
        if (!created.accepted) {
            lastCalibration = null
            reportText.text = "Abgelehnt: ${created.rejection} – " +
                created.detail
            statusText.text = "Nicht auswertbar."
            return
        }
        val calibration = created.calibration!!
        lastCalibration = calibration
        val summary = ArKernelImuCalibrationAdapter.summarize(calibration)
        reportText.text = reportOf(calibration, summary)
        statusText.text = if (summary.calibrationSufficient) {
            "Alle elf Prüfungen bestanden. unverified."
        } else {
            "Noch nicht ausreichend – siehe Bericht. unverified."
        }
    }

    private fun reportOf(
        calibration: ArKernelImuCalibration,
        summary: ArKernelImuCalibrationSummary,
    ): String {
        val out = StringBuilder()
        out.append("ARKIMUCAL1 · ${calibration.deviceToken}\n")
        out.append("Ströme ${summary.streamCount} · Statik " +
            "${summary.staticCount} (${summary.staticSampleTotal} Werte) · " +
            "Lagen ${summary.poseCount} " +
            "(${summary.poseSampleTotal} Werte) · " +
            "Drehungen ${summary.turnCount}\n\n")
        out.append("BESCHLEUNIGER\n")
        out.append("  Bias  x/y/z  " + f(summary.accelBiasXMps2, 4) + " / " +
            f(summary.accelBiasYMps2, 4) + " / " +
            f(summary.accelBiasZMps2, 4) + " m/s²  " +
            "(Betrag " + f(summary.accelBiasNormMps2, 4) + ")\n")
        out.append("  Maßstab x/y/z  " + f(summary.accelScaleXPermille, 1) +
            " / " + f(summary.accelScaleYPermille, 1) + " / " +
            f(summary.accelScaleZPermille, 1) + " ‰\n")
        out.append("  größte Fehlstellung  " +
            summary.accelMisalignmentMaxPermille + " ‰\n")
        out.append("  Abweichung von g  " +
            f(summary.gravityMagnitudeErrorMps2, 4) + " m/s²\n")
        out.append("  Wiederholstreuung  " +
            f(summary.accelResidualRmsMps2, 4) + " m/s² " +
            "(" + summary.accelRepeatCycles + " Durchgänge)\n")
        out.append("  Rauschen  " +
            f(summary.accelNoiseDensityMgSqrtHz, 2) + " mg/√Hz · " +
            "Bias-Stabilität " + f(summary.accelBiasStabilityMg, 2) +
            " mg\n\n")
        out.append("GYROSKOP\n")
        out.append("  Bias  x/y/z  " + f(summary.gyroBiasXDps, 4) + " / " +
            f(summary.gyroBiasYDps, 4) + " / " +
            f(summary.gyroBiasZDps, 4) + " °/s  " +
            "(Betrag " + f(summary.gyroBiasNormDps, 4) + ")\n")
        out.append("  Lagenabhängigkeit des Bias  " +
            f(summary.gyroBiasSpreadDps, 4) + " °/s\n")
        out.append("  Maßstabsfehler  " +
            summary.gyroScaleErrorMaxPermille + " ‰ · " +
            "Nebenachsen " + summary.gyroCrossAxisMaxPermille + " ‰\n")
        out.append("  Winkelrauschen  " + f(summary.gyroArwDegSqrtHr, 3) +
            " °/√h · Bias-Stabilität " +
            f(summary.gyroBiasStabilityDph, 2) + " °/h\n\n")
        out.append("FREIE INTEGRATION (eine Minute ohne Korrektur)\n")
        out.append("  Lage driftet  " + f(summary.angleDriftDegPerMin, 2) +
            " °/min\n")
        out.append("  Position driftet  " +
            f(summary.positionDriftMPerMin, 2) + " m/min\n")
        out.append("  Temperaturspanne  " +
            f(summary.temperatureSpanC, 1) + " °C\n\n")
        out.append("PRÜFUNGEN\n")
        for (line in verdictLines(summary)) out.append("  $line\n")
        if (summary.outstanding.isNotEmpty()) {
            out.append("\nNoch offen: " +
                summary.outstanding.joinToString(", ") + "\n")
        }
        out.append("\nBewertung: unverified. Diese Kalibrierung ist eine " +
            "Voraussetzung, kein Genauigkeitsnachweis.")
        return out.toString()
    }

    private fun verdictLines(
        summary: ArKernelImuCalibrationSummary,
    ): List<String> {
        val lines = ArrayList<String>()
        lines += mark(summary.cadenceOk) +
            " Kadenz (Lücke " + summary.observedMaxGapMs + " ms, " +
            "Ratenabweichung " + summary.observedMaxRateErrorPermille + " ‰)"
        lines += mark(summary.durationOk) + " Aufnahmedauern lang genug"
        lines += mark(summary.gravityOk) +
            " Betrag der Ruhemessung trifft g"
        lines += mark(summary.accelFitOk) +
            " Beschleuniger-Modell vollständig bestimmbar"
        lines += mark(summary.accelRepeatChecked) +
            " Lagen wiederholt und wiederholbar"
        lines += mark(summary.gyroScaleOk) +
            " Gyroskop-Maßstab gegen die Lehre geprüft"
        lines += mark(summary.biasOk) + " Gyroskop-Bias innerhalb der Grenze"
        lines += mark(summary.noiseOk) + " Rauschen innerhalb der Grenze"
        lines += mark(summary.stabilityOk) +
            " Bias-Stabilität innerhalb der Grenze"
        lines += mark(summary.thermalCovered) +
            " verlangte Temperaturspanne erreicht"
        lines += mark(summary.calibrationSufficient) +
            " Kalibrierung insgesamt ausreichend"
        return lines
    }

    private fun mark(ok: Boolean): String = if (ok) "[ok]  " else "[off] "

    private fun f(value: Double, decimals: Int): String = when (decimals) {
        0 -> String.format(Locale.US, "%.0f", value)
        1 -> String.format(Locale.US, "%.1f", value)
        2 -> String.format(Locale.US, "%.2f", value)
        3 -> String.format(Locale.US, "%.3f", value)
        else -> String.format(Locale.US, "%.4f", value)
    }

    // ---- sensor plumbing ----------------------------------------------------

    private fun capture(durationMs: Long): ImuCapture {
        val manager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val accelSensor =
            manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (manager == null || accelSensor == null || gyroSensor == null) {
            return ImuCapture(emptyList(), emptyList())
        }
        val thread = HandlerThread("arkernel-imucal").apply { start() }
        val handler = Handler(thread.looper)
        val accel =
            Collections.synchronizedList(ArrayList<ImuCalSample>())
        val gyro =
            Collections.synchronizedList(ArrayList<ImuCalSample>())
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                val sample = ImuCalSample(
                    event.timestamp,
                    event.values[0],
                    event.values[1],
                    event.values[2],
                )
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER ->
                        synchronized(accel) {
                            if (accel.size < MAX_SAMPLES) accel.add(sample)
                        }
                    Sensor.TYPE_GYROSCOPE ->
                        synchronized(gyro) {
                            if (gyro.size < MAX_SAMPLES) gyro.add(sample)
                        }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) =
                Unit
        }
        manager.registerListener(
            listener, accelSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        manager.registerListener(
            listener, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        try {
            val done = CountDownLatch(1)
            handler.postDelayed({ done.countDown() }, durationMs)
            done.await(durationMs + 5_000L, TimeUnit.MILLISECONDS)
        } finally {
            manager.unregisterListener(listener)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        val accelCopy = synchronized(accel) { accel.toList() }
        val gyroCopy = synchronized(gyro) { gyro.toList() }
        return ImuCapture(accelCopy, gyroCopy)
    }

    private fun cadenceOf(samples: List<ImuCalSample>): CadenceStats? {
        if (samples.size < 3) return null
        val deltas = ArrayList<Long>(samples.size - 1)
        for (i in 1 until samples.size) {
            deltas += samples[i].t - samples[i - 1].t
        }
        val sorted = deltas.sorted()
        val median = sorted[sorted.size / 2]
        val threshold = if (median > 0L) median * 2L else Long.MAX_VALUE
        var gaps = 0
        var duplicates = 0
        var backwards = 0
        for (dt in deltas) {
            if (dt == 0L) duplicates++
            else if (dt < 0L) backwards++
            else if (dt > threshold) gaps++
        }
        val span = samples[samples.size - 1].t - samples[0].t
        val measuredHz = if (span > 0L) {
            (samples.size - 1) * 1_000_000.0 / span.toDouble()
        } else {
            0.0
        }
        return CadenceStats(
            count = samples.size,
            measuredHz = measuredHz,
            minDtUs = sorted.first(),
            maxDtUs = sorted.last(),
            gapCount = gaps,
            duplicateCount = duplicates,
            monotonicViolations = backwards,
        )
    }

    /**
     * Android hands sensor timestamps on one of three clocks and nothing says
     * which. The closest clock within two seconds wins; if none is close, the
     * stream cannot be merged with another one at all.
     */
    private fun domainOf(samples: List<ImuCalSample>):
        ArKernelImuTimestampDomain {
        if (samples.isEmpty()) return ArKernelImuTimestampDomain.UNKNOWN
        val t = samples[samples.size / 2].t
        if (kotlin.math.abs(t - SystemClock.elapsedRealtimeNanos()) <
            DOMAIN_TOLERANCE_NS
        ) {
            return ArKernelImuTimestampDomain.ELAPSED_REALTIME
        }
        if (kotlin.math.abs(t - SystemClock.uptimeMillis() * 1_000_000L) <
            DOMAIN_TOLERANCE_NS
        ) {
            return ArKernelImuTimestampDomain.UPTIME
        }
        if (kotlin.math.abs(t - System.currentTimeMillis() * 1_000_000L) <
            DOMAIN_TOLERANCE_NS
        ) {
            return ArKernelImuTimestampDomain.WALL
        }
        return ArKernelImuTimestampDomain.UNKNOWN
    }

    private fun nominalHzOf(sensor: Sensor, stats: CadenceStats): Int {
        val minDelay = sensor.minDelay
        val advertised = if (minDelay > 0) 1_000_000 / minDelay else 0
        return if (advertised > 0) {
            advertised.coerceIn(1, 10_000)
        } else {
            stats.measuredHz.toInt().coerceIn(1, 10_000)
        }
    }

    private fun staticsOf(
        accel: List<ImuCalSample>,
        gyro: List<ImuCalSample>,
        temperatureC: Double,
    ): StaticsBundle? {
        if (accel.size < 2 || gyro.size < 2) return null
        return StaticsBundle(
            sampleCount = accel.size.toLong(),
            gyroSampleCount = gyro.size.toLong(),
            meanGyroX = meanOf(gyro, 0),
            meanGyroY = meanOf(gyro, 1),
            meanGyroZ = meanOf(gyro, 2),
            rmsAcc = rmsOf(accel),
            rmsGyro = rmsOf(gyro),
            stabAcc = stabilityOf(accel),
            stabGyro = stabilityOf(gyro),
            maxGapMs = maxGapMsOf(accel, gyro),
            temperatureC = temperatureC,
        )
    }

    private fun maxGapMsOf(
        accel: List<ImuCalSample>,
        gyro: List<ImuCalSample>,
    ): Long {
        var worst = 0L
        for (list in listOf(accel, gyro)) {
            for (i in 1 until list.size) {
                val dt = list[i].t - list[i - 1].t
                if (dt > worst) worst = dt
            }
        }
        return worst / 1_000_000L
    }

    private fun meanOf(samples: List<ImuCalSample>, axis: Int): Double {
        if (samples.isEmpty()) return 0.0
        var total = 0.0
        for (sample in samples) total += valueOf(sample, axis).toDouble()
        return total / samples.size
    }

    /** Isotropic noise: the square root of the mean per-axis variance. */
    private fun rmsOf(samples: List<ImuCalSample>): Double {
        if (samples.size < 2) return 0.0
        var total = 0.0
        for (axis in 0..2) total += varianceOf(samples, axis)
        return kotlin.math.sqrt(total / 3.0)
    }

    /**
     * Bias stability: the standard deviation of block means. This is how much
     * the average wanders, which is a different thing from how noisy one
     * sample is.
     */
    private fun stabilityOf(samples: List<ImuCalSample>): Double {
        if (samples.size < STABILITY_BLOCKS * 2) return 0.0
        val blockSize = samples.size / STABILITY_BLOCKS
        var total = 0.0
        for (axis in 0..2) {
            val means = ArrayList<Double>(STABILITY_BLOCKS)
            for (block in 0 until STABILITY_BLOCKS) {
                var sum = 0.0
                val from = block * blockSize
                val to = if (block == STABILITY_BLOCKS - 1) {
                    samples.size
                } else {
                    from + blockSize
                }
                for (i in from until to) {
                    sum += valueOf(samples[i], axis).toDouble()
                }
                means += sum / (to - from)
            }
            total += sampleVariance(means)
        }
        return kotlin.math.sqrt(total / 3.0)
    }

    private fun varianceOf(samples: List<ImuCalSample>, axis: Int): Double {
        if (samples.size < 2) return 0.0
        val mean = meanOf(samples, axis)
        var total = 0.0
        for (sample in samples) {
            val d = valueOf(sample, axis).toDouble() - mean
            total += d * d
        }
        return total / (samples.size - 1)
    }

    private fun sampleVariance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        var mean = 0.0
        for (value in values) mean += value
        mean /= values.size
        var total = 0.0
        for (value in values) {
            val d = value - mean
            total += d * d
        }
        return total / (values.size - 1)
    }

    private fun valueOf(sample: ImuCalSample, axis: Int): Float = when (axis) {
        0 -> sample.x
        1 -> sample.y
        else -> sample.z
    }

    /**
     * Integrates the gyroscope over the manoeuvre with the trapezoid rule and
     * subtracts the best bias known so far, so the result is the angle the
     * device actually turned through.
     */
    private fun turnOf(
        samples: List<ImuCalSample>,
        axisIndex: Int,
    ): TurnBundle? {
        if (samples.size < 2) return null
        val bias = gyroBiasEstimate()
        var primary = 0.0
        var otherA = 0.0
        var otherB = 0.0
        var peak = 0.0
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            val dtSeconds = (current.t - previous.t) / 1_000_000_000.0
            if (dtSeconds <= 0.0) continue
            for (axis in 0..2) {
                val previousRate = valueOf(previous, axis).toDouble() -
                    bias[axis]
                val currentRate = valueOf(current, axis).toDouble() -
                    bias[axis]
                val step = 0.5 * (previousRate + currentRate) * dtSeconds
                when (axis) {
                    axisIndex -> primary += step
                    else -> if (axis == (axisIndex + 1) % 3) {
                        otherA += step
                    } else {
                        otherB += step
                    }
                }
            }
            val magnitude = kotlin.math.sqrt(
                valueOf(current, 0) * valueOf(current, 0) +
                    valueOf(current, 1) * valueOf(current, 1) +
                    valueOf(current, 2) * valueOf(current, 2),
            ).toDouble()
            if (magnitude > peak) peak = magnitude
        }
        return TurnBundle(
            integratedAngleDeg = primary,
            offAxisAngleDeg = kotlin.math.sqrt(
                otherA * otherA + otherB * otherB,
            ),
            durationMs = (samples[samples.size - 1].t - samples[0].t) /
                1_000_000L,
            sampleCount = samples.size.toLong(),
            peakRateDps = peak,
        )
    }

    private fun gyroBiasEstimate(): DoubleArray {
        var weight = 0L
        val total = DoubleArray(3)
        for (row in statics) {
            val w = row.gyroSampleCount
            if (w <= 0L) continue
            total[0] += row.meanGyroXDps * w
            total[1] += row.meanGyroYDps * w
            total[2] += row.meanGyroZDps * w
            weight += w
        }
        if (weight <= 0L) return DoubleArray(3) { 0.0 }
        return DoubleArray(3) { i -> total[i] / weight.toDouble() }
    }

    /**
     * Battery temperature in degrees Celsius. It is a proxy for how warm the
     * device is, not the temperature of the sensor die, and it moves slowly.
     */
    private fun temperatureC(): Double {
        val intent = registerReceiver(
            null,
            android.content.IntentFilter(
                android.content.Intent.ACTION_BATTERY_CHANGED,
            ),
        )
        val tenths = intent?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE,
        ) ?: Int.MIN_VALUE
        return if (tenths == Int.MIN_VALUE) {
            FALLBACK_TEMPERATURE_C
        } else {
            tenths / 10.0
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun resetAll() {
        streams.clear()
        statics.clear()
        poses.clear()
        turns.clear()
        lastCalibration = null
        reportText.text = ""
        refreshList()
        statusText.text = "Zurückgesetzt."
    }

    private fun refreshList() {
        val out = StringBuilder()
        if (streams.isEmpty() && statics.isEmpty() && poses.isEmpty() &&
            turns.isEmpty()
        ) {
            out.append("noch nichts aufgenommen")
        }
        for (row in streams) {
            out.append("strom ${row.label}: " +
                String.format(Locale.US, "%.1f", row.measuredHz) +
                " Hz (soll " + row.nominalHz + "), Lücke " +
                row.maxDtUs / 1000 + " ms, " + row.domain.wireName() + "\n")
        }
        for (row in statics) {
            out.append("statik ${row.label}: " + row.durationMs / 1000 +
                " s, " + row.sampleCount + " Werte, " +
                f(row.temperatureC, 1) + " °C\n")
        }
        for (row in poses) {
            out.append("lage ${row.label}: " + row.sampleCount + " Werte\n")
        }
        for (row in turns) {
            out.append("drehung ${row.label}: integriert " +
                f(row.integratedAngleDeg, 2) + "°, Nebenachsen " +
                f(row.offAxisAngleDeg, 2) + "°, Spitze " +
                f(row.peakRateDps, 0) + " °/s\n")
        }
        listText.text = out.toString()
    }

    private fun busy(running: Boolean, message: String) {
        setCaptureButtons(!running)
        if (running) statusText.text = message
    }

    private fun setCaptureButtons(enabled: Boolean) {
        cadenceButton.isEnabled = enabled
        staticButton.isEnabled = enabled
        poseButton.isEnabled = enabled
        turnButton.isEnabled = enabled
        evaluateButton.isEnabled = enabled
    }

    private fun staticDurationMs(): Long = when (
        durationSpinner.selectedItemPosition
    ) {
        0 -> 10_000L
        1 -> 30_000L
        else -> 60_000L
    }

    private fun directionFromSpinner(): ArKernelImuPoseDirection =
        when (directionSpinner.selectedItemPosition) {
            0 -> ArKernelImuPoseDirection.POSITIVE_X
            1 -> ArKernelImuPoseDirection.NEGATIVE_X
            2 -> ArKernelImuPoseDirection.POSITIVE_Y
            3 -> ArKernelImuPoseDirection.NEGATIVE_Y
            4 -> ArKernelImuPoseDirection.POSITIVE_Z
            else -> ArKernelImuPoseDirection.NEGATIVE_Z
        }

    private fun axisFromSpinner(): ArKernelImuTurnAxis =
        axisFromIndex(axisSpinner.selectedItemPosition)

    private fun axisFromIndex(index: Int): ArKernelImuTurnAxis = when (index) {
        0 -> ArKernelImuTurnAxis.X
        1 -> ArKernelImuTurnAxis.Y
        else -> ArKernelImuTurnAxis.Z
    }

    private fun poseLabel(direction: ArKernelImuPoseDirection): String =
        direction.wireName() + "-" + (poses.size + 1)

    private fun doubleOf(input: EditText, fallback: Double): Double =
        input.text.toString().trim().toDoubleOrNull() ?: fallback

    private fun longOf(input: EditText, fallback: Long): Long =
        input.text.toString().trim().toLongOrNull() ?: fallback

    private fun intOf(input: EditText, fallback: Int): Int =
        input.text.toString().trim().toIntOrNull() ?: fallback

    private fun currentText(): String {
        val calibration = lastCalibration
            ?: return "Noch nichts ausgewertet."
        return ArKernelImuCalibrationAdapter.encode(calibration)
    }

    private fun currentFileName(): String {
        val device = deviceInput.text.toString().trim()
            .ifEmpty { "device" }
        val session = sessionInput.text.toString().trim()
            .ifEmpty { "session" }
        return "arkernel-imucal-$device-$session.txt"
    }

    private fun saveFile() {
        if (lastCalibration == null) {
            statusText.text = "Erst auswerten."
            return
        }
        val name = currentFileName()
        val path = writeTextToDownloads(name, currentText())
        statusText.text = if (path.startsWith("Downloads")) {
            "Gespeichert: $path"
        } else {
            path
        }
    }

    private fun shareFile() {
        if (lastCalibration == null) {
            statusText.text = "Erst auswerten."
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, currentFileName())
            putExtra(Intent.EXTRA_TEXT, currentText())
        }
        startActivity(Intent.createChooser(intent, "ARKIMUCAL1 teilen"))
    }

    private fun copyFile() {
        if (lastCalibration == null) {
            statusText.text = "Erst auswerten."
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        if (manager == null) {
            statusText.text = "Zwischenablage nicht verfügbar."
            return
        }
        manager.setPrimaryClip(
            android.content.ClipData.newPlainText(
                currentFileName(), currentText(),
            ),
        )
        Toast.makeText(this, "Kopiert.", Toast.LENGTH_SHORT).show()
    }

    private fun labelled(
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
    ): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(9))
        isSingleLine = true
        setText(initial)
        textSize = 13f
        contentDescription = description
    }

    private fun decimalEdit(
        initial: String,
        description: String,
    ): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED
        filters = arrayOf(InputFilter.LengthFilter(12))
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
            this@ImuCalibrationActivity,
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SESSION, sessionInput.text.toString())
        outState.putString(STATE_DEVICE, deviceInput.text.toString())
        outState.putString(STATE_GRAVITY, gravityInput.text.toString())
        outState.putString(STATE_REPORT, reportText.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getString(STATE_SESSION)?.let {
            sessionInput.setText(it)
        }
        savedInstanceState.getString(STATE_DEVICE)?.let {
            deviceInput.setText(it)
        }
        savedInstanceState.getString(STATE_GRAVITY)?.let {
            gravityInput.setText(it)
        }
        reportText.text = savedInstanceState.getString(STATE_REPORT) ?: ""
        statusText.text = savedInstanceState.getString(STATE_STATUS) ?: ""
    }

    override fun onDestroy() {
        if (turnRunning) {
            val manager = getSystemService(Context.SENSOR_SERVICE)
                as? SensorManager
            turnListener?.let { manager?.unregisterListener(it) }
            turnHandler?.removeCallbacksAndMessages(null)
            turnThread?.quitSafely()
            turnThread = null
            turnHandler = null
            turnListener = null
            turnRunning = false
        }
        super.onDestroy()
    }
}
