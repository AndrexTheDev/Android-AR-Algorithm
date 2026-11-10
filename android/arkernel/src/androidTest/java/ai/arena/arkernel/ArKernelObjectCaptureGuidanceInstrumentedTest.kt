package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArKernelObjectCaptureGuidanceInstrumentedTest {
    @Test
    fun completesBoundedOrbitWithDeterministicGuidance() {
        val config = boundedConfig()
        ArKernelObjectCaptureGuidance(config).use { guidance ->
            val initial = guidance.guidance()
            assertEquals(ObjectCapturePhase.ACQUIRE_OBJECT, initial.phase)
            assertEquals(8L, initial.totalBins)
            assertEquals(0L, initial.coveredBins)

            val observations = mutableListOf<ObjectCaptureGuidanceObservation>()
            observations += observation(config, 1_000L, 0, 0, 0.8)
            observations += observation(config, 1_100L, 0, 0, 0.8)
            for (elevation in 0 until config.elevationBins.toInt()) {
                for (azimuth in 0 until config.azimuthBins.toInt()) {
                    observations += observation(config,
                        1_200L + observations.size * 100L, azimuth, elevation, 0.8)
                }
            }

            val first = guidance.observe(observations[0])
            assertEquals(ObjectCapturePhase.STABILIZE_CENTER, first.phase)
            assertEquals(ObjectCaptureInstruction.HOLD_STILL, first.instruction)
            assertEquals(ObjectCaptureRejection.CENTER_UNSTABLE, first.rejection)
            assertEquals(0L, first.coveredBins)

            val second = guidance.observe(observations[1])
            assertEquals(ObjectCapturePhase.ORBIT, second.phase)
            assertEquals(ObjectCaptureInstruction.ORBIT_TO_NEXT_VIEW, second.instruction)
            assertEquals(1L, second.coveredBins)
            assertEquals(90.0, second.turntableStepDegrees, 1e-6)

            var final = second
            for (index in 2 until observations.size) {
                final = guidance.observe(observations[index])
            }
            assertTrue("complete=${final.complete} phase=${final.phase} " +
                "covered=${final.coveredBins}/${final.totalBins}", final.complete)
            assertEquals(ObjectCapturePhase.COMPLETE, final.phase)
            assertEquals(ObjectCaptureInstruction.COMPLETE, final.instruction)
            assertEquals(ObjectCaptureRejection.NONE, final.rejection)
            assertEquals(8L, final.coveredBins)
            assertEquals(2L, final.coveredRings)
            assertEquals(1.0, final.coverageRatio, 1e-12)
            assertTrue(guidance.guidance().complete)

            guidance.reset()
            val reset = guidance.guidance()
            assertEquals(ObjectCapturePhase.ACQUIRE_OBJECT, reset.phase)
            assertEquals(0L, reset.coveredBins)
            assertEquals(8L, reset.totalBins)
        }
    }

    @Test
    fun rejectsInvalidHostObservationsAndBudgetExhaustion() {
        val config = boundedConfig()
        ArKernelObjectCaptureGuidance(config).use { guidance ->
            val invalidForeground = guidance.observe(observation(config, 1_000L, 0, 0,
                0.8, foregroundRatio = 0.01))
            assertEquals(ObjectCaptureRejection.FOREGROUND_INVALID,
                invalidForeground.rejection)
            val unreliableTracking = guidance.observe(observation(config, 1_100L, 0, 0,
                0.8, trackingReliable = false))
            assertEquals(ObjectCaptureRejection.TRACKING_UNRELIABLE,
                unreliableTracking.rejection)
            val nonMonotonic = guidance.observe(observation(config, 1_050L, 1, 0, 0.8))
            assertEquals(ObjectCaptureRejection.NON_MONOTONIC_TIMESTAMP,
                nonMonotonic.rejection)
        }

        val capacityConfig = boundedConfig().copy(maxObservations = 2L,
            minStableCenterObservations = 1L)
        ArKernelObjectCaptureGuidance(capacityConfig).use { guidance ->
            assertEquals(1L, guidance.observe(
                observation(capacityConfig, 1_000L, 0, 0, 0.8)).coveredBins)
            assertEquals(2L, guidance.observe(
                observation(capacityConfig, 1_100L, 1, 0, 0.8)).coveredBins)
            val exhausted = guidance.observe(
                observation(capacityConfig, 1_200L, 2, 0, 0.8))
            assertEquals(ObjectCapturePhase.BLOCKED, exhausted.phase)
            assertEquals(ObjectCaptureInstruction.STOP, exhausted.instruction)
            assertEquals(ObjectCaptureRejection.COVERAGE_INCOMPLETE, exhausted.rejection)
            assertEquals(2L, exhausted.coveredBins)
        }
    }

    private fun boundedConfig() = ObjectCaptureGuidanceConfig(
        azimuthBins = 4L, elevationBins = 2L, maxObservations = 64L,
        minStableCenterObservations = 2L, minDistanceMeters = 0.2,
        maxDistanceMeters = 2.0, targetDistanceMeters = 0.8,
        distanceToleranceMeters = 0.05, minCoverageRatio = 1.0,
        elevationStepDegrees = 20.0)

    private fun observation(
        config: ObjectCaptureGuidanceConfig,
        timestampNs: Long,
        azimuthBin: Int,
        elevationBin: Int,
        radiusMeters: Double,
        foregroundRatio: Double = 0.5,
        trackingReliable: Boolean = true,
    ): ObjectCaptureGuidanceObservation {
        val azimuth = (azimuthBin + 0.5) * 2.0 * Math.PI /
            config.azimuthBins.toDouble()
        val elevation = -Math.PI / 2.0 +
            (elevationBin + 0.5) * Math.PI / config.elevationBins.toDouble()
        val delta = Vector3(cos(elevation) * cos(azimuth) * radiusMeters,
            cos(elevation) * sin(azimuth) * radiusMeters,
            sin(elevation) * radiusMeters)
        return ObjectCaptureGuidanceObservation(
            WorldPose(timestampNs, Rotation(1.0, 0.0, 0.0, 0.0), delta),
            trackingReliable, foregroundRatio, Vector3(0.0, 0.0, 0.0))
    }
}
