#include "arkernel/image_descriptor.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <optional>
#include <stdexcept>
#include <unordered_set>

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

LandmarkDescriptorStore::LandmarkDescriptorStore(LandmarkDescriptorStoreConfig config)
    :config_(config){
  if(config_.max_entries==0||config_.min_observations_for_snapshot==0||
     config_.max_accumulated_observations<config_.min_observations_for_snapshot||
     config_.max_accumulated_observations>std::numeric_limits<std::uint16_t>::max())
    throw std::invalid_argument("Invalid landmark descriptor store configuration");
}

void LandmarkDescriptorStore::integrate(
    const std::vector<BinaryFeatureDescriptor>& descriptors,
    const std::vector<std::uint32_t>& confirmed_feature_ids){
  const std::unordered_set<std::uint32_t> confirmed(confirmed_feature_ids.begin(),
                                                    confirmed_feature_ids.end());
  for(auto iterator=entries_.begin();iterator!=entries_.end();)
    if(confirmed.count(iterator->first)==0)iterator=entries_.erase(iterator);else ++iterator;
  for(const auto& descriptor:descriptors){
    if(confirmed.count(descriptor.feature_id)==0)continue;
    auto iterator=entries_.find(descriptor.feature_id);
    if(iterator==entries_.end()){
      if(entries_.size()>=config_.max_entries){
        auto victim=entries_.begin();
        for(auto candidate=entries_.begin();candidate!=entries_.end();++candidate)
          if(candidate->second.observations<victim->second.observations||
             (candidate->second.observations==victim->second.observations&&
              candidate->first>victim->first))victim=candidate;
        if(victim->second.observations>1||
           (victim->second.observations==1&&descriptor.feature_id>victim->first))continue;
        entries_.erase(victim);
      }
      iterator=entries_.emplace(descriptor.feature_id,Entry{}).first;
    }
    auto& entry=iterator->second;
    if(entry.observations>=config_.max_accumulated_observations){
      entry.observations/=2;
      for(auto& votes:entry.one_votes)votes=static_cast<std::uint16_t>(votes/2);
    }
    for(std::size_t bit=0;bit<entry.one_votes.size();++bit)
      if(descriptor.bytes[bit/8]&(1u<<(bit%8)))++entry.one_votes[bit];
    ++entry.observations;
  }
}

std::vector<BinaryFeatureDescriptor> LandmarkDescriptorStore::snapshot() const {
  std::vector<BinaryFeatureDescriptor> output;output.reserve(entries_.size());
  for(const auto& item:entries_){
    if(item.second.observations<config_.min_observations_for_snapshot)continue;
    BinaryFeatureDescriptor descriptor;descriptor.feature_id=item.first;
    for(std::size_t bit=0;bit<item.second.one_votes.size();++bit)
      if(static_cast<std::size_t>(item.second.one_votes[bit])*2>=item.second.observations)
        descriptor.bytes[bit/8]|=static_cast<std::uint8_t>(1u<<(bit%8));
    output.push_back(descriptor);
  }
  std::sort(output.begin(),output.end(),[](const auto& a,const auto& b){
    return a.feature_id<b.feature_id;});
  return output;
}

bool LandmarkDescriptorStore::restore(
    const std::vector<BinaryFeatureDescriptor>& descriptors,
    const std::vector<std::uint32_t>& confirmed_feature_ids){
  if(descriptors.size()>config_.max_entries)return false;
  const std::unordered_set<std::uint32_t> confirmed(confirmed_feature_ids.begin(),
                                                    confirmed_feature_ids.end());
  LandmarkDescriptorStore replacement(config_);
  for(const auto& descriptor:descriptors){
    if(confirmed.count(descriptor.feature_id)==0||
       replacement.entries_.count(descriptor.feature_id)!=0)return false;
    Entry entry;entry.observations=config_.min_observations_for_snapshot;
    for(std::size_t bit=0;bit<entry.one_votes.size();++bit)
      if(descriptor.bytes[bit/8]&(1u<<(bit%8)))
        entry.one_votes[bit]=static_cast<std::uint16_t>(entry.observations);
    replacement.entries_.emplace(descriptor.feature_id,entry);
  }
  entries_=std::move(replacement.entries_);return true;
}

void LandmarkDescriptorStore::reset(){entries_.clear();}

}  // namespace arkernel
