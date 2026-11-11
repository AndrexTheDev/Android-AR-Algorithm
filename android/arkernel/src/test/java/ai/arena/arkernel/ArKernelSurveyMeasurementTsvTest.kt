package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelSurveyMeasurementTsvTest {
    @Test
    fun roundTripsCanonicalTsvAndAcceptsBomCrlf() {
        val observations = sampleObservations()
        val tsv = ArKernelSurveyMeasurementTsv.encode(observations)
        assertTrue(tsv.startsWith("ARKSURVEYTSV1\n"))
        assertTrue(tsv.contains("\tevidence_id\treference_kind\t".trimStart()))
        assertTrue(tsv.endsWith("\tfalse"))
        assertTrue(tsv.length <= 65_536)
        assertTrue(tsv.lines().all { it.length <= 1_024 })

        val parsed = ArKernelSurveyMeasurementTsv.parse(tsv)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelSurveyTsvRejection.NONE, parsed.rejection)
        assertEquals(3, parsed.observations.size)
        assertEquals("scale-01", parsed.observations[0].evidenceId)
        assertEquals(
            DimensionalReferenceKind.HELD_OUT_SCALE_BAR,
            parsed.observations[0].referenceKind)
        assertEquals(
            2.0, parsed.observations[1].referenceDistanceMeters, 0.0)
        assertEquals(
            DimensionalReferenceKind.SENSOR_GROUND_TRUTH,
            parsed.observations[2].referenceKind)
        assertFalse(
            parsed.observations[2].heldOutFromCalibrationAndOptimization)
        assertEquals(tsv, ArKernelSurveyMeasurementTsv.encode(parsed.observations))

        val bomCrlf = "\uFEFF" + tsv.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelSurveyMeasurementTsv.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(tsv, ArKernelSurveyMeasurementTsv.encode(crlfParsed.observations))
        assertTrue(ArKernelSurveyMeasurementTsv.parse("$tsv\n").accepted)
    }

    @Test
    fun maximumBoundedEnvelopeStaysCoherent() {
        val observations = List(64) { index ->
            HeldOutDimensionObservation(
                evidenceId = "e".repeat(126) +
                    index.toString().padStart(2, '0'),
                reconstructedPointA = Vector3(0.0, 0.0, 0.0),
                reconstructedPointB = Vector3(1.0, 0.0, 0.0),
                referenceDistanceMeters = 1.0,
                referenceUncertaintyMeters = 0.0001,
                referenceKind =
                    DimensionalReferenceKind.HELD_OUT_SCALE_BAR,
                heldOutFromCalibrationAndOptimization = true,
            )
        }
        val tsv = ArKernelSurveyMeasurementTsv.encode(observations)
        assertTrue(tsv.length <= 65_536)
        assertTrue(tsv.lines().all { it.length <= 1_024 })
        val parsed = ArKernelSurveyMeasurementTsv.parse(tsv, maxRows = 64)
        assertTrue(parsed.accepted)
        assertEquals(64, parsed.observations.size)
        assertEquals(
            tsv, ArKernelSurveyMeasurementTsv.encode(parsed.observations))
    }

    @Test
    fun rejectsMalformedSchemaAndBudgets() {
        val tsv = ArKernelSurveyMeasurementTsv.encode(sampleObservations())
        assertEquals(
            ArKernelSurveyTsvRejection.EMPTY_DATASET,
            ArKernelSurveyMeasurementTsv.parse("").rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.EMPTY_DATASET,
            ArKernelSurveyMeasurementTsv.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.EMPTY_DATASET,
            ArKernelSurveyMeasurementTsv.parse(
                tsv.lines().take(2).joinToString("\n")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_HEADER,
            ArKernelSurveyMeasurementTsv.parse(
                tsv.lines().take(1).joinToString("\n")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INPUT_TOO_LARGE,
            ArKernelSurveyMeasurementTsv.parse(
                "x".repeat(65_537)).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_HEADER,
            ArKernelSurveyMeasurementTsv.parse(
                replaceLine(tsv, 0, "OTHER")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_HEADER,
            ArKernelSurveyMeasurementTsv.parse(
                replaceLine(tsv, 1, "wrong\theader")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.LINE_TOO_LONG,
            ArKernelSurveyMeasurementTsv.parse(
                replaceLine(tsv, 2, "x".repeat(1_025))).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.EMPTY_LINE,
            ArKernelSurveyMeasurementTsv.parse(
                insertLine(tsv, 2, "")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.EMPTY_LINE,
            ArKernelSurveyMeasurementTsv.parse("$tsv\n\n").rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.COLUMN_COUNT,
            ArKernelSurveyMeasurementTsv.parse(
                replaceLine(tsv, 2, "a\tb\tc")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.COLUMN_COUNT,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 0, "a\tb")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.ROW_COUNT_EXCEEDED,
            ArKernelSurveyMeasurementTsv.parse(tsv, maxRows = 1).rejection)
        val budget = ArKernelSurveyMeasurementTsv.parse(tsv, maxRows = 1)
        assertTrue(budget.observations.isEmpty())
        assertFalse(budget.accepted)
    }

    @Test
    fun rejectsInvalidEvidenceFields() {
        val tsv = ArKernelSurveyMeasurementTsv.encode(sampleObservations())
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_EVIDENCE_ID,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 0, "bad id")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.DUPLICATE_EVIDENCE_ID,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 1, 0, "scale-01")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_REFERENCE_KIND,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 1, "UNKNOWN")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 2, "NaN")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 2, "Infinity")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 5, "not-a-number")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_BOOLEAN,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 10, "yes")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_BOOLEAN,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 10, "TRUE")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.EMPTY_FIELD,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 4, "")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.EMPTY_FIELD,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 10, "")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 2, "0.0")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 3, "2.0")).rejection)
        assertEquals(
            ArKernelSurveyTsvRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementTsv.parse(
                replaceColumn(tsv, 0, 3, "-0.1")).rejection)
    }

    @Test
    fun rejectsInvalidConfigurationsAndDuplicateEncoding() {
        val observations = sampleObservations()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementTsv.parse("", maxRows = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementTsv.parse("", maxRows = 65)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementTsv.encode(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementTsv.encode(
                listOf(observations[0], observations[0]))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementTsv.encode(
                List(65) { index ->
                    observations[0].copy(evidenceId = "e.$index")
                })
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeldOutDimensionObservation(
                evidenceId = "bad\tid",
                reconstructedPointA = Vector3(0.0, 0.0, 0.0),
                reconstructedPointB = Vector3(1.0, 0.0, 0.0),
                referenceDistanceMeters = 1.0,
                referenceUncertaintyMeters = 0.001,
                referenceKind =
                    DimensionalReferenceKind.HELD_OUT_SCALE_BAR,
            )
        }
    }

    private fun sampleObservations():
        List<HeldOutDimensionObservation> = listOf(
        HeldOutDimensionObservation(
            evidenceId = "scale-01",
            reconstructedPointA = Vector3(0.0, 0.0, 0.0),
            reconstructedPointB = Vector3(1.0, 0.0, 0.0),
            referenceDistanceMeters = 1.0,
            referenceUncertaintyMeters = 0.001,
            referenceKind = DimensionalReferenceKind.HELD_OUT_SCALE_BAR,
            heldOutFromCalibrationAndOptimization = true,
        ),
        HeldOutDimensionObservation(
            evidenceId = "survey-02",
            reconstructedPointA = Vector3(0.0, 0.0, 0.0),
            reconstructedPointB = Vector3(0.0, 2.0, 0.0),
            referenceDistanceMeters = 2.0,
            referenceUncertaintyMeters = 0.002,
            referenceKind = DimensionalReferenceKind.SURVEYED_DISTANCE,
            heldOutFromCalibrationAndOptimization = true,
        ),
        HeldOutDimensionObservation(
            evidenceId = "sensor-03",
            reconstructedPointA = Vector3(1.0, 1.0, 1.0),
            reconstructedPointB = Vector3(1.0, 1.0, 2.0),
            referenceDistanceMeters = 1.0,
            referenceUncertaintyMeters = 0.0,
            referenceKind = DimensionalReferenceKind.SENSOR_GROUND_TRUTH,
            heldOutFromCalibrationAndOptimization = false,
        ),
    )

    private fun replaceLine(tsv: String, lineIndex: Int, value: String): String {
        val lines = tsv.lines().toMutableList()
        lines[lineIndex] = value
        return lines.joinToString("\n")
    }

    private fun insertLine(tsv: String, lineIndex: Int, value: String): String {
        val lines = tsv.lines().toMutableList()
        lines.add(lineIndex, value)
        return lines.joinToString("\n")
    }

    private fun replaceColumn(
        tsv: String,
        rowIndex: Int,
        columnIndex: Int,
        value: String,
    ): String {
        val lines = tsv.lines().toMutableList()
        val lineIndex = 2 + rowIndex
        val fields = lines[lineIndex].split('\t').toMutableList()
        fields[columnIndex] = value
        lines[lineIndex] = fields.joinToString("\t")
        return lines.joinToString("\n")
    }
}
