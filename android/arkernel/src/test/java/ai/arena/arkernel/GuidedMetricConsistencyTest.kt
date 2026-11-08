package ai.arena.arkernel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedMetricConsistencyTest {
    @Test
    fun separatesRuntimeConsistencyFromPhysicalDimensionalEvidence() {
        val runtimeOnly = GuidedMetricConsistencyProbe()
        feedHealthyRuntime(runtimeOnly)
        val runtimeReport = runtimeOnly.finish()
        assertTrue(runtimeReport.runtimeReasons.toString(), runtimeReport.runtimeConsistent)
        assertFalse(runtimeReport.dimensionallyValidated)
        assertTrue(MetricTargetReason.INSUFFICIENT_KNOWN_DISTANCE_OBSERVATIONS in
            runtimeReport.targetReasons)

        val validated = GuidedMetricConsistencyProbe()
        feedHealthyRuntime(validated)
        assertTrue(validated.addKnownDistance(0.501, 0.500, 0.001))
        assertTrue(validated.addKnownDistance(1.002, 1.000, 0.001))
        assertTrue(validated.addKnownDistance(1.499, 1.500, 0.001))
        val validatedReport = validated.finish()
        assertTrue(validatedReport.runtimeConsistent)
        assertTrue(validatedReport.targetReasons.toString(), validatedReport.dimensionallyValidated)
    }

    @Test
    fun rejectsBadGravityDirectionAgreementAndTargetScale() {
        val probe = GuidedMetricConsistencyProbe()
        repeat(100) { index ->
            probe.addImuSample(Vector3(0.0, 0.0, 8.8 + (index % 2) * 0.4),
                Vector3(0.04, 0.0, 0.0))
        }
        repeat(16) {
            probe.addVisualFusion(VisualFusionSnapshot(true, false, false,
                0.0, 0.03, 0.2))
        }
        repeat(3) {
            probe.addKnownDistance(1.10, 1.00, 0.001)
        }

        val report = probe.finish()
        assertTrue(MetricRuntimeReason.GRAVITY_MAGNITUDE_INVALID in report.runtimeReasons)
        assertTrue(MetricRuntimeReason.STATIONARY_GYROSCOPE_MOTION_HIGH in report.runtimeReasons)
        assertTrue(MetricRuntimeReason.VISUAL_INERTIAL_DIRECTION_DISAGREES in
            report.runtimeReasons)
        assertTrue(MetricRuntimeReason.VISUAL_INERTIAL_SCALE_ACCEPTANCE_LOW in
            report.runtimeReasons)
        assertTrue(MetricTargetReason.KNOWN_DISTANCE_ERROR_TOO_HIGH in report.targetReasons)
    }

    private fun feedHealthyRuntime(probe: GuidedMetricConsistencyProbe) {
        repeat(100) { index ->
            val delta = (index % 5 - 2) * 0.01
            assertTrue(probe.addImuSample(Vector3(0.0, 0.0, 9.80665 + delta),
                Vector3(0.002, 0.001, 0.0)))
        }
        repeat(16) { index ->
            assertTrue(probe.addVisualFusion(VisualFusionSnapshot(true, true,
                index != 0, 0.02, 0.021, 0.92 + (index % 3) * 0.01)))
        }
    }
}
