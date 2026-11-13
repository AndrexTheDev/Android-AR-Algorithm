package ai.arena.arkernel

import kotlin.math.abs
import kotlin.math.sqrt

private const val GC_HEADER = "ARKLOOPGC1"
private const val GC_VERSION = "1"
private const val GC_QUALITY = "unverified"
private const val GC_END = "END"
private const val GC_MAX_INPUT_CHARACTERS = 16_384
private const val GC_MAX_LINE_CHARACTERS = 512
private const val GC_MIN_CORRESPONDENCES = 1
private const val GC_MAX_CORRESPONDENCES = 64

private val GC_TOKEN = Regex("[A-Za-z0-9._:-]{1,32}")
private val GC_UNSIGNED = Regex("0|[1-9][0-9]*")
private val GC_DECIMAL = Regex(
    """-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
private val GC_KEYS = listOf(
    "version",
    "evidence_id",
    "candidate_id",
    "keyframe_a",
    "keyframe_b",
    "rotation_w",
    "rotation_x",
    "rotation_y",
    "rotation_z",
    "translation_x",
    "translation_y",
    "translation_z",
    "prior_disagreement_m",
    "correspondence_count",
    "pose_graph_optimization_disabled",
    "global_bundle_adjustment_disabled",
    "physical_ground_truth_disabled",
    "automatic_map_mutation_disabled",
    "quality_grade",
)
private val GC_SAFETY_KEYS = listOf(
    "pose_graph_optimization_disabled",
    "global_bundle_adjustment_disabled",
    "physical_ground_truth_disabled",
    "automatic_map_mutation_disabled",
)

enum class ArKernelLoopClosureGeometricRejection {
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
    DUPLICATE_EVIDENCE_ID,
    INVALID_CORRESPONDENCE_COUNT,
    UNSAFE_INVARIANT,
    INVALID_ROTATION,
    INSUFFICIENT_EVIDENCE,
    DEGENERATE_GEOMETRY,
    PRIOR_DISAGREEMENT_TOO_LARGE,
    RESIDUAL_TOO_LARGE,
    RMSE_TOO_LARGE,
    INLIERS_TOO_FEW,
    INLIER_RATIO_TOO_LOW,
    LEDGER_CANDIDATE_NOT_FOUND,
    LEDGER_INLIER_BUDGET_MISMATCH,
    LEDGER_RESIDUAL_BOUND_MISMATCH,
}

/** Bounded, opt-in policy for the purely structural consistency gate. */
data class ArKernelLoopClosureGeometricPolicy(
    val minCorrespondences: Int = 3,
    val maxCorrespondences: Int = GC_MAX_CORRESPONDENCES,
    val inlierThresholdMeters: Double = 0.02,
    val maxResidualMeters: Double = 0.02,
    val maxRmseMeters: Double = 0.01,
    val minInlierRatio: Double = 0.8,
    val minInliers: Int = 3,
    val maxPriorDisagreementMeters: Double = 0.05,
    val minSpreadMeters: Double = 0.05,
    val quaternionTolerance: Double = 1e-6,
) {
    init {
        require(maxCorrespondences in
            GC_MIN_CORRESPONDENCES..GC_MAX_CORRESPONDENCES)
        require(minCorrespondences in 1..maxCorrespondences)
        require(minInliers in 1..maxCorrespondences)
        require(minInlierRatio in 0.0..1.0)
        require(listOf(inlierThresholdMeters, maxResidualMeters,
            maxRmseMeters, maxPriorDisagreementMeters, minSpreadMeters,
            quaternionTolerance).all { it.isFinite() })
        require(inlierThresholdMeters > 0.0 &&
            inlierThresholdMeters <= 1.0)
        require(maxResidualMeters > 0.0 && maxResidualMeters <= 1.0)
        require(maxRmseMeters > 0.0 && maxRmseMeters <= 1.0)
        require(maxPriorDisagreementMeters >= 0.0 &&
            maxPriorDisagreementMeters <= 1.0)
        require(minSpreadMeters > 0.0 && minSpreadMeters <= 1.0)
        require(quaternionTolerance > 0.0 &&
            quaternionTolerance <= 1.0)
    }
}

/** One opaque 3D–3D pair supplied by the host; no image or descriptor data. */
data class ArKernelLoopClosureGeometricCorrespondence(
    val index: Int,
    val evidenceId: String,
    val pointA: Vector3,
    val pointB: Vector3,
) {
    init {
        require(index >= 0) { "Invalid geometric correspondence index" }
        require(GC_TOKEN.matches(evidenceId)) {
            "Invalid geometric correspondence ID"
        }
        require(listOf(pointA.x, pointA.y, pointA.z,
            pointB.x, pointB.y, pointB.z).all { it.isFinite() }) {
            "Invalid geometric correspondence point"
        }
    }
}

/**
 * Bounded host-owned evidence for one proposed loop candidate. It carries no
 * descriptor, image, trajectory, pose graph or map mutation. The grade remains
 * `unverified`; all four safety invariants must stay disabled.
 */
data class ArKernelLoopClosureGeometricEvidence(
    val evidenceId: String,
    val candidateId: String,
    val keyframeA: String,
    val keyframeB: String,
    val rotation: Rotation,
    val translation: Vector3,
    val priorDisagreementMeters: Double,
    val correspondences: List<ArKernelLoopClosureGeometricCorrespondence>,
    val poseGraphOptimizationDisabled: Boolean = true,
    val globalBundleAdjustmentDisabled: Boolean = true,
    val physicalGroundTruthDisabled: Boolean = true,
    val automaticMapMutationDisabled: Boolean = true,
    val qualityGrade: String = GC_QUALITY,
) {
    init {
        require(GC_TOKEN.matches(evidenceId)) {
            "Invalid geometric evidence ID"
        }
        require(GC_TOKEN.matches(candidateId)) {
            "Invalid geometric candidate ID"
        }
        require(GC_TOKEN.matches(keyframeA)) {
            "Invalid geometric keyframe A"
        }
        require(GC_TOKEN.matches(keyframeB)) {
            "Invalid geometric keyframe B"
        }
        require(keyframeA != keyframeB) {
            "Geometric keyframes must be distinct"
        }
        require(listOf(rotation.w, rotation.x, rotation.y, rotation.z,
            translation.x, translation.y, translation.z,
            priorDisagreementMeters).all { it.isFinite() }) {
            "Invalid geometric transform or prior disagreement"
        }
        require(priorDisagreementMeters >= 0.0) {
            "Geometric prior disagreement must be non-negative"
        }
        require(poseGraphOptimizationDisabled &&
            globalBundleAdjustmentDisabled &&
            physicalGroundTruthDisabled &&
            automaticMapMutationDisabled) {
            "Geometric evidence must remain fail-closed"
        }
        require(qualityGrade == GC_QUALITY) {
            "Geometric evidence cannot assert assessed quality"
        }
        require(correspondences.size in
            GC_MIN_CORRESPONDENCES..GC_MAX_CORRESPONDENCES) {
            "Invalid geometric correspondence count"
        }
        require(correspondences.map { it.evidenceId }.toSet().size ==
            correspondences.size) {
            "Geometric correspondence IDs must be unique"
        }
        require(correspondences.withIndex().all {
            it.value.index == it.index
        }) {
            "Geometric correspondences are not contiguous"
        }
    }
}

/** Fail-closed parse result; on rejection no partially trusted data is returned. */
data class ArKernelLoopClosureGeometricParseResult(
    val evidence: ArKernelLoopClosureGeometricEvidence?,
    val rejection: ArKernelLoopClosureGeometricRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelLoopClosureGeometricRejection.NONE && evidence != null
}

/**
 * Bounded structural metrics. `accepted` means only that the supplied evidence
 * passed the configured synthetic consistency gates; it never proves a loop,
 * runs RANSAC, optimizes a graph, mutates a map or grants a quality grade.
 */
data class ArKernelLoopClosureGeometricReport(
    val correspondenceCount: Int,
    val inlierCount: Int,
    val maxResidualMeters: Double,
    val rmseMeters: Double,
    val inlierRatio: Double,
    val priorDisagreementMeters: Double,
    val spreadMeters: Double,
    val rejection: ArKernelLoopClosureGeometricRejection,
    val qualityGrade: String = GC_QUALITY,
    val poseGraphOptimizationDisabled: Boolean = true,
    val globalBundleAdjustmentDisabled: Boolean = true,
    val physicalGroundTruthDisabled: Boolean = true,
    val automaticMapMutationDisabled: Boolean = true,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelLoopClosureGeometricRejection.NONE
}

/**
 * Canonical `ARKLOOPGC1` text envelope plus a deterministic geometric
 * consistency gate for host-supplied loop-closure evidence.
 *
 * The parser enforces fixed metadata order, strict input/line/correspondence
 * budgets, unique opaque IDs, contiguous indices, exact safety invariants,
 * finite decimals and a fail-closed `END`. The evaluator rotates each point B
 * by a normalized unit quaternion, adds the translation, measures residuals
 * against point A and gates RMSE, maximum residual, inlier count/ratio, prior
 * disagreement and bounded spatial spread. BOM/CRLF and one optional final
 * separator are accepted. This is a pure transport/consistency contract: it
 * performs no I/O, descriptor matching, robust RANSAC, pose-graph
 * optimization, global bundle adjustment, map mutation, physical validation
 * or quality upgrade, and stays `unverified`. `evaluateAgainstLedger`
 * additionally cross-checks one exact `ARKLOOP1` candidate ID/keyframe pair
 * and its bounded structural priors; it never converts the report into a
 * verified loop or a pose-graph constraint.
 */
object ArKernelLoopClosureGeometricGate {
    fun encode(evidence: ArKernelLoopClosureGeometricEvidence): String {
        val lines = ArrayList<String>(
            GC_KEYS.size + evidence.correspondences.size + 2)
        lines.add(GC_HEADER)
        lines.add("version=$GC_VERSION")
        lines.add("evidence_id=${evidence.evidenceId}")
        lines.add("candidate_id=${evidence.candidateId}")
        lines.add("keyframe_a=${evidence.keyframeA}")
        lines.add("keyframe_b=${evidence.keyframeB}")
        lines.add("rotation_w=${evidence.rotation.w}")
        lines.add("rotation_x=${evidence.rotation.x}")
        lines.add("rotation_y=${evidence.rotation.y}")
        lines.add("rotation_z=${evidence.rotation.z}")
        lines.add("translation_x=${evidence.translation.x}")
        lines.add("translation_y=${evidence.translation.y}")
        lines.add("translation_z=${evidence.translation.z}")
        lines.add(
            "prior_disagreement_m=${evidence.priorDisagreementMeters}")
        lines.add("correspondence_count=${evidence.correspondences.size}")
        GC_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${evidence.qualityGrade}")
        evidence.correspondences.forEach { correspondence ->
            lines.add(
                "corr=${correspondence.index}|" +
                    "${correspondence.evidenceId}|" +
                    "${correspondence.pointA.x}|" +
                    "${correspondence.pointA.y}|" +
                    "${correspondence.pointA.z}|" +
                    "${correspondence.pointB.x}|" +
                    "${correspondence.pointB.y}|" +
                    "${correspondence.pointB.z}")
        }
        lines.add(GC_END)
        val text = lines.joinToString("\n")
        require(text.length <= GC_MAX_INPUT_CHARACTERS) {
            "Geometric evidence exceeds its bounded size"
        }
        require(lines.all { it.length <= GC_MAX_LINE_CHARACTERS }) {
            "Geometric evidence line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelLoopClosureGeometricParseResult {
        if (text.length > GC_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INPUT_TOO_LARGE, 0,
                "Geometric evidence input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelLoopClosureGeometricRejection.EMPTY_PLAN, 0,
                "Geometric evidence input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelLoopClosureGeometricRejection.EMPTY_PLAN, 0,
                "Geometric evidence contains no plan")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelLoopClosureGeometricRejection.EMPTY_LINE, 0,
                "Geometric evidence contains an extra final separator")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != GC_HEADER || header.any { it.isWhitespace() } ||
            header.length > GC_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_HEADER, 1,
                "Geometric evidence header is invalid")
        }
        if (lines.size < GC_KEYS.size + 3) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA, 0,
                "Geometric evidence is too short")
        }
        val lineByKey = GC_KEYS.withIndex().associate {
            it.value to it.index + 2
        }
        val values = HashMap<String, String>(GC_KEYS.size)
        for (index in GC_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Geometric metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != GC_KEYS[index]) {
                return reject(
                    ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Geometric metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != GC_VERSION) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported geometric evidence version")
        }
        if (values.getValue("quality_grade") != GC_QUALITY) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Geometric evidence cannot assert assessed quality")
        }
        for (key in GC_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelLoopClosureGeometricRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Geometric safety invariant must be true")
            }
        }
        val evidenceId = values.getValue("evidence_id")
        val candidateId = values.getValue("candidate_id")
        val keyframeA = values.getValue("keyframe_a")
        val keyframeB = values.getValue("keyframe_b")
        if (!GC_TOKEN.matches(evidenceId) ||
            !GC_TOKEN.matches(candidateId) ||
            !GC_TOKEN.matches(keyframeA) ||
            !GC_TOKEN.matches(keyframeB)) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_TOKEN,
                lineByKey.getValue("evidence_id"),
                "Geometric evidence token is invalid")
        }
        if (keyframeA == keyframeB) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
                lineByKey.getValue("keyframe_a"),
                "Geometric keyframes must be distinct")
        }
        val qw = decimal(values.getValue("rotation_w"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                lineByKey.getValue("rotation_w"),
                "Invalid geometric rotation W")
        val qx = decimal(values.getValue("rotation_x"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                lineByKey.getValue("rotation_x"),
                "Invalid geometric rotation X")
        val qy = decimal(values.getValue("rotation_y"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                lineByKey.getValue("rotation_y"),
                "Invalid geometric rotation Y")
        val qz = decimal(values.getValue("rotation_z"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                lineByKey.getValue("rotation_z"),
                "Invalid geometric rotation Z")
        val tx = decimal(values.getValue("translation_x"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                lineByKey.getValue("translation_x"),
                "Invalid geometric translation X")
        val ty = decimal(values.getValue("translation_y"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                lineByKey.getValue("translation_y"),
                "Invalid geometric translation Y")
        val tz = decimal(values.getValue("translation_z"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                lineByKey.getValue("translation_z"),
                "Invalid geometric translation Z")
        val prior = decimal(values.getValue("prior_disagreement_m"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                lineByKey.getValue("prior_disagreement_m"),
                "Invalid geometric prior disagreement")
        if (prior < 0.0) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
                lineByKey.getValue("prior_disagreement_m"),
                "Geometric prior disagreement must be non-negative")
        }
        val count = unsignedInt(values.getValue("correspondence_count"))
            ?: return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
                lineByKey.getValue("correspondence_count"),
                "Invalid geometric correspondence count")
        if (count !in GC_MIN_CORRESPONDENCES..GC_MAX_CORRESPONDENCES) {
            return reject(
                ArKernelLoopClosureGeometricRejection
                    .INVALID_CORRESPONDENCE_COUNT,
                lineByKey.getValue("correspondence_count"),
                "Geometric correspondence count is out of bounds")
        }
        if (lines.size != GC_KEYS.size + count + 2) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA, 0,
                "Geometric line count does not match correspondence count")
        }
        val correspondences =
            ArrayList<ArKernelLoopClosureGeometricCorrespondence>(count)
        val seen = HashSet<String>(count)
        for (index in 0 until count) {
            val lineNumber = GC_KEYS.size + index + 2
            val lineText = lines[GC_KEYS.size + 1 + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("corr=")) {
                return reject(
                    ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Geometric line is not a correspondence")
            }
            val fields = lineText.substringAfter("corr=").split('|')
            if (fields.size != 8) {
                return reject(
                    ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Geometric correspondence must have eight fields")
            }
            val correspondenceIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid geometric correspondence index")
            if (correspondenceIndex != index) {
                return reject(
                    ArKernelLoopClosureGeometricRejection.INVALID_ORDER,
                    lineNumber,
                    "Geometric correspondences are not contiguous")
            }
            val correspondenceId = fields[1]
            if (!GC_TOKEN.matches(correspondenceId)) {
                return reject(
                    ArKernelLoopClosureGeometricRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid geometric correspondence ID")
            }
            if (!seen.add(correspondenceId)) {
                return reject(
                    ArKernelLoopClosureGeometricRejection
                        .DUPLICATE_EVIDENCE_ID,
                    lineNumber,
                    "Geometric correspondence ID is reused")
            }
            val numbers = DoubleArray(6)
            for (numberIndex in 0 until 6) {
                val value = decimal(fields[numberIndex + 2])
                    ?: return reject(
                        ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
                        lineNumber,
                        "Invalid geometric correspondence coordinate")
                numbers[numberIndex] = value
            }
            correspondences += try {
                ArKernelLoopClosureGeometricCorrespondence(
                    correspondenceIndex,
                    correspondenceId,
                    Vector3(numbers[0], numbers[1], numbers[2]),
                    Vector3(numbers[3], numbers[4], numbers[5]),
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid geometric correspondence")
            }
        }
        val endIndex = GC_KEYS.size + 1 + count
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != GC_END) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
                endLineNumber,
                "Geometric evidence must end with END")
        }
        val evidence = try {
            ArKernelLoopClosureGeometricEvidence(
                evidenceId,
                candidateId,
                keyframeA,
                keyframeB,
                Rotation(qw, qx, qy, qz),
                Vector3(tx, ty, tz),
                prior,
                correspondences,
                true,
                true,
                true,
                true,
                GC_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
                0,
                error.message ?: "Invalid geometric evidence")
        }
        return ArKernelLoopClosureGeometricParseResult(
            evidence,
            ArKernelLoopClosureGeometricRejection.NONE,
            0,
            "",
        )
    }

    fun evaluate(
        text: String,
        policy: ArKernelLoopClosureGeometricPolicy =
            ArKernelLoopClosureGeometricPolicy(),
    ): ArKernelLoopClosureGeometricReport {
        val parsed = parse(text)
        if (!parsed.accepted || parsed.evidence == null) {
            return emptyReport(parsed.rejection)
        }
        return evaluateEvidence(parsed.evidence, policy)
    }

    /**
     * Cross-check one exact `ARKLOOP1` candidate without verifying the loop.
     * Candidate state, safety flags and `unverified` grade are already fixed by
     * `ArKernelLoopClosureCandidateLedger`. The candidate's inlier count is an
     * upper structural budget, and residual plus uncertainty is a conservative
     * bound on both prior disagreement and measured RMSE.
     */
    fun evaluateAgainstLedger(
        text: String,
        ledger: ArKernelLoopClosureCandidateLedger,
        policy: ArKernelLoopClosureGeometricPolicy =
            ArKernelLoopClosureGeometricPolicy(),
    ): ArKernelLoopClosureGeometricReport {
        val parsed = parse(text)
        if (!parsed.accepted || parsed.evidence == null) {
            return emptyReport(parsed.rejection)
        }
        val evidence = parsed.evidence
        val candidate = ledger.candidates.firstOrNull {
            it.candidateId == evidence.candidateId &&
                it.keyframeA == evidence.keyframeA &&
                it.keyframeB == evidence.keyframeB
        } ?: return emptyReport(
            ArKernelLoopClosureGeometricRejection
                .LEDGER_CANDIDATE_NOT_FOUND)
        val result = evaluateEvidence(evidence, policy)
        if (!result.accepted) return result
        if (result.inlierCount > candidate.inlierCount) {
            return result.copy(
                rejection = ArKernelLoopClosureGeometricRejection
                    .LEDGER_INLIER_BUDGET_MISMATCH)
        }
        val bound = candidate.residual + candidate.uncertainty
        if (!bound.isFinite() ||
            evidence.priorDisagreementMeters > bound ||
            result.rmseMeters > bound) {
            return result.copy(
                rejection = ArKernelLoopClosureGeometricRejection
                    .LEDGER_RESIDUAL_BOUND_MISMATCH)
        }
        return result
    }

    private fun evaluateEvidence(
        evidence: ArKernelLoopClosureGeometricEvidence,
        policy: ArKernelLoopClosureGeometricPolicy,
    ): ArKernelLoopClosureGeometricReport {
        val count = evidence.correspondences.size
        if (count < policy.minCorrespondences) {
            return report(
                count, 0, 0.0, 0.0, 0.0,
                evidence.priorDisagreementMeters, 0.0,
                ArKernelLoopClosureGeometricRejection.INSUFFICIENT_EVIDENCE)
        }
        if (count > policy.maxCorrespondences) {
            return report(
                count, 0, 0.0, 0.0, 0.0,
                evidence.priorDisagreementMeters, 0.0,
                ArKernelLoopClosureGeometricRejection
                    .INVALID_CORRESPONDENCE_COUNT)
        }
        val rotation = evidence.rotation
        val normSquared = rotation.w * rotation.w +
            rotation.x * rotation.x +
            rotation.y * rotation.y +
            rotation.z * rotation.z
        if (!normSquared.isFinite() || normSquared <= 0.0 ||
            abs(sqrt(normSquared) - 1.0) > policy.quaternionTolerance) {
            return report(
                count, 0, 0.0, 0.0, 0.0,
                evidence.priorDisagreementMeters, 0.0,
                ArKernelLoopClosureGeometricRejection.INVALID_ROTATION)
        }
        val scale = 1.0 / sqrt(normSquared)
        val unitRotation = Rotation(
            rotation.w * scale,
            rotation.x * scale,
            rotation.y * scale,
            rotation.z * scale,
        )
        val pointsA = evidence.correspondences.map { it.pointA }
        val pointsB = evidence.correspondences.map { it.pointB }
        val spreadMeters = minOf(spread(pointsA), spread(pointsB))
        if (!spreadMeters.isFinite()) {
            return report(
                count, 0, 0.0, 0.0, 0.0,
                evidence.priorDisagreementMeters, 0.0,
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER)
        }
        if (spreadMeters < policy.minSpreadMeters) {
            return report(
                count, 0, 0.0, 0.0, 0.0,
                evidence.priorDisagreementMeters, spreadMeters,
                ArKernelLoopClosureGeometricRejection.DEGENERATE_GEOMETRY)
        }
        var sumSquares = 0.0
        var maxResidual = 0.0
        var inliers = 0
        for (correspondence in evidence.correspondences) {
            val rotated = rotate(unitRotation, correspondence.pointB)
            val dx = rotated.x + evidence.translation.x -
                correspondence.pointA.x
            val dy = rotated.y + evidence.translation.y -
                correspondence.pointA.y
            val dz = rotated.z + evidence.translation.z -
                correspondence.pointA.z
            val squared = dx * dx + dy * dy + dz * dz
            if (!squared.isFinite()) {
                return report(
                    count, 0, 0.0, 0.0, 0.0,
                    evidence.priorDisagreementMeters, spreadMeters,
                    ArKernelLoopClosureGeometricRejection.INVALID_NUMBER)
            }
            val residual = sqrt(squared)
            sumSquares += squared
            maxResidual = maxOf(maxResidual, residual)
            if (residual <= policy.inlierThresholdMeters) ++inliers
        }
        if (!sumSquares.isFinite()) {
            return report(
                count, 0, 0.0, 0.0, 0.0,
                evidence.priorDisagreementMeters, spreadMeters,
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER)
        }
        val rmse = sqrt(sumSquares / count.toDouble())
        if (!rmse.isFinite() || !maxResidual.isFinite()) {
            return report(
                count, 0, 0.0, 0.0, 0.0,
                evidence.priorDisagreementMeters, spreadMeters,
                ArKernelLoopClosureGeometricRejection.INVALID_NUMBER)
        }
        val inlierRatio = inliers.toDouble() / count.toDouble()
        val result = report(
            count, inliers, maxResidual, rmse, inlierRatio,
            evidence.priorDisagreementMeters, spreadMeters,
            ArKernelLoopClosureGeometricRejection.NONE)
        if (evidence.priorDisagreementMeters >
            policy.maxPriorDisagreementMeters) {
            return result.copy(
                rejection = ArKernelLoopClosureGeometricRejection
                    .PRIOR_DISAGREEMENT_TOO_LARGE)
        }
        if (maxResidual > policy.maxResidualMeters) {
            return result.copy(
                rejection = ArKernelLoopClosureGeometricRejection
                    .RESIDUAL_TOO_LARGE)
        }
        if (rmse > policy.maxRmseMeters) {
            return result.copy(
                rejection = ArKernelLoopClosureGeometricRejection
                    .RMSE_TOO_LARGE)
        }
        if (inliers < policy.minInliers) {
            return result.copy(
                rejection = ArKernelLoopClosureGeometricRejection
                    .INLIERS_TOO_FEW)
        }
        if (inlierRatio < policy.minInlierRatio) {
            return result.copy(
                rejection = ArKernelLoopClosureGeometricRejection
                    .INLIER_RATIO_TOO_LOW)
        }
        return result
    }

    private fun rotate(rotation: Rotation, point: Vector3): Vector3 {
        val ux = rotation.x
        val uy = rotation.y
        val uz = rotation.z
        val w = rotation.w
        val twiceCrossX = 2.0 * (uy * point.z - uz * point.y)
        val twiceCrossY = 2.0 * (uz * point.x - ux * point.z)
        val twiceCrossZ = 2.0 * (ux * point.y - uy * point.x)
        val secondX = uy * twiceCrossZ - uz * twiceCrossY
        val secondY = uz * twiceCrossX - ux * twiceCrossZ
        val secondZ = ux * twiceCrossY - uy * twiceCrossX
        return Vector3(
            point.x + w * twiceCrossX + secondX,
            point.y + w * twiceCrossY + secondY,
            point.z + w * twiceCrossZ + secondZ,
        )
    }

    private fun spread(points: List<Vector3>): Double {
        var maximum = 0.0
        for (first in points.indices) {
            for (second in first + 1 until points.size) {
                val dx = points[first].x - points[second].x
                val dy = points[first].y - points[second].y
                val dz = points[first].z - points[second].z
                maximum = maxOf(maximum, sqrt(
                    dx * dx + dy * dy + dz * dz))
            }
        }
        return maximum
    }

    private fun report(
        correspondenceCount: Int,
        inlierCount: Int,
        maxResidualMeters: Double,
        rmseMeters: Double,
        inlierRatio: Double,
        priorDisagreementMeters: Double,
        spreadMeters: Double,
        rejection: ArKernelLoopClosureGeometricRejection,
    ): ArKernelLoopClosureGeometricReport =
        ArKernelLoopClosureGeometricReport(
            correspondenceCount,
            inlierCount,
            maxResidualMeters,
            rmseMeters,
            inlierRatio,
            priorDisagreementMeters,
            spreadMeters,
            rejection,
        )

    private fun emptyReport(
        rejection: ArKernelLoopClosureGeometricRejection,
    ): ArKernelLoopClosureGeometricReport =
        report(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, rejection)

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelLoopClosureGeometricParseResult? {
        if (lineText.length > GC_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelLoopClosureGeometricRejection.LINE_TOO_LONG,
                lineNumber,
                "Geometric evidence line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelLoopClosureGeometricRejection.EMPTY_LINE,
                lineNumber,
                "Geometric evidence contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
                lineNumber,
                "Geometric evidence lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (GC_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun decimal(value: String): Double? {
        if (!GC_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        return if (parsed.isFinite()) parsed else null
    }

    private fun reject(
        rejection: ArKernelLoopClosureGeometricRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelLoopClosureGeometricParseResult =
        ArKernelLoopClosureGeometricParseResult(
            null, rejection, lineNumber, detail)
}
