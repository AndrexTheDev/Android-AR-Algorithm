package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelSurveyMeasurementJsonlTest {
    @Test
    fun roundTripsCanonicalJsonLinesAndAcceptsBomCrLfAndTrailingNewline() {
        val observations = sampleObservations()
        val jsonl = ArKernelSurveyMeasurementJsonl.encode(observations)
        val parsed = ArKernelSurveyMeasurementJsonl.parse(jsonl)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelSurveyJsonlRejection.NONE, parsed.rejection)
        assertEquals(3, parsed.lineNumber)
        assertEquals(observations, parsed.observations)
        assertEquals("scale-01", parsed.observations[0].evidenceId)
        assertEquals(DimensionalReferenceKind.HELD_OUT_SCALE_BAR,
            parsed.observations[0].referenceKind)
        assertEquals(1.0, parsed.observations[1].referenceDistanceMeters, 0.0)
        assertEquals(DimensionalReferenceKind.SENSOR_GROUND_TRUTH,
            parsed.observations[2].referenceKind)
        assertFalse(parsed.observations[2].heldOutFromCalibrationAndOptimization)
        assertEquals(jsonl, ArKernelSurveyMeasurementJsonl.encode(parsed.observations))

        assertTrue(ArKernelSurveyMeasurementJsonl.parse("\uFEFF$jsonl").accepted)
        assertTrue(ArKernelSurveyMeasurementJsonl.parse(
            jsonl.replace("\n", "\r\n")).accepted)
        assertTrue(ArKernelSurveyMeasurementJsonl.parse("$jsonl\n").accepted)
    }

    @Test
    fun rejectsMalformedSchemaAndBudgets() {
        val jsonl = ArKernelSurveyMeasurementJsonl.encode(sampleObservations())
        assertEquals(ArKernelSurveyJsonlRejection.EMPTY_DATASET,
            ArKernelSurveyMeasurementJsonl.parse("").rejection)
        assertEquals(ArKernelSurveyJsonlRejection.EMPTY_DATASET,
            ArKernelSurveyMeasurementJsonl.parse("\uFEFF").rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INPUT_TOO_LARGE,
            ArKernelSurveyMeasurementJsonl.parse("x".repeat(65_537)).rejection)

        val tooLong = jsonl.lines().toMutableList().also {
            it[0] = "x".repeat(1_025)
        }
        assertEquals(ArKernelSurveyJsonlRejection.LINE_TOO_LONG,
            ArKernelSurveyMeasurementJsonl.parse(tooLong.joinToString("\n")).rejection)

        val blank = jsonl.lines().toMutableList().also { it.add(1, "") }
        assertEquals(ArKernelSurveyJsonlRejection.EMPTY_LINE,
            ArKernelSurveyMeasurementJsonl.parse(blank.joinToString("\n")).rejection)

        val extra = jsonl.lines().toMutableList().also {
            it[0] = it[0].dropLast(1) + ""","extra":1}"""
        }
        val extraResult = ArKernelSurveyMeasurementJsonl.parse(extra.joinToString("\n"))
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_SCHEMA, extraResult.rejection)
        assertEquals(1, extraResult.lineNumber)

        assertEquals(ArKernelSurveyJsonlRejection.INVALID_SCHEMA,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceKey(jsonl, 0, "evidence_id", "id")).rejection)

        val whitespace = jsonl.lines().toMutableList().also {
            it[0] = " ${it[0]}"
        }
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_JSON,
            ArKernelSurveyMeasurementJsonl.parse(whitespace.joinToString("\n")).rejection)

        val budget = ArKernelSurveyMeasurementJsonl.parse(jsonl, maxRows = 1)
        assertEquals(ArKernelSurveyJsonlRejection.ROW_COUNT_EXCEEDED, budget.rejection)
        assertTrue(budget.observations.isEmpty())
        assertFalse(budget.accepted)
    }

    @Test
    fun rejectsInvalidEvidenceFields() {
        val jsonl = ArKernelSurveyMeasurementJsonl.encode(sampleObservations())
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_EVIDENCE_ID,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "evidence_id", "\"bad/id\"")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.DUPLICATE_EVIDENCE_ID,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 1, "evidence_id", "\"scale-01\"")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_REFERENCE_KIND,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "reference_kind", "\"UNKNOWN\"")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "reference_distance_m", "NaN")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "ax_m", "not-a-number")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "ay_m", "1.")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_BOOLEAN,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "held_out", "yes")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_JSON,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "evidence_id", "scale-01")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "reference_distance_m", "0.0")).rejection)
        assertEquals(ArKernelSurveyJsonlRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementJsonl.parse(
                replaceValue(jsonl, 0, "reference_uncertainty_m", "2.0")).rejection)
    }

    @Test
    fun rejectsInvalidConfigurationsAndDuplicateEncoding() {
        val observations = sampleObservations()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementJsonl.parse("", maxRows = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementJsonl.parse("", maxRows = 65)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementJsonl.encode(emptyList())
        }
        val duplicate = listOf(observations[0], observations[0])
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementJsonl.encode(duplicate)
        }
        val tooMany = List(65) {
            observation("id-$it", 0.5,
                DimensionalReferenceKind.HELD_OUT_SCALE_BAR, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementJsonl.encode(tooMany)
        }
    }

    private fun replaceValue(
        jsonl: String,
        row: Int,
        key: String,
        value: String,
    ): String {
        val lines = jsonl.lines().toMutableList()
        val body = lines[row].drop(1).dropLast(1)
        val segments = body.split(',').toMutableList()
        val index = segments.indexOfFirst { it.startsWith("\"$key\":") }
        require(index >= 0) { "Unknown JSONL key" }
        segments[index] = "\"$key\":$value"
        lines[row] = "{" + segments.joinToString(",") + "}"
        return lines.joinToString("\n")
    }

    private fun replaceKey(
        jsonl: String,
        row: Int,
        oldKey: String,
        newKey: String,
    ): String {
        val lines = jsonl.lines().toMutableList()
        val body = lines[row].drop(1).dropLast(1)
        val segments = body.split(',').toMutableList()
        val index = segments.indexOfFirst { it.startsWith("\"$oldKey\":") }
        require(index >= 0) { "Unknown JSONL key" }
        segments[index] = "\"$newKey\":" + segments[index].substringAfter(':')
        lines[row] = "{" + segments.joinToString(",") + "}"
        return lines.joinToString("\n")
    }

    private fun sampleObservations(): List<HeldOutDimensionObservation> = listOf(
        observation("scale-01", 0.5,
            DimensionalReferenceKind.HELD_OUT_SCALE_BAR, true),
        observation("survey-02", 1.0,
            DimensionalReferenceKind.SURVEYED_DISTANCE, true),
        observation("sensor-03", 1.5,
            DimensionalReferenceKind.SENSOR_GROUND_TRUTH, false),
    )

    private fun observation(
        id: String,
        distance: Double,
        kind: DimensionalReferenceKind,
        heldOut: Boolean,
    ): HeldOutDimensionObservation = HeldOutDimensionObservation(
        evidenceId = id,
        reconstructedPointA = Vector3(0.0, 0.0, 0.0),
        reconstructedPointB = Vector3(distance, 0.0, 0.0),
        referenceDistanceMeters = distance,
        referenceUncertaintyMeters = 0.001,
        referenceKind = kind,
        heldOutFromCalibrationAndOptimization = heldOut,
    )
}
