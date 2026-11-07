#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/epipolar_ransac.h"

namespace arkernel {

struct HomographyRansacConfig {
  int max_iterations{250};
  double transfer_threshold{0.003};
  std::size_t min_inliers{10};
  std::uint32_t random_seed{0x484F4D31u};
};

struct HomographyVerification {
  // Row-major mapping from previous to current normalized image coordinates.
  std::array<double,9> matrix{};
  std::vector<std::uint8_t> inliers;
  std::size_t inlier_count{0};
  double median_symmetric_transfer_error{0.0};
  bool valid{false};
};

class HomographyRansac {
 public:
  explicit HomographyRansac(HomographyRansacConfig config = {});
  HomographyVerification verify(
      const std::vector<NormalizedCorrespondence>& correspondences) const;
  static double symmetricTransferError(
      const std::array<double,9>& homography,
      const NormalizedCorrespondence& correspondence);
 private:
  HomographyRansacConfig config_;
};

}  // namespace arkernel
