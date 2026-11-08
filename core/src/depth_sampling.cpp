#include "arkernel/depth_sampling.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
bool layoutValid(int width,int height,int stride,std::size_t count){
  if(width<=0||height<=0||stride<width)return false;
  const auto rows=static_cast<std::size_t>(height-1);
  const auto row_stride=static_cast<std::size_t>(stride);
  if(rows>std::numeric_limits<std::size_t>::max()/row_stride)return false;
  const auto offset=rows*row_stride;
  return static_cast<std::size_t>(width)<=count&&offset<=count-static_cast<std::size_t>(width);
}
Vec3 cross(const Vec3& a,const Vec3& b){
  return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};
}
}

bool validMetricDepthImage(const MetricDepthImageView& image){
  if(!image.depth_m||!layoutValid(image.width,image.height,image.row_stride_elements,
                                  image.depth_element_count))return false;
  if(!image.confidence)return image.confidence_row_stride_elements==0&&
                               image.confidence_element_count==0;
  return layoutValid(image.width,image.height,image.confidence_row_stride_elements,
                     image.confidence_element_count);
}

std::vector<SurfelObservation> sampleMetricDepthImage(
    const MetricDepthImageView& image,const PinholeRadTanCamera& camera,
    TimestampNs timestamp_ns,DepthSamplingConfig config){
  if(config.pixel_stride<=0||!std::isfinite(config.min_depth_m)||
     !std::isfinite(config.max_depth_m)||config.min_depth_m<=0||
     config.max_depth_m<=config.min_depth_m||!std::isfinite(config.min_confidence)||
     config.min_confidence<0||config.min_confidence>1||
     !std::isfinite(config.max_normal_depth_discontinuity_ratio)||
     config.max_normal_depth_discontinuity_ratio<=0||
     !std::isfinite(config.min_radius_m)||!std::isfinite(config.max_radius_m)||
     config.min_radius_m<=0||config.max_radius_m<config.min_radius_m||
     config.max_samples==0||config.exclusion_rects.size()>64)
    throw std::invalid_argument("Invalid depth sampling configuration");
  for(const auto& rect:config.exclusion_rects)
    if(!std::isfinite(rect.left)||!std::isfinite(rect.top)||
       !std::isfinite(rect.right)||!std::isfinite(rect.bottom)||
       rect.right<=rect.left||rect.bottom<=rect.top)
      throw std::invalid_argument("Invalid depth exclusion rectangle");
  std::vector<SurfelObservation> output;
  const auto& intrinsics=camera.intrinsics();
  if(timestamp_ns<=0||!validMetricDepthImage(image)||image.width!=intrinsics.width||
     image.height!=intrinsics.height)return output;
  const auto excluded=[&](int x,int y){
    return std::any_of(config.exclusion_rects.begin(),config.exclusion_rects.end(),
      [&](const DepthExclusionRect& rect){return x>=rect.left&&x<rect.right&&
        y>=rect.top&&y<rect.bottom;});
  };
  const auto sample_point=[&](int x,int y)->std::optional<Vec3>{
    if(excluded(x,y))return std::nullopt;
    const float depth=image.depth_m[static_cast<std::size_t>(y)*image.row_stride_elements+x];
    if(!std::isfinite(depth)||depth<config.min_depth_m||depth>config.max_depth_m)return std::nullopt;
    const auto ray=camera.unproject({static_cast<double>(x),static_cast<double>(y)});
    if(!ray||ray->z<=1e-9)return std::nullopt;
    return *ray*(static_cast<double>(depth)/ray->z);
  };
  output.reserve(std::min(config.max_samples,image.depth_element_count));
  const double focal=std::min(intrinsics.fx,intrinsics.fy);
  for(int y=0;y<image.height&&output.size()<config.max_samples;y+=config.pixel_stride){
    for(int x=0;x<image.width&&output.size()<config.max_samples;x+=config.pixel_stride){
      const double confidence=image.confidence?
        image.confidence[static_cast<std::size_t>(y)*image.confidence_row_stride_elements+x]:1.0;
      if(!std::isfinite(confidence)||confidence<config.min_confidence||confidence>1)continue;
      const auto center=sample_point(x,y);if(!center)continue;
      SurfelObservation observation;observation.position_world_m=*center;
      observation.confidence=confidence;observation.timestamp_ns=timestamp_ns;
      observation.radius_m=std::clamp(center->z*config.pixel_stride/focal,
                                      config.min_radius_m,config.max_radius_m);
      if(config.estimate_normals&&x+config.pixel_stride<image.width&&
         y+config.pixel_stride<image.height){
        const auto right=sample_point(x+config.pixel_stride,y);
        const auto down=sample_point(x,y+config.pixel_stride);
        if(right&&down&&std::abs(right->z-center->z)<=
             center->z*config.max_normal_depth_discontinuity_ratio&&
           std::abs(down->z-center->z)<=center->z*config.max_normal_depth_discontinuity_ratio){
          const Vec3 normal=cross(*right-*center,*down-*center);const double norm=normal.norm();
          if(std::isfinite(norm)&&norm>1e-9){observation.normal_world=normal*(1.0/norm);
            observation.normal_valid=true;}
        }
      }
      output.push_back(observation);
    }
  }
  return output;
}

}  // namespace arkernel
