package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TensorRuntimeConformanceTest {
    @Test
    fun selectsDelegateWithinCombinedAbsoluteAndRelativeTolerance() {
        val cpuClosed = AtomicInteger()
        val delegateClosed = AtomicInteger()
        val cpu = floatRuntime(cpuClosed) { input ->
            floatArrayOf(input[0].toFloat(), input[1].toFloat() * 2f)
        }
        val delegate = floatRuntime(delegateClosed) { input ->
            floatArrayOf(input[0] + 0.00001f, input[1] * 2.0001f)
        }

        val selection = TensorRuntimeConformanceSelector.select(cpu, delegate, cases(),
            TensorConformanceOptions(8, TensorConformanceEncoding.FLOAT32,
                absoluteTolerance = 0.001, relativeTolerance = 0.001))

        assertTrue(selection.usingDelegate)
        assertTrue(selection.report.delegateAccepted)
        assertEquals(2, selection.report.evaluatedCases)
        assertEquals(4L, selection.report.comparedElements)
        assertEquals(1, cpuClosed.get())
        assertEquals(0, delegateClosed.get())
        selection.runtime.close()
        assertEquals(1, delegateClosed.get())
    }

    @Test
    fun valueMismatchClosesDelegateAndFallsBackToCpu() {
        val cpuClosed = AtomicInteger()
        val delegateClosed = AtomicInteger()
        val cpu = floatRuntime(cpuClosed) { floatArrayOf(1f, 2f) }
        val delegate = floatRuntime(delegateClosed) { floatArrayOf(1f, 20f) }

        val selection = TensorRuntimeConformanceSelector.select(cpu, delegate, cases(),
            TensorConformanceOptions(8, TensorConformanceEncoding.FLOAT32))

        assertFalse(selection.usingDelegate)
        assertEquals(TensorConformanceFailure.VALUE_MISMATCH, selection.report.failure)
        assertEquals("dark", selection.report.failedCaseId)
        assertEquals(1, delegateClosed.get())
        assertEquals(0, cpuClosed.get())
        selection.runtime.close()
        assertEquals(1, cpuClosed.get())
    }

    @Test
    fun malformedOrNonFiniteDelegateOutputFallsBackDeterministically() {
        val short = TensorRuntimeConformanceSelector.select(
            floatRuntime(AtomicInteger()) { floatArrayOf(1f, 2f) },
            object : SingleInputTensorRuntime {
                override fun execute(input: ByteBuffer, output: ByteBuffer): Int = 4
            }, cases(), TensorConformanceOptions(8, TensorConformanceEncoding.FLOAT32))
        assertEquals(TensorConformanceFailure.OUTPUT_SIZE_MISMATCH, short.report.failure)
        short.runtime.close()

        val nonFinite = TensorRuntimeConformanceSelector.select(
            floatRuntime(AtomicInteger()) { floatArrayOf(1f, 2f) },
            floatRuntime(AtomicInteger()) { floatArrayOf(Float.NaN, 2f) },
            cases(), TensorConformanceOptions(8, TensorConformanceEncoding.FLOAT32))
        assertEquals(TensorConformanceFailure.NON_FINITE_OUTPUT, nonFinite.report.failure)
        nonFinite.runtime.close()
    }

    @Test
    fun integerEncodingUsesExactOrExplicitIntegerTolerance() {
        val cpu = byteRuntime(byteArrayOf(10, 20))
        val delegate = byteRuntime(byteArrayOf(11, 19))
        val accepted = TensorRuntimeConformanceSelector.select(cpu, delegate, cases(),
            TensorConformanceOptions(2, TensorConformanceEncoding.UINT8,
                integerTolerance = 1))
        assertTrue(accepted.usingDelegate)
        accepted.runtime.close()

        val rejected = TensorRuntimeConformanceSelector.select(
            byteRuntime(byteArrayOf(10, 20)), byteRuntime(byteArrayOf(12, 20)), cases(),
            TensorConformanceOptions(2, TensorConformanceEncoding.UINT8,
                integerTolerance = 1))
        assertFalse(rejected.usingDelegate)
        rejected.runtime.close()
    }

    @Test
    fun invalidCpuReferenceClosesBothAndNeverSelectsDelegate() {
        val cpuClosed = AtomicInteger()
        val delegateClosed = AtomicInteger()
        val cpu = object : SingleInputTensorRuntime {
            override fun execute(input: ByteBuffer, output: ByteBuffer): Int = error("CPU failed")
            override fun close() { cpuClosed.incrementAndGet() }
        }
        val delegate = floatRuntime(delegateClosed) { floatArrayOf(1f, 2f) }

        assertThrows(IllegalStateException::class.java) {
            TensorRuntimeConformanceSelector.select(cpu, delegate, cases(),
                TensorConformanceOptions(8, TensorConformanceEncoding.FLOAT32))
        }
        assertEquals(1, cpuClosed.get())
        assertEquals(1, delegateClosed.get())
    }

    private fun cases() = listOf(
        TensorGoldenCase("dark", byteArrayOf(1, 2, 3, 4)),
        TensorGoldenCase("bright", byteArrayOf(4, 3, 2, 1)),
    )

    private fun floatRuntime(closed: AtomicInteger,
        values: (ByteArray) -> FloatArray): SingleInputTensorRuntime =
        object : SingleInputTensorRuntime {
            override fun execute(input: ByteBuffer, output: ByteBuffer): Int {
                val bytes = ByteArray(input.remaining()).also(input::get)
                val result = values(bytes)
                output.order(ByteOrder.nativeOrder())
                result.forEach { value -> output.putFloat(value) }
                return result.size * 4
            }
            override fun close() { closed.incrementAndGet() }
        }

    private fun byteRuntime(values: ByteArray): SingleInputTensorRuntime =
        object : SingleInputTensorRuntime {
            override fun execute(input: ByteBuffer, output: ByteBuffer): Int {
                output.put(values)
                return values.size
            }
        }
}
