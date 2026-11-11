package ai.arena.arkernel

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelLoopClosureGeometricGateTest {
    @Test
    fun geometricEnvelopeRoundTripsAndReportsBoundedFacts() {
        val evidence = sampleEvidence()
        val text = ArKernelLoopClosureGeometricGate.encode(evidence)
        assertTrue(text.length <= 16_384)
        assertTrue(text.lines().all { it.length <= 512 })
        assertTrue(text.startsWith("ARKLOOPGC1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("version=1"))
        assertTrue(text.contains("candidate_id=loop-01"))
        assertTrue(text.contains("keyframe_a=kf-001"))
        assertTrue(text.contains("keyframe_b=kf-050"))
        assertTrue(text.contains("quality_grade=unverified"))
        assertTrue(text.contains("pose_graph_optimization_disabled=true"))
        assertTrue(text.contains("global_bundle_adjustment_disabled=true"))
        assertTrue(text.contains("physical_ground_truth_disabled=true"))
        assertTrue(text.contains("automatic_map_mutation_disabled=true"))
        assertTrue(text.contains(
            "corr=0|gc-01|0.0|0.0|0.0|-1.0|0.0|0.0"))
        assertTrue(text.contains(
            "corr=2|gc-03|0.0|1.0|0.0|-1.0|1.001|0.0"))

        val parsed = ArKernelLoopClosureGeometricGate.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.NONE,
            parsed.rejection)
        assertEquals(evidence, parsed.evidence)
        assertEquals(
            text,
            ArKernelLoopClosureGeometricGate.encode(parsed.evidence!!))

        val report = ArKernelLoopClosureGeometricGate.evaluate(text)
        assertTrue(report.accepted)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.NONE,
            report.rejection)
        assertEquals(3, report.correspondenceCount)
        assertEquals(3, report.inlierCount)
        assertEquals(1.0, report.inlierRatio, 0.0)
        assertEquals(0.01, report.priorDisagreementMeters, 0.0)
        assertEquals(0.001, report.maxResidualMeters, 1e-15)
        assertEquals(
            0.0005773502691895622,
            report.rmseMeters,
            1e-15)
        assertEquals(sqrt(2.0), report.spreadMeters, 1e-15)
        assertEquals("unverified", report.qualityGrade)
        assertTrue(report.poseGraphOptimizationDisabled)
        assertTrue(report.globalBundleAdjustmentDisabled)
        assertTrue(report.physicalGroundTruthDisabled)
        assertTrue(report.automaticMapMutationDisabled)

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelLoopClosureGeometricGate.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(evidence, crlfParsed.evidence)
        assertTrue(
            ArKernelLoopClosureGeometricGate.parse("$text\n").accepted)
    }

    @Test
    fun rotatedUnitQuaternionAndLedgerCrossCheckStayDeterministic() {
        val rotatedText =
            ArKernelLoopClosureGeometricGate.encode(rotatedEvidence())
        val rotatedReport =
            ArKernelLoopClosureGeometricGate.evaluate(rotatedText)
        assertTrue(rotatedReport.accepted)
        assertEquals(3, rotatedReport.inlierCount)
        assertTrue(rotatedReport.maxResidualMeters < 1e-12)
        assertTrue(rotatedReport.rmseMeters < 1e-12)
        assertTrue(
            kotlin.math.abs(
                rotatedReport.spreadMeters - sqrt(2.0)) < 1e-12)

        val text = ArKernelLoopClosureGeometricGate.encode(sampleEvidence())
        val report = ArKernelLoopClosureGeometricGate
            .evaluateAgainstLedger(text, sampleLedger())
        assertTrue(report.accepted)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.NONE,
            report.rejection)
        assertEquals(3, report.inlierCount)
        assertEquals("unverified", report.qualityGrade)

        assertEquals(
            ArKernelLoopClosureGeometricRejection
                .LEDGER_CANDIDATE_NOT_FOUND,
            ArKernelLoopClosureGeometricGate.evaluateAgainstLedger(
                replaceValue(
                    text,
                    "candidate_id=loop-01",
                    "candidate_id=loop-99"),
                sampleLedger()).rejection)
        val smallBudget = ArKernelLoopClosureCandidateLedger(
            ledgerId = "loop.candidates.v1",
            candidates = listOf(
                ArKernelLoopClosureCandidate(
                    0, "loop-01", "kf-001", "kf-050",
                    0.25, 0.01, 2)),
        )
        val budgetReport = ArKernelLoopClosureGeometricGate
            .evaluateAgainstLedger(text, smallBudget)
        assertEquals(
            ArKernelLoopClosureGeometricRejection
                .LEDGER_INLIER_BUDGET_MISMATCH,
            budgetReport.rejection)
        assertEquals(3, budgetReport.inlierCount)
        assertFalse(budgetReport.accepted)

        val zeroBound = ArKernelLoopClosureCandidateLedger(
            ledgerId = "loop.candidates.v1",
            candidates = listOf(
                ArKernelLoopClosureCandidate(
                    0, "loop-01", "kf-001", "kf-050",
                    0.0, 0.0, 30)),
        )
        assertEquals(
            ArKernelLoopClosureGeometricRejection
                .LEDGER_RESIDUAL_BOUND_MISMATCH,
            ArKernelLoopClosureGeometricGate.evaluateAgainstLedger(
                text, zeroBound).rejection)
    }

    @Test
    fun maximumBoundedEnvelopeStaysCoherent() {
        val correspondences = List(64) { index ->
            val suffix = index.toString().padStart(2, '0')
            ArKernelLoopClosureGeometricCorrespondence(
                index,
                "c".repeat(30) + suffix,
                Vector3(index * 0.1, (index % 2) * 0.1, 0.0),
                Vector3(index * 0.1 - 1.0, (index % 2) * 0.1, 0.0),
            )
        }
        val evidence = sampleEvidence().copy(
            evidenceId = "gc.max.v1",
            correspondences = correspondences,
        )
        val text = ArKernelLoopClosureGeometricGate.encode(evidence)
        assertTrue(text.length <= 16_384)
        assertTrue(text.lines().all { it.length <= 512 })
        val parsed = ArKernelLoopClosureGeometricGate.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(evidence, parsed.evidence)
        assertEquals(
            text,
            ArKernelLoopClosureGeometricGate.encode(parsed.evidence!!))
        val report = ArKernelLoopClosureGeometricGate.evaluate(text)
        assertTrue(report.accepted)
        assertEquals(64, report.correspondenceCount)
        assertEquals(64, report.inlierCount)
        assertEquals(0.0, report.maxResidualMeters, 1e-12)
        assertEquals(0.0, report.rmseMeters, 1e-12)
        assertTrue(report.spreadMeters >= 0.05)
    }

    @Test
    fun parserRejectsMalformedUnsafeAndOutOfBudgetInputs() {
        val text =
            ArKernelLoopClosureGeometricGate.encode(sampleEvidence())
        val tooLarge = ArKernelLoopClosureGeometricGate.parse(
            "x".repeat(16_385))
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INPUT_TOO_LARGE,
            tooLarge.rejection)
        assertFalse(tooLarge.accepted)
        assertNull(tooLarge.evidence)
        assertTrue(tooLarge.detail.isNotBlank())
        assertEquals(
            0,
            ArKernelLoopClosureGeometricGate.evaluate(
                "x".repeat(16_385)).correspondenceCount)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.EMPTY_PLAN,
            ArKernelLoopClosureGeometricGate.parse("").rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.EMPTY_PLAN,
            ArKernelLoopClosureGeometricGate.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.EMPTY_LINE,
            ArKernelLoopClosureGeometricGate.parse("$text\n\n").rejection)
        val header = ArKernelLoopClosureGeometricGate.parse(
            replaceValue(text, "ARKLOOPGC1", "OTHER"))
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_HEADER,
            header.rejection)
        assertEquals(1, header.lineNumber)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(text, "version=1", "version=2")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_QUALITY,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text,
                    "quality_grade=unverified",
                    "quality_grade=verified")).rejection)
        for (key in listOf(
            "pose_graph_optimization_disabled",
            "global_bundle_adjustment_disabled",
            "physical_ground_truth_disabled",
            "automatic_map_mutation_disabled")) {
            assertEquals(
                ArKernelLoopClosureGeometricRejection.UNSAFE_INVARIANT,
                ArKernelLoopClosureGeometricGate.parse(
                    replaceValue(
                        text, "$key=true", "$key=false")).rejection)
        }
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_TOKEN,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(text, "candidate_id=loop-01",
                    "candidate_id=bad!id")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text,
                    "keyframe_b=kf-050",
                    "keyframe_b=kf-001")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text, "rotation_w=1.0", "rotation_w=NaN")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text, "rotation_w=1.0", "rotation_w=1e309")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_VALUE,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text,
                    "prior_disagreement_m=0.01",
                    "prior_disagreement_m=-0.01")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection
                .INVALID_CORRESPONDENCE_COUNT,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text,
                    "correspondence_count=3",
                    "correspondence_count=65")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text,
                    "correspondence_count=3",
                    "correspondence_count=2")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
            ArKernelLoopClosureGeometricGate.parse(
                replaceLine(text, 1, "candidate_id=loop-01")).rejection)
        val tooLongLine = ArKernelLoopClosureGeometricGate.parse(
            replaceLine(text, 20, "x".repeat(513)))
        assertEquals(
            ArKernelLoopClosureGeometricRejection.LINE_TOO_LONG,
            tooLongLine.rejection)
        assertEquals(21, tooLongLine.lineNumber)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.EMPTY_LINE,
            ArKernelLoopClosureGeometricGate.parse(
                replaceLine(text, 20, "")).rejection)
        val badEnd = ArKernelLoopClosureGeometricGate.parse(
            replaceLine(text, 23, "STOP"))
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_SCHEMA,
            badEnd.rejection)
        assertEquals(24, badEnd.lineNumber)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_ORDER,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text, "corr=0|gc-01", "corr=1|gc-01")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection
                .DUPLICATE_EVIDENCE_ID,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text, "corr=1|gc-02", "corr=1|gc-01")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_TOKEN,
            ArKernelLoopClosureGeometricGate.parse(
                replaceValue(
                    text, "corr=0|gc-01", "corr=0|bad!id")).rejection)
    }

    @Test
    fun deterministicGatesRejectUnsafeGeometricEvidence() {
        val text =
            ArKernelLoopClosureGeometricGate.encode(sampleEvidence())
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_ROTATION,
            ArKernelLoopClosureGeometricGate.evaluate(
                replaceValue(
                    text, "rotation_w=1.0", "rotation_w=2.0")).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INSUFFICIENT_EVIDENCE,
            ArKernelLoopClosureGeometricGate.evaluate(
                text,
                ArKernelLoopClosureGeometricPolicy(
                    minCorrespondences = 4)).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection
                .INVALID_CORRESPONDENCE_COUNT,
            ArKernelLoopClosureGeometricGate.evaluate(
                text,
                ArKernelLoopClosureGeometricPolicy(
                    minCorrespondences = 1,
                    maxCorrespondences = 2,
                    minInliers = 1)).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.DEGENERATE_GEOMETRY,
            ArKernelLoopClosureGeometricGate.evaluate(
                ArKernelLoopClosureGeometricGate.encode(
                    degenerateEvidence())).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection
                .PRIOR_DISAGREEMENT_TOO_LARGE,
            ArKernelLoopClosureGeometricGate.evaluate(
                text,
                ArKernelLoopClosureGeometricPolicy(
                    maxPriorDisagreementMeters = 0.001)).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.RESIDUAL_TOO_LARGE,
            ArKernelLoopClosureGeometricGate.evaluate(
                text,
                ArKernelLoopClosureGeometricPolicy(
                    maxResidualMeters = 1e-9)).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.RMSE_TOO_LARGE,
            ArKernelLoopClosureGeometricGate.evaluate(
                text,
                ArKernelLoopClosureGeometricPolicy(
                    maxRmseMeters = 1e-9)).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INLIERS_TOO_FEW,
            ArKernelLoopClosureGeometricGate.evaluate(
                text,
                ArKernelLoopClosureGeometricPolicy(
                    minInliers = 4)).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INLIER_RATIO_TOO_LOW,
            ArKernelLoopClosureGeometricGate.evaluate(
                text,
                ArKernelLoopClosureGeometricPolicy(
                    minInliers = 2,
                    minInlierRatio = 0.9,
                    inlierThresholdMeters = 1e-9)).rejection)
        assertEquals(
            ArKernelLoopClosureGeometricRejection.INVALID_NUMBER,
            ArKernelLoopClosureGeometricGate.evaluate(
                replaceValue(
                    text,
                    "corr=0|gc-01|0.0|0.0|0.0|-1.0|0.0|0.0",
                    "corr=0|gc-01|1e308|0.0|0.0|-1.0|0.0|0.0"))
                .rejection)
    }

    @Test
    fun constructorsRejectInvalidPoliciesAndEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureGeometricPolicy(maxCorrespondences = 65)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureGeometricPolicy(minCorrespondences = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureGeometricPolicy(
                inlierThresholdMeters = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureGeometricPolicy(minInlierRatio = 1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureGeometricPolicy(minSpreadMeters = 1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureGeometricCorrespondence(
                0,
                "bad!id",
                Vector3(0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelLoopClosureGeometricCorrespondence(
                0,
                "gc-01",
                Vector3(Double.NaN, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampleEvidence().copy(
                poseGraphOptimizationDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampleEvidence().copy(keyframeB = "kf-001")
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampleEvidence().copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampleEvidence().copy(
                rotation = Rotation(Double.NaN, 0.0, 0.0, 0.0))
        }
        val duplicate = sampleEvidence().correspondences.toMutableList()
        duplicate[1] = duplicate[1].copy(
            evidenceId = duplicate[0].evidenceId)
        assertThrows(IllegalArgumentException::class.java) {
            sampleEvidence().copy(correspondences = duplicate)
        }
        val nonContiguous =
            sampleEvidence().correspondences.toMutableList()
        nonContiguous[1] = nonContiguous[1].copy(index = 5)
        assertThrows(IllegalArgumentException::class.java) {
            sampleEvidence().copy(correspondences = nonContiguous)
        }
    }

    private fun sampleEvidence(): ArKernelLoopClosureGeometricEvidence =
        ArKernelLoopClosureGeometricEvidence(
            evidenceId = "gc.evidence.v1",
            candidateId = "loop-01",
            keyframeA = "kf-001",
            keyframeB = "kf-050",
            rotation = Rotation(1.0, 0.0, 0.0, 0.0),
            translation = Vector3(1.0, 0.0, 0.0),
            priorDisagreementMeters = 0.01,
            correspondences = listOf(
                ArKernelLoopClosureGeometricCorrespondence(
                    0, "gc-01",
                    Vector3(0.0, 0.0, 0.0),
                    Vector3(-1.0, 0.0, 0.0)),
                ArKernelLoopClosureGeometricCorrespondence(
                    1, "gc-02",
                    Vector3(1.0, 0.0, 0.0),
                    Vector3(0.0, 0.0, 0.0)),
                ArKernelLoopClosureGeometricCorrespondence(
                    2, "gc-03",
                    Vector3(0.0, 1.0, 0.0),
                    Vector3(-1.0, 1.001, 0.0)),
            ),
        )

    private fun rotatedEvidence(): ArKernelLoopClosureGeometricEvidence {
        val root = 0.7071067811865476
        return sampleEvidence().copy(
            evidenceId = "gc.evidence.rot",
            rotation = Rotation(root, 0.0, 0.0, root),
            correspondences = listOf(
                ArKernelLoopClosureGeometricCorrespondence(
                    0, "gc-01",
                    Vector3(1.0, 0.0, 0.0),
                    Vector3(0.0, 0.0, 0.0)),
                ArKernelLoopClosureGeometricCorrespondence(
                    1, "gc-02",
                    Vector3(1.0, 1.0, 0.0),
                    Vector3(1.0, 0.0, 0.0)),
                ArKernelLoopClosureGeometricCorrespondence(
                    2, "gc-03",
                    Vector3(0.0, 0.0, 0.0),
                    Vector3(0.0, 1.0, 0.0)),
            ),
        )
    }

    private fun degenerateEvidence(): ArKernelLoopClosureGeometricEvidence =
        sampleEvidence().copy(
            evidenceId = "gc.evidence.degenerate",
            correspondences = listOf(
                ArKernelLoopClosureGeometricCorrespondence(
                    0, "gc-01",
                    Vector3(0.0, 0.0, 0.0),
                    Vector3(-1.0, 0.0, 0.0)),
                ArKernelLoopClosureGeometricCorrespondence(
                    1, "gc-02",
                    Vector3(0.01, 0.0, 0.0),
                    Vector3(-0.99, 0.0, 0.0)),
                ArKernelLoopClosureGeometricCorrespondence(
                    2, "gc-03",
                    Vector3(0.0, 0.01, 0.0),
                    Vector3(-1.0, 0.01, 0.0)),
            ),
        )

    private fun sampleLedger(): ArKernelLoopClosureCandidateLedger =
        ArKernelLoopClosureCandidateLedger(
            ledgerId = "loop.candidates.v1",
            revision = 1,
            candidates = listOf(
                ArKernelLoopClosureCandidate(
                    0, "loop-01", "kf-001", "kf-050",
                    0.25, 0.01, 30),
                ArKernelLoopClosureCandidate(
                    1, "loop-02", "kf-002", "kf-051",
                    0.5, 0.02, 20),
            ),
        )

    private fun replaceValue(
        text: String,
        old: String,
        new: String,
    ): String {
        val index = text.indexOf(old)
        assertTrue(index >= 0)
        return text.substring(0, index) + new +
            text.substring(index + old.length)
    }

    private fun replaceLine(
        text: String,
        lineIndex: Int,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        lines[lineIndex] = value
        return lines.joinToString("\n")
    }
}
