#include "arkernel/feature_detector.h"
#include <algorithm>
#include <stdexcept>

namespace arkernel {

bool GrayImageView::valid() const {
  if (!data || width <= 0 || height <= 0 || row_stride < width) return false;
  const std::size_t required = static_cast<std::size_t>(height-1)*row_stride+
                               static_cast<std::size_t>(width);
  return data_size >= required;
}

FastGridDetector::FastGridDetector(FeatureDetectorConfig config) : config_(config) {
  if (config_.threshold < 1 || config_.threshold > 255 || config_.grid_columns < 1 ||
      config_.grid_rows < 1 || config_.max_features_per_cell < 1 || config_.border < 3)
    throw std::invalid_argument("Invalid feature detector configuration");
}

int FastGridDetector::cornerScore(const GrayImageView& image, int x, int y) const {
  static constexpr int ring[16][2] = {
    {0,-3},{1,-3},{2,-2},{3,-1},{3,0},{3,1},{2,2},{1,3},
    {0,3},{-1,3},{-2,2},{-3,1},{-3,0},{-3,-1},{-2,-2},{-1,-3}};
  const int center = image.at(x,y);
  int best = 0;
  for (int sign : {-1, 1}) {
    for (int start=0; start<16; ++start) {
      int minimum = 256;
      bool valid = true;
      for (int n=0; n<9; ++n) {
        const int i = (start+n) & 15;
        const int contrast = sign * (static_cast<int>(image.at(x+ring[i][0], y+ring[i][1])) - center);
        if (contrast <= config_.threshold) { valid = false; break; }
        minimum = std::min(minimum, contrast);
      }
      if (valid) best = std::max(best, minimum);
    }
  }
  return best;
}

std::vector<ImageFeature> FastGridDetector::detect(const GrayImageView& image) const {
  if (!image.valid()) throw std::invalid_argument("Invalid grayscale image view");
  if (image.width <= 2*config_.border || image.height <= 2*config_.border) return {};

  std::vector<int> scores(static_cast<std::size_t>(image.width)*image.height, 0);
  for (int y=config_.border; y<image.height-config_.border; ++y)
    for (int x=config_.border; x<image.width-config_.border; ++x)
      scores[static_cast<std::size_t>(y)*image.width+x] = cornerScore(image,x,y);

  const int cells = config_.grid_columns * config_.grid_rows;
  std::vector<std::vector<ImageFeature>> buckets(static_cast<std::size_t>(cells));
  for (int y=config_.border; y<image.height-config_.border; ++y) {
    for (int x=config_.border; x<image.width-config_.border; ++x) {
      const int score = scores[static_cast<std::size_t>(y)*image.width+x];
      if (score == 0) continue;
      bool maximum = true;
      for (int dy=-1; dy<=1 && maximum; ++dy)
        for (int dx=-1; dx<=1; ++dx)
          if ((dx || dy) && scores[static_cast<std::size_t>(y+dy)*image.width+x+dx] > score) {
            maximum = false; break;
          }
      if (!maximum) continue;
      const int column = std::min(config_.grid_columns-1, x*config_.grid_columns/image.width);
      const int row = std::min(config_.grid_rows-1, y*config_.grid_rows/image.height);
      buckets[static_cast<std::size_t>(row*config_.grid_columns+column)]
          .push_back({static_cast<float>(x), static_cast<float>(y), static_cast<float>(score), 0});
    }
  }

  std::vector<ImageFeature> result;
  result.reserve(static_cast<std::size_t>(cells*config_.max_features_per_cell));
  std::uint32_t id = 0;
  for (auto& bucket : buckets) {
    std::stable_sort(bucket.begin(), bucket.end(), [](const auto& a, const auto& b) {
      if (a.score != b.score) return a.score > b.score;
      if (a.y != b.y) return a.y < b.y;
      return a.x < b.x;
    });
    if (bucket.size() > static_cast<std::size_t>(config_.max_features_per_cell))
      bucket.resize(static_cast<std::size_t>(config_.max_features_per_cell));
    for (auto& feature : bucket) { feature.id = id++; result.push_back(feature); }
  }
  return result;
}

}  // namespace arkernel
