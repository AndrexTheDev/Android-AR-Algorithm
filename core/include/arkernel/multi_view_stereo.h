#pragma once

#include <cstddef>
#include <vector>

#include "arkernel/camera_model.h"
#include "arkernel/feature_detector.h"
#include "arkernel/types.h"

namespace arkernel {

struct MultiViewStereoFrame {
  GrayImageView image{};
  const PinholeRadTanCamera* camera{nullptr};
  Pose world_from_camera{};
};

enum class MultiViewStereoMatchingCost {
  kZncc,
  kCensus,
  kHybrid,
};

struct MultiViewStereoConfig {
  double min_depth_m{0.15};
  double max_depth_m{10.0};
  std::size_t depth_hypotheses{64};
  std::size_t refinement_levels{2};
  std::size_t refinement_hypotheses{9};
  double refinement_radius_steps{1.5};
  int pixel_stride{2};
  int patch_radius{2};
  std::size_t min_supporting_views{2};
  std::size_t max_source_views{8};
  std::size_t max_output_pixels{4'194'304};
  double min_baseline_m{0.03};
  double max_baseline_m{3.0};
  MultiViewStereoMatchingCost matching_cost{MultiViewStereoMatchingCost::kHybrid};
  double census_weight{0.35};
  double census_intensity_tolerance{1.0};
  std::size_t min_census_bits{6};
  // Retained for source compatibility; this is the minimum normalized score
  // for the selected ZNCC, Census, or hybrid matching cost.
  double min_zncc{0.65};
  double min_winner_margin{0.02};
};

struct MultiViewStereoDepth {
  int width{0},height{0};
  std::vector<float> depth_m;
  std::vector<float> confidence;
  std::vector<float> uncertainty_m;
  std::size_t valid_pixels{0};
  std::size_t consistency_tested_pixels{0};
  std::size_t consistency_rejected_pixels{0};
  bool valid{false};
};

struct MultiViewStereoDepthFrame {
  MultiViewStereoFrame frame{};
  const MultiViewStereoDepth* depth{nullptr};
};

struct MultiViewStereoConsistencyConfig {
  std::size_t min_consistent_views{1};
  std::size_t max_source_views{8};
  std::size_t max_output_pixels{4'194'304};
  int search_radius_px{2};
  double min_source_confidence{0.1};
  double min_baseline_m{0.03};
  double max_baseline_m{3.0};
  double max_absolute_depth_error_m{0.03};
  double max_relative_depth_error{0.02};
  double uncertainty_sigma{2.0};
  double max_round_trip_error_px{1.5};
};

// Bounded calibrated inverse-depth plane sweep. Invalid pixels remain NaN and
// are never filled. Input poses are expected to be globally optimized.
MultiViewStereoDepth estimateMultiViewDepth(
    const MultiViewStereoFrame& reference,
    const std::vector<MultiViewStereoFrame>& sources,
    MultiViewStereoConfig config={});

// Filters an existing reference depth map against cached source-view depth
// maps. Failed pixels remain explicit NaN; no missing depth is synthesized.
MultiViewStereoDepth filterMultiViewDepthConsistency(
    const MultiViewStereoFrame& reference,
    const MultiViewStereoDepth& reference_depth,
    const std::vector<MultiViewStereoDepthFrame>& sources,
    MultiViewStereoConsistencyConfig config={});

}  // namespace arkernel
