#include "arkernel/visual_frontend.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {

VisualFrontend::VisualFrontend(PinholeRadTanCamera camera, VisualFrontendConfig config)
    : camera_(std::move(camera)), config_(config), detector_(config.detector),
      flow_(config.optical_flow), epipolar_(config.epipolar),
      homography_(config.homography), selector_(config.model_selection),
      pose_solver_(config.pose) {
  if (config_.max_active_features < config_.min_tracked_features ||
      config_.min_tracked_features < 4 || config_.feature_exclusion_radius_px <= 0.0 ||
      config_.keyframe_median_motion_px <= 0.0 ||
      config_.max_frames_between_keyframes == 0 || config_.max_frame_gap_ns <= 0)
    throw std::invalid_argument("Invalid visual frontend configuration");
}

void VisualFrontend::storeImage(const GrayImageView& image) {
  previous_width_ = image.width; previous_height_ = image.height;
  previous_pixels_.resize(static_cast<std::size_t>(image.width)*image.height);
  for (int y=0; y<image.height; ++y)
    std::copy_n(image.data+static_cast<std::size_t>(y)*image.row_stride, image.width,
                previous_pixels_.data()+static_cast<std::size_t>(y)*image.width);
}

GrayImageView VisualFrontend::storedImageView() const {
  return {previous_width_,previous_height_,previous_width_,previous_pixels_.data(),previous_pixels_.size()};
}

void VisualFrontend::replenish(const GrayImageView& image, std::vector<ImageFeature>& retained) {
  const auto candidates=detector_.detect(image);
  const double minimum_distance_squared=config_.feature_exclusion_radius_px*
                                         config_.feature_exclusion_radius_px;
  for (const auto& candidate:candidates) {
    if (retained.size()>=config_.max_active_features) break;
    bool occupied=false;
    for (const auto& existing:retained) {
      const double dx=candidate.x-existing.x,dy=candidate.y-existing.y;
      if (dx*dx+dy*dy<minimum_distance_squared) { occupied=true; break; }
    }
    if (!occupied) {
      auto feature=candidate; feature.id=next_feature_id_++; retained.push_back(feature);
    }
  }
}

VisualFrontendResult VisualFrontend::initialize(const GrayImageView& image,
                                                 TimestampNs timestamp_ns,
                                                 FrontendState state) {
  active_features_.clear(); replenish(image,active_features_); storeImage(image);
  previous_timestamp_=timestamp_ns; frames_since_keyframe_=0;
  VisualFrontendResult result;result.timestamp_ns=timestamp_ns;result.state=state;
  result.active_feature_count=active_features_.size();result.keyframe=true;return result;
}

VisualFrontendResult VisualFrontend::process(
    const GrayImageView& image, TimestampNs timestamp_ns,
    const std::optional<Quaternion>& rotation) {
  if (!image.valid()) throw std::invalid_argument("Invalid visual frontend image");
  if (image.width!=camera_.intrinsics().width || image.height!=camera_.intrinsics().height)
    throw std::invalid_argument("Image dimensions do not match camera calibration");
  if (timestamp_ns<=0) throw std::invalid_argument("Timestamp must be positive");
  if (previous_timestamp_==0) return initialize(image,timestamp_ns,FrontendState::kInitializing);
  if (timestamp_ns<=previous_timestamp_) throw std::invalid_argument("Frame timestamps must increase");
  if (image.width!=previous_width_ || image.height!=previous_height_ ||
      timestamp_ns-previous_timestamp_>config_.max_frame_gap_ns)
    return initialize(image,timestamp_ns,FrontendState::kReinitialized);

  std::vector<Vec2> guesses;
  if (rotation) guesses=predictRotationFlow(active_features_,camera_,*rotation);
  auto tracks=flow_.track(storedImageView(),image,active_features_,guesses);
  std::vector<NormalizedCorrespondence> correspondences;
  std::vector<std::size_t> correspondence_to_track;
  for (std::size_t i=0;i<tracks.size();++i) {
    if (tracks[i].status!=FlowStatus::kTracked) continue;
    const auto previous_ray=camera_.unproject(tracks[i].previous);
    const auto current_ray=camera_.unproject(tracks[i].current);
    if (!previous_ray || !current_ray || previous_ray->z<=1e-9 || current_ray->z<=1e-9) continue;
    correspondences.push_back({{previous_ray->x/previous_ray->z,previous_ray->y/previous_ray->z},
                               {current_ray->x/current_ray->z,current_ray->y/current_ray->z}});
    correspondence_to_track.push_back(i);
  }

  const auto epipolar=epipolar_.verify(correspondences);
  const auto homography=homography_.verify(correspondences);
  const auto selection=selector_.select(epipolar,homography);
  std::vector<std::uint8_t> accepted(correspondences.size(),0);
  if (selection.geometry==SceneGeometry::kGeneral3d) accepted=epipolar.inliers;
  else if (selection.geometry==SceneGeometry::kPlanarOrRotationDominant)
    accepted=homography.valid?homography.inliers:epipolar.inliers;
  else
    for (std::size_t i=0;i<correspondences.size();++i) accepted[i]=1;

  std::vector<ImageFeature> retained;retained.reserve(config_.max_active_features);
  std::vector<double> motions;
  for (std::size_t i=0;i<correspondences.size();++i) if (accepted[i]) {
    auto& track=tracks[correspondence_to_track[i]];
    retained.push_back({static_cast<float>(track.current.x),static_cast<float>(track.current.y),
                        static_cast<float>(std::max(0.0,255.0-track.photometric_error)),track.id});
    motions.push_back(std::hypot(track.current.x-track.previous.x,track.current.y-track.previous.y));
  }

  VisualFrontendResult result;result.timestamp_ns=timestamp_ns;result.tracks=std::move(tracks);
  result.geometry=selection;result.accepted_track_count=retained.size();
  result.accepted_feature_ids.reserve(retained.size());
  for (const auto& feature:retained) result.accepted_feature_ids.push_back(feature.id);
  if (selection.geometry==SceneGeometry::kGeneral3d&&epipolar.valid)
    result.relative_pose=pose_solver_.solve(epipolar.matrix,correspondences,epipolar.inliers);
  result.state=retained.size()>=config_.min_tracked_features&&
      selection.geometry!=SceneGeometry::kInvalid?FrontendState::kTracking:FrontendState::kLimited;

  double median_motion=0.0;
  if (!motions.empty()) { std::sort(motions.begin(),motions.end());median_motion=motions[motions.size()/2]; }
  ++frames_since_keyframe_;
  result.keyframe=median_motion>=config_.keyframe_median_motion_px||
      frames_since_keyframe_>=config_.max_frames_between_keyframes||
      retained.size()<config_.min_tracked_features*2;
  if (result.keyframe) frames_since_keyframe_=0;

  if (retained.size()<config_.min_tracked_features) {
    // Keep the current image as a clean recovery origin; stale tracks are not
    // allowed to poison subsequent geometry.
    active_features_.clear();replenish(image,active_features_);
    result.state=FrontendState::kReinitialized;result.keyframe=true;frames_since_keyframe_=0;
  } else {
    replenish(image,retained);active_features_=std::move(retained);
  }
  storeImage(image);previous_timestamp_=timestamp_ns;
  result.active_feature_count=active_features_.size();return result;
}

std::vector<BinaryFeatureDescriptor> VisualFrontend::activeDescriptors() const {
  if(previous_pixels_.empty())return {};
  return describeFeatures(storedImageView(),active_features_,config_.max_active_features);
}

void VisualFrontend::reset() {
  previous_pixels_.clear();active_features_.clear();previous_width_=previous_height_=0;
  previous_timestamp_=0;next_feature_id_=1;frames_since_keyframe_=0;
}
bool VisualFrontend::reserveFeatureIdsAbove(std::uint32_t feature_id){
  if(feature_id==std::numeric_limits<std::uint32_t>::max())return false;
  next_feature_id_=std::max(next_feature_id_,feature_id+1);return true;
}

}  // namespace arkernel
