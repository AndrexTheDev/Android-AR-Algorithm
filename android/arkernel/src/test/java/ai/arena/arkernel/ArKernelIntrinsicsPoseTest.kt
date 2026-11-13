package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Checks the translation, which only exists once a square has been measured.
 *
 * The intrinsics do not care how big the squares are - scale cancels out of
 * them, which is why a printed sheet suffices. The translation does care: a
 * small board held close and a large board held far away put every corner in
 * exactly the same place, and no arithmetic on those corners distinguishes
 * them. So the distance to the board is recoverable *if and only if* one
 * square edge was measured, and these tests check both halves of that:
 *
 * * when the size is given, the recovered distances match the ones the board
 *   was rendered at, which makes the calibration checkable with a tape
 *   measure rather than merely believable;
 * * when it is not given, nothing at all is reported in metres - not a
 *   guess, not a zero, not a distance in squares.
 *
 * The board's origin is its first corner, because that is the origin the fit
 * uses; a test that measured to the centre of the board would be comparing
 * against a point nobody claimed to know.
 */
class ArKernelIntrinsicsPoseTest {

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val COLUMNS = 9
        const val ROWS = 7

        const val FX = 1400.0
        const val FY = 1400.0
        const val CX = 960.0
        const val CY = 540.0
        const val K1 = -0.18
        const val K2 = 0.04

        /** A real printed square: four centimetres along the edge. */
        const val SQUARE = 0.04

        /** Roughly how far the board is held, in metres. */
        const val DISTANCE = 0.6

        private fun rotation(
            xDeg: Double,
            yDeg: Double,
            zDeg: Double,
        ): Array<DoubleArray> {
            val ax = Math.toRadians(xDeg)
            val ay = Math.toRadians(yDeg)
            val az = Math.toRadians(zDeg)
            val rx = arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(0.0, cos(ax), -sin(ax)),
                doubleArrayOf(0.0, sin(ax), cos(ax)),
            )
            val ry = arrayOf(
                doubleArrayOf(cos(ay), 0.0, sin(ay)),
                doubleArrayOf(0.0, 1.0, 0.0),
                doubleArrayOf(-sin(ay), 0.0, cos(ay)),
            )
            val rz = arrayOf(
                doubleArrayOf(cos(az), -sin(az), 0.0),
                doubleArrayOf(sin(az), cos(az), 0.0),
                doubleArrayOf(0.0, 0.0, 1.0),
            )
            return mul(mul(rz, ry), rx)
        }

        private fun mul(
            a: Array<DoubleArray>,
            b: Array<DoubleArray>,
        ): Array<DoubleArray> {
            val out = Array(3) { DoubleArray(3) }
            for (i in 0..2) {
                for (j in 0..2) {
                    out[i][j] = a[i][0] * b[0][j] +
                        a[i][1] * b[1][j] + a[i][2] * b[2][j]
                }
            }
            return out
        }
    }

    /** One rendered view plus the distance it was rendered at. */
    private class Truth(
        val points: List<ArKernelIntrinsicsPoint>,
        val tX: Double,
        val tY: Double,
        val tZ: Double,
    ) {
        val distance: Double get() = sqrt(tX * tX + tY * tY + tZ * tZ)
    }

    /**
     * Renders the board from spread-out directions. Grid corner (0, 0) sits
     * at `t`, so `|t|` is the distance to the origin the fit recovers.
     */
    private fun synthesise(
        viewCount: Int = 8,
        seed: Long = 21L,
        noiseSigmaPx: Double = 0.0,
        distance: Double = DISTANCE,
        squareSize: Double = SQUARE,
    ): List<Truth> {
        val random = java.util.Random(seed)
        val truths = ArrayList<Truth>(viewCount)
        for (view in 0 until viewCount) {
            val r = rotation(
                (random.nextDouble() - 0.5) * 50.0,
                (random.nextDouble() - 0.5) * 50.0,
                (random.nextDouble() - 0.5) * 30.0,
            )
            val tx = (random.nextDouble() - 0.5) * 0.10
            val ty = (random.nextDouble() - 0.5) * 0.10
            val tz = distance * (0.85 + 0.3 * random.nextDouble())
            val points = ArrayList<ArKernelIntrinsicsPoint>(COLUMNS * ROWS)
            for (row in 0 until ROWS) {
                for (column in 0 until COLUMNS) {
                    val bx = column * squareSize
                    val by = row * squareSize
                    val px = r[0][0] * bx + r[0][1] * by + tx
                    val py = r[1][0] * bx + r[1][1] * by + ty
                    val pz = r[2][0] * bx + r[2][1] * by + tz
                    assertTrue("target behind camera", pz > 0.0)
                    val xn = px / pz
                    val yn = py / pz
                    val r2 = xn * xn + yn * yn
                    val factor = 1.0 + K1 * r2 + K2 * r2 * r2
                    points += ArKernelIntrinsicsPoint(
                        xPx = FX * xn * factor + CX +
                            random.nextGaussian() * noiseSigmaPx,
                        yPx = FY * yn * factor + CY +
                            random.nextGaussian() * noiseSigmaPx,
                    )
                }
            }
            truths += Truth(points, tx, ty, tz)
        }
        return truths
    }

    private fun estimate(
        truths: List<Truth>,
        squareSize: Double? = SQUARE,
        noiseSigmaPx: Double = 0.0,
    ): ArKernelIntrinsicsEstimate = ArKernelIntrinsicsEstimator.estimate(
        views = truths.map { ArKernelIntrinsicsView(it.points) },
        gridColumns = COLUMNS,
        gridRows = ROWS,
        imageWidthPx = WIDTH,
        imageHeightPx = HEIGHT,
        squareSizeMeters = squareSize,
    ).also { check(noiseSigmaPx == 0.0) }

    // ------------------------------------------------------- with a measured square

    @Test
    fun theDistanceComesBackWhenOneSquareIsMeasured() {
        val truths = synthesise()
        val estimate = estimate(truths)
        assertTrue(estimate.detail, estimate.accepted)
        assertTrue("must be metric", estimate.metric)
        assertEquals(truths.size, estimate.boardPoses.size)
        for (index in truths.indices) {
            val expected = truths[index].distance
            val found = estimate.boardPoses[index].distanceMeters
            val error = abs(found - expected) / expected
            assertTrue(
                "view $index: distance $found m, expected $expected m " +
                    "(${error * 100} % off)",
                error < 0.03,
            )
        }
    }

    @Test
    fun theTranslationDirectionIsRightToo() {
        // Distance alone would be satisfied by any point on a sphere. The
        // axis along which the board lies is the part that makes the pose
        // usable, and the z component is the one a tape measure checks.
        val truths = synthesise()
        val estimate = estimate(truths)
        assertTrue(estimate.detail, estimate.accepted)
        for (index in truths.indices) {
            val pose = estimate.boardPoses[index]
            val expectedZ = truths[index].tZ
            val error = abs(pose.tZMeters - expectedZ) / expectedZ
            assertTrue(
                "view $index: tz ${pose.tZMeters} m, expected " +
                    "$expectedZ m (${error * 100} % off)",
                error < 0.05,
            )
        }
    }

    @Test
    fun everyTranslationIsPositiveDistanceInFrontOfTheCamera() {
        // A board behind the camera is the classic sign ambiguity of pose
        // recovery, and a negative z is what it looks like from the outside.
        val truths = synthesise(viewCount = 10, seed = 99L)
        val estimate = estimate(truths)
        assertTrue(estimate.detail, estimate.accepted)
        for (pose in estimate.boardPoses) {
            assertTrue(
                "board must be in front: z = ${pose.tZMeters} m",
                pose.tZMeters > 0.0,
            )
            assertTrue(
                "distance must be positive: ${pose.distanceMeters} m",
                pose.distanceMeters > 0.0,
            )
        }
    }

    @Test
    fun theDistanceSurvivesSensorNoise() {
        val truths = synthesise(noiseSigmaPx = 0.4)
        val estimate = estimate(truths)
        assertTrue(estimate.detail, estimate.accepted)
        for (index in truths.indices) {
            val expected = truths[index].distance
            val found = estimate.boardPoses[index].distanceMeters
            val error = abs(found - expected) / expected
            assertTrue(
                "view $index under noise: $found m vs $expected m",
                error < 0.05,
            )
        }
    }

    @Test
    fun aBiggerSquareAtTheSameImageGivesABiggerDistance() {
        // The only thing that separates these two scenes is the measured
        // edge: rendered at a larger size and proportionally further away,
        // they produce the same picture to within rounding. Whoever measures
        // the square decides which world it was.
        val small = synthesise(squareSize = 0.03, distance = 0.45)
        val large = synthesise(squareSize = 0.06, distance = 0.90)
        val smallEstimate = estimate(small, squareSize = 0.03)
        val largeEstimate = estimate(large, squareSize = 0.06)
        assertTrue(smallEstimate.detail, smallEstimate.accepted)
        assertTrue(largeEstimate.detail, largeEstimate.accepted)
        val smallMean = smallEstimate.distancesMeters.average()
        val largeMean = largeEstimate.distancesMeters.average()
        assertTrue(
            "small squares should read nearer: $smallMean m vs $largeMean m",
            smallMean < largeMean * 0.75,
        )
    }

    @Test
    fun anUnmeasuredBoardIsNotSilentlyGivenADistance() {
        // The important negative. Without a measured square the scale is
        // genuinely undetermined - not poorly determined - so anything here
        // in metres would be invented. The estimate must say it has nothing.
        val truths = synthesise()
        val estimate = estimate(truths, squareSize = null)
        assertTrue(estimate.detail, estimate.accepted)
        assertFalse("must not claim to be metric", estimate.metric)
        assertTrue(estimate.boardPoses.isEmpty())
        assertTrue(estimate.distancesMeters.isEmpty())
        assertEquals(null, estimate.squareSizeMeters)
    }

    @Test
    fun anImplausibleSquareIsRefusedRatherThanScaled() {
        // A typo in the millimetre field would otherwise produce a distance
        // of eight kilometres and a residual that is perfectly happy.
        val truths = synthesise()
        for (bad in listOf(0.0, -0.04, 50.0, Double.NaN)) {
            val estimate = estimate(truths, squareSize = bad)
            assertFalse(
                "square size $bad must be refused",
                estimate.accepted,
            )
        }
    }

    @Test
    fun aRefusedCameraQuotesNoDistanceAtAll() {
        // Order matters: the poses are computed from the fitted camera, so a
        // rejected fit has nothing to scale. A distance beside a rejection
        // would be a number with no derivation behind it.
        val single = synthesise(viewCount = 2)
        val estimate = estimate(single)
        assertFalse(estimate.accepted)
        assertTrue(estimate.boardPoses.isEmpty())
        assertFalse(estimate.metric)
    }
}
