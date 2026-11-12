package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelCampaignSceneKind
import ai.arena.arkernel.ArKernelMeasurementCondition
import ai.arena.arkernel.ArKernelMeasurementFeedAdapter
import ai.arena.arkernel.ArKernelMeasurementFeedMarker
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
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
import android.view.WindowManager
import android.view.View
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
 * Opt-in live camera preview with user-placed measurement markers.
 *
 * The user sees the rear camera preview, taps two markers on a known
 * independent physical reference (folding rule, construction site) and two
 * markers on the target, then enters the independently measured reference
 * length. The app computes only a screen-ratio scale and a clearly
 * non-metric estimate and writes a bounded `ARKFEED1` text record.
 *
 * No frame, image, pixel array, pose or trajectory is exported: preview
 * frames are counted, never saved. This is not metric measurement, not
 * centimetre validation, not ARCore-equivalent depth, not automatic or
 * universal calibration, not a signature verification, not an automatic
 * upload and not a quality assessment. A real AI model, inference, weights
 * and network access are absent. All captures require explicit user
 * confirmation. The grade stays `unverified`.
 */
class MeasurementFeedActivity : DiagnosticsActivity() {

    private companion object {
        const val REQUEST_CAMERA_FEED = 1002
        const val STATE_OPT_IN = "feed.opt_in"
        const val STATE_REFERENCE = "feed.reference"
        const val STATE_UNCERTAINTY = "feed.uncertainty"
        const val STATE_KIND = "feed.kind"
        const val STATE_CONDITION = "feed.condition"
        const val STATE_HELD_OUT = "feed.held_out"
        const val STATE_CONFIRM = "feed.confirm"
        const val STATE_MARKERS = "feed.markers"
        const val STATE_MARKER_COUNT = "feed.marker_count"
        const val STATE_CAPTURE_INDEX = "feed.capture_index"
        const val STATE_RECORD = "feed.record"
        const val STATE_STATUS = "feed.status"
    }

    private lateinit var optInBox: CheckBox
    private lateinit var referenceInput: EditText
    private lateinit var uncertaintyInput: EditText
    private lateinit var kindSpinner: Spinner
    private lateinit var conditionSpinner: Spinner
    private lateinit var heldOutBox: CheckBox
    private lateinit var confirmBox: CheckBox
    private lateinit var statusText: TextView
    private lateinit var markerText: TextView
    private lateinit var previewText: TextView
    private lateinit var cameraButton: Button
    private lateinit var shareButton: Button
    private lateinit var textureView: TextureView
    private lateinit var overlay: FeedOverlayView

    private val runId = "feed." + System.currentTimeMillis()
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

    private var cameraRequested = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var previewSurface: Surface? = null
    private var activeSurfaceTexture: SurfaceTexture? = null
    private var captureIndex = 0
    private var currentRecord: String = ""
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var sensorDegrees = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "Live-Kamerafeed & Messung (ARKFEED1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für Live-Kamerafeed und Messmarken"
        }
        val version = TextView(this).apply {
            text = "Version 2.9-feed · Wenn dieser Text fehlt, ist noch " +
                "die alte APK installiert."
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "1) Opt-in anhaken und Kamera starten. " +
                "2) Zwei Marken auf eine bekannte Referenzstrecke " +
                "(Zollstock) tippen. 3) Zwei Marken auf das Ziel tippen. " +
                "4) Referenzlänge in Metern eintragen und bestätigen. " +
                "5) Messung aufnehmen."
            textSize = 13f
            contentDescription = "Bedienreihenfolge des Kamerafeeds"
        }
        val safety = TextView(this).apply {
            text = "Kein Bild, Frame, Pixel, Pose oder Trajektorie wird " +
                "gespeichert oder geteilt; Frames werden nur gezählt. " +
                "Die Schätzung ist ein Bildverhältnis, keine metrische " +
                "Messung und keine Zentimetergenauigkeit. Kein KI-Modell, " +
                "keine Inferenz, kein Netz, keine automatische/universelle " +
                "Kalibrierung, keine Signaturprüfung, kein Upload, keine " +
                "Qualitätsaufstufung. Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Grenzen des Kamerafeeds"
        }
        statusText = TextView(this).apply {
            text = savedInstanceState?.getString(
                STATE_STATUS) ?: "Kamera noch nicht gestartet."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        optInBox = CheckBox(this).apply {
            text = "Ich stimme dem Live-Kamerafeed für diese Messung " +
                "ausdrücklich zu (kein Bild wird gespeichert)."
            textSize = 13f
            isChecked = savedInstanceState?.getBoolean(
                STATE_OPT_IN, false) ?: false
            contentDescription = "Ausdrückliches Opt-in für den Kamerafeed"
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
        overlay = FeedOverlayView(this).apply {
            contentDescription = "Messmarken: vier Tipps in fester Reihenfolge"
        }
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

        markerText = TextView(this).apply {
            text = "Marken: 0/4"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            contentDescription = "Anzahl gesetzter Messmarken"
        }
        cameraButton = labeledButton(
            "Kamera starten / stoppen",
            "Startet oder stoppt die Live-Vorschau")
        val resetButton = labeledButton(
            "Marken zurücksetzen",
            "Löscht alle vier Messmarken")
        referenceInput = EditText(this).apply {
            hint = "Referenzlänge in m (z. B. 0.3)"
            setText(savedInstanceState?.getString(
                STATE_REFERENCE, "0.3") ?: "0.3")
            textSize = 13f
            contentDescription = "Unabhängig gemessene Referenzlänge in Metern"
        }
        uncertaintyInput = EditText(this).apply {
            hint = "Unsicherheit in m (z. B. 0.001)"
            setText(savedInstanceState?.getString(
                STATE_UNCERTAINTY, "0.001") ?: "0.001")
            textSize = 13f
            contentDescription = "Unsicherheit der Referenzlänge in Metern"
        }
        kindSpinner = spinner(kindNames,
            savedInstanceState?.getString(STATE_KIND),
            "Szenenart")
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
            text = "Ich habe die Referenz selbst mit Zollstock / " +
                "Baustellenmaß gemessen und bestätige diese Aufnahme."
            textSize = 13f
            isChecked = savedInstanceState?.getBoolean(
                STATE_CONFIRM, false) ?: false
            contentDescription = "Ausdrückliche Bestätigung der Aufnahme"
        }
        val captureButton = labeledButton(
            "ARKFEED1-Messung aufnehmen",
            "Erzeugt die ARKFEED1-Messdatei")
        shareButton = labeledButton(
            "ARKFEED1 teilen / hier hochladen",
            "Teilt die ARKFEED1-Messdatei")
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
        root.addView(
            frame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                previewHeight,
            ),
        )
        root.addView(markerText, fullWidthParams())
        root.addView(resetButton, fullWidthParams())
        root.addView(referenceInput, fullWidthParams())
        root.addView(uncertaintyInput, fullWidthParams())
        root.addView(kindSpinner, fullWidthParams())
        root.addView(conditionSpinner, fullWidthParams())
        root.addView(heldOutBox, fullWidthParams())
        root.addView(confirmBox, fullWidthParams())
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
        resetButton.setOnClickListener {
            overlay.reset()
            updateMarkerText()
        }
        captureButton.setOnClickListener { captureRecord() }
        shareButton.setOnClickListener { shareRecord() }
        backButton.setOnClickListener { finish() }

        if (savedInstanceState != null) {
            captureIndex = savedInstanceState.getInt(
                STATE_CAPTURE_INDEX, 0)
            currentRecord = savedInstanceState.getString(
                STATE_RECORD, "")!!
            if (currentRecord.isNotEmpty()) {
                previewText.text = currentRecord
            }
            val saved = savedInstanceState.getFloatArray(STATE_MARKERS)
            val count = savedInstanceState.getInt(
                STATE_MARKER_COUNT, 0)
            if (saved != null && saved.size == 8) {
                overlay.restore(saved, count)
            }
        }
        shareButton.isEnabled = currentRecord.isNotEmpty()
        updateMarkerText()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPT_IN, optInBox.isChecked)
        outState.putString(STATE_REFERENCE,
            referenceInput.text.toString())
        outState.putString(STATE_UNCERTAINTY,
            uncertaintyInput.text.toString())
        outState.putString(STATE_KIND,
            kindSpinner.selectedItem.toString())
        outState.putString(STATE_CONDITION,
            conditionSpinner.selectedItem.toString())
        outState.putBoolean(STATE_HELD_OUT, heldOutBox.isChecked)
        outState.putBoolean(STATE_CONFIRM, confirmBox.isChecked)
        outState.putFloatArray(STATE_MARKERS, overlay.snapshot())
        outState.putInt(STATE_MARKER_COUNT, overlay.markerCount())
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
        stopCamera()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_FEED) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            activeSurfaceTexture?.let { openCamera(it) }
        } else {
            cameraRequested = false
            statusText.text = "Kameraberechtigung verweigert – " +
                "der Feed bleibt aus. Qualität: unverified."
            cameraButton.text = "Kamera starten / stoppen"
        }
    }

    private fun toggleCamera() {
        if (cameraRequested) {
            cameraRequested = false
            stopCamera()
            statusText.text = "Kamera gestoppt. Frames gezählt: " +
                frameCounter.get() + " (nicht exportiert)."
            cameraButton.text = "Kamera starten / stoppen"
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
                REQUEST_CAMERA_FEED)
            cameraRequested = true
            cameraButton.text = "Kamera stoppen"
            return
        }
        cameraRequested = true
        cameraButton.text = "Kamera stoppen"
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
        val size = try {
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
        val thread = HandlerThread("arkfeed-camera").apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler
        try {
            surfaceTexture.setDefaultBufferSize(
                size.width, size.height)
            val surface = Surface(surfaceTexture)
            previewSurface = surface
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val builder = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW)
                            builder.addTarget(surface)
                            builder.set(
                                CaptureRequest.CONTROL_MODE,
                                CameraMetadata.CONTROL_MODE_AUTO,
                            )
                            camera.createCaptureSession(
                                listOf(surface),
                                object : CameraCaptureSession
                                    .StateCallback() {
                                    override fun onConfigured(
                                        session: CameraCaptureSession,
                                    ) {
                                        captureSession = session
                                        bufferWidth = size.width
                                        bufferHeight = size.height
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
                                                    "Tippe vier Marken. " +
                                                    "Frames werden nur " +
                                                    "gezählt."
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

                    override fun onDisconnected(
                        camera: CameraDevice,
                    ) {
                        runOnUiThread {
                            statusText.text =
                                "Kamera getrennt."
                        }
                        stopCamera()
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int,
                    ) {
                        runOnUiThread {
                            statusText.text =
                                "Kamerafehler $error."
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
        cameraHandler?.removeCallbacksAndMessages(null)
        try {
            cameraThread?.quitSafely()
        } catch (error: Exception) {
        }
        captureSession = null
        cameraDevice = null
        previewSurface = null
        cameraHandler = null
        cameraThread = null
    }

    /**
     * Rotates and scales the preview so the camera buffer fills the view.
     * Without this the rear-camera preview appears sideways on most phones.
     */
    private fun applyPreviewTransform() {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0 ||
            bufferWidth <= 0 || bufferHeight <= 0) return
        val matrix = Matrix()
        val buffer = RectF(
            -bufferWidth / 2f, -bufferHeight / 2f,
            bufferWidth / 2f, bufferHeight / 2f,
        )
        val rotated = RectF()
        var degrees = (sensorDegrees - displayRotationDegrees()) % 360
        if (degrees < 0) degrees += 360
        matrix.setRotate(degrees.toFloat())
        matrix.mapRect(rotated, buffer)
        if (rotated.width() <= 0f || rotated.height() <= 0f) return
        val scale = maxOf(
            viewWidth / rotated.width(), viewHeight / rotated.height())
        matrix.postScale(scale, scale)
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
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

    private fun backCameraId(manager: CameraManager): String? = try {
        manager.getCameraIdList().firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.getCameraIdList().firstOrNull()
    } catch (error: Exception) {
        null
    }

    private fun captureRecord() {
        if (!optInBox.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        if (overlay.markerCount() != 4) {
            statusText.text = "Bitte vier Marken setzen: 2 Referenz, " +
                "2 Ziel. Aktuell: ${overlay.markerCount()}/4."
            return
        }
        val reference = referenceInput.text.toString()
            .trim().toDoubleOrNull()
        val uncertainty = uncertaintyInput.text.toString()
            .trim().toDoubleOrNull()
        if (reference == null || uncertainty == null) {
            statusText.text = "Bitte gültige Zahlen für Referenzlänge " +
                "und Unsicherheit eingeben."
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
        val created = ArKernelMeasurementFeedAdapter.create(
            sessionId = runId,
            deviceToken = deviceToken,
            captureIndex = captureIndex,
            framesObserved = frameCounter.get(),
            sceneKind = kind,
            condition = condition,
            heldOut = heldOutBox.isChecked,
            referenceMeters = reference,
            referenceUncertaintyMeters = uncertainty,
            markers = overlay.markerList(),
            userConfirmed = confirmBox.isChecked,
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
            ArKernelMeasurementFeedAdapter.encode(created.bundle!!)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val verified = ArKernelMeasurementFeedAdapter.verify(text)
        if (!verified.accepted || verified.bundle == null) {
            statusText.text = "Fail-closed: ${verified.rejection} – " +
                verified.detail
            return
        }
        val report = ArKernelMeasurementFeedAdapter.summarize(
            verified.bundle!!)
        currentRecord = text
        previewText.text = text
        statusText.text = "ARKFEED1 geprüft | Frames gezählt: " +
            "${report.framesObserved} | exportierte Frames: " +
            "${report.framesExported} | Marken: " +
            "${report.markerCount}\nBildverhältnis-Schätzung: " +
            "${report.estimatedTargetM} m (NICHT metrisch, keine " +
            "Zentimetergenauigkeit). Struktur vollständig: " +
            "${report.captureReady}. Qualität: unverified."
        val fileName = "ARKFEED1_${runId}_${captureIndex}.txt"
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
            "ARKFEED1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        announce("ARKFEED1-Messung gespeichert")
    }

    private fun shareRecord() {
        if (currentRecord.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentRecord)
        }
        startActivity(
            Intent.createChooser(intent, "ARKFEED1 teilen"))
    }

    private fun updateMarkerText() {
        markerText.text = "Marken: ${overlay.markerCount()}/4 " +
            "(1–2 Referenz, 3–4 Ziel). Koordinaten in " +
            "Bildschirm-Pixeln, nicht in Metern."
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
            this@MeasurementFeedActivity,
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

    /** Four user-placed markers drawn over the camera preview. */
    private inner class FeedOverlayView(
        context: android.content.Context,
    ) : View(context) {
        private val points = FloatArray(8)
        private var count = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 3f
        }

        init {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(140, 255, 255, 255)
            canvas.drawLine(
                width / 2f, 0f, width / 2f, height.toFloat(), paint)
            canvas.drawLine(
                0f, height / 2f, width.toFloat(), height / 2f, paint)
            if (count >= 2) {
                paint.color = Color.rgb(34, 197, 94)
                canvas.drawLine(
                    points[0], points[1], points[2], points[3], paint)
            }
            if (count >= 4) {
                paint.color = Color.rgb(56, 189, 248)
                canvas.drawLine(
                    points[4], points[5], points[6], points[7], paint)
            }
            paint.style = Paint.Style.FILL
            for (index in 0 until count) {
                paint.color = if (index < 2) {
                    Color.rgb(34, 197, 94)
                } else {
                    Color.rgb(56, 189, 248)
                }
                canvas.drawCircle(
                    points[index * 2], points[index * 2 + 1],
                    12f, paint)
                paint.color = Color.BLACK
                canvas.drawText(
                    (index + 1).toString(),
                    points[index * 2] - 4f,
                    points[index * 2 + 1] + 4f,
                    paint.apply { textSize = 14f })
                paint.color = if (index < 2) {
                    Color.rgb(34, 197, 94)
                } else {
                    Color.rgb(56, 189, 248)
                }
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
                performClick()
                invalidate()
                updateMarkerText()
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

        fun markerList(): List<ArKernelMeasurementFeedMarker> =
            (0 until 4).map { index ->
                ArKernelMeasurementFeedMarker(
                    points[index * 2].toDouble(),
                    points[index * 2 + 1].toDouble(),
                )
            }
    }
}
