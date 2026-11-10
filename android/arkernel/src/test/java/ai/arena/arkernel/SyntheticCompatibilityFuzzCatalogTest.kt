package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticCompatibilityFuzzCatalogTest {
    @Test
    fun seededCatalogIsDeterministicUniqueAndBounded() {
        val first = SyntheticCompatibilityFuzzCatalog.seeded(1234L, count = 48)
        val second = SyntheticCompatibilityFuzzCatalog.seeded(1234L, count = 48)
        assertEquals(48, first.size)
        assertEquals(first.map { it.scenarioId }, second.map { it.scenarioId })
        assertEquals(first.map { it.faults }, second.map { it.faults })
        assertEquals(first.map { it.cameraSamples }, second.map { it.cameraSamples })
        assertEquals(first.map { it.imuSamplesPerSensor },
            second.map { it.imuSamplesPerSensor })
        assertEquals(first.size, first.map { it.scenarioId }.distinct().size)
        assertTrue(first.all { it.faults.size <= 4 })
        assertTrue(first.all { it.cameraSamples in 20..84 })
        assertTrue(first.all { it.imuSamplesPerSensor in 50..349 })
        assertTrue(SyntheticCompatibilityFuzzCatalog.seeded(1235L, count = 48)
            .first().scenarioId != first.first().scenarioId)
    }

    @Test
    fun rejectsInvalidCatalogBudgets() {
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticCompatibilityFuzzCatalog.seeded(1L, count = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticCompatibilityFuzzCatalog.seeded(1L, count = 257)
        }
    }

    @Test
    fun seededRunsStayBoundedAndNeverPromoteFaultedEvidence() {
        for (scenario in SyntheticCompatibilityFuzzCatalog.seeded(7L, count = 64)) {
            val run = execute(scenario)
            assertTrue(run.offeredCameraSamples <= scenario.cameraSamples * 2)
            assertTrue(run.offeredImuSamples <= scenario.imuSamplesPerSensor * 2)
            if (scenario.faults.isNotEmpty()) {
                assertFalse("${scenario.scenarioId} unexpectedly promoted",
                    run.report.promotedTier ==
                        DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC)
            }
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
