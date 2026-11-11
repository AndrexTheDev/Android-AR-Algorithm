package ai.arena.arkernel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

private const val BIN_HEADER_SIZE = 24
private const val BIN_RECORD_SIZE = 200
private const val BIN_TRAILER_SIZE = 4
private const val BIN_ID_BYTES = 128
private const val BIN_MAX_INPUT_BYTES = 12_828

class ArKernelSurveyMeasurementBinaryTest {
    @Test
    fun roundTripsCanonicalBinaryAndIsDeterministic() {
        val observations = sampleObservations()
        val bytes = ArKernelSurveyMeasurementBinary.encode(observations)
        assertEquals(BIN_HEADER_SIZE + 3 * BIN_RECORD_SIZE +
            BIN_TRAILER_SIZE, bytes.size)
        assertEquals(
            "ARKSURVEYBIN1",
            String(bytes.copyOfRange(0, 13), StandardCharsets.US_ASCII))
        assertEquals(0, bytes[13].toInt())
        assertEquals(0, bytes[14].toInt())
        assertEquals(0, bytes[15].toInt())
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.position(16)
        assertEquals(1, header.get().toInt() and 0xff)
        assertEquals(3, header.get().toInt() and 0xff)
        assertEquals(0, header.getShort().toInt() and 0xffff)
        assertEquals(3 * BIN_RECORD_SIZE, header.getInt())
        assertTrue(bytes.size <= BIN_MAX_INPUT_BYTES)
        assertArrayEquals(bytes, recomputeCrc(bytes))

        val parsed = ArKernelSurveyMeasurementBinary.parse(bytes)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelSurveyBinaryRejection.NONE, parsed.rejection)
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
        assertArrayEquals(
            bytes, ArKernelSurveyMeasurementBinary.encode(parsed.observations))
        assertArrayEquals(
            bytes, ArKernelSurveyMeasurementBinary.encode(observations))
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
        val bytes = ArKernelSurveyMeasurementBinary.encode(observations)
        assertEquals(BIN_MAX_INPUT_BYTES, bytes.size)
        val parsed = ArKernelSurveyMeasurementBinary.parse(bytes, maxRows = 64)
        assertTrue(parsed.accepted)
        assertEquals(64, parsed.observations.size)
        assertArrayEquals(
            bytes, ArKernelSurveyMeasurementBinary.encode(parsed.observations))
    }

    @Test
    fun rejectsMalformedHeaderLengthAndCrc() {
        val bytes = ArKernelSurveyMeasurementBinary.encode(sampleObservations())
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_LENGTH,
            ArKernelSurveyMeasurementBinary.parse(ByteArray(0)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INPUT_TOO_LARGE,
            ArKernelSurveyMeasurementBinary.parse(
                ByteArray(BIN_MAX_INPUT_BYTES + 1)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_LENGTH,
            ArKernelSurveyMeasurementBinary.parse(
                bytes.copyOf(bytes.size - 1)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_LENGTH,
            ArKernelSurveyMeasurementBinary.parse(
                bytes.copyOf(bytes.size + 1)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_HEADER,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, 0, 'X'.code.toByte())).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_HEADER,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, 16, 2)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_RESERVED,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, 18, 1)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.EMPTY_DATASET,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, 17, 0)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_ROW_COUNT,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, 17, 65)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_LENGTH,
            ArKernelSurveyMeasurementBinary.parse(
                withInt(bytes, 20, 3 * BIN_RECORD_SIZE - 1)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.CRC_MISMATCH,
            ArKernelSurveyMeasurementBinary.parse(
                bytes.copyOf().also {
                    it[it.size - 1] =
                        (it[it.size - 1].toInt() xor 0x01).toByte()
                }).rejection)
        val budget = ArKernelSurveyMeasurementBinary.parse(bytes, maxRows = 2)
        assertEquals(
            ArKernelSurveyBinaryRejection.ROW_COUNT_EXCEEDED,
            budget.rejection)
        assertTrue(budget.observations.isEmpty())
        assertFalse(budget.accepted)
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementBinary.parse(bytes, maxRows = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementBinary.parse(bytes, maxRows = 65)
        }
    }

    @Test
    fun rejectsInvalidRecordFields() {
        val bytes = ArKernelSurveyMeasurementBinary.encode(sampleObservations())
        val first = recordOffset(0)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_EVIDENCE_ID,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, first, ' '.code.toByte())).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_EVIDENCE_ID,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, first, 0x80.toByte())).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_EVIDENCE_ID,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, first, 0)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.DUPLICATE_EVIDENCE_ID,
            ArKernelSurveyMeasurementBinary.parse(
                withCopiedId(bytes, 0, 1)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_REFERENCE_KIND,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, first + BIN_ID_BYTES, 9)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_BOOLEAN,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, first + BIN_ID_BYTES + 1, 2)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_RESERVED,
            ArKernelSurveyMeasurementBinary.parse(
                withByte(bytes, first + BIN_ID_BYTES + 2, 1)).rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_NUMBER,
            ArKernelSurveyMeasurementBinary.parse(
                withDouble(bytes, first + BIN_ID_BYTES + 8, Double.NaN))
                .rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementBinary.parse(
                withDouble(bytes, first + BIN_ID_BYTES + 8, -1.0))
                .rejection)
        assertEquals(
            ArKernelSurveyBinaryRejection.INVALID_OBSERVATION,
            ArKernelSurveyMeasurementBinary.parse(
                withDouble(bytes, first + BIN_ID_BYTES + 16, 2.0))
                .rejection)
    }

    @Test
    fun rejectsInvalidConfigurationsAndDuplicateEncoding() {
        val observations = sampleObservations()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementBinary.encode(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementBinary.encode(
                listOf(observations[0], observations[0]))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSurveyMeasurementBinary.encode(
                List(65) { index ->
                    observations[0].copy(evidenceId = "e.$index")
                })
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeldOutDimensionObservation(
                evidenceId = "bad id",
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

    private fun recordOffset(index: Int): Int =
        BIN_HEADER_SIZE + index * BIN_RECORD_SIZE

    private fun recomputeCrc(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        val checksumOffset = copy.size - BIN_TRAILER_SIZE
        val crc = CRC32().apply {
            update(copy, 0, checksumOffset)
        }.value.toInt()
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(checksumOffset, crc)
        return copy
    }

    private fun withByte(
        bytes: ByteArray,
        offset: Int,
        value: Byte,
    ): ByteArray {
        val copy = bytes.copyOf()
        copy[offset] = value
        return recomputeCrc(copy)
    }

    private fun withInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ): ByteArray {
        val copy = bytes.copyOf()
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(offset, value)
        return recomputeCrc(copy)
    }

    private fun withDouble(
        bytes: ByteArray,
        offset: Int,
        value: Double,
    ): ByteArray {
        val copy = bytes.copyOf()
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(offset, java.lang.Double.doubleToRawLongBits(value))
        return recomputeCrc(copy)
    }

    private fun withCopiedId(
        bytes: ByteArray,
        fromRecord: Int,
        toRecord: Int,
    ): ByteArray {
        val copy = bytes.copyOf()
        val from = recordOffset(fromRecord)
        val id = copy.copyOfRange(from, from + BIN_ID_BYTES)
        id.copyInto(copy, recordOffset(toRecord))
        return recomputeCrc(copy)
    }
}
