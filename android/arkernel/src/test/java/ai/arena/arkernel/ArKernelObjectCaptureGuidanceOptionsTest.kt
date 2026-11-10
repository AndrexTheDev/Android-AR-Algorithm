package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
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
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(azimuthBins = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(elevationBins = 181L)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(maxObservations = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(minStableCenterObservations = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(maxObservations = 2L,
                minStableCenterObservations = 3L)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(targetDistanceMeters = 10.0)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(minForegroundRatio = 0.95)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(minCoverageRatio = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceConfig(elevationStepDegrees = Double.NaN)
        }
    }

    @Test
    fun rejectsInvalidObservationPose() {
        val pose = WorldPose(1_000L, Rotation(1.0, 0.0, 0.0, 0.0),
            Vector3(0.8, 0.0, 0.0))
        ObjectCaptureGuidanceObservation(pose, true, 0.5, Vector3(0.0, 0.0, 0.0))
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceObservation(pose.copy(timestampNs = 0L), true, 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceObservation(pose, true, 1.5)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceObservation(
                pose.copy(rotation = Rotation(0.0, 0.0, 0.0, 0.0)), true, 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectCaptureGuidanceObservation(pose, true, 0.5,
                Vector3(Double.NaN, 0.0, 0.0))
        }
    }
}
