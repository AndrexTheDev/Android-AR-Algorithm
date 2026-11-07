#include "arkernel/vio_estimator.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace arkernel {
namespace {
double dot(const Vec3& a,const Vec3& b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 cross(const Vec3& a,const Vec3& b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
Quaternion alignVectors(const Vec3& from,const Vec3& to){
  const double from_norm=from.norm(),to_norm=to.norm();
  if(from_norm<1e-9||to_norm<1e-9)return {};
  const Vec3 a=from*(1.0/from_norm),b=to*(1.0/to_norm);
  const double cosine=std::max(-1.0,std::min(1.0,dot(a,b)));
  if(cosine>0.999999)return {};
  if(cosine<-0.999999){
    Vec3 axis=std::abs(a.x)<0.8?cross(a,{1,0,0}):cross(a,{0,1,0});
    axis=axis*(1.0/axis.norm());return {0,axis.x,axis.y,axis.z};
  }
  const Vec3 axis=cross(a,b);
  return Quaternion{1.0+cosine,axis.x,axis.y,axis.z}.normalized();
}
}  // namespace

VioEstimator::VioEstimator(VioConfig config):config_(config){
  if(config_.initialization_samples<2||config_.initialization_duration_s<=0||
     config_.gravity_magnitude_mps2<=0||config_.gravity_tolerance_mps2<=0||
     config_.max_stationary_gyro_std_rps<=0||config_.max_stationary_accel_std_mps2<=0||
     config_.max_imu_gap_s<=0||config_.visual_sync_tolerance_ns<0||
     config_.visual_rotation_gain<0||config_.visual_rotation_gain>1||
     config_.visual_position_gain<0||config_.visual_position_gain>1||
     config_.min_scale_displacement_m<=0||config_.min_scale_direction_cosine<0||
     config_.min_scale_direction_cosine>1)
    throw std::invalid_argument("Invalid VIO configuration");
  const double extrinsic_norm=std::sqrt(
      config_.body_from_camera_rotation.w*config_.body_from_camera_rotation.w+
      config_.body_from_camera_rotation.x*config_.body_from_camera_rotation.x+
      config_.body_from_camera_rotation.y*config_.body_from_camera_rotation.y+
      config_.body_from_camera_rotation.z*config_.body_from_camera_rotation.z);
  if(extrinsic_norm<1e-9||!std::isfinite(config_.body_from_camera_translation_m.x)||
     !std::isfinite(config_.body_from_camera_translation_m.y)||
     !std::isfinite(config_.body_from_camera_translation_m.z))
    throw std::invalid_argument("Invalid camera-to-body transform");
  config_.body_from_camera_rotation=config_.body_from_camera_rotation.normalized();
  state_.gravity_world_mps2={0,0,-config_.gravity_magnitude_mps2};
}

bool VioEstimator::submitImu(const ImuSample& sample){
  if(sample.timestamp_ns<=0||!std::isfinite(sample.acceleration_mps2.x)||
     !std::isfinite(sample.acceleration_mps2.y)||!std::isfinite(sample.acceleration_mps2.z)||
     !std::isfinite(sample.angular_velocity_rps.x)||!std::isfinite(sample.angular_velocity_rps.y)||
     !std::isfinite(sample.angular_velocity_rps.z))return false;
  if(previous_imu_&&sample.timestamp_ns<=previous_imu_->timestamp_ns)return false;
  if(!initialized()){
    initialization_buffer_.push_back(sample);previous_imu_=sample;
    while(initialization_buffer_.size()>config_.initialization_samples*2)initialization_buffer_.pop_front();
    return tryInitialize();
  }
  if(!previous_imu_){previous_imu_=sample;state_.timestamp_ns=sample.timestamp_ns;return false;}
  const double dt=(sample.timestamp_ns-previous_imu_->timestamp_ns)*1e-9;
  if(dt>config_.max_imu_gap_s){
    previous_imu_=sample;state_.timestamp_ns=sample.timestamp_ns;state_.status=VioStatus::kLimited;
    previous_visual_world_pose_.reset();return false;
  }
  propagate(*previous_imu_,sample);previous_imu_=sample;return true;
}

bool VioEstimator::tryInitialize(){
  if(initialization_buffer_.size()<config_.initialization_samples)return false;
  const double duration=(initialization_buffer_.back().timestamp_ns-
                         initialization_buffer_.front().timestamp_ns)*1e-9;
  if(duration<config_.initialization_duration_s)return false;
  Vec3 mean_accel{},mean_gyro{};
  for(const auto& sample:initialization_buffer_){mean_accel+=sample.acceleration_mps2;mean_gyro+=sample.angular_velocity_rps;}
  const double count=static_cast<double>(initialization_buffer_.size());
  mean_accel=mean_accel*(1.0/count);mean_gyro=mean_gyro*(1.0/count);
  double accel_variance=0,gyro_variance=0;
  for(const auto& sample:initialization_buffer_){
    const Vec3 da=sample.acceleration_mps2-mean_accel,dg=sample.angular_velocity_rps-mean_gyro;
    accel_variance+=dot(da,da);gyro_variance+=dot(dg,dg);
  }
  const double accel_std=std::sqrt(accel_variance/count),gyro_std=std::sqrt(gyro_variance/count);
  if(accel_std>config_.max_stationary_accel_std_mps2||gyro_std>config_.max_stationary_gyro_std_rps||
     std::abs(mean_accel.norm()-config_.gravity_magnitude_mps2)>config_.gravity_tolerance_mps2)
    return false;
  const Vec3 world_up{0,0,config_.gravity_magnitude_mps2};
  state_.world_from_body.rotation=alignVectors(mean_accel,world_up);
  const Vec3 expected_body=state_.world_from_body.rotation.inverse().rotate(world_up);
  state_.accelerometer_bias_mps2=mean_accel-expected_body;
  state_.gyroscope_bias_rps=mean_gyro;
  state_.timestamp_ns=initialization_buffer_.back().timestamp_ns;
  state_.status=VioStatus::kInertial;
  previous_imu_=initialization_buffer_.back();initialization_buffer_.clear();return true;
}

void VioEstimator::propagate(const ImuSample& previous,const ImuSample& current){
  const double dt=(current.timestamp_ns-previous.timestamp_ns)*1e-9;
  const Vec3 gyro=(previous.angular_velocity_rps+current.angular_velocity_rps)*0.5-
                  state_.gyroscope_bias_rps;
  const Vec3 accel=(previous.acceleration_mps2+current.acceleration_mps2)*0.5-
                   state_.accelerometer_bias_mps2;
  const Quaternion middle=state_.world_from_body.rotation*
      Quaternion::fromRotationVector(gyro*(0.5*dt));
  const Vec3 acceleration_world=middle.rotate(accel)+state_.gravity_world_mps2;
  state_.world_from_body.translation+=state_.velocity_world_mps*dt+
                                      acceleration_world*(0.5*dt*dt);
  state_.velocity_world_mps+=acceleration_world*dt;
  state_.world_from_body.rotation=(state_.world_from_body.rotation*
      Quaternion::fromRotationVector(gyro*dt)).normalized();
  state_.timestamp_ns=current.timestamp_ns;
  if(state_.status==VioStatus::kLimited)state_.status=VioStatus::kInertial;
}

VisualFusionResult VioEstimator::fuseVisual(TimestampNs timestamp_ns,
                                             const RelativePoseEstimate& visual){
  VisualFusionResult result;
  const double rotation_norm=std::sqrt(
      visual.rotation_current_from_previous.w*visual.rotation_current_from_previous.w+
      visual.rotation_current_from_previous.x*visual.rotation_current_from_previous.x+
      visual.rotation_current_from_previous.y*visual.rotation_current_from_previous.y+
      visual.rotation_current_from_previous.z*visual.rotation_current_from_previous.z);
  const double translation_norm=visual.translation_direction_current.norm();
  if(!initialized()||!visual.valid||!std::isfinite(rotation_norm)||
     !std::isfinite(translation_norm)||rotation_norm<1e-9||translation_norm<1e-9||
     std::llabs(timestamp_ns-state_.timestamp_ns)>config_.visual_sync_tolerance_ns)return result;
  if(!previous_visual_world_pose_){previous_visual_world_pose_=state_.world_from_body;return result;}
  const Pose anchor=*previous_visual_world_pose_;
  const Quaternion camera_to_body=config_.body_from_camera_rotation;
  const Quaternion visual_body_rotation=(camera_to_body*
      visual.rotation_current_from_previous*camera_to_body.inverse()).normalized();
  const Vec3 visual_body_translation=camera_to_body.rotate(
      visual.translation_direction_current*(1.0/translation_norm));
  const Quaternion measured_world_rotation=(anchor.rotation*
      visual_body_rotation.inverse()).normalized();
  state_.world_from_body.rotation=Quaternion::slerp(
      state_.world_from_body.rotation,measured_world_rotation,config_.visual_rotation_gain);
  result.rotation_applied=true;

  const Vec3 center_direction_previous=visual_body_rotation.inverse().rotate(
      visual_body_translation*-1.0);
  const Vec3 direction_world=anchor.rotation.rotate(center_direction_previous);
  const Vec3 lever_body=config_.body_from_camera_translation_m;
  const Vec3 previous_camera_world=anchor.translation+anchor.rotation.rotate(lever_body);
  const Vec3 predicted_camera_world=state_.world_from_body.translation+
      state_.world_from_body.rotation.rotate(lever_body);
  const Vec3 predicted_displacement=predicted_camera_world-previous_camera_world;
  const double predicted_norm=predicted_displacement.norm();
  const double observed_scale=dot(predicted_displacement,direction_world);
  const double direction_cosine=predicted_norm>1e-12?observed_scale/predicted_norm:-1.0;
  if(observed_scale>=config_.min_scale_displacement_m&&
     direction_cosine>=config_.min_scale_direction_cosine){
    result.observed_scale_m=observed_scale;result.scale_updated=true;
    state_.visual_translation_scale_m=observed_scale;
    state_.visual_translation_scale_valid=true;
    // The essential translation is unit length; scale is therefore specific to
    // this frame interval, not a global quantity to average across frame rates.
    const Vec3 measured_camera_position=previous_camera_world+direction_world*observed_scale;
    const Vec3 measured_body_position=measured_camera_position-
      state_.world_from_body.rotation.rotate(lever_body);
    state_.world_from_body.translation=state_.world_from_body.translation*
        (1.0-config_.visual_position_gain)+measured_body_position*config_.visual_position_gain;
    result.position_applied=true;
  }
  state_.status=VioStatus::kVisualInertial;
  previous_visual_world_pose_=state_.world_from_body;return result;
}

void VioEstimator::reset(){
  state_={};state_.gravity_world_mps2={0,0,-config_.gravity_magnitude_mps2};
  initialization_buffer_.clear();previous_imu_.reset();previous_visual_world_pose_.reset();
}

}  // namespace arkernel
