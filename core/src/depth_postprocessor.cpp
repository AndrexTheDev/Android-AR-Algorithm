#include "arkernel/depth_postprocessor.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
std::size_t elementSize(DepthTensorElementType type){
  switch(type){case DepthTensorElementType::kFloat32:return sizeof(float);
    case DepthTensorElementType::kInt8:case DepthTensorElementType::kUInt8:return 1;}
  return 0;
}

bool validView(const DepthTensorView& tensor){
  const std::size_t element_size=elementSize(tensor.element_type);
  if(!tensor.data||tensor.width<=0||tensor.height<=0||element_size==0||
     tensor.row_stride_elements<tensor.width)return false;
  const auto stride=static_cast<std::size_t>(tensor.row_stride_elements);
  const auto rows=static_cast<std::size_t>(tensor.height-1);
  const auto width=static_cast<std::size_t>(tensor.width);
  if(rows>std::numeric_limits<std::size_t>::max()/stride)return false;
  const auto last=rows*stride;
  if(last>std::numeric_limits<std::size_t>::max()-width)return false;
  const auto elements=last+width;
  return elements<=std::numeric_limits<std::size_t>::max()/element_size&&
         elements*element_size<=tensor.data_size_bytes;
}

double readRaw(const DepthTensorView& tensor,int x,int y){
  const auto index=static_cast<std::size_t>(y)*tensor.row_stride_elements+x;
  const auto* bytes=static_cast<const std::uint8_t*>(tensor.data);
  if(tensor.element_type==DepthTensorElementType::kFloat32){
    float value=0;std::memcpy(&value,bytes+index*sizeof(float),sizeof(value));return value;
  }
  if(tensor.element_type==DepthTensorElementType::kInt8){
    std::int8_t value=0;std::memcpy(&value,bytes+index,1);
    return (static_cast<int>(value)-tensor.quantization_zero_point)*tensor.quantization_scale;
  }
  return (static_cast<int>(bytes[index])-tensor.quantization_zero_point)*
         tensor.quantization_scale;
}

double metricDepth(const DepthTensorView& tensor,const DepthPostprocessConfig& config,
                   int x,int y){
  const double raw=readRaw(tensor,x,y);
  if(!std::isfinite(raw))return 0;
  double depth=raw;
  if(config.transform==DepthValueTransform::kInverse){if(raw<=0)return 0;depth=1.0/raw;}
  depth*=config.unit_to_meters;
  return std::isfinite(depth)&&depth>=config.min_depth_meters&&
    depth<=config.max_depth_meters?depth:0;
}
}

PostprocessedDepth postprocessDepthTensor(const DepthTensorView& tensor,
                                          DepthPostprocessConfig config){
  if(!validView(tensor)||config.output_width<=0||config.output_height<=0||
     !std::isfinite(config.unit_to_meters)||config.unit_to_meters<=0||
     !std::isfinite(config.min_depth_meters)||!std::isfinite(config.max_depth_meters)||
     config.min_depth_meters<=0||config.max_depth_meters<=config.min_depth_meters||
     !std::isfinite(config.max_relative_discontinuity)||
     config.max_relative_discontinuity<0||config.max_relative_discontinuity>10||
     config.max_output_elements==0||
     (config.transform!=DepthValueTransform::kDirect&&
      config.transform!=DepthValueTransform::kInverse))
    throw std::invalid_argument("Invalid depth tensor or postprocess configuration");
  if(tensor.element_type!=DepthTensorElementType::kFloat32&&
     (!std::isfinite(tensor.quantization_scale)||tensor.quantization_scale<=0))
    throw std::invalid_argument("Invalid depth quantization scale");
  if((tensor.element_type==DepthTensorElementType::kInt8&&
      (tensor.quantization_zero_point< -128||tensor.quantization_zero_point>127))||
     (tensor.element_type==DepthTensorElementType::kUInt8&&
      (tensor.quantization_zero_point<0||tensor.quantization_zero_point>255)))
    throw std::invalid_argument("Invalid depth quantization zero point");
  const auto width=static_cast<std::size_t>(config.output_width);
  const auto height=static_cast<std::size_t>(config.output_height);
  if(width>std::numeric_limits<std::size_t>::max()/height||
     width*height>config.max_output_elements)
    throw std::length_error("Depth output exceeds element budget");
  PostprocessedDepth output;output.width=config.output_width;output.height=config.output_height;
  output.depth_meters.assign(width*height,0);output.confidence.assign(width*height,0);
  for(int y=0;y<config.output_height;++y){
    const double sy=std::clamp((static_cast<double>(y)+0.5)*tensor.height/
      config.output_height-0.5,0.0,static_cast<double>(tensor.height-1));
    const int y0=static_cast<int>(std::floor(sy)),y1=std::min(y0+1,tensor.height-1);
    const double wy=sy-y0;
    for(int x=0;x<config.output_width;++x){
      const double sx=std::clamp((static_cast<double>(x)+0.5)*tensor.width/
        config.output_width-0.5,0.0,static_cast<double>(tensor.width-1));
      const int x0=static_cast<int>(std::floor(sx)),x1=std::min(x0+1,tensor.width-1);
      const double wx=sx-x0;
      const std::array<double,4> weights{(1-wx)*(1-wy),wx*(1-wy),(1-wx)*wy,wx*wy};
      const std::array<double,4> depths{metricDepth(tensor,config,x0,y0),
        metricDepth(tensor,config,x1,y0),metricDepth(tensor,config,x0,y1),
        metricDepth(tensor,config,x1,y1)};
      double weighted=0,valid_weight=0,min_depth=std::numeric_limits<double>::max();
      double max_depth=0;
      for(std::size_t i=0;i<4;++i)if(depths[i]>0&&weights[i]>0){
        weighted+=weights[i]*depths[i];valid_weight+=weights[i];
        min_depth=std::min(min_depth,depths[i]);max_depth=std::max(max_depth,depths[i]);}
      const auto index=static_cast<std::size_t>(y)*width+x;
      if(valid_weight>0){
        double selected=weighted/valid_weight;
        if(max_depth-min_depth>config.max_relative_discontinuity*min_depth)
          selected=min_depth;
        output.depth_meters[index]=static_cast<float>(selected);
      }
      output.confidence[index]=static_cast<float>(std::clamp(valid_weight,0.0,1.0));
    }
  }
  output.valid=true;return output;
}

}  // namespace arkernel
