package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelSurveyMeasurementCsvTest {
    @Test
    fun roundTripsCanonicalCsvAndAcceptsByteOrderMark() {
        val observations = sampleObservations()
        val csv = ArKernelSurveyMeasurementCsv.encode(observations)
        val parsed = ArKernelSurveyMeasurementCsv.parse(csv)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelSurveyCsvRejection.NONE, parsed.rejection)
        assertEquals(3, parsed.observations.size)
        assertEquals("scale-01", parsed.observations[0].evidenceId)
        assertEquals(DimensionalReferenceKind.HELD_OUT_SCALE_BAR,
            parsed.observations[0].referenceKind)
        assertEquals(1.0, parsed.observations[1].referenceDistanceMeters, 0.0)
        assertEquals(DimensionalReferenceKind.SENSOR_GROUND_TRUTH,
            parsed.observations[2].referenceKind)
        assertFalse(parsed.observations[2].heldOutFromCalibrationAndOptimization)
        assertEquals(csv, ArKernelSurveyMeasurementCsv.encode(parsed.observations))

        val bomParsed = ArKernelSurveyMeasurementCsv.parse("\uFEFF$csv")
        assertTrue(bomParsed.accepted)
        assertEquals(csv, ArKernelSurveyMeasurementCsv.encode(bomParsed.observations))
    }

    @Test
    fun rejectsMalformedSchemaAndBudgets() {
        val csv = ArKernelSurveyMeasurementCsv.encode(sampleObservations())
        assertEquals(ArKernelSurveyCsvRejection.EMPTY_DATASET,
            ArKernelSurveyMeasurementCsv.parse("").rejection)
        assertEquals(ArKernelSurveyCsvRejection.EMPTY_DATASET,
            ArKernelSurveyMeasurementCsv.parse(csv.lines().first()).rejection)
        assertEquals(ArKernelSurveyCsvRejection.INPUT_TOO_LARGE,
            ArKernelSurveyMeasurementCsv.parse("x".repeat(65_537)).rejection)

        val badHeader = csv.lines().toMutableList().also { it[0] = "wrong,header" }
        val headerResult = ArKernelSurveyMeasurementCsv.parse(badHeader.joinToString("\n"))
        assertEquals(ArKernelSurveyCsvRejection.INVALID_HEADER, headerResult.rejection)
        assertEquals(1, headerResult.lineNumber)

        val tooLong = csv.lines().toMutableList().also { it[1] = "x".repeat(1_025) }
        assertEquals(ArKernelSurveyCsvRejection.LINE_TOO_LONG,
            ArKernelSurveyMeasurementCsv.parse(tooLong.joinToString("\n")).rejection)

        val blank = csv.lines().toMutableList().also { it.add(1, "") }
        assertEquals(ArKernelSurveyCsvRejection.EMPTY_LINE,
            ArKernelSurveyMeasurementCsv.parse(blank.joinToString("\n")).rejection)

        val shortRow = csv.lines().toMutableList().also { it[1] = "a,b,c" }
        assertEquals(ArKernelSurveyCsvRejection.COLUMN_COUNT,
            ArKernelSurveyMeasurementCsv.parse(shortRow.joinToString("\n")).rejection)

        val budget = ArKernelSurveyMeasurementCsv.parse(csv, maxRows = 1)
        assertEquals(ArKernelSurveyCsvRejection.ROW_COUNT_EXCEEDED, budget.rejection)
        assertTrue(budget.observations.isEmpty())
        assertFalse(budget.accepted)
    }

    @Test
    fun rejectsInvalidEvidenceFields() {
        val csv = ArKernelSurveyMeasurementCsv.encode(sampleObservations())
        assertEquals(ArKernelSurveyCsvRejection.INVALID_EVIDENCE_ID,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 1, 0, "bad id")).rejection)
        assertEquals(ArKernelSurveyCsvRejection.DUPLICATE_EVIDENCE_ID,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 2, 0, "scale-01")).rejection)
        assertEquals(ArKernelSurveyCsvRejection.INVALID_REFERENCE_KIND,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 1, 1, "UNKNOWN")).rejection)
        assertEquals(ArKernelSurveyCsvRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 1, 2, "NaN")).rejection)
        assertEquals(ArKernelSurveyCsvRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 1, 5, "not-a-number")).rejection)
        assertEquals(ArKernelSurveyCsvRejection.INVALID_BOOLEAN,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 1, 10, "yes")).rejection)
        assertEquals(ArKernelSurveyCsvRejection.EMPTY_FIELD,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 1, 4, "")).rejection)
        assertEquals(ArKernelSurveyCsvRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 1, 2, "0.0")).rejection)
        assertEquals(ArKernelSurveyCsvRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementCsv.parse(replaceColumn(csv, 1, 3, "2.0")).rejection)
    }

    @Test
    fun rejectsInvalidConfigurationsAndDuplicateEncoding() {
        val observations = sampleObservations()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementCsv.parse("", maxRows = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementCsv.parse("", maxRows = 65)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementCsv.encode(emptyList())
        }
        val duplicate = listOf(observations[0], observations[0])
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementCsv.encode(duplicate)
        }
    }

    private fun replaceColumn(csv: String, row: Int, column: Int, value: String): String {
        val lines = csv.lines().toMutableList()
        val fields = lines[row].split(',').toMutableList()
        fields[column] = value
        lines[row] = fields.joinToString(",")
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
