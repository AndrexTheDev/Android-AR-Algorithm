package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

private const val SURVEY_BINARY_MAGIC_SIZE = 16
private const val SURVEY_BINARY_VERSION = 1
private const val SURVEY_BINARY_HEADER_SIZE = 24
private const val SURVEY_BINARY_TRAILER_SIZE = 4
private const val SURVEY_BINARY_ID_BYTES = 128
private const val SURVEY_BINARY_RESERVED_BYTES = 6
private const val SURVEY_BINARY_DOUBLE_COUNT = 8
private const val SURVEY_BINARY_RECORD_SIZE =
    SURVEY_BINARY_ID_BYTES + 2 + SURVEY_BINARY_RESERVED_BYTES +
        SURVEY_BINARY_DOUBLE_COUNT * 8
private const val SURVEY_BINARY_MAX_ROWS = 64
private const val SURVEY_BINARY_MAX_INPUT_BYTES =
    SURVEY_BINARY_HEADER_SIZE +
        SURVEY_BINARY_RECORD_SIZE * SURVEY_BINARY_MAX_ROWS +
        SURVEY_BINARY_TRAILER_SIZE

private val SURVEY_BINARY_MAGIC = ByteArray(SURVEY_BINARY_MAGIC_SIZE).also {
    val marker = "ARKSURVEYBIN1".toByteArray(StandardCharsets.US_ASCII)
    require(marker.size == 13)
    marker.copyInto(it)
}
private val SURVEY_BINARY_EVIDENCE_ID = Regex("[A-Za-z0-9._:-]{1,128}")

enum class ArKernelSurveyBinaryRejection {
    NONE,
    INPUT_TOO_LARGE,
    INVALID_HEADER,
    INVALID_RESERVED,
    INVALID_LENGTH,
    EMPTY_DATASET,
    INVALID_ROW_COUNT,
    CRC_MISMATCH,
    INVALID_EVIDENCE_ID,
    DUPLICATE_EVIDENCE_ID,
    INVALID_REFERENCE_KIND,
    INVALID_NUMBER,
    INVALID_BOOLEAN,
    INVALID_OBSERVATION,
    ROW_COUNT_EXCEEDED,
}

/**
 * Fail-closed result for a bounded canonical survey binary envelope. On
 * rejection, [observations] is always empty; no partially trusted evidence is
 * returned. Parsing is only a transport adapter and never grants a quality
 * grade.
 */
data class ArKernelSurveyBinaryParseResult(
    val observations: List<HeldOutDimensionObservation>,
    val rejection: ArKernelSurveyBinaryRejection,
    val recordNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelSurveyBinaryRejection.NONE &&
            observations.isNotEmpty()
}

/**
 * Bounded canonical `ARKSURVEYBIN1` binary transport adapter for held-out
 * dimensional survey evidence.
 *
 * The envelope has a fixed 24-byte header (16-byte magic, one version byte,
 * one row-count byte, two reserved zero bytes and a little-endian payload
 * length), 1–64 fixed 200-byte records and a 4-byte little-endian CRC-32 of
 * everything before the trailer. Each record stores a zero-padded ASCII
 * evidence ID, exact reference-kind and held-out bytes, six reserved zero
 * bytes and eight little-endian IEEE-754 doubles: reference distance,
 * uncertainty, point A and point B. CRC-32 detects accidental corruption, not
 * tampering. The parser enforces exact byte/row budgets, unique bounded IDs,
 * exact reference kinds, finite numbers, canonical padding and the existing
 * dimensional evidence contract. It performs no I/O, measures no survey, runs
 * no evaluator, grants no quality grade and never implies `unverified` output
 * is `centimeter-validated`.
 */
object ArKernelSurveyMeasurementBinary {
    fun parse(
        bytes: ByteArray,
        maxRows: Int = SURVEY_BINARY_MAX_ROWS,
    ): ArKernelSurveyBinaryParseResult {
        require(maxRows in 1..SURVEY_BINARY_MAX_ROWS) {
            "Survey binary row budget must be in 1..64"
        }
        if (bytes.size > SURVEY_BINARY_MAX_INPUT_BYTES) {
            return reject(
                ArKernelSurveyBinaryRejection.INPUT_TOO_LARGE, 0,
                "Survey binary input exceeds its bounded size")
        }
        if (bytes.size < SURVEY_BINARY_HEADER_SIZE +
            SURVEY_BINARY_TRAILER_SIZE) {
            return reject(
                ArKernelSurveyBinaryRejection.INVALID_LENGTH, 0,
                "Survey binary envelope is truncated")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(SURVEY_BINARY_MAGIC_SIZE)
        buffer.get(magic)
        val version = buffer.get().toInt() and 0xff
        val rowCount = buffer.get().toInt() and 0xff
        val reserved = buffer.getShort().toInt() and 0xffff
        val payloadLength = buffer.getInt()
        if (!magic.contentEquals(SURVEY_BINARY_MAGIC) ||
            version != SURVEY_BINARY_VERSION) {
            return reject(
                ArKernelSurveyBinaryRejection.INVALID_HEADER, 0,
                "Survey binary magic or version does not match")
        }
        if (reserved != 0) {
            return reject(
                ArKernelSurveyBinaryRejection.INVALID_RESERVED, 0,
                "Survey binary header reserved bytes are nonzero")
        }
        if (rowCount == 0) {
            return reject(
                ArKernelSurveyBinaryRejection.EMPTY_DATASET, 0,
                "Survey binary contains no records")
        }
        if (rowCount > SURVEY_BINARY_MAX_ROWS) {
            return reject(
                ArKernelSurveyBinaryRejection.INVALID_ROW_COUNT, 0,
                "Survey binary row count exceeds the canonical maximum")
        }
        val expectedPayloadLength = rowCount * SURVEY_BINARY_RECORD_SIZE
        if (payloadLength != expectedPayloadLength) {
            return reject(
                ArKernelSurveyBinaryRejection.INVALID_LENGTH, 0,
                "Survey binary payload length does not match its row count")
        }
        val expectedSize = SURVEY_BINARY_HEADER_SIZE +
            expectedPayloadLength + SURVEY_BINARY_TRAILER_SIZE
        if (bytes.size != expectedSize) {
            return reject(
                ArKernelSurveyBinaryRejection.INVALID_LENGTH, 0,
                "Survey binary envelope length does not match its row count")
        }
        val checksumOffset = expectedSize - SURVEY_BINARY_TRAILER_SIZE
        val storedCrc = buffer.getInt(checksumOffset)
        val actualCrc = CRC32().apply {
            update(bytes, 0, checksumOffset)
        }.value.toInt()
        if (storedCrc != actualCrc) {
            return reject(
                ArKernelSurveyBinaryRejection.CRC_MISMATCH, 0,
                "Survey binary CRC-32 does not match its envelope")
        }
        if (rowCount > maxRows) {
            return reject(
                ArKernelSurveyBinaryRejection.ROW_COUNT_EXCEEDED, 0,
                "Survey binary exceeds its configured row budget")
        }
        val observations = mutableListOf<HeldOutDimensionObservation>()
        val ids = HashSet<String>(rowCount)
        for (index in 0 until rowCount) {
            val recordNumber = index + 1
            val recordStart = buffer.position()
            var idLength = 0
            while (idLength < SURVEY_BINARY_ID_BYTES &&
                bytes[recordStart + idLength] != 0.toByte()) {
                val raw = bytes[recordStart + idLength].toInt() and 0xff
                if (raw > 0x7f) {
                    return reject(
                        ArKernelSurveyBinaryRejection.INVALID_EVIDENCE_ID,
                        recordNumber,
                        "Survey binary evidence ID is not canonical ASCII")
                }
                ++idLength
            }
            if (idLength == 0) {
                return reject(
                    ArKernelSurveyBinaryRejection.INVALID_EVIDENCE_ID,
                    recordNumber,
                    "Survey binary evidence ID is empty")
            }
            for (padding in idLength until SURVEY_BINARY_ID_BYTES) {
                if (bytes[recordStart + padding] != 0.toByte()) {
                    return reject(
                        ArKernelSurveyBinaryRejection.INVALID_EVIDENCE_ID,
                        recordNumber,
                        "Survey binary evidence ID padding is nonzero")
                }
            }
            val idBytes = bytes.copyOfRange(
                recordStart, recordStart + idLength)
            val evidenceId = String(idBytes, StandardCharsets.US_ASCII)
            if (!SURVEY_BINARY_EVIDENCE_ID.matches(evidenceId)) {
                return reject(
                    ArKernelSurveyBinaryRejection.INVALID_EVIDENCE_ID,
                    recordNumber,
                    "Survey binary evidence ID is invalid")
            }
            if (!ids.add(evidenceId)) {
                return reject(
                    ArKernelSurveyBinaryRejection.DUPLICATE_EVIDENCE_ID,
                    recordNumber,
                    "Survey binary evidence ID is reused")
            }
            var cursor = recordStart + SURVEY_BINARY_ID_BYTES
            val kindCode = bytes[cursor].toInt() and 0xff
            val heldOutCode = bytes[cursor + 1].toInt() and 0xff
            cursor += 2
            for (reservedIndex in 0 until SURVEY_BINARY_RESERVED_BYTES) {
                if (bytes[cursor + reservedIndex] != 0.toByte()) {
                    return reject(
                        ArKernelSurveyBinaryRejection.INVALID_RESERVED,
                        recordNumber,
                        "Survey binary record reserved bytes are nonzero")
                }
            }
            cursor += SURVEY_BINARY_RESERVED_BYTES
            val referenceKind = DimensionalReferenceKind.entries.firstOrNull {
                it.nativeCode == kindCode.toLong()
            } ?: return reject(
                ArKernelSurveyBinaryRejection.INVALID_REFERENCE_KIND,
                recordNumber,
                "Survey binary reference kind is unknown")
            val heldOut = when (heldOutCode) {
                1 -> true
                0 -> false
                else -> return reject(
                    ArKernelSurveyBinaryRejection.INVALID_BOOLEAN,
                    recordNumber,
                    "Survey binary held-out flag is not 0 or 1")
            }
            val numbers = DoubleArray(SURVEY_BINARY_DOUBLE_COUNT)
            for (numberIndex in 0 until SURVEY_BINARY_DOUBLE_COUNT) {
                val bits = buffer.getLong(cursor + numberIndex * 8)
                val value = java.lang.Double.longBitsToDouble(bits)
                if (!value.isFinite()) {
                    return reject(
                        ArKernelSurveyBinaryRejection.INVALID_NUMBER,
                        recordNumber,
                        "Survey binary number is not finite")
                }
                numbers[numberIndex] = value
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
                    ArKernelSurveyBinaryRejection.INVALID_OBSERVATION,
                    recordNumber,
                    "Survey binary record violates the evidence contract")
            }
            observations.add(observation)
            buffer.position(recordStart + SURVEY_BINARY_RECORD_SIZE)
        }
        return ArKernelSurveyBinaryParseResult(
            observations,
            ArKernelSurveyBinaryRejection.NONE,
            rowCount,
            "Survey binary parsed within its bounded contract",
        )
    }

    /** Deterministic canonical encoding for already validated evidence. */
    fun encode(
        observations: List<HeldOutDimensionObservation>,
    ): ByteArray {
        require(observations.size in 1..SURVEY_BINARY_MAX_ROWS) {
            "Survey binary evidence count must be in 1..64"
        }
        val ids = HashSet<String>(observations.size)
        val rowCount = observations.size
        val payloadLength = rowCount * SURVEY_BINARY_RECORD_SIZE
        val totalSize = SURVEY_BINARY_HEADER_SIZE + payloadLength +
            SURVEY_BINARY_TRAILER_SIZE
        val output = ByteArray(totalSize)
        val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(SURVEY_BINARY_MAGIC)
        buffer.put(SURVEY_BINARY_VERSION.toByte())
        buffer.put(rowCount.toByte())
        buffer.putShort(0.toShort())
        buffer.putInt(payloadLength)
        observations.forEach { observation ->
            require(ids.add(observation.evidenceId)) {
                "Survey binary evidence IDs must be unique"
            }
            val idBytes = observation.evidenceId.toByteArray(
                StandardCharsets.US_ASCII)
            require(
                SURVEY_BINARY_EVIDENCE_ID.matches(observation.evidenceId) &&
                    idBytes.size in 1..SURVEY_BINARY_ID_BYTES) {
                "Survey binary evidence ID is invalid"
            }
            buffer.put(idBytes)
            repeat(SURVEY_BINARY_ID_BYTES - idBytes.size) {
                buffer.put(0.toByte())
            }
            buffer.put(observation.referenceKind.nativeCode.toByte())
            buffer.put(
                if (observation.heldOutFromCalibrationAndOptimization) {
                    1.toByte()
                } else {
                    0.toByte()
                })
            repeat(SURVEY_BINARY_RESERVED_BYTES) {
                buffer.put(0.toByte())
            }
            val numbers = doubleArrayOf(
                observation.referenceDistanceMeters,
                observation.referenceUncertaintyMeters,
                observation.reconstructedPointA.x,
                observation.reconstructedPointA.y,
                observation.reconstructedPointA.z,
                observation.reconstructedPointB.x,
                observation.reconstructedPointB.y,
                observation.reconstructedPointB.z,
            )
            numbers.forEach { value ->
                require(value.isFinite()) {
                    "Survey binary number is not finite"
                }
                buffer.putLong(java.lang.Double.doubleToRawLongBits(value))
            }
        }
        val checksumOffset = totalSize - SURVEY_BINARY_TRAILER_SIZE
        val crc = CRC32().apply {
            update(output, 0, checksumOffset)
        }.value.toInt()
        buffer.putInt(crc)
        require(!buffer.hasRemaining()) {
            "Survey binary encoding did not fill its bounded envelope"
        }
        return output
    }

    private fun reject(
        rejection: ArKernelSurveyBinaryRejection,
        recordNumber: Int,
        detail: String,
    ): ArKernelSurveyBinaryParseResult =
        ArKernelSurveyBinaryParseResult(emptyList(), rejection, recordNumber, detail)
}
