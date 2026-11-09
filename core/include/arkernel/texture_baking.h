#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "arkernel/texture_atlas.h"

namespace arkernel {

enum class TexturePixelFormat { kGray8, kRgb8, kRgba8, kI420 };

struct TextureSourceImageView {
  std::uint64_t frame_id{0};
  int width{0};
  int height{0};
  int row_stride_bytes{0};
  TexturePixelFormat format{TexturePixelFormat::kRgb8};
  const std::uint8_t* data{nullptr};
  std::size_t data_size{0};
};

struct TextureBakingConfig {
  std::size_t max_source_images{256};
  std::size_t max_atlas_pixels{16777216};
  std::size_t max_pixel_tests{67108864};
  std::size_t max_padding_pixels{16777216};
};

struct TextureBakingResult {
  // Straight-alpha RGBA8, top-left row origin, tightly packed.
  std::vector<std::uint8_t> rgba8;
  int width{0};
  int height{0};
  std::size_t rasterized_pixels{0};
  std::size_t padding_pixels{0};
  std::size_t overlap_pixels{0};
  std::size_t pixel_tests{0};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Bakes source pixels into an existing atlas layout. JPEG/PNG decoding and
// final image encoding remain platform adapters; canonical tightly packed I420
// from ReconstructionI420Packer is accepted directly.
TextureBakingResult bakeTextureAtlas(
    const TextureAtlasLayoutResult& layout,
    const std::vector<TextureViewReference>& views,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<TextureSourceImageView>& images,
    TextureBakingConfig config = {});

}  // namespace arkernel
