package ai.arena.arkernel

import java.nio.ByteBuffer
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTargetAdapterTest {
    @Test
    fun validatesAdapterOutputAndGuidesCoverage() {
        var index = 0
        val views = listOf(-30.0 to -15.0, -15.0 to 15.0, 0.0 to -15.0,
            15.0 to 15.0, 30.0 to 0.0, 0.0 to 30.0)
        val adapter = CalibrationTargetPoseAdapter { frame, target, _ ->
            val (azimuth, elevation) = views[index % views.size]
            val distance = if (index < 6) 0.7 else 1.3
            index++
            detection(frame.timestampNs, target.targetId, distance,
                Vector3(tan(Math.toRadians(azimuth)), tan(Math.toRadians(elevation)), 1.0))
        }
        val pipeline = CalibrationTargetPipeline(target(), calibration(), adapter)
        val controller = CalibrationGuidanceController(pipeline)
        val observedStates = mutableSetOf<CalibrationGuidanceState>()
        repeat(12) { frameIndex ->
            observedStates += controller.process(frame(1_000_000_000L + frameIndex))
        }
        assertTrue(CalibrationGuidanceState.CAPTURE_MORE in observedStates)
        assertTrue(CalibrationGuidanceState.CHANGE_DISTANCE in observedStates ||
            CalibrationGuidanceState.TILT_TARGET_OR_PHONE in observedStates)
        assertTrue(CalibrationGuidanceState.READY_TO_FINISH in observedStates)
        val (state, report) = controller.finish()
        assertEquals(CalibrationGuidanceState.COMPLETE, state)
        assertTrue(report.observable)
    }

    @Test
    fun rejectsOutOfBoundsOrThrowingAdapterOutput() {
        val malformedCorners = corners().also { it[0] = -1.0 }
        val outOfBounds = CalibrationTargetPoseAdapter { frame, target, _ ->
            CalibrationTargetDetection(frame.timestampNs, target.targetId, malformedCorners,
                Vector3(0.0, 0.0, 1.0), Vector3(0.0, 0.0, 1.0), 1.0, 0.5)
        }
        assertEquals(CalibrationTargetProcessResult.MALFORMED_DETECTION,
            CalibrationTargetPipeline(target(), calibration(), outOfBounds).process(frame(1)))
        val throwing = CalibrationTargetPoseAdapter { _, _, _ -> error("adapter failure") }
        assertEquals(CalibrationTargetProcessResult.MALFORMED_DETECTION,
            CalibrationTargetPipeline(target(), calibration(), throwing).process(frame(2)))
        val absent = CalibrationTargetPoseAdapter { _, _, _ -> null }
        assertEquals(CalibrationTargetProcessResult.NO_TARGET,
            CalibrationTargetPipeline(target(), calibration(), absent).process(frame(3)))
    }

    private fun target() = PlanarCalibrationTarget("board", 1.0, 0.001, 20)
    private fun calibration() = CameraCalibration(640, 480, 500.0, 500.0, 320.0, 240.0)

    private fun detection(timestamp: Long, id: String, distance: Double,
        normal: Vector3) = CalibrationTargetDetection(timestamp, id, corners(),
        Vector3(0.0, 0.0, distance), normal, 1.002, 0.5)

    private fun corners(): DoubleArray {
        val result = DoubleArray(40)
        var output = 0
        repeat(4) { row -> repeat(5) { column ->
            result[output++] = 120.0 + column * 90.0
            result[output++] = 90.0 + row * 90.0
        } }
        return result
    }

    private fun frame(timestamp: Long): CalibrationTargetFrame {
        val buffer = ByteBuffer.allocateDirect(640 * 480)
        repeat(640 * 480) { pixel -> buffer.put(if ((pixel + pixel / 640) % 2 == 0)
            0.toByte() else 255.toByte()) }
        buffer.flip()
        return CalibrationTargetFrame(timestamp, 640, 480, 640, 1, buffer)
    }
}
