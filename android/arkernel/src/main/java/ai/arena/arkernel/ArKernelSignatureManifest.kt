package ai.arena.arkernel

import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

private const val SIGN_HEADER = "ARKSIGN1"
private const val SIGN_VERSION = "1"
private const val SIGN_QUALITY = "unverified"
private const val SIGN_END = "END"
private const val SIGN_ALGORITHM = "sha256withecdsa"
private const val SIGN_JCA_ALGORITHM = "SHA256withECDSA"
private const val SIGN_DIGEST_KEY = "manifest_digest"
private const val SIGN_SIGNATURE_KEY = "signature"
private const val SIGN_MAX_INPUT_CHARACTERS = 16_384
private const val SIGN_MAX_LINE_CHARACTERS = 256
private const val SIGN_MAX_ENTRIES = 32
private const val SIGN_ROW_FIELDS = 3

private val SIGN_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val SIGN_HEX64 = Regex("[0-9a-f]{64}")
private val SIGN_UNSIGNED = Regex("0|[1-9][0-9]*")
private val SIGN_BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")
private val SIGN_TRUE_KEYS = listOf(
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "universal_calibration_disabled",
    "images_disabled",
    "manual_upload_only",
    "external_verification_required",
)
private val SIGN_FALSE_KEYS = listOf("metric_accuracy_claimed")
private val SIGN_KEYS = listOf(
    "version",
    "manifest_id",
    "device_token",
    "key_origin",
    "signature_algorithm",
    "public_key_fingerprint",
    "entry_count",
    "manifest_digest",
    "signature",
    "automatic_calibration_disabled",
    "automatic_upload_disabled",
    "universal_calibration_disabled",
    "images_disabled",
    "manual_upload_only",
    "metric_accuracy_claimed",
    "external_verification_required",
    "quality_grade",
)

enum class ArKernelSignatureKeyOrigin {
    ANDROID_KEYSTORE,
    SOFTWARE;

    fun wireName(): String = when (this) {
        ArKernelSignatureKeyOrigin.ANDROID_KEYSTORE -> "android_keystore"
        ArKernelSignatureKeyOrigin.SOFTWARE -> "software"
    }

    companion object {
        fun fromWire(value: String): ArKernelSignatureKeyOrigin? =
            ArKernelSignatureKeyOrigin.entries.firstOrNull {
                it.wireName() == value
            }
    }
}

enum class ArKernelSignatureRejection {
    NONE,
    INPUT_TOO_LARGE,
    EMPTY_MANIFEST,
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
    INVALID_COUNT,
    INVALID_ROW,
    INVALID_KEY_ORIGIN,
    INVALID_ALGORITHM,
    INVALID_SIGNATURE,
    DUPLICATE_ENTRY,
    DUPLICATE_DIGEST,
    INVALID_ORDER,
    EMPTY_SOURCE,
    MANIFEST_DIGEST_MISMATCH,
    SIGNING_FAILED,
}

/** One artefact bound into the manifest by name and SHA-256 digest. */
data class ArKernelSignatureEntry(
    val index: Int,
    val artifactName: String,
    val digest: String,
) {
    init {
        require(index >= 0) { "Invalid signature entry index" }
        require(SIGN_TOKEN.matches(artifactName)) {
            "Invalid signature artefact name"
        }
        require(SIGN_HEX64.matches(digest)) {
            "Invalid signature digest"
        }
    }
}

/**
 * The unsigned part of an `ARKSIGN1` manifest.
 *
 * This is the text that gets signed: the canonical line block with
 * `manifest_digest=` and `signature=` left empty, followed by the entry rows
 * and `END`. Because both fields are empty inside the digest pre-image, the
 * digest is not circular.
 */
data class ArKernelSignatureManifest(
    val manifestId: String,
    val deviceToken: String,
    val keyOrigin: ArKernelSignatureKeyOrigin,
    val entries: List<ArKernelSignatureEntry>,
) {
    init {
        require(SIGN_TOKEN.matches(manifestId) &&
            SIGN_TOKEN.matches(deviceToken)) {
            "Invalid signature token"
        }
        require(entries.size in 1..SIGN_MAX_ENTRIES) {
            "Invalid signature entry count"
        }
        require(entries.withIndex().all { it.value.index == it.index }) {
            "Signature entries are not contiguous"
        }
        require(entries.map { it.artifactName }.toSet().size ==
            entries.size) {
            "Duplicate signature artefact name"
        }
        require(entries.map { it.digest }.toSet().size == entries.size) {
            "Duplicate signature digest"
        }
    }
}

/** A manifest plus the signature and the fingerprint of the signing key. */
data class ArKernelSignedManifest(
    val manifest: ArKernelSignatureManifest,
    val manifestDigest: String,
    val signature: String,
    val publicKeyFingerprint: String,
) {
    init {
        require(SIGN_HEX64.matches(manifestDigest)) {
            "Invalid manifest digest"
        }
        require(SIGN_HEX64.matches(publicKeyFingerprint)) {
            "Invalid public key fingerprint"
        }
    }
}

/** One artefact to bind: its name and the text whose digest is taken. */
data class ArKernelSignatureEntryInput(
    val artifactName: String,
    val sourceText: String,
)

/** Result of a real cryptographic check against one public key. */
data class ArKernelSignatureVerifyResult(
    val keyFingerprintMatches: Boolean,
    val signatureValid: Boolean,
    val publicKeyFingerprint: String,
    val detail: String,
) {
    val verified: Boolean
        get() = keyFingerprintMatches && signatureValid
}

data class ArKernelSignatureCreateResult(
    val signed: ArKernelSignedManifest?,
    val rejection: ArKernelSignatureRejection,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelSignatureRejection.NONE && signed != null
}

data class ArKernelSignatureParseResult(
    val signed: ArKernelSignedManifest?,
    val rejection: ArKernelSignatureRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelSignatureRejection.NONE && signed != null
}

/**
 * Canonical `ARKSIGN1` signature manifest.
 *
 * An ECDSA P-256 signature (SHA-256 with ECDSA) over the canonical manifest
 * text binds a list of artefact SHA-256 digests to one signing key. The digest
 * pre-image is the manifest with `manifest_digest=` and `signature=` left
 * empty, so the digest is not circular; the signed text is the same block with
 * the digest filled in and the signature still empty.
 *
 * What this proves is **integrity and key continuity**: the entries cannot be
 * changed and the digest list cannot be swapped without breaking the
 * signature, and the same fingerprint keeps showing up as long as the same
 * key signs. It does **not** prove who owns the key. There is no certificate
 * authority, no trust chain and no device attestation, so a rooted device or
 * a copied private key can produce a perfectly valid signature. The public
 * key lives outside the manifest and has to be pinned by whoever verifies.
 *
 * The adapter performs no key generation, no file I/O, no upload and no
 * quality assessment. The grade stays `unverified`.
 */
object ArKernelSignatureManifestAdapter {

    /** Lowercase SHA-256 of the UTF-8 bytes of [text]. */
    fun digestOf(text: String): String = signSha256Hex(text.toByteArray())

    /** Lowercase SHA-256 of the DER encoding of [publicKey]. */
    fun fingerprintOf(publicKey: PublicKey): String {
        val encoded = publicKey.encoded ?: ByteArray(0)
        return signSha256Hex(encoded)
    }

    /**
     * Signs [entries] with [signer] and returns the signed manifest.
     *
     * [signer] receives the UTF-8 bytes of the text that has to be signed and
     * returns a DER-encoded ECDSA signature. It is supplied by the caller so
     * the private key never enters this adapter.
     */
    fun create(
        manifestId: String,
        deviceToken: String,
        keyOrigin: ArKernelSignatureKeyOrigin,
        publicKey: PublicKey,
        entries: List<ArKernelSignatureEntryInput>,
        signer: (ByteArray) -> ByteArray,
    ): ArKernelSignatureCreateResult {
        if (!SIGN_TOKEN.matches(manifestId) ||
            !SIGN_TOKEN.matches(deviceToken)
        ) {
            return createReject(
                ArKernelSignatureRejection.INVALID_TOKEN,
                "Invalid signature token")
        }
        if (entries.isEmpty() || entries.size > SIGN_MAX_ENTRIES) {
            return createReject(
                ArKernelSignatureRejection.INVALID_COUNT,
                "Signature entry count is out of bounds")
        }
        val bound = ArrayList<ArKernelSignatureEntry>(entries.size)
        for ((index, input) in entries.withIndex()) {
            if (!SIGN_TOKEN.matches(input.artifactName)) {
                return createReject(
                    ArKernelSignatureRejection.INVALID_TOKEN,
                    "Invalid signature artefact name")
            }
            if (input.sourceText.isBlank()) {
                return createReject(
                    ArKernelSignatureRejection.EMPTY_SOURCE,
                    "Signature artefact text is empty")
            }
            bound += try {
                ArKernelSignatureEntry(
                    index = index,
                    artifactName = input.artifactName,
                    digest = signSha256Hex(input.sourceText.toByteArray()),
                )
            } catch (error: IllegalArgumentException) {
                return createReject(
                    ArKernelSignatureRejection.INVALID_ROW,
                    error.message ?: "Invalid signature entry")
            }
        }
        val manifest = try {
            ArKernelSignatureManifest(
                manifestId = manifestId,
                deviceToken = deviceToken,
                keyOrigin = keyOrigin,
                entries = bound,
            )
        } catch (error: IllegalArgumentException) {
            return createReject(
                ArKernelSignatureRejection.INVALID_ROW,
                error.message ?: "Invalid signature manifest")
        }
        val fingerprint = fingerprintOf(publicKey)
        val manifestDigest = signSha256Hex(
            buildLines(manifest, fingerprint, "", "")
                .joinToString("\n").plus("\n").toByteArray())
        val signedText = buildLines(manifest, fingerprint, manifestDigest, "")
            .joinToString("\n").plus("\n")
        val signature = try {
            signer(signedText.toByteArray())
        } catch (error: Exception) {
            return createReject(
                ArKernelSignatureRejection.SIGNING_FAILED,
                error.javaClass.simpleName + ": " +
                    (error.message ?: "signing failed"))
        }
        if (signature.isEmpty()) {
            return createReject(
                ArKernelSignatureRejection.SIGNING_FAILED,
                "Signer returned an empty signature")
        }
        return ArKernelSignatureCreateResult(
            ArKernelSignedManifest(
                manifest = manifest,
                manifestDigest = manifestDigest,
                signature = Base64.getEncoder().encodeToString(signature),
                publicKeyFingerprint = fingerprint,
            ),
            ArKernelSignatureRejection.NONE,
            "",
        )
    }

    fun encode(signed: ArKernelSignedManifest): String =
        buildLines(
            signed.manifest,
            signed.publicKeyFingerprint,
            signed.manifestDigest,
            signed.signature,
        ).joinToString("\n").plus("\n")

    /** Verifies the signature and the key binding against [publicKey]. */
    fun verify(
        signed: ArKernelSignedManifest,
        publicKey: PublicKey,
    ): ArKernelSignatureVerifyResult {
        val fingerprint = fingerprintOf(publicKey)
        val keyMatches = fingerprint == signed.publicKeyFingerprint
        val signedText = buildLines(
            signed.manifest,
            signed.publicKeyFingerprint,
            signed.manifestDigest,
            "",
        ).joinToString("\n").plus("\n")
        var signatureValid = false
        var detail = ""
        try {
            val verifier = Signature.getInstance(SIGN_JCA_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(signedText.toByteArray())
            signatureValid = verifier.verify(
                Base64.getDecoder().decode(signed.signature))
            if (!signatureValid) {
                detail = "Signature does not match the manifest text"
            }
        } catch (error: Exception) {
            signatureValid = false
            detail = error.javaClass.simpleName + ": " +
                (error.message ?: "verification failed")
        }
        if (!keyMatches) {
            detail = if (detail.isEmpty()) {
                "Public key fingerprint does not match the manifest"
            } else {
                "Public key fingerprint does not match the manifest; $detail"
            }
        }
        return ArKernelSignatureVerifyResult(
            keyFingerprintMatches = keyMatches,
            signatureValid = signatureValid,
            publicKeyFingerprint = fingerprint,
            detail = detail,
        )
    }

    fun parse(text: String): ArKernelSignatureParseResult {
        if (text.length > SIGN_MAX_INPUT_CHARACTERS) {
            return signReject(
                ArKernelSignatureRejection.INPUT_TOO_LARGE, 0,
                "Signature manifest exceeds its bounded length")
        }
        if (text.isBlank()) {
            return signReject(
                ArKernelSignatureRejection.EMPTY_MANIFEST, 0,
                "Signature manifest is empty")
        }
        val normalized =
            if (text.startsWith("﻿")) text.substring(1) else text
        val lines = normalized.trimEnd('\n').split('\n')
        val headerError = validateLine(lines[0].trimEnd('\r'), 1)
        if (headerError != null) return headerError
        if (lines[0].trimEnd('\r') != SIGN_HEADER) {
            return signReject(
                ArKernelSignatureRejection.INVALID_HEADER, 1,
                "Signature manifest must start with ARKSIGN1")
        }
        if (lines.size < SIGN_KEYS.size + 2) {
            return signReject(
                ArKernelSignatureRejection.INVALID_SCHEMA, 1,
                "Signature manifest is missing its key block")
        }
        val values = LinkedHashMap<String, String>()
        val lineByKey = LinkedHashMap<String, Int>()
        for ((offset, key) in SIGN_KEYS.withIndex()) {
            val lineNumber = offset + 2
            val lineText = lines[offset + 1].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            val separator = lineText.indexOf('=')
            if (separator <= 0) {
                return signReject(
                    ArKernelSignatureRejection.INVALID_SCHEMA,
                    lineNumber, "Signature line is not a key")
            }
            if (lineText.substring(0, separator) != key) {
                return signReject(
                    ArKernelSignatureRejection.INVALID_SCHEMA,
                    lineNumber, "Signature key order is invalid")
            }
            values[key] = lineText.substring(separator + 1)
            lineByKey[key] = lineNumber
        }
        if (values.getValue("version") != SIGN_VERSION) {
            return signReject(
                ArKernelSignatureRejection.INVALID_VALUE,
                lineByKey.getValue("version"),
                "Signature version must be one")
        }
        if (values.getValue("quality_grade") != SIGN_QUALITY) {
            return signReject(
                ArKernelSignatureRejection.INVALID_QUALITY,
                lineByKey.getValue("quality_grade"),
                "Signature cannot assert assessed quality")
        }
        if (values.getValue("signature_algorithm") != SIGN_ALGORITHM) {
            return signReject(
                ArKernelSignatureRejection.INVALID_ALGORITHM,
                lineByKey.getValue("signature_algorithm"),
                "Signature algorithm must be sha256withecdsa")
        }
        for (key in SIGN_TRUE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return signReject(
                    ArKernelSignatureRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Signature flag must be a boolean")
            }
            if (value != "true") {
                return signReject(
                    ArKernelSignatureRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Signature safety invariant must be true")
            }
        }
        for (key in SIGN_FALSE_KEYS) {
            val value = values.getValue(key)
            if (value != "true" && value != "false") {
                return signReject(
                    ArKernelSignatureRejection.INVALID_BOOLEAN,
                    lineByKey.getValue(key),
                    "Signature flag must be a boolean")
            }
            if (value != "false") {
                return signReject(
                    ArKernelSignatureRejection.UNSAFE_INVARIANT,
                    lineByKey.getValue(key),
                    "Signature cannot claim metric accuracy")
            }
        }
        for (key in listOf("manifest_id", "device_token")) {
            if (!SIGN_TOKEN.matches(values.getValue(key))) {
                return signReject(
                    ArKernelSignatureRejection.INVALID_TOKEN,
                    lineByKey.getValue(key),
                    "Invalid signature token")
            }
        }
        val keyOrigin = ArKernelSignatureKeyOrigin.fromWire(
            values.getValue("key_origin"))
            ?: return signReject(
                ArKernelSignatureRejection.INVALID_KEY_ORIGIN,
                lineByKey.getValue("key_origin"),
                "Invalid signature key origin")
        if (!SIGN_HEX64.matches(
                values.getValue("public_key_fingerprint"))) {
            return signReject(
                ArKernelSignatureRejection.INVALID_DIGEST,
                lineByKey.getValue("public_key_fingerprint"),
                "Invalid public key fingerprint")
        }
        if (!SIGN_HEX64.matches(values.getValue(SIGN_DIGEST_KEY))) {
            return signReject(
                ArKernelSignatureRejection.INVALID_DIGEST,
                lineByKey.getValue(SIGN_DIGEST_KEY),
                "Invalid manifest digest")
        }
        val signature = values.getValue(SIGN_SIGNATURE_KEY)
        if (!SIGN_BASE64.matches(signature)) {
            return signReject(
                ArKernelSignatureRejection.INVALID_SIGNATURE,
                lineByKey.getValue(SIGN_SIGNATURE_KEY),
                "Invalid signature encoding")
        }
        val entryCount = signUInt(values.getValue("entry_count"))
            ?: return signReject(
                ArKernelSignatureRejection.INVALID_VALUE,
                lineByKey.getValue("entry_count"),
                "Invalid signature entry count")
        if (entryCount !in 1..SIGN_MAX_ENTRIES) {
            return signReject(
                ArKernelSignatureRejection.INVALID_COUNT,
                lineByKey.getValue("entry_count"),
                "Signature entry count is out of bounds")
        }
        val rowStart = SIGN_KEYS.size + 1
        val endIndex = rowStart + entryCount
        if (lines.size != endIndex + 1) {
            return signReject(
                ArKernelSignatureRejection.INVALID_SCHEMA, 0,
                "Signature line count does not match entry count")
        }
        val entries = ArrayList<ArKernelSignatureEntry>(entryCount)
        for (index in 0 until entryCount) {
            val lineNumber = rowStart + index + 1
            val lineText = lines[rowStart + index].trimEnd('\r')
            val lineError = validateLine(lineText, lineNumber)
            if (lineError != null) return lineError
            if (!lineText.startsWith("entry=")) {
                return signReject(
                    ArKernelSignatureRejection.INVALID_ROW,
                    lineNumber, "Signature line is not an entry")
            }
            val fields =
                lineText.substringAfter("entry=").split('|')
            if (fields.size != SIGN_ROW_FIELDS) {
                return signReject(
                    ArKernelSignatureRejection.INVALID_ROW,
                    lineNumber,
                    "Signature entry must have three fields")
            }
            val rowIndex = signUInt(fields[0])
                ?: return signReject(
                    ArKernelSignatureRejection.INVALID_VALUE,
                    lineNumber, "Invalid signature entry index")
            if (rowIndex != index) {
                return signReject(
                    ArKernelSignatureRejection.INVALID_ORDER,
                    lineNumber, "Signature entries are not contiguous")
            }
            entries += try {
                ArKernelSignatureEntry(
                    index = rowIndex,
                    artifactName = fields[1],
                    digest = fields[2],
                )
            } catch (error: IllegalArgumentException) {
                return signReject(
                    ArKernelSignatureRejection.INVALID_ROW,
                    lineNumber,
                    error.message ?: "Invalid signature entry")
            }
        }
        if (entries.map { it.artifactName }.toSet().size != entries.size) {
            return signReject(
                ArKernelSignatureRejection.DUPLICATE_ENTRY,
                rowStart + 1, "Duplicate signature artefact name")
        }
        if (entries.map { it.digest }.toSet().size != entries.size) {
            return signReject(
                ArKernelSignatureRejection.DUPLICATE_DIGEST,
                rowStart + 1, "Duplicate signature digest")
        }
        val endLineNumber = endIndex + 1
        val endText = lines[endIndex].trimEnd('\r')
        val endError = validateLine(endText, endLineNumber)
        if (endError != null) return endError
        if (endText != SIGN_END) {
            return signReject(
                ArKernelSignatureRejection.INVALID_SCHEMA,
                endLineNumber, "Signature manifest must end with END")
        }
        val manifest = try {
            ArKernelSignatureManifest(
                manifestId = values.getValue("manifest_id"),
                deviceToken = values.getValue("device_token"),
                keyOrigin = keyOrigin,
                entries = entries,
            )
        } catch (error: IllegalArgumentException) {
            return signReject(
                ArKernelSignatureRejection.INVALID_VALUE, 0,
                error.message ?: "Invalid signature manifest")
        }
        val expectedDigest = signSha256Hex(
            buildLines(manifest, values.getValue("public_key_fingerprint"),
                "", "").joinToString("\n").plus("\n").toByteArray())
        if (expectedDigest != values.getValue(SIGN_DIGEST_KEY)) {
            return signReject(
                ArKernelSignatureRejection.MANIFEST_DIGEST_MISMATCH,
                lineByKey.getValue(SIGN_DIGEST_KEY),
                "Manifest digest does not match the entries")
        }
        return ArKernelSignatureParseResult(
            ArKernelSignedManifest(
                manifest = manifest,
                manifestDigest = values.getValue(SIGN_DIGEST_KEY),
                signature = signature,
                publicKeyFingerprint =
                    values.getValue("public_key_fingerprint"),
            ),
            ArKernelSignatureRejection.NONE,
            0,
            "",
        )
    }

    private fun buildLines(
        manifest: ArKernelSignatureManifest,
        fingerprint: String,
        manifestDigest: String,
        signature: String,
    ): List<String> {
        val lines = ArrayList<String>(SIGN_KEYS.size +
            manifest.entries.size + 2)
        lines += SIGN_HEADER
        lines += "version=$SIGN_VERSION"
        lines += "manifest_id=${manifest.manifestId}"
        lines += "device_token=${manifest.deviceToken}"
        lines += "key_origin=${manifest.keyOrigin.wireName()}"
        lines += "signature_algorithm=$SIGN_ALGORITHM"
        lines += "public_key_fingerprint=$fingerprint"
        lines += "entry_count=${manifest.entries.size}"
        lines += "$SIGN_DIGEST_KEY=$manifestDigest"
        lines += "$SIGN_SIGNATURE_KEY=$signature"
        lines += "automatic_calibration_disabled=true"
        lines += "automatic_upload_disabled=true"
        lines += "universal_calibration_disabled=true"
        lines += "images_disabled=true"
        lines += "manual_upload_only=true"
        lines += "metric_accuracy_claimed=false"
        lines += "external_verification_required=true"
        lines += "quality_grade=$SIGN_QUALITY"
        for (entry in manifest.entries) {
            lines += "entry=" + listOf(
                entry.index.toString(),
                entry.artifactName,
                entry.digest,
            ).joinToString("|")
        }
        lines += SIGN_END
        return lines
    }

    private fun validateLine(
        lineText: String,
        lineNumber: Int,
    ): ArKernelSignatureParseResult? {
        if (lineText.length > SIGN_MAX_LINE_CHARACTERS) {
            return signReject(
                ArKernelSignatureRejection.LINE_TOO_LONG,
                lineNumber,
                "Signature line exceeds its bounded length")
        }
        if (lineText.isBlank()) {
            return signReject(
                ArKernelSignatureRejection.EMPTY_LINE,
                lineNumber,
                "Signature manifest contains a blank line")
        }
        if (lineText.any { it.isWhitespace() }) {
            return signReject(
                ArKernelSignatureRejection.INVALID_SCHEMA,
                lineNumber,
                "Signature lines must not contain whitespace")
        }
        return null
    }

    private fun signUInt(value: String): Int? =
        if (SIGN_UNSIGNED.matches(value)) value.toIntOrNull() else null

    private fun signSha256Hex(bytes: ByteArray): String {
        val digested = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digested.size * 2)
        for (byte in digested) {
            val value = byte.toInt() and 0xFF
            if (value < 16) builder.append('0')
            builder.append(value.toString(16))
        }
        return builder.toString()
    }

    private fun signReject(
        rejection: ArKernelSignatureRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelSignatureParseResult =
        ArKernelSignatureParseResult(null, rejection, lineNumber, detail)

    private fun createReject(
        rejection: ArKernelSignatureRejection,
        detail: String,
    ): ArKernelSignatureCreateResult =
        ArKernelSignatureCreateResult(null, rejection, detail)
}
