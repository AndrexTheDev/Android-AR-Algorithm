package ai.arena.arkernel

import java.security.MessageDigest
import java.util.Locale

private const val BIND_HEADER = "ARKBIND1"
private const val BIND_VERSION = "1"
private const val BIND_QUALITY = "unverified"
private const val BIND_END = "END"
private const val BIND_MAX_INPUT_CHARACTERS = 8_192
private const val BIND_MAX_LINE_CHARACTERS = 256
private const val BIND_MAX_SCENES = 16
private const val BIND_MAX_OBSERVATIONS = 16
private const val BIND_MAX_POSES = 32
private const val BIND_CANONICAL_QUESTIONS = 10

private val BIND_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val BIND_HEX64 = Regex("[0-9a-f]{64}")
private val BIND_UNSIGNED = Regex("0|[1-9][0-9]*")
private val BIND_KEYS = listOf(
    "version",
    "session_id",
    "device_token",
    "campaign_id",
    "profile_digest",
    "evidence_digest",
    "plan_digest",
    "device_class",
    "scene_count",
    "question_count",
    "observation_count",
    "pose_count",
    "manual_upload_only",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "portable_protocol",
    "quality_grade",
)
private val BIND_BOOLEAN_KEYS = listOf(
    "manual_upload_only",
    "images_disabled",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "signature_verification_disabled",
    "portable_protocol",
)
private val BIND_INVARIANT_KEYS = BIND_BOOLEAN_KEYS

/**
 * Bounded, fail-closed binding of one device-adaptive profile, one physical
 * calibration evidence file and one systematic campaign plan.
 *
 * Digests cover the canonical re-encoded source texts, so BOM/CRLF variants
 * of the same source bind identically. All six invariants are mandatory:
 * manual upload only, no images, no automatic calibration, no automatic
 * upload, no signature verification and one portable protocol. The grade is
 * permanently `unverified`.
 */
data class ArKernelSessionBindingBundle(
    val sessionId: String,
    val deviceToken: String,
    val campaignId: String,
    val profileDigest: String,
    val evidenceDigest: String,
    val planDigest: String,
    val deviceClass: ArKernelAdaptiveDeviceClass,
    val sceneCount: Int,
    val questionCount: Int,
    val observationCount: Int,
    val poseCount: Int,
    val manualUploadOnly: Boolean = true,
    val imagesDisabled: Boolean = true,
    val automaticCalibrationDisabled: Boolean = true,
    val automaticUploadDisabled: Boolean = true,
    val signatureVerificationDisabled: Boolean = true,
    val portableProtocol: Boolean = true,
    val qualityGrade: String = BIND_QUALITY,
) {
    init {
        require(BIND_TOKEN.matches(sessionId) &&
            BIND_TOKEN.matches(deviceToken) &&
            BIND_TOKEN.matches(campaignId)) {
            "Invalid binding session, device or campaign token"
        }
        require(BIND_HEX64.matches(profileDigest) &&
            BIND_HEX64.matches(evidenceDigest) &&
            BIND_HEX64.matches(planDigest)) {
            "Binding digest must be lowercase SHA-256 hex"
        }
        require(sceneCount in 1..BIND_MAX_SCENES) {
            "Invalid binding scene count"
        }
        require(questionCount == BIND_CANONICAL_QUESTIONS) {
            "Binding questions must be canonical"
        }
        require(observationCount in 1..BIND_MAX_OBSERVATIONS) {
            "Invalid binding observation count"
        }
        require(poseCount in 0..BIND_MAX_POSES) {
            "Invalid binding pose count"
        }
        require(manualUploadOnly && imagesDisabled &&
            automaticCalibrationDisabled && automaticUploadDisabled &&
            signatureVerificationDisabled && portableProtocol) {
            "Session binding must remain fail-closed"
        }
        require(qualityGrade == BIND_QUALITY) {
            "Session binding cannot assert assessed quality"
        }
    }
}

enum class ArKernelSessionBindingRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_BINDING,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_BOOLEAN,
    UNSAFE_INVARIANT,
    INVALID_QUALITY,
    INVALID_TOKEN,
    INVALID_DIGEST,
    INVALID_DEVICE_CLASS,
    INVALID_COUNT,
    INVALID_PROFILE,
    INVALID_EVIDENCE,
    INVALID_PLAN,
    DEVICE_TOKEN_MISMATCH,
    DEVICE_CLASS_MISMATCH,
    DIGEST_FAILED,
}

data class ArKernelSessionBindingParseResult(
    val bundle: ArKernelSessionBindingBundle?,
    val rejection: ArKernelSessionBindingRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelSessionBindingRejection.NONE &&
            bundle != null
}

/** Structural binding facts; none of these is a quality assessment. */
data class ArKernelSessionBindingReport(
    val allSourcesParsed: Boolean,
    val digestsMatch: Boolean,
    val deviceTokenMatch: Boolean,
    val campaignIdMatch: Boolean,
    val countsMatch: Boolean,
    val deviceClassMatch: Boolean,
    val bindingConsistent: Boolean,
    val evidenceReady: Boolean,
    val campaignEvidenceComplete: Boolean,
    val manualUploadOnly: Boolean,
    val qualityGrade: String = BIND_QUALITY,
)

data class ArKernelSessionBindingCreateResult(
    val bundle: ArKernelSessionBindingBundle?,
    val report: ArKernelSessionBindingReport?,
    val rejection: ArKernelSessionBindingRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelSessionBindingRejection.NONE &&
            bundle != null && report != null
}

private data class ArKernelBindingSources(
    val profile: ArKernelAdaptiveBundle,
    val evidence: ArKernelCalibrationEvidenceBundle,
    val plan: ArKernelCampaignBundle,
    val profileCanonical: String,
    val evidenceCanonical: String,
    val planCanonical: String,
)

/**
 * Canonical `ARKBIND1` text adapter plus a deterministic source binder.
 *
 * `create()` parses three host-supplied canonical texts, re-encodes them to
 * their canonical form, hashes those bytes with SHA-256 and requires one
 * identical device token and one identical device class across the sources.
 * `verify()` recomputes every digest and count and reports only structural
 * consistency plus the two existing structural readiness pre-checks.
 *
 * The adapter reads no sensor, measures no target, captures no images,
 * pixels, poses or trajectories, infers no intrinsics, extrinsics or metric
 * scale, performs no automatic/universal calibration, verifies no signature,
 * writes no file, uploads nothing, performs no I/O beyond hashing in memory
 * and grants no quality grade. `bindingConsistent` is not an answer, not
 * accuracy, not centimetre validation and not an upgrade. Real independent
 * targets, signature verification, manual redaction/upload and physical
 * validation remain host work. The grade stays `unverified`.
 */
object ArKernelSessionBindingAdapter {
    fun create(
        sessionId: String,
        profileText: String,
        evidenceText: String,
        planText: String,
    ): ArKernelSessionBindingCreateResult {
        if (!BIND_TOKEN.matches(sessionId)) {
            return createReject(
                ArKernelSessionBindingRejection.INVALID_TOKEN,
                "Invalid binding session token")
        }
        val profileResult =
            ArKernelDeviceAdaptiveProfileAdapter.parse(profileText)
        if (!profileResult.accepted || profileResult.bundle == null) {
            return createReject(
                ArKernelSessionBindingRejection.INVALID_PROFILE,
                profileResult.detail)
        }
        val evidenceResult =
            ArKernelCalibrationEvidenceAdapter.parse(evidenceText)
        if (!evidenceResult.accepted || evidenceResult.bundle == null) {
            return createReject(
                ArKernelSessionBindingRejection.INVALID_EVIDENCE,
                evidenceResult.detail)
        }
        val planResult = ArKernelCampaignPlanAdapter.parse(planText)
        if (!planResult.accepted || planResult.bundle == null) {
            return createReject(
                ArKernelSessionBindingRejection.INVALID_PLAN,
                planResult.detail)
        }
        val profile = profileResult.bundle!!
        val evidence = evidenceResult.bundle!!
        val plan = planResult.bundle!!
        if (profile.deviceToken != evidence.deviceToken ||
            profile.deviceToken != plan.deviceToken) {
            return createReject(
                ArKernelSessionBindingRejection.DEVICE_TOKEN_MISMATCH,
                "Sources do not belong to the same device token")
        }
        if (profile.deviceClass != plan.deviceClass) {
            return createReject(
                ArKernelSessionBindingRejection.DEVICE_CLASS_MISMATCH,
                "Profile and plan device classes differ")
        }
        val profileCanonical =
            ArKernelDeviceAdaptiveProfileAdapter.encode(profile)
        val evidenceCanonical =
            ArKernelCalibrationEvidenceAdapter.encode(evidence)
        val planCanonical = ArKernelCampaignPlanAdapter.encode(plan)
        val bundle = try {
            ArKernelSessionBindingBundle(
                sessionId = sessionId,
                deviceToken = profile.deviceToken,
                campaignId = plan.campaignId,
                profileDigest = sha256Hex(profileCanonical),
                evidenceDigest = sha256Hex(evidenceCanonical),
                planDigest = sha256Hex(planCanonical),
                deviceClass = profile.deviceClass,
                sceneCount = plan.scenes.size,
                questionCount = plan.questions.size,
                observationCount = evidence.observations.size,
                poseCount = evidence.poses.size,
            )
        } catch (error: IllegalArgumentException) {
            return createReject(
                ArKernelSessionBindingRejection.INVALID_VALUE,
                error.message ?: "Invalid session binding")
        }
        val sources = ArKernelBindingSources(
            profile, evidence, plan,
            profileCanonical, evidenceCanonical, planCanonical)
        val report = verify(bundle, sources)
        if (!report.bindingConsistent) {
            return createReject(
                ArKernelSessionBindingRejection.DIGEST_FAILED,
                "Recomputed source binding is inconsistent")
        }
        return ArKernelSessionBindingCreateResult(
            bundle, report,
            ArKernelSessionBindingRejection.NONE, "")
    }

    fun verify(
        bundle: ArKernelSessionBindingBundle,
        profileText: String,
        evidenceText: String,
        planText: String,
    ): ArKernelSessionBindingReport {
        val sources = readSources(
            profileText, evidenceText, planText)
            ?: return emptyReport(bundle)
        return verify(bundle, sources)
    }

    private fun verify(
        bundle: ArKernelSessionBindingBundle,
        sources: ArKernelBindingSources,
    ): ArKernelSessionBindingReport {
        val digestsMatch =
            bundle.profileDigest == sha256Hex(sources.profileCanonical) &&
            bundle.evidenceDigest ==
                sha256Hex(sources.evidenceCanonical) &&
            bundle.planDigest == sha256Hex(sources.planCanonical)
        val token = bundle.deviceToken
        val deviceTokenMatch =
            token == sources.profile.deviceToken &&
            token == sources.evidence.deviceToken &&
            token == sources.plan.deviceToken
        val campaignIdMatch =
            bundle.campaignId == sources.plan.campaignId
        val countsMatch =
            bundle.sceneCount == sources.plan.scenes.size &&
            bundle.questionCount == sources.plan.questions.size &&
            bundle.observationCount ==
                sources.evidence.observations.size &&
            bundle.poseCount == sources.evidence.poses.size
        val deviceClassMatch =
            bundle.deviceClass == sources.profile.deviceClass &&
            bundle.deviceClass == sources.plan.deviceClass
        val evidenceReady =
            ArKernelCalibrationEvidenceAdapter
                .summarize(sources.evidence).evaluationReady
        val campaignEvidenceComplete =
            ArKernelCampaignPlanAdapter
                .summarize(sources.plan).campaignEvidenceComplete
        val bindingConsistent = digestsMatch && deviceTokenMatch &&
            campaignIdMatch && countsMatch && deviceClassMatch
        return ArKernelSessionBindingReport(
            allSourcesParsed = true,
            digestsMatch = digestsMatch,
            deviceTokenMatch = deviceTokenMatch,
            campaignIdMatch = campaignIdMatch,
            countsMatch = countsMatch,
            deviceClassMatch = deviceClassMatch,
            bindingConsistent = bindingConsistent,
            evidenceReady = evidenceReady,
            campaignEvidenceComplete = campaignEvidenceComplete,
            manualUploadOnly = bundle.manualUploadOnly,
        )
    }

    private fun readSources(
        profileText: String,
        evidenceText: String,
        planText: String,
    ): ArKernelBindingSources? {
        val profileResult =
            ArKernelDeviceAdaptiveProfileAdapter.parse(profileText)
        val evidenceResult =
            ArKernelCalibrationEvidenceAdapter.parse(evidenceText)
        val planResult = ArKernelCampaignPlanAdapter.parse(planText)
        if (!profileResult.accepted || profileResult.bundle == null ||
            !evidenceResult.accepted ||
            evidenceResult.bundle == null ||
            !planResult.accepted || planResult.bundle == null) {
            return null
        }
        val profile = profileResult.bundle!!
        val evidence = evidenceResult.bundle!!
        val plan = planResult.bundle!!
        return ArKernelBindingSources(
            profile, evidence, plan,
            ArKernelDeviceAdaptiveProfileAdapter.encode(profile),
            ArKernelCalibrationEvidenceAdapter.encode(evidence),
            ArKernelCampaignPlanAdapter.encode(plan),
        )
    }

    private fun emptyReport(
        bundle: ArKernelSessionBindingBundle,
    ): ArKernelSessionBindingReport =
        ArKernelSessionBindingReport(
            allSourcesParsed = false,
            digestsMatch = false,
            deviceTokenMatch = false,
            campaignIdMatch = false,
            countsMatch = false,
            deviceClassMatch = false,
            bindingConsistent = false,
            evidenceReady = false,
            campaignEvidenceComplete = false,
            manualUploadOnly = bundle.manualUploadOnly,
        )

    fun sha256Hex(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
        return digest.joinToString("") { byte ->
            String.format(
                Locale.US, "%02x", byte.toInt() and 0xFF)
        }
    }

    fun encode(bundle: ArKernelSessionBindingBundle): String {
        val lines = ArrayList<String>(BIND_KEYS.size + 2)
        lines.add(BIND_HEADER)
        lines.add("version=$BIND_VERSION")
        lines.add("session_id=${bundle.sessionId}")
        lines.add("device_token=${bundle.deviceToken}")
        lines.add("campaign_id=${bundle.campaignId}")
        lines.add("profile_digest=${bundle.profileDigest}")
        lines.add("evidence_digest=${bundle.evidenceDigest}")
        lines.add("plan_digest=${bundle.planDigest}")
        lines.add("device_class=${bundle.deviceClass.wireName()}")
        lines.add("scene_count=${bundle.sceneCount}")
        lines.add("question_count=${bundle.questionCount}")
        lines.add("observation_count=${bundle.observationCount}")
        lines.add("pose_count=${bundle.poseCount}")
        lines.add("manual_upload_only=true")
        lines.add("images_disabled=true")
        lines.add("automatic_calibration_disabled=true")
        lines.add("automatic_upload_disabled=true")
        lines.add("signature_verification_disabled=true")
        lines.add("portable_protocol=true")
        lines.add("quality_grade=${bundle.qualityGrade}")
        lines.add(BIND_END)
        val text = lines.joinToString("\n")
        require(text.length <= BIND_MAX_INPUT_CHARACTERS) {
            "Session binding exceeds its bounded size"
        }
        require(lines.all { it.length <= BIND_MAX_LINE_CHARACTERS }) {
            "Session binding line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelSessionBindingParseResult {
        if (text.length > BIND_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelSessionBindingRejection.INPUT_TOO_LARGE, 0,
                "Session binding input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (normalized.isEmpty()) {
            return reject(
                ArKernelSessionBindingRejection.EMPTY_BINDING, 0,
                "Session binding input is empty")
        }
        val body = when {
            normalized.endsWith("\r\n") ->
                normalized.substring(0, normalized.length - 2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.substring(0, normalized.length - 1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelSessionBindingRejection.EMPTY_BINDING, 0,
                "Session binding contains no manifest")
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return reject(
                ArKernelSessionBindingRejection.EMPTY_LINE, 0,
                "Session binding has an extra final separator")
        }
        val lines = body.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val header = lines[0].trimEnd('\r')
        if (header != BIND_HEADER || header.isBlank() ||
            header.length > BIND_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelSessionBindingRejection.INVALID_HEADER, 1,
                "Session binding header is invalid")
        }
        if (lines.size != BIND_KEYS.size + 2) {
            return reject(
                ArKernelSessionBindingRejection.INVALID_SCHEMA, 0,
                "Session binding line count is invalid")
        }
        val lineByKey = BIND_KEYS.withIndex()
            .associate { it.value to it.index + 2 }
        val values = HashMap<String, String>(BIND_KEYS.size)
        for (index in BIND_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelSessionBindingRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Binding metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != BIND_KEYS[index]) {
                return reject(
                    ArKernelSessionBindingRejection.INVALID_SCHEMA,
                    lineNumber,
                    "Binding metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        if (values.getValue("version") != BIND_VERSION) {
            return reject(
                ArKernelSessionBindingRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Unsupported session binding version")
        }
        if (values.getValue("quality_grade") != BIND_QUALITY) {
            return reject(
                ArKernelSessionBindingRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Session binding cannot assert assessed quality")
        }
        for (key in BIND_BOOLEAN_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return reject(
                    ArKernelSessionBindingRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Binding flag must be a boolean")
            }
            if (key in BIND_INVARIANT_KEYS && value != "true") {
                return reject(
                    ArKernelSessionBindingRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Binding safety invariant must be true")
            }
        }
        for (key in listOf("session_id", "device_token",
            "campaign_id")) {
            if (!BIND_TOKEN.matches(values.getValue(key))) {
                return reject(
                    ArKernelSessionBindingRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid session, device or campaign token")
            }
        }
        for (key in listOf("profile_digest", "evidence_digest",
            "plan_digest")) {
            if (!BIND_HEX64.matches(values.getValue(key))) {
                return reject(
                    ArKernelSessionBindingRejection.INVALID_DIGEST,
                    lineByKey.getValue(key),
                    "Binding digest must be lowercase SHA-256 hex")
            }
        }
        val deviceClass = ArKernelAdaptiveDeviceClass.fromWire(
            values.getValue("device_class"))
            ?: return reject(
                ArKernelSessionBindingRejection.INVALID_DEVICE_CLASS,
                lineByKey.getValue("device_class"),
                "Invalid binding device class")
        val sceneCount = boundedCount(
            values.getValue("scene_count"), BIND_MAX_SCENES)
        val questionCount = boundedCount(
            values.getValue("question_count"), BIND_MAX_SCENES)
        val observationCount = boundedCount(
            values.getValue("observation_count"),
            BIND_MAX_OBSERVATIONS)
        val poseCount = boundedCount(
            values.getValue("pose_count"), BIND_MAX_POSES)
        if (sceneCount == null || sceneCount < 1 ||
            questionCount == null ||
            questionCount != BIND_CANONICAL_QUESTIONS ||
            observationCount == null || observationCount < 1 ||
            poseCount == null) {
            return reject(
                ArKernelSessionBindingRejection.INVALID_COUNT, 0,
                "Invalid session binding counts")
        }
        val endLineNumber = BIND_KEYS.size + 2
        val endText = lines[BIND_KEYS.size + 1].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != BIND_END) {
            return reject(
                ArKernelSessionBindingRejection.INVALID_SCHEMA,
                endLineNumber, "Session binding must end with END")
        }
        val bundle = try {
            ArKernelSessionBindingBundle(
                sessionId = values.getValue("session_id"),
                deviceToken = values.getValue("device_token"),
                campaignId = values.getValue("campaign_id"),
                profileDigest = values.getValue("profile_digest"),
                evidenceDigest = values.getValue("evidence_digest"),
                planDigest = values.getValue("plan_digest"),
                deviceClass = deviceClass,
                sceneCount = sceneCount,
                questionCount = questionCount,
                observationCount = observationCount,
                poseCount = poseCount,
            )
        } catch (error: IllegalArgumentException) {
            return reject(
                ArKernelSessionBindingRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid session binding")
        }
        return ArKernelSessionBindingParseResult(
            bundle, ArKernelSessionBindingRejection.NONE, 0, "")
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelSessionBindingParseResult? {
        if (lineText.length > BIND_MAX_LINE_CHARACTERS) {
            return reject(
                ArKernelSessionBindingRejection.LINE_TOO_LONG,
                lineNumber,
                "Session binding line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return reject(
                ArKernelSessionBindingRejection.EMPTY_LINE,
                lineNumber,
                "Session binding contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return reject(
                ArKernelSessionBindingRejection.INVALID_SCHEMA,
                lineNumber,
                "Binding lines must not contain whitespace")
        }
        return null
    }

    private fun boundedCount(value: String, maximum: Int): Int? {
        val parsed = unsignedInt(value) ?: return null
        return if (parsed <= maximum) parsed else null
    }

    private fun unsignedInt(value: String): Int? =
        if (BIND_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun createReject(
        rejection: ArKernelSessionBindingRejection,
        detail: String,
    ): ArKernelSessionBindingCreateResult =
        ArKernelSessionBindingCreateResult(
            null, null, rejection, detail)

    private fun reject(
        rejection: ArKernelSessionBindingRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelSessionBindingParseResult =
        ArKernelSessionBindingParseResult(
            null, rejection, lineNumber, detail)
}
