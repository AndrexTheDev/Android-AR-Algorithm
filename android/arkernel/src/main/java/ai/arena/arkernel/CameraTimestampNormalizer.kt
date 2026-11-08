package ai.arena.arkernel

import kotlin.math.abs

enum class CameraTimestampDomain { REALTIME, UPTIME, EQUIVALENT }
enum class CameraTimestampFailureReason {
    AMBIGUOUS_DOMAIN,
    ELAPSED_REALTIME_PRECEDES_UPTIME,
    SESSION_OFFSET_CHANGED,
    REALTIME_RESIDUAL_CHANGED,
    EQUIVALENT_CLOCKS_DIVERGED,
    AFFINE_DRIFT_EXCEEDED,
    AFFINE_RESIDUAL_EXCEEDED,
}

data class CameraTimestampNormalization(
    val rawTimestampNs: Long,
    val normalizedTimestampNs: Long,
    val domain: CameraTimestampDomain,
    val appliedOffsetNs: Long,
)

/** Determines one camera clock domain and then applies one fixed session offset. */
class CameraTimestampNormalizer(
    private val minimumObservations: Int = 8,
    private val decisionMarginNs: Long = 5_000_000L,
    private val equivalentClockDeltaNs: Long = 20_000_000L,
    private val maximumSessionOffsetChangeNs: Long = 50_000_000L,
    private val maximumRealtimeResidualChangeNs: Long = 250_000_000L,
) {
    private val elapsedResiduals = ArrayList<Long>()
    private val uptimeResiduals = ArrayList<Long>()
    private val elapsedMinusUptime = ArrayList<Long>()
    private var lockedDomain: CameraTimestampDomain? = null
    private var lockedOffsetNs = 0L
    private var lockedRealtimeResidualNs = 0L
    private var failure: CameraTimestampFailureReason? = null

    init {
        require(minimumObservations in 3..64)
        require(decisionMarginNs in 0..1_000_000_000L)
        require(equivalentClockDeltaNs in 0..1_000_000_000L)
        require(maximumSessionOffsetChangeNs in 1_000_000L..5_000_000_000L)
        require(maximumRealtimeResidualChangeNs in 1_000_000L..5_000_000_000L)
    }

    val isReady: Boolean get() = lockedDomain != null && failure == null
    val isFailed: Boolean get() = failure != null
    val failureReason: CameraTimestampFailureReason? get() = failure
    val domain: CameraTimestampDomain? get() = lockedDomain
    val offsetNs: Long get() = lockedOffsetNs

    /** Observe image timing. Returns null until the session clock decision is locked. */
    @Synchronized
    fun observeAndNormalize(rawTimestampNs: Long, callbackElapsedRealtimeNs: Long,
        callbackUptimeNs: Long): CameraTimestampNormalization? {
        require(rawTimestampNs > 0 && callbackElapsedRealtimeNs > 0 && callbackUptimeNs > 0)
        if (failure != null) return null
        if (lockedDomain == null) {
            if (elapsedResiduals.size < minimumObservations) {
                elapsedResiduals += absDifference(callbackElapsedRealtimeNs, rawTimestampNs)
                uptimeResiduals += absDifference(callbackUptimeNs, rawTimestampNs)
                elapsedMinusUptime += callbackElapsedRealtimeNs - callbackUptimeNs
            }
            if (elapsedResiduals.size == minimumObservations) lockDecision()
        } else {
            monitorLockedSession(rawTimestampNs, callbackElapsedRealtimeNs,
                callbackUptimeNs)
        }
        return normalize(rawTimestampNs)
    }

    /** Apply the already locked decision to a matching CaptureResult timestamp. */
    @Synchronized
    fun normalize(rawTimestampNs: Long): CameraTimestampNormalization? {
        require(rawTimestampNs > 0)
        if (failure != null) return null
        val currentDomain = lockedDomain ?: return null
        val normalized = Math.addExact(rawTimestampNs, lockedOffsetNs)
        return CameraTimestampNormalization(rawTimestampNs, normalized, currentDomain,
            lockedOffsetNs)
    }

    @Synchronized
    fun reset() {
        elapsedResiduals.clear()
        uptimeResiduals.clear()
        elapsedMinusUptime.clear()
        lockedDomain = null
        lockedOffsetNs = 0L
        lockedRealtimeResidualNs = 0L
        failure = null
    }

    private fun lockDecision() {
        val elapsedResidual = median(elapsedResiduals)
        val uptimeResidual = median(uptimeResiduals)
        val clockDelta = median(elapsedMinusUptime)
        if (clockDelta < 0L) {
            failure = CameraTimestampFailureReason.ELAPSED_REALTIME_PRECEDES_UPTIME
            return
        }
        lockedRealtimeResidualNs = elapsedResidual
        when {
            abs(clockDelta) <= equivalentClockDeltaNs -> {
                lockedDomain = CameraTimestampDomain.EQUIVALENT
                lockedOffsetNs = 0L
            }
            elapsedResidual < uptimeResidual &&
                uptimeResidual - elapsedResidual > decisionMarginNs -> {
                lockedDomain = CameraTimestampDomain.REALTIME
                lockedOffsetNs = 0L
            }
            uptimeResidual < elapsedResidual &&
                elapsedResidual - uptimeResidual > decisionMarginNs -> {
                lockedDomain = CameraTimestampDomain.UPTIME
                lockedOffsetNs = clockDelta
            }
            else -> failure = CameraTimestampFailureReason.AMBIGUOUS_DOMAIN
        }
    }

    private fun monitorLockedSession(rawTimestampNs: Long, callbackElapsedRealtimeNs: Long,
        callbackUptimeNs: Long) {
        val currentDomain = lockedDomain ?: return
        val currentOffset = callbackElapsedRealtimeNs - callbackUptimeNs
        if (currentOffset < 0L) {
            failure = CameraTimestampFailureReason.ELAPSED_REALTIME_PRECEDES_UPTIME
            return
        }
        when (currentDomain) {
            CameraTimestampDomain.UPTIME -> {
                if (absDifference(currentOffset, lockedOffsetNs) > maximumSessionOffsetChangeNs)
                    failure = CameraTimestampFailureReason.SESSION_OFFSET_CHANGED
            }
            CameraTimestampDomain.REALTIME -> {
                val residual = absDifference(callbackElapsedRealtimeNs, rawTimestampNs)
                if (absDifference(residual, lockedRealtimeResidualNs) >
                    maximumRealtimeResidualChangeNs)
                    failure = CameraTimestampFailureReason.REALTIME_RESIDUAL_CHANGED
            }
            CameraTimestampDomain.EQUIVALENT -> {
                if (currentOffset > equivalentClockDeltaNs + maximumSessionOffsetChangeNs)
                    failure = CameraTimestampFailureReason.EQUIVALENT_CLOCKS_DIVERGED
            }
        }
    }
}

private fun median(values: List<Long>): Long {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    if (sorted.size % 2 == 1) return sorted[middle]
    val lower = sorted[middle - 1]
    val upper = sorted[middle]
    return lower / 2 + upper / 2 + (lower and 1L) * (upper and 1L)
}

private fun absDifference(a: Long, b: Long): Long = if (a >= b) a - b else b - a
