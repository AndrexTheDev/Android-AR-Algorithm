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

struct TextureGlobalSeamConfig {
  int samples_per_seam{8};
  std::size_t max_triangles{2000000};
  std::size_t max_unique_edges{3000000};
  std::size_t max_seam_pairs{1000000};
  std::size_t max_seam_samples{16000000};
  std::size_t max_patches{100000};
  std::size_t max_iterations{32};
  std::size_t max_pixel_updates{16000000};
  double convergence_delta{1e-4};
  double regularization{1.0};
  double min_gain{0.5};
  double max_gain{2.0};
  double min_offset{-0.25};
  double max_offset{0.25};
};

struct TextureGlobalSeamTelemetry {
  std::size_t seam_pairs{0};
  std::size_t sampled_pairs{0};
  std::size_t skipped_transparent_samples{0};
  std::size_t iterations{0};
  std::size_t constrained_patches{0};
  std::size_t pixel_updates{0};
  double residual_before{0.0};
  double residual_after{0.0};
  bool enabled{false};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

struct TextureGlobalSeamResult {
  std::vector<std::uint8_t> rgba8;
  int width{0};
  int height{0};
  std::size_t unique_edges{0};
  TextureGlobalSeamTelemetry telemetry{};
};

// Solves one bounded global least-squares problem over all manifold atlas seam
// pairs. Each patch gets a regularized per-channel gain/offset that minimizes
// corrected color disagreement across every sampled seam correspondence. This
// is not a full Poisson/multiband seam solve or a calibrated camera-response
// model; alpha and transparent padding are preserved. Only opaque pixels in
// owning patch rectangles are updated, under explicit work budgets.
TextureGlobalSeamResult optimizeTextureAtlasSeamsGlobally(
    const ReconstructionMesh& source_mesh,
    const TextureAtlasLayoutResult& layout,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<std::uint8_t>& rgba8,
    int width,
    int height,
    TextureGlobalSeamConfig config = {});

}  // namespace arkernel
