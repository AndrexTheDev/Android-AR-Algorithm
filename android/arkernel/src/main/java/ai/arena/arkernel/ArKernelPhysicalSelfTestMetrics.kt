package ai.arena.arkernel

import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.sqrt

private val SELF_TEST_TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")

/** One bounded local sensor sample; no device-model assumption is applied. */
data class ArKernelAxisSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

/** Structural summary of one three-axis stream. */
data class ArKernelAxisSummary(
    val label: String,
    val sampleCount: Int,
    val expectedMinSamples: Int,
    val monotonicViolations: Int,
    val nonFiniteValues: Int,
    val maxGapNs: Long,
    val meanX: Double,
    val meanY: Double,
    val meanZ: Double,
    val stdDevX: Double,
    val stdDevY: Double,
    val stdDevZ: Double,
) {
    val structuralPass: Boolean
        get() = sampleCount >= expectedMinSamples &&
            monotonicViolations == 0 && nonFiniteValues == 0 && maxGapNs >= 0L
}

/** Structural summary of a repeated Camera2 preview run. */
data class ArKernelCaptureSummary(
    val frameCount: Int,
    val expectedMinFrames: Int,
    val monotonicViolations: Int,
    val nonFiniteValues: Int,
    val maxGapNs: Long,
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val minIso: Int,
    val maxIso: Int,
    val minFocusDistance: Float,
    val maxFocusDistance: Float,
    val errorCount: Int,
    val disconnectCount: Int,
) {
    val structuralPass: Boolean
        get() = frameCount >= expectedMinFrames &&
            monotonicViolations == 0 && nonFiniteValues == 0 &&
            errorCount == 0 && disconnectCount == 0
}

/** One bounded local thermal sample. */
data class ArKernelThermalSample(
    val timestampNs: Long,
    val status: Int,
    val headroom: Float,
)

data class ArKernelThermalSummary(
    val sampleCount: Int,
    val monotonicViolations: Int,
    val minStatus: Int,
    val maxStatus: Int,
    val minHeadroom: Float,
    val maxHeadroom: Float,
) {
    val structuralPass: Boolean
        get() = sampleCount >= 2 && monotonicViolations == 0 &&
            minHeadroom >= 0f && maxHeadroom >= minHeadroom
}

data class ArKernelStorageSummary(
    val byteCount: Int,
    val crc32Hex: String,
    val roundTripMatch: Boolean,
) {
    val structuralPass: Boolean
        get() = byteCount > 0 && roundTripMatch
}

data class ArKernelComputeSelfTestSummary(
    val accepted: Boolean,
    val byteIdentical: Boolean,
    val eventCount: Int,
) {
    val structuralPass: Boolean
        get() = accepted && byteIdentical && eventCount >= 2
}

/**
 * Pure, model-independent structural metrics for a host-owned physical
 * self-test run. These functions measure only self-consistency of locally
 * observed streams: finite values, monotonic timestamps, bounded gaps, byte
 * round trips and deterministic core encode/parse. They never compare against
 * a device model, external target, rig or physical ground truth, never retain
 * pixels/poses/trajectories, and never assert an assessed quality grade.
 */
object ArKernelPhysicalSelfTestMetrics {
    /** ASCII-only opaque token; no whitespace and no sensitive free text. */
    fun opaqueToken(raw: String): String {
        val mapped = raw.lowercase(Locale.ROOT).map { char ->
            when {
                char in 'a'..'z' || char in '0'..'9' || char == '.' ||
                    char == '-' || char == ':' -> char
                else -> '.'
            }
        }.joinToString("")
        val collapsed = mapped.replace(Regex("\\.+"), ".").trim('.', '-', ':')
        return collapsed.take(128).ifEmpty { "unknown" }
    }

    fun crc32Hex(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return String.format(Locale.US, "%08x", crc.value)
    }

    fun summarizeAxis(
        label: String,
        samples: List<ArKernelAxisSample>,
        expectedMinSamples: Int,
    ): ArKernelAxisSummary {
        require(expectedMinSamples >= 0) { "Invalid expected minimum sample count" }
        var monotonicViolations = 0
        var nonFiniteValues = 0
        var maxGapNs = 0L
        var previousTimestamp: Long? = null
        val sums = DoubleArray(3)
        val sumSquares = DoubleArray(3)
        val finiteCounts = IntArray(3)
        for (sample in samples) {
            val previous = previousTimestamp
            if (previous != null) {
                if (sample.timestampNs <= previous) monotonicViolations++
                val gap = sample.timestampNs - previous
                if (gap > maxGapNs) maxGapNs = gap
            }
            previousTimestamp = sample.timestampNs
            val values = floatArrayOf(sample.x, sample.y, sample.z)
            for (index in 0..2) {
                val value = values[index].toDouble()
                if (value.isNaN() || value.isInfinite()) {
                    nonFiniteValues++
                    continue
                }
                sums[index] += value
                sumSquares[index] += value * value
                finiteCounts[index]++
            }
        }
        val means = (0..2).map { index ->
            if (finiteCounts[index] == 0) 0.0 else sums[index] / finiteCounts[index]
        }
        val stdDevs = (0..2).map { index ->
            if (finiteCounts[index] == 0) 0.0 else {
                val variance = sumSquares[index] / finiteCounts[index] -
                    means[index] * means[index]
                sqrt(variance.coerceAtLeast(0.0))
            }
        }
        return ArKernelAxisSummary(
            label = label,
            sampleCount = samples.size,
            expectedMinSamples = expectedMinSamples,
            monotonicViolations = monotonicViolations,
            nonFiniteValues = nonFiniteValues,
            maxGapNs = maxGapNs,
            meanX = means[0],
            meanY = means[1],
            meanZ = means[2],
            stdDevX = stdDevs[0],
            stdDevY = stdDevs[1],
            stdDevZ = stdDevs[2],
        )
    }

    fun summarizeCaptures(
        timestampsNs: List<Long>,
        exposuresNs: List<Long>,
        isos: List<Int>,
        focusDistances: List<Float>,
        expectedMinFrames: Int,
        errorCount: Int,
        disconnectCount: Int,
    ): ArKernelCaptureSummary {
        require(expectedMinFrames >= 0) { "Invalid expected minimum frame count" }
        var monotonicViolations = 0
        var maxGapNs = 0L
        var previousTimestamp: Long? = null
        for (timestamp in timestampsNs) {
            val previous = previousTimestamp
            if (previous != null) {
                if (timestamp <= previous) monotonicViolations++
                val gap = timestamp - previous
                if (gap > maxGapNs) maxGapNs = gap
            }
            previousTimestamp = timestamp
        }
        val nonFiniteValues = exposuresNs.count { it < 0L } +
            isos.count { it < 0 } +
            focusDistances.count { it.isNaN() || it.isInfinite() }
        val validExposures = exposuresNs.filter { it >= 0L }
        val validIsos = isos.filter { it >= 0 }
        val validFocus = focusDistances.filter {
            !it.isNaN() && !it.isInfinite()
        }
        return ArKernelCaptureSummary(
            frameCount = timestampsNs.size,
            expectedMinFrames = expectedMinFrames,
            monotonicViolations = monotonicViolations,
            nonFiniteValues = nonFiniteValues,
            maxGapNs = maxGapNs,
            minExposureNs = validExposures.minOrNull() ?: 0L,
            maxExposureNs = validExposures.maxOrNull() ?: 0L,
            minIso = validIsos.minOrNull() ?: 0,
            maxIso = validIsos.maxOrNull() ?: 0,
            minFocusDistance = validFocus.minOrNull() ?: 0f,
            maxFocusDistance = validFocus.maxOrNull() ?: 0f,
            errorCount = errorCount,
            disconnectCount = disconnectCount,
        )
    }

    fun summarizeThermal(
        samples: List<ArKernelThermalSample>,
    ): ArKernelThermalSummary {
        var monotonicViolations = 0
        var previousTimestamp: Long? = null
        for (sample in samples) {
            val previous = previousTimestamp
            if (previous != null && sample.timestampNs <= previous) {
                monotonicViolations++
            }
            previousTimestamp = sample.timestampNs
        }
        val validHeadrooms = samples.map { it.headroom }.filter {
            !it.isNaN() && !it.isInfinite() && it >= 0f
        }
        return ArKernelThermalSummary(
            sampleCount = samples.size,
            monotonicViolations = monotonicViolations,
            minStatus = samples.minOfOrNull { it.status } ?: 0,
            maxStatus = samples.maxOfOrNull { it.status } ?: 0,
            minHeadroom = validHeadrooms.minOrNull() ?: 0f,
            maxHeadroom = validHeadrooms.maxOrNull() ?: 0f,
        )
    }

    fun storageRoundTrip(
        written: ByteArray,
        readBack: ByteArray,
    ): ArKernelStorageSummary {
        val crc = crc32Hex(written)
        return ArKernelStorageSummary(
            byteCount = written.size,
            crc32Hex = crc,
            roundTripMatch = written.contentEquals(readBack) &&
                crc == crc32Hex(readBack),
        )
    }

    /** Deterministic on-device check of the core text contracts. */
    fun computeSelfTest(): ArKernelComputeSelfTestSummary {
        val events = listOf(
            ArKernelFieldDiagnosticsLogEvent(
                0, 0L,
                ArKernelFieldDiagnosticsLogEventKind.RUN_START,
                "self.test", null, "compute",
            ),
            ArKernelFieldDiagnosticsLogEvent(
                1, 1_000_000L,
                ArKernelFieldDiagnosticsLogEventKind.COMPUTE_SELFTEST,
                "encode", 1L, "unverified",
            ),
            ArKernelFieldDiagnosticsLogEvent(
                2, 2_000_000L,
                ArKernelFieldDiagnosticsLogEventKind.RUN_END,
                null, 3L, null,
            ),
        )
        val record = ArKernelFieldDiagnosticsLogRecord(
            "session.self", "run.self", "self.test.v1", events)
        val text = ArKernelFieldDiagnosticsRunLog.encode(record)
        val parsed = ArKernelFieldDiagnosticsRunLog.parse(text)
        val reencoded = parsed.record?.let {
            ArKernelFieldDiagnosticsRunLog.encode(it)
        } ?: ""
        return ArKernelComputeSelfTestSummary(
            accepted = parsed.accepted,
            byteIdentical = reencoded == text,
            eventCount = events.size,
        )
    }

    fun axisToken(summary: ArKernelAxisSummary): String = opaqueToken(
        "${summary.label}.n.${summary.sampleCount}.mono." +
            "${summary.monotonicViolations}.finite.${summary.nonFiniteValues}." +
            "gapms.${summary.maxGapNs / 1_000_000L}.mean." +
            "${fmt(summary.meanX)}.${fmt(summary.meanY)}.${fmt(summary.meanZ)}." +
            "sd.${fmt(summary.stdDevX)}.${fmt(summary.stdDevY)}." +
            "${fmt(summary.stdDevZ)}.pass.${passFlag(summary.structuralPass)}"
    )

    fun captureToken(summary: ArKernelCaptureSummary): String = opaqueToken(
        "frames.${summary.frameCount}.mono.${summary.monotonicViolations}." +
            "finite.${summary.nonFiniteValues}.gapms." +
            "${summary.maxGapNs / 1_000_000L}.expns." +
            "${summary.minExposureNs}.${summary.maxExposureNs}.iso." +
            "${summary.minIso}.${summary.maxIso}.focus." +
            "${fmt(summary.minFocusDistance.toDouble())}." +
            "${fmt(summary.maxFocusDistance.toDouble())}.err." +
            "${summary.errorCount}.dis.${summary.disconnectCount}.pass." +
            passFlag(summary.structuralPass)
    )

    fun thermalToken(summary: ArKernelThermalSummary): String = opaqueToken(
        "thermal.n.${summary.sampleCount}.mono." +
            "${summary.monotonicViolations}.status." +
            "${summary.minStatus}.${summary.maxStatus}.headroom." +
            "${fmt(summary.minHeadroom.toDouble())}." +
            "${fmt(summary.maxHeadroom.toDouble())}.pass." +
            passFlag(summary.structuralPass)
    )

    fun storageToken(summary: ArKernelStorageSummary): String = opaqueToken(
        "storage.bytes.${summary.byteCount}.crc.${summary.crc32Hex}." +
            "match.${passFlag(summary.roundTripMatch)}.pass." +
            passFlag(summary.structuralPass)
    )

    fun computeToken(summary: ArKernelComputeSelfTestSummary): String =
        opaqueToken(
            "compute.accepted.${passFlag(summary.accepted)}.identical." +
                "${passFlag(summary.byteIdentical)}.events." +
                "${summary.eventCount}.pass." +
                passFlag(summary.structuralPass)
        )

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.4f", value)

    private fun passFlag(value: Boolean): Int = if (value) 1 else 0

    fun tokenPattern(): Regex = SELF_TEST_TOKEN
}
