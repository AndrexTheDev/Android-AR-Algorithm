package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconstructionAssetArchiveInstrumentedTest {
    @Test
    fun atomicallyPublishesValidGlbAndEvictsOldestGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = ReconstructionAssetArchive(context, "asset-publication-test",
            maxAssets = 2, maxAssetBytes = 1024, maxTotalBytes = 2048)
        archive.clear()
        try {
            val firstBytes = minimalGlb(1)
            val first = archive.publish("source", 1, firstBytes)
            assertArrayEquals(firstBytes, archive.load(first))
            val second = archive.publish("delivery", 2, minimalGlb(2))
            val third = archive.publish("textured", 3, minimalGlb(3))
            assertNull(archive.load(first))
            assertEquals(listOf(second, third), archive.references())
            assertTrue(archive.delete(second))
            assertFalse(archive.delete(second))
        } finally {
            archive.clear()
        }
    }

    @Test
    fun rejectsMalformedGlbDuplicateGenerationAndForeignReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = ReconstructionAssetArchive(context, "asset-validation-test",
            maxAssets = 2, maxAssetBytes = 1024, maxTotalBytes = 2048)
        archive.clear()
        try {
            val valid = minimalGlb(7)
            archive.publish("mesh", 1, valid)
            assertFails { archive.publish("mesh", 1, valid) }
            assertFails { archive.publish("bad", 2, valid.copyOf().also { it[8] = 1 }) }
            assertFails { archive.load("arkglb://another-session/0000000000000000001-mesh.glb") }
        } finally {
            archive.clear()
        }
    }

    private fun minimalGlb(marker: Int): ByteArray {
        val bytes = ArrayList<Byte>()
        fun u32(value: Long) {
            repeat(4) { shift -> bytes += ((value shr (shift * 8)) and 0xff).toByte() }
        }
        u32(0x46546c67)
        u32(2)
        u32(36)
        u32(4)
        u32(0x4e4f534a)
        bytes += '{'.code.toByte(); bytes += '}'.code.toByte()
        bytes += ' '.code.toByte(); bytes += ' '.code.toByte()
        u32(4)
        u32(0x004e4942)
        bytes += marker.toByte(); bytes += 0.toByte(); bytes += 0.toByte(); bytes += 0.toByte()
        return bytes.toByteArray()
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
