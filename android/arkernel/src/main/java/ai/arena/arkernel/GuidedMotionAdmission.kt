package ai.arena.arkernel

enum class GuidedMotionAdmissionReason {
    ACTIVE_COMPATIBILITY_REJECTED,
    ROTATION_UNOBSERVABLE,
    TIME_OFFSET_UNOBSERVABLE,
    METRIC_RUNTIME_INCONSISTENT,
}

data class GuidedMotionAdmissionReport(
    val activeCompatibility: ActiveCompatibilityReport,
    val rotation: RotationObservabilityReport,
    val timeOffset: TimeOffsetObservabilityReport,
    val metricConsistency: MetricConsistencyReport,
    val blockingReasons: List<GuidedMotionAdmissionReason>,
) {
    init {
        require(blockingReasons.distinct().size == blockingReasons.size)
        require((activeCompatibility.promotedTier ==
            DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC &&
            activeCompatibility.blockingReasons.isEmpty()) ==
            (GuidedMotionAdmissionReason.ACTIVE_COMPATIBILITY_REJECTED !in blockingReasons))
        require(rotation.passed ==
            (GuidedMotionAdmissionReason.ROTATION_UNOBSERVABLE !in blockingReasons))
        require(timeOffset.observable ==
            (GuidedMotionAdmissionReason.TIME_OFFSET_UNOBSERVABLE !in blockingReasons))
        require(metricConsistency.runtimeConsistent ==
            (GuidedMotionAdmissionReason.METRIC_RUNTIME_INCONSISTENT !in blockingReasons))
    }

    /** Runtime metric fusion may start only after every required measured check passes. */
    val runtimeMetricAdmissionGranted: Boolean get() = blockingReasons.isEmpty()

    /** Physical known-distance evidence remains mandatory for a dimensional claim. */
    val dimensionallyValidated: Boolean
        get() = runtimeMetricAdmissionGranted && metricConsistency.dimensionallyValidated
}

/**
 * Owns one bounded guided compatibility run and combines existing probes into one terminal gate.
 * It does not estimate calibration, alter timestamps, or persist evidence. The host controls when
 * the guided motion is complete by calling [finish]; all later observations are rejected.
 */
class GuidedMotionAdmissionProbe(
    internal val activeCompatibilityProbe: ActiveDeviceCompatibilityProbe,
    bodyFromCameraRotation: Rotation,
    rotationConfig: RotationObservabilityConfig = RotationObservabilityConfig(),
    timeOffsetConfig: TimeOffsetObservabilityConfig = TimeOffsetObservabilityConfig(),
    metricConfig: MetricConsistencyConfig = MetricConsistencyConfig(),
) {
    private val rotationProbe = GuidedRotationObservabilityProbe(rotationConfig)
    private val timeOffsetProbe = GuidedTimeOffsetObservabilityProbe(
        bodyFromCameraRotation, timeOffsetConfig)
    private val metricProbe = GuidedMetricConsistencyProbe(metricConfig)
    @Volatile private var completed: GuidedMotionAdmissionReport? = null

    val isFinished: Boolean get() = completed != null
    val runtimeMetricAdmissionGranted: Boolean
        get() = completed?.runtimeMetricAdmissionGranted == true
    val report: GuidedMotionAdmissionReport? get() = completed

    @Synchronized
    fun addMatchedRotation(visualCurrentFromPrevious: Rotation,
        predictedCurrentFromPrevious: Rotation): Boolean =
        if (completed != null) false else rotationProbe.addMatchedRotation(
            visualCurrentFromPrevious, predictedCurrentFromPrevious)

    @Synchronized
    fun addGyroscopeBody(timestampNs: Long, angularVelocity: Vector3): Boolean =
        if (completed != null) false else timeOffsetProbe.addGyroscopeBody(
            timestampNs, angularVelocity)

    @Synchronized
    fun addVisualRotation(previousTimestampNs: Long, currentTimestampNs: Long,
        currentFromPrevious: Rotation): Boolean =
        if (completed != null) false else timeOffsetProbe.addVisualRotation(
            previousTimestampNs, currentTimestampNs, currentFromPrevious)

    @Synchronized
    fun addImuSample(acceleration: Vector3, angularVelocity: Vector3): Boolean =
        if (completed != null) false else metricProbe.addImuSample(acceleration, angularVelocity)

    @Synchronized
    fun addVisualFusion(result: VisualFusionSnapshot): Boolean =
        if (completed != null) false else metricProbe.addVisualFusion(result)

    @Synchronized
    fun addKnownDistance(measuredMeters: Double, referenceMeters: Double,
        referenceUncertaintyMeters: Double): Boolean =
        if (completed != null) false else metricProbe.addKnownDistance(
            measuredMeters, referenceMeters, referenceUncertaintyMeters)

    @Synchronized
    fun finish(): GuidedMotionAdmissionReport {
        completed?.let { return it }
        val active = activeCompatibilityProbe.finish()
        val rotation = rotationProbe.finish()
        val timeOffset = timeOffsetProbe.finish()
        val metric = metricProbe.finish()
        val reasons = mutableListOf<GuidedMotionAdmissionReason>()
        if (active.promotedTier != DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC ||
            active.blockingReasons.isNotEmpty())
            reasons += GuidedMotionAdmissionReason.ACTIVE_COMPATIBILITY_REJECTED
        if (!rotation.passed)
            reasons += GuidedMotionAdmissionReason.ROTATION_UNOBSERVABLE
        if (!timeOffset.observable)
            reasons += GuidedMotionAdmissionReason.TIME_OFFSET_UNOBSERVABLE
        if (!metric.runtimeConsistent)
            reasons += GuidedMotionAdmissionReason.METRIC_RUNTIME_INCONSISTENT
        return GuidedMotionAdmissionReport(active, rotation, timeOffset, metric,
            reasons).also { completed = it }
    }
}
