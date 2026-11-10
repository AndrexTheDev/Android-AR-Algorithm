package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelRuntimeAdapterDeploymentManifestTest {
    @Test
    fun deploymentManifestRoundTripsAndReportsBoundedFacts() {
        val manifest = sampleManifest()
        val text = ArKernelRuntimeAdapterDeploymentBundle.encode(manifest)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.startsWith("ARKRTAD1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("quality_grade=unverified"))
        assertTrue(text.contains("cpu_fallback_required=true"))
        assertTrue(text.contains("startup_sentinel_required=true"))
        assertTrue(text.contains("read_only_factory_admission=true"))
        assertTrue(text.contains("model_name_free_identity=true"))
        assertTrue(text.contains("separate_packaging_required=true"))
        assertTrue(text.contains("adapter=0|alpha.adapter|1.0|cpu.f32|cpu.v1|"))
        assertTrue(text.contains("|4096|declared"))

        val parsed = ArKernelRuntimeAdapterDeploymentBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.NONE,
            parsed.rejection)
        val parsedManifest = parsed.manifest!!
        assertEquals(manifest, parsedManifest)
        assertEquals(
            text,
            ArKernelRuntimeAdapterDeploymentBundle.encode(parsedManifest))

        val report =
            ArKernelRuntimeAdapterDeploymentBundle.summarize(parsedManifest)
        assertEquals(2, report.adapterCount)
        assertEquals(12_288L, report.totalBytes)
        assertEquals(2, report.declaredCount)
        assertEquals(2, report.distinctBackendProfiles)
        assertEquals(2, report.distinctDriverProfiles)
        assertEquals("unverified", report.qualityGrade)

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelRuntimeAdapterDeploymentBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(manifest, crlfParsed.manifest)
        assertTrue(
            ArKernelRuntimeAdapterDeploymentBundle.parse("$text\n").accepted)
    }

    @Test
    fun maximumBoundedEnvelopeStaysCoherent() {
        val adapters = List(16) { index ->
            ArKernelRuntimeAdapterDeploymentEntry(
                index,
                "a".repeat(30) + index.toString().padStart(2, '0'),
                "v".repeat(32),
                "b".repeat(32),
                "d".repeat(32),
                "c".repeat(64),
                1_099_511_627_776L,
            )
        }
        val manifest = ArKernelRuntimeAdapterDeploymentManifest(
            manifestId = "m".repeat(32),
            adapters = adapters,
        )
        val text = ArKernelRuntimeAdapterDeploymentBundle.encode(manifest)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        val parsed = ArKernelRuntimeAdapterDeploymentBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(manifest, parsed.manifest)
        assertEquals(
            text,
            ArKernelRuntimeAdapterDeploymentBundle.encode(parsed.manifest!!))

        val report = ArKernelRuntimeAdapterDeploymentBundle.summarize(manifest)
        assertEquals(16, report.adapterCount)
        assertEquals(
            16L * 1_099_511_627_776L, report.totalBytes)
        assertEquals(16, report.declaredCount)
        assertEquals(1, report.distinctBackendProfiles)
        assertEquals(1, report.distinctDriverProfiles)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun parserRejectsMalformedUnsafeAndOutOfBudgetInputs() {
        val text =
            ArKernelRuntimeAdapterDeploymentBundle.encode(sampleManifest())
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INPUT_TOO_LARGE,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                "x".repeat(8_193)).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.EMPTY_PLAN,
            ArKernelRuntimeAdapterDeploymentBundle.parse("").rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.EMPTY_PLAN,
            ArKernelRuntimeAdapterDeploymentBundle.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_HEADER,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("ARKRTAD1", "OTHER")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("version=1", "version=2")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_QUALITY,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("quality_grade=unverified",
                    "quality_grade=verified")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.UNSAFE_INVARIANT,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("cpu_fallback_required=true",
                    "cpu_fallback_required=false")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.UNSAFE_INVARIANT,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("startup_sentinel_required=true",
                    "startup_sentinel_required=false")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.UNSAFE_INVARIANT,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("read_only_factory_admission=true",
                    "read_only_factory_admission=false")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.UNSAFE_INVARIANT,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("model_name_free_identity=true",
                    "model_name_free_identity=false")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.UNSAFE_INVARIANT,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("separate_packaging_required=true",
                    "separate_packaging_required=false")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("revision=1", "revision=0")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_ADAPTER_COUNT,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("adapter_count=2",
                    "adapter_count=0")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_ADAPTER_COUNT,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("adapter_count=2",
                    "adapter_count=17")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("adapter_count=2",
                    "adapter_count=3")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("END", "STOP")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.EMPTY_LINE,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                blankLine(text, 12)).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("adapter=0|", "row=0|")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_SCHEMA,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("adapter=0|alpha.adapter|1.0|",
                    "adapter=0|alpha.adapter|")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.DUPLICATE_ADAPTER_ID,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(text, 1, 1, "alpha.adapter")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_ORDER,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(text, 1, 0, "2")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_DIGEST,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(text, 0, 5, "A".repeat(64))).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_DIGEST,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(text, 0, 5, "a".repeat(63))).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_STATE,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(text, 0, 7, "loaded")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(text, 0, 6, "0")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(
                    text, 0, 6, "1099511627777")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_VALUE,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(text, 0, 6, "abc")).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(
                    text, 0, 1, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(
                    text, 0, 2, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(
                    text, 0, 3, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(
                    text, 0, 4, "x".repeat(33))).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.INVALID_TOKEN,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                text.replace("manifest_id=runtime.adapters.v1",
                    "manifest_id=" + "x".repeat(33))).rejection)
        assertEquals(
            ArKernelRuntimeAdapterDeploymentRejection.LINE_TOO_LONG,
            ArKernelRuntimeAdapterDeploymentBundle.parse(
                replaceAdapterField(
                    text, 0, 1, "x".repeat(300))).rejection)
    }

    @Test
    fun manifestConstructionRejectsUnsafeAndMalformedAdapters() {
        val adapters = sampleAdapters()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentManifest(
                "ok.id", adapters = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentManifest(
                "ok.id",
                adapters = List(17) { index ->
                    adapters[0].copy(
                        index = index, adapterId = "a.$index")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentManifest(
                "ok.id",
                cpuFallbackRequired = false,
                adapters = adapters,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentManifest(
                "ok.id",
                adapters = listOf(
                    adapters[0],
                    adapters[0].copy(index = 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentManifest(
                "ok.id",
                adapters = listOf(
                    adapters[0].copy(index = 1),
                    adapters[1]),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentEntry(
                0, "alpha.adapter", "1.0", "cpu.f32", "cpu.v1",
                "a".repeat(64), 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentEntry(
                0, "alpha.adapter", "1.0", "cpu.f32", "cpu.v1",
                "a".repeat(64), 1_099_511_627_777L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentEntry(
                0, "alpha.adapter", "1.0", "cpu.f32", "cpu.v1",
                "A".repeat(64), 4096L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentEntry(
                0, "alpha.adapter", "1.0", "cpu.f32", "cpu.v1",
                "a".repeat(64), 4096L, "loaded")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelRuntimeAdapterDeploymentEntry(
                0, "x".repeat(33), "1.0", "cpu.f32", "cpu.v1",
                "a".repeat(64), 4096L)
        }
    }

    private fun sampleManifest(): ArKernelRuntimeAdapterDeploymentManifest =
        ArKernelRuntimeAdapterDeploymentManifest(
            manifestId = "runtime.adapters.v1",
            revision = 1,
            adapters = sampleAdapters(),
        )

    private fun sampleAdapters():
        List<ArKernelRuntimeAdapterDeploymentEntry> = listOf(
        ArKernelRuntimeAdapterDeploymentEntry(
            0,
            "alpha.adapter",
            "1.0",
            "cpu.f32",
            "cpu.v1",
            "a".repeat(64),
            4096L,
        ),
        ArKernelRuntimeAdapterDeploymentEntry(
            1,
            "beta.adapter",
            "2.0",
            "nnapi.i8",
            "nnapi.v3",
            "b".repeat(64),
            8192L,
        ),
    )

    private fun blankLine(text: String, lineIndex: Int): String {
        val lines = text.lines().toMutableList()
        lines[lineIndex] = ""
        return lines.joinToString("\n")
    }

    private fun replaceAdapterField(
        text: String,
        adapterIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        val lineIndex = 11 + adapterIndex
        val prefix = "adapter="
        val fields = lines[lineIndex].substringAfter(prefix).split('|')
            .toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
