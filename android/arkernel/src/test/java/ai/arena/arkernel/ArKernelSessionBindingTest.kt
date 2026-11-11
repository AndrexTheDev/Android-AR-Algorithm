package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PROFILE_DIGEST =
    "6cdd7b487fe4e698cdacccf1f1ff2937178be4c65169501bc7ba085f5ce50ad5"
private const val EVIDENCE_DIGEST =
    "d2c8951c3513069235693b539d4d7f2b188a5c8b35ddd0909d42e19eb6b57190"
private const val PLAN_DIGEST =
    "6e0e2f4a8b59a52c69cdf13d7c189b7fa7f4dcb7c6b008bfddb326717261431f"

private data class BindRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelSessionBindingRejection,
)

class ArKernelSessionBindingTest {
    @Test
    fun createAndVerifyBindsThreeCanonicalSources() {
        val profile = sampleProfileText()
        val evidence = sampleEvidenceText()
        val plan = samplePlanText()
        val result = ArKernelSessionBindingAdapter.create(
            "bind.session.v1", profile, evidence, plan)
        assertTrue(result.detail, result.accepted)
        assertEquals(ArKernelSessionBindingRejection.NONE,
            result.rejection)
        val bundle = result.bundle!!
        assertEquals("device.host.v1", bundle.deviceToken)
        assertEquals("campaign.host.v1", bundle.campaignId)
        assertEquals(PROFILE_DIGEST, bundle.profileDigest)
        assertEquals(EVIDENCE_DIGEST, bundle.evidenceDigest)
        assertEquals(PLAN_DIGEST, bundle.planDigest)
        assertEquals(9, bundle.sceneCount)
        assertEquals(10, bundle.questionCount)
        assertEquals(3, bundle.observationCount)
        assertEquals(2, bundle.poseCount)
        val report = result.report!!
        assertTrue(report.allSourcesParsed)
        assertTrue(report.digestsMatch)
        assertTrue(report.deviceTokenMatch)
        assertTrue(report.campaignIdMatch)
        assertTrue(report.countsMatch)
        assertTrue(report.deviceClassMatch)
        assertTrue(report.bindingConsistent)
        assertTrue(report.evidenceReady)
        assertFalse(report.campaignEvidenceComplete)
        assertTrue(report.manualUploadOnly)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", bundle.qualityGrade)
        val text = ArKernelSessionBindingAdapter.encode(bundle)
        val parsed = ArKernelSessionBindingAdapter.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(bundle, parsed.bundle)
        assertEquals(
            ArKernelSessionBindingAdapter.sha256Hex(profile),
            PROFILE_DIGEST)
    }

    @Test
    fun mismatchedOrTamperedSourcesFailClosed() {
        val profile = sampleProfileText()
        val evidence = sampleEvidenceText()
        val plan = samplePlanText()
        val good = ArKernelSessionBindingAdapter.create(
            "bind.session.v1", profile, evidence, plan)
        assertTrue(good.accepted)
        val bundle = good.bundle!!
        val badToken = ArKernelSessionBindingAdapter.create(
            "bind.session.v1", profile,
            evidence.replace(
                "device_token=device.host.v1",
                "device_token=other.phone.v1"),
            plan)
        assertFalse(badToken.accepted)
        assertEquals(
            ArKernelSessionBindingRejection.DEVICE_TOKEN_MISMATCH,
            badToken.rejection)
        val badClass = ArKernelSessionBindingAdapter.create(
            "bind.session.v1", profile, evidence,
            plan.replace("device_class=full", "device_class=legacy"))
        assertFalse(badClass.accepted)
        assertEquals(
            ArKernelSessionBindingRejection.DEVICE_CLASS_MISMATCH,
            badClass.rejection)
        for ((label, broken, reason) in listOf(
            Triple("profile", "OTHER1\n" + profile,
                ArKernelSessionBindingRejection.INVALID_PROFILE),
            Triple("evidence", "OTHER1\n" + evidence,
                ArKernelSessionBindingRejection.INVALID_EVIDENCE),
            Triple("plan", "OTHER1\n" + plan,
                ArKernelSessionBindingRejection.INVALID_PLAN),
        )) {
            val actual = when (label) {
                "profile" -> ArKernelSessionBindingAdapter.create(
                    "bind.session.v1", broken, evidence, plan)
                "evidence" -> ArKernelSessionBindingAdapter.create(
                    "bind.session.v1", profile, broken, plan)
                else -> ArKernelSessionBindingAdapter.create(
                    "bind.session.v1", profile, evidence, broken)
            }
            assertFalse(label, actual.accepted)
            assertEquals(label, reason, actual.rejection)
        }
        val tamperedDigest = ArKernelSessionBindingAdapter.verify(
            bundle.copy(profileDigest = "0".repeat(64)),
            profile, evidence, plan)
        assertFalse(tamperedDigest.digestsMatch)
        assertFalse(tamperedDigest.bindingConsistent)
        val tamperedCounts = ArKernelSessionBindingAdapter.verify(
            bundle.copy(sceneCount = 8),
            profile, evidence, plan)
        assertFalse(tamperedCounts.countsMatch)
        assertFalse(tamperedCounts.bindingConsistent)
        val unparsed = ArKernelSessionBindingAdapter.verify(
            bundle, "OTHER1\n" + profile, evidence, plan)
        assertFalse(unparsed.allSourcesParsed)
        assertFalse(unparsed.bindingConsistent)
        assertFalse(unparsed.evidenceReady)
        assertEquals("unverified", unparsed.qualityGrade)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val bundle = ArKernelSessionBindingAdapter.create(
            "bind.session.v1", sampleProfileText(),
            sampleEvidenceText(), samplePlanText()).bundle!!
        val text = ArKernelSessionBindingAdapter.encode(bundle)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val parsed =
                ArKernelSessionBindingAdapter.parse(variant)
            assertTrue(parsed.detail, parsed.accepted)
            assertEquals(bundle, parsed.bundle)
        }
        assertEquals(
            ArKernelSessionBindingRejection.EMPTY_LINE,
            ArKernelSessionBindingAdapter
                .parse(text + "\n\n").rejection)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = ArKernelSessionBindingAdapter.encode(
            ArKernelSessionBindingAdapter.create(
                "bind.session.v1", sampleProfileText(),
                sampleEvidenceText(), samplePlanText()).bundle!!)
        val cases = listOf(
            BindRejectionCase(
                "inputTooLarge",
                "x".repeat(8_193),
                ArKernelSessionBindingRejection.INPUT_TOO_LARGE,
            ),
            BindRejectionCase(
                "empty",
                "",
                ArKernelSessionBindingRejection.EMPTY_BINDING,
            ),
            BindRejectionCase(
                "badHeader",
                replaceLine(
                        text,
                        0,
                        "OTHER1",
                    ),
                ArKernelSessionBindingRejection.INVALID_HEADER,
            ),
            BindRejectionCase(
                "headerWithSpace",
                replaceLine(
                        text,
                        0,
                        "ARKBIND1 x",
                    ),
                ArKernelSessionBindingRejection.INVALID_HEADER,
            ),
            BindRejectionCase(
                "badVersion",
                replaceOnce(
                        text,
                        "version=1",
                        "version=2",
                    ),
                ArKernelSessionBindingRejection.INVALID_VALUE,
            ),
            BindRejectionCase(
                "assessedQualityInput",
                replaceOnce(
                        text,
                        "quality_grade=unverified",
                        "quality_grade=verified",
                    ),
                ArKernelSessionBindingRejection.INVALID_QUALITY,
            ),
            BindRejectionCase(
                "invalidBoolean",
                replaceOnce(
                        text,
                        "images_disabled=true",
                        "images_disabled=maybe",
                    ),
                ArKernelSessionBindingRejection.INVALID_BOOLEAN,
            ),
            BindRejectionCase(
                "manualUploadOnlyFalse",
                replaceOnce(
                        text,
                        "manual_upload_only=true",
                        "manual_upload_only=false",
                    ),
                ArKernelSessionBindingRejection.UNSAFE_INVARIANT,
            ),
            BindRejectionCase(
                "imagesEnabled",
                replaceOnce(
                        text,
                        "images_disabled=true",
                        "images_disabled=false",
                    ),
                ArKernelSessionBindingRejection.UNSAFE_INVARIANT,
            ),
            BindRejectionCase(
                "automaticCalibrationEnabled",
                replaceOnce(
                        text,
                        "automatic_calibration_disabled=true",
                        "automatic_calibration_disabled=false",
                    ),
                ArKernelSessionBindingRejection.UNSAFE_INVARIANT,
            ),
            BindRejectionCase(
                "automaticUploadEnabled",
                replaceOnce(
                        text,
                        "automatic_upload_disabled=true",
                        "automatic_upload_disabled=false",
                    ),
                ArKernelSessionBindingRejection.UNSAFE_INVARIANT,
            ),
            BindRejectionCase(
                "signatureVerificationEnabled",
                replaceOnce(
                        text,
                        "signature_verification_disabled=true",
                        "signature_verification_disabled=false",
                    ),
                ArKernelSessionBindingRejection.UNSAFE_INVARIANT,
            ),
            BindRejectionCase(
                "portableProtocolFalse",
                replaceOnce(
                        text,
                        "portable_protocol=true",
                        "portable_protocol=false",
                    ),
                ArKernelSessionBindingRejection.UNSAFE_INVARIANT,
            ),
            BindRejectionCase(
                "invalidSessionToken",
                replaceOnce(
                        text,
                        "session_id=bind.session.v1",
                        "session_id=bad!id",
                    ),
                ArKernelSessionBindingRejection.INVALID_TOKEN,
            ),
            BindRejectionCase(
                "invalidDeviceToken",
                replaceOnce(
                        text,
                        "device_token=device.host.v1",
                        "device_token=bad!id",
                    ),
                ArKernelSessionBindingRejection.INVALID_TOKEN,
            ),
            BindRejectionCase(
                "invalidCampaignId",
                replaceOnce(
                        text,
                        "campaign_id=campaign.host.v1",
                        "campaign_id=bad!id",
                    ),
                ArKernelSessionBindingRejection.INVALID_TOKEN,
            ),
            BindRejectionCase(
                "invalidProfileDigest",
                replaceOnce(
                        text,
                        "profile_digest=" + PROFILE_DIGEST,
                        "profile_digest=ABC",
                    ),
                ArKernelSessionBindingRejection.INVALID_DIGEST,
            ),
            BindRejectionCase(
                "uppercaseDigest",
                replaceOnce(
                        text,
                        "plan_digest=" + PLAN_DIGEST,
                        "plan_digest=" + PLAN_DIGEST.uppercase(),
                    ),
                ArKernelSessionBindingRejection.INVALID_DIGEST,
            ),
            BindRejectionCase(
                "invalidEvidenceDigest",
                replaceOnce(
                        text,
                        "evidence_digest=" + EVIDENCE_DIGEST,
                        "evidence_digest=" + "z".repeat(64),
                    ),
                ArKernelSessionBindingRejection.INVALID_DIGEST,
            ),
            BindRejectionCase(
                "invalidDeviceClass",
                replaceOnce(
                        text,
                        "device_class=full",
                        "device_class=premium",
                    ),
                ArKernelSessionBindingRejection.INVALID_DEVICE_CLASS,
            ),
            BindRejectionCase(
                "zeroScenes",
                replaceOnce(
                        text,
                        "scene_count=9",
                        "scene_count=0",
                    ),
                ArKernelSessionBindingRejection.INVALID_COUNT,
            ),
            BindRejectionCase(
                "tooManyScenes",
                replaceOnce(
                        text,
                        "scene_count=9",
                        "scene_count=17",
                    ),
                ArKernelSessionBindingRejection.INVALID_COUNT,
            ),
            BindRejectionCase(
                "nonCanonicalQuestionCount",
                replaceOnce(
                        text,
                        "question_count=10",
                        "question_count=9",
                    ),
                ArKernelSessionBindingRejection.INVALID_COUNT,
            ),
            BindRejectionCase(
                "zeroObservations",
                replaceOnce(
                        text,
                        "observation_count=3",
                        "observation_count=0",
                    ),
                ArKernelSessionBindingRejection.INVALID_COUNT,
            ),
            BindRejectionCase(
                "tooManyObservations",
                replaceOnce(
                        text,
                        "observation_count=3",
                        "observation_count=17",
                    ),
                ArKernelSessionBindingRejection.INVALID_COUNT,
            ),
            BindRejectionCase(
                "tooManyPoses",
                replaceOnce(
                        text,
                        "pose_count=2",
                        "pose_count=33",
                    ),
                ArKernelSessionBindingRejection.INVALID_COUNT,
            ),
            BindRejectionCase(
                "invalidCountValue",
                replaceOnce(
                        text,
                        "pose_count=2",
                        "pose_count=x",
                    ),
                ArKernelSessionBindingRejection.INVALID_COUNT,
            ),
            BindRejectionCase(
                "keyOrder",
                replaceLine(
                        text,
                        1,
                        "session_id=bind.session.v1",
                    ),
                ArKernelSessionBindingRejection.INVALID_SCHEMA,
            ),
            BindRejectionCase(
                "spaceInMetadata",
                replaceOnce(
                        text,
                        "version=1",
                        "version=1 x",
                    ),
                ArKernelSessionBindingRejection.INVALID_SCHEMA,
            ),
            BindRejectionCase(
                "lineTooLong",
                replaceLine(
                        text,
                        5,
                        "x".repeat(257),
                    ),
                ArKernelSessionBindingRejection.LINE_TOO_LONG,
            ),
            BindRejectionCase(
                "blankLine",
                replaceLine(
                        text,
                        5,
                        "",
                    ),
                ArKernelSessionBindingRejection.EMPTY_LINE,
            ),
            BindRejectionCase(
                "extraFinalSeparator",
                text + "\n\n",
                ArKernelSessionBindingRejection.EMPTY_LINE,
            ),
            BindRejectionCase(
                "missingEnd",
                replaceLine(
                        text,
                        20,
                        "STOP",
                    ),
                ArKernelSessionBindingRejection.INVALID_SCHEMA,
            ),
            BindRejectionCase(
                "extraLine",
                text + "\nextra=1",
                ArKernelSessionBindingRejection.INVALID_SCHEMA,
            ),
        )
        assertEquals(34, cases.size)
        for (case in cases) {
            val result =
                ArKernelSessionBindingAdapter.parse(case.text)
            assertFalse("${case.name} unexpectedly accepted",
                result.accepted)
            assertNull("${case.name} leaked a partial bundle",
                result.bundle)
            assertEquals(case.name, case.reason, result.rejection)
            assertTrue("${case.name} lacked a rejection detail",
                result.detail.isNotBlank())
            assertTrue("${case.name} had a negative line number",
                result.lineNumber >= 0)
        }
    }

    @Test
    fun constructorsRejectUnsafeShapes() {
        val bundle = ArKernelSessionBindingAdapter.create(
            "bind.session.v1", sampleProfileText(),
            sampleEvidenceText(), samplePlanText()).bundle!!
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(manualUploadOnly = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(signatureVerificationDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(portableProtocol = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(profileDigest = "ABC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(sceneCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(questionCount = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(observationCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(poseCount = 33)
        }
    }

    private fun sampleProfileText(): String {
        val selection =
            ArKernelDeviceAdaptiveProfileAdapter.selectProfile(
                ArKernelAdaptiveDeviceFacts(
                    1, 3, true, true, true, false, 24, false, 35))
        val bundle = ArKernelAdaptiveBundle(
            sessionId = "adapt.session.v1",
            deviceToken = "device.host.v1",
            deviceClass = selection.deviceClass,
            intrinsicsState = selection.intrinsicsState,
            gyroAvailable = true,
            rotationVectorAvailable = true,
            thermalMonitoringAvailable = true,
            steps = selection.steps,
        )
        return ArKernelDeviceAdaptiveProfileAdapter.encode(bundle)
    }

    private fun sampleEvidenceText(): String {
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
            deviceToken = "device.host.v1",
            observations = observations,
            poses = poses,
            assetExtentMeters = 2.0,
        )
        return ArKernelCalibrationEvidenceAdapter.encode(bundle)
    }

    private fun samplePlanText(): String =
        ArKernelCampaignPlanAdapter.encode(
            ArKernelCampaignPlanAdapter.canonicalPlan(
                "campaign.host.v1", "device.host.v1",
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
}
