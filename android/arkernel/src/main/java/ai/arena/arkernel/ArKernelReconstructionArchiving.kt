package ai.arena.arkernel

/** Converts one timestamp-matched Camera2/session packet into the archive contract. */
fun ArKernelReconstructionFrame.toArchivedFrame(
    frameId: Long,
    translationSigmaMeters: Double,
    rotationSigmaDegrees: Double,
): ArchivedReconstructionFrame {
    val pose = requireNotNull(tracking.worldFromCamera) {
        "Reconstruction frame has no initialized world pose"
    }
    require(pose.timestampNs == camera.timestampNs) { "Camera and world pose timestamps differ" }
    val r = pose.rotation
    val p = pose.positionMeters
    return ArchivedReconstructionFrame(
        ReconstructionFrameMetadata(
            frameId = frameId,
            timestampNs = camera.timestampNs,
            width = camera.width,
            height = camera.height,
            rowStrideBytes = camera.width,
            encoding = ReconstructionImageEncoding.I420,
            exposureTimeNs = camera.exposureTimeNs,
            sensitivityIso = camera.sensitivityIso,
            worldFromCamera = doubleArrayOf(r.w, r.x, r.y, r.z, p.x, p.y, p.z),
            translationSigmaMeters = translationSigmaMeters,
            rotationSigmaDegrees = rotationSigmaDegrees,
        ),
        camera.i420Bytes,
    )
}
