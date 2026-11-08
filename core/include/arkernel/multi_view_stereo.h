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

struct MultiViewStereoConfig {
  double min_depth_m{0.15};
  double max_depth_m{10.0};
  std::size_t depth_hypotheses{64};
  int pixel_stride{2};
  int patch_radius{2};
  std::size_t min_supporting_views{2};
  std::size_t max_source_views{8};
  std::size_t max_output_pixels{4'194'304};
  double min_baseline_m{0.03};
  double max_baseline_m{3.0};
  double min_zncc{0.65};
  double min_winner_margin{0.02};
};

struct MultiViewStereoDepth {
  int width{0},height{0};
  std::vector<float> depth_m;
  std::vector<float> confidence;
  std::vector<float> uncertainty_m;
  std::size_t valid_pixels{0};
  bool valid{false};
};

// Bounded calibrated inverse-depth plane sweep. Invalid pixels remain NaN and
// are never filled. Input poses are expected to be globally optimized.
MultiViewStereoDepth estimateMultiViewDepth(
    const MultiViewStereoFrame& reference,
    const std::vector<MultiViewStereoFrame>& sources,
    MultiViewStereoConfig config={});

}  // namespace arkernel
