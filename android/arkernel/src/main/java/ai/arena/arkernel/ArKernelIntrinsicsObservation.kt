package ai.arena.arkernel

/**
 * The record of a camera that was measured rather than believed.
 *
 * Every other camera record in this repository carries what the manufacturer
 * declared. `ARKCAMT1` carries a focal length; `ARKCAMEXT1` carries a focal
 * length implied by a rotation. Neither is a measurement of the lens, and the
 * difference is not academic: an uncorrected barrel distortion of a couple of
 * percent puts a point near the corner of the frame tens of pixels from where
 * a pinhole model says it is. Scale, orthogonality and distance claims made
 * from that frame inherit the error, and nothing downstream can tell the
 * difference between a lens and a wrong measurement.
 *
 * This record is different in one respect that matters more than the numbers
 * it contains. It does not only state the intrinsics: it carries the corner
 * positions they were computed from, and [ArKernelIntrinsicsObservationAdapter]
 * **recomputes the estimate from the stored corners** and refuses the file if
 * the stated answer does not follow from its own evidence. A record that
 * cannot be checked is a claim, and the whole point of measuring is to stop
 * making claims.
 *
 * Two limits are stated in the file itself rather than left to be assumed:
 *
 * - `square_size_known=false`. The grid must be *square*, not *measured*: a
 *   printed sheet is enough, because scale cancels out of the intrinsics.
 *   Nothing here therefore supports a statement in metres, and the field says
 *   so where nobody can miss it.
 * - No translation. The board's distance and position relative to the camera
 *   are not recovered, because with an unmeasured square size they are not
 *   recoverable. The poses the fit solves for are internal to the
 *   computation and are not written down.
 * - Image sizes and corner coordinates are in the **sensor's** frame, not in
 *   what was on the screen, and `sensor_rotation_deg` says which way up that
 *   frame is. A portrait calibration and a landscape one differ by a quarter
 *   turn that no arithmetic downstream could notice.
 */

private const val INTR_HEADER = "ARKINTR1"
private const val INTR_VERSION = "1"
private const val INTR_END = "END"
private const val INTR_MAX_INPUT_CHARACTERS = 1_048_576
private const val INTR_MAX_LINE_CHARACTERS = 8_192
private const val INTR_MAX_VIEWS = 12
private const val INTR_MIN_VIEWS = 6
private const val INTR_MIN_SIDE = 3
private const val INTR_MAX_SIDE = 32
private const val INTR_MAX_CORNERS = 1_024
private const val INTR_MIN_IMAGE = 16
private const val INTR_MAX_IMAGE = 65_536
private const val INTR_DETAIL_CHARACTERS = 160

/** Relative slack allowed between a stated value and the recomputed one. */
private const val INTR_RELATIVE_TOLERANCE = 1e-3

/** Absolute slack, in pixels, for quantities that can pass through zero. */
private const val INTR_ABSOLUTE_TOLERANCE_PX = 0.05

private val INTR_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val INTR_UNSIGNED = Regex("0|[1-9][0-9]*")
private val INTR_DECIMAL = Regex("-?(0|[1-9][0-9]{0,6})(\\.[0-9]{1,6})?")
private val INTR_DETAIL = Regex("[A-Za-z0-9 .,_%+-]{0,160}")
private val INTR_BOOLEAN = Regex("(true|false)")

private val INTR_KEYS = listOf(
    "version",
    "session_id",
    "device_token",
    "grid_columns",
    "grid_rows",
    "image_width_px",
    "image_height_px",
    "sensor_rotation_deg",
    "view_count",
    "square_size_known",
    "translation_recovered",
    "images_disabled",
    "automatic_upload_disabled",
    "accepted",
    "rejection",
    "fx_px",
    "fy_px",
    "cx_px",
    "cy_px",
    "k1",
    "k2",
    "rms_error_px",
    "max_error_px",
    "detail",
)

/** Why an `ARKINTR1` text was refused. */
enum class ArKernelIntrinsicsObservationRejection {
    OK,
    HEADER,
    VERSION,
    KEYS,
    TOKEN,
    NUMBER,
    BOOLEAN,
    BUDGET,
    GRID,
    IMAGE_SIZE,
    VIEW_COUNT,
    VIEW_ORDER,
    CORNER_COUNT,
    CORNER_RANGE,
    ESTIMATE_MISSING,
    ESTIMATE_IMPLAUSIBLE,

    /**
     * The stated intrinsics do not follow from the corners in this file.
     *
     * This is the one that matters. It is raised after recomputing the
     * estimate from the stored corners and comparing it to what the file
     * claims, so a record whose numbers were edited, copied from another
     * device, or produced by a different estimator is refused even though
     * every field in it is individually well formed.
     */
    ESTIMATE_MISMATCH,
    END,
    ;

    val accepted: Boolean get() = this == OK
}

/** One view of the board: its corners, in image pixels, row-major. */
data class ArKernelIntrinsicsObservationView(
    val index: Int,
    val corners: List<ArKernelIntrinsicsPoint>,
)

/** What `ARKINTR1` carries. */
data class ArKernelIntrinsicsObservationBundle(
    val sessionId: String,
    val deviceToken: String,
    val gridColumns: Int,
    val gridRows: Int,
    val imageWidth: Int,
    val imageHeight: Int,

    /**
     * How far the sensor image is turned relative to the device held upright.
     *
     * The corners are read in the sensor's own coordinates, not in what was
     * on the screen, so this is the only thing that says which way up the
     * numbers are. Without it a portrait calibration and a landscape one
     * differ by a quarter turn that no arithmetic downstream can detect: the
     * intrinsics are plausible either way, and the principal point is simply
     * in the wrong corner.
     */
    val sensorRotationDeg: Int,
    val views: List<ArKernelIntrinsicsObservationView>,
    val intrinsics: ArKernelCameraIntrinsics,
    val rmsPx: Double,
    val maxPx: Double,
    val detail: String,
) {
    val cornerCount: Int get() = gridColumns * gridRows

    init {
        require(sensorRotationDeg in setOf(0, 90, 180, 270)) {
            "Sensor rotation must be a quarter turn"
        }
    }

    /** Horizontal field of view, which is the one number a person can check. */
    fun horizontalFovDeg(): Double = intrinsics.horizontalFovDeg(imageWidth)
}

/** Outcome of reading an `ARKINTR1` text. */
data class ArKernelIntrinsicsObservationParse(
    val accepted: Boolean,
    val rejection: ArKernelIntrinsicsObservationRejection,
    val detail: String,
    val bundle: ArKernelIntrinsicsObservationBundle? = null,
)

/**
 * Canonical `ARKINTR1` text adapter.
 *
 * `parse()` enforces the exact key set in order, bounded budgets, a fail-closed
 * `END`, well-formed numbers, and - when the file claims to be accepted -
 * recomputes the intrinsics from the stored corners and compares them against
 * the stated ones. It performs no I/O, captures nothing, uploads nothing and
 * infers nothing beyond the arithmetic in [ArKernelCameraIntrinsics].
 */
object ArKernelIntrinsicsObservationAdapter {

    fun encode(bundle: ArKernelIntrinsicsObservationBundle): String {
        val lines = ArrayList<String>(INTR_KEYS.size + bundle.views.size + 2)
        lines.add(INTR_HEADER)
        lines.add("version=$INTR_VERSION")
        lines.add("session_id=${bundle.sessionId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("grid_columns=${bundle.gridColumns}")
        lines.add("grid_rows=${bundle.gridRows}")
        lines.add("image_width_px=${bundle.imageWidth}")
        lines.add("image_height_px=${bundle.imageHeight}")
        lines.add("sensor_rotation_deg=${bundle.sensorRotationDeg}")
        lines.add("view_count=${bundle.views.size}")
        // Stated, not assumed: the grid is square but unmeasured, and no
        // translation is recovered. Both are written so that a reader cannot
        // take this file for something it is not.
        lines.add("square_size_known=false")
        lines.add("translation_recovered=false")
        lines.add("images_disabled=true")
        lines.add("automatic_upload_disabled=true")
        // Always true, and written so that a reader does not have to infer
        // it: a refused measurement produces a message on the device and no
        // file, so there is no such thing as a refused ARKINTR1.
        lines.add("accepted=true")
        lines.add("rejection=OK")
        lines.add("fx_px=${decimal(bundle.intrinsics.fxPx)}")
        lines.add("fy_px=${decimal(bundle.intrinsics.fyPx)}")
        lines.add("cx_px=${decimal(bundle.intrinsics.cxPx)}")
        lines.add("cy_px=${decimal(bundle.intrinsics.cyPx)}")
        lines.add("k1=${decimal(bundle.intrinsics.k1)}")
        lines.add("k2=${decimal(bundle.intrinsics.k2)}")
        lines.add("rms_error_px=${decimal(bundle.rmsPx)}")
        lines.add("max_error_px=${decimal(bundle.maxPx)}")
        lines.add("detail=${sanitiseDetail(bundle.detail)}")
        for (view in bundle.views) {
            val body = view.corners.joinToString(";") { corner ->
                "${decimal(corner.xPx)},${decimal(corner.yPx)}"
            }
            lines.add("view=${view.index}|${view.corners.size}|$body")
        }
        lines.add(INTR_END)
        val text = lines.joinToString("\n")
        require(text.length <= INTR_MAX_INPUT_CHARACTERS) {
            "Intrinsics observation exceeds its bounded size"
        }
        require(lines.all { it.length <= INTR_MAX_LINE_CHARACTERS }) {
            "Intrinsics observation line exceeds its bounded length"
        }
        return text
    }

    fun parse(raw: String): ArKernelIntrinsicsObservationParse {
        // Tolerant of a byte-order mark and of Windows line endings: the file
        // crosses at least one chat application and both are real.
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").trim()
        if (text.length > INTR_MAX_INPUT_CHARACTERS) {
            return refused(ArKernelIntrinsicsObservationRejection.BUDGET,
                "Zu gross fuer die vorgegebene Grenze")
        }
        val lines = text.split("\n")
        if (lines.any { it.length > INTR_MAX_LINE_CHARACTERS }) {
            return refused(ArKernelIntrinsicsObservationRejection.BUDGET,
                "Eine Zeile ist laenger als erlaubt")
        }
        if (lines.isEmpty() || lines.first().trim() != INTR_HEADER) {
            return refused(ArKernelIntrinsicsObservationRejection.HEADER,
                "Die Kopfzeile $INTR_HEADER fehlt")
        }
        if (lines.last().trim() != INTR_END) {
            return refused(ArKernelIntrinsicsObservationRejection.END,
                "Das abschliessende $INTR_END fehlt")
        }

        val body = ArrayList<String>(lines.size)
        for (index in 1 until lines.size - 1) {
            val line = lines[index].trim()
            // One optional blank separator before END, and no more than that.
            if (line.isEmpty()) {
                if (index == lines.size - 2) continue
                return refused(ArKernelIntrinsicsObservationRejection.KEYS,
                    "Eine leere Zeile steht mitten in der Datei")
            }
            body.add(line)
        }

        val values = LinkedHashMap<String, String>()
        val views = ArrayList<ArKernelIntrinsicsObservationView>()
        var keyIndex = 0
        for (line in body) {
            val separator = line.indexOf('=')
            if (separator <= 0) {
                return refused(ArKernelIntrinsicsObservationRejection.KEYS,
                    "Zeile ohne Schluessel: ${preview(line)}")
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            if (key == "view") {
                views += parseView(value, views.size) ?: return refused(
                    ArKernelIntrinsicsObservationRejection.VIEW_ORDER,
                    "Ansicht ${views.size} ist nicht lesbar: ${preview(value)}")
                continue
            }
            if (keyIndex >= INTR_KEYS.size || INTR_KEYS[keyIndex] != key) {
                return refused(ArKernelIntrinsicsObservationRejection.KEYS,
                    "Erwartet ${INTR_KEYS.getOrNull(keyIndex)
                        ?: "keinen Schluessel mehr"}, gelesen: ${preview(key)}")
            }
            values[key] = value
            keyIndex++
        }
        if (keyIndex != INTR_KEYS.size) {
            return refused(ArKernelIntrinsicsObservationRejection.KEYS,
                "Es fehlen Schluessel")
        }

        if (values["version"] != INTR_VERSION) {
            return refused(ArKernelIntrinsicsObservationRejection.VERSION,
                "Nur Version $INTR_VERSION wird gelesen")
        }
        val sessionId = values["session_id"]!!
        val deviceToken = values["device_token"]!!
        if (!INTR_TOKEN.matches(sessionId) || !INTR_TOKEN.matches(deviceToken)) {
            return refused(ArKernelIntrinsicsObservationRejection.TOKEN,
                "Sitzungs- oder Geraetekenzeichen hat ein unerlaubtes Zeichen")
        }
        for (key in listOf(
            "images_disabled",
            "automatic_upload_disabled",
            "square_size_known",
            "translation_recovered",
            "accepted",
        )) {
            if (!INTR_BOOLEAN.matches(values[key]!!)) {
                return refused(ArKernelIntrinsicsObservationRejection.BOOLEAN,
                    "$key ist nicht wahr oder falsch")
            }
        }
        // The invariants are part of the contract, not decoration: a file that
        // says otherwise is describing a different measurement.
        if (values["images_disabled"] != "true" ||
            values["automatic_upload_disabled"] != "true"
        ) {
            return refused(ArKernelIntrinsicsObservationRejection.BOOLEAN,
                "Bilder und automatisches Hochladen muessen abgeschaltet sein")
        }
        if (values["translation_recovered"] != "false") {
            return refused(ArKernelIntrinsicsObservationRejection.BOOLEAN,
                "Dieser Datensatz stellt keine Verschiebung wieder")
        }

        val gridColumns = unsigned(values["grid_columns"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.GRID,
                "grid_columns ist keine Zahl")
        val gridRows = unsigned(values["grid_rows"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.GRID,
                "grid_rows ist keine Zahl")
        if (gridColumns < INTR_MIN_SIDE || gridColumns > INTR_MAX_SIDE ||
            gridRows < INTR_MIN_SIDE || gridRows > INTR_MAX_SIDE
        ) {
            return refused(ArKernelIntrinsicsObservationRejection.GRID,
                "Das Raster muss zwischen $INTR_MIN_SIDE und $INTR_MAX_SIDE " +
                    "liegen")
        }
        val imageWidth = unsigned(values["image_width_px"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.IMAGE_SIZE,
                "image_width_px ist keine Zahl")
        val imageHeight = unsigned(values["image_height_px"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.IMAGE_SIZE,
                "image_height_px ist keine Zahl")
        if (imageWidth < INTR_MIN_IMAGE || imageWidth > INTR_MAX_IMAGE ||
            imageHeight < INTR_MIN_IMAGE || imageHeight > INTR_MAX_IMAGE
        ) {
            return refused(ArKernelIntrinsicsObservationRejection.IMAGE_SIZE,
                "Die Bildgroesse liegt ausserhalb des zulaessigen Bereichs")
        }
        val rotation = unsigned(values["sensor_rotation_deg"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.IMAGE_SIZE,
                "sensor_rotation_deg ist keine Zahl")
        if (rotation !in setOf(0, 90, 180, 270)) {
            return refused(ArKernelIntrinsicsObservationRejection.IMAGE_SIZE,
                "sensor_rotation_deg muss 0, 90, 180 oder 270 sein")
        }
        val viewCount = unsigned(values["view_count"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.VIEW_COUNT,
                "view_count ist keine Zahl")
        if (viewCount != views.size) {
            return refused(ArKernelIntrinsicsObservationRejection.VIEW_COUNT,
                "view_count nennt $viewCount, die Datei enthaelt " +
                    "${views.size} Ansichten")
        }
        if (viewCount < INTR_MIN_VIEWS || viewCount > INTR_MAX_VIEWS) {
            return refused(ArKernelIntrinsicsObservationRejection.VIEW_COUNT,
                "Zwischen $INTR_MIN_VIEWS und $INTR_MAX_VIEWS Ansichten " +
                    "sind noetig, genannt sind $viewCount")
        }

        val expected = gridColumns * gridRows
        for (view in views) {
            if (view.corners.size != expected) {
                return refused(ArKernelIntrinsicsObservationRejection.CORNER_COUNT,
                    "Ansicht ${view.index} nennt ${view.corners.size} Ecken, " +
                        "das Raster verlangt $expected")
            }
            for (corner in view.corners) {
                if (corner.xPx < 0.0 || corner.xPx > imageWidth.toDouble() ||
                    corner.yPx < 0.0 || corner.yPx > imageHeight.toDouble()
                ) {
                    return refused(ArKernelIntrinsicsObservationRejection.CORNER_RANGE,
                        "Ansicht ${view.index} hat eine Ecke ausserhalb " +
                            "des Bildes")
                }
            }
        }

        val fx = decimal(values["fx_px"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.NUMBER,
                "fx_px ist keine Zahl")
        val fy = decimal(values["fy_px"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.NUMBER,
                "fy_px ist keine Zahl")
        val cx = decimal(values["cx_px"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.NUMBER,
                "cx_px ist keine Zahl")
        val cy = decimal(values["cy_px"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.NUMBER,
                "cy_px ist keine Zahl")
        val k1 = decimal(values["k1"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.NUMBER,
                "k1 ist keine Zahl")
        val k2 = decimal(values["k2"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.NUMBER,
                "k2 ist keine Zahl")
        val rms = decimal(values["rms_error_px"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.NUMBER,
                "rms_error_px ist keine Zahl")
        val maxError = decimal(values["max_error_px"])
            ?: return refused(ArKernelIntrinsicsObservationRejection.NUMBER,
                "max_error_px ist keine Zahl")
        if (values["detail"]!!.let { !INTR_DETAIL.matches(it) }) {
            return refused(ArKernelIntrinsicsObservationRejection.TOKEN,
                "Die Beschreibung hat ein unerlaubtes Zeichen")
        }
        val claimedAccepted = values["accepted"] == "true"
        val rejectionName = values["rejection"]!!
        val rejection = ArKernelIntrinsicsRejection.entries.firstOrNull { it.name == rejectionName }
            ?: return refused(ArKernelIntrinsicsObservationRejection.TOKEN,
                "Unbekannte Ablehnung: ${preview(rejectionName)}")
        if (claimedAccepted != rejection.accepted) {
            return refused(ArKernelIntrinsicsObservationRejection.ESTIMATE_MISSING,
                "accepted und rejection widersprechen sich")
        }

        if (fx <= 0.0 || fy <= 0.0 || rms < 0.0 || maxError < 0.0 ||
            cx < 0.0 || cx > imageWidth.toDouble() ||
            cy < 0.0 || cy > imageHeight.toDouble()
        ) {
            return refused(ArKernelIntrinsicsObservationRejection.ESTIMATE_IMPLAUSIBLE,
                "Die angegebenen Kamerawerte sind unplausibel")
        }

        val bundle = ArKernelIntrinsicsObservationBundle(
            sessionId = sessionId,
            deviceToken = deviceToken,
            gridColumns = gridColumns,
            gridRows = gridRows,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            sensorRotationDeg = rotation,
            views = views,
            intrinsics = ArKernelCameraIntrinsics(fx, fy, cx, cy, k1, k2),
            rmsPx = rms,
            maxPx = maxError,
            detail = values["detail"]!!,
        )

        // The check that makes this record worth keeping: recompute the
        // intrinsics from the corners the file carries and compare. A file
        // whose numbers do not follow from its own evidence is refused even
        // when every field in it is well formed.
        if (claimedAccepted) {
            val recomputed = ArKernelIntrinsicsEstimator.estimate(
                views = views.map { ArKernelIntrinsicsView(it.corners) },
                gridColumns = gridColumns,
                gridRows = gridRows,
                imageWidthPx = imageWidth,
                imageHeightPx = imageHeight,
            )
            if (!recomputed.accepted) {
                return refused(ArKernelIntrinsicsObservationRejection.ESTIMATE_MISMATCH,
                    "Aus den gespeicherten Ecken folgt keine Annahme: " +
                        "${recomputed.rejection.name}")
            }
            val stated = bundle.intrinsics
            // Nullable by construction; guarded by the accepted check above.
            val found = recomputed.intrinsics ?: return refused(
                ArKernelIntrinsicsObservationRejection.ESTIMATE_MISMATCH,
                "Die Nachrechnung lieferte keine Kamerawerte")
            if (!close(stated.fxPx, found.fxPx) || !close(stated.fyPx, found.fyPx) ||
                !close(stated.cxPx, found.cxPx) || !close(stated.cyPx, found.cyPx) ||
                !close(stated.k1, found.k1) || !close(stated.k2, found.k2)
            ) {
                return refused(ArKernelIntrinsicsObservationRejection.ESTIMATE_MISMATCH,
                    "Die angegebenen Werte folgen nicht aus den " +
                        "gespeicherten Ecken (fx " +
                        "${decimal(stated.fxPx)} gegen ${decimal(found.fxPx)})")
            }
        }

        return ArKernelIntrinsicsObservationParse(
            accepted = true,
            rejection = ArKernelIntrinsicsObservationRejection.OK,
            detail = "$viewCount Ansichten, $expected Ecken je Ansicht, " +
                "rms ${decimal(rms)} px",
            bundle = bundle,
        )
    }

    private fun close(stated: Double, found: Double): Boolean {
        val difference = kotlin.math.abs(stated - found)
        return difference <= INTR_ABSOLUTE_TOLERANCE_PX ||
            difference <= INTR_RELATIVE_TOLERANCE * kotlin.math.abs(found)
    }

    private fun parseView(value: String, expectedIndex: Int):
        ArKernelIntrinsicsObservationView? {
        val first = value.indexOf('|')
        if (first <= 0) return null
        val second = value.indexOf('|', first + 1)
        if (second <= first) return null
        val index = unsigned(value.substring(0, first)) ?: return null
        if (index != expectedIndex) return null
        val count = unsigned(value.substring(first + 1, second)) ?: return null
        if (count == 0 || count > INTR_MAX_CORNERS) return null
        val body = value.substring(second + 1)
        val parts = body.split(";")
        if (parts.size != count) return null
        val corners = ArrayList<ArKernelIntrinsicsPoint>(count)
        for (part in parts) {
            val comma = part.indexOf(',')
            if (comma <= 0) return null
            val x = decimal(part.substring(0, comma)) ?: return null
            val y = decimal(part.substring(comma + 1)) ?: return null
            corners += ArKernelIntrinsicsPoint(x, y)
        }
        return ArKernelIntrinsicsObservationView(index, corners)
    }

    private fun unsigned(value: String?): Int? {
        if (value == null || !INTR_UNSIGNED.matches(value)) return null
        return value.toIntOrNull()
    }

    private fun decimal(value: String?): Double? {
        if (value == null || !INTR_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        if (parsed.isNaN() || parsed.isInfinite()) return null
        return parsed
    }

    private fun decimal(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val rounded = kotlin.math.round(value * 1e6) / 1e6
        return if (rounded == kotlin.math.floor(rounded) &&
            kotlin.math.abs(rounded) < 1e15
        ) {
            rounded.toLong().toString()
        } else {
            val text = String.format(java.util.Locale.ROOT, "%.6f", rounded)
            text.trimEnd('0').trimEnd('.')
        }
    }

    private fun sanitiseDetail(detail: String): String {
        val kept = StringBuilder(INTR_DETAIL_CHARACTERS)
        for (character in detail) {
            if (character in 'A'..'Z' || character in 'a'..'z' ||
                character in '0'..'9' || character in " .,_%+-"
            ) {
                kept.append(character)
            } else {
                kept.append(' ')
            }
            if (kept.length >= INTR_DETAIL_CHARACTERS) break
        }
        val text = kept.toString().trim()
        return if (INTR_DETAIL.matches(text)) text else ""
    }

    private fun preview(value: String): String =
        if (value.length <= 24) value else value.substring(0, 24) + "..."

    private fun refused(
        rejection: ArKernelIntrinsicsObservationRejection,
        detail: String,
    ): ArKernelIntrinsicsObservationParse =
        ArKernelIntrinsicsObservationParse(false, rejection, detail)
}
