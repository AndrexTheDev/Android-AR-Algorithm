package ai.arena.arkernel

import java.nio.ByteBuffer
import kotlin.math.sqrt

private const val MAX_TARGET_CORNERS = 4096

data class PlanarCalibrationTarget(
    val targetId: String,
    val referenceSpanMeters: Double,
    val referenceSpanUncertaintyMeters: Double,
    val expectedCornerCount: Int,
) {
    init {
        require(targetId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(referenceSpanMeters.isFinite() && referenceSpanMeters > 0.0)
        require(referenceSpanUncertaintyMeters.isFinite() &&
            referenceSpanUncertaintyMeters in 0.0..referenceSpanMeters * 0.02)
        require(expectedCornerCount in 4..MAX_TARGET_CORNERS)
    }
}

/** Direct Y plane is valid only for the synchronous adapter call. */
data class CalibrationTargetFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val pixelStrideBytes: Int,
    val yPlane: ByteBuffer,
) {
    init {
        require(timestampNs > 0 && width >= 3 && height >= 3)
        require(rowStrideBytes > 0 && pixelStrideBytes > 0 && yPlane.isDirect)
        val required = Math.addExact(Math.multiplyExact(height - 1, rowStrideBytes),
            Math.addExact(Math.multiplyExact(width - 1, pixelStrideBytes), 1))
        require(required <= yPlane.remaining())
    }
}

class CalibrationTargetDetection(
    val timestampNs: Long,
    val targetId: String,
    cornerPixels: DoubleArray,
    val targetCenterCameraMeters: Vector3,
    val targetNormalCamera: Vector3,
    val measuredReferenceSpanMeters: Double,
    val reprojectionRmsePixels: Double,
) {
    private val cornerPixelValues: DoubleArray = cornerPixels.copyOf()
    val cornerPixels: DoubleArray get() = cornerPixelValues.copyOf()

    init {
        require(timestampNs > 0 && targetId.isNotBlank())
        require(cornerPixels.size in 8..MAX_TARGET_CORNERS * 2 && cornerPixels.size % 2 == 0)
        require(cornerPixels.all { it.isFinite() })
        require(listOf(targetCenterCameraMeters.x, targetCenterCameraMeters.y,
            targetCenterCameraMeters.z, targetNormalCamera.x, targetNormalCamera.y,
            targetNormalCamera.z, measuredReferenceSpanMeters, reprojectionRmsePixels)
            .all { it.isFinite() })
        require(targetCenterCameraMeters.z > 0.0 && measuredReferenceSpanMeters > 0.0 &&
            reprojectionRmsePixels >= 0.0)
    }
}

/** Optional OpenCV/AprilTag/custom implementations live outside the core AAR. */
fun interface CalibrationTargetPoseAdapter {
    fun detect(frame: CalibrationTargetFrame, target: PlanarCalibrationTarget,
        calibration: CameraCalibration): CalibrationTargetDetection?
}

enum class CalibrationTargetProcessResult {
    ACCEPTED,
    NO_TARGET,
    MALFORMED_DETECTION,
    QUALITY_REJECTED,
}

class CalibrationTargetPipeline(
    private val target: PlanarCalibrationTarget,
    private val calibration: CameraCalibration,
    private val adapter: CalibrationTargetPoseAdapter,
    val probe: CalibrationTargetCaptureProbe = CalibrationTargetCaptureProbe(),
) {
    init { require(calibration.width > 0 && calibration.height > 0) }

    /** Adapter and quality evaluation are synchronous; no frame buffer may be retained. */
    fun process(frame: CalibrationTargetFrame): CalibrationTargetProcessResult {
        if (frame.width != calibration.width || frame.height != calibration.height)
            return CalibrationTargetProcessResult.MALFORMED_DETECTION
        val quality = runCatching { CameraFrameQualityEvaluator.evaluate(frame.yPlane,
            frame.width, frame.height, frame.rowStrideBytes, frame.pixelStrideBytes) }
            .getOrNull() ?: return CalibrationTargetProcessResult.MALFORMED_DETECTION
        val detectionResult = runCatching { adapter.detect(frame, target, calibration) }
        if (detectionResult.isFailure) return CalibrationTargetProcessResult.MALFORMED_DETECTION
        val detection = detectionResult.getOrNull()
            ?: return CalibrationTargetProcessResult.NO_TARGET
        val observation = runCatching { toObservation(frame, detection, quality) }.getOrNull()
            ?: return CalibrationTargetProcessResult.MALFORMED_DETECTION
        return if (probe.add(observation)) CalibrationTargetProcessResult.ACCEPTED
        else CalibrationTargetProcessResult.QUALITY_REJECTED
    }

    private fun toObservation(frame: CalibrationTargetFrame,
        detection: CalibrationTargetDetection, quality: CameraFrameQuality): CalibrationTargetObservation {
        require(detection.timestampNs == frame.timestampNs && detection.targetId == target.targetId)
        val corners = detection.cornerPixels
        require(corners.size / 2 == target.expectedCornerCount)
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        var index = 0
        while (index < corners.size) {
            val x = corners[index]
            val y = corners[index + 1]
            require(x in 0.0..(frame.width - 1).toDouble() &&
                y in 0.0..(frame.height - 1).toDouble())
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
            index += 2
        }
        val coverage = ((maxX - minX) * (maxY - minY) /
            (frame.width.toDouble() * frame.height)).coerceIn(0.0, 1.0)
        val center = detection.targetCenterCameraMeters
        val centerDistance = sqrt(center.x * center.x + center.y * center.y + center.z * center.z)
        return CalibrationTargetObservation(detection.measuredReferenceSpanMeters,
            target.referenceSpanMeters, target.referenceSpanUncertaintyMeters,
            centerDistance, detection.targetNormalCamera, target.expectedCornerCount,
            coverage, quality.sharpness, detection.reprojectionRmsePixels)
    }
}

enum class CalibrationGuidanceState {
    SEEK_TARGET,
    IMPROVE_IMAGE_QUALITY,
    CHANGE_DISTANCE,
    TILT_TARGET_OR_PHONE,
    CAPTURE_MORE,
    READY_TO_FINISH,
    COMPLETE,
    FAILED,
}

/** Deterministic UI guidance over pipeline outcomes and bounded coverage progress. */
class CalibrationGuidanceController(private val pipeline: CalibrationTargetPipeline) {
    @Volatile private var terminal: CalibrationGuidanceState? = null

    fun process(frame: CalibrationTargetFrame): CalibrationGuidanceState {
        terminal?.let { return it }
        return when (pipeline.process(frame)) {
            CalibrationTargetProcessResult.NO_TARGET -> CalibrationGuidanceState.SEEK_TARGET
            CalibrationTargetProcessResult.MALFORMED_DETECTION -> {
                terminal = CalibrationGuidanceState.FAILED
                CalibrationGuidanceState.FAILED
            }
            CalibrationTargetProcessResult.QUALITY_REJECTED ->
                CalibrationGuidanceState.IMPROVE_IMAGE_QUALITY
            CalibrationTargetProcessResult.ACCEPTED -> guidanceFromProgress(pipeline.probe.progress())
        }
    }

    fun finish(): Pair<CalibrationGuidanceState, CalibrationTargetCaptureReport> {
        val report = pipeline.probe.finish()
        val state = if (terminal == CalibrationGuidanceState.FAILED)
            CalibrationGuidanceState.FAILED
        else if (report.observable) CalibrationGuidanceState.COMPLETE
        else CalibrationGuidanceState.FAILED
        terminal = state
        return state to report
    }

    private fun guidanceFromProgress(progress: CalibrationTargetCaptureProgress): CalibrationGuidanceState =
        when {
            progress.distanceBins < progress.requiredDistanceBins &&
                progress.acceptedObservations >= 2 -> CalibrationGuidanceState.CHANGE_DISTANCE
            progress.viewBins < progress.requiredViewBins &&
                progress.acceptedObservations >= 2 -> CalibrationGuidanceState.TILT_TARGET_OR_PHONE
            progress.acceptedObservations < progress.requiredObservations ->
                CalibrationGuidanceState.CAPTURE_MORE
            progress.viewBins >= progress.requiredViewBins &&
                progress.distanceBins >= progress.requiredDistanceBins ->
                CalibrationGuidanceState.READY_TO_FINISH
            else -> CalibrationGuidanceState.CAPTURE_MORE
        }
}
