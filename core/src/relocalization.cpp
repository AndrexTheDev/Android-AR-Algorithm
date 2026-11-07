#include "arkernel/relocalization.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_map>

namespace arkernel {
namespace {
bool finite(const Vec3& v){return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z);}
Vec3 cross(const Vec3& a,const Vec3& b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}

double triangleArea(const Vec3& a,const Vec3& b,const Vec3& c){
  return 0.5*cross(b-a,c-a).norm();
}

std::optional<Pose> fitRigid(const std::vector<RelocalizationCorrespondence>& values,
                             const std::vector<std::size_t>& indices){
  if(indices.size()<3)return std::nullopt;
  Vec3 saved_center{},current_center{};
  for(const auto index:indices){saved_center+=values[index].saved_world_point_m;
    current_center+=values[index].current_world_point_m;}
  const double inverse=1.0/static_cast<double>(indices.size());
  saved_center=saved_center*inverse;current_center=current_center*inverse;
  double sxx=0,sxy=0,sxz=0,syx=0,syy=0,syz=0,szx=0,szy=0,szz=0;
  for(const auto index:indices){
    const Vec3 a=values[index].saved_world_point_m-saved_center;
    const Vec3 b=values[index].current_world_point_m-current_center;
    sxx+=a.x*b.x;sxy+=a.x*b.y;sxz+=a.x*b.z;
    syx+=a.y*b.x;syy+=a.y*b.y;syz+=a.y*b.z;
    szx+=a.z*b.x;szy+=a.z*b.y;szz+=a.z*b.z;
  }
  const double trace=sxx+syy+szz;
  const std::array<std::array<double,4>,4> n{{
    {{trace,syz-szy,szx-sxz,sxy-syx}},
    {{syz-szy,sxx-syy-szz,sxy+syx,szx+sxz}},
    {{szx-sxz,sxy+syx,-sxx+syy-szz,syz+szy}},
    {{sxy-syx,szx+sxz,syz+szy,-sxx-syy+szz}}
  }};
  double shift=1.0;
  for(const auto& row:n){double sum=0;for(const double value:row)sum+=std::abs(value);
    shift=std::max(shift,sum);}
  std::array<double,4> q{{1,0,0,0}};
  for(int iteration=0;iteration<80;++iteration){
    std::array<double,4> next{};
    for(std::size_t row=0;row<4;++row){
      for(std::size_t column=0;column<4;++column)next[row]+=n[row][column]*q[column];
      next[row]+=shift*q[row];
    }
    double norm=0;for(const double value:next)norm+=value*value;norm=std::sqrt(norm);
    if(!std::isfinite(norm)||norm<1e-12)return std::nullopt;
    for(std::size_t i=0;i<4;++i)q[i]=next[i]/norm;
  }
  Quaternion rotation{q[0],q[1],q[2],q[3]};rotation=rotation.normalized();
  const Vec3 translation=current_center-rotation.rotate(saved_center);
  if(!finite(translation))return std::nullopt;
  return Pose{rotation,translation};
}

double residual(const Pose& pose,const RelocalizationCorrespondence& value){
  return (pose.rotation.rotate(value.saved_world_point_m)+pose.translation-
          value.current_world_point_m).norm();
}

std::uint32_t randomNext(std::uint32_t& state){
  state^=state<<13;state^=state>>17;state^=state<<5;return state;
}
}  // namespace

std::vector<RelocalizationCorrespondence> matchRelocalizationLandmarks(
    const SpatialSnapshot& saved,const std::vector<SparseLandmark>& current,
    const std::vector<BinaryFeatureDescriptor>& current_descriptors,
    DescriptorMatcherConfig matcher_config){
  std::unordered_map<std::uint32_t,Vec3> saved_positions,current_positions;
  saved_positions.reserve(saved.landmarks.size());current_positions.reserve(current.size());
  for(const auto& landmark:saved.landmarks)
    saved_positions.emplace(landmark.feature_id,landmark.position_world_m);
  for(const auto& landmark:current)
    current_positions.emplace(landmark.feature_id,landmark.position_world_m);
  const auto matches=matchDescriptors(saved.landmark_descriptors,current_descriptors,matcher_config);
  std::vector<RelocalizationCorrespondence> output;output.reserve(matches.size());
  for(const auto& match:matches){
    const auto saved_point=saved_positions.find(match.query_feature_id);
    const auto current_point=current_positions.find(match.train_feature_id);
    if(saved_point!=saved_positions.end()&&current_point!=current_positions.end())
      output.push_back({saved_point->second,current_point->second});
  }
  return output;
}

RelocalizationResult estimateRelocalization(
    const std::vector<RelocalizationCorrespondence>& correspondences,
    RelocalizationConfig config){
  RelocalizationResult result;result.correspondence_count=correspondences.size();
  if(config.max_correspondences<3||config.min_inliers<3||
     config.min_inliers>config.max_correspondences||config.ransac_iterations==0||
     config.inlier_threshold_m<=0||config.min_inlier_ratio<=0||config.min_inlier_ratio>1||
     config.max_rmse_m<=0||config.min_sample_triangle_area_m2<=0)
    throw std::invalid_argument("Invalid relocalization configuration");
  if(correspondences.size()<config.min_inliers||
     correspondences.size()>config.max_correspondences)return result;
  for(const auto& value:correspondences)
    if(!finite(value.saved_world_point_m)||!finite(value.current_world_point_m))return result;
  std::vector<std::size_t> best;
  double best_error=std::numeric_limits<double>::infinity();
  std::uint32_t random=config.random_seed?config.random_seed:1u;
  for(std::size_t iteration=0;iteration<config.ransac_iterations;++iteration){
    std::array<std::size_t,3> sample{};
    sample[0]=randomNext(random)%correspondences.size();
    do sample[1]=randomNext(random)%correspondences.size();while(sample[1]==sample[0]);
    do sample[2]=randomNext(random)%correspondences.size();
      while(sample[2]==sample[0]||sample[2]==sample[1]);
    const auto& a=correspondences[sample[0]];const auto& b=correspondences[sample[1]];
    const auto& c=correspondences[sample[2]];
    if(triangleArea(a.saved_world_point_m,b.saved_world_point_m,c.saved_world_point_m)<
         config.min_sample_triangle_area_m2||
       triangleArea(a.current_world_point_m,b.current_world_point_m,c.current_world_point_m)<
         config.min_sample_triangle_area_m2)continue;
    const std::vector<std::size_t> sample_indices{sample[0],sample[1],sample[2]};
    const auto pose=fitRigid(correspondences,sample_indices);if(!pose)continue;
    std::vector<std::size_t> inliers;double squared_error=0;
    for(std::size_t i=0;i<correspondences.size();++i){const double error=residual(*pose,correspondences[i]);
      if(error<=config.inlier_threshold_m){inliers.push_back(i);squared_error+=error*error;}}
    if(inliers.size()>best.size()||(inliers.size()==best.size()&&squared_error<best_error)){
      best=std::move(inliers);best_error=squared_error;}
  }
  const auto required_ratio=static_cast<double>(best.size())/
                            static_cast<double>(correspondences.size());
  if(best.size()<config.min_inliers||required_ratio<config.min_inlier_ratio)return result;
  const auto refined=fitRigid(correspondences,best);if(!refined)return result;
  std::vector<std::size_t> final_inliers;double squared_error=0;
  for(std::size_t i=0;i<correspondences.size();++i){const double error=residual(*refined,correspondences[i]);
    if(error<=config.inlier_threshold_m){final_inliers.push_back(i);squared_error+=error*error;}}
  if(final_inliers.size()<config.min_inliers||
     static_cast<double>(final_inliers.size())/correspondences.size()<config.min_inlier_ratio)return result;
  const auto final_pose=fitRigid(correspondences,final_inliers);if(!final_pose)return result;
  squared_error=0;for(const auto index:final_inliers){const double error=residual(*final_pose,correspondences[index]);
    squared_error+=error*error;}
  const double rmse=std::sqrt(squared_error/static_cast<double>(final_inliers.size()));
  if(!std::isfinite(rmse)||rmse>config.max_rmse_m)return result;
  result.current_from_saved=*final_pose;result.inlier_count=final_inliers.size();
  result.rmse_m=rmse;result.valid=true;return result;
}

SpatialSnapshot transformSpatialSnapshot(const SpatialSnapshot& snapshot,const Pose& transform){
  SpatialSnapshot transformed=snapshot;
  const Quaternion rotation=transform.rotation.normalized();
  for(auto& landmark:transformed.landmarks)
    landmark.position_world_m=rotation.rotate(landmark.position_world_m)+transform.translation;
  for(auto& anchor:transformed.anchors){
    anchor.world_from_anchor.translation=
      rotation.rotate(anchor.world_from_anchor.translation)+transform.translation;
    anchor.world_from_anchor.rotation=(rotation*anchor.world_from_anchor.rotation).normalized();
  }
  return transformed;
}

bool restoreRelocalizedSpatialSnapshot(const SpatialSnapshot& snapshot,
    const Pose& current_from_saved,SparseLandmarkMap& map,AnchorStore& anchors){
  if(!finite(current_from_saved.translation))return false;
  const auto& q=current_from_saved.rotation;
  const double norm=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
  if(!std::isfinite(norm)||norm<1e-9)return false;
  return restoreSpatialSnapshot(transformSpatialSnapshot(snapshot,current_from_saved),map,anchors);
}

}  // namespace arkernel
