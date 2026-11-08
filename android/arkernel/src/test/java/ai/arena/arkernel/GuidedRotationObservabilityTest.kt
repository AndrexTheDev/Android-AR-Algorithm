package ai.arena.arkernel

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedRotationObservabilityTest {
    @Test
    fun acceptsSignedMultiAxisCameraGyroAgreement() {
        val probe = GuidedRotationObservabilityProbe()
        repeat(18) { index ->
            val axis = when (index % 3) {
                0 -> Vector3(1.0, 0.0, 0.0)
                1 -> Vector3(0.0, 1.0, 0.0)
                else -> Vector3(0.0, 0.0, 1.0)
            }
            val rotation = axisAngle(axis, 0.03)
            assertTrue(probe.addMatchedRotation(rotation, rotation))
        }

        val report = probe.finish()
        assertTrue(report.passed)
        assertEquals(18, report.matchedRotations)
        assertEquals(1.0, report.directionCosine, 1e-12)
        assertEquals(1.0, report.magnitudeRatio, 1e-12)
        assertFalse(probe.addMatchedRotation(axisAngle(Vector3(1.0, 0.0, 0.0), 0.1),
            axisAngle(Vector3(1.0, 0.0, 0.0), 0.1)))
    }

    @Test
    fun rejectsAxisSignErrorEvenWhenMagnitudesMatch() {
        val probe = GuidedRotationObservabilityProbe()
        repeat(18) {
            probe.addMatchedRotation(axisAngle(Vector3(0.0, 1.0, 0.0), 0.03),
                axisAngle(Vector3(0.0, -1.0, 0.0), 0.03))
        }

        val report = probe.finish()
        assertTrue(RotationObservabilityReason.CAMERA_GYRO_DIRECTION_DISAGREES in report.reasons)
        assertFalse(RotationObservabilityReason.CAMERA_GYRO_MAGNITUDE_DISAGREES in report.reasons)
    }

    @Test
    fun rejectsUnobservableMotionAndScaleError() {
        val probe = GuidedRotationObservabilityProbe()
        repeat(4) {
            probe.addMatchedRotation(axisAngle(Vector3(1.0, 0.0, 0.0), 0.01),
                axisAngle(Vector3(1.0, 0.0, 0.0), 0.02))
        }

        val report = probe.finish()
        assertTrue(RotationObservabilityReason.INSUFFICIENT_MATCHED_ROTATIONS in report.reasons)
        assertTrue(RotationObservabilityReason.INSUFFICIENT_GUIDED_MOTION in report.reasons)
        assertTrue(RotationObservabilityReason.CAMERA_GYRO_MAGNITUDE_DISAGREES in report.reasons)
    }

    private fun axisAngle(axis: Vector3, radians: Double): Rotation {
        val sine = sin(radians / 2.0)
        return Rotation(cos(radians / 2.0), axis.x * sine, axis.y * sine, axis.z * sine)
    }
}
