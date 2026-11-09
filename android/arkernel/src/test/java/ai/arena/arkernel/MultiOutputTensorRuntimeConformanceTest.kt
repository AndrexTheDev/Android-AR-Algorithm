package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiOutputTensorRuntimeConformanceTest {
    @Test
    fun acceptsOnlyWhenEveryOutputHeadConforms() {
        val cpuClosed = AtomicInteger()
        val delegateClosed = AtomicInteger()
        val cpu = runtime(cpuClosed) { listOf(floatArrayOf(1f, 2f), floatArrayOf(0.5f)) }
        val delegate = runtime(delegateClosed) {
            listOf(floatArrayOf(1.00001f, 2.00001f), floatArrayOf(0.50001f))
        }

        val selection = MultiOutputTensorRuntimeConformanceSelector.select(cpu, delegate,
            cases(), options())

        assertTrue(selection.usingDelegate)
        assertTrue(selection.report.delegateAccepted)
        assertEquals(2, selection.report.outputs.size)
        assertEquals(4L, selection.report.outputs[0].comparedElements)
        assertEquals(2L, selection.report.outputs[1].comparedElements)
        assertEquals(1, cpuClosed.get())
        assertEquals(0, delegateClosed.get())
        selection.runtime.close()
        assertEquals(1, delegateClosed.get())
    }

    @Test
    fun divergenceInSecondHeadRejectsWholeDelegate() {
        val cpuClosed = AtomicInteger()
        val delegateClosed = AtomicInteger()
        val cpu = runtime(cpuClosed) { listOf(floatArrayOf(1f, 2f), floatArrayOf(0.5f)) }
        val delegate = runtime(delegateClosed) {
            listOf(floatArrayOf(1f, 2f), floatArrayOf(9f))
        }

        val selection = MultiOutputTensorRuntimeConformanceSelector.select(cpu, delegate,
            cases(), options())

        assertFalse(selection.usingDelegate)
        assertEquals(TensorConformanceFailure.VALUE_MISMATCH, selection.report.failure)
        assertEquals(1, selection.report.failedOutputIndex)
        assertEquals("scene-a", selection.report.failedCaseId)
        assertEquals(1, delegateClosed.get())
        assertEquals(0, cpuClosed.get())
        selection.runtime.close()
    }

    @Test
    fun wrongHeadCountOrHeadSizeFallsBackToCpu() {
        val wrongCount = MultiOutputTensorRuntimeConformanceSelector.select(
            runtime(AtomicInteger()) { listOf(floatArrayOf(1f, 2f), floatArrayOf(0.5f)) },
            object : MultiOutputTensorRuntime {
                override fun execute(input: ByteBuffer,
                    outputs: List<ByteBuffer>): IntArray = intArrayOf(8)
            }, cases(), options())
        assertEquals(TensorConformanceFailure.OUTPUT_COUNT_MISMATCH,
            wrongCount.report.failure)
        wrongCount.runtime.close()

        val wrongSize = MultiOutputTensorRuntimeConformanceSelector.select(
            runtime(AtomicInteger()) { listOf(floatArrayOf(1f, 2f), floatArrayOf(0.5f)) },
            object : MultiOutputTensorRuntime {
                override fun execute(input: ByteBuffer,
                    outputs: List<ByteBuffer>): IntArray = intArrayOf(8, 0)
            }, cases(), options())
        assertEquals(TensorConformanceFailure.OUTPUT_SIZE_MISMATCH,
            wrongSize.report.failure)
        assertEquals(1, wrongSize.report.failedOutputIndex)
        wrongSize.runtime.close()
    }

    @Test
    fun nonFiniteCpuHeadInvalidatesBothRuntimes() {
        val cpuClosed = AtomicInteger()
        val delegateClosed = AtomicInteger()
        val cpu = runtime(cpuClosed) {
            listOf(floatArrayOf(1f, 2f), floatArrayOf(Float.NaN))
        }
        val delegate = runtime(delegateClosed) {
            listOf(floatArrayOf(1f, 2f), floatArrayOf(0.5f))
        }

        assertThrows(IllegalStateException::class.java) {
            MultiOutputTensorRuntimeConformanceSelector.select(cpu, delegate,
                cases(), options())
        }
        assertEquals(1, cpuClosed.get())
        assertEquals(1, delegateClosed.get())
    }

    private fun options() = MultiOutputTensorConformanceOptions(listOf(
        TensorConformanceOptions(8, TensorConformanceEncoding.FLOAT32,
            absoluteTolerance = 0.001, relativeTolerance = 0.001),
        TensorConformanceOptions(4, TensorConformanceEncoding.FLOAT32,
            absoluteTolerance = 0.001, relativeTolerance = 0.001),
    ))

    private fun cases() = listOf(
        TensorGoldenCase("scene-a", byteArrayOf(1, 2, 3, 4)),
        TensorGoldenCase("scene-b", byteArrayOf(4, 3, 2, 1)),
    )

    private fun runtime(closed: AtomicInteger,
        values: () -> List<FloatArray>): MultiOutputTensorRuntime =
        object : MultiOutputTensorRuntime {
            override fun execute(input: ByteBuffer,
                outputs: List<ByteBuffer>): IntArray {
                val valuesByOutput = values()
                valuesByOutput.forEachIndexed { index, values ->
                    outputs[index].order(ByteOrder.nativeOrder())
                    values.forEach { outputs[index].putFloat(it) }
                }
                return IntArray(valuesByOutput.size) { valuesByOutput[it].size * 4 }
            }
            override fun close() { closed.incrementAndGet() }
        }
}
