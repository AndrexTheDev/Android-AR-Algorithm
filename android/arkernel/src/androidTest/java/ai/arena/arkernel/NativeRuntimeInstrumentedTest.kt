package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
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

        val planner = ArKernelReconstructionPlanner(ReconstructionScanMode.ENVIRONMENT)
        planner.close()
    }
}
