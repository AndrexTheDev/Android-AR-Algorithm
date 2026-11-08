package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImuSampleSynchronizerTest {
    @Test
    fun interpolatesBothStreamsAtStrictCommonTimes() {
        val synchronizer = ImuSampleSynchronizer(maximumInterpolationGapNs = 20_000_000)
        assertNull(synchronizer.add(ImuTimingSampleKind.ACCELEROMETER, 100_000_000,
            vector(0.0)))
        assertNull(synchronizer.add(ImuTimingSampleKind.GYROSCOPE, 105_000_000,
            vector(10.0)))

        val first = requireNotNull(synchronizer.add(ImuTimingSampleKind.ACCELEROMETER,
            110_000_000, vector(10.0)))
        assertEquals(105_000_000, first.timestampNs)
        assertVector(5.0, first.acceleration)
        assertVector(10.0, first.angularVelocity)

        val second = requireNotNull(synchronizer.add(ImuTimingSampleKind.GYROSCOPE,
            115_000_000, vector(20.0)))
        assertEquals(110_000_000, second.timestampNs)
        assertVector(10.0, second.acceleration)
        assertVector(15.0, second.angularVelocity)
    }

    @Test
    fun rejectsOutOfOrderInputWithoutReplacingLatestSample() {
        val synchronizer = ImuSampleSynchronizer()
        synchronizer.add(ImuTimingSampleKind.ACCELEROMETER, 100, vector(1.0))
        synchronizer.add(ImuTimingSampleKind.GYROSCOPE, 105, vector(2.0))
        assertNull(synchronizer.add(ImuTimingSampleKind.GYROSCOPE, 104, vector(99.0)))
        val output = requireNotNull(synchronizer.add(ImuTimingSampleKind.ACCELEROMETER,
            110, vector(3.0)))
        assertVector(2.0, output.angularVelocity)
    }

    @Test
    fun refusesInterpolationAcrossExcessiveGap() {
        val synchronizer = ImuSampleSynchronizer(maximumInterpolationGapNs = 10_000_000)
        synchronizer.add(ImuTimingSampleKind.ACCELEROMETER, 100_000_000, vector(0.0))
        synchronizer.add(ImuTimingSampleKind.GYROSCOPE, 105_000_000, vector(0.0))
        assertNull(synchronizer.add(ImuTimingSampleKind.ACCELEROMETER,
            200_000_000, vector(1.0)))
    }

    @Test
    fun resetRequiresFreshBracketingSamples() {
        val synchronizer = ImuSampleSynchronizer()
        synchronizer.add(ImuTimingSampleKind.ACCELEROMETER, 100, vector(0.0))
        synchronizer.add(ImuTimingSampleKind.GYROSCOPE, 105, vector(0.0))
        synchronizer.add(ImuTimingSampleKind.ACCELEROMETER, 110, vector(1.0))
        synchronizer.reset()
        assertNull(synchronizer.add(ImuTimingSampleKind.ACCELEROMETER, 200, vector(2.0)))
        assertNull(synchronizer.add(ImuTimingSampleKind.GYROSCOPE, 205, vector(2.0)))
    }

    private fun vector(value: Double) = Vector3(value, value, value)

    private fun assertVector(expected: Double, actual: Vector3) {
        assertEquals(expected, actual.x, 1e-12)
        assertEquals(expected, actual.y, 1e-12)
        assertEquals(expected, actual.z, 1e-12)
    }
}
