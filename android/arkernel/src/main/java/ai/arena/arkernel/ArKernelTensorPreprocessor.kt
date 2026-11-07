package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PreprocessedTensorBuffer(
    val width: Int,
    val height: Int,
    val channels: Int,
    val quantization: TensorQuantization,
    val elementCount: Long,
    val bytes: ByteBuffer,
)

/** Native, deterministic preprocessing shared by inference backend adapters. */
object ArKernelTensorPreprocessor {
    private const val RESULT_OK = 0
    private const val RESULT_INVALID_ARGUMENT = 1
    private const val MAX_OUTPUT_ELEMENTS = 16_000_000L

    init { System.loadLibrary("arkernel_jni") }

    fun allocateAndPreprocess(
        frame: PerceptionFrame,
        manifest: ModelManifest,
        swapRedBlue: Boolean = false,
    ): PreprocessedTensorBuffer {
        require(manifest.inputChannels == 1 || manifest.inputChannels == 3) {
            "Native shared preprocessor supports one or three output channels"
        }
        val bytesPerElement = when (manifest.quantization) {
            TensorQuantization.FLOAT32 -> 4
            TensorQuantization.INT8, TensorQuantization.UINT8 -> 1
            TensorQuantization.FLOAT16 -> throw IllegalArgumentException(
                "Native shared preprocessor does not encode FLOAT16")
        }
        val elementCount = manifest.inputWidth.toLong() * manifest.inputHeight *
            manifest.inputChannels
        require(elementCount in 1L..MAX_OUTPUT_ELEMENTS)
        val requiredBytes = elementCount * bytesPerElement
        require(requiredBytes <= Int.MAX_VALUE)
        val output = ByteBuffer.allocateDirect(requiredBytes.toInt()).order(ByteOrder.nativeOrder())
        return preprocessInto(frame, manifest, output, swapRedBlue)
    }

    /** Writes from output.position() and leaves both input/output positions unchanged. */
    fun preprocessInto(
        frame: PerceptionFrame,
        manifest: ModelManifest,
        output: ByteBuffer,
        swapRedBlue: Boolean = false,
    ): PreprocessedTensorBuffer {
        require(output.isDirect && !output.isReadOnly)
        require(output.order() == ByteOrder.nativeOrder())
        require(manifest.inputChannels == 1 || manifest.inputChannels == 3) {
            "Native shared preprocessor supports one or three output channels"
        }
        val nativeElementType = when (manifest.quantization) {
            TensorQuantization.FLOAT32 -> 0
            TensorQuantization.INT8 -> 1
            TensorQuantization.UINT8 -> 2
            TensorQuantization.FLOAT16 -> throw IllegalArgumentException(
                "Native shared preprocessor does not encode FLOAT16")
        }
        val metadata = LongArray(5)
        val status = nativePreprocess(frame.image, frame.image.position(), frame.image.remaining(),
            frame.calibration.width, frame.calibration.height, frame.rowStrideBytes,
            frame.format.ordinal, manifest.inputWidth, manifest.inputHeight,
            manifest.inputChannels, manifest.inputMean.toDoubleArray(),
            manifest.inputStd.toDoubleArray(), nativeElementType,
            manifest.quantizationScale ?: 1.0, manifest.quantizationZeroPoint ?: 0,
            swapRedBlue, MAX_OUTPUT_ELEMENTS, output, output.position(), output.remaining(), metadata)
        require(status != RESULT_INVALID_ARGUMENT) {
            "Invalid image, manifest preprocessing parameters, or output capacity"
        }
        check(status == RESULT_OK) { "Native tensor preprocessing failed: $status" }
        val view = output.duplicate().order(ByteOrder.nativeOrder())
        view.limit(output.position() + metadata[4].toInt())
        view.position(output.position())
        return PreprocessedTensorBuffer(metadata[0].toInt(), metadata[1].toInt(),
            metadata[2].toInt(), manifest.quantization, metadata[3], view.slice().order(ByteOrder.nativeOrder()))
    }

    private external fun nativePreprocess(
        image: ByteBuffer,
        imageOffsetBytes: Int,
        imageSizeBytes: Int,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        imageFormat: Int,
        outputWidth: Int,
        outputHeight: Int,
        outputChannels: Int,
        mean: DoubleArray,
        standardDeviation: DoubleArray,
        elementType: Int,
        quantizationScale: Double,
        quantizationZeroPoint: Int,
        swapRedBlue: Boolean,
        maxOutputElements: Long,
        output: ByteBuffer,
        outputOffsetBytes: Int,
        outputCapacityBytes: Int,
        metadata: LongArray,
    ): Int
}
