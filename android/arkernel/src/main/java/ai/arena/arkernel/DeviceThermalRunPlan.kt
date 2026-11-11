package ai.arena.arkernel

private const val THERMAL_RUN_DURATION_SECONDS = 30 * 60
private const val MAX_THERMAL_RUN_REPEATS = 8
private const val MIN_THERMAL_SAMPLE_INTERVAL_SECONDS = 10
private const val MAX_THERMAL_SAMPLE_INTERVAL_SECONDS = 300
private const val MAX_THERMAL_SAMPLE_SLOTS =
    THERMAL_RUN_DURATION_SECONDS / MIN_THERMAL_SAMPLE_INTERVAL_SECONDS + 1

enum class DeviceThermalRunPhase {
    BASELINE,
    SUSTAINED_SCAN,
    TERMINAL_SAMPLE,
}

private fun thermalRunPhase(offsetSeconds: Int): DeviceThermalRunPhase = when {
    offsetSeconds == 0 -> DeviceThermalRunPhase.BASELINE
    offsetSeconds == THERMAL_RUN_DURATION_SECONDS ->
        DeviceThermalRunPhase.TERMINAL_SAMPLE
    else -> DeviceThermalRunPhase.SUSTAINED_SCAN
}

/** Bounded deterministic schedule; it does not measure temperature or run scans. */
data class DeviceThermalRunConfig(
    val repeatCount: Int = 1,
    val sampleIntervalSeconds: Int = 60,
    val minimumFrameRateHz: Double = 15.0,
) {
    val durationSeconds: Int get() = THERMAL_RUN_DURATION_SECONDS
    val slotsPerRun: Int get() = durationSeconds / sampleIntervalSeconds + 1

    init {
        require(repeatCount in 1..MAX_THERMAL_RUN_REPEATS)
        require(sampleIntervalSeconds in
            MIN_THERMAL_SAMPLE_INTERVAL_SECONDS..MAX_THERMAL_SAMPLE_INTERVAL_SECONDS)
        require(THERMAL_RUN_DURATION_SECONDS % sampleIntervalSeconds == 0)
        require(slotsPerRun in 2..MAX_THERMAL_SAMPLE_SLOTS)
        require(minimumFrameRateHz.isFinite() && minimumFrameRateHz >= 1.0 &&
            minimumFrameRateHz <= 60.0)
    }
}

data class DeviceThermalRunSlot(
    val runIndex: Int,
    val offsetSeconds: Int,
    val sampleId: String,
    val phase: DeviceThermalRunPhase,
    val scanActive: Boolean,
) {
    init {
        require(runIndex in 1..MAX_THERMAL_RUN_REPEATS)
        require(offsetSeconds in 0..THERMAL_RUN_DURATION_SECONDS)
        require(sampleId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(phase == thermalRunPhase(offsetSeconds))
        require(scanActive == (offsetSeconds < THERMAL_RUN_DURATION_SECONDS))
    }

    val qualityGrade: String get() = "unverified"
}

data class DeviceThermalRunPlan(
    val config: DeviceThermalRunConfig,
    val slots: List<DeviceThermalRunSlot>,
) {
    init {
        require(slots.isNotEmpty())
        require(slots.size == config.repeatCount * config.slotsPerRun)
        require(slots.size <= config.repeatCount * MAX_THERMAL_SAMPLE_SLOTS)
        require(slots.map { it.sampleId }.distinct().size == slots.size)
        require(slots.first().runIndex == 1 && slots.first().offsetSeconds == 0)
        require(slots.last().runIndex == config.repeatCount &&
            slots.last().offsetSeconds == THERMAL_RUN_DURATION_SECONDS)
        require(slots.all { it.runIndex in 1..config.repeatCount })
    }

    val totalSamples: Int get() = slots.size
    val qualityGrade: String get() = "unverified"
}

object DeviceThermalRunPlanner {
    fun plan(
        config: DeviceThermalRunConfig = DeviceThermalRunConfig(),
    ): DeviceThermalRunPlan {
        val slots = ArrayList<DeviceThermalRunSlot>(
            config.repeatCount * config.slotsPerRun)
        for (runIndex in 1..config.repeatCount) {
            var offsetSeconds = 0
            while (offsetSeconds <= THERMAL_RUN_DURATION_SECONDS) {
                val padded = offsetSeconds.toString().padStart(4, '0')
                slots += DeviceThermalRunSlot(
                    runIndex = runIndex,
                    offsetSeconds = offsetSeconds,
                    sampleId = "thermal-run-$runIndex-t$padded",
                    phase = thermalRunPhase(offsetSeconds),
                    scanActive = offsetSeconds < THERMAL_RUN_DURATION_SECONDS,
                )
                offsetSeconds += config.sampleIntervalSeconds
            }
        }
        return DeviceThermalRunPlan(config, slots)
    }
}

/** One host-supplied observation; no sensor access, I/O or retention here. */
data class DeviceThermalRunSample(
    val runIndex: Int,
    val offsetSeconds: Int,
    val thermalState: InferenceThermalState,
    val observedFrameRateHz: Double,
) {
    init {
        require(runIndex in 1..MAX_THERMAL_RUN_REPEATS)
        require(offsetSeconds in 0..THERMAL_RUN_DURATION_SECONDS)
        require(observedFrameRateHz.isFinite() && observedFrameRateHz > 0.0 &&
            observedFrameRateHz <= 240.0)
    }

    val qualityGrade: String get() = "unverified"
}

enum class DeviceThermalRunRejection {
    NONE,
    NO_SAMPLES,
    INCOMPLETE_RUN,
    CRITICAL_THERMAL_STATE,
    FRAME_RATE_TOO_LOW,
}

data class DeviceThermalRunSummary(
    val accepted: Boolean,
    val rejection: DeviceThermalRunRejection,
    val recordedSamples: Int,
    val expectedSamples: Int,
    val completedRuns: Int,
    val minimumFrameRateHz: Double?,
    val maximumThermalState: InferenceThermalState?,
    val criticalSamples: Int,
) {
    init {
        require(expectedSamples > 0)
        require(recordedSamples in 0..expectedSamples)
        require(completedRuns >= 0)
        require(criticalSamples in 0..recordedSamples)
        require(accepted == (rejection == DeviceThermalRunRejection.NONE))
    }

    val qualityGrade: String get() = "unverified"
}

/**
 * Fail-closed accounting for host-supplied samples against one exact plan.
 *
 * It accepts only expected, strictly ordered slots, never invents missing
 * observations and reports completeness, critical thermal state and the
 * configured frame-rate gate. It performs no thermal measurement, scan
 * execution, I/O, upload, provider/rig integration or quality upgrade; the
 * summary remains explicitly `unverified`.
 */
class DeviceThermalRunLedger(private val plan: DeviceThermalRunPlan) {
    private val orderByKey: Map<Pair<Int, Int>, Int> =
        plan.slots.mapIndexed { index, slot ->
            (slot.runIndex to slot.offsetSeconds) to index
        }.toMap()
    private val expectedSlotsByRun: Map<Int, List<DeviceThermalRunSlot>> =
        plan.slots.groupBy { it.runIndex }
    private val recorded = LinkedHashMap<Pair<Int, Int>, DeviceThermalRunSample>()
    private var lastIndex = -1
    @Volatile private var completedSummary: DeviceThermalRunSummary? = null

    val isFinished: Boolean get() = completedSummary != null

    @Synchronized
    fun add(sample: DeviceThermalRunSample): Boolean {
        if (completedSummary != null) return false
        val key = sample.runIndex to sample.offsetSeconds
        val index = orderByKey[key] ?: return false
        if (index <= lastIndex || recorded.containsKey(key)) return false
        lastIndex = index
        recorded[key] = sample
        return true
    }

    @Synchronized
    fun finish(): DeviceThermalRunSummary {
        completedSummary?.let { return it }
        val count = recorded.size
        val expected = plan.slots.size
        val minimumFrameRateHz = recorded.values.minOfOrNull {
            it.observedFrameRateHz
        }
        val maximumOrdinal = recorded.values.maxOfOrNull {
            it.thermalState.ordinal
        }
        val maximumThermalState = maximumOrdinal?.let {
            InferenceThermalState.entries[it]
        }
        val criticalSamples = recorded.values.count {
            it.thermalState == InferenceThermalState.CRITICAL
        }
        val completedRuns = expectedSlotsByRun.values.count { runSlots ->
            runSlots.all { slot ->
                recorded.containsKey(slot.runIndex to slot.offsetSeconds)
            }
        }
        val rejection = when {
            count == 0 -> DeviceThermalRunRejection.NO_SAMPLES
            count < expected -> DeviceThermalRunRejection.INCOMPLETE_RUN
            criticalSamples > 0 ->
                DeviceThermalRunRejection.CRITICAL_THERMAL_STATE
            minimumFrameRateHz != null &&
                minimumFrameRateHz < plan.config.minimumFrameRateHz ->
                DeviceThermalRunRejection.FRAME_RATE_TOO_LOW
            else -> DeviceThermalRunRejection.NONE
        }
        return DeviceThermalRunSummary(
            accepted = rejection == DeviceThermalRunRejection.NONE,
            rejection = rejection,
            recordedSamples = count,
            expectedSamples = expected,
            completedRuns = completedRuns,
            minimumFrameRateHz = minimumFrameRateHz,
            maximumThermalState = maximumThermalState,
            criticalSamples = criticalSamples,
        ).also { completedSummary = it }
    }
}
