package ai.arena.arkernel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * Lifecycle owner for Android accelerometer/gyroscope callbacks. It combines
 * the latest values at each strictly newer boot-time timestamp and never runs
 * sensor work on the UI thread.
 */
class AndroidImuSource(
    context: Context,
    private val vio: ArKernelVio,
    private val sensorDelay: Int = SensorManager.SENSOR_DELAY_FASTEST,
    private val sampleSink: (Long, Vector3, Vector3) -> Unit = { timestamp, acceleration, angularVelocity ->
        vio.submitImu(timestamp, acceleration, angularVelocity)
        Unit
    },
    private val compatibilityTimingSink: ((ImuTimingSample) -> Unit)? = null,
) : SensorEventListener, AutoCloseable {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: error("Device has no accelerometer")
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: error("Device has no gyroscope")
    private var thread: HandlerThread? = null
    private val synchronizer = ImuSampleSynchronizer()

    @Synchronized
    fun start() {
        if (thread != null) return
        vio.resume()
        synchronizer.reset()
        val sensorThread = HandlerThread("ARKernel-IMU").also { it.start() }
        val handler = Handler(sensorThread.looper)
        val accelRegistered = manager.registerListener(this, accelerometer, sensorDelay, handler)
        val gyroRegistered = manager.registerListener(this, gyroscope, sensorDelay, handler)
        if (!accelRegistered || !gyroRegistered) {
            manager.unregisterListener(this)
            sensorThread.quitSafely()
            vio.pause()
            error("Could not register required IMU sensors")
        }
        thread = sensorThread
    }

    @Synchronized
    fun stop() {
        val sensorThread = thread ?: return
        manager.unregisterListener(this)
        thread = null
        sensorThread.quitSafely()
        synchronizer.reset()
        vio.pause()
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        val timingKind = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> ImuTimingSampleKind.ACCELEROMETER
            Sensor.TYPE_GYROSCOPE -> ImuTimingSampleKind.GYROSCOPE
            else -> return
        }
        compatibilityTimingSink?.let { sink ->
            runCatching { sink(ImuTimingSample(timingKind, event.timestamp,
                SystemClock.elapsedRealtimeNanos())) }
        }
        val value = Vector3(event.values[0].toDouble(), event.values[1].toDouble(),
                            event.values[2].toDouble())
        val synchronized = synchronizer.add(timingKind, event.timestamp, value) ?: return
        sampleSink(synchronized.timestampNs, synchronized.acceleration,
            synchronized.angularVelocity)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun close() = stop()
}
