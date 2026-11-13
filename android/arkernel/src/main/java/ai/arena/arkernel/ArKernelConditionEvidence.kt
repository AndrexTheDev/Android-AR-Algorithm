package ai.arena.arkernel

private const val COND_HEADER = "ARKCOND1"
private const val COND_VERSION = "1"
private const val COND_QUALITY = "unverified"
private const val COND_END = "END"
private const val COND_MAX_INPUT_CHARACTERS = 16_384
private const val COND_MAX_LINE_CHARACTERS = 256
private const val COND_MAX_SAMPLES = 64
private const val COND_MAX_REQUIRED_CONDITIONS = 6
private const val COND_MAX_MIN_SAMPLES = 64
private const val COND_ROW_FIELDS = 10
private const val COND_MIN_POSE = 0
private const val COND_MAX_POSE = 1_023
private const val COND_MIN_REPEAT = 0
private const val COND_MAX_REPEAT = 63
private const val COND_MIN_THERMAL = 0
private const val COND_MAX_THERMAL = 6
private const val COND_MIN_HEADROOM = 0
private const val COND_MAX_HEADROOM = 1_000
private const val COND_MAX_LUX_MILLI = 200_000_000L
private const val COND_LUX_UNIT = "mlux"
private const val COND_THERMAL_SOURCE = "host_reported"

private val COND_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val COND_UNSIGNED = Regex("0|[1-9][0-9]*")

private val COND_TRUE_KEYS = listOf(
    "automatic_capture_disabled",
    "automatic_upload_disabled",
    "manual_upload_only",
    "images_disabled",
)

private val COND_FALSE_KEYS = listOf("metric_accuracy_claimed")

private val COND_KEYS = listOf(
    "version",
    "ledger_id",
    "device_token",
    "campaign_id",
    "required_conditions",
    "min_samples_per_condition",
    "max_thermal_status",
    "day_min_lux_milli",
    "night_max_lux_milli",
    "sample_count",
    "condition_count",
    "covered_conditions",
    "day_count",
    "night_count",
    "rig_count",
    "handheld_count",
    "repeat_count",
    "label_conflicts",
    "min_lux_milli",
    "max_lux_milli",
    "observed_max_thermal_status",
    "day_night_covered",
    "labels_coherent",
    "thermal_stable",
    "repeatability_documented",
    "evidence_sufficient",
    "lux_unit",
    "thermal_source",
    "automatic_capture_disabled",
    "automatic_upload_disabled",
    "manual_upload_only",
    "images_disabled",
    "metric_accuracy_claimed",
    "quality_grade",
)

/** How the device was held or mounted while a sample was taken. */
enum class ArKernelRigKind {
    HANDHELD,
    TRIPOD,
    TURNTABLE,
    LINEAR_RAIL,
    GANTRY,
    ;

    fun wireName(): String = when (this) {
        HANDHELD -> "handheld"
        TRIPOD -> "tripod"
        TURNTABLE -> "turntable"
        LINEAR_RAIL -> "linear.rail"
        GANTRY -> "gantry"
    }

    companion object {
        fun fromWire(value: String): ArKernelRigKind? =
            entries.firstOrNull { it.wireName() == value }
    }
}

enum class ArKernelConditionEvidenceRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_LEDGER,
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
    INVALID_CONDITION,
    INVALID_RIG,
    NON_MONOTONIC,
    DUPLICATE_SAMPLE,
    STATISTICS_MISMATCH,
    EVIDENCE_MISMATCH,
}

/**
 * One controlled-condition sample.
 *
 * `thermalStatus` and `headroomPermille` come from the platform thermal API,
 * `luxMilli` from the ambient light sensor. They are readings from this device
 * at this moment, not a calibrated measurement of the scene: a phone in a
 * pocket reads darkness in daylight, and a lamp pointed at the sensor reads
 * daylight at night.
 *
 * `condition` is a label the operator chose. The ledger does not verify the
 * label against reality — it only checks the label against the lux thresholds
 * **the operator also declared**, so a run mislabelled by mistake shows up as
 * a conflict instead of passing silently.
 */
data class ArKernelConditionSample(
    val index: Int,
    val condition: ArKernelMeasurementCondition,
    val rigId: String,
    val rigKind: ArKernelRigKind,
    val poseIndex: Int,
    val repeatIndex: Int,
    val thermalStatus: Int,
    val headroomPermille: Int,
    val luxMilli: Long,
    val timestampMillis: Long,
)

/**
 * A bounded ledger of controlled-condition evidence.
 *
 * What it can show: that you actually sampled in enough conditions, that both
 * day and night are covered, that the device was not thermally throttled
 * while you did it, that at least one pose was repeated so the repeatability
 * claim has something behind it, and that no run is labelled against its own
 * declared light thresholds.
 *
 * What it cannot show: that any of it improved accuracy. Every number here is
 * a reading from one device or a label typed by a human. The ledger performs
 * no capture, no inference, no upload and no quality assessment.
 */
data class ArKernelConditionLedger(
    val ledgerId: String,
    val deviceToken: String,
    val campaignId: String,
    val requiredConditions: Int,
    val minSamplesPerCondition: Int,
    val maxThermalStatus: Int,
    val dayMinLuxMilli: Long,
    val nightMaxLuxMilli: Long,
    val samples: List<ArKernelConditionSample>,
) {
    val sampleCount: Int
        get() = samples.size

    val conditionCount: Int
        get() = samples.map { it.condition }.distinct().size

    val coveredConditions: Int
        get() = samples
            .groupingBy { it.condition }
            .eachCount()
            .count { it.value >= minSamplesPerCondition }

    val dayCount: Int
        get() = samples.count {
            it.condition == ArKernelMeasurementCondition.DAY
        }

    val nightCount: Int
        get() = samples.count {
            it.condition == ArKernelMeasurementCondition.NIGHT
        }

    val rigCount: Int
        get() = samples.map { it.rigId }.distinct().size

    val handheldCount: Int
        get() = samples.count {
            it.rigKind == ArKernelRigKind.HANDHELD
        }

    val repeatCount: Int
        get() = samples.count { it.repeatIndex > 0 }

    /** Samples whose lux reading contradicts the operator's own label. */
    val labelConflicts: Int
        get() = samples.count { sample ->
            when (sample.condition) {
                ArKernelMeasurementCondition.DAY ->
                    sample.luxMilli < dayMinLuxMilli
                ArKernelMeasurementCondition.NIGHT ->
                    sample.luxMilli > nightMaxLuxMilli
                else -> false
            }
        }

    val minLuxMilli: Long
        get() = samples.minOf { it.luxMilli }

    val maxLuxMilli: Long
        get() = samples.maxOf { it.luxMilli }

    val observedMaxThermalStatus: Int
        get() = samples.maxOf { it.thermalStatus }

    val dayNightCovered: Boolean
        get() = dayCount >= minSamplesPerCondition &&
            nightCount >= minSamplesPerCondition

    val labelsCoherent: Boolean
        get() = labelConflicts == 0

    val thermalStable: Boolean
        get() = observedMaxThermalStatus <= maxThermalStatus

    /** Whether any single pose was captured at least twice. */
    val repeatabilityDocumented: Boolean
        get() = samples
            .groupingBy { Triple(it.rigId, it.condition, it.poseIndex) }
            .eachCount()
            .any { it.value >= 2 }

    val evidenceSufficient: Boolean
        get() = dayNightCovered &&
            labelsCoherent &&
            thermalStable &&
            repeatabilityDocumented &&
            coveredConditions >= requiredConditions

    /** Ids of the rigs that were actually used. */
    fun rigIds(): List<String> =
        samples.map { it.rigId }.distinct()
}

data class ArKernelConditionSummary(
    val sampleCount: Int,
    val conditionCount: Int,
    val coveredConditions: Int,
    val dayCount: Int,
    val nightCount: Int,
    val rigCount: Int,
    val handheldCount: Int,
    val repeatCount: Int,
    val labelConflicts: Int,
    val minLuxMilli: Long,
    val maxLuxMilli: Long,
    val observedMaxThermalStatus: Int,
    val dayNightCovered: Boolean,
    val labelsCoherent: Boolean,
    val thermalStable: Boolean,
    val repeatabilityDocumented: Boolean,
    val evidenceSufficient: Boolean,
)

data class ArKernelConditionResult(
    val ledger: ArKernelConditionLedger?,
    val rejection: ArKernelConditionEvidenceRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelConditionEvidenceRejection.NONE &&
            ledger != null
}

data class ArKernelConditionParseResult(
    val ledger: ArKernelConditionLedger?,
    val rejection: ArKernelConditionEvidenceRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelConditionEvidenceRejection.NONE &&
            ledger != null
}

/**
 * Canonical `ARKCOND1` ledgers of controlled-condition evidence.
 *
 * The adapter recomputes every declared count and every verdict on parse and
 * rejects the file on any disagreement, so a ledger cannot claim coverage it
 * did not collect. It performs no capture, no inference, no file I/O, no
 * upload and no quality assessment; the grade stays `unverified`.
 */
object ArKernelConditionEvidenceAdapter {

    fun create(
        ledgerId: String,
        deviceToken: String,
        campaignId: String,
        requiredConditions: Int,
        minSamplesPerCondition: Int,
        maxThermalStatus: Int,
        dayMinLuxMilli: Long,
        nightMaxLuxMilli: Long,
        samples: List<ArKernelConditionSample>,
    ): ArKernelConditionResult {
        if (!COND_TOKEN.matches(ledgerId) ||
            !COND_TOKEN.matches(deviceToken) ||
            !COND_TOKEN.matches(campaignId)
        ) {
            return createReject(
                ArKernelConditionEvidenceRejection.INVALID_TOKEN,
                "Invalid condition ledger token")
        }
        if (requiredConditions < 1 ||
            requiredConditions > COND_MAX_REQUIRED_CONDITIONS
        ) {
            return createReject(
                ArKernelConditionEvidenceRejection.INVALID_COUNT,
                "Required conditions are out of bounds")
        }
        if (minSamplesPerCondition < 1 ||
            minSamplesPerCondition > COND_MAX_MIN_SAMPLES
        ) {
            return createReject(
                ArKernelConditionEvidenceRejection.INVALID_COUNT,
                "Minimum samples per condition are out of bounds")
        }
        if (maxThermalStatus < COND_MIN_THERMAL ||
            maxThermalStatus > COND_MAX_THERMAL
        ) {
            return createReject(
                ArKernelConditionEvidenceRejection.INVALID_VALUE,
                "Maximum thermal status is out of bounds")
        }
        if (dayMinLuxMilli > COND_MAX_LUX_MILLI ||
            nightMaxLuxMilli > COND_MAX_LUX_MILLI
        ) {
            return createReject(
                ArKernelConditionEvidenceRejection.INVALID_VALUE,
                "Light threshold is out of bounds")
        }
        if (samples.isEmpty() || samples.size > COND_MAX_SAMPLES) {
            return createReject(
                ArKernelConditionEvidenceRejection.INVALID_COUNT,
                "Condition sample count is out of bounds")
        }
        val seen = HashSet<String>()
        var previousTimestamp = Long.MIN_VALUE
        for ((index, sample) in samples.withIndex()) {
            if (sample.index != index) {
                return createReject(
                    ArKernelConditionEvidenceRejection.INVALID_ORDER,
                    "Condition samples are not contiguous")
            }
            if (!COND_TOKEN.matches(sample.rigId)) {
                return createReject(
                    ArKernelConditionEvidenceRejection.INVALID_TOKEN,
                    "Invalid condition rig id")
            }
            if (sample.poseIndex < COND_MIN_POSE ||
                sample.poseIndex > COND_MAX_POSE ||
                sample.repeatIndex < COND_MIN_REPEAT ||
                sample.repeatIndex > COND_MAX_REPEAT ||
                sample.thermalStatus < COND_MIN_THERMAL ||
                sample.thermalStatus > COND_MAX_THERMAL ||
                sample.headroomPermille < COND_MIN_HEADROOM ||
                sample.headroomPermille > COND_MAX_HEADROOM ||
                sample.luxMilli > COND_MAX_LUX_MILLI
            ) {
                return createReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    "Condition sample value is out of bounds")
            }
            if (sample.timestampMillis <= previousTimestamp) {
                return createReject(
                    ArKernelConditionEvidenceRejection.NON_MONOTONIC,
                    "Condition sample timestamps must increase")
            }
            previousTimestamp = sample.timestampMillis
            val key = listOf(
                sample.rigId,
                sample.condition.wireName(),
                sample.poseIndex.toString(),
                sample.repeatIndex.toString(),
            ).joinToString("|")
            if (!seen.add(key)) {
                return createReject(
                    ArKernelConditionEvidenceRejection.DUPLICATE_SAMPLE,
                    "Duplicate condition sample $key")
            }
        }
        return ArKernelConditionResult(
            ledger = ArKernelConditionLedger(
                ledgerId = ledgerId,
                deviceToken = deviceToken,
                campaignId = campaignId,
                requiredConditions = requiredConditions,
                minSamplesPerCondition = minSamplesPerCondition,
                maxThermalStatus = maxThermalStatus,
                dayMinLuxMilli = dayMinLuxMilli,
                nightMaxLuxMilli = nightMaxLuxMilli,
                samples = samples.toList(),
            ),
            rejection = ArKernelConditionEvidenceRejection.NONE,
            detail = "accepted",
        )
    }

    fun summarize(
        ledger: ArKernelConditionLedger,
    ): ArKernelConditionSummary =
        ArKernelConditionSummary(
            sampleCount = ledger.sampleCount,
            conditionCount = ledger.conditionCount,
            coveredConditions = ledger.coveredConditions,
            dayCount = ledger.dayCount,
            nightCount = ledger.nightCount,
            rigCount = ledger.rigCount,
            handheldCount = ledger.handheldCount,
            repeatCount = ledger.repeatCount,
            labelConflicts = ledger.labelConflicts,
            minLuxMilli = ledger.minLuxMilli,
            maxLuxMilli = ledger.maxLuxMilli,
            observedMaxThermalStatus = ledger.observedMaxThermalStatus,
            dayNightCovered = ledger.dayNightCovered,
            labelsCoherent = ledger.labelsCoherent,
            thermalStable = ledger.thermalStable,
            repeatabilityDocumented = ledger.repeatabilityDocumented,
            evidenceSufficient = ledger.evidenceSufficient,
        )

    fun encode(ledger: ArKernelConditionLedger): String {
        val lines =
            ArrayList<String>(COND_KEYS.size + ledger.samples.size)
        lines += COND_HEADER
        lines += "version=$COND_VERSION"
        lines += "ledger_id=${ledger.ledgerId}"
        lines += "device_token=${ledger.deviceToken}"
        lines += "campaign_id=${ledger.campaignId}"
        lines += "required_conditions=${ledger.requiredConditions}"
        lines += "min_samples_per_condition=" +
            ledger.minSamplesPerCondition
        lines += "max_thermal_status=${ledger.maxThermalStatus}"
        lines += "day_min_lux_milli=${ledger.dayMinLuxMilli}"
        lines += "night_max_lux_milli=${ledger.nightMaxLuxMilli}"
        lines += "sample_count=${ledger.sampleCount}"
        lines += "condition_count=${ledger.conditionCount}"
        lines += "covered_conditions=${ledger.coveredConditions}"
        lines += "day_count=${ledger.dayCount}"
        lines += "night_count=${ledger.nightCount}"
        lines += "rig_count=${ledger.rigCount}"
        lines += "handheld_count=${ledger.handheldCount}"
        lines += "repeat_count=${ledger.repeatCount}"
        lines += "label_conflicts=${ledger.labelConflicts}"
        lines += "min_lux_milli=${ledger.minLuxMilli}"
        lines += "max_lux_milli=${ledger.maxLuxMilli}"
        lines += "observed_max_thermal_status=" +
            ledger.observedMaxThermalStatus
        lines += "day_night_covered=${boolText(ledger.dayNightCovered)}"
        lines += "labels_coherent=${boolText(ledger.labelsCoherent)}"
        lines += "thermal_stable=${boolText(ledger.thermalStable)}"
        lines += "repeatability_documented=" +
            boolText(ledger.repeatabilityDocumented)
        lines += "evidence_sufficient=" +
            boolText(ledger.evidenceSufficient)
        lines += "lux_unit=$COND_LUX_UNIT"
        lines += "thermal_source=$COND_THERMAL_SOURCE"
        for (key in COND_TRUE_KEYS) lines += "$key=true"
        for (key in COND_FALSE_KEYS) lines += "$key=false"
        lines += "quality_grade=$COND_QUALITY"
        for (sample in ledger.samples) lines += sampleRow(sample)
        lines += COND_END
        return lines.joinToString("\n") + "\n"
    }

    fun parse(text: String): ArKernelConditionParseResult {
        if (text.length > COND_MAX_INPUT_CHARACTERS * 2) {
            return condReject(
                ArKernelConditionEvidenceRejection.INPUT_TOO_LARGE, 0,
                "Condition ledger exceeds its bounded length")
        }
        if (text.isBlank()) {
            return condReject(
                ArKernelConditionEvidenceRejection.EMPTY_LEDGER, 0,
                "Condition ledger is empty")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        val lines = normalized.trimEnd('\n').split('\n')
        val headerError = validateLine(lines[0].trimEnd('\r'), 1)
        if (headerError != null) return headerError
        if (lines[0].trimEnd('\r') != COND_HEADER) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_HEADER, 1,
                "Condition ledger must start with ARKCOND1")
        }
        if (lines.size < COND_KEYS.size + 2) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_SCHEMA, 1,
                "Condition ledger is missing its key block")
        }
        val values = LinkedHashMap<String, String>()
        val lineByKey = LinkedHashMap<String, Int>()
        for ((offset, key) in COND_KEYS.withIndex()) {
            val lineNumber = offset + 2
            val lineText = lines[offset + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val separator = lineText.indexOf('=')
            if (separator <= 0) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_SCHEMA,
                    lineNumber, "Condition line is not a key")
            }
            if (lineText.substring(0, separator) != key) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_SCHEMA,
                    lineNumber, "Condition key order is invalid")
            }
            values[key] = lineText.substring(separator + 1)
            lineByKey[key] = lineNumber
        }
        if (values.getValue("version") != COND_VERSION) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Condition version must be one")
        }
        if (values.getValue("quality_grade") != COND_QUALITY) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Condition evidence cannot assert assessed quality")
        }
        for (key in COND_TRUE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Condition flag must be a boolean")
            }
            if (value != "true") {
                return condReject(
                    ArKernelConditionEvidenceRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Condition safety invariant must be true")
            }
        }
        for (key in COND_FALSE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Condition flag must be a boolean")
            }
            if (value != "false") {
                return condReject(
                    ArKernelConditionEvidenceRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Condition safety invariant must be false")
            }
        }
        if (values.getValue("lux_unit") != COND_LUX_UNIT) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_VALUE,
                lineByKey.getValue("lux_unit"),
                "Condition light unit must be mlux")
        }
        if (values.getValue("thermal_source") != COND_THERMAL_SOURCE) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_VALUE,
                lineByKey.getValue("thermal_source"),
                "Condition thermal source must be host_reported")
        }
        for (key in listOf("ledger_id", "device_token", "campaign_id")) {
            if (!COND_TOKEN.matches(values.getValue(key))) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid condition ledger token")
            }
        }
        val smallCounts = listOf(
            "required_conditions",
            "min_samples_per_condition",
            "max_thermal_status",
            "sample_count",
        )
        val ints = LinkedHashMap<String, Int>()
        for (key in smallCounts) {
            val raw = unsigned(values.getValue(key))
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineByKey.getValue(key),
                    "Invalid condition count")
            if (raw > 1_000L) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_COUNT,
                    lineByKey.getValue(key),
                    "Condition count is out of bounds")
            }
            ints[key] = raw.toInt()
        }
        val requiredConditions = ints.getValue("required_conditions")
        val minSamples = ints.getValue("min_samples_per_condition")
        val maxThermal = ints.getValue("max_thermal_status")
        val sampleCount = ints.getValue("sample_count")
        if (requiredConditions < 1 ||
            requiredConditions > COND_MAX_REQUIRED_CONDITIONS
        ) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_COUNT,
                lineByKey.getValue("required_conditions"),
                "Required conditions are out of bounds")
        }
        if (minSamples < 1 || minSamples > COND_MAX_MIN_SAMPLES) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_COUNT,
                lineByKey.getValue("min_samples_per_condition"),
                "Minimum samples per condition are out of bounds")
        }
        if (maxThermal < COND_MIN_THERMAL ||
            maxThermal > COND_MAX_THERMAL
        ) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_VALUE,
                lineByKey.getValue("max_thermal_status"),
                "Maximum thermal status is out of bounds")
        }
        if (sampleCount < 1 || sampleCount > COND_MAX_SAMPLES) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_COUNT,
                lineByKey.getValue("sample_count"),
                "Condition sample count is out of bounds")
        }
        val luxThresholds = LinkedHashMap<String, Long>()
        for (key in listOf("day_min_lux_milli", "night_max_lux_milli")) {
            val raw = unsigned(values.getValue(key))
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineByKey.getValue(key),
                    "Invalid light threshold")
            if (raw > COND_MAX_LUX_MILLI) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineByKey.getValue(key),
                    "Light threshold is out of bounds")
            }
            luxThresholds[key] = raw
        }
        val declared = LinkedHashMap<String, Long>()
        for (key in listOf(
            "condition_count",
            "covered_conditions",
            "day_count",
            "night_count",
            "rig_count",
            "handheld_count",
            "repeat_count",
            "label_conflicts",
            "min_lux_milli",
            "max_lux_milli",
            "observed_max_thermal_status",
        )) {
            val raw = unsigned(values.getValue(key))
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineByKey.getValue(key),
                    "Invalid condition statistic")
            declared[key] = raw
        }
        val verdicts = listOf(
            "day_night_covered",
            "labels_coherent",
            "thermal_stable",
            "repeatability_documented",
            "evidence_sufficient",
        )
        for (key in verdicts) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Condition verdict must be a boolean")
            }
        }
        val expectedRows = COND_KEYS.size + 1 + sampleCount + 1
        if (lines.size != expectedRows) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_SCHEMA,
                lines.size,
                "Condition row count does not match sample_count")
        }
        val samples = ArrayList<ArKernelConditionSample>(sampleCount)
        for (index in 0 until sampleCount) {
            val lineNumber = COND_KEYS.size + 2 + index
            val rowText = lines[COND_KEYS.size + 1 + index].trimEnd('\r')
            val lineError = validateLine(rowText, lineNumber)
            if (lineError != null) return lineError
            if (!rowText.startsWith("sample=")) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_ROW,
                    lineNumber, "Condition sample row is missing")
            }
            val fields = rowText.substring("sample=".length).split('|')
            if (fields.size != COND_ROW_FIELDS) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_ROW,
                    lineNumber, "Condition sample must have ten fields")
            }
            val rowIndex = unsigned(fields[0])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineNumber, "Invalid condition sample index")
            if (rowIndex.toInt() != index) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_ORDER,
                    lineNumber, "Condition samples are not contiguous")
            }
            val condition = ArKernelMeasurementCondition
                .fromWire(fields[1])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_CONDITION,
                    lineNumber, "Invalid condition label")
            if (!COND_TOKEN.matches(fields[2])) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_TOKEN,
                    lineNumber, "Invalid condition rig id")
            }
            val rig = ArKernelRigKind.fromWire(fields[3])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_RIG,
                    lineNumber, "Invalid rig kind")
            val pose = unsigned(fields[4])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineNumber, "Invalid pose index")
            val repeat = unsigned(fields[5])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineNumber, "Invalid repeat index")
            val thermal = unsigned(fields[6])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineNumber, "Invalid thermal status")
            val headroom = unsigned(fields[7])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineNumber, "Invalid thermal headroom")
            val lux = unsigned(fields[8])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineNumber, "Invalid light level")
            val stamp = unsigned(fields[9])
                ?: return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineNumber, "Invalid timestamp")
            if (pose > COND_MAX_POSE.toLong() ||
                repeat > COND_MAX_REPEAT.toLong() ||
                thermal > COND_MAX_THERMAL.toLong() ||
                headroom > COND_MAX_HEADROOM.toLong() ||
                lux > COND_MAX_LUX_MILLI
            ) {
                return condReject(
                    ArKernelConditionEvidenceRejection.INVALID_VALUE,
                    lineNumber, "Condition sample value is out of bounds")
            }
            samples += ArKernelConditionSample(
                index = index,
                condition = condition,
                rigId = fields[2],
                rigKind = rig,
                poseIndex = pose.toInt(),
                repeatIndex = repeat.toInt(),
                thermalStatus = thermal.toInt(),
                headroomPermille = headroom.toInt(),
                luxMilli = lux,
                timestampMillis = stamp,
            )
        }
        val endLine = lines[lines.size - 1].trimEnd('\r')
        if (endLine != COND_END) {
            return condReject(
                ArKernelConditionEvidenceRejection.INVALID_SCHEMA,
                lines.size, "Condition ledger must end with END")
        }
        val result = create(
            ledgerId = values.getValue("ledger_id"),
            deviceToken = values.getValue("device_token"),
            campaignId = values.getValue("campaign_id"),
            requiredConditions = requiredConditions,
            minSamplesPerCondition = minSamples,
            maxThermalStatus = maxThermal,
            dayMinLuxMilli = luxThresholds.getValue("day_min_lux_milli"),
            nightMaxLuxMilli =
                luxThresholds.getValue("night_max_lux_milli"),
            samples = samples,
        )
        if (!result.accepted) {
            return condReject(
                result.rejection, COND_KEYS.size + 2, result.detail)
        }
        val ledger = result.ledger
            ?: return condReject(
                ArKernelConditionEvidenceRejection.INVALID_SCHEMA, 1,
                "Condition ledger could not be rebuilt")
        val expected = mapOf(
            "condition_count" to ledger.conditionCount.toLong(),
            "covered_conditions" to ledger.coveredConditions.toLong(),
            "day_count" to ledger.dayCount.toLong(),
            "night_count" to ledger.nightCount.toLong(),
            "rig_count" to ledger.rigCount.toLong(),
            "handheld_count" to ledger.handheldCount.toLong(),
            "repeat_count" to ledger.repeatCount.toLong(),
            "label_conflicts" to ledger.labelConflicts.toLong(),
            "min_lux_milli" to ledger.minLuxMilli,
            "max_lux_milli" to ledger.maxLuxMilli,
            "observed_max_thermal_status" to
                ledger.observedMaxThermalStatus.toLong(),
        )
        for ((key, value) in expected) {
            if (declared.getValue(key) != value) {
                return condReject(
                    ArKernelConditionEvidenceRejection
                        .STATISTICS_MISMATCH,
                    lineByKey.getValue(key),
                    "Recomputed $key disagrees with the declared value")
            }
        }
        val declaredVerdicts = mapOf(
            "day_night_covered" to ledger.dayNightCovered,
            "labels_coherent" to ledger.labelsCoherent,
            "thermal_stable" to ledger.thermalStable,
            "repeatability_documented" to ledger.repeatabilityDocumented,
            "evidence_sufficient" to ledger.evidenceSufficient,
        )
        for ((key, value) in declaredVerdicts) {
            if ((values.getValue(key) == "true") != value) {
                return condReject(
                    ArKernelConditionEvidenceRejection.EVIDENCE_MISMATCH,
                    lineByKey.getValue(key),
                    "Recomputed $key disagrees with the declared verdict")
            }
        }
        return ArKernelConditionParseResult(
            ledger = ledger,
            rejection = ArKernelConditionEvidenceRejection.NONE,
            lineNumber = 0,
            detail = "accepted",
        )
    }

    private fun sampleRow(sample: ArKernelConditionSample): String =
        "sample=" + listOf(
            sample.index.toString(),
            sample.condition.wireName(),
            sample.rigId,
            sample.rigKind.wireName(),
            sample.poseIndex.toString(),
            sample.repeatIndex.toString(),
            sample.thermalStatus.toString(),
            sample.headroomPermille.toString(),
            sample.luxMilli.toString(),
            sample.timestampMillis.toString(),
        ).joinToString("|")

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun unsigned(value: String): Long? =
        if (COND_UNSIGNED.matches(value)) value.toLongOrNull() else null

    private fun validateLine(
        line: String,
        lineNumber: Int,
    ): ArKernelConditionParseResult? {
        if (line.isEmpty()) {
            return condReject(
                ArKernelConditionEvidenceRejection.EMPTY_LINE,
                lineNumber, "Condition line is empty")
        }
        if (line.length > COND_MAX_LINE_CHARACTERS) {
            return condReject(
                ArKernelConditionEvidenceRejection.LINE_TOO_LONG,
                lineNumber, "Condition line is too long")
        }
        return null
    }

    private fun condReject(
        rejection: ArKernelConditionEvidenceRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelConditionParseResult =
        ArKernelConditionParseResult(
            ledger = null,
            rejection = rejection,
            lineNumber = lineNumber,
            detail = detail,
        )

    private fun createReject(
        rejection: ArKernelConditionEvidenceRejection,
        detail: String,
    ): ArKernelConditionResult =
        ArKernelConditionResult(
            ledger = null,
            rejection = rejection,
            detail = detail,
        )
}
