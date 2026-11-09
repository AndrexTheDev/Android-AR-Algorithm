package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

enum class ReconstructionTexturePipelineStage {
    NONE, VIEW_SELECTION, LABEL_OPTIMIZATION, ATLAS_LAYOUT, PHOTOMETRY, BAKING,
    SEAM_BLENDING, COMPLETE
}

data class ReconstructionTexturePipelineTelemetry(
    val values: LongArray,
) {
    init { require(values.size == FIELD_COUNT) }
    val assignedTriangles get() = values[2]
    val unassignedTriangles get() = values[3]
    val rasterizedPixels get() = values[12]
    val blendedSeams get() = values[15]
    companion object { const val FIELD_COUNT = 17 }
}

data class ReconstructionSourceMeshBuffers(
    val positionsMeters: FloatBuffer,
    val normals: FloatBuffer? = null,
    val triangleIndices: IntBuffer,
)

data class ReconstructionTextureVisibilityDepth(
    /** Optical-axis Z depth aligned to the candidate camera image. */
    val image: MetricDepthBuffer,
)

data class ReconstructionTextureCandidate(
    val source: LoadedReconstructionTextureSource,
    val calibration: CameraCalibration,
    /** Quaternion w,x,y,z followed by translation x,y,z in metres. */
    val worldFromCamera: DoubleArray,
    val qualityScore: Double,
    val visibilityDepth: ReconstructionTextureVisibilityDepth? = null,
) {
    init {
        require(calibration.width == source.width && calibration.height == source.height)
        require(worldFromCamera.size == 7 && worldFromCamera.all { it.isFinite() })
        require(qualityScore.isFinite())
        visibilityDepth?.image?.let { depth ->
            require(depth.width == source.width && depth.height == source.height)
            require(depth.timestampNs == source.timestampNs) {
                "Visibility depth timestamp must match the archived texture frame"
            }
        }
    }
}

data class ReconstructionTexturePipelineOptions(
    val maxVertices: Int = 1_000_000,
    val maxTriangles: Int = 2_000_000,
    val maxCandidates: Int = 256,
    val maxResultBytes: Int = 256 * 1024 * 1024,
    val atlasWidth: Int = 1024,
    val atlasHeight: Int = 1024,
    val requireVisibilityDepth: Boolean = false,
    val enablePhotometry: Boolean = true,
    val enableSeamBlending: Boolean = true,
) {
    init {
        require(maxVertices in 1..5_000_000)
        require(maxTriangles in 1..10_000_000)
        require(maxCandidates in 1..4_096)
        require(maxResultBytes in 1..512 * 1024 * 1024)
        require(atlasWidth in 16..4096 && atlasHeight in 16..4096)
    }
}

data class ReconstructionTexturePipelineResult(
    val stage: ReconstructionTexturePipelineStage,
    val inputValid: Boolean,
    val truncated: Boolean,
    val error: String,
    val telemetry: ReconstructionTexturePipelineTelemetry,
    val mesh: ReconstructionTextureMeshBuffers?,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val atlasRgba8: ByteArray,
)

/**
 * Bounded JNI adapter for the complete portable texture graph. Optional
 * optical-axis depth, confidence and uncertainty remain borrowed direct buffers
 * only for the synchronized native call. When requireVisibilityDepth is true,
 * every candidate must provide timestamp- and dimension-matched depth. Only
 * COMPLETE results expose mesh or atlas bytes.
 */
class ReconstructionTexturePipeline(
    private val options: ReconstructionTexturePipelineOptions =
        ReconstructionTexturePipelineOptions(),
) {
    @Synchronized
    fun build(
        sourceMesh: ReconstructionSourceMeshBuffers,
        candidates: List<ReconstructionTextureCandidate>,
    ): ReconstructionTexturePipelineResult {
        require(candidates.isNotEmpty() && candidates.size <= options.maxCandidates)
        require(candidates.zipWithNext().all { (a, b) -> a.source.frameId < b.source.frameId })
        require(candidates.map { it.source.reference }.toSet().size == candidates.size)
        requireDirectNative(sourceMesh.positionsMeters, "positions")
        sourceMesh.normals?.let { requireDirectNative(it, "normals") }
        require(sourceMesh.triangleIndices.isDirect &&
            sourceMesh.triangleIndices.order() == ByteOrder.nativeOrder())
        val positionValues = sourceMesh.positionsMeters.remaining()
        require(positionValues % 3 == 0 && positionValues / 3 in 1..options.maxVertices)
        require(sourceMesh.normals == null || sourceMesh.normals.remaining() == positionValues)
        require(sourceMesh.triangleIndices.remaining() % 3 == 0 &&
            sourceMesh.triangleIndices.remaining() / 3 in 1..options.maxTriangles)

        val frameIds = LongArray(candidates.size)
        val references = arrayOfNulls<String>(candidates.size)
        val cameraData = DoubleArray(candidates.size * CAMERA_VALUES)
        val formats = IntArray(candidates.size)
        val widths = IntArray(candidates.size)
        val heights = IntArray(candidates.size)
        val strides = IntArray(candidates.size)
        val images = arrayOfNulls<ByteArray>(candidates.size)
        val depthBuffers = arrayOfNulls<FloatBuffer>(candidates.size)
        val confidenceBuffers = arrayOfNulls<FloatBuffer>(candidates.size)
        val uncertaintyBuffers = arrayOfNulls<FloatBuffer>(candidates.size)
        val depthData = IntArray(candidates.size * DEPTH_VALUES)
        if (options.requireVisibilityDepth) require(candidates.all { it.visibilityDepth != null }) {
            "Every texture candidate requires aligned visibility depth"
        }
        candidates.forEachIndexed { index, candidate ->
            val source = candidate.source
            frameIds[index] = source.frameId
            references[index] = source.reference
            widths[index] = source.width
            heights[index] = source.height
            strides[index] = source.rowStrideBytes
            formats[index] = when (source.format) {
                CanonicalTexturePixelFormat.GRAY8 -> 1
                CanonicalTexturePixelFormat.RGBA8 -> 2
                CanonicalTexturePixelFormat.I420 -> 3
            }
            images[index] = source.bytes
            val c = candidate.calibration
            val values = doubleArrayOf(c.fx, c.fy, c.cx, c.cy, c.k1, c.k2, c.p1, c.p2,
                c.k3, *candidate.worldFromCamera, candidate.qualityScore)
            values.copyInto(cameraData, index * CAMERA_VALUES)
            candidate.visibilityDepth?.image?.let { depth ->
                requireDirectNative(depth.depthMeters, "visibility depth")
                depth.confidence?.let { requireDirectNative(it, "visibility confidence") }
                depth.uncertaintyMeters?.let { requireDirectNative(it, "visibility uncertainty") }
                require(depth.rowStrideElements >= depth.width)
                require(depth.confidence == null || depth.confidenceRowStrideElements >= depth.width)
                require(depth.uncertaintyMeters == null ||
                    depth.uncertaintyRowStrideElements >= depth.width)
                fun requiredElements(stride: Int) = Math.addExact(
                    Math.multiplyExact((depth.height - 1).toLong(), stride.toLong()),
                    depth.width.toLong())
                require(depth.depthMeters.remaining().toLong() >=
                    requiredElements(depth.rowStrideElements))
                require(depth.confidence == null || depth.confidence.remaining().toLong() >=
                    requiredElements(depth.confidenceRowStrideElements))
                require(depth.uncertaintyMeters == null ||
                    depth.uncertaintyMeters.remaining().toLong() >=
                    requiredElements(depth.uncertaintyRowStrideElements))
                depthBuffers[index] = depth.depthMeters
                confidenceBuffers[index] = depth.confidence
                uncertaintyBuffers[index] = depth.uncertaintyMeters
                val base = index * DEPTH_VALUES
                depthData[base] = depth.depthMeters.position()
                depthData[base + 1] = depth.depthMeters.remaining()
                depthData[base + 2] = depth.rowStrideElements
                depthData[base + 3] = depth.confidence?.position() ?: 0
                depthData[base + 4] = depth.confidence?.remaining() ?: 0
                depthData[base + 5] = depth.confidenceRowStrideElements
                depthData[base + 6] = depth.uncertaintyMeters?.position() ?: 0
                depthData[base + 7] = depth.uncertaintyMeters?.remaining() ?: 0
                depthData[base + 8] = depth.uncertaintyRowStrideElements
            }
        }
        val encoded = requireNotNull(nativeBuild(
            sourceMesh.positionsMeters, sourceMesh.positionsMeters.position(), positionValues,
            sourceMesh.normals, sourceMesh.normals?.position() ?: 0,
            sourceMesh.normals?.remaining() ?: 0,
            sourceMesh.triangleIndices, sourceMesh.triangleIndices.position(),
            sourceMesh.triangleIndices.remaining(), frameIds, references.requireNoNulls(),
            cameraData, formats, widths, heights, strides, images.requireNoNulls(),
            depthBuffers, confidenceBuffers, uncertaintyBuffers, depthData,
            options.maxVertices, options.maxTriangles, options.maxResultBytes,
            options.atlasWidth, options.atlasHeight, options.requireVisibilityDepth,
            options.enablePhotometry, options.enableSeamBlending)) {
            "Native texture pipeline rejected the bounded request"
        }
        return decode(encoded)
    }

    private fun decode(encoded: ByteArray): ReconstructionTexturePipelineResult {
        require(encoded.size <= options.maxResultBytes)
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() >= HEADER_BYTES && input.int == MAGIC && input.int == VERSION)
        val stage = ReconstructionTexturePipelineStage.entries.getOrNull(input.int)
            ?: throw IllegalArgumentException("Unknown native texture stage")
        val flags = input.int
        val width = input.int
        val height = input.int
        val vertices = input.int
        val normals = input.int
        val indices = input.int
        val errorBytes = input.int
        val rgbaBytes = input.int
        val telemetryValues = LongArray(ReconstructionTexturePipelineTelemetry.FIELD_COUNT) {
            input.long
        }
        require(width >= 0 && height >= 0 && vertices >= 0 && normals >= 0 && indices >= 0 &&
            errorBytes >= 0 && rgbaBytes >= 0)
        val payload = Math.addExact(errorBytes.toLong(), Math.addExact(rgbaBytes.toLong(),
            Math.addExact(vertices.toLong() * 20L + normals.toLong() * 12L,
                indices.toLong() * 4L)))
        require(payload == input.remaining().toLong())
        val errorData = ByteArray(errorBytes); input.get(errorData)
        val complete = stage == ReconstructionTexturePipelineStage.COMPLETE
        require(complete == (vertices > 0 && indices > 0 && rgbaBytes > 0))
        val mesh = if (complete) {
            val positionBuffer = ByteBuffer.allocateDirect(vertices * 12)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(vertices * 3) { positionBuffer.put(input.float) }; positionBuffer.flip()
            val normalBuffer = if (normals > 0) ByteBuffer.allocateDirect(normals * 12)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().also { buffer ->
                    repeat(normals * 3) { buffer.put(input.float) }; buffer.flip()
                } else null
            val texcoordBuffer = ByteBuffer.allocateDirect(vertices * 8)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(vertices * 2) { texcoordBuffer.put(input.float) }; texcoordBuffer.flip()
            val indexBuffer = ByteBuffer.allocateDirect(indices * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer()
            repeat(indices) { indexBuffer.put(input.int) }; indexBuffer.flip()
            ReconstructionTextureMeshBuffers(positionBuffer, normalBuffer, texcoordBuffer, indexBuffer)
        } else null
        val rgba = ByteArray(rgbaBytes); input.get(rgba)
        require(!input.hasRemaining())
        return ReconstructionTexturePipelineResult(stage, flags and 1 != 0, flags and 2 != 0,
            errorData.toString(Charsets.UTF_8),
            ReconstructionTexturePipelineTelemetry(telemetryValues), mesh, width, height, rgba)
    }

    private fun requireDirectNative(buffer: FloatBuffer, name: String) {
        require(buffer.isDirect && buffer.order() == ByteOrder.nativeOrder()) {
            "$name must be a direct native-order buffer"
        }
    }

    private external fun nativeBuild(
        positions: FloatBuffer, positionsOffset: Int, positionsRemaining: Int,
        normals: FloatBuffer?, normalsOffset: Int, normalsRemaining: Int,
        indices: IntBuffer, indicesOffset: Int, indicesRemaining: Int,
        frameIds: LongArray, references: Array<String>, cameraData: DoubleArray,
        formats: IntArray, widths: IntArray, heights: IntArray, strides: IntArray,
        images: Array<ByteArray>, depthBuffers: Array<FloatBuffer?>,
        confidenceBuffers: Array<FloatBuffer?>, uncertaintyBuffers: Array<FloatBuffer?>,
        depthData: IntArray, maxVertices: Int, maxTriangles: Int, maxResultBytes: Int,
        atlasWidth: Int, atlasHeight: Int, requireVisibilityDepth: Boolean,
        enablePhotometry: Boolean, enableSeamBlending: Boolean,
    ): ByteArray?

    private companion object {
        const val MAGIC = 0x54584B41
        const val VERSION = 1
        const val CAMERA_VALUES = 17
        const val DEPTH_VALUES = 9
        const val HEADER_BYTES = 11 * 4 + ReconstructionTexturePipelineTelemetry.FIELD_COUNT * 8
        init { System.loadLibrary("arkernel_jni") }
    }
}
