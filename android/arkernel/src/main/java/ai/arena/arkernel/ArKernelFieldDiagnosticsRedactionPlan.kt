package ai.arena.arkernel

private const val REDACTION_HEADER = "ARKREDACT1"
private const val REDACTION_VERSION = "1"
private const val REDACTION_QUALITY = "unverified"
private const val REDACTION_END = "END"
private const val REDACTION_MAX_INPUT_CHARACTERS = 8_192
private const val REDACTION_MAX_LINE_CHARACTERS = 256
private const val REDACTION_MIN_RULES = 1
private const val REDACTION_MAX_RULES = 32
private const val REDACTION_MAX_REVISION = 1_000_000

private val REDACTION_TOKEN = Regex("[A-Za-z0-9._:-]{1,32}")
private val REDACTION_UNSIGNED = Regex("0|[1-9][0-9]*")

private val REDACTION_KEYS = listOf(
    "version",
    "plan_id",
    "revision",
    "rule_count",
    "collection_opt_in",
    "terminal_text_only",
    "core_upload_disabled",
    "host_redaction_required",
    "host_signature_verification_required",
    "independent_targets_required",
    "quality_grade",
)

private val REDACTION_TRUE_KEYS = listOf(
    "terminal_text_only",
    "core_upload_disabled",
    "host_redaction_required",
    "host_signature_verification_required",
    "independent_targets_required",
)

enum class ArKernelFieldDiagnosticsRedactionAction(val wire: String) {
    DROP("drop"),
    HASH("hash"),
}

enum class ArKernelFieldDiagnosticsRedactionRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_PLAN,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_ACTION,
    INVALID_ORDER,
    DUPLICATE_FIELD_ID,
    INVALID_RULE_COUNT,
    UNSAFE_INVARIANT,
}

/** Fail-closed parse result; on rejection no partially trusted plan is returned. */
data class ArKernelFieldDiagnosticsRedactionParseResult(
    val plan: ArKernelFieldDiagnosticsRedactionPlan?,
    val rejection: ArKernelFieldDiagnosticsRedactionRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelFieldDiagnosticsRedactionRejection.NONE && plan != null
}

/**
 * One bounded host-declared redaction rule. All field IDs, replacement tokens
 * and reasons are opaque tokens; only `drop` and `hash` actions are allowed.
 * No field value, image, pose, trajectory, calibration array or pixel is held.
 */
data class ArKernelFieldDiagnosticsRedactionRule(
    val index: Int,
    val fieldId: String,
    val action: ArKernelFieldDiagnosticsRedactionAction,
    val token: String,
    val reason: String,
) {
    init {
        require(index >= 0) { "Invalid field diagnostics redaction index" }
        require(REDACTION_TOKEN.matches(fieldId)) {
            "Invalid field diagnostics redaction field ID"
        }
        require(REDACTION_TOKEN.matches(token)) {
            "Invalid field diagnostics redaction token"
        }
        require(REDACTION_TOKEN.matches(reason)) {
            "Invalid field diagnostics redaction reason"
        }
    }
}

/**
 * Bounded canonical plan for host-declared field-diagnostics redaction rules.
 *
 * It performs no collection, redaction, upload, signature verification, target
 * measurement, device validation, I/O or quality upgrade. The safety flags are
 * requirements on the host, not implemented capabilities; collection stays
 * disabled and the grade stays `unverified`.
 */
data class ArKernelFieldDiagnosticsRedactionPlan(
    val planId: String,
    val revision: Int = 1,
    val collectionOptIn: Boolean = false,
    val terminalTextOnly: Boolean = true,
    val coreUploadDisabled: Boolean = true,
    val hostRedactionRequired: Boolean = true,
    val hostSignatureVerificationRequired: Boolean = true,
    val independentTargetsRequired: Boolean = true,
    val rules: List<ArKernelFieldDiagnosticsRedactionRule>,
    val qualityGrade: String = REDACTION_QUALITY,
) {
    init {
        require(REDACTION_TOKEN.matches(planId)) {
            "Invalid field diagnostics redaction plan ID"
        }
        require(revision in 1..REDACTION_MAX_REVISION) {
            "Invalid field diagnostics redaction plan revision"
        }
        require(!collectionOptIn && terminalTextOnly && coreUploadDisabled &&
            hostRedactionRequired && hostSignatureVerificationRequired &&
            independentTargetsRequired) {
            "Field diagnostics redaction plan must remain fail-closed"
        }
        require(qualityGrade == REDACTION_QUALITY) {
            "Field diagnostics redaction plan cannot assert assessed quality"
        }
        require(rules.size in REDACTION_MIN_RULES..REDACTION_MAX_RULES) {
            "Invalid field diagnostics redaction rule count"
        }
        require(rules.map { it.fieldId }.toSet().size == rules.size) {
            "Field diagnostics redaction plan contains duplicate field IDs"
        }
        require(rules.withIndex().all { it.value.index == it.index }) {
            "Field diagnostics redaction rules are not contiguous"
        }
    }
}

/** Bounded structural facts only; never a quality or safety grade. */
data class ArKernelFieldDiagnosticsRedactionReport(
    val ruleCount: Int,
    val dropCount: Int,
    val hashCount: Int,
    val distinctFieldIds: Int,
    val distinctTokens: Int,
    val qualityGrade: String = REDACTION_QUALITY,
)

/**
 * Canonical `ARKREDACT1` text adapter for a bounded host redaction rule set.
 *
 * Metadata order, input/line/rule budgets, unique opaque field IDs, contiguous
 * indices, opaque replacement tokens/reasons and the exact `drop`/`hash` action
 * vocabulary are enforced fail-closed. BOM/CRLF input with exactly one optional
 * final separator is accepted. The adapter never collects or redacts a field,
 * uploads anything, verifies a signature, validates a device or upgrades
 * `unverified`.
 */
object ArKernelFieldDiagnosticsRedactionBundle {
    fun encode(plan: ArKernelFieldDiagnosticsRedactionPlan): String {
        val lines = ArrayList<String>(
            REDACTION_KEYS.size + plan.rules.size + 2)
        lines.add(REDACTION_HEADER)
        lines.add("version=$REDACTION_VERSION")
        lines.add("plan_id=${plan.planId}")
        lines.add("revision=${plan.revision}")
        lines.add("rule_count=${plan.rules.size}")
        lines.add(
            "collection_opt_in=${boolText(plan.collectionOptIn)}")
        lines.add(
            "terminal_text_only=${boolText(plan.terminalTextOnly)}")
        lines.add(
            "core_upload_disabled=${boolText(plan.coreUploadDisabled)}")
        lines.add(
            "host_redaction_required=${boolText(plan.hostRedactionRequired)}")
        lines.add(
            "host_signature_verification_required=" +
                boolText(plan.hostSignatureVerificationRequired))
        lines.add(
            "independent_targets_required=" +
                boolText(plan.independentTargetsRequired))
        lines.add("quality_grade=${plan.qualityGrade}")
        for (rule in plan.rules) {
            lines.add(
                "rule=${rule.index}|${rule.fieldId}|${rule.action.wire}|" +
                    "${rule.token}|${rule.reason}")
        }
        lines.add(REDACTION_END)
        val text = lines.joinToString("\n")
        require(text.length <= REDACTION_MAX_INPUT_CHARACTERS) {
            "Field diagnostics redaction plan exceeds its bounded size"
        }
        require(lines.all { it.length <= REDACTION_MAX_LINE_CHARACTERS }) {
            "Field diagnostics redaction line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelFieldDiagnosticsRedactionParseResult {
        if (text.length > REDACTION_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INPUT_TOO_LARGE,
                0,
                "Field diagnostics redaction input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.EMPTY_PLAN, 0,
                "Field diagnostics redaction input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.EMPTY_PLAN, 0,
                "Field diagnostics redaction input contains no plan")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.EMPTY_LINE, 0,
                "Field diagnostics redaction contains an extra final separator")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != REDACTION_HEADER ||
            header.any { it.isWhitespace() } ||
            header.length > REDACTION_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_HEADER,
                1,
                "Field diagnostics redaction header is invalid")
        }
        if (lines.size < REDACTION_KEYS.size + 3) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
                0,
                "Field diagnostics redaction plan is too short")
        }
        val lineByKey = REDACTION_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(REDACTION_KEYS.size)
        for (index in REDACTION_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics redaction metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != REDACTION_KEYS[index]) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics redaction metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != REDACTION_VERSION) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported field diagnostics redaction version")
        }
        if (values.getValue("quality_grade") != REDACTION_QUALITY) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Field diagnostics redaction cannot assert assessed quality")
        }
        if (values.getValue("collection_opt_in") != "false") {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
                lineByKey.getValue("collection_opt_in"),
                "Field diagnostics redaction plan must keep collection disabled")
        }
        for (key in REDACTION_TRUE_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Field diagnostics redaction safety invariant must be true")
            }
        }
        val planId = values.getValue("plan_id")
        if (!REDACTION_TOKEN.matches(planId)) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_TOKEN,
                lineByKey.getValue("plan_id"),
                "Invalid field diagnostics redaction plan ID")
        }
        val revision = unsignedInt(values.getValue("revision"))
            ?: return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Invalid field diagnostics redaction revision")
        if (revision !in 1..REDACTION_MAX_REVISION) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Field diagnostics redaction revision is out of bounds")
        }
        val ruleCount = unsignedInt(values.getValue("rule_count"))
            ?: return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_VALUE,
                lineByKey.getValue("rule_count"),
                "Invalid field diagnostics redaction rule count")
        if (ruleCount !in
            REDACTION_MIN_RULES..REDACTION_MAX_RULES) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_RULE_COUNT,
                lineByKey.getValue("rule_count"),
                "Field diagnostics redaction rule count is out of bounds")
        }
        val ruleStartIndex = REDACTION_KEYS.size + 1
        val endIndex = ruleStartIndex + ruleCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
                0,
                "Field diagnostics redaction line count does not match rule count")
        }
        val rules = ArrayList<ArKernelFieldDiagnosticsRedactionRule>(ruleCount)
        val seen = HashSet<String>(ruleCount)
        for (index in 0 until ruleCount) {
            val lineNumber = ruleStartIndex + index + 1
            val lineText = lines[ruleStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("rule=")) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics redaction line is not a rule")
            }
            val fields = lineText.substringAfter("rule=").split('|')
            if (fields.size != 5) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics redaction rule must have five fields")
            }
            val ruleIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid field diagnostics redaction index")
            if (ruleIndex != index) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_ORDER,
                    lineNumber,
                    "Field diagnostics redaction rules are not contiguous")
            }
            val fieldId = fields[1]
            if (!REDACTION_TOKEN.matches(fieldId)) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid field diagnostics redaction field ID")
            }
            if (!seen.add(fieldId)) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.DUPLICATE_FIELD_ID,
                    lineNumber,
                    "Field diagnostics redaction contains duplicate field IDs")
            }
            val action =
                ArKernelFieldDiagnosticsRedactionAction.entries.firstOrNull {
                    it.wire == fields[2]
                } ?: return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_ACTION,
                    lineNumber,
                    "Field diagnostics redaction action must be drop or hash")
            val token = fields[3]
            if (!REDACTION_TOKEN.matches(token)) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid field diagnostics redaction token")
            }
            val reason = fields[4]
            if (!REDACTION_TOKEN.matches(reason)) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid field diagnostics redaction reason")
            }
            rules += try {
                ArKernelFieldDiagnosticsRedactionRule(
                    ruleIndex,
                    fieldId,
                    action,
                    token,
                    reason,
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelFieldDiagnosticsRedactionRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid field diagnostics redaction rule")
            }
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != REDACTION_END) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
                endLineNumber,
                "Field diagnostics redaction plan must end with END")
        }
        val plan = try {
            ArKernelFieldDiagnosticsRedactionPlan(
                planId,
                revision,
                false,
                true,
                true,
                true,
                true,
                true,
                rules,
                REDACTION_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
                0,
                error.message ?: "Invalid field diagnostics redaction plan")
        }
        return ArKernelFieldDiagnosticsRedactionParseResult(
            plan,
            ArKernelFieldDiagnosticsRedactionRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(plan: ArKernelFieldDiagnosticsRedactionPlan):
        ArKernelFieldDiagnosticsRedactionReport =
        ArKernelFieldDiagnosticsRedactionReport(
            ruleCount = plan.rules.size,
            dropCount = plan.rules.count {
                it.action == ArKernelFieldDiagnosticsRedactionAction.DROP
            },
            hashCount = plan.rules.count {
                it.action == ArKernelFieldDiagnosticsRedactionAction.HASH
            },
            distinctFieldIds = plan.rules.map { it.fieldId }.toSet().size,
            distinctTokens = plan.rules.map { it.token }.toSet().size,
            qualityGrade = plan.qualityGrade,
        )

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelFieldDiagnosticsRedactionParseResult? {
        if (lineText.length > REDACTION_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.LINE_TOO_LONG,
                lineNumber,
                "Field diagnostics redaction line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.EMPTY_LINE,
                lineNumber,
                "Field diagnostics redaction contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
                lineNumber,
                "Field diagnostics redaction lines must not contain whitespace")
        }
        return null
    }

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun unsignedInt(value: String): Int? =
        if (REDACTION_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun reject(
        rejection: ArKernelFieldDiagnosticsRedactionRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelFieldDiagnosticsRedactionParseResult =
        ArKernelFieldDiagnosticsRedactionParseResult(
            null, rejection, lineNumber, detail)
}
