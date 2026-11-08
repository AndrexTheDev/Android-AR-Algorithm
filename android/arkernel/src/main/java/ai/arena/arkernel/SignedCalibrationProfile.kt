package ai.arena.arkernel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

private const val CALIBRATION_PROFILE_MAGIC = 0x41524B43 // ARKC
private const val CALIBRATION_PROFILE_VERSION = 1
private const val MAX_CALIBRATION_PROFILE_BYTES = 16 * 1024
private const val MAX_CALIBRATION_SIGNATURE_BYTES = 512

enum class CalibrationProfileIssuerClass {
    USER_MEASURED,
    MANUFACTURER,
    INDEPENDENT_LAB,
}

enum class CalibrationMetricClaim {
    TRACKING_ONLY,
    APPROXIMATE_METRIC,
    CENTIMETER_VALIDATED,
}

data class CalibrationStreamBinding(
    val cameraId: String,
    val physicalCameraId: String?,
    val width: Int,
    val height: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val videoStabilizationMode: Int,
    val opticalStabilizationMode: Int,
    val distortionCorrectionMode: Int,
) {
    init {
        require(cameraId.isNotBlank() && cameraId.length <= 256)
        require(physicalCameraId == null ||
            (physicalCameraId.isNotBlank() && physicalCameraId.length <= 256))
        require(width > 0 && height > 0)
        require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0)
    }
}

data class CalibrationMeasurementEvidence(
    val targetObservationCount: Int,
    val meanAbsoluteErrorMeters: Double,
    val maximumRelativeError: Double,
    val reprojectionRmsePixels: Double,
    val maximumValidatedDistanceMeters: Double,
    val rotationObservable: Boolean,
    val timeOffsetObservable: Boolean,
    val metricRuntimeConsistent: Boolean,
) {
    init {
        require(targetObservationCount in 0..100_000)
        require(listOf(meanAbsoluteErrorMeters, maximumRelativeError,
            reprojectionRmsePixels, maximumValidatedDistanceMeters)
            .all { it.isFinite() && it >= 0.0 })
    }
}

data class CalibrationProfile(
    val profileId: String,
    val revision: Long,
    val issuerClass: CalibrationProfileIssuerClass,
    val metricClaim: CalibrationMetricClaim,
    val capabilitySignatureSha256: String,
    val stream: CalibrationStreamBinding,
    val calibration: CameraCalibration,
    val bodyFromCameraRotation: Rotation,
    val bodyFromCameraTranslationMeters: Vector3,
    val cameraToImuOffsetNs: Long,
    val cameraToImuOffsetUncertaintyNs: Long,
    val evidence: CalibrationMeasurementEvidence,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(profileId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(revision > 0)
        require(capabilitySignatureSha256.matches(Regex("[0-9a-f]{64}")))
        require(calibration.width == stream.width && calibration.height == stream.height)
        require(listOf(calibration.fx, calibration.fy, calibration.cx, calibration.cy,
            calibration.k1, calibration.k2, calibration.p1, calibration.p2, calibration.k3,
            bodyFromCameraRotation.w, bodyFromCameraRotation.x, bodyFromCameraRotation.y,
            bodyFromCameraRotation.z, bodyFromCameraTranslationMeters.x,
            bodyFromCameraTranslationMeters.y, bodyFromCameraTranslationMeters.z)
            .all { it.isFinite() })
        require(calibration.fx > 0.0 && calibration.fy > 0.0)
        require(calibration.cx in 0.0..calibration.width.toDouble() &&
            calibration.cy in 0.0..calibration.height.toDouble())
        require(listOf(calibration.k1, calibration.k2, calibration.p1,
            calibration.p2, calibration.k3).all { absCalibration(it) <= 10.0 })
        val translationNormSquared = bodyFromCameraTranslationMeters.x *
            bodyFromCameraTranslationMeters.x + bodyFromCameraTranslationMeters.y *
            bodyFromCameraTranslationMeters.y + bodyFromCameraTranslationMeters.z *
            bodyFromCameraTranslationMeters.z
        require(translationNormSquared <= 4.0)
        val rotationNorm = bodyFromCameraRotation.w * bodyFromCameraRotation.w +
            bodyFromCameraRotation.x * bodyFromCameraRotation.x +
            bodyFromCameraRotation.y * bodyFromCameraRotation.y +
            bodyFromCameraRotation.z * bodyFromCameraRotation.z
        require(rotationNorm in 0.99..1.01)
        require(absLongCalibration(cameraToImuOffsetNs) <= 1_000_000_000L)
        require(cameraToImuOffsetUncertaintyNs in 0..1_000_000_000L)
        require(createdAtEpochMillis >= 0 && expiresAtEpochMillis > createdAtEpochMillis)
        require(expiresAtEpochMillis - createdAtEpochMillis <=
            10L * 365 * 24 * 60 * 60 * 1_000)
        if (metricClaim == CalibrationMetricClaim.CENTIMETER_VALIDATED) {
            require(issuerClass == CalibrationProfileIssuerClass.INDEPENDENT_LAB)
            require(evidence.targetObservationCount >= 3)
            require(evidence.meanAbsoluteErrorMeters <= 0.01)
            require(evidence.maximumRelativeError <= 0.02)
            require(evidence.maximumValidatedDistanceMeters > 0.0)
            require(evidence.reprojectionRmsePixels <= 1.0)
            require(evidence.rotationObservable && evidence.timeOffsetObservable &&
                evidence.metricRuntimeConsistent)
        }
        if (metricClaim == CalibrationMetricClaim.APPROXIMATE_METRIC)
            require(evidence.metricRuntimeConsistent)
    }
}

enum class CalibrationSignatureAlgorithm { ECDSA_P256_SHA256 }

class SignedCalibrationProfile(
    val keyId: String,
    val algorithm: CalibrationSignatureAlgorithm,
    payload: ByteArray,
    signature: ByteArray,
) {
    private val payloadBytes: ByteArray = payload.copyOf()
    private val signatureBytes: ByteArray = signature.copyOf()
    val payload: ByteArray get() = payloadBytes.copyOf()
    val signature: ByteArray get() = signatureBytes.copyOf()

    init {
        require(keyId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(payload.size in 1..MAX_CALIBRATION_PROFILE_BYTES)
        require(signature.size in 1..MAX_CALIBRATION_SIGNATURE_BYTES)
    }

    val profileSha256: String by lazy { sha256Calibration(payloadBytes) }

    override fun equals(other: Any?): Boolean = other is SignedCalibrationProfile &&
        keyId == other.keyId && algorithm == other.algorithm &&
        payloadBytes.contentEquals(other.payloadBytes) &&
        signatureBytes.contentEquals(other.signatureBytes)

    override fun hashCode(): Int = (((keyId.hashCode() * 31 + algorithm.hashCode()) * 31 +
        payloadBytes.contentHashCode()) * 31 + signatureBytes.contentHashCode())
}

data class TrustedCalibrationKey(
    val keyId: String,
    val publicKey: PublicKey,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val revokedAtEpochMillis: Long? = null,
) {
    init {
        require(keyId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(validFromEpochMillis >= 0 && validUntilEpochMillis > validFromEpochMillis)
        require(revokedAtEpochMillis == null || revokedAtEpochMillis >= validFromEpochMillis)
    }
}

/** Host-installed offline trust policy. Its revision must itself be rollback-protected by the host. */
class CalibrationTrustPolicy(
    val revision: Long,
    trustedKeys: List<TrustedCalibrationKey>,
    minimumProfileRevisions: Map<String, Long> = emptyMap(),
    val validFromEpochMillis: Long = 0L,
    val validUntilEpochMillis: Long = Long.MAX_VALUE,
) {
    val trustedKeys: List<TrustedCalibrationKey> = trustedKeys.toList()
    val minimumProfileRevisions: Map<String, Long> = minimumProfileRevisions.toMap()
    private val keysById = this.trustedKeys.associateBy { it.keyId }

    init {
        require(revision > 0)
        require(validFromEpochMillis >= 0 && validUntilEpochMillis > validFromEpochMillis)
        require(this.trustedKeys.size <= 32 && keysById.size == this.trustedKeys.size)
        require(this.minimumProfileRevisions.size <= 256)
        require(this.minimumProfileRevisions.all { (id, floor) ->
            id.matches(Regex("[A-Za-z0-9._:-]{1,128}")) && floor > 0
        })
    }

    internal fun key(keyId: String): TrustedCalibrationKey? = keysById[keyId]
    internal fun minimumRevision(profileId: String): Long =
        minimumProfileRevisions[profileId] ?: 0L
}

enum class CalibrationVerificationReason {
    TRUST_POLICY_NOT_YET_VALID,
    TRUST_POLICY_EXPIRED,
    UNKNOWN_SIGNING_KEY,
    SIGNING_KEY_NOT_YET_VALID,
    SIGNING_KEY_EXPIRED,
    SIGNING_KEY_REVOKED,
    UNSUPPORTED_KEY_TYPE,
    SIGNATURE_INVALID,
    PAYLOAD_INVALID,
    PROFILE_EXPIRED,
    PROFILE_NOT_YET_VALID,
    PROFILE_REVISION_ROLLBACK,
    CAPABILITY_SIGNATURE_MISMATCH,
    CAMERA_STREAM_MISMATCH,
}

data class CalibrationVerificationResult(
    val profile: CalibrationProfile?,
    val profileSha256: String?,
    val reasons: List<CalibrationVerificationReason>,
) {
    val accepted: Boolean get() = profile != null && reasons.isEmpty()
}

/** Verifies authenticity and exact runtime binding; it never upgrades the claim in the payload. */
class CalibrationProfileVerifier(
    private val trustPolicy: CalibrationTrustPolicy,
    minimumAcceptedPolicyRevision: Long = 0,
) {
    init {
        require(minimumAcceptedPolicyRevision >= 0)
        require(trustPolicy.revision >= minimumAcceptedPolicyRevision) {
            "Calibration trust policy rollback rejected"
        }
    }

    constructor(trustedKeys: Map<String, PublicKey>) : this(CalibrationTrustPolicy(
        revision = 1,
        trustedKeys = trustedKeys.map { (id, key) ->
            TrustedCalibrationKey(id, key, 0L, Long.MAX_VALUE)
        },
    ))

    fun verify(signed: SignedCalibrationProfile, signature: DeviceCapabilitySignature,
        stream: CalibrationStreamBinding, nowEpochMillis: Long,
        minimumAcceptedRevision: Long = 0): CalibrationVerificationResult {
        require(minimumAcceptedRevision >= 0)
        if (nowEpochMillis < trustPolicy.validFromEpochMillis)
            return rejected(CalibrationVerificationReason.TRUST_POLICY_NOT_YET_VALID)
        if (nowEpochMillis >= trustPolicy.validUntilEpochMillis)
            return rejected(CalibrationVerificationReason.TRUST_POLICY_EXPIRED)
        val trustedKey = trustPolicy.key(signed.keyId) ?: return rejected(
            CalibrationVerificationReason.UNKNOWN_SIGNING_KEY)
        if (nowEpochMillis < trustedKey.validFromEpochMillis)
            return rejected(CalibrationVerificationReason.SIGNING_KEY_NOT_YET_VALID)
        if (trustedKey.revokedAtEpochMillis?.let { nowEpochMillis >= it } == true)
            return rejected(CalibrationVerificationReason.SIGNING_KEY_REVOKED)
        if (nowEpochMillis >= trustedKey.validUntilEpochMillis)
            return rejected(CalibrationVerificationReason.SIGNING_KEY_EXPIRED)
        val key = trustedKey.publicKey
        if (key !is ECPublicKey || !key.algorithm.equals("EC", ignoreCase = true) ||
            key.params.order.bitLength() != 256)
            return rejected(CalibrationVerificationReason.UNSUPPORTED_KEY_TYPE)
        val signatureValid = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(signed.payload); verify(signed.signature)
            }
        }.getOrDefault(false)
        if (!signatureValid) return rejected(CalibrationVerificationReason.SIGNATURE_INVALID)
        val profile = runCatching { CalibrationProfileCodec.decode(signed.payload) }.getOrNull()
            ?: return rejected(CalibrationVerificationReason.PAYLOAD_INVALID)
        val reasons = mutableListOf<CalibrationVerificationReason>()
        if (nowEpochMillis < profile.createdAtEpochMillis)
            reasons += CalibrationVerificationReason.PROFILE_NOT_YET_VALID
        if (nowEpochMillis >= profile.expiresAtEpochMillis)
            reasons += CalibrationVerificationReason.PROFILE_EXPIRED
        val requiredRevision = maxOf(minimumAcceptedRevision,
            trustPolicy.minimumRevision(profile.profileId))
        if (profile.revision < requiredRevision)
            reasons += CalibrationVerificationReason.PROFILE_REVISION_ROLLBACK
        if (profile.capabilitySignatureSha256 != signature.stableIdSha256)
            reasons += CalibrationVerificationReason.CAPABILITY_SIGNATURE_MISMATCH
        if (profile.stream != stream)
            reasons += CalibrationVerificationReason.CAMERA_STREAM_MISMATCH
        return CalibrationVerificationResult(profile.takeIf { reasons.isEmpty() },
            signed.profileSha256, reasons)
    }

    private fun rejected(reason: CalibrationVerificationReason) =
        CalibrationVerificationResult(null, null, listOf(reason))
}

internal object CalibrationProfileCodec {
    fun encode(profile: CalibrationProfile): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(CALIBRATION_PROFILE_MAGIC)
            output.writeInt(CALIBRATION_PROFILE_VERSION)
            output.writeUTF(profile.profileId)
            output.writeLong(profile.revision)
            output.writeInt(profile.issuerClass.ordinal)
            output.writeInt(profile.metricClaim.ordinal)
            output.writeUTF(profile.capabilitySignatureSha256)
            writeStream(output, profile.stream)
            val camera = profile.calibration
            output.writeInt(camera.width); output.writeInt(camera.height)
            listOf(camera.fx, camera.fy, camera.cx, camera.cy, camera.k1, camera.k2,
                camera.p1, camera.p2, camera.k3).forEach(output::writeDouble)
            val rotation = profile.bodyFromCameraRotation
            listOf(rotation.w, rotation.x, rotation.y, rotation.z).forEach(output::writeDouble)
            val translation = profile.bodyFromCameraTranslationMeters
            listOf(translation.x, translation.y, translation.z).forEach(output::writeDouble)
            output.writeLong(profile.cameraToImuOffsetNs)
            output.writeLong(profile.cameraToImuOffsetUncertaintyNs)
            val evidence = profile.evidence
            output.writeInt(evidence.targetObservationCount)
            output.writeDouble(evidence.meanAbsoluteErrorMeters)
            output.writeDouble(evidence.maximumRelativeError)
            output.writeDouble(evidence.reprojectionRmsePixels)
            output.writeDouble(evidence.maximumValidatedDistanceMeters)
            output.writeBoolean(evidence.rotationObservable)
            output.writeBoolean(evidence.timeOffsetObservable)
            output.writeBoolean(evidence.metricRuntimeConsistent)
            output.writeLong(profile.createdAtEpochMillis)
            output.writeLong(profile.expiresAtEpochMillis)
        }
    }.toByteArray().also { require(it.size <= MAX_CALIBRATION_PROFILE_BYTES) }

    fun decode(payload: ByteArray): CalibrationProfile {
        require(payload.size in 1..MAX_CALIBRATION_PROFILE_BYTES)
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == CALIBRATION_PROFILE_MAGIC)
            require(input.readInt() == CALIBRATION_PROFILE_VERSION)
            val id = input.readUTF()
            val revision = input.readLong()
            val issuer = enumValue<CalibrationProfileIssuerClass>(input.readInt())
            val claim = enumValue<CalibrationMetricClaim>(input.readInt())
            val capability = input.readUTF()
            val stream = readStream(input)
            val width = input.readInt(); val height = input.readInt()
            val values = DoubleArray(16) { input.readDouble() }
            val calibration = CameraCalibration(width, height, values[0], values[1], values[2],
                values[3], values[4], values[5], values[6], values[7], values[8])
            val rotation = Rotation(values[9], values[10], values[11], values[12])
            val translation = Vector3(values[13], values[14], values[15])
            val offset = input.readLong(); val offsetUncertainty = input.readLong()
            val evidence = CalibrationMeasurementEvidence(input.readInt(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(), input.readBoolean(),
                input.readBoolean(), input.readBoolean())
            val created = input.readLong(); val expires = input.readLong()
            require(input.available() == 0)
            CalibrationProfile(id, revision, issuer, claim, capability, stream, calibration, rotation,
                translation, offset, offsetUncertainty, evidence, created, expires)
        }
    }

    private fun writeStream(output: DataOutputStream, stream: CalibrationStreamBinding) {
        output.writeUTF(stream.cameraId)
        output.writeBoolean(stream.physicalCameraId != null)
        stream.physicalCameraId?.let(output::writeUTF)
        output.writeInt(stream.width); output.writeInt(stream.height)
        output.writeInt(stream.cropLeft); output.writeInt(stream.cropTop)
        output.writeInt(stream.cropWidth); output.writeInt(stream.cropHeight)
        output.writeInt(stream.videoStabilizationMode)
        output.writeInt(stream.opticalStabilizationMode)
        output.writeInt(stream.distortionCorrectionMode)
    }

    private fun readStream(input: DataInputStream) = CalibrationStreamBinding(
        input.readUTF(), if (input.readBoolean()) input.readUTF() else null,
        input.readInt(), input.readInt(), input.readInt(), input.readInt(),
        input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt())

    private inline fun <reified T : Enum<T>> enumValue(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal) ?: throw IllegalArgumentException("Invalid enum ordinal")
}

private fun absCalibration(value: Double): Double = if (value < 0.0) -value else value
private fun absLongCalibration(value: Long): Long = when {
    value == Long.MIN_VALUE -> Long.MAX_VALUE
    value < 0L -> -value
    else -> value
}

private fun sha256Calibration(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
