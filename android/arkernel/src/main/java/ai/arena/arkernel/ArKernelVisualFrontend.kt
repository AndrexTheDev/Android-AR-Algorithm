package ai.arena.arkernel

import java.nio.ByteBuffer

data class CameraCalibration(
    val width: Int, val height: Int,
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val k1: Double = 0.0, val k2: Double = 0.0,
    val p1: Double = 0.0, val p2: Double = 0.0, val k3: Double = 0.0,
)
enum class FrontendState { INITIALIZING, TRACKING, LIMITED, REINITIALIZED }
enum class SceneGeometry { INVALID, GENERAL_3D, PLANAR_OR_ROTATION }
data class VisualFrontendSnapshot(
    val timestampNs: Long,
    val state: FrontendState,
    val geometry: SceneGeometry,
    val acceptedTrackCount: Long,
    val activeFeatureCount: Long,
    val keyframe: Boolean,
    val relativePose: RelativeVisualPose?,
    val positiveDepthCount: Long,
    val medianParallaxDegrees: Double,
)

/** Synchronous owner of the native visual frontend. Never retains [buffer]. */
class ArKernelVisualFrontend(private val calibration: CameraCalibration) : AutoCloseable {
    private var nativeHandle = nativeCreate(calibration.width, calibration.height,
        calibration.fx, calibration.fy, calibration.cx, calibration.cy,
        calibration.k1, calibration.k2, calibration.p1, calibration.p2, calibration.k3).also {
        check(it != 0L) { "Could not create native visual frontend; check calibration" }
    }

    @Synchronized
    fun processGray(
        buffer: ByteBuffer,
        timestampNs: Long,
        rowStride: Int,
        rotationPrediction: Rotation? = null,
    ): VisualFrontendSnapshot {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(buffer.isDirect) { "Camera Y plane must use a direct ByteBuffer" }
        val metadata = LongArray(8)
        val values = DoubleArray(8)
        val rotation = rotationPrediction ?: Rotation(1.0, 0.0, 0.0, 0.0)
        val result = nativeProcessGray(nativeHandle, buffer, buffer.position(), buffer.remaining(),
            calibration.width, calibration.height, rowStride, timestampNs,
            rotationPrediction != null, rotation.w, rotation.x, rotation.y, rotation.z,
            metadata, values)
        require(result != RESULT_OUT_OF_ORDER) { "Camera timestamps must increase" }
        check(result == RESULT_OK) { "Native visual frontend error: $result" }
        val pose = if (metadata[6] != 0L) RelativeVisualPose(
            metadata[0], Rotation(values[0], values[1], values[2], values[3]),
            Vector3(values[4], values[5], values[6])) else null
        return VisualFrontendSnapshot(metadata[0], FrontendState.entries[metadata[1].toInt()],
            SceneGeometry.entries[metadata[2].toInt()], metadata[3], metadata[4],
            metadata[5] != 0L, pose, metadata[7], values[7])
    }

    @Synchronized fun reset() { check(nativeHandle != 0L); nativeReset(nativeHandle) }
    @Synchronized override fun close() {
        if (nativeHandle != 0L) { nativeDestroy(nativeHandle); nativeHandle = 0L }
    }

    private external fun nativeCreate(width: Int, height: Int,
        fx: Double, fy: Double, cx: Double, cy: Double,
        k1: Double, k2: Double, p1: Double, p2: Double, k3: Double): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeProcessGray(handle: Long, buffer: ByteBuffer,
        offset: Int, dataSize: Int, width: Int, height: Int, rowStride: Int, timestampNs: Long,
        hasRotation: Boolean, rw: Double, rx: Double, ry: Double, rz: Double,
        metadata: LongArray, values: DoubleArray): Int

    private companion object {
        const val RESULT_OK = 0
        const val RESULT_OUT_OF_ORDER = 2
        init { System.loadLibrary("arkernel_jni") }
    }
}
