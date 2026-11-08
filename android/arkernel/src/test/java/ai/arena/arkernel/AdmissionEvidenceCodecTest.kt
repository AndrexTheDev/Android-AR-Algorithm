package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionEvidenceCodecTest {
    @Test
    fun roundTripsExactAdmissionEvidence() {
        val rotation = RotationObservabilityReport(18, 0.54, 0.53, 0.98, 0.99, emptyList())
        val timeOffset = TimeOffsetObservabilityReport(500, 100, 3_000_000_000L,
            24_000_000L, 4_000_000L, 5.0e12, 0.98, 0.05, emptyList())
        val drift = TimeOffsetDriftReport(6, 0, 100_000_000_000L,
            120.0, 4.0, 100_000.0, emptyList())
        val evidence = PersistedAdmissionEvidence(
            capabilitySignatureSha256 = "a".repeat(64),
            calibrationProfileSha256 = "b".repeat(64),
            softwareRevision = "core-17:model-none:policy-3",
            recordedAtEpochMillis = 1_000,
            expiresAtEpochMillis = 2_000,
            promotedTier = DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC,
            observedCameraTimebase = ObservedCameraTimebase.REALTIME,
            activeBlockingReasons = emptyList(),
            rotationReport = rotation,
            timeOffsetReport = timeOffset,
            timeOffsetDriftReport = drift,
        )

        assertEquals(evidence, AdmissionEvidenceCodec.decode(AdmissionEvidenceCodec.encode(evidence)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCorruptedEvidence() {
        val evidence = PersistedAdmissionEvidence("a".repeat(64), "b".repeat(64), "revision",
            1_000, 2_000, DeviceCompatibilityTier.DEGRADED_TRACKING,
            ObservedCameraTimebase.UPTIME,
            listOf(ActiveCompatibilityReasonCode.CAMERA_FRAME_RATE_TOO_LOW), null)
        val bytes = AdmissionEvidenceCodec.encode(evidence)
        bytes[bytes.lastIndex - 2] = (bytes[bytes.lastIndex - 2].toInt() xor 1).toByte()
        AdmissionEvidenceCodec.decode(bytes)
    }

    @Test
    fun exactMatchRequiresSignatureCalibrationRevisionAndFreshness() {
        val signature = signature("a".repeat(64))
        val evidence = PersistedAdmissionEvidence(signature.stableIdSha256,
            "b".repeat(64), "revision", 1_000, 2_000,
            DeviceCompatibilityTier.DEGRADED_TRACKING,
            ObservedCameraTimebase.EQUIVALENT, emptyList(), null)
        assertTrue(evidence.matches(signature, "b".repeat(64), "revision", 1_500))
        assertTrue(!evidence.matches(signature, "c".repeat(64), "revision", 1_500))
        assertTrue(!evidence.matches(signature, "b".repeat(64), "other", 1_500))
        assertTrue(!evidence.matches(signature, "b".repeat(64), "revision", 2_000))
    }

    private fun signature(buildHash: String): DeviceCapabilitySignature =
        DeviceCapabilitySignature(apiLevel = 35, supportedAbis = listOf("arm64-v8a"),
            lowRamDevice = false, memoryClassMb = 256, buildFingerprintSha256 = buildHash,
            camera = null, imu = ImuCapabilityFacts(false, false, -1, -1, 0, 0, 0f, 0f))
}
