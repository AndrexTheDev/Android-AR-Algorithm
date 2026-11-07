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
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

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

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(cameraId: String? = null) {
        if (running) return
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "CAMERA permission must be granted by the host app"
        }
        frontend.reset()
        lastTimestampNs = 0L
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
                    captureSession.setRepeatingRequest(request, null, handler)
                }
                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    captureSession.close()
                    if (running) onError(IllegalStateException("Camera capture session failed"))
                }
            }, handler)
    }

    private fun consumeLatest(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            if (!running || image.timestamp <= lastTimestampNs) return
            val y = image.planes[0]
            check(y.pixelStride == 1) { "Unsupported Camera2 Y-plane pixel stride ${y.pixelStride}" }
            lastTimestampNs = image.timestamp
            onResult(frontend.processGray(
                y.buffer, image.timestamp, y.rowStride, rotationPrediction(image.timestamp)))
        } catch (error: Throwable) {
            if (running) onError(error)
        } finally {
            image.close()
        }
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
        frontend.reset()
    }

    override fun close() = stop()
}
