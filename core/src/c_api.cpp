#include "arkernel/c_api.h"
#include <algorithm>
#include <cstring>
#include <cmath>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <stdexcept>
#include <string>
#include "arkernel/relocalization.h"
#include "arkernel/semantic_map.h"
#include "arkernel/sparse_map.h"
#include "arkernel/spatial_snapshot.h"
#include "arkernel/tracking_quality.h"
#include "arkernel/vio_estimator.h"
#include "arkernel/visual_frontend.h"

struct ark_vio_handle {
  explicit ark_vio_handle(const arkernel::VioConfig& config) : estimator(config) {}
  arkernel::VioEstimator estimator;
  std::mutex mutex;
  std::string error;
  int64_t last_submitted_imu_ns{0};
};

struct ark_visual_handle {
  ark_visual_handle(const arkernel::PinholeRadTanCamera& calibrated_camera,
                    const arkernel::VisualFrontendConfig& config)
      : camera(calibrated_camera),frontend(calibrated_camera,config) {}
  arkernel::PinholeRadTanCamera camera;
  arkernel::VisualFrontend frontend;
  arkernel::SparseLandmarkMap map;
  arkernel::SparsePlaneTracker plane_tracker;
  arkernel::PlaneEstimate plane;
  arkernel::RoomLayoutEstimate room_layout;
  arkernel::MappingQualityGate mapping_gate;
  arkernel::SemanticObjectMap semantic_map;
  arkernel::LandmarkDescriptorStore descriptor_store;
  arkernel::AnchorStore anchors;
  std::optional<arkernel::VisualFrontendResult> last_result;
  std::mutex mutex;
  std::string error;
  int64_t last_frame_ns{0};
  int64_t last_map_frame_ns{0};
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
bool validConfigHeader(const ark_visual_config_v1* config) {
  return config && config->struct_size>=sizeof(ark_visual_config_v1) &&
         config->api_version==ARKERNEL_C_API_VERSION;
}
arkernel::VisualFrontendConfig convert(const ark_visual_config_v1& c) {
  arkernel::VisualFrontendConfig result;
  result.max_active_features=c.max_active_features;
  result.min_tracked_features=c.min_tracked_features;
  result.max_frame_gap_ns=c.max_frame_gap_ns;
  return result;
}
template <typename T> bool validStruct(const T* value) {
  return value && value->struct_size>=sizeof(T);
}
bool convertPose(const ark_world_pose_v1& value,arkernel::Pose& pose) {
  const double norm=std::sqrt(value.rotation_w*value.rotation_w+
    value.rotation_x*value.rotation_x+value.rotation_y*value.rotation_y+
    value.rotation_z*value.rotation_z);
  if(value.timestamp_ns<=0||!std::isfinite(norm)||norm<1e-9||
     !std::isfinite(value.position_x_m)||!std::isfinite(value.position_y_m)||
     !std::isfinite(value.position_z_m))return false;
  pose.rotation={value.rotation_w/norm,value.rotation_x/norm,
    value.rotation_y/norm,value.rotation_z/norm};
  pose.translation={value.position_x_m,value.position_y_m,value.position_z_m};
  return true;
}
ark_result fail(ark_vio_handle* handle,ark_result result,const char* message) {
  if(handle)handle->error=message;
  return result;
}
ark_result fail(ark_visual_handle* handle,ark_result result,const char* message) {
  if(handle)handle->error=message;
  return result;
}
template <typename Handle>
size_t copyError(Handle* handle,char* buffer,size_t buffer_size) {
  static const char invalid[]="Invalid native handle";
  const std::string value=handle?handle->error:invalid;
  const size_t required=value.size()+1;
  if(buffer&&buffer_size){const size_t count=std::min(buffer_size-1,value.size());
    std::memcpy(buffer,value.data(),count);buffer[count]='\0';}
  return required;
}
bool installSnapshot(ark_visual_handle* handle,arkernel::SpatialSnapshot snapshot,
                     int64_t restore_now_ns,std::string& error){
  std::uint32_t maximum_feature_id=0;
  std::vector<std::uint32_t> landmark_ids;landmark_ids.reserve(snapshot.landmarks.size());
  for(auto& landmark:snapshot.landmarks){landmark.last_seen_ns=restore_now_ns;
    maximum_feature_id=std::max(maximum_feature_id,landmark.feature_id);
    landmark_ids.push_back(landmark.feature_id);}
  if(maximum_feature_id==std::numeric_limits<std::uint32_t>::max()){
    error="Snapshot feature ID range is exhausted";return false;}
  arkernel::LandmarkDescriptorStore replacement_descriptors;
  if(!replacement_descriptors.restore(snapshot.landmark_descriptors,landmark_ids)){
    error="Snapshot descriptors exceed runtime limits";return false;}
  if(!arkernel::restoreSpatialSnapshot(snapshot,handle->map,handle->anchors)){
    error="Snapshot exceeds runtime map limits";return false;}
  handle->descriptor_store=std::move(replacement_descriptors);
  handle->frontend.reset();handle->frontend.reserveFeatureIdsAbove(maximum_feature_id);
  handle->last_result.reset();handle->plane_tracker.reset();
  const auto& planes=handle->plane_tracker.update(handle->map.snapshot());
  handle->plane=planes.empty()?arkernel::PlaneEstimate{}:planes.front().estimate;
  handle->room_layout=arkernel::estimateRoomLayout(planes);return true;
}
}  // namespace

extern "C" {
uint32_t ark_c_api_version(void){return ARKERNEL_C_API_VERSION;}

ark_result ark_visual_default_config(int32_t width,int32_t height,double fx,double fy,
    double cx,double cy,ark_visual_config_v1* out){
  if(!out||width<=0||height<=0||fx<=0||fy<=0)return ARK_RESULT_INVALID_ARGUMENT;
  arkernel::VisualFrontendConfig defaults;
  *out={};out->struct_size=sizeof(*out);out->api_version=ARKERNEL_C_API_VERSION;
  out->width=width;out->height=height;out->fx=fx;out->fy=fy;out->cx=cx;out->cy=cy;
  out->max_active_features=defaults.max_active_features;
  out->min_tracked_features=defaults.min_tracked_features;
  out->max_frame_gap_ns=defaults.max_frame_gap_ns;return ARK_RESULT_OK;
}

ark_result ark_visual_create(const ark_visual_config_v1* config,ark_visual_handle** out){
  if(!out)return ARK_RESULT_INVALID_ARGUMENT;
  *out=nullptr;
  if(!validConfigHeader(config)||!std::isfinite(config->fx)||!std::isfinite(config->fy)||
     !std::isfinite(config->cx)||!std::isfinite(config->cy)||
     !std::isfinite(config->distortion_k1)||!std::isfinite(config->distortion_k2)||
     !std::isfinite(config->distortion_p1)||!std::isfinite(config->distortion_p2)||
     !std::isfinite(config->distortion_k3))return ARK_RESULT_INVALID_ARGUMENT;
  try{
    arkernel::CameraIntrinsics intrinsics{config->width,config->height,config->fx,
                                          config->fy,config->cx,config->cy};
    arkernel::RadTanDistortion distortion{config->distortion_k1,config->distortion_k2,
      config->distortion_p1,config->distortion_p2,config->distortion_k3};
    *out=new ark_visual_handle(arkernel::PinholeRadTanCamera(intrinsics,distortion),convert(*config));
    return ARK_RESULT_OK;
  }catch(const std::bad_alloc&){return ARK_RESULT_INTERNAL_ERROR;}
  catch(const std::invalid_argument&){return ARK_RESULT_INVALID_ARGUMENT;}
  catch(...){return ARK_RESULT_INTERNAL_ERROR;}
}
void ark_visual_destroy(ark_visual_handle* handle){delete handle;}

ark_result ark_visual_reset(ark_visual_handle* handle){
  if(!handle)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  handle->frontend.reset();handle->map.reset();handle->plane_tracker.reset();
  handle->mapping_gate.reset();handle->semantic_map.reset();handle->descriptor_store.reset();
  handle->anchors.reset();
  handle->plane={};handle->room_layout={};handle->last_result.reset();handle->last_frame_ns=0;
  handle->last_map_frame_ns=0;handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_process_gray(ark_visual_handle* handle,const ark_gray_frame_v1* frame,
                                   ark_visual_result_v1* out){
  if(!handle||!validStruct(frame)||!validStruct(out)||!frame->data)
    return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  if(frame->timestamp_ns<=handle->last_frame_ns)
    return fail(handle,ARK_RESULT_OUT_OF_ORDER,"Camera timestamp is not increasing");
  try{
    std::optional<arkernel::Quaternion> prediction;
    if(frame->has_rotation_prediction){
      const double norm=std::sqrt(frame->rotation_current_from_previous_w*
          frame->rotation_current_from_previous_w+frame->rotation_current_from_previous_x*
          frame->rotation_current_from_previous_x+frame->rotation_current_from_previous_y*
          frame->rotation_current_from_previous_y+frame->rotation_current_from_previous_z*
          frame->rotation_current_from_previous_z);
      if(!std::isfinite(norm)||norm<1e-9)
        return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"Rotation prediction is invalid");
      prediction=arkernel::Quaternion{frame->rotation_current_from_previous_w/norm,
        frame->rotation_current_from_previous_x/norm,
        frame->rotation_current_from_previous_y/norm,
        frame->rotation_current_from_previous_z/norm};
    }
    const arkernel::GrayImageView view{frame->width,frame->height,frame->row_stride,
                                       frame->data,frame->data_size};
    const auto result=handle->frontend.process(view,frame->timestamp_ns,prediction);
    const uint32_t size=out->struct_size;*out={};out->struct_size=size;
    out->timestamp_ns=result.timestamp_ns;out->state=static_cast<uint32_t>(result.state);
    out->geometry=static_cast<uint32_t>(result.geometry.geometry);
    out->accepted_track_count=result.accepted_track_count;
    out->active_feature_count=result.active_feature_count;out->keyframe=result.keyframe?1u:0u;
    out->relative_pose_valid=result.relative_pose.valid?1u:0u;
    out->relative_rotation_w=result.relative_pose.rotation_current_from_previous.w;
    out->relative_rotation_x=result.relative_pose.rotation_current_from_previous.x;
    out->relative_rotation_y=result.relative_pose.rotation_current_from_previous.y;
    out->relative_rotation_z=result.relative_pose.rotation_current_from_previous.z;
    out->relative_translation_direction_x=result.relative_pose.translation_direction_current.x;
    out->relative_translation_direction_y=result.relative_pose.translation_direction_current.y;
    out->relative_translation_direction_z=result.relative_pose.translation_direction_current.z;
    out->positive_depth_count=result.relative_pose.positive_depth_count;
    out->median_parallax_degrees=result.relative_pose.median_parallax_degrees;
    handle->last_result=result;handle->last_frame_ns=frame->timestamp_ns;
    handle->error.clear();return ARK_RESULT_OK;
  }catch(const std::invalid_argument& e){return fail(handle,ARK_RESULT_INVALID_ARGUMENT,e.what());}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown visual frontend error");}
}

size_t ark_visual_copy_last_error(ark_visual_handle* handle,char* buffer,size_t buffer_size){
  if(!handle)return copyError(handle,buffer,buffer_size);
  std::lock_guard<std::mutex> lock(handle->mutex);return copyError(handle,buffer,buffer_size);
}

ark_result ark_visual_update_mapping_gate(ark_visual_handle* handle,uint32_t pose_initialized,
    uint32_t pose_limited,uint32_t visual_tracking,size_t accepted_tracks,
    uint32_t imu_queue_overflow,ark_mapping_gate_v1* out){
  if(!handle||!validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  const auto decision=handle->mapping_gate.update(pose_initialized!=0,pose_limited!=0,
    visual_tracking!=0,accepted_tracks,imu_queue_overflow!=0);
  const uint32_t size=out->struct_size;*out={};out->struct_size=size;
  out->state=static_cast<uint32_t>(decision.state);
  out->reason=static_cast<uint32_t>(decision.reason);out->integrate=decision.integrate?1u:0u;
  handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_integrate_map(ark_visual_handle* handle,const ark_world_pose_v1* pose,
                                    ark_map_update_v1* out){
  if(!handle||!validStruct(pose)||!validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);arkernel::Pose world_from_camera;
  if(!convertPose(*pose,world_from_camera))
    return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"World-from-camera pose is invalid");
  if(!handle->last_result||pose->timestamp_ns!=handle->last_result->timestamp_ns)
    return fail(handle,ARK_RESULT_NOT_READY,"Pose timestamp does not match the latest camera frame");
  if(pose->timestamp_ns<=handle->last_map_frame_ns)
    return fail(handle,ARK_RESULT_OUT_OF_ORDER,"Camera frame was already integrated into the map");
  try{
    const auto observations=arkernel::makeLandmarkObservations(
      *handle->last_result,handle->camera,world_from_camera);
    const auto update=handle->map.integrate(observations,pose->timestamp_ns);
    const auto landmarks=handle->map.snapshot();
    std::vector<std::uint32_t> landmark_ids;landmark_ids.reserve(landmarks.size());
    for(const auto& landmark:landmarks)landmark_ids.push_back(landmark.feature_id);
    handle->descriptor_store.integrate(handle->frontend.activeDescriptors(),landmark_ids);
    const auto& planes=handle->plane_tracker.update(
      landmarks,{0,0,1},world_from_camera.translation);
    handle->plane=planes.empty()?arkernel::PlaneEstimate{}:planes.front().estimate;
    handle->room_layout=arkernel::estimateRoomLayout(planes);
    const uint32_t size=out->struct_size;*out={};out->struct_size=size;
    out->timestamp_ns=pose->timestamp_ns;out->created=update.created;out->updated=update.updated;
    out->rejected=update.rejected;out->pruned=update.pruned;out->landmark_count=handle->map.size();
    out->plane_valid=handle->plane.valid?1u:0u;out->plane_inlier_count=handle->plane.inlier_count;
    out->plane_normal_x=handle->plane.normal_world.x;out->plane_normal_y=handle->plane.normal_world.y;
    out->plane_normal_z=handle->plane.normal_world.z;
    out->plane_distance_from_origin_m=handle->plane.distance_from_origin_m;
    out->plane_center_x_m=handle->plane.center_world.x;out->plane_center_y_m=handle->plane.center_world.y;
    out->plane_center_z_m=handle->plane.center_world.z;
    out->plane_observed_area_m2=handle->plane.observed_area_m2;
    out->plane_mean_absolute_error_m=handle->plane.mean_absolute_error_m;
    handle->last_map_frame_ns=pose->timestamp_ns;handle->error.clear();return ARK_RESULT_OK;
  }catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown map integration error");}
}

ark_result ark_visual_get_plane_count(ark_visual_handle* handle,size_t* out_count){
  if(!handle||!out_count)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  *out_count=handle->plane_tracker.planes().size();handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_get_plane(ark_visual_handle* handle,size_t index,ark_plane_v1* out){
  if(!handle||!validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);const auto& planes=handle->plane_tracker.planes();
  if(index>=planes.size())return fail(handle,ARK_RESULT_NOT_READY,"Plane index does not exist");
  const auto& tracked=planes[index];const auto& plane=tracked.estimate;
  const uint32_t size=out->struct_size;*out={};out->struct_size=size;out->id=tracked.id;
  out->normal_x=plane.normal_world.x;out->normal_y=plane.normal_world.y;out->normal_z=plane.normal_world.z;
  out->distance_from_origin_m=plane.distance_from_origin_m;out->center_x_m=plane.center_world.x;
  out->center_y_m=plane.center_world.y;out->center_z_m=plane.center_world.z;
  out->observed_area_m2=plane.observed_area_m2;out->mean_absolute_error_m=plane.mean_absolute_error_m;
  out->inlier_count=plane.inlier_count;handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_get_plane_semantic(ark_visual_handle* handle,size_t index,uint32_t* out){
  if(!handle||!out)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);const auto& planes=handle->plane_tracker.planes();
  if(index>=planes.size())return fail(handle,ARK_RESULT_NOT_READY,"Plane index does not exist");
  *out=static_cast<uint32_t>(planes[index].semantic);handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_get_room_layout(ark_visual_handle* handle,ark_room_layout_v1* out){
  if(!handle||!validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);const auto& layout=handle->room_layout;
  const uint32_t size=out->struct_size;*out={};out->struct_size=size;
  out->has_floor=layout.has_floor?1u:0u;out->has_ceiling=layout.has_ceiling?1u:0u;
  out->height_valid=layout.height_valid?1u:0u;out->floor_plane_id=layout.floor_plane_id;
  out->ceiling_plane_id=layout.ceiling_plane_id;out->floor_elevation_m=layout.floor_elevation_m;
  out->ceiling_elevation_m=layout.ceiling_elevation_m;out->room_height_m=layout.room_height_m;
  out->observed_floor_area_m2=layout.observed_floor_area_m2;
  out->observed_ceiling_area_m2=layout.observed_ceiling_area_m2;
  out->observed_wall_area_m2=layout.observed_wall_area_m2;out->wall_count=layout.wall_count;
  handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_project_semantic(ark_visual_handle* handle,
    const ark_semantic_projection_v1* projection,const ark_world_pose_v1* pose,
    int64_t now_ns,ark_semantic_update_v1* out){
  if(!handle||!validStruct(projection)||!validStruct(pose)||!validStruct(out)||now_ns<=0||
     projection->surface>ARK_SEMANTIC_SURFACE_WALL)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);arkernel::Pose world_from_camera;
  if(!convertPose(*pose,world_from_camera))
    return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"World-from-camera pose is invalid");
  try{
    const arkernel::SemanticProjectionRequest request{projection->class_id,
      projection->source_track_id,{projection->pixel_x,projection->pixel_y},
      projection->confidence,projection->timestamp_ns,projection->dynamic!=0,
      static_cast<arkernel::SemanticProjectionSurface>(projection->surface),
      projection->min_distance_m,projection->max_distance_m};
    const auto observation=arkernel::projectSemanticObservation(
      request,handle->camera,world_from_camera,handle->plane_tracker.planes());
    if(!observation)return fail(handle,ARK_RESULT_NOT_READY,
      "Semantic ray does not hit the requested observed surface");
    const auto update=handle->semantic_map.integrate({*observation},now_ns);
    const uint32_t size=out->struct_size;*out={};out->struct_size=size;
    out->created=update.created;out->updated=update.updated;out->rejected=update.rejected;
    out->pruned=update.pruned;out->object_count=handle->semantic_map.size();
    handle->error.clear();return ARK_RESULT_OK;
  }catch(const std::invalid_argument& e){return fail(handle,ARK_RESULT_INVALID_ARGUMENT,e.what());}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown semantic projection error");}
}

ark_result ark_visual_integrate_semantics(ark_visual_handle* handle,
    const ark_semantic_observation_v1* observations,size_t count,int64_t now_ns,
    ark_semantic_update_v1* out){
  if(!handle||!validStruct(out)||now_ns<=0||count>4096||(!observations&&count>0))
    return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  try{
    std::vector<arkernel::SemanticObjectObservation> converted;converted.reserve(count);
    for(size_t i=0;i<count;++i){
      if(!validStruct(&observations[i]))
        return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"Semantic observation struct is invalid");
      const auto& value=observations[i];
      converted.push_back({value.class_id,value.source_track_id,
        {value.position_x_m,value.position_y_m,value.position_z_m},value.confidence,
        value.timestamp_ns,value.dynamic!=0});
    }
    const auto update=handle->semantic_map.integrate(converted,now_ns);
    const uint32_t size=out->struct_size;*out={};out->struct_size=size;
    out->created=update.created;out->updated=update.updated;out->rejected=update.rejected;
    out->pruned=update.pruned;out->object_count=handle->semantic_map.size();
    handle->error.clear();return ARK_RESULT_OK;
  }catch(const std::invalid_argument& e){return fail(handle,ARK_RESULT_INVALID_ARGUMENT,e.what());}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown semantic map error");}
}

ark_result ark_visual_get_semantic_object_count(ark_visual_handle* handle,size_t* out_count){
  if(!handle||!out_count)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  *out_count=handle->semantic_map.size();handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_get_semantic_object(ark_visual_handle* handle,size_t index,
                                          ark_semantic_object_v1* out){
  if(!handle||!validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);const auto objects=handle->semantic_map.snapshot();
  if(index>=objects.size())return fail(handle,ARK_RESULT_NOT_READY,"Semantic object index does not exist");
  const auto& object=objects[index];const uint32_t size=out->struct_size;*out={};out->struct_size=size;
  out->id=object.id;out->class_id=object.class_id;out->source_track_id=object.source_track_id;
  out->first_seen_ns=object.first_seen_ns;out->last_seen_ns=object.last_seen_ns;
  out->position_x_m=object.position_world_m.x;out->position_y_m=object.position_world_m.y;
  out->position_z_m=object.position_world_m.z;out->confidence=object.confidence;
  out->observation_count=object.observation_count;out->dynamic=object.dynamic?1u:0u;
  handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_raycast_plane(ark_visual_handle* handle,double ox,double oy,double oz,
    double dx,double dy,double dz,double min_distance,double max_distance,ark_plane_hit_v1* out){
  if(!handle||!validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  if(!std::isfinite(ox)||!std::isfinite(oy)||!std::isfinite(oz)||!std::isfinite(dx)||
     !std::isfinite(dy)||!std::isfinite(dz)||!std::isfinite(min_distance)||
     !std::isfinite(max_distance)||min_distance<0||max_distance<=min_distance)
    return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"Raycast arguments are invalid");
  std::optional<arkernel::PlaneHit> hit;
  for(const auto& plane:handle->plane_tracker.planes()){
    const auto candidate=plane.estimate.raycast({ox,oy,oz},{dx,dy,dz},min_distance,max_distance);
    if(candidate&&(!hit||candidate->distance_m<hit->distance_m))hit=candidate;
  }
  if(!hit)return fail(handle,ARK_RESULT_NOT_READY,"Ray does not hit observed plane geometry");
  const uint32_t size=out->struct_size;*out={};out->struct_size=size;
  out->position_x_m=hit->position_world.x;out->position_y_m=hit->position_world.y;
  out->position_z_m=hit->position_world.z;out->normal_x=hit->normal_world.x;
  out->normal_y=hit->normal_world.y;out->normal_z=hit->normal_world.z;
  out->distance_m=hit->distance_m;out->plane_u_m=hit->plane_uv.x;out->plane_v_m=hit->plane_uv.y;
  handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_create_anchor(ark_visual_handle* handle,const ark_world_pose_v1* pose,
                                    uint64_t* out_id){
  if(!handle||!validStruct(pose)||!out_id)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);arkernel::Pose world_from_anchor;
  if(!convertPose(*pose,world_from_anchor))
    return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"World-from-anchor pose is invalid");
  try{*out_id=handle->anchors.create(world_from_anchor,pose->timestamp_ns);
    handle->error.clear();return ARK_RESULT_OK;}
  catch(const std::invalid_argument& e){return fail(handle,ARK_RESULT_INVALID_ARGUMENT,e.what());}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown anchor creation error");}
}

ark_result ark_visual_remove_anchor(ark_visual_handle* handle,uint64_t id){
  if(!handle||id==0)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  if(!handle->anchors.remove(id))return fail(handle,ARK_RESULT_NOT_READY,"Anchor does not exist");
  handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_get_anchor(ark_visual_handle* handle,uint64_t id,ark_anchor_v1* out){
  if(!handle||id==0||!validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);const auto anchor=handle->anchors.find(id);
  if(!anchor)return fail(handle,ARK_RESULT_NOT_READY,"Anchor does not exist");
  const uint32_t size=out->struct_size;*out={};out->struct_size=size;out->id=anchor->id;
  out->created_ns=anchor->created_ns;out->rotation_w=anchor->world_from_anchor.rotation.w;
  out->rotation_x=anchor->world_from_anchor.rotation.x;out->rotation_y=anchor->world_from_anchor.rotation.y;
  out->rotation_z=anchor->world_from_anchor.rotation.z;
  out->position_x_m=anchor->world_from_anchor.translation.x;
  out->position_y_m=anchor->world_from_anchor.translation.y;
  out->position_z_m=anchor->world_from_anchor.translation.z;
  handle->error.clear();return ARK_RESULT_OK;
}

ark_result ark_visual_export_snapshot(ark_visual_handle* handle,int64_t created_ns,
                                      uint8_t* buffer,size_t capacity,size_t* out_size){
  if(!handle||!out_size||created_ns<=0)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  try{
    const auto landmarks=handle->map.snapshot();
    const arkernel::SpatialSnapshot snapshot{created_ns,landmarks,
      handle->anchors.snapshot(),handle->descriptor_store.snapshot()};
    const auto encoded=arkernel::SpatialSnapshotCodec::encode(snapshot);
    *out_size=encoded.size();
    if(!buffer){handle->error.clear();return ARK_RESULT_OK;}
    if(capacity<encoded.size())
      return fail(handle,ARK_RESULT_INVALID_ARGUMENT,"Snapshot output buffer is too small");
    std::memcpy(buffer,encoded.data(),encoded.size());handle->error.clear();return ARK_RESULT_OK;
  }catch(const std::invalid_argument& e){return fail(handle,ARK_RESULT_INVALID_ARGUMENT,e.what());}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown snapshot export error");}
}

ark_result ark_visual_restore_snapshot(ark_visual_handle* handle,const uint8_t* buffer,size_t size,
                                       int64_t restore_now_ns){
  if(!handle||!buffer||size==0||restore_now_ns<=0)return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  try{
    const std::vector<std::uint8_t> bytes(buffer,buffer+size);
    auto decoded=arkernel::SpatialSnapshotCodec::decode(bytes);
    if(!decoded.valid)return fail(handle,ARK_RESULT_INVALID_ARGUMENT,decoded.error.c_str());
    std::string error;
    if(!installSnapshot(handle,std::move(decoded.snapshot),restore_now_ns,error))
      return fail(handle,ARK_RESULT_INVALID_ARGUMENT,error.c_str());
    handle->error.clear();return ARK_RESULT_OK;
  }catch(const std::bad_alloc& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown snapshot restore error");}
}

ark_result ark_visual_restore_snapshot_relocalized(ark_visual_handle* handle,
    const uint8_t* buffer,size_t size,const ark_relocalization_correspondence_v1* values,
    size_t count,int64_t restore_now_ns,ark_relocalization_result_v1* out){
  if(!handle||!buffer||size==0||!values||count<3||count>4096||restore_now_ns<=0||
     !validStruct(out))return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  try{
    std::vector<arkernel::RelocalizationCorrespondence> correspondences;correspondences.reserve(count);
    for(size_t i=0;i<count;++i){
      if(!validStruct(&values[i]))return fail(handle,ARK_RESULT_INVALID_ARGUMENT,
        "Relocalization correspondence struct is invalid");
      correspondences.push_back({{values[i].saved_x_m,values[i].saved_y_m,values[i].saved_z_m},
        {values[i].current_x_m,values[i].current_y_m,values[i].current_z_m}});
    }
    const auto alignment=arkernel::estimateRelocalization(correspondences);
    const uint32_t output_size=out->struct_size;*out={};out->struct_size=output_size;
    out->correspondence_count=alignment.correspondence_count;out->inlier_count=alignment.inlier_count;
    out->rmse_m=alignment.rmse_m;
    if(!alignment.valid)return fail(handle,ARK_RESULT_NOT_READY,
      "Relocalization geometry did not pass inlier and residual gates");
    const std::vector<std::uint8_t> bytes(buffer,buffer+size);
    auto decoded=arkernel::SpatialSnapshotCodec::decode(bytes);
    if(!decoded.valid)return fail(handle,ARK_RESULT_INVALID_ARGUMENT,decoded.error.c_str());
    auto transformed=arkernel::transformSpatialSnapshot(decoded.snapshot,alignment.current_from_saved);
    std::string error;
    if(!installSnapshot(handle,std::move(transformed),restore_now_ns,error))
      return fail(handle,ARK_RESULT_INVALID_ARGUMENT,error.c_str());
    const auto& pose=alignment.current_from_saved;
    out->rotation_w=pose.rotation.w;out->rotation_x=pose.rotation.x;
    out->rotation_y=pose.rotation.y;out->rotation_z=pose.rotation.z;
    out->translation_x_m=pose.translation.x;out->translation_y_m=pose.translation.y;
    out->translation_z_m=pose.translation.z;handle->error.clear();return ARK_RESULT_OK;
  }catch(const std::bad_alloc& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(const std::invalid_argument& e){return fail(handle,ARK_RESULT_INVALID_ARGUMENT,e.what());}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown relocalized restore error");}
}

ark_result ark_visual_restore_snapshot_matched(ark_visual_handle* handle,
    const uint8_t* buffer,size_t size,int64_t restore_now_ns,ark_relocalization_result_v1* out){
  if(!handle||!buffer||size==0||restore_now_ns<=0||!validStruct(out))
    return ARK_RESULT_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(handle->mutex);
  try{
    const std::vector<std::uint8_t> bytes(buffer,buffer+size);
    auto decoded=arkernel::SpatialSnapshotCodec::decode(bytes);
    if(!decoded.valid)return fail(handle,ARK_RESULT_INVALID_ARGUMENT,decoded.error.c_str());
    const auto correspondences=arkernel::matchRelocalizationLandmarks(decoded.snapshot,
      handle->map.snapshot(),handle->descriptor_store.snapshot());
    const auto alignment=arkernel::estimateRelocalization(correspondences);
    const uint32_t output_size=out->struct_size;*out={};out->struct_size=output_size;
    out->correspondence_count=alignment.correspondence_count;out->inlier_count=alignment.inlier_count;
    out->rmse_m=alignment.rmse_m;
    if(!alignment.valid)return fail(handle,ARK_RESULT_NOT_READY,
      "Descriptor matches did not pass relocalization geometry gates");
    auto transformed=arkernel::transformSpatialSnapshot(decoded.snapshot,alignment.current_from_saved);
    std::string error;
    if(!installSnapshot(handle,std::move(transformed),restore_now_ns,error))
      return fail(handle,ARK_RESULT_INVALID_ARGUMENT,error.c_str());
    const auto& pose=alignment.current_from_saved;
    out->rotation_w=pose.rotation.w;out->rotation_x=pose.rotation.x;
    out->rotation_y=pose.rotation.y;out->rotation_z=pose.rotation.z;
    out->translation_x_m=pose.translation.x;out->translation_y_m=pose.translation.y;
    out->translation_z_m=pose.translation.z;handle->error.clear();return ARK_RESULT_OK;
  }catch(const std::bad_alloc& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(const std::invalid_argument& e){return fail(handle,ARK_RESULT_INVALID_ARGUMENT,e.what());}
  catch(const std::exception& e){return fail(handle,ARK_RESULT_INTERNAL_ERROR,e.what());}
  catch(...){return fail(handle,ARK_RESULT_INTERNAL_ERROR,"Unknown matched restore error");}
}

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
  return ark_vio_create_with_extrinsics(config,0,0,0,out);
}
ark_result ark_vio_create_with_extrinsics(const ark_vio_config_v1* config,
    double tx,double ty,double tz,ark_vio_handle** out){
  if(!out)return ARK_RESULT_INVALID_ARGUMENT;
  *out=nullptr;
  ark_vio_config_v1 defaults{};
  if(!config){ark_vio_default_config(&defaults);config=&defaults;}
  if(!validConfigHeader(config)||!std::isfinite(tx)||!std::isfinite(ty)||!std::isfinite(tz))
    return ARK_RESULT_INVALID_ARGUMENT;
  try{auto converted=convert(*config);converted.body_from_camera_translation_m={tx,ty,tz};
    *out=new ark_vio_handle(converted);return ARK_RESULT_OK;}
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
