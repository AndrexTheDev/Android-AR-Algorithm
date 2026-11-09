package ai.arena.arkernel

import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

data class ReconstructionTextureMeshBuffers(
    val positionsMeters: FloatBuffer,
    val normals: FloatBuffer? = null,
    val texcoords: FloatBuffer,
    val triangleIndices: IntBuffer,
)

data class ReconstructionTexturedAssetOptions(
    val maxVertices: Int = 1_000_000,
    val maxTriangles: Int = 2_000_000,
    val maxGlbBytes: Int = 256 * 1024 * 1024,
    val png: ReconstructionTexturePngOptions = ReconstructionTexturePngOptions(),
) {
    init {
        require(maxVertices in 1..5_000_000)
        require(maxTriangles in 1..10_000_000)
        require(maxGlbBytes in 1..512 * 1024 * 1024)
    }
}

data class PublishedTexturedReconstructionAsset(
    val reference: String,
    val vertices: Int,
    val triangles: Int,
    val pngBytes: Int,
    val glbBytes: Int,
)

/**
 * Final Android owner for atlas encoding, canonical single-file GLB export and
 * atomic publication. The supplied mesh must already carry the core-generated
 * seam-aware UVs; this class does not invent geometry, UVs or quality evidence.
 * Its GLB remains explicitly unverified; assessed export needs held-out evidence.
 */
class ReconstructionTexturedAssetPublisher(
    private val archive: ReconstructionAssetArchive,
    private val options: ReconstructionTexturedAssetOptions =
        ReconstructionTexturedAssetOptions(),
) {
    @Synchronized
    fun publish(
        assetId: String,
        generation: Long,
        mesh: ReconstructionTextureMeshBuffers,
        atlasWidth: Int,
        atlasHeight: Int,
        atlasRgba8: ByteArray,
    ): PublishedTexturedReconstructionAsset {
        requireDirectNative(mesh.positionsMeters, "positions")
        mesh.normals?.let { requireDirectNative(it, "normals") }
        requireDirectNative(mesh.texcoords, "texcoords")
        require(mesh.triangleIndices.isDirect &&
            mesh.triangleIndices.order() == ByteOrder.nativeOrder()) {
            "triangle indices must be a direct native-order buffer"
        }
        val positionValues = mesh.positionsMeters.remaining()
        require(positionValues % 3 == 0)
        val vertices = positionValues / 3
        require(vertices in 1..options.maxVertices)
        require(mesh.texcoords.remaining() == vertices * 2)
        require(mesh.normals == null || mesh.normals.remaining() == vertices * 3)
        require(mesh.triangleIndices.remaining() % 3 == 0)
        val triangles = mesh.triangleIndices.remaining() / 3
        require(triangles in 1..options.maxTriangles)

        val png = ReconstructionTexturePngEncoder.encodeRgba8(
            atlasWidth, atlasHeight, atlasRgba8, options.png)
        val glb = requireNotNull(nativeExportEmbeddedGlb(
            mesh.positionsMeters, mesh.positionsMeters.position(), positionValues,
            mesh.normals, mesh.normals?.position() ?: 0, mesh.normals?.remaining() ?: 0,
            mesh.texcoords, mesh.texcoords.position(), mesh.texcoords.remaining(),
            mesh.triangleIndices, mesh.triangleIndices.position(),
            mesh.triangleIndices.remaining(), png.bytes, options.maxVertices,
            options.maxTriangles, options.maxGlbBytes)) {
            "Native textured GLB export rejected mesh or output budget"
        }
        check(glb.size <= options.maxGlbBytes)
        val reference = archive.publish(assetId, generation, glb)
        return PublishedTexturedReconstructionAsset(reference, vertices, triangles,
            png.bytes.size, glb.size)
    }

    private fun requireDirectNative(buffer: FloatBuffer, name: String) {
        require(buffer.isDirect && buffer.order() == ByteOrder.nativeOrder()) {
            "$name must be a direct native-order buffer"
        }
    }

    private external fun nativeExportEmbeddedGlb(
        positions: FloatBuffer,
        positionsOffset: Int,
        positionsRemaining: Int,
        normals: FloatBuffer?,
        normalsOffset: Int,
        normalsRemaining: Int,
        texcoords: FloatBuffer,
        texcoordsOffset: Int,
        texcoordsRemaining: Int,
        indices: IntBuffer,
        indicesOffset: Int,
        indicesRemaining: Int,
        png: ByteArray,
        maxVertices: Int,
        maxTriangles: Int,
        maxGlbBytes: Int,
    ): ByteArray?

    private companion object {
        init { System.loadLibrary("arkernel_jni") }
    }
}
