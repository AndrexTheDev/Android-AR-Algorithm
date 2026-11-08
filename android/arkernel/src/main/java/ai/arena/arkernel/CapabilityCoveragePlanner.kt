package ai.arena.arkernel

private const val MAX_COVERAGE_CANDIDATES = 256
private const val MAX_COVERAGE_REQUIREMENTS = 16_384

enum class CapabilityCoverageDimension {
    API_CLASS,
    ABI_CLASS,
    MEMORY_CLASS,
    CAMERA_PRESENCE,
    CAMERA_HARDWARE_LEVEL,
    CAMERA_TIMESTAMP_SOURCE,
    CAMERA_FRAME_DURATION_CLASS,
    LOGICAL_CAMERA_CLASS,
    CAMERA_METADATA_CLASS,
    IMU_CLASS,
}

data class CapabilityCoverageFeature(
    val dimension: CapabilityCoverageDimension,
    val value: String,
) : Comparable<CapabilityCoverageFeature> {
    init { require(value.matches(Regex("[A-Za-z0-9._:+-]{1,128}"))) }
    override fun compareTo(other: CapabilityCoverageFeature): Int =
        compareValuesBy(this, other, { it.dimension.ordinal }, { it.value })
}

data class CapabilityCoverageRequirement(
    val features: List<CapabilityCoverageFeature>,
    val weight: Int,
) : Comparable<CapabilityCoverageRequirement> {
    init {
        require(features.size in 1..2 && features == features.sorted() &&
            features.distinct().size == features.size)
        require(features.map { it.dimension }.distinct().size == features.size)
        require(weight in 1..100)
    }
    override fun compareTo(other: CapabilityCoverageRequirement): Int {
        val size = features.size.compareTo(other.features.size)
        if (size != 0) return size
        for (index in features.indices) {
            val compared = features[index].compareTo(other.features[index])
            if (compared != 0) return compared
        }
        return 0
    }
}

class CapabilityTestCandidate(
    val candidateId: String,
    val signature: DeviceCapabilitySignature,
) {
    init { require(candidateId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}

data class CapabilityCoverageSelection(
    val candidateId: String,
    val newlyCoveredRequirements: Int,
    val newlyCoveredWeight: Int,
)

data class CapabilityCoveragePlan(
    val selections: List<CapabilityCoverageSelection>,
    val coveredRequirements: Int,
    val totalRequirements: Int,
    val coveredWeight: Int,
    val totalWeight: Int,
    val uncoveredRequirements: List<CapabilityCoverageRequirement>,
) {
    val complete: Boolean get() = coveredRequirements == totalRequirements
}

/** Deterministic weighted single/pairwise set cover over observable capability classes. */
object CapabilityCoveragePlanner {
    fun plan(candidates: List<CapabilityTestCandidate>, maximumDevices: Int):
        CapabilityCoveragePlan {
        require(candidates.isNotEmpty() && candidates.size <= MAX_COVERAGE_CANDIDATES)
        require(maximumDevices in 1..minOf(64, candidates.size))
        require(candidates.map { it.candidateId }.distinct().size == candidates.size)
        val classified = candidates.sortedBy { it.candidateId }.associateWith {
            classify(it.signature)
        }
        val requirements = classified.values.flatMap(::requirements).toSortedSet()
        require(requirements.size <= MAX_COVERAGE_REQUIREMENTS)
        val uncovered = requirements.toMutableSet()
        val selections = ArrayList<CapabilityCoverageSelection>()
        val remaining = classified.keys.toMutableSet()
        var coveredWeight = 0
        while (selections.size < maximumDevices && uncovered.isNotEmpty()) {
            val best = remaining.map { candidate ->
                val covered = requirements(classified.getValue(candidate)).filter { it in uncovered }
                CandidateGain(candidate, covered.size, covered.sumOf { it.weight })
            }.filter { it.requirements > 0 }
                .maxWithOrNull(compareBy<CandidateGain> { it.weight }
                    .thenBy { it.requirements }
                    .thenByDescending { it.candidate.candidateId }) ?: break
            val newlyCovered = requirements(classified.getValue(best.candidate))
                .filter { uncovered.remove(it) }
            coveredWeight += newlyCovered.sumOf { it.weight }
            selections += CapabilityCoverageSelection(best.candidate.candidateId,
                newlyCovered.size, newlyCovered.sumOf { it.weight })
            remaining.remove(best.candidate)
        }
        val totalWeight = requirements.sumOf { it.weight }
        return CapabilityCoveragePlan(selections, requirements.size - uncovered.size,
            requirements.size, coveredWeight, totalWeight, uncovered.sorted())
    }

    fun classify(signature: DeviceCapabilitySignature): List<CapabilityCoverageFeature> {
        val camera = signature.camera
        val metadata = if (camera == null) "none" else listOf(
            if (camera.publishesIntrinsics) "i1" else "i0",
            if (camera.publishesDistortion) "d1" else "d0",
            if (camera.publishesRollingShutterSkew) "r1" else "r0",
        ).joinToString("")
        return listOf(
            feature(CapabilityCoverageDimension.API_CLASS, when (signature.apiLevel) {
                in 1..27 -> "api-le-27"
                in 28..30 -> "api-28-30"
                in 31..33 -> "api-31-33"
                else -> "api-ge-34"
            }),
            feature(CapabilityCoverageDimension.ABI_CLASS, abiClass(signature.supportedAbis)),
            feature(CapabilityCoverageDimension.MEMORY_CLASS, when {
                signature.lowRamDevice -> "low-ram"
                signature.memoryClassMb <= 192 -> "mem-le-192"
                signature.memoryClassMb <= 384 -> "mem-193-384"
                signature.memoryClassMb <= 768 -> "mem-385-768"
                else -> "mem-gt-768"
            }),
            feature(CapabilityCoverageDimension.CAMERA_PRESENCE,
                if (camera == null) "absent" else "present"),
            feature(CapabilityCoverageDimension.CAMERA_HARDWARE_LEVEL,
                camera?.hardwareLevel?.let { "level-$it" } ?: "none"),
            feature(CapabilityCoverageDimension.CAMERA_TIMESTAMP_SOURCE,
                camera?.timestampSource?.let { "source-$it" } ?: "none"),
            feature(CapabilityCoverageDimension.CAMERA_FRAME_DURATION_CLASS, when {
                camera == null -> "none"
                camera.minimumFrameDurationNs <= 33_333_333L -> "fps-ge-30"
                camera.minimumFrameDurationNs <= 66_666_667L -> "fps-15-29"
                else -> "fps-lt-15"
            }),
            feature(CapabilityCoverageDimension.LOGICAL_CAMERA_CLASS, when {
                camera == null -> "none"
                camera.physicalCameraIds.size > 1 -> "multi-physical"
                else -> "single-physical"
            }),
            feature(CapabilityCoverageDimension.CAMERA_METADATA_CLASS, metadata),
            feature(CapabilityCoverageDimension.IMU_CLASS, when {
                signature.imu.hasAccelerometer && signature.imu.hasGyroscope -> "accel-gyro"
                signature.imu.hasAccelerometer -> "accel-only"
                signature.imu.hasGyroscope -> "gyro-only"
                else -> "none"
            }),
        ).sorted()
    }

    private fun requirements(features: List<CapabilityCoverageFeature>):
        List<CapabilityCoverageRequirement> {
        val result = ArrayList<CapabilityCoverageRequirement>(
            features.size + features.size * (features.size - 1) / 2)
        features.forEach { result += CapabilityCoverageRequirement(listOf(it), weight(listOf(it))) }
        for (first in features.indices) for (second in first + 1 until features.size) {
            val pair = listOf(features[first], features[second])
            result += CapabilityCoverageRequirement(pair, weight(pair))
        }
        return result
    }

    private fun weight(features: List<CapabilityCoverageFeature>): Int {
        val risk = features.count(::isHighRisk)
        return (if (features.size == 1) 1 else 2) + risk * 5
    }

    private fun isHighRisk(feature: CapabilityCoverageFeature): Boolean =
        feature.value in setOf("low-ram", "absent", "none", "fps-lt-15", "accel-only",
            "gyro-only") || feature.value == "level-2" || feature.value == "source-0" ||
            feature.value.contains("i0") || feature.value.contains("d0") ||
            feature.value.contains("r0")

    private fun abiClass(abis: List<String>): String = when {
        "arm64-v8a" in abis -> "arm64"
        "armeabi-v7a" in abis -> "arm32"
        abis.any { it.startsWith("x86") } -> "x86"
        else -> "other"
    }

    private fun feature(dimension: CapabilityCoverageDimension, value: String) =
        CapabilityCoverageFeature(dimension, value)

    private data class CandidateGain(
        val candidate: CapabilityTestCandidate,
        val requirements: Int,
        val weight: Int,
    )
}
