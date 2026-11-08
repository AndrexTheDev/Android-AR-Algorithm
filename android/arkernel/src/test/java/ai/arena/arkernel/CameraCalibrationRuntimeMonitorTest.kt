package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraCalibrationRuntimeMonitorTest {
    @Test
    fun acceptsExactModeWithinOpticalTolerances() {
        val monitor = CameraCalibrationRuntimeMonitor(binding())
        assertNull(monitor.observe(state(focal = 4.01, focus = 0.99)))
        assertNull(monitor.failure)
    }

    @Test
    fun cropOrStabilizationChangeIsTerminal() {
        val crop = CameraCalibrationRuntimeMonitor(binding())
        assertEquals(CameraCalibrationRuntimeFailure.CROP_REGION_CHANGED,
            crop.observe(state(cropLeft = 1)))
        assertEquals(CameraCalibrationRuntimeFailure.CROP_REGION_CHANGED,
            crop.observe(state(videoMode = 99)))

        val stabilization = CameraCalibrationRuntimeMonitor(binding())
        assertEquals(CameraCalibrationRuntimeFailure.VIDEO_STABILIZATION_CHANGED,
            stabilization.observe(state(videoMode = 1)))
    }

    @Test
    fun rejectsMissingOrChangedLensMetadataWhenProfileBindsIt() {
        val focal = CameraCalibrationRuntimeMonitor(binding())
        assertEquals(CameraCalibrationRuntimeFailure.FOCAL_LENGTH_MISSING,
            focal.observe(state(focal = null)))
        val focus = CameraCalibrationRuntimeMonitor(binding())
        assertEquals(CameraCalibrationRuntimeFailure.FOCUS_DISTANCE_CHANGED,
            focus.observe(state(focus = 1.2)))
    }

    private fun binding() = CalibrationStreamBinding("0", null, 640, 480,
        0, 0, 4000, 3000, videoStabilizationMode = 0,
        opticalStabilizationMode = 0, distortionCorrectionMode = 0,
        expectedFocalLengthMm = 4.0, focalLengthToleranceMm = 0.02,
        expectedFocusDistanceDiopters = 1.0, focusDistanceToleranceDiopters = 0.05)

    private fun state(cropLeft: Int = 0, videoMode: Int = 0,
        focal: Double? = 4.0, focus: Double? = 1.0) = CameraCalibrationCaptureState(
        cropLeft, 0, 4000, 3000, videoMode, 0, 0, focal, focus)
}
