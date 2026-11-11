package ai.arena.arkernel

private const val CALIB_HEADER = "ARKCALIB1"
private const val CALIB_VERSION = "1"
private const val CALIB_QUALITY = "unverified"
private const val CALIB_END = "END"
private const val CALIB_MAX_INPUT_CHARACTERS = 16_384
private const val CALIB_MAX_LINE_CHARACTERS = 256
private const val CALIB_MAX_OBSERVATIONS = 16
private const val CALIB_MAX_POSES = 32
private const val CALIB_MAX_METRES = 100.0
private const val CALIB_MAX_UNCERTAINTY = 1.0
private const val CALIB_DIMENSION_BIN_M = 0.25
private const val CALIB_MEAN_ERROR_GATE_M = 0.01
private const val CALIB_MAX_ERROR_GATE_M = 0.01
private const val CALIB_MAX_RELATIVE_ERROR_GATE = 0.02
private const val CALIB_MIN_OBSERVATIONS = 3
private const val CALIB_MIN_DIMENSION_BINS = 2
private const val CALIB_QUATERNION_TOLERANCE = 1e-6

private val CALIB_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val CALIB_UNSIGNED = Regex("0|[1-9][0-9]*")
private val CALIB_DECIMAL = Regex("(0|[1-9][0-9]{0,3})(\\.[0-9]{1,6})?")
private val CALIB_SIGNED_DECIMAL =
    Regex("-?(0|[1-9][0-9]{0,3})(\\.[0-9]{1,6})?")
private val CALIB_KEYS = listOf(
    "version",
    "session_id",
    "device_token",
    "capture_opt_in",
    "pose_capture_opt_in",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "observation_count",
    "pose_count",
    "asset_extent_m",
    "quality_grade",
)
private val CALIB_BOOLEAN_KEYS = listOf(
    "capture_opt_in",
    "pose_capture_opt_in",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
)

enum class ArKernelCalibrationProvenance {
    SCALE_BAR,
    SURVEY,
    SENSOR_GROUND_TRUTH;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelCalibrationProvenance? =
            entries.firstOrNull { it.wireName() == value }
    }
}

enum class ArKernelCalibrationRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_EVIDENCE,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_PROVENANCE,
    INVALID_DECIMAL,
    INVALID_RANGE,
    INVALID_TIMESTAMP,
    INVALID_QUATERNION,
    INVALID_COUNT,
    INVALID_ORDER,
    HELD_OUT_REQUIRED,
    DUPLICATE_EVIDENCE_ID,
    METRIC_POSITION_REQUIRED_ZERO,
}

/** One independent physical scale observation supplied by the host. */
data class ArKernelCalibrationObservation(
    val index: Int,
    val evidenceId: String,
    val provenance: ArKernelCalibrationProvenance,
    val referenceMeters: Double,
    val measuredMeters: Double,
    val referenceUncertaintyMeters: Double,
    val heldOut: Boolean,
) {
    init {
        require(index >= 0) { "Invalid calibration observation index" }
        require(CALIB_TOKEN.matches(evidenceId)) {
            "Invalid calibration evidence ID"
        }
        require(isFinite(referenceMeters) && isFinite(measuredMeters) &&
            isFinite(referenceUncertaintyMeters)) {
            "Calibration observation contains a non-finite value"
        }
        require(referenceMeters > 0.0 &&
            referenceMeters <= CALIB_MAX_METRES) {
            "Reference distance is out of bounds"
        }
        require(measuredMeters >= 0.0 &&
            measuredMeters <= CALIB_MAX_METRES) {
            "Measured distance is out of bounds"
        }
        require(referenceUncertaintyMeters >= 0.0 &&
            referenceUncertaintyMeters <= CALIB_MAX_UNCERTAINTY) {
            "Reference uncertainty is out of bounds"
        }
        require(heldOut) {
            "Calibration evidence must be held out"
        }
    }

    val absoluteErrorMeters: Double
        get() = kotlin.math.abs(measuredMeters - referenceMeters)

    val relativeError: Double
        get() = absoluteErrorMeters / referenceMeters

    val uncertaintyAdjustedErrorMeters: Double
        get() = absoluteErrorMeters + referenceUncertaintyMeters

    val dimensionBin: Int
        get() = (referenceMeters / CALIB_DIMENSION_BIN_M).toInt()
}

/**
 * One bounded host-supplied pose/orientation sample.
 *
 * When [metricPositionKnown] is false, the translation must be exactly zero:
 * a device rotation-vector sample carries a real orientation but no metric
 * world position. Nothing in this class infers or invents a metric position.
 */
data class ArKernelCalibrationPose(
    val index: Int,
    val timestampNs: Long,
    val txMeters: Double,
    val tyMeters: Double,
    val tzMeters: Double,
    val qw: Double,
    val qx: Double,
    val qy: Double,
    val qz: Double,
    val uncertainty: Double,
    val metricPositionKnown: Boolean,
) {
    init {
        require(index >= 0) { "Invalid calibration pose index" }
        require(timestampNs > 0L) {
            "Invalid calibration pose timestamp"
        }
        require(isFinite(txMeters) && isFinite(tyMeters) &&
            isFinite(tzMeters) && isFinite(qw) && isFinite(qx) &&
            isFinite(qy) && isFinite(qz) && isFinite(uncertainty)) {
            "Calibration pose contains a non-finite value"
        }
        require(kotlin.math.abs(txMeters) <= CALIB_MAX_METRES &&
            kotlin.math.abs(tyMeters) <= CALIB_MAX_METRES &&
            kotlin.math.abs(tzMeters) <= CALIB_MAX_METRES) {
            "Calibration pose translation is out of bounds"
        }
        require(uncertainty >= 0.0 &&
            uncertainty <= CALIB_MAX_UNCERTAINTY) {
            "Calibration pose uncertainty is out of bounds"
        }
        require(listOf(qw, qx, qy, qz).all {
            kotlin.math.abs(it) <= 1.0
        }) { "Calibration quaternion component is out of bounds" }
        val normSquared = qw * qw + qx * qx + qy * qy + qz * qz
        require(kotlin.math.abs(normSquared - 1.0) <=
            CALIB_QUATERNION_TOLERANCE) {
            "Calibration quaternion is not unit length"
        }
        if (!metricPositionKnown) {
            require(txMeters == 0.0 && tyMeters == 0.0 &&
                tzMeters == 0.0) {
                "Orientation-only pose must not assert a metric position"
            }
        }
    }
}

/**
 * Bounded host-owned physical calibration capture bundle.
 *
 * All six invariants are mandatory: explicit capture and pose-capture opt-in,
 * no images, no automatic calibration, no automatic upload and a permanent
 * `unverified` grade. Observations and poses are host-supplied; nothing is
 * measured, inferred or invented here.
 */
data class ArKernelCalibrationEvidenceBundle(
    val sessionId: String,
    val deviceToken: String,
    val observations: List<ArKernelCalibrationObservation>,
    val poses: List<ArKernelCalibrationPose>,
    val assetExtentMeters: Double,
    val captureOptIn: Boolean = true,
    val poseCaptureOptIn: Boolean = true,
    val imagesDisabled: Boolean = true,
    val automaticCalibrationDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val qualityGrade: String = CALIB_QUALITY,
) {
    init {
        require(CALIB_TOKEN.matches(sessionId) &&
            CALIB_TOKEN.matches(deviceToken)) {
            "Invalid calibration session or device token"
        }
        require(isFinite(assetExtentMeters) &&
            assetExtentMeters > 0.0 &&
            assetExtentMeters <= CALIB_MAX_METRES) {
            "Invalid calibration asset extent"
        }
        require(captureOptIn && poseCaptureOptIn && imagesDisabled &&
            automaticCalibrationDisabled && automaticUploadDisabled) {
            "Calibration evidence must remain fail-closed"
        }
        require(qualityGrade == CALIB_QUALITY) {
            "Calibration evidence cannot assert assessed quality"
        }
        require(observations.size in 1..CALIB_MAX_OBSERVATIONS &&
            poses.size <= CALIB_MAX_POSES) {
            "Invalid calibration evidence row counts"
        }
        require(observations.withIndex().all {
            it.value.index == it.index
        }) { "Calibration observations are not contiguous" }
        require(poses.withIndex().all {
            it.value.index == it.index
        }) { "Calibration poses are not contiguous" }
        require(observations.map { it.evidenceId }.toSet().size ==
            observations.size) {
            "Duplicate calibration evidence ID"
        }
        require(poses.zipWithNext().all { (a, b) ->
            b.timestampNs > a.timestampNs
        }) { "Calibration pose timestamps are not increasing" }
    }
}

data class ArKernelCalibrationEvidenceParseResult(
    val bundle: ArKernelCalibrationEvidenceBundle?,
    val rejection: ArKernelCalibrationRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelCalibrationRejection.NONE &&
            bundle != null
}

/**
 * Structural summary plus a **pre-check of the host-supplied evidence**.
 *
 * `evaluationReady` means the recorded numbers would satisfy the documented
 * held-out dimension gates. It is not a quality grade, not a measurement and
 * not an upgrade: the authoritative grade remains the job of
 * `assessReconstructionQuality` / `evaluateHeldOutDimensions` with real
 * independent evidence. The grade here stays `unverified`.
 */
data class ArKernelCalibrationEvidenceReport(
    val observationCount: Int,
    val poseCount: Int,
    val distinctEvidenceIds: Int,
    val scaleBarCount: Int,
    val surveyCount: Int,
    val sensorGroundTruthCount: Int,
    val heldOutCount: Int,
    val dimensionBinCount: Int,
    val meanAbsoluteErrorMeters: Double,
    val maxAbsoluteErrorMeters: Double,
    val maxUncertaintyAdjustedErrorMeters: Double,
    val maxRelativeError: Double,
    val largestReferenceMeters: Double,
    val assetExtentMeters: Double,
    val metricPoseCount: Int,
    val orientationOnlyPoseCount: Int,
    val minObservationsReady: Boolean,
    val minDimensionBinsReady: Boolean,
    val meanErrorGateReady: Boolean,
    val maxAdjustedErrorGateReady: Boolean,
    val maxRelativeErrorGateReady: Boolean,
    val allHeldOutReady: Boolean,
    val extentCoveredReady: Boolean,
    val qualityGrade: String = CALIB_QUALITY,
) {
    val evaluationReady: Boolean
        get() = minObservationsReady && minDimensionBinsReady &&
            meanErrorGateReady && maxAdjustedErrorGateReady &&
            maxRelativeErrorGateReady && allHeldOutReady &&
            extentCoveredReady
}

/**
 * Canonical `ARKCALIB1` text adapter for a bounded host-owned physical
 * calibration capture.
 *
 * It enforces twelve exact metadata keys in order, six fail-closed safety
 * invariants, 1–16 unique held-out independent scale observations with
 * canonical finite decimals and explicit provenance, 0–32 bounded pose or
 * orientation-only samples with strictly increasing timestamps and unit
 * quaternions, strict input/line budgets, BOM/CRLF tolerance, one optional
 * final separator and a fail-closed `END`.
 *
 * `summarize()` computes only arithmetic on host-supplied numbers and a
 * readiness pre-check. It captures no image, pixel, frame or dense geometry,
 * infers no camera intrinsics, extrinsics or metric scale, performs no
 * automatic calibration, verifies no signature, uploads nothing, performs no
 * I/O and grants no quality grade. Physical targets, real device capture and
 * centimetre validation remain host work; the grade stays `unverified`.
 */
object ArKernelCalibrationEvidenceAdapter {
    fun encode(bundle: ArKernelCalibrationEvidenceBundle): String {
        val lines = ArrayList<String>(
            CALIB_KEYS.size + bundle.observations.size +
                bundle.poses.size + 2)
        lines.add(CALIB_HEADER)
        lines.add("version=$CALIB_VERSION")
        lines.add("session_id=${bundle.sessionId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("capture_opt_in=true")
        lines.add("pose_capture_opt_in=true")
        lines.add("images_disabled=true")
        lines.add("automatic_calibration_disabled=true")
        lines.add("automatic_upload_disabled=true")
        lines.add("observation_count=${bundle.observations.size}")
        lines.add("pose_count=${bundle.poses.size}")
        lines.add(
            "asset_extent_m=" +
                decimal(bundle.assetExtentMeters))
        lines.add("quality_grade=${bundle.qualityGrade}")
        for (observation in bundle.observations) {
            lines.add(
                "observation=${observation.index}|" +
                    "${observation.evidenceId}|" +
                    "${observation.provenance.wireName()}|" +
                    "${decimal(observation.referenceMeters)}|" +
                    "${decimal(observation.measuredMeters)}|" +
                    "${decimal(observation.referenceUncertaintyMeters)}|" +
                    (if (observation.heldOut) "true" else "false"))
        }
        for (pose in bundle.poses) {
            lines.add(
                "pose=${pose.index}|" +
                    "${pose.timestampNs}|" +
                    "${decimal(pose.txMeters)}|" +
                    "${decimal(pose.tyMeters)}|" +
                    "${decimal(pose.tzMeters)}|" +
                    "${decimal(pose.qw)}|" +
                    "${decimal(pose.qx)}|" +
                    "${decimal(pose.qy)}|" +
                    "${decimal(pose.qz)}|" +
                    "${decimal(pose.uncertainty)}|" +
                    (if (pose.metricPositionKnown) "true" else "false"))
        }
        lines.add(CALIB_END)
        val text = lines.joinToString("\n")
        require(text.length <= CALIB_MAX_INPUT_CHARACTERS) {
            "Calibration evidence exceeds its bounded size"
        }
        require(lines.all { it.length <= CALIB_MAX_LINE_CHARACTERS }) {
            "Calibration evidence line exceeds its bounded length"
        }
        return text
    }

    fun summarize(bundle: ArKernelCalibrationEvidenceBundle):
        ArKernelCalibrationEvidenceReport {
        val observations = bundle.observations
        val absoluteErrors = observations.map {
            it.absoluteErrorMeters
        }
        val adjustedErrors = observations.map {
            it.uncertaintyAdjustedErrorMeters
        }
        val relativeErrors = observations.map { it.relativeError }
        val mean = absoluteErrors.sum() / observations.size
        val maxAbsolute = absoluteErrors.max()
        val maxAdjusted = adjustedErrors.max()
        val maxRelative = relativeErrors.max()
        val largestReference = observations.maxOf {
            it.referenceMeters
        }
        return ArKernelCalibrationEvidenceReport(
            observationCount = observations.size,
            poseCount = bundle.poses.size,
            distinctEvidenceIds =
                observations.map { it.evidenceId }.toSet().size,
            scaleBarCount = observations.count {
                it.provenance ==
                    ArKernelCalibrationProvenance.SCALE_BAR
            },
            surveyCount = observations.count {
                it.provenance ==
                    ArKernelCalibrationProvenance.SURVEY
            },
            sensorGroundTruthCount = observations.count {
                it.provenance ==
                    ArKernelCalibrationProvenance.SENSOR_GROUND_TRUTH
            },
            heldOutCount = observations.count { it.heldOut },
            dimensionBinCount =
                observations.map { it.dimensionBin }.toSet().size,
            meanAbsoluteErrorMeters = mean,
            maxAbsoluteErrorMeters = maxAbsolute,
            maxUncertaintyAdjustedErrorMeters = maxAdjusted,
            maxRelativeError = maxRelative,
            largestReferenceMeters = largestReference,
            assetExtentMeters = bundle.assetExtentMeters,
            metricPoseCount = bundle.poses.count {
                it.metricPositionKnown
            },
            orientationOnlyPoseCount = bundle.poses.count {
                !it.metricPositionKnown
            },
            minObservationsReady =
                observations.size >= CALIB_MIN_OBSERVATIONS,
            minDimensionBinsReady =
                observations.map { it.dimensionBin }.toSet().size >=
                    CALIB_MIN_DIMENSION_BINS,
            meanErrorGateReady = mean <= CALIB_MEAN_ERROR_GATE_M,
            maxAdjustedErrorGateReady =
                maxAdjusted <= CALIB_MAX_ERROR_GATE_M,
            maxRelativeErrorGateReady =
                maxRelative <= CALIB_MAX_RELATIVE_ERROR_GATE,
            allHeldOutReady = observations.all { it.heldOut },
            extentCoveredReady =
                largestReference >= bundle.assetExtentMeters,
        )
    }

    fun parse(text: String): ArKernelCalibrationEvidenceParseResult {
        if (text.length > CALIB_MAX_INPUT_CHARACTERS) {
            return reject(ArKernelCalibrationRejection.INPUT_TOO_LARGE, 0,
                "Calibration input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(ArKernelCalibrationRejection.EMPTY_EVIDENCE, 0,
                "Calibration input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") ->
                normalized.substring(0, normalized.length - 2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.substring(0, normalized.length - 1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(ArKernelCalibrationRejection.EMPTY_EVIDENCE, 0,
                "Calibration input contains no evidence")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(ArKernelCalibrationRejection.EMPTY_LINE, 0,
                "Calibration input has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != CALIB_HEADER || header.isBlank() ||
            header.length > CALIB_MAX_LINE_CHARACTERS) {
            return reject(ArKernelCalibrationRejection.INVALID_HEADER, 1,
                "Calibration header is invalid")
        }
        if (lines.size < CALIB_KEYS.size + 3) {
            return reject(ArKernelCalibrationRejection.INVALID_SCHEMA, 0,
                "Calibration evidence is too short")
        }
        val lineByKey = CALIB_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(CALIB_KEYS.size)
        for (index in CALIB_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(ArKernelCalibrationRejection.INVALID_SCHEMA,
                    lineNumber, "Calibration metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != CALIB_KEYS[index]) {
                return reject(ArKernelCalibrationRejection.INVALID_SCHEMA,
                    lineNumber, "Calibration metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != CALIB_VERSION) {
            return reject(ArKernelCalibrationRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported calibration evidence version")
        }
        if (values.getValue("quality_grade") != CALIB_QUALITY) {
            return reject(ArKernelCalibrationRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Calibration evidence cannot assert assessed quality")
        }
        for (key in CALIB_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return reject(ArKernelCalibrationRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Calibration flag must be a boolean")
            }
            if (value != "true") {
                return reject(ArKernelCalibrationRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Calibration safety invariant must be true")
            }
        }
        if (!CALIB_TOKEN.matches(values.getValue("session_id")) ||
            !CALIB_TOKEN.matches(values.getValue("device_token"))) {
            return reject(ArKernelCalibrationRejection.INVALID_TOKEN,
                lineByKey.getValue("session_id"),
                "Invalid calibration session or device token")
        }
        val observationCount = boundedCount(
            values.getValue("observation_count"),
            CALIB_MAX_OBSERVATIONS)
        val poseCount = boundedCount(
            values.getValue("pose_count"), CALIB_MAX_POSES)
        if (observationCount == null || poseCount == null ||
            observationCount < 1) {
            return reject(ArKernelCalibrationRejection.INVALID_COUNT,
                lineByKey.getValue("observation_count"),
                "Invalid calibration evidence row counts")
        }
        val assetExtent = unsignedDecimal(
            values.getValue("asset_extent_m"))
        if (assetExtent == null || assetExtent <= 0.0 ||
            assetExtent > CALIB_MAX_METRES) {
            return reject(ArKernelCalibrationRejection.INVALID_RANGE,
                lineByKey.getValue("asset_extent_m"),
                "Calibration asset extent is out of bounds")
        }
        val observationStart = CALIB_KEYS.size + 1
        val poseStart = observationStart + observationCount
        val endIndex = poseStart + poseCount
        if (lines.size != endIndex + 1) {
            return reject(ArKernelCalibrationRejection.INVALID_SCHEMA, 0,
                "Calibration line count does not match row counts")
        }
        val observations =
            ArrayList<ArKernelCalibrationObservation>(observationCount)
        val seenIds = HashSet<String>(observationCount)
        for (index in 0 until observationCount) {
            val lineNumber = observationStart + index + 1
            val lineText = lines[observationStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("observation=")) {
                return reject(ArKernelCalibrationRejection.INVALID_SCHEMA,
                    lineNumber, "Calibration line is not an observation")
            }
            val fields = lineText.substringAfter("observation=")
                .split('|')
            if (fields.size != 7) {
                return reject(ArKernelCalibrationRejection.INVALID_SCHEMA,
                    lineNumber, "Observation must have seven fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(ArKernelCalibrationRejection.INVALID_VALUE,
                    lineNumber, "Invalid observation index")
            if (rowIndex != index) {
                return reject(ArKernelCalibrationRejection.INVALID_ORDER,
                    lineNumber, "Observations are not contiguous")
            }
            if (!CALIB_TOKEN.matches(fields[1])) {
                return reject(ArKernelCalibrationRejection.INVALID_TOKEN,
                    lineNumber, "Invalid calibration evidence ID")
            }
            val provenance =
                ArKernelCalibrationProvenance.fromWire(fields[2])
                ?: return reject(
                    ArKernelCalibrationRejection.INVALID_PROVENANCE,
                    lineNumber, "Invalid calibration provenance")
            val reference = unsignedDecimal(fields[3])
                ?: return reject(ArKernelCalibrationRejection.INVALID_DECIMAL,
                    lineNumber, "Invalid reference distance")
            val measured = unsignedDecimal(fields[4])
                ?: return reject(ArKernelCalibrationRejection.INVALID_DECIMAL,
                    lineNumber, "Invalid measured distance")
            val uncertainty = unsignedDecimal(fields[5])
                ?: return reject(ArKernelCalibrationRejection.INVALID_DECIMAL,
                    lineNumber, "Invalid reference uncertainty")
            if (reference <= 0.0 || reference > CALIB_MAX_METRES ||
                measured > CALIB_MAX_METRES ||
                uncertainty > CALIB_MAX_UNCERTAINTY) {
                return reject(ArKernelCalibrationRejection.INVALID_RANGE,
                    lineNumber, "Observation value is out of bounds")
            }
            if (fields[6] != "true" && fields[6] != "false") {
                return reject(ArKernelCalibrationRejection.INVALID_BOOLEAN,
                    lineNumber, "Held-out flag must be a boolean")
            }
            if (fields[6] != "true") {
                return reject(
                    ArKernelCalibrationRejection.HELD_OUT_REQUIRED,
                    lineNumber, "Observation must be held out")
            }
            if (!seenIds.add(fields[1])) {
                return reject(
                    ArKernelCalibrationRejection.DUPLICATE_EVIDENCE_ID,
                    lineNumber, "Calibration evidence ID is reused")
            }
            observations += try {
                ArKernelCalibrationObservation(
                    rowIndex, fields[1], provenance, reference,
                    measured, uncertainty, true)
            } catch (error: IllegalArgumentException) {
                return reject(ArKernelCalibrationRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid calibration observation")
            }
        }
        val poses = ArrayList<ArKernelCalibrationPose>(poseCount)
        var lastTimestamp = 0L
        for (index in 0 until poseCount) {
            val lineNumber = poseStart + index + 1
            val lineText = lines[poseStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("pose=")) {
                return reject(ArKernelCalibrationRejection.INVALID_SCHEMA,
                    lineNumber, "Calibration line is not a pose sample")
            }
            val fields = lineText.substringAfter("pose=").split('|')
            if (fields.size != 11) {
                return reject(ArKernelCalibrationRejection.INVALID_SCHEMA,
                    lineNumber, "Pose sample must have eleven fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(ArKernelCalibrationRejection.INVALID_VALUE,
                    lineNumber, "Invalid pose index")
            if (rowIndex != index) {
                return reject(ArKernelCalibrationRejection.INVALID_ORDER,
                    lineNumber, "Pose samples are not contiguous")
            }
            if (!CALIB_UNSIGNED.matches(fields[1])) {
                return reject(
                    ArKernelCalibrationRejection.INVALID_TIMESTAMP,
                    lineNumber, "Invalid pose timestamp")
            }
            val timestamp = fields[1].toLongOrNull()
                ?: return reject(
                    ArKernelCalibrationRejection.INVALID_TIMESTAMP,
                    lineNumber, "Pose timestamp is out of range")
            if (timestamp <= lastTimestamp) {
                return reject(
                    ArKernelCalibrationRejection.INVALID_TIMESTAMP,
                    lineNumber,
                    "Pose timestamps must be strictly increasing")
            }
            lastTimestamp = timestamp
            val signed = ArrayList<Double>(9)
            for (field in 2..9) {
                val number = signedDecimal(fields[field])
                    ?: return reject(
                        ArKernelCalibrationRejection.INVALID_DECIMAL,
                        lineNumber, "Invalid pose decimal field")
                signed += number
            }
            if (signed[7] < 0.0 || signed[7] > CALIB_MAX_UNCERTAINTY) {
                return reject(ArKernelCalibrationRejection.INVALID_RANGE,
                    lineNumber, "Pose uncertainty is out of bounds")
            }
            if (fields[10] != "true" && fields[10] != "false") {
                return reject(ArKernelCalibrationRejection.INVALID_BOOLEAN,
                    lineNumber,
                    "Metric position flag must be a boolean")
            }
            val metricPositionKnown = fields[10] == "true"
            if (!metricPositionKnown && (signed[0] != 0.0 ||
                    signed[1] != 0.0 || signed[2] != 0.0)) {
                return reject(
                    ArKernelCalibrationRejection
                        .METRIC_POSITION_REQUIRED_ZERO,
                    lineNumber,
                    "Orientation-only pose must not assert a translation")
            }
            poses += try {
                ArKernelCalibrationPose(
                    rowIndex, timestamp, signed[0], signed[1],
                    signed[2], signed[3], signed[4], signed[5],
                    signed[6], signed[7], metricPositionKnown)
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelCalibrationRejection.INVALID_QUATERNION,
                    lineNumber,
                    error.message ?: "Invalid calibration pose")
            }
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != CALIB_END) {
            return reject(ArKernelCalibrationRejection.INVALID_SCHEMA,
                endLineNumber, "Calibration evidence must end with END")
        }
        val bundle = try {
            ArKernelCalibrationEvidenceBundle(
                values.getValue("session_id"),
                values.getValue("device_token"),
                observations,
                poses,
                assetExtent,
                true,
                true,
                true,
                true,
                true,
                CALIB_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(ArKernelCalibrationRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid calibration evidence")
        }
        return ArKernelCalibrationEvidenceParseResult(
            bundle, ArKernelCalibrationRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelCalibrationEvidenceParseResult? {
        if (lineText.length > CALIB_MAX_LINE_CHARACTERS) {
            return reject(ArKernelCalibrationRejection.LINE_TOO_LONG,
                lineNumber, "Calibration line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(ArKernelCalibrationRejection.EMPTY_LINE,
                lineNumber, "Calibration evidence contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(ArKernelCalibrationRejection.INVALID_SCHEMA,
                lineNumber,
                "Calibration lines must not contain whitespace")
        }
        return null
    }

    private fun boundedCount(value: String, maximum: Int): Int? {
        val parsed = unsignedInt(value) ?: return null
        return if (parsed <= maximum) parsed else null
    }

    private fun unsignedInt(value: String): Int? =
        if (CALIB_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun unsignedDecimal(value: String): Double? {
        if (!CALIB_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        return if (isFinite(parsed)) parsed else null
    }

    private fun signedDecimal(value: String): Double? {
        if (!CALIB_SIGNED_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        if (!isFinite(parsed)) return null
        if (value.startsWith("-") && parsed >= 0.0) return null
        return parsed
    }

    private fun isFinite(value: Double): Boolean =
        !value.isNaN() && !value.isInfinite()

    private fun decimal(value: Double): String {
        val bounded = kotlin.math.abs(value)
        require(isFinite(value) && bounded <= CALIB_MAX_METRES) {
            "Calibration decimal is out of bounds"
        }
        val scaled = kotlin.math.round(bounded * 1_000_000.0).toLong()
        val integerPart = scaled / 1_000_000L
        val fraction = scaled % 1_000_000L
        val fractionText = fraction.toString().padStart(6, '0')
            .trimEnd('0')
        val magnitude = if (fractionText.isEmpty()) {
            integerPart.toString()
        } else {
            "$integerPart.$fractionText"
        }
        return if (value < 0.0) "-$magnitude" else magnitude
    }

    private fun reject(
        rejection: ArKernelCalibrationRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelCalibrationEvidenceParseResult =
        ArKernelCalibrationEvidenceParseResult(
            null, rejection, lineNumber, detail)
}
