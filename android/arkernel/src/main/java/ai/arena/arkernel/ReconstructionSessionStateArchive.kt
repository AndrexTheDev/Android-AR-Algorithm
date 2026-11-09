package ai.arena.arkernel

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32

private const val MAX_STATE_BYTES = 8 * 1024 * 1024
private const val MIN_STATE_BYTES = 128
private const val MAX_SESSION_ID_BYTES = 64
private const val MAX_ASSET_ID_BYTES = 64
private const val MAX_QUALITY_GRADE_BYTES = 32
private const val MAX_REFERENCE_BYTES = 1024
private const val MAX_ERROR_BYTES = 4096
private const val MAX_FRAMES = 4_096
private const val MAX_FUSION_UPDATES = 4_096
private const val POSE_VALUES = 7

private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
private val ASSET_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

private fun utf8Length(value: String) = value.toByteArray(Charsets.UTF_8).size

/**
 * Bounded terminal state of one reconstruction session generation. It carries
 * opaque references and exact telemetry only; colour, depth and trajectory
 * payloads remain in their own archives. The convenience quality grade stays
 * explicitly unverified unless separate assessed evidence is supplied later.
 */
data class ReconstructionSessionStateRecord(
    val sessionId: String,
    val assetId: String,
    val generation: Long,
    val stage: ReconstructionTexturedSessionStage,
    val complete: Boolean,
    val error: String,
    val qualityGrade: String,
    val poseManifestGeneration: Long?,
    val frames: List<ReconstructionTexturedSessionFrameEvidence>,
    val archivedBytes: Long,
    val canonicalBytes: Long,
    val decodedCompressedSources: Int,
    val visibilityDepthBytes: Long,
    val loadedVisibilityDepthSources: Int,
    val fusionUpdates: List<ReconstructionFusionUpdate>,
    val sourceMesh: ReconstructionTexturedSessionMeshTelemetry,
    val textureStage: ReconstructionTexturePipelineStage,
    val textureInputValid: Boolean,
    val textureTruncated: Boolean,
    val textureError: String,
    val textureTelemetry: ReconstructionTexturePipelineTelemetry,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val atlasBytes: Int,
    val asset: PublishedTexturedReconstructionAsset?,
) {
    init {
        require(SESSION_ID_PATTERN.matches(sessionId)) { "Invalid durable session ID" }
        require(ASSET_ID_PATTERN.matches(assetId)) { "Invalid durable asset ID" }
        require(generation >= 0) { "Durable state generation must not be negative" }
        require(utf8Length(sessionId) <= MAX_SESSION_ID_BYTES &&
            utf8Length(assetId) <= MAX_ASSET_ID_BYTES)
        require(utf8Length(error) <= MAX_ERROR_BYTES &&
            utf8Length(sourceMesh.error) <= MAX_ERROR_BYTES &&
            utf8Length(textureError) <= MAX_ERROR_BYTES)
        require(utf8Length(qualityGrade) in 1..MAX_QUALITY_GRADE_BYTES)
        require(poseManifestGeneration == null || poseManifestGeneration > 0)
        require(frames.size <= MAX_FRAMES && fusionUpdates.size <= MAX_FUSION_UPDATES)
        require(textureTelemetry.values.size == ReconstructionTexturePipelineTelemetry.FIELD_COUNT)
        require(complete == (stage == ReconstructionTexturedSessionStage.COMPLETE &&
            error.isEmpty() && asset != null)) {
            "Durable state completion does not match its asset and error"
        }
        require(asset == null || complete) { "Incomplete durable state cannot carry an asset" }
        require(error.isEmpty() || !complete) { "Complete durable state cannot carry an error" }
        require(archivedBytes >= 0 && canonicalBytes >= 0 && visibilityDepthBytes >= 0 &&
            decodedCompressedSources >= 0 && loadedVisibilityDepthSources >= 0)
        require(atlasWidth >= 0 && atlasHeight >= 0 && atlasBytes >= 0)
        require(sourceMesh.sourceVertices >= 0 && sourceMesh.sourceTriangles >= 0 &&
            sourceMesh.rawMeshBytes >= 0)
        frames.forEach { frame ->
            require(utf8Length(frame.reference) <= MAX_REFERENCE_BYTES)
            val calibration = frame.calibration
            require(calibration.width == frame.width && calibration.height == frame.height &&
                calibration.fx > 0.0 && calibration.fy > 0.0 &&
                listOf(calibration.fx, calibration.fy, calibration.cx, calibration.cy,
                    calibration.k1, calibration.k2, calibration.p1, calibration.p2,
                    calibration.k3).all { it.isFinite() }) {
                "Durable frame calibration is invalid"
            }
            require(frame.worldFromCamera.size == POSE_VALUES &&
                frame.worldFromCamera.all { it.isFinite() })
        }
        asset?.let {
            require(utf8Length(it.reference) <= MAX_REFERENCE_BYTES &&
                it.vertices >= 0 && it.triangles >= 0 && it.pngBytes >= 0 && it.glbBytes >= 0)
        }
    }
}

/** Strict dependency-free `ARKSES01` envelope codec, separated from Android I/O for JVM testing. */
internal object ReconstructionSessionStateCodec {
    private val MAGIC = byteArrayOf(0x41, 0x52, 0x4b, 0x53, 0x45, 0x53, 0x30, 0x31) // ARKSES01
    private const val VERSION: Byte = 1
    private const val FLAG_POSE_MANIFEST = 1
    private const val FLAG_ASSET = 2
    const val MAX_BYTES = MAX_STATE_BYTES

    fun encode(record: ReconstructionSessionStateRecord): ByteArray {
        var flags = 0
        if (record.poseManifestGeneration != null) flags = flags or FLAG_POSE_MANIFEST
        if (record.asset != null) flags = flags or FLAG_ASSET
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(MAGIC)
        data.writeByte(VERSION.toInt())
        data.writeByte(flags)
        data.writeShort(0)
        data.writeLong(record.generation)
        data.writeInt(record.stage.ordinal)
        writeBool(data, record.complete)
        data.writeLong(record.poseManifestGeneration ?: 0L)
        data.writeInt(record.frames.size)
        data.writeInt(record.fusionUpdates.size)
        data.writeInt(record.textureTelemetry.values.size)
        data.writeLong(record.archivedBytes)
        data.writeLong(record.canonicalBytes)
        data.writeInt(record.decodedCompressedSources)
        data.writeLong(record.visibilityDepthBytes)
        data.writeInt(record.loadedVisibilityDepthSources)
        data.writeInt(record.textureStage.ordinal)
        writeBool(data, record.textureInputValid)
        writeBool(data, record.textureTruncated)
        data.writeInt(record.atlasWidth)
        data.writeInt(record.atlasHeight)
        data.writeInt(record.atlasBytes)
        writeBool(data, record.sourceMesh.complete)
        writeBool(data, record.sourceMesh.fusionTruncated)
        writeBool(data, record.sourceMesh.meshingTruncated)
        writeBool(data, record.sourceMesh.filteringTruncated)
        writeBool(data, record.sourceMesh.simplificationTruncated)
        data.writeLong(record.sourceMesh.sourceVertices)
        data.writeLong(record.sourceMesh.sourceTriangles)
        data.writeLong(record.sourceMesh.rawMeshBytes)
        val checksumOffset = output.size()
        data.writeInt(0)
        writeString(data, record.sessionId, MAX_SESSION_ID_BYTES)
        writeString(data, record.assetId, MAX_ASSET_ID_BYTES)
        writeString(data, record.error, MAX_ERROR_BYTES)
        writeString(data, record.qualityGrade, MAX_QUALITY_GRADE_BYTES)
        writeString(data, record.sourceMesh.error, MAX_ERROR_BYTES)
        writeString(data, record.textureError, MAX_ERROR_BYTES)
        record.frames.forEach { writeFrame(data, it) }
        record.fusionUpdates.forEach { writeFusionUpdate(data, it) }
        record.textureTelemetry.values.forEach { data.writeLong(it) }
        record.asset?.let { asset ->
            writeString(data, asset.reference, MAX_REFERENCE_BYTES)
            data.writeInt(asset.vertices)
            data.writeInt(asset.triangles)
            data.writeInt(asset.pngBytes)
            data.writeInt(asset.glbBytes)
        }
        data.flush()
        val bytes = output.toByteArray()
        require(bytes.size in MIN_STATE_BYTES..MAX_BYTES) { "Durable state envelope size is invalid" }
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            .putInt(checksumOffset, checksum(bytes, checksumOffset))
        return bytes
    }

    fun decode(bytes: ByteArray): ReconstructionSessionStateRecord {
        require(bytes.size in MIN_STATE_BYTES..MAX_BYTES) { "Durable state envelope size is invalid" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        require(magic.contentEquals(MAGIC)) { "Durable state magic is invalid" }
        require(input.get() == VERSION) { "Unsupported durable state version" }
        val flags = input.get().toInt() and 0xff
        require(flags and (FLAG_POSE_MANIFEST or FLAG_ASSET).inv() == 0 &&
            input.short.toInt() == 0) { "Durable state reserved bits are non-zero" }
        val generation = input.long
        val stage = ReconstructionTexturedSessionStage.entries.getOrNull(input.int)
            ?: throw IllegalArgumentException("Unknown durable session stage")
        val complete = readBool(input)
        val storedPoseManifestGeneration = input.long
        if (flags and FLAG_POSE_MANIFEST == 0) require(storedPoseManifestGeneration == 0L)
        val frameCount = input.int
        val fusionCount = input.int
        val telemetryCount = input.int
        require(frameCount in 0..MAX_FRAMES && fusionCount in 0..MAX_FUSION_UPDATES &&
            telemetryCount == ReconstructionTexturePipelineTelemetry.FIELD_COUNT)
        val archivedBytes = input.long
        val canonicalBytes = input.long
        val decodedCompressedSources = input.int
        val visibilityDepthBytes = input.long
        val loadedVisibilityDepthSources = input.int
        val textureStage = ReconstructionTexturePipelineStage.entries.getOrNull(input.int)
            ?: throw IllegalArgumentException("Unknown durable texture stage")
        val textureInputValid = readBool(input)
        val textureTruncated = readBool(input)
        val atlasWidth = input.int
        val atlasHeight = input.int
        val atlasBytes = input.int
        val sourceMeshComplete = readBool(input)
        val fusionTruncated = readBool(input)
        val meshingTruncated = readBool(input)
        val filteringTruncated = readBool(input)
        val simplificationTruncated = readBool(input)
        val sourceVertices = input.long
        val sourceTriangles = input.long
        val rawMeshBytes = input.long
        val checksumOffset = input.position()
        val expectedChecksum = input.int
        require(checksumOffset + 4 <= bytes.size &&
            expectedChecksum == checksum(bytes, checksumOffset)) {
            "Durable state checksum is invalid"
        }
        val sessionId = readString(input, MAX_SESSION_ID_BYTES)
        val assetId = readString(input, MAX_ASSET_ID_BYTES)
        val error = readString(input, MAX_ERROR_BYTES)
        val qualityGrade = readString(input, MAX_QUALITY_GRADE_BYTES)
        val sourceMeshError = readString(input, MAX_ERROR_BYTES)
        val textureError = readString(input, MAX_ERROR_BYTES)
        val frames = List(frameCount) { readFrame(input) }
        val fusionUpdates = List(fusionCount) { readFusionUpdate(input) }
        val telemetryValues = LongArray(telemetryCount) { input.long }
        val asset = if (flags and FLAG_ASSET != 0) {
            PublishedTexturedReconstructionAsset(
                readString(input, MAX_REFERENCE_BYTES), input.int, input.int,
                input.int, input.int)
        } else null
        require(!input.hasRemaining()) { "Durable state payload length is inconsistent" }
        return ReconstructionSessionStateRecord(
            sessionId = sessionId, assetId = assetId, generation = generation,
            stage = stage, complete = complete, error = error,
            qualityGrade = qualityGrade,
            poseManifestGeneration = if (flags and FLAG_POSE_MANIFEST != 0)
                storedPoseManifestGeneration else null,
            frames = frames, archivedBytes = archivedBytes, canonicalBytes = canonicalBytes,
            decodedCompressedSources = decodedCompressedSources,
            visibilityDepthBytes = visibilityDepthBytes,
            loadedVisibilityDepthSources = loadedVisibilityDepthSources,
            fusionUpdates = fusionUpdates,
            sourceMesh = ReconstructionTexturedSessionMeshTelemetry(
                complete = sourceMeshComplete, fusionTruncated = fusionTruncated,
                meshingTruncated = meshingTruncated, filteringTruncated = filteringTruncated,
                simplificationTruncated = simplificationTruncated,
                sourceVertices = sourceVertices, sourceTriangles = sourceTriangles,
                rawMeshBytes = rawMeshBytes, error = sourceMeshError),
            textureStage = textureStage, textureInputValid = textureInputValid,
            textureTruncated = textureTruncated, textureError = textureError,
            textureTelemetry = ReconstructionTexturePipelineTelemetry(telemetryValues),
            atlasWidth = atlasWidth, atlasHeight = atlasHeight, atlasBytes = atlasBytes,
            asset = asset)
    }

    private fun writeString(output: DataOutputStream, value: String, limit: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "Durable state string exceeds its bound" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: ByteBuffer, limit: Int): String {
        val length = input.int
        require(length in 0..limit && length <= input.remaining()) {
            "Durable state string length is invalid"
        }
        return ByteArray(length).also { input.get(it) }.toString(Charsets.UTF_8)
    }

    private fun writeBool(output: DataOutputStream, value: Boolean) =
        output.writeByte(if (value) 1 else 0)

    private fun readBool(input: ByteBuffer): Boolean {
        val value = input.get().toInt() and 0xff
        require(value == 0 || value == 1) { "Durable state boolean is invalid" }
        return value == 1
    }

    private fun writeCalibration(output: DataOutputStream, value: CameraCalibration) {
        output.writeInt(value.width)
        output.writeInt(value.height)
        output.writeDouble(value.fx)
        output.writeDouble(value.fy)
        output.writeDouble(value.cx)
        output.writeDouble(value.cy)
        output.writeDouble(value.k1)
        output.writeDouble(value.k2)
        output.writeDouble(value.p1)
        output.writeDouble(value.p2)
        output.writeDouble(value.k3)
    }

    private fun readCalibration(input: ByteBuffer): CameraCalibration = CameraCalibration(
        input.int, input.int, input.double, input.double, input.double, input.double,
        input.double, input.double, input.double, input.double, input.double)

    private fun writeFrame(
        output: DataOutputStream,
        frame: ReconstructionTexturedSessionFrameEvidence,
    ) {
        output.writeLong(frame.frameId)
        output.writeLong(frame.timestampNs)
        writeString(output, frame.reference, MAX_REFERENCE_BYTES)
        output.writeInt(frame.width)
        output.writeInt(frame.height)
        writeCalibration(output, frame.calibration)
        output.writeDouble(frame.qualityScore)
        frame.worldFromCamera.forEach(output::writeDouble)
        output.writeDouble(frame.translationSigmaMeters)
        output.writeDouble(frame.rotationSigmaDegrees)
        output.writeInt(frame.canonicalBytes)
        output.writeLong(frame.visibilityDepthBytes)
    }

    private fun readFrame(input: ByteBuffer): ReconstructionTexturedSessionFrameEvidence {
        val frameId = input.long
        val timestampNs = input.long
        val reference = readString(input, MAX_REFERENCE_BYTES)
        val width = input.int
        val height = input.int
        val calibration = readCalibration(input)
        val qualityScore = input.double
        val pose = DoubleArray(POSE_VALUES) { input.double }
        val translationSigma = input.double
        val rotationSigma = input.double
        val canonicalBytes = input.int
        val visibilityDepthBytes = input.long
        return ReconstructionTexturedSessionFrameEvidence(
            frameId, timestampNs, reference, width, height, calibration, qualityScore,
            pose, translationSigma, rotationSigma, canonicalBytes, visibilityDepthBytes)
    }

    private fun writeFusionUpdate(
        output: DataOutputStream,
        update: ReconstructionFusionUpdate,
    ) {
        writeBool(output, update.accepted)
        output.writeInt(update.rejection.ordinal)
        output.writeLong(update.acceptedFrames)
        output.writeLong(update.rejectedFrames)
        output.writeLong(update.cumulativeIntegratedPixels)
        output.writeLong(update.cumulativeVoxelUpdates)
        output.writeLong(update.visitedPixels)
        output.writeLong(update.integratedPixels)
        output.writeLong(update.rejectedPixels)
        output.writeLong(update.voxelVisits)
        output.writeLong(update.voxelUpdates)
        output.writeLong(update.allocatedBlocks)
        output.writeLong(update.capacityRejections)
        writeBool(output, update.fusionTruncated)
    }

    private fun readFusionUpdate(input: ByteBuffer): ReconstructionFusionUpdate {
        val accepted = readBool(input)
        val rejection = ReconstructionIntegrationRejection.entries.getOrNull(input.int)
            ?: throw IllegalArgumentException("Unknown durable fusion rejection")
        val acceptedFrames = input.long
        val rejectedFrames = input.long
        val cumulativeIntegratedPixels = input.long
        val cumulativeVoxelUpdates = input.long
        val visitedPixels = input.long
        val integratedPixels = input.long
        val rejectedPixels = input.long
        val voxelVisits = input.long
        val voxelUpdates = input.long
        val allocatedBlocks = input.long
        val capacityRejections = input.long
        val fusionTruncated = readBool(input)
        return ReconstructionFusionUpdate(
            accepted, rejection, acceptedFrames, rejectedFrames,
            cumulativeIntegratedPixels, cumulativeVoxelUpdates, visitedPixels,
            integratedPixels, rejectedPixels, voxelVisits, voxelUpdates,
            allocatedBlocks, capacityRejections, fusionTruncated)
    }

    private fun checksum(bytes: ByteArray, checksumOffset: Int): Int {
        val crc = CRC32()
        crc.update(bytes, 0, checksumOffset)
        crc.update(byteArrayOf(0, 0, 0, 0))
        crc.update(bytes, checksumOffset + 4, bytes.size - checksumOffset - 4)
        return crc.value.toInt()
    }
}

/**
 * Crash-safe bounded state archive in app-private no-backup storage. One
 * AtomicFile per session/generation avoids rewriting other sessions. Generation
 * must strictly increase within a session; session, per-session generation and
 * aggregate byte budgets evict deterministically. CRC detects truncation, not
 * tampering: this is neither encryption nor authenticity, and hosts retain the
 * separate retention/deletion policy for sensitive imagery.
 */
class ReconstructionSessionStateArchive(
    context: Context,
    namespace: String,
    private val maxSessions: Int = 32,
    private val maxGenerationsPerSession: Int = 16,
    private val maxStateBytes: Long = 1L * 1024L * 1024L,
    private val maxTotalBytes: Long = 64L * 1024L * 1024L,
) {
    private val directory: File
    private val referencePrefix: String

    init {
        require(SESSION_ID_PATTERN.matches(namespace)) { "Invalid durable state namespace" }
        require(maxSessions in 1..1_024) { "Invalid durable session budget" }
        require(maxGenerationsPerSession in 1..256) { "Invalid durable generation budget" }
        require(maxStateBytes in 1L..ReconstructionSessionStateCodec.MAX_BYTES.toLong() &&
            maxTotalBytes >= maxStateBytes) { "Invalid durable state byte budgets" }
        directory = File(context.noBackupFilesDir,
            "arkernel-reconstruction-state/$namespace")
        require(directory.mkdirs() || directory.isDirectory) {
            "Could not create durable state archive"
        }
        referencePrefix = "arkses://$namespace/"
    }

    @Synchronized
    fun save(record: ReconstructionSessionStateRecord): String {
        val name = fileName(record.generation, record.sessionId)
        val target = File(directory, name)
        require(!target.exists()) { "Durable state generation already exists" }
        val latest = latest(record.sessionId)
        require(latest == null || record.generation > latest.generation) {
            "Durable state generation must increase"
        }
        val envelope = ReconstructionSessionStateCodec.encode(record)
        require(envelope.size.toLong() <= maxStateBytes &&
            envelope.size.toLong() <= maxTotalBytes) {
            "Durable state exceeds archive byte budget"
        }
        evictFor(envelope.size.toLong(), record.sessionId)
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
    fun load(reference: String): ReconstructionSessionStateRecord? {
        val name = validatedName(reference)
        val atomic = AtomicFile(File(directory, name))
        val input = try { atomic.openRead() } catch (_: FileNotFoundException) { return null }
        val length = atomic.baseFile.length()
        require(length in 1L..maxStateBytes) {
            input.close()
            "Durable state has an invalid size"
        }
        val bytes = input.use { it.readBytes() }
        require(bytes.size.toLong() == length) { "Durable state was truncated" }
        val record = ReconstructionSessionStateCodec.decode(bytes)
        require(record.sessionId == sessionIdOf(name) &&
            record.generation == generationOf(name)) {
            "Durable state reference provenance does not match its envelope"
        }
        return record
    }

    @Synchronized
    fun latest(sessionId: String): ReconstructionSessionStateRecord? {
        require(SESSION_ID_PATTERN.matches(sessionId)) { "Invalid durable session ID" }
        val latestFile = stateFiles().lastOrNull { sessionIdOf(it.name) == sessionId }
            ?: return null
        return load(referencePrefix + latestFile.name)
    }

    @Synchronized
    fun generations(sessionId: String): List<String> {
        require(SESSION_ID_PATTERN.matches(sessionId)) { "Invalid durable session ID" }
        return stateFiles().filter { sessionIdOf(it.name) == sessionId }
            .map { referencePrefix + it.name }
    }

    @Synchronized
    fun references(): List<String> = stateFiles().map { referencePrefix + it.name }

    @Synchronized
    fun delete(reference: String): Boolean = File(directory, validatedName(reference)).delete()

    @Synchronized
    fun clear(): Boolean = directory.deleteRecursively().also {
        if (it) require(directory.mkdirs() || directory.isDirectory)
    }

    /** Host-facing helper: persist one terminal session result under its request provenance. */
    fun record(
        sessionId: String,
        request: ReconstructionTexturedSessionRequest,
        result: ReconstructionTexturedSessionResult,
    ): String = save(ReconstructionSessionStateRecord(
        sessionId = sessionId, assetId = request.assetId,
        generation = request.generation, stage = result.stage,
        complete = result.complete, error = result.error,
        qualityGrade = result.qualityGrade,
        poseManifestGeneration = result.poseManifestGeneration,
        frames = result.frames.map {
            it.copy(worldFromCamera = it.worldFromCamera.copyOf())
        },
        archivedBytes = result.archivedBytes, canonicalBytes = result.canonicalBytes,
        decodedCompressedSources = result.decodedCompressedSources,
        visibilityDepthBytes = result.visibilityDepthBytes,
        loadedVisibilityDepthSources = result.loadedVisibilityDepthSources,
        fusionUpdates = result.fusionUpdates.toList(),
        sourceMesh = result.sourceMesh, textureStage = result.textureStage,
        textureInputValid = result.textureInputValid,
        textureTruncated = result.textureTruncated, textureError = result.textureError,
        textureTelemetry = ReconstructionTexturePipelineTelemetry(
            result.textureTelemetry.values.copyOf()),
        atlasWidth = result.atlasWidth, atlasHeight = result.atlasHeight,
        atlasBytes = result.atlasBytes, asset = result.asset))

    private fun evictFor(incomingBytes: Long, targetSessionId: String) {
        val files = stateFiles().toMutableList()
        var bytes = files.sumOf { it.length() }
        val groups = files.groupBy { sessionIdOf(it.name) }
        if (targetSessionId !in groups && groups.size >= maxSessions) {
            val oldestSession = groups.keys.minByOrNull { key ->
                groups.getValue(key).minOf { it.name }
            } ?: error("Durable state session budget cannot be satisfied")
            val victims = files.filter { sessionIdOf(it.name) == oldestSession }
            victims.forEach { victim ->
                val length = victim.length()
                check(victim.delete()) { "Could not evict old durable state session" }
                bytes -= length
            }
            files.removeAll(victims.toSet())
        }
        val targetFiles = files.filter { sessionIdOf(it.name) == targetSessionId }.toMutableList()
        while (targetFiles.size >= maxGenerationsPerSession) {
            val victim = targetFiles.removeAt(0)
            val length = victim.length()
            check(victim.delete()) { "Could not evict old durable state generation" }
            files.remove(victim)
            bytes -= length
        }
        fun overBudget() = files.size >= maxSessions * maxGenerationsPerSession ||
            bytes > maxTotalBytes - incomingBytes
        while (overBudget()) {
            val victim = files.removeAt(0)
            val length = victim.length()
            check(victim.delete()) { "Could not evict old durable state" }
            bytes -= length
        }
    }

    private fun validatedName(reference: String): String {
        val name = reference.removePrefix(referencePrefix)
        require(reference.startsWith(referencePrefix) && FILE_PATTERN.matches(name)) {
            "Durable state reference does not belong to this archive"
        }
        return name
    }

    private fun stateFiles(): List<File> = directory.listFiles { file ->
        file.isFile && FILE_PATTERN.matches(file.name)
    }?.sortedBy { it.name } ?: emptyList()

    private fun fileName(generation: Long, sessionId: String): String =
        String.format(Locale.US, "%019d-%s.arks", generation, sessionId)

    private fun sessionIdOf(name: String): String =
        name.removeSuffix(".arks").substringAfter('-')

    private fun generationOf(name: String): Long = name.substringBefore('-').toLong()

    private companion object {
        val FILE_PATTERN = Regex("[0-9]{19}-[A-Za-z0-9_-]{1,64}\\.arks")
    }
}
