package ai.arena.arkernel

private const val GOLDEN_HEADER = "ARKGOLDEN1"
private const val GOLDEN_VERSION = "1"
private const val GOLDEN_QUALITY = "unverified"
private const val GOLDEN_END = "END"
private const val GOLDEN_MAX_INPUT_CHARACTERS = 8_192
private const val GOLDEN_MAX_LINE_CHARACTERS = 256
private const val GOLDEN_MIN_CASES = 1
private const val GOLDEN_MAX_CASES = 16
private const val GOLDEN_MAX_FRAME_EDGE = 4_096
private const val GOLDEN_MAX_DETECTION_BUDGET = 256
private const val GOLDEN_MAX_DEPTH_PIXEL_BUDGET = 4_194_304

private val GOLDEN_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val GOLDEN_HEX_256 = Regex("[0-9a-f]{64}")
private val GOLDEN_UNSIGNED = Regex("0|[1-9][0-9]*")
private val GOLDEN_DECIMAL =
    Regex("(0|[1-9][0-9]{0,2})(\\.[0-9]{1,6})?")
private val GOLDEN_KEYS = listOf(
    "version",
    "suite_id",
    "golden_suite_sha256",
    "case_count",
    "box_tolerance_px",
    "anchor_tolerance_px",
    "confidence_tolerance",
    "depth_absolute_tolerance_m",
    "depth_relative_tolerance",
    "depth_confidence_tolerance",
    "model_loading_disabled",
    "delegate_execution_disabled",
    "evidence_cache_write_disabled",
    "automatic_upload_disabled",
    "device_farm_corpora_disabled",
    "quality_grade",
)
private val GOLDEN_TOLERANCE_KEYS = listOf(
    "box_tolerance_px",
    "anchor_tolerance_px",
    "confidence_tolerance",
    "depth_absolute_tolerance_m",
    "depth_relative_tolerance",
    "depth_confidence_tolerance",
)
private val GOLDEN_SAFETY_KEYS = listOf(
    "model_loading_disabled",
    "delegate_execution_disabled",
    "evidence_cache_write_disabled",
    "automatic_upload_disabled",
    "device_farm_corpora_disabled",
)
private val GOLDEN_ALLOWED_TASKS = setOf(
    InferenceTask.SEMANTIC_DETECTION,
    InferenceTask.METRIC_DEPTH,
)

private fun isCanonicalDecimal(value: String): Boolean {
    if (!GOLDEN_DECIMAL.matches(value)) return false
    val parsed = value.toDoubleOrNull() ?: return false
    return parsed.isFinite() && parsed >= 0.0
}

enum class ArKernelGoldenSuiteCaseRole {
    GOLDEN,
    SENTINEL,
}

enum class ArKernelGoldenSuitePlanRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_PLAN,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    UNSAFE_INVARIANT,
    INVALID_TOKEN,
    INVALID_DIGEST,
    INVALID_TOLERANCE,
    INVALID_TASK,
    INVALID_ROLE,
    INVALID_FRAME_SIZE,
    INVALID_BUDGET,
    INVALID_CASE_COUNT,
    INVALID_SENTINEL_COUNT,
    DUPLICATE_CASE_ID,
    DUPLICATE_FRAME_DIGEST,
    INVALID_ORDER,
}

/** Fail-closed parse result; on rejection no partially trusted plan is returned. */
data class ArKernelGoldenSuitePlanParseResult(
    val plan: ArKernelGoldenSuitePlan?,
    val rejection: ArKernelGoldenSuitePlanRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelGoldenSuitePlanRejection.NONE && plan != null
}

/**
 * Six canonical decimal tolerance strings. Values are bounded, finite,
 * non-negative and have no exponent or leading zero, so encode/parse stays
 * byte-exact. `options()` only builds the existing inert data object; it runs
 * no inference and admits no backend.
 */
data class ArKernelGoldenSuiteTolerances(
    val boxTolerancePixels: String = "1",
    val anchorTolerancePixels: String = "1",
    val confidenceTolerance: String = "0.01",
    val depthAbsoluteToleranceMeters: String = "0.02",
    val depthRelativeTolerance: String = "0.02",
    val depthConfidenceTolerance: String = "0.02",
) {
    init {
        require(canonicalValues().all { isCanonicalDecimal(it) }) {
            "Golden suite tolerance is not a canonical finite decimal"
        }
    }

    fun canonicalValues(): List<String> = listOf(
        boxTolerancePixels,
        anchorTolerancePixels,
        confidenceTolerance,
        depthAbsoluteToleranceMeters,
        depthRelativeTolerance,
        depthConfidenceTolerance,
    )

    /** Inert host-owned tolerance policy; no model, frame or delegate is used. */
    fun options(): InferenceSemanticConformanceOptions =
        InferenceSemanticConformanceOptions(
            boxTolerancePixels = boxTolerancePixels.toDouble(),
            anchorTolerancePixels = anchorTolerancePixels.toDouble(),
            confidenceTolerance = confidenceTolerance.toDouble(),
            depthAbsoluteToleranceMeters =
                depthAbsoluteToleranceMeters.toDouble(),
            depthRelativeTolerance = depthRelativeTolerance.toDouble(),
            depthConfidenceTolerance = depthConfidenceTolerance.toDouble(),
        )
}

/**
 * One host-declared golden case. The frame digest is opaque metadata: it is
 * never compared with model bytes, a frame buffer or any stored artifact.
 * Only semantic-detection and metric-depth tasks are representable; relative
 * depth and segmentation are rejected because the bounded decoded selector
 * compares exactly those two output families.
 */
data class ArKernelGoldenSuiteCase(
    val index: Int,
    val caseId: String,
    val task: InferenceTask,
    val frameSha256: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val detectionBudget: Int,
    val depthPixelBudget: Int,
    val role: ArKernelGoldenSuiteCaseRole,
) {
    init {
        require(index >= 0) { "Invalid golden suite case index" }
        require(GOLDEN_TOKEN.matches(caseId)) {
            "Invalid golden suite case ID"
        }
        require(task in GOLDEN_ALLOWED_TASKS) {
            "Golden suite task is outside the bounded decoded selector"
        }
        require(GOLDEN_HEX_256.matches(frameSha256)) {
            "Invalid host-declared golden frame digest"
        }
        require(frameWidth in 1..GOLDEN_MAX_FRAME_EDGE &&
            frameHeight in 1..GOLDEN_MAX_FRAME_EDGE) {
            "Invalid host-declared golden frame size"
        }
        require(detectionBudget in 0..GOLDEN_MAX_DETECTION_BUDGET &&
            depthPixelBudget in 0..GOLDEN_MAX_DEPTH_PIXEL_BUDGET) {
            "Invalid host-declared golden output budget"
        }
    }
}

/**
 * Bounded canonical plan for a host-declared CPU/delegate golden suite.
 *
 * All five safety invariants must stay disabled: model loading, delegate
 * execution, evidence-cache writes, automatic upload and device-farm corpora.
 * Exactly one case is the declared startup sentinel; the rest are golden
 * cases. The grade stays `unverified`.
 */
data class ArKernelGoldenSuitePlan(
    val suiteId: String,
    val goldenSuiteSha256: String,
    val tolerances: ArKernelGoldenSuiteTolerances =
        ArKernelGoldenSuiteTolerances(),
    val modelLoadingDisabled: Boolean = true,
    val delegateExecutionDisabled: Boolean = true,
    val evidenceCacheWriteDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val deviceFarmCorporaDisabled: Boolean = true,
    val cases: List<ArKernelGoldenSuiteCase>,
    val qualityGrade: String = GOLDEN_QUALITY,
) {
    init {
        require(GOLDEN_TOKEN.matches(suiteId)) {
            "Invalid golden suite ID"
        }
        require(GOLDEN_HEX_256.matches(goldenSuiteSha256)) {
            "Invalid host-declared golden suite digest"
        }
        require(modelLoadingDisabled && delegateExecutionDisabled &&
            evidenceCacheWriteDisabled && automaticUploadDisabled &&
            deviceFarmCorporaDisabled) {
            "Golden suite plan must remain fail-closed"
        }
        require(qualityGrade == GOLDEN_QUALITY) {
            "Golden suite plan cannot assert assessed quality"
        }
        require(cases.size in GOLDEN_MIN_CASES..GOLDEN_MAX_CASES) {
            "Invalid golden suite case count"
        }
        require(cases.map { it.caseId }.toSet().size == cases.size) {
            "Golden suite contains duplicate case IDs"
        }
        require(cases.map { it.frameSha256 }.toSet().size == cases.size) {
            "Golden suite contains duplicate frame digests"
        }
        require(cases.withIndex().all {
            it.value.index == it.index
        }) {
            "Golden suite cases are not contiguous"
        }
        require(cases.count {
            it.role == ArKernelGoldenSuiteCaseRole.SENTINEL
        } == 1) {
            "Golden suite must declare exactly one startup sentinel"
        }
    }
}

/** Bounded structural facts only; never an accuracy or quality grade. */
data class ArKernelGoldenSuitePlanReport(
    val caseCount: Int,
    val distinctCaseIds: Int,
    val distinctFrameDigests: Int,
    val semanticDetectionCount: Int,
    val metricDepthCount: Int,
    val sentinelCount: Int,
    val totalDetectionBudget: Int,
    val totalDepthPixelBudget: Long,
    val maxFramePixels: Long,
    val qualityGrade: String = GOLDEN_QUALITY,
)

/**
 * Canonical `ARKGOLDEN1` text adapter for a bounded host-owned golden suite.
 *
 * It enforces exact metadata order, strict input/line/case budgets, unique
 * case IDs and opaque frame digests, contiguous indices, exactly one declared
 * startup sentinel, exact `InferenceTask` names restricted to semantic
 * detection and metric depth, bounded frame dimensions and output budgets,
 * six canonical finite decimal tolerances, five fail-closed safety
 * invariants, BOM/CRLF tolerance with one optional final separator and a
 * fail-closed `END`. `ArKernelGoldenSuiteTolerances.options()` only builds the
 * existing inert options object; the pure summarizer reports structural
 * counts, budgets and frame maxima. The adapter loads no model, verifies no
 * manifest/bytes/signature, constructs no factory or delegate, runs no
 * inference or startup sentinel, executes no CPU fallback, writes no evidence
 * cache, uploads nothing, loads no device-farm corpus, performs no I/O and
 * upgrades no quality. Concrete separately packaged adapter artifacts,
 * factory implementations, real golden frames and device-farm corpus results
 * remain host work; the grade stays `unverified`.
 */
object ArKernelGoldenSuitePlanBundle {
    fun encode(plan: ArKernelGoldenSuitePlan): String {
        val lines = ArrayList<String>(
            GOLDEN_KEYS.size + plan.cases.size + 2)
        lines.add(GOLDEN_HEADER)
        lines.add("version=$GOLDEN_VERSION")
        lines.add("suite_id=${plan.suiteId}")
        lines.add("golden_suite_sha256=${plan.goldenSuiteSha256}")
        lines.add("case_count=${plan.cases.size}")
        GOLDEN_TOLERANCE_KEYS.zip(plan.tolerances.canonicalValues()).forEach {
            lines.add("${it.first}=${it.second}")
        }
        GOLDEN_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${plan.qualityGrade}")
        for (case in plan.cases) {
            lines.add(
                "case=${case.index}|" +
                    "${case.caseId}|" +
                    "${case.task.name}|" +
                    "${case.frameSha256}|" +
                    "${case.frameWidth}|" +
                    "${case.frameHeight}|" +
                    "${case.detectionBudget}|" +
                    "${case.depthPixelBudget}|" +
                    case.role.name.lowercase())
        }
        lines.add(GOLDEN_END)
        val text = lines.joinToString("\n")
        require(text.length <= GOLDEN_MAX_INPUT_CHARACTERS) {
            "Golden suite plan exceeds its bounded size"
        }
        require(lines.all { it.length <= GOLDEN_MAX_LINE_CHARACTERS }) {
            "Golden suite plan line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelGoldenSuitePlanParseResult {
        if (text.length > GOLDEN_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INPUT_TOO_LARGE,
                0,
                "Golden suite input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelGoldenSuitePlanRejection.EMPTY_PLAN,
                0,
                "Golden suite input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") ->
                normalized.substring(0, normalized.length - 2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.substring(0, normalized.length - 1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelGoldenSuitePlanRejection.EMPTY_PLAN,
                0,
                "Golden suite input contains no plan")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelGoldenSuitePlanRejection.EMPTY_LINE,
                0,
                "Golden suite has an extra final separator")
        }
        // Explicit separator normalization keeps line counting independent of
        // stdlib lineSequence trailing-empty-line behavior.
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != GOLDEN_HEADER ||
            header.any { it.isWhitespace() } ||
            header.length > GOLDEN_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_HEADER,
                1,
                "Golden suite header is invalid")
        }
        if (lines.size < GOLDEN_KEYS.size + 3) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA,
                0,
                "Golden suite is too short")
        }
        val lineByKey = GOLDEN_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(GOLDEN_KEYS.size)
        for (index in GOLDEN_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Golden metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != GOLDEN_KEYS[index]) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Golden metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != GOLDEN_VERSION) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported golden suite version")
        }
        if (values.getValue("quality_grade") != GOLDEN_QUALITY) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Golden suite cannot assert assessed quality")
        }
        for (key in GOLDEN_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelGoldenSuitePlanRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Golden suite safety invariant must be true")
            }
        }
        val suiteId = values.getValue("suite_id")
        if (!GOLDEN_TOKEN.matches(suiteId)) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_TOKEN,
                lineByKey.getValue("suite_id"),
                "Invalid golden suite ID")
        }
        val suiteDigest = values.getValue("golden_suite_sha256")
        if (!GOLDEN_HEX_256.matches(suiteDigest)) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_DIGEST,
                lineByKey.getValue("golden_suite_sha256"),
                "Invalid host-declared golden suite digest")
        }
        val toleranceValues = GOLDEN_TOLERANCE_KEYS.map { key ->
            val value = values.getValue(key)
            if (!isCanonicalDecimal(value)) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_TOLERANCE,
                    lineByKey.getValue(key),
                    "Golden suite tolerance is not canonical")
            }
            value
        }
        val tolerances = try {
            ArKernelGoldenSuiteTolerances(
                toleranceValues[0],
                toleranceValues[1],
                toleranceValues[2],
                toleranceValues[3],
                toleranceValues[4],
                toleranceValues[5],
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_TOLERANCE,
                0,
                error.message ?: "Invalid golden suite tolerances")
        }
        val caseCount = unsignedInt(values.getValue("case_count"))
            ?: return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                lineByKey.getValue("case_count"),
                "Invalid golden suite case count")
        if (caseCount !in GOLDEN_MIN_CASES..GOLDEN_MAX_CASES) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_CASE_COUNT,
                lineByKey.getValue("case_count"),
                "Golden suite case count is out of bounds")
        }
        val entryStartIndex = GOLDEN_KEYS.size + 1
        val endIndex = entryStartIndex + caseCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA,
                0,
                "Golden suite line count does not match case count")
        }
        val cases = ArrayList<ArKernelGoldenSuiteCase>(caseCount)
        val seenCaseIds = HashSet<String>(caseCount)
        val seenDigests = HashSet<String>(caseCount)
        var sentinelCount = 0
        for (index in 0 until caseCount) {
            val lineNumber = entryStartIndex + index + 1
            val lineText = lines[entryStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("case=")) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Golden suite line is not a case")
            }
            val fields = lineText.substringAfter("case=")
                .split('|')
            if (fields.size != 9) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Golden suite case must have nine fields")
            }
            val caseIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid golden suite case index")
            if (caseIndex != index) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_ORDER,
                    lineNumber,
                    "Golden suite cases are not contiguous")
            }
            val caseId = fields[1]
            if (!GOLDEN_TOKEN.matches(caseId)) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid golden suite case ID")
            }
            val digest = fields[3]
            if (!GOLDEN_HEX_256.matches(digest)) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_DIGEST,
                    lineNumber,
                    "Invalid host-declared golden frame digest")
            }
            val task = InferenceTask.entries.firstOrNull {
                it.name == fields[2]
            }
            if (task == null || task !in GOLDEN_ALLOWED_TASKS) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_TASK,
                    lineNumber,
                    "Golden suite task is not comparable by the bounded selector")
            }
            val width = unsignedInt(fields[4])
                ?: return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid golden suite frame width")
            val height = unsignedInt(fields[5])
                ?: return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid golden suite frame height")
            if (width !in 1..GOLDEN_MAX_FRAME_EDGE ||
                height !in 1..GOLDEN_MAX_FRAME_EDGE) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_FRAME_SIZE,
                    lineNumber,
                    "Golden suite frame size is out of bounds")
            }
            val detectionBudget = unsignedInt(fields[6])
                ?: return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid golden suite detection budget")
            val depthPixelBudget = unsignedInt(fields[7])
                ?: return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid golden suite depth pixel budget")
            if (detectionBudget > GOLDEN_MAX_DETECTION_BUDGET ||
                depthPixelBudget > GOLDEN_MAX_DEPTH_PIXEL_BUDGET) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_BUDGET,
                    lineNumber,
                    "Golden suite output budget is out of bounds")
            }
            val role = when (fields[8]) {
                "golden" -> ArKernelGoldenSuiteCaseRole.GOLDEN
                "sentinel" -> {
                    sentinelCount++
                    ArKernelGoldenSuiteCaseRole.SENTINEL
                }
                else -> return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_ROLE,
                    lineNumber,
                    "Invalid golden suite case role")
            }
            if (!seenCaseIds.add(caseId)) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.DUPLICATE_CASE_ID,
                    lineNumber,
                    "Golden suite case ID is reused")
            }
            if (!seenDigests.add(digest)) {
                return reject(
                    ArKernelGoldenSuitePlanRejection
                        .DUPLICATE_FRAME_DIGEST,
                    lineNumber,
                    "Golden suite frame digest is reused")
            }
            cases += try {
                ArKernelGoldenSuiteCase(
                    caseIndex,
                    caseId,
                    task,
                    digest,
                    width,
                    height,
                    detectionBudget,
                    depthPixelBudget,
                    role,
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid golden suite case")
            }
        }
        if (sentinelCount != 1) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_SENTINEL_COUNT,
                entryStartIndex + 1,
                "Golden suite must declare exactly one startup sentinel")
        }
        val endText = lines[endIndex].trimEnd('\r')
        val endLineNumber = endIndex + 1
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != GOLDEN_END) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA,
                endLineNumber,
                "Golden suite must end with END")
        }
        val plan = try {
            ArKernelGoldenSuitePlan(
                suiteId,
                suiteDigest,
                tolerances,
                true,
                true,
                true,
                true,
                true,
                cases,
                GOLDEN_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_VALUE,
                0,
                error.message ?: "Invalid golden suite plan")
        }
        return ArKernelGoldenSuitePlanParseResult(
            plan,
            ArKernelGoldenSuitePlanRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(plan: ArKernelGoldenSuitePlan):
        ArKernelGoldenSuitePlanReport =
        ArKernelGoldenSuitePlanReport(
            caseCount = plan.cases.size,
            distinctCaseIds = plan.cases.map { it.caseId }.toSet().size,
            distinctFrameDigests =
                plan.cases.map { it.frameSha256 }.toSet().size,
            semanticDetectionCount = plan.cases.count {
                it.task == InferenceTask.SEMANTIC_DETECTION
            },
            metricDepthCount = plan.cases.count {
                it.task == InferenceTask.METRIC_DEPTH
            },
            sentinelCount = plan.cases.count {
                it.role == ArKernelGoldenSuiteCaseRole.SENTINEL
            },
            totalDetectionBudget = plan.cases.sumOf {
                it.detectionBudget
            },
            totalDepthPixelBudget = plan.cases.sumOf {
                it.depthPixelBudget.toLong()
            },
            maxFramePixels = plan.cases.maxOf {
                it.frameWidth.toLong() * it.frameHeight.toLong()
            },
        )

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelGoldenSuitePlanParseResult? {
        if (lineText.length > GOLDEN_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelGoldenSuitePlanRejection.LINE_TOO_LONG,
                lineNumber,
                "Golden suite line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelGoldenSuitePlanRejection.EMPTY_LINE,
                lineNumber,
                "Golden suite contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelGoldenSuitePlanRejection.INVALID_SCHEMA,
                lineNumber,
                "Golden suite lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (GOLDEN_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun reject(
        rejection: ArKernelGoldenSuitePlanRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelGoldenSuitePlanParseResult =
        ArKernelGoldenSuitePlanParseResult(
            null, rejection, lineNumber, detail)
}
