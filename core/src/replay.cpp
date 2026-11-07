#include "arkernel/replay.h"
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <type_traits>

namespace arkernel {
namespace {
std::string nextPgmToken(std::istream& input) {
  std::string token;
  while (input >> token) {
    if (!token.empty() && token[0] == '#') {
      input.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
      continue;
    }
    return token;
  }
  throw std::runtime_error("Unexpected end of PGM header");
}

ReplayFrame loadPgm(const std::filesystem::path& path, int expected_width, int expected_height) {
  std::ifstream input(path,std::ios::binary);
  if (!input) throw std::runtime_error("Cannot open frame: "+path.string());
  if (nextPgmToken(input)!="P5") throw std::runtime_error("Only binary P5 PGM is supported");
  const int width=std::stoi(nextPgmToken(input));
  const int height=std::stoi(nextPgmToken(input));
  const int maximum=std::stoi(nextPgmToken(input));
  if (width!=expected_width||height!=expected_height||maximum!=255||width<=0||height<=0)
    throw std::runtime_error("PGM dimensions or range do not match calibration");
  input.get();
  ReplayFrame frame;frame.width=width;frame.height=height;frame.row_stride=width;
  frame.gray.resize(static_cast<std::size_t>(width)*height);
  input.read(reinterpret_cast<char*>(frame.gray.data()),static_cast<std::streamsize>(frame.gray.size()));
  if (input.gcount()!=static_cast<std::streamsize>(frame.gray.size()))
    throw std::runtime_error("Truncated PGM frame");
  return frame;
}

void hashByte(std::uint64_t& hash,std::uint8_t value){hash^=value;hash*=1099511628211ULL;}
template <typename T> void hashInteger(std::uint64_t& hash,T value){
  using Unsigned=typename std::make_unsigned<T>::type;
  Unsigned bits=static_cast<Unsigned>(value);
  for(std::size_t i=0;i<sizeof(T);++i)hashByte(hash,static_cast<std::uint8_t>(bits>>(i*8)));
}
void hashQuantized(std::uint64_t& hash,double value,double scale){
  hashInteger(hash,static_cast<std::int64_t>(std::llround(value*scale)));
}
}  // namespace

ReplayDataset ReplayDataset::loadManifest(const std::string& manifest_path) {
  std::ifstream input(manifest_path);
  if (!input) throw std::runtime_error("Cannot open replay manifest");
  std::string line;
  if (!std::getline(input,line)||line!="ARK_REPLAY_V1")
    throw std::runtime_error("Unsupported replay manifest version");
  ReplayDataset result;bool has_camera=false,has_extrinsic=false;
  TimestampNs last_event=0,last_imu=0,last_frame=0;
  ReplayEventType last_type=ReplayEventType::kImu;
  const auto base=std::filesystem::absolute(manifest_path).parent_path();
  std::size_t line_number=1;
  while(std::getline(input,line)){
    ++line_number;
    if(line.empty()||line[0]=='#')continue;
    std::istringstream row(line);std::string type;row>>type;
    if(type=="CAMERA"){
      if(has_camera||!result.events.empty())throw std::runtime_error("CAMERA must occur once before events");
      row>>result.intrinsics.width>>result.intrinsics.height>>result.intrinsics.fx>>
          result.intrinsics.fy>>result.intrinsics.cx>>result.intrinsics.cy>>
          result.distortion.k1>>result.distortion.k2>>result.distortion.p1>>
          result.distortion.p2>>result.distortion.k3;
      const std::int64_t pixels=static_cast<std::int64_t>(result.intrinsics.width)*
                                result.intrinsics.height;
      if(!row||!result.intrinsics.valid()||result.intrinsics.width>8192||
         result.intrinsics.height>8192||pixels>64000000||
         !std::isfinite(result.intrinsics.cx)||
         !std::isfinite(result.intrinsics.cy)||!std::isfinite(result.distortion.k1)||
         !std::isfinite(result.distortion.k2)||!std::isfinite(result.distortion.p1)||
         !std::isfinite(result.distortion.p2)||!std::isfinite(result.distortion.k3))
        throw std::runtime_error("Invalid CAMERA row");
      has_camera=true;continue;
    }
    if(type=="EXTRINSIC"){
      if(!has_camera||has_extrinsic||!result.events.empty())
        throw std::runtime_error("EXTRINSIC must occur once after CAMERA and before events");
      row>>result.body_from_camera_rotation.w>>result.body_from_camera_rotation.x>>
          result.body_from_camera_rotation.y>>result.body_from_camera_rotation.z;
      const auto& q=result.body_from_camera_rotation;
      const double norm=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
      if(!row||!std::isfinite(norm)||norm<1e-9)throw std::runtime_error("Invalid EXTRINSIC row");
      result.body_from_camera_rotation=result.body_from_camera_rotation.normalized();
      has_extrinsic=true;continue;
    }
    if(!has_camera)throw std::runtime_error("Event before CAMERA row");
    ReplayEvent event;
    if(type=="IMU"){
      event.type=ReplayEventType::kImu;
      row>>event.timestamp_ns>>event.imu.acceleration_mps2.x>>event.imu.acceleration_mps2.y>>
          event.imu.acceleration_mps2.z>>event.imu.angular_velocity_rps.x>>
          event.imu.angular_velocity_rps.y>>event.imu.angular_velocity_rps.z;
      event.imu.timestamp_ns=event.timestamp_ns;
      if(!row||event.timestamp_ns<=last_imu||
         !std::isfinite(event.imu.acceleration_mps2.x)||
         !std::isfinite(event.imu.acceleration_mps2.y)||
         !std::isfinite(event.imu.acceleration_mps2.z)||
         !std::isfinite(event.imu.angular_velocity_rps.x)||
         !std::isfinite(event.imu.angular_velocity_rps.y)||
         !std::isfinite(event.imu.angular_velocity_rps.z))
        throw std::runtime_error("Invalid IMU row at "+std::to_string(line_number));
      last_imu=event.timestamp_ns;
    }else if(type=="FRAME"){
      event.type=ReplayEventType::kFrame;std::string relative_path;
      row>>event.timestamp_ns>>relative_path;
      if(!row||event.timestamp_ns<=last_frame||relative_path.empty())
        throw std::runtime_error("Invalid FRAME row at "+std::to_string(line_number));
      const std::filesystem::path frame_path(relative_path);
      if(frame_path.is_absolute()||
         std::find(frame_path.begin(),frame_path.end(),std::filesystem::path(".."))!=frame_path.end())
        throw std::runtime_error("FRAME path must remain inside replay directory");
      event.frame=loadPgm(base/frame_path,result.intrinsics.width,result.intrinsics.height);
      last_frame=event.timestamp_ns;
    }else throw std::runtime_error("Unknown replay row at "+std::to_string(line_number));
    if(event.timestamp_ns<last_event||
       (event.timestamp_ns==last_event&&last_type==ReplayEventType::kFrame&&
        event.type==ReplayEventType::kImu))
      throw std::runtime_error("Replay events are not monotonic (equal-time IMU must precede FRAME)");
    last_event=event.timestamp_ns;last_type=event.type;
    if(result.events.size()>=2000000)throw std::runtime_error("Replay event limit exceeded");
    result.events.push_back(std::move(event));
  }
  if(!has_camera||result.events.empty())throw std::runtime_error("Replay is empty");
  return result;
}

ReplayRunner::ReplayRunner(PinholeRadTanCamera camera,VisualFrontendConfig visual_config,
                           VioConfig vio_config)
    :camera_(std::move(camera)),visual_config_(visual_config),vio_config_(vio_config),
     body_from_camera_(vio_config.body_from_camera_rotation.normalized()){}

Quaternion ReplayRunner::cameraRotationPrediction(const Quaternion& current_world_from_body) const {
  const Quaternion body_current_from_previous=current_world_from_body.inverse()*previous_world_from_body_;
  return (body_from_camera_.inverse()*body_current_from_previous*body_from_camera_).normalized();
}

ReplayMetrics ReplayRunner::run(const ReplayDataset& dataset) {
  if(dataset.intrinsics.width!=camera_.intrinsics().width||
     dataset.intrinsics.height!=camera_.intrinsics().height)
    throw std::invalid_argument("Replay camera dimensions do not match runner");
  VisualFrontend frontend(camera_,visual_config_);VioEstimator vio(vio_config_);
  previous_world_from_body_={};has_previous_camera_pose_=false;
  ReplayMetrics metrics;
  for(const auto& event:dataset.events){
    hashInteger(metrics.deterministic_hash,event.timestamp_ns);
    if(event.type==ReplayEventType::kImu){
      vio.submitImu(event.imu);++metrics.imu_samples;continue;
    }
    std::optional<Quaternion> prediction;
    if(vio.initialized()){
      const auto current=vio.state().world_from_body.rotation;
      if(has_previous_camera_pose_)prediction=cameraRotationPrediction(current);
    }
    const GrayImageView view{event.frame.width,event.frame.height,event.frame.row_stride,
                             event.frame.gray.data(),event.frame.gray.size()};
    const auto visual=frontend.process(view,event.timestamp_ns,prediction);
    ++metrics.frames;metrics.peak_active_features=std::max(metrics.peak_active_features,visual.active_feature_count);
    metrics.tracking_frames+=visual.state==FrontendState::kTracking;
    metrics.limited_frames+=visual.state==FrontendState::kLimited;
    metrics.reinitializations+=visual.state==FrontendState::kReinitialized;
    metrics.general_3d_frames+=visual.geometry.geometry==SceneGeometry::kGeneral3d;
    metrics.planar_frames+=visual.geometry.geometry==SceneGeometry::kPlanarOrRotationDominant;
    if(visual.relative_pose.valid){const auto fused=vio.fuseVisual(event.timestamp_ns,visual.relative_pose);
      metrics.visual_fusions+=fused.rotation_applied||fused.position_applied;}
    if(vio.initialized()){
      previous_world_from_body_=vio.state().world_from_body.rotation;has_previous_camera_pose_=true;
      const auto& state=vio.state();
      if(metrics.trajectory.empty()||state.timestamp_ns>metrics.trajectory.back().timestamp_ns)
        metrics.trajectory.push_back({state.timestamp_ns,state.world_from_body});
    }
    hashInteger(metrics.deterministic_hash,static_cast<std::uint32_t>(visual.state));
    hashInteger(metrics.deterministic_hash,static_cast<std::uint32_t>(visual.geometry.geometry));
    hashInteger(metrics.deterministic_hash,visual.accepted_track_count);
    hashInteger(metrics.deterministic_hash,visual.active_feature_count);
  }
  metrics.final_vio_status=vio.state().status;metrics.final_world_from_body=vio.state().world_from_body;
  hashInteger(metrics.deterministic_hash,static_cast<std::uint32_t>(metrics.final_vio_status));
  hashQuantized(metrics.deterministic_hash,metrics.final_world_from_body.translation.x,1e6);
  hashQuantized(metrics.deterministic_hash,metrics.final_world_from_body.translation.y,1e6);
  hashQuantized(metrics.deterministic_hash,metrics.final_world_from_body.translation.z,1e6);
  return metrics;
}

}  // namespace arkernel
