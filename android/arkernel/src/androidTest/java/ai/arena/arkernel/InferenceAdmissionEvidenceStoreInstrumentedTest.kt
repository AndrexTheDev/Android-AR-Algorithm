package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InferenceAdmissionEvidenceStoreInstrumentedTest {
    private val modelBytes = "instrumented-evidence-model".toByteArray()

    @Test
    fun atomicallyReadsOnlyExactUnexpiredEvidenceAndRejectsCorruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = "inference-admission-instrumented.bin"
        val store = InferenceAdmissionEvidenceStore(context, fileName)
        val identity = VerifiedInferenceIdentity.create(manifest(), ByteBuffer.wrap(modelBytes))
        val signature = signature()
        val evidence = PersistedInferenceAdmissionEvidence.fromAccepted(identity, signature,
            "b".repeat(64), "adapter", "1", "software", 3, acceptedResult(),
            1_000, 1_000)
        store.clear()
        try {
            store.write(evidence)
            assertNotNull(store.readExact(identity, signature, "b".repeat(64), "adapter",
                "1", "software", 3, 1_500))
            assertNull(store.readExact(identity, signature, "b".repeat(64), "adapter",
                "2", "software", 3, 1_500))
            assertNull(store.readExact(identity, signature, "b".repeat(64), "adapter",
                "1", "software", 3, 2_000))

            File(context.noBackupFilesDir, fileName).writeBytes(byteArrayOf(1, 2, 3))
            assertNull(store.readExact(identity, signature, "b".repeat(64), "adapter",
                "1", "software", 3, 1_500))
        } finally {
            store.clear()
        }
    }

    private fun acceptedResult(): VerifiedInferenceBackendAdmissionResult {
        val report = InferenceSemanticConformanceReport(true, 1, 1, 0,
            0.0, 0.0, 0.0, 0.0, 0.0, null, null)
        return VerifiedInferenceBackendAdmissionResult(FakeBackend(manifest()), true,
            InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_ACCEPTED, report)
    }

    private fun manifest() = ModelManifest(modelId = "instrumented-evidence",
        modelVersion = "1", sha256 = sha256(modelBytes), licenseSpdx = "Apache-2.0",
        sourceUrl = "https://example.com/instrumented-evidence",
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
