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

data class OptimizedReconstructionPose(
    val frameId: Long,
    val timestampNs: Long,
    val worldFromCamera: DoubleArray,
    val translationSigmaMeters: Double,
    val rotationSigmaDegrees: Double,
) {
    init {
        require(frameId > 0 && timestampNs > 0) { "Optimized pose identity is invalid" }
        require(worldFromCamera.size == 7 && worldFromCamera.all { it.isFinite() }) {
            "Optimized pose must contain seven finite values"
        }
        require(kotlin.math.sqrt(worldFromCamera.take(4).sumOf { it * it }) > 1e-9) {
            "Optimized pose quaternion is invalid"
        }
        require(translationSigmaMeters.isFinite() && translationSigmaMeters >= 0.0 &&
            rotationSigmaDegrees.isFinite() && rotationSigmaDegrees >= 0.0) {
            "Optimized pose uncertainty is invalid"
        }
    }
}

data class ReconstructionPoseManifest(
    val generation: Long,
    val poses: List<OptimizedReconstructionPose>,
) {
    init {
        require(generation > 0 && poses.isNotEmpty()) { "Pose manifest is empty or unversioned" }
        require(poses.size <= ReconstructionPoseManifestCodec.MAX_POSES) { "Pose manifest is too large" }
        require(poses.map { it.frameId }.toSet().size == poses.size &&
            poses.map { it.timestampNs }.toSet().size == poses.size) { "Pose manifest identities are not unique" }
    }
}

internal object ReconstructionPoseManifestCodec {
    private val MAGIC = byteArrayOf(0x41, 0x52, 0x4b, 0x50, 0x4f, 0x53, 0x30, 0x31) // ARKPOS01
    private const val VERSION: Byte = 1
    private const val HEADER_BYTES = 8 + 1 + 3 + 8 + 4 + 4
    private const val ENTRY_BYTES = 8 + 8 + 7 * 8 + 8 + 8
    const val MAX_POSES = 2_000
    const val MAX_BYTES = HEADER_BYTES + MAX_POSES * ENTRY_BYTES

    fun encode(manifest: ReconstructionPoseManifest): ByteArray {
        val poses = manifest.poses.sortedBy { it.frameId }
        val output = ByteBuffer.allocate(Math.addExact(HEADER_BYTES,
            Math.multiplyExact(poses.size, ENTRY_BYTES))).order(ByteOrder.BIG_ENDIAN)
        output.put(MAGIC).put(VERSION).put(0).put(0).put(0)
            .putLong(manifest.generation).putInt(poses.size)
        val checksumOffset = output.position()
        output.putInt(0)
        poses.forEach { pose ->
            output.putLong(pose.frameId).putLong(pose.timestampNs)
            pose.worldFromCamera.forEach { output.putDouble(it) }
            output.putDouble(pose.translationSigmaMeters).putDouble(pose.rotationSigmaDegrees)
        }
        val bytes = output.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            .putInt(checksumOffset, crc(bytes, checksumOffset))
        return bytes
    }

    fun decode(bytes: ByteArray): ReconstructionPoseManifest {
        require(bytes.size in (HEADER_BYTES + ENTRY_BYTES)..MAX_BYTES) { "Pose manifest size is invalid" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        require(magic.contentEquals(MAGIC) && input.get() == VERSION) { "Pose manifest header is invalid" }
        require(input.get().toInt() == 0 && input.get().toInt() == 0 && input.get().toInt() == 0) {
            "Pose manifest reserved bits are non-zero"
        }
        val generation = input.long
        val count = input.int
        val checksumOffset = input.position()
        val expectedChecksum = input.int
        require(count in 1..MAX_POSES && bytes.size == HEADER_BYTES + count * ENTRY_BYTES) {
            "Pose manifest count is inconsistent"
        }
        require(expectedChecksum == crc(bytes, checksumOffset)) { "Pose manifest checksum is invalid" }
        val poses = List(count) {
            OptimizedReconstructionPose(input.long, input.long, DoubleArray(7) { input.double },
                input.double, input.double)
        }
        return ReconstructionPoseManifest(generation, poses)
    }

    private fun crc(bytes: ByteArray, checksumOffset: Int): Int {
        val checksum = CRC32()
        checksum.update(bytes, 0, checksumOffset)
        checksum.update(byteArrayOf(0, 0, 0, 0))
        checksum.update(bytes, checksumOffset + 4, bytes.size - checksumOffset - 4)
        return checksum.value.toInt()
    }
}

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
 * truncation/corruption; it is not authentication. When
 * [authenticatedEncryption] is enabled, each colour/depth envelope is wrapped
 * in an AES-256-GCM envelope bound to this session and file identity, and the
 * non-exportable key lives in Android Keystore. Hosts must still expose
 * explicit retention/deletion controls; [clear] can delete the key as well as
 * all retained frames.
 */
class ReconstructionFrameArchive(
    context: Context,
    sessionId: String,
    private val maxFrames: Int = 2_000,
    private val maxTotalBytes: Long = 1L shl 30,
    override val authenticatedEncryption: Boolean = false,
    keyAlias: String = "ai.arena.arkernel.reconstruction-frames.v1.$sessionId",
) : ArKernelRetentionActionSink {
    private val directory: File
    private val referencePrefix: String
    private val sessionId: String
    private val keyAlias: String

    init {
        require(SESSION_PATTERN.matches(sessionId)) { "Invalid reconstruction session ID" }
        require(maxFrames > 0 && maxTotalBytes > 0) { "Archive budgets must be positive" }
        require(keyAlias.isNotBlank()) { "Frame encryption key alias must not be blank" }
        this.sessionId = sessionId
        this.keyAlias = keyAlias
        directory = File(context.noBackupFilesDir, "arkernel-reconstruction/$sessionId")
        require(directory.mkdirs() || directory.isDirectory) { "Could not create reconstruction archive" }
        referencePrefix = "arkf://$sessionId/"
    }

    private fun wrapEnvelope(plaintext: ByteArray, fileName: String, kind: Byte): ByteArray =
        if (!authenticatedEncryption) plaintext else
            ReconstructionFrameAeadCodec.encrypt(plaintext,
                ReconstructionFrameAeadCodec.aad(sessionId, fileName, kind), keyAlias)

    private fun unwrapEnvelope(bytes: ByteArray, fileName: String, kind: Byte): ByteArray =
        if (!authenticatedEncryption) bytes else
            ReconstructionFrameAeadCodec.decrypt(bytes,
                ReconstructionFrameAeadCodec.aad(sessionId, fileName, kind), keyAlias)

    @Synchronized
    fun save(frame: ArchivedReconstructionFrame): String {
        val envelope = ReconstructionFrameCodec.encode(frame)
        val name = fileName(frame.metadata.timestampNs, frame.metadata.frameId)
        val payload = wrapEnvelope(envelope, name, ReconstructionFrameAeadCodec.KIND_COLOUR)
        require(payload.size.toLong() <= maxTotalBytes) { "Frame exceeds archive byte budget" }
        val target = File(directory, name)
        require(!target.exists()) { "Frame already exists" }
        evictFor(payload.size.toLong())
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(payload)
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
        return ReconstructionFrameCodec.decode(
            unwrapEnvelope(bytes, name, ReconstructionFrameAeadCodec.KIND_COLOUR))
    }

    /**
     * Atomically publishes tightly packed visibility depth beside an existing
     * archived frame. The public frame reference remains the sole lookup key.
     */
    @Synchronized
    fun saveVisibilityDepth(reference: String, depth: MetricDepthBuffer) {
        val name = validatedFrameName(reference)
        val frame = load(reference) ?: throw IllegalArgumentException("Archived colour frame is missing")
        require(depth.timestampNs == frame.metadata.timestampNs &&
            depth.width == frame.metadata.width && depth.height == frame.metadata.height) {
            "Visibility depth provenance does not match archived colour frame"
        }
        val archived = ReconstructionVisibilityDepthCodec.copyFrom(frame.metadata.frameId, depth)
        val envelope = ReconstructionVisibilityDepthCodec.encode(archived)
        val depthName = depthFile(name).name
        val payload = wrapEnvelope(envelope, depthName, ReconstructionFrameAeadCodec.KIND_DEPTH)
        require(payload.size.toLong() <= maxTotalBytes) { "Visibility depth exceeds archive budget" }
        val target = depthFile(name)
        require(!target.exists()) { "Visibility depth already exists" }
        evictFor(payload.size.toLong(), protectedFrameName = name, addsFrame = false)
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(payload)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun loadVisibilityDepth(reference: String): ArchivedReconstructionVisibilityDepth? {
        val name = validatedFrameName(reference)
        val frame = load(reference) ?: return null
        val atomic = AtomicFile(depthFile(name))
        val input = try { atomic.openRead() } catch (_: FileNotFoundException) { return null }
        val length = atomic.baseFile.length()
        require(length in 1..(ReconstructionVisibilityDepthCodec.MAX_BYTES +
                MAX_AEAD_OVERHEAD_BYTES).toLong()) {
            input.close(); "Archived visibility depth has an invalid size"
        }
        val bytes = input.use { it.readBytes() }
        require(bytes.size.toLong() == length) { "Archived visibility depth was truncated" }
        val depth = ReconstructionVisibilityDepthCodec.decode(
            unwrapEnvelope(bytes, depthFile(name).name, ReconstructionFrameAeadCodec.KIND_DEPTH))
        require(depth.frameId == frame.metadata.frameId &&
            depth.timestampNs == frame.metadata.timestampNs &&
            depth.width == frame.metadata.width && depth.height == frame.metadata.height) {
            "Archived visibility depth provenance does not match colour frame"
        }
        return depth
    }

    @Synchronized
    fun publishOptimizedPoses(manifest: ReconstructionPoseManifest) {
        require(manifest.poses.size <= maxFrames) { "Pose manifest exceeds archive frame budget" }
        manifest.poses.forEach { pose ->
            require(File(directory, fileName(pose.timestampNs, pose.frameId)).isFile) {
                "Optimized pose has no matching archived frame"
            }
        }
        val previous = loadPoseManifest()
        require(previous == null || manifest.generation > previous.generation) {
            "Pose manifest generation must increase"
        }
        val bytes = ReconstructionPoseManifestCodec.encode(manifest)
        val atomic = AtomicFile(manifestFile())
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun loadPoseManifest(): ReconstructionPoseManifest? {
        val atomic = AtomicFile(manifestFile())
        val input = try { atomic.openRead() } catch (_: FileNotFoundException) { return null }
        val length = atomic.baseFile.length()
        require(length in 1..ReconstructionPoseManifestCodec.MAX_BYTES.toLong()) {
            input.close(); "Pose manifest has an invalid size"
        }
        val manifest = ReconstructionPoseManifestCodec.decode(input.use { it.readBytes() })
        manifest.poses.forEach { pose ->
            require(File(directory, fileName(pose.timestampNs, pose.frameId)).isFile) {
                "Pose manifest references a missing frame"
            }
        }
        return manifest
    }

    @Synchronized
    fun loadWithOptimizedPose(reference: String): ArchivedReconstructionFrame? {
        val frame = load(reference) ?: return null
        val optimized = loadPoseManifest()?.poses?.firstOrNull {
            it.frameId == frame.metadata.frameId && it.timestampNs == frame.metadata.timestampNs
        } ?: return frame
        return frame.copy(metadata = frame.metadata.copy(
            worldFromCamera = optimized.worldFromCamera.copyOf(),
            translationSigmaMeters = optimized.translationSigmaMeters,
            rotationSigmaDegrees = optimized.rotationSigmaDegrees,
        ))
    }

    @Synchronized
    fun delete(reference: String): Boolean {
        val name = validatedFrameName(reference)
        val deleted = File(directory, name).delete()
        if (deleted) {
            val companion = depthFile(name)
            if (companion.exists()) check(companion.delete()) { "Could not delete visibility depth" }
            invalidatePoseManifest()
        }
        return deleted
    }

    /** Explicit host deletion of all retained frames and optional key rotation. */
    @Synchronized
    fun clear(deleteKey: Boolean = false): Boolean {
        val deleted = directory.deleteRecursively()
        if (deleted) require(directory.mkdirs() || directory.isDirectory)
        if (deleteKey && authenticatedEncryption) deleteEncryptionKey()
        return deleted
    }

    /** Bounded action-sink entry point; the host still calls this explicitly. */
    override fun clearFrames(deleteKey: Boolean): Boolean = clear(deleteKey)

    /** Explicit host deletion of the non-exportable Android Keystore key. */
    @Synchronized
    override fun deleteEncryptionKey(): Boolean =
        authenticatedEncryption && ReconstructionFrameAeadCodec.deleteKey(keyAlias)

    @Synchronized
    fun references(): List<String> = frameFiles().map { referencePrefix + it.name }

    private fun evictFor(
        incomingBytes: Long,
        protectedFrameName: String? = null,
        addsFrame: Boolean = true,
    ) {
        cleanupOrphanDepthFiles()
        val files = frameFiles().toMutableList()
        var bytes = archivePayloadFiles().sumOf { it.length() }
        fun overBudget() = (addsFrame && files.size >= maxFrames) ||
            bytes > maxTotalBytes - incomingBytes
        if (overBudget()) invalidatePoseManifest()
        while (overBudget()) {
            val victimIndex = files.indexOfFirst { it.name != protectedFrameName }
            require(victimIndex >= 0) { "Archive budget cannot be satisfied without evicting source frame" }
            val victim = files.removeAt(victimIndex)
            val companion = depthFile(victim.name)
            val length = victim.length() + if (companion.isFile) companion.length() else 0L
            check(victim.delete()) { "Could not evict old reconstruction frame" }
            if (companion.exists()) check(companion.delete()) { "Could not evict visibility depth" }
            bytes -= length
        }
    }

    private fun manifestFile(): File = File(directory, POSE_MANIFEST_FILE)

    private fun invalidatePoseManifest() {
        val file = manifestFile()
        if (file.exists()) check(file.delete()) { "Could not invalidate optimized pose manifest" }
    }

    private fun validatedFrameName(reference: String): String {
        val name = reference.removePrefix(referencePrefix)
        require(reference.startsWith(referencePrefix) && FILE_PATTERN.matches(name)) {
            "Frame reference does not belong to this archive"
        }
        return name
    }

    private fun depthFile(frameName: String): File =
        File(directory, frameName.removeSuffix(".arkf") + ".arkd")

    private fun frameFiles(): List<File> = directory.listFiles { file ->
        file.isFile && FILE_PATTERN.matches(file.name)
    }?.sortedBy { it.name } ?: emptyList()

    private fun archivePayloadFiles(): List<File> = directory.listFiles { file ->
        file.isFile && (FILE_PATTERN.matches(file.name) || DEPTH_FILE_PATTERN.matches(file.name))
    }?.toList() ?: emptyList()

    private fun cleanupOrphanDepthFiles() {
        directory.listFiles { file -> file.isFile && DEPTH_FILE_PATTERN.matches(file.name) }
            ?.forEach { depth ->
                val frame = File(directory, depth.name.removeSuffix(".arkd") + ".arkf")
                if (!frame.isFile) check(depth.delete()) { "Could not delete orphan visibility depth" }
            }
    }

    private fun fileName(timestampNs: Long, frameId: Long): String =
        String.format(Locale.US, "%019d-%019d.arkf", timestampNs, frameId)

    private companion object {
        const val MAX_HEADER_BYTES = 320
        const val MAX_AEAD_OVERHEAD_BYTES = 64
        const val POSE_MANIFEST_FILE = "optimized-poses.arkp"
        val SESSION_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val FILE_PATTERN = Regex("[0-9]{19}-[0-9]{19}\\.arkf")
        val DEPTH_FILE_PATTERN = Regex("[0-9]{19}-[0-9]{19}\\.arkd")
    }
}
