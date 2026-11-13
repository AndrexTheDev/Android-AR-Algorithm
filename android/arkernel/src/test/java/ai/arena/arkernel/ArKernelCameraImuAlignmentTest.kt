package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelCameraImuAlignmentTest {

    companion object {
        private const val LAG_MIN_MS = -200
        private const val LAG_STEP_MS = 10
        private const val POINTS = 41

        /**
         * A curve that peaks at [peakLagMs] on [peakAxis] and is unremarkable
         * everywhere else, the way a real one looks when the scene has texture
         * and the motion is a clean pan.
         */
        private fun curve(
            peakAxis: ArKernelCameraImuAxis = ArKernelCameraImuAxis.Y,
            peakLagMs: Int = -20,
            peakCorrelation: Int = 930,
            lagMinMs: Int = LAG_MIN_MS,
            lagStepMs: Int = LAG_STEP_MS,
            points: Int = POINTS,
        ): List<ArKernelCameraImuCurveRow> {
            val rows = ArrayList<ArKernelCameraImuCurveRow>()
            for (axis in ArKernelCameraImuAxis.values()) {
                for (index in 0 until points) {
                    val lagMs = lagMinMs + index * lagStepMs
                    val correlation =
                        if (axis == peakAxis && lagMs == peakLagMs) {
                            peakCorrelation
                        } else {
                            20 + (index * 7 + axis.ordinal * 3) % 60
                        }
                    rows.add(
                        ArKernelCameraImuCurveRow(
                            axis = axis,
                            index = index,
                            lagMs = lagMs,
                            correlationPermille = correlation,
                        ))
                }
            }
            return rows
        }

        private fun record(
            frameCount: Int = 240,
            gyroSampleCount: Int = 6_000,
            gyroRateMedianHz: Long = 200L,
            imageRateRms: Double = 12.5,
            motionRangeDps: Double = 90.0,
            lagMinMs: Int = LAG_MIN_MS,
            lagStepMs: Int = LAG_STEP_MS,
            rows: List<ArKernelCameraImuCurveRow> = curve(),
        ): ArKernelCameraImuAlignment = ArKernelCameraImuAlignment(
            runId = "camimu.example",
            deviceToken = "phone.test",
            cameraId = "0",
            cameraLevel = ArKernelCameraLevel.LIMITED,
            cameraFacing = ArKernelCameraFacing.BACK,
            sensorOrientationDeg = 90,
            profileWidth = 128,
            profileHeight = 8,
            frameCount = frameCount,
            gyroSampleCount = gyroSampleCount,
            durationMs = 8_000L,
            frameIntervalMedianUs = 33_333L,
            gyroRateMedianHz = gyroRateMedianHz,
            imageRateRms = imageRateRms,
            gyroRateRms = 48.0,
            motionRangeDps = motionRangeDps,
            timestampDomain = CameraTimestampDomain.REALTIME,
            domainOffsetMs = 4_203L,
            lagMinMs = lagMinMs,
            lagStepMs = lagStepMs,
            curve = rows,
        )

        private fun textOf(value: ArKernelCameraImuAlignment): String =
            ArKernelCameraImuAdapter.encode(value)
    }

    @Test
    fun aHealthyRunRoundTrips() {
        val built = record()
        val text = textOf(built)
        val parsed = ArKernelCameraImuAdapter.parse(text)
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(text, textOf(parsed.record!!))
    }

    @Test
    fun aHealthyRunIsAligned() {
        val built = record()
        assertTrue(built.framesOk)
        assertTrue(built.gyroOk)
        assertTrue(built.motionOk)
        assertTrue(built.correlationOk)
        assertTrue(built.peakInteriorOk)
        assertTrue(built.alignmentOk)
        assertTrue(built.outstandingSteps().isEmpty())
    }

    @Test
    fun aNegativeLagMeansTheFrameTimestampsAreLate() {
        // The image agrees with what the gyroscope reported 20 ms earlier,
        // so the frames must have been stamped 20 ms after they were taken.
        val built = record(rows = curve(peakLagMs = -20))
        assertEquals(-20, built.bestLagMs)
        assertEquals(20_000L, built.estimatedOffsetUs)
        assertEquals(ArKernelCameraImuAxis.Y, built.bestAxis)
        assertEquals(930, built.bestCorrelationPermille)
    }

    @Test
    fun aPositiveLagMeansTheFrameTimestampsAreEarly() {
        val built = record(rows = curve(peakLagMs = 30))
        assertEquals(30, built.bestLagMs)
        assertEquals(-30_000L, built.estimatedOffsetUs)
    }

    @Test
    fun theAxisAndItsSignAreReported() {
        val with = record(rows = curve(
            peakAxis = ArKernelCameraImuAxis.Z, peakCorrelation = 880))
        assertEquals(ArKernelCameraImuAxis.Z, with.bestAxis)
        assertEquals(1, with.bestAxisSign)
        val against = record(rows = curve(
            peakAxis = ArKernelCameraImuAxis.X, peakCorrelation = -880))
        assertEquals(ArKernelCameraImuAxis.X, against.bestAxis)
        assertEquals(-1, against.bestAxisSign)
        assertTrue(against.correlationOk)
    }

    @Test
    fun tooFewFramesFailAndNameFrames() {
        val short = record(frameCount = 60)
        assertFalse(short.framesOk)
        assertEquals("frames", short.outstandingSteps().first())
    }

    @Test
    fun aSlowGyroscopeFails() {
        val slow = record(gyroRateMedianHz = 20L)
        assertFalse(slow.gyroOk)
        assertEquals(listOf("gyro"), slow.outstandingSteps())
        assertTrue(record(gyroRateMedianHz = 50L).gyroOk)
    }

    @Test
    fun aStillRunIsNotAMeasurement() {
        val still = record(motionRangeDps = 4.0)
        assertFalse(still.motionOk)
        assertEquals(listOf("motion"), still.outstandingSteps())
        val noOpticalFlow = record(imageRateRms = 0.0)
        assertFalse(noOpticalFlow.motionOk)
    }

    @Test
    fun aWeakCorrelationFailsAndNamesTheScene() {
        val weak = record(rows = curve(peakCorrelation = 520))
        assertFalse(weak.correlationOk)
        assertEquals(listOf("scene"), weak.outstandingSteps())
        assertTrue(record(rows = curve(peakCorrelation = 700)).correlationOk)
    }

    @Test
    fun aPeakAtTheEdgeOfTheWindowHasNotBeenFound() {
        val edge = record(rows = curve(peakLagMs = LAG_MIN_MS))
        assertFalse(edge.peakInteriorOk)
        assertEquals(listOf("range"), edge.outstandingSteps())
    }

    @Test
    fun thePeakMustStandClearOfTheRestOfTheCurve() {
        val built = record()
        assertTrue(built.peakMarginPermille > 0)
        assertEquals(
            kotlin.math.abs(built.bestCorrelationPermille) -
                built.curveMeanAbsPermille,
            built.peakMarginPermille)
    }

    @Test
    fun aStatisticsMismatchIsRejected() {
        val text = textOf(record())
            .replace("best_lag_ms=-20", "best_lag_ms=-10")
        val parsed = ArKernelCameraImuAdapter.parse(text)
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelCameraImuRejection.STATISTICS_MISMATCH,
            parsed.rejection)
    }

    @Test
    fun aRaisedQualityGradeIsRejected() {
        val text = textOf(record()).replace(
            "quality_grade=unverified", "quality_grade=verified")
        val parsed = ArKernelCameraImuAdapter.parse(text)
        assertEquals(
            ArKernelCameraImuRejection.INVALID_QUALITY, parsed.rejection)
    }

    @Test
    fun claimingToKeepFramesIsRejected() {
        val text = textOf(record()).replace(
            "no_frames_retained=true", "no_frames_retained=false")
        val parsed = ArKernelCameraImuAdapter.parse(text)
        assertEquals(
            ArKernelCameraImuRejection.UNSAFE_INVARIANT, parsed.rejection)
    }

    @Test
    fun trustingManufacturerIntrinsicsIsRejected() {
        val text = textOf(record()).replace(
            "manufacturer_intrinsics_trusted=false",
            "manufacturer_intrinsics_trusted=true")
        val parsed = ArKernelCameraImuAdapter.parse(text)
        assertEquals(
            ArKernelCameraImuRejection.UNSAFE_INVARIANT, parsed.rejection)
    }

    @Test
    fun anUnevenCurveIsRejected() {
        val rows = curve().dropLast(1)
        val parsed = kotlin.runCatching { record(rows = rows) }
        assertTrue(parsed.isFailure)
    }

    @Test
    fun aReorderedKeyIsRejected() {
        val text = textOf(record()).replace(
            "version=1\nrun_id=camimu.example",
            "run_id=camimu.example\nversion=1")
        val parsed = ArKernelCameraImuAdapter.parse(text)
        assertEquals(
            ArKernelCameraImuRejection.INVALID_SCHEMA, parsed.rejection)
    }
}
