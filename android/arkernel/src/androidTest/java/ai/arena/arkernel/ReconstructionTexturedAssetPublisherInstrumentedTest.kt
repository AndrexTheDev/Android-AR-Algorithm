package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionTexturedAssetPublisherInstrumentedTest {
    @Test
    fun encodesEmbedsAndAtomicallyPublishesTexturedMesh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = ReconstructionAssetArchive(context, "textured-publisher-test",
            maxAssets = 2, maxAssetBytes = 1024 * 1024, maxTotalBytes = 2L * 1024 * 1024)
        archive.clear()
        try {
            val mesh = triangleMesh(intArrayOf(0, 1, 2))
            val publisher = ReconstructionTexturedAssetPublisher(archive,
                ReconstructionTexturedAssetOptions(maxVertices = 3, maxTriangles = 1,
                    maxGlbBytes = 1024 * 1024,
                    png = ReconstructionTexturePngOptions(maxPixels = 4,
                        maxEncodedBytes = 64 * 1024)))
            val atlas = byteArrayOf(
                255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(),
                0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(),
                255.toByte())
            val published = publisher.publish("textured", 1, mesh, 2, 2, atlas)
            assertEquals(3, published.vertices)
            assertEquals(1, published.triangles)
            assertTrue(published.pngBytes > 8)
            assertTrue(published.glbBytes > published.pngBytes)
            val loaded = archive.load(published.reference)
            assertNotNull(loaded)
            val glb = requireNotNull(loaded)
            assertTrue(glb.take(4).map { it.toInt() and 0xff } == listOf(103, 108, 84, 70))
            val glbText = glb.toString(Charsets.ISO_8859_1)
            assertTrue(glbText.contains("image/png"))
            assertTrue(glbText.contains("unverified"))
            assertTrue(contains(glb, byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
            assertEquals(0, mesh.positionsMeters.position())
            assertEquals(0, mesh.texcoords.position())
            assertEquals(0, mesh.triangleIndices.position())
        } finally {
            archive.clear()
        }
    }

    @Test
    fun rejectsOutOfRangeMeshIndexWithoutPublishing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = ReconstructionAssetArchive(context, "textured-publisher-invalid-test",
            maxAssets = 1, maxAssetBytes = 1024 * 1024, maxTotalBytes = 1024 * 1024)
        archive.clear()
        try {
            val publisher = ReconstructionTexturedAssetPublisher(archive,
                ReconstructionTexturedAssetOptions(maxVertices = 3, maxTriangles = 1,
                    maxGlbBytes = 1024 * 1024,
                    png = ReconstructionTexturePngOptions(maxPixels = 1,
                        maxEncodedBytes = 64 * 1024)))
            var failed = false
            try {
                publisher.publish("bad", 1, triangleMesh(intArrayOf(0, 1, 3)),
                    1, 1, byteArrayOf(0, 0, 0, 255.toByte()))
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
            assertTrue(archive.references().isEmpty())
        } finally {
            archive.clear()
        }
    }

    private fun triangleMesh(indices: IntArray): ReconstructionTextureMeshBuffers {
        val positions = ByteBuffer.allocateDirect(9 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)); flip() }
        val normals = ByteBuffer.allocateDirect(9 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { repeat(3) { put(floatArrayOf(0f, 0f, 1f)) }; flip() }
        val texcoords = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)); flip() }
        val indexBuffer = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.nativeOrder())
            .asIntBuffer().apply { put(indices); flip() }
        return ReconstructionTextureMeshBuffers(positions, normals, texcoords, indexBuffer)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { offset ->
            needle.indices.all { haystack[offset + it] == needle[it] }
        }
}
