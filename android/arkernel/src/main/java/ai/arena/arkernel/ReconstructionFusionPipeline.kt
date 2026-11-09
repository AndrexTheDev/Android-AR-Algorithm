package ai.arena.arkernel

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
    val maxGlbBytesPerMesh: Long = 128L * 1024L * 1024L,
)

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
)

/**
 * Bounded Android owner for metric depth fusion and mesh extraction.
 *
 * Calls are synchronous and serialized. Buffers must be direct, native-order,
 * alive and immutable until integrate returns. Unknown depth remains unknown;
 * this class performs neither model inference nor hole filling.
 */
class ReconstructionFusionPipeline(
    options: ReconstructionFusionOptions = ReconstructionFusionOptions(),
) : AutoCloseable {
    private var nativeHandle = nativeCreate(
        longArrayOf(options.pixelStride.toLong(), options.maxBlocks,
            options.maxIntegratedFrames, options.maxDepthPixelsPerUpdate,
            options.maxVoxelVisitsPerUpdate, options.maxSnapshotVoxels,
            options.maxMeshCells, options.maxMeshVertices, options.maxMeshTriangles,
            options.minComponentTriangles, options.maxUniqueEdges, options.maxClusters,
            options.maxGlbBytesPerMesh),
        doubleArrayOf(options.voxelSizeMeters, options.truncationDistanceMeters,
            options.minDepthMeters, options.maxDepthMeters, options.minConfidence,
            options.defaultUncertaintyMeters, options.minComponentAreaSquareMeters,
            options.simplificationCellSizeMeters, options.maxSimplificationErrorMeters,
            options.sharpEdgeAngleDegrees, options.maxUvError),
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
        val metadata = LongArray(19)
        val values = DoubleArray(1)
        val meshes = nativeBuild(nativeHandle, metadata, values)
            ?: error("Native reconstruction build failed")
        check(meshes.size == 2)
        return ReconstructionBuildOutput(meshes[0], meshes[1], metadata[0] != 0L,
            metadata[1] != 0L, metadata[2] != 0L, metadata[3] != 0L,
            metadata[4] != 0L, metadata[5], metadata[6], metadata[7], metadata[8],
            metadata[9], metadata[10], metadata[11], metadata[12], metadata[13],
            metadata[14], metadata[15], metadata[16], metadata[17], metadata[18], values[0])
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
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    private companion object {
        const val RESULT_OK = 0
        init { System.loadLibrary("arkernel_jni") }
    }
}
