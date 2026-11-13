package ai.arena.arkernel

/**
 * The metric scale ladder: does one scale factor survive a change of distance?
 *
 * The kernel carries a metric scale. Whether a *single* number can correct it
 * is a question this contract answers, and it is the question that decides
 * whether a scale factor in the kernel is enough at all.
 *
 * **What is measured.** A folding ruler is held at a series of distances from
 * the camera. At each distance the app detects the ruler's centimetre ticks
 * and derives how many image pixels one metre spans at that distance. The
 * ruler's own tick spacing is the ground truth - it is a real, traceable
 * standard, so no separate reference object and no stated length is needed.
 * Only the *distance* comes from the operator's tape measure.
 *
 * **Why this works without intrinsics.** A pinhole camera at distance d maps
 * one metre onto f/d pixels, so pixels-per-metre multiplied by distance is
 * the focal length in pixels, and that product should be the *same number at
 * every rung*. If it is, the scale obeys the model and one factor suffices.
 * If the product drifts with distance, the scale error grows with distance and
 * no single factor can fix it. No focal length has to be known beforehand to
 * test this - the test is whether the implied one holds still.
 *
 * **What it does not claim.** The absolute value of the implied focal length
 * is *not* a measured intrinsic. It carries the biases listed below, and the
 * contract reports it as `unverified` like everything else. What the ladder
 * measures is the **trend across rungs**, which cancels most of those biases
 * because they act alike at every distance:
 *
 * - the ruler is never perfectly fronto-parallel, so it foreshortens;
 * - lens distortion grows away from the optical axis, and the ruler's ends
 *   are off-axis at close range;
 * - the tape measures to whatever the operator thinks the ruler plane is;
 * - the ticks are detected along a hand-placed line, not a calibrated one.
 *
 * No frame is retained: each frame is reduced in memory to a one-dimensional
 * luminance profile along the marked line and dropped.
 *
 * All statistics are integer arithmetic in thousandths, so the parser can
 * recompute them and reject any file whose numbers do not match.
 */
object ArKernelScaleLadderAdapter {

    fun encode(record: ArKernelScaleLadder): String {
        val lines = ArrayList<String>(
            LADDER_KEYS.size + record.rungs.size + 2)
        lines.add(LADDER_HEADER)
        lines.add("version=$LADDER_VERSION")
        lines.add("run_id=${record.runId}")
        lines.add("device_token=${record.deviceToken}")
        lines.add("campaign_id=${record.campaignId}")
        lines.add("rung_count=${record.rungCount}")
        lines.add("distance_unit=$LADDER_DISTANCE_UNIT")
        lines.add("ground_truth_source=$LADDER_GROUND_TRUTH_SOURCE")
        lines.add("tick_unit=$LADDER_TICK_UNIT")
        // Written out in full because the order has to match LADDER_KEYS.
        lines.add("ground_truth_manual=true")
        lines.add("automatic_calibration_disabled=true")
        lines.add("automatic_upload_disabled=true")
        lines.add("signature_verification_disabled=true")
        lines.add("universal_calibration_disabled=true")
        lines.add("per_device_calibration=true")
        lines.add("manual_upload_only=true")
        lines.add("images_disabled=true")
        lines.add("no_frames_retained=true")
        lines.add("intrinsics_fitted=false")
        lines.add("metric_accuracy_claimed=false")
        lines.add("quality_grade=$LADDER_QUALITY")
        lines.add("focal_px_mean=${fixed(record.focalPxMean)}")
        lines.add("focal_px_min=${fixed(record.focalPxMin)}")
        lines.add("focal_px_max=${fixed(record.focalPxMax)}")
        lines.add("spread_permille=${record.spreadPermille}")
        lines.add("trend_permille_per_m=${record.trendPermillePerM}")
        lines.add("trend_correlation_permille=${record.trendCorrelationPermille}")
        lines.add("residual_max_permille=${record.residualMaxPermille}")
        lines.add("rungs_ok=${record.rungsOk}")
        lines.add("trend_ok=${record.trendOk}")
        lines.add("spread_ok=${record.spreadOk}")
        lines.add("scale_sufficient=${record.scaleSufficient}")
        for (rung in record.rungs) {
            lines.add(
                "rung=${rung.index},${rung.distanceMm}," +
                    "${rung.scaleMilliPxPerM},${rung.tickCount}," +
                    "${rung.regularityPermille}")
        }
        lines.add(LADDER_END)
        return lines.joinToString("\n")
    }

    fun parse(text: String): ArKernelScaleLadderParseResult {
        val size = text.length
        if (size > LADDER_MAX_INPUT_CHARACTERS) {
            return rejected(ArKernelScaleLadderRejection.INPUT_TOO_LARGE,
                "Scale ladder input is too large")
        }
        if (text.isEmpty()) {
            return rejected(ArKernelScaleLadderRejection.EMPTY_RECORD,
                "Scale ladder record is empty")
        }
        val lines = text.split("\n")
        for ((offset, line) in lines.withIndex()) {
            if (line.length > LADDER_MAX_LINE_CHARACTERS) {
                return rejected(ArKernelScaleLadderRejection.LINE_TOO_LONG,
                    "Scale ladder line ${offset + 1} is too long")
            }
        }
        if (lines[0] != LADDER_HEADER) {
            return rejected(ArKernelScaleLadderRejection.INVALID_HEADER,
                "Scale ladder header is wrong")
        }
        if (lines.size < LADDER_KEYS.size + 3) {
            return rejected(ArKernelScaleLadderRejection.INVALID_SCHEMA,
                "Scale ladder record is too short")
        }
        if (lines[lines.size - 1] != LADDER_END) {
            return rejected(ArKernelScaleLadderRejection.INVALID_SCHEMA,
                "Scale ladder terminator is missing")
        }
        val metadataEnd = LADDER_KEYS.size + 1
        for (index in 0 until LADDER_KEYS.size) {
            val line = lines[index + 1]
            val separator = line.indexOf('=')
            if (separator <= 0) {
                return rejected(ArKernelScaleLadderRejection.INVALID_SCHEMA,
                    "Scale ladder metadata key order is wrong")
            }
            val key = line.substring(0, separator)
            if (key != LADDER_KEYS[index]) {
                return rejected(ArKernelScaleLadderRejection.INVALID_SCHEMA,
                    "Scale ladder metadata key order is wrong")
            }
        }
        val values = HashMap<String, String>()
        for (index in 1 until metadataEnd) {
            val line = lines[index]
            val separator = line.indexOf('=')
            values[line.substring(0, separator)] =
                line.substring(separator + 1)
        }
        for (key in LADDER_TRUE_KEYS) {
            if (values[key] != "true") {
                return rejected(ArKernelScaleLadderRejection.UNSAFE_INVARIANT,
                    "Scale ladder invariant $key must be true")
            }
        }
        for (key in LADDER_FALSE_KEYS) {
            if (values[key] != "false") {
                return rejected(ArKernelScaleLadderRejection.UNSAFE_INVARIANT,
                    "Scale ladder invariant $key must be false")
            }
        }
        if (values["quality_grade"] != LADDER_QUALITY) {
            return rejected(ArKernelScaleLadderRejection.INVALID_QUALITY,
                "Scale ladder grade must stay $LADDER_QUALITY")
        }
        for (key in LADDER_STATIC_TEXT) {
            if (values[key.key] != key.value) {
                return rejected(ArKernelScaleLadderRejection.INVALID_VALUE,
                    "Scale ladder ${key.key} is fixed")
            }
        }
        if (values["version"] != LADDER_VERSION) {
            return rejected(ArKernelScaleLadderRejection.INVALID_VALUE,
                "Scale ladder version is unsupported")
        }
        val runId = values["run_id"] ?: return rejected(
            ArKernelScaleLadderRejection.INVALID_SCHEMA, "missing run_id")
        if (!LADDER_TOKEN.matches(runId)) {
            return rejected(ArKernelScaleLadderRejection.INVALID_TOKEN,
                "Scale ladder run id is not a bounded token")
        }
        val deviceToken = values["device_token"] ?: return rejected(
            ArKernelScaleLadderRejection.INVALID_SCHEMA, "missing device_token")
        if (!LADDER_TOKEN.matches(deviceToken)) {
            return rejected(ArKernelScaleLadderRejection.INVALID_TOKEN,
                "Scale ladder device token is not a bounded token")
        }
        val campaignId = values["campaign_id"] ?: return rejected(
            ArKernelScaleLadderRejection.INVALID_SCHEMA, "missing campaign_id")
        if (!LADDER_TOKEN.matches(campaignId)) {
            return rejected(ArKernelScaleLadderRejection.INVALID_TOKEN,
                "Scale ladder campaign id is not a bounded token")
        }
        val rungCountText = values["rung_count"] ?: return rejected(
            ArKernelScaleLadderRejection.INVALID_SCHEMA, "missing rung_count")
        if (!LADDER_UNSIGNED.matches(rungCountText)) {
            return rejected(ArKernelScaleLadderRejection.INVALID_VALUE,
                "Scale ladder rung count is not an unsigned integer")
        }
        val declaredRungCount = rungCountText.toLongOrNull() ?: return rejected(
            ArKernelScaleLadderRejection.INVALID_VALUE,
            "Scale ladder rung count is out of range")
        if (declaredRungCount > LADDER_MAX_RUNGS) {
            return rejected(ArKernelScaleLadderRejection.INVALID_COUNT,
                "Scale ladder rung count is too large")
        }

        val rungs = ArrayList<ArKernelScaleLadderRung>()
        var cursor = metadataEnd
        while (cursor < lines.size - 1) {
            val line = lines[cursor]
            if (!line.startsWith("rung=")) {
                return rejected(ArKernelScaleLadderRejection.INVALID_SCHEMA,
                    "Scale ladder row ${cursor + 1} is not a rung")
            }
            val fields = line.substring("rung=".length).split(",")
            if (fields.size != LADDER_ROW_FIELDS) {
                return rejected(ArKernelScaleLadderRejection.INVALID_ROW,
                    "Scale ladder row ${cursor + 1} has the wrong field count")
            }
            if (!LADDER_UNSIGNED.matches(fields[0]) ||
                !LADDER_UNSIGNED.matches(fields[1]) ||
                !LADDER_UNSIGNED.matches(fields[2]) ||
                !LADDER_UNSIGNED.matches(fields[3]) ||
                !LADDER_UNSIGNED.matches(fields[4])
            ) {
                return rejected(ArKernelScaleLadderRejection.INVALID_ROW,
                    "Scale ladder row ${cursor + 1} has a non-numeric field")
            }
            val values5 = LongArray(5)
            for (index in 0..4) {
                values5[index] = fields[index].toLongOrNull() ?: return rejected(
                    ArKernelScaleLadderRejection.INVALID_ROW,
                    "Scale ladder row ${cursor + 1} is out of long range")
            }
            if (values5[0] != rungs.size.toLong()) {
                return rejected(ArKernelScaleLadderRejection.INVALID_ORDER,
                    "Scale ladder rung indices must be contiguous")
            }
            if (values5[1] < LADDER_MIN_DISTANCE_MM ||
                values5[1] > LADDER_MAX_DISTANCE_MM
            ) {
                return rejected(ArKernelScaleLadderRejection.DISTANCE_OUT_OF_RANGE,
                    "Scale ladder distance is out of range")
            }
            if (values5[2] < LADDER_MIN_SCALE_MILLI ||
                values5[2] > LADDER_MAX_SCALE_MILLI
            ) {
                return rejected(ArKernelScaleLadderRejection.SCALE_OUT_OF_RANGE,
                    "Scale ladder pixels-per-metre is out of range")
            }
            if (values5[3] < LADDER_MIN_TICKS || values5[3] > LADDER_MAX_TICKS) {
                return rejected(ArKernelScaleLadderRejection.INVALID_ROW,
                    "Scale ladder tick count is out of range")
            }
            if (values5[4] > LADDER_MAX_PERMILLE) {
                return rejected(ArKernelScaleLadderRejection.INVALID_ROW,
                    "Scale ladder regularity is out of range")
            }
            if (rungs.isNotEmpty() &&
                values5[1] <= rungs[rungs.size - 1].distanceMm
            ) {
                return rejected(ArKernelScaleLadderRejection.INVALID_ORDER,
                    "Scale ladder distances must strictly increase")
            }
            rungs.add(
                ArKernelScaleLadderRung(
                    index = values5[0].toInt(),
                    distanceMm = values5[1],
                    scaleMilliPxPerM = values5[2],
                    tickCount = values5[3].toInt(),
                    regularityPermille = values5[4].toInt(),
                ))
            cursor++
        }
        if (rungs.size.toLong() != declaredRungCount) {
            return rejected(ArKernelScaleLadderRejection.INVALID_COUNT,
                "Scale ladder rung count does not match the rows")
        }
        val record = ArKernelScaleLadder(
            runId = runId,
            deviceToken = deviceToken,
            campaignId = campaignId,
            rungs = rungs,
        )
        if (fixed(record.focalPxMean) != values["focal_px_mean"]) {
            return rejected(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
                "Scale ladder mean focal length does not match the rungs")
        }
        if (fixed(record.focalPxMin) != values["focal_px_min"]) {
            return rejected(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
                "Scale ladder minimum focal length does not match the rungs")
        }
        if (fixed(record.focalPxMax) != values["focal_px_max"]) {
            return rejected(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
                "Scale ladder maximum focal length does not match the rungs")
        }
        if (record.spreadPermille.toString() != values["spread_permille"]) {
            return rejected(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
                "Scale ladder spread does not match the rungs")
        }
        if (record.trendPermillePerM.toString() !=
            values["trend_permille_per_m"]
        ) {
            return rejected(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
                "Scale ladder trend does not match the rungs")
        }
        if (record.trendCorrelationPermille.toString() !=
            values["trend_correlation_permille"]
        ) {
            return rejected(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
                "Scale ladder trend correlation does not match the rungs")
        }
        if (record.residualMaxPermille.toString() !=
            values["residual_max_permille"]
        ) {
            return rejected(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
                "Scale ladder residual does not match the rungs")
        }
        if (record.rungsOk.toString() != values["rungs_ok"] ||
            record.trendOk.toString() != values["trend_ok"] ||
            record.spreadOk.toString() != values["spread_ok"] ||
            record.scaleSufficient.toString() != values["scale_sufficient"]
        ) {
            return rejected(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
                "Scale ladder verdicts do not match the rungs")
        }
        return ArKernelScaleLadderParseResult(record,
            ArKernelScaleLadderRejection.NONE, "", 0)
    }

    /** Bounded structural summary; never a quality or accuracy grade. */
    fun report(record: ArKernelScaleLadder): ArKernelScaleLadderReport =
        ArKernelScaleLadderReport(
            rungCount = record.rungCount,
            focalPxMean = record.focalPxMean,
            trendPermillePerM = record.trendPermillePerM,
            scaleSufficient = record.scaleSufficient,
            qualityGrade = LADDER_QUALITY,
        )

    private fun rejected(
        rejection: ArKernelScaleLadderRejection,
        detail: String,
    ): ArKernelScaleLadderParseResult =
        ArKernelScaleLadderParseResult(null, rejection, detail, 0)

    /** Three decimals, so the parser can compare the text it was given. */
    private fun fixed(value: Double): String =
        ((value * 1_000.0).toLong() / 1_000.0).let {
            val whole = it.toLong()
            val fraction = kotlin.math.abs(
                ((it - whole) * 1_000.0).toLong() % 1_000L)
            "$whole.${fraction.toString().padStart(3, '0')}"
        }
}

enum class ArKernelScaleLadderRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_RECORD,
    LINE_TOO_LONG,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_COUNT,
    INVALID_ROW,
    INVALID_ORDER,
    DISTANCE_OUT_OF_RANGE,
    SCALE_OUT_OF_RANGE,
    STATISTICS_MISMATCH,
}

/** One rung: pixels per metre at one operator-measured distance. */
data class ArKernelScaleLadderRung(
    val index: Int,
    val distanceMm: Long,
    /** Pixels per metre in thousandths, i.e. px/m x 1000. */
    val scaleMilliPxPerM: Long,
    val tickCount: Int,
    /** How evenly the ticks were spaced; a structure check, not accuracy. */
    val regularityPermille: Int,
) {

    /** f = (px/m) x d. Kept in thousandths so no rounding creeps in. */
    val impliedFocalMilliPx: Long
        get() = scaleMilliPxPerM * distanceMm / LADDER_MILLI

    val impliedFocalPx: Double
        get() = impliedFocalMilliPx / 1_000.0
}

/**
 * A whole ladder. Every verdict is recomputed from the rungs, never stored.
 */
data class ArKernelScaleLadder(
    val runId: String,
    val deviceToken: String,
    val campaignId: String,
    val rungs: List<ArKernelScaleLadderRung>,
) {

    val rungCount: Int get() = rungs.size

    /** Short-ranged division, so a mean of a small set cannot overflow. */
    private fun divide(numerator: Long, denominator: Long): Long =
        if (denominator == 0L) 0L else numerator / denominator

    private val focal: LongArray
        get() = LongArray(rungs.size) { rungs[it].impliedFocalMilliPx }

    private val meanDistanceMm: Long
        get() = divide(
            rungs.fold(0L) { sum, rung -> sum + rung.distanceMm },
            rungs.size.toLong())

    val focalPxMean: Double
        get() = if (rungs.isEmpty()) 0.0 else
            divide(
                rungs.fold(0L) { sum, rung -> sum + rung.impliedFocalMilliPx },
                rungs.size.toLong()) / 1_000.0

    val focalPxMin: Double
        get() = if (rungs.isEmpty()) 0.0 else focal.minOrNull()!! / 1_000.0

    val focalPxMax: Double
        get() = if (rungs.isEmpty()) 0.0 else focal.maxOrNull()!! / 1_000.0

    private val focalMeanMilli: Long
        get() = if (rungs.isEmpty()) 0L else
            divide(
                rungs.fold(0L) { sum, rung -> sum + rung.impliedFocalMilliPx },
                rungs.size.toLong())

    /** Peak-to-peak spread of the implied focal length, in thousandths. */
    val spreadPermille: Long
        get() {
            if (rungs.size < 2) return 0L
            val mean = focalMeanMilli
            if (mean == 0L) return 0L
            val low = focal.minOrNull()!!
            val high = focal.maxOrNull()!!
            return (high - low) * 1_000L / mean
        }

    /**
     * Least-squares slope of the implied focal length against distance,
     * expressed as a change in thousandths per metre of distance.
     *
     * This is the number the whole ladder exists to produce. Near zero means
     * the scale obeys the pinhole model across the range and one factor
     * suffices; large means the scale error grows with distance and no
     * single factor can correct it.
     */
    val trendPermillePerM: Long
        get() {
            if (rungs.size < 3) return 0L
            val meanDistance = meanDistanceMm
            val meanFocal = focalMeanMilli
            var numerator = 0L
            var denominator = 0L
            for (rung in rungs) {
                val distanceDelta = rung.distanceMm - meanDistance
                val focalDelta = rung.impliedFocalMilliPx - meanFocal
                numerator += distanceDelta * focalDelta
                denominator += distanceDelta * distanceDelta
            }
            if (denominator == 0L || meanFocal == 0L) return 0L
            // (px per mm) / px  ->  per mm; x1000 -> per metre; x1000 -> permille
            return numerator * 1_000_000L / (denominator * meanFocal)
        }

    /** How straight the focal-length trend is; a weak trend is not a trend. */
    val trendCorrelationPermille: Int
        get() {
            if (rungs.size < 3) return 0
            val meanDistance = meanDistanceMm
            val meanFocal = focalMeanMilli
            var covariance = 0.0
            var distanceVariance = 0.0
            var focalVariance = 0.0
            for (rung in rungs) {
                val distanceDelta =
                    (rung.distanceMm - meanDistance).toDouble()
                val focalDelta =
                    (rung.impliedFocalMilliPx - meanFocal).toDouble()
                covariance += distanceDelta * focalDelta
                distanceVariance += distanceDelta * distanceDelta
                focalVariance += focalDelta * focalDelta
            }
            if (distanceVariance <= 0.0 || focalVariance <= 0.0) return 0
            val r = covariance /
                kotlin.math.sqrt(distanceVariance * focalVariance)
            return (r * 1_000.0).toLong().coerceIn(-1_000L, 1_000L).toInt()
        }

    /** Largest gap between a rung and the fitted line, in thousandths. */
    val residualMaxPermille: Long
        get() {
            if (rungs.size < 3) return 0L
            val meanDistance = meanDistanceMm
            val meanFocal = focalMeanMilli
            var numerator = 0L
            var denominator = 0L
            for (rung in rungs) {
                val distanceDelta = rung.distanceMm - meanDistance
                val focalDelta = rung.impliedFocalMilliPx - meanFocal
                numerator += distanceDelta * focalDelta
                denominator += distanceDelta * distanceDelta
            }
            if (denominator == 0L || meanFocal == 0L) return 0L
            var worst = 0L
            for (rung in rungs) {
                val fitted = meanFocal + divide(
                    numerator * (rung.distanceMm - meanDistance), denominator)
                val gap = kotlin.math.abs(rung.impliedFocalMilliPx - fitted)
                if (gap > worst) worst = gap
            }
            return worst * 1_000L / meanFocal
        }

    val rungsOk: Boolean
        get() = rungCount >= LADDER_MIN_RUNGS

    val trendOk: Boolean
        get() = rungsOk &&
            kotlin.math.abs(trendPermillePerM) <= LADDER_TREND_LIMIT_PERMILLE_PER_M

    val spreadOk: Boolean
        get() = rungsOk && spreadPermille <= LADDER_SPREAD_LIMIT_PERMILLE

    /**
     * A precondition gate, not a quality grade. It says the scale behaves
     * consistently enough over the range for one factor to be a meaningful
     * correction - not that the correction is any good.
     */
    val scaleSufficient: Boolean
        get() = rungsOk && trendOk && spreadOk

    /**
     * German-facing tokens naming what a failed ladder still needs.
     *
     * Too few rungs short-circuits the rest. With two rungs the trend and the
     * spread are not *failing*, they are *unmeasurable*, and reporting them
     * alongside would send the operator to fix a drift that cannot yet be
     * computed. Only an actionable step is named.
     */
    fun outstandingSteps(): List<String> {
        if (!rungsOk) return listOf("rungs")
        val steps = ArrayList<String>()
        if (!trendOk) steps.add("trend")
        if (!spreadOk) steps.add("spread")
        return steps
    }
}

data class ArKernelScaleLadderReport(
    val rungCount: Int,
    val focalPxMean: Double,
    val trendPermillePerM: Long,
    val scaleSufficient: Boolean,
    val qualityGrade: String = LADDER_QUALITY,
)

data class ArKernelScaleLadderParseResult(
    val record: ArKernelScaleLadder?,
    val rejection: ArKernelScaleLadderRejection,
    val detail: String,
    val lineNumber: Int,
) {
    val accepted: Boolean
        get() = record != null &&
            rejection == ArKernelScaleLadderRejection.NONE
}

private const val LADDER_HEADER = "ARKLADDER1"
private const val LADDER_VERSION = "1"
private const val LADDER_QUALITY = "unverified"
private const val LADDER_END = "END"
private const val LADDER_MAX_INPUT_CHARACTERS = 16_384
private const val LADDER_MAX_LINE_CHARACTERS = 256
private const val LADDER_ROW_FIELDS = 5
private const val LADDER_MILLI = 1_000L

private const val LADDER_DISTANCE_UNIT = "mm"
private const val LADDER_GROUND_TRUTH_SOURCE = "operator_tape_measure"
private const val LADDER_TICK_UNIT = "cm"

/** A ladder of fewer than three rungs cannot distinguish a trend from noise. */
const val LADDER_MIN_RUNGS = 3

const val LADDER_MAX_RUNGS = 16

const val LADDER_MIN_DISTANCE_MM = 100L

const val LADDER_MAX_DISTANCE_MM = 50_000L

const val LADDER_MIN_SCALE_MILLI = 1L

const val LADDER_MAX_SCALE_MILLI = 100_000_000L

const val LADDER_MIN_TICKS = 3L

const val LADDER_MAX_TICKS = 2_048L

const val LADDER_MAX_PERMILLE = 1_000L

/**
 * How far the implied focal length may drift per metre of distance before the
 * scale is called distance-dependent. Ten per mille per metre means the
 * correction would be off by about one per cent for every metre the subject
 * moves away - enough that a single factor stops being meaningful.
 */
const val LADDER_TREND_LIMIT_PERMILLE_PER_M = 10L

/**
 * How far the rungs may scatter around their own mean. This is looser than the
 * trend limit on purpose: scatter is what a bad rung looks like, drift is what
 * a wrong model looks like, and they are not the same failure.
 */
const val LADDER_SPREAD_LIMIT_PERMILLE = 50L

private val LADDER_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val LADDER_UNSIGNED = Regex("0|[1-9][0-9]*")

private val LADDER_TRUE_KEYS = listOf(
    "ground_truth_manual",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "universal_calibration_disabled",
    "per_device_calibration",
    "manual_upload_only",
    "images_disabled",
    "no_frames_retained",
)

private val LADDER_FALSE_KEYS = listOf(
    "intrinsics_fitted",
    "metric_accuracy_claimed",
)

private val LADDER_STATIC_TEXT = mapOf(
    "distance_unit" to LADDER_DISTANCE_UNIT,
    "ground_truth_source" to LADDER_GROUND_TRUTH_SOURCE,
    "tick_unit" to LADDER_TICK_UNIT,
)

private val LADDER_KEYS = listOf(
    "version",
    "run_id",
    "device_token",
    "campaign_id",
    "rung_count",
    "distance_unit",
    "ground_truth_source",
    "tick_unit",
    "ground_truth_manual",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "universal_calibration_disabled",
    "per_device_calibration",
    "manual_upload_only",
    "images_disabled",
    "no_frames_retained",
    "intrinsics_fitted",
    "metric_accuracy_claimed",
    "quality_grade",
    "focal_px_mean",
    "focal_px_min",
    "focal_px_max",
    "spread_permille",
    "trend_permille_per_m",
    "trend_correlation_permille",
    "residual_max_permille",
    "rungs_ok",
    "trend_ok",
    "spread_ok",
    "scale_sufficient",
)
