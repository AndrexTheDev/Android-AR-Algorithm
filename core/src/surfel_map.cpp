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
     config_.min_confirming_observations==0||config_.voxel_size_m<=0||
     config_.min_observation_confidence<0||config_.min_observation_confidence>1||
     config_.max_position_norm_m<=0||
     config_.max_position_norm_m/config_.voxel_size_m>
       static_cast<double>(std::numeric_limits<std::int32_t>::max()-1)||
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

std::size_t SurfelMap::size() const {
  std::size_t count=0;for(const auto& item:surfels_)if(item.second.confirmed)++count;return count;
}

void SurfelMap::reset(){surfels_.clear();next_id_=1;}

}  // namespace arkernel
