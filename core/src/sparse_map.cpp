#include "arkernel/sparse_map.h"
#include "arkernel/visual_frontend.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_set>

namespace arkernel {
namespace {
double dot(const Vec3& a,const Vec3& b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 cross(const Vec3& a,const Vec3& b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
Vec3 normalized(const Vec3& v){const double n=v.norm();return n>1e-12?v*(1.0/n):Vec3{};}
double rayDistance(const Vec3& center,const Vec3& direction,const Vec3& point){
  const Vec3 offset=point-center;return (offset-direction*dot(offset,direction)).norm();
}
std::uint32_t nextRandom(std::uint32_t& state){state^=state<<13;state^=state>>17;state^=state<<5;return state;}
bool finite(const Vec3& v){return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z);}
Vec3 canonicalNormal(Vec3 normal){
  normal=normalized(normal);
  const double epsilon=1e-12;
  if(normal.z<-epsilon||(std::abs(normal.z)<=epsilon&&normal.y<-epsilon)||
     (std::abs(normal.z)<=epsilon&&std::abs(normal.y)<=epsilon&&normal.x<0))normal=normal*-1.0;
  return normal;
}
std::optional<Vec3> leastSquaresPlaneNormal(const std::vector<Vec3>& points){
  if(points.size()<3)return std::nullopt;
  Vec3 center;for(const auto& point:points){if(!finite(point))return std::nullopt;center+=point;}
  center=center*(1.0/static_cast<double>(points.size()));
  double matrix[3][3]{};
  for(const auto& point:points){const Vec3 d=point-center;const double value[3]{d.x,d.y,d.z};
    for(int row=0;row<3;++row)for(int column=0;column<3;++column)
      matrix[row][column]+=value[row]*value[column];}
  double vectors[3][3]{{1,0,0},{0,1,0},{0,0,1}};
  for(int sweep=0;sweep<24;++sweep){
    int p=0,q=1;double largest=std::abs(matrix[0][1]);
    if(std::abs(matrix[0][2])>largest){p=0;q=2;largest=std::abs(matrix[0][2]);}
    if(std::abs(matrix[1][2])>largest){p=1;q=2;largest=std::abs(matrix[1][2]);}
    if(largest<1e-15)break;
    const double app=matrix[p][p],aqq=matrix[q][q],apq=matrix[p][q];
    const double angle=0.5*std::atan2(2.0*apq,aqq-app);
    const double cosine=std::cos(angle),sine=std::sin(angle);
    for(int k=0;k<3;++k)if(k!=p&&k!=q){
      const double akp=matrix[k][p],akq=matrix[k][q];
      matrix[k][p]=matrix[p][k]=cosine*akp-sine*akq;
      matrix[k][q]=matrix[q][k]=sine*akp+cosine*akq;
    }
    matrix[p][p]=cosine*cosine*app-2.0*sine*cosine*apq+sine*sine*aqq;
    matrix[q][q]=sine*sine*app+2.0*sine*cosine*apq+cosine*cosine*aqq;
    matrix[p][q]=matrix[q][p]=0;
    for(int k=0;k<3;++k){const double vkp=vectors[k][p],vkq=vectors[k][q];
      vectors[k][p]=cosine*vkp-sine*vkq;vectors[k][q]=sine*vkp+cosine*vkq;}
  }
  int minimum=0;if(matrix[1][1]<matrix[minimum][minimum])minimum=1;
  if(matrix[2][2]<matrix[minimum][minimum])minimum=2;
  const Vec3 normal=canonicalNormal({vectors[0][minimum],vectors[1][minimum],vectors[2][minimum]});
  return normal.norm()>0.9?std::optional<Vec3>(normal):std::nullopt;
}
double cross2(const Vec2& origin,const Vec2& a,const Vec2& b){
  return (a.x-origin.x)*(b.y-origin.y)-(a.y-origin.y)*(b.x-origin.x);
}
std::vector<Vec2> convexHull(std::vector<Vec2> points){
  std::sort(points.begin(),points.end(),[](const Vec2& a,const Vec2& b){
    return a.x!=b.x?a.x<b.x:a.y<b.y;
  });
  points.erase(std::unique(points.begin(),points.end(),[](const Vec2& a,const Vec2& b){
    return std::abs(a.x-b.x)<1e-9&&std::abs(a.y-b.y)<1e-9;
  }),points.end());
  if(points.size()<3)return {};
  std::vector<Vec2> hull(points.size()*2);std::size_t count=0;
  for(const auto& point:points){
    while(count>=2&&cross2(hull[count-2],hull[count-1],point)<=0)--count;
    hull[count++]=point;
  }
  const std::size_t lower_end=count+1;
  for(std::size_t i=points.size()-1;i>0;--i){const auto& point=points[i-1];
    while(count>=lower_end&&cross2(hull[count-2],hull[count-1],point)<=0)--count;
    hull[count++]=point;
  }
  if(count>1)--count;
  hull.resize(count);
  return hull.size()>=3?hull:std::vector<Vec2>{};
}
double polygonArea(const std::vector<Vec2>& polygon){
  double twice_area=0;
  for(std::size_t i=0;i<polygon.size();++i){const auto& a=polygon[i];const auto& b=polygon[(i+1)%polygon.size()];
    twice_area+=a.x*b.y-a.y*b.x;}
  return std::abs(twice_area)*0.5;
}
bool insideConvex(const std::vector<Vec2>& polygon,const Vec2& point){
  if(polygon.size()<3)return false;
  for(std::size_t i=0;i<polygon.size();++i)
    if(cross2(polygon[i],polygon[(i+1)%polygon.size()],point)<-1e-8)return false;
  return true;
}
}  // namespace

std::vector<LandmarkObservation> makeLandmarkObservations(
    const VisualFrontendResult& result,const PinholeRadTanCamera& camera,
    const Pose& world_from_camera){
  std::vector<LandmarkObservation> observations;
  observations.reserve(result.accepted_feature_ids.size());
  for(const auto id:result.accepted_feature_ids){
    const auto track=std::find_if(result.tracks.begin(),result.tracks.end(),
                                  [id](const auto& value){return value.id==id;});
    if(track==result.tracks.end())continue;
    const auto ray=camera.unproject(track->current);
    if(ray)observations.push_back({id,result.timestamp_ns,*ray,world_from_camera});
  }
  return observations;
}

SparseLandmarkMap::SparseLandmarkMap(SparseMapConfig config):config_(config){
  if(config_.max_landmarks<1||config_.min_triangulation_baseline_m<=0||
     config_.min_triangulation_parallax_degrees<=0||
     config_.min_triangulation_parallax_degrees>=90||config_.max_ray_residual_m<=0||
     config_.min_confirming_observations<3||config_.max_consecutive_rejections<1||
     config_.pending_observation_lifetime_ns<=0||config_.landmark_lifetime_ns<=0)
    throw std::invalid_argument("Invalid sparse map configuration");
}

std::optional<std::pair<Vec3,double>> SparseLandmarkMap::triangulate(
    const LandmarkObservation& first,const LandmarkObservation& second) const{
  const Vec3 center1=first.world_from_camera.translation;
  const Vec3 center2=second.world_from_camera.translation;
  if((center2-center1).norm()<config_.min_triangulation_baseline_m)return std::nullopt;
  const Vec3 ray1=normalized(first.world_from_camera.rotation.rotate(first.unit_ray_camera));
  const Vec3 ray2=normalized(second.world_from_camera.rotation.rotate(second.unit_ray_camera));
  if(ray1.norm()<0.9||ray2.norm()<0.9)return std::nullopt;
  const double cosine=std::max(-1.0,std::min(1.0,dot(ray1,ray2)));
  const double parallax=std::acos(cosine)*180.0/3.14159265358979323846;
  if(parallax<config_.min_triangulation_parallax_degrees)return std::nullopt;
  const Vec3 baseline=center2-center1;
  const double b=dot(ray1,ray2),denominator=1.0-b*b;
  if(denominator<1e-9)return std::nullopt;
  const double d=dot(ray1,baseline),e=dot(ray2,baseline);
  const double depth1=(d-b*e)/denominator;
  const double depth2=(b*d-e)/denominator;
  if(depth1<=0||depth2<=0)return std::nullopt;
  const Vec3 point1=center1+ray1*depth1,point2=center2+ray2*depth2;
  const double residual=(point1-point2).norm();
  if(residual>config_.max_ray_residual_m)return std::nullopt;
  return std::make_pair((point1+point2)*0.5,residual);
}

MapUpdate SparseLandmarkMap::integrate(const std::vector<LandmarkObservation>& observations,
                                        TimestampNs now_ns){
  if(now_ns<=0)throw std::invalid_argument("Map timestamp must be positive");
  MapUpdate update;
  for(auto it=pending_.begin();it!=pending_.end();){
    if(now_ns-it->second.observation.timestamp_ns>config_.pending_observation_lifetime_ns){
      it=pending_.erase(it);++update.pruned;
    }else ++it;
  }
  for(auto it=landmarks_.begin();it!=landmarks_.end();){
    if(now_ns-it->second.last_seen_ns>config_.landmark_lifetime_ns){
      consecutive_rejections_.erase(it->first);it=landmarks_.erase(it);++update.pruned;
    }else ++it;
  }
  auto makeRoom=[&](){
    if(landmarks_.size()<config_.max_landmarks)return;
    auto victim=std::min_element(landmarks_.begin(),landmarks_.end(),[](const auto& a,const auto& b){
      if(a.second.observation_count!=b.second.observation_count)
        return a.second.observation_count<b.second.observation_count;
      if(a.second.last_seen_ns!=b.second.last_seen_ns)
        return a.second.last_seen_ns<b.second.last_seen_ns;
      return a.second.feature_id<b.second.feature_id;
    });
    if(victim!=landmarks_.end()){
      consecutive_rejections_.erase(victim->first);landmarks_.erase(victim);++update.pruned;
    }
  };
  for(const auto& observation:observations){
    const auto& rotation=observation.world_from_camera.rotation;
    const double rotation_norm=std::sqrt(rotation.w*rotation.w+rotation.x*rotation.x+
      rotation.y*rotation.y+rotation.z*rotation.z);
    if(observation.timestamp_ns<=0||observation.timestamp_ns>now_ns||
       !finite(observation.unit_ray_camera)||observation.unit_ray_camera.norm()<1e-9||
       !finite(observation.world_from_camera.translation)||!std::isfinite(rotation_norm)||
       rotation_norm<1e-9){++update.rejected;continue;}
    const Vec3 ray=normalized(rotation.rotate(observation.unit_ray_camera));
    auto landmark=landmarks_.find(observation.feature_id);
    if(landmark!=landmarks_.end()){
      if(observation.timestamp_ns<=landmark->second.last_seen_ns){++update.rejected;continue;}
      const Vec3 offset=landmark->second.position_world_m-observation.world_from_camera.translation;
      const double residual=rayDistance(observation.world_from_camera.translation,ray,
                                        landmark->second.position_world_m);
      if(!std::isfinite(residual)||residual>config_.max_ray_residual_m||dot(offset,ray)<=0){
        ++update.rejected;
        auto& count=consecutive_rejections_[observation.feature_id];
        if(++count>=config_.max_consecutive_rejections){
          landmarks_.erase(landmark);consecutive_rejections_.erase(observation.feature_id);
          pending_[observation.feature_id]=Pending{observation,std::nullopt,1,0.0};
          ++update.pruned;++update.dynamic_pruned;
        }
        continue;
      }
      consecutive_rejections_.erase(observation.feature_id);
      auto candidate=pending_.find(observation.feature_id);
      if(candidate!=pending_.end()){
        const auto triangulated=triangulate(candidate->second.observation,observation);
        if(triangulated){
          const double count=static_cast<double>(landmark->second.observation_count);
          landmark->second.position_world_m=(landmark->second.position_world_m*count+
                                             triangulated->first)*(1.0/(count+1.0));
        }
      }
      const double count=static_cast<double>(landmark->second.observation_count);
      landmark->second.mean_ray_residual_m=(landmark->second.mean_ray_residual_m*count+residual)/(count+1.0);
      ++landmark->second.observation_count;landmark->second.last_seen_ns=observation.timestamp_ns;
      pending_[observation.feature_id]=Pending{observation,std::nullopt,1,0.0};++update.updated;continue;
    }
    auto pending=pending_.find(observation.feature_id);
    if(pending==pending_.end()){
      pending_[observation.feature_id]=Pending{observation,std::nullopt,1,0.0};continue;
    }
    if(observation.timestamp_ns<=pending->second.observation.timestamp_ns){++update.rejected;continue;}
    if(!pending->second.candidate_position_world_m){
      const auto triangulated=triangulate(pending->second.observation,observation);
      if(!triangulated){pending->second=Pending{observation,std::nullopt,1,0.0};++update.rejected;continue;}
      pending->second.observation=observation;
      pending->second.candidate_position_world_m=triangulated->first;
      pending->second.confirming_observations=2;
      pending->second.accumulated_residual_m=triangulated->second;
      continue;
    }
    const Vec3 candidate_position=*pending->second.candidate_position_world_m;
    const Vec3 candidate_offset=candidate_position-observation.world_from_camera.translation;
    const double confirmation_residual=rayDistance(
      observation.world_from_camera.translation,ray,candidate_position);
    if(!std::isfinite(confirmation_residual)||confirmation_residual>config_.max_ray_residual_m||
       dot(candidate_offset,ray)<=0){
      pending->second=Pending{observation,std::nullopt,1,0.0};++update.rejected;continue;
    }
    const auto pair_estimate=triangulate(pending->second.observation,observation);
    ++pending->second.confirming_observations;
    pending->second.accumulated_residual_m+=confirmation_residual;
    if(pair_estimate){
      const double prior_weight=static_cast<double>(pending->second.confirming_observations-2);
      pending->second.candidate_position_world_m=(candidate_position*prior_weight+
        pair_estimate->first)*(1.0/(prior_weight+1.0));
    }
    pending->second.observation=observation;
    if(pending->second.confirming_observations<config_.min_confirming_observations)continue;
    makeRoom();
    const double residual_count=static_cast<double>(pending->second.confirming_observations-1);
    landmarks_[observation.feature_id]={observation.feature_id,
      *pending->second.candidate_position_world_m,pending->second.confirming_observations,
      pending->second.accumulated_residual_m/residual_count,observation.timestamp_ns};
    pending->second=Pending{observation,std::nullopt,1,0.0};++update.created;
  }
  return update;
}

std::optional<SparseLandmark> SparseLandmarkMap::find(std::uint32_t id) const{
  const auto found=landmarks_.find(id);return found==landmarks_.end()?std::nullopt:
    std::optional<SparseLandmark>(found->second);
}
std::vector<SparseLandmark> SparseLandmarkMap::snapshot() const{
  std::vector<SparseLandmark> result;result.reserve(landmarks_.size());
  for(const auto& entry:landmarks_)result.push_back(entry.second);
  std::sort(result.begin(),result.end(),[](const auto& a,const auto& b){return a.feature_id<b.feature_id;});return result;
}
bool SparseLandmarkMap::restore(const std::vector<SparseLandmark>& values){
  if(values.size()>config_.max_landmarks)return false;
  std::unordered_map<std::uint32_t,SparseLandmark> restored;
  for(const auto& value:values){
    if(!finite(value.position_world_m)||value.observation_count<2||
       !std::isfinite(value.mean_ray_residual_m)||value.mean_ray_residual_m<0||
       value.last_seen_ns<=0||!restored.emplace(value.feature_id,value).second)return false;
  }
  landmarks_=std::move(restored);pending_.clear();consecutive_rejections_.clear();return true;
}
void SparseLandmarkMap::reset(){pending_.clear();landmarks_.clear();consecutive_rejections_.clear();}

std::optional<PlaneHit> PlaneEstimate::raycast(const Vec3& origin,const Vec3& direction,
                                                double minimum,double maximum) const{
  if(!valid||minimum<0||maximum<=minimum||!finite(origin)||!finite(direction))return std::nullopt;
  const Vec3 ray=normalized(direction);if(ray.norm()<0.9)return std::nullopt;
  const double denominator=dot(normal_world,ray);
  if(std::abs(denominator)<1e-9)return std::nullopt;
  const double distance=-(dot(normal_world,origin)+distance_from_origin_m)/denominator;
  if(distance<minimum||distance>maximum)return std::nullopt;
  const Vec3 position=origin+ray*distance,offset=position-center_world;
  const Vec2 uv{dot(offset,basis_u_world),dot(offset,basis_v_world)};
  if(!insideConvex(boundary_uv,uv))return std::nullopt;
  return PlaneHit{position,normal_world,distance,uv};
}

SparsePlaneDetector::SparsePlaneDetector(PlaneDetectorConfig config):config_(config){
  if(config_.ransac_iterations<1||config_.inlier_threshold_m<=0||config_.min_inliers<3)
    throw std::invalid_argument("Invalid plane detector configuration");
}
PlaneEstimate SparsePlaneDetector::detect(const std::vector<SparseLandmark>& landmarks) const{
  PlaneEstimate best;if(landmarks.size()<3)return best;std::uint32_t state=config_.random_seed?config_.random_seed:1;
  for(int iteration=0;iteration<config_.ransac_iterations;++iteration){
    std::size_t a=nextRandom(state)%landmarks.size(),b=nextRandom(state)%landmarks.size(),c=nextRandom(state)%landmarks.size();
    if(a==b||a==c||b==c)continue;
    if(!finite(landmarks[a].position_world_m)||!finite(landmarks[b].position_world_m)||
       !finite(landmarks[c].position_world_m))continue;
    Vec3 normal=normalized(cross(landmarks[b].position_world_m-landmarks[a].position_world_m,
                                 landmarks[c].position_world_m-landmarks[a].position_world_m));
    if(normal.norm()<0.9)continue;
    double distance=-dot(normal,landmarks[a].position_world_m);std::vector<std::uint32_t> ids;double error=0;
    for(const auto& point:landmarks){const double residual=std::abs(dot(normal,point.position_world_m)+distance);
      if(residual<=config_.inlier_threshold_m){ids.push_back(point.feature_id);error+=residual;}}
    if(ids.size()>best.inlier_count||(ids.size()==best.inlier_count&&ids.size()&&error/ids.size()<best.mean_absolute_error_m)){
      const Vec3 canonical=canonicalNormal(normal);
      if(dot(canonical,normal)<0)distance=-distance;
      best.normal_world=canonical;best.distance_from_origin_m=distance;best.inlier_count=ids.size();
      best.mean_absolute_error_m=ids.empty()?0:error/ids.size();best.landmark_ids=std::move(ids);
    }
  }
  if(best.inlier_count<config_.min_inliers)return best;
  auto collectInliers=[&](const Vec3& normal,double distance,std::vector<Vec3>& points,
                          std::vector<std::uint32_t>& ids,double& error){
    points.clear();ids.clear();error=0;
    for(const auto& landmark:landmarks){
      if(!finite(landmark.position_world_m))continue;
      const double residual=std::abs(dot(normal,landmark.position_world_m)+distance);
      if(residual<=config_.inlier_threshold_m){points.push_back(landmark.position_world_m);
        ids.push_back(landmark.feature_id);error+=residual;}
    }
  };
  std::vector<Vec3> inlier_points;std::vector<std::uint32_t> inlier_ids;double error=0;
  collectInliers(best.normal_world,best.distance_from_origin_m,inlier_points,inlier_ids,error);
  for(int refinement=0;refinement<2;++refinement){
    if(inlier_points.size()<config_.min_inliers)return PlaneEstimate{};
    const auto fitted_normal=leastSquaresPlaneNormal(inlier_points);
    if(!fitted_normal)return PlaneEstimate{};
    Vec3 center;for(const auto& point:inlier_points)center+=point;
    center=center*(1.0/static_cast<double>(inlier_points.size()));
    best.normal_world=*fitted_normal;best.distance_from_origin_m=-dot(best.normal_world,center);
    collectInliers(best.normal_world,best.distance_from_origin_m,inlier_points,inlier_ids,error);
  }
  if(inlier_points.size()<config_.min_inliers)return PlaneEstimate{};
  best.inlier_count=inlier_points.size();best.landmark_ids=std::move(inlier_ids);
  best.mean_absolute_error_m=error/static_cast<double>(best.inlier_count);best.center_world={};
  for(const auto& point:inlier_points)best.center_world+=point;
  best.center_world=best.center_world*(1.0/static_cast<double>(inlier_points.size()));
  best.center_world=best.center_world-best.normal_world*(
      dot(best.normal_world,best.center_world)+best.distance_from_origin_m);
  const Vec3 reference=std::abs(best.normal_world.z)<0.9?Vec3{0,0,1}:Vec3{1,0,0};
  best.basis_u_world=normalized(cross(reference,best.normal_world));
  best.basis_v_world=normalized(cross(best.normal_world,best.basis_u_world));
  std::vector<Vec2> projected;projected.reserve(inlier_points.size());
  for(const auto& point:inlier_points){const Vec3 offset=point-best.center_world;
    projected.push_back({dot(offset,best.basis_u_world),dot(offset,best.basis_v_world)});}
  best.boundary_uv=convexHull(std::move(projected));
  best.observed_area_m2=polygonArea(best.boundary_uv);
  best.valid=best.boundary_uv.size()>=3&&best.observed_area_m2>1e-8;
  return best;
}

std::vector<PlaneEstimate> SparsePlaneDetector::detectMultiple(
    const std::vector<SparseLandmark>& landmarks,std::size_t max_planes) const{
  std::vector<PlaneEstimate> result;
  if(max_planes==0)return result;
  std::vector<SparseLandmark> remaining=landmarks;
  result.reserve(std::min(max_planes,remaining.size()/3));
  while(result.size()<max_planes){
    auto plane=detect(remaining);
    if(!plane.valid)break;
    std::unordered_set<std::uint32_t> inliers(plane.landmark_ids.begin(),plane.landmark_ids.end());
    if(inliers.empty())break;
    result.push_back(std::move(plane));
    remaining.erase(std::remove_if(remaining.begin(),remaining.end(),[&](const auto& landmark){
      return inliers.find(landmark.feature_id)!=inliers.end();
    }),remaining.end());
  }
  return result;
}

PlaneSemantic classifyPlane(const PlaneEstimate& plane,const Vec3& world_up,
                            const std::optional<Vec3>& observer_world,
                            PlaneClassificationConfig config){
  if(config.horizontal_tolerance_degrees<=0||config.horizontal_tolerance_degrees>=45||
     config.vertical_tolerance_degrees<=0||config.vertical_tolerance_degrees>=45||
     config.min_observer_separation_m<0)
    throw std::invalid_argument("Invalid plane classification configuration");
  const Vec3 up=normalized(world_up);
  if(!plane.valid||up.norm()<0.9||!finite(plane.normal_world))return PlaneSemantic::kUnknown;
  const double alignment=dot(plane.normal_world,up);
  const double absolute_alignment=std::abs(alignment);
  const double horizontal_cosine=std::cos(config.horizontal_tolerance_degrees*
                                           3.14159265358979323846/180.0);
  if(absolute_alignment>=horizontal_cosine){
    if(!observer_world||!finite(*observer_world))return PlaneSemantic::kUnknown;
    const double orientation=alignment>=0?1.0:-1.0;
    const double signed_separation=orientation*(dot(plane.normal_world,*observer_world)+
                                                plane.distance_from_origin_m);
    if(signed_separation>config.min_observer_separation_m)return PlaneSemantic::kFloor;
    if(signed_separation<-config.min_observer_separation_m)return PlaneSemantic::kCeiling;
    return PlaneSemantic::kUnknown;
  }
  const double vertical_sine=std::sin(config.vertical_tolerance_degrees*
                                      3.14159265358979323846/180.0);
  return absolute_alignment<=vertical_sine?PlaneSemantic::kWall:PlaneSemantic::kSloped;
}

SparsePlaneTracker::SparsePlaneTracker(PlaneDetectorConfig detector_config,
                                       PlaneTrackerConfig tracker_config)
    :detector_(detector_config),config_(tracker_config){
  if(config_.max_planes<1||config_.max_normal_angle_degrees<=0||
     config_.max_normal_angle_degrees>=90||config_.max_offset_change_m<=0||
     config_.max_center_shift_m<=0)throw std::invalid_argument("Invalid plane tracker configuration");
  classifyPlane({}, {0,0,1}, std::nullopt, config_.classification);
}
const std::vector<TrackedPlane>& SparsePlaneTracker::update(
    const std::vector<SparseLandmark>& landmarks,const Vec3& world_up,
    const std::optional<Vec3>& observer_world){
  auto detected=detector_.detectMultiple(landmarks,config_.max_planes);
  std::vector<TrackedPlane> tracked;tracked.reserve(detected.size());
  std::vector<bool> matched(planes_.size(),false);
  const double minimum_cosine=std::cos(config_.max_normal_angle_degrees*
                                       3.14159265358979323846/180.0);
  for(auto& estimate:detected){
    std::size_t best_index=planes_.size();double best_score=std::numeric_limits<double>::infinity();
    for(std::size_t i=0;i<planes_.size();++i){
      if(matched[i])continue;
      const auto& previous=planes_[i].estimate;
      const double cosine=dot(previous.normal_world,estimate.normal_world);
      const double offset=std::abs(previous.distance_from_origin_m-estimate.distance_from_origin_m);
      const double center=(previous.center_world-estimate.center_world).norm();
      if(cosine<minimum_cosine||offset>config_.max_offset_change_m||
         center>config_.max_center_shift_m)continue;
      const double score=(1.0-cosine)/(1.0-minimum_cosine)+
        offset/config_.max_offset_change_m+center/config_.max_center_shift_m;
      if(score<best_score||(std::abs(score-best_score)<1e-12&&
         (best_index==planes_.size()||planes_[i].id<planes_[best_index].id))){
        best_score=score;best_index=i;
      }
    }
    PlaneId id=0;
    if(best_index<planes_.size()){id=planes_[best_index].id;matched[best_index]=true;}
    else{
      if(next_id_==std::numeric_limits<PlaneId>::max())
        throw std::overflow_error("Plane ID range exhausted");
      id=next_id_++;
    }
    const auto semantic=classifyPlane(estimate,world_up,observer_world,config_.classification);
    tracked.push_back({id,std::move(estimate),semantic});
  }
  planes_=std::move(tracked);return planes_;
}
void SparsePlaneTracker::reset(){planes_.clear();next_id_=1;}

AnchorId AnchorStore::create(const Pose& pose,TimestampNs timestamp){
  const double rotation_norm=std::sqrt(pose.rotation.w*pose.rotation.w+
      pose.rotation.x*pose.rotation.x+pose.rotation.y*pose.rotation.y+
      pose.rotation.z*pose.rotation.z);
  if(timestamp<=0||!finite(pose.translation)||!std::isfinite(rotation_norm)||rotation_norm<1e-9)
    throw std::invalid_argument("Invalid anchor");
  const AnchorId id=next_id_++;anchors_[id]={id,{pose.rotation.normalized(),pose.translation},timestamp};return id;
}
bool AnchorStore::remove(AnchorId id){return anchors_.erase(id)>0;}
std::optional<WorldAnchor> AnchorStore::find(AnchorId id) const{
  const auto found=anchors_.find(id);return found==anchors_.end()?std::nullopt:std::optional<WorldAnchor>(found->second);
}
std::vector<WorldAnchor> AnchorStore::snapshot() const{
  std::vector<WorldAnchor> result;for(const auto& entry:anchors_)result.push_back(entry.second);
  std::sort(result.begin(),result.end(),[](const auto& a,const auto& b){return a.id<b.id;});return result;
}
bool AnchorStore::restore(const std::vector<WorldAnchor>& values){
  std::unordered_map<AnchorId,WorldAnchor> restored;AnchorId next=1;
  for(const auto& value:values){
    const double norm=std::sqrt(value.world_from_anchor.rotation.w*value.world_from_anchor.rotation.w+
        value.world_from_anchor.rotation.x*value.world_from_anchor.rotation.x+
        value.world_from_anchor.rotation.y*value.world_from_anchor.rotation.y+
        value.world_from_anchor.rotation.z*value.world_from_anchor.rotation.z);
    if(value.id==0||value.created_ns<=0||!finite(value.world_from_anchor.translation)||
       !std::isfinite(norm)||norm<1e-9||!restored.emplace(value.id,value).second)return false;
    restored[value.id].world_from_anchor.rotation=restored[value.id].world_from_anchor.rotation.normalized();
    if(value.id==std::numeric_limits<AnchorId>::max())return false;
    next=std::max(next,value.id+1);
  }
  anchors_=std::move(restored);next_id_=next;return true;
}
void AnchorStore::reset(){anchors_.clear();next_id_=1;}

}  // namespace arkernel
