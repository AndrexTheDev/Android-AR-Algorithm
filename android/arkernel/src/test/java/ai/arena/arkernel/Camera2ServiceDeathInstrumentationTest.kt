package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2ServiceDeathInstrumentationTest {
    @Test
    fun classifiesRecognizedFrameworkCodesAndLeavesOrdinaryDeviceErrorsAlone() {
        val service = Camera2ServiceDeathInstrumentation.classifyStateCallbackError(
            Camera2ServiceDeathInstrumentation.STATE_ERROR_CAMERA_SERVICE)
        assertEquals(Camera2FrameworkErrorSource.STATE_CALLBACK, service!!.source)
        assertEquals(Camera2ServiceDeathReason.SERVICE_UNAVAILABLE, service.reason)
        assertEquals(Camera2LifecycleFailure.SERVICE_DEATH, service.lifecycleFailure)

        assertEquals(Camera2ServiceDeathReason.SERVICE_DISABLED,
            Camera2ServiceDeathInstrumentation.classifyStateCallbackError(
                Camera2ServiceDeathInstrumentation.STATE_ERROR_CAMERA_DISABLED)!!.reason)
        assertEquals(Camera2ServiceDeathReason.SERVICE_BUSY,
            Camera2ServiceDeathInstrumentation.classifyStateCallbackError(
                Camera2ServiceDeathInstrumentation.STATE_ERROR_CAMERA_IN_USE)!!.reason)
        assertEquals(Camera2ServiceDeathReason.SERVICE_BUSY,
            Camera2ServiceDeathInstrumentation.classifyStateCallbackError(
                Camera2ServiceDeathInstrumentation.STATE_ERROR_MAX_CAMERAS_IN_USE)!!.reason)
        assertNull(Camera2ServiceDeathInstrumentation.classifyStateCallbackError(4))
        assertNull(Camera2ServiceDeathInstrumentation.classifyStateCallbackError(999))

        assertEquals(Camera2ServiceDeathReason.SERVICE_DISCONNECTED,
            Camera2ServiceDeathInstrumentation.classifyAccessException(
                Camera2ServiceDeathInstrumentation.ACCESS_CAMERA_DISCONNECTED)!!.reason)
        assertEquals(Camera2LifecycleFailure.SERVICE_DEATH,
            Camera2ServiceDeathInstrumentation.classifyAccessException(
                Camera2ServiceDeathInstrumentation.ACCESS_MAX_CAMERAS_IN_USE)!!
                .lifecycleFailure)
        assertNull(Camera2ServiceDeathInstrumentation.classifyAccessException(3))
        assertNull(Camera2ServiceDeathInstrumentation.classifyAccessException(999))
    }

    @Test
    fun serviceDeathFailsFromEveryActiveStateAndReportsOnlyOnce() {
        listOf(
            Camera2LifecycleState.OPENING,
            Camera2LifecycleState.CONFIGURING,
            Camera2LifecycleState.STREAMING,
        ).forEach { target ->
            val machine = Camera2LifecycleStateMachine()
            val generation = machine.beginStart()
            if (target != Camera2LifecycleState.OPENING) {
                assertTrue(machine.deviceOpened(generation).accepted)
            }
            if (target == Camera2LifecycleState.STREAMING) {
                assertTrue(machine.sessionConfigured(generation).accepted)
            }
            val first = machine.fail(generation, Camera2LifecycleFailure.SERVICE_DEATH)
            assertTrue(first.accepted)
            assertTrue(first.shouldReportFailure)
            assertEquals(Camera2LifecycleFailure.SERVICE_DEATH, first.failure)
            assertEquals(Camera2LifecycleState.FAILED, machine.state)

            val second = machine.fail(generation, Camera2LifecycleFailure.DEVICE_ERROR)
            assertFalse(second.accepted)
            assertFalse(second.shouldReportFailure)
            assertFalse(machine.deviceOpened(generation).accepted)
            assertFalse(machine.sessionConfigured(generation).accepted)
        }
    }

    @Test
    fun seededFuzzReplayIsDeterministicAndInvariantSafe() {
        val first = Camera2ServiceDeathFuzzHarness.run(42L, sequences = 96,
            eventsPerSequence = 12)
        val second = Camera2ServiceDeathFuzzHarness.run(42L, sequences = 96,
            eventsPerSequence = 12)
        assertEquals(first, second)
        assertEquals(42L, first.seed)
        assertEquals(96, first.sequences)
        assertEquals(96 * 12, first.events)
        assertTrue("fuzz violations: ${first.violations}", first.violations.isEmpty())
        assertTrue(first.restarts > 0)
        assertTrue(first.rejectedStaleCallbacks > 0)
        assertTrue(first.acceptedServiceDeaths > 0)

        val different = Camera2ServiceDeathFuzzHarness.run(43L, sequences = 96,
            eventsPerSequence = 12)
        assertTrue(different.violations.isEmpty())
        assertEquals(43L, different.seed)
    }

    @Test
    fun rejectsUnboundedFuzzBudgets() {
        assertThrows(IllegalArgumentException::class.java) {
            Camera2ServiceDeathFuzzHarness.run(1L, sequences = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Camera2ServiceDeathFuzzHarness.run(1L, sequences = 257)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Camera2ServiceDeathFuzzHarness.run(1L, eventsPerSequence = 17)
        }
    }
}
