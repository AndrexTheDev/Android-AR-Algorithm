package ai.arena.arkernel

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32

enum class ReconstructionImageEncoding(val wireValue: Int) {
    GRAY8(1), JPEG(2), PNG(3), I420(4);
    companion object {
        fun fromWire(value: Int): ReconstructionImageEncoding = entries.firstOrNull {
            it.wireValue == value
        } ?: throw IllegalArgumentException("Unknown reconstruction image encoding")
    }
}

data class ReconstructionFrameMetadata(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val encoding: ReconstructionImageEncoding,
    val exposureTimeNs: Long = 0,
    val sensitivityIso: Int = 0,
    val worldFromCamera: DoubleArray,
    val translationSigmaMeters: Double,
    val rotationSigmaDegrees: Double,
) {
    init {
        require(frameId > 0 && timestampNs > 0) { "Frame ID and timestamp must be positive" }
        require(width > 0 && height > 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            "Invalid reconstruction image dimensions"
        }
        require(rowStrideBytes >= width && rowStrideBytes <= MAX_ROW_STRIDE) { "Invalid row stride" }
        require(exposureTimeNs >= 0 && sensitivityIso >= 0) { "Invalid exposure metadata" }
        require(worldFromCamera.size == 7 && worldFromCamera.all { it.isFinite() }) {
            "Pose must contain finite quaternion and translation values"
        }
        val quaternionNorm = kotlin.math.sqrt(worldFromCamera.take(4).sumOf { it * it })
        require(quaternionNorm > 1e-9) { "Pose quaternion is invalid" }
        require(translationSigmaMeters.isFinite() && translationSigmaMeters >= 0.0 &&
            rotationSigmaDegrees.isFinite() && rotationSigmaDegrees >= 0.0) {
            "Pose uncertainty is invalid"
        }
    }

    private companion object {
        const val MAX_DIMENSION = 32_768
        const val MAX_ROW_STRIDE = 1 shl 20
    }
}

data class ArchivedReconstructionFrame(
    val metadata: ReconstructionFrameMetadata,
    val imageBytes: ByteArray,
)

/** Strict dependency-free envelope codec; separated from Android I/O for JVM testing. */
internal object ReconstructionFrameCodec {
    private val MAGIC = byteArrayOf(0x41, 0x52, 0x4b, 0x46, 0x52, 0x4d, 0x30, 0x31) // ARKFRM01
    private const val VERSION: Byte = 1
    private const val HEADER_BYTES = 8 + 1 + 1 + 2 + 8 + 8 + 4 + 4 + 4 + 8 + 4 + 7 * 8 + 8 + 8 + 4 + 4
    const val MAX_IMAGE_BYTES = 64 * 1024 * 1024

    fun encode(frame: ArchivedReconstructionFrame): ByteArray {
        validatePayload(frame.metadata, frame.imageBytes)
        val size = Math.addExact(HEADER_BYTES, frame.imageBytes.size)
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        output.put(MAGIC).put(VERSION).put(frame.metadata.encoding.wireValue.toByte()).putShort(0)
            .putLong(frame.metadata.frameId).putLong(frame.metadata.timestampNs)
            .putInt(frame.metadata.width).putInt(frame.metadata.height)
            .putInt(frame.metadata.rowStrideBytes).putLong(frame.metadata.exposureTimeNs)
            .putInt(frame.metadata.sensitivityIso)
        frame.metadata.worldFromCamera.forEach { output.putDouble(it) }
        output.putDouble(frame.metadata.translationSigmaMeters)
            .putDouble(frame.metadata.rotationSigmaDegrees)
            .putInt(frame.imageBytes.size)
        val checksumOffset = output.position()
        output.putInt(0).put(frame.imageBytes)
        val bytes = output.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(checksumOffset, crc(bytes, checksumOffset))
        return bytes
    }

    fun decode(envelope: ByteArray): ArchivedReconstructionFrame {
        require(envelope.size in (HEADER_BYTES + 1)..(HEADER_BYTES + MAX_IMAGE_BYTES)) {
            "Reconstruction frame envelope has an invalid size"
        }
        val input = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        require(magic.contentEquals(MAGIC)) { "Reconstruction frame magic is invalid" }
        require(input.get() == VERSION) { "Unsupported reconstruction frame version" }
        val encoding = ReconstructionImageEncoding.fromWire(input.get().toInt() and 0xff)
        require(input.short.toInt() == 0) { "Reserved reconstruction frame bits are non-zero" }
        val frameId = input.long
        val timestampNs = input.long
        val width = input.int
        val height = input.int
        val rowStride = input.int
        val exposureTimeNs = input.long
        val sensitivityIso = input.int
        val pose = DoubleArray(7) { input.double }
        val translationSigma = input.double
        val rotationSigma = input.double
        val payloadLength = input.int
        val checksumOffset = input.position()
        val expectedChecksum = input.int
        require(payloadLength in 1..MAX_IMAGE_BYTES && payloadLength == input.remaining()) {
            "Reconstruction frame payload length is inconsistent"
        }
        require(expectedChecksum == crc(envelope, checksumOffset)) {
            "Reconstruction frame checksum is invalid"
        }
        val metadata = ReconstructionFrameMetadata(frameId, timestampNs, width, height, rowStride,
            encoding, exposureTimeNs, sensitivityIso, pose, translationSigma, rotationSigma)
        return ArchivedReconstructionFrame(metadata, ByteArray(payloadLength).also { input.get(it) }).also {
            validatePayload(it.metadata, it.imageBytes)
        }
    }

    private fun validatePayload(metadata: ReconstructionFrameMetadata, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "Invalid image payload size" }
        if (metadata.encoding == ReconstructionImageEncoding.GRAY8) {
            val minimum = Math.addExact(Math.multiplyExact(metadata.rowStrideBytes, metadata.height - 1),
                metadata.width)
            require(bytes.size >= minimum) { "GRAY8 payload is truncated" }
        } else if (metadata.encoding == ReconstructionImageEncoding.I420) {
            require(metadata.width % 2 == 0 && metadata.height % 2 == 0) {
                "I420 dimensions must be even"
            }
            val pixels = Math.multiplyExact(metadata.width, metadata.height)
            require(bytes.size == Math.addExact(pixels, pixels / 2)) {
                "I420 payload size is inconsistent"
            }
        }
    }

    private fun crc(bytes: ByteArray, checksumOffset: Int): Int {
        val crc = CRC32()
        crc.update(bytes, 0, checksumOffset)
        crc.update(byteArrayOf(0, 0, 0, 0))
        crc.update(bytes, checksumOffset + 4, bytes.size - checksumOffset - 4)
        return crc.value.toInt()
    }
}

/**
 * Crash-safe bounded keyframe archive in app-private no-backup storage.
 * One AtomicFile per frame avoids rewriting an entire scan. CRC detects
 * truncation/corruption; it is not authentication or encryption.
 */
class ReconstructionFrameArchive(
    context: Context,
    sessionId: String,
    private val maxFrames: Int = 2_000,
    private val maxTotalBytes: Long = 1L shl 30,
) {
    private val directory: File
    private val referencePrefix: String

    init {
        require(SESSION_PATTERN.matches(sessionId)) { "Invalid reconstruction session ID" }
        require(maxFrames > 0 && maxTotalBytes > 0) { "Archive budgets must be positive" }
        directory = File(context.noBackupFilesDir, "arkernel-reconstruction/$sessionId")
        require(directory.mkdirs() || directory.isDirectory) { "Could not create reconstruction archive" }
        referencePrefix = "arkf://$sessionId/"
    }

    @Synchronized
    fun save(frame: ArchivedReconstructionFrame): String {
        val envelope = ReconstructionFrameCodec.encode(frame)
        require(envelope.size.toLong() <= maxTotalBytes) { "Frame exceeds archive byte budget" }
        val name = fileName(frame.metadata.timestampNs, frame.metadata.frameId)
        val target = File(directory, name)
        require(!target.exists()) { "Frame already exists" }
        evictFor(envelope.size.toLong())
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(envelope)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        return referencePrefix + name
    }

    @Synchronized
    fun load(reference: String): ArchivedReconstructionFrame? {
        val name = reference.removePrefix(referencePrefix)
        require(reference.startsWith(referencePrefix) && FILE_PATTERN.matches(name)) {
            "Frame reference does not belong to this archive"
        }
        val atomic = AtomicFile(File(directory, name))
        val input = try { atomic.openRead() } catch (_: FileNotFoundException) { return null }
        val length = atomic.baseFile.length()
        require(length in 1..(ReconstructionFrameCodec.MAX_IMAGE_BYTES + MAX_HEADER_BYTES).toLong()) {
            input.close(); "Archived frame has an invalid size"
        }
        val bytes = input.use { it.readBytes() }
        require(bytes.size.toLong() == length) { "Archived frame was truncated" }
        return ReconstructionFrameCodec.decode(bytes)
    }

    @Synchronized
    fun delete(reference: String): Boolean {
        val name = reference.removePrefix(referencePrefix)
        require(reference.startsWith(referencePrefix) && FILE_PATTERN.matches(name)) {
            "Frame reference does not belong to this archive"
        }
        return File(directory, name).delete()
    }

    @Synchronized
    fun clear(): Boolean = directory.deleteRecursively().also {
        if (it) require(directory.mkdirs() || directory.isDirectory)
    }

    @Synchronized
    fun references(): List<String> = frameFiles().map { referencePrefix + it.name }

    private fun evictFor(incomingBytes: Long) {
        val files = frameFiles().toMutableList()
        var bytes = files.sumOf { it.length() }
        while (files.size >= maxFrames || bytes > maxTotalBytes - incomingBytes) {
            val victim = (if (files.isEmpty()) null else files.removeAt(0))
                ?: error("Archive budget cannot be satisfied")
            val length = victim.length()
            check(victim.delete()) { "Could not evict old reconstruction frame" }
            bytes -= length
        }
    }

    private fun frameFiles(): List<File> = directory.listFiles { file ->
        file.isFile && FILE_PATTERN.matches(file.name)
    }?.sortedBy { it.name } ?: emptyList()

    private fun fileName(timestampNs: Long, frameId: Long): String =
        String.format(Locale.US, "%019d-%019d.arkf", timestampNs, frameId)

    private companion object {
        const val MAX_HEADER_BYTES = 256
        val SESSION_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val FILE_PATTERN = Regex("[0-9]{19}-[0-9]{19}\\.arkf")
    }
}
