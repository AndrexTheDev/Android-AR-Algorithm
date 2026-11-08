package ai.arena.arkernel

private const val MAX_RESOURCE_LATENCY_SAMPLES = 256

enum class RuntimeResourceTier {
    PERFORMANCE,
    BALANCED,
    CONSERVATIVE,
    CAPTURE_ONLY,
    UNSUPPORTED,
}

enum class RuntimeMemoryPressure { NORMAL, MODERATE, CRITICAL }

enum class RuntimeResourceReason {
    STATIC_COMPATIBILITY_UNSUPPORTED,
    STATIC_COMPATIBILITY_CAPTURE_ONLY,
    ACTIVE_EVIDENCE_MISSING,
    ACTIVE_COMPATIBILITY_FAILED,
    LOW_RAM_DEVICE,
    MEMORY_CLASS_LOW,
    STREAM_EXCEEDS_TIER_PIXEL_BUDGET,
    FRAME_PROCESSING_SLOW,
    CAMERA_RATE_LOW,
    CAMERA_GAPS_HIGH,
    THERMAL_WARM,
    THERMAL_HOT,
    THERMAL_CRITICAL,
    MEMORY_PRESSURE_MODERATE,
    MEMORY_PRESSURE_CRITICAL,
    TELEMETRY_BUDGET_EXCEEDED,
}

data class RuntimeResourceTelemetry(
    val processingSamples: Int,
    val processingP95Ns: Long,
    val memoryPressure: RuntimeMemoryPressure,
    val budgetExceeded: Boolean,
) {
    init {
        require(processingSamples in 0..MAX_RESOURCE_LATENCY_SAMPLES)
        require(processingP95Ns >= 0)
    }
}

class RuntimeResourceTelemetryCollector {
    private val processingNs = ArrayList<Long>()
    private var budgetExceeded = false
    @Volatile private var memoryPressure = RuntimeMemoryPressure.NORMAL

    @Synchronized
    fun addFrameProcessingDuration(durationNs: Long): Boolean {
        if (durationNs <= 0) return false
        if (processingNs.size == MAX_RESOURCE_LATENCY_SAMPLES) {
            budgetExceeded = true
            return false
        }
        processingNs += durationNs
        return true
    }

    @Synchronized fun updateMemoryPressure(pressure: RuntimeMemoryPressure) {
        memoryPressure = pressure
    }

    @Synchronized
    fun snapshot(): RuntimeResourceTelemetry {
        val sorted = processingNs.sorted()
        val p95 = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * 95) / 100]
        return RuntimeResourceTelemetry(sorted.size, p95, memoryPressure, budgetExceeded)
    }
}

data class RuntimeResourceDecision(
    val tier: RuntimeResourceTier,
    val maximumCalibratedStreamPixels: Int,
    val targetFramesPerSecond: Int,
    val maximumQueuedImuSamples: Int,
    val maximumPendingReconstructionFrames: Int,
    val denseDepthEnabled: Boolean,
    val semanticInferenceEnabled: Boolean,
    val liveMappingEnabled: Boolean,
    val reasons: List<RuntimeResourceReason>,
    val calibratedAlternativeTier: RuntimeResourceTier? = null,
) {
    init {
        require(maximumCalibratedStreamPixels >= 0)
        require(targetFramesPerSecond in 0..240)
        require(maximumQueuedImuSamples == 0 || maximumQueuedImuSamples >= 128)
        require(maximumPendingReconstructionFrames in 0..8)
        require(reasons.distinct().size == reasons.size)
        if (tier >= RuntimeResourceTier.CAPTURE_ONLY) {
            require(!denseDepthEnabled && !semanticInferenceEnabled && !liveMappingEnabled)
        }
        require(calibratedAlternativeTier == null ||
            (tier == RuntimeResourceTier.CAPTURE_ONLY &&
                calibratedAlternativeTier < RuntimeResourceTier.CAPTURE_ONLY))
    }
}

/** Capability/measurement policy only; a lower resolution requires a separately calibrated profile. */
object RuntimeResourcePolicy {
    fun evaluate(staticReport: DeviceCompatibilityReport,
        activeReport: ActiveCompatibilityReport?,
        telemetry: RuntimeResourceTelemetry = RuntimeResourceTelemetry(
            0, 0, RuntimeMemoryPressure.NORMAL, false),
        thermalState: InferenceThermalState = InferenceThermalState.NORMAL,
    ): RuntimeResourceDecision {
        val signature = staticReport.signature
        val camera = signature.camera
        if (staticReport.provisionalTier == DeviceCompatibilityTier.UNSUPPORTED ||
            camera == null) return decision(RuntimeResourceTier.UNSUPPORTED,
                listOf(RuntimeResourceReason.STATIC_COMPATIBILITY_UNSUPPORTED))
        val reasons = mutableListOf<RuntimeResourceReason>()
        var tier = RuntimeResourceTier.PERFORMANCE
        if (staticReport.provisionalTier == DeviceCompatibilityTier.CAPTURE_ONLY) {
            reasons += RuntimeResourceReason.STATIC_COMPATIBILITY_CAPTURE_ONLY
            tier = RuntimeResourceTier.CAPTURE_ONLY
        }
        if (activeReport == null) {
            reasons += RuntimeResourceReason.ACTIVE_EVIDENCE_MISSING
            tier = maxTier(tier, RuntimeResourceTier.BALANCED)
        } else {
            if (activeReport.blockingReasons.isNotEmpty()) {
                reasons += RuntimeResourceReason.ACTIVE_COMPATIBILITY_FAILED
                tier = RuntimeResourceTier.CAPTURE_ONLY
            }
            if (activeReport.camera.medianRateHz < 24.0) {
                reasons += RuntimeResourceReason.CAMERA_RATE_LOW
                tier = maxTier(tier, RuntimeResourceTier.CONSERVATIVE)
            }
            if (activeReport.camera.largeGapRatio > 0.05) {
                reasons += RuntimeResourceReason.CAMERA_GAPS_HIGH
                tier = maxTier(tier, RuntimeResourceTier.CONSERVATIVE)
            }
        }
        if (signature.lowRamDevice) {
            reasons += RuntimeResourceReason.LOW_RAM_DEVICE
            tier = maxTier(tier, RuntimeResourceTier.CONSERVATIVE)
        }
        when {
            signature.memoryClassMb < 128 -> {
                reasons += RuntimeResourceReason.MEMORY_CLASS_LOW
                tier = RuntimeResourceTier.CAPTURE_ONLY
            }
            signature.memoryClassMb < 256 -> {
                reasons += RuntimeResourceReason.MEMORY_CLASS_LOW
                tier = maxTier(tier, RuntimeResourceTier.CONSERVATIVE)
            }
            signature.memoryClassMb < 512 -> tier = maxTier(tier, RuntimeResourceTier.BALANCED)
        }
        if (telemetry.processingP95Ns > 80_000_000L) {
            reasons += RuntimeResourceReason.FRAME_PROCESSING_SLOW
            tier = RuntimeResourceTier.CAPTURE_ONLY
        } else if (telemetry.processingP95Ns > 40_000_000L) {
            reasons += RuntimeResourceReason.FRAME_PROCESSING_SLOW
            tier = maxTier(tier, RuntimeResourceTier.CONSERVATIVE)
        } else if (telemetry.processingP95Ns > 25_000_000L) {
            reasons += RuntimeResourceReason.FRAME_PROCESSING_SLOW
            tier = maxTier(tier, RuntimeResourceTier.BALANCED)
        }
        when (telemetry.memoryPressure) {
            RuntimeMemoryPressure.NORMAL -> Unit
            RuntimeMemoryPressure.MODERATE -> {
                reasons += RuntimeResourceReason.MEMORY_PRESSURE_MODERATE
                tier = maxTier(tier, RuntimeResourceTier.CONSERVATIVE)
            }
            RuntimeMemoryPressure.CRITICAL -> {
                reasons += RuntimeResourceReason.MEMORY_PRESSURE_CRITICAL
                tier = RuntimeResourceTier.CAPTURE_ONLY
            }
        }
        when (thermalState) {
            InferenceThermalState.NORMAL -> Unit
            InferenceThermalState.WARM -> {
                reasons += RuntimeResourceReason.THERMAL_WARM
                tier = maxTier(tier, RuntimeResourceTier.BALANCED)
            }
            InferenceThermalState.HOT -> {
                reasons += RuntimeResourceReason.THERMAL_HOT
                tier = maxTier(tier, RuntimeResourceTier.CONSERVATIVE)
            }
            InferenceThermalState.CRITICAL -> {
                reasons += RuntimeResourceReason.THERMAL_CRITICAL
                tier = RuntimeResourceTier.CAPTURE_ONLY
            }
        }
        if (telemetry.budgetExceeded)
            reasons += RuntimeResourceReason.TELEMETRY_BUDGET_EXCEEDED
        val provisional = decision(tier, reasons)
        val cameraPixels = Math.multiplyExact(camera.width, camera.height)
        if (tier < RuntimeResourceTier.CAPTURE_ONLY &&
            cameraPixels > provisional.maximumCalibratedStreamPixels) {
            val alternativeTier = tier
            reasons += RuntimeResourceReason.STREAM_EXCEEDS_TIER_PIXEL_BUDGET
            return decision(RuntimeResourceTier.CAPTURE_ONLY, reasons.distinct(), alternativeTier)
        }
        return decision(tier, reasons.distinct())
    }

    /**
     * Re-admits only a separately verified calibrated stream. A capture decision caused by
     * thermal, memory, or compatibility evidence has no alternative tier and cannot be promoted.
     */
    fun admitCalibratedStream(
        decision: RuntimeResourceDecision,
        calibratedStreamPixels: Int,
    ): RuntimeResourceDecision? {
        require(calibratedStreamPixels > 0)
        val alternative = decision.calibratedAlternativeTier
        val candidate = if (alternative == null) decision else decision(alternative,
            decision.reasons.filterNot {
                it == RuntimeResourceReason.STREAM_EXCEEDS_TIER_PIXEL_BUDGET
            })
        return candidate.takeIf {
            calibratedStreamPixels <= it.maximumCalibratedStreamPixels
        }
    }

    private fun maxTier(first: RuntimeResourceTier, second: RuntimeResourceTier) =
        if (first.ordinal >= second.ordinal) first else second

    private fun decision(
        tier: RuntimeResourceTier,
        reasons: List<RuntimeResourceReason>,
        calibratedAlternativeTier: RuntimeResourceTier? = null,
    ) = when (tier) {
        RuntimeResourceTier.PERFORMANCE -> RuntimeResourceDecision(tier, 1920 * 1080, 30,
            4096, 2, denseDepthEnabled = true, semanticInferenceEnabled = true,
            liveMappingEnabled = true, reasons, calibratedAlternativeTier)
        RuntimeResourceTier.BALANCED -> RuntimeResourceDecision(tier, 1280 * 720, 24,
            3072, 1, denseDepthEnabled = true, semanticInferenceEnabled = true,
            liveMappingEnabled = true, reasons, calibratedAlternativeTier)
        RuntimeResourceTier.CONSERVATIVE -> RuntimeResourceDecision(tier, 640 * 480, 15,
            1024, 1, denseDepthEnabled = false, semanticInferenceEnabled = false,
            liveMappingEnabled = true, reasons, calibratedAlternativeTier)
        RuntimeResourceTier.CAPTURE_ONLY -> RuntimeResourceDecision(tier, 1920 * 1080, 10,
            0, 1, denseDepthEnabled = false, semanticInferenceEnabled = false,
            liveMappingEnabled = false, reasons, calibratedAlternativeTier)
        RuntimeResourceTier.UNSUPPORTED -> RuntimeResourceDecision(tier, 0, 0,
            0, 0, denseDepthEnabled = false, semanticInferenceEnabled = false,
            liveMappingEnabled = false, reasons, calibratedAlternativeTier)
    }
}

/**
 * Planning-only degrade filter. Never apply a changed result to a live owner; use
 * [RuntimeExecutionPlanController] to convert executable changes into a terminal restart.
 */
class RuntimeResourceController(initial: RuntimeResourceDecision) {
    @Volatile private var current = initial
    val decision: RuntimeResourceDecision get() = current

    @Synchronized
    fun update(candidate: RuntimeResourceDecision): RuntimeResourceDecision {
        if (candidate.tier.ordinal >= current.tier.ordinal) current = candidate
        return current
    }
}
