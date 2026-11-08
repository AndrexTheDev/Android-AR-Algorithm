package ai.arena.arkernel

private const val MAX_CALIBRATED_STREAM_CANDIDATES = 64

data class CalibratedStreamCandidate(
    val signedProfile: SignedCalibrationProfile,
    val observedCapabilitySignature: DeviceCapabilitySignature,
)

enum class CalibratedStreamRejectionReason {
    MALFORMED_PROFILE,
    DUPLICATE_PROFILE,
    CAPABILITY_STREAM_MISMATCH,
    VERIFICATION_FAILED,
    RESOURCE_BUDGET_EXCEEDED,
}

data class CalibratedStreamRejection(
    val candidateIndex: Int,
    val profileId: String?,
    val reason: CalibratedStreamRejectionReason,
    val verificationReasons: List<CalibrationVerificationReason> = emptyList(),
)

enum class CalibratedStreamTransition {
    START_NEW_SESSION,
    KEEP_CURRENT_SESSION,
    RESTART_REQUIRED,
    NO_COMPATIBLE_PROFILE,
    UNSUPPORTED,
}

data class SelectedCalibratedStream(
    val profile: CalibrationProfile,
    val profileSha256: String,
    val resourceDecision: RuntimeResourceDecision,
) {
    fun standaloneConfig(): StandaloneArRuntimeConfig = StandaloneArRuntimeConfig(
        cameraId = profile.stream.cameraId,
        calibration = profile.calibration,
        bodyFromCameraRotation = profile.bodyFromCameraRotation,
        bodyFromCameraTranslationMeters = profile.bodyFromCameraTranslationMeters,
        maxQueuedImuSamples = resourceDecision.maximumQueuedImuSamples.takeIf { it >= 128 }
            ?: 128,
    )

    fun cameraOnlyConfig(): CameraOnlyArRuntimeConfig = CameraOnlyArRuntimeConfig(
        cameraId = profile.stream.cameraId,
        calibration = profile.calibration,
    )

    fun newRuntimeMonitor(): CameraCalibrationRuntimeMonitor =
        CameraCalibrationRuntimeMonitor(profile.stream)
}

data class CalibratedStreamSelection(
    val selected: SelectedCalibratedStream?,
    val transition: CalibratedStreamTransition,
    val rejections: List<CalibratedStreamRejection>,
)

/**
 * Verifies and ranks exact signed stream profiles. It never rescales intrinsics and never mutates
 * a running session: a changed profile is returned only as RESTART_REQUIRED.
 */
class CalibratedStreamProfileSelector(
    private val verifier: CalibrationProfileVerifier,
) {
    fun select(
        candidates: List<CalibratedStreamCandidate>,
        resourceDecision: RuntimeResourceDecision,
        nowEpochMillis: Long,
        deviceSignatureAnchor: DeviceCapabilitySignature,
        currentProfileSha256: String? = null,
    ): CalibratedStreamSelection {
        require(candidates.size <= MAX_CALIBRATED_STREAM_CANDIDATES)
        require(nowEpochMillis >= 0)
        require(currentProfileSha256 == null ||
            currentProfileSha256.matches(Regex("[0-9a-f]{64}")))
        if (resourceDecision.tier == RuntimeResourceTier.UNSUPPORTED)
            return CalibratedStreamSelection(null, CalibratedStreamTransition.UNSUPPORTED,
                emptyList())

        val accepted = mutableListOf<SelectedCalibratedStream>()
        val rejected = mutableListOf<CalibratedStreamRejection>()
        val observedHashes = mutableSetOf<String>()
        candidates.forEachIndexed { index, candidate ->
            val signed = candidate.signedProfile
            val hash = signed.profileSha256
            if (!observedHashes.add(hash)) {
                rejected += CalibratedStreamRejection(index, null,
                    CalibratedStreamRejectionReason.DUPLICATE_PROFILE)
                return@forEachIndexed
            }
            val decoded = runCatching { CalibrationProfileCodec.decode(signed.payload) }.getOrNull()
            if (decoded == null) {
                rejected += CalibratedStreamRejection(index, null,
                    CalibratedStreamRejectionReason.MALFORMED_PROFILE)
                return@forEachIndexed
            }
            if (!sameDeviceAndCamera(deviceSignatureAnchor,
                    candidate.observedCapabilitySignature) ||
                !capabilityMatchesStream(candidate.observedCapabilitySignature, decoded.stream)) {
                rejected += CalibratedStreamRejection(index, decoded.profileId,
                    CalibratedStreamRejectionReason.CAPABILITY_STREAM_MISMATCH)
                return@forEachIndexed
            }
            val verified = verifier.verify(signed, candidate.observedCapabilitySignature,
                decoded.stream, nowEpochMillis)
            val profile = verified.profile
            if (!verified.accepted || profile == null || verified.profileSha256 == null) {
                rejected += CalibratedStreamRejection(index, decoded.profileId,
                    CalibratedStreamRejectionReason.VERIFICATION_FAILED, verified.reasons)
                return@forEachIndexed
            }
            val pixels = runCatching {
                Math.multiplyExact(profile.stream.width, profile.stream.height)
            }.getOrNull()
            val admitted = pixels?.let {
                RuntimeResourcePolicy.admitCalibratedStream(resourceDecision, it)
            }
            if (admitted == null) {
                rejected += CalibratedStreamRejection(index, profile.profileId,
                    CalibratedStreamRejectionReason.RESOURCE_BUDGET_EXCEEDED)
                return@forEachIndexed
            }
            accepted += SelectedCalibratedStream(profile,
                requireNotNull(verified.profileSha256), admitted)
        }

        val selected = accepted.sortedWith(
            compareByDescending<SelectedCalibratedStream> { it.resourceDecision.liveMappingEnabled }
                .thenByDescending { it.profile.metricClaim.ordinal }
                .thenByDescending {
                    it.profile.stream.width.toLong() * it.profile.stream.height.toLong()
                }
                .thenByDescending { it.profile.evidence.maximumValidatedDistanceMeters }
                .thenBy { it.profile.evidence.reprojectionRmsePixels }
                .thenByDescending { it.profile.revision }
                .thenBy { it.profile.profileId }
                .thenBy { it.profileSha256 }
        ).firstOrNull() ?: return CalibratedStreamSelection(null,
            CalibratedStreamTransition.NO_COMPATIBLE_PROFILE, rejected)

        val transition = when (currentProfileSha256) {
            null -> CalibratedStreamTransition.START_NEW_SESSION
            selected.profileSha256 -> CalibratedStreamTransition.KEEP_CURRENT_SESSION
            else -> CalibratedStreamTransition.RESTART_REQUIRED
        }
        return CalibratedStreamSelection(selected, transition, rejected)
    }

    private fun sameDeviceAndCamera(
        anchor: DeviceCapabilitySignature,
        candidate: DeviceCapabilitySignature,
    ): Boolean {
        if (anchor.schemaVersion != candidate.schemaVersion ||
            anchor.apiLevel != candidate.apiLevel ||
            anchor.supportedAbis != candidate.supportedAbis ||
            anchor.lowRamDevice != candidate.lowRamDevice ||
            anchor.memoryClassMb != candidate.memoryClassMb ||
            anchor.buildFingerprintSha256 != candidate.buildFingerprintSha256 ||
            anchor.imu != candidate.imu) return false
        val first = anchor.camera ?: return candidate.camera == null
        val second = candidate.camera ?: return false
        return first.cameraId == second.cameraId &&
            first.physicalCameraIds == second.physicalCameraIds &&
            first.hardwareLevel == second.hardwareLevel &&
            first.timestampSource == second.timestampSource &&
            first.sensorOrientationDegrees == second.sensorOrientationDegrees &&
            first.activeArrayWidth == second.activeArrayWidth &&
            first.activeArrayHeight == second.activeArrayHeight &&
            first.preCorrectionArrayWidth == second.preCorrectionArrayWidth &&
            first.preCorrectionArrayHeight == second.preCorrectionArrayHeight &&
            first.focalLengths == second.focalLengths &&
            first.videoStabilizationModes == second.videoStabilizationModes &&
            first.opticalStabilizationModes == second.opticalStabilizationModes &&
            first.distortionCorrectionModes == second.distortionCorrectionModes &&
            first.publishesIntrinsics == second.publishesIntrinsics &&
            first.publishesDistortion == second.publishesDistortion &&
            first.publishesRollingShutterSkew == second.publishesRollingShutterSkew
    }

    private fun capabilityMatchesStream(
        signature: DeviceCapabilitySignature,
        stream: CalibrationStreamBinding,
    ): Boolean {
        val camera = signature.camera ?: return false
        if (camera.cameraId != stream.cameraId || camera.width != stream.width ||
            camera.height != stream.height) return false
        val physical = stream.physicalCameraId ?: return true
        return physical == camera.cameraId || physical in camera.physicalCameraIds
    }
}
