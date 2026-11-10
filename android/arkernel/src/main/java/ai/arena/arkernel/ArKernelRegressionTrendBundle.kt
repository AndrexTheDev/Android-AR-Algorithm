package ai.arena.arkernel

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private const val REGRESSION_HEADER = "ARKREGR1"
private const val REGRESSION_VERSION = "1"
private const val REGRESSION_QUALITY = "unverified"
private const val REGRESSION_END = "END"
private const val REGRESSION_MAX_INPUT_CHARACTERS = 8_192
private const val REGRESSION_MAX_LINE_CHARACTERS = 256
private const val REGRESSION_MIN_RUNS = 2
private const val REGRESSION_MAX_RUNS = 64
private const val REGRESSION_EPSILON = 1e-9

private val REGRESSION_TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")
private val REGRESSION_METRIC = Regex("[A-Za-z0-9._:-]{1,64}")
private val REGRESSION_UNSIGNED = Regex("0|[1-9][0-9]*")
private val REGRESSION_DECIMAL = Regex(
    """-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?""",
)

private val REGRESSION_KEYS = listOf(
    "version",
    "metric",
    "direction",
    "baseline",
    "budget_abs",
    "budget_rel",
    "min_samples",
    "quality_grade",
    "run_count",
)

/** Direction in which a changed scalar result counts as an improvement. */
enum class ArKernelRegressionTrendDirection {
    LOWER_IS_BETTER,
    HIGHER_IS_BETTER,
}

/** Bounded classification of a host-supplied scalar trend; never a quality grade. */
enum class ArKernelRegressionTrend {
    IMPROVING,
    STABLE,
    DEGRADING,
    BUDGET_VIOLATION,
}

enum class ArKernelRegressionTrendRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_LEDGER,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_ORDER,
    INVALID_OBSERVATION,
    DUPLICATE_RUN_ID,
    TOO_MANY_RUNS,
    INSUFFICIENT_SAMPLES,
    UNSAFE_INVARIANT,
}

/** Fail-closed parse result; on rejection no partially trusted ledger is returned. */
data class ArKernelRegressionTrendParseResult(
    val ledger: ArKernelRegressionTrendLedger?,
    val rejection: ArKernelRegressionTrendRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelRegressionTrendRejection.NONE && ledger != null
}

/** One bounded host-supplied scalar result. No trajectory, image or dataset bytes. */
data class ArKernelRegressionTrendRun(
    val index: Int,
    val runId: String,
    val value: Double,
    val uncertainty: Double,
) {
    init {
        require(index >= 0) { "Invalid regression run index" }
        require(REGRESSION_TOKEN.matches(runId)) { "Invalid regression run ID" }
        require(!value.isNaN() && !value.isInfinite()) {
            "Regression run value must be finite"
        }
        require(!uncertainty.isNaN() && !uncertainty.isInfinite() &&
            uncertainty >= 0.0) {
            "Regression run uncertainty must be finite and non-negative"
        }
    }
}

/**
 * Bounded, canonical ledger for host-supplied benchmark scalars.
 *
 * It performs no I/O, dataset loading, replay, trajectory evaluation, upload,
 * signature verification or device validation. A budget violation is a
 * reported structural fact, not an assessed accuracy or product-quality grade.
 */
data class ArKernelRegressionTrendLedger(
    val metric: String,
    val direction: ArKernelRegressionTrendDirection,
    val baseline: Double,
    val budgetAbsolute: Double,
    val budgetRelative: Double,
    val minSamples: Int,
    val runs: List<ArKernelRegressionTrendRun>,
    val qualityGrade: String = REGRESSION_QUALITY,
) {
    init {
        require(REGRESSION_METRIC.matches(metric)) { "Invalid regression metric" }
        require(!baseline.isNaN() && !baseline.isInfinite()) {
            "Regression baseline must be finite"
        }
        require(!budgetAbsolute.isNaN() && !budgetAbsolute.isInfinite() &&
            budgetAbsolute >= 0.0) {
            "Regression absolute budget must be finite and non-negative"
        }
        require(!budgetRelative.isNaN() && !budgetRelative.isInfinite() &&
            budgetRelative >= 0.0) {
            "Regression relative budget must be finite and non-negative"
        }
        require(minSamples in REGRESSION_MIN_RUNS..REGRESSION_MAX_RUNS) {
            "Regression minimum sample count is out of bounds"
        }
        require(runs.size in REGRESSION_MIN_RUNS..REGRESSION_MAX_RUNS) {
            "Regression run count is out of bounds"
        }
        require(runs.size >= minSamples) {
            "Regression ledger has fewer runs than its minimum sample count"
        }
        require(qualityGrade == REGRESSION_QUALITY) {
            "Regression ledger cannot assert assessed quality"
        }
        require(runs.map { it.runId }.distinct().size == runs.size) {
            "Regression run IDs must be unique"
        }
        require(runs.withIndex().all { it.value.index == it.index }) {
            "Regression run indices must be contiguous"
        }
    }
}

/** Deterministic scalar summary; [qualityGrade] remains permanently unverified. */
data class ArKernelRegressionTrendReport(
    val metric: String,
    val direction: ArKernelRegressionTrendDirection,
    val sampleCount: Int,
    val firstValue: Double,
    val lastValue: Double,
    val delta: Double,
    val degradation: Double,
    val improvement: Double,
    val relativeDegradation: Double,
    val budgetAbsolute: Double,
    val budgetRelative: Double,
    val noiseFloor: Double,
    val budgetPass: Boolean,
    val trend: ArKernelRegressionTrend,
    val qualityGrade: String = REGRESSION_QUALITY,
) {
    init {
        require(qualityGrade == REGRESSION_QUALITY) {
            "Regression trend report cannot assert assessed quality"
        }
    }
}

/**
 * Canonical `ARKREGR1` text adapter plus a pure trend evaluator.
 *
 * The text contract has a fixed metadata order, bounded input/line/run budgets,
 * strictly ordered run indices, unique opaque run IDs, finite decimal scalars,
 * BOM/CRLF tolerance with one optional final separator and an explicit `END`.
 * Unknown metrics are not rejected as such, but every metric, direction, budget
 * and quality claim is bounded and fail-closed. The evaluator accepts only
 * host-supplied scalars; it never creates MH_01/TUM-VI/KITTI evidence, never
 * runs a benchmark and never upgrades `unverified`.
 */
object ArKernelRegressionTrendBundle {

    fun encode(ledger: ArKernelRegressionTrendLedger): String {
        val lines = ArrayList<String>(REGRESSION_KEYS.size + ledger.runs.size + 2)
        lines.add(REGRESSION_HEADER)
        lines.add("version=$REGRESSION_VERSION")
        lines.add("metric=${ledger.metric}")
        lines.add("direction=${ledger.direction.name}")
        lines.add("baseline=${fmt(ledger.baseline)}")
        lines.add("budget_abs=${fmt(ledger.budgetAbsolute)}")
        lines.add("budget_rel=${fmt(ledger.budgetRelative)}")
        lines.add("min_samples=${ledger.minSamples}")
        lines.add("quality_grade=${ledger.qualityGrade}")
        lines.add("run_count=${ledger.runs.size}")
        for (run in ledger.runs) {
            lines.add(
                "run=${run.index}|${run.runId}|${fmt(run.value)}|" +
                    fmt(run.uncertainty),
            )
        }
        lines.add(REGRESSION_END)
        val text = lines.joinToString("\n")
        require(text.length <= REGRESSION_MAX_INPUT_CHARACTERS) {
            "Regression ledger exceeds its bounded size"
        }
        require(lines.all { it.length <= REGRESSION_MAX_LINE_CHARACTERS }) {
            "Regression ledger line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelRegressionTrendParseResult {
        if (text.length > REGRESSION_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelRegressionTrendRejection.INPUT_TOO_LARGE, 0,
                "Regression ledger input exceeds its bounded size")
        }
        val normalized = if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelRegressionTrendRejection.EMPTY_LEDGER, 0,
                "Regression ledger input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelRegressionTrendRejection.EMPTY_LEDGER, 0,
                "Regression ledger input contains no ledger")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != REGRESSION_HEADER || header.any { it.isWhitespace() } ||
            header.length > REGRESSION_MAX_LINE_CHARACTERS
        ) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_HEADER, 1,
                "Regression ledger header is invalid")
        }
        if (lines.size < REGRESSION_KEYS.size + 3) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_SCHEMA, 0,
                "Regression ledger is too short")
        }
        val lineByKey = REGRESSION_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(REGRESSION_KEYS.size)
        for (index in REGRESSION_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelRegressionTrendRejection.INVALID_SCHEMA,
                    lineNumber, "Regression ledger metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != REGRESSION_KEYS[index]) {
                return reject(
                    ArKernelRegressionTrendRejection.INVALID_SCHEMA,
                    lineNumber, "Regression ledger metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != REGRESSION_VERSION) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported regression ledger version")
        }
        if (values.getValue("quality_grade") != REGRESSION_QUALITY) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Regression ledger cannot assert assessed quality")
        }
        val metric = values.getValue("metric")
        if (!REGRESSION_METRIC.matches(metric)) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_TOKEN,
                lineByKey.getValue("metric"), "Invalid regression metric token")
        }
        val direction = ArKernelRegressionTrendDirection.entries.firstOrNull {
            it.name == values.getValue("direction")
        } ?: return reject(
            ArKernelRegressionTrendRejection.INVALID_VALUE,
            lineByKey.getValue("direction"), "Invalid regression direction")
        val baseline = decimal(values.getValue("baseline"))
            ?: return reject(
                ArKernelRegressionTrendRejection.INVALID_VALUE,
                lineByKey.getValue("baseline"), "Invalid regression baseline")
        val budgetAbsolute = nonNegativeDecimal(values.getValue("budget_abs"))
            ?: return reject(
                ArKernelRegressionTrendRejection.INVALID_VALUE,
                lineByKey.getValue("budget_abs"),
                "Invalid regression absolute budget")
        val budgetRelative = nonNegativeDecimal(values.getValue("budget_rel"))
            ?: return reject(
                ArKernelRegressionTrendRejection.INVALID_VALUE,
                lineByKey.getValue("budget_rel"),
                "Invalid regression relative budget")
        val minSamples = unsignedInt(values.getValue("min_samples"))
            ?: return reject(
                ArKernelRegressionTrendRejection.INVALID_VALUE,
                lineByKey.getValue("min_samples"),
                "Invalid regression minimum sample count")
        if (minSamples !in REGRESSION_MIN_RUNS..REGRESSION_MAX_RUNS) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_VALUE,
                lineByKey.getValue("min_samples"),
                "Regression minimum sample count is out of bounds")
        }
        val runCount = unsignedInt(values.getValue("run_count"))
            ?: return reject(
                ArKernelRegressionTrendRejection.INVALID_VALUE,
                lineByKey.getValue("run_count"), "Invalid regression run count")
        if (runCount > REGRESSION_MAX_RUNS) {
            return reject(
                ArKernelRegressionTrendRejection.TOO_MANY_RUNS,
                lineByKey.getValue("run_count"),
                "Regression run count exceeds its bounded size")
        }
        if (runCount < REGRESSION_MIN_RUNS) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_VALUE,
                lineByKey.getValue("run_count"),
                "Regression run count is below its bounded size")
        }
        if (minSamples > runCount) {
            return reject(
                ArKernelRegressionTrendRejection.INSUFFICIENT_SAMPLES,
                lineByKey.getValue("min_samples"),
                "Regression ledger has fewer runs than its minimum sample count")
        }
        val runStartIndex = REGRESSION_KEYS.size + 1
        val endIndex = runStartIndex + runCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_SCHEMA, 0,
                "Regression ledger line count does not match run count")
        }
        val runs = ArrayList<ArKernelRegressionTrendRun>(runCount)
        val runIds = HashSet<String>(runCount)
        for (index in 0 until runCount) {
            val lineNumber = runStartIndex + index + 1
            val lineText = lines[runStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("run=")) {
                return reject(
                    ArKernelRegressionTrendRejection.INVALID_OBSERVATION,
                    lineNumber, "Regression ledger line is not a run")
            }
            val fields = lineText.substringAfter("run=").split('|')
            if (fields.size != 4) {
                return reject(
                    ArKernelRegressionTrendRejection.INVALID_OBSERVATION,
                    lineNumber, "Regression run must have four fields")
            }
            val runIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelRegressionTrendRejection.INVALID_ORDER,
                    lineNumber, "Invalid regression run index")
            if (runIndex != index) {
                return reject(
                    ArKernelRegressionTrendRejection.INVALID_ORDER,
                    lineNumber, "Regression run indices must be contiguous")
            }
            val runId = fields[1]
            if (!REGRESSION_TOKEN.matches(runId)) {
                return reject(
                    ArKernelRegressionTrendRejection.INVALID_TOKEN,
                    lineNumber, "Invalid regression run ID")
            }
            if (!runIds.add(runId)) {
                return reject(
                    ArKernelRegressionTrendRejection.DUPLICATE_RUN_ID,
                    lineNumber, "Regression run ID is reused")
            }
            val value = decimal(fields[2])
                ?: return reject(
                    ArKernelRegressionTrendRejection.INVALID_OBSERVATION,
                    lineNumber, "Invalid regression run value")
            val uncertainty = nonNegativeDecimal(fields[3])
                ?: return reject(
                    ArKernelRegressionTrendRejection.INVALID_OBSERVATION,
                    lineNumber, "Invalid regression run uncertainty")
            runs += try {
                ArKernelRegressionTrendRun(runIndex, runId, value, uncertainty)
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelRegressionTrendRejection.INVALID_OBSERVATION,
                    lineNumber, error.message ?: "Invalid regression run")
            }
        }
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validateLine(endText, endIndex + 1)
        if (endRejection != null) return endRejection
        if (endText != REGRESSION_END) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_SCHEMA,
                endIndex + 1, "Regression ledger must end with END")
        }
        val ledger = try {
            ArKernelRegressionTrendLedger(
                metric,
                direction,
                baseline,
                budgetAbsolute,
                budgetRelative,
                minSamples,
                runs,
                REGRESSION_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelRegressionTrendRejection.UNSAFE_INVARIANT, 0,
                error.message ?: "Invalid regression ledger")
        }
        return ArKernelRegressionTrendParseResult(
            ledger,
            ArKernelRegressionTrendRejection.NONE,
            0,
            "",
        )
    }

    /** Pure structural trend classification; no physical or dataset evidence. */
    fun evaluate(
        ledger: ArKernelRegressionTrendLedger,
    ): ArKernelRegressionTrendReport {
        require(ledger.qualityGrade == REGRESSION_QUALITY) {
            "Regression trend report cannot assert assessed quality"
        }
        val first = ledger.runs.first().value
        val last = ledger.runs.last().value
        val delta = last - first
        val degradation = when (ledger.direction) {
            ArKernelRegressionTrendDirection.LOWER_IS_BETTER ->
                max(0.0, delta)
            ArKernelRegressionTrendDirection.HIGHER_IS_BETTER ->
                max(0.0, -delta)
        }
        val improvement = when (ledger.direction) {
            ArKernelRegressionTrendDirection.LOWER_IS_BETTER ->
                max(0.0, -delta)
            ArKernelRegressionTrendDirection.HIGHER_IS_BETTER ->
                max(0.0, delta)
        }
        val denominator = max(abs(ledger.baseline), REGRESSION_EPSILON)
        val relativeDegradation = degradation / denominator
        val budgetPass =
            degradation <= ledger.budgetAbsolute + REGRESSION_EPSILON &&
                relativeDegradation <= ledger.budgetRelative + REGRESSION_EPSILON
        val noiseFloor = max(
            ledger.runs.maxOf { it.uncertainty },
            REGRESSION_EPSILON,
        )
        val trend = when {
            !budgetPass -> ArKernelRegressionTrend.BUDGET_VIOLATION
            degradation > noiseFloor + REGRESSION_EPSILON ->
                ArKernelRegressionTrend.DEGRADING
            improvement > noiseFloor + REGRESSION_EPSILON ->
                ArKernelRegressionTrend.IMPROVING
            else -> ArKernelRegressionTrend.STABLE
        }
        return ArKernelRegressionTrendReport(
            metric = ledger.metric,
            direction = ledger.direction,
            sampleCount = ledger.runs.size,
            firstValue = first,
            lastValue = last,
            delta = delta,
            degradation = degradation,
            improvement = improvement,
            relativeDegradation = relativeDegradation,
            budgetAbsolute = ledger.budgetAbsolute,
            budgetRelative = ledger.budgetRelative,
            noiseFloor = noiseFloor,
            budgetPass = budgetPass,
            trend = trend,
        )
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelRegressionTrendParseResult? {
        if (lineText.length > REGRESSION_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelRegressionTrendRejection.LINE_TOO_LONG,
                lineNumber, "Regression ledger line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelRegressionTrendRejection.EMPTY_LINE,
                lineNumber, "Regression ledger contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelRegressionTrendRejection.INVALID_SCHEMA,
                lineNumber, "Regression ledger lines must not contain whitespace")
        }
        return null
    }

    private fun fmt(value: Double): String {
        require(!value.isNaN() && !value.isInfinite()) {
            "Regression decimal must be finite"
        }
        val formatted = String.format(Locale.US, "%.6f", value)
        return if (formatted == "-0.000000") "0.000000" else formatted
    }

    private fun decimal(value: String): Double? {
        if (!REGRESSION_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        return if (!parsed.isNaN() && !parsed.isInfinite()) parsed else null
    }

    private fun nonNegativeDecimal(value: String): Double? {
        val parsed = decimal(value) ?: return null
        return if (parsed >= 0.0) parsed else null
    }

    private fun unsignedInt(value: String): Int? =
        if (REGRESSION_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun reject(
        rejection: ArKernelRegressionTrendRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelRegressionTrendParseResult =
        ArKernelRegressionTrendParseResult(null, rejection, lineNumber, detail)
}
