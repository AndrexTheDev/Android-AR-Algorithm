#pragma once

#include <cstddef>
#include "arkernel/epipolar_ransac.h"
#include "arkernel/homography_ransac.h"

namespace arkernel {

enum class SceneGeometry {
  kInvalid,
  kGeneral3d,
  kPlanarOrRotationDominant
};

struct GeometrySelectionConfig {
  // If homography explains at least this fraction of epipolar inliers, prefer
  // the conservative planar/rotation state and do not initialize translation.
  double homography_to_epipolar_ratio{0.85};
  std::size_t minimum_epipolar_advantage{8};
};

struct GeometrySelection {
  SceneGeometry geometry{SceneGeometry::kInvalid};
  std::size_t epipolar_inliers{0};
  std::size_t homography_inliers{0};
  double homography_support_ratio{0.0};
};

class GeometryModelSelector {
 public:
  explicit GeometryModelSelector(GeometrySelectionConfig config = {});
  GeometrySelection select(const EpipolarVerification& epipolar,
                           const HomographyVerification& homography) const;
 private:
  GeometrySelectionConfig config_;
};

}  // namespace arkernel
