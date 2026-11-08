package ai.arena.arkernel

import android.content.Context

data class PerceptionPipelineOptions(
    val minimumIntervalNs: Long = 100_000_000L,
    val cadenceOptions: InferenceCadenceOptions = InferenceCadenceOptions(),
    val enableDetectionTracking: Boolean = true,
    val detectionTrackerOptions: DetectionTrackerOptions = DetectionTrackerOptions(),
    val enableTemporalDepth: Boolean = true,
    val temporalDepthOptions: TemporalDepthFilterOptions = TemporalDepthFilterOptions(),
    val enableAndroidThermalMonitor: Boolean = true,
    val threadName: String = "ArKernelInference",
) {
    init { require(minimumIntervalNs > 0 && threadName.isNotBlank()) }
}

data class PerceptionPipelineSnapshot(
    val cadence: InferenceCadenceSnapshot,
    val temporalDepth: TemporalDepthFilterResult?,
)

/**
 * Owns the correctly ordered inference decorators, scheduler, and optional Android thermal bridge.
 * Callback execution remains on the single inference worker and no queue is introduced.
 */
class ArKernelPerceptionPipeline private constructor(
    private val scheduler: BoundedInferenceScheduler,
    private val cadenceController: InferenceCadenceController,
    private val trackingBackend: TrackedInferenceBackend?,
    private val temporalBackend: TemporalDepthInferenceBackend?,
    private val thermalMonitor: AndroidInferenceThermalMonitor?,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    fun submit(
        frame: PerceptionFrame,
        onResult: (OnDeviceInferenceOutput) -> Unit,
        onError: (Exception) -> Unit = {},
    ): InferenceSubmitResult = synchronized(lock) {
        if (closed) InferenceSubmitResult.CLOSED else scheduler.submit(frame, onResult, onError)
    }

    fun submitAndIntegrate(
        frame: PerceptionFrame,
        session: ArKernelSession,
        depthOptions: DepthSamplingOptions = DepthSamplingOptions(),
        onIntegrated: (PerceptionIntegrationResult) -> Unit = {},
        onError: (Exception) -> Unit = {},
    ): InferenceSubmitResult = submit(frame, { output ->
        onIntegrated(session.integrateInferenceOutput(output, depthOptions))
    }, onError)

    fun updateThermalState(state: InferenceThermalState) =
        scheduler.updateThermalState(state)

    fun snapshot(): PerceptionPipelineSnapshot = PerceptionPipelineSnapshot(
        cadenceController.snapshot(), temporalBackend?.lastFilterResult)

    fun resetState() = synchronized(lock) {
        check(!closed) { "Perception pipeline is closed" }
        trackingBackend?.resetTracks()
        temporalBackend?.resetHistory()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        var failure: Exception? = null
        try {
            thermalMonitor?.close()
        } catch (error: Exception) {
            failure = error
        }
        try {
            scheduler.close()
        } catch (error: Exception) {
            if (failure == null) failure = error else failure?.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    companion object {
        fun create(
            backend: OnDeviceInferenceBackend,
            context: Context? = null,
            options: PerceptionPipelineOptions = PerceptionPipelineOptions(),
        ): ArKernelPerceptionPipeline {
            var ownedBackend = backend
            var tracking: TrackedInferenceBackend? = null
            var temporal: TemporalDepthInferenceBackend? = null
            var closeOwnedBackendOnFailure = true
            try {
                if (options.enableDetectionTracking &&
                    InferenceTask.SEMANTIC_DETECTION in backend.manifest.tasks) {
                    tracking = TrackedInferenceBackend(ownedBackend, options.detectionTrackerOptions)
                    ownedBackend = tracking
                }
                if (options.enableTemporalDepth &&
                    InferenceTask.METRIC_DEPTH in backend.manifest.tasks) {
                    temporal = TemporalDepthInferenceBackend(ownedBackend,
                        options.temporalDepthOptions)
                    ownedBackend = temporal
                }
                val cadence = InferenceCadenceController(options.cadenceOptions)
                val scheduler = BoundedInferenceScheduler(ownedBackend,
                    options.minimumIntervalNs, options.threadName, cadence)
                closeOwnedBackendOnFailure = false
                var monitor: AndroidInferenceThermalMonitor? = null
                try {
                    if (options.enableAndroidThermalMonitor && context != null)
                        monitor = AndroidInferenceThermalMonitor(context, scheduler)
                    return ArKernelPerceptionPipeline(scheduler, cadence, tracking,
                        temporal, monitor)
                } catch (error: Exception) {
                    scheduler.close()
                    throw error
                }
            } catch (error: Exception) {
                if (closeOwnedBackendOnFailure) {
                    try {
                        ownedBackend.close()
                    } catch (closeError: Exception) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }
}
