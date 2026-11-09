package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2LifecycleStateMachineTest {
    @Test
    fun happyPathRequiresOrderedCallbacks() {
        val machine = Camera2LifecycleStateMachine()
        val generation = machine.beginStart()

        assertEquals(Camera2LifecycleState.OPENING, machine.state)
        assertFalse(machine.sessionConfigured(generation).accepted)
        assertFalse(machine.fail(generation,
            Camera2LifecycleFailure.SESSION_CONFIGURE_FAILED).accepted)
        assertTrue(machine.deviceOpened(generation).accepted)
        assertEquals(Camera2LifecycleState.CONFIGURING, machine.state)
        assertTrue(machine.sessionConfigured(generation).accepted)
        assertEquals(Camera2LifecycleState.STREAMING, machine.state)
        assertFalse(machine.sessionConfigured(generation).accepted)
        assertEquals(Camera2LifecycleState.STREAMING, machine.state)
        assertTrue(machine.stop().accepted)
        assertEquals(Camera2LifecycleState.STOPPED, machine.state)
    }

    @Test
    fun terminalFailureReportsOnceAndRequiresStopBeforeRestart() {
        val machine = Camera2LifecycleStateMachine()
        val generation = machine.beginStart()
        machine.deviceOpened(generation)
        val failure = machine.fail(generation,
            Camera2LifecycleFailure.SESSION_CONFIGURE_FAILED)

        assertTrue(failure.shouldReportFailure)
        assertEquals(Camera2LifecycleState.FAILED, machine.state)
        assertFalse(machine.fail(generation,
            Camera2LifecycleFailure.DEVICE_ERROR).shouldReportFailure)
        assertFalse(machine.sessionConfigured(generation).accepted)
        machine.stop()
        assertTrue(machine.beginStart() > generation)
    }

    @Test
    fun staleCallbacksCannotAffectNewStartGeneration() {
        val machine = Camera2LifecycleStateMachine()
        val first = machine.beginStart()
        machine.stop()
        val second = machine.beginStart()

        val staleOpen = machine.deviceOpened(first)
        assertFalse(staleOpen.accepted)
        assertTrue(staleOpen.shouldCloseCallbackResource)
        assertEquals(Camera2LifecycleState.OPENING, machine.state)
        assertFalse(machine.fail(first,
            Camera2LifecycleFailure.DEVICE_DISCONNECTED).shouldReportFailure)
        assertTrue(machine.deviceOpened(second).accepted)
        assertTrue(machine.sessionConfigured(second).accepted)
    }

    @Test
    fun exhaustiveBoundedCallbackSequencesPreserveTerminalInvariants() {
        val events = TestEvent.entries
        repeat(power(events.size, 5)) { encoded ->
            var value = encoded
            val machine = Camera2LifecycleStateMachine()
            val generation = machine.beginStart()
            var failureReports = 0
            var terminalReached = false
            repeat(5) {
                val event = events[value % events.size]
                value /= events.size
                val transition = when (event) {
                    TestEvent.OPENED -> machine.deviceOpened(generation)
                    TestEvent.CONFIGURED -> machine.sessionConfigured(generation)
                    TestEvent.DISCONNECTED -> machine.fail(generation,
                        Camera2LifecycleFailure.DEVICE_DISCONNECTED)
                    TestEvent.DEVICE_ERROR -> machine.fail(generation,
                        Camera2LifecycleFailure.DEVICE_ERROR)
                    TestEvent.CONFIGURE_FAILED -> machine.fail(generation,
                        Camera2LifecycleFailure.SESSION_CONFIGURE_FAILED)
                    TestEvent.START_FAILED -> machine.fail(generation,
                        Camera2LifecycleFailure.SESSION_START_FAILED)
                    TestEvent.STOP -> machine.stop()
                }
                if (transition.shouldReportFailure) failureReports++
                if (terminalReached && event != TestEvent.STOP)
                    assertFalse("callback escaped terminal state in sequence $encoded",
                        transition.accepted)
                if (machine.state == Camera2LifecycleState.FAILED ||
                    machine.state == Camera2LifecycleState.STOPPED) terminalReached = true
                assertEquals(generation, machine.generation)
            }
            assertTrue("multiple terminal errors in sequence $encoded", failureReports <= 1)
        }
    }

    private enum class TestEvent {
        OPENED,
        CONFIGURED,
        DISCONNECTED,
        DEVICE_ERROR,
        CONFIGURE_FAILED,
        START_FAILED,
        STOP,
    }

    private fun power(base: Int, exponent: Int): Int {
        var result = 1
        repeat(exponent) { result *= base }
        return result
    }
}
