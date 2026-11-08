package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TemporalDepthFilterOptions(
    val minDepthMeters: Double = 0.05,
    val maxDepthMeters: Double = 20.0,
    val maxRelativeDifference: Double = 0.08,
    val maxAbsoluteDifferenceMeters: Double = 0.05,
    val historyWeight: Double = 0.35,
    val maxFrameGapNs: Long = 500_000_000L,
    val maxPixels: Long = 4_194_304L,
)
data class TemporalDepthFilterResult(
    val depth: MetricDepthBuffer,
    val blendedPixels: Long,
    val rejectedHistoryPixels: Long,
    val historyUsed: Boolean,
)

/** Pose-compensated conservative temporal filtering for camera-aligned metric depth. */
class TemporalMetricDepthFilter(options: TemporalDepthFilterOptions = TemporalDepthFilterOptions()) :
    AutoCloseable {
    private var handle: Long
    init {
        handle = nativeCreate(options.minDepthMeters, options.maxDepthMeters,
            options.maxRelativeDifference, options.maxAbsoluteDifferenceMeters,
            options.historyWeight, options.maxFrameGapNs, options.maxPixels)
        require(handle != 0L) { "Invalid temporal depth filter options" }
    }

    @Synchronized
    fun update(depth: MetricDepthBuffer, calibration: CameraCalibration,
               worldFromCamera: WorldPose): TemporalDepthFilterResult {
        check(handle != 0L) { "Temporal depth filter is closed" }
        require(depth.timestampNs == worldFromCamera.timestampNs)
        require(depth.width == calibration.width && depth.height == calibration.height)
        require(depth.depthMeters.isDirect && depth.depthMeters.order() == ByteOrder.nativeOrder())
        depth.confidence?.let { require(it.isDirect && it.order() == ByteOrder.nativeOrder()) }
        val pixels = depth.width.toLong() * depth.height
        require(pixels in 1L..Int.MAX_VALUE)
        val outputBytes = Math.multiplyExact(pixels, 4L).toInt()
        val outputDepth = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
        val outputConfidence = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
        val metadata = LongArray(3)
        val rotation = worldFromCamera.rotation
        val position = worldFromCamera.positionMeters
        val status = nativeUpdate(handle, depth.depthMeters, depth.depthMeters.position(),
            depth.depthMeters.remaining(), depth.confidence, depth.confidence?.position() ?: 0,
            depth.confidence?.remaining() ?: 0, depth.width, depth.height,
            depth.rowStrideElements, depth.confidenceRowStrideElements, depth.timestampNs,
            calibration.fx, calibration.fy, calibration.cx, calibration.cy, calibration.k1,
            calibration.k2, calibration.p1, calibration.p2, calibration.k3,
            rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z, outputDepth.asFloatBuffer(),
            outputConfidence.asFloatBuffer(), metadata)
        require(status != 1) { "Invalid or out-of-order temporal depth frame" }
        check(status == 0) { "Native temporal depth filtering failed: $status" }
        val filtered = MetricDepthBuffer(depth.width, depth.height, depth.width,
            outputDepth.asFloatBuffer(), outputConfidence.asFloatBuffer(), depth.width,
            depth.timestampNs)
        return TemporalDepthFilterResult(filtered, metadata[0], metadata[1], metadata[2] != 0L)
    }

    @Synchronized fun reset() { if (handle != 0L) nativeReset(handle) }
    @Synchronized override fun close() {
        if (handle != 0L) { nativeDestroy(handle); handle = 0L }
    }

    private external fun nativeCreate(minDepth: Double, maxDepth: Double,
        relativeGate: Double, absoluteGate: Double, historyWeight: Double,
        maxFrameGapNs: Long, maxPixels: Long): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeUpdate(handle: Long, depth: java.nio.FloatBuffer,
        depthOffset: Int, depthRemaining: Int, confidence: java.nio.FloatBuffer?,
        confidenceOffset: Int, confidenceRemaining: Int, width: Int, height: Int,
        depthStride: Int, confidenceStride: Int, timestampNs: Long,
        fx: Double, fy: Double, cx: Double, cy: Double, k1: Double, k2: Double,
        p1: Double, p2: Double, k3: Double, rw: Double, rx: Double, ry: Double,
        rz: Double, tx: Double, ty: Double, tz: Double,
        outputDepth: java.nio.FloatBuffer, outputConfidence: java.nio.FloatBuffer,
        metadata: LongArray): Int
    companion object { init { System.loadLibrary("arkernel_jni") } }
}
