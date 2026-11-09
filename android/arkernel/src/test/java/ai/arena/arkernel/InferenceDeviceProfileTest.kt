package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceDeviceProfileTest {
    @Test
    fun canonicalDriverIdentityIsStableAndBindsObservableFields() {
        val first = driver()
        val same = driver()
        val changed = driver(version = "2")
        assertEquals(first.fingerprintSha256, same.fingerprintSha256)
        assertEquals(64, first.fingerprintSha256.length)
        assertNotEquals(first.fingerprintSha256, changed.fingerprintSha256)
        assertNotEquals("test-gpu", first.acceleratorNameSha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wildcardDriverProviderIsRejected() {
        driver().copy(providerId = "vendor*")
    }

    @Test
    fun lookupRequiresExactCapabilityBackendVersionAndDriver() {
        val profile = profile()
        val manifest = manifest(listOf(profile))
        assertEquals(profile, manifest.measuredDeviceProfile(
            "a".repeat(64), "litert-gpu", "2.1.0", driver()))
        assertNull(manifest.measuredDeviceProfile(
            "c".repeat(64), "litert-gpu", "2.1.0", driver()))
        assertNull(manifest.measuredDeviceProfile(
            "a".repeat(64), "litert-gpu", "2.1.1", driver()))
        assertNull(manifest.measuredDeviceProfile(
            "a".repeat(64), "litert-gpu", "2.1.0", driver(version = "2")))
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
            driverIdentity = driver(version = "2")))).exactIdentity().stableSha256()
        assertNotEquals(first, second)
    }

    private fun profile() = InferenceDeviceProfile(
        capabilitySignatureSha256 = "a".repeat(64),
        backendId = "litert-gpu",
        backendVersion = "2.1.0",
        driverIdentity = driver(),
        averageLatencyMs = 7.0,
        p95LatencyMs = 10.0,
        peakWorkingMemoryBytes = 32L * 1024L * 1024L,
        sampleCount = 100)

    private fun driver(version: String = "1") = InferenceDriverIdentity(
        api = InferenceDriverApi.VULKAN,
        providerId = "test-vendor",
        driverVersion = version,
        acceleratorNameSha256 = InferenceDriverIdentity.hashObservedAcceleratorName("test-gpu"),
        featureLevel = 13)

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
