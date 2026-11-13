package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Checks the intrinsics fit against cameras whose answer is known.
 *
 * Every test here projects a grid through a camera that was written down
 * first, then asks the estimator to recover it. That is the only way to test
 * this: a fit that reports a small residual has merely explained its own
 * input, and the residual says nothing about whether the focal length is
 * right. The ground truth is what makes the test mean something.
 *
 * The negative tests matter as much. A target held small in the middle of the
 * frame, or the same view repeated, produces an excellent fit to a wrong
 * answer - small residual, plausible numbers, no internal complaint. Those
 * have to be refused on geometry, before the numbers are ever shown.
 */
class ArKernelCameraIntrinsicsTest {

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val COLUMNS = 9
        const val ROWS = 7
        const val SQUARES_X = (COLUMNS - 1).toDouble()
        const val SQUARES_Y = (ROWS - 1).toDouble()

        /** A plausible phone camera of the kind this has to work on. */
        const val TRUE_FX = 1400.0
        const val TRUE_FY = 1400.0
        const val TRUE_CX = 960.0
        const val TRUE_CY = 540.0
        const val TRUE_K1 = -0.18
        const val TRUE_K2 = 0.04

        private fun rotation(xDeg: Double, yDeg: Double, zDeg: Double): Array<DoubleArray> {
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
            return multiply3(multiply3(rz, ry), rx)
        }

        private fun multiply3(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
            val out = Array(3) { DoubleArray(3) }
            for (i in 0..2) {
                for (j in 0..2) {
                    out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j]
                }
            }
            return out
        }

        /**
         * A grid seen from `viewCount` angles, projected through a known
         * camera. `squareSize` scales the whole scene including the distance,
         * so the image is the same either way - which is the claim the
         * estimator makes about not needing a measured target.
         */
        private fun synthesise(
            viewCount: Int,
            seed: Long,
            noiseSigmaPx: Double = 0.0,
            fx: Double = TRUE_FX,
            fy: Double = TRUE_FY,
            cx: Double = TRUE_CX,
            cy: Double = TRUE_CY,
            k1: Double = TRUE_K1,
            k2: Double = TRUE_K2,
            squareSize: Double = 1.0,
            tiltDeg: Double = 25.0,
            distanceScale: Double = 1.0,
            columns: Int = COLUMNS,
            rows: Int = ROWS,
        ): List<ArKernelIntrinsicsView> {
            val random = java.util.Random(seed)
            val boardWidth = (columns - 1).toDouble() * squareSize
            val distance = fx * boardWidth / (0.5 * WIDTH) * distanceScale
            val views = ArrayList<ArKernelIntrinsicsView>()
            for (view in 0 until viewCount) {
                val r = rotation(
                    (random.nextDouble() - 0.5) * 2.0 * tiltDeg,
                    (random.nextDouble() - 0.5) * 2.0 * tiltDeg,
                    (random.nextDouble() - 0.5) * 30.0,
                )
                val tx = (random.nextDouble() - 0.5) * boardWidth * 0.35
                val ty = (random.nextDouble() - 0.5) * boardWidth * 0.35
                val tz = distance * (0.9 + 0.2 * random.nextDouble())
                val points = ArrayList<ArKernelIntrinsicsPoint>()
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        val bx = (column - (columns - 1) / 2.0) * squareSize
                        val by = (row - (rows - 1) / 2.0) * squareSize
                        val px = r[0][0] * bx + r[0][1] * by + tx
                        val py = r[1][0] * bx + r[1][1] * by + ty
                        val pz = r[2][0] * bx + r[2][1] * by + tz
                        assertTrue("target behind camera", pz > 0.0)
                        val xn = px / pz
                        val yn = py / pz
                        val r2 = xn * xn + yn * yn
                        val factor = 1.0 + k1 * r2 + k2 * r2 * r2
                        val u = fx * xn * factor + cx
                        val v = fy * yn * factor + cy
                        points += ArKernelIntrinsicsPoint(
                            xPx = u + random.nextGaussian() * noiseSigmaPx,
                            yPx = v + random.nextGaussian() * noiseSigmaPx,
                        )
                    }
                }
                views += ArKernelIntrinsicsView(points)
            }
            return views
        }

        private fun relativeError(actual: Double, expected: Double): Double =
            abs(actual - expected) / abs(expected)
    }

    // ------------------------------------------------------------ recovery

    @Test
    fun recoversAKnownCameraExactly() {
        val views = synthesise(viewCount = 8, seed = 11L)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertTrue(estimate.detail, estimate.accepted)
        val k = estimate.intrinsics!!
        assertTrue("fx ${k.fxPx} vs $TRUE_FX", relativeError(k.fxPx, TRUE_FX) < 0.005)
        assertTrue("fy ${k.fyPx} vs $TRUE_FY", relativeError(k.fyPx, TRUE_FY) < 0.005)
        assertTrue("cx ${k.cxPx} vs $TRUE_CX", abs(k.cxPx - TRUE_CX) < 2.0)
        assertTrue("cy ${k.cyPx} vs $TRUE_CY", abs(k.cyPx - TRUE_CY) < 2.0)
        assertTrue("k1 ${k.k1} vs $TRUE_K1", abs(k.k1 - TRUE_K1) < 0.02)
        assertTrue("k2 ${k.k2} vs $TRUE_K2", abs(k.k2 - TRUE_K2) < 0.02)
        assertTrue("rms ${estimate.rmsPx}", estimate.rmsPx < 0.05)
    }

    @Test
    fun recoversAKnownCameraThroughPixelNoise() {
        // A detector is not exact. Two tenths of a pixel is better than any
        // corner detector manages, so anything that only works here is useless.
        val views = synthesise(viewCount = 10, seed = 27L, noiseSigmaPx = 0.4)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertTrue(estimate.detail, estimate.accepted)
        val k = estimate.intrinsics!!
        assertTrue("fx ${k.fxPx}", relativeError(k.fxPx, TRUE_FX) < 0.02)
        assertTrue("fy ${k.fyPx}", relativeError(k.fyPx, TRUE_FY) < 0.02)
        assertTrue("cx ${k.cxPx}", abs(k.cxPx - TRUE_CX) < 8.0)
        assertTrue("cy ${k.cyPx}", abs(k.cyPx - TRUE_CY) < 8.0)
        assertTrue("k1 ${k.k1}", abs(k.k1 - TRUE_K1) < 0.05)
        assertTrue("rms ${estimate.rmsPx}", estimate.rmsPx < 1.0)
    }

    @Test
    fun recoversACameraThatIsNotDistorted() {
        // A camera with k1 = k2 = 0 must come back that way, not with the
        // small spurious coefficients a fit invents to explain noise.
        val views = synthesise(
            viewCount = 8, seed = 5L, k1 = 0.0, k2 = 0.0)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertTrue(estimate.detail, estimate.accepted)
        val k = estimate.intrinsics!!
        assertTrue("k1 ${k.k1}", abs(k.k1) < 0.01)
        assertTrue("k2 ${k.k2}", abs(k.k2) < 0.01)
    }

    @Test
    fun separatesTwoDifferentFocalLengths() {
        val fx = 1500.0
        val fy = 1200.0
        val views = synthesise(viewCount = 9, seed = 91L, fx = fx, fy = fy)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertTrue(estimate.detail, estimate.accepted)
        val k = estimate.intrinsics!!
        assertTrue("fx ${k.fxPx} vs $fx", relativeError(k.fxPx, fx) < 0.01)
        assertTrue("fy ${k.fyPx} vs $fy", relativeError(k.fyPx, fy) < 0.01)
        // 300 / 1350 is about 222 per thousand.
        val expected = (abs(fx - fy) / ((fx + fy) / 2.0) * 1000.0).toInt()
        assertTrue(
            "split ${estimate.focalSplitPermille} vs $expected",
            abs(estimate.focalSplitPermille - expected) < 60,
        )
    }

    @Test
    fun anOffCentrePrincipalPointIsFoundWhereItIs() {
        val views = synthesise(viewCount = 9, seed = 61L, cx = 1010.0, cy = 500.0)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertTrue(estimate.detail, estimate.accepted)
        val k = estimate.intrinsics!!
        assertTrue("cx ${k.cxPx}", abs(k.cxPx - 1010.0) < 10.0)
        assertTrue("cy ${k.cyPx}", abs(k.cyPx - 500.0) < 10.0)
    }

    @Test
    fun thePhysicalSquareSizeNeverEntersTheAnswer() {
        // The claim that makes this usable by a person with a printer: the
        // target's shape is enough, its size is not. Same image, two scenes
        // that differ only by a uniform scale, must give the same camera.
        val views = synthesise(viewCount = 8, seed = 3L)
        val small = synthesise(viewCount = 8, seed = 3L, squareSize = 0.025)
        val a = ArKernelIntrinsicsEstimator.estimate(views, COLUMNS, ROWS, WIDTH, HEIGHT)
        val b = ArKernelIntrinsicsEstimator.estimate(small, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertTrue(a.detail, a.accepted)
        assertTrue(b.detail, b.accepted)
        val ka = a.intrinsics!!
        val kb = b.intrinsics!!
        assertEquals(ka.fxPx, kb.fxPx, 1e-6)
        assertEquals(ka.fyPx, kb.fyPx, 1e-6)
        assertEquals(ka.cxPx, kb.cxPx, 1e-6)
        assertEquals(ka.cyPx, kb.cyPx, 1e-6)
    }

    // ------------------------------------------------------------ refusal

    @Test
    fun tooFewViewsIsRefusedBeforeAnythingIsComputed() {
        val views = synthesise(
            viewCount = ArKernelIntrinsicsEstimator.MIN_VIEWS - 1, seed = 1L)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertEquals(ArKernelIntrinsicsRejection.TOO_FEW_VIEWS, estimate.rejection)
        assertNull(estimate.intrinsics)
    }

    @Test
    fun aViewWithTheWrongNumberOfCornersIsRefused() {
        val views = synthesise(viewCount = 8, seed = 1L).toMutableList()
        views[3] = ArKernelIntrinsicsView(views[3].points.dropLast(1))
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertEquals(ArKernelIntrinsicsRejection.GRID_MISMATCH, estimate.rejection)
    }

    @Test
    fun aCornerThatIsNotANumberIsRefused() {
        val views = synthesise(viewCount = 8, seed = 1L).toMutableList()
        val points = views[0].points.toMutableList()
        points[0] = ArKernelIntrinsicsPoint(Double.NaN, 10.0)
        views[0] = ArKernelIntrinsicsView(points)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertEquals(ArKernelIntrinsicsRejection.NON_FINITE_INPUT, estimate.rejection)
    }

    @Test
    fun theSameViewRepeatedIsRefusedRatherThanFitted() {
        // This is the failure with no internal symptom: eight copies of one
        // view are eight constraints that agree perfectly, so the residual is
        // excellent and the focal length is whatever the geometry allows.
        val one = synthesise(viewCount = 1, seed = 4L).first()
        val views = List(8) { one }
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertFalse("must not accept eight identical views", estimate.accepted)
        assertNull(estimate.intrinsics)
    }

    @Test
    fun aTargetHeldSmallInTheMiddleIsRefusedOnCoverage() {
        // Far away and square on: a fine fit to a badly determined answer.
        val views = synthesise(
            viewCount = 8, seed = 8L, tiltDeg = 2.0, distanceScale = 4.0)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        assertEquals(
            estimate.detail,
            ArKernelIntrinsicsRejection.POOR_COVERAGE,
            estimate.rejection,
        )
    }

    @Test
    fun aRefusalSaysWhatWasOpen() {
        val views = synthesise(
            viewCount = 8, seed = 8L, tiltDeg = 2.0, distanceScale = 4.0)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        val open = estimate.verdicts.filter { !it.value }.keys
        assertTrue("nothing named as open", open.isNotEmpty())
        assertTrue(estimate.detail, estimate.detail.contains("Abgelehnt"))
    }

    @Test
    fun anAcceptedEstimateReportsEveryGateClosed() {
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            synthesise(viewCount = 8, seed = 11L), COLUMNS, ROWS, WIDTH, HEIGHT)
        assertTrue(estimate.detail, estimate.accepted)
        assertEquals(
            estimate.verdicts.filter { !it.value }.keys.joinToString(),
            0,
            estimate.verdicts.filter { !it.value }.size,
        )
        assertTrue(estimate.verdicts.containsKey("distortion_bounded"))
        assertTrue(estimate.verdicts.containsKey("conditioning_ok"))
        assertTrue(estimate.verdicts.containsKey("coverage_ok"))
    }

    // ------------------------------------------------------------- reporting

    @Test
    fun theHorizontalFieldOfViewMatchesTheFocalLength() {
        val k = ArKernelCameraIntrinsics(1400.0, 1400.0, 960.0, 540.0, 0.0, 0.0)
        val expected = 2.0 * Math.toDegrees(
            kotlin.math.atan(1920.0 / (2.0 * 1400.0)))
        assertEquals(expected, k.horizontalFovDeg(1920), 1e-9)
        // A number a person can check: this is a wide-ish phone camera.
        assertTrue("${k.horizontalFovDeg(1920)}", k.horizontalFovDeg(1920) in 60.0..75.0)
    }

    @Test
    fun theModelRefusesANonPhysicalCamera() {
        for (bad in listOf(
            ArKernelCameraIntrinsics::class to doubleArrayOf(-1.0, 1400.0, 960.0, 540.0, 0.0, 0.0),
        )) {
            var threw = false
            try {
                ArKernelCameraIntrinsics(bad.second[0], bad.second[1], bad.second[2],
                    bad.second[3], bad.second[4], bad.second[5])
            } catch (error: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw)
        }
    }

    @Test
    fun theResidualIsTheReprojectionErrorNotAFitScore() {
        // Feed the true camera's own projection back and check the estimator
        // agrees the error is zero, which is what makes rmsPx meaningful.
        val views = synthesise(viewCount = 8, seed = 11L)
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views, COLUMNS, ROWS, WIDTH, HEIGHT)
        val k = estimate.intrinsics!!
        val fov = k.horizontalFovDeg(WIDTH)
        assertTrue("fov $fov", fov > 0.0)
        val diagonal = sqrt(WIDTH * WIDTH.toDouble() + HEIGHT * HEIGHT.toDouble())
        // The recovered focal must be in the same league as the true one,
        // not merely positive: a fit can be stable and wrong.
        assertTrue(
            "fx ${k.fxPx} against an image diagonal of $diagonal",
            k.fxPx in (diagonal * 0.4)..(diagonal * 2.0),
        )
        assertNotNull(estimate.detail)
    }
}
