package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelCameraTimingTest {

    companion object {
        private const val FRAME = 33_000_000L
        private const val EXPOSURE = 10_000_000L

        private fun rows(
            count: Int,
            intervalNs: Long = FRAME,
            gapAt: Int = -1,
            gapNs: Long = 0L,
            exposureNs: Long = EXPOSURE,
            iso: Long = 400L,
        ): List<ArKernelCameraFrameRow> {
            val out = ArrayList<ArKernelCameraFrameRow>(count)
            var timestamp = 1_000_000_000L
            for (index in 0 until count) {
                val interval = when {
                    index == 0 -> 0L
                    index == gapAt -> gapNs
                    else -> intervalNs
                }
                out.add(
                    ArKernelCameraFrameRow(
                        index = index,
                        timestampNs = timestamp,
                        intervalNs = interval,
                        exposureNs = exposureNs,
                        sensitivityIso = iso,
                        focusDistanceDiopters = 0.5,
                    ))
                timestamp += if (index == 0) intervalNs else interval
            }
            return out
        }

        private fun record(
            frameRows: List<ArKernelCameraFrameRow> = rows(30),
            domain: CameraTimestampDomain? = CameraTimestampDomain.UPTIME,
            timestampFailure: CameraTimestampFailureReason? = null,
            captureFailure: CameraCalibrationRuntimeFailure? = null,
            rollingShutterSkewNs: Long = 2_000_000L,
        ): ArKernelCameraTiming = ArKernelCameraTiming(
            runId = "camrun.test",
            deviceToken = "phone.test",
            cameraId = "0",
            cameraLevel = ArKernelCameraLevel.FULL,
            cameraFacing = ArKernelCameraFacing.BACK,
            physicalCameraIds = "0",
            sensorOrientationDeg = 90,
            width = 1920,
            height = 1080,
            timestampDomain = domain,
            domainOffsetNs = 1_234_000L,
            timestampFailure = timestampFailure,
            focusDistanceDiopters = 0.5,
            focalLengthMm = 4.2,
            rollingShutterSkewNs = rollingShutterSkewNs,
            captureFailure = captureFailure,
            rows = frameRows,
        )

        private fun textOf(value: ArKernelCameraTiming): String =
            ArKernelCameraTimingAdapter.encode(value)
    }

    @Test
    fun aSteadyRunRoundTrips() {
        val built = record()
        val text = textOf(built)
        val parsed = ArKernelCameraTimingAdapter.parse(text)
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(text, textOf(parsed.record!!))
    }

    @Test
    fun aSteadyRunIsSufficient() {
        val built = record()
        assertTrue(built.framesOk)
        assertTrue(built.cadenceOk)
        assertTrue(built.timestampDomainOk)
        assertTrue(built.exposureOk)
        assertTrue(built.captureStableOk)
        assertTrue(built.cameraSufficient)
        assertTrue(built.outstandingSteps().isEmpty())
    }

    @Test
    fun statisticsComeFromTheFramesNotFromTheCaller() {
        val built = record(rows(30))
        assertEquals(FRAME, built.intervalMedianNs)
        assertEquals(FRAME, built.intervalMeanNs)
        assertEquals(0L, built.intervalJitterNs)
        assertEquals(FRAME, built.intervalMaxGapNs)
        assertEquals(EXPOSURE, built.exposureMedianNs)
        assertEquals(400L, built.sensitivityMedian)
    }

    @Test
    fun aHoleWiderThanThreeIntervalsFailsCadence() {
        val steady = record(rows(30, gapAt = 15, gapNs = FRAME * 3))
        assertTrue("three intervals is still interpolation", steady.cadenceOk)
        val hole = record(rows(30, gapAt = 15, gapNs = FRAME * 3 + 1))
        assertFalse(hole.cadenceOk)
        assertFalse(hole.cameraSufficient)
        assertEquals(listOf("cadence"), hole.outstandingSteps())
    }

    @Test
    fun anUnknownClockDomainFailsAndIsReportedFirst() {
        val built = record(domain = null)
        assertFalse(built.timestampDomainOk)
        assertEquals("UNKNOWN", ArKernelCameraTimingAdapter
            .report(built).timestampDomain)
        assertEquals("clock", built.outstandingSteps().first())
    }

    @Test
    fun aContradictedClockFailsEvenWhenADomainWasNamed() {
        val built = record(
            domain = CameraTimestampDomain.UPTIME,
            timestampFailure =
                CameraTimestampFailureReason.SESSION_OFFSET_CHANGED,
        )
        assertFalse(built.timestampDomainOk)
    }

    @Test
    fun aLongExposureFailsAndNamesTheSmear() {
        val built = record(rows(30, exposureNs = 33_000_001L))
        assertFalse(built.exposureOk)
        assertEquals(listOf("exposure"), built.outstandingSteps())
        // 33 ms at 90 deg/s smears the frame by about three degrees.
        val smear = built.smearDegPer90DegPerSec
        assertTrue("expected about 3 degrees, got $smear", smear > 2.9 && smear < 3.1)
    }

    @Test
    fun smearUsesTheMedianExposureNotTheWorst() {
        val rows = rows(29) + ArKernelCameraFrameRow(
            index = 29,
            timestampNs = 1_000_000_000L + FRAME * 29,
            intervalNs = FRAME,
            exposureNs = 100_000_000L,
            sensitivityIso = 400L,
            focusDistanceDiopters = 0.5,
        )
        val built = record(rows)
        assertEquals(EXPOSURE, built.exposureMedianNs)
        assertEquals(100_000_000L, built.exposureMaxNs)
        assertFalse(built.exposureOk)
        val smear = built.smearDegPer90DegPerSec
        assertTrue("expected about 0.9 degrees, got $smear", smear < 1.0)
    }

    @Test
    fun aCapturePipelineThatMovedFailsStability() {
        val built = record(
            captureFailure =
                CameraCalibrationRuntimeFailure.CROP_REGION_CHANGED)
        assertFalse(built.captureStableOk)
        assertEquals(listOf("stability"), built.outstandingSteps())
    }

    @Test
    fun tooFewFramesIsRejectedRatherThanJudged() {
        var threw = false
        try {
            record(rows(7))
        } catch (error: IllegalArgumentException) {
            threw = true
        }
        assertTrue("fewer than eight frames cannot lock a clock", threw)
    }

    @Test
    fun nonMonotonicTimestampsAreRejected() {
        val text = textOf(record())
        val broken = text.replace(
            "frame=2|1066000000|33000000|10000000|400|0.500000",
            "frame=2|1000000000|33000000|10000000|400|0.500000",
        )
        val parsed = ArKernelCameraTimingAdapter.parse(broken)
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelCameraTimingRejection.NON_MONOTONIC_TIMESTAMP,
            parsed.rejection)
    }

    @Test
    fun aStatisticsMismatchIsRejected() {
        val text = textOf(record()).replace(
            "interval_max_gap_ns=33000000", "interval_max_gap_ns=33000001")
        val parsed = ArKernelCameraTimingAdapter.parse(text)
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelCameraTimingRejection.STATISTICS_MISMATCH,
            parsed.rejection)
    }

    @Test
    fun trustingManufacturerIntrinsicsIsRejected() {
        val text = textOf(record()).replace(
            "manufacturer_intrinsics_trusted=false",
            "manufacturer_intrinsics_trusted=true")
        val parsed = ArKernelCameraTimingAdapter.parse(text)
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelCameraTimingRejection.UNSAFE_INVARIANT,
            parsed.rejection)
    }

    @Test
    fun aRaisedQualityGradeIsRejected() {
        val text = textOf(record()).replace(
            "quality_grade=unverified", "quality_grade=verified")
        val parsed = ArKernelCameraTimingAdapter.parse(text)
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelCameraTimingRejection.INVALID_QUALITY,
            parsed.rejection)
    }

    @Test
    fun anUnknownLevelAndFacingAreAcceptedWithoutPretending() {
        val built = record().copy(
            cameraLevel = ArKernelCameraLevel.UNKNOWN,
            cameraFacing = ArKernelCameraFacing.UNKNOWN,
        )
        val parsed = ArKernelCameraTimingAdapter.parse(textOf(built))
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(ArKernelCameraLevel.UNKNOWN, parsed.record!!.cameraLevel)
    }

    @Test
    fun aReorderedKeyIsRejected() {
        val text = textOf(record()).replace(
            "version=1\nrun_id=camrun.test",
            "run_id=camrun.test\nversion=1")
        val parsed = ArKernelCameraTimingAdapter.parse(text)
        assertEquals(
            ArKernelCameraTimingRejection.INVALID_SCHEMA,
            parsed.rejection)
    }
}
