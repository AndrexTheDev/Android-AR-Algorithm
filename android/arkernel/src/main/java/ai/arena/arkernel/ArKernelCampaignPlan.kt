package ai.arena.arkernel

private const val CAMP_HEADER = "ARKCAMPAIGN1"
private const val CAMP_VERSION = "1"
private const val CAMP_PROTOCOL = "arkadapt1"
private const val CAMP_QUALITY = "unverified"
private const val CAMP_END = "END"
private const val CAMP_MAX_INPUT_CHARACTERS = 16_384
private const val CAMP_MAX_LINE_CHARACTERS = 256
private const val CAMP_MAX_SCENES = 16
private const val CAMP_MAX_REPEAT = 16
private const val CAMP_MIN_REPEAT = 1

private val CAMP_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val CAMP_UNSIGNED = Regex("0|[1-9][0-9]*")
private val CAMP_KEYS = listOf(
    "version",
    "campaign_id",
    "device_token",
    "protocol_id",
    "device_class",
    "scene_count",
    "question_count",
    "repeat_budget",
    "scale_bar_required",
    "independent_target_required",
    "per_device_calibration",
    "universal_calibration_disabled",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "held_out_required",
    "quality_grade",
)
private val CAMP_BOOLEAN_KEYS = listOf(
    "scale_bar_required",
    "independent_target_required",
    "per_device_calibration",
    "universal_calibration_disabled",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "held_out_required",
)
private val CAMP_INVARIANT_KEYS = CAMP_BOOLEAN_KEYS

enum class ArKernelCampaignSceneKind {
    INDOOR_SCALE,
    OUTDOOR_SCALE,
    RIGHT_ANGLE,
    STILLNESS,
    THERMAL_REPEAT,
    DAY_NIGHT,
    HELD_OUT,
    CROSS_SESSION,
    CROSS_DEVICE;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelCampaignSceneKind? =
            ArKernelCampaignSceneKind.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

enum class ArKernelCampaignSceneStatus {
    PLANNED,
    HOST_COMPLETED,
    SKIPPED,
    REJECTED;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelCampaignSceneStatus? =
            ArKernelCampaignSceneStatus.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

enum class ArKernelCampaignQuestionStatus {
    PENDING,
    HOST_CONFIRMED,
    SKIPPED,
    REJECTED;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelCampaignQuestionStatus? =
            ArKernelCampaignQuestionStatus.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

/** One canonical systematic question; `requiredRepeats` is evidence depth. */
data class ArKernelCampaignQuestion(
    val wireId: String,
    val kind: ArKernelCampaignSceneKind,
    val requiredRepeats: Int,
)

private val CAMP_QUESTIONS = listOf(
    ArKernelCampaignQuestion(
        "Q_SCALE_SHORT", ArKernelCampaignSceneKind.INDOOR_SCALE, 2),
    ArKernelCampaignQuestion(
        "Q_SCALE_LONG", ArKernelCampaignSceneKind.OUTDOOR_SCALE, 2),
    ArKernelCampaignQuestion(
        "Q_ORTHOGONALITY", ArKernelCampaignSceneKind.RIGHT_ANGLE, 1),
    ArKernelCampaignQuestion(
        "Q_ORIENTATION_STILLNESS",
        ArKernelCampaignSceneKind.STILLNESS, 1),
    ArKernelCampaignQuestion(
        "Q_THERMAL_DRIFT",
        ArKernelCampaignSceneKind.THERMAL_REPEAT, 2),
    ArKernelCampaignQuestion(
        "Q_LIGHTING_CHANGE", ArKernelCampaignSceneKind.DAY_NIGHT, 2),
    ArKernelCampaignQuestion(
        "Q_HELD_OUT_DIMENSION", ArKernelCampaignSceneKind.HELD_OUT, 1),
    ArKernelCampaignQuestion(
        "Q_REPEATABILITY",
        ArKernelCampaignSceneKind.INDOOR_SCALE, 3),
    ArKernelCampaignQuestion(
        "Q_CROSS_SESSION",
        ArKernelCampaignSceneKind.CROSS_SESSION, 1),
    ArKernelCampaignQuestion(
        "Q_CROSS_DEVICE", ArKernelCampaignSceneKind.CROSS_DEVICE, 1),
)

/** One bounded host-reported measurement scene, never an observation. */
data class ArKernelCampaignScene(
    val index: Int,
    val kind: ArKernelCampaignSceneKind,
    val targetToken: String,
    val repeats: Int,
    val status: ArKernelCampaignSceneStatus =
        ArKernelCampaignSceneStatus.PLANNED,
) {
    init {
        require(index >= 0) { "Invalid campaign scene index" }
        require(CAMP_TOKEN.matches(targetToken)) {
            "Invalid campaign scene target"
        }
        require(repeats in CAMP_MIN_REPEAT..CAMP_MAX_REPEAT) {
            "Invalid campaign scene repeats"
        }
    }
}

/** One canonical question plus its host-reported status. */
data class ArKernelCampaignQuestionRow(
    val index: Int,
    val question: ArKernelCampaignQuestion,
    val status: ArKernelCampaignQuestionStatus =
        ArKernelCampaignQuestionStatus.PENDING,
) {
    init {
        require(index >= 0) { "Invalid campaign question index" }
    }
}

/**
 * Bounded, device-independent systematic measurement campaign plan.
 *
 * The protocol is portable to every Android device: fixed scenes, fixed
 * canonical questions, fixed evidence depths and a fixed held-out gate.
 * `canonicalPlan()` only shortens the scene list for weaker/unknown devices.
 * Calibration values are never portable: `per_device_calibration`,
 * `independent_target_required` and `universal_calibration_disabled` are
 * mandatory. All eight invariants are fail-closed; the grade stays
 * `unverified`. Scene completions and question confirmations are host
 * reports, not measurements, not physical evidence and not an assessment.
 */
data class ArKernelCampaignBundle(
    val campaignId: String,
    val deviceToken: String,
    val deviceClass: ArKernelAdaptiveDeviceClass,
    val scenes: List<ArKernelCampaignScene>,
    val questions: List<ArKernelCampaignQuestionRow>,
    val repeatBudget: Int = CAMP_MAX_REPEAT,
    val scaleBarRequired: Boolean = true,
    val independentTargetRequired: Boolean = true,
    val perDeviceCalibration: Boolean = true,
    val universalCalibrationDisabled: Boolean = true,
    val imagesDisabled: Boolean = true,
    val automaticCalibrationDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val heldOutRequired: Boolean = true,
    val qualityGrade: String = CAMP_QUALITY,
) {
    init {
        require(CAMP_TOKEN.matches(campaignId) &&
            CAMP_TOKEN.matches(deviceToken)) {
            "Invalid campaign or device token"
        }
        require(repeatBudget in CAMP_MIN_REPEAT..CAMP_MAX_REPEAT) {
            "Invalid campaign repeat budget"
        }
        require(scaleBarRequired && independentTargetRequired &&
            perDeviceCalibration && universalCalibrationDisabled &&
            imagesDisabled && automaticCalibrationDisabled &&
            automaticUploadDisabled && heldOutRequired) {
            "Campaign plan must remain fail-closed"
        }
        require(qualityGrade == CAMP_QUALITY) {
            "Campaign plan cannot assert assessed quality"
        }
        require(scenes.size in 1..CAMP_MAX_SCENES) {
            "Invalid campaign scene count"
        }
        require(scenes.withIndex().all {
            it.value.index == it.index
        }) { "Campaign scenes are not contiguous" }
        require(scenes.map { it.targetToken }.toSet().size ==
            scenes.size) {
            "Duplicate campaign scene target"
        }
        require(scenes.all { it.repeats <= repeatBudget }) {
            "Campaign scene exceeds repeat budget"
        }
        require(questions.size == CAMP_QUESTIONS.size) {
            "Campaign questions must be canonical"
        }
        require(questions.withIndex().all {
            it.value.index == it.index &&
                it.value.question == CAMP_QUESTIONS[it.index]
        }) { "Campaign questions are not canonical" }
    }
}

enum class ArKernelCampaignRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_PLAN,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_PROTOCOL,
    INVALID_DEVICE_CLASS,
    INVALID_COUNT,
    INVALID_REPEAT,
    INVALID_ORDER,
    INVALID_SCENE_KIND,
    INVALID_SCENE_STATUS,
    INVALID_QUESTION,
    INVALID_QUESTION_STATUS,
    DUPLICATE_TARGET,
    INVALID_ROW_FIELDS,
}

data class ArKernelCampaignParseResult(
    val bundle: ArKernelCampaignBundle?,
    val rejection: ArKernelCampaignRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelCampaignRejection.NONE &&
            bundle != null
}

/** Structural counts only; evidence completeness is not a quality grade. */
data class ArKernelCampaignReport(
    val sceneCount: Int,
    val distinctSceneTargets: Int,
    val plannedSceneCount: Int,
    val completedSceneCount: Int,
    val skippedSceneCount: Int,
    val rejectedSceneCount: Int,
    val heldOutSceneCount: Int,
    val maxSceneRepeats: Int,
    val questionCount: Int,
    val pendingQuestionCount: Int,
    val confirmedQuestionCount: Int,
    val skippedQuestionCount: Int,
    val rejectedQuestionCount: Int,
    val evidenceCompleteQuestionCount: Int,
    val unsupportedQuestionCount: Int,
    val allQuestionsAddressed: Boolean,
    val campaignEvidenceComplete: Boolean,
    val qualityGrade: String = CAMP_QUALITY,
)

/**
 * Canonical `ARKCAMPAIGN1` text adapter plus a deterministic portable
 * protocol builder.
 *
 * The adapter reads no sensor, measures no target, captures no images,
 * pixels, poses or trajectories, infers no intrinsics, extrinsics or metric
 * scale, performs no automatic/universal calibration, verifies no signature,
 * writes no file, uploads nothing, performs no I/O and grants no quality
 * grade. `evidenceCompleteQuestionCount` means a host reported a completed
 * scene of the required kind and depth; it is not an answer, not accuracy,
 * not centimetre validation and not an upgrade. Independent targets, real
 * field collection, signature verification and physical validation remain
 * host work. The grade stays `unverified`.
 */
object ArKernelCampaignPlanAdapter {
    fun canonicalQuestions(): List<ArKernelCampaignQuestion> =
        CAMP_QUESTIONS.toList()

    /** Device-independent protocol; weaker devices get fewer scenes only. */
    fun canonicalPlan(
        campaignId: String,
        deviceToken: String,
        deviceClass: ArKernelAdaptiveDeviceClass,
    ): ArKernelCampaignBundle {
        val kinds = when (deviceClass) {
            ArKernelAdaptiveDeviceClass.FULL ->
                ArKernelCampaignSceneKind.entries.toList()
            ArKernelAdaptiveDeviceClass.LIMITED ->
                ArKernelCampaignSceneKind.entries.filter {
                    it != ArKernelCampaignSceneKind.CROSS_DEVICE
                }
            ArKernelAdaptiveDeviceClass.LEGACY ->
                ArKernelCampaignSceneKind.entries.filter {
                    it != ArKernelCampaignSceneKind.CROSS_DEVICE &&
                        it != ArKernelCampaignSceneKind.DAY_NIGHT &&
                        it != ArKernelCampaignSceneKind.THERMAL_REPEAT
                }
            ArKernelAdaptiveDeviceClass.UNKNOWN -> listOf(
                ArKernelCampaignSceneKind.INDOOR_SCALE,
                ArKernelCampaignSceneKind.OUTDOOR_SCALE,
                ArKernelCampaignSceneKind.RIGHT_ANGLE,
                ArKernelCampaignSceneKind.STILLNESS,
                ArKernelCampaignSceneKind.HELD_OUT,
            )
        }
        val scenes = kinds.mapIndexed { index, kind ->
            val repeats = when (kind) {
                ArKernelCampaignSceneKind.INDOOR_SCALE -> 4
                ArKernelCampaignSceneKind.OUTDOOR_SCALE -> 3
                ArKernelCampaignSceneKind.CROSS_SESSION,
                ArKernelCampaignSceneKind.CROSS_DEVICE -> 1
                else -> 2
            }
            ArKernelCampaignScene(
                index, kind,
                "scene." + kind.wireName() + ".r" + repeats,
                repeats,
            )
        }
        val questions = CAMP_QUESTIONS.mapIndexed { index, question ->
            ArKernelCampaignQuestionRow(index, question)
        }
        return ArKernelCampaignBundle(
            campaignId, deviceToken, deviceClass, scenes, questions)
    }

    fun encode(bundle: ArKernelCampaignBundle): String {
        val lines = ArrayList<String>(
            CAMP_KEYS.size + bundle.scenes.size +
                bundle.questions.size + 2)
        lines.add(CAMP_HEADER)
        lines.add("version=$CAMP_VERSION")
        lines.add("campaign_id=${bundle.campaignId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("protocol_id=$CAMP_PROTOCOL")
        lines.add("device_class=${bundle.deviceClass.wireName()}")
        lines.add("scene_count=${bundle.scenes.size}")
        lines.add("question_count=${bundle.questions.size}")
        lines.add("repeat_budget=${bundle.repeatBudget}")
        lines.add("scale_bar_required=true")
        lines.add("independent_target_required=true")
        lines.add("per_device_calibration=true")
        lines.add("universal_calibration_disabled=true")
        lines.add("images_disabled=true")
        lines.add("automatic_calibration_disabled=true")
        lines.add("automatic_upload_disabled=true")
        lines.add("held_out_required=true")
        lines.add("quality_grade=${bundle.qualityGrade}")
        for (scene in bundle.scenes) {
            lines.add(
                "scene=${scene.index}|${scene.kind.wireName()}|" +
                    "${scene.targetToken}|${scene.repeats}|" +
                    scene.status.wireName())
        }
        for (row in bundle.questions) {
            lines.add(
                "question=${row.index}|${row.question.wireId}|" +
                    "${row.question.kind.wireName()}|" +
                    "${row.question.requiredRepeats}|" +
                    row.status.wireName())
        }
        lines.add(CAMP_END)
        val text = lines.joinToString("\n")
        require(text.length <= CAMP_MAX_INPUT_CHARACTERS) {
            "Campaign plan exceeds its bounded size"
        }
        require(lines.all { it.length <= CAMP_MAX_LINE_CHARACTERS }) {
            "Campaign plan line exceeds its bounded length"
        }
        return text
    }

    fun summarize(bundle: ArKernelCampaignBundle):
        ArKernelCampaignReport {
        val scenes = bundle.scenes
        val questions = bundle.questions
        val sceneCountByStatus =
            { status: ArKernelCampaignSceneStatus ->
                scenes.count { it.status == status }
            }
        val questionCountByStatus =
            { status: ArKernelCampaignQuestionStatus ->
                questions.count { it.status == status }
            }
        val plannedKinds = scenes.map { it.kind }.toSet()
        val completedByKind = HashMap<ArKernelCampaignSceneKind, Int>()
        for (scene in scenes) {
            if (scene.status ==
                ArKernelCampaignSceneStatus.HOST_COMPLETED) {
                val previous = completedByKind[scene.kind] ?: 0
                completedByKind[scene.kind] =
                    maxOf(previous, scene.repeats)
            }
        }
        var evidenceComplete = 0
        var unsupported = 0
        for (row in questions) {
            if (row.question.kind !in plannedKinds) {
                unsupported += 1
            }
            val depth = completedByKind[row.question.kind] ?: 0
            if (row.status ==
                ArKernelCampaignQuestionStatus.HOST_CONFIRMED &&
                depth >= row.question.requiredRepeats) {
                evidenceComplete += 1
            }
        }
        val pending = questionCountByStatus(
            ArKernelCampaignQuestionStatus.PENDING)
        val skipped = questionCountByStatus(
            ArKernelCampaignQuestionStatus.SKIPPED)
        val rejected = questionCountByStatus(
            ArKernelCampaignQuestionStatus.REJECTED)
        val heldOut = completedByKind[
            ArKernelCampaignSceneKind.HELD_OUT] ?: 0
        val allAddressed = pending == 0 && skipped == 0 && rejected == 0
        return ArKernelCampaignReport(
            sceneCount = scenes.size,
            distinctSceneTargets = scenes.map {
                it.targetToken }.toSet().size,
            plannedSceneCount = sceneCountByStatus(
                ArKernelCampaignSceneStatus.PLANNED),
            completedSceneCount = sceneCountByStatus(
                ArKernelCampaignSceneStatus.HOST_COMPLETED),
            skippedSceneCount = sceneCountByStatus(
                ArKernelCampaignSceneStatus.SKIPPED),
            rejectedSceneCount = sceneCountByStatus(
                ArKernelCampaignSceneStatus.REJECTED),
            heldOutSceneCount = heldOut,
            maxSceneRepeats = scenes.maxOf { it.repeats },
            questionCount = questions.size,
            pendingQuestionCount = pending,
            confirmedQuestionCount = questionCountByStatus(
                ArKernelCampaignQuestionStatus.HOST_CONFIRMED),
            skippedQuestionCount = skipped,
            rejectedQuestionCount = rejected,
            evidenceCompleteQuestionCount = evidenceComplete,
            unsupportedQuestionCount = unsupported,
            allQuestionsAddressed = allAddressed,
            campaignEvidenceComplete = allAddressed &&
                unsupported == 0 &&
                evidenceComplete == questions.size && heldOut >= 1,
        )
    }

    fun parse(text: String): ArKernelCampaignParseResult {
        if (text.length > CAMP_MAX_INPUT_CHARACTERS) {
            return reject(ArKernelCampaignRejection.INPUT_TOO_LARGE, 0,
                "Campaign input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(ArKernelCampaignRejection.EMPTY_PLAN, 0,
                "Campaign input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") ->
                normalized.substring(0, normalized.length - 2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.substring(0, normalized.length - 1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(ArKernelCampaignRejection.EMPTY_PLAN, 0,
                "Campaign contains no manifest")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(ArKernelCampaignRejection.EMPTY_LINE, 0,
                "Campaign has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != CAMP_HEADER || header.isBlank() ||
            header.length > CAMP_MAX_LINE_CHARACTERS) {
            return reject(ArKernelCampaignRejection.INVALID_HEADER, 1,
                "Campaign header is invalid")
        }
        if (lines.size < CAMP_KEYS.size + 3) {
            return reject(ArKernelCampaignRejection.INVALID_SCHEMA, 0,
                "Campaign is too short")
        }
        val lineByKey = CAMP_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(CAMP_KEYS.size)
        for (index in CAMP_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(ArKernelCampaignRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Campaign metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != CAMP_KEYS[index]) {
                return reject(ArKernelCampaignRejection.INVALID_SCHEMA,
                    lineNumber, "Campaign metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != CAMP_VERSION) {
            return reject(ArKernelCampaignRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported campaign version")
        }
        if (values.getValue("protocol_id") != CAMP_PROTOCOL) {
            return reject(ArKernelCampaignRejection.INVALID_PROTOCOL,
                lineByKey.getValue("protocol_id"),
                "Unsupported portable protocol")
        }
        if (values.getValue("quality_grade") != CAMP_QUALITY) {
            return reject(ArKernelCampaignRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Campaign cannot assert assessed quality")
        }
        for (key in CAMP_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return reject(ArKernelCampaignRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Campaign flag must be a boolean")
            }
            if (key in CAMP_INVARIANT_KEYS && value != "true") {
                return reject(ArKernelCampaignRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Campaign safety invariant must be true")
            }
        }
        if (!CAMP_TOKEN.matches(values.getValue("campaign_id")) ||
            !CAMP_TOKEN.matches(values.getValue("device_token"))) {
            return reject(ArKernelCampaignRejection.INVALID_TOKEN,
                lineByKey.getValue("campaign_id"),
                "Invalid campaign or device token")
        }
        val deviceClass = ArKernelAdaptiveDeviceClass.fromWire(
            values.getValue("device_class"))
            ?: return reject(
                ArKernelCampaignRejection.INVALID_DEVICE_CLASS,
                lineByKey.getValue("device_class"),
                "Invalid campaign device class")
        val sceneCount = boundedCount(
            values.getValue("scene_count"), CAMP_MAX_SCENES)
        if (sceneCount == null || sceneCount < 1) {
            return reject(ArKernelCampaignRejection.INVALID_COUNT,
                lineByKey.getValue("scene_count"),
                "Invalid campaign scene count")
        }
        val questionCount = boundedCount(
            values.getValue("question_count"), CAMP_MAX_SCENES)
        if (questionCount == null ||
            questionCount != CAMP_QUESTIONS.size) {
            return reject(ArKernelCampaignRejection.INVALID_COUNT,
                lineByKey.getValue("question_count"),
                "Campaign question count is not canonical")
        }
        val repeatBudget = boundedCount(
            values.getValue("repeat_budget"), CAMP_MAX_REPEAT)
        if (repeatBudget == null || repeatBudget < CAMP_MIN_REPEAT) {
            return reject(ArKernelCampaignRejection.INVALID_REPEAT,
                lineByKey.getValue("repeat_budget"),
                "Invalid campaign repeat budget")
        }
        val sceneStart = CAMP_KEYS.size + 1
        val questionStart = sceneStart + sceneCount
        val endIndex = questionStart + questionCount
        if (lines.size != endIndex + 1) {
            return reject(ArKernelCampaignRejection.INVALID_SCHEMA, 0,
                "Campaign line count does not match row counts")
        }
        val scenes = ArrayList<ArKernelCampaignScene>(sceneCount)
        val seenTargets = HashSet<String>(sceneCount)
        for (index in 0 until sceneCount) {
            val lineNumber = sceneStart + index + 1
            val lineText = lines[sceneStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("scene=")) {
                return reject(ArKernelCampaignRejection.INVALID_SCHEMA,
                    lineNumber, "Campaign line is not a scene")
            }
            val fields = lineText.substringAfter("scene=").split('|')
            if (fields.size != 5) {
                return reject(
                    ArKernelCampaignRejection.INVALID_ROW_FIELDS,
                    lineNumber, "Scene must have five fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(ArKernelCampaignRejection.INVALID_VALUE,
                    lineNumber, "Invalid scene index")
            if (rowIndex != index) {
                return reject(ArKernelCampaignRejection.INVALID_ORDER,
                    lineNumber, "Campaign scenes are not contiguous")
            }
            val kind = ArKernelCampaignSceneKind.fromWire(fields[1])
                ?: return reject(
                    ArKernelCampaignRejection.INVALID_SCENE_KIND,
                    lineNumber, "Invalid campaign scene kind")
            if (!CAMP_TOKEN.matches(fields[2])) {
                return reject(ArKernelCampaignRejection.INVALID_TOKEN,
                    lineNumber, "Invalid campaign scene target")
            }
            val repeats = unsignedInt(fields[3])
                ?: return reject(ArKernelCampaignRejection.INVALID_REPEAT,
                    lineNumber, "Invalid campaign scene repeats")
            if (repeats < CAMP_MIN_REPEAT ||
                repeats > repeatBudget) {
                return reject(ArKernelCampaignRejection.INVALID_REPEAT,
                    lineNumber, "Scene repeats exceed the budget")
            }
            val status = ArKernelCampaignSceneStatus.fromWire(fields[4])
                ?: return reject(
                    ArKernelCampaignRejection.INVALID_SCENE_STATUS,
                    lineNumber, "Invalid campaign scene status")
            if (!seenTargets.add(fields[2])) {
                return reject(ArKernelCampaignRejection.DUPLICATE_TARGET,
                    lineNumber, "Campaign scene target is reused")
            }
            scenes += try {
                ArKernelCampaignScene(
                    rowIndex, kind, fields[2], repeats, status)
            } catch (error: IllegalArgumentException) {
                return reject(ArKernelCampaignRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid campaign scene")
            }
        }
        val questions =
            ArrayList<ArKernelCampaignQuestionRow>(questionCount)
        for (index in 0 until questionCount) {
            val lineNumber = questionStart + index + 1
            val lineText = lines[questionStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("question=")) {
                return reject(ArKernelCampaignRejection.INVALID_SCHEMA,
                    lineNumber, "Campaign line is not a question")
            }
            val fields = lineText.substringAfter("question=").split('|')
            if (fields.size != 5) {
                return reject(
                    ArKernelCampaignRejection.INVALID_ROW_FIELDS,
                    lineNumber, "Question must have five fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(ArKernelCampaignRejection.INVALID_VALUE,
                    lineNumber, "Invalid question index")
            if (rowIndex != index) {
                return reject(ArKernelCampaignRejection.INVALID_ORDER,
                    lineNumber,
                    "Campaign questions are not contiguous")
            }
            val canonical = CAMP_QUESTIONS[index]
            if (fields[1] != canonical.wireId ||
                fields[2] != canonical.kind.wireName() ||
                fields[3] != canonical.requiredRepeats.toString()) {
                return reject(
                    ArKernelCampaignRejection.INVALID_QUESTION,
                    lineNumber, "Campaign question is not canonical")
            }
            val status =
                ArKernelCampaignQuestionStatus.fromWire(fields[4])
                ?: return reject(
                    ArKernelCampaignRejection.INVALID_QUESTION_STATUS,
                    lineNumber, "Invalid campaign question status")
            questions += ArKernelCampaignQuestionRow(
                rowIndex, canonical, status)
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != CAMP_END) {
            return reject(ArKernelCampaignRejection.INVALID_SCHEMA,
                endLineNumber, "Campaign must end with END")
        }
        val bundle = try {
            ArKernelCampaignBundle(
                values.getValue("campaign_id"),
                values.getValue("device_token"),
                deviceClass,
                scenes,
                questions,
                repeatBudget,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                CAMP_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(ArKernelCampaignRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid campaign plan")
        }
        return ArKernelCampaignParseResult(
            bundle, ArKernelCampaignRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelCampaignParseResult? {
        if (lineText.length > CAMP_MAX_LINE_CHARACTERS) {
            return reject(ArKernelCampaignRejection.LINE_TOO_LONG,
                lineNumber, "Campaign line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(ArKernelCampaignRejection.EMPTY_LINE,
                lineNumber, "Campaign contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(ArKernelCampaignRejection.INVALID_SCHEMA,
                lineNumber,
                "Campaign lines must not contain whitespace")
        }
        return null
    }

    private fun boundedCount(value: String, maximum: Int): Int? {
        val parsed = unsignedInt(value) ?: return null
        return if (parsed <= maximum) parsed else null
    }

    private fun unsignedInt(value: String): Int? =
        if (CAMP_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun reject(
        rejection: ArKernelCampaignRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelCampaignParseResult = ArKernelCampaignParseResult(
        null, rejection, lineNumber, detail)
}
