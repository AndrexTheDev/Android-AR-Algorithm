package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Test

class ArKernelCameraImuShift2dTest {

    companion object {

        /**
         * Smoothed noise, not a sinusoid.
         *
         * A sinusoidal test pattern has a periodic difference curve with
         * several equally good minima, so an estimator can land on any of them
         * and the test proves nothing about behaviour on a real scene.
         * Smoothing makes neighbouring samples correlate the way real textures
         * do, instead of being pure static.
         */
        private fun pattern(columns: Int, rows: Int): ByteArray {
            val out = ByteArray(columns * rows)
            var seed = 987654321L
            fun next(): Double {
                seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
                return seed / 0x7FFFFFFFL.toDouble()
            }
            val raw = DoubleArray(columns * rows) { next() }
            val smooth = DoubleArray(columns * rows)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    var total = 0.0
                    var count = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val y = row + dy
                            val x = column + dx
                            if (y < 0 || y >= rows || x < 0 || x >= columns) {
                                continue
                            }
                            total += raw[y * columns + x]
                            count++
                        }
                    }
                    smooth[row * columns + column] = total / count
                }
            }
            for (index in smooth.indices) {
                out[index] = (40.0 + 180.0 * smooth[index]).toLong().toByte()
            }
            return out
        }

        private fun shifted(
            source: ByteArray,
            columns: Int,
            rows: Int,
            dx: Int,
            dy: Int,
        ): ByteArray {
            val out = ByteArray(columns * rows)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val fromRow = (row - dy).coerceIn(0, rows - 1)
                    val fromColumn = (column - dx).coerceIn(0, columns - 1)
                    out[row * columns + column] =
                        source[fromRow * columns + fromColumn]
                }
            }
            return out
        }
    }

    @Test
    fun aKnownShiftInBothDirectionsIsRecovered() {
        val columns = 64
        val rows = 32
        val previous = pattern(columns, rows)
        val current = shifted(previous, columns, rows, 5, -3)
        val shift = ArKernelCameraImuCorrelator.shift2d(
            previous, current, columns, rows)
        assertEquals(5.0, shift[0], 0.6)
        assertEquals(-3.0, shift[1], 0.6)
    }

    @Test
    fun aPureHorizontalShiftLeavesVerticalNearZero() {
        val columns = 64
        val rows = 32
        val previous = pattern(columns, rows)
        val current = shifted(previous, columns, rows, 6, 0)
        val shift = ArKernelCameraImuCorrelator.shift2d(
            previous, current, columns, rows)
        assertEquals(6.0, shift[0], 0.6)
        assertEquals(0.0, shift[1], 0.6)
    }

    @Test
    fun aPureVerticalShiftLeavesHorizontalNearZero() {
        val columns = 64
        val rows = 32
        val previous = pattern(columns, rows)
        val current = shifted(previous, columns, rows, 0, 4)
        val shift = ArKernelCameraImuCorrelator.shift2d(
            previous, current, columns, rows)
        assertEquals(0.0, shift[0], 0.6)
        assertEquals(4.0, shift[1], 0.6)
    }

    @Test
    fun anIdenticalFrameShowsNoMotion() {
        val columns = 64
        val rows = 32
        val previous = pattern(columns, rows)
        val shift = ArKernelCameraImuCorrelator.shift2d(
            previous, previous, columns, rows)
        assertEquals(0.0, shift[0], 1e-6)
        assertEquals(0.0, shift[1], 1e-6)
    }

    @Test
    fun theSeriesAveragesTheGyroscopeOverTheFrameInterval() {
        // Three frames one second apart; the gyroscope runs ten times faster,
        // so each interval covers ten samples whose mean is what should come
        // back, not the single sample at the midpoint.
        val mid = longArrayOf(500_000_000L, 1_500_000_000L, 2_500_000_000L)
        val interval = longArrayOf(1_000_000_000L, 1_000_000_000L,
            1_000_000_000L)
        val time = LongArray(30) { it * 100_000_000L }
        val gyroX = DoubleArray(30) { (it % 10).toDouble() }
        val series = ArKernelCameraImuCorrelator.seriesForRotation(
            frameMidNs = mid,
            frameIntervalNs = interval,
            shiftX = doubleArrayOf(1.0, 2.0, 3.0),
            shiftY = doubleArrayOf(0.0, 0.0, 0.0),
            gyroTimeNs = time,
            gyroX = gyroX,
            gyroY = DoubleArray(30),
            gyroZ = DoubleArray(30))
        // Each interval covers samples 0..9, 10..19, 20..29 -> means 4.5 each.
        for (index in 0..2) {
            assertEquals(4.5, series.gyroX[index], 1e-6)
        }
        // One sample per second means the rate is the shift itself.
        assertEquals(1.0, series.rateX[0], 1e-6)
        assertEquals(3.0, series.rateX[2], 1e-6)
    }
}
