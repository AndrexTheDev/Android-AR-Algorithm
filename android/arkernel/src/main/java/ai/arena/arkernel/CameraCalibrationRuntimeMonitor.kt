package ai.arena.arkernel

import kotlin.math.abs

enum class CameraCalibrationRuntimeFailure {
    CROP_REGION_MISSING,
    CROP_REGION_CHANGED,
    VIDEO_STABILIZATION_MISSING,
    VIDEO_STABILIZATION_CHANGED,
    OPTICAL_STABILIZATION_MISSING,
    OPTICAL_STABILIZATION_CHANGED,
    DISTORTION_CORRECTION_MISSING,
    DISTORTION_CORRECTION_CHANGED,
    FOCAL_LENGTH_MISSING,
    FOCAL_LENGTH_CHANGED,
    FOCUS_DISTANCE_MISSING,
    FOCUS_DISTANCE_CHANGED,
}

data class CameraCalibrationCaptureState(
    val cropLeft: Int?,
    val cropTop: Int?,
    val cropWidth: Int?,
    val cropHeight: Int?,
    val videoStabilizationMode: Int?,
    val opticalStabilizationMode: Int?,
    val distortionCorrectionMode: Int?,
    val focalLengthMm: Double?,
    val focusDistanceDiopters: Double?,
)

/** Terminal exact-mode monitor. A mismatch never mutates calibration during an active map. */
class CameraCalibrationRuntimeMonitor(val binding: CalibrationStreamBinding) {
    @Volatile private var terminalFailure: CameraCalibrationRuntimeFailure? = null
    val failure: CameraCalibrationRuntimeFailure? get() = terminalFailure

    @Synchronized
    fun observe(state: CameraCalibrationCaptureState): CameraCalibrationRuntimeFailure? {
        terminalFailure?.let { return it }
        val cropValues = listOf(state.cropLeft, state.cropTop, state.cropWidth, state.cropHeight)
        val failure = when {
            cropValues.any { it == null } -> CameraCalibrationRuntimeFailure.CROP_REGION_MISSING
            state.cropLeft != binding.cropLeft || state.cropTop != binding.cropTop ||
                state.cropWidth != binding.cropWidth || state.cropHeight != binding.cropHeight ->
                CameraCalibrationRuntimeFailure.CROP_REGION_CHANGED
            state.videoStabilizationMode == null ->
                CameraCalibrationRuntimeFailure.VIDEO_STABILIZATION_MISSING
            state.videoStabilizationMode != binding.videoStabilizationMode ->
                CameraCalibrationRuntimeFailure.VIDEO_STABILIZATION_CHANGED
            state.opticalStabilizationMode == null ->
                CameraCalibrationRuntimeFailure.OPTICAL_STABILIZATION_MISSING
            state.opticalStabilizationMode != binding.opticalStabilizationMode ->
                CameraCalibrationRuntimeFailure.OPTICAL_STABILIZATION_CHANGED
            binding.distortionCorrectionMode >= 0 && state.distortionCorrectionMode == null ->
                CameraCalibrationRuntimeFailure.DISTORTION_CORRECTION_MISSING
            binding.distortionCorrectionMode >= 0 &&
                state.distortionCorrectionMode != binding.distortionCorrectionMode ->
                CameraCalibrationRuntimeFailure.DISTORTION_CORRECTION_CHANGED
            binding.expectedFocalLengthMm != null && state.focalLengthMm == null ->
                CameraCalibrationRuntimeFailure.FOCAL_LENGTH_MISSING
            binding.expectedFocalLengthMm != null &&
                abs(requireNotNull(state.focalLengthMm) -
                    requireNotNull(binding.expectedFocalLengthMm)) >
                binding.focalLengthToleranceMm -> CameraCalibrationRuntimeFailure.FOCAL_LENGTH_CHANGED
            binding.expectedFocusDistanceDiopters != null && state.focusDistanceDiopters == null ->
                CameraCalibrationRuntimeFailure.FOCUS_DISTANCE_MISSING
            binding.expectedFocusDistanceDiopters != null &&
                abs(requireNotNull(state.focusDistanceDiopters) -
                    requireNotNull(binding.expectedFocusDistanceDiopters)) >
                binding.focusDistanceToleranceDiopters ->
                CameraCalibrationRuntimeFailure.FOCUS_DISTANCE_CHANGED
            else -> null
        }
        if (failure != null) terminalFailure = failure
        return failure
    }

    @Synchronized fun reset() { terminalFailure = null }
}
