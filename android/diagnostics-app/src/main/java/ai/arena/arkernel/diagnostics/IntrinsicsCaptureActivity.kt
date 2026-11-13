package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelArtifactCatalogue
import ai.arena.arkernel.ArKernelBoardRejection
import ai.arena.arkernel.ArKernelCheckerboard
import ai.arena.arkernel.ArKernelIntrinsicsEstimator
import ai.arena.arkernel.ArKernelIntrinsicsObservationAdapter
import ai.arena.arkernel.ArKernelIntrinsicsObservationBundle
import ai.arena.arkernel.ArKernelIntrinsicsObservationView
import ai.arena.arkernel.ArKernelIntrinsicsPoint
import ai.arena.arkernel.ArKernelIntrinsicsView
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
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Knopf 28: measures the camera instead of taking the manufacturer's word.
 *
 * The operator prints a checkerboard, taps its four outer corners, and takes
 * a series of shots from spread-out directions. From those corners the kernel
 * recovers focal length, principal point and radial distortion.
 *
 * What it deliberately does not do:
 *
 * * **No automatic board finding.** A detector that reports corners whether or
 *   not it found them is worse than none, because its failures are invisible.
 *   Four taps pin the plane down, and every corner is then *predicted* before
 *   it is searched for - so the search can only correct the prediction, never
 *   invent one.
 * * **No claim in metres.** The grid has to be square, not measured: a printed
 *   sheet suffices, and the square's size in millimetres never enters the
 *   computation. Nothing here recovers the board's distance either, and the
 *   record says both.
 * * **No file unless the measurement is accepted.** A refused fit produces a
 *   message on screen naming which gate stayed open, and nothing is written.
 *   An `ARKINTR1` that exists is therefore one whose numbers the reader can
 *   recompute from the corners inside it.
 *
 * Images are not stored. The camera stream exists because the corner search
 * needs pixels; the frames stay in memory and are discarded, and only corner
 * coordinates - numbers, not a picture of the room - are written down.
 */
class IntrinsicsCaptureActivity : android.app.Activity() {

    private companion object {
        const val GRID_COLUMNS = 9
        const val GRID_ROWS = 7
        const val MIN_VIEWS = 6
        const val MAX_VIEWS = 12
        const val CAMERA_PERMISSION_CODE = 4821
    }

    private lateinit var optInBox: CheckBox
    private lateinit var statusText: TextView
    private lateinit var captureText: TextView
    private lateinit var cameraButton: Button
    private lateinit var tapButton: Button
    private lateinit var captureButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var textureView: TextureView
    private lateinit var overlay: BoardOverlayView

    private val runId = "intrinsics." + System.currentTimeMillis()
    private val deviceToken = opaqueDeviceToken()
    private val frameCounter = AtomicLong(0)

    @Volatile
    private var latestLuma: ByteArray? = null
    @Volatile
    private var lumaWidth = 0
    @Volatile
    private var lumaHeight = 0

    private val previewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var matrixReady = false

    private var cameraRequested = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var previewSurface: Surface? = null
    private var reader: ImageReader? = null
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var sensorDegrees = 0

    /** One accepted shot: the corners, in sensor pixels, row-major. */
    private val views = ArrayList<ArKernelIntrinsicsObservationView>()
    private var lastWritten = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        statusText = TextView(this).apply {
            text = "Schritt 28: die Kamera selbst vermessen."
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        optInBox = CheckBox(this).apply {
            text = "Ich habe ein Schachbrett (mindestens 9 x 7 Ecken, " +
                "also 8 x 6 Kaestchen) flach aufgeklebt und will messen."
            isChecked = false
        }
        cameraButton = Button(this).apply { text = "Kamera starten" }
        tapButton = Button(this).apply { text = "Die vier Ecken neu tippen" }
        captureButton = Button(this).apply { text = "Diese Ansicht aufnehmen" }
        saveButton = Button(this).apply { text = "ARKINTR1 erzeugen" }
        shareButton = Button(this).apply { text = "ARKINTR1 teilen" }
        captureText = TextView(this).apply {
            text = "Noch keine Ansicht aufgenommen."
            textSize = 13f
        }

        textureView = TextureView(this)
        overlay = BoardOverlayView(this)
        val previewHeight = (220 * resources.displayMetrics.density).toInt()
        val fill = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        val frame = FrameLayout(this).apply {
            addView(textureView)
            addView(overlay)
        }
        textureView.layoutParams = fill
        overlay.layoutParams = fill

        val guidance = TextView(this).apply {
            text = "So geht es: Brett ganz ins Bild halten - nichts darf " +
                "abgeschnitten sein. Die vier AEUSSEREN Ecken des Musters " +
                "antippen, im Uhrzeigersinn: oben links, oben rechts, " +
                "unten rechts, unten links. Dann aufnehmen. " +
                "Danach das Brett kippen, drehen, naeher heran, weiter weg " +
                "- und wieder. Ab 6 verschiedenen Ansichten kann gespeichert " +
                "werden; 10 sind besser."
            textSize = 13f
            setTextColor(Color.rgb(180, 190, 205))
        }
        val note = TextView(this).apply {
            text = "Bilder werden nicht gespeichert. Es fliesst nur die " +
                "Kamera ins Bild, damit die Ecken gefunden werden koennen; " +
                "in der Datei stehen am Ende Zahlen, kein Foto des Raumes."
            textSize = 12f
            setTextColor(Color.rgb(150, 160, 175))
        }
        val backButton = Button(this).apply { text = "Zurueck" }

        val full = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        root.addView(statusText, full)
        root.addView(guidance, full)
        root.addView(optInBox, full)
        root.addView(cameraButton, full)
        root.addView(
            frame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                previewHeight,
            ),
        )
        root.addView(tapButton, full)
        root.addView(captureButton, full)
        root.addView(captureText, full)
        root.addView(saveButton, full)
        root.addView(shareButton, full)
        root.addView(note, full)
        root.addView(backButton, full)
        scroll.addView(root)
        setContentView(scroll)

        cameraButton.setOnClickListener { toggleCamera() }
        tapButton.setOnClickListener {
            overlay.reset()
            statusText.text = "Bitte die vier aeusseren Ecken antippen: " +
                "oben links, oben rechts, unten rechts, unten links."
        }
        captureButton.setOnClickListener { captureView() }
        saveButton.setOnClickListener { saveRecord() }
        shareButton.setOnClickListener { shareRecord() }
        backButton.setOnClickListener { finish() }

        textureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    if (cameraRequested) openCamera()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = applyPreviewTransform()

                override fun onSurfaceTextureDestroyed(
                    surface: SurfaceTexture,
                ): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    frameCounter.incrementAndGet()
                }
            }
        updateButtons()
    }

    private fun updateButtons() {
        val ready = views.size >= MIN_VIEWS
        saveButton.isEnabled = ready && views.size <= MAX_VIEWS
        captureButton.isEnabled = views.size < MAX_VIEWS
        shareButton.isEnabled = lastWritten.isNotEmpty()
    }

    // ------------------------------------------------------------- capture

    private fun captureView() {
        if (!optInBox.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        val luma = latestLuma
        if (luma == null || lumaWidth <= 0 || lumaHeight <= 0) {
            statusText.text = "Noch kein Bild. Bitte die Kamera starten."
            return
        }
        if (overlay.count() != 4) {
            statusText.text = "Bitte vier Ecken antippen. Aktuell: " +
                "${overlay.count()}/4."
            return
        }
        val taps = overlay.tapsInBuffer()
        val result = ArKernelCheckerboard.refineBoard(
            gray = luma,
            width = lumaWidth,
            height = lumaHeight,
            columns = GRID_COLUMNS,
            rows = GRID_ROWS,
            quad = taps,
        )
        if (!result.accepted) {
            statusText.text = explanationFor(result.rejection) +
                "\n" + result.detail
            return
        }
        val points = result.corners.map {
            ArKernelIntrinsicsPoint(it.xPx, it.yPx)
        }
        views.add(
            ArKernelIntrinsicsObservationView(
                index = views.size,
                corners = points,
            ),
        )
        overlay.markFound(result.corners.filter { it.valid }.map {
            Pair(it.xPx.toFloat(), it.yPx.toFloat())
        })
        captureText.text = buildString {
            append("${views.size} von mindestens $MIN_VIEWS Ansichten. ")
            append("Letzte: ${result.validCount} Ecken, ")
            append(
                String.format(
                    Locale.US,
                    "Verschiebung %.2f px, Kontrast %.0f.",
                    result.meanShiftPx,
                    result.meanScore,
                ),
            )
            if (views.size >= MIN_VIEWS) {
                append(" Speichern ist jetzt moeglich.")
            }
        }
        statusText.text = "Ansicht aufgenommen. Das Brett nun deutlich " +
            "anders hinhalten - kippen, drehen, naeher, weiter weg - " +
            "und die vier Ecken neu antippen."
        updateButtons()
    }

    /**
     * What the operator did wrong, in their words.
     *
     * Every refusal the kernel can produce is translated here, because a
     * message the operator cannot act on is the same as no message: the
     * measurement simply does not happen.
     */
    private fun explanationFor(rejection: ArKernelBoardRejection): String =
        when (rejection) {
            ArKernelBoardRejection.OK -> "Angenommen."
            ArKernelBoardRejection.QUAD_DEGENERATE ->
                "Die vier Punkte liegen aufeinander oder auf einer Linie. " +
                    "Bitte vier verschiedene Ecken des Musters antippen."
            ArKernelBoardRejection.SQUARES_TOO_SMALL ->
                "Die Kaestchen sind zu klein im Bild. Bitte naeher heran."
            ArKernelBoardRejection.TOO_FEW_CORNERS ->
                "Zu viele Ecken wurden nicht gefunden. Meist liegt das an " +
                    "Licht oder Bewegung: ruhig halten, Schatten und helle " +
                    "Flecken auf dem Muster vermeiden."
            ArKernelBoardRejection.BOARD_NOT_FULLY_VISIBLE ->
                "Das Schachbrett ist nicht vollstaendig im Bild. Eine Ecke, " +
                    "die abgeschnitten ist, wird nicht gemessen - bitte das " +
                    "Brett ganz ins Bild nehmen oder weiter weg gehen."
            ArKernelBoardRejection.INCONSISTENT_ORIENTATION ->
                "Die gefundenen Punkte sind kein Schachbrett. Wahrscheinlich " +
                    "sitzt einer der vier Tipps nicht auf einer Ecke, oder " +
                    "die Reihenfolge stimmt nicht: oben links, oben rechts, " +
                    "unten rechts, unten links."
            ArKernelBoardRejection.LOW_CONTRAST ->
                "Kein Muster erkennbar. Bitte das Brett ins Bild halten und " +
                    "auf gleichmaessiges Licht achten."
        }

    private fun saveRecord() {
        if (!optInBox.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        if (views.size < MIN_VIEWS) {
            statusText.text = "Mindestens $MIN_VIEWS Ansichten noetig, " +
                "aktuell ${views.size}."
            return
        }
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views = views.map { ArKernelIntrinsicsView(it.corners) },
            gridColumns = GRID_COLUMNS,
            gridRows = GRID_ROWS,
            imageWidthPx = lumaWidth,
            imageHeightPx = lumaHeight,
        )
        if (!estimate.accepted) {
            val open = estimate.verdicts
                .filter { !it.value }
                .keys
                .joinToString(", ")
                .ifEmpty { "keins" }
            statusText.text = "Die Messung wird abgelehnt und es wird " +
                "KEINE Datei geschrieben. Grund: ${estimate.detail}\n" +
                "Offene Pruefung: $open."
            return
        }
        val intrinsics = estimate.intrinsics ?: run {
            statusText.text = "Die Rechnung lieferte keine Kamerawerte."
            return
        }
        val bundle = ArKernelIntrinsicsObservationBundle(
            sessionId = runId,
            deviceToken = deviceToken,
            gridColumns = GRID_COLUMNS,
            gridRows = GRID_ROWS,
            imageWidth = lumaWidth,
            imageHeight = lumaHeight,
            sensorRotationDeg = sensorDegrees,
            views = views.toList(),
            intrinsics = intrinsics,
            rmsPx = estimate.rmsPx,
            maxPx = estimate.maxPx,
            detail = estimate.detail,
        )
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle)
        // Write only what the reader can check: reparse first, so a file is
        // never produced that the catalogue would reject.
        val check = ArKernelIntrinsicsObservationAdapter.parse(text)
        if (!check.accepted) {
            statusText.text = "Die erzeugte Datei haelt ihrer eigenen " +
                "Pruefung nicht stand (${check.rejection.name}) und wird " +
                "nicht geschrieben: ${check.detail}"
            return
        }
        lastWritten = text
        val fov = bundle.horizontalFovDeg()
        statusText.text = String.format(
            Locale.US,
            "Angenommen. Brennweite %.1f / %.1f px, Hauptpunkt " +
                "%.1f / %.1f px, Verzeichnung k1 %.4f, k2 %.4f. " +
                "Rueckstand %.2f px, Bildwinkel %.1f Grad.\n" +
                "Das Raster wurde NICHT gemessen: daraus folgt kein Maß in " +
                "Metern und keine Entfernung.",
            intrinsics.fxPx,
            intrinsics.fyPx,
            intrinsics.cxPx,
            intrinsics.cyPx,
            intrinsics.k1,
            intrinsics.k2,
            estimate.rmsPx,
            fov,
        )
        val fileName = "ARKINTR1_${runId}.txt"
        Thread {
            val result = writeTextToDownloads(fileName, text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $result"
                val verification = ArKernelArtifactCatalogue
                    .contractOf(fileName)
                if (verification == null) {
                    statusText.text = statusText.text.toString() +
                        "\nAchtung: der Dateiname wurde vom Katalog nicht " +
                        "erkannt."
                }
            }
        }.start()
        Toast.makeText(
            this,
            "ARKINTR1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        updateButtons()
    }

    private fun shareRecord() {
        if (lastWritten.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lastWritten)
        }
        startActivity(Intent.createChooser(intent, "ARKINTR1 teilen"))
    }

    // -------------------------------------------------------------- camera

    private fun toggleCamera() {
        if (cameraDevice != null) {
            closeCamera()
            cameraButton.text = "Kamera starten"
            statusText.text = "Kamera gestoppt."
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE,
            )
            return
        }
        cameraRequested = true
        if (textureView.isAvailable) openCamera() else {
            statusText.text = "Vorschau wird vorbereitet ..."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            cameraRequested = true
            if (textureView.isAvailable) openCamera()
        } else {
            statusText.text = "Ohne Kamerarecht kann nicht gemessen werden."
        }
    }

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as? CameraManager
            ?: run {
                statusText.text = "Kein Kameradienst."
                return
            }
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            statusText.text = "Keine Kamera gefunden."
            return
        }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val previewSize = chooseSize(characteristics)
        val thread = HandlerThread("arkintr-camera").apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler
        try {
            val surfaceTexture = textureView.surfaceTexture
            if (surfaceTexture == null) {
                statusText.text = "Vorschau nicht bereit - bitte kurz warten."
                return
            }
            surfaceTexture.setDefaultBufferSize(
                previewSize.width, previewSize.height)
            val surface = Surface(surfaceTexture)
            previewSurface = surface
            // The reader is the size of the preview on purpose. A separate
            // reader size would put the tapped corners in one coordinate
            // system and the pixels they refer to in another, and every
            // conversion between the two is a chance to be subtly wrong.
            val imageReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                android.graphics.ImageFormat.YUV_420_888,
                2,
            )
            reader = imageReader
            imageReader.setOnImageAvailableListener({ readerValue ->
                val image = readerValue.acquireLatestImage()
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
                                    buffer.get(source + column * pixelStride)
                            }
                        }
                        latestLuma = compact
                        lumaWidth = width
                        lumaHeight = height
                    } catch (ignored: Exception) {
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
                                object : CameraCaptureSession.StateCallback() {
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
                                                builder.build(), null, handler)
                                            runOnUiThread {
                                                cameraButton.text =
                                                    "Kamera stoppen"
                                                statusText.text =
                                                    "Kamera laeuft. Das " +
                                                        "Brett ganz ins " +
                                                        "Bild halten und die " +
                                                        "vier aeusseren " +
                                                        "Ecken antippen."
                                            }
                                        } catch (error: Exception) {
                                            runOnUiThread {
                                                statusText.text =
                                                    "Vorschau " +
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
                                                "Kamera-Sitzung konnte " +
                                                    "nicht eingerichtet " +
                                                    "werden."
                                        }
                                    }
                                },
                                handler,
                            )
                        } catch (error: Exception) {
                            runOnUiThread {
                                statusText.text =
                                    "Kamera konnte nicht geoeffnet werden: " +
                                        error.message
                            }
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        closeCamera()
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int,
                    ) {
                        closeCamera()
                        runOnUiThread {
                            statusText.text = "Kamerafehler: $error"
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            statusText.text = "Kamera nicht verfuegbar: ${error.message}"
        }
    }

    private fun chooseSize(characteristics: CameraCharacteristics): Size {
        return try {
            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            map?.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { it.width <= 1_920 && it.height <= 1_080 }
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: Size(1_280, 720)
        } catch (ignored: Exception) {
            Size(1_280, 720)
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
        } catch (ignored: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (ignored: Exception) {
        }
        cameraDevice = null
        try {
            reader?.close()
        } catch (ignored: Exception) {
        }
        reader = null
        latestLuma = null
        previewSurface = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        runOnUiThread { cameraButton.text = "Kamera starten" }
    }

    /**
     * Preview transform and its inverse.
     *
     * The same matrix maps the camera buffer onto the preview, so inverting it
     * maps a tapped point back onto buffer pixels exactly, whatever the
     * device's sensor orientation is.
     */
    private fun applyPreviewTransform() {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0 ||
            bufferWidth <= 0 || bufferHeight <= 0
        ) return
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

    /** Maps an on-screen point into camera-buffer pixel coordinates. */
    private fun toBuffer(x: Float, y: Float, out: FloatArray) {
        out[0] = x / textureView.width.toFloat().coerceAtLeast(1f) * lumaWidth
        out[1] = y / textureView.height.toFloat().coerceAtLeast(1f) * lumaHeight
        if (!matrixReady) return
        val mapped = floatArrayOf(x, y)
        inverseMatrix.mapPoints(mapped)
        out[0] = mapped[0] + bufferWidth / 2f
        out[1] = mapped[1] + bufferHeight / 2f
    }

    override fun onDestroy() {
        closeCamera()
        super.onDestroy()
    }

    // --------------------------------------------------------------- file

    /** Stable, non-reversible device label: enough to group, not to identify. */
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
                    Environment.DIRECTORY_DOWNLOADS + "/ARKernelDiagnostics",
                )
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
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: getExternalFilesDir(null)
                    ?: filesDir
                if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                    return "App-Dateien: Verzeichnis nicht verfuegbar"
                }
                val file = java.io.File(dir, fileName)
                file.writeBytes(bytes)
                "${file.absolutePath}"
            } catch (error: Exception) {
                "App-Dateien: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
    }

    /**
     * Four taps, shown numbered so the order is visible rather than assumed,
     * and the found corners drawn on top so the operator can see what was
     * actually measured.
     */
    private inner class BoardOverlayView(
        context: Context,
    ) : View(context) {
        private val points = FloatArray(8)
        private var count = 0
        private var found: List<Pair<Float, Float>> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription =
                "Vier Eckpunkte des Schachbretts, im Uhrzeigersinn"
        }

        fun count(): Int = count

        fun reset() {
            count = 0
            found = emptyList()
            invalidate()
        }

        fun markFound(corners: List<Pair<Float, Float>>) {
            // Found corners live in buffer pixels; the operator sees the
            // preview, so they have to be mapped back the other way.
            val out = FloatArray(2)
            found = corners.map { (x, y) ->
                out[0] = x
                out[1] = y
                if (matrixReady) {
                    val mapped = floatArrayOf(
                        x - bufferWidth / 2f,
                        y - bufferHeight / 2f,
                    )
                    previewMatrix.mapPoints(mapped)
                    Pair(mapped[0], mapped[1])
                } else {
                    Pair(x, y)
                }
            }
            invalidate()
        }

        /** The four taps in camera-buffer pixel coordinates, tap order. */
        fun tapsInBuffer(): List<ArKernelCheckerboard.ArKernelBoardCornerInput> {
            val out = FloatArray(2)
            val result = ArrayList<ArKernelCheckerboard.ArKernelBoardCornerInput>(4)
            for (index in 0 until count) {
                toBuffer(points[index * 2], points[index * 2 + 1], out)
                result += ArKernelCheckerboard.ArKernelBoardCornerInput(
                    out[0].toDouble(), out[1].toDouble())
            }
            return result
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.style = Paint.Style.FILL
            for (corner in found) {
                paint.color = Color.rgb(74, 222, 128)
                canvas.drawCircle(corner.first, corner.second, 3f, paint)
            }
            paint.textSize = 15f
            for (index in 0 until count) {
                paint.color = Color.rgb(56, 189, 248)
                canvas.drawCircle(
                    points[index * 2], points[index * 2 + 1], 13f, paint)
                paint.color = Color.BLACK
                canvas.drawText(
                    (index + 1).toString(),
                    points[index * 2] - 4f,
                    points[index * 2 + 1] + 5f,
                    paint)
            }
            if (count > 1) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.color = Color.argb(160, 56, 189, 248)
                for (index in 1 until count) {
                    canvas.drawLine(
                        points[(index - 1) * 2], points[(index - 1) * 2 + 1],
                        points[index * 2], points[index * 2 + 1], paint)
                }
                if (count == 4) {
                    canvas.drawLine(
                        points[6], points[7], points[0], points[1], paint)
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
                found = emptyList()
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
    }
}
