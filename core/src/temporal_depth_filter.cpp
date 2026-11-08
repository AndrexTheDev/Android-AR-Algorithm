#include "arkernel/temporal_depth_filter.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
bool finitePose(const Pose& pose){
  const auto& q=pose.rotation;const auto& t=pose.translation;
  const double n=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
  return std::isfinite(n)&&n>1e-9&&std::isfinite(t.x)&&std::isfinite(t.y)&&std::isfinite(t.z);
}
float validDepth(float value,double minimum,double maximum){
  return std::isfinite(value)&&value>=minimum&&value<=maximum?value:0.0F;
}
float validConfidence(const TemporalDepthFrameView& frame,int x,int y){
  if(!frame.confidence)return 1.0F;
  const float value=frame.confidence[static_cast<std::size_t>(y)*
    frame.confidence_row_stride_elements+x];
  return std::isfinite(value)?std::clamp(value,0.0F,1.0F):0.0F;
}
}
TemporalDepthFilter::TemporalDepthFilter(TemporalDepthFilterConfig config):config_(config){
  if(!std::isfinite(config.min_depth_m)||!std::isfinite(config.max_depth_m)||
     config.min_depth_m<=0||config.max_depth_m<=config.min_depth_m||
     !std::isfinite(config.max_relative_difference)||config.max_relative_difference<0||
     config.max_relative_difference>1||!std::isfinite(config.max_absolute_difference_m)||
     config.max_absolute_difference_m<0||!std::isfinite(config.history_weight)||
     config.history_weight<0||config.history_weight>1||config.max_frame_gap_ns<=0||
     config.max_pixels==0)throw std::invalid_argument("Invalid temporal depth configuration");
}
void TemporalDepthFilter::reset(){width_=height_=0;timestamp_ns_=0;intrinsics_={};
  depth_.clear();confidence_.clear();}
TemporalDepthResult TemporalDepthFilter::update(const TemporalDepthFrameView& frame,
                                                const PinholeRadTanCamera& camera){
  if(frame.width<=0||frame.height<=0||frame.row_stride_elements<frame.width||
     !frame.depth_m||frame.timestamp_ns<=0||!finitePose(frame.world_from_camera)||
     camera.intrinsics().width!=frame.width||camera.intrinsics().height!=frame.height||
     (frame.confidence&&frame.confidence_row_stride_elements<frame.width))
    throw std::invalid_argument("Invalid temporal depth frame");
  const auto width=static_cast<std::size_t>(frame.width),height=static_cast<std::size_t>(frame.height);
  if(width>std::numeric_limits<std::size_t>::max()/height||width*height>config_.max_pixels)
    throw std::length_error("Temporal depth pixel budget exceeded");
  const auto rows=static_cast<std::size_t>(frame.height-1);
  const auto depth_stride=static_cast<std::size_t>(frame.row_stride_elements);
  if(rows>(std::numeric_limits<std::size_t>::max()-width)/depth_stride)
    throw std::invalid_argument("Temporal depth stride overflow");
  const auto required_depth=rows*depth_stride+width;
  if(required_depth>frame.depth_elements)throw std::invalid_argument("Truncated temporal depth");
  if(frame.confidence){const auto confidence_stride=
      static_cast<std::size_t>(frame.confidence_row_stride_elements);
    if(rows>(std::numeric_limits<std::size_t>::max()-width)/confidence_stride)
      throw std::invalid_argument("Temporal confidence stride overflow");
    const auto required_confidence=rows*confidence_stride+width;
    if(required_confidence>frame.confidence_elements)
      throw std::invalid_argument("Truncated temporal confidence");}
  if(timestamp_ns_>0&&frame.timestamp_ns<=timestamp_ns_)
    throw std::invalid_argument("Temporal depth timestamps must increase");
  TemporalDepthResult result;result.width=frame.width;result.height=frame.height;
  result.depth_m.assign(width*height,0);result.confidence.assign(width*height,0);
  for(int y=0;y<frame.height;++y)for(int x=0;x<frame.width;++x){
    const auto target=static_cast<std::size_t>(y)*width+x;
    result.depth_m[target]=validDepth(frame.depth_m[static_cast<std::size_t>(y)*
      frame.row_stride_elements+x],config_.min_depth_m,config_.max_depth_m);
    result.confidence[target]=result.depth_m[target]>0?validConfidence(frame,x,y):0;
  }
  const auto& current_intrinsics=camera.intrinsics();
  const bool compatible=timestamp_ns_>0&&width_==frame.width&&height_==frame.height&&
    intrinsics_.fx==current_intrinsics.fx&&intrinsics_.fy==current_intrinsics.fy&&
    intrinsics_.cx==current_intrinsics.cx&&intrinsics_.cy==current_intrinsics.cy&&
    frame.timestamp_ns-timestamp_ns_<=config_.max_frame_gap_ns;
  if(compatible){
    result.history_used=true;
    std::vector<float> predicted(width*height,0),predicted_confidence(width*height,0);
    const Quaternion current_from_world=frame.world_from_camera.rotation.inverse();
    const Quaternion current_from_previous=current_from_world*world_from_camera_.rotation;
    const Vec3 translation=current_from_world.rotate(world_from_camera_.translation-
      frame.world_from_camera.translation);
    for(int y=0;y<height_;++y)for(int x=0;x<width_;++x){
      const auto source=static_cast<std::size_t>(y)*width+x;const float z=depth_[source];
      if(z<=0||confidence_[source]<=0)continue;
      const auto ray=camera.unproject({static_cast<double>(x),static_cast<double>(y)});
      if(!ray||ray->z<=1e-9)continue;
      const Vec3 previous_point=*ray*(z/ray->z);
      const Vec3 current_point=current_from_previous.rotate(previous_point)+translation;
      if(current_point.z<config_.min_depth_m||current_point.z>config_.max_depth_m)continue;
      const auto pixel=camera.project(current_point);if(!pixel)continue;
      const int px=static_cast<int>(std::lround(pixel->x));
      const int py=static_cast<int>(std::lround(pixel->y));
      if(px<0||py<0||px>=frame.width||py>=frame.height)continue;
      const auto target=static_cast<std::size_t>(py)*width+px;
      if(predicted[target]==0||current_point.z<predicted[target]){
        predicted[target]=static_cast<float>(current_point.z);
        predicted_confidence[target]=confidence_[source];}
    }
    for(std::size_t i=0;i<width*height;++i){
      if(result.depth_m[i]<=0||predicted[i]<=0)continue;
      const double difference=std::abs(result.depth_m[i]-predicted[i]);
      const double gate=std::max(config_.max_absolute_difference_m,
        config_.max_relative_difference*std::min(result.depth_m[i],predicted[i]));
      if(difference>gate){++result.rejected_history_pixels;continue;}
      const double current_weight=result.confidence[i];
      const double history_weight=config_.history_weight*predicted_confidence[i];
      const double total=current_weight+history_weight;
      if(total<=0)continue;
      result.depth_m[i]=static_cast<float>((current_weight*result.depth_m[i]+
        history_weight*predicted[i])/total);
      result.confidence[i]=static_cast<float>(std::clamp(1.0-(1.0-current_weight)*
        (1.0-history_weight),0.0,1.0));++result.blended_pixels;
    }
  }
  width_=frame.width;height_=frame.height;timestamp_ns_=frame.timestamp_ns;
  world_from_camera_=frame.world_from_camera;intrinsics_=current_intrinsics;
  depth_=result.depth_m;confidence_=result.confidence;
  return result;
}
}  // namespace arkernel
