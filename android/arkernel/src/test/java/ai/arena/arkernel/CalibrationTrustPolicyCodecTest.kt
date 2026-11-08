package ai.arena.arkernel

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationTrustPolicyCodecTest {
    @Test
    fun canonicalPolicyAndEnvelopeRoundTrip() {
        val profileKey = keyPair()
        val policy = CalibrationTrustPolicy(9, listOf(
            TrustedCalibrationKey("profile-b", profileKey.public, 1_000, 9_000, 8_000),
            TrustedCalibrationKey("profile-a", keyPair().public, 1_000, 9_000)),
            mapOf("profile-z" to 7L, "profile-a" to 3L), 1_000, 10_000)
        val payload = CalibrationTrustPolicyCodec.encode(policy)
        val decoded = CalibrationTrustPolicyCodec.decode(payload)
        assertEquals(9L, decoded.revision)
        assertEquals(2, decoded.trustedKeys.size)
        assertEquals(3L, decoded.minimumProfileRevisions["profile-a"])
        assertArrayEquals(payload, CalibrationTrustPolicyCodec.encode(decoded))

        val root = keyPair()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(root.private); update(payload); sign()
        }
        val signed = SignedCalibrationTrustPolicy("root-1", payload, signature)
        val restored = TrustPolicyEnvelopeCodec.decode(TrustPolicyEnvelopeCodec.encode(signed))
        assertEquals("root-1", restored.rootKeyId)
        assertArrayEquals(payload, restored.payload)
        assertArrayEquals(signature, restored.signature)
    }

    @Test
    fun decodedPolicyEnforcesExpiryThroughProfileVerifier() {
        val profileKey = keyPair()
        val policy = CalibrationTrustPolicyCodec.decode(CalibrationTrustPolicyCodec.encode(
            CalibrationTrustPolicy(2, listOf(TrustedCalibrationKey(
                "profile-key", profileKey.public, 1_000, 9_000)),
                validFromEpochMillis = 2_000, validUntilEpochMillis = 3_000)))
        val verifier = CalibrationProfileVerifier(policy)
        // Policy validity is checked before key lookup or profile parsing.
        val placeholder = SignedCalibrationProfile("profile-key",
            CalibrationSignatureAlgorithm.ECDSA_P256_SHA256, byteArrayOf(1), byteArrayOf(1))
        assertEquals(listOf(CalibrationVerificationReason.TRUST_POLICY_NOT_YET_VALID),
            verifier.verify(placeholder, deviceSignature(), stream(), 1_500).reasons)
        assertEquals(listOf(CalibrationVerificationReason.TRUST_POLICY_EXPIRED),
            verifier.verify(placeholder, deviceSignature(), stream(), 3_000).reasons)
    }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun stream() = CalibrationStreamBinding("0", null, 640, 480,
        0, 0, 4000, 3000, 0, 0, 0)

    private fun deviceSignature() = DeviceCapabilitySignature(apiLevel = 35,
        supportedAbis = listOf("arm64-v8a"), lowRamDevice = false, memoryClassMb = 256,
        buildFingerprintSha256 = "a".repeat(64), camera = null,
        imu = ImuCapabilityFacts(true, true, 5_000, 5_000, 0, 0, 0.001f, 0.001f))
}
