package ai.arena.arkernel

import kotlin.math.abs
import kotlin.math.sqrt

private const val MAX_GRAVITY_SAMPLES = 512
private const val MAX_TRANSLATION_OBSERVATIONS = 256
private const val MAX_KNOWN_DISTANCE_OBSERVATIONS = 32

enum class MetricRuntimeReason {
    INSUFFICIENT_STATIONARY_IMU_SAMPLES,
    GRAVITY_MAGNITUDE_INVALID,
    STATIONARY_ACCELEROMETER_NOISE_HIGH,
    STATIONARY_GYROSCOPE_MOTION_HIGH,
    INSUFFICIENT_TRANSLATION_OBSERVATIONS,
    VISUAL_INERTIAL_DIRECTION_DISAGREES,
    VISUAL_INERTIAL_SCALE_ACCEPTANCE_LOW,
    SAMPLE_BUDGET_EXCEEDED,
}

enum class MetricTargetReason {
    INSUFFICIENT_KNOWN_DISTANCE_OBSERVATIONS,
    KNOWN_DISTANCE_ERROR_TOO_HIGH,
}

data class MetricConsistencyConfig(
    val minimumStationarySamples: Int = 80,
    val minimumTranslationObservations: Int = 12,
    val minimumKnownDistanceObservations: Int = 3,
    val expectedGravityMetersPerSecondSquared: Double = 9.80665,
    val maximumGravityMagnitudeError: Double = 0.5,
    val maximumStationaryAccelerationStdDev: Double = 0.25,
    val maximumStationaryGyroscopeRms: Double = 0.03,
    val minimumTranslationDirectionCosine: Double = 0.80,
    val minimumScaleAcceptanceRatio: Double = 0.70,
    val maximumKnownDistanceRelativeError: Double = 0.02,
    val maximumKnownDistanceAbsoluteErrorMeters: Double = 0.01,
) {
    init {
        require(minimumStationarySamples in 3..MAX_GRAVITY_SAMPLES)
        require(minimumTranslationObservations in 3..MAX_TRANSLATION_OBSERVATIONS)
        require(minimumKnownDistanceObservations in 1..MAX_KNOWN_DISTANCE_OBSERVATIONS)
        require(expectedGravityMetersPerSecondSquared in 5.0..15.0)
        require(maximumGravityMagnitudeError in 0.01..3.0)
        require(maximumStationaryAccelerationStdDev in 0.01..3.0)
        require(maximumStationaryGyroscopeRms in 0.001..1.0)
        require(minimumTranslationDirectionCosine in 0.0..1.0)
        require(minimumScaleAcceptanceRatio in 0.0..1.0)
        require(maximumKnownDistanceRelativeError in 0.001..0.50)
        require(maximumKnownDistanceAbsoluteErrorMeters in 0.001..1.0)
    }
}

data class MetricConsistencyReport(
    val stationarySamples: Int,
    val gravityMeanMetersPerSecondSquared: Double,
    val gravityStdDevMetersPerSecondSquared: Double,
    val gyroscopeRmsRadiansPerSecond: Double,
    val translationObservations: Int,
    val scaleAcceptedObservations: Int,
    val medianTranslationDirectionCosine: Double,
    val knownDistanceObservations: Int,
    val knownDistanceMeanAbsoluteErrorMeters: Double,
    val knownDistanceMaximumRelativeError: Double,
    val runtimeReasons: List<MetricRuntimeReason>,
    val targetReasons: List<MetricTargetReason>,
) {
    init {
        require(stationarySamples in 0..MAX_GRAVITY_SAMPLES)
        require(translationObservations in 0..MAX_TRANSLATION_OBSERVATIONS)
        require(scaleAcceptedObservations in 0..translationObservations)
        require(knownDistanceObservations in 0..MAX_KNOWN_DISTANCE_OBSERVATIONS)
        require(listOf(gravityMeanMetersPerSecondSquared,
            gravityStdDevMetersPerSecondSquared, gyroscopeRmsRadiansPerSecond,
            medianTranslationDirectionCosine, knownDistanceMeanAbsoluteErrorMeters,
            knownDistanceMaximumRelativeError).all { it.isFinite() && it >= 0.0 })
        require(medianTranslationDirectionCosine <= 1.0)
        require(runtimeReasons.distinct().size == runtimeReasons.size)
        require(targetReasons.distinct().size == targetReasons.size)
    }

    val runtimeConsistent: Boolean get() = runtimeReasons.isEmpty()
    /** Requires physical known-distance evidence in addition to runtime consistency. */
    val dimensionallyValidated: Boolean
        get() = runtimeConsistent && targetReasons.isEmpty()
}

/**
 * Guided runtime consistency check. Stationary IMU observations validate gravity units/noise;
 * measured native fusion results validate visual/inertial translation direction and scale-gate
 * frequency. Absolute dimensional validation is separate and requires caller-supplied physical
 * known-distance observations with uncertainty. This class never adjusts VIO state or calibration.
 */
class GuidedMetricConsistencyProbe(
    private val config: MetricConsistencyConfig = MetricConsistencyConfig(),
) {
    private data class ImuMagnitude(val acceleration: Double, val gyroscope: Double)
    private data class Translation(val directionCosine: Double, val scaleAccepted: Boolean)
    private data class KnownDistance(val measuredMeters: Double, val referenceMeters: Double,
        val referenceUncertaintyMeters: Double)

    private val stationary = ArrayList<ImuMagnitude>()
    private val translations = ArrayList<Translation>()
    private val knownDistances = ArrayList<KnownDistance>()
    private var budgetExceeded = false
    @Volatile private var completed: MetricConsistencyReport? = null

    val isFinished: Boolean get() = completed != null

    /** Non-stationary-looking values are ignored rather than poisoning the stationary window. */
    @Synchronized
    fun addImuSample(acceleration: Vector3, angularVelocity: Vector3): Boolean {
        if (completed != null || !acceleration.finiteMetric() || !angularVelocity.finiteMetric())
            return false
        val accelerationNorm = normMetric(acceleration)
        val gyroNorm = normMetric(angularVelocity)
        if (gyroNorm > 0.10 || accelerationNorm !in 5.0..15.0) return false
        if (stationary.size == MAX_GRAVITY_SAMPLES) { budgetExceeded = true; return false }
        stationary += ImuMagnitude(accelerationNorm, gyroNorm)
        return true
    }

    @Synchronized
    fun addVisualFusion(result: VisualFusionSnapshot): Boolean {
        if (completed != null || !result.predictedDisplacementMeters.isFinite() ||
            !result.translationDirectionCosine.isFinite() ||
            result.predictedDisplacementMeters <= 0.0 ||
            result.translationDirectionCosine !in -1.0..1.0) return false
        if (translations.size == MAX_TRANSLATION_OBSERVATIONS) {
            budgetExceeded = true
            return false
        }
        translations += Translation(result.translationDirectionCosine,
            result.scaleUpdated && result.positionApplied && result.observedScaleMeters > 0.0)
        return true
    }

    /** Reference uncertainty does not erase error; it only expands the physical pass envelope. */
    @Synchronized
    fun addKnownDistance(measuredMeters: Double, referenceMeters: Double,
        referenceUncertaintyMeters: Double): Boolean {
        if (completed != null || !measuredMeters.isFinite() || !referenceMeters.isFinite() ||
            !referenceUncertaintyMeters.isFinite() || measuredMeters <= 0.0 ||
            referenceMeters <= 0.0 || referenceUncertaintyMeters < 0.0 ||
            referenceUncertaintyMeters >= referenceMeters) return false
        if (knownDistances.size == MAX_KNOWN_DISTANCE_OBSERVATIONS) {
            budgetExceeded = true
            return false
        }
        knownDistances += KnownDistance(measuredMeters, referenceMeters,
            referenceUncertaintyMeters)
        return true
    }

    @Synchronized
    fun finish(): MetricConsistencyReport {
        completed?.let { return it }
        val accelerationValues = stationary.map { it.acceleration }
        val gravityMean = accelerationValues.averageOrZero()
        val gravityStdDev = stdDevMetric(accelerationValues, gravityMean)
        val gyroRms = if (stationary.isEmpty()) 0.0 else
            sqrt(stationary.sumOf { it.gyroscope * it.gyroscope } / stationary.size)
        val directionCosines = translations.map { it.directionCosine }
        val medianDirection = medianMetric(directionCosines).coerceAtLeast(0.0)
        val accepted = translations.count { it.scaleAccepted }
        val acceptanceRatio = if (translations.isEmpty()) 0.0 else
            accepted.toDouble() / translations.size

        val absoluteErrors = knownDistances.map { abs(it.measuredMeters - it.referenceMeters) }
        val meanAbsoluteError = absoluteErrors.averageOrZero()
        val maximumRelativeError = knownDistances.indices.maxOfOrNull { index ->
            val observation = knownDistances[index]
            val effectiveError = (absoluteErrors[index] - observation.referenceUncertaintyMeters)
                .coerceAtLeast(0.0)
            effectiveError / observation.referenceMeters
        } ?: 0.0
        val targetOutsideAbsoluteEnvelope = knownDistances.indices.any { index ->
            val observation = knownDistances[index]
            val allowed = config.maximumKnownDistanceAbsoluteErrorMeters +
                observation.referenceUncertaintyMeters
            absoluteErrors[index] > allowed &&
                absoluteErrors[index] / observation.referenceMeters >
                config.maximumKnownDistanceRelativeError
        }

        val runtimeReasons = mutableListOf<MetricRuntimeReason>()
        if (stationary.size < config.minimumStationarySamples)
            runtimeReasons += MetricRuntimeReason.INSUFFICIENT_STATIONARY_IMU_SAMPLES
        if (abs(gravityMean - config.expectedGravityMetersPerSecondSquared) >
            config.maximumGravityMagnitudeError)
            runtimeReasons += MetricRuntimeReason.GRAVITY_MAGNITUDE_INVALID
        if (gravityStdDev > config.maximumStationaryAccelerationStdDev)
            runtimeReasons += MetricRuntimeReason.STATIONARY_ACCELEROMETER_NOISE_HIGH
        if (gyroRms > config.maximumStationaryGyroscopeRms)
            runtimeReasons += MetricRuntimeReason.STATIONARY_GYROSCOPE_MOTION_HIGH
        if (translations.size < config.minimumTranslationObservations)
            runtimeReasons += MetricRuntimeReason.INSUFFICIENT_TRANSLATION_OBSERVATIONS
        if (medianDirection < config.minimumTranslationDirectionCosine)
            runtimeReasons += MetricRuntimeReason.VISUAL_INERTIAL_DIRECTION_DISAGREES
        if (acceptanceRatio < config.minimumScaleAcceptanceRatio)
            runtimeReasons += MetricRuntimeReason.VISUAL_INERTIAL_SCALE_ACCEPTANCE_LOW
        if (budgetExceeded) runtimeReasons += MetricRuntimeReason.SAMPLE_BUDGET_EXCEEDED

        val targetReasons = mutableListOf<MetricTargetReason>()
        if (knownDistances.size < config.minimumKnownDistanceObservations)
            targetReasons += MetricTargetReason.INSUFFICIENT_KNOWN_DISTANCE_OBSERVATIONS
        if (targetOutsideAbsoluteEnvelope ||
            maximumRelativeError > config.maximumKnownDistanceRelativeError)
            targetReasons += MetricTargetReason.KNOWN_DISTANCE_ERROR_TOO_HIGH
        return MetricConsistencyReport(stationary.size, gravityMean, gravityStdDev, gyroRms,
            translations.size, accepted, medianDirection, knownDistances.size,
            meanAbsoluteError, maximumRelativeError, runtimeReasons.distinct(),
            targetReasons.distinct()).also { completed = it }
    }
}

private fun Vector3.finiteMetric() = x.isFinite() && y.isFinite() && z.isFinite()
private fun normMetric(value: Vector3) = sqrt(value.x * value.x + value.y * value.y + value.z * value.z)
private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
private fun stdDevMetric(values: List<Double>, mean: Double) = if (values.isEmpty()) 0.0 else
    sqrt(values.sumOf { value -> (value - mean) * (value - mean) } / values.size)
private fun medianMetric(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle]
    else (sorted[middle - 1] + sorted[middle]) / 2.0
}
