package ai.arena.arkernel

enum class ObjectCapturePhase {
    ACQUIRE_OBJECT, STABILIZE_CENTER, SET_DISTANCE, ORBIT, COMPLETE, BLOCKED,
}

enum class ObjectCaptureInstruction {
    CENTER_OBJECT, HOLD_STILL, MOVE_CLOSER, MOVE_FARTHER,
    ORBIT_TO_NEXT_VIEW, RAISE_CAMERA, LOWER_CAMERA, COMPLETE,
    RETRY_TRACKING, STOP,
}

enum class ObjectCaptureRejection {
    NONE, INVALID, NON_MONOTONIC_TIMESTAMP, TRACKING_UNRELIABLE,
    FOREGROUND_INVALID, OBJECT_CENTER_MISSING, CENTER_UNSTABLE,
    DISTANCE_INVALID, CAPACITY_REACHED, COVERAGE_INCOMPLETE,
}

/**
 * Bounded configuration for deterministic isolated-object capture guidance.
 * Foreground ratio, object centre and camera pose are measured by the host;
 * this class does not segment images or actuate a physical turntable.
 */
data class ObjectCaptureGuidanceConfig(
    val azimuthBins: Long = 12,
    val elevationBins: Long = 3,
    val maxObservations: Long = 512,
    val minStableCenterObservations: Long = 3,
    val minForegroundRatio: Double = 0.10,
    val maxForegroundRatio: Double = 0.90,
    val minDistanceMeters: Double = 0.15,
    val maxDistanceMeters: Double = 3.0,
    val targetDistanceMeters: Double = 0.8,
    val distanceToleranceMeters: Double = 0.15,
    val maxCenterShiftMeters: Double = 0.02,
    val minCoverageRatio: Double = 0.80,
    val elevationStepDegrees: Double = 10.0,
) {
    init {
        require(azimuthBins in 1L..360L && elevationBins in 1L..180L) {
            "Object capture view bins are outside their bounded range"
        }
        require(maxObservations in 1L..1_000_000L &&
            minStableCenterObservations in 1L..maxObservations) {
            "Object capture observation budgets are invalid"
        }
        require(listOf(minForegroundRatio, maxForegroundRatio, minDistanceMeters,
            maxDistanceMeters, targetDistanceMeters, distanceToleranceMeters,
            maxCenterShiftMeters, minCoverageRatio, elevationStepDegrees)
            .all { it.isFinite() }) { "Object guidance scalars must be finite" }
        require(minForegroundRatio >= 0.0 && maxForegroundRatio <= 1.0 &&
            maxForegroundRatio > minForegroundRatio) { "Foreground ratio bounds are invalid" }
        require(minDistanceMeters > 0.0 && maxDistanceMeters > minDistanceMeters &&
            targetDistanceMeters >= minDistanceMeters &&
            targetDistanceMeters <= maxDistanceMeters) { "Object distance bounds are invalid" }
        require(distanceToleranceMeters >= 0.0 && maxCenterShiftMeters >= 0.0 &&
            minCoverageRatio > 0.0 && minCoverageRatio <= 1.0 &&
            elevationStepDegrees > 0.0 && elevationStepDegrees <= 180.0) {
            "Object guidance tolerances are invalid"
        }
    }
}

data class ObjectCaptureGuidanceObservation(
    val pose: WorldPose,
    val trackingReliable: Boolean,
    val foregroundRatio: Double,
    val objectCenterWorldMeters: Vector3? = null,
) {
    init {
        require(pose.timestampNs > 0) { "Observation timestamp must be positive" }
        require(foregroundRatio.isFinite() && foregroundRatio in 0.0..1.0) {
            "Foreground ratio must be finite and normalized"
        }
        val rotation = pose.rotation
        val position = pose.positionMeters
        require(listOf(rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z).all { it.isFinite() }) {
            "Observation pose must be finite"
        }
        require(kotlin.math.sqrt(rotation.w * rotation.w + rotation.x * rotation.x +
            rotation.y * rotation.y + rotation.z * rotation.z) > 1e-9) {
            "Observation quaternion is invalid"
        }
        val center = objectCenterWorldMeters
        if (center != null) {
            require(listOf(center.x, center.y, center.z).all { it.isFinite() }) {
                "Object centre must be finite"
            }
        }
    }
}

data class ObjectCaptureGuidanceResult(
    val phase: ObjectCapturePhase,
    val instruction: ObjectCaptureInstruction,
    val rejection: ObjectCaptureRejection,
    val complete: Boolean,
    val observations: Long,
    val stableCenterObservations: Long,
    val coveredBins: Long,
    val totalBins: Long,
    val coveredRings: Long,
    val coverageRatio: Double,
    val currentAzimuthDegrees: Double,
    val currentElevationDegrees: Double,
    val targetAzimuthDegrees: Double,
    val targetElevationDegrees: Double,
    val turntableStepDegrees: Double,
    val measuredDistanceMeters: Double,
)

/**
 * Lifecycle-safe Android owner of the portable bounded guidance state machine.
 * Results are UI-neutral: hosts map instructions to localized prompts. Coverage
 * is measured view coverage only, never a reconstruction-quality claim.
 */
class ArKernelObjectCaptureGuidance(
    private val config: ObjectCaptureGuidanceConfig = ObjectCaptureGuidanceConfig(),
) : AutoCloseable {
    private var nativeHandle = nativeCreate(
        longArrayOf(config.azimuthBins, config.elevationBins,
            config.maxObservations, config.minStableCenterObservations),
        doubleArrayOf(config.minForegroundRatio, config.maxForegroundRatio,
            config.minDistanceMeters, config.maxDistanceMeters,
            config.targetDistanceMeters, config.distanceToleranceMeters,
            config.maxCenterShiftMeters, config.minCoverageRatio,
            config.elevationStepDegrees)).also {
        require(it != 0L) { "Invalid object capture guidance configuration" }
    }

    @Synchronized
    fun observe(observation: ObjectCaptureGuidanceObservation): ObjectCaptureGuidanceResult {
        check(nativeHandle != 0L) { "Object capture guidance is closed" }
        val pose = observation.pose
        val center = observation.objectCenterWorldMeters ?: Vector3(0.0, 0.0, 0.0)
        val metadata = LongArray(9)
        val values = DoubleArray(7)
        val status = nativeObserve(nativeHandle, pose.timestampNs,
            pose.rotation.w, pose.rotation.x, pose.rotation.y, pose.rotation.z,
            pose.positionMeters.x, pose.positionMeters.y, pose.positionMeters.z,
            observation.trackingReliable, observation.foregroundRatio,
            observation.objectCenterWorldMeters != null,
            center.x, center.y, center.z, metadata, values)
        check(status == RESULT_OK) { "Native object capture guidance error: $status" }
        return decode(metadata, values)
    }

    @Synchronized
    fun guidance(): ObjectCaptureGuidanceResult {
        check(nativeHandle != 0L) { "Object capture guidance is closed" }
        val metadata = LongArray(9)
        val values = DoubleArray(7)
        val status = nativeGuidance(nativeHandle, metadata, values)
        check(status == RESULT_OK) { "Native object capture guidance error: $status" }
        return decode(metadata, values)
    }

    @Synchronized
    fun reset() {
        check(nativeHandle != 0L) { "Object capture guidance is closed" }
        check(nativeReset(nativeHandle) == RESULT_OK)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun decode(metadata: LongArray, values: DoubleArray) =
        ObjectCaptureGuidanceResult(
            ObjectCapturePhase.entries[metadata[0].toInt()],
            ObjectCaptureInstruction.entries[metadata[1].toInt()],
            ObjectCaptureRejection.entries[metadata[2].toInt()],
            metadata[3] != 0L, metadata[4], metadata[5], metadata[6],
            metadata[7], metadata[8], values[0], values[1], values[2],
            values[3], values[4], values[5], values[6])

    private external fun nativeCreate(integerConfig: LongArray,
        scalarConfig: DoubleArray): Long
    private external fun nativeObserve(handle: Long, timestampNs: Long,
        rw: Double, rx: Double, ry: Double, rz: Double,
        px: Double, py: Double, pz: Double, trackingReliable: Boolean,
        foregroundRatio: Double, hasObjectCenter: Boolean,
        centerX: Double, centerY: Double, centerZ: Double,
        metadata: LongArray, values: DoubleArray): Int
    private external fun nativeGuidance(handle: Long, metadata: LongArray,
        values: DoubleArray): Int
    private external fun nativeReset(handle: Long): Int
    private external fun nativeDestroy(handle: Long)

    private companion object {
        const val RESULT_OK = 0
        init { System.loadLibrary("arkernel_jni") }
    }
}
