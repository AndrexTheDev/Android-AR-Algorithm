package ai.arena.arkernel

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedTimeOffsetObservabilityTest {
    @Test
    fun recoversUniqueOffsetFromChangingMultiAxisMotion() {
        val probe = GuidedTimeOffsetObservabilityProbe(identity(),
            TimeOffsetObservabilityConfig(minimumPeakMargin = 0.002))
        val origin = 10_000_000_000L
        val expectedOffset = 24_000_000L
        var timestamp = origin
        while (timestamp <= origin + 5_000_000_000L) {
            probe.addGyroscopeBody(timestamp, physicalAngularVelocity(timestamp, origin))
            timestamp += 5_000_000L
        }
        var previous = origin + 300_000_000L
        repeat(180) {
            val current = previous + 20_000_000L
            val midpointWithOffset = previous + 10_000_000L + expectedOffset
            val visualVelocity = negate(physicalAngularVelocity(midpointWithOffset, origin))
            probe.addVisualRotation(previous, current,
                rotationVector(visualVelocity, 0.020))
            previous = current
        }

        val report = probe.finish()
        assertTrue(report.reasons.toString(), report.observable)
        assertTrue(kotlin.math.abs(requireNotNull(report.visualToGyroscopeOffsetNs) -
            expectedOffset) <= 2_000_000L)
        assertTrue(report.bestCorrelation > 0.99)
        assertNotNull(report.offsetVarianceNsSquared)
        assertTrue(requireNotNull(report.offsetUncertaintyNs) >= 2_000_000L)
    }

    @Test
    fun rejectsConstantSingleAxisMotionAsUnobservable() {
        val probe = GuidedTimeOffsetObservabilityProbe(identity())
        val origin = 10_000_000_000L
        repeat(700) { index ->
            probe.addGyroscopeBody(origin + index * 5_000_000L, Vector3(0.0, 0.0, 0.5))
        }
        var previous = origin + 200_000_000L
        repeat(120) {
            val current = previous + 20_000_000L
            probe.addVisualRotation(previous, current,
                rotationVector(Vector3(0.0, 0.0, -0.5), 0.020))
            previous = current
        }

        val report = probe.finish()
        assertTrue(TimeOffsetObservabilityReason.INSUFFICIENT_ROTATIONAL_EXCITATION in report.reasons)
        assertTrue(TimeOffsetObservabilityReason.INSUFFICIENT_MULTI_AXIS_EXCITATION in report.reasons)
        assertEquals(null, report.visualToGyroscopeOffsetNs)
    }

    @Test
    fun rejectsPeakAtSearchBoundary() {
        val config = TimeOffsetObservabilityConfig(maximumAbsoluteOffsetNs = 40_000_000L,
            minimumPeakMargin = 0.0)
        val probe = GuidedTimeOffsetObservabilityProbe(identity(), config)
        val origin = 10_000_000_000L
        repeat(900) { index ->
            val timestamp = origin + index * 5_000_000L
            probe.addGyroscopeBody(timestamp, physicalAngularVelocity(timestamp, origin))
        }
        var previous = origin + 300_000_000L
        repeat(150) {
            val current = previous + 20_000_000L
            val velocity = negate(physicalAngularVelocity(previous + 10_000_000L +
                config.maximumAbsoluteOffsetNs, origin))
            probe.addVisualRotation(previous, current, rotationVector(velocity, 0.020))
            previous = current
        }

        val report = probe.finish()
        assertTrue(TimeOffsetObservabilityReason.OFFSET_AT_SEARCH_BOUNDARY in report.reasons)
        assertEquals(null, report.visualToGyroscopeOffsetNs)
    }

    private fun physicalAngularVelocity(timestampNs: Long, originNs: Long): Vector3 {
        val time = (timestampNs - originNs) * 1e-9
        return Vector3(0.7 * sin(4.3 * time) + 0.2 * sin(11.0 * time),
            0.6 * cos(5.7 * time + 0.3),
            0.5 * sin(7.9 * time + 0.8))
    }

    private fun rotationVector(angularVelocity: Vector3, durationSeconds: Double): Rotation {
        val x = angularVelocity.x * durationSeconds
        val y = angularVelocity.y * durationSeconds
        val z = angularVelocity.z * durationSeconds
        val angle = kotlin.math.sqrt(x * x + y * y + z * z)
        if (angle == 0.0) return identity()
        val scale = sin(angle / 2.0) / angle
        return Rotation(cos(angle / 2.0), x * scale, y * scale, z * scale)
    }

    private fun negate(value: Vector3) = Vector3(-value.x, -value.y, -value.z)
    private fun identity() = Rotation(1.0, 0.0, 0.0, 0.0)
}
