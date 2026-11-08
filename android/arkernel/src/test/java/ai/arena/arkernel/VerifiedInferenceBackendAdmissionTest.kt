package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VerifiedInferenceBackendAdmissionTest {
    private val artifactBytes = "one-verified-artifact".toByteArray()
    private val manifest = manifest()

    @Test
    fun factoriesReceiveFreshReadOnlyViewsAndConformingDelegateIsSelected() {
        val frame = frame(10)
        val source = sourceBuffer()
        val sourcePosition = source.position()
        val cpu = FakeBackend(manifest) { output(frame, 0.90) }
        val delegate = FakeBackend(manifest) { output(frame, 0.905) }
        val cpuCalls = AtomicInteger()
        val delegateCalls = AtomicInteger()

        val result = VerifiedInferenceBackendAdmission.createAndSelect(manifest, source,
            cpuFactory = VerifiedInferenceBackendFactory { suppliedManifest, bytes ->
                assertSame(manifest, suppliedManifest)
                assertReadOnlyArtifact(bytes)
                bytes.position(bytes.limit())
                cpuCalls.incrementAndGet()
                cpu
            },
            delegateFactory = VerifiedInferenceBackendFactory { _, bytes ->
                assertEquals(0, bytes.position())
                assertReadOnlyArtifact(bytes)
                delegateCalls.incrementAndGet()
                delegate
            },
            cases = listOf(InferenceSemanticGoldenCase("admission", frame)))

        assertEquals(InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_ACCEPTED,
            result.status)
        assertTrue(result.usingDelegate)
        assertSame(delegate, result.backend)
        assertEquals(sourcePosition, source.position())
        assertEquals(1, cpuCalls.get())
        assertEquals(1, delegateCalls.get())
        assertEquals(1, cpu.closeCount)
        assertEquals(0, delegate.closeCount)
    }

    @Test
    fun absentDelegateFactoryReturnsExplicitCpuOnlyOwner() {
        val cpu = FakeBackend(manifest) { error("not executed") }

        val result = VerifiedInferenceBackendAdmission.createAndSelect(manifest, sourceBuffer(),
            cpuFactory = VerifiedInferenceBackendFactory { _, _ -> cpu })

        assertEquals(InferenceBackendAdmissionStatus.CPU_ONLY, result.status)
        assertFalse(result.usingDelegate)
        assertSame(cpu, result.backend)
        assertTrue(result.conformanceReport == null)
        assertEquals(0, cpu.closeCount)
    }

    @Test
    fun delegateCreationFailureRetainsVerifiedCpu() {
        val source = sourceBuffer()
        val cpu = FakeBackend(manifest) { error("not executed") }

        val result = VerifiedInferenceBackendAdmission.createAndSelect(manifest, source,
            cpuFactory = VerifiedInferenceBackendFactory { _, _ -> cpu },
            delegateFactory = VerifiedInferenceBackendFactory { _, _ ->
                throw IllegalStateException("delegate unavailable")
            })

        assertEquals(InferenceBackendAdmissionStatus.DELEGATE_CREATION_FAILED, result.status)
        assertFalse(result.usingDelegate)
        assertSame(cpu, result.backend)
        assertEquals(0, cpu.closeCount)
    }

    @Test
    fun semanticRejectionClosesDelegateAndRetainsCpu() {
        val frame = frame(20)
        val cpu = FakeBackend(manifest) { output(frame, 0.9) }
        val delegate = FakeBackend(manifest) { output(frame, 0.1) }

        val result = VerifiedInferenceBackendAdmission.createAndSelect(manifest, sourceBuffer(),
            cpuFactory = VerifiedInferenceBackendFactory { _, _ -> cpu },
            delegateFactory = VerifiedInferenceBackendFactory { _, _ -> delegate },
            cases = listOf(InferenceSemanticGoldenCase("reject", frame)))

        assertEquals(InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_REJECTED,
            result.status)
        assertSame(cpu, result.backend)
        assertEquals(InferenceSemanticConformanceFailure.DETECTION_VALUE_MISMATCH,
            result.conformanceReport?.failure)
        assertEquals(0, cpu.closeCount)
        assertEquals(1, delegate.closeCount)
    }

    @Test
    fun mismatchedCpuFactoryResultClosesCpuWithoutCreatingDelegate() {
        val cpu = FakeBackend(manifest(version = "wrong")) { error("not executed") }
        val delegateCalls = AtomicInteger()

        try {
            VerifiedInferenceBackendAdmission.createAndSelect(manifest, sourceBuffer(),
                cpuFactory = VerifiedInferenceBackendFactory { _, _ -> cpu },
                delegateFactory = VerifiedInferenceBackendFactory { _, _ ->
                    delegateCalls.incrementAndGet()
                    error("must not be created")
                })
            fail("Expected CPU identity rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, cpu.closeCount)
            assertEquals(0, delegateCalls.get())
        }
    }

    @Test
    fun invalidSemanticArgumentsCloseEachTransferredBackendOnce() {
        val cpu = FakeBackend(manifest) { error("not executed") }
        val delegate = FakeBackend(manifest) { error("not executed") }

        try {
            VerifiedInferenceBackendAdmission.createAndSelect(manifest, sourceBuffer(),
                cpuFactory = VerifiedInferenceBackendFactory { _, _ -> cpu },
                delegateFactory = VerifiedInferenceBackendFactory { _, _ -> delegate })
            fail("Expected missing golden cases rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, cpu.closeCount)
            assertEquals(1, delegate.closeCount)
        }
    }

    private fun assertReadOnlyArtifact(bytes: ByteBuffer) {
        assertTrue(bytes.isReadOnly)
        assertEquals(artifactBytes.size, bytes.remaining())
        val copy = ByteArray(bytes.remaining())
        bytes.duplicate().get(copy)
        assertTrue(copy.contentEquals(artifactBytes))
        try {
            bytes.put(0, 1.toByte())
            fail("Expected read-only model view")
        } catch (_: ReadOnlyBufferException) { }
    }

    private fun sourceBuffer(): ByteBuffer = ByteBuffer.allocate(artifactBytes.size + 3).apply {
        put(byteArrayOf(7, 8, 9))
        put(artifactBytes)
        flip()
        position(3)
    }

    private fun output(frame: PerceptionFrame, confidence: Double) =
        OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera,
            detections = listOf(SemanticDetection2d(0, confidence = confidence,
                left = 1.0, top = 1.0, right = 2.0, bottom = 2.0)))

    private fun frame(timestampNs: Long): PerceptionFrame {
        val calibration = CameraCalibration(4, 4, 2.0, 2.0, 2.0, 2.0)
        val pose = WorldPose(timestampNs, Rotation(1.0, 0.0, 0.0, 0.0),
            Vector3(0.0, 0.0, 0.0))
        return PerceptionFrame.wrap(timestampNs, calibration, pose,
            InferencePixelFormat.GRAY8, 4, ByteBuffer.allocateDirect(16))
    }

    private fun manifest(version: String = "1") = ModelManifest(
        modelId = "admission-test",
        modelVersion = version,
        sha256 = MessageDigest.getInstance("SHA-256").digest(artifactBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
        licenseSpdx = "Apache-2.0",
        sourceUrl = "https://example.com/admission-model",
        tasks = setOf(InferenceTask.SEMANTIC_DETECTION),
        inputWidth = 4,
        inputHeight = 4,
        inputChannels = 1,
        inputMean = listOf(0.0),
        inputStd = listOf(1.0),
        quantization = TensorQuantization.FLOAT32,
        labels = mapOf(0L to "Unknown"),
        modelByteSize = artifactBytes.size.toLong(),
    )

    private class FakeBackend(
        override val manifest: ModelManifest,
        private val inference: (PerceptionFrame) -> OnDeviceInferenceOutput,
    ) : OnDeviceInferenceBackend {
        var closeCount = 0
        override fun infer(frame: PerceptionFrame) = inference(frame)
        override fun close() { ++closeCount }
    }
}
