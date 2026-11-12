package ai.arena.arkernel

import kotlin.math.abs

private const val TARGETS_HEADER = "ARKTARGET1"
private const val TARGETS_VERSION = "1"
private const val TARGETS_QUALITY = "unverified"
private const val TARGETS_END = "END"
private const val TARGETS_MAX_INPUT_CHARACTERS = 16_384
private const val TARGETS_MAX_LINE_CHARACTERS = 256
private const val TARGETS_MAX_TARGETS = 16
private const val TARGETS_MAX_REQUIRED = 8
private const val TARGETS_ROW_FIELDS = 10
private const val TARGETS_MIN_MILLIMETRES = 1L
private const val TARGETS_MAX_MILLIMETRES = 100_000L
private const val TARGETS_MIN_TOLERANCE_MILLIMETRES = 1L
private const val TARGETS_MAX_TOLERANCE_MILLIMETRES = 10_000L
private const val TARGETS_MIN_REPEATS = 1
private const val TARGETS_MAX_REPEATS = 64
private const val TARGETS_DIMENSION_UNIT = "mm"

private val TARGETS_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val TARGETS_UNSIGNED = Regex("0|[1-9][0-9]*")

private val TARGETS_TRUE_KEYS = listOf(
    "ground_truth_manual",
    "automatic_calibration_disabled",
    "universal_calibration_disabled",
    "per_device_calibration",
    "automatic_upload_disabled",
    "manual_upload_only",
    "images_disabled",
)

private val TARGETS_FALSE_KEYS = listOf("metric_accuracy_claimed")

private val TARGETS_KEYS = listOf(
    "version",
    "registry_id",
    "device_token",
    "campaign_id",
    "required_independent_targets",
    "target_count",
    "consistent_count",
    "independent_count",
    "held_out_count",
    "usable_check_count",
    "max_deviation_mm",
    "targets_consistent",
    "independence_sufficient",
    "ground_truth_unit",
    "tolerance_unit",
    "ground_truth_manual",
    "automatic_calibration_disabled",
    "universal_calibration_disabled",
    "per_device_calibration",
    "automatic_upload_disabled",
    "manual_upload_only",
    "images_disabled",
    "metric_accuracy_claimed",
    "quality_grade",
)

/** The kind of physical reference object a row describes. */
enum class ArKernelTargetKind {
    PRINTED_CHECKERBOARD,
    PRINTED_APRILTAG,
    PRINTED_ARUCO,
    MACHINED_BAR,
    GAUGE_BLOCK,
    TAPE_SPAN,
    ROOM_DIAGONAL,
    FLOOR_TILE,
    ;

    fun wireName(): String = when (this) {
        PRINTED_CHECKERBOARD -> "printed.checkerboard"
        PRINTED_APRILTAG -> "printed.apriltag"
        PRINTED_ARUCO -> "printed.aruco"
        MACHINED_BAR -> "machined.bar"
        GAUGE_BLOCK -> "gauge.block"
        TAPE_SPAN -> "tape.span"
        ROOM_DIAGONAL -> "room.diagonal"
        FLOOR_TILE -> "floor.tile"
    }

    companion object {
        fun fromWire(value: String): ArKernelTargetKind? =
            entries.firstOrNull { it.wireName() == value }
    }
}

/**
 * How the declared dimension of a target is known.
 *
 * Every entry is a *human, physical* source. The library never measures a
 * target itself and never receives a measurement from a camera frame.
 */
enum class ArKernelTargetDimensionSource {
    MANUFACTURER_SPEC,
    CALIPER,
    TAPE_MEASURE,
    LASER_DISTANCE_METER,
    CERTIFIED_GAUGE,
    ;

    fun wireName(): String = when (this) {
        MANUFACTURER_SPEC -> "manufacturer.spec"
        CALIPER -> "caliper"
        TAPE_MEASURE -> "tape.measure"
        LASER_DISTANCE_METER -> "laser.distance"
        CERTIFIED_GAUGE -> "certified.gauge"
    }

    companion object {
        fun fromWire(value: String): ArKernelTargetDimensionSource? =
            entries.firstOrNull { it.wireName() == value }
    }
}

enum class ArKernelIndependentTargetsRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_REGISTRY,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_COUNT,
    INVALID_ORDER,
    INVALID_ROW,
    INVALID_KIND,
    INVALID_SOURCE,
    ROLE_CONFLICT,
    DUPLICATE_TARGET,
    STATISTICS_MISMATCH,
    INDEPENDENCE_MISMATCH,
}

/**
 * One physical reference target and the part it plays.
 *
 * `nominalMillimetres` is the dimension the target is supposed to have —
 * printed on it, specified by its maker, or engraved on a gauge block.
 * `measuredMillimetres` is what the operator actually measured on the real
 * object with a real tool. The gap between the two is the target's own error,
 * and it is why a sheet that came out of the printer at the wrong scale is
 * caught here instead of silently wrecking every measurement taken against it.
 *
 * `usedForScale` records whether this target's value was fed into the scale
 * estimate. `heldOut` records whether its measured value was withheld from
 * calibration. A target that did both is a contradiction and is rejected with
 * `ROLE_CONFLICT`: a value that set the scale was by definition not withheld.
 */
data class ArKernelIndependentTargetRow(
    val index: Int,
    val targetId: String,
    val kind: ArKernelTargetKind,
    val nominalMillimetres: Long,
    val measuredMillimetres: Long,
    val toleranceMillimetres: Long,
    val source: ArKernelTargetDimensionSource,
    val usedForScale: Boolean,
    val heldOut: Boolean,
    val repeatCount: Int,
) {
    /** How far the real object is from its declared dimension. */
    val deviationMillimetres: Long
        get() = abs(measuredMillimetres - nominalMillimetres)

    /** Whether this object is trustworthy as a reference at all. */
    val consistent: Boolean
        get() = deviationMillimetres <= toleranceMillimetres

    /** Whether this target was kept out of the scale estimate. */
    val independent: Boolean
        get() = !usedForScale

    /** Whether this target can serve as a non-circular check. */
    val usableAsCheck: Boolean
        get() = consistent && independent && heldOut
}

/**
 * A bounded registry of physical reference targets with their roles.
 *
 * The point of the registry is non-circularity bookkeeping. A validation that
 * compares a reconstruction against the very target used to set its scale
 * proves nothing about accuracy; it only proves the arithmetic round trips.
 * `independenceSufficient` says whether enough targets survived the filter for
 * a later check to mean something.
 *
 * It does **not** say the reconstruction is accurate. `targetsConsistent`
 * describes the physical objects, not the app: the app never measures a
 * target, never sees a camera frame and never receives a pose here. Every
 * millimetre in this record was typed in by a human holding a tool.
 */
data class ArKernelIndependentTargetRegistry(
    val registryId: String,
    val deviceToken: String,
    val campaignId: String,
    val requiredIndependentTargets: Int,
    val targets: List<ArKernelIndependentTargetRow>,
) {
    val targetCount: Int
        get() = targets.size

    val consistentCount: Int
        get() = targets.count { it.consistent }

    val independentCount: Int
        get() = targets.count { it.independent }

    val heldOutCount: Int
        get() = targets.count { it.heldOut }

    val usableCheckCount: Int
        get() = targets.count { it.usableAsCheck }

    val maxDeviationMillimetres: Long
        get() = targets.maxOf { it.deviationMillimetres }

    val targetsConsistent: Boolean
        get() = targets.all { it.consistent }

    val independenceSufficient: Boolean
        get() = usableCheckCount >= requiredIndependentTargets

    /** Ids of the targets that can serve as a non-circular check. */
    fun usableCheckIds(): List<String> =
        targets.filter { it.usableAsCheck }.map { it.targetId }
}

data class ArKernelIndependentTargetSummary(
    val targetCount: Int,
    val consistentCount: Int,
    val independentCount: Int,
    val heldOutCount: Int,
    val usableCheckCount: Int,
    val maxDeviationMillimetres: Long,
    val distinctKinds: Int,
    val distinctSources: Int,
    val targetsConsistent: Boolean,
    val independenceSufficient: Boolean,
)

data class ArKernelIndependentTargetResult(
    val registry: ArKernelIndependentTargetRegistry?,
    val rejection: ArKernelIndependentTargetsRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelIndependentTargetsRejection.NONE &&
            registry != null
}

data class ArKernelIndependentTargetParseResult(
    val registry: ArKernelIndependentTargetRegistry?,
    val rejection: ArKernelIndependentTargetsRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelIndependentTargetsRejection.NONE &&
            registry != null
}

/**
 * Canonical `ARKTARGET1` registries of independent physical targets.
 *
 * The adapter refuses anything that would let a record smuggle in a claim it
 * has not earned: a target that both set the scale and claims to be held out,
 * a duplicate id, a declared count that disagrees with the rows, or an
 * `independence_sufficient` that contradicts the recomputed one. It performs
 * no measurement, no calibration, no inference, no file I/O and no upload,
 * and the grade stays `unverified`.
 */
object ArKernelIndependentTargetsAdapter {

    fun create(
        registryId: String,
        deviceToken: String,
        campaignId: String,
        requiredIndependentTargets: Int,
        targets: List<ArKernelIndependentTargetRow>,
    ): ArKernelIndependentTargetResult {
        if (!TARGETS_TOKEN.matches(registryId) ||
            !TARGETS_TOKEN.matches(deviceToken) ||
            !TARGETS_TOKEN.matches(campaignId)
        ) {
            return createReject(
                ArKernelIndependentTargetsRejection.INVALID_TOKEN,
                "Invalid independent target token")
        }
        if (requiredIndependentTargets < 1 ||
            requiredIndependentTargets > TARGETS_MAX_REQUIRED
        ) {
            return createReject(
                ArKernelIndependentTargetsRejection.INVALID_COUNT,
                "Required independent targets are out of bounds")
        }
        if (targets.isEmpty() || targets.size > TARGETS_MAX_TARGETS) {
            return createReject(
                ArKernelIndependentTargetsRejection.INVALID_COUNT,
                "Independent target count is out of bounds")
        }
        val seen = HashSet<String>()
        for ((index, target) in targets.withIndex()) {
            if (target.index != index) {
                return createReject(
                    ArKernelIndependentTargetsRejection.INVALID_ORDER,
                    "Independent target rows are not contiguous")
            }
            if (!TARGETS_TOKEN.matches(target.targetId)) {
                return createReject(
                    ArKernelIndependentTargetsRejection.INVALID_TOKEN,
                    "Invalid independent target id")
            }
            if (!seen.add(target.targetId)) {
                return createReject(
                    ArKernelIndependentTargetsRejection.DUPLICATE_TARGET,
                    "Duplicate independent target id ${target.targetId}")
            }
            val rangeError = checkMillimetres(target)
            if (rangeError != null) {
                return createReject(
                    rangeError,
                    "Independent target dimension is out of bounds")
            }
            if (target.usedForScale && target.heldOut) {
                return createReject(
                    ArKernelIndependentTargetsRejection.ROLE_CONFLICT,
                    "Target ${target.targetId} cannot set the scale " +
                        "and be held out")
            }
            if (target.repeatCount < TARGETS_MIN_REPEATS ||
                target.repeatCount > TARGETS_MAX_REPEATS
            ) {
                return createReject(
                    ArKernelIndependentTargetsRejection.INVALID_COUNT,
                    "Independent target repeat count is out of bounds")
            }
        }
        return ArKernelIndependentTargetResult(
            registry = ArKernelIndependentTargetRegistry(
                registryId = registryId,
                deviceToken = deviceToken,
                campaignId = campaignId,
                requiredIndependentTargets = requiredIndependentTargets,
                targets = targets.toList(),
            ),
            rejection = ArKernelIndependentTargetsRejection.NONE,
            detail = "accepted",
        )
    }

    fun summarize(
        registry: ArKernelIndependentTargetRegistry,
    ): ArKernelIndependentTargetSummary =
        ArKernelIndependentTargetSummary(
            targetCount = registry.targetCount,
            consistentCount = registry.consistentCount,
            independentCount = registry.independentCount,
            heldOutCount = registry.heldOutCount,
            usableCheckCount = registry.usableCheckCount,
            maxDeviationMillimetres = registry.maxDeviationMillimetres,
            distinctKinds = registry.targets.map { it.kind }.distinct().size,
            distinctSources =
                registry.targets.map { it.source }.distinct().size,
            targetsConsistent = registry.targetsConsistent,
            independenceSufficient = registry.independenceSufficient,
        )

    fun encode(registry: ArKernelIndependentTargetRegistry): String {
        val lines =
            ArrayList<String>(TARGETS_KEYS.size + registry.targets.size)
        lines += TARGETS_HEADER
        lines += "version=$TARGETS_VERSION"
        lines += "registry_id=${registry.registryId}"
        lines += "device_token=${registry.deviceToken}"
        lines += "campaign_id=${registry.campaignId}"
        lines += "required_independent_targets=" +
            registry.requiredIndependentTargets
        lines += "target_count=${registry.targetCount}"
        lines += "consistent_count=${registry.consistentCount}"
        lines += "independent_count=${registry.independentCount}"
        lines += "held_out_count=${registry.heldOutCount}"
        lines += "usable_check_count=${registry.usableCheckCount}"
        lines += "max_deviation_mm=${registry.maxDeviationMillimetres}"
        lines += "targets_consistent=${boolText(registry.targetsConsistent)}"
        lines += "independence_sufficient=" +
            boolText(registry.independenceSufficient)
        lines += "ground_truth_unit=$TARGETS_DIMENSION_UNIT"
        lines += "tolerance_unit=$TARGETS_DIMENSION_UNIT"
        for (key in TARGETS_TRUE_KEYS) lines += "$key=true"
        for (key in TARGETS_FALSE_KEYS) lines += "$key=false"
        lines += "quality_grade=$TARGETS_QUALITY"
        for (target in registry.targets) lines += targetRow(target)
        lines += TARGETS_END
        return lines.joinToString("\n") + "\n"
    }

    fun parse(text: String): ArKernelIndependentTargetParseResult {
        if (text.length > TARGETS_MAX_INPUT_CHARACTERS * 2) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INPUT_TOO_LARGE, 0,
                "Independent target registry exceeds its bounded length")
        }
        if (text.isBlank()) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.EMPTY_REGISTRY, 0,
                "Independent target registry is empty")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        val lines = normalized.trimEnd('\n').split('\n')
        val headerError = validateLine(lines[0].trimEnd('\r'), 1)
        if (headerError != null) return headerError
        if (lines[0].trimEnd('\r') != TARGETS_HEADER) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_HEADER, 1,
                "Independent target registry must start with ARKTARGET1")
        }
        if (lines.size < TARGETS_KEYS.size + 2) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_SCHEMA, 1,
                "Independent target registry is missing its key block")
        }
        val values = LinkedHashMap<String, String>()
        val lineByKey = LinkedHashMap<String, Int>()
        for ((offset, key) in TARGETS_KEYS.withIndex()) {
            val lineNumber = offset + 2
            val lineText = lines[offset + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val separator = lineText.indexOf('=')
            if (separator <= 0) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_SCHEMA,
                    lineNumber, "Independent target line is not a key")
            }
            if (lineText.substring(0, separator) != key) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_SCHEMA,
                    lineNumber, "Independent target key order is invalid")
            }
            values[key] = lineText.substring(separator + 1)
            lineByKey[key] = lineNumber
        }
        if (values.getValue("version") != TARGETS_VERSION) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Independent target version must be one")
        }
        if (values.getValue("quality_grade") != TARGETS_QUALITY) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Independent targets cannot assert assessed quality")
        }
        for (key in TARGETS_TRUE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Independent target flag must be a boolean")
            }
            if (value != "true") {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Independent target safety invariant must be true")
            }
        }
        for (key in TARGETS_FALSE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Independent target flag must be a boolean")
            }
            if (value != "false") {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Independent target safety invariant must be false")
            }
        }
        if (values.getValue("ground_truth_unit") !=
            TARGETS_DIMENSION_UNIT ||
            values.getValue("tolerance_unit") != TARGETS_DIMENSION_UNIT
        ) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_VALUE,
                lineByKey.getValue("ground_truth_unit"),
                "Independent target dimensions must be millimetres")
        }
        for (key in listOf("registry_id", "device_token", "campaign_id")) {
            if (!TARGETS_TOKEN.matches(values.getValue(key))) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid independent target token")
            }
        }
        val requiredText = values.getValue("required_independent_targets")
        val requiredValue = unsigned(requiredText)
            ?: return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_VALUE,
                lineByKey.getValue("required_independent_targets"),
                "Invalid required independent target count")
        if (requiredValue < 1 || requiredValue > TARGETS_MAX_REQUIRED) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_COUNT,
                lineByKey.getValue("required_independent_targets"),
                "Required independent targets are out of bounds")
        }
        val required = requiredValue.toInt()
        val targetCountValue = unsigned(values.getValue("target_count"))
            ?: return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_VALUE,
                lineByKey.getValue("target_count"),
                "Invalid independent target count")
        if (targetCountValue < 1 || targetCountValue > TARGETS_MAX_TARGETS) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_COUNT,
                lineByKey.getValue("target_count"),
                "Independent target count is out of bounds")
        }
        val targetCount = targetCountValue.toInt()
        val consistentText = values.getValue("targets_consistent")
        val sufficientText = values.getValue("independence_sufficient")
        if (!isBoolean(consistentText) || !isBoolean(sufficientText)) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_BOOLEAN,
                lineByKey.getValue("targets_consistent"),
                "Independent target verdict must be a boolean")
        }
        val declared = LinkedHashMap<String, Long>()
        for (key in listOf(
            "consistent_count",
            "independent_count",
            "held_out_count",
            "usable_check_count",
            "max_deviation_mm",
        )) {
            val value = unsigned(values.getValue(key))
                ?: return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_VALUE,
                    lineByKey.getValue(key),
                    "Invalid independent target statistic")
            declared[key] = value
        }
        val expectedRows = TARGETS_KEYS.size + 1 + targetCount + 1
        if (lines.size != expectedRows) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_SCHEMA,
                lines.size,
                "Independent target row count does not match target_count")
        }
        val targets = ArrayList<ArKernelIndependentTargetRow>(targetCount)
        val seen = HashSet<String>()
        for (index in 0 until targetCount) {
            val lineNumber = TARGETS_KEYS.size + 2 + index
            val rowText = lines[TARGETS_KEYS.size + 1 + index].trimEnd('\r')
            val lineError = validateLine(rowText, lineNumber)
            if (lineError != null) return lineError
            if (!rowText.startsWith("target=")) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_ROW,
                    lineNumber, "Independent target row is missing")
            }
            val fields = rowText.substring("target=".length).split('|')
            if (fields.size != TARGETS_ROW_FIELDS) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_ROW,
                    lineNumber, "Independent target must have ten fields")
            }
            val rowIndex = unsigned(fields[0])
                ?: return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_VALUE,
                    lineNumber, "Invalid independent target row index")
            if (rowIndex.toInt() != index) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_ORDER,
                    lineNumber, "Independent target rows are not contiguous")
            }
            if (!TARGETS_TOKEN.matches(fields[1])) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_TOKEN,
                    lineNumber, "Invalid independent target id")
            }
            if (!seen.add(fields[1])) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.DUPLICATE_TARGET,
                    lineNumber,
                    "Duplicate independent target id ${fields[1]}")
            }
            val kind = ArKernelTargetKind.fromWire(fields[2])
                ?: return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_KIND,
                    lineNumber, "Invalid independent target kind")
            val nominal = unsigned(fields[3])
                ?: return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_VALUE,
                    lineNumber, "Invalid nominal dimension")
            val measured = unsigned(fields[4])
                ?: return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_VALUE,
                    lineNumber, "Invalid measured dimension")
            val tolerance = unsigned(fields[5])
                ?: return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_VALUE,
                    lineNumber, "Invalid tolerance")
            val source = ArKernelTargetDimensionSource.fromWire(fields[6])
                ?: return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_SOURCE,
                    lineNumber, "Invalid independent target source")
            if (!isBoolean(fields[7]) || !isBoolean(fields[8])) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_BOOLEAN,
                    lineNumber, "Independent target role must be a boolean")
            }
            val usedForScale = fields[7] == "true"
            val heldOut = fields[8] == "true"
            if (usedForScale && heldOut) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.ROLE_CONFLICT,
                    lineNumber,
                    "Target ${fields[1]} cannot set the scale and be held out")
            }
            val repeats = unsigned(fields[9])
                ?: return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_VALUE,
                    lineNumber, "Invalid repeat count")
            if (repeats < TARGETS_MIN_REPEATS ||
                repeats.toInt() > TARGETS_MAX_REPEATS
            ) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection.INVALID_COUNT,
                    lineNumber, "Repeat count is out of bounds")
            }
            val row = ArKernelIndependentTargetRow(
                index = index,
                targetId = fields[1],
                kind = kind,
                nominalMillimetres = nominal,
                measuredMillimetres = measured,
                toleranceMillimetres = tolerance,
                source = source,
                usedForScale = usedForScale,
                heldOut = heldOut,
                repeatCount = repeats.toInt(),
            )
            val rangeError = checkMillimetres(row)
            if (rangeError != null) {
                return targetsReject(
                    rangeError, lineNumber,
                    "Independent target dimension is out of bounds")
            }
            targets += row
        }
        val endLine = lines[lines.size - 1].trimEnd('\r')
        if (endLine != TARGETS_END) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_SCHEMA,
                lines.size, "Independent target registry must end with END")
        }
        val result = create(
            registryId = values.getValue("registry_id"),
            deviceToken = values.getValue("device_token"),
            campaignId = values.getValue("campaign_id"),
            requiredIndependentTargets = required,
            targets = targets,
        )
        if (!result.accepted) {
            return targetsReject(
                result.rejection, TARGETS_KEYS.size + 2, result.detail)
        }
        val registry = result.registry
            ?: return targetsReject(
                ArKernelIndependentTargetsRejection.INVALID_SCHEMA, 1,
                "Independent target registry could not be rebuilt")
        val expected = mapOf(
            "consistent_count" to registry.consistentCount.toLong(),
            "independent_count" to registry.independentCount.toLong(),
            "held_out_count" to registry.heldOutCount.toLong(),
            "usable_check_count" to registry.usableCheckCount.toLong(),
            "max_deviation_mm" to registry.maxDeviationMillimetres,
        )
        for ((key, value) in expected) {
            if (declared.getValue(key) != value) {
                return targetsReject(
                    ArKernelIndependentTargetsRejection
                        .STATISTICS_MISMATCH,
                    lineByKey.getValue(key),
                    "Recomputed $key disagrees with the declared value")
            }
        }
        if ((consistentText == "true") != registry.targetsConsistent) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.STATISTICS_MISMATCH,
                lineByKey.getValue("targets_consistent"),
                "Recomputed target consistency disagrees")
        }
        if ((sufficientText == "true") != registry.independenceSufficient) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.INDEPENDENCE_MISMATCH,
                lineByKey.getValue("independence_sufficient"),
                "Recomputed independence disagrees with the declared verdict")
        }
        return ArKernelIndependentTargetParseResult(
            registry = registry,
            rejection = ArKernelIndependentTargetsRejection.NONE,
            lineNumber = 0,
            detail = "accepted",
        )
    }

    private fun checkMillimetres(
        target: ArKernelIndependentTargetRow,
    ): ArKernelIndependentTargetsRejection? {
        val nominal = target.nominalMillimetres
        val measured = target.measuredMillimetres
        val tolerance = target.toleranceMillimetres
        if (nominal < TARGETS_MIN_MILLIMETRES ||
            nominal > TARGETS_MAX_MILLIMETRES ||
            measured < TARGETS_MIN_MILLIMETRES ||
            measured > TARGETS_MAX_MILLIMETRES
        ) {
            return ArKernelIndependentTargetsRejection.INVALID_VALUE
        }
        if (tolerance < TARGETS_MIN_TOLERANCE_MILLIMETRES ||
            tolerance > TARGETS_MAX_TOLERANCE_MILLIMETRES
        ) {
            return ArKernelIndependentTargetsRejection.INVALID_VALUE
        }
        return null
    }

    private fun targetRow(target: ArKernelIndependentTargetRow): String =
        "target=" + listOf(
            target.index.toString(),
            target.targetId,
            target.kind.wireName(),
            target.nominalMillimetres.toString(),
            target.measuredMillimetres.toString(),
            target.toleranceMillimetres.toString(),
            target.source.wireName(),
            boolText(target.usedForScale),
            boolText(target.heldOut),
            target.repeatCount.toString(),
        ).joinToString("|")

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun isBoolean(value: String): Boolean =
        value == "true" || value == "false"

    private fun unsigned(value: String): Long? =
        if (TARGETS_UNSIGNED.matches(value)) value.toLongOrNull() else null

    private fun validateLine(
        line: String,
        lineNumber: Int,
    ): ArKernelIndependentTargetParseResult? {
        if (line.isEmpty()) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.EMPTY_LINE,
                lineNumber, "Independent target line is empty")
        }
        if (line.length > TARGETS_MAX_LINE_CHARACTERS) {
            return targetsReject(
                ArKernelIndependentTargetsRejection.LINE_TOO_LONG,
                lineNumber, "Independent target line is too long")
        }
        return null
    }

    private fun targetsReject(
        rejection: ArKernelIndependentTargetsRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelIndependentTargetParseResult =
        ArKernelIndependentTargetParseResult(
            registry = null,
            rejection = rejection,
            lineNumber = lineNumber,
            detail = detail,
        )

    private fun createReject(
        rejection: ArKernelIndependentTargetsRejection,
        detail: String,
    ): ArKernelIndependentTargetResult =
        ArKernelIndependentTargetResult(
            registry = null,
            rejection = rejection,
            detail = detail,
        )
}
