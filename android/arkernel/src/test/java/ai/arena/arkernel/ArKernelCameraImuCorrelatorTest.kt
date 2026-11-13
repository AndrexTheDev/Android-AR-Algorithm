package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The correlator is the only part of this app that estimates a physical
 * quantity nobody has told it, so it is the part that most needs a test with a
 * known answer. Each synthetic run below has a time offset injected on
 * purpose; the test asserts that the estimator hands it back.
 */
class ArKernelCameraImuCorrelatorTest {

    companion object {
        private const val FRAMES = 240
        private const val DT_NS = 33_333_333L
        private const val GYRO_STEP_NS = 5_000_000L

        /** Samples per degree of pan; arbitrary, and deliberately so. */
        private const val SCALE = 3.0

        private fun omega(tSeconds: Double): Double =
            60.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 0.7 * tSeconds) +
                40.0 * kotlin.math.sin(
                    2.0 * kotlin.math.PI * 1.9 * tSeconds + 1.0) +
                30.0 * kotlin.math.sin(
                    2.0 * kotlin.math.PI * 3.3 * tSeconds + 2.0)

        /** Analytic integral of [omega], so the image shift is exact. */
        private fun angle(tSeconds: Double): Double {
            val a = -60.0 / (2.0 * kotlin.math.PI * 0.7) *
                kotlin.math.cos(2.0 * kotlin.math.PI * 0.7 * tSeconds)
            val b = -40.0 / (2.0 * kotlin.math.PI * 1.9) *
                kotlin.math.cos(
                    2.0 * kotlin.math.PI * 1.9 * tSeconds + 1.0)
            val c = -30.0 / (2.0 * kotlin.math.PI * 3.3) *
                kotlin.math.cos(
                    2.0 * kotlin.math.PI * 3.3 * tSeconds + 2.0)
            return a + b + c
        }

        /** A pan whose frame timestamps are late by [offsetMs]. */
        private class SyntheticRun(
            offsetMs: Long,
            axisIndex: Int = 1,
            sign: Double = 1.0,
        ) {
            val midNs = LongArray(FRAMES - 1)
            val intervalNs = LongArray(FRAMES - 1)
            val shift = DoubleArray(FRAMES - 1)
            val gyroTimeNs: LongArray
            val gyroX: DoubleArray
            val gyroY: DoubleArray
            val gyroZ: DoubleArray

            init {
                for (index in midNs.indices) {
                    val start = index.toLong() * DT_NS
                    midNs[index] = start + DT_NS / 2L
                    intervalNs[index] = DT_NS
                    // The content of a frame stamped at t was captured at
                    // t - offset, so the interval it really covers is shifted.
                    val trueHigh = (start + DT_NS).toDouble() / 1e9 -
                        offsetMs.toDouble() / 1e3
                    val trueLow = start.toDouble() / 1e9 -
                        offsetMs.toDouble() / 1e3
                    shift[index] = SCALE * (angle(trueHigh) - angle(trueLow))
                }
                val span = FRAMES.toLong() * DT_NS
                val count = (span / GYRO_STEP_NS).toInt() + 2
                gyroTimeNs = LongArray(count)
                gyroX = DoubleArray(count)
                gyroY = DoubleArray(count)
                gyroZ = DoubleArray(count)
                for (index in 0 until count) {
                    val t = index.toLong() * GYRO_STEP_NS
                    gyroTimeNs[index] = t
                    val seconds = t.toDouble() / 1e9
                    val main = sign * omega(seconds)
                    val other = 2.0 * kotlin.math.sin(
                        2.0 * kotlin.math.PI * 5.0 * seconds)
                    gyroX[index] = if (axisIndex == 0) main else other
                    gyroY[index] = if (axisIndex == 1) main else other
                    gyroZ[index] = if (axisIndex == 2) main else
                        other + 1.0
                }
            }

            fun curve(): List<ArKernelCameraImuCurveRow> =
                ArKernelCameraImuCorrelator.correlate(
                    frameMidNs = midNs,
                    frameIntervalNs = intervalNs,
                    imageShift = shift,
                    gyroTimeNs = gyroTimeNs,
                    gyroX = gyroX,
                    gyroY = gyroY,
                    gyroZ = gyroZ)
        }

        private fun record(
            rows: List<ArKernelCameraImuCurveRow>,
            imageRateRms: Double = 40.0,
            motionRangeDps: Double = 240.0,
        ): ArKernelCameraImuAlignment = ArKernelCameraImuAlignment(
            runId = "camimu.synthetic",
            deviceToken = "phone.test",
            cameraId = "0",
            cameraLevel = ArKernelCameraLevel.FULL,
            cameraFacing = ArKernelCameraFacing.BACK,
            sensorOrientationDeg = 90,
            profileWidth = ArKernelCameraImuCorrelator.PROFILE_COLUMNS,
            profileHeight = ArKernelCameraImuCorrelator.PROFILE_ROWS,
            frameCount = FRAMES,
            gyroSampleCount = 1_600,
            durationMs = 8_000L,
            frameIntervalMedianUs = DT_NS / 1_000L,
            gyroRateMedianHz = 200L,
            imageRateRms = imageRateRms,
            gyroRateRms = 48.0,
            motionRangeDps = motionRangeDps,
            timestampDomain = CameraTimestampDomain.REALTIME,
            domainOffsetMs = 0L,
            lagMinMs = ArKernelCameraImuCorrelator.LAG_MIN_MS,
            lagStepMs = ArKernelCameraImuCorrelator.LAG_STEP_MS,
            curve = rows,
        )
    }

    @Test
    fun aProfileIsSampledFromTheRightPlaces() {
        val width = 32
        val height = 16
        val plane = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                plane[row * width + column] = (row * 16 + column).toByte()
            }
        }
        val profile = ArKernelCameraImuCorrelator.extractProfile(
            plane = plane, width = width, height = height,
            rowStride = width, pixelStride = 1, columns = 8, rows = 4)
        assertEquals(32, profile.size)
        assertEquals(0, (profile[0].toInt() and 0xFF))
        assertEquals(31, (profile[7].toInt() and 0xFF))
        assertEquals(240, (profile[3 * 8].toInt() and 0xFF))
    }

    @Test
    fun aKnownShiftIsRecovered() {
        val columns = 64
        val previous = ByteArray(columns)
        for (x in 0 until columns) {
            previous[x] = (128.0 +
                100.0 * kotlin.math.sin(2.0 * kotlin.math.PI * x / 21.0))
                .toLong().toByte()
        }
        val current = ByteArray(columns)
        for (x in 0 until columns) {
            current[x] = previous[maxOf(0, x - 3)]
        }
        val shift = ArKernelCameraImuCorrelator.shiftOf(
            previous, current, columns, 1)
        assertEquals(3.0, shift, 0.2)
    }

    @Test
    fun aFractionalShiftIsNotRoundedToWholeSamples() {
        val columns = 64
        val previous = ByteArray(columns)
        for (x in 0 until columns) {
            previous[x] = (128.0 +
                100.0 * kotlin.math.sin(2.0 * kotlin.math.PI * x / 21.0))
                .toLong().toByte()
        }
        val current = ByteArray(columns)
        for (x in 0 until columns) {
            val a = previous[maxOf(0, x - 2)].toInt() and 0xFF
            val b = previous[maxOf(0, x - 3)].toInt() and 0xFF
            current[x] = (0.5 * a + 0.5 * b).toLong().toByte()
        }
        val shift = ArKernelCameraImuCorrelator.shiftOf(
            previous, current, columns, 1)
        assertEquals(2.5, shift, 0.5)
    }

    @Test
    fun correlationBehaves() {
        val a = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, ArKernelCameraImuCorrelator.pearson(a, a), 1e-9)
        val b = doubleArrayOf(5.0, 4.0, 3.0, 2.0, 1.0)
        assertEquals(-1.0, ArKernelCameraImuCorrelator.pearson(a, b), 1e-9)
        val flat = doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0)
        assertEquals(0.0, ArKernelCameraImuCorrelator.pearson(a, flat), 1e-9)
    }

    @Test
    fun aTwentyMillisecondOffsetIsFound() {
        val run = SyntheticRun(offsetMs = 20L)
        val built = record(run.curve())
        assertEquals(-20, built.bestLagMs)
        assertEquals(20_000L, built.estimatedOffsetUs)
        assertEquals(ArKernelCameraImuAxis.Y, built.bestAxis)
        assertEquals(1, built.bestAxisSign)
        assertTrue("correlation ${built.bestCorrelationPermille}",
            built.bestCorrelationPermille > 950)
        assertTrue(built.alignmentOk)
    }

    @Test
    fun aZeroOffsetIsFound() {
        val run = SyntheticRun(offsetMs = 0L)
        val built = record(run.curve())
        assertEquals(0, built.bestLagMs)
        assertEquals(0L, built.estimatedOffsetUs)
        assertTrue(built.alignmentOk)
    }

    @Test
    fun anEarlyTimestampGivesTheOppositeSign() {
        val run = SyntheticRun(offsetMs = -35L)
        val built = record(run.curve())
        assertEquals(35, built.bestLagMs)
        assertEquals(-35_000L, built.estimatedOffsetUs)
        assertTrue(built.alignmentOk)
    }

    @Test
    fun theAxisAndItsSignFollowTheMotion() {
        val onZ = record(SyntheticRun(offsetMs = 15L, axisIndex = 2).curve())
        assertEquals(ArKernelCameraImuAxis.Z, onZ.bestAxis)
        val inverted = record(
            SyntheticRun(offsetMs = 15L, axisIndex = 0, sign = -1.0).curve())
        assertEquals(ArKernelCameraImuAxis.X, inverted.bestAxis)
        assertEquals(-1, inverted.bestAxisSign)
        assertTrue(inverted.correlationOk)
    }

    @Test
    fun aStillRunProducesNoPeak() {
        val run = SyntheticRun(offsetMs = 20L)
        val flatShift = DoubleArray(FRAMES - 1)
        val curve = ArKernelCameraImuCorrelator.correlate(
            frameMidNs = run.midNs,
            frameIntervalNs = run.intervalNs,
            imageShift = flatShift,
            gyroTimeNs = run.gyroTimeNs,
            gyroX = run.gyroX,
            gyroY = run.gyroY,
            gyroZ = run.gyroZ)
        val built = record(
            rows = curve, imageRateRms = 0.0, motionRangeDps = 0.0)
        assertEquals(0, built.bestCorrelationPermille)
        assertFalse(built.correlationOk)
        assertFalse(built.motionOk)
        // With nothing to correlate the peak is degenerate and sits on the
        // edge of the window, so the record names both failures rather than
        // pretending to have found a lag.
        assertEquals(listOf("motion", "scene", "range"),
            built.outstandingSteps())
        assertFalse(built.alignmentOk)
    }

    @Test
    fun theMedianOfAnEvenCountDoesNotOverflow() {
        assertEquals(6L, ArKernelCameraImuCorrelator.medianOf(
            longArrayOf(10L, 2L, 8L, 4L)))
        val huge = Long.MAX_VALUE - 1L
        assertEquals(huge, ArKernelCameraImuCorrelator.medianOf(
            longArrayOf(huge, huge)))
        assertEquals(huge, ArKernelCameraImuCorrelator.medianOf(
            longArrayOf(huge, huge, huge, huge)))
    }
}
