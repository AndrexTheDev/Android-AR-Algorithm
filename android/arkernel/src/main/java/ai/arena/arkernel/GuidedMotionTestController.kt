package ai.arena.arkernel

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class GuidedMotionGuidanceState {
    NOT_STARTED,
    HOLD_STILL,
    ROTATE_MULTI_AXIS,
    TRANSLATE_MULTI_DIRECTION,
    MEASURE_KNOWN_DISTANCE,
    COMPLETED,
}

data class GuidedMotionTestConfig(
    val holdStillDurationMillis: Long = 3_000,
    val rotationDurationMillis: Long = 8_000,
    val translationDurationMillis: Long = 8_000,
    /** Zero omits the physical-target stage and therefore cannot imply dimensional validation. */
    val knownDistanceDurationMillis: Long = 0,
) {
    init {
        require(holdStillDurationMillis in 1..60_000)
        require(rotationDurationMillis in 1..60_000)
        require(translationDurationMillis in 1..60_000)
        require(knownDistanceDurationMillis in 0..60_000)
        require(totalDurationMillis in 3..180_000)
    }

    val totalDurationMillis: Long
        get() = Math.addExact(Math.addExact(holdStillDurationMillis,
            rotationDurationMillis), Math.addExact(translationDurationMillis,
            knownDistanceDurationMillis))
}

/**
 * UI-neutral bounded scheduler for one guided motion admission run. Guidance callbacks are hints;
 * only the probe's measured terminal report grants admission. Manual and timed completion are
 * idempotent, callbacks run at most once, and close cancels an unfinished run without admitting it.
 */
class GuidedMotionTestController(
    val probe: GuidedMotionAdmissionProbe,
    private val config: GuidedMotionTestConfig = GuidedMotionTestConfig(),
    private val onGuidanceChanged: (GuidedMotionGuidanceState) -> Unit = {},
    private val onCompleted: (GuidedMotionAdmissionReport) -> Unit = {},
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ARKernel-GuidedMotion").apply { isDaemon = true }
    }
    private val scheduled = ArrayList<ScheduledFuture<*>>()
    @Volatile private var guidance = GuidedMotionGuidanceState.NOT_STARTED

    val currentGuidance: GuidedMotionGuidanceState get() = guidance
    val isFinished: Boolean get() = completed.get()

    @Synchronized
    fun start() {
        check(!closed.get()) { "Guided motion controller is closed" }
        if (started.get()) return
        check(!probe.isFinished) { "Guided motion probe is already finished" }
        started.set(true)
        var elapsed = config.holdStillDurationMillis
        schedule(elapsed, GuidedMotionGuidanceState.ROTATE_MULTI_AXIS)
        elapsed = Math.addExact(elapsed, config.rotationDurationMillis)
        schedule(elapsed, GuidedMotionGuidanceState.TRANSLATE_MULTI_DIRECTION)
        elapsed = Math.addExact(elapsed, config.translationDurationMillis)
        if (config.knownDistanceDurationMillis > 0) {
            schedule(elapsed, GuidedMotionGuidanceState.MEASURE_KNOWN_DISTANCE)
            elapsed = Math.addExact(elapsed, config.knownDistanceDurationMillis)
        }
        scheduled += executor.schedule({ finishNow() }, elapsed, TimeUnit.MILLISECONDS)
        transition(GuidedMotionGuidanceState.HOLD_STILL)
    }

    /** Finishes early when the host has completed the instructions; invokes completion once. */
    @Synchronized
    fun finishNow(): GuidedMotionAdmissionReport {
        check(started.get()) { "Guided motion controller has not started" }
        check(!closed.get() || probe.isFinished) { "Guided motion controller is closed" }
        val report = probe.finish()
        if (completed.compareAndSet(false, true)) {
            scheduled.forEach { it.cancel(false) }
            scheduled.clear()
            transition(GuidedMotionGuidanceState.COMPLETED)
            runCatching { onCompleted(report) }
            executor.shutdown()
        }
        return report
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scheduled.forEach { it.cancel(false) }
        scheduled.clear()
        executor.shutdownNow()
    }

    @Synchronized
    private fun schedule(delayMillis: Long, state: GuidedMotionGuidanceState) {
        scheduled += executor.schedule({
            synchronized(this) {
                if (!completed.get() && !closed.get()) transition(state)
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun transition(state: GuidedMotionGuidanceState) {
        if (guidance == state) return
        guidance = state
        runCatching { onGuidanceChanged(state) }
    }
}
