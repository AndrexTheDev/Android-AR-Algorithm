package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelLoopClosureCandidateLedgerTest {
    @Test
    fun candidateLedgerRoundTripsAndReportsBoundedFacts() {
        val ledger = sampleLedger()
        val text = ArKernelLoopClosureCandidateBundle.encode(ledger)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.startsWith("ARKLOOP1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("quality_grade=unverified"))
        assertTrue(text.contains("pose_graph_optimization_disabled=true"))
        assertTrue(text.contains("global_bundle_adjustment_disabled=true"))
        assertTrue(text.contains("physical_ground_truth_disabled=true"))
        assertTrue(text.contains("automatic_map_mutation_disabled=true"))
        assertTrue(text.contains(
            "candidate=0|loop-01|kf-001|kf-050|0.25|0.01|30|proposed"))
        assertTrue(text.contains(
            "candidate=1|loop-02|kf-002|kf-051|0.5|0.02|20|proposed"))

        val parsed = ArKernelLoopClosureCandidateBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.NONE,
            parsed.rejection)
        val parsedLedger = parsed.ledger!!
        assertEquals(ledger, parsedLedger)
        assertEquals(
            text,
            ArKernelLoopClosureCandidateBundle.encode(parsedLedger))

        val report =
            ArKernelLoopClosureCandidateBundle.summarize(parsedLedger)
        assertEquals(2, report.candidateCount)
        assertEquals(2, report.proposedCount)
        assertEquals(50L, report.totalInliers)
        assertEquals(4, report.distinctKeyframes)
        assertEquals(0.5, report.maxResidual, 0.0)
        assertEquals("unverified", report.qualityGrade)

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelLoopClosureCandidateBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(ledger, crlfParsed.ledger)
        assertTrue(
            ArKernelLoopClosureCandidateBundle.parse("$text\n").accepted)
    }

    @Test
    fun maximumBoundedEnvelopeStaysCoherent() {
        val candidates = List(48) { index ->
            val suffix = index.toString().padStart(2, '0')
            ArKernelLoopClosureCandidate(
                index,
                "c".repeat(30) + suffix,
                "a".repeat(30) + suffix,
                "b".repeat(30) + suffix,
                0.5,
                0.001,
                10,
            )
        }
        val ledger = ArKernelLoopClosureCandidateLedger(
            ledgerId = "l".repeat(32),
            candidates = candidates,
        )
        val text = ArKernelLoopClosureCandidateBundle.encode(ledger)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        val parsed = ArKernelLoopClosureCandidateBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(ledger, parsed.ledger)
        assertEquals(
            text,
            ArKernelLoopClosureCandidateBundle.encode(parsed.ledger!!))

        val report =
            ArKernelLoopClosureCandidateBundle.summarize(ledger)
        assertEquals(48, report.candidateCount)
        assertEquals(48, report.proposedCount)
        assertEquals(480L, report.totalInliers)
        assertEquals(96, report.distinctKeyframes)
        assertEquals(0.5, report.maxResidual, 0.0)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun parserRejectsMalformedUnsafeAndOutOfBudgetInputs() {
        val text =
            ArKernelLoopClosureCandidateBundle.encode(sampleLedger())
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INPUT_TOO_LARGE,
            ArKernelLoopClosureCandidateBundle.parse(
                "x".repeat(8_193)).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.EMPTY_PLAN,
            ArKernelLoopClosureCandidateBundle.parse("").rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.EMPTY_PLAN,
            ArKernelLoopClosureCandidateBundle.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_HEADER,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("ARKLOOP1", "OTHER")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("version=1", "version=2")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_QUALITY,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("quality_grade=unverified",
                    "quality_grade=verified")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.UNSAFE_INVARIANT,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("pose_graph_optimization_disabled=true",
                    "pose_graph_optimization_disabled=false")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.UNSAFE_INVARIANT,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("global_bundle_adjustment_disabled=true",
                    "global_bundle_adjustment_disabled=false")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.UNSAFE_INVARIANT,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("physical_ground_truth_disabled=true",
                    "physical_ground_truth_disabled=false")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.UNSAFE_INVARIANT,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("automatic_map_mutation_disabled=true",
                    "automatic_map_mutation_disabled=false")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("revision=1", "revision=0")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_CANDIDATE_COUNT,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("candidate_count=2",
                    "candidate_count=0")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_CANDIDATE_COUNT,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("candidate_count=2",
                    "candidate_count=49")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("candidate_count=2",
                    "candidate_count=3")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("END", "STOP")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.EMPTY_LINE,
            ArKernelLoopClosureCandidateBundle.parse(
                blankLine(text, 11)).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.EMPTY_LINE,
            ArKernelLoopClosureCandidateBundle.parse(
                "$text\n\n").rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("candidate=0|", "row=0|")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_SCHEMA,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("candidate=0|loop-01|kf-001|",
                    "candidate=0|loop-01|")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.DUPLICATE_CANDIDATE_ID,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 1, 1, "loop-01")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_ORDER,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 1, 0, "2")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 0, 3, "kf-001")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_NUMBER,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 0, 4, "NaN")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_NUMBER,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 0, 4, "Infinity")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_NUMBER,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 0, 4, "-0.1")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_NUMBER,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(
                    text, 0, 4, "not-a-number")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_NUMBER,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 0, 5, "-0.1")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(
                    text, 0, 6, "1000001")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_VALUE,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 0, 6, "abc")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_STATE,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(text, 0, 7, "verified")).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_TOKEN,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(
                    text, 0, 1, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_TOKEN,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(
                    text, 0, 2, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_TOKEN,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(
                    text, 0, 3, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.INVALID_TOKEN,
            ArKernelLoopClosureCandidateBundle.parse(
                text.replace("ledger_id=loop.candidates.v1",
                    "ledger_id=" + "x".repeat(33))).rejection)
        assertEquals(
            ArKernelLoopClosureCandidateRejection.LINE_TOO_LONG,
            ArKernelLoopClosureCandidateBundle.parse(
                replaceCandidateField(
                    text, 0, 1, "x".repeat(300))).rejection)
    }

    @Test
    fun ledgerConstructionRejectsUnsafeAndMalformedCandidates() {
        val candidates = sampleCandidates()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidateLedger(
                "ok.id", candidates = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidateLedger(
                "ok.id",
                candidates = List(49) { index ->
                    candidates[0].copy(
                        index = index, candidateId = "c.$index")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidateLedger(
                "ok.id",
                poseGraphOptimizationDisabled = false,
                candidates = candidates,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidateLedger(
                "ok.id",
                candidates = listOf(
                    candidates[0],
                    candidates[0].copy(index = 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidateLedger(
                "ok.id",
                candidates = listOf(
                    candidates[0].copy(index = 1),
                    candidates[1]),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidate(
                0, "c.0", "kf.0", "kf.0", 0.25, 0.01, 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidate(
                0, "c.0", "kf.0", "kf.1", -0.1, 0.01, 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidate(
                0, "c.0", "kf.0", "kf.1", 0.25,
                Double.NaN, 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidate(
                0, "c.0", "kf.0", "kf.1", 0.25, 0.01,
                1_000_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureCandidate(
                0, "c.0", "kf.0", "kf.1", 0.25, 0.01, 30,
                "verified")
        }
    }

    private fun sampleLedger(): ArKernelLoopClosureCandidateLedger =
        ArKernelLoopClosureCandidateLedger(
            ledgerId = "loop.candidates.v1",
            revision = 1,
            candidates = sampleCandidates(),
        )

    private fun sampleCandidates(): List<ArKernelLoopClosureCandidate> =
        listOf(
            ArKernelLoopClosureCandidate(
                0, "loop-01", "kf-001", "kf-050", 0.25, 0.01, 30),
            ArKernelLoopClosureCandidate(
                1, "loop-02", "kf-002", "kf-051", 0.5, 0.02, 20),
        )

    private fun blankLine(text: String, lineIndex: Int): String {
        val lines = text.lines().toMutableList()
        lines[lineIndex] = ""
        return lines.joinToString("\n")
    }

    private fun replaceCandidateField(
        text: String,
        candidateIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        val lineIndex = 10 + candidateIndex
        val prefix = "candidate="
        val fields = lines[lineIndex].substringAfter(prefix).split('|')
            .toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
