package ai.arena.arkernel

/**
 * The signal processing behind `ARKCAMIMU1`, kept free of Android so it can be
 * tested against a synthetic run with a known answer.
 *
 * Three steps, and the middle one is where the care is:
 *
 * **1. Profile.** Each frame is reduced in memory to a sparse grid of
 * luminance samples and then discarded. Nothing about the scene survives that
 * a person could recognise.
 *
 * **2. Shift.** Consecutive profiles are compared by summed absolute
 * difference over a bounded range of horizontal shifts, and the minimum is
 * refined parabolically so the estimate is not quantised to whole samples. A
 * whole-sample estimate would cap the resolution of the whole measurement at
 * one sample of image motion, which is coarser than the offset being sought.
 *
 * **3. Correlation.** A frame-to-frame shift is an *average* rate over the
 * interval between two frames, not an instantaneous one. Correlating it
 * against instantaneous gyroscope samples would bias the peak by half an
 * interval - a systematic error of around sixteen milliseconds at thirty
 * frames per second, which is larger than the quantity being measured. The
 * gyroscope is therefore averaged over exactly the same intervals as the
 * image, so both sides carry the same filtering and the peak lands where it
 * should.
 *
 * The result does not depend on the scale of either signal, only on their
 * shape, so it needs no focal length and trusts no manufacturer number.
 */
/** Image rates in px/s and the gyroscope averaged over the same intervals. */
data class ArKernelCameraImuRotationSeries(
    val rateX: DoubleArray,
    val rateY: DoubleArray,
    val gyroX: DoubleArray,
    val gyroY: DoubleArray,
    val gyroZ: DoubleArray,
)

object ArKernelCameraImuCorrelator {

    /** Samples per row of the sparse profile. */
    const val PROFILE_COLUMNS = 128

    /** Rows per profile. More rows buy robustness, not resolution. */
    const val PROFILE_ROWS = 8

    /** Widest horizontal shift searched, in profile samples. */
    const val MAX_SHIFT = 24

    /** Narrow enough that a lag there is still a lag, not a coincidence. */
    const val LAG_MIN_MS = -150

    const val LAG_STEP_MS = 5

    const val LAG_POINTS = 61

    /**
     * Reduces a Y plane to a sparse grid. `rowStride` and `pixelStride` are
     * the plane's own strides; the plane itself is never retained.
     */
    fun extractProfile(
        plane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        columns: Int = PROFILE_COLUMNS,
        rows: Int = PROFILE_ROWS,
    ): ByteArray {
        require(width > 0 && height > 0) { "Invalid profile source size" }
        require(rowStride >= width && pixelStride > 0) {
            "Invalid profile strides"
        }
        require(columns in 8..4_096 && rows >= 1) {
            "Invalid profile geometry"
        }
        val out = ByteArray(columns * rows)
        for (row in 0 until rows) {
            val sourceRow = if (rows == 1) {
                height / 2
            } else {
                row * (height - 1) / (rows - 1)
            }
            val sourceBase = sourceRow * rowStride
            val targetBase = row * columns
            for (column in 0 until columns) {
                val sourceColumn = if (columns == 1) {
                    width / 2
                } else {
                    column * (width - 1) / (columns - 1)
                }
                out[targetBase + column] =
                    plane[sourceBase + sourceColumn * pixelStride]
            }
        }
        return out
    }

    /**
     * Horizontal shift in samples, positive when the content moved right.
     * Summed across all rows before the minimum is taken, so a single noisy
     * row cannot decide the estimate.
     */
    fun shiftOf(
        previous: ByteArray,
        current: ByteArray,
        columns: Int,
        rows: Int,
        maxShift: Int = MAX_SHIFT,
    ): Double {
        val size = columns * rows
        if (size == 0 || previous.size < size || current.size < size) {
            return 0.0
        }
        val limit = maxShift.coerceIn(0, columns / 2)
        val width = 2 * limit + 1
        val sad = DoubleArray(width)
        for (shift in -limit..limit) {
            var total = 0.0
            var count = 0
            for (row in 0 until rows) {
                val base = row * columns
                for (x in 0 until columns) {
                    val source = x - shift
                    if (source < 0 || source >= columns) continue
                    val a = current[base + x].toInt() and 0xFF
                    val b = previous[base + source].toInt() and 0xFF
                    total += if (a > b) (a - b).toDouble() else (b - a).toDouble()
                    count++
                }
            }
            sad[shift + limit] =
                if (count > 0) total / count.toDouble() else Double.MAX_VALUE
        }
        var best = 0
        for (index in 1 until width) {
            if (sad[index] < sad[best]) best = index
        }
        if (best <= 0 || best >= width - 1) return (best - limit).toDouble()
        val left = sad[best - 1]
        val middle = sad[best]
        val right = sad[best + 1]
        if (left == Double.MAX_VALUE || right == Double.MAX_VALUE) {
            return (best - limit).toDouble()
        }
        val denominator = left - 2.0 * middle + right
        val refined = if (kotlin.math.abs(denominator) > 1e-9) {
            best + 0.5 * (left - right) / denominator
        } else {
            best.toDouble()
        }
        return refined - limit
    }

    /**
     * Correlates the image-derived rate against each gyroscope axis over the
     * whole lag window.
     *
     * `frameMidNs` are the midpoints of the frame intervals, already on the
     * gyroscope's clock; `frameIntervalNs` their lengths; `imageShift` the
     * estimated shift in samples over each interval.
     */
    fun correlate(
        frameMidNs: LongArray,
        frameIntervalNs: LongArray,
        imageShift: DoubleArray,
        gyroTimeNs: LongArray,
        gyroX: DoubleArray,
        gyroY: DoubleArray,
        gyroZ: DoubleArray,
        lagMinMs: Int = LAG_MIN_MS,
        lagStepMs: Int = LAG_STEP_MS,
        points: Int = LAG_POINTS,
    ): List<ArKernelCameraImuCurveRow> {
        val count = minOf(
            frameMidNs.size, frameIntervalNs.size, imageShift.size)
        if (count < 2) return emptyList()
        val rate = DoubleArray(count)
        for (index in 0 until count) {
            val intervalNs = frameIntervalNs[index]
            rate[index] = if (intervalNs > 0L) {
                imageShift[index] * 1_000_000_000.0 / intervalNs.toDouble()
            } else {
                0.0
            }
        }
        val axes = arrayOf(gyroX, gyroY, gyroZ)
        val rows = ArrayList<ArKernelCameraImuCurveRow>(points * 3)
        for (axisIndex in 0..2) {
            val prefix = prefixOf(axes[axisIndex])
            for (index in 0 until points) {
                val lagMs = lagMinMs + index * lagStepMs
                val lagNs = lagMs.toLong() * 1_000_000L
                val series = DoubleArray(count)
                for (frame in 0 until count) {
                    val half = frameIntervalNs[frame] / 2L
                    val centre = frameMidNs[frame] + lagNs
                    series[frame] = meanBetween(
                        gyroTimeNs, prefix,
                        centre - half, centre + half,
                        axes[axisIndex])
                }
                val r = pearson(rate, series)
                rows.add(
                    ArKernelCameraImuCurveRow(
                        axis = ArKernelCameraImuAxis.values()[axisIndex],
                        index = index,
                        lagMs = lagMs,
                        correlationPermille =
                            (r * 1_000.0).toLong().coerceIn(-1_000L, 1_000L)
                                .toInt(),
                    ))
            }
        }
        return rows
    }

    /** Pearson correlation; zero when either series is flat. */
    /**
     * Shift in both directions, in profile samples, as `[x, y]`.
     *
     * Each axis is measured on a one-dimensional profile - the mean over the
     * orthogonal axis - and not by sliding the whole two-dimensional frame.
     * That distinction is the whole point: sliding the frame along one axis
     * while it has also moved along the other compares pixels that never
     * corresponded, the difference curve comes back flat, and the argmin is
     * noise. Projecting first makes each axis measure the projection of the
     * motion onto that axis, which is the quantity the rotation model is
     * written in.
     *
     * Both axes are refined parabolically. Quantising to whole samples would
     * discard most of the signal: a gentle rotation moves the content a
     * fraction of a sample between frames.
     */
    fun shift2d(
        previous: ByteArray,
        current: ByteArray,
        columns: Int,
        rows: Int,
        maxShift: Int = MAX_SHIFT,
    ): DoubleArray {
        val size = columns * rows
        if (size == 0 || previous.size < size || current.size < size) {
            return doubleArrayOf(0.0, 0.0)
        }
        return doubleArrayOf(
            shiftAlong(previous, current, columns, rows, maxShift, true),
            shiftAlong(previous, current, columns, rows, maxShift, false))
    }

    private fun shiftAlong(
        previous: ByteArray,
        current: ByteArray,
        columns: Int,
        rows: Int,
        maxShift: Int,
        horizontal: Boolean,
    ): Double {
        // along = samples on the axis being measured, across = samples the
        // profile is averaged over.
        val along = if (horizontal) columns else rows
        val across = if (horizontal) rows else columns
        if (along < 4 || across < 1) return 0.0
        // A quarter of the axis, not a half. Searching half of it leaves too
        // little overlap to be trustworthy, and on the shorter axis it reaches
        // far enough to find an aliased minimum in a repeating scene.
        val limit = maxShift.coerceIn(0, along / 4)
        val profilePrevious = profile(previous, columns, rows, horizontal)
        val profileCurrent = profile(current, columns, rows, horizontal)
        val width = 2 * limit + 1
        val sad = DoubleArray(width)
        for (shift in -limit..limit) {
            var total = 0.0
            var count = 0
            for (inner in 0 until along) {
                val source = inner - shift
                if (source < 0 || source >= along) continue
                total += kotlin.math.abs(
                    profileCurrent[inner] - profilePrevious[source])
                count++
            }
            sad[shift + limit] =
                if (count > 0) total / count.toDouble() else Double.MAX_VALUE
        }
        return refine(sad, limit)
    }

    /** Mean over the orthogonal axis; one sample per position on `horizontal`. */
    private fun profile(
        image: ByteArray,
        columns: Int,
        rows: Int,
        horizontal: Boolean,
    ): DoubleArray {
        val along = if (horizontal) columns else rows
        val across = if (horizontal) rows else columns
        val out = DoubleArray(along)
        for (inner in 0 until along) {
            var total = 0.0
            for (outer in 0 until across) {
                val index = if (horizontal) {
                    outer * columns + inner
                } else {
                    inner * columns + outer
                }
                total += (image[index].toInt() and 0xFF).toDouble()
            }
            out[inner] = total / across.toDouble()
        }
        return out
    }

    /**
     * Sub-sample peak of the difference curve, by parabolic interpolation on
     * the three points around the minimum.
     *
     * A whole-sample estimate would be a poor fit for what is being measured:
     * a slow rotation moves the content a fraction of a sample per frame, so
     * rounding to integers throws away most of the motion and biases the
     * recovered focal length downwards.
     */
    private fun refine(sad: DoubleArray, limit: Int): Double {
        var best = 0
        for (index in 1 until sad.size) {
            if (sad[index] < sad[best]) best = index
        }
        // At either edge the parabola has no support; return the bound.
        if (best <= 0 || best >= sad.size - 1) return (best - limit).toDouble()
        val left = sad[best - 1]
        val middle = sad[best]
        val right = sad[best + 1]
        if (left == Double.MAX_VALUE || right == Double.MAX_VALUE) {
            return (best - limit).toDouble()
        }
        val denominator = left - 2.0 * middle + right
        val refined = if (kotlin.math.abs(denominator) > 1e-9) {
            best + 0.5 * (left - right) / denominator
        } else {
            best.toDouble()
        }
        return refined - limit
    }

    /**
     * Everything `ArKernelCameraImuRotation.recover` needs, on one clock.
     *
     * The gyroscope is averaged over exactly the frame intervals, because an
     * image shift is a mean rate over an interval and not an instantaneous
     * one; averaging the gyroscope over the same intervals puts both sides
     * through the same filter.
     */
    fun seriesForRotation(
        frameMidNs: LongArray,
        frameIntervalNs: LongArray,
        shiftX: DoubleArray,
        shiftY: DoubleArray,
        gyroTimeNs: LongArray,
        gyroX: DoubleArray,
        gyroY: DoubleArray,
        gyroZ: DoubleArray,
    ): ArKernelCameraImuRotationSeries {
        val count = minOf(
            frameMidNs.size, frameIntervalNs.size,
            shiftX.size, shiftY.size)
        val rateX = DoubleArray(count)
        val rateY = DoubleArray(count)
        val meanX = DoubleArray(count)
        val meanY = DoubleArray(count)
        val meanZ = DoubleArray(count)
        val prefixX = prefixOf(gyroX)
        val prefixY = prefixOf(gyroY)
        val prefixZ = prefixOf(gyroZ)
        for (index in 0 until count) {
            val intervalNs = frameIntervalNs[index]
            if (intervalNs > 0L) {
                rateX[index] = shiftX[index] * 1_000_000_000.0 /
                    intervalNs.toDouble()
                rateY[index] = shiftY[index] * 1_000_000_000.0 /
                    intervalNs.toDouble()
            }
            val half = intervalNs / 2L
            val centre = frameMidNs[index]
            meanX[index] = meanBetween(
                gyroTimeNs, prefixX, centre - half, centre + half, gyroX)
            meanY[index] = meanBetween(
                gyroTimeNs, prefixY, centre - half, centre + half, gyroY)
            meanZ[index] = meanBetween(
                gyroTimeNs, prefixZ, centre - half, centre + half, gyroZ)
        }
        return ArKernelCameraImuRotationSeries(
            rateX, rateY, meanX, meanY, meanZ)
    }

    fun pearson(left: DoubleArray, right: DoubleArray): Double {
        val count = minOf(left.size, right.size)
        if (count < 2) return 0.0
        var sumLeft = 0.0
        var sumRight = 0.0
        for (index in 0 until count) {
            sumLeft += left[index]
            sumRight += right[index]
        }
        val meanLeft = sumLeft / count
        val meanRight = sumRight / count
        var covariance = 0.0
        var varianceLeft = 0.0
        var varianceRight = 0.0
        for (index in 0 until count) {
            val a = left[index] - meanLeft
            val b = right[index] - meanRight
            covariance += a * b
            varianceLeft += a * a
            varianceRight += b * b
        }
        if (varianceLeft <= 0.0 || varianceRight <= 0.0) return 0.0
        val r = covariance /
            kotlin.math.sqrt(varianceLeft * varianceRight)
        return r.coerceIn(-1.0, 1.0)
    }

    fun rmsOf(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var total = 0.0
        for (value in values) total += value * value
        return kotlin.math.sqrt(total / values.size)
    }

    /** Peak-to-peak spread, which is what "was there enough motion" asks. */
    fun rangeOf(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var low = values[0]
        var high = values[0]
        for (value in values) {
            if (value < low) low = value
            if (value > high) high = value
        }
        return high - low
    }

    /** Median of a bounded sample, used for the reported frame interval. */
    fun medianOf(values: LongArray): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            // Averaged this way so two large values cannot overflow.
            sorted[middle - 1] / 2 + sorted[middle] / 2 +
                (sorted[middle - 1] and 1L) * (sorted[middle] and 1L)
        }
    }

    private fun prefixOf(values: DoubleArray): DoubleArray {
        val prefix = DoubleArray(values.size + 1)
        for (index in values.indices) {
            prefix[index + 1] = prefix[index] + values[index]
        }
        return prefix
    }

    private fun lowerBound(times: LongArray, value: Long): Int {
        var low = 0
        var high = times.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (times[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }

    /** Mean of the samples in [fromNs, toNs); the nearest one if none. */
    private fun meanBetween(
        times: LongArray,
        prefix: DoubleArray,
        fromNs: Long,
        toNs: Long,
        values: DoubleArray,
    ): Double {
        if (times.isEmpty()) return 0.0
        val first = lowerBound(times, fromNs)
        val last = lowerBound(times, toNs)
        if (last > first) {
            return (prefix[last] - prefix[first]) / (last - first).toDouble()
        }
        val nearest = if (first >= times.size) {
            times.size - 1
        } else {
            first
        }
        return values[nearest]
    }
}
