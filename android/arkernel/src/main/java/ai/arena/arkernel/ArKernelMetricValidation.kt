package ai.arena.arkernel

import java.security.MessageDigest

private const val VALID_HEADER = "ARKVALID1"
private const val VALID_VERSION = "1"
private const val VALID_QUALITY = "unverified"
private const val VALID_END = "END"
private const val VALID_MAX_INPUT_CHARACTERS = 16_384
private const val VALID_MAX_LINE_CHARACTERS = 256
private const val VALID_MAX_SCENES = 16
private const val VALID_ROW_FIELDS = 8
private const val VALID_MIN_MILLIMETRES = 1L
private const val VALID_MAX_MILLIMETRES = 100_000L
private const val VALID_MIN_THRESHOLD_MILLIMETRES = 1L
private const val VALID_MAX_THRESHOLD_MILLIMETRES = 10_000L
private const val VALID_GROUND_TRUTH_UNIT = "mm"
private const val VALID_ESTIMATE_UNIT = "mm"
private const val VALID_GROUND_TRUTH_SOURCE = "operator_tape_measure"
private const val VALID_P95_NUMERATOR = 95L
private const val VALID_P95_DENOMINATOR = 100L

private val VALID_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val VALID_HEX64 = Regex("[0-9a-f]{64}")
private val VALID_UNSIGNED = Regex("0|[1-9][0-9]*")
private val VALID_SIGNED = Regex("0|-?[1-9][0-9]*")
private val VALID_TRUE_KEYS = listOf(
    "ground_truth_manual",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "universal_calibration_disabled",
    "per_device_calibration",
    "manual_upload_only",
    "images_disabled",
    "held_out_required",
)
private val VALID_FALSE_KEYS = listOf("metric_accuracy_claimed")
private val VALID_KEYS = listOf(
    "version",
    "validation_id",
    "device_token",
    "campaign_id",
    "scene_count",
    "threshold_mm",
    "ground_truth_unit",
    "estimate_unit",
    "ground_truth_source",
    "ground_truth_manual",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "universal_calibration_disabled",
    "per_device_calibration",
    "manual_upload_only",
    "images_disabled",
    "held_out_required",
    "metric_accuracy_claimed",
    "quality_grade",
    "mean_abs_error_mm",
    "bias_mm",
    "max_abs_error_mm",
    "p95_abs_error_mm",
    "within_threshold_count",
    "within_threshold",
)
private val VALID_STATIC_TEXT = mapOf(
    "ground_truth_unit" to VALID_GROUND_TRUTH_UNIT,
    "estimate_unit" to VALID_ESTIMATE_UNIT,
    "ground_truth_source" to VALID_GROUND_TRUTH_SOURCE,
)

enum class ArKernelMetricValidationRejection {
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
    INVALID_DIGEST,
    INVALID_COUNT,
    INVALID_ROW,
    INVALID_KIND,
    INVALID_CONDITION,
    INVALID_UNIT,
    DUPLICATE_SCENE,
    DUPLICATE_DIGEST,
    INVALID_ORDER,
    GROUND_TRUTH_OUT_OF_RANGE,
    ESTIMATE_OUT_OF_RANGE,
    THRESHOLD_OUT_OF_RANGE,
    EMPTY_SOURCE,
    HELD_OUT_REQUIRED,
    STATISTICS_MISMATCH,
}

/** One validated scene: operator ground truth against one app estimate. */
data class ArKernelMetricValidationRow(
    val index: Int,
    val sceneId: String,
    val kind: ArKernelCampaignSceneKind,
    val condition: ArKernelMeasurementCondition,
    val heldOut: Boolean,
    val groundTruthMm: Long,
    val estimateMm: Long,
    val sourceDigest: String,
) {
    init {
        require(index >= 0) { "Invalid validation row index" }
        require(VALID_TOKEN.matches(sceneId)) {
            "Invalid validation scene ID"
        }
        require(VALID_HEX64.matches(sourceDigest)) {
            "Invalid validation source digest"
        }
        require(groundTruthMm in VALID_MIN_MILLIMETRES..VALID_MAX_MILLIMETRES) {
            "Validation ground truth is out of bounds"
        }
        require(estimateMm in VALID_MIN_MILLIMETRES..VALID_MAX_MILLIMETRES) {
            "Validation estimate is out of bounds"
        }
    }

    /** Estimate minus ground truth in millimetres; negative means too short. */
    fun signedErrorMm(): Long = estimateMm - groundTruthMm

    fun absoluteErrorMm(): Long {
        val error = signedErrorMm()
        return if (error < 0L) -error else error
    }
}

/**
 * Bounded centimetre validation ledger.
 *
 * Every row binds one operator-measured ground truth (a tape measure reading
 * in whole millimetres) to one app estimate taken from one pasted measurement
 * artefact, bound by the lowercase SHA-256 digest of that artefact's text.
 * The adapter computes the error statistics itself: mean absolute error,
 * signed bias, maximum absolute error, an integer p95 absolute error and the
 * number of scenes inside a declared threshold.
 *
 * This is the *measurement of the deviation*, not a promise of accuracy. The
 * ground truth is typed in by a human and is never re-verified, so
 * `metric_accuracy_claimed` stays false and the grade stays `unverified`.
 * Nine fail-closed invariants keep manual ground truth, per-device
 * calibration, disabled automatic calibration and upload, disabled image
 * export and manual upload mandatory. At least one held-out scene is
 * required.
 *
 * No image, pixel, pose or trajectory is read or written, no frame is
 * captured, no calibration value is produced and no quality is assessed.
 */
data class ArKernelMetricValidationBundle(
    val validationId: String,
    val deviceToken: String,
    val campaignId: String,
    val thresholdMm: Long,
    val rows: List<ArKernelMetricValidationRow>,
    val qualityGrade: String = VALID_QUALITY,
) {
    init {
        require(VALID_TOKEN.matches(validationId) &&
            VALID_TOKEN.matches(deviceToken) &&
            VALID_TOKEN.matches(campaignId)) {
            "Invalid validation token"
        }
        require(thresholdMm in
            VALID_MIN_THRESHOLD_MILLIMETRES..VALID_MAX_THRESHOLD_MILLIMETRES) {
            "Validation threshold is out of bounds"
        }
        require(rows.size in 1..VALID_MAX_SCENES) {
            "Invalid validation scene count"
        }
        require(rows.withIndex().all { it.value.index == it.index }) {
            "Validation rows are not contiguous"
        }
        require(rows.map { it.sceneId }.toSet().size == rows.size) {
            "Duplicate validation scene ID"
        }
        require(rows.map { it.sourceDigest }.toSet().size == rows.size) {
            "Duplicate validation source digest"
        }
        require(rows.any { it.heldOut }) {
            "At least one held-out validation scene is required"
        }
        require(qualityGrade == VALID_QUALITY) {
            "Validation cannot assert assessed quality"
        }
    }
}

/** Error statistics over one ledger; integer millimetres, no rounding modes. */
data class ArKernelMetricValidationReport(
    val sceneCount: Int,
    val meanAbsErrorMm: Long,
    val biasMm: Long,
    val maxAbsErrorMm: Long,
    val p95AbsErrorMm: Long,
    val withinThresholdCount: Int,
    val withinThreshold: Boolean,
    val heldOutCount: Int,
    val distinctKinds: Int,
    val metricAccuracyClaimed: Boolean,
    val qualityGrade: String = VALID_QUALITY,
)

/** One host-supplied scene draft for [ArKernelMetricValidationAdapter.create]. */
data class ArKernelMetricValidationEntry(
    val sceneId: String,
    val kind: ArKernelCampaignSceneKind,
    val condition: ArKernelMeasurementCondition,
    val heldOut: Boolean,
    val groundTruthMm: Long,
    val estimateMm: Long,
    val sourceText: String,
)

data class ArKernelMetricValidationCreateResult(
    val bundle: ArKernelMetricValidationBundle?,
    val rejection: ArKernelMetricValidationRejection,
    val detail: String,
    val sourceDigests: List<String>,
) {
    val accepted: Boolean
        get() = rejection == ArKernelMetricValidationRejection.NONE &&
            bundle != null
}

data class ArKernelMetricValidationParseResult(
    val bundle: ArKernelMetricValidationBundle?,
    val rejection: ArKernelMetricValidationRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelMetricValidationRejection.NONE &&
            bundle != null
}

/**
 * Canonical `ARKVALID1` text adapter.
 *
 * `create()` digests the pasted source text of each scene so every row stays
 * bound to the artefact it came from; the source text itself is never stored
 * in the ledger. `encode()` writes the recomputed statistics, `parse()`
 * recomputes them from the scene rows and rejects any ledger whose declared
 * statistics disagree. `summarize()` truncates integer division toward zero,
 * so a negative bias reads as "on average too short" rather than as a rounded
 * magnitude. p95 is the linear interpolation between the neighbours at
 * position `0.95 * (n - 1)` of the ascending absolute errors, rounded half up
 * in whole millimetres.
 *
 * The adapter performs no file I/O, no upload, no signature verification, no
 * image, pixel, pose or trajectory handling, no calibration, no physical
 * re-measurement and no quality assessment. The grade stays `unverified`.
 */
object ArKernelMetricValidationAdapter {

    /** Lowercase SHA-256 of the UTF-8 bytes, used only to bind scene rows. */
    fun digestOf(text: String): String = validSha256Hex(text)

    fun create(
        validationId: String,
        deviceToken: String,
        campaignId: String,
        thresholdMm: Long,
        entries: List<ArKernelMetricValidationEntry>,
    ): ArKernelMetricValidationCreateResult {
        if (!VALID_TOKEN.matches(validationId) ||
            !VALID_TOKEN.matches(deviceToken) ||
            !VALID_TOKEN.matches(campaignId)
        ) {
            return createReject(
                ArKernelMetricValidationRejection.INVALID_TOKEN,
                "Invalid validation token")
        }
        if (thresholdMm !in
            VALID_MIN_THRESHOLD_MILLIMETRES..VALID_MAX_THRESHOLD_MILLIMETRES
        ) {
            return createReject(
                ArKernelMetricValidationRejection.THRESHOLD_OUT_OF_RANGE,
                "Validation threshold is out of bounds")
        }
        if (entries.isEmpty() || entries.size > VALID_MAX_SCENES) {
            return createReject(
                ArKernelMetricValidationRejection.INVALID_COUNT,
                "Validation scene count is out of bounds")
        }
        val rows = ArrayList<ArKernelMetricValidationRow>(entries.size)
        val digests = ArrayList<String>(entries.size)
        for ((index, entry) in entries.withIndex()) {
            if (!VALID_TOKEN.matches(entry.sceneId)) {
                return createReject(
                    ArKernelMetricValidationRejection.INVALID_TOKEN,
                    "Invalid validation scene ID")
            }
            if (entry.groundTruthMm !in
                VALID_MIN_MILLIMETRES..VALID_MAX_MILLIMETRES
            ) {
                return createReject(
                    ArKernelMetricValidationRejection
                        .GROUND_TRUTH_OUT_OF_RANGE,
                    "Validation ground truth is out of bounds")
            }
            if (entry.estimateMm !in
                VALID_MIN_MILLIMETRES..VALID_MAX_MILLIMETRES
            ) {
                return createReject(
                    ArKernelMetricValidationRejection.ESTIMATE_OUT_OF_RANGE,
                    "Validation estimate is out of bounds")
            }
            if (entry.sourceText.isBlank()) {
                return createReject(
                    ArKernelMetricValidationRejection.EMPTY_SOURCE,
                    "Validation source text is empty")
            }
            val digest = validSha256Hex(entry.sourceText)
            val row = try {
                ArKernelMetricValidationRow(
                    index = index,
                    sceneId = entry.sceneId,
                    kind = entry.kind,
                    condition = entry.condition,
                    heldOut = entry.heldOut,
                    groundTruthMm = entry.groundTruthMm,
                    estimateMm = entry.estimateMm,
                    sourceDigest = digest,
                )
            } catch (error: IllegalArgumentException) {
                return createReject(
                    ArKernelMetricValidationRejection.INVALID_ROW,
                    error.message ?: "Invalid validation row")
            }
            rows += row
            digests += digest
        }
        if (rows.map { it.sceneId }.toSet().size != rows.size) {
            return createReject(
                ArKernelMetricValidationRejection.DUPLICATE_SCENE,
                "Duplicate validation scene ID")
        }
        if (digests.toSet().size != digests.size) {
            return createReject(
                ArKernelMetricValidationRejection.DUPLICATE_DIGEST,
                "Duplicate validation source digest")
        }
        if (!rows.any { it.heldOut }) {
            return createReject(
                ArKernelMetricValidationRejection.HELD_OUT_REQUIRED,
                "At least one held-out validation scene is required")
        }
        val bundle = try {
            ArKernelMetricValidationBundle(
                validationId = validationId,
                deviceToken = deviceToken,
                campaignId = campaignId,
                thresholdMm = thresholdMm,
                rows = rows,
            )
        } catch (error: IllegalArgumentException) {
            return createReject(
                ArKernelMetricValidationRejection.INVALID_COUNT,
                error.message ?: "Invalid validation bundle")
        }
        return ArKernelMetricValidationCreateResult(
            bundle,
            ArKernelMetricValidationRejection.NONE,
            "",
            digests,
        )
    }

    fun encode(bundle: ArKernelMetricValidationBundle): String {
        val report = summarize(bundle)
        val lines = ArrayList<String>(VALID_KEYS.size + bundle.rows.size + 2)
        lines += VALID_HEADER
        lines += "version=$VALID_VERSION"
        lines += "validation_id=${bundle.validationId}"
        lines += "device_token=${bundle.deviceToken}"
        lines += "campaign_id=${bundle.campaignId}"
        lines += "scene_count=${bundle.rows.size}"
        lines += "threshold_mm=${bundle.thresholdMm}"
        lines += "ground_truth_unit=$VALID_GROUND_TRUTH_UNIT"
        lines += "estimate_unit=$VALID_ESTIMATE_UNIT"
        lines += "ground_truth_source=$VALID_GROUND_TRUTH_SOURCE"
        lines += "ground_truth_manual=true"
        lines += "automatic_calibration_disabled=true"
        lines += "automatic_upload_disabled=true"
        lines += "signature_verification_disabled=true"
        lines += "universal_calibration_disabled=true"
        lines += "per_device_calibration=true"
        lines += "manual_upload_only=true"
        lines += "images_disabled=true"
        lines += "held_out_required=true"
        lines += "metric_accuracy_claimed=false"
        lines += "quality_grade=$VALID_QUALITY"
        lines += "mean_abs_error_mm=${report.meanAbsErrorMm}"
        lines += "bias_mm=${report.biasMm}"
        lines += "max_abs_error_mm=${report.maxAbsErrorMm}"
        lines += "p95_abs_error_mm=${report.p95AbsErrorMm}"
        lines += "within_threshold_count=${report.withinThresholdCount}"
        lines += "within_threshold=${boolText(report.withinThreshold)}"
        for (row in bundle.rows) lines += rowLine(row)
        lines += VALID_END
        return lines.joinToString("\n") + "\n"
    }

    fun summarize(
        bundle: ArKernelMetricValidationBundle,
    ): ArKernelMetricValidationReport {
        val rows = bundle.rows
        val count = rows.size
        val absolute = ArrayList<Long>(count)
        var sumAbsolute = 0L
        var sumSigned = 0L
        for (row in rows) {
            val error = row.signedErrorMm()
            val magnitude = if (error < 0L) -error else error
            absolute += magnitude
            sumAbsolute += magnitude
            sumSigned += error
        }
        val sorted = absolute.sorted()
        val mean = sumAbsolute / count
        val bias = sumSigned / count
        val scaled = VALID_P95_NUMERATOR * (count - 1).toLong()
        val lowerIndex = (scaled / VALID_P95_DENOMINATOR).toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(count - 1)
        val remainder = scaled % VALID_P95_DENOMINATOR
        val lower = sorted[lowerIndex]
        val upper = sorted[upperIndex]
        val p95 = lower + ((upper - lower) * remainder +
            (VALID_P95_DENOMINATOR / 2L)) / VALID_P95_DENOMINATOR
        var withinThreshold = 0
        for (row in rows) {
            if (row.absoluteErrorMm() <= bundle.thresholdMm) {
                withinThreshold++
            }
        }
        return ArKernelMetricValidationReport(
            sceneCount = count,
            meanAbsErrorMm = mean,
            biasMm = bias,
            maxAbsErrorMm = sorted[count - 1],
            p95AbsErrorMm = p95,
            withinThresholdCount = withinThreshold,
            withinThreshold = withinThreshold == count,
            heldOutCount = rows.count { it.heldOut },
            distinctKinds = rows.map { it.kind }.toSet().size,
            metricAccuracyClaimed = false,
        )
    }

    /** Structural consistency only; it re-checks no ground truth. */
    fun verify(bundle: ArKernelMetricValidationBundle): Boolean {
        if (bundle.rows.isEmpty()) return false
        if (bundle.rows.withIndex().any { it.value.index != it.index }) {
            return false
        }
        if (bundle.rows.map { it.sceneId }.toSet().size !=
            bundle.rows.size
        ) {
            return false
        }
        if (bundle.rows.map { it.sourceDigest }.toSet().size !=
            bundle.rows.size
        ) {
            return false
        }
        return bundle.rows.any { it.heldOut }
    }

    fun parse(text: String): ArKernelMetricValidationParseResult {
        if (text.length > VALID_MAX_INPUT_CHARACTERS) {
            return validReject(
                ArKernelMetricValidationRejection.INPUT_TOO_LARGE,
                0, "Validation exceeds its bounded length")
        }
        if (text.isBlank()) {
            return validReject(
                ArKernelMetricValidationRejection.EMPTY_LEDGER,
                0, "Validation is empty")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        val lines = normalized.trimEnd('\n').split('\n')
        val headerError = validateLine(
            lines[0].trimEnd('\r'), 1)
        if (headerError != null) return headerError
        if (lines[0].trimEnd('\r') != VALID_HEADER) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_HEADER,
                1, "Validation must start with ARKVALID1")
        }
        if (lines.size < VALID_KEYS.size + 2) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_SCHEMA,
                1, "Validation is missing its key block")
        }
        val values = LinkedHashMap<String, String>()
        val lineByKey = LinkedHashMap<String, Int>()
        for ((offset, key) in VALID_KEYS.withIndex()) {
            val lineNumber = offset + 2
            val lineText = lines[offset + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val separator = lineText.indexOf('=')
            if (separator <= 0) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_SCHEMA,
                    lineNumber, "Validation line is not a key")
            }
            if (lineText.substring(0, separator) != key) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_SCHEMA,
                    lineNumber, "Validation key order is invalid")
            }
            values[key] = lineText.substring(separator + 1)
            lineByKey[key] = lineNumber
        }
        if (values.getValue("version") != VALID_VERSION) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Validation version must be one")
        }
        if (values.getValue("quality_grade") != VALID_QUALITY) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Validation cannot assert assessed quality")
        }
        for ((key, expected) in VALID_STATIC_TEXT) {
            if (values.getValue(key) != expected) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_UNIT,
                    lineByKey.getValue(key),
                    "Validation unit declaration must be $expected")
            }
        }
        for (key in VALID_TRUE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Validation flag must be a boolean")
            }
            if (value != "true") {
                return validReject(
                    ArKernelMetricValidationRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Validation safety invariant must be true")
            }
        }
        for (key in VALID_FALSE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Validation flag must be a boolean")
            }
            if (value != "false") {
                return validReject(
                    ArKernelMetricValidationRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Validation cannot claim metric accuracy")
            }
        }
        val withinThresholdText = values.getValue("within_threshold")
        if (withinThresholdText != "true" && withinThresholdText != "false") {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_BOOLEAN,
                lineByKey.getValue("within_threshold"),
                "Validation threshold flag must be a boolean")
        }
        for (key in listOf(
            "validation_id", "device_token", "campaign_id")) {
            if (!VALID_TOKEN.matches(values.getValue(key))) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid validation token")
            }
        }
        val sceneCount = validUInt(values.getValue("scene_count"))
            ?: return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE,
                lineByKey.getValue("scene_count"),
                "Invalid validation scene count")
        if (sceneCount !in 1..VALID_MAX_SCENES) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_COUNT,
                lineByKey.getValue("scene_count"),
                "Validation scene count is out of bounds")
        }
        val thresholdMm = validULong(values.getValue("threshold_mm"))
            ?: return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE,
                lineByKey.getValue("threshold_mm"),
                "Invalid validation threshold")
        if (thresholdMm !in
            VALID_MIN_THRESHOLD_MILLIMETRES..VALID_MAX_THRESHOLD_MILLIMETRES
        ) {
            return validReject(
                ArKernelMetricValidationRejection.THRESHOLD_OUT_OF_RANGE,
                lineByKey.getValue("threshold_mm"),
                "Validation threshold is out of bounds")
        }
        val meanAbsErrorMm = validULong(
            values.getValue("mean_abs_error_mm"))
            ?: return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE,
                lineByKey.getValue("mean_abs_error_mm"),
                "Invalid validation mean error")
        val biasMm = validLong(values.getValue("bias_mm"))
            ?: return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE,
                lineByKey.getValue("bias_mm"),
                "Invalid validation bias")
        val maxAbsErrorMm = validULong(
            values.getValue("max_abs_error_mm"))
            ?: return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE,
                lineByKey.getValue("max_abs_error_mm"),
                "Invalid validation maximum error")
        val p95AbsErrorMm = validULong(
            values.getValue("p95_abs_error_mm"))
            ?: return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE,
                lineByKey.getValue("p95_abs_error_mm"),
                "Invalid validation p95 error")
        val withinThresholdCount = validUInt(
            values.getValue("within_threshold_count"))
            ?: return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE,
                lineByKey.getValue("within_threshold_count"),
                "Invalid validation threshold count")
        val rowStart = VALID_KEYS.size + 1
        val endIndex = rowStart + sceneCount
        if (lines.size != endIndex + 1) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_SCHEMA, 0,
                "Validation line count does not match scene count")
        }
        val rows = ArrayList<ArKernelMetricValidationRow>(sceneCount)
        for (index in 0 until sceneCount) {
            val lineNumber = rowStart + index + 1
            val lineText = lines[rowStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("scene=")) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_ROW,
                    lineNumber, "Validation line is not a scene")
            }
            val fields =
                lineText.substringAfter("scene=").split('|')
            if (fields.size != VALID_ROW_FIELDS) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_ROW,
                    lineNumber,
                    "Validation scene must have eight fields")
            }
            val rowIndex = validUInt(fields[0])
                ?: return validReject(
                    ArKernelMetricValidationRejection.INVALID_VALUE,
                    lineNumber, "Invalid validation row index")
            if (rowIndex != index) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_ORDER,
                    lineNumber, "Validation rows are not contiguous")
            }
            if (!VALID_TOKEN.matches(fields[1])) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_TOKEN,
                    lineNumber, "Invalid validation scene ID")
            }
            val kind = ArKernelCampaignSceneKind.fromWire(fields[2])
                ?: return validReject(
                    ArKernelMetricValidationRejection.INVALID_KIND,
                    lineNumber, "Invalid validation scene kind")
            val condition = ArKernelMeasurementCondition.fromWire(fields[3])
                ?: return validReject(
                    ArKernelMetricValidationRejection.INVALID_CONDITION,
                    lineNumber, "Invalid validation condition")
            if (fields[4] != "true" && fields[4] != "false") {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_BOOLEAN,
                    lineNumber,
                    "Validation held-out flag must be a boolean")
            }
            val groundTruthMm = validULong(fields[5])
                ?: return validReject(
                    ArKernelMetricValidationRejection.INVALID_VALUE,
                    lineNumber, "Invalid validation ground truth")
            if (groundTruthMm !in
                VALID_MIN_MILLIMETRES..VALID_MAX_MILLIMETRES
            ) {
                return validReject(
                    ArKernelMetricValidationRejection
                        .GROUND_TRUTH_OUT_OF_RANGE,
                    lineNumber,
                    "Validation ground truth is out of bounds")
            }
            val estimateMm = validULong(fields[6])
                ?: return validReject(
                    ArKernelMetricValidationRejection.INVALID_VALUE,
                    lineNumber, "Invalid validation estimate")
            if (estimateMm !in VALID_MIN_MILLIMETRES..VALID_MAX_MILLIMETRES) {
                return validReject(
                    ArKernelMetricValidationRejection.ESTIMATE_OUT_OF_RANGE,
                    lineNumber, "Validation estimate is out of bounds")
            }
            if (!VALID_HEX64.matches(fields[7])) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_DIGEST,
                    lineNumber, "Invalid validation source digest")
            }
            rows += try {
                ArKernelMetricValidationRow(
                    index = rowIndex,
                    sceneId = fields[1],
                    kind = kind,
                    condition = condition,
                    heldOut = fields[4] == "true",
                    groundTruthMm = groundTruthMm,
                    estimateMm = estimateMm,
                    sourceDigest = fields[7],
                )
            } catch (error: IllegalArgumentException) {
                return validReject(
                    ArKernelMetricValidationRejection.INVALID_ROW,
                    lineNumber,
                    error.message ?: "Invalid validation row")
            }
        }
        if (rows.map { it.sceneId }.toSet().size != rows.size) {
            return validReject(
                ArKernelMetricValidationRejection.DUPLICATE_SCENE,
                rowStart + 1, "Duplicate validation scene ID")
        }
        if (rows.map { it.sourceDigest }.toSet().size != rows.size) {
            return validReject(
                ArKernelMetricValidationRejection.DUPLICATE_DIGEST,
                rowStart + 1, "Duplicate validation source digest")
        }
        if (!rows.any { it.heldOut }) {
            return validReject(
                ArKernelMetricValidationRejection.HELD_OUT_REQUIRED,
                rowStart + 1,
                "At least one held-out validation scene is required")
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != VALID_END) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_SCHEMA,
                endLineNumber, "Validation must end with END")
        }
        val bundle = try {
            ArKernelMetricValidationBundle(
                validationId = values.getValue("validation_id"),
                deviceToken = values.getValue("device_token"),
                campaignId = values.getValue("campaign_id"),
                thresholdMm = thresholdMm,
                rows = rows,
            )
        } catch (error: IllegalArgumentException) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid validation bundle")
        }
        val report = summarize(bundle)
        if (report.meanAbsErrorMm != meanAbsErrorMm ||
            report.biasMm != biasMm ||
            report.maxAbsErrorMm != maxAbsErrorMm ||
            report.p95AbsErrorMm != p95AbsErrorMm ||
            report.withinThresholdCount != withinThresholdCount ||
            report.withinThreshold != (withinThresholdText == "true")
        ) {
            return validReject(
                ArKernelMetricValidationRejection.STATISTICS_MISMATCH,
                lineByKey.getValue("mean_abs_error_mm"),
                "Validation statistics do not match the scene rows")
        }
        return ArKernelMetricValidationParseResult(
            bundle, ArKernelMetricValidationRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelMetricValidationParseResult? {
        if (lineText.length > VALID_MAX_LINE_CHARACTERS) {
            return validReject(
                ArKernelMetricValidationRejection.LINE_TOO_LONG,
                lineNumber,
                "Validation line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return validReject(
                ArKernelMetricValidationRejection.EMPTY_LINE,
                lineNumber,
                "Validation contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return validReject(
                ArKernelMetricValidationRejection.INVALID_SCHEMA,
                lineNumber,
                "Validation lines must not contain whitespace")
        }
        return null
    }

    private fun rowLine(row: ArKernelMetricValidationRow): String =
        "scene=" + listOf(
            row.index.toString(),
            row.sceneId,
            row.kind.wireName(),
            row.condition.wireName(),
            boolText(row.heldOut),
            row.groundTruthMm.toString(),
            row.estimateMm.toString(),
            row.sourceDigest,
        ).joinToString("|")

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun validUInt(value: String): Int? =
        if (VALID_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun validULong(value: String): Long? =
        if (VALID_UNSIGNED.matches(value)) value.toLongOrNull() else null

    private fun validLong(value: String): Long? =
        if (VALID_SIGNED.matches(value)) value.toLongOrNull() else null

    private fun validSha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value < 16) builder.append('0')
            builder.append(value.toString(16))
        }
        return builder.toString()
    }

    private fun validReject(
        rejection: ArKernelMetricValidationRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelMetricValidationParseResult =
        ArKernelMetricValidationParseResult(
            null, rejection, lineNumber, detail)

    private fun createReject(
        rejection: ArKernelMetricValidationRejection,
        detail: String,
    ): ArKernelMetricValidationCreateResult =
        ArKernelMetricValidationCreateResult(
            null, rejection, detail, emptyList())
}
