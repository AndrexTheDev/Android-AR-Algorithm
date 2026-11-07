#pragma once
#include <array>
#include "arkernel/types.h"

namespace arkernel {

struct ImuBias { Vec3 accelerometer{}; Vec3 gyroscope{}; };
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

  PreintegratedImu corrected(const ImuBias& bias) const;
};

// Midpoint preintegration in the local frame. Biases are explicit so a future
// optimizer can update them without changing the public API.
class ImuPreintegrator {
 public:
  explicit ImuPreintegrator(ImuBias bias = {}) : bias_(bias) { value_.reference_bias = bias; }
  void reset();
  bool add(const ImuSample& sample);
  const PreintegratedImu& value() const { return value_; }
 private:
  ImuBias bias_;
  PreintegratedImu value_;
  ImuSample previous_{};
  bool has_previous_{false};
};

}  // namespace arkernel
