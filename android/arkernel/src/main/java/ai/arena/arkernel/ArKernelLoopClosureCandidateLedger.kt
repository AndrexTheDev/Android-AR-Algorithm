package ai.arena.arkernel

private const val LOOP_HEADER = "ARKLOOP1"
private const val LOOP_VERSION = "1"
private const val LOOP_QUALITY = "unverified"
private const val LOOP_STATE = "proposed"
private const val LOOP_END = "END"
private const val LOOP_MAX_INPUT_CHARACTERS = 8_192
private const val LOOP_MAX_LINE_CHARACTERS = 256
private const val LOOP_MIN_CANDIDATES = 1
private const val LOOP_MAX_CANDIDATES = 48
private const val LOOP_MAX_REVISION = 1_000_000
private const val LOOP_MAX_INLIERS = 1_000_000

private val LOOP_TOKEN = Regex("[A-Za-z0-9._:-]{1,32}")
private val LOOP_UNSIGNED = Regex("0|[1-9][0-9]*")
private val LOOP_DECIMAL = Regex(
    "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

private val LOOP_KEYS = listOf(
    "version",
    "ledger_id",
    "revision",
    "candidate_count",
    "pose_graph_optimization_disabled",
    "global_bundle_adjustment_disabled",
    "physical_ground_truth_disabled",
    "automatic_map_mutation_disabled",
    "quality_grade",
)

private val LOOP_SAFETY_KEYS = listOf(
    "pose_graph_optimization_disabled",
    "global_bundle_adjustment_disabled",
    "physical_ground_truth_disabled",
    "automatic_map_mutation_disabled",
)

enum class ArKernelLoopClosureCandidateRejection {
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
    INVALID_NUMBER,
    INVALID_ORDER,
    INVALID_STATE,
    DUPLICATE_CANDIDATE_ID,
    INVALID_CANDIDATE_COUNT,
    UNSAFE_INVARIANT,
}

/** Fail-closed parse result; on rejection no partially trusted plan is returned. */
data class ArKernelLoopClosureCandidateParseResult(
    val ledger: ArKernelLoopClosureCandidateLedger?,
    val rejection: ArKernelLoopClosureCandidateRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelLoopClosureCandidateRejection.NONE && ledger != null
}

/**
 * One bounded host-proposed loop-closure candidate. Keyframe and candidate IDs
 * are opaque tokens; residual/uncertainty are finite non-negative scalars and
 * the inlier count is bounded. No pose, trajectory, descriptor, image, pixel,
 * calibration array or dense geometry is carried here. The state is always
 * `proposed`, never verified or accepted into a map.
 */
data class ArKernelLoopClosureCandidate(
    val index: Int,
    val candidateId: String,
    val keyframeA: String,
    val keyframeB: String,
    val residual: Double,
    val uncertainty: Double,
    val inlierCount: Int,
    val state: String = LOOP_STATE,
) {
    init {
        require(index >= 0) { "Invalid loop-closure candidate index" }
        require(LOOP_TOKEN.matches(candidateId)) {
            "Invalid loop-closure candidate ID"
        }
        require(LOOP_TOKEN.matches(keyframeA)) {
            "Invalid loop-closure keyframe A"
        }
        require(LOOP_TOKEN.matches(keyframeB)) {
            "Invalid loop-closure keyframe B"
        }
        require(keyframeA != keyframeB) {
            "Loop-closure keyframes must be distinct"
        }
        require(residual.isFinite() && residual >= 0.0) {
            "Invalid loop-closure residual"
        }
        require(uncertainty.isFinite() && uncertainty >= 0.0) {
            "Invalid loop-closure uncertainty"
        }
        require(inlierCount in 0..LOOP_MAX_INLIERS) {
            "Invalid loop-closure inlier count"
        }
        require(state == LOOP_STATE) {
            "Loop-closure candidates must stay proposed"
        }
    }
}

/**
 * Bounded canonical ledger for host-proposed loop-closure candidates.
 *
 * It performs no descriptor matching, RANSAC, geometric verification, pose
 * graph optimization, global bundle adjustment, map mutation, I/O, upload,
 * signature verification, device validation or quality upgrade. The safety
 * flags are requirements on the host, not implemented capabilities, and the
 * grade stays `unverified`.
 */
data class ArKernelLoopClosureCandidateLedger(
    val ledgerId: String,
    val revision: Int = 1,
    val poseGraphOptimizationDisabled: Boolean = true,
    val globalBundleAdjustmentDisabled: Boolean = true,
    val physicalGroundTruthDisabled: Boolean = true,
    val automaticMapMutationDisabled: Boolean = true,
    val candidates: List<ArKernelLoopClosureCandidate>,
    val qualityGrade: String = LOOP_QUALITY,
) {
    init {
        require(LOOP_TOKEN.matches(ledgerId)) {
            "Invalid loop-closure candidate ledger ID"
        }
        require(revision in 1..LOOP_MAX_REVISION) {
            "Invalid loop-closure candidate ledger revision"
        }
        require(poseGraphOptimizationDisabled &&
            globalBundleAdjustmentDisabled &&
            physicalGroundTruthDisabled &&
            automaticMapMutationDisabled) {
            "Loop-closure candidate ledger must remain fail-closed"
        }
        require(qualityGrade == LOOP_QUALITY) {
            "Loop-closure candidate ledger cannot assert assessed quality"
        }
        require(candidates.size in
            LOOP_MIN_CANDIDATES..LOOP_MAX_CANDIDATES) {
            "Invalid loop-closure candidate count"
        }
        require(candidates.map { it.candidateId }.toSet().size ==
            candidates.size) {
            "Loop-closure candidate ledger contains duplicate IDs"
        }
        require(candidates.withIndex().all { it.value.index == it.index }) {
            "Loop-closure candidates are not contiguous"
        }
    }
}

/** Bounded structural facts only; never a quality or verification grade. */
data class ArKernelLoopClosureCandidateReport(
    val candidateCount: Int,
    val proposedCount: Int,
    val totalInliers: Long,
    val distinctKeyframes: Int,
    val maxResidual: Double,
    val qualityGrade: String = LOOP_QUALITY,
)

/**
 * Canonical `ARKLOOP1` text adapter for a bounded host candidate ledger.
 *
 * Metadata order, input/line/candidate budgets, unique opaque IDs, contiguous
 * indices, distinct opaque keyframe pairs, finite non-negative decimal
 * residual/uncertainty, bounded inlier counts and the fixed `proposed` state
 * are enforced fail-closed. BOM/CRLF input with exactly one optional final
 * separator is accepted. The adapter never verifies a loop, optimizes a graph,
 * mutates a map, uploads anything or upgrades `unverified`.
 */
object ArKernelLoopClosureCandidateBundle {
    fun encode(ledger: ArKernelLoopClosureCandidateLedger): String {
        val lines = ArrayList<String>(
            LOOP_KEYS.size + ledger.candidates.size + 2)
        lines.add(LOOP_HEADER)
        lines.add("version=$LOOP_VERSION")
        lines.add("ledger_id=${ledger.ledgerId}")
        lines.add("revision=${ledger.revision}")
        lines.add("candidate_count=${ledger.candidates.size}")
        LOOP_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${ledger.qualityGrade}")
        for (candidate in ledger.candidates) {
            lines.add(
                "candidate=${candidate.index}|${candidate.candidateId}|" +
                    "${candidate.keyframeA}|${candidate.keyframeB}|" +
                    "${candidate.residual}|${candidate.uncertainty}|" +
                    "${candidate.inlierCount}|${candidate.state}")
        }
        lines.add(LOOP_END)
        val text = lines.joinToString("\n")
        require(text.length <= LOOP_MAX_INPUT_CHARACTERS) {
            "Loop-closure candidate ledger exceeds its bounded size"
        }
        require(lines.all { it.length <= LOOP_MAX_LINE_CHARACTERS }) {
            "Loop-closure candidate line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelLoopClosureCandidateParseResult {
        if (text.length > LOOP_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INPUT_TOO_LARGE,
                0,
                "Loop-closure candidate input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelLoopClosureCandidateRejection.EMPTY_PLAN, 0,
                "Loop-closure candidate input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelLoopClosureCandidateRejection.EMPTY_PLAN, 0,
                "Loop-closure candidate input contains no plan")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelLoopClosureCandidateRejection.EMPTY_LINE, 0,
                "Loop-closure candidate contains an extra final separator")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != LOOP_HEADER ||
            header.any { it.isWhitespace() } ||
            header.length > LOOP_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_HEADER, 1,
                "Loop-closure candidate header is invalid")
        }
        if (lines.size < LOOP_KEYS.size + 3) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA, 0,
                "Loop-closure candidate ledger is too short")
        }
        val lineByKey = LOOP_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(LOOP_KEYS.size)
        for (index in LOOP_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Loop-closure candidate metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != LOOP_KEYS[index]) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Loop-closure candidate metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != LOOP_VERSION) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported loop-closure candidate version")
        }
        if (values.getValue("quality_grade") != LOOP_QUALITY) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Loop-closure candidate cannot assert assessed quality")
        }
        for (key in LOOP_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelLoopClosureCandidateRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Loop-closure candidate safety invariant must be true")
            }
        }
        val ledgerId = values.getValue("ledger_id")
        if (!LOOP_TOKEN.matches(ledgerId)) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_TOKEN,
                lineByKey.getValue("ledger_id"),
                "Invalid loop-closure candidate ledger ID")
        }
        val revision = unsignedInt(values.getValue("revision"))
            ?: return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Invalid loop-closure candidate revision")
        if (revision !in 1..LOOP_MAX_REVISION) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Loop-closure candidate revision is out of bounds")
        }
        val candidateCount = unsignedInt(values.getValue("candidate_count"))
            ?: return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                lineByKey.getValue("candidate_count"),
                "Invalid loop-closure candidate count")
        if (candidateCount !in
            LOOP_MIN_CANDIDATES..LOOP_MAX_CANDIDATES) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_CANDIDATE_COUNT,
                lineByKey.getValue("candidate_count"),
                "Loop-closure candidate count is out of bounds")
        }
        val candidateStartIndex = LOOP_KEYS.size + 1
        val endIndex = candidateStartIndex + candidateCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA, 0,
                "Loop-closure candidate line count does not match count")
        }
        val candidates =
            ArrayList<ArKernelLoopClosureCandidate>(candidateCount)
        val seen = HashSet<String>(candidateCount)
        for (index in 0 until candidateCount) {
            val lineNumber = candidateStartIndex + index + 1
            val lineText = lines[candidateStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("candidate=")) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Loop-closure candidate line is not a candidate")
            }
            val fields = lineText.substringAfter("candidate=").split('|')
            if (fields.size != 8) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Loop-closure candidate must have eight fields")
            }
            val candidateIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                    lineNumber, "Invalid loop-closure candidate index")
            if (candidateIndex != index) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_ORDER,
                    lineNumber,
                    "Loop-closure candidates are not contiguous")
            }
            val candidateId = fields[1]
            if (!LOOP_TOKEN.matches(candidateId)) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_TOKEN,
                    lineNumber, "Invalid loop-closure candidate ID")
            }
            if (!seen.add(candidateId)) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.DUPLICATE_CANDIDATE_ID,
                    lineNumber,
                    "Loop-closure candidate ledger contains duplicate IDs")
            }
            val keyframeA = fields[2]
            if (!LOOP_TOKEN.matches(keyframeA)) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_TOKEN,
                    lineNumber, "Invalid loop-closure keyframe A")
            }
            val keyframeB = fields[3]
            if (!LOOP_TOKEN.matches(keyframeB)) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_TOKEN,
                    lineNumber, "Invalid loop-closure keyframe B")
            }
            if (keyframeA == keyframeB) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                    lineNumber,
                    "Loop-closure keyframes must be distinct")
            }
            val residual = decimal(fields[4])
                ?: return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_NUMBER,
                    lineNumber, "Invalid loop-closure residual")
            val uncertainty = decimal(fields[5])
                ?: return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_NUMBER,
                    lineNumber, "Invalid loop-closure uncertainty")
            val inlierCount = unsignedInt(fields[6])
                ?: return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                    lineNumber, "Invalid loop-closure inlier count")
            if (inlierCount !in 0..LOOP_MAX_INLIERS) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                    lineNumber,
                    "Loop-closure inlier count is out of bounds")
            }
            val state = fields[7]
            if (state != LOOP_STATE) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_STATE,
                    lineNumber,
                    "Loop-closure candidates must stay proposed")
            }
            candidates += try {
                ArKernelLoopClosureCandidate(
                    candidateIndex,
                    candidateId,
                    keyframeA,
                    keyframeB,
                    residual,
                    uncertainty,
                    inlierCount,
                    state,
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid loop-closure candidate")
            }
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != LOOP_END) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
                endLineNumber,
                "Loop-closure candidate ledger must end with END")
        }
        val ledger = try {
            ArKernelLoopClosureCandidateLedger(
                ledgerId,
                revision,
                true,
                true,
                true,
                true,
                candidates,
                LOOP_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelLoopClosureCandidateRejection.UNSAFE_INVARIANT,
                0, error.message ?: "Invalid loop-closure candidate ledger")
        }
        return ArKernelLoopClosureCandidateParseResult(
            ledger,
            ArKernelLoopClosureCandidateRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(ledger: ArKernelLoopClosureCandidateLedger):
        ArKernelLoopClosureCandidateReport =
        ArKernelLoopClosureCandidateReport(
            candidateCount = ledger.candidates.size,
            proposedCount = ledger.candidates.count {
                it.state == LOOP_STATE
            },
            totalInliers = ledger.candidates.sumOf {
                it.inlierCount.toLong()
            },
            distinctKeyframes = (ledger.candidates.map {
                it.keyframeA
            } + ledger.candidates.map { it.keyframeB }).toSet().size,
            maxResidual = ledger.candidates.maxOf { it.residual },
            qualityGrade = ledger.qualityGrade,
        )

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelLoopClosureCandidateParseResult? {
        if (lineText.length > LOOP_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelLoopClosureCandidateRejection.LINE_TOO_LONG,
                lineNumber,
                "Loop-closure candidate line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelLoopClosureCandidateRejection.EMPTY_LINE,
                lineNumber, "Loop-closure candidate contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
                lineNumber,
                "Loop-closure candidate lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (LOOP_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun decimal(value: String): Double? {
        if (!LOOP_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        if (!parsed.isFinite() || parsed < 0.0) return null
        return parsed
    }

    private fun reject(
        rejection: ArKernelLoopClosureCandidateRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelLoopClosureCandidateParseResult =
        ArKernelLoopClosureCandidateParseResult(
            null, rejection, lineNumber, detail)
}
