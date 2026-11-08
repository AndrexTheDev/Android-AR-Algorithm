package ai.arena.arkernel

import kotlin.math.acos
import kotlin.math.sqrt

private const val MAX_ROTATION_OBSERVATIONS = 512

enum class RotationObservabilityReason {
    INSUFFICIENT_MATCHED_ROTATIONS,
    INSUFFICIENT_GUIDED_MOTION,
    CAMERA_GYRO_DIRECTION_DISAGREES,
    CAMERA_GYRO_MAGNITUDE_DISAGREES,
    OBSERVATION_BUDGET_EXCEEDED,
}

data class RotationObservabilityConfig(
    val minimumMatchedRotations: Int = 12,
    val minimumTotalVisualRotationRadians: Double = 0.35,
    val minimumDirectionCosine: Double = 0.80,
    val minimumMagnitudeRatio: Double = 0.70,
    val maximumMagnitudeRatio: Double = 1.30,
    val minimumPairRotationRadians: Double = 0.005,
) {
    init {
        require(minimumMatchedRotations in 3..MAX_ROTATION_OBSERVATIONS)
        require(minimumTotalVisualRotationRadians in 0.05..20.0)
        require(minimumDirectionCosine in -1.0..1.0)
        require(minimumMagnitudeRatio in 0.01..1.0)
        require(maximumMagnitudeRatio in 1.0..10.0)
        require(minimumMagnitudeRatio <= maximumMagnitudeRatio)
        require(minimumPairRotationRadians in 0.0001..0.5)
    }
}

data class RotationObservabilityReport(
    val matchedRotations: Int,
    val totalVisualRotationRadians: Double,
    val totalPredictedRotationRadians: Double,
    val directionCosine: Double,
    val magnitudeRatio: Double,
    val reasons: List<RotationObservabilityReason>,
) {
    init {
        require(matchedRotations in 0..MAX_ROTATION_OBSERVATIONS)
        require(totalVisualRotationRadians.isFinite() && totalVisualRotationRadians >= 0.0)
        require(totalPredictedRotationRadians.isFinite() && totalPredictedRotationRadians >= 0.0)
        require(directionCosine.isFinite() && directionCosine in -1.0..1.0)
        require(magnitudeRatio.isFinite() && magnitudeRatio >= 0.0)
        require(reasons.distinct().size == reasons.size)
    }
    val passed: Boolean get() = reasons.isEmpty()
}

/**
 * Bounded guided check comparing visual frame-to-frame rotation with the IMU prediction that
 * was supplied to that exact frame. Both rotations use camera-current-from-camera-previous,
 * so a signed disagreement exposes bad camera/IMU axes, extrinsics, timing, or severe visual
 * distortion. This is an admission check, not a calibration estimator.
 */
class GuidedRotationObservabilityProbe(
    private val config: RotationObservabilityConfig = RotationObservabilityConfig(),
) {
    private data class PairVectors(val visual: Vector3, val predicted: Vector3)
    private val observations = ArrayList<PairVectors>()
    private var budgetExceeded = false
    @Volatile private var completed: RotationObservabilityReport? = null

    val isFinished: Boolean get() = completed != null

    @Synchronized
    fun addMatchedRotation(visualCurrentFromPrevious: Rotation,
        predictedCurrentFromPrevious: Rotation): Boolean {
        if (completed != null) return false
        if (observations.size == MAX_ROTATION_OBSERVATIONS) {
            budgetExceeded = true
            return false
        }
        val visual = rotationVector(visualCurrentFromPrevious) ?: return false
        val predicted = rotationVector(predictedCurrentFromPrevious) ?: return false
        if (norm(visual) < config.minimumPairRotationRadians ||
            norm(predicted) < config.minimumPairRotationRadians) return false
        observations += PairVectors(visual, predicted)
        return true
    }

    @Synchronized
    fun finish(): RotationObservabilityReport {
        completed?.let { return it }
        var visualNormSum = 0.0
        var predictedNormSum = 0.0
        var dotSum = 0.0
        var visualSquaredSum = 0.0
        var predictedSquaredSum = 0.0
        for (pair in observations) {
            visualNormSum += norm(pair.visual)
            predictedNormSum += norm(pair.predicted)
            dotSum += dot(pair.visual, pair.predicted)
            visualSquaredSum += dot(pair.visual, pair.visual)
            predictedSquaredSum += dot(pair.predicted, pair.predicted)
        }
        val denominator = sqrt(visualSquaredSum * predictedSquaredSum)
        val cosine = if (denominator > 0.0) (dotSum / denominator).coerceIn(-1.0, 1.0) else 0.0
        val ratio = if (visualNormSum > 0.0) predictedNormSum / visualNormSum else 0.0
        val reasons = mutableListOf<RotationObservabilityReason>()
        if (observations.size < config.minimumMatchedRotations)
            reasons += RotationObservabilityReason.INSUFFICIENT_MATCHED_ROTATIONS
        if (visualNormSum < config.minimumTotalVisualRotationRadians)
            reasons += RotationObservabilityReason.INSUFFICIENT_GUIDED_MOTION
        if (cosine < config.minimumDirectionCosine)
            reasons += RotationObservabilityReason.CAMERA_GYRO_DIRECTION_DISAGREES
        if (ratio < config.minimumMagnitudeRatio || ratio > config.maximumMagnitudeRatio)
            reasons += RotationObservabilityReason.CAMERA_GYRO_MAGNITUDE_DISAGREES
        if (budgetExceeded) reasons += RotationObservabilityReason.OBSERVATION_BUDGET_EXCEEDED
        return RotationObservabilityReport(observations.size, visualNormSum,
            predictedNormSum, cosine, ratio, reasons).also { completed = it }
    }
}

private fun rotationVector(rotation: Rotation): Vector3? {
    val values = listOf(rotation.w, rotation.x, rotation.y, rotation.z)
    if (values.any { !it.isFinite() }) return null
    val length = sqrt(values.sumOf { it * it })
    if (length < 1e-12) return null
    var w = rotation.w / length
    var x = rotation.x / length
    var y = rotation.y / length
    var z = rotation.z / length
    // q and -q encode the same rotation. Canonicalizing w keeps the shortest signed rotation.
    if (w < 0.0) { w = -w; x = -x; y = -y; z = -z }
    w = w.coerceIn(-1.0, 1.0)
    val vectorNorm = sqrt(x * x + y * y + z * z)
    if (vectorNorm < 1e-12) return Vector3(0.0, 0.0, 0.0)
    val angle = 2.0 * acos(w)
    val scale = angle / vectorNorm
    return Vector3(x * scale, y * scale, z * scale)
}

private fun dot(a: Vector3, b: Vector3): Double = a.x * b.x + a.y * b.y + a.z * b.z
private fun norm(value: Vector3): Double = sqrt(dot(value, value))
