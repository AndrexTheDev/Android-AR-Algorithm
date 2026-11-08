package ai.arena.arkernel

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedCalibrationProfileTest {
    @Test
    fun verifiesSignatureExactCapabilityStreamAndValidity() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signature = deviceSignature()
        val stream = stream()
        val profile = profile(signature.stableIdSha256, stream)
        val payload = CalibrationProfileCodec.encode(profile)
        assertEquals(profile, CalibrationProfileCodec.decode(payload))
        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private); update(payload); sign()
        }
        val signed = SignedCalibrationProfile("lab-key-2026",
            CalibrationSignatureAlgorithm.ECDSA_P256_SHA256, payload, signatureBytes)
        val verifier = CalibrationProfileVerifier(mapOf("lab-key-2026" to keyPair.public))

        val accepted = verifier.verify(signed, signature, stream, 1_500)
        assertTrue(accepted.reasons.toString(), accepted.accepted)
        assertEquals(CalibrationMetricClaim.CENTIMETER_VALIDATED,
            requireNotNull(accepted.profile).metricClaim)
        assertEquals(64, requireNotNull(accepted.profileSha256).length)

        val wrongStream = stream.copy(cropLeft = 1)
        val mismatch = verifier.verify(signed, signature, wrongStream, 1_500)
        assertFalse(mismatch.accepted)
        assertTrue(CalibrationVerificationReason.CAMERA_STREAM_MISMATCH in mismatch.reasons)

        val expired = verifier.verify(signed, signature, stream, 2_000)
        assertTrue(CalibrationVerificationReason.PROFILE_EXPIRED in expired.reasons)
        val rollback = verifier.verify(signed, signature, stream, 1_500,
            minimumAcceptedRevision = 2)
        assertTrue(CalibrationVerificationReason.PROFILE_REVISION_ROLLBACK in rollback.reasons)
    }

    @Test
    fun rejectsTamperingAndUnknownSigner() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val profile = profile(deviceSignature().stableIdSha256, stream())
        val payload = CalibrationProfileCodec.encode(profile)
        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private); update(payload); sign()
        }
        val tampered = payload.copyOf().also { it[it.lastIndex - 2] =
            (it[it.lastIndex - 2].toInt() xor 1).toByte() }
        val signed = SignedCalibrationProfile("lab",
            CalibrationSignatureAlgorithm.ECDSA_P256_SHA256, tampered, signatureBytes)

        val invalid = CalibrationProfileVerifier(mapOf("lab" to keyPair.public))
            .verify(signed, deviceSignature(), stream(), 1_500)
        assertEquals(listOf(CalibrationVerificationReason.SIGNATURE_INVALID), invalid.reasons)
        val unknown = CalibrationProfileVerifier(emptyMap())
            .verify(signed, deviceSignature(), stream(), 1_500)
        assertEquals(listOf(CalibrationVerificationReason.UNKNOWN_SIGNING_KEY), unknown.reasons)
    }

    @Test(expected = IllegalArgumentException::class)
    fun userMeasuredProfileCannotClaimLabValidation() {
        profile(deviceSignature().stableIdSha256, stream()).copy(
            issuerClass = CalibrationProfileIssuerClass.USER_MEASURED)
    }

    private fun profile(capability: String, stream: CalibrationStreamBinding) =
        CalibrationProfile("lab-profile-1", 1L, CalibrationProfileIssuerClass.INDEPENDENT_LAB,
            CalibrationMetricClaim.CENTIMETER_VALIDATED, capability, stream,
            CameraCalibration(640, 480, 500.0, 501.0, 320.0, 240.0,
                -0.01, 0.001, 0.0, 0.0, 0.0),
            Rotation(1.0, 0.0, 0.0, 0.0), Vector3(0.01, 0.0, 0.0),
            2_000_000L, 500_000L,
            CalibrationMeasurementEvidence(12, 0.004, 0.01, 0.35, 3.0,
                rotationObservable = true, timeOffsetObservable = true,
                metricRuntimeConsistent = true),
            createdAtEpochMillis = 1_000, expiresAtEpochMillis = 2_000)

    private fun stream() = CalibrationStreamBinding("0", "0", 640, 480,
        0, 0, 4000, 3000, 0, 0, 0)

    private fun deviceSignature() = DeviceCapabilitySignature(apiLevel = 35,
        supportedAbis = listOf("arm64-v8a"), lowRamDevice = false, memoryClassMb = 256,
        buildFingerprintSha256 = "a".repeat(64), camera = null,
        imu = ImuCapabilityFacts(true, true, 5_000, 5_000, 0, 0, 0.001f, 0.001f))
}
