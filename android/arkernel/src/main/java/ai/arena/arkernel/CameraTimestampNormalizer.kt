package ai.arena.arkernel

import kotlin.math.abs

enum class CameraTimestampDomain { REALTIME, UPTIME, EQUIVALENT }

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
) {
    private val elapsedResiduals = ArrayList<Long>()
    private val uptimeResiduals = ArrayList<Long>()
    private val elapsedMinusUptime = ArrayList<Long>()
    private var lockedDomain: CameraTimestampDomain? = null
    private var lockedOffsetNs = 0L
    private var failed = false

    init {
        require(minimumObservations in 3..64)
        require(decisionMarginNs in 0..1_000_000_000L)
        require(equivalentClockDeltaNs in 0..1_000_000_000L)
    }

    val isReady: Boolean get() = lockedDomain != null
    val isFailed: Boolean get() = failed
    val domain: CameraTimestampDomain? get() = lockedDomain
    val offsetNs: Long get() = lockedOffsetNs

    /** Observe image timing. Returns null until the session clock decision is locked. */
    @Synchronized
    fun observeAndNormalize(rawTimestampNs: Long, callbackElapsedRealtimeNs: Long,
        callbackUptimeNs: Long): CameraTimestampNormalization? {
        require(rawTimestampNs > 0 && callbackElapsedRealtimeNs > 0 && callbackUptimeNs > 0)
        if (failed) return null
        if (lockedDomain == null) {
            if (elapsedResiduals.size < minimumObservations) {
                elapsedResiduals += absDifference(callbackElapsedRealtimeNs, rawTimestampNs)
                uptimeResiduals += absDifference(callbackUptimeNs, rawTimestampNs)
                elapsedMinusUptime += callbackElapsedRealtimeNs - callbackUptimeNs
            }
            if (elapsedResiduals.size == minimumObservations) lockDecision()
        }
        return normalize(rawTimestampNs)
    }

    /** Apply the already locked decision to a matching CaptureResult timestamp. */
    @Synchronized
    fun normalize(rawTimestampNs: Long): CameraTimestampNormalization? {
        require(rawTimestampNs > 0)
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
        failed = false
    }

    private fun lockDecision() {
        val elapsedResidual = median(elapsedResiduals)
        val uptimeResidual = median(uptimeResiduals)
        val clockDelta = median(elapsedMinusUptime)
        if (clockDelta < 0L) {
            failed = true
            return
        }
        when {
            abs(clockDelta) <= equivalentClockDeltaNs -> {
                lockedDomain = CameraTimestampDomain.EQUIVALENT
                lockedOffsetNs = 0L
            }
            elapsedResidual + decisionMarginNs < uptimeResidual -> {
                lockedDomain = CameraTimestampDomain.REALTIME
                lockedOffsetNs = 0L
            }
            uptimeResidual + decisionMarginNs < elapsedResidual -> {
                lockedDomain = CameraTimestampDomain.UPTIME
                lockedOffsetNs = clockDelta
            }
            else -> failed = true
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
