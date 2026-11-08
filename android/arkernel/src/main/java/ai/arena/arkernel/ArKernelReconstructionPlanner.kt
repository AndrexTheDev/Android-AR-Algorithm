package ai.arena.arkernel

enum class ReconstructionScanMode { ENVIRONMENT, OBJECT }
enum class ReconstructionFrameRejection {
    NONE, INVALID, NON_MONOTONIC_TIMESTAMP, TRACKING_UNRELIABLE, BLURRED,
    EXPOSURE_CLIPPED, TOO_FEW_FEATURES, POSE_UNCERTAIN, INSUFFICIENT_MOTION,
    OBJECT_ROI_INVALID, OBJECT_DISTANCE_INVALID, CAPACITY_REACHED,
}

data class ReconstructionPlannerConfig(
    val maxKeyframes: Long = 2_000,
    val minIntervalNs: Long = 100_000_000,
    val minTrackedFeatures: Long = 60,
    val objectAzimuthBins: Long = 12,
    val objectElevationBins: Long = 3,
    val minTranslationMeters: Double = 0.04,
    val minRotationDegrees: Double = 5.0,
    val minSharpness: Double = 0.35,
    val maxClippedPixelRatio: Double = 0.08,
    val maxTranslationSigmaMeters: Double = 0.02,
    val maxRotationSigmaDegrees: Double = 1.0,
    val minObjectForegroundRatio: Double = 0.10,
    val maxObjectForegroundRatio: Double = 0.90,
    val minObjectDistanceMeters: Double = 0.15,
    val maxObjectDistanceMeters: Double = 3.0,
    val maxObjectCenterShiftMeters: Double = 0.02,
)

data class ReconstructionObjectObservation(
    val centerWorldMeters: Vector3,
    val foregroundRatio: Double,
)

data class ReconstructionPlannerDecision(
    val accepted: Boolean,
    val rejection: ReconstructionFrameRejection,
    val qualityScore: Double,
    val objectViewBin: Long?,
    val coveredObjectBins: Long,
    val totalObjectBins: Long,
    val objectCoverageRatio: Double,
    val reservationToken: Long = 0,
)

/** Android owner for the portable bounded C++ capture planner. */
class ArKernelReconstructionPlanner(
    mode: ReconstructionScanMode,
    config: ReconstructionPlannerConfig = ReconstructionPlannerConfig(),
) : AutoCloseable {
    private var nativeHandle = nativeCreate(mode == ReconstructionScanMode.OBJECT,
        longArrayOf(config.maxKeyframes, config.minIntervalNs, config.minTrackedFeatures,
            config.objectAzimuthBins, config.objectElevationBins),
        doubleArrayOf(config.minTranslationMeters, config.minRotationDegrees,
            config.minSharpness, config.maxClippedPixelRatio,
            config.maxTranslationSigmaMeters, config.maxRotationSigmaDegrees,
            config.minObjectForegroundRatio, config.maxObjectForegroundRatio,
            config.minObjectDistanceMeters, config.maxObjectDistanceMeters,
            config.maxObjectCenterShiftMeters)).also {
        require(it != 0L) { "Invalid reconstruction planner configuration" }
    }

    /** Immediate path only when the frame payload is already durable. */
    @Synchronized fun consider(frameId: Long, session: ArKernelSessionResult,
        quality: CameraFrameQuality, translationSigmaMeters: Double,
        rotationSigmaDegrees: Double,
        objectObservation: ReconstructionObjectObservation? = null
    ) = decide(false, frameId, session, quality, translationSigmaMeters,
        rotationSigmaDegrees, objectObservation)

    /** Pre-copy path. Call commit(token) only after durable storage, otherwise cancel(token). */
    @Synchronized fun reserve(frameId: Long, session: ArKernelSessionResult,
        quality: CameraFrameQuality, translationSigmaMeters: Double,
        rotationSigmaDegrees: Double,
        objectObservation: ReconstructionObjectObservation? = null
    ) = decide(true, frameId, session, quality, translationSigmaMeters,
        rotationSigmaDegrees, objectObservation)

    @Synchronized fun commit(reservationToken: Long): Boolean {
        check(nativeHandle != 0L)
        return nativeCommit(nativeHandle, reservationToken)
    }

    @Synchronized fun cancel(reservationToken: Long): Boolean {
        check(nativeHandle != 0L)
        return nativeCancel(nativeHandle, reservationToken)
    }

    private fun decide(reserve: Boolean, frameId: Long, session: ArKernelSessionResult,
        quality: CameraFrameQuality, translationSigmaMeters: Double,
        rotationSigmaDegrees: Double,
        objectObservation: ReconstructionObjectObservation?): ReconstructionPlannerDecision {
        check(nativeHandle != 0L) { "Reconstruction planner is closed" }
        val pose = session.worldFromCamera
        if (pose == null) return unavailable(ReconstructionFrameRejection.TRACKING_UNRELIABLE)
        require(pose.timestampNs == session.visual.timestampNs)
        val objectCenter = objectObservation?.centerWorldMeters ?: Vector3(0.0, 0.0, 0.0)
        val metadata = LongArray(7)
        val values = DoubleArray(2)
        val status = nativeDecide(nativeHandle, reserve, frameId, pose.timestampNs,
            pose.rotation.w, pose.rotation.x, pose.rotation.y, pose.rotation.z,
            pose.positionMeters.x, pose.positionMeters.y, pose.positionMeters.z,
            session.visual.state == FrontendState.TRACKING && session.vio?.status != VioStatus.LIMITED,
            session.visual.acceptedTrackCount, quality.sharpness, quality.clippedPixelRatio,
            translationSigmaMeters, rotationSigmaDegrees,
            objectObservation?.foregroundRatio ?: 0.0, objectObservation != null,
            objectCenter.x, objectCenter.y, objectCenter.z, metadata, values)
        check(status == RESULT_OK) { "Native reconstruction planner error: $status" }
        return ReconstructionPlannerDecision(metadata[0] != 0L,
            ReconstructionFrameRejection.entries[metadata[1].toInt()], values[0],
            if (metadata[2] != 0L) metadata[3] else null,
            metadata[4], metadata[5], values[1], metadata[6])
    }

    @Synchronized fun reset() {
        check(nativeHandle != 0L)
        check(nativeReset(nativeHandle) == RESULT_OK)
    }

    @Synchronized override fun close() {
        if (nativeHandle != 0L) { nativeDestroy(nativeHandle); nativeHandle = 0L }
    }

    private fun unavailable(reason: ReconstructionFrameRejection) =
        ReconstructionPlannerDecision(false, reason, 0.0, null, 0, 0, 0.0)

    private external fun nativeCreate(objectMode: Boolean, integerConfig: LongArray,
                                      scalarConfig: DoubleArray): Long
    private external fun nativeDecide(handle: Long, reserve: Boolean,
        frameId: Long, timestampNs: Long,
        rw: Double, rx: Double, ry: Double, rz: Double, px: Double, py: Double, pz: Double,
        tracking: Boolean, trackedFeatures: Long, sharpness: Double, clippedRatio: Double,
        translationSigmaMeters: Double, rotationSigmaDegrees: Double,
        objectForegroundRatio: Double, hasObjectCenter: Boolean,
        objectCenterX: Double, objectCenterY: Double, objectCenterZ: Double,
        metadata: LongArray, values: DoubleArray): Int
    private external fun nativeCommit(handle: Long, reservationToken: Long): Boolean
    private external fun nativeCancel(handle: Long, reservationToken: Long): Boolean
    private external fun nativeReset(handle: Long): Int
    private external fun nativeDestroy(handle: Long)

    private companion object {
        const val RESULT_OK = 0
        init { System.loadLibrary("arkernel_jni") }
    }
}
