package ai.arena.arkernel

/**
 * Recovers the camera intrinsics from images of a planar grid, and refuses to
 * when it cannot.
 *
 * **Why this exists.** Every record this app produced so far took the camera
 * as the manufacturer described it. `ARKCAMT1` stores `focal_length_mm`
 * because that is what the platform reports; `ARKCAMEXT1` recovers a focal
 * length in pixels, but only as the *norm* that an orthonormal rotation
 * imposes, and it says so. Neither measures the principal point, neither
 * separates `fx` from `fy`, and neither measures distortion. Those three are
 * the difference between a projection that is roughly right and one that is
 * right, and they are measured here.
 *
 * **The method is Zhang's**, in the form that stays checkable: a planar grid
 * gives one homography per view; each homography gives two linear constraints
 * on `B = K^-T K^-1`; stacking the views solves for `K` in closed form; the
 * radial coefficients then enter *linearly* once poses are known, so they fall
 * out of a two-by-two least squares. The two steps are alternated, because
 * each is only correct given the other's current answer.
 *
 * **No external reference is needed for this, and that is worth saying
 * plainly.** Intrinsics do not depend on how large the squares physically are,
 * only on their shape: scaling the whole target scales every homography and
 * leaves the constraints homogeneous. So the operator needs a grid with equal
 * squares, not a *measured* one - a printed sheet is enough. What cannot be
 * recovered is anything metric, and this file does not claim any.
 *
 * **This is a completeness result, not a quality grade.** `sufficient` says
 * the views were rich enough to identify the parameters at all. It does not
 * say the recovered values are the camera's true ones - that would need an
 * independent measurement, which this is not.
 */

/** One detected grid corner, in pixels, as the detector placed it. */
data class ArKernelIntrinsicsPoint(val xPx: Double, val yPx: Double)

/**
 * One view of the grid: every inner corner, in row-major order, top-left
 * first. A missing corner is not a view; the caller must not interpolate one.
 */
data class ArKernelIntrinsicsView(val points: List<ArKernelIntrinsicsPoint>)

/** The pinhole-with-radial-distortion model this file fits. */
data class ArKernelCameraIntrinsics(
    val fxPx: Double,
    val fyPx: Double,
    val cxPx: Double,
    val cyPx: Double,
    val k1: Double,
    val k2: Double,
) {
    init {
        require(fxPx.isFinite() && fxPx > 0.0) { "fx must be positive" }
        require(fyPx.isFinite() && fyPx > 0.0) { "fy must be positive" }
        require(cxPx.isFinite() && cyPx.isFinite()) { "principal point must be finite" }
        require(k1.isFinite() && k2.isFinite()) { "distortion must be finite" }
    }

    /** Horizontal field of view in degrees, which is what a person checks. */
    fun horizontalFovDeg(imageWidthPx: Int): Double =
        2.0 * Math.toDegrees(kotlin.math.atan(imageWidthPx / (2.0 * fxPx)))
}

/** Why an estimate was refused. Every one of these is permanent input. */
enum class ArKernelIntrinsicsRejection {
    OK,
    TOO_FEW_VIEWS,
    GRID_MISMATCH,
    NON_FINITE_INPUT,
    DEGENERATE,
    POOR_COVERAGE,
    RESIDUAL_TOO_LARGE,
    PRINCIPAL_POINT_IMPLAUSIBLE,
    FOCAL_SPLIT_TOO_LARGE,
    DISTORTION_UNBOUNDED,
    ;

    val accepted: Boolean get() = this == OK
}

/** What one fit produced, whether or not it was accepted. */
data class ArKernelIntrinsicsEstimate(
    val intrinsics: ArKernelCameraIntrinsics?,
    val rejection: ArKernelIntrinsicsRejection,
    val detail: String,
    val rmsPx: Double,
    val maxPx: Double,
    val viewCount: Int,
    val pointCount: Int,
    /** Mean fraction of the frame the grid covered, per thousand. */
    val coveragePermille: Int,
    /** Smallest over largest pivot of the intrinsics system, per thousand. */
    val conditioningPermille: Int,
    /** `|fx-fy|` over their mean, per thousand. */
    val focalSplitPermille: Int,
    val verdicts: Map<String, Boolean>,
) {
    val accepted: Boolean get() = rejection.accepted
}

object ArKernelIntrinsicsEstimator {

    /** Fewer views than this cannot identify five intrinsics robustly. */
    const val MIN_VIEWS = 6

    /** The grid has to have enough corners to constrain a homography. */
    const val MIN_INNER_CORNERS = 12

    /**
     * The grid has to cover this fraction of the frame on average. A target
     * held small in the middle of the image identifies the focal length
     * poorly, and no residual test catches that: the fit is excellent and
     * wrong.
     */
    const val MIN_COVERAGE_PERMILLE = 120

    /** Below this the system is numerically indistinguishable from singular. */
    const val MIN_CONDITIONING_PERMILLE = 15

    /** Synthetic-free data should reproject inside a pixel or two. */
    const val MAX_RMS_PX = 2.0
    const val MAX_MAX_PX = 8.0

    /**
     * `fx` and `fy` disagreeing by more than this is treated as a fit that
     * ran away rather than a camera with non-square pixels.
     *
     * The limit is deliberately loose. Square pixels are the common case, but
     * "common" is not "true", and rejecting an unusual camera would hide the
     * one thing worth reporting about it. Everything a real failure looks
     * like - a principal point outside the frame, a large residual, a
     * singular system - is caught by its own gate; this one only has to catch
     * what the others miss.
     */
    const val MAX_FOCAL_SPLIT_PERMILLE = 300

    /** Beyond this the radial model is being asked for something else. */
    const val MAX_ABS_K1 = 1.0
    const val MAX_ABS_K2 = 1.0

    /** The principal point outside the frame means the fit ran away. */
    const val MAX_PRINCIPAL_OFFSET_PERMILLE = 250

    private const val ALTERNATIONS = 6
    private const val UNDISTORT_STEPS = 8

    /**
     * Damped Gauss-Newton steps after the closed form.
     *
     * The alternation above converges, but slowly and not all the way: it
     * reaches a pixel or two and then creeps. The closed form is an excellent
     * starting point and a poor answer, so the answer is produced here, by
     * minimising the reprojection error over the intrinsics and every pose at
     * once.
     *
     * The Jacobian is numerical. An analytic one would be faster; this one
     * cannot disagree with the residual it differentiates, which for code
     * nobody can run on a device is worth more than speed.
     */
    private const val REFINE_STEPS = 25

    /** Finite-difference step, scaled to each parameter's own magnitude. */
    private const val JACOBIAN_STEP = 1e-6

    fun estimate(
        views: List<ArKernelIntrinsicsView>,
        gridColumns: Int,
        gridRows: Int,
        imageWidthPx: Int,
        imageHeightPx: Int,
    ): ArKernelIntrinsicsEstimate {
        val expected = gridColumns * gridRows
        if (views.size < MIN_VIEWS) {
            return refused(
                ArKernelIntrinsicsRejection.TOO_FEW_VIEWS,
                "${views.size} Aufnahmen, mindestens $MIN_VIEWS noetig",
                views.size, 0, 0, 0, 0,
            )
        }
        if (gridColumns < 3 || gridRows < 3 || expected < MIN_INNER_CORNERS) {
            return refused(
                ArKernelIntrinsicsRejection.GRID_MISMATCH,
                "Raster $gridColumns x $gridRows ist zu klein",
                views.size, 0, 0, 0, 0,
            )
        }
        var pointCount = 0
        for (view in views) {
            if (view.points.size != expected) {
                return refused(
                    ArKernelIntrinsicsRejection.GRID_MISMATCH,
                    "Eine Aufnahme hat ${view.points.size} Ecken, erwartet $expected",
                    views.size, 0, 0, 0, 0,
                )
            }
            for (point in view.points) {
                if (!point.xPx.isFinite() || !point.yPx.isFinite()) {
                    return refused(
                        ArKernelIntrinsicsRejection.NON_FINITE_INPUT,
                        "Eine Ecke ist keine endliche Zahl",
                        views.size, 0, 0, 0, 0,
                    )
                }
            }
            pointCount += expected
        }

        val coverage = coveragePermille(views, imageWidthPx, imageHeightPx)
        if (coverage < MIN_COVERAGE_PERMILLE) {
            return refused(
                ArKernelIntrinsicsRejection.POOR_COVERAGE,
                "Das Raster bedeckt im Mittel nur ${coverage / 10.0} % des Bildes",
                views.size, pointCount, coverage, 0, 0,
            )
        }

        // Target coordinates: the grid in square units. The unit cancels,
        // which is exactly why the squares need not be measured.
        val targetX = DoubleArray(expected)
        val targetY = DoubleArray(expected)
        var index = 0
        for (row in 0 until gridRows) {
            for (column in 0 until gridColumns) {
                targetX[index] = column.toDouble()
                targetY[index] = row.toDouble()
                index++
            }
        }

        // Work in normalised coordinates. Without this the homography system
        // mixes pixel magnitudes with unit magnitudes and the pivots differ by
        // orders of magnitude, which is a numerical failure that looks like a
        // successful fit.
        val normalisation = Normalisation.of(targetX, targetY)
        val normTargetX = normalisation.applyX(targetX)
        val normTargetY = normalisation.applyY(targetY)

        var intrinsics: ArKernelCameraIntrinsics? = null
        var poses: List<Pose> = emptyList()
        var conditioning = 0
        for (round in 0 until ALTERNATIONS) {
            val homographies = ArrayList<DoubleArray>(views.size)
            for (view in views) {
                val image: Array<DoubleArray> = if (intrinsics == null) {
                    arrayOf(
                        DoubleArray(expected) { view.points[it].xPx },
                        DoubleArray(expected) { view.points[it].yPx },
                    )
                } else {
                    undistort(intrinsics, view.points)
                }
                val normalised = Normalisation.ofPixels(image[0], image[1])
                val h = homographyFrom(
                    normTargetX, normTargetY,
                    normalised.applyX(image[0]), normalised.applyY(image[1]),
                ) ?: return refused(
                    ArKernelIntrinsicsRejection.DEGENERATE,
                    "Die Abbildung einer Aufnahme ist nicht bestimmbar",
                    views.size, pointCount, coverage, 0, 0,
                )
                homographies.add(
                    denormaliseHomography(h, normalisation, normalised))
            }
            val solved = solveIntrinsics(homographies)
                ?: return refused(
                    ArKernelIntrinsicsRejection.DEGENERATE,
                    "Das Intrinsics-System ist singulaer",
                    views.size, pointCount, coverage, 0, 0,
                )
            conditioning = solved.conditioningPermille
            if (conditioning < MIN_CONDITIONING_PERMILLE) {
                return refused(
                    ArKernelIntrinsicsRejection.DEGENERATE,
                    "Konditionierung $conditioning ist zu schlecht: die " +
                        "Aufnahmen unterscheiden sich zu wenig",
                    views.size, pointCount, coverage, conditioning, 0,
                )
            }
            poses = homographies.map { poseFrom(it, solved.intrinsics) }
            val distortion = solveDistortion(
                solved.intrinsics, poses, views, targetX, targetY)
            intrinsics = ArKernelCameraIntrinsics(
                fxPx = solved.intrinsics.fxPx,
                fyPx = solved.intrinsics.fyPx,
                cxPx = solved.intrinsics.cxPx,
                cyPx = solved.intrinsics.cyPx,
                k1 = distortion[0],
                k2 = distortion[1],
            )
        }
        val fitted: ArKernelCameraIntrinsics
        val finalPoses: List<Pose>
        val refined = refine(intrinsics!!, poses, views, targetX, targetY)
        if (refined != null &&
            sumSquares(residualsFor(pack(refined.first, refined.second),
                views, targetX, targetY)) <
            sumSquares(residualsFor(pack(intrinsics, poses), views, targetX, targetY))
        ) {
            fitted = refined.first
            finalPoses = refined.second
        } else {
            // The closed form stays when refinement cannot beat it. A worse
            // answer with a better story is still a worse answer.
            fitted = intrinsics
            finalPoses = poses
        }
        val residual = residual(fitted, finalPoses, views, targetX, targetY)
        val split = focalSplitPermille(fitted)
        val verdicts = linkedMapOf(
            "conditioning_ok" to (conditioning >= MIN_CONDITIONING_PERMILLE),
            "coverage_ok" to (coverage >= MIN_COVERAGE_PERMILLE),
            "residual_ok" to (residual.rms <= MAX_RMS_PX && residual.max <= MAX_MAX_PX),
            "principal_point_plausible" to principalPointPlausible(
                fitted, imageWidthPx, imageHeightPx),
            "focal_split_ok" to (split <= MAX_FOCAL_SPLIT_PERMILLE),
            "distortion_bounded" to (kotlin.math.abs(fitted.k1) <= MAX_ABS_K1 &&
                kotlin.math.abs(fitted.k2) <= MAX_ABS_K2),
        )
        val rejection = when {
            !verdicts.getValue("residual_ok") ->
                ArKernelIntrinsicsRejection.RESIDUAL_TOO_LARGE
            !verdicts.getValue("principal_point_plausible") ->
                ArKernelIntrinsicsRejection.PRINCIPAL_POINT_IMPLAUSIBLE
            !verdicts.getValue("focal_split_ok") ->
                ArKernelIntrinsicsRejection.FOCAL_SPLIT_TOO_LARGE
            !verdicts.getValue("distortion_bounded") ->
                ArKernelIntrinsicsRejection.DISTORTION_UNBOUNDED
            else -> ArKernelIntrinsicsRejection.OK
        }
        return ArKernelIntrinsicsEstimate(
            intrinsics = if (rejection.accepted) fitted else null,
            rejection = rejection,
            detail = if (rejection.accepted) {
                "fx=${format(fitted.fxPx)} px, fy=${format(fitted.fyPx)} px, " +
                    "Hauptpunkt (${format(fitted.cxPx)}, ${format(fitted.cyPx)}), " +
                    "k1=${format(fitted.k1)}, k2=${format(fitted.k2)}, " +
                    "Rueckprojektion ${format(residual.rms)} px im Mittel"
            } else {
                val open = verdicts.filter { !it.value }.keys.joinToString(", ")
                "Abgelehnt ($rejection); offen: $open. " +
                    "Rms ${format(residual.rms)} px, " +
                    "Konditionierung $conditioning, Deckung $coverage."
            },
            rmsPx = residual.rms,
            maxPx = residual.max,
            viewCount = views.size,
            pointCount = pointCount,
            coveragePermille = coverage,
            conditioningPermille = conditioning,
            focalSplitPermille = split,
            verdicts = verdicts,
        )
    }

    // ------------------------------------------------------------ geometry

    /** One view's pose: the rotation columns and the translation. */
    private class Pose(
        val r1: DoubleArray,
        val r2: DoubleArray,
        val r3: DoubleArray,
        val t: DoubleArray,
    )

    /**
     * Puts both coordinate sets around the origin at mean distance `sqrt(2)`,
     * which is the normalisation Hartley recommends for a homography.
     */
    private class Normalisation(
        val cx: Double,
        val cy: Double,
        val scale: Double,
    ) {
        fun applyX(values: DoubleArray): DoubleArray =
            DoubleArray(values.size) { (values[it] - cx) * scale }

        fun applyY(values: DoubleArray): DoubleArray =
            DoubleArray(values.size) { (values[it] - cy) * scale }

        companion object {
            fun of(x: DoubleArray, y: DoubleArray): Normalisation =
                ofPairs(x, y)

            fun ofPixels(x: DoubleArray, y: DoubleArray): Normalisation =
                ofPairs(x, y)

            private fun ofPairs(x: DoubleArray, y: DoubleArray): Normalisation {
                var sx = 0.0
                var sy = 0.0
                for (i in x.indices) {
                    sx += x[i]
                    sy += y[i]
                }
                val cx = sx / x.size
                val cy = sy / y.size
                var total = 0.0
                for (i in x.indices) {
                    val dx = x[i] - cx
                    val dy = y[i] - cy
                    total += kotlin.math.sqrt(dx * dx + dy * dy)
                }
                val mean = total / x.size
                val scale = if (mean > 1e-12) kotlin.math.sqrt(2.0) / mean else 1.0
                return Normalisation(cx, cy, scale)
            }
        }
    }

    /** H in normalised space, then undo the normalisations on both sides. */
    private fun denormaliseHomography(
        h: DoubleArray,
        target: Normalisation,
        image: Normalisation,
    ): DoubleArray {
        // H maps normalised target -> normalised image. With `T` the matrix
        // that normalises target points and `S` the one that normalises image
        // points, `S i = H (T t)`, so the raw homography is `S^-1 H T`.
        val imageDenormalise = doubleArrayOf(
            1.0 / image.scale, 0.0, image.cx,
            0.0, 1.0 / image.scale, image.cy,
            0.0, 0.0, 1.0,
        )
        val targetNormalise = doubleArrayOf(
            target.scale, 0.0, -target.cx * target.scale,
            0.0, target.scale, -target.cy * target.scale,
            0.0, 0.0, 1.0,
        )
        return multiply(imageDenormalise, multiply(h, targetNormalise))
    }

    /**
     * The homography taking the planar grid to the image, by the direct
     * linear transform: eight unknowns, two rows per corner, least squares.
     */
    private fun homographyFrom(
        targetX: DoubleArray,
        targetY: DoubleArray,
        imageX: DoubleArray,
        imageY: DoubleArray,
    ): DoubleArray? {
        val rows = targetX.size * 2
        val a = Array(rows) { DoubleArray(8) }
        val b = DoubleArray(rows)
        for (i in targetX.indices) {
            val x = targetX[i]
            val y = targetY[i]
            val u = imageX[i]
            val v = imageY[i]
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)
            b[i * 2] = u
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
            b[i * 2 + 1] = v
        }
        return solveLeastSquares(a, b, 8)?.let {
            doubleArrayOf(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], 1.0)
        }
    }

    private class IntrinsicsSolution(
        val intrinsics: ArKernelCameraIntrinsics,
        val conditioningPermille: Int,
    )

    /**
     * Zhang's closed form: two constraints per homography on
     * `B = K^-T K^-1`, stacked and solved with `B33 = 1` fixing the scale.
     */
    private fun solveIntrinsics(
        homographies: List<DoubleArray>,
    ): IntrinsicsSolution? {
        val rows = homographies.size * 2
        val a = Array(rows) { DoubleArray(5) }
        val b = DoubleArray(rows)
        var row = 0
        for (h in homographies) {
            // Zhang's `h1` and `h2` are the first two **columns** of
            // H = s K [r1 r2 t], not the first two rows. The rows carry no
            // orthogonality: only the columns are scaled rotations.
            val a11 = h[0]; val a21 = h[3]; val a31 = h[6]
            val a12 = h[1]; val a22 = h[4]; val a32 = h[7]

            // h1^T B h2 = 0
            a[row] = doubleArrayOf(
                a11 * a12,
                a11 * a22 + a21 * a12,
                a21 * a22,
                a11 * a32 + a31 * a12,
                a21 * a32 + a31 * a22,
            )
            b[row] = -(a31 * a32)
            row++

            // h1^T B h1 = h2^T B h2
            a[row] = doubleArrayOf(
                a11 * a11 - a12 * a12,
                2.0 * (a11 * a21 - a12 * a22),
                a21 * a21 - a22 * a22,
                2.0 * (a11 * a31 - a12 * a32),
                2.0 * (a21 * a31 - a22 * a32),
            )
            b[row] = -(a31 * a31 - a32 * a32)
            row++
        }
        val system = buildSystem(a, b, 5)
        val solved = system.solveScaled() ?: return null
        val b11 = solved[0]
        val b12 = solved[1]
        val b22 = solved[2]
        val b13 = solved[3]
        val b23 = solved[4]
        val b33 = 1.0

        val denominator = b11 * b22 - b12 * b12
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < 1e-18) return null
        val cy = (b12 * b13 - b11 * b23) / denominator
        val lambda = b33 - (b13 * b13 + cy * (b12 * b13 - b11 * b23)) / b11
        if (!lambda.isFinite() || lambda <= 0.0 || b11 <= 0.0) return null
        val fx = kotlin.math.sqrt(lambda / b11)
        val fyDen = denominator
        val fy = kotlin.math.sqrt(lambda * b11 / fyDen)
        val cx = -b13 * fx * fx / lambda
        if (!fx.isFinite() || !fy.isFinite() || !cx.isFinite() || !cy.isFinite()) {
            return null
        }
        val conditioning = system.conditioningPermille()
        return IntrinsicsSolution(
            ArKernelCameraIntrinsics(fx, fy, cx, cy, 0.0, 0.0), conditioning)
    }

    /** Recovers the pose a homography implies, then makes it orthonormal. */
    private fun poseFrom(h: DoubleArray, k: ArKernelCameraIntrinsics): Pose {
        val kInverse = inverse3(
            doubleArrayOf(k.fxPx, 0.0, k.cxPx, 0.0, k.fyPx, k.cyPx, 0.0, 0.0, 1.0))
            ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val h1 = doubleArrayOf(h[0], h[3], h[6])
        val h2 = doubleArrayOf(h[1], h[4], h[7])
        val h3 = doubleArrayOf(h[2], h[5], h[8])
        val r1Raw = apply3(kInverse, h1)
        val r2Raw = apply3(kInverse, h2)
        val tRaw = apply3(kInverse, h3)
        val scale = 1.0 / (norm3(r1Raw).takeIf { it > 1e-12 } ?: 1.0)
        var r1 = scale(r1Raw, scale)
        var r2 = scale(r2Raw, scale)
        val t = scale(tRaw, scale)
        r1 = normalise3(r1)
        // Gram-Schmidt: the homography only gives the rotation up to the
        // noise in it, and a rotation that is not orthonormal projects
        // consistently wrong everywhere.
        val dot = dot3(r1, r2)
        r2 = normalise3(doubleArrayOf(r2[0] - dot * r1[0], r2[1] - dot * r1[1],
            r2[2] - dot * r1[2]))
        val r3 = cross3(r1, r2)
        return Pose(r1, r2, r3, t)
    }

    /**
     * The radial coefficients, which are linear in this model once the poses
     * are known: the observed radius is the ideal radius scaled by
     * `1 + k1 r^2 + k2 r^4`.
     */
    private fun solveDistortion(
        k: ArKernelCameraIntrinsics,
        poses: List<Pose>,
        views: List<ArKernelIntrinsicsView>,
        targetX: DoubleArray,
        targetY: DoubleArray,
    ): DoubleArray {
        val rows = views.size * targetX.size * 2
        val a = Array(rows) { DoubleArray(2) }
        val b = DoubleArray(rows)
        var row = 0
        for (viewIndex in views.indices) {
            val pose = poses[viewIndex]
            val view = views[viewIndex]
            for (i in targetX.indices) {
                val projected = project(k, pose, targetX[i], targetY[i])
                    ?: continue
                val xn = projected[0]
                val yn = projected[1]
                val r2 = xn * xn + yn * yn
                val observed = view.points[i]
                a[row] = doubleArrayOf(xn * r2, xn * r2 * r2)
                b[row] = (observed.xPx - k.cxPx) / k.fxPx - xn
                row++
                a[row] = doubleArrayOf(yn * r2, yn * r2 * r2)
                b[row] = (observed.yPx - k.cyPx) / k.fyPx - yn
                row++
            }
        }
        if (row < 2) return doubleArrayOf(0.0, 0.0)
        val trimmedA = Array(row) { a[it] }
        val trimmedB = DoubleArray(row) { b[it] }
        val solved = solveLeastSquares(trimmedA, trimmedB, 2)
            ?: return doubleArrayOf(0.0, 0.0)
        return doubleArrayOf(solved[0].coerceIn(-MAX_ABS_K1, MAX_ABS_K1),
            solved[1].coerceIn(-MAX_ABS_K2, MAX_ABS_K2))
    }

    /**
     * Removes the distortion from observed corners by fixed-point iteration,
     * so the next intrinsics pass sees a pinhole image. This is the step that
     * makes the alternation converge instead of merely repeating.
     */
    private fun undistort(
        k: ArKernelCameraIntrinsics,
        points: List<ArKernelIntrinsicsPoint>,
    ): Array<DoubleArray> {
        val x = DoubleArray(points.size)
        val y = DoubleArray(points.size)
        for (i in points.indices) {
            val a = (points[i].xPx - k.cxPx) / k.fxPx
            val b = (points[i].yPx - k.cyPx) / k.fyPx
            var xn = a
            var yn = b
            repeat(UNDISTORT_STEPS) {
                val r2 = xn * xn + yn * yn
                val factor = 1.0 + k.k1 * r2 + k.k2 * r2 * r2
                if (!factor.isFinite() || kotlin.math.abs(factor) < 1e-9) return@repeat
                xn = a / factor
                yn = b / factor
            }
            x[i] = xn * k.fxPx + k.cxPx
            y[i] = yn * k.fyPx + k.cyPx
        }
        return arrayOf(x, y)
    }

    // ------------------------------------------------------------ refinement

    /** Intrinsics and every pose flattened into one vector. */
    private fun pack(k: ArKernelCameraIntrinsics, poses: List<Pose>): DoubleArray {
        val out = DoubleArray(6 + poses.size * 9)
        out[0] = k.fxPx
        out[1] = k.fyPx
        out[2] = k.cxPx
        out[3] = k.cyPx
        out[4] = k.k1
        out[5] = k.k2
        var i = 6
        for (pose in poses) {
            out[i++] = pose.r1[0]; out[i++] = pose.r1[1]; out[i++] = pose.r1[2]
            out[i++] = pose.r2[0]; out[i++] = pose.r2[1]; out[i++] = pose.r2[2]
            out[i++] = pose.t[0]; out[i++] = pose.t[1]; out[i++] = pose.t[2]
        }
        return out
    }

    /**
     * Poses are carried as two free vectors and made orthonormal here, so the
     * parameterisation has three redundant degrees of freedom per view. The
     * damping below is what makes that harmless.
     */
    private fun unpackPose(params: DoubleArray, base: Int): Pose {
        val r1 = normalise3(doubleArrayOf(params[base], params[base + 1], params[base + 2]))
        val raw = doubleArrayOf(params[base + 3], params[base + 4], params[base + 5])
        val d = dot3(r1, raw)
        val r2 = normalise3(
            doubleArrayOf(raw[0] - d * r1[0], raw[1] - d * r1[1], raw[2] - d * r1[2]))
        return Pose(r1, r2, cross3(r1, r2),
            doubleArrayOf(params[base + 6], params[base + 7], params[base + 8]))
    }

    /** Signed reprojection error per coordinate, over every view and corner. */
    private fun residualsFor(
        params: DoubleArray,
        views: List<ArKernelIntrinsicsView>,
        targetX: DoubleArray,
        targetY: DoubleArray,
    ): DoubleArray {
        val out = DoubleArray(views.size * targetX.size * 2)
        val fx = params[0]
        val fy = params[1]
        val cx = params[2]
        val cy = params[3]
        val k1 = params[4]
        val k2 = params[5]
        // A trial step can leave the model's domain. Report that as infinitely
        // bad rather than producing numbers that look like a fit.
        if (!(fx > 0.0) || !(fy > 0.0) || !cx.isFinite() || !cy.isFinite() ||
            !k1.isFinite() || !k2.isFinite()
        ) {
            for (i in out.indices) out[i] = 1e12
            return out
        }
        var at = 0
        for (viewIndex in views.indices) {
            val pose = unpackPose(params, 6 + viewIndex * 9)
            val view = views[viewIndex]
            for (i in targetX.indices) {
                val px = pose.r1[0] * targetX[i] + pose.r2[0] * targetY[i] + pose.t[0]
                val py = pose.r1[1] * targetX[i] + pose.r2[1] * targetY[i] + pose.t[1]
                val pz = pose.r1[2] * targetX[i] + pose.r2[2] * targetY[i] + pose.t[2]
                if (kotlin.math.abs(pz) < 1e-9) {
                    out[at++] = 1e12
                    out[at++] = 1e12
                    continue
                }
                val xn = px / pz
                val yn = py / pz
                val r2 = xn * xn + yn * yn
                val factor = 1.0 + k1 * r2 + k2 * r2 * r2
                out[at++] = fx * xn * factor + cx - view.points[i].xPx
                out[at++] = fy * yn * factor + cy - view.points[i].yPx
            }
        }
        return out
    }

    private fun sumSquares(values: DoubleArray): Double {
        var total = 0.0
        for (value in values) total += value * value
        return total
    }

    /**
     * Minimises the reprojection error over intrinsics and poses together.
     * Returns null when it cannot improve on the closed form, in which case
     * the caller keeps that instead.
     */
    private fun refine(
        start: ArKernelCameraIntrinsics,
        startPoses: List<Pose>,
        views: List<ArKernelIntrinsicsView>,
        targetX: DoubleArray,
        targetY: DoubleArray,
    ): Pair<ArKernelCameraIntrinsics, List<Pose>>? {
        var params = pack(start, startPoses)
        var residual = residualsFor(params, views, targetX, targetY)
        var cost = sumSquares(residual)
        val size = params.size
        var damping = 1e-3
        for (step in 0 until REFINE_STEPS) {
            val jacobian = Array(residual.size) { DoubleArray(size) }
            for (column in 0 until size) {
                val h = JACOBIAN_STEP * kotlin.math.abs(params[column])
                    .coerceAtLeast(1.0)
                val saved = params[column]
                params[column] = saved + h
                val forward = residualsFor(params, views, targetX, targetY)
                params[column] = saved
                for (row in residual.indices) {
                    jacobian[row][column] = (forward[row] - residual[row]) / h
                }
            }
            val jtj = Array(size) { DoubleArray(size) }
            val jtr = DoubleArray(size)
            for (row in residual.indices) {
                for (i in 0 until size) {
                    val ji = jacobian[row][i]
                    jtr[i] += ji * residual[row]
                    for (j in 0 until size) jtj[i][j] += ji * jacobian[row][j]
                }
            }
            for (i in 0 until size) jtj[i][i] += damping * (1.0 + jtj[i][i])
            val negative = DoubleArray(size) { -jtr[it] }
            val delta = solve(jtj, negative, size) ?: return null
            val trial = DoubleArray(size) { params[it] + delta[it] }
            val trialResidual = residualsFor(trial, views, targetX, targetY)
            val trialCost = sumSquares(trialResidual)
            if (trialCost < cost) {
                params = trial
                residual = trialResidual
                cost = trialCost
                damping = (damping * 0.4).coerceAtLeast(1e-12)
            } else {
                damping *= 8.0
                if (damping > 1e12) break
            }
        }
        val poses = ArrayList<Pose>(views.size)
        for (viewIndex in views.indices) {
            poses += unpackPose(params, 6 + viewIndex * 9)
        }
        return Pair(
            ArKernelCameraIntrinsics(
                params[0].coerceAtLeast(1.0),
                params[1].coerceAtLeast(1.0),
                params[2], params[3], params[4], params[5],
            ),
            poses,
        )
    }

    private class Residual(val rms: Double, val max: Double)

    private fun residual(
        k: ArKernelCameraIntrinsics,
        poses: List<Pose>,
        views: List<ArKernelIntrinsicsView>,
        targetX: DoubleArray,
        targetY: DoubleArray,
    ): Residual {
        var sum = 0.0
        var max = 0.0
        var count = 0
        for (viewIndex in views.indices) {
            val pose = poses[viewIndex]
            val view = views[viewIndex]
            for (i in targetX.indices) {
                val projected = project(k, pose, targetX[i], targetY[i]) ?: continue
                val xn = projected[0]
                val yn = projected[1]
                val r2 = xn * xn + yn * yn
                val factor = 1.0 + k.k1 * r2 + k.k2 * r2 * r2
                val u = k.fxPx * xn * factor + k.cxPx
                val v = k.fyPx * yn * factor + k.cyPx
                val dx = u - view.points[i].xPx
                val dy = v - view.points[i].yPx
                val squared = dx * dx + dy * dy
                sum += squared
                if (squared > max) max = squared
                count++
            }
        }
        if (count == 0) return Residual(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        return Residual(kotlin.math.sqrt(sum / count), kotlin.math.sqrt(max))
    }

    /** Normalised image coordinates of a target point, or null behind camera. */
    private fun project(
        k: ArKernelCameraIntrinsics,
        pose: Pose,
        targetX: Double,
        targetY: Double,
    ): DoubleArray? {
        val px = pose.r1[0] * targetX + pose.r2[0] * targetY + pose.t[0]
        val py = pose.r1[1] * targetX + pose.r2[1] * targetY + pose.t[1]
        val pz = pose.r1[2] * targetX + pose.r2[2] * targetY + pose.t[2]
        if (kotlin.math.abs(pz) < 1e-9) return null
        return doubleArrayOf(px / pz, py / pz)
    }

    private fun coveragePermille(
        views: List<ArKernelIntrinsicsView>,
        imageWidthPx: Int,
        imageHeightPx: Int,
    ): Int {
        if (imageWidthPx <= 0 || imageHeightPx <= 0) return 0
        var total = 0.0
        for (view in views) {
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (point in view.points) {
                if (point.xPx < minX) minX = point.xPx
                if (point.xPx > maxX) maxX = point.xPx
                if (point.yPx < minY) minY = point.yPx
                if (point.yPx > maxY) maxY = point.yPx
            }
            val width = (maxX - minX).coerceAtLeast(0.0)
            val height = (maxY - minY).coerceAtLeast(0.0)
            total += (width / imageWidthPx) * (height / imageHeightPx)
        }
        return ((total / views.size) * 1000.0).toInt().coerceIn(0, 1000)
    }

    private fun focalSplitPermille(k: ArKernelCameraIntrinsics): Int {
        val mean = (k.fxPx + k.fyPx) / 2.0
        if (mean <= 0.0) return 1000
        return (kotlin.math.abs(k.fxPx - k.fyPx) / mean * 1000.0).toInt()
    }

    private fun principalPointPlausible(
        k: ArKernelCameraIntrinsics,
        imageWidthPx: Int,
        imageHeightPx: Int,
    ): Boolean {
        if (imageWidthPx <= 0 || imageHeightPx <= 0) return false
        val offsetX = kotlin.math.abs(k.cxPx - imageWidthPx / 2.0) /
            (imageWidthPx / 2.0)
        val offsetY = kotlin.math.abs(k.cyPx - imageHeightPx / 2.0) /
            (imageHeightPx / 2.0)
        return (offsetX * 1000.0).toInt() <= MAX_PRINCIPAL_OFFSET_PERMILLE &&
            (offsetY * 1000.0).toInt() <= MAX_PRINCIPAL_OFFSET_PERMILLE
    }

    // --------------------------------------------------------- linear algebra

    /**
     * Least squares by normal equations, with the columns equilibrated first.
     *
     * The constraint rows mix squares of homography entries of very different
     * magnitudes, so the normal matrix spans many orders and the elimination
     * reports "singular" on problems that are perfectly well posed. Scaling
     * each column to unit norm is the standard cure, and the solution is
     * unscaled again at the end.
     */
    /** A normal-equation system whose columns have been equilibrated. */
    private class ScaledSystem(
        val matrix: Array<DoubleArray>,
        val right: DoubleArray,
        val scale: DoubleArray,
        val size: Int,
    ) {
        /** Smallest over largest pivot, per thousand. */
        fun conditioningPermille(): Int {
            val m = Array(size) { i -> matrix[i].copyOf() }
            var smallest = Double.POSITIVE_INFINITY
            var largest = 0.0
            for (column in 0 until size) {
                var pivot = column
                for (row in column + 1 until size) {
                    if (kotlin.math.abs(m[row][column]) >
                        kotlin.math.abs(m[pivot][column])
                    ) {
                        pivot = row
                    }
                }
                val value = kotlin.math.abs(m[pivot][column])
                if (value < smallest) smallest = value
                if (value > largest) largest = value
                if (value < 1e-300) return 0
                if (pivot != column) {
                    val swap = m[column]
                    m[column] = m[pivot]
                    m[pivot] = swap
                }
                val divisor = m[column][column]
                for (row in column + 1 until size) {
                    val factor = m[row][column] / divisor
                    if (factor == 0.0) continue
                    for (j in column until size) m[row][j] -= factor * m[column][j]
                }
            }
            if (largest <= 0.0) return 0
            return ((smallest / largest) * 1000.0).toInt().coerceIn(0, 1000)
        }

        fun solveScaled(): DoubleArray? {
            val solved = solve(matrix, right, size) ?: return null
            return DoubleArray(size) { solved[it] / scale[it] }
        }
    }

    /**
     * Normal equations with the columns equilibrated first.
     *
     * The constraint rows mix squares of homography entries of very different
     * magnitudes, so the unscaled normal matrix spans many orders and the
     * elimination calls well-posed problems singular. Scaling each column to
     * unit norm is the standard cure, and the solution is unscaled at the end.
     */
    private fun buildSystem(
        a: Array<DoubleArray>,
        b: DoubleArray,
        unknowns: Int,
    ): ScaledSystem {
        val scale = DoubleArray(unknowns) { 1.0 }
        for (i in 0 until unknowns) {
            var sum = 0.0
            for (row in a.indices) sum += a[row][i] * a[row][i]
            val norm = kotlin.math.sqrt(sum)
            if (norm > 1e-18) scale[i] = norm
        }
        val normal = Array(unknowns) { DoubleArray(unknowns) }
        val right = DoubleArray(unknowns)
        for (row in a.indices) {
            for (i in 0 until unknowns) {
                val ai = a[row][i] / scale[i]
                right[i] += ai * b[row]
                for (j in 0 until unknowns) {
                    normal[i][j] += ai * (a[row][j] / scale[j])
                }
            }
        }
        return ScaledSystem(normal, right, scale, unknowns)
    }

    private fun solveLeastSquares(
        a: Array<DoubleArray>,
        b: DoubleArray,
        unknowns: Int,
    ): DoubleArray? = buildSystem(a, b, unknowns).solveScaled()

    /** Gaussian elimination with partial pivoting. */
    private fun solve(
        matrix: Array<DoubleArray>,
        right: DoubleArray,
        size: Int,
    ): DoubleArray? {
        val m = Array(size) { i -> DoubleArray(size + 1) }
        for (i in 0 until size) {
            for (j in 0 until size) m[i][j] = matrix[i][j]
            m[i][size] = right[i]
        }
        for (column in 0 until size) {
            var pivot = column
            for (row in column + 1 until size) {
                if (kotlin.math.abs(m[row][column]) >
                    kotlin.math.abs(m[pivot][column])
                ) {
                    pivot = row
                }
            }
            if (kotlin.math.abs(m[pivot][column]) < 1e-18) return null
            if (pivot != column) {
                val swap = m[column]
                m[column] = m[pivot]
                m[pivot] = swap
            }
            val divisor = m[column][column]
            for (row in column + 1 until size) {
                val factor = m[row][column] / divisor
                if (factor == 0.0) continue
                for (j in column..size) m[row][j] -= factor * m[column][j]
            }
        }
        val result = DoubleArray(size)
        for (row in size - 1 downTo 0) {
            var sum = m[row][size]
            for (j in row + 1 until size) sum -= m[row][j] * result[j]
            val divisor = m[row][row]
            if (kotlin.math.abs(divisor) < 1e-18) return null
            result[row] = sum / divisor
        }
        return result
    }

    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
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

    private fun apply3(m: DoubleArray, v: DoubleArray): DoubleArray =
        doubleArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        )

    private fun inverse3(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        if (kotlin.math.abs(det) < 1e-18) return null
        val inv = DoubleArray(9)
        inv[0] = (m[4] * m[8] - m[5] * m[7]) / det
        inv[1] = (m[2] * m[7] - m[1] * m[8]) / det
        inv[2] = (m[1] * m[5] - m[2] * m[4]) / det
        inv[3] = (m[5] * m[6] - m[3] * m[8]) / det
        inv[4] = (m[0] * m[8] - m[2] * m[6]) / det
        inv[5] = (m[2] * m[3] - m[0] * m[5]) / det
        inv[6] = (m[3] * m[7] - m[4] * m[6]) / det
        inv[7] = (m[1] * m[6] - m[0] * m[7]) / det
        inv[8] = (m[0] * m[4] - m[1] * m[3]) / det
        return inv
    }

    private fun cross3(a: DoubleArray, b: DoubleArray): DoubleArray =
        doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

    private fun dot3(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun norm3(v: DoubleArray): Double =
        kotlin.math.sqrt(dot3(v, v))

    private fun scale(v: DoubleArray, factor: Double): DoubleArray =
        doubleArrayOf(v[0] * factor, v[1] * factor, v[2] * factor)

    private fun normalise3(v: DoubleArray): DoubleArray {
        val n = norm3(v)
        return if (n > 1e-12) scale(v, 1.0 / n) else doubleArrayOf(1.0, 0.0, 0.0)
    }

    private fun format(value: Double): String =
        if (value.isFinite()) "%.3f".format(java.util.Locale.US, value) else "n/a"

    /**
     * A refusal that says nothing was open is a refusal the operator cannot
     * act on, so even the early ones name the gate they failed. These are
     * checked before the fit, which is why the remaining gates are absent
     * rather than closed: the fit never happened.
     */
    private fun refused(
        rejection: ArKernelIntrinsicsRejection,
        detail: String,
        viewCount: Int,
        pointCount: Int,
        coverage: Int,
        conditioning: Int,
        split: Int,
    ): ArKernelIntrinsicsEstimate = ArKernelIntrinsicsEstimate(
        intrinsics = null,
        rejection = rejection,
        detail = "Abgelehnt ($rejection): $detail",
        rmsPx = Double.NaN,
        maxPx = Double.NaN,
        viewCount = viewCount,
        pointCount = pointCount,
        coveragePermille = coverage,
        conditioningPermille = conditioning,
        focalSplitPermille = split,
        verdicts = linkedMapOf(
            when (rejection) {
                ArKernelIntrinsicsRejection.TOO_FEW_VIEWS -> "view_count_ok"
                ArKernelIntrinsicsRejection.GRID_MISMATCH -> "grid_ok"
                ArKernelIntrinsicsRejection.NON_FINITE_INPUT -> "input_finite"
                ArKernelIntrinsicsRejection.DEGENERATE -> "conditioning_ok"
                ArKernelIntrinsicsRejection.POOR_COVERAGE -> "coverage_ok"
                else -> "accepted"
            } to false,
        ),
    )
}
