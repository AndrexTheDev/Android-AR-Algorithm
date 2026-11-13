package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class AdaptiveRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelAdaptiveRejection,
)

class ArKernelDeviceAdaptiveProfileTest {
    @Test
    fun sampleProfileRoundTripsAndReportsOnlyStructuralFacts() {
        val bundle = sampleBundle()
        val text = ArKernelDeviceAdaptiveProfileAdapter.encode(bundle)
        val result = ArKernelDeviceAdaptiveProfileAdapter.parse(text)
        assertTrue(result.accepted)
        assertEquals(ArKernelAdaptiveRejection.NONE, result.rejection)
        assertEquals(bundle, result.bundle)
        assertEquals(text,
            ArKernelDeviceAdaptiveProfileAdapter.encode(result.bundle!!))
        val report = ArKernelDeviceAdaptiveProfileAdapter
            .summarize(result.bundle!!)
        assertEquals(13, report.stepCount)
        assertEquals(13, report.distinctTargets)
        assertEquals(13, report.pendingCount)
        assertEquals(5, report.scaleBarCount)
        assertEquals(1, report.surveyTargetCount)
        assertEquals(1, report.heldOutReviewCount)
        assertFalse(report.allStepsAddressed)
        assertFalse(report.profileComplete)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.bundle!!.qualityGrade)
    }

    @Test
    fun profileCompleteIsNotAQualityGrade() {
        val addressed = sampleBundle().copy(
            steps = sampleBundle().steps.map {
                it.copy(
                    status =
                    ArKernelAdaptiveStepStatus.HOST_CONFIRMED)
            },
        )
        val report = ArKernelDeviceAdaptiveProfileAdapter
            .summarize(addressed)
        assertTrue(report.allStepsAddressed)
        assertTrue(report.profileComplete)
        assertEquals(13, report.hostConfirmedCount)
        assertEquals("unverified", report.qualityGrade)
        val weak = sampleBundle().copy(
            steps = listOf(
                sampleBundle().steps[0].copy(
                    status =
                    ArKernelAdaptiveStepStatus.HOST_CONFIRMED),
                sampleBundle().steps[1].copy(
                    status =
                    ArKernelAdaptiveStepStatus.SKIPPED),
                sampleBundle().steps[2],
            ),
        )
        assertFalse(
            ArKernelDeviceAdaptiveProfileAdapter
                .summarize(weak).profileComplete)
    }

    @Test
    fun deviceAdaptationIsDeterministicBoundedAndConservative() {
        val cases = listOf(
            "full" to ArKernelAdaptiveDeviceFacts(
                1, 3, true, true, true, false, 24, false, 35),
            "limited" to ArKernelAdaptiveDeviceFacts(
                0, 2, true, false, false, false, 12, true, 33),
            "legacy" to ArKernelAdaptiveDeviceFacts(
                2, 1, false, false, false, false, 8, true, 29),
            "unknown" to ArKernelAdaptiveDeviceFacts(
                -1, 0, false, false, false, false, 0, true, 26),
        )
        var fullSteps = 0
        var legacySteps = 0
        for ((label, facts) in cases) {
            val selection =
                ArKernelDeviceAdaptiveProfileAdapter.selectProfile(facts)
            assertEquals(label, selection.deviceClass.wireName())
            assertTrue(selection.steps.size <= 16)
            assertTrue(selection.steps.withIndex().all {
                it.value.index == it.index
            })
            assertTrue(selection.steps.all {
                it.status == ArKernelAdaptiveStepStatus.PENDING
            })
            assertEquals(selection.steps.size,
                selection.steps.map { it.targetToken }.toSet().size)
            val again =
                ArKernelDeviceAdaptiveProfileAdapter.selectProfile(facts)
            assertEquals(selection, again)
            assertTrue(selection.reasonToken.length <= 64)
            if (label == "full") fullSteps = selection.steps.size
            if (label == "legacy") legacySteps = selection.steps.size
        }
        assertTrue(fullSteps > legacySteps)
        assertEquals(
            ArKernelAdaptiveIntrinsicsState.UNAVAILABLE,
            ArKernelDeviceAdaptiveProfileAdapter.selectProfile(
                cases.last().second).intrinsicsState)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val bundle = sampleBundle()
        val text = ArKernelDeviceAdaptiveProfileAdapter.encode(bundle)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result =
                ArKernelDeviceAdaptiveProfileAdapter.parse(variant)
            assertTrue("variant rejected: ${result.detail}",
                result.accepted)
            assertEquals(bundle, result.bundle)
        }
        assertEquals(ArKernelAdaptiveRejection.EMPTY_LINE,
            ArKernelDeviceAdaptiveProfileAdapter
                .parse(text + "\n\n").rejection)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = ArKernelDeviceAdaptiveProfileAdapter.encode(
            sampleBundle())
        val cases = listOf(
            AdaptiveRejectionCase("inputTooLarge",
                "x".repeat(8_193),
                ArKernelAdaptiveRejection.INPUT_TOO_LARGE),
            AdaptiveRejectionCase("empty",
                "",
                ArKernelAdaptiveRejection.EMPTY_PROFILE),
            AdaptiveRejectionCase("badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelAdaptiveRejection.INVALID_HEADER),
            AdaptiveRejectionCase("headerWithSpace",
                replaceLine(text, 0, "ARKADAPT1 x"),
                ArKernelAdaptiveRejection.INVALID_HEADER),
            AdaptiveRejectionCase("badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelAdaptiveRejection.INVALID_VALUE),
            AdaptiveRejectionCase("assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified",
                    "quality_grade=verified"),
                ArKernelAdaptiveRejection.INVALID_QUALITY),
            AdaptiveRejectionCase("imagesEnabled",
                replaceOnce(text, "images_disabled=true",
                    "images_disabled=false"),
                ArKernelAdaptiveRejection.UNSAFE_INVARIANT),
            AdaptiveRejectionCase(
                "automaticProfileSelectionDisabled",
                replaceOnce(text, "automatic_profile_selection=true",
                    "automatic_profile_selection=false"),
                ArKernelAdaptiveRejection.UNSAFE_INVARIANT),
            AdaptiveRejectionCase(
                "metricCalibrationRequiresUserFalse",
                replaceOnce(text,
                    "metric_calibration_requires_user=true",
                    "metric_calibration_requires_user=false"),
                ArKernelAdaptiveRejection.UNSAFE_INVARIANT),
            AdaptiveRejectionCase("physicalTargetRequiredFalse",
                replaceOnce(text, "physical_target_required=true",
                    "physical_target_required=false"),
                ArKernelAdaptiveRejection.UNSAFE_INVARIANT),
            AdaptiveRejectionCase("automaticUploadEnabled",
                replaceOnce(text, "automatic_upload_disabled=true",
                    "automatic_upload_disabled=false"),
                ArKernelAdaptiveRejection.UNSAFE_INVARIANT),
            AdaptiveRejectionCase("invalidBoolean",
                replaceOnce(text, "gyro_available=true",
                    "gyro_available=maybe"),
                ArKernelAdaptiveRejection.INVALID_BOOLEAN),
            AdaptiveRejectionCase("invalidBooleanInvariant",
                replaceOnce(text, "images_disabled=true",
                    "images_disabled=maybe"),
                ArKernelAdaptiveRejection.INVALID_BOOLEAN),
            AdaptiveRejectionCase("invalidSessionToken",
                replaceOnce(text, "session_id=adapt.session.v1",
                    "session_id=bad!id"),
                ArKernelAdaptiveRejection.INVALID_TOKEN),
            AdaptiveRejectionCase("invalidDeviceToken",
                replaceOnce(text, "device_token=device.host.v1",
                    "device_token=bad!id"),
                ArKernelAdaptiveRejection.INVALID_TOKEN),
            AdaptiveRejectionCase("invalidDeviceClass",
                replaceOnce(text, "device_class=full",
                    "device_class=premium"),
                ArKernelAdaptiveRejection.INVALID_DEVICE_CLASS),
            AdaptiveRejectionCase("invalidIntrinsicsState",
                replaceOnce(text, "intrinsics_state=untrusted",
                    "intrinsics_state=verified"),
                ArKernelAdaptiveRejection.INVALID_INTRINSICS_STATE),
            AdaptiveRejectionCase("zeroSteps",
                replaceOnce(text, "step_count=13", "step_count=0"),
                ArKernelAdaptiveRejection.INVALID_COUNT),
            AdaptiveRejectionCase("tooManySteps",
                replaceOnce(text, "step_count=13", "step_count=17"),
                ArKernelAdaptiveRejection.INVALID_COUNT),
            AdaptiveRejectionCase("invalidStepCountValue",
                replaceOnce(text, "step_count=13", "step_count=x"),
                ArKernelAdaptiveRejection.INVALID_COUNT),
            AdaptiveRejectionCase("lineCountMismatch",
                replaceOnce(text, "step_count=13", "step_count=12"),
                ArKernelAdaptiveRejection.INVALID_SCHEMA),
            AdaptiveRejectionCase("keyOrder",
                replaceLine(text, 1, "session_id=adapt.session.v1"),
                ArKernelAdaptiveRejection.INVALID_SCHEMA),
            AdaptiveRejectionCase("spaceInMetadata",
                replaceOnce(text, "version=1", "version=1 x"),
                ArKernelAdaptiveRejection.INVALID_SCHEMA),
            AdaptiveRejectionCase("lineTooLong",
                replaceLine(text, 16, "x".repeat(257)),
                ArKernelAdaptiveRejection.LINE_TOO_LONG),
            AdaptiveRejectionCase("blankLine",
                replaceLine(text, 16, ""),
                ArKernelAdaptiveRejection.EMPTY_LINE),
            AdaptiveRejectionCase("extraFinalSeparator",
                text + "\n\n",
                ArKernelAdaptiveRejection.EMPTY_LINE),
            AdaptiveRejectionCase("missingEnd",
                replaceLine(text, 29, "STOP"),
                ArKernelAdaptiveRejection.INVALID_SCHEMA),
            AdaptiveRejectionCase("wrongStepPrefix",
                replaceLine(text, 16, "stepx=0|only"),
                ArKernelAdaptiveRejection.INVALID_SCHEMA),
            AdaptiveRejectionCase("stepFieldCount",
                replaceLine(text, 16, "step=0|only"),
                ArKernelAdaptiveRejection.INVALID_STEP_FIELDS),
            AdaptiveRejectionCase("invalidStepIndex",
                stepField(text, 16, 0, "x"),
                ArKernelAdaptiveRejection.INVALID_VALUE),
            AdaptiveRejectionCase("stepOrder",
                replaceOnce(text, "step=0|", "step=1|"),
                ArKernelAdaptiveRejection.INVALID_ORDER),
            AdaptiveRejectionCase("invalidOperator",
                stepField(text, 16, 1, "auto_calibrate"),
                ArKernelAdaptiveRejection.INVALID_OPERATOR),
            AdaptiveRejectionCase("invalidStatus",
                stepField(text, 16, 3, "verified"),
                ArKernelAdaptiveRejection.INVALID_STATUS),
            AdaptiveRejectionCase("invalidStepTarget",
                stepField(text, 16, 2, "bad!id"),
                ArKernelAdaptiveRejection.INVALID_TOKEN),
            AdaptiveRejectionCase("duplicateTarget",
                stepField(text, 17, 2, "device.catalog"),
                ArKernelAdaptiveRejection.DUPLICATE_TARGET),
        )
        assertEquals(35, cases.size)
        for (case in cases) {
            val result =
                ArKernelDeviceAdaptiveProfileAdapter.parse(case.text)
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
        val bundle = sampleBundle()
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(automaticProfileSelection = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(metricCalibrationRequiresUser = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(physicalTargetRequired = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(steps = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(steps = listOf(
                bundle.steps[0],
                bundle.steps[1].copy(targetToken =
                    bundle.steps[0].targetToken),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelAdaptiveDeviceFacts(
                9, 3, true, true, true, false, 24, false, 35)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelAdaptiveStep(
                0, ArKernelAdaptiveOperator.SCALE_BAR, "bad!id")
        }
    }

    private fun sampleBundle(): ArKernelAdaptiveBundle {
        val selection =
            ArKernelDeviceAdaptiveProfileAdapter.selectProfile(
                ArKernelAdaptiveDeviceFacts(
                    1, 3, true, true, true, false, 24, false, 35))
        return ArKernelAdaptiveBundle(
            sessionId = "adapt.session.v1",
            deviceToken = "device.host.v1",
            deviceClass = selection.deviceClass,
            intrinsicsState = selection.intrinsicsState,
            gyroAvailable = true,
            rotationVectorAvailable = true,
            thermalMonitoringAvailable = true,
            steps = selection.steps,
        )
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

    private fun stepField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.split("\n").toMutableList()
        val fields = lines[lineIndex].removePrefix("step=")
            .split("|").toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = "step=" + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
