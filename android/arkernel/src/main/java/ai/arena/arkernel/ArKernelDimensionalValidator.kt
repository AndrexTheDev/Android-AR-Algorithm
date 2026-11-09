package ai.arena.arkernel

enum class DimensionalReferenceKind(val nativeCode: Long) {
    HELD_OUT_SCALE_BAR(1),
    SURVEYED_DISTANCE(2),
    SENSOR_GROUND_TRUTH(3),
}

enum class DimensionalValidationReason(val flag: Long) {
    INVALID_POLICY(1L shl 0),
    OBSERVATION_BUDGET_EXCEEDED(1L shl 1),
    INVALID_OR_NON_INDEPENDENT_OBSERVATION(1L shl 2),
    INSUFFICIENT_OBSERVATIONS(1L shl 3),
    INSUFFICIENT_DIMENSION_DIVERSITY(1L shl 4),
    MEAN_ABSOLUTE_ERROR_TOO_HIGH(1L shl 5),
    MAXIMUM_ABSOLUTE_ERROR_TOO_HIGH(1L shl 6),
    MAXIMUM_RELATIVE_ERROR_TOO_HIGH(1L shl 7),
}

data class HeldOutDimensionObservation(
    val evidenceId: String,
    val reconstructedPointA: Vector3,
    val reconstructedPointB: Vector3,
    val referenceDistanceMeters: Double,
    val referenceUncertaintyMeters: Double,
    val referenceKind: DimensionalReferenceKind,
    val heldOutFromCalibrationAndOptimization: Boolean = true,
) {
    init {
        require(evidenceId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(listOf(reconstructedPointA.x, reconstructedPointA.y, reconstructedPointA.z,
            reconstructedPointB.x, reconstructedPointB.y, reconstructedPointB.z,
            referenceDistanceMeters, referenceUncertaintyMeters).all { it.isFinite() })
        require(referenceDistanceMeters > 0.0)
        require(referenceUncertaintyMeters >= 0.0 &&
            referenceUncertaintyMeters < referenceDistanceMeters)
    }
}

data class DimensionalValidationPolicy(
    val maximumObservations: Int = 64,
    val minimumObservations: Int = 3,
    val minimumDimensionBins: Int = 2,
    val dimensionBinWidthMeters: Double = 0.25,
    val maximumReferenceUncertaintyMeters: Double = 0.002,
    val maximumMeanAbsoluteErrorMeters: Double = 0.01,
    val maximumAbsoluteErrorMeters: Double = 0.01,
    val maximumRelativeError: Double = 0.02,
) {
    init {
        require(maximumObservations in 1..64)
        require(minimumObservations in 1..maximumObservations)
        require(minimumDimensionBins in 1..minimumObservations)
        require(listOf(dimensionBinWidthMeters, maximumReferenceUncertaintyMeters,
            maximumMeanAbsoluteErrorMeters, maximumAbsoluteErrorMeters,
            maximumRelativeError).all { it.isFinite() })
        require(dimensionBinWidthMeters > 0.0)
        require(maximumReferenceUncertaintyMeters >= 0.0)
        require(maximumMeanAbsoluteErrorMeters > 0.0 && maximumAbsoluteErrorMeters > 0.0)
        require(maximumRelativeError in 0.0..1.0 && maximumRelativeError > 0.0)
    }
}

data class DimensionalValidationReport(
    val passed: Boolean,
    val acceptedObservations: Int,
    val dimensionBins: Int,
    val meanAbsoluteErrorMeters: Double,
    val maximumEffectiveAbsoluteErrorMeters: Double,
    val maximumEffectiveRelativeError: Double,
    val maximumValidatedDimensionMeters: Double,
    val reasons: List<DimensionalValidationReason>,
) {
    init {
        require(acceptedObservations in 0..64 && dimensionBins in 0..64)
        require(listOf(meanAbsoluteErrorMeters, maximumEffectiveAbsoluteErrorMeters,
            maximumEffectiveRelativeError, maximumValidatedDimensionMeters)
            .all { it.isFinite() && it >= 0.0 })
        require(reasons.distinct().size == reasons.size)
        require(passed == reasons.isEmpty())
    }
}

/** Android/JNI entry point for the portable held-out dimensional evaluator. */
object ArKernelDimensionalValidator {
    private const val RESULT_OK = 0
    init { System.loadLibrary("arkernel_jni") }

    fun evaluate(
        observations: List<HeldOutDimensionObservation>,
        policy: DimensionalValidationPolicy = DimensionalValidationPolicy(),
    ): DimensionalValidationReport {
        require(observations.size <= policy.maximumObservations)
        val ids = observations.map { it.evidenceId }.toTypedArray()
        val values = DoubleArray(observations.size * 8)
        val metadata = LongArray(observations.size * 2)
        observations.forEachIndexed { index, observation ->
            val offset = index * 8
            values[offset] = observation.reconstructedPointA.x
            values[offset + 1] = observation.reconstructedPointA.y
            values[offset + 2] = observation.reconstructedPointA.z
            values[offset + 3] = observation.reconstructedPointB.x
            values[offset + 4] = observation.reconstructedPointB.y
            values[offset + 5] = observation.reconstructedPointB.z
            values[offset + 6] = observation.referenceDistanceMeters
            values[offset + 7] = observation.referenceUncertaintyMeters
            metadata[index * 2] = observation.referenceKind.nativeCode
            metadata[index * 2 + 1] =
                if (observation.heldOutFromCalibrationAndOptimization) 1 else 0
        }
        val policyCounts = longArrayOf(policy.maximumObservations.toLong(),
            policy.minimumObservations.toLong(), policy.minimumDimensionBins.toLong())
        val policyValues = doubleArrayOf(policy.dimensionBinWidthMeters,
            policy.maximumReferenceUncertaintyMeters,
            policy.maximumMeanAbsoluteErrorMeters, policy.maximumAbsoluteErrorMeters,
            policy.maximumRelativeError)
        val outputMetadata = LongArray(4)
        val outputValues = DoubleArray(4)
        val status = nativeEvaluate(ids, values, metadata, policyCounts, policyValues,
            outputMetadata, outputValues)
        check(status == RESULT_OK) { "Native dimensional validation failed: $status" }
        val flags = outputMetadata[1]
        val knownFlags = DimensionalValidationReason.entries.fold(0L) { value, reason ->
            value or reason.flag
        }
        check(flags and knownFlags.inv() == 0L) { "Unknown dimensional failure flags" }
        return DimensionalValidationReport(outputMetadata[0] != 0L,
            outputMetadata[2].toInt(), outputMetadata[3].toInt(), outputValues[0],
            outputValues[1], outputValues[2], outputValues[3],
            DimensionalValidationReason.entries.filter { flags and it.flag != 0L })
    }

    private external fun nativeEvaluate(
        evidenceIds: Array<String>,
        observationValues: DoubleArray,
        observationMetadata: LongArray,
        policyCounts: LongArray,
        policyValues: DoubleArray,
        outputMetadata: LongArray,
        outputValues: DoubleArray,
    ): Int
}
