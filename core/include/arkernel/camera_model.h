#pragma once

#include <optional>
#include "arkernel/types.h"

namespace arkernel {

struct Vec2 { double x{0.0}, y{0.0}; };

// Brown-Conrady radial-tangential lens distortion. Coefficients are in the
// normalized image plane and match the common OpenCV ordering.
struct RadTanDistortion {
  double k1{0.0}, k2{0.0}, p1{0.0}, p2{0.0}, k3{0.0};
};

class PinholeRadTanCamera {
 public:
  PinholeRadTanCamera(CameraIntrinsics intrinsics, RadTanDistortion distortion = {});

  const CameraIntrinsics& intrinsics() const { return intrinsics_; }
  std::optional<Vec2> project(const Vec3& camera_point) const;
  std::optional<Vec3> unproject(const Vec2& pixel, int iterations = 8) const;
  bool isInside(const Vec2& pixel, double border = 0.0) const;

 private:
  Vec2 distort(const Vec2& normalized) const;
  CameraIntrinsics intrinsics_;
  RadTanDistortion distortion_;
};

}  // namespace arkernel
