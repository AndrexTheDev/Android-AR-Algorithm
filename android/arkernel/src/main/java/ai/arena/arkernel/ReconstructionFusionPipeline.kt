package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ReconstructionFusionOptions(
    val voxelSizeMeters: Double = 0.02,
    val truncationDistanceMeters: Double = 0.06,
    val minDepthMeters: Double = 0.15,
    val maxDepthMeters: Double = 10.0,
    val minConfidence: Double = 0.20,
    val defaultUncertaintyMeters: Double = 0.03,
    val pixelStride: Int = 2,
    val maxBlocks: Long = 4096,
    val maxIntegratedFrames: Long = 2000,
    val maxDepthPixelsPerUpdate: Long = 262_144,
    val maxVoxelVisitsPerUpdate: Long = 1_048_576,
    val maxSnapshotVoxels: Long = 1_000_000,
    val maxMeshCells: Long = 1_000_000,
    val maxMeshVertices: Long = 1_000_000,
    val maxMeshTriangles: Long = 2_000_000,
    val minComponentTriangles: Long = 4,
    val minComponentAreaSquareMeters: Double = 1e-6,
    val simplificationCellSizeMeters: Double = 0.03,
    val maxSimplificationErrorMeters: Double = 0.02,
    val sharpEdgeAngleDegrees: Double = 35.0,
    val maxUvError: Double = 0.01,
    val maxUniqueEdges: Long = 3_000_000,
    val maxClusters: Long = 1_000_000,
    /** Optional measured quadric-error-guided delivery LOD; source mesh stays intact. */
    val enableQuadricErrorLod: Boolean = false,
    val maxQuadricErrorSquareMeters: Double = 1e-4,
    val maxQuadricLodDisplacementMeters: Double = 0.02,
    val quadricRegularization: Double = 1e-9,
    val maxQuadricEvaluations: Long = 10_000_000,
    val maxGlbBytesPerMesh: Long = 128L * 1024L * 1024L,
    val maxRawMeshBytes: Int = 128 * 1024 * 1024,
) {
    init {
        require(maxQuadricErrorSquareMeters > 0.0 &&
            maxQuadricErrorSquareMeters.isFinite() &&
            maxQuadricErrorSquareMeters <= 100.0) {
            "Invalid quadric error budget"
        }
        require(maxQuadricLodDisplacementMeters > 0.0 &&
            maxQuadricLodDisplacementMeters.isFinite() &&
            maxQuadricLodDisplacementMeters <= 10.0) {
            "Invalid quadric LOD displacement budget"
        }
        require(quadricRegularization > 0.0 && quadricRegularization.isFinite() &&
            quadricRegularization <= 1.0) { "Invalid quadric regularization" }
        require(maxQuadricEvaluations in 1L..100_000_000L) {
            "Invalid quadric evaluation budget"
        }
    }
}

enum class ReconstructionIntegrationRejection {
    NONE, INVALID_TIMESTAMP, NON_MONOTONIC_TIMESTAMP, FRAME_BUDGET_EXCEEDED,
    INVALID_DEPTH_INPUT,
}

data class ReconstructionFusionUpdate(
    val accepted: Boolean,
    val rejection: ReconstructionIntegrationRejection,
    val acceptedFrames: Long,
    val rejectedFrames: Long,
    val cumulativeIntegratedPixels: Long,
    val cumulativeVoxelUpdates: Long,
    val visitedPixels: Long,
    val integratedPixels: Long,
    val rejectedPixels: Long,
    val voxelVisits: Long,
    val voxelUpdates: Long,
    val allocatedBlocks: Long,
    val capacityRejections: Long,
    val fusionTruncated: Boolean,
)

data class ReconstructionSourceMeshBuildOutput(
    val mesh: ReconstructionSourceMeshBuffers?,
    val complete: Boolean,
    val fusionTruncated: Boolean,
    val meshingTruncated: Boolean,
    val filteringTruncated: Boolean,
    val simplificationTruncated: Boolean,
    val sourceVertices: Long,
    val sourceTriangles: Long,
    val error: String,
)

data class ReconstructionBuildOutput(
    /** Filtered, non-decimated metric source representation. */
    val sourceGlb: ByteArray,
    /** Conservative delivery representation; never replaces sourceGlb. */
    val simplifiedGlb: ByteArray,
    val complete: Boolean,
    val fusionTruncated: Boolean,
    val meshingTruncated: Boolean,
    val filteringTruncated: Boolean,
    val simplificationTruncated: Boolean,
    val fusedFrames: Long,
    val fusedBlocks: Long,
    val fusedVoxels: Long,
    val meshingInputVoxels: Long,
    val meshingExaminedCells: Long,
    val meshingCompleteCells: Long,
    val meshingSurfaceCells: Long,
    val meshingSkippedIncompleteCells: Long,
    val sourceComponents: Long,
    val retainedComponents: Long,
    val sourceVertices: Long,
    val sourceTriangles: Long,
    val simplifiedVertices: Long,
    val simplifiedTriangles: Long,
    val maxSimplificationDisplacementMeters: Double,
    val quadricLodEnabled: Boolean,
    val quadricLodTruncated: Boolean,
    val quadricLodInputVertices: Long,
    val quadricLodOutputVertices: Long,
    val quadricLodOutputTriangles: Long,
    val quadricLodAcceptedClusters: Long,
    val quadricLodRejectedClusters: Long,
    val quadricLodLockedVertices: Long,
    val quadricLodRemovedDegenerateTriangles: Long,
    val quadricLodInitialErrorSquareMeters: Double,
    val quadricLodFinalErrorSquareMeters: Double,
    val quadricLodMaxDisplacementMeters: Double,
)

/**
 * Bounded Android owner for metric depth fusion and mesh extraction.
 *
 * Calls are synchronous and serialized. Buffers must be direct, native-order,
 * alive and immutable until integrate returns. Unknown depth remains unknown;
 * this class performs neither model inference nor hole filling. An optional
 * measured quadric-error LOD may refine only the delivery mesh; the filtered
 * source mesh used for texturing is never replaced.
 */
class ReconstructionFusionPipeline(
    options: ReconstructionFusionOptions = ReconstructionFusionOptions(),
) : AutoCloseable {
    private val maxRawMeshBytes = options.maxRawMeshBytes.also {
        require(it in 1..512 * 1024 * 1024) { "Invalid raw mesh byte budget" }
    }
    private var nativeHandle = nativeCreate(
        longArrayOf(options.pixelStride.toLong(), options.maxBlocks,
            options.maxIntegratedFrames, options.maxDepthPixelsPerUpdate,
            options.maxVoxelVisitsPerUpdate, options.maxSnapshotVoxels,
            options.maxMeshCells, options.maxMeshVertices, options.maxMeshTriangles,
            options.minComponentTriangles, options.maxUniqueEdges, options.maxClusters,
            options.maxGlbBytesPerMesh,
            if (options.enableQuadricErrorLod) 1L else 0L,
            options.maxQuadricEvaluations),
        doubleArrayOf(options.voxelSizeMeters, options.truncationDistanceMeters,
            options.minDepthMeters, options.maxDepthMeters, options.minConfidence,
            options.defaultUncertaintyMeters, options.minComponentAreaSquareMeters,
            options.simplificationCellSizeMeters, options.maxSimplificationErrorMeters,
            options.sharpEdgeAngleDegrees, options.maxUvError,
            options.maxQuadricErrorSquareMeters,
            options.maxQuadricLodDisplacementMeters, options.quadricRegularization),
    ).also { require(it != 0L) { "Invalid reconstruction fusion options" } }

    @Synchronized
    fun integrate(depth: MetricDepthBuffer, calibration: CameraCalibration,
                  worldFromCamera: WorldPose): ReconstructionFusionUpdate {
        check(nativeHandle != 0L) { "Reconstruction fusion pipeline is closed" }
        require(depth.timestampNs == worldFromCamera.timestampNs)
        require(depth.width == calibration.width && depth.height == calibration.height)
        require(depth.depthMeters.isDirect && depth.depthMeters.order() == ByteOrder.nativeOrder())
        depth.confidence?.let {
            require(it.isDirect && it.order() == ByteOrder.nativeOrder())
        }
        depth.uncertaintyMeters?.let {
            require(it.isDirect && it.order() == ByteOrder.nativeOrder())
        }
        val metadata = LongArray(14)
        val rotation = worldFromCamera.rotation
        val position = worldFromCamera.positionMeters
        val status = nativeIntegrate(nativeHandle, depth.timestampNs,
            depth.depthMeters, depth.depthMeters.position(), depth.depthMeters.remaining(),
            depth.confidence, depth.confidence?.position() ?: 0,
            depth.confidence?.remaining() ?: 0,
            depth.uncertaintyMeters, depth.uncertaintyMeters?.position() ?: 0,
            depth.uncertaintyMeters?.remaining() ?: 0,
            depth.width, depth.height, depth.rowStrideElements,
            depth.confidenceRowStrideElements, depth.uncertaintyRowStrideElements,
            calibration.fx, calibration.fy, calibration.cx, calibration.cy,
            calibration.k1, calibration.k2, calibration.p1, calibration.p2, calibration.k3,
            rotation.w, rotation.x, rotation.y, rotation.z,
            position.x, position.y, position.z, metadata)
        check(status == RESULT_OK) { "Native reconstruction integration failed: $status" }
        return ReconstructionFusionUpdate(metadata[0] != 0L,
            ReconstructionIntegrationRejection.entries[metadata[1].toInt()],
            metadata[2], metadata[3], metadata[4], metadata[5], metadata[6], metadata[7],
            metadata[8], metadata[9], metadata[10], metadata[11], metadata[12],
            metadata[13] != 0L)
    }

    @Synchronized
    fun build(): ReconstructionBuildOutput {
        check(nativeHandle != 0L) { "Reconstruction fusion pipeline is closed" }
        val metadata = LongArray(28)
        val values = DoubleArray(4)
        val meshes = nativeBuild(nativeHandle, metadata, values)
            ?: error("Native reconstruction build failed")
        check(meshes.size == 2)
        return ReconstructionBuildOutput(meshes[0], meshes[1], metadata[0] != 0L,
            metadata[1] != 0L, metadata[2] != 0L, metadata[3] != 0L,
            metadata[4] != 0L, metadata[5], metadata[6], metadata[7], metadata[8],
            metadata[9], metadata[10], metadata[11], metadata[12], metadata[13],
            metadata[14], metadata[15], metadata[16], metadata[17], metadata[18],
            values[0], metadata[19] != 0L, metadata[20] != 0L,
            metadata[21], metadata[22], metadata[23], metadata[24], metadata[25],
            metadata[26], metadata[27], values[1], values[2], values[3])
    }

    /**
     * Builds the filtered metric source mesh without a GLB encode/decode round
     * trip. Any truncated or failed build returns telemetry/error but no mesh.
     */
    @Synchronized
    fun buildSourceMesh(): ReconstructionSourceMeshBuildOutput {
        check(nativeHandle != 0L) { "Reconstruction fusion pipeline is closed" }
        val metadata = LongArray(7)
        val encoded = requireNotNull(nativeBuildSourceMesh(nativeHandle, metadata,
            maxRawMeshBytes)) { "Native raw reconstruction mesh build failed" }
        require(encoded.size <= maxRawMeshBytes)
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() >= RAW_HEADER_BYTES && input.int == RAW_MAGIC &&
            input.int == RAW_VERSION)
        val vertices = input.int
        val normals = input.int
        val indices = input.int
        val errorBytes = input.int
        require(vertices >= 0 && normals >= 0 && indices >= 0 && errorBytes >= 0 &&
            (normals == 0 || normals == vertices) && indices % 3 == 0)
        val payloadBytes = Math.addExact(errorBytes.toLong(), Math.addExact(
            Math.multiplyExact(vertices.toLong(), 12L), Math.addExact(
                Math.multiplyExact(normals.toLong(), 12L),
                Math.multiplyExact(indices.toLong(), 4L))))
        require(payloadBytes == input.remaining().toLong())
        val errorData = ByteArray(errorBytes); input.get(errorData)
        val complete = metadata[0] != 0L
        require(complete == (vertices > 0 && indices > 0))
        val mesh = if (complete) {
            val positions = ByteBuffer.allocateDirect(Math.multiplyExact(vertices, 12))
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(vertices * 3) { positions.put(input.float) }; positions.flip()
            val normalBuffer = if (normals > 0) ByteBuffer.allocateDirect(
                Math.multiplyExact(normals, 12)).order(ByteOrder.nativeOrder())
                .asFloatBuffer().also { buffer ->
                    repeat(normals * 3) { buffer.put(input.float) }; buffer.flip()
                } else null
            val indexBuffer = ByteBuffer.allocateDirect(Math.multiplyExact(indices, 4))
                .order(ByteOrder.nativeOrder()).asIntBuffer()
            repeat(indices) { indexBuffer.put(input.int) }; indexBuffer.flip()
            ReconstructionSourceMeshBuffers(positions, normalBuffer, indexBuffer)
        } else null
        require(!input.hasRemaining())
        return ReconstructionSourceMeshBuildOutput(mesh, complete, metadata[1] != 0L,
            metadata[2] != 0L, metadata[3] != 0L, metadata[4] != 0L,
            metadata[5], metadata[6], errorData.toString(Charsets.UTF_8))
    }

    @Synchronized fun reset() {
        check(nativeHandle != 0L) { "Reconstruction fusion pipeline is closed" }
        nativeReset(nativeHandle)
    }

    @Synchronized override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(integerOptions: LongArray,
                                      scalarOptions: DoubleArray): Long
    private external fun nativeIntegrate(handle: Long, timestampNs: Long,
        depth: java.nio.FloatBuffer, depthOffset: Int, depthRemaining: Int,
        confidence: java.nio.FloatBuffer?, confidenceOffset: Int, confidenceRemaining: Int,
        uncertainty: java.nio.FloatBuffer?, uncertaintyOffset: Int, uncertaintyRemaining: Int,
        width: Int, height: Int, depthStride: Int, confidenceStride: Int,
        uncertaintyStride: Int, fx: Double, fy: Double, cx: Double, cy: Double, k1: Double, k2: Double,
        p1: Double, p2: Double, k3: Double, rw: Double, rx: Double, ry: Double,
        rz: Double, tx: Double, ty: Double, tz: Double, metadata: LongArray): Int
    private external fun nativeBuild(handle: Long, metadata: LongArray,
                                     values: DoubleArray): Array<ByteArray>?
    private external fun nativeBuildSourceMesh(handle: Long, metadata: LongArray,
        maxResultBytes: Int): ByteArray?
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    private companion object {
        const val RESULT_OK = 0
        const val RAW_MAGIC = 0x4d4b5241
        const val RAW_VERSION = 1
        const val RAW_HEADER_BYTES = 6 * 4
        init { System.loadLibrary("arkernel_jni") }
    }
}
