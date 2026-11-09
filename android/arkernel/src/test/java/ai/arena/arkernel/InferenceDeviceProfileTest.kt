package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceDeviceProfileTest {
    @Test
    fun lookupRequiresExactCapabilityBackendVersionAndDriver() {
        val profile = profile()
        val manifest = manifest(listOf(profile))
        assertEquals(profile, manifest.measuredDeviceProfile(
            "a".repeat(64), "litert-gpu", "2.1.0", "b".repeat(64)))
        assertNull(manifest.measuredDeviceProfile(
            "c".repeat(64), "litert-gpu", "2.1.0", "b".repeat(64)))
        assertNull(manifest.measuredDeviceProfile(
            "a".repeat(64), "litert-gpu", "2.1.1", "b".repeat(64)))
        assertNull(manifest.measuredDeviceProfile(
            "a".repeat(64), "litert-gpu", "2.1.0", "c".repeat(64)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateExecutionEnvironmentProfilesAreRejected() {
        manifest(listOf(profile(), profile().copy(averageLatencyMs = 8.0,
            p95LatencyMs = 11.0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelNamePatternCannotReplaceCapabilityHash() {
        profile().copy(capabilitySignatureSha256 = "Pixel*")
    }

    @Test
    fun exactManifestHashBindsDriverFingerprint() {
        val first = manifest(listOf(profile())).exactIdentity().stableSha256()
        val second = manifest(listOf(profile().copy(
            driverFingerprintSha256 = "c".repeat(64)))).exactIdentity().stableSha256()
        assertNotEquals(first, second)
    }

    private fun profile() = InferenceDeviceProfile(
        capabilitySignatureSha256 = "a".repeat(64),
        backendId = "litert-gpu",
        backendVersion = "2.1.0",
        driverFingerprintSha256 = "b".repeat(64),
        averageLatencyMs = 7.0,
        p95LatencyMs = 10.0,
        peakWorkingMemoryBytes = 32L * 1024L * 1024L,
        sampleCount = 100)

    private fun manifest(profiles: List<InferenceDeviceProfile>) = ModelManifest(
        modelId = "depth-small",
        modelVersion = "1",
        sha256 = "d".repeat(64),
        licenseSpdx = "Apache-2.0",
        sourceUrl = "https://example.com/model.tflite",
        tasks = setOf(InferenceTask.METRIC_DEPTH),
        inputWidth = 16,
        inputHeight = 16,
        inputChannels = 1,
        inputMean = listOf(0.0),
        inputStd = listOf(1.0),
        quantization = TensorQuantization.FLOAT32,
        depthUnitToMeters = 1.0,
        modelByteSize = 1,
        deviceProfiles = profiles)
}
