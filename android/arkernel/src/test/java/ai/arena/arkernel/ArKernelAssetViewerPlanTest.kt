package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelAssetViewerPlanTest {
    @Test
    fun viewerPlanRoundTripsAndReportsBoundedFacts() {
        val plan = samplePlan()
        val text = ArKernelAssetViewerPlanBundle.encode(plan)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.startsWith("ARKVIEW1\n"))
        assertTrue(text.endsWith("\nEND"))
        assertTrue(text.contains("version=1"))
        assertTrue(text.contains("plan_id=viewer.plan.v1"))
        assertTrue(text.contains("revision=1"))
        assertTrue(text.contains("view_count=2"))
        assertTrue(text.contains("asset_loading_disabled=true"))
        assertTrue(text.contains(
            "renderer_initialization_disabled=true"))
        assertTrue(text.contains("automatic_upload_disabled=true"))
        assertTrue(text.contains(
            "external_format_import_disabled=true"))
        assertTrue(text.contains("quality_grade=unverified"))

        val parsed = ArKernelAssetViewerPlanBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(
            ArKernelAssetViewerPlanRejection.NONE,
            parsed.rejection)
        assertEquals(plan, parsed.plan)
        assertEquals(
            text,
            ArKernelAssetViewerPlanBundle.encode(parsed.plan!!))

        val report =
            ArKernelAssetViewerPlanBundle.summarize(parsed.plan!!)
        assertEquals(2, report.viewCount)
        assertEquals(2, report.distinctAssetRefs)
        assertEquals(1, report.orbitCount)
        assertEquals(1, report.normalsEnabledCount)
        assertEquals(4.0, report.maxDistanceMeters, 0.0)
        assertEquals(2_073_600L, report.maxFramePixels)
        assertEquals("unverified", report.qualityGrade)

        val bomCrlf = "\uFEFF" + text.replace("\n", "\r\n") + "\r\n"
        val crlfParsed =
            ArKernelAssetViewerPlanBundle.parse(bomCrlf)
        assertTrue(crlfParsed.accepted)
        assertEquals(plan, crlfParsed.plan)
        assertTrue(
            ArKernelAssetViewerPlanBundle.parse("$text\n").accepted)
    }

    @Test
    fun maximumBoundedViewerPlanStaysCoherent() {
        val views = List(16) { index ->
            val suffix = index.toString().padStart(2, '0')
            val generation = index.toString().padStart(19, '0')
            ArKernelAssetViewerPlanEntry(
                index,
                "v".repeat(14) + suffix,
                "arkglb://" + "s".repeat(16) + "/" +
                    generation + "-" + "a".repeat(16) + ".glb",
                ArKernelAssetViewMode.ORBIT,
                ArKernelAssetColorMode.BASE_COLOR,
                0.0,
                0.0,
                1.0,
                60.0,
                640,
                480,
                index % 2 == 0,
            )
        }
        val plan = samplePlan().copy(
            planId = "viewer.max.v1",
            views = views,
        )
        val text = ArKernelAssetViewerPlanBundle.encode(plan)
        assertTrue(text.length <= 8_192)
        assertTrue(text.lines().all { it.length <= 256 })
        assertTrue(text.contains("view_count=16"))
        val parsed = ArKernelAssetViewerPlanBundle.parse(text)
        assertTrue(parsed.accepted)
        assertEquals(plan, parsed.plan)
        assertEquals(
            text,
            ArKernelAssetViewerPlanBundle.encode(parsed.plan!!))
        val report =
            ArKernelAssetViewerPlanBundle.summarize(parsed.plan!!)
        assertEquals(16, report.viewCount)
        assertEquals(16, report.distinctAssetRefs)
        assertEquals(16, report.orbitCount)
        assertEquals(8, report.normalsEnabledCount)
        assertEquals(1.0, report.maxDistanceMeters, 0.0)
        assertEquals(640L * 480L, report.maxFramePixels)
        assertEquals("unverified", report.qualityGrade)
    }

    @Test
    fun parserRejectsMalformedUnsafeAndOutOfBudgetInputs() {
        val text =
            ArKernelAssetViewerPlanBundle.encode(samplePlan())
        val tooLarge = ArKernelAssetViewerPlanBundle.parse(
            "x".repeat(8_193))
        assertEquals(
            ArKernelAssetViewerPlanRejection.INPUT_TOO_LARGE,
            tooLarge.rejection)
        assertFalse(tooLarge.accepted)
        assertNull(tooLarge.plan)
        assertTrue(tooLarge.detail.isNotBlank())
        assertEquals(
            ArKernelAssetViewerPlanRejection.EMPTY_PLAN,
            ArKernelAssetViewerPlanBundle.parse("").rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.EMPTY_PLAN,
            ArKernelAssetViewerPlanBundle.parse("\uFEFF").rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.EMPTY_LINE,
            ArKernelAssetViewerPlanBundle.parse(
                "$text\n\n").rejection)
        val header = ArKernelAssetViewerPlanBundle.parse(
            replaceValue(text, "ARKVIEW1", "OTHER"))
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_HEADER,
            header.rejection)
        assertEquals(1, header.lineNumber)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_VALUE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(text, "version=1", "version=2"))
                .rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_QUALITY,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(
                    text,
                    "quality_grade=unverified",
                    "quality_grade=verified")).rejection)
        for (key in listOf(
            "asset_loading_disabled",
            "renderer_initialization_disabled",
            "automatic_upload_disabled",
            "external_format_import_disabled")) {
            assertEquals(
                ArKernelAssetViewerPlanRejection.UNSAFE_INVARIANT,
                ArKernelAssetViewerPlanBundle.parse(
                    replaceValue(
                        text, "$key=true", "$key=false")).rejection)
        }
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_TOKEN,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(
                    text,
                    "plan_id=viewer.plan.v1",
                    "plan_id=bad!id")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_VALUE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(text, "revision=1",
                    "revision=1000001")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_VIEW_COUNT,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(text, "view_count=2",
                    "view_count=17")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(text, "view_count=2",
                    "view_count=1")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
            ArKernelAssetViewerPlanBundle.parse(
                replaceLine(
                    text, 1, "plan_id=viewer.plan.v1")).rejection)
        val tooLongLine = ArKernelAssetViewerPlanBundle.parse(
            replaceLine(text, 10, "x".repeat(257)))
        assertEquals(
            ArKernelAssetViewerPlanRejection.LINE_TOO_LONG,
            tooLongLine.rejection)
        assertEquals(11, tooLongLine.lineNumber)
        assertEquals(
            ArKernelAssetViewerPlanRejection.EMPTY_LINE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceLine(text, 10, "")).rejection)
        val badEnd = ArKernelAssetViewerPlanBundle.parse(
            replaceLine(text, 12, "STOP"))
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
            badEnd.rejection)
        assertEquals(13, badEnd.lineNumber)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_ORDER,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(
                    text, "view=0|view-01",
                    "view=1|view-01")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.DUPLICATE_VIEW_ID,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(
                    text, "view=1|view-02",
                    "view=1|view-01")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_TOKEN,
            ArKernelAssetViewerPlanBundle.parse(
                replaceValue(
                    text, "view=0|view-01",
                    "view=0|bad!id")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection
                .INVALID_ASSET_REFERENCE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(
                    text, 10, 2,
                    "https://example/asset.glb")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_VIEW_MODE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(
                    text, 10, 3, "cinematic")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_COLOR_MODE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(
                    text, 10, 4, "thermal")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_NUMBER,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(text, 10, 5, "NaN")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_NUMBER,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(text, 10, 5, "1e309")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_CAMERA_RANGE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(text, 10, 5, "181.0")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_CAMERA_RANGE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(text, 10, 6, "-90.0")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_CAMERA_RANGE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(text, 10, 7, "0.01")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_CAMERA_RANGE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(text, 10, 8, "121.0")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_FRAME_SIZE,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(text, 10, 9, "4097")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_BOOLEAN,
            ArKernelAssetViewerPlanBundle.parse(
                replaceField(text, 10, 11, "true")).rejection)
        assertEquals(
            ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
            ArKernelAssetViewerPlanBundle.parse(
                replaceLine(
                    text, 10, "view=0|view-01|only-three"))
                .rejection)
    }

    @Test
    fun constructorsRejectInvalidPlansAndViews() {
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(assetLoadingDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(
                rendererInitializationDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(automaticUploadDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(
                externalFormatImportDisabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(qualityGrade = "verified")
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(views = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(revision = 0)
        }
        val duplicate = samplePlan().views.toMutableList()
        duplicate[1] = duplicate[1].copy(
            viewId = duplicate[0].viewId)
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(views = duplicate)
        }
        val nonContiguous = samplePlan().views.toMutableList()
        nonContiguous[1] = nonContiguous[1].copy(index = 5)
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().copy(views = nonContiguous)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(viewId = "bad!id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(
                assetRef = "https://example/asset.glb")
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(
                yawDegrees = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(yawDegrees = 181.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(pitchDegrees = -90.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(distanceMeters = 0.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(
                fieldOfViewDegrees = 121.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(frameWidth = 4_097)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePlan().views[0].copy(frameHeight = 0)
        }
    }

    private fun samplePlan(): ArKernelAssetViewerPlan =
        ArKernelAssetViewerPlan(
            planId = "viewer.plan.v1",
            revision = 1,
            views = listOf(
                ArKernelAssetViewerPlanEntry(
                    0,
                    "view-01",
                    "arkglb://demo_session/" +
                        "0000000000000000001-asset-01.glb",
                    ArKernelAssetViewMode.ORBIT,
                    ArKernelAssetColorMode.BASE_COLOR,
                    15.0,
                    -10.0,
                    2.5,
                    60.0,
                    1920,
                    1080,
                    true,
                ),
                ArKernelAssetViewerPlanEntry(
                    1,
                    "view-02",
                    "arkglb://demo_session/" +
                        "0000000000000000002-asset-02.glb",
                    ArKernelAssetViewMode.FRONT,
                    ArKernelAssetColorMode.NORMALS,
                    -45.0,
                    20.0,
                    4.0,
                    45.0,
                    1280,
                    720,
                    false,
                ),
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

    private fun replaceField(
        text: String,
        lineIndex: Int,
        fieldIndex: Int,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        val prefix = "view="
        val fields = lines[lineIndex].substringAfter(prefix)
            .split('|').toMutableList()
        fields[fieldIndex] = value
        lines[lineIndex] = prefix + fields.joinToString("|")
        return lines.joinToString("\n")
    }
}
