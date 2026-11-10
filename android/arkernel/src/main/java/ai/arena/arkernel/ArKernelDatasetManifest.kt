package ai.arena.arkernel

private const val DATASET_HEADER = "ARKDSMAN1"
private const val DATASET_VERSION = "1"
private const val DATASET_QUALITY = "unverified"
private const val DATASET_END = "END"
private const val DATASET_MAX_INPUT_CHARACTERS = 8_192
private const val DATASET_MAX_LINE_CHARACTERS = 256
private const val DATASET_MIN_ENTRIES = 1
private const val DATASET_MAX_ENTRIES = 64
private const val DATASET_MAX_REVISION = 1_000_000
private const val DATASET_MAX_SIZE_BYTES = 1_099_511_627_776L

private val DATASET_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val DATASET_HASH = Regex("[0-9a-f]{64}")
private val DATASET_UNSIGNED = Regex("0|[1-9][0-9]*")

private val DATASET_KEYS = listOf(
    "version",
    "manifest_id",
    "revision",
    "entry_count",
    "dataset_loading_disabled",
    "automatic_budget_disabled",
    "host_signature_verification_required",
    "independent_targets_required",
    "quality_grade",
)

private val DATASET_SAFETY_KEYS = listOf(
    "dataset_loading_disabled",
    "automatic_budget_disabled",
    "host_signature_verification_required",
    "independent_targets_required",
)

enum class ArKernelDatasetManifestRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_MANIFEST,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_HASH,
    INVALID_ORDER,
    DUPLICATE_DATASET_ID,
    TOO_MANY_ENTRIES,
    UNSAFE_INVARIANT,
}

/** Fail-closed parse result; on rejection no partially trusted manifest is returned. */
data class ArKernelDatasetManifestParseResult(
    val manifest: ArKernelDatasetManifest?,
    val rejection: ArKernelDatasetManifestRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelDatasetManifestRejection.NONE && manifest != null
}

/**
 * One bounded host-supplied corpus entry. IDs, licenses and roles are opaque
 * tokens; the hash is a 64-character lowercase SHA-256 hex digest. No bytes,
 * images, trajectories, poses, calibration arrays or benchmark values are
 * carried here.
 */
data class ArKernelDatasetManifestEntry(
    val index: Int,
    val datasetId: String,
    val licenseId: String,
    val sha256Hex: String,
    val sizeBytes: Long,
    val role: String,
) {
    init {
        require(index >= 0) { "Invalid dataset manifest entry index" }
        require(DATASET_TOKEN.matches(datasetId)) {
            "Invalid dataset manifest dataset ID"
        }
        require(DATASET_TOKEN.matches(licenseId)) {
            "Invalid dataset manifest license ID"
        }
        require(DATASET_HASH.matches(sha256Hex)) {
            "Invalid dataset manifest SHA-256 hex digest"
        }
        require(sizeBytes in 1L..DATASET_MAX_SIZE_BYTES) {
            "Invalid dataset manifest byte size"
        }
        require(DATASET_TOKEN.matches(role)) {
            "Invalid dataset manifest role"
        }
    }
}

/**
 * Bounded canonical manifest for host-supplied dataset corpus metadata.
 *
 * It performs no I/O, dataset loading, replay, benchmark execution, automatic
 * budget derivation, upload, signature verification or device validation. The
 * safety flags are requirements on the host, not implemented capabilities, and
 * the grade stays permanently `unverified`.
 */
data class ArKernelDatasetManifest(
    val manifestId: String,
    val revision: Int = 1,
    val datasetLoadingDisabled: Boolean = true,
    val automaticBudgetDisabled: Boolean = true,
    val hostSignatureVerificationRequired: Boolean = true,
    val independentTargetsRequired: Boolean = true,
    val entries: List<ArKernelDatasetManifestEntry>,
    val qualityGrade: String = DATASET_QUALITY,
) {
    init {
        require(DATASET_TOKEN.matches(manifestId)) {
            "Invalid dataset manifest ID"
        }
        require(revision in 1..DATASET_MAX_REVISION) {
            "Invalid dataset manifest revision"
        }
        require(datasetLoadingDisabled && automaticBudgetDisabled &&
            hostSignatureVerificationRequired && independentTargetsRequired) {
            "Dataset manifest must remain fail-closed"
        }
        require(qualityGrade == DATASET_QUALITY) {
            "Dataset manifest cannot assert assessed quality"
        }
        require(entries.size in DATASET_MIN_ENTRIES..DATASET_MAX_ENTRIES) {
            "Invalid dataset manifest entry count"
        }
        require(entries.map { it.datasetId }.toSet().size == entries.size) {
            "Dataset manifest contains duplicate dataset IDs"
        }
        require(entries.withIndex().all { it.value.index == it.index }) {
            "Dataset manifest entries are not contiguous"
        }
    }
}

/** Bounded structural facts only; never a quality or accuracy grade. */
data class ArKernelDatasetManifestReport(
    val entryCount: Int,
    val totalBytes: Long,
    val distinctLicenses: Int,
    val distinctRoles: Int,
    val qualityGrade: String = DATASET_QUALITY,
)

/**
 * Canonical `ARKDSMAN1` text adapter for a bounded host dataset manifest.
 *
 * Metadata order, input/line/entry budgets, unique opaque IDs, contiguous
 * indices, lowercase SHA-256 hex digests and positive byte sizes are enforced
 * fail-closed. BOM/CRLF input with exactly one optional final separator is
 * accepted. The adapter never loads a corpus, verifies a signature, derives a
 * budget from real data, uploads anything or upgrades `unverified`.
 */
object ArKernelDatasetManifestBundle {
    fun encode(manifest: ArKernelDatasetManifest): String {
        val lines = ArrayList<String>(
            DATASET_KEYS.size + manifest.entries.size + 2)
        lines.add(DATASET_HEADER)
        lines.add("version=$DATASET_VERSION")
        lines.add("manifest_id=${manifest.manifestId}")
        lines.add("revision=${manifest.revision}")
        lines.add("entry_count=${manifest.entries.size}")
        DATASET_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${manifest.qualityGrade}")
        for (entry in manifest.entries) {
            lines.add(
                "entry=${entry.index}|${entry.datasetId}|" +
                    "${entry.licenseId}|${entry.sha256Hex}|" +
                    "${entry.sizeBytes}|${entry.role}")
        }
        lines.add(DATASET_END)
        val text = lines.joinToString("\n")
        require(text.length <= DATASET_MAX_INPUT_CHARACTERS) {
            "Dataset manifest exceeds its bounded size"
        }
        require(lines.all { it.length <= DATASET_MAX_LINE_CHARACTERS }) {
            "Dataset manifest line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelDatasetManifestParseResult {
        if (text.length > DATASET_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelDatasetManifestRejection.INPUT_TOO_LARGE, 0,
                "Dataset manifest input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelDatasetManifestRejection.EMPTY_MANIFEST, 0,
                "Dataset manifest input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelDatasetManifestRejection.EMPTY_MANIFEST, 0,
                "Dataset manifest input contains no manifest")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != DATASET_HEADER || header.any { it.isWhitespace() } ||
            header.length > DATASET_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_HEADER, 1,
                "Dataset manifest header is invalid")
        }
        if (lines.size < DATASET_KEYS.size + 3) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_SCHEMA, 0,
                "Dataset manifest is too short")
        }
        val lineByKey = DATASET_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(DATASET_KEYS.size)
        for (index in DATASET_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_SCHEMA,
                    lineNumber, "Dataset manifest metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != DATASET_KEYS[index]) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_SCHEMA,
                    lineNumber, "Dataset manifest metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != DATASET_VERSION) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported dataset manifest version")
        }
        if (values.getValue("quality_grade") != DATASET_QUALITY) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Dataset manifest cannot assert assessed quality")
        }
        for (key in DATASET_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelDatasetManifestRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Dataset manifest safety invariant must remain true")
            }
        }
        val manifestId = values.getValue("manifest_id")
        if (!DATASET_TOKEN.matches(manifestId)) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_TOKEN,
                lineByKey.getValue("manifest_id"),
                "Invalid dataset manifest ID")
        }
        val revision = unsignedInt(values.getValue("revision"))
            ?: return reject(
                ArKernelDatasetManifestRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Invalid dataset manifest revision")
        if (revision !in 1..DATASET_MAX_REVISION) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Dataset manifest revision is out of bounds")
        }
        val entryCount = unsignedInt(values.getValue("entry_count"))
            ?: return reject(
                ArKernelDatasetManifestRejection.INVALID_VALUE,
                lineByKey.getValue("entry_count"),
                "Invalid dataset manifest entry count")
        if (entryCount !in DATASET_MIN_ENTRIES..DATASET_MAX_ENTRIES) {
            return reject(
                ArKernelDatasetManifestRejection.TOO_MANY_ENTRIES,
                lineByKey.getValue("entry_count"),
                "Dataset manifest entry count is out of bounds")
        }
        val entryStartIndex = DATASET_KEYS.size + 1
        val endIndex = entryStartIndex + entryCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_SCHEMA, 0,
                "Dataset manifest line count does not match entry count")
        }
        val entries = ArrayList<ArKernelDatasetManifestEntry>(entryCount)
        val seen = HashSet<String>(entryCount)
        for (index in 0 until entryCount) {
            val lineNumber = entryStartIndex + index + 1
            val lineText = lines[entryStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("entry=")) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_SCHEMA,
                    lineNumber, "Dataset manifest line is not an entry")
            }
            val fields = lineText.substringAfter("entry=").split('|')
            if (fields.size != 6) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_SCHEMA,
                    lineNumber, "Dataset manifest entry must have six fields")
            }
            val entryIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelDatasetManifestRejection.INVALID_VALUE,
                    lineNumber, "Invalid dataset manifest entry index")
            if (entryIndex != index) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_ORDER,
                    lineNumber, "Dataset manifest entries are not contiguous")
            }
            val datasetId = fields[1]
            if (!DATASET_TOKEN.matches(datasetId)) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_TOKEN,
                    lineNumber, "Invalid dataset manifest dataset ID")
            }
            if (!seen.add(datasetId)) {
                return reject(
                    ArKernelDatasetManifestRejection.DUPLICATE_DATASET_ID,
                    lineNumber, "Dataset manifest contains duplicate dataset IDs")
            }
            val licenseId = fields[2]
            if (!DATASET_TOKEN.matches(licenseId)) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_TOKEN,
                    lineNumber, "Invalid dataset manifest license ID")
            }
            val sha256Hex = fields[3]
            if (!DATASET_HASH.matches(sha256Hex)) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_HASH,
                    lineNumber, "Invalid dataset manifest SHA-256 hex digest")
            }
            val sizeBytes = unsignedLong(fields[4])
                ?: return reject(
                    ArKernelDatasetManifestRejection.INVALID_VALUE,
                    lineNumber, "Invalid dataset manifest byte size")
            if (sizeBytes !in 1L..DATASET_MAX_SIZE_BYTES) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_VALUE,
                    lineNumber, "Dataset manifest byte size is out of bounds")
            }
            val role = fields[5]
            if (!DATASET_TOKEN.matches(role)) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_TOKEN,
                    lineNumber, "Invalid dataset manifest role")
            }
            entries += try {
                ArKernelDatasetManifestEntry(
                    entryIndex, datasetId, licenseId, sha256Hex, sizeBytes, role)
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelDatasetManifestRejection.INVALID_VALUE,
                    lineNumber, error.message ?: "Invalid dataset manifest entry")
            }
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != DATASET_END) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_SCHEMA,
                endLineNumber, "Dataset manifest must end with END")
        }
        val manifest = try {
            ArKernelDatasetManifest(
                manifestId,
                revision,
                true,
                true,
                true,
                true,
                entries,
                DATASET_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelDatasetManifestRejection.UNSAFE_INVARIANT,
                0, error.message ?: "Invalid dataset manifest")
        }
        return ArKernelDatasetManifestParseResult(
            manifest,
            ArKernelDatasetManifestRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(manifest: ArKernelDatasetManifest):
        ArKernelDatasetManifestReport =
        ArKernelDatasetManifestReport(
            entryCount = manifest.entries.size,
            totalBytes = manifest.entries.sumOf { it.sizeBytes },
            distinctLicenses = manifest.entries.map { it.licenseId }.toSet().size,
            distinctRoles = manifest.entries.map { it.role }.toSet().size,
            qualityGrade = manifest.qualityGrade,
        )

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelDatasetManifestParseResult? {
        if (lineText.length > DATASET_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelDatasetManifestRejection.LINE_TOO_LONG,
                lineNumber, "Dataset manifest line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelDatasetManifestRejection.EMPTY_LINE,
                lineNumber, "Dataset manifest contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelDatasetManifestRejection.INVALID_SCHEMA,
                lineNumber, "Dataset manifest lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (DATASET_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun unsignedLong(value: String): Long? =
        if (DATASET_UNSIGNED.matches(value)) value.toLongOrNull() else null

    private fun reject(
        rejection: ArKernelDatasetManifestRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelDatasetManifestParseResult =
        ArKernelDatasetManifestParseResult(null, rejection, lineNumber, detail)
}
