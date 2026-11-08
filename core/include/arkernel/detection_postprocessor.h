#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace arkernel {

enum class DetectionTensorElementType : std::uint8_t { kFloat32=0,kInt8=1,kUInt8=2 };
enum class DetectionBoxFormat : std::uint8_t { kXyxy=0,kCenterXywh=1 };

struct DetectionTensorView {
  int rows=0;
  int columns=0;
  int row_stride_elements=0;
  DetectionTensorElementType element_type=DetectionTensorElementType::kFloat32;
  const void* data=nullptr;
  std::size_t data_size_bytes=0;
  double quantization_scale=1.0;
  int quantization_zero_point=0;
};

struct DetectionPostprocessConfig {
  int image_width=0;
  int image_height=0;
  int x0_column=0;
  int y0_column=1;
  int x1_or_width_column=2;
  int y1_or_height_column=3;
  int score_column=4;
  int class_column=5;
  DetectionBoxFormat box_format=DetectionBoxFormat::kXyxy;
  bool normalized_coordinates=true;
  bool class_aware_nms=true;
  double score_threshold=0.25;
  double iou_threshold=0.45;
  std::size_t max_candidates=4096;
  std::size_t max_detections=256;
};

struct DecodedDetection {
  std::uint32_t class_id=0;
  double confidence=0;
  double left=0;
  double top=0;
  double right=0;
  double bottom=0;
  std::size_t source_row=0;
};

std::vector<DecodedDetection> decodeDetectionTensor(const DetectionTensorView& tensor,
                                                     DetectionPostprocessConfig config);

}  // namespace arkernel
