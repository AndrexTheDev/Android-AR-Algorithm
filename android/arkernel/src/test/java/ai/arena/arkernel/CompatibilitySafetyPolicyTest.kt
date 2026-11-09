package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilitySafetyPolicyTest {
    @Test
    fun canonicalSignedPolicyRoundTripsAndNewerRemoteIsSelected() {
        val root = keyPair()
        val builtIn = CompatibilitySafetyPolicy(1, 0, Long.MAX_VALUE, emptyList())
        val remote = CompatibilitySafetyPolicy(2, 1_000, 3_000, listOf(
            CompatibilitySafetyRule("legacy-depth-off", hardwareLevels = listOf(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY),
                actions = listOf(CompatibilitySafetyAction.DISABLE_DENSE_DEPTH))))
        val payload = CompatibilitySafetyPolicyCodec.encode(remote)
        val decoded = CompatibilitySafetyPolicyCodec.decode(payload)
        assertEquals(remote, decoded)
        assertArrayEquals(payload, CompatibilitySafetyPolicyCodec.encode(decoded))
        val signed = sign(root, payload)
        val envelope = CompatibilitySafetyPolicyEnvelopeCodec.encode(signed)
        val restored = CompatibilitySafetyPolicyEnvelopeCodec.decode(envelope)
        assertArrayEquals(payload, restored.payload)
        val resolver = CompatibilitySafetyPolicyResolver(builtIn,
            CompatibilitySafetyPolicyVerifier(mapOf("root" to root.public)))

        val resolved = resolver.resolve(restored, 1_500)

        assertEquals(SafetyPolicySource.VERIFIED_REMOTE, resolved.source)
        assertEquals(2L, resolved.policy.revision)
    }

    @Test
    fun tamperedOrExpiredRemoteFallsBackToPermanentBuiltInPolicy() {
        val root = keyPair()
        val builtIn = CompatibilitySafetyPolicy(3, 0, Long.MAX_VALUE, listOf(
            CompatibilitySafetyRule("offline-default",
                actions = listOf(CompatibilitySafetyAction.DISABLE_SEMANTIC_INFERENCE))))
        val remote = CompatibilitySafetyPolicy(4, 1_000, 2_000, emptyList())
        val payload = CompatibilitySafetyPolicyCodec.encode(remote)
        val signed = sign(root, payload)
        val resolver = CompatibilitySafetyPolicyResolver(builtIn,
            CompatibilitySafetyPolicyVerifier(mapOf("root" to root.public)))

        assertEquals(SafetyPolicySource.BUILT_IN, resolver.resolve(signed, 2_000).source)
        assertEquals(SafetyPolicyVerificationReason.EXPIRED,
            resolver.resolve(signed, 2_000).remoteRejection)
        val tampered = SignedCompatibilitySafetyPolicy("root",
            payload.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            signed.signature)
        assertEquals(SafetyPolicyVerificationReason.SIGNATURE_INVALID,
            resolver.resolve(tampered, 1_500).remoteRejection)
    }

    @Test
    fun matchingRulesCanOnlyRemoveCapabilities() {
        val static = staticReport()
        val measured = RuntimeResourcePolicy.evaluate(static, activeReport())
        assertEquals(RuntimeResourceTier.PERFORMANCE, measured.tier)
        val policy = CompatibilitySafetyPolicy(1, 0, Long.MAX_VALUE, listOf(
            CompatibilitySafetyRule("full-camera-conservative-features",
                hardwareLevels = listOf(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL),
                lowRamMatch = SafetyBooleanMatch.EXCLUDED,
                actions = listOf(CompatibilitySafetyAction.DISABLE_DENSE_DEPTH,
                    CompatibilitySafetyAction.DISABLE_SEMANTIC_INFERENCE))))

        val applied = CompatibilitySafetyPolicyEvaluator.apply(measured, policy,
            static, activeReport(), 1_500)

        assertEquals(RuntimeResourceTier.PERFORMANCE, applied.tier)
        assertEquals(measured.targetFramesPerSecond, applied.targetFramesPerSecond)
        assertFalse(applied.denseDepthEnabled)
        assertFalse(applied.semanticInferenceEnabled)
        assertTrue(applied.liveMappingEnabled)
        assertTrue(RuntimeResourceReason.SIGNED_SAFETY_POLICY in applied.reasons)
    }

    @Test
    fun activeFailureRuleForcesCaptureButCannotGrantMapping() {
        val static = staticReport()
        val failed = activeReport(listOf(
            ActiveCompatibilityReasonCode.CAMERA_FRAME_GAPS_EXCESSIVE))
        val measured = RuntimeResourcePolicy.evaluate(static, failed)
        val policy = CompatibilitySafetyPolicy(1, 0, Long.MAX_VALUE, listOf(
            CompatibilitySafetyRule("gap-kill-switch",
                anyActiveReasons = listOf(
                    ActiveCompatibilityReasonCode.CAMERA_FRAME_GAPS_EXCESSIVE),
                actions = listOf(CompatibilitySafetyAction.FORCE_CAPTURE_ONLY))))

        val applied = CompatibilitySafetyPolicyEvaluator.apply(measured, policy,
            static, failed, 1_500)

        assertEquals(RuntimeResourceTier.CAPTURE_ONLY, applied.tier)
        assertFalse(applied.liveMappingEnabled)
        assertEquals(0, applied.maximumQueuedImuSamples)
    }

    private fun sign(key: java.security.KeyPair,
        payload: ByteArray): SignedCompatibilitySafetyPolicy {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(payload); sign()
        }
        return SignedCompatibilitySafetyPolicy("root", payload, signature)
    }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun staticReport(): DeviceCompatibilityReport {
        val camera = CameraCapabilityFacts("0", emptyList(), 640, 480,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, 90,
            4000, 3000, 4000, 3000, 33_333_333L, listOf(4f), listOf(0),
            listOf(0), listOf(0), true, true, true)
        val signature = DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = false,
            memoryClassMb = 768, buildFingerprintSha256 = "a".repeat(64),
            camera = camera, imu = ImuCapabilityFacts(true, true, 5_000, 5_000,
                0, 0, 0.001f, 0.001f))
        return DeviceCompatibilityEvaluator.evaluate(signature)
    }

    private fun activeReport(
        reasons: List<ActiveCompatibilityReasonCode> = emptyList(),
    ): ActiveCompatibilityReport {
        val camera = TimingStreamStatistics(100, true, 30.0, 33_333_333L,
            100_000L, if (reasons.isEmpty()) 0.0 else 0.2,
            1_000_000_000L, 4_000_000_000L)
        val imu = camera.copy(medianRateHz = 100.0, medianIntervalNs = 10_000_000L,
            largeGapRatio = 0.0)
        return ActiveCompatibilityReport(ObservedCameraTimebase.REALTIME, camera, imu, imu,
            1.0, 3_000_000_000L, 10_000_000L, 10_000_000L,
            if (reasons.isEmpty()) DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC
            else DeviceCompatibilityTier.CAPTURE_ONLY, reasons)
    }
}
