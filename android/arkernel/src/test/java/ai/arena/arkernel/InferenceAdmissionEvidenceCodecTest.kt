package ai.arena.arkernel

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InferenceAdmissionEvidenceCodecTest {
    private val modelBytes = "evidence-model".toByteArray()

    @Test
    fun acceptedReportRoundTripsWithExactManifestHash() {
        val identity = identity()
        val evidence = evidence(identity)

        val decoded = InferenceAdmissionEvidenceCodec.decode(
            InferenceAdmissionEvidenceCodec.encode(evidence))

        assertEquals(evidence, decoded)
        assertEquals(64, evidence.manifestIdentitySha256.length)
        assertTrue(decoded.matches(identity, signature(), "b".repeat(64),
            "d".repeat(64), InferenceSemanticConformanceOptions(), "adapter", "1.2.3", "e".repeat(64),
            "software-7", 9, 1_500))
    }

    @Test
    fun everyExactAdmissionDimensionAndExpiryCausesCacheMiss() {
        val identity = identity()
        val evidence = evidence(identity)
        val signature = signature()

        val options = InferenceSemanticConformanceOptions()
        assertFalse(evidence.matches(identity(version = "2"), signature, "b".repeat(64),
            "d".repeat(64), options, "adapter", "1.2.3", "e".repeat(64), "software-7", 9, 1_500))
        assertFalse(evidence.matches(identity, signature, "c".repeat(64), "d".repeat(64),
            options, "adapter", "1.2.3", "e".repeat(64), "software-7", 9, 1_500))
        assertFalse(evidence.matches(identity, signature, "b".repeat(64), "e".repeat(64),
            options, "adapter", "1.2.3", "e".repeat(64), "software-7", 9, 1_500))
        assertFalse(evidence.matches(identity, signature, "b".repeat(64), "d".repeat(64),
            options.copy(confidenceTolerance = 0.02), "adapter", "1.2.3", "e".repeat(64),
            "software-7", 9, 1_500))
        assertFalse(evidence.matches(identity, signature, "b".repeat(64), "d".repeat(64),
            options, "other", "1.2.3", "e".repeat(64), "software-7", 9, 1_500))
        assertFalse(evidence.matches(identity, signature, "b".repeat(64), "d".repeat(64),
            options, "adapter", "2", "e".repeat(64), "software-7", 9, 1_500))
        assertFalse(evidence.matches(identity, signature, "b".repeat(64), "d".repeat(64),
            options, "adapter", "1.2.3", "f".repeat(64), "software-7", 9, 1_500))
        assertFalse(evidence.matches(identity, signature, "b".repeat(64), "d".repeat(64),
            options, "adapter", "1.2.3", "e".repeat(64), "software-8", 9, 1_500))
        assertFalse(evidence.matches(identity, signature, "b".repeat(64), "d".repeat(64),
            options, "adapter", "1.2.3", "e".repeat(64), "software-7", 10, 1_500))
        assertFalse(evidence.matches(identity, signature, "b".repeat(64), "d".repeat(64),
            options, "adapter", "1.2.3", "e".repeat(64), "software-7", 9, 2_000))
    }

    @Test
    fun canonicalManifestHashIncludesFieldsBeyondModelArtifactHash() {
        val first = identity()
        val second = identity(version = "2")

        assertEquals(first.manifest.modelSha256, second.manifest.modelSha256)
        assertNotEquals(first.manifest.stableSha256(), second.manifest.stableSha256())
    }

    @Test
    fun rejectedAdmissionCannotBePersistedAsSuccess() {
        val rejected = VerifiedInferenceBackendAdmissionResult(FakeBackend(manifest()), false,
            InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_REJECTED,
            report(accepted = false))
        try {
            PersistedInferenceAdmissionEvidence.fromAccepted(identity(), signature(),
                "b".repeat(64), "d".repeat(64), InferenceSemanticConformanceOptions(),
                "adapter", "1", "e".repeat(64), "software", 0, rejected, 1_000, 1_000)
            fail("Expected rejected evidence refusal")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun previousEvidenceVersionIsRejectedInsteadOfReusedWithoutDriverIdentity() {
        val bytes = InferenceAdmissionEvidenceCodec.encode(evidence(identity()))
        val payloadSize = ByteBuffer.wrap(bytes, 0, Int.SIZE_BYTES).int
        ByteBuffer.wrap(bytes, 2 * Int.SIZE_BYTES, Int.SIZE_BYTES).putInt(2)
        val crc = CRC32().apply { update(bytes, Int.SIZE_BYTES, payloadSize) }.value
        ByteBuffer.wrap(bytes, Int.SIZE_BYTES + payloadSize, Int.SIZE_BYTES).putInt(crc.toInt())
        try {
            InferenceAdmissionEvidenceCodec.decode(bytes)
            fail("Expected old evidence version rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun checksumCorruptionIsRejected() {
        val bytes = InferenceAdmissionEvidenceCodec.encode(evidence(identity()))
        bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 1).toByte()
        try {
            InferenceAdmissionEvidenceCodec.decode(bytes)
            fail("Expected checksum rejection")
        } catch (_: IllegalArgumentException) { }
    }

    private fun evidence(identity: VerifiedInferenceIdentity) =
        PersistedInferenceAdmissionEvidence.fromAccepted(identity, signature(), "b".repeat(64),
            "d".repeat(64), InferenceSemanticConformanceOptions(), "adapter", "1.2.3", "e".repeat(64),
            "software-7", 9, acceptedResult(), 1_000, 1_000)

    private fun acceptedResult() = VerifiedInferenceBackendAdmissionResult(
        FakeBackend(manifest()), true,
        InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_ACCEPTED,
        report(accepted = true))

    private fun report(accepted: Boolean) = InferenceSemanticConformanceReport(accepted,
        evaluatedCases = 2, comparedDetections = 3, comparedDepthPixels = 4,
        maximumDetectionCoordinateErrorPixels = 0.5,
        maximumDetectionConfidenceError = 0.01,
        maximumDepthAbsoluteErrorMeters = 0.015,
        maximumDepthRelativeError = 0.02,
        maximumDepthConfidenceError = 0.01,
        failure = if (accepted) null else
            InferenceSemanticConformanceFailure.DETECTION_VALUE_MISMATCH,
        failedCaseId = if (accepted) null else "failed")

    private fun identity(version: String = "1") = VerifiedInferenceIdentity.create(
        manifest(version), ByteBuffer.wrap(modelBytes))

    private fun manifest(version: String = "1") = ModelManifest(
        modelId = "evidence", modelVersion = version, sha256 = sha256(modelBytes),
        licenseSpdx = "Apache-2.0", sourceUrl = "https://example.com/evidence",
        tasks = setOf(InferenceTask.SEMANTIC_DETECTION), inputWidth = 1, inputHeight = 1,
        inputChannels = 1, inputMean = listOf(0.0), inputStd = listOf(1.0),
        quantization = TensorQuantization.FLOAT32, labels = mapOf(0L to "Unknown"),
        modelByteSize = modelBytes.size.toLong())

    private fun signature() = DeviceCapabilitySignature(apiLevel = 35,
        supportedAbis = listOf("arm64-v8a"), lowRamDevice = false, memoryClassMb = 512,
        buildFingerprintSha256 = "a".repeat(64), camera = null,
        imu = ImuCapabilityFacts(true, true, 5_000, 5_000, 0, 0, 0.001f, 0.001f))

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class FakeBackend(override val manifest: ModelManifest) : OnDeviceInferenceBackend {
        override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput = error("unused")
    }
}
