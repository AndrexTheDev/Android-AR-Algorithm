package ai.arena.arkernel

/** Deterministic scalar fault classes for admission/replay tests; never used as device evidence. */
enum class SyntheticCompatibilityFault {
    CAMERA_TIMESTAMP_REGRESSION,
    CAMERA_FRAME_GAPS,
    CAPTURE_RESULT_DROPOUT,
    ACCELEROMETER_DROPOUT,
    GYROSCOPE_DROPOUT,
    ACCELEROMETER_TIMESTAMP_REGRESSION,
    GYROSCOPE_TIMESTAMP_REGRESSION,
    IMU_CALLBACK_LATENCY,
    CAMERA_AMBIGUOUS_TIMEBASE,
    CAMERA_IMU_WINDOWS_DISJOINT,
}

class SyntheticCompatibilityScenario(
    val scenarioId: String,
    faults: Set<SyntheticCompatibilityFault> = emptySet(),
    val cameraSamples: Int = 60,
    val imuSamplesPerSensor: Int = 200,
) {
    val faults: Set<SyntheticCompatibilityFault> = faults.toSet()

    init {
        require(scenarioId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(this.faults.size <= 4)
        require(cameraSamples in 20..512)
        require(imuSamplesPerSensor in 50..2_048)
    }
}

data class SyntheticCompatibilityRun(
    val scenarioId: String,
    val offeredCameraSamples: Int,
    val acceptedCameraSamples: Int,
    val offeredImuSamples: Int,
    val acceptedImuSamples: Int,
    val report: ActiveCompatibilityReport,
)

/**
 * Generates a bounded healthy timing stream and applies explicit deterministic transforms.
 * This is a test source, not Camera2/IMU emulation and not evidence about physical hardware.
 */
class SyntheticCompatibilityFaultSource(
    private val scenario: SyntheticCompatibilityScenario,
) {
    fun run(probe: ActiveDeviceCompatibilityProbe): SyntheticCompatibilityRun {
        var offeredCamera = 0
        var acceptedCamera = 0
        var offeredImu = 0
        var acceptedImu = 0
        val cameraStart = 10_000_000_000L
        val framePeriod = 33_333_333L
        var injectedGapNs = 0L
        repeat(scenario.cameraSamples) { index ->
            if (SyntheticCompatibilityFault.CAMERA_FRAME_GAPS in scenario.faults &&
                index > 0 && index % 4 == 0) injectedGapNs += framePeriod * 3
            var timestamp = cameraStart + index * framePeriod + injectedGapNs
            if (SyntheticCompatibilityFault.CAMERA_TIMESTAMP_REGRESSION in scenario.faults &&
                index == scenario.cameraSamples / 2) timestamp -= framePeriod * 2
            val (elapsed, uptime) = cameraCallbacks(timestamp)
            val image = CameraTimingSample(CameraTimingSampleKind.IMAGE, timestamp,
                elapsed, uptime)
            offeredCamera++
            if (probe.addCameraSample(image)) acceptedCamera++
            val dropCapture = SyntheticCompatibilityFault.CAPTURE_RESULT_DROPOUT in
                scenario.faults && index % 3 == 0
            if (!dropCapture) {
                offeredCamera++
                if (probe.addCameraSample(image.copy(
                    kind = CameraTimingSampleKind.CAPTURE_RESULT))) acceptedCamera++
            }
        }

        val disjoint = SyntheticCompatibilityFault.CAMERA_IMU_WINDOWS_DISJOINT in scenario.faults
        val imuStart = if (disjoint)
            cameraStart + scenario.cameraSamples * framePeriod + 2_000_000_000L
        else cameraStart
        repeat(scenario.imuSamplesPerSensor) { index ->
            val baseTimestamp = imuStart + index * 10_000_000L
            val accelerationTimestamp = if (
                SyntheticCompatibilityFault.ACCELEROMETER_TIMESTAMP_REGRESSION in scenario.faults &&
                index == scenario.imuSamplesPerSensor / 2) baseTimestamp - 20_000_000L
            else baseTimestamp
            val gyroscopeTimestamp = if (
                SyntheticCompatibilityFault.GYROSCOPE_TIMESTAMP_REGRESSION in scenario.faults &&
                index == scenario.imuSamplesPerSensor / 2) baseTimestamp - 20_000_000L
            else baseTimestamp
            val highLatency = SyntheticCompatibilityFault.IMU_CALLBACK_LATENCY in scenario.faults
            val accelerationDropped = SyntheticCompatibilityFault.ACCELEROMETER_DROPOUT in
                scenario.faults && index % 10 != 0
            val gyroscopeDropped = SyntheticCompatibilityFault.GYROSCOPE_DROPOUT in
                scenario.faults && index % 10 != 0
            if (!accelerationDropped) {
                offeredImu++
                val callback = accelerationTimestamp + if (highLatency) 500_000_000L else 20_000_000L
                if (probe.addImuSample(ImuTimingSample(
                    ImuTimingSampleKind.ACCELEROMETER, accelerationTimestamp, callback)))
                    acceptedImu++
            }
            if (!gyroscopeDropped) {
                offeredImu++
                val callback = gyroscopeTimestamp + if (highLatency) 500_000_000L else 20_000_000L
                if (probe.addImuSample(ImuTimingSample(
                    ImuTimingSampleKind.GYROSCOPE, gyroscopeTimestamp, callback))) acceptedImu++
            }
        }
        return SyntheticCompatibilityRun(scenario.scenarioId, offeredCamera, acceptedCamera,
            offeredImu, acceptedImu, probe.finish())
    }

    private fun cameraCallbacks(timestampNs: Long): Pair<Long, Long> =
        if (SyntheticCompatibilityFault.CAMERA_AMBIGUOUS_TIMEBASE in scenario.faults)
            timestampNs + 20_000_000L to timestampNs - 20_000_000L
        else timestampNs + 20_000_000L to timestampNs - 5_000_000_000L
}

object SyntheticCompatibilityScenarioCatalog {
    fun healthy(): SyntheticCompatibilityScenario =
        SyntheticCompatibilityScenario("healthy")

    fun singleFaults(): List<SyntheticCompatibilityScenario> =
        SyntheticCompatibilityFault.entries.map { fault ->
            SyntheticCompatibilityScenario("single-${fault.name.lowercase()}", setOf(fault))
        }

    /** All bounded two-fault interactions, deterministic and independent of model names. */
    fun pairwiseFaults(): List<SyntheticCompatibilityScenario> {
        val faults = SyntheticCompatibilityFault.entries
        return buildList {
            for (first in faults.indices)
                for (second in first + 1 until faults.size) {
                    val a = faults[first]
                    val b = faults[second]
                    add(SyntheticCompatibilityScenario(
                        "pair-${a.name.lowercase()}-${b.name.lowercase()}", setOf(a, b)))
                }
        }
    }
}
