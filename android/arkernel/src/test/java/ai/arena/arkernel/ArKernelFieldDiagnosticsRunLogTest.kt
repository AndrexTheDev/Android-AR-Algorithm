package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelFieldDiagnosticsRunLogTest {
    @Test
    fun canonicalLogRoundTripRequiresExplicitPolicyOptIn() {
        val events = sampleEvents()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRunLog.encode(
                ArKernelFieldDiagnosticsPolicy("field-log"),
                "session-a", "run-1", events)
        }
        val policy = ArKernelFieldDiagnosticsPolicy(
            "field-log", collectionOptIn = true)
        val text = ArKernelFieldDiagnosticsRunLog.encode(
            policy, "session-a", "run-1", events)
        assertEquals(listOf(
            "ARKFDLOG1",
            "version=1",
            "session_id=session-a",
            "run_id=run-1",
            "policy_id=field-log",
            "quality_grade=unverified",
            "event_count=5",
            "event=0|0|RUN_START|-|-|-",
            "event=1|1000000000|CAPABILITY|api35|35|arm64-v8a",
            "event=2|2000000000|THERMAL|WARM|30|-",
            "event=3|3000000000|SESSION_TERMINAL|COMPLETE|1|unverified",
            "event=4|4000000000|RUN_END|-|5|-",
            "END",
        ).joinToString("\n"), text)

        val record = ArKernelFieldDiagnosticsLogRecord(
            "session-a", "run-1", "field-log", events)
        val parsed = ArKernelFieldDiagnosticsRunLog.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.NONE, parsed.rejection)
        assertEquals(record, parsed.record)
        assertEquals("unverified", parsed.record!!.qualityGrade)
        assertEquals(5, parsed.record.events.size)

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelFieldDiagnosticsRunLog.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(record, crlfParsed.record)
        assertTrue(ArKernelFieldDiagnosticsRunLog.parse("$text\n").accepted)
    }

    @Test
    fun recordRejectsUnsafeEventSequencesAndQualityUpgrade() {
        val events = sampleEvents()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsLogRecord(
                "session-a", "run-1", "field-log", events.drop(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val broken = events.toMutableList()
            broken[2] = broken[2].copy(timestampNs = 1_000_000_000L)
            ArKernelFieldDiagnosticsLogRecord(
                "session-a", "run-1", "field-log", broken)
        }
        assertThrows(IllegalArgumentException::class.java) {
            val broken = events.toMutableList()
            broken[2] = broken[2].copy(sequence = 1)
            ArKernelFieldDiagnosticsLogRecord(
                "session-a", "run-1", "field-log", broken)
        }
        assertThrows(IllegalArgumentException::class.java) {
            val broken = events.toMutableList()
            broken[2] = broken[2].copy(
                kind = ArKernelFieldDiagnosticsLogEventKind.RUN_END)
            ArKernelFieldDiagnosticsLogRecord(
                "session-a", "run-1", "field-log", broken)
        }
        assertThrows(IllegalArgumentException::class.java) {
            val many = List(129) { index ->
                ArKernelFieldDiagnosticsLogEvent(
                    index, index.toLong(),
                    when (index) {
                        0 -> ArKernelFieldDiagnosticsLogEventKind.RUN_START
                        128 -> ArKernelFieldDiagnosticsLogEventKind.RUN_END
                        else -> ArKernelFieldDiagnosticsLogEventKind.CAPABILITY
                    }, null, null, null)
            }
            ArKernelFieldDiagnosticsLogRecord(
                "session-a", "run-1", "field-log", many)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsLogRecord(
                "session-a", "run-1", "field-log", events, "assessed")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsLogRecord(
                "bad/id", "run-1", "field-log", events)
        }
        assertThrows(IllegalArgumentException::class.java) {
            events[1].copy(code = "bad/id")
        }
    }

    @Test
    fun parseRejectsMalformedUnsafeAndSensitiveInputs() {
        val text = canonicalText()
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INPUT_TOO_LARGE,
            ArKernelFieldDiagnosticsRunLog.parse("x".repeat(32_769)).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.EMPTY_LOG,
            ArKernelFieldDiagnosticsRunLog.parse("").rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.EMPTY_LOG,
            ArKernelFieldDiagnosticsRunLog.parse("\uFEFF").rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.EMPTY_LOG,
            ArKernelFieldDiagnosticsRunLog.parse("\n").rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_HEADER,
            ArKernelFieldDiagnosticsRunLog.parse(
                "WRONG\n" + text.lines().drop(1).joinToString("\n")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRunLog.parse("$text\nextra=1").rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRunLog.parse("$text\n\n").rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceLine(text, "version=", "ver=1")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventLine(text, 1, "event=1")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventLine(text, 1, " event=1|1000000000|CAPABILITY|api35|35|arm64-v8a")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.LINE_TOO_LONG,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventLine(text, 1, "x".repeat(513))).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.EMPTY_LINE,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventLine(text, 1, "")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceMetadataValue(text, "version", "2")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_QUALITY,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceMetadataValue(text, "quality_grade", "assessed")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceMetadataValue(text, "event_count", "1")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_TOKEN,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceMetadataValue(text, "policy_id", "bad/id")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_ORDER,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventField(text, 2, 0, "1")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_ORDER,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventField(text, 2, 1, "1000000000")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventField(text, 2, 2, "UNKNOWN")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventField(text, 2, 4, "-1")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_TOKEN,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventField(text, 2, 5, "bad/id")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventLine(text, 4,
                    "event=4|4000000000|RUN_END|-|5|-|extra")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceLine(text, "END", "STOP")).rejection)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.UNSAFE_EVENT,
            ArKernelFieldDiagnosticsRunLog.parse(
                replaceEventField(text, 0, 2, "CAPABILITY")).rejection)
    }

    @Test
    fun logTextNeverCarriesSensitiveOrQualityClaims() {
        val text = canonicalText()
        assertTrue(text.contains("quality_grade=unverified"))
        assertFalse(text.contains("quality_grade=verified"))
        for (forbidden in listOf(
            "image",
            "trajectory",
            "pose",
            "calibration",
            "pixel",
            "depth",
            "centimeter",
            "signature_verified",
            "upload_enabled",
        )) {
            assertFalse("Unexpected $forbidden in diagnostics log",
                text.contains(forbidden, ignoreCase = true))
        }
        assertTrue(text.lines().all { it.length <= 512 })
    }

    @Test
    fun allBoundedPhysicalSelfTestKindsRoundTrip() {
        val middleKinds = ArKernelFieldDiagnosticsLogEventKind.entries.filter {
            it != ArKernelFieldDiagnosticsLogEventKind.RUN_START &&
                it != ArKernelFieldDiagnosticsLogEventKind.RUN_END
        }
        val events = mutableListOf<ArKernelFieldDiagnosticsLogEvent>()
        events += ArKernelFieldDiagnosticsLogEvent(
            0, 0L,
            ArKernelFieldDiagnosticsLogEventKind.RUN_START,
            "self.test", null, "unverified",
        )
        middleKinds.forEachIndexed { index, kind ->
            events += ArKernelFieldDiagnosticsLogEvent(
                index + 1, (index + 1).toLong() * 1_000_000L,
                kind, "test.kind", 1L, "unverified",
            )
        }
        events += ArKernelFieldDiagnosticsLogEvent(
            middleKinds.size + 1,
            (middleKinds.size + 1).toLong() * 1_000_000L,
            ArKernelFieldDiagnosticsLogEventKind.RUN_END,
            null, (middleKinds.size + 2).toLong(), null,
        )
        val record = ArKernelFieldDiagnosticsLogRecord(
            "session.self", "run.self", "self.test.v1", events)
        val text = ArKernelFieldDiagnosticsRunLog.encode(record)
        val parsed = ArKernelFieldDiagnosticsRunLog.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelFieldDiagnosticsLogRejection.NONE, parsed.rejection)
        assertEquals(middleKinds.size + 2, parsed.record!!.events.size)
        assertEquals("unverified", parsed.record.qualityGrade)
        assertTrue(text.lines().all { it.length <= 512 })
    }

    private fun canonicalText(): String =
        ArKernelFieldDiagnosticsRunLog.encode(
            ArKernelFieldDiagnosticsPolicy(
                "field-log", collectionOptIn = true),
            "session-a", "run-1", sampleEvents())

    private fun sampleEvents(): List<ArKernelFieldDiagnosticsLogEvent> = listOf(
        ArKernelFieldDiagnosticsLogEvent(0, 0L,
            ArKernelFieldDiagnosticsLogEventKind.RUN_START, null, null, null),
        ArKernelFieldDiagnosticsLogEvent(1, 1_000_000_000L,
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "api35", 35L, "arm64-v8a"),
        ArKernelFieldDiagnosticsLogEvent(2, 2_000_000_000L,
            ArKernelFieldDiagnosticsLogEventKind.THERMAL,
            "WARM", 30L, null),
        ArKernelFieldDiagnosticsLogEvent(3, 3_000_000_000L,
            ArKernelFieldDiagnosticsLogEventKind.SESSION_TERMINAL,
            "COMPLETE", 1L, "unverified"),
        ArKernelFieldDiagnosticsLogEvent(4, 4_000_000_000L,
            ArKernelFieldDiagnosticsLogEventKind.RUN_END,
            null, 5L, null),
    )

    private fun replaceMetadataValue(
        text: String,
        key: String,
        value: String,
    ): String = replaceLine(text, "$key=", "$key=$value")

    private fun replaceEventLine(
        text: String,
        sequence: Int,
        line: String,
    ): String = replaceLine(text, "event=$sequence|", line)

    private fun replaceEventField(
        text: String,
        sequence: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("event=$sequence|") }
        require(index >= 0) { "Unknown diagnostics log event" }
        val fields = lines[index].substringAfter("event=")
            .split('|').toMutableList()
        fields[fieldIndex] = value
        lines[index] = "event=" + fields.joinToString("|")
        return lines.joinToString("\n")
    }

    private fun replaceLine(
        text: String,
        prefix: String,
        line: String,
    ): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith(prefix) }
        require(index >= 0) { "Unknown diagnostics log prefix" }
        lines[index] = line
        return lines.joinToString("\n")
    }
}
