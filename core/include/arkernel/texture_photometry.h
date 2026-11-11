#pragma once

#include <array>
#include <cstddef>
#include <string>
#include <vector>

#include "arkernel/texture_baking.h"

namespace arkernel {

/** Stable fail-closed reason for bounded photometric holdout validation. */
enum class TexturePhotometryRejection {
  kNone,
  kInsufficientTrainingEvidence,
  kInsufficientHoldoutEvidence,
  kRobustSolverDidNotConverge,
  kTrainingRmseTooLarge,
  kHoldoutResidualTooLarge,
  kHoldoutRmseTooLarge,
  kHoldoutRatioTooLarge,
};

struct TexturePhotometryConfig {
  std::size_t samples_per_seam_edge{9};
  std::size_t max_views{4096};
  std::size_t max_triangles{2000000};
  std::size_t max_unique_edges{3000000};
  std::size_t max_sample_tests{8000000};
  std::size_t max_equations{1000000};
  // Bounded Huber-weighted Jacobi iterations used for the gain solve.
  std::size_t solver_iterations{64};
  // Deterministic equation split: (equation index + 1) % holdout_stride == 0
  // goes to holdout; all other equations train the gain solve.
  std::size_t holdout_stride{5};
  std::size_t min_training_equations{1};
  std::size_t min_holdout_equations{2};
  double min_usable_channel{8.0};
  double max_usable_channel{247.0};
  double min_gain{0.5};
  double max_gain{2.0};
  double huber_delta{0.02};
  double max_training_rmse{0.20};
  double max_holdout_residual{0.20};
  double max_holdout_rmse{0.10};
  double max_holdout_ratio{3.0};
  // Strict cross-validation is opt-in so existing bounded callers keep their
  // identity fallback when a scene has too little holdout evidence.
  bool require_holdout_validation{false};
};

struct TexturePhotometryResult {
  // One record per input view, in view-table order. Unconnected views retain
  // identity gain and are explicitly marked unconstrained.
  std::vector<TextureViewColorCorrection> corrections;
  std::size_t unique_edges{0};
  std::size_t seam_edges{0};
  std::size_t equations{0};
  std::size_t training_equations{0};
  std::size_t holdout_equations{0};
  std::size_t robust_iterations{0};
  std::size_t downweighted_equations{0};
  std::size_t sample_tests{0};
  std::size_t rejected_samples{0};
  std::size_t connected_components{0};
  std::size_t constrained_views{0};
  std::size_t clamped_channels{0};
  double training_rmse{0.0};
  double holdout_max_residual{0.0};
  double holdout_rmse{0.0};
  double holdout_ratio{0.0};
  bool robust_converged{false};
  bool holdout_validated{false};
  bool holdout_accepted{true};
  TexturePhotometryRejection rejection{TexturePhotometryRejection::kNone};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Estimates deterministic per-view RGB gains from corresponding samples along
// manifold mesh edges whose adjacent triangles use different source views.
// Each connected view graph is anchored at its lowest view-table index; views
// without usable seam evidence stay at identity rather than being guessed.
// A bounded Huber-weighted Jacobi solve downweights inconsistent training
// equations, while an optional deterministic holdout split provides
// fail-closed cross-validation. This is synthetic consistency evidence, stays
// explicitly `unverified`, and is not a full Poisson/multiband seam solve,
// calibrated camera-response model or reconstruction quality grade.
TexturePhotometryResult estimateTexturePhotometry(
    const ReconstructionMesh& mesh,
    const std::vector<TextureViewReference>& views,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<TextureSourceImageView>& images,
    TexturePhotometryConfig config = {});

const char* texturePhotometryRejectionText(
    TexturePhotometryRejection rejection);

}  // namespace arkernel
