package ai.arena.arkernel

private const val COV_HEADER = "ARKCOV1"
private const val COV_VERSION = "1"
private const val COV_QUALITY = "unverified"
private const val COV_END = "END"
private const val COV_ABSENT = "none"
private const val COV_MAX_INPUT_CHARACTERS = 32_768
private const val COV_MAX_LINE_CHARACTERS = 256
private const val COV_MAX_SELECTIONS = 64
private const val COV_MAX_UNCOVERED = 64
private const val COV_MAX_REQUIREMENTS = 16_384
private const val COV_MAX_WEIGHT = 1_000_000

private val COV_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val COV_VALUE = Regex("[A-Za-z0-9._:+-]{1,32}")
private val COV_UNSIGNED = Regex("0|[1-9][0-9]*")
private val COV_KEYS = listOf(
    "version",
    "inventory_id",
    "selection_count",
    "uncovered_count",
    "covered_requirements",
    "total_requirements",
    "covered_weight",
    "total_weight",
    "provider_binding_disabled",
    "mobly_rig_control_disabled",
    "thermal_run_execution_disabled",
    "physical_scene_capture_disabled",
    "automatic_upload_disabled",
    "quality_grade",
)
private val COV_SAFETY_KEYS = listOf(
    "provider_binding_disabled",
    "mobly_rig_control_disabled",
    "thermal_run_execution_disabled",
    "physical_scene_capture_disabled",
    "automatic_upload_disabled",
)

enum class ArKernelCoveragePlanRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_LEDGER,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    UNSAFE_INVARIANT,
    INVALID_TOKEN,
    INVALID_DIMENSION,
    INVALID_COUNT,
    INVALID_WEIGHT,
    INVALID_FEATURE_COUNT,
    DUPLICATE_CANDIDATE,
    DUPLICATE_REQUIREMENT,
    INVALID_ORDER,
    INVALID_CONSISTENCY,
}

/** Fail-closed parse result; on rejection no partially trusted ledger is returned. */
data class ArKernelCoveragePlanParseResult(
    val ledger: ArKernelCoveragePlanLedger?,
    val rejection: ArKernelCoveragePlanRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelCoveragePlanRejection.NONE && ledger != null
}

/** One deterministic greedy selection result. */
data class ArKernelCoverageSelection(
    val index: Int,
    val candidateId: String,
    val newlyCoveredRequirements: Int,
    val newlyCoveredWeight: Int,
) {
    init {
        require(index >= 0) { "Invalid coverage selection index" }
        require(COV_TOKEN.matches(candidateId)) {
            "Invalid coverage selection candidate ID"
        }
        require(newlyCoveredRequirements in 0..COV_MAX_REQUIREMENTS) {
            "Invalid coverage selection requirement count"
        }
        require(newlyCoveredWeight in 0..COV_MAX_WEIGHT) {
            "Invalid coverage selection weight"
        }
    }
}

/**
 * One uncovered single or pairwise requirement. `dimension2`/`value2` are
 * null exactly when `featureCount` is one; a two-feature requirement keeps
 * canonical ascending dimension order.
 */
data class ArKernelCoverageUncovered(
    val index: Int,
    val featureCount: Int,
    val dimension1: CapabilityCoverageDimension,
    val value1: String,
    val dimension2: CapabilityCoverageDimension?,
    val value2: String?,
    val weight: Int,
) {
    init {
        require(index >= 0) { "Invalid uncovered requirement index" }
        require(featureCount in 1..2) {
            "Invalid uncovered requirement feature count"
        }
        require(COV_VALUE.matches(value1)) {
            "Invalid uncovered requirement value"
        }
        require(weight in 1..100) {
            "Invalid uncovered requirement weight"
        }
        if (featureCount == 1) {
            require(dimension2 == null && value2 == null) {
                "A single-feature requirement must not carry a second feature"
            }
        } else {
            val second = requireNotNull(dimension2) {
                "A pairwise requirement needs a second feature"
            }
            val secondValue = requireNotNull(value2) {
                "A pairwise requirement needs a second value"
            }
            require(second.ordinal > dimension1.ordinal) {
                "Uncovered requirement dimensions are not canonical"
            }
            require(COV_VALUE.matches(secondValue)) {
                "Invalid uncovered requirement second value"
            }
        }
    }

    /** Rebuilds the existing immutable requirement data object. */
    fun requirement(): CapabilityCoverageRequirement {
        val first = CapabilityCoverageFeature(dimension1, value1)
        val features = if (featureCount == 1) listOf(first)
        else listOf(
            first,
            CapabilityCoverageFeature(
                requireNotNull(dimension2),
                requireNotNull(value2),
            ),
        )
        return CapabilityCoverageRequirement(features, weight)
    }
}

/**
 * Bounded canonical ledger for one host-supplied weighted set-cover plan.
 *
 * All five safety invariants must stay disabled: provider binding, Mobly rig
 * control, thermal-run execution, physical scene capture and automatic upload.
 * Aggregate counts, selection sums and requirement coverage must be exactly
 * consistent. The grade stays `unverified`.
 */
data class ArKernelCoveragePlanLedger(
    val inventoryId: String,
    val selections: List<ArKernelCoverageSelection>,
    val uncovered: List<ArKernelCoverageUncovered>,
    val coveredRequirements: Int,
    val totalRequirements: Int,
    val coveredWeight: Int,
    val totalWeight: Int,
    val qualityGrade: String = COV_QUALITY,
) {
    init {
        require(COV_TOKEN.matches(inventoryId)) {
            "Invalid coverage inventory ID"
        }
        require(qualityGrade == COV_QUALITY) {
            "Coverage plan ledger cannot assert assessed quality"
        }
        require(selections.size <= COV_MAX_SELECTIONS &&
            uncovered.size <= COV_MAX_UNCOVERED) {
            "Coverage plan ledger exceeds its row budgets"
        }
        require(selections.withIndex().all {
            it.value.index == it.index
        }) { "Coverage selections are not contiguous" }
        require(uncovered.withIndex().all {
            it.value.index == it.index
        }) { "Uncovered requirements are not contiguous" }
        require(selections.map { it.candidateId }.toSet().size ==
            selections.size) {
            "Coverage plan ledger contains duplicate candidate IDs"
        }
        require(uncovered.map {
            listOf(
                it.dimension1.name,
                it.value1,
                it.dimension2?.name ?: COV_ABSENT,
                it.value2 ?: COV_ABSENT,
            )
        }.toSet().size == uncovered.size) {
            "Coverage plan ledger contains duplicate uncovered requirements"
        }
        require(coveredRequirements in 0..COV_MAX_REQUIREMENTS &&
            totalRequirements in 0..COV_MAX_REQUIREMENTS) {
            "Coverage requirement totals are out of bounds"
        }
        require(coveredWeight in 0..COV_MAX_WEIGHT &&
            totalWeight in 0..COV_MAX_WEIGHT) {
            "Coverage weight totals are out of bounds"
        }
        require(coveredRequirements + uncovered.size ==
            totalRequirements) {
            "Covered and uncovered requirements do not sum to the total"
        }
        require(coveredWeight <= totalWeight) {
            "Covered weight exceeds total weight"
        }
        require(selections.sumOf {
            it.newlyCoveredRequirements
        } == coveredRequirements) {
            "Selection requirement counts do not sum to covered requirements"
        }
        require(selections.sumOf {
            it.newlyCoveredWeight
        } == coveredWeight) {
            "Selection weights do not sum to covered weight"
        }
    }

    /** Rebuilds the existing immutable set-cover plan data object. */
    fun toPlan(): CapabilityCoveragePlan =
        CapabilityCoveragePlan(
            selections.map {
                CapabilityCoverageSelection(
                    it.candidateId,
                    it.newlyCoveredRequirements,
                    it.newlyCoveredWeight,
                )
            },
            coveredRequirements,
            totalRequirements,
            coveredWeight,
            totalWeight,
            uncovered.map { it.requirement() },
        )
}

/** Bounded structural facts only; never a device-farm or accuracy grade. */
data class ArKernelCoveragePlanReport(
    val selectionCount: Int,
    val uncoveredCount: Int,
    val distinctCandidateIds: Int,
    val coveredRequirements: Int,
    val totalRequirements: Int,
    val coveredWeight: Int,
    val totalWeight: Int,
    val singleFeatureUncoveredCount: Int,
    val pairwiseFeatureUncoveredCount: Int,
    val distinctDimensions: Int,
    val complete: Boolean,
    val qualityGrade: String = COV_QUALITY,
)

/**
 * Canonical `ARKCOV1` text adapter for a bounded host-owned set-cover plan.
 *
 * It enforces exact metadata order, strict input/line/row budgets, unique
 * opaque candidate IDs, contiguous indices, exact
 * `CapabilityCoverageDimension` names with canonical ascending order for
 * pairwise requirements, bounded value tokens and weights, five fail-closed
 * safety invariants, BOM/CRLF tolerance with one optional final separator and
 * a fail-closed `END`. `fromPlan()` serializes an existing
 * [CapabilityCoveragePlan] and `toPlan()` rebuilds it; `summarize()` reports
 * only structural counts, sums and distinctness. The adapter binds no Cloud
 * or Lab provider, controls no Mobly sensor-fusion rig, executes no thermal
 * or day/night/weather run, captures no physical scene, uploads nothing,
 * performs no I/O and upgrades no quality. Concrete provider inventories,
 * rig automation and device-farm evidence remain host work; the grade stays
 * `unverified`.
 */
object ArKernelCoveragePlanBundle {
    fun encode(ledger: ArKernelCoveragePlanLedger): String {
        val lines = ArrayList<String>(
            COV_KEYS.size + ledger.selections.size +
                ledger.uncovered.size + 2)
        lines.add(COV_HEADER)
        lines.add("version=$COV_VERSION")
        lines.add("inventory_id=${ledger.inventoryId}")
        lines.add("selection_count=${ledger.selections.size}")
        lines.add("uncovered_count=${ledger.uncovered.size}")
        lines.add("covered_requirements=${ledger.coveredRequirements}")
        lines.add("total_requirements=${ledger.totalRequirements}")
        lines.add("covered_weight=${ledger.coveredWeight}")
        lines.add("total_weight=${ledger.totalWeight}")
        COV_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${ledger.qualityGrade}")
        for (selection in ledger.selections) {
            lines.add(
                "selection=${selection.index}|" +
                    "${selection.candidateId}|" +
                    "${selection.newlyCoveredRequirements}|" +
                    selection.newlyCoveredWeight)
        }
        for (uncovered in ledger.uncovered) {
            val dim2 = uncovered.dimension2?.name ?: COV_ABSENT
            val value2 = uncovered.value2 ?: COV_ABSENT
            lines.add(
                "uncovered=${uncovered.index}|" +
                    "${uncovered.featureCount}|" +
                    "${uncovered.dimension1.name}|" +
                    "${uncovered.value1}|" +
                    "$dim2|" +
                    "$value2|" +
                    uncovered.weight)
        }
        lines.add(COV_END)
        val text = lines.joinToString("\n")
        require(text.length <= COV_MAX_INPUT_CHARACTERS) {
            "Coverage plan ledger exceeds its bounded size"
        }
        require(lines.all { it.length <= COV_MAX_LINE_CHARACTERS }) {
            "Coverage plan ledger line exceeds its bounded length"
        }
        return text
    }

    /** Serializes one existing bounded plan; no provider or rig is contacted. */
    fun fromPlan(
        plan: CapabilityCoveragePlan,
        inventoryId: String = "host.inventory.v1",
    ): ArKernelCoveragePlanLedger {
        require(plan.selections.size <= COV_MAX_SELECTIONS &&
            plan.uncoveredRequirements.size <= COV_MAX_UNCOVERED) {
            "Coverage plan exceeds the bounded ledger row budgets"
        }
        require(plan.coveredRequirements in 0..COV_MAX_REQUIREMENTS &&
            plan.totalRequirements in 0..COV_MAX_REQUIREMENTS &&
            plan.coveredWeight in 0..COV_MAX_WEIGHT &&
            plan.totalWeight in 0..COV_MAX_WEIGHT) {
            "Coverage plan totals exceed the bounded ledger"
        }
        val selections = plan.selections.mapIndexed { index, selection ->
            ArKernelCoverageSelection(
                index,
                selection.candidateId,
                selection.newlyCoveredRequirements,
                selection.newlyCoveredWeight,
            )
        }
        val uncovered = plan.uncoveredRequirements.mapIndexed {
            index, requirement ->
            val features = requirement.features
            require(features.size in 1..2 &&
                features.all { COV_VALUE.matches(it.value) }) {
                "Coverage plan requirement is outside the bounded ledger"
            }
            ArKernelCoverageUncovered(
                index,
                features.size,
                features[0].dimension,
                features[0].value,
                features.getOrNull(1)?.dimension,
                features.getOrNull(1)?.value,
                requirement.weight,
            )
        }
        return ArKernelCoveragePlanLedger(
            inventoryId,
            selections,
            uncovered,
            plan.coveredRequirements,
            plan.totalRequirements,
            plan.coveredWeight,
            plan.totalWeight,
        )
    }

    fun parse(text: String): ArKernelCoveragePlanParseResult {
        if (text.length > COV_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelCoveragePlanRejection.INPUT_TOO_LARGE,
                0,
                "Coverage plan input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelCoveragePlanRejection.EMPTY_LEDGER,
                0,
                "Coverage plan input is empty")
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
                ArKernelCoveragePlanRejection.EMPTY_LEDGER,
                0,
                "Coverage plan input contains no ledger")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelCoveragePlanRejection.EMPTY_LINE,
                0,
                "Coverage plan has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != COV_HEADER ||
            header.any { it.isWhitespace() } ||
            header.length > COV_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_HEADER,
                1,
                "Coverage plan header is invalid")
        }
        if (lines.size < COV_KEYS.size + 2) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                0,
                "Coverage plan is too short")
        }
        val lineByKey = COV_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(COV_KEYS.size)
        for (index in COV_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Coverage metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != COV_KEYS[index]) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Coverage metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != COV_VERSION) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported coverage plan version")
        }
        if (values.getValue("quality_grade") != COV_QUALITY) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Coverage plan cannot assert assessed quality")
        }
        for (key in COV_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelCoveragePlanRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Coverage plan safety invariant must be true")
            }
        }
        val inventoryId = values.getValue("inventory_id")
        if (!COV_TOKEN.matches(inventoryId)) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_TOKEN,
                lineByKey.getValue("inventory_id"),
                "Invalid coverage inventory ID")
        }
        val selectionCount = boundedInt(
            values.getValue("selection_count"),
            COV_MAX_SELECTIONS)
            ?: return reject(
                ArKernelCoveragePlanRejection.INVALID_COUNT,
                lineByKey.getValue("selection_count"),
                "Invalid coverage selection count")
        val uncoveredCount = boundedInt(
            values.getValue("uncovered_count"),
            COV_MAX_UNCOVERED)
            ?: return reject(
                ArKernelCoveragePlanRejection.INVALID_COUNT,
                lineByKey.getValue("uncovered_count"),
                "Invalid uncovered requirement count")
        val coveredRequirements = boundedInt(
            values.getValue("covered_requirements"),
            COV_MAX_REQUIREMENTS)
            ?: return reject(
                ArKernelCoveragePlanRejection.INVALID_VALUE,
                lineByKey.getValue("covered_requirements"),
                "Invalid covered requirement total")
        val totalRequirements = boundedInt(
            values.getValue("total_requirements"),
            COV_MAX_REQUIREMENTS)
            ?: return reject(
                ArKernelCoveragePlanRejection.INVALID_VALUE,
                lineByKey.getValue("total_requirements"),
                "Invalid total requirement count")
        val coveredWeight = boundedInt(
            values.getValue("covered_weight"),
            COV_MAX_WEIGHT)
            ?: return reject(
                ArKernelCoveragePlanRejection.INVALID_VALUE,
                lineByKey.getValue("covered_weight"),
                "Invalid covered weight total")
        val totalWeight = boundedInt(
            values.getValue("total_weight"),
            COV_MAX_WEIGHT)
            ?: return reject(
                ArKernelCoveragePlanRejection.INVALID_VALUE,
                lineByKey.getValue("total_weight"),
                "Invalid total weight")
        val entryStartIndex = COV_KEYS.size + 1
        val uncoveredStartIndex = entryStartIndex + selectionCount
        val endIndex = uncoveredStartIndex + uncoveredCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                0,
                "Coverage plan line count does not match its row counts")
        }
        val selections =
            ArrayList<ArKernelCoverageSelection>(selectionCount)
        val seenCandidates = HashSet<String>(selectionCount)
        for (index in 0 until selectionCount) {
            val lineNumber = entryStartIndex + index + 1
            val lineText = lines[entryStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("selection=")) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Coverage plan line is not a selection")
            }
            val fields = lineText.substringAfter("selection=")
                .split('|')
            if (fields.size != 4) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Coverage selection must have four fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid coverage selection index")
            if (rowIndex != index) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_ORDER,
                    lineNumber,
                    "Coverage selections are not contiguous")
            }
            val candidateId = fields[1]
            if (!COV_TOKEN.matches(candidateId)) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid coverage selection candidate ID")
            }
            if (!seenCandidates.add(candidateId)) {
                return reject(
                    ArKernelCoveragePlanRejection.DUPLICATE_CANDIDATE,
                    lineNumber,
                    "Coverage selection candidate ID is reused")
            }
            val newlyCovered = unsignedInt(fields[2])
                ?: return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid coverage selection requirement count")
            val newlyWeight = unsignedInt(fields[3])
                ?: return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid coverage selection weight")
            if (newlyCovered > COV_MAX_REQUIREMENTS ||
                newlyWeight > COV_MAX_WEIGHT) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Coverage selection count or weight is out of bounds")
            }
            selections += try {
                ArKernelCoverageSelection(
                    rowIndex, candidateId, newlyCovered, newlyWeight)
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid coverage selection")
            }
        }
        val uncovered =
            ArrayList<ArKernelCoverageUncovered>(uncoveredCount)
        val seenRequirements =
            HashSet<List<String>>(uncoveredCount)
        for (index in 0 until uncoveredCount) {
            val lineNumber = uncoveredStartIndex + index + 1
            val lineText = lines[uncoveredStartIndex + index]
                .trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("uncovered=")) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Coverage plan line is not an uncovered requirement")
            }
            val fields = lineText.substringAfter("uncovered=")
                .split('|')
            if (fields.size != 7) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Uncovered requirement must have seven fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid uncovered requirement index")
            if (rowIndex != index) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_ORDER,
                    lineNumber,
                    "Uncovered requirements are not contiguous")
            }
            val featureCount = unsignedInt(fields[1])
                ?: return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid uncovered feature count")
            if (featureCount !in 1..2) {
                return reject(
                    ArKernelCoveragePlanRejection
                        .INVALID_FEATURE_COUNT,
                    lineNumber,
                    "Uncovered feature count must be one or two")
            }
            val dimension1 = dimensionFromText(fields[2])
                ?: return reject(
                    ArKernelCoveragePlanRejection.INVALID_DIMENSION,
                    lineNumber,
                    "Invalid uncovered first dimension")
            if (!COV_VALUE.matches(fields[3])) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid uncovered first value")
            }
            val dimension2: CapabilityCoverageDimension?
            val value2: String?
            if (featureCount == 1) {
                if (fields[4] != COV_ABSENT ||
                    fields[5] != COV_ABSENT) {
                    return reject(
                        ArKernelCoveragePlanRejection
                            .INVALID_FEATURE_COUNT,
                        lineNumber,
                        "Single-feature requirement must use none placeholders")
                }
                dimension2 = null
                value2 = null
            } else {
                dimension2 = dimensionFromText(fields[4])
                    ?: return reject(
                        ArKernelCoveragePlanRejection
                            .INVALID_DIMENSION,
                        lineNumber,
                        "Invalid uncovered second dimension")
                if (dimension2.ordinal <= dimension1.ordinal) {
                    return reject(
                        ArKernelCoveragePlanRejection
                            .INVALID_DIMENSION,
                        lineNumber,
                        "Uncovered dimensions are not ascending")
                }
                if (!COV_VALUE.matches(fields[5])) {
                    return reject(
                        ArKernelCoveragePlanRejection.INVALID_TOKEN,
                        lineNumber,
                        "Invalid uncovered second value")
                }
                value2 = fields[5]
            }
            val weight = unsignedInt(fields[6])
                ?: return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid uncovered requirement weight")
            if (weight !in 1..100) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_WEIGHT,
                    lineNumber,
                    "Uncovered requirement weight is out of bounds")
            }
            val key = listOf(
                dimension1.name, fields[3],
                dimension2?.name ?: COV_ABSENT,
                value2 ?: COV_ABSENT,
            )
            if (!seenRequirements.add(key)) {
                return reject(
                    ArKernelCoveragePlanRejection
                        .DUPLICATE_REQUIREMENT,
                    lineNumber,
                    "Uncovered requirement is reused")
            }
            uncovered += try {
                ArKernelCoverageUncovered(
                    rowIndex,
                    featureCount,
                    dimension1,
                    fields[3],
                    dimension2,
                    value2,
                    weight,
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelCoveragePlanRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid uncovered requirement")
            }
        }
        val endText = lines[endIndex].trimEnd('\r')
        val endLineNumber = endIndex + 1
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != COV_END) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                endLineNumber,
                "Coverage plan must end with END")
        }
        if (coveredRequirements + uncoveredCount !=
            totalRequirements ||
            coveredWeight > totalWeight ||
            selections.sumOf { it.newlyCoveredRequirements } !=
                coveredRequirements ||
            selections.sumOf { it.newlyCoveredWeight } !=
                coveredWeight) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_CONSISTENCY,
                0,
                "Coverage plan aggregates are inconsistent")
        }
        val ledger = try {
            ArKernelCoveragePlanLedger(
                inventoryId,
                selections,
                uncovered,
                coveredRequirements,
                totalRequirements,
                coveredWeight,
                totalWeight,
                COV_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_CONSISTENCY,
                0,
                error.message ?: "Invalid coverage plan ledger")
        }
        return ArKernelCoveragePlanParseResult(
            ledger,
            ArKernelCoveragePlanRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(ledger: ArKernelCoveragePlanLedger):
        ArKernelCoveragePlanReport =
        ArKernelCoveragePlanReport(
            selectionCount = ledger.selections.size,
            uncoveredCount = ledger.uncovered.size,
            distinctCandidateIds = ledger.selections
                .map { it.candidateId }.toSet().size,
            coveredRequirements = ledger.coveredRequirements,
            totalRequirements = ledger.totalRequirements,
            coveredWeight = ledger.coveredWeight,
            totalWeight = ledger.totalWeight,
            singleFeatureUncoveredCount = ledger.uncovered.count {
                it.featureCount == 1
            },
            pairwiseFeatureUncoveredCount = ledger.uncovered.count {
                it.featureCount == 2
            },
            distinctDimensions = ledger.uncovered.flatMap {
                listOfNotNull(it.dimension1, it.dimension2)
            }.toSet().size,
            complete = ledger.uncovered.isEmpty(),
        )

    private fun dimensionFromText(
        value: String,
    ): CapabilityCoverageDimension? =
        CapabilityCoverageDimension.entries.firstOrNull {
            it.name == value
        }

    private fun boundedInt(
        value: String,
        maximum: Int,
    ): Int? {
        val parsed = unsignedInt(value) ?: return null
        return if (parsed <= maximum) parsed else null
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelCoveragePlanParseResult? {
        if (lineText.length > COV_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelCoveragePlanRejection.LINE_TOO_LONG,
                lineNumber,
                "Coverage plan line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelCoveragePlanRejection.EMPTY_LINE,
                lineNumber,
                "Coverage plan contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelCoveragePlanRejection.INVALID_SCHEMA,
                lineNumber,
                "Coverage plan lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (COV_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun reject(
        rejection: ArKernelCoveragePlanRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelCoveragePlanParseResult =
        ArKernelCoveragePlanParseResult(
            null, rejection, lineNumber, detail)
}
