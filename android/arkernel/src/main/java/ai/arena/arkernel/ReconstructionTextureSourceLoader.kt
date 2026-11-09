package ai.arena.arkernel

enum class CanonicalTexturePixelFormat { GRAY8, RGBA8, I420 }

data class ReconstructionTextureSourceRequest(
    val frameId: Long,
    val reference: String,
    val width: Int,
    val height: Int,
)

data class LoadedReconstructionTextureSource(
    val frameId: Long,
    val timestampNs: Long,
    val reference: String,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val format: CanonicalTexturePixelFormat,
    /** Loader-owned canonical bytes; callers must not mutate during native use. */
    val bytes: ByteArray,
    val archivedEncoding: ReconstructionImageEncoding,
    val visibilityDepth: MetricDepthBuffer? = null,
    /** Exact archived provenance, including an optimized pose when published. */
    val frameMetadata: ReconstructionFrameMetadata? = null,
) {
    init {
        frameMetadata?.let { metadata ->
            require(metadata.frameId == frameId && metadata.timestampNs == timestampNs &&
                metadata.width == width && metadata.height == height &&
                metadata.encoding == archivedEncoding) {
                "Loaded source frame metadata does not match canonical source"
            }
        }
    }
}

data class ReconstructionTextureSourceLoadOptions(
    val maxSources: Int = 256,
    val maxCanonicalBytes: Long = 512L * 1024L * 1024L,
    val maxVisibilityDepthBytes: Long = 512L * 1024L * 1024L,
    val decode: ReconstructionTextureDecodeOptions = ReconstructionTextureDecodeOptions(),
) {
    init {
        require(maxSources in 1..4_096)
        require(maxCanonicalBytes in 1..(2L shl 30))
        require(maxVisibilityDepthBytes in 1..(2L shl 30))
    }
}

data class LoadedReconstructionTextureSources(
    val sources: List<LoadedReconstructionTextureSource>,
    val archivedBytes: Long,
    val canonicalBytes: Long,
    val decodedCompressedSources: Int,
    val visibilityDepthBytes: Long,
    val loadedVisibilityDepthSources: Int,
    val poseManifestGeneration: Long? = null,
)

/**
 * Resolves the core's unique frame/view table against one archive and converts
 * compressed sources to canonical pixels as one fail-closed operation. Exact
 * archived metadata, including an accepted optimized pose and its manifest
 * generation, travels with each owned canonical source.
 */
class ReconstructionTextureSourceLoader(
    private val archive: ReconstructionFrameArchive,
    private val options: ReconstructionTextureSourceLoadOptions =
        ReconstructionTextureSourceLoadOptions(),
) {
    @Synchronized
    fun load(requests: List<ReconstructionTextureSourceRequest>):
        LoadedReconstructionTextureSources {
        require(requests.isNotEmpty() && requests.size <= options.maxSources)
        require(requests.zipWithNext().all { (first, second) -> first.frameId < second.frameId }) {
            "Texture source requests must use unique ascending frame IDs"
        }
        require(requests.map { it.reference }.toSet().size == requests.size) {
            "Texture source references must be unique"
        }
        val output = ArrayList<LoadedReconstructionTextureSource>(requests.size)
        val poseManifest = archive.loadPoseManifest()
        var archivedBytes = 0L
        var canonicalBytes = 0L
        var decodedSources = 0
        var visibilityDepthBytes = 0L
        var loadedVisibilityDepthSources = 0
        requests.forEach { request ->
            require(request.frameId > 0 && request.width > 0 && request.height > 0)
            val frame = requireNotNull(archive.load(request.reference)) {
                "Archived texture source is missing"
            }
            val archivedMetadata = frame.metadata
            require(archivedMetadata.frameId == request.frameId &&
                archivedMetadata.width == request.width &&
                archivedMetadata.height == request.height) {
                "Archived texture source provenance does not match view table"
            }
            val optimizedPose = poseManifest?.poses?.firstOrNull {
                it.frameId == archivedMetadata.frameId &&
                    it.timestampNs == archivedMetadata.timestampNs
            }
            val metadata = optimizedPose?.let { pose ->
                archivedMetadata.copy(worldFromCamera = pose.worldFromCamera.copyOf(),
                    translationSigmaMeters = pose.translationSigmaMeters,
                    rotationSigmaDegrees = pose.rotationSigmaDegrees)
            } ?: archivedMetadata.copy(
                worldFromCamera = archivedMetadata.worldFromCamera.copyOf())
            archivedBytes = Math.addExact(archivedBytes, frame.imageBytes.size.toLong())
            val expectedCanonicalBytes = when (metadata.encoding) {
                ReconstructionImageEncoding.JPEG, ReconstructionImageEncoding.PNG ->
                    Math.multiplyExact(Math.multiplyExact(metadata.width.toLong(),
                        metadata.height.toLong()), 4L)
                else -> frame.imageBytes.size.toLong()
            }
            require(expectedCanonicalBytes <= options.maxCanonicalBytes - canonicalBytes) {
                "Canonical texture source byte budget exceeded"
            }
            val source = when (metadata.encoding) {
                ReconstructionImageEncoding.JPEG, ReconstructionImageEncoding.PNG -> {
                    val decoded = ReconstructionTextureSourceDecoder.decodeCompressed(frame,
                        options.decode)
                    decodedSources++
                    LoadedReconstructionTextureSource(metadata.frameId, metadata.timestampNs, request.reference,
                        metadata.width, metadata.height, metadata.width * 4,
                        CanonicalTexturePixelFormat.RGBA8, decoded.rgba8, metadata.encoding,
                        frameMetadata = metadata)
                }
                ReconstructionImageEncoding.GRAY8 ->
                    LoadedReconstructionTextureSource(metadata.frameId, metadata.timestampNs, request.reference,
                        metadata.width, metadata.height, metadata.rowStrideBytes,
                        CanonicalTexturePixelFormat.GRAY8, frame.imageBytes, metadata.encoding,
                        frameMetadata = metadata)
                ReconstructionImageEncoding.I420 ->
                    LoadedReconstructionTextureSource(metadata.frameId, metadata.timestampNs, request.reference,
                        metadata.width, metadata.height, metadata.width,
                        CanonicalTexturePixelFormat.I420, frame.imageBytes, metadata.encoding,
                        frameMetadata = metadata)
            }
            require(source.bytes.size.toLong() == expectedCanonicalBytes ||
                metadata.encoding == ReconstructionImageEncoding.GRAY8) {
                "Canonical texture source size is inconsistent"
            }
            val archivedDepth = archive.loadVisibilityDepth(request.reference)
            val depthBytes = archivedDepth?.let { depth ->
                val planes = 1 + (if (depth.confidence == null) 0 else 1) +
                    (if (depth.uncertaintyMeters == null) 0 else 1)
                Math.multiplyExact(Math.multiplyExact(depth.width.toLong(),
                    depth.height.toLong()), Math.multiplyExact(planes.toLong(), 4L))
            } ?: 0L
            require(depthBytes <= options.maxVisibilityDepthBytes - visibilityDepthBytes) {
                "Visibility depth byte budget exceeded"
            }
            canonicalBytes = Math.addExact(canonicalBytes, source.bytes.size.toLong())
            visibilityDepthBytes = Math.addExact(visibilityDepthBytes, depthBytes)
            if (archivedDepth != null) loadedVisibilityDepthSources++
            output += source.copy(visibilityDepth = archivedDepth?.toMetricDepthBuffer())
        }
        return LoadedReconstructionTextureSources(output, archivedBytes, canonicalBytes,
            decodedSources, visibilityDepthBytes, loadedVisibilityDepthSources,
            poseManifest?.generation)
    }
}
