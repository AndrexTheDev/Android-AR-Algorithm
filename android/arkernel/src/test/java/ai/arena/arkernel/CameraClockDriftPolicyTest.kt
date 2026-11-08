package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraClockDriftPolicyTest {
    private val policy = CameraClockDriftPolicy(minimumSourceSpanNs = 10_000_000_000L,
        maximumAbsoluteDriftPartsPerMillion = 1_000.0,
        maximumRmsErrorNs = 50_000_000.0)

    @Test
    fun ignoresModelsBeforeMinimumTimeSpan() {
        assertNull(policy.evaluate(snapshot(scale = 1.01, rms = 500_000_000.0,
            span = 9_999_999_999L)))
    }

    @Test
    fun acceptsBoundedLongWindowFit() {
        val value = snapshot(scale = 1.0005, rms = 10_000_000.0,
            span = 20_000_000_000L)
        assertEquals(500.0, value.driftPartsPerMillion, 1e-7)
        assertNull(policy.evaluate(value))
    }

    @Test
    fun rejectsExcessiveDriftBeforeResidual() {
        assertEquals(CameraClockDriftFailureReason.DRIFT_EXCEEDED,
            policy.evaluate(snapshot(scale = 1.002, rms = 100_000_000.0,
                span = 20_000_000_000L)))
    }

    @Test
    fun rejectsNoisyCallbackFit() {
        assertEquals(CameraClockDriftFailureReason.RESIDUAL_EXCEEDED,
            policy.evaluate(snapshot(scale = 1.0001, rms = 60_000_000.0,
                span = 20_000_000_000L)))
    }

    private fun snapshot(scale: Double, rms: Double, span: Long) =
        ClockAlignmentSnapshot(scale, 2_000_000.0, rms, 32, span)
}
