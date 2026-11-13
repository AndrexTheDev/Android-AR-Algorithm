package ai.arena.arkernel

import java.util.Locale

/**
 * Bounded canonical `ARKCAMIMU1` record: the time offset between a camera and
 * a gyroscope, and which gyroscope axis answers a pan of the image.
 *
 * A visual-inertial kernel fuses two streams that are timestamped by two
 * different pieces of hardware. If the frame timestamps are late by even a few
 * milliseconds, every frame is fused against the wrong pose - and it does not
 * look like an error. At one radian per second, ten milliseconds of offset is
 * ten milliradians of rotation the kernel attributes to the world. The
 * trajectory still looks smooth; it is simply turned, slightly, everywhere.
 *
 * The offset cannot be configured and cannot be found in a datasheet. It has
 * to be measured, and it is measured here by correlation rather than by
 * fitting: the camera is panned back and forth, and the angular rate implied
 * by the image motion is compared against the angular rate the gyroscope
 * reported, over a window of candidate lags. The lag that maximises the
 * correlation is the answer.
 *
 * **Correlation, not scale.** The image-derived rate is only known up to an
 * unknown constant, because turning it into degrees per second needs a focal
 * length and this app does not fit one. That does not matter: the *lag* at
 * which two signals agree does not depend on how either one is scaled, so the
 * measurement stands without trusting a single manufacturer number.
 *
 * **Interval averages on both sides.** A frame-to-frame image shift is an
 * average rate over the interval between two frames, not an instantaneous one.
 * Correlating it against instantaneous gyroscope samples would bias the result
 * by half an interval. The gyroscope is therefore averaged over exactly the
 * same intervals, so both sides are filtered identically and the peak lands
 * where it should.
 *
 * No frame is retained: each frame is reduced in memory to a sparse luminance
 * profile and then discarded. No intrinsics are fitted, and
 * `manufacturer_intrinsics_trusted` is written as `false`. The axis is
 * reported in the gyroscope's own sensor frame - converting it into a camera
 * frame needs the mounting rotation, which this test deliberately does not
 * estimate.
 */

private const val CAMIMU_HEADER = "ARKCAMIMU1"
private const val CAMIMU_VERSION = "1"
private const val CAMIMU_QUALITY = "unverified"
private const val CAMIMU_END = "END"
private const val CAMIMU_MAX_INPUT_CHARACTERS = 16_384
private const val CAMIMU_MAX_LINE_CHARACTERS = 256
private const val CAMIMU_MAX_FRAMES = 400
private const val CAMIMU_MAX_GYRO_SAMPLES = 200_000
private const val CAMIMU_MAX_CURVE_POINTS = 64

/** Below this many frames the correlation has nothing to work with. */
const val ARK_CAMIMU_MIN_FRAMES = 90

/** The gyroscope must be fast enough to resolve a lag of a few milliseconds. */
const val ARK_CAMIMU_MIN_GYRO_RATE_HZ = 50L

const val ARK_CAMIMU_MIN_GYRO_SAMPLES = 500

/** A pan of less than this carries more sensor noise than signal. */
const val ARK_CAMIMU_MIN_MOTION_RANGE_DPS = 30.0

/** Below this the two signals are not the same motion. */
const val ARK_CAMIMU_MIN_CORRELATION_PERMILLE = 700

private val CAMIMU_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val CAMIMU_UNSIGNED = Regex("0|[1-9][0-9]*")
private val CAMIMU_SIGNED = Regex("-?(0|[1-9][0-9]*)")
private val CAMIMU_DECIMAL = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]{1,6})?")

private val CAMIMU_KEYS = listOf(
    "version",
    "run_id",
    "device_token",
    "camera_id",
    "camera_level",
    "camera_facing",
    "sensor_orientation_deg",
    "profile_width",
    "profile_height",
    "frame_count",
    "gyro_sample_count",
    "duration_ms",
    "frame_interval_median_us",
    "gyro_rate_median_hz",
    "lag_min_ms",
    "lag_step_ms",
    "curve_points",
    "image_rate_rms",
    "gyro_rate_rms",
    "motion_range_dps",
    "best_axis",
    "best_axis_sign",
    "best_correlation_permille",
    "best_lag_ms",
    "curve_mean_abs_permille",
    "peak_margin_permille",
    "estimated_offset_us",
    "timestamp_domain",
    "domain_offset_ms",
    "automatic_upload_disabled",
    "manual_share_only",
    "no_frames_retained",
    "no_intrinsics_fitted",
    "manufacturer_intrinsics_trusted",
    "no_quality_claim",
    "frames_ok",
    "gyro_ok",
    "motion_ok",
    "correlation_ok",
    "peak_interior_ok",
    "alignment_ok",
    "quality_grade",
)

private val CAMIMU_SAFETY_TRUE_KEYS = listOf(
    "automatic_upload_disabled",
    "manual_share_only",
    "no_frames_retained",
    "no_intrinsics_fitted",
    "no_quality_claim",
)

enum class ArKernelCameraImuAxis(val token: String) {
    X("x"),
    Y("y"),
    Z("z"),
    ;

    companion object {
        fun ofToken(token: String): ArKernelCameraImuAxis? =
            values().firstOrNull { it.token == token }
    }
}

enum class ArKernelCameraImuRejection {
    NONE,
    INPUT_TOO_LARGE,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_TOKEN,
    INVALID_AXIS,
    INVALID_QUALITY,
    INVALID_ROW,
    INVALID_ORDER,
    DUPLICATE_CURVE_INDEX,
    NON_MONOTONIC_LAG,
    UNSAFE_INVARIANT,
    STATISTICS_MISMATCH,
}

/** Fail-closed parse result; on rejection no partially trusted record is returned. */
data class ArKernelCameraImuParseResult(
    val record: ArKernelCameraImuAlignment?,
    val rejection: ArKernelCameraImuRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelCameraImuRejection.NONE && record != null
}

/** One point of the correlation curve: how well the two signals agree at a lag. */
data class ArKernelCameraImuCurveRow(
    val axis: ArKernelCameraImuAxis,
    val index: Int,
    val lagMs: Int,
    val correlationPermille: Int,
) {
    init {
        require(index >= 0) { "Invalid camera IMU curve index" }
        require(lagMs in -1_000..1_000) { "Invalid camera IMU lag" }
        require(correlationPermille in -1_000..1_000) {
            "Invalid camera IMU correlation"
        }
    }
}

/**
 * One bounded camera-IMU alignment measurement.
 *
 * `lagMs` is the shift applied to the gyroscope series: a positive lag means
 * the image motion agrees with what the gyroscope reported *later*, which is
 * only possible if the frame timestamps are late. The record therefore reports
 * `estimatedOffsetUs = -bestLagMs * 1000`: a positive value is the amount by
 * which frame timestamps trail the true capture instant, and a kernel should
 * subtract it before fusing.
 */
data class ArKernelCameraImuAlignment(
    val runId: String,
    val deviceToken: String,
    val cameraId: String,
    val cameraLevel: ArKernelCameraLevel,
    val cameraFacing: ArKernelCameraFacing,
    val sensorOrientationDeg: Int,
    val profileWidth: Int,
    val profileHeight: Int,
    val frameCount: Int,
    val gyroSampleCount: Int,
    val durationMs: Long,
    val frameIntervalMedianUs: Long,
    val gyroRateMedianHz: Long,
    val imageRateRms: Double,
    val gyroRateRms: Double,
    val motionRangeDps: Double,
    val timestampDomain: CameraTimestampDomain?,
    val domainOffsetMs: Long,
    val lagMinMs: Int,
    val lagStepMs: Int,
    val curve: List<ArKernelCameraImuCurveRow>,
) {
    init {
        require(CAMIMU_TOKEN.matches(runId)) { "Invalid camera IMU run id" }
        require(CAMIMU_TOKEN.matches(deviceToken)) {
            "Invalid camera IMU device token"
        }
        require(CAMIMU_TOKEN.matches(cameraId)) {
            "Invalid camera IMU camera id"
        }
        require(sensorOrientationDeg in 0..359) {
            "Invalid camera IMU sensor orientation"
        }
        require(profileWidth in 8..4_096 && profileHeight in 1..4_096) {
            "Invalid camera IMU profile size"
        }
        require(frameCount in 0..CAMIMU_MAX_FRAMES) {
            "Camera IMU frame count is out of range"
        }
        require(gyroSampleCount in 0..CAMIMU_MAX_GYRO_SAMPLES) {
            "Camera IMU gyroscope sample count is out of range"
        }
        require(durationMs >= 0L) { "Invalid camera IMU duration" }
        require(frameIntervalMedianUs >= 0L) {
            "Invalid camera IMU frame interval"
        }
        require(gyroRateMedianHz >= 0L) {
            "Invalid camera IMU gyroscope rate"
        }
        require(imageRateRms.isFinite() && imageRateRms >= 0.0) {
            "Invalid camera IMU image rate"
        }
        require(gyroRateRms.isFinite() && gyroRateRms >= 0.0) {
            "Invalid camera IMU gyroscope rate spread"
        }
        require(motionRangeDps.isFinite() && motionRangeDps >= 0.0) {
            "Invalid camera IMU motion range"
        }
        require(lagStepMs > 0 && lagStepMs <= 100) {
            "Invalid camera IMU lag step"
        }
        require(curve.isNotEmpty() && curve.size % 3 == 0) {
            "Camera IMU curve does not cover three axes evenly"
        }
        val points = curve.size / 3
        require(points <= CAMIMU_MAX_CURVE_POINTS) {
            "Camera IMU curve carries more than $CAMIMU_MAX_CURVE_POINTS points"
        }
        for (axis in ArKernelCameraImuAxis.values()) {
            require(curve.count { it.axis == axis } == points) {
                "Camera IMU curve is uneven across its axes"
            }
        }
        require(curve.withIndex().all {
            val expected = it.index % points
            val expectedAxis = ArKernelCameraImuAxis.values()[it.index / points]
            it.value.index == expected && it.value.axis == expectedAxis
        }) {
            "Camera IMU curve rows are not grouped by axis and ordered"
        }
        require(curve.all { it.lagMs == lagMinMs + it.index * lagStepMs }) {
            "Camera IMU curve lags disagree with the declared range"
        }
    }

    val curvePoints: Int get() = curve.size / 3

    val lagMaxMs: Int get() = lagMinMs + (curvePoints - 1) * lagStepMs

    /** The point where the two signals agree best, over all three axes. */
    val peak: ArKernelCameraImuCurveRow
        get() = curve.reduce { best, row ->
            if (kotlin.math.abs(row.correlationPermille) >
                kotlin.math.abs(best.correlationPermille)
            ) {
                row
            } else {
                best
            }
        }

    val bestAxis: ArKernelCameraImuAxis get() = peak.axis

    /** Whether that axis turns with the image motion or against it. */
    val bestAxisSign: Int
        get() = if (peak.correlationPermille < 0) -1 else 1

    val bestCorrelationPermille: Int get() = peak.correlationPermille

    val bestLagMs: Int get() = peak.lagMs

    /** Mean magnitude of the whole curve; the peak must stand clear of it. */
    val curveMeanAbsPermille: Int =
        if (curve.isEmpty()) 0
        else curve.sumOf { kotlin.math.abs(it.correlationPermille) } /
            curve.size

    val peakMarginPermille: Int =
        kotlin.math.abs(bestCorrelationPermille) - curveMeanAbsPermille

    /**
     * Positive means frame timestamps are late by this many microseconds; a
     * kernel should subtract it from every frame timestamp before fusing.
     */
    val estimatedOffsetUs: Long = -bestLagMs.toLong() * 1_000L

    val framesOk: Boolean get() = frameCount >= ARK_CAMIMU_MIN_FRAMES

    val gyroOk: Boolean
        get() = gyroSampleCount >= ARK_CAMIMU_MIN_GYRO_SAMPLES &&
            gyroRateMedianHz >= ARK_CAMIMU_MIN_GYRO_RATE_HZ

    val motionOk: Boolean
        get() = motionRangeDps >= ARK_CAMIMU_MIN_MOTION_RANGE_DPS &&
            imageRateRms > 0.0

    val correlationOk: Boolean
        get() = kotlin.math.abs(bestCorrelationPermille) >=
            ARK_CAMIMU_MIN_CORRELATION_PERMILLE

    /**
     * A peak pinned to either end of the search window has not been found: it
     * only says the true lag is somewhere outside the window.
     */
    val peakInteriorOk: Boolean
        get() = bestLagMs > lagMinMs && bestLagMs < lagMaxMs

    val alignmentOk: Boolean
        get() = framesOk && gyroOk && motionOk && correlationOk &&
            peakInteriorOk

    /** What to do next, in the order the campaign needs it. */
    fun outstandingSteps(): List<String> {
        val steps = ArrayList<String>()
        if (!framesOk) steps.add("frames")
        if (!gyroOk) steps.add("gyro")
        if (!motionOk) steps.add("motion")
        if (!correlationOk) steps.add("scene")
        if (!peakInteriorOk) steps.add("range")
        return steps
    }
}

/** Bounded structural summary; never a quality or accuracy grade. */
data class ArKernelCameraImuReport(
    val frameCount: Int,
    val bestAxisToken: String,
    val estimatedOffsetUs: Long,
    val correlationPermille: Int,
    val alignmentOk: Boolean,
    val qualityGrade: String = CAMIMU_QUALITY,
)

/** Canonical `ARKCAMIMU1` text adapter; fail-closed in both directions. */
object ArKernelCameraImuAdapter {

    fun encode(record: ArKernelCameraImuAlignment): String {
        val lines = ArrayList<String>(
            CAMIMU_KEYS.size + record.curve.size + 2)
        lines.add(CAMIMU_HEADER)
        lines.add("version=$CAMIMU_VERSION")
        lines.add("run_id=${record.runId}")
        lines.add("device_token=${record.deviceToken}")
        lines.add("camera_id=${record.cameraId}")
        lines.add("camera_level=${record.cameraLevel.token}")
        lines.add("camera_facing=${record.cameraFacing.token}")
        lines.add("sensor_orientation_deg=${record.sensorOrientationDeg}")
        lines.add("profile_width=${record.profileWidth}")
        lines.add("profile_height=${record.profileHeight}")
        lines.add("frame_count=${record.frameCount}")
        lines.add("gyro_sample_count=${record.gyroSampleCount}")
        lines.add("duration_ms=${record.durationMs}")
        lines.add(
            "frame_interval_median_us=${record.frameIntervalMedianUs}")
        lines.add("gyro_rate_median_hz=${record.gyroRateMedianHz}")
        lines.add("lag_min_ms=${record.lagMinMs}")
        lines.add("lag_step_ms=${record.lagStepMs}")
        lines.add("curve_points=${record.curvePoints}")
        lines.add("image_rate_rms=${fixed(record.imageRateRms)}")
        lines.add("gyro_rate_rms=${fixed(record.gyroRateRms)}")
        lines.add("motion_range_dps=${fixed(record.motionRangeDps)}")
        lines.add("best_axis=${record.bestAxis.token}")
        lines.add("best_axis_sign=${record.bestAxisSign}")
        lines.add(
            "best_correlation_permille=${record.bestCorrelationPermille}")
        lines.add("best_lag_ms=${record.bestLagMs}")
        lines.add(
            "curve_mean_abs_permille=${record.curveMeanAbsPermille}")
        lines.add("peak_margin_permille=${record.peakMarginPermille}")
        lines.add("estimated_offset_us=${record.estimatedOffsetUs}")
        lines.add(
            "timestamp_domain=${record.timestampDomain?.name ?: "UNKNOWN"}")
        lines.add("domain_offset_ms=${record.domainOffsetMs}")
        // Written out in full because the order has to match CAMIMU_KEYS,
        // and one of these invariants is false rather than true.
        lines.add("automatic_upload_disabled=true")
        lines.add("manual_share_only=true")
        lines.add("no_frames_retained=true")
        lines.add("no_intrinsics_fitted=true")
        lines.add("manufacturer_intrinsics_trusted=false")
        lines.add("no_quality_claim=true")
        lines.add("frames_ok=${bool(record.framesOk)}")
        lines.add("gyro_ok=${bool(record.gyroOk)}")
        lines.add("motion_ok=${bool(record.motionOk)}")
        lines.add("correlation_ok=${bool(record.correlationOk)}")
        lines.add("peak_interior_ok=${bool(record.peakInteriorOk)}")
        lines.add("alignment_ok=${bool(record.alignmentOk)}")
        lines.add("quality_grade=$CAMIMU_QUALITY")
        for (row in record.curve) {
            lines.add(
                "curve=${row.axis.token}|${row.index}|${row.lagMs}|" +
                    "${row.correlationPermille}")
        }
        lines.add(CAMIMU_END)
        val text = lines.joinToString("\n")
        require(text.length <= CAMIMU_MAX_INPUT_CHARACTERS) {
            "Camera IMU record exceeds its bounded size"
        }
        require(lines.all { it.length <= CAMIMU_MAX_LINE_CHARACTERS }) {
            "Camera IMU record line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelCameraImuParseResult {
        if (text.length > CAMIMU_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelCameraImuRejection.INPUT_TOO_LARGE, 0,
                "Camera IMU input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelCameraImuRejection.INVALID_SCHEMA, 0,
                "Camera IMU input contains no record")
        }
        val lines = body.lineSequence().toList()
        if (lines[0] != CAMIMU_HEADER) {
            return reject(
                ArKernelCameraImuRejection.INVALID_HEADER, 1,
                "Camera IMU header is invalid")
        }
        if (lines.size < CAMIMU_KEYS.size + 2) {
            return reject(
                ArKernelCameraImuRejection.INVALID_SCHEMA, 0,
                "Camera IMU record is too short")
        }
        val values = LinkedHashMap<String, String>(CAMIMU_KEYS.size)
        for (index in CAMIMU_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            if (lineText.length > CAMIMU_MAX_LINE_CHARACTERS) {
                return reject(
                    ArKernelCameraImuRejection.LINE_TOO_LONG, lineNumber,
                    "Camera IMU line exceeds its bounded length")
            }
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelCameraImuRejection.INVALID_SCHEMA, lineNumber,
                    "Camera IMU metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != CAMIMU_KEYS[index]) {
                return reject(
                    ArKernelCameraImuRejection.INVALID_SCHEMA, lineNumber,
                    "Camera IMU metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        for (key in CAMIMU_SAFETY_TRUE_KEYS) {
            if (values[key] != "true") {
                return reject(
                    ArKernelCameraImuRejection.UNSAFE_INVARIANT, 0,
                    "Camera IMU invariant $key must be true")
            }
        }
        if (values["manufacturer_intrinsics_trusted"] != "false") {
            return reject(
                ArKernelCameraImuRejection.UNSAFE_INVARIANT, 0,
                "Camera IMU must not trust manufacturer intrinsics")
        }
        if (values["version"] != CAMIMU_VERSION) {
            return reject(
                ArKernelCameraImuRejection.INVALID_VALUE, 2,
                "Unsupported camera IMU record version")
        }
        if (values["quality_grade"] != CAMIMU_QUALITY) {
            return reject(
                ArKernelCameraImuRejection.INVALID_QUALITY,
                CAMIMU_KEYS.size + 1,
                "Camera IMU record cannot assert assessed quality")
        }
        val declaredPoints = values["curve_points"]!!.toIntOrNull()
            ?: return reject(
                ArKernelCameraImuRejection.INVALID_VALUE, 17,
                "Camera IMU curve point count is not a number")

        val rowLines = lines.drop(CAMIMU_KEYS.size + 1).dropLast(1)
            .map { it.trimEnd('\r') }
        if (rowLines.size != declaredPoints * 3) {
            return reject(
                ArKernelCameraImuRejection.INVALID_VALUE, 17,
                "Camera IMU curve point count disagrees with its rows")
        }
        val curve = ArrayList<ArKernelCameraImuCurveRow>(rowLines.size)
        var previousLag = Int.MIN_VALUE
        for ((offset, lineText) in rowLines.withIndex()) {
            val lineNumber = CAMIMU_KEYS.size + 2 + offset
            if (lineText.isEmpty()) {
                return reject(
                    ArKernelCameraImuRejection.EMPTY_LINE, lineNumber,
                    "Camera IMU record contains an empty line")
            }
            if (lineText.length > CAMIMU_MAX_LINE_CHARACTERS) {
                return reject(
                    ArKernelCameraImuRejection.LINE_TOO_LONG, lineNumber,
                    "Camera IMU line exceeds its bounded length")
            }
            val parts = lineText.split('|')
            if (parts.size != 4) {
                return reject(
                    ArKernelCameraImuRejection.INVALID_ROW, lineNumber,
                    "Camera IMU curve row does not have four fields")
            }
            val axis = ArKernelCameraImuAxis.ofToken(
                parts[0].removePrefix("curve="))
                ?: return reject(
                    ArKernelCameraImuRejection.INVALID_AXIS, lineNumber,
                    "Camera IMU curve axis is unknown")
            val index = parts[1].toIntOrNull()
                ?: return reject(
                    ArKernelCameraImuRejection.INVALID_ROW, lineNumber,
                    "Camera IMU curve index is not a number")
            val lagMs = parts[2].toIntOrNull()
                ?: return reject(
                    ArKernelCameraImuRejection.INVALID_ROW, lineNumber,
                    "Camera IMU curve lag is not a number")
            val correlation = parts[3].toIntOrNull()
                ?: return reject(
                    ArKernelCameraImuRejection.INVALID_ROW, lineNumber,
                    "Camera IMU curve correlation is not a number")
            val expectedAxis = ArKernelCameraImuAxis
                .values()[offset / declaredPoints]
            if (axis != expectedAxis || index != offset % declaredPoints) {
                return reject(
                    ArKernelCameraImuRejection.INVALID_ORDER, lineNumber,
                    "Camera IMU curve rows are not grouped by axis")
            }
            if (index > 0 && lagMs <= previousLag) {
                return reject(
                    ArKernelCameraImuRejection.NON_MONOTONIC_LAG,
                    lineNumber, "Camera IMU curve lags are not increasing")
            }
            previousLag = lagMs
            curve.add(
                ArKernelCameraImuCurveRow(
                    axis = axis,
                    index = index,
                    lagMs = lagMs,
                    correlationPermille = correlation,
                ))
        }

        val level = ArKernelCameraLevel.ofToken(values["camera_level"]!!)
            ?: return reject(
                ArKernelCameraImuRejection.INVALID_TOKEN, 6,
                "Camera IMU hardware level is unknown")
        val facing = ArKernelCameraFacing.ofToken(values["camera_facing"]!!)
            ?: return reject(
                ArKernelCameraImuRejection.INVALID_TOKEN, 7,
                "Camera IMU facing is unknown")
        val domainText = values["timestamp_domain"]!!
        val domain = if (domainText == "UNKNOWN") {
            null
        } else {
            runCatching {
                CameraTimestampDomain.valueOf(domainText)
            }.getOrNull()
                ?: return reject(
                    ArKernelCameraImuRejection.INVALID_TOKEN, 28,
                    "Camera IMU timestamp domain is unknown")
        }
        val record = runCatching {
            ArKernelCameraImuAlignment(
                runId = values["run_id"]!!,
                deviceToken = values["device_token"]!!,
                cameraId = values["camera_id"]!!,
                cameraLevel = level,
                cameraFacing = facing,
                sensorOrientationDeg =
                    values["sensor_orientation_deg"]!!.toIntOrNull()
                        ?: throw IllegalArgumentException("orientation"),
                profileWidth = values["profile_width"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("profile"),
                profileHeight = values["profile_height"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("profile"),
                frameCount = values["frame_count"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("frames"),
                gyroSampleCount = values["gyro_sample_count"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("gyro"),
                durationMs = values["duration_ms"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("duration"),
                frameIntervalMedianUs =
                    values["frame_interval_median_us"]!!.toLongOrNull()
                        ?: throw IllegalArgumentException("interval"),
                gyroRateMedianHz =
                    values["gyro_rate_median_hz"]!!.toLongOrNull()
                        ?: throw IllegalArgumentException("rate"),
                imageRateRms = decimal(values["image_rate_rms"]!!)
                    ?: throw IllegalArgumentException("image"),
                gyroRateRms = decimal(values["gyro_rate_rms"]!!)
                    ?: throw IllegalArgumentException("gyroRms"),
                motionRangeDps = decimal(values["motion_range_dps"]!!)
                    ?: throw IllegalArgumentException("motion"),
                timestampDomain = domain,
                domainOffsetMs = values["domain_offset_ms"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("offset"),
                lagMinMs = values["lag_min_ms"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("lag"),
                lagStepMs = values["lag_step_ms"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("lag"),
                curve = curve,
            )
        }.getOrElse {
            return reject(
                ArKernelCameraImuRejection.INVALID_VALUE, 0,
                "Camera IMU metadata is invalid: ${it.message}")
        }

        val expected = mapOf(
            "best_axis" to record.bestAxis.token,
            "best_axis_sign" to record.bestAxisSign.toString(),
            "best_correlation_permille" to
                record.bestCorrelationPermille.toString(),
            "best_lag_ms" to record.bestLagMs.toString(),
            "curve_mean_abs_permille" to
                record.curveMeanAbsPermille.toString(),
            "peak_margin_permille" to record.peakMarginPermille.toString(),
            "estimated_offset_us" to record.estimatedOffsetUs.toString(),
            "frames_ok" to bool(record.framesOk),
            "gyro_ok" to bool(record.gyroOk),
            "motion_ok" to bool(record.motionOk),
            "correlation_ok" to bool(record.correlationOk),
            "peak_interior_ok" to bool(record.peakInteriorOk),
            "alignment_ok" to bool(record.alignmentOk),
        )
        for ((key, expectedValue) in expected) {
            if (values[key] != expectedValue) {
                return reject(
                    ArKernelCameraImuRejection.STATISTICS_MISMATCH,
                    CAMIMU_KEYS.indexOf(key) + 2,
                    "Camera IMU $key disagrees with its own curve")
            }
        }
        return ArKernelCameraImuParseResult(
            record, ArKernelCameraImuRejection.NONE, 0, "accepted")
    }

    fun report(record: ArKernelCameraImuAlignment): ArKernelCameraImuReport =
        ArKernelCameraImuReport(
            frameCount = record.frameCount,
            bestAxisToken = record.bestAxis.token,
            estimatedOffsetUs = record.estimatedOffsetUs,
            correlationPermille = record.bestCorrelationPermille,
            alignmentOk = record.alignmentOk,
        )

    private fun reject(
        rejection: ArKernelCameraImuRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelCameraImuParseResult =
        ArKernelCameraImuParseResult(null, rejection, lineNumber, detail)
}

private fun decimal(value: String): Double? =
    if (CAMIMU_DECIMAL.matches(value)) value.toDoubleOrNull() else null

private fun fixed(value: Double): String =
    String.format(Locale.US, "%.6f", value)

private fun bool(value: Boolean): String = if (value) "true" else "false"
