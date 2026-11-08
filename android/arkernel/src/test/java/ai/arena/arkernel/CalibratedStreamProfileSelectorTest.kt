package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratedStreamProfileSelectorTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val verifier = CalibrationProfileVerifier(mapOf("lab" to keyPair.public))

    @Test
    fun selectsVerifiedLowerStreamAndRequiresCleanRestart() {
        val highSignature = deviceSignature(1280, 720, memoryMb = 192, lowRam = true)
        val lowSignature = deviceSignature(640, 480, memoryMb = 192, lowRam = true)
        val high = candidate("profile-720", highSignature)
        val low = candidate("profile-480", lowSignature)
        val decision = RuntimeResourcePolicy.evaluate(
            DeviceCompatibilityEvaluator.evaluate(highSignature), activeReport())
        assertEquals(RuntimeResourceTier.CAPTURE_ONLY, decision.tier)
        assertEquals(RuntimeResourceTier.CONSERVATIVE, decision.calibratedAlternativeTier)

        val selection = CalibratedStreamProfileSelector(verifier).select(
            listOf(high, low), decision, 1_500, highSignature, high.signedProfile.profileSha256)

        assertEquals(CalibratedStreamTransition.RESTART_REQUIRED, selection.transition)
        assertEquals("profile-480", requireNotNull(selection.selected).profile.profileId)
        assertEquals(RuntimeResourceTier.CONSERVATIVE,
            requireNotNull(selection.selected).resourceDecision.tier)
        assertTrue(selection.rejections.any {
            it.profileId == "profile-720" &&
                it.reason == CalibratedStreamRejectionReason.RESOURCE_BUDGET_EXCEEDED
        })
        assertEquals(640, requireNotNull(selection.selected).standaloneConfig().calibration.width)
        assertNotNull(requireNotNull(selection.selected).newRuntimeMonitor())
    }

    @Test
    fun doesNotPromoteThermalCaptureOnlyDecision() {
        val signature = deviceSignature(640, 480, memoryMb = 768)
        val decision = RuntimeResourcePolicy.evaluate(
            DeviceCompatibilityEvaluator.evaluate(signature), activeReport(),
            thermalState = InferenceThermalState.CRITICAL)
        assertEquals(null, decision.calibratedAlternativeTier)

        val selection = CalibratedStreamProfileSelector(verifier).select(
            listOf(candidate("thermal-capture", signature)), decision, 1_500, signature)

        assertEquals(RuntimeResourceTier.CAPTURE_ONLY,
            requireNotNull(selection.selected).resourceDecision.tier)
        assertFalse(requireNotNull(selection.selected).resourceDecision.liveMappingEnabled)
    }

    @Test
    fun failsClosedWhenNoVerifiedProfileFitsBudget() {
        val signature = deviceSignature(1280, 720, memoryMb = 192, lowRam = true)
        val decision = RuntimeResourcePolicy.evaluate(
            DeviceCompatibilityEvaluator.evaluate(signature), activeReport())
        val onlyOversized = candidate("only-720", signature)

        val selection = CalibratedStreamProfileSelector(verifier).select(
            listOf(onlyOversized, onlyOversized), decision, 1_500, signature)

        assertEquals(CalibratedStreamTransition.NO_COMPATIBLE_PROFILE, selection.transition)
        assertEquals(null, selection.selected)
        assertTrue(selection.rejections.any {
            it.reason == CalibratedStreamRejectionReason.DUPLICATE_PROFILE
        })
    }

    @Test
    fun rejectsCapabilityThatDoesNotDescribeSignedStream() {
        val signedFor640 = candidate("mismatch", deviceSignature(640, 480))
        val wrongObserved = CalibratedStreamCandidate(signedFor640.signedProfile,
            deviceSignature(1280, 720))
        val decision = RuntimeResourcePolicy.evaluate(
            DeviceCompatibilityEvaluator.evaluate(deviceSignature(640, 480)), activeReport())

        val selection = CalibratedStreamProfileSelector(verifier).select(
            listOf(wrongObserved), decision, 1_500, deviceSignature(640, 480))

        assertEquals(CalibratedStreamTransition.NO_COMPATIBLE_PROFILE, selection.transition)
        assertEquals(CalibratedStreamRejectionReason.CAPABILITY_STREAM_MISMATCH,
            selection.rejections.single().reason)
    }

    private fun candidate(id: String,
        capability: DeviceCapabilitySignature): CalibratedStreamCandidate {
        val camera = requireNotNull(capability.camera)
        val stream = CalibrationStreamBinding(camera.cameraId, null, camera.width, camera.height,
            0, 0, camera.activeArrayWidth, camera.activeArrayHeight, 0, 0, 0)
        val profile = CalibrationProfile(id, 1,
            CalibrationProfileIssuerClass.USER_MEASURED,
            CalibrationMetricClaim.TRACKING_ONLY, capability.stableIdSha256, stream,
            CameraCalibration(camera.width, camera.height, camera.width * 0.8,
                camera.height * 1.1, camera.width / 2.0, camera.height / 2.0),
            Rotation(1.0, 0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0),
            0, 1_000_000, CalibrationMeasurementEvidence(0, 0.0, 0.0, 0.5, 0.0,
                rotationObservable = false, timeOffsetObservable = false,
                metricRuntimeConsistent = false), 1_000, 2_000)
        val payload = CalibrationProfileCodec.encode(profile)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private); update(payload); sign()
        }
        return CalibratedStreamCandidate(SignedCalibrationProfile("lab",
            CalibrationSignatureAlgorithm.ECDSA_P256_SHA256, payload, signature), capability)
    }

    private fun deviceSignature(width: Int, height: Int, memoryMb: Int = 768,
        lowRam: Boolean = false): DeviceCapabilitySignature {
        val camera = CameraCapabilityFacts("0", emptyList(), width, height,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, 90,
            4000, 3000, 4000, 3000, 33_333_333L, listOf(4f), listOf(0),
            listOf(0), listOf(0), true, true, true)
        return DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = lowRam,
            memoryClassMb = memoryMb, buildFingerprintSha256 = "a".repeat(64),
            camera = camera, imu = ImuCapabilityFacts(true, true, 5_000, 5_000,
                0, 0, 0.001f, 0.001f))
    }

    private fun activeReport(): ActiveCompatibilityReport {
        val camera = TimingStreamStatistics(100, true, 30.0, 33_333_333L,
            100_000L, 0.0, 1_000_000_000L, 4_000_000_000L)
        val imu = camera.copy(medianRateHz = 100.0, medianIntervalNs = 10_000_000L)
        return ActiveCompatibilityReport(ObservedCameraTimebase.REALTIME, camera, imu, imu,
            1.0, 3_000_000_000L, 10_000_000L, 10_000_000L,
            DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC, emptyList())
    }
}
