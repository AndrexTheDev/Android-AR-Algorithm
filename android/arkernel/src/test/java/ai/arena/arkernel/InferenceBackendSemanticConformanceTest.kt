package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InferenceBackendSemanticConformanceTest {
    private val modelBytes = "semantic-model-artifact".toByteArray()
    private val manifest = manifest()
    private val identity by lazy {
        VerifiedInferenceIdentity.create(manifest, ByteBuffer.wrap(modelBytes))
    }

    @Test
    fun canonicalDetectionsAndCrossRepresentationDepthAcceptDelegateWithoutMutation() {
        val frame = frame(10)
        val first = detection(1, 0.90, 1.0, 1.0, 3.0, 3.0)
        val second = detection(0, 0.80, 5.0, 2.0, 8.0, 6.0)
        val cpuDepth = depthImage(10, floatArrayOf(1.0f, 2.0f, 99f, 3.0f, 4.0f),
            floatArrayOf(0.9f, 0.8f, 99f, 0.7f, 0.6f))
        val delegateDepth = depthBuffer(10,
            floatArrayOf(1.01f, 2.01f, 88f, 3.01f, 4.01f),
            floatArrayOf(0.91f, 0.81f, 88f, 0.71f, 0.61f))
        val depthPosition = delegateDepth.depthMeters.position()
        val confidencePosition = requireNotNull(delegateDepth.confidence).position()
        val cpu = FakeBackend(manifest) { output(frame, listOf(first, second), cpuDepth) }
        val delegate = FakeBackend(manifest) {
            output(frame, listOf(second.copy(confidence = 0.805),
                first.copy(left = 1.25)), delegateDepth)
        }

        val selection = InferenceBackendSemanticConformanceSelector.select(cpu, delegate,
            identity, listOf(InferenceSemanticGoldenCase("combined", frame)))

        assertTrue(selection.usingDelegate)
        assertSame(delegate, selection.backend)
        assertEquals(2, selection.report.comparedDetections)
        assertEquals(4, selection.report.comparedDepthPixels)
        assertTrue(selection.report.maximumDetectionCoordinateErrorPixels > 0.0)
        assertTrue(selection.report.maximumDepthAbsoluteErrorMeters > 0.0)
        assertEquals(depthPosition, delegateDepth.depthMeters.position())
        assertEquals(confidencePosition, requireNotNull(delegateDepth.confidence).position())
        assertTrue(cpu.closed)
        assertFalse(delegate.closed)
    }

    @Test
    fun semanticDifferenceInSecondCanonicalDetectionFallsBackToCpu() {
        val frame = frame(20)
        val reference = listOf(
            detection(0, 0.9, 1.0, 1.0, 2.0, 2.0),
            detection(1, 0.8, 3.0, 3.0, 5.0, 5.0),
        )
        val candidate = listOf(reference[1].copy(dynamic = true), reference[0])
        val depth = depthImage(20, floatArrayOf(1f, 1f, 0f, 1f, 1f))
        val cpu = FakeBackend(manifest) { output(frame, reference, depth) }
        val delegate = FakeBackend(manifest) { output(frame, candidate, depth) }

        val selection = InferenceBackendSemanticConformanceSelector.select(cpu, delegate,
            identity, listOf(InferenceSemanticGoldenCase("semantic-mismatch", frame)))

        assertFalse(selection.usingDelegate)
        assertSame(cpu, selection.backend)
        assertEquals(InferenceSemanticConformanceFailure.DETECTION_SEMANTIC_MISMATCH,
            selection.report.failure)
        assertEquals("semantic-mismatch", selection.report.failedCaseId)
        assertFalse(cpu.closed)
        assertTrue(delegate.closed)
    }

    @Test
    fun nonFiniteDelegateDepthFallsBackToCpu() {
        val frame = frame(30)
        val valid = depthImage(30, floatArrayOf(1f, 2f, 0f, 3f, 4f))
        val invalid = depthImage(30, floatArrayOf(1f, Float.NaN, 0f, 3f, 4f))
        val detections = listOf(detection(0, 0.9, 1.0, 1.0, 2.0, 2.0))
        val cpu = FakeBackend(manifest) { output(frame, detections, valid) }
        val delegate = FakeBackend(manifest) { output(frame, detections, invalid) }

        val selection = InferenceBackendSemanticConformanceSelector.select(cpu, delegate,
            identity, listOf(InferenceSemanticGoldenCase("nan-depth", frame)))

        assertEquals(InferenceSemanticConformanceFailure.DEPTH_NON_FINITE,
            selection.report.failure)
        assertTrue(delegate.closed)
        assertFalse(cpu.closed)
    }

    @Test
    fun invalidCpuDepthClosesBothAndAborts() {
        val frame = frame(40)
        val invalid = depthImage(40, floatArrayOf(1f, -1f, 0f, 3f, 4f))
        val valid = depthImage(40, floatArrayOf(1f, 2f, 0f, 3f, 4f))
        val detections = listOf(detection(0, 0.9, 1.0, 1.0, 2.0, 2.0))
        val cpu = FakeBackend(manifest) { output(frame, detections, invalid) }
        val delegate = FakeBackend(manifest) { output(frame, detections, valid) }

        try {
            InferenceBackendSemanticConformanceSelector.select(cpu, delegate, identity,
                listOf(InferenceSemanticGoldenCase("bad-cpu", frame)))
            fail("Expected invalid CPU oracle")
        } catch (_: IllegalStateException) {
            assertTrue(cpu.closed)
            assertTrue(delegate.closed)
        }
    }

    @Test
    fun identityMismatchRejectsDelegateBeforeEitherBackendExecutes() {
        val frame = frame(50)
        val calls = AtomicInteger()
        val cpu = FakeBackend(manifest) { calls.incrementAndGet(); error("must not execute") }
        val delegate = FakeBackend(manifest(version = "different")) {
            calls.incrementAndGet(); error("must not execute")
        }

        val selection = InferenceBackendSemanticConformanceSelector.select(cpu, delegate,
            identity, listOf(InferenceSemanticGoldenCase("identity", frame)))

        assertFalse(selection.usingDelegate)
        assertEquals(InferenceSemanticConformanceFailure.DELEGATE_MANIFEST_MISMATCH,
            selection.report.failure)
        assertEquals(0, calls.get())
        assertTrue(delegate.closed)
        assertFalse(cpu.closed)
    }

    private fun output(frame: PerceptionFrame, detections: List<SemanticDetection2d>,
        depth: MetricDepthImage) = OnDeviceInferenceOutput(frame.timestampNs,
        frame.worldFromCamera, detections, metricDepth = depth)

    private fun output(frame: PerceptionFrame, detections: List<SemanticDetection2d>,
        depth: MetricDepthBuffer) = OnDeviceInferenceOutput(frame.timestampNs,
        frame.worldFromCamera, detections, metricDepthBuffer = depth)

    private fun detection(classId: Long, confidence: Double, left: Double, top: Double,
        right: Double, bottom: Double) = SemanticDetection2d(classId,
        confidence = confidence, left = left, top = top, right = right, bottom = bottom)

    private fun depthImage(timestampNs: Long, depth: FloatArray,
        confidence: FloatArray? = null) = MetricDepthImage(2, 2, 3, depth, confidence,
        if (confidence == null) 0 else 3, timestampNs)

    private fun depthBuffer(timestampNs: Long, depthValues: FloatArray,
        confidenceValues: FloatArray): MetricDepthBuffer {
        val depth = directFloats(depthValues, 2)
        val confidence = directFloats(confidenceValues, 1)
        return MetricDepthBuffer(2, 2, 3, depth, confidence, 3, timestampNs)
    }

    private fun directFloats(values: FloatArray, offset: Int) =
        ByteBuffer.allocateDirect((values.size + offset) * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                values.forEachIndexed { index, value -> put(offset + index, value) }
                position(offset)
            }

    private fun frame(timestampNs: Long): PerceptionFrame {
        val calibration = CameraCalibration(10, 10, 5.0, 5.0, 5.0, 5.0)
        val pose = WorldPose(timestampNs, Rotation(1.0, 0.0, 0.0, 0.0),
            Vector3(0.0, 0.0, 0.0))
        return PerceptionFrame.wrap(timestampNs, calibration, pose,
            InferencePixelFormat.GRAY8, 10, ByteBuffer.allocateDirect(100))
    }

    private fun manifest(version: String = "1") = ModelManifest(
        modelId = "semantic-conformance",
        modelVersion = version,
        sha256 = sha256(modelBytes),
        licenseSpdx = "Apache-2.0",
        sourceUrl = "https://example.com/semantic-model",
        tasks = setOf(InferenceTask.SEMANTIC_DETECTION, InferenceTask.METRIC_DEPTH),
        inputWidth = 10,
        inputHeight = 10,
        inputChannels = 1,
        inputMean = listOf(0.0),
        inputStd = listOf(1.0),
        quantization = TensorQuantization.FLOAT32,
        labels = mapOf(0L to "Unknown", 1L to "Object"),
        depthUnitToMeters = 1.0,
        modelByteSize = modelBytes.size.toLong(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class FakeBackend(
        override val manifest: ModelManifest,
        private val inference: (PerceptionFrame) -> OnDeviceInferenceOutput,
    ) : OnDeviceInferenceBackend {
        var closed = false
        override fun infer(frame: PerceptionFrame) = inference(frame)
        override fun close() { closed = true }
    }
}
