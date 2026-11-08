package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Owns an inference backend and conservatively filters any metric depth it emits before the
 * scheduler callback. Detections, frame pose, and timestamp pass through unchanged.
 */
class TemporalDepthInferenceBackend(
    private val delegate: OnDeviceInferenceBackend,
    options: TemporalDepthFilterOptions = TemporalDepthFilterOptions(),
) : OnDeviceInferenceBackend {
    override val manifest: ModelManifest = delegate.manifest
    private val filter = TemporalMetricDepthFilter(options)
    private var closed = false
    @Volatile var lastFilterResult: TemporalDepthFilterResult? = null
        private set

    init { require(InferenceTask.METRIC_DEPTH in manifest.tasks) }

    @Synchronized
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed) { "Temporal inference backend is closed" }
        val output = delegate.infer(frame)
        require(output.timestampNs == frame.timestampNs &&
            output.worldFromCamera == frame.worldFromCamera)
        require(output.metricDepth == null || output.metricDepthBuffer == null)
        val source = output.metricDepthBuffer ?: output.metricDepth?.toDirectBuffer()
        if (source == null) {
            lastFilterResult = null
            return output
        }
        require(source.timestampNs == frame.timestampNs)
        require(source.width == frame.calibration.width &&
            source.height == frame.calibration.height)
        val filtered = filter.update(source, frame.calibration, frame.worldFromCamera)
        lastFilterResult = filtered
        return output.copy(metricDepth = null, metricDepthBuffer = filtered.depth)
    }

    @Synchronized
    fun resetHistory() {
        check(!closed) { "Temporal inference backend is closed" }
        filter.reset()
        lastFilterResult = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        try {
            filter.close()
        } catch (error: Exception) {
            failure = error
        }
        try {
            delegate.close()
        } catch (error: Exception) {
            if (failure == null) failure = error else failure?.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun MetricDepthImage.toDirectBuffer(): MetricDepthBuffer {
        require(width > 0 && height > 0 && rowStrideElements >= width)
        val requiredDepth = (height.toLong() - 1L) * rowStrideElements + width
        require(requiredDepth <= depthMeters.size.toLong())
        confidence?.let {
            val requiredConfidence = (height.toLong() - 1L) *
                confidenceRowStrideElements + width
            require(confidenceRowStrideElements >= width &&
                requiredConfidence <= it.size.toLong())
        }
        val elements = Math.multiplyExact(width.toLong(), height.toLong())
        require(elements in 1L..Int.MAX_VALUE)
        val depthBytes = ByteBuffer.allocateDirect(Math.multiplyExact(elements, 4L).toInt())
            .order(ByteOrder.nativeOrder())
        val depthOutput = depthBytes.asFloatBuffer()
        for (y in 0 until height) {
            val sourceOffset = y * rowStrideElements
            depthOutput.put(depthMeters, sourceOffset, width)
        }
        depthOutput.position(0)
        val confidenceOutput = confidence?.let { source ->
            require(confidenceRowStrideElements >= width)
            val bytes = ByteBuffer.allocateDirect(Math.multiplyExact(elements, 4L).toInt())
                .order(ByteOrder.nativeOrder())
            val target = bytes.asFloatBuffer()
            for (y in 0 until height) {
                val sourceOffset = y * confidenceRowStrideElements
                target.put(source, sourceOffset, width)
            }
            target.position(0)
            target
        }
        return MetricDepthBuffer(width, height, width, depthOutput, confidenceOutput,
            if (confidenceOutput == null) 0 else width, timestampNs)
    }
}
