#include "arkernel/spatial_snapshot.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <type_traits>
#include <unordered_set>

namespace arkernel {
namespace {
constexpr std::array<std::uint8_t,8> kMagic{'A','R','K','M','A','P','0','1'};
constexpr std::uint32_t kVersion=1;

template<typename T> void appendInteger(std::vector<std::uint8_t>& out,T value){
  using U=typename std::make_unsigned<T>::type;const U bits=static_cast<U>(value);
  for(std::size_t i=0;i<sizeof(T);++i)out.push_back(static_cast<std::uint8_t>(bits>>(8*i)));
}
void appendDouble(std::vector<std::uint8_t>& out,double value){
  static_assert(sizeof(double)==sizeof(std::uint64_t),"64-bit double required");
  std::uint64_t bits{};std::memcpy(&bits,&value,sizeof(bits));appendInteger(out,bits);
}
std::uint32_t crc32(const std::uint8_t* data,std::size_t size){
  std::uint32_t crc=0xFFFFFFFFu;
  for(std::size_t i=0;i<size;++i){crc^=data[i];for(int bit=0;bit<8;++bit)
    crc=(crc>>1)^((crc&1u)?0xEDB88320u:0u);}
  return ~crc;
}
bool finite(const Vec3& v){return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z);}

class Reader {
 public:
  Reader(const std::vector<std::uint8_t>& bytes,std::size_t payload_size)
      :bytes_(bytes),payload_size_(payload_size){}
  template<typename T> bool integer(T& value){
    if(position_+sizeof(T)>payload_size_)return false;
    using U=typename std::make_unsigned<T>::type;U bits=0;
    for(std::size_t i=0;i<sizeof(T);++i)bits|=static_cast<U>(bytes_[position_++])<<(8*i);
    value=static_cast<T>(bits);return true;
  }
  bool real(double& value){std::uint64_t bits{};if(!integer(bits))return false;
    std::memcpy(&value,&bits,sizeof(value));return std::isfinite(value);}
  std::size_t position()const{return position_;}
 private:
  const std::vector<std::uint8_t>& bytes_;std::size_t payload_size_;std::size_t position_{0};
};

bool validLimits(const SpatialSnapshotLimits& limits){
  return limits.max_bytes>=64&&limits.max_landmarks>0&&limits.max_anchors>0;
}
}  // namespace

bool restoreSpatialSnapshot(const SpatialSnapshot& snapshot,SparseLandmarkMap& map,
                            AnchorStore& anchors){
  const auto previous_landmarks=map.snapshot();
  if(!map.restore(snapshot.landmarks))return false;
  if(anchors.restore(snapshot.anchors))return true;
  map.restore(previous_landmarks);
  return false;
}

std::vector<std::uint8_t> SpatialSnapshotCodec::encode(
    const SpatialSnapshot& snapshot,SpatialSnapshotLimits limits){
  if(!validLimits(limits)||snapshot.created_ns<=0||snapshot.landmarks.size()>limits.max_landmarks||
     snapshot.anchors.size()>limits.max_anchors||
     snapshot.landmarks.size()>std::numeric_limits<std::uint32_t>::max()||
     snapshot.anchors.size()>std::numeric_limits<std::uint32_t>::max())
    throw std::invalid_argument("Invalid spatial snapshot");
  std::unordered_set<std::uint32_t> landmark_ids;
  std::unordered_set<AnchorId> anchor_ids;
  std::vector<std::uint8_t> out;out.reserve(32+snapshot.landmarks.size()*64+snapshot.anchors.size()*80);
  out.insert(out.end(),kMagic.begin(),kMagic.end());appendInteger(out,kVersion);
  appendInteger(out,snapshot.created_ns);
  appendInteger(out,static_cast<std::uint32_t>(snapshot.landmarks.size()));
  appendInteger(out,static_cast<std::uint32_t>(snapshot.anchors.size()));
  for(const auto& landmark:snapshot.landmarks){
    if(!finite(landmark.position_world_m)||landmark.observation_count<2||
       !std::isfinite(landmark.mean_ray_residual_m)||landmark.mean_ray_residual_m<0||
       landmark.last_seen_ns<=0||!landmark_ids.insert(landmark.feature_id).second)
      throw std::invalid_argument("Invalid persisted landmark");
    appendInteger(out,landmark.feature_id);appendDouble(out,landmark.position_world_m.x);
    appendDouble(out,landmark.position_world_m.y);appendDouble(out,landmark.position_world_m.z);
    appendInteger(out,static_cast<std::uint64_t>(landmark.observation_count));
    appendDouble(out,landmark.mean_ray_residual_m);appendInteger(out,landmark.last_seen_ns);
  }
  for(const auto& anchor:snapshot.anchors){
    const auto& pose=anchor.world_from_anchor;const double quaternion_norm=std::sqrt(
      pose.rotation.w*pose.rotation.w+pose.rotation.x*pose.rotation.x+
      pose.rotation.y*pose.rotation.y+pose.rotation.z*pose.rotation.z);
    if(anchor.id==0||anchor.created_ns<=0||!finite(pose.translation)||
       !std::isfinite(quaternion_norm)||quaternion_norm<1e-9||
       !anchor_ids.insert(anchor.id).second)
      throw std::invalid_argument("Invalid persisted anchor");
    const auto rotation=pose.rotation.normalized();appendInteger(out,anchor.id);
    appendDouble(out,pose.translation.x);appendDouble(out,pose.translation.y);appendDouble(out,pose.translation.z);
    appendDouble(out,rotation.w);appendDouble(out,rotation.x);appendDouble(out,rotation.y);appendDouble(out,rotation.z);
    appendInteger(out,anchor.created_ns);
  }
  if(out.size()+sizeof(std::uint32_t)>limits.max_bytes)throw std::length_error("Spatial snapshot too large");
  appendInteger(out,crc32(out.data(),out.size()));return out;
}

SpatialDecodeResult SpatialSnapshotCodec::decode(
    const std::vector<std::uint8_t>& bytes,SpatialSnapshotLimits limits){
  SpatialDecodeResult result;
  if(!validLimits(limits)){result.error="Invalid decode limits";return result;}
  if(bytes.size()<8+4+8+4+4+4||bytes.size()>limits.max_bytes){result.error="Invalid snapshot size";return result;}
  if(!std::equal(kMagic.begin(),kMagic.end(),bytes.begin())){result.error="Invalid snapshot magic";return result;}
  const std::size_t payload_size=bytes.size()-4;
  std::uint32_t stored_crc=0;
  for(std::size_t i=0;i<4;++i)stored_crc|=static_cast<std::uint32_t>(bytes[payload_size+i])<<(8*i);
  if(stored_crc!=crc32(bytes.data(),payload_size)){result.error="Snapshot CRC mismatch";return result;}
  Reader reader(bytes,payload_size);std::array<std::uint8_t,8> magic{};
  for(auto& value:magic)if(!reader.integer(value)){result.error="Truncated snapshot";return result;}
  std::uint32_t version=0,landmark_count=0,anchor_count=0;
  if(!reader.integer(version)||version!=kVersion||!reader.integer(result.snapshot.created_ns)||
     !reader.integer(landmark_count)||!reader.integer(anchor_count)){
    result.error="Unsupported or truncated snapshot header";return result;}
  if(result.snapshot.created_ns<=0||landmark_count>limits.max_landmarks||anchor_count>limits.max_anchors){
    result.error="Snapshot count limit exceeded";return result;}
  result.snapshot.landmarks.reserve(landmark_count);
  std::unordered_set<std::uint32_t> landmark_ids;
  for(std::uint32_t i=0;i<landmark_count;++i){SparseLandmark value;std::uint64_t observations=0;
    if(!reader.integer(value.feature_id)||!reader.real(value.position_world_m.x)||
       !reader.real(value.position_world_m.y)||!reader.real(value.position_world_m.z)||
       !reader.integer(observations)||!reader.real(value.mean_ray_residual_m)||
       !reader.integer(value.last_seen_ns)||observations<2||observations>std::numeric_limits<std::size_t>::max()||
       value.mean_ray_residual_m<0||value.last_seen_ns<=0||
       !landmark_ids.insert(value.feature_id).second){result.error="Invalid landmark record";return result;}
    value.observation_count=static_cast<std::size_t>(observations);result.snapshot.landmarks.push_back(value);
  }
  result.snapshot.anchors.reserve(anchor_count);
  std::unordered_set<AnchorId> anchor_ids;
  for(std::uint32_t i=0;i<anchor_count;++i){WorldAnchor value;
    if(!reader.integer(value.id)||!reader.real(value.world_from_anchor.translation.x)||
       !reader.real(value.world_from_anchor.translation.y)||!reader.real(value.world_from_anchor.translation.z)||
       !reader.real(value.world_from_anchor.rotation.w)||!reader.real(value.world_from_anchor.rotation.x)||
       !reader.real(value.world_from_anchor.rotation.y)||!reader.real(value.world_from_anchor.rotation.z)||
       !reader.integer(value.created_ns)){result.error="Invalid anchor record";return result;}
    const auto& q=value.world_from_anchor.rotation;const double norm=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
    if(value.id==0||value.created_ns<=0||!std::isfinite(norm)||norm<1e-9||
       !anchor_ids.insert(value.id).second){result.error="Invalid anchor value";return result;}
    value.world_from_anchor.rotation=value.world_from_anchor.rotation.normalized();
    result.snapshot.anchors.push_back(value);
  }
  if(reader.position()!=payload_size){result.error="Unexpected trailing snapshot payload";return result;}
  result.valid=true;return result;
}

}  // namespace arkernel
