package ai.arena.arkernel

enum class Camera2LifecycleState {
    STOPPED,
    OPENING,
    CONFIGURING,
    STREAMING,
    FAILED,
}

enum class Camera2LifecycleFailure {
    DEVICE_DISCONNECTED,
    DEVICE_ERROR,
    SESSION_CONFIGURE_FAILED,
    SESSION_START_FAILED,
}

data class Camera2LifecycleTransition(
    val accepted: Boolean,
    val previousState: Camera2LifecycleState,
    val state: Camera2LifecycleState,
    val generation: Long,
    val failure: Camera2LifecycleFailure? = null,
) {
    val shouldCloseCallbackResource: Boolean get() = !accepted
    val shouldReportFailure: Boolean get() = accepted && failure != null
}

/** Pure generation-aware state machine used by Camera2 callbacks and deterministic JVM tests. */
class Camera2LifecycleStateMachine {
    private var currentState = Camera2LifecycleState.STOPPED
    private var currentGeneration = 0L

    val state: Camera2LifecycleState @Synchronized get() = currentState
    val generation: Long @Synchronized get() = currentGeneration

    @Synchronized
    fun beginStart(): Long {
        check(currentState == Camera2LifecycleState.STOPPED) {
            "Camera2 lifecycle must be stopped before start"
        }
        currentGeneration = Math.addExact(currentGeneration, 1L)
        currentState = Camera2LifecycleState.OPENING
        return currentGeneration
    }

    @Synchronized
    fun deviceOpened(generation: Long): Camera2LifecycleTransition =
        advance(generation, Camera2LifecycleState.OPENING,
            Camera2LifecycleState.CONFIGURING)

    @Synchronized
    fun sessionConfigured(generation: Long): Camera2LifecycleTransition =
        advance(generation, Camera2LifecycleState.CONFIGURING,
            Camera2LifecycleState.STREAMING)

    @Synchronized
    fun fail(generation: Long,
        failure: Camera2LifecycleFailure): Camera2LifecycleTransition {
        val previous = currentState
        val validState = when (failure) {
            Camera2LifecycleFailure.DEVICE_DISCONNECTED,
            Camera2LifecycleFailure.DEVICE_ERROR -> currentState == Camera2LifecycleState.OPENING ||
                currentState == Camera2LifecycleState.CONFIGURING ||
                currentState == Camera2LifecycleState.STREAMING
            Camera2LifecycleFailure.SESSION_CONFIGURE_FAILED ->
                currentState == Camera2LifecycleState.CONFIGURING
            Camera2LifecycleFailure.SESSION_START_FAILED ->
                currentState == Camera2LifecycleState.CONFIGURING ||
                    currentState == Camera2LifecycleState.STREAMING
        }
        val active = generation == currentGeneration && validState
        if (active) currentState = Camera2LifecycleState.FAILED
        return Camera2LifecycleTransition(active, previous, currentState,
            currentGeneration, if (active) failure else null)
    }

    @Synchronized
    fun stop(): Camera2LifecycleTransition {
        val previous = currentState
        currentState = Camera2LifecycleState.STOPPED
        return Camera2LifecycleTransition(previous != Camera2LifecycleState.STOPPED,
            previous, currentState, currentGeneration)
    }

    private fun advance(generation: Long, expected: Camera2LifecycleState,
        target: Camera2LifecycleState): Camera2LifecycleTransition {
        val previous = currentState
        val accepted = generation == currentGeneration && currentState == expected
        if (accepted) currentState = target
        return Camera2LifecycleTransition(accepted, previous, currentState, currentGeneration)
    }
}
