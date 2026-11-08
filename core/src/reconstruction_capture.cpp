#include "arkernel/reconstruction_capture.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
constexpr double kPi = 3.14159265358979323846;
bool finite(double v) { return std::isfinite(v); }
bool finite(const Vec3& v) { return finite(v.x) && finite(v.y) && finite(v.z); }
bool validPose(const Pose& pose) {
  const auto& q=pose.rotation;
  const double n=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
  return finite(pose.translation)&&finite(q.w)&&finite(q.x)&&finite(q.y)&&finite(q.z)&&n>1e-9;
}
double rotationDegrees(const Quaternion& a,const Quaternion& b) {
  const Quaternion qa=a.normalized(),qb=b.normalized();
  const double dot=std::abs(qa.w*qb.w+qa.x*qb.x+qa.y*qb.y+qa.z*qb.z);
  return 2.0*std::acos(std::min(1.0,std::max(0.0,dot)))*180.0/kPi;
}
}  // namespace

ReconstructionCapturePlanner::ReconstructionCapturePlanner(ScanMode mode,
    CaptureQualityConfig config):mode_(mode),config_(config) {
  const bool valid=config_.max_keyframes>0&&config_.min_interval_ns>=0&&
    finite(config_.min_translation_m)&&config_.min_translation_m>=0&&
    finite(config_.min_rotation_degrees)&&config_.min_rotation_degrees>=0&&
    finite(config_.min_sharpness)&&config_.min_sharpness>=0&&config_.min_sharpness<=1&&
    finite(config_.max_clipped_pixel_ratio)&&config_.max_clipped_pixel_ratio>=0&&
    config_.max_clipped_pixel_ratio<=1&&config_.min_tracked_features>0&&
    finite(config_.max_translation_sigma_m)&&config_.max_translation_sigma_m>0&&
    finite(config_.max_rotation_sigma_degrees)&&config_.max_rotation_sigma_degrees>0&&
    finite(config_.min_object_foreground_ratio)&&finite(config_.max_object_foreground_ratio)&&
    config_.min_object_foreground_ratio>=0&&
    config_.max_object_foreground_ratio>config_.min_object_foreground_ratio&&
    config_.max_object_foreground_ratio<=1&&finite(config_.min_object_distance_m)&&
    finite(config_.max_object_distance_m)&&config_.min_object_distance_m>0&&
    config_.max_object_distance_m>config_.min_object_distance_m&&
    finite(config_.max_object_center_shift_m)&&config_.max_object_center_shift_m>=0&&
    config_.object_azimuth_bins>0&&config_.object_elevation_bins>0&&
    config_.object_azimuth_bins<=360&&config_.object_elevation_bins<=180&&
    config_.object_azimuth_bins<=std::numeric_limits<std::size_t>::max()/config_.object_elevation_bins;
  if(!valid)throw std::invalid_argument("Invalid reconstruction capture configuration");
  object_bins_.assign(config_.object_azimuth_bins*config_.object_elevation_bins,false);
}

std::optional<std::size_t> ReconstructionCapturePlanner::objectBin(
    const ReconstructionFrameCandidate& candidate) const {
  if(!candidate.object_center_world_m||!finite(*candidate.object_center_world_m))return std::nullopt;
  const Vec3 delta=candidate.world_from_camera.translation-*candidate.object_center_world_m;
  const double distance=delta.norm();
  if(!finite(distance)||distance<config_.min_object_distance_m||
     distance>config_.max_object_distance_m)return std::nullopt;
  double azimuth=std::atan2(delta.y,delta.x);
  if(azimuth<0)azimuth+=2.0*kPi;
  const double elevation=std::asin(std::max(-1.0,std::min(1.0,delta.z/distance)));
  const auto az=std::min(config_.object_azimuth_bins-1,
    static_cast<std::size_t>(azimuth/(2.0*kPi)*config_.object_azimuth_bins));
  const auto el=std::min(config_.object_elevation_bins-1,
    static_cast<std::size_t>((elevation+kPi/2.0)/kPi*config_.object_elevation_bins));
  return el*config_.object_azimuth_bins+az;
}

CaptureDecision ReconstructionCapturePlanner::consider(
    const ReconstructionFrameCandidate& c) {
  CaptureDecision result;
  if(pending_reservation_){result.rejection=CaptureRejection::kInvalid;return result;}
  if(c.timestamp_ns<=0||c.frame_id==0||c.image_reference.empty()||!validPose(c.world_from_camera)||
     !finite(c.sharpness)||c.sharpness<0||c.sharpness>1||
     !finite(c.clipped_pixel_ratio)||c.clipped_pixel_ratio<0||c.clipped_pixel_ratio>1||
     !finite(c.translation_sigma_m)||c.translation_sigma_m<0||
     !finite(c.rotation_sigma_degrees)||c.rotation_sigma_degrees<0||
     !finite(c.object_foreground_ratio)||c.object_foreground_ratio<0||
     c.object_foreground_ratio>1){result.rejection=CaptureRejection::kInvalid;return result;}
  if(last_seen_timestamp_ns_!=0&&c.timestamp_ns<=last_seen_timestamp_ns_){
    result.rejection=CaptureRejection::kNonMonotonicTimestamp;return result;}
  last_seen_timestamp_ns_=c.timestamp_ns;
  if(c.tracking_state!=TrackingState::kTracking){result.rejection=CaptureRejection::kTrackingUnreliable;return result;}
  if(c.sharpness<config_.min_sharpness){result.rejection=CaptureRejection::kBlurred;return result;}
  if(c.clipped_pixel_ratio>config_.max_clipped_pixel_ratio){result.rejection=CaptureRejection::kExposureClipped;return result;}
  if(c.tracked_features<config_.min_tracked_features){result.rejection=CaptureRejection::kTooFewFeatures;return result;}
  if(c.translation_sigma_m>config_.max_translation_sigma_m||
     c.rotation_sigma_degrees>config_.max_rotation_sigma_degrees){
    result.rejection=CaptureRejection::kPoseUncertain;return result;}
  std::optional<std::size_t> bin;
  if(mode_==ScanMode::kObject){
    if(!c.object_center_world_m||c.object_foreground_ratio<config_.min_object_foreground_ratio||
       c.object_foreground_ratio>config_.max_object_foreground_ratio||
       (reference_object_center_world_m_&&
        (*c.object_center_world_m-*reference_object_center_world_m_).norm()>
          config_.max_object_center_shift_m)){
      result.rejection=CaptureRejection::kObjectRoiInvalid;return result;}
    bin=objectBin(c);
    if(!bin){result.rejection=CaptureRejection::kObjectDistanceInvalid;return result;}
  }
  if(!keyframes_.empty()){
    const auto& previous=keyframes_.back().frame;
    if(c.timestamp_ns-previous.timestamp_ns<config_.min_interval_ns){
      result.rejection=CaptureRejection::kInsufficientMotion;return result;}
    const double translation=(c.world_from_camera.translation-
      previous.world_from_camera.translation).norm();
    const double rotation=rotationDegrees(c.world_from_camera.rotation,
                                           previous.world_from_camera.rotation);
    if(translation<config_.min_translation_m&&rotation<config_.min_rotation_degrees&&
       (!bin||object_bins_[*bin])){
      result.rejection=CaptureRejection::kInsufficientMotion;return result;}
  }
  if(keyframes_.size()>=config_.max_keyframes){result.rejection=CaptureRejection::kCapacityReached;return result;}
  const double feature_score=std::min(1.0,static_cast<double>(c.tracked_features)/
                                           (2.0*config_.min_tracked_features));
  const double exposure_score=1.0-c.clipped_pixel_ratio;
  const double translation_score=1.0-std::min(1.0,c.translation_sigma_m/
                                                  config_.max_translation_sigma_m);
  const double rotation_score=1.0-std::min(1.0,c.rotation_sigma_degrees/
                                               config_.max_rotation_sigma_degrees);
  result.quality_score=std::max(0.0,std::min(1.0,0.35*c.sharpness+0.20*exposure_score+
    0.20*feature_score+0.15*translation_score+0.10*rotation_score));
  result.accepted=true;result.object_view_bin=bin;
  keyframes_.push_back({c,result.quality_score,bin});
  if(bin){object_bins_[*bin]=true;if(!reference_object_center_world_m_)
    reference_object_center_world_m_=c.object_center_world_m;}
  return result;
}

CaptureDecision ReconstructionCapturePlanner::reserve(
    const ReconstructionFrameCandidate& candidate) {
  CaptureDecision result;
  if(pending_reservation_){result.rejection=CaptureRejection::kInvalid;return result;}
  ReconstructionCapturePlanner preview=*this;
  result=preview.consider(candidate);
  if(!result.accepted){
    // Preserve monotonic observation semantics for rejected frames.
    return consider(candidate);
  }
  std::uint64_t token=next_reservation_token_++;
  if(token==0){token=next_reservation_token_++;}
  pending_reservation_=PendingReservation{token,candidate};
  result.reservation_token=token;
  return result;
}

bool ReconstructionCapturePlanner::commit(std::uint64_t token) {
  if(!pending_reservation_||token==0||pending_reservation_->token!=token)return false;
  const ReconstructionFrameCandidate candidate=pending_reservation_->candidate;
  pending_reservation_.reset();
  const CaptureDecision decision=consider(candidate);
  return decision.accepted;
}

bool ReconstructionCapturePlanner::cancel(std::uint64_t token) {
  if(!pending_reservation_||token==0||pending_reservation_->token!=token)return false;
  last_seen_timestamp_ns_=std::max(last_seen_timestamp_ns_,
                                   pending_reservation_->candidate.timestamp_ns);
  pending_reservation_.reset();return true;
}

bool ReconstructionCapturePlanner::hasPendingReservation() const {
  return pending_reservation_.has_value();
}

const std::vector<SelectedReconstructionFrame>&
ReconstructionCapturePlanner::keyframes() const{return keyframes_;}

ObjectCoverage ReconstructionCapturePlanner::objectCoverage() const {
  ObjectCoverage output;if(mode_!=ScanMode::kObject)return output;
  output.total_bins=object_bins_.size();
  output.covered_bins=static_cast<std::size_t>(std::count(object_bins_.begin(),object_bins_.end(),true));
  output.ratio=output.total_bins==0?0.0:static_cast<double>(output.covered_bins)/output.total_bins;
  return output;
}

void ReconstructionCapturePlanner::reset(){keyframes_.clear();
  std::fill(object_bins_.begin(),object_bins_.end(),false);reference_object_center_world_m_.reset();
  pending_reservation_.reset();last_seen_timestamp_ns_=0;}

}  // namespace arkernel
