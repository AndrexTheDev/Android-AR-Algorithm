package ai.arena.arkernel

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32

private const val ADMISSION_EVIDENCE_MAGIC = 0x41524B41 // ARKA
private const val ADMISSION_EVIDENCE_VERSION = 4
private const val MAX_ADMISSION_EVIDENCE_BYTES = 16 * 1024
private const val MAX_ADMISSION_ID_LENGTH = 256
private const val MAX_PERSISTED_ROTATION_OBSERVATIONS = 512

data class PersistedAdmissionEvidence(
    val capabilitySignatureSha256: String,
    val calibrationProfileSha256: String,
    val softwareRevision: String,
    val recordedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val promotedTier: DeviceCompatibilityTier,
    val observedCameraTimebase: ObservedCameraTimebase,
    val activeBlockingReasons: List<ActiveCompatibilityReasonCode>,
    val rotationReport: RotationObservabilityReport?,
    val timeOffsetReport: TimeOffsetObservabilityReport? = null,
    val timeOffsetDriftReport: TimeOffsetDriftReport? = null,
    val metricConsistencyReport: MetricConsistencyReport? = null,
) {
    init {
        require(capabilitySignatureSha256.isSha256())
        require(calibrationProfileSha256.isSha256())
        require(softwareRevision.isNotBlank() && softwareRevision.length <= MAX_ADMISSION_ID_LENGTH)
        require(recordedAtEpochMillis >= 0 && expiresAtEpochMillis > recordedAtEpochMillis)
        require(activeBlockingReasons.distinct().size == activeBlockingReasons.size)
        if (promotedTier == DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC)
            require(activeBlockingReasons.isEmpty())
    }

    fun matches(signature: DeviceCapabilitySignature, calibrationSha256: String,
        revision: String, nowEpochMillis: Long): Boolean =
        capabilitySignatureSha256 == signature.stableIdSha256 &&
            calibrationProfileSha256 == calibrationSha256 &&
            softwareRevision == revision && nowEpochMillis in recordedAtEpochMillis until expiresAtEpochMillis

    companion object {
        fun fromReports(signature: DeviceCapabilitySignature, calibrationProfileSha256: String,
            softwareRevision: String, activeReport: ActiveCompatibilityReport,
            rotationReport: RotationObservabilityReport?, recordedAtEpochMillis: Long,
            validityMillis: Long,
            timeOffsetReport: TimeOffsetObservabilityReport? = null,
            timeOffsetDriftReport: TimeOffsetDriftReport? = null,
            metricConsistencyReport: MetricConsistencyReport? = null): PersistedAdmissionEvidence {
            require(validityMillis in 1..30L * 24 * 60 * 60 * 1_000)
            return PersistedAdmissionEvidence(signature.stableIdSha256,
                calibrationProfileSha256, softwareRevision, recordedAtEpochMillis,
                Math.addExact(recordedAtEpochMillis, validityMillis), activeReport.promotedTier,
                activeReport.observedCameraTimebase, activeReport.blockingReasons, rotationReport,
                timeOffsetReport, timeOffsetDriftReport, metricConsistencyReport)
        }
    }
}

/**
 * App-private, atomic cache of measured admission evidence. Exact capability, calibration and
 * software identities plus expiry prevent an OTA or profile change from inheriting stale success.
 * This cache is diagnostic/advisory: continuous session monitors still run, and it is not signed
 * dimensional evidence for a centimetre-accuracy claim.
 */
class AdmissionEvidenceStore(context: Context, fileName: String = "arkernel-admission.bin") {
    private val file: AtomicFile

    init {
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        file = AtomicFile(File(context.noBackupFilesDir, fileName))
    }

    @Synchronized
    fun write(evidence: PersistedAdmissionEvidence) {
        val bytes = AdmissionEvidenceCodec.encode(evidence)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun readExact(signature: DeviceCapabilitySignature, calibrationSha256: String,
        revision: String, nowEpochMillis: Long = System.currentTimeMillis()): PersistedAdmissionEvidence? {
        if (!calibrationSha256.isSha256() || revision.isBlank()) return null
        val base = file.baseFile
        if (!base.isFile || base.length() !in 1..MAX_ADMISSION_EVIDENCE_BYTES.toLong()) return null
        val evidence = runCatching { AdmissionEvidenceCodec.decode(file.readFully()) }.getOrNull()
            ?: return null
        return evidence.takeIf { it.matches(signature, calibrationSha256, revision, nowEpochMillis) }
    }

    @Synchronized fun clear() { file.delete() }
}

internal object AdmissionEvidenceCodec {
    fun encode(value: PersistedAdmissionEvidence): ByteArray {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(ADMISSION_EVIDENCE_MAGIC)
                output.writeInt(ADMISSION_EVIDENCE_VERSION)
                output.writeUTF(value.capabilitySignatureSha256)
                output.writeUTF(value.calibrationProfileSha256)
                output.writeUTF(value.softwareRevision)
                output.writeLong(value.recordedAtEpochMillis)
                output.writeLong(value.expiresAtEpochMillis)
                output.writeInt(value.promotedTier.ordinal)
                output.writeInt(value.observedCameraTimebase.ordinal)
                output.writeInt(value.activeBlockingReasons.size)
                value.activeBlockingReasons.forEach { output.writeInt(it.ordinal) }
                output.writeBoolean(value.rotationReport != null)
                value.rotationReport?.let { report ->
                    output.writeInt(report.matchedRotations)
                    output.writeDouble(report.totalVisualRotationRadians)
                    output.writeDouble(report.totalPredictedRotationRadians)
                    output.writeDouble(report.directionCosine)
                    output.writeDouble(report.magnitudeRatio)
                    output.writeInt(report.reasons.size)
                    report.reasons.forEach { output.writeInt(it.ordinal) }
                }
                output.writeBoolean(value.timeOffsetReport != null)
                value.timeOffsetReport?.let { report ->
                    output.writeInt(report.gyroscopeSamples)
                    output.writeInt(report.visualRotations)
                    output.writeLong(report.observedSpanNs)
                    output.writeBoolean(report.visualToGyroscopeOffsetNs != null)
                    if (report.visualToGyroscopeOffsetNs != null) {
                        output.writeLong(report.visualToGyroscopeOffsetNs)
                        output.writeLong(requireNotNull(report.offsetUncertaintyNs))
                        output.writeDouble(requireNotNull(report.offsetVarianceNsSquared))
                    }
                    output.writeDouble(report.bestCorrelation)
                    output.writeDouble(report.peakMargin)
                    output.writeInt(report.reasons.size)
                    report.reasons.forEach { output.writeInt(it.ordinal) }
                }
                output.writeBoolean(value.timeOffsetDriftReport != null)
                value.timeOffsetDriftReport?.let { report ->
                    output.writeInt(report.acceptedWindows)
                    output.writeInt(report.rejectedWindows)
                    output.writeLong(report.observedSpanNs)
                    output.writeBoolean(report.driftPpm != null)
                    if (report.driftPpm != null) {
                        output.writeDouble(report.driftPpm)
                        output.writeDouble(requireNotNull(report.driftUncertaintyPpm))
                        output.writeDouble(requireNotNull(report.residualRmseNs))
                    }
                    output.writeInt(report.reasons.size)
                    report.reasons.forEach { output.writeInt(it.ordinal) }
                }
                output.writeBoolean(value.metricConsistencyReport != null)
                value.metricConsistencyReport?.let { report ->
                    output.writeInt(report.stationarySamples)
                    output.writeDouble(report.gravityMeanMetersPerSecondSquared)
                    output.writeDouble(report.gravityStdDevMetersPerSecondSquared)
                    output.writeDouble(report.gyroscopeRmsRadiansPerSecond)
                    output.writeInt(report.translationObservations)
                    output.writeInt(report.scaleAcceptedObservations)
                    output.writeDouble(report.medianTranslationDirectionCosine)
                    output.writeInt(report.knownDistanceObservations)
                    output.writeDouble(report.knownDistanceMeanAbsoluteErrorMeters)
                    output.writeDouble(report.knownDistanceMaximumRelativeError)
                    output.writeInt(report.runtimeReasons.size)
                    report.runtimeReasons.forEach { output.writeInt(it.ordinal) }
                    output.writeInt(report.targetReasons.size)
                    report.targetReasons.forEach { output.writeInt(it.ordinal) }
                }
            }
        }.toByteArray()
        require(payload.size + 8 <= MAX_ADMISSION_EVIDENCE_BYTES)
        val crc = CRC32().apply { update(payload) }.value
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(payload.size)
                output.write(payload)
                output.writeInt(crc.toInt())
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): PersistedAdmissionEvidence {
        require(bytes.size in 9..MAX_ADMISSION_EVIDENCE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val size = input.readInt()
        require(size in 1..MAX_ADMISSION_EVIDENCE_BYTES - 8 && bytes.size == size + 8)
        val payload = ByteArray(size).also { input.readFully(it) }
        val storedCrc = input.readInt().toLong() and 0xffffffffL
        require(CRC32().apply { update(payload) }.value == storedCrc)
        return DataInputStream(ByteArrayInputStream(payload)).use { data ->
            require(data.readInt() == ADMISSION_EVIDENCE_MAGIC)
            require(data.readInt() == ADMISSION_EVIDENCE_VERSION)
            val signature = data.readUTF().also { require(it.isSha256()) }
            val calibration = data.readUTF().also { require(it.isSha256()) }
            val revision = data.readUTF().also {
                require(it.isNotBlank() && it.length <= MAX_ADMISSION_ID_LENGTH)
            }
            val recorded = data.readLong()
            val expires = data.readLong()
            val tier = enumAt<DeviceCompatibilityTier>(data.readInt())
            val timebase = enumAt<ObservedCameraTimebase>(data.readInt())
            val activeReasons = readEnums<ActiveCompatibilityReasonCode>(data)
            val rotation = if (!data.readBoolean()) null else {
                val matched = data.readInt().also { require(it in 0..MAX_PERSISTED_ROTATION_OBSERVATIONS) }
                val visual = data.readDouble()
                val predicted = data.readDouble()
                val cosine = data.readDouble()
                val ratio = data.readDouble()
                require(listOf(visual, predicted, cosine, ratio).all { it.isFinite() })
                RotationObservabilityReport(matched, visual, predicted, cosine, ratio,
                    readEnums(data))
            }
            val timeOffset = if (!data.readBoolean()) null else {
                val gyroSamples = data.readInt()
                val visualRotations = data.readInt()
                val span = data.readLong()
                val hasEstimate = data.readBoolean()
                val estimate = if (hasEstimate) data.readLong() else null
                val uncertainty = if (hasEstimate) data.readLong() else null
                val variance = if (hasEstimate) data.readDouble() else null
                val correlation = data.readDouble()
                val margin = data.readDouble()
                TimeOffsetObservabilityReport(gyroSamples, visualRotations, span,
                    estimate, uncertainty, variance, correlation, margin, readEnums(data))
            }
            val drift = if (!data.readBoolean()) null else {
                val acceptedWindows = data.readInt()
                val rejectedWindows = data.readInt()
                val span = data.readLong()
                val hasEstimate = data.readBoolean()
                val driftPpm = if (hasEstimate) data.readDouble() else null
                val uncertaintyPpm = if (hasEstimate) data.readDouble() else null
                val residualRmseNs = if (hasEstimate) data.readDouble() else null
                TimeOffsetDriftReport(acceptedWindows, rejectedWindows, span,
                    driftPpm, uncertaintyPpm, residualRmseNs, readEnums(data))
            }
            val metric = if (!data.readBoolean()) null else MetricConsistencyReport(
                data.readInt(), data.readDouble(), data.readDouble(), data.readDouble(),
                data.readInt(), data.readInt(), data.readDouble(), data.readInt(),
                data.readDouble(), data.readDouble(), readEnums(data), readEnums(data))
            require(data.available() == 0)
            PersistedAdmissionEvidence(signature, calibration, revision, recorded, expires,
                tier, timebase, activeReasons, rotation, timeOffset, drift, metric)
        }
    }

    private inline fun <reified T : Enum<T>> readEnums(input: DataInputStream): List<T> {
        val count = input.readInt()
        require(count in 0..128)
        return List(count) { enumAt<T>(input.readInt()) }.also {
            require(it.distinct().size == it.size)
        }
    }

    private inline fun <reified T : Enum<T>> enumAt(index: Int): T =
        enumValues<T>().getOrNull(index) ?: throw IllegalArgumentException("Invalid enum ordinal")
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
