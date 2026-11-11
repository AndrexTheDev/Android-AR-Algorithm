package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelFieldDiagnosticsRedactionPlanTest {
    @Test
    fun redactionPlanRoundTripsAndReportsBoundedFacts() {
        val plan = samplePlan()
        val text = ArKernelFieldDiagnosticsRedactionBundle.encode(plan)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.startsWith("ARKREDACT1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("collection_opt_in=false"))
        assertTrue(text.contains("terminal_text_only=true"))
        assertTrue(text.contains("core_upload_disabled=true"))
        assertTrue(text.contains("host_redaction_required=true"))
        assertTrue(text.contains("host_signature_verification_required=true"))
        assertTrue(text.contains("independent_targets_required=true"))
        assertTrue(text.contains("quality_grade=unverified"))
        assertTrue(text.contains("rule=0|error_text|drop|dropped|privacy"))
        assertTrue(text.contains(
            "rule=1|capability_signature|hash|hash.v1|identity"))

        val parsed = ArKernelFieldDiagnosticsRedactionBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.NONE,
            parsed.rejection)
        val parsedPlan = parsed.plan!!
        assertEquals(plan, parsedPlan)
        assertEquals(
            text,
            ArKernelFieldDiagnosticsRedactionBundle.encode(parsedPlan))

        val report =
            ArKernelFieldDiagnosticsRedactionBundle.summarize(parsedPlan)
        assertEquals(2, report.ruleCount)
        assertEquals(1, report.dropCount)
        assertEquals(1, report.hashCount)
        assertEquals(2, report.distinctFieldIds)
        assertEquals(2, report.distinctTokens)
        assertEquals("unverified", report.qualityGrade)

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed =
            ArKernelFieldDiagnosticsRedactionBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(plan, crlfParsed.plan)
        assertTrue(
            ArKernelFieldDiagnosticsRedactionBundle.parse("$text\n").accepted)
    }

    @Test
    fun maximumBoundedEnvelopeStaysCoherent() {
        val rules = List(32) { index ->
            ArKernelFieldDiagnosticsRedactionRule(
                index,
                "f".repeat(30) + index.toString().padStart(2, '0'),
                if (index % 2 == 0) {
                    ArKernelFieldDiagnosticsRedactionAction.DROP
                } else {
                    ArKernelFieldDiagnosticsRedactionAction.HASH
                },
                "t".repeat(32),
                "r".repeat(32),
            )
        }
        val plan = ArKernelFieldDiagnosticsRedactionPlan(
            planId = "m".repeat(32),
            rules = rules,
        )
        val text = ArKernelFieldDiagnosticsRedactionBundle.encode(plan)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        val parsed = ArKernelFieldDiagnosticsRedactionBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(plan, parsed.plan)
        assertEquals(
            text,
            ArKernelFieldDiagnosticsRedactionBundle.encode(parsed.plan!!))

        val report =
            ArKernelFieldDiagnosticsRedactionBundle.summarize(plan)
        assertEquals(32, report.ruleCount)
        assertEquals(16, report.dropCount)
        assertEquals(16, report.hashCount)
        assertEquals(32, report.distinctFieldIds)
        assertEquals(1, report.distinctTokens)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun parserRejectsMalformedUnsafeAndOutOfBudgetInputs() {
        val text =
            ArKernelFieldDiagnosticsRedactionBundle.encode(samplePlan())
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INPUT_TOO_LARGE,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                "x".repeat(8_193)).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.EMPTY_PLAN,
            ArKernelFieldDiagnosticsRedactionBundle.parse("").rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.EMPTY_PLAN,
            ArKernelFieldDiagnosticsRedactionBundle.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_HEADER,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("ARKREDACT1", "OTHER")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("version=1", "version=2")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_QUALITY,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("quality_grade=unverified",
                    "quality_grade=verified")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("collection_opt_in=false",
                    "collection_opt_in=true")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("terminal_text_only=true",
                    "terminal_text_only=false")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("core_upload_disabled=true",
                    "core_upload_disabled=false")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("host_redaction_required=true",
                    "host_redaction_required=false")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("host_signature_verification_required=true",
                    "host_signature_verification_required=false")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.UNSAFE_INVARIANT,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("independent_targets_required=true",
                    "independent_targets_required=false")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_VALUE,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("revision=1", "revision=0")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_RULE_COUNT,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("rule_count=2",
                    "rule_count=0")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_RULE_COUNT,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("rule_count=2",
                    "rule_count=33")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("rule_count=2",
                    "rule_count=3")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("END", "STOP")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.EMPTY_LINE,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                blankLine(text, 13)).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.EMPTY_LINE,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                "$text\n\n").rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("rule=0|", "row=0|")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_SCHEMA,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("rule=0|error_text|drop|",
                    "rule=0|error_text|")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.DUPLICATE_FIELD_ID,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                replaceRuleField(text, 1, 1, "error_text")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_ORDER,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                replaceRuleField(text, 1, 0, "2")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_ACTION,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                replaceRuleField(text, 0, 2, "keep")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_ACTION,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                replaceRuleField(text, 0, 2, "DROP")).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_TOKEN,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                replaceRuleField(
                    text, 0, 1, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_TOKEN,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                replaceRuleField(
                    text, 0, 3, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_TOKEN,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                replaceRuleField(
                    text, 0, 4, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.INVALID_TOKEN,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                text.replace("plan_id=field.redaction.v1",
                    "plan_id=" + "x".repeat(33))).rejection)
        assertEquals(
            ArKernelFieldDiagnosticsRedactionRejection.LINE_TOO_LONG,
            ArKernelFieldDiagnosticsRedactionBundle.parse(
                replaceRuleField(
                    text, 0, 1, "x".repeat(300))).rejection)
    }

    @Test
    fun planConstructionRejectsUnsafeAndMalformedRules() {
        val rules = sampleRules()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionPlan(
                "ok.id", rules = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionPlan(
                "ok.id",
                rules = List(33) { index ->
                    rules[0].copy(
                        index = index, fieldId = "f.$index")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionPlan(
                "ok.id",
                collectionOptIn = true,
                rules = rules,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionPlan(
                "ok.id",
                terminalTextOnly = false,
                rules = rules,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionPlan(
                "ok.id",
                rules = listOf(
                    rules[0],
                    rules[0].copy(index = 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionPlan(
                "ok.id",
                rules = listOf(
                    rules[0].copy(index = 1),
                    rules[1]),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionRule(
                0,
                "x".repeat(33),
                ArKernelFieldDiagnosticsRedactionAction.DROP,
                "token",
                "reason",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionRule(
                0,
                "field",
                ArKernelFieldDiagnosticsRedactionAction.DROP,
                "x".repeat(33),
                "reason",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelFieldDiagnosticsRedactionRule(
                0,
                "field",
                ArKernelFieldDiagnosticsRedactionAction.HASH,
                "token",
                "x".repeat(33),
            )
        }
    }

    private fun samplePlan(): ArKernelFieldDiagnosticsRedactionPlan =
        ArKernelFieldDiagnosticsRedactionPlan(
            planId = "field.redaction.v1",
            revision = 1,
            rules = sampleRules(),
        )

    private fun sampleRules():
        List<ArKernelFieldDiagnosticsRedactionRule> = listOf(
        ArKernelFieldDiagnosticsRedactionRule(
            0,
            "error_text",
            ArKernelFieldDiagnosticsRedactionAction.DROP,
            "dropped",
            "privacy",
        ),
        ArKernelFieldDiagnosticsRedactionRule(
            1,
            "capability_signature",
            ArKernelFieldDiagnosticsRedactionAction.HASH,
            "hash.v1",
            "identity",
        ),
    )

    private fun blankLine(text: String, lineIndex: Int): String {
        val lines = text.lines().toMutableList()
        lines[lineIndex] = ""
        return lines.joinToString("\n")
    }

    private fun replaceRuleField(
        text: String,
        ruleIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        val lineIndex = 12 + ruleIndex
        val prefix = "rule="
        val fields = lines[lineIndex].substringAfter(prefix).split('|')
            .toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
