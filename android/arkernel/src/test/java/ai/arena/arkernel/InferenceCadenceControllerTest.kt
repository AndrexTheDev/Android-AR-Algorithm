package ai.arena.arkernel

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceCadenceControllerTest {
    @Test
    fun adaptsToLatencyThermalsAndFailures() {
        val controller = InferenceCadenceController(InferenceCadenceOptions(
            minimumIntervalNs = 10,
            maximumIntervalNs = 10_000,
            targetDutyCycle = 0.5,
            latencyEwmaAlpha = 1.0,
        ))
        assertEquals(10, controller.recommendedIntervalNs())
        controller.record(100, true)
        assertEquals(200, controller.recommendedIntervalNs())
        controller.updateThermalState(InferenceThermalState.WARM)
        assertEquals(300, controller.recommendedIntervalNs())
        controller.updateThermalState(InferenceThermalState.HOT)
        assertEquals(500, controller.recommendedIntervalNs())
        controller.record(100, false)
        assertEquals(625, controller.recommendedIntervalNs())
        controller.updateThermalState(InferenceThermalState.CRITICAL)
        assertTrue(controller.isPaused())
        val snapshot = controller.snapshot()
        assertEquals(2, snapshot.sampleCount)
        assertEquals(100, snapshot.latencyEwmaNs)
        assertEquals(100, snapshot.latencyP95Ns)
        assertEquals(1, snapshot.consecutiveFailures)
        assertTrue(snapshot.paused)
    }

    @Test
    fun schedulerReportsThermalPauseWithoutCallingBackend() {
        val calls = AtomicInteger()
        val backend = fakeBackend(calls)
        val controller = InferenceCadenceController()
        controller.updateThermalState(InferenceThermalState.CRITICAL)
        BoundedInferenceScheduler(backend, minimumIntervalNs = 1,
            cadenceController = controller).use { scheduler ->
            assertEquals(InferenceSubmitResult.THERMAL_PAUSED,
                scheduler.submit(frame(1), {}))
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun ownedPipelineSchedulesAndClosesWithoutOptionalNativeDecorators() {
        val calls = AtomicInteger()
        val closed = AtomicInteger()
        val latch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val delegate = fakeBackend(calls, closed, closeLatch)
        ArKernelPerceptionPipeline.create(delegate, options = PerceptionPipelineOptions(
            minimumIntervalNs = 1,
            enableDetectionTracking = false,
            enableTemporalDepth = false,
            enableAndroidThermalMonitor = false,
        )).use { pipeline ->
            assertEquals(InferenceSubmitResult.ACCEPTED,
                pipeline.submit(frame(3), { latch.countDown() }))
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(1, pipeline.snapshot().cadence.sampleCount)
        }
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        assertEquals(1, closed.get())
    }

    @Test
    fun schedulerRecordsBackendLatencyBeforeCallback() {
        val calls = AtomicInteger()
        val resultLatch = CountDownLatch(1)
        val backend = fakeBackend(calls)
        val controller = InferenceCadenceController(InferenceCadenceOptions(
            minimumIntervalNs = 1,
            maximumIntervalNs = 1_000_000_000,
            targetDutyCycle = 1.0,
        ))
        BoundedInferenceScheduler(backend, minimumIntervalNs = 1,
            cadenceController = controller).use { scheduler ->
            assertEquals(InferenceSubmitResult.ACCEPTED,
                scheduler.submit(frame(2), { resultLatch.countDown() }))
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
            val snapshot = scheduler.cadenceSnapshot()
            assertNotNull(snapshot)
            assertEquals(1, snapshot!!.sampleCount)
            assertTrue(snapshot.latencyEwmaNs > 0)
            assertEquals(1, calls.get())
            assertFalse(snapshot.paused)
        }
    }

    private fun fakeBackend(calls: AtomicInteger,
                            closed: AtomicInteger = AtomicInteger(),
                            closeLatch: CountDownLatch? = null): OnDeviceInferenceBackend =
        object : OnDeviceInferenceBackend {
            override val manifest = ModelManifest(
                modelId = "test-model",
                modelVersion = "1",
                sha256 = "0".repeat(64),
                licenseSpdx = "Apache-2.0",
                sourceUrl = "https://example.com/model",
                tasks = setOf(InferenceTask.RELATIVE_DEPTH),
                inputWidth = 1,
                inputHeight = 1,
                inputChannels = 1,
                inputMean = listOf(0.0),
                inputStd = listOf(1.0),
                quantization = TensorQuantization.FLOAT32,
                modelByteSize = 1,
            )
            override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
                calls.incrementAndGet()
                return OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera)
            }
            override fun close() { closed.incrementAndGet(); closeLatch?.countDown() }
        }

    private fun frame(timestampNs: Long): PerceptionFrame {
        val calibration = CameraCalibration(1, 1, 1.0, 1.0, 0.0, 0.0)
        val pose = WorldPose(timestampNs, Rotation(1.0, 0.0, 0.0, 0.0),
            Vector3(0.0, 0.0, 0.0))
        return PerceptionFrame.wrap(timestampNs, calibration, pose,
            InferencePixelFormat.GRAY8, 1, ByteBuffer.allocateDirect(1))
    }
}
