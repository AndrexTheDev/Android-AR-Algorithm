package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class RetentionRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelRetentionRunRejection,
)

class ArKernelRetentionRunLedgerTest {
    @Test
    fun sampleLedgerRoundTripsAndReportsOnlyStructuralFacts() {
        val bundle = sampleBundle()
        val text = ArKernelRetentionRunLedgerAdapter.encode(bundle)
        val result = ArKernelRetentionRunLedgerAdapter.parse(text)
        assertTrue(result.accepted)
        assertEquals(ArKernelRetentionRunRejection.NONE,
            result.rejection)
        assertEquals(bundle, result.bundle)
        assertEquals(text,
            ArKernelRetentionRunLedgerAdapter.encode(
                result.bundle!!))
        val report = ArKernelRetentionRunLedgerAdapter
            .summarize(result.bundle!!)
        assertTrue(report.dialogShown)
        assertTrue(report.deleteKeyAvailable)
        assertFalse(report.deleteKeySelected)
        assertEquals(ArKernelRetentionLedgerAction.NONE,
            report.actionKind)
        assertFalse(report.actionApplied)
        assertTrue(report.explicitConfirmationRequired)
        assertTrue(report.dryRun)
        assertTrue(report.physicalValidationDisabled)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.bundle!!.qualityGrade)
    }

    @Test
    fun anAppliedDeletionRunIsStillOnlyADryRun() {
        val bundle = sampleBundle().copy(
            selectedMode =
                ArKernelRetentionLedgerMode.DELETE_AFTER_PUBLICATION,
            deleteKeySelected = true,
            actionKind =
                ArKernelRetentionLedgerAction.AFTER_PUBLICATION,
            actionApplied = true,
        )
        val text = ArKernelRetentionRunLedgerAdapter.encode(bundle)
        val result = ArKernelRetentionRunLedgerAdapter.parse(text)
        assertTrue(result.accepted)
        val report = ArKernelRetentionRunLedgerAdapter
            .summarize(result.bundle!!)
        assertTrue(report.actionApplied)
        assertTrue(report.deleteKeySelected)
        assertTrue(report.dryRun)
        assertTrue(report.physicalValidationDisabled)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val bundle = sampleBundle()
        val text = ArKernelRetentionRunLedgerAdapter.encode(bundle)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result =
                ArKernelRetentionRunLedgerAdapter.parse(variant)
            assertTrue(result.detail, result.accepted)
            assertEquals(bundle, result.bundle)
        }
        assertEquals(ArKernelRetentionRunRejection.EMPTY_LINE,
            ArKernelRetentionRunLedgerAdapter
                .parse(text + "\n\n").rejection)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = ArKernelRetentionRunLedgerAdapter.encode(
            sampleBundle())
        val cases = listOf(
            RetentionRejectionCase(
                "inputTooLarge",
                "x".repeat(8_193),
                ArKernelRetentionRunRejection.INPUT_TOO_LARGE,
            ),
            RetentionRejectionCase(
                "empty",
                "",
                ArKernelRetentionRunRejection.EMPTY_LEDGER,
            ),
            RetentionRejectionCase(
                "badHeader",
                replaceLine(
                        text,
                        0,
                        "OTHER1",
                    ),
                ArKernelRetentionRunRejection.INVALID_HEADER,
            ),
            RetentionRejectionCase(
                "headerWithSpace",
                replaceLine(
                        text,
                        0,
                        "ARKRETENT1 x",
                    ),
                ArKernelRetentionRunRejection.INVALID_HEADER,
            ),
            RetentionRejectionCase(
                "badVersion",
                replaceOnce(
                        text,
                        "version=1",
                        "version=2",
                    ),
                ArKernelRetentionRunRejection.INVALID_VALUE,
            ),
            RetentionRejectionCase(
                "assessedQualityInput",
                replaceOnce(
                        text,
                        "quality_grade=unverified",
                        "quality_grade=verified",
                    ),
                ArKernelRetentionRunRejection.INVALID_QUALITY,
            ),
            RetentionRejectionCase(
                "invalidBoolean",
                replaceOnce(
                        text,
                        "dialog_shown=true",
                        "dialog_shown=maybe",
                    ),
                ArKernelRetentionRunRejection.INVALID_BOOLEAN,
            ),
            RetentionRejectionCase(
                "explicitConfirmationFalse",
                replaceOnce(
                        text,
                        "explicit_confirmation_required=true",
                        "explicit_confirmation_required=false",
                    ),
                ArKernelRetentionRunRejection.UNSAFE_INVARIANT,
            ),
            RetentionRejectionCase(
                "dryRunFalse",
                replaceOnce(
                        text,
                        "dry_run=true",
                        "dry_run=false",
                    ),
                ArKernelRetentionRunRejection.UNSAFE_INVARIANT,
            ),
            RetentionRejectionCase(
                "physicalValidationEnabled",
                replaceOnce(
                        text,
                        "physical_validation_disabled=true",
                        "physical_validation_disabled=false",
                    ),
                ArKernelRetentionRunRejection.UNSAFE_INVARIANT,
            ),
            RetentionRejectionCase(
                "invalidRunToken",
                replaceOnce(
                        text,
                        "run_id=retention.host.v1",
                        "run_id=bad!id",
                    ),
                ArKernelRetentionRunRejection.INVALID_TOKEN,
            ),
            RetentionRejectionCase(
                "invalidDeviceToken",
                replaceOnce(
                        text,
                        "device_token=device.host.v1",
                        "device_token=bad!id",
                    ),
                ArKernelRetentionRunRejection.INVALID_TOKEN,
            ),
            RetentionRejectionCase(
                "invalidLocale",
                replaceOnce(
                        text,
                        "locale_tag=de-DE",
                        "locale_tag=de_DE",
                    ),
                ArKernelRetentionRunRejection.INVALID_LOCALE,
            ),
            RetentionRejectionCase(
                "invalidMode",
                replaceOnce(
                        text,
                        "selected_mode=keep",
                        "selected_mode=auto_delete",
                    ),
                ArKernelRetentionRunRejection.INVALID_MODE,
            ),
            RetentionRejectionCase(
                "invalidAction",
                replaceOnce(
                        text,
                        "action_kind=none",
                        "action_kind=auto",
                    ),
                ArKernelRetentionRunRejection.INVALID_ACTION,
            ),
            RetentionRejectionCase(
                "appliedNone",
                replaceOnce(
                        text,
                        "action_applied=false",
                        "action_applied=true",
                    ),
                ArKernelRetentionRunRejection.APPLIED_NONE,
            ),
            RetentionRejectionCase(
                "deleteKeyWithKeep",
                replaceOnce(
                        text,
                        "delete_key_selected=false",
                        "delete_key_selected=true",
                    ),
                ArKernelRetentionRunRejection.DELETE_KEY_WITH_KEEP,
            ),
            RetentionRejectionCase(
                "keyOrder",
                replaceLine(
                        text,
                        1,
                        "run_id=retention.host.v1",
                    ),
                ArKernelRetentionRunRejection.INVALID_SCHEMA,
            ),
            RetentionRejectionCase(
                "spaceInMetadata",
                replaceOnce(
                        text,
                        "version=1",
                        "version=1 x",
                    ),
                ArKernelRetentionRunRejection.INVALID_SCHEMA,
            ),
            RetentionRejectionCase(
                "lineTooLong",
                replaceLine(
                        text,
                        5,
                        "x".repeat(257),
                    ),
                ArKernelRetentionRunRejection.LINE_TOO_LONG,
            ),
            RetentionRejectionCase(
                "blankLine",
                replaceLine(
                        text,
                        5,
                        "",
                    ),
                ArKernelRetentionRunRejection.EMPTY_LINE,
            ),
            RetentionRejectionCase(
                "extraFinalSeparator",
                text + "\n\n",
                ArKernelRetentionRunRejection.EMPTY_LINE,
            ),
            RetentionRejectionCase(
                "missingEnd",
                replaceLine(
                        text,
                        15,
                        "STOP",
                    ),
                ArKernelRetentionRunRejection.INVALID_SCHEMA,
            ),
            RetentionRejectionCase(
                "extraLine",
                text + "\nextra=1",
                ArKernelRetentionRunRejection.INVALID_SCHEMA,
            ),
        )
        assertEquals(24, cases.size)
        for (case in cases) {
            val result =
                ArKernelRetentionRunLedgerAdapter.parse(case.text)
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
            bundle.copy(explicitConfirmationRequired = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(dryRun = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(physicalValidationDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(actionApplied = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(deleteKeySelected = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(localeTag = "de_DE")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bundle.copy(runId = "bad!id")
        }
    }

    private fun sampleBundle(): ArKernelRetentionRunBundle =
        ArKernelRetentionRunBundle(
            runId = "retention.host.v1",
            deviceToken = "device.host.v1",
            localeTag = "de-DE",
            selectedMode = ArKernelRetentionLedgerMode.KEEP,
            deleteKeyAvailable = true,
            deleteKeySelected = false,
            dialogShown = true,
            actionKind = ArKernelRetentionLedgerAction.NONE,
            actionApplied = false,
        )

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
