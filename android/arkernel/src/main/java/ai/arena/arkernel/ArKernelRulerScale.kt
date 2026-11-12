package ai.arena.arkernel

private const val RULER_HEADER = "ARKSCALE1"
private const val RULER_VERSION = "1"
private const val RULER_QUALITY = "unverified"
private const val RULER_END = "END"
private const val RULER_MAX_INPUT_CHARACTERS = 16_384
private const val RULER_MAX_LINE_CHARACTERS = 256
private const val RULER_MAX_COORD = 4_096.0
private const val RULER_MIN_FOLDED = 0.05
private const val RULER_MAX_FOLDED = 4.0
private const val RULER_CM_PER_M = 100
private const val RULER_MAX_TICKS = 200
private const val RULER_MIN_TICKS = 2
private const val RULER_MAX_SCALE = 999_999_999.0
private const val RULER_MAX_FRAMES = 10_000_000L
private const val RULER_MAX_CAPTURE_INDEX = 9_999
private const val RULER_MARKER_COUNT = 4
private const val RULER_ROW_FIELDS = 3

private val RULER_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val RULER_UNSIGNED = Regex("0|[1-9][0-9]*")
private val RULER_DECIMAL = Regex(
    "(0|[1-9][0-9]{0,8})(\\.[0-9]{1,6})?")
private val RULER_KEYS = listOf(
    "version",
    "session_id",
    "device_token",
    "capture_index",
    "frames_observed",
    "frames_analyzed",
    "frames_exported",
    "sample_count",
    "frame_analysis_opt_in",
    "frame_persistence_disabled",
    "image_export_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "physical_validation_disabled",
    "metric_accuracy_claimed",
    "per_device_calibration",
    "universal_calibration_disabled",
    "manual_upload_only",
    "user_confirmed",
    "ruler_detection_confirmed",
    "scene_kind",
    "condition",
    "held_out",
    "stated_folded_m",
    "tick_count",
    "expected_tick_count",
    "tick_spacing_px",
    "tick_regularity",
    "tick_count_consistent",
    "reference_pixel_distance",
    "target_pixel_distance",
    "scale_px_per_m",
    "estimated_target_m",
    "quality_grade",
)
private val RULER_BOOLEAN_KEYS = listOf(
    "frame_analysis_opt_in",
    "frame_persistence_disabled",
    "image_export_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "physical_validation_disabled",
    "metric_accuracy_claimed",
    "per_device_calibration",
    "universal_calibration_disabled",
    "manual_upload_only",
    "user_confirmed",
    "ruler_detection_confirmed",
    "held_out",
    "tick_count_consistent",
)
private val RULER_TRUE_KEYS = listOf(
    "frame_analysis_opt_in",
    "frame_persistence_disabled",
    "image_export_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "physical_validation_disabled",
    "per_device_calibration",
    "universal_calibration_disabled",
    "manual_upload_only",
    "user_confirmed",
    "ruler_detection_confirmed",
)
private val RULER_FALSE_KEYS = listOf("metric_accuracy_claimed")
private val RULER_MARKER_PREFIXES = listOf(
    "reference_marker=0|",
    "reference_marker=1|",
    "target_marker=0|",
    "target_marker=1|",
)
private const val RULER_MARKER_ROWS = 4

enum class ArKernelRulerScaleRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_RECORD,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    UNCONFIRMED_CAPTURE,
    RULER_NOT_CONFIRMED,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_DECIMAL,
    INVALID_RANGE,
    INVALID_COUNT,
    INVALID_KIND,
    INVALID_CONDITION,
    INVALID_TICK,
    DUPLICATE_TICK,
    TICK_COUNT_TOO_LARGE,
    RULER_NOT_DETECTED,
    INVALID_SAMPLE_COUNT,
    INVALID_MARKER,
    INVALID_GEOMETRY,
    FRAMES_EXPORTED_NONZERO,
    FRAMES_ANALYZED_EXCEEDS_OBSERVED,
}

/** One automatically detected tick mark in overlay-view pixel coordinates. */
data class ArKernelRulerTickRow(
    val index: Int,
    val x: Double,
    val y: Double,
) {
    init {
        require(index >= 0) { "Invalid ruler tick index" }
        require(isRulerFinite(x) && isRulerFinite(y)) {
            "Ruler tick coordinate is not finite"
        }
        require(x >= 0.0 && x <= RULER_MAX_COORD &&
            y >= 0.0 && y <= RULER_MAX_COORD) {
            "Ruler tick coordinate is out of bounds"
        }
    }
}

/** Result of the pure, deterministic tick detection over one luma profile. */
data class ArKernelRulerTickDetection(
    val indices: List<Int>,
    val sampleCount: Int,
    val regularity: Double,
    val spacing: Double,
) {
    val detected: Boolean
        get() = indices.size >= RULER_MIN_TICKS
}

/**
 * One bounded opt-in automatic folding-ruler capture.
 *
 * A folding ruler carries evenly spaced, high-contrast tick marks. The app
 * samples luma along the line the user indicates, detects those ticks purely
 * in memory, snaps the reference endpoints to the nearest detected ticks and
 * derives a pixels-per-metre scale from the length the user **stated** they
 * folded out (for example 1 m or 2 m). The detected tick count is compared
 * with the number of centimetre ticks implied by the stated length, so a
 * wrong ruler or a wrong statement is visible as `tick_count_consistent=false`.
 *
 * This improves **repeatability of endpoint placement**, not metric accuracy:
 * depth, parallax, perspective, rolling shutter and lens distortion are not
 * modelled, so `metric_accuracy_claimed` is always false and
 * `estimated_target_m` remains a screen-ratio estimate that assumes the ruler
 * and the target lie in one fronto-parallel plane at equal depth.
 *
 * Preview frames are analysed in memory and discarded; `frames_exported` is
 * always zero and `frame_persistence_disabled` is always true. Thirteen
 * fail-closed invariants keep the capture opt-in, export-free, manual-upload
 * only and explicitly confirmed. This is a structural capture record, not an
 * accuracy assessment, not a centimetre validation, not a calibration and not
 * a quality grade. The grade stays `unverified`.
 */
data class ArKernelRulerScaleBundle(
    val sessionId: String,
    val deviceToken: String,
    val captureIndex: Int,
    val framesObserved: Long,
    val framesAnalyzed: Long,
    val sampleCount: Int,
    val sceneKind: ArKernelCampaignSceneKind,
    val condition: ArKernelMeasurementCondition,
    val heldOut: Boolean,
    val statedFoldedMeters: Double,
    val ticks: List<ArKernelRulerTickRow>,
    val referenceStart: ArKernelMeasurementFeedMarker,
    val referenceEnd: ArKernelMeasurementFeedMarker,
    val targetStart: ArKernelMeasurementFeedMarker,
    val targetEnd: ArKernelMeasurementFeedMarker,
    val referencePixelDistance: Double,
    val targetPixelDistance: Double,
    val scalePxPerM: Double,
    val estimatedTargetM: Double,
    val frameAnalysisOptIn: Boolean = true,
    val framePersistenceDisabled: Boolean = true,
    val imageExportDisabled: Boolean = true,
    val automaticCalibrationDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val signatureVerificationDisabled: Boolean = true,
    val physicalValidationDisabled: Boolean = true,
    val metricAccuracyClaimed: Boolean = false,
    val perDeviceCalibration: Boolean = true,
    val universalCalibrationDisabled: Boolean = true,
    val manualUploadOnly: Boolean = true,
    val userConfirmed: Boolean = true,
    val rulerDetectionConfirmed: Boolean = true,
    val framesExported: Int = 0,
    val qualityGrade: String = RULER_QUALITY,
) {
    init {
        require(RULER_TOKEN.matches(sessionId) &&
            RULER_TOKEN.matches(deviceToken)) {
            "Invalid ruler scale token"
        }
        require(captureIndex in 0..RULER_MAX_CAPTURE_INDEX) {
            "Invalid ruler scale capture index"
        }
        require(framesObserved in 0..RULER_MAX_FRAMES &&
            framesAnalyzed in 0..RULER_MAX_FRAMES &&
            framesAnalyzed <= framesObserved) {
            "Invalid ruler scale frame counts"
        }
        require(sampleCount in RULER_MIN_SAMPLES..RULER_MAX_SAMPLES) {
            "Invalid ruler scale sample count"
        }
        require(ticks.size in RULER_MIN_TICKS..RULER_MAX_TICKS) {
            "Invalid ruler scale tick count"
        }
        require(ticks.withIndex().all { it.value.index == it.index }) {
            "Ruler ticks are not contiguous"
        }
        require(isRulerFinite(statedFoldedMeters) &&
            statedFoldedMeters >= RULER_MIN_FOLDED &&
            statedFoldedMeters <= RULER_MAX_FOLDED) {
            "Stated folded length is out of bounds"
        }
        require(isRulerFinite(referencePixelDistance) &&
            referencePixelDistance > 0.0 &&
            isRulerFinite(targetPixelDistance) &&
            targetPixelDistance > 0.0 &&
            isRulerFinite(scalePxPerM) && scalePxPerM > 0.0 &&
            scalePxPerM <= RULER_MAX_SCALE &&
            isRulerFinite(estimatedTargetM) &&
            estimatedTargetM > 0.0) {
            "Ruler scale geometry is out of bounds"
        }
        require(frameAnalysisOptIn && framePersistenceDisabled &&
            imageExportDisabled && automaticCalibrationDisabled &&
            automaticUploadDisabled && signatureVerificationDisabled &&
            physicalValidationDisabled && !metricAccuracyClaimed &&
            perDeviceCalibration && universalCalibrationDisabled &&
            manualUploadOnly && userConfirmed &&
            rulerDetectionConfirmed) {
            "Ruler scale must remain fail-closed"
        }
        require(framesExported == 0) {
            "Ruler scale must not export frames"
        }
        require(qualityGrade == RULER_QUALITY) {
            "Ruler scale cannot assert assessed quality"
        }
    }
}

data class ArKernelRulerScaleParseResult(
    val bundle: ArKernelRulerScaleBundle?,
    val rejection: ArKernelRulerScaleRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelRulerScaleRejection.NONE &&
            bundle != null
}

data class ArKernelRulerScaleCreateResult(
    val bundle: ArKernelRulerScaleBundle?,
    val rejection: ArKernelRulerScaleRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelRulerScaleRejection.NONE &&
            bundle != null
}

/** Structural capture facts only; neither accuracy nor a quality grade. */
data class ArKernelRulerScaleReport(
    val tickCount: Int,
    val expectedTickCount: Int,
    val tickSpacingPx: Double,
    val tickRegularity: Double,
    val tickCountConsistent: Boolean,
    val scalePxPerM: Double,
    val estimatedTargetM: Double,
    val framesAnalyzed: Long,
    val framesExported: Int,
    val heldOut: Boolean,
    val invariantsHeld: Boolean,
    val geometryConsistent: Boolean,
    val qualityGrade: String = RULER_QUALITY,
) {
    val captureReady: Boolean
        get() = tickCount >= RULER_MIN_TICKS &&
            framesExported == 0 && invariantsHeld &&
            geometryConsistent
}

/**
 * Canonical `ARKSCALE1` text adapter.
 *
 * `create()` derives a screen-ratio scale from automatically detected ticks,
 * a stated folded length and four snapped markers. `parse()` re-checks all
 * thirty-five exact metadata keys, thirteen invariants, the ordered tick rows
 * and the derived geometry. `verify()` is structural only; the analysed frames
 * are never persisted, so there is nothing to re-verify. The adapter performs
 * no file I/O, no upload, no camera access, no automatic or universal
 * calibration and no quality assessment. The grade stays `unverified`.
 */
object ArKernelRulerScaleAdapter {
    fun create(
        sessionId: String,
        deviceToken: String,
        captureIndex: Int,
        framesObserved: Long,
        framesAnalyzed: Long,
        sampleCount: Int,
        sceneKind: ArKernelCampaignSceneKind,
        condition: ArKernelMeasurementCondition,
        heldOut: Boolean,
        statedFoldedMeters: Double,
        ticks: List<ArKernelRulerTickRow>,
        referenceStart: ArKernelMeasurementFeedMarker,
        referenceEnd: ArKernelMeasurementFeedMarker,
        targetStart: ArKernelMeasurementFeedMarker,
        targetEnd: ArKernelMeasurementFeedMarker,
        userConfirmed: Boolean,
        rulerDetectionConfirmed: Boolean,
    ): ArKernelRulerScaleCreateResult {
        if (!RULER_TOKEN.matches(sessionId) ||
            !RULER_TOKEN.matches(deviceToken)) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.INVALID_TOKEN,
                "Invalid ruler scale token")
        }
        if (captureIndex !in 0..RULER_MAX_CAPTURE_INDEX) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.INVALID_COUNT,
                "Capture index is out of bounds")
        }
        if (framesObserved !in 0..RULER_MAX_FRAMES ||
            framesAnalyzed !in 0..RULER_MAX_FRAMES ||
            framesAnalyzed > framesObserved) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection
                    .FRAMES_ANALYZED_EXCEEDS_OBSERVED,
                "Analysed frames exceed observed frames")
        }
        if (sampleCount !in RULER_MIN_SAMPLES..RULER_MAX_SAMPLES) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.INVALID_SAMPLE_COUNT,
                "Sample count is out of bounds")
        }
        if (ticks.size < RULER_MIN_TICKS) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.RULER_NOT_DETECTED,
                "At least two ruler ticks are required")
        }
        if (ticks.size > RULER_MAX_TICKS) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.TICK_COUNT_TOO_LARGE,
                "Detected tick count exceeds 200")
        }
        if (!isRulerFinite(statedFoldedMeters) ||
            statedFoldedMeters < RULER_MIN_FOLDED ||
            statedFoldedMeters > RULER_MAX_FOLDED) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.INVALID_RANGE,
                "Stated folded length is out of bounds")
        }
        if (!userConfirmed) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.UNCONFIRMED_CAPTURE,
                "Every ruler capture requires explicit confirmation")
        }
        if (!rulerDetectionConfirmed) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.RULER_NOT_CONFIRMED,
                "The detected ruler must be confirmed explicitly")
        }
        val folded = rulerRound6(statedFoldedMeters)
        val rows = ticks.mapIndexed { index, tick ->
            ArKernelRulerTickRow(
                index, rulerRound6(tick.x), rulerRound6(tick.y))
        }
        val reference = rulerRound6(rulerDistance(
            referenceStart, referenceEnd))
        val target = rulerRound6(
            rulerDistance(targetStart, targetEnd))
        if (reference <= 0.0 || target <= 0.0) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.INVALID_GEOMETRY,
                "Marker pairs must be distinct")
        }
        val scale = rulerRound6(reference / folded)
        val estimate = rulerRound6(target / scale)
        if (scale <= 0.0 || scale > RULER_MAX_SCALE ||
            estimate <= 0.0) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.INVALID_RANGE,
                "Derived screen-ratio values are out of bounds")
        }
        val bundle = try {
            ArKernelRulerScaleBundle(
                sessionId = sessionId,
                deviceToken = deviceToken,
                captureIndex = captureIndex,
                framesObserved = framesObserved,
                framesAnalyzed = framesAnalyzed,
                sampleCount = sampleCount,
                sceneKind = sceneKind,
                condition = condition,
                heldOut = heldOut,
                statedFoldedMeters = folded,
                ticks = rows,
                referenceStart = referenceStart,
                referenceEnd = referenceEnd,
                targetStart = targetStart,
                targetEnd = targetEnd,
                referencePixelDistance = reference,
                targetPixelDistance = target,
                scalePxPerM = scale,
                estimatedTargetM = estimate,
            )
        } catch (error: IllegalArgumentException) {
            return rulerCreateReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                error.message ?: "Invalid ruler scale capture")
        }
        val text = encode(bundle)
        val parsed = parse(text)
        return ArKernelRulerScaleCreateResult(
            parsed.bundle, parsed.rejection, parsed.detail)
    }

    fun encode(bundle: ArKernelRulerScaleBundle): String {
        val lines = ArrayList<String>(
            RULER_KEYS.size + bundle.ticks.size + 2)
        lines.add(RULER_HEADER)
        lines.add("version=$RULER_VERSION")
        lines.add("session_id=${bundle.sessionId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("capture_index=${bundle.captureIndex}")
        lines.add("frames_observed=${bundle.framesObserved}")
        lines.add("frames_analyzed=${bundle.framesAnalyzed}")
        lines.add("frames_exported=${bundle.framesExported}")
        lines.add("sample_count=${bundle.sampleCount}")
        lines.add("frame_analysis_opt_in=" +
            rulerBool(bundle.frameAnalysisOptIn))
        lines.add("frame_persistence_disabled=" +
            rulerBool(bundle.framePersistenceDisabled))
        lines.add("image_export_disabled=" +
            rulerBool(bundle.imageExportDisabled))
        lines.add("automatic_calibration_disabled=" +
            rulerBool(bundle.automaticCalibrationDisabled))
        lines.add("automatic_upload_disabled=" +
            rulerBool(bundle.automaticUploadDisabled))
        lines.add("signature_verification_disabled=" +
            rulerBool(bundle.signatureVerificationDisabled))
        lines.add("physical_validation_disabled=" +
            rulerBool(bundle.physicalValidationDisabled))
        lines.add("metric_accuracy_claimed=" +
            rulerBool(bundle.metricAccuracyClaimed))
        lines.add("per_device_calibration=" +
            rulerBool(bundle.perDeviceCalibration))
        lines.add("universal_calibration_disabled=" +
            rulerBool(bundle.universalCalibrationDisabled))
        lines.add("manual_upload_only=" +
            rulerBool(bundle.manualUploadOnly))
        lines.add("user_confirmed=" +
            rulerBool(bundle.userConfirmed))
        lines.add("ruler_detection_confirmed=" +
            rulerBool(bundle.rulerDetectionConfirmed))
        lines.add("scene_kind=${bundle.sceneKind.wireName()}")
        lines.add("condition=${bundle.condition.wireName()}")
        lines.add("held_out=" + rulerBool(bundle.heldOut))
        lines.add("stated_folded_m=" +
            rulerDecimal(bundle.statedFoldedMeters))
        lines.add("tick_count=${bundle.ticks.size}")
        lines.add("expected_tick_count=" +
            rulerExpectedTicks(bundle.statedFoldedMeters))
        lines.add("tick_spacing_px=" +
            rulerDecimal(rulerMeanTickSpacing(bundle.ticks)))
        lines.add("tick_regularity=" +
            rulerDecimal(rulerTickRegularity(bundle.ticks)))
        lines.add("tick_count_consistent=" +
            rulerBool(rulerTickConsistent(
                bundle.ticks.size,
                rulerExpectedTicks(bundle.statedFoldedMeters))))
        lines.add("reference_pixel_distance=" +
            rulerDecimal(bundle.referencePixelDistance))
        lines.add("target_pixel_distance=" +
            rulerDecimal(bundle.targetPixelDistance))
        lines.add("scale_px_per_m=" +
            rulerDecimal(bundle.scalePxPerM))
        lines.add("estimated_target_m=" +
            rulerDecimal(bundle.estimatedTargetM))
        lines.add("quality_grade=${bundle.qualityGrade}")
        val markerRows = listOf(
            bundle.referenceStart,
            bundle.referenceEnd,
            bundle.targetStart,
            bundle.targetEnd,
        )
        for (index in 0 until RULER_MARKER_ROWS) {
            val marker = markerRows[index]
            lines.add(
                RULER_MARKER_PREFIXES[index] +
                    rulerDecimal(marker.x) + "|" +
                    rulerDecimal(marker.y))
        }
        for (tick in bundle.ticks) {
            lines.add(
                "tick=${tick.index}|" +
                    rulerDecimal(tick.x) + "|" +
                    rulerDecimal(tick.y))
        }
        lines.add(RULER_END)
        val text = lines.joinToString("\n")
        require(text.length <= RULER_MAX_INPUT_CHARACTERS) {
            "Ruler scale record exceeds its bounded size"
        }
        require(lines.all {
            it.length <= RULER_MAX_LINE_CHARACTERS
        }) { "Ruler scale line exceeds its bounded length" }
        return text
    }

    fun summarize(bundle: ArKernelRulerScaleBundle):
        ArKernelRulerScaleReport {
        val expected =
            rulerExpectedTicks(bundle.statedFoldedMeters)
        return ArKernelRulerScaleReport(
            tickCount = bundle.ticks.size,
            expectedTickCount = expected,
            tickSpacingPx = rulerMeanTickSpacing(bundle.ticks),
            tickRegularity = rulerTickRegularity(bundle.ticks),
            tickCountConsistent = rulerTickConsistent(
                bundle.ticks.size, expected),
            scalePxPerM = bundle.scalePxPerM,
            estimatedTargetM = bundle.estimatedTargetM,
            framesAnalyzed = bundle.framesAnalyzed,
            framesExported = bundle.framesExported,
            heldOut = bundle.heldOut,
            invariantsHeld = bundle.imageExportDisabled &&
                bundle.framePersistenceDisabled &&
                bundle.automaticCalibrationDisabled &&
                bundle.automaticUploadDisabled &&
                bundle.userConfirmed &&
                bundle.rulerDetectionConfirmed &&
                !bundle.metricAccuracyClaimed,
            geometryConsistent = rulerGeometryConsistent(bundle),
        )
    }

    /** Structural verification only; analysed frames are never persisted. */
    fun verify(text: String): ArKernelRulerScaleParseResult = parse(text)

    fun parse(text: String): ArKernelRulerScaleParseResult {
        if (text.length > RULER_MAX_INPUT_CHARACTERS) {
            return rulerReject(
                ArKernelRulerScaleRejection.INPUT_TOO_LARGE,
                0, "Ruler scale input is too large")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return rulerReject(
                ArKernelRulerScaleRejection.EMPTY_RECORD,
                0, "Ruler scale input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") ->
                normalized.substring(0, normalized.length - 2)
            normalized.endsWith("\n") ||
                normalized.endsWith("\r") ->
                normalized.substring(0, normalized.length - 1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return rulerReject(
                ArKernelRulerScaleRejection.EMPTY_RECORD,
                0, "Ruler scale has no record")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return rulerReject(
                ArKernelRulerScaleRejection.EMPTY_LINE,
                0, "Ruler scale has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != RULER_HEADER || header.isBlank() ||
            header.length > RULER_MAX_LINE_CHARACTERS) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_HEADER,
                1, "Ruler scale header is invalid")
        }
        val rulerMinimumLines =
            RULER_KEYS.size + RULER_MARKER_ROWS + 2
        if (lines.size < rulerMinimumLines) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
                0, "Ruler scale is too short")
        }
        val lineByKey = RULER_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(RULER_KEYS.size)
        for (index in RULER_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = rulerValidateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Ruler scale metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != RULER_KEYS[index]) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Ruler scale metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != RULER_VERSION) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported ruler scale version")
        }
        if (values.getValue("quality_grade") != RULER_QUALITY) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Ruler scale cannot assert assessed quality")
        }
        for (key in RULER_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Ruler scale flag must be a boolean")
            }
            if (key in RULER_TRUE_KEYS && value != "true") {
                val rejection = when (key) {
                    "user_confirmed" ->
                        ArKernelRulerScaleRejection.UNCONFIRMED_CAPTURE
                    "ruler_detection_confirmed" ->
                        ArKernelRulerScaleRejection.RULER_NOT_CONFIRMED
                    else -> ArKernelRulerScaleRejection.UNSAFE_INVARIANT
                }
                return rulerReject(rejection, lineByKey.getValue(key),
                    "Ruler scale invariant must be true")
            }
            if (key in RULER_FALSE_KEYS && value != "false") {
                return rulerReject(
                    ArKernelRulerScaleRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Metric accuracy must not be claimed")
            }
        }
        for (key in listOf("session_id", "device_token")) {
            if (!RULER_TOKEN.matches(values.getValue(key))) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid ruler scale token")
            }
        }
        val captureIndex = rulerUInt(
            values.getValue("capture_index"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("capture_index"),
                "Invalid capture index")
        if (captureIndex > RULER_MAX_CAPTURE_INDEX) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_COUNT,
                lineByKey.getValue("capture_index"),
                "Capture index is out of bounds")
        }
        val framesObserved = rulerULong(
            values.getValue("frames_observed"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("frames_observed"),
                "Invalid observed frame count")
        val framesAnalyzed = rulerULong(
            values.getValue("frames_analyzed"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("frames_analyzed"),
                "Invalid analysed frame count")
        if (framesObserved > RULER_MAX_FRAMES ||
            framesAnalyzed > RULER_MAX_FRAMES ||
            framesAnalyzed > framesObserved) {
            return rulerReject(
                ArKernelRulerScaleRejection
                    .FRAMES_ANALYZED_EXCEEDS_OBSERVED,
                lineByKey.getValue("frames_analyzed"),
                "Analysed frames exceed observed frames")
        }
        val framesExported = rulerUInt(
            values.getValue("frames_exported"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("frames_exported"),
                "Invalid exported frame count")
        if (framesExported != 0) {
            return rulerReject(
                ArKernelRulerScaleRejection.FRAMES_EXPORTED_NONZERO,
                lineByKey.getValue("frames_exported"),
                "Ruler scale must not export frames")
        }
        val sampleCount = rulerUInt(
            values.getValue("sample_count"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("sample_count"),
                "Invalid sample count")
        if (sampleCount !in RULER_MIN_SAMPLES..RULER_MAX_SAMPLES) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_SAMPLE_COUNT,
                lineByKey.getValue("sample_count"),
                "Sample count is out of bounds")
        }
        val sceneKind = ArKernelCampaignSceneKind.fromWire(
            values.getValue("scene_kind"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_KIND,
                lineByKey.getValue("scene_kind"),
                "Invalid ruler scale scene kind")
        val condition = ArKernelMeasurementCondition.fromWire(
            values.getValue("condition"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_CONDITION,
                lineByKey.getValue("condition"),
                "Invalid ruler scale condition")
        val folded = rulerDec(values.getValue("stated_folded_m"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_DECIMAL,
                lineByKey.getValue("stated_folded_m"),
                "Invalid stated folded length")
        if (folded < RULER_MIN_FOLDED || folded > RULER_MAX_FOLDED) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_RANGE,
                lineByKey.getValue("stated_folded_m"),
                "Stated folded length is out of bounds")
        }
        val tickCount = rulerUInt(values.getValue("tick_count"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("tick_count"),
                "Invalid tick count")
        if (tickCount < RULER_MIN_TICKS ||
            tickCount > RULER_MAX_TICKS) {
            return rulerReject(
                if (tickCount < RULER_MIN_TICKS) {
                    ArKernelRulerScaleRejection.RULER_NOT_DETECTED
                } else {
                    ArKernelRulerScaleRejection.TICK_COUNT_TOO_LARGE
                },
                lineByKey.getValue("tick_count"),
                "Tick count is out of bounds")
        }
        val expected = rulerExpectedTicks(folded)
        if (values.getValue("expected_tick_count") !=
            expected.toString()) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("expected_tick_count"),
                "Expected tick count does not match stated length")
        }
        val spacing = rulerDec(
            values.getValue("tick_spacing_px"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_DECIMAL,
                lineByKey.getValue("tick_spacing_px"),
                "Invalid tick spacing")
        val regularity = rulerDec(
            values.getValue("tick_regularity"))
            ?: return rulerReject(
                ArKernelRulerScaleRejection.INVALID_DECIMAL,
                lineByKey.getValue("tick_regularity"),
                "Invalid tick regularity")
        if (regularity < 0.0 || regularity > 1.0) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_RANGE,
                lineByKey.getValue("tick_regularity"),
                "Tick regularity must be within [0,1]")
        }
        val reference = rulerDec(
            values.getValue("reference_pixel_distance"))
        val target = rulerDec(
            values.getValue("target_pixel_distance"))
        val scale = rulerDec(values.getValue("scale_px_per_m"))
        val estimate = rulerDec(
            values.getValue("estimated_target_m"))
        if (reference == null || target == null ||
            scale == null || estimate == null) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_DECIMAL,
                0, "Invalid derived ruler scale decimal")
        }
        if (reference <= 0.0 || target <= 0.0 || scale <= 0.0 ||
            scale > RULER_MAX_SCALE || estimate <= 0.0) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_RANGE,
                0, "Derived ruler scale values are out of bounds")
        }
        val markerStart = RULER_KEYS.size + 1
        val parsedMarkers =
            ArrayList<ArKernelMeasurementFeedMarker>(
                RULER_MARKER_ROWS)
        for (index in 0 until RULER_MARKER_ROWS) {
            val lineNumber = markerStart + index + 1
            if (lineNumber >= lines.size) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_SCHEMA,
                    lineNumber, "Ruler scale is missing a marker row")
            }
            val lineText = lines[markerStart + index].trimEnd('\r')
            val lineError = rulerValidateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith(
                    RULER_MARKER_PREFIXES[index])) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_MARKER,
                    lineNumber, "Ruler marker row order is wrong")
            }
            val markerFields = lineText.substring(
                RULER_MARKER_PREFIXES[index].length).split('|')
            if (markerFields.size != 2) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_MARKER,
                    lineNumber,
                    "Ruler marker needs two coordinates")
            }
            val mx = rulerDec(markerFields[0], lineNumber)
            val my = rulerDec(markerFields[1], lineNumber)
            if (mx == null || my == null ||
                mx > RULER_MAX_COORD || my > RULER_MAX_COORD) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_RANGE,
                    lineNumber, "Ruler marker is out of bounds")
            }
            parsedMarkers += ArKernelMeasurementFeedMarker(mx, my)
        }
        val referenceDistance = rulerRound6(rulerDistance(
            parsedMarkers[0], parsedMarkers[1]))
        val targetDistance = rulerRound6(rulerDistance(
            parsedMarkers[2], parsedMarkers[3]))
        if (referenceDistance <= 0.0 || targetDistance <= 0.0) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_GEOMETRY,
                markerStart + 1, "Ruler marker pairs must be distinct")
        }
        if (!rulerClose(reference, referenceDistance) ||
            !rulerClose(target, targetDistance)) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_GEOMETRY,
                markerStart + 1,
                "Ruler distances do not match the marker rows")
        }
        val tickStart = markerStart + RULER_MARKER_ROWS
        if (lines.size != tickStart + tickCount + 1) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
                0, "Ruler scale line count does not match tick count")
        }
        val ticks = ArrayList<ArKernelRulerTickRow>(tickCount)
        for (index in 0 until tickCount) {
            val lineNumber = tickStart + index + 1
            val lineText = lines[tickStart + index].trimEnd('\r')
            val lineError = rulerValidateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("tick=")) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_TICK,
                    lineNumber,
                    "Ruler scale line is not a tick")
            }
            val fields = lineText.substringAfter("tick=").split('|')
            if (fields.size != RULER_ROW_FIELDS) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_TICK,
                    lineNumber,
                    "Ruler tick must have three fields")
            }
            val rowIndex = rulerUInt(fields[0])
                ?: return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_VALUE,
                    lineNumber, "Invalid ruler tick index")
            if (rowIndex != index) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_TICK,
                    lineNumber, "Ruler ticks are not contiguous")
            }
            val x = rulerDec(fields[1], lineNumber)
            val y = rulerDec(fields[2], lineNumber)
            if (x == null || y == null ||
                x > RULER_MAX_COORD || y > RULER_MAX_COORD) {
                return rulerReject(
                    ArKernelRulerScaleRejection.INVALID_RANGE,
                    lineNumber, "Ruler tick is out of bounds")
            }
            ticks += ArKernelRulerTickRow(rowIndex, x, y)
        }
        val endIndex = tickStart + tickCount
        val endText = lines[endIndex].trimEnd('\r')
        val endError = rulerValidateLine(endText, endIndex + 1)
        if (endError != null) return endError
        if (endText != RULER_END) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
                endIndex + 1, "Ruler scale must end with END")
        }
        if (values.getValue("tick_count_consistent") !=
            rulerBool(rulerTickConsistent(tickCount, expected))) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                lineByKey.getValue("tick_count_consistent"),
                "Tick consistency flag does not match the record")
        }
        if (!rulerClose(spacing, rulerMeanTickSpacing(ticks)) ||
            !rulerClose(regularity, rulerTickRegularity(ticks))) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_GEOMETRY,
                0, "Ruler tick statistics are inconsistent")
        }
        val expectedScale = rulerRound6(
            referenceDistance / folded)
        val expectedEstimate = rulerRound6(
            targetDistance / expectedScale)
        if (!rulerClose(scale, expectedScale) ||
            !rulerClose(estimate, expectedEstimate)) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_GEOMETRY,
                0, "Ruler screen-ratio geometry is inconsistent")
        }
        val bundle = try {
            ArKernelRulerScaleBundle(
                sessionId = values.getValue("session_id"),
                deviceToken = values.getValue("device_token"),
                captureIndex = captureIndex,
                framesObserved = framesObserved,
                framesAnalyzed = framesAnalyzed,
                sampleCount = sampleCount,
                sceneKind = sceneKind,
                condition = condition,
                heldOut = values.getValue("held_out") == "true",
                statedFoldedMeters = folded,
                ticks = ticks,
                referenceStart = parsedMarkers[0],
                referenceEnd = parsedMarkers[1],
                targetStart = parsedMarkers[2],
                targetEnd = parsedMarkers[3],
                referencePixelDistance = reference,
                targetPixelDistance = target,
                scalePxPerM = scale,
                estimatedTargetM = estimate,
            )
        } catch (error: IllegalArgumentException) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_VALUE,
                0, error.message ?: "Invalid ruler scale capture")
        }
        return ArKernelRulerScaleParseResult(
            bundle, ArKernelRulerScaleRejection.NONE, 0, "")
    }

    private fun rulerValidateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelRulerScaleParseResult? {
        if (lineText.length > RULER_MAX_LINE_CHARACTERS) {
            return rulerReject(
                ArKernelRulerScaleRejection.LINE_TOO_LONG,
                lineNumber,
                "Ruler scale line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return rulerReject(
                ArKernelRulerScaleRejection.EMPTY_LINE,
                lineNumber,
                "Ruler scale contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return rulerReject(
                ArKernelRulerScaleRejection.INVALID_SCHEMA,
                lineNumber,
                "Ruler scale lines must not contain whitespace")
        }
        return null
    }

    private fun rulerDec(value: String, lineNumber: Int): Double? {
        if (!RULER_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        if (!isRulerFinite(parsed)) return null
        return parsed
    }

    private fun rulerDec(value: String): Double? =
        rulerDec(value, 0)

    private fun rulerUInt(value: String): Int? =
        if (RULER_UNSIGNED.matches(value)) value.toIntOrNull()
        else null

    private fun rulerULong(value: String): Long? =
        if (RULER_UNSIGNED.matches(value)) value.toLongOrNull()
        else null

    private fun rulerBool(value: Boolean): String =
        if (value) "true" else "false"

    private fun rulerReject(
        rejection: ArKernelRulerScaleRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelRulerScaleParseResult =
        ArKernelRulerScaleParseResult(
            null, rejection, lineNumber, detail)

    private fun rulerCreateReject(
        rejection: ArKernelRulerScaleRejection,
        detail: String,
    ): ArKernelRulerScaleCreateResult =
        ArKernelRulerScaleCreateResult(null, rejection, detail)
}

/** In-memory tick detection over one bounded luma profile; no I/O. */
const val RULER_MIN_SAMPLES = 32
const val RULER_MAX_SAMPLES = 1_024
private const val RULER_MIN_CONTRAST = 10
private const val RULER_MERGE_GAP = 1
private const val RULER_TICK_TOLERANCE = 10

/**
 * Detects evenly spaced dark tick marks along one sampled luma profile.
 *
 * The profile is analysed **in memory and discarded**; no frame, image, pixel
 * array or file is produced. The detector is pure and deterministic: it uses
 * only integer arithmetic, so the same profile always yields the same ticks.
 * `regularity` describes how evenly spaced the detected ticks are – a
 * structural property of the detected pattern, not an accuracy judgement.
 */
object ArKernelRulerTickDetector {
    fun detect(luma: IntArray): ArKernelRulerTickDetection {
        require(luma.size in RULER_MIN_SAMPLES..RULER_MAX_SAMPLES) {
            "Luma profile size is out of bounds"
        }
        require(luma.all { it in 0..255 }) {
            "Luma sample is out of range"
        }
        val size = luma.size
        var sum = 0
        for (value in luma) sum += value
        val mean = sum / size
        var deviationSum = 0
        for (value in luma) {
            deviationSum += kotlin.math.abs(value - mean)
        }
        val meanDeviation = deviationSum / size
        val contrast = kotlin.math.max(
            RULER_MIN_CONTRAST, meanDeviation)
        val threshold = mean - contrast
        val centers = ArrayList<Int>()
        var index = 0
        while (index < size) {
            if (luma[index] <= threshold) {
                var end = index
                var positionSum = 0
                var count = 0
                while (end < size && luma[end] <= threshold) {
                    positionSum += end
                    count++
                    end++
                }
                centers.add(positionSum / count)
                index = end
            } else {
                index++
            }
        }
        val ticks = ArrayList<Int>()
        for (center in centers) {
            if (ticks.isNotEmpty() &&
                center - ticks.last() <= RULER_MERGE_GAP) {
                ticks[ticks.size - 1] = (ticks.last() + center) / 2
            } else {
                ticks.add(center)
            }
        }
        if (ticks.size < 2) {
            return ArKernelRulerTickDetection(
                ticks, size, 0.0, 0.0)
        }
        var spacingSum = 0
        for (i in 1 until ticks.size) {
            spacingSum += ticks[i] - ticks[i - 1]
        }
        val meanSpacing = spacingSum / (ticks.size - 1)
        var worstDeviation = 0
        for (i in 1 until ticks.size) {
            val deviation = kotlin.math.abs(
                (ticks[i] - ticks[i - 1]) - meanSpacing)
            if (deviation > worstDeviation) {
                worstDeviation = deviation
            }
        }
        val regularity = if (meanSpacing <= 0) {
            0.0
        } else {
            val raw = 1.0 - worstDeviation.toDouble() /
                meanSpacing.toDouble()
            when {
                raw < 0.0 -> 0.0
                raw > 1.0 -> 1.0
                else -> raw
            }
        }
        return ArKernelRulerTickDetection(
            ticks, size, rulerRound6(regularity),
            meanSpacing.toDouble())
    }

    /** Nearest detected tick sample index, or the original when none exist. */
    fun snap(index: Int, ticks: List<Int>): Int {
        if (ticks.isEmpty()) return index
        var best = ticks[0]
        var bestDistance = kotlin.math.abs(ticks[0] - index)
        for (tick in ticks) {
            val distance = kotlin.math.abs(tick - index)
            if (distance < bestDistance) {
                best = tick
                bestDistance = distance
            }
        }
        return best
    }
}

private fun isRulerFinite(value: Double): Boolean =
    !value.isNaN() && !value.isInfinite()

private fun rulerRound6(value: Double): Double =
    kotlin.math.round(value * 1_000_000.0) / 1_000_000.0

private fun rulerDistance(
    a: ArKernelMeasurementFeedMarker,
    b: ArKernelMeasurementFeedMarker,
): Double = kotlin.math.sqrt(
    (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))

private fun rulerClose(actual: Double, expected: Double): Boolean {
    val magnitude = kotlin.math.abs(expected)
    val scale = if (magnitude > 1.0) magnitude else 1.0
    return kotlin.math.abs(actual - expected) <= 0.000001 * scale
}

private fun rulerExpectedTicks(foldedMeters: Double): Int {
    val raw = kotlin.math.round(
        foldedMeters * RULER_CM_PER_M.toDouble()).toInt()
    return when {
        raw < 1 -> 1
        raw > RULER_MAX_TICKS -> RULER_MAX_TICKS
        else -> raw
    }
}

private fun rulerTickConsistent(count: Int, expected: Int): Boolean {
    val tolerance = kotlin.math.max(
        2, expected / RULER_TICK_TOLERANCE)
    return kotlin.math.abs(count - expected) <= tolerance
}

private fun rulerRowDistance(
    a: ArKernelRulerTickRow,
    b: ArKernelRulerTickRow,
): Double = kotlin.math.sqrt(
    (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))

private fun rulerMeanTickSpacing(
    ticks: List<ArKernelRulerTickRow>,
): Double {
    if (ticks.size < 2) return 0.0
    var total = 0.0
    for (index in 1 until ticks.size) {
        total += rulerRowDistance(
            ticks[index - 1], ticks[index])
    }
    return rulerRound6(total / (ticks.size - 1))
}

private fun rulerTickRegularity(
    ticks: List<ArKernelRulerTickRow>,
): Double {
    if (ticks.size < 2) return 0.0
    val count = ticks.size - 1
    var sum = 0.0
    for (index in 1 until ticks.size) {
        sum += rulerRowDistance(ticks[index - 1], ticks[index])
    }
    val mean = sum / count
    if (!isRulerFinite(mean) || mean <= 0.0) return 0.0
    var worst = 0.0
    for (index in 1 until ticks.size) {
        val deviation = kotlin.math.abs(
            rulerRowDistance(ticks[index - 1], ticks[index]) - mean)
        if (deviation > worst) worst = deviation
    }
    val raw = 1.0 - worst / mean
    return rulerRound6(
        if (raw < 0.0) 0.0 else if (raw > 1.0) 1.0 else raw)
}

private fun rulerGeometryConsistent(
    bundle: ArKernelRulerScaleBundle,
): Boolean {
    val reference = rulerRound6(rulerDistance(
        bundle.referenceStart, bundle.referenceEnd))
    val target = rulerRound6(rulerDistance(
        bundle.targetStart, bundle.targetEnd))
    val scale = rulerRound6(reference / bundle.statedFoldedMeters)
    val estimate = rulerRound6(target / scale)
    return reference > 0.0 && target > 0.0 &&
        rulerClose(bundle.referencePixelDistance, reference) &&
        rulerClose(bundle.targetPixelDistance, target) &&
        rulerClose(bundle.scalePxPerM, scale) &&
        rulerClose(bundle.estimatedTargetM, estimate)
}

/** Canonical non-negative decimal: up to nine integer, six fraction digits. */
private fun rulerDecimal(value: Double): String {
    require(isRulerFinite(value) && value >= 0.0) {
        "Ruler decimal is out of range"
    }
    val scaled = kotlin.math.round(value * 1_000_000.0).toLong()
    val integerPart = scaled / 1_000_000L
    val fractionText = (scaled % 1_000_000L).toString()
        .padStart(6, '0').trimEnd('0')
    return if (fractionText.isEmpty()) {
        integerPart.toString()
    } else {
        "$integerPart.$fractionText"
    }
}
