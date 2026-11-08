package ai.arena.arkernel

import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import java.security.MessageDigest

const val DEVICE_CAPABILITY_SCHEMA_VERSION = 1

enum class DeviceCompatibilityTier {
    VALIDATED_METRIC,
    RUNTIME_VERIFIED_METRIC,
    DEGRADED_TRACKING,
    CAPTURE_ONLY,
    UNSUPPORTED,
}

enum class CompatibilityReasonCode {
    NO_CAMERA_SERVICE,
    CAMERA_ENUMERATION_FAILED,
    NO_MATCHING_BACK_YUV_STREAM,
    NO_ACCELEROMETER,
    NO_GYROSCOPE,
    LEGACY_CAMERA_HARDWARE,
    CAMERA_TIMESTAMP_SOURCE_UNKNOWN,
    CAMERA_INTRINSICS_MISSING,
    CAMERA_DISTORTION_METADATA_MISSING,
    CAMERA_ROLLING_SHUTTER_METADATA_MISSING,
    LOGICAL_MULTI_CAMERA_REQUIRES_PHYSICAL_PINNING,
    CAMERA_IMU_EXTRINSICS_UNVERIFIED,
    ACTIVE_CAMERA_IMU_TEST_REQUIRED,
}

class CameraCapabilityFacts(
    val cameraId: String,
    physicalCameraIds: List<String>,
    val width: Int,
    val height: Int,
    val hardwareLevel: Int,
    val timestampSource: Int,
    val sensorOrientationDegrees: Int,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val preCorrectionArrayWidth: Int,
    val preCorrectionArrayHeight: Int,
    val minimumFrameDurationNs: Long,
    focalLengths: List<Float>,
    videoStabilizationModes: List<Int>,
    opticalStabilizationModes: List<Int>,
    distortionCorrectionModes: List<Int>,
    val publishesIntrinsics: Boolean,
    val publishesDistortion: Boolean,
    val publishesRollingShutterSkew: Boolean,
) {
    val physicalCameraIds: List<String> = physicalCameraIds.toList()
    val focalLengths: List<Float> = focalLengths.toList()
    val videoStabilizationModes: List<Int> = videoStabilizationModes.toList()
    val opticalStabilizationModes: List<Int> = opticalStabilizationModes.toList()
    val distortionCorrectionModes: List<Int> = distortionCorrectionModes.toList()

    init {
        require(cameraId.isNotEmpty() && cameraId.length <= 256)
        require(physicalCameraIds.size <= 16 &&
            physicalCameraIds.all { it.isNotEmpty() && it.length <= 256 })
        require(width > 0 && height > 0)
        require(activeArrayWidth >= 0 && activeArrayHeight >= 0)
        require(preCorrectionArrayWidth >= 0 && preCorrectionArrayHeight >= 0)
        require(minimumFrameDurationNs >= 0)
        require(focalLengths.size <= 32 && focalLengths.all { it.isFinite() && it > 0f })
        require(videoStabilizationModes.size <= 32 && opticalStabilizationModes.size <= 32 &&
            distortionCorrectionModes.size <= 32)
    }
}

data class ImuCapabilityFacts(
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val accelerometerMinDelayUs: Int,
    val gyroscopeMinDelayUs: Int,
    val accelerometerReportingMode: Int,
    val gyroscopeReportingMode: Int,
    val accelerometerResolution: Float,
    val gyroscopeResolution: Float,
) {
    init {
        require(accelerometerMinDelayUs >= -1 && gyroscopeMinDelayUs >= -1)
        require(accelerometerResolution.isFinite() && accelerometerResolution >= 0f)
        require(gyroscopeResolution.isFinite() && gyroscopeResolution >= 0f)
    }
}

class DeviceCapabilitySignature(
    val schemaVersion: Int = DEVICE_CAPABILITY_SCHEMA_VERSION,
    val apiLevel: Int,
    supportedAbis: List<String>,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val buildFingerprintSha256: String,
    val camera: CameraCapabilityFacts?,
    val imu: ImuCapabilityFacts,
) {
    val supportedAbis: List<String> = supportedAbis.toList()

    init {
        require(schemaVersion == DEVICE_CAPABILITY_SCHEMA_VERSION)
        require(apiLevel >= 1)
        require(supportedAbis.isNotEmpty() && supportedAbis.size <= 16 &&
            supportedAbis.all { it.isNotBlank() && it.length <= 64 })
        require(memoryClassMb > 0)
        require(buildFingerprintSha256.matches(Regex("[0-9a-f]{64}")))
    }

    /** Stable identifier contains no raw build fingerprint or sensor samples. */
    val stableIdSha256: String by lazy { sha256Hex(canonicalString()) }

    private fun canonicalString(): String {
        val cameraValue = camera
        val values = mutableListOf(
            schemaVersion.toString(), apiLevel.toString(),
            supportedAbis.sorted().joinToString(","), lowRamDevice.toString(),
            memoryClassMb.toString(), buildFingerprintSha256,
            imu.hasAccelerometer.toString(), imu.hasGyroscope.toString(),
            imu.accelerometerMinDelayUs.toString(), imu.gyroscopeMinDelayUs.toString(),
            imu.accelerometerReportingMode.toString(), imu.gyroscopeReportingMode.toString(),
            imu.accelerometerResolution.toRawBits().toString(),
            imu.gyroscopeResolution.toRawBits().toString(),
        )
        if (cameraValue == null) values += "no-camera" else values += listOf(
            cameraValue.cameraId,
            cameraValue.physicalCameraIds.sorted().joinToString(","),
            cameraValue.width.toString(), cameraValue.height.toString(),
            cameraValue.hardwareLevel.toString(), cameraValue.timestampSource.toString(),
            cameraValue.sensorOrientationDegrees.toString(),
            cameraValue.activeArrayWidth.toString(), cameraValue.activeArrayHeight.toString(),
            cameraValue.preCorrectionArrayWidth.toString(),
            cameraValue.preCorrectionArrayHeight.toString(),
            cameraValue.minimumFrameDurationNs.toString(),
            cameraValue.focalLengths.sorted().joinToString(",") { it.toRawBits().toString() },
            cameraValue.videoStabilizationModes.sorted().joinToString(","),
            cameraValue.opticalStabilizationModes.sorted().joinToString(","),
            cameraValue.distortionCorrectionModes.sorted().joinToString(","),
            cameraValue.publishesIntrinsics.toString(),
            cameraValue.publishesDistortion.toString(),
            cameraValue.publishesRollingShutterSkew.toString(),
        )
        return values.joinToString("|") { value -> "${value.length}:$value" }
    }
}

data class DeviceCompatibilityReport(
    val signature: DeviceCapabilitySignature,
    val provisionalTier: DeviceCompatibilityTier,
    val highestEligibleTierAfterActiveTests: DeviceCompatibilityTier,
    val blockingReasons: List<CompatibilityReasonCode>,
    val warnings: List<CompatibilityReasonCode>,
) {
    init {
        require(blockingReasons.distinct().size == blockingReasons.size)
        require(warnings.distinct().size == warnings.size)
        require(blockingReasons.none { it in warnings })
        if (provisionalTier == DeviceCompatibilityTier.UNSUPPORTED)
            require(blockingReasons.isNotEmpty())
    }

    val standaloneVioCandidate: Boolean
        get() = highestEligibleTierAfterActiveTests <=
            DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC && blockingReasons.isEmpty()
}

object DeviceCompatibilityEvaluator {
    fun evaluate(signature: DeviceCapabilitySignature): DeviceCompatibilityReport {
        val blocking = mutableListOf<CompatibilityReasonCode>()
        val warnings = mutableListOf<CompatibilityReasonCode>()
        val camera = signature.camera
        if (camera == null) blocking += CompatibilityReasonCode.NO_MATCHING_BACK_YUV_STREAM
        if (!signature.imu.hasAccelerometer)
            blocking += CompatibilityReasonCode.NO_ACCELEROMETER
        if (!signature.imu.hasGyroscope)
            blocking += CompatibilityReasonCode.NO_GYROSCOPE

        if (camera != null) {
            if (camera.hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY)
                warnings += CompatibilityReasonCode.LEGACY_CAMERA_HARDWARE
            if (camera.timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN)
                warnings += CompatibilityReasonCode.CAMERA_TIMESTAMP_SOURCE_UNKNOWN
            if (!camera.publishesIntrinsics)
                warnings += CompatibilityReasonCode.CAMERA_INTRINSICS_MISSING
            if (!camera.publishesDistortion)
                warnings += CompatibilityReasonCode.CAMERA_DISTORTION_METADATA_MISSING
            if (!camera.publishesRollingShutterSkew)
                warnings += CompatibilityReasonCode.CAMERA_ROLLING_SHUTTER_METADATA_MISSING
            if (camera.physicalCameraIds.size > 1)
                warnings += CompatibilityReasonCode.LOGICAL_MULTI_CAMERA_REQUIRES_PHYSICAL_PINNING
        }
        warnings += CompatibilityReasonCode.CAMERA_IMU_EXTRINSICS_UNVERIFIED
        warnings += CompatibilityReasonCode.ACTIVE_CAMERA_IMU_TEST_REQUIRED

        val tier: DeviceCompatibilityTier
        val highest: DeviceCompatibilityTier
        if (camera == null) {
            tier = DeviceCompatibilityTier.UNSUPPORTED
            highest = DeviceCompatibilityTier.UNSUPPORTED
        } else if (!signature.imu.hasAccelerometer || !signature.imu.hasGyroscope) {
            tier = DeviceCompatibilityTier.CAPTURE_ONLY
            highest = DeviceCompatibilityTier.CAPTURE_ONLY
        } else {
            // Static declarations never self-award metric admission.
            tier = DeviceCompatibilityTier.DEGRADED_TRACKING
            highest = DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC
        }
        return DeviceCompatibilityReport(signature, tier, highest,
            blocking.distinct(), warnings.filterNot { it in blocking }.distinct())
    }
}

object AndroidDeviceCompatibilityProbe {
    fun inspect(context: Context, requestedCameraId: String?, width: Int,
        height: Int): DeviceCompatibilityReport {
        require(width > 0 && height > 0)
        val appContext = context.applicationContext
        val activity = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sensors = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val imu = ImuCapabilityFacts(accelerometer != null, gyroscope != null,
            accelerometer?.minDelay ?: -1, gyroscope?.minDelay ?: -1,
            accelerometer?.reportingMode ?: -1, gyroscope?.reportingMode ?: -1,
            accelerometer?.resolution ?: 0f, gyroscope?.resolution ?: 0f)
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        var cameraProbeFailure: CompatibilityReasonCode? = null
        val camera = if (manager == null) {
            cameraProbeFailure = CompatibilityReasonCode.NO_CAMERA_SERVICE
            null
        } else try {
            findCamera(manager, requestedCameraId, width, height)
        } catch (_: Exception) {
            cameraProbeFailure = CompatibilityReasonCode.CAMERA_ENUMERATION_FAILED
            null
        }
        val signature = DeviceCapabilitySignature(
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            lowRamDevice = activity.isLowRamDevice,
            memoryClassMb = activity.memoryClass,
            buildFingerprintSha256 = sha256Hex(Build.FINGERPRINT),
            camera = camera,
            imu = imu,
        )
        val evaluated = DeviceCompatibilityEvaluator.evaluate(signature)
        if (cameraProbeFailure == null) return evaluated
        return evaluated.copy(blockingReasons =
            (evaluated.blockingReasons - CompatibilityReasonCode.NO_MATCHING_BACK_YUV_STREAM +
                cameraProbeFailure).distinct())
    }

    private fun findCamera(manager: CameraManager, requestedCameraId: String?, width: Int,
        height: Int): CameraCapabilityFacts? {
        val ids = manager.cameraIdList.toList()
        val candidates = requestedCameraId?.let(::listOf) ?: ids
        for (id in candidates) {
            val characteristics = try { manager.getCameraCharacteristics(id) }
            catch (_: Exception) { continue }
            if (requestedCameraId == null &&
                characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK) continue
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: continue
            val size = map.getOutputSizes(ImageFormat.YUV_420_888)?.firstOrNull {
                it.width == width && it.height == height
            } ?: continue
            val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val preCorrection = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            val physicalIds = if (Build.VERSION.SDK_INT >= 28)
                Api28CameraMetadata.physicalCameraIds(characteristics) else emptyList()
            val distortionModes = if (Build.VERSION.SDK_INT >= 28)
                Api28CameraMetadata.distortionModes(characteristics) else emptyList()
            return CameraCapabilityFacts(
                cameraId = id,
                physicalCameraIds = physicalIds,
                width = width,
                height = height,
                hardwareLevel = characteristics.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
                timestampSource = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: -1,
                sensorOrientationDegrees = characteristics.get(
                    CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                activeArrayWidth = active?.width() ?: 0,
                activeArrayHeight = active?.height() ?: 0,
                preCorrectionArrayWidth = preCorrection?.width() ?: 0,
                preCorrectionArrayHeight = preCorrection?.height() ?: 0,
                minimumFrameDurationNs = map.getOutputMinFrameDuration(
                    ImageFormat.YUV_420_888, size).coerceAtLeast(0L),
                focalLengths = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
                videoStabilizationModes = characteristics.get(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.toList().orEmpty(),
                opticalStabilizationModes = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.toList().orEmpty(),
                distortionCorrectionModes = distortionModes,
                publishesIntrinsics = characteristics.get(
                    CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null,
                publishesDistortion = if (Build.VERSION.SDK_INT >= 28)
                    Api28CameraMetadata.publishesDistortion(characteristics)
                else publishesLegacyDistortion(characteristics),
                publishesRollingShutterSkew = characteristics.availableCaptureResultKeys.contains(
                    android.hardware.camera2.CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
            )
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun publishesLegacyDistortion(characteristics: CameraCharacteristics): Boolean =
        characteristics.get(CameraCharacteristics.LENS_RADIAL_DISTORTION) != null

    @TargetApi(28)
    private object Api28CameraMetadata {
        fun physicalCameraIds(characteristics: CameraCharacteristics): List<String> =
            characteristics.physicalCameraIds.sorted()

        fun distortionModes(characteristics: CameraCharacteristics): List<Int> =
            characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?.toList().orEmpty()

        fun publishesDistortion(characteristics: CameraCharacteristics): Boolean =
            characteristics.get(CameraCharacteristics.LENS_DISTORTION) != null
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
