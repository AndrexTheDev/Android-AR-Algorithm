#include "arkernel/camera_model.h"
#include <cmath>
#include <stdexcept>

namespace arkernel {

PinholeRadTanCamera::PinholeRadTanCamera(CameraIntrinsics intrinsics,
                                         RadTanDistortion distortion)
    : intrinsics_(intrinsics), distortion_(distortion) {
  if (!intrinsics_.valid()) throw std::invalid_argument("Invalid camera intrinsics");
}

Vec2 PinholeRadTanCamera::distort(const Vec2& n) const {
  const double x2 = n.x * n.x;
  const double y2 = n.y * n.y;
  const double xy = n.x * n.y;
  const double r2 = x2 + y2;
  const double radial = 1.0 + distortion_.k1*r2 + distortion_.k2*r2*r2 +
                        distortion_.k3*r2*r2*r2;
  return {n.x*radial + 2.0*distortion_.p1*xy + distortion_.p2*(r2 + 2.0*x2),
          n.y*radial + distortion_.p1*(r2 + 2.0*y2) + 2.0*distortion_.p2*xy};
}

std::optional<Vec2> PinholeRadTanCamera::project(const Vec3& p) const {
  if (!std::isfinite(p.x) || !std::isfinite(p.y) || !std::isfinite(p.z) || p.z <= 1e-9)
    return std::nullopt;
  const Vec2 d = distort({p.x / p.z, p.y / p.z});
  return Vec2{intrinsics_.fx*d.x + intrinsics_.cx,
              intrinsics_.fy*d.y + intrinsics_.cy};
}

std::optional<Vec3> PinholeRadTanCamera::unproject(const Vec2& pixel, int iterations) const {
  if (!std::isfinite(pixel.x) || !std::isfinite(pixel.y) || iterations < 1)
    return std::nullopt;
  const Vec2 observed{(pixel.x - intrinsics_.cx) / intrinsics_.fx,
                      (pixel.y - intrinsics_.cy) / intrinsics_.fy};
  Vec2 estimate = observed;
  // Fixed-point inversion is stable for calibrated mobile lenses in their
  // valid image region. Reject divergence instead of returning a bogus ray.
  for (int i = 0; i < iterations; ++i) {
    const Vec2 current = distort(estimate);
    estimate.x += observed.x - current.x;
    estimate.y += observed.y - current.y;
    if (!std::isfinite(estimate.x) || !std::isfinite(estimate.y) ||
        std::abs(estimate.x) > 10.0 || std::abs(estimate.y) > 10.0)
      return std::nullopt;
  }
  const double norm = std::sqrt(estimate.x*estimate.x + estimate.y*estimate.y + 1.0);
  return Vec3{estimate.x/norm, estimate.y/norm, 1.0/norm};
}

bool PinholeRadTanCamera::isInside(const Vec2& p, double border) const {
  return border >= 0.0 && p.x >= border && p.y >= border &&
         p.x < static_cast<double>(intrinsics_.width) - border &&
         p.y < static_cast<double>(intrinsics_.height) - border;
}

}  // namespace arkernel
