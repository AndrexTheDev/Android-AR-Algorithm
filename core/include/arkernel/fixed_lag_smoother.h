#pragma once

#include <cstddef>
#include <deque>
#include <optional>
#include "arkernel/essential_pose.h"
#include "arkernel/imu_preintegrator.h"
#include "arkernel/vio_estimator.h"

namespace arkernel {

struct FixedLagConfig {
  std::size_t max_keyframes{10};
  int optimization_iterations{6};
  double imu_position_weight{1.0};
  double imu_velocity_weight{0.5};
  double imu_rotation_weight{1.0};
  double visual_rotation_weight{0.7};
  double visual_direction_weight{0.4};
  double huber_delta_position_m{0.15};
  double huber_delta_rotation_rad{0.12};
  double update_gain{0.35};
  bool use_imu_covariance{true};
  double rotation_variance_floor{1e-5};
  double velocity_variance_floor{1e-3};
  double position_variance_floor{1e-4};
  Vec3 gravity_world_mps2{0.0,0.0,-9.80665};
};

struct SmootherKeyframe {
  TimestampNs timestamp_ns{0};
  Pose world_from_body{};
  Vec3 velocity_world_mps{};
  Vec3 accelerometer_bias_mps2{};
  Vec3 gyroscope_bias_rps{};
};

struct SmootherDiagnostics {
  double initial_cost{0.0};
  double final_cost{0.0};
  std::size_t keyframe_count{0};
  std::size_t imu_factor_count{0};
  std::size_t visual_factor_count{0};
  std::size_t downweighted_residual_count{0};
  std::size_t covariance_downweighted_factor_count{0};
  std::size_t marginalized_keyframe_count{0};
};

// Bounded fixed-lag smoother for adjacent keyframes. The oldest state is the
// fixed prior. Overflow promotes the next optimized state to the new prior,
// keeping memory and runtime bounded. Bias Jacobians and full Schur-complement
// marginalization are deliberately left for the nonlinear backend phase.
class FixedLagSmoother {
 public:
  explicit FixedLagSmoother(FixedLagConfig config = {});
  bool addKeyframe(const VioState& state,
                   const std::optional<PreintegratedImu>& imu_from_previous = std::nullopt,
                   const std::optional<RelativePoseEstimate>& visual_from_previous = std::nullopt);
  SmootherDiagnostics optimize();
  const std::deque<SmootherKeyframe>& keyframes() const { return keyframes_; }
  std::size_t marginalizedCount() const { return marginalized_count_; }
  void reset();

 private:
  struct Edge {
    PreintegratedImu imu;
    std::optional<RelativePoseEstimate> visual;
  };
  double calculateCost(std::size_t* downweighted = nullptr) const;
  FixedLagConfig config_;
  std::deque<SmootherKeyframe> keyframes_;
  std::deque<Edge> edges_;
  std::size_t marginalized_count_{0};
};

}  // namespace arkernel
