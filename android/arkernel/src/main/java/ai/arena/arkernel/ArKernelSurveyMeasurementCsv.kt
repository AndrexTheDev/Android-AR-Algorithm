package ai.arena.arkernel

private const val SURVEY_CSV_HEADER =
    "evidence_id,reference_kind,reference_distance_m,reference_uncertainty_m," +
        "ax_m,ay_m,az_m,bx_m,by_m,bz_m,held_out"
private const val SURVEY_CSV_COLUMNS = 11
private const val SURVEY_CSV_MAX_INPUT_CHARACTERS = 65_536
private const val SURVEY_CSV_MAX_LINE_CHARACTERS = 1_024
private val SURVEY_CSV_EVIDENCE_ID = Regex("[A-Za-z0-9._:-]{1,128}")

enum class ArKernelSurveyCsvRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_DATASET,
    INVALID_HEADER,
    LINE_TOO_LONG,
    EMPTY_LINE,
    COLUMN_COUNT,
    EMPTY_FIELD,
    INVALID_EVIDENCE_ID,
    DUPLICATE_EVIDENCE_ID,
    INVALID_REFERENCE_KIND,
    INVALID_NUMBER,
    INVALID_BOOLEAN,
    INVALID_OBSERVATION,
    ROW_COUNT_EXCEEDED,
}

/**
 * Fail-closed result for a bounded canonical survey CSV. On rejection,
 * [observations] is always empty; no partially trusted evidence is returned.
 * Parsing is only a transport adapter and never grants a quality grade.
 */
data class ArKernelSurveyCsvParseResult(
    val observations: List<HeldOutDimensionObservation>,
    val rejection: ArKernelSurveyCsvRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelSurveyCsvRejection.NONE && observations.isNotEmpty()
}

/**
 * Bounded canonical CSV adapter for held-out dimensional survey evidence.
 *
 * The format has one exact header and plain comma-separated fields: evidence
 * ID, reference provenance, reference distance/uncertainty in metres, two
 * reconstructed 3D points in metres, and an explicit held-out flag. Quoted
 * fields, embedded commas, comments and blank rows are intentionally not
 * accepted. The parser enforces input, line and row budgets, unique bounded
 * IDs, exact reference kinds, finite numbers and the existing dimensional
 * evidence contract. It does not measure anything, run the validator, upgrade
 * `unverified` output or imply `centimeter-validated` accuracy.
 */
object ArKernelSurveyMeasurementCsv {
    fun parse(
        text: String,
        maxRows: Int = 64,
    ): ArKernelSurveyCsvParseResult {
        require(maxRows in 1..64) { "Survey CSV row budget must be in 1..64" }
        if (text.length > SURVEY_CSV_MAX_INPUT_CHARACTERS) {
            return reject(ArKernelSurveyCsvRejection.INPUT_TOO_LARGE, 0,
                "Survey CSV input exceeds its bounded size")
        }
        val normalized = if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(ArKernelSurveyCsvRejection.EMPTY_DATASET, 0,
                "Survey CSV input is empty")
        }
        val iterator = normalized.lineSequence().iterator()
        if (!iterator.hasNext()) {
            return reject(ArKernelSurveyCsvRejection.EMPTY_DATASET, 0,
                "Survey CSV input has no header")
        }
        val header = iterator.next().trimEnd('\r')
        if (header.length > SURVEY_CSV_MAX_LINE_CHARACTERS) {
            return reject(ArKernelSurveyCsvRejection.LINE_TOO_LONG, 1,
                "Survey CSV header exceeds its bounded length")
        }
        if (header != SURVEY_CSV_HEADER) {
            return reject(ArKernelSurveyCsvRejection.INVALID_HEADER, 1,
                "Survey CSV header does not match the canonical schema")
        }

        val observations = mutableListOf<HeldOutDimensionObservation>()
        val ids = HashSet<String>(maxRows)
        var lineNumber = 1
        while (iterator.hasNext()) {
            ++lineNumber
            val line = iterator.next().trimEnd('\r')
            if (line.length > SURVEY_CSV_MAX_LINE_CHARACTERS) {
                return reject(ArKernelSurveyCsvRejection.LINE_TOO_LONG, lineNumber,
                    "Survey CSV row exceeds its bounded length")
            }
            if (line.isBlank()) {
                return reject(ArKernelSurveyCsvRejection.EMPTY_LINE, lineNumber,
                    "Survey CSV contains a blank row")
            }
            if (observations.size >= maxRows) {
                return reject(ArKernelSurveyCsvRejection.ROW_COUNT_EXCEEDED, lineNumber,
                    "Survey CSV exceeds its bounded row count")
            }
            val fields = splitLine(line).map { it.trim() }
            if (fields.size != SURVEY_CSV_COLUMNS) {
                return reject(ArKernelSurveyCsvRejection.COLUMN_COUNT, lineNumber,
                    "Survey CSV row does not have exactly 11 columns")
            }
            if (fields.any { it.isEmpty() }) {
                return reject(ArKernelSurveyCsvRejection.EMPTY_FIELD, lineNumber,
                    "Survey CSV row contains an empty field")
            }
            val evidenceId = fields[0]
            if (!SURVEY_CSV_EVIDENCE_ID.matches(evidenceId)) {
                return reject(ArKernelSurveyCsvRejection.INVALID_EVIDENCE_ID, lineNumber,
                    "Survey CSV evidence ID is invalid")
            }
            if (!ids.add(evidenceId)) {
                return reject(ArKernelSurveyCsvRejection.DUPLICATE_EVIDENCE_ID, lineNumber,
                    "Survey CSV evidence ID is reused")
            }
            val referenceKind = DimensionalReferenceKind.entries.firstOrNull {
                it.name == fields[1]
            } ?: return reject(ArKernelSurveyCsvRejection.INVALID_REFERENCE_KIND, lineNumber,
                "Survey CSV reference kind is unknown")

            val numbers = DoubleArray(8)
            for (column in 2..9) {
                val value = fields[column].toDoubleOrNull()
                if (value == null || !value.isFinite()) {
                    return reject(ArKernelSurveyCsvRejection.INVALID_NUMBER, lineNumber,
                        "Survey CSV column ${column + 1} is not a finite number")
                }
                numbers[column - 2] = value
            }
            val heldOut = when (fields[10]) {
                "true" -> true
                "false" -> false
                else -> return reject(ArKernelSurveyCsvRejection.INVALID_BOOLEAN, lineNumber,
                    "Survey CSV held-out flag must be true or false")
            }
            val observation = try {
                HeldOutDimensionObservation(
                    evidenceId = evidenceId,
                    reconstructedPointA = Vector3(numbers[2], numbers[3], numbers[4]),
                    reconstructedPointB = Vector3(numbers[5], numbers[6], numbers[7]),
                    referenceDistanceMeters = numbers[0],
                    referenceUncertaintyMeters = numbers[1],
                    referenceKind = referenceKind,
                    heldOutFromCalibrationAndOptimization = heldOut,
                )
            } catch (_: IllegalArgumentException) {
                return reject(ArKernelSurveyCsvRejection.INVALID_OBSERVATION, lineNumber,
                    "Survey CSV row violates the dimensional evidence contract")
            }
            observations.add(observation)
        }
        if (observations.isEmpty()) {
            return reject(ArKernelSurveyCsvRejection.EMPTY_DATASET, 1,
                "Survey CSV contains no data rows")
        }
        return ArKernelSurveyCsvParseResult(
            observations, ArKernelSurveyCsvRejection.NONE, lineNumber,
            "Survey CSV parsed within its bounded contract")
    }

    /** Deterministic canonical encoding for an already validated evidence list. */
    fun encode(observations: List<HeldOutDimensionObservation>): String {
        require(observations.size in 1..64) {
            "Survey CSV evidence count must be in 1..64"
        }
        val ids = HashSet<String>(observations.size)
        observations.forEach { observation ->
            require(ids.add(observation.evidenceId)) {
                "Survey CSV evidence IDs must be unique"
            }
        }
        return observations.joinToString("\n", prefix = "$SURVEY_CSV_HEADER\n") {
            row(it)
        }
    }

    private fun row(observation: HeldOutDimensionObservation): String = listOf(
        observation.evidenceId,
        observation.referenceKind.name,
        observation.referenceDistanceMeters.toString(),
        observation.referenceUncertaintyMeters.toString(),
        observation.reconstructedPointA.x.toString(),
        observation.reconstructedPointA.y.toString(),
        observation.reconstructedPointA.z.toString(),
        observation.reconstructedPointB.x.toString(),
        observation.reconstructedPointB.y.toString(),
        observation.reconstructedPointB.z.toString(),
        if (observation.heldOutFromCalibrationAndOptimization) "true" else "false",
    ).joinToString(",")

    private fun splitLine(line: String): List<String> {
        val fields = ArrayList<String>(SURVEY_CSV_COLUMNS)
        val current = StringBuilder()
        for (character in line) {
            if (character == ',') {
                fields.add(current.toString())
                current.setLength(0)
            } else {
                current.append(character)
            }
        }
        fields.add(current.toString())
        return fields
    }

    private fun reject(
        rejection: ArKernelSurveyCsvRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelSurveyCsvParseResult =
        ArKernelSurveyCsvParseResult(emptyList(), rejection, lineNumber, detail)
}
