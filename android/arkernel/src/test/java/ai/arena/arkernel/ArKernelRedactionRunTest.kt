package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelRedactionRunTest {

    companion object {
        private const val MAC = "a4:5e:60:1f:2b:3c"
        private const val IP = "192.168.1.23"
        private const val MAIL = "operator@example.com"
        private const val PATH =
            "/storage/emulated/0/ARKernelDiagnostics/ARKFEED1_1.txt"
        private const val HEX16 = "0123456789abcdef"
        private const val DEC15 = "356938035643809"

        private val FULL_TEXT = "device=phone.one mac=$MAC ip=$IP " +
            "mail=$MAIL path=$PATH end\n"

        private fun input(
            name: String = "ARKFEED1_1.txt",
            text: String = FULL_TEXT,
        ): ArKernelRedactionInput =
            ArKernelRedactionInput(artifactName = name, sourceText = text)

        private fun planOf(vararg fieldIds: String):
            ArKernelFieldDiagnosticsRedactionPlan {
            val rules = fieldIds.mapIndexed { index, fieldId ->
                ArKernelFieldDiagnosticsRedactionRule(
                    index = index,
                    fieldId = fieldId,
                    action =
                        ArKernelFieldDiagnosticsRedactionAction.DROP,
                    token = "redacted.$index",
                    reason = "privacy",
                )
            }
            return ArKernelFieldDiagnosticsRedactionPlan(
                planId = "field.redaction.test.v1",
                rules = rules,
            )
        }

        private fun runWith(
            plan: ArKernelFieldDiagnosticsRedactionPlan,
            inputs: List<ArKernelRedactionInput> = listOf(input()),
        ): ArKernelRedactionRun {
            val result = ArKernelRedactionRunAdapter.apply(
                plan = plan,
                runId = "redrun.test.v1",
                inputs = inputs,
            )
            check(result.accepted) { result.detail }
            return checkNotNull(result.run)
        }

        private fun withValue(
            text: String,
            key: String,
            value: String,
        ): String {
            val lines = text.trimEnd('\n').split('\n').toMutableList()
            val index = lines.indexOfFirst { it.startsWith("$key=") }
            check(index >= 0) { "missing key $key" }
            lines[index] = "$key=$value"
            return lines.joinToString("\n") + "\n"
        }
    }

    @Test
    fun defaultPlanCoversEveryDetector() {
        val run = runWith(ArKernelRedactionRunAdapter.defaultPlan())
        assertTrue(run.residuals.isEmpty())
        assertTrue(run.uploadAdmitted)
        assertEquals(4, run.findings.size)
        assertEquals(4, run.totalMatches)
        assertEquals(0, run.residualFindings)
        assertTrue(run.artifacts[0].changed)
        assertNotEquals(
            run.artifacts[0].digestBefore, run.artifacts[0].digestAfter)
        val redacted = run.artifacts[0].redactedText
        assertFalse(redacted.contains(MAC))
        assertFalse(redacted.contains(IP))
        assertFalse(redacted.contains(MAIL))
        assertFalse(redacted.contains(PATH))
    }

    @Test
    fun dropReplacesWithTheOpaqueToken() {
        val run = runWith(planOf("net.mac"))
        assertEquals(1, run.findings.size)
        assertEquals(1, run.findings[0].count)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionAction.DROP,
            run.findings[0].action)
        assertEquals(
            "device=phone.one mac=redacted.0 ip=$IP mail=$MAIL " +
                "path=$PATH end\n",
            run.artifacts[0].redactedText)
    }

    @Test
    fun hashKeepsLinkageButHidesTheValue() {
        val text = "first=$HEX16 second=$HEX16 third=fedcba9876543210\n"
        val plan = ArKernelFieldDiagnosticsRedactionPlan(
            planId = "field.redaction.hash.v1",
            rules = listOf(
                ArKernelFieldDiagnosticsRedactionRule(
                    index = 0,
                    fieldId = "id.hex16",
                    action = ArKernelFieldDiagnosticsRedactionAction.HASH,
                    token = "hex.id",
                    reason = "identity",
                ),
            ),
        )
        val run = runWith(plan, listOf(input(text = text)))
        assertEquals(3, run.totalMatches)
        val redacted = run.artifacts[0].redactedText
        assertFalse(redacted.contains(HEX16))
        assertFalse(redacted.contains("fedcba9876543210"))
        val tokens = Regex("hex\\.id\\.h[0-9a-f]{15}")
            .findAll(redacted).map { it.value }.toList()
        assertEquals(3, tokens.size)
        assertEquals(tokens[0], tokens[1])
        assertNotEquals(tokens[0], tokens[2])
    }

    @Test
    fun hashTokenDoesNotLookLikeAHexIdentifier() {
        val text = "id=$HEX16\n"
        val plan = ArKernelFieldDiagnosticsRedactionPlan(
            planId = "field.redaction.hash.v2",
            rules = listOf(
                ArKernelFieldDiagnosticsRedactionRule(
                    index = 0,
                    fieldId = "id.hex16",
                    action = ArKernelFieldDiagnosticsRedactionAction.HASH,
                    token = "hex.id",
                    reason = "identity",
                ),
            ),
        )
        val run = runWith(plan, listOf(input(text = text)))
        val redacted = run.artifacts[0].redactedText
        assertEquals(
            0,
            ArKernelRedactionDetectorId.ID_HEX16.pattern()
                .findAll(redacted).count())
    }

    @Test
    fun uncoveredDetectorsBecomeResiduals() {
        val run = runWith(planOf("net.mac"))
        val detectors = run.residuals.map { it.detector }.toSet()
        assertEquals(
            setOf(
                ArKernelRedactionDetectorId.NET_IPV4,
                ArKernelRedactionDetectorId.PATH_ABSOLUTE,
                ArKernelRedactionDetectorId.CONTACT_EMAIL,
            ),
            detectors,
        )
        assertEquals(3, run.residualFindings)
        assertFalse(run.uploadAdmitted)
        assertTrue(run.findings.isNotEmpty())
    }

    @Test
    fun unknownDetectorIsRejected() {
        val result = ArKernelRedactionRunAdapter.apply(
            plan = planOf("error_text"),
            runId = "redrun.test.v2",
            inputs = listOf(input()),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelRedactionRejection.UNKNOWN_DETECTOR,
            result.rejection)
        assertTrue(result.detail.contains("error_text"))
    }

    @Test
    fun sha256DigestsAreNotFlaggedAsIdentifiers() {
        val digest = "9b5a491974cdd108e2196cb16f6a9923621e748605" +
            "ef521d82462861ebfa88a3"
        val text = "ARKFEED1\ndigest=$digest\nEND\n"
        val run = runWith(
            ArKernelRedactionRunAdapter.defaultPlan(),
            listOf(input(text = text)),
        )
        assertTrue(run.findings.isEmpty())
        assertTrue(run.residuals.isEmpty())
        assertFalse(run.artifacts[0].changed)
        assertEquals(
            ArKernelRedactionRunAdapter.digestOf(text),
            run.artifacts[0].digestAfter)
    }

    @Test
    fun pathAndDecimalDetectorsFire() {
        val text = "imei=$DEC15 file=$PATH\n"
        val run = runWith(
            ArKernelRedactionRunAdapter.defaultPlan(),
            listOf(input(text = text)),
        )
        val detectors = run.findings.map { it.detector }.toSet()
        assertTrue(detectors.contains(
            ArKernelRedactionDetectorId.ID_DECIMAL15))
        assertTrue(detectors.contains(
            ArKernelRedactionDetectorId.PATH_ABSOLUTE))
        assertFalse(run.artifacts[0].redactedText.contains(DEC15))
        assertFalse(run.artifacts[0].redactedText.contains(PATH))
    }

    @Test
    fun encodeThenParseRoundTrips() {
        val run = runWith(ArKernelRedactionRunAdapter.defaultPlan())
        val text = ArKernelRedactionRunAdapter.encode(run)
        val parsed = ArKernelRedactionRunAdapter.parse(text)
        assertTrue(parsed.detail, parsed.accepted)
        val restored = checkNotNull(parsed.run)
        assertEquals(run.runId, restored.runId)
        assertEquals(run.planId, restored.planId)
        assertEquals(run.ruleCount, restored.ruleCount)
        assertEquals(run.totalMatches, restored.totalMatches)
        assertEquals(run.residualFindings, restored.residualFindings)
        assertEquals(run.uploadAdmitted, restored.uploadAdmitted)
        assertEquals(1, restored.artifacts.size)
        assertEquals(
            run.artifacts[0].digestAfter,
            restored.artifacts[0].digestAfter)
        assertTrue(restored.artifacts[0].redactedText.isEmpty())
    }

    @Test
    fun parseRejectsTamperedStatistics() {
        val text = ArKernelRedactionRunAdapter
            .encode(runWith(ArKernelRedactionRunAdapter.defaultPlan()))
        val result = ArKernelRedactionRunAdapter
            .parse(withValue(text, "total_matches", "99"))
        assertEquals(
            ArKernelRedactionRejection.STATISTICS_MISMATCH,
            result.rejection)
    }

    @Test
    fun parseRejectsInconsistentChangeFlag() {
        val run = runWith(ArKernelRedactionRunAdapter.defaultPlan())
        val text = ArKernelRedactionRunAdapter.encode(run)
        val broken = text.replace(
            "artefact=0|ARKFEED1_1.txt|${run.artifacts[0].digestBefore}|" +
                "${run.artifacts[0].digestAfter}|true",
            "artefact=0|ARKFEED1_1.txt|${run.artifacts[0].digestBefore}|" +
                "${run.artifacts[0].digestAfter}|false",
        )
        val result = ArKernelRedactionRunAdapter.parse(broken)
        assertEquals(
            ArKernelRedactionRejection.INVALID_ROW,
            result.rejection)
    }

    @Test
    fun parseRejectsRelaxedInvariant() {
        val text = ArKernelRedactionRunAdapter
            .encode(runWith(ArKernelRedactionRunAdapter.defaultPlan()))
        assertEquals(
            ArKernelRedactionRejection.UNSAFE_INVARIANT,
            ArKernelRedactionRunAdapter
                .parse(withValue(text, "manual_upload_only", "false"))
                .rejection)
    }

    @Test
    fun parseRejectsEmptyAndMalformedInput() {
        assertEquals(
            ArKernelRedactionRejection.EMPTY_RUN,
            ArKernelRedactionRunAdapter.parse("   ").rejection)
        assertEquals(
            ArKernelRedactionRejection.INVALID_HEADER,
            ArKernelRedactionRunAdapter
                .parse("ARKREDRUN0\nEND\n").rejection)
    }

    @Test
    fun applyRejectsDuplicateArtefactName() {
        val result = ArKernelRedactionRunAdapter.apply(
            plan = ArKernelRedactionRunAdapter.defaultPlan(),
            runId = "redrun.test.v3",
            inputs = listOf(input(), input(text = "other\n")),
        )
        assertEquals(
            ArKernelRedactionRejection.DUPLICATE_ARTEFACT,
            result.rejection)
    }

    @Test
    fun applyRejectsEmptySourceText() {
        val result = ArKernelRedactionRunAdapter.apply(
            plan = ArKernelRedactionRunAdapter.defaultPlan(),
            runId = "redrun.test.v4",
            inputs = listOf(input(text = "   ")),
        )
        assertEquals(
            ArKernelRedactionRejection.EMPTY_SOURCE,
            result.rejection)
    }

    @Test
    fun applyRejectsInvalidToken() {
        val result = ArKernelRedactionRunAdapter.apply(
            plan = ArKernelRedactionRunAdapter.defaultPlan(),
            runId = "bad id",
            inputs = listOf(input()),
        )
        assertEquals(
            ArKernelRedactionRejection.INVALID_TOKEN,
            result.rejection)
    }

    @Test
    fun detectorWireNamesRoundTrip() {
        val names = ArKernelRedactionDetectorId.entries
            .map { it.wireName() }.toSet()
        assertEquals(6, names.size)
        assertEquals(null,
            ArKernelRedactionDetectorId.fromWire("device.serial"))
        assertEquals(ArKernelRedactionDetectorId.NET_MAC,
            ArKernelRedactionDetectorId.fromWire("net.mac"))
    }

    @Test
    fun artefactRejectsInvalidName() {
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRedactionArtifactResult(
                index = 0,
                artifactName = "with space.txt",
                digestBefore = "a".repeat(64),
                digestAfter = "b".repeat(64),
                redactedText = "",
            )
        }
    }
}
