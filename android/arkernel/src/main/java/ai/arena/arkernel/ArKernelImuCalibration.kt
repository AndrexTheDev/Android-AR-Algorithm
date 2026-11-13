package ai.arena.arkernel

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

private const val IMUCAL_HEADER = "ARKIMUCAL1"
private const val IMUCAL_VERSION = "1"
private const val IMUCAL_QUALITY = "unverified"
private const val IMUCAL_END = "END"
private const val IMUCAL_MAX_INPUT_CHARACTERS = 16_384
private const val IMUCAL_MAX_LINE_CHARACTERS = 256
private const val IMUCAL_MAX_STREAMS = 4
private const val IMUCAL_MAX_STATICS = 8
private const val IMUCAL_MAX_POSES = 12
private const val IMUCAL_MAX_TURNS = 16
private const val IMUCAL_MIN_TURN_DURATION_MS = 200L
private const val IMUCAL_MIN_TURN_SAMPLES = 2L
private const val IMUCAL_MIN_REPEAT_CYCLES = 1
private const val IMUCAL_MAX_REPEAT_CYCLES = 4
private const val IMUCAL_MAX_TEMPERATURE_SPAN_C = 80.0
private const val IMUCAL_MIN_GRAVITY_MPS2 = 5.0
private const val IMUCAL_MAX_GRAVITY_MPS2 = 15.0
private const val IMUCAL_MAX_PERMILLE = 1_000
private const val IMUCAL_MIN_DURATION_MS = 1_000L
private const val IMUCAL_MAX_DURATION_MS = 600_000L
private const val IMUCAL_MAX_SAMPLES = 1_000_000L
private const val IMUCAL_MAX_ACCEL_MPS2 = 200.0
private const val IMUCAL_MAX_GYRO_DPS = 100.0
private const val IMUCAL_MAX_RATE_DPS = 100_000.0
private const val IMUCAL_MAX_ANGLE_DEG = 1_000.0
private const val IMUCAL_MIN_REFERENCE_ANGLE_DEG = 1.0
private const val IMUCAL_MAX_REFERENCE_ANGLE_DEG = 720.0
private const val IMUCAL_MIN_TEMPERATURE_C = -40.0
private const val IMUCAL_MAX_TEMPERATURE_C = 100.0
private const val IMUCAL_MAX_GAP_MS = 1_000_000L
private const val IMUCAL_MAX_DT_US = 1_000_000_000L
private const val IMUCAL_MAX_HZ = 10_000
private const val IMUCAL_ROUND_EPSILON = 1e-9
private const val IMUCAL_DRIFT_SECONDS = 60.0

private val IMUCAL_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val IMUCAL_UNSIGNED = Regex("0|[1-9][0-9]*")
private val IMUCAL_DECIMAL =
    Regex("(0|[1-9][0-9]{0,9})(\\.[0-9]{1,6})?")
private val IMUCAL_SIGNED =
    Regex("-?(0|[1-9][0-9]{0,9})(\\.[0-9]{1,6})?")
private val IMUCAL_BOOLEAN = Regex("true|false")

private val IMUCAL_TRUE_KEYS = listOf(
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "manual_upload_only",
    "images_disabled",
)

private val IMUCAL_FALSE_KEYS = listOf("metric_accuracy_claimed")

private val IMUCAL_KEYS = listOf(
    "version",
    "session_id",
    "device_token",
    "reference_gravity_mps2",
    "required_temperature_span_c",
    "min_repeat_cycles",
    "min_static_duration_ms",
    "max_gap_ms",
    "max_rate_error_permille",
    "max_turn_rate_dps",
    "max_gravity_error_mps2",
    "max_accel_bias_mps2",
    "max_accel_scale_error_permille",
    "max_accel_misalignment_permille",
    "max_accel_residual_mps2",
    "max_accel_noise_mg_sqrt_hz",
    "max_gyro_bias_dps",
    "max_gyro_scale_error_permille",
    "max_cross_axis_permille",
    "max_gyro_arw_deg_sqrt_hr",
    "max_accel_bias_stability_mg",
    "max_gyro_bias_stability_dph",
    "stream_count",
    "static_count",
    "pose_count",
    "turn_count",
    "static_sample_total",
    "pose_sample_total",
    "observed_max_gap_ms",
    "observed_max_rate_error_permille",
    "observed_peak_rate_dps",
    "gravity_magnitude_error_mps2",
    "accel_bias_x_mps2",
    "accel_bias_y_mps2",
    "accel_bias_z_mps2",
    "accel_bias_norm_mps2",
    "accel_scale_x_permille",
    "accel_scale_y_permille",
    "accel_scale_z_permille",
    "accel_scale_error_max_permille",
    "accel_misalignment_max_permille",
    "accel_residual_rms_mps2",
    "accel_repeat_cycles",
    "accel_noise_density_mg_sqrt_hz",
    "accel_bias_stability_mg",
    "gyro_bias_x_dps",
    "gyro_bias_y_dps",
    "gyro_bias_z_dps",
    "gyro_bias_norm_dps",
    "gyro_bias_spread_dps",
    "gyro_scale_error_max_permille",
    "gyro_cross_axis_max_permille",
    "gyro_arw_deg_sqrt_hr",
    "gyro_bias_stability_dph",
    "angle_drift_deg_per_min",
    "position_drift_m_per_min",
    "temperature_span_c",
    "cadence_ok",
    "duration_ok",
    "gravity_ok",
    "accel_fit_ok",
    "accel_repeat_checked",
    "gyro_scale_ok",
    "bias_ok",
    "noise_ok",
    "stability_ok",
    "thermal_covered",
    "calibration_sufficient",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "manual_upload_only",
    "images_disabled",
    "metric_accuracy_claimed",
    "quality_grade",
)

private val IMUCAL_STATISTIC_KEYS = listOf(
    "static_sample_total",
    "pose_sample_total",
    "observed_max_gap_ms",
    "observed_max_rate_error_permille",
    "observed_peak_rate_dps",
    "gravity_magnitude_error_mps2",
    "accel_bias_x_mps2",
    "accel_bias_y_mps2",
    "accel_bias_z_mps2",
    "accel_bias_norm_mps2",
    "accel_scale_x_permille",
    "accel_scale_y_permille",
    "accel_scale_z_permille",
    "accel_scale_error_max_permille",
    "accel_misalignment_max_permille",
    "accel_residual_rms_mps2",
    "accel_repeat_cycles",
    "accel_noise_density_mg_sqrt_hz",
    "accel_bias_stability_mg",
    "gyro_bias_x_dps",
    "gyro_bias_y_dps",
    "gyro_bias_z_dps",
    "gyro_bias_norm_dps",
    "gyro_bias_spread_dps",
    "gyro_scale_error_max_permille",
    "gyro_cross_axis_max_permille",
    "gyro_arw_deg_sqrt_hr",
    "gyro_bias_stability_dph",
    "angle_drift_deg_per_min",
    "position_drift_m_per_min",
    "temperature_span_c",
)

private val IMUCAL_VERDICT_KEYS = listOf(
    "cadence_ok",
    "duration_ok",
    "gravity_ok",
    "accel_fit_ok",
    "accel_repeat_checked",
    "gyro_scale_ok",
    "bias_ok",
    "noise_ok",
    "stability_ok",
    "thermal_covered",
    "calibration_sufficient",
)

enum class ArKernelImuTimestampDomain {
    ELAPSED_REALTIME,
    UPTIME,
    WALL,
    UNKNOWN,
    ;

    fun wireName(): String = name.lowercase(Locale.US)

    companion object {
        fun fromWire(value: String): ArKernelImuTimestampDomain? =
            entries.firstOrNull { it.wireName() == value }
    }
}

/**
 * Which device axis points **up** during a static pose.
 *
 * The device frame is the Android sensor frame: +X to the right of the screen,
 * +Y towards the top of the screen, +Z out of the front face.
 */
enum class ArKernelImuPoseDirection {
    POSITIVE_X,
    NEGATIVE_X,
    POSITIVE_Y,
    NEGATIVE_Y,
    POSITIVE_Z,
    NEGATIVE_Z,
    ;

    fun wireName(): String = when (this) {
        POSITIVE_X -> "px"
        NEGATIVE_X -> "nx"
        POSITIVE_Y -> "py"
        NEGATIVE_Y -> "ny"
        POSITIVE_Z -> "pz"
        NEGATIVE_Z -> "nz"
    }

    /** +1 when the named axis points up, -1 when it points down. */
    fun sign(): Double = when (this) {
        POSITIVE_X, POSITIVE_Y, POSITIVE_Z -> 1.0
        NEGATIVE_X, NEGATIVE_Y, NEGATIVE_Z -> -1.0
    }

    /** 0 for X, 1 for Y, 2 for Z. */
    fun axisIndex(): Int = when (this) {
        POSITIVE_X, NEGATIVE_X -> 0
        POSITIVE_Y, NEGATIVE_Y -> 1
        POSITIVE_Z, NEGATIVE_Z -> 2
    }

    /** The opposite orientation of the same axis. */
    fun opposite(): ArKernelImuPoseDirection = when (this) {
        POSITIVE_X -> NEGATIVE_X
        NEGATIVE_X -> POSITIVE_X
        POSITIVE_Y -> NEGATIVE_Y
        NEGATIVE_Y -> POSITIVE_Y
        POSITIVE_Z -> NEGATIVE_Z
        NEGATIVE_Z -> POSITIVE_Z
    }

    companion object {
        fun fromWire(value: String): ArKernelImuPoseDirection? =
            entries.firstOrNull { it.wireName() == value }
    }
}

enum class ArKernelImuTurnAxis {
    X,
    Y,
    Z,
    ;

    fun wireName(): String = name.lowercase(Locale.US)

    fun axisIndex(): Int = when (this) {
        X -> 0
        Y -> 1
        Z -> 2
    }

    companion object {
        fun fromWire(value: String): ArKernelImuTurnAxis? =
            entries.firstOrNull { it.wireName() == value }
    }
}

enum class ArKernelImuCalibrationRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_BLOCK,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_UNSIGNED,
    INVALID_DECIMAL,
    INVALID_TOKEN,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_COUNT,
    INVALID_ORDER,
    INVALID_ROW,
    INVALID_DOMAIN,
    INVALID_DIRECTION,
    INVALID_AXIS,
    INVALID_TEMPERATURE,
    INVALID_DURATION,
    STATISTICS_MISMATCH,
    CALIBRATION_MISMATCH,
}

/**
 * One recorded sensor stream: what rate the platform promised and what it
 * actually delivered.
 *
 * Everything here is a property of the *delivery*, not of the physics: an
 * integration that assumes 200 Hz but receives 190 Hz with occasional 40 ms
 * holes is wrong before any calibration is applied. `gapCount`,
 * `duplicateCount` and `monotonicViolations` are counted over the whole
 * recording, and `domain` records which clock the timestamps came from — a
 * stream whose clock is not the one the kernel aligns against cannot be
 * merged with another stream at all.
 */
data class ArKernelImuStreamRow(
    val index: Int,
    val label: String,
    val nominalHz: Int,
    val measuredHz: Double,
    val minDtUs: Long,
    val maxDtUs: Long,
    val gapCount: Int,
    val duplicateCount: Int,
    val monotonicViolations: Int,
    val domain: ArKernelImuTimestampDomain,
) {
    /** Relative rate error in per mille of the nominal rate. */
    val rateErrorPermille: Double =
        if (nominalHz == 0) 0.0
        else kotlin.math.abs(measuredHz - nominalHz) * 1_000.0 / nominalHz
}

/**
 * One stationary recording: the device at rest for `durationMs`.
 *
 * `rmsAcc` and `rmsGyr` are the isotropic noise of the two triads — the square
 * root of the mean per-axis variance — and `stabAcc` / `stabGyr` are the
 * standard deviation of block means, which is what "bias stability" means
 * here: how much the average wanders, not how noisy a single sample is.
 */
data class ArKernelImuStaticRow(
    val index: Int,
    val label: String,
    val durationMs: Long,
    val sampleCount: Long,
    val gyroSampleCount: Long,
    val meanGyroXDps: Double,
    val meanGyroYDps: Double,
    val meanGyroZDps: Double,
    val rmsAccMps2: Double,
    val rmsGyroDps: Double,
    val stabilityAccMps2: Double,
    val stabilityGyroDps: Double,
    val maxGapMs: Long,
    val temperatureC: Double,
) {
    /** Effective accelerometer sampling rate of this recording, in hertz. */
    val rateHz: Double =
        if (durationMs <= 0L) 0.0 else sampleCount / (durationMs / 1_000.0)

    /** Effective gyroscope sampling rate of this recording, in hertz. */
    val gyroRateHz: Double =
        if (durationMs <= 0L) 0.0 else gyroSampleCount / (durationMs / 1_000.0)
}

/**
 * One static pose of the six-position tumble.
 *
 * `mean` is the accelerometer reading averaged over the pose; with the device
 * at rest its only true input is gravity along the axis named by `direction`.
 */
data class ArKernelImuPoseRow(
    val index: Int,
    val label: String,
    val direction: ArKernelImuPoseDirection,
    val sampleCount: Long,
    val meanXMps2: Double,
    val meanYMps2: Double,
    val meanZMps2: Double,
    val meanGyroXDps: Double,
    val meanGyroYDps: Double,
    val meanGyroZDps: Double,
    val temperatureC: Double,
) {
    fun mean(): DoubleArray =
        doubleArrayOf(meanXMps2, meanYMps2, meanZMps2)

    fun meanGyro(): DoubleArray =
        doubleArrayOf(meanGyroXDps, meanGyroYDps, meanGyroZDps)
}

/**
 * One rotation through a known angle.
 *
 * `integratedAngleDeg` is what the gyroscope integrated about `axis` over the
 * manoeuvre and `referenceAngleDeg` is the angle the jig actually imposed;
 * the ratio of the two is the gyroscope scale factor. `offAxisAngleDeg` is the
 * magnitude of the angle integrated about the other two axes, which should be
 * zero and is not: it is the cross-axis coupling.
 */
data class ArKernelImuTurnRow(
    val index: Int,
    val label: String,
    val axis: ArKernelImuTurnAxis,
    val referenceAngleDeg: Double,
    val integratedAngleDeg: Double,
    val offAxisAngleDeg: Double,
    val durationMs: Long,
    val sampleCount: Long,
    val peakRateDps: Double,
) {
    /** Scale-factor error in per mille of the reference angle. */
    val scaleErrorPermille: Double =
        (integratedAngleDeg - referenceAngleDeg) / referenceAngleDeg * 1_000.0

    /** Cross-axis coupling in per mille of the reference angle. */
    val crossAxisPermille: Double =
        kotlin.math.abs(offAxisAngleDeg) /
            kotlin.math.abs(referenceAngleDeg) * 1_000.0
}

/**
 * A bounded physical calibration of one device's inertial sensors.
 *
 * The accelerometer model is fitted in closed form from the six-position
 * tumble: with `a = M · gAxis + b`, the mean of two opposite poses gives `b`
 * (gravity cancels) and their difference gives one column of `M`. All twelve
 * parameters — three biases, three scale factors, six misalignment terms —
 * come out of six poses, and the deviation of repeated poses measures whether
 * the fit means anything.
 *
 * The gyroscope scale factor comes from rotations through an angle the
 * operator imposed with a jig, never from the gyroscope itself.
 *
 * Two numbers are derived rather than measured and are reported because they
 * decide what the kernel may attempt: `angleDriftDegPerMin` is how fast an
 * uncorrected orientation runs away, and `positionDriftMPerMin` is how far an
 * uncorrected position runs away in a minute of free inertial integration.
 *
 * What this cannot do is verify the camera. No image is captured, no extrinsic
 * is estimated, no time offset is searched, and no quality grade is raised:
 * a good inertial calibration is a precondition for a working visual-inertial
 * system, not evidence that one works.
 */
data class ArKernelImuCalibration(
    val sessionId: String,
    val deviceToken: String,
    val referenceGravityMps2: Double,
    val requiredTemperatureSpanC: Double,
    val minRepeatCycles: Int,
    val minStaticDurationMs: Long,
    val maxGapMs: Long,
    val maxRateErrorPermille: Int,
    val maxTurnRateDps: Double,
    val maxGravityErrorMps2: Double,
    val maxAccelBiasMps2: Double,
    val maxAccelScaleErrorPermille: Int,
    val maxAccelMisalignmentPermille: Int,
    val maxAccelResidualMps2: Double,
    val maxAccelNoiseMgSqrtHz: Double,
    val maxGyroBiasDps: Double,
    val maxGyroScaleErrorPermille: Int,
    val maxCrossAxisPermille: Int,
    val maxGyroArwDegSqrtHr: Double,
    val maxAccelBiasStabilityMg: Double,
    val maxGyroBiasStabilityDph: Double,
    val streams: List<ArKernelImuStreamRow>,
    val statics: List<ArKernelImuStaticRow>,
    val poses: List<ArKernelImuPoseRow>,
    val turns: List<ArKernelImuTurnRow>,
) {

    val streamCount: Int get() = streams.size
    val staticCount: Int get() = statics.size
    val poseCount: Int get() = poses.size
    val turnCount: Int get() = turns.size

    // ---- 1. cadence -------------------------------------------------
    val observedMaxGapMs: Long =
        imucalMaxOf(
            streams.map { it.maxDtUs / 1_000.0 } +
                statics.map { it.maxGapMs.toDouble() },
        ).let { ceil(it - IMUCAL_ROUND_EPSILON).toLong() }

    val observedMaxRateErrorPermille: Int =
        ceil(
            (streams.maxOfOrNull { it.rateErrorPermille } ?: 0.0) -
                IMUCAL_ROUND_EPSILON,
        ).toInt()

    val cadenceClean: Boolean
        get() = streams.all {
            it.gapCount == 0 && it.duplicateCount == 0 &&
                it.monotonicViolations == 0
        }

    val cadenceOk: Boolean
        get() = streams.isNotEmpty() && cadenceClean &&
            observedMaxGapMs <= maxGapMs &&
            observedMaxRateErrorPermille <= maxRateErrorPermille

    // ---- 2. durations -----------------------------------------------
    val durationOk: Boolean
        get() = statics.isNotEmpty() && turns.isNotEmpty() &&
            statics.all { it.durationMs >= minStaticDurationMs } &&
            turns.all {
                it.durationMs >= IMUCAL_MIN_TURN_DURATION_MS &&
                    it.sampleCount >= IMUCAL_MIN_TURN_SAMPLES
            }

    // ---- 3. static recordings ---------------------------------------
    private val staticWeight: Long get() =
        statics.sumOf { it.sampleCount }

    private val gyroBiasVector: DoubleArray =
        imucalWeightedVector(
            statics.map {
                Pair(
                    doubleArrayOf(
                        it.meanGyroXDps, it.meanGyroYDps, it.meanGyroZDps,
                    ),
                    it.gyroSampleCount,
                )
            },
        )

    val staticSampleTotal: Long get() = staticWeight

    val gyroBiasXDps: Double get() = gyroBiasVector[0]
    val gyroBiasYDps: Double get() = gyroBiasVector[1]
    val gyroBiasZDps: Double get() = gyroBiasVector[2]
    val gyroBiasNormDps: Double get() = imucalNorm(gyroBiasVector)

    /** White-noise density of the accelerometer, in mg per root hertz. */
    val accelNoiseDensityMgSqrtHz: Double
        get() = imucalWeightedScalar(
            statics.map {
                Pair(
                    it.rmsAccMps2 * imucalNoiseFactor(it.rateHz),
                    it.sampleCount,
                )
            },
        ) * 1_000.0

    /** White-noise density of the gyroscope, in deg/s per root hertz. */
    private val gyroNoiseDensityDpsSqrtHz: Double
        get() = imucalWeightedScalar(
            statics.map {
                Pair(
                    it.rmsGyroDps * imucalNoiseFactor(it.gyroRateHz),
                    it.gyroSampleCount,
                )
            },
        )

    /** Angle random walk, in degrees per root hour. */
    val gyroArwDegSqrtHr: Double
        get() = gyroNoiseDensityDpsSqrtHz * 60.0

    val accelBiasStabilityMg: Double
        get() = imucalWeightedScalar(
            statics.map { Pair(it.stabilityAccMps2, it.sampleCount) },
        ) * 1_000.0

    val gyroBiasStabilityDph: Double
        get() = imucalWeightedScalar(
            statics.map { Pair(it.stabilityGyroDps, it.gyroSampleCount) },
        ) * 3_600.0

    private val sigmaAccMps2: Double
        get() = imucalWeightedScalar(
            statics.map { Pair(it.rmsAccMps2, it.sampleCount) },
        )

    // ---- 4. six-position tumble --------------------------------------
    val poseSampleTotal: Long get() = poses.sumOf { it.sampleCount }

    private val posesByDirection: Map<ArKernelImuPoseDirection, List<ArKernelImuPoseRow>>
        get() = ArKernelImuPoseDirection.entries.associateWith { direction ->
            poses.filter { it.direction == direction }
        }

    val sixDirectionsPresent: Boolean
        get() = posesByDirection.values.all { it.isNotEmpty() }

    val accelRepeatCycles: Int
        get() = posesByDirection.values
            .filter { it.isNotEmpty() }
            .minOfOrNull { it.size } ?: 0

    private val directionMeanAcc: Map<ArKernelImuPoseDirection, DoubleArray>
        get() = posesByDirection.mapValues { entry ->
            imucalWeightedVector(
                entry.value.map { Pair(it.mean(), it.sampleCount) },
            )
        }

    private val directionMeanGyro: Map<ArKernelImuPoseDirection, DoubleArray>
        get() = posesByDirection.mapValues { entry ->
            imucalWeightedVector(
                entry.value.map { Pair(it.meanGyro(), it.sampleCount) },
            )
        }

    private val accelBiasVector: DoubleArray
        get() {
            if (!sixDirectionsPresent) return doubleArrayOf(0.0, 0.0, 0.0)
            val means = directionMeanAcc
            return DoubleArray(3) { axis ->
                ArKernelImuPoseDirection.entries.sumOf {
                    means.getValue(it)[axis]
                } / 6.0
            }
        }

    val accelBiasXMps2: Double get() = accelBiasVector[0]
    val accelBiasYMps2: Double get() = accelBiasVector[1]
    val accelBiasZMps2: Double get() = accelBiasVector[2]
    val accelBiasNormMps2: Double get() = imucalNorm(accelBiasVector)

    /**
     * Column `axis` of the accelerometer matrix `M`: the response the sensor
     * produces when gravity acts along that device axis.
     */
    private fun accelColumn(axis: Int): DoubleArray {
        if (!sixDirectionsPresent) return doubleArrayOf(0.0, 0.0, 0.0)
        val means = directionMeanAcc
        val positive = ArKernelImuPoseDirection.entries
            .first { it.axisIndex() == axis && it.sign() > 0.0 }
        val negative = positive.opposite()
        val up = means.getValue(positive)
        val down = means.getValue(negative)
        val denominator = 2.0 * referenceGravityMps2
        return DoubleArray(3) { i -> (up[i] - down[i]) / denominator }
    }

    val accelScaleXPermille: Double
        get() = if (!sixDirectionsPresent) 0.0
        else (accelColumn(0)[0] - 1.0) * 1_000.0

    val accelScaleYPermille: Double
        get() = if (!sixDirectionsPresent) 0.0
        else (accelColumn(1)[1] - 1.0) * 1_000.0

    val accelScaleZPermille: Double
        get() = if (!sixDirectionsPresent) 0.0
        else (accelColumn(2)[2] - 1.0) * 1_000.0

    val accelScaleErrorMaxPermille: Int
        get() {
            if (!sixDirectionsPresent) return 0
            return ceil(
                maxOf(
                    kotlin.math.abs(accelScaleXPermille),
                    kotlin.math.abs(accelScaleYPermille),
                    kotlin.math.abs(accelScaleZPermille),
                ) - IMUCAL_ROUND_EPSILON,
            ).toInt()
        }

    val accelMisalignmentMaxPermille: Int
        get() {
            if (!sixDirectionsPresent) return 0
            val x = accelColumn(0)
            val y = accelColumn(1)
            val z = accelColumn(2)
            val worst = maxOf(
                kotlin.math.abs(x[1]), kotlin.math.abs(x[2]),
                kotlin.math.abs(y[0]), kotlin.math.abs(y[2]),
                kotlin.math.abs(z[0]), kotlin.math.abs(z[1]),
            )
            return ceil(worst * 1_000.0 - IMUCAL_ROUND_EPSILON).toInt()
        }

    /**
     * How far the at-rest reading is from the declared local gravity, after
     * the fitted bias is removed. This is the one check that cannot be
     * faked by the fit: every column is derived from the same data, but its
     * *length* has to come out equal to `g`.
     */
    val gravityMagnitudeErrorMps2: Double
        get() {
            if (!sixDirectionsPresent) return 0.0
            val means = directionMeanAcc
            val bias = accelBiasVector
            var total = 0.0
            for (direction in ArKernelImuPoseDirection.entries) {
                val mean = means.getValue(direction)
                val centred = DoubleArray(3) { i -> mean[i] - bias[i] }
                val error = imucalNorm(centred) - referenceGravityMps2
                total += error * error
            }
            return sqrt(total / 6.0)
        }

    /** Deviation of each pose from the mean of its own direction. */
    val accelResidualRmsMps2: Double
        get() {
            if (poses.isEmpty()) return 0.0
            val means = directionMeanAcc
            var total = 0.0
            for (pose in poses) {
                val mean = means.getValue(pose.direction)
                val delta = pose.mean()
                var squared = 0.0
                for (i in 0..2) {
                    val d = delta[i] - mean[i]
                    squared += d * d
                }
                total += squared
            }
            return sqrt(total / poses.size)
        }

    /** How much the gyroscope bias moves between orientations. */
    val gyroBiasSpreadDps: Double
        get() {
            if (!sixDirectionsPresent) return 0.0
            val means = directionMeanGyro
            val centre = DoubleArray(3) { axis ->
                ArKernelImuPoseDirection.entries.sumOf {
                    means.getValue(it)[axis]
                } / 6.0
            }
            var worst = 0.0
            for (direction in ArKernelImuPoseDirection.entries) {
                val mean = means.getValue(direction)
                val delta = DoubleArray(3) { i -> mean[i] - centre[i] }
                worst = maxOf(worst, imucalNorm(delta))
            }
            return worst
        }

    // ---- 5. guided rotations ------------------------------------------
    val gyroScaleErrorMaxPermille: Int
        get() = ceil(
            (
                turns.map { kotlin.math.abs(it.scaleErrorPermille) }
                    .maxOrNull() ?: 0.0
                ) - IMUCAL_ROUND_EPSILON,
        ).toInt()

    val gyroCrossAxisMaxPermille: Int
        get() = ceil(
            (turns.map { it.crossAxisPermille }.maxOrNull() ?: 0.0) -
                IMUCAL_ROUND_EPSILON,
        ).toInt()

    val observedPeakRateDps: Double
        get() = turns.map { it.peakRateDps }.maxOrNull() ?: 0.0

    val turnRateClipped: Boolean
        get() = turns.any { it.peakRateDps > maxTurnRateDps }

    // ---- 6. temperature ------------------------------------------------
    val temperatureSpanC: Double
        get() {
            val values = statics.map { it.temperatureC } +
                poses.map { it.temperatureC }
            val high = values.maxOrNull() ?: return 0.0
            val low = values.minOrNull() ?: return 0.0
            return high - low
        }

    // ---- 7. free-integration drift -------------------------------------
    /**
     * Orientation error after one minute of uncorrected gyroscope
     * integration: the bias term grows linearly, the noise term as the square
     * root of time.
     */
    val angleDriftDegPerMin: Double
        get() {
            val bias = gyroBiasNormDps * IMUCAL_DRIFT_SECONDS
            val walk = gyroNoiseDensityDpsSqrtHz *
                sqrt(IMUCAL_DRIFT_SECONDS)
            return sqrt(bias * bias + walk * walk)
        }

    /**
     * Position error after one minute of uncorrected double integration:
     * a residual acceleration grows as t^2/2, accelerometer white noise as
     * t^1.5 / sqrt(3).
     */
    val positionDriftMPerMin: Double
        get() {
            val seconds = IMUCAL_DRIFT_SECONDS
            val residual = 0.5 * accelResidualRmsMps2 * seconds * seconds
            val walk = sigmaAccMps2 * seconds * sqrt(seconds) / sqrt(3.0)
            return sqrt(residual * residual + walk * walk)
        }

    // ---- verdicts --------------------------------------------------------
    val gravityOk: Boolean
        get() = sixDirectionsPresent &&
            gravityMagnitudeErrorMps2 <= maxGravityErrorMps2

    val accelFitOk: Boolean
        get() = sixDirectionsPresent &&
            accelBiasNormMps2 <= maxAccelBiasMps2 &&
            accelScaleErrorMaxPermille <= maxAccelScaleErrorPermille &&
            accelMisalignmentMaxPermille <= maxAccelMisalignmentPermille

    val accelRepeatChecked: Boolean
        get() = accelRepeatCycles >= minRepeatCycles &&
            accelResidualRmsMps2 <= maxAccelResidualMps2

    val gyroScaleOk: Boolean
        get() = turns.isNotEmpty() && !turnRateClipped &&
            gyroScaleErrorMaxPermille <= maxGyroScaleErrorPermille &&
            gyroCrossAxisMaxPermille <= maxCrossAxisPermille

    val biasOk: Boolean
        get() = statics.isNotEmpty() && gyroBiasNormDps <= maxGyroBiasDps

    val noiseOk: Boolean
        get() = statics.isNotEmpty() &&
            accelNoiseDensityMgSqrtHz <= maxAccelNoiseMgSqrtHz &&
            gyroArwDegSqrtHr <= maxGyroArwDegSqrtHr

    val stabilityOk: Boolean
        get() = statics.isNotEmpty() &&
            accelBiasStabilityMg <= maxAccelBiasStabilityMg &&
            gyroBiasStabilityDph <= maxGyroBiasStabilityDph

    val thermalCovered: Boolean
        get() = temperatureSpanC >= requiredTemperatureSpanC

    val calibrationSufficient: Boolean
        get() = cadenceOk && durationOk && gravityOk && accelFitOk &&
            accelRepeatChecked && gyroScaleOk && biasOk && noiseOk &&
            stabilityOk && thermalCovered

    /** The unmatched part of the campaign, for the operator to finish. */
    fun outstandingSteps(): List<String> {
        val steps = ArrayList<String>()
        if (streams.isEmpty()) steps += "cadence"
        if (statics.isEmpty()) steps += "static"
        if (!sixDirectionsPresent) steps += "poses"
        if (accelRepeatCycles < minRepeatCycles) steps += "repeat"
        if (turns.isEmpty()) steps += "turns"
        if (turnRateClipped) steps += "turn_rate"
        if (!thermalCovered) steps += "temperature"
        return steps
    }
}

data class ArKernelImuCalibrationSummary(
    val streamCount: Int,
    val staticCount: Int,
    val poseCount: Int,
    val turnCount: Int,
    val staticSampleTotal: Long,
    val poseSampleTotal: Long,
    val observedMaxGapMs: Long,
    val observedMaxRateErrorPermille: Int,
    val observedPeakRateDps: Double,
    val gravityMagnitudeErrorMps2: Double,
    val accelBiasXMps2: Double,
    val accelBiasYMps2: Double,
    val accelBiasZMps2: Double,
    val accelBiasNormMps2: Double,
    val accelScaleXPermille: Double,
    val accelScaleYPermille: Double,
    val accelScaleZPermille: Double,
    val accelScaleErrorMaxPermille: Int,
    val accelMisalignmentMaxPermille: Int,
    val accelResidualRmsMps2: Double,
    val accelRepeatCycles: Int,
    val accelNoiseDensityMgSqrtHz: Double,
    val accelBiasStabilityMg: Double,
    val gyroBiasXDps: Double,
    val gyroBiasYDps: Double,
    val gyroBiasZDps: Double,
    val gyroBiasNormDps: Double,
    val gyroBiasSpreadDps: Double,
    val gyroScaleErrorMaxPermille: Int,
    val gyroCrossAxisMaxPermille: Int,
    val gyroArwDegSqrtHr: Double,
    val gyroBiasStabilityDph: Double,
    val angleDriftDegPerMin: Double,
    val positionDriftMPerMin: Double,
    val temperatureSpanC: Double,
    val cadenceOk: Boolean,
    val durationOk: Boolean,
    val gravityOk: Boolean,
    val accelFitOk: Boolean,
    val accelRepeatChecked: Boolean,
    val gyroScaleOk: Boolean,
    val biasOk: Boolean,
    val noiseOk: Boolean,
    val stabilityOk: Boolean,
    val thermalCovered: Boolean,
    val calibrationSufficient: Boolean,
    val outstanding: List<String>,
)

data class ArKernelImuCalibrationResult(
    val calibration: ArKernelImuCalibration?,
    val rejection: ArKernelImuCalibrationRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelImuCalibrationRejection.NONE &&
            calibration != null
}

data class ArKernelImuCalibrationParseResult(
    val calibration: ArKernelImuCalibration?,
    val rejection: ArKernelImuCalibrationRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelImuCalibrationRejection.NONE &&
            calibration != null
}

/**
 * Canonical `ARKIMUCAL1` blocks of inertial intrinsic calibrations.
 *
 * Every statistic and all eleven verdicts are recomputed on parse and any
 * disagreement is rejected, so a device cannot claim a calibration it did not
 * produce. The adapter performs no capture, no inference, no file I/O, no
 * upload and no quality assessment; the grade stays `unverified`.
 */
object ArKernelImuCalibrationAdapter {

    fun create(
        sessionId: String,
        deviceToken: String,
        referenceGravityMps2: Double,
        requiredTemperatureSpanC: Double,
        minRepeatCycles: Int,
        minStaticDurationMs: Long,
        maxGapMs: Long,
        maxRateErrorPermille: Int,
        maxTurnRateDps: Double,
        maxGravityErrorMps2: Double,
        maxAccelBiasMps2: Double,
        maxAccelScaleErrorPermille: Int,
        maxAccelMisalignmentPermille: Int,
        maxAccelResidualMps2: Double,
        maxAccelNoiseMgSqrtHz: Double,
        maxGyroBiasDps: Double,
        maxGyroScaleErrorPermille: Int,
        maxCrossAxisPermille: Int,
        maxGyroArwDegSqrtHr: Double,
        maxAccelBiasStabilityMg: Double,
        maxGyroBiasStabilityDph: Double,
        streams: List<ArKernelImuStreamRow>,
        statics: List<ArKernelImuStaticRow>,
        poses: List<ArKernelImuPoseRow>,
        turns: List<ArKernelImuTurnRow>,
    ): ArKernelImuCalibrationResult {
        if (!IMUCAL_TOKEN.matches(sessionId)) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_TOKEN,
                "Invalid session id",
            )
        }
        if (!IMUCAL_TOKEN.matches(deviceToken)) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_TOKEN,
                "Invalid device token",
            )
        }
        if (!imucalFinite(referenceGravityMps2) ||
            referenceGravityMps2 < IMUCAL_MIN_GRAVITY_MPS2 ||
            referenceGravityMps2 > IMUCAL_MAX_GRAVITY_MPS2
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Reference gravity is out of bounds",
            )
        }
        if (!imucalFinite(requiredTemperatureSpanC) ||
            requiredTemperatureSpanC < 0.0 ||
            requiredTemperatureSpanC > IMUCAL_MAX_TEMPERATURE_SPAN_C
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Required temperature span is out of bounds",
            )
        }
        if (minRepeatCycles < IMUCAL_MIN_REPEAT_CYCLES ||
            minRepeatCycles > IMUCAL_MAX_REPEAT_CYCLES
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Minimum repeat cycles is out of bounds",
            )
        }
        if (minStaticDurationMs < IMUCAL_MIN_DURATION_MS ||
            minStaticDurationMs > IMUCAL_MAX_DURATION_MS
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_DURATION,
                "Minimum static duration is out of bounds",
            )
        }
        if (maxGapMs < 1L || maxGapMs > IMUCAL_MAX_GAP_MS) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum gap is out of bounds",
            )
        }
        if (maxRateErrorPermille < 1 ||
            maxRateErrorPermille > IMUCAL_MAX_PERMILLE
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum rate error is out of bounds",
            )
        }
        if (!imucalFinite(maxTurnRateDps) || maxTurnRateDps < 10.0 ||
            maxTurnRateDps > IMUCAL_MAX_RATE_DPS
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum turn rate is out of bounds",
            )
        }
        if (!imucalFinite(maxGravityErrorMps2) ||
            maxGravityErrorMps2 < 0.001 || maxGravityErrorMps2 > 1.0
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum gravity error is out of bounds",
            )
        }
        if (!imucalFinite(maxAccelBiasMps2) ||
            maxAccelBiasMps2 < 0.001 || maxAccelBiasMps2 > 1.0
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum accelerometer bias is out of bounds",
            )
        }
        if (maxAccelScaleErrorPermille < 1 ||
            maxAccelScaleErrorPermille > IMUCAL_MAX_PERMILLE
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum accelerometer scale error is out of bounds",
            )
        }
        if (maxAccelMisalignmentPermille < 1 ||
            maxAccelMisalignmentPermille > IMUCAL_MAX_PERMILLE
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum misalignment is out of bounds",
            )
        }
        if (!imucalFinite(maxAccelResidualMps2) ||
            maxAccelResidualMps2 < 0.001 || maxAccelResidualMps2 > 1.0
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum accelerometer residual is out of bounds",
            )
        }
        if (!imucalFinite(maxAccelNoiseMgSqrtHz) ||
            maxAccelNoiseMgSqrtHz < 0.1 || maxAccelNoiseMgSqrtHz > 1_000.0
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum accelerometer noise is out of bounds",
            )
        }
        if (!imucalFinite(maxGyroBiasDps) ||
            maxGyroBiasDps < 0.001 || maxGyroBiasDps > 10.0
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum gyroscope bias is out of bounds",
            )
        }
        if (maxGyroScaleErrorPermille < 1 ||
            maxGyroScaleErrorPermille > IMUCAL_MAX_PERMILLE
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum gyroscope scale error is out of bounds",
            )
        }
        if (maxCrossAxisPermille < 1 ||
            maxCrossAxisPermille > IMUCAL_MAX_PERMILLE
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum cross-axis coupling is out of bounds",
            )
        }
        if (!imucalFinite(maxGyroArwDegSqrtHr) ||
            maxGyroArwDegSqrtHr < 0.01 || maxGyroArwDegSqrtHr > 1_000.0
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum angle random walk is out of bounds",
            )
        }
        if (!imucalFinite(maxAccelBiasStabilityMg) ||
            maxAccelBiasStabilityMg < 0.1 ||
            maxAccelBiasStabilityMg > 10_000.0
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum accelerometer bias stability is out of bounds",
            )
        }
        if (!imucalFinite(maxGyroBiasStabilityDph) ||
            maxGyroBiasStabilityDph < 0.01 ||
            maxGyroBiasStabilityDph > 10_000.0
        ) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_VALUE,
                "Maximum gyroscope bias stability is out of bounds",
            )
        }
        if (streams.size > IMUCAL_MAX_STREAMS) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_COUNT,
                "Too many stream rows",
            )
        }
        if (statics.size > IMUCAL_MAX_STATICS) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_COUNT,
                "Too many static rows",
            )
        }
        if (poses.size > IMUCAL_MAX_POSES) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_COUNT,
                "Too many pose rows",
            )
        }
        if (turns.size > IMUCAL_MAX_TURNS) {
            return imucalReject(
                ArKernelImuCalibrationRejection.INVALID_COUNT,
                "Too many turn rows",
            )
        }
        streams.forEachIndexed { index, row ->
            if (row.index != index) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_ORDER,
                    "Stream rows are not contiguous",
                )
            }
            if (!IMUCAL_TOKEN.matches(row.label)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_TOKEN,
                    "Invalid stream label",
                )
            }
            if (row.nominalHz < 1 || row.nominalHz > IMUCAL_MAX_HZ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Stream nominal rate is out of bounds",
                )
            }
            if (!imucalFinite(row.measuredHz) || row.measuredHz < 0.0 ||
                row.measuredHz > IMUCAL_MAX_HZ.toDouble()
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Stream measured rate is out of bounds",
                )
            }
            if (row.minDtUs < 0L || row.maxDtUs < row.minDtUs ||
                row.maxDtUs > IMUCAL_MAX_DT_US
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Stream interval is out of bounds",
                )
            }
            if (row.gapCount < 0 || row.duplicateCount < 0 ||
                row.monotonicViolations < 0
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Stream counters must not be negative",
                )
            }
        }
        statics.forEachIndexed { index, row ->
            if (row.index != index) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_ORDER,
                    "Static rows are not contiguous",
                )
            }
            if (!IMUCAL_TOKEN.matches(row.label)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_TOKEN,
                    "Invalid static label",
                )
            }
            if (row.durationMs < 1L ||
                row.durationMs > IMUCAL_MAX_DURATION_MS
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_DURATION,
                    "Static duration is out of bounds",
                )
            }
            if (row.sampleCount < 1L ||
                row.sampleCount > IMUCAL_MAX_SAMPLES
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_COUNT,
                    "Static sample count is out of bounds",
                )
            }
            if (row.gyroSampleCount < 1L ||
                row.gyroSampleCount > IMUCAL_MAX_SAMPLES
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_COUNT,
                    "Static gyroscope sample count is out of bounds",
                )
            }
            if (!imucalGyroTriple(
                    row.meanGyroXDps, row.meanGyroYDps, row.meanGyroZDps,
                )
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Static gyroscope mean is out of bounds",
                )
            }
            if (!imucalUnit(row.rmsAccMps2, IMUCAL_MAX_ACCEL_MPS2)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Static accelerometer noise is out of bounds",
                )
            }
            if (!imucalUnit(row.rmsGyroDps, IMUCAL_MAX_GYRO_DPS)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Static gyroscope noise is out of bounds",
                )
            }
            if (!imucalUnit(row.stabilityAccMps2, IMUCAL_MAX_ACCEL_MPS2)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Static accelerometer stability is out of bounds",
                )
            }
            if (!imucalUnit(row.stabilityGyroDps, IMUCAL_MAX_GYRO_DPS)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Static gyroscope stability is out of bounds",
                )
            }
            if (row.maxGapMs < 0L || row.maxGapMs > IMUCAL_MAX_GAP_MS) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Static maximum gap is out of bounds",
                )
            }
            if (!imucalTemperature(row.temperatureC)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_TEMPERATURE,
                    "Static temperature is out of bounds",
                )
            }
        }
        poses.forEachIndexed { index, row ->
            if (row.index != index) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_ORDER,
                    "Pose rows are not contiguous",
                )
            }
            if (!IMUCAL_TOKEN.matches(row.label)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_TOKEN,
                    "Invalid pose label",
                )
            }
            if (row.sampleCount < 1L ||
                row.sampleCount > IMUCAL_MAX_SAMPLES
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_COUNT,
                    "Pose sample count is out of bounds",
                )
            }
            if (!imucalAccelTriple(
                    row.meanXMps2, row.meanYMps2, row.meanZMps2,
                )
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Pose accelerometer mean is out of bounds",
                )
            }
            if (!imucalGyroTriple(
                    row.meanGyroXDps, row.meanGyroYDps, row.meanGyroZDps,
                )
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Pose gyroscope mean is out of bounds",
                )
            }
            if (!imucalTemperature(row.temperatureC)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_TEMPERATURE,
                    "Pose temperature is out of bounds",
                )
            }
        }
        turns.forEachIndexed { index, row ->
            if (row.index != index) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_ORDER,
                    "Turn rows are not contiguous",
                )
            }
            if (!IMUCAL_TOKEN.matches(row.label)) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_TOKEN,
                    "Invalid turn label",
                )
            }
            val reference = kotlin.math.abs(row.referenceAngleDeg)
            if (reference < IMUCAL_MIN_REFERENCE_ANGLE_DEG ||
                reference > IMUCAL_MAX_REFERENCE_ANGLE_DEG
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Turn reference angle is out of bounds",
                )
            }
            if (!imucalFinite(row.integratedAngleDeg) ||
                kotlin.math.abs(row.integratedAngleDeg) >
                IMUCAL_MAX_ANGLE_DEG
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Turn integrated angle is out of bounds",
                )
            }
            if (!imucalFinite(row.offAxisAngleDeg) ||
                row.offAxisAngleDeg < 0.0 ||
                row.offAxisAngleDeg > IMUCAL_MAX_ANGLE_DEG
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Turn off-axis angle is out of bounds",
                )
            }
            if (row.durationMs < 1L ||
                row.durationMs > IMUCAL_MAX_DURATION_MS
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_DURATION,
                    "Turn duration is out of bounds",
                )
            }
            if (row.sampleCount < 1L ||
                row.sampleCount > IMUCAL_MAX_SAMPLES
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_COUNT,
                    "Turn sample count is out of bounds",
                )
            }
            if (!imucalFinite(row.peakRateDps) || row.peakRateDps < 0.0 ||
                row.peakRateDps > IMUCAL_MAX_RATE_DPS
            ) {
                return imucalReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    "Turn peak rate is out of bounds",
                )
            }
        }
        return ArKernelImuCalibrationResult(
            calibration = ArKernelImuCalibration(
                sessionId = sessionId,
                deviceToken = deviceToken,
                referenceGravityMps2 = referenceGravityMps2,
                requiredTemperatureSpanC = requiredTemperatureSpanC,
                minRepeatCycles = minRepeatCycles,
                minStaticDurationMs = minStaticDurationMs,
                maxGapMs = maxGapMs,
                maxRateErrorPermille = maxRateErrorPermille,
                maxTurnRateDps = maxTurnRateDps,
                maxGravityErrorMps2 = maxGravityErrorMps2,
                maxAccelBiasMps2 = maxAccelBiasMps2,
                maxAccelScaleErrorPermille = maxAccelScaleErrorPermille,
                maxAccelMisalignmentPermille = maxAccelMisalignmentPermille,
                maxAccelResidualMps2 = maxAccelResidualMps2,
                maxAccelNoiseMgSqrtHz = maxAccelNoiseMgSqrtHz,
                maxGyroBiasDps = maxGyroBiasDps,
                maxGyroScaleErrorPermille = maxGyroScaleErrorPermille,
                maxCrossAxisPermille = maxCrossAxisPermille,
                maxGyroArwDegSqrtHr = maxGyroArwDegSqrtHr,
                maxAccelBiasStabilityMg = maxAccelBiasStabilityMg,
                maxGyroBiasStabilityDph = maxGyroBiasStabilityDph,
                streams = streams,
                statics = statics,
                poses = poses,
                turns = turns,
            ),
            rejection = ArKernelImuCalibrationRejection.NONE,
            detail = "accepted",
        )
    }

    fun summarize(
        calibration: ArKernelImuCalibration,
    ): ArKernelImuCalibrationSummary =
        ArKernelImuCalibrationSummary(
            streamCount = calibration.streamCount,
            staticCount = calibration.staticCount,
            poseCount = calibration.poseCount,
            turnCount = calibration.turnCount,
            staticSampleTotal = calibration.staticSampleTotal,
            poseSampleTotal = calibration.poseSampleTotal,
            observedMaxGapMs = calibration.observedMaxGapMs,
            observedMaxRateErrorPermille =
                calibration.observedMaxRateErrorPermille,
            observedPeakRateDps = calibration.observedPeakRateDps,
            gravityMagnitudeErrorMps2 =
                calibration.gravityMagnitudeErrorMps2,
            accelBiasXMps2 = calibration.accelBiasXMps2,
            accelBiasYMps2 = calibration.accelBiasYMps2,
            accelBiasZMps2 = calibration.accelBiasZMps2,
            accelBiasNormMps2 = calibration.accelBiasNormMps2,
            accelScaleXPermille = calibration.accelScaleXPermille,
            accelScaleYPermille = calibration.accelScaleYPermille,
            accelScaleZPermille = calibration.accelScaleZPermille,
            accelScaleErrorMaxPermille =
                calibration.accelScaleErrorMaxPermille,
            accelMisalignmentMaxPermille =
                calibration.accelMisalignmentMaxPermille,
            accelResidualRmsMps2 = calibration.accelResidualRmsMps2,
            accelRepeatCycles = calibration.accelRepeatCycles,
            accelNoiseDensityMgSqrtHz =
                calibration.accelNoiseDensityMgSqrtHz,
            accelBiasStabilityMg = calibration.accelBiasStabilityMg,
            gyroBiasXDps = calibration.gyroBiasXDps,
            gyroBiasYDps = calibration.gyroBiasYDps,
            gyroBiasZDps = calibration.gyroBiasZDps,
            gyroBiasNormDps = calibration.gyroBiasNormDps,
            gyroBiasSpreadDps = calibration.gyroBiasSpreadDps,
            gyroScaleErrorMaxPermille =
                calibration.gyroScaleErrorMaxPermille,
            gyroCrossAxisMaxPermille = calibration.gyroCrossAxisMaxPermille,
            gyroArwDegSqrtHr = calibration.gyroArwDegSqrtHr,
            gyroBiasStabilityDph = calibration.gyroBiasStabilityDph,
            angleDriftDegPerMin = calibration.angleDriftDegPerMin,
            positionDriftMPerMin = calibration.positionDriftMPerMin,
            temperatureSpanC = calibration.temperatureSpanC,
            cadenceOk = calibration.cadenceOk,
            durationOk = calibration.durationOk,
            gravityOk = calibration.gravityOk,
            accelFitOk = calibration.accelFitOk,
            accelRepeatChecked = calibration.accelRepeatChecked,
            gyroScaleOk = calibration.gyroScaleOk,
            biasOk = calibration.biasOk,
            noiseOk = calibration.noiseOk,
            stabilityOk = calibration.stabilityOk,
            thermalCovered = calibration.thermalCovered,
            calibrationSufficient = calibration.calibrationSufficient,
            outstanding = calibration.outstandingSteps(),
        )

    fun encode(calibration: ArKernelImuCalibration): String {
        val lines = ArrayList<String>(
            IMUCAL_KEYS.size + calibration.streamCount +
                calibration.staticCount + calibration.poseCount +
                calibration.turnCount + 2,
        )
        lines += IMUCAL_HEADER
        lines += "version=$IMUCAL_VERSION"
        lines += "session_id=${calibration.sessionId}"
        lines += "device_token=${calibration.deviceToken}"
        lines += "reference_gravity_mps2=" +
            imucalFixed(calibration.referenceGravityMps2)
        lines += "required_temperature_span_c=" +
            imucalFixed(calibration.requiredTemperatureSpanC)
        lines += "min_repeat_cycles=${calibration.minRepeatCycles}"
        lines += "min_static_duration_ms=${calibration.minStaticDurationMs}"
        lines += "max_gap_ms=${calibration.maxGapMs}"
        lines += "max_rate_error_permille=${calibration.maxRateErrorPermille}"
        lines += "max_turn_rate_dps=" + imucalFixed(calibration.maxTurnRateDps)
        lines += "max_gravity_error_mps2=" +
            imucalFixed(calibration.maxGravityErrorMps2)
        lines += "max_accel_bias_mps2=" +
            imucalFixed(calibration.maxAccelBiasMps2)
        lines += "max_accel_scale_error_permille=" +
            calibration.maxAccelScaleErrorPermille
        lines += "max_accel_misalignment_permille=" +
            calibration.maxAccelMisalignmentPermille
        lines += "max_accel_residual_mps2=" +
            imucalFixed(calibration.maxAccelResidualMps2)
        lines += "max_accel_noise_mg_sqrt_hz=" +
            imucalFixed(calibration.maxAccelNoiseMgSqrtHz)
        lines += "max_gyro_bias_dps=" +
            imucalFixed(calibration.maxGyroBiasDps)
        lines += "max_gyro_scale_error_permille=" +
            calibration.maxGyroScaleErrorPermille
        lines += "max_cross_axis_permille=${calibration.maxCrossAxisPermille}"
        lines += "max_gyro_arw_deg_sqrt_hr=" +
            imucalFixed(calibration.maxGyroArwDegSqrtHr)
        lines += "max_accel_bias_stability_mg=" +
            imucalFixed(calibration.maxAccelBiasStabilityMg)
        lines += "max_gyro_bias_stability_dph=" +
            imucalFixed(calibration.maxGyroBiasStabilityDph)
        lines += "stream_count=${calibration.streamCount}"
        lines += "static_count=${calibration.staticCount}"
        lines += "pose_count=${calibration.poseCount}"
        lines += "turn_count=${calibration.turnCount}"
        lines += "static_sample_total=${calibration.staticSampleTotal}"
        lines += "pose_sample_total=${calibration.poseSampleTotal}"
        lines += "observed_max_gap_ms=${calibration.observedMaxGapMs}"
        lines += "observed_max_rate_error_permille=" +
            calibration.observedMaxRateErrorPermille
        lines += "observed_peak_rate_dps=" +
            imucalFixed(calibration.observedPeakRateDps)
        lines += "gravity_magnitude_error_mps2=" +
            imucalFixed(calibration.gravityMagnitudeErrorMps2)
        lines += "accel_bias_x_mps2=" + imucalFixed(calibration.accelBiasXMps2)
        lines += "accel_bias_y_mps2=" + imucalFixed(calibration.accelBiasYMps2)
        lines += "accel_bias_z_mps2=" + imucalFixed(calibration.accelBiasZMps2)
        lines += "accel_bias_norm_mps2=" +
            imucalFixed(calibration.accelBiasNormMps2)
        lines += "accel_scale_x_permille=" +
            imucalFixed(calibration.accelScaleXPermille)
        lines += "accel_scale_y_permille=" +
            imucalFixed(calibration.accelScaleYPermille)
        lines += "accel_scale_z_permille=" +
            imucalFixed(calibration.accelScaleZPermille)
        lines += "accel_scale_error_max_permille=" +
            calibration.accelScaleErrorMaxPermille
        lines += "accel_misalignment_max_permille=" +
            calibration.accelMisalignmentMaxPermille
        lines += "accel_residual_rms_mps2=" +
            imucalFixed(calibration.accelResidualRmsMps2)
        lines += "accel_repeat_cycles=${calibration.accelRepeatCycles}"
        lines += "accel_noise_density_mg_sqrt_hz=" +
            imucalFixed(calibration.accelNoiseDensityMgSqrtHz)
        lines += "accel_bias_stability_mg=" +
            imucalFixed(calibration.accelBiasStabilityMg)
        lines += "gyro_bias_x_dps=" + imucalFixed(calibration.gyroBiasXDps)
        lines += "gyro_bias_y_dps=" + imucalFixed(calibration.gyroBiasYDps)
        lines += "gyro_bias_z_dps=" + imucalFixed(calibration.gyroBiasZDps)
        lines += "gyro_bias_norm_dps=" +
            imucalFixed(calibration.gyroBiasNormDps)
        lines += "gyro_bias_spread_dps=" +
            imucalFixed(calibration.gyroBiasSpreadDps)
        lines += "gyro_scale_error_max_permille=" +
            calibration.gyroScaleErrorMaxPermille
        lines += "gyro_cross_axis_max_permille=" +
            calibration.gyroCrossAxisMaxPermille
        lines += "gyro_arw_deg_sqrt_hr=" +
            imucalFixed(calibration.gyroArwDegSqrtHr)
        lines += "gyro_bias_stability_dph=" +
            imucalFixed(calibration.gyroBiasStabilityDph)
        lines += "angle_drift_deg_per_min=" +
            imucalFixed(calibration.angleDriftDegPerMin)
        lines += "position_drift_m_per_min=" +
            imucalFixed(calibration.positionDriftMPerMin)
        lines += "temperature_span_c=" +
            imucalFixed(calibration.temperatureSpanC)
        lines += "cadence_ok=" + imucalBool(calibration.cadenceOk)
        lines += "duration_ok=" + imucalBool(calibration.durationOk)
        lines += "gravity_ok=" + imucalBool(calibration.gravityOk)
        lines += "accel_fit_ok=" + imucalBool(calibration.accelFitOk)
        lines += "accel_repeat_checked=" +
            imucalBool(calibration.accelRepeatChecked)
        lines += "gyro_scale_ok=" + imucalBool(calibration.gyroScaleOk)
        lines += "bias_ok=" + imucalBool(calibration.biasOk)
        lines += "noise_ok=" + imucalBool(calibration.noiseOk)
        lines += "stability_ok=" + imucalBool(calibration.stabilityOk)
        lines += "thermal_covered=" + imucalBool(calibration.thermalCovered)
        lines += "calibration_sufficient=" +
            imucalBool(calibration.calibrationSufficient)
        for (key in IMUCAL_TRUE_KEYS) lines += "$key=true"
        for (key in IMUCAL_FALSE_KEYS) lines += "$key=false"
        lines += "quality_grade=$IMUCAL_QUALITY"
        for (row in calibration.streams) lines += imucalStreamRow(row)
        for (row in calibration.statics) lines += imucalStaticRow(row)
        for (row in calibration.poses) lines += imucalPoseRow(row)
        for (row in calibration.turns) lines += imucalTurnRow(row)
        lines += IMUCAL_END
        return lines.joinToString("\n") + "\n"
    }

    fun parse(text: String): ArKernelImuCalibrationParseResult {
        if (text.length > IMUCAL_MAX_INPUT_CHARACTERS) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.INPUT_TOO_LARGE,
                1,
                "Calibration block is too large",
            )
        }
        val lines = text.split("\n")
        if (lines.isEmpty()) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.EMPTY_BLOCK,
                1,
                "Calibration block is empty",
            )
        }
        if (lines[0].trimEnd('\r') != IMUCAL_HEADER) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_HEADER,
                1,
                "Calibration block must start with $IMUCAL_HEADER",
            )
        }
        val keyLines = IMUCAL_KEYS.size
        if (lines.size < keyLines + 2) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                lines.size,
                "Calibration block is truncated",
            )
        }
        val values = LinkedHashMap<String, String>()
        val lineByKey = LinkedHashMap<String, Int>()
        for (offset in 0 until keyLines) {
            val lineNumber = offset + 2
            val line = lines[offset + 1].trimEnd('\r')
            val empty = imucalValidateLine(line, lineNumber)
            if (empty != null) return empty
            val split = line.indexOf('=')
            if (split <= 0) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Calibration line is not a key=value pair",
                )
            }
            val key = line.substring(0, split)
            val expectedKey = IMUCAL_KEYS[offset]
            if (key != expectedKey) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Expected key $expectedKey but found $key",
                )
            }
            values[key] = line.substring(split + 1)
            lineByKey[key] = lineNumber
        }
        if (values.getValue("version") != IMUCAL_VERSION) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                lineByKey.getValue("version"),
                "Unsupported calibration version",
            )
        }
        for (key in IMUCAL_TRUE_KEYS) {
            if (values.getValue(key) != "true") {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "$key must be true",
                )
            }
        }
        for (key in IMUCAL_FALSE_KEYS) {
            if (values.getValue(key) != "false") {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "$key must be false",
                )
            }
        }
        if (values.getValue("quality_grade") != IMUCAL_QUALITY) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Calibration quality grade must stay $IMUCAL_QUALITY",
            )
        }
        val sessionId = values.getValue("session_id")
        if (!IMUCAL_TOKEN.matches(sessionId)) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_TOKEN,
                lineByKey.getValue("session_id"),
                "Invalid session id",
            )
        }
        val deviceToken = values.getValue("device_token")
        if (!IMUCAL_TOKEN.matches(deviceToken)) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_TOKEN,
                lineByKey.getValue("device_token"),
                "Invalid device token",
            )
        }
        val referenceGravity = imucalDecimal(
            values.getValue("reference_gravity_mps2"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("reference_gravity_mps2"),
            "Invalid reference gravity",
        )
        val requiredSpan = imucalDecimal(
            values.getValue("required_temperature_span_c"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("required_temperature_span_c"),
            "Invalid required temperature span",
        )
        val minRepeat = imucalUnsigned(
            values.getValue("min_repeat_cycles"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
            lineByKey.getValue("min_repeat_cycles"),
            "Invalid minimum repeat cycles",
        )
        val minDuration = imucalUnsigned(
            values.getValue("min_static_duration_ms"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
            lineByKey.getValue("min_static_duration_ms"),
            "Invalid minimum static duration",
        )
        val maxGap = imucalUnsigned(values.getValue("max_gap_ms"))
            ?: return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
                lineByKey.getValue("max_gap_ms"),
                "Invalid maximum gap",
            )
        val maxRateError = imucalUnsigned(
            values.getValue("max_rate_error_permille"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
            lineByKey.getValue("max_rate_error_permille"),
            "Invalid maximum rate error",
        )
        val maxTurnRate = imucalDecimal(values.getValue("max_turn_rate_dps"))
            ?: return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                lineByKey.getValue("max_turn_rate_dps"),
                "Invalid maximum turn rate",
            )
        val maxGravityError = imucalDecimal(
            values.getValue("max_gravity_error_mps2"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("max_gravity_error_mps2"),
            "Invalid maximum gravity error",
        )
        val maxAccelBias = imucalDecimal(
            values.getValue("max_accel_bias_mps2"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("max_accel_bias_mps2"),
            "Invalid maximum accelerometer bias",
        )
        val maxScaleError = imucalUnsigned(
            values.getValue("max_accel_scale_error_permille"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
            lineByKey.getValue("max_accel_scale_error_permille"),
            "Invalid maximum accelerometer scale error",
        )
        val maxMisalignment = imucalUnsigned(
            values.getValue("max_accel_misalignment_permille"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
            lineByKey.getValue("max_accel_misalignment_permille"),
            "Invalid maximum misalignment",
        )
        val maxResidual = imucalDecimal(
            values.getValue("max_accel_residual_mps2"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("max_accel_residual_mps2"),
            "Invalid maximum accelerometer residual",
        )
        val maxAccelNoise = imucalDecimal(
            values.getValue("max_accel_noise_mg_sqrt_hz"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("max_accel_noise_mg_sqrt_hz"),
            "Invalid maximum accelerometer noise",
        )
        val maxGyroBias = imucalDecimal(
            values.getValue("max_gyro_bias_dps"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("max_gyro_bias_dps"),
            "Invalid maximum gyroscope bias",
        )
        val maxGyroScaleError = imucalUnsigned(
            values.getValue("max_gyro_scale_error_permille"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
            lineByKey.getValue("max_gyro_scale_error_permille"),
            "Invalid maximum gyroscope scale error",
        )
        val maxCrossAxis = imucalUnsigned(
            values.getValue("max_cross_axis_permille"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
            lineByKey.getValue("max_cross_axis_permille"),
            "Invalid maximum cross-axis coupling",
        )
        val maxArw = imucalDecimal(values.getValue("max_gyro_arw_deg_sqrt_hr"))
            ?: return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                lineByKey.getValue("max_gyro_arw_deg_sqrt_hr"),
                "Invalid maximum angle random walk",
            )
        val maxAccelStability = imucalDecimal(
            values.getValue("max_accel_bias_stability_mg"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("max_accel_bias_stability_mg"),
            "Invalid maximum accelerometer bias stability",
        )
        val maxGyroStability = imucalDecimal(
            values.getValue("max_gyro_bias_stability_dph"),
        ) ?: return imucalParseReject(
            ArKernelImuCalibrationRejection.INVALID_DECIMAL,
            lineByKey.getValue("max_gyro_bias_stability_dph"),
            "Invalid maximum gyroscope bias stability",
        )
        val streamTotal = imucalUnsigned(values.getValue("stream_count"))
            ?: return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
                lineByKey.getValue("stream_count"),
                "Invalid stream count",
            )
        val staticTotal = imucalUnsigned(values.getValue("static_count"))
            ?: return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
                lineByKey.getValue("static_count"),
                "Invalid static count",
            )
        val poseTotal = imucalUnsigned(values.getValue("pose_count"))
            ?: return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
                lineByKey.getValue("pose_count"),
                "Invalid pose count",
            )
        val turnTotal = imucalUnsigned(values.getValue("turn_count"))
            ?: return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_UNSIGNED,
                lineByKey.getValue("turn_count"),
                "Invalid turn count",
            )

        var cursor = keyLines + 1
        val streams = ArrayList<ArKernelImuStreamRow>()
        for (index in 0 until streamTotal.toInt()) {
            val line = lines.getOrNull(cursor)
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                    cursor + 1,
                    "Calibration block ended before the stream rows",
                )
            cursor++
            val trimmed = line.trimEnd('\r')
            val empty = imucalValidateLine(trimmed, cursor)
            if (empty != null) return empty
            if (!trimmed.startsWith("stream=")) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ROW,
                    cursor,
                    "Expected a stream row",
                )
            }
            val fields = trimmed.substring("stream=".length).split("|")
            if (fields.size != 10) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ROW,
                    cursor,
                    "Stream row must have ten fields",
                )
            }
            val rowIndex = imucalUnsigned(fields[0])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid stream index",
                )
            if (rowIndex.toInt() != index) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ORDER,
                    cursor,
                    "Stream rows are not contiguous",
                )
            }
            if (!IMUCAL_TOKEN.matches(fields[1])) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_TOKEN,
                    cursor,
                    "Invalid stream label",
                )
            }
            val nominalHz = imucalUnsigned(fields[2])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid nominal rate",
                )
            val measuredHz = imucalDecimal(fields[3])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid measured rate",
                )
            val minDt = imucalUnsigned(fields[4])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid minimum interval",
                )
            val maxDt = imucalUnsigned(fields[5])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid maximum interval",
                )
            val gaps = imucalUnsigned(fields[6])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid gap count",
                )
            val duplicates = imucalUnsigned(fields[7])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid duplicate count",
                )
            val monotonic = imucalUnsigned(fields[8])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid monotonicity count",
                )
            val domain = ArKernelImuTimestampDomain.fromWire(fields[9])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DOMAIN,
                    cursor,
                    "Invalid timestamp domain",
                )
            streams += ArKernelImuStreamRow(
                index = index,
                label = fields[1],
                nominalHz = nominalHz.toInt(),
                measuredHz = measuredHz,
                minDtUs = minDt,
                maxDtUs = maxDt,
                gapCount = gaps.toInt(),
                duplicateCount = duplicates.toInt(),
                monotonicViolations = monotonic.toInt(),
                domain = domain,
            )
        }
        val statics = ArrayList<ArKernelImuStaticRow>()
        for (index in 0 until staticTotal.toInt()) {
            val line = lines.getOrNull(cursor)
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                    cursor + 1,
                    "Calibration block ended before the static rows",
                )
            cursor++
            val trimmed = line.trimEnd('\r')
            val empty = imucalValidateLine(trimmed, cursor)
            if (empty != null) return empty
            if (!trimmed.startsWith("static=")) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ROW,
                    cursor,
                    "Expected a static row",
                )
            }
            val fields = trimmed.substring("static=".length).split("|")
            if (fields.size != 14) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ROW,
                    cursor,
                    "Static row must have fourteen fields",
                )
            }
            val rowIndex = imucalUnsigned(fields[0])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid static index",
                )
            if (rowIndex.toInt() != index) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ORDER,
                    cursor,
                    "Static rows are not contiguous",
                )
            }
            if (!IMUCAL_TOKEN.matches(fields[1])) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_TOKEN,
                    cursor,
                    "Invalid static label",
                )
            }
            val duration = imucalUnsigned(fields[2])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid static duration",
                )
            val samples = imucalUnsigned(fields[3])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid static sample count",
                )
            val gyroSamples = imucalUnsigned(fields[4])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid static gyroscope sample count",
                )
            val gyroX = imucalSigned(fields[5])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid static gyroscope X mean",
                )
            val gyroY = imucalSigned(fields[6])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid static gyroscope Y mean",
                )
            val gyroZ = imucalSigned(fields[7])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid static gyroscope Z mean",
                )
            val rmsAcc = imucalDecimal(fields[8])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid static accelerometer noise",
                )
            val rmsGyro = imucalDecimal(fields[9])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid static gyroscope noise",
                )
            val stabAcc = imucalDecimal(fields[10])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid static accelerometer stability",
                )
            val stabGyro = imucalDecimal(fields[11])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid static gyroscope stability",
                )
            val maxGapRow = imucalUnsigned(fields[12])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid static maximum gap",
                )
            val temperature = imucalSigned(fields[13])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid static temperature",
                )
            statics += ArKernelImuStaticRow(
                index = index,
                label = fields[1],
                durationMs = duration,
                sampleCount = samples,
                gyroSampleCount = gyroSamples,
                meanGyroXDps = gyroX,
                meanGyroYDps = gyroY,
                meanGyroZDps = gyroZ,
                rmsAccMps2 = rmsAcc,
                rmsGyroDps = rmsGyro,
                stabilityAccMps2 = stabAcc,
                stabilityGyroDps = stabGyro,
                maxGapMs = maxGapRow,
                temperatureC = temperature,
            )
        }
        val poses = ArrayList<ArKernelImuPoseRow>()
        for (index in 0 until poseTotal.toInt()) {
            val line = lines.getOrNull(cursor)
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                    cursor + 1,
                    "Calibration block ended before the pose rows",
                )
            cursor++
            val trimmed = line.trimEnd('\r')
            val empty = imucalValidateLine(trimmed, cursor)
            if (empty != null) return empty
            if (!trimmed.startsWith("pose=")) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ROW,
                    cursor,
                    "Expected a pose row",
                )
            }
            val fields = trimmed.substring("pose=".length).split("|")
            if (fields.size != 11) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ROW,
                    cursor,
                    "Pose row must have eleven fields",
                )
            }
            val rowIndex = imucalUnsigned(fields[0])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid pose index",
                )
            if (rowIndex.toInt() != index) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ORDER,
                    cursor,
                    "Pose rows are not contiguous",
                )
            }
            if (!IMUCAL_TOKEN.matches(fields[1])) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_TOKEN,
                    cursor,
                    "Invalid pose label",
                )
            }
            val direction = ArKernelImuPoseDirection.fromWire(fields[2])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DIRECTION,
                    cursor,
                    "Invalid pose direction",
                )
            val samples = imucalUnsigned(fields[3])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid pose sample count",
                )
            val meanX = imucalSigned(fields[4])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid pose accelerometer X mean",
                )
            val meanY = imucalSigned(fields[5])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid pose accelerometer Y mean",
                )
            val meanZ = imucalSigned(fields[6])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid pose accelerometer Z mean",
                )
            val gyroX = imucalSigned(fields[7])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid pose gyroscope X mean",
                )
            val gyroY = imucalSigned(fields[8])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid pose gyroscope Y mean",
                )
            val gyroZ = imucalSigned(fields[9])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid pose gyroscope Z mean",
                )
            val temperature = imucalSigned(fields[10])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid pose temperature",
                )
            poses += ArKernelImuPoseRow(
                index = index,
                label = fields[1],
                direction = direction,
                sampleCount = samples,
                meanXMps2 = meanX,
                meanYMps2 = meanY,
                meanZMps2 = meanZ,
                meanGyroXDps = gyroX,
                meanGyroYDps = gyroY,
                meanGyroZDps = gyroZ,
                temperatureC = temperature,
            )
        }
        val turns = ArrayList<ArKernelImuTurnRow>()
        for (index in 0 until turnTotal.toInt()) {
            val line = lines.getOrNull(cursor)
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                    cursor + 1,
                    "Calibration block ended before the turn rows",
                )
            cursor++
            val trimmed = line.trimEnd('\r')
            val empty = imucalValidateLine(trimmed, cursor)
            if (empty != null) return empty
            if (!trimmed.startsWith("turn=")) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ROW,
                    cursor,
                    "Expected a turn row",
                )
            }
            val fields = trimmed.substring("turn=".length).split("|")
            if (fields.size != 9) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ROW,
                    cursor,
                    "Turn row must have nine fields",
                )
            }
            val rowIndex = imucalUnsigned(fields[0])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid turn index",
                )
            if (rowIndex.toInt() != index) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_ORDER,
                    cursor,
                    "Turn rows are not contiguous",
                )
            }
            if (!IMUCAL_TOKEN.matches(fields[1])) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_TOKEN,
                    cursor,
                    "Invalid turn label",
                )
            }
            val axis = ArKernelImuTurnAxis.fromWire(fields[2])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_AXIS,
                    cursor,
                    "Invalid turn axis",
                )
            val reference = imucalSigned(fields[3])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid reference angle",
                )
            val integrated = imucalSigned(fields[4])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid integrated angle",
                )
            val offAxis = imucalDecimal(fields[5])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid off-axis angle",
                )
            val duration = imucalUnsigned(fields[6])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid turn duration",
                )
            val samples = imucalUnsigned(fields[7])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_VALUE,
                    cursor,
                    "Invalid turn sample count",
                )
            val peakRate = imucalDecimal(fields[8])
                ?: return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_DECIMAL,
                    cursor,
                    "Invalid turn peak rate",
                )
            turns += ArKernelImuTurnRow(
                index = index,
                label = fields[1],
                axis = axis,
                referenceAngleDeg = reference,
                integratedAngleDeg = integrated,
                offAxisAngleDeg = offAxis,
                durationMs = duration,
                sampleCount = samples,
                peakRateDps = peakRate,
            )
        }
        val endLine = lines.getOrNull(cursor)?.trimEnd('\r')
        if (endLine != IMUCAL_END) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                cursor + 1,
                "Calibration block must end with END",
            )
        }
        val result = create(
            sessionId = sessionId,
            deviceToken = deviceToken,
            referenceGravityMps2 = referenceGravity,
            requiredTemperatureSpanC = requiredSpan,
            minRepeatCycles = minRepeat.toInt(),
            minStaticDurationMs = minDuration,
            maxGapMs = maxGap,
            maxRateErrorPermille = maxRateError.toInt(),
            maxTurnRateDps = maxTurnRate,
            maxGravityErrorMps2 = maxGravityError,
            maxAccelBiasMps2 = maxAccelBias,
            maxAccelScaleErrorPermille = maxScaleError.toInt(),
            maxAccelMisalignmentPermille = maxMisalignment.toInt(),
            maxAccelResidualMps2 = maxResidual,
            maxAccelNoiseMgSqrtHz = maxAccelNoise,
            maxGyroBiasDps = maxGyroBias,
            maxGyroScaleErrorPermille = maxGyroScaleError.toInt(),
            maxCrossAxisPermille = maxCrossAxis.toInt(),
            maxGyroArwDegSqrtHr = maxArw,
            maxAccelBiasStabilityMg = maxAccelStability,
            maxGyroBiasStabilityDph = maxGyroStability,
            streams = streams,
            statics = statics,
            poses = poses,
            turns = turns,
        )
        if (!result.accepted) {
            return imucalParseReject(
                result.rejection,
                keyLines + 2,
                result.detail,
            )
        }
        val calibration = result.calibration
            ?: return imucalParseReject(
                ArKernelImuCalibrationRejection.INVALID_SCHEMA,
                1,
                "Calibration could not be rebuilt",
            )
        val expected = LinkedHashMap<String, String>()
        for (key in IMUCAL_STATISTIC_KEYS) {
            expected[key] = imucalStatisticText(calibration, key)
        }
        for (key in IMUCAL_STATISTIC_KEYS) {
            val declared = values.getValue(key)
            if (declared != expected.getValue(key)) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.STATISTICS_MISMATCH,
                    lineByKey.getValue(key),
                    "Recomputed $key disagrees with the declared value",
                )
            }
        }
        for (key in IMUCAL_VERDICT_KEYS) {
            val declared = values.getValue(key)
            if (!IMUCAL_BOOLEAN.matches(declared)) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Invalid verdict value",
                )
            }
            if ((declared == "true") != imucalVerdict(calibration, key)) {
                return imucalParseReject(
                    ArKernelImuCalibrationRejection.CALIBRATION_MISMATCH,
                    lineByKey.getValue(key),
                    "Recomputed $key disagrees with the declared verdict",
                )
            }
        }
        return ArKernelImuCalibrationParseResult(
            calibration = calibration,
            rejection = ArKernelImuCalibrationRejection.NONE,
            lineNumber = 0,
            detail = "accepted",
        )
    }

    private fun imucalStatisticText(
        calibration: ArKernelImuCalibration,
        key: String,
    ): String = when (key) {
        "static_sample_total" -> calibration.staticSampleTotal.toString()
        "pose_sample_total" -> calibration.poseSampleTotal.toString()
        "observed_max_gap_ms" -> calibration.observedMaxGapMs.toString()
        "observed_max_rate_error_permille" ->
            calibration.observedMaxRateErrorPermille.toString()
        "observed_peak_rate_dps" ->
            imucalFixed(calibration.observedPeakRateDps)
        "gravity_magnitude_error_mps2" ->
            imucalFixed(calibration.gravityMagnitudeErrorMps2)
        "accel_bias_x_mps2" -> imucalFixed(calibration.accelBiasXMps2)
        "accel_bias_y_mps2" -> imucalFixed(calibration.accelBiasYMps2)
        "accel_bias_z_mps2" -> imucalFixed(calibration.accelBiasZMps2)
        "accel_bias_norm_mps2" -> imucalFixed(calibration.accelBiasNormMps2)
        "accel_scale_x_permille" -> imucalFixed(calibration.accelScaleXPermille)
        "accel_scale_y_permille" -> imucalFixed(calibration.accelScaleYPermille)
        "accel_scale_z_permille" -> imucalFixed(calibration.accelScaleZPermille)
        "accel_scale_error_max_permille" ->
            calibration.accelScaleErrorMaxPermille.toString()
        "accel_misalignment_max_permille" ->
            calibration.accelMisalignmentMaxPermille.toString()
        "accel_residual_rms_mps2" ->
            imucalFixed(calibration.accelResidualRmsMps2)
        "accel_repeat_cycles" -> calibration.accelRepeatCycles.toString()
        "accel_noise_density_mg_sqrt_hz" ->
            imucalFixed(calibration.accelNoiseDensityMgSqrtHz)
        "accel_bias_stability_mg" ->
            imucalFixed(calibration.accelBiasStabilityMg)
        "gyro_bias_x_dps" -> imucalFixed(calibration.gyroBiasXDps)
        "gyro_bias_y_dps" -> imucalFixed(calibration.gyroBiasYDps)
        "gyro_bias_z_dps" -> imucalFixed(calibration.gyroBiasZDps)
        "gyro_bias_norm_dps" -> imucalFixed(calibration.gyroBiasNormDps)
        "gyro_bias_spread_dps" -> imucalFixed(calibration.gyroBiasSpreadDps)
        "gyro_scale_error_max_permille" ->
            calibration.gyroScaleErrorMaxPermille.toString()
        "gyro_cross_axis_max_permille" ->
            calibration.gyroCrossAxisMaxPermille.toString()
        "gyro_arw_deg_sqrt_hr" -> imucalFixed(calibration.gyroArwDegSqrtHr)
        "gyro_bias_stability_dph" ->
            imucalFixed(calibration.gyroBiasStabilityDph)
        "angle_drift_deg_per_min" ->
            imucalFixed(calibration.angleDriftDegPerMin)
        "position_drift_m_per_min" ->
            imucalFixed(calibration.positionDriftMPerMin)
        "temperature_span_c" -> imucalFixed(calibration.temperatureSpanC)
        else -> ""
    }

    private fun imucalVerdict(
        calibration: ArKernelImuCalibration,
        key: String,
    ): Boolean = when (key) {
        "cadence_ok" -> calibration.cadenceOk
        "duration_ok" -> calibration.durationOk
        "gravity_ok" -> calibration.gravityOk
        "accel_fit_ok" -> calibration.accelFitOk
        "accel_repeat_checked" -> calibration.accelRepeatChecked
        "gyro_scale_ok" -> calibration.gyroScaleOk
        "bias_ok" -> calibration.biasOk
        "noise_ok" -> calibration.noiseOk
        "stability_ok" -> calibration.stabilityOk
        "thermal_covered" -> calibration.thermalCovered
        "calibration_sufficient" -> calibration.calibrationSufficient
        else -> false
    }

    private fun imucalStreamRow(row: ArKernelImuStreamRow): String =
        "stream=" + listOf(
            row.index.toString(),
            row.label,
            row.nominalHz.toString(),
            imucalFixed(row.measuredHz),
            row.minDtUs.toString(),
            row.maxDtUs.toString(),
            row.gapCount.toString(),
            row.duplicateCount.toString(),
            row.monotonicViolations.toString(),
            row.domain.wireName(),
        ).joinToString("|")

    private fun imucalStaticRow(row: ArKernelImuStaticRow): String =
        "static=" + listOf(
            row.index.toString(),
            row.label,
            row.durationMs.toString(),
            row.sampleCount.toString(),
            row.gyroSampleCount.toString(),
            imucalFixed(row.meanGyroXDps),
            imucalFixed(row.meanGyroYDps),
            imucalFixed(row.meanGyroZDps),
            imucalFixed(row.rmsAccMps2),
            imucalFixed(row.rmsGyroDps),
            imucalFixed(row.stabilityAccMps2),
            imucalFixed(row.stabilityGyroDps),
            row.maxGapMs.toString(),
            imucalFixed(row.temperatureC),
        ).joinToString("|")

    private fun imucalPoseRow(row: ArKernelImuPoseRow): String =
        "pose=" + listOf(
            row.index.toString(),
            row.label,
            row.direction.wireName(),
            row.sampleCount.toString(),
            imucalFixed(row.meanXMps2),
            imucalFixed(row.meanYMps2),
            imucalFixed(row.meanZMps2),
            imucalFixed(row.meanGyroXDps),
            imucalFixed(row.meanGyroYDps),
            imucalFixed(row.meanGyroZDps),
            imucalFixed(row.temperatureC),
        ).joinToString("|")

    private fun imucalTurnRow(row: ArKernelImuTurnRow): String =
        "turn=" + listOf(
            row.index.toString(),
            row.label,
            row.axis.wireName(),
            imucalFixed(row.referenceAngleDeg),
            imucalFixed(row.integratedAngleDeg),
            imucalFixed(row.offAxisAngleDeg),
            row.durationMs.toString(),
            row.sampleCount.toString(),
            imucalFixed(row.peakRateDps),
        ).joinToString("|")

    private fun imucalValidateLine(
        line: String,
        lineNumber: Int,
    ): ArKernelImuCalibrationParseResult? {
        if (line.isEmpty()) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.EMPTY_LINE,
                lineNumber,
                "Calibration line is empty",
            )
        }
        if (line.length > IMUCAL_MAX_LINE_CHARACTERS) {
            return imucalParseReject(
                ArKernelImuCalibrationRejection.LINE_TOO_LONG,
                lineNumber,
                "Calibration line is too long",
            )
        }
        return null
    }

    private fun imucalParseReject(
        rejection: ArKernelImuCalibrationRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelImuCalibrationParseResult =
        ArKernelImuCalibrationParseResult(
            calibration = null,
            rejection = rejection,
            lineNumber = lineNumber,
            detail = detail,
        )

    private fun imucalReject(
        rejection: ArKernelImuCalibrationRejection,
        detail: String,
    ): ArKernelImuCalibrationResult =
        ArKernelImuCalibrationResult(
            calibration = null,
            rejection = rejection,
            detail = detail,
        )
}

private fun imucalFinite(value: Double): Boolean =
    !value.isNaN() && !value.isInfinite()

/**
 * Converts the standard deviation of discrete samples into a noise density:
 * a sampled white-noise process spreads its variance over the Nyquist band,
 * so the single-sided density is sigma * sqrt(2 / rate).
 */
private fun imucalNoiseFactor(rateHz: Double): Double =
    if (rateHz <= 0.0) 0.0 else sqrt(2.0 / rateHz)

private fun imucalMaxOf(values: List<Double>): Double =
    values.maxOrNull() ?: 0.0

private fun imucalNorm(vector: DoubleArray): Double {
    var total = 0.0
    for (value in vector) total += value * value
    return sqrt(total)
}

private fun imucalWeightedVector(
    rows: List<Pair<DoubleArray, Long>>,
): DoubleArray {
    var total = 0L
    for (row in rows) total += row.second
    if (total <= 0L) return doubleArrayOf(0.0, 0.0, 0.0)
    val out = DoubleArray(3)
    for (row in rows) {
        val weight = row.second.toDouble()
        for (i in 0..2) out[i] += row.first[i] * weight
    }
    for (i in 0..2) out[i] /= total.toDouble()
    return out
}

private fun imucalWeightedScalar(
    rows: List<Pair<Double, Long>>,
): Double {
    var total = 0L
    for (row in rows) total += row.second
    if (total <= 0L) return 0.0
    var sum = 0.0
    for (row in rows) sum += row.first * row.second.toDouble()
    return sum / total.toDouble()
}

private fun imucalFixed(value: Double): String =
    String.format(Locale.US, "%.6f", value)

private fun imucalBool(value: Boolean): String =
    if (value) "true" else "false"

private fun imucalUnsigned(value: String): Long? =
    if (IMUCAL_UNSIGNED.matches(value)) value.toLongOrNull() else null

private fun imucalDecimal(value: String): Double? =
    if (IMUCAL_DECIMAL.matches(value)) value.toDoubleOrNull() else null

private fun imucalSigned(value: String): Double? =
    if (IMUCAL_SIGNED.matches(value)) value.toDoubleOrNull() else null

private fun imucalUnit(value: Double, bound: Double): Boolean =
    imucalFinite(value) && value >= 0.0 && value <= bound

private fun imucalAccelTriple(x: Double, y: Double, z: Double): Boolean =
    imucalFinite(x) && imucalFinite(y) && imucalFinite(z) &&
        kotlin.math.abs(x) <= IMUCAL_MAX_ACCEL_MPS2 &&
        kotlin.math.abs(y) <= IMUCAL_MAX_ACCEL_MPS2 &&
        kotlin.math.abs(z) <= IMUCAL_MAX_ACCEL_MPS2

private fun imucalGyroTriple(x: Double, y: Double, z: Double): Boolean =
    imucalFinite(x) && imucalFinite(y) && imucalFinite(z) &&
        kotlin.math.abs(x) <= IMUCAL_MAX_GYRO_DPS &&
        kotlin.math.abs(y) <= IMUCAL_MAX_GYRO_DPS &&
        kotlin.math.abs(z) <= IMUCAL_MAX_GYRO_DPS

private fun imucalTemperature(value: Double): Boolean =
    imucalFinite(value) &&
        value >= IMUCAL_MIN_TEMPERATURE_C &&
        value <= IMUCAL_MAX_TEMPERATURE_C
