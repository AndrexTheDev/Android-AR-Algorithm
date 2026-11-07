#include "arkernel/imu_preintegrator.h"

namespace arkernel {

void ImuPreintegrator::reset() {
  value_ = {};
  previous_ = {};
  has_previous_ = false;
}

bool ImuPreintegrator::add(const ImuSample& sample) {
  if (!has_previous_) {
    previous_ = sample;
    has_previous_ = true;
    value_.sample_count = 1;
    return true;
  }
  if (sample.timestamp_ns <= previous_.timestamp_ns) return false;
  const double dt = static_cast<double>(sample.timestamp_ns - previous_.timestamp_ns) * 1e-9;
  // Reject gaps: integrating stale/paused sensors is worse than declaring loss.
  if (dt > 0.1) { previous_ = sample; return false; }

  const Vec3 gyro = (previous_.angular_velocity_rps + sample.angular_velocity_rps) * 0.5
                    - bias_.gyroscope;
  const Vec3 accel = (previous_.acceleration_mps2 + sample.acceleration_mps2) * 0.5
                     - bias_.accelerometer;
  const Vec3 accel_local = value_.delta_rotation.rotate(accel);
  value_.delta_position += value_.delta_velocity * dt + accel_local * (0.5 * dt * dt);
  value_.delta_velocity += accel_local * dt;
  value_.delta_rotation = (value_.delta_rotation *
      Quaternion::fromRotationVector(gyro * dt)).normalized();
  value_.duration_s += dt;
  ++value_.sample_count;
  previous_ = sample;
  return true;
}

}  // namespace arkernel
