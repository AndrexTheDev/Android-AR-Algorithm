package ai.arena.arkernel

private const val ADAPT_HEADER = "ARKADAPT1"
private const val ADAPT_VERSION = "1"
private const val ADAPT_QUALITY = "unverified"
private const val ADAPT_END = "END"
private const val ADAPT_MAX_INPUT_CHARACTERS = 8_192
private const val ADAPT_MAX_LINE_CHARACTERS = 256
private const val ADAPT_MAX_STEPS = 16
private const val ADAPT_MIN_STEPS = 1

private val ADAPT_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val ADAPT_UNSIGNED = Regex("0|[1-9][0-9]*")
private val ADAPT_KEYS = listOf(
    "version",
    "session_id",
    "device_token",
    "device_class",
    "intrinsics_state",
    "gyro_available",
    "rotation_vector_available",
    "thermal_monitoring_available",
    "images_disabled",
    "automatic_profile_selection",
    "metric_calibration_requires_user",
    "physical_target_required",
    "automatic_upload_disabled",
    "step_count",
    "quality_grade",
)
private val ADAPT_INVARIANT_KEYS = listOf(
    "images_disabled",
    "automatic_profile_selection",
    "metric_calibration_requires_user",
    "physical_target_required",
    "automatic_upload_disabled",
)
private val ADAPT_BOOLEAN_KEYS = listOf(
    "gyro_available",
    "rotation_vector_available",
    "thermal_monitoring_available",
) + ADAPT_INVARIANT_KEYS

enum class ArKernelAdaptiveDeviceClass {
    FULL,
    LIMITED,
    LEGACY,
    UNKNOWN;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelAdaptiveDeviceClass? =
            ArKernelAdaptiveDeviceClass.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

enum class ArKernelAdaptiveIntrinsicsState {
    AVAILABLE,
    UNTRUSTED,
    UNAVAILABLE;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelAdaptiveIntrinsicsState? =
            ArKernelAdaptiveIntrinsicsState.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

enum class ArKernelAdaptiveOperator {
    DEVICE_CATALOG,
    CAMERA_INTRINSICS,
    IMU_STILLNESS,
    ROTATION_VECTOR,
    THERMAL_SNAPSHOT,
    SCALE_BAR,
    SURVEY_TARGET,
    CONTROL_LENGTH,
    HELD_OUT_REVIEW;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelAdaptiveOperator? =
            ArKernelAdaptiveOperator.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

enum class ArKernelAdaptiveStepStatus {
    PENDING,
    HOST_CONFIRMED,
    HOST_MEASURED,
    SKIPPED,
    REJECTED;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelAdaptiveStepStatus? =
            ArKernelAdaptiveStepStatus.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

/** Host-read, bounded, non-personal device facts. No identifiers leave the app. */
data class ArKernelAdaptiveDeviceFacts(
    val cameraHardwareLevel: Int,
    val cameraCount: Int,
    val gyroAvailable: Boolean,
    val rotationVectorAvailable: Boolean,
    val thermalMonitoringAvailable: Boolean,
    val intrinsicsTrusted: Boolean,
    val sensorCount: Int,
    val lowRam: Boolean,
    val apiLevel: Int,
) {
    init {
        require(cameraHardwareLevel in -1..3) {
            "Invalid camera hardware level"
        }
        require(cameraCount in 0..64 && sensorCount in 0..64) {
            "Invalid device fact counts"
        }
        require(apiLevel in 0..100) { "Invalid API level" }
    }
}

/** Bounded, deterministic result of device adaptation. */
data class ArKernelAdaptiveSelection(
    val deviceClass: ArKernelAdaptiveDeviceClass,
    val intrinsicsState: ArKernelAdaptiveIntrinsicsState,
    val steps: List<ArKernelAdaptiveStep>,
    val reasonToken: String,
)

data class ArKernelAdaptiveStep(
    val index: Int,
    val operator: ArKernelAdaptiveOperator,
    val targetToken: String,
    val status: ArKernelAdaptiveStepStatus =
        ArKernelAdaptiveStepStatus.PENDING,
) {
    init {
        require(index >= 0) { "Invalid adaptive step index" }
        require(ADAPT_TOKEN.matches(targetToken)) {
            "Invalid adaptive step target"
        }
    }
}

/**
 * Bounded host-owned device-adaptive onboarding profile.
 *
 * Device recognition and profile selection are automatic; metric calibration
 * is not. All five invariants are mandatory: no images, automatic profile
 * selection only, metric calibration requires the user, a physical target is
 * required and automatic upload stays disabled. The grade is permanently
 * `unverified`. Step statuses are host-reported confirmations, never
 * measurements or quality evidence.
 */
data class ArKernelAdaptiveBundle(
    val sessionId: String,
    val deviceToken: String,
    val deviceClass: ArKernelAdaptiveDeviceClass,
    val intrinsicsState: ArKernelAdaptiveIntrinsicsState,
    val gyroAvailable: Boolean,
    val rotationVectorAvailable: Boolean,
    val thermalMonitoringAvailable: Boolean,
    val steps: List<ArKernelAdaptiveStep>,
    val imagesDisabled: Boolean = true,
    val automaticProfileSelection: Boolean = true,
    val metricCalibrationRequiresUser: Boolean = true,
    val physicalTargetRequired: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val qualityGrade: String = ADAPT_QUALITY,
) {
    init {
        require(ADAPT_TOKEN.matches(sessionId) &&
            ADAPT_TOKEN.matches(deviceToken)) {
            "Invalid adaptive session or device token"
        }
        require(imagesDisabled && automaticProfileSelection &&
            metricCalibrationRequiresUser && physicalTargetRequired &&
            automaticUploadDisabled) {
            "Device-adaptive profile must remain fail-closed"
        }
        require(qualityGrade == ADAPT_QUALITY) {
            "Device-adaptive profile cannot assert assessed quality"
        }
        require(steps.size in ADAPT_MIN_STEPS..ADAPT_MAX_STEPS) {
            "Invalid adaptive step count"
        }
        require(steps.withIndex().all {
            it.value.index == it.index
        }) { "Adaptive steps are not contiguous" }
        require(steps.map { it.targetToken }.toSet().size ==
            steps.size) {
            "Duplicate adaptive step target"
        }
    }
}

enum class ArKernelAdaptiveRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_PROFILE,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_DEVICE_CLASS,
    INVALID_INTRINSICS_STATE,
    INVALID_OPERATOR,
    INVALID_STATUS,
    INVALID_COUNT,
    INVALID_ORDER,
    DUPLICATE_TARGET,
    INVALID_STEP_FIELDS,
}

data class ArKernelAdaptiveParseResult(
    val bundle: ArKernelAdaptiveBundle?,
    val rejection: ArKernelAdaptiveRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelAdaptiveRejection.NONE &&
            bundle != null
}

/** Structural facts only; `profileComplete` is not a quality grade. */
data class ArKernelAdaptiveReport(
    val stepCount: Int,
    val distinctTargets: Int,
    val pendingCount: Int,
    val hostConfirmedCount: Int,
    val hostMeasuredCount: Int,
    val skippedCount: Int,
    val rejectedCount: Int,
    val scaleBarCount: Int,
    val surveyTargetCount: Int,
    val heldOutReviewCount: Int,
    val allStepsAddressed: Boolean,
    val profileComplete: Boolean,
    val qualityGrade: String = ADAPT_QUALITY,
)

/**
 * Canonical `ARKADAPT1` text adapter plus a deterministic device-profile
 * selector.
 *
 * `selectProfile()` adapts the systematic step list to host-read device facts:
 * camera hardware level, sensor availability, thermal monitoring, intrinsics
 * trust, RAM class and API level. Unknown or weak devices receive the most
 * conservative profile. Selection is automatic, deterministic and repeatable
 * on every Android device; it is the *procedure* that is portable, not a
 * calibration value.
 *
 * The adapter never reads a sensor, never infers intrinsics, extrinsics or
 * metric scale, never performs automatic calibration, never captures images,
 * pixels, poses or trajectories, never measures a physical target, writes no
 * file, uploads nothing, performs no I/O and grants no quality grade. A
 * passing `profileComplete` means only that the host reported all steps as
 * addressed; real measurements, independent targets, per-device calibration
 * and centimetre validation remain host work. The grade stays `unverified`.
 */
object ArKernelDeviceAdaptiveProfileAdapter {
    fun selectProfile(facts: ArKernelAdaptiveDeviceFacts):
        ArKernelAdaptiveSelection {
        val completeSensors =
            facts.gyroAvailable && facts.rotationVectorAvailable
        val deviceClass = when (facts.cameraHardwareLevel) {
            1, 3 -> if (completeSensors) {
                ArKernelAdaptiveDeviceClass.FULL
            } else {
                ArKernelAdaptiveDeviceClass.LIMITED
            }
            0 -> ArKernelAdaptiveDeviceClass.LIMITED
            2 -> ArKernelAdaptiveDeviceClass.LEGACY
            else -> ArKernelAdaptiveDeviceClass.UNKNOWN
        }
        val intrinsicsState = when {
            facts.cameraCount <= 0 ->
                ArKernelAdaptiveIntrinsicsState.UNAVAILABLE
            facts.intrinsicsTrusted ->
                ArKernelAdaptiveIntrinsicsState.AVAILABLE
            else -> ArKernelAdaptiveIntrinsicsState.UNTRUSTED
        }
        val scaleTargets = when (deviceClass) {
            ArKernelAdaptiveDeviceClass.FULL -> listOf(
                "scale.0p25.m", "scale.0p5.m", "scale.1p0.m",
                "scale.2p0.m", "scale.4p0.m")
            ArKernelAdaptiveDeviceClass.LIMITED -> listOf(
                "scale.0p25.m", "scale.0p5.m", "scale.1p0.m",
                "scale.2p0.m")
            ArKernelAdaptiveDeviceClass.LEGACY -> listOf(
                "scale.0p25.m", "scale.0p5.m", "scale.1p0.m")
            ArKernelAdaptiveDeviceClass.UNKNOWN -> listOf(
                "scale.0p25.m", "scale.0p5.m", "scale.1p0.m")
        }
        val operators =
            ArrayList<Pair<ArKernelAdaptiveOperator, String>>(16)
        operators += ArKernelAdaptiveOperator.DEVICE_CATALOG to
            "device.catalog"
        operators += ArKernelAdaptiveOperator.CAMERA_INTRINSICS to
            "camera.intrinsics"
        if (facts.gyroAvailable) {
            operators += ArKernelAdaptiveOperator.IMU_STILLNESS to
                "imu.stillness"
        }
        if (facts.rotationVectorAvailable) {
            operators += ArKernelAdaptiveOperator.ROTATION_VECTOR to
                "rotation.vector"
        }
        if (facts.thermalMonitoringAvailable) {
            operators += ArKernelAdaptiveOperator.THERMAL_SNAPSHOT to
                "thermal.snapshot"
        }
        for (target in scaleTargets) {
            operators += ArKernelAdaptiveOperator.SCALE_BAR to target
        }
        operators += ArKernelAdaptiveOperator.SURVEY_TARGET to
            "survey.target"
        operators += ArKernelAdaptiveOperator.CONTROL_LENGTH to
            "control.length"
        operators += ArKernelAdaptiveOperator.HELD_OUT_REVIEW to
            "held.out.review"
        require(operators.size <= ADAPT_MAX_STEPS) {
            "Selected profile exceeds its bounded step count"
        }
        val steps = operators.mapIndexed { index, pair ->
            ArKernelAdaptiveStep(index, pair.first, pair.second)
        }
        val reasonToken = "c." + deviceClass.wireName() +
            ".l" + facts.cameraHardwareLevel +
            ".g" + boolDigit(facts.gyroAvailable) +
            ".r" + boolDigit(facts.rotationVectorAvailable) +
            ".t" + boolDigit(facts.thermalMonitoringAvailable) +
            ".i." + intrinsicsState.wireName() +
            ".m" + boolDigit(facts.lowRam)
        require(ADAPT_TOKEN.matches(reasonToken)) {
            "Selected profile reason token is out of bounds"
        }
        return ArKernelAdaptiveSelection(
            deviceClass, intrinsicsState, steps, reasonToken)
    }

    fun encode(bundle: ArKernelAdaptiveBundle): String {
        val lines = ArrayList<String>(ADAPT_KEYS.size + bundle.steps.size + 2)
        lines.add(ADAPT_HEADER)
        lines.add("version=$ADAPT_VERSION")
        lines.add("session_id=${bundle.sessionId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("device_class=${bundle.deviceClass.wireName()}")
        lines.add(
            "intrinsics_state=${bundle.intrinsicsState.wireName()}")
        lines.add("gyro_available=${boolText(bundle.gyroAvailable)}")
        lines.add(
            "rotation_vector_available=" +
                boolText(bundle.rotationVectorAvailable))
        lines.add(
            "thermal_monitoring_available=" +
                boolText(bundle.thermalMonitoringAvailable))
        lines.add("images_disabled=true")
        lines.add("automatic_profile_selection=true")
        lines.add("metric_calibration_requires_user=true")
        lines.add("physical_target_required=true")
        lines.add("automatic_upload_disabled=true")
        lines.add("step_count=${bundle.steps.size}")
        lines.add("quality_grade=${bundle.qualityGrade}")
        for (step in bundle.steps) {
            lines.add(
                "step=${step.index}|" +
                    "${step.operator.wireName()}|" +
                    "${step.targetToken}|" +
                    step.status.wireName())
        }
        lines.add(ADAPT_END)
        val text = lines.joinToString("\n")
        require(text.length <= ADAPT_MAX_INPUT_CHARACTERS) {
            "Adaptive profile exceeds its bounded size"
        }
        require(lines.all { it.length <= ADAPT_MAX_LINE_CHARACTERS }) {
            "Adaptive profile line exceeds its bounded length"
        }
        return text
    }

    fun summarize(bundle: ArKernelAdaptiveBundle): ArKernelAdaptiveReport {
        val steps = bundle.steps
        val pending = steps.count {
            it.status == ArKernelAdaptiveStepStatus.PENDING
        }
        val confirmed = steps.count {
            it.status == ArKernelAdaptiveStepStatus.HOST_CONFIRMED
        }
        val measured = steps.count {
            it.status == ArKernelAdaptiveStepStatus.HOST_MEASURED
        }
        val skipped = steps.count {
            it.status == ArKernelAdaptiveStepStatus.SKIPPED
        }
        val rejected = steps.count {
            it.status == ArKernelAdaptiveStepStatus.REJECTED
        }
        val scaleBars = steps.count {
            it.operator == ArKernelAdaptiveOperator.SCALE_BAR
        }
        val surveys = steps.count {
            it.operator == ArKernelAdaptiveOperator.SURVEY_TARGET
        }
        val reviews = steps.count {
            it.operator == ArKernelAdaptiveOperator.HELD_OUT_REVIEW
        }
        val allAddressed = pending == 0 && skipped == 0 && rejected == 0
        return ArKernelAdaptiveReport(
            stepCount = steps.size,
            distinctTargets = steps.map { it.targetToken }.toSet().size,
            pendingCount = pending,
            hostConfirmedCount = confirmed,
            hostMeasuredCount = measured,
            skippedCount = skipped,
            rejectedCount = rejected,
            scaleBarCount = scaleBars,
            surveyTargetCount = surveys,
            heldOutReviewCount = reviews,
            allStepsAddressed = allAddressed,
            profileComplete = allAddressed && steps.size >= 3 &&
                scaleBars >= 3 && reviews >= 1,
        )
    }

    fun parse(text: String): ArKernelAdaptiveParseResult {
        if (text.length > ADAPT_MAX_INPUT_CHARACTERS) {
            return reject(ArKernelAdaptiveRejection.INPUT_TOO_LARGE, 0,
                "Adaptive profile input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(ArKernelAdaptiveRejection.EMPTY_PROFILE, 0,
                "Adaptive profile input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") ->
                normalized.substring(0, normalized.length - 2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.substring(0, normalized.length - 1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(ArKernelAdaptiveRejection.EMPTY_PROFILE, 0,
                "Adaptive profile contains no manifest")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(ArKernelAdaptiveRejection.EMPTY_LINE, 0,
                "Adaptive profile has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != ADAPT_HEADER || header.isBlank() ||
            header.length > ADAPT_MAX_LINE_CHARACTERS) {
            return reject(ArKernelAdaptiveRejection.INVALID_HEADER, 1,
                "Adaptive profile header is invalid")
        }
        if (lines.size < ADAPT_KEYS.size + 3) {
            return reject(ArKernelAdaptiveRejection.INVALID_SCHEMA, 0,
                "Adaptive profile is too short")
        }
        val lineByKey = ADAPT_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(ADAPT_KEYS.size)
        for (index in ADAPT_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(ArKernelAdaptiveRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Adaptive metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != ADAPT_KEYS[index]) {
                return reject(ArKernelAdaptiveRejection.INVALID_SCHEMA,
                    lineNumber, "Adaptive metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != ADAPT_VERSION) {
            return reject(ArKernelAdaptiveRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported adaptive profile version")
        }
        if (values.getValue("quality_grade") != ADAPT_QUALITY) {
            return reject(ArKernelAdaptiveRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Adaptive profile cannot assert assessed quality")
        }
        for (key in ADAPT_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return reject(ArKernelAdaptiveRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Adaptive flag must be a boolean")
            }
            if (key in ADAPT_INVARIANT_KEYS && value != "true") {
                return reject(ArKernelAdaptiveRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Adaptive safety invariant must be true")
            }
        }
        if (!ADAPT_TOKEN.matches(values.getValue("session_id")) ||
            !ADAPT_TOKEN.matches(values.getValue("device_token"))) {
            return reject(ArKernelAdaptiveRejection.INVALID_TOKEN,
                lineByKey.getValue("session_id"),
                "Invalid adaptive session or device token")
        }
        val deviceClass = ArKernelAdaptiveDeviceClass.fromWire(
            values.getValue("device_class"))
            ?: return reject(
                ArKernelAdaptiveRejection.INVALID_DEVICE_CLASS,
                lineByKey.getValue("device_class"),
                "Invalid adaptive device class")
        val intrinsicsState = ArKernelAdaptiveIntrinsicsState.fromWire(
            values.getValue("intrinsics_state"))
            ?: return reject(
                ArKernelAdaptiveRejection.INVALID_INTRINSICS_STATE,
                lineByKey.getValue("intrinsics_state"),
                "Invalid adaptive intrinsics state")
        val stepCount = boundedCount(
            values.getValue("step_count"), ADAPT_MAX_STEPS)
        if (stepCount == null || stepCount < ADAPT_MIN_STEPS) {
            return reject(ArKernelAdaptiveRejection.INVALID_COUNT,
                lineByKey.getValue("step_count"),
                "Invalid adaptive step count")
        }
        val stepStart = ADAPT_KEYS.size + 1
        val endIndex = stepStart + stepCount
        if (lines.size != endIndex + 1) {
            return reject(ArKernelAdaptiveRejection.INVALID_SCHEMA, 0,
                "Adaptive line count does not match step count")
        }
        val steps = ArrayList<ArKernelAdaptiveStep>(stepCount)
        val seenTargets = HashSet<String>(stepCount)
        for (index in 0 until stepCount) {
            val lineNumber = stepStart + index + 1
            val lineText = lines[stepStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("step=")) {
                return reject(ArKernelAdaptiveRejection.INVALID_SCHEMA,
                    lineNumber, "Adaptive line is not a step")
            }
            val fields = lineText.substringAfter("step=").split('|')
            if (fields.size != 4) {
                return reject(
                    ArKernelAdaptiveRejection.INVALID_STEP_FIELDS,
                    lineNumber, "Adaptive step must have four fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(ArKernelAdaptiveRejection.INVALID_VALUE,
                    lineNumber, "Invalid adaptive step index")
            if (rowIndex != index) {
                return reject(ArKernelAdaptiveRejection.INVALID_ORDER,
                    lineNumber, "Adaptive steps are not contiguous")
            }
            val operator = ArKernelAdaptiveOperator.fromWire(fields[1])
                ?: return reject(
                    ArKernelAdaptiveRejection.INVALID_OPERATOR,
                    lineNumber, "Invalid adaptive operator")
            if (!ADAPT_TOKEN.matches(fields[2])) {
                return reject(ArKernelAdaptiveRejection.INVALID_TOKEN,
                    lineNumber, "Invalid adaptive step target")
            }
            val status = ArKernelAdaptiveStepStatus.fromWire(fields[3])
                ?: return reject(ArKernelAdaptiveRejection.INVALID_STATUS,
                    lineNumber, "Invalid adaptive step status")
            if (!seenTargets.add(fields[2])) {
                return reject(ArKernelAdaptiveRejection.DUPLICATE_TARGET,
                    lineNumber, "Adaptive step target is reused")
            }
            steps += try {
                ArKernelAdaptiveStep(
                    rowIndex, operator, fields[2], status)
            } catch (error: IllegalArgumentException) {
                return reject(ArKernelAdaptiveRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid adaptive step")
            }
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != ADAPT_END) {
            return reject(ArKernelAdaptiveRejection.INVALID_SCHEMA,
                endLineNumber, "Adaptive profile must end with END")
        }
        val bundle = try {
            ArKernelAdaptiveBundle(
                values.getValue("session_id"),
                values.getValue("device_token"),
                deviceClass,
                intrinsicsState,
                values.getValue("gyro_available") == "true",
                values.getValue("rotation_vector_available") == "true",
                values.getValue("thermal_monitoring_available") ==
                    "true",
                steps,
                true,
                true,
                true,
                true,
                true,
                ADAPT_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(ArKernelAdaptiveRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid adaptive profile")
        }
        return ArKernelAdaptiveParseResult(
            bundle, ArKernelAdaptiveRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelAdaptiveParseResult? {
        if (lineText.length > ADAPT_MAX_LINE_CHARACTERS) {
            return reject(ArKernelAdaptiveRejection.LINE_TOO_LONG,
                lineNumber, "Adaptive line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(ArKernelAdaptiveRejection.EMPTY_LINE,
                lineNumber, "Adaptive profile contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(ArKernelAdaptiveRejection.INVALID_SCHEMA,
                lineNumber,
                "Adaptive lines must not contain whitespace")
        }
        return null
    }

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun boolDigit(value: Boolean): String =
        if (value) "1" else "0"

    private fun boundedCount(value: String, maximum: Int): Int? {
        val parsed = unsignedInt(value) ?: return null
        return if (parsed <= maximum) parsed else null
    }

    private fun unsignedInt(value: String): Int? =
        if (ADAPT_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun reject(
        rejection: ArKernelAdaptiveRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelAdaptiveParseResult = ArKernelAdaptiveParseResult(
        null, rejection, lineNumber, detail)
}
