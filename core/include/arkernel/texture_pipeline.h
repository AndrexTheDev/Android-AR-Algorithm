#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "arkernel/texture_atlas.h"
#include "arkernel/texture_baking.h"
#include "arkernel/texture_photometry.h"
#include "arkernel/texture_seam_blending.h"
#include "arkernel/texture_view_selection.h"

namespace arkernel {

enum class TexturePipelineStage {
  kNone,
  kViewSelection,
  kLabelOptimization,
  kAtlasLayout,
  kPhotometry,
  kBaking,
  kSeamBlending,
  kComplete,
};

struct TexturePipelineConfig {
  TextureViewSelectionConfig view_selection{};
  TextureLabelOptimizationConfig label_optimization{};
  TextureAtlasLayoutConfig atlas_layout{};
  TexturePhotometryConfig photometry{};
  TextureBakingConfig baking{};
  TextureSeamBlendingConfig seam_blending{};
  TextureGlobalSeamConfig global_seam{};
  bool enable_photometric_correction{true};
  bool enable_seam_blending{true};
  bool enable_global_seam_optimization{false};
};

struct TexturePipelineTelemetry {
  std::size_t candidate_views{0};
  std::size_t projection_tests{0};
  std::size_t assigned_triangles{0};
  std::size_t unassigned_triangles{0};
  std::size_t label_iterations{0};
  std::size_t seams_before_labeling{0};
  std::size_t seams_after_labeling{0};
  std::size_t atlas_patches{0};
  std::size_t atlas_vertices{0};
  std::size_t atlas_triangles{0};
  std::size_t photometry_equations{0};
  std::size_t photometry_constrained_views{0};
  std::size_t rasterized_pixels{0};
  std::size_t padding_pixels{0};
  std::size_t raster_pixel_tests{0};
  std::size_t blended_seams{0};
  std::size_t blended_pixel_updates{0};
  // Cumulative seam work across optional local blending and global optimization.
  std::size_t global_seam_pairs{0};
  std::size_t global_seam_samples{0};
  std::size_t global_seam_iterations{0};
  std::size_t global_seam_pixel_updates{0};
};

struct TexturePipelineResult {
  ReconstructionMesh mesh;
  // Tightly packed top-left-origin straight-alpha RGBA8. No URI is assigned.
  std::vector<std::uint8_t> rgba8;
  std::vector<TextureViewReference> views;
  std::vector<TextureViewColorCorrection> corrections;
  int atlas_width{0};
  int atlas_height{0};
  TexturePipelineTelemetry telemetry{};
  TextureGlobalSeamTelemetry global_seam{};
  TexturePipelineStage stage{TexturePipelineStage::kNone};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Runs the dependency-free texture stages as one fail-closed transaction. Any
// invalid or truncated stage stops the graph; only kComplete carries final mesh
// and pixels. JPEG/PNG decoding and PNG encoding remain platform adapters.
TexturePipelineResult buildTextureAtlas(
    const ReconstructionMesh& source_mesh,
    const std::vector<TextureViewCandidate>& candidates,
    const std::vector<TextureSourceImageView>& images,
    TexturePipelineConfig config = {});

}  // namespace arkernel
