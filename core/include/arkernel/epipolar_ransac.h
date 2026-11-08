#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/camera_model.h"

namespace arkernel {

struct NormalizedCorrespondence {
  Vec2 previous;
  Vec2 current;
};

struct EpipolarRansacConfig {
  int max_iterations{300};
  double sampson_threshold{0.0015};
  std::size_t min_inliers{12};
  std::uint32_t random_seed{0x41524B31u};
};

struct EpipolarVerification {
  // Row-major matrix satisfying current^T * E * previous = 0. This is an
  // unconstrained normalized 8-point model for verification, not yet a pose.
  std::array<double,9> matrix{};
  std::vector<std::uint8_t> inliers;
  std::size_t inlier_count{0};
  double median_sampson_error{0.0};
  bool valid{false};
};

// Deterministic calibrated 8-point RANSAC. Input points are normalized camera
// coordinates after lens undistortion (x/z, y/z), not pixels.
class EpipolarRansac {
 public:
  explicit EpipolarRansac(EpipolarRansacConfig config = {});
  EpipolarVerification verify(
      const std::vector<NormalizedCorrespondence>& correspondences) const;
  static double sampsonError(const std::array<double,9>& matrix,
                             const NormalizedCorrespondence& correspondence);
 private:
  EpipolarRansacConfig config_;
};

}  // namespace arkernel
