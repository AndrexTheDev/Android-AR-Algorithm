package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRuntimeInstrumentedTest {
    @Test
    fun loadsPackagedJniAndExercisesNativeOwners() {
        val vio = ArKernelVio()
        try {
            assertNull(vio.snapshot())
            vio.submitImu(
                timestampNs = 1_000_000_000L,
                acceleration = Vector3(0.0, 0.0, 9.81),
                angularVelocity = Vector3(0.0, 0.0, 0.0),
            )
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

        val planner = ArKernelReconstructionPlanner(ReconstructionScanMode.ENVIRONMENT)
        planner.close()
    }
}
