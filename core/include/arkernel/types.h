#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace arkernel {

using TimestampNs = std::int64_t;

struct Vec3 {
  double x{0.0}, y{0.0}, z{0.0};
  Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
  Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
  Vec3 operator*(double s) const { return {x * s, y * s, z * s}; }
  Vec3& operator+=(const Vec3& o) { x += o.x; y += o.y; z += o.z; return *this; }
  double norm() const { return std::sqrt(x*x + y*y + z*z); }
};

struct Quaternion {
  double w{1.0}, x{0.0}, y{0.0}, z{0.0};

  Quaternion normalized() const {
    const double n = std::sqrt(w*w + x*x + y*y + z*z);
    return n > 1e-12 ? Quaternion{w/n, x/n, y/n, z/n} : Quaternion{};
  }
  Quaternion operator*(const Quaternion& o) const {
    return {w*o.w - x*o.x - y*o.y - z*o.z,
            w*o.x + x*o.w + y*o.z - z*o.y,
            w*o.y - x*o.z + y*o.w + z*o.x,
            w*o.z + x*o.y - y*o.x + z*o.w};
  }
  Vec3 rotate(const Vec3& v) const {
    const Quaternion q = normalized();
    const Quaternion p{0.0, v.x, v.y, v.z};
    const Quaternion inv{q.w, -q.x, -q.y, -q.z};
    const Quaternion r = q * p * inv;
    return {r.x, r.y, r.z};
  }
  static Quaternion fromRotationVector(const Vec3& rv) {
    const double angle = rv.norm();
    if (angle < 1e-12) return {1.0, rv.x*0.5, rv.y*0.5, rv.z*0.5};
    const double s = std::sin(angle*0.5) / angle;
    return {std::cos(angle*0.5), rv.x*s, rv.y*s, rv.z*s};
  }
};

struct Pose {
  Quaternion rotation{};
  Vec3 translation{};
};

struct CameraIntrinsics {
  int width{0}, height{0};
  double fx{0.0}, fy{0.0}, cx{0.0}, cy{0.0};
  bool valid() const { return width > 0 && height > 0 && fx > 0.0 && fy > 0.0; }
};

enum class PixelFormat { kGray8, kYuv420, kRgba8888 };
struct CameraFrame {
  TimestampNs timestamp_ns{0};
  int width{0}, height{0}, row_stride{0};
  PixelFormat format{PixelFormat::kGray8};
  const std::uint8_t* data{nullptr};
  std::size_t data_size{0};
};

struct ImuSample {
  TimestampNs timestamp_ns{0};
  Vec3 acceleration_mps2{};
  Vec3 angular_velocity_rps{};
};

enum class TrackingState { kUninitialized, kTracking, kLimited, kLost };
struct TrackingEstimate {
  TimestampNs timestamp_ns{0};
  Pose world_from_camera{};
  TrackingState state{TrackingState::kUninitialized};
  int tracked_features{0};
  int inliers{0};
  double reprojection_error_px{0.0};
  double confidence{0.0};
};

struct Detection {
  std::string label;
  float confidence{0.0F};
  float left{0.0F}, top{0.0F}, right{0.0F}, bottom{0.0F};
  std::int64_t track_id{-1};
};

struct DepthMap {
  int width{0}, height{0};
  std::vector<float> depth_m;
  float confidence{0.0F};
};

struct FrameResult {
  TrackingEstimate tracking{};
  std::vector<Detection> detections;
  DepthMap depth;
  bool semantics_updated{false};
  bool depth_updated{false};
};

}  // namespace arkernel
