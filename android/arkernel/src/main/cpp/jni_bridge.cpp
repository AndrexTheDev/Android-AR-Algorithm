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
