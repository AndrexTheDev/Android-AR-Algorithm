#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace arkernel {

enum class DepthTensorElementType : std::uint8_t { kFloat32=0,kInt8=1,kUInt8=2 };
enum class DepthValueTransform : std::uint8_t { kDirect=0,kInverse=1 };

struct DepthTensorView {
  int width=0;
  int height=0;
  int row_stride_elements=0;
  DepthTensorElementType element_type=DepthTensorElementType::kFloat32;
  const void* data=nullptr;
  std::size_t data_size_bytes=0;
  double quantization_scale=1.0;
  int quantization_zero_point=0;
};

struct DepthPostprocessConfig {
  int output_width=0;
  int output_height=0;
  DepthValueTransform transform=DepthValueTransform::kDirect;
  double unit_to_meters=1.0;
  double min_depth_meters=0.05;
  double max_depth_meters=20.0;
  double max_relative_discontinuity=0.10;
  std::size_t max_output_elements=16'000'000;
};

struct PostprocessedDepth {
  int width=0;
  int height=0;
  std::vector<float> depth_meters;
  std::vector<float> confidence;
  bool valid=false;
};

PostprocessedDepth postprocessDepthTensor(const DepthTensorView& tensor,
                                          DepthPostprocessConfig config);

}  // namespace arkernel
