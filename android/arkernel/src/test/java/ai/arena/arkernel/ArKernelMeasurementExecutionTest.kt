package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class MeasurementRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelMeasurementRejection,
)

class ArKernelMeasurementExecutionTest {
    companion object {
        private const val FIRST_ROW_INDEX = 21
        private const val END_INDEX = 30
        private val DIGESTS = listOf(
        "5b00d84269df230ecb5f885ddb1a35f6b409cec0b6b84765a9a46a58b556ff12",
        "cb9b4ba2679338295bad3662213e3917a7f0ed12ce542eba9ac535f42b25f427",
        "2c214a0b5b9dfa3c1928facf683a3006a77cff87b884175ff71c531d0b8b9f66",
        "24f1dce0b42bd4efa519c9c2eecf3c9da411b2a3884211c04ae4a1830973b991",
        "a12ea2e3ffecb084ad209e08e6e47e4097619d4284f4fbda368727fc14643757",
        "de356b9be08ea47bddcc2fa3da10f1ece957c17d09b7eac80a41444f2bf67aad",
        "04c49cae38bc9638239a0cbc92d8c34bc2c8da84cd8a250c458991ea0b384072",
        "ddb22079addcf8a0e716492aa955de719a236cd3bd31622643729c4221b19234",
        "0c22d106bbbec37eeb284a8b47aeb33f8ed10510adb58c17b5f40c7b51ced550",
        )
        private val KINDS = listOf(
            ArKernelCampaignSceneKind.INDOOR_SCALE,
            ArKernelCampaignSceneKind.OUTDOOR_SCALE,
            ArKernelCampaignSceneKind.RIGHT_ANGLE,
            ArKernelCampaignSceneKind.STILLNESS,
            ArKernelCampaignSceneKind.THERMAL_REPEAT,
            ArKernelCampaignSceneKind.DAY_NIGHT,
            ArKernelCampaignSceneKind.HELD_OUT,
            ArKernelCampaignSceneKind.CROSS_SESSION,
            ArKernelCampaignSceneKind.CROSS_DEVICE,
        )
        private val CONDITIONS = listOf(
            ArKernelMeasurementCondition.INDOOR,
            ArKernelMeasurementCondition.OUTDOOR,
            ArKernelMeasurementCondition.INDOOR,
            ArKernelMeasurementCondition.STILL,
            ArKernelMeasurementCondition.THERMAL,
            ArKernelMeasurementCondition.NIGHT,
            ArKernelMeasurementCondition.OUTDOOR,
            ArKernelMeasurementCondition.DAY,
            ArKernelMeasurementCondition.DAY,
        )
    }

    @Test
    fun sampleLedgerRoundTripsAndReportsOnlyStructuralFacts() {
        val bundle = sampleBundle()
        val text = ArKernelMeasurementAdapter.encode(bundle)
        val result = ArKernelMeasurementAdapter.parse(text)
        assertTrue(result.accepted)
        assertEquals(ArKernelMeasurementRejection.NONE,
            result.rejection)
        assertEquals(bundle, result.bundle)
        assertEquals(text, ArKernelMeasurementAdapter.encode(
            result.bundle!!))
        val report = ArKernelMeasurementAdapter.summarize(
            result.bundle!!)
        assertEquals(9, report.sceneCount)
        assertEquals(9, report.distinctKinds)
        assertEquals(1, report.heldOutCount)
        assertEquals(9, report.confirmedCount)
        assertEquals(9, report.distinctDigests)
        assertTrue(report.allCanonicalKindsCovered)
        assertTrue(report.allScenesConfirmed)
        assertTrue(report.heldOutPresent)
        assertTrue(report.evidenceComplete)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.bundle!!.qualityGrade)
    }

    @Test
    fun createBindsNineCanonicalCalibrationTextsByDigest() {
        val entries = KINDS.indices.map { index ->
            ArKernelMeasurementEntry(
                kind = KINDS[index],
                sceneId = "scene." +
                    (index + 1).toString().padStart(2, '0'),
                condition = CONDITIONS[index],
                userConfirmed = true,
                heldOut = KINDS[index] ==
                    ArKernelCampaignSceneKind.HELD_OUT,
                calibrationText = calibrationText(index + 1),
            )
        }
        val result = ArKernelMeasurementAdapter.create(
            "measurement.host.v1", "phone.redmi.v1",
            ArKernelAdaptiveDeviceClass.FULL,
            "campaign.host.v1", entries)
        assertTrue(result.detail, result.accepted)
        assertEquals(DIGESTS, result.sourceDigests)
        val text = ArKernelMeasurementAdapter.encode(result.bundle!!)
        val parsed = ArKernelMeasurementAdapter.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(DIGESTS[0], parsed.bundle!!.rows[0]
            .calibrationDigest)
        assertTrue(ArKernelMeasurementAdapter
            .summarize(parsed.bundle!!).evidenceComplete)
        assertEquals(ArKernelMeasurementRejection.NONE,
            ArKernelMeasurementAdapter.verify(text).rejection)
    }

    @Test
    fun createRejectsUnconfirmedDuplicateDigestMissingHeldOutAndBadSource() {
        fun entries(): List<ArKernelMeasurementEntry> =
            KINDS.indices.map { index ->
                ArKernelMeasurementEntry(
                    kind = KINDS[index],
                    sceneId = "scene." +
                        (index + 1).toString().padStart(2, '0'),
                    condition = CONDITIONS[index],
                    userConfirmed = true,
                    heldOut = KINDS[index] ==
                        ArKernelCampaignSceneKind.HELD_OUT,
                    calibrationText = calibrationText(index + 1),
                )
            }
        fun create(entries: List<ArKernelMeasurementEntry>) =
            ArKernelMeasurementAdapter.create(
                "measurement.host.v1", "phone.redmi.v1",
                ArKernelAdaptiveDeviceClass.FULL,
                "campaign.host.v1", entries)
        assertEquals(
            ArKernelMeasurementRejection.UNCONFIRMED_SCENE,
            create(entries().mapIndexed { index, entry ->
                if (index == 0) entry.copy(userConfirmed = false)
                else entry
            }).rejection)
        assertEquals(
            ArKernelMeasurementRejection.HELD_OUT_REQUIRED,
            create(entries().map { it.copy(heldOut = false) })
                .rejection)
        assertEquals(
            ArKernelMeasurementRejection.DUPLICATE_DIGEST,
            create(entries().mapIndexed { index, entry ->
                if (index == 1) entry.copy(
                    calibrationText = calibrationText(1))
                else entry
            }).rejection)
        assertEquals(
            ArKernelMeasurementRejection.DUPLICATE_SCENE,
            create(entries().mapIndexed { index, entry ->
                if (index == 1) entry.copy(sceneId = "scene.01")
                else entry
            }).rejection)
        assertEquals(
            ArKernelMeasurementRejection.INVALID_SOURCE,
            create(entries().mapIndexed { index, entry ->
                if (index == 0) entry.copy(
                    calibrationText = "not a calibration")
                else entry
            }).rejection)
        assertEquals(
            ArKernelMeasurementRejection.INVALID_TOKEN,
            ArKernelMeasurementAdapter.create(
                "bad!id", "phone.redmi.v1",
                ArKernelAdaptiveDeviceClass.FULL,
                "campaign.host.v1", entries()).rejection)
        assertEquals(
            ArKernelMeasurementRejection.INVALID_COUNT,
            create(emptyList()).rejection)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val bundle = sampleBundle()
        val text = ArKernelMeasurementAdapter.encode(bundle)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result = ArKernelMeasurementAdapter.parse(variant)
            assertTrue(result.detail, result.accepted)
            assertEquals(bundle, result.bundle)
        }
        assertEquals(ArKernelMeasurementRejection.EMPTY_LINE,
            ArKernelMeasurementAdapter.parse(text + "\n\n")
                .rejection)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = ArKernelMeasurementAdapter.encode(sampleBundle())
        val row0 = text.split("\n")[FIRST_ROW_INDEX]
        val cases = listOf(
            MeasurementRejectionCase(
                "inputTooLarge",
                "x".repeat(16_385),
                ArKernelMeasurementRejection.INPUT_TOO_LARGE,
            ),
            MeasurementRejectionCase(
                "empty",
                "",
                ArKernelMeasurementRejection.EMPTY_LEDGER,
            ),
            MeasurementRejectionCase(
                "badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelMeasurementRejection.INVALID_HEADER,
            ),
            MeasurementRejectionCase(
                "headerWithSpace",
                replaceLine(text, 0, "ARKMEAS1 x"),
                ArKernelMeasurementRejection.INVALID_HEADER,
            ),
            MeasurementRejectionCase(
                "badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelMeasurementRejection.INVALID_VALUE,
            ),
            MeasurementRejectionCase(
                "badProtocol",
                replaceOnce(text, "protocol_id=arkadapt1", "protocol_id=other"),
                ArKernelMeasurementRejection.INVALID_VALUE,
            ),
            MeasurementRejectionCase(
                "assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified", "quality_grade=verified"),
                ArKernelMeasurementRejection.INVALID_QUALITY,
            ),
            MeasurementRejectionCase(
                "invalidBoolean",
                replaceOnce(text, "images_disabled=true", "images_disabled=maybe"),
                ArKernelMeasurementRejection.INVALID_BOOLEAN,
            ),
            MeasurementRejectionCase(
                "scaleBarFalse",
                replaceOnce(text, "scale_bar_required=true", "scale_bar_required=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "independentTargetFalse",
                replaceOnce(text, "independent_target_required=true",
                    "independent_target_required=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "perDeviceFalse",
                replaceOnce(text, "per_device_calibration=true", "per_device_calibration=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "universalEnabled",
                replaceOnce(text, "universal_calibration_disabled=true",
                    "universal_calibration_disabled=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "imagesEnabled",
                replaceOnce(text, "images_disabled=true", "images_disabled=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "autoCalibrationEnabled",
                replaceOnce(text, "automatic_calibration_disabled=true",
                    "automatic_calibration_disabled=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "autoUploadEnabled",
                replaceOnce(text, "automatic_upload_disabled=true",
                    "automatic_upload_disabled=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "signatureEnabled",
                replaceOnce(text, "signature_verification_disabled=true",
                    "signature_verification_disabled=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "userConfirmationFalse",
                replaceOnce(text, "user_confirmation_required=true",
                    "user_confirmation_required=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "heldOutRequiredFalse",
                replaceOnce(text, "held_out_required=true", "held_out_required=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "manualUploadFalse",
                replaceOnce(text, "manual_upload_only=true", "manual_upload_only=false"),
                ArKernelMeasurementRejection.UNSAFE_INVARIANT,
            ),
            MeasurementRejectionCase(
                "invalidMeasurementToken",
                replaceOnce(text, "measurement_id=measurement.host.v1", "measurement_id=bad!id"),
                ArKernelMeasurementRejection.INVALID_TOKEN,
            ),
            MeasurementRejectionCase(
                "invalidDeviceToken",
                replaceOnce(text, "device_token=phone.redmi.v1", "device_token=bad!id"),
                ArKernelMeasurementRejection.INVALID_TOKEN,
            ),
            MeasurementRejectionCase(
                "invalidCampaignToken",
                replaceOnce(text, "campaign_id=campaign.host.v1", "campaign_id=bad!id"),
                ArKernelMeasurementRejection.INVALID_TOKEN,
            ),
            MeasurementRejectionCase(
                "invalidDeviceClass",
                replaceOnce(text, "device_class=full", "device_class=auto"),
                ArKernelMeasurementRejection.INVALID_DEVICE_CLASS,
            ),
            MeasurementRejectionCase(
                "invalidSceneCount",
                replaceOnce(text, "scene_count=9", "scene_count=maybe"),
                ArKernelMeasurementRejection.INVALID_VALUE,
            ),
            MeasurementRejectionCase(
                "sceneCountZero",
                replaceOnce(text, "scene_count=9", "scene_count=0"),
                ArKernelMeasurementRejection.INVALID_COUNT,
            ),
            MeasurementRejectionCase(
                "sceneCountTooLarge",
                replaceOnce(text, "scene_count=9", "scene_count=17"),
                ArKernelMeasurementRejection.INVALID_COUNT,
            ),
            MeasurementRejectionCase(
                "invalidQuestionCount",
                replaceOnce(text, "question_count=10", "question_count=9"),
                ArKernelMeasurementRejection.INVALID_VALUE,
            ),
            MeasurementRejectionCase(
                "keyOrder",
                replaceLine(text, 1, "measurement_id=measurement.host.v1"),
                ArKernelMeasurementRejection.INVALID_SCHEMA,
            ),
            MeasurementRejectionCase(
                "spaceInMetadata",
                replaceOnce(text, "version=1", "version=1 x"),
                ArKernelMeasurementRejection.INVALID_SCHEMA,
            ),
            MeasurementRejectionCase(
                "lineTooLong",
                replaceLine(text, 5, "x".repeat(257)),
                ArKernelMeasurementRejection.LINE_TOO_LONG,
            ),
            MeasurementRejectionCase(
                "blankLine",
                replaceLine(text, 5, ""),
                ArKernelMeasurementRejection.EMPTY_LINE,
            ),
            MeasurementRejectionCase(
                "rowNotScene",
                replaceLine(text, FIRST_ROW_INDEX, "other=1"),
                ArKernelMeasurementRejection.INVALID_ROW,
            ),
            MeasurementRejectionCase(
                "rowFieldCount",
                replaceLine(text, FIRST_ROW_INDEX, row0 + "|extra"),
                ArKernelMeasurementRejection.INVALID_ROW,
            ),
            MeasurementRejectionCase(
                "rowIndexSkip",
                replaceOnce(text, "scene=1|outdoor_scale", "scene=9|outdoor_scale"),
                ArKernelMeasurementRejection.INVALID_ORDER,
            ),
            MeasurementRejectionCase(
                "invalidKind",
                replaceOnce(text, "|indoor_scale|", "|magic_scene|"),
                ArKernelMeasurementRejection.INVALID_KIND,
            ),
            MeasurementRejectionCase(
                "invalidSceneToken",
                replaceOnce(text, "|scene.01|", "|bad!id|"),
                ArKernelMeasurementRejection.INVALID_TOKEN,
            ),
            MeasurementRejectionCase(
                "invalidDigest",
                replaceOnce(text, DIGESTS[0], "Z".repeat(64)),
                ArKernelMeasurementRejection.INVALID_DIGEST,
            ),
            MeasurementRejectionCase(
                "uppercaseDigest",
                replaceOnce(text, DIGESTS[0], DIGESTS[0].uppercase()),
                ArKernelMeasurementRejection.INVALID_DIGEST,
            ),
            MeasurementRejectionCase(
                "invalidCondition",
                replaceOnce(text, "|indoor|true|false", "|rainy|true|false"),
                ArKernelMeasurementRejection.INVALID_CONDITION,
            ),
            MeasurementRejectionCase(
                "unconfirmedScene",
                replaceOnce(text, "|indoor|true|false", "|indoor|false|false"),
                ArKernelMeasurementRejection.UNCONFIRMED_SCENE,
            ),
            MeasurementRejectionCase(
                "invalidConfirmation",
                replaceOnce(text, "|indoor|true|false", "|indoor|maybe|false"),
                ArKernelMeasurementRejection.INVALID_BOOLEAN,
            ),
            MeasurementRejectionCase(
                "invalidHeldOut",
                replaceOnce(text, "|indoor|true|false", "|indoor|true|maybe"),
                ArKernelMeasurementRejection.INVALID_BOOLEAN,
            ),
            MeasurementRejectionCase(
                "duplicateScene",
                replaceOnce(text, "|scene.02|", "|scene.01|"),
                ArKernelMeasurementRejection.DUPLICATE_SCENE,
            ),
            MeasurementRejectionCase(
                "duplicateDigest",
                replaceOnce(text, DIGESTS[1], DIGESTS[0]),
                ArKernelMeasurementRejection.DUPLICATE_DIGEST,
            ),
            MeasurementRejectionCase(
                "noHeldOut",
                replaceOnce(text, "|outdoor|true|true", "|outdoor|true|false"),
                ArKernelMeasurementRejection.HELD_OUT_REQUIRED,
            ),
            MeasurementRejectionCase(
                "missingEnd",
                replaceLine(text, END_INDEX, "STOP"),
                ArKernelMeasurementRejection.INVALID_SCHEMA,
            ),
            MeasurementRejectionCase(
                "extraLine",
                text + "\nextra=1",
                ArKernelMeasurementRejection.INVALID_SCHEMA,
            ),
            MeasurementRejectionCase(
                "extraFinalSeparator",
                text + "\n\n",
                ArKernelMeasurementRejection.EMPTY_LINE,
            ),
        )
        assertEquals(48, cases.size)
        for (case in cases) {
            val result = ArKernelMeasurementAdapter.parse(case.text)
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
        val bundle = sampleBundle()
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(scaleBarRequired = false)
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
            bundle.copy(rows = bundle.rows.map { row ->
                row.copy(heldOut = false)
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelMeasurementSceneRow(
                index = 0,
                kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                sceneId = "scene.01",
                calibrationDigest = DIGESTS[0],
                condition = ArKernelMeasurementCondition.INDOOR,
                userConfirmed = false,
                heldOut = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelMeasurementSceneRow(
                index = 0,
                kind = ArKernelCampaignSceneKind.INDOOR_SCALE,
                sceneId = "scene.01",
                calibrationDigest = DIGESTS[0].uppercase(),
                condition = ArKernelMeasurementCondition.INDOOR,
                userConfirmed = true,
                heldOut = true,
            )
        }
    }

    private fun sampleBundle(): ArKernelMeasurementBundle {
        val rows = KINDS.indices.map { index ->
            ArKernelMeasurementSceneRow(
                index = index,
                kind = KINDS[index],
                sceneId = "scene." +
                    (index + 1).toString().padStart(2, '0'),
                calibrationDigest = DIGESTS[index],
                condition = CONDITIONS[index],
                userConfirmed = true,
                heldOut = KINDS[index] ==
                    ArKernelCampaignSceneKind.HELD_OUT,
            )
        }
        return ArKernelMeasurementBundle(
            measurementId = "measurement.host.v1",
            deviceToken = "phone.redmi.v1",
            deviceClass = ArKernelAdaptiveDeviceClass.FULL,
            campaignId = "campaign.host.v1",
            rows = rows,
        )
    }

    private fun calibrationText(index: Int): String {
        val session = "calib.run1.s" +
            index.toString().padStart(2, '0')
        val observation = "obs-" +
            index.toString().padStart(2, '0')
        return listOf(
            "ARKCALIB1",
            "version=1",
            "session_id=$session",
            "device_token=phone.redmi.v1",
            "capture_opt_in=true",
            "pose_capture_opt_in=true",
            "images_disabled=true",
            "automatic_calibration_disabled=true",
            "automatic_upload_disabled=true",
            "observation_count=1",
            "pose_count=0",
            "asset_extent_m=2",
            "quality_grade=unverified",
            "observation=0|$observation|scale_bar|" +
                "0.297|0.299|0.001|true",
            "END",
        ).joinToString("\n")
    }

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
}
