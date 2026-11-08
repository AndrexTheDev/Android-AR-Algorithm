package ai.arena.arkernel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import java.util.TreeMap
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
    val timestampNs: Long, val width: Int, val height: Int, val i420Bytes: ByteArray)

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
 * Lifecycle owner for a calibrated Camera2 YUV stream. The Y plane is consumed
 * synchronously by native code before Image.close(), so no dangling buffer can
 * cross the JNI boundary.
 */
class Camera2FrameSource(
    context: Context,
    private val calibration: CameraCalibration,
    private val frontend: ArKernelVisualFrontend,
    private val rotationPrediction: (timestampNs: Long) -> Rotation? = { null },
    private val onResult: (VisualFrontendSnapshot) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val onReconstructionFrame: ((CameraReconstructionFrame) -> Unit)? = null,
    private val reconstructionQualityGate:
        ((VisualFrontendSnapshot, CameraFrameQuality) -> Boolean)? = null,
    private val compatibilityTimingSink: ((CameraTimingSample) -> Unit)? = null,
    private val onTimestampNormalizationFailure:
        ((CameraTimestampFailureReason) -> Unit)? = null,
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
    private val timestampNormalizer = CameraTimestampNormalizer()
    private val captureMetadata = CameraCaptureMetadataMatcher()
    private val pendingReconstructionFrames = TreeMap<Long, PendingCameraReconstructionFrame>()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(cameraId: String? = null) {
        if (running) return
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "CAMERA permission must be granted by the host app"
        }
        frontend.reset()
        lastTimestampNs = 0L
        timestampFailureReported = false
        timestampNormalizer.reset()
        captureMetadata.clear()
        pendingReconstructionFrames.clear()
        val cameraThread = HandlerThread("ARKernel-Camera").also { it.start() }
        val cameraHandler = Handler(cameraThread.looper)
        val imageReader = ImageReader.newInstance(
            calibration.width, calibration.height, ImageFormat.YUV_420_888, 3)
        imageReader.setOnImageAvailableListener({ source -> consumeLatest(source) }, cameraHandler)
        thread = cameraThread; handler = cameraHandler; reader = imageReader; running = true
        try {
            manager.openCamera(cameraId ?: selectBackCamera(), object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running) { camera.close(); return }
                    device = camera
                    createSession(camera, imageReader, cameraHandler)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); if (running) onError(IllegalStateException("Camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); if (running) onError(IllegalStateException("Camera2 error $error"))
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

    private fun createSession(camera: CameraDevice, imageReader: ImageReader, handler: Handler) {
        camera.createCaptureSession(listOf(imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    if (!running) { captureSession.close(); return }
                    session = captureSession
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    captureSession.setRepeatingRequest(request,
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult,
                            ) {
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
                }
                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    captureSession.close()
                    if (running) onError(IllegalStateException("Camera capture session failed"))
                }
            }, handler)
    }

    private fun handleCaptureMetadata(timestampNs: Long, exposureTimeNs: Long, sensitivityIso: Int) {
        captureMetadata.put(timestampNs, exposureTimeNs, sensitivityIso)
        val pending = pendingReconstructionFrames.remove(timestampNs) ?: return
        val exposure = captureMetadata.take(timestampNs) ?: return
        try {
            if (running) onReconstructionFrame?.invoke(CameraReconstructionFrame(timestampNs,
                pending.width, pending.height, pending.i420Bytes,
                exposure.exposureTimeNs, exposure.sensitivityIso))
        } catch (error: Throwable) {
            if (running) onError(error)
        }
    }

    private fun consumeLatest(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            if (!running) return
            val timing = emitCompatibilityTiming(CameraTimingSampleKind.IMAGE, image.timestamp)
            val normalization = timestampNormalizer.observeAndNormalize(image.timestamp,
                timing.callbackElapsedRealtimeNs, timing.callbackUptimeNs)
            if (normalization == null) {
                val failure = timestampNormalizer.failureReason
                if (failure != null && !timestampFailureReported) {
                    timestampFailureReported = true
                    onTimestampNormalizationFailure?.invoke(failure)
                }
                return
            }
            val timestamp = normalization.normalizedTimestampNs
            if (timestamp <= lastTimestampNs) return
            val y = image.planes[0]
            check(y.pixelStride == 1) { "Unsupported Camera2 Y-plane pixel stride ${y.pixelStride}" }
            lastTimestampNs = timestamp
            val snapshot = frontend.processGray(
                y.buffer, timestamp, y.rowStride, rotationPrediction(timestamp))
            onResult(snapshot)
            val reconstructionCallback = onReconstructionFrame
            if (snapshot.keyframe && reconstructionCallback != null) {
                val quality = CameraFrameQualityEvaluator.evaluate(y.buffer, image.width, image.height,
                    y.rowStride, y.pixelStride)
                if (reconstructionQualityGate?.invoke(snapshot, quality) == false) return
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
                if (exposure != null) reconstructionCallback(CameraReconstructionFrame(
                    timestamp, image.width, image.height, i420,
                    exposure.exposureTimeNs, exposure.sensitivityIso))
                else {
                    pendingReconstructionFrames[timestamp] = PendingCameraReconstructionFrame(
                        timestamp, image.width, image.height, i420)
                    // Captured image data is already owned, but memory remains strictly bounded.
                    while (pendingReconstructionFrames.size > MAX_PENDING_RECONSTRUCTION_FRAMES)
                        pendingReconstructionFrames.pollFirstEntry()
                }
            }
        } catch (error: Throwable) {
            if (running) onError(error)
        } finally {
            image.close()
        }
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

    @Synchronized
    fun stop() {
        running = false
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        handler = null
        thread?.quitSafely(); thread = null
        lastTimestampNs = 0L
        timestampFailureReported = false
        timestampNormalizer.reset()
        captureMetadata.clear()
        pendingReconstructionFrames.clear()
        frontend.reset()
    }

    override fun close() = stop()

    private companion object { const val MAX_PENDING_RECONSTRUCTION_FRAMES = 2 }
}
