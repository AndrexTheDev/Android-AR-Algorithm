package ai.arena.arkernel

private const val FIELD_LOG_HEADER = "ARKFDLOG1"
private const val FIELD_LOG_VERSION = "1"
private const val FIELD_LOG_QUALITY = "unverified"
private const val FIELD_LOG_END = "END"
private const val FIELD_LOG_MAX_INPUT_CHARACTERS = 32_768
private const val FIELD_LOG_MAX_LINE_CHARACTERS = 512
private const val FIELD_LOG_MIN_EVENTS = 2
private const val FIELD_LOG_MAX_EVENTS = 128
private const val FIELD_LOG_MAX_TOKEN_LENGTH = 128

private val FIELD_LOG_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val FIELD_LOG_TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")
private val FIELD_LOG_UNSIGNED = Regex("0|[1-9][0-9]*")

private val FIELD_LOG_METADATA_KEYS = listOf(
    "version",
    "session_id",
    "run_id",
    "policy_id",
    "quality_grade",
    "event_count",
)

enum class ArKernelFieldDiagnosticsLogEventKind {
    RUN_START,
    CAPABILITY,
    SENSOR_CATALOG,
    CAMERA_CATALOG,
    GUIDED_STEP,
    IMU_STILLNESS,
    CAMERA_LIFECYCLE,
    CAMERA_FRAMES,
    STORAGE_ROUNDTRIP,
    COMPUTE_SELFTEST,
    TIMING,
    THERMAL,
    THERMAL_SOAK,
    SERVICE_DEATH,
    SESSION_TERMINAL,
    RUN_END,
}

enum class ArKernelFieldDiagnosticsLogRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_LOG,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_ORDER,
    UNSAFE_EVENT,
}

/** Fail-closed result; on rejection no partially trusted run log is returned. */
data class ArKernelFieldDiagnosticsLogParseResult(
    val record: ArKernelFieldDiagnosticsLogRecord?,
    val rejection: ArKernelFieldDiagnosticsLogRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelFieldDiagnosticsLogRejection.NONE && record != null
}

/**
 * One bounded, text-only diagnostic event. Opaque tokens may carry stable
 * reason codes only; they never carry images, poses, trajectories, calibration
 * arrays, pixels or dense geometry.
 */
data class ArKernelFieldDiagnosticsLogEvent(
    val sequence: Int,
    val timestampNs: Long,
    val kind: ArKernelFieldDiagnosticsLogEventKind,
    val code: String?,
    val value: Long?,
    val detailToken: String?,
) {
    init {
        require(sequence >= 0) { "Invalid diagnostics log sequence" }
        require(timestampNs >= 0L) { "Invalid diagnostics log timestamp" }
        code?.let {
            require(FIELD_LOG_TOKEN.matches(it)) { "Invalid diagnostics log code" }
        }
        detailToken?.let {
            require(FIELD_LOG_TOKEN.matches(it)) {
                "Invalid diagnostics log detail token"
            }
        }
        value?.let { require(it >= 0L) { "Invalid diagnostics log value" } }
    }
}

/** Bounded ordered run log; timestamps must strictly increase. */
data class ArKernelFieldDiagnosticsLogRecord(
    val sessionId: String,
    val runId: String,
    val policyId: String,
    val events: List<ArKernelFieldDiagnosticsLogEvent>,
    val qualityGrade: String = FIELD_LOG_QUALITY,
) {
    init {
        require(FIELD_LOG_ID.matches(sessionId)) { "Invalid diagnostics log session ID" }
        require(FIELD_LOG_ID.matches(runId)) { "Invalid diagnostics log run ID" }
        require(FIELD_LOG_ID.matches(policyId)) { "Invalid diagnostics log policy ID" }
        require(qualityGrade == FIELD_LOG_QUALITY) {
            "Diagnostics log cannot assert assessed quality"
        }
        require(events.size in FIELD_LOG_MIN_EVENTS..FIELD_LOG_MAX_EVENTS) {
            "Invalid diagnostics log event count"
        }
        require(events.first().kind ==
            ArKernelFieldDiagnosticsLogEventKind.RUN_START) {
            "Diagnostics log must start with RUN_START"
        }
        require(events.last().kind ==
            ArKernelFieldDiagnosticsLogEventKind.RUN_END) {
            "Diagnostics log must end with RUN_END"
        }
        require(events.drop(1).dropLast(1).none {
            it.kind == ArKernelFieldDiagnosticsLogEventKind.RUN_START ||
                it.kind == ArKernelFieldDiagnosticsLogEventKind.RUN_END
        }) { "Diagnostics log has misplaced terminal events" }
        require(events.withIndex().all { it.value.sequence == it.index }) {
            "Diagnostics log sequence is not contiguous"
        }
        require(events.zipWithNext().all { (first, second) ->
            second.timestampNs > first.timestampNs
        }) { "Diagnostics log timestamps must strictly increase" }
    }
}

/**
 * Canonical `ARKFDLOG1` text adapter for an optional host diagnostic run.
 *
 * A host app may write this exact text after an explicitly opted-in
 * `ARKFDPOL1` policy. This adapter performs no I/O, collection, redaction,
 * upload, signature verification or device validation. It carries only bounded
 * opaque event tokens, never images, per-frame poses, trajectories,
 * calibration arrays, pixels or dense geometry, and stays `unverified`. It can
 * supply bounded runtime facts but cannot replace physical targets, a rig,
 * controlled thermal runs or device-farm evidence. The additional catalog,
 * guided-step, stillness, frame, storage, compute and thermal-soak kinds carry
 * only bounded structural self-test scalars; they are not physical ground
 * truth and cannot assert assessed quality.
 */
object ArKernelFieldDiagnosticsRunLog {
    /** Requires the host's explicit collection opt-in; still performs no I/O. */
    fun encode(
        policy: ArKernelFieldDiagnosticsPolicy,
        sessionId: String,
        runId: String,
        events: List<ArKernelFieldDiagnosticsLogEvent>,
    ): String {
        require(policy.collectionOptIn) {
            "Diagnostics run log requires explicit policy opt-in"
        }
        require(policy.qualityGrade == FIELD_LOG_QUALITY) {
            "Diagnostics run log requires an unverified policy"
        }
        return encode(ArKernelFieldDiagnosticsLogRecord(
            sessionId, runId, policy.policyId, events))
    }

    fun encode(record: ArKernelFieldDiagnosticsLogRecord): String {
        val lines = ArrayList<String>(record.events.size +
            FIELD_LOG_METADATA_KEYS.size + 2)
        lines.add(FIELD_LOG_HEADER)
        lines.add("version=$FIELD_LOG_VERSION")
        lines.add("session_id=${record.sessionId}")
        lines.add("run_id=${record.runId}")
        lines.add("policy_id=${record.policyId}")
        lines.add("quality_grade=${record.qualityGrade}")
        lines.add("event_count=${record.events.size}")
        for (event in record.events) {
            val code = event.code ?: "-"
            val value = event.value?.toString() ?: "-"
            val detail = event.detailToken ?: "-"
            lines.add("event=${event.sequence}|${event.timestampNs}|" +
                "${event.kind.name}|$code|$value|$detail")
        }
        lines.add(FIELD_LOG_END)
        val text = lines.joinToString("\n")
        require(text.length <= FIELD_LOG_MAX_INPUT_CHARACTERS) {
            "Diagnostics run log exceeds its bounded size"
        }
        require(lines.all { it.length <= FIELD_LOG_MAX_LINE_CHARACTERS }) {
            "Diagnostics run log line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelFieldDiagnosticsLogParseResult {
        if (text.length > FIELD_LOG_MAX_INPUT_CHARACTERS) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INPUT_TOO_LARGE, 0,
                "Diagnostics run log input exceeds its bounded size")
        }
        val normalized = if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.EMPTY_LOG, 0,
                "Diagnostics run log input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.EMPTY_LOG, 0,
                "Diagnostics run log input contains no log")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != FIELD_LOG_HEADER || header.any { it.isWhitespace() } ||
            header.length > FIELD_LOG_MAX_LINE_CHARACTERS) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_HEADER, 1,
                "Diagnostics run log header is invalid")
        }
        if (lines.size < FIELD_LOG_METADATA_KEYS.size + 3) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA, 0,
                "Diagnostics run log is too short")
        }
        val lineByKey = FIELD_LOG_METADATA_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(FIELD_LOG_METADATA_KEYS.size)
        for (index in FIELD_LOG_METADATA_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLogLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Diagnostics run log metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != FIELD_LOG_METADATA_KEYS[index]) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Diagnostics run log metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != FIELD_LOG_VERSION) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported diagnostics run log version")
        }
        if (values.getValue("quality_grade") != FIELD_LOG_QUALITY) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Diagnostics run log cannot assert assessed quality")
        }
        val sessionId = values.getValue("session_id")
        val runId = values.getValue("run_id")
        val policyId = values.getValue("policy_id")
        for ((key, value) in listOf(
            "session_id" to sessionId,
            "run_id" to runId,
            "policy_id" to policyId,
        )) {
            if (!FIELD_LOG_ID.matches(value)) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid diagnostics run log $key")
            }
        }
        val eventCount = logUnsignedInt(values.getValue("event_count"))
            ?: return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
                lineByKey.getValue("event_count"),
                "Invalid diagnostics run log event count")
        if (eventCount !in FIELD_LOG_MIN_EVENTS..FIELD_LOG_MAX_EVENTS) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
                lineByKey.getValue("event_count"),
                "Diagnostics run log event count is out of bounds")
        }
        val eventStartIndex = FIELD_LOG_METADATA_KEYS.size + 1
        val endIndex = eventStartIndex + eventCount
        if (lines.size != endIndex + 1) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA, 0,
                "Diagnostics run log line count does not match event count")
        }
        val events = ArrayList<ArKernelFieldDiagnosticsLogEvent>(eventCount)
        var previousTimestamp = -1L
        for (index in 0 until eventCount) {
            val lineNumber = eventStartIndex + index + 1
            val lineText = lines[eventStartIndex + index].trimEnd('\r')
            val rejection = validateLogLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("event=")) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Diagnostics run log line is not an event")
            }
            val fields = lineText.substringAfter("event=").split('|')
            if (fields.size != 6) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Diagnostics run log event must have six fields")
            }
            val sequence = logUnsignedInt(fields[0])
                ?: return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
                    lineNumber, "Invalid diagnostics log sequence")
            if (sequence != index) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_ORDER,
                    lineNumber, "Diagnostics log sequence is not contiguous")
            }
            val timestampNs = logUnsignedLong(fields[1])
                ?: return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
                    lineNumber, "Invalid diagnostics log timestamp")
            if (timestampNs <= previousTimestamp) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_ORDER,
                    lineNumber,
                    "Diagnostics log timestamps must strictly increase")
            }
            previousTimestamp = timestampNs
            val kind = ArKernelFieldDiagnosticsLogEventKind.entries.firstOrNull {
                it.name == fields[2]
            } ?: return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
                lineNumber, "Invalid diagnostics log event kind")
            val (codeValid, code) = logOptionalToken(fields[3])
            if (!codeValid) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_TOKEN,
                    lineNumber, "Invalid diagnostics log code")
            }
            val value = if (fields[4] == "-") {
                null
            } else {
                logUnsignedLong(fields[4]) ?: return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_VALUE,
                    lineNumber, "Invalid diagnostics log value")
            }
            val (detailValid, detailToken) = logOptionalToken(fields[5])
            if (!detailValid) {
                return logReject(
                    ArKernelFieldDiagnosticsLogRejection.INVALID_TOKEN,
                    lineNumber, "Invalid diagnostics log detail token")
            }
            events += ArKernelFieldDiagnosticsLogEvent(
                sequence, timestampNs, kind, code, value, detailToken)
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endRejection = validateLogLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != FIELD_LOG_END) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
                endLineNumber,
                "Diagnostics run log must end with END")
        }
        if (events.first().kind !=
            ArKernelFieldDiagnosticsLogEventKind.RUN_START ||
            events.last().kind !=
            ArKernelFieldDiagnosticsLogEventKind.RUN_END ||
            events.drop(1).dropLast(1).any {
                it.kind == ArKernelFieldDiagnosticsLogEventKind.RUN_START ||
                    it.kind == ArKernelFieldDiagnosticsLogEventKind.RUN_END
            }) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.UNSAFE_EVENT,
                eventStartIndex + 1,
                "Diagnostics run log terminal events are misplaced")
        }
        val record = try {
            ArKernelFieldDiagnosticsLogRecord(
                sessionId, runId, policyId, events, FIELD_LOG_QUALITY)
        } catch (error: IllegalArgumentException) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.UNSAFE_EVENT,
                0, error.message ?: "Invalid diagnostics run log")
        }
        return ArKernelFieldDiagnosticsLogParseResult(
            record,
            ArKernelFieldDiagnosticsLogRejection.NONE,
            0,
            "",
        )
    }

    private fun validateLogLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelFieldDiagnosticsLogParseResult? {
        if (lineText.length > FIELD_LOG_MAX_LINE_CHARACTERS) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.LINE_TOO_LONG,
                lineNumber,
                "Diagnostics run log line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.EMPTY_LINE,
                lineNumber,
                "Diagnostics run log contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return logReject(
                ArKernelFieldDiagnosticsLogRejection.INVALID_SCHEMA,
                lineNumber,
                "Diagnostics run log lines must not contain whitespace")
        }
        return null
    }
}

/** Validity plus an optional token; `-` is a valid explicit absence. */
private fun logOptionalToken(value: String): Pair<Boolean, String?> = when {
    value == "-" -> true to null
    FIELD_LOG_TOKEN.matches(value) && value.length <= FIELD_LOG_MAX_TOKEN_LENGTH ->
        true to value
    else -> false to null
}

private fun logUnsignedInt(value: String): Int? =
    if (FIELD_LOG_UNSIGNED.matches(value)) value.toIntOrNull() else null

private fun logUnsignedLong(value: String): Long? =
    if (FIELD_LOG_UNSIGNED.matches(value)) value.toLongOrNull() else null

private fun logReject(
    rejection: ArKernelFieldDiagnosticsLogRejection,
    lineNumber: Int,
    detail: String,
): ArKernelFieldDiagnosticsLogParseResult =
    ArKernelFieldDiagnosticsLogParseResult(null, rejection, lineNumber, detail)
