package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelIndependentTargetsTest {

    companion object {
        private const val REGISTRY_ID = "targets.test.v1"
        private const val DEVICE_TOKEN = "phone.one"
        private const val CAMPAIGN_ID = "campaign.test.v1"

        /** A gauge block: nominal equals measured, so it is consistent. */
        private fun checkTarget(
            id: String = "gauge.100",
            nominal: Long = 100L,
            measured: Long = 100L,
            tolerance: Long = 1L,
        ): ArKernelIndependentTargetRow =
            ArKernelIndependentTargetRow(
                index = 0,
                targetId = id,
                kind = ArKernelTargetKind.GAUGE_BLOCK,
                nominalMillimetres = nominal,
                measuredMillimetres = measured,
                toleranceMillimetres = tolerance,
                source = ArKernelTargetDimensionSource.CERTIFIED_GAUGE,
                usedForScale = false,
                heldOut = true,
                repeatCount = 3,
            )

        /** The sheet that set the scale: it can never be a check. */
        private fun scaleTarget(
            id: String = "checker.a4",
            nominal: Long = 250L,
            measured: Long = 250L,
            tolerance: Long = 2L,
        ): ArKernelIndependentTargetRow =
            ArKernelIndependentTargetRow(
                index = 0,
                targetId = id,
                kind = ArKernelTargetKind.PRINTED_CHECKERBOARD,
                nominalMillimetres = nominal,
                measuredMillimetres = measured,
                toleranceMillimetres = tolerance,
                source = ArKernelTargetDimensionSource.MANUFACTURER_SPEC,
                usedForScale = true,
                heldOut = false,
                repeatCount = 2,
            )

        private fun registry(
            targets: List<ArKernelIndependentTargetRow>,
            required: Int = 2,
        ): ArKernelIndependentTargetRegistry {
            val result = ArKernelIndependentTargetsAdapter.create(
                registryId = REGISTRY_ID,
                deviceToken = DEVICE_TOKEN,
                campaignId = CAMPAIGN_ID,
                requiredIndependentTargets = required,
                targets = targets,
            )
            check(result.accepted) { result.detail }
            return checkNotNull(result.registry)
        }

        private fun reindex(
            rows: List<ArKernelIndependentTargetRow>,
        ): List<ArKernelIndependentTargetRow> =
            rows.mapIndexed { index, row -> row.copy(index = index) }
    }

    @Test
    fun roundTripPreservesEveryField() {
        val source = registry(
            reindex(
                listOf(
                    scaleTarget(),
                    checkTarget(),
                    checkTarget(id = "bar.500").copy(
                        kind = ArKernelTargetKind.MACHINED_BAR,
                        nominalMillimetres = 500L,
                        measuredMillimetres = 501L,
                        toleranceMillimetres = 2L,
                        source = ArKernelTargetDimensionSource.CALIPER,
                    ),
                )
            )
        )
        val parsed = ArKernelIndependentTargetsAdapter
            .parse(ArKernelIndependentTargetsAdapter.encode(source))
        assertTrue(parsed.detail, parsed.accepted)
        val registry = checkNotNull(parsed.registry)
        assertEquals(source.targets, registry.targets)
        assertEquals(source.targetCount, registry.targetCount)
        assertEquals(source.usableCheckCount, registry.usableCheckCount)
        assertEquals(source.maxDeviationMillimetres,
            registry.maxDeviationMillimetres)
        assertEquals(
            source.independenceSufficient,
            registry.independenceSufficient,
        )
    }

    @Test
    fun deviationIsTheGapBetweenNominalAndMeasured() {
        val row = checkTarget(nominal = 100L, measured = 104L)
        assertEquals(4L, row.deviationMillimetres)
        assertTrue(row.consistent)
        val outside = checkTarget(nominal = 100L, measured = 106L)
        assertEquals(6L, outside.deviationMillimetres)
        assertFalse(outside.consistent)
    }

    @Test
    fun aMisprintedSheetIsCaughtAsInconsistent() {
        val row = checkTarget(
            id = "checker.a4",
            nominal = 250L,
            measured = 268L,
            tolerance = 2L,
        )
        assertEquals(18L, row.deviationMillimetres)
        assertFalse(row.consistent)
        val registry = registry(reindex(listOf(scaleTarget(), row)))
        assertFalse(registry.targetsConsistent)
        assertFalse(registry.independenceSufficient)
    }

    @Test
    fun aTargetThatSetTheScaleIsNeverUsableAsACheck() {
        val scale = scaleTarget()
        assertFalse(scale.independent)
        assertFalse(scale.usableAsCheck)
        val registry = registry(reindex(listOf(scale)))
        assertEquals(0, registry.usableCheckCount)
        assertEquals(0, registry.independentCount)
        assertFalse(registry.independenceSufficient)
    }

    @Test
    fun consistencyAloneDoesNotMakeATargetIndependent() {
        val consistentButUsed = scaleTarget()
        assertTrue(consistentButUsed.consistent)
        assertFalse(consistentButUsed.usableAsCheck)
    }

    @Test
    fun independenceNeedsConsistentHeldOutUnusedTargets() {
        val one = registry(
            reindex(listOf(scaleTarget(), checkTarget())),
            required = 1,
        )
        assertTrue(one.independenceSufficient)
        assertEquals(1, one.usableCheckCount)
        val two = registry(
            reindex(listOf(scaleTarget(), checkTarget())),
            required = 2,
        )
        assertFalse(two.independenceSufficient)
    }

    @Test
    fun usableCheckIdsListOnlyTheNonCircularTargets() {
        val registry = registry(
            reindex(
                listOf(
                    scaleTarget(id = "checker.a4"),
                    checkTarget(id = "gauge.100"),
                    checkTarget(id = "gauge.200").copy(
                        nominalMillimetres = 200L,
                        measuredMillimetres = 200L,
                    ),
                )
            )
        )
        assertEquals(
            listOf("gauge.100", "gauge.200"),
            registry.usableCheckIds(),
        )
    }

    @Test
    fun aTargetCannotSetTheScaleAndBeHeldOut() {
        val result = ArKernelIndependentTargetsAdapter.create(
            registryId = REGISTRY_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredIndependentTargets = 1,
            targets = listOf(
                checkTarget().copy(usedForScale = true, heldOut = true),
            ),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelIndependentTargetsRejection.ROLE_CONFLICT,
            result.rejection,
        )
    }

    @Test
    fun roleConflictIsRejectedOnParseToo() {
        val registry = registry(reindex(listOf(checkTarget())))
        val text = ArKernelIndependentTargetsAdapter.encode(registry)
            .replace("|false|true|", "|true|true|")
        val parsed = ArKernelIndependentTargetsAdapter.parse(text)
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelIndependentTargetsRejection.ROLE_CONFLICT,
            parsed.rejection,
        )
    }

    @Test
    fun duplicateTargetIdsAreRejected() {
        val result = ArKernelIndependentTargetsAdapter.create(
            registryId = REGISTRY_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredIndependentTargets = 1,
            targets = reindex(listOf(checkTarget(), checkTarget())),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelIndependentTargetsRejection.DUPLICATE_TARGET,
            result.rejection,
        )
    }

    @Test
    fun nonContiguousRowsAreRejected() {
        val result = ArKernelIndependentTargetsAdapter.create(
            registryId = REGISTRY_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredIndependentTargets = 1,
            targets = listOf(
                scaleTarget(),
                checkTarget().copy(index = 2),
            ),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelIndependentTargetsRejection.INVALID_ORDER,
            result.rejection,
        )
    }

    @Test
    fun outOfBoundsDimensionsAreRejected() {
        val tooLarge = ArKernelIndependentTargetsAdapter.create(
            registryId = REGISTRY_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredIndependentTargets = 1,
            targets = listOf(checkTarget(nominal = 100_001L)),
        )
        assertEquals(
            ArKernelIndependentTargetsRejection.INVALID_VALUE,
            tooLarge.rejection,
        )
        val zeroTolerance = ArKernelIndependentTargetsAdapter.create(
            registryId = REGISTRY_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredIndependentTargets = 1,
            targets = listOf(checkTarget(tolerance = 0L)),
        )
        assertFalse(zeroTolerance.accepted)
        val zeroRepeats = ArKernelIndependentTargetsAdapter.create(
            registryId = REGISTRY_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredIndependentTargets = 1,
            targets = listOf(checkTarget().copy(repeatCount = 0)),
        )
        assertEquals(
            ArKernelIndependentTargetsRejection.INVALID_COUNT,
            zeroRepeats.rejection,
        )
    }

    @Test
    fun emptyAndOversizedRegistriesAreRejected() {
        val empty = ArKernelIndependentTargetsAdapter.create(
            registryId = REGISTRY_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredIndependentTargets = 1,
            targets = emptyList(),
        )
        assertFalse(empty.accepted)
        val tooMany = ArKernelIndependentTargetsAdapter.create(
            registryId = REGISTRY_ID,
            deviceToken = DEVICE_TOKEN,
            campaignId = CAMPAIGN_ID,
            requiredIndependentTargets = 1,
            targets = reindex(
                (0..16).map { checkTarget(id = "gauge.$it") }
            ),
        )
        assertFalse(tooMany.accepted)
    }

    @Test
    fun tamperingWithADeclaredStatisticIsRejected() {
        val registry = registry(reindex(listOf(scaleTarget(), checkTarget())))
        val text = ArKernelIndependentTargetsAdapter.encode(registry)
        val tampered = text.replace(
            "usable_check_count=1",
            "usable_check_count=2",
        )
        val parsed = ArKernelIndependentTargetsAdapter.parse(tampered)
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelIndependentTargetsRejection.STATISTICS_MISMATCH,
            parsed.rejection,
        )
    }

    @Test
    fun tamperingWithTheIndependenceVerdictIsRejected() {
        val registry = registry(
            reindex(listOf(scaleTarget(), checkTarget())),
            required = 2,
        )
        assertFalse(registry.independenceSufficient)
        val text = ArKernelIndependentTargetsAdapter.encode(registry)
        val parsed = ArKernelIndependentTargetsAdapter.parse(
            text.replace("independence_sufficient=false",
                "independence_sufficient=true")
        )
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelIndependentTargetsRejection.INDEPENDENCE_MISMATCH,
            parsed.rejection,
        )
    }

    @Test
    fun safetyInvariantsCannotBeRelaxed() {
        val registry = registry(reindex(listOf(checkTarget())), required = 1)
        val text = ArKernelIndependentTargetsAdapter.encode(registry)
        for (key in listOf(
            "automatic_calibration_disabled",
            "universal_calibration_disabled",
            "per_device_calibration",
            "automatic_upload_disabled",
            "manual_upload_only",
            "images_disabled",
            "ground_truth_manual",
        )) {
            assertTrue(text.contains("$key=true"))
            val parsed = ArKernelIndependentTargetsAdapter.parse(
                text.replace("$key=true", "$key=false")
            )
            assertFalse(parsed.accepted)
            assertEquals(
                ArKernelIndependentTargetsRejection.UNSAFE_INVARIANT,
                parsed.rejection,
            )
        }
        assertTrue(text.contains("metric_accuracy_claimed=false"))
        val claimed = ArKernelIndependentTargetsAdapter.parse(
            text.replace(
                "metric_accuracy_claimed=false",
                "metric_accuracy_claimed=true",
            )
        )
        assertFalse(claimed.accepted)
    }

    @Test
    fun theGradeCanNeverBeUpgraded() {
        val registry = registry(reindex(listOf(checkTarget())), required = 1)
        val text = ArKernelIndependentTargetsAdapter.encode(registry)
        assertTrue(text.contains("quality_grade=unverified"))
        val parsed = ArKernelIndependentTargetsAdapter.parse(
            text.replace(
                "quality_grade=unverified",
                "quality_grade=centimeter-validated",
            )
        )
        assertFalse(parsed.accepted)
        assertEquals(
            ArKernelIndependentTargetsRejection.INVALID_QUALITY,
            parsed.rejection,
        )
    }

    @Test
    fun malformedInputIsRejectedWithoutThrowing() {
        assertEquals(
            ArKernelIndependentTargetsRejection.EMPTY_REGISTRY,
            ArKernelIndependentTargetsAdapter.parse("   ").rejection,
        )
        assertEquals(
            ArKernelIndependentTargetsRejection.INVALID_HEADER,
            ArKernelIndependentTargetsAdapter.parse("WRONG\nEND").rejection,
        )
        val registry = registry(reindex(listOf(checkTarget())), required = 1)
        val text = ArKernelIndependentTargetsAdapter.encode(registry)
        assertEquals(
            ArKernelIndependentTargetsRejection.INVALID_SCHEMA,
            ArKernelIndependentTargetsAdapter.parse(
                text.replace("END", "DONE")
            ).rejection,
        )
        assertEquals(
            ArKernelIndependentTargetsRejection.INVALID_KIND,
            ArKernelIndependentTargetsAdapter.parse(
                text.replace("gauge.block", "unobtainium")
            ).rejection,
        )
        assertEquals(
            ArKernelIndependentTargetsRejection.INVALID_SOURCE,
            ArKernelIndependentTargetsAdapter.parse(
                text.replace("certified.gauge", "eyeballed")
            ).rejection,
        )
        assertEquals(
            ArKernelIndependentTargetsRejection.LINE_TOO_LONG,
            ArKernelIndependentTargetsAdapter.parse(
                text.replace(
                    "target=0|gauge.100",
                    "target=0|" + "x".repeat(300),
                )
            ).rejection,
        )
        assertEquals(
            ArKernelIndependentTargetsRejection.INPUT_TOO_LARGE,
            ArKernelIndependentTargetsAdapter.parse(
                "ARKTARGET1\n" + "x\n".repeat(40_000)
            ).rejection,
        )
    }

    @Test
    fun aByteOrderMarkAndCarriageReturnsAreTolerated() {
        val registry = registry(
            reindex(listOf(scaleTarget(), checkTarget())),
            required = 1,
        )
        val text = ArKernelIndependentTargetsAdapter.encode(registry)
        val parsed = ArKernelIndependentTargetsAdapter.parse(
            "\uFEFF" + text.replace("\n", "\r\n")
        )
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(
            registry.targets,
            checkNotNull(parsed.registry).targets,
        )
    }

    @Test
    fun summarizeReportsStructureAndNothingAboutAccuracy() {
        val registry = registry(
            reindex(
                listOf(
                    scaleTarget(),
                    checkTarget(),
                    checkTarget(id = "bar.500").copy(
                        kind = ArKernelTargetKind.MACHINED_BAR,
                        nominalMillimetres = 500L,
                        measuredMillimetres = 502L,
                        toleranceMillimetres = 3L,
                        source = ArKernelTargetDimensionSource.CALIPER,
                    ),
                )
            )
        )
        val summary = ArKernelIndependentTargetsAdapter.summarize(registry)
        assertEquals(3, summary.targetCount)
        assertEquals(3, summary.consistentCount)
        assertEquals(2, summary.independentCount)
        assertEquals(2, summary.heldOutCount)
        assertEquals(2, summary.usableCheckCount)
        assertEquals(2L, summary.maxDeviationMillimetres)
        assertEquals(3, summary.distinctKinds)
        assertEquals(3, summary.distinctSources)
        assertTrue(summary.targetsConsistent)
        assertTrue(summary.independenceSufficient)
        assertEquals(
            ArKernelIndependentTargetsRejection.NONE,
            ArKernelIndependentTargetsAdapter
                .parse(ArKernelIndependentTargetsAdapter.encode(registry))
                .rejection,
        )
    }
}
