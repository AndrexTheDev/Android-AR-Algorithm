#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/camera_model.h"
#include "arkernel/types.h"

namespace arkernel {

struct TemporalDepthFilterConfig {
  double min_depth_m=0.05;
  double max_depth_m=20.0;
  double max_relative_difference=0.08;
  double max_absolute_difference_m=0.05;
  double history_weight=0.35;
  std::int64_t max_frame_gap_ns=500'000'000;
  std::size_t max_pixels=4'194'304;
};
struct TemporalDepthFrameView {
  int width=0,height=0,row_stride_elements=0;
  const float* depth_m=nullptr;
  const float* confidence=nullptr;
  int confidence_row_stride_elements=0;
  std::size_t depth_elements=0,confidence_elements=0;
  std::int64_t timestamp_ns=0;
  Pose world_from_camera{};
};
struct TemporalDepthResult {
  int width=0,height=0;
  std::vector<float> depth_m;
  std::vector<float> confidence;
  std::size_t blended_pixels=0,rejected_history_pixels=0;
  bool history_used=false;
};

class TemporalDepthFilter {
 public:
  explicit TemporalDepthFilter(TemporalDepthFilterConfig config={});
  TemporalDepthResult update(const TemporalDepthFrameView& frame,
                             const PinholeRadTanCamera& camera);
  void reset();
 private:
  TemporalDepthFilterConfig config_;
  int width_=0,height_=0;
  std::int64_t timestamp_ns_=0;
  Pose world_from_camera_{};
  CameraIntrinsics intrinsics_{};
  std::vector<float> depth_,confidence_;
};
}  // namespace arkernel
