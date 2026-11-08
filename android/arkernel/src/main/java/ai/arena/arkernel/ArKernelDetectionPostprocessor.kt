package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DetectionTensorEncoding(val bytesPerElement: Int) { FLOAT32(4), INT8(1), UINT8(1) }
enum class DetectionBoxFormat { XYXY, CENTER_XYWH }

data class DetectionTensorSpec(
    val rows: Int,
    val columns: Int,
    val rowStrideElements: Int = columns,
    val encoding: DetectionTensorEncoding = DetectionTensorEncoding.FLOAT32,
    val quantizationScale: Double = 1.0,
    val quantizationZeroPoint: Int = 0,
) {
    init {
        require(rows in 1..4096 && columns > 0 && rowStrideElements >= columns)
        if (encoding != DetectionTensorEncoding.FLOAT32) {
            require(quantizationScale.isFinite() && quantizationScale > 0.0)
            if (encoding == DetectionTensorEncoding.INT8)
                require(quantizationZeroPoint in -128..127)
            else require(quantizationZeroPoint in 0..255)
        }
    }
}

data class DetectionPostprocessOptions(
    val x0Column: Int = 0,
    val y0Column: Int = 1,
    val x1OrWidthColumn: Int = 2,
    val y1OrHeightColumn: Int = 3,
    val scoreColumn: Int = 4,
    val classColumn: Int = 5,
    val boxFormat: DetectionBoxFormat = DetectionBoxFormat.XYXY,
    val normalizedCoordinates: Boolean = true,
    val classAwareNms: Boolean = true,
    val scoreThreshold: Double = 0.25,
    val iouThreshold: Double = 0.45,
    val maxDetections: Int = 256,
) {
    init {
        require(scoreThreshold.isFinite() && scoreThreshold in 0.0..1.0)
        require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0)
        require(maxDetections in 1..256)
    }
}

/** Decodes explicit row-based detection tensors and applies deterministic bounded NMS. */
object ArKernelDetectionPostprocessor {
    private const val RESULT_OK = 0
    private const val RESULT_INVALID_ARGUMENT = 1

    init { System.loadLibrary("arkernel_jni") }

    fun decodeForFrame(
        tensor: ByteBuffer,
        spec: DetectionTensorSpec,
        frame: PerceptionFrame,
        manifest: ModelManifest,
        options: DetectionPostprocessOptions = DetectionPostprocessOptions(),
    ): List<SemanticDetection2d> {
        require(InferenceTask.SEMANTIC_DETECTION in manifest.tasks)
        val detections = decode(tensor, spec, frame.calibration.width,
            frame.calibration.height, options)
        require(detections.all { manifest.labels[it.classId] != null }) {
            "Detection tensor contains a class absent from the model manifest"
        }
        return detections
    }

    fun decode(
        tensor: ByteBuffer,
        spec: DetectionTensorSpec,
        imageWidth: Int,
        imageHeight: Int,
        options: DetectionPostprocessOptions = DetectionPostprocessOptions(),
    ): List<SemanticDetection2d> {
        require(tensor.isDirect && tensor.order() == ByteOrder.nativeOrder())
        require(imageWidth > 0 && imageHeight > 0)
        val columns = intArrayOf(options.x0Column, options.y0Column,
            options.x1OrWidthColumn, options.y1OrHeightColumn,
            options.scoreColumn, options.classColumn)
        require(columns.all { it in 0 until spec.columns })
        val elements = (spec.rows.toLong() - 1L) * spec.rowStrideElements + spec.columns
        val requiredBytes = elements * spec.encoding.bytesPerElement
        require(requiredBytes <= tensor.remaining().toLong()) { "Detection tensor is truncated" }
        val classIds = LongArray(options.maxDetections)
        val values = DoubleArray(options.maxDetections * 5)
        val metadata = LongArray(1)
        val status = nativeDecode(tensor, tensor.position(), tensor.remaining(), spec.rows,
            spec.columns, spec.rowStrideElements, spec.encoding.ordinal,
            spec.quantizationScale, spec.quantizationZeroPoint, imageWidth, imageHeight,
            options.x0Column, options.y0Column, options.x1OrWidthColumn,
            options.y1OrHeightColumn, options.scoreColumn, options.classColumn,
            options.boxFormat.ordinal, options.normalizedCoordinates, options.classAwareNms,
            options.scoreThreshold, options.iouThreshold, options.maxDetections,
            classIds, values, metadata)
        require(status != RESULT_INVALID_ARGUMENT) { "Invalid detection tensor or configuration" }
        check(status == RESULT_OK) { "Native detection postprocessing failed: $status" }
        return List(metadata[0].toInt()) { index ->
            val offset = index * 5
            val left = values[offset + 1]
            val right = values[offset + 3]
            val bottom = values[offset + 4]
            SemanticDetection2d(classIds[index], confidence = values[offset],
                left = left, top = values[offset + 2], right = right, bottom = bottom,
                anchorPixelX = (left + right) * 0.5,
                anchorPixelY = minOf(bottom, Math.nextDown(imageHeight.toDouble())))
        }
    }

    private external fun nativeDecode(
        tensor: ByteBuffer,
        tensorOffsetBytes: Int,
        tensorSizeBytes: Int,
        rows: Int,
        columns: Int,
        rowStrideElements: Int,
        elementType: Int,
        quantizationScale: Double,
        quantizationZeroPoint: Int,
        imageWidth: Int,
        imageHeight: Int,
        x0Column: Int,
        y0Column: Int,
        x1OrWidthColumn: Int,
        y1OrHeightColumn: Int,
        scoreColumn: Int,
        classColumn: Int,
        boxFormat: Int,
        normalizedCoordinates: Boolean,
        classAwareNms: Boolean,
        scoreThreshold: Double,
        iouThreshold: Double,
        maxDetections: Int,
        classIds: LongArray,
        confidenceAndBoxes: DoubleArray,
        metadata: LongArray,
    ): Int
}
