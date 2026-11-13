package ai.arena.arkernel

import java.security.MessageDigest

private const val REDRUN_HEADER = "ARKREDRUN1"
private const val REDRUN_VERSION = "1"
private const val REDRUN_QUALITY = "unverified"
private const val REDRUN_END = "END"
private const val REDRUN_MAX_INPUT_CHARACTERS = 16_384
private const val REDRUN_MAX_LINE_CHARACTERS = 256
private const val REDRUN_MAX_ARTEFACTS = 16
private const val REDRUN_ROW_FIELDS = 5
private const val REDRUN_SHORT_ROW_FIELDS = 4

private val REDRUN_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val REDRUN_HEX64 = Regex("[0-9a-f]{64}")
private val REDRUN_UNSIGNED = Regex("0|[1-9][0-9]*")
private val REDRUN_TRUE_KEYS = listOf(
    "automatic_upload_disabled",
    "manual_upload_only",
    "redacted_output_only",
    "core_upload_disabled",
)
private val REDRUN_KEYS = listOf(
    "version",
    "run_id",
    "plan_id",
    "plan_revision",
    "rule_count",
    "artefact_count",
    "finding_count",
    "residual_count",
    "total_matches",
    "residual_findings",
    "upload_admitted",
    "automatic_upload_disabled",
    "manual_upload_only",
    "redacted_output_only",
    "core_upload_disabled",
    "quality_grade",
)

/**
 * The six built-in detectors. Each one is a small, bounded pattern for a value
 * this app can actually end up holding: a MAC address, an IPv4 address, a
 * sixteen-character hexadecimal Android-style identifier, a fifteen-digit
 * device identifier, an absolute storage path and an e-mail address.
 *
 * The catalogue is deliberately small and explicit. A detector that matched
 * every long hexadecimal run would flag the SHA-256 digests that every
 * artefact legitimately carries, so `id.hex16` is anchored to exactly sixteen
 * characters with word boundaries on both sides.
 */
enum class ArKernelRedactionDetectorId {
    NET_MAC,
    NET_IPV4,
    ID_HEX16,
    ID_DECIMAL15,
    PATH_ABSOLUTE,
    CONTACT_EMAIL;

    fun wireName(): String = when (this) {
        ArKernelRedactionDetectorId.NET_MAC -> "net.mac"
        ArKernelRedactionDetectorId.NET_IPV4 -> "net.ipv4"
        ArKernelRedactionDetectorId.ID_HEX16 -> "id.hex16"
        ArKernelRedactionDetectorId.ID_DECIMAL15 -> "id.decimal15"
        ArKernelRedactionDetectorId.PATH_ABSOLUTE -> "path.absolute"
        ArKernelRedactionDetectorId.CONTACT_EMAIL -> "contact.email"
    }

    fun pattern(): Regex = redrunPattern(this)

    companion object {
        fun fromWire(value: String): ArKernelRedactionDetectorId? =
            ArKernelRedactionDetectorId.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

private fun redrunPattern(
    detector: ArKernelRedactionDetectorId,
): Regex = when (detector) {
    ArKernelRedactionDetectorId.NET_MAC ->
        Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    ArKernelRedactionDetectorId.NET_IPV4 ->
        Regex("(?<!\\d)(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?!\\d)")
    ArKernelRedactionDetectorId.ID_HEX16 ->
        Regex("\\b[0-9a-fA-F]{16}\\b")
    ArKernelRedactionDetectorId.ID_DECIMAL15 ->
        Regex("\\b[0-9]{15}\\b")
    ArKernelRedactionDetectorId.PATH_ABSOLUTE ->
        Regex("/(?:storage|data|sdcard|mnt|system)/[A-Za-z0-9._/-]+")
    ArKernelRedactionDetectorId.CONTACT_EMAIL ->
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
}

enum class ArKernelRedactionRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_RUN,
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
    INVALID_DETECTOR,
    INVALID_ACTION,
    DUPLICATE_ARTEFACT,
    INVALID_ORDER,
    EMPTY_SOURCE,
    UNKNOWN_DETECTOR,
    STATISTICS_MISMATCH,
}

/** One artefact to redact: its name and the text to work on. */
data class ArKernelRedactionInput(
    val artifactName: String,
    val sourceText: String,
)

/** One artefact after redaction; the text is never part of the ledger. */
data class ArKernelRedactionArtifactResult(
    val index: Int,
    val artifactName: String,
    val digestBefore: String,
    val digestAfter: String,
    val redactedText: String,
) {
    init {
        require(index >= 0) { "Invalid redaction artefact index" }
        require(REDRUN_TOKEN.matches(artifactName)) {
            "Invalid redaction artefact name"
        }
    }

    val changed: Boolean
        get() = digestBefore != digestAfter
}

data class ArKernelRedactionFindingRow(
    val index: Int,
    val artifactIndex: Int,
    val detector: ArKernelRedactionDetectorId,
    val action: ArKernelFieldDiagnosticsRedactionAction,
    val count: Int,
)

/** A detector that still matches although no rule covered it. */
data class ArKernelRedactionResidualRow(
    val index: Int,
    val artifactIndex: Int,
    val detector: ArKernelRedactionDetectorId,
    val count: Int,
)

/**
 * The result of applying one bounded redaction plan to a set of artefacts.
 *
 * `uploadAdmitted` is a gate, not a promise: it says that none of the six
 * built-in detectors still matches anywhere in the redacted output. It does
 * **not** say that the file contains nothing sensitive — a value this
 * catalogue does not recognise, a name typed into a scene ID or a number that
 * identifies a device in context passes straight through.
 */
data class ArKernelRedactionRun(
    val runId: String,
    val planId: String,
    val planRevision: Int,
    val ruleCount: Int,
    val artifacts: List<ArKernelRedactionArtifactResult>,
    val findings: List<ArKernelRedactionFindingRow>,
    val residuals: List<ArKernelRedactionResidualRow>,
) {
    init {
        require(REDRUN_TOKEN.matches(runId) &&
            REDRUN_TOKEN.matches(planId)) {
            "Invalid redaction token"
        }
        require(planRevision >= 1) { "Invalid redaction plan revision" }
        require(ruleCount >= 0) { "Invalid redaction rule count" }
        require(artifacts.size in 1..REDRUN_MAX_ARTEFACTS) {
            "Invalid redaction artefact count"
        }
        require(artifacts.withIndex().all { it.value.index == it.index }) {
            "Redaction artefacts are not contiguous"
        }
        require(artifacts.map { it.artifactName }.toSet().size ==
            artifacts.size) {
            "Duplicate redaction artefact name"
        }
    }

    val totalMatches: Int
        get() = findings.sumOf { it.count }

    val residualFindings: Int
        get() = residuals.sumOf { it.count }

    val uploadAdmitted: Boolean
        get() = residuals.isEmpty()
}

data class ArKernelRedactionRunResult(
    val run: ArKernelRedactionRun?,
    val rejection: ArKernelRedactionRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelRedactionRejection.NONE && run != null
}

data class ArKernelRedactionRunParseResult(
    val run: ArKernelRedactionRun?,
    val rejection: ArKernelRedactionRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelRedactionRejection.NONE && run != null
}

/**
 * Applies a canonical `ARKREDACT1` plan to artefact texts.
 *
 * Every rule in the plan has to name one of the six built-in detectors; a plan
 * that names anything else is rejected with `UNKNOWN_DETECTOR` rather than
 * silently left unapplied. `drop` replaces a match with the rule's opaque
 * token, `hash` appends `h` plus the first fifteen hex characters of the
 * SHA-256 of the matched value, so linkage survives without the value. The
 * prefix is not a hex digit on purpose — otherwise a hash token would look
 * like a hexadecimal identifier to the residual scan.
 *
 * After the rules run, every detector **without** a rule is scanned again over
 * the redacted text. Anything still matching becomes a residual row and blocks
 * `uploadAdmitted`. The adapter performs no file I/O, no upload, no signature
 * verification, no collection and no quality assessment; the grade stays
 * `unverified`.
 */
object ArKernelRedactionRunAdapter {

    /** Lowercase SHA-256 of the UTF-8 bytes of [text]. */
    fun digestOf(text: String): String = redrunSha256Hex(text)

    /** A plan covering all six built-in detectors. */
    fun defaultPlan(): ArKernelFieldDiagnosticsRedactionPlan =
        ArKernelFieldDiagnosticsRedactionPlan(
            planId = "field.redaction.run.v1",
            revision = 1,
            rules = listOf(
                ArKernelFieldDiagnosticsRedactionRule(
                    index = 0,
                    fieldId = "net.mac",
                    action = ArKernelFieldDiagnosticsRedactionAction.DROP,
                    token = "mac.redacted",
                    reason = "privacy",
                ),
                ArKernelFieldDiagnosticsRedactionRule(
                    index = 1,
                    fieldId = "net.ipv4",
                    action = ArKernelFieldDiagnosticsRedactionAction.DROP,
                    token = "ip.redacted",
                    reason = "privacy",
                ),
                ArKernelFieldDiagnosticsRedactionRule(
                    index = 2,
                    fieldId = "id.hex16",
                    action = ArKernelFieldDiagnosticsRedactionAction.HASH,
                    token = "hex.id",
                    reason = "identity",
                ),
                ArKernelFieldDiagnosticsRedactionRule(
                    index = 3,
                    fieldId = "id.decimal15",
                    action = ArKernelFieldDiagnosticsRedactionAction.HASH,
                    token = "dec.id",
                    reason = "identity",
                ),
                ArKernelFieldDiagnosticsRedactionRule(
                    index = 4,
                    fieldId = "path.absolute",
                    action = ArKernelFieldDiagnosticsRedactionAction.DROP,
                    token = "path.redacted",
                    reason = "privacy",
                ),
                ArKernelFieldDiagnosticsRedactionRule(
                    index = 5,
                    fieldId = "contact.email",
                    action = ArKernelFieldDiagnosticsRedactionAction.DROP,
                    token = "email.redacted",
                    reason = "privacy",
                ),
            ),
        )

    fun apply(
        plan: ArKernelFieldDiagnosticsRedactionPlan,
        runId: String,
        inputs: List<ArKernelRedactionInput>,
    ): ArKernelRedactionRunResult {
        if (!REDRUN_TOKEN.matches(runId) ||
            !REDRUN_TOKEN.matches(plan.planId)
        ) {
            return applyReject(
                ArKernelRedactionRejection.INVALID_TOKEN,
                "Invalid redaction token")
        }
        if (inputs.isEmpty() || inputs.size > REDRUN_MAX_ARTEFACTS) {
            return applyReject(
                ArKernelRedactionRejection.INVALID_COUNT,
                "Redaction artefact count is out of bounds")
        }
        val detectors = ArrayList<ArKernelRedactionDetectorId>(
            plan.rules.size)
        for (rule in plan.rules) {
            val detector = ArKernelRedactionDetectorId
                .fromWire(rule.fieldId)
                ?: return applyReject(
                    ArKernelRedactionRejection.UNKNOWN_DETECTOR,
                    "No built-in detector for field ${rule.fieldId}")
            detectors += detector
        }
        val artifacts =
            ArrayList<ArKernelRedactionArtifactResult>(inputs.size)
        val findings = ArrayList<ArKernelRedactionFindingRow>()
        val residuals = ArrayList<ArKernelRedactionResidualRow>()
        val covered = detectors.toSet()
        for ((index, input) in inputs.withIndex()) {
            if (!REDRUN_TOKEN.matches(input.artifactName)) {
                return applyReject(
                    ArKernelRedactionRejection.INVALID_TOKEN,
                    "Invalid redaction artefact name")
            }
            if (input.sourceText.isBlank()) {
                return applyReject(
                    ArKernelRedactionRejection.EMPTY_SOURCE,
                    "Redaction artefact text is empty")
            }
            if (input.sourceText.length > REDRUN_MAX_INPUT_CHARACTERS) {
                return applyReject(
                    ArKernelRedactionRejection.INPUT_TOO_LARGE,
                    "Redaction artefact exceeds its bounded length")
            }
            var text = input.sourceText
            val before = redrunSha256Hex(text)
            for ((ruleIndex, rule) in plan.rules.withIndex()) {
                val pattern = detectors[ruleIndex].pattern()
                val count = pattern.findAll(text).count()
                if (count == 0) continue
                text = pattern.replace(text) { match ->
                    if (rule.action ==
                        ArKernelFieldDiagnosticsRedactionAction.DROP
                    ) {
                        rule.token
                    } else {
                        rule.token + ".h" +
                            redrunSha256Hex(match.value).take(15)
                    }
                }
                findings += ArKernelRedactionFindingRow(
                    index = findings.size,
                    artifactIndex = index,
                    detector = detectors[ruleIndex],
                    action = rule.action,
                    count = count,
                )
            }
            for (detector in ArKernelRedactionDetectorId.entries) {
                if (detector in covered) continue
                val count = detector.pattern().findAll(text).count()
                if (count == 0) continue
                residuals += ArKernelRedactionResidualRow(
                    index = residuals.size,
                    artifactIndex = index,
                    detector = detector,
                    count = count,
                )
            }
            artifacts += try {
                ArKernelRedactionArtifactResult(
                    index = index,
                    artifactName = input.artifactName,
                    digestBefore = before,
                    digestAfter = redrunSha256Hex(text),
                    redactedText = text,
                )
            } catch (error: IllegalArgumentException) {
                return applyReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    error.message ?: "Invalid redaction artefact")
            }
        }
        if (artifacts.map { it.artifactName }.toSet().size !=
            artifacts.size
        ) {
            return applyReject(
                ArKernelRedactionRejection.DUPLICATE_ARTEFACT,
                "Duplicate redaction artefact name")
        }
        val run = try {
            ArKernelRedactionRun(
                runId = runId,
                planId = plan.planId,
                planRevision = plan.revision,
                ruleCount = plan.rules.size,
                artifacts = artifacts,
                findings = findings,
                residuals = residuals,
            )
        } catch (error: IllegalArgumentException) {
            return applyReject(
                ArKernelRedactionRejection.INVALID_ROW,
                error.message ?: "Invalid redaction run")
        }
        return ArKernelRedactionRunResult(
            run, ArKernelRedactionRejection.NONE, "")
    }

    fun encode(run: ArKernelRedactionRun): String {
        val lines = ArrayList<String>(
            REDRUN_KEYS.size + run.artifacts.size +
                run.findings.size + run.residuals.size + 2)
        lines += REDRUN_HEADER
        lines += "version=$REDRUN_VERSION"
        lines += "run_id=${run.runId}"
        lines += "plan_id=${run.planId}"
        lines += "plan_revision=${run.planRevision}"
        lines += "rule_count=${run.ruleCount}"
        lines += "artefact_count=${run.artifacts.size}"
        lines += "finding_count=${run.findings.size}"
        lines += "residual_count=${run.residuals.size}"
        lines += "total_matches=${run.totalMatches}"
        lines += "residual_findings=${run.residualFindings}"
        lines += "upload_admitted=${boolText(run.uploadAdmitted)}"
        lines += "automatic_upload_disabled=true"
        lines += "manual_upload_only=true"
        lines += "redacted_output_only=true"
        lines += "core_upload_disabled=true"
        lines += "quality_grade=$REDRUN_QUALITY"
        for (artifact in run.artifacts) {
            lines += "artefact=" + listOf(
                artifact.index.toString(),
                artifact.artifactName,
                artifact.digestBefore,
                artifact.digestAfter,
                boolText(artifact.changed),
            ).joinToString("|")
        }
        for (finding in run.findings) {
            lines += "finding=" + listOf(
                finding.index.toString(),
                finding.artifactIndex.toString(),
                finding.detector.wireName(),
                finding.action.wire,
                finding.count.toString(),
            ).joinToString("|")
        }
        for (residual in run.residuals) {
            lines += "residual=" + listOf(
                residual.index.toString(),
                residual.artifactIndex.toString(),
                residual.detector.wireName(),
                residual.count.toString(),
            ).joinToString("|")
        }
        lines += REDRUN_END
        return lines.joinToString("\n") + "\n"
    }

    fun parse(text: String): ArKernelRedactionRunParseResult {
        if (text.length > REDRUN_MAX_INPUT_CHARACTERS * 2) {
            return redrunReject(
                ArKernelRedactionRejection.INPUT_TOO_LARGE, 0,
                "Redaction run exceeds its bounded length")
        }
        if (text.isBlank()) {
            return redrunReject(
                ArKernelRedactionRejection.EMPTY_RUN, 0,
                "Redaction run is empty")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        val lines = normalized.trimEnd('\n').split('\n')
        val headerError = validateLine(lines[0].trimEnd('\r'), 1)
        if (headerError != null) return headerError
        if (lines[0].trimEnd('\r') != REDRUN_HEADER) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_HEADER, 1,
                "Redaction run must start with ARKREDRUN1")
        }
        if (lines.size < REDRUN_KEYS.size + 2) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_SCHEMA, 1,
                "Redaction run is missing its key block")
        }
        val values = LinkedHashMap<String, String>()
        val lineByKey = LinkedHashMap<String, Int>()
        for ((offset, key) in REDRUN_KEYS.withIndex()) {
            val lineNumber = offset + 2
            val lineText = lines[offset + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val separator = lineText.indexOf('=')
            if (separator <= 0) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_SCHEMA,
                    lineNumber, "Redaction line is not a key")
            }
            if (lineText.substring(0, separator) != key) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_SCHEMA,
                    lineNumber, "Redaction key order is invalid")
            }
            values[key] = lineText.substring(separator + 1)
            lineByKey[key] = lineNumber
        }
        if (values.getValue("version") != REDRUN_VERSION) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Redaction version must be one")
        }
        if (values.getValue("quality_grade") != REDRUN_QUALITY) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Redaction cannot assert assessed quality")
        }
        for (key in REDRUN_TRUE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Redaction flag must be a boolean")
            }
            if (value != "true") {
                return redrunReject(
                    ArKernelRedactionRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Redaction safety invariant must be true")
            }
        }
        val uploadAdmittedText = values.getValue("upload_admitted")
        if (uploadAdmittedText != "true" && uploadAdmittedText != "false") {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_BOOLEAN,
                lineByKey.getValue("upload_admitted"),
                "Redaction upload flag must be a boolean")
        }
        for (key in listOf("run_id", "plan_id")) {
            if (!REDRUN_TOKEN.matches(values.getValue(key))) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid redaction token")
            }
        }
        val counts = LinkedHashMap<String, Int>()
        for (key in listOf("plan_revision", "rule_count",
            "artefact_count", "finding_count", "residual_count",
            "total_matches", "residual_findings")) {
            counts[key] = redrunUInt(values.getValue(key))
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_VALUE,
                    lineByKey.getValue(key),
                    "Invalid redaction count")
        }
        val artefactCount = counts.getValue("artefact_count")
        val findingCount = counts.getValue("finding_count")
        val residualCount = counts.getValue("residual_count")
        if (artefactCount !in 1..REDRUN_MAX_ARTEFACTS) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_COUNT,
                lineByKey.getValue("artefact_count"),
                "Redaction artefact count is out of bounds")
        }
        val expectedLines = REDRUN_KEYS.size + 1 + artefactCount +
            findingCount + residualCount + 1
        if (lines.size != expectedLines) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_SCHEMA, 0,
                "Redaction line count does not match the rows")
        }
        var cursor = REDRUN_KEYS.size + 1
        val artifacts =
            ArrayList<ArKernelRedactionArtifactResult>(artefactCount)
        for (index in 0 until artefactCount) {
            val lineNumber = cursor + 1
            val lineText = lines[cursor].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("artefact=")) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber, "Redaction line is not an artefact")
            }
            val fields =
                lineText.substringAfter("artefact=").split('|')
            if (fields.size != REDRUN_ROW_FIELDS) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber,
                    "Redaction artefact must have five fields")
            }
            val rowIndex = redrunUInt(fields[0])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_VALUE,
                    lineNumber, "Invalid redaction artefact index")
            if (rowIndex != index) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ORDER,
                    lineNumber, "Redaction artefacts are not contiguous")
            }
            if (!REDRUN_HEX64.matches(fields[2]) ||
                !REDRUN_HEX64.matches(fields[3])
            ) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_DIGEST,
                    lineNumber, "Invalid redaction artefact digest")
            }
            if (fields[4] != "true" && fields[4] != "false") {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_BOOLEAN,
                    lineNumber,
                    "Redaction change flag must be a boolean")
            }
            if ((fields[4] == "true") != (fields[2] != fields[3])) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber,
                    "Redaction change flag disagrees with the digests")
            }
            artifacts += try {
                ArKernelRedactionArtifactResult(
                    index = rowIndex,
                    artifactName = fields[1],
                    digestBefore = fields[2],
                    digestAfter = fields[3],
                    redactedText = "",
                )
            } catch (error: IllegalArgumentException) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber,
                    error.message ?: "Invalid redaction artefact")
            }
            cursor++
        }
        val findings = ArrayList<ArKernelRedactionFindingRow>(findingCount)
        for (index in 0 until findingCount) {
            val lineNumber = cursor + 1
            val lineText = lines[cursor].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("finding=")) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber, "Redaction line is not a finding")
            }
            val fields =
                lineText.substringAfter("finding=").split('|')
            if (fields.size != REDRUN_ROW_FIELDS) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber,
                    "Redaction finding must have five fields")
            }
            val rowIndex = redrunUInt(fields[0])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_VALUE,
                    lineNumber, "Invalid redaction finding index")
            if (rowIndex != index) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ORDER,
                    lineNumber, "Redaction findings are not contiguous")
            }
            val artefactIndex = redrunUInt(fields[1])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_VALUE,
                    lineNumber, "Invalid redaction artefact reference")
            if (artefactIndex >= artefactCount) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber,
                    "Redaction finding points at a missing artefact")
            }
            val detector = ArKernelRedactionDetectorId.fromWire(fields[2])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_DETECTOR,
                    lineNumber, "Invalid redaction detector")
            val action = ArKernelFieldDiagnosticsRedactionAction
                .entries.firstOrNull { it.wire == fields[3] }
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_ACTION,
                    lineNumber, "Invalid redaction action")
            val count = redrunUInt(fields[4])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_VALUE,
                    lineNumber, "Invalid redaction finding count")
            findings += ArKernelRedactionFindingRow(
                index = rowIndex,
                artifactIndex = artefactIndex,
                detector = detector,
                action = action,
                count = count,
            )
            cursor++
        }
        val residuals =
            ArrayList<ArKernelRedactionResidualRow>(residualCount)
        for (index in 0 until residualCount) {
            val lineNumber = cursor + 1
            val lineText = lines[cursor].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("residual=")) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber, "Redaction line is not a residual")
            }
            val fields =
                lineText.substringAfter("residual=").split('|')
            if (fields.size != REDRUN_SHORT_ROW_FIELDS) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber,
                    "Redaction residual must have four fields")
            }
            val rowIndex = redrunUInt(fields[0])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_VALUE,
                    lineNumber, "Invalid redaction residual index")
            if (rowIndex != index) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ORDER,
                    lineNumber, "Redaction residuals are not contiguous")
            }
            val artefactIndex = redrunUInt(fields[1])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_VALUE,
                    lineNumber, "Invalid redaction artefact reference")
            if (artefactIndex >= artefactCount) {
                return redrunReject(
                    ArKernelRedactionRejection.INVALID_ROW,
                    lineNumber,
                    "Redaction residual points at a missing artefact")
            }
            val detector = ArKernelRedactionDetectorId.fromWire(fields[2])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_DETECTOR,
                    lineNumber, "Invalid redaction detector")
            val count = redrunUInt(fields[3])
                ?: return redrunReject(
                    ArKernelRedactionRejection.INVALID_VALUE,
                    lineNumber, "Invalid redaction residual count")
            residuals += ArKernelRedactionResidualRow(
                index = rowIndex,
                artifactIndex = artefactIndex,
                detector = detector,
                count = count,
            )
            cursor++
        }
        val endLineNumber = cursor + 1
        val endText = lines[cursor].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != REDRUN_END) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_SCHEMA,
                endLineNumber, "Redaction run must end with END")
        }
        val run = try {
            ArKernelRedactionRun(
                runId = values.getValue("run_id"),
                planId = values.getValue("plan_id"),
                planRevision = counts.getValue("plan_revision"),
                ruleCount = counts.getValue("rule_count"),
                artifacts = artifacts,
                findings = findings,
                residuals = residuals,
            )
        } catch (error: IllegalArgumentException) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid redaction run")
        }
        if (run.totalMatches != counts.getValue("total_matches") ||
            run.residualFindings != counts.getValue("residual_findings") ||
            run.uploadAdmitted != (uploadAdmittedText == "true") ||
            counts.getValue("rule_count") != run.ruleCount
        ) {
            return redrunReject(
                ArKernelRedactionRejection.STATISTICS_MISMATCH,
                lineByKey.getValue("total_matches"),
                "Redaction statistics do not match the rows")
        }
        return ArKernelRedactionRunParseResult(
            run, ArKernelRedactionRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelRedactionRunParseResult? {
        if (lineText.length > REDRUN_MAX_LINE_CHARACTERS) {
            return redrunReject(
                ArKernelRedactionRejection.LINE_TOO_LONG,
                lineNumber,
                "Redaction line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return redrunReject(
                ArKernelRedactionRejection.EMPTY_LINE,
                lineNumber,
                "Redaction run contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return redrunReject(
                ArKernelRedactionRejection.INVALID_SCHEMA,
                lineNumber,
                "Redaction lines must not contain whitespace")
        }
        return null
    }

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun redrunUInt(value: String): Int? =
        if (REDRUN_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun redrunSha256Hex(text: String): String {
        val digested = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digested.size * 2)
        for (byte in digested) {
            val value = byte.toInt() and 0xFF
            if (value < 16) builder.append('0')
            builder.append(value.toString(16))
        }
        return builder.toString()
    }

    private fun redrunReject(
        rejection: ArKernelRedactionRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelRedactionRunParseResult =
        ArKernelRedactionRunParseResult(null, rejection, lineNumber, detail)

    private fun applyReject(
        rejection: ArKernelRedactionRejection,
        detail: String,
    ): ArKernelRedactionRunResult =
        ArKernelRedactionRunResult(null, rejection, detail)
}
