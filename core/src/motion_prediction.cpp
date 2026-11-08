#include "arkernel/motion_prediction.h"

namespace arkernel {

std::vector<Vec2> predictRotationFlow(
    const std::vector<ImageFeature>& features,
    const PinholeRadTanCamera& camera,
    const Quaternion& rotation_current_from_previous) {
  std::vector<Vec2> predictions;
  predictions.reserve(features.size());
  for (const auto& feature : features) {
    const Vec2 original{feature.x, feature.y};
    const auto ray = camera.unproject(original);
    if (!ray) { predictions.push_back(original); continue; }
    const auto pixel = camera.project(rotation_current_from_previous.rotate(*ray));
    predictions.push_back(pixel ? *pixel : original);
  }
  return predictions;
}

}  // namespace arkernel
