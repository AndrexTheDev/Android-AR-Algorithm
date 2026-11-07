package ai.arena.arkernel

data class Vector3(val x: Double, val y: Double, val z: Double)
data class Rotation(val w: Double, val x: Double, val y: Double, val z: Double)
data class RelativeVisualPose(
    val timestampNs: Long,
    val rotationCurrentFromPrevious: Rotation,
    val translationDirectionCurrent: Vector3,
)
enum class VioStatus { UNINITIALIZED, INERTIAL, VISUAL_INERTIAL, LIMITED }
data class VioSnapshot(
    val timestampNs: Long,
    val status: VioStatus,
    val rotation: Rotation,
    val positionMeters: Vector3,
    val velocityMetersPerSecond: Vector3,
    val accelerometerBias: Vector3,
    val gyroscopeBias: Vector3,
    val visualTranslationScaleMeters: Double?,
)

/**
 * Lifecycle-safe owner of one native VIO session. Sensor timestamps must use
 * Android's monotonic boot-time domain (SensorEvent.timestamp).
 */
class ArKernelVio(
    bodyFromCameraRotation: Rotation = Rotation(1.0, 0.0, 0.0, 0.0),
) : AutoCloseable {
    private var nativeHandle: Long = nativeCreate(
        bodyFromCameraRotation.w, bodyFromCameraRotation.x,
        bodyFromCameraRotation.y, bodyFromCameraRotation.z).also {
        check(it != 0L) { "Could not create native AR kernel" }
    }
    private var acceptingSamples = true

    @Synchronized
    fun submitImu(timestampNs: Long, acceleration: Vector3, angularVelocity: Vector3): Boolean {
        checkOpen()
        if (!acceptingSamples) return false
        return when (val result = nativeSubmitImu(nativeHandle, timestampNs,
            acceleration.x, acceleration.y, acceleration.z,
            angularVelocity.x, angularVelocity.y, angularVelocity.z)) {
            RESULT_OK -> true
            RESULT_NOT_READY -> false
            RESULT_OUT_OF_ORDER -> throw IllegalArgumentException("IMU timestamps must increase")
            else -> throw IllegalStateException("Native IMU error: $result")
        }
    }

    @Synchronized
    fun fuseVisual(pose: RelativeVisualPose): Boolean {
        checkOpen()
        if (!acceptingSamples) return false
        val r = pose.rotationCurrentFromPrevious
        val t = pose.translationDirectionCurrent
        return when (val result = nativeFuseVisual(nativeHandle, pose.timestampNs,
            r.w, r.x, r.y, r.z, t.x, t.y, t.z)) {
            RESULT_OK -> true
            RESULT_NOT_READY -> false
            else -> throw IllegalStateException("Native visual fusion error: $result")
        }
    }

    @Synchronized
    fun snapshot(): VioSnapshot? {
        checkOpen()
        val metadata = LongArray(3)
        val values = DoubleArray(17)
        val result = nativeGetState(nativeHandle, metadata, values)
        if (result == RESULT_NOT_READY) return null
        check(result == RESULT_OK) { "Native state error: $result" }
        return VioSnapshot(
            metadata[0], VioStatus.entries[metadata[1].toInt()],
            Rotation(values[0], values[1], values[2], values[3]),
            Vector3(values[4], values[5], values[6]),
            Vector3(values[7], values[8], values[9]),
            Vector3(values[10], values[11], values[12]),
            Vector3(values[13], values[14], values[15]),
            if (metadata[2] != 0L) values[16] else null,
        )
    }

    /** Stop callbacks before invoking this from Activity/Process onPause. */
    @Synchronized fun pause() { checkOpen(); acceptingSamples = false; nativeReset(nativeHandle) }
    /** Starts a clean session; old camera and IMU timestamps are never mixed. */
    @Synchronized fun resume() { checkOpen(); nativeReset(nativeHandle); acceptingSamples = true }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) { nativeDestroy(nativeHandle); nativeHandle = 0L }
        acceptingSamples = false
    }

    private fun checkOpen() = check(nativeHandle != 0L) { "ArKernelVio is closed" }

    private external fun nativeCreate(rw: Double, rx: Double, ry: Double, rz: Double): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long): Int
    private external fun nativeSubmitImu(handle: Long, timestampNs: Long,
        ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): Int
    private external fun nativeFuseVisual(handle: Long, timestampNs: Long,
        rw: Double, rx: Double, ry: Double, rz: Double,
        tx: Double, ty: Double, tz: Double): Int
    private external fun nativeGetState(handle: Long, metadata: LongArray, values: DoubleArray): Int

    private companion object {
        const val RESULT_OK = 0
        const val RESULT_OUT_OF_ORDER = 2
        const val RESULT_NOT_READY = 3
        init { System.loadLibrary("arkernel_jni") }
    }
}
