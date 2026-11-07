#pragma once

#include <cstddef>
#include <deque>
#include <optional>
#include "arkernel/essential_pose.h"
#include "arkernel/types.h"

namespace arkernel {

enum class VioStatus { kUninitialized, kInertial, kVisualInertial, kLimited };

struct VioState {
  TimestampNs timestamp_ns{0};
  Pose world_from_body{};
  Vec3 velocity_world_mps{};
  Vec3 accelerometer_bias_mps2{};
  Vec3 gyroscope_bias_rps{};
  Vec3 gravity_world_mps2{0.0,0.0,-9.80665};
  VioStatus status{VioStatus::kUninitialized};
  double visual_translation_scale_m{0.0};
  bool visual_translation_scale_valid{false};
};

struct VioConfig {
  std::size_t initialization_samples{80};
  double initialization_duration_s{0.6};
  double gravity_magnitude_mps2{9.80665};
  double gravity_tolerance_mps2{1.0};
  double max_stationary_gyro_std_rps{0.03};
  double max_stationary_accel_std_mps2{0.25};
  double max_imu_gap_s{0.05};
  TimestampNs visual_sync_tolerance_ns{10000000};
  double visual_rotation_gain{0.35};
  double visual_position_gain{0.2};
  double min_scale_displacement_m{0.005};
  double min_scale_direction_cosine{0.5};
  // Fixed calibration rotation mapping camera vectors into the IMU/body frame.
  Quaternion body_from_camera_rotation{};
};

struct VisualFusionResult {
  bool rotation_applied{false};
  bool position_applied{false};
  bool scale_updated{false};
  double observed_scale_m{0.0};
};

// Lightweight VIO state layer. It performs strapdown IMU propagation and
// conservative visual corrections. It is intentionally not a replacement for
// the later sliding-window nonlinear optimizer, but already enforces the same
// state/convention contracts and never invents monocular metric scale.
class VioEstimator {
 public:
  explicit VioEstimator(VioConfig config = {});
  bool submitImu(const ImuSample& sample);
  VisualFusionResult fuseVisual(TimestampNs timestamp_ns,
                                const RelativePoseEstimate& relative_pose);
  const VioState& state() const { return state_; }
  bool initialized() const { return state_.status != VioStatus::kUninitialized; }
  void reset();

 private:
  bool tryInitialize();
  void propagate(const ImuSample& previous, const ImuSample& current);
  VioConfig config_;
  VioState state_{};
  std::deque<ImuSample> initialization_buffer_;
  std::optional<ImuSample> previous_imu_;
  std::optional<Pose> previous_visual_world_pose_;
};

}  // namespace arkernel
