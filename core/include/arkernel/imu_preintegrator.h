#pragma once
#include <array>
#include "arkernel/types.h"

namespace arkernel {

struct ImuBias { Vec3 accelerometer{}; Vec3 gyroscope{}; };
struct ImuNoise {
  // Continuous-time white-noise densities per sqrt(Hz).
  double accelerometer_mps2_sqrt_hz{0.15};
  double gyroscope_rps_sqrt_hz{0.015};
};
struct PreintegratedImu {
  double duration_s{0.0};
  Quaternion delta_rotation{};
  Vec3 delta_velocity{};
  Vec3 delta_position{};
  std::size_t sample_count{0};
  ImuBias reference_bias{};
  // Row-major first-order derivatives around reference_bias.
  std::array<double,9> rotation_wrt_gyro_bias{};
  std::array<double,9> velocity_wrt_accel_bias{};
  std::array<double,9> velocity_wrt_gyro_bias{};
  std::array<double,9> position_wrt_accel_bias{};
  std::array<double,9> position_wrt_gyro_bias{};
  // Diagonal covariance ordering: rotation xyz, velocity xyz, position xyz.
  std::array<double,9> covariance_diagonal{};

  PreintegratedImu corrected(const ImuBias& bias) const;
};

// Midpoint preintegration in the local frame. Biases are explicit so a future
// optimizer can update them without changing the public API.
class ImuPreintegrator {
 public:
  explicit ImuPreintegrator(ImuBias bias = {}, ImuNoise noise = {});
  void reset();
  bool add(const ImuSample& sample);
  const PreintegratedImu& value() const { return value_; }
 private:
  ImuBias bias_;
  ImuNoise noise_;
  PreintegratedImu value_;
  ImuSample previous_{};
  bool has_previous_{false};
};

}  // namespace arkernel
