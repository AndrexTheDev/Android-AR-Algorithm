package ai.arena.arkernel

/**
 * A bounded inventory of the contract files the diagnostics app has written.
 *
 * Twenty screens each produce one file and none of them can see the others,
 * so the question "what is still missing?" had no answer anywhere: the app's
 * own checklist admitted as much. This catalogue answers it from the files
 * themselves.
 *
 * It does three things and nothing else:
 *
 *  - it recognises which contract a filename belongs to;
 *  - it hands each file to that contract's own parser, so a truncated or
 *    edited file is reported as rejected rather than counted as present;
 *  - it lists the verdicts the file itself reports as `false`.
 *
 * Files that are not contract blocks - notably the public key export the
 * signature screen writes under the `ARKSIGN1_` prefix - are listed as what
 * they are instead of being handed to a parser that would reject them.
 *
 * Three deliberate limits. The verdict scan is **textual**: it reads
 * `key=false` lines out of canonical text and does not interpret them, so
 * `accel_repeat_checked=false` is reported as an open verdict without any
 * claim about whether that is a fault or simply unfinished work. The
 * inventory is **structural**: a present, accepted file with no open verdict
 * has been collected, which is not the same as being any good. And the
 * manifest binds **digests**, which detect a change, not a forgery - a
 * manifest signed by `ARKSIGN1` is what ties the bytes to a key.
 *
 * No file is read, written, uploaded or deleted here. The host supplies the
 * names, sizes, timestamps and digests it measured; this file only arranges
 * them. The grade stays `unverified`.
 */

private val ARTIFACT_SHA256 = Regex("[0-9a-f]{64}")
private val ARTIFACT_TOKEN_CHAR = Regex("[A-Za-z0-9._:-]")
private val ARTIFACT_VERDICT_LINE = Regex("([a-z0-9_]+)=(true|false)")

/** A contract the diagnostics app can produce, in campaign order. */
enum class ArKernelArtifactContract(
    val token: String,
    val button: Int,
    val purpose: String,
) {
    FDPOL("ARKFDPOL1", 1, "Field diagnostics policy"),
    FDPLAN("ARKFDPLAN1", 2, "Bounded test plan for one run"),
    FDLOG("ARKFDLOG1", 1, "Result log of one plan run"),
    ADAPT("ARKADAPT1", 6, "Device-adaptive class selection"),
    CAMPAIGN("ARKCAMPAIGN1", 7, "Measurement campaign and questions"),
    CALIB("ARKCALIB1", 5, "Physical calibration observations"),
    IMUCAL("ARKIMUCAL1", 20, "Inertial intrinsic calibration"),
    CAMTIM("ARKCAMT1", 22, "Camera cadence, clock domain and exposure"),
    DEVHW("ARKDEVHW1", 23, "Device inventory, throttling and clocks"),
    MEAS("ARKMEAS1", 11, "Guided measurement execution"),
    FEED("ARKFEED1", 12, "Live camera feed markers"),
    SCALE("ARKSCALE1", 13, "Ruler mark detection"),
    COND("ARKCOND1", 18, "Rig, thermal and day/night conditions"),
    TARGET("ARKTARGET1", 17, "Independent scale targets"),
    VALID("ARKVALID1", 14, "Metric deviation against a tape"),
    CROSS("ARKCROSS1", 9, "Cross-device comparison"),
    CORPUS("ARKCORPUS1", 19, "Device-farm corpus index"),
    BIND("ARKBIND1", 8, "Session binding of three artifacts"),
    RETENT("ARKRETENT1", 10, "Retention decisions"),
    SIGN("ARKSIGN1", 15, "Signature manifest and verification"),
    REDRUN("ARKREDRUN1", 16, "Redaction and upload admission"),
    DSMAN("ARKDSMAN1", 21, "Digest manifest over artifacts"),
    ;

    companion object {
        /** Longest token first, so a longer token is never read as a shorter one. */
        private val BY_TOKEN_LENGTH: List<ArKernelArtifactContract> =
            values().sortedByDescending { it.token.length }

        fun ofToken(token: String): ArKernelArtifactContract? =
            values().firstOrNull { it.token == token }
    }
}

/**
 * What a file is. Only `CONTRACT` files are verified; the others exist so the
 * inventory can list them without pretending they are something they are not.
 */
enum class ArKernelArtifactKind {
    /** A canonical contract block, verified against its own parser. */
    CONTRACT,

    /**
     * A public key export. The signature screen writes one alongside the
     * manifests, using the same `ARKSIGN1_` prefix, but it is a key and not a
     * manifest: handing it to the manifest parser would reject it every time
     * and report a permanent false alarm on the one screen that exists to
     * establish trust.
     */
    PUBLIC_KEY,

    /** Anything else: a note, a photo, a file copied in from somewhere. */
    UNKNOWN,
}

/** One file as the host measured it. The host owns the I/O; this is the report. */
data class ArKernelArtifactFile(
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val sha256Hex: String,
) {
    init {
        require(name.isNotBlank()) { "Invalid artifact file name" }
        require(sizeBytes >= 0L) { "Invalid artifact file size" }
        require(modifiedMs >= 0L) { "Invalid artifact modification time" }
        require(ARTIFACT_SHA256.matches(sha256Hex)) {
            "Invalid artifact SHA-256 hex digest"
        }
    }
}

/** One file read back through its own contract. */
data class ArKernelArtifactReading(
    val file: ArKernelArtifactFile,
    val kind: ArKernelArtifactKind,
    val contract: ArKernelArtifactContract?,
    val accepted: Boolean,
    val rejection: String,
    val detail: String,
    val openVerdicts: List<String>,
) {
    /** Not usable as collected: either it will not parse, or a gate is open. */
    val hasOpenWork: Boolean
        get() = !accepted || openVerdicts.isNotEmpty()
}

/** Everything the inventory knows about one contract. */
data class ArKernelArtifactState(
    val contract: ArKernelArtifactContract,
    val readings: List<ArKernelArtifactReading>,
) {
    val fileCount: Int get() = readings.size
    val acceptedCount: Int get() = readings.count { it.accepted }
    val rejectedCount: Int get() = readings.count { !it.accepted }
    val newestModifiedMs: Long
        get() = readings.maxOfOrNull { it.file.modifiedMs } ?: 0L
    val openVerdicts: List<String>
        get() = readings.flatMap { it.openVerdicts }.distinct().sorted()
    val present: Boolean get() = readings.isNotEmpty()

    /** Present, every copy parses, and no verdict left open. */
    val collected: Boolean
        get() = present && rejectedCount == 0 && openVerdicts.isEmpty()
}

/** The inventory of one device at one moment. */
data class ArKernelArtifactInventory(
    val readings: List<ArKernelArtifactReading>,
    val states: List<ArKernelArtifactState>,
    val unrecognised: List<String>,
    val publicKeys: List<String> = emptyList(),
) {
    val missing: List<ArKernelArtifactContract>
        get() = states.filter { !it.present }.map { it.contract }

    val incomplete: List<ArKernelArtifactContract>
        get() = states.filter { it.present && !it.collected }.map { it.contract }

    /** The first contract in campaign order that is missing, else the first open one. */
    val next: ArKernelArtifactContract?
        get() = missing.firstOrNull() ?: incomplete.firstOrNull()

    val totalBytes: Long get() = readings.sumOf { it.file.sizeBytes }
    val rejectedCount: Int get() = readings.count { !it.accepted }

    /** File count for one contract, or zero when it was never produced. */
    fun countOf(contract: ArKernelArtifactContract): Int =
        states.firstOrNull { it.contract == contract }?.fileCount ?: 0
}

/** Fail-closed manifest build outcome; `manifest` is null whenever `error` is set. */
data class ArKernelArtifactManifestResult(
    val manifest: ArKernelDatasetManifest?,
    val error: String?,
    val omitted: List<String>,
    val droppedEmpty: Int,
) {
    val accepted: Boolean get() = manifest != null && error == null
}

/**
 * The manifest's size budget is 8,192 characters and each entry costs about
 * 135, so the contract's own ceiling of 64 entries does not fit once file
 * names are long. 48 leaves room without discovering the limit at runtime.
 */
const val ARK_ARTIFACT_MANIFEST_MAX_ENTRIES = 48

/** The `ARKDSMAN1` entry ceiling, restated here so the clamp has a name. */
const val ARK_ARTIFACT_MANIFEST_CEILING = 64

/** These files were produced on the device; no external corpus licence applies. */
const val ARK_ARTIFACT_LICENSE_TOKEN = "local.device"

/** Role for a file no contract recognises, so it is still bound and visible. */
const val ARK_ARTIFACT_UNCLASSIFIED_TOKEN = "unclassified"

object ArKernelArtifactCatalogue {

    /**
     * Verdict suffixes a contract uses for its gates. Matching on the suffix
     * keeps this file ignorant of the nineteen contracts it reads; what it
     * loses is the ability to say whether a false gate is a fault or simply
     * unfinished work, and it does not try.
     */
    private val VERDICT_SUFFIXES = listOf(
        "_ok",
        "_sufficient",
        "_checked",
        "_ready",
        "_consistent",
        "_admitted",
        "_covered",
        "_valid",
        "_usable",
        "_complete",
        "_pass",
        "_passing",
    )

    /**
     * Key exports the signature screen writes next to its manifests. They
     * share the `ARKSIGN1_` prefix but are not manifests, so they are listed
     * rather than parsed.
     */
    private val PUBLIC_KEY_PATTERNS = listOf(
        Regex("ARKSIGN1_pubkey_.*"),
        Regex("ARKSIGN1_publickey.*"),
    )

    /** How much of a file is scanned. Anything past this is ignored, not reported. */
    private const val SCAN_CHARACTERS = 200_000
    private const val MAX_OPEN_VERDICTS = 32
    private const val MAX_ID_LENGTH = 64

    /** Which contract a filename belongs to, by longest matching token. */
    fun contractOf(fileName: String): ArKernelArtifactContract? {
        val ordered = ArKernelArtifactContract.values()
            .sortedByDescending { it.token.length }
        return ordered.firstOrNull { fileName.contains(it.token) }
    }

    /**
     * Verdicts a canonical artifact reports as `false`, read as text.
     *
     * This is a reader, not an interpreter: it finds lines of the form
     * `key=false` whose key ends in a gate suffix. It cannot tell a failed
     * check from work that was never done, and it says nothing about a file it
     * cannot parse - a rejected file is reported through `rejection` instead.
     */
    fun openVerdicts(text: String): List<String> {
        val body = if (text.length > SCAN_CHARACTERS) {
            text.substring(0, SCAN_CHARACTERS)
        } else {
            text
        }
        val found = LinkedHashSet<String>()
        for (line in body.lineSequence()) {
            val match = ARTIFACT_VERDICT_LINE.matchEntire(line.trimEnd('\r'))
                ?: continue
            if (match.groupValues[2] != "false") continue
            val key = match.groupValues[1]
            if (VERDICT_SUFFIXES.none { key.endsWith(it) }) continue
            found.add(key)
            if (found.size >= MAX_OPEN_VERDICTS) break
        }
        return found.sorted()
    }

    /** What a file is, before anything tries to parse it. */
    fun kindOf(fileName: String): ArKernelArtifactKind = when {
        PUBLIC_KEY_PATTERNS.any { it.matches(fileName) } ->
            ArKernelArtifactKind.PUBLIC_KEY
        contractOf(fileName) != null -> ArKernelArtifactKind.CONTRACT
        else -> ArKernelArtifactKind.UNKNOWN
    }

    /** Verifies one artifact against its own contract's parser. */
    fun read(file: ArKernelArtifactFile, text: String): ArKernelArtifactReading {
        val kind = kindOf(file.name)
        val contract = if (kind == ArKernelArtifactKind.CONTRACT) {
            contractOf(file.name)
        } else {
            null
        }
        if (contract == null) {
            return ArKernelArtifactReading(
                file = file,
                kind = kind,
                contract = null,
                accepted = false,
                rejection = if (kind == ArKernelArtifactKind.PUBLIC_KEY) {
                    "NOT_A_CONTRACT_FILE"
                } else {
                    "UNRECOGNISED"
                },
                detail = if (kind == ArKernelArtifactKind.PUBLIC_KEY) {
                    "A public key export, not a manifest"
                } else {
                    "No contract matches the file name"
                },
                openVerdicts = emptyList(),
            )
        }
        val outcome = verify(contract, text)
        return ArKernelArtifactReading(
            file = file,
            kind = kind,
            contract = contract,
            accepted = outcome.accepted,
            rejection = outcome.rejection,
            detail = outcome.detail,
            openVerdicts = if (outcome.accepted) openVerdicts(text) else emptyList(),
        )
    }

    /** Groups readings into one state per contract, in campaign order. */
    fun inventory(readings: List<ArKernelArtifactReading>): ArKernelArtifactInventory {
        val states = ArKernelArtifactContract.values().map { contract ->
            ArKernelArtifactState(
                contract = contract,
                readings = readings.filter {
                    it.kind == ArKernelArtifactKind.CONTRACT &&
                        it.contract == contract
                },
            )
        }
        val unrecognised = readings
            .filter { it.kind == ArKernelArtifactKind.UNKNOWN }
            .map { it.file.name }
            .sorted()
        val publicKeys = readings
            .filter { it.kind == ArKernelArtifactKind.PUBLIC_KEY }
            .map { it.file.name }
            .sorted()
        return ArKernelArtifactInventory(
            readings = readings,
            states = states,
            unrecognised = unrecognised,
            publicKeys = publicKeys,
        )
    }

    /**
     * Binds files into an `ARKDSMAN1` manifest.
     *
     * The result is a digest list: it detects that a file changed after the
     * manifest was written. It does not prove who wrote it - that is what
     * signing the manifest with `ARKSIGN1` is for.
     *
     * Files with no bytes are dropped rather than padded, because a manifest
     * entry requires a positive size. Names are folded into the manifest token
     * alphabet and truncated; two names that collapse onto the same token are
     * disambiguated by index instead of being silently merged. When there are
     * more files than fit, the newest are kept and the rest are named in the
     * result so the host can say so rather than implying the list was whole.
     */
    fun manifest(
        manifestId: String,
        files: List<ArKernelArtifactFile>,
        revision: Int = 1,
        maxEntries: Int = ARK_ARTIFACT_MANIFEST_MAX_ENTRIES,
    ): ArKernelArtifactManifestResult {
        val empty = files.filter { it.sizeBytes == 0L }.map { it.name }
        val ordered = files
            .filter { it.sizeBytes > 0L }
            .sortedWith(
                compareByDescending<ArKernelArtifactFile> { it.modifiedMs }
                    .thenBy { it.name },
            )
        val limit = maxEntries.coerceIn(1, ARK_ARTIFACT_MANIFEST_CEILING)
        val kept = ordered.take(limit)
        val omitted = ordered.drop(limit).map { it.name } + empty
        if (kept.isEmpty()) {
            return ArKernelArtifactManifestResult(
                manifest = null,
                error = "No artifact carries any bytes",
                omitted = omitted,
                droppedEmpty = empty.size,
            )
        }
        val ids = disambiguate(kept.map { token(it.name) })
        return try {
            val manifest = ArKernelDatasetManifest(
                manifestId = token(manifestId),
                revision = revision,
                entries = kept.mapIndexed { index, file ->
                    ArKernelDatasetManifestEntry(
                        index = index,
                        datasetId = ids[index],
                        licenseId = ARK_ARTIFACT_LICENSE_TOKEN,
                        sha256Hex = file.sha256Hex,
                        sizeBytes = file.sizeBytes,
                        role = contractOf(file.name)?.token
                            ?: ARK_ARTIFACT_UNCLASSIFIED_TOKEN,
                    )
                },
            )
            ArKernelArtifactManifestResult(
                manifest = manifest,
                error = null,
                omitted = omitted,
                droppedEmpty = empty.size,
            )
        } catch (error: IllegalArgumentException) {
            ArKernelArtifactManifestResult(
                manifest = null,
                error = error.message ?: "Manifest rejected",
                omitted = omitted,
                droppedEmpty = empty.size,
            )
        }
    }

    /** Folds a host string into the manifest token alphabet, at most 64 characters. */
    fun token(value: String): String {
        val folded = buildString {
            for (character in value) {
                append(if (ARTIFACT_TOKEN_CHAR.matches(character.toString())) {
                    character
                } else {
                    '.'
                })
            }
        }
        val trimmed = if (folded.length <= MAX_ID_LENGTH) {
            folded
        } else {
            folded.substring(folded.length - MAX_ID_LENGTH)
        }
        val cleaned = trimmed.trim('.')
        return if (cleaned.isEmpty()) "artifact" else cleaned
    }

    private fun disambiguate(ids: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return ids.map { id ->
            val count = seen[id] ?: 0
            seen[id] = count + 1
            if (count == 0) id else suffix(id, count)
        }
    }

    private fun suffix(id: String, count: Int): String {
        val tag = "-$count"
        return if (id.length + tag.length <= MAX_ID_LENGTH) {
            id + tag
        } else {
            id.substring(0, MAX_ID_LENGTH - tag.length) + tag
        }
    }

    private fun verify(
        contract: ArKernelArtifactContract,
        text: String,
    ): ArKernelArtifactVerification = when (contract) {
        ArKernelArtifactContract.FDPOL -> {
            val result = ArKernelFieldDiagnosticsPolicyBundle.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.FDPLAN -> {
            val result = ArKernelFieldDiagnosticsTestPlanBundle.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.FDLOG -> {
            val result = ArKernelFieldDiagnosticsRunLog.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.ADAPT -> {
            val result = ArKernelDeviceAdaptiveProfileAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.CAMPAIGN -> {
            val result = ArKernelCampaignPlanAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.CALIB -> {
            val result = ArKernelCalibrationEvidenceAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.IMUCAL -> {
            val result = ArKernelImuCalibrationAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.CAMTIM -> {
            val result = ArKernelCameraTimingAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.DEVHW -> {
            val result = ArKernelDeviceHardwareAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.MEAS -> {
            val result = ArKernelMeasurementAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.FEED -> {
            val result = ArKernelMeasurementFeedAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.SCALE -> {
            val result = ArKernelRulerScaleAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.COND -> {
            val result = ArKernelConditionEvidenceAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.TARGET -> {
            val result = ArKernelIndependentTargetsAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.VALID -> {
            val result = ArKernelMetricValidationAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.CROSS -> {
            val result = ArKernelCrossDeviceComparisonAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.CORPUS -> {
            val result = ArKernelDeviceFarmCorpusAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.BIND -> {
            val result = ArKernelSessionBindingAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.RETENT -> {
            val result = ArKernelRetentionRunLedgerAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.SIGN -> {
            val result = ArKernelSignatureManifestAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.REDRUN -> {
            val result = ArKernelRedactionRunAdapter.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
        ArKernelArtifactContract.DSMAN -> {
            val result = ArKernelDatasetManifestBundle.parse(text)
            ArKernelArtifactVerification(
                result.accepted, result.rejection.name, result.detail)
        }
    }
}

/** The one shape every contract's parse result shares, named once. */
data class ArKernelArtifactVerification(
    val accepted: Boolean,
    val rejection: String,
    val detail: String,
)
