package ai.arena.arkernel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Lifecycle owner for Android accelerometer/gyroscope callbacks. It combines
 * the latest values at each strictly newer boot-time timestamp and never runs
 * sensor work on the UI thread.
 */
class AndroidImuSource(
    context: Context,
    private val vio: ArKernelVio,
    private val sensorDelay: Int = SensorManager.SENSOR_DELAY_FASTEST,
) : SensorEventListener, AutoCloseable {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: error("Device has no accelerometer")
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: error("Device has no gyroscope")
    private var thread: HandlerThread? = null
    private var latestAcceleration: Vector3? = null
    private var latestAngularVelocity: Vector3? = null
    private var lastTimestampNs = 0L

    @Synchronized
    fun start() {
        if (thread != null) return
        vio.resume()
        latestAcceleration = null
        latestAngularVelocity = null
        lastTimestampNs = 0L
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
        latestAcceleration = null
        latestAngularVelocity = null
        lastTimestampNs = 0L
        vio.pause()
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        val value = Vector3(event.values[0].toDouble(), event.values[1].toDouble(),
                            event.values[2].toDouble())
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> latestAcceleration = value
            Sensor.TYPE_GYROSCOPE -> latestAngularVelocity = value
            else -> return
        }
        val acceleration = latestAcceleration ?: return
        val angularVelocity = latestAngularVelocity ?: return
        if (event.timestamp <= lastTimestampNs) return
        lastTimestampNs = event.timestamp
        vio.submitImu(event.timestamp, acceleration, angularVelocity)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun close() = stop()
}
