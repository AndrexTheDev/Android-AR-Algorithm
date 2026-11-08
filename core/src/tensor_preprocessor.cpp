#include "arkernel/tensor_preprocessor.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
int channelCount(TensorImageFormat format){
  switch(format){case TensorImageFormat::kGray8:return 1;
    case TensorImageFormat::kRgb888:return 3;case TensorImageFormat::kRgba8888:return 4;}
  return 0;
}

std::array<double,3> pixelRgb(const TensorImageView& image,int x,int y){
  const int channels=channelCount(image.format);
  const auto* pixel=image.data+static_cast<std::size_t>(y)*image.row_stride_bytes+
                    static_cast<std::size_t>(x)*channels;
  if(channels==1){const double value=pixel[0]/255.0;return {value,value,value};}
  return {pixel[0]/255.0,pixel[1]/255.0,pixel[2]/255.0};
}

std::array<double,3> interpolate(const TensorImageView& image,double x,double y){
  x=std::clamp(x,0.0,static_cast<double>(image.width-1));
  y=std::clamp(y,0.0,static_cast<double>(image.height-1));
  const int x0=static_cast<int>(std::floor(x)),y0=static_cast<int>(std::floor(y));
  const int x1=std::min(x0+1,image.width-1),y1=std::min(y0+1,image.height-1);
  const double wx=x-x0,wy=y-y0;
  const auto a=pixelRgb(image,x0,y0),b=pixelRgb(image,x1,y0);
  const auto c=pixelRgb(image,x0,y1),d=pixelRgb(image,x1,y1);
  std::array<double,3> result{};
  for(std::size_t channel=0;channel<3;++channel){
    const double top=a[channel]*(1.0-wx)+b[channel]*wx;
    const double bottom=c[channel]*(1.0-wx)+d[channel]*wx;
    result[channel]=top*(1.0-wy)+bottom*wy;
  }
  return result;
}
}

std::size_t PreprocessedTensor::elementCount() const {
  switch(element_type){case TensorElementType::kFloat32:return float_values.size();
    case TensorElementType::kInt8:return int8_values.size();
    case TensorElementType::kUInt8:return uint8_values.size();}
  return 0;
}

bool validTensorImage(const TensorImageView& image){
  const int channels=channelCount(image.format);
  if(!image.data||image.width<=0||image.height<=0||channels==0||
     static_cast<std::size_t>(image.width)>
       std::numeric_limits<std::size_t>::max()/static_cast<std::size_t>(channels))return false;
  const auto row_bytes=static_cast<std::size_t>(image.width)*channels;
  if(image.row_stride_bytes<0||static_cast<std::size_t>(image.row_stride_bytes)<row_bytes)return false;
  const auto rows=static_cast<std::size_t>(image.height-1);
  const auto stride=static_cast<std::size_t>(image.row_stride_bytes);
  if(rows>std::numeric_limits<std::size_t>::max()/stride)return false;
  const auto offset=rows*stride;
  return row_bytes<=image.data_size&&offset<=image.data_size-row_bytes;
}

PreprocessedTensor preprocessTensorImage(const TensorImageView& image,
                                         TensorPreprocessConfig config){
  if(!validTensorImage(image)||config.output_width<=0||config.output_height<=0||
     (config.output_channels!=1&&config.output_channels!=3)||config.max_output_elements==0||
     (config.element_type!=TensorElementType::kFloat32&&
      config.element_type!=TensorElementType::kInt8&&
      config.element_type!=TensorElementType::kUInt8))
    throw std::invalid_argument("Invalid tensor image or output shape");
  for(int channel=0;channel<config.output_channels;++channel)
    if(!std::isfinite(config.mean[channel])||
       !std::isfinite(config.standard_deviation[channel])||
       config.standard_deviation[channel]<=0)
      throw std::invalid_argument("Invalid tensor normalization");
  if(config.element_type!=TensorElementType::kFloat32&&
     (!std::isfinite(config.quantization_scale)||config.quantization_scale<=0))
    throw std::invalid_argument("Invalid tensor quantization scale");
  if((config.element_type==TensorElementType::kInt8&&
      (config.quantization_zero_point<-128||config.quantization_zero_point>127))||
     (config.element_type==TensorElementType::kUInt8&&
      (config.quantization_zero_point<0||config.quantization_zero_point>255)))
    throw std::invalid_argument("Invalid tensor quantization zero point");
  const std::size_t width=static_cast<std::size_t>(config.output_width);
  const std::size_t height=static_cast<std::size_t>(config.output_height);
  const std::size_t channels=static_cast<std::size_t>(config.output_channels);
  if(width>std::numeric_limits<std::size_t>::max()/height||
     width*height>std::numeric_limits<std::size_t>::max()/channels||
     width*height*channels>config.max_output_elements)
    throw std::length_error("Tensor output exceeds element budget");
  const std::size_t count=width*height*channels;
  PreprocessedTensor output;output.width=config.output_width;output.height=config.output_height;
  output.channels=config.output_channels;output.element_type=config.element_type;
  if(config.element_type==TensorElementType::kFloat32)output.float_values.resize(count);
  else if(config.element_type==TensorElementType::kInt8)output.int8_values.resize(count);
  else output.uint8_values.resize(count);
  std::size_t output_index=0;
  for(int y=0;y<config.output_height;++y){
    const double source_y=(static_cast<double>(y)+0.5)*image.height/config.output_height-0.5;
    for(int x=0;x<config.output_width;++x){
      const double source_x=(static_cast<double>(x)+0.5)*image.width/config.output_width-0.5;
      auto rgb=interpolate(image,source_x,source_y);
      if(config.swap_red_blue)std::swap(rgb[0],rgb[2]);
      std::array<double,3> values=rgb;
      if(config.output_channels==1)values[0]=0.299*rgb[0]+0.587*rgb[1]+0.114*rgb[2];
      for(int channel=0;channel<config.output_channels;++channel){
        const double normalized=(values[channel]-config.mean[channel])/
                                config.standard_deviation[channel];
        if(!std::isfinite(normalized))
          throw std::invalid_argument("Tensor normalization overflow");
        if(config.element_type==TensorElementType::kFloat32){
          if(std::abs(normalized)>std::numeric_limits<float>::max())
            throw std::invalid_argument("FLOAT32 tensor value overflow");
          output.float_values[output_index]=static_cast<float>(normalized);
        }else{
          const double quantized=std::round(normalized/config.quantization_scale)+
                                 config.quantization_zero_point;
          if(config.element_type==TensorElementType::kInt8)
            output.int8_values[output_index]=static_cast<std::int8_t>(
              std::clamp(quantized,-128.0,127.0));
          else output.uint8_values[output_index]=static_cast<std::uint8_t>(
              std::clamp(quantized,0.0,255.0));
        }
        ++output_index;
      }
    }
  }
  output.valid=true;return output;
}

}  // namespace arkernel
