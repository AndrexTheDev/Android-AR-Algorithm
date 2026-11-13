package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Checks the `ARKINTR1` record.
 *
 * The board is projected through a camera written down first, so the corners
 * are real rather than invented, and the file can be checked end to end:
 * written, read back, and recomputed.
 *
 * The test that matters most here is the one where the numbers are altered.
 * Every other contract in this repository is checked for being *well formed*,
 * which catches mistakes in writing and nothing else. A file whose intrinsics
 * were copied from another device, or edited, or produced by a different
 * estimator is perfectly well formed, and that is exactly the failure worth
 * catching - so this record carries the corners it was computed from, and the
 * reader recomputes the answer rather than trusting it.
 */
class ArKernelIntrinsicsObservationTest {

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val COLUMNS = 9
        const val ROWS = 7
        const val EXPECTED = COLUMNS * ROWS

        const val FX = 1100.0
        const val FY = 1100.0
        const val CX = 640.0
        const val CY = 360.0
        const val K1 = -0.12
        const val K2 = 0.02

        private fun rotation(
            xDeg: Double,
            yDeg: Double,
            zDeg: Double,
        ): Array<DoubleArray> {
            val ax = Math.toRadians(xDeg)
            val ay = Math.toRadians(yDeg)
            val az = Math.toRadians(zDeg)
            val rx = arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(0.0, cos(ax), -sin(ax)),
                doubleArrayOf(0.0, sin(ax), cos(ax)))
            val ry = arrayOf(
                doubleArrayOf(cos(ay), 0.0, sin(ay)),
                doubleArrayOf(0.0, 1.0, 0.0),
                doubleArrayOf(-sin(ay), 0.0, cos(ay)))
            val rz = arrayOf(
                doubleArrayOf(cos(az), -sin(az), 0.0),
                doubleArrayOf(sin(az), cos(az), 0.0),
                doubleArrayOf(0.0, 0.0, 1.0))
            return mul(mul(rz, ry), rx)
        }

        private fun mul(
            a: Array<DoubleArray>,
            b: Array<DoubleArray>,
        ): Array<DoubleArray> {
            val out = Array(3) { DoubleArray(3) }
            for (i in 0..2) {
                for (j in 0..2) {
                    out[i][j] = a[i][0] * b[0][j] +
                        a[i][1] * b[1][j] + a[i][2] * b[2][j]
                }
            }
            return out
        }

        /** Grid corner (column, row) as distorted image pixels. */
        private fun project(
            r: Array<DoubleArray>,
            tx: Double,
            ty: Double,
            tz: Double,
            column: Int,
            row: Int,
        ): ArKernelIntrinsicsPoint {
            val px = r[0][0] * column + r[0][1] * row + tx
            val py = r[1][0] * column + r[1][1] * row + ty
            val pz = r[2][0] * column + r[2][1] * row + tz
            val xn = px / pz
            val yn = py / pz
            val r2 = xn * xn + yn * yn
            val factor = 1.0 + K1 * r2 + K2 * r2 * r2
            return ArKernelIntrinsicsPoint(
                FX * xn * factor + CX,
                FY * yn * factor + CY,
            )
        }
    }

    private fun round6(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.6f", value)

    /** Eight views of the same board, from spread-out directions. */
    private fun views(): List<ArKernelIntrinsicsObservationView> {
        val poses = listOf(
            Triple(0.0, 0.0, 0.0),
            Triple(28.0, -12.0, 0.0),
            Triple(-26.0, 14.0, 0.0),
            Triple(6.0, 30.0, 0.0),
            Triple(-8.0, -29.0, 0.0),
            Triple(34.0, 22.0, 0.0),
            Triple(-33.0, -20.0, 0.0),
            Triple(16.0, -34.0, 0.0),
        )
        return poses.mapIndexed { index, (xDeg, yDeg, zDeg) ->
            val r = rotation(xDeg, yDeg, zDeg)
            val points = ArrayList<ArKernelIntrinsicsPoint>(EXPECTED)
            for (row in 0 until ROWS) {
                for (column in 0 until COLUMNS) {
                    points += project(r, -4.0, -3.0, 19.0, column, row)
                }
            }
            ArKernelIntrinsicsObservationView(index, points)
        }
    }

    private fun bundle(
        views: List<ArKernelIntrinsicsObservationView> = views(),
    ): ArKernelIntrinsicsObservationBundle {
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views = views.map { ArKernelIntrinsicsView(it.corners) },
            gridColumns = COLUMNS,
            gridRows = ROWS,
            imageWidthPx = WIDTH,
            imageHeightPx = HEIGHT,
        )
        assertTrue(
            "the fixture must estimate cleanly: ${estimate.detail}",
            estimate.accepted,
        )
        return ArKernelIntrinsicsObservationBundle(
            sessionId = "session-1",
            deviceToken = "device-1",
            gridColumns = COLUMNS,
            gridRows = ROWS,
            imageWidth = WIDTH,
            imageHeight = HEIGHT,
            views = views,
            intrinsics = estimate.intrinsics!!,
            rmsPx = estimate.rmsPx,
            maxPx = estimate.maxPx,
            detail = estimate.detail,
        )
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun aWrittenFileIsReadBackWhole() {
        val written = bundle()
        val parsed = ArKernelIntrinsicsObservationAdapter.parse(
            ArKernelIntrinsicsObservationAdapter.encode(written))
        assertTrue(parsed.detail, parsed.accepted)
        val read = parsed.bundle!!
        assertEquals(written.sessionId, read.sessionId)
        assertEquals(written.deviceToken, read.deviceToken)
        assertEquals(written.gridColumns, read.gridColumns)
        assertEquals(written.gridRows, read.gridRows)
        assertEquals(written.imageWidth, read.imageWidth)
        assertEquals(written.imageHeight, read.imageHeight)
        assertEquals(written.views.size, read.views.size)
        assertEquals(EXPECTED, read.views.first().corners.size)
        assertEquals(written.intrinsics.fxPx, read.intrinsics.fxPx, 0.01)
        assertEquals(written.intrinsics.fyPx, read.intrinsics.fyPx, 0.01)
        assertEquals(written.intrinsics.cxPx, read.intrinsics.cxPx, 0.05)
        assertEquals(written.intrinsics.cyPx, read.intrinsics.cyPx, 0.05)
        assertEquals(written.intrinsics.k1, read.intrinsics.k1, 0.01)
        assertEquals(written.intrinsics.k2, read.intrinsics.k2, 0.01)
    }

    @Test
    fun theEstimateRecoversTheCameraItWasRenderedWith() {
        // Sanity on the fixture itself: if this is wrong, the tests below are
        // agreeing with a broken computation.
        val estimate = ArKernelIntrinsicsEstimator.estimate(
            views = views().map { ArKernelIntrinsicsView(it.corners) },
            gridColumns = COLUMNS,
            gridRows = ROWS,
            imageWidthPx = WIDTH,
            imageHeightPx = HEIGHT,
        )
        assertTrue(estimate.detail, estimate.accepted)
        val found = estimate.intrinsics!!
        assertEquals(FX, found.fxPx, FX * 0.02)
        assertEquals(FY, found.fyPx, FY * 0.02)
        assertEquals(CX, found.cxPx, 8.0)
        assertEquals(CY, found.cyPx, 8.0)
    }

    @Test
    fun theFileStatesWhatItCannotSupport() {
        // The two denials are in the file, not in a comment somewhere: the
        // grid is square but unmeasured, and no translation is claimed. A
        // reader must not be able to mistake this for a metric calibration.
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        assertTrue(text.contains("square_size_known=false"))
        assertTrue(text.contains("translation_recovered=false"))
        assertTrue(text.contains("images_disabled=true"))
        assertTrue(text.contains("automatic_upload_disabled=true"))
    }

    @Test
    fun anAcceptedFileIsAlwaysMarkedAccepted() {
        // A refused measurement produces a message on the device and no file,
        // so a file that says otherwise is not one this writer produced.
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        assertTrue(text.contains("accepted=true"))
        assertTrue(text.contains("rejection=OK"))
    }

    // -------------------------------------------------- the recompute refusal

    @Test
    fun aFileWhoseNumbersDoNotFollowFromItsOwnCornersIsRefused() {
        // The whole point of carrying the corners. A well-formed file with a
        // focal length from another device, or edited by hand, is refused
        // even though every field in it is individually valid - and the
        // reason given names what was checked.
        val written = bundle()
        val text = ArKernelIntrinsicsObservationAdapter.encode(written)
        val edited = text.replace(
            "fx_px=",
            "fx_px=0",
        ).let { it.replaceFirst(Regex("fx_px=0([0-9.]+)"), "fx_px=1234.0") }
        val parsed = ArKernelIntrinsicsObservationAdapter.parse(edited)
        assertFalse("must not accept an answer its corners do not give",
            parsed.accepted)
        assertEquals(
            parsed.detail,
            ArKernelIntrinsicsObservationRejection.ESTIMATE_MISMATCH,
            parsed.rejection,
        )
    }

    @Test
    fun aDistortionThatDoesNotFollowIsRefusedToo() {
        // Focal length is the obvious thing to check; the distortion is the
        // one that quietly changes every downstream measurement, so it is
        // checked just the same.
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        val edited = Regex("k1=[-0-9.]+").replace(text, "k1=0.9")
        val parsed = ArKernelIntrinsicsObservationAdapter.parse(edited)
        assertEquals(
            parsed.detail,
            ArKernelIntrinsicsObservationRejection.ESTIMATE_MISMATCH,
            parsed.rejection,
        )
    }

    @Test
    fun cornersThatFitNoCameraAtAllAreRefused() {
        // Corners scrambled into noise are still well-formed numbers in the
        // right places. The recomputation is what notices.
        // Keep every stated value from a good run and push the corners off
        // the grid: the shape a careless edit or a copy from another board
        // takes. Nothing but the recomputation can notice.
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        val scrambled = text.split("\n").joinToString("\n") { line ->
            if (!line.startsWith("view=")) return@joinToString line
            val header = line.substringBefore("|")
            val rest = line.substringAfter("|")
            val count = rest.substringBefore("|")
            val body = rest.substringAfter("|")
            val moved = body.split(";").mapIndexed { index, pair ->
                val parts = pair.split(",")
                val x = parts[0].toDouble() + if (index % 2 == 0) 40.0 else -40.0
                val y = parts[1].toDouble() + if (index % 3 == 0) 30.0 else -30.0
                // Six decimals, which is what the format allows.
                "${round6(x)},${round6(y)}"
            }
            "$header|$count|" + moved.joinToString(";")
        }
        val parsed = ArKernelIntrinsicsObservationAdapter.parse(scrambled)
        assertFalse("scrambled corners must not yield a camera", parsed.accepted)
        assertEquals(
            parsed.detail,
            ArKernelIntrinsicsObservationRejection.ESTIMATE_MISMATCH,
            parsed.rejection,
        )
    }

    // ------------------------------------------------------------- structure

    @Test
    fun theHeaderAndTheEndAreRequired() {
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        assertEquals(
            ArKernelIntrinsicsObservationRejection.HEADER,
            ArKernelIntrinsicsObservationAdapter.parse(
                text.replace("ARKINTR1", "ARKINTR2")).rejection,
        )
        assertEquals(
            ArKernelIntrinsicsObservationRejection.END,
            ArKernelIntrinsicsObservationAdapter.parse(
                text.replace("\nEND", "\n")).rejection,
        )
    }

    @Test
    fun theKeysAreCheckedInOrder() {
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        val swapped = text.replace(
            "session_id=session-1\ndevice_token=device-1",
            "device_token=device-1\nsession_id=session-1")
        assertEquals(
            ArKernelIntrinsicsObservationRejection.KEYS,
            ArKernelIntrinsicsObservationAdapter.parse(swapped).rejection,
        )
    }

    @Test
    fun fewerViewsThanTheFitNeedsIsRefused() {
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        // Drop views down to four and say so in the count: a file that is
        // internally consistent but under-determined.
        val kept = text.split("\n").filter { !it.startsWith("view=4|") &&
            !it.startsWith("view=5|") && !it.startsWith("view=6|") &&
            !it.startsWith("view=7|") }
            .joinToString("\n")
            .replace("view_count=8", "view_count=4")
        val parsed = ArKernelIntrinsicsObservationAdapter.parse(kept)
        assertEquals(
            parsed.detail,
            ArKernelIntrinsicsObservationRejection.VIEW_COUNT,
            parsed.rejection,
        )
    }

    @Test
    fun aViewWithTheWrongNumberOfCornersIsRefused() {
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        val shortBody = (0 until 12).joinToString(";") { "100.5,100.5" }
        val broken = Regex("view=0\\|[0-9]+\\|[^\\n]*")
            .replace(text, "view=0|12|$shortBody")
        assertEquals(
            ArKernelIntrinsicsObservationRejection.CORNER_COUNT,
            ArKernelIntrinsicsObservationAdapter.parse(broken).rejection,
        )
    }

    @Test
    fun aClaimedTranslationIsRefused() {
        // Not recoverable from an unmeasured grid. A file that claims one is
        // describing a different measurement, and the honest answer is to
        // refuse rather than to half-believe it.
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
            .replace("translation_recovered=false", "translation_recovered=true")
        assertEquals(
            ArKernelIntrinsicsObservationRejection.BOOLEAN,
            ArKernelIntrinsicsObservationAdapter.parse(text).rejection,
        )
    }

    @Test
    fun aRefusalAlwaysSaysWhy() {
        val parsed = ArKernelIntrinsicsObservationAdapter.parse("nothing here")
        assertFalse(parsed.accepted)
        assertTrue(parsed.detail.isNotBlank())
        assertEquals(null, parsed.bundle)
    }

    @Test
    fun theRecordIsRegisteredInTheCatalogue() {
        // An unregistered contract is dropped without complaint at the
        // manifest step, so the record would never be seen again.
        val contract = ArKernelArtifactContract.entries.firstOrNull {
            it.token == "ARKINTR1"
        }
        assertNotNull("ARKINTR1 must be registered", contract)
        assertEquals(28, contract!!.button)
    }

    @Test
    fun theCatalogueReadsThisRecordByItsFileName() {
        // The wiring, not just the parser: a file named for this contract has
        // to be recognised, read and accepted through the same path the app
        // uses. A parser that works but is never reached is no parser.
        val text = ArKernelIntrinsicsObservationAdapter.encode(bundle())
        val file = ArKernelArtifactFile(
            name = "ARKINTR1_session-1.txt",
            sizeBytes = text.length.toLong(),
            modifiedMs = 1_700_000_000_000L,
            sha256Hex = "a".repeat(64),
        )
        val reading = ArKernelArtifactCatalogue.read(file, text)
        assertEquals(
            ArKernelArtifactKind.CONTRACT,
            reading.kind,
        )
        assertEquals(ArKernelArtifactContract.CAMINTR, reading.contract)
        assertTrue(reading.detail, reading.accepted)
    }
}
