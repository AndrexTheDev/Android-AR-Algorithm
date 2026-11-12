package ai.arena.arkernel

import java.util.Locale
import kotlin.math.sqrt

/**
 * Bounded canonical `ARKCAMT1` record of one camera timing run.
 *
 * A visual-inertial kernel fuses camera frames with inertial samples. Three
 * things about the camera decide whether that fusion can work at all, and
 * none of them needs a single image to be kept:
 *
 *  - **Cadence.** Does the stream actually deliver the rate it promises, or
 *    does it arrive in bursts with holes? A hole is a gap in the trajectory
 *    the kernel has to bridge by integration.
 *  - **Clock domain.** Which of the Android clocks are the frame timestamps
 *    on? A frame stamped in `uptimeMillis` and an inertial sample stamped in
 *    `elapsedRealtime` differ by the whole time the device spent in deep
 *    sleep, which is seconds - orders of magnitude more than the exposure.
 *    This is decided by [CameraTimestampNormalizer], not guessed.
 *  - **Exposure.** A long exposure smears a moving camera. At 90 deg/s a
 *    33 ms exposure smears the whole frame by three degrees, which is more
 *    than most feature trackers tolerate.
 *
 * A fourth is stability: if the crop region, the stabilisation mode or the
 * distortion-correction mode changes mid-run, every intrinsic number
 * collected during that run describes a camera that no longer exists. That is
 * [CameraCalibrationRuntimeMonitor]'s one job, and this record carries its
 * verdict rather than a number.
 *
 * What this is not: it fits no intrinsics, keeps no frame, and does not trust
 * the manufacturer's focal length. `manufacturer_intrinsics_trusted` is
 * written as `false` and the grade stays permanently `unverified`. Timing and
 * stability are preconditions for measuring a camera, not measurements of one.
 */

private const val CAMTIM_HEADER = "ARKCAMT1"
private const val CAMTIM_VERSION = "1"
private const val CAMTIM_QUALITY = "unverified"
private const val CAMTIM_END = "END"
private const val CAMTIM_MAX_INPUT_CHARACTERS = 16_384
private const val CAMTIM_MAX_LINE_CHARACTERS = 256
private const val CAMTIM_MAX_FRAMES = 200
private const val CAMTIM_MIN_FRAMES = 8
private const val CAMTIM_MAX_DIMENSION = 16_384
private const val CAMTIM_UNKNOWN = -1L

private val CAMTIM_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val CAMTIM_IDS = Regex("[A-Za-z0-9._:,-]{1,64}")
private val CAMTIM_UNSIGNED = Regex("0|[1-9][0-9]*")
private val CAMTIM_DECIMAL = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]{1,6})?")

private val CAMTIM_KEYS = listOf(
    "version",
    "run_id",
    "device_token",
    "camera_id",
    "camera_level",
    "camera_facing",
    "physical_camera_ids",
    "sensor_orientation_deg",
    "width",
    "height",
    "frame_count",
    "timestamp_domain",
    "domain_offset_ns",
    "interval_mean_ns",
    "interval_median_ns",
    "interval_jitter_ns",
    "interval_max_gap_ns",
    "exposure_min_ns",
    "exposure_median_ns",
    "exposure_max_ns",
    "sensitivity_median",
    "focus_distance_diopters",
    "focal_length_mm",
    "rolling_shutter_skew_ns",
    "smear_deg_per_90deg_s",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "manual_share_only",
    "frame_retention_disabled",
    "intrinsics_fitting_disabled",
    "manufacturer_intrinsics_trusted",
    "frames_ok",
    "cadence_ok",
    "timestamp_domain_ok",
    "exposure_ok",
    "capture_stable_ok",
    "camera_sufficient",
    "quality_grade",
)

private val CAMTIM_SAFETY_KEYS = listOf(
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "manual_share_only",
    "frame_retention_disabled",
    "intrinsics_fitting_disabled",
    "manufacturer_intrinsics_trusted",
)

/** How a camera reports itself; `UNKNOWN` is honest, not a failure. */
enum class ArKernelCameraLevel(val token: String) {
    LEGACY("legacy"),
    LIMITED("limited"),
    FULL("full"),
    LEVEL_3("level3"),
    EXTERNAL("external"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun ofToken(token: String): ArKernelCameraLevel? =
            values().firstOrNull { it.token == token }
    }
}

enum class ArKernelCameraFacing(val token: String) {
    BACK("back"),
    FRONT("front"),
    EXTERNAL("external"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun ofToken(token: String): ArKernelCameraFacing? =
            values().firstOrNull { it.token == token }
    }
}

/** Why a run cannot support a camera measurement at all. */
enum class ArKernelCameraTimingRejection {
    NONE,
    INPUT_TOO_LARGE,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_TOKEN,
    INVALID_LEVEL,
    INVALID_FACING,
    INVALID_DOMAIN,
    INVALID_QUALITY,
    INVALID_ORDER,
    INVALID_ROW,
    DUPLICATE_FRAME_INDEX,
    TOO_FEW_FRAMES,
    TOO_MANY_FRAMES,
    NON_MONOTONIC_TIMESTAMP,
    UNSAFE_INVARIANT,
    STATISTICS_MISMATCH,
}

/** Fail-closed parse result; on rejection no partially trusted record is returned. */
data class ArKernelCameraTimingParseResult(
    val record: ArKernelCameraTiming?,
    val rejection: ArKernelCameraTimingRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelCameraTimingRejection.NONE && record != null
}

/** One delivered frame. `intervalNs` is zero on the first frame. */
data class ArKernelCameraFrameRow(
    val index: Int,
    val timestampNs: Long,
    val intervalNs: Long,
    val exposureNs: Long,
    val sensitivityIso: Long,
    val focusDistanceDiopters: Double,
) {
    init {
        require(index >= 0) { "Invalid camera frame index" }
        require(timestampNs > 0L) { "Invalid camera frame timestamp" }
        require(intervalNs >= 0L) { "Invalid camera frame interval" }
        require(exposureNs >= 0L) { "Invalid camera frame exposure" }
        require(sensitivityIso >= 0L) { "Invalid camera frame sensitivity" }
        require(focusDistanceDiopters.isFinite() &&
            focusDistanceDiopters >= -1.0) {
            "Invalid camera frame focus distance"
        }
    }
}

/**
 * One bounded camera timing record.
 *
 * Every declared statistic is recomputed from the rows on parse and any
 * disagreement rejects the record, so the numbers in the file cannot claim
 * more than the frames in the file support.
 */
data class ArKernelCameraTiming(
    val runId: String,
    val deviceToken: String,
    val cameraId: String,
    val cameraLevel: ArKernelCameraLevel,
    val cameraFacing: ArKernelCameraFacing,
    val physicalCameraIds: String,
    val sensorOrientationDeg: Int,
    val width: Int,
    val height: Int,
    val timestampDomain: CameraTimestampDomain?,
    val domainOffsetNs: Long,
    val timestampFailure: CameraTimestampFailureReason? = null,
    val focusDistanceDiopters: Double = -1.0,
    val focalLengthMm: Double = -1.0,
    val rollingShutterSkewNs: Long = CAMTIM_UNKNOWN,
    val captureFailure: CameraCalibrationRuntimeFailure? = null,
    val rows: List<ArKernelCameraFrameRow>,
) {
    init {
        require(CAMTIM_TOKEN.matches(runId)) { "Invalid camera timing run id" }
        require(CAMTIM_TOKEN.matches(deviceToken)) {
            "Invalid camera timing device token"
        }
        require(CAMTIM_TOKEN.matches(cameraId)) {
            "Invalid camera timing camera id"
        }
        require(CAMTIM_IDS.matches(physicalCameraIds)) {
            "Invalid camera timing physical camera ids"
        }
        require(sensorOrientationDeg in 0..359) {
            "Invalid camera timing sensor orientation"
        }
        require(width in 1..CAMTIM_MAX_DIMENSION) {
            "Invalid camera timing width"
        }
        require(height in 1..CAMTIM_MAX_DIMENSION) {
            "Invalid camera timing height"
        }
        require(rows.size in CAMTIM_MIN_FRAMES..CAMTIM_MAX_FRAMES) {
            "Invalid camera timing frame count"
        }
        require(rows.withIndex().all { it.value.index == it.index }) {
            "Camera timing frame indices are not contiguous"
        }
        require(focusDistanceDiopters.isFinite() &&
            focusDistanceDiopters >= -1.0) {
            "Invalid camera timing focus distance"
        }
        require(focalLengthMm.isFinite() && focalLengthMm >= -1.0) {
            "Invalid camera timing focal length"
        }
        require(rollingShutterSkewNs == CAMTIM_UNKNOWN ||
            rollingShutterSkewNs >= 0L) {
            "Invalid camera timing rolling shutter skew"
        }
    }

    private val intervals: List<Long> get() = rows.drop(1).map { it.intervalNs }
    private val exposures: List<Long> get() = rows.map { it.exposureNs }

    val frameCount: Int get() = rows.size

    val intervalMeanNs: Long
        get() = if (intervals.isEmpty()) 0L else
            (intervals.sumOf { it } / intervals.size)

    val intervalMedianNs: Long get() = median(intervals)

    /** Population standard deviation of the delivery intervals. */
    val intervalJitterNs: Long
        get() {
            if (intervals.size < 2) return 0L
            val mean = intervals.sumOf { it }.toDouble() / intervals.size
            val variance = intervals.sumOf {
                val d = it.toDouble() - mean
                d * d
            } / intervals.size
            return sqrt(variance).toLong()
        }

    val intervalMaxGapNs: Long get() = intervals.maxOrNull() ?: 0L

    val exposureMinNs: Long get() = exposures.minOrNull() ?: 0L
    val exposureMedianNs: Long get() = median(exposures)
    val exposureMaxNs: Long get() = exposures.maxOrNull() ?: 0L

    val sensitivityMedian: Long
        get() = median(rows.map { it.sensitivityIso })

    /**
     * How far the image smears across one frame at 90 deg/s of rotation.
     *
     * Ninety degrees per second is a brisk but ordinary handheld pan. The
     * number is the smear during the median exposure, which is the honest
     * one to quote: the worst exposure is one bad frame, the median is what
     * the tracker lives with.
     */
    val smearDegPer90DegPerSec: Double
        get() = exposureMedianNs.toDouble() * 90.0 / 1_000_000_000.0

    /** Enough frames for the clock-domain decision to have been locked. */
    val framesOk: Boolean get() = rows.size >= CAMTIM_MIN_FRAMES

    /**
     * No hole wider than three median intervals. A single dropped frame
     * doubles one interval; three is where the kernel stops interpolating
     * and starts extrapolating.
     */
    val cadenceOk: Boolean get() {
        val medianInterval = intervalMedianNs
        if (medianInterval <= 0L) return false
        return intervalMaxGapNs <= medianInterval * 3L
    }

    /** The clock was identified and never contradicted itself during the run. */
    val timestampDomainOk: Boolean
        get() = timestampDomain != null && timestampFailure == null

    /** A third of a second per frame is already far too slow to track. */
    val exposureOk: Boolean
        get() = exposureMaxNs in 1L..CAMTIM_MAX_EXPOSURE_NS

    /** Nothing about the capture pipeline changed underneath the run. */
    val captureStableOk: Boolean get() = captureFailure == null

    val cameraSufficient: Boolean
        get() = framesOk && cadenceOk && timestampDomainOk &&
            exposureOk && captureStableOk

    /** What to do next, in the order the campaign needs it. */
    fun outstandingSteps(): List<String> {
        val steps = ArrayList<String>()
        if (!timestampDomainOk) steps.add("clock")
        if (!framesOk) steps.add("frames")
        if (!cadenceOk) steps.add("cadence")
        if (!exposureOk) steps.add("exposure")
        if (!captureStableOk) steps.add("stability")
        return steps
    }
}

/** Bounded structural summary; never a quality or accuracy grade. */
data class ArKernelCameraTimingReport(
    val frameCount: Int,
    val intervalMedianNs: Long,
    val intervalMaxGapNs: Long,
    val exposureMedianNs: Long,
    val smearDegPer90DegPerSec: Double,
    val timestampDomain: String,
    val cameraSufficient: Boolean,
    val qualityGrade: String = CAMTIM_QUALITY,
)

/**
 * Canonical `ARKCAMT1` text adapter.
 *
 * Encodes and parses fail-closed: metadata order, input/line/row budgets,
 * strictly increasing timestamps, contiguous indices and every recomputed
 * statistic are enforced, and a record that disagrees with its own rows is
 * rejected rather than corrected.
 */
object ArKernelCameraTimingAdapter {

    fun encode(record: ArKernelCameraTiming): String {
        val lines = ArrayList<String>(
            CAMTIM_KEYS.size + record.rows.size + 2)
        lines.add(CAMTIM_HEADER)
        lines.add("version=$CAMTIM_VERSION")
        lines.add("run_id=${record.runId}")
        lines.add("device_token=${record.deviceToken}")
        lines.add("camera_id=${record.cameraId}")
        lines.add("camera_level=${record.cameraLevel.token}")
        lines.add("camera_facing=${record.cameraFacing.token}")
        lines.add("physical_camera_ids=${record.physicalCameraIds}")
        lines.add("sensor_orientation_deg=${record.sensorOrientationDeg}")
        lines.add("width=${record.width}")
        lines.add("height=${record.height}")
        lines.add("frame_count=${record.frameCount}")
        lines.add(
            "timestamp_domain=${record.timestampDomain?.name ?: "UNKNOWN"}")
        lines.add("domain_offset_ns=${record.domainOffsetNs}")
        lines.add("interval_mean_ns=${record.intervalMeanNs}")
        lines.add("interval_median_ns=${record.intervalMedianNs}")
        lines.add("interval_jitter_ns=${record.intervalJitterNs}")
        lines.add("interval_max_gap_ns=${record.intervalMaxGapNs}")
        lines.add("exposure_min_ns=${record.exposureMinNs}")
        lines.add("exposure_median_ns=${record.exposureMedianNs}")
        lines.add("exposure_max_ns=${record.exposureMaxNs}")
        lines.add("sensitivity_median=${record.sensitivityMedian}")
        lines.add("focus_distance_diopters=${fixed(record.focusDistanceDiopters)}")
        lines.add("focal_length_mm=${fixed(record.focalLengthMm)}")
        lines.add("rolling_shutter_skew_ns=${record.rollingShutterSkewNs}")
        lines.add("smear_deg_per_90deg_s=${fixed(record.smearDegPer90DegPerSec)}")
        for (key in CAMTIM_SAFETY_KEYS) {
            val value = if (key == "manufacturer_intrinsics_trusted") {
                "false"
            } else {
                "true"
            }
            lines.add("$key=$value")
        }
        lines.add("frames_ok=${bool(record.framesOk)}")
        lines.add("cadence_ok=${bool(record.cadenceOk)}")
        lines.add("timestamp_domain_ok=${bool(record.timestampDomainOk)}")
        lines.add("exposure_ok=${bool(record.exposureOk)}")
        lines.add("capture_stable_ok=${bool(record.captureStableOk)}")
        lines.add("camera_sufficient=${bool(record.cameraSufficient)}")
        lines.add("quality_grade=$CAMTIM_QUALITY")
        for (row in record.rows) {
            lines.add(
                "frame=${row.index}|${row.timestampNs}|${row.intervalNs}|" +
                    "${row.exposureNs}|${row.sensitivityIso}|" +
                    fixed(row.focusDistanceDiopters))
        }
        lines.add(CAMTIM_END)
        val text = lines.joinToString("\n")
        require(text.length <= CAMTIM_MAX_INPUT_CHARACTERS) {
            "Camera timing record exceeds its bounded size"
        }
        require(lines.all { it.length <= CAMTIM_MAX_LINE_CHARACTERS }) {
            "Camera timing line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelCameraTimingParseResult {
        if (text.length > CAMTIM_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelCameraTimingRejection.INPUT_TOO_LARGE, 0,
                "Camera timing input exceeds its bounded size")
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
                ArKernelCameraTimingRejection.INVALID_SCHEMA, 0,
                "Camera timing input contains no record")
        }
        val lines = body.lineSequence().toList()
        if (lines[0] != CAMTIM_HEADER) {
            return reject(
                ArKernelCameraTimingRejection.INVALID_HEADER, 1,
                "Camera timing header is invalid")
        }
        if (lines.size < CAMTIM_KEYS.size + 3) {
            return reject(
                ArKernelCameraTimingRejection.INVALID_SCHEMA, 0,
                "Camera timing is too short")
        }
        val values = LinkedHashMap<String, String>(CAMTIM_KEYS.size)
        for (index in CAMTIM_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            if (lineText.length > CAMTIM_MAX_LINE_CHARACTERS) {
                return reject(
                    ArKernelCameraTimingRejection.LINE_TOO_LONG, lineNumber,
                    "Camera timing line exceeds its bounded length")
            }
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelCameraTimingRejection.INVALID_SCHEMA, lineNumber,
                    "Camera timing metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != CAMTIM_KEYS[index]) {
                return reject(
                    ArKernelCameraTimingRejection.INVALID_SCHEMA, lineNumber,
                    "Camera timing metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        for (key in CAMTIM_SAFETY_KEYS) {
            val expected = if (key == "manufacturer_intrinsics_trusted") {
                "false"
            } else {
                "true"
            }
            if (values[key] != expected) {
                return reject(
                    ArKernelCameraTimingRejection.UNSAFE_INVARIANT, 0,
                    "Camera timing invariant $key must be $expected")
            }
        }
        if (values["version"] != CAMTIM_VERSION) {
            return reject(
                ArKernelCameraTimingRejection.INVALID_VALUE, 2,
                "Unsupported camera timing version")
        }
        if (values["quality_grade"] != CAMTIM_QUALITY) {
            return reject(
                ArKernelCameraTimingRejection.INVALID_QUALITY,
                CAMTIM_KEYS.size + 1,
                "Camera timing cannot assert assessed quality")
        }
        val level = ArKernelCameraLevel.ofToken(values["camera_level"]!!)
            ?: return reject(
                ArKernelCameraTimingRejection.INVALID_LEVEL, 6,
                "Unknown camera level")
        val facing = ArKernelCameraFacing.ofToken(values["camera_facing"]!!)
            ?: return reject(
                ArKernelCameraTimingRejection.INVALID_FACING, 7,
                "Unknown camera facing")
        val domainToken = values["timestamp_domain"]!!
        val domain = if (domainToken == "UNKNOWN") {
            null
        } else {
            runCatching { CameraTimestampDomain.valueOf(domainToken) }
                .getOrNull()
                ?: return reject(
                    ArKernelCameraTimingRejection.INVALID_DOMAIN, 14,
                    "Unknown camera timestamp domain")
        }
        val declaredCount = values["frame_count"]!!.toLongOrNull()
            ?: return reject(
                ArKernelCameraTimingRejection.INVALID_VALUE, 12,
                "Camera timing frame count is not a number")

        val rowLines = lines.drop(CAMTIM_KEYS.size + 1)
            .dropLast(1)
            .map { it.trimEnd('\r') }
        if (rowLines.size > CAMTIM_MAX_FRAMES) {
            return reject(
                ArKernelCameraTimingRejection.TOO_MANY_FRAMES, 0,
                "Camera timing carries more than $CAMTIM_MAX_FRAMES frames")
        }
        if (rowLines.size < CAMTIM_MIN_FRAMES) {
            return reject(
                ArKernelCameraTimingRejection.TOO_FEW_FRAMES, 0,
                "Camera timing carries fewer than $CAMTIM_MIN_FRAMES frames")
        }
        if (rowLines.size.toLong() != declaredCount) {
            return reject(
                ArKernelCameraTimingRejection.INVALID_VALUE, 12,
                "Camera timing frame count disagrees with its rows")
        }
        val rows = ArrayList<ArKernelCameraFrameRow>(rowLines.size)
        var previousTimestamp = 0L
        for ((offset, lineText) in rowLines.withIndex()) {
            val lineNumber = CAMTIM_KEYS.size + 2 + offset
            if (lineText.isEmpty()) {
                return reject(
                    ArKernelCameraTimingRejection.EMPTY_LINE, lineNumber,
                    "Camera timing contains an empty line")
            }
            if (lineText.length > CAMTIM_MAX_LINE_CHARACTERS) {
                return reject(
                    ArKernelCameraTimingRejection.LINE_TOO_LONG, lineNumber,
                    "Camera timing line exceeds its bounded length")
            }
            val parts = lineText.split('|')
            if (parts.size != 6) {
                return reject(
                    ArKernelCameraTimingRejection.INVALID_ROW, lineNumber,
                    "Camera timing frame row does not have six fields")
            }
            val index = parts[0].removePrefix("frame=").toIntOrNull()
                ?: return reject(
                    ArKernelCameraTimingRejection.INVALID_ROW, lineNumber,
                    "Camera timing frame index is not a number")
            if (index != offset) {
                return reject(
                    ArKernelCameraTimingRejection.DUPLICATE_FRAME_INDEX,
                    lineNumber,
                    "Camera timing frame indices are not contiguous")
            }
            val timestamp = CAMTIM_UNSIGNED.takeIf {
                it.matches(parts[1])
            }?.let { parts[1].toLongOrNull() }
                ?: return reject(
                    ArKernelCameraTimingRejection.INVALID_ROW, lineNumber,
                    "Camera timing frame timestamp is invalid")
            if (timestamp <= previousTimestamp) {
                return reject(
                    ArKernelCameraTimingRejection.NON_MONOTONIC_TIMESTAMP,
                    lineNumber,
                    "Camera timing frame timestamps are not increasing")
            }
            previousTimestamp = timestamp
            val interval = parseUnsigned(parts[2])
                ?: return reject(
                    ArKernelCameraTimingRejection.INVALID_ROW, lineNumber,
                    "Camera timing frame interval is invalid")
            val exposure = parseUnsigned(parts[3])
                ?: return reject(
                    ArKernelCameraTimingRejection.INVALID_ROW, lineNumber,
                    "Camera timing frame exposure is invalid")
            val iso = parseUnsigned(parts[4])
                ?: return reject(
                    ArKernelCameraTimingRejection.INVALID_ROW, lineNumber,
                    "Camera timing frame sensitivity is invalid")
            val focus = parseDecimal(parts[5])
                ?: return reject(
                    ArKernelCameraTimingRejection.INVALID_ROW, lineNumber,
                    "Camera timing frame focus distance is invalid")
            rows.add(
                ArKernelCameraFrameRow(
                    index = index,
                    timestampNs = timestamp,
                    intervalNs = interval,
                    exposureNs = exposure,
                    sensitivityIso = iso,
                    focusDistanceDiopters = focus,
                ))
        }

        val record = runCatching {
            ArKernelCameraTiming(
                runId = values["run_id"]!!,
                deviceToken = values["device_token"]!!,
                cameraId = values["camera_id"]!!,
                cameraLevel = level,
                cameraFacing = facing,
                physicalCameraIds = values["physical_camera_ids"]!!,
                sensorOrientationDeg = values["sensor_orientation_deg"]!!
                    .toIntOrNull()
                    ?: throw IllegalArgumentException("orientation"),
                width = values["width"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("width"),
                height = values["height"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("height"),
                timestampDomain = domain,
                domainOffsetNs = values["domain_offset_ns"]!!
                    .toLongOrNull()
                    ?: throw IllegalArgumentException("offset"),
                timestampFailure = null,
                focusDistanceDiopters = parseDecimal(
                    values["focus_distance_diopters"]!!)
                    ?: throw IllegalArgumentException("focus"),
                focalLengthMm = parseDecimal(values["focal_length_mm"]!!)
                    ?: throw IllegalArgumentException("focal"),
                rollingShutterSkewNs = values["rolling_shutter_skew_ns"]!!
                    .toLongOrNull()
                    ?: throw IllegalArgumentException("skew"),
                captureFailure = null,
                rows = rows,
            )
        }.getOrElse {
            return reject(
                ArKernelCameraTimingRejection.INVALID_VALUE, 0,
                "Camera timing metadata is invalid: ${it.message}")
        }

        val expected = mapOf(
            "interval_mean_ns" to record.intervalMeanNs.toString(),
            "interval_median_ns" to record.intervalMedianNs.toString(),
            "interval_jitter_ns" to record.intervalJitterNs.toString(),
            "interval_max_gap_ns" to record.intervalMaxGapNs.toString(),
            "exposure_min_ns" to record.exposureMinNs.toString(),
            "exposure_median_ns" to record.exposureMedianNs.toString(),
            "exposure_max_ns" to record.exposureMaxNs.toString(),
            "sensitivity_median" to record.sensitivityMedian.toString(),
            "smear_deg_per_90deg_s" to
                fixed(record.smearDegPer90DegPerSec),
            "frames_ok" to bool(record.framesOk),
            "cadence_ok" to bool(record.cadenceOk),
            "timestamp_domain_ok" to bool(record.timestampDomainOk),
            "exposure_ok" to bool(record.exposureOk),
            "capture_stable_ok" to bool(record.captureStableOk),
            "camera_sufficient" to bool(record.cameraSufficient),
        )
        for ((key, expectedValue) in expected) {
            if (values[key] != expectedValue) {
                return reject(
                    ArKernelCameraTimingRejection.STATISTICS_MISMATCH,
                    CAMTIM_KEYS.indexOf(key) + 2,
                    "Camera timing $key disagrees with its frames")
            }
        }
        return ArKernelCameraTimingParseResult(
            record, ArKernelCameraTimingRejection.NONE, 0, "accepted")
    }

    fun report(record: ArKernelCameraTiming): ArKernelCameraTimingReport =
        ArKernelCameraTimingReport(
            frameCount = record.frameCount,
            intervalMedianNs = record.intervalMedianNs,
            intervalMaxGapNs = record.intervalMaxGapNs,
            exposureMedianNs = record.exposureMedianNs,
            smearDegPer90DegPerSec = record.smearDegPer90DegPerSec,
            timestampDomain = record.timestampDomain?.name ?: "UNKNOWN",
            cameraSufficient = record.cameraSufficient,
        )

    private fun reject(
        rejection: ArKernelCameraTimingRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelCameraTimingParseResult =
        ArKernelCameraTimingParseResult(null, rejection, lineNumber, detail)
}

/** A third of a second: past this, a moving camera cannot be tracked at all. */
const val ARK_CAMERA_MAX_EXPOSURE_NS = 33_000_000L

private const val CAMTIM_MAX_EXPOSURE_NS = ARK_CAMERA_MAX_EXPOSURE_NS

private fun parseUnsigned(value: String): Long? =
    if (CAMTIM_UNSIGNED.matches(value)) value.toLongOrNull() else null

private fun parseDecimal(value: String): Double? =
    if (CAMTIM_DECIMAL.matches(value)) value.toDoubleOrNull() else null

private fun fixed(value: Double): String =
    String.format(Locale.US, "%.6f", value)

private fun bool(value: Boolean): String = if (value) "true" else "false"

private fun median(values: List<Long>): Long {
    if (values.isEmpty()) return 0L
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        sorted[middle - 1] / 2 + sorted[middle] / 2 +
            (sorted[middle - 1] and 1L) * (sorted[middle] and 1L)
    }
}
