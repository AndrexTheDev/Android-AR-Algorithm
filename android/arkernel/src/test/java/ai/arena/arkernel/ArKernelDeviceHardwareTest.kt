package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelDeviceHardwareTest {

    companion object {
        private fun sensor(
            index: Int,
            kind: String,
            name: String = kind,
        ): ArKernelDeviceSensorRow = ArKernelDeviceSensorRow(
            index = index,
            kind = kind,
            name = name,
            vendor = "vendor",
            maxRange = 16.0,
            resolution = 0.001,
            minDelayUs = 5000,
            maxDelayUs = 100000,
            powerMa = 0.5,
            reportingMode = "continuous",
            wakeUp = false,
        )

        private fun sensors(): List<ArKernelDeviceSensorRow> = listOf(
            sensor(0, "accelerometer"),
            sensor(1, "gyroscope"),
            sensor(2, "magnetometer"),
        )

        private fun device(
            sensors: List<ArKernelDeviceSensorRow> = sensors(),
            availableMemoryMb: Long = 1_024L,
            freeStorageMb: Long = 8_192L,
            thermalStart: Int = 1,
            thermalEnd: Int = 2,
            rateEarly: Long = 1_000L,
            rateLate: Long = 950L,
            driftPpm: Long = 12L,
            violations: Int = 0,
            clockOffsetMs: Long = 4_203L,
        ): ArKernelDeviceHardware = ArKernelDeviceHardware(
            runId = "devrun.test",
            deviceToken = "phone.test",
            apiLevel = 35,
            manufacturer = "Example",
            model = "Model",
            device = "device",
            board = "board",
            hardware = "hardware",
            totalMemoryMb = 4_096L,
            availableMemoryMb = availableMemoryMb,
            lowRamDevice = false,
            freeStorageMb = freeStorageMb,
            totalStorageMb = 65_536L,
            batteryPercent = 78,
            batteryCharging = false,
            thermalStatusStart = thermalStart,
            thermalStatusEnd = thermalEnd,
            loadDurationMs = 10_000L,
            loadRateEarly = rateEarly,
            loadRateLate = rateLate,
            clockOffsetMs = clockOffsetMs,
            clockDriftPpm = driftPpm,
            monotonicViolations = violations,
            sensors = sensors,
        )

        private fun textOf(value: ArKernelDeviceHardware): String =
            ArKernelDeviceHardwareAdapter.encode(value)
    }

    @Test
    fun aHealthyDeviceRoundTrips() {
        val built = device()
        val text = textOf(built)
        val parsed = ArKernelDeviceHardwareAdapter.parse(text)
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(text, textOf(parsed.record!!))
    }

    @Test
    fun aHealthyDeviceIsSufficient() {
        val built = device()
        assertTrue(built.sensorsOk)
        assertTrue(built.storageOk)
        assertTrue(built.memoryOk)
        assertTrue(built.thermalOk)
        assertTrue(built.throttlingOk)
        assertTrue(built.clockOk)
        assertTrue(built.deviceSufficient)
        assertTrue(built.outstandingSteps().isEmpty())
    }

    @Test
    fun theKernelNeedsAnAccelerometerAndAGyroscope() {
        val noGyro = device(sensors = listOf(sensor(0, "accelerometer")))
        assertFalse(noGyro.requiredSensorsPresent)
        assertFalse(noGyro.sensorsOk)
        assertEquals("sensors", noGyro.outstandingSteps().first())
        val both = device()
        assertTrue(both.requiredSensorsPresent)
    }

    @Test
    fun aThrottledDeviceFailsAndNamesCooling() {
        val throttled = device(rateEarly = 1_000L, rateLate = 700L)
        assertEquals(700L, throttled.loadRateRatioPermille)
        assertFalse(throttled.throttlingOk)
        assertEquals(listOf("cooling"), throttled.outstandingSteps())
        // Eight hundred permille is still within budget.
        assertTrue(device(rateLate = 800L).throttlingOk)
    }

    @Test
    fun aFullDiskFailsBeforeAnythingElseIsMeasured() {
        val full = device(freeStorageMb = 40L)
        assertFalse(full.storageOk)
        assertFalse(full.memoryOk.not())
        assertEquals(listOf("storage"), full.outstandingSteps())
    }

    @Test
    fun aBackwardsClockFailsEvenWhenTheDriftIsSmall() {
        val broken = device(violations = 1)
        assertFalse(broken.clockOk)
        assertEquals("clock", broken.outstandingSteps().first())
    }

    @Test
    fun aDriftingClockFails() {
        assertFalse(device(driftPpm = 1_500L).clockOk)
        assertFalse(device(driftPpm = -1_500L).clockOk)
        assertTrue(device(driftPpm = 1_000L).clockOk)
    }

    @Test
    fun aSilentThermalSensorIsNotFailedForBeingSilent() {
        val silent = device(thermalStart = -1, thermalEnd = -1)
        assertTrue("no evidence either way", silent.thermalOk)
    }

    @Test
    fun aSevereThermalStatusFails() {
        assertFalse(device(thermalEnd = 5).thermalOk)
        assertTrue(device(thermalEnd = 3).thermalOk)
    }

    @Test
    fun aStatisticsMismatchIsRejected() {
        val text = textOf(device()).replace(
            "load_rate_ratio_permille=950",
            "load_rate_ratio_permille=951")
        val parsed = ArKernelDeviceHardwareAdapter.parse(text)
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelDeviceHardwareRejection.STATISTICS_MISMATCH,
            parsed.rejection)
    }

    @Test
    fun aRaisedQualityGradeIsRejected() {
        val text = textOf(device()).replace(
            "quality_grade=unverified", "quality_grade=verified")
        val parsed = ArKernelDeviceHardwareAdapter.parse(text)
        assertEquals(
            ArKernelDeviceHardwareRejection.INVALID_QUALITY,
            parsed.rejection)
    }

    @Test
    fun claimingToRetainSamplesIsRejected() {
        val text = textOf(device()).replace(
            "no_samples_retained=true", "no_samples_retained=false")
        val parsed = ArKernelDeviceHardwareAdapter.parse(text)
        assertEquals(
            ArKernelDeviceHardwareRejection.UNSAFE_INVARIANT,
            parsed.rejection)
    }

    @Test
    fun nonContiguousSensorIndicesAreRejected() {
        val text = textOf(device()).replace(
            "sensor=1|gyroscope|gyroscope|vendor",
            "sensor=2|gyroscope|gyroscope|vendor")
        val parsed = ArKernelDeviceHardwareAdapter.parse(text)
        assertEquals(
            ArKernelDeviceHardwareRejection.DUPLICATE_SENSOR_INDEX,
            parsed.rejection)
    }

    @Test
    fun namesAreFoldedIntoTheRecordAlphabet() {
        assertEquals("Google.Pixel.8",
            ArKernelDeviceHardwareAdapter.name("Google Pixel 8"))
        assertEquals("unknown",
            ArKernelDeviceHardwareAdapter.name("!!!"))
        assertTrue(
            ArKernelDeviceHardwareAdapter.name("x".repeat(200)).length <= 64)
    }

    @Test
    fun aReorderedKeyIsRejected() {
        val text = textOf(device()).replace(
            "version=1\nrun_id=devrun.test",
            "run_id=devrun.test\nversion=1")
        val parsed = ArKernelDeviceHardwareAdapter.parse(text)
        assertEquals(
            ArKernelDeviceHardwareRejection.INVALID_SCHEMA,
            parsed.rejection)
    }
}
