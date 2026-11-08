package ai.arena.arkernel

import kotlin.math.tan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTargetCaptureTest {
    @Test
    fun acceptsDiverseSharpTargetEvidence() {
        val probe = CalibrationTargetCaptureProbe()
        val views = listOf(-30.0 to -15.0, -15.0 to 15.0, 0.0 to -15.0,
            15.0 to 15.0, 30.0 to 0.0, 0.0 to 30.0)
        repeat(12) { index ->
            val (azimuth, elevation) = views[index % views.size]
            val normal = Vector3(tan(Math.toRadians(azimuth)),
                tan(Math.toRadians(elevation)), 1.0)
            assertTrue(probe.add(CalibrationTargetObservation(
                measuredDistanceMeters = 1.002 + (index % 2) * 0.001,
                referenceDistanceMeters = 1.0,
                referenceUncertaintyMeters = 0.001,
                targetCenterDistanceMeters = if (index < 6) 0.7 else 1.3,
                targetNormalCamera = normal,
                detectedCorners = 48,
                imageCoverageRatio = 0.20,
                sharpnessScore = 0.8,
                reprojectionRmsePixels = 0.4 + (index % 3) * 0.1,
            )))
        }

        val report = probe.finish()
        assertTrue(report.reasons.toString(), report.observable)
        assertTrue(report.viewBins >= 5)
        assertTrue(report.distanceBins >= 2)
        assertNotNull(report.measurementEvidence(true, true, true))
    }

    @Test
    fun rejectsLowQualityAndRepetitiveCapture() {
        val probe = CalibrationTargetCaptureProbe()
        repeat(12) {
            probe.add(CalibrationTargetObservation(1.08, 1.0, 0.001, 0.7,
                Vector3(0.0, 0.0, 1.0), 48, 0.20, 0.8, 0.5))
        }
        assertFalse(probe.add(CalibrationTargetObservation(1.0, 1.0, 0.001, 0.7,
            Vector3(0.0, 0.0, 1.0), 3, 0.01, 0.01, 5.0)))

        val report = probe.finish()
        assertTrue(CalibrationTargetReason.INSUFFICIENT_VIEW_DIVERSITY in report.reasons)
        assertTrue(CalibrationTargetReason.INSUFFICIENT_DISTANCE_DIVERSITY in report.reasons)
        assertTrue(CalibrationTargetReason.DIMENSIONAL_ERROR_TOO_HIGH in report.reasons)
        assertTrue(report.measurementEvidence(true, true, true) == null)
    }
}
