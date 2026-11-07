#include "arkernel/tracking_quality.h"

namespace arkernel {

void TrackingQualityGate::reset() {
  state_ = TrackingState::kUninitialized;
  good_frames_ = bad_frames_ = 0;
}

TrackingState TrackingQualityGate::update(int inliers, double error) {
  const bool good = inliers >= policy_.min_inliers_tracking &&
                    error <= policy_.max_error_tracking_px;
  const bool usable = inliers >= policy_.min_inliers_limited &&
                      error <= policy_.max_error_limited_px;
  good_frames_ = good ? good_frames_ + 1 : 0;
  bad_frames_ = usable ? 0 : bad_frames_ + 1;

  if (good_frames_ >= policy_.good_frames_before_recovered) state_ = TrackingState::kTracking;
  else if (bad_frames_ >= policy_.bad_frames_before_lost) state_ = TrackingState::kLost;
  else if (!good && state_ != TrackingState::kLost) state_ = TrackingState::kLimited;
  return state_;
}

}  // namespace arkernel
