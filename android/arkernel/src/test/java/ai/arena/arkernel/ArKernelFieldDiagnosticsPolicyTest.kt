package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelFieldDiagnosticsPolicyTest {
    @Test
    fun canonicalRoundTripKeepsFailClosedDefaultsAndAcceptsBomCrlf() {
        val policy = defaultPolicy()
        val text = ArKernelFieldDiagnosticsPolicyBundle.encode(policy)
        assertEquals(listOf(
            "ARKFDPOL1",
            "version=1",
            "policy_id=field-default",
            "revision=1",
            "bundle_version=ARKFDIAG1",
            "max_bundle_chars=65536",
            "collection_opt_in=false",
            "terminal_text_only=true",
            "host_redaction_required=true",
            "core_upload_disabled=true",
            "host_signature_verification_required=true",
            "independent_targets_required=true",
            "quality_grade=unverified",
        ).joinToString("\n"), text)
        assertEquals(13, text.lines().size)

        val parsed = ArKernelFieldDiagnosticsPolicyBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.NONE, parsed.rejection)
        assertEquals(0, parsed.lineNumber)
        assertEquals(policy, parsed.policy)
        assertFalse(parsed.policy!!.collectionOptIn)
        assertEquals("unverified", parsed.policy.qualityGrade)

        assertTrue(ArKernelFieldDiagnosticsPolicyBundle.parse("$text\n").accepted)
        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelFieldDiagnosticsPolicyBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(policy, crlfParsed.policy)
    }

    @Test
    fun optInDoesNotWeakenInvariantsAndRejectsUnsafeText() {
        val policy = ArKernelFieldDiagnosticsPolicy(
            "field-opt-in", collectionOptIn = true)
        val text = ArKernelFieldDiagnosticsPolicyBundle.encode(policy)
        val parsed = ArKernelFieldDiagnosticsPolicyBundle.parse(text)
        assertTrue(parsed.accepted)
        assertTrue(parsed.policy!!.collectionOptIn)

        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(terminalTextOnly = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(hostRedactionRequired = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(coreUploadDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(hostSignatureVerificationRequired = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(independentTargetsRequired = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(qualityGrade = "assessed")
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(policyId = "bad/id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(revision = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(maxBundleChars = 65_537)
        }

        for (key in listOf(
            "terminal_text_only",
            "host_redaction_required",
            "core_upload_disabled",
            "host_signature_verification_required",
            "independent_targets_required",
        )) {
            assertEquals(ArKernelFieldDiagnosticsPolicyRejection.UNSAFE_INVARIANT,
                ArKernelFieldDiagnosticsPolicyBundle.parse(
                    replaceValue(text, key, "false")).rejection)
        }
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_QUALITY,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceValue(text, "quality_grade", "assessed")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceValue(text, "bundle_version", "ARKFDIAG2")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceValue(text, "version", "2")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceValue(text, "policy_id", "bad/id")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceValue(text, "revision", "-1")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceValue(text, "max_bundle_chars", "65537")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceValue(text, "collection_opt_in", "yes")).rejection)
    }

    @Test
    fun rejectsMalformedCanonicalText() {
        val text = ArKernelFieldDiagnosticsPolicyBundle.encode(defaultPolicy())
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INPUT_TOO_LARGE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                "x".repeat(4_097)).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.EMPTY_POLICY,
            ArKernelFieldDiagnosticsPolicyBundle.parse("").rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.EMPTY_POLICY,
            ArKernelFieldDiagnosticsPolicyBundle.parse("\uFEFF").rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.EMPTY_POLICY,
            ArKernelFieldDiagnosticsPolicyBundle.parse("\n").rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_HEADER,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                "WRONG\n" + text.lines().drop(1).joinToString("\n")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsPolicyBundle.parse("$text\nextra=1").rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsPolicyBundle.parse("$text\n\n").rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceKey(text, "policy_id", "id")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceLine(text, "policy_id",
                    " policy_id=field-default")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.LINE_TOO_LONG,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceLine(text, "policy_id", "x".repeat(257))).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.EMPTY_LINE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceLine(text, "policy_id", "")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceLine(text, "policy_id", "policy_id")).rejection)
        assertEquals(ArKernelFieldDiagnosticsPolicyRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsPolicyBundle.parse(
                replaceValue(text, "terminal_text_only", "yes")).rejection)
    }

    @Test
    fun policyTextNeverImplementsOrClaimsPhysicalEvidence() {
        val text = ArKernelFieldDiagnosticsPolicyBundle.encode(defaultPolicy())
        assertTrue(text.contains("quality_grade=unverified"))
        assertFalse(text.contains("quality_grade=verified"))
        for (forbidden in listOf(
            "image",
            "trajectory",
            "pose",
            "calibration",
            "pixel",
            "depth",
            "upload_enabled",
            "signature_verified",
            "centimeter",
        )) {
            assertFalse("Unexpected $forbidden in policy text",
                text.contains(forbidden, ignoreCase = true))
        }
        assertTrue(text.lines().all { it.length <= 256 })
        assertEquals(65_536, defaultPolicy().maxBundleChars)
    }

    private fun defaultPolicy(): ArKernelFieldDiagnosticsPolicy =
        ArKernelFieldDiagnosticsPolicy("field-default")

    private fun replaceValue(text: String, key: String, value: String): String =
        replaceLine(text, key, "$key=$value")

    private fun replaceKey(text: String, oldKey: String, newKey: String): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("$oldKey=") }
        require(index >= 0) { "Unknown policy key" }
        lines[index] = "$newKey=" + lines[index].substringAfter('=')
        return lines.joinToString("\n")
    }

    private fun replaceLine(text: String, key: String, line: String): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("$key=") }
        require(index >= 0) { "Unknown policy key" }
        lines[index] = line
        return lines.joinToString("\n")
    }
}
