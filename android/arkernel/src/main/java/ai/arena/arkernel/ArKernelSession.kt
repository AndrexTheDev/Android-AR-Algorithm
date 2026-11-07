package ai.arena.arkernel

import android.content.Context
import android.os.SystemClock
import java.util.ArrayDeque
import kotlin.math.sqrt

data class ArKernelSessionResult(
    val visual: VisualFrontendSnapshot,
    val vio: VioSnapshot?,
    val spatialMap: SpatialMapUpdate?,
    val visualFusionApplied: Boolean,
    val queuedImuSamples: Int,
    val droppedImuSamples: Long,
)

/**
 * Coordinates Camera2, SensorManager, visual tracking and VIO in one monotonic
 * timeline. Callbacks run on the camera worker thread, not the main thread.
 * Camera-to-IMU rotation is mandatory; silently assuming aligned Android sensor
 * and optical axes would corrupt both prediction and visual fusion.
 */
class ArKernelSession(
    context: Context,
    calibration: CameraCalibration,
    private val bodyFromCameraRotation: Rotation,
    private val onResult: (ArKernelSessionResult) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val maxQueuedImuSamples: Int = 4096,
    private val bodyFromCameraTranslationMeters: Vector3 = Vector3(0.0, 0.0, 0.0),
) : AutoCloseable {
    private data class ImuPacket(
        val timestampNs: Long,
        val acceleration: Vector3,
        val angularVelocity: Vector3,
    )

    private val lock = Any()
    private val queue = ArrayDeque<ImuPacket>()
    private val vio = ArKernelVio(bodyFromCameraRotation, bodyFromCameraTranslationMeters)
    private val visual = ArKernelVisualFrontend(calibration)
    private var droppedSamples = 0L
    private var previousBodyWorldRotation: Rotation? = null
    private var pendingBodyWorldRotation: Pair<Long, Rotation>? = null
    @Volatile private var running = false
    @Volatile private var closed = false

    private val imu = AndroidImuSource(context, vio, sampleSink = ::enqueueImu)
    private val camera = Camera2FrameSource(
        context, calibration, visual,
        rotationPrediction = ::predictRotationAt,
        onResult = ::handleVisualResult,
        onError = onError,
    )

    init { require(maxQueuedImuSamples >= 128) }

    fun start(cameraId: String? = null) {
        check(!closed) { "AR kernel session is closed" }
        if (running) return
        synchronized(lock) {
            queue.clear(); droppedSamples = 0
            previousBodyWorldRotation = null; pendingBodyWorldRotation = null
        }
        running = true
        try {
            imu.start()
            camera.start(cameraId)
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        if (!running) return
        running = false
        camera.stop()
        imu.stop()
        synchronized(lock) {
            queue.clear(); previousBodyWorldRotation = null; pendingBodyWorldRotation = null
        }
    }

    private fun enqueueImu(timestamp: Long, acceleration: Vector3, angularVelocity: Vector3) {
        if (!running) return
        synchronized(lock) {
            if (queue.isNotEmpty() && timestamp <= queue.peekLast().timestampNs) return
            if (queue.size >= maxQueuedImuSamples) {
                queue.removeFirst(); droppedSamples++
            }
            queue.addLast(ImuPacket(timestamp, acceleration, angularVelocity))
        }
    }

    private fun predictRotationAt(cameraTimestampNs: Long): Rotation? {
        if (!running) return null
        val due = ArrayList<ImuPacket>()
        synchronized(lock) {
            while (queue.isNotEmpty() && queue.peekFirst().timestampNs <= cameraTimestampNs)
                due += queue.removeFirst()
        }
        for (sample in due)
            vio.submitImu(sample.timestampNs, sample.acceleration, sample.angularVelocity)
        val state = vio.snapshot() ?: return null
        // The queue contract guarantees state.timestamp <= camera timestamp.
        if (state.timestampNs > cameraTimestampNs) return null
        synchronized(lock) {
            pendingBodyWorldRotation = cameraTimestampNs to state.rotation
            return previousBodyWorldRotation?.let { previous ->
                val bodyCurrentFromPrevious = multiply(inverse(state.rotation), previous)
                multiply(multiply(inverse(bodyFromCameraRotation), bodyCurrentFromPrevious),
                         bodyFromCameraRotation)
            }
        }
    }

    private fun handleVisualResult(result: VisualFrontendSnapshot) {
        if (!running) return
        val fusionApplied = result.relativePose?.let(vio::fuseVisual) ?: false
        val snapshot = vio.snapshot()
        val queueStatus = synchronized(lock) {
            val pending = pendingBodyWorldRotation
            if (pending?.first == result.timestampNs)
                previousBodyWorldRotation = snapshot?.rotation ?: pending.second
            pendingBodyWorldRotation = null
            queue.size to droppedSamples
        }
        val spatialMap = snapshot?.let { state ->
            val cameraOffsetWorld = rotate(state.rotation, bodyFromCameraTranslationMeters)
            val worldFromCamera = WorldPose(result.timestampNs,
                multiply(state.rotation, bodyFromCameraRotation),
                Vector3(state.positionMeters.x + cameraOffsetWorld.x,
                    state.positionMeters.y + cameraOffsetWorld.y,
                    state.positionMeters.z + cameraOffsetWorld.z))
            visual.integrateMap(worldFromCamera)
        }
        onResult(ArKernelSessionResult(
            result, snapshot, spatialMap, fusionApplied, queueStatus.first, queueStatus.second))
    }

    fun planes(): List<PlaneEstimateSnapshot> {
        check(!closed) { "AR kernel session is closed" }
        return visual.planes()
    }

    fun roomLayout(): RoomLayoutSnapshot {
        check(!closed) { "AR kernel session is closed" }
        return visual.roomLayout()
    }

    /** Returns the nearest hit across all currently observed plane boundaries. */
    fun raycastPlane(originWorldMeters: Vector3, directionWorld: Vector3,
                     minDistanceMeters: Double = 0.05,
                     maxDistanceMeters: Double = 20.0): PlaneRaycastHit? {
        check(!closed) { "AR kernel session is closed" }
        return visual.raycastPlane(originWorldMeters, directionWorld,
            minDistanceMeters, maxDistanceMeters)
    }

    fun createAnchor(worldPose: WorldPose): Long {
        check(!closed) { "AR kernel session is closed" }
        return visual.createAnchor(worldPose)
    }

    fun removeAnchor(id: Long): Boolean {
        check(!closed) { "AR kernel session is closed" }
        return visual.removeAnchor(id)
    }

    fun anchor(id: Long): WorldAnchorSnapshot? {
        check(!closed) { "AR kernel session is closed" }
        return visual.anchor(id)
    }

    fun saveSpatialMap(store: ArKernelMapStore, createdNs: Long) {
        check(!closed) { "AR kernel session is closed" }
        store.save(visual.exportSpatialSnapshot(createdNs))
    }

    /**
     * Restores bytes only when they are already expressed in this session's
     * current world frame. Cross-session use requires relocalization first.
     */
    fun restoreSpatialMapInCurrentWorld(store: ArKernelMapStore): Boolean {
        check(!closed) { "AR kernel session is closed" }
        check(running) { "Start and relocalize the session before restoring its world map" }
        val snapshot = store.load() ?: return false
        visual.restoreSpatialSnapshot(snapshot, SystemClock.elapsedRealtimeNanos())
        return true
    }

    override fun close() {
        if (closed) return
        stop()
        camera.close(); imu.close(); visual.close(); vio.close()
        closed = true
    }

    private fun inverse(q: Rotation): Rotation {
        val norm = q.w*q.w + q.x*q.x + q.y*q.y + q.z*q.z
        return Rotation(q.w/norm, -q.x/norm, -q.y/norm, -q.z/norm)
    }

    private fun rotate(q: Rotation, v: Vector3): Vector3 {
        val inverseNorm = 1.0/sqrt(q.w*q.w + q.x*q.x + q.y*q.y + q.z*q.z)
        val w = q.w*inverseNorm; val x = q.x*inverseNorm
        val y = q.y*inverseNorm; val z = q.z*inverseNorm
        val tx = 2.0*(y*v.z-z*v.y)
        val ty = 2.0*(z*v.x-x*v.z)
        val tz = 2.0*(x*v.y-y*v.x)
        return Vector3(v.x+w*tx+(y*tz-z*ty),
            v.y+w*ty+(z*tx-x*tz), v.z+w*tz+(x*ty-y*tx))
    }

    private fun multiply(a: Rotation, b: Rotation): Rotation {
        val value = Rotation(
            a.w*b.w-a.x*b.x-a.y*b.y-a.z*b.z,
            a.w*b.x+a.x*b.w+a.y*b.z-a.z*b.y,
            a.w*b.y-a.x*b.z+a.y*b.w+a.z*b.x,
            a.w*b.z+a.x*b.y-a.y*b.x+a.z*b.w,
        )
        val norm = sqrt(value.w*value.w+value.x*value.x+value.y*value.y+value.z*value.z)
        return Rotation(value.w/norm, value.x/norm, value.y/norm, value.z/norm)
    }
}
