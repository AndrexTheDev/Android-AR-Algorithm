package ai.arena.arkernel

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Crash-safe bounded publication store for final canonical GLB assets.
 * Files remain directly consumable GLB; AtomicFile prevents partial replacement,
 * while strict container framing detects truncation. This is not authentication.
 */
class ReconstructionAssetArchive(
    context: Context,
    sessionId: String,
    private val maxAssets: Int = 32,
    private val maxAssetBytes: Long = 256L * 1024L * 1024L,
    private val maxTotalBytes: Long = 1L shl 30,
) {
    private val directory: File
    private val referencePrefix: String

    init {
        require(SESSION_PATTERN.matches(sessionId)) { "Invalid reconstruction session ID" }
        require(maxAssets in 1..1_024)
        require(maxAssetBytes > 0 && maxTotalBytes >= maxAssetBytes)
        directory = File(context.noBackupFilesDir, "arkernel-reconstruction-assets/$sessionId")
        require(directory.mkdirs() || directory.isDirectory) {
            "Could not create reconstruction asset archive"
        }
        referencePrefix = "arkglb://$sessionId/"
    }

    @Synchronized
    fun publish(assetId: String, generation: Long, glb: ByteArray): String {
        require(ASSET_ID_PATTERN.matches(assetId)) { "Invalid reconstruction asset ID" }
        require(generation >= 0) { "Invalid reconstruction asset generation" }
        require(glb.size.toLong() <= maxAssetBytes) { "GLB exceeds per-asset byte budget" }
        requireCanonicalGlb(glb)
        val name = fileName(generation, assetId)
        val target = File(directory, name)
        require(!target.exists()) { "Reconstruction asset generation already exists" }
        evictFor(glb.size.toLong())
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(glb)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        return referencePrefix + name
    }

    @Synchronized
    fun load(reference: String): ByteArray? {
        val name = validatedName(reference)
        val atomic = AtomicFile(File(directory, name))
        val input = try {
            atomic.openRead()
        } catch (_: FileNotFoundException) {
            return null
        }
        val length = atomic.baseFile.length()
        require(length in 1..maxAssetBytes) {
            input.close()
            "Archived GLB has an invalid size"
        }
        val bytes = input.use { it.readBytes() }
        require(bytes.size.toLong() == length) { "Archived GLB was truncated" }
        requireCanonicalGlb(bytes)
        return bytes
    }

    @Synchronized
    fun delete(reference: String): Boolean = File(directory, validatedName(reference)).delete()

    @Synchronized
    fun references(): List<String> = assetFiles().map { referencePrefix + it.name }

    @Synchronized
    fun clear(): Boolean = directory.deleteRecursively().also {
        if (it) require(directory.mkdirs() || directory.isDirectory)
    }

    private fun evictFor(incomingBytes: Long) {
        val files = assetFiles().toMutableList()
        var bytes = files.sumOf { it.length() }
        while (files.size >= maxAssets || bytes > maxTotalBytes - incomingBytes) {
            val victim = if (files.isEmpty()) null else files.removeAt(0)
            checkNotNull(victim) { "Asset archive budget cannot be satisfied" }
            val length = victim.length()
            check(victim.delete()) { "Could not evict old reconstruction asset" }
            bytes -= length
        }
    }

    private fun validatedName(reference: String): String {
        val name = reference.removePrefix(referencePrefix)
        require(reference.startsWith(referencePrefix) && FILE_PATTERN.matches(name)) {
            "Asset reference does not belong to this archive"
        }
        return name
    }

    private fun assetFiles(): List<File> = directory.listFiles { file ->
        file.isFile && FILE_PATTERN.matches(file.name)
    }?.sortedBy { it.name } ?: emptyList()

    private fun fileName(generation: Long, assetId: String): String =
        String.format(Locale.US, "%019d-%s.glb", generation, assetId)

    private fun requireCanonicalGlb(bytes: ByteArray) {
        require(bytes.size >= 36) { "GLB is too small" }
        require(readU32(bytes, 0) == GLB_MAGIC && readU32(bytes, 4) == GLB_VERSION) {
            "Invalid GLB header"
        }
        require(readU32(bytes, 8).toLong() == bytes.size.toLong()) {
            "GLB declared length does not match payload"
        }
        val jsonLength = readU32(bytes, 12).toLong()
        require(jsonLength > 0 && jsonLength % 4L == 0L &&
            readU32(bytes, 16) == JSON_CHUNK) { "Invalid GLB JSON chunk" }
        val binaryHeader = 20L + jsonLength
        require(binaryHeader + 8L <= bytes.size.toLong()) { "GLB binary chunk is missing" }
        val binaryOffset = binaryHeader.toInt()
        val binaryLength = readU32(bytes, binaryOffset).toLong()
        require(binaryLength % 4L == 0L && readU32(bytes, binaryOffset + 4) == BINARY_CHUNK &&
            binaryHeader + 8L + binaryLength == bytes.size.toLong()) {
            "Invalid GLB binary chunk"
        }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset <= bytes.size - 4) { "Truncated GLB integer" }
        return (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }

    private companion object {
        const val GLB_MAGIC = 0x46546c67L
        const val GLB_VERSION = 2L
        const val JSON_CHUNK = 0x4e4f534aL
        const val BINARY_CHUNK = 0x004e4942L
        val SESSION_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val ASSET_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val FILE_PATTERN = Regex("[0-9]{19}-[A-Za-z0-9_-]{1,64}\\.glb")
    }
}
