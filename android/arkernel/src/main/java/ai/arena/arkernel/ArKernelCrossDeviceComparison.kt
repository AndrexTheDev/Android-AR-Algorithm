package ai.arena.arkernel

private const val CROSS_HEADER = "ARKCROSS1"
private const val CROSS_VERSION = "1"
private const val CROSS_PROTOCOL = "arkadapt1"
private const val CROSS_QUALITY = "unverified"
private const val CROSS_END = "END"
private const val CROSS_MAX_INPUT_CHARACTERS = 16_384
private const val CROSS_MAX_LINE_CHARACTERS = 256
private const val CROSS_MAX_SESSIONS = 16
private const val CROSS_MAX_SCENES = 16
private const val CROSS_MAX_OBSERVATIONS = 16
private const val CROSS_MAX_POSES = 32
private const val CROSS_CANONICAL_QUESTIONS = 10
private const val CROSS_ROW_FIELDS = 9

private val CROSS_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val CROSS_HEX64 = Regex("[0-9a-f]{64}")
private val CROSS_UNSIGNED = Regex("0|[1-9][0-9]*")
private val CROSS_KEYS = listOf(
    "version",
    "comparison_id",
    "protocol_id",
    "session_count",
    "question_count",
    "portable_protocol",
    "per_device_calibration",
    "universal_calibration_disabled",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "manual_upload_only",
    "quality_grade",
)
private val CROSS_BOOLEAN_KEYS = listOf(
    "portable_protocol",
    "per_device_calibration",
    "universal_calibration_disabled",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "manual_upload_only",
)
private val CROSS_INVARIANT_KEYS = CROSS_BOOLEAN_KEYS

/** One declared ARKBIND1 session in a cross-device comparison. */
data class ArKernelCrossDeviceSessionRow(
    val index: Int,
    val deviceToken: String,
    val deviceClass: ArKernelAdaptiveDeviceClass,
    val campaignId: String,
    val bindingDigest: String,
    val sceneCount: Int,
    val questionCount: Int,
    val observationCount: Int,
    val poseCount: Int,
) {
    init {
        require(index >= 0) { "Invalid cross-device row index" }
        require(CROSS_TOKEN.matches(deviceToken) &&
            CROSS_TOKEN.matches(campaignId)) {
            "Invalid cross-device token"
        }
        require(CROSS_HEX64.matches(bindingDigest)) {
            "Invalid cross-device binding digest"
        }
        require(sceneCount in 1..CROSS_MAX_SCENES) {
            "Invalid cross-device scene count"
        }
        require(questionCount == CROSS_CANONICAL_QUESTIONS) {
            "Cross-device questions must be canonical"
        }
        require(observationCount in 1..CROSS_MAX_OBSERVATIONS) {
            "Invalid cross-device observation count"
        }
        require(poseCount in 0..CROSS_MAX_POSES) {
            "Invalid cross-device pose count"
        }
    }
}

/**
 * Bounded, device-independent cross-device comparison plan.
 *
 * The portable protocol is identical for every row: ten canonical questions
 * and eight fail-closed invariants. Only the device token, device class,
 * campaign ID and per-session counts differ. Calibration values are never
 * transferred: `per_device_calibration` and
 * `universal_calibration_disabled` are mandatory. Binding digests cover the
 * canonical re-encoded `ARKBIND1` envelopes; this contract does **not**
 * re-run `ARKBIND1.verify()` against the three underlying source texts.
 * The grade stays `unverified`.
 */
data class ArKernelCrossDeviceBundle(
    val comparisonId: String,
    val rows: List<ArKernelCrossDeviceSessionRow>,
    val portableProtocol: Boolean = true,
    val perDeviceCalibration: Boolean = true,
    val universalCalibrationDisabled: Boolean = true,
    val imagesDisabled: Boolean = true,
    val automaticCalibrationDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val signatureVerificationDisabled: Boolean = true,
    val manualUploadOnly: Boolean = true,
    val qualityGrade: String = CROSS_QUALITY,
) {
    init {
        require(CROSS_TOKEN.matches(comparisonId)) {
            "Invalid comparison token"
        }
        require(rows.size in 1..CROSS_MAX_SESSIONS) {
            "Invalid cross-device session count"
        }
        require(rows.withIndex().all {
            it.value.index == it.index
        }) { "Cross-device rows are not contiguous" }
        require(rows.map { it.deviceToken }.toSet().size ==
            rows.size) {
            "Duplicate cross-device device token"
        }
        require(rows.map { it.bindingDigest }.toSet().size ==
            rows.size) {
            "Duplicate cross-device binding digest"
        }
        require(rows.all {
            it.questionCount == CROSS_CANONICAL_QUESTIONS
        }) { "Cross-device questions are not canonical" }
        require(portableProtocol && perDeviceCalibration &&
            universalCalibrationDisabled && imagesDisabled &&
            automaticCalibrationDisabled &&
            automaticUploadDisabled &&
            signatureVerificationDisabled && manualUploadOnly) {
            "Cross-device comparison must remain fail-closed"
        }
        require(qualityGrade == CROSS_QUALITY) {
            "Cross-device comparison cannot assert assessed quality"
        }
    }
}

enum class ArKernelCrossDeviceRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_PLAN,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_PROTOCOL,
    INVALID_DEVICE_CLASS,
    INVALID_COUNT,
    INVALID_DIGEST,
    INVALID_ROW_FIELDS,
    INVALID_ORDER,
    DUPLICATE_DEVICE,
    DUPLICATE_BINDING,
    INVALID_SESSION,
    DIGEST_FAILED,
}

data class ArKernelCrossDeviceParseResult(
    val bundle: ArKernelCrossDeviceBundle?,
    val rejection: ArKernelCrossDeviceRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelCrossDeviceRejection.NONE &&
            bundle != null
}

/** Structural counts only; no measurement and no quality assessment. */
data class ArKernelCrossDeviceReport(
    val sessionCount: Int,
    val distinctDeviceTokens: Int,
    val distinctDeviceClasses: Int,
    val distinctCampaignIds: Int,
    val fullCount: Int,
    val limitedCount: Int,
    val legacyCount: Int,
    val unknownCount: Int,
    val minSceneCount: Int,
    val maxSceneCount: Int,
    val minObservationCount: Int,
    val maxObservationCount: Int,
    val allCanonicalQuestions: Boolean,
    val portableProtocolSame: Boolean,
    val qualityGrade: String = CROSS_QUALITY,
)

/** Re-check of the declared binding envelopes, not of their sources. */
data class ArKernelCrossDeviceVerificationReport(
    val allSessionsParsed: Boolean,
    val digestsMatch: Boolean,
    val deviceTokensUnique: Boolean,
    val countsMatch: Boolean,
    val canonicalQuestions: Boolean,
    val comparisonConsistent: Boolean,
    val sourceReverificationPerformed: Boolean = false,
    val qualityGrade: String = CROSS_QUALITY,
)

data class ArKernelCrossDeviceCreateResult(
    val bundle: ArKernelCrossDeviceBundle?,
    val report: ArKernelCrossDeviceVerificationReport?,
    val rejection: ArKernelCrossDeviceRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelCrossDeviceRejection.NONE &&
            bundle != null && report != null
}

private data class ArKernelCrossBinding(
    val bundle: ArKernelSessionBindingBundle,
    val canonical: String,
    val digest: String,
)

/**
 * Canonical `ARKCROSS1` text adapter plus a deterministic cross-device
 * comparison builder.
 *
 * `create()` parses 1–16 host-supplied `ARKBIND1` envelopes, re-encodes each
 * to its canonical form, hashes those bytes with SHA-256 and requires unique
 * device tokens and unique binding digests. `verify()` recomputes the digests
 * and counts; `summarize()` reports only structural counts. This adapter does
 * **not** re-verify the three source texts behind each binding, captures no
 * images, pixels, poses or trajectories, measures no target, infers no
 * intrinsics, extrinsics or metric scale, performs no automatic/universal
 * calibration, verifies no signature, uploads nothing, performs no file I/O
 * and grants no quality grade. `comparisonConsistent` is not an answer, not
 * accuracy, not centimetre validation and not an upgrade. The portable part
 * is the protocol; calibration values remain per device. The grade stays
 * `unverified`.
 */
object ArKernelCrossDeviceComparisonAdapter {
    fun create(
        comparisonId: String,
        bindingTexts: List<String>,
    ): ArKernelCrossDeviceCreateResult {
        if (!CROSS_TOKEN.matches(comparisonId)) {
            return createReject(
                ArKernelCrossDeviceRejection.INVALID_TOKEN,
                "Invalid comparison token")
        }
        if (bindingTexts.size !in 1..CROSS_MAX_SESSIONS) {
            return createReject(
                ArKernelCrossDeviceRejection.INVALID_COUNT,
                "Cross-device session count is out of bounds")
        }
        val bindings =
            ArrayList<ArKernelCrossBinding>(bindingTexts.size)
        for (text in bindingTexts) {
            val parsed = ArKernelSessionBindingAdapter.parse(text)
            if (!parsed.accepted || parsed.bundle == null) {
                return createReject(
                    ArKernelCrossDeviceRejection.INVALID_SESSION,
                    parsed.detail)
            }
            val canonical = ArKernelSessionBindingAdapter
                .encode(parsed.bundle!!)
            bindings += ArKernelCrossBinding(
                parsed.bundle!!,
                canonical,
                ArKernelSessionBindingAdapter.sha256Hex(canonical),
            )
        }
        if (bindings.map { it.bundle.deviceToken }.toSet().size !=
            bindings.size) {
            return createReject(
                ArKernelCrossDeviceRejection.DUPLICATE_DEVICE,
                "Device tokens must be unique across sessions")
        }
        if (bindings.map { it.digest }.toSet().size !=
            bindings.size) {
            return createReject(
                ArKernelCrossDeviceRejection.DUPLICATE_BINDING,
                "Binding digests must be unique across sessions")
        }
        val rows = bindings.mapIndexed { index, binding ->
            val bundle = binding.bundle
            try {
                ArKernelCrossDeviceSessionRow(
                    index = index,
                    deviceToken = bundle.deviceToken,
                    deviceClass = bundle.deviceClass,
                    campaignId = bundle.campaignId,
                    bindingDigest = binding.digest,
                    sceneCount = bundle.sceneCount,
                    questionCount = bundle.questionCount,
                    observationCount = bundle.observationCount,
                    poseCount = bundle.poseCount,
                )
            } catch (error: IllegalArgumentException) {
                return createReject(
                    ArKernelCrossDeviceRejection.INVALID_VALUE,
                    error.message ?: "Invalid cross-device row")
            }
        }
        val comparison = try {
            ArKernelCrossDeviceBundle(comparisonId, rows)
        } catch (error: IllegalArgumentException) {
            return createReject(
                ArKernelCrossDeviceRejection.INVALID_VALUE,
                error.message ?: "Invalid cross-device comparison")
        }
        val report = verify(comparison, bindingTexts)
        if (!report.comparisonConsistent) {
            return createReject(
                ArKernelCrossDeviceRejection.DIGEST_FAILED,
                "Recomputed cross-device binding is inconsistent")
        }
        return ArKernelCrossDeviceCreateResult(
            comparison, report,
            ArKernelCrossDeviceRejection.NONE, "")
    }

    fun verify(
        bundle: ArKernelCrossDeviceBundle,
        bindingTexts: List<String>,
    ): ArKernelCrossDeviceVerificationReport {
        if (bindingTexts.size != bundle.rows.size) {
            return emptyReport()
        }
        val bindings =
            ArrayList<ArKernelCrossBinding>(bindingTexts.size)
        for (text in bindingTexts) {
            val parsed = ArKernelSessionBindingAdapter.parse(text)
            if (!parsed.accepted || parsed.bundle == null) {
                return emptyReport()
            }
            val canonical = ArKernelSessionBindingAdapter
                .encode(parsed.bundle!!)
            bindings += ArKernelCrossBinding(
                parsed.bundle!!,
                canonical,
                ArKernelSessionBindingAdapter.sha256Hex(canonical),
            )
        }
        var digestsMatch = true
        var countsMatch = true
        var canonicalQuestions = true
        for (index in bundle.rows.indices) {
            val row = bundle.rows[index]
            val binding = bindings[index]
            val source = binding.bundle
            if (row.bindingDigest != binding.digest) {
                digestsMatch = false
            }
            if (row.deviceToken != source.deviceToken ||
                row.campaignId != source.campaignId ||
                row.deviceClass != source.deviceClass ||
                row.sceneCount != source.sceneCount ||
                row.questionCount != source.questionCount ||
                row.observationCount != source.observationCount ||
                row.poseCount != source.poseCount) {
                countsMatch = false
            }
            if (source.questionCount !=
                CROSS_CANONICAL_QUESTIONS ||
                row.questionCount != CROSS_CANONICAL_QUESTIONS) {
                canonicalQuestions = false
            }
        }
        val deviceTokensUnique =
            bundle.rows.map { it.deviceToken }.toSet().size ==
                bundle.rows.size
        val comparisonConsistent = digestsMatch && countsMatch &&
            canonicalQuestions && deviceTokensUnique
        return ArKernelCrossDeviceVerificationReport(
            allSessionsParsed = true,
            digestsMatch = digestsMatch,
            deviceTokensUnique = deviceTokensUnique,
            countsMatch = countsMatch,
            canonicalQuestions = canonicalQuestions,
            comparisonConsistent = comparisonConsistent,
        )
    }

    private fun emptyReport():
        ArKernelCrossDeviceVerificationReport =
        ArKernelCrossDeviceVerificationReport(
            allSessionsParsed = false,
            digestsMatch = false,
            deviceTokensUnique = false,
            countsMatch = false,
            canonicalQuestions = false,
            comparisonConsistent = false,
        )

    fun summarize(bundle: ArKernelCrossDeviceBundle):
        ArKernelCrossDeviceReport {
        val rows = bundle.rows
        return ArKernelCrossDeviceReport(
            sessionCount = rows.size,
            distinctDeviceTokens =
                rows.map { it.deviceToken }.toSet().size,
            distinctDeviceClasses =
                rows.map { it.deviceClass }.toSet().size,
            distinctCampaignIds =
                rows.map { it.campaignId }.toSet().size,
            fullCount = rows.count {
                it.deviceClass ==
                    ArKernelAdaptiveDeviceClass.FULL
            },
            limitedCount = rows.count {
                it.deviceClass ==
                    ArKernelAdaptiveDeviceClass.LIMITED
            },
            legacyCount = rows.count {
                it.deviceClass ==
                    ArKernelAdaptiveDeviceClass.LEGACY
            },
            unknownCount = rows.count {
                it.deviceClass ==
                    ArKernelAdaptiveDeviceClass.UNKNOWN
            },
            minSceneCount = rows.minOf { it.sceneCount },
            maxSceneCount = rows.maxOf { it.sceneCount },
            minObservationCount = rows.minOf {
                it.observationCount },
            maxObservationCount = rows.maxOf {
                it.observationCount },
            allCanonicalQuestions = rows.all {
                it.questionCount ==
                    CROSS_CANONICAL_QUESTIONS
            },
            portableProtocolSame = bundle.portableProtocol &&
                rows.all { it.questionCount ==
                    CROSS_CANONICAL_QUESTIONS },
        )
    }

    fun encode(bundle: ArKernelCrossDeviceBundle): String {
        val lines = ArrayList<String>(
            CROSS_KEYS.size + bundle.rows.size + 2)
        lines.add(CROSS_HEADER)
        lines.add("version=$CROSS_VERSION")
        lines.add("comparison_id=${bundle.comparisonId}")
        lines.add("protocol_id=$CROSS_PROTOCOL")
        lines.add("session_count=${bundle.rows.size}")
        lines.add("question_count=$CROSS_CANONICAL_QUESTIONS")
        lines.add("portable_protocol=true")
        lines.add("per_device_calibration=true")
        lines.add("universal_calibration_disabled=true")
        lines.add("images_disabled=true")
        lines.add("automatic_calibration_disabled=true")
        lines.add("automatic_upload_disabled=true")
        lines.add("signature_verification_disabled=true")
        lines.add("manual_upload_only=true")
        lines.add("quality_grade=${bundle.qualityGrade}")
        for (row in bundle.rows) {
            lines.add(
                "session=${row.index}|${row.deviceToken}|" +
                    "${row.deviceClass.wireName()}|" +
                    "${row.campaignId}|${row.bindingDigest}|" +
                    "${row.sceneCount}|${row.questionCount}|" +
                    "${row.observationCount}|${row.poseCount}")
        }
        lines.add(CROSS_END)
        val text = lines.joinToString("\n")
        require(text.length <= CROSS_MAX_INPUT_CHARACTERS) {
            "Cross-device comparison exceeds its bounded size"
        }
        require(lines.all {
            it.length <= CROSS_MAX_LINE_CHARACTERS
        }) { "Cross-device line exceeds its bounded length" }
        return text
    }

    fun parse(text: String): ArKernelCrossDeviceParseResult {
        if (text.length > CROSS_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelCrossDeviceRejection.INPUT_TOO_LARGE, 0,
                "Cross-device input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelCrossDeviceRejection.EMPTY_PLAN, 0,
                "Cross-device input is empty")
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
                ArKernelCrossDeviceRejection.EMPTY_PLAN, 0,
                "Cross-device contains no manifest")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelCrossDeviceRejection.EMPTY_LINE, 0,
                "Cross-device has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != CROSS_HEADER || header.isBlank() ||
            header.length > CROSS_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_HEADER, 1,
                "Cross-device header is invalid")
        }
        if (lines.size < CROSS_KEYS.size + 3) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_SCHEMA, 0,
                "Cross-device is too short")
        }
        val lineByKey = CROSS_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(CROSS_KEYS.size)
        for (index in CROSS_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Cross-device metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != CROSS_KEYS[index]) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Cross-device metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != CROSS_VERSION) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported cross-device version")
        }
        if (values.getValue("protocol_id") != CROSS_PROTOCOL) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_PROTOCOL,
                lineByKey.getValue("protocol_id"),
                "Unsupported portable protocol")
        }
        if (values.getValue("quality_grade") != CROSS_QUALITY) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Cross-device cannot assert assessed quality")
        }
        for (key in CROSS_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Cross-device flag must be a boolean")
            }
            if (key in CROSS_INVARIANT_KEYS && value != "true") {
                return reject(
                    ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Cross-device safety invariant must be true")
            }
        }
        if (!CROSS_TOKEN.matches(
                values.getValue("comparison_id"))) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_TOKEN,
                lineByKey.getValue("comparison_id"),
                "Invalid comparison token")
        }
        val sessionCount = boundedCount(
            values.getValue("session_count"), CROSS_MAX_SESSIONS)
        if (sessionCount == null || sessionCount < 1) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_COUNT,
                lineByKey.getValue("session_count"),
                "Invalid cross-device session count")
        }
        val questionCount = boundedCount(
            values.getValue("question_count"), CROSS_MAX_SESSIONS)
        if (questionCount == null ||
            questionCount != CROSS_CANONICAL_QUESTIONS) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_COUNT,
                lineByKey.getValue("question_count"),
                "Cross-device questions are not canonical")
        }
        val rowStart = CROSS_KEYS.size + 1
        val endIndex = rowStart + sessionCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_SCHEMA, 0,
                "Cross-device line count does not match rows")
        }
        val rows = ArrayList<ArKernelCrossDeviceSessionRow>(
            sessionCount)
        val seenTokens = HashSet<String>(sessionCount)
        val seenDigests = HashSet<String>(sessionCount)
        for (index in 0 until sessionCount) {
            val lineNumber = rowStart + index + 1
            val lineText = lines[rowStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("session=")) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_SCHEMA,
                    lineNumber, "Cross-device line is not a session")
            }
            val fields = lineText.substringAfter("session=")
                .split('|')
            if (fields.size != CROSS_ROW_FIELDS) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_ROW_FIELDS,
                    lineNumber,
                    "Session row must have nine fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelCrossDeviceRejection.INVALID_VALUE,
                    lineNumber, "Invalid session row index")
            if (rowIndex != index) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_ORDER,
                    lineNumber,
                    "Cross-device rows are not contiguous")
            }
            if (!CROSS_TOKEN.matches(fields[1]) ||
                !CROSS_TOKEN.matches(fields[3])) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_TOKEN,
                    lineNumber, "Invalid cross-device row token")
            }
            val deviceClass =
                ArKernelAdaptiveDeviceClass.fromWire(fields[2])
                ?: return reject(
                    ArKernelCrossDeviceRejection.INVALID_DEVICE_CLASS,
                    lineNumber, "Invalid cross-device device class")
            if (!CROSS_HEX64.matches(fields[4])) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_DIGEST,
                    lineNumber,
                    "Invalid cross-device binding digest")
            }
            val sceneCount = boundedCount(
                fields[5], CROSS_MAX_SCENES)
            val rowQuestionCount = boundedCount(
                fields[6], CROSS_MAX_SESSIONS)
            val observationCount = boundedCount(
                fields[7], CROSS_MAX_OBSERVATIONS)
            val poseCount = boundedCount(
                fields[8], CROSS_MAX_POSES)
            if (sceneCount == null || sceneCount < 1 ||
                rowQuestionCount == null ||
                rowQuestionCount !=
                    CROSS_CANONICAL_QUESTIONS ||
                observationCount == null ||
                observationCount < 1 || poseCount == null) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_COUNT,
                    lineNumber, "Invalid cross-device row counts")
            }
            if (!seenTokens.add(fields[1])) {
                return reject(
                    ArKernelCrossDeviceRejection.DUPLICATE_DEVICE,
                    lineNumber, "Duplicate cross-device device token")
            }
            if (!seenDigests.add(fields[4])) {
                return reject(
                    ArKernelCrossDeviceRejection.DUPLICATE_BINDING,
                    lineNumber, "Duplicate cross-device binding digest")
            }
            rows += try {
                ArKernelCrossDeviceSessionRow(
                    rowIndex, fields[1], deviceClass, fields[3],
                    fields[4], sceneCount, rowQuestionCount,
                    observationCount, poseCount)
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelCrossDeviceRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid cross-device row")
            }
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != CROSS_END) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_SCHEMA,
                endLineNumber, "Cross-device must end with END")
        }
        val bundle = try {
            ArKernelCrossDeviceBundle(
                values.getValue("comparison_id"), rows)
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid cross-device comparison")
        }
        return ArKernelCrossDeviceParseResult(
            bundle, ArKernelCrossDeviceRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelCrossDeviceParseResult? {
        if (lineText.length > CROSS_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelCrossDeviceRejection.LINE_TOO_LONG,
                lineNumber,
                "Cross-device line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelCrossDeviceRejection.EMPTY_LINE,
                lineNumber,
                "Cross-device contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelCrossDeviceRejection.INVALID_SCHEMA,
                lineNumber,
                "Cross-device lines must not contain whitespace")
        }
        return null
    }

    private fun boundedCount(value: String, maximum: Int): Int? {
        val parsed = unsignedInt(value) ?: return null
        return if (parsed <= maximum) parsed else null
    }

    private fun unsignedInt(value: String): Int? =
        if (CROSS_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun createReject(
        rejection: ArKernelCrossDeviceRejection,
        detail: String,
    ): ArKernelCrossDeviceCreateResult =
        ArKernelCrossDeviceCreateResult(
            null, null, rejection, detail)

    private fun reject(
        rejection: ArKernelCrossDeviceRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelCrossDeviceParseResult =
        ArKernelCrossDeviceParseResult(
            null, rejection, lineNumber, detail)
}
