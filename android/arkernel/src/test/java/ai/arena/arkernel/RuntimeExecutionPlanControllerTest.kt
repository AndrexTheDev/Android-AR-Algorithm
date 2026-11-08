package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeExecutionPlanControllerTest {
    @Test
    fun sameExecutablePlanOnlyRefreshesDiagnostics() {
        val controller = RuntimeExecutionPlanController(RuntimeExecutionPlan(
            decision(RuntimeResourceTier.BALANCED), "a".repeat(64)))
        val refreshed = controller.update(RuntimeExecutionPlan(
            decision(RuntimeResourceTier.BALANCED,
                listOf(RuntimeResourceReason.THERMAL_WARM)), "a".repeat(64)))

        assertEquals(RuntimeExecutionPlanUpdate.DIAGNOSTICS_UPDATED, refreshed.update)
        assertFalse(controller.restartRequired)
        assertTrue(RuntimeResourceReason.THERMAL_WARM in
            controller.activePlan.resources.reasons)
    }

    @Test
    fun runtimeUpgradeIsRejectedWithoutChangingActivePlan() {
        val controller = RuntimeExecutionPlanController(RuntimeExecutionPlan(
            decision(RuntimeResourceTier.CONSERVATIVE), null))
        val result = controller.update(RuntimeExecutionPlan(
            decision(RuntimeResourceTier.PERFORMANCE), null))

        assertEquals(RuntimeExecutionPlanUpdate.UPGRADE_REJECTED, result.update)
        assertEquals(RuntimeResourceTier.CONSERVATIVE,
            controller.activePlan.resources.tier)
        assertNull(result.restartPlan)
    }

    @Test
    fun degradationRequiresStickyOwnerRestart() {
        val controller = RuntimeExecutionPlanController(RuntimeExecutionPlan(
            decision(RuntimeResourceTier.BALANCED), "a".repeat(64)))
        val degradedPlan = RuntimeExecutionPlan(
            decision(RuntimeResourceTier.CONSERVATIVE), "a".repeat(64))
        val first = controller.update(degradedPlan)
        val repeated = controller.update(RuntimeExecutionPlan(
            decision(RuntimeResourceTier.BALANCED), "a".repeat(64)))

        assertEquals(RuntimeExecutionPlanUpdate.RESTART_REQUIRED, first.update)
        assertEquals(listOf(RuntimeRestartReason.RESOURCE_PLAN_CHANGED), first.restartReasons)
        assertEquals(first, repeated)
        assertTrue(controller.restartRequired)
        assertEquals(RuntimeResourceTier.BALANCED, controller.activePlan.resources.tier)
        assertEquals(RuntimeResourceTier.CONSERVATIVE,
            requireNotNull(first.restartPlan).resources.tier)
    }

    @Test
    fun calibratedProfileChangeRequiresRestartEvenWithinSameTier() {
        val controller = RuntimeExecutionPlanController(RuntimeExecutionPlan(
            decision(RuntimeResourceTier.CONSERVATIVE), "a".repeat(64)))
        val result = controller.update(RuntimeExecutionPlan(
            decision(RuntimeResourceTier.CONSERVATIVE), "b".repeat(64)))

        assertEquals(RuntimeExecutionPlanUpdate.RESTART_REQUIRED, result.update)
        assertEquals(listOf(RuntimeRestartReason.CALIBRATED_PROFILE_CHANGED),
            result.restartReasons)
    }

    private fun decision(tier: RuntimeResourceTier,
        reasons: List<RuntimeResourceReason> = emptyList()): RuntimeResourceDecision = when (tier) {
        RuntimeResourceTier.PERFORMANCE -> RuntimeResourceDecision(tier, 1920 * 1080, 30,
            4096, 2, true, true, true, reasons)
        RuntimeResourceTier.BALANCED -> RuntimeResourceDecision(tier, 1280 * 720, 24,
            3072, 1, true, true, true, reasons)
        RuntimeResourceTier.CONSERVATIVE -> RuntimeResourceDecision(tier, 640 * 480, 15,
            1024, 1, false, false, true, reasons)
        RuntimeResourceTier.CAPTURE_ONLY -> RuntimeResourceDecision(tier, 1920 * 1080, 10,
            0, 1, false, false, false, reasons)
        RuntimeResourceTier.UNSUPPORTED -> RuntimeResourceDecision(tier, 0, 0,
            0, 0, false, false, false, reasons)
    }
}
