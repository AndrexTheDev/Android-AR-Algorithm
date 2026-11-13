package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the layperson's wizard.
 *
 * The failure this exists to prevent is not a crash. It is a wizard that
 * announces a step is finished when no file exists, that sends the operator to
 * a button whose prerequisites are missing, or that offers an acceptance test
 * nobody can actually carry out. All three are silent, and all three waste a
 * session of measurements.
 *
 * The inventory is the interesting part: progress is computed from the device's
 * own records rather than from anything the wizard remembers, so these tests
 * mostly construct inventories and check what the plan concludes from them.
 */
class ArKernelWizardTest {

    companion object {
        private const val DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"

        private const val OPEN_GATE = "thermal_covered"

        private val QUESTIONS: List<ArKernelCampaignQuestion> = listOf(
            ArKernelCampaignQuestion(
                "Q_SCALE_SHORT", ArKernelCampaignSceneKind.INDOOR_SCALE, 2),
            ArKernelCampaignQuestion(
                "Q_SCALE_LONG", ArKernelCampaignSceneKind.OUTDOOR_SCALE, 2),
            ArKernelCampaignQuestion(
                "Q_ORTHOGONALITY", ArKernelCampaignSceneKind.RIGHT_ANGLE, 1),
            ArKernelCampaignQuestion(
                "Q_ORIENTATION_STILLNESS",
                ArKernelCampaignSceneKind.STILLNESS, 1),
            ArKernelCampaignQuestion(
                "Q_THERMAL_DRIFT", ArKernelCampaignSceneKind.THERMAL_REPEAT, 2),
            ArKernelCampaignQuestion(
                "Q_LIGHTING_CHANGE", ArKernelCampaignSceneKind.DAY_NIGHT, 2),
            ArKernelCampaignQuestion(
                "Q_HELD_OUT_DIMENSION", ArKernelCampaignSceneKind.HELD_OUT, 1),
            ArKernelCampaignQuestion(
                "Q_REPEATABILITY", ArKernelCampaignSceneKind.INDOOR_SCALE, 3),
            ArKernelCampaignQuestion(
                "Q_CROSS_SESSION", ArKernelCampaignSceneKind.CROSS_SESSION, 1),
            ArKernelCampaignQuestion(
                "Q_CROSS_DEVICE", ArKernelCampaignSceneKind.CROSS_DEVICE, 1),
        )

        private fun file(name: String): ArKernelArtifactFile =
            ArKernelArtifactFile(
                name = name,
                sizeBytes = 1L,
                modifiedMs = 0L,
                sha256Hex = DIGEST)

        private fun reading(
            token: String,
            accepted: Boolean,
            openVerdicts: List<String> = emptyList(),
        ): ArKernelArtifactReading {
            val contract = ArKernelArtifactContract.ofToken(token)
            assertNotNull("no contract for $token", contract)
            return ArKernelArtifactReading(
                file = file("${token}_run-1.txt"),
                kind = ArKernelArtifactKind.CONTRACT,
                contract = contract,
                accepted = accepted,
                rejection = if (accepted) "OK" else "REJECTED",
                detail = "",
                openVerdicts = openVerdicts,
            )
        }

        /** An inventory in which the named records exist in the named state. */
        private fun inventoryWith(
            collected: Set<String> = emptySet(),
            rejected: Set<String> = emptySet(),
            gated: Set<String> = emptySet(),
        ): ArKernelArtifactInventory {
            val readings = ArrayList<ArKernelArtifactReading>()
            for (token in collected) readings += reading(token, accepted = true)
            for (token in rejected) readings += reading(token, accepted = false)
            for (token in gated) {
                readings += reading(
                    token, accepted = true, openVerdicts = listOf(OPEN_GATE))
            }
            return ArKernelArtifactCatalogue.inventory(readings)
        }

        private fun allRecords(): Set<String> =
            ArKernelCampaignStep.values().map { it.record }.toSet()

        private fun planOf(inventory: ArKernelArtifactInventory?): ArKernelWizardPlan =
            ArKernelWizard.plan(QUESTIONS, inventory)

        private fun itemFor(
            plan: ArKernelWizardPlan,
            step: ArKernelCampaignStep,
        ): ArKernelWizardItem =
            plan.items.first { it.step == step }
    }

    // ------------------------------------------------------------- guidance

    @Test
    fun everyStepTheRouteCanProduceHasWrittenGuidance() {
        for (step in ArKernelCampaignStep.values()) {
            val entry = ArKernelWizard.entryFor(step)
            assertEquals(step, entry.step)
            assertTrue("${step.record} has an empty title", entry.title.isNotBlank())
            assertTrue("${step.record} has no action", entry.action.isNotBlank())
            assertTrue("${step.record} has no reason", entry.whyHere.isNotBlank())
            assertFalse(
                "${step.record} has no preparation",
                entry.prepare.isEmpty(),
            )
            assertFalse(
                "${step.record} names no failure mode",
                entry.mistakes.isEmpty(),
            )
            assertTrue("${step.record} has no title for its duration", entry.minutes > 0)
        }
    }

    @Test
    fun everyAcceptanceTestNamesTheFileTheOperatorCanLookFor() {
        // An acceptance test the operator cannot carry out is not one. The
        // only thing they can check without interpreting the record is that a
        // file with the right token exists, so the token has to be in there.
        for (step in ArKernelCampaignStep.values()) {
            val entry = ArKernelWizard.entryFor(step)
            assertTrue(
                "${step.record}: the operator cannot check \"${entry.accept}\"",
                entry.accept.contains(step.record),
            )
        }
    }

    @Test
    fun noGuidanceIsAPlaceholder() {
        for (step in ArKernelCampaignStep.values()) {
            val entry = ArKernelWizard.entryFor(step)
            val all = entry.prepare + entry.mistakes +
                listOf(entry.title, entry.action, entry.accept, entry.whyHere)
            for (text in all) {
                assertFalse(
                    "${step.record} still has a placeholder: $text",
                    text.contains("TODO") || text.contains("XXX") ||
                        text.contains("?"),
                )
            }
        }
    }

    // ---------------------------------------------------------------- order

    @Test
    fun thePlanFollowsTheRunOrderExactly() {
        val expected = ArKernelCampaignRoute.runOrder(QUESTIONS)
        val plan = planOf(inventoryWith())
        assertEquals(expected, plan.items.map { it.step })
        assertEquals(expected.size, plan.total)
    }

    @Test
    fun thePlanNeverContainsTheSameStepTwice() {
        val steps = planOf(inventoryWith()).items.map { it.step }
        assertEquals("a step would be measured twice", steps.size, steps.toSet().size)
    }

    @Test
    fun thePrerequisitesComeFirstAndInTheirPhysicalOrder() {
        val first = planOf(inventoryWith()).items.take(5).map { it.step }
        assertEquals(
            listOf(
                ArKernelCampaignStep.DEVICE_RUNTIME,
                ArKernelCampaignStep.IMU_SERIES,
                ArKernelCampaignStep.CAMERA_TIMING,
                ArKernelCampaignStep.CAMERA_IMU_OFFSET,
                ArKernelCampaignStep.CAMERA_IMU_ROTATION,
            ),
            first,
        )
    }

    // ---------------------------------------------------------------- state

    @Test
    fun anEmptyDeviceLeavesTheFirstStepReadyAndTheRestBlocked() {
        val plan = planOf(inventoryWith())
        assertEquals(ArKernelWizardStage.READY, plan.items.first().stage)
        for (item in plan.items.drop(1)) {
            assertEquals("${item.record} should wait", ArKernelWizardStage.BLOCKED, item.stage)
        }
        assertEquals(plan.items.first(), plan.next)
    }

    @Test
    fun atMostOneStepCanEverBeWorkedOn() {
        // The point of a wizard: there is never a choice to make wrongly.
        for (inventory in listOf(
            inventoryWith(),
            inventoryWith(collected = setOf("ARKDEVHW1")),
            inventoryWith(rejected = setOf("ARKIMUCAL1")),
            inventoryWith(gated = setOf("ARKCAMT1")),
            inventoryWith(collected = allRecords()),
        )) {
            val workable = planOf(inventory).items.filter {
                it.stage == ArKernelWizardStage.READY ||
                    it.stage == ArKernelWizardStage.NEEDS_REPEAT
            }
            assertTrue(
                "$workable are simultaneously workable",
                workable.size <= 1,
            )
        }
    }

    @Test
    fun aCompleteRecordMarksItsStepDoneAndUnblocksTheNext() {
        val plan = planOf(inventoryWith(collected = setOf("ARKDEVHW1")))
        val device = itemFor(plan, ArKernelCampaignStep.DEVICE_RUNTIME)
        val imu = itemFor(plan, ArKernelCampaignStep.IMU_SERIES)
        assertEquals(ArKernelWizardStage.DONE, device.stage)
        assertEquals(ArKernelWizardStage.READY, imu.stage)
        assertEquals(imu, plan.next)
    }

    @Test
    fun aRejectedRecordHoldsEverythingBehindIt() {
        // The file exists, so a checkbox-based wizard would tick it and move
        // on. This one cannot: the record does not parse, so the step it
        // establishes has not been established.
        val plan = planOf(inventoryWith(rejected = setOf("ARKDEVHW1")))
        val device = itemFor(plan, ArKernelCampaignStep.DEVICE_RUNTIME)
        assertEquals(ArKernelWizardStage.NEEDS_REPEAT, device.stage)
        assertEquals(1, device.fileCount)
        assertEquals(device, plan.next)
        assertEquals(
            "a later step must wait while an earlier one is broken",
            ArKernelWizardStage.BLOCKED,
            itemFor(plan, ArKernelCampaignStep.IMU_SERIES).stage,
        )
    }

    @Test
    fun anOpenGateIsRepeatedByItsOwnName() {
        val plan = planOf(inventoryWith(gated = setOf("ARKDEVHW1")))
        val device = itemFor(plan, ArKernelCampaignStep.DEVICE_RUNTIME)
        assertEquals(ArKernelWizardStage.NEEDS_REPEAT, device.stage)
        assertEquals(listOf(OPEN_GATE), device.openVerdicts)
        assertTrue(
            "the operator is told which gate is open: ${device.note}",
            device.note.contains(OPEN_GATE),
        )
    }

    @Test
    fun anUnreadableInventoryLeavesEveryStepOutstanding() {
        // Nothing is known, so nothing is claimed to be finished.
        val plan = ArKernelWizard.plan(QUESTIONS, null)
        assertEquals(plan.total, plan.outstanding.size)
        assertEquals(0, plan.doneCount)
        assertFalse(plan.complete)
        assertNull("no step may be called done", plan.items.firstOrNull {
            it.stage == ArKernelWizardStage.DONE
        })
    }

    // ------------------------------------------------------------- progress

    @Test
    fun progressCountsOnlyStepsWhoseRecordIsComplete() {
        val plan = planOf(
            inventoryWith(collected = setOf("ARKDEVHW1", "ARKIMUCAL1")))
        assertEquals(2, plan.doneCount)
        assertEquals(plan.total, plan.total)
        assertTrue(plan.progressPermille > 0)
        assertTrue(plan.progressPermille < 1000)
    }

    @Test
    fun aRejectedRecordNeverCountsTowardsProgress() {
        val plan = planOf(inventoryWith(rejected = setOf("ARKDEVHW1")))
        assertEquals(0, plan.doneCount)
        assertEquals(0, plan.progressPermille)
        assertFalse(plan.complete)
    }

    @Test
    fun progressReachesAThousandOnlyWhenEveryStepIsComplete() {
        val plan = planOf(inventoryWith(collected = allRecords()))
        assertEquals(1000, plan.progressPermille)
        assertTrue(plan.complete)
        assertNull("nothing left to do", plan.next)
        assertEquals(0, plan.remainingMinutes)
    }

    @Test
    fun minutesShrinkAsStepsAreCompleted() {
        val empty = planOf(inventoryWith())
        val half = planOf(inventoryWith(collected = allRecords().take(8).toSet()))
        assertTrue(empty.totalMinutes > 0)
        assertEquals(empty.totalMinutes, empty.remainingMinutes)
        assertTrue(
            "remaining ${half.remainingMinutes} of ${half.totalMinutes}",
            half.remainingMinutes < half.totalMinutes,
        )
        assertEquals(half.totalMinutes, empty.totalMinutes)
    }

    // ----------------------------------------------------------------- text

    @Test
    fun theSummaryNamesTheButtonToPressNow() {
        val plan = planOf(inventoryWith())
        val summary = plan.summary()
        assertTrue(
            "the operator is told which button: $summary",
            summary.contains("Knopf ${plan.next!!.button}"),
        )
        assertTrue(summary.contains("von ${plan.total}"))
    }

    @Test
    fun aFinishedRunSaysSoRatherThanNamingANextButton() {
        val summary = planOf(inventoryWith(collected = allRecords())).summary()
        assertTrue(summary.startsWith("Fertig"))
        assertFalse(summary.contains("Knopf"))
    }

    @Test
    fun propsAreOnlyAskedForWhileTheyAreStillNeeded() {
        val empty = planOf(inventoryWith())
        assertTrue(
            "the tape is needed for the ladder and the validation",
            empty.requiredProps.contains(ArKernelWizardProp.TAPE),
        )
        // Nothing optional may ever block the run.
        for (prop in empty.requiredProps) {
            assertFalse("$prop is optional and must not block", prop.optional)
            assertFalse(prop == ArKernelWizardProp.NOTHING)
        }
        val done = planOf(inventoryWith(collected = allRecords()))
        assertTrue(
            "a finished run asks for nothing",
            done.requiredProps.isEmpty(),
        )
    }

    @Test
    fun theNoteForADoneStepCountsItsFiles() {
        val plan = planOf(inventoryWith(collected = setOf("ARKDEVHW1")))
        val device = itemFor(plan, ArKernelCampaignStep.DEVICE_RUNTIME)
        assertTrue(device.note.contains("ARKDEVHW1"))
        assertEquals(1, device.fileCount)
    }
}
