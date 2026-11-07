#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/camera_model.h"
#include "arkernel/surfel_map.h"

namespace arkernel {

struct MetricDepthImageView {
  int width{0};
  int height{0};
  int row_stride_elements{0};
  const float* depth_m{nullptr};
  std::size_t depth_element_count{0};
  const float* confidence{nullptr};
  int confidence_row_stride_elements{0};
  std::size_t confidence_element_count{0};
};

struct DepthSamplingConfig {
  int pixel_stride{4};
  double min_depth_m{0.15};
  double max_depth_m{20.0};
  double min_confidence{0.20};
  double max_normal_depth_discontinuity_ratio{0.10};
  double min_radius_m{0.002};
  double max_radius_m{0.50};
  std::size_t max_samples{100000};
  bool estimate_normals{true};
};

bool validMetricDepthImage(const MetricDepthImageView& image);

// Converts an aligned metric depth image into bounded camera-space surfel
// observations. Invalid depth/confidence pixels are skipped, never imputed.
std::vector<SurfelObservation> sampleMetricDepthImage(
    const MetricDepthImageView& image,
    const PinholeRadTanCamera& camera,
    TimestampNs timestamp_ns,
    DepthSamplingConfig config = {});

}  // namespace arkernel
