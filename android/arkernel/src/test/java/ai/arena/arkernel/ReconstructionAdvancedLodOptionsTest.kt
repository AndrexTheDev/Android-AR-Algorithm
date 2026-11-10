package ai.arena.arkernel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionAdvancedLodOptionsTest {
    @Test
    fun optionalAdvancedStagesAreOffByDefault() {
        val fusion = ReconstructionFusionOptions()
        assertFalse(fusion.enableQuadricErrorLod)
        assertTrue(fusion.maxQuadricErrorSquareMeters > 0.0)
        assertTrue(fusion.maxQuadricLodDisplacementMeters > 0.0)
        assertTrue(fusion.quadricRegularization > 0.0)
        assertTrue(fusion.maxQuadricEvaluations > 0)

        val texture = ReconstructionTexturePipelineOptions()
        assertFalse(texture.enableGlobalSeamOptimization)
        assertTrue(texture.enableSeamBlending)
    }

    @Test
    fun rejectsInvalidQuadricLodBudgets() {
        assertFails { ReconstructionFusionOptions(maxQuadricErrorSquareMeters = 0.0) }
        assertFails { ReconstructionFusionOptions(maxQuadricErrorSquareMeters = 101.0) }
        assertFails { ReconstructionFusionOptions(maxQuadricLodDisplacementMeters = 0.0) }
        assertFails { ReconstructionFusionOptions(quadricRegularization = 0.0) }
        assertFails { ReconstructionFusionOptions(maxQuadricEvaluations = 0L) }
        assertFails { ReconstructionFusionOptions(maxQuadricEvaluations = 100_000_001L) }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
