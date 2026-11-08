package ai.arena.arkernel

import android.content.Context
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.sqrt

data class ArKernelSessionResult(
    val visual: VisualFrontendSnapshot,
    val vio: VioSnapshot?,
    val worldFromCamera: WorldPose?,
    val mappingGate: MappingGateDecision,
    val spatialMap: SpatialMapUpdate?,
    val visualFusionApplied: Boolean,
    val queuedImuSamples: Int,
    val droppedImuSamples: Long,
)

data class ArKernelReconstructionFrame(
    val camera: CameraReconstructionFrame,
    val tracking: ArKernelSessionResult,
) {
    init { require(camera.timestampNs == tracking.visual.timestampNs) }
}

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
    private val onReconstructionFrame: ((ArKernelReconstructionFrame) -> Unit)? = null,
    private val reconstructionQualityGate:
        ((ArKernelSessionResult, CameraFrameQuality) -> Boolean)? = null,
    private val compatibilityProbe: ActiveDeviceCompatibilityProbe? = null,
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
    private var gateObservedDroppedSamples = 0L
    @Volatile private var latestMappingGate = MappingGateDecision(
        MappingGateState.PAUSED, MappingPauseReason.POSE_UNINITIALIZED, false)
    private var previousBodyWorldRotation: Rotation? = null
    private var pendingBodyWorldRotation: Pair<Long, Rotation>? = null
    private val pendingReconstructionResults = TreeMap<Long, ArKernelSessionResult>()
    @Volatile private var running = false
    @Volatile private var closed = false

    private val imu = AndroidImuSource(context, vio, sampleSink = ::enqueueImu,
        compatibilityTimingSink = compatibilityProbe?.let { probe ->
            { sample -> probe.addImuSample(sample); Unit }
        })
    private val camera = Camera2FrameSource(
        context, calibration, visual,
        rotationPrediction = ::predictRotationAt,
        onResult = ::handleVisualResult,
        onError = onError,
        onReconstructionFrame = if (onReconstructionFrame == null) null
            else ::handleReconstructionCameraFrame,
        reconstructionQualityGate = if (onReconstructionFrame == null) null else { snapshot, quality ->
            val result = pendingReconstructionResults[snapshot.timestampNs]
            val accepted = result != null &&
                (reconstructionQualityGate?.invoke(result, quality) ?: true)
            if (!accepted) pendingReconstructionResults.remove(snapshot.timestampNs)
            accepted
        },
        compatibilityTimingSink = compatibilityProbe?.let { probe ->
            { sample -> probe.addCameraSample(sample); Unit }
        },
    )

    init { require(maxQueuedImuSamples >= 128) }

    fun start(cameraId: String? = null) {
        check(!closed) { "AR kernel session is closed" }
        if (running) return
        synchronized(lock) {
            queue.clear(); droppedSamples = 0; gateObservedDroppedSamples = 0
            previousBodyWorldRotation = null; pendingBodyWorldRotation = null
            pendingReconstructionResults.clear()
        }
        latestMappingGate = MappingGateDecision(
            MappingGateState.PAUSED, MappingPauseReason.POSE_UNINITIALIZED, false)
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
        latestMappingGate = MappingGateDecision(
            MappingGateState.PAUSED, MappingPauseReason.POSE_UNINITIALIZED, false)
        camera.stop()
        imu.stop()
        synchronized(lock) {
            queue.clear(); previousBodyWorldRotation = null; pendingBodyWorldRotation = null
            pendingReconstructionResults.clear()
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
        val stateBeforeFusion = vio.snapshot()
        val fusionApplied = result.relativePose?.let(vio::fuseVisual) ?: false
        val snapshot = vio.snapshot()
        val queueStatus = synchronized(lock) {
            val pending = pendingBodyWorldRotation
            if (pending?.first == result.timestampNs)
                previousBodyWorldRotation = snapshot?.rotation ?: pending.second
            pendingBodyWorldRotation = null
            val overflow = droppedSamples > gateObservedDroppedSamples
            gateObservedDroppedSamples = droppedSamples
            Triple(queue.size, droppedSamples, overflow)
        }
        val kernelMappingGate = visual.updateMappingGate(
            poseInitialized = snapshot != null,
            poseLimited = stateBeforeFusion?.status == VioStatus.LIMITED ||
                snapshot?.status == VioStatus.LIMITED,
            visualTracking = result.state == FrontendState.TRACKING,
            acceptedTracks = result.acceptedTrackCount,
            imuQueueOverflow = queueStatus.third,
        )
        val mappingGate = compatibilityProbe?.let { probe ->
            if (probe.metricAdmissionGranted) kernelMappingGate else MappingGateDecision(
                MappingGateState.PAUSED,
                if (probe.isFinished) MappingPauseReason.DEVICE_COMPATIBILITY_REJECTED
                else MappingPauseReason.DEVICE_COMPATIBILITY_PENDING,
                false)
        } ?: kernelMappingGate
        latestMappingGate = mappingGate
        val worldFromCamera = snapshot?.let { state ->
            val cameraOffsetWorld = rotate(state.rotation, bodyFromCameraTranslationMeters)
            WorldPose(result.timestampNs, multiply(state.rotation, bodyFromCameraRotation),
                Vector3(state.positionMeters.x + cameraOffsetWorld.x,
                    state.positionMeters.y + cameraOffsetWorld.y,
                    state.positionMeters.z + cameraOffsetWorld.z))
        }
        val spatialMap = if (mappingGate.integrate && worldFromCamera != null)
            visual.integrateMap(worldFromCamera) else null
        val sessionResult = ArKernelSessionResult(
            result, snapshot, worldFromCamera, mappingGate, spatialMap, fusionApplied,
            queueStatus.first, queueStatus.second)
        if (result.keyframe && onReconstructionFrame != null) {
            pendingReconstructionResults[result.timestampNs] = sessionResult
            while (pendingReconstructionResults.size > MAX_PENDING_RECONSTRUCTION_RESULTS)
                pendingReconstructionResults.pollFirstEntry()
        }
        onResult(sessionResult)
    }

    private fun handleReconstructionCameraFrame(frame: CameraReconstructionFrame) {
        if (!running) return
        val result = pendingReconstructionResults.remove(frame.timestampNs) ?: return
        while (pendingReconstructionResults.isNotEmpty() &&
            pendingReconstructionResults.firstKey() < frame.timestampNs)
            pendingReconstructionResults.pollFirstEntry()
        onReconstructionFrame?.invoke(ArKernelReconstructionFrame(frame, result))
    }

    fun planes(): List<PlaneEstimateSnapshot> {
        check(!closed) { "AR kernel session is closed" }
        return visual.planes()
    }

    fun roomLayout(): RoomLayoutSnapshot {
        check(!closed) { "AR kernel session is closed" }
        return visual.roomLayout()
    }

    /** Samples and fuses an aligned metric depth image only while mapping is stable. */
    fun integrateMetricDepthImage(
        image: MetricDepthImage,
        frameWorldFromCamera: WorldPose,
        options: DepthSamplingOptions = DepthSamplingOptions(),
    ): SurfelMapUpdate? {
        check(!closed) { "AR kernel session is closed" }
        if (!running || !latestMappingGate.integrate) return null
        return visual.integrateDepthImage(image, frameWorldFromCamera, options,
            SystemClock.elapsedRealtimeNanos())
    }

    /** Zero-copy direct FloatBuffer variant for sustained dense inference pipelines. */
    fun integrateMetricDepthBuffer(
        image: MetricDepthBuffer,
        frameWorldFromCamera: WorldPose,
        options: DepthSamplingOptions = DepthSamplingOptions(),
    ): SurfelMapUpdate? {
        check(!closed) { "AR kernel session is closed" }
        if (!running || !latestMappingGate.integrate) return null
        return visual.integrateDepthBuffer(image, frameWorldFromCamera, options,
            SystemClock.elapsedRealtimeNanos())
    }

    /** Fuses model-independent camera-space depth samples only while mapping is stable. */
    fun integrateSurfelObservations(
        observations: List<SurfelObservationCamera>,
        frameWorldFromCamera: WorldPose,
    ): SurfelMapUpdate? {
        check(!closed) { "AR kernel session is closed" }
        if (!running || !latestMappingGate.integrate) return null
        return visual.integrateSurfels(observations, frameWorldFromCamera,
            SystemClock.elapsedRealtimeNanos())
    }

    fun surfels(): List<SurfelSnapshot> {
        check(!closed) { "AR kernel session is closed" }
        return visual.surfels()
    }

    /** Projects a frame-associated calibrated pixel only onto observed requested surfaces. */
    fun projectSemanticDetection(
        projection: SemanticPixelProjection,
        frameWorldFromCamera: WorldPose,
    ): SemanticMapUpdate? {
        check(!closed) { "AR kernel session is closed" }
        require(projection.timestampNs == frameWorldFromCamera.timestampNs) {
            "Detection and camera pose timestamps must match"
        }
        if (!running || !latestMappingGate.integrate) return null
        return visual.projectSemantic(projection, frameWorldFromCamera,
            SystemClock.elapsedRealtimeNanos())
    }

    /** Returns null while geometric mapping is paused or recovering. */
    fun integrateSemanticObservation(observation: SemanticObjectObservation3d): SemanticMapUpdate? {
        check(!closed) { "AR kernel session is closed" }
        if (!running || !latestMappingGate.integrate) return null
        return visual.integrateSemantic(observation, SystemClock.elapsedRealtimeNanos())
    }

    fun semanticObjects(): List<SemanticObjectSnapshot> {
        check(!closed) { "AR kernel session is closed" }
        return visual.semanticObjects()
    }

    fun pruneSemanticObjects(nowNs: Long): SemanticMapUpdate {
        check(!closed) { "AR kernel session is closed" }
        return visual.pruneSemantics(nowNs)
    }

    /** Returns the nearest hit across all currently observed plane boundaries. */
    fun raycastPlane(originWorldMeters: Vector3, directionWorld: Vector3,
                     minDistanceMeters: Double = 0.05,
                     maxDistanceMeters: Double = 20.0): PlaneRaycastHit? {
        check(!closed) { "AR kernel session is closed" }
        return visual.raycastPlane(originWorldMeters, directionWorld,
            minDistanceMeters, maxDistanceMeters)
    }

    /** Returns the nearest finite-disc/sphere hit in the confirmed surfel map. */
    fun raycastSurfel(originWorldMeters: Vector3, directionWorld: Vector3,
                      minDistanceMeters: Double = 0.05,
                      maxDistanceMeters: Double = 20.0): SurfelHitSnapshot? {
        check(!closed) { "AR kernel session is closed" }
        return visual.raycastSurfel(originWorldMeters, directionWorld,
            minDistanceMeters, maxDistanceMeters)
    }

    /** Builds a metric camera Z-buffer; zero pixels mean unknown, not free space. */
    fun renderSurfelDepth(
        worldFromCamera: WorldPose,
        options: SurfelDepthRenderOptions = SurfelDepthRenderOptions(),
    ): SurfelDepthImage {
        check(!closed) { "AR kernel session is closed" }
        return visual.renderSurfelDepth(worldFromCamera, options)
    }

    /** Writes metric camera Z into a reusable direct renderer buffer. */
    fun renderSurfelDepthInto(
        worldFromCamera: WorldPose,
        output: java.nio.FloatBuffer,
        options: SurfelDepthRenderOptions = SurfelDepthRenderOptions(),
    ): SurfelDepthRenderResult {
        check(!closed) { "AR kernel session is closed" }
        return visual.renderSurfelDepthInto(worldFromCamera, output, options)
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

    /**
     * Robustly aligns a persisted saved world to the running current world and
     * atomically restores only when the native inlier/residual gates pass.
     */
    fun restoreSpatialMapRelocalized(
        store: ArKernelMapStore,
        correspondences: List<RelocalizationCorrespondence>,
    ): RelocalizationResult? {
        check(!closed) { "AR kernel session is closed" }
        check(running) { "Start the session before relocalized map restore" }
        val snapshot = store.load() ?: return null
        return visual.restoreRelocalizedSpatialSnapshot(snapshot, correspondences,
            SystemClock.elapsedRealtimeNanos())
    }

    /**
     * Attempts descriptor matching against the running confirmed sparse map,
     * then applies the same metric RANSAC gates as explicit relocalization.
     */
    fun restoreSpatialMapMatched(store: ArKernelMapStore): RelocalizationResult? {
        check(!closed) { "AR kernel session is closed" }
        check(running) { "Start and map the current scene before matched restore" }
        if (!latestMappingGate.integrate) return null
        val snapshot = store.load() ?: return null
        return visual.restoreMatchedSpatialSnapshot(snapshot, SystemClock.elapsedRealtimeNanos())
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

    private companion object { const val MAX_PENDING_RECONSTRUCTION_RESULTS = 4 }
}
