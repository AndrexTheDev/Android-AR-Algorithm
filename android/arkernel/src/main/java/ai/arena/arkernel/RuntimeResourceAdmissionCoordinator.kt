package ai.arena.arkernel

data class RuntimeResourceAdmissionRefresh(
    val decision: RuntimeResourceDecision,
    val telemetry: RuntimeResourceTelemetry,
    val thermalState: InferenceThermalState,
    val executionResult: RuntimeExecutionPlanResult,
)

/**
 * Atomically re-evaluates bounded runtime evidence and submits it only through the guarded owner.
 * Active compatibility evidence and profile identity are immutable for this admitted owner.
 */
class RuntimeResourceAdmissionCoordinator(
    private val staticReport: DeviceCompatibilityReport,
    private val activeReport: ActiveCompatibilityReport?,
    private val calibrationProfileSha256: String?,
    private val owner: RuntimeExecutionPlanOwner,
    private val telemetryCollector: RuntimeResourceTelemetryCollector =
        RuntimeResourceTelemetryCollector(),
    initialThermalState: InferenceThermalState = InferenceThermalState.NORMAL,
) {
    @Volatile private var thermalState = initialThermalState

    init {
        require(calibrationProfileSha256 == null ||
            calibrationProfileSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun addFrameProcessingDuration(durationNs: Long): Boolean =
        telemetryCollector.addFrameProcessingDuration(durationNs)

    fun updateMemoryPressure(pressure: RuntimeMemoryPressure) =
        telemetryCollector.updateMemoryPressure(pressure)

    @Synchronized
    fun updateThermalState(state: InferenceThermalState) {
        thermalState = state
    }

    @Synchronized
    fun refresh(): RuntimeResourceAdmissionRefresh {
        val telemetry = telemetryCollector.snapshot()
        val thermal = thermalState
        val decision = RuntimeResourcePolicy.evaluate(staticReport, activeReport,
            telemetry, thermal)
        val execution = owner.updateExecutionPlan(
            RuntimeExecutionPlan(decision, calibrationProfileSha256))
        return RuntimeResourceAdmissionRefresh(decision, telemetry, thermal, execution)
    }
}
