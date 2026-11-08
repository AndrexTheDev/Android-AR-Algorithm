#include "arkernel/loop_closure.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>

namespace arkernel {
namespace {
constexpr double kRadiansToDegrees=57.2957795130823208768;

bool finite(const Vec3& value){
  return std::isfinite(value.x)&&std::isfinite(value.y)&&std::isfinite(value.z);
}
bool validPose(const Pose& pose){
  if(!finite(pose.translation))return false;
  const auto& q=pose.rotation;
  const double norm=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
  return std::isfinite(norm)&&norm>1e-9;
}
TimestampNs absoluteDifference(TimestampNs a,TimestampNs b){return a>=b?a-b:b-a;}

bool indexKeyframe(const LoopKeyframe& keyframe,std::size_t max_landmarks,
                   std::unordered_map<std::uint32_t,Vec3>& positions){
  if(keyframe.id==0||keyframe.timestamp_ns<=0||!validPose(keyframe.world_from_keyframe)||
     keyframe.landmarks.empty()||keyframe.landmarks.size()>max_landmarks||
     keyframe.descriptors.empty()||keyframe.descriptors.size()>max_landmarks)return false;
  positions.reserve(keyframe.landmarks.size());
  for(const auto& landmark:keyframe.landmarks)
    if(landmark.feature_id==0||!finite(landmark.position_keyframe_m)||
       !positions.emplace(landmark.feature_id,landmark.position_keyframe_m).second)return false;
  std::unordered_set<std::uint32_t> descriptor_ids;descriptor_ids.reserve(keyframe.descriptors.size());
  for(const auto& descriptor:keyframe.descriptors)
    if(positions.count(descriptor.feature_id)==0||
       !descriptor_ids.insert(descriptor.feature_id).second)return false;
  return true;
}

Pose expectedCurrentFromReference(const Pose& world_from_reference,
                                  const Pose& world_from_current){
  const Quaternion current_from_world=world_from_current.rotation.inverse();
  return {(current_from_world*world_from_reference.rotation).normalized(),
    current_from_world.rotate(world_from_reference.translation-world_from_current.translation)};
}

double rotationDifferenceDegrees(const Quaternion& first,const Quaternion& second){
  const Quaternion difference=(first.normalized().inverse()*second.normalized()).normalized();
  const double cosine=std::min(1.0,std::max(-1.0,std::abs(difference.w)));
  return 2.0*std::acos(cosine)*kRadiansToDegrees;
}
}  // namespace

LoopClosureConstraint verifyLoopClosure(const LoopKeyframe& reference,
    const LoopKeyframe& current,LoopClosureConfig config){
  LoopClosureConstraint result;result.reference_keyframe_id=reference.id;
  result.current_keyframe_id=current.id;
  if(config.max_landmarks_per_keyframe<3||config.min_temporal_separation_ns<0||
     !std::isfinite(config.max_translation_disagreement_m)||
     config.max_translation_disagreement_m<=0||
     !std::isfinite(config.max_rotation_disagreement_degrees)||
     config.max_rotation_disagreement_degrees<=0||config.max_rotation_disagreement_degrees>180)
    return result;
  std::unordered_map<std::uint32_t,Vec3> reference_positions,current_positions;
  if(reference.id==current.id||
     !indexKeyframe(reference,config.max_landmarks_per_keyframe,reference_positions)||
     !indexKeyframe(current,config.max_landmarks_per_keyframe,current_positions))return result;
  if(absoluteDifference(reference.timestamp_ns,current.timestamp_ns)<
     config.min_temporal_separation_ns){
    result.rejection_reason=LoopClosureRejectionReason::kTemporallyNear;return result;
  }
  std::vector<DescriptorMatch> matches;
  try {matches=matchDescriptors(reference.descriptors,current.descriptors,config.matcher);}
  catch(const std::invalid_argument&){return result;}
  result.descriptor_match_count=matches.size();
  if(matches.size()<config.geometry.min_inliers){
    result.rejection_reason=LoopClosureRejectionReason::kInsufficientMatches;return result;
  }
  std::vector<RelocalizationCorrespondence> correspondences;correspondences.reserve(matches.size());
  for(const auto& match:matches){
    const auto reference_point=reference_positions.find(match.query_feature_id);
    const auto current_point=current_positions.find(match.train_feature_id);
    if(reference_point!=reference_positions.end()&&current_point!=current_positions.end())
      correspondences.push_back({reference_point->second,current_point->second});
  }
  RelocalizationResult geometry;
  try {geometry=estimateRelocalization(correspondences,config.geometry);}
  catch(const std::invalid_argument&){return result;}
  result.inlier_count=geometry.inlier_count;result.rmse_m=geometry.rmse_m;
  if(!correspondences.empty())result.inlier_ratio=static_cast<double>(geometry.inlier_count)/
                                                correspondences.size();
  if(!geometry.valid){result.rejection_reason=LoopClosureRejectionReason::kGeometryRejected;return result;}
  result.current_from_reference=geometry.current_from_saved;
  const Pose expected=expectedCurrentFromReference(reference.world_from_keyframe,
                                                   current.world_from_keyframe);
  result.translation_disagreement_m=(geometry.current_from_saved.translation-
                                     expected.translation).norm();
  result.rotation_disagreement_degrees=rotationDifferenceDegrees(
      geometry.current_from_saved.rotation,expected.rotation);
  if(result.translation_disagreement_m>config.max_translation_disagreement_m||
     result.rotation_disagreement_degrees>config.max_rotation_disagreement_degrees){
    result.rejection_reason=LoopClosureRejectionReason::kPoseDisagreement;return result;
  }
  result.valid=true;result.rejection_reason=LoopClosureRejectionReason::kNone;return result;
}

}  // namespace arkernel
