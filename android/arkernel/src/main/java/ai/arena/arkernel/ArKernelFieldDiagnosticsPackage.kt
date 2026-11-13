package ai.arena.arkernel

private const val PACK_HEADER = "ARKFDPACK1"
private const val PACK_VERSION = "1"
private const val PACK_QUALITY = "unverified"
private const val PACK_END = "END"
private const val PACK_MAX_INPUT_CHARACTERS = 8_192
private const val PACK_MAX_LINE_CHARACTERS = 256
private const val PACK_MAX_REPORTS = 16
private const val PACK_MAX_RULES = 16
private const val PACK_MAX_TARGETS = 8
private const val PACK_MIN_REPORT_BYTES = 1
private const val PACK_MAX_REPORT_BYTES = 1_000_000

private val PACK_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val PACK_TARGET = Regex("[A-Za-z0-9._:-]{1,128}")
private val PACK_SHA256 = Regex("[0-9a-f]{64}")
private val PACK_UNSIGNED = Regex("0|[1-9][0-9]*")
private val PACK_KEYS = listOf(
    "version",
    "package_id",
    "report_count",
    "rule_count",
    "target_count",
    "images_disabled",
    "poses_disabled",
    "trajectories_disabled",
    "automatic_capture_disabled",
    "automatic_upload_disabled",
    "quality_grade",
)
private val PACK_SAFETY_KEYS = listOf(
    "images_disabled",
    "poses_disabled",
    "trajectories_disabled",
    "automatic_capture_disabled",
    "automatic_upload_disabled",
)

enum class ArKernelFieldDiagnosticsPackageRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_PACKAGE,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    UNSAFE_INVARIANT,
    INVALID_TOKEN,
    INVALID_DIGEST,
    INVALID_SIZE,
    INVALID_ACTION,
    INVALID_COUNT,
    DUPLICATE_REPORT_REF,
    DUPLICATE_DIGEST,
    DUPLICATE_RULE_REF,
    DUPLICATE_TARGET_REF,
    INVALID_ORDER,
}

/** Fail-closed parse result; on rejection no partially trusted package is returned. */
data class ArKernelFieldDiagnosticsPackageParseResult(
    val bundle: ArKernelFieldDiagnosticsPackageBundle?,
    val rejection: ArKernelFieldDiagnosticsPackageRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelFieldDiagnosticsPackageRejection.NONE &&
            bundle != null
}

/**
 * One opaque host-declared `ARKFDIAG1` report reference. The digest is a
 * host-supplied lowercase SHA-256 label; it is never recomputed, verified or
 * resolved to a file.
 */
data class ArKernelFieldDiagnosticsReportRef(
    val index: Int,
    val reportId: String,
    val reportSha256: String,
    val byteSize: Int,
) {
    init {
        require(index >= 0) { "Invalid diagnostics report index" }
        require(PACK_TOKEN.matches(reportId)) {
            "Invalid diagnostics report reference"
        }
        require(PACK_SHA256.matches(reportSha256)) {
            "Invalid diagnostics report digest"
        }
        require(byteSize in
            PACK_MIN_REPORT_BYTES..PACK_MAX_REPORT_BYTES) {
            "Invalid diagnostics report byte size"
        }
    }
}

/** One opaque `ARKREDACT1` rule reference; `drop` or `hash` only. */
data class ArKernelFieldDiagnosticsRuleRef(
    val index: Int,
    val ruleId: String,
    val action: ArKernelFieldDiagnosticsRuleAction,
) {
    init {
        require(index >= 0) { "Invalid redaction rule index" }
        require(PACK_TOKEN.matches(ruleId)) {
            "Invalid redaction rule reference"
        }
    }
}

/** One opaque independent target reference; never measured or verified here. */
data class ArKernelFieldDiagnosticsTargetRef(
    val index: Int,
    val targetId: String,
) {
    init {
        require(index >= 0) { "Invalid target reference index" }
        require(PACK_TARGET.matches(targetId)) {
            "Invalid independent target reference"
        }
    }
}

enum class ArKernelFieldDiagnosticsRuleAction {
    DROP,
    HASH,
}

/**
 * Bounded canonical manifest for one host-declared field diagnostics package.
 *
 * All five safety invariants must stay disabled: images, poses, trajectories,
 * automatic capture and automatic upload. The package carries only opaque
 * references to already existing host artifacts; the grade stays `unverified`.
 */
data class ArKernelFieldDiagnosticsPackageBundle(
    val packageId: String,
    val reports: List<ArKernelFieldDiagnosticsReportRef>,
    val rules: List<ArKernelFieldDiagnosticsRuleRef>,
    val targets: List<ArKernelFieldDiagnosticsTargetRef>,
    val imagesDisabled: Boolean = true,
    val posesDisabled: Boolean = true,
    val trajectoriesDisabled: Boolean = true,
    val automaticCaptureDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val qualityGrade: String = PACK_QUALITY,
) {
    init {
        require(PACK_TOKEN.matches(packageId)) {
            "Invalid field diagnostics package ID"
        }
        require(imagesDisabled && posesDisabled &&
            trajectoriesDisabled && automaticCaptureDisabled &&
            automaticUploadDisabled) {
            "Field diagnostics package must remain fail-closed"
        }
        require(qualityGrade == PACK_QUALITY) {
            "Field diagnostics package cannot assert assessed quality"
        }
        require(reports.size in 1..PACK_MAX_REPORTS &&
            rules.size <= PACK_MAX_RULES &&
            targets.size <= PACK_MAX_TARGETS) {
            "Invalid field diagnostics package row counts"
        }
        require(reports.withIndex().all {
            it.value.index == it.index
        }) { "Diagnostics report references are not contiguous" }
        require(rules.withIndex().all {
            it.value.index == it.index
        }) { "Redaction rule references are not contiguous" }
        require(targets.withIndex().all {
            it.value.index == it.index
        }) { "Target references are not contiguous" }
        require(reports.map { it.reportId }.toSet().size ==
            reports.size) {
            "Duplicate diagnostics report reference"
        }
        require(reports.map { it.reportSha256 }.toSet().size ==
            reports.size) {
            "Duplicate diagnostics report digest"
        }
        require(rules.map { it.ruleId }.toSet().size ==
            rules.size) {
            "Duplicate redaction rule reference"
        }
        require(targets.map { it.targetId }.toSet().size ==
            targets.size) {
            "Duplicate independent target reference"
        }
    }

    /** Opaque host labels only; no file, byte or signature is read. */
    fun reportRefs(): List<String> = reports.map { it.reportId }

    /** Opaque host digests only; nothing is recomputed or verified. */
    fun referencedDigests(): List<String> =
        reports.map { it.reportSha256 }
}

/** Bounded structural facts only; never an accuracy or quality grade. */
data class ArKernelFieldDiagnosticsPackageReport(
    val reportCount: Int,
    val ruleCount: Int,
    val targetCount: Int,
    val distinctReportRefs: Int,
    val distinctReportDigests: Int,
    val totalReportBytes: Long,
    val dropRuleCount: Int,
    val hashRuleCount: Int,
    val distinctTargetRefs: Int,
    val qualityGrade: String = PACK_QUALITY,
)

/**
 * Canonical `ARKFDPACK1` text adapter for a bounded host-owned field
 * diagnostics package manifest.
 *
 * It enforces exact metadata order, strict input/line/row budgets, unique
 * opaque report references and lowercase SHA-256 labels, positive bounded
 * byte sizes, unique `drop`/`hash` rule references, unique opaque independent
 * target references, contiguous indices, five fail-closed safety invariants,
 * BOM/CRLF tolerance with one optional final separator and a fail-closed
 * `END`. `summarize()` reports only structural counts, sums and distinctness.
 * The adapter captures no image, pose, trajectory, pixel or dense geometry,
 * performs no redaction, signature or digest verification, writes no file,
 * uploads nothing, measures no target, performs no I/O and upgrades no
 * quality. Actual field collection, independent target evidence and
 * centimetre validation remain host work; the grade stays `unverified`.
 */
object ArKernelFieldDiagnosticsPackageAdapter {
    fun encode(bundle: ArKernelFieldDiagnosticsPackageBundle): String {
        val lines = ArrayList<String>(
            PACK_KEYS.size + bundle.reports.size +
                bundle.rules.size + bundle.targets.size + 2)
        lines.add(PACK_HEADER)
        lines.add("version=$PACK_VERSION")
        lines.add("package_id=${bundle.packageId}")
        lines.add("report_count=${bundle.reports.size}")
        lines.add("rule_count=${bundle.rules.size}")
        lines.add("target_count=${bundle.targets.size}")
        PACK_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${bundle.qualityGrade}")
        for (report in bundle.reports) {
            lines.add(
                "report=${report.index}|" +
                    "${report.reportId}|" +
                    "${report.reportSha256}|" +
                    report.byteSize)
        }
        for (rule in bundle.rules) {
            lines.add(
                "rule=${rule.index}|" +
                    "${rule.ruleId}|" +
                    rule.action.name.lowercase())
        }
        for (target in bundle.targets) {
            lines.add("target=${target.index}|${target.targetId}")
        }
        lines.add(PACK_END)
        val text = lines.joinToString("\n")
        require(text.length <= PACK_MAX_INPUT_CHARACTERS) {
            "Field diagnostics package exceeds its bounded size"
        }
        require(lines.all { it.length <= PACK_MAX_LINE_CHARACTERS }) {
            "Field diagnostics package line exceeds its bounded length"
        }
        return text
    }

    fun summarize(bundle: ArKernelFieldDiagnosticsPackageBundle):
        ArKernelFieldDiagnosticsPackageReport =
        ArKernelFieldDiagnosticsPackageReport(
            reportCount = bundle.reports.size,
            ruleCount = bundle.rules.size,
            targetCount = bundle.targets.size,
            distinctReportRefs =
                bundle.reports.map { it.reportId }.toSet().size,
            distinctReportDigests =
                bundle.reports.map { it.reportSha256 }.toSet().size,
            totalReportBytes = bundle.reports.sumOf {
                it.byteSize.toLong()
            },
            dropRuleCount = bundle.rules.count {
                it.action == ArKernelFieldDiagnosticsRuleAction.DROP
            },
            hashRuleCount = bundle.rules.count {
                it.action == ArKernelFieldDiagnosticsRuleAction.HASH
            },
            distinctTargetRefs =
                bundle.targets.map { it.targetId }.toSet().size,
        )

    fun parse(text: String): ArKernelFieldDiagnosticsPackageParseResult {
        if (text.length > PACK_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection
                    .INPUT_TOO_LARGE,
                0,
                "Field diagnostics package input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.EMPTY_PACKAGE,
                0,
                "Field diagnostics package input is empty")
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
                ArKernelFieldDiagnosticsPackageRejection.EMPTY_PACKAGE,
                0,
                "Field diagnostics package input contains no manifest")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.EMPTY_LINE,
                0,
                "Field diagnostics package has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != PACK_HEADER ||
            header.any { it.isWhitespace() } ||
            header.length > PACK_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_HEADER,
                1,
                "Field diagnostics package header is invalid")
        }
        if (lines.size < PACK_KEYS.size + 3) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA,
                0,
                "Field diagnostics package is too short")
        }
        val lineByKey = PACK_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(PACK_KEYS.size)
        for (index in PACK_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != PACK_KEYS[index]) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != PACK_VERSION) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported field diagnostics package version")
        }
        if (values.getValue("quality_grade") != PACK_QUALITY) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Field diagnostics package cannot assert assessed quality")
        }
        for (key in PACK_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Field diagnostics safety invariant must be true")
            }
        }
        val packageId = values.getValue("package_id")
        if (!PACK_TOKEN.matches(packageId)) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_TOKEN,
                lineByKey.getValue("package_id"),
                "Invalid field diagnostics package ID")
        }
        val reportCount = boundedCount(
            values.getValue("report_count"), PACK_MAX_REPORTS)
        val ruleCount = boundedCount(
            values.getValue("rule_count"), PACK_MAX_RULES)
        val targetCount = boundedCount(
            values.getValue("target_count"), PACK_MAX_TARGETS)
        if (reportCount == null || ruleCount == null ||
            targetCount == null || reportCount < 1) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_COUNT,
                lineByKey.getValue("report_count"),
                "Invalid field diagnostics package row counts")
        }
        val reportStart = PACK_KEYS.size + 1
        val ruleStart = reportStart + reportCount
        val targetStart = ruleStart + ruleCount
        val endIndex = targetStart + targetCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA,
                0,
                "Field diagnostics line count does not match row counts")
        }
        val reports =
            ArrayList<ArKernelFieldDiagnosticsReportRef>(reportCount)
        val seenReportRefs = HashSet<String>(reportCount)
        val seenDigests = HashSet<String>(reportCount)
        for (index in 0 until reportCount) {
            val lineNumber = reportStart + index + 1
            val lineText = lines[reportStart + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("report=")) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics line is not a report reference")
            }
            val fields = lineText.substringAfter("report=").split('|')
            if (fields.size != 4) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_SCHEMA,
                    lineNumber,
                    "Report reference must have four fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid report reference index")
            if (rowIndex != index) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_ORDER,
                    lineNumber,
                    "Report references are not contiguous")
            }
            if (!PACK_TOKEN.matches(fields[1])) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid report reference ID")
            }
            if (!PACK_SHA256.matches(fields[2])) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_DIGEST,
                    lineNumber,
                    "Invalid host-declared report digest")
            }
            val byteSize = unsignedInt(fields[3])
                ?: return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid report byte size")
            if (byteSize !in
                PACK_MIN_REPORT_BYTES..PACK_MAX_REPORT_BYTES) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_SIZE,
                    lineNumber,
                    "Report byte size is out of bounds")
            }
            if (!seenReportRefs.add(fields[1])) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .DUPLICATE_REPORT_REF,
                    lineNumber,
                    "Report reference is reused")
            }
            if (!seenDigests.add(fields[2])) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .DUPLICATE_DIGEST,
                    lineNumber,
                    "Report digest is reused")
            }
            reports += try {
                ArKernelFieldDiagnosticsReportRef(
                    rowIndex, fields[1], fields[2], byteSize)
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid report reference")
            }
        }
        val rules = ArrayList<ArKernelFieldDiagnosticsRuleRef>(ruleCount)
        val seenRuleRefs = HashSet<String>(ruleCount)
        for (index in 0 until ruleCount) {
            val lineNumber = ruleStart + index + 1
            val lineText = lines[ruleStart + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("rule=")) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics line is not a rule reference")
            }
            val fields = lineText.substringAfter("rule=").split('|')
            if (fields.size != 3) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_SCHEMA,
                    lineNumber,
                    "Rule reference must have three fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid rule reference index")
            if (rowIndex != index) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_ORDER,
                    lineNumber,
                    "Rule references are not contiguous")
            }
            if (!PACK_TOKEN.matches(fields[1])) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid rule reference ID")
            }
            val action = when (fields[2]) {
                "drop" -> ArKernelFieldDiagnosticsRuleAction.DROP
                "hash" -> ArKernelFieldDiagnosticsRuleAction.HASH
                else -> return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_ACTION,
                    lineNumber,
                    "Invalid redaction rule action")
            }
            if (!seenRuleRefs.add(fields[1])) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .DUPLICATE_RULE_REF,
                    lineNumber,
                    "Rule reference is reused")
            }
            rules += try {
                ArKernelFieldDiagnosticsRuleRef(
                    rowIndex, fields[1], action)
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid rule reference")
            }
        }
        val targets =
            ArrayList<ArKernelFieldDiagnosticsTargetRef>(targetCount)
        val seenTargetRefs = HashSet<String>(targetCount)
        for (index in 0 until targetCount) {
            val lineNumber = targetStart + index + 1
            val lineText = lines[targetStart + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("target=")) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_SCHEMA,
                    lineNumber,
                    "Field diagnostics line is not a target reference")
            }
            val fields = lineText.substringAfter("target=").split('|')
            if (fields.size != 2) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .INVALID_SCHEMA,
                    lineNumber,
                    "Target reference must have two fields")
            }
            val rowIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid target reference index")
            if (rowIndex != index) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_ORDER,
                    lineNumber,
                    "Target references are not contiguous")
            }
            if (!PACK_TARGET.matches(fields[1])) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid independent target reference")
            }
            if (!seenTargetRefs.add(fields[1])) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection
                        .DUPLICATE_TARGET_REF,
                    lineNumber,
                    "Target reference is reused")
            }
            targets += try {
                ArKernelFieldDiagnosticsTargetRef(rowIndex, fields[1])
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid target reference")
            }
        }
        val endText = lines[endIndex].trimEnd('\r')
        val endLineNumber = endIndex + 1
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != PACK_END) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA,
                endLineNumber,
                "Field diagnostics package must end with END")
        }
        val bundle = try {
            ArKernelFieldDiagnosticsPackageBundle(
                packageId,
                reports,
                rules,
                targets,
                true,
                true,
                true,
                true,
                true,
                PACK_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE,
                0,
                error.message ?: "Invalid field diagnostics package")
        }
        return ArKernelFieldDiagnosticsPackageParseResult(
            bundle,
            ArKernelFieldDiagnosticsPackageRejection.NONE,
            0,
            "",
        )
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelFieldDiagnosticsPackageParseResult? {
        if (lineText.length > PACK_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.LINE_TOO_LONG,
                lineNumber,
                "Field diagnostics line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.EMPTY_LINE,
                lineNumber,
                "Field diagnostics package contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA,
                lineNumber,
                "Field diagnostics lines must not contain whitespace")
        }
        return null
    }

    private fun boundedCount(value: String, maximum: Int): Int? {
        val parsed = unsignedInt(value) ?: return null
        return if (parsed <= maximum) parsed else null
    }

    private fun unsignedInt(value: String): Int? =
        if (PACK_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun reject(
        rejection: ArKernelFieldDiagnosticsPackageRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelFieldDiagnosticsPackageParseResult =
        ArKernelFieldDiagnosticsPackageParseResult(
            null, rejection, lineNumber, detail)
}
