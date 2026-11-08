package ai.arena.arkernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

private const val MAX_RAW_DETECTION_ROWS = 32_768
private const val MAX_RAW_DETECTION_COLUMNS = 2_048
private const val MAX_CLASS_SCORE_VALUES = 8_000_000L

enum class DetectionScoreActivation { PROBABILITY, SIGMOID, SOFTMAX }
enum class DetectionCoordinateSpace { NORMALIZED, MODEL_PIXELS }
enum class SsdBoxOrder { YXYX, XYXY }

data class RawDetectionTensorSpec(
    val rows: Int,
    val columns: Int,
    val rowStrideElements: Int = columns,
    val encoding: DetectionTensorEncoding = DetectionTensorEncoding.FLOAT32,
    val quantizationScale: Double = 1.0,
    val quantizationZeroPoint: Int = 0,
) {
    init {
        require(rows in 1..MAX_RAW_DETECTION_ROWS && columns in 1..MAX_RAW_DETECTION_COLUMNS)
        require(rowStrideElements >= columns)
        if (encoding != DetectionTensorEncoding.FLOAT32) {
            require(quantizationScale.isFinite() && quantizationScale > 0.0)
            require(quantizationZeroPoint in if (encoding == DetectionTensorEncoding.INT8)
                -128..127 else 0..255)
        }
    }
}

data class AnchorFreeDetectionOptions(
    val xColumn: Int = 0,
    val yColumn: Int = 1,
    val widthOrRightColumn: Int = 2,
    val heightOrBottomColumn: Int = 3,
    val boxFormat: DetectionBoxFormat = DetectionBoxFormat.CENTER_XYWH,
    val objectnessColumn: Int? = 4,
    val firstClassColumn: Int = 5,
    val classCount: Int,
    val objectnessActivation: DetectionScoreActivation = DetectionScoreActivation.PROBABILITY,
    val classActivation: DetectionScoreActivation = DetectionScoreActivation.PROBABILITY,
    val coordinateSpace: DetectionCoordinateSpace = DetectionCoordinateSpace.MODEL_PIXELS,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
    val classIdOffset: Long = 1,
    val scoreThreshold: Double = 0.25,
    val iouThreshold: Double = 0.45,
    val classAwareNms: Boolean = true,
    val maxPreNmsCandidates: Int = 4_096,
    val maxDetections: Int = 256,
) {
    init {
        require(classCount in 1..1_024 && modelInputWidth > 0 && modelInputHeight > 0)
        require(classIdOffset >= 0 && classIdOffset + classCount <= 0x1_0000_0000L)
        require(objectnessActivation != DetectionScoreActivation.SOFTMAX)
        require(scoreThreshold.isFinite() && scoreThreshold in 0.0..1.0)
        require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0)
        require(maxPreNmsCandidates in 1..4_096 && maxDetections in 1..256)
    }
}

class DistributionFocalDetectionOptions(
    val featureStrides: IntArray,
    val classCount: Int,
    val regressionMax: Int,
    val firstClassColumn: Int = 0,
    val firstDistributionColumn: Int = firstClassColumn + classCount,
    val classActivation: DetectionScoreActivation = DetectionScoreActivation.SIGMOID,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
    val classIdOffset: Long = 1,
    val scoreThreshold: Double = 0.25,
    val iouThreshold: Double = 0.45,
    val classAwareNms: Boolean = true,
    val maxPreNmsCandidates: Int = 4_096,
    val maxDetections: Int = 256,
) {
    val strides: IntArray = featureStrides.copyOf()

    init {
        require(strides.isNotEmpty() && strides.size <= 8 &&
            strides.all { it in 1..512 } && strides.toSet().size == strides.size)
        require(strides.zipWithNext().all { (a, b) -> a < b })
        require(classCount in 1..1_024 && regressionMax in 1..32)
        require(classActivation != DetectionScoreActivation.SOFTMAX) {
            "Independent DFL class heads use probability or sigmoid activation"
        }
        require(modelInputWidth in 1..16_384 && modelInputHeight in 1..16_384)
        require(classIdOffset >= 0 && classIdOffset + classCount <= 0x1_0000_0000L)
        require(scoreThreshold.isFinite() && scoreThreshold in 0.0..1.0)
        require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0)
        require(maxPreNmsCandidates in 1..4_096 && maxDetections in 1..256)
    }
}

data class SsdDetectionOptions(
    val boxOrder: SsdBoxOrder = SsdBoxOrder.YXYX,
    val normalizedCoordinates: Boolean = true,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
    val classIdOffset: Long = 1,
    val scoreThreshold: Double = 0.25,
    val iouThreshold: Double = 0.45,
    val classAwareNms: Boolean = true,
    val maxDetections: Int = 256,
) {
    init {
        require(modelInputWidth > 0 && modelInputHeight > 0 && classIdOffset >= 0)
        require(scoreThreshold.isFinite() && scoreThreshold in 0.0..1.0)
        require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0)
        require(maxDetections in 1..256)
    }
}

private data class DetectionCandidate(
    val classId: Long,
    val score: Double,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val sourceIndex: Int,
)

private class DetectionTensorReader(
    private val tensor: ByteBuffer,
    private val spec: RawDetectionTensorSpec,
) {
    private val start = tensor.position()

    init {
        require(tensor.isDirect && tensor.order() == ByteOrder.nativeOrder())
        val elements = Math.addExact(Math.multiplyExact((spec.rows - 1).toLong(),
            spec.rowStrideElements.toLong()), spec.columns.toLong())
        val required = Math.multiplyExact(elements, spec.encoding.bytesPerElement.toLong())
        require(required <= tensor.remaining().toLong()) { "Detection tensor is truncated" }
    }

    fun get(row: Int, column: Int): Double {
        val element = row.toLong() * spec.rowStrideElements + column
        val byteOffset = Math.toIntExact(element * spec.encoding.bytesPerElement + start)
        return when (spec.encoding) {
            DetectionTensorEncoding.FLOAT32 -> tensor.getFloat(byteOffset).toDouble()
            DetectionTensorEncoding.INT8 ->
                (tensor.get(byteOffset).toInt() - spec.quantizationZeroPoint) * spec.quantizationScale
            DetectionTensorEncoding.UINT8 ->
                ((tensor.get(byteOffset).toInt() and 0xff) - spec.quantizationZeroPoint) *
                    spec.quantizationScale
        }
    }
}

object ArKernelAnchorFreeDetectionDecoder {
    fun decodeForFrame(
        tensor: ByteBuffer,
        spec: RawDetectionTensorSpec,
        options: AnchorFreeDetectionOptions,
        frame: PerceptionFrame,
        manifest: ModelManifest,
    ): List<SemanticDetection2d> {
        require(InferenceTask.SEMANTIC_DETECTION in manifest.tasks)
        val result = decode(tensor, spec, options, frame.calibration.width, frame.calibration.height)
        require(result.all { manifest.labels[it.classId] != null }) {
            "Detection tensor contains a class absent from the model manifest"
        }
        return result
    }

    fun decode(
        tensor: ByteBuffer,
        spec: RawDetectionTensorSpec,
        options: AnchorFreeDetectionOptions,
        imageWidth: Int,
        imageHeight: Int,
    ): List<SemanticDetection2d> {
        require(imageWidth > 0 && imageHeight > 0)
        val requiredColumns = listOf(options.xColumn, options.yColumn,
            options.widthOrRightColumn, options.heightOrBottomColumn,
            options.firstClassColumn, options.firstClassColumn + options.classCount - 1) +
            listOfNotNull(options.objectnessColumn)
        require(requiredColumns.all { it in 0 until spec.columns })
        require(spec.rows.toLong() * options.classCount <= MAX_CLASS_SCORE_VALUES)
        val reader = DetectionTensorReader(tensor, spec)
        val candidates = ArrayList<DetectionCandidate>(minOf(spec.rows,
            options.maxPreNmsCandidates))
        for (row in 0 until spec.rows) {
            val objectness = options.objectnessColumn?.let {
                activate(reader.get(row, it), options.objectnessActivation)
            } ?: 1.0
            if (!objectness.isFinite() || objectness <= 0.0) continue
            val classResult = bestClass(reader, row, options)
            val score = objectness * classResult.second
            if (!score.isFinite() || score < options.scoreThreshold || score > 1.0) continue
            val box = mapBox(reader.get(row, options.xColumn), reader.get(row, options.yColumn),
                reader.get(row, options.widthOrRightColumn),
                reader.get(row, options.heightOrBottomColumn), options.boxFormat,
                options.coordinateSpace, options.modelInputWidth, options.modelInputHeight,
                imageWidth, imageHeight) ?: continue
            candidates += DetectionCandidate(options.classIdOffset + classResult.first,
                score, box[0], box[1], box[2], box[3], row)
        }
        return finish(candidates, options.iouThreshold, options.classAwareNms,
            options.maxPreNmsCandidates, options.maxDetections, imageHeight)
    }

    private fun bestClass(reader: DetectionTensorReader, row: Int,
        options: AnchorFreeDetectionOptions): Pair<Int, Double> {
        if (options.classActivation == DetectionScoreActivation.SOFTMAX) {
            var maximum = Double.NEGATIVE_INFINITY
            for (index in 0 until options.classCount)
                maximum = maxOf(maximum, reader.get(row, options.firstClassColumn + index))
            if (!maximum.isFinite()) return 0 to Double.NaN
            var sum = 0.0
            var bestValue = Double.NEGATIVE_INFINITY
            var bestIndex = 0
            for (index in 0 until options.classCount) {
                val value = exp((reader.get(row, options.firstClassColumn + index) - maximum)
                    .coerceIn(-80.0, 0.0))
                sum += value
                if (value > bestValue) { bestValue = value; bestIndex = index }
            }
            return bestIndex to if (sum > 0.0) bestValue / sum else Double.NaN
        }
        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (index in 0 until options.classCount) {
            val score = activate(reader.get(row, options.firstClassColumn + index),
                options.classActivation)
            if (score > bestScore) { bestScore = score; bestIndex = index }
        }
        return bestIndex to bestScore
    }
}

object ArKernelDistributionFocalDetectionDecoder {
    fun decodeForFrame(
        tensor: ByteBuffer,
        spec: RawDetectionTensorSpec,
        options: DistributionFocalDetectionOptions,
        frame: PerceptionFrame,
        manifest: ModelManifest,
    ): List<SemanticDetection2d> {
        require(InferenceTask.SEMANTIC_DETECTION in manifest.tasks)
        val detections = decode(tensor, spec, options, frame.calibration.width,
            frame.calibration.height)
        require(detections.all { manifest.labels[it.classId] != null }) {
            "DFL output contains a class absent from the model manifest"
        }
        return detections
    }

    fun decode(
        tensor: ByteBuffer,
        spec: RawDetectionTensorSpec,
        options: DistributionFocalDetectionOptions,
        imageWidth: Int,
        imageHeight: Int,
    ): List<SemanticDetection2d> {
        require(imageWidth > 0 && imageHeight > 0)
        val bins = options.regressionMax + 1
        val finalClassColumn = options.firstClassColumn + options.classCount - 1
        val finalDistributionColumn = options.firstDistributionColumn + 4 * bins - 1
        require(options.firstClassColumn >= 0 && options.firstDistributionColumn >= 0)
        val classColumns = options.firstClassColumn..finalClassColumn
        val distributionColumns = options.firstDistributionColumn..finalDistributionColumn
        require(classColumns.last < distributionColumns.first ||
            distributionColumns.last < classColumns.first) { "DFL class and regression columns overlap" }
        require(spec.columns == maxOf(finalClassColumn, finalDistributionColumn) + 1) {
            "DFL column count does not exactly match the configured output contract"
        }
        require(spec.rows.toLong() * (options.classCount + 4L * bins) <=
            MAX_CLASS_SCORE_VALUES)
        val levelRows = options.strides.map { stride ->
            val columns = (options.modelInputWidth + stride - 1) / stride
            val rows = (options.modelInputHeight + stride - 1) / stride
            Math.multiplyExact(columns, rows)
        }
        require(levelRows.sumOf { it.toLong() } == spec.rows.toLong()) {
            "DFL rows do not match configured feature-map strides"
        }
        val reader = DetectionTensorReader(tensor, spec)
        val candidates = ArrayList<DetectionCandidate>(minOf(spec.rows,
            options.maxPreNmsCandidates))
        var globalRow = 0
        for (level in options.strides.indices) {
            val stride = options.strides[level]
            val featureWidth = (options.modelInputWidth + stride - 1) / stride
            repeat(levelRows[level]) { levelRow ->
                val classResult = bestIndependentClass(reader, globalRow,
                    options.firstClassColumn, options.classCount, options.classActivation)
                if (classResult.second.isFinite() &&
                    classResult.second >= options.scoreThreshold) {
                    val centerX = (levelRow % featureWidth + 0.5) * stride
                    val centerY = (levelRow / featureWidth + 0.5) * stride
                    val distances = DoubleArray(4) { side ->
                        distributionExpectation(reader, globalRow,
                            options.firstDistributionColumn + side * bins, bins) * stride
                    }
                    val box = mapBox(centerX - distances[0], centerY - distances[1],
                        centerX + distances[2], centerY + distances[3],
                        DetectionBoxFormat.XYXY, DetectionCoordinateSpace.MODEL_PIXELS,
                        options.modelInputWidth, options.modelInputHeight, imageWidth,
                        imageHeight)
                    if (box != null) candidates += DetectionCandidate(
                        options.classIdOffset + classResult.first, classResult.second,
                        box[0], box[1], box[2], box[3], globalRow)
                }
                globalRow++
            }
        }
        return finish(candidates, options.iouThreshold, options.classAwareNms,
            options.maxPreNmsCandidates, options.maxDetections, imageHeight)
    }

    private fun distributionExpectation(reader: DetectionTensorReader, row: Int,
        firstColumn: Int, bins: Int): Double {
        var maximum = Double.NEGATIVE_INFINITY
        for (bin in 0 until bins) maximum = maxOf(maximum, reader.get(row, firstColumn + bin))
        if (!maximum.isFinite()) return Double.NaN
        var weighted = 0.0
        var sum = 0.0
        for (bin in 0 until bins) {
            val probability = exp((reader.get(row, firstColumn + bin) - maximum)
                .coerceIn(-80.0, 0.0))
            weighted += bin * probability
            sum += probability
        }
        return if (sum > 0.0) weighted / sum else Double.NaN
    }
}

object ArKernelSsdDetectionDecoder {
    fun decodeDetectionCount(tensor: ByteBuffer, spec: RawDetectionTensorSpec): Int {
        require(spec.rows == 1 && spec.columns == 1)
        val raw = DetectionTensorReader(tensor, spec).get(0, 0)
        require(raw.isFinite() && raw >= 0.0)
        val rounded = kotlin.math.round(raw)
        require(kotlin.math.abs(raw - rounded) <= 1e-4 && rounded <= 4_096.0)
        return rounded.toInt()
    }

    fun decode(
        boxes: ByteBuffer,
        boxSpec: RawDetectionTensorSpec,
        classes: ByteBuffer,
        classSpec: RawDetectionTensorSpec,
        scores: ByteBuffer,
        scoreSpec: RawDetectionTensorSpec,
        detectionCount: Int,
        options: SsdDetectionOptions,
        imageWidth: Int,
        imageHeight: Int,
    ): List<SemanticDetection2d> {
        require(imageWidth > 0 && imageHeight > 0 && boxSpec.columns >= 4)
        require(classSpec.columns >= 1 && scoreSpec.columns >= 1)
        val count = detectionCount.coerceAtMost(minOf(boxSpec.rows, classSpec.rows, scoreSpec.rows))
        require(count in 0..4_096)
        val boxReader = DetectionTensorReader(boxes, boxSpec)
        val classReader = DetectionTensorReader(classes, classSpec)
        val scoreReader = DetectionTensorReader(scores, scoreSpec)
        val candidates = ArrayList<DetectionCandidate>(count)
        for (row in 0 until count) {
            val score = scoreReader.get(row, 0)
            val rawClass = classReader.get(row, 0)
            if (!score.isFinite() || score !in options.scoreThreshold..1.0 ||
                !rawClass.isFinite() || rawClass < 0.0) continue
            val roundedClass = kotlin.math.round(rawClass)
            if (kotlin.math.abs(rawClass - roundedClass) > 1e-4) continue
            val values = DoubleArray(4) { boxReader.get(row, it) }
            val ordered = if (options.boxOrder == SsdBoxOrder.YXYX)
                doubleArrayOf(values[1], values[0], values[3], values[2]) else values
            val box = mapBox(ordered[0], ordered[1], ordered[2], ordered[3],
                DetectionBoxFormat.XYXY,
                if (options.normalizedCoordinates) DetectionCoordinateSpace.NORMALIZED else
                    DetectionCoordinateSpace.MODEL_PIXELS,
                options.modelInputWidth, options.modelInputHeight, imageWidth, imageHeight) ?: continue
            candidates += DetectionCandidate(options.classIdOffset + roundedClass.toLong(), score,
                box[0], box[1], box[2], box[3], row)
        }
        return finish(candidates, options.iouThreshold, options.classAwareNms,
            4_096, options.maxDetections, imageHeight)
    }
}

private fun bestIndependentClass(reader: DetectionTensorReader, row: Int,
    firstColumn: Int, classCount: Int,
    activation: DetectionScoreActivation): Pair<Int, Double> {
    var bestIndex = 0
    var bestScore = Double.NEGATIVE_INFINITY
    for (index in 0 until classCount) {
        val score = activate(reader.get(row, firstColumn + index), activation)
        if (score > bestScore) { bestScore = score; bestIndex = index }
    }
    return bestIndex to bestScore
}

private fun activate(value: Double, activation: DetectionScoreActivation): Double = when (activation) {
    DetectionScoreActivation.PROBABILITY -> if (value in 0.0..1.0) value else Double.NaN
    DetectionScoreActivation.SIGMOID -> if (value.isFinite())
        1.0 / (1.0 + exp((-value).coerceIn(-80.0, 80.0))) else Double.NaN
    DetectionScoreActivation.SOFTMAX -> error("Softmax requires the complete class vector")
}

private fun mapBox(a: Double, b: Double, c: Double, d: Double, format: DetectionBoxFormat,
    coordinateSpace: DetectionCoordinateSpace, modelWidth: Int, modelHeight: Int,
    imageWidth: Int, imageHeight: Int): DoubleArray? {
    if (!listOf(a, b, c, d).all { it.isFinite() }) return null
    var left = a
    var top = b
    var right = c
    var bottom = d
    if (format == DetectionBoxFormat.CENTER_XYWH) {
        if (c <= 0.0 || d <= 0.0) return null
        left = a - c * 0.5; right = a + c * 0.5
        top = b - d * 0.5; bottom = b + d * 0.5
    }
    val scaleX = imageWidth.toDouble() /
        if (coordinateSpace == DetectionCoordinateSpace.NORMALIZED) 1.0 else modelWidth.toDouble()
    val scaleY = imageHeight.toDouble() /
        if (coordinateSpace == DetectionCoordinateSpace.NORMALIZED) 1.0 else modelHeight.toDouble()
    left = (left * scaleX).coerceIn(0.0, imageWidth.toDouble())
    right = (right * scaleX).coerceIn(0.0, imageWidth.toDouble())
    top = (top * scaleY).coerceIn(0.0, imageHeight.toDouble())
    bottom = (bottom * scaleY).coerceIn(0.0, imageHeight.toDouble())
    return if (right > left && bottom > top) doubleArrayOf(left, top, right, bottom) else null
}

private fun finish(input: List<DetectionCandidate>, iouThreshold: Double, classAware: Boolean,
    maxPreNms: Int, maxDetections: Int, imageHeight: Int): List<SemanticDetection2d> {
    val candidates = input.sortedWith(compareByDescending<DetectionCandidate> { it.score }
        .thenBy { it.sourceIndex }).take(maxPreNms)
    val accepted = ArrayList<DetectionCandidate>(minOf(maxDetections, candidates.size))
    for (candidate in candidates) {
        val suppressed = accepted.any { existing ->
            (!classAware || candidate.classId == existing.classId) && iou(candidate, existing) > iouThreshold
        }
        if (!suppressed) {
            accepted += candidate
            if (accepted.size == maxDetections) break
        }
    }
    return accepted.map { value ->
        SemanticDetection2d(value.classId, confidence = value.score,
            left = value.left, top = value.top, right = value.right, bottom = value.bottom,
            anchorPixelX = (value.left + value.right) * 0.5,
            anchorPixelY = minOf(value.bottom, Math.nextDown(imageHeight.toDouble())))
    }
}

private fun iou(a: DetectionCandidate, b: DetectionCandidate): Double {
    val intersection = maxOf(0.0, minOf(a.right, b.right) - maxOf(a.left, b.left)) *
        maxOf(0.0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
    val union = (a.right - a.left) * (a.bottom - a.top) +
        (b.right - b.left) * (b.bottom - b.top) - intersection
    return if (union > 0.0) intersection / union else 0.0
}
