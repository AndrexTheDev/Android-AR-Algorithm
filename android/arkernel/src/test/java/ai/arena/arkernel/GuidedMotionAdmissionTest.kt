package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedMotionAdmissionTest {
    @Test
    fun grantsRuntimeAdmissionWithoutInventingDimensionalValidation() {
        val runtimeMetric = healthyMetric(
            listOf(MetricTargetReason.INSUFFICIENT_KNOWN_DISTANCE_OBSERVATIONS))
        val report = GuidedMotionAdmissionReport(acceptedActive(), acceptedRotation(),
            acceptedOffset(), runtimeMetric, emptyList())

        assertTrue(report.runtimeMetricAdmissionGranted)
        assertFalse(report.dimensionallyValidated)

        val physicallyValidated = report.copy(metricConsistency = healthyMetric(emptyList()))
        assertTrue(physicallyValidated.runtimeMetricAdmissionGranted)
        assertTrue(physicallyValidated.dimensionallyValidated)
    }

    @Test
    fun exposesEveryFailedMeasuredCheckWithoutPartialAdmission() {
        val active = acceptedActive().copy(
            promotedTier = DeviceCompatibilityTier.DEGRADED_TRACKING,
            blockingReasons = listOf(ActiveCompatibilityReasonCode.CAMERA_FRAME_RATE_TOO_LOW))
        val rotation = acceptedRotation().copy(reasons =
            listOf(RotationObservabilityReason.INSUFFICIENT_GUIDED_MOTION))
        val offset = acceptedOffset().copy(visualToGyroscopeOffsetNs = null,
            offsetUncertaintyNs = null, offsetVarianceNsSquared = null,
            reasons = listOf(TimeOffsetObservabilityReason.CORRELATION_TOO_WEAK))
        val metric = healthyMetric(emptyList()).copy(runtimeReasons =
            listOf(MetricRuntimeReason.VISUAL_INERTIAL_DIRECTION_DISAGREES))
        val reasons = GuidedMotionAdmissionReason.entries
        val report = GuidedMotionAdmissionReport(active, rotation, offset, metric, reasons)

        assertFalse(report.runtimeMetricAdmissionGranted)
        assertFalse(report.dimensionallyValidated)
        assertEquals(reasons, report.blockingReasons)
    }

    @Test
    fun terminalProbeRejectsLaterObservationsAndReturnsSameReport() {
        val active = ActiveDeviceCompatibilityProbe(staticReport(), true, true)
        val probe = GuidedMotionAdmissionProbe(active, identity())

        val first = probe.finish()
        val second = probe.finish()

        assertSame(first, second)
        assertTrue(probe.isFinished)
        assertFalse(probe.runtimeMetricAdmissionGranted)
        assertEquals(GuidedMotionAdmissionReason.entries, first.blockingReasons)
        assertFalse(probe.addGyroscopeBody(1, Vector3(0.0, 0.0, 0.1)))
        assertFalse(probe.addImuSample(Vector3(0.0, 0.0, 9.8),
            Vector3(0.0, 0.0, 0.0)))
        assertFalse(probe.addKnownDistance(1.0, 1.0, 0.001))
    }

    private fun acceptedActive(): ActiveCompatibilityReport {
        val stats = TimingStreamStatistics(100, true, 30.0, 33_333_333L,
            100_000L, 0.0, 1_000_000_000L, 4_300_000_000L)
        val imu = stats.copy(medianRateHz = 200.0, medianIntervalNs = 5_000_000L)
        return ActiveCompatibilityReport(ObservedCameraTimebase.REALTIME, stats, imu, imu,
            1.0, 3_000_000_000L, 1_000_000L, 1_000_000L,
            DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC, emptyList())
    }

    private fun acceptedRotation() = RotationObservabilityReport(
        18, 0.54, 0.53, 0.98, 0.99, emptyList())

    private fun acceptedOffset() = TimeOffsetObservabilityReport(
        500, 100, 3_000_000_000L, 24_000_000L, 4_000_000L,
        5.0e12, 0.98, 0.05, emptyList())

    private fun healthyMetric(targetReasons: List<MetricTargetReason>) =
        MetricConsistencyReport(100, 9.80665, 0.02, 0.003,
            16, 15, 0.94, if (targetReasons.isEmpty()) 3 else 0,
            if (targetReasons.isEmpty()) 0.001 else 0.0,
            if (targetReasons.isEmpty()) 0.002 else 0.0,
            emptyList(), targetReasons)

    private fun staticReport(): DeviceCompatibilityReport {
        val camera = CameraCapabilityFacts("0", emptyList(), 640, 480,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, 90,
            4000, 3000, 4000, 3000, 33_333_333, listOf(4f), listOf(0), listOf(0),
            listOf(0), true, true, true)
        val signature = DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = false, memoryClassMb = 256,
            buildFingerprintSha256 = "c".repeat(64), camera = camera,
            imu = ImuCapabilityFacts(true, true, 5_000, 5_000, 0, 0, 0.001f, 0.001f))
        return DeviceCompatibilityEvaluator.evaluate(signature)
    }

    private fun identity() = Rotation(1.0, 0.0, 0.0, 0.0)
}
