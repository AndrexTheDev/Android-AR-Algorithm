package ai.arena.arkernel

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

private const val MAX_TARGET_OBSERVATIONS = 64

enum class CalibrationTargetReason {
    INSUFFICIENT_ACCEPTED_OBSERVATIONS,
    INSUFFICIENT_VIEW_DIVERSITY,
    INSUFFICIENT_DISTANCE_DIVERSITY,
    REPROJECTION_ERROR_TOO_HIGH,
    DIMENSIONAL_ERROR_TOO_HIGH,
    OBSERVATION_BUDGET_EXCEEDED,
}

data class CalibrationTargetCaptureConfig(
    val minimumAcceptedObservations: Int = 12,
    val minimumViewBins: Int = 5,
    val minimumDistanceBins: Int = 2,
    val viewBinDegrees: Double = 15.0,
    val distanceBinMeters: Double = 0.5,
    val minimumDetectedCorners: Int = 20,
    val minimumImageCoverageRatio: Double = 0.05,
    val maximumImageCoverageRatio: Double = 0.80,
    val minimumSharpnessScore: Double = 0.15,
    val maximumObservationReprojectionRmsePixels: Double = 2.0,
    val maximumMeanReprojectionRmsePixels: Double = 1.0,
    val maximumMeanAbsoluteErrorMeters: Double = 0.01,
    val maximumRelativeError: Double = 0.02,
) {
    init {
        require(minimumAcceptedObservations in 3..MAX_TARGET_OBSERVATIONS)
        require(minimumViewBins in 2..32 && minimumDistanceBins in 2..16)
        require(viewBinDegrees in 5.0..45.0 && distanceBinMeters in 0.05..5.0)
        require(minimumDetectedCorners in 4..10_000)
        require(minimumImageCoverageRatio in 0.001..0.5)
        require(maximumImageCoverageRatio in minimumImageCoverageRatio..1.0)
        require(minimumSharpnessScore in 0.0..1.0)
        require(maximumObservationReprojectionRmsePixels in 0.1..10.0)
        require(maximumMeanReprojectionRmsePixels in 0.05..5.0)
        require(maximumMeanAbsoluteErrorMeters in 0.001..1.0)
        require(maximumRelativeError in 0.001..0.5)
    }
}

data class CalibrationTargetObservation(
    val measuredDistanceMeters: Double,
    val referenceDistanceMeters: Double,
    val referenceUncertaintyMeters: Double,
    val targetCenterDistanceMeters: Double,
    val targetNormalCamera: Vector3,
    val detectedCorners: Int,
    val imageCoverageRatio: Double,
    val sharpnessScore: Double,
    val reprojectionRmsePixels: Double,
)

data class CalibrationTargetCaptureReport(
    val acceptedObservations: Int,
    val rejectedObservations: Int,
    val viewBins: Int,
    val distanceBins: Int,
    val meanReprojectionRmsePixels: Double,
    val meanAbsoluteErrorMeters: Double,
    val maximumRelativeError: Double,
    val maximumValidatedDistanceMeters: Double,
    val reasons: List<CalibrationTargetReason>,
) {
    init {
        require(acceptedObservations in 0..MAX_TARGET_OBSERVATIONS)
        require(rejectedObservations in 0..MAX_TARGET_OBSERVATIONS)
        require(acceptedObservations + rejectedObservations <= MAX_TARGET_OBSERVATIONS)
        require(viewBins >= 0 && distanceBins >= 0)
        require(listOf(meanReprojectionRmsePixels, meanAbsoluteErrorMeters,
            maximumRelativeError, maximumValidatedDistanceMeters)
            .all { it.isFinite() && it >= 0.0 })
        require(reasons.distinct().size == reasons.size)
    }

    val observable: Boolean get() = reasons.isEmpty()

    fun measurementEvidence(rotationObservable: Boolean, timeOffsetObservable: Boolean,
        metricRuntimeConsistent: Boolean): CalibrationMeasurementEvidence? =
        if (!observable) null else CalibrationMeasurementEvidence(acceptedObservations,
            meanAbsoluteErrorMeters, maximumRelativeError, meanReprojectionRmsePixels,
            maximumValidatedDistanceMeters, rotationObservable, timeOffsetObservable,
            metricRuntimeConsistent)
}

/**
 * Bounded target-capture quality/evidence accumulator. Detection and pose estimation remain
 * adapter-owned; this class refuses low-quality or repetitive observations and emits aggregate
 * evidence only. It does not solve intrinsics or sign a profile.
 */
class CalibrationTargetCaptureProbe(
    private val config: CalibrationTargetCaptureConfig = CalibrationTargetCaptureConfig(),
) {
    private data class Accepted(val absoluteError: Double, val effectiveRelativeError: Double,
        val centerDistance: Double, val reprojection: Double, val viewBin: Pair<Int, Int>,
        val distanceBin: Int)
    private val observations = ArrayList<Accepted>()
    private var attempts = 0
    private var rejected = 0
    private var budgetExceeded = false
    @Volatile private var completed: CalibrationTargetCaptureReport? = null

    @Synchronized
    fun add(observation: CalibrationTargetObservation): Boolean {
        if (completed != null) return false
        if (attempts == MAX_TARGET_OBSERVATIONS) { budgetExceeded = true; return false }
        attempts++
        val normal = normalizeTarget(observation.targetNormalCamera)
        val validScalars = listOf(observation.measuredDistanceMeters,
            observation.referenceDistanceMeters, observation.referenceUncertaintyMeters,
            observation.targetCenterDistanceMeters, observation.imageCoverageRatio,
            observation.sharpnessScore, observation.reprojectionRmsePixels).all { it.isFinite() }
        val accepted = validScalars && normal != null &&
            observation.measuredDistanceMeters > 0.0 &&
            observation.referenceDistanceMeters > 0.0 &&
            observation.referenceUncertaintyMeters >= 0.0 &&
            observation.referenceUncertaintyMeters / observation.referenceDistanceMeters <= 0.02 &&
            observation.targetCenterDistanceMeters > 0.0 &&
            observation.detectedCorners >= config.minimumDetectedCorners &&
            observation.imageCoverageRatio in config.minimumImageCoverageRatio..
                config.maximumImageCoverageRatio &&
            observation.sharpnessScore >= config.minimumSharpnessScore &&
            observation.reprojectionRmsePixels <=
                config.maximumObservationReprojectionRmsePixels
        if (!accepted) { rejected++; return false }
        val absoluteError = kotlin.math.abs(observation.measuredDistanceMeters -
            observation.referenceDistanceMeters)
        val effectiveRelativeError = (absoluteError - observation.referenceUncertaintyMeters)
            .coerceAtLeast(0.0) / observation.referenceDistanceMeters
        val azimuthDegrees = Math.toDegrees(atan2(normal!!.x, normal.z))
        val elevationDegrees = Math.toDegrees(asin(normal.y.coerceIn(-1.0, 1.0)))
        val viewBin = kotlin.math.floor(azimuthDegrees / config.viewBinDegrees).toInt() to
            kotlin.math.floor(elevationDegrees / config.viewBinDegrees).toInt()
        val distanceBin = kotlin.math.floor(observation.targetCenterDistanceMeters /
            config.distanceBinMeters).toInt()
        observations += Accepted(absoluteError, effectiveRelativeError,
            observation.targetCenterDistanceMeters, observation.reprojectionRmsePixels,
            viewBin, distanceBin)
        return true
    }

    @Synchronized
    fun finish(): CalibrationTargetCaptureReport {
        completed?.let { return it }
        val viewBins = observations.map { it.viewBin }.toSet().size
        val distanceBins = observations.map { it.distanceBin }.toSet().size
        val meanReprojection = observations.map { it.reprojection }.averageOrTargetZero()
        val meanAbsoluteError = observations.map { it.absoluteError }.averageOrTargetZero()
        val maximumRelativeError = observations.maxOfOrNull { it.effectiveRelativeError } ?: 0.0
        val maximumDistance = observations.maxOfOrNull { it.centerDistance } ?: 0.0
        val reasons = mutableListOf<CalibrationTargetReason>()
        if (observations.size < config.minimumAcceptedObservations)
            reasons += CalibrationTargetReason.INSUFFICIENT_ACCEPTED_OBSERVATIONS
        if (viewBins < config.minimumViewBins)
            reasons += CalibrationTargetReason.INSUFFICIENT_VIEW_DIVERSITY
        if (distanceBins < config.minimumDistanceBins)
            reasons += CalibrationTargetReason.INSUFFICIENT_DISTANCE_DIVERSITY
        if (meanReprojection > config.maximumMeanReprojectionRmsePixels)
            reasons += CalibrationTargetReason.REPROJECTION_ERROR_TOO_HIGH
        if (meanAbsoluteError > config.maximumMeanAbsoluteErrorMeters ||
            maximumRelativeError > config.maximumRelativeError)
            reasons += CalibrationTargetReason.DIMENSIONAL_ERROR_TOO_HIGH
        if (budgetExceeded) reasons += CalibrationTargetReason.OBSERVATION_BUDGET_EXCEEDED
        return CalibrationTargetCaptureReport(observations.size, rejected, viewBins, distanceBins,
            meanReprojection, meanAbsoluteError, maximumRelativeError, maximumDistance,
            reasons).also { completed = it }
    }
}

private fun normalizeTarget(value: Vector3): Vector3? {
    if (!value.x.isFinite() || !value.y.isFinite() || !value.z.isFinite()) return null
    val norm = sqrt(value.x * value.x + value.y * value.y + value.z * value.z)
    if (norm < 1e-9) return null
    return Vector3(value.x / norm, value.y / norm, value.z / norm)
}

private fun List<Double>.averageOrTargetZero() = if (isEmpty()) 0.0 else average()
