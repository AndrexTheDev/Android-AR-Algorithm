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
    val reference: String,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val format: CanonicalTexturePixelFormat,
    /** Loader-owned canonical bytes; callers must not mutate during native use. */
    val bytes: ByteArray,
    val archivedEncoding: ReconstructionImageEncoding,
)

data class ReconstructionTextureSourceLoadOptions(
    val maxSources: Int = 256,
    val maxCanonicalBytes: Long = 512L * 1024L * 1024L,
    val decode: ReconstructionTextureDecodeOptions = ReconstructionTextureDecodeOptions(),
) {
    init {
        require(maxSources in 1..4_096)
        require(maxCanonicalBytes in 1..(2L shl 30))
    }
}

data class LoadedReconstructionTextureSources(
    val sources: List<LoadedReconstructionTextureSource>,
    val archivedBytes: Long,
    val canonicalBytes: Long,
    val decodedCompressedSources: Int,
)

/**
 * Resolves the core's unique frame/view table against one archive and converts
 * compressed sources to canonical pixels as one fail-closed operation.
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
        var archivedBytes = 0L
        var canonicalBytes = 0L
        var decodedSources = 0
        requests.forEach { request ->
            require(request.frameId > 0 && request.width > 0 && request.height > 0)
            val frame = requireNotNull(archive.load(request.reference)) {
                "Archived texture source is missing"
            }
            val metadata = frame.metadata
            require(metadata.frameId == request.frameId && metadata.width == request.width &&
                metadata.height == request.height) {
                "Archived texture source provenance does not match view table"
            }
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
                    LoadedReconstructionTextureSource(metadata.frameId, request.reference,
                        metadata.width, metadata.height, metadata.width * 4,
                        CanonicalTexturePixelFormat.RGBA8, decoded.rgba8, metadata.encoding)
                }
                ReconstructionImageEncoding.GRAY8 ->
                    LoadedReconstructionTextureSource(metadata.frameId, request.reference,
                        metadata.width, metadata.height, metadata.rowStrideBytes,
                        CanonicalTexturePixelFormat.GRAY8, frame.imageBytes, metadata.encoding)
                ReconstructionImageEncoding.I420 ->
                    LoadedReconstructionTextureSource(metadata.frameId, request.reference,
                        metadata.width, metadata.height, metadata.width,
                        CanonicalTexturePixelFormat.I420, frame.imageBytes, metadata.encoding)
            }
            require(source.bytes.size.toLong() == expectedCanonicalBytes ||
                metadata.encoding == ReconstructionImageEncoding.GRAY8) {
                "Canonical texture source size is inconsistent"
            }
            canonicalBytes = Math.addExact(canonicalBytes, source.bytes.size.toLong())
            output += source
        }
        return LoadedReconstructionTextureSources(output, archivedBytes, canonicalBytes,
            decodedSources)
    }
}
