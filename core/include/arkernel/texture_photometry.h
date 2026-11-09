#pragma once

#include <array>
#include <cstddef>
#include <string>
#include <vector>

#include "arkernel/texture_baking.h"

namespace arkernel {

struct TexturePhotometryConfig {
  std::size_t samples_per_seam_edge{9};
  std::size_t max_views{4096};
  std::size_t max_triangles{2000000};
  std::size_t max_unique_edges{3000000};
  std::size_t max_sample_tests{8000000};
  std::size_t max_equations{1000000};
  std::size_t solver_iterations{12};
  double min_usable_channel{8.0};
  double max_usable_channel{247.0};
  double min_gain{0.5};
  double max_gain{2.0};
};

struct TexturePhotometryResult {
  // One record per input view, in view-table order. Unconnected views retain
  // identity gain and are explicitly marked unconstrained.
  std::vector<TextureViewColorCorrection> corrections;
  std::size_t unique_edges{0};
  std::size_t seam_edges{0};
  std::size_t equations{0};
  std::size_t sample_tests{0};
  std::size_t rejected_samples{0};
  std::size_t connected_components{0};
  std::size_t constrained_views{0};
  std::size_t clamped_channels{0};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Estimates deterministic per-view RGB gains from corresponding samples along
// manifold mesh edges whose adjacent triangles use different source views.
// Each connected view graph is anchored at its lowest view-table index; views
// without usable seam evidence stay at identity rather than being guessed.
TexturePhotometryResult estimateTexturePhotometry(
    const ReconstructionMesh& mesh,
    const std::vector<TextureViewReference>& views,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<TextureSourceImageView>& images,
    TexturePhotometryConfig config = {});

}  // namespace arkernel
