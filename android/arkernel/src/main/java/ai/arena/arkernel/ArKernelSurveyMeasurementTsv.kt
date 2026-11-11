package ai.arena.arkernel

private const val SURVEY_TSV_MAGIC = "ARKSURVEYTSV1"
private const val SURVEY_TSV_HEADER =
    "evidence_id\treference_kind\treference_distance_m\t" +
        "reference_uncertainty_m\tax_m\tay_m\taz_m\t" +
        "bx_m\tby_m\tbz_m\theld_out"
private const val SURVEY_TSV_COLUMNS = 11
private const val SURVEY_TSV_MAX_INPUT_CHARACTERS = 65_536
private const val SURVEY_TSV_MAX_LINE_CHARACTERS = 1_024
private val SURVEY_TSV_EVIDENCE_ID = Regex("[A-Za-z0-9._:-]{1,128}")

enum class ArKernelSurveyTsvRejection {
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
 * Fail-closed result for a bounded canonical survey TSV. On rejection,
 * [observations] is always empty; no partially trusted evidence is returned.
 * Parsing is only a transport adapter and never grants a quality grade.
 */
data class ArKernelSurveyTsvParseResult(
    val observations: List<HeldOutDimensionObservation>,
    val rejection: ArKernelSurveyTsvRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelSurveyTsvRejection.NONE &&
            observations.isNotEmpty()
}

/**
 * Bounded canonical TSV adapter for held-out dimensional survey evidence.
 *
 * The format has one exact `ARKSURVEYTSV1` marker line, one exact tab-separated
 * column header and plain tab-separated data rows: evidence ID, reference
 * provenance, reference distance/uncertainty in metres, two reconstructed 3D
 * points in metres and an explicit held-out flag. Quoted fields, embedded tabs,
 * comments and blank rows are intentionally not accepted. The parser enforces
 * input, line and row budgets, unique bounded IDs, exact reference kinds,
 * finite numbers and the existing dimensional evidence contract. It does not
 * measure anything, run the validator, upgrade `unverified` output or imply
 * `centimeter-validated` accuracy.
 */
object ArKernelSurveyMeasurementTsv {
    fun parse(
        text: String,
        maxRows: Int = 64,
    ): ArKernelSurveyTsvParseResult {
        require(maxRows in 1..64) {
            "Survey TSV row budget must be in 1..64"
        }
        if (text.length > SURVEY_TSV_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelSurveyTsvRejection.INPUT_TOO_LARGE, 0,
                "Survey TSV input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelSurveyTsvRejection.EMPTY_DATASET, 0,
                "Survey TSV input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelSurveyTsvRejection.EMPTY_DATASET, 0,
                "Survey TSV input contains no plan")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelSurveyTsvRejection.EMPTY_LINE, 0,
                "Survey TSV contains an extra final separator")
        }
        val lines = body.lineSequence().toList()
        if (lines.size < 2) {
            return reject(
                ArKernelSurveyTsvRejection.INVALID_HEADER, 1,
                "Survey TSV marker or column header is missing")
        }
        val magic = lines[0].trimEnd('\r')
        val header = lines[1].trimEnd('\r')
        if (magic.length > SURVEY_TSV_MAX_LINE_CHARACTERS ||
            header.length > SURVEY_TSV_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelSurveyTsvRejection.LINE_TOO_LONG, 1,
                "Survey TSV header exceeds its bounded length")
        }
        if (magic != SURVEY_TSV_MAGIC || header != SURVEY_TSV_HEADER) {
            return reject(
                ArKernelSurveyTsvRejection.INVALID_HEADER, 1,
                "Survey TSV marker or header does not match the canonical schema")
        }
        if (lines.size == 2) {
            return reject(
                ArKernelSurveyTsvRejection.EMPTY_DATASET, 2,
                "Survey TSV contains no data rows")
        }
        val observations = mutableListOf<HeldOutDimensionObservation>()
        val ids = HashSet<String>(maxRows)
        var lineNumber = 2
        for (index in 2 until lines.size) {
            ++lineNumber
            val line = lines[index].trimEnd('\r')
            if (line.length > SURVEY_TSV_MAX_LINE_CHARACTERS) {
                return reject(
                    ArKernelSurveyTsvRejection.LINE_TOO_LONG, lineNumber,
                    "Survey TSV row exceeds its bounded length")
            }
            if (line.isBlank()) {
                return reject(
                    ArKernelSurveyTsvRejection.EMPTY_LINE, lineNumber,
                    "Survey TSV contains a blank row")
            }
            if (observations.size >= maxRows) {
                return reject(
                    ArKernelSurveyTsvRejection.ROW_COUNT_EXCEEDED,
                    lineNumber,
                    "Survey TSV exceeds its bounded row count")
            }
            val fields = splitLine(line).map { it.trim() }
            if (fields.size != SURVEY_TSV_COLUMNS) {
                return reject(
                    ArKernelSurveyTsvRejection.COLUMN_COUNT, lineNumber,
                    "Survey TSV row does not have exactly 11 columns")
            }
            if (fields.any { it.isEmpty() }) {
                return reject(
                    ArKernelSurveyTsvRejection.EMPTY_FIELD, lineNumber,
                    "Survey TSV row contains an empty field")
            }
            val evidenceId = fields[0]
            if (!SURVEY_TSV_EVIDENCE_ID.matches(evidenceId)) {
                return reject(
                    ArKernelSurveyTsvRejection.INVALID_EVIDENCE_ID,
                    lineNumber,
                    "Survey TSV evidence ID is invalid")
            }
            if (!ids.add(evidenceId)) {
                return reject(
                    ArKernelSurveyTsvRejection.DUPLICATE_EVIDENCE_ID,
                    lineNumber,
                    "Survey TSV evidence ID is reused")
            }
            val referenceKind = DimensionalReferenceKind.entries.firstOrNull {
                it.name == fields[1]
            } ?: return reject(
                ArKernelSurveyTsvRejection.INVALID_REFERENCE_KIND,
                lineNumber,
                "Survey TSV reference kind is unknown")
            val numbers = DoubleArray(8)
            for (column in 2..9) {
                val value = fields[column].toDoubleOrNull()
                if (value == null || !value.isFinite()) {
                    return reject(
                        ArKernelSurveyTsvRejection.INVALID_NUMBER,
                        lineNumber,
                        "Survey TSV column ${column + 1} is not a finite number")
                }
                numbers[column - 2] = value
            }
            val heldOut = when (fields[10]) {
                "true" -> true
                "false" -> false
                else -> return reject(
                    ArKernelSurveyTsvRejection.INVALID_BOOLEAN,
                    lineNumber,
                    "Survey TSV held-out flag must be true or false")
            }
            val observation = try {
                HeldOutDimensionObservation(
                    evidenceId = evidenceId,
                    reconstructedPointA = Vector3(
                        numbers[2], numbers[3], numbers[4]),
                    reconstructedPointB = Vector3(
                        numbers[5], numbers[6], numbers[7]),
                    referenceDistanceMeters = numbers[0],
                    referenceUncertaintyMeters = numbers[1],
                    referenceKind = referenceKind,
                    heldOutFromCalibrationAndOptimization = heldOut,
                )
            } catch (_: IllegalArgumentException) {
                return reject(
                    ArKernelSurveyTsvRejection.INVALID_OBSERVATION,
                    lineNumber,
                    "Survey TSV row violates the dimensional evidence contract")
            }
            observations.add(observation)
        }
        return ArKernelSurveyTsvParseResult(
            observations,
            ArKernelSurveyTsvRejection.NONE,
            lineNumber,
            "Survey TSV parsed within its bounded contract",
        )
    }

    /** Deterministic canonical encoding for an already validated evidence list. */
    fun encode(observations: List<HeldOutDimensionObservation>): String {
        require(observations.size in 1..64) {
            "Survey TSV evidence count must be in 1..64"
        }
        val ids = HashSet<String>(observations.size)
        observations.forEach { observation ->
            require(ids.add(observation.evidenceId)) {
                "Survey TSV evidence IDs must be unique"
            }
        }
        val lines = ArrayList<String>(observations.size + 2)
        lines.add(SURVEY_TSV_MAGIC)
        lines.add(SURVEY_TSV_HEADER)
        observations.forEach { lines.add(row(it)) }
        val text = lines.joinToString("\n")
        require(text.length <= SURVEY_TSV_MAX_INPUT_CHARACTERS) {
            "Survey TSV exceeds its bounded size"
        }
        require(lines.all { it.length <= SURVEY_TSV_MAX_LINE_CHARACTERS }) {
            "Survey TSV line exceeds its bounded length"
        }
        return text
    }

    private fun row(observation: HeldOutDimensionObservation): String =
        listOf(
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
            if (observation.heldOutFromCalibrationAndOptimization) {
                "true"
            } else {
                "false"
            },
        ).joinToString("\t")

    private fun splitLine(line: String): List<String> {
        val fields = ArrayList<String>(SURVEY_TSV_COLUMNS)
        val current = StringBuilder()
        for (character in line) {
            if (character == '\t') {
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
        rejection: ArKernelSurveyTsvRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelSurveyTsvParseResult =
        ArKernelSurveyTsvParseResult(emptyList(), rejection, lineNumber, detail)
}
