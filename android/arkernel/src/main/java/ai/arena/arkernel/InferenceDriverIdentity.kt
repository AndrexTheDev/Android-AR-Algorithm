package ai.arena.arkernel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class InferenceExecutionIdentity(
    val adapterId: String,
    val adapterVersion: String,
    val driverIdentity: InferenceDriverIdentity,
) {
    init {
        require(adapterId.matches(Regex("[A-Za-z0-9._:+-]{1,128}")))
        require(adapterVersion.matches(Regex("[A-Za-z0-9._:+-]{1,128}")))
    }
}

/** Owning adapter for attaching measured runtime identity to a concrete backend. */
class ExecutionIdentifiedInferenceBackend(
    private val delegate: OnDeviceInferenceBackend,
    override val executionIdentity: InferenceExecutionIdentity,
) : OnDeviceInferenceBackend {
    override val manifest: ModelManifest = delegate.manifest
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput = delegate.infer(frame)
    override fun close() = delegate.close()
}

enum class InferenceDriverApi {
    CPU,
    NNAPI,
    OPENGL,
    VULKAN,
    CUSTOM,
}

/**
 * Canonical privacy-safe identity reported by a separately packaged runtime adapter.
 * acceleratorNameSha256 hashes the runtime-reported device/accelerator name; raw names and phone
 * model names are deliberately not retained. Missing driver facts must not be replaced by wildcards.
 */
data class InferenceDriverIdentity(
    val schemaVersion: Int = 1,
    val api: InferenceDriverApi,
    val providerId: String,
    val driverVersion: String,
    val acceleratorNameSha256: String,
    val featureLevel: Int,
) {
    init {
        require(schemaVersion == 1)
        require(providerId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(driverVersion.length in 1..128 && driverVersion == driverVersion.trim() &&
            driverVersion.all { it.code in 0x20..0x7e })
        require(acceleratorNameSha256.matches(Regex("[0-9a-f]{64}")))
        require(featureLevel in 0..1_000_000)
    }

    val fingerprintSha256: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val canonical = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(schemaVersion)
                output.writeInt(api.ordinal)
                output.writeUTF(providerId)
                output.writeUTF(driverVersion)
                output.writeUTF(acceleratorNameSha256)
                output.writeInt(featureLevel)
            }
        }.toByteArray()
        MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        /** Hashes an observed runtime name without retaining it in manifests or diagnostics. */
        fun hashObservedAcceleratorName(observedName: String): String {
            require(observedName.length in 1..512 && observedName.none { it.isISOControl() })
            return MessageDigest.getInstance("SHA-256")
                .digest(observedName.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
