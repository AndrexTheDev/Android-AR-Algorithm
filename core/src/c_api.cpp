#include "arkernel/c_api.h"
#include <algorithm>
#include <cstring>
#include <cmath>
#include <exception>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include "arkernel/vio_estimator.h"

struct ark_vio_handle {
  explicit ark_vio_handle(const arkernel::VioConfig& config) : estimator(config) {}
  arkernel::VioEstimator estimator;
  std::mutex mutex;
  std::string error;
  int64_t last_submitted_imu_ns{0};
};

namespace {
arkernel::VioConfig convert(const ark_vio_config_v1& c) {
  arkernel::VioConfig result;
  result.initialization_samples=c.initialization_samples;
  result.initialization_duration_s=c.initialization_duration_s;
  result.gravity_magnitude_mps2=c.gravity_magnitude_mps2;
  result.gravity_tolerance_mps2=c.gravity_tolerance_mps2;
  result.max_stationary_gyro_std_rps=c.max_stationary_gyro_std_rps;
  result.max_stationary_accel_std_mps2=c.max_stationary_accel_std_mps2;
  result.max_imu_gap_s=c.max_imu_gap_s;
  result.visual_sync_tolerance_ns=c.visual_sync_tolerance_ns;
  result.visual_rotation_gain=c.visual_rotation_gain;
  result.visual_position_gain=c.visual_position_gain;
  result.min_scale_displacement_m=c.min_scale_displacement_m;
  result.min_scale_direction_cosine=c.min_scale_direction_cosine;
  result.body_from_camera_rotation={c.body_from_camera_w,c.body_from_camera_x,
                                    c.body_from_camera_y,c.body_from_camera_z};
  return result;
}
bool validConfigHeader(const ark_vio_config_v1* config) {
  return config && config->struct_size>=sizeof(ark_vio_config_v1) &&
         config->api_version==ARKERNEL_C_API_VERSION;
}
template <typename T> bool validStruct(const T* value) {
  return value && value->struct_size>=sizeof(T);
}
ark_result fail(ark_vio_handle* handle,ark_result result,const char* message) {
  if(handle)handle->error=message;
  return result;
}
}  // namespace

extern "C" {
uint32_t ark_c_api_version(void){return ARKERNEL_C_API_VERSION;}

ark_result ark_vio_default_config(ark_vio_config_v1* out){
  if(!out)return ARK_RESULT_INVALID_ARGUMENT;
  arkernel::VioConfig c;
  *out={};out->struct_size=sizeof(*out);out->api_version=ARKERNEL_C_API_VERSION;
  out->initialization_samples=c.initialization_samples;
  out->initialization_duration_s=c.initialization_duration_s;
  out->gravity_magnitude_mps2=c.gravity_magnitude_mps2;
  out->gravity_tolerance_mps2=c.gravity_tolerance_mps2;
  out->max_stationary_gyro_std_rps=c.max_stationary_gyro_std_rps;
  out->max_stationary_accel_std_mps2=c.max_stationary_accel_std_mps2;
  out->max_imu_gap_s=c.max_imu_gap_s;
  out->visual_sync_tolerance_ns=c.visual_sync_tolerance_ns;
  out->visual_rotation_gain=c.visual_rotation_gain;
  out->visual_position_gain=c.visual_position_gain;
  out->min_scale_displacement_m=c.min_scale_displacement_m;
  out->min_scale_direction_cosine=c.min_scale_direction_cosine;
  out->body_from_camera_w=1.0;return ARK_RESULT_OK;
}

ark_result ark_vio_create(const ark_vio_config_v1* config,ark_vio_handle** out){
  if(!out)return ARK_RESULT_INVALID_ARGUMENT;
  *out=nullptr;
  ark_vio_config_v1 defaults{};
  if(!config){ark_vio_default_config(&defaults);config=&defaults;}
  if(!validConfigHeader(config))return ARK_RESULT_INVALID_ARGUMENT;
  try{*out=new ark_vio_handle(convert(*config));return ARK_RESULT_OK;}
  catch(const std::bad_alloc&){return ARK_RESULT_INTERNAL_ERROR;}
  catch(const std::exception&){return ARK_RESULT_INVALID_ARGUMENT;}
  catch(...){return ARK_RESULT_INTERNAL_ERROR;}
}
void ark_vio_destroy(ark_vio_handle* handle){delete handle;}

ark_result ark_vio_reset(ark_vio_handle* handle){
  if(!handle)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  try{handle->estimator.reset();handle->last_submitted_imu_ns=0;handle->error.clear();return ARK_RESULT_OK;}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown reset error");}
}

ark_result ark_vio_submit_imu(ark_vio_handle* handle,const ark_imu_sample_v1* sample){
  if(!handle||!validStruct(sample))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  try{
    if(!std::isfinite(sample->acceleration_x_mps2)||
       !std::isfinite(sample->acceleration_y_mps2)||
       !std::isfinite(sample->acceleration_z_mps2)||
       !std::isfinite(sample->angular_velocity_x_rps)||
       !std::isfinite(sample->angular_velocity_y_rps)||
       !std::isfinite(sample->angular_velocity_z_rps))
      return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"IMU values must be finite");
    if(sample->timestamp_ns<=handle->last_submitted_imu_ns)
      return fail(handle,ARK_RESULT_OUT_OF_ORDER,"IMU timestamp is not increasing");
    const bool accepted=handle->estimator.submitImu({sample->timestamp_ns,
      {sample->acceleration_x_mps2,sample->acceleration_y_mps2,sample->acceleration_z_mps2},
      {sample->angular_velocity_x_rps,sample->angular_velocity_y_rps,sample->angular_velocity_z_rps}});
    handle->last_submitted_imu_ns=sample->timestamp_ns;
    handle->error.clear();
    return accepted?ARK_RESULT_OK:ARK_RESULT_NOT_READY;
  }catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown IMU error");}
}

ark_result ark_vio_fuse_visual(ark_vio_handle* handle,const ark_relative_pose_v1* pose){
  if(!handle||!validStruct(pose))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  try{
    const double rotation_norm=std::sqrt(pose->rotation_w*pose->rotation_w+
      pose->rotation_x*pose->rotation_x+pose->rotation_y*pose->rotation_y+
      pose->rotation_z*pose->rotation_z);
    const double translation_norm=std::sqrt(
      pose->translation_direction_x*pose->translation_direction_x+
      pose->translation_direction_y*pose->translation_direction_y+
      pose->translation_direction_z*pose->translation_direction_z);
    if(!std::isfinite(rotation_norm)||!std::isfinite(translation_norm)||
       rotation_norm<1e-9||translation_norm<1e-9)
      return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"Visual pose must be finite and non-zero");
    arkernel::RelativePoseEstimate value;value.valid=pose->valid!=0;
    value.rotation_current_from_previous={pose->rotation_w,pose->rotation_x,
      pose->rotation_y,pose->rotation_z};
    value.translation_direction_current={pose->translation_direction_x/translation_norm,
      pose->translation_direction_y/translation_norm,
      pose->translation_direction_z/translation_norm};
    const auto fused=handle->estimator.fuseVisual(pose->timestamp_ns,value);
    handle->error.clear();
    return fused.rotation_applied?ARK_RESULT_OK:ARK_RESULT_NOT_READY;
  }catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown visual fusion error");}
}

ark_result ark_vio_get_state(ark_vio_handle* handle,ark_vio_state_v1* out){
  if(!handle||!validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  const auto& s=handle->estimator.state();const uint32_t size=out->struct_size;*out={};out->struct_size=size;
  out->timestamp_ns=s.timestamp_ns;out->status=static_cast<uint32_t>(s.status);
  out->rotation_w=s.world_from_body.rotation.w;out->rotation_x=s.world_from_body.rotation.x;
  out->rotation_y=s.world_from_body.rotation.y;out->rotation_z=s.world_from_body.rotation.z;
  out->position_x_m=s.world_from_body.translation.x;out->position_y_m=s.world_from_body.translation.y;out->position_z_m=s.world_from_body.translation.z;
  out->velocity_x_mps=s.velocity_world_mps.x;out->velocity_y_mps=s.velocity_world_mps.y;out->velocity_z_mps=s.velocity_world_mps.z;
  out->accelerometer_bias_x_mps2=s.accelerometer_bias_mps2.x;out->accelerometer_bias_y_mps2=s.accelerometer_bias_mps2.y;out->accelerometer_bias_z_mps2=s.accelerometer_bias_mps2.z;
  out->gyroscope_bias_x_rps=s.gyroscope_bias_rps.x;out->gyroscope_bias_y_rps=s.gyroscope_bias_rps.y;out->gyroscope_bias_z_rps=s.gyroscope_bias_rps.z;
  out->visual_translation_scale_m=s.visual_translation_scale_m;
  out->visual_translation_scale_valid=s.visual_translation_scale_valid?1u:0u;
  handle->error.clear();return handle->estimator.initialized()?ARK_RESULT_OK:ARK_RESULT_NOT_READY;
}

size_t ark_vio_copy_last_error(ark_vio_handle* handle,char* buffer,size_t buffer_size){
  static const char invalid[]="Invalid VIO handle";
  if(!handle){
    const size_t required=sizeof(invalid);
    if(buffer&&buffer_size){const size_t count=std::min(buffer_size-1,required-1);
      std::memcpy(buffer,invalid,count);buffer[count]='\0';}
    return required;
  }
  std::lock_guard<std::mutex> lock(handle->mutex);
  const size_t required=handle->error.size()+1;
  if(buffer&&buffer_size){const size_t count=std::min(buffer_size-1,handle->error.size());
    std::memcpy(buffer,handle->error.data(),count);buffer[count]='\0';}
  return required;
}
}  // extern "C"
