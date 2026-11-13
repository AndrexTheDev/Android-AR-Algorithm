package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The estimator is checked against runs built from **known** rotations, with
 * the focal length never handed to it.
 *
 * That is the whole point of the test design. The focal length falls out of
 * the unit-norm constraint, so a test that supplied it would prove nothing;
 * a test that withholds it and still recovers the rotation proves the thing
 * that is actually claimed.
 */
class ArKernelCameraImuRotationTest {

    companion object {

        /** Never passed to the estimator. It has to come back on its own. */
        private const val FOCAL_PX = 2_800.0
        private const val SAMPLES = 420

        private fun rotationAbout(axis: Char, angle: Double): Array<DoubleArray> {
            val c = cos(angle)
            val s = sin(angle)
            return when (axis) {
                'x' -> arrayOf(
                    doubleArrayOf(1.0, 0.0, 0.0),
                    doubleArrayOf(0.0, c, -s),
                    doubleArrayOf(0.0, s, c))
                'y' -> arrayOf(
                    doubleArrayOf(c, 0.0, s),
                    doubleArrayOf(0.0, 1.0, 0.0),
                    doubleArrayOf(-s, 0.0, c))
                else -> arrayOf(
                    doubleArrayOf(c, -s, 0.0),
                    doubleArrayOf(s, c, 0.0),
                    doubleArrayOf(0.0, 0.0, 1.0))
            }
        }

        private fun multiply(
            left: Array<DoubleArray>,
            right: Array<DoubleArray>,
        ): Array<DoubleArray> {
            val out = Array(3) { DoubleArray(3) }
            for (row in 0..2) {
                for (column in 0..2) {
                    var total = 0.0
                    for (index in 0..2) {
                        total += left[row][index] * right[index][column]
                    }
                    out[row][column] = total
                }
            }
            return out
        }

        private fun matrixOf(matrix: Array<DoubleArray>): ArKernelCameraImuMatrix =
            ArKernelCameraImuMatrix(
                ArKernelCameraImuVector(
                    matrix[0][0], matrix[0][1], matrix[0][2]),
                ArKernelCameraImuVector(
                    matrix[1][0], matrix[1][1], matrix[1][2]),
                ArKernelCameraImuVector(
                    matrix[2][0], matrix[2][1], matrix[2][2]))

        private class Run(
            val rateX: DoubleArray,
            val rateY: DoubleArray,
            val gyroX: DoubleArray,
            val gyroY: DoubleArray,
            val gyroZ: DoubleArray,
        )

        /**
         * Builds a run from a known rotation and a known focal length.
         *
         * Only the gyroscope rate and the resulting image rates are handed to
         * the estimator; `focal` stays inside this function.
         */
        private fun synthetic(
            rotation: Array<DoubleArray>,
            focal: Double = FOCAL_PX,
            samples: Int = SAMPLES,
            seed: Long = 9_873L,
            noise: Double = 0.0,
            exciteZ: Boolean = true,
        ): Run {
            var state = seed
            fun next(): Double {
                state = (state * 1_103_515_245L + 12_345L) and 0x7FFFFFFFL
                return state / 0x7FFFFFFFL.toDouble() * 2.0 - 1.0
            }
            val gyroX = DoubleArray(samples)
            val gyroY = DoubleArray(samples)
            val gyroZ = DoubleArray(samples)
            val rateX = DoubleArray(samples)
            val rateY = DoubleArray(samples)
            for (index in 0 until samples) {
                val wx = next()
                val wy = next()
                val wz = if (exciteZ) next() else 0.0
                gyroX[index] = wx
                gyroY[index] = wy
                gyroZ[index] = wz
                // omega_camera = R * omega_imu
                val cx = rotation[0][0] * wx +
                    rotation[0][1] * wy + rotation[0][2] * wz
                val cy = rotation[1][0] * wx +
                    rotation[1][1] * wy + rotation[1][2] * wz
                // x-dot = -f * w_y and y-dot = +f * w_x, near the axis.
                rateX[index] = -focal * cy + if (noise > 0.0) {
                    next() * noise
                } else {
                    0.0
                }
                rateY[index] = focal * cx + if (noise > 0.0) {
                    next() * noise
                } else {
                    0.0
                }
            }
            return Run(rateX, rateY, gyroX, gyroY, gyroZ)
        }

        private fun recover(run: Run): ArKernelCameraImuRotationResult =
            ArKernelCameraImuRotation.recover(
                rateX = run.rateX,
                rateY = run.rateY,
                gyroX = run.gyroX,
                gyroY = run.gyroY,
                gyroZ = run.gyroZ)

        private fun assertRowsClose(
            expected: Array<DoubleArray>,
            actual: ArKernelCameraImuMatrix,
            tolerance: Double = 0.01,
        ) {
            val want = matrixOf(expected)
            for (value in arrayOf(
                want.rowX.x to actual.rowX.x,
                want.rowX.y to actual.rowX.y,
                want.rowX.z to actual.rowX.z,
                want.rowY.x to actual.rowY.x,
                want.rowY.y to actual.rowY.y,
                want.rowY.z to actual.rowY.z,
                want.rowZ.x to actual.rowZ.x,
                want.rowZ.y to actual.rowZ.y,
                want.rowZ.z to actual.rowZ.z)
            ) {
                assertEquals(
                    "row component", value.first, value.second, tolerance)
            }
        }
    }

    @Test
    fun aQuarterTurnAboutZIsRecovered() {
        val rotation = rotationAbout('z', PI / 2.0)
        val result = recover(synthetic(rotation))
        assertTrue("${result.conditioningPermille}", result.observable)
        val matrix = result.rotation
        assertNotNull("rotation", matrix)
        assertRowsClose(rotation, matrix!!)
    }

    @Test
    fun aRotationAboutXIsRecovered() {
        val rotation = rotationAbout('x', 0.4)
        val result = recover(synthetic(rotation))
        assertTrue(result.observable)
        assertRowsClose(rotation, result.rotation!!)
    }

    @Test
    fun aRotationAboutYIsRecovered() {
        val rotation = rotationAbout('y', -0.7)
        val result = recover(synthetic(rotation))
        assertTrue(result.observable)
        assertRowsClose(rotation, result.rotation!!)
    }

    @Test
    fun aCombinedRotationIsRecovered() {
        val rotation = multiply(
            rotationAbout('z', 0.9),
            multiply(rotationAbout('y', -0.3), rotationAbout('x', 0.5)))
        val result = recover(synthetic(rotation))
        assertTrue(result.observable)
        assertRowsClose(rotation, result.rotation!!)
    }

    @Test
    fun theFocalLengthComesBackWithoutBeingSupplied() {
        val rotation = rotationAbout('y', 0.25)
        val result = recover(synthetic(rotation))
        assertTrue(result.observable)
        // FOCAL_PX was never given to the estimator; the unit-norm constraint
        // is the only thing that can produce it.
        assertEquals(FOCAL_PX, result.focalPxHorizontal, 1.0)
        assertEquals(FOCAL_PX, result.focalPxVertical, 1.0)
    }

    @Test
    fun theTwoFocalEstimatesAgree() {
        val result = recover(synthetic(rotationAbout('x', 0.6)))
        assertTrue(result.observable)
        assertTrue("split ${result.focalSplitPermille}",
            result.focalSplitPermille <=
                ArKernelCameraImuRotation.MAX_FOCAL_SPLIT_PERMILLE)
    }

    @Test
    fun aDifferentFocalLengthIsRecoveredToo() {
        // Guards against a test that would pass on one hard-coded value.
        val result = recover(synthetic(rotationAbout('z', 0.3), focal = 1_150.0))
        assertEquals(1_150.0, result.focalPxMean, 1.0)
    }

    @Test
    fun twoAxesAreNotEnoughToBeObservable() {
        // A three-component row cannot be recovered from a two-dimensional
        // excitation: the component along the unexcited axis is never seen.
        val run = synthetic(rotationAbout('y', 0.5), exciteZ = false)
        val result = recover(run)
        assertFalse(result.observable)
        assertFalse(result.extrinsicsSufficient)
        assertEquals(listOf("motion"), result.outstandingSteps())
    }

    @Test
    fun aWrongSignWouldStillBeAValidRotation() {
        // The permanent guard for the trap: flipping both recovered rows
        // leaves an orthonormal matrix with determinant +1, so no internal
        // consistency check can catch a sign error. Only the physics does.
        val rotation = matrixOf(rotationAbout('z', PI / 2.0))
        val flipped = ArKernelCameraImuMatrix(
            rotation.rowX.negate(), rotation.rowY.negate(), rotation.rowZ)
        assertTrue("the flipped matrix must be a valid rotation, " +
            "which is why only the tests can catch a sign error",
            flipped.isProperRotation())
    }

    @Test
    fun tooFewSamplesIsUnobservable() {
        val run = synthetic(rotationAbout('x', 0.2), samples = 3)
        assertFalse(recover(run).observable)
    }

    @Test
    fun theRecoveredMatrixIsAProperRotation() {
        val result = recover(synthetic(rotationAbout('x', 0.35)))
        val matrix = result.rotation
        assertNotNull("rotation", matrix)
        assertTrue(matrix!!.isProperRotation())
        assertEquals(1.0, matrix.determinant(), 0.02)
        assertTrue(result.rotationOk)
    }

    @Test
    fun theThirdRowIsTheCrossProductOfTheFirstTwo() {
        // Roll is inferred, not measured. This pins how.
        val result = recover(synthetic(rotationAbout('z', 1.1)))
        val matrix = result.rotation!!
        val cross = ArKernelCameraImuVector.cross(matrix.rowX, matrix.rowY)
        assertEquals(cross.x, matrix.rowZ.x, 0.01)
        assertEquals(cross.y, matrix.rowZ.y, 0.01)
        assertEquals(cross.z, matrix.rowZ.z, 0.01)
    }

    @Test
    fun noiseDegradesTheEstimateButNotItsSign() {
        // Noise a third of the signal: the run should still be accepted and
        // the recovered matrix should still be closer to the truth than to its
        // own negation.
        val rotation = rotationAbout('y', 0.8)
        val result = recover(synthetic(rotation, noise = FOCAL_PX / 3.0))
        assertTrue(result.observable)
        val matrix = result.rotation!!
        val truth = matrixOf(rotation)
        val towards = matrix.rowX.x * truth.rowX.x +
            matrix.rowX.y * truth.rowX.y + matrix.rowX.z * truth.rowX.z
        assertTrue("sign flipped: dot $towards", towards > 0.0)
    }

    @Test
    fun theRecoveredRotationMapsGyroOntoImageRates() {
        // Independent of how it was fitted: R must reproduce the rates.
        val rotation = rotationAbout('x', 0.45)
        val run = synthetic(rotation)
        val result = recover(run)
        val matrix = result.rotation!!
        val focal = result.focalPxMean
        for (index in 0 until 40) {
            val cx = matrix.rowX.x * run.gyroX[index] +
                matrix.rowX.y * run.gyroY[index] +
                matrix.rowX.z * run.gyroZ[index]
            val cy = matrix.rowY.x * run.gyroX[index] +
                matrix.rowY.y * run.gyroY[index] +
                matrix.rowY.z * run.gyroZ[index]
            assertEquals(-focal * cy, run.rateX[index], focal * 0.02)
            assertEquals(focal * cx, run.rateY[index], focal * 0.02)
        }
    }

    @Test
    fun aQuietRunReportsMotionRatherThanARotation() {
        // Nothing moved: there is no answer, and the step name has to say so
        // instead of blaming the rotation.
        val run = Run(
            rateX = DoubleArray(SAMPLES),
            rateY = DoubleArray(SAMPLES),
            gyroX = DoubleArray(SAMPLES),
            gyroY = DoubleArray(SAMPLES),
            gyroZ = DoubleArray(SAMPLES))
        val result = recover(run)
        assertFalse(result.observable)
        assertEquals(listOf("motion"), result.outstandingSteps())
    }
}
