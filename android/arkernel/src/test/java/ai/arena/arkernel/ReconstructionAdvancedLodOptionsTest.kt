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
        assertFalse(fusion.enableEdgeCollapseQem)
        assertTrue(fusion.maxEdgeCollapseErrorSquareMeters > 0.0)
        assertTrue(fusion.maxEdgeCollapseDisplacementMeters > 0.0)
        assertTrue(fusion.edgeCollapseRegularization > 0.0)
        assertTrue(fusion.maxEdgeCollapseEvaluations > 0)
        assertTrue(fusion.maxEdgeCollapseCollapses > 0)
        assertTrue(fusion.edgeCollapsePreserveBoundaries)
        assertTrue(fusion.edgeCollapsePreserveNonManifold)
        assertTrue(fusion.edgeCollapsePreserveSharpEdges)
        assertTrue(fusion.edgeCollapsePreserveUvSeams)

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
        assertFails { ReconstructionFusionOptions(maxEdgeCollapseErrorSquareMeters = 0.0) }
        assertFails { ReconstructionFusionOptions(maxEdgeCollapseErrorSquareMeters = 101.0) }
        assertFails { ReconstructionFusionOptions(maxEdgeCollapseDisplacementMeters = 0.0) }
        assertFails { ReconstructionFusionOptions(edgeCollapseRegularization = 0.0) }
        assertFails { ReconstructionFusionOptions(edgeCollapseMinNormalDot = 2.0) }
        assertFails { ReconstructionFusionOptions(maxEdgeCollapseEvaluations = 0L) }
        assertFails { ReconstructionFusionOptions(maxEdgeCollapseEvaluations = 100_000_001L) }
        assertFails { ReconstructionFusionOptions(maxEdgeCollapseCollapses = 0L) }
        assertFails { ReconstructionFusionOptions(maxEdgeCollapseCollapses = 1_000_001L) }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
