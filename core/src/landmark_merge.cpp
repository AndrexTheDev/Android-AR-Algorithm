#include "arkernel/landmark_merge.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_map>

namespace arkernel {
namespace {
bool finite(const Vec3& value){return std::isfinite(value.x)&&std::isfinite(value.y)&&std::isfinite(value.z);}
}

LandmarkMergeResult mergeGlobalLandmarks(const std::vector<GlobalLandmark>& landmarks,
    const std::vector<LandmarkMergeAssociation>& associations,LandmarkMergeConfig config){
  if(config.max_landmarks==0||config.max_associations==0||
     !std::isfinite(config.max_cluster_diameter_m)||config.max_cluster_diameter_m<=0)
    throw std::invalid_argument("Invalid landmark merge configuration");
  LandmarkMergeResult result;
  if(landmarks.empty()||landmarks.size()>config.max_landmarks||
     associations.size()>config.max_associations)return result;
  std::vector<GlobalLandmark> input=landmarks;
  std::sort(input.begin(),input.end(),[](const auto& a,const auto& b){return a.id<b.id;});
  std::unordered_map<GlobalLandmarkId,std::size_t> indices;indices.reserve(input.size());
  for(std::size_t i=0;i<input.size();++i){const auto& value=input[i];
    if(value.id==0||!finite(value.position_world_m)||value.observation_count==0||
       !std::isfinite(value.mean_residual_m)||value.mean_residual_m<0||value.last_seen_ns<=0||
       !indices.emplace(value.id,i).second)return result;
  }
  std::vector<std::size_t> parent(input.size());
  std::vector<std::vector<std::size_t>> members(input.size());
  for(std::size_t i=0;i<input.size();++i){parent[i]=i;members[i].push_back(i);}
  auto root=[&](std::size_t value){while(parent[value]!=value)value=parent[value];return value;};
  std::vector<LandmarkMergeAssociation> ordered=associations;
  for(auto& pair:ordered)if(pair.second_id<pair.first_id)std::swap(pair.first_id,pair.second_id);
  std::sort(ordered.begin(),ordered.end(),[](const auto& a,const auto& b){
    return a.first_id==b.first_id?a.second_id<b.second_id:a.first_id<b.first_id;});
  ordered.erase(std::unique(ordered.begin(),ordered.end(),[](const auto& a,const auto& b){
    return a.first_id==b.first_id&&a.second_id==b.second_id;}),ordered.end());
  for(const auto& pair:ordered){const auto first=indices.find(pair.first_id),second=indices.find(pair.second_id);
    if(first==indices.end()||second==indices.end()||pair.first_id==pair.second_id)return result;
    std::size_t a=root(first->second),b=root(second->second);if(a==b){++result.accepted_associations;continue;}
    bool compatible=true;
    for(const auto left:members[a])for(const auto right:members[b])
      if((input[left].position_world_m-input[right].position_world_m).norm()>
         config.max_cluster_diameter_m)compatible=false;
    if(!compatible){++result.rejected_associations;continue;}
    if(input[members[b].front()].id<input[members[a].front()].id)std::swap(a,b);
    parent[b]=a;members[a].insert(members[a].end(),members[b].begin(),members[b].end());
    members[b].clear();++result.accepted_associations;
  }
  result.landmarks.reserve(input.size());result.aliases.reserve(input.size());
  for(std::size_t i=0;i<input.size();++i)if(parent[i]==i){
    const auto& cluster=members[i];GlobalLandmark merged;merged.id=input[cluster.front()].id;
    long double total_weight=0,residual_sum=0;Vec3 weighted{};
    for(const auto index:cluster){const auto& value=input[index];
      if(std::numeric_limits<std::size_t>::max()-merged.observation_count<value.observation_count)
        return LandmarkMergeResult{};
      merged.observation_count+=value.observation_count;
      const double weight=static_cast<double>(value.observation_count);total_weight+=weight;
      weighted+=value.position_world_m*weight;residual_sum+=weight*value.mean_residual_m;
      merged.last_seen_ns=std::max(merged.last_seen_ns,value.last_seen_ns);
      merged.id=std::min(merged.id,value.id);
    }
    const double inverse=1.0/static_cast<double>(total_weight);
    merged.position_world_m=weighted*inverse;
    merged.mean_residual_m=static_cast<double>(residual_sum/total_weight);
    if(!finite(merged.position_world_m)||!std::isfinite(merged.mean_residual_m))
      return LandmarkMergeResult{};
    result.landmarks.push_back(merged);
    for(const auto index:cluster)result.aliases.push_back({input[index].id,merged.id});
  }
  std::sort(result.landmarks.begin(),result.landmarks.end(),[](const auto& a,const auto& b){return a.id<b.id;});
  std::sort(result.aliases.begin(),result.aliases.end(),[](const auto& a,const auto& b){return a.source_id<b.source_id;});
  result.valid=true;return result;
}

}  // namespace arkernel
