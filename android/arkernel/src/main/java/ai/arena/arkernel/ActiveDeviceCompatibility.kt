package ai.arena.arkernel

import kotlin.math.abs

private const val MAX_ACTIVE_CAMERA_SAMPLES = 2_048
private const val MAX_ACTIVE_IMU_SAMPLES = 8_192

enum class CameraTimingSampleKind { IMAGE, CAPTURE_RESULT }
enum class ImuTimingSampleKind { ACCELEROMETER, GYROSCOPE }
enum class ObservedCameraTimebase { REALTIME, UPTIME, AMBIGUOUS, UNRESOLVED }

data class CameraTimingSample(
    val kind: CameraTimingSampleKind,
    val sensorTimestampNs: Long,
    val callbackElapsedRealtimeNs: Long,
    val callbackUptimeNs: Long,
)

data class ImuTimingSample(
    val kind: ImuTimingSampleKind,
    val sensorTimestampNs: Long,
    val callbackElapsedRealtimeNs: Long,
)

enum class ActiveCompatibilityReasonCode {
    INSUFFICIENT_CAMERA_SAMPLES,
    INSUFFICIENT_ACCELEROMETER_SAMPLES,
    INSUFFICIENT_GYROSCOPE_SAMPLES,
    NON_MONOTONIC_CAMERA_TIMESTAMPS,
    NON_MONOTONIC_ACCELEROMETER_TIMESTAMPS,
    NON_MONOTONIC_GYROSCOPE_TIMESTAMPS,
    CAMERA_CAPTURE_TIMESTAMP_MISMATCH,
    CAMERA_FRAME_RATE_TOO_LOW,
    ACCELEROMETER_RATE_TOO_LOW,
    GYROSCOPE_RATE_TOO_LOW,
    ACCELEROMETER_TIMESTAMP_DOMAIN_INVALID,
    GYROSCOPE_TIMESTAMP_DOMAIN_INVALID,
    CAMERA_FRAME_GAPS_EXCESSIVE,
    CAMERA_TIMESTAMP_DOMAIN_NOT_REALTIME,
    CAMERA_IMU_WINDOWS_DO_NOT_OVERLAP,
    CAMERA_CALIBRATION_UNTRUSTED,
    CAMERA_IMU_EXTRINSICS_UNTRUSTED,
    SAMPLE_BUDGET_EXCEEDED,
    STATIC_COMPATIBILITY_BLOCKED,
}

data class ActiveCompatibilityConfig(
    val minimumCameraSamples: Int = 20,
    val minimumAccelerometerSamples: Int = 50,
    val minimumGyroscopeSamples: Int = 50,
    val minimumCameraRateHz: Double = 15.0,
    val minimumAccelerometerRateHz: Double = 50.0,
    val minimumGyroscopeRateHz: Double = 50.0,
    val minimumCaptureMatchRatio: Double = 0.90,
    val maximumLargeFrameGapRatio: Double = 0.10,
    val largeFrameGapFactor: Double = 2.5,
    val timebaseDecisionMarginNs: Long = 5_000_000,
    val maximumMedianImuCallbackLatencyNs: Long = 250_000_000,
) {
    init {
        require(minimumCameraSamples in 3..MAX_ACTIVE_CAMERA_SAMPLES)
        require(minimumAccelerometerSamples in 3..MAX_ACTIVE_IMU_SAMPLES)
        require(minimumGyroscopeSamples in 3..MAX_ACTIVE_IMU_SAMPLES)
        require(minimumCameraRateHz in 1.0..240.0)
        require(minimumAccelerometerRateHz in 1.0..2_000.0)
        require(minimumGyroscopeRateHz in 1.0..2_000.0)
        require(minimumCaptureMatchRatio in 0.0..1.0)
        require(maximumLargeFrameGapRatio in 0.0..1.0)
        require(largeFrameGapFactor in 1.1..20.0)
        require(timebaseDecisionMarginNs in 0..1_000_000_000)
        require(maximumMedianImuCallbackLatencyNs in 1_000_000..5_000_000_000)
    }
}

data class TimingStreamStatistics(
    val sampleCount: Int,
    val monotonic: Boolean,
    val medianRateHz: Double,
    val medianIntervalNs: Long,
    val medianAbsoluteDeviationNs: Long,
    val largeGapRatio: Double,
    val firstTimestampNs: Long,
    val lastTimestampNs: Long,
)

data class ActiveCompatibilityReport(
    val observedCameraTimebase: ObservedCameraTimebase,
    val camera: TimingStreamStatistics,
    val accelerometer: TimingStreamStatistics,
    val gyroscope: TimingStreamStatistics,
    val captureTimestampMatchRatio: Double,
    val cameraImuOverlapNs: Long,
    val accelerometerMedianCallbackLatencyNs: Long,
    val gyroscopeMedianCallbackLatencyNs: Long,
    val promotedTier: DeviceCompatibilityTier,
    val blockingReasons: List<ActiveCompatibilityReasonCode>,
) {
    init {
        require(captureTimestampMatchRatio in 0.0..1.0)
        require(cameraImuOverlapNs >= 0 && accelerometerMedianCallbackLatencyNs >= 0 &&
            gyroscopeMedianCallbackLatencyNs >= 0)
        require(blockingReasons.distinct().size == blockingReasons.size)
        if (promotedTier == DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC)
            require(blockingReasons.isEmpty())
    }
}

/** Bounded host-fed active test. It owns scalar timestamps only, never images or IMU values. */
class ActiveDeviceCompatibilityProbe(
    private val staticReport: DeviceCompatibilityReport,
    private val calibrationTrusted: Boolean,
    private val cameraImuExtrinsicsTrusted: Boolean,
    private val config: ActiveCompatibilityConfig = ActiveCompatibilityConfig(),
) {
    private val imageTimestamps = ArrayList<Long>()
    private val captureTimestamps = ArrayList<Long>()
    private val cameraElapsedResiduals = ArrayList<Long>()
    private val cameraUptimeResiduals = ArrayList<Long>()
    private val accelerometerTimestamps = ArrayList<Long>()
    private val gyroscopeTimestamps = ArrayList<Long>()
    private val accelerometerCallbackLatencies = ArrayList<Long>()
    private val gyroscopeCallbackLatencies = ArrayList<Long>()
    private var budgetExceeded = false
    @Volatile private var completedReport: ActiveCompatibilityReport? = null

    val isFinished: Boolean get() = completedReport != null
    val metricAdmissionGranted: Boolean
        get() = completedReport?.promotedTier == DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC

    @Synchronized
    fun addCameraSample(sample: CameraTimingSample): Boolean {
        if (completedReport != null || sample.sensorTimestampNs <= 0 || sample.callbackElapsedRealtimeNs <= 0 ||
            sample.callbackUptimeNs <= 0) return false
        val target = if (sample.kind == CameraTimingSampleKind.IMAGE)
            imageTimestamps else captureTimestamps
        if (target.size == MAX_ACTIVE_CAMERA_SAMPLES) {
            budgetExceeded = true
            return false
        }
        target += sample.sensorTimestampNs
        if (sample.kind == CameraTimingSampleKind.IMAGE) {
            cameraElapsedResiduals += absDifference(sample.callbackElapsedRealtimeNs,
                sample.sensorTimestampNs)
            cameraUptimeResiduals += absDifference(sample.callbackUptimeNs,
                sample.sensorTimestampNs)
        }
        return true
    }

    @Synchronized
    fun addImuSample(sample: ImuTimingSample): Boolean {
        if (completedReport != null || sample.sensorTimestampNs <= 0 || sample.callbackElapsedRealtimeNs <= 0) return false
        val target = if (sample.kind == ImuTimingSampleKind.ACCELEROMETER)
            accelerometerTimestamps else gyroscopeTimestamps
        if (target.size == MAX_ACTIVE_IMU_SAMPLES) {
            budgetExceeded = true
            return false
        }
        target += sample.sensorTimestampNs
        val latency = absDifference(sample.callbackElapsedRealtimeNs, sample.sensorTimestampNs)
        if (sample.kind == ImuTimingSampleKind.ACCELEROMETER)
            accelerometerCallbackLatencies += latency else gyroscopeCallbackLatencies += latency
        return true
    }

    @Synchronized
    fun finish(): ActiveCompatibilityReport {
        completedReport?.let { return it }
        val cameraStats = statistics(imageTimestamps, config.largeFrameGapFactor)
        val accelerometerStats = statistics(accelerometerTimestamps,
            config.largeFrameGapFactor)
        val gyroscopeStats = statistics(gyroscopeTimestamps, config.largeFrameGapFactor)
        val imageSet = imageTimestamps.toHashSet()
        val matched = captureTimestamps.count { it in imageSet }
        val denominator = maxOf(imageTimestamps.size, captureTimestamps.size)
        val matchRatio = if (denominator == 0) 0.0 else matched.toDouble() / denominator
        val timebase = classifyTimebase(cameraElapsedResiduals, cameraUptimeResiduals,
            config.timebaseDecisionMarginNs)
        val overlap = overlapNs(cameraStats, accelerometerStats, gyroscopeStats)
        val accelerometerLatency = median(accelerometerCallbackLatencies)
        val gyroscopeLatency = median(gyroscopeCallbackLatencies)
        val reasons = mutableListOf<ActiveCompatibilityReasonCode>()
        if (imageTimestamps.size < config.minimumCameraSamples)
            reasons += ActiveCompatibilityReasonCode.INSUFFICIENT_CAMERA_SAMPLES
        if (accelerometerTimestamps.size < config.minimumAccelerometerSamples)
            reasons += ActiveCompatibilityReasonCode.INSUFFICIENT_ACCELEROMETER_SAMPLES
        if (gyroscopeTimestamps.size < config.minimumGyroscopeSamples)
            reasons += ActiveCompatibilityReasonCode.INSUFFICIENT_GYROSCOPE_SAMPLES
        if (!cameraStats.monotonic)
            reasons += ActiveCompatibilityReasonCode.NON_MONOTONIC_CAMERA_TIMESTAMPS
        if (!accelerometerStats.monotonic)
            reasons += ActiveCompatibilityReasonCode.NON_MONOTONIC_ACCELEROMETER_TIMESTAMPS
        if (!gyroscopeStats.monotonic)
            reasons += ActiveCompatibilityReasonCode.NON_MONOTONIC_GYROSCOPE_TIMESTAMPS
        if (matchRatio < config.minimumCaptureMatchRatio)
            reasons += ActiveCompatibilityReasonCode.CAMERA_CAPTURE_TIMESTAMP_MISMATCH
        if (cameraStats.medianRateHz < config.minimumCameraRateHz)
            reasons += ActiveCompatibilityReasonCode.CAMERA_FRAME_RATE_TOO_LOW
        if (accelerometerStats.medianRateHz < config.minimumAccelerometerRateHz)
            reasons += ActiveCompatibilityReasonCode.ACCELEROMETER_RATE_TOO_LOW
        if (gyroscopeStats.medianRateHz < config.minimumGyroscopeRateHz)
            reasons += ActiveCompatibilityReasonCode.GYROSCOPE_RATE_TOO_LOW
        if (accelerometerLatency > config.maximumMedianImuCallbackLatencyNs)
            reasons += ActiveCompatibilityReasonCode.ACCELEROMETER_TIMESTAMP_DOMAIN_INVALID
        if (gyroscopeLatency > config.maximumMedianImuCallbackLatencyNs)
            reasons += ActiveCompatibilityReasonCode.GYROSCOPE_TIMESTAMP_DOMAIN_INVALID
        if (cameraStats.largeGapRatio > config.maximumLargeFrameGapRatio)
            reasons += ActiveCompatibilityReasonCode.CAMERA_FRAME_GAPS_EXCESSIVE
        if (timebase != ObservedCameraTimebase.REALTIME)
            reasons += ActiveCompatibilityReasonCode.CAMERA_TIMESTAMP_DOMAIN_NOT_REALTIME
        if (overlap == 0L)
            reasons += ActiveCompatibilityReasonCode.CAMERA_IMU_WINDOWS_DO_NOT_OVERLAP
        if (!calibrationTrusted)
            reasons += ActiveCompatibilityReasonCode.CAMERA_CALIBRATION_UNTRUSTED
        if (!cameraImuExtrinsicsTrusted)
            reasons += ActiveCompatibilityReasonCode.CAMERA_IMU_EXTRINSICS_UNTRUSTED
        if (budgetExceeded)
            reasons += ActiveCompatibilityReasonCode.SAMPLE_BUDGET_EXCEEDED
        if (!staticReport.standaloneVioCandidate)
            reasons += ActiveCompatibilityReasonCode.STATIC_COMPATIBILITY_BLOCKED
        val distinctReasons = reasons.distinct()
        val promoted = if (distinctReasons.isEmpty())
            DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC
        else staticReport.provisionalTier
        return ActiveCompatibilityReport(timebase, cameraStats, accelerometerStats,
            gyroscopeStats, matchRatio.coerceIn(0.0, 1.0), overlap,
            accelerometerLatency, gyroscopeLatency, promoted, distinctReasons).also { completedReport = it }
    }
}

private fun statistics(values: List<Long>, largeGapFactor: Double): TimingStreamStatistics {
    if (values.isEmpty()) return TimingStreamStatistics(0, true, 0.0, 0, 0, 0.0, 0, 0)
    var monotonic = true
    val intervals = ArrayList<Long>(maxOf(0, values.size - 1))
    for (index in 1 until values.size) {
        val interval = values[index] - values[index - 1]
        if (interval <= 0) monotonic = false else intervals += interval
    }
    val median = median(intervals)
    val deviations = if (median == 0L) emptyList() else intervals.map {
        absDifference(it, median)
    }
    val mad = median(deviations)
    val largeThreshold = median * largeGapFactor
    val largeGaps = if (median == 0L) 0 else intervals.count { it > largeThreshold }
    val rate = if (median > 0L) 1_000_000_000.0 / median else 0.0
    val ratio = if (intervals.isEmpty()) 0.0 else largeGaps.toDouble() / intervals.size
    return TimingStreamStatistics(values.size, monotonic, rate, median, mad, ratio,
        values.first(), values.last())
}

private fun classifyTimebase(elapsedResiduals: List<Long>, uptimeResiduals: List<Long>,
    marginNs: Long): ObservedCameraTimebase {
    if (elapsedResiduals.isEmpty() || elapsedResiduals.size != uptimeResiduals.size)
        return ObservedCameraTimebase.UNRESOLVED
    val elapsed = median(elapsedResiduals)
    val uptime = median(uptimeResiduals)
    return when {
        elapsed + marginNs < uptime -> ObservedCameraTimebase.REALTIME
        uptime + marginNs < elapsed -> ObservedCameraTimebase.UPTIME
        else -> ObservedCameraTimebase.AMBIGUOUS
    }
}

private fun overlapNs(camera: TimingStreamStatistics, accelerometer: TimingStreamStatistics,
    gyroscope: TimingStreamStatistics): Long {
    if (camera.sampleCount == 0 || accelerometer.sampleCount == 0 || gyroscope.sampleCount == 0)
        return 0
    val start = maxOf(camera.firstTimestampNs, accelerometer.firstTimestampNs,
        gyroscope.firstTimestampNs)
    val end = minOf(camera.lastTimestampNs, accelerometer.lastTimestampNs,
        gyroscope.lastTimestampNs)
    return maxOf(0, end - start)
}

private fun median(values: List<Long>): Long {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle]
    else sorted[middle - 1] / 2 + sorted[middle] / 2 +
        ((sorted[middle - 1] and 1L) + (sorted[middle] and 1L)) / 2
}

private fun absDifference(a: Long, b: Long): Long = if (a >= b) a - b else b - a
