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
data class PlaneEstimateSnapshot(
    val normalWorld: Vector3,
    val distanceFromOriginMeters: Double,
    val centerWorldMeters: Vector3,
    val observedAreaSquareMeters: Double,
    val meanAbsoluteErrorMeters: Double,
    val inlierCount: Long,
    val id: Long = 0,
)
data class SpatialMapUpdate(
    val timestampNs: Long,
    val created: Long,
    val updated: Long,
    val rejected: Long,
    val pruned: Long,
    val landmarkCount: Long,
    val plane: PlaneEstimateSnapshot?,
)
data class PlaneRaycastHit(
    val positionWorldMeters: Vector3,
    val normalWorld: Vector3,
    val distanceMeters: Double,
    val planeCoordinatesMeters: Pair<Double, Double>,
)
data class WorldAnchorSnapshot(val id: Long, val worldPose: WorldPose)

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

    @Synchronized
    fun integrateMap(worldFromCamera: WorldPose): SpatialMapUpdate {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val metadata = LongArray(8)
        val values = DoubleArray(9)
        val r = worldFromCamera.rotation
        val p = worldFromCamera.positionMeters
        val result = nativeIntegrateMap(nativeHandle, worldFromCamera.timestampNs,
            r.w, r.x, r.y, r.z, p.x, p.y, p.z, metadata, values)
        check(result == RESULT_OK) { "Native map integration error: $result" }
        val plane = if (metadata[6] != 0L) PlaneEstimateSnapshot(
            Vector3(values[0], values[1], values[2]), values[3],
            Vector3(values[4], values[5], values[6]), values[7], values[8], metadata[7]) else null
        return SpatialMapUpdate(metadata[0], metadata[1], metadata[2], metadata[3],
            metadata[4], metadata[5], plane)
    }

    @Synchronized
    fun planes(): List<PlaneEstimateSnapshot> {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val count = nativeGetPlaneCount(nativeHandle)
        check(count >= 0) { "Could not query native planes" }
        return List(count.toInt()) { index ->
            val metadata = LongArray(2)
            val values = DoubleArray(9)
            val result = nativeGetPlane(nativeHandle, index.toLong(), metadata, values)
            check(result == RESULT_OK) { "Native plane query error: $result" }
            PlaneEstimateSnapshot(Vector3(values[0], values[1], values[2]), values[3],
                Vector3(values[4], values[5], values[6]), values[7], values[8],
                metadata[1], metadata[0])
        }
    }

    @Synchronized
    fun raycastPlane(originWorldMeters: Vector3, directionWorld: Vector3,
                     minDistanceMeters: Double = 0.05,
                     maxDistanceMeters: Double = 20.0): PlaneRaycastHit? {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val values = DoubleArray(9)
        val result = nativeRaycastPlane(nativeHandle,
            originWorldMeters.x, originWorldMeters.y, originWorldMeters.z,
            directionWorld.x, directionWorld.y, directionWorld.z,
            minDistanceMeters, maxDistanceMeters, values)
        if (result == RESULT_NOT_READY) return null
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid plane raycast" }
        check(result == RESULT_OK) { "Native plane raycast error: $result" }
        return PlaneRaycastHit(Vector3(values[0], values[1], values[2]),
            Vector3(values[3], values[4], values[5]), values[6], values[7] to values[8])
    }

    @Synchronized
    fun createAnchor(worldPose: WorldPose): Long {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val r = worldPose.rotation
        val p = worldPose.positionMeters
        return nativeCreateAnchor(nativeHandle, worldPose.timestampNs,
            r.w, r.x, r.y, r.z, p.x, p.y, p.z).also {
            check(it != 0L) { "Could not create world anchor" }
        }
    }

    @Synchronized fun removeAnchor(id: Long): Boolean {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(id > 0) { "Anchor ID must be positive" }
        return nativeRemoveAnchor(nativeHandle, id)
    }

    @Synchronized fun anchor(id: Long): WorldAnchorSnapshot? {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(id > 0) { "Anchor ID must be positive" }
        val metadata = LongArray(2)
        val values = DoubleArray(7)
        val result = nativeGetAnchor(nativeHandle, id, metadata, values)
        if (result == RESULT_NOT_READY) return null
        check(result == RESULT_OK) { "Native anchor query error: $result" }
        return WorldAnchorSnapshot(metadata[0], WorldPose(metadata[1],
            Rotation(values[0], values[1], values[2], values[3]),
            Vector3(values[4], values[5], values[6])))
    }

    /** Exports ARKMAP01 plaintext. Encrypt before writing it to persistent storage. */
    @Synchronized fun exportSpatialSnapshot(createdNs: Long): ByteArray {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(createdNs > 0) { "Snapshot timestamp must be positive" }
        return checkNotNull(nativeExportSpatialSnapshot(nativeHandle, createdNs)) {
            "Could not export native spatial snapshot"
        }
    }

    /** Restores only after the caller has authenticated and decrypted the bytes. */
    @Synchronized fun restoreSpatialSnapshot(snapshot: ByteArray, restoreNowNs: Long) {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(snapshot.isNotEmpty()) { "Spatial snapshot must not be empty" }
        require(restoreNowNs > 0) { "Restore timestamp must be positive" }
        val result = nativeRestoreSpatialSnapshot(nativeHandle, snapshot, restoreNowNs)
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid or corrupted spatial snapshot" }
        check(result == RESULT_OK) { "Native snapshot restore error: $result" }
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
    private external fun nativeIntegrateMap(handle: Long, timestampNs: Long,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, metadata: LongArray, values: DoubleArray): Int
    private external fun nativeGetPlaneCount(handle: Long): Long
    private external fun nativeGetPlane(handle: Long, index: Long,
        metadata: LongArray, values: DoubleArray): Int
    private external fun nativeRaycastPlane(handle: Long,
        ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double,
        minDistance: Double, maxDistance: Double, values: DoubleArray): Int
    private external fun nativeCreateAnchor(handle: Long, timestampNs: Long,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double): Long
    private external fun nativeRemoveAnchor(handle: Long, id: Long): Boolean
    private external fun nativeGetAnchor(handle: Long, id: Long,
        metadata: LongArray, values: DoubleArray): Int
    private external fun nativeExportSpatialSnapshot(handle: Long, createdNs: Long): ByteArray?
    private external fun nativeRestoreSpatialSnapshot(handle: Long, snapshot: ByteArray,
        restoreNowNs: Long): Int

    private companion object {
        const val RESULT_OK = 0
        const val RESULT_INVALID_ARGUMENT = 1
        const val RESULT_OUT_OF_ORDER = 2
        const val RESULT_NOT_READY = 3
        init { System.loadLibrary("arkernel_jni") }
    }
}
