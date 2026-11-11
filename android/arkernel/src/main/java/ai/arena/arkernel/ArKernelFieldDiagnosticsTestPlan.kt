package ai.arena.arkernel

private const val PLAN_HEADER = "ARKFDPLAN1"
private const val PLAN_VERSION = "1"
private const val PLAN_QUALITY = "unverified"
private const val PLAN_END = "END"
private const val PLAN_TRUE = "true"
private const val PLAN_MAX_INPUT_CHARACTERS = 4_096
private const val PLAN_MAX_LINE_CHARACTERS = 256
private const val PLAN_MAX_STEPS = 24
private const val PLAN_MAX_DURATION_MS = 120_000L
private const val PLAN_MAX_COUNT = 262_144
private const val PLAN_MAX_REVISION = 1_000_000

private val PLAN_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val PLAN_OP = Regex("[A-Z0-9_]{1,32}")
private val PLAN_LABEL = Regex("[A-Za-z0-9._:-]{1,128}")
private val PLAN_UNSIGNED = Regex("0|[1-9][0-9]*")

private val PLAN_KEYS = listOf(
    "version",
    "plan_id",
    "revision",
    "max_events",
    "terminal_text_only",
    "host_redaction_required",
    "core_upload_disabled",
    "host_signature_verification_required",
    "independent_targets_required",
    "quality_grade",
    "step_count",
)

private val PLAN_SAFETY_KEYS = listOf(
    "terminal_text_only",
    "host_redaction_required",
    "core_upload_disabled",
    "host_signature_verification_required",
    "independent_targets_required",
)

/** Operators implemented by the installed app; unknown ops fail closed. */
val SUPPORTED_TEST_PLAN_OPS: Set<String> = setOf(
    "CAPABILITY_CATALOG",
    "SENSOR_CATALOG",
    "CAMERA_CATALOG",
    "PERMISSION_CATALOG",
    "PACKAGE_CATALOG",
    "MEMORY_SNAPSHOT",
    "DISPLAY_SNAPSHOT",
    "AUDIO_CATALOG",
    "CONNECTIVITY_CATALOG",
    "BATTERY_SNAPSHOT",
    "IMU_STILLNESS",
    "SENSOR_LIVE",
    "CAMERA_PREVIEW",
    "CAMERA_LIFECYCLE_REPEAT",
    "STORAGE_ROUNDTRIP",
    "COMPUTE_SELFTEST",
    "THERMAL_SOAK",
)

enum class ArKernelFieldDiagnosticsTestPlanRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_PLAN,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_STEP,
    TOO_MANY_STEPS,
    UNKNOWN_OP,
    UNSAFE_INVARIANT,
}

data class ArKernelFieldDiagnosticsTestPlanParseResult(
    val plan: ArKernelFieldDiagnosticsTestPlan?,
    val rejection: ArKernelFieldDiagnosticsTestPlanRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelFieldDiagnosticsTestPlanRejection.NONE && plan != null
}

/**
 * One bounded step of a host-supplied test plan.
 *
 * `durationMs` is a timeout or sampling interval, `count` is an op-specific
 * primary budget (frames, samples, bytes, repeats or catalog cap) and
 * `expectedMin` is the op-specific structural pass threshold. `label` selects
 * an implemented variant such as a sensor name; it never carries free text.
 */
data class ArKernelFieldDiagnosticsTestPlanStep(
    val op: String,
    val durationMs: Long,
    val count: Int,
    val expectedMin: Int,
    val label: String,
) {
    init {
        require(PLAN_OP.matches(op)) { "Invalid test plan operator" }
        require(op in SUPPORTED_TEST_PLAN_OPS) { "Unsupported test plan operator" }
        require(durationMs in 0..PLAN_MAX_DURATION_MS) {
            "Invalid test plan duration"
        }
        require(count in 0..PLAN_MAX_COUNT) { "Invalid test plan count" }
        require(expectedMin in 0..PLAN_MAX_COUNT) {
            "Invalid test plan expected minimum"
        }
        require(PLAN_LABEL.matches(label)) { "Invalid test plan label" }
    }
}

/**
 * Canonical, text-only plan for the single installed diagnostics app.
 *
 * A plan changes which already-implemented host tests run, with which bounded
 * parameters and in which order. It performs no I/O, collection, upload,
 * signature verification or physical measurement. It cannot introduce new
 * low-level capabilities; that would still require an update of the same app.
 * Plans stay explicitly `unverified` and require the host's fail-closed safety
 * invariants plus the app's separate collection opt-in.
 */
data class ArKernelFieldDiagnosticsTestPlan(
    val planId: String,
    val revision: Int = 1,
    val maxEvents: Int = 128,
    val steps: List<ArKernelFieldDiagnosticsTestPlanStep>,
    val qualityGrade: String = PLAN_QUALITY,
) {
    init {
        require(PLAN_ID.matches(planId)) { "Invalid test plan ID" }
        require(revision in 1..PLAN_MAX_REVISION) { "Invalid test plan revision" }
        require(maxEvents in 2..128) { "Invalid test plan event budget" }
        require(steps.size in 1..PLAN_MAX_STEPS) { "Invalid test plan step count" }
        require(qualityGrade == PLAN_QUALITY) {
            "Test plan cannot assert assessed quality"
        }
    }
}

object ArKernelFieldDiagnosticsTestPlanBundle {
    fun supportedOps(): Set<String> = SUPPORTED_TEST_PLAN_OPS

    fun encode(plan: ArKernelFieldDiagnosticsTestPlan): String {
        val lines = ArrayList<String>(PLAN_KEYS.size + plan.steps.size + 2)
        lines.add(PLAN_HEADER)
        lines.add("version=$PLAN_VERSION")
        lines.add("plan_id=${plan.planId}")
        lines.add("revision=${plan.revision}")
        lines.add("max_events=${plan.maxEvents}")
        PLAN_SAFETY_KEYS.forEach { lines.add("$it=$PLAN_TRUE") }
        lines.add("quality_grade=${plan.qualityGrade}")
        lines.add("step_count=${plan.steps.size}")
        for (step in plan.steps) {
            lines.add(
                "step=${step.op}|${step.durationMs}|${step.count}|" +
                    "${step.expectedMin}|${step.label}"
            )
        }
        lines.add(PLAN_END)
        val text = lines.joinToString("\n")
        require(text.length <= PLAN_MAX_INPUT_CHARACTERS) {
            "Test plan exceeds its bounded size"
        }
        require(lines.all { it.length <= PLAN_MAX_LINE_CHARACTERS }) {
            "Test plan line exceeds its bounded length"
        }
        return text
    }

    fun defaultPlanText(): String = encode(defaultArKernelTestPlan())

    fun parse(text: String): ArKernelFieldDiagnosticsTestPlanParseResult {
        if (text.length > PLAN_MAX_INPUT_CHARACTERS) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INPUT_TOO_LARGE, 0,
                "Test plan input exceeds its bounded size")
        }
        val normalized = if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.EMPTY_PLAN, 0,
                "Test plan input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.EMPTY_PLAN, 0,
                "Test plan input contains no plan")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != PLAN_HEADER || header.any { it.isWhitespace() } ||
            header.length > PLAN_MAX_LINE_CHARACTERS
        ) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_HEADER, 1,
                "Test plan header is invalid")
        }
        if (lines.size < PLAN_KEYS.size + 3) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA, 0,
                "Test plan is too short")
        }
        val lineByKey = PLAN_KEYS.withIndex().associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(PLAN_KEYS.size)
        for (index in PLAN_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validatePlanLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
                    lineNumber, "Test plan metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != PLAN_KEYS[index]) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
                    lineNumber, "Test plan metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != PLAN_VERSION) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                lineByKey.getValue("version"), "Unsupported test plan version")
        }
        if (values.getValue("quality_grade") != PLAN_QUALITY) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Test plan cannot assert assessed quality")
        }
        for (key in PLAN_SAFETY_KEYS) {
            if (values.getValue(key) != PLAN_TRUE) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key), "Test plan safety invariant is false")
            }
        }
        val planId = values.getValue("plan_id")
        if (!PLAN_ID.matches(planId)) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_TOKEN,
                lineByKey.getValue("plan_id"), "Invalid test plan ID")
        }
        val revision = planUnsignedInt(values.getValue("revision"))
            ?: return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                lineByKey.getValue("revision"), "Invalid test plan revision")
        if (revision !in 1..PLAN_MAX_REVISION) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                lineByKey.getValue("revision"), "Test plan revision out of bounds")
        }
        val maxEvents = planUnsignedInt(values.getValue("max_events"))
            ?: return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                lineByKey.getValue("max_events"), "Invalid test plan event budget")
        if (maxEvents !in 2..128) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                lineByKey.getValue("max_events"), "Test plan event budget out of bounds")
        }
        val stepCount = planUnsignedInt(values.getValue("step_count"))
            ?: return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                lineByKey.getValue("step_count"), "Invalid test plan step count")
        if (stepCount !in 1..PLAN_MAX_STEPS) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.TOO_MANY_STEPS,
                lineByKey.getValue("step_count"), "Test plan step count out of bounds")
        }
        val stepStartIndex = PLAN_KEYS.size + 1
        val endIndex = stepStartIndex + stepCount
        if (lines.size != endIndex + 1) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA, 0,
                "Test plan line count does not match step count")
        }
        val steps = ArrayList<ArKernelFieldDiagnosticsTestPlanStep>(stepCount)
        for (index in 0 until stepCount) {
            val lineNumber = stepStartIndex + index + 1
            val lineText = lines[stepStartIndex + index].trimEnd('\r')
            val rejection = validatePlanLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("step=")) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_STEP,
                    lineNumber, "Test plan line is not a step")
            }
            val fields = lineText.substringAfter("step=").split('|')
            if (fields.size != 5) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_STEP,
                    lineNumber, "Test plan step must have five fields")
            }
            val op = fields[0]
            if (!PLAN_OP.matches(op)) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_TOKEN,
                    lineNumber, "Invalid test plan operator token")
            }
            if (op !in SUPPORTED_TEST_PLAN_OPS) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.UNKNOWN_OP,
                    lineNumber, "Unsupported test plan operator")
            }
            val durationMs = planUnsignedLong(fields[1])
                ?: return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                    lineNumber, "Invalid test plan duration")
            if (durationMs > PLAN_MAX_DURATION_MS) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                    lineNumber, "Test plan duration out of bounds")
            }
            val count = planUnsignedInt(fields[2])
                ?: return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                    lineNumber, "Invalid test plan count")
            if (count > PLAN_MAX_COUNT) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                    lineNumber, "Test plan count out of bounds")
            }
            val expectedMin = planUnsignedInt(fields[3])
                ?: return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                    lineNumber, "Invalid test plan expected minimum")
            if (expectedMin > PLAN_MAX_COUNT) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_VALUE,
                    lineNumber, "Test plan expected minimum out of bounds")
            }
            val label = fields[4]
            if (!PLAN_LABEL.matches(label)) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_TOKEN,
                    lineNumber, "Invalid test plan label")
            }
            steps += try {
                ArKernelFieldDiagnosticsTestPlanStep(
                    op, durationMs, count, expectedMin, label)
            } catch (error: IllegalArgumentException) {
                return planReject(
                    ArKernelFieldDiagnosticsTestPlanRejection.INVALID_STEP,
                    lineNumber, error.message ?: "Invalid test plan step")
            }
        }
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validatePlanLine(endText, endIndex + 1)
        if (endRejection != null) return endRejection
        if (endText != PLAN_END) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
                endIndex + 1, "Test plan must end with END")
        }
        val plan = try {
            ArKernelFieldDiagnosticsTestPlan(
                planId, revision, maxEvents, steps, PLAN_QUALITY)
        } catch (error: IllegalArgumentException) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.UNSAFE_INVARIANT,
                0, error.message ?: "Invalid test plan")
        }
        return ArKernelFieldDiagnosticsTestPlanParseResult(
            plan,
            ArKernelFieldDiagnosticsTestPlanRejection.NONE,
            0,
            "",
        )
    }

    private fun validatePlanLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelFieldDiagnosticsTestPlanParseResult? {
        if (lineText.length > PLAN_MAX_LINE_CHARACTERS) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.LINE_TOO_LONG,
                lineNumber, "Test plan line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.EMPTY_LINE,
                lineNumber, "Test plan contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return planReject(
                ArKernelFieldDiagnosticsTestPlanRejection.INVALID_SCHEMA,
                lineNumber, "Test plan lines must not contain whitespace")
        }
        return null
    }
}

/** Built-in plan covering every operator shipped in the installed app. */
fun defaultArKernelTestPlan(): ArKernelFieldDiagnosticsTestPlan =
    ArKernelFieldDiagnosticsTestPlan(
        planId = "arkernel.ultimate.v2",
        revision = 1,
        maxEvents = 128,
        steps = listOf(
            ArKernelFieldDiagnosticsTestPlanStep(
                "CAPABILITY_CATALOG", 0L, 0, 0, "base"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "SENSOR_CATALOG", 0L, 24, 1, "sensors"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "CAMERA_CATALOG", 0L, 8, 1, "cameras"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "PERMISSION_CATALOG", 0L, 0, 0, "permissions"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "PACKAGE_CATALOG", 0L, 0, 0, "packages"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "MEMORY_SNAPSHOT", 0L, 0, 0, "memory"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "DISPLAY_SNAPSHOT", 0L, 0, 0, "display"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "AUDIO_CATALOG", 0L, 0, 0, "audio"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "CONNECTIVITY_CATALOG", 0L, 0, 0, "connectivity"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "BATTERY_SNAPSHOT", 0L, 0, 0, "battery"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "IMU_STILLNESS", 10_000L, 4_000, 100,
                "accelerometer.gyroscope"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "SENSOR_LIVE", 5_000L, 2_000, 50, "gyroscope"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "SENSOR_LIVE", 5_000L, 2_000, 50, "magnetometer"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "SENSOR_LIVE", 5_000L, 2_000, 50, "light"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "SENSOR_LIVE", 5_000L, 2_000, 50, "rotation.vector"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "CAMERA_PREVIEW", 8_000L, 30, 25, "back.preview"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "CAMERA_LIFECYCLE_REPEAT", 8_000L, 3, 3, "back.openclose"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "STORAGE_ROUNDTRIP", 0L, 65_536, 3, "storage"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "COMPUTE_SELFTEST", 0L, 3, 3, "compute"),
            ArKernelFieldDiagnosticsTestPlanStep(
                "THERMAL_SOAK", 2_000L, 10, 8, "thermal"),
        ),
    )

private fun planUnsignedInt(value: String): Int? =
    if (PLAN_UNSIGNED.matches(value)) value.toIntOrNull() else null

private fun planUnsignedLong(value: String): Long? =
    if (PLAN_UNSIGNED.matches(value)) value.toLongOrNull() else null

private fun planReject(
    rejection: ArKernelFieldDiagnosticsTestPlanRejection,
    lineNumber: Int,
    detail: String,
): ArKernelFieldDiagnosticsTestPlanParseResult =
    ArKernelFieldDiagnosticsTestPlanParseResult(
        null, rejection, lineNumber, detail)
