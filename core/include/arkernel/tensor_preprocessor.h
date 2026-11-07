#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace arkernel {

enum class TensorImageFormat : std::uint32_t { kGray8, kRgb888, kRgba8888 };
enum class TensorElementType : std::uint32_t { kFloat32, kInt8, kUInt8 };

struct TensorImageView {
  int width{0};
  int height{0};
  int row_stride_bytes{0};
  TensorImageFormat format{TensorImageFormat::kGray8};
  const std::uint8_t* data{nullptr};
  std::size_t data_size{0};
};

struct TensorPreprocessConfig {
  int output_width{0};
  int output_height{0};
  int output_channels{3};
  std::array<double,4> mean{{0.0,0.0,0.0,0.0}};
  std::array<double,4> standard_deviation{{1.0,1.0,1.0,1.0}};
  TensorElementType element_type{TensorElementType::kFloat32};
  double quantization_scale{1.0};
  int quantization_zero_point{0};
  bool swap_red_blue{false};
  std::size_t max_output_elements{16000000};
};

struct PreprocessedTensor {
  int width{0};
  int height{0};
  int channels{0};
  TensorElementType element_type{TensorElementType::kFloat32};
  std::vector<float> float_values;
  std::vector<std::int8_t> int8_values;
  std::vector<std::uint8_t> uint8_values;
  bool valid{false};

  std::size_t elementCount() const;
};

bool validTensorImage(const TensorImageView& image);

// Deterministic NHWC preprocessing with bilinear resize. RGB/RGBA inputs are
// interpreted in byte order as named; alpha is ignored. Gray output uses
// Rec. 601 luma, while gray input is replicated for three-channel output.
PreprocessedTensor preprocessTensorImage(const TensorImageView& image,
                                         TensorPreprocessConfig config);

}  // namespace arkernel
