#include "arkernel/optical_flow.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace arkernel {
namespace {

double sample(const GrayImageView& image, double x, double y) {
  const int x0 = static_cast<int>(std::floor(x));
  const int y0 = static_cast<int>(std::floor(y));
  const double ax = x - x0;
  const double ay = y - y0;
  const double top = (1.0-ax)*image.at(x0,y0) + ax*image.at(x0+1,y0);
  const double bottom = (1.0-ax)*image.at(x0,y0+1) + ax*image.at(x0+1,y0+1);
  return (1.0-ay)*top + ay*bottom;
}

bool patchInside(const GrayImageView& image, const Vec2& p, int radius) {
  // One pixel for central gradients plus one for bilinear x0+1/y0+1.
  const double margin = radius + 2.0;
  return p.x >= margin && p.y >= margin &&
         p.x < image.width-margin && p.y < image.height-margin;
}

}  // namespace

GrayImagePyramid::GrayImagePyramid(const GrayImageView& source, int requested_levels) {
  if (!source.valid()) throw std::invalid_argument("Invalid pyramid source image");
  if (requested_levels < 1) throw std::invalid_argument("Pyramid must contain a level");
  Image base;
  base.width = source.width; base.height = source.height;
  base.pixels.resize(static_cast<std::size_t>(base.width)*base.height);
  for (int y=0; y<base.height; ++y)
    std::copy_n(source.data + static_cast<std::size_t>(y)*source.row_stride,
                base.width, base.pixels.data() + static_cast<std::size_t>(y)*base.width);
  images_.push_back(std::move(base));

  for (int level_index=1; level_index<requested_levels; ++level_index) {
    const auto& parent = images_.back();
    if (parent.width < 16 || parent.height < 16) break;
    Image child;
    child.width = parent.width/2; child.height = parent.height/2;
    child.pixels.resize(static_cast<std::size_t>(child.width)*child.height);
    for (int y=0; y<child.height; ++y) for (int x=0; x<child.width; ++x) {
      const int px = 2*x, py = 2*y;
      const int sum = parent.pixels[static_cast<std::size_t>(py)*parent.width+px] +
          parent.pixels[static_cast<std::size_t>(py)*parent.width+px+1] +
          parent.pixels[static_cast<std::size_t>(py+1)*parent.width+px] +
          parent.pixels[static_cast<std::size_t>(py+1)*parent.width+px+1];
      child.pixels[static_cast<std::size_t>(y)*child.width+x] =
          static_cast<std::uint8_t>((sum+2)/4);
    }
    images_.push_back(std::move(child));
  }
}

GrayImageView GrayImagePyramid::level(int index) const {
  if (index < 0 || index >= levels()) throw std::out_of_range("Invalid pyramid level");
  const auto& image = images_[static_cast<std::size_t>(index)];
  return {image.width, image.height, image.width, image.pixels.data(), image.pixels.size()};
}

PyramidalLucasKanade::PyramidalLucasKanade(OpticalFlowConfig config) : config_(config) {
  if (config_.pyramid_levels < 1 || config_.window_radius < 2 ||
      config_.max_iterations < 1 || config_.convergence_epsilon <= 0.0 ||
      config_.min_gradient_eigenvalue <= 0.0 || config_.max_photometric_error <= 0.0 ||
      config_.max_forward_backward_error <= 0.0)
    throw std::invalid_argument("Invalid optical flow configuration");
}

PyramidalLucasKanade::SingleResult PyramidalLucasKanade::trackOne(
    const GrayImagePyramid& from, const GrayImagePyramid& to,
    Vec2 from_point, Vec2 initial_to_point) const {
  const int levels = std::min(from.levels(), to.levels());
  Vec2 estimate{};
  double final_error = 0.0;
  bool converged_any = false;
  bool started = false;

  for (int level_index=levels-1; level_index>=0; --level_index) {
    const double scale = std::ldexp(1.0, -level_index);
    const Vec2 source{from_point.x*scale, from_point.y*scale};
    if (!started)
      estimate = {initial_to_point.x*scale, initial_to_point.y*scale};
    else { estimate.x *= 2.0; estimate.y *= 2.0; }
    const auto a = from.level(level_index);
    const auto b = to.level(level_index);
    if (!patchInside(a, source, config_.window_radius) ||
        !patchInside(b, estimate, config_.window_radius)) {
      // A valid fine-level feature can be too close to the border in a tiny
      // coarse image. Start at the first usable scale rather than losing it.
      if (!started) continue;
      return {estimate, FlowStatus::kOutOfBounds, final_error};
    }
    started = true;

    bool level_converged = false;
    for (int iteration=0; iteration<config_.max_iterations; ++iteration) {
      if (!patchInside(b, estimate, config_.window_radius))
        return {estimate, FlowStatus::kOutOfBounds, final_error};
      double gxx=0.0, gxy=0.0, gyy=0.0, bx=0.0, by=0.0, abs_error=0.0;
      int count=0;
      for (int dy=-config_.window_radius; dy<=config_.window_radius; ++dy) {
        for (int dx=-config_.window_radius; dx<=config_.window_radius; ++dx) {
          const double reference = sample(a, source.x+dx, source.y+dy);
          const double x = estimate.x+dx, y = estimate.y+dy;
          const double observed = sample(b,x,y);
          const double gx = 0.5*(sample(b,x+1.0,y)-sample(b,x-1.0,y));
          const double gy = 0.5*(sample(b,x,y+1.0)-sample(b,x,y-1.0));
          const double residual = observed-reference;
          gxx += gx*gx; gxy += gx*gy; gyy += gy*gy;
          bx += gx*residual; by += gy*residual;
          abs_error += std::abs(residual); ++count;
        }
      }
      const double trace = gxx+gyy;
      const double determinant = gxx*gyy-gxy*gxy;
      const double discriminant = std::sqrt(std::max(0.0, trace*trace-4.0*determinant));
      const double min_eigenvalue = 0.5*(trace-discriminant)/count;
      if (determinant <= 1e-9 || min_eigenvalue < config_.min_gradient_eigenvalue)
        return {estimate, FlowStatus::kLowTexture, abs_error/count};
      const double step_x = (-gyy*bx + gxy*by)/determinant;
      const double step_y = ( gxy*bx - gxx*by)/determinant;
      estimate.x += step_x; estimate.y += step_y;
      final_error = abs_error/count;
      if (step_x*step_x + step_y*step_y <=
          config_.convergence_epsilon*config_.convergence_epsilon) {
        level_converged = true; converged_any = true; break;
      }
    }
    if (!level_converged && level_index == 0)
      return {estimate, FlowStatus::kNotConverged, final_error};
  }
  if (!started) return {estimate, FlowStatus::kOutOfBounds, final_error};
  if (!converged_any) return {estimate, FlowStatus::kNotConverged, final_error};
  if (final_error > config_.max_photometric_error)
    return {estimate, FlowStatus::kNotConverged, final_error};
  return {estimate, FlowStatus::kTracked, final_error};
}

std::vector<TrackedFeature> PyramidalLucasKanade::track(
    const GrayImageView& previous, const GrayImageView& current,
    const std::vector<ImageFeature>& features,
    const std::vector<Vec2>& initial_guesses) const {
  if (!previous.valid() || !current.valid() || previous.width != current.width ||
      previous.height != current.height)
    throw std::invalid_argument("Optical-flow images must be valid and equally sized");
  if (!initial_guesses.empty() && initial_guesses.size() != features.size())
    throw std::invalid_argument("Initial guess count must match feature count");
  GrayImagePyramid previous_pyramid(previous, config_.pyramid_levels);
  GrayImagePyramid current_pyramid(current, config_.pyramid_levels);
  std::vector<TrackedFeature> output;
  output.reserve(features.size());
  for (std::size_t i=0; i<features.size(); ++i) {
    const Vec2 from{features[i].x, features[i].y};
    const Vec2 guess = initial_guesses.empty() ? from : initial_guesses[i];
    const auto forward = trackOne(previous_pyramid, current_pyramid, from, guess);
    TrackedFeature tracked{features[i].id, from, forward.point, forward.status,
                           forward.error, 0.0};
    if (forward.status == FlowStatus::kTracked) {
      const auto backward = trackOne(current_pyramid, previous_pyramid,
                                     forward.point, from);
      if (backward.status != FlowStatus::kTracked) {
        tracked.status = backward.status;
      } else {
        tracked.forward_backward_error = std::hypot(backward.point.x-from.x,
                                                     backward.point.y-from.y);
        if (tracked.forward_backward_error > config_.max_forward_backward_error)
          tracked.status = FlowStatus::kForwardBackwardMismatch;
      }
    }
    output.push_back(tracked);
  }
  return output;
}

}  // namespace arkernel
