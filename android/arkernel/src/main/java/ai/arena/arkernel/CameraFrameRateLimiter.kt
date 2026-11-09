package ai.arena.arkernel

/** Timestamp-domain output limiter; it never rewrites camera timestamps. */
class CameraFrameRateLimiter(maximumFramesPerSecond: Int) {
    private val minimumIntervalNs: Long
    private var lastAcceptedTimestampNs = 0L

    init {
        require(maximumFramesPerSecond in 1..240)
        minimumIntervalNs = 1_000_000_000L / maximumFramesPerSecond
    }

    @Synchronized
    fun accept(timestampNs: Long): Boolean {
        require(timestampNs > 0)
        if (lastAcceptedTimestampNs > 0L &&
            timestampNs - lastAcceptedTimestampNs < minimumIntervalNs) return false
        lastAcceptedTimestampNs = timestampNs
        return true
    }

    @Synchronized fun reset() { lastAcceptedTimestampNs = 0L }
}
