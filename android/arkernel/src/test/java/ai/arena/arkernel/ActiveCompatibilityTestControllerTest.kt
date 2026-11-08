package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCompatibilityTestControllerTest {
    @Test
    fun timeoutFinishesEmptyProbeAndCallsBackOnce() {
        val callbackCount = AtomicInteger()
        val completed = CountDownLatch(1)
        val probe = ActiveDeviceCompatibilityProbe(staticReport(), true, true)
        val controller = ActiveCompatibilityTestController(probe, 20) {
            callbackCount.incrementAndGet()
            completed.countDown()
        }
        controller.start()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(probe.isFinished)
        assertEquals(1, callbackCount.get())
        controller.finishNow()
        assertEquals(1, callbackCount.get())
        controller.close()
    }

    @Test
    fun manualFinishIsTerminalBeforeLongTimeout() {
        val callbackCount = AtomicInteger()
        val probe = ActiveDeviceCompatibilityProbe(staticReport(), true, true)
        val controller = ActiveCompatibilityTestController(probe, 60_000) {
            callbackCount.incrementAndGet()
        }
        controller.start()
        val first = controller.finishNow()
        val second = controller.finishNow()

        assertEquals(first, second)
        assertEquals(1, callbackCount.get())
        controller.close()
    }

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
}
