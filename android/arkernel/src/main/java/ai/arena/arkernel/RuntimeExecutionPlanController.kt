package ai.arena.arkernel

enum class RuntimeExecutionPlanUpdate {
    DIAGNOSTICS_UPDATED,
    UPGRADE_REJECTED,
    RESTART_REQUIRED,
}

enum class RuntimeRestartReason {
    RESOURCE_PLAN_CHANGED,
    CALIBRATED_PROFILE_CHANGED,
}

data class RuntimeExecutionPlan(
    val resources: RuntimeResourceDecision,
    val calibrationProfileSha256: String?,
) {
    init {
        require(calibrationProfileSha256 == null ||
            calibrationProfileSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class RuntimeExecutionPlanResult(
    val update: RuntimeExecutionPlanUpdate,
    val activePlan: RuntimeExecutionPlan,
    val restartPlan: RuntimeExecutionPlan?,
    val restartReasons: List<RuntimeRestartReason>,
) {
    init {
        require(restartReasons.distinct().size == restartReasons.size)
        require((update == RuntimeExecutionPlanUpdate.RESTART_REQUIRED) ==
            (restartPlan != null && restartReasons.isNotEmpty()))
    }
}

/**
 * Session-scoped execution guard. It updates diagnostics for an identical plan, rejects upgrades,
 * and terminally requests owner replacement for every executable degradation/profile change.
 */
class RuntimeExecutionPlanController(initial: RuntimeExecutionPlan) {
    @Volatile private var active = initial
    @Volatile private var terminal: RuntimeExecutionPlanResult? = null

    val activePlan: RuntimeExecutionPlan get() = active
    val restartRequired: Boolean get() = terminal != null

    @Synchronized
    fun update(candidate: RuntimeExecutionPlan): RuntimeExecutionPlanResult {
        terminal?.let { return it }
        if (candidate.resources.tier.ordinal < active.resources.tier.ordinal)
            return RuntimeExecutionPlanResult(RuntimeExecutionPlanUpdate.UPGRADE_REJECTED,
                active, null, emptyList())

        val reasons = mutableListOf<RuntimeRestartReason>()
        if (!executionEquivalent(active.resources, candidate.resources))
            reasons += RuntimeRestartReason.RESOURCE_PLAN_CHANGED
        if (active.calibrationProfileSha256 != candidate.calibrationProfileSha256)
            reasons += RuntimeRestartReason.CALIBRATED_PROFILE_CHANGED
        if (reasons.isNotEmpty()) {
            return RuntimeExecutionPlanResult(RuntimeExecutionPlanUpdate.RESTART_REQUIRED,
                active, candidate, reasons).also { terminal = it }
        }

        active = candidate
        return RuntimeExecutionPlanResult(RuntimeExecutionPlanUpdate.DIAGNOSTICS_UPDATED,
            active, null, emptyList())
    }

    private fun executionEquivalent(
        first: RuntimeResourceDecision,
        second: RuntimeResourceDecision,
    ): Boolean = first.tier == second.tier &&
        first.maximumCalibratedStreamPixels == second.maximumCalibratedStreamPixels &&
        first.targetFramesPerSecond == second.targetFramesPerSecond &&
        first.maximumQueuedImuSamples == second.maximumQueuedImuSamples &&
        first.maximumPendingReconstructionFrames ==
            second.maximumPendingReconstructionFrames &&
        first.denseDepthEnabled == second.denseDepthEnabled &&
        first.semanticInferenceEnabled == second.semanticInferenceEnabled &&
        first.liveMappingEnabled == second.liveMappingEnabled
}
