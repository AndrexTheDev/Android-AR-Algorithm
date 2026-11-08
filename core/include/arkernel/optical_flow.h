#pragma once

#include <cstdint>
#include <vector>
#include "arkernel/camera_model.h"
#include "arkernel/feature_detector.h"

namespace arkernel {

class GrayImagePyramid {
 public:
  GrayImagePyramid(const GrayImageView& source, int requested_levels);
  int levels() const { return static_cast<int>(images_.size()); }
  GrayImageView level(int index) const;

 private:
  struct Image { int width{}, height{}; std::vector<std::uint8_t> pixels; };
  std::vector<Image> images_;
};

enum class FlowStatus {
  kTracked,
  kOutOfBounds,
  kLowTexture,
  kNotConverged,
  kForwardBackwardMismatch
};

struct TrackedFeature {
  std::uint32_t id{0};
  Vec2 previous{};
  Vec2 current{};
  FlowStatus status{FlowStatus::kNotConverged};
  double photometric_error{0.0};
  double forward_backward_error{0.0};
};

struct OpticalFlowConfig {
  int pyramid_levels{4};
  int window_radius{4};
  int max_iterations{15};
  double convergence_epsilon{0.01};
  double min_gradient_eigenvalue{4.0};
  double max_photometric_error{35.0};
  double max_forward_backward_error{1.0};
};

// Sparse coarse-to-fine Lucas-Kanade tracker. Initial guesses can come from
// IMU rotation prediction; when omitted, zero image motion is assumed.
class PyramidalLucasKanade {
 public:
  explicit PyramidalLucasKanade(OpticalFlowConfig config = {});
  std::vector<TrackedFeature> track(
      const GrayImageView& previous,
      const GrayImageView& current,
      const std::vector<ImageFeature>& features,
      const std::vector<Vec2>& initial_guesses = {}) const;

 private:
  struct SingleResult { Vec2 point; FlowStatus status; double error; };
  SingleResult trackOne(const GrayImagePyramid& from,
                        const GrayImagePyramid& to,
                        Vec2 from_point,
                        Vec2 initial_to_point) const;
  OpticalFlowConfig config_;
};

}  // namespace arkernel
