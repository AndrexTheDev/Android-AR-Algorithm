package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceThermalRunPlanTest {
    @Test
    fun planIsDeterministicBoundedAndExplicitlyUnverified() {
        val config = DeviceThermalRunConfig()
        val plan = DeviceThermalRunPlanner.plan(config)
        val second = DeviceThermalRunPlanner.plan(config)

        assertEquals(plan.slots, second.slots)
        assertEquals(1800, config.durationSeconds)
        assertEquals(31, config.slotsPerRun)
        assertEquals(31, plan.totalSamples)
        assertEquals("unverified", plan.qualityGrade)
        assertTrue(plan.slots.all { it.qualityGrade == "unverified" })
        assertEquals(plan.slots.size, plan.slots.map { it.sampleId }.distinct().size)

        assertEquals(1, plan.slots.first().runIndex)
        assertEquals(0, plan.slots.first().offsetSeconds)
        assertEquals(DeviceThermalRunPhase.BASELINE, plan.slots.first().phase)
        assertTrue(plan.slots.first().scanActive)
        assertEquals(config.repeatCount, plan.slots.last().runIndex)
        assertEquals(1800, plan.slots.last().offsetSeconds)
        assertEquals(DeviceThermalRunPhase.TERMINAL_SAMPLE,
            plan.slots.last().phase)
        assertFalse(plan.slots.last().scanActive)
        assertTrue(plan.slots.filter {
            it.offsetSeconds > 0 && it.offsetSeconds < 1800
        }.all {
            it.phase == DeviceThermalRunPhase.SUSTAINED_SCAN && it.scanActive
        })

        val wide = DeviceThermalRunPlanner.plan(
            DeviceThermalRunConfig(sampleIntervalSeconds = 300))
        assertEquals(7, wide.totalSamples)
        assertEquals(7, wide.slots.map { it.sampleId }.distinct().size)
        val repeated = DeviceThermalRunPlanner.plan(
            DeviceThermalRunConfig(repeatCount = 2))
        assertEquals(62, repeated.totalSamples)
        assertEquals(2, repeated.slots.last().runIndex)
        assertEquals("unverified", repeated.qualityGrade)
    }

    @Test
    fun rejectsInvalidPlanBudgetsAndFrameRateGate() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunConfig(repeatCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunConfig(repeatCount = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunConfig(sampleIntervalSeconds = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunConfig(sampleIntervalSeconds = 301)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunConfig(sampleIntervalSeconds = 7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunConfig(minimumFrameRateHz = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunConfig(minimumFrameRateHz = Double.NaN)
        }
    }

    @Test
    fun ledgerAcceptsCompleteOrderedHostSamplesWithoutQualityUpgrade() {
        val plan = DeviceThermalRunPlanner.plan()
        val ledger = DeviceThermalRunLedger(plan)
        for ((index, slot) in plan.slots.withIndex()) {
            val state = if (index % 2 == 0) {
                InferenceThermalState.NORMAL
            } else {
                InferenceThermalState.WARM
            }
            assertTrue(ledger.add(sample(slot, 30.0, state)))
        }
        assertFalse(ledger.isFinished)

        val first = ledger.finish()
        assertTrue(first.accepted)
        assertEquals(DeviceThermalRunRejection.NONE, first.rejection)
        assertEquals(31, first.recordedSamples)
        assertEquals(31, first.expectedSamples)
        assertEquals(1, first.completedRuns)
        assertEquals(30.0, first.minimumFrameRateHz!!, 0.0)
        assertEquals(InferenceThermalState.WARM, first.maximumThermalState)
        assertEquals(0, first.criticalSamples)
        assertEquals("unverified", first.qualityGrade)
        assertTrue(ledger.isFinished)
        assertFalse(ledger.add(sample(plan.slots.first(), 30.0)))
        assertEquals(first, ledger.finish())
    }

    @Test
    fun ledgerRejectsUnexpectedDuplicateAndOutOfOrderSamples() {
        val plan = DeviceThermalRunPlanner.plan()
        val ledger = DeviceThermalRunLedger(plan)
        val first = plan.slots[0]
        val second = plan.slots[1]

        assertFalse(ledger.add(DeviceThermalRunSample(
            first.runIndex, first.offsetSeconds + 1,
            InferenceThermalState.NORMAL, 30.0)))
        assertFalse(ledger.add(DeviceThermalRunSample(
            first.runIndex + 1, first.offsetSeconds,
            InferenceThermalState.NORMAL, 30.0)))
        assertTrue(ledger.add(sample(first, 30.0)))
        assertFalse(ledger.add(sample(first, 30.0)))
        assertTrue(ledger.add(sample(second, 30.0)))
        assertFalse(ledger.add(sample(first, 30.0)))

        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunSample(1, 0, InferenceThermalState.NORMAL, Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunSample(1, 0, InferenceThermalState.NORMAL, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceThermalRunSample(1, 0, InferenceThermalState.NORMAL, 241.0)
        }
    }

    @Test
    fun incompleteCriticalLowFrameRateAndEmptySummariesFailClosed() {
        val plan = DeviceThermalRunPlanner.plan()

        val empty = DeviceThermalRunLedger(plan).finish()
        assertFalse(empty.accepted)
        assertEquals(DeviceThermalRunRejection.NO_SAMPLES, empty.rejection)
        assertEquals(0, empty.recordedSamples)

        val incompleteLedger = DeviceThermalRunLedger(plan)
        plan.slots.take(30).forEach { incompleteLedger.add(sample(it, 30.0)) }
        val incomplete = incompleteLedger.finish()
        assertFalse(incomplete.accepted)
        assertEquals(DeviceThermalRunRejection.INCOMPLETE_RUN,
            incomplete.rejection)
        assertEquals(30, incomplete.recordedSamples)
        assertEquals(31, incomplete.expectedSamples)
        assertEquals(0, incomplete.completedRuns)

        val criticalLedger = DeviceThermalRunLedger(plan)
        plan.slots.forEachIndexed { index, slot ->
            val state = if (index == 5) {
                InferenceThermalState.CRITICAL
            } else {
                InferenceThermalState.NORMAL
            }
            criticalLedger.add(sample(slot, 30.0, state))
        }
        val critical = criticalLedger.finish()
        assertFalse(critical.accepted)
        assertEquals(DeviceThermalRunRejection.CRITICAL_THERMAL_STATE,
            critical.rejection)
        assertEquals(1, critical.criticalSamples)
        assertEquals(InferenceThermalState.CRITICAL, critical.maximumThermalState)

        val lowRateLedger = DeviceThermalRunLedger(plan)
        plan.slots.forEach { lowRateLedger.add(sample(it, 10.0)) }
        val lowRate = lowRateLedger.finish()
        assertFalse(lowRate.accepted)
        assertEquals(DeviceThermalRunRejection.FRAME_RATE_TOO_LOW,
            lowRate.rejection)
        assertEquals(10.0, lowRate.minimumFrameRateHz!!, 0.0)

        assertTrue(listOf(empty, incomplete, critical, lowRate).all {
            it.qualityGrade == "unverified"
        })
    }

    @Test
    fun repeatedRunsRequireEverySlotAndCountCompletedRuns() {
        val config = DeviceThermalRunConfig(repeatCount = 2)
        val plan = DeviceThermalRunPlanner.plan(config)
        val partialLedger = DeviceThermalRunLedger(plan)
        plan.slots.take(31).forEach { partialLedger.add(sample(it, 30.0)) }
        val partial = partialLedger.finish()
        assertFalse(partial.accepted)
        assertEquals(DeviceThermalRunRejection.INCOMPLETE_RUN, partial.rejection)
        assertEquals(1, partial.completedRuns)
        assertEquals(31, partial.recordedSamples)
        assertEquals(62, partial.expectedSamples)

        val fullLedger = DeviceThermalRunLedger(plan)
        plan.slots.forEach { fullLedger.add(sample(it, 30.0)) }
        val full = fullLedger.finish()
        assertTrue(full.accepted)
        assertEquals(2, full.completedRuns)
        assertEquals(62, full.recordedSamples)
        assertEquals("unverified", full.qualityGrade)
    }

    private fun sample(
        slot: DeviceThermalRunSlot,
        frameRateHz: Double,
        state: InferenceThermalState = InferenceThermalState.NORMAL,
    ): DeviceThermalRunSample = DeviceThermalRunSample(
        slot.runIndex, slot.offsetSeconds, state, frameRateHz)
}
