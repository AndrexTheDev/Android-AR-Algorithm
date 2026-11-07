#include "arkernel/imu_preintegrator.h"

namespace arkernel {
namespace {
using Matrix3 = std::array<double,9>;

Matrix3 identity() { return {1,0,0,0,1,0,0,0,1}; }
Matrix3 multiply(const Matrix3& a,const Matrix3& b) {
  Matrix3 c{};
  for(int r=0;r<3;++r)for(int col=0;col<3;++col)
    for(int k=0;k<3;++k)c[r*3+col]+=a[r*3+k]*b[k*3+col];
  return c;
}
Vec3 multiply(const Matrix3& a,const Vec3& v) {
  return {a[0]*v.x+a[1]*v.y+a[2]*v.z,
          a[3]*v.x+a[4]*v.y+a[5]*v.z,
          a[6]*v.x+a[7]*v.y+a[8]*v.z};
}
Matrix3 scaled(Matrix3 value,double scale) {
  for(double& element:value)element*=scale;
  return value;
}
void addScaled(Matrix3& target,const Matrix3& value,double scale) {
  for(std::size_t i=0;i<target.size();++i)target[i]+=value[i]*scale;
}
Matrix3 rotationMatrix(const Quaternion& q) {
  const Vec3 x=q.rotate({1,0,0}),y=q.rotate({0,1,0}),z=q.rotate({0,0,1});
  return {x.x,y.x,z.x,x.y,y.y,z.y,x.z,y.z,z.z};
}
Matrix3 skew(const Vec3& v) { return {0,-v.z,v.y,v.z,0,-v.x,-v.y,v.x,0}; }
}  // namespace

PreintegratedImu PreintegratedImu::corrected(const ImuBias& bias) const {
  PreintegratedImu result=*this;
  const Vec3 delta_accel=bias.accelerometer-reference_bias.accelerometer;
  const Vec3 delta_gyro=bias.gyroscope-reference_bias.gyroscope;
  result.delta_rotation=(delta_rotation*Quaternion::fromRotationVector(
      multiply(rotation_wrt_gyro_bias,delta_gyro))).normalized();
  result.delta_velocity=delta_velocity+multiply(velocity_wrt_accel_bias,delta_accel)+
                        multiply(velocity_wrt_gyro_bias,delta_gyro);
  result.delta_position=delta_position+multiply(position_wrt_accel_bias,delta_accel)+
                        multiply(position_wrt_gyro_bias,delta_gyro);
  result.reference_bias=bias;
  return result;
}

void ImuPreintegrator::reset() {
  value_ = {};
  value_.reference_bias = bias_;
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
  const Matrix3 rotation=rotationMatrix(value_.delta_rotation);
  const Matrix3 negative_rotation=scaled(rotation,-1.0);
  Matrix3 midpoint_rotation_jacobian=value_.rotation_wrt_gyro_bias;
  addScaled(midpoint_rotation_jacobian,identity(),-0.5*dt);
  const Matrix3 accel_wrt_gyro=scaled(
      multiply(multiply(rotation,skew(accel)),midpoint_rotation_jacobian),-1.0);

  // Position Jacobians use velocity Jacobians from the beginning of the step.
  addScaled(value_.position_wrt_accel_bias,value_.velocity_wrt_accel_bias,dt);
  addScaled(value_.position_wrt_accel_bias,negative_rotation,0.5*dt*dt);
  addScaled(value_.position_wrt_gyro_bias,value_.velocity_wrt_gyro_bias,dt);
  addScaled(value_.position_wrt_gyro_bias,accel_wrt_gyro,0.5*dt*dt);
  addScaled(value_.velocity_wrt_accel_bias,negative_rotation,dt);
  addScaled(value_.velocity_wrt_gyro_bias,accel_wrt_gyro,dt);
  addScaled(value_.rotation_wrt_gyro_bias,identity(),-dt);

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
