package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder's whole claim is that it can tell a scale that holds still from
 * one that drifts with distance. Each test below builds a ladder with a known
 * behaviour and asserts the verdict matches it.
 */
class ArKernelScaleLadderTest {

    companion object {
        /** A focal length, in pixels, that the synthetic rungs will imply. */
        private const val FOCAL_PX = 2_800.0

        /**
         * Rungs obeying f = (px/m) x d exactly, so px/m = f / d.
         * [driftPermillePerM] bends that on purpose.
         */
        private fun ladder(
            distancesMm: List<Long>,
            driftPermillePerM: Double = 0.0,
            focalPx: Double = FOCAL_PX,
        ): ArKernelScaleLadder {
            val rungs = distancesMm.mapIndexed { index, distanceMm ->
                val metres = distanceMm / 1_000.0
                // Scale in px/m, drifted by the requested amount per metre.
                val scalePxPerM = focalPx / metres *
                    (1.0 + driftPermillePerM / 1_000.0 * metres)
                ArKernelScaleLadderRung(
                    index = index,
                    distanceMm = distanceMm,
                    scaleMilliPxPerM =
                        (scalePxPerM * 1_000.0).toLong(),
                    tickCount = 100,
                    regularityPermille = 950,
                )
            }
            return ArKernelScaleLadder(
                runId = "ladder.test",
                deviceToken = "phone.test",
                campaignId = "campaign.test",
                rungs = rungs,
            )
        }

        private fun healthy(): ArKernelScaleLadder =
            ladder(listOf(500L, 1_000L, 2_000L, 5_000L))
    }

    @Test
    fun aSingleRungImpliesTheFocalLength() {
        val rung = ArKernelScaleLadderRung(
            index = 0,
            distanceMm = 1_000L,
            // 2 800 px/m at 1 m implies a 2 800 px focal length.
            scaleMilliPxPerM = 2_800_000L,
            tickCount = 100,
            regularityPermille = 900,
        )
        assertEquals(2_800_000L, rung.impliedFocalMilliPx)
        assertEquals(2_800.0, rung.impliedFocalPx, 1e-6)
    }

    @Test
    fun aPinholeLadderHoldsStill() {
        val built = healthy()
        assertEquals(2_800.0, built.focalPxMean, 1.0)
        assertEquals(2_800.0, built.focalPxMin, 1.0)
        assertEquals(2_800.0, built.focalPxMax, 1.0)
        assertEquals(0L, built.spreadPermille)
        assertEquals(0L, built.trendPermillePerM)
        assertEquals(0L, built.residualMaxPermille)
        assertTrue(built.rungsOk)
        assertTrue(built.trendOk)
        assertTrue(built.spreadOk)
        assertTrue(built.scaleSufficient)
        assertEquals(emptyList<String>(), built.outstandingSteps())
    }

    @Test
    fun aDriftingLadderIsDetected() {
        // 40 per mille per metre is four times the limit.
        val built = ladder(
            listOf(500L, 1_000L, 2_000L, 5_000L),
            driftPermillePerM = 40.0)
        assertTrue("trend was ${built.trendPermillePerM}",
            built.trendPermillePerM > LADDER_TREND_LIMIT_PERMILLE_PER_M)
        assertFalse(built.trendOk)
        assertFalse(built.scaleSufficient)
        assertTrue(built.outstandingSteps().contains("trend"))
        // A monotone drift is a straight line, so the correlation is strong.
        assertTrue("correlation ${built.trendCorrelationPermille}",
            built.trendCorrelationPermille > 900)
    }

    @Test
    fun aNegativeDriftIsDetectedAsWell() {
        val built = ladder(
            listOf(500L, 1_000L, 2_000L, 5_000L),
            driftPermillePerM = -40.0)
        assertTrue("trend was ${built.trendPermillePerM}",
            built.trendPermillePerM < -LADDER_TREND_LIMIT_PERMILLE_PER_M)
        assertFalse(built.trendOk)
    }

    @Test
    fun aDriftBelowTheLimitStillPasses() {
        val built = ladder(
            listOf(500L, 1_000L, 2_000L, 5_000L),
            driftPermillePerM = 3.0)
        assertTrue("trend was ${built.trendPermillePerM}", built.trendOk)
        assertTrue(built.scaleSufficient)
    }

    @Test
    fun oneBadRungShowsAsScatterNotDrift() {
        // A single mis-measured rung moves the spread, not the slope.
        val distances = listOf(500L, 1_000L, 2_000L, 5_000L)
        val rungs = distances.mapIndexed { index, distanceMm ->
            val metres = distanceMm / 1_000.0
            val scaleMilli = ((FOCAL_PX / metres) * 1_000.0).toLong()
            ArKernelScaleLadderRung(
                index = index,
                distanceMm = distanceMm,
                scaleMilliPxPerM =
                    if (index == 2) (scaleMilli * 1.2).toLong() else scaleMilli,
                tickCount = 100,
                regularityPermille = 900,
            )
        }
        val built = ArKernelScaleLadder(
            runId = "ladder.test", deviceToken = "phone.test",
            campaignId = "campaign.test", rungs = rungs)
        assertTrue("spread ${built.spreadPermille}",
            built.spreadPermille > LADDER_SPREAD_LIMIT_PERMILLE)
        assertFalse(built.spreadOk)
        assertFalse(built.scaleSufficient)
        assertTrue(built.outstandingSteps().contains("spread"))
    }

    @Test
    fun twoRungsCannotShowATrend() {
        val built = ladder(listOf(500L, 1_000L))
        assertFalse(built.rungsOk)
        assertFalse(built.trendOk)
        assertFalse(built.spreadOk)
        assertFalse(built.scaleSufficient)
        assertEquals(0L, built.trendPermillePerM)
        assertEquals(listOf("rungs"), built.outstandingSteps())
    }

    @Test
    fun aHealthyLadderRoundTrips() {
        val text = ArKernelScaleLadderAdapter.encode(healthy())
        val parsed = ArKernelScaleLadderAdapter.parse(text)
        assertTrue("${parsed.rejection} ${parsed.detail}", parsed.accepted)
        val record = parsed.record!!
        assertEquals(4, record.rungCount)
        assertEquals(healthy().focalPxMean, record.focalPxMean, 1e-6)
        assertEquals(healthy().trendPermillePerM, record.trendPermillePerM)
        assertTrue(record.scaleSufficient)
    }

    @Test
    fun aDriftingLadderRoundTrips() {
        val built = ladder(
            listOf(500L, 1_000L, 2_000L, 5_000L),
            driftPermillePerM = 40.0)
        val parsed = ArKernelScaleLadderAdapter.parse(
            ArKernelScaleLadderAdapter.encode(built))
        assertTrue(parsed.accepted)
        assertFalse(parsed.record!!.scaleSufficient)
    }

    @Test
    fun statisticsAreRecomputedAndMismatchesRejected() {
        val text = ArKernelScaleLadderAdapter.encode(healthy())
        val tampered = text.replace("trend_permille_per_m=0",
            "trend_permille_per_m=7")
        assertTrue(tampered != text)
        val parsed = ArKernelScaleLadderAdapter.parse(tampered)
        assertEquals(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
            parsed.rejection)
    }

    @Test
    fun aFocalMeanCannotBeRewritten() {
        val text = ArKernelScaleLadderAdapter.encode(healthy())
        val parsed = ArKernelScaleLadderAdapter.parse(
            text.replace("focal_px_mean=", "focal_px_mean=9"))
        assertEquals(ArKernelScaleLadderRejection.STATISTICS_MISMATCH,
            parsed.rejection)
    }

    @Test
    fun aRaisedQualityGradeIsRejected() {
        val text = ArKernelScaleLadderAdapter.encode(healthy())
            .replace("quality_grade=unverified",
                "quality_grade=centimeter-validated")
        val parsed = ArKernelScaleLadderAdapter.parse(text)
        assertEquals(ArKernelScaleLadderRejection.INVALID_QUALITY,
            parsed.rejection)
    }

    @Test
    fun claimingAccuracyIsRejected() {
        val text = ArKernelScaleLadderAdapter.encode(healthy())
            .replace("metric_accuracy_claimed=false",
                "metric_accuracy_claimed=true")
        val parsed = ArKernelScaleLadderAdapter.parse(text)
        assertEquals(ArKernelScaleLadderRejection.UNSAFE_INVARIANT,
            parsed.rejection)
    }

    @Test
    fun claimingToHaveFittedIntrinsicsIsRejected() {
        val text = ArKernelScaleLadderAdapter.encode(healthy())
            .replace("intrinsics_fitted=false", "intrinsics_fitted=true")
        val parsed = ArKernelScaleLadderAdapter.parse(text)
        assertEquals(ArKernelScaleLadderRejection.UNSAFE_INVARIANT,
            parsed.rejection)
    }

    @Test
    fun claimingToKeepFramesIsRejected() {
        val text = ArKernelScaleLadderAdapter.encode(healthy())
            .replace("images_disabled=false", "images_disabled=true")
            .replace("no_frames_retained=true", "no_frames_retained=false")
        val parsed = ArKernelScaleLadderAdapter.parse(text)
        assertEquals(ArKernelScaleLadderRejection.UNSAFE_INVARIANT,
            parsed.rejection)
    }

    @Test
    fun distancesMustStrictlyIncrease() {
        val built = ArKernelScaleLadder(
            runId = "ladder.test", deviceToken = "phone.test",
            campaignId = "campaign.test",
            rungs = listOf(
                ArKernelScaleLadderRung(0, 1_000L, 2_800_000L, 100, 900),
                ArKernelScaleLadderRung(1, 1_000L, 2_800_000L, 100, 900),
                ArKernelScaleLadderRung(2, 2_000L, 1_400_000L, 100, 900),
            ))
        val text = ArKernelScaleLadderAdapter.encode(built)
        val parsed = ArKernelScaleLadderAdapter.parse(text)
        assertEquals(ArKernelScaleLadderRejection.INVALID_ORDER,
            parsed.rejection)
    }

    @Test
    fun aRungCountLyingAboutItsRowsIsRejected() {
        val text = ArKernelScaleLadderAdapter.encode(healthy())
            .replace("rung_count=4", "rung_count=7")
        assertEquals(ArKernelScaleLadderRejection.INVALID_COUNT,
            ArKernelScaleLadderAdapter.parse(text).rejection)
    }

    @Test
    fun anOutOfRangeDistanceIsRejected() {
        val built = ArKernelScaleLadder(
            runId = "ladder.test", deviceToken = "phone.test",
            campaignId = "campaign.test",
            rungs = listOf(
                ArKernelScaleLadderRung(0, 1L, 2_800_000L, 100, 900),
                ArKernelScaleLadderRung(1, 2_000L, 1_400_000L, 100, 900),
                ArKernelScaleLadderRung(2, 3_000L, 933_333L, 100, 900),
            ))
        assertEquals(
            ArKernelScaleLadderRejection.DISTANCE_OUT_OF_RANGE,
            ArKernelScaleLadderAdapter.parse(
                ArKernelScaleLadderAdapter.encode(built)).rejection)
    }

    @Test
    fun aGarbageInputIsRejected() {
        assertEquals(ArKernelScaleLadderRejection.INVALID_HEADER,
            ArKernelScaleLadderAdapter.parse("nonsense").rejection)
        assertEquals(ArKernelScaleLadderRejection.EMPTY_RECORD,
            ArKernelScaleLadderAdapter.parse("").rejection)
        assertEquals(ArKernelScaleLadderRejection.INPUT_TOO_LARGE,
            ArKernelScaleLadderAdapter.parse("x".repeat(20_000)).rejection)
    }

    @Test
    fun theReportNeverGrades() {
        val report = ArKernelScaleLadderAdapter.report(healthy())
        assertEquals("unverified", report.qualityGrade)
        assertEquals(4, report.rungCount)
        assertNotNull(report)
    }
}
