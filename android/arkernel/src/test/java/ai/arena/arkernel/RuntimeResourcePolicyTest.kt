package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResourcePolicyTest {
    @Test
    fun selectsPerformanceOnlyFromStrongMeasuredEvidence() {
        val static = staticReport(1280, 720, memoryMb = 768)
        val decision = RuntimeResourcePolicy.evaluate(static, activeReport())
        assertEquals(RuntimeResourceTier.PERFORMANCE, decision.tier)
        assertTrue(decision.liveMappingEnabled)
        assertTrue(decision.denseDepthEnabled)
    }

    @Test
    fun lowMemoryAndOversizedStreamsDegradeDeterministically() {
        val conservative = RuntimeResourcePolicy.evaluate(
            staticReport(640, 480, memoryMb = 192, lowRam = true), activeReport())
        assertEquals(RuntimeResourceTier.CONSERVATIVE, conservative.tier)
        assertFalse(conservative.denseDepthEnabled)
        assertTrue(RuntimeResourceReason.LOW_RAM_DEVICE in conservative.reasons)

        val oversized = RuntimeResourcePolicy.evaluate(
            staticReport(1280, 720, memoryMb = 192, lowRam = true), activeReport())
        assertEquals(RuntimeResourceTier.CAPTURE_ONLY, oversized.tier)
        assertTrue(RuntimeResourceReason.STREAM_EXCEEDS_TIER_PIXEL_BUDGET in oversized.reasons)
    }

    @Test
    fun thermalOrMemoryCriticalPreservesCaptureFallback() {
        val telemetry = RuntimeResourceTelemetry(20, 20_000_000L,
            RuntimeMemoryPressure.CRITICAL, false)
        val decision = RuntimeResourcePolicy.evaluate(staticReport(640, 480, 512),
            activeReport(), telemetry, InferenceThermalState.CRITICAL)
        assertEquals(RuntimeResourceTier.CAPTURE_ONLY, decision.tier)
        assertEquals(10, decision.targetFramesPerSecond)
        assertFalse(decision.liveMappingEnabled)
    }

    @Test
    fun sessionControllerNeverUpgradesWithoutRestart() {
        val conservative = RuntimeResourcePolicy.evaluate(staticReport(640, 480, 192),
            activeReport())
        val controller = RuntimeResourceController(conservative)
        val performance = RuntimeResourcePolicy.evaluate(staticReport(640, 480, 768),
            activeReport())
        assertEquals(RuntimeResourceTier.CONSERVATIVE,
            controller.update(performance).tier)
        val capture = RuntimeResourcePolicy.evaluate(staticReport(640, 480, 768),
            activeReport(), thermalState = InferenceThermalState.CRITICAL)
        assertEquals(RuntimeResourceTier.CAPTURE_ONLY, controller.update(capture).tier)
    }

    @Test
    fun telemetryIsBoundedAndReportsP95() {
        val collector = RuntimeResourceTelemetryCollector()
        repeat(256) { index -> assertTrue(collector.addFrameProcessingDuration(
            1_000_000L + index * 1_000L)) }
        assertFalse(collector.addFrameProcessingDuration(2_000_000L))
        val snapshot = collector.snapshot()
        assertEquals(256, snapshot.processingSamples)
        assertTrue(snapshot.processingP95Ns >= 1_240_000L)
        assertTrue(snapshot.budgetExceeded)
    }

    private fun activeReport(): ActiveCompatibilityReport {
        val stats = TimingStreamStatistics(100, true, 30.0, 33_333_333L,
            100_000L, 0.0, 1_000_000_000L, 4_000_000_000L)
        val imu = stats.copy(medianRateHz = 100.0, medianIntervalNs = 10_000_000L)
        return ActiveCompatibilityReport(ObservedCameraTimebase.REALTIME, stats, imu, imu,
            1.0, 3_000_000_000L, 10_000_000L, 10_000_000L,
            DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC, emptyList())
    }

    private fun staticReport(width: Int, height: Int, memoryMb: Int,
        lowRam: Boolean = false): DeviceCompatibilityReport {
        val camera = CameraCapabilityFacts("0", emptyList(), width, height,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, 90,
            4000, 3000, 4000, 3000, 33_333_333L, listOf(4f), listOf(0),
            listOf(0), listOf(0), true, true, true)
        val signature = DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = lowRam,
            memoryClassMb = memoryMb, buildFingerprintSha256 = "a".repeat(64),
            camera = camera, imu = ImuCapabilityFacts(true, true, 5_000, 5_000,
                0, 0, 0.001f, 0.001f))
        return DeviceCompatibilityEvaluator.evaluate(signature)
    }
}
