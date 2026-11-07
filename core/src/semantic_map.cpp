#include "arkernel/semantic_map.h"
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

SemanticObjectMap::SemanticObjectMap(SemanticMapConfig config):config_(config){
  if(config_.max_objects<1||config_.min_confirming_observations<1||
     config_.min_observation_confidence<0||config_.min_observation_confidence>1||
     config_.association_distance_m<=0||
     config_.max_tracked_jump_m<config_.association_distance_m||
     config_.max_position_norm_m<=0||
     config_.static_lifetime_ns<=0||config_.dynamic_lifetime_ns<=0)
    throw std::invalid_argument("Invalid semantic map configuration");
}

void SemanticObjectMap::makeRoom(SemanticMapUpdate& update){
  if(objects_.size()<config_.max_objects)return;
  const auto victim=std::min_element(objects_.begin(),objects_.end(),[](const auto& a,const auto& b){
    const auto& left=a.second;const auto& right=b.second;
    if(left.confirmed!=right.confirmed)return !left.confirmed;
    if(left.dynamic!=right.dynamic)return left.dynamic;
    if(left.observation_count!=right.observation_count)
      return left.observation_count<right.observation_count;
    if(left.confidence!=right.confidence)return left.confidence<right.confidence;
    if(left.last_seen_ns!=right.last_seen_ns)return left.last_seen_ns<right.last_seen_ns;
    return left.id<right.id;
  });
  if(victim!=objects_.end()){objects_.erase(victim);++update.pruned;}
}

SemanticObject* SemanticObjectMap::associate(const SemanticObjectObservation& observation){
  SemanticObject* tracked=nullptr;
  if(observation.source_track_id>=0){
    for(auto& entry:objects_){auto& object=entry.second;
      if(object.class_id!=observation.class_id||object.dynamic!=observation.dynamic||
         object.source_track_id!=observation.source_track_id)continue;
      if((object.position_world_m-observation.position_world_m).norm()<=config_.max_tracked_jump_m&&
         (!tracked||object.id<tracked->id))tracked=&object;
    }
    if(tracked)return tracked;
  }
  SemanticObject* nearest=nullptr;double nearest_distance=config_.association_distance_m;
  for(auto& entry:objects_){auto& object=entry.second;
    if(object.class_id!=observation.class_id||object.dynamic!=observation.dynamic)continue;
    if(observation.source_track_id>=0&&object.source_track_id>=0&&
       observation.source_track_id!=object.source_track_id)continue;
    const double distance=(object.position_world_m-observation.position_world_m).norm();
    if(distance<nearest_distance||(std::abs(distance-nearest_distance)<1e-12&&
       nearest&&object.id<nearest->id)){
      nearest=&object;nearest_distance=distance;
    }
  }
  return nearest;
}

SemanticMapUpdate SemanticObjectMap::integrate(
    const std::vector<SemanticObjectObservation>& observations,TimestampNs now_ns){
  if(now_ns<=0)throw std::invalid_argument("Semantic map timestamp must be positive");
  SemanticMapUpdate update;
  for(auto it=objects_.begin();it!=objects_.end();){
    const TimestampNs lifetime=it->second.dynamic?config_.dynamic_lifetime_ns:
      config_.static_lifetime_ns;
    if(now_ns-it->second.last_seen_ns>lifetime){it=objects_.erase(it);++update.pruned;}
    else ++it;
  }
  for(const auto& observation:observations){
    if(observation.timestamp_ns<=0||observation.timestamp_ns>now_ns||
       !finite(observation.position_world_m)||
       observation.position_world_m.norm()>config_.max_position_norm_m||
       !std::isfinite(observation.confidence)||
       observation.confidence<config_.min_observation_confidence||observation.confidence>1){
      ++update.rejected;continue;
    }
    auto* object=associate(observation);
    if(!object){
      makeRoom(update);
      if(next_id_==std::numeric_limits<SemanticObjectId>::max())
        throw std::overflow_error("Semantic object ID range exhausted");
      const SemanticObjectId id=next_id_++;
      const bool confirmed=config_.min_confirming_observations==1;
      objects_.emplace(id,SemanticObject{id,observation.class_id,observation.source_track_id,
        observation.position_world_m,observation.confidence,1,observation.timestamp_ns,
        observation.timestamp_ns,observation.dynamic,confirmed,observation.confidence});
      if(confirmed)++update.created;
      continue;
    }
    if(observation.timestamp_ns<=object->last_seen_ns){++update.rejected;continue;}
    const bool was_confirmed=object->confirmed;
    const double combined_weight=object->accumulated_weight+observation.confidence;
    object->position_world_m=(object->position_world_m*object->accumulated_weight+
      observation.position_world_m*observation.confidence)*(1.0/combined_weight);
    object->confidence=(object->confidence*static_cast<double>(object->observation_count)+
      observation.confidence)/static_cast<double>(object->observation_count+1);
    object->accumulated_weight=combined_weight;++object->observation_count;
    object->last_seen_ns=observation.timestamp_ns;
    if(observation.source_track_id>=0)object->source_track_id=observation.source_track_id;
    object->confirmed=object->observation_count>=config_.min_confirming_observations;
    if(!was_confirmed&&object->confirmed)++update.created;
    else if(object->confirmed)++update.updated;
  }
  return update;
}

std::optional<SemanticObject> SemanticObjectMap::find(SemanticObjectId id) const{
  const auto found=objects_.find(id);
  return found==objects_.end()?std::nullopt:std::optional<SemanticObject>(found->second);
}
std::vector<SemanticObject> SemanticObjectMap::snapshot(bool include_unconfirmed) const{
  std::vector<SemanticObject> result;result.reserve(objects_.size());
  for(const auto& entry:objects_)
    if(include_unconfirmed||entry.second.confirmed)result.push_back(entry.second);
  std::sort(result.begin(),result.end(),[](const auto& a,const auto& b){return a.id<b.id;});
  return result;
}
std::size_t SemanticObjectMap::size() const{
  return static_cast<std::size_t>(std::count_if(objects_.begin(),objects_.end(),
    [](const auto& entry){return entry.second.confirmed;}));
}
void SemanticObjectMap::reset(){objects_.clear();next_id_=1;}

}  // namespace arkernel
