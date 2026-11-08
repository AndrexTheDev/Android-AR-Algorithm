package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

private const val MAX_MULTI_GOLDEN_CASES = 16
private const val MAX_MULTI_GOLDEN_TOTAL_INPUT_BYTES = 32 * 1024 * 1024
private const val MAX_CONFORMANCE_OUTPUTS = 8
private const val MAX_CONFORMANCE_TOTAL_OUTPUT_BYTES = 32 * 1024 * 1024

class MultiOutputTensorConformanceOptions(outputs: List<TensorConformanceOptions>) {
    val outputs: List<TensorConformanceOptions> = outputs.toList()

    init {
        require(outputs.isNotEmpty() && outputs.size <= MAX_CONFORMANCE_OUTPUTS)
        require(outputs.sumOf { it.outputBytes.toLong() } <=
            MAX_CONFORMANCE_TOTAL_OUTPUT_BYTES)
    }
}

data class TensorOutputConformanceSummary(
    val outputIndex: Int,
    val comparedElements: Long,
    val maximumAbsoluteError: Double,
    val maximumRelativeError: Double,
)

data class MultiOutputTensorConformanceReport(
    val delegateAccepted: Boolean,
    val evaluatedCases: Int,
    val outputs: List<TensorOutputConformanceSummary>,
    val failure: TensorConformanceFailure?,
    val failedCaseId: String?,
    val failedOutputIndex: Int?,
) {
    init {
        require(evaluatedCases >= 0)
        require(outputs.map { it.outputIndex }.distinct().size == outputs.size)
        require(outputs.all { it.outputIndex >= 0 && it.comparedElements >= 0 &&
            it.maximumAbsoluteError.isFinite() && it.maximumAbsoluteError >= 0.0 &&
            it.maximumRelativeError.isFinite() && it.maximumRelativeError >= 0.0 })
        require(delegateAccepted == (failure == null && failedCaseId == null &&
            failedOutputIndex == null))
    }
}

data class ConformingMultiOutputTensorRuntimeSelection(
    val runtime: MultiOutputTensorRuntime,
    val usingDelegate: Boolean,
    val report: MultiOutputTensorConformanceReport,
)

/** Bounded all-head conformance gate; one failing output rejects the complete delegate runtime. */
object MultiOutputTensorRuntimeConformanceSelector {
    fun select(
        cpuReference: MultiOutputTensorRuntime,
        delegate: MultiOutputTensorRuntime,
        cases: List<TensorGoldenCase>,
        options: MultiOutputTensorConformanceOptions,
    ): ConformingMultiOutputTensorRuntimeSelection {
        try {
            require(cases.isNotEmpty() && cases.size <= MAX_MULTI_GOLDEN_CASES)
            require(cases.map { it.caseId }.distinct().size == cases.size)
            require(cases.map { it.inputSize }.distinct().size == 1)
            require(cases.sumOf { it.inputSize.toLong() } <=
                MAX_MULTI_GOLDEN_TOTAL_INPUT_BYTES)
        } catch (error: RuntimeException) {
            closeQuietly(delegate); closeQuietly(cpuReference); throw error
        }
        val compared = LongArray(options.outputs.size)
        val maximumAbsolute = DoubleArray(options.outputs.size)
        val maximumRelative = DoubleArray(options.outputs.size)
        try {
            cases.forEachIndexed { caseIndex, golden ->
                val reference = executeReference(cpuReference, golden, options.outputs)
                val candidate = executeDelegate(delegate, golden, options.outputs)
                if (candidate.failure != null) {
                    val report = report(false, caseIndex, compared, maximumAbsolute,
                        maximumRelative, candidate.failure, golden.caseId,
                        candidate.failedOutputIndex)
                    closeQuietly(delegate)
                    return ConformingMultiOutputTensorRuntimeSelection(cpuReference,
                        false, report)
                }
                val candidateOutputs = requireNotNull(candidate.outputs)
                for (outputIndex in options.outputs.indices) {
                    val comparison = TensorRuntimeConformanceSelector.compareForConformance(
                        reference[outputIndex], candidateOutputs[outputIndex],
                        options.outputs[outputIndex])
                    compared[outputIndex] += comparison.elements
                    maximumAbsolute[outputIndex] = max(maximumAbsolute[outputIndex],
                        comparison.maximumAbsoluteError)
                    maximumRelative[outputIndex] = max(maximumRelative[outputIndex],
                        comparison.maximumRelativeError)
                    if (comparison.failure != null) {
                        val report = report(false, caseIndex + 1, compared, maximumAbsolute,
                            maximumRelative, comparison.failure, golden.caseId, outputIndex)
                        closeQuietly(delegate)
                        return ConformingMultiOutputTensorRuntimeSelection(cpuReference,
                            false, report)
                    }
                }
            }
        } catch (error: Throwable) {
            closeQuietly(delegate); closeQuietly(cpuReference)
            if (error is Error) throw error
            throw IllegalStateException("CPU multi-output golden reference execution failed", error)
        }
        closeQuietly(cpuReference)
        return ConformingMultiOutputTensorRuntimeSelection(delegate, true,
            report(true, cases.size, compared, maximumAbsolute, maximumRelative,
                null, null, null))
    }

    private fun executeReference(runtime: MultiOutputTensorRuntime, golden: TensorGoldenCase,
        specs: List<TensorConformanceOptions>): List<ByteArray> {
        val buffers = outputBuffers(specs)
        val written = runtime.execute(golden.newInputBuffer(), buffers)
        check(written.size == specs.size) { "CPU reference returned wrong output count" }
        return specs.indices.map { index ->
            check(written[index] == specs[index].outputBytes) {
                "CPU output $index returned ${written[index]} bytes; expected ${specs[index].outputBytes}"
            }
            outputBytes(buffers[index], written[index]).also { bytes ->
                val validity = TensorRuntimeConformanceSelector.compareForConformance(
                    bytes, bytes, specs[index])
                check(validity.failure == null) { "CPU output $index is non-finite" }
            }
        }
    }

    private fun executeDelegate(runtime: MultiOutputTensorRuntime, golden: TensorGoldenCase,
        specs: List<TensorConformanceOptions>): DelegateExecution = try {
        val buffers = outputBuffers(specs)
        val written = runtime.execute(golden.newInputBuffer(), buffers)
        if (written.size != specs.size) return DelegateExecution(null,
            TensorConformanceFailure.OUTPUT_COUNT_MISMATCH, null)
        for (index in specs.indices)
            if (written[index] != specs[index].outputBytes) return DelegateExecution(null,
                TensorConformanceFailure.OUTPUT_SIZE_MISMATCH, index)
        DelegateExecution(specs.indices.map { outputBytes(buffers[it], written[it]) }, null, null)
    } catch (error: Throwable) {
        if (error is Error) throw error
        DelegateExecution(null, TensorConformanceFailure.DELEGATE_EXECUTION_FAILED, null)
    }

    private fun outputBuffers(specs: List<TensorConformanceOptions>): List<ByteBuffer> =
        specs.map { ByteBuffer.allocateDirect(it.outputBytes).order(ByteOrder.nativeOrder()) }

    private fun outputBytes(output: ByteBuffer, count: Int): ByteArray {
        val source = output.duplicate().order(ByteOrder.nativeOrder())
        source.position(0); source.limit(count)
        return ByteArray(count).also(source::get)
    }

    private fun report(accepted: Boolean, evaluatedCases: Int, compared: LongArray,
        maximumAbsolute: DoubleArray, maximumRelative: DoubleArray,
        failure: TensorConformanceFailure?, caseId: String?, outputIndex: Int?) =
        MultiOutputTensorConformanceReport(accepted, evaluatedCases,
            compared.indices.map { index -> TensorOutputConformanceSummary(index,
                compared[index], maximumAbsolute[index], maximumRelative[index]) },
            failure, caseId, outputIndex)

    private fun closeQuietly(runtime: MultiOutputTensorRuntime) {
        try { runtime.close() } catch (_: Throwable) { }
    }

    private data class DelegateExecution(
        val outputs: List<ByteArray>?,
        val failure: TensorConformanceFailure?,
        val failedOutputIndex: Int?,
    )
}
