#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "arkernel/texture_baking.h"

namespace arkernel {

struct TextureSeamBlendingConfig {
  int blend_radius_px{4};
  std::size_t max_triangles{2000000};
  std::size_t max_unique_edges{3000000};
  std::size_t max_seam_edges{1000000};
  std::size_t max_pixel_tests{64000000};
  std::size_t max_pixel_updates{16000000};
};

struct TextureSeamBlendingResult {
  // Same dimensions and top-left-origin straight-alpha RGBA8 representation as
  // TextureBakingResult. Input pixels are never modified.
  std::vector<std::uint8_t> rgba8;
  int width{0};
  int height{0};
  std::size_t unique_edges{0};
  std::size_t seam_edges{0};
  std::size_t skipped_transparent_samples{0};
  std::size_t pixel_tests{0};
  std::size_t pixel_updates{0};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Locally blends the two atlas copies of each manifold source-mesh edge whose
// adjacent triangles belong to different patches. Work stays inside each patch
// rectangle and does not invent evidence for open or non-manifold boundaries.
TextureSeamBlendingResult blendTextureAtlasSeams(
    const ReconstructionMesh& source_mesh,
    const TextureAtlasLayoutResult& layout,
    const std::vector<TriangleTextureView>& labels,
    const TextureBakingResult& baked,
    TextureSeamBlendingConfig config = {});

}  // namespace arkernel
