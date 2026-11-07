#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/spatial_snapshot.h"
#include "arkernel/types.h"

namespace arkernel {

struct RelocalizationCorrespondence {
  Vec3 saved_world_point_m{};
  Vec3 current_world_point_m{};
};

struct RelocalizationConfig {
  std::size_t max_correspondences{4096};
  std::size_t min_inliers{6};
  std::size_t ransac_iterations{128};
  double inlier_threshold_m{0.10};
  double min_inlier_ratio{0.60};
  double max_rmse_m{0.05};
  double min_sample_triangle_area_m2{0.0025};
  std::uint32_t random_seed{0x52454C31u};
};

struct RelocalizationResult {
  Pose current_from_saved{};
  std::size_t correspondence_count{0};
  std::size_t inlier_count{0};
  double rmse_m{0.0};
  bool valid{false};
};

RelocalizationResult estimateRelocalization(
    const std::vector<RelocalizationCorrespondence>& correspondences,
    RelocalizationConfig config = {});

SpatialSnapshot transformSpatialSnapshot(const SpatialSnapshot& snapshot,
                                         const Pose& current_from_saved);

bool restoreRelocalizedSpatialSnapshot(const SpatialSnapshot& snapshot,
                                       const Pose& current_from_saved,
                                       SparseLandmarkMap& map,
                                       AnchorStore& anchors);

}  // namespace arkernel
