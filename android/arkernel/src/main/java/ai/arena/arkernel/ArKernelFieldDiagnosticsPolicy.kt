package ai.arena.arkernel

private const val FIELD_POLICY_HEADER = "ARKFDPOL1"
private const val FIELD_POLICY_VERSION = "1"
private const val FIELD_POLICY_BUNDLE_VERSION = "ARKFDIAG1"
private const val FIELD_POLICY_QUALITY = "unverified"
private const val FIELD_POLICY_MAX_INPUT_CHARACTERS = 4_096
private const val FIELD_POLICY_MAX_LINE_CHARACTERS = 256
private const val FIELD_POLICY_MAX_BUNDLE_CHARACTERS = 65_536
private const val FIELD_POLICY_MAX_REVISION = 1_000_000

private val FIELD_POLICY_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val FIELD_POLICY_UNSIGNED = Regex("0|[1-9][0-9]*")

private val FIELD_POLICY_KEYS = listOf(
    "version",
    "policy_id",
    "revision",
    "bundle_version",
    "max_bundle_chars",
    "collection_opt_in",
    "terminal_text_only",
    "host_redaction_required",
    "core_upload_disabled",
    "host_signature_verification_required",
    "independent_targets_required",
    "quality_grade",
)

enum class ArKernelFieldDiagnosticsPolicyRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_POLICY,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    UNSAFE_INVARIANT,
}

/** Fail-closed result; on rejection no partially trusted policy is returned. */
data class ArKernelFieldDiagnosticsPolicyParseResult(
    val policy: ArKernelFieldDiagnosticsPolicy?,
    val rejection: ArKernelFieldDiagnosticsPolicyRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelFieldDiagnosticsPolicyRejection.NONE && policy != null
}

/**
 * Bounded host-owned policy contract for optional field diagnostics.
 *
 * `collectionOptIn` is explicit and defaults to false. The safety flags are
 * requirements on the host, not implemented capabilities: this object performs
 * no collection, redaction, upload, signature verification, target measurement
 * or device validation. Accepted policies remain explicitly `unverified`.
 */
data class ArKernelFieldDiagnosticsPolicy(
    val policyId: String,
    val revision: Int = 1,
    val maxBundleChars: Int = FIELD_POLICY_MAX_BUNDLE_CHARACTERS,
    val collectionOptIn: Boolean = false,
    val terminalTextOnly: Boolean = true,
    val hostRedactionRequired: Boolean = true,
    val coreUploadDisabled: Boolean = true,
    val hostSignatureVerificationRequired: Boolean = true,
    val independentTargetsRequired: Boolean = true,
    val qualityGrade: String = FIELD_POLICY_QUALITY,
) {
    init {
        require(FIELD_POLICY_ID.matches(policyId)) {
            "Invalid field diagnostics policy ID"
        }
        require(revision in 1..FIELD_POLICY_MAX_REVISION) {
            "Invalid field diagnostics policy revision"
        }
        require(maxBundleChars in 1..FIELD_POLICY_MAX_BUNDLE_CHARACTERS) {
            "Invalid field diagnostics bundle budget"
        }
        require(terminalTextOnly && hostRedactionRequired && coreUploadDisabled &&
            hostSignatureVerificationRequired && independentTargetsRequired) {
            "Field diagnostics policy must remain fail-closed"
        }
        require(qualityGrade == FIELD_POLICY_QUALITY) {
            "Field diagnostics policy cannot assert assessed quality"
        }
    }
}

/**
 * Canonical `ARKFDPOL1` text adapter for one host policy.
 *
 * It has a fixed key order, input/line budgets, canonical booleans/numbers,
 * BOM/CRLF tolerance with exactly one optional final separator and strict
 * fail-closed parsing. It performs no I/O and never includes images,
 * trajectories, per-frame poses, calibration arrays, dense geometry,
 * pixels, signatures or physical target measurements.
 */
object ArKernelFieldDiagnosticsPolicyBundle {
    fun encode(policy: ArKernelFieldDiagnosticsPolicy): String {
        val values = HashMap<String, String>(FIELD_POLICY_KEYS.size)
        values["version"] = FIELD_POLICY_VERSION
        values["policy_id"] = policy.policyId
        values["revision"] = policy.revision.toString()
        values["bundle_version"] = FIELD_POLICY_BUNDLE_VERSION
        values["max_bundle_chars"] = policy.maxBundleChars.toString()
        values["collection_opt_in"] = policyBoolText(policy.collectionOptIn)
        values["terminal_text_only"] = policyBoolText(policy.terminalTextOnly)
        values["host_redaction_required"] = policyBoolText(policy.hostRedactionRequired)
        values["core_upload_disabled"] = policyBoolText(policy.coreUploadDisabled)
        values["host_signature_verification_required"] =
            policyBoolText(policy.hostSignatureVerificationRequired)
        values["independent_targets_required"] =
            policyBoolText(policy.independentTargetsRequired)
        values["quality_grade"] = policy.qualityGrade
        require(values.size == FIELD_POLICY_KEYS.size) {
            "Field diagnostics policy key set is incomplete"
        }
        val lines = ArrayList<String>(FIELD_POLICY_KEYS.size + 1)
        lines.add(FIELD_POLICY_HEADER)
        FIELD_POLICY_KEYS.forEach { key -> lines.add("$key=${values.getValue(key)}") }
        val text = lines.joinToString("\n")
        require(text.length <= FIELD_POLICY_MAX_INPUT_CHARACTERS) {
            "Field diagnostics policy exceeds its bounded size"
        }
        return text
    }

    fun parse(text: String): ArKernelFieldDiagnosticsPolicyParseResult {
        if (text.length > FIELD_POLICY_MAX_INPUT_CHARACTERS) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.INPUT_TOO_LARGE, 0,
                "Field diagnostics policy input exceeds its bounded size")
        }
        val normalized = if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.EMPTY_POLICY, 0,
                "Field diagnostics policy input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.EMPTY_POLICY, 0,
                "Field diagnostics policy input contains no policy")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != FIELD_POLICY_HEADER || header.any { it.isWhitespace() }) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.INVALID_HEADER, 1,
                "Field diagnostics policy header is invalid")
        }
        if (lines.size != FIELD_POLICY_KEYS.size + 1) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA, 0,
                "Field diagnostics policy line count is wrong")
        }
        val lineByKey = FIELD_POLICY_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(FIELD_POLICY_KEYS.size)
        for (index in FIELD_POLICY_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            if (lineText.length > FIELD_POLICY_MAX_LINE_CHARACTERS) {
                return policyReject(
                    ArKernelFieldDiagnosticsPolicyRejection.LINE_TOO_LONG,
                    lineNumber,
                    "Field diagnostics policy line exceeds its bounded length")
            }
            if (lineText.isBlank()) {
                return policyReject(
                    ArKernelFieldDiagnosticsPolicyRejection.EMPTY_LINE,
                    lineNumber,
                    "Field diagnostics policy contains a blank line")
            }
            if (lineText.any { it.isWhitespace() }) {
                return policyReject(
                    ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics policy lines must not contain whitespace")
            }
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return policyReject(
                    ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics policy line is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != FIELD_POLICY_KEYS[index]) {
                return policyReject(
                    ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics policy key order or name is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != FIELD_POLICY_VERSION) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported field diagnostics policy version")
        }
        if (values.getValue("bundle_version") != FIELD_POLICY_BUNDLE_VERSION) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
                lineByKey.getValue("bundle_version"),
                "Field diagnostics policy allows only ARKFDIAG1 bundles")
        }
        if (values.getValue("quality_grade") != FIELD_POLICY_QUALITY) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Field diagnostics policy cannot assert assessed quality")
        }
        val policyId = values.getValue("policy_id")
        if (!FIELD_POLICY_ID.matches(policyId)) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
                lineByKey.getValue("policy_id"),
                "Invalid field diagnostics policy ID")
        }
        fun invalidValue(key: String): ArKernelFieldDiagnosticsPolicyParseResult =
            policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
                lineByKey.getValue(key),
                "Invalid field diagnostics policy value for $key")
        val revision = policyIntValue(values.getValue("revision"))
            ?: return invalidValue("revision")
        if (revision !in 1..FIELD_POLICY_MAX_REVISION) return invalidValue("revision")
        val maxBundleChars = policyIntValue(values.getValue("max_bundle_chars"))
            ?: return invalidValue("max_bundle_chars")
        if (maxBundleChars !in 1..FIELD_POLICY_MAX_BUNDLE_CHARACTERS) {
            return invalidValue("max_bundle_chars")
        }
        val collectionOptIn = policyBoolValue(values.getValue("collection_opt_in"))
            ?: return invalidValue("collection_opt_in")
        val terminalTextOnly = policyBoolValue(values.getValue("terminal_text_only"))
            ?: return invalidValue("terminal_text_only")
        val hostRedactionRequired =
            policyBoolValue(values.getValue("host_redaction_required"))
                ?: return invalidValue("host_redaction_required")
        val coreUploadDisabled =
            policyBoolValue(values.getValue("core_upload_disabled"))
                ?: return invalidValue("core_upload_disabled")
        val hostSignatureVerificationRequired =
            policyBoolValue(values.getValue("host_signature_verification_required"))
                ?: return invalidValue("host_signature_verification_required")
        val independentTargetsRequired =
            policyBoolValue(values.getValue("independent_targets_required"))
                ?: return invalidValue("independent_targets_required")
        val unsafe = listOf(
            "terminal_text_only" to terminalTextOnly,
            "host_redaction_required" to hostRedactionRequired,
            "core_upload_disabled" to coreUploadDisabled,
            "host_signature_verification_required" to
                hostSignatureVerificationRequired,
            "independent_targets_required" to independentTargetsRequired,
        ).firstOrNull { !it.second }
        if (unsafe != null) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.UNSAFE_INVARIANT,
                lineByKey.getValue(unsafe.first),
                "Field diagnostics policy would weaken a fail-closed invariant")
        }
        val policy = try {
            ArKernelFieldDiagnosticsPolicy(
                policyId = policyId,
                revision = revision,
                maxBundleChars = maxBundleChars,
                collectionOptIn = collectionOptIn,
                terminalTextOnly = terminalTextOnly,
                hostRedactionRequired = hostRedactionRequired,
                coreUploadDisabled = coreUploadDisabled,
                hostSignatureVerificationRequired =
                    hostSignatureVerificationRequired,
                independentTargetsRequired = independentTargetsRequired,
                qualityGrade = FIELD_POLICY_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return policyReject(
                ArKernelFieldDiagnosticsPolicyRejection.UNSAFE_INVARIANT,
                0, error.message ?: "Invalid field diagnostics policy")
        }
        return ArKernelFieldDiagnosticsPolicyParseResult(
            policy,
            ArKernelFieldDiagnosticsPolicyRejection.NONE,
            0,
            "",
        )
    }
}

private fun policyBoolText(value: Boolean): String = if (value) "true" else "false"

private fun policyBoolValue(value: String): Boolean? = when (value) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun policyIntValue(value: String): Int? =
    if (FIELD_POLICY_UNSIGNED.matches(value)) value.toIntOrNull() else null

private fun policyReject(
    rejection: ArKernelFieldDiagnosticsPolicyRejection,
    lineNumber: Int,
    detail: String,
): ArKernelFieldDiagnosticsPolicyParseResult =
    ArKernelFieldDiagnosticsPolicyParseResult(null, rejection, lineNumber, detail)
