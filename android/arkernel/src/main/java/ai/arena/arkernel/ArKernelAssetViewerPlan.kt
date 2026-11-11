package ai.arena.arkernel

private const val VIEW_HEADER = "ARKVIEW1"
private const val VIEW_VERSION = "1"
private const val VIEW_QUALITY = "unverified"
private const val VIEW_END = "END"
private const val VIEW_MAX_INPUT_CHARACTERS = 8_192
private const val VIEW_MAX_LINE_CHARACTERS = 256
private const val VIEW_MIN_VIEWS = 1
private const val VIEW_MAX_VIEWS = 16
private const val VIEW_MIN_FRAME_DIMENSION = 1
private const val VIEW_MAX_FRAME_DIMENSION = 4_096
private const val VIEW_MAX_REVISION = 1_000_000
private const val VIEW_MIN_YAW_DEGREES = -180.0
private const val VIEW_MAX_YAW_DEGREES = 180.0
private const val VIEW_MIN_PITCH_DEGREES = -89.0
private const val VIEW_MAX_PITCH_DEGREES = 89.0
private const val VIEW_MIN_DISTANCE_METERS = 0.1
private const val VIEW_MAX_DISTANCE_METERS = 1_000.0
private const val VIEW_MIN_FIELD_OF_VIEW_DEGREES = 20.0
private const val VIEW_MAX_FIELD_OF_VIEW_DEGREES = 120.0

private val VIEW_TOKEN = Regex("[A-Za-z0-9._:-]{1,32}")
private val VIEW_ASSET_REF = Regex(
    "arkglb://[A-Za-z0-9_-]{1,64}/" +
        "[0-9]{19}-[A-Za-z0-9_-]{1,64}\\.glb")
private val VIEW_UNSIGNED = Regex("0|[1-9][0-9]*")
private val VIEW_DECIMAL = Regex(
    """-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
private val VIEW_KEYS = listOf(
    "version",
    "plan_id",
    "revision",
    "view_count",
    "asset_loading_disabled",
    "renderer_initialization_disabled",
    "automatic_upload_disabled",
    "external_format_import_disabled",
    "quality_grade",
)
private val VIEW_SAFETY_KEYS = listOf(
    "asset_loading_disabled",
    "renderer_initialization_disabled",
    "automatic_upload_disabled",
    "external_format_import_disabled",
)

enum class ArKernelAssetViewMode { ORBIT, FRONT, TOP }
enum class ArKernelAssetColorMode { BASE_COLOR, NORMALS, WIREFRAME }

enum class ArKernelAssetViewerPlanRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_PLAN,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_ASSET_REFERENCE,
    INVALID_VIEW_MODE,
    INVALID_COLOR_MODE,
    INVALID_NUMBER,
    INVALID_CAMERA_RANGE,
    INVALID_FRAME_SIZE,
    INVALID_BOOLEAN,
    INVALID_ORDER,
    DUPLICATE_VIEW_ID,
    INVALID_VIEW_COUNT,
    UNSAFE_INVARIANT,
}

private fun inRange(value: Double, min: Double, max: Double): Boolean =
    value >= min && value <= max

/** Fail-closed parse result; on rejection no partially trusted plan is returned. */
data class ArKernelAssetViewerPlanParseResult(
    val plan: ArKernelAssetViewerPlan?,
    val rejection: ArKernelAssetViewerPlanRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection ==
            ArKernelAssetViewerPlanRejection.NONE && plan != null
}

/**
 * One bounded host-supplied inspection view. The asset reference is an opaque
 * `arkglb://` token matching the existing archive naming pattern; it is never
 * resolved, loaded or decoded here. Camera values are finite decimals under
 * explicit orbit/frame bounds. No pixels, meshes, textures, render handles,
 * trajectories, poses or physical evidence are carried.
 */
data class ArKernelAssetViewerPlanEntry(
    val index: Int,
    val viewId: String,
    val assetRef: String,
    val viewMode: ArKernelAssetViewMode,
    val colorMode: ArKernelAssetColorMode,
    val yawDegrees: Double,
    val pitchDegrees: Double,
    val distanceMeters: Double,
    val fieldOfViewDegrees: Double,
    val frameWidth: Int,
    val frameHeight: Int,
    val showNormals: Boolean,
) {
    init {
        require(index >= 0) { "Invalid asset-viewer plan entry index" }
        require(VIEW_TOKEN.matches(viewId)) {
            "Invalid asset-viewer plan view ID"
        }
        require(VIEW_ASSET_REF.matches(assetRef)) {
            "Invalid asset-viewer plan asset reference"
        }
        require(listOf(yawDegrees, pitchDegrees, distanceMeters,
            fieldOfViewDegrees).all { it.isFinite() }) {
            "Invalid asset-viewer plan camera value"
        }
        require(inRange(yawDegrees, VIEW_MIN_YAW_DEGREES,
            VIEW_MAX_YAW_DEGREES)) {
            "Asset-viewer yaw is out of bounds"
        }
        require(inRange(pitchDegrees, VIEW_MIN_PITCH_DEGREES,
            VIEW_MAX_PITCH_DEGREES)) {
            "Asset-viewer pitch is out of bounds"
        }
        require(inRange(distanceMeters, VIEW_MIN_DISTANCE_METERS,
            VIEW_MAX_DISTANCE_METERS)) {
            "Asset-viewer distance is out of bounds"
        }
        require(inRange(fieldOfViewDegrees,
            VIEW_MIN_FIELD_OF_VIEW_DEGREES,
            VIEW_MAX_FIELD_OF_VIEW_DEGREES)) {
            "Asset-viewer field of view is out of bounds"
        }
        require(frameWidth in
            VIEW_MIN_FRAME_DIMENSION..VIEW_MAX_FRAME_DIMENSION) {
            "Invalid asset-viewer frame width"
        }
        require(frameHeight in
            VIEW_MIN_FRAME_DIMENSION..VIEW_MAX_FRAME_DIMENSION) {
            "Invalid asset-viewer frame height"
        }
    }
}

/**
 * Bounded canonical plan for a future optional host viewer. All four safety
 * invariants must stay disabled: no asset loading, renderer initialization,
 * automatic upload or external-format import. The grade stays `unverified`.
 */
data class ArKernelAssetViewerPlan(
    val planId: String,
    val revision: Int = 1,
    val assetLoadingDisabled: Boolean = true,
    val rendererInitializationDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val externalFormatImportDisabled: Boolean = true,
    val views: List<ArKernelAssetViewerPlanEntry>,
    val qualityGrade: String = VIEW_QUALITY,
) {
    init {
        require(VIEW_TOKEN.matches(planId)) {
            "Invalid asset-viewer plan ID"
        }
        require(revision in 1..VIEW_MAX_REVISION) {
            "Invalid asset-viewer plan revision"
        }
        require(assetLoadingDisabled &&
            rendererInitializationDisabled &&
            automaticUploadDisabled &&
            externalFormatImportDisabled) {
            "Asset-viewer plan must remain fail-closed"
        }
        require(qualityGrade == VIEW_QUALITY) {
            "Asset-viewer plan cannot assert assessed quality"
        }
        require(views.size in
            VIEW_MIN_VIEWS..VIEW_MAX_VIEWS) {
            "Invalid asset-viewer plan view count"
        }
        require(views.map { it.viewId }.toSet().size == views.size) {
            "Asset-viewer plan contains duplicate view IDs"
        }
        require(views.withIndex().all { it.value.index == it.index }) {
            "Asset-viewer plan views are not contiguous"
        }
    }
}

/** Bounded structural facts only; never a quality or accuracy grade. */
data class ArKernelAssetViewerPlanReport(
    val viewCount: Int,
    val distinctAssetRefs: Int,
    val orbitCount: Int,
    val normalsEnabledCount: Int,
    val maxDistanceMeters: Double,
    val maxFramePixels: Long,
    val qualityGrade: String = VIEW_QUALITY,
)

/**
 * Canonical `ARKVIEW1` text adapter for a bounded host viewer plan.
 *
 * It enforces exact metadata order, strict input/line/view budgets, unique
 * opaque view IDs, contiguous indices, exact view/color modes, opaque internal
 * `arkglb://` references, finite bounded camera decimals, bounded frame
 * dimensions, an exact `0`/`1` normals flag, four fail-closed safety
 * invariants, BOM/CRLF tolerance with one optional final separator and a
 * fail-closed `END`. The pure summarizer reports only structural counts and
 * maxima. The adapter performs no I/O, asset decoding, GLB/GLTF parsing,
 * Filament initialization, rendering, upload, USDZ or other external-format
 * import, quality inference, physical validation or quality upgrade; the
 * separate optional Android viewer module remains host work and stays
 * `unverified`.
 */
object ArKernelAssetViewerPlanBundle {
    fun encode(plan: ArKernelAssetViewerPlan): String {
        val lines = ArrayList<String>(
            VIEW_KEYS.size + plan.views.size + 2)
        lines.add(VIEW_HEADER)
        lines.add("version=$VIEW_VERSION")
        lines.add("plan_id=${plan.planId}")
        lines.add("revision=${plan.revision}")
        lines.add("view_count=${plan.views.size}")
        VIEW_SAFETY_KEYS.forEach { lines.add("$it=true") }
        lines.add("quality_grade=${plan.qualityGrade}")
        for (entry in plan.views) {
            val normals = if (entry.showNormals) "1" else "0"
            lines.add(
                "view=${entry.index}|${entry.viewId}|" +
                    "${entry.assetRef}|${viewModeText(entry.viewMode)}|" +
                    "${colorModeText(entry.colorMode)}|" +
                    "${entry.yawDegrees}|${entry.pitchDegrees}|" +
                    "${entry.distanceMeters}|" +
                    "${entry.fieldOfViewDegrees}|" +
                    "${entry.frameWidth}|${entry.frameHeight}|" +
                    normals)
        }
        lines.add(VIEW_END)
        val text = lines.joinToString("\n")
        require(text.length <= VIEW_MAX_INPUT_CHARACTERS) {
            "Asset-viewer plan exceeds its bounded size"
        }
        require(lines.all { it.length <= VIEW_MAX_LINE_CHARACTERS }) {
            "Asset-viewer plan line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelAssetViewerPlanParseResult {
        if (text.length > VIEW_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelAssetViewerPlanRejection.INPUT_TOO_LARGE, 0,
                "Asset-viewer plan input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelAssetViewerPlanRejection.EMPTY_PLAN, 0,
                "Asset-viewer plan input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelAssetViewerPlanRejection.EMPTY_PLAN, 0,
                "Asset-viewer plan input contains no plan")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelAssetViewerPlanRejection.EMPTY_LINE, 0,
                "Asset-viewer plan contains an extra final separator")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != VIEW_HEADER ||
            header.any { it.isWhitespace() } ||
            header.length > VIEW_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_HEADER, 1,
                "Asset-viewer plan header is invalid")
        }
        if (lines.size < VIEW_KEYS.size + 3) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_SCHEMA, 0,
                "Asset-viewer plan is too short")
        }
        val lineByKey = VIEW_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(VIEW_KEYS.size)
        for (index in VIEW_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Asset-viewer metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != VIEW_KEYS[index]) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Asset-viewer metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != VIEW_VERSION) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported asset-viewer plan version")
        }
        if (values.getValue("quality_grade") != VIEW_QUALITY) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Asset-viewer plan cannot assert assessed quality")
        }
        for (key in VIEW_SAFETY_KEYS) {
            if (values.getValue(key) != "true") {
                return reject(
                    ArKernelAssetViewerPlanRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Asset-viewer safety invariant must be true")
            }
        }
        val planId = values.getValue("plan_id")
        if (!VIEW_TOKEN.matches(planId)) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_TOKEN,
                lineByKey.getValue("plan_id"),
                "Invalid asset-viewer plan ID")
        }
        val revision = unsignedInt(values.getValue("revision"))
            ?: return reject(
                ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Invalid asset-viewer plan revision")
        if (revision !in 1..VIEW_MAX_REVISION) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                lineByKey.getValue("revision"),
                "Asset-viewer plan revision is out of bounds")
        }
        val viewCount = unsignedInt(values.getValue("view_count"))
            ?: return reject(
                ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                lineByKey.getValue("view_count"),
                "Invalid asset-viewer plan view count")
        if (viewCount !in VIEW_MIN_VIEWS..VIEW_MAX_VIEWS) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_VIEW_COUNT,
                lineByKey.getValue("view_count"),
                "Asset-viewer plan view count is out of bounds")
        }
        val entryStartIndex = VIEW_KEYS.size + 1
        val endIndex = entryStartIndex + viewCount
        if (lines.size != endIndex + 1) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_SCHEMA, 0,
                "Asset-viewer line count does not match view count")
        }
        val views =
            ArrayList<ArKernelAssetViewerPlanEntry>(viewCount)
        val seen = HashSet<String>(viewCount)
        for (index in 0 until viewCount) {
            val lineNumber = entryStartIndex + index + 1
            val lineText = lines[entryStartIndex + index].trimEnd('\r')
            val rejection = validateLine(lineText, lineNumber)
            if (rejection != null) return rejection
            if (!lineText.startsWith("view=")) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Asset-viewer line is not a view")
            }
            val fields = lineText.substringAfter("view=").split('|')
            if (fields.size != 12) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Asset-viewer view must have twelve fields")
            }
            val viewIndex = unsignedInt(fields[0])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid asset-viewer view index")
            if (viewIndex != index) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_ORDER,
                    lineNumber,
                    "Asset-viewer views are not contiguous")
            }
            val viewId = fields[1]
            if (!VIEW_TOKEN.matches(viewId)) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_TOKEN,
                    lineNumber,
                    "Invalid asset-viewer view ID")
            }
            if (!seen.add(viewId)) {
                return reject(
                    ArKernelAssetViewerPlanRejection.DUPLICATE_VIEW_ID,
                    lineNumber,
                    "Asset-viewer view ID is reused")
            }
            val assetRef = fields[2]
            if (!VIEW_ASSET_REF.matches(assetRef)) {
                return reject(
                    ArKernelAssetViewerPlanRejection
                        .INVALID_ASSET_REFERENCE,
                    lineNumber,
                    "Invalid asset-viewer asset reference")
            }
            val viewMode = parseViewMode(fields[3])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_VIEW_MODE,
                    lineNumber,
                    "Invalid asset-viewer view mode")
            val colorMode = parseColorMode(fields[4])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_COLOR_MODE,
                    lineNumber,
                    "Invalid asset-viewer color mode")
            val yaw = decimal(fields[5])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_NUMBER,
                    lineNumber,
                    "Invalid asset-viewer yaw")
            val pitch = decimal(fields[6])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_NUMBER,
                    lineNumber,
                    "Invalid asset-viewer pitch")
            val distance = decimal(fields[7])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_NUMBER,
                    lineNumber,
                    "Invalid asset-viewer distance")
            val fov = decimal(fields[8])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_NUMBER,
                    lineNumber,
                    "Invalid asset-viewer field of view")
            if (!inRange(yaw, VIEW_MIN_YAW_DEGREES,
                VIEW_MAX_YAW_DEGREES) ||
                !inRange(pitch, VIEW_MIN_PITCH_DEGREES,
                    VIEW_MAX_PITCH_DEGREES) ||
                !inRange(distance, VIEW_MIN_DISTANCE_METERS,
                    VIEW_MAX_DISTANCE_METERS) ||
                !inRange(fov, VIEW_MIN_FIELD_OF_VIEW_DEGREES,
                    VIEW_MAX_FIELD_OF_VIEW_DEGREES)) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_CAMERA_RANGE,
                    lineNumber,
                    "Asset-viewer camera value is out of bounds")
            }
            val frameWidth = unsignedInt(fields[9])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid asset-viewer frame width")
            val frameHeight = unsignedInt(fields[10])
                ?: return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                    lineNumber,
                    "Invalid asset-viewer frame height")
            if (frameWidth !in
                VIEW_MIN_FRAME_DIMENSION..VIEW_MAX_FRAME_DIMENSION ||
                frameHeight !in
                    VIEW_MIN_FRAME_DIMENSION..VIEW_MAX_FRAME_DIMENSION) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_FRAME_SIZE,
                    lineNumber,
                    "Asset-viewer frame dimension is out of bounds")
            }
            val showNormals = when (fields[11]) {
                "0" -> false
                "1" -> true
                else -> return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_BOOLEAN,
                    lineNumber,
                    "Invalid asset-viewer normals flag")
            }
            views += try {
                ArKernelAssetViewerPlanEntry(
                    viewIndex,
                    viewId,
                    assetRef,
                    viewMode,
                    colorMode,
                    yaw,
                    pitch,
                    distance,
                    fov,
                    frameWidth,
                    frameHeight,
                    showNormals,
                )
            } catch (error: IllegalArgumentException) {
                return reject(
                    ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                    lineNumber,
                    error.message ?: "Invalid asset-viewer view")
            }
        }
        val endText = lines[endIndex].trimEnd('\r')
        val endLineNumber = endIndex + 1
        val endRejection = validateLine(endText, endLineNumber)
        if (endRejection != null) return endRejection
        if (endText != VIEW_END) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
                endLineNumber,
                "Asset-viewer plan must end with END")
        }
        val plan = try {
            ArKernelAssetViewerPlan(
                planId,
                revision,
                true,
                true,
                true,
                true,
                views,
                VIEW_QUALITY,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_VALUE,
                0,
                error.message ?: "Invalid asset-viewer plan")
        }
        return ArKernelAssetViewerPlanParseResult(
            plan,
            ArKernelAssetViewerPlanRejection.NONE,
            0,
            "",
        )
    }

    fun summarize(plan: ArKernelAssetViewerPlan):
        ArKernelAssetViewerPlanReport {
        val maxDistance = plan.views.maxOf { it.distanceMeters }
        val maxFramePixels = plan.views.maxOf {
            it.frameWidth.toLong() * it.frameHeight.toLong()
        }
        return ArKernelAssetViewerPlanReport(
            viewCount = plan.views.size,
            distinctAssetRefs =
                plan.views.map { it.assetRef }.toSet().size,
            orbitCount = plan.views.count {
                it.viewMode == ArKernelAssetViewMode.ORBIT
            },
            normalsEnabledCount = plan.views.count { it.showNormals },
            maxDistanceMeters = maxDistance,
            maxFramePixels = maxFramePixels,
        )
    }

    private fun viewModeText(mode: ArKernelAssetViewMode): String =
        when (mode) {
            ArKernelAssetViewMode.ORBIT -> "orbit"
            ArKernelAssetViewMode.FRONT -> "front"
            ArKernelAssetViewMode.TOP -> "top"
        }

    private fun parseViewMode(value: String): ArKernelAssetViewMode? =
        when (value) {
            "orbit" -> ArKernelAssetViewMode.ORBIT
            "front" -> ArKernelAssetViewMode.FRONT
            "top" -> ArKernelAssetViewMode.TOP
            else -> null
        }

    private fun colorModeText(mode: ArKernelAssetColorMode): String =
        when (mode) {
            ArKernelAssetColorMode.BASE_COLOR -> "base_color"
            ArKernelAssetColorMode.NORMALS -> "normals"
            ArKernelAssetColorMode.WIREFRAME -> "wireframe"
        }

    private fun parseColorMode(value: String): ArKernelAssetColorMode? =
        when (value) {
            "base_color" -> ArKernelAssetColorMode.BASE_COLOR
            "normals" -> ArKernelAssetColorMode.NORMALS
            "wireframe" -> ArKernelAssetColorMode.WIREFRAME
            else -> null
        }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelAssetViewerPlanParseResult? {
        if (lineText.length > VIEW_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelAssetViewerPlanRejection.LINE_TOO_LONG,
                lineNumber,
                "Asset-viewer plan line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelAssetViewerPlanRejection.EMPTY_LINE,
                lineNumber,
                "Asset-viewer plan contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelAssetViewerPlanRejection.INVALID_SCHEMA,
                lineNumber,
                "Asset-viewer plan lines must not contain whitespace")
        }
        return null
    }

    private fun unsignedInt(value: String): Int? =
        if (VIEW_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun decimal(value: String): Double? {
        if (!VIEW_DECIMAL.matches(value)) return null
        val parsed = value.toDoubleOrNull() ?: return null
        return if (parsed.isFinite()) parsed else null
    }

    private fun reject(
        rejection: ArKernelAssetViewerPlanRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelAssetViewerPlanParseResult =
        ArKernelAssetViewerPlanParseResult(
            null, rejection, lineNumber, detail)
}
