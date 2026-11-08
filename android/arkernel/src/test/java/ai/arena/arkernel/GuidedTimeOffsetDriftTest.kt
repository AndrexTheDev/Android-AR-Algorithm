package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedTimeOffsetDriftTest {
    @Test
    fun estimatesRobustDriftAcrossAcceptedWindows() {
        val probe = GuidedTimeOffsetDriftProbe()
        val origin = 10_000_000_000L
        val driftPpm = 120.0
        repeat(6) { index ->
            val elapsed = index * 20_000_000_000L
            val offset = 4_000_000L + (driftPpm * elapsed / 1_000_000.0).toLong()
            assertTrue(probe.addWindow(origin + elapsed, offsetReport(offset, 200_000L)))
        }

        val report = probe.finish()
        assertTrue(report.reasons.toString(), report.usable)
        assertEquals(6, report.acceptedWindows)
        assertEquals(120.0, requireNotNull(report.driftPpm), 1e-9)
        assertTrue(requireNotNull(report.driftUncertaintyPpm) < 10.0)
        assertTrue(requireNotNull(report.residualRmseNs) < 1.0)
        assertFalse(probe.addWindow(origin + 200_000_000_000L,
            offsetReport(1_000_000L, 200_000L)))
    }

    @Test
    fun rejectsNoisyOffsetsAndExcessiveDrift() {
        val noisy = GuidedTimeOffsetDriftProbe(TimeOffsetDriftConfig(
            maximumResidualRmseNs = 1_000_000.0))
        val origin = 10_000_000_000L
        val offsets = listOf(0L, 8_000_000L, -7_000_000L, 9_000_000L, -8_000_000L)
        offsets.forEachIndexed { index, offset ->
            noisy.addWindow(origin + index * 20_000_000_000L,
                offsetReport(offset, 200_000L))
        }
        assertTrue(TimeOffsetDriftReason.OFFSET_RESIDUAL_TOO_HIGH in noisy.finish().reasons)

        val excessive = GuidedTimeOffsetDriftProbe()
        repeat(5) { index ->
            val elapsed = index * 20_000_000_000L
            excessive.addWindow(origin + elapsed,
                offsetReport((2_000.0 * elapsed / 1_000_000.0).toLong(), 200_000L))
        }
        assertTrue(TimeOffsetDriftReason.ABSOLUTE_DRIFT_TOO_HIGH in excessive.finish().reasons)
    }

    @Test
    fun ignoresRejectedWindowsButRequiresEnoughAcceptedEvidence() {
        val probe = GuidedTimeOffsetDriftProbe()
        val rejected = TimeOffsetObservabilityReport(10, 2, 100_000_000L,
            null, null, null, 0.2, 0.0,
            listOf(TimeOffsetObservabilityReason.CORRELATION_TOO_WEAK))
        assertFalse(probe.addWindow(10_000_000_000L, rejected))
        assertFalse(probe.addWindow(20_000_000_000L,
            offsetReport(0L, 20_000_000L)))

        val report = probe.finish()
        assertEquals(0, report.acceptedWindows)
        assertEquals(2, report.rejectedWindows)
        assertTrue(TimeOffsetDriftReason.INSUFFICIENT_ACCEPTED_WINDOWS in report.reasons)
    }

    private fun offsetReport(offsetNs: Long, uncertaintyNs: Long) =
        TimeOffsetObservabilityReport(500, 100, 3_000_000_000L,
            offsetNs, uncertaintyNs,
            uncertaintyNs.toDouble() * uncertaintyNs / 3.0,
            0.98, 0.05, emptyList())
}
