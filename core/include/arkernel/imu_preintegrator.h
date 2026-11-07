#pragma once
#include "arkernel/types.h"

namespace arkernel {

struct ImuBias { Vec3 accelerometer{}; Vec3 gyroscope{}; };
struct PreintegratedImu {
  double duration_s{0.0};
  Quaternion delta_rotation{};
  Vec3 delta_velocity{};
  Vec3 delta_position{};
  std::size_t sample_count{0};
};

// Midpoint preintegration in the local frame. Biases are explicit so a future
// optimizer can update them without changing the public API.
class ImuPreintegrator {
 public:
  explicit ImuPreintegrator(ImuBias bias = {}) : bias_(bias) {}
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
