package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCoveragePlannerTest {
    @Test
    fun classificationUsesCapabilitiesNotDeviceOrCameraNames() {
        val first = signature("a".repeat(64), "rear-main",
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
        val second = signature("b".repeat(64), "vendor-camera-9",
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)

        assertEquals(CapabilityCoveragePlanner.classify(first),
            CapabilityCoveragePlanner.classify(second))
    }

    @Test
    fun weightedBudgetPrioritizesRareHighRiskCapabilityClass() {
        val normal = CapabilityTestCandidate("lab-normal", signature("a".repeat(64), "0",
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME))
        val risky = CapabilityTestCandidate("lab-risky", signature("b".repeat(64), "1",
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
            lowRam = true, metadata = false))

        val plan = CapabilityCoveragePlanner.plan(listOf(normal, risky), 1)

        assertEquals(listOf("lab-risky"), plan.selections.map { it.candidateId })
        assertFalse(plan.complete)
        assertTrue(plan.coveredWeight > 0)
        assertTrue(plan.uncoveredRequirements.isNotEmpty())
    }

    @Test
    fun fullInventoryBudgetCoversEveryObservedSingleAndPairwisePartition() {
        val candidates = inventory()

        val plan = CapabilityCoveragePlanner.plan(candidates, candidates.size)

        assertTrue(plan.complete)
        assertEquals(plan.totalRequirements, plan.coveredRequirements)
        assertEquals(plan.totalWeight, plan.coveredWeight)
        assertTrue(plan.totalRequirements > 55)
        assertEquals(plan.selections.size,
            plan.selections.map { it.candidateId }.distinct().size)
    }

    @Test
    fun planIsStableAcrossInventoryOrderingAndContainsCapabilityPairs() {
        val candidates = inventory()
        val forward = CapabilityCoveragePlanner.plan(candidates, 2)
        val reverse = CapabilityCoveragePlanner.plan(candidates.reversed(), 2)

        assertEquals(forward, reverse)
        assertTrue(forward.uncoveredRequirements.all { it.features.size in 1..2 })
        assertTrue(forward.totalRequirements >
            CapabilityCoverageDimension.entries.size)
    }

    private fun inventory() = listOf(
        CapabilityTestCandidate("candidate-a", signature("a".repeat(64), "0",
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)),
        CapabilityTestCandidate("candidate-b", signature("b".repeat(64), "1",
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
            lowRam = true, metadata = false)),
        CapabilityTestCandidate("candidate-c", signature("c".repeat(64), "2",
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            api = 29, memoryMb = 256, physicalIds = listOf("2a", "2b"))),
        CapabilityTestCandidate("candidate-d", DeviceCapabilitySignature(apiLevel = 26,
            supportedAbis = listOf("armeabi-v7a"), lowRamDevice = true,
            memoryClassMb = 128, buildFingerprintSha256 = "d".repeat(64), camera = null,
            imu = ImuCapabilityFacts(false, false, -1, -1, -1, -1, 0f, 0f))),
    )

    private fun signature(buildHash: String, cameraId: String, hardwareLevel: Int,
        timestampSource: Int, lowRam: Boolean = false, metadata: Boolean = true,
        api: Int = 35, memoryMb: Int = 512,
        physicalIds: List<String> = emptyList()): DeviceCapabilitySignature {
        val camera = CameraCapabilityFacts(cameraId, physicalIds, 640, 480,
            hardwareLevel, timestampSource, 90, 4000, 3000, 4000, 3000,
            33_333_333L, listOf(4f), listOf(0), listOf(0), listOf(0),
            metadata, metadata, metadata)
        return DeviceCapabilitySignature(apiLevel = api,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = lowRam,
            memoryClassMb = memoryMb, buildFingerprintSha256 = buildHash,
            camera = camera, imu = ImuCapabilityFacts(true, true, 5_000, 5_000,
                0, 0, 0.001f, 0.001f))
    }
}
