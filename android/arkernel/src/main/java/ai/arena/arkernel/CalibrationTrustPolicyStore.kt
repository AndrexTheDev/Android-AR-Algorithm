package ai.arena.arkernel

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

private const val TRUST_POLICY_MAGIC = 0x41524B54 // ARKT
private const val TRUST_POLICY_VERSION = 1
private const val MAX_TRUST_POLICY_BYTES = 64 * 1024
private const val MAX_ENCODED_PUBLIC_KEY_BYTES = 2 * 1024
private const val MAX_POLICY_SIGNATURE_BYTES = 512

class SignedCalibrationTrustPolicy(
    val rootKeyId: String,
    payload: ByteArray,
    signature: ByteArray,
) {
    private val payloadBytes = payload.copyOf()
    private val signatureBytes = signature.copyOf()
    val payload: ByteArray get() = payloadBytes.copyOf()
    val signature: ByteArray get() = signatureBytes.copyOf()

    init {
        require(rootKeyId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(payload.size in 1..MAX_TRUST_POLICY_BYTES)
        require(signature.size in 1..MAX_POLICY_SIGNATURE_BYTES)
    }
}

enum class TrustPolicyInstallReason {
    UNKNOWN_ROOT_KEY,
    UNSUPPORTED_ROOT_KEY,
    SIGNATURE_INVALID,
    PAYLOAD_INVALID,
    REVISION_ROLLBACK,
    STORAGE_FAILURE,
}

data class TrustPolicyInstallResult(
    val installedPolicy: CalibrationTrustPolicy?,
    val reason: TrustPolicyInstallReason?,
) {
    val installed: Boolean get() = installedPolicy != null && reason == null
}

/**
 * Verifies root-signed policy documents and atomically installs only a strictly newer revision.
 * Public keys are not secret; signature authenticity and monotonic revision are the security
 * properties. The host must keep its small root-key set in the application trust boundary.
 */
class CalibrationTrustPolicyStore(
    context: Context,
    rootKeys: Map<String, PublicKey>,
    fileName: String = "arkernel-calibration-trust.bin",
    private val minimumAcceptedPolicyRevision: Long = 0,
) {
    private val roots = rootKeys.toMap()
    private val file: AtomicFile

    init {
        require(roots.size <= 16)
        require(minimumAcceptedPolicyRevision >= 0)
        require(roots.keys.all { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) })
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        file = AtomicFile(File(context.noBackupFilesDir, fileName))
    }

    @Synchronized
    fun install(signed: SignedCalibrationTrustPolicy): TrustPolicyInstallResult {
        val verified = verify(signed)
        val policy = verified.installedPolicy ?: return verified
        val storedExists = file.baseFile.isFile
        val currentSigned = readSigned()
        if (storedExists && currentSigned == null)
            return TrustPolicyInstallResult(null, TrustPolicyInstallReason.STORAGE_FAILURE)
        val currentVerified = currentSigned?.let(::verify)
        if (currentSigned != null && currentVerified?.installedPolicy == null)
            return currentVerified ?: TrustPolicyInstallResult(null,
                TrustPolicyInstallReason.STORAGE_FAILURE)
        val currentRevision = currentVerified?.installedPolicy?.revision ?: 0L
        if (policy.revision < minimumAcceptedPolicyRevision || policy.revision <= currentRevision)
            return TrustPolicyInstallResult(null, TrustPolicyInstallReason.REVISION_ROLLBACK)
        val encoded = TrustPolicyEnvelopeCodec.encode(signed)
        val output = runCatching { file.startWrite() }.getOrElse {
            return TrustPolicyInstallResult(null, TrustPolicyInstallReason.STORAGE_FAILURE)
        }
        return try {
            output.write(encoded); output.fd.sync(); file.finishWrite(output)
            TrustPolicyInstallResult(policy, null)
        } catch (_: Throwable) {
            file.failWrite(output)
            TrustPolicyInstallResult(null, TrustPolicyInstallReason.STORAGE_FAILURE)
        }
    }

    /** Returns null for absent, corrupted, untrusted, not-yet-valid, or expired policy data. */
    @Synchronized
    fun load(nowEpochMillis: Long): CalibrationTrustPolicy? {
        val policy = readSigned()?.let(::verify)?.installedPolicy ?: return null
        return policy.takeIf { it.revision >= minimumAcceptedPolicyRevision &&
            nowEpochMillis in it.validFromEpochMillis until it.validUntilEpochMillis }
    }

    @Synchronized fun clear() { file.delete() }

    private fun verify(signed: SignedCalibrationTrustPolicy): TrustPolicyInstallResult {
        val root = roots[signed.rootKeyId] ?: return TrustPolicyInstallResult(null,
            TrustPolicyInstallReason.UNKNOWN_ROOT_KEY)
        if (root !is ECPublicKey || root.params.order.bitLength() != 256)
            return TrustPolicyInstallResult(null, TrustPolicyInstallReason.UNSUPPORTED_ROOT_KEY)
        val valid = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(root); update(signed.payload); verify(signed.signature)
            }
        }.getOrDefault(false)
        if (!valid) return TrustPolicyInstallResult(null, TrustPolicyInstallReason.SIGNATURE_INVALID)
        val policy = runCatching { CalibrationTrustPolicyCodec.decode(signed.payload) }.getOrNull()
            ?: return TrustPolicyInstallResult(null, TrustPolicyInstallReason.PAYLOAD_INVALID)
        return TrustPolicyInstallResult(policy, null)
    }

    private fun readSigned(): SignedCalibrationTrustPolicy? {
        val base = file.baseFile
        if (!base.isFile || base.length() !in 1..MAX_TRUST_POLICY_BYTES.toLong()) return null
        return runCatching { TrustPolicyEnvelopeCodec.decode(file.readFully()) }.getOrNull()
    }
}

internal object CalibrationTrustPolicyCodec {
    fun encode(policy: CalibrationTrustPolicy): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(TRUST_POLICY_MAGIC)
                output.writeInt(TRUST_POLICY_VERSION)
                output.writeLong(policy.revision)
                output.writeLong(policy.validFromEpochMillis)
                output.writeLong(policy.validUntilEpochMillis)
                val keys = policy.trustedKeys.sortedBy { it.keyId }
                output.writeInt(keys.size)
                keys.forEach { key ->
                    output.writeUTF(key.keyId)
                    val encoded = key.publicKey.encoded
                    require(encoded.size in 1..MAX_ENCODED_PUBLIC_KEY_BYTES)
                    output.writeInt(encoded.size); output.write(encoded)
                    output.writeLong(key.validFromEpochMillis)
                    output.writeLong(key.validUntilEpochMillis)
                    output.writeBoolean(key.revokedAtEpochMillis != null)
                    key.revokedAtEpochMillis?.let(output::writeLong)
                }
                val floors = policy.minimumProfileRevisions.toSortedMap()
                output.writeInt(floors.size)
                floors.forEach { (id, revision) -> output.writeUTF(id); output.writeLong(revision) }
            }
        }.toByteArray().also { require(it.size <= MAX_TRUST_POLICY_BYTES) }

    fun decode(payload: ByteArray): CalibrationTrustPolicy {
        require(payload.size in 1..MAX_TRUST_POLICY_BYTES)
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == TRUST_POLICY_MAGIC)
            require(input.readInt() == TRUST_POLICY_VERSION)
            val revision = input.readLong()
            val validFrom = input.readLong(); val validUntil = input.readLong()
            val keyCount = input.readInt().also { require(it in 0..32) }
            val keys = ArrayList<TrustedCalibrationKey>(keyCount)
            repeat(keyCount) {
                val id = input.readUTF()
                val size = input.readInt().also { require(it in 1..MAX_ENCODED_PUBLIC_KEY_BYTES) }
                val encoded = ByteArray(size).also { input.readFully(it) }
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
                val from = input.readLong(); val until = input.readLong()
                val revoked = if (input.readBoolean()) input.readLong() else null
                keys += TrustedCalibrationKey(id, key, from, until, revoked)
            }
            val floorCount = input.readInt().also { require(it in 0..256) }
            val floors = LinkedHashMap<String, Long>(floorCount)
            repeat(floorCount) { require(floors.put(input.readUTF(), input.readLong()) == null) }
            require(input.available() == 0)
            CalibrationTrustPolicy(revision, keys, floors, validFrom, validUntil)
        }
    }
}

internal object TrustPolicyEnvelopeCodec {
    fun encode(signed: SignedCalibrationTrustPolicy): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(signed.rootKeyId)
                val payload = signed.payload; val signature = signed.signature
                output.writeInt(payload.size); output.write(payload)
                output.writeInt(signature.size); output.write(signature)
            }
        }.toByteArray().also { require(it.size <= MAX_TRUST_POLICY_BYTES) }

    fun decode(bytes: ByteArray): SignedCalibrationTrustPolicy {
        require(bytes.size in 1..MAX_TRUST_POLICY_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val id = input.readUTF()
            val payloadSize = input.readInt().also { require(it in 1..MAX_TRUST_POLICY_BYTES) }
            val payload = ByteArray(payloadSize).also { input.readFully(it) }
            val signatureSize = input.readInt().also {
                require(it in 1..MAX_POLICY_SIGNATURE_BYTES)
            }
            val signature = ByteArray(signatureSize).also { input.readFully(it) }
            require(input.available() == 0)
            SignedCalibrationTrustPolicy(id, payload, signature)
        }
    }
}
