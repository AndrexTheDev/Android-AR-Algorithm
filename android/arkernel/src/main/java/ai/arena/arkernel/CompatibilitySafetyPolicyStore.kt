package ai.arena.arkernel

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.PublicKey
import kotlin.math.max

private const val MAX_STORED_SAFETY_POLICY_BYTES = 66 * 1024

enum class SafetyPolicyInstallReason {
    UNKNOWN_ROOT_KEY,
    UNSUPPORTED_ROOT_KEY,
    SIGNATURE_INVALID,
    PAYLOAD_INVALID,
    REVISION_ROLLBACK,
    NOT_YET_VALID,
    EXPIRED,
    STORAGE_FAILURE,
}

data class SafetyPolicyInstallResult(
    val installedPolicy: CompatibilitySafetyPolicy?,
    val reason: SafetyPolicyInstallReason?,
) {
    val installed: Boolean get() = installedPolicy != null && reason == null
}

/**
 * App-private atomic storage for an optional root-signed disable-only safety policy.
 * The built-in policy is never overwritten and remains the deterministic offline fallback.
 */
class CompatibilitySafetyPolicyStore(
    context: Context,
    private val builtIn: CompatibilitySafetyPolicy,
    rootKeys: Map<String, PublicKey>,
    fileName: String = "arkernel-compatibility-safety.bin",
    minimumAcceptedRevision: Long = 0,
) {
    private val verifier = CompatibilitySafetyPolicyVerifier(rootKeys,
        max(minimumAcceptedRevision, if (builtIn.revision == Long.MAX_VALUE)
            Long.MAX_VALUE else builtIn.revision + 1))
    private val resolver = CompatibilitySafetyPolicyResolver(builtIn, verifier)
    private val file: AtomicFile

    init {
        require(minimumAcceptedRevision >= 0)
        require(builtIn.revision < Long.MAX_VALUE)
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        file = AtomicFile(File(context.noBackupFilesDir, fileName))
    }

    /** Verifies validity and atomically installs only a strictly newer trusted revision. */
    @Synchronized
    fun install(signed: SignedCompatibilitySafetyPolicy,
        nowEpochMillis: Long = System.currentTimeMillis()): SafetyPolicyInstallResult {
        require(nowEpochMillis >= 0)
        val verified = verifier.verify(signed, nowEpochMillis)
        val policy = verified.policy ?: return rejected(requireNotNull(verified.reason))
        val storedExists = file.baseFile.isFile
        val currentSigned = readSigned()
        if (storedExists && currentSigned == null)
            return SafetyPolicyInstallResult(null, SafetyPolicyInstallReason.STORAGE_FAILURE)
        val currentPolicy = currentSigned?.let(verifier::verifyAuthenticity)
        if (currentSigned != null && currentPolicy?.policy == null)
            return SafetyPolicyInstallResult(null, SafetyPolicyInstallReason.STORAGE_FAILURE)
        if (policy.revision <= (currentPolicy?.policy?.revision ?: builtIn.revision))
            return SafetyPolicyInstallResult(null, SafetyPolicyInstallReason.REVISION_ROLLBACK)
        val encoded = CompatibilitySafetyPolicyEnvelopeCodec.encode(signed)
        val output = runCatching { file.startWrite() }.getOrElse {
            return SafetyPolicyInstallResult(null, SafetyPolicyInstallReason.STORAGE_FAILURE)
        }
        return try {
            output.write(encoded)
            output.fd.sync()
            file.finishWrite(output)
            SafetyPolicyInstallResult(policy, null)
        } catch (_: Throwable) {
            file.failWrite(output)
            SafetyPolicyInstallResult(null, SafetyPolicyInstallReason.STORAGE_FAILURE)
        }
    }

    /**
     * Resolves the stored remote policy against the permanent built-in fallback. Corrupt,
     * untrusted, expired or missing remote bytes can never prevent an offline startup.
     */
    @Synchronized
    fun load(nowEpochMillis: Long = System.currentTimeMillis()):
        ResolvedCompatibilitySafetyPolicy {
        require(nowEpochMillis >= 0)
        if (!file.baseFile.isFile) return resolver.resolve(null, nowEpochMillis)
        val signed = readSigned() ?: return ResolvedCompatibilitySafetyPolicy(builtIn,
            SafetyPolicySource.BUILT_IN, SafetyPolicyVerificationReason.PAYLOAD_INVALID)
        return resolver.resolve(signed, nowEpochMillis)
    }

    @Synchronized
    fun clearRemote() {
        file.delete()
    }

    private fun readSigned(): SignedCompatibilitySafetyPolicy? {
        val base = file.baseFile
        if (!base.isFile || base.length() !in 1..MAX_STORED_SAFETY_POLICY_BYTES.toLong())
            return null
        return runCatching {
            CompatibilitySafetyPolicyEnvelopeCodec.decode(file.readFully())
        }.getOrNull()
    }

    private fun rejected(reason: SafetyPolicyVerificationReason) = SafetyPolicyInstallResult(
        null, when (reason) {
            SafetyPolicyVerificationReason.UNKNOWN_ROOT_KEY ->
                SafetyPolicyInstallReason.UNKNOWN_ROOT_KEY
            SafetyPolicyVerificationReason.UNSUPPORTED_ROOT_KEY ->
                SafetyPolicyInstallReason.UNSUPPORTED_ROOT_KEY
            SafetyPolicyVerificationReason.SIGNATURE_INVALID ->
                SafetyPolicyInstallReason.SIGNATURE_INVALID
            SafetyPolicyVerificationReason.PAYLOAD_INVALID ->
                SafetyPolicyInstallReason.PAYLOAD_INVALID
            SafetyPolicyVerificationReason.REVISION_ROLLBACK ->
                SafetyPolicyInstallReason.REVISION_ROLLBACK
            SafetyPolicyVerificationReason.NOT_YET_VALID ->
                SafetyPolicyInstallReason.NOT_YET_VALID
            SafetyPolicyVerificationReason.EXPIRED -> SafetyPolicyInstallReason.EXPIRED
        })
}
