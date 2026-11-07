#include "arkernel/image_descriptor.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <optional>
#include <stdexcept>

namespace arkernel {
namespace {
struct Pair { int ax,ay,bx,by; };
std::uint32_t randomNext(std::uint32_t& state){
  state^=state<<13;state^=state>>17;state^=state<<5;return state;
}
const std::array<Pair,kBinaryDescriptorBytes*8>& pattern(){
  static const auto values=[](){
    std::array<Pair,kBinaryDescriptorBytes*8> output{};std::uint32_t state=0x42524631u;
    for(auto& value:output){
      auto coordinate=[&](){return static_cast<int>(randomNext(state)%25u)-12;};
      do value={coordinate(),coordinate(),coordinate(),coordinate()};
        while(value.ax==value.bx&&value.ay==value.by);
    }
    return output;
  }();
  return values;
}
std::uint16_t popcount(std::uint8_t value){
  std::uint16_t count=0;while(value){value&=static_cast<std::uint8_t>(value-1);++count;}return count;
}
struct Nearest {std::size_t index{0};std::uint16_t distance{0};bool valid{false};};
Nearest nearestFor(const BinaryFeatureDescriptor& value,
                   const std::vector<BinaryFeatureDescriptor>& candidates,
                   std::optional<std::size_t>* second_index=nullptr){
  std::uint16_t best=std::numeric_limits<std::uint16_t>::max();
  std::uint16_t second=std::numeric_limits<std::uint16_t>::max();
  std::size_t best_index=0;std::size_t runner_up=0;
  for(std::size_t i=0;i<candidates.size();++i){
    const auto distance=descriptorHammingDistance(value,candidates[i]);
    if(distance<best||(distance==best&&candidates[i].feature_id<candidates[best_index].feature_id)){
      second=best;runner_up=best_index;best=distance;best_index=i;
    }else if(distance<second||(distance==second&&candidates[i].feature_id<candidates[runner_up].feature_id)){
      second=distance;runner_up=i;
    }
  }
  if(second_index)*second_index=candidates.size()>1?std::optional<std::size_t>(runner_up):std::nullopt;
  return {best_index,best,!candidates.empty()};
}
}  // namespace

std::vector<BinaryFeatureDescriptor> describeFeatures(const GrayImageView& image,
    const std::vector<ImageFeature>& features,std::size_t max_descriptors){
  std::vector<BinaryFeatureDescriptor> output;
  if(!image.valid()||max_descriptors==0)return output;
  output.reserve(std::min(features.size(),max_descriptors));
  for(const auto& feature:features){
    if(output.size()>=max_descriptors)break;
    constexpr int border=18;
    if(!std::isfinite(feature.x)||!std::isfinite(feature.y)||feature.x<border||
       feature.y<border||feature.x>=image.width-border||feature.y>=image.height-border)continue;
    const int center_x=static_cast<int>(std::lround(feature.x));
    const int center_y=static_cast<int>(std::lround(feature.y));
    double moment_x=0,moment_y=0;
    for(int y=-8;y<=8;++y)for(int x=-8;x<=8;++x){
      const double intensity=image.at(center_x+x,center_y+y);
      moment_x+=x*intensity;moment_y+=y*intensity;
    }
    const double angle=std::atan2(moment_y,moment_x);
    const double cosine=std::cos(angle),sine=std::sin(angle);
    BinaryFeatureDescriptor descriptor;descriptor.feature_id=feature.id;
    for(std::size_t bit=0;bit<pattern().size();++bit){const auto& pair=pattern()[bit];
      const int ax=center_x+static_cast<int>(std::lround(cosine*pair.ax-sine*pair.ay));
      const int ay=center_y+static_cast<int>(std::lround(sine*pair.ax+cosine*pair.ay));
      const int bx=center_x+static_cast<int>(std::lround(cosine*pair.bx-sine*pair.by));
      const int by=center_y+static_cast<int>(std::lround(sine*pair.bx+cosine*pair.by));
      if(image.at(ax,ay)<image.at(bx,by))
        descriptor.bytes[bit/8]|=static_cast<std::uint8_t>(1u<<(bit%8));
    }
    output.push_back(descriptor);
  }
  return output;
}

std::uint16_t descriptorHammingDistance(const BinaryFeatureDescriptor& a,
                                        const BinaryFeatureDescriptor& b){
  std::uint16_t distance=0;
  for(std::size_t i=0;i<a.bytes.size();++i)distance+=popcount(a.bytes[i]^b.bytes[i]);
  return distance;
}

std::vector<DescriptorMatch> matchDescriptors(
    const std::vector<BinaryFeatureDescriptor>& query,
    const std::vector<BinaryFeatureDescriptor>& train,DescriptorMatcherConfig config){
  if(config.max_hamming_distance>kBinaryDescriptorBytes*8||config.ratio_threshold<=0||
     config.ratio_threshold>=1||config.max_descriptors==0)
    throw std::invalid_argument("Invalid descriptor matcher configuration");
  std::vector<DescriptorMatch> output;
  if(query.empty()||train.empty()||query.size()>config.max_descriptors||
     train.size()>config.max_descriptors)return output;
  for(const auto& value:query){
    std::optional<std::size_t> second_index;
    const auto nearest=nearestFor(value,train,&second_index);
    if(!nearest.valid||nearest.distance>config.max_hamming_distance)continue;
    if(second_index){const auto second=descriptorHammingDistance(value,train[*second_index]);
      if(static_cast<double>(nearest.distance)>=config.ratio_threshold*second)continue;}
    if(config.mutual_best){
      const auto reverse=nearestFor(train[nearest.index],query);
      if(!reverse.valid||query[reverse.index].feature_id!=value.feature_id)continue;
    }
    output.push_back({value.feature_id,train[nearest.index].feature_id,nearest.distance});
  }
  std::sort(output.begin(),output.end(),[](const auto& a,const auto& b){
    if(a.query_feature_id!=b.query_feature_id)return a.query_feature_id<b.query_feature_id;
    return a.train_feature_id<b.train_feature_id;});
  return output;
}

}  // namespace arkernel
