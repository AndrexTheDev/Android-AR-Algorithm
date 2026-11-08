package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StandaloneArRuntimeConfigTest {
    @Test
    fun acceptsExplicitCalibratedStandaloneProfile() {
        val config = StandaloneArRuntimeConfig(
            cameraId = "0",
            calibration = CameraCalibration(640, 480, 500.0, 501.0, 320.0, 240.0),
            bodyFromCameraRotation = Rotation(1.0, 0.0, 0.0, 0.0),
            bodyFromCameraTranslationMeters = Vector3(0.01, 0.0, -0.02),
        )
        assertEquals("0", config.cameraId)
        assertEquals(4096, config.maxQueuedImuSamples)
    }

    @Test
    fun rejectsUnknownCameraToImuRotation() {
        assertThrows(IllegalArgumentException::class.java) {
            StandaloneArRuntimeConfig(
                calibration = CameraCalibration(640, 480, 500.0, 500.0, 320.0, 240.0),
                bodyFromCameraRotation = Rotation(0.0, 0.0, 0.0, 0.0),
            )
        }
    }

    @Test
    fun standaloneCapabilitiesDoNotClaimLidarRequirement() {
        val capabilities = ArRuntimeCapabilities(true, true, true, true, true, true, false)
        assertEquals(false, capabilities.requiresLidar)
        assertEquals(true, capabilities.sixDofTracking)
    }
}
