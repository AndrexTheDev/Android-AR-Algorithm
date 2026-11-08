package ai.arena.arkernel

import kotlin.math.acos
import kotlin.math.sqrt

private const val MAX_OFFSET_GYRO_SAMPLES = 4096
private const val MAX_OFFSET_VISUAL_SAMPLES = 512

enum class TimeOffsetObservabilityReason {
    INSUFFICIENT_GYROSCOPE_SAMPLES,
    INSUFFICIENT_VISUAL_ROTATIONS,
    INSUFFICIENT_TIME_SPAN,
    INSUFFICIENT_ROTATIONAL_EXCITATION,
    INSUFFICIENT_MULTI_AXIS_EXCITATION,
    CORRELATION_TOO_WEAK,
    OFFSET_AMBIGUOUS,
    OFFSET_AT_SEARCH_BOUNDARY,
    SAMPLE_BUDGET_EXCEEDED,
}

data class TimeOffsetObservabilityConfig(
    val minimumGyroscopeSamples: Int = 100,
    val minimumVisualRotations: Int = 20,
    val minimumSpanNs: Long = 2_000_000_000L,
    val maximumAbsoluteOffsetNs: Long = 100_000_000L,
    val searchStepNs: Long = 2_000_000L,
    val minimumCorrelation: Double = 0.75,
    val minimumPeakMargin: Double = 0.005,
    val peakExclusionRadiusNs: Long = 10_000_000L,
    val uncertaintyCorrelationDrop: Double = 0.02,
    val minimumAngularSpeedStdDev: Double = 0.10,
    val minimumSecondaryAxisEnergyRatio: Double = 0.05,
) {
    init {
        require(minimumGyroscopeSamples in 3..MAX_OFFSET_GYRO_SAMPLES)
        require(minimumVisualRotations in 3..MAX_OFFSET_VISUAL_SAMPLES)
        require(minimumSpanNs in 100_000_000L..60_000_000_000L)
        require(maximumAbsoluteOffsetNs in 2_000_000L..500_000_000L)
        require(searchStepNs in 100_000L..maximumAbsoluteOffsetNs)
        require(maximumAbsoluteOffsetNs % searchStepNs == 0L)
        require(minimumCorrelation in -1.0..1.0)
        require(minimumPeakMargin in 0.0..1.0)
        require(peakExclusionRadiusNs >= searchStepNs)
        require(uncertaintyCorrelationDrop in 0.001..0.25)
        require(minimumAngularSpeedStdDev in 0.001..10.0)
        require(minimumSecondaryAxisEnergyRatio in 0.001..0.5)
    }
}

data class TimeOffsetObservabilityReport(
    val gyroscopeSamples: Int,
    val visualRotations: Int,
    val observedSpanNs: Long,
    /** Add this value to a visual midpoint timestamp to sample the gyro timeline. */
    val visualToGyroscopeOffsetNs: Long?,
    val offsetUncertaintyNs: Long?,
    val offsetVarianceNsSquared: Double?,
    val bestCorrelation: Double,
    val peakMargin: Double,
    val reasons: List<TimeOffsetObservabilityReason>,
) {
    init {
        require(gyroscopeSamples in 0..MAX_OFFSET_GYRO_SAMPLES)
        require(visualRotations in 0..MAX_OFFSET_VISUAL_SAMPLES)
        require(observedSpanNs >= 0)
        require(bestCorrelation.isFinite() && bestCorrelation in -1.0..1.0)
        require(peakMargin.isFinite() && peakMargin >= 0.0)
        require((visualToGyroscopeOffsetNs == null) == (offsetUncertaintyNs == null))
        require((visualToGyroscopeOffsetNs == null) == (offsetVarianceNsSquared == null))
        require(offsetUncertaintyNs == null || offsetUncertaintyNs >= 0)
        require(offsetVarianceNsSquared == null ||
            (offsetVarianceNsSquared.isFinite() && offsetVarianceNsSquared >= 0.0))
        require(reasons.distinct().size == reasons.size)
    }
    val observable: Boolean get() = reasons.isEmpty()
}

/**
 * Bounded guided angular-rate correlator. It searches a fixed offset grid and reports a
 * conservative peak-width variance only when changing, multi-axis motion makes the peak unique.
 * It never applies the result to live timestamps or persists a calibration by itself.
 */
class GuidedTimeOffsetObservabilityProbe(
    private val bodyFromCameraRotation: Rotation,
    private val config: TimeOffsetObservabilityConfig = TimeOffsetObservabilityConfig(),
) {
    private data class TimedVector(val timestampNs: Long, val value: Vector3)
    private val cameraFromBodyRotation = inverseUnit(bodyFromCameraRotation)
    private val gyro = ArrayList<TimedVector>()
    private val visual = ArrayList<TimedVector>()
    private var budgetExceeded = false
    @Volatile private var completed: TimeOffsetObservabilityReport? = null

    val isFinished: Boolean get() = completed != null

    /** SensorManager angular velocity is in body axes; comparison is performed in camera axes. */
    @Synchronized
    fun addGyroscopeBody(timestampNs: Long, angularVelocityBody: Vector3): Boolean {
        if (completed != null || timestampNs <= 0 || !angularVelocityBody.finite()) return false
        if (gyro.isNotEmpty() && timestampNs <= gyro.last().timestampNs) return false
        if (gyro.size == MAX_OFFSET_GYRO_SAMPLES) { budgetExceeded = true; return false }
        // Visual pose is camera-current-from-camera-previous, the inverse of the camera's
        // physical world rotation increment, hence the signed minus here.
        gyro += TimedVector(timestampNs, scale(
            rotateVector(cameraFromBodyRotation, angularVelocityBody), -1.0))
        return true
    }

    /** Rotation must use camera-current-from-camera-previous for the stated frame interval. */
    @Synchronized
    fun addVisualRotation(previousTimestampNs: Long, currentTimestampNs: Long,
        currentFromPrevious: Rotation): Boolean {
        if (completed != null || previousTimestampNs <= 0 || currentTimestampNs <= previousTimestampNs)
            return false
        if (visual.size == MAX_OFFSET_VISUAL_SAMPLES) { budgetExceeded = true; return false }
        val vector = rotationVectorForOffset(currentFromPrevious) ?: return false
        val durationSeconds = (currentTimestampNs - previousTimestampNs) * 1e-9
        val midpoint = previousTimestampNs + (currentTimestampNs - previousTimestampNs) / 2
        visual += TimedVector(midpoint, scale(vector, 1.0 / durationSeconds))
        return true
    }

    @Synchronized
    fun finish(): TimeOffsetObservabilityReport {
        completed?.let { return it }
        val reasons = mutableListOf<TimeOffsetObservabilityReason>()
        if (gyro.size < config.minimumGyroscopeSamples)
            reasons += TimeOffsetObservabilityReason.INSUFFICIENT_GYROSCOPE_SAMPLES
        if (visual.size < config.minimumVisualRotations)
            reasons += TimeOffsetObservabilityReason.INSUFFICIENT_VISUAL_ROTATIONS
        val span = if (visual.size < 2) 0L else visual.last().timestampNs - visual.first().timestampNs
        if (span < config.minimumSpanNs) reasons += TimeOffsetObservabilityReason.INSUFFICIENT_TIME_SPAN
        val excitation = excitationStatistics(visual.map { it.value })
        if (excitation.first < config.minimumAngularSpeedStdDev)
            reasons += TimeOffsetObservabilityReason.INSUFFICIENT_ROTATIONAL_EXCITATION
        if (excitation.second < config.minimumSecondaryAxisEnergyRatio)
            reasons += TimeOffsetObservabilityReason.INSUFFICIENT_MULTI_AXIS_EXCITATION

        val candidates = ArrayList<Pair<Long, Double>>()
        var offset = -config.maximumAbsoluteOffsetNs
        while (offset <= config.maximumAbsoluteOffsetNs) {
            correlationAt(offset)?.let { candidates += offset to it }
            offset += config.searchStepNs
        }
        val best = candidates.maxByOrNull { it.second }
        val second = if (best == null) null else candidates
            .filter { kotlin.math.abs(it.first - best.first) > config.peakExclusionRadiusNs }
            .maxByOrNull { it.second }
        val bestCorrelation = best?.second ?: 0.0
        val margin = (bestCorrelation - (second?.second ?: bestCorrelation)).coerceAtLeast(0.0)
        if (bestCorrelation < config.minimumCorrelation)
            reasons += TimeOffsetObservabilityReason.CORRELATION_TOO_WEAK
        if (margin < config.minimumPeakMargin)
            reasons += TimeOffsetObservabilityReason.OFFSET_AMBIGUOUS
        if (best != null && kotlin.math.abs(best.first) == config.maximumAbsoluteOffsetNs)
            reasons += TimeOffsetObservabilityReason.OFFSET_AT_SEARCH_BOUNDARY
        if (budgetExceeded) reasons += TimeOffsetObservabilityReason.SAMPLE_BUDGET_EXCEEDED

        val accepted = best != null && reasons.isEmpty()
        val uncertainty = if (!accepted) null else candidates
            .filter { it.second >= bestCorrelation - config.uncertaintyCorrelationDrop }
            .maxOf { kotlin.math.abs(it.first - best!!.first) }
            .coerceAtLeast(config.searchStepNs)
        val variance = uncertainty?.toDouble()?.let { width -> width * width / 3.0 }
        return TimeOffsetObservabilityReport(gyro.size, visual.size, span,
            if (accepted) best!!.first else null, uncertainty, variance,
            bestCorrelation.coerceIn(-1.0, 1.0), margin, reasons.distinct()).also { completed = it }
    }

    private fun correlationAt(offsetNs: Long): Double? {
        val pairs = visual.mapNotNull { sample ->
            interpolateGyro(sample.timestampNs + offsetNs)?.let { sample.value to it }
        }
        if (pairs.size < config.minimumVisualRotations) return null
        val visualMean = mean(pairs.map { it.first })
        val gyroMean = mean(pairs.map { it.second })
        var cross = 0.0; var visualEnergy = 0.0; var gyroEnergy = 0.0
        for ((visualValue, gyroValue) in pairs) {
            val a = subtract(visualValue, visualMean)
            val b = subtract(gyroValue, gyroMean)
            cross += dotOffset(a, b)
            visualEnergy += dotOffset(a, a)
            gyroEnergy += dotOffset(b, b)
        }
        val denominator = sqrt(visualEnergy * gyroEnergy)
        return if (denominator > 1e-12) (cross / denominator).coerceIn(-1.0, 1.0) else null
    }

    private fun interpolateGyro(timestampNs: Long): Vector3? {
        if (gyro.size < 2 || timestampNs < gyro.first().timestampNs ||
            timestampNs > gyro.last().timestampNs) return null
        var low = 0; var high = gyro.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) ushr 1
            if (gyro[middle].timestampNs <= timestampNs) low = middle else high = middle
        }
        val start = gyro[low]
        if (start.timestampNs == timestampNs) return start.value
        val end = gyro[high]
        val alpha = (timestampNs - start.timestampNs).toDouble() /
            (end.timestampNs - start.timestampNs).toDouble()
        return add(start.value, scale(subtract(end.value, start.value), alpha))
    }
}

private fun excitationStatistics(values: List<Vector3>): Pair<Double, Double> {
    if (values.isEmpty()) return 0.0 to 0.0
    val center = mean(values)
    val axisEnergy = DoubleArray(3)
    var speedVariance = 0.0
    val speeds = values.map { sqrt(dotOffset(it, it)) }
    val speedMean = speeds.average()
    values.forEachIndexed { index, value ->
        val delta = subtract(value, center)
        axisEnergy[0] += delta.x * delta.x
        axisEnergy[1] += delta.y * delta.y
        axisEnergy[2] += delta.z * delta.z
        val speedDelta = speeds[index] - speedMean
        speedVariance += speedDelta * speedDelta
    }
    axisEnergy.sortDescending()
    val ratio = if (axisEnergy[0] > 1e-12) axisEnergy[1] / axisEnergy[0] else 0.0
    return sqrt(speedVariance / values.size) to ratio
}

private fun rotationVectorForOffset(rotation: Rotation): Vector3? {
    if (!listOf(rotation.w, rotation.x, rotation.y, rotation.z).all { it.isFinite() }) return null
    val length = sqrt(rotation.w * rotation.w + rotation.x * rotation.x +
        rotation.y * rotation.y + rotation.z * rotation.z)
    if (length < 1e-12) return null
    var w = rotation.w / length; var x = rotation.x / length
    var y = rotation.y / length; var z = rotation.z / length
    if (w < 0.0) { w = -w; x = -x; y = -y; z = -z }
    val vectorNorm = sqrt(x * x + y * y + z * z)
    if (vectorNorm < 1e-12) return Vector3(0.0, 0.0, 0.0)
    val scale = 2.0 * acos(w.coerceIn(-1.0, 1.0)) / vectorNorm
    return Vector3(x * scale, y * scale, z * scale)
}

private fun inverseUnit(rotation: Rotation): Rotation {
    val norm = rotation.w * rotation.w + rotation.x * rotation.x +
        rotation.y * rotation.y + rotation.z * rotation.z
    require(norm.isFinite() && norm > 1e-12)
    return Rotation(rotation.w / norm, -rotation.x / norm,
        -rotation.y / norm, -rotation.z / norm)
}

private fun rotateVector(rotation: Rotation, value: Vector3): Vector3 {
    val q = Vector3(rotation.x, rotation.y, rotation.z)
    val t = scale(crossOffset(q, value), 2.0)
    return add(value, add(scale(t, rotation.w), crossOffset(q, t)))
}

private fun Vector3.finite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
private fun mean(values: List<Vector3>): Vector3 = if (values.isEmpty()) Vector3(0.0, 0.0, 0.0)
    else scale(values.reduce(::add), 1.0 / values.size)
private fun add(a: Vector3, b: Vector3) = Vector3(a.x + b.x, a.y + b.y, a.z + b.z)
private fun subtract(a: Vector3, b: Vector3) = Vector3(a.x - b.x, a.y - b.y, a.z - b.z)
private fun scale(value: Vector3, factor: Double) = Vector3(value.x * factor, value.y * factor, value.z * factor)
private fun dotOffset(a: Vector3, b: Vector3) = a.x * b.x + a.y * b.y + a.z * b.z
private fun crossOffset(a: Vector3, b: Vector3) = Vector3(a.y * b.z - a.z * b.y,
    a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
