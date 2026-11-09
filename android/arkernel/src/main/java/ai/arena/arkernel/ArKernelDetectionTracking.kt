package ai.arena.arkernel

data class DetectionTrackerOptions(
    val minimumIou: Double = 0.30,
    val maxTracks: Int = 256,
    val maxMissedFrames: Int = 5,
    val maxFrameGapNs: Long = 1_000_000_000L,
)

/** Bounded deterministic class-aware IoU tracking for detections without model-provided IDs. */
class BoundedSemanticDetectionTracker(options: DetectionTrackerOptions = DetectionTrackerOptions()) :
    AutoCloseable {
    private var handle = nativeCreate(options.minimumIou, options.maxTracks,
        options.maxMissedFrames, options.maxFrameGapNs)
    init { require(handle != 0L) { "Invalid detection tracker options" } }

    @Synchronized
    fun update(timestampNs: Long, detections: List<SemanticDetection2d>): List<SemanticDetection2d> {
        check(handle != 0L) { "Detection tracker is closed" }
        require(detections.size <= 256)
        val untracked = detections.withIndex().filter { it.value.sourceTrackId < 0 }
        val classIds = LongArray(untracked.size)
        val values = DoubleArray(untracked.size * 5)
        untracked.forEachIndexed { outputIndex, indexed ->
            val detection = indexed.value
            classIds[outputIndex] = detection.classId
            val offset = outputIndex * 5
            values[offset] = detection.confidence
            values[offset + 1] = detection.left
            values[offset + 2] = detection.top
            values[offset + 3] = detection.right
            values[offset + 4] = detection.bottom
        }
        val trackIds = LongArray(untracked.size)
        val status = nativeUpdate(handle, timestampNs, classIds, values, trackIds)
        require(status != 1) { "Invalid or out-of-order detection tracker update" }
        check(status == 0) { "Native detection tracker failed: $status" }
        if (untracked.isEmpty()) return detections
        val result = detections.toMutableList()
        untracked.forEachIndexed { trackedIndex, indexed ->
            val id = trackIds[trackedIndex]
            if (id > 0) result[indexed.index] = indexed.value.copy(sourceTrackId = id)
        }
        return result
    }

    @Synchronized fun reset() { if (handle != 0L) nativeReset(handle) }
    @Synchronized override fun close() {
        if (handle != 0L) { nativeDestroy(handle); handle = 0L }
    }

    private external fun nativeCreate(minimumIou: Double, maxTracks: Int,
        maxMissedFrames: Int, maxFrameGapNs: Long): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeUpdate(handle: Long, timestampNs: Long, classIds: LongArray,
        confidenceAndBoxes: DoubleArray, trackIds: LongArray): Int
    companion object { init { System.loadLibrary("arkernel_jni") } }
}

/** Adds stable local IDs only where a delegate did not already provide model tracker IDs. */
class TrackedInferenceBackend(
    private val delegate: OnDeviceInferenceBackend,
    options: DetectionTrackerOptions = DetectionTrackerOptions(),
) : OnDeviceInferenceBackend {
    override val manifest: ModelManifest = delegate.manifest
    override val executionIdentity: InferenceExecutionIdentity? = delegate.executionIdentity
    private val tracker = BoundedSemanticDetectionTracker(options)
    private var closed = false
    init { require(InferenceTask.SEMANTIC_DETECTION in manifest.tasks) }

    @Synchronized override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed) { "Tracked inference backend is closed" }
        val output = delegate.infer(frame)
        require(output.timestampNs == frame.timestampNs && output.worldFromCamera == frame.worldFromCamera)
        return output.copy(detections = tracker.update(frame.timestampNs, output.detections))
    }
    @Synchronized fun resetTracks() { check(!closed); tracker.reset() }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        try { tracker.close() } catch (error: Exception) { failure = error }
        try { delegate.close() } catch (error: Exception) {
            if (failure == null) failure = error else failure?.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
