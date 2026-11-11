package ai.arena.arkernel

private const val FAULT_HEADER = "ARKFAULT1"
private const val FAULT_VERSION = "1"
private const val FAULT_QUALITY = "unverified"
private const val FAULT_END = "END"
private const val FAULT_MAX_INPUT_CHARACTERS = 8_192
private const val FAULT_MAX_LINE_CHARACTERS = 256
private const val FAULT_MIN_SCENARIOS = 1
private const val FAULT_MAX_SCENARIOS = 45
private const val FAULT_MIN_CAMERA_SAMPLES = 20
private const val FAULT_MAX_CAMERA_SAMPLES = 512
private const val FAULT_MIN_IMU_SAMPLES = 50
private const val FAULT_MAX_IMU_SAMPLES = 2_048
private const val FAULT_MAX_REVISION = 1_000_000

private val FAULT_TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")
private val FAULT_UNSIGNED = Regex("0|[1-9][0-9]*")
private val FAULT_KEYS = listOf(
    "version",
    "catalog_id",
    "revision",
    "scenario_count",
    "framework_injection_disabled",
    "binder_service_death_disabled",
    "coverage_guided_fuzzing_disabled",
    "device_farm_corpora_disabled",
    "quality_grade",
)
private val FAULT_SAFETY_KEYS = listOf(
    "framework_injection_disabled",
    "binder_service_death_disabled",
    "coverage_guided_fuzzing_disabled",
    "device_farm_corpora_disabled",
)

private fun faultText(fault: SyntheticCompatibilityFault): String =
    fault.name.lowercase()

enum class ArKernelSyntheticFaultCatalogRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_CATALOG,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_SCENARIO_ID,
    INVALID_FAULT,
    INVALID_FAULT_ORDER,
    DUPLICATE_FAULT_PAIR,
    INVALID_SAMPLE_BUDGET,
    INVALID_SEED,
    INVALID_BOOLEAN,
    INVALID_ORDER,
    INVALID_SCENARIO_COUNT,
    UNSAFE_INVARIANT,
}

/** Fail-closed parse result; on rejection no partially trusted catalog is returned. */
data class ArKernelSyntheticFaultCatalogParseResult(
    val catalog: ArKernelSyntheticFaultCatalog?,
    val rejection: ArKernelSyntheticFaultCatalogRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelSyntheticFaultCatalogRejection.NONE &&
            catalog != null
}

/**
 * One bounded host-declared synthetic two-fault replay scenario. Fault names
 * are exact `SyntheticCompatibilityFault` enum entries in canonical ordinal
 * order; the scenario ID is derived canonically from that pair, while sample
 * budgets, seed and dry-run flag are bounded structural metadata.
 * No real Camera2/IMU stream, Binder death, framework injection, physical
 * device evidence or promoted quality is carried here.
 */
data class ArKernelSyntheticFaultScenario(
    val index: Int,
    val scenarioId: String,
    val faultA: SyntheticCompatibilityFault,
    val faultB: SyntheticCompatibilityFault,
    val cameraSamples: Int,
    val imuSamplesPerSensor: Int,
    val seed: Long,
    val dryRun: Boolean,
) {
    init {
        require(index >= 0) { "Invalid synthetic fault scenario index" }
        require(FAULT_TOKEN.matches(scenarioId)) {
            "Invalid synthetic fault scenario ID"
        }
        require(faultA.ordinal < faultB.ordinal) {
            "Synthetic fault pair is not in canonical order"
        }
        require(scenarioId ==
            "pair-${faultText(faultA)}-${faultText(faultB)}") {
            "Synthetic fault scenario ID is not canonical"
        }
        require(cameraSamples in
            FAULT_MIN_CAMERA_SAMPLES..FAULT_MAX_CAMERA_SAMPLES) {
            "Invalid synthetic fault camera sample budget"
        }
        require(imuSamplesPerSensor in
            FAULT_MIN_IMU_SAMPLES..FAULT_MAX_IMU_SAMPLES) {
            "Invalid synthetic fault IMU sample budget"
        }
        require(seed >= 0L) { "Invalid synthetic fault seed" }
    }
}

/**
 * Bounded canonical catalog for host-declared synthetic fault replay. All four
 * safety invariants must stay disabled: framework injection, Binder service
 * death, coverage-guided fuzzing and device-farm corpora. The grade stays
 * `unverified`.
 */
data class ArKernelSyntheticFaultCatalog(
    val catalogId: String,
    val revision: Int = 1,
    val frameworkInjectionDisabled: Boolean = true,
    val binderServiceDeathDisabled: Boolean = true,
    val coverageGuidedFuzzingDisabled: Boolean = true,
    val deviceFarmCorporaDisabled: Boolean = true,
    val scenarios: List<ArKernelSyntheticFaultScenario>,
    val qualityGrade: String = FAULT_QUALITY,
) {
    init {
        require(FAULT_TOKEN.matches(catalogId)) {
            "Invalid synthetic fault catalog ID"
        }
        require(revision in 1..FAULT_MAX_REVISION) {
            "Invalid synthetic fault catalog revision"
        }
        require(frameworkInjectionDisabled &&
            binderServiceDeathDisabled &&
            coverageGuidedFuzzingDisabled &&
            deviceFarmCorporaDisabled) {
            "Synthetic fault catalog must remain fail-closed"
        }
        require(qualityGrade == FAULT_QUALITY) {
            "Synthetic fault catalog cannot assert assessed quality"
        }
        require(scenarios.size in
            FAULT_MIN_SCENARIOS..FAULT_MAX_SCENARIOS) {
            "Invalid synthetic fault catalog scenario count"
        }
        require(scenarios.map { it.scenarioId }.toSet().size ==
            scenarios.size) {
            "Synthetic fault catalog contains duplicate scenario IDs"
        }
        require(scenarios.withIndex().all {
            it.value.index == it.index
        }) {
            "Synthetic fault scenarios are not contiguous"
        }
        require(scenarios.map {
            it.faultA.name to it.faultB.name
        }.toSet().size == scenarios.size) {
            "Synthetic fault catalog contains duplicate fault pairs"
        }
    }
}

/** Bounded structural facts only; never a quality or accuracy grade. */
data class ArKernelSyntheticFaultCatalogReport(
    val scenarioCount: Int,
    val distinctFaults: Int,
    val distinctFaultPairs: Int,
    val minCameraSamples: Int,
    val maxCameraSamples: Int,
    val minImuSamplesPerSensor: Int,
    val maxImuSamplesPerSensor: Int,
    val dryRunCount: Int,
    val maxSeed: Long,
    val pairwiseComplete: Boolean,
    val qualityGrade: String = FAULT_QUALITY,
)

/**
 * Canonical `ARKFAULT1` text adapter for a bounded synthetic fault catalog.
 *
 * It enforces exact metadata order, strict input/line/scenario budgets,
 * unique canonical scenario IDs, contiguous indices, exact uppercase
 * `SyntheticCompatibilityFault` pairs in ordinal order, bounded replay sample
 * budgets, a non-negative Long seed, an exact `0`/`1` dry-run flag, four
 * fail-closed safety invariants, BOM/CRLF tolerance with one optional final
 * separator and a fail-closed `END`. `fromPairwiseCatalog` produces the exact
 * 45-pair catalog in the same deterministic order as
 * `SyntheticCompatibilityScenarioCatalog.pairwiseFaults()`. The pure
 * summarizer reports only structural counts and maxima. The adapter performs
 * no I/O, replay execution, framework/HAL/Binder injection, coverage-guided
 * or unbounded fuzzing, device-farm corpus loading, physical validation or
 * quality upgrade; real service-death injection and device-farm evidence
 * remain host work and the grade stays `unverified`.
 */
object ArKernelSyntheticFaultCatalogBundle {
    fun encode(catalog: ArKernelSyntheticFaultCatalog): String {
        val lines = ArrayList<String>(
            FAULT_KEYS.size + catalog.scenarios.size + 2)
        lines.add(FAULT_HEADER)
        lines.add("version=$FAULT_VERSION")
        lines.add("catalog_id=${catalog.catalogId}")
        lines.add("revision=${catalog.revision}")
        lines.add("scenario_count=${catalog.scenarios.size}")
        FAULT_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${catalog.qualityGrade}")
        for (scenario in catalog.scenarios) {
            val dryRun = if (scenario.dryRun) "1" else "0"
            lines.add(
                "scenario=${scenario.index}|" +
                    "${scenario.scenarioId}|" +
                    "${scenario.faultA.name}|" +
                    "${scenario.faultB.name}|" +
                    "${scenario.cameraSamples}|" +
                    "${scenario.imuSamplesPerSensor}|" +
                    "${scenario.seed}|$dryRun")
        }
        lines.add(FAULT_END)
        val text = lines.joinToString("\n")
        require(text.length <= FAULT_MAX_INPUT_CHARACTERS) {
            "Synthetic fault catalog exceeds its bounded size"
        }
        require(lines.all { it.length <= FAULT_MAX_LINE_CHARACTERS }) {
            "Synthetic fault catalog line exceeds its bounded length"
        }
        return text
    }

    fun fromPairwiseCatalog(
        catalogId: String = "synthetic.pairwise.v1",
        revision: Int = 1,
        seed: Long = 1L,
        cameraSamples: Int = 60,
        imuSamplesPerSensor: Int = 200,
        dryRun: Boolean = true,
    ): ArKernelSyntheticFaultCatalog {
        val faults = SyntheticCompatibilityFault.entries
        val scenarios = buildList<ArKernelSyntheticFaultScenario> {
            for (first in faults.indices) {
                for (second in first + 1 until faults.size) {
                    val a = faults[first]
                    val b = faults[second]
                    add(
                        ArKernelSyntheticFaultScenario(
                            size,
                            "pair-${faultText(a)}-${faultText(b)}",
                            a,
                            b,
                            cameraSamples,
                            imuSamplesPerSensor,
                            seed,
                            dryRun,
                        ))
                }
            }
        }
        return ArKernelSyntheticFaultCatalog(
            catalogId,
            revision,
            true,
            true,
            true,
            true,
            scenarios,
        )
    }

    fun parse(text: String): ArKernelSyntheticFaultCatalogParseResult {
        if (text.length > FAULT_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INPUT_TOO_LARGE,
                0,
                "Synthetic fault catalog input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.EMPTY_CATALOG,
                0,
                "Synthetic fault catalog input is empty")
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
                ArKernelSyntheticFaultCatalogRejection.EMPTY_CATALOG,
                0,
                "Synthetic fault catalog input contains no catalog")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.EMPTY_LINE,
                0,
                "Synthetic fault catalog has an extra final separator")
        }
        // Explicit separator normalization keeps line counting independent of
        // stdlib lineSequence trailing-empty-line behavior.
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != FAULT_HEADER ||
            header.any { it.isWhitespace() } ||
            header.length > FAULT_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_HEADER,
                1,
                "Synthetic fault catalog header is invalid")
        }
        if (lines.size < FAULT_KEYS.size + 3) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA,
                0,
                "Synthetic fault catalog is too short")
        }
        val lineByKey = FAULT_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(FAULT_KEYS.size)
        for (index in FAULT_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Synthetic fault metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != FAULT_KEYS[index]) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Synthetic fault metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != FAULT_VERSION) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported synthetic fault catalog version")
        }
        if (values.getValue("quality_grade") != FAULT_QUALITY) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Synthetic fault catalog cannot assert assessed quality")
        }
        for (key in FAULT_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Synthetic fault safety invariant must be true")
            }
        }
        val catalogId = values.getValue("catalog_id")
        if (!FAULT_TOKEN.matches(catalogId)) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_TOKEN,
                lineByKey.getValue("catalog_id"),
                "Invalid synthetic fault catalog ID")
        }
        val revision = unsignedInt(values.getValue("revision"))
            ?: return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Invalid synthetic fault catalog revision")
        if (revision !in 1..FAULT_MAX_REVISION) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Synthetic fault catalog revision is out of bounds")
        }
        val scenarioCount =
            unsignedInt(values.getValue("scenario_count"))
                ?: return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                    lineByKey.getValue("scenario_count"),
                    "Invalid synthetic fault catalog scenario count")
        if (scenarioCount !in
            FAULT_MIN_SCENARIOS..FAULT_MAX_SCENARIOS) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection
                    .INVALID_SCENARIO_COUNT,
                lineByKey.getValue("scenario_count"),
                "Synthetic fault scenario count is out of bounds")
        }
        val entryStartIndex = FAULT_KEYS.size + 1
        val endIndex = entryStartIndex + scenarioCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA,
                0,
                "Synthetic fault line count does not match scenario count")
        }
        val scenarios =
            ArrayList<ArKernelSyntheticFaultScenario>(scenarioCount)
        val seenIds = HashSet<String>(scenarioCount)
        for (index in 0 until scenarioCount) {
            val lineNumber = entryStartIndex + index + 1
            val lineText = lines[entryStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("scenario=")) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Synthetic fault line is not a scenario")
            }
            val fields = lineText.substringAfter("scenario=")
                .split('|')
            if (fields.size != 8) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Synthetic fault scenario must have eight fields")
            }
            val scenarioIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid synthetic fault scenario index")
            if (scenarioIndex != index) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_ORDER,
                    lineNumber,
                    "Synthetic fault scenarios are not contiguous")
            }
            val scenarioId = fields[1]
            if (!FAULT_TOKEN.matches(scenarioId)) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid synthetic fault scenario ID")
            }
            val faultA = faultFromText(fields[2])
                ?: return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_FAULT,
                    lineNumber,
                    "Invalid synthetic fault A")
            val faultB = faultFromText(fields[3])
                ?: return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_FAULT,
                    lineNumber,
                    "Invalid synthetic fault B")
            if (faultA.ordinal >= faultB.ordinal) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection
                        .INVALID_FAULT_ORDER,
                    lineNumber,
                    "Synthetic fault pair is not in canonical order")
            }
            val expectedId =
                "pair-${faultText(faultA)}-${faultText(faultB)}"
            if (scenarioId != expectedId) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection
                        .INVALID_SCENARIO_ID,
                    lineNumber,
                    "Synthetic fault scenario ID is not canonical")
            }
            if (!seenIds.add(scenarioId)) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection
                        .DUPLICATE_FAULT_PAIR,
                    lineNumber,
                    "Synthetic fault pair is reused")
            }
            val cameraSamples = unsignedInt(fields[4])
                ?: return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid synthetic fault camera sample budget")
            val imuSamplesPerSensor = unsignedInt(fields[5])
                ?: return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid synthetic fault IMU sample budget")
            if (cameraSamples !in
                FAULT_MIN_CAMERA_SAMPLES..FAULT_MAX_CAMERA_SAMPLES ||
                imuSamplesPerSensor !in
                    FAULT_MIN_IMU_SAMPLES..FAULT_MAX_IMU_SAMPLES) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection
                        .INVALID_SAMPLE_BUDGET,
                    lineNumber,
                    "Synthetic fault sample budget is out of bounds")
            }
            val seed = unsignedLong(fields[6])
                ?: return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_SEED,
                    lineNumber,
                    "Invalid synthetic fault seed")
            val dryRun = when (fields[7]) {
                "0" -> false
                "1" -> true
                else -> return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_BOOLEAN,
                    lineNumber,
                    "Invalid synthetic fault dry-run flag")
            }
            scenarios += try {
                ArKernelSyntheticFaultScenario(
                    scenarioIndex,
                    scenarioId,
                    faultA,
                    faultB,
                    cameraSamples,
                    imuSamplesPerSensor,
                    seed,
                    dryRun,
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid synthetic fault scenario")
            }
        }
        val endText = lines[endIndex].trimEnd('\r')
        val endLineNumber = endIndex + 1
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != FAULT_END) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA,
                endLineNumber,
                "Synthetic fault catalog must end with END")
        }
        val catalog = try {
            ArKernelSyntheticFaultCatalog(
                catalogId,
                revision,
                true,
                true,
                true,
                true,
                scenarios,
                FAULT_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_VALUE,
                0,
                error.message ?: "Invalid synthetic fault catalog")
        }
        return ArKernelSyntheticFaultCatalogParseResult(
            catalog,
            ArKernelSyntheticFaultCatalogRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(catalog: ArKernelSyntheticFaultCatalog):
        ArKernelSyntheticFaultCatalogReport {
        val distinctFaults = catalog.scenarios
            .flatMap { listOf(it.faultA, it.faultB) }
            .toSet().size
        val distinctFaultPairs = catalog.scenarios.map {
            it.faultA.name to it.faultB.name
        }.toSet().size
        return ArKernelSyntheticFaultCatalogReport(
            scenarioCount = catalog.scenarios.size,
            distinctFaults = distinctFaults,
            distinctFaultPairs = distinctFaultPairs,
            minCameraSamples = catalog.scenarios.minOf {
                it.cameraSamples
            },
            maxCameraSamples = catalog.scenarios.maxOf {
                it.cameraSamples
            },
            minImuSamplesPerSensor = catalog.scenarios.minOf {
                it.imuSamplesPerSensor
            },
            maxImuSamplesPerSensor = catalog.scenarios.maxOf {
                it.imuSamplesPerSensor
            },
            dryRunCount = catalog.scenarios.count { it.dryRun },
            maxSeed = catalog.scenarios.maxOf { it.seed },
            pairwiseComplete =
                catalog.scenarios.size == FAULT_MAX_SCENARIOS &&
                distinctFaultPairs == FAULT_MAX_SCENARIOS,
        )
    }

    private fun faultFromText(
        value: String,
    ): SyntheticCompatibilityFault? =
        SyntheticCompatibilityFault.entries.firstOrNull {
            it.name == value
        }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelSyntheticFaultCatalogParseResult? {
        if (lineText.length > FAULT_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.LINE_TOO_LONG,
                lineNumber,
                "Synthetic fault catalog line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.EMPTY_LINE,
                lineNumber,
                "Synthetic fault catalog contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelSyntheticFaultCatalogRejection.INVALID_SCHEMA,
                lineNumber,
                "Synthetic fault lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (FAULT_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun unsignedLong(value: String): Long? =
        if (FAULT_UNSIGNED.matches(value)) value.toLongOrNull() else null

    private fun reject(
        rejection: ArKernelSyntheticFaultCatalogRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelSyntheticFaultCatalogParseResult =
        ArKernelSyntheticFaultCatalogParseResult(
            null, rejection, lineNumber, detail)
}
