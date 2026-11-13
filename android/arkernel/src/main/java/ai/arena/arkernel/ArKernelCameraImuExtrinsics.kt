package ai.arena.arkernel

import kotlin.math.abs

/**
 * The `ARKCAMEXT1` record: the rotation between the camera and the IMU, and
 * the focal length that falls out of recovering it.
 *
 * The rotation is recovered by `ArKernelCameraImuRotation`; this file is the
 * bounded text form that carries it, and it recomputes every verdict at parse
 * time so a file whose numbers do not add up is rejected rather than trusted.
 *
 * **What is claimed, and what is not.** The rotation is reported, in permille
 * and as Euler angles for a human reader. The focal length is reported as
 * *implied*: it is whatever scale makes the recovered rows unit vectors, and
 * it carries the biases of the method - the motion is rarely a pure rotation
 * about the optical centre, translation is not modelled, and the profile is
 * coarser than the pixels it stands for. It is not a measured intrinsic, and
 * the record says so in a field rather than in a comment.
 *
 * **What is missing.** The translation between the camera and the IMU is not
 * in this record because it is not observable by this method: recovering it
 * needs position, and position needs metric scale. `translation_fitted` is
 * `false` for the same reason `intrinsics_fitted` is, and both are enforced.
 *
 * No frame is retained. Each frame is reduced in memory to a luminance grid,
 * differenced against the previous one, and dropped.
 */
object ArKernelCameraImuExtrinsicsAdapter {

    fun encode(record: ArKernelCameraImuExtrinsics): String {
        val lines = ArrayList<String>(EXT_KEYS.size + 2)
        lines.add(EXT_HEADER)
        lines.add("version=$EXT_VERSION")
        lines.add("run_id=${record.runId}")
        lines.add("device_token=${record.deviceToken}")
        lines.add("camera_id=${record.cameraId}")
        lines.add("camera_level=${record.cameraLevel.token}")
        lines.add("camera_facing=${record.cameraFacing.token}")
        lines.add("sensor_orientation_deg=${record.sensorOrientationDeg}")
        lines.add("profile_width=${record.profileWidth}")
        lines.add("profile_height=${record.profileHeight}")
        // Carried because a focal length in pixels is meaningless without
        // saying which pixel grid it belongs to: the same lens has a
        // different focal length in pixels at 1280x720 than at 4032x3024.
        lines.add("source_width=${record.sourceWidth}")
        lines.add("source_height=${record.sourceHeight}")
        lines.add("frame_count=${record.frameCount}")
        lines.add("gyro_sample_count=${record.gyroSampleCount}")
        lines.add("duration_ms=${record.durationMs}")
        lines.add("frame_interval_median_us=${record.frameIntervalMedianUs}")
        lines.add("gyro_rate_median_hz=${record.gyroRateMedianHz}")
        lines.add("motion_range_dps=${fixed(record.motionRangeDps)}")
        val components = record.matrix.componentsPermille()
        val names = arrayOf(
            "r_xx", "r_xy", "r_xz",
            "r_yx", "r_yy", "r_yz",
            "r_zx", "r_zy", "r_zz")
        for (index in 0..8) {
            lines.add("${names[index]}=${components[index]}")
        }
        val euler = record.matrix.eulerDegrees()
        lines.add("euler_roll_mdeg=${milliDegrees(euler[0])}")
        lines.add("euler_pitch_mdeg=${milliDegrees(euler[1])}")
        lines.add("euler_yaw_mdeg=${milliDegrees(euler[2])}")
        lines.add("focal_px_horizontal=${fixed(record.focalPxHorizontal)}")
        lines.add("focal_px_vertical=${fixed(record.focalPxVertical)}")
        lines.add("focal_px_mean=${fixed(record.focalPxMean)}")
        lines.add("focal_split_permille=${record.focalSplitPermille}")
        lines.add("conditioning_permille=${record.conditioningPermille}")
        lines.add("residual_permille=${record.residualPermille}")
        lines.add("correlation_permille=${record.correlationPermille}")
        // Written out in full because one of them is false and the order has
        // to match EXT_KEYS exactly.
        lines.add("automatic_upload_disabled=true")
        lines.add("manual_share_only=true")
        lines.add("no_frames_retained=true")
        lines.add("intrinsics_fitted=false")
        lines.add("translation_fitted=false")
        lines.add("no_quality_claim=true")
        lines.add("quality_grade=$EXT_QUALITY")
        lines.add("rotation_ok=${record.rotationOk}")
        lines.add("focal_ok=${record.focalOk}")
        lines.add("extrinsics_sufficient=${record.extrinsicsSufficient}")
        lines.add(EXT_END)
        return lines.joinToString("\n")
    }

    fun parse(text: String): ArKernelCameraImuExtrinsicsParseResult {
        if (text.length > EXT_MAX_INPUT_CHARACTERS) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.INPUT_TOO_LARGE,
                "Camera IMU extrinsics input is too large")
        }
        if (text.isEmpty()) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.EMPTY_RECORD,
                "Camera IMU extrinsics record is empty")
        }
        val lines = text.split("\n")
        for ((offset, line) in lines.withIndex()) {
            if (line.length > EXT_MAX_LINE_CHARACTERS) {
                return rejected(
                    ArKernelCameraImuExtrinsicsRejection.LINE_TOO_LONG,
                    "Camera IMU extrinsics line ${offset + 1} is too long")
            }
        }
        if (lines[0] != EXT_HEADER) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_HEADER,
                "Camera IMU extrinsics header is wrong")
        }
        if (lines.size != EXT_KEYS.size + 2) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_SCHEMA,
                "Camera IMU extrinsics record has the wrong line count")
        }
        if (lines[lines.size - 1] != EXT_END) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_SCHEMA,
                "Camera IMU extrinsics terminator is missing")
        }
        for (index in 0 until EXT_KEYS.size) {
            val line = lines[index + 1]
            val separator = line.indexOf('=')
            if (separator <= 0 ||
                line.substring(0, separator) != EXT_KEYS[index]
            ) {
                return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_SCHEMA,
                    "Camera IMU extrinsics metadata key order is wrong")
            }
        }
        val values = HashMap<String, String>()
        for (index in 1 until lines.size - 1) {
            val line = lines[index]
            val separator = line.indexOf('=')
            values[line.substring(0, separator)] =
                line.substring(separator + 1)
        }
        for (key in EXT_TRUE_KEYS) {
            if (values[key] != "true") {
                return rejected(ArKernelCameraImuExtrinsicsRejection.UNSAFE_INVARIANT,
                    "Camera IMU extrinsics invariant $key must be true")
            }
        }
        for (key in EXT_FALSE_KEYS) {
            if (values[key] != "false") {
                return rejected(ArKernelCameraImuExtrinsicsRejection.UNSAFE_INVARIANT,
                    "Camera IMU extrinsics invariant $key must be false")
            }
        }
        if (values["quality_grade"] != EXT_QUALITY) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_QUALITY,
                "Camera IMU extrinsics grade must stay $EXT_QUALITY")
        }
        if (values["version"] != EXT_VERSION) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                "Camera IMU extrinsics version is unsupported")
        }
        val runId = values["run_id"] ?: return rejected(
            ArKernelCameraImuExtrinsicsRejection.INVALID_SCHEMA,
            "missing run_id")
        if (!EXT_TOKEN.matches(runId)) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_TOKEN,
                "Camera IMU extrinsics run id is not a bounded token")
        }
        val deviceToken = values["device_token"] ?: return rejected(
            ArKernelCameraImuExtrinsicsRejection.INVALID_SCHEMA,
            "missing device_token")
        if (!EXT_TOKEN.matches(deviceToken)) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_TOKEN,
                "Camera IMU extrinsics device token is not a bounded token")
        }
        val components = IntArray(9)
        val names = arrayOf(
            "r_xx", "r_xy", "r_xz",
            "r_yx", "r_yy", "r_yz",
            "r_zx", "r_zy", "r_zz")
        for (index in 0..8) {
            val raw = values[names[index]] ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_SCHEMA,
                "missing ${names[index]}")
            if (!EXT_SIGNED.matches(raw)) {
                return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics ${names[index]} is not an integer")
            }
            val parsed = raw.toIntOrNull() ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                "Camera IMU extrinsics ${names[index]} is out of range")
            if (parsed < -1_000 || parsed > 1_000) {
                return rejected(ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics ${names[index]} is out of permille range")
            }
            components[index] = parsed
        }
        val matrix = ArKernelCameraImuMatrix(
            rowX = ArKernelCameraImuVector(
                components[0] / 1_000.0,
                components[1] / 1_000.0,
                components[2] / 1_000.0),
            rowY = ArKernelCameraImuVector(
                components[3] / 1_000.0,
                components[4] / 1_000.0,
                components[5] / 1_000.0),
            rowZ = ArKernelCameraImuVector(
                components[6] / 1_000.0,
                components[7] / 1_000.0,
                components[8] / 1_000.0))
        if (!matrix.isProperRotation()) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.NOT_A_ROTATION,
                "Camera IMU extrinsics matrix is not a proper rotation")
        }
        val record = ArKernelCameraImuExtrinsics(
            runId = runId,
            deviceToken = deviceToken,
            cameraId = values["camera_id"] ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_SCHEMA,
                "missing camera_id"),
            cameraLevel = ArKernelCameraLevel.ofToken(
                values["camera_level"] ?: "")
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics level is unknown"),
            cameraFacing = ArKernelCameraFacing.ofToken(
                values["camera_facing"] ?: "")
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics facing is unknown"),
            sensorOrientationDeg = values["sensor_orientation_deg"]
                ?.toIntOrNull() ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                "Camera IMU extrinsics sensor orientation is not an integer"),
            profileWidth = values["profile_width"]?.toIntOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics profile width is not an integer"),
            profileHeight = values["profile_height"]?.toIntOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics profile height is not an integer"),
            sourceWidth = values["source_width"]?.toIntOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics source width is not an integer"),
            sourceHeight = values["source_height"]?.toIntOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics source height is not an integer"),
            frameCount = values["frame_count"]?.toIntOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics frame count is not an integer"),
            gyroSampleCount = values["gyro_sample_count"]?.toIntOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics gyro count is not an integer"),
            durationMs = values["duration_ms"]?.toLongOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics duration is not an integer"),
            frameIntervalMedianUs = values["frame_interval_median_us"]
                ?.toLongOrNull() ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                "Camera IMU extrinsics frame interval is not an integer"),
            gyroRateMedianHz = values["gyro_rate_median_hz"]?.toLongOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics gyro rate is not an integer"),
            motionRangeDps = values["motion_range_dps"]?.toDoubleOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics motion range is not a number"),
            matrix = matrix,
            focalPxHorizontal = values["focal_px_horizontal"]
                ?.toDoubleOrNull() ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                "Camera IMU extrinsics focal length is not a number"),
            focalPxVertical = values["focal_px_vertical"]?.toDoubleOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics focal length is not a number"),
            focalSplitPermille = values["focal_split_permille"]?.toIntOrNull()
                ?: return rejected(
                    ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                    "Camera IMU extrinsics focal split is not an integer"),
            conditioningPermille = values["conditioning_permille"]
                ?.toIntOrNull() ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                "Camera IMU extrinsics conditioning is not an integer"),
            residualPermille = values["residual_permille"]?.toIntOrNull()
                ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                "Camera IMU extrinsics residual is not an integer"),
            correlationPermille = values["correlation_permille"]?.toIntOrNull()
                ?: return rejected(
                ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
                "Camera IMU extrinsics correlation is not an integer"),
        )
        // Rotation-derived fields are recomputed, not taken on trust.
        val euler = record.matrix.eulerDegrees()
        val expected = intArrayOf(
            milliDegrees(euler[0]), milliDegrees(euler[1]),
            milliDegrees(euler[2]))
        val found = arrayOf(
            values["euler_roll_mdeg"], values["euler_pitch_mdeg"],
            values["euler_yaw_mdeg"])
        for (index in 0..2) {
            if (found[index] != expected[index].toString()) {
                return rejected(
                    ArKernelCameraImuExtrinsicsRejection.STATISTICS_MISMATCH,
                    "Camera IMU extrinsics Euler angle does not match the matrix")
            }
        }
        if (fixed(record.focalPxMean) != values["focal_px_mean"]) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.STATISTICS_MISMATCH,
                "Camera IMU extrinsics mean focal length does not match")
        }
        if (record.rotationOk.toString() != values["rotation_ok"] ||
            record.focalOk.toString() != values["focal_ok"] ||
            record.extrinsicsSufficient.toString() !=
                values["extrinsics_sufficient"]
        ) {
            return rejected(ArKernelCameraImuExtrinsicsRejection.STATISTICS_MISMATCH,
                "Camera IMU extrinsics verdicts do not match the matrix")
        }
        return ArKernelCameraImuExtrinsicsParseResult(record,
            ArKernelCameraImuExtrinsicsRejection.NONE, "", 0)
    }

    /** Bounded structural summary; never a quality or accuracy grade. */
    fun report(
        record: ArKernelCameraImuExtrinsics,
    ): ArKernelCameraImuExtrinsicsReport =
        ArKernelCameraImuExtrinsicsReport(
            frameCount = record.frameCount,
            rotationOk = record.rotationOk,
            focalPxMean = record.focalPxMean,
            extrinsicsSufficient = record.extrinsicsSufficient,
            qualityGrade = EXT_QUALITY,
        )

    private fun rejected(
        rejection: ArKernelCameraImuExtrinsicsRejection,
        detail: String,
    ): ArKernelCameraImuExtrinsicsParseResult =
        ArKernelCameraImuExtrinsicsParseResult(null, rejection, detail, 0)

    private fun fixed(value: Double): String {
        val whole = value.toLong()
        val fraction = abs(
            ((value - whole) * 1_000.0).toLong() % 1_000L)
        return "$whole.${fraction.toString().padStart(3, '0')}"
    }

    private fun milliDegrees(degrees: Double): Int =
        (degrees * 1_000.0).toLong().coerceIn(
            -360_000L, 360_000L).toInt()
}

enum class ArKernelCameraImuExtrinsicsRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_RECORD,
    LINE_TOO_LONG,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    NOT_A_ROTATION,
    STATISTICS_MISMATCH,
}

/**
 * One extrinsics run. Every verdict is recomputed from the matrix, never
 * stored, so a file that claims a better outcome than its numbers support is
 * rejected.
 */
data class ArKernelCameraImuExtrinsics(
    val runId: String,
    val deviceToken: String,
    val cameraId: String,
    val cameraLevel: ArKernelCameraLevel,
    val cameraFacing: ArKernelCameraFacing,
    val sensorOrientationDeg: Int,
    val profileWidth: Int,
    val profileHeight: Int,
    /** Width in pixels of the image grid the focal length refers to. */
    val sourceWidth: Int,
    /** Height in pixels of the image grid the focal length refers to. */
    val sourceHeight: Int,
    val frameCount: Int,
    val gyroSampleCount: Int,
    val durationMs: Long,
    val frameIntervalMedianUs: Long,
    val gyroRateMedianHz: Long,
    val motionRangeDps: Double,
    val matrix: ArKernelCameraImuMatrix,
    val focalPxHorizontal: Double,
    val focalPxVertical: Double,
    val focalSplitPermille: Int,
    val conditioningPermille: Int,
    val residualPermille: Int,
    val correlationPermille: Int,
) {

    val focalPxMean: Double
        get() = (focalPxHorizontal + focalPxVertical) / 2.0

    val rotationOk: Boolean get() = matrix.isProperRotation()

    val focalOk: Boolean
        get() = focalSplitPermille <=
            ArKernelCameraImuRotation.MAX_FOCAL_SPLIT_PERMILLE &&
            focalPxMean > 0.0

    /**
     * A precondition gate, not a quality grade: the motion excited the axes
     * well enough for a rotation to be identifiable. It says nothing about
     * whether that rotation is the true one.
     */
    val extrinsicsSufficient: Boolean
        get() = rotationOk && focalOk &&
            conditioningPermille >=
                ArKernelCameraImuRotation.MIN_CONDITIONING_PERMILLE

    /** German-facing tokens naming what a failed run still needs. */
    fun outstandingSteps(): List<String> {
        if (conditioningPermille <
            ArKernelCameraImuRotation.MIN_CONDITIONING_PERMILLE
        ) {
            return listOf("motion")
        }
        val steps = ArrayList<String>()
        if (!rotationOk) steps.add("rotation")
        if (!focalOk) steps.add("focal")
        return steps
    }
}

data class ArKernelCameraImuExtrinsicsReport(
    val frameCount: Int,
    val rotationOk: Boolean,
    val focalPxMean: Double,
    val extrinsicsSufficient: Boolean,
    val qualityGrade: String = EXT_QUALITY,
)

data class ArKernelCameraImuExtrinsicsParseResult(
    val record: ArKernelCameraImuExtrinsics?,
    val rejection: ArKernelCameraImuExtrinsicsRejection,
    val detail: String,
    val lineNumber: Int,
) {
    val accepted: Boolean
        get() = record != null &&
            rejection == ArKernelCameraImuExtrinsicsRejection.NONE
}

private const val EXT_HEADER = "ARKCAMEXT1"
private const val EXT_VERSION = "1"
private const val EXT_QUALITY = "unverified"
private const val EXT_END = "END"
private const val EXT_MAX_INPUT_CHARACTERS = 16_384
private const val EXT_MAX_LINE_CHARACTERS = 256

private val EXT_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val EXT_SIGNED = Regex("0|-?[1-9][0-9]*")

private val EXT_TRUE_KEYS = listOf(
    "automatic_upload_disabled",
    "manual_share_only",
    "no_frames_retained",
    "no_quality_claim",
)

private val EXT_FALSE_KEYS = listOf(
    "intrinsics_fitted",
    "translation_fitted",
)

private val EXT_KEYS = listOf(
    "version",
    "run_id",
    "device_token",
    "camera_id",
    "camera_level",
    "camera_facing",
    "sensor_orientation_deg",
    "profile_width",
    "profile_height",
    "source_width",
    "source_height",
    "frame_count",
    "gyro_sample_count",
    "duration_ms",
    "frame_interval_median_us",
    "gyro_rate_median_hz",
    "motion_range_dps",
    "r_xx", "r_xy", "r_xz",
    "r_yx", "r_yy", "r_yz",
    "r_zx", "r_zy", "r_zz",
    "euler_roll_mdeg",
    "euler_pitch_mdeg",
    "euler_yaw_mdeg",
    "focal_px_horizontal",
    "focal_px_vertical",
    "focal_px_mean",
    "focal_split_permille",
    "conditioning_permille",
    "residual_permille",
    "correlation_permille",
    "automatic_upload_disabled",
    "manual_share_only",
    "no_frames_retained",
    "intrinsics_fitted",
    "translation_fitted",
    "no_quality_claim",
    "quality_grade",
    "rotation_ok",
    "focal_ok",
    "extrinsics_sufficient",
)
