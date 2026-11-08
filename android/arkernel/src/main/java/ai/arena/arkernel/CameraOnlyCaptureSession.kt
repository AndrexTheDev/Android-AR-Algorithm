package ai.arena.arkernel

import android.content.Context

/** A calibrated camera frame with bounded, deterministic image-quality evidence. */
data class CameraOnlyCaptureFrame(
    val camera: CameraReconstructionFrame,
    val quality: CameraFrameQuality,
)

/**
 * Owns a calibrated Camera2 stream without constructing VIO or accessing Android
 * motion sensors. Frames carry no pose and are intended for durable capture and
 * later offline reconstruction; this class never claims live tracking or mapping.
 */
class CameraOnlyCaptureSession(
    context: Context,
    calibration: CameraCalibration,
    private val onFrame: (CameraOnlyCaptureFrame) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    maximumFramesPerSecond: Int = 10,
    maximumPendingFrames: Int = 1,
    calibrationRuntimeMonitor: CameraCalibrationRuntimeMonitor? = null,
) : AutoCloseable {
    private val camera = Camera2FrameSource(
        context = context,
        calibration = calibration,
        frontend = null,
        onError = onError,
        onCaptureFrame = { frame, quality -> onFrame(CameraOnlyCaptureFrame(frame, quality)) },
        onTimestampNormalizationFailure = { reason ->
            handleTerminalFailure("Camera clock invalidated: $reason")
        },
        calibrationRuntimeMonitor = calibrationRuntimeMonitor,
        onCalibrationRuntimeFailure = { reason ->
            handleTerminalFailure("Camera calibration binding invalidated: $reason")
        },
        maximumOutputFramesPerSecond = maximumFramesPerSecond,
        maximumPendingFrames = maximumPendingFrames,
    )
    @Volatile private var running = false
    @Volatile private var closed = false

    @Synchronized
    fun start(cameraId: String? = null) {
        check(!closed) { "Camera-only capture session is closed" }
        if (running) return
        running = true
        try {
            camera.start(cameraId)
        } catch (error: Throwable) {
            running = false
            camera.stop()
            throw error
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        camera.stop()
    }

    private fun handleTerminalFailure(message: String) {
        if (running) onError(IllegalStateException(message))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        stop()
        closed = true
    }
}
