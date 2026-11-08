package ai.arena.arkernel

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

enum class ArRuntimeBackend { STANDALONE_ARKERNEL, EXTERNAL_ARCORE }

data class ArRuntimeCapabilities(
    val sixDofTracking: Boolean,
    val planeDetection: Boolean,
    val anchors: Boolean,
    val mapPersistence: Boolean,
    val acceptsMetricDepth: Boolean,
    val acceptsSemanticInference: Boolean,
    val requiresLidar: Boolean,
)

interface ArRuntime : AutoCloseable {
    val backend: ArRuntimeBackend
    val capabilities: ArRuntimeCapabilities
    fun start()
    fun stop()
}

/** Optional adapter boundary; the core AAR never links against or requires ARCore. */
interface OptionalExternalArRuntimeFactory {
    val backendName: String
    fun isSupported(context: Context): Boolean
    fun create(context: Context): ArRuntime
}

data class StandaloneArRuntimeConfig(
    val cameraId: String? = null,
    val calibration: CameraCalibration,
    val bodyFromCameraRotation: Rotation,
    val bodyFromCameraTranslationMeters: Vector3 = Vector3(0.0, 0.0, 0.0),
    val maxQueuedImuSamples: Int = 4096,
) {
    init {
        require(calibration.width > 0 && calibration.height > 0 &&
            calibration.fx > 0.0 && calibration.fy > 0.0)
        require(listOf(calibration.fx, calibration.fy, calibration.cx, calibration.cy,
            calibration.k1, calibration.k2, calibration.p1, calibration.p2,
            calibration.k3).all { it.isFinite() })
        require(calibration.cx in 0.0..calibration.width.toDouble() &&
            calibration.cy in 0.0..calibration.height.toDouble())
        require(listOf(bodyFromCameraTranslationMeters.x, bodyFromCameraTranslationMeters.y,
            bodyFromCameraTranslationMeters.z).all { it.isFinite() })
        val q = bodyFromCameraRotation
        val norm = kotlin.math.sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
        require(norm.isFinite() && norm > 1e-9)
        require(maxQueuedImuSamples >= 128)
    }
}

data class StandaloneSupportReport(
    val supported: Boolean,
    val selectedCameraId: String?,
    val blockingReasons: List<String>,
    val warnings: List<String>,
)

object StandaloneArSupportProbe {
    /** Versioned capability report; static probing never self-awards metric admission. */
    fun inspectCompatibility(context: Context,
        config: StandaloneArRuntimeConfig): DeviceCompatibilityReport =
        AndroidDeviceCompatibilityProbe.inspect(context, config.cameraId,
            config.calibration.width, config.calibration.height)

    fun inspect(context: Context, config: StandaloneArRuntimeConfig): StandaloneSupportReport {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null)
            reasons += "No accelerometer"
        if (sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null)
            reasons += "No gyroscope"
        val cameras = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameras == null)
            return StandaloneSupportReport(false, null, reasons + "No Camera2 service", warnings)
        val cameraIds = try {
            cameras.cameraIdList.toList()
        } catch (error: Exception) {
            return StandaloneSupportReport(false, null,
                reasons + "Camera2 enumeration failed: ${error.javaClass.simpleName}", warnings)
        }
        val candidates = if (config.cameraId != null) listOf(config.cameraId) else cameraIds
        var selected: String? = null
        for (cameraId in candidates) {
            val characteristics = try {
                cameras.getCameraCharacteristics(cameraId)
            } catch (_: Exception) {
                continue
            }
            if (config.cameraId == null &&
                characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK) continue
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            if (sizes.none { it.width == config.calibration.width &&
                    it.height == config.calibration.height }) continue
            selected = cameraId
            val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY)
                warnings += "Selected Camera2 device reports LEGACY hardware level"
            if (characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) == null)
                warnings += "Camera2 does not publish intrinsic calibration; supplied calibration must be independently verified"
            break
        }
        if (selected == null) reasons += if (config.cameraId == null)
            "No back Camera2 YUV stream matches the configured calibration size" else
            "Configured Camera2 ID does not provide the calibrated YUV stream size"
        warnings += "Camera-to-IMU extrinsics cannot be inferred reliably from ARCore availability and must be device-calibrated"
        return StandaloneSupportReport(reasons.isEmpty(), selected, reasons, warnings)
    }
}

/** Fully standalone Camera2 + SensorManager + native VIO/mapping runtime. */
class StandaloneArRuntime private constructor(
    val session: ArKernelSession,
    private val cameraId: String,
    val supportReport: StandaloneSupportReport,
) : ArRuntime {
    override val backend = ArRuntimeBackend.STANDALONE_ARKERNEL
    override val capabilities = ArRuntimeCapabilities(true, true, true, true,
        acceptsMetricDepth = true, acceptsSemanticInference = true, requiresLidar = false)
    private var closed = false

    @Synchronized override fun start() {
        check(!closed) { "Standalone AR runtime is closed" }
        session.start(cameraId)
    }
    @Synchronized override fun stop() { if (!closed) session.stop() }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    companion object {
        fun create(
            context: Context,
            config: StandaloneArRuntimeConfig,
            onResult: (ArKernelSessionResult) -> Unit,
            onError: (Throwable) -> Unit = {},
            compatibilityProbe: ActiveDeviceCompatibilityProbe? = null,
        ): StandaloneArRuntime {
            val report = StandaloneArSupportProbe.inspect(context, config)
            require(report.supported) {
                "Standalone AR unsupported: ${report.blockingReasons.joinToString()}"
            }
            val session = ArKernelSession(context, config.calibration,
                config.bodyFromCameraRotation, onResult, onError,
                config.maxQueuedImuSamples, config.bodyFromCameraTranslationMeters,
                compatibilityProbe = compatibilityProbe)
            return StandaloneArRuntime(session, requireNotNull(report.selectedCameraId), report)
        }
    }
}

data class ArRuntimeSelection(
    val runtime: ArRuntime,
    val usedFallback: Boolean,
    val diagnostics: List<String>,
)

object ArRuntimeSelector {
    fun select(
        context: Context,
        standaloneConfig: StandaloneArRuntimeConfig,
        onStandaloneResult: (ArKernelSessionResult) -> Unit,
        onStandaloneError: (Throwable) -> Unit = {},
        optionalExternalFactory: OptionalExternalArRuntimeFactory? = null,
        preferExternal: Boolean = true,
        activeCompatibilityProbe: ActiveDeviceCompatibilityProbe? = null,
    ): ArRuntimeSelection {
        val diagnostics = mutableListOf<String>()
        if (preferExternal && optionalExternalFactory != null) {
            try {
                if (optionalExternalFactory.isSupported(context)) {
                    return ArRuntimeSelection(optionalExternalFactory.create(context), false,
                        listOf("Selected optional ${optionalExternalFactory.backendName} runtime"))
                }
                diagnostics += "Optional ${optionalExternalFactory.backendName} runtime is unsupported"
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                diagnostics += "Optional ${optionalExternalFactory.backendName} runtime failed: ${error.javaClass.simpleName}"
            }
        } else {
            diagnostics += if (optionalExternalFactory == null)
                "No optional ARCore adapter is linked" else "External runtime preference is disabled"
        }
        val standalone = StandaloneArRuntime.create(context, standaloneConfig,
            onStandaloneResult, onStandaloneError, activeCompatibilityProbe)
        diagnostics += standalone.supportReport.warnings
        diagnostics += "Selected standalone Camera2 + SensorManager + ARKernel runtime"
        return ArRuntimeSelection(standalone, true, diagnostics)
    }
}
