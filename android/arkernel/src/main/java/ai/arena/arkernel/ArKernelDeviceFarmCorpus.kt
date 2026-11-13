package ai.arena.arkernel

private const val CORPUS_HEADER = "ARKCORPUS1"
private const val CORPUS_VERSION = "1"
private const val CORPUS_QUALITY = "unverified"
private const val CORPUS_END = "END"
private const val CORPUS_MAX_INPUT_CHARACTERS = 16_384
private const val CORPUS_MAX_LINE_CHARACTERS = 256
private const val CORPUS_MAX_ENTRIES = 64
private const val CORPUS_MAX_REQUIRED_DEVICES = 16
private const val CORPUS_MAX_REQUIRED_CONDITIONS = 6
private const val CORPUS_MAX_REQUIRED_SCENES = 9
private const val CORPUS_MAX_REQUIRED_CELLS = 64
private const val CORPUS_MAX_SHARE_PERMILLE = 1_000
private const val CORPUS_ROW_FIELDS = 7
private const val CORPUS_MIN_SAMPLES = 1L
private const val CORPUS_MAX_SAMPLES = 1_000_000L
private const val CORPUS_MAX_REVISION = 1_000_000

private val CORPUS_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val CORPUS_UNSIGNED = Regex("0|[1-9][0-9]*")

private val CORPUS_TRUE_KEYS = listOf(
    "automatic_capture_disabled",
    "automatic_upload_disabled",
    "manual_upload_only",
    "per_device_calibration",
    "images_disabled",
)

private val CORPUS_FALSE_KEYS = listOf("metric_accuracy_claimed")

private val CORPUS_KEYS = listOf(
    "version",
    "corpus_id",
    "corpus_revision",
    "required_devices",
    "required_conditions",
    "required_scenes",
    "required_matched_cells",
    "max_device_share_permille",
    "entry_count",
    "device_count",
    "device_class_count",
    "condition_count",
    "scene_count",
    "sample_total",
    "observed_max_device_share_permille",
    "total_cells",
    "matched_cells",
    "device_diversity_met",
    "conditions_covered",
    "scenes_covered",
    "balance_ok",
    "cross_device_ready",
    "corpus_sufficient",
    "automatic_capture_disabled",
    "automatic_upload_disabled",
    "manual_upload_only",
    "per_device_calibration",
    "images_disabled",
    "metric_accuracy_claimed",
    "quality_grade",
)

enum class ArKernelDeviceFarmCorpusRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_CORPUS,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_COUNT,
    INVALID_ORDER,
    INVALID_ROW,
    INVALID_CLASS,
    INVALID_CONDITION,
    INVALID_SCENE,
    DUPLICATE_ENTRY,
    STATISTICS_MISMATCH,
    CORPUS_MISMATCH,
}

/**
 * One capture inside a device-farm corpus.
 *
 * This is an index row, not the capture: `captureId` is an opaque host-owned
 * label for a recording that lives somewhere else. The row records only the
 * three axes a corpus has to be spread over — which device, under which
 * condition, on which scene kind — and how much of it there is.
 */
data class ArKernelCorpusEntry(
    val index: Int,
    val captureId: String,
    val deviceToken: String,
    val deviceClass: ArKernelAdaptiveDeviceClass,
    val condition: ArKernelMeasurementCondition,
    val sceneKind: ArKernelCampaignSceneKind,
    val sampleCount: Long,
)

/**
 * A bounded index of a device-farm corpus and what it can support.
 *
 * The point is the question a corpus has to answer before anyone compares
 * devices on it: **is the same cell present on more than one device?** A
 * (condition, scene) cell that exists on one phone only cannot support a
 * cross-device comparison, no matter how many samples it holds. `matchedCells`
 * counts exactly those cells, and `cross_device_ready` gates on them.
 *
 * The second question is dominance. A "device farm" in which one phone
 * supplies nine samples in ten is a single-device corpus with extra steps, so
 * the largest share any one device contributes is recomputed and compared
 * against a declared ceiling.
 *
 * What this cannot say is whether the corpus is *good*. Coverage and balance
 * are structural preconditions for a comparison, not evidence that the
 * comparison means anything. The adapter performs no capture, no inference, no
 * upload and no quality assessment.
 */
data class ArKernelDeviceFarmCorpus(
    val corpusId: String,
    val corpusRevision: Int,
    val requiredDevices: Int,
    val requiredConditions: Int,
    val requiredScenes: Int,
    val requiredMatchedCells: Int,
    val maxDeviceSharePermille: Int,
    val entries: List<ArKernelCorpusEntry>,
) {
    val entryCount: Int
        get() = entries.size

    val deviceCount: Int
        get() = entries.map { it.deviceToken }.distinct().size

    val deviceClassCount: Int
        get() = entries.map { it.deviceClass }.distinct().size

    val conditionCount: Int
        get() = entries.map { it.condition }.distinct().size

    val sceneCount: Int
        get() = entries.map { it.sceneKind }.distinct().size

    val sampleTotal: Long
        get() = entries.fold(0L) { total, entry -> total + entry.sampleCount }

    /** Largest share of the corpus contributed by any single device. */
    val observedMaxDeviceSharePermille: Int
        get() {
            val total = sampleTotal
            if (total == 0L) return 0
            return entries
                .groupingBy { it.deviceToken }
                .fold(0L) { sum, entry -> sum + entry.sampleCount }
                .values
                .maxOf { ((it * 1_000L) / total).toInt() }
        }

    /** Distinct (condition, scene) cells in the corpus. */
    val totalCells: Int
        get() = entries.map { it.condition to it.sceneKind }.distinct().size

    /** Cells that at least two different devices captured. */
    val matchedCells: Int
        get() = entries
            .groupBy { it.condition to it.sceneKind }
            .count { (_, rows) ->
                rows.map { it.deviceToken }.distinct().size >= 2
            }

    val deviceDiversityMet: Boolean
        get() = deviceCount >= requiredDevices

    val conditionsCovered: Boolean
        get() = conditionCount >= requiredConditions

    val scenesCovered: Boolean
        get() = sceneCount >= requiredScenes

    val balanceOk: Boolean
        get() = observedMaxDeviceSharePermille <= maxDeviceSharePermille

    val crossDeviceReady: Boolean
        get() = matchedCells >= requiredMatchedCells

    val corpusSufficient: Boolean
        get() = deviceDiversityMet &&
            conditionsCovered &&
            scenesCovered &&
            balanceOk &&
            crossDeviceReady

    /** Devices holding the largest share of the corpus. */
    fun dominantDeviceTokens(): List<String> {
        val total = sampleTotal
        if (total == 0L) return emptyList()
        val shares = entries
            .groupingBy { it.deviceToken }
            .fold(0L) { sum, entry -> sum + entry.sampleCount }
        val worst = shares.values.maxOf { ((it * 1_000L) / total).toInt() }
        return shares
            .filter { ((it.value * 1_000L) / total).toInt() == worst }
            .keys
            .sorted()
    }

    /** Cells no comparison can use, because only one device captured them. */
    fun unmatchedCells(): List<
        Pair<
            ArKernelMeasurementCondition,
            ArKernelCampaignSceneKind,
            >
        > = entries
        .groupBy { it.condition to it.sceneKind }
        .filter { (_, rows) ->
            rows.map { it.deviceToken }.distinct().size < 2
        }
        .keys
        .sortedBy { it.first.wireName() + "/" + it.second.wireName() }
}

data class ArKernelDeviceFarmCorpusSummary(
    val entryCount: Int,
    val deviceCount: Int,
    val deviceClassCount: Int,
    val conditionCount: Int,
    val sceneCount: Int,
    val sampleTotal: Long,
    val observedMaxDeviceSharePermille: Int,
    val totalCells: Int,
    val matchedCells: Int,
    val deviceDiversityMet: Boolean,
    val conditionsCovered: Boolean,
    val scenesCovered: Boolean,
    val balanceOk: Boolean,
    val crossDeviceReady: Boolean,
    val corpusSufficient: Boolean,
)

data class ArKernelDeviceFarmCorpusResult(
    val corpus: ArKernelDeviceFarmCorpus?,
    val rejection: ArKernelDeviceFarmCorpusRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelDeviceFarmCorpusRejection.NONE &&
            corpus != null
}

data class ArKernelDeviceFarmCorpusParseResult(
    val corpus: ArKernelDeviceFarmCorpus?,
    val rejection: ArKernelDeviceFarmCorpusRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelDeviceFarmCorpusRejection.NONE &&
            corpus != null
}

/**
 * Canonical `ARKCORPUS1` indexes of device-farm corpora.
 *
 * Every declared count and all five verdicts are recomputed on parse and any
 * disagreement is rejected, so a corpus cannot claim coverage or balance it
 * does not have. The adapter performs no capture, no inference, no file I/O,
 * no upload and no quality assessment; the grade stays `unverified`.
 */
object ArKernelDeviceFarmCorpusAdapter {

    fun create(
        corpusId: String,
        corpusRevision: Int,
        requiredDevices: Int,
        requiredConditions: Int,
        requiredScenes: Int,
        requiredMatchedCells: Int,
        maxDeviceSharePermille: Int,
        entries: List<ArKernelCorpusEntry>,
    ): ArKernelDeviceFarmCorpusResult {
        if (!CORPUS_TOKEN.matches(corpusId)) {
            return createReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_TOKEN,
                "Invalid corpus id")
        }
        if (corpusRevision < 1 || corpusRevision > CORPUS_MAX_REVISION) {
            return createReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                "Corpus revision is out of bounds")
        }
        if (requiredDevices < 1 ||
            requiredDevices > CORPUS_MAX_REQUIRED_DEVICES
        ) {
            return createReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
                "Required devices are out of bounds")
        }
        if (requiredConditions < 1 ||
            requiredConditions > CORPUS_MAX_REQUIRED_CONDITIONS
        ) {
            return createReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
                "Required conditions are out of bounds")
        }
        if (requiredScenes < 1 ||
            requiredScenes > CORPUS_MAX_REQUIRED_SCENES
        ) {
            return createReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
                "Required scenes are out of bounds")
        }
        if (requiredMatchedCells < 0 ||
            requiredMatchedCells > CORPUS_MAX_REQUIRED_CELLS
        ) {
            return createReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
                "Required matched cells are out of bounds")
        }
        if (maxDeviceSharePermille < 1 ||
            maxDeviceSharePermille > CORPUS_MAX_SHARE_PERMILLE
        ) {
            return createReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                "Maximum device share is out of bounds")
        }
        if (entries.isEmpty() || entries.size > CORPUS_MAX_ENTRIES) {
            return createReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
                "Corpus entry count is out of bounds")
        }
        val seen = HashSet<String>()
        for ((index, entry) in entries.withIndex()) {
            if (entry.index != index) {
                return createReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_ORDER,
                    "Corpus entries are not contiguous")
            }
            if (!CORPUS_TOKEN.matches(entry.captureId)) {
                return createReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_TOKEN,
                    "Invalid corpus capture id")
            }
            if (!seen.add(entry.captureId)) {
                return createReject(
                    ArKernelDeviceFarmCorpusRejection.DUPLICATE_ENTRY,
                    "Duplicate corpus capture id ${entry.captureId}")
            }
            if (!CORPUS_TOKEN.matches(entry.deviceToken)) {
                return createReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_TOKEN,
                    "Invalid corpus device token")
            }
            if (entry.sampleCount < CORPUS_MIN_SAMPLES ||
                entry.sampleCount > CORPUS_MAX_SAMPLES
            ) {
                return createReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                    "Corpus sample count is out of bounds")
            }
        }
        return ArKernelDeviceFarmCorpusResult(
            corpus = ArKernelDeviceFarmCorpus(
                corpusId = corpusId,
                corpusRevision = corpusRevision,
                requiredDevices = requiredDevices,
                requiredConditions = requiredConditions,
                requiredScenes = requiredScenes,
                requiredMatchedCells = requiredMatchedCells,
                maxDeviceSharePermille = maxDeviceSharePermille,
                entries = entries.toList(),
            ),
            rejection = ArKernelDeviceFarmCorpusRejection.NONE,
            detail = "accepted",
        )
    }

    fun summarize(
        corpus: ArKernelDeviceFarmCorpus,
    ): ArKernelDeviceFarmCorpusSummary =
        ArKernelDeviceFarmCorpusSummary(
            entryCount = corpus.entryCount,
            deviceCount = corpus.deviceCount,
            deviceClassCount = corpus.deviceClassCount,
            conditionCount = corpus.conditionCount,
            sceneCount = corpus.sceneCount,
            sampleTotal = corpus.sampleTotal,
            observedMaxDeviceSharePermille =
                corpus.observedMaxDeviceSharePermille,
            totalCells = corpus.totalCells,
            matchedCells = corpus.matchedCells,
            deviceDiversityMet = corpus.deviceDiversityMet,
            conditionsCovered = corpus.conditionsCovered,
            scenesCovered = corpus.scenesCovered,
            balanceOk = corpus.balanceOk,
            crossDeviceReady = corpus.crossDeviceReady,
            corpusSufficient = corpus.corpusSufficient,
        )

    fun encode(corpus: ArKernelDeviceFarmCorpus): String {
        val lines =
            ArrayList<String>(CORPUS_KEYS.size + corpus.entries.size)
        lines += CORPUS_HEADER
        lines += "version=$CORPUS_VERSION"
        lines += "corpus_id=${corpus.corpusId}"
        lines += "corpus_revision=${corpus.corpusRevision}"
        lines += "required_devices=${corpus.requiredDevices}"
        lines += "required_conditions=${corpus.requiredConditions}"
        lines += "required_scenes=${corpus.requiredScenes}"
        lines += "required_matched_cells=${corpus.requiredMatchedCells}"
        lines += "max_device_share_permille=${corpus.maxDeviceSharePermille}"
        lines += "entry_count=${corpus.entryCount}"
        lines += "device_count=${corpus.deviceCount}"
        lines += "device_class_count=${corpus.deviceClassCount}"
        lines += "condition_count=${corpus.conditionCount}"
        lines += "scene_count=${corpus.sceneCount}"
        lines += "sample_total=${corpus.sampleTotal}"
        lines += "observed_max_device_share_permille=" +
            corpus.observedMaxDeviceSharePermille
        lines += "total_cells=${corpus.totalCells}"
        lines += "matched_cells=${corpus.matchedCells}"
        lines += "device_diversity_met=" +
            boolText(corpus.deviceDiversityMet)
        lines += "conditions_covered=${boolText(corpus.conditionsCovered)}"
        lines += "scenes_covered=${boolText(corpus.scenesCovered)}"
        lines += "balance_ok=${boolText(corpus.balanceOk)}"
        lines += "cross_device_ready=${boolText(corpus.crossDeviceReady)}"
        lines += "corpus_sufficient=${boolText(corpus.corpusSufficient)}"
        for (key in CORPUS_TRUE_KEYS) lines += "$key=true"
        for (key in CORPUS_FALSE_KEYS) lines += "$key=false"
        lines += "quality_grade=$CORPUS_QUALITY"
        for (entry in corpus.entries) lines += entryRow(entry)
        lines += CORPUS_END
        return lines.joinToString("\n") + "\n"
    }

    fun parse(text: String): ArKernelDeviceFarmCorpusParseResult {
        if (text.length > CORPUS_MAX_INPUT_CHARACTERS * 2) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INPUT_TOO_LARGE, 0,
                "Corpus index exceeds its bounded length")
        }
        if (text.isBlank()) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.EMPTY_CORPUS, 0,
                "Corpus index is empty")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        val lines = normalized.trimEnd('\n').split('\n')
        val headerError = validateLine(lines[0].trimEnd('\r'), 1)
        if (headerError != null) return headerError
        if (lines[0].trimEnd('\r') != CORPUS_HEADER) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_HEADER, 1,
                "Corpus index must start with ARKCORPUS1")
        }
        if (lines.size < CORPUS_KEYS.size + 2) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_SCHEMA, 1,
                "Corpus index is missing its key block")
        }
        val values = LinkedHashMap<String, String>()
        val lineByKey = LinkedHashMap<String, Int>()
        for ((offset, key) in CORPUS_KEYS.withIndex()) {
            val lineNumber = offset + 2
            val lineText = lines[offset + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val separator = lineText.indexOf('=')
            if (separator <= 0) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_SCHEMA,
                    lineNumber, "Corpus line is not a key")
            }
            if (lineText.substring(0, separator) != key) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_SCHEMA,
                    lineNumber, "Corpus key order is invalid")
            }
            values[key] = lineText.substring(separator + 1)
            lineByKey[key] = lineNumber
        }
        if (values.getValue("version") != CORPUS_VERSION) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Corpus version must be one")
        }
        if (values.getValue("quality_grade") != CORPUS_QUALITY) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "A corpus index cannot assert assessed quality")
        }
        for (key in CORPUS_TRUE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Corpus flag must be a boolean")
            }
            if (value != "true") {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Corpus safety invariant must be true")
            }
        }
        for (key in CORPUS_FALSE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Corpus flag must be a boolean")
            }
            if (value != "false") {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Corpus safety invariant must be false")
            }
        }
        if (!CORPUS_TOKEN.matches(values.getValue("corpus_id"))) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_TOKEN,
                lineByKey.getValue("corpus_id"),
                "Invalid corpus id")
        }
        val ints = LinkedHashMap<String, Int>()
        for (key in listOf(
            "corpus_revision",
            "required_devices",
            "required_conditions",
            "required_scenes",
            "required_matched_cells",
            "max_device_share_permille",
            "entry_count",
        )) {
            val raw = unsigned(values.getValue(key))
                ?: return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                    lineByKey.getValue(key),
                    "Invalid corpus count")
            if (raw > 1_000_000L) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
                    lineByKey.getValue(key),
                    "Corpus count is out of bounds")
            }
            ints[key] = raw.toInt()
        }
        val requiredDevices = ints.getValue("required_devices")
        val requiredConditions = ints.getValue("required_conditions")
        val requiredScenes = ints.getValue("required_scenes")
        val requiredCells = ints.getValue("required_matched_cells")
        val maxShare = ints.getValue("max_device_share_permille")
        val entryCount = ints.getValue("entry_count")
        if (requiredDevices < 1 ||
            requiredDevices > CORPUS_MAX_REQUIRED_DEVICES ||
            requiredConditions < 1 ||
            requiredConditions > CORPUS_MAX_REQUIRED_CONDITIONS ||
            requiredScenes < 1 ||
            requiredScenes > CORPUS_MAX_REQUIRED_SCENES ||
            requiredCells < 0 ||
            requiredCells > CORPUS_MAX_REQUIRED_CELLS ||
            maxShare < 1 ||
            maxShare > CORPUS_MAX_SHARE_PERMILLE ||
            entryCount < 1 ||
            entryCount > CORPUS_MAX_ENTRIES
        ) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_COUNT,
                lineByKey.getValue("required_devices"),
                "A declared corpus requirement is out of bounds")
        }
        val declared = LinkedHashMap<String, Long>()
        for (key in listOf(
            "device_count",
            "device_class_count",
            "condition_count",
            "scene_count",
            "sample_total",
            "observed_max_device_share_permille",
            "total_cells",
            "matched_cells",
        )) {
            val raw = unsigned(values.getValue(key))
                ?: return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                    lineByKey.getValue(key),
                    "Invalid corpus statistic")
            declared[key] = raw
        }
        for (key in listOf(
            "device_diversity_met",
            "conditions_covered",
            "scenes_covered",
            "balance_ok",
            "cross_device_ready",
            "corpus_sufficient",
        )) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Corpus verdict must be a boolean")
            }
        }
        val expectedRows = CORPUS_KEYS.size + 1 + entryCount + 1
        if (lines.size != expectedRows) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_SCHEMA,
                lines.size,
                "Corpus row count does not match entry_count")
        }
        val entries = ArrayList<ArKernelCorpusEntry>(entryCount)
        for (index in 0 until entryCount) {
            val lineNumber = CORPUS_KEYS.size + 2 + index
            val rowText = lines[CORPUS_KEYS.size + 1 + index].trimEnd('\r')
            val lineError = validateLine(rowText, lineNumber)
            if (lineError != null) return lineError
            if (!rowText.startsWith("entry=")) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_ROW,
                    lineNumber, "Corpus entry row is missing")
            }
            val fields = rowText.substring("entry=".length).split('|')
            if (fields.size != CORPUS_ROW_FIELDS) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_ROW,
                    lineNumber, "Corpus entry must have seven fields")
            }
            val rowIndex = unsigned(fields[0])
                ?: return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                    lineNumber, "Invalid corpus entry index")
            if (rowIndex.toInt() != index) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_ORDER,
                    lineNumber, "Corpus entries are not contiguous")
            }
            if (!CORPUS_TOKEN.matches(fields[1]) ||
                !CORPUS_TOKEN.matches(fields[2])
            ) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_TOKEN,
                    lineNumber, "Invalid corpus entry token")
            }
            val deviceClass = ArKernelAdaptiveDeviceClass
                .fromWire(fields[3])
                ?: return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_CLASS,
                    lineNumber, "Invalid device class")
            val condition = ArKernelMeasurementCondition.fromWire(fields[4])
                ?: return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_CONDITION,
                    lineNumber, "Invalid condition label")
            val sceneKind = ArKernelCampaignSceneKind.fromWire(fields[5])
                ?: return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_SCENE,
                    lineNumber, "Invalid scene kind")
            val sampleCount = unsigned(fields[6])
                ?: return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                    lineNumber, "Invalid corpus sample count")
            if (sampleCount < CORPUS_MIN_SAMPLES ||
                sampleCount > CORPUS_MAX_SAMPLES
            ) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.INVALID_VALUE,
                    lineNumber, "Corpus sample count is out of bounds")
            }
            entries += ArKernelCorpusEntry(
                index = index,
                captureId = fields[1],
                deviceToken = fields[2],
                deviceClass = deviceClass,
                condition = condition,
                sceneKind = sceneKind,
                sampleCount = sampleCount,
            )
        }
        val endLine = lines[lines.size - 1].trimEnd('\r')
        if (endLine != CORPUS_END) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_SCHEMA,
                lines.size, "Corpus index must end with END")
        }
        val result = create(
            corpusId = values.getValue("corpus_id"),
            corpusRevision = ints.getValue("corpus_revision"),
            requiredDevices = requiredDevices,
            requiredConditions = requiredConditions,
            requiredScenes = requiredScenes,
            requiredMatchedCells = requiredCells,
            maxDeviceSharePermille = maxShare,
            entries = entries,
        )
        if (!result.accepted) {
            return corpusReject(
                result.rejection, CORPUS_KEYS.size + 2, result.detail)
        }
        val corpus = result.corpus
            ?: return corpusReject(
                ArKernelDeviceFarmCorpusRejection.INVALID_SCHEMA, 1,
                "Corpus could not be rebuilt")
        val expected = mapOf(
            "device_count" to corpus.deviceCount.toLong(),
            "device_class_count" to corpus.deviceClassCount.toLong(),
            "condition_count" to corpus.conditionCount.toLong(),
            "scene_count" to corpus.sceneCount.toLong(),
            "sample_total" to corpus.sampleTotal,
            "observed_max_device_share_permille" to
                corpus.observedMaxDeviceSharePermille.toLong(),
            "total_cells" to corpus.totalCells.toLong(),
            "matched_cells" to corpus.matchedCells.toLong(),
        )
        for ((key, value) in expected) {
            if (declared.getValue(key) != value) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.STATISTICS_MISMATCH,
                    lineByKey.getValue(key),
                    "Recomputed $key disagrees with the declared value")
            }
        }
        val verdicts = mapOf(
            "device_diversity_met" to corpus.deviceDiversityMet,
            "conditions_covered" to corpus.conditionsCovered,
            "scenes_covered" to corpus.scenesCovered,
            "balance_ok" to corpus.balanceOk,
            "cross_device_ready" to corpus.crossDeviceReady,
            "corpus_sufficient" to corpus.corpusSufficient,
        )
        for ((key, value) in verdicts) {
            if ((values.getValue(key) == "true") != value) {
                return corpusReject(
                    ArKernelDeviceFarmCorpusRejection.CORPUS_MISMATCH,
                    lineByKey.getValue(key),
                    "Recomputed $key disagrees with the declared verdict")
            }
        }
        return ArKernelDeviceFarmCorpusParseResult(
            corpus = corpus,
            rejection = ArKernelDeviceFarmCorpusRejection.NONE,
            lineNumber = 0,
            detail = "accepted",
        )
    }

    private fun entryRow(entry: ArKernelCorpusEntry): String =
        "entry=" + listOf(
            entry.index.toString(),
            entry.captureId,
            entry.deviceToken,
            entry.deviceClass.wireName(),
            entry.condition.wireName(),
            entry.sceneKind.wireName(),
            entry.sampleCount.toString(),
        ).joinToString("|")

    private fun boolText(value: Boolean): String =
        if (value) "true" else "false"

    private fun unsigned(value: String): Long? =
        if (CORPUS_UNSIGNED.matches(value)) value.toLongOrNull() else null

    private fun validateLine(
        line: String,
        lineNumber: Int,
    ): ArKernelDeviceFarmCorpusParseResult? {
        if (line.isEmpty()) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.EMPTY_LINE,
                lineNumber, "Corpus line is empty")
        }
        if (line.length > CORPUS_MAX_LINE_CHARACTERS) {
            return corpusReject(
                ArKernelDeviceFarmCorpusRejection.LINE_TOO_LONG,
                lineNumber, "Corpus line is too long")
        }
        return null
    }

    private fun corpusReject(
        rejection: ArKernelDeviceFarmCorpusRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelDeviceFarmCorpusParseResult =
        ArKernelDeviceFarmCorpusParseResult(
            corpus = null,
            rejection = rejection,
            lineNumber = lineNumber,
            detail = detail,
        )

    private fun createReject(
        rejection: ArKernelDeviceFarmCorpusRejection,
        detail: String,
    ): ArKernelDeviceFarmCorpusResult =
        ArKernelDeviceFarmCorpusResult(
            corpus = null,
            rejection = rejection,
            detail = detail,
        )
}
