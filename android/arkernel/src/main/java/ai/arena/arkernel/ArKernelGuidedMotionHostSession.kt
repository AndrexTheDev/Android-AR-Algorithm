package ai.arena.arkernel

import java.util.Locale

enum class ArKernelGuidedMotionSessionPhase {
    NOT_STARTED,
    RUNNING,
    COMPLETED,
    CANCELLED,
}

/** Explicit host-owned actions; the session never applies them by itself. */
enum class ArKernelGuidedMotionHostAction {
    START,
    FINISH,
    CANCEL,
    RETRY,
}

/** One immutable, UI-neutral view state including text-only accessibility copy. */
data class ArKernelGuidedMotionViewState(
    val runGeneration: Int,
    val phase: ArKernelGuidedMotionSessionPhase,
    val guidance: GuidedMotionGuidanceState,
    val prompt: ArKernelLocalizedPrompt,
    val labels: ArKernelGuidedMotionActionLabels,
    val contentDescription: String,
    val cancelAvailable: Boolean,
    val finishAvailable: Boolean,
    val retryAvailable: Boolean,
) {
    init {
        require(runGeneration >= 0)
        require(contentDescription.isNotBlank())
        require(prompt.locale == labels.locale)
    }

    val terminal: Boolean
        get() = phase == ArKernelGuidedMotionSessionPhase.COMPLETED ||
            phase == ArKernelGuidedMotionSessionPhase.CANCELLED

    /** A terminal or not-yet-started run must not reuse a finished probe. */
    val requiresFreshProbe: Boolean
        get() = phase != ArKernelGuidedMotionSessionPhase.RUNNING

    val qualityGrade: String get() = "unverified"
}

/**
 * Bounded host-owned UX state composition for one guided motion run.
 *
 * It accepts exact guidance transitions from [GuidedMotionTestController],
 * publishes localized view states, emits explicit START/FINISH/CANCEL/RETRY
 * actions and requires a fresh probe after retry. It renders no Android views,
 * does not start or finish a probe, does not close a controller, does not
 * actuate hardware, does not implement platform accessibility semantics and
 * never upgrades the explicit `unverified` quality contract. Full app UX,
 * lifecycle integration and physical rig evidence remain host work.
 */
class ArKernelGuidedMotionHostSession(
    private val locale: Locale = Locale.getDefault(),
    private val onViewStateChanged: (ArKernelGuidedMotionViewState) -> Unit = {},
    private val onHostAction: (ArKernelGuidedMotionHostAction) -> Unit = {},
) : AutoCloseable {
    private var phase = ArKernelGuidedMotionSessionPhase.NOT_STARTED
    private var guidance = GuidedMotionGuidanceState.NOT_STARTED
    private var terminalReport: GuidedMotionAdmissionReport? = null
    private var finishRequested = false
    private var runGeneration = 0
    @Volatile private var closed = false
    private var current: ArKernelGuidedMotionViewState = buildState()

    val isClosed: Boolean get() = closed

    @Synchronized
    fun currentState(): ArKernelGuidedMotionViewState = current

    /** Host must create a fresh probe/controller before calling this. */
    @Synchronized
    fun start(): ArKernelGuidedMotionViewState {
        checkOpen()
        check(phase == ArKernelGuidedMotionSessionPhase.NOT_STARTED) {
            "Guided motion host session already started"
        }
        phase = ArKernelGuidedMotionSessionPhase.RUNNING
        guidance = GuidedMotionGuidanceState.NOT_STARTED
        terminalReport = null
        finishRequested = false
        publishState()
        emit(ArKernelGuidedMotionHostAction.START)
        return current
    }

    /** Exact consecutive controller guidance; COMPLETED must come with a report. */
    @Synchronized
    fun publishGuidance(state: GuidedMotionGuidanceState): ArKernelGuidedMotionViewState {
        checkOpen()
        check(phase == ArKernelGuidedMotionSessionPhase.RUNNING) {
            "Guidance is only accepted while the session is running"
        }
        check(state.ordinal in 1..4 && state.ordinal == guidance.ordinal + 1) {
            "Guided motion guidance must advance exactly one state"
        }
        guidance = state
        publishState()
        return current
    }

    /** Terminal measured report; later guidance or completion is rejected. */
    @Synchronized
    fun publishCompletion(
        report: GuidedMotionAdmissionReport,
    ): ArKernelGuidedMotionViewState {
        checkOpen()
        check(phase == ArKernelGuidedMotionSessionPhase.RUNNING) {
            "Guided motion completion is terminal and once-only"
        }
        phase = ArKernelGuidedMotionSessionPhase.COMPLETED
        guidance = GuidedMotionGuidanceState.COMPLETED
        terminalReport = report
        finishRequested = true
        publishState()
        return current
    }

    /** Requests host-side controller finish; the measured report still decides. */
    @Synchronized
    fun requestFinish(): ArKernelGuidedMotionViewState {
        checkOpen()
        check(phase == ArKernelGuidedMotionSessionPhase.RUNNING && !finishRequested) {
            "Finish is only available once while the session is running"
        }
        finishRequested = true
        publishState()
        emit(ArKernelGuidedMotionHostAction.FINISH)
        return current
    }

    /** Cancels the UX run; the host still closes its own controller. */
    @Synchronized
    fun requestCancel(): ArKernelGuidedMotionViewState {
        checkOpen()
        check(phase == ArKernelGuidedMotionSessionPhase.RUNNING) {
            "Only a running guided motion session can be cancelled"
        }
        phase = ArKernelGuidedMotionSessionPhase.CANCELLED
        terminalReport = null
        finishRequested = true
        publishState()
        emit(ArKernelGuidedMotionHostAction.CANCEL)
        return current
    }

    /** Resets to NOT_STARTED with a new generation; [start] remains explicit. */
    @Synchronized
    fun requestRetry(): ArKernelGuidedMotionViewState {
        checkOpen()
        val canRetry = when (phase) {
            ArKernelGuidedMotionSessionPhase.CANCELLED -> true
            ArKernelGuidedMotionSessionPhase.COMPLETED ->
                requireNotNull(terminalReport).blockingReasons.isNotEmpty()
            else -> false
        }
        check(canRetry) {
            "Retry is only available after cancellation or a blocked report"
        }
        runGeneration++
        phase = ArKernelGuidedMotionSessionPhase.NOT_STARTED
        guidance = GuidedMotionGuidanceState.NOT_STARTED
        terminalReport = null
        finishRequested = false
        publishState()
        emit(ArKernelGuidedMotionHostAction.RETRY)
        return current
    }

    @Synchronized
    override fun close() {
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "Guided motion host session is closed" }
    }

    private fun publishState() {
        current = buildState()
        runCatching { onViewStateChanged(current) }
    }

    private fun emit(action: ArKernelGuidedMotionHostAction) {
        runCatching { onHostAction(action) }
    }

    private fun buildState(): ArKernelGuidedMotionViewState {
        val labels = ArKernelGuidedMotionPrompts.actionLabels(locale)
        val prompt = when (phase) {
            ArKernelGuidedMotionSessionPhase.NOT_STARTED,
            ArKernelGuidedMotionSessionPhase.RUNNING ->
                ArKernelGuidedMotionPrompts.format(guidance, locale)
            ArKernelGuidedMotionSessionPhase.CANCELLED ->
                ArKernelGuidedMotionPrompts.cancelled(locale)
            ArKernelGuidedMotionSessionPhase.COMPLETED ->
                ArKernelGuidedMotionPrompts.format(requireNotNull(terminalReport), locale)
        }
        val cancelAvailable = phase == ArKernelGuidedMotionSessionPhase.RUNNING &&
            !finishRequested
        val finishAvailable = phase == ArKernelGuidedMotionSessionPhase.RUNNING &&
            !finishRequested
        val retryAvailable = when (phase) {
            ArKernelGuidedMotionSessionPhase.CANCELLED -> true
            ArKernelGuidedMotionSessionPhase.COMPLETED ->
                requireNotNull(terminalReport).blockingReasons.isNotEmpty()
            else -> false
        }
        val stateGuidance =
            if (phase == ArKernelGuidedMotionSessionPhase.COMPLETED) {
                GuidedMotionGuidanceState.COMPLETED
            } else {
                guidance
            }
        val description = ArKernelGuidedMotionPrompts.accessibilityDescription(
            prompt, labels, cancelAvailable, finishAvailable, retryAvailable)
        return ArKernelGuidedMotionViewState(
            runGeneration, phase, stateGuidance, prompt, labels, description,
            cancelAvailable, finishAvailable, retryAvailable)
    }
}
