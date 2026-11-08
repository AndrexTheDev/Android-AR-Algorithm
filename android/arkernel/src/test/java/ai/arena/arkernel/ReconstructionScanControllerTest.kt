package ai.arena.arkernel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionScanControllerTest {
    @Test
    fun commitsCoverageOnlyAfterArchiveSinkSucceeds() {
        val planner = FakePlanner()
        val stored = CountDownLatch(1)
        val controller = ReconstructionScanController(planner, archiveSink = { "arkf://test/1" },
            uncertaintyProvider = { ReconstructionPoseUncertainty(0.005, 0.2) },
            onStored = { stored.countDown() })
        val session = session(1000)
        assertTrue(controller.qualityGate(session, CameraFrameQuality(0.8, 0.01, 100)))
        assertEquals(0, planner.committed.get())
        controller.reconstructionFrame(cameraFrame(session))
        assertTrue(stored.await(5, TimeUnit.SECONDS))
        assertEquals(1, planner.committed.get())
        assertEquals(0, planner.cancelled.get())
        assertFalse(controller.hasPendingFrame())
        controller.close()
    }

    @Test
    fun cancelsReservationWhenStorageFails() {
        val planner = FakePlanner()
        val failed = CountDownLatch(1)
        val controller = ReconstructionScanController(planner,
            archiveSink = { throw IllegalStateException("disk full") },
            uncertaintyProvider = { ReconstructionPoseUncertainty(0.005, 0.2) },
            onError = { failed.countDown() })
        val session = session(2000)
        assertTrue(controller.qualityGate(session, CameraFrameQuality(0.8, 0.01, 100)))
        controller.reconstructionFrame(cameraFrame(session))
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(0, planner.committed.get())
        assertEquals(1, planner.cancelled.get())
        controller.close()
    }

    @Test
    fun newerCandidateCancelsReservationWhosePixelsNeverArrived() {
        val planner = FakePlanner()
        val controller = ReconstructionScanController(planner, archiveSink = { "unused" },
            uncertaintyProvider = { ReconstructionPoseUncertainty(0.005, 0.2) })
        assertTrue(controller.qualityGate(session(3000), CameraFrameQuality(0.8, 0.0, 100)))
        assertTrue(controller.qualityGate(session(4000), CameraFrameQuality(0.8, 0.0, 100)))
        assertEquals(1, planner.cancelled.get())
        assertTrue(controller.hasPendingFrame())
        controller.close()
        assertEquals(2, planner.cancelled.get())
    }

    private class FakePlanner : TransactionalReconstructionPlanner {
        val committed = AtomicLong()
        val cancelled = AtomicLong()
        private val token = AtomicLong(10)
        override fun reserve(frameId: Long, session: ArKernelSessionResult,
            quality: CameraFrameQuality, translationSigmaMeters: Double,
            rotationSigmaDegrees: Double, objectObservation: ReconstructionObjectObservation?
        ): ReconstructionPlannerDecision = ReconstructionPlannerDecision(true,
            ReconstructionFrameRejection.NONE, 0.9, null, 0, 0, 0.0, token.incrementAndGet())
        override fun commit(reservationToken: Long): Boolean { committed.incrementAndGet(); return true }
        override fun cancel(reservationToken: Long): Boolean { cancelled.incrementAndGet(); return true }
        override fun close() = Unit
    }

    private fun session(timestamp: Long): ArKernelSessionResult {
        val visual = VisualFrontendSnapshot(timestamp, FrontendState.TRACKING,
            SceneGeometry.GENERAL_3D, 100, 120, true, null, 80, 3.0)
        return ArKernelSessionResult(visual, null,
            WorldPose(timestamp, Rotation(1.0, 0.0, 0.0, 0.0), Vector3(1.0, 2.0, 3.0)),
            MappingGateDecision(MappingGateState.ACTIVE, MappingPauseReason.NONE, true),
            null, false, 0, 0)
    }

    private fun cameraFrame(session: ArKernelSessionResult): ArKernelReconstructionFrame {
        val camera = CameraReconstructionFrame(session.visual.timestampNs, 4, 2,
            ByteArray(12), 8_000_000, 200)
        return ArKernelReconstructionFrame(camera, session)
    }
}
