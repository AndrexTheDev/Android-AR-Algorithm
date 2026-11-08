#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/types.h"

namespace arkernel {

struct GrayImageView {
  int width{0}, height{0}, row_stride{0};
  const std::uint8_t* data{nullptr};
  std::size_t data_size{0};
  bool valid() const;
  std::uint8_t at(int x, int y) const { return data[y*row_stride + x]; }
};

struct ImageFeature {
  float x{0.0F}, y{0.0F};
  float score{0.0F};
  std::uint32_t id{0};
};

struct FeatureDetectorConfig {
  int threshold{20};
  int grid_columns{8};
  int grid_rows{6};
  int max_features_per_cell{8};
  int border{4};
};

// FAST-9 with non-maximum suppression and per-cell quotas. Grid quotas prevent
// all features concentrating on a poster or horizon, which makes VIO unstable.
class FastGridDetector {
 public:
  explicit FastGridDetector(FeatureDetectorConfig config = {});
  std::vector<ImageFeature> detect(const GrayImageView& image) const;
 private:
  int cornerScore(const GrayImageView& image, int x, int y) const;
  FeatureDetectorConfig config_;
};

}  // namespace arkernel
