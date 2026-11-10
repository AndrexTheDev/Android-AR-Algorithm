package ai.arena.arkernel

import ai.arena.arkernel.ArKernelTurntableActuationPhase.*
import ai.arena.arkernel.ArKernelTurntableActuationRejection.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelTurntableActuationTest {
    @Test
    fun issuesBoundedCommandAndAcceptsMatchingFeedback() {
        val controller = ArKernelTurntableActuationController()
        val issued = controller.issue(validGuidance(), 1_000L)
        assertEquals(COMMAND, issued.phase)
        assertEquals(NONE, issued.rejection)
        assertEquals(1L, issued.commandsIssued)
        assertFalse(issued.terminal)
        val command = requireNotNull(issued.command)
        assertEquals(1L, command.commandId)
        assertEquals(1L, command.sequence)
        assertEquals(1_000L, command.issuedTimestampNs)
        assertEquals(1_000L + 5_000_000_000L, command.deadlineTimestampNs)
        assertEquals(90.0, command.stepDegrees, 0.0)
        assertEquals(0.125, command.coverageRatio, 0.0)
        assertNotNull(controller.pending())

        val duplicate = controller.issue(validGuidance(), 2_000L)
        assertEquals(REJECTED, duplicate.phase)
        assertEquals(PENDING_COMMAND, duplicate.rejection)
        assertFalse(duplicate.terminal)

        val accepted = controller.confirm(feedback(command, timestampNs = 2_000L))
        assertEquals(ACCEPTED, accepted.phase)
        assertEquals(NONE, accepted.rejection)
        assertEquals(1L, accepted.commandsIssued)
        assertFalse(accepted.terminal)
        assertNull(controller.pending())
    }

    @Test
    fun holdsForNonActionableGuidanceWithoutIssuingCommands() {
        val controller = ArKernelTurntableActuationController()
        val complete = controller.issue(
            validGuidance().copy(
                phase = ObjectCapturePhase.COMPLETE,
                instruction = ObjectCaptureInstruction.COMPLETE,
                complete = true), 1_000L)
        assertEquals(HOLD, complete.phase)
        assertEquals(SCAN_COMPLETE, complete.rejection)

        val elevation = controller.issue(
            validGuidance().copy(instruction = ObjectCaptureInstruction.RAISE_CAMERA), 2_000L)
        assertEquals(HOLD, elevation.phase)
        assertEquals(ELEVATION_CHANGE_REQUIRED, elevation.rejection)

        val blocked = controller.issue(
            validGuidance().copy(
                phase = ObjectCapturePhase.BLOCKED,
                instruction = ObjectCaptureInstruction.STOP,
                rejection = ObjectCaptureRejection.COVERAGE_INCOMPLETE), 3_000L)
        assertEquals(HOLD, blocked.phase)
        assertEquals(GUIDANCE_INACTIVE, blocked.rejection)

        val rejected = controller.issue(
            validGuidance().copy(
                instruction = ObjectCaptureInstruction.RETRY_TRACKING,
                rejection = ObjectCaptureRejection.TRACKING_UNRELIABLE), 4_000L)
        assertEquals(HOLD, rejected.phase)
        assertEquals(GUIDANCE_REJECTED, rejected.rejection)

        val distance = controller.issue(
            validGuidance().copy(
                phase = ObjectCapturePhase.SET_DISTANCE,
                instruction = ObjectCaptureInstruction.MOVE_CLOSER), 5_000L)
        assertEquals(HOLD, distance.phase)
        assertEquals(GUIDANCE_INACTIVE, distance.rejection)
        assertEquals(0L, distance.commandsIssued)
        assertNull(controller.pending())
    }

    @Test
    fun rejectsInvalidStepTargetAndTimestampInputs() {
        val controller = ArKernelTurntableActuationController()
        assertEquals(INVALID_TIMESTAMP, controller.issue(validGuidance(), 0L).rejection)
        assertEquals(TIMESTAMP_OVERFLOW,
            controller.issue(validGuidance(), Long.MAX_VALUE - 1L).rejection)
        assertEquals(INVALID_STEP,
            controller.issue(validGuidance().copy(turntableStepDegrees = Double.NaN), 1_000L).rejection)
        assertEquals(INVALID_STEP,
            controller.issue(validGuidance().copy(turntableStepDegrees = 0.0), 2_000L).rejection)
        assertEquals(STEP_OUT_OF_BOUNDS,
            controller.issue(validGuidance().copy(turntableStepDegrees = 200.0), 3_000L).rejection)
        assertEquals(STEP_TARGET_MISMATCH,
            controller.issue(validGuidance().copy(targetAzimuthDegrees = 100.0), 4_000L).rejection)
        assertEquals(INVALID_GUIDANCE,
            controller.issue(validGuidance().copy(targetAzimuthDegrees = 400.0), 5_000L).rejection)
        assertEquals(INVALID_GUIDANCE,
            controller.issue(validGuidance().copy(coverageRatio = 1.5), 6_000L).rejection)
        val accepted = controller.issue(validGuidance(), 7_000L)
        assertEquals(COMMAND, accepted.phase)
        assertEquals(1L, accepted.commandsIssued)
        assertNotNull(controller.pending())
    }

    @Test
    fun confirmationFailsClosedForFeedbackViolations() {
        assertRejection(COMMAND_ID_MISMATCH) { command ->
            feedback(command).copy(commandId = command.commandId + 1L)
        }
        assertRejection(NON_MONOTONIC_TIMESTAMP) { command ->
            feedback(command).copy(timestampNs = command.issuedTimestampNs)
        }
        assertRejection(TIMEOUT) { command ->
            feedback(command).copy(timestampNs = command.deadlineTimestampNs + 1L)
        }
        assertRejection(ACTUATOR_FAULT) { command ->
            feedback(command).copy(fault = true)
        }
        assertRejection(ACTUATOR_MOVING) { command ->
            feedback(command).copy(moving = true)
        }
        assertRejection(EXECUTED_STEP_MISMATCH) { command ->
            feedback(command).copy(executedStepDegrees = 80.0)
        }

        val fresh = ArKernelTurntableActuationController()
        val noPending = fresh.confirm(
            ArKernelTurntableFeedback(1L, 1_000L, 90.0, false, false))
        assertEquals(REJECTED, noPending.phase)
        assertEquals(NO_PENDING_COMMAND, noPending.rejection)
        assertFalse(noPending.terminal)
    }

    @Test
    fun adapterConvenienceFailsClosedAndResetRecovers() {
        val acceptedController = ArKernelTurntableActuationController()
        val accepted = acceptedController.actuateWith(
            ArKernelTurntableActuator { command -> feedback(command) },
            validGuidance(), 1_000L)
        assertEquals(ACCEPTED, accepted.phase)
        assertNull(acceptedController.pending())

        val controller = ArKernelTurntableActuationController()
        val failed = controller.actuateWith(
            ArKernelTurntableActuator { throw IllegalStateException("hardware") },
            validGuidance(), 1_000L)
        assertEquals(REJECTED, failed.phase)
        assertEquals(ADAPTER_FAILED, failed.rejection)
        assertTrue(failed.terminal)
        assertTrue(controller.isTerminalFailure())
        assertNull(controller.pending())
        assertEquals(TERMINAL_FAILURE, controller.issue(validGuidance(), 2_000L).rejection)
        controller.reset()
        assertFalse(controller.isTerminalFailure())
        assertEquals(COMMAND, controller.issue(validGuidance(), 3_000L).phase)
    }

    @Test
    fun commandBudgetAndNonMonotonicTimestampsAreBounded() {
        val controller = ArKernelTurntableActuationController(
            ArKernelTurntableActuationConfig(
                maxCommands = 2L,
                commandTimeoutNs = 1_000L))
        val first = controller.issue(validGuidance(), 1_000L)
        val firstCommand = requireNotNull(first.command)
        assertEquals(ACCEPTED,
            controller.confirm(feedback(firstCommand, timestampNs = 1_100L)).phase)
        assertEquals(NON_MONOTONIC_TIMESTAMP,
            controller.issue(validGuidance(), 900L).rejection)

        val second = controller.issue(validGuidance(), 1_200L)
        assertEquals(COMMAND, second.phase)
        val secondCommand = requireNotNull(second.command)
        assertEquals(2L, secondCommand.sequence)
        assertEquals(ACCEPTED,
            controller.confirm(feedback(secondCommand, timestampNs = 1_300L)).phase)
        assertEquals(COMMAND_BUDGET_REACHED,
            controller.issue(validGuidance(), 1_400L).rejection)

        controller.reset()
        assertEquals(COMMAND, controller.issue(validGuidance(), 1_500L).phase)
    }

    @Test
    fun configRejectsInvalidBudgets() {
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelTurntableActuationConfig(maxStepDegrees = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelTurntableActuationConfig(maxStepDegrees = 181.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelTurntableActuationConfig(maxCommands = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelTurntableActuationConfig(commandTimeoutNs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelTurntableActuationConfig(stepToleranceDegrees = -1.0)
        }
    }

    private fun assertRejection(
        expected: ArKernelTurntableActuationRejection,
        feedback: (ArKernelTurntableCommand) -> ArKernelTurntableFeedback,
    ) {
        val controller = ArKernelTurntableActuationController()
        val issued = controller.issue(validGuidance(), 1_000L)
        val command = requireNotNull(issued.command)
        val actual = controller.confirm(feedback(command))
        assertEquals(REJECTED, actual.phase)
        assertEquals(expected, actual.rejection)
        assertTrue(actual.terminal)
        assertTrue(controller.isTerminalFailure())
        assertNull(controller.pending())
    }

    private fun feedback(
        command: ArKernelTurntableCommand,
        timestampNs: Long = command.issuedTimestampNs + 1L,
        executedStepDegrees: Double = command.stepDegrees,
        moving: Boolean = false,
        fault: Boolean = false,
    ): ArKernelTurntableFeedback = ArKernelTurntableFeedback(
        command.commandId, timestampNs, executedStepDegrees, moving, fault)

    private fun validGuidance(): ObjectCaptureGuidanceResult = ObjectCaptureGuidanceResult(
        phase = ObjectCapturePhase.ORBIT,
        instruction = ObjectCaptureInstruction.ORBIT_TO_NEXT_VIEW,
        rejection = ObjectCaptureRejection.NONE,
        complete = false,
        observations = 2L,
        stableCenterObservations = 2L,
        coveredBins = 1L,
        totalBins = 8L,
        coveredRings = 1L,
        coverageRatio = 0.125,
        currentAzimuthDegrees = 45.0,
        currentElevationDegrees = 0.0,
        targetAzimuthDegrees = 135.0,
        targetElevationDegrees = 0.0,
        turntableStepDegrees = 90.0,
        measuredDistanceMeters = 0.8,
    )
}
