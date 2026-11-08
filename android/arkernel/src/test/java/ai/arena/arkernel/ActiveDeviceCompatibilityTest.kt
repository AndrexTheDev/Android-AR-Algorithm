package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveDeviceCompatibilityTest {
    @Test
    fun promotesOnlyCompleteRealtimeCalibratedCapture() {
        val probe = ActiveDeviceCompatibilityProbe(staticReport(),
            calibrationTrusted = true, cameraImuExtrinsicsTrusted = true)
        feedHealthyCapture(probe)

        val report = probe.finish()
        assertEquals(ObservedCameraTimebase.REALTIME, report.observedCameraTimebase)
        assertEquals(DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC, report.promotedTier)
        assertTrue(report.blockingReasons.isEmpty())
        assertEquals(1.0, report.captureTimestampMatchRatio, 0.0)
        assertTrue(report.camera.medianRateHz > 29.0)
        assertTrue(report.gyroscope.medianRateHz > 99.0)
        assertTrue(report.cameraImuOverlapNs > 0)
        assertTrue(probe.isFinished)
        assertTrue(probe.metricAdmissionGranted)
        assertEquals(report, probe.finish())
        assertEquals(false, probe.addCameraSample(CameraTimingSample(
            CameraTimingSampleKind.IMAGE, 1, 1, 1)))
    }

    @Test
    fun detectsUptimeCameraEvenWhenStreamsOtherwiseHealthy() {
        val probe = ActiveDeviceCompatibilityProbe(staticReport(), true, true)
        val start = 10_000_000_000L
        repeat(30) { index ->
            val timestamp = start + index * 33_333_333L
            val callbackUptime = timestamp + 20_000_000L
            val callbackElapsed = callbackUptime + 5_000_000_000L
            probe.addCameraSample(CameraTimingSample(CameraTimingSampleKind.IMAGE,
                timestamp, callbackElapsed, callbackUptime))
            probe.addCameraSample(CameraTimingSample(CameraTimingSampleKind.CAPTURE_RESULT,
                timestamp, callbackElapsed, callbackUptime))
        }
        repeat(120) { index ->
            val timestamp = start + index * 10_000_000L
            probe.addImuSample(ImuTimingSample(ImuTimingSampleKind.ACCELEROMETER,
                timestamp, timestamp + 5_020_000_000L))
            probe.addImuSample(ImuTimingSample(ImuTimingSampleKind.GYROSCOPE,
                timestamp, timestamp + 5_020_000_000L))
        }

        val report = probe.finish()
        assertEquals(ObservedCameraTimebase.UPTIME, report.observedCameraTimebase)
        assertTrue(ActiveCompatibilityReasonCode.CAMERA_TIMESTAMP_DOMAIN_NOT_REALTIME in
            report.blockingReasons)
        assertEquals(DeviceCompatibilityTier.DEGRADED_TRACKING, report.promotedTier)
    }

    @Test
    fun rejectsCaptureMismatchAndUntrustedCalibration() {
        val probe = ActiveDeviceCompatibilityProbe(staticReport(),
            calibrationTrusted = false, cameraImuExtrinsicsTrusted = false)
        feedHealthyCapture(probe, includeCaptureResults = false)

        val report = probe.finish()
        assertTrue(ActiveCompatibilityReasonCode.CAMERA_CAPTURE_TIMESTAMP_MISMATCH in
            report.blockingReasons)
        assertTrue(ActiveCompatibilityReasonCode.CAMERA_CALIBRATION_UNTRUSTED in
            report.blockingReasons)
        assertTrue(ActiveCompatibilityReasonCode.CAMERA_IMU_EXTRINSICS_UNTRUSTED in
            report.blockingReasons)
    }

    @Test
    fun detectsNonMonotonicGyroscopeSamples() {
        val probe = ActiveDeviceCompatibilityProbe(staticReport(), true, true,
            ActiveCompatibilityConfig(minimumCameraSamples = 3,
                minimumAccelerometerSamples = 3, minimumGyroscopeSamples = 3,
                minimumCameraRateHz = 1.0, minimumAccelerometerRateHz = 1.0,
                minimumGyroscopeRateHz = 1.0))
        val start = 10_000_000_000L
        repeat(3) { index ->
            val timestamp = start + index * 100_000_000L
            probe.addCameraSample(CameraTimingSample(CameraTimingSampleKind.IMAGE,
                timestamp, timestamp + 10_000_000L, timestamp - 4_000_000_000L))
            probe.addCameraSample(CameraTimingSample(CameraTimingSampleKind.CAPTURE_RESULT,
                timestamp, timestamp + 10_000_000L, timestamp - 4_000_000_000L))
            probe.addImuSample(ImuTimingSample(ImuTimingSampleKind.ACCELEROMETER,
                timestamp, timestamp + 10_000_000L))
        }
        listOf(start, start + 100_000_000L, start + 50_000_000L).forEach { timestamp ->
            probe.addImuSample(ImuTimingSample(ImuTimingSampleKind.GYROSCOPE,
                timestamp, timestamp + 10_000_000L))
        }

        assertTrue(ActiveCompatibilityReasonCode.NON_MONOTONIC_GYROSCOPE_TIMESTAMPS in
            probe.finish().blockingReasons)
    }

    private fun feedHealthyCapture(probe: ActiveDeviceCompatibilityProbe,
        includeCaptureResults: Boolean = true) {
        val start = 10_000_000_000L
        repeat(30) { index ->
            val timestamp = start + index * 33_333_333L
            val elapsed = timestamp + 20_000_000L
            val uptime = elapsed - 5_000_000_000L
            probe.addCameraSample(CameraTimingSample(CameraTimingSampleKind.IMAGE,
                timestamp, elapsed, uptime))
            if (includeCaptureResults) probe.addCameraSample(CameraTimingSample(
                CameraTimingSampleKind.CAPTURE_RESULT, timestamp, elapsed, uptime))
        }
        repeat(120) { index ->
            val timestamp = start + index * 10_000_000L
            probe.addImuSample(ImuTimingSample(ImuTimingSampleKind.ACCELEROMETER,
                timestamp, timestamp + 20_000_000L))
            probe.addImuSample(ImuTimingSample(ImuTimingSampleKind.GYROSCOPE,
                timestamp, timestamp + 20_000_000L))
        }
    }

    private fun staticReport(): DeviceCompatibilityReport {
        val camera = CameraCapabilityFacts(cameraId = "0", physicalCameraIds = emptyList(),
            width = 640, height = 480,
            hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            timestampSource = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            sensorOrientationDegrees = 90, activeArrayWidth = 4000,
            activeArrayHeight = 3000, preCorrectionArrayWidth = 4000,
            preCorrectionArrayHeight = 3000, minimumFrameDurationNs = 33_333_333,
            focalLengths = listOf(4f), videoStabilizationModes = listOf(0),
            opticalStabilizationModes = listOf(0), distortionCorrectionModes = listOf(0),
            publishesIntrinsics = true, publishesDistortion = true,
            publishesRollingShutterSkew = true)
        val imu = ImuCapabilityFacts(true, true, 5_000, 5_000, 0, 0, 0.001f, 0.001f)
        return DeviceCompatibilityEvaluator.evaluate(DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = false, memoryClassMb = 256,
            buildFingerprintSha256 = "b".repeat(64), camera = camera, imu = imu))
    }
}
