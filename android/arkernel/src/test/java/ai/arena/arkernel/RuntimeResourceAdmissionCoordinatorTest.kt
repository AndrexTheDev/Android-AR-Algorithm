package ai.arena.arkernel

import android.content.ComponentCallbacks2
import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResourceAdmissionCoordinatorTest {
    @Test
    fun boundedLatencyEvidenceFlowsThroughTerminalOwnerGuard() {
        val static = staticReport()
        val active = activeReport()
        val initial = RuntimeResourcePolicy.evaluate(static, active)
        val owner = GuardedOwner(RuntimeExecutionPlan(initial, "a".repeat(64)))
        val coordinator = RuntimeResourceAdmissionCoordinator(static, active,
            "a".repeat(64), owner)

        val unchanged = coordinator.refresh()
        assertEquals(RuntimeExecutionPlanUpdate.DIAGNOSTICS_UPDATED,
            unchanged.executionResult.update)
        repeat(20) { assertTrue(coordinator.addFrameProcessingDuration(90_000_000L)) }
        val degraded = coordinator.refresh()

        assertEquals(RuntimeResourceTier.CAPTURE_ONLY, degraded.decision.tier)
        assertEquals(RuntimeExecutionPlanUpdate.RESTART_REQUIRED,
            degraded.executionResult.update)
        assertTrue(owner.controller.restartRequired)
    }

    @Test
    fun thermalUpgradeAfterTerminalDegradationCannotClearRestart() {
        val static = staticReport()
        val active = activeReport()
        val initial = RuntimeResourcePolicy.evaluate(static, active)
        val owner = GuardedOwner(RuntimeExecutionPlan(initial, null))
        val coordinator = RuntimeResourceAdmissionCoordinator(static, active, null, owner)

        coordinator.updateThermalState(InferenceThermalState.HOT)
        val hot = coordinator.refresh()
        coordinator.updateThermalState(InferenceThermalState.NORMAL)
        val cooled = coordinator.refresh()

        assertEquals(RuntimeResourceTier.CONSERVATIVE, hot.decision.tier)
        assertEquals(RuntimeExecutionPlanUpdate.RESTART_REQUIRED,
            hot.executionResult.update)
        assertEquals(hot.executionResult, cooled.executionResult)
    }

    @Test
    fun signedDisablePolicyFlowsThroughSameRestartGuard() {
        val static = staticReport()
        val active = activeReport()
        val initial = RuntimeResourcePolicy.evaluate(static, active)
        val owner = GuardedOwner(RuntimeExecutionPlan(initial, null))
        val policy = CompatibilitySafetyPolicy(1, 0, Long.MAX_VALUE, listOf(
            CompatibilitySafetyRule("semantic-kill-switch",
                hardwareLevels = listOf(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL),
                actions = listOf(
                    CompatibilitySafetyAction.DISABLE_SEMANTIC_INFERENCE))))
        val coordinator = RuntimeResourceAdmissionCoordinator(static, active, null, owner,
            safetyPolicy = policy, nowEpochMillis = { 1_500L })

        val refresh = coordinator.refresh()

        assertFalse(refresh.decision.semanticInferenceEnabled)
        assertEquals(RuntimeExecutionPlanUpdate.RESTART_REQUIRED,
            refresh.executionResult.update)
    }

    @Test
    fun mapsAndroidTrimLevelsWithoutTreatingUiHiddenAsPressure() {
        assertEquals(RuntimeMemoryPressure.CRITICAL,
            AndroidRuntimeResourceMonitor.mapTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(RuntimeMemoryPressure.MODERATE,
            AndroidRuntimeResourceMonitor.mapTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(RuntimeMemoryPressure.NORMAL,
            AndroidRuntimeResourceMonitor.mapTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(AndroidRuntimeResourceMonitor.mapTrimMemory(0) ==
            RuntimeMemoryPressure.CRITICAL)
    }

    private class GuardedOwner(initial: RuntimeExecutionPlan) : RuntimeExecutionPlanOwner {
        val controller = RuntimeExecutionPlanController(initial)
        override fun updateExecutionPlan(candidate: RuntimeExecutionPlan) =
            controller.update(candidate)
    }

    private fun staticReport(): DeviceCompatibilityReport {
        val camera = CameraCapabilityFacts("0", emptyList(), 640, 480,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, 90,
            4000, 3000, 4000, 3000, 33_333_333L, listOf(4f), listOf(0),
            listOf(0), listOf(0), true, true, true)
        val signature = DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = false,
            memoryClassMb = 768, buildFingerprintSha256 = "a".repeat(64),
            camera = camera, imu = ImuCapabilityFacts(true, true, 5_000, 5_000,
                0, 0, 0.001f, 0.001f))
        return DeviceCompatibilityEvaluator.evaluate(signature)
    }

    private fun activeReport(): ActiveCompatibilityReport {
        val camera = TimingStreamStatistics(100, true, 30.0, 33_333_333L,
            100_000L, 0.0, 1_000_000_000L, 4_000_000_000L)
        val imu = camera.copy(medianRateHz = 100.0, medianIntervalNs = 10_000_000L)
        return ActiveCompatibilityReport(ObservedCameraTimebase.REALTIME, camera, imu, imu,
            1.0, 3_000_000_000L, 10_000_000L, 10_000_000L,
            DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC, emptyList())
    }
}
