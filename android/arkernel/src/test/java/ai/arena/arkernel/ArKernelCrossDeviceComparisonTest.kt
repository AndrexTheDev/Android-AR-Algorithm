package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class CrossRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelCrossDeviceRejection,
)

class ArKernelCrossDeviceComparisonTest {
    @Test
    fun createAndVerifyBindsTwoDeviceSessions() {
        val bindings = sampleBindingTexts()
        val result =
            ArKernelCrossDeviceComparisonAdapter.create(
                "compare.host.v1", bindings)
        assertTrue(result.detail, result.accepted)
        assertEquals(ArKernelCrossDeviceRejection.NONE,
            result.rejection)
        val bundle = result.bundle!!
        assertEquals(2, bundle.rows.size)
        assertEquals("device.host.v1",
            bundle.rows[0].deviceToken)
        assertEquals("device.other.v1",
            bundle.rows[1].deviceToken)
        assertEquals("campaign.host.v1",
            bundle.rows[0].campaignId)
        assertEquals("campaign.other.v1",
            bundle.rows[1].campaignId)
        assertFalse(bundle.rows[0].bindingDigest.isEmpty())
        val report = result.report!!
        assertTrue(report.allSessionsParsed)
        assertTrue(report.digestsMatch)
        assertTrue(report.deviceTokensUnique)
        assertTrue(report.countsMatch)
        assertTrue(report.canonicalQuestions)
        assertTrue(report.comparisonConsistent)
        assertFalse(report.sourceReverificationPerformed)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", bundle.qualityGrade)
        val summary =
            ArKernelCrossDeviceComparisonAdapter.summarize(bundle)
        assertEquals(2, summary.sessionCount)
        assertEquals(2, summary.distinctDeviceTokens)
        assertEquals(1, summary.distinctDeviceClasses)
        assertEquals(2, summary.distinctCampaignIds)
        assertEquals(2, summary.fullCount)
        assertEquals(9, summary.minSceneCount)
        assertEquals(9, summary.maxSceneCount)
        assertEquals(3, summary.minObservationCount)
        assertEquals(3, summary.maxObservationCount)
        assertTrue(summary.allCanonicalQuestions)
        assertTrue(summary.portableProtocolSame)
        assertEquals("unverified", summary.qualityGrade)
        val text =
            ArKernelCrossDeviceComparisonAdapter.encode(bundle)
        val parsed =
            ArKernelCrossDeviceComparisonAdapter.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(bundle, parsed.bundle)
    }

    @Test
    fun mismatchedOrTamperedSessionsFailClosed() {
        val bindings = sampleBindingTexts()
        val good =
            ArKernelCrossDeviceComparisonAdapter.create(
                "compare.host.v1", bindings)
        assertTrue(good.accepted)
        val bundle = good.bundle!!
        val duplicate =
            ArKernelCrossDeviceComparisonAdapter.create(
                "compare.host.v1",
                listOf(bindings[0], bindings[0]))
        assertFalse(duplicate.accepted)
        assertEquals(
            ArKernelCrossDeviceRejection.DUPLICATE_DEVICE,
            duplicate.rejection)
        val invalid =
            ArKernelCrossDeviceComparisonAdapter.create(
                "compare.host.v1",
                listOf(bindings[0], "OTHER1\n" + bindings[1]))
        assertFalse(invalid.accepted)
        assertEquals(
            ArKernelCrossDeviceRejection.INVALID_SESSION,
            invalid.rejection)
        val empty =
            ArKernelCrossDeviceComparisonAdapter.create(
                "compare.host.v1", emptyList())
        assertFalse(empty.accepted)
        assertEquals(
            ArKernelCrossDeviceRejection.INVALID_COUNT,
            empty.rejection)
        val tamperedDigest =
            ArKernelCrossDeviceComparisonAdapter.verify(
                bundle.copy(rows = bundle.rows.mapIndexed { i, row ->
                    if (i == 0) {
                        row.copy(bindingDigest = "0".repeat(64))
                    } else {
                        row
                    }
                }), bindings)
        assertFalse(tamperedDigest.digestsMatch)
        assertFalse(tamperedDigest.comparisonConsistent)
        val tamperedCounts =
            ArKernelCrossDeviceComparisonAdapter.verify(
                bundle.copy(rows = bundle.rows.mapIndexed { i, row ->
                    if (i == 0) {
                        row.copy(sceneCount = 8)
                    } else {
                        row
                    }
                }), bindings)
        assertFalse(tamperedCounts.countsMatch)
        assertFalse(tamperedCounts.comparisonConsistent)
        val unparsed =
            ArKernelCrossDeviceComparisonAdapter.verify(
                bundle, listOf(bindings[0], "OTHER1\n" +
                    bindings[1]))
        assertFalse(unparsed.allSessionsParsed)
        assertFalse(unparsed.comparisonConsistent)
        assertFalse(unparsed.sourceReverificationPerformed)
        assertEquals("unverified", unparsed.qualityGrade)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val bundle =
            ArKernelCrossDeviceComparisonAdapter.create(
                "compare.host.v1", sampleBindingTexts()).bundle!!
        val text =
            ArKernelCrossDeviceComparisonAdapter.encode(bundle)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val parsed =
                ArKernelCrossDeviceComparisonAdapter.parse(variant)
            assertTrue(parsed.detail, parsed.accepted)
            assertEquals(bundle, parsed.bundle)
        }
        assertEquals(
            ArKernelCrossDeviceRejection.EMPTY_LINE,
            ArKernelCrossDeviceComparisonAdapter
                .parse(text + "\n\n").rejection)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val bundle =
            ArKernelCrossDeviceComparisonAdapter.create(
                "compare.host.v1", sampleBindingTexts()).bundle!!
        val text =
            ArKernelCrossDeviceComparisonAdapter.encode(bundle)
        val FIRST_DIGEST = bundle.rows[0].bindingDigest
        assertTrue(text.contains(FIRST_DIGEST))
        val cases = listOf(
            CrossRejectionCase(
                "inputTooLarge",
                "x".repeat(16_385),
                ArKernelCrossDeviceRejection.INPUT_TOO_LARGE,
            ),
            CrossRejectionCase(
                "empty",
                "",
                ArKernelCrossDeviceRejection.EMPTY_PLAN,
            ),
            CrossRejectionCase(
                "badHeader",
                replaceLine(
                        text,
                        0,
                        "OTHER1",
                    ),
                ArKernelCrossDeviceRejection.INVALID_HEADER,
            ),
            CrossRejectionCase(
                "headerWithSpace",
                replaceLine(
                        text,
                        0,
                        "ARKCROSS1 x",
                    ),
                ArKernelCrossDeviceRejection.INVALID_HEADER,
            ),
            CrossRejectionCase(
                "badVersion",
                replaceOnce(
                        text,
                        "version=1",
                        "version=2",
                    ),
                ArKernelCrossDeviceRejection.INVALID_VALUE,
            ),
            CrossRejectionCase(
                "badProtocol",
                replaceOnce(
                        text,
                        "protocol_id=arkadapt1",
                        "protocol_id=other",
                    ),
                ArKernelCrossDeviceRejection.INVALID_PROTOCOL,
            ),
            CrossRejectionCase(
                "assessedQualityInput",
                replaceOnce(
                        text,
                        "quality_grade=unverified",
                        "quality_grade=verified",
                    ),
                ArKernelCrossDeviceRejection.INVALID_QUALITY,
            ),
            CrossRejectionCase(
                "invalidBoolean",
                replaceOnce(
                        text,
                        "images_disabled=true",
                        "images_disabled=maybe",
                    ),
                ArKernelCrossDeviceRejection.INVALID_BOOLEAN,
            ),
            CrossRejectionCase(
                "portableProtocolFalse",
                replaceOnce(
                        text,
                        "portable_protocol=true",
                        "portable_protocol=false",
                    ),
                ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
            ),
            CrossRejectionCase(
                "perDeviceCalibrationFalse",
                replaceOnce(
                        text,
                        "per_device_calibration=true",
                        "per_device_calibration=false",
                    ),
                ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
            ),
            CrossRejectionCase(
                "universalCalibrationEnabled",
                replaceOnce(
                        text,
                        "universal_calibration_disabled=true",
                        "universal_calibration_disabled=false",
                    ),
                ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
            ),
            CrossRejectionCase(
                "imagesEnabled",
                replaceOnce(
                        text,
                        "images_disabled=true",
                        "images_disabled=false",
                    ),
                ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
            ),
            CrossRejectionCase(
                "automaticCalibrationEnabled",
                replaceOnce(
                        text,
                        "automatic_calibration_disabled=true",
                        "automatic_calibration_disabled=false",
                    ),
                ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
            ),
            CrossRejectionCase(
                "automaticUploadEnabled",
                replaceOnce(
                        text,
                        "automatic_upload_disabled=true",
                        "automatic_upload_disabled=false",
                    ),
                ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
            ),
            CrossRejectionCase(
                "signatureVerificationEnabled",
                replaceOnce(
                        text,
                        "signature_verification_disabled=true",
                        "signature_verification_disabled=false",
                    ),
                ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
            ),
            CrossRejectionCase(
                "manualUploadOnlyFalse",
                replaceOnce(
                        text,
                        "manual_upload_only=true",
                        "manual_upload_only=false",
                    ),
                ArKernelCrossDeviceRejection.UNSAFE_INVARIANT,
            ),
            CrossRejectionCase(
                "invalidComparisonToken",
                replaceOnce(
                        text,
                        "comparison_id=compare.host.v1",
                        "comparison_id=bad!id",
                    ),
                ArKernelCrossDeviceRejection.INVALID_TOKEN,
            ),
            CrossRejectionCase(
                "zeroSessions",
                replaceOnce(
                        text,
                        "session_count=2",
                        "session_count=0",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "tooManySessions",
                replaceOnce(
                        text,
                        "session_count=2",
                        "session_count=17",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "nonCanonicalQuestionCount",
                replaceOnce(
                        text,
                        "question_count=10",
                        "question_count=9",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "lineCountMismatch",
                replaceOnce(
                        text,
                        "session_count=2",
                        "session_count=1",
                    ),
                ArKernelCrossDeviceRejection.INVALID_SCHEMA,
            ),
            CrossRejectionCase(
                "keyOrder",
                replaceLine(
                        text,
                        1,
                        "comparison_id=compare.host.v1",
                    ),
                ArKernelCrossDeviceRejection.INVALID_SCHEMA,
            ),
            CrossRejectionCase(
                "spaceInMetadata",
                replaceOnce(
                        text,
                        "version=1",
                        "version=1 x",
                    ),
                ArKernelCrossDeviceRejection.INVALID_SCHEMA,
            ),
            CrossRejectionCase(
                "lineTooLong",
                replaceLine(
                        text,
                        15,
                        "x".repeat(257),
                    ),
                ArKernelCrossDeviceRejection.LINE_TOO_LONG,
            ),
            CrossRejectionCase(
                "blankLine",
                replaceLine(
                        text,
                        15,
                        "",
                    ),
                ArKernelCrossDeviceRejection.EMPTY_LINE,
            ),
            CrossRejectionCase(
                "extraFinalSeparator",
                text + "\n\n",
                ArKernelCrossDeviceRejection.EMPTY_LINE,
            ),
            CrossRejectionCase(
                "missingEnd",
                replaceLine(
                        text,
                        17,
                        "STOP",
                    ),
                ArKernelCrossDeviceRejection.INVALID_SCHEMA,
            ),
            CrossRejectionCase(
                "wrongRowPrefix",
                replaceLine(
                        text,
                        15,
                        "sessionx=0|only",
                    ),
                ArKernelCrossDeviceRejection.INVALID_SCHEMA,
            ),
            CrossRejectionCase(
                "rowFieldCount",
                replaceLine(
                        text,
                        15,
                        "session=0|only",
                    ),
                ArKernelCrossDeviceRejection.INVALID_ROW_FIELDS,
            ),
            CrossRejectionCase(
                "invalidRowIndex",
                rowField(
                        text,
                        15,
                        0,
                        "x",
                    ),
                ArKernelCrossDeviceRejection.INVALID_VALUE,
            ),
            CrossRejectionCase(
                "rowOrder",
                replaceOnce(
                        text,
                        "session=0|",
                        "session=1|",
                    ),
                ArKernelCrossDeviceRejection.INVALID_ORDER,
            ),
            CrossRejectionCase(
                "invalidDeviceToken",
                rowField(
                        text,
                        15,
                        1,
                        "bad!id",
                    ),
                ArKernelCrossDeviceRejection.INVALID_TOKEN,
            ),
            CrossRejectionCase(
                "invalidCampaignId",
                rowField(
                        text,
                        15,
                        3,
                        "bad!id",
                    ),
                ArKernelCrossDeviceRejection.INVALID_TOKEN,
            ),
            CrossRejectionCase(
                "invalidDeviceClass",
                rowField(
                        text,
                        15,
                        2,
                        "premium",
                    ),
                ArKernelCrossDeviceRejection.INVALID_DEVICE_CLASS,
            ),
            CrossRejectionCase(
                "invalidBindingDigest",
                rowField(
                        text,
                        15,
                        4,
                        "ABC",
                    ),
                ArKernelCrossDeviceRejection.INVALID_DIGEST,
            ),
            CrossRejectionCase(
                "uppercaseDigest",
                rowField(
                        text,
                        15,
                        4,
                        FIRST_DIGEST.uppercase(),
                    ),
                ArKernelCrossDeviceRejection.INVALID_DIGEST,
            ),
            CrossRejectionCase(
                "zeroScenes",
                rowField(
                        text,
                        15,
                        5,
                        "0",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "tooManyScenes",
                rowField(
                        text,
                        15,
                        5,
                        "17",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "nonCanonicalRowQuestions",
                rowField(
                        text,
                        15,
                        6,
                        "9",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "zeroObservations",
                rowField(
                        text,
                        15,
                        7,
                        "0",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "tooManyObservations",
                rowField(
                        text,
                        15,
                        7,
                        "17",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "tooManyPoses",
                rowField(
                        text,
                        15,
                        8,
                        "33",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "invalidRowCountValue",
                rowField(
                        text,
                        15,
                        8,
                        "x",
                    ),
                ArKernelCrossDeviceRejection.INVALID_COUNT,
            ),
            CrossRejectionCase(
                "duplicateDevice",
                rowField(
                        text,
                        16,
                        1,
                        "device.host.v1",
                    ),
                ArKernelCrossDeviceRejection.DUPLICATE_DEVICE,
            ),
            CrossRejectionCase(
                "duplicateBinding",
                rowField(
                        text,
                        16,
                        4,
                        FIRST_DIGEST,
                    ),
                ArKernelCrossDeviceRejection.DUPLICATE_BINDING,
            ),
        )
        assertEquals(45, cases.size)
        for (case in cases) {
            val result =
                ArKernelCrossDeviceComparisonAdapter.parse(case.text)
            assertFalse("${case.name} unexpectedly accepted",
                result.accepted)
            assertNull("${case.name} leaked a partial bundle",
                result.bundle)
            assertEquals(case.name, case.reason,
                result.rejection)
            assertTrue("${case.name} lacked a rejection detail",
                result.detail.isNotBlank())
            assertTrue("${case.name} had a negative line number",
                result.lineNumber >= 0)
        }
    }

    @Test
    fun constructorsRejectUnsafeShapes() {
        val bundle =
            ArKernelCrossDeviceComparisonAdapter.create(
                "compare.host.v1", sampleBindingTexts()).bundle!!
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(portableProtocol = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(perDeviceCalibration = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(universalCalibrationDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(manualUploadOnly = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(rows = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(rows = listOf(
                bundle.rows[0],
                bundle.rows[1].copy(deviceToken =
                    bundle.rows[0].deviceToken),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCrossDeviceSessionRow(
                0, "device.ok",
                ArKernelAdaptiveDeviceClass.FULL,
                "campaign.ok", "a".repeat(64), 9, 9, 3, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelCrossDeviceSessionRow(
                0, "device.ok",
                ArKernelAdaptiveDeviceClass.FULL,
                "campaign.ok", "ABC", 9, 10, 3, 2)
        }
    }

    private fun sampleBindingTexts(): List<String> =
        listOf(
            ArKernelSessionBindingAdapter.encode(
                ArKernelSessionBindingAdapter.create(
                    "bind.session.v1",
                    sampleProfileText("device.host.v1"),
                    sampleEvidenceText("device.host.v1"),
                    samplePlanText("campaign.host.v1",
                        "device.host.v1")).bundle!!),
            ArKernelSessionBindingAdapter.encode(
                ArKernelSessionBindingAdapter.create(
                    "bind.session.v2",
                    sampleProfileText("device.other.v1"),
                    sampleEvidenceText("device.other.v1"),
                    samplePlanText("campaign.other.v1",
                        "device.other.v1")).bundle!!),
        )

    private fun sampleProfileText(deviceToken: String): String {
        val selection =
            ArKernelDeviceAdaptiveProfileAdapter.selectProfile(
                ArKernelAdaptiveDeviceFacts(
                    1, 3, true, true, true, false, 24, false, 35))
        val bundle = ArKernelAdaptiveBundle(
            sessionId = "adapt.session.v1",
            deviceToken = deviceToken,
            deviceClass = selection.deviceClass,
            intrinsicsState = selection.intrinsicsState,
            gyroAvailable = true,
            rotationVectorAvailable = true,
            thermalMonitoringAvailable = true,
            steps = selection.steps,
        )
        return ArKernelDeviceAdaptiveProfileAdapter.encode(bundle)
    }

    private fun sampleEvidenceText(deviceToken: String): String {
        val observations = listOf(
            ArKernelCalibrationObservation(
                0, "obs-01",
                ArKernelCalibrationProvenance.SCALE_BAR,
                0.297, 0.299, 0.001, true),
            ArKernelCalibrationObservation(
                1, "obs-02",
                ArKernelCalibrationProvenance.SURVEY,
                0.5, 0.502, 0.001, true),
            ArKernelCalibrationObservation(
                2, "obs-03",
                ArKernelCalibrationProvenance.SENSOR_GROUND_TRUTH,
                2.0, 2.003, 0.002, true),
        )
        val poses = listOf(
            ArKernelCalibrationPose(
                0, 1_000_000_000L, 0.0, 0.0, 0.0,
                1.0, 0.0, 0.0, 0.0, 0.05, false),
            ArKernelCalibrationPose(
                1, 2_000_000_000L, 0.0, 0.0, 0.0,
                0.707107, 0.707107, 0.0, 0.0, 0.05, false),
        )
        val bundle = ArKernelCalibrationEvidenceBundle(
            sessionId = "calib.session.v1",
            deviceToken = deviceToken,
            observations = observations,
            poses = poses,
            assetExtentMeters = 2.0,
        )
        return ArKernelCalibrationEvidenceAdapter.encode(bundle)
    }

    private fun samplePlanText(
        campaignId: String,
        deviceToken: String,
    ): String =
        ArKernelCampaignPlanAdapter.encode(
            ArKernelCampaignPlanAdapter.canonicalPlan(
                campaignId, deviceToken,
                ArKernelAdaptiveDeviceClass.FULL))

    private fun replaceLine(
        text: String,
        index: Int,
        value: String,
    ): String {
        val lines = text.split("\n").toMutableList()
        lines[index] = value
        return lines.joinToString("\n")
    }

    private fun replaceOnce(
        text: String,
        old: String,
        new: String,
    ): String {
        val at = text.indexOf(old)
        check(at >= 0) { "Missing replacement anchor: $old" }
        return text.substring(0, at) + new +
            text.substring(at + old.length)
    }

    private fun rowField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.split("\n").toMutableList()
        val fields = lines[lineIndex].removePrefix("session=")
            .split("|").toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = "session=" + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
