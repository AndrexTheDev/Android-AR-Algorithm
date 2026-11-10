@file:Suppress("DEPRECATION")

package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelAxisSample
import ai.arena.arkernel.ArKernelAxisSummary
import ai.arena.arkernel.ArKernelCaptureSummary
import ai.arena.arkernel.ArKernelFieldDiagnosticsLogEventKind
import ai.arena.arkernel.ArKernelPhysicalSelfTestMetrics
import ai.arena.arkernel.ArKernelThermalSample
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class PendingLogEvent(
    val kind: ArKernelFieldDiagnosticsLogEventKind,
    val code: String?,
    val value: Long?,
    val detailToken: String?,
)

internal data class PhysicalSelfTestResult(
    val events: List<PendingLogEvent>,
    val passed: Int,
    val total: Int,
    val summary: String,
)

private data class ImuResult(
    val acc: ArKernelAxisSummary,
    val gyro: ArKernelAxisSummary,
)

private data class CameraTestResult(
    val summary: ArKernelCaptureSummary,
    val opened: Boolean,
    val configured: Boolean,
    val closed: Boolean,
)

/**
 * Guided, model-independent hardware self-test runner for one physical device.
 *
 * It measures only local structural invariants: sensor/camera catalogs, IMU
 * stillness stream integrity, Camera2 lifecycle and repeated capture results,
 * byte-exact storage round trip, deterministic core encode/parse and a bounded
 * thermal time series. It never captures or retains images, per-frame poses,
 * trajectories, calibration arrays or dense geometry; user confirmations are
 * explicitly host-reported, not verified; every result stays `unverified`.
 * No external target, rig, device model or physical ground truth is used.
 */
internal class PhysicalSelfTestRunner(private val activity: Activity) {

    fun run(onProgress: (String) -> Unit): PhysicalSelfTestResult {
        val events = mutableListOf<PendingLogEvent>()
        var passed = 0
        var total = 0

        fun record(
            kind: ArKernelFieldDiagnosticsLogEventKind,
            code: String,
            value: Long,
            detailToken: String,
        ) {
            events += PendingLogEvent(kind, code, value, detailToken)
        }

        return try {
            onProgress("Schritt 1/8: Gerätekataloge werden gelesen …")
            record(
                ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
                "self.test.config",
                0L,
                "imu.10s.camera.30f.storage.64k.thermal.20s.unverified",
            )
            val sensorEvents = sensorCatalog()
            val cameraEvents = cameraCatalog()
            events += sensorEvents
            events += cameraEvents
            val catalogPass = sensorEvents.isNotEmpty() && cameraEvents.isNotEmpty()
            total++
            if (catalogPass) passed++
            record(
                ArKernelFieldDiagnosticsLogEventKind.TIMING,
                "self.test.budget",
                0L,
                "events.max.128.lines.512.input.32768.catalog.pass." +
                    passFlag(catalogPass),
            )

            onProgress("Schritt 2/8: Bitte Gerät flach auf einen Tisch legen …")
            if (confirm(
                    "IMU-Stillstand",
                    "Gerät flach hinlegen und 10 Sekunden nicht bewegen. " +
                        "Danach Bereit antippen.",
                )
            ) {
                record(
                    ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                    "user.confirmed.stillness",
                    1L,
                    "host.reported.not.verified",
                )
                val imu = runImuStillness()
                val imuPass = imu.acc.structuralPass && imu.gyro.structuralPass
                total++
                if (imuPass) passed++
                record(
                    ArKernelFieldDiagnosticsLogEventKind.IMU_STILLNESS,
                    "accelerometer",
                    imu.acc.sampleCount.toLong(),
                    ArKernelPhysicalSelfTestMetrics.axisToken(imu.acc),
                )
                record(
                    ArKernelFieldDiagnosticsLogEventKind.IMU_STILLNESS,
                    "gyroscope",
                    imu.gyro.sampleCount.toLong(),
                    ArKernelPhysicalSelfTestMetrics.axisToken(imu.gyro),
                )
                record(
                    ArKernelFieldDiagnosticsLogEventKind.TIMING,
                    "imu.duration.ms",
                    IMU_DURATION_MS,
                    "measured.by.host",
                )
            } else {
                record(
                    ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                    "user.confirmation.timeout",
                    0L,
                    "host.reported.not.verified",
                )
                total++
            }

            onProgress("Schritt 3/8: Kamera-Test wird vorbereitet …")
            if (confirm(
                    "Camera2-Test",
                    "Gerät ruhig halten, eine beliebige Szene genügt. " +
                        "Die App öffnet die Rückkamera, holt 30 Preview-Ergebnisse " +
                        "und schließt sie wieder. Bereit antippen.",
                )
            ) {
                record(
                    ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                    "user.confirmed.camera",
                    1L,
                    "host.reported.not.verified",
                )
                val camera = runCameraTest()
                val cameraPass = camera.summary.structuralPass &&
                    camera.opened && camera.configured && camera.closed
                total++
                if (cameraPass) passed++
                record(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_LIFECYCLE,
                    "camera.open",
                    passFlag(camera.opened).toLong(),
                    "unverified",
                )
                record(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_LIFECYCLE,
                    "camera.session",
                    passFlag(camera.configured).toLong(),
                    "unverified",
                )
                record(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_FRAMES,
                    "preview",
                    camera.summary.frameCount.toLong(),
                    ArKernelPhysicalSelfTestMetrics.captureToken(camera.summary),
                )
                record(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_LIFECYCLE,
                    "camera.close",
                    passFlag(camera.closed).toLong(),
                    "unverified",
                )
            } else {
                record(
                    ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                    "user.confirmation.timeout",
                    0L,
                    "host.reported.not.verified",
                )
                total++
            }

            onProgress("Schritt 4/8: Storage-Roundtrip …")
            val storage = runStorageTest()
            total++
            if (storage.structuralPass) passed++
            record(
                ArKernelFieldDiagnosticsLogEventKind.STORAGE_ROUNDTRIP,
                "storage",
                storage.byteCount.toLong(),
                ArKernelPhysicalSelfTestMetrics.storageToken(storage),
            )

            onProgress("Schritt 5/8: Deterministischer Kern-Selbsttest …")
            val compute = ArKernelPhysicalSelfTestMetrics.computeSelfTest()
            total++
            if (compute.structuralPass) passed++
            record(
                ArKernelFieldDiagnosticsLogEventKind.COMPUTE_SELFTEST,
                "compute",
                passFlag(compute.structuralPass).toLong(),
                ArKernelPhysicalSelfTestMetrics.computeToken(compute),
            )

            onProgress("Schritt 6/8: Thermal-Zeitreihe wird geschrieben …")
            if (confirm(
                    "Thermal-Zeitreihe",
                    "Gerät 20 Sekunden ruhig liegen lassen. " +
                        "Die App liest Thermal-Status/Headroom und führt eine " +
                        "begrenzte CPU-Last aus. Bereit antippen.",
                )
            ) {
                record(
                    ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                    "user.confirmed.thermal",
                    1L,
                    "host.reported.not.verified",
                )
                val (thermal, samples) = runThermalSoak()
                total++
                if (thermal.structuralPass) passed++
                samples.forEachIndexed { index, sample ->
                    record(
                        ArKernelFieldDiagnosticsLogEventKind.THERMAL_SOAK,
                        "thermal.sample.$index",
                        sample.status.toLong(),
                        ArKernelPhysicalSelfTestMetrics.opaqueToken(
                            "headroom.${sample.headroom}.unverified",
                        ),
                    )
                }
                record(
                    ArKernelFieldDiagnosticsLogEventKind.THERMAL_SOAK,
                    "thermal.summary",
                    thermal.sampleCount.toLong(),
                    ArKernelPhysicalSelfTestMetrics.thermalToken(thermal),
                )
            } else {
                record(
                    ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                    "user.confirmation.timeout",
                    0L,
                    "host.reported.not.verified",
                )
                total++
            }

            onProgress("Schritt 7/8: Selbsttests abgeschlossen …")
            record(
                ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                "self.test.finished",
                1L,
                "host.reported.not.verified",
            )

            val summary = "$passed/$total strukturelle Selbsttests bestanden. " +
                "Alle Ergebnisse bleiben unverified; keine physikalische " +
                "Bodentruth, keine Zentimetervalidierung."
            PhysicalSelfTestResult(events.toList(), passed, total, summary)
        } catch (error: Exception) {
            record(
                ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                "self.test.error",
                0L,
                ArKernelPhysicalSelfTestMetrics.opaqueToken(
                    error.javaClass.simpleName,
                ),
            )
            PhysicalSelfTestResult(
                events.toList(),
                passed,
                total,
                "Selbsttest abgebrochen: ${error.message}. unverified.",
            )
        }
    }

    private fun confirm(title: String, message: String): Boolean {
        val latch = CountDownLatch(1)
        val confirmed = AtomicBoolean(false)
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Bereit") { _, _ ->
                    confirmed.set(true)
                    latch.countDown()
                }
                .setNegativeButton("Abbrechen") { _, _ -> latch.countDown() }
                .show()
        }
        return try {
            latch.await(90, TimeUnit.SECONDS) && confirmed.get()
        } catch (error: InterruptedException) {
            false
        }
    }

    private fun sensorCatalog(): List<PendingLogEvent> {
        val manager = activity.getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val sensors = manager?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
        val events = mutableListOf<PendingLogEvent>()
        sensors.take(24).forEachIndexed { index, sensor ->
            val raw = "type.${sensor.type}.vendor.${sensor.vendor}." +
                "res.${sensor.resolution}.range.${sensor.maximumRange}." +
                "power.${sensor.power}.minDelay.${sensor.minDelay}." +
                "fifo.${sensor.fifoReservedEventCount}." +
                "maxFifo.${sensor.fifoMaxEventCount}"
            events += PendingLogEvent(
                ArKernelFieldDiagnosticsLogEventKind.SENSOR_CATALOG,
                "sensor.$index",
                sensor.type.toLong(),
                ArKernelPhysicalSelfTestMetrics.opaqueToken(raw),
            )
        }
        events += PendingLogEvent(
            ArKernelFieldDiagnosticsLogEventKind.SENSOR_CATALOG,
            "sensor.count",
            sensors.size.toLong(),
            "catalog.bounded.24",
        )
        return events
    }

    private fun cameraCatalog(): List<PendingLogEvent> {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE)
            as? CameraManager ?: return emptyList()
        val ids = try {
            manager.getCameraIdList().toList()
        } catch (error: Exception) {
            emptyList()
        }
        val events = mutableListOf<PendingLogEvent>()
        ids.take(8).forEachIndexed { index, id ->
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                val level = characteristics.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                ) ?: -1
                val map: StreamConfigurationMap? = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                )
                val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.take(4)
                    ?.joinToString(".") { "${it.width}x${it.height}" }
                    ?: "none"
                val caps = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                )?.take(8)?.joinToString(".") ?: "none"
                val raw = "id.$id.level.$level.sizes.$sizes.caps.$caps"
                events += PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_CATALOG,
                    "camera.$index",
                    level.coerceAtLeast(0).toLong(),
                    ArKernelPhysicalSelfTestMetrics.opaqueToken(raw),
                )
            } catch (error: Exception) {
                events += PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_CATALOG,
                    "camera.$index",
                    0L,
                    "catalog.query.failed",
                )
            }
        }
        events += PendingLogEvent(
            ArKernelFieldDiagnosticsLogEventKind.CAMERA_CATALOG,
            "camera.count",
            ids.size.toLong(),
            "catalog.bounded.8",
        )
        return events
    }

    private fun runImuStillness(): ImuResult {
        val manager = activity.getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val accSensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (manager == null || accSensor == null || gyroSensor == null) {
            return ImuResult(
                ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                    "acc", emptyList(), EXPECTED_MIN_IMU_SAMPLES),
                ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                    "gyro", emptyList(), EXPECTED_MIN_IMU_SAMPLES),
            )
        }
        val thread = HandlerThread("arkernel-imu").apply { start() }
        val handler = Handler(thread.looper)
        val accSamples = Collections.synchronizedList(
            mutableListOf<ArKernelAxisSample>(),
        )
        val gyroSamples = Collections.synchronizedList(
            mutableListOf<ArKernelAxisSample>(),
        )
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                val sample = ArKernelAxisSample(
                    event.timestamp,
                    event.values[0],
                    event.values[1],
                    event.values[2],
                )
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER ->
                        if (accSamples.size < MAX_IMU_SAMPLES) accSamples.add(sample)
                    Sensor.TYPE_GYROSCOPE ->
                        if (gyroSamples.size < MAX_IMU_SAMPLES) gyroSamples.add(sample)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            manager.registerListener(
                listener, accSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            manager.registerListener(
                listener, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            val done = CountDownLatch(1)
            handler.postDelayed({ done.countDown() }, IMU_DURATION_MS)
            done.await(IMU_DURATION_MS + 5_000L, TimeUnit.MILLISECONDS)
        } finally {
            manager.unregisterListener(listener)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        val accCopy = synchronized(accSamples) { accSamples.toList() }
        val gyroCopy = synchronized(gyroSamples) { gyroSamples.toList() }
        return ImuResult(
            ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                "acc", accCopy, EXPECTED_MIN_IMU_SAMPLES),
            ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                "gyro", gyroCopy, EXPECTED_MIN_IMU_SAMPLES),
        )
    }

    private fun runCameraTest(): CameraTestResult {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return CameraTestResult(failedCapture(1), false, false, false)
        }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE)
            as? CameraManager
            ?: return CameraTestResult(failedCapture(1), false, false, false)
        val backId = try {
            manager.getCameraIdList().firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraMetadata.LENS_FACING_BACK
            } ?: manager.getCameraIdList().firstOrNull()
        } catch (error: Exception) {
            null
        } ?: return CameraTestResult(failedCapture(1), false, false, false)
        val size: Size = try {
            val map = manager.getCameraCharacteristics(backId).get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            )
            map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.filter { it.width <= 1280 && it.height <= 720 }
                ?.minByOrNull { it.width.toLong() * it.height.toLong() }
                ?: map?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.minByOrNull { it.width.toLong() * it.height.toLong() }
        } catch (error: Exception) {
            null
        } ?: return CameraTestResult(failedCapture(1), false, false, false)

        val thread = HandlerThread("arkernel-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, 2)
        val timestamps = Collections.synchronizedList(mutableListOf<Long>())
        val exposures = Collections.synchronizedList(mutableListOf<Long>())
        val isos = Collections.synchronizedList(mutableListOf<Int>())
        val focuses = Collections.synchronizedList(mutableListOf<Float>())
        val errors = AtomicInteger(0)
        val disconnects = AtomicInteger(0)
        val opened = AtomicBoolean(false)
        val configured = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val done = CountDownLatch(1)
        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun finish() {
            if (!stopped.compareAndSet(false, true)) return
            try {
                session?.stopRepeating()
            } catch (error: Exception) {
            }
            try {
                session?.close()
            } catch (error: Exception) {
            }
            try {
                cameraDevice?.close()
            } catch (error: Exception) {
            }
            try {
                reader.close()
            } catch (error: Exception) {
            }
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            closed.set(true)
            done.countDown()
        }

        reader.setOnImageAvailableListener({ imageReader ->
            try {
                imageReader.acquireLatestImage()?.close()
            } catch (error: Exception) {
            }
        }, handler)

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                activeSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    ?: SystemClock.elapsedRealtimeNanos()
                timestamps.add(timestamp)
                exposures.add(
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L)
                isos.add(result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0)
                val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (focus != null && !focus.isNaN() && !focus.isInfinite()) {
                    focuses.add(focus)
                }
                if (timestamps.size >= TARGET_FRAMES) {
                    handler.post { finish() }
                }
            }

            override fun onCaptureFailed(
                activeSession: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                errors.incrementAndGet()
            }
        }

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened.set(true)
                cameraDevice = camera
                try {
                    val builder = camera.createCaptureRequest(
                        CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(reader.surface)
                    builder.set(
                        CaptureRequest.CONTROL_MODE,
                        CameraMetadata.CONTROL_MODE_AUTO,
                    )
                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(
                                activeSession: CameraCaptureSession,
                            ) {
                                configured.set(true)
                                session = activeSession
                                try {
                                    activeSession.setRepeatingRequest(
                                        builder.build(), captureCallback, handler)
                                } catch (error: Exception) {
                                    errors.incrementAndGet()
                                    handler.post { finish() }
                                }
                            }

                            override fun onConfigureFailed(
                                activeSession: CameraCaptureSession,
                            ) {
                                errors.incrementAndGet()
                                handler.post { finish() }
                            }
                        },
                        handler,
                    )
                } catch (error: Exception) {
                    errors.incrementAndGet()
                    handler.post { finish() }
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                disconnects.incrementAndGet()
                handler.post { finish() }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                errors.incrementAndGet()
                handler.post { finish() }
            }
        }

        try {
            manager.openCamera(backId, stateCallback, handler)
        } catch (error: Exception) {
            errors.incrementAndGet()
            finish()
        }
        handler.postDelayed({ finish() }, CAMERA_TIMEOUT_MS)
        done.await(CAMERA_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
        finish()

        val timestampsCopy = synchronized(timestamps) { timestamps.toList() }
        val exposuresCopy = synchronized(exposures) { exposures.toList() }
        val isosCopy = synchronized(isos) { isos.toList() }
        val focusesCopy = synchronized(focuses) { focuses.toList() }
        val summary = ArKernelPhysicalSelfTestMetrics.summarizeCaptures(
            timestampsCopy,
            exposuresCopy,
            isosCopy,
            focusesCopy,
            TARGET_FRAMES,
            errors.get(),
            disconnects.get(),
        )
        return CameraTestResult(
            summary,
            opened.get(),
            configured.get(),
            closed.get(),
        )
    }

    private fun failedCapture(errorCount: Int): ArKernelCaptureSummary =
        ArKernelPhysicalSelfTestMetrics.summarizeCaptures(
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            TARGET_FRAMES,
            errorCount,
            0,
        )

    private fun runStorageTest() =
        try {
            val bytes = ByteArray(STORAGE_BYTES) { index ->
                ((index * 31 + 17) and 0xFF).toByte()
            }
            val file = File(activity.filesDir, "arkernel_physical_selftest.bin")
            file.writeBytes(bytes)
            val readBack = file.readBytes()
            file.delete()
            ArKernelPhysicalSelfTestMetrics.storageRoundTrip(bytes, readBack)
        } catch (error: Exception) {
            ArKernelPhysicalSelfTestMetrics.storageRoundTrip(
                ByteArray(STORAGE_BYTES), ByteArray(0))
        }

    private fun runThermalSoak():
        Pair<ai.arena.arkernel.ArKernelThermalSummary, List<ArKernelThermalSample>> {
        val samples = mutableListOf<ArKernelThermalSample>()
        val manager = activity.getSystemService(Context.POWER_SERVICE)
            as? PowerManager
        repeat(THERMAL_SAMPLES) { index ->
            val status = if (manager != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                manager.getCurrentThermalStatus()
            } else {
                0
            }
            val headroom = if (manager != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                val value = manager.getThermalHeadroom(30)
                if (!value.isNaN() && !value.isInfinite() && value >= 0f) {
                    value
                } else {
                    0f
                }
            } else {
                0f
            }
            samples += ArKernelThermalSample(
                SystemClock.elapsedRealtimeNanos(), status, headroom)
            var sink = 0L
            for (i in 0 until 200_000) sink += (i % 7).toLong()
            if (sink == Long.MIN_VALUE) samples.clear()
            if (index < THERMAL_SAMPLES - 1) {
                try {
                    Thread.sleep(THERMAL_INTERVAL_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return ArKernelPhysicalSelfTestMetrics.summarizeThermal(samples) to samples
    }

    private fun passFlag(value: Boolean): Int = if (value) 1 else 0

    private companion object {
        const val IMU_DURATION_MS = 10_000L
        const val EXPECTED_MIN_IMU_SAMPLES = 100
        const val MAX_IMU_SAMPLES = 4000
        const val TARGET_FRAMES = 30
        const val CAMERA_TIMEOUT_MS = 8_000L
        const val THERMAL_SAMPLES = 10
        const val THERMAL_INTERVAL_MS = 2_000L
        const val STORAGE_BYTES = 64 * 1024
    }
}
