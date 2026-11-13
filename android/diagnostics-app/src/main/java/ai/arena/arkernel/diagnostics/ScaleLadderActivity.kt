package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelRulerTickDetector
import ai.arena.arkernel.ArKernelScaleLadder
import ai.arena.arkernel.ArKernelScaleLadderAdapter
import ai.arena.arkernel.ArKernelScaleLadderRung
import ai.arena.arkernel.LADDER_MAX_DISTANCE_MM
import ai.arena.arkernel.LADDER_MAX_RUNGS
import ai.arena.arkernel.LADDER_MAX_SCALE_MILLI
import ai.arena.arkernel.LADDER_MIN_DISTANCE_MM
import ai.arena.arkernel.LADDER_MIN_RUNGS
import ai.arena.arkernel.LADDER_SPREAD_LIMIT_PERMILLE
import ai.arena.arkernel.LADDER_TREND_LIMIT_PERMILLE_PER_M
import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The metric scale ladder: does one scale factor survive a change of distance?
 *
 * The user holds a folding ruler at a series of distances, marks its two ends
 * on the preview at each one, and the app derives how many image pixels one
 * metre spans there. The ruler's centimetre ticks are the ground truth - a
 * real standard - so no separate reference object is needed. Only the
 * distance comes from the operator's tape measure.
 *
 * A pinhole camera maps one metre at distance d onto f/d pixels, so
 * pixels-per-metre times distance is the focal length in pixels, and that
 * product should be the **same number at every rung**. The screen shows it
 * per rung as the run proceeds, so a ladder that is drifting is visible while
 * it happens rather than after the fit.
 *
 * **Why the visible ruler length is asked for.** It is not the source of the
 * scale - the tick spacing is - but a cross-check on the tick *count*. If the
 * detector locks onto every other centimetre mark, the spacing doubles and
 * the whole measurement comes out silently half as large; the spacing is just
 * as uniform either way, so regularity cannot catch it. Comparing the detected
 * count against the stated length can, and turns a silent factor-of-two error
 * into a visible rejection.
 *
 * What this does not give: the implied focal length is not a measured
 * intrinsic. The ruler foreshortens when it is not fronto-parallel, distortion
 * grows off-axis, and the tape measures to wherever the operator thinks the
 * ruler plane is. Those biases act alike at every distance, so the **trend**
 * across rungs is meaningful even where the absolute value is not.
 *
 * No frame is retained: each frame is reduced in memory to a one-dimensional
 * luminance profile along the marked line and dropped immediately.
 */
class ScaleLadderActivity : DiagnosticsActivity() {

    private companion object {
        const val REQUEST_CAMERA = 1025
        const val STATE_OPT_IN = "ladder.opt_in"
        const val STATE_DISTANCE = "ladder.distance"
        const val STATE_VISIBLE = "ladder.visible"
        const val STATE_STATUS = "ladder.status"
        const val STATE_RECORD = "ladder.record"

        const val READER_WIDTH = 1_280
        const val READER_HEIGHT = 720

        /** Profile samples along the marked line; matches the detector. */
        const val SAMPLE_COUNT = 512

        /** One centimetre, in metres. The ruler's tick spacing. */
        const val TICK_METRES = 0.01

        /**
         * How far the detected tick count may sit from the stated length.
         * Generous on purpose: at five metres the ticks are only a few pixels
         * apart and some are missed, which is noise, not a wrong scale.
         */
        const val TICK_TOLERANCE_FRACTION = 0.25
        const val TICK_TOLERANCE_MINIMUM = 3.0
    }

    private lateinit var optInBox: CheckBox
    private lateinit var distanceInput: EditText
    private lateinit var visibleInput: EditText
    private lateinit var startButton: Button
    private lateinit var detectButton: Button
    private lateinit var addButton: Button
    private lateinit var evaluateButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button
    private lateinit var rungText: TextView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var textureView: TextureView
    private lateinit var overlay: LadderOverlayView

    private val runId = "ladder." + System.currentTimeMillis()
    private val deviceToken = opaqueDeviceToken()
    private val rungs = ArrayList<ArKernelScaleLadderRung>()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var latestLuma: ByteArray? = null
    private var lumaWidth = 0
    private var lumaHeight = 0
    private var lumaRowStride = 0
    private var lumaPixelStride = 1
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var sensorDegrees = 0
    private val previewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var matrixReady = false

    private var detectedTickIndices: List<Int> = emptyList()
    private var detectedSpacingSamples = 0.0
    private var detectedRegularity = 0.0
    private var pendingScaleMilliPxPerM: Long = 0L

    private var currentRecord = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            setText("Maßstabsleiter über die Entfernung")
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            setText("Beantwortet die Frage, ob ein einziger Skalierungsfaktor " +
                "im Kern überhaupt reicht. Wenn der Maßstabsfehler mit der " +
                "Entfernung wächst, tut er das nicht.\n\n" +
                "So wird gemessen: Ein Zollstock wird bei mehreren " +
                "Abständen ins Bild gehalten. Die App erkennt seine " +
                "Zentimeter-Marken und bestimmt, wie viele Bildpunkte ein " +
                "Meter bei diesem Abstand einnimmt. Bei einer Lochkamera " +
                "gilt: Bildpunkte pro Meter mal Abstand ist die " +
                "Brennweite in Bildpunkten — und die muss bei jeder " +
                "Sprosse dieselbe Zahl sein.")
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            setText("Ablauf\n" +
                "1. Zollstock so halten, dass er das Bild gut füllt und " +
                "möglichst parallel zum Bildrand liegt.\n" +
                "2. Abstand zum Zollstock mit dem Maßband messen und " +
                "eintragen.\n" +
                "3. Die beiden Enden des sichtbaren Stücks antippen.\n" +
                "4. „Marken erkennen“ und dann „Sprosse aufnehmen“.\n" +
                "5. Mit mehreren Abständen wiederholen, mindestens dreimal.")
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            setText("Nah hält man ein kurzes Stück Zollstock ins Bild, fern " +
                "ein langes: bei 0,5 m füllen 20 cm das Bild, bei 5 m " +
                "braucht es dafür 2 m. Die Zentimeter-Marken sind in " +
                "beiden Fällen gleich weit voneinander entfernt, deshalb " +
                "bleibt die Wahrheit dieselbe.")
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            setText("Es wird kein Bild gespeichert. Jedes Bild wird im " +
                "Speicher auf eine Helligkeitslinie reduziert und sofort " +
                "verworfen.")
            setPadding(0, 0, 0, 16)
        })

        optInBox = CheckBox(this).apply {
            setText("Ich habe die Hinweise gelesen und stimme der " +
                "Messung zu.")
        }
        root.addView(optInBox)

        distanceInput = EditText(this).apply {
            hint = "Abstand in mm (z. B. 1000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1000")
        }
        root.addView(labelled("Abstand zum Zollstock (Maßband, mm)",
            distanceInput))

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (distance in listOf("500", "1000", "2000", "5000")) {
            quickRow.addView(Button(this).apply {
                setText("${distance.toLong() / 10} cm")
                setOnClickListener { distanceInput.setText(distance) }
            })
        }
        root.addView(quickRow)

        visibleInput = EditText(this).apply {
            hint = "Sichtbares Stück in cm (z. B. 20)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("20")
        }
        root.addView(labelled(
            "Sichtbares Zollstockstück (cm) – nur als Gegenprobe",
            visibleInput))

        root.addView(TextView(this).apply {
            setText("Der Maßstab kommt aus dem Abstand der Marken, nicht " +
                "aus dieser Zahl. Sie dient nur dazu zu prüfen, ob die " +
                "Erkennung jede Zentimeter-Marke gefunden hat — sonst " +
                "wäre das Ergebnis stillschweigend um ein Vielfaches " +
                "falsch.")
            textSize = 12f
            setPadding(0, 0, 0, 16)
        })

        startButton = Button(this).apply {
            setText("Vorschau starten")
            setOnClickListener { openCamera() }
        }
        root.addView(startButton)

        val previewFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 640)
        }
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            surfaceTextureListener = previewListener()
        }
        overlay = LadderOverlayView(this)
        previewFrame.addView(textureView)
        previewFrame.addView(overlay)
        root.addView(previewFrame)

        detectButton = Button(this).apply {
            setText("Marken erkennen")
            setOnClickListener { detectTicks() }
        }
        root.addView(detectButton)

        addButton = Button(this).apply {
            setText("Sprosse aufnehmen")
            setOnClickListener { addRung() }
        }
        root.addView(addButton)

        rungText = TextView(this).apply {
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, 12, 0, 4)
        }
        root.addView(rungText)

        evaluateButton = Button(this).apply {
            setText("Leiter auswerten")
            setOnClickListener { evaluate() }
        }
        root.addView(evaluateButton)

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
            setText("ARKLADDER1 teilen")
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
        title = "Maßstabsleiter"

        savedInstanceState?.let {
            optInBox.isChecked = it.getBoolean(STATE_OPT_IN, false)
            distanceInput.setText(it.getString(STATE_DISTANCE, "1000") ?: "1000")
            visibleInput.setText(it.getString(STATE_VISIBLE, "20") ?: "20")
            statusText.setText(it.getString(STATE_STATUS, "") ?: "")
            currentRecord = it.getString(STATE_RECORD, "") ?: ""
            if (currentRecord.isNotEmpty()) {
                shareButton.isEnabled = true
                copyButton.isEnabled = true
            }
        }
        refreshRungs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPT_IN, optInBox.isChecked)
        outState.putString(STATE_DISTANCE, distanceInput.text.toString())
        outState.putString(STATE_VISIBLE, visibleInput.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
        outState.putString(STATE_RECORD, currentRecord)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
    }

    private fun labelled(label: String, view: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ScaleLadderActivity).apply {
                setText(label)
                textSize = 12f
            })
            addView(view)
        }

    // --------------------------------------------------------- rung ledger

    private fun refreshRungs() {
        if (rungs.isEmpty()) {
            rungText.setText("Noch keine Sprosse.")
            return
        }
        val builder = StringBuilder()
        builder.append("Abstand      px/m        Brennweite\n")
        for (rung in rungs) {
            builder.append(
                String.format(Locale.US, "%6d mm  %9.1f  %9.1f px%n",
                    rung.distanceMm,
                    rung.scaleMilliPxPerM / 1_000.0,
                    rung.impliedFocalPx))
        }
        rungText.setText(builder.toString())
    }

    private fun detectTicks() {
        if (!optInBox.isChecked) {
            statusText.setText("Bitte zuerst das Opt-in anhaken.")
            return
        }
        if (overlay.markerCount() != 2) {
            statusText.setText("Bitte die beiden Enden des Zollstocks " +
                "antippen. Aktuell: ${overlay.markerCount()}/2.")
            return
        }
        val luma = latestLuma
        if (luma == null) {
            statusText.setText("Noch kein Bild im Speicher – bitte kurz " +
                "warten, bis die Vorschau läuft.")
            return
        }
        val start = overlay.markerAt(0)
        val end = overlay.markerAt(1)
        val point = FloatArray(2)
        val profile = IntArray(SAMPLE_COUNT)
        for (index in 0 until SAMPLE_COUNT) {
            val ratio = index.toFloat() / (SAMPLE_COUNT - 1).toFloat()
            val x = start[0] + (end[0] - start[0]) * ratio
            val y = start[1] + (end[1] - start[1]) * ratio
            toBuffer(x, y, point)
            profile[index] = lumaAt(point[0].toInt(), point[1].toInt())
        }
        val detection = try {
            ArKernelRulerTickDetector.detect(profile)
        } catch (error: IllegalArgumentException) {
            statusText.setText("Erkennung abgelehnt: ${error.message}")
            return
        }
        if (!detection.detected) {
            detectedTickIndices = emptyList()
            pendingScaleMilliPxPerM = 0L
            statusText.setText("Keine gleichmäßigen Marken erkannt " +
                "(gefunden: ${detection.indices.size}). Bitte Zollstock " +
                "näher, flacher und heller ins Bild halten.")
            overlay.setTicks(emptyList(), start, end)
            return
        }
        detectedTickIndices = detection.indices
        detectedSpacingSamples = detection.spacing
        detectedRegularity = detection.regularity
        overlay.setTicks(detection.indices, start, end)

        // Samples are along the marked screen line, so the spacing has to be
        // carried back into screen pixels before it means anything.
        val lineLengthPx = hypot(
            (end[0] - start[0]).toDouble(),
            (end[1] - start[1]).toDouble())
        val spacingPx = detection.spacing * lineLengthPx /
            (SAMPLE_COUNT - 1).toDouble()
        val scalePxPerM = spacingPx / TICK_METRES
        pendingScaleMilliPxPerM = (scalePxPerM * 1_000.0).toLong()

        val visibleCm = visibleInput.text.toString().trim().toDoubleOrNull()
        val expectation = if (visibleCm == null) {
            "keine Gegenprobe (Länge eintragen)"
        } else {
            "erwartet etwa ${visibleCm.toLong()} Marken"
        }
        statusText.setText("Erkannt: ${detection.indices.size} Marken " +
            "($expectation)\nGleichmäßigkeit: " +
            "${(detection.regularity * 1_000.0).toLong() / 10.0} %\n" +
            "Maßstab: ${scalePxPerM.toLong()} px/m")
        announce("${detection.indices.size} Marken erkannt")
    }

    private fun addRung() {
        if (!optInBox.isChecked) {
            statusText.setText("Bitte zuerst das Opt-in anhaken.")
            return
        }
        if (detectedTickIndices.isEmpty() || pendingScaleMilliPxPerM <= 0L) {
            statusText.setText("Bitte zuerst „Marken erkennen“ ausführen.")
            return
        }
        val distanceMm = distanceInput.text.toString().trim().toLongOrNull()
        if (distanceMm == null ||
            distanceMm < LADDER_MIN_DISTANCE_MM ||
            distanceMm > LADDER_MAX_DISTANCE_MM
        ) {
            statusText.setText("Bitte einen Abstand zwischen " +
                "${LADDER_MIN_DISTANCE_MM} und " +
                "${LADDER_MAX_DISTANCE_MM} mm eintragen.")
            return
        }
        val visibleCm = visibleInput.text.toString().trim().toDoubleOrNull()
        if (visibleCm == null || visibleCm < 1.0 || visibleCm > 500.0) {
            statusText.setText("Bitte das sichtbare Zollstockstück in " +
                "Zentimetern eintragen (1–500).")
            return
        }
        if (rungs.size >= LADDER_MAX_RUNGS) {
            statusText.setText("Die Leiter ist voll " +
                "($LADDER_MAX_RUNGS Sprossen).")
            return
        }
        if (rungs.isNotEmpty() &&
            distanceMm <= rungs[rungs.size - 1].distanceMm
        ) {
            statusText.setText("Die Abstände müssen von Sprosse zu Sprosse " +
                "größer werden, sonst gibt die Leiter keine Richtung her. " +
                "Zuletzt: ${rungs[rungs.size - 1].distanceMm} mm.")
            return
        }
        // The cross-check: a detector that locked onto every other tick would
        // double the spacing and halve the scale, and the spacing would look
        // just as regular either way.
        val expected = visibleCm
        val tolerance = max(TICK_TOLERANCE_MINIMUM,
            expected * TICK_TOLERANCE_FRACTION)
        if (abs(detectedTickIndices.size - expected) > tolerance) {
            statusText.setText("Gegenprobe fehlgeschlagen: ${detectedTickIndices.size} " +
                "Marken erkannt, aber bei $expected cm Zollstock " +
                "wären etwa ${expected.toLong()} zu erwarten.\n" +
                "Wahrscheinlich hat die Erkennung Marken übersprungen " +
                "oder doppelt gezählt. Bitte das markierte Stück " +
                "nachmessen und neu erkennen.")
            return
        }
        if (pendingScaleMilliPxPerM > LADDER_MAX_SCALE_MILLI) {
            statusText.setText("Der abgeleitete Maßstab ist zu groß – " +
                "bitte die Marken prüfen.")
            return
        }
        rungs.add(
            ArKernelScaleLadderRung(
                index = rungs.size,
                distanceMm = distanceMm,
                scaleMilliPxPerM = pendingScaleMilliPxPerM,
                tickCount = detectedTickIndices.size,
                regularityPermille =
                    (detectedRegularity * 1_000.0).toLong()
                        .coerceIn(0L, 1_000L).toInt(),
            ))
        detectedTickIndices = emptyList()
        pendingScaleMilliPxPerM = 0L
        overlay.setTicks(emptyList(), null, null)
        overlay.clearMarkers()
        refreshRungs()
        statusText.setText("Sprosse ${rungs.size} aufgenommen. " +
            "Nächsten Abstand messen, Zollstock neu antippen, " +
            "„Marken erkennen“ drücken.")
        Toast.makeText(this, "Sprosse ${rungs.size} aufgenommen",
            Toast.LENGTH_SHORT).show()
    }

    private fun evaluate() {
        if (rungs.size < LADDER_MIN_RUNGS) {
            statusText.setText("Mindestens $LADDER_MIN_RUNGS Sprossen, " +
                "sonst lässt sich ein Trend nicht von Zufall unterscheiden. " +
                "Aktuell: ${rungs.size}.")
            return
        }
        val record = ArKernelScaleLadder(
            runId = runId,
            deviceToken = deviceToken,
            campaignId = runId,
            rungs = ArrayList(rungs),
        )
        val text = ArKernelScaleLadderAdapter.encode(record)
        val parsed = ArKernelScaleLadderAdapter.parse(text)
        if (parsed.record == null) {
            statusText.setText("Datensatz abgelehnt: ${parsed.rejection} " +
                "(${parsed.detail})")
            return
        }
        currentRecord = text
        resultText.setText(describe(record))
        shareButton.isEnabled = true
        copyButton.isEnabled = true
        Thread {
            val written = writeTextToDownloads(
                "ladder-$runId.txt", text)
            runOnUiThread {
                statusText.setText("Datei: $written")
                Toast.makeText(this@ScaleLadderActivity,
                    "ARKLADDER1 erzeugt. Bitte Text hier im Chat hochladen.",
                    Toast.LENGTH_LONG).show()
                announce("Maßstabsleiter gespeichert")
            }
        }.start()
    }

    private fun describe(record: ArKernelScaleLadder): String {
        val builder = StringBuilder()
        builder.append("Ergebnis\n")
        builder.append("Sprossen: ${record.rungCount}\n")
        builder.append("Abgeleitete Brennweite: ")
            .append(String.format(Locale.US, "%.0f", record.focalPxMean))
            .append(" px")
        builder.append(" (")
            .append(String.format(Locale.US, "%.0f", record.focalPxMin))
            .append(" – ")
            .append(String.format(Locale.US, "%.0f", record.focalPxMax))
            .append(" px)\n")
        builder.append("Streuung: ")
            .append(record.spreadPermille / 10.0)
            .append(" % (zulässig ")
            .append(LADDER_SPREAD_LIMIT_PERMILLE / 10.0)
            .append(" %)\n")
        builder.append("Trend: ")
            .append(record.trendPermillePerM / 10.0)
            .append(" % pro Meter (zulässig ")
            .append(LADDER_TREND_LIMIT_PERMILLE_PER_M / 10.0)
            .append(" %)\n")
        builder.append("Güte: ").append(
            ArKernelScaleLadderAdapter.report(record).qualityGrade)
            .append("\n\n")
        if (record.scaleSufficient) {
            builder.append("Der Maßstab verhält sich über die gemessenen " +
                "Abstände hinweg gleich. Ein einziger Skalierungsfaktor " +
                "kann ihn also korrigieren.\n")
        } else {
            val steps = record.outstandingSteps().map { stepName(it) }
            builder.append("Noch nicht ausreichend: ")
                .append(steps.joinToString(", "))
                .append("\n")
            if (record.outstandingSteps().contains("trend")) {
                builder.append("Die abgeleitete Brennweite ändert sich mit " +
                    "dem Abstand. Dann wächst der Maßstabsfehler mit der " +
                    "Entfernung, und ein einziger Faktor reicht nicht.\n")
            }
        }
        builder.append("\nDer absolute Wert der Brennweite ist keine " +
            "gemessene Intrinsic. Der Zollstock steht selten exakt " +
            "parallel, die Verzeichnung wächst zum Bildrand, und das " +
            "Maßband misst bis zu der Ebene, die du dafür hältst. Diese " +
            "Einflüsse wirken bei jedem Abstand ähnlich, deshalb ist der " +
            "Trend über die Sprossen verlässlich und der absolute Wert " +
            "nicht.")
        return builder.toString()
    }

    private fun stepName(token: String): String = when (token) {
        "rungs" -> "mindestens $LADDER_MIN_RUNGS Sprossen"
        "trend" -> "Brennweite ändert sich mit dem Abstand"
        "spread" -> "Sprossen streuen zu stark"
        else -> token
    }

    // -------------------------------------------------------------- camera

    private fun previewListener(): TextureView.SurfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture, width: Int, height: Int) =
                applyPreviewTransform()
            override fun onSurfaceTextureDestroyed(
                surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(
                surface: SurfaceTexture) = Unit
        }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA)
            return
        }
        val manager = getSystemService(CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            statusText.setText("Kein Kamera-Dienst verfügbar.")
            return
        }
        val cameraId = backCameraId(manager)
        if (cameraId == null) {
            statusText.setText("Keine Rückkamera gefunden.")
            return
        }
        sensorDegrees = try {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (error: Exception) {
            0
        }
        val previewSize = previewSizeOf(manager, cameraId)
        val thread = HandlerThread("arkladder-camera").apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler
        val surfaceTexture = textureView.surfaceTexture ?: run {
            statusText.setText("Vorschau noch nicht bereit.")
            return
        }
        try {
            surfaceTexture.setDefaultBufferSize(
                previewSize.width, previewSize.height)
            val surface = Surface(surfaceTexture)
            previewSurface = surface
            val imageReader = ImageReader.newInstance(
                READER_WIDTH, READER_HEIGHT,
                android.graphics.ImageFormat.YUV_420_888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ readerValue ->
                val image = try {
                    readerValue.acquireLatestImage()
                } catch (error: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
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
                    latestLuma = compact
                    lumaWidth = width
                    lumaHeight = height
                    lumaRowStride = width
                    lumaPixelStride = 1
                } catch (error: Exception) {
                    // Keep the previous frame; nothing is persisted.
                } finally {
                    image.close()
                }
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
                                        session: CameraCaptureSession) {
                                        captureSession = session
                                        bufferWidth = previewSize.width
                                        bufferHeight = previewSize.height
                                        applyPreviewTransform()
                                        try {
                                            session.setRepeatingRequest(
                                                builder.build(), null, handler)
                                            runOnUiThread {
                                                statusText.setText("Vorschau läuft. " +
                                                    "Zollstock ins Bild " +
                                                    "halten und die beiden " +
                                                    "Enden antippen.")
                                            }
                                        } catch (error: Exception) {
                                            fail("Vorschau-Anfrage " +
                                                "fehlgeschlagen: " +
                                                error.message)
                                        }
                                    }

                                    override fun onConfigureFailed(
                                        session: CameraCaptureSession) {
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

                    override fun onError(camera: CameraDevice, error: Int) {
                        fail("Kamera-Fehler $error.")
                    }
                }, handler)
        } catch (error: SecurityException) {
            fail("Kamera-Erlaubnis fehlt.")
        } catch (error: Exception) {
            fail("Kamera konnte nicht geöffnet werden: ${error.message}")
        }
    }

    private fun fail(message: String) {
        runOnUiThread { statusText.setText(message) }
    }

    private fun previewSizeOf(manager: CameraManager, cameraId: String): Size =
        try {
            val map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            sizes?.filter {
                it.width <= 1_920 && it.height <= 1_080
            }?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: Size(1_280, 720)
        } catch (error: Exception) {
            Size(1_280, 720)
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

    private fun applyPreviewTransform() {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0 ||
            bufferWidth <= 0 || bufferHeight <= 0) return
        val matrix = Matrix()
        val buffer = RectF(
            -bufferWidth / 2f, -bufferHeight / 2f,
            bufferWidth / 2f, bufferHeight / 2f)
        val rotated = RectF()
        var degrees = (sensorDegrees - displayRotationDegrees()) % 360
        if (degrees < 0) degrees += 360
        matrix.setRotate(degrees.toFloat())
        matrix.mapRect(rotated, buffer)
        if (rotated.width() <= 0f || rotated.height() <= 0f) return
        val scale = maxOf(
            viewWidth / rotated.width(),
            viewHeight / rotated.height())
        matrix.postScale(scale, scale)
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)
        previewMatrix.set(matrix)
        matrixReady = matrix.invert(inverseMatrix)
        textureView.setTransform(previewMatrix)
    }

    private fun displayRotationDegrees(): Int = when (
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    ) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun lumaAt(x: Int, y: Int): Int {
        val luma = latestLuma ?: return 0
        val width = lumaWidth
        val height = lumaHeight
        if (width <= 0 || height <= 0) return 0
        val clampedX = x.coerceIn(0, width - 1)
        val clampedY = y.coerceIn(0, height - 1)
        val offset = clampedY * lumaRowStride + clampedX * lumaPixelStride
        return if (offset < 0 || offset >= luma.size) {
            0
        } else {
            luma[offset].toInt() and 0xFF
        }
    }

    /** Maps an on-screen point into camera-buffer pixel coordinates. */
    private fun toBuffer(x: Float, y: Float, out: FloatArray) {
        out[0] = x
        out[1] = y
        if (!matrixReady) {
            out[0] = x / textureView.width.toFloat().coerceAtLeast(1f) *
                lumaWidth
            out[1] = y / textureView.height.toFloat().coerceAtLeast(1f) *
                lumaHeight
            return
        }
        inverseMatrix.mapPoints(out)
        out[0] += bufferWidth / 2f
        out[1] += bufferHeight / 2f
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

    // -------------------------------------------------------------- output

    private fun announce(text: String) {
        statusText.announceForAccessibility(text)
    }

    /**
     * A one-way digest of the device's build fields, so runs from the same
     * handset group without recording which handset it is.
     */
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

    private fun shareRecord() {
        if (currentRecord.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentRecord)
        }
        startActivity(Intent.createChooser(intent, "ARKLADDER1 teilen"))
    }

    private fun copyRecord() {
        if (currentRecord.isEmpty()) return
        val manager = getSystemService(CLIPBOARD_SERVICE) as?
            android.content.ClipboardManager ?: return
        manager.setPrimaryClip(android.content.ClipData.newPlainText(
            "ARKLADDER1", currentRecord))
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
            openCamera()
        }
    }

    // ------------------------------------------------------------- overlay

    /** Two markers for the ruler's ends, and the ticks found between them. */
    private inner class LadderOverlayView(
        context: android.content.Context,
    ) : View(context) {

        private val points = FloatArray(4)
        private var count = 0
        private var ticks: List<Int> = emptyList()
        private var tickStart: FloatArray? = null
        private var tickEnd: FloatArray? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var viewWidth = 0f
        private var viewHeight = 0f

        init {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription =
                "Messmarken: zwei Tipps auf die Enden des Zollstocks"
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    addPoint(event.x, event.y)
                    true
                } else {
                    false
                }
            }
        }

        private fun addPoint(x: Float, y: Float) {
            if (count >= 2) {
                count = 0
            }
            points[count * 2] = x
            points[count * 2 + 1] = y
            count++
            detectedTickIndices = emptyList()
            pendingScaleMilliPxPerM = 0L
            invalidate()
        }

        fun markerCount(): Int = count

        fun markerAt(index: Int): FloatArray =
            floatArrayOf(points[index * 2], points[index * 2 + 1])

        fun clearMarkers() {
            count = 0
            ticks = emptyList()
            tickStart = null
            tickEnd = null
            invalidate()
        }

        fun setTicks(
            next: List<Int>,
            start: FloatArray?,
            end: FloatArray?,
        ) {
            ticks = next
            tickStart = start
            tickEnd = end
            invalidate()
        }

        override fun onSizeChanged(
            w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            viewWidth = w.toFloat()
            viewHeight = h.toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(120, 255, 255, 255)
            canvas.drawLine(viewWidth / 2f, 0f, viewWidth / 2f,
                viewHeight, paint)
            canvas.drawLine(0f, viewHeight / 2f, viewWidth,
                viewHeight / 2f, paint)
            if (count >= 2) {
                paint.color = Color.rgb(234, 179, 8)
                paint.strokeWidth = 3f
                canvas.drawLine(points[0], points[1], points[2],
                    points[3], paint)
            }
            val start = tickStart
            val end = tickEnd
            if (start != null && end != null && ticks.isNotEmpty()) {
                paint.color = Color.rgb(34, 197, 94)
                paint.strokeWidth = 2f
                val last = SAMPLE_COUNT - 1
                for (tick in ticks) {
                    val ratio = tick.toFloat() / last.toFloat()
                    val x = start[0] + (end[0] - start[0]) * ratio
                    val y = start[1] + (end[1] - start[1]) * ratio
                    canvas.drawCircle(x, y, 5f, paint)
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(239, 68, 68)
            for (index in 0 until count) {
                canvas.drawCircle(points[index * 2],
                    points[index * 2 + 1], 10f, paint)
            }
        }
    }
}
