package ai.arena.arkernel

data class ReconstructionPoseUncertainty(
    val translationSigmaMeters: Double,
    val rotationSigmaDegrees: Double,
) {
    init {
        require(translationSigmaMeters.isFinite() && translationSigmaMeters >= 0.0)
        require(rotationSigmaDegrees.isFinite() && rotationSigmaDegrees >= 0.0)
    }
}

data class StoredReconstructionFrame(
    val frameId: Long,
    val timestampNs: Long,
    val reference: String,
)

/**
 * Transactional coordinator for the session pre-copy gate and frame callback.
 * Exactly one planner reservation can be awaiting pixels or durable storage.
 */
class ReconstructionScanController(
    private val planner: TransactionalReconstructionPlanner,
    archiveSink: (ArchivedReconstructionFrame) -> String,
    writerCapacity: Int = 2,
    private val uncertaintyProvider: (ArKernelSessionResult) -> ReconstructionPoseUncertainty?,
    private val objectObservationProvider:
        (ArKernelSessionResult) -> ReconstructionObjectObservation? = { null },
    private val onDecision: (ReconstructionPlannerDecision) -> Unit = {},
    private val onStored: (StoredReconstructionFrame) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private data class Pending(
        val frameId: Long,
        val timestampNs: Long,
        val token: Long,
        val uncertainty: ReconstructionPoseUncertainty,
        var submitted: Boolean = false,
    )

    private val lock = Any()
    private var pending: Pending? = null
    private var nextFrameId = 1L
    @Volatile private var closed = false
    private val writer = BoundedReconstructionFrameWriter(
        capacity = writerCapacity,
        sink = archiveSink,
        onStoredFrame = ::handleStored,
        onFailedFrame = ::handleFailed,
    )

    /** Pass this function to [ArKernelSession.reconstructionQualityGate]. */
    fun qualityGate(session: ArKernelSessionResult, quality: CameraFrameQuality): Boolean {
        val decision: ReconstructionPlannerDecision
        synchronized(lock) {
            if (closed) return false
            val previous = pending
            if (previous != null) {
                // No pixel callback arrived for the previous reservation. A submitted frame is
                // already owned by the writer and must finish before planner state can advance.
                if (previous.submitted) return false
                planner.cancel(previous.token)
                pending = null
            }
            val uncertainty = uncertaintyProvider(session) ?: return false
            val frameId = nextFrameId
            nextFrameId = if (nextFrameId == Long.MAX_VALUE) 1L else nextFrameId + 1L
            decision = planner.reserve(frameId, session, quality,
                uncertainty.translationSigmaMeters, uncertainty.rotationSigmaDegrees,
                objectObservationProvider(session))
            if (decision.accepted) {
                check(decision.reservationToken > 0) { "Accepted reservation has no token" }
                pending = Pending(frameId, session.visual.timestampNs,
                    decision.reservationToken, uncertainty)
            }
        }
        notifyDecision(decision)
        return decision.accepted
    }

    /** Pass this function to [ArKernelSession.onReconstructionFrame]. */
    fun reconstructionFrame(frame: ArKernelReconstructionFrame) {
        var failure: Throwable? = null
        synchronized(lock) {
            if (closed) return
            val reservation = pending
            if (reservation == null || reservation.timestampNs != frame.camera.timestampNs ||
                reservation.submitted) return
            val archived = try {
                frame.toArchivedFrame(reservation.frameId,
                    reservation.uncertainty.translationSigmaMeters,
                    reservation.uncertainty.rotationSigmaDegrees)
            } catch (error: Throwable) {
                planner.cancel(reservation.token); pending = null; failure = error; null
            }
            if (archived != null) {
                reservation.submitted = true
                if (!writer.trySubmit(archived)) {
                    planner.cancel(reservation.token); pending = null
                    failure = IllegalStateException("Reconstruction archive writer is saturated")
                }
            }
        }
        failure?.let(::notifyError)
    }

    fun hasPendingFrame(): Boolean = synchronized(lock) { pending != null }

    private fun handleStored(frame: ArchivedReconstructionFrame, reference: String) {
        var stored: StoredReconstructionFrame? = null
        var failure: Throwable? = null
        synchronized(lock) {
            val reservation = pending
            if (reservation == null || reservation.frameId != frame.metadata.frameId ||
                reservation.timestampNs != frame.metadata.timestampNs) return
            if (planner.commit(reservation.token)) {
                stored = StoredReconstructionFrame(reservation.frameId,
                    reservation.timestampNs, reference)
            } else failure = IllegalStateException("Could not commit stored reconstruction frame")
            pending = null
        }
        stored?.let(::notifyStored)
        failure?.let(::notifyError)
    }

    private fun handleFailed(frame: ArchivedReconstructionFrame, error: Throwable) {
        synchronized(lock) {
            val reservation = pending
            if (reservation != null && reservation.frameId == frame.metadata.frameId) {
                planner.cancel(reservation.token)
                pending = null
            }
        }
        notifyError(error)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // Draining may commit the current submitted frame through handleStored().
        writer.close()
        synchronized(lock) {
            pending?.let { planner.cancel(it.token) }
            pending = null
        }
        planner.close()
    }

    private fun notifyDecision(value: ReconstructionPlannerDecision) =
        try { onDecision(value) } catch (_: Throwable) { }
    private fun notifyStored(value: StoredReconstructionFrame) =
        try { onStored(value) } catch (_: Throwable) { }
    private fun notifyError(value: Throwable) =
        try { onError(value) } catch (_: Throwable) { }
}

fun ReconstructionFrameArchive.newScanController(
    mode: ReconstructionScanMode,
    plannerConfig: ReconstructionPlannerConfig = ReconstructionPlannerConfig(),
    writerCapacity: Int = 2,
    uncertaintyProvider: (ArKernelSessionResult) -> ReconstructionPoseUncertainty?,
    objectObservationProvider: (ArKernelSessionResult) -> ReconstructionObjectObservation? = { null },
    onDecision: (ReconstructionPlannerDecision) -> Unit = {},
    onStored: (StoredReconstructionFrame) -> Unit = {},
    onError: (Throwable) -> Unit = {},
): ReconstructionScanController = ReconstructionScanController(
    ArKernelReconstructionPlanner(mode, plannerConfig), { frame -> save(frame) }, writerCapacity,
    uncertaintyProvider, objectObservationProvider, onDecision, onStored, onError)
