#include "arkernel/detection_postprocessor.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
std::size_t elementSize(DetectionTensorElementType type){
  switch(type){case DetectionTensorElementType::kFloat32:return sizeof(float);
    case DetectionTensorElementType::kInt8:case DetectionTensorElementType::kUInt8:return 1;}
  return 0;
}
bool validView(const DetectionTensorView& tensor){
  const auto size=elementSize(tensor.element_type);
  if(!tensor.data||tensor.rows<=0||tensor.columns<=0||
     tensor.row_stride_elements<tensor.columns||size==0)return false;
  const auto rows=static_cast<std::size_t>(tensor.rows-1);
  const auto stride=static_cast<std::size_t>(tensor.row_stride_elements);
  const auto columns=static_cast<std::size_t>(tensor.columns);
  if(rows>std::numeric_limits<std::size_t>::max()/stride)return false;
  const auto offset=rows*stride;
  if(offset>std::numeric_limits<std::size_t>::max()-columns)return false;
  const auto elements=offset+columns;
  return elements<=std::numeric_limits<std::size_t>::max()/size&&
    elements*size<=tensor.data_size_bytes;
}
double readValue(const DetectionTensorView& tensor,int row,int column){
  const auto index=static_cast<std::size_t>(row)*tensor.row_stride_elements+column;
  const auto* bytes=static_cast<const std::uint8_t*>(tensor.data);
  if(tensor.element_type==DetectionTensorElementType::kFloat32){
    float value=0;std::memcpy(&value,bytes+index*sizeof(float),sizeof(value));return value;
  }
  if(tensor.element_type==DetectionTensorElementType::kInt8){
    std::int8_t value=0;std::memcpy(&value,bytes+index,1);
    return (static_cast<int>(value)-tensor.quantization_zero_point)*tensor.quantization_scale;
  }
  return (static_cast<int>(bytes[index])-tensor.quantization_zero_point)*
    tensor.quantization_scale;
}
double intersectionOverUnion(const DecodedDetection& a,const DecodedDetection& b){
  const double left=std::max(a.left,b.left),top=std::max(a.top,b.top);
  const double right=std::min(a.right,b.right),bottom=std::min(a.bottom,b.bottom);
  const double intersection=std::max(0.0,right-left)*std::max(0.0,bottom-top);
  const double area_a=(a.right-a.left)*(a.bottom-a.top);
  const double area_b=(b.right-b.left)*(b.bottom-b.top);
  const double united=area_a+area_b-intersection;
  return united>0?intersection/united:0;
}
}

std::vector<DecodedDetection> decodeDetectionTensor(const DetectionTensorView& tensor,
                                                     DetectionPostprocessConfig config){
  const std::array<int,6> columns{config.x0_column,config.y0_column,
    config.x1_or_width_column,config.y1_or_height_column,config.score_column,
    config.class_column};
  if(!validView(tensor)||config.image_width<=0||config.image_height<=0||
     std::any_of(columns.begin(),columns.end(),[&](int column){
       return column<0||column>=tensor.columns;})||
     !std::isfinite(config.score_threshold)||config.score_threshold<0||
     config.score_threshold>1||!std::isfinite(config.iou_threshold)||
     config.iou_threshold<0||config.iou_threshold>1||config.max_candidates==0||
     config.max_candidates>4096||config.max_detections==0||config.max_detections>256||
     (config.box_format!=DetectionBoxFormat::kXyxy&&
      config.box_format!=DetectionBoxFormat::kCenterXywh))
    throw std::invalid_argument("Invalid detection tensor or configuration");
  if(static_cast<std::size_t>(tensor.rows)>config.max_candidates)
    throw std::length_error("Detection candidate budget exceeded");
  if(tensor.element_type!=DetectionTensorElementType::kFloat32&&
     (!std::isfinite(tensor.quantization_scale)||tensor.quantization_scale<=0))
    throw std::invalid_argument("Invalid detection quantization scale");
  if((tensor.element_type==DetectionTensorElementType::kInt8&&
      (tensor.quantization_zero_point< -128||tensor.quantization_zero_point>127))||
     (tensor.element_type==DetectionTensorElementType::kUInt8&&
      (tensor.quantization_zero_point<0||tensor.quantization_zero_point>255)))
    throw std::invalid_argument("Invalid detection quantization zero point");
  std::vector<DecodedDetection> candidates;candidates.reserve(tensor.rows);
  for(int row=0;row<tensor.rows;++row){
    const double score=readValue(tensor,row,config.score_column);
    const double raw_class=readValue(tensor,row,config.class_column);
    if(!std::isfinite(score)||score<config.score_threshold||score>1||
       !std::isfinite(raw_class)||raw_class<0||
       raw_class>std::numeric_limits<std::uint32_t>::max())continue;
    const double rounded_class=std::round(raw_class);
    if(std::abs(raw_class-rounded_class)>1e-4)continue;
    double a=readValue(tensor,row,config.x0_column);
    double b=readValue(tensor,row,config.y0_column);
    double c=readValue(tensor,row,config.x1_or_width_column);
    double d=readValue(tensor,row,config.y1_or_height_column);
    if(!std::isfinite(a)||!std::isfinite(b)||!std::isfinite(c)||!std::isfinite(d))continue;
    if(config.normalized_coordinates){a*=config.image_width;b*=config.image_height;
      c*=config.image_width;d*=config.image_height;}
    double left=a,top=b,right=c,bottom=d;
    if(config.box_format==DetectionBoxFormat::kCenterXywh){
      if(c<=0||d<=0)continue;
      left=a-c*0.5;right=a+c*0.5;top=b-d*0.5;bottom=b+d*0.5;
    }
    left=std::clamp(left,0.0,static_cast<double>(config.image_width));
    right=std::clamp(right,0.0,static_cast<double>(config.image_width));
    top=std::clamp(top,0.0,static_cast<double>(config.image_height));
    bottom=std::clamp(bottom,0.0,static_cast<double>(config.image_height));
    if(right<=left||bottom<=top)continue;
    candidates.push_back({static_cast<std::uint32_t>(rounded_class),score,left,top,right,bottom,
      static_cast<std::size_t>(row)});
  }
  std::stable_sort(candidates.begin(),candidates.end(),[](const auto& a,const auto& b){
    if(a.confidence!=b.confidence)return a.confidence>b.confidence;
    return a.source_row<b.source_row;});
  std::vector<DecodedDetection> output;output.reserve(std::min(config.max_detections,
    candidates.size()));
  for(const auto& candidate:candidates){
    bool suppressed=false;
    for(const auto& accepted:output){
      if(config.class_aware_nms&&candidate.class_id!=accepted.class_id)continue;
      if(intersectionOverUnion(candidate,accepted)>config.iou_threshold){suppressed=true;break;}
    }
    if(!suppressed){output.push_back(candidate);if(output.size()==config.max_detections)break;}
  }
  return output;
}

}  // namespace arkernel
