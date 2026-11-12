package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelCampaignSceneKind
import ai.arena.arkernel.ArKernelMeasurementCondition
import ai.arena.arkernel.ArKernelMeasurementFeedMarker
import ai.arena.arkernel.ArKernelRulerScaleAdapter
import ai.arena.arkernel.ArKernelRulerTickDetector
import ai.arena.arkernel.ArKernelRulerTickRow
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in folding-ruler recognition: the app finds the ruler's tick marks
 * itself instead of making the user tap them precisely.
 *
 * The user folds the ruler out to a known length (for example 1 m or 2 m),
 * points the camera at it, taps roughly the two ends of the ruler and the two
 * ends of the target, and lets the app detect the centimetre ticks. Luma is
 * sampled along the reference line **from a preview frame held in memory**;
 * the frame is discarded immediately and is never written, exported or shared.
 *
 * The detected tick count is compared with the number of centimetre ticks
 * implied by the stated length, so a wrong ruler or a wrong statement shows up
 * as `tick_count_consistent=false`. Detection improves **repeatability of
 * endpoint placement**, not metric accuracy: depth, parallax, perspective,
 * rolling shutter and lens distortion are not modelled, so
 * `metric_accuracy_claimed` is always false and `estimated_target_m` stays a
 * screen-ratio estimate that assumes ruler and target lie in one
 * fronto-parallel plane at equal depth.
 *
 * No AI model, inference, weights or network access is involved. The grade
 * stays `unverified`.
 */
class RulerScaleActivity : android.app.Activity() {

    private companion object {
        const val REQUEST_CAMERA_RULER = 1003
        const val STATE_OPT_IN = "ruler.opt_in"
        const val STATE_FOLDED = "ruler.folded"
        const val STATE_KIND = "ruler.kind"
        const val STATE_CONDITION = "ruler.condition"
        const val STATE_HELD_OUT = "ruler.held_out"
        const val STATE_CONFIRM = "ruler.confirm"
        const val STATE_DETECT = "ruler.detect"
        const val STATE_MARKERS = "ruler.markers"
        const val STATE_MARKER_COUNT = "ruler.marker_count"
        const val STATE_FLIP = "ruler.flip"
        const val STATE_CAPTURE_INDEX = "ruler.capture_index"
        const val STATE_RECORD = "ruler.record"
        const val STATE_STATUS = "ruler.status"
        const val SAMPLE_COUNT = 512
        const val READER_WIDTH = 1_280
        const val READER_HEIGHT = 720
    }

    private lateinit var optInBox: CheckBox
    private lateinit var foldedInput: EditText
    private lateinit var kindSpinner: Spinner
    private lateinit var conditionSpinner: Spinner
    private lateinit var heldOutBox: CheckBox
    private lateinit var confirmBox: CheckBox
    private lateinit var detectBox: CheckBox
    private lateinit var statusText: TextView
    private lateinit var detectionText: TextView
    private lateinit var previewText: TextView
    private lateinit var cameraButton: Button
    private lateinit var shareButton: Button
    private lateinit var textureView: TextureView
    private lateinit var overlay: RulerOverlayView

    private val runId = "scale." + System.currentTimeMillis()
    private val deviceToken = opaqueDeviceToken()
    private val frameCounter = AtomicLong(0)
    private val kindNames = listOf(
        "indoor_scale", "outdoor_scale", "right_angle",
        "stillness", "thermal_repeat", "day_night",
        "held_out", "cross_session", "cross_device",
    )
    private val conditionNames = listOf(
        "indoor", "outdoor", "day", "night", "thermal", "still",
    )

    @Volatile
    private var latestLuma: ByteArray? = null
    @Volatile
    private var lumaWidth = 0
    @Volatile
    private var lumaHeight = 0
    @Volatile
    private var lumaRowStride = 0
    @Volatile
    private var lumaPixelStride = 1

    private val previewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var matrixReady = false

    private var cameraRequested = false
    private var flip180 = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var previewSurface: Surface? = null
    private var reader: ImageReader? = null
    private var activeSurfaceTexture: SurfaceTexture? = null
    private var captureIndex = 0
    private var currentRecord: String = ""
    private var detectedTicks: List<ArKernelRulerTickRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "Zollstock automatisch erkennen (ARKSCALE1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm zur automatischen Zollstock-Erkennung"
        }
        val version = TextView(this).apply {
            text = "Version 2.10-ruler"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "1) Zollstock auf eine bekannte Länge ausklappen " +
                "(z. B. 1 m oder 2 m). 2) Opt-in anhaken, Kamera starten. " +
                "3) Zwei Marken grob auf die Enden des Zollstocks tippen, " +
                "zwei auf das Ziel. 4) Ausgeklappte Länge eintragen. " +
                "5) „Zollstock erkennen\" – die App findet die " +
                "Zentimeter-Marken selbst."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der Zollstock-Erkennung"
        }
        val safety = TextView(this).apply {
            text = "Der Kamerabildinhalt wird nur im Arbeitsspeicher " +
                "ausgewertet und sofort verworfen: kein Bild, Frame, " +
                "Pixel oder Pose wird gespeichert oder geteilt. Die " +
                "Erkennung macht die Endpunkte wiederholgenau, aber " +
                "nicht metrisch richtig: Tiefe, Perspektive, " +
                "Rolling-Shutter und Verzeichnung bleiben unberücksichtigt. " +
                "Kein KI-Modell, keine Inferenz, kein Netz, keine " +
                "automatische/universelle Kalibrierung, keine " +
                "Zentimetergenauigkeit. Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Grenzen der Zollstock-Erkennung"
        }
        statusText = TextView(this).apply {
            text = savedInstanceState?.getString(
                STATE_STATUS) ?: "Kamera noch nicht gestartet."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        optInBox = CheckBox(this).apply {
            text = "Ich stimme der Bildauswertung im Arbeitsspeicher " +
                "für diese Messung zu (kein Bild wird gespeichert)."
            textSize = 13f
            isChecked = savedInstanceState?.getBoolean(
                STATE_OPT_IN, false) ?: false
            contentDescription = "Ausdrückliches Opt-in für die Bildauswertung"
        }

        val previewHeight = (300 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this)
        textureView = TextureView(this).apply {
            contentDescription = "Live-Vorschau der Rückkamera"
        }
        frame.addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                previewHeight,
            ),
        )
        overlay = RulerOverlayView(this)
        frame.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                previewHeight,
            ),
        )
        textureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    activeSurfaceTexture = surface
                    if (cameraRequested) openCamera(surface)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                }

                override fun onSurfaceTextureDestroyed(
                    surface: SurfaceTexture,
                ): Boolean {
                    stopCamera()
                    activeSurfaceTexture = null
                    return true
                }

                override fun onSurfaceTextureUpdated(
                    surface: SurfaceTexture,
                ) {
                }
            }

        detectionText = TextView(this).apply {
            text = "Noch keine Erkennung durchgeführt."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            contentDescription = "Ergebnis der Zollstock-Erkennung"
        }
        cameraButton = labeledButton(
            "Kamera starten / stoppen",
            "Startet oder stoppt die Live-Vorschau")
        val flipButton = labeledButton(
            "Bild um 180° drehen",
            "Dreht die Vorschau, falls sie auf dem Kopf steht")
        val detectButton = labeledButton(
            "Zollstock erkennen",
            "Erkennt die Zentimeter-Marken automatisch")
        val resetButton = labeledButton(
            "Marken zurücksetzen",
            "Löscht alle vier Messmarken")
        foldedInput = EditText(this).apply {
            hint = "Ausgeklappte Länge in m (z. B. 1 oder 2)"
            setText(savedInstanceState?.getString(
                STATE_FOLDED, "1") ?: "1")
            textSize = 13f
            contentDescription = "Ausgeklappte Länge des Zollstocks in Metern"
        }
        val oneMetreButton = labeledButton(
            "1 m",
            "Setzt die ausgeklappte Länge auf einen Meter")
        val twoMetreButton = labeledButton(
            "2 m",
            "Setzt die ausgeklappte Länge auf zwei Meter")
        kindSpinner = spinner(kindNames,
            savedInstanceState?.getString(STATE_KIND), "Szenenart")
        conditionSpinner = spinner(conditionNames,
            savedInstanceState?.getString(STATE_CONDITION),
            "Umgebungsbedingung")
        heldOutBox = CheckBox(this).apply {
            text = "Held-out-Messung (zurückgehaltene Referenz)"
            textSize = 13f
            isChecked = savedInstanceState?.getBoolean(
                STATE_HELD_OUT, false) ?: false
            contentDescription = "Zurückgehaltene Held-out-Messung"
        }
        confirmBox = CheckBox(this).apply {
            text = "Ich habe den Zollstock selbst ausgeklappt und " +
                "bestätige diese Aufnahme."
            textSize = 13f
            isChecked = savedInstanceState?.getBoolean(
                STATE_CONFIRM, false) ?: false
            contentDescription = "Ausdrückliche Bestätigung der Aufnahme"
        }
        detectBox = CheckBox(this).apply {
            text = "Ich habe die erkannten Marken geprüft und bestätige " +
                "die Erkennung."
            textSize = 13f
            isChecked = savedInstanceState?.getBoolean(
                STATE_DETECT, false) ?: false
            contentDescription = "Bestätigung der erkannten Zollstock-Marken"
        }
        val captureButton = labeledButton(
            "ARKSCALE1-Messung aufnehmen",
            "Erzeugt die ARKSCALE1-Messdatei")
        shareButton = labeledButton(
            "ARKSCALE1 teilen / hier hochladen",
            "Teilt die ARKSCALE1-Messdatei")
        val backButton = labeledButton(
            "Zurück zur Übersicht",
            "Kehrt zur Übersicht zurück")
        previewText = TextView(this).apply {
            text = "Noch keine Messung aufgenommen."
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
        root.addView(version, fullWidthParams())
        root.addView(intro, fullWidthParams())
        root.addView(safety, fullWidthParams())
        root.addView(statusText, fullWidthParams())
        root.addView(optInBox, fullWidthParams())
        root.addView(cameraButton, fullWidthParams())
        root.addView(flipButton, fullWidthParams())
        root.addView(
            frame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                previewHeight,
            ),
        )
        root.addView(detectionText, fullWidthParams())
        root.addView(detectButton, fullWidthParams())
        root.addView(resetButton, fullWidthParams())
        root.addView(foldedInput, fullWidthParams())
        root.addView(oneMetreButton, fullWidthParams())
        root.addView(twoMetreButton, fullWidthParams())
        root.addView(kindSpinner, fullWidthParams())
        root.addView(conditionSpinner, fullWidthParams())
        root.addView(heldOutBox, fullWidthParams())
        root.addView(confirmBox, fullWidthParams())
        root.addView(detectBox, fullWidthParams())
        root.addView(captureButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(backButton, fullWidthParams())
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)

        cameraButton.setOnClickListener { toggleCamera() }
        flipButton.setOnClickListener {
            flip180 = !flip180
            applyPreviewTransform()
        }
        detectButton.setOnClickListener { detectRuler() }
        resetButton.setOnClickListener {
            overlay.reset()
            detectedTicks = emptyList()
            detectionText.text = "Marken zurückgesetzt."
        }
        oneMetreButton.setOnClickListener { foldedInput.setText("1") }
        twoMetreButton.setOnClickListener { foldedInput.setText("2") }
        captureButton.setOnClickListener { captureRecord() }
        shareButton.setOnClickListener { shareRecord() }
        backButton.setOnClickListener { finish() }

        if (savedInstanceState != null) {
            captureIndex = savedInstanceState.getInt(
                STATE_CAPTURE_INDEX, 0)
            flip180 = savedInstanceState.getBoolean(STATE_FLIP, false)
            currentRecord = savedInstanceState.getString(
                STATE_RECORD, "")!!
            if (currentRecord.isNotEmpty()) {
                previewText.text = currentRecord
            }
            val saved = savedInstanceState.getFloatArray(STATE_MARKERS)
            val count = savedInstanceState.getInt(STATE_MARKER_COUNT, 0)
            if (saved != null && saved.size == 8) {
                overlay.restore(saved, count)
            }
        }
        shareButton.isEnabled = currentRecord.isNotEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPT_IN, optInBox.isChecked)
        outState.putString(STATE_FOLDED, foldedInput.text.toString())
        outState.putString(STATE_KIND,
            kindSpinner.selectedItem.toString())
        outState.putString(STATE_CONDITION,
            conditionSpinner.selectedItem.toString())
        outState.putBoolean(STATE_HELD_OUT, heldOutBox.isChecked)
        outState.putBoolean(STATE_CONFIRM, confirmBox.isChecked)
        outState.putBoolean(STATE_DETECT, detectBox.isChecked)
        outState.putFloatArray(STATE_MARKERS, overlay.snapshot())
        outState.putInt(STATE_MARKER_COUNT, overlay.markerCount())
        outState.putBoolean(STATE_FLIP, flip180)
        outState.putInt(STATE_CAPTURE_INDEX, captureIndex)
        outState.putString(STATE_RECORD, currentRecord)
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    override fun onStart() {
        super.onStart()
        if (cameraRequested && activeSurfaceTexture != null &&
            cameraDevice == null) {
            openCamera(activeSurfaceTexture!!)
        }
    }

    override fun onStop() {
        stopCamera()
        super.onStop()
    }

    override fun onDestroy() {
        latestLuma = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_RULER) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            activeSurfaceTexture?.let { openCamera(it) }
        } else {
            cameraRequested = false
            statusText.text = "Kameraberechtigung verweigert – " +
                "die Erkennung bleibt aus. Qualität: unverified."
        }
    }

    private fun toggleCamera() {
        if (cameraRequested) {
            cameraRequested = false
            stopCamera()
            statusText.text = "Kamera gestoppt. Frames gezählt: " +
                frameCounter.get() + " (nicht gespeichert)."
            return
        }
        if (!optInBox.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_RULER)
            cameraRequested = true
            return
        }
        cameraRequested = true
        activeSurfaceTexture?.let { openCamera(it) }
            ?: run { statusText.text = "Vorschau wird vorbereitet …" }
    }

    private fun openCamera(surfaceTexture: SurfaceTexture) {
        if (cameraDevice != null || cameraThread != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.CAMERA_SERVICE)
            as? CameraManager
        if (manager == null) {
            statusText.text = "Kein Kamera-Manager verfügbar."
            return
        }
        val cameraId = backCameraId(manager)
        if (cameraId == null) {
            statusText.text = "Keine Kamera gefunden."
            return
        }
        val sensorOrientation = try {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (error: Exception) {
            0
        }
        val previewSize = try {
            val map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics
                    .SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(
                SurfaceTexture::class.java)
            sizes?.filter {
                it.width <= 1_920 && it.height <= 1_080
            }?.maxByOrNull {
                it.width.toLong() * it.height.toLong()
            } ?: Size(1_280, 720)
        } catch (error: Exception) {
            Size(1_280, 720)
        }
        val thread = HandlerThread("arkscale-camera").apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler
        try {
            surfaceTexture.setDefaultBufferSize(
                previewSize.width, previewSize.height)
            val surface = Surface(surfaceTexture)
            previewSurface = surface
            val imageReader = ImageReader.newInstance(
                READER_WIDTH, READER_HEIGHT,
                android.graphics.ImageFormat.YUV_420_888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ imageReaderValue ->
                val image = imageReaderValue.acquireLatestImage()
                if (image != null) {
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
                                    buffer.get(
                                        source + column * pixelStride)
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
                }
            }, handler)
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val builder = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW)
                            builder.addTarget(surface)
                            builder.addTarget(imageReader.surface)
                            builder.set(
                                CaptureRequest.CONTROL_MODE,
                                CameraMetadata.CONTROL_MODE_AUTO,
                            )
                            camera.createCaptureSession(
                                listOf(surface, imageReader.surface),
                                object : CameraCaptureSession
                                    .StateCallback() {
                                    override fun onConfigured(
                                        session: CameraCaptureSession,
                                    ) {
                                        captureSession = session
                                        bufferWidth = previewSize.width
                                        bufferHeight = previewSize.height
                                        sensorDegrees = sensorOrientation
                                        applyPreviewTransform()
                                        try {
                                            session.setRepeatingRequest(
                                                builder.build(),
                                                frameCallback(),
                                                handler)
                                            runOnUiThread {
                                                statusText.text =
                                                    "Live-Vorschau läuft. " +
                                                    "Zollstock ins Bild " +
                                                    "halten und vier " +
                                                    "Marken setzen."
                                            }
                                        } catch (error: Exception) {
                                            runOnUiThread {
                                                statusText.text =
                                                    "Vorschau-Anfrage " +
                                                    "fehlgeschlagen: " +
                                                    error.message
                                            }
                                        }
                                    }

                                    override fun onConfigureFailed(
                                        session: CameraCaptureSession,
                                    ) {
                                        runOnUiThread {
                                            statusText.text =
                                                "Vorschau konnte nicht " +
                                                "konfiguriert werden."
                                        }
                                    }
                                },
                                handler,
                            )
                        } catch (error: Exception) {
                            runOnUiThread {
                                statusText.text =
                                    "Kamera-Session fehlgeschlagen: " +
                                    error.message
                            }
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        stopCamera()
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int,
                    ) {
                        runOnUiThread {
                            statusText.text = "Kamerafehler $error."
                        }
                        stopCamera()
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            statusText.text = "Kamera konnte nicht geöffnet werden: " +
                error.message
            stopCamera()
        }
    }

    private var bufferWidth = 0
    private var bufferHeight = 0
    private var sensorDegrees = 0

    /**
     * Builds the buffer-to-view matrix and keeps its inverse. The very same
     * matrix maps the camera buffer onto the preview, so inverting it maps
     * on-screen markers back onto buffer pixels exactly – the measurement is
     * therefore correct whatever the device's sensor orientation is. If the
     * preview still appears upside down, the flip button rotates it by 180°
     * and the inverse is rebuilt, so the mapping stays consistent.
     */
    private fun applyPreviewTransform() {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0 ||
            bufferWidth <= 0 || bufferHeight <= 0) return
        val displayDegrees = displayRotationDegrees()
        val matrix = Matrix()
        val buffer = RectF(
            -bufferWidth / 2f, -bufferHeight / 2f,
            bufferWidth / 2f, bufferHeight / 2f,
        )
        val rotated = RectF()
        var degrees = (sensorDegrees - displayDegrees) % 360
        if (degrees < 0) degrees += 360
        if (flip180) degrees = (degrees + 180) % 360
        matrix.setRotate(degrees.toFloat())
        matrix.mapRect(rotated, buffer)
        if (rotated.width() <= 0f || rotated.height() <= 0f) return
        val scale = maxOf(
            viewWidth / rotated.width(),
            viewHeight / rotated.height(),
        )
        matrix.postScale(scale, scale)
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)
        previewMatrix.set(matrix)
        matrixReady = matrix.invert(inverseMatrix)
        textureView.setTransform(previewMatrix)
    }

    private fun displayRotationDegrees(): Int =
        when (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }) {
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
        val offset = clampedY * lumaRowStride +
            clampedX * lumaPixelStride
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

    private fun detectRuler() {
        if (!optInBox.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        if (overlay.markerCount() != 4) {
            statusText.text = "Bitte vier Marken setzen: 2 auf den " +
                "Zollstock, 2 auf das Ziel. Aktuell: " +
                "${overlay.markerCount()}/4."
            return
        }
        val luma = latestLuma
        if (luma == null) {
            detectionText.text = "Noch kein Bild im Speicher – bitte " +
                "kurz warten, bis die Vorschau läuft."
            return
        }
        val start = overlay.markerAt(0)
        val end = overlay.markerAt(1)
        val profile = IntArray(SAMPLE_COUNT)
        val point = FloatArray(2)
        for (index in 0 until SAMPLE_COUNT) {
            val ratio = index.toFloat() / (SAMPLE_COUNT - 1).toFloat()
            val x = start[0] + (end[0] - start[0]) * ratio
            val y = start[1] + (end[1] - start[1]) * ratio
            toBuffer(x, y, point)
            profile[index] = lumaAt(
                point[0].toInt(), point[1].toInt())
        }
        val detection = try {
            ArKernelRulerTickDetector.detect(profile)
        } catch (error: IllegalArgumentException) {
            detectionText.text = "Erkennung abgelehnt: ${error.message}"
            return
        }
        if (!detection.detected) {
            detectedTicks = emptyList()
            detectionText.text = "Keine gleichmäßigen Marken erkannt " +
                "(gefunden: ${detection.indices.size}). Bitte Zollstock " +
                "näher, flacher und heller ins Bild halten."
            return
        }
        val rows = detection.indices.mapIndexed { index, sample ->
            val ratio = sample.toFloat() /
                (SAMPLE_COUNT - 1).toFloat()
            ArKernelRulerTickRow(
                index,
                (start[0] + (end[0] - start[0]) * ratio).toDouble(),
                (start[1] + (end[1] - start[1]) * ratio).toDouble(),
            )
        }
        detectedTicks = rows
        overlay.setTicks(rows)
        val folded = foldedInput.text.toString().trim().toDoubleOrNull()
        val expected = if (folded == null) {
            null
        } else {
            (folded * 100.0).toLong().coerceIn(1L, 200L).toInt()
        }
        val expectation = if (expected == null) {
            "unbekannte Erwartung (Länge eintragen)"
        } else {
            "erwartet $expected"
        }
        detectionText.text = "Erkannt: ${rows.size} Marken " +
            "($expectation) | Abstand: ${detection.spacing} " +
            "Samples | Gleichmäßigkeit: ${detection.regularity}\n" +
            "Gleichmäßigkeit ist ein Strukturbild der erkannten " +
            "Marken, kein Genauigkeitsurteil."
        announce("${rows.size} Marken erkannt")
    }

    private fun captureRecord() {
        if (!optInBox.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        if (overlay.markerCount() != 4) {
            statusText.text = "Bitte vier Marken setzen. Aktuell: " +
                "${overlay.markerCount()}/4."
            return
        }
        if (detectedTicks.isEmpty()) {
            statusText.text = "Bitte zuerst „Zollstock erkennen\" " +
                "ausführen."
            return
        }
        val folded = foldedInput.text.toString().trim().toDoubleOrNull()
        if (folded == null) {
            statusText.text = "Bitte die ausgeklappte Länge in Metern " +
                "eintragen (z. B. 1 oder 2)."
            return
        }
        val kind = ArKernelCampaignSceneKind.fromWire(
            kindSpinner.selectedItem.toString())
        val condition = ArKernelMeasurementCondition.fromWire(
            conditionSpinner.selectedItem.toString())
        if (kind == null || condition == null) {
            statusText.text = "Ungültige Szene oder Bedingung."
            return
        }
        val snapped = overlay.snappedMarkers(detectedTicks)
        val created = ArKernelRulerScaleAdapter.create(
            sessionId = runId,
            deviceToken = deviceToken,
            captureIndex = captureIndex,
            framesObserved = frameCounter.get(),
            framesAnalyzed = 1L,
            sampleCount = SAMPLE_COUNT,
            sceneKind = kind,
            condition = condition,
            heldOut = heldOutBox.isChecked,
            statedFoldedMeters = folded,
            ticks = detectedTicks,
            referenceStart = snapped[0],
            referenceEnd = snapped[1],
            targetStart = snapped[2],
            targetEnd = snapped[3],
            userConfirmed = confirmBox.isChecked,
            rulerDetectionConfirmed = detectBox.isChecked,
        )
        if (!created.accepted || created.bundle == null) {
            statusText.text = "Fail-closed: ${created.rejection} – " +
                created.detail
            announce("Messung abgelehnt")
            currentRecord = ""
            previewText.text = "Keine gültige Messung."
            shareButton.isEnabled = false
            return
        }
        val text = try {
            ArKernelRulerScaleAdapter.encode(created.bundle!!)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val verified = ArKernelRulerScaleAdapter.verify(text)
        if (!verified.accepted || verified.bundle == null) {
            statusText.text = "Fail-closed: ${verified.rejection} – " +
                verified.detail
            return
        }
        val report = ArKernelRulerScaleAdapter.summarize(
            verified.bundle!!)
        currentRecord = text
        previewText.text = text
        statusText.text = "ARKSCALE1 geprüft | Marken: " +
            "${report.tickCount}/${report.expectedTickCount} | " +
            "plausibel: ${report.tickCountConsistent} | " +
            "Gleichmäßigkeit: ${report.tickRegularity}\nMaßstab: " +
            "${report.scalePxPerM} px/m | Schätzung: " +
            "${report.estimatedTargetM} m (Bildverhältnis, NICHT " +
            "metrisch, keine Zentimetergenauigkeit). Struktur " +
            "vollständig: ${report.captureReady}. " +
            "Qualität: unverified."
        val fileName = "ARKSCALE1_${runId}_${captureIndex}.txt"
        Thread {
            val written = writeTextToDownloads(fileName, text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $written"
            }
        }.start()
        captureIndex++
        shareButton.isEnabled = true
        Toast.makeText(
            this,
            "ARKSCALE1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        announce("ARKSCALE1-Messung gespeichert")
    }

    private fun shareRecord() {
        if (currentRecord.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentRecord)
        }
        startActivity(
            Intent.createChooser(intent, "ARKSCALE1 teilen"))
    }

    private fun frameCallback(): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                frameCounter.incrementAndGet()
            }
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
        cameraHandler?.removeCallbacksAndMessages(null)
        try {
            cameraThread?.quitSafely()
        } catch (error: Exception) {
        }
        captureSession = null
        cameraDevice = null
        previewSurface = null
        reader = null
        cameraHandler = null
        cameraThread = null
        latestLuma = null
        matrixReady = false
    }

    private fun backCameraId(manager: CameraManager): String? = try {
        manager.getCameraIdList().firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.getCameraIdList().firstOrNull()
    } catch (error: Exception) {
        null
    }

    private fun announce(text: String) {
        statusText.announceForAccessibility(text)
    }

    private fun spinner(
        names: List<String>,
        saved: String?,
        description: String,
    ): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@RulerScaleActivity,
            android.R.layout.simple_spinner_item,
            names,
        ).apply {
            setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item)
        }
        if (saved != null) {
            setSelection(names.indexOf(saved).coerceAtLeast(0))
        }
        contentDescription = description
    }

    private fun labeledButton(
        label: String,
        description: String,
    ): Button = Button(this).apply {
        text = label
        textSize = 14f
        setAllCaps(false)
        contentDescription = description
        importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 6, 0, 0) }

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

    /** Marker overlay that also draws the automatically detected ticks. */
    private inner class RulerOverlayView(
        context: android.content.Context,
    ) : View(context) {
        private val points = FloatArray(8)
        private var count = 0
        private var ticks: List<ArKernelRulerTickRow> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription =
                "Messmarken: vier Tipps, erkannte Marken gelb"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.argb(140, 255, 255, 255)
            canvas.drawLine(
                width / 2f, 0f, width / 2f, height.toFloat(), paint)
            canvas.drawLine(
                0f, height / 2f, width.toFloat(), height / 2f, paint)
            if (count >= 2) {
                paint.color = Color.rgb(234, 179, 8)
                canvas.drawLine(
                    points[0], points[1], points[2], points[3], paint)
            }
            if (count >= 4) {
                paint.color = Color.rgb(56, 189, 248)
                canvas.drawLine(
                    points[4], points[5], points[6], points[7], paint)
            }
            paint.style = Paint.Style.FILL
            for (tick in ticks) {
                paint.color = Color.rgb(250, 204, 21)
                canvas.drawCircle(
                    tick.x.toFloat(), tick.y.toFloat(), 3f, paint)
            }
            for (index in 0 until count) {
                paint.color = if (index < 2) {
                    Color.rgb(234, 179, 8)
                } else {
                    Color.rgb(56, 189, 248)
                }
                canvas.drawCircle(
                    points[index * 2], points[index * 2 + 1],
                    12f, paint)
                paint.color = Color.BLACK
                paint.textSize = 14f
                canvas.drawText(
                    (index + 1).toString(),
                    points[index * 2] - 4f,
                    points[index * 2 + 1] + 4f,
                    paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (count < 4) {
                    points[count * 2] = event.x
                    points[count * 2 + 1] = event.y
                    count++
                } else {
                    points[0] = event.x
                    points[1] = event.y
                    count = 1
                }
                ticks = emptyList()
                detectedTicks = emptyList()
                performClick()
                invalidate()
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        fun reset() {
            count = 0
            ticks = emptyList()
            invalidate()
        }

        fun setTicks(rows: List<ArKernelRulerTickRow>) {
            ticks = rows
            invalidate()
        }

        fun restore(saved: FloatArray, savedCount: Int) {
            if (saved.size == 8) {
                System.arraycopy(saved, 0, points, 0, 8)
                count = savedCount.coerceIn(0, 4)
                invalidate()
            }
        }

        fun snapshot(): FloatArray = points.copyOf()

        fun markerCount(): Int = count

        fun markerAt(index: Int): FloatArray =
            floatArrayOf(points[index * 2], points[index * 2 + 1])

        /**
         * Snaps the two ruler markers to the nearest detected tick so the
         * reference endpoints sit exactly on centimetre marks.
         */
        fun snappedMarkers(
            rows: List<ArKernelRulerTickRow>,
        ): List<ArKernelMeasurementFeedMarker> {
            val start = markerAt(0)
            val end = markerAt(1)
            val targetStart = markerAt(2)
            val targetEnd = markerAt(3)
            val startIndex = nearestTickIndex(start, rows, 0)
            val endIndex = nearestTickIndex(end, rows, rows.size - 1)
            val snappedStart = rows[startIndex]
            val snappedEnd = rows[endIndex]
            return listOf(
                ArKernelMeasurementFeedMarker(
                    snappedStart.x, snappedStart.y),
                ArKernelMeasurementFeedMarker(
                    snappedEnd.x, snappedEnd.y),
                ArKernelMeasurementFeedMarker(
                    targetStart[0].toDouble(),
                    targetStart[1].toDouble()),
                ArKernelMeasurementFeedMarker(
                    targetEnd[0].toDouble(),
                    targetEnd[1].toDouble()),
            )
        }

        private fun nearestTickIndex(
            point: FloatArray,
            rows: List<ArKernelRulerTickRow>,
            fallback: Int,
        ): Int {
            if (rows.isEmpty()) return fallback
            var best = fallback
            var bestDistance = Double.MAX_VALUE
            for ((index, row) in rows.withIndex()) {
                val dx = row.x - point[0].toDouble()
                val dy = row.y - point[1].toDouble()
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            return best
        }
    }
}
