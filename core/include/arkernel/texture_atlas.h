#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "arkernel/reconstruction_asset.h"
#include "arkernel/texture_view_selection.h"

namespace arkernel {

struct TextureAtlasLayoutConfig {
  int atlas_width{4096};
  int atlas_height{4096};
  int padding_px{4};
  double min_uniform_scale{0.125};
  double max_normal_angle_degrees{75.0};
  bool require_all_triangles_assigned{true};
  std::size_t max_patches{100000};
  std::size_t max_input_vertices{1000000};
  std::size_t max_input_triangles{2000000};
  std::size_t max_unique_edges{3000000};
  std::size_t max_output_vertices{3000000};
};

struct TextureAtlasPatch {
  std::size_t patch_id{0};
  std::size_t view_index{0};
  std::uint64_t frame_id{0};
  double source_min_x_px{0.0};
  double source_min_y_px{0.0};
  double source_max_x_px{0.0};
  double source_max_y_px{0.0};
  int atlas_x_px{0};
  int atlas_y_px{0};
  int atlas_width_px{0};
  int atlas_height_px{0};
};

struct TextureAtlasLayoutResult {
  // Geometry with vertices duplicated only where patch-specific UVs require it.
  // No texture URI is assigned until a later baker creates the actual image.
  ReconstructionMesh mesh;
  std::vector<TextureAtlasPatch> patches;
  std::vector<std::size_t> source_triangle_patch;
  int atlas_width{0};
  int atlas_height{0};
  double uniform_scale{0.0};
  std::size_t assigned_triangles{0};
  std::size_t omitted_unassigned_triangles{0};
  std::size_t duplicated_vertices{0};
  std::size_t boundary_edges{0};
  std::size_t sharp_edges{0};
  std::size_t non_manifold_edges{0};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Builds deterministic same-view UV islands and packs their source-image
// bounds into one atlas page. This lays out UVs only: it does not decode source
// images, copy pixels, compensate exposure, blend seams, or assign a texture URI.
TextureAtlasLayoutResult layoutTextureAtlas(
    const ReconstructionMesh& mesh,
    const std::vector<TextureViewReference>& views,
    const std::vector<TriangleTextureView>& labels,
    TextureAtlasLayoutConfig config = {});

}  // namespace arkernel
