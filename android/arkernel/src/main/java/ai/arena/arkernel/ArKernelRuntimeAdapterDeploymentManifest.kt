package ai.arena.arkernel

private const val ADAPTER_HEADER = "ARKRTAD1"
private const val ADAPTER_VERSION = "1"
private const val ADAPTER_QUALITY = "unverified"
private const val ADAPTER_STATE = "declared"
private const val ADAPTER_END = "END"
private const val ADAPTER_MAX_INPUT_CHARACTERS = 8_192
private const val ADAPTER_MAX_LINE_CHARACTERS = 256
private const val ADAPTER_MIN_ADAPTERS = 1
private const val ADAPTER_MAX_ADAPTERS = 16
private const val ADAPTER_MAX_REVISION = 1_000_000
private const val ADAPTER_MAX_SIZE_BYTES = 1_099_511_627_776L

private val ADAPTER_TOKEN = Regex("[A-Za-z0-9._:-]{1,32}")
private val ADAPTER_SHA256 = Regex("[0-9a-f]{64}")
private val ADAPTER_UNSIGNED = Regex("0|[1-9][0-9]*")

private val ADAPTER_KEYS = listOf(
    "version",
    "manifest_id",
    "revision",
    "adapter_count",
    "cpu_fallback_required",
    "startup_sentinel_required",
    "read_only_factory_admission",
    "model_name_free_identity",
    "separate_packaging_required",
    "quality_grade",
)

private val ADAPTER_SAFETY_KEYS = listOf(
    "cpu_fallback_required",
    "startup_sentinel_required",
    "read_only_factory_admission",
    "model_name_free_identity",
    "separate_packaging_required",
)

enum class ArKernelRuntimeAdapterDeploymentRejection {
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
    INVALID_DIGEST,
    INVALID_STATE,
    INVALID_ORDER,
    DUPLICATE_ADAPTER_ID,
    INVALID_ADAPTER_COUNT,
    UNSAFE_INVARIANT,
}

/** Fail-closed parse result; on rejection no partially trusted plan is returned. */
data class ArKernelRuntimeAdapterDeploymentParseResult(
    val manifest: ArKernelRuntimeAdapterDeploymentManifest?,
    val rejection: ArKernelRuntimeAdapterDeploymentRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelRuntimeAdapterDeploymentRejection.NONE && manifest != null
}

/**
 * One bounded host-declared separately packaged runtime adapter. All IDs,
 * versions and profiles are opaque tokens; the artifact digest is lowercase
 * SHA-256 hex. The state is always `declared`, never loaded or verified.
 */
data class ArKernelRuntimeAdapterDeploymentEntry(
    val index: Int,
    val adapterId: String,
    val adapterVersion: String,
    val backendProfile: String,
    val driverProfile: String,
    val artifactSha256: String,
    val sizeBytes: Long,
    val state: String = ADAPTER_STATE,
) {
    init {
        require(index >= 0) { "Invalid runtime adapter deployment index" }
        require(ADAPTER_TOKEN.matches(adapterId)) {
            "Invalid runtime adapter ID"
        }
        require(ADAPTER_TOKEN.matches(adapterVersion)) {
            "Invalid runtime adapter version"
        }
        require(ADAPTER_TOKEN.matches(backendProfile)) {
            "Invalid runtime adapter backend profile"
        }
        require(ADAPTER_TOKEN.matches(driverProfile)) {
            "Invalid runtime adapter driver profile"
        }
        require(ADAPTER_SHA256.matches(artifactSha256)) {
            "Invalid runtime adapter artifact digest"
        }
        require(sizeBytes in 1..ADAPTER_MAX_SIZE_BYTES) {
            "Invalid runtime adapter artifact size"
        }
        require(state == ADAPTER_STATE) {
            "Runtime adapter deployment entries must stay declared"
        }
    }
}

/**
 * Bounded canonical manifest for host-declared separately packaged runtime
 * adapters.
 *
 * It performs no adapter loading, artifact read, signature verification,
 * factory construction, startup sentinel, CPU-fallback execution, device-farm
 * run, I/O, upload or quality upgrade. The safety flags are requirements on
 * the host, not implemented capabilities, and the grade stays `unverified`.
 */
data class ArKernelRuntimeAdapterDeploymentManifest(
    val manifestId: String,
    val revision: Int = 1,
    val cpuFallbackRequired: Boolean = true,
    val startupSentinelRequired: Boolean = true,
    val readOnlyFactoryAdmission: Boolean = true,
    val modelNameFreeIdentity: Boolean = true,
    val separatePackagingRequired: Boolean = true,
    val adapters: List<ArKernelRuntimeAdapterDeploymentEntry>,
    val qualityGrade: String = ADAPTER_QUALITY,
) {
    init {
        require(ADAPTER_TOKEN.matches(manifestId)) {
            "Invalid runtime adapter deployment manifest ID"
        }
        require(revision in 1..ADAPTER_MAX_REVISION) {
            "Invalid runtime adapter deployment manifest revision"
        }
        require(cpuFallbackRequired && startupSentinelRequired &&
            readOnlyFactoryAdmission && modelNameFreeIdentity &&
            separatePackagingRequired) {
            "Runtime adapter deployment manifest must remain fail-closed"
        }
        require(qualityGrade == ADAPTER_QUALITY) {
            "Runtime adapter deployment manifest cannot assert assessed quality"
        }
        require(adapters.size in
            ADAPTER_MIN_ADAPTERS..ADAPTER_MAX_ADAPTERS) {
            "Invalid runtime adapter deployment count"
        }
        require(adapters.map { it.adapterId }.toSet().size == adapters.size) {
            "Runtime adapter deployment manifest contains duplicate IDs"
        }
        require(adapters.withIndex().all { it.value.index == it.index }) {
            "Runtime adapter deployment entries are not contiguous"
        }
    }
}

/** Bounded structural facts only; never a quality or conformance grade. */
data class ArKernelRuntimeAdapterDeploymentReport(
    val adapterCount: Int,
    val totalBytes: Long,
    val declaredCount: Int,
    val distinctBackendProfiles: Int,
    val distinctDriverProfiles: Int,
    val qualityGrade: String = ADAPTER_QUALITY,
)

/**
 * Canonical `ARKRTAD1` text adapter for a bounded host deployment manifest.
 *
 * Metadata order, input/line/adapter budgets, unique opaque IDs, contiguous
 * indices, lowercase SHA-256 digests, positive bounded byte sizes, opaque
 * backend/driver profiles and the fixed `declared` state are enforced
 * fail-closed. BOM/CRLF input with exactly one optional final separator is
 * accepted. The adapter never loads an adapter, verifies an artifact, runs a
 * factory/sentinel, uploads anything or upgrades `unverified`.
 */
object ArKernelRuntimeAdapterDeploymentBundle {
    fun encode(manifest: ArKernelRuntimeAdapterDeploymentManifest): String {
        val lines = ArrayList<String>(
            ADAPTER_KEYS.size + manifest.adapters.size + 2)
        lines.add(ADAPTER_HEADER)
        lines.add("version=$ADAPTER_VERSION")
        lines.add("manifest_id=${manifest.manifestId}")
        lines.add("revision=${manifest.revision}")
        lines.add("adapter_count=${manifest.adapters.size}")
        ADAPTER_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${manifest.qualityGrade}")
        for (entry in manifest.adapters) {
            lines.add(
                "adapter=${entry.index}|${entry.adapterId}|" +
                    "${entry.adapterVersion}|${entry.backendProfile}|" +
                    "${entry.driverProfile}|${entry.artifactSha256}|" +
                    "${entry.sizeBytes}|${entry.state}")
        }
        lines.add(ADAPTER_END)
        val text = lines.joinToString("\n")
        require(text.length <= ADAPTER_MAX_INPUT_CHARACTERS) {
            "Runtime adapter deployment manifest exceeds its bounded size"
        }
        require(lines.all { it.length <= ADAPTER_MAX_LINE_CHARACTERS }) {
            "Runtime adapter deployment line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelRuntimeAdapterDeploymentParseResult {
        if (text.length > ADAPTER_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INPUT_TOO_LARGE,
                0,
                "Runtime adapter deployment input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.EMPTY_PLAN, 0,
                "Runtime adapter deployment input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.EMPTY_PLAN, 0,
                "Runtime adapter deployment input contains no plan")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != ADAPTER_HEADER ||
            header.any { it.isWhitespace() } ||
            header.length > ADAPTER_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_HEADER, 1,
                "Runtime adapter deployment header is invalid")
        }
        if (lines.size < ADAPTER_KEYS.size + 3) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA, 0,
                "Runtime adapter deployment manifest is too short")
        }
        val lineByKey = ADAPTER_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(ADAPTER_KEYS.size)
        for (index in ADAPTER_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Runtime adapter deployment metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != ADAPTER_KEYS[index]) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Runtime adapter deployment metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != ADAPTER_VERSION) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported runtime adapter deployment version")
        }
        if (values.getValue("quality_grade") != ADAPTER_QUALITY) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Runtime adapter deployment cannot assert assessed quality")
        }
        for (key in ADAPTER_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Runtime adapter deployment safety invariant must be true")
            }
        }
        val manifestId = values.getValue("manifest_id")
        if (!ADAPTER_TOKEN.matches(manifestId)) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
                lineByKey.getValue("manifest_id"),
                "Invalid runtime adapter deployment manifest ID")
        }
        val revision = unsignedInt(values.getValue("revision"))
            ?: return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Invalid runtime adapter deployment revision")
        if (revision !in 1..ADAPTER_MAX_REVISION) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Runtime adapter deployment revision is out of bounds")
        }
        val adapterCount = unsignedInt(values.getValue("adapter_count"))
            ?: return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
                lineByKey.getValue("adapter_count"),
                "Invalid runtime adapter deployment count")
        if (adapterCount !in
            ADAPTER_MIN_ADAPTERS..ADAPTER_MAX_ADAPTERS) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_ADAPTER_COUNT,
                lineByKey.getValue("adapter_count"),
                "Runtime adapter deployment count is out of bounds")
        }
        val adapterStartIndex = ADAPTER_KEYS.size + 1
        val endIndex = adapterStartIndex + adapterCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA, 0,
                "Runtime adapter deployment line count does not match count")
        }
        val adapters =
            ArrayList<ArKernelRuntimeAdapterDeploymentEntry>(adapterCount)
        val seen = HashSet<String>(adapterCount)
        for (index in 0 until adapterCount) {
            val lineNumber = adapterStartIndex + index + 1
            val lineText = lines[adapterStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("adapter=")) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Runtime adapter deployment line is not an adapter")
            }
            val fields = lineText.substringAfter("adapter=").split('|')
            if (fields.size != 8) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Runtime adapter entry must have eight fields")
            }
            val entryIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
                    lineNumber, "Invalid runtime adapter deployment index")
            if (entryIndex != index) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_ORDER,
                    lineNumber,
                    "Runtime adapter deployment entries are not contiguous")
            }
            val adapterId = fields[1]
            if (!ADAPTER_TOKEN.matches(adapterId)) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
                    lineNumber, "Invalid runtime adapter ID")
            }
            if (!seen.add(adapterId)) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.DUPLICATE_ADAPTER_ID,
                    lineNumber,
                    "Runtime adapter deployment contains duplicate IDs")
            }
            val adapterVersion = fields[2]
            if (!ADAPTER_TOKEN.matches(adapterVersion)) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
                    lineNumber, "Invalid runtime adapter version")
            }
            val backendProfile = fields[3]
            if (!ADAPTER_TOKEN.matches(backendProfile)) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
                    lineNumber, "Invalid runtime adapter backend profile")
            }
            val driverProfile = fields[4]
            if (!ADAPTER_TOKEN.matches(driverProfile)) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
                    lineNumber, "Invalid runtime adapter driver profile")
            }
            val artifactSha256 = fields[5]
            if (!ADAPTER_SHA256.matches(artifactSha256)) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_DIGEST,
                    lineNumber, "Invalid runtime adapter artifact digest")
            }
            val sizeBytes = unsignedLong(fields[6])
                ?: return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
                    lineNumber, "Invalid runtime adapter artifact size")
            if (sizeBytes !in 1..ADAPTER_MAX_SIZE_BYTES) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
                    lineNumber,
                    "Runtime adapter artifact size is out of bounds")
            }
            val state = fields[7]
            if (state != ADAPTER_STATE) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_STATE,
                    lineNumber,
                    "Runtime adapter deployment entries must stay declared")
            }
            adapters += try {
                ArKernelRuntimeAdapterDeploymentEntry(
                    entryIndex,
                    adapterId,
                    adapterVersion,
                    backendProfile,
                    driverProfile,
                    artifactSha256,
                    sizeBytes,
                    state,
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid runtime adapter entry")
            }
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != ADAPTER_END) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
                endLineNumber,
                "Runtime adapter deployment manifest must end with END")
        }
        val manifest = try {
            ArKernelRuntimeAdapterDeploymentManifest(
                manifestId,
                revision,
                true,
                true,
                true,
                true,
                true,
                adapters,
                ADAPTER_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.UNSAFE_INVARIANT,
                0, error.message ?: "Invalid runtime adapter deployment")
        }
        return ArKernelRuntimeAdapterDeploymentParseResult(
            manifest,
            ArKernelRuntimeAdapterDeploymentRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(manifest: ArKernelRuntimeAdapterDeploymentManifest):
        ArKernelRuntimeAdapterDeploymentReport =
        ArKernelRuntimeAdapterDeploymentReport(
            adapterCount = manifest.adapters.size,
            totalBytes = manifest.adapters.sumOf { it.sizeBytes },
            declaredCount = manifest.adapters.count {
                it.state == ADAPTER_STATE
            },
            distinctBackendProfiles =
                manifest.adapters.map { it.backendProfile }.toSet().size,
            distinctDriverProfiles =
                manifest.adapters.map { it.driverProfile }.toSet().size,
            qualityGrade = manifest.qualityGrade,
        )

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelRuntimeAdapterDeploymentParseResult? {
        if (lineText.length > ADAPTER_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.LINE_TOO_LONG,
                lineNumber,
                "Runtime adapter deployment line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.EMPTY_LINE,
                lineNumber,
                "Runtime adapter deployment contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
                lineNumber,
                "Runtime adapter deployment lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (ADAPTER_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun unsignedLong(value: String): Long? =
        if (ADAPTER_UNSIGNED.matches(value)) value.toLongOrNull() else null

    private fun reject(
        rejection: ArKernelRuntimeAdapterDeploymentRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelRuntimeAdapterDeploymentParseResult =
        ArKernelRuntimeAdapterDeploymentParseResult(
            null, rejection, lineNumber, detail)
}
