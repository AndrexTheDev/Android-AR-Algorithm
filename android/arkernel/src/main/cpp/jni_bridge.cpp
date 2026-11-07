#include <jni.h>
#include <cstdint>
#include <limits>
#include <vector>
#include "arkernel/c_api.h"

namespace {
ark_vio_handle* fromVioHandle(jlong value) {
  return reinterpret_cast<ark_vio_handle*>(static_cast<intptr_t>(value));
}
ark_visual_handle* fromVisualHandle(jlong value) {
  return reinterpret_cast<ark_visual_handle*>(static_cast<intptr_t>(value));
}
jlong toHandle(const void* value) {
  return static_cast<jlong>(reinterpret_cast<intptr_t>(value));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeCreate(JNIEnv*,jobject,
    jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble tx,jdouble ty,jdouble tz) {
  ark_vio_config_v1 config{};
  if(ark_vio_default_config(&config)!=ARK_RESULT_OK)return 0;
  config.body_from_camera_w=rw;config.body_from_camera_x=rx;
  config.body_from_camera_y=ry;config.body_from_camera_z=rz;
  ark_vio_handle* handle=nullptr;
  return ark_vio_create_with_extrinsics(&config,tx,ty,tz,&handle)==ARK_RESULT_OK?
    toHandle(handle):0;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeDestroy(JNIEnv*,jobject,jlong handle) {
  ark_vio_destroy(fromVioHandle(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeReset(JNIEnv*,jobject,jlong handle) {
  return ark_vio_reset(fromVioHandle(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeSubmitImu(JNIEnv*,jobject,jlong handle,
    jlong timestamp,jdouble ax,jdouble ay,jdouble az,jdouble gx,jdouble gy,jdouble gz) {
  ark_imu_sample_v1 sample{};sample.struct_size=sizeof(sample);sample.timestamp_ns=timestamp;
  sample.acceleration_x_mps2=ax;sample.acceleration_y_mps2=ay;sample.acceleration_z_mps2=az;
  sample.angular_velocity_x_rps=gx;sample.angular_velocity_y_rps=gy;sample.angular_velocity_z_rps=gz;
  return ark_vio_submit_imu(fromVioHandle(handle),&sample);
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeFuseVisual(JNIEnv*,jobject,jlong handle,jlong timestamp,
    jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble tx,jdouble ty,jdouble tz) {
  ark_relative_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.translation_direction_x=tx;pose.translation_direction_y=ty;
  pose.translation_direction_z=tz;pose.valid=1;
  return ark_vio_fuse_visual(fromVioHandle(handle),&pose);
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeGetState(JNIEnv* env,jobject,jlong handle,
                                                   jlongArray metadata,jdoubleArray values) {
  if(!metadata||!values||env->GetArrayLength(metadata)<3||env->GetArrayLength(values)<17)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_vio_state_v1 state{};state.struct_size=sizeof(state);
  const ark_result result=ark_vio_get_state(fromVioHandle(handle),&state);
  const jlong meta[3]={state.timestamp_ns,static_cast<jlong>(state.status),
                       static_cast<jlong>(state.visual_translation_scale_valid)};
  const jdouble data[17]={state.rotation_w,state.rotation_x,state.rotation_y,state.rotation_z,
    state.position_x_m,state.position_y_m,state.position_z_m,
    state.velocity_x_mps,state.velocity_y_mps,state.velocity_z_mps,
    state.accelerometer_bias_x_mps2,state.accelerometer_bias_y_mps2,state.accelerometer_bias_z_mps2,
    state.gyroscope_bias_x_rps,state.gyroscope_bias_y_rps,state.gyroscope_bias_z_rps,
    state.visual_translation_scale_m};
  env->SetLongArrayRegion(metadata,0,3,meta);env->SetDoubleArrayRegion(values,0,17,data);
  return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeCreate(JNIEnv*,jobject,
    jint width,jint height,jdouble fx,jdouble fy,jdouble cx,jdouble cy,
    jdouble k1,jdouble k2,jdouble p1,jdouble p2,jdouble k3) {
  ark_visual_config_v1 config{};
  if(ark_visual_default_config(width,height,fx,fy,cx,cy,&config)!=ARK_RESULT_OK)return 0;
  config.distortion_k1=k1;config.distortion_k2=k2;config.distortion_p1=p1;
  config.distortion_p2=p2;config.distortion_k3=k3;
  ark_visual_handle* handle=nullptr;
  return ark_visual_create(&config,&handle)==ARK_RESULT_OK?toHandle(handle):0;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeDestroy(JNIEnv*,jobject,jlong handle) {
  ark_visual_destroy(fromVisualHandle(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeReset(JNIEnv*,jobject,jlong handle) {
  ark_visual_reset(fromVisualHandle(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeProcessGray(JNIEnv* env,jobject,jlong handle,
    jobject buffer,jint offset,jint data_size,jint width,jint height,jint row_stride,jlong timestamp,
    jboolean has_rotation,jdouble rw,jdouble rx,jdouble ry,jdouble rz,
    jlongArray metadata,jdoubleArray values) {
  if(!buffer||!metadata||!values||offset<0||data_size<0||
     env->GetArrayLength(metadata)<8||env->GetArrayLength(values)<8)
    return ARK_RESULT_INVALID_ARGUMENT;
  auto* base=static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
  const jlong capacity=env->GetDirectBufferCapacity(buffer);
  if(!base||capacity<0||static_cast<jlong>(offset)+data_size>capacity)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_gray_frame_v1 frame{};frame.struct_size=sizeof(frame);frame.timestamp_ns=timestamp;
  frame.width=width;frame.height=height;frame.row_stride=row_stride;
  frame.data=base+offset;frame.data_size=static_cast<size_t>(data_size);
  frame.has_rotation_prediction=has_rotation?1u:0u;
  frame.rotation_current_from_previous_w=rw;frame.rotation_current_from_previous_x=rx;
  frame.rotation_current_from_previous_y=ry;frame.rotation_current_from_previous_z=rz;
  ark_visual_result_v1 result{};result.struct_size=sizeof(result);
  const ark_result status=ark_visual_process_gray(
      fromVisualHandle(handle),&frame,&result);
  if(status!=ARK_RESULT_OK)return status;
  const jlong meta[8]={result.timestamp_ns,static_cast<jlong>(result.state),
    static_cast<jlong>(result.geometry),static_cast<jlong>(result.accepted_track_count),
    static_cast<jlong>(result.active_feature_count),static_cast<jlong>(result.keyframe),
    static_cast<jlong>(result.relative_pose_valid),static_cast<jlong>(result.positive_depth_count)};
  const jdouble data[8]={result.relative_rotation_w,result.relative_rotation_x,
    result.relative_rotation_y,result.relative_rotation_z,
    result.relative_translation_direction_x,result.relative_translation_direction_y,
    result.relative_translation_direction_z,result.median_parallax_degrees};
  env->SetLongArrayRegion(metadata,0,8,meta);env->SetDoubleArrayRegion(values,0,8,data);
  return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeIntegrateMap(JNIEnv* env,jobject,jlong handle,
    jlong timestamp,jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble px,jdouble py,jdouble pz,
    jlongArray metadata,jdoubleArray values) {
  if(!metadata||!values||env->GetArrayLength(metadata)<8||env->GetArrayLength(values)<9)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_world_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.position_x_m=px;pose.position_y_m=py;pose.position_z_m=pz;
  ark_map_update_v1 update{};update.struct_size=sizeof(update);
  const auto status=ark_visual_integrate_map(fromVisualHandle(handle),&pose,&update);
  if(status!=ARK_RESULT_OK)return status;
  const jlong meta[8]={update.timestamp_ns,static_cast<jlong>(update.created),
    static_cast<jlong>(update.updated),static_cast<jlong>(update.rejected),
    static_cast<jlong>(update.pruned),static_cast<jlong>(update.landmark_count),
    static_cast<jlong>(update.plane_valid),static_cast<jlong>(update.plane_inlier_count)};
  const jdouble data[9]={update.plane_normal_x,update.plane_normal_y,update.plane_normal_z,
    update.plane_distance_from_origin_m,update.plane_center_x_m,update.plane_center_y_m,
    update.plane_center_z_m,update.plane_observed_area_m2,update.plane_mean_absolute_error_m};
  env->SetLongArrayRegion(metadata,0,8,meta);env->SetDoubleArrayRegion(values,0,9,data);return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeRaycastPlane(JNIEnv* env,jobject,jlong handle,
    jdouble ox,jdouble oy,jdouble oz,jdouble dx,jdouble dy,jdouble dz,
    jdouble min_distance,jdouble max_distance,jdoubleArray values) {
  if(!values||env->GetArrayLength(values)<9)return ARK_RESULT_INVALID_ARGUMENT;
  ark_plane_hit_v1 hit{};hit.struct_size=sizeof(hit);
  const auto status=ark_visual_raycast_plane(fromVisualHandle(handle),ox,oy,oz,dx,dy,dz,
                                              min_distance,max_distance,&hit);
  if(status!=ARK_RESULT_OK)return status;
  const jdouble data[9]={hit.position_x_m,hit.position_y_m,hit.position_z_m,
    hit.normal_x,hit.normal_y,hit.normal_z,hit.distance_m,hit.plane_u_m,hit.plane_v_m};
  env->SetDoubleArrayRegion(values,0,9,data);return status;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeCreateAnchor(JNIEnv*,jobject,jlong handle,
    jlong timestamp,jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble px,jdouble py,jdouble pz) {
  ark_world_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.position_x_m=px;pose.position_y_m=py;pose.position_z_m=pz;uint64_t id=0;
  return ark_visual_create_anchor(fromVisualHandle(handle),&pose,&id)==ARK_RESULT_OK?
    static_cast<jlong>(id):0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeRemoveAnchor(JNIEnv*,jobject,jlong handle,jlong id) {
  return ark_visual_remove_anchor(fromVisualHandle(handle),static_cast<uint64_t>(id))==ARK_RESULT_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeGetAnchor(JNIEnv* env,jobject,jlong handle,jlong id,
                                                               jlongArray metadata,jdoubleArray values) {
  if(!metadata||!values||env->GetArrayLength(metadata)<2||env->GetArrayLength(values)<7)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_anchor_v1 anchor{};anchor.struct_size=sizeof(anchor);
  const auto status=ark_visual_get_anchor(fromVisualHandle(handle),static_cast<uint64_t>(id),&anchor);
  if(status!=ARK_RESULT_OK)return status;
  const jlong meta[2]={static_cast<jlong>(anchor.id),anchor.created_ns};
  const jdouble data[7]={anchor.rotation_w,anchor.rotation_x,anchor.rotation_y,anchor.rotation_z,
    anchor.position_x_m,anchor.position_y_m,anchor.position_z_m};
  env->SetLongArrayRegion(metadata,0,2,meta);env->SetDoubleArrayRegion(values,0,7,data);return status;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeExportSpatialSnapshot(
    JNIEnv* env,jobject,jlong handle,jlong created_ns) {
  size_t required=0;
  if(ark_visual_export_snapshot(fromVisualHandle(handle),created_ns,nullptr,0,&required)!=ARK_RESULT_OK||
     required>static_cast<size_t>(std::numeric_limits<jsize>::max()))return nullptr;
  std::vector<uint8_t> bytes(required);
  if(ark_visual_export_snapshot(fromVisualHandle(handle),created_ns,bytes.data(),bytes.size(),
                                &required)!=ARK_RESULT_OK)return nullptr;
  jbyteArray result=env->NewByteArray(static_cast<jsize>(required));
  if(!result)return nullptr;
  env->SetByteArrayRegion(result,0,static_cast<jsize>(required),
                          reinterpret_cast<const jbyte*>(bytes.data()));
  return env->ExceptionCheck()?nullptr:result;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeRestoreSpatialSnapshot(
    JNIEnv* env,jobject,jlong handle,jbyteArray snapshot,jlong restore_now_ns) {
  if(!snapshot)return ARK_RESULT_INVALID_ARGUMENT;
  const jsize size=env->GetArrayLength(snapshot);
  if(size<=0)return ARK_RESULT_INVALID_ARGUMENT;
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  env->GetByteArrayRegion(snapshot,0,size,reinterpret_cast<jbyte*>(bytes.data()));
  if(env->ExceptionCheck())return ARK_RESULT_INTERNAL_ERROR;
  return ark_visual_restore_snapshot(fromVisualHandle(handle),bytes.data(),bytes.size(),restore_now_ns);
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeRestoreRelocalizedSpatialSnapshot(
    JNIEnv* env,jobject,jlong handle,jbyteArray snapshot,jdoubleArray saved_points,
    jdoubleArray current_points,jlong restore_now_ns,jdoubleArray output) {
  if(!snapshot||!saved_points||!current_points||!output)return ARK_RESULT_INVALID_ARGUMENT;
  const jsize byte_count=env->GetArrayLength(snapshot);
  const jsize saved_count=env->GetArrayLength(saved_points);
  if(byte_count<=0||saved_count<18||saved_count%3!=0||saved_count/3>4096||
     env->GetArrayLength(current_points)!=saved_count||env->GetArrayLength(output)<10)
    return ARK_RESULT_INVALID_ARGUMENT;
  std::vector<uint8_t> bytes(static_cast<size_t>(byte_count));
  std::vector<jdouble> saved(static_cast<size_t>(saved_count));
  std::vector<jdouble> current(static_cast<size_t>(saved_count));
  env->GetByteArrayRegion(snapshot,0,byte_count,reinterpret_cast<jbyte*>(bytes.data()));
  env->GetDoubleArrayRegion(saved_points,0,saved_count,saved.data());
  env->GetDoubleArrayRegion(current_points,0,saved_count,current.data());
  if(env->ExceptionCheck())return ARK_RESULT_INTERNAL_ERROR;
  const size_t count=static_cast<size_t>(saved_count/3);
  std::vector<ark_relocalization_correspondence_v1> correspondences(count);
  for(size_t i=0;i<count;++i){auto& value=correspondences[i];value.struct_size=sizeof(value);
    value.saved_x_m=saved[i*3];value.saved_y_m=saved[i*3+1];value.saved_z_m=saved[i*3+2];
    value.current_x_m=current[i*3];value.current_y_m=current[i*3+1];
    value.current_z_m=current[i*3+2];}
  ark_relocalization_result_v1 result{};result.struct_size=sizeof(result);
  const auto status=ark_visual_restore_snapshot_relocalized(fromVisualHandle(handle),bytes.data(),
    bytes.size(),correspondences.data(),correspondences.size(),restore_now_ns,&result);
  if(status!=ARK_RESULT_OK)return status;
  const jdouble values[10]={static_cast<double>(result.correspondence_count),
    static_cast<double>(result.inlier_count),result.rmse_m,result.rotation_w,result.rotation_x,
    result.rotation_y,result.rotation_z,result.translation_x_m,result.translation_y_m,
    result.translation_z_m};
  env->SetDoubleArrayRegion(output,0,10,values);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeRestoreMatchedSpatialSnapshot(
    JNIEnv* env,jobject,jlong handle,jbyteArray snapshot,jlong restore_now_ns,jdoubleArray output) {
  if(!snapshot||!output||env->GetArrayLength(output)<10)return ARK_RESULT_INVALID_ARGUMENT;
  const jsize byte_count=env->GetArrayLength(snapshot);
  if(byte_count<=0)return ARK_RESULT_INVALID_ARGUMENT;
  std::vector<uint8_t> bytes(static_cast<size_t>(byte_count));
  env->GetByteArrayRegion(snapshot,0,byte_count,reinterpret_cast<jbyte*>(bytes.data()));
  if(env->ExceptionCheck())return ARK_RESULT_INTERNAL_ERROR;
  ark_relocalization_result_v1 result{};result.struct_size=sizeof(result);
  const auto status=ark_visual_restore_snapshot_matched(fromVisualHandle(handle),bytes.data(),
    bytes.size(),restore_now_ns,&result);
  if(status!=ARK_RESULT_OK)return status;
  const jdouble values[10]={static_cast<double>(result.correspondence_count),
    static_cast<double>(result.inlier_count),result.rmse_m,result.rotation_w,result.rotation_x,
    result.rotation_y,result.rotation_z,result.translation_x_m,result.translation_y_m,
    result.translation_z_m};
  env->SetDoubleArrayRegion(output,0,10,values);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeGetPlaneCount(JNIEnv*,jobject,jlong handle) {
  size_t count=0;
  return ark_visual_get_plane_count(fromVisualHandle(handle),&count)==ARK_RESULT_OK?
    static_cast<jlong>(count):-1;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeGetPlane(JNIEnv* env,jobject,jlong handle,
    jlong index,jlongArray metadata,jdoubleArray values) {
  if(index<0||!metadata||!values||env->GetArrayLength(metadata)<3||
     env->GetArrayLength(values)<9)return ARK_RESULT_INVALID_ARGUMENT;
  ark_plane_v1 plane{};plane.struct_size=sizeof(plane);
  const auto status=ark_visual_get_plane(fromVisualHandle(handle),static_cast<size_t>(index),&plane);
  if(status!=ARK_RESULT_OK)return status;
  uint32_t semantic=ARK_PLANE_UNKNOWN;
  const auto semantic_status=ark_visual_get_plane_semantic(
    fromVisualHandle(handle),static_cast<size_t>(index),&semantic);
  if(semantic_status!=ARK_RESULT_OK)return semantic_status;
  const jlong meta[3]={static_cast<jlong>(plane.id),static_cast<jlong>(plane.inlier_count),
                       static_cast<jlong>(semantic)};
  const jdouble data[9]={plane.normal_x,plane.normal_y,plane.normal_z,
    plane.distance_from_origin_m,plane.center_x_m,plane.center_y_m,plane.center_z_m,
    plane.observed_area_m2,plane.mean_absolute_error_m};
  env->SetLongArrayRegion(metadata,0,3,meta);env->SetDoubleArrayRegion(values,0,9,data);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeGetRoomLayout(JNIEnv* env,jobject,jlong handle,
    jlongArray metadata,jdoubleArray values) {
  if(!metadata||!values||env->GetArrayLength(metadata)<6||env->GetArrayLength(values)<6)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_room_layout_v1 layout{};layout.struct_size=sizeof(layout);
  const auto status=ark_visual_get_room_layout(fromVisualHandle(handle),&layout);
  if(status!=ARK_RESULT_OK)return status;
  const jlong meta[6]={static_cast<jlong>(layout.has_floor),static_cast<jlong>(layout.has_ceiling),
    static_cast<jlong>(layout.height_valid),static_cast<jlong>(layout.floor_plane_id),
    static_cast<jlong>(layout.ceiling_plane_id),static_cast<jlong>(layout.wall_count)};
  const jdouble data[6]={layout.floor_elevation_m,layout.ceiling_elevation_m,layout.room_height_m,
    layout.observed_floor_area_m2,layout.observed_ceiling_area_m2,layout.observed_wall_area_m2};
  env->SetLongArrayRegion(metadata,0,6,meta);env->SetDoubleArrayRegion(values,0,6,data);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeUpdateMappingGate(JNIEnv* env,jobject,jlong handle,
    jboolean pose_initialized,jboolean pose_limited,jboolean visual_tracking,jlong accepted_tracks,
    jboolean imu_overflow,jlongArray output) {
  if(accepted_tracks<0||static_cast<uint64_t>(accepted_tracks)>
       std::numeric_limits<size_t>::max()||!output||env->GetArrayLength(output)<3)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_mapping_gate_v1 gate{};gate.struct_size=sizeof(gate);
  const auto status=ark_visual_update_mapping_gate(fromVisualHandle(handle),pose_initialized?1u:0u,
    pose_limited?1u:0u,visual_tracking?1u:0u,static_cast<size_t>(accepted_tracks),
    imu_overflow?1u:0u,&gate);
  if(status!=ARK_RESULT_OK)return status;
  const jlong values[3]={static_cast<jlong>(gate.state),static_cast<jlong>(gate.reason),
                         static_cast<jlong>(gate.integrate)};
  env->SetLongArrayRegion(output,0,3,values);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeIntegrateSemantic(JNIEnv* env,jobject,jlong handle,
    jlong class_id,jlong track_id,jlong timestamp,jlong now_ns,jdouble x,jdouble y,jdouble z,
    jdouble confidence,jboolean dynamic,jlongArray output) {
  if(class_id<0||static_cast<uint64_t>(class_id)>std::numeric_limits<uint32_t>::max()||
     !output||env->GetArrayLength(output)<5)return ARK_RESULT_INVALID_ARGUMENT;
  ark_semantic_observation_v1 observation{};observation.struct_size=sizeof(observation);
  observation.class_id=static_cast<uint32_t>(class_id);observation.source_track_id=track_id;
  observation.timestamp_ns=timestamp;observation.position_x_m=x;observation.position_y_m=y;
  observation.position_z_m=z;observation.confidence=confidence;observation.dynamic=dynamic?1u:0u;
  ark_semantic_update_v1 update{};update.struct_size=sizeof(update);
  const auto status=ark_visual_integrate_semantics(
    fromVisualHandle(handle),&observation,1,now_ns,&update);
  if(status!=ARK_RESULT_OK)return status;
  const jlong values[5]={static_cast<jlong>(update.created),static_cast<jlong>(update.updated),
    static_cast<jlong>(update.rejected),static_cast<jlong>(update.pruned),
    static_cast<jlong>(update.object_count)};
  env->SetLongArrayRegion(output,0,5,values);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativePruneSemantics(JNIEnv* env,jobject,jlong handle,
    jlong now_ns,jlongArray output) {
  if(!output||env->GetArrayLength(output)<5)return ARK_RESULT_INVALID_ARGUMENT;
  ark_semantic_update_v1 update{};update.struct_size=sizeof(update);
  const auto status=ark_visual_integrate_semantics(fromVisualHandle(handle),nullptr,0,now_ns,&update);
  if(status!=ARK_RESULT_OK)return status;
  const jlong values[5]={static_cast<jlong>(update.created),static_cast<jlong>(update.updated),
    static_cast<jlong>(update.rejected),static_cast<jlong>(update.pruned),
    static_cast<jlong>(update.object_count)};
  env->SetLongArrayRegion(output,0,5,values);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeGetSemanticObjectCount(
    JNIEnv*,jobject,jlong handle) {
  size_t count=0;
  return ark_visual_get_semantic_object_count(fromVisualHandle(handle),&count)==ARK_RESULT_OK?
    static_cast<jlong>(count):-1;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeGetSemanticObject(JNIEnv* env,jobject,jlong handle,
    jlong index,jlongArray metadata,jdoubleArray values) {
  if(index<0||!metadata||!values||env->GetArrayLength(metadata)<7||
     env->GetArrayLength(values)<4)return ARK_RESULT_INVALID_ARGUMENT;
  ark_semantic_object_v1 object{};object.struct_size=sizeof(object);
  const auto status=ark_visual_get_semantic_object(
    fromVisualHandle(handle),static_cast<size_t>(index),&object);
  if(status!=ARK_RESULT_OK)return status;
  const jlong meta[7]={static_cast<jlong>(object.id),static_cast<jlong>(object.class_id),
    object.source_track_id,object.first_seen_ns,object.last_seen_ns,
    static_cast<jlong>(object.observation_count),static_cast<jlong>(object.dynamic)};
  const jdouble data[4]={object.position_x_m,object.position_y_m,object.position_z_m,
                          object.confidence};
  env->SetLongArrayRegion(metadata,0,7,meta);env->SetDoubleArrayRegion(values,0,4,data);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeProjectSemantic(JNIEnv* env,jobject,jlong handle,
    jlong class_id,jlong track_id,jlong timestamp,jlong now_ns,jdouble pixel_x,jdouble pixel_y,
    jdouble confidence,jboolean dynamic,jint surface,jdouble min_distance,jdouble max_distance,
    jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble px,jdouble py,jdouble pz,jlongArray output) {
  if(class_id<0||static_cast<uint64_t>(class_id)>std::numeric_limits<uint32_t>::max()||
     !output||env->GetArrayLength(output)<5)return ARK_RESULT_INVALID_ARGUMENT;
  ark_semantic_projection_v1 projection{};projection.struct_size=sizeof(projection);
  projection.class_id=static_cast<uint32_t>(class_id);projection.source_track_id=track_id;
  projection.timestamp_ns=timestamp;projection.pixel_x=pixel_x;projection.pixel_y=pixel_y;
  projection.confidence=confidence;projection.dynamic=dynamic?1u:0u;
  projection.surface=static_cast<uint32_t>(surface);projection.min_distance_m=min_distance;
  projection.max_distance_m=max_distance;
  ark_world_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.position_x_m=px;pose.position_y_m=py;pose.position_z_m=pz;
  ark_semantic_update_v1 update{};update.struct_size=sizeof(update);
  const auto status=ark_visual_project_semantic(
    fromVisualHandle(handle),&projection,&pose,now_ns,&update);
  if(status!=ARK_RESULT_OK)return status;
  const jlong values[5]={static_cast<jlong>(update.created),static_cast<jlong>(update.updated),
    static_cast<jlong>(update.rejected),static_cast<jlong>(update.pruned),
    static_cast<jlong>(update.object_count)};
  env->SetLongArrayRegion(output,0,5,values);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeIntegrateSurfels(JNIEnv* env,jobject,jlong handle,
    jlong timestamp,jlong now_ns,jdoubleArray observations,jdouble rw,jdouble rx,jdouble ry,jdouble rz,
    jdouble px,jdouble py,jdouble pz,jlongArray output) {
  if(!observations||!output||env->GetArrayLength(output)<5)return ARK_RESULT_INVALID_ARGUMENT;
  const jsize value_count=env->GetArrayLength(observations);
  if(value_count<0||value_count%9!=0||value_count/9>100000)return ARK_RESULT_INVALID_ARGUMENT;
  std::vector<jdouble> values(static_cast<size_t>(value_count));
  env->GetDoubleArrayRegion(observations,0,value_count,values.data());
  if(env->ExceptionCheck())return ARK_RESULT_INTERNAL_ERROR;
  const size_t count=static_cast<size_t>(value_count/9);
  std::vector<ark_surfel_observation_v1> native_observations(count);
  for(size_t i=0;i<count;++i){const size_t offset=i*9;auto& value=native_observations[i];
    value.struct_size=sizeof(value);value.timestamp_ns=timestamp;
    value.position_x_camera_m=values[offset];value.position_y_camera_m=values[offset+1];
    value.position_z_camera_m=values[offset+2];value.normal_x_camera=values[offset+3];
    value.normal_y_camera=values[offset+4];value.normal_z_camera=values[offset+5];
    value.confidence=values[offset+6];value.radius_m=values[offset+7];
    value.normal_valid=values[offset+8]!=0.0?1u:0u;}
  ark_world_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.position_x_m=px;pose.position_y_m=py;pose.position_z_m=pz;
  ark_surfel_update_v1 update{};update.struct_size=sizeof(update);
  const auto status=ark_visual_integrate_surfels(fromVisualHandle(handle),
    native_observations.empty()?nullptr:native_observations.data(),count,&pose,now_ns,&update);
  if(status!=ARK_RESULT_OK)return status;
  const jlong result[5]={static_cast<jlong>(update.created),static_cast<jlong>(update.updated),
    static_cast<jlong>(update.rejected),static_cast<jlong>(update.pruned),
    static_cast<jlong>(update.surfel_count)};
  env->SetLongArrayRegion(output,0,5,result);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeGetSurfelCount(JNIEnv*,jobject,jlong handle) {
  size_t count=0;
  return ark_visual_get_surfel_count(fromVisualHandle(handle),&count)==ARK_RESULT_OK?
    static_cast<jlong>(count):-1;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeGetSurfel(JNIEnv* env,jobject,jlong handle,
    jlong index,jlongArray metadata,jdoubleArray values) {
  if(index<0||!metadata||!values||env->GetArrayLength(metadata)<5||
     env->GetArrayLength(values)<8)return ARK_RESULT_INVALID_ARGUMENT;
  ark_surfel_v1 surfel{};surfel.struct_size=sizeof(surfel);
  const auto status=ark_visual_get_surfel(fromVisualHandle(handle),static_cast<size_t>(index),&surfel);
  if(status!=ARK_RESULT_OK)return status;
  const jlong meta[5]={static_cast<jlong>(surfel.id),static_cast<jlong>(surfel.observation_count),
    surfel.first_seen_ns,surfel.last_seen_ns,static_cast<jlong>(surfel.normal_valid)};
  const jdouble data[8]={surfel.position_x_world_m,surfel.position_y_world_m,
    surfel.position_z_world_m,surfel.normal_x_world,surfel.normal_y_world,
    surfel.normal_z_world,surfel.confidence,surfel.radius_m};
  env->SetLongArrayRegion(metadata,0,5,meta);env->SetDoubleArrayRegion(values,0,8,data);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeIntegrateDepthImage(JNIEnv* env,jobject,
    jlong handle,jfloatArray depth,jfloatArray confidence,jint width,jint height,jint row_stride,
    jint confidence_stride,jlong timestamp,jlong now_ns,jint pixel_stride,jdouble min_depth,
    jdouble max_depth,jdouble min_confidence,jboolean estimate_normals,jdouble rw,jdouble rx,
    jdouble ry,jdouble rz,jdouble px,jdouble py,jdouble pz,jlongArray output) {
  if(!depth||!output||env->GetArrayLength(output)<5)return ARK_RESULT_INVALID_ARGUMENT;
  const jsize depth_count=env->GetArrayLength(depth);
  const jsize confidence_count=confidence?env->GetArrayLength(confidence):0;
  std::vector<jfloat> depth_values(static_cast<size_t>(depth_count));
  std::vector<jfloat> confidence_values(static_cast<size_t>(confidence_count));
  env->GetFloatArrayRegion(depth,0,depth_count,depth_values.data());
  if(confidence)env->GetFloatArrayRegion(confidence,0,confidence_count,confidence_values.data());
  if(env->ExceptionCheck())return ARK_RESULT_INTERNAL_ERROR;
  ark_metric_depth_image_v1 image{};image.struct_size=sizeof(image);image.width=width;image.height=height;
  image.row_stride_elements=row_stride;image.depth_m=depth_values.data();
  image.depth_element_count=depth_values.size();
  image.confidence=confidence?confidence_values.data():nullptr;
  image.confidence_row_stride_elements=confidence?confidence_stride:0;
  image.confidence_element_count=confidence_values.size();image.timestamp_ns=timestamp;
  image.pixel_stride=pixel_stride;image.min_depth_m=min_depth;image.max_depth_m=max_depth;
  image.min_confidence=min_confidence;image.estimate_normals=estimate_normals?1u:0u;
  ark_world_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.position_x_m=px;pose.position_y_m=py;pose.position_z_m=pz;
  ark_surfel_update_v1 update{};update.struct_size=sizeof(update);
  const auto status=ark_visual_integrate_depth_image(fromVisualHandle(handle),&image,&pose,now_ns,&update);
  if(status!=ARK_RESULT_OK)return status;
  const jlong result[5]={static_cast<jlong>(update.created),static_cast<jlong>(update.updated),
    static_cast<jlong>(update.rejected),static_cast<jlong>(update.pruned),
    static_cast<jlong>(update.surfel_count)};
  env->SetLongArrayRegion(output,0,5,result);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeRaycastSurfel(JNIEnv* env,jobject,jlong handle,
    jdouble ox,jdouble oy,jdouble oz,jdouble dx,jdouble dy,jdouble dz,jdouble min_distance,
    jdouble max_distance,jlongArray metadata,jdoubleArray values) {
  if(!metadata||!values||env->GetArrayLength(metadata)<2||env->GetArrayLength(values)<8)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_surfel_hit_v1 hit{};hit.struct_size=sizeof(hit);
  const auto status=ark_visual_raycast_surfel(fromVisualHandle(handle),ox,oy,oz,dx,dy,dz,
    min_distance,max_distance,&hit);
  if(status!=ARK_RESULT_OK)return status;
  const jlong meta[2]={static_cast<jlong>(hit.surfel_id),static_cast<jlong>(hit.normal_valid)};
  const jdouble data[8]={hit.position_x_world_m,hit.position_y_world_m,hit.position_z_world_m,
    hit.normal_x_world,hit.normal_y_world,hit.normal_z_world,hit.distance_m,hit.confidence};
  env->SetLongArrayRegion(metadata,0,2,meta);env->SetDoubleArrayRegion(values,0,8,data);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeRenderSurfelDepth(JNIEnv* env,jobject,
    jlong handle,jlong timestamp,jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble px,
    jdouble py,jdouble pz,jdouble min_depth,jdouble max_depth,jint max_radius,jlong max_work,
    jlongArray metadata) {
  if(!metadata||env->GetArrayLength(metadata)<5||max_work<=0||
     static_cast<uint64_t>(max_work)>std::numeric_limits<size_t>::max())return nullptr;
  ark_world_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.position_x_m=px;pose.position_y_m=py;pose.position_z_m=pz;
  ark_surfel_depth_render_v1 render{};render.struct_size=sizeof(render);
  auto status=ark_visual_render_surfel_depth(fromVisualHandle(handle),&pose,min_depth,max_depth,
    max_radius,static_cast<size_t>(max_work),nullptr,0,&render);
  if(status!=ARK_RESULT_OK||render.required_pixel_count>
     static_cast<uint64_t>(std::numeric_limits<jsize>::max()))return nullptr;
  std::vector<float> depth(static_cast<size_t>(render.required_pixel_count));
  status=ark_visual_render_surfel_depth(fromVisualHandle(handle),&pose,min_depth,max_depth,
    max_radius,static_cast<size_t>(max_work),depth.data(),depth.size(),&render);
  if(status!=ARK_RESULT_OK)return nullptr;
  jfloatArray result=env->NewFloatArray(static_cast<jsize>(depth.size()));if(!result)return nullptr;
  env->SetFloatArrayRegion(result,0,static_cast<jsize>(depth.size()),depth.data());
  const jlong meta[5]={render.width,render.height,static_cast<jlong>(render.projected_surfel_count),
    static_cast<jlong>(render.rasterized_pixel_count),static_cast<jlong>(render.truncated)};
  env->SetLongArrayRegion(metadata,0,5,meta);
  return env->ExceptionCheck()?nullptr:result;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeIntegrateDepthBuffer(JNIEnv* env,jobject,
    jlong handle,jobject depth,jint depth_offset,jint depth_count,jobject confidence,
    jint confidence_offset,jint confidence_count,jint width,jint height,jint row_stride,
    jint confidence_stride,jlong timestamp,jlong now_ns,jint pixel_stride,jdouble min_depth,
    jdouble max_depth,jdouble min_confidence,jboolean estimate_normals,jdouble rw,jdouble rx,
    jdouble ry,jdouble rz,jdouble px,jdouble py,jdouble pz,jlongArray output) {
  if(!depth||!output||env->GetArrayLength(output)<5||depth_offset<0||depth_count<0||
     confidence_offset<0||confidence_count<0)return ARK_RESULT_INVALID_ARGUMENT;
  void* depth_address=env->GetDirectBufferAddress(depth);
  const jlong depth_capacity=env->GetDirectBufferCapacity(depth);
  if(!depth_address||depth_capacity<0||static_cast<jlong>(depth_offset)+depth_count>depth_capacity||
     reinterpret_cast<uintptr_t>(depth_address)%alignof(float)!=0)return ARK_RESULT_INVALID_ARGUMENT;
  void* confidence_address=nullptr;jlong confidence_capacity=0;
  if(confidence){
    confidence_address=env->GetDirectBufferAddress(confidence);
    confidence_capacity=env->GetDirectBufferCapacity(confidence);
    if(!confidence_address||confidence_capacity<0||
       static_cast<jlong>(confidence_offset)+confidence_count>confidence_capacity||
       reinterpret_cast<uintptr_t>(confidence_address)%alignof(float)!=0)
      return ARK_RESULT_INVALID_ARGUMENT;
  }else if(confidence_offset!=0||confidence_count!=0||confidence_stride!=0){
    return ARK_RESULT_INVALID_ARGUMENT;
  }
  ark_metric_depth_image_v1 image{};image.struct_size=sizeof(image);image.width=width;
  image.height=height;image.row_stride_elements=row_stride;
  image.depth_m=static_cast<const float*>(depth_address)+depth_offset;
  image.depth_element_count=static_cast<size_t>(depth_count);
  image.confidence=confidence?static_cast<const float*>(confidence_address)+confidence_offset:nullptr;
  image.confidence_row_stride_elements=confidence?confidence_stride:0;
  image.confidence_element_count=static_cast<size_t>(confidence_count);image.timestamp_ns=timestamp;
  image.pixel_stride=pixel_stride;image.min_depth_m=min_depth;image.max_depth_m=max_depth;
  image.min_confidence=min_confidence;image.estimate_normals=estimate_normals?1u:0u;
  ark_world_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.position_x_m=px;pose.position_y_m=py;pose.position_z_m=pz;
  ark_surfel_update_v1 update{};update.struct_size=sizeof(update);
  const auto status=ark_visual_integrate_depth_image(
    fromVisualHandle(handle),&image,&pose,now_ns,&update);
  if(status!=ARK_RESULT_OK)return status;
  const jlong result[5]={static_cast<jlong>(update.created),static_cast<jlong>(update.updated),
    static_cast<jlong>(update.rejected),static_cast<jlong>(update.pruned),
    static_cast<jlong>(update.surfel_count)};
  env->SetLongArrayRegion(output,0,5,result);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVisualFrontend_nativeRenderSurfelDepthInto(JNIEnv* env,jobject,
    jlong handle,jlong timestamp,jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble px,
    jdouble py,jdouble pz,jdouble min_depth,jdouble max_depth,jint max_radius,jlong max_work,
    jobject output,jint output_offset,jint output_count,jlongArray metadata) {
  if(!output||!metadata||env->GetArrayLength(metadata)<5||output_offset<0||output_count<0||
     max_work<=0||static_cast<uint64_t>(max_work)>std::numeric_limits<size_t>::max())
    return ARK_RESULT_INVALID_ARGUMENT;
  void* address=env->GetDirectBufferAddress(output);
  const jlong capacity=env->GetDirectBufferCapacity(output);
  if(!address||capacity<0||static_cast<jlong>(output_offset)+output_count>capacity||
     reinterpret_cast<uintptr_t>(address)%alignof(float)!=0)return ARK_RESULT_INVALID_ARGUMENT;
  ark_world_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.position_x_m=px;pose.position_y_m=py;pose.position_z_m=pz;
  ark_surfel_depth_render_v1 render{};render.struct_size=sizeof(render);
  const auto status=ark_visual_render_surfel_depth(fromVisualHandle(handle),&pose,min_depth,
    max_depth,max_radius,static_cast<size_t>(max_work),
    static_cast<float*>(address)+output_offset,static_cast<size_t>(output_count),&render);
  if(status!=ARK_RESULT_OK)return status;
  const jlong values[5]={render.width,render.height,
    static_cast<jlong>(render.projected_surfel_count),
    static_cast<jlong>(render.rasterized_pixel_count),static_cast<jlong>(render.truncated)};
  env->SetLongArrayRegion(metadata,0,5,values);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelTensorPreprocessor_nativePreprocess(JNIEnv* env,jobject,
    jobject image,jint image_offset,jint image_size,jint width,jint height,jint row_stride,
    jint image_format,jint output_width,jint output_height,jint output_channels,
    jdoubleArray mean,jdoubleArray standard_deviation,jint element_type,
    jdouble quantization_scale,jint quantization_zero_point,jboolean swap_red_blue,
    jlong max_output_elements,jobject output,jint output_offset,jint output_capacity,
    jlongArray metadata) {
  if(!image||!output||!mean||!standard_deviation||!metadata||image_offset<0||image_size<0||
     output_offset<0||output_capacity<0||max_output_elements<=0||output_channels<1||
     env->GetArrayLength(mean)<output_channels||
     env->GetArrayLength(standard_deviation)<output_channels||
     env->GetArrayLength(metadata)<5)return ARK_RESULT_INVALID_ARGUMENT;
  auto* image_base=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(image));
  auto* output_base=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(output));
  const jlong image_capacity=env->GetDirectBufferCapacity(image);
  const jlong direct_output_capacity=env->GetDirectBufferCapacity(output);
  if(!image_base||!output_base||image_capacity<0||direct_output_capacity<0||
     static_cast<jlong>(image_offset)+image_size>image_capacity||
     static_cast<jlong>(output_offset)+output_capacity>direct_output_capacity||
     static_cast<unsigned long long>(max_output_elements)>
       static_cast<unsigned long long>(std::numeric_limits<size_t>::max()))
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_tensor_image_v1 input{};input.struct_size=sizeof(input);input.width=width;input.height=height;
  input.row_stride_bytes=row_stride;input.format=static_cast<uint32_t>(image_format);
  input.data=image_base+image_offset;input.data_size=static_cast<size_t>(image_size);
  ark_tensor_preprocess_config_v1 config{};config.struct_size=sizeof(config);
  config.output_width=output_width;config.output_height=output_height;
  config.output_channels=output_channels;
  for(double& value:config.standard_deviation)value=1.0;
  env->GetDoubleArrayRegion(mean,0,output_channels,config.mean);
  env->GetDoubleArrayRegion(standard_deviation,0,output_channels,config.standard_deviation);
  if(env->ExceptionCheck())return ARK_RESULT_INVALID_ARGUMENT;
  config.element_type=static_cast<uint32_t>(element_type);
  config.quantization_scale=quantization_scale;
  config.quantization_zero_point=quantization_zero_point;
  config.swap_red_blue=swap_red_blue?1u:0u;
  config.max_output_elements=static_cast<size_t>(max_output_elements);
  ark_tensor_output_v1 tensor{};tensor.struct_size=sizeof(tensor);
  const ark_result result=ark_preprocess_tensor(&input,&config,output_base+output_offset,
    static_cast<size_t>(output_capacity),&tensor);
  const jlong values[5]={tensor.width,tensor.height,tensor.channels,
    static_cast<jlong>(tensor.element_count),static_cast<jlong>(tensor.required_bytes)};
  env->SetLongArrayRegion(metadata,0,5,values);
  return result;
}
