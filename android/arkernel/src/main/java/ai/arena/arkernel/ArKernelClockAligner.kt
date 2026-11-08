package ai.arena.arkernel

import kotlin.math.abs

data class ClockAlignmentSnapshot(
    val scale: Double,
    val offsetNs: Double,
    val rmsErrorNs: Double,
    val observationCount: Long,
    val sourceSpanNs: Long,
) {
    val driftPartsPerMillion: Double get() = (scale - 1.0) * 1_000_000.0

    init {
        require(scale.isFinite() && offsetNs.isFinite() && rmsErrorNs.isFinite())
        require(scale > 0.0 && rmsErrorNs >= 0.0 && observationCount >= 2 && sourceSpanNs > 0)
    }
}

enum class CameraClockDriftFailureReason { DRIFT_EXCEEDED, RESIDUAL_EXCEEDED }

data class CameraClockDriftPolicy(
    val minimumSourceSpanNs: Long = 10_000_000_000L,
    val maximumAbsoluteDriftPartsPerMillion: Double = 1_000.0,
    val maximumRmsErrorNs: Double = 50_000_000.0,
) {
    init {
        require(minimumSourceSpanNs in 1_000_000_000L..300_000_000_000L)
        require(maximumAbsoluteDriftPartsPerMillion in 1.0..100_000.0)
        require(maximumRmsErrorNs in 1_000.0..1_000_000_000.0)
    }

    fun evaluate(snapshot: ClockAlignmentSnapshot): CameraClockDriftFailureReason? {
        if (snapshot.sourceSpanNs < minimumSourceSpanNs) return null
        if (abs(snapshot.driftPartsPerMillion) > maximumAbsoluteDriftPartsPerMillion)
            return CameraClockDriftFailureReason.DRIFT_EXCEEDED
        if (snapshot.rmsErrorNs > maximumRmsErrorNs)
            return CameraClockDriftFailureReason.RESIDUAL_EXCEEDED
        return null
    }
}

/** Lifecycle-safe JNI owner for the robust bounded native affine clock fit. */
class ArKernelClockAligner(
    capacity: Int = 64,
    minimumObservations: Int = 12,
) : AutoCloseable {
    private var nativeHandle = nativeCreate(capacity, minimumObservations).also {
        require(it != 0L) { "Invalid native clock aligner configuration" }
    }

    @Synchronized
    fun addObservation(sourceTimestampNs: Long, coreTimestampNs: Long): Boolean {
        checkOpen()
        require(sourceTimestampNs > 0 && coreTimestampNs > 0)
        return nativeAddObservation(nativeHandle, sourceTimestampNs, coreTimestampNs)
    }

    @Synchronized
    fun alignment(): ClockAlignmentSnapshot? {
        checkOpen()
        val metadata = LongArray(2)
        val values = DoubleArray(3)
        if (!nativeAlignment(nativeHandle, metadata, values)) return null
        return ClockAlignmentSnapshot(values[0], values[1], values[2], metadata[0], metadata[1])
    }

    @Synchronized
    fun reset() { checkOpen(); nativeReset(nativeHandle) }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun checkOpen() = check(nativeHandle != 0L) { "Clock aligner is closed" }

    private external fun nativeCreate(capacity: Int, minimumObservations: Int): Long
    private external fun nativeAddObservation(handle: Long, sourceTimestampNs: Long,
        coreTimestampNs: Long): Boolean
    private external fun nativeAlignment(handle: Long, metadata: LongArray,
        values: DoubleArray): Boolean
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    private companion object { init { System.loadLibrary("arkernel_jni") } }
}
