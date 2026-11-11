package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelGuidedMotionHostSessionTest {
    @Test
    fun fullGuidedSequencePublishesStrictLocalizedStatesAndTerminalGrantedReport() {
        val states = mutableListOf<ArKernelGuidedMotionViewState>()
        val actions = mutableListOf<ArKernelGuidedMotionHostAction>()
        val session = ArKernelGuidedMotionHostSession(Locale.GERMAN,
            onViewStateChanged = { states += it },
            onHostAction = { actions += it })

        val initial = session.currentState()
        assertEquals(ArKernelGuidedMotionSessionPhase.NOT_STARTED, initial.phase)
        assertEquals(0, initial.runGeneration)
        assertTrue(initial.requiresFreshProbe)
        assertFalse(initial.cancelAvailable)
        assertFalse(initial.finishAvailable)
        assertFalse(initial.retryAvailable)
        assertTrue(states.isEmpty())

        val running = session.start()
        assertEquals(ArKernelGuidedMotionSessionPhase.RUNNING, running.phase)
        assertTrue(running.cancelAvailable)
        assertTrue(running.finishAvailable)
        assertFalse(running.retryAvailable)
        assertEquals(listOf(ArKernelGuidedMotionHostAction.START), actions)

        for (state in listOf(
            GuidedMotionGuidanceState.HOLD_STILL,
            GuidedMotionGuidanceState.ROTATE_MULTI_AXIS,
            GuidedMotionGuidanceState.TRANSLATE_MULTI_DIRECTION,
            GuidedMotionGuidanceState.MEASURE_KNOWN_DISTANCE,
        )) {
            val published = session.publishGuidance(state)
            assertEquals(state, published.guidance)
            assertTrue(published.cancelAvailable)
            assertTrue(published.finishAvailable)
        }

        val finish = session.requestFinish()
        assertFalse(finish.cancelAvailable)
        assertFalse(finish.finishAvailable)
        assertEquals(listOf(ArKernelGuidedMotionHostAction.START,
            ArKernelGuidedMotionHostAction.FINISH), actions)

        val completed = session.publishCompletion(grantedReport())
        assertEquals(ArKernelGuidedMotionSessionPhase.COMPLETED, completed.phase)
        assertEquals(GuidedMotionGuidanceState.COMPLETED, completed.guidance)
        assertTrue(completed.terminal)
        assertTrue(completed.requiresFreshProbe)
        assertFalse(completed.retryAvailable)
        assertFalse(completed.cancelAvailable)
        assertFalse(completed.finishAvailable)
        assertEquals("unverified", completed.qualityGrade)
        assertTrue(completed.contentDescription.contains("ungeprüft"))
        assertEquals(completed, states.last())
        assertEquals(7, states.size)
        assertEquals(listOf(ArKernelGuidedMotionHostAction.START,
            ArKernelGuidedMotionHostAction.FINISH), actions)

        assertThrows(IllegalStateException::class.java) { session.start() }
        assertThrows(IllegalStateException::class.java) {
            session.publishCompletion(grantedReport())
        }
        assertThrows(IllegalStateException::class.java) { session.requestFinish() }
        assertThrows(IllegalStateException::class.java) { session.requestCancel() }
        assertThrows(IllegalStateException::class.java) { session.requestRetry() }
        session.close()
        assertTrue(session.isClosed)
    }

    @Test
    fun blockedReportAllowsRetryOnlyWithFreshGenerationAndExplicitStart() {
        val actions = mutableListOf<ArKernelGuidedMotionHostAction>()
        val session = ArKernelGuidedMotionHostSession(Locale.ENGLISH,
            onHostAction = { actions += it })
        session.start()
        for (state in listOf(
            GuidedMotionGuidanceState.HOLD_STILL,
            GuidedMotionGuidanceState.ROTATE_MULTI_AXIS,
            GuidedMotionGuidanceState.TRANSLATE_MULTI_DIRECTION,
        )) {
            session.publishGuidance(state)
        }

        val completed = session.publishCompletion(blockedReport())
        assertTrue(completed.retryAvailable)
        assertTrue(completed.contentDescription.contains("Quality: unverified"))
        assertTrue(completed.contentDescription.contains(
            "Active compatibility check failed"))
        assertEquals(0, completed.runGeneration)

        val retried = session.requestRetry()
        assertEquals(1, retried.runGeneration)
        assertEquals(ArKernelGuidedMotionSessionPhase.NOT_STARTED, retried.phase)
        assertEquals(GuidedMotionGuidanceState.NOT_STARTED, retried.guidance)
        assertTrue(retried.requiresFreshProbe)
        assertFalse(retried.retryAvailable)
        assertEquals(listOf(ArKernelGuidedMotionHostAction.START,
            ArKernelGuidedMotionHostAction.RETRY), actions)

        val restarted = session.start()
        assertEquals(1, restarted.runGeneration)
        assertEquals(listOf(ArKernelGuidedMotionHostAction.START,
            ArKernelGuidedMotionHostAction.RETRY,
            ArKernelGuidedMotionHostAction.START), actions)
        assertThrows(IllegalStateException::class.java) {
            session.publishGuidance(
                GuidedMotionGuidanceState.TRANSLATE_MULTI_DIRECTION)
        }
        session.close()
    }

    @Test
    fun cancelIsTerminalAndRetryRequiresExplicitStart() {
        val actions = mutableListOf<ArKernelGuidedMotionHostAction>()
        val session = ArKernelGuidedMotionHostSession(Locale.GERMAN,
            onHostAction = { actions += it })
        session.start()
        session.publishGuidance(GuidedMotionGuidanceState.HOLD_STILL)

        val cancelled = session.requestCancel()
        assertEquals(ArKernelGuidedMotionSessionPhase.CANCELLED, cancelled.phase)
        assertEquals(GuidedMotionGuidanceState.HOLD_STILL, cancelled.guidance)
        assertTrue(cancelled.terminal)
        assertTrue(cancelled.retryAvailable)
        assertFalse(cancelled.cancelAvailable)
        assertFalse(cancelled.finishAvailable)
        assertTrue(cancelled.prompt.message.contains("abgebrochen"))
        assertTrue(cancelled.contentDescription.contains("Erneut versuchen"))
        assertEquals(listOf(ArKernelGuidedMotionHostAction.START,
            ArKernelGuidedMotionHostAction.CANCEL), actions)

        assertThrows(IllegalStateException::class.java) {
            session.publishGuidance(GuidedMotionGuidanceState.ROTATE_MULTI_AXIS)
        }
        assertThrows(IllegalStateException::class.java) {
            session.publishCompletion(blockedReport())
        }
        assertThrows(IllegalStateException::class.java) { session.requestFinish() }
        assertEquals(ArKernelGuidedMotionSessionPhase.CANCELLED,
            session.currentState().phase)

        val retried = session.requestRetry()
        assertEquals(1, retried.runGeneration)
        assertEquals(ArKernelGuidedMotionSessionPhase.NOT_STARTED, retried.phase)
        assertEquals(listOf(ArKernelGuidedMotionHostAction.START,
            ArKernelGuidedMotionHostAction.CANCEL,
            ArKernelGuidedMotionHostAction.RETRY), actions)
        assertThrows(IllegalStateException::class.java) { session.requestRetry() }
        session.close()
    }

    @Test
    fun invalidGuidanceAndCloseLeaveStateUnchanged() {
        val states = mutableListOf<ArKernelGuidedMotionViewState>()
        val actions = mutableListOf<ArKernelGuidedMotionHostAction>()
        val session = ArKernelGuidedMotionHostSession(Locale.ENGLISH,
            onViewStateChanged = { states += it },
            onHostAction = { actions += it })
        val initial = session.currentState()

        assertThrows(IllegalStateException::class.java) {
            session.publishGuidance(GuidedMotionGuidanceState.HOLD_STILL)
        }
        assertEquals(initial, session.currentState())
        assertTrue(states.isEmpty())

        session.start()
        assertThrows(IllegalStateException::class.java) { session.start() }
        assertThrows(IllegalStateException::class.java) {
            session.publishGuidance(GuidedMotionGuidanceState.ROTATE_MULTI_AXIS)
        }
        assertThrows(IllegalStateException::class.java) {
            session.publishGuidance(GuidedMotionGuidanceState.COMPLETED)
        }
        assertThrows(IllegalStateException::class.java) {
            session.publishGuidance(GuidedMotionGuidanceState.NOT_STARTED)
        }
        assertEquals(GuidedMotionGuidanceState.NOT_STARTED,
            session.currentState().guidance)

        session.publishGuidance(GuidedMotionGuidanceState.HOLD_STILL)
        assertThrows(IllegalStateException::class.java) {
            session.publishGuidance(GuidedMotionGuidanceState.HOLD_STILL)
        }
        assertEquals(GuidedMotionGuidanceState.HOLD_STILL,
            session.currentState().guidance)
        assertEquals(2, states.size)
        assertEquals(listOf(ArKernelGuidedMotionHostAction.START), actions)

        session.close()
        assertTrue(session.isClosed)
        assertThrows(IllegalStateException::class.java) { session.requestCancel() }
        assertThrows(IllegalStateException::class.java) { session.requestRetry() }
        assertEquals(GuidedMotionGuidanceState.HOLD_STILL,
            session.currentState().guidance)
    }

    @Test
    fun everyPublishedStateKeepsUnverifiedAndNoCentimeterClaim() {
        val states = mutableListOf<ArKernelGuidedMotionViewState>()
        val session = ArKernelGuidedMotionHostSession(Locale.GERMAN,
            onViewStateChanged = { states += it })
        val fullSequence = listOf(
            GuidedMotionGuidanceState.HOLD_STILL,
            GuidedMotionGuidanceState.ROTATE_MULTI_AXIS,
            GuidedMotionGuidanceState.TRANSLATE_MULTI_DIRECTION,
            GuidedMotionGuidanceState.MEASURE_KNOWN_DISTANCE,
        )
        session.start()
        fullSequence.forEach { session.publishGuidance(it) }
        session.publishCompletion(blockedReport())
        session.requestRetry()
        session.start()
        fullSequence.take(3).forEach { session.publishGuidance(it) }
        session.requestCancel()

        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it.qualityGrade == "unverified" })
        assertTrue(states.all { it.contentDescription.contains("ungeprüft") })
        assertTrue(states.all {
            !it.prompt.message.contains("zentimetervalidiert", ignoreCase = true) &&
                !it.prompt.detail.contains("zentimetervalidiert", ignoreCase = true) &&
                !it.contentDescription.contains("zentimetervalidiert",
                    ignoreCase = true)
        })
        assertTrue(states.any {
            it.phase == ArKernelGuidedMotionSessionPhase.COMPLETED
        })
        assertTrue(states.any {
            it.phase == ArKernelGuidedMotionSessionPhase.CANCELLED
        })
        assertTrue(states.any { it.runGeneration == 1 })
        session.close()
    }

    private fun grantedReport(): GuidedMotionAdmissionReport =
        GuidedMotionAdmissionReport(
            acceptedActive(), acceptedRotation(), acceptedOffset(),
            healthyMetric(listOf(MetricTargetReason.INSUFFICIENT_KNOWN_DISTANCE_OBSERVATIONS)),
            emptyList())

    private fun blockedReport(): GuidedMotionAdmissionReport =
        GuidedMotionAdmissionProbe(
            ActiveDeviceCompatibilityProbe(staticReport(), true, true),
            Rotation(1.0, 0.0, 0.0, 0.0)).finish()

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
            4000, 3000, 4000, 3000, 33_333_333L, listOf(4f), listOf(0), listOf(0),
            listOf(0), true, true, true)
        val signature = DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = false,
            memoryClassMb = 256, buildFingerprintSha256 = "c".repeat(64),
            camera = camera, imu = ImuCapabilityFacts(true, true, 5_000, 5_000,
                0, 0, 0.001f, 0.001f))
        return DeviceCompatibilityEvaluator.evaluate(signature)
    }
}
