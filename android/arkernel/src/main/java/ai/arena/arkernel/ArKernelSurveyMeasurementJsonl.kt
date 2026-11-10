package ai.arena.arkernel

private const val SURVEY_JSONL_MAX_INPUT_CHARACTERS = 65_536
private const val SURVEY_JSONL_MAX_LINE_CHARACTERS = 1_024
private val SURVEY_JSONL_EVIDENCE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val SURVEY_JSONL_NUMBER = Regex(
    """-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
private val SURVEY_JSONL_KEYS = listOf(
    "evidence_id",
    "reference_kind",
    "reference_distance_m",
    "reference_uncertainty_m",
    "ax_m",
    "ay_m",
    "az_m",
    "bx_m",
    "by_m",
    "bz_m",
    "held_out",
)
private val SURVEY_JSONL_NUMBER_KEYS = listOf(
    "reference_distance_m",
    "reference_uncertainty_m",
    "ax_m",
    "ay_m",
    "az_m",
    "bx_m",
    "by_m",
    "bz_m",
)

enum class ArKernelSurveyJsonlRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_DATASET,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_JSON,
    INVALID_SCHEMA,
    INVALID_EVIDENCE_ID,
    DUPLICATE_EVIDENCE_ID,
    INVALID_REFERENCE_KIND,
    INVALID_NUMBER,
    INVALID_BOOLEAN,
    INVALID_OBSERVATION,
    ROW_COUNT_EXCEEDED,
}

/**
 * Fail-closed result for a bounded canonical survey JSON Lines document. On
 * rejection, [observations] is always empty; no partially trusted evidence is
 * returned. Parsing is only a transport adapter and never grants a quality
 * grade.
 */
data class ArKernelSurveyJsonlParseResult(
    val observations: List<HeldOutDimensionObservation>,
    val rejection: ArKernelSurveyJsonlRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelSurveyJsonlRejection.NONE && observations.isNotEmpty()
}

/**
 * Bounded canonical JSON Lines adapter for held-out dimensional survey
 * evidence.
 *
 * Each line is one flat JSON object with exactly eleven keys in a fixed order,
 * no whitespace, no nested values and no escaped strings. A single optional
 * final line separator is accepted; any other blank line is rejected. Evidence
 * IDs and reference kinds use the same bounded vocabularies as the CSV adapter,
 * while numeric fields accept only finite JSON numbers. The parser enforces
 * input, line and row budgets, unique IDs, exact provenance and the existing
 * dimensional evidence contract. It does not measure anything, run the
 * validator, upgrade `unverified` output or imply `centimeter-validated`
 * accuracy.
 */
object ArKernelSurveyMeasurementJsonl {
    fun parse(
        text: String,
        maxRows: Int = 64,
    ): ArKernelSurveyJsonlParseResult {
        require(maxRows in 1..64) {
            "Survey JSON Lines row budget must be in 1..64"
        }
        if (text.length > SURVEY_JSONL_MAX_INPUT_CHARACTERS) {
            return reject(ArKernelSurveyJsonlRejection.INPUT_TOO_LARGE, 0,
                "Survey JSON Lines input exceeds its bounded size")
        }
        val normalized = if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(ArKernelSurveyJsonlRejection.EMPTY_DATASET, 0,
                "Survey JSON Lines input is empty")
        }
        // JSON Lines permits one final line separator. Remove exactly one so
        // that a terminal newline is not mistaken for an additional blank row.
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") -> normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(ArKernelSurveyJsonlRejection.EMPTY_DATASET, 0,
                "Survey JSON Lines input contains no data rows")
        }

        val observations = mutableListOf<HeldOutDimensionObservation>()
        val ids = HashSet<String>(maxRows)
        var lineNumber = 0
        for (rawLine in body.lineSequence()) {
            ++lineNumber
            val line = rawLine.trimEnd('\r')
            if (line.length > SURVEY_JSONL_MAX_LINE_CHARACTERS) {
                return reject(ArKernelSurveyJsonlRejection.LINE_TOO_LONG, lineNumber,
                    "Survey JSON Lines row exceeds its bounded length")
            }
            if (line.isBlank()) {
                return reject(ArKernelSurveyJsonlRejection.EMPTY_LINE, lineNumber,
                    "Survey JSON Lines contains a blank row")
            }
            if (observations.size >= maxRows) {
                return reject(ArKernelSurveyJsonlRejection.ROW_COUNT_EXCEEDED, lineNumber,
                    "Survey JSON Lines exceeds its bounded row count")
            }
            if (line.any { it.isWhitespace() }) {
                return reject(ArKernelSurveyJsonlRejection.INVALID_JSON, lineNumber,
                    "Survey JSON Lines rows must not contain whitespace")
            }
            if (!line.startsWith("{") || !line.endsWith("}")) {
                return reject(ArKernelSurveyJsonlRejection.INVALID_JSON, lineNumber,
                    "Survey JSON Lines row is not a flat JSON object")
            }
            val body = line.substring(1, line.length - 1)
            val segments = body.split(',')
            if (segments.size != SURVEY_JSONL_KEYS.size) {
                return reject(ArKernelSurveyJsonlRejection.INVALID_SCHEMA, lineNumber,
                    "Survey JSON Lines row does not have exactly 11 keys")
            }

            val values = HashMap<String, String>(SURVEY_JSONL_KEYS.size)
            for (index in SURVEY_JSONL_KEYS.indices) {
                val segment = segments[index]
                val colon = segment.indexOf(':')
                if (colon < 3 || segment[0] != '"' || segment[colon - 1] != '"') {
                    return reject(ArKernelSurveyJsonlRejection.INVALID_JSON, lineNumber,
                        "Survey JSON Lines row has a malformed key/value pair")
                }
                val key = segment.substring(1, colon - 1)
                if (key != SURVEY_JSONL_KEYS[index]) {
                    return reject(ArKernelSurveyJsonlRejection.INVALID_SCHEMA, lineNumber,
                        "Survey JSON Lines key order or name is wrong")
                }
                values[key] = segment.substring(colon + 1)
            }

            val evidenceId = stringValue(values.getValue("evidence_id"))
                ?: return reject(ArKernelSurveyJsonlRejection.INVALID_JSON, lineNumber,
                    "Survey JSON Lines evidence ID is not a plain JSON string")
            if (!SURVEY_JSONL_EVIDENCE_ID.matches(evidenceId)) {
                return reject(ArKernelSurveyJsonlRejection.INVALID_EVIDENCE_ID, lineNumber,
                    "Survey JSON Lines evidence ID is invalid")
            }
            if (!ids.add(evidenceId)) {
                return reject(ArKernelSurveyJsonlRejection.DUPLICATE_EVIDENCE_ID, lineNumber,
                    "Survey JSON Lines evidence ID is reused")
            }

            val referenceKindText = stringValue(values.getValue("reference_kind"))
                ?: return reject(ArKernelSurveyJsonlRejection.INVALID_JSON, lineNumber,
                    "Survey JSON Lines reference kind is not a plain JSON string")
            val referenceKind = DimensionalReferenceKind.entries.firstOrNull {
                it.name == referenceKindText
            } ?: return reject(ArKernelSurveyJsonlRejection.INVALID_REFERENCE_KIND, lineNumber,
                "Survey JSON Lines reference kind is unknown")

            val numbers = DoubleArray(8)
            for (index in SURVEY_JSONL_NUMBER_KEYS.indices) {
                val value = numberValue(values.getValue(SURVEY_JSONL_NUMBER_KEYS[index]))
                    ?: return reject(ArKernelSurveyJsonlRejection.INVALID_NUMBER, lineNumber,
                        "Survey JSON Lines ${SURVEY_JSONL_NUMBER_KEYS[index]} is not a finite JSON number")
                numbers[index] = value
            }
            val heldOut = when (values.getValue("held_out")) {
                "true" -> true
                "false" -> false
                else -> return reject(ArKernelSurveyJsonlRejection.INVALID_BOOLEAN, lineNumber,
                    "Survey JSON Lines held-out flag must be true or false")
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
                return reject(ArKernelSurveyJsonlRejection.INVALID_OBSERVATION, lineNumber,
                    "Survey JSON Lines row violates the dimensional evidence contract")
            }
            observations.add(observation)
        }
        if (observations.isEmpty()) {
            return reject(ArKernelSurveyJsonlRejection.EMPTY_DATASET, lineNumber,
                "Survey JSON Lines contains no data rows")
        }
        return ArKernelSurveyJsonlParseResult(
            observations, ArKernelSurveyJsonlRejection.NONE, lineNumber,
            "Survey JSON Lines parsed within its bounded contract")
    }

    /** Deterministic canonical encoding for an already validated evidence list. */
    fun encode(observations: List<HeldOutDimensionObservation>): String {
        require(observations.size in 1..64) {
            "Survey JSON Lines evidence count must be in 1..64"
        }
        val ids = HashSet<String>(observations.size)
        observations.forEach { observation ->
            require(ids.add(observation.evidenceId)) {
                "Survey JSON Lines evidence IDs must be unique"
            }
            require(SURVEY_JSONL_EVIDENCE_ID.matches(observation.evidenceId)) {
                "Survey JSON Lines evidence ID is invalid"
            }
        }
        return observations.joinToString("\n") { row(it) }
    }

    private fun row(observation: HeldOutDimensionObservation): String = buildString {
        append("{\"evidence_id\":\"").append(observation.evidenceId)
        append("\",\"reference_kind\":\"").append(observation.referenceKind.name)
        append("\",\"reference_distance_m\":")
            .append(observation.referenceDistanceMeters.toString())
        append(",\"reference_uncertainty_m\":")
            .append(observation.referenceUncertaintyMeters.toString())
        append(",\"ax_m\":").append(observation.reconstructedPointA.x.toString())
        append(",\"ay_m\":").append(observation.reconstructedPointA.y.toString())
        append(",\"az_m\":").append(observation.reconstructedPointA.z.toString())
        append(",\"bx_m\":").append(observation.reconstructedPointB.x.toString())
        append(",\"by_m\":").append(observation.reconstructedPointB.y.toString())
        append(",\"bz_m\":").append(observation.reconstructedPointB.z.toString())
        append(",\"held_out\":").append(
            if (observation.heldOutFromCalibrationAndOptimization) "true" else "false")
        append("}")
    }

    private fun stringValue(value: String): String? {
        if (value.length < 2 || value[0] != '"' || value[value.length - 1] != '"') return null
        val inner = value.substring(1, value.length - 1)
        if (inner.isEmpty()) return null
        if (inner.any { it == '"' || it == '\\' }) return null
        return inner
    }

    private fun numberValue(value: String): Double? {
        if (!SURVEY_JSONL_NUMBER.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        if (!parsed.isFinite()) return null
        return parsed
    }

    private fun reject(
        rejection: ArKernelSurveyJsonlRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelSurveyJsonlParseResult =
        ArKernelSurveyJsonlParseResult(emptyList(), rejection, lineNumber, detail)
}
