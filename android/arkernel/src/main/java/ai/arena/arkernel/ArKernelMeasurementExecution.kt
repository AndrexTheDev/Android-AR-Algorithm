package ai.arena.arkernel

private const val MEAS_HEADER = "ARKMEAS1"
private const val MEAS_VERSION = "1"
private const val MEAS_PROTOCOL = "arkadapt1"
private const val MEAS_QUALITY = "unverified"
private const val MEAS_END = "END"
private const val MEAS_MAX_INPUT_CHARACTERS = 16_384
private const val MEAS_MAX_LINE_CHARACTERS = 256
private const val MEAS_MAX_SCENES = 16
private const val MEAS_CANONICAL_QUESTIONS = 10
private const val MEAS_ROW_FIELDS = 7

private val MEAS_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val MEAS_HEX64 = Regex("[0-9a-f]{64}")
private val MEAS_UNSIGNED = Regex("0|[1-9][0-9]*")
private val MEAS_KEYS = listOf(
    "version",
    "measurement_id",
    "device_token",
    "device_class",
    "protocol_id",
    "campaign_id",
    "scene_count",
    "question_count",
    "scale_bar_required",
    "independent_target_required",
    "per_device_calibration",
    "universal_calibration_disabled",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "user_confirmation_required",
    "held_out_required",
    "manual_upload_only",
    "quality_grade",
)
private val MEAS_BOOLEAN_KEYS = listOf(
    "scale_bar_required",
    "independent_target_required",
    "per_device_calibration",
    "universal_calibration_disabled",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "user_confirmation_required",
    "held_out_required",
    "manual_upload_only",
)
private val MEAS_INVARIANT_KEYS = MEAS_BOOLEAN_KEYS

enum class ArKernelMeasurementCondition {
    INDOOR,
    OUTDOOR,
    DAY,
    NIGHT,
    THERMAL,
    STILL;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelMeasurementCondition? =
            ArKernelMeasurementCondition.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

enum class ArKernelMeasurementRejection {
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
    INVALID_DEVICE_CLASS,
    DUPLICATE_SCENE,
    DUPLICATE_DIGEST,
    UNCONFIRMED_SCENE,
    HELD_OUT_REQUIRED,
    INVALID_SOURCE,
    INVALID_ORDER,
}

/** One host-executed measurement scene bound to one canonical ARKCALIB1. */
data class ArKernelMeasurementSceneRow(
    val index: Int,
    val kind: ArKernelCampaignSceneKind,
    val sceneId: String,
    val calibrationDigest: String,
    val condition: ArKernelMeasurementCondition,
    val userConfirmed: Boolean,
    val heldOut: Boolean,
) {
    init {
        require(index >= 0) { "Invalid measurement row index" }
        require(MEAS_TOKEN.matches(sceneId)) {
            "Invalid measurement scene ID"
        }
        require(MEAS_HEX64.matches(calibrationDigest)) {
            "Invalid measurement calibration digest"
        }
        require(userConfirmed) {
            "Every measurement scene requires explicit user confirmation"
        }
    }
}

/**
 * Bounded canonical ledger of host-executed physical measurement scenes.
 *
 * The portable part is the nine-scene protocol, never a calibration value:
 * each row binds one canonical `ARKCALIB1` envelope by lowercase SHA-256
 * digest, carries an opaque scene ID, an environmental condition and an
 * explicit user confirmation. Eleven fail-closed invariants keep physical
 * references, per-device calibration, manual upload and disabled automatic
 * calibration mandatory. At least one held-out scene is required.
 *
 * This is a structural completeness record, not an accuracy assessment, not a
 * centimetre validation, not a signature verification, not an automatic or
 * universal calibration and not a quality grade. The grade stays
 * `unverified`.
 */
data class ArKernelMeasurementBundle(
    val measurementId: String,
    val deviceToken: String,
    val deviceClass: ArKernelAdaptiveDeviceClass,
    val campaignId: String,
    val rows: List<ArKernelMeasurementSceneRow>,
    val scaleBarRequired: Boolean = true,
    val independentTargetRequired: Boolean = true,
    val perDeviceCalibration: Boolean = true,
    val universalCalibrationDisabled: Boolean = true,
    val imagesDisabled: Boolean = true,
    val automaticCalibrationDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val signatureVerificationDisabled: Boolean = true,
    val userConfirmationRequired: Boolean = true,
    val heldOutRequired: Boolean = true,
    val manualUploadOnly: Boolean = true,
    val qualityGrade: String = MEAS_QUALITY,
) {
    init {
        require(MEAS_TOKEN.matches(measurementId) &&
            MEAS_TOKEN.matches(deviceToken) &&
            MEAS_TOKEN.matches(campaignId)) {
            "Invalid measurement token"
        }
        require(rows.size in 1..MEAS_MAX_SCENES) {
            "Invalid measurement scene count"
        }
        require(rows.withIndex().all { it.value.index == it.index }) {
            "Measurement rows are not contiguous"
        }
        require(rows.map { it.sceneId }.toSet().size == rows.size) {
            "Duplicate measurement scene ID"
        }
        require(rows.map { it.calibrationDigest }.toSet().size ==
            rows.size) {
            "Duplicate measurement calibration digest"
        }
        require(rows.all { it.userConfirmed }) {
            "Unconfirmed measurement scene"
        }
        require(rows.any { it.heldOut }) {
            "At least one held-out measurement scene is required"
        }
        require(scaleBarRequired && independentTargetRequired &&
            perDeviceCalibration && universalCalibrationDisabled &&
            imagesDisabled && automaticCalibrationDisabled &&
            automaticUploadDisabled && signatureVerificationDisabled &&
            userConfirmationRequired && heldOutRequired &&
            manualUploadOnly) {
            "Measurement ledger must remain fail-closed"
        }
        require(qualityGrade == MEAS_QUALITY) {
            "Measurement cannot assert assessed quality"
        }
    }
}

data class ArKernelMeasurementParseResult(
    val bundle: ArKernelMeasurementBundle?,
    val rejection: ArKernelMeasurementRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelMeasurementRejection.NONE &&
            bundle != null
}

/** Structural completeness facts only; neither accuracy nor a quality grade. */
data class ArKernelMeasurementReport(
    val sceneCount: Int,
    val distinctKinds: Int,
    val heldOutCount: Int,
    val confirmedCount: Int,
    val distinctDigests: Int,
    val allCanonicalKindsCovered: Boolean,
    val allScenesConfirmed: Boolean,
    val heldOutPresent: Boolean,
    val qualityGrade: String = MEAS_QUALITY,
) {
    val evidenceComplete: Boolean
        get() = allCanonicalKindsCovered && allScenesConfirmed &&
            heldOutPresent
}

/** One host-supplied scene draft for [ArKernelMeasurementAdapter.create]. */
data class ArKernelMeasurementEntry(
    val kind: ArKernelCampaignSceneKind,
    val sceneId: String,
    val condition: ArKernelMeasurementCondition,
    val userConfirmed: Boolean,
    val heldOut: Boolean,
    val calibrationText: String,
)

private data class ArKernelMeasurementSource(
    val canonical: String,
    val digest: String,
)

data class ArKernelMeasurementCreateResult(
    val bundle: ArKernelMeasurementBundle?,
    val rejection: ArKernelMeasurementRejection,
    val detail: String,
    val sourceDigests: List<String>,
) {
    val accepted: Boolean
        get() = rejection == ArKernelMeasurementRejection.NONE &&
            bundle != null
}

/**
 * Canonical `ARKMEAS1` text adapter.
 *
 * `create()` parses host-supplied `ARKCALIB1` texts, re-encodes them
 * canonically and hashes the UTF-8 bytes with SHA-256; it requires unique
 * scene IDs, unique digests, explicit confirmation for every scene and at
 * least one held-out scene. `parse()` and `verify()` are structural only and
 * explicitly do **not** re-run `ArKernelCalibrationEvidenceAdapter.parse()`
 * on the source texts (`sourceReverificationPerformed=false`). The adapter
 * performs no file I/O, no upload, no signature verification, no image,
 * pixel, pose or trajectory capture, no automatic or universal calibration,
 * no physical validation and no quality assessment. The grade stays
 * `unverified`.
 */
object ArKernelMeasurementAdapter {
    fun create(
        measurementId: String,
        deviceToken: String,
        deviceClass: ArKernelAdaptiveDeviceClass,
        campaignId: String,
        entries: List<ArKernelMeasurementEntry>,
    ): ArKernelMeasurementCreateResult {
        if (!MEAS_TOKEN.matches(measurementId) ||
            !MEAS_TOKEN.matches(deviceToken) ||
            !MEAS_TOKEN.matches(campaignId)) {
            return createReject(
                ArKernelMeasurementRejection.INVALID_TOKEN,
                "Invalid measurement token")
        }
        if (entries.size !in 1..MEAS_MAX_SCENES) {
            return createReject(
                ArKernelMeasurementRejection.INVALID_COUNT,
                "Measurement scene count is out of bounds")
        }
        val sources =
            ArrayList<ArKernelMeasurementSource>(entries.size)
        for (entry in entries) {
            if (!MEAS_TOKEN.matches(entry.sceneId)) {
                return createReject(
                    ArKernelMeasurementRejection.INVALID_TOKEN,
                    "Invalid measurement scene ID")
            }
            if (!entry.userConfirmed) {
                return createReject(
                    ArKernelMeasurementRejection.UNCONFIRMED_SCENE,
                    "Every scene requires explicit user confirmation")
            }
            val parsed =
                ArKernelCalibrationEvidenceAdapter.parse(
                    entry.calibrationText)
            if (!parsed.accepted || parsed.bundle == null) {
                return createReject(
                    ArKernelMeasurementRejection.INVALID_SOURCE,
                    parsed.detail)
            }
            val canonical =
                ArKernelCalibrationEvidenceAdapter.encode(
                    parsed.bundle!!)
            sources += ArKernelMeasurementSource(
                canonical,
                ArKernelSessionBindingAdapter.sha256Hex(canonical))
        }
        if (sources.map { it.digest }.toSet().size != sources.size) {
            return createReject(
                ArKernelMeasurementRejection.DUPLICATE_DIGEST,
                "Calibration digests must be unique across scenes")
        }
        if (entries.map { it.sceneId }.toSet().size != entries.size) {
            return createReject(
                ArKernelMeasurementRejection.DUPLICATE_SCENE,
                "Scene IDs must be unique")
        }
        if (!entries.any { it.heldOut }) {
            return createReject(
                ArKernelMeasurementRejection.HELD_OUT_REQUIRED,
                "At least one held-out scene is required")
        }
        val rows = entries.mapIndexed { index, entry ->
            ArKernelMeasurementSceneRow(
                index = index,
                kind = entry.kind,
                sceneId = entry.sceneId,
                calibrationDigest = sources[index].digest,
                condition = entry.condition,
                userConfirmed = true,
                heldOut = entry.heldOut,
            )
        }
        val bundle = try {
            ArKernelMeasurementBundle(
                measurementId = measurementId,
                deviceToken = deviceToken,
                deviceClass = deviceClass,
                campaignId = campaignId,
                rows = rows,
            )
        } catch (error: IllegalArgumentException) {
            return createReject(
                ArKernelMeasurementRejection.INVALID_VALUE,
                error.message ?: "Invalid measurement bundle")
        }
        return ArKernelMeasurementCreateResult(
            bundle,
            ArKernelMeasurementRejection.NONE,
            "",
            sources.map { it.digest },
        )
    }

    fun encode(bundle: ArKernelMeasurementBundle): String {
        val lines = ArrayList<String>(MEAS_KEYS.size + bundle.rows.size + 2)
        lines.add(MEAS_HEADER)
        lines.add("version=$MEAS_VERSION")
        lines.add("measurement_id=${bundle.measurementId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("device_class=${bundle.deviceClass.wireName()}")
        lines.add("protocol_id=$MEAS_PROTOCOL")
        lines.add("campaign_id=${bundle.campaignId}")
        lines.add("scene_count=${bundle.rows.size}")
        lines.add("question_count=$MEAS_CANONICAL_QUESTIONS")
        for (key in MEAS_BOOLEAN_KEYS) {
            lines.add("$key=true")
        }
        lines.add("quality_grade=${bundle.qualityGrade}")
        for (row in bundle.rows) {
            lines.add(
                "scene=${row.index}|${row.kind.wireName()}|" +
                    "${row.sceneId}|${row.calibrationDigest}|" +
                    "${row.condition.wireName()}|" +
                    boolText(row.userConfirmed) + "|" +
                    boolText(row.heldOut))
        }
        lines.add(MEAS_END)
        val text = lines.joinToString("\n")
        require(text.length <= MEAS_MAX_INPUT_CHARACTERS) {
            "Measurement ledger exceeds its bounded size"
        }
        require(lines.all { it.length <= MEAS_MAX_LINE_CHARACTERS }) {
            "Measurement line exceeds its bounded length"
        }
        return text
    }

    fun summarize(bundle: ArKernelMeasurementBundle):
        ArKernelMeasurementReport {
        val kinds = bundle.rows.map { it.kind }.toSet()
        return ArKernelMeasurementReport(
            sceneCount = bundle.rows.size,
            distinctKinds = kinds.size,
            heldOutCount = bundle.rows.count { it.heldOut },
            confirmedCount = bundle.rows.count { it.userConfirmed },
            distinctDigests =
                bundle.rows.map { it.calibrationDigest }.toSet().size,
            allCanonicalKindsCovered =
                kinds.containsAll(
                    ArKernelCampaignSceneKind.entries.toSet()),
            allScenesConfirmed = bundle.rows.all { it.userConfirmed },
            heldOutPresent = bundle.rows.any { it.heldOut },
        )
    }

    /**
     * Structural verification only. Source `ARKCALIB1` texts are not available
     * here, so `sourceReverificationPerformed` is deliberately false.
     */
    fun verify(text: String): ArKernelMeasurementParseResult = parse(text)

    fun parse(text: String): ArKernelMeasurementParseResult {
        if (text.length > MEAS_MAX_INPUT_CHARACTERS) {
            return measReject(
                ArKernelMeasurementRejection.INPUT_TOO_LARGE, 0,
                "Measurement input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return measReject(
                ArKernelMeasurementRejection.EMPTY_LEDGER, 0,
                "Measurement input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") ->
                normalized.substring(0, normalized.length - 2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.substring(0, normalized.length - 1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return measReject(
                ArKernelMeasurementRejection.EMPTY_LEDGER, 0,
                "Measurement contains no ledger")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return measReject(
                ArKernelMeasurementRejection.EMPTY_LINE, 0,
                "Measurement has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != MEAS_HEADER || header.isBlank() ||
            header.length > MEAS_MAX_LINE_CHARACTERS) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_HEADER, 1,
                "Measurement header is invalid")
        }
        if (lines.size < MEAS_KEYS.size + 3) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_SCHEMA, 0,
                "Measurement is too short")
        }
        val lineByKey = MEAS_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(MEAS_KEYS.size)
        for (index in MEAS_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Measurement metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != MEAS_KEYS[index]) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Measurement metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != MEAS_VERSION) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported measurement version")
        }
        if (values.getValue("protocol_id") != MEAS_PROTOCOL) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_VALUE,
                lineByKey.getValue("protocol_id"),
                "Unsupported measurement protocol")
        }
        if (values.getValue("quality_grade") != MEAS_QUALITY) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Measurement cannot assert assessed quality")
        }
        for (key in MEAS_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Measurement flag must be a boolean")
            }
            if (key in MEAS_INVARIANT_KEYS && value != "true") {
                return measReject(
                    ArKernelMeasurementRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Measurement safety invariant must be true")
            }
        }
        for (key in listOf(
            "measurement_id", "device_token", "campaign_id")) {
            if (!MEAS_TOKEN.matches(values.getValue(key))) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid measurement token")
            }
        }
        val deviceClass = ArKernelAdaptiveDeviceClass.fromWire(
            values.getValue("device_class"))
            ?: return measReject(
                ArKernelMeasurementRejection.INVALID_DEVICE_CLASS,
                lineByKey.getValue("device_class"),
                "Invalid measurement device class")
        val sceneCount = measUInt(values.getValue("scene_count"))
            ?: return measReject(
                ArKernelMeasurementRejection.INVALID_VALUE,
                lineByKey.getValue("scene_count"),
                "Invalid measurement scene count")
        if (sceneCount !in 1..MEAS_MAX_SCENES) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_COUNT,
                lineByKey.getValue("scene_count"),
                "Measurement scene count is out of bounds")
        }
        val questionCount = measUInt(values.getValue("question_count"))
        if (questionCount != MEAS_CANONICAL_QUESTIONS) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_VALUE,
                lineByKey.getValue("question_count"),
                "Measurement questions must be canonical")
        }
        val rowStart = MEAS_KEYS.size + 1
        val endIndex = rowStart + sceneCount
        if (lines.size != endIndex + 1) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_SCHEMA, 0,
                "Measurement line count does not match scene count")
        }
        val rows =
            ArrayList<ArKernelMeasurementSceneRow>(sceneCount)
        for (index in 0 until sceneCount) {
            val lineNumber = rowStart + index + 1
            val lineText = lines[rowStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("scene=")) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_ROW,
                    lineNumber, "Measurement line is not a scene")
            }
            val fields =
                lineText.substringAfter("scene=").split('|')
            if (fields.size != MEAS_ROW_FIELDS) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_ROW,
                    lineNumber,
                    "Measurement scene must have seven fields")
            }
            val rowIndex = measUInt(fields[0])
                ?: return measReject(
                    ArKernelMeasurementRejection.INVALID_VALUE,
                    lineNumber, "Invalid measurement row index")
            if (rowIndex != index) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_ORDER,
                    lineNumber,
                    "Measurement rows are not contiguous")
            }
            val kind = ArKernelCampaignSceneKind.fromWire(fields[1])
                ?: return measReject(
                    ArKernelMeasurementRejection.INVALID_KIND,
                    lineNumber, "Invalid measurement scene kind")
            if (!MEAS_TOKEN.matches(fields[2])) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_TOKEN,
                    lineNumber, "Invalid measurement scene ID")
            }
            if (!MEAS_HEX64.matches(fields[3])) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_DIGEST,
                    lineNumber,
                    "Invalid measurement calibration digest")
            }
            val condition =
                ArKernelMeasurementCondition.fromWire(fields[4])
                    ?: return measReject(
                        ArKernelMeasurementRejection.INVALID_CONDITION,
                        lineNumber,
                        "Invalid measurement condition")
            if (fields[5] != "true" && fields[5] != "false") {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_BOOLEAN,
                    lineNumber,
                    "Measurement confirmation must be a boolean")
            }
            if (fields[5] != "true") {
                return measReject(
                    ArKernelMeasurementRejection.UNCONFIRMED_SCENE,
                    lineNumber,
                    "Unconfirmed measurement scene")
            }
            if (fields[6] != "true" && fields[6] != "false") {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_BOOLEAN,
                    lineNumber,
                    "Measurement held-out flag must be a boolean")
            }
            rows += try {
                ArKernelMeasurementSceneRow(
                    index = rowIndex,
                    kind = kind,
                    sceneId = fields[2],
                    calibrationDigest = fields[3],
                    condition = condition,
                    userConfirmed = true,
                    heldOut = fields[6] == "true",
                )
            } catch (error: IllegalArgumentException) {
                return measReject(
                    ArKernelMeasurementRejection.INVALID_ROW,
                    lineNumber,
                    error.message ?: "Invalid measurement row")
            }
        }
        if (rows.map { it.sceneId }.toSet().size != rows.size) {
            return measReject(
                ArKernelMeasurementRejection.DUPLICATE_SCENE,
                rowStart + 1, "Duplicate measurement scene ID")
        }
        if (rows.map { it.calibrationDigest }.toSet().size !=
            rows.size) {
            return measReject(
                ArKernelMeasurementRejection.DUPLICATE_DIGEST,
                rowStart + 1,
                "Duplicate measurement calibration digest")
        }
        if (!rows.any { it.heldOut }) {
            return measReject(
                ArKernelMeasurementRejection.HELD_OUT_REQUIRED,
                rowStart + 1,
                "At least one held-out scene is required")
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != MEAS_END) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_SCHEMA,
                endLineNumber, "Measurement must end with END")
        }
        val bundle = try {
            ArKernelMeasurementBundle(
                measurementId = values.getValue("measurement_id"),
                deviceToken = values.getValue("device_token"),
                deviceClass = deviceClass,
                campaignId = values.getValue("campaign_id"),
                rows = rows,
            )
        } catch (error: IllegalArgumentException) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid measurement bundle")
        }
        return ArKernelMeasurementParseResult(
            bundle, ArKernelMeasurementRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelMeasurementParseResult? {
        if (lineText.length > MEAS_MAX_LINE_CHARACTERS) {
            return measReject(
                ArKernelMeasurementRejection.LINE_TOO_LONG,
                lineNumber,
                "Measurement line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return measReject(
                ArKernelMeasurementRejection.EMPTY_LINE,
                lineNumber,
                "Measurement contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return measReject(
                ArKernelMeasurementRejection.INVALID_SCHEMA,
                lineNumber,
                "Measurement lines must not contain whitespace")
        }
        return null
    }

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun measUInt(value: String): Int? =
        if (MEAS_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun measReject(
        rejection: ArKernelMeasurementRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelMeasurementParseResult =
        ArKernelMeasurementParseResult(
            null, rejection, lineNumber, detail)

    private fun createReject(
        rejection: ArKernelMeasurementRejection,
        detail: String,
    ): ArKernelMeasurementCreateResult =
        ArKernelMeasurementCreateResult(
            null, rejection, detail, emptyList())
}
