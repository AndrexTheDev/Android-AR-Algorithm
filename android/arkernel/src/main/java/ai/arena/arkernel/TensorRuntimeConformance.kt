package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

private const val MAX_GOLDEN_CASES = 16
private const val MAX_GOLDEN_CASE_BYTES = 16 * 1024 * 1024
private const val MAX_GOLDEN_TOTAL_BYTES = 32 * 1024 * 1024

enum class TensorConformanceEncoding(val bytesPerElement: Int) {
    FLOAT32(4),
    INT8(1),
    UINT8(1),
}

class TensorGoldenCase(
    val caseId: String,
    inputBytes: ByteArray,
) {
    private val ownedInput = inputBytes.copyOf()
    val inputSize: Int get() = ownedInput.size

    init {
        require(caseId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(inputBytes.size in 1..MAX_GOLDEN_CASE_BYTES)
    }

    internal fun newInputBuffer(): ByteBuffer = ByteBuffer.allocateDirect(ownedInput.size)
        .order(ByteOrder.nativeOrder()).apply { put(ownedInput); flip() }
}

data class TensorConformanceOptions(
    val outputBytes: Int,
    val encoding: TensorConformanceEncoding,
    val absoluteTolerance: Double = 1.0e-4,
    val relativeTolerance: Double = 1.0e-3,
    val integerTolerance: Int = 0,
) {
    init {
        require(outputBytes in 1..MAX_GOLDEN_CASE_BYTES &&
            outputBytes % encoding.bytesPerElement == 0)
        require(absoluteTolerance.isFinite() && absoluteTolerance >= 0.0)
        require(relativeTolerance.isFinite() && relativeTolerance >= 0.0)
        require(integerTolerance in 0..255)
    }
}

enum class TensorConformanceFailure {
    DELEGATE_EXECUTION_FAILED,
    OUTPUT_SIZE_MISMATCH,
    NON_FINITE_OUTPUT,
    VALUE_MISMATCH,
}

data class TensorConformanceReport(
    val delegateAccepted: Boolean,
    val evaluatedCases: Int,
    val comparedElements: Long,
    val maximumAbsoluteError: Double,
    val maximumRelativeError: Double,
    val failure: TensorConformanceFailure?,
    val failedCaseId: String?,
) {
    init {
        require(evaluatedCases >= 0 && comparedElements >= 0)
        require(maximumAbsoluteError.isFinite() && maximumAbsoluteError >= 0.0)
        require(maximumRelativeError.isFinite() && maximumRelativeError >= 0.0)
        require(delegateAccepted == (failure == null && failedCaseId == null))
    }
}

data class ConformingTensorRuntimeSelection(
    val runtime: SingleInputTensorRuntime,
    val usingDelegate: Boolean,
    val report: TensorConformanceReport,
)

/**
 * Runs bounded golden inputs through fresh CPU-reference and delegate runtimes. Ownership of both
 * runtimes is transferred: the loser is closed, and the caller owns the selected runtime.
 */
object TensorRuntimeConformanceSelector {
    fun select(
        cpuReference: SingleInputTensorRuntime,
        delegate: SingleInputTensorRuntime,
        cases: List<TensorGoldenCase>,
        options: TensorConformanceOptions,
    ): ConformingTensorRuntimeSelection {
        try {
            require(cases.isNotEmpty() && cases.size <= MAX_GOLDEN_CASES)
            require(cases.map { it.caseId }.distinct().size == cases.size)
            require(cases.map { it.inputSize }.distinct().size == 1) {
                "Golden inputs for one runtime must have one exact byte shape"
            }
            require(cases.sumOf { it.inputSize.toLong() } <= MAX_GOLDEN_TOTAL_BYTES)
        } catch (error: RuntimeException) {
            closeQuietly(delegate)
            closeQuietly(cpuReference)
            throw error
        }
        var compared = 0L
        var maximumAbsolute = 0.0
        var maximumRelative = 0.0
        try {
            cases.forEachIndexed { index, golden ->
                val reference = executeReference(cpuReference, golden, options)
                val candidateExecution = executeDelegate(delegate, golden, options)
                val candidateFailure = candidateExecution.failure
                if (candidateFailure != null) {
                    val report = failed(index, compared, maximumAbsolute, maximumRelative,
                        candidateFailure, golden.caseId)
                    closeQuietly(delegate)
                    return ConformingTensorRuntimeSelection(cpuReference, false, report)
                }
                val comparison = compare(reference,
                    requireNotNull(candidateExecution.bytes), options)
                compared += comparison.elements
                maximumAbsolute = max(maximumAbsolute, comparison.maximumAbsoluteError)
                maximumRelative = max(maximumRelative, comparison.maximumRelativeError)
                if (comparison.failure != null) {
                    val report = failed(index + 1, compared, maximumAbsolute, maximumRelative,
                        comparison.failure, golden.caseId)
                    closeQuietly(delegate)
                    return ConformingTensorRuntimeSelection(cpuReference, false, report)
                }
            }
        } catch (error: Throwable) {
            closeQuietly(delegate)
            closeQuietly(cpuReference)
            if (error is Error) throw error
            throw IllegalStateException("CPU golden reference execution failed", error)
        }
        closeQuietly(cpuReference)
        return ConformingTensorRuntimeSelection(delegate, true, TensorConformanceReport(
            true, cases.size, compared, maximumAbsolute, maximumRelative, null, null))
    }

    private fun executeReference(runtime: SingleInputTensorRuntime, golden: TensorGoldenCase,
        options: TensorConformanceOptions): ByteArray {
        val output = ByteBuffer.allocateDirect(options.outputBytes).order(ByteOrder.nativeOrder())
        val written = runtime.execute(golden.newInputBuffer(), output)
        check(written == options.outputBytes) {
            "CPU reference returned $written bytes; expected ${options.outputBytes}"
        }
        return outputBytes(output, written).also { bytes ->
            if (options.encoding == TensorConformanceEncoding.FLOAT32) {
                val values = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (index in 0 until values.remaining())
                    check(values.get(index).isFinite()) { "CPU reference returned non-finite output" }
            }
        }
    }

    private fun executeDelegate(runtime: SingleInputTensorRuntime, golden: TensorGoldenCase,
        options: TensorConformanceOptions): DelegateExecution = try {
        val output = ByteBuffer.allocateDirect(options.outputBytes).order(ByteOrder.nativeOrder())
        val written = runtime.execute(golden.newInputBuffer(), output)
        if (written != options.outputBytes) DelegateExecution(null,
            TensorConformanceFailure.OUTPUT_SIZE_MISMATCH)
        else DelegateExecution(outputBytes(output, written), null)
    } catch (error: Throwable) {
        if (error is Error) throw error
        DelegateExecution(null, TensorConformanceFailure.DELEGATE_EXECUTION_FAILED)
    }

    private fun outputBytes(output: ByteBuffer, count: Int): ByteArray {
        val source = output.duplicate().order(ByteOrder.nativeOrder())
        source.position(0); source.limit(count)
        return ByteArray(count).also(source::get)
    }

    private fun compare(reference: ByteArray, candidate: ByteArray,
        options: TensorConformanceOptions): Comparison = when (options.encoding) {
        TensorConformanceEncoding.FLOAT32 -> compareFloat(reference, candidate, options)
        TensorConformanceEncoding.INT8 -> compareInteger(reference, candidate,
            options.integerTolerance, signed = true)
        TensorConformanceEncoding.UINT8 -> compareInteger(reference, candidate,
            options.integerTolerance, signed = false)
    }

    private fun compareFloat(reference: ByteArray, candidate: ByteArray,
        options: TensorConformanceOptions): Comparison {
        val expected = ByteBuffer.wrap(reference).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val actual = ByteBuffer.wrap(candidate).order(ByteOrder.nativeOrder()).asFloatBuffer()
        var maximumAbsolute = 0.0
        var maximumRelative = 0.0
        for (index in 0 until expected.remaining()) {
            val a = expected.get(index).toDouble()
            val b = actual.get(index).toDouble()
            if (!a.isFinite() || !b.isFinite()) return Comparison(index.toLong() + 1,
                maximumAbsolute, maximumRelative, TensorConformanceFailure.NON_FINITE_OUTPUT)
            val absolute = abs(a - b)
            val relative = absolute / max(abs(a), 1.0e-12)
            maximumAbsolute = max(maximumAbsolute, absolute)
            maximumRelative = max(maximumRelative, relative)
            if (absolute > options.absoluteTolerance && relative > options.relativeTolerance)
                return Comparison(index.toLong() + 1, maximumAbsolute, maximumRelative,
                    TensorConformanceFailure.VALUE_MISMATCH)
        }
        return Comparison(expected.remaining().toLong(), maximumAbsolute, maximumRelative, null)
    }

    private fun compareInteger(reference: ByteArray, candidate: ByteArray, tolerance: Int,
        signed: Boolean): Comparison {
        var maximum = 0
        for (index in reference.indices) {
            val a = if (signed) reference[index].toInt() else reference[index].toInt() and 0xff
            val b = if (signed) candidate[index].toInt() else candidate[index].toInt() and 0xff
            val difference = abs(a - b)
            maximum = max(maximum, difference)
            if (difference > tolerance) return Comparison(index.toLong() + 1,
                maximum.toDouble(), 0.0, TensorConformanceFailure.VALUE_MISMATCH)
        }
        return Comparison(reference.size.toLong(), maximum.toDouble(), 0.0, null)
    }

    private fun failed(evaluatedCases: Int, comparedElements: Long, maximumAbsolute: Double,
        maximumRelative: Double, failure: TensorConformanceFailure,
        caseId: String) = TensorConformanceReport(false, evaluatedCases, comparedElements,
        maximumAbsolute, maximumRelative, failure, caseId)

    private fun closeQuietly(runtime: SingleInputTensorRuntime) {
        try { runtime.close() } catch (_: Throwable) { }
    }

    private data class DelegateExecution(
        val bytes: ByteArray?,
        val failure: TensorConformanceFailure?,
    )

    private data class Comparison(
        val elements: Long,
        val maximumAbsoluteError: Double,
        val maximumRelativeError: Double,
        val failure: TensorConformanceFailure?,
    )
}
