package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRuntimeInstrumentedTest {
    @Test
    fun loadsPackagedJniAndExercisesNativeOwners() {
        val vio = ArKernelVio()
        try {
            assertNull(vio.snapshot())
            repeat(80) { index ->
                vio.submitImu(1_000_000_000L + index * 10_000_000L,
                    Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0))
            }
            val initializedAt = requireNotNull(vio.snapshot()).timestampNs
            val reference = RelativeVisualPose(initializedAt,
                Rotation(1.0, 0.0, 0.0, 0.0), Vector3(-1.0, 0.0, 0.0))
            assertNull(vio.fuseVisualMeasured(reference))
            repeat(10) { index ->
                vio.submitImu(initializedAt + (index + 1) * 10_000_000L,
                    Vector3(1.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0))
            }
            val measured = requireNotNull(vio.fuseVisualMeasured(reference.copy(
                timestampNs = requireNotNull(vio.snapshot()).timestampNs)))
            assertTrue(measured.rotationApplied)
            assertTrue(measured.predictedDisplacementMeters > 0.0)
            assertTrue(measured.translationDirectionCosine > 0.0)
            vio.pause()
            vio.resume()
        } finally {
            vio.close()
        }

        val clockAligner = ArKernelClockAligner(capacity = 16, minimumObservations = 6)
        try {
            repeat(8) { index ->
                val source = 1_000_000_000L + index * 100_000_000L
                clockAligner.addObservation(source, source + 2_000_000L)
            }
            val alignment = requireNotNull(clockAligner.alignment())
            assertEquals(700_000_000L, alignment.sourceSpanNs)
        } finally {
            clockAligner.close()
        }

        val dimensions = listOf(
            heldOut("scale-0.5m", 0.502, 0.5, DimensionalReferenceKind.HELD_OUT_SCALE_BAR),
            heldOut("survey-1m", 1.003, 1.0, DimensionalReferenceKind.SURVEYED_DISTANCE),
            heldOut("rig-2m", 2.004, 2.0, DimensionalReferenceKind.SENSOR_GROUND_TRUTH),
        )
        val dimensionalReport = ArKernelDimensionalValidator.evaluate(dimensions)
        assertTrue(dimensionalReport.reasons.toString(), dimensionalReport.passed)
        assertEquals(3, dimensionalReport.acceptedObservations)
        val rejectedReport = ArKernelDimensionalValidator.evaluate(dimensions.mapIndexed {
                index, value -> if (index == 0) value.copy(
                    heldOutFromCalibrationAndOptimization = false) else value
        })
        assertTrue(DimensionalValidationReason.INVALID_OR_NON_INDEPENDENT_OBSERVATION in
            rejectedReport.reasons)

        val planner = ArKernelReconstructionPlanner(ReconstructionScanMode.ENVIRONMENT)
        planner.close()
    }

    private fun heldOut(id: String, reconstructed: Double, reference: Double,
        kind: DimensionalReferenceKind) = HeldOutDimensionObservation(id,
        Vector3(0.0, 0.0, 0.0), Vector3(reconstructed, 0.0, 0.0), reference, 0.001, kind)
}
