package ai.arena.arkernel

private data class TimedVector3(val timestampNs: Long, val value: Vector3)

data class SynchronizedImuSample(
    val timestampNs: Long,
    val acceleration: Vector3,
    val angularVelocity: Vector3,
)

/**
 * Synchronizes independent accelerometer and gyroscope callbacks without extrapolation.
 * At most two samples per stream are retained. Output timestamps are strictly increasing.
 */
class ImuSampleSynchronizer(
    private val maximumInterpolationGapNs: Long = 100_000_000L,
) {
    private var previousAcceleration: TimedVector3? = null
    private var latestAcceleration: TimedVector3? = null
    private var previousGyroscope: TimedVector3? = null
    private var latestGyroscope: TimedVector3? = null
    private var lastOutputTimestampNs = 0L

    init { require(maximumInterpolationGapNs in 1_000_000L..1_000_000_000L) }

    @Synchronized
    fun add(kind: ImuTimingSampleKind, timestampNs: Long,
        value: Vector3): SynchronizedImuSample? {
        require(timestampNs > 0)
        require(listOf(value.x, value.y, value.z).all { it.isFinite() })
        when (kind) {
            ImuTimingSampleKind.ACCELEROMETER -> {
                val latest = latestAcceleration
                if (latest != null && timestampNs <= latest.timestampNs) return null
                previousAcceleration = latest
                latestAcceleration = TimedVector3(timestampNs, value)
            }
            ImuTimingSampleKind.GYROSCOPE -> {
                val latest = latestGyroscope
                if (latest != null && timestampNs <= latest.timestampNs) return null
                previousGyroscope = latest
                latestGyroscope = TimedVector3(timestampNs, value)
            }
        }
        val accelerationEnd = latestAcceleration ?: return null
        val gyroscopeEnd = latestGyroscope ?: return null
        val target = minOf(accelerationEnd.timestampNs, gyroscopeEnd.timestampNs)
        if (target <= lastOutputTimestampNs) return null
        val acceleration = interpolate(previousAcceleration, accelerationEnd, target) ?: return null
        val gyroscope = interpolate(previousGyroscope, gyroscopeEnd, target) ?: return null
        lastOutputTimestampNs = target
        return SynchronizedImuSample(target, acceleration, gyroscope)
    }

    @Synchronized
    fun reset() {
        previousAcceleration = null
        latestAcceleration = null
        previousGyroscope = null
        latestGyroscope = null
        lastOutputTimestampNs = 0L
    }

    private fun interpolate(previous: TimedVector3?, latest: TimedVector3,
        targetNs: Long): Vector3? {
        if (targetNs == latest.timestampNs) return latest.value
        val start = previous ?: return null
        if (targetNs < start.timestampNs || targetNs > latest.timestampNs) return null
        val gap = latest.timestampNs - start.timestampNs
        if (gap <= 0L || gap > maximumInterpolationGapNs) return null
        val alpha = (targetNs - start.timestampNs).toDouble() / gap.toDouble()
        return Vector3(start.value.x + (latest.value.x - start.value.x) * alpha,
            start.value.y + (latest.value.y - start.value.y) * alpha,
            start.value.z + (latest.value.z - start.value.z) * alpha)
    }
}
