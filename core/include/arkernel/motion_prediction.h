#pragma once

#include <vector>
#include "arkernel/camera_model.h"
#include "arkernel/feature_detector.h"

namespace arkernel {

// Predicts feature locations caused by camera rotation. rotation_current_from_previous
// rotates a ray expressed in the previous camera frame into the current camera frame.
// Invalid projections fall back to the original pixel so callers retain index alignment.
std::vector<Vec2> predictRotationFlow(
    const std::vector<ImageFeature>& features,
    const PinholeRadTanCamera& camera,
    const Quaternion& rotation_current_from_previous);

}  // namespace arkernel
