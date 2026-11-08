package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatibilityTest {
    @Test
    fun stableSignatureIgnoresSetOrderingButChangesWithCameraBehavior() {
        val first = signature(camera(physicalIds = listOf("2", "0")),
            abis = listOf("x86_64", "arm64-v8a"))
        val reordered = signature(camera(physicalIds = listOf("0", "2")),
            abis = listOf("arm64-v8a", "x86_64"))
        val changed = signature(camera(physicalIds = listOf("0", "2"),
            timestampSource = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN))

        assertEquals(first.stableIdSha256, reordered.stableIdSha256)
        assertNotEquals(first.stableIdSha256, changed.stableIdSha256)
        assertEquals(64, first.stableIdSha256.length)
    }

    @Test
    fun staticMetadataCanOnlyProduceDegradedProvisionalTier() {
        val report = DeviceCompatibilityEvaluator.evaluate(signature(camera()))

        assertEquals(DeviceCompatibilityTier.DEGRADED_TRACKING, report.provisionalTier)
        assertEquals(DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC,
            report.highestEligibleTierAfterActiveTests)
        assertTrue(report.standaloneVioCandidate)
        assertTrue(CompatibilityReasonCode.ACTIVE_CAMERA_IMU_TEST_REQUIRED in report.warnings)
        assertTrue(CompatibilityReasonCode.CAMERA_IMU_EXTRINSICS_UNVERIFIED in report.warnings)
    }

    @Test
    fun missingGyroscopePreservesCaptureOnlyFallback() {
        val report = DeviceCompatibilityEvaluator.evaluate(signature(camera(),
            imu = imu(hasGyroscope = false)))

        assertEquals(DeviceCompatibilityTier.CAPTURE_ONLY, report.provisionalTier)
        assertEquals(DeviceCompatibilityTier.CAPTURE_ONLY,
            report.highestEligibleTierAfterActiveTests)
        assertTrue(CompatibilityReasonCode.NO_GYROSCOPE in report.blockingReasons)
        assertFalse(report.standaloneVioCandidate)
    }

    @Test
    fun noMatchingCameraIsUnsupportedWithStableReason() {
        val report = DeviceCompatibilityEvaluator.evaluate(signature(camera = null))

        assertEquals(DeviceCompatibilityTier.UNSUPPORTED, report.provisionalTier)
        assertTrue(CompatibilityReasonCode.NO_MATCHING_BACK_YUV_STREAM in
            report.blockingReasons)
    }

    @Test
    fun incompleteCameraMetadataProducesMachineReadableWarnings() {
        val report = DeviceCompatibilityEvaluator.evaluate(signature(camera(
            physicalIds = listOf("0", "1"),
            timestampSource = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
            publishesIntrinsics = false,
            publishesDistortion = false,
            publishesRollingShutter = false)))

        assertTrue(CompatibilityReasonCode.LOGICAL_MULTI_CAMERA_REQUIRES_PHYSICAL_PINNING in
            report.warnings)
        assertTrue(CompatibilityReasonCode.CAMERA_TIMESTAMP_SOURCE_UNKNOWN in report.warnings)
        assertTrue(CompatibilityReasonCode.CAMERA_INTRINSICS_MISSING in report.warnings)
        assertTrue(CompatibilityReasonCode.CAMERA_DISTORTION_METADATA_MISSING in report.warnings)
        assertTrue(CompatibilityReasonCode.CAMERA_ROLLING_SHUTTER_METADATA_MISSING in
            report.warnings)
    }

    private fun signature(camera: CameraCapabilityFacts?,
        abis: List<String> = listOf("arm64-v8a"),
        imu: ImuCapabilityFacts = imu()): DeviceCapabilitySignature =
        DeviceCapabilitySignature(apiLevel = 35, supportedAbis = abis,
            lowRamDevice = false, memoryClassMb = 256,
            buildFingerprintSha256 = "a".repeat(64), camera = camera, imu = imu)

    private fun imu(hasGyroscope: Boolean = true) = ImuCapabilityFacts(
        hasAccelerometer = true, hasGyroscope = hasGyroscope,
        accelerometerMinDelayUs = 5_000, gyroscopeMinDelayUs = 5_000,
        accelerometerReportingMode = 0, gyroscopeReportingMode = 0,
        accelerometerResolution = 0.001f, gyroscopeResolution = 0.0001f)

    private fun camera(
        physicalIds: List<String> = emptyList(),
        timestampSource: Int = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        publishesIntrinsics: Boolean = true,
        publishesDistortion: Boolean = true,
        publishesRollingShutter: Boolean = true,
    ) = CameraCapabilityFacts(cameraId = "0", physicalCameraIds = physicalIds,
        width = 640, height = 480,
        hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
        timestampSource = timestampSource, sensorOrientationDegrees = 90,
        activeArrayWidth = 4000, activeArrayHeight = 3000,
        preCorrectionArrayWidth = 4200, preCorrectionArrayHeight = 3200,
        minimumFrameDurationNs = 33_333_333,
        focalLengths = listOf(4.2f), videoStabilizationModes = listOf(0),
        opticalStabilizationModes = listOf(0), distortionCorrectionModes = listOf(0),
        publishesIntrinsics = publishesIntrinsics,
        publishesDistortion = publishesDistortion,
        publishesRollingShutterSkew = publishesRollingShutter)
}
