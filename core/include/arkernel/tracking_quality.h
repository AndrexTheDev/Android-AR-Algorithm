#pragma once
#include "arkernel/types.h"

namespace arkernel {

struct QualityPolicy {
  int min_inliers_tracking{30};
  int min_inliers_limited{12};
  double max_error_tracking_px{2.5};
  double max_error_limited_px{5.0};
  int bad_frames_before_lost{5};
  int good_frames_before_recovered{3};
};

// Adds hysteresis to noisy backend metrics, preventing TRACKING/LOST flicker.
class TrackingQualityGate {
 public:
  explicit TrackingQualityGate(QualityPolicy policy = {}) : policy_(policy) {}
  TrackingState update(int inliers, double reprojection_error_px);
  void reset();
  TrackingState state() const { return state_; }
 private:
  QualityPolicy policy_;
  TrackingState state_{TrackingState::kUninitialized};
  int good_frames_{0};
  int bad_frames_{0};
};

}  // namespace arkernel
