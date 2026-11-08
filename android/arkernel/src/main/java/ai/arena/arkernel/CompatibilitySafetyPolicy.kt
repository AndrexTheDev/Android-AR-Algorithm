package ai.arena.arkernel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

private const val SAFETY_POLICY_MAGIC = 0x41524B53 // ARKS
private const val SAFETY_POLICY_VERSION = 1
private const val MAX_SAFETY_POLICY_BYTES = 64 * 1024
private const val MAX_SAFETY_SIGNATURE_BYTES = 512
private const val MAX_SAFETY_RULES = 128

enum class SafetyBooleanMatch { ANY, REQUIRED, EXCLUDED }

enum class CompatibilitySafetyAction {
    DISABLE_DENSE_DEPTH,
    DISABLE_SEMANTIC_INFERENCE,
    DISABLE_LIVE_MAPPING,
    FORCE_CAPTURE_ONLY,
    FORCE_UNSUPPORTED,
}

data class CompatibilitySafetyRule(
    val ruleId: String,
    val minimumApiLevel: Int = 1,
    val maximumApiLevel: Int = Int.MAX_VALUE,
    val hardwareLevels: List<Int> = emptyList(),
    val timestampSources: List<Int> = emptyList(),
    val lowRamMatch: SafetyBooleanMatch = SafetyBooleanMatch.ANY,
    val maximumMemoryClassMb: Int = Int.MAX_VALUE,
    val anyActiveReasons: List<ActiveCompatibilityReasonCode> = emptyList(),
    val actions: List<CompatibilitySafetyAction>,
) {
    init {
        require(ruleId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(minimumApiLevel in 1..10_000 && maximumApiLevel >= minimumApiLevel)
        require(hardwareLevels.size <= 16 && hardwareLevels.distinct().size == hardwareLevels.size)
        require(timestampSources.size <= 16 &&
            timestampSources.distinct().size == timestampSources.size)
        require(maximumMemoryClassMb > 0)
        require(anyActiveReasons.size <= 32 &&
            anyActiveReasons.distinct().size == anyActiveReasons.size)
        require(actions.isNotEmpty() && actions.size <= CompatibilitySafetyAction.entries.size &&
            actions.distinct().size == actions.size)
    }
}

data class CompatibilitySafetyPolicy(
    val revision: Long,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val rules: List<CompatibilitySafetyRule>,
) {
    init {
        require(revision > 0)
        require(validFromEpochMillis >= 0 && validUntilEpochMillis > validFromEpochMillis)
        require(rules.size <= MAX_SAFETY_RULES)
        require(rules.map { it.ruleId }.distinct().size == rules.size)
    }
}

class SignedCompatibilitySafetyPolicy(
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
        require(payload.size in 1..MAX_SAFETY_POLICY_BYTES)
        require(signature.size in 1..MAX_SAFETY_SIGNATURE_BYTES)
    }
}

enum class SafetyPolicyVerificationReason {
    UNKNOWN_ROOT_KEY,
    UNSUPPORTED_ROOT_KEY,
    SIGNATURE_INVALID,
    PAYLOAD_INVALID,
    REVISION_ROLLBACK,
    NOT_YET_VALID,
    EXPIRED,
}

data class SafetyPolicyVerificationResult(
    val policy: CompatibilitySafetyPolicy?,
    val reason: SafetyPolicyVerificationReason?,
) {
    val accepted: Boolean get() = policy != null && reason == null
}

class CompatibilitySafetyPolicyVerifier(
    rootKeys: Map<String, PublicKey>,
    private val minimumRevision: Long = 0,
) {
    private val roots = rootKeys.toMap()

    init {
        require(roots.size <= 16 && minimumRevision >= 0)
        require(roots.keys.all { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) })
    }

    fun verify(signed: SignedCompatibilitySafetyPolicy,
        nowEpochMillis: Long): SafetyPolicyVerificationResult {
        require(nowEpochMillis >= 0)
        val root = roots[signed.rootKeyId] ?: return rejected(
            SafetyPolicyVerificationReason.UNKNOWN_ROOT_KEY)
        if (root !is ECPublicKey || root.params.order.bitLength() != 256)
            return rejected(SafetyPolicyVerificationReason.UNSUPPORTED_ROOT_KEY)
        val valid = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(root); update(signed.payload); verify(signed.signature)
            }
        }.getOrDefault(false)
        if (!valid) return rejected(SafetyPolicyVerificationReason.SIGNATURE_INVALID)
        val policy = runCatching {
            CompatibilitySafetyPolicyCodec.decode(signed.payload)
        }.getOrNull() ?: return rejected(SafetyPolicyVerificationReason.PAYLOAD_INVALID)
        if (policy.revision < minimumRevision)
            return rejected(SafetyPolicyVerificationReason.REVISION_ROLLBACK)
        if (nowEpochMillis < policy.validFromEpochMillis)
            return rejected(SafetyPolicyVerificationReason.NOT_YET_VALID)
        if (nowEpochMillis >= policy.validUntilEpochMillis)
            return rejected(SafetyPolicyVerificationReason.EXPIRED)
        return SafetyPolicyVerificationResult(policy, null)
    }

    private fun rejected(reason: SafetyPolicyVerificationReason) =
        SafetyPolicyVerificationResult(null, reason)
}

enum class SafetyPolicySource { BUILT_IN, VERIFIED_REMOTE }

data class ResolvedCompatibilitySafetyPolicy(
    val policy: CompatibilitySafetyPolicy,
    val source: SafetyPolicySource,
    val remoteRejection: SafetyPolicyVerificationReason?,
)

/** A missing, invalid, expired, or non-newer remote document always falls back to built-in rules. */
class CompatibilitySafetyPolicyResolver(
    private val builtIn: CompatibilitySafetyPolicy,
    private val verifier: CompatibilitySafetyPolicyVerifier,
) {
    init {
        require(builtIn.validFromEpochMillis == 0L &&
            builtIn.validUntilEpochMillis == Long.MAX_VALUE) {
            "Built-in safety fallback must remain valid offline"
        }
    }

    fun resolve(remote: SignedCompatibilitySafetyPolicy?,
        nowEpochMillis: Long): ResolvedCompatibilitySafetyPolicy {
        if (remote == null) return ResolvedCompatibilitySafetyPolicy(
            builtIn, SafetyPolicySource.BUILT_IN, null)
        val verified = verifier.verify(remote, nowEpochMillis)
        val policy = verified.policy
        if (policy == null) return ResolvedCompatibilitySafetyPolicy(
            builtIn, SafetyPolicySource.BUILT_IN, verified.reason)
        if (policy.revision <= builtIn.revision) return ResolvedCompatibilitySafetyPolicy(
            builtIn, SafetyPolicySource.BUILT_IN,
            SafetyPolicyVerificationReason.REVISION_ROLLBACK)
        return ResolvedCompatibilitySafetyPolicy(policy,
            SafetyPolicySource.VERIFIED_REMOTE, null)
    }
}

object CompatibilitySafetyPolicyEvaluator {
    fun apply(
        decision: RuntimeResourceDecision,
        policy: CompatibilitySafetyPolicy,
        staticReport: DeviceCompatibilityReport,
        activeReport: ActiveCompatibilityReport?,
        nowEpochMillis: Long,
    ): RuntimeResourceDecision {
        require(nowEpochMillis >= 0)
        if (nowEpochMillis !in policy.validFromEpochMillis until policy.validUntilEpochMillis)
            return decision
        val actions = policy.rules.asSequence()
            .filter { matches(it, staticReport, activeReport) }
            .flatMap { it.actions.asSequence() }
            .toSet()
        if (actions.isEmpty()) return decision
        val reasons = (decision.reasons + RuntimeResourceReason.SIGNED_SAFETY_POLICY).distinct()
        if (CompatibilitySafetyAction.FORCE_UNSUPPORTED in actions)
            return RuntimeResourceDecision(RuntimeResourceTier.UNSUPPORTED, 0, 0, 0, 0,
                false, false, false, reasons)
        if (CompatibilitySafetyAction.FORCE_CAPTURE_ONLY in actions ||
            CompatibilitySafetyAction.DISABLE_LIVE_MAPPING in actions)
            return RuntimeResourceDecision(RuntimeResourceTier.CAPTURE_ONLY, 1920 * 1080, 10,
                0, 1, false, false, false, reasons)
        return decision.copy(
            denseDepthEnabled = decision.denseDepthEnabled &&
                CompatibilitySafetyAction.DISABLE_DENSE_DEPTH !in actions,
            semanticInferenceEnabled = decision.semanticInferenceEnabled &&
                CompatibilitySafetyAction.DISABLE_SEMANTIC_INFERENCE !in actions,
            reasons = reasons,
            calibratedAlternativeTier = null,
        )
    }

    private fun matches(rule: CompatibilitySafetyRule,
        staticReport: DeviceCompatibilityReport,
        activeReport: ActiveCompatibilityReport?): Boolean {
        val signature = staticReport.signature
        val camera = signature.camera ?: return false
        if (signature.apiLevel !in rule.minimumApiLevel..rule.maximumApiLevel) return false
        if (rule.hardwareLevels.isNotEmpty() &&
            camera.hardwareLevel !in rule.hardwareLevels) return false
        if (rule.timestampSources.isNotEmpty() &&
            camera.timestampSource !in rule.timestampSources) return false
        if (signature.memoryClassMb > rule.maximumMemoryClassMb) return false
        if (rule.lowRamMatch == SafetyBooleanMatch.REQUIRED && !signature.lowRamDevice) return false
        if (rule.lowRamMatch == SafetyBooleanMatch.EXCLUDED && signature.lowRamDevice) return false
        if (rule.anyActiveReasons.isNotEmpty() &&
            activeReport?.blockingReasons?.any { it in rule.anyActiveReasons } != true) return false
        return true
    }
}

internal object CompatibilitySafetyPolicyCodec {
    fun encode(policy: CompatibilitySafetyPolicy): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(SAFETY_POLICY_MAGIC)
                output.writeInt(SAFETY_POLICY_VERSION)
                output.writeLong(policy.revision)
                output.writeLong(policy.validFromEpochMillis)
                output.writeLong(policy.validUntilEpochMillis)
                val rules = policy.rules.sortedBy { it.ruleId }
                output.writeInt(rules.size)
                rules.forEach { rule ->
                    output.writeUTF(rule.ruleId)
                    output.writeInt(rule.minimumApiLevel)
                    output.writeInt(rule.maximumApiLevel)
                    writeInts(output, rule.hardwareLevels)
                    writeInts(output, rule.timestampSources)
                    output.writeInt(rule.lowRamMatch.ordinal)
                    output.writeInt(rule.maximumMemoryClassMb)
                    val active = rule.anyActiveReasons.sortedBy { it.ordinal }
                    output.writeInt(active.size); active.forEach { output.writeInt(it.ordinal) }
                    val actions = rule.actions.sortedBy { it.ordinal }
                    output.writeInt(actions.size); actions.forEach { output.writeInt(it.ordinal) }
                }
            }
        }.toByteArray().also { require(it.size <= MAX_SAFETY_POLICY_BYTES) }

    fun decode(payload: ByteArray): CompatibilitySafetyPolicy {
        require(payload.size in 1..MAX_SAFETY_POLICY_BYTES)
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == SAFETY_POLICY_MAGIC)
            require(input.readInt() == SAFETY_POLICY_VERSION)
            val revision = input.readLong()
            val from = input.readLong(); val until = input.readLong()
            val count = input.readInt().also { require(it in 0..MAX_SAFETY_RULES) }
            val rules = ArrayList<CompatibilitySafetyRule>(count)
            repeat(count) {
                val id = input.readUTF()
                val minimumApi = input.readInt(); val maximumApi = input.readInt()
                val hardware = readInts(input)
                val timestamps = readInts(input)
                val lowRam = enumValue<SafetyBooleanMatch>(input.readInt())
                val memory = input.readInt()
                val activeCount = input.readInt().also {
                    require(it in 0..ActiveCompatibilityReasonCode.entries.size)
                }
                val active = List(activeCount) {
                    enumValue<ActiveCompatibilityReasonCode>(input.readInt())
                }
                val actionCount = input.readInt().also {
                    require(it in 1..CompatibilitySafetyAction.entries.size)
                }
                val actions = List(actionCount) {
                    enumValue<CompatibilitySafetyAction>(input.readInt())
                }
                rules += CompatibilitySafetyRule(id, minimumApi, maximumApi,
                    hardware, timestamps, lowRam, memory, active, actions)
            }
            require(input.available() == 0)
            CompatibilitySafetyPolicy(revision, from, until, rules)
        }
    }

    private fun writeInts(output: DataOutputStream, values: List<Int>) {
        val sorted = values.sorted()
        output.writeInt(sorted.size); sorted.forEach(output::writeInt)
    }

    private fun readInts(input: DataInputStream): List<Int> {
        val count = input.readInt().also { require(it in 0..16) }
        return List(count) { input.readInt() }
    }

    private inline fun <reified T : Enum<T>> enumValue(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal) ?: throw IllegalArgumentException("Invalid enum ordinal")
}
