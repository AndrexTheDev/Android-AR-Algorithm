#pragma once
#include <cstddef>
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

enum class MappingGateState { kPaused, kRecovering, kActive };
enum class MappingPauseReason {
  kNone,
  kPoseUninitialized,
  kPoseLimited,
  kVisualNotTracking,
  kTooFewTracks,
  kImuOverflow,
  kRecovering
};

struct MappingGatePolicy {
  std::size_t min_accepted_tracks{12};
  int good_frames_before_resume{3};
  int bad_frames_before_pause{1};
};

struct MappingGateDecision {
  MappingGateState state{MappingGateState::kPaused};
  MappingPauseReason reason{MappingPauseReason::kPoseUninitialized};
  bool integrate{false};
};

// Mapping pauses conservatively on bad pose/frame evidence and requires a
// streak of good frames before resuming, preventing unstable geometry writes.
class MappingQualityGate {
 public:
  explicit MappingQualityGate(MappingGatePolicy policy = {});
  MappingGateDecision update(bool pose_initialized,
                             bool pose_limited,
                             bool visual_tracking,
                             std::size_t accepted_tracks,
                             bool imu_queue_overflow);
  void reset();
  MappingGateState state() const { return state_; }
 private:
  MappingGatePolicy policy_;
  MappingGateState state_{MappingGateState::kPaused};
  int good_frames_{0};
  int bad_frames_{0};
};

}  // namespace arkernel
