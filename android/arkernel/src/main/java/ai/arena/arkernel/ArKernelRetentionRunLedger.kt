package ai.arena.arkernel

private const val RET_HEADER = "ARKRETENT1"
private const val RET_VERSION = "1"
private const val RET_QUALITY = "unverified"
private const val RET_END = "END"
private const val RET_MAX_INPUT_CHARACTERS = 8_192
private const val RET_MAX_LINE_CHARACTERS = 256

private val RET_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val RET_LOCALE = Regex("[A-Za-z0-9-]{1,16}")
private val RET_KEYS = listOf(
    "version",
    "run_id",
    "device_token",
    "locale_tag",
    "selected_mode",
    "delete_key_available",
    "delete_key_selected",
    "dialog_shown",
    "explicit_confirmation_required",
    "action_kind",
    "action_applied",
    "dry_run",
    "physical_validation_disabled",
    "quality_grade",
)
private val RET_BOOLEAN_KEYS = listOf(
    "delete_key_available",
    "delete_key_selected",
    "dialog_shown",
    "explicit_confirmation_required",
    "action_applied",
    "dry_run",
    "physical_validation_disabled",
)
private val RET_INVARIANT_KEYS = listOf(
    "explicit_confirmation_required",
    "dry_run",
    "physical_validation_disabled",
)

enum class ArKernelRetentionLedgerMode {
    KEEP,
    DELETE_AFTER_PUBLICATION,
    DELETE_ON_EXPLICIT_RELEASE;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelRetentionLedgerMode? =
            ArKernelRetentionLedgerMode.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

enum class ArKernelRetentionLedgerAction {
    NONE,
    AFTER_PUBLICATION,
    EXPLICIT_RELEASE;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ArKernelRetentionLedgerAction? =
            ArKernelRetentionLedgerAction.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

/**
 * Bounded canonical record of one host-driven retention UX run.
 *
 * All three invariants are mandatory: explicit confirmation is required, the
 * run is a dry run and physical-device lifecycle validation is disabled. The
 * grade is permanently `unverified`. The ledger never deletes frames or keys,
 * never controls storage, never validates encryption on a real device and
 * never asserts a quality level.
 */
data class ArKernelRetentionRunBundle(
    val runId: String,
    val deviceToken: String,
    val localeTag: String,
    val selectedMode: ArKernelRetentionLedgerMode,
    val deleteKeyAvailable: Boolean,
    val deleteKeySelected: Boolean,
    val dialogShown: Boolean,
    val actionKind: ArKernelRetentionLedgerAction,
    val actionApplied: Boolean,
    val explicitConfirmationRequired: Boolean = true,
    val dryRun: Boolean = true,
    val physicalValidationDisabled: Boolean = true,
    val qualityGrade: String = RET_QUALITY,
) {
    init {
        require(RET_TOKEN.matches(runId) &&
            RET_TOKEN.matches(deviceToken)) {
            "Invalid retention run or device token"
        }
        require(RET_LOCALE.matches(localeTag)) {
            "Invalid retention locale tag"
        }
        require(explicitConfirmationRequired && dryRun &&
            physicalValidationDisabled) {
            "Retention run ledger must remain fail-closed"
        }
        require(qualityGrade == RET_QUALITY) {
            "Retention run cannot assert assessed quality"
        }
        require(actionKind != ArKernelRetentionLedgerAction.NONE ||
            !actionApplied) {
            "An applied action requires an explicit action kind"
        }
        require(selectedMode !=
            ArKernelRetentionLedgerMode.KEEP ||
            !deleteKeySelected) {
            "Keeping frames cannot select encryption-key deletion"
        }
    }
}

enum class ArKernelRetentionRunRejection {
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
    INVALID_LOCALE,
    INVALID_MODE,
    INVALID_ACTION,
    APPLIED_NONE,
    DELETE_KEY_WITH_KEEP,
}

data class ArKernelRetentionRunParseResult(
    val bundle: ArKernelRetentionRunBundle?,
    val rejection: ArKernelRetentionRunRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelRetentionRunRejection.NONE &&
            bundle != null
}

/** Structural facts only; neither an assessment nor a device proof. */
data class ArKernelRetentionRunReport(
    val dialogShown: Boolean,
    val deleteKeyAvailable: Boolean,
    val deleteKeySelected: Boolean,
    val actionKind: ArKernelRetentionLedgerAction,
    val actionApplied: Boolean,
    val explicitConfirmationRequired: Boolean,
    val dryRun: Boolean,
    val physicalValidationDisabled: Boolean,
    val qualityGrade: String = RET_QUALITY,
)

/**
 * Canonical `ARKRETENT1` text adapter.
 *
 * The adapter only encodes and decodes host-reported structural facts. It
 * reads no sensor, deletes no frame or key, controls no storage, shows no
 * dialog, verifies no signature, uploads nothing, performs no file I/O and
 * grants no quality grade. `dryRun` is mandatory. Real encrypted keyframe
 * lifecycle validation on a physical device remains host work. The grade
 * stays `unverified`.
 */
object ArKernelRetentionRunLedgerAdapter {
    fun encode(bundle: ArKernelRetentionRunBundle): String {
        val lines = ArrayList<String>(RET_KEYS.size + 2)
        lines.add(RET_HEADER)
        lines.add("version=$RET_VERSION")
        lines.add("run_id=${bundle.runId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("locale_tag=${bundle.localeTag}")
        lines.add("selected_mode=${bundle.selectedMode.wireName()}")
        lines.add("delete_key_available=" +
            boolText(bundle.deleteKeyAvailable))
        lines.add("delete_key_selected=" +
            boolText(bundle.deleteKeySelected))
        lines.add("dialog_shown=" + boolText(bundle.dialogShown))
        lines.add("explicit_confirmation_required=true")
        lines.add("action_kind=${bundle.actionKind.wireName()}")
        lines.add("action_applied=" + boolText(bundle.actionApplied))
        lines.add("dry_run=true")
        lines.add("physical_validation_disabled=true")
        lines.add("quality_grade=${bundle.qualityGrade}")
        lines.add(RET_END)
        val text = lines.joinToString("\n")
        require(text.length <= RET_MAX_INPUT_CHARACTERS) {
            "Retention run ledger exceeds its bounded size"
        }
        require(lines.all {
            it.length <= RET_MAX_LINE_CHARACTERS
        }) { "Retention line exceeds its bounded length" }
        return text
    }

    fun summarize(bundle: ArKernelRetentionRunBundle):
        ArKernelRetentionRunReport =
        ArKernelRetentionRunReport(
            dialogShown = bundle.dialogShown,
            deleteKeyAvailable = bundle.deleteKeyAvailable,
            deleteKeySelected = bundle.deleteKeySelected,
            actionKind = bundle.actionKind,
            actionApplied = bundle.actionApplied,
            explicitConfirmationRequired =
                bundle.explicitConfirmationRequired,
            dryRun = bundle.dryRun,
            physicalValidationDisabled =
                bundle.physicalValidationDisabled,
        )

    fun parse(text: String): ArKernelRetentionRunParseResult {
        if (text.length > RET_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelRetentionRunRejection.INPUT_TOO_LARGE, 0,
                "Retention input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelRetentionRunRejection.EMPTY_LEDGER, 0,
                "Retention input is empty")
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
                ArKernelRetentionRunRejection.EMPTY_LEDGER, 0,
                "Retention contains no manifest")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelRetentionRunRejection.EMPTY_LINE, 0,
                "Retention has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != RET_HEADER || header.isBlank() ||
            header.length > RET_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_HEADER, 1,
                "Retention header is invalid")
        }
        if (lines.size != RET_KEYS.size + 2) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_SCHEMA, 0,
                "Retention line count is invalid")
        }
        val lineByKey = RET_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(RET_KEYS.size)
        for (index in RET_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelRetentionRunRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Retention metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != RET_KEYS[index]) {
                return reject(
                    ArKernelRetentionRunRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Retention metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != RET_VERSION) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported retention ledger version")
        }
        if (values.getValue("quality_grade") != RET_QUALITY) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Retention cannot assert assessed quality")
        }
        for (key in RET_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return reject(
                    ArKernelRetentionRunRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Retention flag must be a boolean")
            }
            if (key in RET_INVARIANT_KEYS && value != "true") {
                return reject(
                    ArKernelRetentionRunRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Retention safety invariant must be true")
            }
        }
        if (!RET_TOKEN.matches(values.getValue("run_id")) ||
            !RET_TOKEN.matches(values.getValue("device_token"))) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_TOKEN,
                lineByKey.getValue("run_id"),
                "Invalid retention run or device token")
        }
        if (!RET_LOCALE.matches(values.getValue("locale_tag"))) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_LOCALE,
                lineByKey.getValue("locale_tag"),
                "Invalid retention locale tag")
        }
        val mode = ArKernelRetentionLedgerMode.fromWire(
            values.getValue("selected_mode"))
            ?: return reject(
                ArKernelRetentionRunRejection.INVALID_MODE,
                lineByKey.getValue("selected_mode"),
                "Invalid retention mode")
        val action = ArKernelRetentionLedgerAction.fromWire(
            values.getValue("action_kind"))
            ?: return reject(
                ArKernelRetentionRunRejection.INVALID_ACTION,
                lineByKey.getValue("action_kind"),
                "Invalid retention action kind")
        if (action == ArKernelRetentionLedgerAction.NONE &&
            values.getValue("action_applied") == "true") {
            return reject(
                ArKernelRetentionRunRejection.APPLIED_NONE,
                lineByKey.getValue("action_applied"),
                "An applied action requires an explicit action kind")
        }
        if (mode == ArKernelRetentionLedgerMode.KEEP &&
            values.getValue("delete_key_selected") == "true") {
            return reject(
                ArKernelRetentionRunRejection.DELETE_KEY_WITH_KEEP,
                lineByKey.getValue("delete_key_selected"),
                "Keeping frames cannot select key deletion")
        }
        val endLineNumber = RET_KEYS.size + 2
        val endText = lines[RET_KEYS.size + 1].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != RET_END) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_SCHEMA,
                endLineNumber, "Retention must end with END")
        }
        val bundle = try {
            ArKernelRetentionRunBundle(
                runId = values.getValue("run_id"),
                deviceToken = values.getValue("device_token"),
                localeTag = values.getValue("locale_tag"),
                selectedMode = mode,
                deleteKeyAvailable =
                    values.getValue("delete_key_available") == "true",
                deleteKeySelected =
                    values.getValue("delete_key_selected") == "true",
                dialogShown =
                    values.getValue("dialog_shown") == "true",
                actionKind = action,
                actionApplied =
                    values.getValue("action_applied") == "true",
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid retention run")
        }
        return ArKernelRetentionRunParseResult(
            bundle, ArKernelRetentionRunRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelRetentionRunParseResult? {
        if (lineText.length > RET_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelRetentionRunRejection.LINE_TOO_LONG,
                lineNumber,
                "Retention line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelRetentionRunRejection.EMPTY_LINE,
                lineNumber,
                "Retention contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelRetentionRunRejection.INVALID_SCHEMA,
                lineNumber,
                "Retention lines must not contain whitespace")
        }
        return null
    }

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun reject(
        rejection: ArKernelRetentionRunRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelRetentionRunParseResult =
        ArKernelRetentionRunParseResult(
            null, rejection, lineNumber, detail)
}
