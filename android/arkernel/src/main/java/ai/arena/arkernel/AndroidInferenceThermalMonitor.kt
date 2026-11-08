package ai.arena.arkernel

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Bridges Android's thermal status into a bounded inference scheduler on API 29+. */
class AndroidInferenceThermalMonitor(
    context: Context,
    private val scheduler: BoundedInferenceScheduler,
) : AutoCloseable {
    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as PowerManager
    private var listener: Any? = null
    private var closed = false
    @Volatile var currentState: InferenceThermalState = InferenceThermalState.NORMAL
        private set

    init { start() }

    @Synchronized
    fun start() {
        check(!closed) { "Thermal monitor is closed" }
        if (listener != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listener = Api29.register(powerManager) { status -> publish(mapStatus(status)) }
            publish(mapStatus(Api29.currentStatus(powerManager)))
        } else {
            publish(InferenceThermalState.NORMAL)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val registeredListener = listener
        listener = null
        if (registeredListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            Api29.unregister(powerManager, registeredListener)
    }

    @Synchronized
    private fun publish(state: InferenceThermalState) {
        if (closed) return
        currentState = state
        scheduler.updateThermalState(state)
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
        private const val STATUS_NONE = 0
        private const val STATUS_LIGHT = 1
        private const val STATUS_MODERATE = 2
        private const val STATUS_SEVERE = 3
        private const val STATUS_CRITICAL = 4
        private const val STATUS_EMERGENCY = 5
        private const val STATUS_SHUTDOWN = 6

        internal fun mapStatus(status: Int): InferenceThermalState = when (status) {
            STATUS_NONE -> InferenceThermalState.NORMAL
            STATUS_LIGHT, STATUS_MODERATE -> InferenceThermalState.WARM
            STATUS_SEVERE -> InferenceThermalState.HOT
            STATUS_CRITICAL, STATUS_EMERGENCY, STATUS_SHUTDOWN ->
                InferenceThermalState.CRITICAL
            else -> InferenceThermalState.HOT
        }
    }
}
