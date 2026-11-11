#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "arkernel/texture_baking.h"

namespace arkernel {

/** Stable fail-closed reason for bounded global seam holdout validation. */
enum class TextureGlobalSeamRejection {
  kNone,
  kInsufficientTrainingEvidence,
  kInsufficientHoldoutEvidence,
  kTrainingRmseTooLarge,
  kHoldoutResidualTooLarge,
  kHoldoutRmseTooLarge,
  kHoldoutRatioTooLarge,
};

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
  // When require_holdout_validation is enabled, a deterministic sample split
  // sends (sample index + 1) % holdout_stride == 0 to holdout; all other
  // samples train the least-squares solve. Disabled validation trains on all
  // samples, preserving the existing bounded solve.
  std::size_t holdout_stride{4};
  std::size_t min_training_samples{1};
  std::size_t min_holdout_samples{2};
  double max_training_rmse{0.20};
  double max_holdout_residual{0.20};
  double max_holdout_rmse{0.10};
  double max_holdout_ratio{3.0};
  // Strict cross-validation is opt-in so existing bounded callers keep their
  // measured least-squares correction when a scene has too little holdout
  // evidence.
  bool require_holdout_validation{false};
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
  std::size_t training_samples{0};
  std::size_t holdout_samples{0};
  double training_rmse{0.0};
  double holdout_max_residual{0.0};
  double holdout_rmse{0.0};
  double holdout_ratio{0.0};
  bool holdout_validated{false};
  bool holdout_accepted{true};
  TextureGlobalSeamRejection rejection{TextureGlobalSeamRejection::kNone};
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
// corrected color disagreement across every sampled seam correspondence. An
// optional deterministic train/holdout split fits only on training samples and
// gates training RMSE, holdout maximum residual/RMSE and the holdout ratio;
// rejected estimates fail closed with stable reasons and leave RGBA pixels
// untouched. Strict validation is a core config option, not a new
// Android/JNI/Kotlin envelope. This is not a full Poisson/multiband seam solve
// or a calibrated camera-response model; alpha and transparent padding are
// preserved. Only opaque pixels in owning patch rectangles are updated, under
// explicit work budgets.
TextureGlobalSeamResult optimizeTextureAtlasSeamsGlobally(
    const ReconstructionMesh& source_mesh,
    const TextureAtlasLayoutResult& layout,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<std::uint8_t>& rgba8,
    int width,
    int height,
    TextureGlobalSeamConfig config = {});

const char* textureGlobalSeamRejectionText(
    TextureGlobalSeamRejection rejection);

}  // namespace arkernel
