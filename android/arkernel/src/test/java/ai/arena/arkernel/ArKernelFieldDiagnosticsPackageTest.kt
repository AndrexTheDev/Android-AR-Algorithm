package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class PackageRejectionCase(
    val name: String,
    val text: String,
    val reason: ArKernelFieldDiagnosticsPackageRejection,
)

class ArKernelFieldDiagnosticsPackageTest {
    @Test
    fun samplePackageRoundTripsAndReportsOnlyStructuralFacts() {
        val bundle = sampleBundle()
        val text = ArKernelFieldDiagnosticsPackageAdapter.encode(bundle)
        val result = ArKernelFieldDiagnosticsPackageAdapter.parse(text)
        assertTrue(result.accepted)
        assertEquals(
            ArKernelFieldDiagnosticsPackageRejection.NONE,
            result.rejection)
        assertEquals(bundle, result.bundle)
        assertEquals(text,
            ArKernelFieldDiagnosticsPackageAdapter.encode(
                result.bundle!!))
        val report = ArKernelFieldDiagnosticsPackageAdapter
            .summarize(result.bundle!!)
        assertEquals(2, report.reportCount)
        assertEquals(2, report.ruleCount)
        assertEquals(1, report.targetCount)
        assertEquals(2, report.distinctReportRefs)
        assertEquals(2, report.distinctReportDigests)
        assertEquals(3_072L, report.totalReportBytes)
        assertEquals(1, report.dropRuleCount)
        assertEquals(1, report.hashRuleCount)
        assertEquals(1, report.distinctTargetRefs)
        assertEquals("unverified", report.qualityGrade)
        assertEquals("unverified", result.bundle!!.qualityGrade)
        assertEquals(listOf("diag-01", "diag-02"),
            result.bundle!!.reportRefs())
        assertEquals(listOf("a".repeat(64), "b".repeat(64)),
            result.bundle!!.referencedDigests())
    }

    @Test
    fun bomCrLfAndOneOptionalFinalSeparatorAreAcceptedIdentically() {
        val bundle = sampleBundle()
        val text = ArKernelFieldDiagnosticsPackageAdapter.encode(bundle)
        val variants = listOf(
            text,
            text + "\n",
            "\uFEFF" + text,
            "\uFEFF" + text.replace("\n", "\r\n") + "\r\n",
            text.replace("\n", "\r\n") + "\r\n",
        )
        for (variant in variants) {
            val result = ArKernelFieldDiagnosticsPackageAdapter
                .parse(variant)
            assertTrue("variant was rejected: ${result.detail}",
                result.accepted)
            assertEquals(bundle, result.bundle)
        }
        assertEquals(
            ArKernelFieldDiagnosticsPackageRejection.EMPTY_LINE,
            ArKernelFieldDiagnosticsPackageAdapter
                .parse(text + "\n\n").rejection)
    }

    @Test
    fun maximalBoundedEnvelopeStaysCoherent() {
        val reports = buildList<ArKernelFieldDiagnosticsReportRef> {
            repeat(16) { index ->
                add(
                    ArKernelFieldDiagnosticsReportRef(
                        index,
                        "diag-" + index.toString().padStart(2, '0'),
                        index.toString(16).padStart(64, '0'),
                        1_000_000,
                    ))
            }
        }
        val rules = buildList<ArKernelFieldDiagnosticsRuleRef> {
            repeat(16) { index ->
                add(
                    ArKernelFieldDiagnosticsRuleRef(
                        index,
                        "rule-" + index.toString().padStart(2, '0'),
                        if (index % 2 == 0)
                            ArKernelFieldDiagnosticsRuleAction.DROP
                        else ArKernelFieldDiagnosticsRuleAction.HASH,
                    ))
            }
        }
        val targets = buildList<ArKernelFieldDiagnosticsTargetRef> {
            repeat(8) { index ->
                add(
                    ArKernelFieldDiagnosticsTargetRef(
                        index,
                        "target-" + index.toString().padStart(2, '0'),
                    ))
            }
        }
        val bundle = ArKernelFieldDiagnosticsPackageBundle(
            packageId = "maximal.package.v1",
            reports = reports,
            rules = rules,
            targets = targets,
        )
        val text = ArKernelFieldDiagnosticsPackageAdapter.encode(bundle)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertEquals(bundle,
            ArKernelFieldDiagnosticsPackageAdapter.parse(text).bundle)
        val report = ArKernelFieldDiagnosticsPackageAdapter
            .summarize(bundle)
        assertEquals(16, report.reportCount)
        assertEquals(16, report.ruleCount)
        assertEquals(8, report.targetCount)
        assertEquals(16L * 1_000_000L, report.totalReportBytes)
        assertEquals(8, report.dropRuleCount)
        assertEquals(8, report.hashRuleCount)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun everyMalformedOrUnsafeVariantFailsClosedWithoutPartialBundle() {
        val text = ArKernelFieldDiagnosticsPackageAdapter.encode(
            sampleBundle())
        val cases = listOf(
            PackageRejectionCase("inputTooLarge",
                "x".repeat(8_193),
                ArKernelFieldDiagnosticsPackageRejection.INPUT_TOO_LARGE),
            PackageRejectionCase("empty",
                "",
                ArKernelFieldDiagnosticsPackageRejection.EMPTY_PACKAGE),
            PackageRejectionCase("badHeader",
                replaceLine(text, 0, "OTHER1"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_HEADER),
            PackageRejectionCase("badVersion",
                replaceOnce(text, "version=1", "version=2"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE),
            PackageRejectionCase("assessedQualityInput",
                replaceOnce(text, "quality_grade=unverified",
                    "quality_grade=verified"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_QUALITY),
            PackageRejectionCase("imagesEnabled",
                replaceOnce(text, "images_disabled=true",
                    "images_disabled=false"),
                ArKernelFieldDiagnosticsPackageRejection.UNSAFE_INVARIANT),
            PackageRejectionCase("posesEnabled",
                replaceOnce(text, "poses_disabled=true",
                    "poses_disabled=false"),
                ArKernelFieldDiagnosticsPackageRejection.UNSAFE_INVARIANT),
            PackageRejectionCase("trajectoriesEnabled",
                replaceOnce(text, "trajectories_disabled=true",
                    "trajectories_disabled=false"),
                ArKernelFieldDiagnosticsPackageRejection.UNSAFE_INVARIANT),
            PackageRejectionCase("captureEnabled",
                replaceOnce(text,
                    "automatic_capture_disabled=true",
                    "automatic_capture_disabled=false"),
                ArKernelFieldDiagnosticsPackageRejection.UNSAFE_INVARIANT),
            PackageRejectionCase("uploadEnabled",
                replaceOnce(text,
                    "automatic_upload_disabled=true",
                    "automatic_upload_disabled=false"),
                ArKernelFieldDiagnosticsPackageRejection.UNSAFE_INVARIANT),
            PackageRejectionCase("invalidPackageId",
                replaceOnce(text, "package_id=field.package.v1",
                    "package_id=bad!id"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_TOKEN),
            PackageRejectionCase("zeroReports",
                replaceOnce(text, "report_count=2", "report_count=0"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_COUNT),
            PackageRejectionCase("tooManyReports",
                replaceOnce(text, "report_count=2", "report_count=17"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_COUNT),
            PackageRejectionCase("tooManyRules",
                replaceOnce(text, "rule_count=2", "rule_count=17"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_COUNT),
            PackageRejectionCase("tooManyTargets",
                replaceOnce(text, "target_count=1", "target_count=9"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_COUNT),
            PackageRejectionCase("lineCountMismatch",
                replaceOnce(text, "target_count=1", "target_count=0"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA),
            PackageRejectionCase("keyOrder",
                replaceLine(text, 1, "package_id=field.package.v1"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA),
            PackageRejectionCase("lineTooLong",
                replaceLine(text, 12, "x".repeat(257)),
                ArKernelFieldDiagnosticsPackageRejection.LINE_TOO_LONG),
            PackageRejectionCase("blankLine",
                replaceLine(text, 12, ""),
                ArKernelFieldDiagnosticsPackageRejection.EMPTY_LINE),
            PackageRejectionCase("extraFinalSeparator",
                text + "\n\n",
                ArKernelFieldDiagnosticsPackageRejection.EMPTY_LINE),
            PackageRejectionCase("missingEnd",
                replaceLine(text, 17, "STOP"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA),
            PackageRejectionCase("reportOrder",
                replaceOnce(text, "report=0|", "report=1|"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_ORDER),
            PackageRejectionCase("ruleOrder",
                replaceOnce(text, "rule=0|", "rule=1|"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_ORDER),
            PackageRejectionCase("targetOrder",
                replaceOnce(text, "target=0|", "target=1|"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_ORDER),
            PackageRejectionCase("duplicateReportRef",
                reportField(text, 13, 1, "diag-01"),
                ArKernelFieldDiagnosticsPackageRejection
                    .DUPLICATE_REPORT_REF),
            PackageRejectionCase("duplicateDigest",
                reportField(text, 13, 2, "a".repeat(64)),
                ArKernelFieldDiagnosticsPackageRejection.DUPLICATE_DIGEST),
            PackageRejectionCase("duplicateRuleRef",
                ruleField(text, 15, 1, "rule-01"),
                ArKernelFieldDiagnosticsPackageRejection
                    .DUPLICATE_RULE_REF),
            PackageRejectionCase("reportFieldCount",
                replaceLine(text, 12, "report=0|only"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA),
            PackageRejectionCase("ruleFieldCount",
                replaceLine(text, 14, "rule=0|only"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA),
            PackageRejectionCase("targetFieldCount",
                replaceLine(text, 16, "target=0"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA),
            PackageRejectionCase("wrongReportPrefix",
                replaceLine(text, 12,
                    "rule=0|diag-01|" + "a".repeat(64) + "|1024"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SCHEMA),
            PackageRejectionCase("invalidReportToken",
                reportField(text, 12, 1, "bad!id"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_TOKEN),
            PackageRejectionCase("invalidRuleToken",
                ruleField(text, 14, 1, "bad!id"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_TOKEN),
            PackageRejectionCase("invalidTargetToken",
                targetField(text, 16, 1, "bad!id"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_TOKEN),
            PackageRejectionCase("uppercaseDigest",
                reportField(text, 12, 2, "A".repeat(64)),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_DIGEST),
            PackageRejectionCase("shortDigest",
                reportField(text, 12, 2, "a".repeat(63)),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_DIGEST),
            PackageRejectionCase("zeroByteSize",
                reportField(text, 12, 3, "0"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SIZE),
            PackageRejectionCase("byteSizeAboveBound",
                reportField(text, 12, 3, "1000001"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_SIZE),
            PackageRejectionCase("invalidAction",
                ruleField(text, 14, 2, "keep"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_ACTION),
            PackageRejectionCase("nonNumericReportIndex",
                reportField(text, 12, 0, "x"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE),
            PackageRejectionCase("nonNumericByteSize",
                reportField(text, 12, 3, "x"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_VALUE),
            PackageRejectionCase("nonNumericCount",
                replaceOnce(text, "report_count=2", "report_count=x"),
                ArKernelFieldDiagnosticsPackageRejection.INVALID_COUNT),
        )
        assertEquals(42, cases.size)
        for (case in cases) {
            val result = ArKernelFieldDiagnosticsPackageAdapter
                .parse(case.text)
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
    fun bundleAndRowConstructorsRejectUnsafeShapes() {
        val bundle = sampleBundle()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsPackageBundle(
                packageId = "empty.v1",
                reports = emptyList(),
                rules = bundle.rules,
                targets = bundle.targets,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsPackageBundle(
                packageId = "doubled.v1",
                reports = listOf(bundle.reports[0], bundle.reports[0]),
                rules = bundle.rules,
                targets = bundle.targets,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsPackageBundle(
                packageId = "gapped.v1",
                reports = listOf(
                    bundle.reports[0],
                    bundle.reports[1].copy(index = 2),
                ),
                rules = bundle.rules,
                targets = bundle.targets,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsPackageBundle(
                packageId = "unsafe.v1",
                reports = bundle.reports,
                rules = bundle.rules,
                targets = bundle.targets,
                imagesDisabled = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsPackageBundle(
                packageId = "unsafe.v1",
                reports = bundle.reports,
                rules = bundle.rules,
                targets = bundle.targets,
                qualityGrade = "verified",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsReportRef(
                0, "diag-01", "A".repeat(64), 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsReportRef(
                0, "diag-01", "a".repeat(64), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRuleRef(
                0, "bad!id", ArKernelFieldDiagnosticsRuleAction.DROP)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsTargetRef(0, "bad!id")
        }
    }

    private fun sampleBundle(): ArKernelFieldDiagnosticsPackageBundle =
        ArKernelFieldDiagnosticsPackageBundle(
            packageId = "field.package.v1",
            reports = listOf(
                ArKernelFieldDiagnosticsReportRef(
                    0, "diag-01", "a".repeat(64), 1_024),
                ArKernelFieldDiagnosticsReportRef(
                    1, "diag-02", "b".repeat(64), 2_048),
            ),
            rules = listOf(
                ArKernelFieldDiagnosticsRuleRef(
                    0, "rule-01",
                    ArKernelFieldDiagnosticsRuleAction.DROP),
                ArKernelFieldDiagnosticsRuleRef(
                    1, "rule-02",
                    ArKernelFieldDiagnosticsRuleAction.HASH),
            ),
            targets = listOf(
                ArKernelFieldDiagnosticsTargetRef(0, "scale-bar-01"),
            ),
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

    private fun reportField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String = replaceField(text, lineIndex, fieldIndex,
        value, "report=")

    private fun ruleField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String = replaceField(text, lineIndex, fieldIndex,
        value, "rule=")

    private fun targetField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String = replaceField(text, lineIndex, fieldIndex,
        value, "target=")

    private fun replaceField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
        prefix: String,
    ): String {
        val lines = text.split("\n").toMutableList()
        val fields = lines[lineIndex].removePrefix(prefix)
            .split("|").toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
