package ai.arena.arkernel

private const val SCENE_HEADER = "ARKSCENE1"
private const val SCENE_VERSION = "1"
private const val SCENE_QUALITY = "unverified"
private const val SCENE_END = "END"
private const val SCENE_MAX_INPUT_CHARACTERS = 8_192
private const val SCENE_MAX_LINE_CHARACTERS = 256
private const val SCENE_MIN_SCENES = 1
private const val SCENE_MAX_SCENES = 24
private const val SCENE_MIN_DURATION_MINUTES = 1
private const val SCENE_MAX_DURATION_MINUTES = 180
private const val SCENE_MAX_REVISION = 1_000_000

private val SCENE_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val SCENE_UNSIGNED = Regex("0|[1-9][0-9]*")

private val SCENE_KEYS = listOf(
    "version",
    "plan_id",
    "revision",
    "scene_count",
    "provider_binding_disabled",
    "rig_evidence_required",
    "thermal_run_evidence_required",
    "independent_targets_required",
    "quality_grade",
)

private val SCENE_SAFETY_KEYS = listOf(
    "provider_binding_disabled",
    "rig_evidence_required",
    "thermal_run_evidence_required",
    "independent_targets_required",
)

enum class ArKernelDeviceFarmSceneRejection {
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
    INVALID_ORDER,
    DUPLICATE_SCENE_ID,
    TOO_MANY_SCENES,
    UNSAFE_INVARIANT,
}

/** Fail-closed parse result; on rejection no partially trusted plan is returned. */
data class ArKernelDeviceFarmSceneParseResult(
    val plan: ArKernelDeviceFarmScenePlan?,
    val rejection: ArKernelDeviceFarmSceneRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelDeviceFarmSceneRejection.NONE && plan != null
}

/**
 * One bounded host-supplied device-farm scene row. Scene IDs, kinds and
 * capability signatures are opaque tokens; the thermal marker is `0` or `1`.
 * No images, poses, trajectories, pixels, rig telemetry or physical evidence
 * are carried here.
 */
data class ArKernelDeviceFarmScene(
    val index: Int,
    val sceneId: String,
    val sceneKind: String,
    val durationMinutes: Int,
    val capabilitySignature: String,
    val thermalRun: Boolean,
) {
    init {
        require(index >= 0) { "Invalid device-farm scene index" }
        require(SCENE_TOKEN.matches(sceneId)) {
            "Invalid device-farm scene ID"
        }
        require(SCENE_TOKEN.matches(sceneKind)) {
            "Invalid device-farm scene kind"
        }
        require(durationMinutes in
            SCENE_MIN_DURATION_MINUTES..SCENE_MAX_DURATION_MINUTES) {
            "Invalid device-farm scene duration"
        }
        require(SCENE_TOKEN.matches(capabilitySignature)) {
            "Invalid device-farm capability signature"
        }
    }
}

/**
 * Bounded canonical plan for host-supplied device-farm scene metadata.
 *
 * It performs no provider binding, Mobly rig control, real thermal run,
 * day/night/weather capture, I/O, upload, signature verification, device
 * validation or quality upgrade. The safety flags are requirements on the
 * host, not implemented capabilities, and the grade stays `unverified`.
 */
data class ArKernelDeviceFarmScenePlan(
    val planId: String,
    val revision: Int = 1,
    val providerBindingDisabled: Boolean = true,
    val rigEvidenceRequired: Boolean = true,
    val thermalRunEvidenceRequired: Boolean = true,
    val independentTargetsRequired: Boolean = true,
    val scenes: List<ArKernelDeviceFarmScene>,
    val qualityGrade: String = SCENE_QUALITY,
) {
    init {
        require(SCENE_TOKEN.matches(planId)) {
            "Invalid device-farm scene plan ID"
        }
        require(revision in 1..SCENE_MAX_REVISION) {
            "Invalid device-farm scene plan revision"
        }
        require(providerBindingDisabled && rigEvidenceRequired &&
            thermalRunEvidenceRequired && independentTargetsRequired) {
            "Device-farm scene plan must remain fail-closed"
        }
        require(qualityGrade == SCENE_QUALITY) {
            "Device-farm scene plan cannot assert assessed quality"
        }
        require(scenes.size in SCENE_MIN_SCENES..SCENE_MAX_SCENES) {
            "Invalid device-farm scene count"
        }
        require(scenes.map { it.sceneId }.toSet().size == scenes.size) {
            "Device-farm scene plan contains duplicate scene IDs"
        }
        require(scenes.withIndex().all { it.value.index == it.index }) {
            "Device-farm scene plan entries are not contiguous"
        }
    }
}

/** Bounded structural facts only; never a quality or accuracy grade. */
data class ArKernelDeviceFarmSceneReport(
    val sceneCount: Int,
    val totalMinutes: Long,
    val thermalRunCount: Int,
    val distinctKinds: Int,
    val distinctCapabilitySignatures: Int,
    val qualityGrade: String = SCENE_QUALITY,
)

/**
 * Canonical `ARKSCENE1` text adapter for a bounded host scene matrix.
 *
 * Metadata order, input/line/scene budgets, unique opaque IDs, contiguous
 * indices, bounded durations, opaque kind/capability tokens and a `0`/`1`
 * thermal marker are enforced fail-closed. BOM/CRLF input with exactly one
 * optional final separator is accepted. The adapter never binds a provider,
 * runs a scene, verifies evidence, uploads anything or upgrades `unverified`.
 */
object ArKernelDeviceFarmScenePlanBundle {
    fun encode(plan: ArKernelDeviceFarmScenePlan): String {
        val lines = ArrayList<String>(
            SCENE_KEYS.size + plan.scenes.size + 2)
        lines.add(SCENE_HEADER)
        lines.add("version=$SCENE_VERSION")
        lines.add("plan_id=${plan.planId}")
        lines.add("revision=${plan.revision}")
        lines.add("scene_count=${plan.scenes.size}")
        SCENE_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${plan.qualityGrade}")
        for (scene in plan.scenes) {
            val thermal = if (scene.thermalRun) "1" else "0"
            lines.add(
                "scene=${scene.index}|${scene.sceneId}|" +
                    "${scene.sceneKind}|${scene.durationMinutes}|" +
                    "${scene.capabilitySignature}|$thermal")
        }
        lines.add(SCENE_END)
        val text = lines.joinToString("\n")
        require(text.length <= SCENE_MAX_INPUT_CHARACTERS) {
            "Device-farm scene plan exceeds its bounded size"
        }
        require(lines.all { it.length <= SCENE_MAX_LINE_CHARACTERS }) {
            "Device-farm scene plan line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelDeviceFarmSceneParseResult {
        if (text.length > SCENE_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INPUT_TOO_LARGE, 0,
                "Device-farm scene plan input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelDeviceFarmSceneRejection.EMPTY_PLAN, 0,
                "Device-farm scene plan input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelDeviceFarmSceneRejection.EMPTY_PLAN, 0,
                "Device-farm scene plan input contains no plan")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != SCENE_HEADER || header.any { it.isWhitespace() } ||
            header.length > SCENE_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_HEADER, 1,
                "Device-farm scene plan header is invalid")
        }
        if (lines.size < SCENE_KEYS.size + 3) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA, 0,
                "Device-farm scene plan is too short")
        }
        val lineByKey = SCENE_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(SCENE_KEYS.size)
        for (index in SCENE_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Device-farm scene plan metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != SCENE_KEYS[index]) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Device-farm scene plan metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != SCENE_VERSION) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported device-farm scene plan version")
        }
        if (values.getValue("quality_grade") != SCENE_QUALITY) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Device-farm scene plan cannot assert assessed quality")
        }
        for (key in SCENE_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelDeviceFarmSceneRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Device-farm scene plan safety invariant must remain true")
            }
        }
        val planId = values.getValue("plan_id")
        if (!SCENE_TOKEN.matches(planId)) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_TOKEN,
                lineByKey.getValue("plan_id"),
                "Invalid device-farm scene plan ID")
        }
        val revision = unsignedInt(values.getValue("revision"))
            ?: return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Invalid device-farm scene plan revision")
        if (revision !in 1..SCENE_MAX_REVISION) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Device-farm scene plan revision is out of bounds")
        }
        val sceneCount = unsignedInt(values.getValue("scene_count"))
            ?: return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                lineByKey.getValue("scene_count"),
                "Invalid device-farm scene count")
        if (sceneCount !in SCENE_MIN_SCENES..SCENE_MAX_SCENES) {
            return reject(
                ArKernelDeviceFarmSceneRejection.TOO_MANY_SCENES,
                lineByKey.getValue("scene_count"),
                "Device-farm scene count is out of bounds")
        }
        val sceneStartIndex = SCENE_KEYS.size + 1
        val endIndex = sceneStartIndex + sceneCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA, 0,
                "Device-farm scene plan line count does not match scene count")
        }
        val scenes = ArrayList<ArKernelDeviceFarmScene>(sceneCount)
        val seen = HashSet<String>(sceneCount)
        for (index in 0 until sceneCount) {
            val lineNumber = sceneStartIndex + index + 1
            val lineText = lines[sceneStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("scene=")) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
                    lineNumber, "Device-farm scene plan line is not a scene")
            }
            val fields = lineText.substringAfter("scene=").split('|')
            if (fields.size != 6) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
                    lineNumber, "Device-farm scene entry must have six fields")
            }
            val sceneIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                    lineNumber, "Invalid device-farm scene index")
            if (sceneIndex != index) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_ORDER,
                    lineNumber, "Device-farm scene entries are not contiguous")
            }
            val sceneId = fields[1]
            if (!SCENE_TOKEN.matches(sceneId)) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_TOKEN,
                    lineNumber, "Invalid device-farm scene ID")
            }
            if (!seen.add(sceneId)) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.DUPLICATE_SCENE_ID,
                    lineNumber,
                    "Device-farm scene plan contains duplicate scene IDs")
            }
            val sceneKind = fields[2]
            if (!SCENE_TOKEN.matches(sceneKind)) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_TOKEN,
                    lineNumber, "Invalid device-farm scene kind")
            }
            val durationMinutes = unsignedInt(fields[3])
                ?: return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                    lineNumber, "Invalid device-farm scene duration")
            if (durationMinutes !in
                SCENE_MIN_DURATION_MINUTES..SCENE_MAX_DURATION_MINUTES) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                    lineNumber,
                    "Device-farm scene duration is out of bounds")
            }
            val capabilitySignature = fields[4]
            if (!SCENE_TOKEN.matches(capabilitySignature)) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid device-farm capability signature")
            }
            val thermalFlag = unsignedInt(fields[5])
                ?: return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                    lineNumber, "Invalid device-farm thermal marker")
            if (thermalFlag !in 0..1) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                    lineNumber,
                    "Device-farm thermal marker must be 0 or 1")
            }
            scenes += try {
                ArKernelDeviceFarmScene(
                    sceneIndex,
                    sceneId,
                    sceneKind,
                    durationMinutes,
                    capabilitySignature,
                    thermalFlag == 1,
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelDeviceFarmSceneRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid device-farm scene entry")
            }
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != SCENE_END) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
                endLineNumber, "Device-farm scene plan must end with END")
        }
        val plan = try {
            ArKernelDeviceFarmScenePlan(
                planId,
                revision,
                true,
                true,
                true,
                true,
                scenes,
                SCENE_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelDeviceFarmSceneRejection.UNSAFE_INVARIANT,
                0, error.message ?: "Invalid device-farm scene plan")
        }
        return ArKernelDeviceFarmSceneParseResult(
            plan,
            ArKernelDeviceFarmSceneRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(plan: ArKernelDeviceFarmScenePlan):
        ArKernelDeviceFarmSceneReport =
        ArKernelDeviceFarmSceneReport(
            sceneCount = plan.scenes.size,
            totalMinutes = plan.scenes.sumOf { it.durationMinutes.toLong() },
            thermalRunCount = plan.scenes.count { it.thermalRun },
            distinctKinds = plan.scenes.map { it.sceneKind }.toSet().size,
            distinctCapabilitySignatures =
                plan.scenes.map { it.capabilitySignature }.toSet().size,
            qualityGrade = plan.qualityGrade,
        )

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelDeviceFarmSceneParseResult? {
        if (lineText.length > SCENE_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelDeviceFarmSceneRejection.LINE_TOO_LONG,
                lineNumber,
                "Device-farm scene plan line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelDeviceFarmSceneRejection.EMPTY_LINE,
                lineNumber, "Device-farm scene plan contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelDeviceFarmSceneRejection.INVALID_SCHEMA,
                lineNumber,
                "Device-farm scene plan lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (SCENE_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun reject(
        rejection: ArKernelDeviceFarmSceneRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelDeviceFarmSceneParseResult =
        ArKernelDeviceFarmSceneParseResult(null, rejection, lineNumber, detail)
}
