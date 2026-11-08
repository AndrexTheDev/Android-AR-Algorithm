package ai.arena.arkernel

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InferenceBackendIdentityConformanceTest {
    private val modelBytes = "verified-model-artifact".toByteArray()

    @Test
    fun matchingVerifiedIdentitySelectsDelegateAndClosesCpu() {
        val manifest = manifest()
        val cpu = FakeBackend(manifest)
        val delegate = FakeBackend(manifest)
        val identity = VerifiedInferenceIdentity.create(manifest, ByteBuffer.wrap(modelBytes))

        val selection = InferenceBackendIdentityConformanceSelector.select(
            cpu, delegate, identity)

        assertTrue(selection.usingDelegate)
        assertSame(delegate, selection.backend)
        assertTrue(selection.report.delegateAccepted)
        assertEquals(listOf(InferenceTask.METRIC_DEPTH), selection.report.expectedTasks)
        assertTrue(cpu.closed)
        assertFalse(delegate.closed)
    }

    @Test
    fun delegateManifestDifferenceFallsBackToOwnedCpu() {
        val expectedManifest = manifest()
        val cpu = FakeBackend(expectedManifest)
        val delegate = FakeBackend(manifest(version = "2"))
        val identity = VerifiedInferenceIdentity.create(expectedManifest,
            ByteBuffer.wrap(modelBytes))

        val selection = InferenceBackendIdentityConformanceSelector.select(
            cpu, delegate, identity)

        assertFalse(selection.usingDelegate)
        assertSame(cpu, selection.backend)
        assertEquals(InferenceIdentityFailure.DELEGATE_MANIFEST_MISMATCH,
            selection.report.failure)
        assertFalse(cpu.closed)
        assertTrue(delegate.closed)
    }

    @Test
    fun exactTaskDifferenceRejectsDelegate() {
        val expectedManifest = manifest()
        val cpu = FakeBackend(expectedManifest)
        val delegate = FakeBackend(manifest(tasks = setOf(InferenceTask.RELATIVE_DEPTH)))
        val identity = VerifiedInferenceIdentity.create(expectedManifest,
            ByteBuffer.wrap(modelBytes))

        val selection = InferenceBackendIdentityConformanceSelector.select(
            cpu, delegate, identity)

        assertFalse(selection.usingDelegate)
        assertTrue(delegate.closed)
        assertFalse(cpu.closed)
    }

    @Test
    fun cpuIdentityMismatchClosesBothAndAborts() {
        val expectedManifest = manifest()
        val cpu = FakeBackend(manifest(version = "wrong-cpu"))
        val delegate = FakeBackend(expectedManifest)
        val identity = VerifiedInferenceIdentity.create(expectedManifest,
            ByteBuffer.wrap(modelBytes))

        try {
            InferenceBackendIdentityConformanceSelector.select(cpu, delegate, identity)
            fail("Expected CPU identity failure")
        } catch (_: IllegalArgumentException) {
            assertTrue(cpu.closed)
            assertTrue(delegate.closed)
        }
    }

    @Test
    fun identityCannotBeMintedFromDifferentModelBytes() {
        val manifest = manifest()
        val bytes = ByteBuffer.wrap("different-artifact".toByteArray())
        val originalPosition = bytes.position()

        try {
            VerifiedInferenceIdentity.create(manifest, bytes)
            fail("Expected model verification failure")
        } catch (_: IllegalArgumentException) {
            assertEquals(originalPosition, bytes.position())
        }
    }

    private fun manifest(
        version: String = "1",
        tasks: Set<InferenceTask> = setOf(InferenceTask.METRIC_DEPTH),
    ): ModelManifest = ModelManifest(
        modelId = "identity-test",
        modelVersion = version,
        sha256 = sha256(modelBytes),
        licenseSpdx = "Apache-2.0",
        sourceUrl = "https://example.com/model",
        tasks = tasks,
        inputWidth = 2,
        inputHeight = 2,
        inputChannels = 1,
        inputMean = listOf(0.5),
        inputStd = listOf(0.25),
        quantization = TensorQuantization.FLOAT32,
        depthUnitToMeters = if (InferenceTask.METRIC_DEPTH in tasks) 1.0 else null,
        modelByteSize = modelBytes.size.toLong(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class FakeBackend(override val manifest: ModelManifest) : OnDeviceInferenceBackend {
        var closed = false
        override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput =
            error("Identity selection must not execute inference")
        override fun close() { closed = true }
    }
}
