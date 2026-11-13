package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Checks the corner refinement against boards that were rendered, not assumed.
 *
 * The point of these tests is that the image is produced by projecting a grid
 * through a camera written down first, so the true position of every corner in
 * pixels is known exactly. Comparing refined corners against the prediction
 * they started from would prove nothing: the prediction comes from the same
 * four taps in both cases. Only an independently rendered board can say
 * whether a corner was found.
 *
 * The taps are deliberately wrong. A person does not place a finger to a
 * tenth of a pixel, and a refinement that only works from an exact start would
 * be useless, so the outer corners here are displaced by a few pixels before
 * the refinement ever sees them.
 */
class ArKernelCheckerboardTest {

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val COLUMNS = 9
        const val ROWS = 7
        const val EXPECTED = COLUMNS * ROWS

        const val FX = 620.0
        const val FY = 620.0
        const val CX = 320.0
        const val CY = 240.0
        const val K1 = -0.15
        const val K2 = 0.03

        const val BACKGROUND = 128

        private fun rotation(xDeg: Double, yDeg: Double, zDeg: Double): Array<DoubleArray> {
            val ax = Math.toRadians(xDeg)
            val ay = Math.toRadians(yDeg)
            val az = Math.toRadians(zDeg)
            val rx = arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(0.0, cos(ax), -sin(ax)),
                doubleArrayOf(0.0, sin(ax), cos(ax)))
            val ry = arrayOf(
                doubleArrayOf(cos(ay), 0.0, sin(ay)),
                doubleArrayOf(0.0, 1.0, 0.0),
                doubleArrayOf(-sin(ay), 0.0, cos(ay)))
            val rz = arrayOf(
                doubleArrayOf(cos(az), -sin(az), 0.0),
                doubleArrayOf(sin(az), cos(az), 0.0),
                doubleArrayOf(0.0, 0.0, 1.0))
            return mul(mul(rz, ry), rx)
        }

        private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
            val out = Array(3) { DoubleArray(3) }
            for (i in 0..2) {
                for (j in 0..2) {
                    out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j]
                }
            }
            return out
        }

        /** Undoes the radial model so a distorted pixel becomes a ray. */
        private fun undistort(xd: Double, yd: Double): Pair<Double, Double> {
            var xn = xd
            var yn = yd
            repeat(12) {
                val r2 = xn * xn + yn * yn
                val factor = 1.0 + K1 * r2 + K2 * r2 * r2
                if (abs(factor) < 1e-9) return Pair(xn, yn)
                xn = xd / factor
                yn = yd / factor
            }
            return Pair(xn, yn)
        }

        /** True image position of grid corner (column, row). */
        private fun project(
            r: Array<DoubleArray>,
            tx: Double,
            ty: Double,
            tz: Double,
            column: Int,
            row: Int,
        ): Pair<Double, Double> {
            val bx = column.toDouble()
            val by = row.toDouble()
            val px = r[0][0] * bx + r[0][1] * by + tx
            val py = r[1][0] * bx + r[1][1] * by + ty
            val pz = r[2][0] * bx + r[2][1] * by + tz
            val xn = px / pz
            val yn = py / pz
            val r2 = xn * xn + yn * yn
            val factor = 1.0 + K1 * r2 + K2 * r2 * r2
            return Pair(FX * xn * factor + CX, FY * yn * factor + CY)
        }

        /**
         * Renders the board by inverting the projection for every pixel, which
         * gives the exact same geometry the estimator will later be asked to
         * recover, including the distortion.
         */
        private fun render(
            r: Array<DoubleArray>,
            tx: Double,
            ty: Double,
            tz: Double,
            invert: Boolean = false,
        ): ByteArray {
            // M = [r1 r2 t]; grid point (X, Y, 1) maps to M * (X, Y, 1)^T.
            val m = arrayOf(
                doubleArrayOf(r[0][0], r[0][1], tx),
                doubleArrayOf(r[1][0], r[1][1], ty),
                doubleArrayOf(r[2][0], r[2][1], tz),
            )
            val inverse = invert3(m)!!
            val gray = ByteArray(WIDTH * HEIGHT)
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val (xn, yn) = undistort((x.toDouble() - CX) / FX, (y.toDouble() - CY) / FY)
                    val qx = inverse[0][0] * xn + inverse[0][1] * yn + inverse[0][2]
                    val qy = inverse[1][0] * xn + inverse[1][1] * yn + inverse[1][2]
                    val qw = inverse[2][0] * xn + inverse[2][1] * yn + inverse[2][2]
                    if (abs(qw) < 1e-12) {
                        gray[y * WIDTH + x] = BACKGROUND.toByte()
                        continue
                    }
                    val gx = qx / qw
                    val gy = qy / qw
                    // Grid points are the CORNERS, so square (i, j) spans
                    // [i, i+1] x [j, j+1] and the colour is (i + j) % 2. The
                    // printed sheet extends one square past the corner array,
                    // so even the outermost corner still sees four squares.
                    val inside = gx >= -1.0 && gx <= COLUMNS - 1.0 &&
                        gy >= -1.0 && gy <= ROWS - 1.0
                    val value = if (!inside) {
                        BACKGROUND
                    } else {
                        val cell = (floor(gx).toInt() + floor(gy).toInt()) and 1
                        if (cell == 0) if (!invert) 20 else 235
                        else if (!invert) 235 else 20
                    }
                    gray[y * WIDTH + x] = value.toByte()
                }
            }
            return gray
        }

        private fun invert3(m: Array<DoubleArray>): Array<DoubleArray>? {
            val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
            if (abs(det) < 1e-18) return null
            val out = Array(3) { DoubleArray(3) }
            out[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det
            out[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det
            out[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det
            out[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det
            out[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det
            out[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det
            out[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det
            out[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det
            out[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det
            return out
        }

        private fun tap(xPx: Double, yPx: Double) =
            ArKernelCheckerboard.ArKernelBoardCornerInput(xPx, yPx)

        private fun pose(seed: Long): Triple<Array<DoubleArray>, List<Double>, Double> {
            val random = java.util.Random(seed)
            val r = rotation(
                (random.nextDouble() - 0.5) * 50.0,
                (random.nextDouble() - 0.5) * 50.0,
                (random.nextDouble() - 0.5) * 25.0)
            val distance = 26.0
            return Triple(r, listOf(
                (random.nextDouble() - 0.5) * 2.0,
                (random.nextDouble() - 0.5) * 2.0), distance)
        }
    }

    /** Renders one view and returns the image plus the true corners. */
    private fun view(
        seed: Long,
        invert: Boolean = false,
        distance: Double = 26.0,
    ): Pair<ByteArray, List<Pair<Double, Double>>> {
        val (r, offset, _) = pose(seed)
        val gray = render(r, offset[0], offset[1], distance, invert)
        val truth = ArrayList<Pair<Double, Double>>(EXPECTED)
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                truth += project(r, offset[0], offset[1], distance, column, row)
            }
        }
        return Pair(gray, truth)
    }

    /** The four outer inner corners, displaced to imitate a fingertip. */
    private fun tapsOf(
        truth: List<Pair<Double, Double>>,
        errorPx: Double,
        seed: Long = 7L,
    ): List<ArKernelCheckerboard.ArKernelBoardCornerInput> {
        val random = java.util.Random(seed)
        fun jitter(value: Double) =
            value + (random.nextDouble() - 0.5) * 2.0 * errorPx
        val topLeft = truth[0]
        val topRight = truth[COLUMNS - 1]
        val bottomRight = truth[(ROWS - 1) * COLUMNS + COLUMNS - 1]
        val bottomLeft = truth[(ROWS - 1) * COLUMNS]
        return listOf(
            tap(jitter(topLeft.first), jitter(topLeft.second)),
            tap(jitter(topRight.first), jitter(topRight.second)),
            tap(jitter(bottomRight.first), jitter(bottomRight.second)),
            tap(jitter(bottomLeft.first), jitter(bottomLeft.second)),
        )
    }

    /** Sensor noise, which a rendered board does not have and a phone does. */
    private fun noise(gray: ByteArray, sigma: Double, seed: Long): ByteArray {
        val random = java.util.Random(seed)
        val out = ByteArray(gray.size)
        for (index in gray.indices) {
            val value = (gray[index].toInt() and 0xFF) + random.nextGaussian() * sigma
            out[index] = kotlin.math.round(value).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun meanError(
        result: ArKernelBoardResult,
        truth: List<Pair<Double, Double>>,
    ): Double {
        val (mean, _) = errors(result, truth)
        return mean
    }

    private fun maxError(
        result: ArKernelBoardResult,
        truth: List<Pair<Double, Double>>,
    ): Double {
        val (_, worst) = errors(result, truth)
        return worst
    }

    /**
     * Mean and worst error over the usable corners.
     *
     * The worst error is the one that matters. A mean can look excellent while
     * a handful of corners have jumped onto a neighbouring square, and a jump
     * of one square is invisible to the fit that consumes these points: it is
     * read as distortion of the lens and fitted as though it were real. So
     * every check below is made on both.
     */
    private fun errors(
        result: ArKernelBoardResult,
        truth: List<Pair<Double, Double>>,
    ): Pair<Double, Double> {
        var total = 0.0
        var worst = 0.0
        var count = 0
        for (index in 0 until EXPECTED) {
            val corner = result.corners[index]
            if (!corner.valid) continue
            val error = hypot(
                corner.xPx - truth[index].first,
                corner.yPx - truth[index].second)
            total += error
            if (error > worst) worst = error
            count++
        }
        return if (count == 0) {
            Pair(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        } else {
            Pair(total / count, worst)
        }
    }

    // ------------------------------------------------------------ refinement

    @Test
    fun refinesEveryCornerFromAnExactTap() {
        val (gray, truth) = view(seed = 21L)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS, tapsOf(truth, 0.0))
        assertTrue(result.detail, result.accepted)
        assertEquals(EXPECTED, result.validCount)
        val error = meanError(result, truth)
        assertTrue("mean error $error px (${result.detail})", error < 0.5)
        val worst = maxError(result, truth)
        assertTrue("worst corner is $worst px off (${result.detail})", worst < 1.5)
    }

    @Test
    fun aFingertipIsNotAPixel() {
        // The realistic case: the four taps are several pixels off, and the
        // homography built from them predicts every interior corner a little
        // wrong. Refinement has to recover from that, not preserve it.
        val (gray, truth) = view(seed = 21L)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS, tapsOf(truth, 4.0))
        assertTrue(result.detail, result.accepted)
        val error = meanError(result, truth)
        assertTrue("mean error $error px (${result.detail})", error < 0.6)
        val worst = maxError(result, truth)
        assertTrue("worst corner is $worst px off (${result.detail})", worst < 2.0)
    }

    @Test
    fun theRefinementImprovesOnThePredictionItWasGiven() {
        // If this fails, the whole exercise is decoration: the taps alone
        // would be as good, and the operator's fingertip would be the answer.
        val (gray, truth) = view(seed = 33L)
        val taps = tapsOf(truth, 4.0)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS, taps)
        assertTrue(result.detail, result.accepted)
        val refined = meanError(result, truth)
        // What the bare homography would have given, measured the same way.
        val homography = ArKernelCheckerboard.homographyForQuad(taps)!!
        var before = 0.0
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                val spanX = (COLUMNS - 1).toDouble()
                val spanY = (ROWS - 1).toDouble()
                val w = homography[6] * (column / spanX) +
                    homography[7] * (row / spanY) + homography[8]
                val px = (homography[0] * (column / spanX) +
                    homography[1] * (row / spanY) + homography[2]) / w
                val py = (homography[3] * (column / spanX) +
                    homography[4] * (row / spanY) + homography[5]) / w
                before += hypot(px - truth[row * COLUMNS + column].first,
                    py - truth[row * COLUMNS + column].second)
            }
        }
        before /= EXPECTED
        assertTrue(
            "refinement made it worse: $refined vs $before",
            refined < before,
        )
    }

    @Test
    fun anInvertedBoardGivesTheOppositeSign() {
        // The response is signed, and the sign is the corner's orientation.
        // Flipping the board's colours must flip it, which is what makes the
        // consistency check below meaningful rather than decorative.
        val (normal, truthA) = view(seed = 21L)
        val (flipped, _) = view(seed = 21L, invert = true)
        val a = ArKernelCheckerboard.refineBoard(
            normal, WIDTH, HEIGHT, COLUMNS, ROWS, tapsOf(truthA, 2.0))
        val b = ArKernelCheckerboard.refineBoard(
            flipped, WIDTH, HEIGHT, COLUMNS, ROWS, tapsOf(truthA, 2.0))
        assertTrue(a.detail, a.accepted)
        assertTrue(b.detail, b.accepted)
        val signA = if (a.corners.first { it.valid }.score > 0) 1 else -1
        val signB = if (b.corners.first { it.valid }.score > 0) 1 else -1
        assertEquals("the sign must follow the board", -signA, signB)
    }

    @Test
    fun theOrientationAlternatesInTheCheckerboardPattern() {
        // A checkerboard's corners are saddles, so the signed response flips
        // from one corner to the next and follows the parity of column + row.
        // That is the board's signature, and it is what the refinement checks.
        val (gray, truth) = view(seed = 45L)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS, tapsOf(truth, 3.0))
        assertTrue(result.detail, result.accepted)
        // Phase-agnostic on purpose: which sign the "even" corners carry
        // depends on the printed board, so what must hold is that after the
        // parity flip is removed every corner points the same way.
        val oriented = ArrayList<Boolean>(EXPECTED)
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                val corner = result.corners[row * COLUMNS + column]
                if (!corner.valid) continue
                val parity = if ((column + row) % 2 == 0) 1 else -1
                oriented += (corner.score * parity) > 0.0
            }
        }
        assertEquals(
            "${oriented.count { it }} with the pattern, " +
                "${oriented.count { !it }} against it",
            1,
            oriented.toSet().size,
        )
    }

    @Test
    fun theCornersComeBackInRowMajorOrder() {
        // If the ordering were wrong the intrinsics fit would be fitting a
        // scrambled board and the residual would not notice.
        val (gray, truth) = view(seed = 21L)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS, tapsOf(truth, 2.0))
        assertTrue(result.detail, result.accepted)
        val first = result.corners.first { it.valid }
        val last = result.corners[EXPECTED - 1]
        assertTrue(
            "corner 0 should sit at the tapped top-left",
            hypot(first.xPx - truth[0].first, first.yPx - truth[0].second) < 2.0)
        assertTrue(
            "the last corner should sit at the bottom-right",
            hypot(last.xPx - truth[EXPECTED - 1].first,
                last.yPx - truth[EXPECTED - 1].second) < 2.0)
    }

    // -------------------------------------------------------------- refusals

    @Test
    fun aFlatImageIsRefusedRatherThanFitted() {
        val gray = ByteArray(WIDTH * HEIGHT) { BACKGROUND.toByte() }
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS,
            listOf(tap(100.0, 100.0), tap(500.0, 100.0),
                tap(500.0, 400.0), tap(100.0, 400.0)))
        assertEquals(result.detail, ArKernelBoardRejection.LOW_CONTRAST, result.rejection)
        assertTrue(result.corners.isEmpty())
    }

    @Test
    fun fourPointsOnTopOfEachOtherAreRefused() {
        val (gray, truth) = view(seed = 21L)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS,
            List(4) { tap(truth[0].first, truth[0].second) })
        assertEquals(
            result.detail,
            ArKernelBoardRejection.QUAD_DEGENERATE,
            result.rejection,
        )
    }

    @Test
    fun aBoardHeldTooFarAwayIsRefusedOnSquareSize() {
        // Far away the board is a handful of pixels across; refinement would
        // happily return something, so this has to be stopped on geometry.
        val (gray, truth) = view(seed = 21L, distance = 260.0)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS, tapsOf(truth, 1.0))
        assertFalse("must not accept a board this small", result.accepted)
    }

    @Test
    fun tapsThatMissTheBoardAltogetherAreRefused() {
        // Four points in an empty corner of the frame are a valid quad with no
        // grid anywhere near them.
        val (gray, _) = view(seed = 21L)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS,
            listOf(tap(20.0, 20.0), tap(70.0, 25.0),
                tap(65.0, 60.0), tap(18.0, 55.0)))
        assertFalse(
            "must not accept taps that are nowhere near the board: ${result.detail}",
            result.accepted,
        )
    }

    @Test
    fun sensorNoiseDoesNotMoveTheCorners() {
        // A phone's image is never clean. Noise does not merely add spread to
        // a good answer, it can move individual corners onto a neighbouring
        // square - which the mean hides and the fit downstream reads as
        // distortion - so this checks the worst corner, not the average one.
        val (gray, truth) = view(seed = 21L)
        val result = ArKernelCheckerboard.refineBoard(
            noise(gray, 8.0, seed = 4242L), WIDTH, HEIGHT, COLUMNS, ROWS,
            tapsOf(truth, 4.0))
        assertTrue(result.detail, result.accepted)
        val error = meanError(result, truth)
        assertTrue("mean error $error px under noise (${result.detail})", error < 1.0)
        val worst = maxError(result, truth)
        assertTrue("worst corner is $worst px off under noise", worst < 2.0)
    }

    @Test
    fun holdsForManyDifferentViews() {
        // One view can be right by luck. Six random poses - tilted, turned,
        // off-centre, with noise on top - either all refine or there is no
        // detector here worth wiring into the app.
        for (seed in longArrayOf(21L, 33L, 45L, 57L, 71L, 88L)) {
            val (gray, truth) = view(seed = seed)
            val result = ArKernelCheckerboard.refineBoard(
                noise(gray, 6.0, seed = seed + 999L), WIDTH, HEIGHT,
                COLUMNS, ROWS, tapsOf(truth, 4.0, seed = seed))
            assertTrue("seed $seed: ${result.detail}", result.accepted)
            val error = meanError(result, truth)
            assertTrue(
                "seed $seed: mean error $error px (${result.detail})",
                error < 1.0,
            )
            val worst = maxError(result, truth)
            assertTrue(
                "seed $seed: worst corner is $worst px off (${result.detail})",
                worst < 2.0,
            )
        }
    }

    @Test
    fun theWholeBoardIsFoundUnderNoiseNotMostOfIt() {
        // Under noise a detector that quietly drops the corners it could not
        // find still reports a fine mean over the ones it kept. The number
        // that matters is how many it kept.
        val (gray, truth) = view(seed = 57L)
        val result = ArKernelCheckerboard.refineBoard(
            noise(gray, 10.0, seed = 31337L), WIDTH, HEIGHT, COLUMNS, ROWS,
            tapsOf(truth, 4.0))
        assertTrue(result.detail, result.accepted)
        assertEquals(result.detail, EXPECTED, result.validCount)
    }

    @Test
    fun aBoardRunningOffTheFrameIsRefused() {
        // The commonest way a person holds a board. A corner whose measuring
        // window hangs over the edge would otherwise be read from a clamped,
        // half-empty window - which returns a confident number for a square
        // that is not in the picture - and the fit would absorb it as
        // distortion. There is no honest measurement here, so there is no
        // measurement.
        val (gray, truth) = view(seed = 21L, distance = 14.0)
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS, tapsOf(truth, 2.0))
        assertEquals(
            result.detail,
            ArKernelBoardRejection.BOARD_NOT_FULLY_VISIBLE,
            result.rejection,
        )
    }

    @Test
    fun aRefusalStillSaysWhy() {
        val gray = ByteArray(WIDTH * HEIGHT) { BACKGROUND.toByte() }
        val result = ArKernelCheckerboard.refineBoard(
            gray, WIDTH, HEIGHT, COLUMNS, ROWS,
            listOf(tap(100.0, 100.0), tap(500.0, 100.0),
                tap(500.0, 400.0), tap(100.0, 400.0)))
        assertTrue(result.detail.isNotBlank())
        assertEquals(0, result.validCount)
    }

    // ------------------------------------------------------------------ quad

    @Test
    fun theQuadHomographyMapsItsOwnCornersExactly() {
        // Four taps, four exact images: the construction has to reproduce the
        // points it was built from before it can be trusted in between.
        val quad = listOf(tap(120.0, 90.0), tap(540.0, 110.0),
            tap(520.0, 410.0), tap(140.0, 390.0))
        val h = ArKernelCheckerboard.homographyForQuad(quad)!!
        val unit = arrayOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0),
            doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0))
        for (index in 0..3) {
            val x = unit[index][0]
            val y = unit[index][1]
            val w = h[6] * x + h[7] * y + h[8]
            val px = (h[0] * x + h[1] * y + h[2]) / w
            val py = (h[3] * x + h[4] * y + h[5]) / w
            assertEquals(quad[index].xPx, px, 1e-6)
            assertEquals(quad[index].yPx, py, 1e-6)
        }
    }

    @Test
    fun theResponseIsZeroOnAFlatFieldAndLargeAtACorner() {
        // The measure has to be blind to edges and uniform areas, or it is not
        // a corner detector at all.
        val flat = ArKernelImageIntegral(
            ByteArray(64 * 64) { 128.toByte() }, 64, 64)
        assertEquals(0.0, ArKernelCheckerboard.responseAt(flat, 32.0, 32.0, 6.0, 3), 1e-9)

        val edge = ByteArray(64 * 64) { i ->
            val x = i % 64
            if (x < 32) 20.toByte() else 235.toByte()
        }
        val edgeImage = ArKernelImageIntegral(edge, 64, 64)
        val onEdge = abs(ArKernelCheckerboard.responseAt(edgeImage, 32.0, 32.0, 6.0, 3))
        assertTrue("a straight edge must not look like a corner: $onEdge", onEdge < 20.0)

        val (gray, truth) = view(seed = 21L)
        val board = ArKernelImageIntegral(gray, WIDTH, HEIGHT)
        val corner = truth[3 * COLUMNS + 4]
        val onCorner = abs(ArKernelCheckerboard.responseAt(board, corner.first, corner.second, 10.0, 5))
        assertTrue("a corner must stand out: $onCorner", onCorner > 100.0)
    }
}
