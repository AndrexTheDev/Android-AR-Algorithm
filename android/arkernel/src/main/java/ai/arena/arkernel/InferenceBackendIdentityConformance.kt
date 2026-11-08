package ai.arena.arkernel

import java.nio.ByteBuffer

/** Immutable, exact snapshot of every bounded field in a verified model manifest. */
data class InferenceManifestIdentity internal constructor(
    val schemaVersion: Int,
    val modelId: String,
    val modelVersion: String,
    val modelSha256: String,
    val modelByteSize: Long,
    val licenseSpdx: String,
    val sourceUrl: String,
    val tasks: List<InferenceTask>,
    val inputWidth: Int,
    val inputHeight: Int,
    val inputChannels: Int,
    val inputMean: List<Double>,
    val inputStd: List<Double>,
    val quantization: TensorQuantization,
    val quantizationScale: Double?,
    val quantizationZeroPoint: Int?,
    val labels: Map<Long, String>,
    val depthUnitToMeters: Double?,
    val deviceProfiles: List<InferenceDeviceProfile>,
)

/**
 * An identity can only be minted after the supplied model artifact matches the manifest byte count
 * and SHA-256. It is an admission token, not a claim that an arbitrary backend used those bytes.
 */
class VerifiedInferenceIdentity private constructor(
    val manifest: InferenceManifestIdentity,
) {
    companion object {
        fun create(manifest: ModelManifest, modelBytes: ByteBuffer): VerifiedInferenceIdentity {
            require(manifest.verifyModelBytes(modelBytes)) {
                "Cannot create inference identity from unverified model bytes"
            }
            return VerifiedInferenceIdentity(manifest.exactIdentity())
        }
    }
}

internal fun ModelManifest.exactIdentity(): InferenceManifestIdentity =
    InferenceManifestIdentity(
        schemaVersion = schemaVersion,
        modelId = modelId,
        modelVersion = modelVersion,
        modelSha256 = sha256,
        modelByteSize = modelByteSize,
        licenseSpdx = licenseSpdx,
        sourceUrl = sourceUrl,
        tasks = tasks.sortedBy { it.ordinal },
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        inputChannels = inputChannels,
        inputMean = inputMean.toList(),
        inputStd = inputStd.toList(),
        quantization = quantization,
        quantizationScale = quantizationScale,
        quantizationZeroPoint = quantizationZeroPoint,
        labels = labels.toSortedMap(),
        depthUnitToMeters = depthUnitToMeters,
        deviceProfiles = deviceProfiles.toList(),
    )

enum class InferenceIdentityFailure {
    DELEGATE_MANIFEST_MISMATCH,
}

data class InferenceIdentityConformanceReport(
    val delegateAccepted: Boolean,
    val expectedModelId: String,
    val expectedModelVersion: String,
    val expectedModelSha256: String,
    val expectedTasks: List<InferenceTask>,
    val failure: InferenceIdentityFailure?,
) {
    init {
        require(expectedModelId.isNotBlank() && expectedModelVersion.isNotBlank())
        require(expectedModelSha256.matches(Regex("[0-9a-f]{64}")))
        require(expectedTasks.isNotEmpty() && expectedTasks.distinct().size == expectedTasks.size)
        require(delegateAccepted == (failure == null))
    }
}

data class IdentityConformingInferenceBackendSelection(
    val backend: OnDeviceInferenceBackend,
    val usingDelegate: Boolean,
    val report: InferenceIdentityConformanceReport,
)

/**
 * Exact manifest/task gate above runtime-specific tensor adapters. Ownership of both backends is
 * transferred. A bad delegate is closed and CPU is retained; an invalid CPU oracle closes both.
 */
object InferenceBackendIdentityConformanceSelector {
    fun select(
        cpuReference: OnDeviceInferenceBackend,
        delegate: OnDeviceInferenceBackend,
        expected: VerifiedInferenceIdentity,
    ): IdentityConformingInferenceBackendSelection {
        val identity = expected.manifest
        if (cpuReference.manifest.exactIdentity() != identity) {
            closeQuietly(delegate)
            closeQuietly(cpuReference)
            throw IllegalArgumentException(
                "CPU backend manifest/task identity does not match verified model artifact")
        }
        if (delegate.manifest.exactIdentity() != identity) {
            closeQuietly(delegate)
            return IdentityConformingInferenceBackendSelection(cpuReference, false,
                report(identity, InferenceIdentityFailure.DELEGATE_MANIFEST_MISMATCH))
        }
        closeQuietly(cpuReference)
        return IdentityConformingInferenceBackendSelection(delegate, true,
            report(identity, null))
    }

    private fun report(identity: InferenceManifestIdentity, failure: InferenceIdentityFailure?) =
        InferenceIdentityConformanceReport(
            delegateAccepted = failure == null,
            expectedModelId = identity.modelId,
            expectedModelVersion = identity.modelVersion,
            expectedModelSha256 = identity.modelSha256,
            expectedTasks = identity.tasks,
            failure = failure,
        )

    private fun closeQuietly(backend: OnDeviceInferenceBackend) {
        try { backend.close() } catch (_: Throwable) { }
    }
}
