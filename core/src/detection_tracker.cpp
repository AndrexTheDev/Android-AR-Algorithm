#include "arkernel/detection_tracker.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
double iou(const DecodedDetection& a,const DecodedDetection& b){
  const double left=std::max(a.left,b.left),top=std::max(a.top,b.top);
  const double right=std::min(a.right,b.right),bottom=std::min(a.bottom,b.bottom);
  const double intersection=std::max(0.0,right-left)*std::max(0.0,bottom-top);
  const double area_a=(a.right-a.left)*(a.bottom-a.top);
  const double area_b=(b.right-b.left)*(b.bottom-b.top);
  const double united=area_a+area_b-intersection;
  return united>0?intersection/united:0;
}
bool valid(const DecodedDetection& value){
  return std::isfinite(value.confidence)&&value.confidence>=0&&value.confidence<=1&&
    std::isfinite(value.left)&&std::isfinite(value.top)&&std::isfinite(value.right)&&
    std::isfinite(value.bottom)&&value.right>value.left&&value.bottom>value.top;
}
}
DetectionTracker::DetectionTracker(DetectionTrackerConfig config):config_(config){
  if(!std::isfinite(config.min_iou)||config.min_iou<0||config.min_iou>1||
     config.max_tracks==0||config.max_tracks>256||config.max_missed_frames>1000||
     config.max_frame_gap_ns<=0)throw std::invalid_argument("Invalid detection tracker config");
}
void DetectionTracker::reset(){tracks_.clear();last_timestamp_ns_=0;}
std::vector<TrackedDetection> DetectionTracker::update(
    std::int64_t timestamp_ns,const std::vector<DecodedDetection>& detections){
  if(timestamp_ns<=0||(last_timestamp_ns_>0&&timestamp_ns<=last_timestamp_ns_)||
     detections.size()>256||std::any_of(detections.begin(),detections.end(),
       [](const auto& value){return !valid(value);}))
    throw std::invalid_argument("Invalid or out-of-order tracked detections");
  if(last_timestamp_ns_>0&&timestamp_ns-last_timestamp_ns_>config_.max_frame_gap_ns)
    tracks_.clear();
  last_timestamp_ns_=timestamp_ns;
  for(auto& track:tracks_)++track.missed;
  struct Candidate {double overlap=0;std::size_t detection=0,track=0;std::uint64_t track_id=0;};
  std::vector<Candidate> candidates;candidates.reserve(detections.size()*tracks_.size());
  for(std::size_t d=0;d<detections.size();++d)for(std::size_t t=0;t<tracks_.size();++t){
    if(detections[d].class_id!=tracks_[t].detection.class_id)continue;
    const double overlap=iou(detections[d],tracks_[t].detection);
    if(overlap>=config_.min_iou)candidates.push_back({overlap,d,t,tracks_[t].id});
  }
  std::sort(candidates.begin(),candidates.end(),[](const auto& a,const auto& b){
    if(a.overlap!=b.overlap)return a.overlap>b.overlap;
    if(a.detection!=b.detection)return a.detection<b.detection;
    return a.track_id<b.track_id;
  });
  std::vector<std::uint64_t> assigned(detections.size(),0);
  std::vector<bool> used_track(tracks_.size(),false);
  for(const auto& candidate:candidates){
    if(assigned[candidate.detection]!=0||used_track[candidate.track])continue;
    auto& track=tracks_[candidate.track];assigned[candidate.detection]=track.id;
    used_track[candidate.track]=true;track.detection=detections[candidate.detection];
    track.last_seen_ns=timestamp_ns;track.missed=0;
  }
  tracks_.erase(std::remove_if(tracks_.begin(),tracks_.end(),[&](const auto& track){
    return track.missed>config_.max_missed_frames;}),tracks_.end());
  for(std::size_t d=0;d<detections.size();++d){
    if(assigned[d]!=0)continue;
    if(tracks_.size()>=config_.max_tracks){
      auto evict=std::max_element(tracks_.begin(),tracks_.end(),[](const auto& a,const auto& b){
        if(a.missed!=b.missed)return a.missed<b.missed;
        if(a.last_seen_ns!=b.last_seen_ns)return a.last_seen_ns>b.last_seen_ns;
        if(a.detection.confidence!=b.detection.confidence)
          return a.detection.confidence>b.detection.confidence;
        return a.id>b.id;
      });
      if(evict!=tracks_.end()&&evict->missed>0)tracks_.erase(evict);else continue;
    }
    if(next_id_==0||next_id_>static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
      throw std::overflow_error("Detection track ID exhausted");
    assigned[d]=next_id_++;tracks_.push_back({assigned[d],detections[d],timestamp_ns,0});
  }
  std::vector<TrackedDetection> output;output.reserve(detections.size());
  for(std::size_t i=0;i<detections.size();++i)output.push_back({detections[i],assigned[i]});
  return output;
}
}  // namespace arkernel
