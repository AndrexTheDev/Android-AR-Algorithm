package ai.arena.arkernel

import ai.arena.arkernel.ArKernelTurntableActuationPhase.*
import ai.arena.arkernel.ArKernelTurntableActuationRejection.*
import kotlin.math.abs

private const val ANGLE_EPSILON_DEGREES = 1e-6

private fun shortestAzimuthStepDegrees(from: Double, to: Double): Double {
    var delta = (to - from) % 360.0
    if (delta > 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    return delta
}

/**
 * Bounded contract for a host-owned physical turntable. The library never
 * talks to hardware: [ArKernelTurntableActuator] is implemented by the app,
 * which must report the measured executed step. This protocol validates
 * command identity, timing and feedback; it is not device/rig evidence.
 */
data class ArKernelTurntableActuationConfig(
    val maxStepDegrees: Double = 180.0,
    val maxCommands: Long = 512L,
    val commandTimeoutNs: Long = 5_000_000_000L,
    val stepToleranceDegrees: Double = 1.0,
) {
    init {
        require(maxStepDegrees.isFinite() && maxStepDegrees > 0.0 &&
            maxStepDegrees <= 180.0) { "Turntable step bound is invalid" }
        require(maxCommands in 1L..1_000_000L) { "Turntable command budget is invalid" }
        require(commandTimeoutNs > 0L) { "Turntable command timeout must be positive" }
        require(stepToleranceDegrees.isFinite() && stepToleranceDegrees >= 0.0 &&
            stepToleranceDegrees <= maxStepDegrees) {
            "Turntable executed-step tolerance is invalid"
        }
    }
}

enum class ArKernelTurntableActuationPhase {
    HOLD,
    COMMAND,
    ACCEPTED,
    REJECTED,
}

enum class ArKernelTurntableActuationRejection {
    NONE,
    GUIDANCE_INACTIVE,
    GUIDANCE_REJECTED,
    SCAN_COMPLETE,
    ELEVATION_CHANGE_REQUIRED,
    INVALID_GUIDANCE,
    INVALID_STEP,
    STEP_OUT_OF_BOUNDS,
    STEP_TARGET_MISMATCH,
    INVALID_TIMESTAMP,
    NON_MONOTONIC_TIMESTAMP,
    TIMESTAMP_OVERFLOW,
    COMMAND_BUDGET_REACHED,
    PENDING_COMMAND,
    NO_PENDING_COMMAND,
    COMMAND_ID_MISMATCH,
    TIMEOUT,
    ACTUATOR_FAULT,
    ACTUATOR_MOVING,
    EXECUTED_STEP_MISMATCH,
    ADAPTER_FAILED,
    TERMINAL_FAILURE,
}

data class ArKernelTurntableCommand(
    val commandId: Long,
    val sequence: Long,
    val issuedTimestampNs: Long,
    val deadlineTimestampNs: Long,
    val stepDegrees: Double,
    val currentAzimuthDegrees: Double,
    val currentElevationDegrees: Double,
    val targetAzimuthDegrees: Double,
    val targetElevationDegrees: Double,
    val coverageRatio: Double,
)

data class ArKernelTurntableFeedback(
    val commandId: Long,
    val timestampNs: Long,
    val executedStepDegrees: Double,
    val moving: Boolean,
    val fault: Boolean,
)

data class ArKernelTurntableActuationResult(
    val phase: ArKernelTurntableActuationPhase,
    val rejection: ArKernelTurntableActuationRejection,
    val command: ArKernelTurntableCommand? = null,
    val commandsIssued: Long = 0L,
    val terminal: Boolean = false,
)

/** Host-owned hardware adapter. Implementations must supply measured feedback. */
fun interface ArKernelTurntableActuator {
    fun actuate(command: ArKernelTurntableCommand): ArKernelTurntableFeedback
}

/**
 * One-at-a-time deterministic owner of the host actuation protocol. It turns
 * an actionable [ObjectCaptureGuidanceResult] into a bounded command and only
 * accepts feedback that matches the exact command, monotonic time budget and
 * measured step. Any adapter or confirmation failure becomes terminal until
 * [reset]. No command is sent automatically and no reconstruction quality is
 * inferred from a completed movement.
 */
class ArKernelTurntableActuationController(
    private val config: ArKernelTurntableActuationConfig = ArKernelTurntableActuationConfig(),
) {
    private var pendingCommand: ArKernelTurntableCommand? = null
    private var nextCommandId = 1L
    private var commandsIssued = 0L
    private var lastTimestampNs = 0L
    private var terminalFailure = false

    @Synchronized
    fun issue(
        guidance: ObjectCaptureGuidanceResult,
        timestampNs: Long,
    ): ArKernelTurntableActuationResult {
        if (terminalFailure) return result(REJECTED, TERMINAL_FAILURE)
        if (pendingCommand != null) return result(REJECTED, PENDING_COMMAND)
        if (commandsIssued >= config.maxCommands) return result(REJECTED, COMMAND_BUDGET_REACHED)
        if (timestampNs <= 0L) return result(REJECTED, INVALID_TIMESTAMP)
        if (timestampNs <= lastTimestampNs) return result(REJECTED, NON_MONOTONIC_TIMESTAMP)
        if (timestampNs > Long.MAX_VALUE - config.commandTimeoutNs) {
            return result(REJECTED, TIMESTAMP_OVERFLOW)
        }
        if (guidance.complete || guidance.phase == ObjectCapturePhase.COMPLETE) {
            return result(HOLD, SCAN_COMPLETE)
        }
        if (guidance.phase == ObjectCapturePhase.BLOCKED ||
            guidance.phase != ObjectCapturePhase.ORBIT) {
            return result(HOLD, GUIDANCE_INACTIVE)
        }
        if (guidance.rejection != ObjectCaptureRejection.NONE) {
            return result(HOLD, GUIDANCE_REJECTED)
        }
        if (guidance.instruction == ObjectCaptureInstruction.RAISE_CAMERA ||
            guidance.instruction == ObjectCaptureInstruction.LOWER_CAMERA) {
            return result(HOLD, ELEVATION_CHANGE_REQUIRED)
        }
        if (guidance.instruction != ObjectCaptureInstruction.ORBIT_TO_NEXT_VIEW) {
            return result(HOLD, GUIDANCE_INACTIVE)
        }

        val step = guidance.turntableStepDegrees
        val scalars = listOf(step, guidance.currentAzimuthDegrees,
            guidance.currentElevationDegrees, guidance.targetAzimuthDegrees,
            guidance.targetElevationDegrees, guidance.coverageRatio)
        if (scalars.any { !it.isFinite() }) return result(REJECTED, INVALID_GUIDANCE)
        if (step == 0.0) return result(REJECTED, INVALID_STEP)
        if (abs(step) > config.maxStepDegrees) return result(REJECTED, STEP_OUT_OF_BOUNDS)
        if (guidance.coverageRatio !in 0.0..1.0 ||
            guidance.currentAzimuthDegrees !in 0.0..360.0 ||
            guidance.targetAzimuthDegrees !in 0.0..360.0 ||
            guidance.currentElevationDegrees !in -90.0..90.0 ||
            guidance.targetElevationDegrees !in -90.0..90.0) {
            return result(REJECTED, INVALID_GUIDANCE)
        }
        val expectedStep = shortestAzimuthStepDegrees(
            guidance.currentAzimuthDegrees, guidance.targetAzimuthDegrees)
        if (abs(expectedStep - step) > ANGLE_EPSILON_DEGREES) {
            return result(REJECTED, STEP_TARGET_MISMATCH)
        }

        val command = ArKernelTurntableCommand(
            commandId = nextCommandId,
            sequence = commandsIssued + 1L,
            issuedTimestampNs = timestampNs,
            deadlineTimestampNs = timestampNs + config.commandTimeoutNs,
            stepDegrees = step,
            currentAzimuthDegrees = guidance.currentAzimuthDegrees,
            currentElevationDegrees = guidance.currentElevationDegrees,
            targetAzimuthDegrees = guidance.targetAzimuthDegrees,
            targetElevationDegrees = guidance.targetElevationDegrees,
            coverageRatio = guidance.coverageRatio,
        )
        pendingCommand = command
        lastTimestampNs = timestampNs
        commandsIssued += 1L
        nextCommandId += 1L
        return result(COMMAND, NONE, command)
    }

    @Synchronized
    fun confirm(feedback: ArKernelTurntableFeedback): ArKernelTurntableActuationResult {
        if (terminalFailure) return result(REJECTED, TERMINAL_FAILURE)
        val command = pendingCommand ?: return result(REJECTED, NO_PENDING_COMMAND)
        if (feedback.commandId != command.commandId) {
            return abort(COMMAND_ID_MISMATCH)
        }
        if (feedback.timestampNs <= lastTimestampNs) {
            return abort(NON_MONOTONIC_TIMESTAMP)
        }
        if (feedback.timestampNs > command.deadlineTimestampNs) return abort(TIMEOUT)
        if (feedback.fault) return abort(ACTUATOR_FAULT)
        if (feedback.moving) return abort(ACTUATOR_MOVING)
        val executed = feedback.executedStepDegrees
        if (!executed.isFinite() ||
            abs(executed - command.stepDegrees) > config.stepToleranceDegrees ||
            abs(executed) > config.maxStepDegrees + config.stepToleranceDegrees) {
            return abort(EXECUTED_STEP_MISMATCH)
        }
        pendingCommand = null
        lastTimestampNs = feedback.timestampNs
        return result(ACCEPTED, NONE)
    }

    /**
     * Convenience path for a host that owns a synchronous adapter. The adapter
     * is called without holding the controller lock; failures are terminal.
     */
    fun actuateWith(
        adapter: ArKernelTurntableActuator,
        guidance: ObjectCaptureGuidanceResult,
        timestampNs: Long,
    ): ArKernelTurntableActuationResult {
        val issued = issue(guidance, timestampNs)
        if (issued.phase != COMMAND) return issued
        val command = issued.command ?: return issued
        val feedback = try {
            adapter.actuate(command)
        } catch (_: Exception) {
            return abort(ADAPTER_FAILED)
        }
        return confirm(feedback)
    }

    @Synchronized
    fun pending(): ArKernelTurntableCommand? = pendingCommand

    @Synchronized
    fun isTerminalFailure(): Boolean = terminalFailure

    /** Clears terminal and budget state; command IDs keep increasing. */
    @Synchronized
    fun reset() {
        pendingCommand = null
        commandsIssued = 0L
        lastTimestampNs = 0L
        terminalFailure = false
    }

    @Synchronized
    private fun abort(
        rejection: ArKernelTurntableActuationRejection,
    ): ArKernelTurntableActuationResult {
        pendingCommand = null
        terminalFailure = true
        return result(REJECTED, rejection)
    }

    private fun result(
        phase: ArKernelTurntableActuationPhase,
        rejection: ArKernelTurntableActuationRejection,
        command: ArKernelTurntableCommand? = null,
    ): ArKernelTurntableActuationResult = ArKernelTurntableActuationResult(
        phase, rejection, command, commandsIssued, terminalFailure)
}
