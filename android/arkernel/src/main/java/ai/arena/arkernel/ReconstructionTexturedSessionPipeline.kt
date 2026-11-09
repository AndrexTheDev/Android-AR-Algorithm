package ai.arena.arkernel

private const val UNVERIFIED_QUALITY = "unverified"

private fun emptyTextureTelemetry() = ReconstructionTexturePipelineTelemetry(
    LongArray(ReconstructionTexturePipelineTelemetry.FIELD_COUNT))

/** One archived calibrated frame. References stay opaque and depth companions stay frame-owned. */
data class ReconstructionTexturedSessionFrame(
    val frameId: Long,
    val reference: String,
    val calibration: CameraCalibration,
    val qualityScore: Double = 1.0,
) {
    init {
        require(frameId > 0) { "Session frame ID must be positive" }
        require(reference.isNotBlank()) { "Session frame reference is blank" }
        require(calibration.width > 0 && calibration.height > 0 &&
            calibration.fx > 0.0 && calibration.fy > 0.0 &&
            listOf(calibration.fx, calibration.fy, calibration.cx, calibration.cy,
                calibration.k1, calibration.k2, calibration.p1, calibration.p2,
                calibration.k3).all { it.isFinite() }) {
            "Session frame calibration is invalid"
        }
        require(qualityScore.isFinite() && qualityScore in 0.0..1.0) {
            "Session frame quality score must be finite and normalized"
        }
    }
}

data class ReconstructionTexturedSessionRequest(
    val assetId: String,
    val generation: Long,
    val frames: List<ReconstructionTexturedSessionFrame>,
) {
    init {
        require(assetId.isNotBlank()) { "Session asset ID is blank" }
        require(generation >= 0) { "Session generation must not be negative" }
        require(frames.isNotEmpty()) { "Session has no archived frames" }
    }
}

enum class ReconstructionTexturedSessionStage {
    SOURCES, FUSION, SOURCE_MESH, TEXTURE, PUBLICATION, COMPLETE
}

/** Exact per-frame provenance and uncertainty retained by the session result. */
data class ReconstructionTexturedSessionFrameEvidence(
    val frameId: Long,
    val timestampNs: Long,
    val reference: String,
    val width: Int,
    val height: Int,
    val calibration: CameraCalibration,
    val qualityScore: Double,
    val worldFromCamera: DoubleArray,
    val translationSigmaMeters: Double,
    val rotationSigmaDegrees: Double,
    val canonicalBytes: Int,
    val visibilityDepthBytes: Long,
) {
    init {
        require(frameId > 0 && timestampNs > 0 && width > 0 && height > 0)
        require(reference.isNotBlank() && canonicalBytes >= 0 && visibilityDepthBytes >= 0)
        require(worldFromCamera.size == 7 && worldFromCamera.all { it.isFinite() })
        require(translationSigmaMeters.isFinite() && translationSigmaMeters >= 0.0 &&
            rotationSigmaDegrees.isFinite() && rotationSigmaDegrees >= 0.0)
        require(qualityScore.isFinite() && qualityScore in 0.0..1.0)
    }
}

data class ReconstructionTexturedSessionMeshTelemetry(
    val complete: Boolean = false,
    val fusionTruncated: Boolean = false,
    val meshingTruncated: Boolean = false,
    val filteringTruncated: Boolean = false,
    val simplificationTruncated: Boolean = false,
    val sourceVertices: Long = 0,
    val sourceTriangles: Long = 0,
    val rawMeshBytes: Long = 0,
    val error: String = "",
)

/**
 * Explicit stage/telemetry result. Only [ReconstructionTexturedSessionStage.COMPLETE]
 * carries a published asset; its quality grade remains deliberately unverified.
 */
data class ReconstructionTexturedSessionResult(
    val stage: ReconstructionTexturedSessionStage,
    val complete: Boolean,
    val error: String,
    val frames: List<ReconstructionTexturedSessionFrameEvidence> = emptyList(),
    val poseManifestGeneration: Long? = null,
    val archivedBytes: Long = 0,
    val canonicalBytes: Long = 0,
    val decodedCompressedSources: Int = 0,
    val visibilityDepthBytes: Long = 0,
    val loadedVisibilityDepthSources: Int = 0,
    val fusionUpdates: List<ReconstructionFusionUpdate> = emptyList(),
    val sourceMesh: ReconstructionTexturedSessionMeshTelemetry =
        ReconstructionTexturedSessionMeshTelemetry(),
    val textureStage: ReconstructionTexturePipelineStage = ReconstructionTexturePipelineStage.NONE,
    val textureInputValid: Boolean = false,
    val textureTruncated: Boolean = false,
    val textureError: String = "",
    val textureTelemetry: ReconstructionTexturePipelineTelemetry = emptyTextureTelemetry(),
    val atlasWidth: Int = 0,
    val atlasHeight: Int = 0,
    val atlasBytes: Int = 0,
    val asset: PublishedTexturedReconstructionAsset? = null,
    val qualityGrade: String = UNVERIFIED_QUALITY,
)

/**
 * Bounded aggregate cap across the overlapping source, raw-mesh, texture-result,
 * PNG and GLB allocations. Each nested stage retains its own stricter budget.
 */
data class ReconstructionTexturedSessionOptions(
    val maxFrames: Int = 256,
    val maxSessionBytes: Long = 1L shl 30,
    val fusion: ReconstructionFusionOptions = ReconstructionFusionOptions(
        maxIntegratedFrames = 256L, maxRawMeshBytes = 64 * 1024 * 1024),
    val sourceLoad: ReconstructionTextureSourceLoadOptions =
        ReconstructionTextureSourceLoadOptions(maxSources = 256,
            maxCanonicalBytes = 128L * 1024L * 1024L,
            maxVisibilityDepthBytes = 128L * 1024L * 1024L),
    val texture: ReconstructionTexturePipelineOptions = ReconstructionTexturePipelineOptions(
        maxCandidates = 256, maxResultBytes = 128 * 1024 * 1024,
        requireVisibilityDepth = true),
    val publisher: ReconstructionTexturedAssetOptions = ReconstructionTexturedAssetOptions(
        maxGlbBytes = 128 * 1024 * 1024,
        png = ReconstructionTexturePngOptions(
            maxPixels = 16_777_216, maxEncodedBytes = 64 * 1024 * 1024)),
) {
    init {
        require(maxFrames in 1..4_096) { "Invalid session frame budget" }
        require(maxSessionBytes in 1L..(2L shl 30)) { "Invalid aggregate session byte budget" }
        require(maxFrames.toLong() <= fusion.maxIntegratedFrames) {
            "Fusion frame budget is smaller than the session frame budget"
        }
        require(maxFrames <= sourceLoad.maxSources && maxFrames <= texture.maxCandidates) {
            "Source or texture candidate budget is smaller than the session frame budget"
        }
        require(texture.requireVisibilityDepth) {
            "Session texturing requires visibility depth for every candidate"
        }
        require(fusion.maxMeshVertices >= texture.maxVertices.toLong() &&
            fusion.maxMeshTriangles >= texture.maxTriangles.toLong()) {
            "Fusion mesh budget is smaller than the texture input budget"
        }
        require(texture.maxVertices <= publisher.maxVertices &&
            texture.maxTriangles <= publisher.maxTriangles) {
            "Publisher mesh budget is smaller than the texture output budget"
        }
        require(texture.atlasWidth.toLong() * texture.atlasHeight.toLong() <=
            publisher.png.maxPixels.toLong()) {
            "PNG pixel budget is smaller than the configured atlas"
        }
        require(publisher.png.maxEncodedBytes <= publisher.maxGlbBytes) {
            "Embedded GLB budget is smaller than its PNG budget"
        }
        val aggregateBytes = listOf(sourceLoad.maxCanonicalBytes,
            sourceLoad.maxVisibilityDepthBytes, fusion.maxRawMeshBytes.toLong(),
            texture.maxResultBytes.toLong(), publisher.png.maxEncodedBytes.toLong(),
            publisher.maxGlbBytes.toLong()).fold(0L) { total, value ->
            Math.addExact(total, value)
        }
        require(aggregateBytes <= maxSessionBytes) {
            "Nested session byte budgets exceed the aggregate cap"
        }
    }
}

/**
 * One fail-closed Android transaction from bounded archived frame references to
 * a canonical embedded-textured GLB. It resolves colour and frame-owned depth
 * once, fuses the same calibrated observations, builds only a complete raw
 * source mesh, runs the complete texture graph, and atomically publishes only
 * after PNG encoding and GLB embedding succeed. No stage assigns texture
 * metadata, publishes a partial asset, or upgrades quality evidence. Hosts may
 * persist the terminal result with [ReconstructionSessionStateArchive.record].
 */
class ReconstructionTexturedSessionPipeline(
    private val frameArchive: ReconstructionFrameArchive,
    private val assetArchive: ReconstructionAssetArchive,
    private val options: ReconstructionTexturedSessionOptions =
        ReconstructionTexturedSessionOptions(),
) {
    private val sourceLoader = ReconstructionTextureSourceLoader(frameArchive, options.sourceLoad)
    private val texturePipeline = ReconstructionTexturePipeline(options.texture)
    private val publisher = ReconstructionTexturedAssetPublisher(assetArchive, options.publisher)

    @Synchronized
    fun run(request: ReconstructionTexturedSessionRequest): ReconstructionTexturedSessionResult {
        var stage = ReconstructionTexturedSessionStage.SOURCES
        var framesEvidence = emptyList<ReconstructionTexturedSessionFrameEvidence>()
        var poseManifestGeneration: Long? = null
        var archivedBytes = 0L
        var canonicalBytes = 0L
        var decodedCompressedSources = 0
        var visibilityDepthBytes = 0L
        var loadedVisibilityDepthSources = 0
        val fusionUpdates = mutableListOf<ReconstructionFusionUpdate>()
        var meshTelemetry = ReconstructionTexturedSessionMeshTelemetry()
        var textureStage = ReconstructionTexturePipelineStage.NONE
        var textureInputValid = false
        var textureTruncated = false
        var textureError = ""
        var textureTelemetry = emptyTextureTelemetry()
        var atlasWidth = 0
        var atlasHeight = 0
        var atlasBytes = 0

        fun result(
            atStage: ReconstructionTexturedSessionStage,
            errorMessage: String,
            asset: PublishedTexturedReconstructionAsset? = null,
        ): ReconstructionTexturedSessionResult {
            val isComplete = atStage == ReconstructionTexturedSessionStage.COMPLETE &&
                errorMessage.isEmpty() && asset != null
            val finalStage = when {
                isComplete -> ReconstructionTexturedSessionStage.COMPLETE
                atStage == ReconstructionTexturedSessionStage.COMPLETE ->
                    ReconstructionTexturedSessionStage.PUBLICATION
                else -> atStage
            }
            return ReconstructionTexturedSessionResult(
                stage = finalStage, complete = isComplete, error = errorMessage,
                frames = framesEvidence.toList(),
                poseManifestGeneration = poseManifestGeneration,
                archivedBytes = archivedBytes, canonicalBytes = canonicalBytes,
                decodedCompressedSources = decodedCompressedSources,
                visibilityDepthBytes = visibilityDepthBytes,
                loadedVisibilityDepthSources = loadedVisibilityDepthSources,
                fusionUpdates = fusionUpdates.toList(), sourceMesh = meshTelemetry,
                textureStage = textureStage, textureInputValid = textureInputValid,
                textureTruncated = textureTruncated, textureError = textureError,
                textureTelemetry = ReconstructionTexturePipelineTelemetry(
                    textureTelemetry.values.copyOf()),
                atlasWidth = atlasWidth, atlasHeight = atlasHeight, atlasBytes = atlasBytes,
                asset = asset, qualityGrade = UNVERIFIED_QUALITY)
        }

        return try {
            val orderedFrames = request.frames.sortedBy { it.frameId }
            require(orderedFrames.isNotEmpty() && orderedFrames.size <= options.maxFrames) {
                "Session frame count is outside its bounded budget"
            }
            require(orderedFrames.zipWithNext().all { (first, second) ->
                first.frameId < second.frameId
            }) { "Session frame IDs must be unique and ascending" }
            require(orderedFrames.map { it.reference }.toSet().size == orderedFrames.size) {
                "Session frame references must be unique"
            }
            val sourceRequests = orderedFrames.map { frame ->
                ReconstructionTextureSourceRequest(frame.frameId, frame.reference,
                    frame.calibration.width, frame.calibration.height)
            }
            val loaded = sourceLoader.load(sourceRequests)
            poseManifestGeneration = loaded.poseManifestGeneration
            archivedBytes = loaded.archivedBytes
            canonicalBytes = loaded.canonicalBytes
            decodedCompressedSources = loaded.decodedCompressedSources
            visibilityDepthBytes = loaded.visibilityDepthBytes
            loadedVisibilityDepthSources = loaded.loadedVisibilityDepthSources
            require(loaded.sources.size == orderedFrames.size &&
                loaded.loadedVisibilityDepthSources == orderedFrames.size) {
                "Every session frame requires an archived visibility-depth companion"
            }
            var previousTimestamp = 0L
            val evidence = loaded.sources.mapIndexed { index, source ->
                val frame = orderedFrames[index]
                val metadata = requireNotNull(source.frameMetadata) {
                    "Loaded source ${source.frameId} has no archived provenance"
                }
                val depth = requireNotNull(source.visibilityDepth) {
                    "Archived frame ${source.frameId} has no visibility depth companion"
                }
                require(source.frameId == frame.frameId && source.reference == frame.reference &&
                    metadata.frameId == frame.frameId &&
                    metadata.timestampNs == source.timestampNs &&
                    metadata.width == source.width && metadata.height == source.height &&
                    metadata.width == frame.calibration.width &&
                    metadata.height == frame.calibration.height &&
                    metadata.encoding == source.archivedEncoding) {
                    "Archived frame ${frame.frameId} provenance does not match the session request"
                }
                require(depth.timestampNs == source.timestampNs &&
                    depth.width == source.width && depth.height == source.height) {
                    "Archived frame ${source.frameId} visibility depth does not match its colour frame"
                }
                require(source.timestampNs > previousTimestamp) {
                    "Archived frame timestamps must be strictly increasing"
                }
                previousTimestamp = source.timestampNs
                val planes = 1 + (if (depth.confidence == null) 0 else 1) +
                    (if (depth.uncertaintyMeters == null) 0 else 1)
                val depthBytes = Math.multiplyExact(
                    Math.multiplyExact(depth.width.toLong(), depth.height.toLong()),
                    Math.multiplyExact(planes.toLong(), 4L))
                ReconstructionTexturedSessionFrameEvidence(
                    frameId = source.frameId, timestampNs = source.timestampNs,
                    reference = source.reference, width = source.width, height = source.height,
                    calibration = frame.calibration, qualityScore = frame.qualityScore,
                    worldFromCamera = metadata.worldFromCamera.copyOf(),
                    translationSigmaMeters = metadata.translationSigmaMeters,
                    rotationSigmaDegrees = metadata.rotationSigmaDegrees,
                    canonicalBytes = source.bytes.size, visibilityDepthBytes = depthBytes)
            }
            framesEvidence = evidence
            val candidates = loaded.sources.mapIndexed { index, source ->
                val frame = orderedFrames[index]
                val metadata = requireNotNull(source.frameMetadata) {
                    "Loaded source ${source.frameId} has no archived pose"
                }
                ReconstructionTextureCandidate(source, frame.calibration,
                    metadata.worldFromCamera.copyOf(), frame.qualityScore)
            }

            stage = ReconstructionTexturedSessionStage.FUSION
            val sourceMeshBuild = ReconstructionFusionPipeline(options.fusion).use { fusion ->
                loaded.sources.forEachIndexed { index, source ->
                    val frame = orderedFrames[index]
                    val metadata = requireNotNull(source.frameMetadata) {
                        "Loaded source ${source.frameId} has no archived pose"
                    }
                    val depth = requireNotNull(source.visibilityDepth) {
                        "Archived frame ${source.frameId} has no visibility depth companion"
                    }
                    val pose = WorldPose(metadata.timestampNs,
                        Rotation(metadata.worldFromCamera[0], metadata.worldFromCamera[1],
                            metadata.worldFromCamera[2], metadata.worldFromCamera[3]),
                        Vector3(metadata.worldFromCamera[4], metadata.worldFromCamera[5],
                            metadata.worldFromCamera[6]))
                    val update = fusion.integrate(depth, frame.calibration, pose)
                    fusionUpdates += update
                    require(update.accepted) {
                        "Archived frame ${source.frameId} was not integrated: ${update.rejection}"
                    }
                }
                stage = ReconstructionTexturedSessionStage.SOURCE_MESH
                val built = fusion.buildSourceMesh()
                meshTelemetry = ReconstructionTexturedSessionMeshTelemetry(
                    complete = built.complete,
                    fusionTruncated = built.fusionTruncated,
                    meshingTruncated = built.meshingTruncated,
                    filteringTruncated = built.filteringTruncated,
                    simplificationTruncated = built.simplificationTruncated,
                    sourceVertices = built.sourceVertices,
                    sourceTriangles = built.sourceTriangles,
                    rawMeshBytes = built.mesh?.let(::rawMeshBytes) ?: 0L,
                    error = built.error)
                require(built.complete && built.error.isEmpty() && built.mesh != null) {
                    built.error.ifEmpty { "Fused source mesh did not complete" }
                }
                built
            }

            stage = ReconstructionTexturedSessionStage.TEXTURE
            val sourceMesh = requireNotNull(sourceMeshBuild.mesh) {
                "Complete raw source mesh is missing"
            }
            val textured = texturePipeline.build(sourceMesh, candidates)
            textureTelemetry = ReconstructionTexturePipelineTelemetry(
                textured.telemetry.values.copyOf())
            textureStage = textured.stage
            textureInputValid = textured.inputValid
            textureTruncated = textured.truncated
            textureError = textured.error
            atlasWidth = textured.atlasWidth
            atlasHeight = textured.atlasHeight
            atlasBytes = textured.atlasRgba8.size
            require(textured.stage == ReconstructionTexturePipelineStage.COMPLETE &&
                textured.error.isEmpty() && textured.mesh != null &&
                textured.atlasRgba8.isNotEmpty() && atlasWidth > 0 && atlasHeight > 0) {
                textured.error.ifEmpty { "Texture pipeline did not complete" }
            }
            val texturedMesh = requireNotNull(textured.mesh) {
                "Complete textured mesh is missing"
            }
            stage = ReconstructionTexturedSessionStage.PUBLICATION
            val asset = publisher.publish(request.assetId, request.generation,
                texturedMesh, textured.atlasWidth, textured.atlasHeight, textured.atlasRgba8)
            result(ReconstructionTexturedSessionStage.COMPLETE, "", asset)
        } catch (error: Throwable) {
            if (error !is Exception) throw error
            result(stage, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun rawMeshBytes(mesh: ReconstructionSourceMeshBuffers): Long {
        val positions = Math.multiplyExact(mesh.positionsMeters.remaining().toLong(), 4L)
        val normals = mesh.normals?.let {
            Math.multiplyExact(it.remaining().toLong(), 4L)
        } ?: 0L
        val indices = Math.multiplyExact(mesh.triangleIndices.remaining().toLong(), 4L)
        return Math.addExact(Math.addExact(positions, normals), indices)
    }
}
