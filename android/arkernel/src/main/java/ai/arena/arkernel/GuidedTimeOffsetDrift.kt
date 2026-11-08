package ai.arena.arkernel

import kotlin.math.abs
import kotlin.math.sqrt

private const val MAX_DRIFT_WINDOWS = 32

enum class TimeOffsetDriftReason {
    INSUFFICIENT_ACCEPTED_WINDOWS,
    INSUFFICIENT_TIME_SPAN,
    OFFSET_RESIDUAL_TOO_HIGH,
    DRIFT_UNCERTAINTY_TOO_HIGH,
    ABSOLUTE_DRIFT_TOO_HIGH,
    WINDOW_BUDGET_EXCEEDED,
}

data class TimeOffsetDriftConfig(
    val minimumAcceptedWindows: Int = 4,
    val minimumSpanNs: Long = 60_000_000_000L,
    val maximumWindowUncertaintyNs: Long = 10_000_000L,
    val maximumResidualRmseNs: Double = 5_000_000.0,
    val maximumDriftUncertaintyPpm: Double = 500.0,
    val maximumAbsoluteDriftPpm: Double = 1_000.0,
) {
    init {
        require(minimumAcceptedWindows in 3..MAX_DRIFT_WINDOWS)
        require(minimumSpanNs in 1_000_000_000L..3_600_000_000_000L)
        require(maximumWindowUncertaintyNs in 100_000L..100_000_000L)
        require(maximumResidualRmseNs in 100_000.0..100_000_000.0)
        require(maximumDriftUncertaintyPpm in 1.0..10_000.0)
        require(maximumAbsoluteDriftPpm in 1.0..100_000.0)
    }
}

data class TimeOffsetDriftReport(
    val acceptedWindows: Int,
    val rejectedWindows: Int,
    val observedSpanNs: Long,
    val driftPpm: Double?,
    val driftUncertaintyPpm: Double?,
    val residualRmseNs: Double?,
    val reasons: List<TimeOffsetDriftReason>,
) {
    init {
        require(acceptedWindows in 0..MAX_DRIFT_WINDOWS)
        require(rejectedWindows in 0..MAX_DRIFT_WINDOWS)
        require(acceptedWindows + rejectedWindows <= MAX_DRIFT_WINDOWS)
        require(observedSpanNs >= 0)
        require((driftPpm == null) == (driftUncertaintyPpm == null))
        require((driftPpm == null) == (residualRmseNs == null))
        require(driftPpm == null || driftPpm.isFinite())
        require(driftUncertaintyPpm == null ||
            (driftUncertaintyPpm.isFinite() && driftUncertaintyPpm >= 0.0))
        require(residualRmseNs == null ||
            (residualRmseNs.isFinite() && residualRmseNs >= 0.0))
        require(reasons.distinct().size == reasons.size)
    }

    /** A result may be measurable but outside policy; usable means every gate passed. */
    val usable: Boolean get() = reasons.isEmpty()
}

/**
 * Robustly estimates linear camera/gyro offset drift from independent accepted guided windows.
 * Pairwise slopes and a median intercept limit one bad-but-accepted window's influence. Reported
 * uncertainty combines slope dispersion with each endpoint window's own timing uncertainty.
 */
class GuidedTimeOffsetDriftProbe(
    private val config: TimeOffsetDriftConfig = TimeOffsetDriftConfig(),
) {
    private data class Window(val midpointTimestampNs: Long, val offsetNs: Long,
        val uncertaintyNs: Long)
    private val windows = ArrayList<Window>()
    private var attemptedWindows = 0
    private var rejectedWindows = 0
    private var budgetExceeded = false
    @Volatile private var completed: TimeOffsetDriftReport? = null

    val isFinished: Boolean get() = completed != null

    /** Adds only a terminal, observable offset report at the host-recorded window midpoint. */
    @Synchronized
    fun addWindow(midpointTimestampNs: Long, report: TimeOffsetObservabilityReport): Boolean {
        if (completed != null || midpointTimestampNs <= 0) return false
        if (attemptedWindows == MAX_DRIFT_WINDOWS) {
            budgetExceeded = true
            return false
        }
        attemptedWindows++
        val offset = report.visualToGyroscopeOffsetNs
        val uncertainty = report.offsetUncertaintyNs
        if (!report.observable || offset == null || uncertainty == null) {
            rejectedWindows++
            return false
        }
        if (uncertainty > config.maximumWindowUncertaintyNs) {
            rejectedWindows++
            return false
        }
        if (windows.isNotEmpty() && midpointTimestampNs <= windows.last().midpointTimestampNs) {
            rejectedWindows++
            return false
        }
        windows += Window(midpointTimestampNs, offset, uncertainty)
        return true
    }

    @Synchronized
    fun finish(): TimeOffsetDriftReport {
        completed?.let { return it }
        val reasons = mutableListOf<TimeOffsetDriftReason>()
        if (windows.size < config.minimumAcceptedWindows)
            reasons += TimeOffsetDriftReason.INSUFFICIENT_ACCEPTED_WINDOWS
        val span = if (windows.size < 2) 0L else
            windows.last().midpointTimestampNs - windows.first().midpointTimestampNs
        if (span < config.minimumSpanNs)
            reasons += TimeOffsetDriftReason.INSUFFICIENT_TIME_SPAN
        if (budgetExceeded) reasons += TimeOffsetDriftReason.WINDOW_BUDGET_EXCEEDED

        var driftPpm: Double? = null
        var uncertaintyPpm: Double? = null
        var residualRmseNs: Double? = null
        if (windows.size >= 2 && span > 0) {
            val slopes = ArrayList<Double>()
            for (first in 0 until windows.lastIndex) {
                for (second in first + 1 until windows.size) {
                    val durationNs = windows[second].midpointTimestampNs -
                        windows[first].midpointTimestampNs
                    slopes += (windows[second].offsetNs - windows[first].offsetNs).toDouble() /
                        durationNs.toDouble() * 1_000_000.0
                }
            }
            val fittedDriftPpm = medianDouble(slopes)
            driftPpm = fittedDriftPpm
            val origin = windows.first().midpointTimestampNs
            val intercepts = windows.map { window ->
                window.offsetNs - fittedDriftPpm *
                    (window.midpointTimestampNs - origin).toDouble() / 1_000_000.0
            }
            val intercept = medianDouble(intercepts)
            val residuals = windows.map { window ->
                window.offsetNs - (intercept + fittedDriftPpm *
                    (window.midpointTimestampNs - origin).toDouble() / 1_000_000.0)
            }
            val fittedResidualRmseNs = sqrt(residuals.sumOf { it * it } / residuals.size)
            residualRmseNs = fittedResidualRmseNs
            val slopeMad = medianDouble(slopes.map { abs(it - fittedDriftPpm) }) * 1.4826
            val endpointBound = (windows.first().uncertaintyNs +
                windows.last().uncertaintyNs).toDouble() / span.toDouble() * 1_000_000.0
            // A grid-width bound is closer to a uniform interval than Gaussian sigma. Keep the
            // larger conservative value rather than claiming precision from window count alone.
            val fittedUncertaintyPpm = maxOf(slopeMad, endpointBound)
            uncertaintyPpm = fittedUncertaintyPpm
            if (fittedResidualRmseNs > config.maximumResidualRmseNs)
                reasons += TimeOffsetDriftReason.OFFSET_RESIDUAL_TOO_HIGH
            if (fittedUncertaintyPpm > config.maximumDriftUncertaintyPpm)
                reasons += TimeOffsetDriftReason.DRIFT_UNCERTAINTY_TOO_HIGH
            if (abs(fittedDriftPpm) > config.maximumAbsoluteDriftPpm)
                reasons += TimeOffsetDriftReason.ABSOLUTE_DRIFT_TOO_HIGH
        }
        return TimeOffsetDriftReport(windows.size, rejectedWindows, span,
            driftPpm, uncertaintyPpm, residualRmseNs, reasons.distinct()).also { completed = it }
    }
}

private fun medianDouble(values: List<Double>): Double {
    require(values.isNotEmpty() && values.all { it.isFinite() })
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle]
    else (sorted[middle - 1] + sorted[middle]) / 2.0
}
