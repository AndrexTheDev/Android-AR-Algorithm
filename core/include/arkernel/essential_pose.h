#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/epipolar_ransac.h"
#include "arkernel/types.h"

namespace arkernel {

struct EssentialPoseConfig {
  std::size_t min_positive_depth{12};
  double min_positive_depth_ratio{0.6};
  double min_median_parallax_degrees{0.25};
};

struct RelativePoseEstimate {
  // Metric scale is unobservable from two monocular frames. Translation is a
  // unit direction and must later be scaled by IMU/VIO optimization.
  Quaternion rotation_current_from_previous{};
  Vec3 translation_direction_current{};
  std::array<double,9> constrained_essential{};
  std::size_t positive_depth_count{0};
  std::size_t evaluated_count{0};
  double median_parallax_degrees{0.0};
  bool valid{false};
};

class EssentialPoseSolver {
 public:
  explicit EssentialPoseSolver(EssentialPoseConfig config = {});
  RelativePoseEstimate solve(
      const std::array<double,9>& epipolar_matrix,
      const std::vector<NormalizedCorrespondence>& correspondences,
      const std::vector<std::uint8_t>& inlier_mask = {}) const;
 private:
  EssentialPoseConfig config_;
};

}  // namespace arkernel
