package ai.arena.arkernel

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one daemon timeout worker and terminally finishes an active probe after a fixed window. */
class ActiveCompatibilityTestController(
    val probe: ActiveDeviceCompatibilityProbe,
    private val testDurationMillis: Long = 3_000,
    private val onCompleted: (ActiveCompatibilityReport) -> Unit = {},
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ARKernel-CompatibilityTimeout").apply { isDaemon = true }
    }
    @Volatile private var timeout: ScheduledFuture<*>? = null

    init { require(testDurationMillis in 1..60_000) }

    /** Start immediately before or after starting the attached runtime. */
    fun start() {
        check(!closed.get()) { "Compatibility test controller is closed" }
        if (!started.compareAndSet(false, true)) return
        if (probe.isFinished) {
            finishNow()
            return
        }
        timeout = executor.schedule({ finishNow() }, testDurationMillis, TimeUnit.MILLISECONDS)
    }

    /** Idempotently returns the same terminal report and invokes completion at most once. */
    fun finishNow(): ActiveCompatibilityReport {
        check(!closed.get() || probe.isFinished) { "Compatibility test controller is closed" }
        val report = probe.finish()
        if (completed.compareAndSet(false, true)) {
            timeout?.cancel(false)
            runCatching { onCompleted(report) }
            executor.shutdown()
        }
        return report
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timeout?.cancel(false)
        executor.shutdownNow()
    }
}
