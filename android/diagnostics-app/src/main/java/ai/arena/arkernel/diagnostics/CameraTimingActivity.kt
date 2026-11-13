package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelCameraFacing
import ai.arena.arkernel.ArKernelCameraFrameRow
import ai.arena.arkernel.ArKernelCameraLevel
import ai.arena.arkernel.ArKernelCameraTiming
import ai.arena.arkernel.ArKernelCameraTimingAdapter
import ai.arena.arkernel.CameraCalibrationCaptureState
import ai.arena.arkernel.CameraCalibrationRuntimeFailure
import ai.arena.arkernel.CameraCalibrationRuntimeMonitor
import ai.arena.arkernel.CalibrationStreamBinding
import ai.arena.arkernel.CameraTimestampNormalizer
import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
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
 * Measures the three things about a camera that decide whether a
 * visual-inertial kernel can fuse its frames at all.
 *
 * **Cadence.** Does the stream deliver the rate it promises, or does it
 * arrive with holes? A hole is a stretch the kernel has to bridge by
 * integrating the gyroscope instead of seeing.
 *
 * **Clock domain.** Which Android clock are the frame timestamps on? A frame
 * stamped in uptime and an inertial sample stamped in elapsedRealtime differ
 * by every second the device spent in deep sleep - orders of magnitude more
 * than an exposure. This screen decides the domain by observation
 * ([CameraTimestampNormalizer]) rather than assuming it, because assuming it
 * wrong is the kind of error that produces a plausible-looking trajectory
 * that is quietly wrong.
 *
 * **Exposure and stability.** A long exposure smears a moving camera, and a
 * capture pipeline that changes its crop or its stabilisation mode mid-run
 * invalidates every number collected during that run.
 *
 * A preview surface is required to run a capture session, but no frame is
 * read, decoded, stored or displayed: the surface exists because Camera2
 * demands a target, and nothing is ever taken from it. No intrinsics are
 * fitted and the manufacturer's focal length is recorded as a reported value,
 * not trusted - `manufacturer_intrinsics_trusted` is written as `false`.
 */
class CameraTimingActivity : DiagnosticsActivity() {

    private companion object {
        const val REQUEST_CAMERA = 1081
        const val STATE_REPORT = "camtim.report"
        const val STATE_STATUS = "camtim.status"
        const val STATE_TEXT = "camtim.text"
        const val STATE_OPT_IN = "camtim.optin"
        const val STATE_CAMERA = "camtim.camera"
        const val STATE_SECONDS = "camtim.seconds"

        /** The contract's row ceiling; capture stops when it is reached. */
        const val MAX_FRAMES = 200

        /** Below eight frames the clock domain cannot be locked at all. */
        const val MIN_FRAMES = 8

        val DURATIONS = listOf(3, 6, 10)
    }

    /** One frame as delivered, before anything is derived from it. */
    private data class Frame(
        val timestampNs: Long,
        val exposureNs: Long,
        val sensitivityIso: Long,
        val focusDiopters: Double,
        val focalLengthMm: Double,
        val rollingShutterSkewNs: Long?,
        val crop: android.graphics.Rect?,
        val videoStabilization: Int?,
        val opticalStabilization: Int?,
    )

    private data class CameraOption(
        val id: String,
        val label: String,
        val level: ArKernelCameraLevel,
        val facing: ArKernelCameraFacing,
        val physicalIds: String,
        val orientation: Int,
        val width: Int,
        val height: Int,
    )

    private lateinit var optInBox: CheckBox
    private lateinit var cameraSpinner: Spinner
    private lateinit var durationSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var reportText: TextView
    private lateinit var statusText: TextView

    private var cameraThread: HandlerThread? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null

    private var options: List<CameraOption> = emptyList()
    private var frames: ArrayList<Frame> = ArrayList()
    private var normalizer = CameraTimestampNormalizer()
    private var stabilityFailure: CameraCalibrationRuntimeFailure? = null
    private var stabilityChecked = false
    private var lastText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        root.addView(TextView(this).apply {
            text = "Kamera-Takt & Fähigkeiten (ARKCAMT1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für Kameratakt und Fähigkeiten"
        })
        root.addView(TextView(this).apply {
            text = "Version 2.20-camtim"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        })
        root.addView(TextView(this).apply {
            text = "1) Kamera wählen und Dauer einstellen. " +
                "2) Opt-in anhaken. 3) „Aufnahme starten“ – " +
                "das Gerät ruhig halten oder gleichmäßig " +
                "schwenken, die App zählt die gelieferten " +
                "Bilder. 4) „Datei speichern“ und selbst " +
                "hochladen."
            textSize = 13f
            contentDescription = "Bedienreihenfolge des Kameratakts"
        })
        root.addView(TextView(this).apply {
            text = "Die Kamera läuft, aber **kein Bild wird " +
                "behalten**: die Vorschaufläche existiert nur, " +
                "weil Camera2 ein Ziel verlangt. Es werden " +
                "keine Intrinsics gerechnet, und die " +
                "Brennweite des Herstellers wird als " +
                "gemeldeter Wert notiert, nicht geglaubt."
            textSize = 12f
            setTextColor(Color.parseColor("#B45309"))
            setPadding(0, 8, 0, 0)
            contentDescription = "Hinweis, dass keine Bilder behalten werden"
        })

        cameraSpinner = Spinner(this)
        root.addView(labelled("Kamera", cameraSpinner))
        durationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CameraTimingActivity,
                android.R.layout.simple_spinner_dropdown_item,
                DURATIONS.map { "$it Sekunden" },
            )
        }
        root.addView(labelled("Dauer", durationSpinner))

        optInBox = CheckBox(this).apply {
            text = "Opt-in: Kamera für diese Messung öffnen"
            textSize = 14f
            contentDescription = "Zustimmung zum Öffnen der Kamera"
        }
        root.addView(optInBox, fullWidthParams())

        startButton = Button(this).apply {
            text = "Aufnahme starten"
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
            text = "Noch keine Aufnahme ausgewertet."
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

        options = enumerateCameras()
        cameraSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { it.label },
        )

        restore(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_REPORT, reportText.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
        outState.putString(STATE_TEXT, lastText)
        outState.putBoolean(STATE_OPT_IN, optInBox.isChecked)
        outState.putInt(STATE_CAMERA, cameraSpinner.selectedItemPosition)
        outState.putInt(STATE_SECONDS, durationSpinner.selectedItemPosition)
    }

    private fun restore(state: Bundle?) {
        if (state == null) return
        reportText.text = state.getString(STATE_REPORT) ?: ""
        statusText.text = state.getString(STATE_STATUS) ?: ""
        lastText = state.getString(STATE_TEXT) ?: ""
        optInBox.isChecked = state.getBoolean(STATE_OPT_IN)
        val camera = state.getInt(STATE_CAMERA, 0)
        if (camera < options.size) cameraSpinner.setSelection(camera)
        durationSpinner.setSelection(state.getInt(STATE_SECONDS, 0))
        saveButton.isEnabled = lastText.isNotEmpty()
        shareButton.isEnabled = lastText.isNotEmpty()
    }

    override fun onDestroy() {
        stopCamera()
        super.onDestroy()
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
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            openAndCapture()
        } else {
            statusText.text = "Kameraberechtigung verweigert – " +
                "keine Messung. Qualität: unverified."
        }
    }

    // ------------------------------------------------------------ inventory

    private fun enumerateCameras(): List<CameraOption> {
        val manager = getSystemService(CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val found = ArrayList<CameraOption>()
        for (id in manager.cameraIdList) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (error: Exception) {
                continue
            }
            val level = when (
                chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
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
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> ArKernelCameraFacing.BACK
                CameraMetadata.LENS_FACING_FRONT -> ArKernelCameraFacing.FRONT
                CameraMetadata.LENS_FACING_EXTERNAL ->
                    ArKernelCameraFacing.EXTERNAL
                else -> ArKernelCameraFacing.UNKNOWN
            }
            val physical = physicalIds(chars)
            val orientation = chars.get(
                CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val size = preferredSize(chars)
            found.add(
                CameraOption(
                    id = id,
                    label = "$id · ${level.token} · ${facing.token} · " +
                        "${size.width}x${size.height}",
                    level = level,
                    facing = facing,
                    physicalIds = physical,
                    orientation = orientation,
                    width = size.width,
                    height = size.height,
                ))
        }
        return found
    }

    private fun physicalIds(chars: CameraCharacteristics): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "none"
        return try {
            val ids = chars.physicalCameraIds
            if (ids.isEmpty()) "none" else ids.sorted().joinToString(",")
        } catch (error: Exception) {
            "none"
        }
    }

    private fun preferredSize(chars: CameraCharacteristics): android.util.Size {
        val map = chars.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
        val chosen = sizes?.filter {
            it.width <= 1_920 && it.height <= 1_080
        }?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        return chosen ?: android.util.Size(1_280, 720)
    }

    // --------------------------------------------------------------- capture

    private fun startRun() {
        if (!optInBox.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        val option = options.getOrNull(cameraSpinner.selectedItemPosition)
        if (option == null) {
            statusText.text = "Keine Kamera verfügbar."
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA)
            return
        }
        openAndCapture()
    }

    private fun openAndCapture() {
        val option = options.getOrNull(cameraSpinner.selectedItemPosition)
            ?: return
        val manager = getSystemService(CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            statusText.text = "Kein Kamera-Manager verfügbar."
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        frames = ArrayList()
        normalizer = CameraTimestampNormalizer()
        stabilityFailure = null
        stabilityChecked = false
        startButton.isEnabled = false
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        lastText = ""
        statusText.text = "Kamera wird geöffnet …"

        val thread = HandlerThread("arkcamtim").apply { start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        val texture = SurfaceTexture(1)
        texture.setDefaultBufferSize(option.width, option.height)
        surfaceTexture = texture
        val surface = Surface(texture)
        previewSurface = surface
        val stopAt = SystemClock.elapsedRealtime() +
            DURATIONS[durationSpinner.selectedItemPosition] * 1_000L

        try {
            manager.openCamera(option.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val builder = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(surface)
                        builder.set(
                            CaptureRequest.CONTROL_MODE,
                            CameraMetadata.CONTROL_MODE_AUTO)
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(
                                    session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        session.setRepeatingRequest(
                                            builder.build(),
                                            frameCallback(option, stopAt),
                                            handler)
                                        runOnUiThread {
                                            statusText.text =
                                                "Aufnahme läuft …"
                                        }
                                    } catch (error: Exception) {
                                        finishRun(
                                            option,
                                            "Wiederholte Anfrage " +
                                                "fehlgeschlagen: " +
                                                error.message)
                                    }
                                }

                                override fun onConfigureFailed(
                                    session: CameraCaptureSession) {
                                    finishRun(
                                        option,
                                        "Capture-Session konnte nicht " +
                                            "konfiguriert werden")
                                }
                            },
                            handler)
                    } catch (error: Exception) {
                        finishRun(option, "Aufbau fehlgeschlagen: " +
                            error.message)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    finishRun(option, "Kamera getrennt")
                }

                override fun onError(
                    camera: CameraDevice,
                    error: Int,
                ) {
                    finishRun(option, "Kamera-Fehler $error")
                }
            }, handler)
        } catch (error: Exception) {
            finishRun(option, "Öffnen fehlgeschlagen: ${error.message}")
        }
    }

    private fun frameCallback(
        option: CameraOption,
        stopAtMs: Long,
    ): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    ?: return
                val elapsed = SystemClock.elapsedRealtimeNanos()
                val uptime = SystemClock.uptimeMillis() * 1_000_000L
                if (timestamp <= 0L || elapsed <= 0L || uptime <= 0L) return
                normalizer.observeAndNormalize(timestamp, elapsed, uptime)

                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    ?: 0L
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    ?.toLong() ?: 0L
                val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    ?.toDouble() ?: -1.0
                val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH)
                    ?.toDouble() ?: -1.0
                val skew = rollingShutterSkew(result)
                val crop = result.get(CaptureResult.SCALER_CROP_REGION)
                val video = result.get(
                    CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                val optical = opticalStabilization(result)

                synchronized(frames) {
                    frames.add(
                        Frame(
                            timestampNs = timestamp,
                            exposureNs = exposure,
                            sensitivityIso = iso,
                            focusDiopters = focus,
                            focalLengthMm = focal,
                            rollingShutterSkewNs = skew,
                            crop = crop,
                            videoStabilization = video,
                            opticalStabilization = optical,
                        ))
                    val count = frames.size
                    if (count == 1) installStabilityBaseline(frames[0], option)
                    else observeStability(frames[count - 1])
                    if (count >= MAX_FRAMES ||
                        SystemClock.elapsedRealtime() >= stopAtMs
                    ) {
                        runOnUiThread { finishRun(option, null) }
                    } else if (count % 15 == 0) {
                        runOnUiThread {
                            statusText.text = "$count Bilder gezählt …"
                        }
                    }
                }
            }
        }

    private fun rollingShutterSkew(result: TotalCaptureResult): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
        } catch (error: Exception) {
            null
        }
    }

    private fun opticalStabilization(result: TotalCaptureResult): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Builds the stability binding from the first frame so that the monitor
     * reports a *change* rather than a missing expectation.
     *
     * Only when the first frame reports all three fields can a change be
     * detected at all. On a camera that never reports its stabilisation mode
     * the monitor would report MISSING on the very first frame, which is not
     * instability - it is an untestable camera - so it is left unmonitored
     * and the screen says so instead.
     */
    private fun installStabilityBaseline(frame: Frame, option: CameraOption) {
        val crop = frame.crop
        if (crop == null || frame.videoStabilization == null ||
            frame.opticalStabilization == null
        ) {
            stabilityChecked = false
            return
        }
        stabilityChecked = true
        monitor = CameraCalibrationRuntimeMonitor(
            CalibrationStreamBinding(
                cameraId = option.id,
                physicalCameraId = null,
                width = option.width,
                height = option.height,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
                videoStabilizationMode = frame.videoStabilization,
                opticalStabilizationMode = frame.opticalStabilization,
                distortionCorrectionMode = -1,
            ))
    }

    private var monitor: CameraCalibrationRuntimeMonitor? = null

    private fun observeStability(frame: Frame) {
        val active = monitor ?: return
        val crop = frame.crop ?: return
        active.observe(
            CameraCalibrationCaptureState(
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
                videoStabilizationMode = frame.videoStabilization,
                opticalStabilizationMode = frame.opticalStabilization,
                distortionCorrectionMode = null,
                focalLengthMm = frame.focalLengthMm,
                focusDistanceDiopters = frame.focusDiopters,
                rollingShutterSkewNs = frame.rollingShutterSkewNs,
            ))
    }

    // ------------------------------------------------------------- reporting

    private fun finishRun(option: CameraOption, failure: String?) {
        val captured = synchronized(frames) { ArrayList(frames) }
        stopCamera()
        startButton.isEnabled = true
        if (failure != null) {
            statusText.text = failure
            return
        }
        if (captured.size < MIN_FRAMES) {
            statusText.text = "Nur ${captured.size} Bilder gekommen – " +
                "mindestens $MIN_FRAMES nötig, sonst lässt sich die " +
                "Zeitbasis nicht festlegen."
            return
        }
        val rows = ArrayList<ArKernelCameraFrameRow>(captured.size)
        var previous = captured[0].timestampNs
        captured.forEachIndexed { index, frame ->
            val interval = if (index == 0) 0L
            else frame.timestampNs - previous
            previous = frame.timestampNs
            rows.add(
                ArKernelCameraFrameRow(
                    index = index,
                    timestampNs = frame.timestampNs,
                    intervalNs = interval,
                    exposureNs = frame.exposureNs,
                    sensitivityIso = frame.sensitivityIso,
                    focusDistanceDiopters = frame.focusDiopters,
                ))
        }
        val last = captured.last()
        val record = try {
            ArKernelCameraTiming(
                runId = "camtim.${System.currentTimeMillis()}",
                deviceToken = opaqueDeviceToken(),
                cameraId = option.id,
                cameraLevel = option.level,
                cameraFacing = option.facing,
                physicalCameraIds = option.physicalIds,
                sensorOrientationDeg = option.orientation,
                width = option.width,
                height = option.height,
                timestampDomain = normalizer.domain,
                domainOffsetNs = normalizer.offsetNs,
                timestampFailure = normalizer.failureReason,
                focusDistanceDiopters = last.focusDiopters,
                focalLengthMm = last.focalLengthMm,
                rollingShutterSkewNs =
                    last.rollingShutterSkewNs ?: -1L,
                captureFailure = monitor?.failure,
                rows = rows,
            )
        } catch (error: IllegalArgumentException) {
            statusText.text = "Messung nicht verwertbar: ${error.message}"
            return
        }
        lastText = ArKernelCameraTimingAdapter.encode(record)
        val parsed = ArKernelCameraTimingAdapter.parse(lastText)
        saveButton.isEnabled = parsed.accepted
        shareButton.isEnabled = parsed.accepted
        reportText.text = render(record, option, stabilityChecked)
        statusText.text = if (parsed.accepted) {
            "${rows.size} Bilder ausgewertet."
        } else {
            "WARNUNG: Rücklesen scheitert – ${parsed.detail}"
        }
    }

    private fun render(
        record: ArKernelCameraTiming,
        option: CameraOption,
        stabilityVerified: Boolean,
    ): String {
        val lines = ArrayList<String>()
        lines.add("Kamera ${option.id} · ${option.level.token} · " +
            "${option.facing.token} · ${option.width}x${option.height}")
        lines.add("Physische IDs: ${option.physicalIds} · " +
            "Sensorlage ${option.orientation}°")
        lines.add("")
        lines.add("Zeitbasis: ${record.timestampDomain?.name ?: "UNBEKANNT"}")
        lines.add("  Abweichung ${record.domainOffsetNs / 1_000_000L} ms")
        record.timestampFailure?.let {
            lines.add("  Widerspruch: $it")
        }
        lines.add("")
        val fps = if (record.intervalMedianNs > 0) {
            1_000_000_000.0 / record.intervalMedianNs.toDouble()
        } else {
            0.0
        }
        lines.add("Takt: ${String.format(Locale.US, "%.1f", fps)} B/s " +
            "(median ${record.intervalMedianNs / 1_000_000L} ms)")
        lines.add("  Jitter ${record.intervalJitterNs / 1_000_000L} ms · " +
            "größte Lücke ${record.intervalMaxGapNs / 1_000_000L} ms")
        lines.add("")
        lines.add("Belichtung: ${record.exposureMinNs / 1_000_000L}/" +
            "${record.exposureMedianNs / 1_000_000L}/" +
            "${record.exposureMaxNs / 1_000_000L} ms (min/median/max)")
        lines.add("  Empfindlichkeit ${record.sensitivityMedian} ISO")
        lines.add("  Schmierung bei 90°/s: " +
            "${String.format(Locale.US, "%.2f",
                record.smearDegPer90DegPerSec)}°")
        lines.add("")
        if (stabilityVerified) {
            val failure = record.captureFailure
            lines.add("Erfassung stabil: " +
                if (failure == null) "ja" else "NEIN ($failure)")
        } else {
            lines.add("Erfassung stabil: nicht prüfbar – die Kamera " +
                "meldet ihren Stabilisierungs-Modus nicht")
        }
        lines.add("Rolling-Shutter: " +
            if (record.rollingShutterSkewNs < 0) "nicht gemeldet"
            else "${record.rollingShutterSkewNs / 1_000L} µs")
        lines.add("")
        lines.add("Urteile")
        lines.add("  genug Bilder        ${mark(record.framesOk)}")
        lines.add("  Takt ohne Löcher    ${mark(record.cadenceOk)}")
        lines.add("  Zeitbasis erkannt   ${mark(record.timestampDomainOk)}")
        lines.add("  Belichtung kurz     ${mark(record.exposureOk)}")
        lines.add("  Erfassung stabil    ${mark(record.captureStableOk)}")
        lines.add("  insgesamt           ${mark(record.cameraSufficient)}")
        val steps = record.outstandingSteps()
        if (steps.isNotEmpty()) {
            lines.add("  offen: ${steps.joinToString(", ")}")
        }
        lines.add("")
        lines.add("Qualität: ${ArKernelCameraTimingAdapter
            .report(record).qualityGrade}")
        return lines.joinToString("\n")
    }

    private fun mark(value: Boolean): String =
        if (value) "ok" else "NEIN"

    // -------------------------------------------------------------- plumbing

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
            surfaceTexture?.release()
        } catch (error: Exception) {
        }
        try {
            cameraThread?.quitSafely()
        } catch (error: Exception) {
        }
        captureSession = null
        cameraDevice = null
        previewSurface = null
        surfaceTexture = null
        cameraThread = null
        monitor = null
    }

    private fun save() {
        if (lastText.isEmpty()) return
        val name = "ARKCAMT1_${System.currentTimeMillis()}.txt"
        val path = writeTextToDownloads(name, lastText)
        statusText.text = path
        Toast.makeText(this, "Kameratakt gespeichert", Toast.LENGTH_SHORT)
            .show()
    }

    private fun share() {
        if (lastText.isEmpty()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKCAMT1")
                putExtra(Intent.EXTRA_TEXT, lastText)
            }
            startActivity(Intent.createChooser(send, "ARKCAMT1 teilen"))
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
            addView(TextView(this@CameraTimingActivity).apply {
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
