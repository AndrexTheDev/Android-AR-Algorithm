package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedMotionTestControllerTest {
    @Test
    fun boundedSchedulePublishesEveryConfiguredInstructionAndFinishesOnce() {
        val states = Collections.synchronizedList(mutableListOf<GuidedMotionGuidanceState>())
        val callbackCount = AtomicInteger()
        val completed = CountDownLatch(1)
        val controller = GuidedMotionTestController(emptyProbe(),
            GuidedMotionTestConfig(15, 15, 15, 15),
            onGuidanceChanged = { states += it },
            onCompleted = { callbackCount.incrementAndGet(); completed.countDown() })

        controller.start()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(controller.isFinished)
        assertEquals(GuidedMotionGuidanceState.COMPLETED, controller.currentGuidance)
        assertEquals(listOf(GuidedMotionGuidanceState.HOLD_STILL,
            GuidedMotionGuidanceState.ROTATE_MULTI_AXIS,
            GuidedMotionGuidanceState.TRANSLATE_MULTI_DIRECTION,
            GuidedMotionGuidanceState.MEASURE_KNOWN_DISTANCE,
            GuidedMotionGuidanceState.COMPLETED), states.toList())
        assertEquals(1, callbackCount.get())
        controller.finishNow()
        assertEquals(1, callbackCount.get())
        controller.close()
    }

    @Test
    fun manualFinishIsTerminalAndOptionalTargetStageIsNotInvented() {
        val states = mutableListOf<GuidedMotionGuidanceState>()
        val callbackCount = AtomicInteger()
        val controller = GuidedMotionTestController(emptyProbe(),
            GuidedMotionTestConfig(60_000, 60_000, 60_000, 0),
            onGuidanceChanged = { states += it },
            onCompleted = { callbackCount.incrementAndGet() })
        controller.start()

        val first = controller.finishNow()
        val second = controller.finishNow()

        assertSame(first, second)
        assertFalse(first.runtimeMetricAdmissionGranted)
        assertEquals(listOf(GuidedMotionGuidanceState.HOLD_STILL,
            GuidedMotionGuidanceState.COMPLETED), states)
        assertEquals(1, callbackCount.get())
        controller.close()
    }

    @Test
    fun cannotFinishBeforeStart() {
        val controller = GuidedMotionTestController(emptyProbe())
        assertThrows(IllegalStateException::class.java) { controller.finishNow() }
        controller.close()
    }

    private fun emptyProbe(): GuidedMotionAdmissionProbe = GuidedMotionAdmissionProbe(
        ActiveDeviceCompatibilityProbe(staticReport(), true, true),
        Rotation(1.0, 0.0, 0.0, 0.0))

    private fun staticReport(): DeviceCompatibilityReport {
        val camera = CameraCapabilityFacts("0", emptyList(), 640, 480,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, 90,
            4000, 3000, 4000, 3000, 33_333_333, listOf(4f), listOf(0), listOf(0),
            listOf(0), true, true, true)
        val signature = DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = false, memoryClassMb = 256,
            buildFingerprintSha256 = "d".repeat(64), camera = camera,
            imu = ImuCapabilityFacts(true, true, 5_000, 5_000, 0, 0, 0.001f, 0.001f))
        return DeviceCompatibilityEvaluator.evaluate(signature)
    }
}
