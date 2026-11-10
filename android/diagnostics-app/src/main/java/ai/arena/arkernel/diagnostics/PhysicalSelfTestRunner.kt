@file:Suppress("DEPRECATION")

package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelAxisSample
import ai.arena.arkernel.ArKernelAxisSummary
import ai.arena.arkernel.ArKernelCaptureSummary
import ai.arena.arkernel.ArKernelFieldDiagnosticsLogEventKind
import ai.arena.arkernel.ArKernelFieldDiagnosticsTestPlan
import ai.arena.arkernel.ArKernelFieldDiagnosticsTestPlanBundle
import ai.arena.arkernel.ArKernelFieldDiagnosticsTestPlanStep
import ai.arena.arkernel.ArKernelPhysicalSelfTestMetrics
import ai.arena.arkernel.ArKernelThermalSample
import ai.arena.arkernel.ArKernelThermalSummary
import ai.arena.arkernel.defaultArKernelTestPlan
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.media.AudioManager
import android.media.ImageReader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Size
import java.io.File
import java.util.Collections
import java.util.Locale
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
    val maxEvents: Int,
    val summary: String,
)

private data class PlanOpResult(
    val events: List<PendingLogEvent>,
    val pass: Boolean,
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

private data class CameraRepeatResult(
    val opened: Int,
    val errors: Int,
    val disconnects: Int,
    val pass: Boolean,
)

private data class StorageRepeatResult(
    val runs: Int,
    val passedRuns: Int,
    val firstCrc: String,
    val pass: Boolean,
)

private data class ComputeRepeatResult(
    val iterations: Int,
    val passedRuns: Int,
    val pass: Boolean,
)

private data class SensorCatalogResult(
    val events: List<PendingLogEvent>,
    val count: Int,
)

private data class CameraCatalogResult(
    val events: List<PendingLogEvent>,
    val count: Int,
)

/**
 * Single installed, plan-driven hardware self-test engine.
 *
 * `ARKFDPLAN1` plans select already-implemented operators and their bounded
 * parameters; they cannot add new low-level capabilities. The app measures only
 * local structural invariants and readable device facts. It never retains
 * images, poses, trajectories, calibration arrays or dense geometry; user
 * confirmations are `host.reported.not.verified`; every result stays
 * `unverified`. No external target, rig, device model or physical ground truth
 * is used.
 */
internal class PhysicalSelfTestRunner(private val activity: Activity) {

    /** Legacy entry point: run the built-in ultimate plan. */
    fun run(onProgress: (String) -> Unit): PhysicalSelfTestResult {
        val plan = defaultArKernelTestPlan()
        return runPlan(
            plan,
            ArKernelFieldDiagnosticsTestPlanBundle.encode(plan),
            onProgress,
        )
    }

    fun runPlan(
        plan: ArKernelFieldDiagnosticsTestPlan,
        planText: String,
        onProgress: (String) -> Unit,
    ): PhysicalSelfTestResult {
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
            onProgress("Ultimativer Lauf: Bestätige den Start …")
            if (!confirm(
                    "Ultimativer Testlauf",
                    "Gerät flach hinlegen, Kamera frei halten und während des " +
                        "Laufs nicht bewegen. Der Plan fragt keine externen " +
                        "Targets ab. Bereit antippen.",
                )
            ) {
                record(
                    ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                    "user.confirmation.timeout",
                    0L,
                    "host.reported.not.verified",
                )
                return PhysicalSelfTestResult(
                    events.toList(), 0, 0, plan.maxEvents,
                    "Lauf abgebrochen: keine Bestätigung. unverified.",
                )
            }

            val crc = ArKernelPhysicalSelfTestMetrics.crc32Hex(
                planText.toByteArray(Charsets.UTF_8))
            record(
                ArKernelFieldDiagnosticsLogEventKind.PLAN_METADATA,
                "plan.engine",
                1L,
                "arkfdplan1.v1",
            )
            record(
                ArKernelFieldDiagnosticsLogEventKind.PLAN_METADATA,
                "plan.id",
                0L,
                ArKernelPhysicalSelfTestMetrics.opaqueToken(plan.planId),
            )
            record(
                ArKernelFieldDiagnosticsLogEventKind.PLAN_METADATA,
                "plan.revision",
                plan.revision.toLong(),
                "unverified",
            )
            record(
                ArKernelFieldDiagnosticsLogEventKind.PLAN_METADATA,
                "plan.max.events",
                plan.maxEvents.toLong(),
                "budget",
            )
            record(
                ArKernelFieldDiagnosticsLogEventKind.PLAN_METADATA,
                "plan.steps",
                plan.steps.size.toLong(),
                "bounded.24",
            )
            record(
                ArKernelFieldDiagnosticsLogEventKind.PLAN_CRC,
                "plan.crc32",
                0L,
                "crc.$crc",
            )
            record(
                ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                "user.confirmed.plan",
                1L,
                "host.reported.not.verified",
            )

            for ((index, step) in plan.steps.withIndex()) {
                onProgress(
                    "Schritt ${index + 1}/${plan.steps.size}: ${step.op} …")
                val result = execute(step)
                total++
                if (result.pass) passed++
                events += result.events
                val code = step.op.lowercase(Locale.ROOT).replace('_', '.')
                record(
                    ArKernelFieldDiagnosticsLogEventKind.PLAN_STEP,
                    code,
                    step.durationMs,
                    ArKernelPhysicalSelfTestMetrics.opaqueToken(
                        "count.${step.count}.expected.${step.expectedMin}." +
                            "label.${step.label}.pass." +
                            passFlag(result.pass),
                    ),
                )
            }

            record(
                ArKernelFieldDiagnosticsLogEventKind.PLAN_RESULT,
                "plan.result",
                passed.toLong(),
                ArKernelPhysicalSelfTestMetrics.opaqueToken(
                    "pass.$passed.of.$total.unverified",
                ),
            )
            record(
                ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                "self.test.finished",
                1L,
                "host.reported.not.verified",
            )

            val summary = "$passed/$total Plan-Schritte bestanden. Alle " +
                "Ergebnisse bleiben unverified; keine physikalische Bodentruth, " +
                "keine Zentimetervalidierung. Bitte ARKFDLOG1 und ARKFDPLAN1 " +
                "hochladen."
            PhysicalSelfTestResult(
                events.toList(), passed, total, plan.maxEvents, summary)
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
                events.toList(), passed, total, plan.maxEvents,
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

    private fun execute(step: ArKernelFieldDiagnosticsTestPlanStep): PlanOpResult =
        when (step.op) {
            "CAPABILITY_CATALOG" -> capabilityCatalog()
            "SENSOR_CATALOG" -> sensorCatalogOp(step)
            "CAMERA_CATALOG" -> cameraCatalogOp(step)
            "PERMISSION_CATALOG" -> permissionCatalog()
            "PACKAGE_CATALOG" -> packageCatalog()
            "MEMORY_SNAPSHOT" -> memorySnapshot()
            "DISPLAY_SNAPSHOT" -> displaySnapshot()
            "AUDIO_CATALOG" -> audioCatalog()
            "CONNECTIVITY_CATALOG" -> connectivityCatalog()
            "BATTERY_SNAPSHOT" -> batterySnapshot()
            "IMU_STILLNESS" -> imuStillnessOp(step)
            "SENSOR_LIVE" -> sensorLiveOp(step)
            "CAMERA_PREVIEW" -> cameraPreviewOp(step)
            "CAMERA_LIFECYCLE_REPEAT" -> cameraRepeatOp(step)
            "STORAGE_ROUNDTRIP" -> storageOp(step)
            "COMPUTE_SELFTEST" -> computeOp(step)
            "THERMAL_SOAK" -> thermalOp(step)
            else -> PlanOpResult(
                listOf(
                    PendingLogEvent(
                        ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                        "plan.unknown.op",
                        0L,
                        "host.reported.not.verified",
                    ),
                ),
                false,
            )
        }

    private fun capabilityCatalog(): PlanOpResult = PlanOpResult(
        listOf(
            PendingLogEvent(
                ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
                "plan.capabilities",
                0L,
                "base.header.always.present",
            ),
        ),
        true,
    )

    private fun sensorCatalogOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val result = sensorCatalog(step.count.coerceIn(1, 32))
        val pass = result.count >= step.expectedMin.coerceIn(0, 32)
        return PlanOpResult(result.events, pass)
    }

    private fun cameraCatalogOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val result = cameraCatalog(step.count.coerceIn(1, 16))
        val pass = result.count >= step.expectedMin.coerceIn(0, 16)
        return PlanOpResult(result.events, pass)
    }

    private fun permissionCatalog(): PlanOpResult {
        val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_NETWORK_STATE,
            // Declared in the manifest; SENSOR_DELAY_FASTEST otherwise throws
            // SecurityException on Android 12+. Keep the literal in sync with it.
            "android.permission.HIGH_SAMPLING_RATE_SENSORS",
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        var granted = 0
        val parts = permissions.map { permission ->
            val value = activity.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED
            if (value) granted++
            "${permission.substringAfterLast('.')}.${passFlag(value)}"
        }
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "granted.$granted.of.${permissions.size}." + parts.joinToString("."))
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.PERMISSION_CATALOG,
                    "permissions",
                    granted.toLong(),
                    token,
                ),
            ),
            true,
        )
    }

    private fun packageCatalog(): PlanOpResult {
        val packages = listOf("com.google.ar.core", "com.google.android.gms")
        var present = 0
        val parts = packages.map { name ->
            val value = try {
                activity.packageManager.getPackageInfo(name, 0)
                true
            } catch (error: Exception) {
                false
            }
            if (value) present++
            "${name.substringAfterLast('.')}.${passFlag(value)}"
        }
        val features = listOf(
            "android.hardware.vulkan",
            "android.hardware.camera.any",
            "android.hardware.sensor.gyroscope",
            "android.hardware.sensor.accelerometer",
        ).map { feature ->
            "${feature.substringAfterLast('.')}." +
                passFlag(activity.packageManager.hasSystemFeature(feature))
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "abi.unknown"
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "present.$present.of.${packages.size}." +
                parts.joinToString(".") + "." +
                features.joinToString(".") + ".abi.$abi")
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.PACKAGE_CATALOG,
                    "packages",
                    present.toLong(),
                    token,
                ),
            ),
            true,
        )
    }

    private fun memorySnapshot(): PlanOpResult {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE)
            as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024L * 1024L)
        val availMb = info.availMem / (1024L * 1024L)
        val lowRam = manager?.isLowRamDevice() == true
        val cores = Runtime.getRuntime().availableProcessors()
        val freeMb = try {
            StatFs(activity.filesDir.absolutePath).getAvailableBytes() /
                (1024L * 1024L)
        } catch (error: Exception) {
            0L
        }
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "total.mb.$totalMb.avail.mb.$availMb.low.ram." +
                "${passFlag(lowRam)}.cores.$cores.storage.free.mb.$freeMb")
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.MEMORY_SNAPSHOT,
                    "memory",
                    totalMb,
                    token,
                ),
            ),
            totalMb > 0L,
        )
    }

    private fun displaySnapshot(): PlanOpResult {
        val metrics: DisplayMetrics = activity.resources.displayMetrics
        val display = activity.windowManager.defaultDisplay
        val refreshRate = display?.refreshRate ?: 0f
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "width.${metrics.widthPixels}.height.${metrics.heightPixels}." +
                "density.${metrics.density}.dpi.${metrics.densityDpi}." +
                "xdpi.${metrics.xdpi}.ydpi.${metrics.ydpi}." +
                "refresh.${refreshRate}")
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.DISPLAY_SNAPSHOT,
                    "display",
                    metrics.widthPixels.toLong(),
                    token,
                ),
            ),
            metrics.widthPixels > 0 && metrics.heightPixels > 0 &&
                metrics.density > 0f,
        )
    }

    private fun audioCatalog(): PlanOpResult {
        val manager = activity.getSystemService(Context.AUDIO_SERVICE)
            as? AudioManager
        val sampleRate = manager?.getProperty(
            AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val frames = manager?.getProperty(
            AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        val mode = manager?.mode ?: -1
        val ringer = manager?.ringerMode ?: -1
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "sample.rate.$sampleRate.frames.$frames.mode.$mode.ringer.$ringer")
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.AUDIO_CATALOG,
                    "audio",
                    sampleRate.toLong(),
                    token,
                ),
            ),
            sampleRate > 0,
        )
    }

    private fun connectivityCatalog(): PlanOpResult {
        val manager = activity.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        val caps = try {
            manager?.let { connectivity ->
                connectivity.activeNetwork?.let { network ->
                    connectivity.getNetworkCapabilities(network)
                }
            }
        } catch (error: Exception) {
            null
        }
        val capabilityNames = listOf(
            "internet" to NetworkCapabilities.NET_CAPABILITY_INTERNET,
            "validated" to NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            "not.metered" to NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            "not.roaming" to NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
        )
        val transportNames = listOf(
            "wifi" to NetworkCapabilities.TRANSPORT_WIFI,
            "cellular" to NetworkCapabilities.TRANSPORT_CELLULAR,
            "ethernet" to NetworkCapabilities.TRANSPORT_ETHERNET,
            "bluetooth" to NetworkCapabilities.TRANSPORT_BLUETOOTH,
            "vpn" to NetworkCapabilities.TRANSPORT_VPN,
        )
        var count = 0
        val parts = capabilityNames.map { (name, value) ->
            val yes = caps?.hasCapability(value) == true
            if (yes) count++
            "$name.${passFlag(yes)}"
        } + transportNames.map { (name, value) ->
            val yes = caps?.hasTransport(value) == true
            if (yes) count++
            "$name.${passFlag(yes)}"
        }
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "capabilities.$count." + parts.joinToString("."))
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.CONNECTIVITY_CATALOG,
                    "connectivity",
                    count.toLong(),
                    token,
                ),
            ),
            manager != null,
        )
    }

    private fun batterySnapshot(): PlanOpResult {
        val intent: Intent? = activity.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val temperature = intent?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "level.$level.scale.$scale.health.$health.status.$status." +
                "plugged.$plugged.temperature.$temperature.voltage.$voltage")
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.BATTERY_SNAPSHOT,
                    "battery",
                    level.toLong().coerceAtLeast(0L),
                    token,
                ),
            ),
            intent != null && scale > 0,
        )
    }

    private fun imuStillnessOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val imu = runImuStillness(
            step.durationMs.coerceIn(1_000L, 120_000L),
            step.count.coerceIn(1, 4_000),
            step.expectedMin.coerceIn(1, 4_000),
        )
        val pass = imu.acc.structuralPass && imu.gyro.structuralPass
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.IMU_STILLNESS,
                    "accelerometer",
                    imu.acc.sampleCount.toLong(),
                    ArKernelPhysicalSelfTestMetrics.axisToken(imu.acc),
                ),
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.IMU_STILLNESS,
                    "gyroscope",
                    imu.gyro.sampleCount.toLong(),
                    ArKernelPhysicalSelfTestMetrics.axisToken(imu.gyro),
                ),
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.TIMING,
                    "imu.duration.ms",
                    step.durationMs.coerceIn(1_000L, 120_000L),
                    "measured.by.host",
                ),
            ),
            pass,
        )
    }

    private fun sensorLiveOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val type = sensorTypeFromLabel(step.label)
            ?: return PlanOpResult(
                listOf(
                    PendingLogEvent(
                        ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                        "plan.unknown.sensor",
                        0L,
                        "host.reported.not.verified",
                    ),
                ),
                false,
            )
        val summary = runSensorLive(
            type,
            step.durationMs.coerceIn(1_000L, 120_000L),
            step.count.coerceIn(1, 4_000),
            step.expectedMin.coerceIn(1, 4_000),
            step.label,
        )
        val pass = summary.structuralPass &&
            summary.sampleCount >= step.expectedMin.coerceIn(1, 4_000)
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.SENSOR_LIVE,
                    "sensor.${step.label}",
                    summary.sampleCount.toLong(),
                    ArKernelPhysicalSelfTestMetrics.axisToken(summary),
                ),
            ),
            pass,
        )
    }

    private fun cameraPreviewOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val targetFrames = step.count.coerceIn(1, 120)
        val timeoutMs = step.durationMs.coerceIn(1_000L, 30_000L)
        val expectedMinFrames = step.expectedMin.coerceIn(1, 120)
        val camera = runCameraTest(targetFrames, timeoutMs, expectedMinFrames)
        val pass = camera.summary.structuralPass && camera.opened &&
            camera.configured && camera.closed
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_LIFECYCLE,
                    "camera.open",
                    passFlag(camera.opened).toLong(),
                    "unverified",
                ),
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_LIFECYCLE,
                    "camera.session",
                    passFlag(camera.configured).toLong(),
                    "unverified",
                ),
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_FRAMES,
                    "preview",
                    camera.summary.frameCount.toLong(),
                    ArKernelPhysicalSelfTestMetrics.captureToken(camera.summary),
                ),
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_LIFECYCLE,
                    "camera.close",
                    passFlag(camera.closed).toLong(),
                    "unverified",
                ),
            ),
            pass,
        )
    }

    private fun cameraRepeatOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val repeats = step.count.coerceIn(1, 8)
        val result = runCameraLifecycleRepeat(
            repeats, step.durationMs.coerceIn(1_000L, 15_000L))
        val pass = result.pass && result.opened >=
            step.expectedMin.coerceIn(1, 8)
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "opened.${result.opened}.err.${result.errors}." +
                "dis.${result.disconnects}.pass.${passFlag(pass)}")
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.CAMERA_REPEAT,
                    "camera.repeat",
                    repeats.toLong(),
                    token,
                ),
            ),
            pass,
        )
    }

    private fun storageOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val bytes = step.count.coerceIn(1_024, 262_144)
        val repeats = step.expectedMin.coerceIn(1, 8)
        val result = runStorageRoundTrip(bytes, repeats)
        val pass = result.pass && result.passedRuns >= repeats
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "runs.${result.runs}.pass.${result.passedRuns}." +
                "crc.${result.firstCrc}.pass.${passFlag(pass)}")
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.STORAGE_REPEAT,
                    "storage.repeat",
                    bytes.toLong(),
                    token,
                ),
            ),
            pass,
        )
    }

    private fun computeOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val iterations = step.count.coerceIn(1, 16)
        val result = runComputeSelfTest(iterations)
        val pass = result.pass && result.passedRuns >=
            step.expectedMin.coerceIn(1, 16)
        val token = ArKernelPhysicalSelfTestMetrics.opaqueToken(
            "pass.${result.passedRuns}.of.${result.iterations}." +
                "pass.${passFlag(pass)}")
        return PlanOpResult(
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.COMPUTE_REPEAT,
                    "compute.repeat",
                    iterations.toLong(),
                    token,
                ),
            ),
            pass,
        )
    }

    private fun thermalOp(step: ArKernelFieldDiagnosticsTestPlanStep):
        PlanOpResult {
        val samples = step.count.coerceIn(2, 30)
        val intervalMs = step.durationMs.coerceIn(500L, 10_000L)
        val (thermal, list) = runThermalSoak(samples, intervalMs)
        val pass = thermal.structuralPass &&
            thermal.sampleCount >= step.expectedMin.coerceIn(2, 30)
        val events = mutableListOf<PendingLogEvent>()
        list.forEachIndexed { index, sample ->
            events += PendingLogEvent(
                ArKernelFieldDiagnosticsLogEventKind.THERMAL_SOAK,
                "thermal.sample.$index",
                sample.status.toLong(),
                ArKernelPhysicalSelfTestMetrics.opaqueToken(
                    "headroom.${sample.headroom}.unverified",
                ),
            )
        }
        events += PendingLogEvent(
            ArKernelFieldDiagnosticsLogEventKind.THERMAL_SOAK,
            "thermal.summary",
            thermal.sampleCount.toLong(),
            ArKernelPhysicalSelfTestMetrics.thermalToken(thermal),
        )
        return PlanOpResult(events, pass)
    }

    private fun sensorCatalog(maxSensors: Int): SensorCatalogResult {
        val manager = activity.getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val sensors = manager?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
        val events = mutableListOf<PendingLogEvent>()
        sensors.take(maxSensors).forEachIndexed { index, sensor ->
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
            "catalog.bounded.$maxSensors",
        )
        return SensorCatalogResult(events, sensors.size)
    }

    private fun cameraCatalog(maxCameras: Int): CameraCatalogResult {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE)
            as? CameraManager
        val ids = try {
            manager?.getCameraIdList()?.toList() ?: emptyList()
        } catch (error: Exception) {
            emptyList()
        }
        val events = mutableListOf<PendingLogEvent>()
        ids.take(maxCameras).forEachIndexed { index, id ->
            try {
                val managerValue = manager!!
                val characteristics = managerValue.getCameraCharacteristics(id)
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
            "catalog.bounded.$maxCameras",
        )
        return CameraCatalogResult(events, ids.size)
    }

    private fun runImuStillness(
        durationMs: Long,
        maxSamples: Int,
        expectedMinSamples: Int,
    ): ImuResult {
        val manager = activity.getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val accSensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (manager == null || accSensor == null || gyroSensor == null) {
            return ImuResult(
                ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                    "acc", emptyList(), expectedMinSamples),
                ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                    "gyro", emptyList(), expectedMinSamples),
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
                if (event.values.isEmpty()) return
                val sample = axisSample(event)
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER ->
                        if (accSamples.size < maxSamples) accSamples.add(sample)
                    Sensor.TYPE_GYROSCOPE ->
                        if (gyroSamples.size < maxSamples) gyroSamples.add(sample)
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
            handler.postDelayed({ done.countDown() }, durationMs)
            done.await(durationMs + 5_000L, TimeUnit.MILLISECONDS)
        } finally {
            manager.unregisterListener(listener)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        val accCopy = synchronized(accSamples) { accSamples.toList() }
        val gyroCopy = synchronized(gyroSamples) { gyroSamples.toList() }
        return ImuResult(
            ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                "acc", accCopy, expectedMinSamples),
            ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                "gyro", gyroCopy, expectedMinSamples),
        )
    }

    private fun runSensorLive(
        sensorType: Int,
        durationMs: Long,
        maxSamples: Int,
        expectedMinSamples: Int,
        label: String,
    ): ArKernelAxisSummary {
        val manager = activity.getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        val sensor = manager?.getDefaultSensor(sensorType)
        if (manager == null || sensor == null) {
            return ArKernelPhysicalSelfTestMetrics.summarizeAxis(
                label, emptyList(), expectedMinSamples)
        }
        val thread = HandlerThread("arkernel-sensor").apply { start() }
        val handler = Handler(thread.looper)
        val samples = Collections.synchronizedList(
            mutableListOf<ArKernelAxisSample>(),
        )
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (samples.size < maxSamples) samples.add(axisSample(event))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            manager.registerListener(
                listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            val done = CountDownLatch(1)
            handler.postDelayed({ done.countDown() }, durationMs)
            done.await(durationMs + 5_000L, TimeUnit.MILLISECONDS)
        } finally {
            manager.unregisterListener(listener)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        val copy = synchronized(samples) { samples.toList() }
        return ArKernelPhysicalSelfTestMetrics.summarizeAxis(
            label, copy, expectedMinSamples)
    }

    private fun axisSample(event: SensorEvent): ArKernelAxisSample {
        val values = event.values
        return ArKernelAxisSample(
            event.timestamp,
            values.getOrElse(0) { 0f },
            values.getOrElse(1) { 0f },
            values.getOrElse(2) { 0f },
        )
    }

    private fun runCameraTest(
        targetFrames: Int,
        timeoutMs: Long,
        expectedMinFrames: Int,
    ): CameraTestResult {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return CameraTestResult(
                failedCapture(expectedMinFrames, 1), false, false, false)
        }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE)
            as? CameraManager
            ?: return CameraTestResult(
                failedCapture(expectedMinFrames, 1), false, false, false)
        val backId = backCameraId(manager)
            ?: return CameraTestResult(
                failedCapture(expectedMinFrames, 1), false, false, false)
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
        } ?: return CameraTestResult(
            failedCapture(expectedMinFrames, 1), false, false, false)

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
                if (timestamps.size >= targetFrames) {
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
        handler.postDelayed({ finish() }, timeoutMs)
        done.await(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
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
            expectedMinFrames,
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

    private fun failedCapture(
        expectedMinFrames: Int,
        errorCount: Int,
    ): ArKernelCaptureSummary =
        ArKernelPhysicalSelfTestMetrics.summarizeCaptures(
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            expectedMinFrames,
            errorCount,
            0,
        )

    private fun runCameraLifecycleRepeat(
        repeats: Int,
        perOpenTimeoutMs: Long,
    ): CameraRepeatResult {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return CameraRepeatResult(0, 1, 0, false)
        }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE)
            as? CameraManager
            ?: return CameraRepeatResult(0, 1, 0, false)
        val backId = backCameraId(manager)
            ?: return CameraRepeatResult(0, 1, 0, false)
        val thread = HandlerThread("arkernel-camera-repeat").apply { start() }
        val handler = Handler(thread.looper)
        val opened = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val disconnects = AtomicInteger(0)
        try {
            repeat(repeats) {
                val latch = CountDownLatch(1)
                val callback = object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opened.incrementAndGet()
                        try {
                            camera.close()
                        } catch (error: Exception) {
                        }
                        latch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        disconnects.incrementAndGet()
                        try {
                            camera.close()
                        } catch (error: Exception) {
                        }
                        latch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        errors.incrementAndGet()
                        try {
                            camera.close()
                        } catch (error: Exception) {
                        }
                        latch.countDown()
                    }
                }
                try {
                    manager.openCamera(backId, callback, handler)
                } catch (error: Exception) {
                    errors.incrementAndGet()
                    latch.countDown()
                }
                if (!latch.await(perOpenTimeoutMs, TimeUnit.MILLISECONDS)) {
                    errors.incrementAndGet()
                }
            }
        } finally {
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        return CameraRepeatResult(
            opened.get(),
            errors.get(),
            disconnects.get(),
            opened.get() >= repeats && errors.get() == 0 &&
                disconnects.get() == 0,
        )
    }

    private fun backCameraId(manager: CameraManager): String? = try {
        manager.getCameraIdList().firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.getCameraIdList().firstOrNull()
    } catch (error: Exception) {
        null
    }

    private fun runStorageRoundTrip(
        bytes: Int,
        repeats: Int,
    ): StorageRepeatResult {
        var passedRuns = 0
        var firstCrc = "00000000"
        repeat(repeats) { index ->
            try {
                val payload = ByteArray(bytes) { offset ->
                    ((offset * 31 + 17 + index) and 0xFF).toByte()
                }
                val file = File(activity.filesDir, "arkernel_plan_selftest.bin")
                file.writeBytes(payload)
                val readBack = file.readBytes()
                file.delete()
                val summary = ArKernelPhysicalSelfTestMetrics.storageRoundTrip(
                    payload, readBack)
                if (index == 0) firstCrc = summary.crc32Hex
                if (summary.structuralPass) passedRuns++
            } catch (error: Exception) {
            }
        }
        return StorageRepeatResult(
            repeats, passedRuns, firstCrc, passedRuns == repeats)
    }

    private fun runComputeSelfTest(iterations: Int): ComputeRepeatResult {
        var passedRuns = 0
        repeat(iterations) {
            if (ArKernelPhysicalSelfTestMetrics.computeSelfTest()
                    .structuralPass
            ) {
                passedRuns++
            }
        }
        return ComputeRepeatResult(
            iterations, passedRuns, passedRuns == iterations)
    }

    @SuppressLint("NewApi")
    private fun runThermalSoak(
        samples: Int,
        intervalMs: Long,
    ): Pair<ArKernelThermalSummary, List<ArKernelThermalSample>> {
        val list = mutableListOf<ArKernelThermalSample>()
        val manager = activity.getSystemService(Context.POWER_SERVICE)
            as? PowerManager
        repeat(samples) { index ->
            val status = try {
                if (manager != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ) {
                    manager.getCurrentThermalStatus()
                } else {
                    0
                }
            } catch (error: Exception) {
                0
            }
            val headroom = try {
                if (manager != null &&
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
            } catch (error: Exception) {
                0f
            }
            list += ArKernelThermalSample(
                SystemClock.elapsedRealtimeNanos(), status, headroom)
            var sink = 0L
            for (i in 0 until 200_000) sink += (i % 7).toLong()
            if (sink == Long.MIN_VALUE) list.clear()
            if (index < samples - 1) {
                try {
                    Thread.sleep(intervalMs)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return ArKernelPhysicalSelfTestMetrics.summarizeThermal(list) to list
    }

    private fun sensorTypeFromLabel(label: String): Int? =
        when (label.lowercase(Locale.ROOT)) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "magnetometer" -> Sensor.TYPE_MAGNETIC_FIELD
            "light" -> Sensor.TYPE_LIGHT
            "pressure" -> Sensor.TYPE_PRESSURE
            "proximity" -> Sensor.TYPE_PROXIMITY
            "gravity" -> Sensor.TYPE_GRAVITY
            "linear.acceleration" -> Sensor.TYPE_LINEAR_ACCELERATION
            "rotation.vector" -> Sensor.TYPE_ROTATION_VECTOR
            else -> null
        }

    private fun passFlag(value: Boolean): Int = if (value) 1 else 0
}
