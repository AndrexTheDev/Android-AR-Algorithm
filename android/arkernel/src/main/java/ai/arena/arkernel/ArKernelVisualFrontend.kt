package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class CameraCalibration(
    val width: Int, val height: Int,
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val k1: Double = 0.0, val k2: Double = 0.0,
    val p1: Double = 0.0, val p2: Double = 0.0, val k3: Double = 0.0,
)
enum class FrontendState { INITIALIZING, TRACKING, LIMITED, REINITIALIZED }
enum class SceneGeometry { INVALID, GENERAL_3D, PLANAR_OR_ROTATION }
enum class PlaneSemantic { UNKNOWN, FLOOR, CEILING, WALL, SLOPED }
enum class MappingGateState { PAUSED, RECOVERING, ACTIVE }
enum class MappingPauseReason {
    NONE, POSE_UNINITIALIZED, POSE_LIMITED, VISUAL_NOT_TRACKING,
    TOO_FEW_TRACKS, IMU_OVERFLOW, RECOVERING,
    DEVICE_COMPATIBILITY_PENDING, DEVICE_COMPATIBILITY_REJECTED,
    CAMERA_CLOCK_DISCONTINUITY
}
data class MappingGateDecision(
    val state: MappingGateState,
    val reason: MappingPauseReason,
    val integrate: Boolean,
)
data class PlaneEstimateSnapshot(
    val normalWorld: Vector3,
    val distanceFromOriginMeters: Double,
    val centerWorldMeters: Vector3,
    val observedAreaSquareMeters: Double,
    val meanAbsoluteErrorMeters: Double,
    val inlierCount: Long,
    val id: Long = 0,
    val semantic: PlaneSemantic = PlaneSemantic.UNKNOWN,
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
data class RoomLayoutSnapshot(
    val floorPlaneId: Long?,
    val ceilingPlaneId: Long?,
    val floorElevationMeters: Double?,
    val ceilingElevationMeters: Double?,
    val roomHeightMeters: Double?,
    val observedFloorAreaSquareMeters: Double,
    val observedCeilingAreaSquareMeters: Double,
    val observedWallAreaSquareMeters: Double,
    val wallCount: Long,
)
data class PlaneRaycastHit(
    val positionWorldMeters: Vector3,
    val normalWorld: Vector3,
    val distanceMeters: Double,
    val planeCoordinatesMeters: Pair<Double, Double>,
)
data class WorldAnchorSnapshot(val id: Long, val worldPose: WorldPose)
data class RelocalizationCorrespondence(
    val savedWorldPointMeters: Vector3,
    val currentWorldPointMeters: Vector3,
)
data class RelocalizationResult(
    val correspondenceCount: Long,
    val inlierCount: Long,
    val rmseMeters: Double,
    val currentFromSaved: WorldPose,
)

enum class SemanticProjectionSurface { ANY, FLOOR, WALL }
data class SemanticPixelProjection(
    val classId: Long,
    val sourceTrackId: Long = -1,
    val pixelX: Double,
    val pixelY: Double,
    val confidence: Double,
    val timestampNs: Long,
    val dynamic: Boolean = false,
    val surface: SemanticProjectionSurface = SemanticProjectionSurface.ANY,
    val minDistanceMeters: Double = 0.05,
    val maxDistanceMeters: Double = 20.0,
)
data class MetricDepthImage(
    val width: Int,
    val height: Int,
    val rowStrideElements: Int,
    val depthMeters: FloatArray,
    val confidence: FloatArray? = null,
    val confidenceRowStrideElements: Int = if (confidence == null) 0 else rowStrideElements,
    val timestampNs: Long,
)
data class MetricDepthBuffer(
    val width: Int,
    val height: Int,
    val rowStrideElements: Int,
    val depthMeters: FloatBuffer,
    val confidence: FloatBuffer? = null,
    val confidenceRowStrideElements: Int = if (confidence == null) 0 else rowStrideElements,
    val timestampNs: Long,
)
data class DepthSamplingOptions(
    val pixelStride: Int = 4,
    val minDepthMeters: Double = 0.15,
    val maxDepthMeters: Double = 20.0,
    val minConfidence: Double = 0.20,
    val estimateNormals: Boolean = true,
)

data class SurfelObservationCamera(
    val positionCameraMeters: Vector3,
    val normalCamera: Vector3 = Vector3(0.0, 0.0, 0.0),
    val confidence: Double,
    val radiusMeters: Double,
    val normalValid: Boolean = false,
)
data class SurfelMapUpdate(
    val created: Long,
    val updated: Long,
    val rejected: Long,
    val pruned: Long,
    val surfelCount: Long,
)
data class SurfelDepthRenderOptions(
    val minDepthMeters: Double = 0.05,
    val maxDepthMeters: Double = 20.0,
    val maxProjectedRadiusPixels: Int = 64,
    val maxRasterizedPixels: Long = 4_000_000,
)
data class SurfelDepthRenderResult(
    val width: Int,
    val height: Int,
    val projectedSurfelCount: Long,
    val rasterizedPixelCount: Long,
    val truncated: Boolean,
)
data class SurfelDepthImage(
    val width: Int,
    val height: Int,
    val depthZMeters: FloatArray,
    val projectedSurfelCount: Long,
    val rasterizedPixelCount: Long,
    val truncated: Boolean,
)
data class SurfelHitSnapshot(
    val surfelId: Long,
    val positionWorldMeters: Vector3,
    val normalWorld: Vector3,
    val distanceMeters: Double,
    val confidence: Double,
    val normalValid: Boolean,
)
data class SurfelSnapshot(
    val id: Long,
    val positionWorldMeters: Vector3,
    val normalWorld: Vector3,
    val confidence: Double,
    val radiusMeters: Double,
    val observationCount: Long,
    val firstSeenNs: Long,
    val lastSeenNs: Long,
    val normalValid: Boolean,
)

data class SemanticObjectObservation3d(
    val classId: Long,
    val sourceTrackId: Long = -1,
    val positionWorldMeters: Vector3,
    val confidence: Double,
    val timestampNs: Long,
    val dynamic: Boolean = false,
)
data class SemanticMapUpdate(
    val created: Long,
    val updated: Long,
    val rejected: Long,
    val pruned: Long,
    val objectCount: Long,
)
data class SemanticObjectSnapshot(
    val id: Long,
    val classId: Long,
    val sourceTrackId: Long,
    val positionWorldMeters: Vector3,
    val confidence: Double,
    val observationCount: Long,
    val firstSeenNs: Long,
    val lastSeenNs: Long,
    val dynamic: Boolean,
)

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
    fun updateMappingGate(
        poseInitialized: Boolean,
        poseLimited: Boolean,
        visualTracking: Boolean,
        acceptedTracks: Long,
        imuQueueOverflow: Boolean,
    ): MappingGateDecision {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(acceptedTracks >= 0) { "Accepted track count must not be negative" }
        val output = LongArray(3)
        val result = nativeUpdateMappingGate(nativeHandle, poseInitialized, poseLimited,
            visualTracking, acceptedTracks, imuQueueOverflow, output)
        check(result == RESULT_OK) { "Native mapping gate error: $result" }
        return MappingGateDecision(MappingGateState.entries[output[0].toInt()],
            MappingPauseReason.entries[output[1].toInt()], output[2] != 0L)
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
            val metadata = LongArray(3)
            val values = DoubleArray(9)
            val result = nativeGetPlane(nativeHandle, index.toLong(), metadata, values)
            check(result == RESULT_OK) { "Native plane query error: $result" }
            PlaneEstimateSnapshot(Vector3(values[0], values[1], values[2]), values[3],
                Vector3(values[4], values[5], values[6]), values[7], values[8],
                metadata[1], metadata[0], PlaneSemantic.entries[metadata[2].toInt()])
        }
    }

    @Synchronized
    fun roomLayout(): RoomLayoutSnapshot {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val metadata = LongArray(6)
        val values = DoubleArray(6)
        val result = nativeGetRoomLayout(nativeHandle, metadata, values)
        check(result == RESULT_OK) { "Native room layout query error: $result" }
        return RoomLayoutSnapshot(
            if (metadata[0] != 0L) metadata[3] else null,
            if (metadata[1] != 0L) metadata[4] else null,
            if (metadata[0] != 0L) values[0] else null,
            if (metadata[1] != 0L) values[1] else null,
            if (metadata[2] != 0L) values[2] else null,
            values[3], values[4], values[5], metadata[5])
    }

    @Synchronized
    fun integrateDepthImage(
        image: MetricDepthImage,
        frameWorldFromCamera: WorldPose,
        options: DepthSamplingOptions = DepthSamplingOptions(),
        nowNs: Long = image.timestampNs,
    ): SurfelMapUpdate {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(image.timestampNs == frameWorldFromCamera.timestampNs) {
            "Depth image and camera pose timestamps must match"
        }
        require(image.width > 0 && image.height > 0 && image.rowStrideElements >= image.width)
        val requiredDepth = (image.height.toLong() - 1L) * image.rowStrideElements.toLong() +
            image.width.toLong()
        require(requiredDepth <= image.depthMeters.size.toLong()) { "Depth image storage is truncated" }
        if (image.confidence == null) require(image.confidenceRowStrideElements == 0)
        image.confidence?.let {
            require(image.confidenceRowStrideElements >= image.width)
            val requiredConfidence = (image.height.toLong() - 1L) *
                image.confidenceRowStrideElements.toLong() + image.width.toLong()
            require(requiredConfidence <= it.size.toLong()) { "Confidence image storage is truncated" }
        }
        val rotation = frameWorldFromCamera.rotation
        val position = frameWorldFromCamera.positionMeters
        val output = LongArray(5)
        val result = nativeIntegrateDepthImage(nativeHandle, image.depthMeters, image.confidence,
            image.width, image.height, image.rowStrideElements,
            image.confidenceRowStrideElements, image.timestampNs, nowNs, options.pixelStride,
            options.minDepthMeters, options.maxDepthMeters, options.minConfidence,
            options.estimateNormals, rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z, output)
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid or unaligned metric depth image" }
        check(result == RESULT_OK) { "Native depth image integration error: $result" }
        return SurfelMapUpdate(output[0], output[1], output[2], output[3], output[4])
    }

    /** Zero-copy input path; buffers must stay valid until this synchronous call returns. */
    @Synchronized
    fun integrateDepthBuffer(
        image: MetricDepthBuffer,
        frameWorldFromCamera: WorldPose,
        options: DepthSamplingOptions = DepthSamplingOptions(),
        nowNs: Long = image.timestampNs,
    ): SurfelMapUpdate {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(image.timestampNs == frameWorldFromCamera.timestampNs) {
            "Depth buffer and camera pose timestamps must match"
        }
        require(image.width > 0 && image.height > 0 && image.rowStrideElements >= image.width)
        require(image.depthMeters.isDirect) { "Depth FloatBuffer must be direct" }
        require(image.depthMeters.order() == ByteOrder.nativeOrder()) {
            "Depth FloatBuffer must use native byte order"
        }
        val requiredDepth = (image.height.toLong() - 1L) * image.rowStrideElements.toLong() +
            image.width.toLong()
        require(requiredDepth <= image.depthMeters.remaining().toLong()) {
            "Depth buffer storage is truncated from its current position"
        }
        if (image.confidence == null) require(image.confidenceRowStrideElements == 0)
        image.confidence?.let {
            require(it.isDirect) { "Confidence FloatBuffer must be direct" }
            require(it.order() == ByteOrder.nativeOrder()) {
                "Confidence FloatBuffer must use native byte order"
            }
            require(image.confidenceRowStrideElements >= image.width)
            val requiredConfidence = (image.height.toLong() - 1L) *
                image.confidenceRowStrideElements.toLong() + image.width.toLong()
            require(requiredConfidence <= it.remaining().toLong()) {
                "Confidence buffer storage is truncated from its current position"
            }
        }
        val rotation = frameWorldFromCamera.rotation
        val position = frameWorldFromCamera.positionMeters
        val output = LongArray(5)
        val result = nativeIntegrateDepthBuffer(nativeHandle,
            image.depthMeters, image.depthMeters.position(), image.depthMeters.remaining(),
            image.confidence, image.confidence?.position() ?: 0,
            image.confidence?.remaining() ?: 0, image.width, image.height,
            image.rowStrideElements, image.confidenceRowStrideElements,
            image.timestampNs, nowNs, options.pixelStride, options.minDepthMeters,
            options.maxDepthMeters, options.minConfidence, options.estimateNormals,
            rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z, output)
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid or unaligned direct metric depth buffer" }
        check(result == RESULT_OK) { "Native direct depth integration error: $result" }
        return SurfelMapUpdate(output[0], output[1], output[2], output[3], output[4])
    }

    @Synchronized
    fun integrateSurfels(
        observations: List<SurfelObservationCamera>,
        frameWorldFromCamera: WorldPose,
        nowNs: Long = frameWorldFromCamera.timestampNs,
    ): SurfelMapUpdate {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(observations.size <= 100_000) { "Too many surfel observations" }
        val values = DoubleArray(observations.size * 9)
        observations.forEachIndexed { index, observation ->
            val offset = index * 9
            values[offset] = observation.positionCameraMeters.x
            values[offset + 1] = observation.positionCameraMeters.y
            values[offset + 2] = observation.positionCameraMeters.z
            values[offset + 3] = observation.normalCamera.x
            values[offset + 4] = observation.normalCamera.y
            values[offset + 5] = observation.normalCamera.z
            values[offset + 6] = observation.confidence
            values[offset + 7] = observation.radiusMeters
            values[offset + 8] = if (observation.normalValid) 1.0 else 0.0
        }
        val rotation = frameWorldFromCamera.rotation
        val position = frameWorldFromCamera.positionMeters
        val output = LongArray(5)
        val result = nativeIntegrateSurfels(nativeHandle, frameWorldFromCamera.timestampNs, nowNs,
            values, rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z, output)
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid surfel observations or camera pose" }
        check(result == RESULT_OK) { "Native surfel integration error: $result" }
        return SurfelMapUpdate(output[0], output[1], output[2], output[3], output[4])
    }

    @Synchronized
    fun surfels(): List<SurfelSnapshot> {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val count = nativeGetSurfelCount(nativeHandle)
        check(count in 0..Int.MAX_VALUE.toLong()) { "Could not query native surfels" }
        return List(count.toInt()) { index ->
            val metadata = LongArray(5)
            val values = DoubleArray(8)
            val result = nativeGetSurfel(nativeHandle, index.toLong(), metadata, values)
            check(result == RESULT_OK) { "Native surfel query error: $result" }
            SurfelSnapshot(metadata[0], Vector3(values[0], values[1], values[2]),
                Vector3(values[3], values[4], values[5]), values[6], values[7],
                metadata[1], metadata[2], metadata[3], metadata[4] != 0L)
        }
    }

    @Synchronized
    fun raycastSurfel(
        originWorldMeters: Vector3,
        directionWorld: Vector3,
        minDistanceMeters: Double = 0.05,
        maxDistanceMeters: Double = 20.0,
    ): SurfelHitSnapshot? {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val metadata = LongArray(2)
        val values = DoubleArray(8)
        val result = nativeRaycastSurfel(nativeHandle,
            originWorldMeters.x, originWorldMeters.y, originWorldMeters.z,
            directionWorld.x, directionWorld.y, directionWorld.z,
            minDistanceMeters, maxDistanceMeters, metadata, values)
        if (result == RESULT_NOT_READY) return null
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid surfel ray" }
        check(result == RESULT_OK) { "Native surfel raycast error: $result" }
        return SurfelHitSnapshot(metadata[0], Vector3(values[0], values[1], values[2]),
            Vector3(values[3], values[4], values[5]), values[6], values[7], metadata[1] != 0L)
    }

    @Synchronized
    fun renderSurfelDepth(
        worldFromCamera: WorldPose,
        options: SurfelDepthRenderOptions = SurfelDepthRenderOptions(),
    ): SurfelDepthImage {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(options.minDepthMeters > 0.0 &&
            options.maxDepthMeters > options.minDepthMeters)
        require(options.maxProjectedRadiusPixels > 0 && options.maxRasterizedPixels > 0)
        val rotation = worldFromCamera.rotation
        val position = worldFromCamera.positionMeters
        val metadata = LongArray(5)
        val depth = checkNotNull(nativeRenderSurfelDepth(nativeHandle, worldFromCamera.timestampNs,
            rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z, options.minDepthMeters,
            options.maxDepthMeters, options.maxProjectedRadiusPixels,
            options.maxRasterizedPixels, metadata)) { "Could not render native surfel depth" }
        check(metadata[0] in 1L..Int.MAX_VALUE.toLong() &&
            metadata[1] in 1L..Int.MAX_VALUE.toLong())
        check(depth.size.toLong() == metadata[0] * metadata[1])
        return SurfelDepthImage(metadata[0].toInt(), metadata[1].toInt(), depth,
            metadata[2], metadata[3], metadata[4] != 0L)
    }

    /** Writes from the buffer's current position without advancing it. */
    @Synchronized
    fun renderSurfelDepthInto(
        worldFromCamera: WorldPose,
        output: FloatBuffer,
        options: SurfelDepthRenderOptions = SurfelDepthRenderOptions(),
    ): SurfelDepthRenderResult {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(output.isDirect) { "Occlusion output FloatBuffer must be direct" }
        require(output.order() == ByteOrder.nativeOrder()) {
            "Occlusion output FloatBuffer must use native byte order"
        }
        require(!output.isReadOnly) { "Occlusion output FloatBuffer must be writable" }
        require(options.minDepthMeters > 0.0 &&
            options.maxDepthMeters > options.minDepthMeters)
        require(options.maxProjectedRadiusPixels > 0 && options.maxRasterizedPixels > 0)
        val rotation = worldFromCamera.rotation
        val position = worldFromCamera.positionMeters
        val metadata = LongArray(5)
        val status = nativeRenderSurfelDepthInto(nativeHandle, worldFromCamera.timestampNs,
            rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z, options.minDepthMeters,
            options.maxDepthMeters, options.maxProjectedRadiusPixels,
            options.maxRasterizedPixels, output, output.position(), output.remaining(), metadata)
        require(status != RESULT_INVALID_ARGUMENT) {
            "Invalid pose, render options, or direct output capacity"
        }
        check(status == RESULT_OK) { "Native direct surfel depth render error: $status" }
        check(metadata[0] in 1L..Int.MAX_VALUE.toLong() &&
            metadata[1] in 1L..Int.MAX_VALUE.toLong())
        return SurfelDepthRenderResult(metadata[0].toInt(), metadata[1].toInt(),
            metadata[2], metadata[3], metadata[4] != 0L)
    }

    @Synchronized
    fun projectSemantic(
        projection: SemanticPixelProjection,
        worldFromCamera: WorldPose,
        nowNs: Long = projection.timestampNs,
    ): SemanticMapUpdate? {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(projection.classId in 0..0xffff_ffffL) { "Semantic class ID is out of range" }
        val output = LongArray(5)
        val rotation = worldFromCamera.rotation
        val position = worldFromCamera.positionMeters
        val result = nativeProjectSemantic(nativeHandle, projection.classId,
            projection.sourceTrackId, projection.timestampNs, nowNs,
            projection.pixelX, projection.pixelY, projection.confidence, projection.dynamic,
            projection.surface.ordinal, projection.minDistanceMeters, projection.maxDistanceMeters,
            rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z, output)
        if (result == RESULT_NOT_READY) return null
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid semantic pixel projection" }
        check(result == RESULT_OK) { "Native semantic projection error: $result" }
        return SemanticMapUpdate(output[0], output[1], output[2], output[3], output[4])
    }

    @Synchronized
    fun integrateSemantic(
        observation: SemanticObjectObservation3d,
        nowNs: Long = observation.timestampNs,
    ): SemanticMapUpdate {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(observation.classId in 0..0xffff_ffffL) { "Semantic class ID is out of range" }
        val output = LongArray(5)
        val position = observation.positionWorldMeters
        val result = nativeIntegrateSemantic(nativeHandle, observation.classId,
            observation.sourceTrackId, observation.timestampNs, nowNs,
            position.x, position.y, position.z,
            observation.confidence, observation.dynamic, output)
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid semantic object observation" }
        check(result == RESULT_OK) { "Native semantic map error: $result" }
        return SemanticMapUpdate(output[0], output[1], output[2], output[3], output[4])
    }

    @Synchronized
    fun pruneSemantics(nowNs: Long): SemanticMapUpdate {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val output = LongArray(5)
        val result = nativePruneSemantics(nativeHandle, nowNs, output)
        require(result != RESULT_INVALID_ARGUMENT) { "Invalid semantic prune timestamp" }
        check(result == RESULT_OK) { "Native semantic prune error: $result" }
        return SemanticMapUpdate(output[0], output[1], output[2], output[3], output[4])
    }

    @Synchronized
    fun semanticObjects(): List<SemanticObjectSnapshot> {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        val count = nativeGetSemanticObjectCount(nativeHandle)
        check(count >= 0) { "Could not query native semantic object count" }
        return List(count.toInt()) { index ->
            val metadata = LongArray(7)
            val values = DoubleArray(4)
            val result = nativeGetSemanticObject(nativeHandle, index.toLong(), metadata, values)
            check(result == RESULT_OK) { "Native semantic object query error: $result" }
            SemanticObjectSnapshot(metadata[0], metadata[1], metadata[2],
                Vector3(values[0], values[1], values[2]), values[3], metadata[5],
                metadata[3], metadata[4], metadata[6] != 0L)
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

    /** Returns null when robust geometry does not pass the relocalization gates. */
    @Synchronized fun restoreRelocalizedSpatialSnapshot(
        snapshot: ByteArray,
        correspondences: List<RelocalizationCorrespondence>,
        restoreNowNs: Long,
    ): RelocalizationResult? {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(snapshot.isNotEmpty()) { "Spatial snapshot must not be empty" }
        require(correspondences.size in 6..4096) { "Relocalization requires 6..4096 correspondences" }
        require(restoreNowNs > 0) { "Restore timestamp must be positive" }
        val saved = DoubleArray(correspondences.size * 3)
        val current = DoubleArray(correspondences.size * 3)
        correspondences.forEachIndexed { index, value ->
            saved[index * 3] = value.savedWorldPointMeters.x
            saved[index * 3 + 1] = value.savedWorldPointMeters.y
            saved[index * 3 + 2] = value.savedWorldPointMeters.z
            current[index * 3] = value.currentWorldPointMeters.x
            current[index * 3 + 1] = value.currentWorldPointMeters.y
            current[index * 3 + 2] = value.currentWorldPointMeters.z
        }
        val output = DoubleArray(10)
        val status = nativeRestoreRelocalizedSpatialSnapshot(nativeHandle, snapshot, saved,
            current, restoreNowNs, output)
        if (status == RESULT_NOT_READY) return null
        require(status != RESULT_INVALID_ARGUMENT) { "Invalid snapshot or correspondences" }
        check(status == RESULT_OK) { "Native relocalized restore error: $status" }
        return RelocalizationResult(output[0].toLong(), output[1].toLong(), output[2],
            WorldPose(restoreNowNs, Rotation(output[3], output[4], output[5], output[6]),
                Vector3(output[7], output[8], output[9])))
    }

    /** Matches persisted descriptors against confirmed landmarks in the current map. */
    @Synchronized fun restoreMatchedSpatialSnapshot(
        snapshot: ByteArray,
        restoreNowNs: Long,
    ): RelocalizationResult? {
        check(nativeHandle != 0L) { "Visual frontend is closed" }
        require(snapshot.isNotEmpty()) { "Spatial snapshot must not be empty" }
        require(restoreNowNs > 0) { "Restore timestamp must be positive" }
        val output = DoubleArray(10)
        val status = nativeRestoreMatchedSpatialSnapshot(nativeHandle, snapshot, restoreNowNs, output)
        if (status == RESULT_NOT_READY) return null
        require(status != RESULT_INVALID_ARGUMENT) { "Invalid spatial snapshot" }
        check(status == RESULT_OK) { "Native descriptor relocalization error: $status" }
        return RelocalizationResult(output[0].toLong(), output[1].toLong(), output[2],
            WorldPose(restoreNowNs, Rotation(output[3], output[4], output[5], output[6]),
                Vector3(output[7], output[8], output[9])))
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
    private external fun nativeUpdateMappingGate(handle: Long,
        poseInitialized: Boolean, poseLimited: Boolean, visualTracking: Boolean,
        acceptedTracks: Long, imuQueueOverflow: Boolean, output: LongArray): Int
    private external fun nativeIntegrateMap(handle: Long, timestampNs: Long,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, metadata: LongArray, values: DoubleArray): Int
    private external fun nativeGetPlaneCount(handle: Long): Long
    private external fun nativeGetPlane(handle: Long, index: Long,
        metadata: LongArray, values: DoubleArray): Int
    private external fun nativeGetRoomLayout(handle: Long,
        metadata: LongArray, values: DoubleArray): Int
    private external fun nativeIntegrateDepthImage(handle: Long, depth: FloatArray,
        confidence: FloatArray?, width: Int, height: Int, rowStride: Int,
        confidenceStride: Int, timestampNs: Long, nowNs: Long, pixelStride: Int,
        minDepth: Double, maxDepth: Double, minConfidence: Double, estimateNormals: Boolean,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, output: LongArray): Int
    private external fun nativeIntegrateDepthBuffer(handle: Long,
        depth: FloatBuffer, depthOffset: Int, depthCount: Int,
        confidence: FloatBuffer?, confidenceOffset: Int, confidenceCount: Int,
        width: Int, height: Int, rowStride: Int, confidenceStride: Int,
        timestampNs: Long, nowNs: Long, pixelStride: Int,
        minDepth: Double, maxDepth: Double, minConfidence: Double, estimateNormals: Boolean,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, output: LongArray): Int
    private external fun nativeIntegrateSurfels(handle: Long, timestampNs: Long, nowNs: Long,
        observations: DoubleArray, rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, output: LongArray): Int
    private external fun nativeGetSurfelCount(handle: Long): Long
    private external fun nativeGetSurfel(handle: Long, index: Long,
        metadata: LongArray, values: DoubleArray): Int
    private external fun nativeRaycastSurfel(handle: Long,
        ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double,
        minDistance: Double, maxDistance: Double,
        metadata: LongArray, values: DoubleArray): Int
    private external fun nativeRenderSurfelDepth(handle: Long, timestampNs: Long,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, minDepth: Double, maxDepth: Double,
        maxRadiusPixels: Int, maxRasterizedPixels: Long, metadata: LongArray): FloatArray?
    private external fun nativeRenderSurfelDepthInto(handle: Long, timestampNs: Long,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, minDepth: Double, maxDepth: Double,
        maxRadiusPixels: Int, maxRasterizedPixels: Long, output: FloatBuffer,
        outputOffset: Int, outputCount: Int, metadata: LongArray): Int
    private external fun nativeProjectSemantic(handle: Long, classId: Long,
        sourceTrackId: Long, timestampNs: Long, nowNs: Long,
        pixelX: Double, pixelY: Double, confidence: Double, dynamic: Boolean, surface: Int,
        minDistance: Double, maxDistance: Double,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, output: LongArray): Int
    private external fun nativeIntegrateSemantic(handle: Long, classId: Long,
        sourceTrackId: Long, timestampNs: Long, nowNs: Long,
        x: Double, y: Double, z: Double,
        confidence: Double, dynamic: Boolean, output: LongArray): Int
    private external fun nativePruneSemantics(handle: Long, nowNs: Long, output: LongArray): Int
    private external fun nativeGetSemanticObjectCount(handle: Long): Long
    private external fun nativeGetSemanticObject(handle: Long, index: Long,
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
    private external fun nativeRestoreRelocalizedSpatialSnapshot(handle: Long,
        snapshot: ByteArray, savedPoints: DoubleArray, currentPoints: DoubleArray,
        restoreNowNs: Long, output: DoubleArray): Int
    private external fun nativeRestoreMatchedSpatialSnapshot(handle: Long,
        snapshot: ByteArray, restoreNowNs: Long, output: DoubleArray): Int

    private companion object {
        const val RESULT_OK = 0
        const val RESULT_INVALID_ARGUMENT = 1
        const val RESULT_OUT_OF_ORDER = 2
        const val RESULT_NOT_READY = 3
        init { System.loadLibrary("arkernel_jni") }
    }
}
