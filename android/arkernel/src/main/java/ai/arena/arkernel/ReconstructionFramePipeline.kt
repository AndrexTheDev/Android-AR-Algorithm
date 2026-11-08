package ai.arena.arkernel

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Borrowed YUV_420_888 plane description. [buffer] is never retained. */
data class ReconstructionYuvPlane(
    val buffer: ByteBuffer,
    val rowStrideBytes: Int,
    val pixelStrideBytes: Int,
)

/** Deterministically packs arbitrary valid YUV_420_888 plane strides to Y, U, V I420. */
object ReconstructionI420Packer {
    fun pack(
        width: Int,
        height: Int,
        y: ReconstructionYuvPlane,
        u: ReconstructionYuvPlane,
        v: ReconstructionYuvPlane,
        maxOutputBytes: Int = 64 * 1024 * 1024,
    ): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "I420 dimensions must be positive and even"
        }
        val pixels = Math.multiplyExact(width, height)
        val outputBytes = Math.addExact(pixels, pixels / 2)
        require(outputBytes <= maxOutputBytes) { "I420 frame exceeds output budget" }
        validatePlane(y, width, height)
        validatePlane(u, width / 2, height / 2)
        validatePlane(v, width / 2, height / 2)
        val output = ByteArray(outputBytes)
        var offset = copyPlane(y, width, height, output, 0)
        offset = copyPlane(u, width / 2, height / 2, output, offset)
        check(copyPlane(v, width / 2, height / 2, output, offset) == output.size)
        return output
    }

    private fun validatePlane(plane: ReconstructionYuvPlane, columns: Int, rows: Int) {
        require(plane.rowStrideBytes > 0 && plane.pixelStrideBytes > 0) { "Invalid YUV stride" }
        val lastRow = Math.multiplyExact(rows - 1, plane.rowStrideBytes)
        val lastColumn = Math.multiplyExact(columns - 1, plane.pixelStrideBytes)
        val required = Math.addExact(Math.addExact(lastRow, lastColumn), 1)
        require(required <= plane.buffer.remaining()) { "YUV plane is truncated" }
    }

    private fun copyPlane(
        plane: ReconstructionYuvPlane,
        columns: Int,
        rows: Int,
        output: ByteArray,
        outputOffset: Int,
    ): Int {
        val source = plane.buffer.duplicate()
        val start = source.position()
        var destination = outputOffset
        for (row in 0 until rows) {
            val rowStart = start + row * plane.rowStrideBytes
            for (column in 0 until columns)
                output[destination++] = source.get(rowStart + column * plane.pixelStrideBytes)
        }
        return destination
    }
}

/**
 * Single-worker bounded handoff. Submission copies bytes before returning, so
 * camera-owned buffers may be closed immediately. Disk work runs only on the
 * worker. A full queue drops the new frame explicitly instead of blocking.
 */
class BoundedReconstructionFrameWriter(
    capacity: Int = 2,
    private val sink: (ArchivedReconstructionFrame) -> String,
    private val onStored: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val open = AtomicBoolean(true)
    private val queue: ArrayBlockingQueue<ArchivedReconstructionFrame>
    private val worker: Thread

    init {
        require(capacity in 1..32) { "Frame writer capacity must be in 1..32" }
        queue = ArrayBlockingQueue(capacity)
        worker = Thread(::run, "ARKernel-FrameArchive").apply {
            isDaemon = true
            start()
        }
    }

    /** Returns false when closed or saturated. Never waits for filesystem I/O. */
    @Synchronized
    fun trySubmit(frame: ArchivedReconstructionFrame): Boolean {
        if (!open.get() || queue.remainingCapacity() == 0) return false
        val owned = ArchivedReconstructionFrame(frame.metadata.copy(
            worldFromCamera = frame.metadata.worldFromCamera.copyOf()), frame.imageBytes.copyOf())
        return queue.offer(owned)
    }

    fun pendingCount(): Int = queue.size

    private fun run() {
        while (open.get() || queue.isNotEmpty()) {
            val frame = try { queue.poll(100, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { continue }
            if (frame != null) try { onStored(sink(frame)) } catch (error: Throwable) { onError(error) }
        }
    }

    /** Stops accepting work and drains already accepted frames. */
    override fun close() {
        check(worker !== Thread.currentThread()) { "Frame writer cannot close itself" }
        val shouldClose = synchronized(this) { open.getAndSet(false) }
        if (!shouldClose) return
        worker.interrupt()
        worker.join(30_000)
        check(!worker.isAlive) { "Timed out draining reconstruction frame writer" }
    }
}

fun ReconstructionFrameArchive.newBoundedWriter(
    capacity: Int = 2,
    onStored: (String) -> Unit = {},
    onError: (Throwable) -> Unit = {},
): BoundedReconstructionFrameWriter = BoundedReconstructionFrameWriter(
    capacity, sink = ::save, onStored = onStored, onError = onError)
