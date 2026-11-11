package ai.arena.arkernel

private const val FIELD_DIAGNOSTICS_HEADER = "ARKFDIAG1"
private const val FIELD_DIAGNOSTICS_VERSION = "1"
private const val FIELD_DIAGNOSTICS_QUALITY = "unverified"
private const val FIELD_DIAGNOSTICS_MAX_INPUT_CHARACTERS = 65_536
private const val FIELD_DIAGNOSTICS_MAX_LINE_CHARACTERS = 8_384
private const val FIELD_DIAGNOSTICS_MAX_ERROR_BYTES = 4_096
private const val FIELD_DIAGNOSTICS_MAX_REFERENCE_BYTES = 1_024
private const val FIELD_DIAGNOSTICS_HEX = "0123456789abcdef"

private val FIELD_DIAGNOSTICS_ID = Regex("[A-Za-z0-9_-]{1,64}")
private val FIELD_DIAGNOSTICS_SHA256 = Regex("[0-9a-f]{64}")
private val FIELD_DIAGNOSTICS_TARGET = Regex("[A-Za-z0-9._:-]{1,128}")
private val FIELD_DIAGNOSTICS_UNSIGNED = Regex("0|[1-9][0-9]*")

private val FIELD_DIAGNOSTICS_TELEMETRY_KEYS = List(
    ReconstructionTexturePipelineTelemetry.FIELD_COUNT) { index ->
    "texture_telemetry_${index.toString().padStart(2, '0')}"
}

private val FIELD_DIAGNOSTICS_KEYS = listOf(
    "version",
    "session_id",
    "asset_id",
    "generation",
    "stage",
    "complete",
    "quality_grade",
    "pose_manifest_generation",
    "frame_count",
    "fusion_update_count",
    "archived_bytes",
    "canonical_bytes",
    "decoded_compressed_sources",
    "visibility_depth_bytes",
    "loaded_visibility_depth_sources",
    "source_mesh_complete",
    "fusion_truncated",
    "meshing_truncated",
    "filtering_truncated",
    "simplification_truncated",
    "source_vertices",
    "source_triangles",
    "raw_mesh_bytes",
    "texture_stage",
    "texture_input_valid",
    "texture_truncated",
    "atlas_width",
    "atlas_height",
    "atlas_bytes",
    "asset_present",
    "asset_vertices",
    "asset_triangles",
    "asset_png_bytes",
    "asset_glb_bytes",
    "capability_signature_sha256",
    "calibration_signature_sha256",
    "held_out_target_ref",
    "error_hex",
    "source_mesh_error_hex",
    "texture_error_hex",
    "asset_reference_hex",
) + FIELD_DIAGNOSTICS_TELEMETRY_KEYS

enum class ArKernelFieldDiagnosticsRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_DATASET,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_QUALITY,
    INVALID_HEX,
}

/** Fail-closed result; on rejection no partially trusted report is returned. */
data class ArKernelFieldDiagnosticsParseResult(
    val record: ArKernelFieldDiagnosticsRecord?,
    val rejection: ArKernelFieldDiagnosticsRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelFieldDiagnosticsRejection.NONE && record != null
}

/**
 * Bounded, image-free field-diagnostics summary of one terminal session state.
 *
 * It deliberately omits frame references, camera poses, calibration arrays,
 * fusion trajectories, colour/depth pixels and dense geometry. Optional
 * capability/calibration signatures and an independent-target reference are
 * opaque host labels; this adapter does not verify them, run a dimensional
 * evaluator or assert an assessed quality grade.
 */
data class ArKernelFieldDiagnosticsRecord(
    val sessionId: String,
    val assetId: String,
    val generation: Long,
    val stage: ReconstructionTexturedSessionStage,
    val complete: Boolean,
    val qualityGrade: String,
    val poseManifestGeneration: Long?,
    val frameCount: Int,
    val fusionUpdateCount: Int,
    val archivedBytes: Long,
    val canonicalBytes: Long,
    val decodedCompressedSources: Int,
    val visibilityDepthBytes: Long,
    val loadedVisibilityDepthSources: Int,
    val sourceMeshComplete: Boolean,
    val fusionTruncated: Boolean,
    val meshingTruncated: Boolean,
    val filteringTruncated: Boolean,
    val simplificationTruncated: Boolean,
    val sourceVertices: Long,
    val sourceTriangles: Long,
    val rawMeshBytes: Long,
    val textureStage: ReconstructionTexturePipelineStage,
    val textureInputValid: Boolean,
    val textureTruncated: Boolean,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val atlasBytes: Int,
    val assetPresent: Boolean,
    val assetVertices: Int,
    val assetTriangles: Int,
    val assetPngBytes: Int,
    val assetGlbBytes: Int,
    val assetReference: String,
    val capabilitySignature: String?,
    val calibrationSignature: String?,
    val heldOutTargetReference: String?,
    val error: String,
    val sourceMeshError: String,
    val textureError: String,
    val textureTelemetry: List<Long>,
) {
    init {
        require(FIELD_DIAGNOSTICS_ID.matches(sessionId)) { "Invalid diagnostics session ID" }
        require(FIELD_DIAGNOSTICS_ID.matches(assetId)) { "Invalid diagnostics asset ID" }
        require(qualityGrade == FIELD_DIAGNOSTICS_QUALITY) {
            "Field diagnostics cannot assert an assessed quality grade"
        }
        require(generation >= 0L && frameCount >= 0 && fusionUpdateCount >= 0)
        val poseGeneration = poseManifestGeneration
        require(poseGeneration == null || poseGeneration > 0L)
        require(archivedBytes >= 0L && canonicalBytes >= 0L && visibilityDepthBytes >= 0L)
        require(decodedCompressedSources >= 0 && loadedVisibilityDepthSources >= 0)
        require(sourceVertices >= 0L && sourceTriangles >= 0L && rawMeshBytes >= 0L)
        require(atlasWidth >= 0 && atlasHeight >= 0 && atlasBytes >= 0)
        require(assetVertices >= 0 && assetTriangles >= 0 &&
            assetPngBytes >= 0 && assetGlbBytes >= 0)
        require(textureTelemetry.size == ReconstructionTexturePipelineTelemetry.FIELD_COUNT &&
            textureTelemetry.all { it >= 0L }) { "Invalid diagnostics texture telemetry" }
        require(complete == (stage == ReconstructionTexturedSessionStage.COMPLETE &&
            error.isEmpty() && assetPresent)) {
            "Diagnostics completion does not match its stage, error and asset"
        }
        require(!assetPresent || complete) { "Incomplete diagnostics cannot carry an asset" }
        require(assetPresent == assetReference.isNotEmpty()) {
            "Diagnostics asset presence does not match its reference"
        }
        capabilitySignature?.let {
            require(FIELD_DIAGNOSTICS_SHA256.matches(it)) { "Invalid capability signature" }
        }
        calibrationSignature?.let {
            require(FIELD_DIAGNOSTICS_SHA256.matches(it)) { "Invalid calibration signature" }
        }
        heldOutTargetReference?.let {
            require(FIELD_DIAGNOSTICS_TARGET.matches(it)) { "Invalid target reference" }
        }
        utf8Bytes(error, FIELD_DIAGNOSTICS_MAX_ERROR_BYTES, "diagnostics error")
        utf8Bytes(sourceMeshError, FIELD_DIAGNOSTICS_MAX_ERROR_BYTES,
            "diagnostics mesh error")
        utf8Bytes(textureError, FIELD_DIAGNOSTICS_MAX_ERROR_BYTES,
            "diagnostics texture error")
        utf8Bytes(assetReference, FIELD_DIAGNOSTICS_MAX_REFERENCE_BYTES,
            "diagnostics asset reference")
    }
}

/**
 * Canonical `ARKFDIAG1` text adapter for optional, host-owned field reports.
 *
 * The adapter performs no I/O, collection, upload, redaction or device
 * validation. It derives only bounded scalar summaries from an already
 * validated terminal state record. Images, trajectories, per-frame poses and
 * dense geometry are never included; accepted reports remain `unverified`.
 */
object ArKernelFieldDiagnosticsBundle {
    fun fromState(
        state: ReconstructionSessionStateRecord,
        capabilitySignature: String? = null,
        calibrationSignature: String? = null,
        heldOutTargetReference: String? = null,
    ): ArKernelFieldDiagnosticsRecord {
        require(state.qualityGrade == FIELD_DIAGNOSTICS_QUALITY) {
            "Field diagnostics accepts only an unverified terminal state"
        }
        val asset = state.asset
        return ArKernelFieldDiagnosticsRecord(
            sessionId = state.sessionId,
            assetId = state.assetId,
            generation = state.generation,
            stage = state.stage,
            complete = state.complete,
            qualityGrade = state.qualityGrade,
            poseManifestGeneration = state.poseManifestGeneration,
            frameCount = state.frames.size,
            fusionUpdateCount = state.fusionUpdates.size,
            archivedBytes = state.archivedBytes,
            canonicalBytes = state.canonicalBytes,
            decodedCompressedSources = state.decodedCompressedSources,
            visibilityDepthBytes = state.visibilityDepthBytes,
            loadedVisibilityDepthSources = state.loadedVisibilityDepthSources,
            sourceMeshComplete = state.sourceMesh.complete,
            fusionTruncated = state.sourceMesh.fusionTruncated,
            meshingTruncated = state.sourceMesh.meshingTruncated,
            filteringTruncated = state.sourceMesh.filteringTruncated,
            simplificationTruncated = state.sourceMesh.simplificationTruncated,
            sourceVertices = state.sourceMesh.sourceVertices,
            sourceTriangles = state.sourceMesh.sourceTriangles,
            rawMeshBytes = state.sourceMesh.rawMeshBytes,
            textureStage = state.textureStage,
            textureInputValid = state.textureInputValid,
            textureTruncated = state.textureTruncated,
            atlasWidth = state.atlasWidth,
            atlasHeight = state.atlasHeight,
            atlasBytes = state.atlasBytes,
            assetPresent = asset != null,
            assetVertices = asset?.vertices ?: 0,
            assetTriangles = asset?.triangles ?: 0,
            assetPngBytes = asset?.pngBytes ?: 0,
            assetGlbBytes = asset?.glbBytes ?: 0,
            assetReference = asset?.reference.orEmpty(),
            capabilitySignature = capabilitySignature,
            calibrationSignature = calibrationSignature,
            heldOutTargetReference = heldOutTargetReference,
            error = state.error,
            sourceMeshError = state.sourceMesh.error,
            textureError = state.textureError,
            textureTelemetry = state.textureTelemetry.values.toList(),
        )
    }

    /** Deterministic canonical encoding for an already validated report. */
    fun encode(record: ArKernelFieldDiagnosticsRecord): String {
        val values = HashMap<String, String>(FIELD_DIAGNOSTICS_KEYS.size)
        values["version"] = FIELD_DIAGNOSTICS_VERSION
        values["session_id"] = record.sessionId
        values["asset_id"] = record.assetId
        values["generation"] = record.generation.toString()
        values["stage"] = record.stage.name
        values["complete"] = boolText(record.complete)
        values["quality_grade"] = record.qualityGrade
        values["pose_manifest_generation"] =
            record.poseManifestGeneration?.toString() ?: "-"
        values["frame_count"] = record.frameCount.toString()
        values["fusion_update_count"] = record.fusionUpdateCount.toString()
        values["archived_bytes"] = record.archivedBytes.toString()
        values["canonical_bytes"] = record.canonicalBytes.toString()
        values["decoded_compressed_sources"] = record.decodedCompressedSources.toString()
        values["visibility_depth_bytes"] = record.visibilityDepthBytes.toString()
        values["loaded_visibility_depth_sources"] =
            record.loadedVisibilityDepthSources.toString()
        values["source_mesh_complete"] = boolText(record.sourceMeshComplete)
        values["fusion_truncated"] = boolText(record.fusionTruncated)
        values["meshing_truncated"] = boolText(record.meshingTruncated)
        values["filtering_truncated"] = boolText(record.filteringTruncated)
        values["simplification_truncated"] = boolText(record.simplificationTruncated)
        values["source_vertices"] = record.sourceVertices.toString()
        values["source_triangles"] = record.sourceTriangles.toString()
        values["raw_mesh_bytes"] = record.rawMeshBytes.toString()
        values["texture_stage"] = record.textureStage.name
        values["texture_input_valid"] = boolText(record.textureInputValid)
        values["texture_truncated"] = boolText(record.textureTruncated)
        values["atlas_width"] = record.atlasWidth.toString()
        values["atlas_height"] = record.atlasHeight.toString()
        values["atlas_bytes"] = record.atlasBytes.toString()
        values["asset_present"] = boolText(record.assetPresent)
        values["asset_vertices"] = record.assetVertices.toString()
        values["asset_triangles"] = record.assetTriangles.toString()
        values["asset_png_bytes"] = record.assetPngBytes.toString()
        values["asset_glb_bytes"] = record.assetGlbBytes.toString()
        values["capability_signature_sha256"] = record.capabilitySignature ?: "-"
        values["calibration_signature_sha256"] = record.calibrationSignature ?: "-"
        values["held_out_target_ref"] = record.heldOutTargetReference ?: "-"
        values["error_hex"] = hexText(record.error, FIELD_DIAGNOSTICS_MAX_ERROR_BYTES,
            "diagnostics error")
        values["source_mesh_error_hex"] = hexText(record.sourceMeshError,
            FIELD_DIAGNOSTICS_MAX_ERROR_BYTES, "diagnostics mesh error")
        values["texture_error_hex"] = hexText(record.textureError,
            FIELD_DIAGNOSTICS_MAX_ERROR_BYTES, "diagnostics texture error")
        values["asset_reference_hex"] = hexText(record.assetReference,
            FIELD_DIAGNOSTICS_MAX_REFERENCE_BYTES, "diagnostics asset reference")
        record.textureTelemetry.forEachIndexed { index, value ->
            values[FIELD_DIAGNOSTICS_TELEMETRY_KEYS[index]] = value.toString()
        }
        require(values.size == FIELD_DIAGNOSTICS_KEYS.size) {
            "Field diagnostics key set is incomplete"
        }
        val lines = ArrayList<String>(FIELD_DIAGNOSTICS_KEYS.size + 1)
        lines.add(FIELD_DIAGNOSTICS_HEADER)
        FIELD_DIAGNOSTICS_KEYS.forEach { key ->
            lines.add("$key=${values.getValue(key)}")
        }
        val text = lines.joinToString("\n")
        require(text.length <= FIELD_DIAGNOSTICS_MAX_INPUT_CHARACTERS) {
            "Field diagnostics report exceeds its bounded size"
        }
        return text
    }

    fun parse(text: String): ArKernelFieldDiagnosticsParseResult {
        if (text.length > FIELD_DIAGNOSTICS_MAX_INPUT_CHARACTERS) {
            return reject(ArKernelFieldDiagnosticsRejection.INPUT_TOO_LARGE, 0,
                "Field diagnostics input exceeds its bounded size")
        }
        val normalized = if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(ArKernelFieldDiagnosticsRejection.EMPTY_DATASET, 0,
                "Field diagnostics input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") -> normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(ArKernelFieldDiagnosticsRejection.EMPTY_DATASET, 0,
                "Field diagnostics input contains no report")
        }
        val lines = body.lineSequence().toList()
        val header = lines[0].trimEnd('\r')
        if (header != FIELD_DIAGNOSTICS_HEADER || header.any { it.isWhitespace() }) {
            return reject(ArKernelFieldDiagnosticsRejection.INVALID_HEADER, 1,
                "Field diagnostics header is invalid")
        }
        if (lines.size != FIELD_DIAGNOSTICS_KEYS.size + 1) {
            return reject(ArKernelFieldDiagnosticsRejection.INVALID_SCHEMA, 0,
                "Field diagnostics line count is wrong")
        }
        val keyLines = FIELD_DIAGNOSTICS_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        fun line(key: String): Int = keyLines.getValue(key)
        val values = HashMap<String, String>(FIELD_DIAGNOSTICS_KEYS.size)
        for (index in FIELD_DIAGNOSTICS_KEYS.indices) {
            val lineNumber = index + 2
            val rawLine = lines[index + 1]
            val lineText = rawLine.trimEnd('\r')
            if (lineText.length > FIELD_DIAGNOSTICS_MAX_LINE_CHARACTERS) {
                return reject(ArKernelFieldDiagnosticsRejection.LINE_TOO_LONG, lineNumber,
                    "Field diagnostics line exceeds its bounded length")
            }
            if (lineText.isBlank()) {
                return reject(ArKernelFieldDiagnosticsRejection.EMPTY_LINE, lineNumber,
                    "Field diagnostics contains a blank line")
            }
            if (lineText.any { it.isWhitespace() }) {
                return reject(ArKernelFieldDiagnosticsRejection.INVALID_SCHEMA, lineNumber,
                    "Field diagnostics lines must not contain whitespace")
            }
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(ArKernelFieldDiagnosticsRejection.INVALID_SCHEMA, lineNumber,
                    "Field diagnostics line is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != FIELD_DIAGNOSTICS_KEYS[index]) {
                return reject(ArKernelFieldDiagnosticsRejection.INVALID_SCHEMA, lineNumber,
                    "Field diagnostics key order or name is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != FIELD_DIAGNOSTICS_VERSION) {
            return reject(ArKernelFieldDiagnosticsRejection.INVALID_VALUE,
                line("version"), "Unsupported field diagnostics version")
        }
        if (values.getValue("quality_grade") != FIELD_DIAGNOSTICS_QUALITY) {
            return reject(ArKernelFieldDiagnosticsRejection.INVALID_QUALITY,
                line("quality_grade"), "Field diagnostics cannot assert assessed quality")
        }
        val error = decodeHexText(values.getValue("error_hex"),
            FIELD_DIAGNOSTICS_MAX_ERROR_BYTES)
            ?: return reject(ArKernelFieldDiagnosticsRejection.INVALID_HEX,
                line("error_hex"), "Field diagnostics error is not canonical hex UTF-8")
        val sourceMeshError = decodeHexText(values.getValue("source_mesh_error_hex"),
            FIELD_DIAGNOSTICS_MAX_ERROR_BYTES)
            ?: return reject(ArKernelFieldDiagnosticsRejection.INVALID_HEX,
                line("source_mesh_error_hex"), "Field diagnostics mesh error is invalid")
        val textureError = decodeHexText(values.getValue("texture_error_hex"),
            FIELD_DIAGNOSTICS_MAX_ERROR_BYTES)
            ?: return reject(ArKernelFieldDiagnosticsRejection.INVALID_HEX,
                line("texture_error_hex"), "Field diagnostics texture error is invalid")
        val assetReference = decodeHexText(values.getValue("asset_reference_hex"),
            FIELD_DIAGNOSTICS_MAX_REFERENCE_BYTES)
            ?: return reject(ArKernelFieldDiagnosticsRejection.INVALID_HEX,
                line("asset_reference_hex"), "Field diagnostics asset reference is invalid")

        val record = try {
            fun invalid(key: String): Nothing = throw IllegalArgumentException(key)
            fun long(key: String): Long = longValue(values.getValue(key)) ?: invalid(key)
            fun int(key: String): Int = intValue(values.getValue(key)) ?: invalid(key)
            fun bool(key: String): Boolean = boolValue(values.getValue(key)) ?: invalid(key)
            fun optionalLong(key: String): Long? {
                val raw = values.getValue(key)
                return if (raw == "-") null else (longValue(raw) ?: invalid(key))
            }
            fun optionalSignature(key: String): String? {
                val raw = values.getValue(key)
                if (raw == "-") return null
                if (!FIELD_DIAGNOSTICS_SHA256.matches(raw)) invalid(key)
                return raw
            }
            fun optionalTarget(key: String): String? {
                val raw = values.getValue(key)
                if (raw == "-") return null
                if (!FIELD_DIAGNOSTICS_TARGET.matches(raw)) invalid(key)
                return raw
            }
            val stage = ReconstructionTexturedSessionStage.entries.firstOrNull {
                it.name == values.getValue("stage")
            } ?: invalid("stage")
            val textureStage = ReconstructionTexturePipelineStage.entries.firstOrNull {
                it.name == values.getValue("texture_stage")
            } ?: invalid("texture_stage")
            val telemetry = ArrayList<Long>(FIELD_DIAGNOSTICS_TELEMETRY_KEYS.size)
            for (key in FIELD_DIAGNOSTICS_TELEMETRY_KEYS) {
                telemetry.add(long(key))
            }
            ArKernelFieldDiagnosticsRecord(
                sessionId = values.getValue("session_id"),
                assetId = values.getValue("asset_id"),
                generation = long("generation"),
                stage = stage,
                complete = bool("complete"),
                qualityGrade = values.getValue("quality_grade"),
                poseManifestGeneration = optionalLong("pose_manifest_generation"),
                frameCount = int("frame_count"),
                fusionUpdateCount = int("fusion_update_count"),
                archivedBytes = long("archived_bytes"),
                canonicalBytes = long("canonical_bytes"),
                decodedCompressedSources = int("decoded_compressed_sources"),
                visibilityDepthBytes = long("visibility_depth_bytes"),
                loadedVisibilityDepthSources = int("loaded_visibility_depth_sources"),
                sourceMeshComplete = bool("source_mesh_complete"),
                fusionTruncated = bool("fusion_truncated"),
                meshingTruncated = bool("meshing_truncated"),
                filteringTruncated = bool("filtering_truncated"),
                simplificationTruncated = bool("simplification_truncated"),
                sourceVertices = long("source_vertices"),
                sourceTriangles = long("source_triangles"),
                rawMeshBytes = long("raw_mesh_bytes"),
                textureStage = textureStage,
                textureInputValid = bool("texture_input_valid"),
                textureTruncated = bool("texture_truncated"),
                atlasWidth = int("atlas_width"),
                atlasHeight = int("atlas_height"),
                atlasBytes = int("atlas_bytes"),
                assetPresent = bool("asset_present"),
                assetVertices = int("asset_vertices"),
                assetTriangles = int("asset_triangles"),
                assetPngBytes = int("asset_png_bytes"),
                assetGlbBytes = int("asset_glb_bytes"),
                assetReference = assetReference,
                capabilitySignature = optionalSignature("capability_signature_sha256"),
                calibrationSignature = optionalSignature("calibration_signature_sha256"),
                heldOutTargetReference = optionalTarget("held_out_target_ref"),
                error = error,
                sourceMeshError = sourceMeshError,
                textureError = textureError,
                textureTelemetry = telemetry,
            )
        } catch (e: IllegalArgumentException) {
            val lineNumber = e.message?.let { keyLines[it] } ?: 0
            return reject(ArKernelFieldDiagnosticsRejection.INVALID_VALUE, lineNumber,
                "Field diagnostics contains an invalid value or invariant")
        }
        return ArKernelFieldDiagnosticsParseResult(
            record, ArKernelFieldDiagnosticsRejection.NONE, lines.size,
            "Field diagnostics parsed within its bounded image-free contract")
    }

    private fun reject(
        reason: ArKernelFieldDiagnosticsRejection,
        lineNumber: Int,
        detail: String,
    ) = ArKernelFieldDiagnosticsParseResult(null, reason, lineNumber, detail)
}

private fun boolText(value: Boolean): String = if (value) "true" else "false"

private fun boolValue(value: String): Boolean? = when (value) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun longValue(value: String): Long? =
    if (FIELD_DIAGNOSTICS_UNSIGNED.matches(value)) value.toLongOrNull() else null

private fun intValue(value: String): Int? =
    if (FIELD_DIAGNOSTICS_UNSIGNED.matches(value)) value.toIntOrNull() else null

private fun utf8Bytes(value: String, maxBytes: Int, name: String): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxBytes) { "$name exceeds its bounded UTF-8 size" }
    require(String(bytes, Charsets.UTF_8) == value) { "$name is not canonical UTF-8" }
    return bytes
}

private fun hexText(value: String, maxBytes: Int, name: String): String {
    val bytes = utf8Bytes(value, maxBytes, name)
    if (bytes.isEmpty()) return "-"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(FIELD_DIAGNOSTICS_HEX[(unsigned ushr 4)])
            append(FIELD_DIAGNOSTICS_HEX[(unsigned and 0x0f)])
        }
    }
}

private fun hexDigit(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    else -> -1
}

private fun decodeHexText(value: String, maxBytes: Int): String? {
    if (value == "-") return ""
    if (value.isEmpty() || value.length % 2 != 0 || value.length > maxBytes * 2) return null
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        val high = hexDigit(value[index * 2])
        val low = hexDigit(value[index * 2 + 1])
        if (high < 0 || low < 0) return null
        bytes[index] = ((high shl 4) or low).toByte()
    }
    val text = String(bytes, Charsets.UTF_8)
    if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return null
    return text
}
