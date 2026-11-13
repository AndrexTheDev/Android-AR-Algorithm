package ai.arena.arkernel

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Recovers the rotation between the camera and the IMU from a motion, and
 * gets the focal length out of the same motion for free.
 *
 * **Why this is possible without knowing the focal length.** The obvious
 * objection is that turning an image shift into an angle needs `f`, and `f`
 * is an intrinsic we do not have. It does not, for the same structural reason
 * the time offset did not: the unknown cancels.
 *
 * Near the optical axis a rotating camera moves content at
 * `x-dot = -f * w_y` and `y-dot = +f * w_x`. Regressing the horizontal image
 * rate onto the three gyroscope axes therefore returns `-f` times one row of
 * `R`, and the vertical regression returns `+f` times another. Every row of a
 * rotation matrix is a **unit vector**, so the norm of either coefficient
 * vector *is* the focal length, and dividing by it recovers the row exactly.
 * The scale is not a free parameter - orthonormality fixes it.
 *
 * **What is inferred, and what is not observable.** The third row is the cross
 * product of the two measured rows, because a rotation is right-handed. Roll
 * is therefore inferred rather than measured, and carries whatever error the
 * other two have.
 *
 * The translation `t` is **not** recovered at all. It shows up only through
 * where content sits at different depths, which needs position, and position
 * needs metric scale. Nothing here supplies either.
 *
 * **Two things fall out for free.** The norm is an implied focal length in
 * pixels. And the two regressions give two independent estimates of that same
 * quantity, sharing no arithmetic - so their disagreement is a real check,
 * and the value cross-checks the scale ladder, which reaches the same number
 * from a folding ruler at known distances.
 *
 * **A sign error is invisible to every internal check.** Flipping the sign of
 * both recovered rows leaves a matrix that is still orthonormal with
 * determinant +1. Nothing internal can catch it. The signs come from the
 * projection physics and from nothing else, and the tests pin them.
 */
object ArKernelCameraImuRotation {

    /**
     * Weakest conditioning of the normal equations that counts as observable,
     * in permille.
     *
     * A motion about a single axis leaves the components along the other two
     * invisible however clean the data is, so this is a completeness gate and
     * not a quality threshold.
     */
    const val MIN_CONDITIONING_PERMILLE = 50

    /** How far the two focal estimates may disagree, in permille. */
    const val MAX_FOCAL_SPLIT_PERMILLE = 200

    /** Floor for the split denominator, so a degenerate run cannot divide by
     * a focal length of zero and report a split of infinity. */
    private const val MAX_FOCAL_SPLIT_WORST = 1_000

    fun recover(
        rateX: DoubleArray,
        rateY: DoubleArray,
        gyroX: DoubleArray,
        gyroY: DoubleArray,
        gyroZ: DoubleArray,
    ): ArKernelCameraImuRotationResult {
        val count = minOf(
            rateX.size, rateY.size,
            gyroX.size, gyroY.size, gyroZ.size)
        if (count < 4) return unobservable(count)
        val design = arrayOf(gyroX, gyroY, gyroZ)
        val horizontal = solve(rateX, design, count)
            ?: return unobservable(count)
        val vertical = solve(rateY, design, count)
            ?: return unobservable(count)
        val conditioning = minOf(
            horizontal.conditioningPermille,
            vertical.conditioningPermille)
        if (conditioning < MIN_CONDITIONING_PERMILLE) {
            return unobservable(
                count = count,
                conditioning = conditioning,
                focalHorizontal = horizontal.norm,
                focalVertical = vertical.norm,
                residual = maxOf(
                    horizontal.residualPermille,
                    vertical.residualPermille),
                correlation = minOf(
                    horizontal.correlationPermille,
                    vertical.correlationPermille))
        }

        // Signs come from the projection, not from convention. Getting either
        // the wrong way round yields a perfectly valid rotation matrix that is
        // quietly wrong, and no internal check can see it.
        val rowY = horizontal.unit()?.negate()
        val rowX = vertical.unit()
        if (rowX == null || rowY == null) {
            return unobservable(count, conditioning)
        }
        val rowZ = ArKernelCameraImuVector.cross(rowX, rowY)
        val rotation = ArKernelCameraImuMatrix(rowX, rowY, rowZ)

        val focalHorizontal = horizontal.norm
        val focalVertical = vertical.norm
        val mean = (focalHorizontal + focalVertical) / 2.0
        val split = if (mean > 0.0) {
            ((abs(focalHorizontal - focalVertical) / mean) * 1_000.0)
                .toLong().coerceIn(0L, MAX_FOCAL_SPLIT_WORST.toLong()).toInt()
        } else {
            MAX_FOCAL_SPLIT_WORST
        }

        return ArKernelCameraImuRotationResult(
            sampleCount = count,
            conditioningPermille = conditioning,
            observable = true,
            rotation = rotation,
            focalPxHorizontal = focalHorizontal,
            focalPxVertical = focalVertical,
            focalSplitPermille = split,
            residualPermille = maxOf(
                horizontal.residualPermille,
                vertical.residualPermille),
            correlationPermille = minOf(
                horizontal.correlationPermille,
                vertical.correlationPermille))
    }

    private fun unobservable(
        count: Int,
        conditioning: Int = 0,
        focalHorizontal: Double = 0.0,
        focalVertical: Double = 0.0,
        residual: Int = 0,
        correlation: Int = 0,
    ): ArKernelCameraImuRotationResult =
        ArKernelCameraImuRotationResult(
            sampleCount = count,
            conditioningPermille = conditioning,
            observable = false,
            rotation = null,
            focalPxHorizontal = focalHorizontal,
            focalPxVertical = focalVertical,
            residualPermille = residual,
            correlationPermille = correlation)

    /**
     * Ordinary least squares of `response` onto three regressors.
     *
     * Solved through the normal equations with partial pivoting rather than by
     * inverting the Gram matrix outright: the regressors here are gyroscope
     * axes during a hand-held motion, and they are correlated enough that an
     * unguarded solve would quietly return numbers for a system that has no
     * answer.
     */
    private fun solve(
        response: DoubleArray,
        design: Array<DoubleArray>,
        count: Int,
    ): ArKernelCameraImuFit? {
        val gram = Array(3) { DoubleArray(3) }
        val moment = DoubleArray(3)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                var total = 0.0
                for (index in 0 until count) {
                    total += design[row][index] * design[column][index]
                }
                gram[row][column] = total
            }
            var total = 0.0
            for (index in 0 until count) {
                total += design[row][index] * response[index]
            }
            moment[row] = total
        }
        var responseEnergy = 0.0
        for (index in 0 until count) {
            responseEnergy += response[index] * response[index]
        }
        if (responseEnergy <= 0.0) return null

        // Partial pivoting. A zero pivot means one regressor is a combination
        // of the others, which is exactly the one-sided-motion case.
        val pivot = IntArray(3)
        var scale = 1.0
        for (step in 0 until 3) {
            var best = step
            var bestMagnitude = abs(gram[step][step])
            for (candidate in step + 1 until 3) {
                val magnitude = abs(gram[candidate][step])
                if (magnitude > bestMagnitude) {
                    bestMagnitude = magnitude
                    best = candidate
                }
            }
            pivot[step] = best
            if (best != step) {
                val swapRow = gram[step]
                gram[step] = gram[best]
                gram[best] = swapRow
                val swapMoment = moment[step]
                moment[step] = moment[best]
                moment[best] = swapMoment
            }
            val diagonal = gram[step][step]
            if (abs(diagonal) <= 1e-12) return null
            scale *= diagonal
            for (row in step + 1 until 3) {
                val factor = gram[row][step] / diagonal
                gram[row][step] = 0.0
                for (column in step + 1 until 3) {
                    gram[row][column] -= factor * gram[step][column]
                }
                moment[row] -= factor * moment[step]
            }
        }

        val beta = DoubleArray(3)
        for (row in 2 downTo 0) {
            var total = moment[row]
            for (column in row + 1 until 3) {
                total -= gram[row][column] * beta[column]
            }
            beta[row] = total / gram[row][row]
        }

        var residualEnergy = 0.0
        for (index in 0 until count) {
            var predicted = 0.0
            for (row in 0 until 3) {
                predicted += beta[row] * design[row][index]
            }
            val error = response[index] - predicted
            residualEnergy += error * error
        }
        val residualPermille = ((sqrt(residualEnergy / responseEnergy))
            .coerceIn(0.0, 1.0) * 1_000.0).toLong().toInt()
        val fit = 1.0 - residualEnergy / responseEnergy
        val correlationPermille = (fit.coerceIn(0.0, 1.0) * 1_000.0)
            .toLong().toInt()

        // Conditioning: how far the Gram matrix is from singular, as a
        // fraction of its own scale. Reported in permille so the parser can
        // recompute it in integers.
        var gramScale = 0.0
        for (row in 0 until 3) {
            gramScale += abs(gram[row][row])
        }
        val conditioningPermille = if (gramScale > 0.0) {
            ((abs(scale) / (gramScale * gramScale * gramScale / 27.0))
                .coerceIn(0.0, 1.0) * 1_000.0).toLong().toInt()
        } else {
            0
        }

        return ArKernelCameraImuFit(
            beta = beta,
            norm = sqrt(
                beta[0] * beta[0] + beta[1] * beta[1] + beta[2] * beta[2]),
            conditioningPermille = conditioningPermille,
            residualPermille = residualPermille,
            correlationPermille = correlationPermille)
    }

    private class ArKernelCameraImuFit(
        val beta: DoubleArray,
        val norm: Double,
        val conditioningPermille: Int,
        val residualPermille: Int,
        val correlationPermille: Int,
    ) {
        fun unit(): ArKernelCameraImuVector? {
            if (norm <= 0.0) return null
            return ArKernelCameraImuVector(
                beta[0] / norm, beta[1] / norm, beta[2] / norm)
        }
    }
}

/** A 3-vector. Deliberately tiny: it exists to keep the signs visible. */
data class ArKernelCameraImuVector(
    val x: Double,
    val y: Double,
    val z: Double,
) {

    fun negate(): ArKernelCameraImuVector =
        ArKernelCameraImuVector(-x, -y, -z)

    fun norm(): Double = sqrt(x * x + y * y + z * z)

    fun dot(other: ArKernelCameraImuVector): Double =
        x * other.x + y * other.y + z * other.z

    fun unit(): ArKernelCameraImuVector? {
        val length = norm()
        if (length <= 0.0) return null
        return ArKernelCameraImuVector(x / length, y / length, z / length)
    }

    companion object {
        fun cross(
            left: ArKernelCameraImuVector,
            right: ArKernelCameraImuVector,
        ): ArKernelCameraImuVector = ArKernelCameraImuVector(
            left.y * right.z - left.z * right.y,
            left.z * right.x - left.x * right.z,
            left.x * right.y - left.y * right.x)
    }
}

/**
 * A rotation from IMU axes to camera axes.
 *
 * Rows, not columns: `omega_camera = R * omega_imu`, so `rowX` dotted with the
 * gyroscope reading gives the camera-frame x component directly. That is also
 * the quantity the regression produces, so keeping rows keeps the algebra
 * honest.
 */
data class ArKernelCameraImuMatrix(
    val rowX: ArKernelCameraImuVector,
    val rowY: ArKernelCameraImuVector,
    val rowZ: ArKernelCameraImuVector,
) {

    /** Components in permille, row by row, for the text record. */
    fun componentsPermille(): IntArray {
        val values = doubleArrayOf(
            rowX.x, rowX.y, rowX.z,
            rowY.x, rowY.y, rowY.z,
            rowZ.x, rowZ.y, rowZ.z)
        return IntArray(9) { index ->
            (values[index] * 1_000.0).toLong()
                .coerceIn(-1_000L, 1_000L).toInt()
        }
    }

    fun determinant(): Double = rowX.x * (rowY.y * rowZ.z - rowY.z * rowZ.y) -
        rowX.y * (rowY.x * rowZ.z - rowY.z * rowZ.x) +
        rowX.z * (rowY.x * rowZ.y - rowY.y * rowZ.x)

    /**
     * Orthonormal rows and determinant +1.
     *
     * A reflection is orthonormal with determinant -1, so the determinant is
     * what separates a rotation from a mirror - and a mirror would quietly
     * produce a left-handed coordinate frame downstream.
     */
    fun isProperRotation(): Boolean {
        if (abs(determinant() - 1.0) > 0.02) return false
        val rows = arrayOf(rowX, rowY, rowZ)
        for (index in 0..2) {
            if (abs(rows[index].norm() - 1.0) > 0.02) return false
        }
        for (left in 0..2) {
            for (right in left + 1..2) {
                if (abs(rows[left].dot(rows[right])) > 0.02) return false
            }
        }
        return true
    }

    /** Roll, pitch and yaw in degrees, for a human reader. */
    fun eulerDegrees(): DoubleArray {
        val pitch = kotlin.math.asin(
            (-rowZ.x).coerceIn(-1.0, 1.0))
        val roll = kotlin.math.atan2(rowZ.y, rowZ.z)
        val yaw = kotlin.math.atan2(rowY.x, rowX.x)
        return doubleArrayOf(
            Math.toDegrees(roll),
            Math.toDegrees(pitch),
            Math.toDegrees(yaw))
    }
}

data class ArKernelCameraImuRotationResult(
    val sampleCount: Int,
    val conditioningPermille: Int,
    val observable: Boolean,
    val rotation: ArKernelCameraImuMatrix?,
    /** Focal length in pixels implied by the horizontal-motion regression. */
    val focalPxHorizontal: Double,
    /** Focal length in pixels implied by the vertical-motion regression. */
    val focalPxVertical: Double,
    /** How far the two focal estimates disagree, in permille. */
    val focalSplitPermille: Int = 0,
    val residualPermille: Int,
    val correlationPermille: Int,
) {

    val focalPxMean: Double
        get() = (focalPxHorizontal + focalPxVertical) / 2.0

    val rotationOk: Boolean
        get() = observable && rotation?.isProperRotation() == true

    val focalAgreementOk: Boolean
        get() = observable &&
            focalSplitPermille <=
                ArKernelCameraImuRotation.MAX_FOCAL_SPLIT_PERMILLE

    /**
     * A precondition gate, not a quality grade: the motion excited the axes
     * enough for a rotation to be identifiable, and the answer is a proper
     * rotation rather than a reflection. It does not say the rotation is the
     * true one.
     */
    val extrinsicsSufficient: Boolean
        get() = rotationOk && focalAgreementOk

    /** German-facing tokens naming what a failed run still needs. */
    fun outstandingSteps(): List<String> {
        if (!observable) return listOf("motion")
        val steps = ArrayList<String>()
        if (!rotationOk) steps.add("rotation")
        if (!focalAgreementOk) steps.add("focal")
        return steps
    }
}
