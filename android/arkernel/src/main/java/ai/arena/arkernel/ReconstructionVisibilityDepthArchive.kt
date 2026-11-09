package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.CRC32

data class ArchivedReconstructionVisibilityDepth(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    /** Tightly packed optical-axis Z depth; non-finite values remain unknown. */
    val depthMeters: FloatArray,
    val confidence: FloatArray? = null,
    val uncertaintyMeters: FloatArray? = null,
) {
    init {
        require(frameId > 0 && timestampNs > 0 && width > 0 && height > 0)
        val pixels = Math.multiplyExact(width, height)
        require(depthMeters.size == pixels)
        require(confidence == null || confidence.size == pixels)
        require(uncertaintyMeters == null || uncertaintyMeters.size == pixels)
    }

    fun toMetricDepthBuffer(): MetricDepthBuffer = MetricDepthBuffer(width, height, width,
        direct(depthMeters), confidence?.let(::direct), if (confidence == null) 0 else width,
        timestampNs, uncertaintyMeters?.let(::direct),
        if (uncertaintyMeters == null) 0 else width)

    private fun direct(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(Math.multiplyExact(values.size, 4))
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); flip() }
}

internal object ReconstructionVisibilityDepthCodec {
    private val MAGIC = byteArrayOf(0x41, 0x52, 0x4b, 0x44, 0x45, 0x50, 0x30, 0x31) // ARKDEP01
    private const val VERSION: Byte = 1
    private const val FLAG_CONFIDENCE = 1
    private const val FLAG_UNCERTAINTY = 2
    private const val HEADER_BYTES = 8 + 1 + 1 + 2 + 8 + 8 + 4 + 4 + 4 + 4
    const val MAX_PIXELS = 16_777_216
    const val MAX_BYTES = HEADER_BYTES + MAX_PIXELS * 3 * 4

    fun encode(depth: ArchivedReconstructionVisibilityDepth): ByteArray {
        val pixels = Math.multiplyExact(depth.width, depth.height)
        require(pixels in 1..MAX_PIXELS)
        var flags = 0
        if (depth.confidence != null) flags = flags or FLAG_CONFIDENCE
        if (depth.uncertaintyMeters != null) flags = flags or FLAG_UNCERTAINTY
        val planeCount = 1 + Integer.bitCount(flags)
        val payloadBytes = Math.multiplyExact(Math.multiplyExact(pixels, planeCount), 4)
        val output = ByteBuffer.allocate(Math.addExact(HEADER_BYTES, payloadBytes))
            .order(ByteOrder.BIG_ENDIAN)
        output.put(MAGIC).put(VERSION).put(flags.toByte()).putShort(0)
            .putLong(depth.frameId).putLong(depth.timestampNs).putInt(depth.width)
            .putInt(depth.height).putInt(payloadBytes)
        val checksumOffset = output.position()
        output.putInt(0)
        depth.depthMeters.forEach { output.putFloat(it) }
        depth.confidence?.forEach { output.putFloat(it) }
        depth.uncertaintyMeters?.forEach { output.putFloat(it) }
        val bytes = output.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            .putInt(checksumOffset, checksum(bytes, checksumOffset))
        return bytes
    }

    fun decode(bytes: ByteArray): ArchivedReconstructionVisibilityDepth {
        require(bytes.size in (HEADER_BYTES + 4)..MAX_BYTES)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        require(magic.contentEquals(MAGIC) && input.get() == VERSION)
        val flags = input.get().toInt() and 0xff
        require(flags and (FLAG_CONFIDENCE or FLAG_UNCERTAINTY).inv() == 0 &&
            input.short.toInt() == 0)
        val frameId = input.long
        val timestampNs = input.long
        val width = input.int
        val height = input.int
        val payloadBytes = input.int
        require(width > 0 && height > 0)
        val checksumOffset = input.position()
        val expectedChecksum = input.int
        val pixels = Math.multiplyExact(width, height)
        require(pixels in 1..MAX_PIXELS)
        val planeCount = 1 + Integer.bitCount(flags)
        require(payloadBytes == Math.multiplyExact(Math.multiplyExact(pixels, planeCount), 4) &&
            payloadBytes == input.remaining())
        require(expectedChecksum == checksum(bytes, checksumOffset))
        fun plane() = FloatArray(pixels) { input.float }
        val depth = plane()
        val confidence = if (flags and FLAG_CONFIDENCE != 0) plane() else null
        val uncertainty = if (flags and FLAG_UNCERTAINTY != 0) plane() else null
        require(!input.hasRemaining())
        return ArchivedReconstructionVisibilityDepth(frameId, timestampNs, width, height,
            depth, confidence, uncertainty)
    }

    fun copyFrom(frameId: Long, depth: MetricDepthBuffer): ArchivedReconstructionVisibilityDepth {
        require(depth.width > 0 && depth.height > 0)
        fun copy(source: FloatBuffer, stride: Int): FloatArray {
            require(stride >= depth.width)
            val required = Math.addExact(Math.multiplyExact(depth.height - 1, stride), depth.width)
            require(source.remaining() >= required)
            val view = source.duplicate()
            val start = view.position()
            return FloatArray(Math.multiplyExact(depth.width, depth.height)) { index ->
                view.get(start + (index / depth.width) * stride + index % depth.width)
            }
        }
        return ArchivedReconstructionVisibilityDepth(frameId, depth.timestampNs,
            depth.width, depth.height, copy(depth.depthMeters, depth.rowStrideElements),
            depth.confidence?.let { copy(it, depth.confidenceRowStrideElements) },
            depth.uncertaintyMeters?.let { copy(it, depth.uncertaintyRowStrideElements) })
    }

    private fun checksum(bytes: ByteArray, checksumOffset: Int): Int {
        val crc = CRC32()
        crc.update(bytes, 0, checksumOffset)
        crc.update(byteArrayOf(0, 0, 0, 0))
        crc.update(bytes, checksumOffset + 4, bytes.size - checksumOffset - 4)
        return crc.value.toInt()
    }
}
