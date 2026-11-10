package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelObjectCaptureGuidanceOptionsTest {
    @Test
    fun defaultConfigurationIsBoundedAndEnvironmentAgnostic() {
        val config = ObjectCaptureGuidanceConfig()
        assertEquals(12L, config.azimuthBins)
        assertEquals(3L, config.elevationBins)
        assertEquals(512L, config.maxObservations)
        assertEquals(3L, config.minStableCenterObservations)
        assertEquals(0.10, config.minForegroundRatio, 0.0)
        assertEquals(0.90, config.maxForegroundRatio, 0.0)
        assertEquals(0.80, config.minCoverageRatio, 0.0)
    }

    @Test
    fun rejectsInvalidGuidanceBudgetsAndBounds() {
        assertFails { ObjectCaptureGuidanceConfig(azimuthBins = 0L) }
        assertFails { ObjectCaptureGuidanceConfig(elevationBins = 181L) }
        assertFails { ObjectCaptureGuidanceConfig(maxObservations = 0L) }
        assertFails { ObjectCaptureGuidanceConfig(minStableCenterObservations = 0L) }
        assertFails {
            ObjectCaptureGuidanceConfig(maxObservations = 2L,
                minStableCenterObservations = 3L)
        }
        assertFails { ObjectCaptureGuidanceConfig(targetDistanceMeters = 10.0) }
        assertFails { ObjectCaptureGuidanceConfig(minForegroundRatio = 0.95) }
        assertFails { ObjectCaptureGuidanceConfig(minCoverageRatio = 0.0) }
        assertFails { ObjectCaptureGuidanceConfig(elevationStepDegrees = Double.NaN) }
    }

    @Test
    fun rejectsInvalidObservationPose() {
        val pose = WorldPose(1_000L, Rotation(1.0, 0.0, 0.0, 0.0),
            Vector3(0.8, 0.0, 0.0))
        ObjectCaptureGuidanceObservation(pose, true, 0.5, Vector3(0.0, 0.0, 0.0))
        assertFails {
            ObjectCaptureGuidanceObservation(pose.copy(timestampNs = 0L), true, 0.5)
        }
        assertFails { ObjectCaptureGuidanceObservation(pose, true, 1.5) }
        assertFails {
            ObjectCaptureGuidanceObservation(
                pose.copy(rotation = Rotation(0.0, 0.0, 0.0, 0.0)), true, 0.5)
        }
        assertFails {
            ObjectCaptureGuidanceObservation(pose, true, 0.5,
                Vector3(Double.NaN, 0.0, 0.0))
        }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
