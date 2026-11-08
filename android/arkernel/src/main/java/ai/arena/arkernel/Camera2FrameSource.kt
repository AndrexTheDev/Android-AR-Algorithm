package ai.arena.arkernel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.util.Range
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import java.util.TreeMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

data class CameraReconstructionFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val i420Bytes: ByteArray,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
)

private data class CameraExposure(val exposureTimeNs: Long, val sensitivityIso: Int)
private data class PendingCameraReconstructionFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val i420Bytes: ByteArray,
    val quality: CameraFrameQuality,
    val emitCaptureFrame: Boolean,
    val emitReconstructionFrame: Boolean,
)

private class CameraCaptureMetadataMatcher(private val capacity: Int = 32) {
    private val values = TreeMap<Long, CameraExposure>()
    init { require(capacity in 2..256) }
    @Synchronized fun put(timestampNs: Long, exposureTimeNs: Long, sensitivityIso: Int) {
        if (timestampNs <= 0 || exposureTimeNs < 0 || sensitivityIso < 0) return
        values[timestampNs] = CameraExposure(exposureTimeNs, sensitivityIso)
        while (values.size > capacity) values.pollFirstEntry()
    }
    @Synchronized fun take(timestampNs: Long): CameraExposure? {
        val match = values.remove(timestampNs)
        while (values.isNotEmpty() && values.firstKey() < timestampNs) values.pollFirstEntry()
        return match
    }
    @Synchronized fun clear() = values.clear()
}

/**
 * Lifecycle owner for a calibrated Camera2 YUV stream. When a visual frontend
 * is attached, the Y plane is consumed synchronously by native code before
 * Image.close(); camera-only capture skips that frontend and copies accepted
 * YUV frames into bounded owned I420 output.
 */
class Camera2FrameSource(
    context: Context,
    private val calibration: CameraCalibration,
    private val frontend: ArKernelVisualFrontend?,
    private val rotationPrediction: (timestampNs: Long) -> Rotation? = { null },
    private val onResult: ((VisualFrontendSnapshot) -> Unit)? = null,
    private val onError: (Throwable) -> Unit = {},
    private val onLifecycleFailure: ((Camera2LifecycleFailure) -> Unit)? = null,
    private val onReconstructionFrame: ((CameraReconstructionFrame) -> Unit)? = null,
    private val onCaptureFrame:
        ((CameraReconstructionFrame, CameraFrameQuality) -> Unit)? = null,
    private val reconstructionQualityGate:
        ((VisualFrontendSnapshot, CameraFrameQuality) -> Boolean)? = null,
    private val compatibilityTimingSink: ((CameraTimingSample) -> Unit)? = null,
    private val onTimestampNormalizationFailure:
        ((CameraTimestampFailureReason) -> Unit)? = null,
    private val calibrationRuntimeMonitor: CameraCalibrationRuntimeMonitor? = null,
    private val onCalibrationRuntimeFailure:
        ((CameraCalibrationRuntimeFailure) -> Unit)? = null,
    private val maximumOutputFramesPerSecond: Int = 30,
    private val maximumPendingFrames: Int = 2,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var running = false
    private var lastTimestampNs = 0L
    private var timestampFailureReported = false
    private var calibrationFailureReported = false
    private var affineSampleCounter = 0L
    private var affineClockAligner: ArKernelClockAligner? = null
    private val affineDriftPolicy = CameraClockDriftPolicy()
    private val timestampNormalizer = CameraTimestampNormalizer()
    private val captureMetadata = CameraCaptureMetadataMatcher()
    private val pendingReconstructionFrames = TreeMap<Long, PendingCameraReconstructionFrame>()
    private val frameRateLimiter = CameraFrameRateLimiter(maximumOutputFramesPerSecond)
    private val lifecycle = Camera2LifecycleStateMachine()

    init {
        require(maximumOutputFramesPerSecond in 1..240)
        require(maximumPendingFrames in 1..8)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(cameraId: String? = null) {
        if (running) return
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "CAMERA permission must be granted by the host app"
        }
        frontend?.reset()
        lastTimestampNs = 0L
        frameRateLimiter.reset()
        timestampFailureReported = false
        calibrationFailureReported = false
        calibrationRuntimeMonitor?.reset()
        affineSampleCounter = 0
        affineClockAligner?.close()
        affineClockAligner = ArKernelClockAligner()
        timestampNormalizer.reset()
        captureMetadata.clear()
        pendingReconstructionFrames.clear()
        val cameraThread = HandlerThread("ARKernel-Camera").also { it.start() }
        val cameraHandler = Handler(cameraThread.looper)
        val imageReader = ImageReader.newInstance(
            calibration.width, calibration.height, ImageFormat.YUV_420_888, 3)
        imageReader.setOnImageAvailableListener({ source -> consumeLatest(source) }, cameraHandler)
        val lifecycleGeneration = lifecycle.beginStart()
        thread = cameraThread; handler = cameraHandler; reader = imageReader; running = true
        try {
            val binding = calibrationRuntimeMonitor?.binding
            val selectedCameraId = binding?.cameraId ?: cameraId ?: selectBackCamera()
            require(cameraId == null || cameraId == selectedCameraId) {
                "Requested camera ID does not match calibration binding"
            }
            val physicalCameraId = binding?.physicalCameraId
            require(physicalCameraId == null || physicalCameraId == selectedCameraId) {
                "Direct Camera2 source cannot silently switch to a different physical camera"
            }
            manager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val transition = lifecycle.deviceOpened(lifecycleGeneration)
                    if (!transition.accepted || !running) { camera.close(); return }
                    device = camera
                    try {
                        createSession(camera, imageReader, cameraHandler, lifecycleGeneration)
                    } catch (error: Throwable) {
                        camera.close()
                        reportLifecycleFailure(lifecycle.fail(lifecycleGeneration,
                            Camera2LifecycleFailure.SESSION_START_FAILED), error)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    val transition = lifecycle.fail(lifecycleGeneration,
                        Camera2LifecycleFailure.DEVICE_DISCONNECTED)
                    camera.close()
                    reportLifecycleFailure(transition,
                        IllegalStateException("Camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    val transition = lifecycle.fail(lifecycleGeneration,
                        Camera2LifecycleFailure.DEVICE_ERROR)
                    camera.close()
                    reportLifecycleFailure(transition,
                        IllegalStateException("Camera2 error $error"))
                }
            }, cameraHandler)
        } catch (error: Throwable) {
            stop(); throw error
        }
    }

    private fun selectBackCamera(): String = manager.cameraIdList.firstOrNull { id ->
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
    } ?: error("No back-facing Camera2 device")

    private fun createSession(camera: CameraDevice, imageReader: ImageReader, handler: Handler,
        lifecycleGeneration: Long) {
        camera.createCaptureSession(listOf(imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    val transition = lifecycle.sessionConfigured(lifecycleGeneration)
                    if (!transition.accepted || !running) { captureSession.close(); return }
                    try {
                        session = captureSession
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        selectBoundedFpsRange(selectedCameraRanges(camera.id),
                            maximumOutputFramesPerSecond)?.let {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                        }
                        val binding = calibrationRuntimeMonitor?.binding
                        if (binding == null) set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        else {
                            set(CaptureRequest.SCALER_CROP_REGION, Rect(binding.cropLeft,
                                binding.cropTop, binding.cropLeft + binding.cropWidth,
                                binding.cropTop + binding.cropHeight))
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                binding.videoStabilizationMode)
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                binding.opticalStabilizationMode)
                            if (binding.expectedFocusDistanceDiopters != null) {
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                set(CaptureRequest.LENS_FOCUS_DISTANCE,
                                    binding.expectedFocusDistanceDiopters.toFloat())
                            } else set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            if (Build.VERSION.SDK_INT >= 28 && binding.distortionCorrectionMode >= 0)
                                set(CaptureRequest.DISTORTION_CORRECTION_MODE,
                                    binding.distortionCorrectionMode)
                        }
                    }.build()
                    captureSession.setRepeatingRequest(request,
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult,
                            ) {
                                if (!monitorCalibration(result)) return
                                val rawTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                                emitCompatibilityTiming(CameraTimingSampleKind.CAPTURE_RESULT,
                                    rawTimestamp)
                                val timestamp = timestampNormalizer.normalize(rawTimestamp)
                                    ?.normalizedTimestampNs ?: return
                                handleCaptureMetadata(timestamp,
                                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                                    result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0)
                            }
                        }, handler)
                    } catch (error: Throwable) {
                        captureSession.close()
                        reportLifecycleFailure(lifecycle.fail(lifecycleGeneration,
                            Camera2LifecycleFailure.SESSION_START_FAILED), error)
                    }
                }
                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    val transition = lifecycle.fail(lifecycleGeneration,
                        Camera2LifecycleFailure.SESSION_CONFIGURE_FAILED)
                    captureSession.close()
                    reportLifecycleFailure(transition,
                        IllegalStateException("Camera capture session failed"))
                }
            }, handler)
    }

    private fun selectedCameraRanges(cameraId: String): Array<out Range<Int>> =
        manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()

    private fun selectBoundedFpsRange(
        ranges: Array<out Range<Int>>,
        maximumFramesPerSecond: Int,
    ): Range<Int>? = ranges
        .filter { it.upper <= maximumFramesPerSecond }
        .maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

    private fun monitorCalibration(result: TotalCaptureResult): Boolean {
        val monitor = calibrationRuntimeMonitor ?: return true
        if (calibrationFailureReported) return false
        val crop = result.get(CaptureResult.SCALER_CROP_REGION)
        val failure = monitor.observe(CameraCalibrationCaptureState(
            crop?.left, crop?.top, crop?.width(), crop?.height(),
            result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
            result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
            if (Build.VERSION.SDK_INT >= 28)
                result.get(CaptureResult.DISTORTION_CORRECTION_MODE) else null,
            result.get(CaptureResult.LENS_FOCAL_LENGTH)?.toDouble(),
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.toDouble()))
        if (failure == null) return true
        calibrationFailureReported = true
        onCalibrationRuntimeFailure?.invoke(failure)
        return false
    }

    private fun handleCaptureMetadata(timestampNs: Long, exposureTimeNs: Long, sensitivityIso: Int) {
        captureMetadata.put(timestampNs, exposureTimeNs, sensitivityIso)
        val pending = pendingReconstructionFrames.remove(timestampNs) ?: return
        val exposure = captureMetadata.take(timestampNs) ?: return
        try {
            if (running) {
                val frame = CameraReconstructionFrame(timestampNs, pending.width, pending.height,
                    pending.i420Bytes, exposure.exposureTimeNs, exposure.sensitivityIso)
                if (pending.emitCaptureFrame) onCaptureFrame?.invoke(frame, pending.quality)
                if (pending.emitReconstructionFrame) onReconstructionFrame?.invoke(frame)
            }
        } catch (error: Throwable) {
            if (running) onError(error)
        }
    }

    @Synchronized
    private fun consumeLatest(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            if (!running || timestampFailureReported || calibrationFailureReported) return
            val timing = emitCompatibilityTiming(CameraTimingSampleKind.IMAGE, image.timestamp)
            affineSampleCounter++
            if (affineSampleCounter % AFFINE_SAMPLE_INTERVAL == 0L) {
                val aligner = affineClockAligner
                if (aligner != null && aligner.addObservation(image.timestamp,
                        timing.callbackElapsedRealtimeNs)) {
                    when (aligner.alignment()?.let(affineDriftPolicy::evaluate)) {
                        CameraClockDriftFailureReason.DRIFT_EXCEEDED -> {
                            reportTimestampFailure(CameraTimestampFailureReason.AFFINE_DRIFT_EXCEEDED)
                            return
                        }
                        CameraClockDriftFailureReason.RESIDUAL_EXCEEDED -> {
                            reportTimestampFailure(CameraTimestampFailureReason.AFFINE_RESIDUAL_EXCEEDED)
                            return
                        }
                        null -> Unit
                    }
                }
            }
            val normalization = timestampNormalizer.observeAndNormalize(image.timestamp,
                timing.callbackElapsedRealtimeNs, timing.callbackUptimeNs)
            if (normalization == null) {
                val failure = timestampNormalizer.failureReason
                if (failure != null) reportTimestampFailure(failure)
                return
            }
            val timestamp = normalization.normalizedTimestampNs
            if (timestamp <= lastTimestampNs) return
            lastTimestampNs = timestamp
            if (!frameRateLimiter.accept(timestamp)) return
            val y = image.planes[0]
            check(y.pixelStride == 1) { "Unsupported Camera2 Y-plane pixel stride ${y.pixelStride}" }
            val snapshot = frontend?.processGray(
                y.buffer, timestamp, y.rowStride, rotationPrediction(timestamp))
            if (snapshot != null) onResult?.invoke(snapshot)
            val emitCapture = onCaptureFrame != null
            val quality = if (emitCapture || (snapshot?.keyframe == true &&
                    onReconstructionFrame != null))
                CameraFrameQualityEvaluator.evaluate(y.buffer, image.width, image.height,
                    y.rowStride, y.pixelStride) else null
            val emitReconstruction = quality != null && snapshot != null && snapshot.keyframe &&
                onReconstructionFrame != null &&
                reconstructionQualityGate?.invoke(snapshot, quality) != false
            if (quality != null && (emitCapture || emitReconstruction)) {
                val exposure = captureMetadata.take(timestamp)
                val planes = image.planes
                check(planes.size == 3) { "YUV_420_888 image must expose three planes" }
                val i420 = ReconstructionI420Packer.pack(image.width, image.height,
                    ReconstructionYuvPlane(planes[0].buffer, planes[0].rowStride,
                        planes[0].pixelStride),
                    ReconstructionYuvPlane(planes[1].buffer, planes[1].rowStride,
                        planes[1].pixelStride),
                    ReconstructionYuvPlane(planes[2].buffer, planes[2].rowStride,
                        planes[2].pixelStride))
                if (exposure != null) {
                    val frame = CameraReconstructionFrame(timestamp, image.width, image.height,
                        i420, exposure.exposureTimeNs, exposure.sensitivityIso)
                    if (emitCapture) onCaptureFrame?.invoke(frame, quality)
                    if (emitReconstruction) onReconstructionFrame?.invoke(frame)
                } else {
                    pendingReconstructionFrames[timestamp] = PendingCameraReconstructionFrame(
                        timestamp, image.width, image.height, i420, quality,
                        emitCapture, emitReconstruction)
                    // Captured image data is already owned, but memory remains strictly bounded.
                    while (pendingReconstructionFrames.size > maximumPendingFrames)
                        pendingReconstructionFrames.pollFirstEntry()
                }
            }
        } catch (error: Throwable) {
            if (running) onError(error)
        } finally {
            image.close()
        }
    }

    private fun reportTimestampFailure(reason: CameraTimestampFailureReason) {
        if (timestampFailureReported) return
        timestampFailureReported = true
        onTimestampNormalizationFailure?.invoke(reason)
    }

    private fun emitCompatibilityTiming(kind: CameraTimingSampleKind,
        timestampNs: Long): CameraTimingSample {
        val sample = CameraTimingSample(kind, timestampNs,
            SystemClock.elapsedRealtimeNanos(),
            Math.multiplyExact(SystemClock.uptimeMillis(), 1_000_000L))
        val sink = compatibilityTimingSink ?: return sample
        try {
            sink(sample)
        } catch (error: Throwable) {
            if (running) onError(error)
        }
        return sample
    }

    private fun reportLifecycleFailure(transition: Camera2LifecycleTransition,
        error: Throwable) {
        if (!transition.shouldReportFailure) return
        stop()
        transition.failure?.let { failure -> runCatching { onLifecycleFailure?.invoke(failure) } }
        onError(error)
    }

    @Synchronized
    fun stop() {
        running = false
        lifecycle.stop()
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        handler = null
        thread?.quitSafely(); thread = null
        lastTimestampNs = 0L
        frameRateLimiter.reset()
        timestampFailureReported = false
        calibrationFailureReported = false
        calibrationRuntimeMonitor?.reset()
        affineSampleCounter = 0
        affineClockAligner?.close()
        affineClockAligner = null
        timestampNormalizer.reset()
        captureMetadata.clear()
        pendingReconstructionFrames.clear()
        frontend?.reset()
    }

    override fun close() = stop()

    private companion object {
        const val AFFINE_SAMPLE_INTERVAL = 15L
    }
}
