package ai.arena.arkernel

import java.nio.ByteBuffer

/** Implemented by separately packaged runtime adapters; the supplied model view is read-only. */
fun interface VerifiedInferenceBackendFactory {
    fun create(manifest: ModelManifest, modelBytes: ByteBuffer): OnDeviceInferenceBackend
}

enum class InferenceBackendAdmissionStatus {
    CPU_ONLY,
    DELEGATE_CREATION_FAILED,
    DELEGATE_CONFORMANCE_REJECTED,
    DELEGATE_CONFORMANCE_ACCEPTED,
    DELEGATE_CACHED_EVIDENCE_ACCEPTED,
}

data class VerifiedInferenceBackendAdmissionResult(
    val backend: OnDeviceInferenceBackend,
    val usingDelegate: Boolean,
    val status: InferenceBackendAdmissionStatus,
    val conformanceReport: InferenceSemanticConformanceReport?,
    val cachedEvidence: PersistedInferenceAdmissionEvidence? = null,
) {
    init {
        require(usingDelegate ==
            (status == InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_ACCEPTED ||
                status == InferenceBackendAdmissionStatus.DELEGATE_CACHED_EVIDENCE_ACCEPTED))
        require((conformanceReport != null) ==
            (status == InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_ACCEPTED ||
                status == InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_REJECTED ||
                status == InferenceBackendAdmissionStatus.DELEGATE_CACHED_EVIDENCE_ACCEPTED))
        require((cachedEvidence != null) ==
            (status == InferenceBackendAdmissionStatus.DELEGATE_CACHED_EVIDENCE_ACCEPTED))
        conformanceReport?.let { require(it.delegateAccepted == usingDelegate) }
    }
}

/**
 * Constructs CPU and optional delegate adapters from fresh read-only views of one verified model
 * artifact, then transfers both to the decoded semantic selector. The returned backend is owned by
 * the caller. No runtime implementation is linked into the core AAR.
 */
object VerifiedInferenceBackendAdmission {
    fun createAndSelect(
        manifest: ModelManifest,
        modelBytes: ByteBuffer,
        cpuFactory: VerifiedInferenceBackendFactory,
        delegateFactory: VerifiedInferenceBackendFactory? = null,
        cases: List<InferenceSemanticGoldenCase> = emptyList(),
        options: InferenceSemanticConformanceOptions = InferenceSemanticConformanceOptions(),
    ): VerifiedInferenceBackendAdmissionResult = createInternal(manifest, modelBytes,
        cpuFactory, delegateFactory, cases, options, null, null, null)

    /** Rechecks artifact/backend identity and uses evidence only when every exact key still matches. */
    fun createAndSelectWithEvidence(
        manifest: ModelManifest,
        modelBytes: ByteBuffer,
        cpuFactory: VerifiedInferenceBackendFactory,
        delegateFactory: VerifiedInferenceBackendFactory,
        evidence: PersistedInferenceAdmissionEvidence,
        signature: DeviceCapabilitySignature,
        calibrationProfileSha256: String,
        goldenSuiteSha256: String,
        adapterId: String,
        adapterVersion: String,
        softwareRevision: String,
        policyRevision: Long,
        nowEpochMillis: Long,
        sentinelCase: InferenceSemanticGoldenCase,
        casesOnCacheMiss: List<InferenceSemanticGoldenCase> = emptyList(),
        options: InferenceSemanticConformanceOptions = InferenceSemanticConformanceOptions(),
    ): VerifiedInferenceBackendAdmissionResult {
        val cacheKey = EvidenceKey(signature, calibrationProfileSha256, goldenSuiteSha256,
            adapterId, adapterVersion, softwareRevision, policyRevision, nowEpochMillis)
        return createInternal(manifest, modelBytes, cpuFactory, delegateFactory,
            casesOnCacheMiss, options, evidence, cacheKey, sentinelCase)
    }

    private fun createInternal(
        manifest: ModelManifest,
        modelBytes: ByteBuffer,
        cpuFactory: VerifiedInferenceBackendFactory,
        delegateFactory: VerifiedInferenceBackendFactory?,
        cases: List<InferenceSemanticGoldenCase>,
        options: InferenceSemanticConformanceOptions,
        evidence: PersistedInferenceAdmissionEvidence?,
        cacheKey: EvidenceKey?,
        sentinelCase: InferenceSemanticGoldenCase?,
    ): VerifiedInferenceBackendAdmissionResult {
        val identity = VerifiedInferenceIdentity.create(manifest, modelBytes)
        val artifact = modelBytes.slice().asReadOnlyBuffer()
        val cpu = try {
            cpuFactory.create(manifest, artifact.duplicate())
        } catch (error: Throwable) {
            if (error is Error) throw error
            throw IllegalStateException("CPU inference backend creation failed", error)
        }
        var cpuOwned = true
        try {
            require(cpu.manifest.exactIdentity() == identity.manifest) {
                "CPU factory returned a backend with mismatched manifest/task identity"
            }
            if (delegateFactory == null) return VerifiedInferenceBackendAdmissionResult(
                cpu, false, InferenceBackendAdmissionStatus.CPU_ONLY, null)
            val delegate = try {
                delegateFactory.create(manifest, artifact.duplicate())
            } catch (error: Throwable) {
                if (error is Error) throw error
                return VerifiedInferenceBackendAdmissionResult(cpu, false,
                    InferenceBackendAdmissionStatus.DELEGATE_CREATION_FAILED, null)
            }
            val exactEvidence = evidence?.takeIf { cached -> cacheKey != null && cached.matches(
                identity, cacheKey.signature, cacheKey.calibrationProfileSha256,
                cacheKey.goldenSuiteSha256, options, cacheKey.adapterId,
                cacheKey.adapterVersion, cacheKey.softwareRevision,
                cacheKey.policyRevision, cacheKey.nowEpochMillis) }
            if (exactEvidence != null) {
                cpuOwned = false
                val sentinel = InferenceBackendSemanticConformanceSelector.select(cpu, delegate,
                    identity, listOf(requireNotNull(sentinelCase)), options)
                return if (sentinel.usingDelegate)
                    VerifiedInferenceBackendAdmissionResult(sentinel.backend, true,
                        InferenceBackendAdmissionStatus.DELEGATE_CACHED_EVIDENCE_ACCEPTED,
                        sentinel.report, exactEvidence)
                else VerifiedInferenceBackendAdmissionResult(sentinel.backend, false,
                    InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_REJECTED,
                    sentinel.report)
            }
            cpuOwned = false
            val selection = InferenceBackendSemanticConformanceSelector.select(cpu, delegate,
                identity, cases, options)
            return VerifiedInferenceBackendAdmissionResult(selection.backend,
                selection.usingDelegate,
                if (selection.usingDelegate)
                    InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_ACCEPTED
                else InferenceBackendAdmissionStatus.DELEGATE_CONFORMANCE_REJECTED,
                selection.report)
        } catch (error: Throwable) {
            if (cpuOwned) closeQuietly(cpu)
            throw error
        }
    }

    private data class EvidenceKey(
        val signature: DeviceCapabilitySignature,
        val calibrationProfileSha256: String,
        val goldenSuiteSha256: String,
        val adapterId: String,
        val adapterVersion: String,
        val softwareRevision: String,
        val policyRevision: Long,
        val nowEpochMillis: Long,
    )

    private fun closeQuietly(backend: OnDeviceInferenceBackend) {
        try { backend.close() } catch (_: Throwable) { }
    }
}
