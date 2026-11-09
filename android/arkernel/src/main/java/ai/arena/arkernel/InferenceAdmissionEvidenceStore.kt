package ai.arena.arkernel

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

private const val INFERENCE_EVIDENCE_MAGIC = 0x41524945 // ARIE
private const val INFERENCE_EVIDENCE_VERSION = 3
private const val MAX_INFERENCE_EVIDENCE_BYTES = 8 * 1024
private const val MAX_INFERENCE_EVIDENCE_ID = 128
private const val MAX_INFERENCE_EVIDENCE_VALIDITY_MILLIS = 30L * 24 * 60 * 60 * 1_000

internal fun InferenceManifestIdentity.stableSha256(): String {
    val canonical = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(schemaVersion)
            output.writeUTF(modelId); output.writeUTF(modelVersion)
            output.writeUTF(modelSha256); output.writeLong(modelByteSize)
            output.writeUTF(licenseSpdx); output.writeUTF(sourceUrl)
            output.writeInt(tasks.size); tasks.forEach { output.writeInt(it.ordinal) }
            output.writeInt(inputWidth); output.writeInt(inputHeight); output.writeInt(inputChannels)
            output.writeInt(inputMean.size); inputMean.forEach(output::writeDouble)
            output.writeInt(inputStd.size); inputStd.forEach(output::writeDouble)
            output.writeInt(quantization.ordinal)
            output.writeBoolean(quantizationScale != null)
            quantizationScale?.let(output::writeDouble)
            output.writeBoolean(quantizationZeroPoint != null)
            quantizationZeroPoint?.let(output::writeInt)
            output.writeInt(labels.size)
            labels.forEach { (id, label) -> output.writeLong(id); output.writeUTF(label) }
            output.writeBoolean(depthUnitToMeters != null)
            depthUnitToMeters?.let(output::writeDouble)
            output.writeInt(deviceProfiles.size)
            deviceProfiles.forEach {
                output.writeUTF(it.capabilitySignatureSha256)
                output.writeUTF(it.backendId); output.writeUTF(it.backendVersion)
                output.writeUTF(it.driverFingerprintSha256)
                output.writeDouble(it.averageLatencyMs); output.writeDouble(it.p95LatencyMs)
                output.writeLong(it.peakWorkingMemoryBytes); output.writeInt(it.sampleCount)
            }
        }
    }.toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(canonical)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun InferenceSemanticConformanceOptions.stableSha256(): String {
    val bytes = ByteArrayOutputStream().also { sink ->
        DataOutputStream(sink).use { output ->
            output.writeDouble(boxTolerancePixels)
            output.writeDouble(anchorTolerancePixels)
            output.writeDouble(confidenceTolerance)
            output.writeDouble(depthAbsoluteToleranceMeters)
            output.writeDouble(depthRelativeTolerance)
            output.writeDouble(depthConfidenceTolerance)
        }
    }.toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

data class PersistedInferenceAdmissionEvidence internal constructor(
    val manifestIdentitySha256: String,
    val capabilitySignatureSha256: String,
    val calibrationProfileSha256: String,
    val goldenSuiteSha256: String,
    val conformanceOptionsSha256: String,
    val adapterId: String,
    val adapterVersion: String,
    val driverFingerprintSha256: String,
    val softwareRevision: String,
    val policyRevision: Long,
    val recordedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val evaluatedCases: Int,
    val comparedDetections: Long,
    val comparedDepthPixels: Long,
    val maximumDetectionCoordinateErrorPixels: Double,
    val maximumDetectionConfidenceError: Double,
    val maximumDepthAbsoluteErrorMeters: Double,
    val maximumDepthRelativeError: Double,
    val maximumDepthConfidenceError: Double,
) {
    init {
        require(manifestIdentitySha256.isInferenceSha256())
        require(capabilitySignatureSha256.isInferenceSha256())
        require(calibrationProfileSha256.isInferenceSha256())
        require(goldenSuiteSha256.isInferenceSha256())
        require(conformanceOptionsSha256.isInferenceSha256())
        require(adapterId.isInferenceEvidenceId() && adapterVersion.isInferenceEvidenceId() &&
            driverFingerprintSha256.isInferenceSha256() &&
            softwareRevision.isInferenceEvidenceId())
        require(policyRevision >= 0)
        require(recordedAtEpochMillis >= 0 && expiresAtEpochMillis > recordedAtEpochMillis &&
            expiresAtEpochMillis - recordedAtEpochMillis <=
                MAX_INFERENCE_EVIDENCE_VALIDITY_MILLIS)
        require(evaluatedCases in 1..16 && comparedDetections >= 0 && comparedDepthPixels >= 0)
        require(errors().all { it.isFinite() && it >= 0.0 })
    }

    fun matches(identity: VerifiedInferenceIdentity, signature: DeviceCapabilitySignature,
        calibrationSha256: String, expectedGoldenSuiteSha256: String,
        expectedOptions: InferenceSemanticConformanceOptions, expectedAdapterId: String,
        expectedAdapterVersion: String, expectedDriverIdentity: InferenceDriverIdentity,
        expectedSoftwareRevision: String,
        expectedPolicyRevision: Long, nowEpochMillis: Long): Boolean =
        manifestIdentitySha256 == identity.manifest.stableSha256() &&
            capabilitySignatureSha256 == signature.stableIdSha256 &&
            calibrationProfileSha256 == calibrationSha256 &&
            goldenSuiteSha256 == expectedGoldenSuiteSha256 &&
            conformanceOptionsSha256 == expectedOptions.stableSha256() &&
            adapterId == expectedAdapterId &&
            adapterVersion == expectedAdapterVersion &&
            driverFingerprintSha256 == expectedDriverIdentity.fingerprintSha256 &&
            softwareRevision == expectedSoftwareRevision &&
            policyRevision == expectedPolicyRevision &&
            nowEpochMillis in recordedAtEpochMillis until expiresAtEpochMillis

    internal fun errors() = listOf(maximumDetectionCoordinateErrorPixels,
        maximumDetectionConfidenceError, maximumDepthAbsoluteErrorMeters,
        maximumDepthRelativeError, maximumDepthConfidenceError)

    companion object {
        fun fromAccepted(
            identity: VerifiedInferenceIdentity,
            signature: DeviceCapabilitySignature,
            calibrationProfileSha256: String,
            goldenSuiteSha256: String,
            options: InferenceSemanticConformanceOptions,
            adapterId: String,
            adapterVersion: String,
            driverIdentity: InferenceDriverIdentity,
            softwareRevision: String,
            policyRevision: Long,
            result: VerifiedInferenceBackendAdmissionResult,
            recordedAtEpochMillis: Long,
            validityMillis: Long,
        ): PersistedInferenceAdmissionEvidence {
            require(result.status ==
                InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_ACCEPTED)
            val report = requireNotNull(result.conformanceReport)
            require(report.delegateAccepted)
            require(validityMillis in 1..MAX_INFERENCE_EVIDENCE_VALIDITY_MILLIS)
            return PersistedInferenceAdmissionEvidence(identity.manifest.stableSha256(),
                signature.stableIdSha256, calibrationProfileSha256, goldenSuiteSha256,
                options.stableSha256(), adapterId, adapterVersion,
                driverIdentity.fingerprintSha256, softwareRevision, policyRevision,
                recordedAtEpochMillis,
                Math.addExact(recordedAtEpochMillis, validityMillis), report.evaluatedCases,
                report.comparedDetections, report.comparedDepthPixels,
                report.maximumDetectionCoordinateErrorPixels,
                report.maximumDetectionConfidenceError,
                report.maximumDepthAbsoluteErrorMeters, report.maximumDepthRelativeError,
                report.maximumDepthConfidenceError)
        }
    }
}

/** Atomic app-private cache of accepted, exact-signature inference conformance evidence. */
class InferenceAdmissionEvidenceStore(
    context: Context,
    fileName: String = "arkernel-inference-admission.bin",
) {
    private val file: AtomicFile

    init {
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        file = AtomicFile(File(context.noBackupFilesDir, fileName))
    }

    @Synchronized
    fun write(evidence: PersistedInferenceAdmissionEvidence) {
        val output = file.startWrite()
        try {
            output.write(InferenceAdmissionEvidenceCodec.encode(evidence))
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun readExact(
        identity: VerifiedInferenceIdentity,
        signature: DeviceCapabilitySignature,
        calibrationSha256: String,
        goldenSuiteSha256: String,
        options: InferenceSemanticConformanceOptions,
        adapterId: String,
        adapterVersion: String,
        driverIdentity: InferenceDriverIdentity,
        softwareRevision: String,
        policyRevision: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PersistedInferenceAdmissionEvidence? {
        if (!calibrationSha256.isInferenceSha256() ||
            !goldenSuiteSha256.isInferenceSha256() || !adapterId.isInferenceEvidenceId() ||
            !adapterVersion.isInferenceEvidenceId() ||
            !softwareRevision.isInferenceEvidenceId() || policyRevision < 0 ||
            nowEpochMillis < 0) return null
        val base = file.baseFile
        if (!base.isFile || base.length() !in 1..MAX_INFERENCE_EVIDENCE_BYTES.toLong()) return null
        val evidence = runCatching {
            InferenceAdmissionEvidenceCodec.decode(file.readFully())
        }.getOrNull() ?: return null
        return evidence.takeIf { it.matches(identity, signature, calibrationSha256,
            goldenSuiteSha256, options, adapterId, adapterVersion,
            driverIdentity, softwareRevision, policyRevision, nowEpochMillis) }
    }

    @Synchronized fun clear() { file.delete() }
}

internal object InferenceAdmissionEvidenceCodec {
    fun encode(value: PersistedInferenceAdmissionEvidence): ByteArray {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(INFERENCE_EVIDENCE_MAGIC)
                output.writeInt(INFERENCE_EVIDENCE_VERSION)
                output.writeUTF(value.manifestIdentitySha256)
                output.writeUTF(value.capabilitySignatureSha256)
                output.writeUTF(value.calibrationProfileSha256)
                output.writeUTF(value.goldenSuiteSha256)
                output.writeUTF(value.conformanceOptionsSha256)
                output.writeUTF(value.adapterId); output.writeUTF(value.adapterVersion)
                output.writeUTF(value.driverFingerprintSha256)
                output.writeUTF(value.softwareRevision); output.writeLong(value.policyRevision)
                output.writeLong(value.recordedAtEpochMillis)
                output.writeLong(value.expiresAtEpochMillis)
                output.writeInt(value.evaluatedCases)
                output.writeLong(value.comparedDetections); output.writeLong(value.comparedDepthPixels)
                value.errors().forEach(output::writeDouble)
            }
        }.toByteArray()
        require(payload.size + 8 <= MAX_INFERENCE_EVIDENCE_BYTES)
        val crc = CRC32().apply { update(payload) }.value
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(payload.size); output.write(payload); output.writeInt(crc.toInt())
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): PersistedInferenceAdmissionEvidence {
        require(bytes.size in 9..MAX_INFERENCE_EVIDENCE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val size = input.readInt()
        require(size in 1..MAX_INFERENCE_EVIDENCE_BYTES - 8 && bytes.size == size + 8)
        val payload = ByteArray(size).also(input::readFully)
        val storedCrc = input.readInt().toLong() and 0xffff_ffffL
        require(CRC32().apply { update(payload) }.value == storedCrc)
        return DataInputStream(ByteArrayInputStream(payload)).use { data ->
            require(data.readInt() == INFERENCE_EVIDENCE_MAGIC)
            require(data.readInt() == INFERENCE_EVIDENCE_VERSION)
            val manifest = data.readUTF()
            val capability = data.readUTF()
            val calibration = data.readUTF()
            val goldenSuite = data.readUTF(); val options = data.readUTF()
            val adapterId = data.readUTF(); val adapterVersion = data.readUTF()
            val driver = data.readUTF()
            val software = data.readUTF(); val policy = data.readLong()
            val recorded = data.readLong(); val expires = data.readLong()
            val cases = data.readInt(); val detections = data.readLong()
            val depth = data.readLong(); val errors = DoubleArray(5) { data.readDouble() }
            require(data.available() == 0)
            PersistedInferenceAdmissionEvidence(manifest, capability, calibration, goldenSuite,
                options, adapterId, adapterVersion, driver, software, policy, recorded, expires, cases,
                detections, depth,
                errors[0], errors[1], errors[2], errors[3], errors[4])
        }
    }
}

private fun String.isInferenceSha256() = matches(Regex("[0-9a-f]{64}"))
private fun String.isInferenceEvidenceId() =
    matches(Regex("[A-Za-z0-9._:+-]{1,$MAX_INFERENCE_EVIDENCE_ID}"))
