package ai.arena.arkernel

/**
 * Finds the inner corners of a printed grid, given roughly where they are.
 *
 * **The division of labour here is deliberate.** A general-purpose checkerboard
 * finder has to decide that a grid is present, find its extent, and assemble
 * hundreds of candidates into an ordered lattice before it can fit anything.
 * Each of those steps fails in a different way on a real phone - motion blur,
 * a busy background, a board at a steep angle - and none of them can be tested
 * here. This file therefore does none of them.
 *
 * It takes four points a person tapped - the four outer inner corners of the
 * grid - and from those it knows where every corner *ought* to be, because
 * four points determine the homography of a plane. It then looks in a small
 * window around each prediction and refines it. That is a search with a known
 * answer nearby, not a detection with none; it is testable offline against
 * rendered boards, and it cannot silently lock onto the wrong thing.
 *
 * **Why a person taps four points.** Not because automatic detection is hard
 * in principle, but because an unfalsifiable detector is worse than no
 * detector: it would report corners with confidence whether or not it had
 * found them. Four taps cost a few seconds once per view and remove the entire
 * class of failure where the board is never found.
 *
 * **The corner measure.** Around an inner corner the four diagonal squares
 * alternate: the two on one diagonal are the same colour, the two on the other
 * are the opposite. So the difference between the sums of the two diagonal
 * pairs is twice the board's contrast at a corner and zero almost everywhere
 * else - in the middle of a square, because all four neighbours share its
 * colour, and on a straight edge, because each diagonal sees one of each. That
 * last property is what makes this a corner detector rather than an edge
 * detector, and it costs four box sums.
 *
 * **The sign is used, not discarded, and it alternates.** A checkerboard's
 * corners are saddles, and the saddle's orientation flips from one corner to
 * the next: it follows the parity of `column + row`. That alternation is the
 * board's signature, so it is checked rather than assumed - a set of points
 * that are not a grid will not carry it, and no threshold on magnitude alone
 * would notice. It also pins the phase: a corner found one square away from
 * where it belongs carries the opposite sign.
 */

/** One corner as the refinement left it. */
data class ArKernelBoardCorner(
    val xPx: Double,
    val yPx: Double,
    /** Signed response; its sign is the corner's orientation. */
    val score: Double,
    val shiftPx: Double,
    val valid: Boolean,
)

enum class ArKernelBoardRejection {
    OK,

    /** The four taps do not define a proper quadrilateral. */
    QUAD_DEGENERATE,

    /** The squares are too small in the image for anything to be measured. */
    SQUARES_TOO_SMALL,

    /** Too many corners could not be found. */
    TOO_FEW_CORNERS,

    /**
     * The board runs off the edge of the frame.
     *
     * This is refused rather than tolerated, and it is the commonest way a
     * person will hold a board. Measuring a corner whose window is half
     * outside the image does not give a worse answer for that corner, it gives
     * a *confidently wrong* one: the missing part of the window is silently
     * read as whatever the clamped edge returns, which looks exactly like a
     * square that is there. The fit downstream then reads the error as lens
     * distortion. A board has to be fully in frame, and saying so is a single
     * instruction anyone can follow.
     */
    BOARD_NOT_FULLY_VISIBLE,

    /** The response did not follow the checkerboard's alternating pattern. */
    INCONSISTENT_ORIENTATION,

    /** No contrast anywhere: a blank frame, or the taps are not on a board. */
    LOW_CONTRAST,
    ;

    val accepted: Boolean get() = this == OK
}

data class ArKernelBoardResult(
    /** Row-major: index `row * columns + column`. Empty when refused. */
    val corners: List<ArKernelBoardCorner>,
    val rejection: ArKernelBoardRejection,
    val detail: String,
    val validCount: Int,
    val meanShiftPx: Double,
    val meanScore: Double,
) {
    val accepted: Boolean get() = rejection.accepted
}

/** Summed-area table over one 8-bit grayscale plane. */
class ArKernelImageIntegral(val width: Int, val height: Int) {

    private val sums = LongArray((width + 1) * (height + 1))

    constructor(gray: ByteArray, width: Int, height: Int) : this(width, height) {
        require(gray.size >= width * height) { "grayscale plane too small" }
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += (gray[y * width + x].toInt() and 0xFF).toLong()
                sums[(y + 1) * (width + 1) + (x + 1)] =
                    sums[y * (width + 1) + (x + 1)] + rowSum
            }
        }
    }

    /** Mean over the inclusive box, clamped to the image. */
    fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val ax0 = x0.coerceIn(0, width)
        val ay0 = y0.coerceIn(0, height)
        val ax1 = x1.coerceIn(0, width)
        val ay1 = y1.coerceIn(0, height)
        if (ax1 <= ax0 || ay1 <= ay0) return 0.0
        val total = sums[(ay1) * (width + 1) + (ax1)] -
            sums[(ay0) * (width + 1) + (ax1)] -
            sums[(ay1) * (width + 1) + (ax0)] +
            sums[(ay0) * (width + 1) + (ax0)]
        val area = (ax1 - ax0).toDouble() * (ay1 - ay0).toDouble()
        return total / area
    }
}

object ArKernelCheckerboard {

    /** Below this many pixels per square the boxes stop resolving a corner. */
    const val MIN_SQUARE_PX = 8.0

    /** Signed response has to reach this fraction of full black-to-white. */
    const val MIN_RESPONSE = 60.0

    /** How far a refinement may move from the prediction, as a fraction. */
    const val MAX_SHIFT_FRACTION = 0.35

    /** Search window around each prediction, as a fraction of the spacing. */
    private const val SEARCH_FRACTION = 0.30

    /**
     * How many times every corner is re-searched against its own neighbours'
     * positions.
     *
     * One pass means one prediction from four taps, and four taps are one
     * fingertip away from being wrong. Worse, the taps define a *projective*
     * map, so every prediction is exact only for an undistorted pinhole: a
     * lens with any barrel or pincushion to it puts the outer corners tens of
     * pixels from where the homography says they are, and no amount of care
     * at the taps fixes that.
     *
     * So the found corners become the next pass's prediction. Each pass feeds
     * on where its neighbours actually turned out to be, which carries the
     * real geometry - distortion included - and the predictions walk in.
     */
    private const val PASSES = 4

    /**
     * Fraction of a square used as the half-width of the measuring boxes in
     * the final, fine pass.
     *
     * The four boxes around a corner are symmetric about it only for a
     * pinhole with no distortion. Add a lens and the far square shrinks while
     * the near one grows, the difference of their means no longer peaks at the
     * corner, and the peak's displacement grows roughly with the *square* of
     * the box size - which is why a coarse window can be several pixels
     * confidently wrong while still finding the corner.
     *
     * So once the corner is localised, it is measured again through a small
     * window, where the bias is small. The search stays tight because the
     * starting point is already close.
     */
    private const val FINE_BOX_FRACTION = 0.10
    private const val FINE_SEARCH_PX = 2

    /** How often the saddle equation is re-solved with a re-centred window. */
    private const val SUBPIXEL_STEPS = 4

    /**
     * How close to the best response a position must come to count as part of
     * the same flat top. See [searchCorner].
     */
    private const val PLATEAU_TOLERANCE = 0.95

    /** At least this share of the corners has to refine cleanly. */
    const val MIN_VALID_FRACTION = 0.85

    /**
     * Signed corner response at one point.
     *
     * The two boxes on one diagonal are summed and the two on the other are
     * subtracted. At a corner the first pair shares a colour and the second
     * pair shares the other, so the result is twice the board's contrast.
     */
    fun responseAt(
        image: ArKernelImageIntegral,
        x: Double,
        y: Double,
        offset: Double,
        boxHalf: Int,
    ): Double = responseAlongAxes(image, x, y, offset, 0.0, 0.0, offset, boxHalf)

    /**
     * Signed response with the boxes placed along the board's own axes.
     *
     * `(ux, uy)` is one grid step in the image and `(vx, vy)` the other, both
     * taken from the corner's predicted neighbours. Placing the boxes along
     * the image axes instead - which is the obvious thing to do - only works
     * while the board is unrotated; a board turned in the frame puts the
     * diagonal squares somewhere else entirely, and the peak then sits a few
     * pixels off the corner with a perfectly convincing score.
     */
    fun responseAlongAxes(
        image: ArKernelImageIntegral,
        x: Double,
        y: Double,
        ux: Double,
        uy: Double,
        vx: Double,
        vy: Double,
        boxHalf: Int,
    ): Double {
        // The four squares touching this corner sit at grid offsets
        // (+/-0.5, +/-0.5) from it, which is half a step along each axis.
        val centres = arrayOf(
            doubleArrayOf(x + 0.5 * (ux + vx), y + 0.5 * (uy + vy)),
            doubleArrayOf(x - 0.5 * (ux + vx), y - 0.5 * (uy + vy)),
            doubleArrayOf(x + 0.5 * (ux - vx), y + 0.5 * (uy - vy)),
            doubleArrayOf(x - 0.5 * (ux - vx), y - 0.5 * (uy - vy)),
        )
        var sameDiagonal = 0.0
        var otherDiagonal = 0.0
        for (index in 0..3) {
            val cx = kotlin.math.round(centres[index][0]).toInt()
            val cy = kotlin.math.round(centres[index][1]).toInt()
            val value = image.mean(
                cx - boxHalf, cy - boxHalf, cx + boxHalf, cy + boxHalf)
            // 0 and 1 are one diagonal; 2 and 3 the other.
            if (index < 2) sameDiagonal += value else otherDiagonal += value
        }
        return sameDiagonal - otherDiagonal
    }

    /**
     * The homography taking the unit square to the tapped quad, in the order
     * top-left, top-right, bottom-right, bottom-left.
     *
     * Four points determine the mapping of a plane, which is the whole point:
     * it means four taps predict every corner on the board without ever
     * looking for one.
     */
    fun homographyForQuad(quad: List<ArKernelBoardCornerInput>): DoubleArray? {
        if (quad.size != 4) return null
        val x0 = quad[0].xPx; val y0 = quad[0].yPx
        val x1 = quad[1].xPx; val y1 = quad[1].yPx
        val x2 = quad[2].xPx; val y2 = quad[2].yPx
        val x3 = quad[3].xPx; val y3 = quad[3].yPx
        val dx1 = x1 - x2
        val dy1 = y1 - y2
        val dx2 = x3 - x2
        val dy2 = y3 - y2
        val denominator = dx1 * dy2 - dy1 * dx2
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < 1e-9) return null
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        val g = (sx * dy2 - dx2 * sy) / denominator
        val h = (dx1 * sy - sx * dy1) / denominator
        if (!g.isFinite() || !h.isFinite()) return null
        return doubleArrayOf(
            x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
            y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
            g, h, 1.0,
        )
    }

    private fun apply(h: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val w = h[6] * x + h[7] * y + h[8]
        if (kotlin.math.abs(w) < 1e-12) return Pair(0.0, 0.0)
        return Pair(
            (h[0] * x + h[1] * y + h[2]) / w,
            (h[3] * x + h[4] * y + h[5]) / w,
        )
    }

    /**
     * Refines every predicted corner.
     *
     * `quad` holds the four outer inner corners of the grid: top-left,
     * top-right, bottom-right, bottom-left, each as the operator tapped it.
     */
    fun refineBoard(
        gray: ByteArray,
        width: Int,
        height: Int,
        columns: Int,
        rows: Int,
        quad: List<ArKernelBoardCornerInput>,
    ): ArKernelBoardResult {
        val expected = columns * rows
        val homography = homographyForQuad(quad)
            ?: return refused(
                ArKernelBoardRejection.QUAD_DEGENERATE,
                "Die vier Punkte ergeben kein flaches Viereck", expected)

        // Grid coordinates run 0..columns-1 and 0..rows-1; the unit square the
        // quad defines spans the same range, so scale the unit square up.
        val spanX = (columns - 1).toDouble().coerceAtLeast(1.0)
        val spanY = (rows - 1).toDouble().coerceAtLeast(1.0)
        val scale = doubleArrayOf(
            1.0 / spanX, 0.0, 0.0,
            0.0, 1.0 / spanY, 0.0,
            0.0, 0.0, 1.0,
        )
        val gridToImage = multiply3(homography, scale)

        val corners = ArrayList<Pair<Double, Double>>(expected)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                corners += apply(gridToImage, column.toDouble(), row.toDouble())
            }
        }

        // Spacing comes from the prediction, so the search scales with the
        // board however close or far the operator is holding it.
        val spacing = meanSpacing(corners, columns, rows)
        if (!spacing.isFinite() || spacing < MIN_SQUARE_PX) {
            return refused(
                ArKernelBoardRejection.SQUARES_TOO_SMALL,
                "Ein Kaestchen ist nur ${fmt(spacing)} px gross; " +
                    "naeher herangehen", expected)
        }
        val image = ArKernelImageIntegral(gray, width, height)
        val refined = ArrayList<ArKernelBoardCorner>(expected)
        var scoreSum = 0.0
        var shiftSum = 0.0
        var validCount = 0
        var positive = 0
        var negative = 0

        // Working copy of the prediction; replaced by the found positions
        // after every pass so the next one starts from the truth it found.
        val working = ArrayList(corners)

        for (pass in 0 until PASSES) {
            refined.clear()
            validCount = 0
            scoreSum = 0.0
            shiftSum = 0.0
            positive = 0
            negative = 0
            val results = ArrayList<Pair<Double, Double>>(expected)
            for (index in 0 until expected) {
            val (px, py) = working[index]
            // Under perspective the squares change size across the board, so
            // one scale for the whole board is wrong at both ends: too small
            // where the board is near, too large - and straddling two squares
            // - where it is far. Each corner is measured with its own
            // neighbours' spacing.
            val local = localSpacing(working, columns, rows, index)
                .takeIf { it.isFinite() && it > 0.0 } ?: spacing
            val axes = gridAxes(working, columns, rows, index)
            val boxHalf = (local * 0.25).toInt().coerceIn(1, 64)
            val search = (local * SEARCH_FRACTION).toInt().coerceAtLeast(1)
            val best = searchCorner(
                image, px, py,
                axes[0], axes[1], axes[2], axes[3],
                boxHalf, search, width, height)
            val shift = kotlin.math.hypot(best.first - px, best.second - py)
            val magnitude = kotlin.math.abs(best.third)
            val usable = magnitude >= MIN_RESPONSE &&
                shift <= local * MAX_SHIFT_FRACTION &&
                best.first > 0.0 && best.second > 0.0 &&
                best.first < width.toDouble() && best.second < height.toDouble()
            if (usable) {
                validCount++
                scoreSum += magnitude
                shiftSum += shift
                // Orientation is expected to flip with the parity of
                // column + row, so remove that flip before comparing.
                val column = index % columns
                val row = index / columns
                val parity = if ((column + row) % 2 == 0) 1.0 else -1.0
                val oriented = best.third * parity
                if (oriented > 0.0) positive++ else negative++
            }
            results += Pair(best.first, best.second)
            refined += ArKernelBoardCorner(
                xPx = best.first,
                yPx = best.second,
                score = best.third,
                shiftPx = shift,
                valid = usable,
            )
            }
            // Found corners drive the next pass; a corner that failed keeps
            // its prediction so it is tried again from where it was expected.
            //
            // Movement away from the prediction is capped at half a square.
            // Each pass is free to walk, and a corner that walks half a square
            // has reached its neighbour's territory - at which point it is no
            // longer the corner it was asked for, and the fit downstream would
            // read the jump as distortion rather than as a mistake.
            for (index in 0 until expected) {
                if (!refined[index].valid) continue
                val anchor = corners[index]
                val moved = results[index]
                val limit = (localSpacing(working, columns, rows, index) * 0.5)
                    .takeIf { it.isFinite() && it > 0.0 } ?: (spacing * 0.5)
                val distance = kotlin.math.hypot(
                    moved.first - anchor.first, moved.second - anchor.second)
                working[index] = if (distance <= limit) {
                    moved
                } else {
                    val scale = limit / distance
                    Pair(
                        anchor.first + (moved.first - anchor.first) * scale,
                        anchor.second + (moved.second - anchor.second) * scale,
                    )
                }
            }
        }

        // Final fine pass: the corner is already localised, so now the
        // measurement is taken through a small window where the distortion
        // bias is small, and only a couple of pixels of movement are allowed.
        refined.clear()
        validCount = 0
        scoreSum = 0.0
        shiftSum = 0.0
        positive = 0
        negative = 0
        val fine = ArrayList<Pair<Double, Double>>(expected)
        var cutOff = 0
        for (index in 0 until expected) {
            val (px, py) = working[index]
            val local = localSpacing(working, columns, rows, index)
                .takeIf { it.isFinite() && it > 0.0 } ?: spacing
            val axes = gridAxes(working, columns, rows, index)
            val boxHalf = (local * FINE_BOX_FRACTION).toInt().coerceIn(1, 64)
            val fits = measurementFits(
                px, py, axes[0], axes[1], axes[2], axes[3],
                boxHalf, local, width, height)
            if (!fits) cutOff++
            val best = searchCorner(
                image, px, py,
                axes[0], axes[1], axes[2], axes[3],
                boxHalf, FINE_SEARCH_PX, width, height)
            val point = refineSubPixel(
                gray, width, height, best.first, best.second, local)
            val magnitude = kotlin.math.abs(
                responseAlongAxes(
                    image, point.first, point.second,
                    axes[0], axes[1], axes[2], axes[3],
                    (local * 0.25).toInt().coerceIn(1, 64)))
            val score = if (best.third >= 0.0) magnitude else -magnitude
            val shift = kotlin.math.hypot(point.first - px, point.second - py)
            val usable = fits &&
                magnitude >= MIN_RESPONSE &&
                shift <= local * MAX_SHIFT_FRACTION &&
                point.first > 0.0 && point.second > 0.0 &&
                point.first < width.toDouble() && point.second < height.toDouble()
            if (usable) {
                validCount++
                scoreSum += magnitude
                shiftSum += shift
                val column = index % columns
                val row = index / columns
                val parity = if ((column + row) % 2 == 0) 1.0 else -1.0
                if (score * parity > 0.0) positive++ else negative++
            }
            fine += Pair(point.first, point.second)
            refined += ArKernelBoardCorner(
                xPx = point.first,
                yPx = point.second,
                score = score,
                shiftPx = shift,
                valid = usable,
            )
        }

        if (validCount < 1) {
            return refused(
                ArKernelBoardRejection.LOW_CONTRAST,
                "Keine Ecke gefunden: Kontrast zu gering oder die " +
                    "vier Punkte sitzen falsch", expected)
        }
        // Every inner corner of one board shares an orientation. A mixture
        // means the search found something that is not the board.
        val minority = kotlin.math.min(positive, negative).toDouble()
        val share = minority / validCount.toDouble()
        if (share > 0.15) {
            return refused(
                ArKernelBoardRejection.INCONSISTENT_ORIENTATION,
                "${(share * 100).toInt()} % der Ecken wechseln die " +
                    "Orientierung nicht im Schachbrettmuster; die gefunden " +
                    "Punkte sind kein Raster",
                expected)
        }
        val validFraction = validCount.toDouble() / expected.toDouble()
        if (cutOff > 0) {
            return refused(
                ArKernelBoardRejection.BOARD_NOT_FULLY_VISIBLE,
                "$cutOff von $expected Ecken liegen zu nah am Bildrand; " +
                    "das Schachbrett muss vollstaendig im Bild sein", expected)
        }
        if (validFraction < MIN_VALID_FRACTION) {
            return refused(
                ArKernelBoardRejection.TOO_FEW_CORNERS,
                "Nur $validCount von $expected Ecken brauchbar", expected)
        }
        return ArKernelBoardResult(
            corners = refined,
            rejection = ArKernelBoardRejection.OK,
            detail = "$validCount von $expected Ecken, " +
                "Verschiebung ${fmt(shiftSum / validCount)} px, " +
                "Kontrast ${fmt(scoreSum / validCount)}",
            validCount = validCount,
            meanShiftPx = shiftSum / validCount,
            meanScore = scoreSum / validCount,
        )
    }

    /** One tapped point. Named apart from the result so a tap is not a corner. */
    data class ArKernelBoardCornerInput(val xPx: Double, val yPx: Double)

    /**
     * The strongest corner inside the window, refined to a fraction of a pixel
     * by a parabola through the three samples across the peak.
     */
    private fun searchCorner(
        image: ArKernelImageIntegral,
        px: Double,
        py: Double,
        ux: Double,
        uy: Double,
        vx: Double,
        vy: Double,
        boxHalf: Int,
        search: Int,
        width: Int,
        height: Int,
    ): Triple<Double, Double, Double> {
        val baseX = kotlin.math.round(px).toInt()
        val baseY = kotlin.math.round(py).toInt()

        // Two passes over the search window: one to find how strong the best
        // response is, one to find where it is.
        //
        // The second pass is a centre of mass and not a simple maximum, and
        // the reason matters. The response is a difference of area means, so
        // on a sharply printed board it is *exactly constant* over every
        // position where all four boxes stay inside their own square. That
        // flat top is several pixels wide, and taking the first maximum the
        // scan happens to reach puts the corner at the edge of the plateau -
        // always in the same direction, because the scan order is fixed. Feed
        // that back through several passes and the corners walk, one plateau
        // edge at a time, all the way onto the neighbouring square, which is
        // one whole square of error that no amount of thresholding would
        // report. The centre of the flat top has no such direction.
        var bestValue = 0.0
        var bestSigned = 0.0
        for (dy in -search..search) {
            val y = baseY + dy
            if (y < 0 || y >= height) continue
            for (dx in -search..search) {
                val x = baseX + dx
                if (x < 0 || x >= width) continue
                val value = responseAlongAxes(
                    image, x.toDouble(), y.toDouble(),
                    ux, uy, vx, vy, boxHalf)
                if (kotlin.math.abs(value) > bestValue) {
                    bestValue = kotlin.math.abs(value)
                    // The sign is the corner's orientation, so it travels
                    // with the magnitude and must not be turned into one.
                    bestSigned = value
                }
            }
        }
        // Tolerance is a fraction of the best response, not a rounding
        // epsilon. Within the plateau the response varies only by sensor
        // noise - a fraction of a percent of the response - while leaving the
        // plateau costs a quarter of it, so a few percent separates them
        // cleanly. With an epsilon instead, a noisy plateau contributes one
        // or two pixels that happen to hold the maximum, and the centre of
        // mass lands wherever those few pixels are: a jitter of half a square
        // that appears on individual corners while leaving the mean - and
        // every plausibility check - looking perfectly healthy.
        val floor = bestValue * PLATEAU_TOLERANCE
        var sumX = 0.0
        var sumY = 0.0
        var weight = 0.0
        for (dy in -search..search) {
            val y = baseY + dy
            if (y < 0 || y >= height) continue
            for (dx in -search..search) {
                val x = baseX + dx
                if (x < 0 || x >= width) continue
                val value = kotlin.math.abs(
                    responseAlongAxes(
                        image, x.toDouble(), y.toDouble(),
                        ux, uy, vx, vy, boxHalf))
                if (value >= floor && value > 0.0) {
                    sumX += x.toDouble()
                    sumY += y.toDouble()
                    weight += 1.0
                }
            }
        }
        val bestX = if (weight > 0.0) sumX / weight else baseX.toDouble()
        val bestY = if (weight > 0.0) sumY / weight else baseY.toDouble()

        // Whole-pixel centre only. Locating the corner *below* the pixel is
        // left to [refineSubPixel], because the box response is a staircase
        // and interpolating its flat top invents precision that is not there.
        return Triple(bestX, bestY, bestSigned)
    }

    /**
     * Sub-pixel position of the saddle, taken from the edges around it.
     *
     * The box response cannot locate a corner below the pixel, and it is worth
     * saying exactly why: it is a difference of area means, so it changes only
     * when one of the four boxes crosses an edge. Between crossings it is
     * *constant* - a plateau, not a peak - and on a sharply printed board that
     * plateau is several pixels wide. Fitting a parabola to three samples of a
     * staircase returns whichever end of the flat top the search reached
     * first, which is precise, repeatable and wrong.
     *
     * The edges carry the information instead. A pixel on an edge has a
     * gradient perpendicular to that edge, and both edges of the saddle pass
     * through the corner, so the corner is the point `p` satisfying
     * `g . (q - p) = 0` for as many edge pixels `q` as possible. Solving that
     * in least squares over a weighted window gives the corner to a fraction
     * of a pixel, needs no smooth maximum to exist, and improves as the
     * printing gets sharper rather than worse.
     */
    private fun refineSubPixel(
        gray: ByteArray,
        width: Int,
        height: Int,
        startX: Double,
        startY: Double,
        spacing: Double,
    ): Pair<Double, Double> {
        var x = startX
        var y = startY
        // Coarse first, fine after. The window has to be wide enough at the
        // start to contain the corner even when the coarse search is most of a
        // square away, which happens when the squares are large: the plateau
        // in the box response scales with the square, so the coarse answer
        // gets less precise as the squares get bigger. Once the corner is
        // caught, the window shrinks so that the far edges of the surrounding
        // squares stop voting on where it is.
        var window = (spacing * 0.5).toInt().coerceIn(3, 24)
        repeat(SUBPIXEL_STEPS) {
            val solution = solveSaddle(
                gray, width, height, x, y, window,
                (window * 0.5).coerceAtLeast(1.0))
            x = solution.first
            y = solution.second
            window = (window * 0.6).toInt().coerceAtLeast(3)
        }
        val drift = kotlin.math.hypot(x - startX, y - startY)
        if (!drift.isFinite() || drift > spacing * 0.5) {
            return Pair(startX, startY)
        }
        return Pair(x, y)
    }

    /** One least-squares solve for the point the surrounding edges imply. */
    private fun solveSaddle(
        gray: ByteArray,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
        halfWindow: Int,
        sigma: Double,
    ): Pair<Double, Double> {
        val cx = kotlin.math.round(x).toInt()
        val cy = kotlin.math.round(y).toInt()
        var a11 = 0.0
        var a12 = 0.0
        var a22 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        val twoSigmaSquared = 2.0 * sigma * sigma
        for (dy in -halfWindow..halfWindow) {
            val py = cy + dy
            if (py < 1 || py >= height - 1) continue
            for (dx in -halfWindow..halfWindow) {
                val qx = cx + dx
                if (qx < 1 || qx >= width - 1) continue
                val gx = (lum(gray, width, qx + 1, py) -
                    lum(gray, width, qx - 1, py)).toDouble()
                val gy = (lum(gray, width, qx, py + 1) -
                    lum(gray, width, qx, py - 1)).toDouble()
                if (gx == 0.0 && gy == 0.0) continue
                // Gaussian, so the far edges of neighbouring squares do not
                // vote on where this corner is.
                val weight = kotlin.math.exp(
                    -(dx * dx + dy * dy).toDouble() / twoSigmaSquared)
                a11 += gx * gx * weight
                a12 += gx * gy * weight
                a22 += gy * gy * weight
                b1 += (gx * gx * qx + gx * gy * py) * weight
                b2 += (gx * gy * qx + gy * gy * py) * weight
            }
        }
        val determinant = a11 * a22 - a12 * a12
        if (!determinant.isFinite() || kotlin.math.abs(determinant) < 1e-9) {
            return Pair(x, y)
        }
        return Pair(
            (a22 * b1 - a12 * b2) / determinant,
            (a11 * b2 - a12 * b1) / determinant,
        )
    }

    private fun lum(gray: ByteArray, width: Int, x: Int, y: Int): Int =
        gray[y * width + x].toInt() and 0xFF

    /**
     * One grid step along each board axis, in image pixels.
     *
     * Taken from the corner's own neighbours rather than from the homography's
     * derivative, so it stays right under perspective and in-plane rotation
     * alike: it is where the neighbouring corners actually are.
     */
    private fun gridAxes(
        corners: List<Pair<Double, Double>>,
        columns: Int,
        rows: Int,
        index: Int,
    ): DoubleArray {
        val column = index % columns
        val row = index / columns
        val here = corners[index]
        var ux = 0.0
        var uy = 0.0
        when {
            column + 1 < columns -> {
                ux = corners[index + 1].first - here.first
                uy = corners[index + 1].second - here.second
            }
            column - 1 >= 0 -> {
                ux = here.first - corners[index - 1].first
                uy = here.second - corners[index - 1].second
            }
        }
        var vx = 0.0
        var vy = 0.0
        when {
            row + 1 < rows -> {
                vx = corners[index + columns].first - here.first
                vy = corners[index + columns].second - here.second
            }
            row - 1 >= 0 -> {
                vx = here.first - corners[index - columns].first
                vy = here.second - corners[index - columns].second
            }
        }
        return doubleArrayOf(ux, uy, vx, vy)
    }

    /**
     * Whether all four measuring boxes and the refinement window lie wholly
     * inside the image.
     *
     * A window that hangs over the edge is not a smaller window, it is a
     * different measurement: the part outside the image comes back as
     * whatever the clamp returns, and a box that is half square and half clamp
     * is indistinguishable from a whole square.
     */
    private fun measurementFits(
        cx: Double,
        cy: Double,
        ux: Double,
        uy: Double,
        vx: Double,
        vy: Double,
        boxHalf: Int,
        spacing: Double,
        width: Int,
        height: Int,
    ): Boolean {
        val centres = doubleArrayOf(
            cx + 0.5 * (ux + vx), cy + 0.5 * (uy + vy),
            cx - 0.5 * (ux + vx), cy - 0.5 * (uy + vy),
            cx + 0.5 * (ux - vx), cy + 0.5 * (uy - vy),
            cx - 0.5 * (ux - vx), cy - 0.5 * (uy - vy),
        )
        for (index in 0..3) {
            val x = centres[index * 2]
            val y = centres[index * 2 + 1]
            if (x - boxHalf < 0.0 || x + boxHalf >= width.toDouble()) return false
            if (y - boxHalf < 0.0 || y + boxHalf >= height.toDouble()) return false
        }
        // The refinement window starts at half a square; one pixel more for
        // the gradient stencil.
        val margin = spacing * 0.5 + 2.0
        return cx - margin >= 0.0 && cx + margin < width.toDouble() &&
            cy - margin >= 0.0 && cy + margin < height.toDouble()
    }

    /** Mean distance from one predicted corner to its existing neighbours. */
    private fun localSpacing(
        corners: List<Pair<Double, Double>>,
        columns: Int,
        rows: Int,
        index: Int,
    ): Double {
        val column = index % columns
        val row = index / columns
        var total = 0.0
        var count = 0
        val neighbours = ArrayList<Int>(4)
        if (column + 1 < columns) neighbours += index + 1
        if (column - 1 >= 0) neighbours += index - 1
        if (row + 1 < rows) neighbours += index + columns
        if (row - 1 >= 0) neighbours += index - columns
        for (other in neighbours) {
            total += kotlin.math.hypot(
                corners[other].first - corners[index].first,
                corners[other].second - corners[index].second)
            count++
        }
        return if (count == 0) 0.0 else total / count
    }

    /** Mean distance between horizontally adjacent predicted corners. */
    private fun meanSpacing(
        corners: List<Pair<Double, Double>>,
        columns: Int,
        rows: Int,
    ): Double {
        var total = 0.0
        var count = 0
        for (row in 0 until rows) {
            for (column in 0 until columns - 1) {
                val a = corners[row * columns + column]
                val b = corners[row * columns + column + 1]
                total += kotlin.math.hypot(b.first - a.first, b.second - a.second)
                count++
            }
        }
        if (count == 0) return 0.0
        return total / count
    }

    private fun multiply3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                out[i * 3 + j] = a[i * 3] * b[j] +
                    a[i * 3 + 1] * b[3 + j] +
                    a[i * 3 + 2] * b[6 + j]
            }
        }
        return out
    }

    private fun fmt(value: Double): String =
        if (value.isFinite()) "%.2f".format(java.util.Locale.US, value) else "n/a"

    private fun refused(
        rejection: ArKernelBoardRejection,
        detail: String,
        expected: Int,
    ): ArKernelBoardResult = ArKernelBoardResult(
        corners = emptyList(),
        rejection = rejection,
        detail = detail,
        validCount = 0,
        meanShiftPx = Double.NaN,
        meanScore = Double.NaN,
    )
}
