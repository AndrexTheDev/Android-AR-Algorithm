package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelDatasetManifestTest {
    @Test
    fun manifestRoundTripsAndReportsBoundedFacts() {
        val manifest = sampleManifest()
        val text = ArKernelDatasetManifestBundle.encode(manifest)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.startsWith("ARKDSMAN1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("quality_grade=unverified"))
        assertTrue(text.contains("dataset_loading_disabled=true"))
        assertTrue(text.contains("automatic_budget_disabled=true"))

        val parsed = ArKernelDatasetManifestBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelDatasetManifestRejection.NONE, parsed.rejection)
        val parsedManifest = parsed.manifest!!
        assertEquals(manifest, parsedManifest)
        assertEquals(text, ArKernelDatasetManifestBundle.encode(parsedManifest))

        val report = ArKernelDatasetManifestBundle.summarize(parsedManifest)
        assertEquals(2, report.entryCount)
        assertEquals(3_000L, report.totalBytes)
        assertEquals(2, report.distinctLicenses)
        assertEquals(2, report.distinctRoles)
        assertEquals("unverified", report.qualityGrade)

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed = ArKernelDatasetManifestBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(manifest, crlfParsed.manifest)
        assertTrue(ArKernelDatasetManifestBundle.parse("$text\n").accepted)
    }

    @Test
    fun parserRejectsMalformedUnsafeAndOutOfBudgetInputs() {
        val text = ArKernelDatasetManifestBundle.encode(sampleManifest())
        assertEquals(
            ArKernelDatasetManifestRejection.INPUT_TOO_LARGE,
            ArKernelDatasetManifestBundle.parse("x".repeat(8_193)).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.EMPTY_MANIFEST,
            ArKernelDatasetManifestBundle.parse("").rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.EMPTY_MANIFEST,
            ArKernelDatasetManifestBundle.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_HEADER,
            ArKernelDatasetManifestBundle.parse(
                text.replace("ARKDSMAN1", "OTHER")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_VALUE,
            ArKernelDatasetManifestBundle.parse(
                text.replace("version=1", "version=2")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_QUALITY,
            ArKernelDatasetManifestBundle.parse(
                text.replace("quality_grade=unverified",
                    "quality_grade=verified")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.UNSAFE_INVARIANT,
            ArKernelDatasetManifestBundle.parse(
                text.replace("dataset_loading_disabled=true",
                    "dataset_loading_disabled=false")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.UNSAFE_INVARIANT,
            ArKernelDatasetManifestBundle.parse(
                text.replace("automatic_budget_disabled=true",
                    "automatic_budget_disabled=false")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_VALUE,
            ArKernelDatasetManifestBundle.parse(
                text.replace("revision=1", "revision=0")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.TOO_MANY_ENTRIES,
            ArKernelDatasetManifestBundle.parse(
                text.replace("entry_count=2", "entry_count=65")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_SCHEMA,
            ArKernelDatasetManifestBundle.parse(
                text.replace("entry_count=2", "entry_count=3")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_SCHEMA,
            ArKernelDatasetManifestBundle.parse(
                text.replace("END", "STOP")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.EMPTY_LINE,
            ArKernelDatasetManifestBundle.parse(
                text.replace("END", "\nEND")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_SCHEMA,
            ArKernelDatasetManifestBundle.parse(
                text.replace("entry=0|", "row=0|")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_SCHEMA,
            ArKernelDatasetManifestBundle.parse(
                text.replace("entry=0|mh.01|cc.by.4.0|",
                    "entry=0|mh.01|")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_HASH,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 0, 3, "A".repeat(64))).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_HASH,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 0, 3, "not-a-hash")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.DUPLICATE_DATASET_ID,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 1, 1, "mh.01")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_ORDER,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 1, 0, "2")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_VALUE,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 0, 4, "0")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_VALUE,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 0, 4, "abc")).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_VALUE,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 0, 4,
                    (1_099_511_627_776L + 1L).toString())).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_TOKEN,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 0, 1, "x".repeat(65))).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_TOKEN,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 0, 2, "x".repeat(65))).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_TOKEN,
            ArKernelDatasetManifestBundle.parse(
                replaceEntryField(text, 0, 5, "x".repeat(65))).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.INVALID_TOKEN,
            ArKernelDatasetManifestBundle.parse(
                text.replace("manifest_id=redmi.corpus.v1",
                    "manifest_id=" + "x".repeat(65))).rejection)
        assertEquals(
            ArKernelDatasetManifestRejection.LINE_TOO_LONG,
            ArKernelDatasetManifestBundle.parse(
                text.replace("cc.by.4.0", "x".repeat(300))).rejection)
    }

    @Test
    fun manifestConstructionRejectsUnsafeAndMalformedEntries() {
        val entries = sampleEntries()
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDatasetManifest("ok.id", entries = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDatasetManifest(
                "ok.id",
                entries = List(65) { index ->
                    entries[0].copy(index = index, datasetId = "d.$index")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDatasetManifest(
                "ok.id",
                datasetLoadingDisabled = false,
                entries = entries,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDatasetManifest(
                "ok.id",
                entries = listOf(entries[0], entries[0].copy(index = 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDatasetManifest(
                "ok.id",
                entries = listOf(entries[0].copy(index = 1), entries[1]),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDatasetManifestEntry(
                0, "mh.01", "cc.by.4.0", "A".repeat(64), 1_000L, "train")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDatasetManifestEntry(
                0, "mh.01", "cc.by.4.0", "a".repeat(64), 0L, "train")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelDatasetManifestEntry(
                0, "mh.01", "cc.by.4.0", "a".repeat(64), 1_000L,
                "x".repeat(65))
        }
    }

    private fun sampleManifest(): ArKernelDatasetManifest =
        ArKernelDatasetManifest(
            manifestId = "redmi.corpus.v1",
            revision = 1,
            entries = sampleEntries(),
        )

    private fun sampleEntries(): List<ArKernelDatasetManifestEntry> = listOf(
        ArKernelDatasetManifestEntry(
            0, "mh.01", "cc.by.4.0", "a".repeat(64), 1_000L, "train"),
        ArKernelDatasetManifestEntry(
            1, "tum.vi", "apache.2.0", "b".repeat(64), 2_000L, "held.out"),
    )

    private fun replaceEntryField(
        text: String,
        entryIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        val lineIndex = 10 + entryIndex
        val prefix = "entry="
        val fields = lines[lineIndex].substringAfter(prefix).split('|')
            .toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
