#include "arkernel/geometry_model_selector.h"
#include <stdexcept>

namespace arkernel {

GeometryModelSelector::GeometryModelSelector(GeometrySelectionConfig config):config_(config){
  if(config_.homography_to_epipolar_ratio<=0.0||config_.homography_to_epipolar_ratio>1.0)
    throw std::invalid_argument("Invalid geometry selection configuration");
}

GeometrySelection GeometryModelSelector::select(const EpipolarVerification& epipolar,
                                                  const HomographyVerification& homography) const {
  GeometrySelection result;
  result.epipolar_inliers=epipolar.inlier_count;
  result.homography_inliers=homography.inlier_count;
  if(!epipolar.valid&&!homography.valid)return result;
  if(!epipolar.valid){result.geometry=SceneGeometry::kPlanarOrRotationDominant;return result;}
  result.homography_support_ratio=epipolar.inlier_count?
      static_cast<double>(homography.inlier_count)/epipolar.inlier_count:0.0;
  const bool homography_competitive=homography.valid&&
      result.homography_support_ratio>=config_.homography_to_epipolar_ratio;
  const bool epipolar_decisive=epipolar.inlier_count>=homography.inlier_count+
      config_.minimum_epipolar_advantage;
  result.geometry=(homography_competitive||!epipolar_decisive)?
      SceneGeometry::kPlanarOrRotationDominant:SceneGeometry::kGeneral3d;
  return result;
}

}  // namespace arkernel
