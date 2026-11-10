package ai.arena.arkernel

/** Source of a bounded synthetic framework-error code; no Android classes are used here. */
enum class Camera2FrameworkErrorSource {
    STATE_CALLBACK,
    ACCESS_EXCEPTION,
}

/** Stable, UI-neutral classification of recognized framework unavailability. */
enum class Camera2ServiceDeathReason {
    SERVICE_UNAVAILABLE,
    SERVICE_DISABLED,
    SERVICE_DISCONNECTED,
    SERVICE_BUSY,
}

data class Camera2ServiceDeathClassification(
    val source: Camera2FrameworkErrorSource,
    val code: Int,
    val reason: Camera2ServiceDeathReason,
    val lifecycleFailure: Camera2LifecycleFailure,
)

/**
 * Bounded synthetic classification of Camera2 framework-level unavailability.
 *
 * Only known service/permission/busy/disconnect codes become a distinct
 * `SERVICE_DEATH` lifecycle failure. Ordinary device or session errors remain
 * outside this classifier. It does not observe a real Binder death, inject a
 * framework fault or claim physical-device evidence.
 */
object Camera2ServiceDeathInstrumentation {
    const val STATE_ERROR_CAMERA_IN_USE = 1
    const val STATE_ERROR_MAX_CAMERAS_IN_USE = 2
    const val STATE_ERROR_CAMERA_DISABLED = 3
    const val STATE_ERROR_CAMERA_SERVICE = 5

    const val ACCESS_CAMERA_DISABLED = 1
    const val ACCESS_CAMERA_DISCONNECTED = 2
    const val ACCESS_CAMERA_IN_USE = 4
    const val ACCESS_MAX_CAMERAS_IN_USE = 5

    fun classifyStateCallbackError(code: Int): Camera2ServiceDeathClassification? =
        classify(Camera2FrameworkErrorSource.STATE_CALLBACK, code)

    fun classifyAccessException(code: Int): Camera2ServiceDeathClassification? =
        classify(Camera2FrameworkErrorSource.ACCESS_EXCEPTION, code)

    private fun classify(
        source: Camera2FrameworkErrorSource,
        code: Int,
    ): Camera2ServiceDeathClassification? {
        val reason = when (source) {
            Camera2FrameworkErrorSource.STATE_CALLBACK -> when (code) {
                STATE_ERROR_CAMERA_SERVICE -> Camera2ServiceDeathReason.SERVICE_UNAVAILABLE
                STATE_ERROR_CAMERA_DISABLED -> Camera2ServiceDeathReason.SERVICE_DISABLED
                STATE_ERROR_CAMERA_IN_USE,
                STATE_ERROR_MAX_CAMERAS_IN_USE -> Camera2ServiceDeathReason.SERVICE_BUSY
                else -> null
            }
            Camera2FrameworkErrorSource.ACCESS_EXCEPTION -> when (code) {
                ACCESS_CAMERA_DISABLED -> Camera2ServiceDeathReason.SERVICE_DISABLED
                ACCESS_CAMERA_DISCONNECTED -> Camera2ServiceDeathReason.SERVICE_DISCONNECTED
                ACCESS_CAMERA_IN_USE,
                ACCESS_MAX_CAMERAS_IN_USE -> Camera2ServiceDeathReason.SERVICE_BUSY
                else -> null
            }
        } ?: return null
        return Camera2ServiceDeathClassification(
            source, code, reason, Camera2LifecycleFailure.SERVICE_DEATH)
    }
}

data class Camera2ServiceDeathFuzzReport(
    val seed: Long,
    val sequences: Int,
    val events: Int,
    val acceptedServiceDeaths: Int,
    val rejectedStaleCallbacks: Int,
    val restarts: Int,
    val violations: List<String>,
)

/**
 * Deterministic, bounded pseudo-random replay over the pure lifecycle machine.
 *
 * It includes service-death signals, ordinary failures, stale callbacks and
 * restarts, but never touches the Camera2 framework. Identical seeds produce
 * identical reports. Every observed invariant violation is returned rather than
 * thrown so CI can assert the complete bounded run.
 */
object Camera2ServiceDeathFuzzHarness {
    private const val MULTIPLIER = 6364136223846793005L
    private const val INCREMENT = 1442695040888963407L

    fun run(
        seed: Long,
        sequences: Int = 64,
        eventsPerSequence: Int = 8,
    ): Camera2ServiceDeathFuzzReport {
        require(sequences in 1..256) { "Service-death fuzz sequence count is invalid" }
        require(eventsPerSequence in 1..16) { "Service-death fuzz event count is invalid" }
        var state = seed
        fun next(): Long {
            state = state * MULTIPLIER + INCREMENT
            return state
        }
        fun bounded(bound: Int): Int = ((next() ushr 1) % bound.toLong()).toInt()

        var acceptedServiceDeaths = 0
        var rejectedStaleCallbacks = 0
        var restarts = 0
        val violations = ArrayList<String>()
        val events = FuzzEvent.entries
        repeat(sequences) { sequence ->
            val machine = Camera2LifecycleStateMachine()
            var generation = machine.beginStart()
            var staleGeneration = 0L
            var terminal = false
            var failureReports = 0
            repeat(eventsPerSequence) { eventIndex ->
                val event = events[bounded(events.size)]
                when (event) {
                    FuzzEvent.OPENED -> {
                        val transition = machine.deviceOpened(generation)
                        if (terminal && transition.accepted) {
                            violations.add("sequence $sequence event $eventIndex escaped terminal")
                        }
                    }
                    FuzzEvent.CONFIGURED -> {
                        val transition = machine.sessionConfigured(generation)
                        if (terminal && transition.accepted) {
                            violations.add("sequence $sequence event $eventIndex escaped terminal")
                        }
                    }
                    FuzzEvent.SERVICE_DEATH,
                    FuzzEvent.DEVICE_ERROR,
                    FuzzEvent.DISCONNECTED -> {
                        val failure = when (event) {
                            FuzzEvent.SERVICE_DEATH -> Camera2LifecycleFailure.SERVICE_DEATH
                            FuzzEvent.DEVICE_ERROR -> Camera2LifecycleFailure.DEVICE_ERROR
                            else -> Camera2LifecycleFailure.DEVICE_DISCONNECTED
                        }
                        val transition = machine.fail(generation, failure)
                        if (transition.accepted) {
                            if (terminal) {
                                violations.add(
                                    "sequence $sequence event $eventIndex escaped terminal")
                            }
                            if (!transition.shouldReportFailure) {
                                violations.add(
                                    "sequence $sequence event $eventIndex did not report once")
                            }
                            if (event == FuzzEvent.SERVICE_DEATH) acceptedServiceDeaths++
                            terminal = true
                        }
                        if (transition.shouldReportFailure) failureReports++
                        if (failureReports > 1) {
                            violations.add(
                                "sequence $sequence event $eventIndex reported multiple failures")
                        }
                    }
                    FuzzEvent.STOP -> {
                        machine.stop()
                        terminal = true
                    }
                    FuzzEvent.RESTART -> {
                        machine.stop()
                        staleGeneration = generation
                        val previous = generation
                        generation = machine.beginStart()
                        if (generation <= previous) {
                            violations.add(
                                "sequence $sequence event $eventIndex did not advance generation")
                        }
                        restarts++
                        terminal = false
                        failureReports = 0
                    }
                    FuzzEvent.STALE_OPENED,
                    FuzzEvent.STALE_CONFIGURED,
                    FuzzEvent.STALE_SERVICE_DEATH -> {
                        val transition = when (event) {
                            FuzzEvent.STALE_OPENED -> machine.deviceOpened(staleGeneration)
                            FuzzEvent.STALE_CONFIGURED ->
                                machine.sessionConfigured(staleGeneration)
                            else -> machine.fail(staleGeneration,
                                Camera2LifecycleFailure.SERVICE_DEATH)
                        }
                        if (transition.accepted) {
                            violations.add(
                                "sequence $sequence event $eventIndex accepted stale callback")
                        }
                        if (!transition.shouldCloseCallbackResource) {
                            violations.add(
                                "sequence $sequence event $eventIndex retained stale resource")
                        } else {
                            rejectedStaleCallbacks++
                        }
                    }
                }
                if (machine.generation != generation) {
                    violations.add(
                        "sequence $sequence event $eventIndex changed active generation")
                }
            }
        }
        return Camera2ServiceDeathFuzzReport(
            seed, sequences, sequences * eventsPerSequence, acceptedServiceDeaths,
            rejectedStaleCallbacks, restarts, violations)
    }

    private enum class FuzzEvent {
        OPENED,
        CONFIGURED,
        SERVICE_DEATH,
        DEVICE_ERROR,
        DISCONNECTED,
        STOP,
        RESTART,
        STALE_OPENED,
        STALE_CONFIGURED,
        STALE_SERVICE_DEATH,
    }
}
