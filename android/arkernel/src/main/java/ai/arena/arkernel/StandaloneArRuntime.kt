package ai.arena.arkernel

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

enum class ArRuntimeBackend {
    STANDALONE_ARKERNEL,
    STANDALONE_CAMERA_CAPTURE,
    EXTERNAL_ARCORE,
}

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

data class CameraOnlyArRuntimeConfig(
    val cameraId: String? = null,
    val calibration: CameraCalibration,
) {
    init {
        require(calibration.width > 0 && calibration.height > 0 &&
            calibration.fx > 0.0 && calibration.fy > 0.0)
        require(listOf(calibration.fx, calibration.fy, calibration.cx, calibration.cy,
            calibration.k1, calibration.k2, calibration.p1, calibration.p2,
            calibration.k3).all { it.isFinite() })
        require(calibration.cx in 0.0..calibration.width.toDouble() &&
            calibration.cy in 0.0..calibration.height.toDouble())
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

    /** Camera-only support deliberately performs no SensorManager access. */
    fun inspectCapture(context: Context, config: StandaloneArRuntimeConfig): StandaloneSupportReport =
        inspectCamera(context, config.cameraId, config.calibration)

    fun inspectCapture(context: Context, config: CameraOnlyArRuntimeConfig): StandaloneSupportReport =
        inspectCamera(context, config.cameraId, config.calibration)

    fun inspect(context: Context, config: StandaloneArRuntimeConfig): StandaloneSupportReport {
        val camera = inspectCamera(context, config.cameraId, config.calibration)
        val reasons = camera.blockingReasons.toMutableList()
        val warnings = camera.warnings.toMutableList()
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null)
            reasons += "No accelerometer"
        if (sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null)
            reasons += "No gyroscope"
        warnings += "Camera-to-IMU extrinsics cannot be inferred reliably from ARCore availability and must be device-calibrated"
        return camera.copy(supported = reasons.isEmpty(), blockingReasons = reasons,
            warnings = warnings)
    }

    private fun inspectCamera(
        context: Context,
        requestedCameraId: String?,
        calibration: CameraCalibration,
    ): StandaloneSupportReport {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val cameras = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return StandaloneSupportReport(false, null, listOf("No Camera2 service"), warnings)
        val cameraIds = try {
            cameras.cameraIdList.toList()
        } catch (error: Exception) {
            return StandaloneSupportReport(false, null,
                listOf("Camera2 enumeration failed: ${error.javaClass.simpleName}"), warnings)
        }
        val candidates = if (requestedCameraId != null) listOf(requestedCameraId) else cameraIds
        var selected: String? = null
        for (cameraId in candidates) {
            val characteristics = try {
                cameras.getCameraCharacteristics(cameraId)
            } catch (_: Exception) {
                continue
            }
            if (requestedCameraId == null &&
                characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK) continue
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            if (sizes.none { it.width == calibration.width &&
                    it.height == calibration.height }) continue
            selected = cameraId
            val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY)
                warnings += "Selected Camera2 device reports LEGACY hardware level"
            if (characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) == null)
                warnings += "Camera2 does not publish intrinsic calibration; supplied calibration must be independently verified"
            break
        }
        if (selected == null) reasons += if (requestedCameraId == null)
            "No back Camera2 YUV stream matches the configured calibration size" else
            "Configured Camera2 ID does not provide the calibrated YUV stream size"
        return StandaloneSupportReport(reasons.isEmpty(), selected, reasons, warnings)
    }
}

private fun mappingCapabilities(decision: RuntimeResourceDecision?) = ArRuntimeCapabilities(
    sixDofTracking = true,
    planeDetection = true,
    anchors = true,
    mapPersistence = true,
    acceptsMetricDepth = decision?.denseDepthEnabled ?: true,
    acceptsSemanticInference = decision?.semanticInferenceEnabled ?: true,
    requiresLidar = false,
)

private val captureOnlyCapabilities = ArRuntimeCapabilities(
    sixDofTracking = false,
    planeDetection = false,
    anchors = false,
    mapPersistence = false,
    acceptsMetricDepth = false,
    acceptsSemanticInference = false,
    requiresLidar = false,
)

/** Fully standalone Camera2 + SensorManager + native VIO/mapping runtime. */
class StandaloneArRuntime private constructor(
    val session: ArKernelSession,
    private val cameraId: String,
    val supportReport: StandaloneSupportReport,
    override val capabilities: ArRuntimeCapabilities,
) : ArRuntime {
    override val backend = ArRuntimeBackend.STANDALONE_ARKERNEL
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
            resourceDecision: RuntimeResourceDecision? = null,
            calibrationRuntimeMonitor: CameraCalibrationRuntimeMonitor? = null,
        ): StandaloneArRuntime {
            require(resourceDecision == null || resourceDecision.liveMappingEnabled) {
                "A non-mapping resource decision cannot create the VIO runtime"
            }
            val pixels = Math.multiplyExact(config.calibration.width, config.calibration.height)
            require(resourceDecision == null ||
                pixels <= resourceDecision.maximumCalibratedStreamPixels) {
                "Calibrated stream exceeds the selected runtime resource budget"
            }
            val boundCameraId = calibrationRuntimeMonitor?.binding?.cameraId
            require(boundCameraId == null || config.cameraId == null ||
                config.cameraId == boundCameraId) {
                "Configured camera ID does not match calibration monitor binding"
            }
            val effectiveConfig = if (boundCameraId == null) config
                else config.copy(cameraId = boundCameraId)
            val report = StandaloneArSupportProbe.inspect(context, effectiveConfig)
            require(report.supported) {
                "Standalone AR unsupported: ${report.blockingReasons.joinToString()}"
            }
            val session = ArKernelSession(context, effectiveConfig.calibration,
                config.bodyFromCameraRotation, onResult, onError,
                resourceDecision?.maximumQueuedImuSamples ?: config.maxQueuedImuSamples,
                config.bodyFromCameraTranslationMeters,
                compatibilityProbe = compatibilityProbe,
                calibrationRuntimeMonitor = calibrationRuntimeMonitor,
                maximumCameraFramesPerSecond = resourceDecision?.targetFramesPerSecond ?: 30,
                maximumPendingReconstructionFrames =
                    resourceDecision?.maximumPendingReconstructionFrames ?: 2)
            return StandaloneArRuntime(session, requireNotNull(report.selectedCameraId), report,
                mappingCapabilities(resourceDecision))
        }
    }
}

/** Camera2-only fallback. It creates neither ArKernelSession/VIO nor an IMU source. */
class CameraOnlyArRuntime private constructor(
    val session: CameraOnlyCaptureSession,
    private val cameraId: String,
    val supportReport: StandaloneSupportReport,
) : ArRuntime {
    override val backend = ArRuntimeBackend.STANDALONE_CAMERA_CAPTURE
    override val capabilities = captureOnlyCapabilities
    private var closed = false

    @Synchronized override fun start() {
        check(!closed) { "Camera-only runtime is closed" }
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
            config: CameraOnlyArRuntimeConfig,
            onFrame: (CameraOnlyCaptureFrame) -> Unit,
            onError: (Throwable) -> Unit = {},
            resourceDecision: RuntimeResourceDecision? = null,
            calibrationRuntimeMonitor: CameraCalibrationRuntimeMonitor? = null,
        ): CameraOnlyArRuntime {
            require(resourceDecision == null ||
                resourceDecision.tier == RuntimeResourceTier.CAPTURE_ONLY) {
                "Camera-only runtime requires a capture-only resource decision"
            }
            val boundCameraId = calibrationRuntimeMonitor?.binding?.cameraId
            require(boundCameraId == null || config.cameraId == null ||
                config.cameraId == boundCameraId) {
                "Configured camera ID does not match calibration monitor binding"
            }
            val effectiveConfig = if (boundCameraId == null) config
                else config.copy(cameraId = boundCameraId)
            val report = StandaloneArSupportProbe.inspectCapture(context, effectiveConfig)
            require(report.supported) {
                "Camera-only capture unsupported: ${report.blockingReasons.joinToString()}"
            }
            val session = CameraOnlyCaptureSession(context, effectiveConfig.calibration, onFrame, onError,
                maximumFramesPerSecond = resourceDecision?.targetFramesPerSecond ?: 10,
                maximumPendingFrames =
                    resourceDecision?.maximumPendingReconstructionFrames ?: 1,
                calibrationRuntimeMonitor = calibrationRuntimeMonitor)
            return CameraOnlyArRuntime(session, requireNotNull(report.selectedCameraId), report)
        }
    }
}

private fun StandaloneArRuntimeConfig.captureOnly() = CameraOnlyArRuntimeConfig(
    cameraId = cameraId,
    calibration = calibration,
)

data class ArRuntimeSelection(
    val runtime: ArRuntime,
    val usedFallback: Boolean,
    val diagnostics: List<String>,
)

object ArRuntimeSelector {
    /** Creates a fresh owner from one atomic verified profile/resource selection. */
    fun selectCalibrated(
        context: Context,
        selection: CalibratedStreamSelection,
        previousRuntimeStopped: Boolean,
        onStandaloneResult: (ArKernelSessionResult) -> Unit,
        onCameraOnlyFrame: (CameraOnlyCaptureFrame) -> Unit = {},
        onError: (Throwable) -> Unit = {},
        activeCompatibilityProbe: ActiveDeviceCompatibilityProbe? = null,
    ): ArRuntimeSelection {
        val selected = requireNotNull(selection.selected) {
            "A verified calibrated stream must be selected before runtime creation"
        }
        require(selection.transition != CalibratedStreamTransition.KEEP_CURRENT_SESSION) {
            "The current calibrated session must be retained rather than recreated"
        }
        require(selection.transition != CalibratedStreamTransition.RESTART_REQUIRED ||
            previousRuntimeStopped) {
            "The previous runtime must be stopped before changing calibrated streams"
        }
        require(selection.transition == CalibratedStreamTransition.START_NEW_SESSION ||
            selection.transition == CalibratedStreamTransition.RESTART_REQUIRED)
        return select(
            context = context,
            standaloneConfig = selected.standaloneConfig(),
            onStandaloneResult = onStandaloneResult,
            onStandaloneError = onError,
            optionalExternalFactory = null,
            preferExternal = false,
            activeCompatibilityProbe = activeCompatibilityProbe,
            resourceDecision = selected.resourceDecision,
            onCameraOnlyFrame = onCameraOnlyFrame,
            calibrationRuntimeMonitor = selected.newRuntimeMonitor(),
        )
    }

    fun select(
        context: Context,
        standaloneConfig: StandaloneArRuntimeConfig,
        onStandaloneResult: (ArKernelSessionResult) -> Unit,
        onStandaloneError: (Throwable) -> Unit = {},
        optionalExternalFactory: OptionalExternalArRuntimeFactory? = null,
        preferExternal: Boolean = true,
        activeCompatibilityProbe: ActiveDeviceCompatibilityProbe? = null,
        resourceDecision: RuntimeResourceDecision? = null,
        onCameraOnlyFrame: (CameraOnlyCaptureFrame) -> Unit = {},
        calibrationRuntimeMonitor: CameraCalibrationRuntimeMonitor? = null,
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
        require(resourceDecision?.tier != RuntimeResourceTier.UNSUPPORTED) {
            "Runtime resource policy rejected camera execution"
        }
        if (resourceDecision?.tier == RuntimeResourceTier.CAPTURE_ONLY) {
            val capture = CameraOnlyArRuntime.create(context, standaloneConfig.captureOnly(),
                onCameraOnlyFrame, onStandaloneError, resourceDecision,
                calibrationRuntimeMonitor)
            diagnostics += capture.supportReport.warnings
            diagnostics += "Selected calibrated camera-only capture runtime by resource policy"
            return ArRuntimeSelection(capture, true, diagnostics)
        }
        val mappingReport = StandaloneArSupportProbe.inspect(context, standaloneConfig)
        if (mappingReport.supported) {
            val standalone = StandaloneArRuntime.create(context, standaloneConfig,
                onStandaloneResult, onStandaloneError, activeCompatibilityProbe,
                resourceDecision, calibrationRuntimeMonitor)
            diagnostics += standalone.supportReport.warnings
            diagnostics += "Selected standalone Camera2 + SensorManager + ARKernel runtime"
            return ArRuntimeSelection(standalone, true, diagnostics)
        }
        val captureReport = StandaloneArSupportProbe.inspectCapture(context, standaloneConfig)
        require(captureReport.supported) {
            "No standalone runtime available: ${(mappingReport.blockingReasons + captureReport.blockingReasons).distinct().joinToString()}"
        }
        val capture = CameraOnlyArRuntime.create(context, standaloneConfig.captureOnly(),
            onCameraOnlyFrame, onStandaloneError,
            calibrationRuntimeMonitor = calibrationRuntimeMonitor)
        diagnostics += mappingReport.blockingReasons
        diagnostics += capture.supportReport.warnings
        diagnostics += "Selected calibrated camera-only capture runtime because VIO prerequisites are unavailable"
        return ArRuntimeSelection(capture, true, diagnostics)
    }
}
