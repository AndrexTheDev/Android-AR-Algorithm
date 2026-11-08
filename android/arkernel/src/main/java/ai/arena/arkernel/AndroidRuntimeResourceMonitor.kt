package ai.arena.arkernel

import android.annotation.TargetApi
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager

/** Android memory/thermal bridge for [RuntimeResourceAdmissionCoordinator]. */
class AndroidRuntimeResourceMonitor(
    context: Context,
    private val coordinator: RuntimeResourceAdmissionCoordinator,
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var thermalListener: Any? = null
    @Volatile private var closed = false
    @Volatile var lastRefresh: RuntimeResourceAdmissionRefresh? = null
        private set

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = publishMemory(RuntimeMemoryPressure.CRITICAL)
        override fun onTrimMemory(level: Int) = publishMemory(mapTrimMemory(level))
    }

    init { start() }

    @Synchronized
    fun start() {
        check(!closed) { "Runtime resource monitor is closed" }
        if (thermalListener != null) return
        appContext.registerComponentCallbacks(memoryCallbacks)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalListener = Api29.register(powerManager) { status ->
                    publishThermal(AndroidInferenceThermalMonitor.mapStatus(status))
                }
                publishThermal(AndroidInferenceThermalMonitor.mapStatus(
                    Api29.currentStatus(powerManager)))
            } else {
                // API 26-28 expose no thermal callback; NORMAL is an explicit unknown-safe input.
                thermalListener = PRE_Q_REGISTERED
                publishThermal(InferenceThermalState.NORMAL)
            }
        } catch (error: Throwable) {
            val listener = thermalListener
            thermalListener = null
            if (listener != null && listener !== PRE_Q_REGISTERED &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                Api29.unregister(powerManager, listener)
            appContext.unregisterComponentCallbacks(memoryCallbacks)
            throw error
        }
    }

    fun addFrameProcessingDuration(durationNs: Long): Boolean =
        coordinator.addFrameProcessingDuration(durationNs)

    @Synchronized
    fun refresh(): RuntimeResourceAdmissionRefresh {
        check(!closed) { "Runtime resource monitor is closed" }
        return coordinator.refresh().also { lastRefresh = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        appContext.unregisterComponentCallbacks(memoryCallbacks)
        val listener = thermalListener
        thermalListener = null
        if (listener != null && listener !== PRE_Q_REGISTERED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            Api29.unregister(powerManager, listener)
    }

    private fun publishMemory(pressure: RuntimeMemoryPressure) {
        if (closed) return
        coordinator.updateMemoryPressure(pressure)
        publishRefresh()
    }

    private fun publishThermal(state: InferenceThermalState) {
        if (closed) return
        coordinator.updateThermalState(state)
        publishRefresh()
    }

    private fun publishRefresh() {
        try {
            lastRefresh = coordinator.refresh()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            onError(error)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun register(powerManager: PowerManager, callback: (Int) -> Unit): Any {
            val listener = PowerManager.OnThermalStatusChangedListener { callback(it) }
            powerManager.addThermalStatusListener(listener)
            return listener
        }
        fun unregister(powerManager: PowerManager, listener: Any) {
            powerManager.removeThermalStatusListener(
                listener as PowerManager.OnThermalStatusChangedListener)
        }
        fun currentStatus(powerManager: PowerManager): Int = powerManager.currentThermalStatus
    }

    companion object {
        private val PRE_Q_REGISTERED = Any()

        internal fun mapTrimMemory(level: Int): RuntimeMemoryPressure = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> RuntimeMemoryPressure.CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> RuntimeMemoryPressure.MODERATE
            else -> RuntimeMemoryPressure.NORMAL
        }
    }
}
