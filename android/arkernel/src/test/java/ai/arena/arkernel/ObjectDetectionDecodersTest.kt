package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectDetectionDecodersTest {
    @Test
    fun yoloxRowsMultiplyObjectnessAndApplyDeterministicNms() {
        // cx, cy, width, height, objectness, class0, class1
        val tensor = floats(
            50f, 40f, 40f, 20f, 0.9f, 0.8f, 0.1f,
            51f, 40f, 40f, 20f, 0.8f, 0.75f, 0.1f,
            80f, 20f, 10f, 10f, 0.9f, 0.1f, 0.7f,
        )
        val detections = ArKernelAnchorFreeDetectionDecoder.decode(tensor,
            RawDetectionTensorSpec(3, 7), AnchorFreeDetectionOptions(
                classCount = 2, modelInputWidth = 100, modelInputHeight = 100,
                scoreThreshold = 0.5, iouThreshold = 0.5), 200, 100)

        assertEquals(2, detections.size)
        assertEquals(1L, detections[0].classId)
        assertEquals(0.72, detections[0].confidence, 1e-6)
        assertEquals(60.0, detections[0].left, 1e-6)
        assertEquals(2L, detections[1].classId)
    }

    @Test
    fun rtDetrNormalizedRowsAcceptSoftmaxLogitsWithoutObjectness() {
        val tensor = floats(0.5f, 0.5f, 0.5f, 0.5f, -2f, 3f)
        val detections = ArKernelAnchorFreeDetectionDecoder.decode(tensor,
            RawDetectionTensorSpec(1, 6), AnchorFreeDetectionOptions(
                objectnessColumn = null, firstClassColumn = 4, classCount = 2,
                classActivation = DetectionScoreActivation.SOFTMAX,
                coordinateSpace = DetectionCoordinateSpace.NORMALIZED,
                modelInputWidth = 640, modelInputHeight = 640,
                scoreThreshold = 0.9), 400, 200)

        assertEquals(1, detections.size)
        assertEquals(2L, detections.single().classId)
        assertTrue(detections.single().confidence > 0.99)
        assertEquals(100.0, detections.single().left, 1e-6)
        assertEquals(50.0, detections.single().top, 1e-6)
    }

    @Test
    fun ssdSplitOutputsDecodeQuantizedScoresAndYxyxBoxes() {
        val boxes = floats(0.1f, 0.2f, 0.6f, 0.8f, 0.12f, 0.22f, 0.58f, 0.78f)
        val classes = floats(3f, 3f)
        val scores = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder())
            .put(0, 90.toByte()).put(1, 80.toByte())
        val detections = ArKernelSsdDetectionDecoder.decode(boxes,
            RawDetectionTensorSpec(2, 4), classes, RawDetectionTensorSpec(2, 1),
            scores, RawDetectionTensorSpec(2, 1, encoding = DetectionTensorEncoding.UINT8,
                quantizationScale = 0.01), 2,
            SsdDetectionOptions(modelInputWidth = 300, modelInputHeight = 300,
                scoreThreshold = 0.5, iouThreshold = 0.5), 200, 100)

        assertEquals(1, detections.size)
        assertEquals(4L, detections.single().classId)
        assertEquals(40.0, detections.single().left, 1e-5)
        assertEquals(10.0, detections.single().top, 1e-5)
        assertEquals(160.0, detections.single().right, 1e-5)
        assertEquals(60.0, detections.single().bottom, 1e-5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedTensor() {
        ArKernelAnchorFreeDetectionDecoder.decode(
            ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()),
            RawDetectionTensorSpec(1, 6), AnchorFreeDetectionOptions(
                classCount = 1, modelInputWidth = 32, modelInputHeight = 32), 32, 32)
    }

    private fun floats(vararg values: Float): ByteBuffer {
        val result = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach { result.putFloat(it) }
        result.flip()
        return result
    }
}
