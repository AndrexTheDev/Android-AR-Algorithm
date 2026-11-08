package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Verified runtime-neutral backend for YOLOX, RT-DETR, and related raw row contracts. */
class AnchorFreeDetectionTensorBackend private constructor(
    override val manifest: ModelManifest,
    private val runtime: SingleInputTensorRuntime,
    private val outputSpec: RawDetectionTensorSpec,
    private val options: AnchorFreeDetectionOptions,
    private val dynamicClassIds: Set<Long>,
    private val projectionSurfaces: Map<Long, SemanticProjectionSurface>,
    private val swapRedBlue: Boolean,
) : OnDeviceInferenceBackend {
    private val closed = AtomicBoolean(false)
    private val input = allocateModelInput(manifest)
    private val outputBytes = rawTensorBytes(outputSpec)
    private val output = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())

    init {
        validateDetectionManifest(manifest, dynamicClassIds, projectionSurfaces)
    }

    @Synchronized
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed.get()) { "Anchor-free detection backend is closed" }
        val preprocessed = ArKernelTensorPreprocessor.preprocessInto(frame, manifest, input,
            swapRedBlue)
        val runtimeInput = preprocessed.bytes.duplicate().order(ByteOrder.nativeOrder()).apply {
            position(0)
        }
        val runtimeOutput = output.duplicate().order(ByteOrder.nativeOrder()).apply {
            clear(); limit(outputBytes)
        }
        val written = runtime.execute(runtimeInput, runtimeOutput)
        require(written == outputBytes) { "Tensor runtime returned $written bytes; expected $outputBytes" }
        val tensor = output.duplicate().order(ByteOrder.nativeOrder()).apply {
            position(0); limit(outputBytes)
        }
        val detections = decorate(ArKernelAnchorFreeDetectionDecoder.decodeForFrame(tensor,
            outputSpec, options, frame, manifest), dynamicClassIds, projectionSurfaces)
        return OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera,
            detections = detections)
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.close()
    }

    companion object {
        fun createVerified(
            manifest: ModelManifest,
            modelBytes: ByteBuffer,
            runtimeFactory: (ByteBuffer) -> SingleInputTensorRuntime,
            outputSpec: RawDetectionTensorSpec,
            options: AnchorFreeDetectionOptions,
            dynamicClassIds: Set<Long> = emptySet(),
            projectionSurfaces: Map<Long, SemanticProjectionSurface> = emptyMap(),
            swapRedBlue: Boolean = false,
        ): AnchorFreeDetectionTensorBackend {
            require(manifest.verifyModelBytes(modelBytes)) { "Model byte size or SHA-256 mismatch" }
            val runtime = runtimeFactory(modelBytes.duplicate())
            return try {
                AnchorFreeDetectionTensorBackend(manifest, runtime, outputSpec, options,
                    dynamicClassIds.toSet(), projectionSurfaces.toMap(), swapRedBlue)
            } catch (error: Exception) {
                closeAfterFailure(runtime, error)
                throw error
            }
        }
    }
}

/** Verified single-output backend for NanoDet/PicoDet-style DFL feature heads. */
class DistributionFocalDetectionTensorBackend private constructor(
    override val manifest: ModelManifest,
    private val runtime: SingleInputTensorRuntime,
    private val outputSpec: RawDetectionTensorSpec,
    private val options: DistributionFocalDetectionOptions,
    private val dynamicClassIds: Set<Long>,
    private val projectionSurfaces: Map<Long, SemanticProjectionSurface>,
    private val swapRedBlue: Boolean,
) : OnDeviceInferenceBackend {
    private val closed = AtomicBoolean(false)
    private val input = allocateModelInput(manifest)
    private val outputBytes = rawTensorBytes(outputSpec)
    private val output = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())

    init {
        validateDetectionManifest(manifest, dynamicClassIds, projectionSurfaces)
    }

    @Synchronized
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed.get()) { "DFL detection backend is closed" }
        val preprocessed = ArKernelTensorPreprocessor.preprocessInto(frame, manifest, input,
            swapRedBlue)
        val runtimeInput = preprocessed.bytes.duplicate().order(ByteOrder.nativeOrder()).apply {
            position(0)
        }
        val runtimeOutput = output.duplicate().order(ByteOrder.nativeOrder()).apply {
            clear(); limit(outputBytes)
        }
        val written = runtime.execute(runtimeInput, runtimeOutput)
        require(written == outputBytes) { "Tensor runtime returned $written bytes; expected $outputBytes" }
        val tensor = output.duplicate().order(ByteOrder.nativeOrder()).apply {
            position(0); limit(outputBytes)
        }
        val detections = decorate(ArKernelDistributionFocalDetectionDecoder.decodeForFrame(
            tensor, outputSpec, options, frame, manifest), dynamicClassIds,
            projectionSurfaces)
        return OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera,
            detections = detections)
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.close()
    }

    companion object {
        fun createVerified(
            manifest: ModelManifest,
            modelBytes: ByteBuffer,
            runtimeFactory: (ByteBuffer) -> SingleInputTensorRuntime,
            outputSpec: RawDetectionTensorSpec,
            options: DistributionFocalDetectionOptions,
            dynamicClassIds: Set<Long> = emptySet(),
            projectionSurfaces: Map<Long, SemanticProjectionSurface> = emptyMap(),
            swapRedBlue: Boolean = false,
        ): DistributionFocalDetectionTensorBackend {
            require(manifest.verifyModelBytes(modelBytes)) { "Model byte size or SHA-256 mismatch" }
            val runtime = runtimeFactory(modelBytes.duplicate())
            return try {
                DistributionFocalDetectionTensorBackend(manifest, runtime, outputSpec, options,
                    dynamicClassIds.toSet(), projectionSurfaces.toMap(), swapRedBlue)
            } catch (error: Exception) {
                closeAfterFailure(runtime, error)
                throw error
            }
        }
    }
}

/** Verified multi-output backend for separate class/regression DFL heads. */
class SeparatedDistributionFocalDetectionTensorBackend private constructor(
    override val manifest: ModelManifest,
    private val runtime: MultiOutputTensorRuntime,
    private val classSpecs: List<RawDetectionTensorSpec>,
    private val distributionSpecs: List<RawDetectionTensorSpec>,
    private val options: SeparatedDistributionFocalDetectionOptions,
    private val dynamicClassIds: Set<Long>,
    private val projectionSurfaces: Map<Long, SemanticProjectionSurface>,
    private val swapRedBlue: Boolean,
) : OnDeviceInferenceBackend {
    private val closed = AtomicBoolean(false)
    private val input = allocateModelInput(manifest)
    private val orderedSpecs = interleaveDflSpecs(classSpecs, distributionSpecs,
        options.strides.size)
    private val outputBytes = orderedSpecs.map(::rawTensorBytes)
    private val outputs = outputBytes.map { bytes ->
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    init {
        validateDetectionManifest(manifest, dynamicClassIds, projectionSurfaces)
        require(classSpecs.size == options.strides.size &&
            distributionSpecs.size == options.strides.size)
        require(outputBytes.sumOf { it.toLong() } <= MAX_TOTAL_OUTPUT_BYTES)
    }

    @Synchronized
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed.get()) { "Separated DFL detection backend is closed" }
        val preprocessed = ArKernelTensorPreprocessor.preprocessInto(frame, manifest, input,
            swapRedBlue)
        val runtimeInput = preprocessed.bytes.duplicate().order(ByteOrder.nativeOrder()).apply {
            position(0)
        }
        val runtimeOutputs = outputs.indices.map { index ->
            outputs[index].duplicate().order(ByteOrder.nativeOrder()).apply {
                clear(); limit(outputBytes[index])
            }
        }
        val written = runtime.execute(runtimeInput, runtimeOutputs)
        require(written.size == outputs.size && written.indices.all {
            written[it] == outputBytes[it]
        }) { "Tensor runtime returned incomplete or unexpected separated DFL outputs" }
        val readable = outputs.indices.map { index ->
            outputs[index].duplicate().order(ByteOrder.nativeOrder()).apply {
                position(0); limit(outputBytes[index])
            }
        }
        val levels = classSpecs.indices.map { index ->
            DistributionFocalTensorLevel(readable[index * 2], classSpecs[index],
                readable[index * 2 + 1], distributionSpecs[index])
        }
        val detections = decorate(
            ArKernelSeparatedDistributionFocalDetectionDecoder.decodeForFrame(levels,
                options, frame, manifest), dynamicClassIds, projectionSurfaces)
        return OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera,
            detections = detections)
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.close()
    }

    companion object {
        fun createVerified(
            manifest: ModelManifest,
            modelBytes: ByteBuffer,
            runtimeFactory: (ByteBuffer) -> MultiOutputTensorRuntime,
            classSpecs: List<RawDetectionTensorSpec>,
            distributionSpecs: List<RawDetectionTensorSpec>,
            options: SeparatedDistributionFocalDetectionOptions,
            dynamicClassIds: Set<Long> = emptySet(),
            projectionSurfaces: Map<Long, SemanticProjectionSurface> = emptyMap(),
            swapRedBlue: Boolean = false,
        ): SeparatedDistributionFocalDetectionTensorBackend {
            require(manifest.verifyModelBytes(modelBytes)) { "Model byte size or SHA-256 mismatch" }
            val runtime = runtimeFactory(modelBytes.duplicate())
            return try {
                SeparatedDistributionFocalDetectionTensorBackend(manifest, runtime,
                    classSpecs.toList(), distributionSpecs.toList(), options,
                    dynamicClassIds.toSet(), projectionSurfaces.toMap(), swapRedBlue)
            } catch (error: Exception) {
                closeAfterFailure(runtime, error)
                throw error
            }
        }
    }
}

/** Verified four-head SSD/EfficientDet Detection_PostProcess backend. */
class SsdDetectionTensorBackend private constructor(
    override val manifest: ModelManifest,
    private val runtime: MultiOutputTensorRuntime,
    private val boxSpec: RawDetectionTensorSpec,
    private val classSpec: RawDetectionTensorSpec,
    private val scoreSpec: RawDetectionTensorSpec,
    private val countSpec: RawDetectionTensorSpec,
    private val options: SsdDetectionOptions,
    private val dynamicClassIds: Set<Long>,
    private val projectionSurfaces: Map<Long, SemanticProjectionSurface>,
    private val swapRedBlue: Boolean,
) : OnDeviceInferenceBackend {
    private val closed = AtomicBoolean(false)
    private val input = allocateModelInput(manifest)
    private val specs = listOf(boxSpec, classSpec, scoreSpec, countSpec)
    private val outputBytes = specs.map(::rawTensorBytes)
    private val outputs = outputBytes.map { ByteBuffer.allocateDirect(it).order(ByteOrder.nativeOrder()) }

    init {
        validateDetectionManifest(manifest, dynamicClassIds, projectionSurfaces)
        require(boxSpec.columns >= 4 && classSpec.columns >= 1 && scoreSpec.columns >= 1)
        require(boxSpec.rows == classSpec.rows && boxSpec.rows == scoreSpec.rows)
        require(countSpec.rows == 1 && countSpec.columns == 1)
        require(outputBytes.sumOf { it.toLong() } <= MAX_TOTAL_OUTPUT_BYTES)
    }

    @Synchronized
    override fun infer(frame: PerceptionFrame): OnDeviceInferenceOutput {
        check(!closed.get()) { "SSD detection backend is closed" }
        val preprocessed = ArKernelTensorPreprocessor.preprocessInto(frame, manifest, input,
            swapRedBlue)
        val runtimeInput = preprocessed.bytes.duplicate().order(ByteOrder.nativeOrder()).apply {
            position(0)
        }
        val runtimeOutputs = outputs.zip(outputBytes).map { (buffer, bytes) ->
            buffer.duplicate().order(ByteOrder.nativeOrder()).apply { clear(); limit(bytes) }
        }
        val written = runtime.execute(runtimeInput, runtimeOutputs)
        require(written.size == outputs.size && written.indices.all { written[it] == outputBytes[it] }) {
            "Tensor runtime returned incomplete or unexpected SSD outputs"
        }
        val readable = outputs.zip(outputBytes).map { (buffer, bytes) ->
            buffer.duplicate().order(ByteOrder.nativeOrder()).apply { position(0); limit(bytes) }
        }
        val count = ArKernelSsdDetectionDecoder.decodeDetectionCount(readable[3], countSpec)
        val detections = ArKernelSsdDetectionDecoder.decode(readable[0], boxSpec, readable[1],
            classSpec, readable[2], scoreSpec, count, options, frame.calibration.width,
            frame.calibration.height)
        require(detections.all { manifest.labels[it.classId] != null }) {
            "SSD output contains a class absent from the model manifest"
        }
        return OnDeviceInferenceOutput(frame.timestampNs, frame.worldFromCamera,
            detections = decorate(detections, dynamicClassIds, projectionSurfaces))
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.close()
    }

    companion object {
        fun createVerified(
            manifest: ModelManifest,
            modelBytes: ByteBuffer,
            runtimeFactory: (ByteBuffer) -> MultiOutputTensorRuntime,
            boxSpec: RawDetectionTensorSpec,
            classSpec: RawDetectionTensorSpec,
            scoreSpec: RawDetectionTensorSpec,
            countSpec: RawDetectionTensorSpec = RawDetectionTensorSpec(1, 1),
            options: SsdDetectionOptions,
            dynamicClassIds: Set<Long> = emptySet(),
            projectionSurfaces: Map<Long, SemanticProjectionSurface> = emptyMap(),
            swapRedBlue: Boolean = false,
        ): SsdDetectionTensorBackend {
            require(manifest.verifyModelBytes(modelBytes)) { "Model byte size or SHA-256 mismatch" }
            val runtime = runtimeFactory(modelBytes.duplicate())
            return try {
                SsdDetectionTensorBackend(manifest, runtime, boxSpec, classSpec, scoreSpec,
                    countSpec, options, dynamicClassIds.toSet(), projectionSurfaces.toMap(),
                    swapRedBlue)
            } catch (error: Exception) {
                closeAfterFailure(runtime, error)
                throw error
            }
        }
    }
}

private const val MAX_BACKEND_TENSOR_BYTES = 64 * 1024 * 1024
private const val MAX_TOTAL_OUTPUT_BYTES = 96L * 1024 * 1024

private fun allocateModelInput(manifest: ModelManifest): ByteBuffer {
    require(manifest.quantization != TensorQuantization.FLOAT16)
    require(manifest.inputChannels == 1 || manifest.inputChannels == 3)
    val elements = Math.multiplyExact(Math.multiplyExact(manifest.inputWidth.toLong(),
        manifest.inputHeight.toLong()), manifest.inputChannels.toLong())
    val bytes = Math.multiplyExact(elements,
        if (manifest.quantization == TensorQuantization.FLOAT32) 4L else 1L)
    require(bytes in 1..MAX_BACKEND_TENSOR_BYTES.toLong())
    return ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())
}

private fun rawTensorBytes(spec: RawDetectionTensorSpec): Int {
    val elements = Math.multiplyExact(spec.rowStrideElements.toLong(), spec.rows.toLong())
    val bytes = Math.multiplyExact(elements, spec.encoding.bytesPerElement.toLong())
    require(bytes in 1..MAX_BACKEND_TENSOR_BYTES.toLong())
    return bytes.toInt()
}

private fun interleaveDflSpecs(classSpecs: List<RawDetectionTensorSpec>,
    distributionSpecs: List<RawDetectionTensorSpec>, expectedLevels: Int): List<RawDetectionTensorSpec> {
    require(classSpecs.size == expectedLevels && distributionSpecs.size == expectedLevels)
    return classSpecs.indices.flatMap { index ->
        listOf(classSpecs[index], distributionSpecs[index])
    }
}

private fun validateDetectionManifest(manifest: ModelManifest, dynamicClassIds: Set<Long>,
    projectionSurfaces: Map<Long, SemanticProjectionSurface>) {
    require(InferenceTask.SEMANTIC_DETECTION in manifest.tasks)
    require(dynamicClassIds.all { manifest.labels[it] != null })
    require(projectionSurfaces.keys.all { manifest.labels[it] != null })
}

private fun decorate(detections: List<SemanticDetection2d>, dynamicClassIds: Set<Long>,
    projectionSurfaces: Map<Long, SemanticProjectionSurface>) = detections.map { detection ->
    detection.copy(dynamic = detection.classId in dynamicClassIds,
        projectionSurface = projectionSurfaces[detection.classId] ?: SemanticProjectionSurface.ANY)
}

private fun closeAfterFailure(runtime: AutoCloseable, error: Exception) {
    try {
        runtime.close()
    } catch (closeError: Exception) {
        error.addSuppressed(closeError)
    }
}
