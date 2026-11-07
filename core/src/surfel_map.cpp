#include "arkernel/surfel_map.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
bool finite(const Vec3& value){
  return std::isfinite(value.x)&&std::isfinite(value.y)&&std::isfinite(value.z);
}
}

std::size_t SurfelMap::VoxelHash::operator()(const VoxelKey& key) const {
  std::size_t seed=0x9e3779b97f4a7c15ULL;
  const auto mix=[&seed](std::uint32_t value){
    seed^=static_cast<std::size_t>(value)+0x9e3779b9U+(seed<<6)+(seed>>2);
  };
  mix(static_cast<std::uint32_t>(key.x));mix(static_cast<std::uint32_t>(key.y));
  mix(static_cast<std::uint32_t>(key.z));return seed;
}

SurfelMap::SurfelMap(SurfelMapConfig config):config_(config){
  if(config_.max_surfels==0||config_.max_observations_per_update==0||
     config_.min_confirming_observations==0||!std::isfinite(config_.voxel_size_m)||
     config_.voxel_size_m<=0||!std::isfinite(config_.min_observation_confidence)||
     config_.min_observation_confidence<0||config_.min_observation_confidence>1||
     !std::isfinite(config_.max_position_norm_m)||config_.max_position_norm_m<=0||
     config_.max_position_norm_m/config_.voxel_size_m>
       static_cast<double>(std::numeric_limits<std::int32_t>::max()-1)||
     !std::isfinite(config_.min_radius_m)||!std::isfinite(config_.max_radius_m)||
     config_.min_radius_m<=0||config_.max_radius_m<config_.min_radius_m||
     config_.lifetime_ns<=0)
    throw std::invalid_argument("Invalid surfel map configuration");
}

SurfelMap::VoxelKey SurfelMap::voxelFor(const Vec3& position) const {
  return {static_cast<std::int32_t>(std::floor(position.x/config_.voxel_size_m)),
          static_cast<std::int32_t>(std::floor(position.y/config_.voxel_size_m)),
          static_cast<std::int32_t>(std::floor(position.z/config_.voxel_size_m))};
}

bool SurfelMap::makeRoom(SurfelMapUpdate& update){
  if(surfels_.size()<config_.max_surfels)return true;
  auto victim=surfels_.begin();
  const auto worse=[](const Surfel& a,const Surfel& b){
    if(a.confirmed!=b.confirmed)return !a.confirmed;
    if(a.observation_count!=b.observation_count)return a.observation_count<b.observation_count;
    if(a.confidence!=b.confidence)return a.confidence<b.confidence;
    if(a.last_seen_ns!=b.last_seen_ns)return a.last_seen_ns<b.last_seen_ns;
    return a.id>b.id;
  };
  for(auto iterator=surfels_.begin();iterator!=surfels_.end();++iterator)
    if(worse(iterator->second,victim->second))victim=iterator;
  if(victim->second.confirmed&&config_.min_confirming_observations>1)return false;
  surfels_.erase(victim);++update.pruned;return true;
}

SurfelMapUpdate SurfelMap::integrate(const std::vector<SurfelObservation>& observations,
                                     TimestampNs now_ns){
  SurfelMapUpdate update;
  if(now_ns<=0||observations.size()>config_.max_observations_per_update){
    update.rejected=observations.size();return update;
  }
  for(auto iterator=surfels_.begin();iterator!=surfels_.end();){
    if(now_ns>iterator->second.last_seen_ns&&
       now_ns-iterator->second.last_seen_ns>config_.lifetime_ns){
      iterator=surfels_.erase(iterator);++update.pruned;
    }else ++iterator;
  }
  for(const auto& observation:observations){
    const double position_norm=observation.position_world_m.norm();
    const double normal_norm=observation.normal_world.norm();
    if(observation.timestamp_ns<=0||observation.timestamp_ns>now_ns||
       !finite(observation.position_world_m)||!std::isfinite(position_norm)||
       position_norm>config_.max_position_norm_m||!std::isfinite(observation.confidence)||
       observation.confidence<config_.min_observation_confidence||observation.confidence>1||
       !std::isfinite(observation.radius_m)||observation.radius_m<config_.min_radius_m||
       observation.radius_m>config_.max_radius_m||
       (observation.normal_valid&&(!finite(observation.normal_world)||
                                   !std::isfinite(normal_norm)||normal_norm<1e-9))){
      ++update.rejected;continue;
    }
    const VoxelKey key=voxelFor(observation.position_world_m);
    auto iterator=surfels_.find(key);
    if(iterator==surfels_.end()){
      if(!makeRoom(update)){++update.rejected;continue;}
      Surfel surfel;surfel.id=next_id_++;surfel.position_world_m=observation.position_world_m;
      surfel.confidence=observation.confidence;surfel.radius_m=observation.radius_m;
      surfel.observation_count=1;surfel.first_seen_ns=surfel.last_seen_ns=observation.timestamp_ns;
      surfel.accumulated_weight=observation.confidence;
      if(observation.normal_valid){surfel.normal_world=observation.normal_world*(1.0/normal_norm);
        surfel.normal_valid=true;surfel.accumulated_normal_weight=observation.confidence;}
      surfel.confirmed=config_.min_confirming_observations<=1;
      if(surfel.confirmed)++update.created;
      surfels_.emplace(key,surfel);continue;
    }
    Surfel& surfel=iterator->second;
    if(observation.timestamp_ns<surfel.last_seen_ns){++update.rejected;continue;}
    if(surfel.accumulated_weight>1000.0){
      const double scale=1000.0/surfel.accumulated_weight;
      surfel.accumulated_weight=1000.0;surfel.accumulated_normal_weight*=scale;
    }
    const bool was_confirmed=surfel.confirmed;
    const double combined=surfel.accumulated_weight+observation.confidence;
    surfel.position_world_m=(surfel.position_world_m*surfel.accumulated_weight+
      observation.position_world_m*observation.confidence)*(1.0/combined);
    surfel.radius_m=(surfel.radius_m*surfel.accumulated_weight+
      observation.radius_m*observation.confidence)*(1.0/combined);
    surfel.confidence=(surfel.confidence*surfel.accumulated_weight+
      observation.confidence*observation.confidence)*(1.0/combined);
    surfel.accumulated_weight=combined;
    if(observation.normal_valid){
      Vec3 normal=observation.normal_world*(1.0/normal_norm);
      if(surfel.normal_valid&&surfel.normal_world.x*normal.x+
         surfel.normal_world.y*normal.y+surfel.normal_world.z*normal.z<0)normal=normal*(-1.0);
      const Vec3 fused=surfel.normal_world*surfel.accumulated_normal_weight+
                       normal*observation.confidence;
      const double fused_norm=fused.norm();
      if(fused_norm>=1e-9){surfel.normal_world=fused*(1.0/fused_norm);surfel.normal_valid=true;}
      surfel.accumulated_normal_weight+=observation.confidence;
    }
    ++surfel.observation_count;surfel.last_seen_ns=observation.timestamp_ns;
    surfel.confirmed=surfel.observation_count>=config_.min_confirming_observations;
    if(!was_confirmed&&surfel.confirmed)++update.created;
    else if(surfel.confirmed)++update.updated;
  }
  return update;
}

std::vector<Surfel> SurfelMap::snapshot(bool include_unconfirmed) const {
  std::vector<Surfel> output;output.reserve(surfels_.size());
  for(const auto& item:surfels_)
    if(include_unconfirmed||item.second.confirmed)output.push_back(item.second);
  std::sort(output.begin(),output.end(),[](const Surfel& a,const Surfel& b){return a.id<b.id;});
  return output;
}

std::optional<SurfelHit> SurfelMap::raycast(const Vec3& origin,const Vec3& direction,
    double min_distance_m,double max_distance_m) const {
  const double direction_norm=direction.norm();
  if(!finite(origin)||!finite(direction)||!std::isfinite(direction_norm)||direction_norm<1e-9||
     !std::isfinite(min_distance_m)||!std::isfinite(max_distance_m)||min_distance_m<0||
     max_distance_m<=min_distance_m)return std::nullopt;
  const Vec3 ray=direction*(1.0/direction_norm);
  std::optional<SurfelHit> nearest;
  for(const auto& item:surfels_){const Surfel& surfel=item.second;if(!surfel.confirmed)continue;
    double distance=std::numeric_limits<double>::infinity();Vec3 hit_normal=surfel.normal_world;
    if(surfel.normal_valid){
      const double denominator=surfel.normal_world.x*ray.x+surfel.normal_world.y*ray.y+
                               surfel.normal_world.z*ray.z;
      if(std::abs(denominator)<1e-8)continue;
      const Vec3 offset=surfel.position_world_m-origin;
      distance=(offset.x*surfel.normal_world.x+offset.y*surfel.normal_world.y+
                offset.z*surfel.normal_world.z)/denominator;
      if(distance<min_distance_m||distance>max_distance_m)continue;
      const Vec3 point=origin+ray*distance;
      if((point-surfel.position_world_m).norm()>surfel.radius_m)continue;
    }else{
      const double sphere_radius=std::min(surfel.radius_m,config_.voxel_size_m*0.5);
      const Vec3 offset=origin-surfel.position_world_m;
      const double projection=offset.x*ray.x+offset.y*ray.y+offset.z*ray.z;
      const double squared=offset.x*offset.x+offset.y*offset.y+offset.z*offset.z-
                           sphere_radius*sphere_radius;
      const double discriminant=projection*projection-squared;
      if(discriminant<0)continue;
      const double root=std::sqrt(discriminant);
      distance=-projection-root;
      if(distance<min_distance_m)distance=-projection+root;
      if(distance<min_distance_m||distance>max_distance_m)continue;
      const Vec3 point=origin+ray*distance;hit_normal=point-surfel.position_world_m;
      const double normal_norm=hit_normal.norm();
      if(normal_norm<1e-9)hit_normal=ray*(-1.0);else hit_normal=hit_normal*(1.0/normal_norm);
    }
    if(!nearest||distance<nearest->distance_m||
       (distance==nearest->distance_m&&surfel.id<nearest->surfel_id))
      nearest=SurfelHit{surfel.id,origin+ray*distance,hit_normal,distance,
                        surfel.confidence,surfel.normal_valid};
  }
  return nearest;
}

SurfelDepthImage SurfelMap::renderDepth(const PinholeRadTanCamera& camera,
    const Pose& world_from_camera,SurfelDepthRenderConfig config) const {
  SurfelDepthImage output;const auto& intrinsics=camera.intrinsics();
  output.width=intrinsics.width;output.height=intrinsics.height;
  const auto& q=world_from_camera.rotation;
  const double quaternion_norm=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
  if(!finite(world_from_camera.translation)||!std::isfinite(quaternion_norm)||
     quaternion_norm<1e-9||!std::isfinite(config.min_depth_m)||
     !std::isfinite(config.max_depth_m)||config.min_depth_m<=0||
     config.max_depth_m<=config.min_depth_m||config.max_projected_radius_px<=0||
     config.max_output_pixels==0||config.max_rasterized_pixels==0)return output;
  const auto width=static_cast<std::size_t>(intrinsics.width);
  const auto height=static_cast<std::size_t>(intrinsics.height);
  if(width>std::numeric_limits<std::size_t>::max()/height)return output;
  const std::size_t pixel_count=width*height;
  if(pixel_count>config.max_output_pixels)return output;
  output.depth_z_m.assign(pixel_count,0.0F);output.valid=true;
  const Quaternion camera_from_world=q.inverse();
  for(const auto& surfel:snapshot()){
    if(!surfel.normal_valid)continue;
    const Vec3 center_camera=camera_from_world.rotate(
      surfel.position_world_m-world_from_camera.translation);
    if(center_camera.z+surfel.radius_m<config.min_depth_m||
       center_camera.z-surfel.radius_m>config.max_depth_m||center_camera.z<=1e-9)continue;
    const auto center_pixel=camera.project(center_camera);if(!center_pixel)continue;
    const double projected_radius=std::max(intrinsics.fx,intrinsics.fy)*surfel.radius_m/
      std::max(config.min_depth_m,center_camera.z-surfel.radius_m);
    if(!std::isfinite(projected_radius))continue;
    int radius=config.max_projected_radius_px;
    if(projected_radius>config.max_projected_radius_px)output.truncated=true;
    else radius=std::max(1,static_cast<int>(std::ceil(projected_radius)));
    if(!std::isfinite(center_pixel->x)||!std::isfinite(center_pixel->y)||
       center_pixel->x<-radius||center_pixel->x>intrinsics.width-1+radius||
       center_pixel->y<-radius||center_pixel->y>intrinsics.height-1+radius)continue;
    const int minimum_x=std::max(0,static_cast<int>(std::floor(center_pixel->x))-radius);
    const int maximum_x=std::min(intrinsics.width-1,
      static_cast<int>(std::ceil(center_pixel->x))+radius);
    const int minimum_y=std::max(0,static_cast<int>(std::floor(center_pixel->y))-radius);
    const int maximum_y=std::min(intrinsics.height-1,
      static_cast<int>(std::ceil(center_pixel->y))+radius);
    if(minimum_x>maximum_x||minimum_y>maximum_y)continue;
    ++output.projected_surfel_count;
    const Vec3 normal_camera=camera_from_world.rotate(surfel.normal_world);
    const double plane_numerator=center_camera.x*normal_camera.x+
      center_camera.y*normal_camera.y+center_camera.z*normal_camera.z;
    for(int y=minimum_y;y<=maximum_y;++y){
      for(int x=minimum_x;x<=maximum_x;++x){
        if(output.rasterized_pixel_count>=config.max_rasterized_pixels){
          output.truncated=true;return output;
        }
        ++output.rasterized_pixel_count;
        const auto ray=camera.unproject({static_cast<double>(x),static_cast<double>(y)});
        if(!ray)continue;
        const double denominator=ray->x*normal_camera.x+ray->y*normal_camera.y+
                                 ray->z*normal_camera.z;
        if(std::abs(denominator)<1e-8)continue;
        const double distance=plane_numerator/denominator;
        if(distance<=0)continue;
        const Vec3 point=*ray*distance;
        if(point.z<config.min_depth_m||point.z>config.max_depth_m||
           (point-center_camera).norm()>surfel.radius_m)continue;
        float& depth=output.depth_z_m[static_cast<std::size_t>(y)*width+x];
        const float value=static_cast<float>(point.z);
        if(depth==0.0F||value<depth)depth=value;
      }
    }
  }
  return output;
}

std::size_t SurfelMap::size() const {
  std::size_t count=0;for(const auto& item:surfels_)if(item.second.confirmed)++count;return count;
}

void SurfelMap::reset(){surfels_.clear();next_id_=1;}

}  // namespace arkernel
