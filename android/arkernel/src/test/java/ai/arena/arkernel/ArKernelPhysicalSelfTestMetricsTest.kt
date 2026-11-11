package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelPhysicalSelfTestMetricsTest {
    @Test
    fun opaqueTokensAreAsciiAndBounded() {
        val metrics = ArKernelPhysicalSelfTestMetrics
        val token = metrics.opaqueToken("Redmi_Note 13 / API:35 / gyro!!!")
        assertTrue(metrics.tokenPattern().matches(token))
        assertFalse(token.contains(' '))
        assertFalse(token.contains('_'))
        assertTrue(token.length <= 128)
        assertEquals("unknown", metrics.opaqueToken("///"))
        assertEquals(
            "a.b.c",
            metrics.opaqueToken("A...B___C"),
        )
    }

    @Test
    fun axisSummaryChecksStructureAndVariance() {
        val samples = listOf(
            ArKernelAxisSample(0L, 1.0f, 10.0f, 100.0f),
            ArKernelAxisSample(1_000_000L, 2.0f, 11.0f, 101.0f),
            ArKernelAxisSample(2_000_000L, 3.0f, 12.0f, 102.0f),
        )
        val summary = ArKernelPhysicalSelfTestMetrics.summarizeAxis(
            "acc", samples, 3)
        assertEquals(3, summary.sampleCount)
        assertEquals(0, summary.monotonicViolations)
        assertEquals(0, summary.nonFiniteValues)
        assertEquals(1_000_000L, summary.maxGapNs)
        assertEquals(2.0, summary.meanX, 1e-9)
        assertEquals(11.0, summary.meanY, 1e-9)
        assertEquals(101.0, summary.meanZ, 1e-9)
        assertTrue(summary.stdDevX > 0.0)
        assertTrue(summary.structuralPass)
        val token = ArKernelPhysicalSelfTestMetrics.axisToken(summary)
        assertTrue(ArKernelPhysicalSelfTestMetrics.tokenPattern().matches(token))
        assertTrue(token.length <= 128)
    }

    @Test
    fun axisSummaryDetectsBrokenStructure() {
        val samples = listOf(
            ArKernelAxisSample(0L, 1.0f, 1.0f, 1.0f),
            ArKernelAxisSample(0L, Float.NaN, 1.0f, 1.0f),
            ArKernelAxisSample(2_000_000L, 1.0f, 1.0f, 1.0f),
        )
        val summary = ArKernelPhysicalSelfTestMetrics.summarizeAxis(
            "gyro", samples, 4)
        assertEquals(1, summary.monotonicViolations)
        assertEquals(1, summary.nonFiniteValues)
        assertFalse(summary.structuralPass)
    }

    @Test
    fun captureSummaryChecksFrameStructure() {
        val summary = ArKernelPhysicalSelfTestMetrics.summarizeCaptures(
            timestampsNs = listOf(0L, 1_000_000L, 2_000_000L),
            exposuresNs = listOf(1_000L, 2_000L, 3_000L),
            isos = listOf(100, 200, 300),
            focusDistances = listOf(1.0f, 2.0f, 3.0f),
            expectedMinFrames = 3,
            errorCount = 0,
            disconnectCount = 0,
        )
        assertEquals(3, summary.frameCount)
        assertEquals(0, summary.monotonicViolations)
        assertEquals(1_000L, summary.minExposureNs)
        assertEquals(3_000L, summary.maxExposureNs)
        assertEquals(100, summary.minIso)
        assertEquals(300, summary.maxIso)
        assertTrue(summary.structuralPass)
        val token = ArKernelPhysicalSelfTestMetrics.captureToken(summary)
        assertTrue(ArKernelPhysicalSelfTestMetrics.tokenPattern().matches(token))
        assertTrue(token.length <= 128)
    }

    @Test
    fun thermalSummaryChecksMonotonicSamples() {
        val summary = ArKernelPhysicalSelfTestMetrics.summarizeThermal(
            listOf(
                ArKernelThermalSample(0L, 0, 0.8f),
                ArKernelThermalSample(1_000_000L, 1, 0.7f),
                ArKernelThermalSample(2_000_000L, 1, 0.6f),
            ),
        )
        assertEquals(3, summary.sampleCount)
        assertEquals(0, summary.minStatus)
        assertEquals(1, summary.maxStatus)
        assertEquals(0.6f, summary.minHeadroom, 1e-6f)
        assertEquals(0.8f, summary.maxHeadroom, 1e-6f)
        assertTrue(summary.structuralPass)
        val token = ArKernelPhysicalSelfTestMetrics.thermalToken(summary)
        assertTrue(ArKernelPhysicalSelfTestMetrics.tokenPattern().matches(token))
    }

    @Test
    fun storageRoundTripIsByteExact() {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        val good = ArKernelPhysicalSelfTestMetrics.storageRoundTrip(
            bytes, bytes.copyOf())
        assertTrue(good.roundTripMatch)
        assertTrue(good.structuralPass)
        assertEquals(8, good.crc32Hex.length)
        val bad = ArKernelPhysicalSelfTestMetrics.storageRoundTrip(
            bytes, bytes.copyOf().also { it[0] = 99.toByte() })
        assertFalse(bad.roundTripMatch)
        assertFalse(bad.structuralPass)
        val token = ArKernelPhysicalSelfTestMetrics.storageToken(good)
        assertTrue(ArKernelPhysicalSelfTestMetrics.tokenPattern().matches(token))
    }

    @Test
    fun computeSelfTestIsDeterministicAndAccepted() {
        val summary = ArKernelPhysicalSelfTestMetrics.computeSelfTest()
        assertTrue(summary.accepted)
        assertTrue(summary.byteIdentical)
        assertEquals(3, summary.eventCount)
        assertTrue(summary.structuralPass)
        val token = ArKernelPhysicalSelfTestMetrics.computeToken(summary)
        assertTrue(ArKernelPhysicalSelfTestMetrics.tokenPattern().matches(token))
    }
}
