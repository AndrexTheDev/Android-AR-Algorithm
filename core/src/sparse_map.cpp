#include "arkernel/sparse_map.h"
#include "arkernel/visual_frontend.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

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
    if(now_ns-it->second.observation.timestamp_ns>config_.pending_observation_lifetime_ns){it=pending_.erase(it);++update.pruned;}
    else ++it;
  }
  for(auto it=landmarks_.begin();it!=landmarks_.end();){
    if(now_ns-it->second.last_seen_ns>config_.landmark_lifetime_ns){it=landmarks_.erase(it);++update.pruned;}
    else ++it;
  }
  for(const auto& observation:observations){
    if(observation.timestamp_ns<=0||observation.timestamp_ns>now_ns||
       !finite(observation.unit_ray_camera)||observation.unit_ray_camera.norm()<1e-9){++update.rejected;continue;}
    const Vec3 ray=normalized(observation.world_from_camera.rotation.rotate(observation.unit_ray_camera));
    auto landmark=landmarks_.find(observation.feature_id);
    if(landmark!=landmarks_.end()){
      const double residual=rayDistance(observation.world_from_camera.translation,ray,
                                        landmark->second.position_world_m);
      if(residual>config_.max_ray_residual_m){++update.rejected;continue;}
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
      pending_[observation.feature_id]={observation};++update.updated;continue;
    }
    auto pending=pending_.find(observation.feature_id);
    if(pending==pending_.end()){
      pending_[observation.feature_id]={observation};continue;
    }
    const auto triangulated=triangulate(pending->second.observation,observation);
    if(!triangulated){pending->second.observation=observation;++update.rejected;continue;}
    if(landmarks_.size()>=config_.max_landmarks){
      auto victim=std::min_element(landmarks_.begin(),landmarks_.end(),[](const auto& a,const auto& b){
        if(a.second.observation_count!=b.second.observation_count)
          return a.second.observation_count<b.second.observation_count;
        if(a.second.last_seen_ns!=b.second.last_seen_ns)
          return a.second.last_seen_ns<b.second.last_seen_ns;
        return a.second.feature_id<b.second.feature_id;});
      if(victim!=landmarks_.end()){landmarks_.erase(victim);++update.pruned;}
    }
    landmarks_[observation.feature_id]={observation.feature_id,triangulated->first,2,
                                        triangulated->second,observation.timestamp_ns};
    pending_[observation.feature_id]={observation};++update.created;
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
void SparseLandmarkMap::reset(){pending_.clear();landmarks_.clear();}

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
    Vec3 normal=normalized(cross(landmarks[b].position_world_m-landmarks[a].position_world_m,
                                 landmarks[c].position_world_m-landmarks[a].position_world_m));
    if(normal.norm()<0.9)continue;
    double distance=-dot(normal,landmarks[a].position_world_m);std::vector<std::uint32_t> ids;double error=0;
    for(const auto& point:landmarks){const double residual=std::abs(dot(normal,point.position_world_m)+distance);
      if(residual<=config_.inlier_threshold_m){ids.push_back(point.feature_id);error+=residual;}}
    if(ids.size()>best.inlier_count||(ids.size()==best.inlier_count&&ids.size()&&error/ids.size()<best.mean_absolute_error_m)){
      if(normal.z<0){normal=normal*-1.0;distance=-distance;}
      best.normal_world=normal;best.distance_from_origin_m=distance;best.inlier_count=ids.size();
      best.mean_absolute_error_m=ids.empty()?0:error/ids.size();best.landmark_ids=std::move(ids);
    }
  }
  if(best.inlier_count<config_.min_inliers)return best;
  std::vector<Vec3> inlier_points;inlier_points.reserve(best.inlier_count);
  for(const auto& point:landmarks)
    if(std::abs(dot(best.normal_world,point.position_world_m)+best.distance_from_origin_m)<=
       config_.inlier_threshold_m)inlier_points.push_back(point.position_world_m);
  if(inlier_points.size()<config_.min_inliers)return best;
  for(const auto& point:inlier_points)best.center_world+=point;
  best.center_world=best.center_world*(1.0/inlier_points.size());
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
void AnchorStore::reset(){anchors_.clear();next_id_=1;}

}  // namespace arkernel
