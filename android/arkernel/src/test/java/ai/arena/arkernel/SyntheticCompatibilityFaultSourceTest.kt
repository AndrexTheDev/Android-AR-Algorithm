package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticCompatibilityFaultSourceTest {
    @Test
    fun healthySyntheticStreamEarnsRuntimeAdmission() {
        val run = execute(SyntheticCompatibilityScenarioCatalog.healthy())

        assertEquals(DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC,
            run.report.promotedTier)
        assertTrue(run.report.blockingReasons.isEmpty())
        assertEquals(run.offeredCameraSamples, run.acceptedCameraSamples)
        assertEquals(run.offeredImuSamples, run.acceptedImuSamples)
    }

    @Test
    fun everySingleFaultMapsToObservableAdmissionReason() {
        val expected = mapOf(
            SyntheticCompatibilityFault.CAMERA_TIMESTAMP_REGRESSION to
                ActiveCompatibilityReasonCode.NON_MONOTONIC_CAMERA_TIMESTAMPS,
            SyntheticCompatibilityFault.CAMERA_FRAME_GAPS to
                ActiveCompatibilityReasonCode.CAMERA_FRAME_GAPS_EXCESSIVE,
            SyntheticCompatibilityFault.CAPTURE_RESULT_DROPOUT to
                ActiveCompatibilityReasonCode.CAMERA_CAPTURE_TIMESTAMP_MISMATCH,
            SyntheticCompatibilityFault.ACCELEROMETER_DROPOUT to
                ActiveCompatibilityReasonCode.INSUFFICIENT_ACCELEROMETER_SAMPLES,
            SyntheticCompatibilityFault.GYROSCOPE_DROPOUT to
                ActiveCompatibilityReasonCode.INSUFFICIENT_GYROSCOPE_SAMPLES,
            SyntheticCompatibilityFault.ACCELEROMETER_TIMESTAMP_REGRESSION to
                ActiveCompatibilityReasonCode.NON_MONOTONIC_ACCELEROMETER_TIMESTAMPS,
            SyntheticCompatibilityFault.GYROSCOPE_TIMESTAMP_REGRESSION to
                ActiveCompatibilityReasonCode.NON_MONOTONIC_GYROSCOPE_TIMESTAMPS,
            SyntheticCompatibilityFault.IMU_CALLBACK_LATENCY to
                ActiveCompatibilityReasonCode.ACCELEROMETER_TIMESTAMP_DOMAIN_INVALID,
            SyntheticCompatibilityFault.CAMERA_AMBIGUOUS_TIMEBASE to
                ActiveCompatibilityReasonCode.CAMERA_TIMESTAMP_DOMAIN_NOT_REALTIME,
            SyntheticCompatibilityFault.CAMERA_IMU_WINDOWS_DISJOINT to
                ActiveCompatibilityReasonCode.CAMERA_IMU_WINDOWS_DO_NOT_OVERLAP,
        )

        for (scenario in SyntheticCompatibilityScenarioCatalog.singleFaults()) {
            val fault = scenario.faults.single()
            val report = execute(scenario).report
            assertTrue("$fault did not produce ${expected[fault]}: ${report.blockingReasons}",
                expected.getValue(fault) in report.blockingReasons)
            assertFalse("$fault unexpectedly promoted", report.blockingReasons.isEmpty())
            assertEquals(DeviceCompatibilityTier.DEGRADED_TRACKING, report.promotedTier)
        }
    }

    @Test
    fun pairwiseCatalogCoversEveryDistinctFaultPairExactlyOnce() {
        val faults = SyntheticCompatibilityFault.entries
        val scenarios = SyntheticCompatibilityScenarioCatalog.pairwiseFaults()

        assertEquals(faults.size * (faults.size - 1) / 2, scenarios.size)
        assertEquals(scenarios.size, scenarios.map { it.scenarioId }.distinct().size)
        assertTrue(scenarios.all { it.faults.size == 2 })
        for (first in faults.indices) for (second in first + 1 until faults.size)
            assertEquals(1, scenarios.count {
                it.faults == setOf(faults[first], faults[second])
            })
    }

    @Test
    fun allPairwiseRunsRemainBoundedAndNeverUpgradeFaultedEvidence() {
        for (scenario in SyntheticCompatibilityScenarioCatalog.pairwiseFaults()) {
            val run = execute(scenario)
            assertTrue(run.offeredCameraSamples <= scenario.cameraSamples * 2)
            assertTrue(run.offeredImuSamples <= scenario.imuSamplesPerSensor * 2)
            assertFalse("${scenario.scenarioId} unexpectedly promoted",
                run.report.promotedTier == DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC)
        }
    }

    private fun execute(scenario: SyntheticCompatibilityScenario): SyntheticCompatibilityRun {
        val probe = ActiveDeviceCompatibilityProbe(staticReport(), true, true)
        return SyntheticCompatibilityFaultSource(scenario).run(probe)
    }

    private fun staticReport(): DeviceCompatibilityReport {
        val camera = CameraCapabilityFacts("0", emptyList(), 640, 480,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, 90,
            4000, 3000, 4000, 3000, 33_333_333L, listOf(4f), listOf(0), listOf(0),
            listOf(0), true, true, true)
        val signature = DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = false,
            memoryClassMb = 512, buildFingerprintSha256 = "d".repeat(64),
            camera = camera, imu = ImuCapabilityFacts(true, true, 5_000, 5_000,
                0, 0, 0.001f, 0.001f))
        return DeviceCompatibilityEvaluator.evaluate(signature)
    }
}
