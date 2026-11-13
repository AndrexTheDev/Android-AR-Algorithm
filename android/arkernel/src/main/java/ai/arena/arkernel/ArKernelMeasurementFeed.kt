package ai.arena.arkernel

private const val FEED_HEADER = "ARKFEED1"
private const val FEED_VERSION = "1"
private const val FEED_QUALITY = "unverified"
private const val FEED_END = "END"
private const val FEED_MAX_INPUT_CHARACTERS = 16_384
private const val FEED_MAX_LINE_CHARACTERS = 256
private const val FEED_MAX_COORD = 4_096.0
private const val FEED_MIN_REFERENCE = 0.01
private const val FEED_MAX_REFERENCE = 100.0
private const val FEED_MAX_UNCERTAINTY = 1.0
private const val FEED_MAX_SCALE = 999_999_999.0
private const val FEED_MAX_FRAMES = 10_000_000L
private const val FEED_MAX_CAPTURE_INDEX = 9_999
private const val FEED_MARKER_COUNT = 4
private const val FEED_ROW_FIELDS = 3

private val FEED_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val FEED_UNSIGNED = Regex("0|[1-9][0-9]*")
private val FEED_DECIMAL = Regex(
    "(0|[1-9][0-9]{0,8})(\\.[0-9]{1,6})?")
private val FEED_KEYS = listOf(
    "version",
    "session_id",
    "device_token",
    "capture_index",
    "frames_observed",
    "frames_exported",
    "camera_preview_opt_in",
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
    "scene_kind",
    "condition",
    "held_out",
    "reference_m",
    "reference_uncertainty_m",
    "reference_pixel_distance",
    "target_pixel_distance",
    "scale_px_per_m",
    "estimated_target_m",
    "quality_grade",
)
private val FEED_BOOLEAN_KEYS = listOf(
    "camera_preview_opt_in",
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
    "held_out",
)
private val FEED_TRUE_KEYS = listOf(
    "camera_preview_opt_in",
    "image_export_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "physical_validation_disabled",
    "per_device_calibration",
    "universal_calibration_disabled",
    "manual_upload_only",
    "user_confirmed",
)
private val FEED_FALSE_KEYS = listOf("metric_accuracy_claimed")
private val FEED_MARKER_PREFIXES = listOf(
    "reference_marker=0|",
    "reference_marker=1|",
    "target_marker=0|",
    "target_marker=1|",
)

enum class ArKernelMeasurementFeedRejection {
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
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_DECIMAL,
    INVALID_RANGE,
    INVALID_COUNT,
    INVALID_KIND,
    INVALID_CONDITION,
    INVALID_MARKER,
    INVALID_ORDER,
    INVALID_GEOMETRY,
    IMAGE_EXPORT_ENABLED,
    FRAME_EXPORT_NONZERO,
}

/** One on-screen marker in overlay-view pixel coordinates. */
data class ArKernelMeasurementFeedMarker(
    val x: Double,
    val y: Double,
) {
    init {
        require(isFeedFinite(x) && isFeedFinite(y)) {
            "Measurement feed marker is not finite"
        }
        require(x >= 0.0 && x <= FEED_MAX_COORD &&
            y >= 0.0 && y <= FEED_MAX_COORD) {
            "Measurement feed marker is out of bounds"
        }
    }
}

/**
 * One bounded, opt-in live camera measurement capture.
 *
 * The user sees a camera preview, places two markers on a known physical
 * reference and two markers on the target, and supplies the reference length
 * measured independently with a folding rule. The adapter records only
 * numbers: overlay-view marker coordinates, derived pixel distances, a
 * screen-ratio scale and a clearly labelled *estimate*. No frame, image,
 * pixel array, pose or trajectory is exported; `frames_exported` is always
 * zero. The estimate assumes a fronto-parallel plane at equal depth and is
 * explicitly **not** a metric measurement: `metric_accuracy_claimed=false`.
 *
 * Eleven fail-closed invariants keep camera opt-in, disabled image export,
 * disabled automatic/universal calibration, disabled signature verification,
 * disabled physical validation, manual upload only and explicit user
 * confirmation mandatory. This is a structural capture record, not an
 * accuracy assessment, not a centimetre validation, not a calibration and
 * not a quality grade. The grade stays `unverified`.
 */
data class ArKernelMeasurementFeedBundle(
    val sessionId: String,
    val deviceToken: String,
    val captureIndex: Int,
    val framesObserved: Long,
    val sceneKind: ArKernelCampaignSceneKind,
    val condition: ArKernelMeasurementCondition,
    val heldOut: Boolean,
    val referenceMeters: Double,
    val referenceUncertaintyMeters: Double,
    val markers: List<ArKernelMeasurementFeedMarker>,
    val referencePixelDistance: Double,
    val targetPixelDistance: Double,
    val scalePxPerM: Double,
    val estimatedTargetM: Double,
    val cameraPreviewOptIn: Boolean = true,
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
    val framesExported: Int = 0,
    val qualityGrade: String = FEED_QUALITY,
) {
    init {
        require(FEED_TOKEN.matches(sessionId) &&
            FEED_TOKEN.matches(deviceToken)) {
            "Invalid measurement feed token"
        }
        require(captureIndex in 0..FEED_MAX_CAPTURE_INDEX) {
            "Invalid measurement feed capture index"
        }
        require(framesObserved in 0..FEED_MAX_FRAMES) {
            "Invalid measurement feed frame count"
        }
        require(markers.size == FEED_MARKER_COUNT) {
            "Measurement feed requires four markers"
        }
        require(isFeedFinite(referenceMeters) &&
            referenceMeters >= FEED_MIN_REFERENCE &&
            referenceMeters <= FEED_MAX_REFERENCE) {
            "Reference length is out of bounds"
        }
        require(isFeedFinite(referenceUncertaintyMeters) &&
            referenceUncertaintyMeters >= 0.0 &&
            referenceUncertaintyMeters <= FEED_MAX_UNCERTAINTY) {
            "Reference uncertainty is out of bounds"
        }
        require(isFeedFinite(referencePixelDistance) &&
            referencePixelDistance > 0.0 &&
            isFeedFinite(targetPixelDistance) &&
            targetPixelDistance > 0.0 &&
            isFeedFinite(scalePxPerM) &&
            scalePxPerM > 0.0 &&
            scalePxPerM <= FEED_MAX_SCALE &&
            isFeedFinite(estimatedTargetM) &&
            estimatedTargetM > 0.0) {
            "Measurement feed geometry is out of bounds"
        }
        require(cameraPreviewOptIn && imageExportDisabled &&
            automaticCalibrationDisabled &&
            automaticUploadDisabled &&
            signatureVerificationDisabled &&
            physicalValidationDisabled &&
            !metricAccuracyClaimed &&
            perDeviceCalibration &&
            universalCalibrationDisabled &&
            manualUploadOnly && userConfirmed) {
            "Measurement feed must remain fail-closed"
        }
        require(framesExported == 0) {
            "Measurement feed must not export frames"
        }
        require(qualityGrade == FEED_QUALITY) {
            "Measurement feed cannot assert assessed quality"
        }
    }
}

data class ArKernelMeasurementFeedParseResult(
    val bundle: ArKernelMeasurementFeedBundle?,
    val rejection: ArKernelMeasurementFeedRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelMeasurementFeedRejection.NONE &&
            bundle != null
}

data class ArKernelMeasurementFeedCreateResult(
    val bundle: ArKernelMeasurementFeedBundle?,
    val rejection: ArKernelMeasurementFeedRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelMeasurementFeedRejection.NONE &&
            bundle != null
}

/** Structural capture facts only; neither accuracy nor a quality grade. */
data class ArKernelMeasurementFeedReport(
    val markerCount: Int,
    val framesObserved: Long,
    val framesExported: Int,
    val referencePixelDistance: Double,
    val targetPixelDistance: Double,
    val scalePxPerM: Double,
    val estimatedTargetM: Double,
    val heldOut: Boolean,
    val invariantsHeld: Boolean,
    val geometryConsistent: Boolean,
    val qualityGrade: String = FEED_QUALITY,
) {
    val captureReady: Boolean
        get() = markerCount == FEED_MARKER_COUNT &&
            framesExported == 0 && invariantsHeld &&
            geometryConsistent
}

/**
 * Canonical `ARKFEED1` text adapter.
 *
 * `create()` computes only a screen-ratio scale and an explicitly
 * non-metric estimate from four user-placed markers and one independently
 * measured reference length. `parse()` re-checks all twenty-seven exact
 * metadata keys, eleven invariants, four ordered marker rows and the derived
 * geometry with a small numeric tolerance. `verify()` is structural only;
 * there is no source text, no signature, no image and no physical validation
 * to re-verify. The adapter performs no file I/O, no upload, no camera
 * access, no automatic or universal calibration and no quality assessment.
 * The grade stays `unverified`.
 */
object ArKernelMeasurementFeedAdapter {
    fun create(
        sessionId: String,
        deviceToken: String,
        captureIndex: Int,
        framesObserved: Long,
        sceneKind: ArKernelCampaignSceneKind,
        condition: ArKernelMeasurementCondition,
        heldOut: Boolean,
        referenceMeters: Double,
        referenceUncertaintyMeters: Double,
        markers: List<ArKernelMeasurementFeedMarker>,
        userConfirmed: Boolean,
    ): ArKernelMeasurementFeedCreateResult {
        if (!FEED_TOKEN.matches(sessionId) ||
            !FEED_TOKEN.matches(deviceToken)) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.INVALID_TOKEN,
                "Invalid measurement feed token")
        }
        if (captureIndex !in 0..FEED_MAX_CAPTURE_INDEX) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.INVALID_COUNT,
                "Capture index is out of bounds")
        }
        if (framesObserved !in 0..FEED_MAX_FRAMES) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.INVALID_COUNT,
                "Frame count is out of bounds")
        }
        if (markers.size != FEED_MARKER_COUNT) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.INVALID_MARKER,
                "Exactly four markers are required")
        }
        if (!isFeedFinite(referenceMeters) ||
            referenceMeters < FEED_MIN_REFERENCE ||
            referenceMeters > FEED_MAX_REFERENCE ||
            !isFeedFinite(referenceUncertaintyMeters) ||
            referenceUncertaintyMeters < 0.0 ||
            referenceUncertaintyMeters > FEED_MAX_UNCERTAINTY) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
                "Reference length or uncertainty is out of bounds")
        }
        if (!userConfirmed) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.UNCONFIRMED_CAPTURE,
                "Every camera capture requires explicit confirmation")
        }
        val reference = feedRound6(referenceMeters)
        val uncertainty = feedRound6(referenceUncertaintyMeters)
        val rounded = markers.map { marker ->
            ArKernelMeasurementFeedMarker(
                feedRound6(marker.x), feedRound6(marker.y))
        }
        val referencePixels = feedRound6(
            feedDistance(rounded[0], rounded[1]))
        val targetPixels = feedRound6(
            feedDistance(rounded[2], rounded[3]))
        if (referencePixels <= 0.0 || targetPixels <= 0.0) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.INVALID_GEOMETRY,
                "Marker pairs must be distinct")
        }
        val scale = feedRound6(referencePixels / reference)
        val estimate = feedRound6(targetPixels / scale)
        if (scale <= 0.0 || scale > FEED_MAX_SCALE ||
            estimate <= 0.0) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
                "Derived screen-ratio values are out of bounds")
        }
        val bundle = try {
            ArKernelMeasurementFeedBundle(
                sessionId = sessionId,
                deviceToken = deviceToken,
                captureIndex = captureIndex,
                framesObserved = framesObserved,
                sceneKind = sceneKind,
                condition = condition,
                heldOut = heldOut,
                referenceMeters = reference,
                referenceUncertaintyMeters = uncertainty,
                markers = rounded,
                referencePixelDistance = referencePixels,
                targetPixelDistance = targetPixels,
                scalePxPerM = scale,
                estimatedTargetM = estimate,
                userConfirmed = userConfirmed,
            )
        } catch (error: IllegalArgumentException) {
            return feedCreateReject(
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
                error.message ?: "Invalid measurement feed capture")
        }
        val text = encode(bundle)
        val parsed = parse(text)
        return ArKernelMeasurementFeedCreateResult(
            parsed.bundle, parsed.rejection, parsed.detail)
    }

    fun encode(bundle: ArKernelMeasurementFeedBundle): String {
        val lines = ArrayList<String>(
            FEED_KEYS.size + FEED_MARKER_COUNT + 2)
        lines.add(FEED_HEADER)
        lines.add("version=$FEED_VERSION")
        lines.add("session_id=${bundle.sessionId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("capture_index=${bundle.captureIndex}")
        lines.add("frames_observed=${bundle.framesObserved}")
        lines.add("frames_exported=${bundle.framesExported}")
        lines.add("camera_preview_opt_in=" +
            feedBool(bundle.cameraPreviewOptIn))
        lines.add("image_export_disabled=" +
            feedBool(bundle.imageExportDisabled))
        lines.add("automatic_calibration_disabled=" +
            feedBool(bundle.automaticCalibrationDisabled))
        lines.add("automatic_upload_disabled=" +
            feedBool(bundle.automaticUploadDisabled))
        lines.add("signature_verification_disabled=" +
            feedBool(bundle.signatureVerificationDisabled))
        lines.add("physical_validation_disabled=" +
            feedBool(bundle.physicalValidationDisabled))
        lines.add("metric_accuracy_claimed=" +
            feedBool(bundle.metricAccuracyClaimed))
        lines.add("per_device_calibration=" +
            feedBool(bundle.perDeviceCalibration))
        lines.add("universal_calibration_disabled=" +
            feedBool(bundle.universalCalibrationDisabled))
        lines.add("manual_upload_only=" +
            feedBool(bundle.manualUploadOnly))
        lines.add("user_confirmed=" +
            feedBool(bundle.userConfirmed))
        lines.add("scene_kind=${bundle.sceneKind.wireName()}")
        lines.add("condition=${bundle.condition.wireName()}")
        lines.add("held_out=" + feedBool(bundle.heldOut))
        lines.add("reference_m=" +
            feedDecimal(bundle.referenceMeters))
        lines.add("reference_uncertainty_m=" +
            feedDecimal(bundle.referenceUncertaintyMeters))
        lines.add("reference_pixel_distance=" +
            feedDecimal(bundle.referencePixelDistance))
        lines.add("target_pixel_distance=" +
            feedDecimal(bundle.targetPixelDistance))
        lines.add("scale_px_per_m=" +
            feedDecimal(bundle.scalePxPerM))
        lines.add("estimated_target_m=" +
            feedDecimal(bundle.estimatedTargetM))
        lines.add("quality_grade=${bundle.qualityGrade}")
        for (index in 0 until FEED_MARKER_COUNT) {
            val marker = bundle.markers[index]
            lines.add(
                FEED_MARKER_PREFIXES[index] +
                    feedDecimal(marker.x) + "|" +
                    feedDecimal(marker.y))
        }
        lines.add(FEED_END)
        val text = lines.joinToString("\n")
        require(text.length <= FEED_MAX_INPUT_CHARACTERS) {
            "Measurement feed record exceeds its bounded size"
        }
        require(lines.all {
            it.length <= FEED_MAX_LINE_CHARACTERS
        }) { "Measurement feed line exceeds its bounded length" }
        return text
    }

    fun summarize(bundle: ArKernelMeasurementFeedBundle):
        ArKernelMeasurementFeedReport =
        ArKernelMeasurementFeedReport(
            markerCount = bundle.markers.size,
            framesObserved = bundle.framesObserved,
            framesExported = bundle.framesExported,
            referencePixelDistance =
                bundle.referencePixelDistance,
            targetPixelDistance = bundle.targetPixelDistance,
            scalePxPerM = bundle.scalePxPerM,
            estimatedTargetM = bundle.estimatedTargetM,
            heldOut = bundle.heldOut,
            invariantsHeld = bundle.imageExportDisabled &&
                bundle.automaticCalibrationDisabled &&
                bundle.automaticUploadDisabled &&
                bundle.userConfirmed &&
                !bundle.metricAccuracyClaimed,
            geometryConsistent = feedGeometryConsistent(bundle),
        )

    /** Structural verification only; no source text exists to re-check. */
    fun verify(text: String): ArKernelMeasurementFeedParseResult =
        parse(text)

    fun parse(text: String): ArKernelMeasurementFeedParseResult {
        if (text.length > FEED_MAX_INPUT_CHARACTERS) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INPUT_TOO_LARGE,
                0, "Measurement feed input is too large")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return feedReject(
                ArKernelMeasurementFeedRejection.EMPTY_RECORD,
                0, "Measurement feed input is empty")
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
            return feedReject(
                ArKernelMeasurementFeedRejection.EMPTY_RECORD,
                0, "Measurement feed has no record")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return feedReject(
                ArKernelMeasurementFeedRejection.EMPTY_LINE,
                0, "Measurement feed has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != FEED_HEADER || header.isBlank() ||
            header.length > FEED_MAX_LINE_CHARACTERS) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_HEADER,
                1, "Measurement feed header is invalid")
        }
        val expectedLines = FEED_KEYS.size + FEED_MARKER_COUNT + 2
        if (lines.size < expectedLines) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
                0, "Measurement feed is too short")
        }
        if (lines.size != expectedLines) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
                0, "Measurement feed line count is wrong")
        }
        val lineByKey = FEED_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(FEED_KEYS.size)
        for (index in FEED_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = feedValidateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return feedReject(
                    ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Measurement feed metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != FEED_KEYS[index]) {
                return feedReject(
                    ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Measurement feed metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != FEED_VERSION) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported measurement feed version")
        }
        if (values.getValue("quality_grade") != FEED_QUALITY) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Measurement feed cannot assert assessed quality")
        }
        for (key in FEED_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return feedReject(
                    ArKernelMeasurementFeedRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Measurement feed flag must be a boolean")
            }
            if (key in FEED_TRUE_KEYS && value != "true") {
                val rejection = if (key == "user_confirmed") {
                    ArKernelMeasurementFeedRejection.UNCONFIRMED_CAPTURE
                } else {
                    ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT
                }
                return feedReject(
                    rejection, lineByKey.getValue(key),
                    "Measurement feed invariant must be true")
            }
            if (key in FEED_FALSE_KEYS && value != "false") {
                return feedReject(
                    ArKernelMeasurementFeedRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Metric accuracy must not be claimed")
            }
        }
        for (key in listOf("session_id", "device_token")) {
            if (!FEED_TOKEN.matches(values.getValue(key))) {
                return feedReject(
                    ArKernelMeasurementFeedRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid measurement feed token")
            }
        }
        val captureIndex = feedUInt(
            values.getValue("capture_index"))
            ?: return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
                lineByKey.getValue("capture_index"),
                "Invalid capture index")
        if (captureIndex > FEED_MAX_CAPTURE_INDEX) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_COUNT,
                lineByKey.getValue("capture_index"),
                "Capture index is out of bounds")
        }
        val framesObserved = feedULong(
            values.getValue("frames_observed"))
            ?: return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
                lineByKey.getValue("frames_observed"),
                "Invalid frame count")
        if (framesObserved > FEED_MAX_FRAMES) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_COUNT,
                lineByKey.getValue("frames_observed"),
                "Frame count is out of bounds")
        }
        val framesExported = feedUInt(
            values.getValue("frames_exported"))
            ?: return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
                lineByKey.getValue("frames_exported"),
                "Invalid exported frame count")
        if (framesExported != 0) {
            return feedReject(
                ArKernelMeasurementFeedRejection.FRAME_EXPORT_NONZERO,
                lineByKey.getValue("frames_exported"),
                "Measurement feed must not export frames")
        }
        val sceneKind = ArKernelCampaignSceneKind.fromWire(
            values.getValue("scene_kind"))
            ?: return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_KIND,
                lineByKey.getValue("scene_kind"),
                "Invalid measurement feed scene kind")
        val condition = ArKernelMeasurementCondition.fromWire(
            values.getValue("condition"))
            ?: return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_CONDITION,
                lineByKey.getValue("condition"),
                "Invalid measurement feed condition")
        if (values.getValue("held_out") != "true" &&
            values.getValue("held_out") != "false") {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_BOOLEAN,
                lineByKey.getValue("held_out"),
                "Held-out flag must be a boolean")
        }
        val referenceMeters = feedDec(
            values.getValue("reference_m"),
            lineByKey.getValue("reference_m"))
        if (referenceMeters == null) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_DECIMAL,
                lineByKey.getValue("reference_m"),
                "Invalid reference length")
        }
        if (referenceMeters < FEED_MIN_REFERENCE ||
            referenceMeters > FEED_MAX_REFERENCE) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
                lineByKey.getValue("reference_m"),
                "Reference length is out of bounds")
        }
        val uncertainty = feedDec(
            values.getValue("reference_uncertainty_m"),
            lineByKey.getValue("reference_uncertainty_m"))
        if (uncertainty == null || uncertainty > FEED_MAX_UNCERTAINTY) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
                lineByKey.getValue("reference_uncertainty_m"),
                "Reference uncertainty is out of bounds")
        }
        val referencePixels = feedDec(
            values.getValue("reference_pixel_distance"),
            lineByKey.getValue("reference_pixel_distance"))
        val targetPixels = feedDec(
            values.getValue("target_pixel_distance"),
            lineByKey.getValue("target_pixel_distance"))
        val scale = feedDec(
            values.getValue("scale_px_per_m"),
            lineByKey.getValue("scale_px_per_m"))
        val estimate = feedDec(
            values.getValue("estimated_target_m"),
            lineByKey.getValue("estimated_target_m"))
        if (referencePixels == null || targetPixels == null ||
            scale == null || estimate == null) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_DECIMAL,
                0, "Invalid derived measurement feed decimal")
        }
        if (referencePixels <= 0.0 || targetPixels <= 0.0 ||
            scale <= 0.0 || scale > FEED_MAX_SCALE ||
            estimate <= 0.0) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_RANGE,
                0, "Derived measurement feed values are out of bounds")
        }
        val markerStart = FEED_KEYS.size + 1
        val markers =
            ArrayList<ArKernelMeasurementFeedMarker>(
                FEED_MARKER_COUNT)
        for (index in 0 until FEED_MARKER_COUNT) {
            val lineNumber = markerStart + index + 1
            val lineText = lines[markerStart + index].trimEnd('\r')
            val lineError = feedValidateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith(
                    FEED_MARKER_PREFIXES[index])) {
                return feedReject(
                    ArKernelMeasurementFeedRejection.INVALID_ORDER,
                    lineNumber,
                    "Measurement feed marker order is wrong")
            }
            val fields = lineText.substring(
                FEED_MARKER_PREFIXES[index].length).split('|')
            if (fields.size != FEED_ROW_FIELDS - 1) {
                return feedReject(
                    ArKernelMeasurementFeedRejection.INVALID_MARKER,
                    lineNumber,
                    "Measurement feed marker needs two coordinates")
            }
            val x = feedDec(fields[0], lineNumber)
            val y = feedDec(fields[1], lineNumber)
            if (x == null || y == null ||
                x > FEED_MAX_COORD || y > FEED_MAX_COORD) {
                return feedReject(
                    ArKernelMeasurementFeedRejection.INVALID_RANGE,
                    lineNumber,
                    "Measurement feed marker is out of bounds")
            }
            markers += ArKernelMeasurementFeedMarker(x, y)
        }
        val endIndex = markerStart + FEED_MARKER_COUNT
        val endText = lines[endIndex].trimEnd('\r')
        val endError = feedValidateLine(endText, endIndex + 1)
        if (endError != null) return endError
        if (endText != FEED_END) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
                endIndex + 1,
                "Measurement feed must end with END")
        }
        val expectedReference = feedRound6(
            feedDistance(markers[0], markers[1]))
        val expectedTarget = feedRound6(
            feedDistance(markers[2], markers[3]))
        val expectedScale = feedRound6(
            expectedReference / referenceMeters)
        val expectedEstimate = feedRound6(
            expectedTarget / expectedScale)
        if (expectedReference <= 0.0 || expectedTarget <= 0.0 ||
            !feedClose(referencePixels, expectedReference) ||
            !feedClose(targetPixels, expectedTarget) ||
            !feedClose(scale, expectedScale) ||
            !feedClose(estimate, expectedEstimate)) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_GEOMETRY,
                0, "Measurement feed geometry is inconsistent")
        }
        val bundle = try {
            ArKernelMeasurementFeedBundle(
                sessionId = values.getValue("session_id"),
                deviceToken = values.getValue("device_token"),
                captureIndex = captureIndex,
                framesObserved = framesObserved,
                sceneKind = sceneKind,
                condition = condition,
                heldOut = values.getValue("held_out") == "true",
                referenceMeters = referenceMeters,
                referenceUncertaintyMeters = uncertainty,
                markers = markers,
                referencePixelDistance = referencePixels,
                targetPixelDistance = targetPixels,
                scalePxPerM = scale,
                estimatedTargetM = estimate,
            )
        } catch (error: IllegalArgumentException) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_VALUE,
                0, error.message ?: "Invalid measurement feed capture")
        }
        return ArKernelMeasurementFeedParseResult(
            bundle,
            ArKernelMeasurementFeedRejection.NONE, 0, "")
    }

    private fun feedValidateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelMeasurementFeedParseResult? {
        if (lineText.length > FEED_MAX_LINE_CHARACTERS) {
            return feedReject(
                ArKernelMeasurementFeedRejection.LINE_TOO_LONG,
                lineNumber,
                "Measurement feed line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return feedReject(
                ArKernelMeasurementFeedRejection.EMPTY_LINE,
                lineNumber,
                "Measurement feed contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return feedReject(
                ArKernelMeasurementFeedRejection.INVALID_SCHEMA,
                lineNumber,
                "Measurement feed lines must not contain whitespace")
        }
        return null
    }

    private fun feedDec(value: String, lineNumber: Int): Double? {
        if (!FEED_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        if (!isFeedFinite(parsed)) return null
        return parsed
    }

    private fun feedUInt(value: String): Int? =
        if (FEED_UNSIGNED.matches(value)) value.toIntOrNull()
        else null

    private fun feedULong(value: String): Long? =
        if (FEED_UNSIGNED.matches(value)) value.toLongOrNull()
        else null

    private fun feedBool(value: Boolean): String =
        if (value) "true" else "false"

    private fun feedReject(
        rejection: ArKernelMeasurementFeedRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelMeasurementFeedParseResult =
        ArKernelMeasurementFeedParseResult(
            null, rejection, lineNumber, detail)

    private fun feedCreateReject(
        rejection: ArKernelMeasurementFeedRejection,
        detail: String,
    ): ArKernelMeasurementFeedCreateResult =
        ArKernelMeasurementFeedCreateResult(
            null, rejection, detail)
}

private fun isFeedFinite(value: Double): Boolean =
    !value.isNaN() && !value.isInfinite()

private fun feedRound6(value: Double): Double =
    kotlin.math.round(value * 1_000_000.0) / 1_000_000.0

private fun feedDistance(
    a: ArKernelMeasurementFeedMarker,
    b: ArKernelMeasurementFeedMarker,
): Double = kotlin.math.sqrt(
    (b.x - a.x) * (b.x - a.x) +
        (b.y - a.y) * (b.y - a.y))

private fun feedClose(actual: Double, expected: Double): Boolean {
    val magnitude = kotlin.math.abs(expected)
    val scale = if (magnitude > 1.0) magnitude else 1.0
    return kotlin.math.abs(actual - expected) <=
        0.000001 * scale
}

private fun feedGeometryConsistent(
    bundle: ArKernelMeasurementFeedBundle,
): Boolean {
    val reference = feedRound6(feedDistance(
        bundle.markers[0], bundle.markers[1]))
    val target = feedRound6(feedDistance(
        bundle.markers[2], bundle.markers[3]))
    val scale = feedRound6(reference / bundle.referenceMeters)
    val estimate = feedRound6(target / scale)
    return reference > 0.0 && target > 0.0 &&
        feedClose(bundle.referencePixelDistance, reference) &&
        feedClose(bundle.targetPixelDistance, target) &&
        feedClose(bundle.scalePxPerM, scale) &&
        feedClose(bundle.estimatedTargetM, estimate)
}

/** Canonical bounded decimal: up to nine integer and six fraction digits. */
private fun feedDecimal(value: Double): String {
    require(isFeedFinite(value)) {
        "Measurement feed decimal is not finite"
    }
    val magnitude = kotlin.math.abs(value)
    val scaled = kotlin.math.round(
        magnitude * 1_000_000.0).toLong()
    val integerPart = scaled / 1_000_000L
    val fractionText = (scaled % 1_000_000L).toString()
        .padStart(6, '0').trimEnd('0')
    val body = if (fractionText.isEmpty()) {
        integerPart.toString()
    } else {
        "$integerPart.$fractionText"
    }
    return if (value < 0.0) "-$body" else body
}
