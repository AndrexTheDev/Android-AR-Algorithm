package ai.arena.arkernel

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
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
    fun decodesVersion2ProfileWithoutInventingRollingShutterEvidence() {
        val original = profile(deviceSignature().stableIdSha256, stream())
        val decoded = CalibrationProfileCodec.decode(asLegacyVersion2(
            CalibrationProfileCodec.encode(original)))
        assertEquals(original.copy(stream = original.stream.copy(
            expectedRollingShutterSkewNs = null,
            rollingShutterSkewToleranceNs = 0L)), decoded)
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

    @Test
    fun enforcesKeyRotationRevocationAndPolicyRevisionFloor() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val device = deviceSignature()
        val profile = profile(device.stableIdSha256, stream())
        val payload = CalibrationProfileCodec.encode(profile)
        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private); update(payload); sign()
        }
        val signed = SignedCalibrationProfile("rotating-key",
            CalibrationSignatureAlgorithm.ECDSA_P256_SHA256, payload, signatureBytes)
        val policy = CalibrationTrustPolicy(7, listOf(TrustedCalibrationKey(
            "rotating-key", keyPair.public, 1_200, 1_900, revokedAtEpochMillis = 1_700)),
            minimumProfileRevisions = mapOf("lab-profile-1" to 2L))
        val verifier = CalibrationProfileVerifier(policy)

        assertEquals(listOf(CalibrationVerificationReason.SIGNING_KEY_NOT_YET_VALID),
            verifier.verify(signed, device, stream(), 1_100).reasons)
        assertTrue(CalibrationVerificationReason.PROFILE_REVISION_ROLLBACK in
            verifier.verify(signed, device, stream(), 1_500).reasons)
        assertEquals(listOf(CalibrationVerificationReason.SIGNING_KEY_REVOKED),
            verifier.verify(signed, device, stream(), 1_750).reasons)
    }

    @Test(expected = IllegalArgumentException::class)
    fun userMeasuredProfileCannotClaimLabValidation() {
        profile(deviceSignature().stableIdSha256, stream()).copy(
            issuerClass = CalibrationProfileIssuerClass.USER_MEASURED)
    }

    private fun asLegacyVersion2(version3: ByteArray): ByteArray {
        val bytes = ByteArrayInputStream(version3)
        val input = DataInputStream(bytes)
        input.readInt(); input.readInt(); input.readUTF(); input.readLong()
        input.readInt(); input.readInt(); input.readUTF()
        input.readUTF()
        if (input.readBoolean()) input.readUTF()
        repeat(9) { input.readInt() }
        if (input.readBoolean()) input.readDouble()
        input.readDouble()
        if (input.readBoolean()) input.readDouble()
        input.readDouble()
        val rollingStart = version3.size - bytes.available()
        if (input.readBoolean()) input.readLong()
        input.readLong()
        val rollingEnd = version3.size - bytes.available()
        val legacy = ByteArray(version3.size - (rollingEnd - rollingStart))
        version3.copyInto(legacy, 0, 0, rollingStart)
        version3.copyInto(legacy, rollingStart, rollingEnd, version3.size)
        ByteBuffer.wrap(legacy, Int.SIZE_BYTES, Int.SIZE_BYTES).putInt(2)
        return legacy
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
        0, 0, 4000, 3000, 0, 0, 0,
        expectedRollingShutterSkewNs = 10_000_000L,
        rollingShutterSkewToleranceNs = 1_000_000L)

    private fun deviceSignature() = DeviceCapabilitySignature(apiLevel = 35,
        supportedAbis = listOf("arm64-v8a"), lowRamDevice = false, memoryClassMb = 256,
        buildFingerprintSha256 = "a".repeat(64), camera = null,
        imu = ImuCapabilityFacts(true, true, 5_000, 5_000, 0, 0, 0.001f, 0.001f))
}
