#include <jni.h>
#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
#include "arkernel/c_api.h"
#include "arkernel/clock_aligner.h"
#include "arkernel/detection_tracker.h"
#include "arkernel/reconstruction_asset.h"
#include "arkernel/reconstruction_capture.h"
#include "arkernel/reconstruction_pipeline.h"
#include "arkernel/temporal_depth_filter.h"
#include "arkernel/texture_pipeline.h"

namespace {
ark_vio_handle* fromVioHandle(jlong value) {
  return reinterpret_cast<ark_vio_handle*>(static_cast<intptr_t>(value));
}
ark_visual_handle* fromVisualHandle(jlong value) {
  return reinterpret_cast<ark_visual_handle*>(static_cast<intptr_t>(value));
}
arkernel::ReconstructionCapturePlanner* fromReconstructionPlannerHandle(jlong value) {
  return reinterpret_cast<arkernel::ReconstructionCapturePlanner*>(static_cast<intptr_t>(value));
}
arkernel::ClockAligner* fromClockAlignerHandle(jlong value) {
  return reinterpret_cast<arkernel::ClockAligner*>(static_cast<intptr_t>(value));
}
struct AndroidReconstructionPipeline {
  explicit AndroidReconstructionPipeline(arkernel::ReconstructionPipelineConfig config,
                                         std::size_t output_budget)
      : pipeline(std::move(config)), max_glb_bytes(output_budget) {}
  arkernel::ReconstructionFusionPipeline pipeline;
  std::size_t max_glb_bytes;
};
AndroidReconstructionPipeline* fromFusionPipelineHandle(jlong value) {
  return reinterpret_cast<AndroidReconstructionPipeline*>(static_cast<intptr_t>(value));
}
bool meshFitsGlbBudget(const arkernel::ReconstructionMesh& mesh, std::size_t budget) {
  constexpr std::size_t kJsonAllowance = 65536;
  const std::size_t vertices = mesh.positions_m.size();
  const std::size_t indices = mesh.triangle_indices.size();
  if (vertices > (std::numeric_limits<std::size_t>::max() - kJsonAllowance) / 32 ||
      indices > (std::numeric_limits<std::size_t>::max() - kJsonAllowance - vertices * 32) / 4)
    return false;
  return kJsonAllowance + vertices * 32 + indices * 4 <= budget;
}
jlong toHandle(const void* value) {
  return static_cast<jlong>(reinterpret_cast<intptr_t>(value));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ArKernelClockAligner_nativeCreate(
    JNIEnv*,jobject,jint capacity,jint min_observations) {
  try {
    if(capacity<2||capacity>4096||min_observations<2||min_observations>capacity)return 0;
    return toHandle(new arkernel::ClockAligner(static_cast<std::size_t>(capacity),
      static_cast<std::size_t>(min_observations)));
  }catch(...){return 0;}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_arena_arkernel_ArKernelClockAligner_nativeAddObservation(
    JNIEnv*,jobject,jlong handle,jlong source_ns,jlong core_ns) {
  auto* aligner=fromClockAlignerHandle(handle);
  return aligner&&source_ns>0&&core_ns>0&&aligner->addObservation(source_ns,core_ns)?
    JNI_TRUE:JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_arena_arkernel_ArKernelClockAligner_nativeAlignment(
    JNIEnv* env,jobject,jlong handle,jlongArray metadata,jdoubleArray values) {
  auto* aligner=fromClockAlignerHandle(handle);
  if(!aligner||!metadata||!values||env->GetArrayLength(metadata)<2||
      env->GetArrayLength(values)<3)return JNI_FALSE;
  const auto model=aligner->alignment();if(!model)return JNI_FALSE;
  const jlong meta[2]={static_cast<jlong>(model->observation_count),
    static_cast<jlong>(model->source_span_ns)};
  const jdouble data[3]={model->scale,model->offset_ns,model->rms_error_ns};
  env->SetLongArrayRegion(metadata,0,2,meta);
  env->SetDoubleArrayRegion(values,0,3,data);
  return env->ExceptionCheck()?JNI_FALSE:JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_ArKernelClockAligner_nativeReset(
    JNIEnv*,jobject,jlong handle) {
  auto* aligner=fromClockAlignerHandle(handle);if(aligner)aligner->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_ArKernelClockAligner_nativeDestroy(
    JNIEnv*,jobject,jlong handle) {delete fromClockAlignerHandle(handle);}

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
Java_ai_arena_arkernel_ArKernelVio_nativeFuseVisual(JNIEnv* env,jobject,jlong handle,jlong timestamp,
    jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble tx,jdouble ty,jdouble tz,
    jlongArray metadata,jdoubleArray values) {
  if(!metadata||!values||env->GetArrayLength(metadata)<3||env->GetArrayLength(values)<3)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_relative_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.translation_direction_x=tx;pose.translation_direction_y=ty;
  pose.translation_direction_z=tz;pose.valid=1;
  ark_vio_fusion_result_v1 result{};result.struct_size=sizeof(result);
  const ark_result status=ark_vio_fuse_visual_measured(fromVioHandle(handle),&pose,&result);
  const jlong meta[3]={static_cast<jlong>(result.rotation_applied),
    static_cast<jlong>(result.position_applied),static_cast<jlong>(result.scale_updated)};
  const jdouble data[3]={result.observed_scale_m,result.predicted_displacement_m,
    result.translation_direction_cosine};
  env->SetLongArrayRegion(metadata,0,3,meta);env->SetDoubleArrayRegion(values,0,3,data);
  return status;
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

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelDepthPostprocessor_nativePostprocess(JNIEnv* env,jobject,
    jobject tensor,jint tensor_offset,jint tensor_size,jint width,jint height,jint row_stride,
    jint element_type,jdouble quantization_scale,jint quantization_zero_point,
    jint output_width,jint output_height,jint transform,jdouble unit_to_meters,
    jdouble min_depth,jdouble max_depth,jdouble max_relative_discontinuity,
    jlong max_output_elements,jobject depth_output,
    jobject confidence_output,jint output_capacity_elements,jlongArray metadata) {
  if(!tensor||!depth_output||!confidence_output||!metadata||tensor_offset<0||tensor_size<0||
     output_capacity_elements<0||max_output_elements<=0||env->GetArrayLength(metadata)<3)
    return ARK_RESULT_INVALID_ARGUMENT;
  auto* tensor_base=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(tensor));
  auto* depth_base=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(depth_output));
  auto* confidence_base=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(confidence_output));
  const jlong tensor_capacity=env->GetDirectBufferCapacity(tensor);
  const jlong depth_capacity=env->GetDirectBufferCapacity(depth_output);
  const jlong confidence_capacity=env->GetDirectBufferCapacity(confidence_output);
  const jlong required_output_bytes=static_cast<jlong>(output_capacity_elements)*sizeof(float);
  if(!tensor_base||!depth_base||!confidence_base||tensor_capacity<0||depth_capacity<0||
     confidence_capacity<0||static_cast<jlong>(tensor_offset)+tensor_size>tensor_capacity||
     required_output_bytes>depth_capacity||required_output_bytes>confidence_capacity||
     static_cast<unsigned long long>(max_output_elements)>
       static_cast<unsigned long long>(std::numeric_limits<size_t>::max()))
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_depth_tensor_v1 input{};input.struct_size=sizeof(input);input.width=width;input.height=height;
  input.row_stride_elements=row_stride;input.element_type=static_cast<uint32_t>(element_type);
  input.data=tensor_base+tensor_offset;input.data_size_bytes=static_cast<size_t>(tensor_size);
  input.quantization_scale=quantization_scale;input.quantization_zero_point=quantization_zero_point;
  ark_depth_postprocess_config_v1 config{};config.struct_size=sizeof(config);
  config.output_width=output_width;config.output_height=output_height;
  config.transform=static_cast<uint32_t>(transform);config.unit_to_meters=unit_to_meters;
  config.min_depth_meters=min_depth;config.max_depth_meters=max_depth;
  config.max_relative_discontinuity=max_relative_discontinuity;
  config.max_output_elements=static_cast<size_t>(max_output_elements);
  ark_depth_postprocess_output_v1 result{};result.struct_size=sizeof(result);
  const ark_result status=ark_postprocess_depth_tensor(&input,&config,
    reinterpret_cast<float*>(depth_base),reinterpret_cast<float*>(confidence_base),
    static_cast<size_t>(output_capacity_elements),&result);
  const jlong values[3]={result.width,result.height,static_cast<jlong>(result.required_elements)};
  env->SetLongArrayRegion(metadata,0,3,values);
  return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelDetectionPostprocessor_nativeDecode(JNIEnv* env,jobject,
    jobject tensor,jint tensor_offset,jint tensor_size,jint rows,jint columns,jint row_stride,
    jint element_type,jdouble quantization_scale,jint quantization_zero_point,
    jint image_width,jint image_height,jint x0_column,jint y0_column,jint x1_column,
    jint y1_column,jint score_column,jint class_column,jint box_format,
    jboolean normalized,jboolean class_aware_nms,jdouble score_threshold,jdouble iou_threshold,
    jint max_detections,jlongArray class_ids,jdoubleArray values,jlongArray metadata) {
  if(!tensor||!class_ids||!values||!metadata||tensor_offset<0||tensor_size<0||
     max_detections<1||max_detections>256||env->GetArrayLength(class_ids)<max_detections||
     env->GetArrayLength(values)<max_detections*5||env->GetArrayLength(metadata)<1)
    return ARK_RESULT_INVALID_ARGUMENT;
  auto* base=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(tensor));
  const jlong capacity=env->GetDirectBufferCapacity(tensor);
  if(!base||capacity<0||static_cast<jlong>(tensor_offset)+tensor_size>capacity)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_detection_tensor_v1 input{};input.struct_size=sizeof(input);input.rows=rows;
  input.columns=columns;input.row_stride_elements=row_stride;
  input.element_type=static_cast<uint32_t>(element_type);input.data=base+tensor_offset;
  input.data_size_bytes=static_cast<size_t>(tensor_size);
  input.quantization_scale=quantization_scale;input.quantization_zero_point=quantization_zero_point;
  ark_detection_postprocess_config_v1 config{};config.struct_size=sizeof(config);
  config.image_width=image_width;config.image_height=image_height;config.x0_column=x0_column;
  config.y0_column=y0_column;config.x1_or_width_column=x1_column;
  config.y1_or_height_column=y1_column;config.score_column=score_column;
  config.class_column=class_column;config.box_format=static_cast<uint32_t>(box_format);
  config.normalized_coordinates=normalized?1u:0u;config.class_aware_nms=class_aware_nms?1u:0u;
  config.score_threshold=score_threshold;config.iou_threshold=iou_threshold;
  config.max_candidates=4096;config.max_detections=static_cast<size_t>(max_detections);
  std::array<uint32_t,256> native_ids{};std::array<double,256*5> native_values{};
  ark_detection_postprocess_output_v1 result{};result.struct_size=sizeof(result);
  const ark_result status=ark_postprocess_detection_tensor(&input,&config,native_ids.data(),
    native_values.data(),static_cast<size_t>(max_detections),&result);
  if(status!=ARK_RESULT_OK)return status;
  std::array<jlong,256> java_ids{};
  for(size_t i=0;i<result.detection_count;++i)java_ids[i]=native_ids[i];
  env->SetLongArrayRegion(class_ids,0,static_cast<jsize>(result.detection_count),java_ids.data());
  env->SetDoubleArrayRegion(values,0,static_cast<jsize>(result.detection_count*5),
    native_values.data());
  const jlong count=static_cast<jlong>(result.detection_count);
  env->SetLongArrayRegion(metadata,0,1,&count);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:ARK_RESULT_OK;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_TemporalMetricDepthFilter_nativeCreate(JNIEnv*,jobject,
    jdouble min_depth,jdouble max_depth,jdouble relative_gate,jdouble absolute_gate,
    jdouble history_weight,jlong max_gap,jlong max_pixels) {
  if(max_pixels<=0||static_cast<unsigned long long>(max_pixels)>
     static_cast<unsigned long long>(std::numeric_limits<size_t>::max()))return 0;
  try{arkernel::TemporalDepthFilterConfig config;config.min_depth_m=min_depth;
    config.max_depth_m=max_depth;config.max_relative_difference=relative_gate;
    config.max_absolute_difference_m=absolute_gate;config.history_weight=history_weight;
    config.max_frame_gap_ns=max_gap;config.max_pixels=static_cast<size_t>(max_pixels);
    return toHandle(new arkernel::TemporalDepthFilter(config));}catch(...){return 0;}
}
extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_TemporalMetricDepthFilter_nativeDestroy(JNIEnv*,jobject,jlong handle){
  delete reinterpret_cast<arkernel::TemporalDepthFilter*>(static_cast<intptr_t>(handle));
}
extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_TemporalMetricDepthFilter_nativeReset(JNIEnv*,jobject,jlong handle){
  auto* filter=reinterpret_cast<arkernel::TemporalDepthFilter*>(static_cast<intptr_t>(handle));
  if(filter)filter->reset();
}
extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_TemporalMetricDepthFilter_nativeUpdate(JNIEnv* env,jobject,jlong handle,
    jobject depth,jint depth_offset,jint depth_remaining,jobject confidence,jint confidence_offset,
    jint confidence_remaining,jint width,jint height,jint depth_stride,jint confidence_stride,
    jlong timestamp,jdouble fx,jdouble fy,jdouble cx,jdouble cy,jdouble k1,jdouble k2,
    jdouble p1,jdouble p2,jdouble k3,jdouble rw,jdouble rx,jdouble ry,jdouble rz,
    jdouble tx,jdouble ty,jdouble tz,jobject output_depth,jobject output_confidence,
    jlongArray metadata){
  auto* filter=reinterpret_cast<arkernel::TemporalDepthFilter*>(static_cast<intptr_t>(handle));
  auto* depth_data=static_cast<float*>(env->GetDirectBufferAddress(depth));
  auto* output_data=static_cast<float*>(env->GetDirectBufferAddress(output_depth));
  auto* output_conf=static_cast<float*>(env->GetDirectBufferAddress(output_confidence));
  auto* confidence_data=confidence?static_cast<float*>(env->GetDirectBufferAddress(confidence)):nullptr;
  if(!filter||!depth_data||!output_data||!output_conf||!metadata||depth_offset<0||
     depth_remaining<0||confidence_offset<0||confidence_remaining<0||
     env->GetArrayLength(metadata)<3)return ARK_RESULT_INVALID_ARGUMENT;
  const jlong depth_capacity=env->GetDirectBufferCapacity(depth);
  const jlong output_capacity=env->GetDirectBufferCapacity(output_depth);
  const jlong output_conf_capacity=env->GetDirectBufferCapacity(output_confidence);
  const jlong confidence_capacity=confidence?env->GetDirectBufferCapacity(confidence):0;
  const jlong pixels=static_cast<jlong>(width)*height;
  if(depth_capacity<0||output_capacity<pixels||output_conf_capacity<pixels||
     static_cast<jlong>(depth_offset)+depth_remaining>depth_capacity||
     (confidence&&(!confidence_data||confidence_capacity<0||
      static_cast<jlong>(confidence_offset)+confidence_remaining>confidence_capacity)))
    return ARK_RESULT_INVALID_ARGUMENT;
  try{arkernel::TemporalDepthFrameView frame;frame.width=width;frame.height=height;
    frame.row_stride_elements=depth_stride;frame.depth_m=depth_data+depth_offset;
    frame.depth_elements=static_cast<size_t>(depth_remaining);frame.confidence=confidence_data?
      confidence_data+confidence_offset:nullptr;frame.confidence_row_stride_elements=confidence_stride;
    frame.confidence_elements=static_cast<size_t>(confidence_remaining);frame.timestamp_ns=timestamp;
    frame.world_from_camera.rotation={rw,rx,ry,rz};frame.world_from_camera.translation={tx,ty,tz};
    const arkernel::PinholeRadTanCamera camera({width,height,fx,fy,cx,cy},{k1,k2,p1,p2,k3});
    const auto result=filter->update(frame,camera);
    std::memcpy(output_data,result.depth_m.data(),result.depth_m.size()*sizeof(float));
    std::memcpy(output_conf,result.confidence.data(),result.confidence.size()*sizeof(float));
    const jlong values[3]={static_cast<jlong>(result.blended_pixels),
      static_cast<jlong>(result.rejected_history_pixels),result.history_used?1:0};
    env->SetLongArrayRegion(metadata,0,3,values);return ARK_RESULT_OK;
  }catch(const std::invalid_argument&){return ARK_RESULT_INVALID_ARGUMENT;}
  catch(const std::length_error&){return ARK_RESULT_INVALID_ARGUMENT;}
  catch(...){return ARK_RESULT_INTERNAL_ERROR;}
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ReconstructionFusionPipeline_nativeCreate(
    JNIEnv* env,jobject,jlongArray integer_options,jdoubleArray scalar_options) {
  if(!integer_options||!scalar_options||env->GetArrayLength(integer_options)<13||
     env->GetArrayLength(scalar_options)<11)return 0;
  std::array<jlong,13> integers{};
  std::array<jdouble,11> scalars{};
  env->GetLongArrayRegion(integer_options,0,integers.size(),integers.data());
  env->GetDoubleArrayRegion(scalar_options,0,scalars.size(),scalars.data());
  if(env->ExceptionCheck())return 0;
  const auto bounded_size=[](jlong value,std::size_t maximum)->std::optional<std::size_t>{
    if(value<=0||static_cast<unsigned long long>(value)>
        static_cast<unsigned long long>(maximum))return std::nullopt;
    return static_cast<std::size_t>(value);
  };
  const auto pixel_stride=bounded_size(integers[0],64);
  const auto max_blocks=bounded_size(integers[1],1000000);
  const auto max_frames=bounded_size(integers[2],1000000);
  const auto max_depth_pixels=bounded_size(integers[3],4194304);
  const auto max_voxel_visits=bounded_size(integers[4],16777216);
  const auto max_snapshot_voxels=bounded_size(integers[5],5000000);
  const auto max_cells=bounded_size(integers[6],5000000);
  const auto max_vertices=bounded_size(integers[7],5000000);
  const auto max_triangles=bounded_size(integers[8],5000000);
  const auto min_component_triangles=bounded_size(integers[9],5000000);
  const auto max_edges=bounded_size(integers[10],6000000);
  const auto max_clusters=bounded_size(integers[11],5000000);
  const auto max_glb_bytes=bounded_size(integers[12],512ULL*1024ULL*1024ULL);
  if(!pixel_stride||!max_blocks||!max_frames||!max_depth_pixels||!max_voxel_visits||
     !max_snapshot_voxels||!max_cells||!max_vertices||!max_triangles||
     !min_component_triangles||!max_edges||!max_clusters||!max_glb_bytes||
     *max_glb_bytes<1024)return 0;
  try{
    arkernel::ReconstructionPipelineConfig config;
    config.fusion.voxel_size_m=scalars[0];
    config.fusion.truncation_distance_m=scalars[1];
    config.fusion.min_depth_m=scalars[2];config.fusion.max_depth_m=scalars[3];
    config.fusion.min_confidence=scalars[4];
    config.fusion.default_uncertainty_m=scalars[5];
    config.fusion.pixel_stride=static_cast<int>(*pixel_stride);
    config.fusion.max_blocks=*max_blocks;
    config.fusion.max_depth_pixels_per_update=*max_depth_pixels;
    config.fusion.max_voxel_visits_per_update=*max_voxel_visits;
    config.fusion.max_snapshot_voxels=*max_snapshot_voxels;
    config.max_integrated_frames=*max_frames;
    config.meshing.max_input_voxels=*max_snapshot_voxels;
    config.meshing.max_cells=*max_cells;config.meshing.max_vertices=*max_vertices;
    config.meshing.max_triangles=*max_triangles;
    config.component_filter.min_component_triangles=*min_component_triangles;
    config.component_filter.min_component_area_m2=scalars[6];
    config.component_filter.max_input_vertices=*max_vertices;
    config.component_filter.max_input_triangles=*max_triangles;
    config.component_filter.max_unique_edges=*max_edges;
    config.simplification.cluster_cell_size_m=scalars[7];
    config.simplification.max_position_error_m=scalars[8];
    config.simplification.sharp_edge_angle_degrees=scalars[9];
    config.simplification.max_uv_error=scalars[10];
    config.simplification.max_input_vertices=*max_vertices;
    config.simplification.max_input_triangles=*max_triangles;
    config.simplification.max_unique_edges=*max_edges;
    config.simplification.max_clusters=*max_clusters;
    return toHandle(new AndroidReconstructionPipeline(std::move(config),*max_glb_bytes));
  }catch(...){return 0;}
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ReconstructionFusionPipeline_nativeIntegrate(
    JNIEnv* env,jobject,jlong handle,jlong timestamp,jobject depth,jint depth_offset,
    jint depth_remaining,jobject confidence,jint confidence_offset,jint confidence_remaining,
    jobject uncertainty,jint uncertainty_offset,jint uncertainty_remaining,
    jint width,jint height,jint depth_stride,jint confidence_stride,jint uncertainty_stride,
    jdouble fx,jdouble fy,
    jdouble cx,jdouble cy,jdouble k1,jdouble k2,jdouble p1,jdouble p2,jdouble k3,
    jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble tx,jdouble ty,jdouble tz,
    jlongArray metadata) {
  auto* owner=fromFusionPipelineHandle(handle);
  auto* depth_data=depth?static_cast<float*>(env->GetDirectBufferAddress(depth)):nullptr;
  auto* confidence_data=confidence?
      static_cast<float*>(env->GetDirectBufferAddress(confidence)):nullptr;
  auto* uncertainty_data=uncertainty?
      static_cast<float*>(env->GetDirectBufferAddress(uncertainty)):nullptr;
  if(!owner||!depth_data||!metadata||env->GetArrayLength(metadata)<14||width<=0||height<=0||
     depth_offset<0||depth_remaining<0||confidence_offset<0||confidence_remaining<0||
     uncertainty_offset<0||uncertainty_remaining<0)return ARK_RESULT_INVALID_ARGUMENT;
  const jlong depth_capacity=env->GetDirectBufferCapacity(depth);
  const jlong confidence_capacity=confidence?env->GetDirectBufferCapacity(confidence):0;
  const jlong uncertainty_capacity=uncertainty?env->GetDirectBufferCapacity(uncertainty):0;
  if(depth_capacity<0||static_cast<jlong>(depth_offset)+depth_remaining>depth_capacity||
     (confidence&&(!confidence_data||confidence_capacity<0||
      static_cast<jlong>(confidence_offset)+confidence_remaining>confidence_capacity))||
     (uncertainty&&(!uncertainty_data||uncertainty_capacity<0||
      static_cast<jlong>(uncertainty_offset)+uncertainty_remaining>uncertainty_capacity)))
    return ARK_RESULT_INVALID_ARGUMENT;
  try{
    arkernel::TsdfDepthImageView view;view.width=width;view.height=height;
    view.depth_row_stride_elements=depth_stride;view.depth_z_m=depth_data+depth_offset;
    view.depth_element_count=static_cast<std::size_t>(depth_remaining);
    view.confidence=confidence_data?confidence_data+confidence_offset:nullptr;
    view.confidence_row_stride_elements=confidence_stride;
    view.confidence_element_count=static_cast<std::size_t>(confidence_remaining);
    view.uncertainty_m=uncertainty_data?uncertainty_data+uncertainty_offset:nullptr;
    view.uncertainty_row_stride_elements=uncertainty_stride;
    view.uncertainty_element_count=static_cast<std::size_t>(uncertainty_remaining);
    const arkernel::PinholeRadTanCamera camera({width,height,fx,fy,cx,cy},{k1,k2,p1,p2,k3});
    arkernel::Pose pose;pose.rotation={rw,rx,ry,rz};pose.translation={tx,ty,tz};
    const auto result=owner->pipeline.integrate(timestamp,view,camera,pose);
    const jlong values[14]={result.accepted?1:0,static_cast<jlong>(result.rejection),
      static_cast<jlong>(result.accepted_frames),static_cast<jlong>(result.rejected_frames),
      static_cast<jlong>(result.cumulative_integrated_pixels),
      static_cast<jlong>(result.cumulative_voxel_updates),
      static_cast<jlong>(result.update.visited_pixels),
      static_cast<jlong>(result.update.integrated_pixels),
      static_cast<jlong>(result.update.rejected_pixels),
      static_cast<jlong>(result.update.voxel_visits),
      static_cast<jlong>(result.update.voxel_updates),
      static_cast<jlong>(result.update.allocated_blocks),
      static_cast<jlong>(result.update.capacity_rejections),
      result.fusion_truncated?1:0};
    env->SetLongArrayRegion(metadata,0,14,values);
    return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:ARK_RESULT_OK;
  }catch(const std::invalid_argument&){return ARK_RESULT_INVALID_ARGUMENT;}
  catch(const std::length_error&){return ARK_RESULT_INVALID_ARGUMENT;}
  catch(...){return ARK_RESULT_INTERNAL_ERROR;}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ai_arena_arkernel_ReconstructionFusionPipeline_nativeBuild(
    JNIEnv* env,jobject,jlong handle,jlongArray metadata,jdoubleArray scalar_values) {
  auto* owner=fromFusionPipelineHandle(handle);
  if(!owner||!metadata||!scalar_values||env->GetArrayLength(metadata)<19||
     env->GetArrayLength(scalar_values)<1)return nullptr;
  try{
    const auto result=owner->pipeline.build();
    if(!result.error.empty()||!meshFitsGlbBudget(result.source_mesh,owner->max_glb_bytes)||
       !meshFitsGlbBudget(result.simplified_mesh,owner->max_glb_bytes))return nullptr;
    const auto& t=result.telemetry;
    const jlong values[19]={t.complete?1:0,t.fusion_truncated?1:0,
      t.meshing_truncated?1:0,t.filtering_truncated?1:0,t.simplification_truncated?1:0,
      static_cast<jlong>(t.fused_frames),static_cast<jlong>(t.fused_blocks),
      static_cast<jlong>(t.fused_voxels),static_cast<jlong>(t.meshing_input_voxels),
      static_cast<jlong>(t.meshing_examined_cells),static_cast<jlong>(t.meshing_complete_cells),
      static_cast<jlong>(t.meshing_surface_cells),
      static_cast<jlong>(t.meshing_skipped_incomplete_cells),
      static_cast<jlong>(t.source_components),static_cast<jlong>(t.retained_components),
      static_cast<jlong>(t.source_vertices),static_cast<jlong>(t.source_triangles),
      static_cast<jlong>(t.simplified_vertices),static_cast<jlong>(t.simplified_triangles)};
    const jdouble displacement=t.max_simplification_displacement_m;
    env->SetLongArrayRegion(metadata,0,19,values);
    env->SetDoubleArrayRegion(scalar_values,0,1,&displacement);
    jclass byte_array_class=env->FindClass("[B");
    if(!byte_array_class||env->ExceptionCheck())return nullptr;
    jobjectArray output=env->NewObjectArray(2,byte_array_class,nullptr);
    env->DeleteLocalRef(byte_array_class);
    if(!output)return nullptr;
    const auto append_mesh=[&](const arkernel::ReconstructionMesh& mesh,jsize index){
      const auto glb=arkernel::exportGlb(mesh);
      if(glb.size()>owner->max_glb_bytes||
         glb.size()>static_cast<std::size_t>(std::numeric_limits<jsize>::max()))return false;
      jbyteArray bytes=env->NewByteArray(static_cast<jsize>(glb.size()));
      if(!bytes)return false;
      env->SetByteArrayRegion(bytes,0,static_cast<jsize>(glb.size()),
                              reinterpret_cast<const jbyte*>(glb.data()));
      env->SetObjectArrayElement(output,index,bytes);
      env->DeleteLocalRef(bytes);
      return !env->ExceptionCheck();
    };
    // Export/copy one mesh at a time so two large native GLB vectors never
    // coexist in addition to the two bounded Java byte arrays.
    if(!append_mesh(result.source_mesh,0)||!append_mesh(result.simplified_mesh,1)){
      env->DeleteLocalRef(output);return nullptr;
    }
    return output;
  }catch(...){return nullptr;}
}

extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_ReconstructionFusionPipeline_nativeReset(
    JNIEnv*,jobject,jlong handle) {
  auto* owner=fromFusionPipelineHandle(handle);if(owner)owner->pipeline.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_ReconstructionFusionPipeline_nativeDestroy(
    JNIEnv*,jobject,jlong handle) {delete fromFusionPipelineHandle(handle);}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_BoundedSemanticDetectionTracker_nativeCreate(JNIEnv*,jobject,
    jdouble min_iou,jint max_tracks,jint max_missed,jlong max_gap){
  try{arkernel::DetectionTrackerConfig config;config.min_iou=min_iou;
    config.max_tracks=static_cast<size_t>(max_tracks);
    config.max_missed_frames=static_cast<size_t>(max_missed);config.max_frame_gap_ns=max_gap;
    return toHandle(new arkernel::DetectionTracker(config));}catch(...){return 0;}
}
extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_BoundedSemanticDetectionTracker_nativeDestroy(JNIEnv*,jobject,jlong handle){
  delete reinterpret_cast<arkernel::DetectionTracker*>(static_cast<intptr_t>(handle));
}
extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_BoundedSemanticDetectionTracker_nativeReset(JNIEnv*,jobject,jlong handle){
  auto* tracker=reinterpret_cast<arkernel::DetectionTracker*>(static_cast<intptr_t>(handle));
  if(tracker)tracker->reset();
}
extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_BoundedSemanticDetectionTracker_nativeUpdate(JNIEnv* env,jobject,
    jlong handle,jlong timestamp,jlongArray class_ids,jdoubleArray values,jlongArray track_ids){
  auto* tracker=reinterpret_cast<arkernel::DetectionTracker*>(static_cast<intptr_t>(handle));
  if(!tracker||!class_ids||!values||!track_ids)return ARK_RESULT_INVALID_ARGUMENT;
  const jsize count=env->GetArrayLength(class_ids);
  if(count<0||count>256||env->GetArrayLength(values)<count*5||
     env->GetArrayLength(track_ids)<count)return ARK_RESULT_INVALID_ARGUMENT;
  std::array<jlong,256> ids{};std::array<jdouble,256*5> data{};
  env->GetLongArrayRegion(class_ids,0,count,ids.data());
  env->GetDoubleArrayRegion(values,0,count*5,data.data());
  if(env->ExceptionCheck())return ARK_RESULT_INTERNAL_ERROR;
  std::vector<arkernel::DecodedDetection> detections;detections.reserve(count);
  for(jsize i=0;i<count;++i){
    if(ids[i]<0||static_cast<unsigned long long>(ids[i])>
       std::numeric_limits<uint32_t>::max())return ARK_RESULT_INVALID_ARGUMENT;
    const auto offset=static_cast<size_t>(i)*5;
    detections.push_back({static_cast<uint32_t>(ids[i]),data[offset],data[offset+1],
      data[offset+2],data[offset+3],data[offset+4],static_cast<size_t>(i)});
  }
  try{const auto tracked=tracker->update(timestamp,detections);
    std::array<jlong,256> output{};
    for(size_t i=0;i<tracked.size();++i)output[i]=static_cast<jlong>(tracked[i].track_id);
    env->SetLongArrayRegion(track_ids,0,count,output.data());
    return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:ARK_RESULT_OK;
  }catch(const std::invalid_argument&){return ARK_RESULT_INVALID_ARGUMENT;}
  catch(...){return ARK_RESULT_INTERNAL_ERROR;}
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelDimensionalValidator_nativeEvaluate(
    JNIEnv* env,jobject,jobjectArray evidence_ids,jdoubleArray observation_values,
    jlongArray observation_metadata,jlongArray policy_counts,jdoubleArray policy_values,
    jlongArray output_metadata,jdoubleArray output_values) {
  if(!evidence_ids||!observation_values||!observation_metadata||!policy_counts||
     !policy_values||!output_metadata||!output_values)return ARK_RESULT_INVALID_ARGUMENT;
  const jsize count=env->GetArrayLength(evidence_ids);
  if(count<0||count>64||env->GetArrayLength(observation_values)!=count*8||
     env->GetArrayLength(observation_metadata)!=count*2||
     env->GetArrayLength(policy_counts)<3||env->GetArrayLength(policy_values)<5||
     env->GetArrayLength(output_metadata)<4||env->GetArrayLength(output_values)<4)
    return ARK_RESULT_INVALID_ARGUMENT;
  std::array<jdouble,64*8> values{};std::array<jlong,64*2> metadata{};
  jlong counts[3]{};jdouble policy_data[5]{};
  env->GetDoubleArrayRegion(observation_values,0,count*8,values.data());
  env->GetLongArrayRegion(observation_metadata,0,count*2,metadata.data());
  env->GetLongArrayRegion(policy_counts,0,3,counts);
  env->GetDoubleArrayRegion(policy_values,0,5,policy_data);
  if(env->ExceptionCheck()||counts[0]<=0||counts[0]>64||counts[1]<=0||
     counts[1]>counts[0]||counts[2]<=0||counts[2]>counts[1])
    return ARK_RESULT_INVALID_ARGUMENT;
  std::vector<std::string> ids;ids.reserve(static_cast<size_t>(count));
  for(jsize i=0;i<count;++i){
    auto* java_id=static_cast<jstring>(env->GetObjectArrayElement(evidence_ids,i));
    if(!java_id)return ARK_RESULT_INVALID_ARGUMENT;
    const jsize length=env->GetStringUTFLength(java_id);
    const char* chars=env->GetStringUTFChars(java_id,nullptr);
    if(!chars){env->DeleteLocalRef(java_id);return ARK_RESULT_INTERNAL_ERROR;}
    if(length<=0||length>128){
      env->ReleaseStringUTFChars(java_id,chars);env->DeleteLocalRef(java_id);
      return ARK_RESULT_INVALID_ARGUMENT;
    }
    ids.emplace_back(chars,static_cast<size_t>(length));
    env->ReleaseStringUTFChars(java_id,chars);env->DeleteLocalRef(java_id);
  }
  std::vector<ark_held_out_dimension_v1> observations(static_cast<size_t>(count));
  for(jsize i=0;i<count;++i){
    auto& output=observations[static_cast<size_t>(i)];const size_t offset=static_cast<size_t>(i)*8;
    output.struct_size=sizeof(output);output.evidence_id=ids[static_cast<size_t>(i)].data();
    output.evidence_id_length=ids[static_cast<size_t>(i)].size();
    output.reconstructed_a_x_m=values[offset];output.reconstructed_a_y_m=values[offset+1];
    output.reconstructed_a_z_m=values[offset+2];output.reconstructed_b_x_m=values[offset+3];
    output.reconstructed_b_y_m=values[offset+4];output.reconstructed_b_z_m=values[offset+5];
    output.reference_distance_m=values[offset+6];output.reference_uncertainty_m=values[offset+7];
    output.reference_kind=static_cast<uint32_t>(metadata[static_cast<size_t>(i)*2]);
    output.held_out_from_calibration_and_optimization=
      metadata[static_cast<size_t>(i)*2+1]!=0?1u:0u;
  }
  ark_dimensional_validation_policy_v1 policy{};policy.struct_size=sizeof(policy);
  policy.max_observations=static_cast<size_t>(counts[0]);
  policy.min_observations=static_cast<size_t>(counts[1]);
  policy.min_dimension_bins=static_cast<size_t>(counts[2]);
  policy.dimension_bin_width_m=policy_data[0];
  policy.max_reference_uncertainty_m=policy_data[1];
  policy.max_mean_absolute_error_m=policy_data[2];
  policy.max_absolute_error_m=policy_data[3];policy.max_relative_error=policy_data[4];
  ark_dimensional_validation_report_v1 report{};report.struct_size=sizeof(report);
  const auto status=ark_evaluate_held_out_dimensions(observations.data(),
    observations.size(),&policy,&report);
  if(status!=ARK_RESULT_OK)return status;
  const jlong report_metadata[4]={report.passed?1L:0L,
    static_cast<jlong>(report.failure_flags),static_cast<jlong>(report.accepted_observations),
    static_cast<jlong>(report.dimension_bins)};
  const jdouble report_values[4]={report.mean_absolute_error_m,
    report.maximum_effective_absolute_error_m,report.maximum_effective_relative_error,
    report.maximum_validated_dimension_m};
  env->SetLongArrayRegion(output_metadata,0,4,report_metadata);
  env->SetDoubleArrayRegion(output_values,0,4,report_values);
  return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:ARK_RESULT_OK;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_arena_arkernel_ArKernelReconstructionPlanner_nativeCreate(
    JNIEnv* env,jobject,jboolean object_mode,jlongArray integer_config,jdoubleArray scalar_config) {
  if(!integer_config||!scalar_config||env->GetArrayLength(integer_config)<5||
     env->GetArrayLength(scalar_config)<11)return 0;
  jlong integers[5]{};jdouble scalars[11]{};
  env->GetLongArrayRegion(integer_config,0,5,integers);
  env->GetDoubleArrayRegion(scalar_config,0,11,scalars);
  if(env->ExceptionCheck()||integers[0]<=0||integers[0]>100000||integers[1]<0||
     integers[2]<=0||integers[2]>1000000||integers[3]<=0||integers[4]<=0)return 0;
  try {
    arkernel::CaptureQualityConfig config;
    config.max_keyframes=static_cast<size_t>(integers[0]);
    config.min_interval_ns=integers[1];
    config.min_tracked_features=static_cast<size_t>(integers[2]);
    config.object_azimuth_bins=static_cast<size_t>(integers[3]);
    config.object_elevation_bins=static_cast<size_t>(integers[4]);
    config.min_translation_m=scalars[0];config.min_rotation_degrees=scalars[1];
    config.min_sharpness=scalars[2];config.max_clipped_pixel_ratio=scalars[3];
    config.max_translation_sigma_m=scalars[4];config.max_rotation_sigma_degrees=scalars[5];
    config.min_object_foreground_ratio=scalars[6];config.max_object_foreground_ratio=scalars[7];
    config.min_object_distance_m=scalars[8];config.max_object_distance_m=scalars[9];
    config.max_object_center_shift_m=scalars[10];
    auto* planner=new arkernel::ReconstructionCapturePlanner(
      object_mode?arkernel::ScanMode::kObject:arkernel::ScanMode::kEnvironment,config);
    return toHandle(planner);
  }catch(...){return 0;}
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelReconstructionPlanner_nativeDecide(
    JNIEnv* env,jobject,jlong handle,jboolean reserve,jlong frame_id,jlong timestamp,
    jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble px,jdouble py,jdouble pz,
    jboolean tracking,jlong tracked_features,jdouble sharpness,jdouble clipped_ratio,
    jdouble translation_sigma,jdouble rotation_sigma,jdouble foreground_ratio,
    jboolean has_object_center,jdouble center_x,jdouble center_y,jdouble center_z,
    jlongArray metadata,jdoubleArray values) {
  auto* planner=fromReconstructionPlannerHandle(handle);
  if(!planner||!metadata||!values||frame_id<=0||tracked_features<0||
     env->GetArrayLength(metadata)<7||env->GetArrayLength(values)<2)
    return ARK_RESULT_INVALID_ARGUMENT;
  try {
    arkernel::ReconstructionFrameCandidate candidate;
    candidate.frame_id=static_cast<uint64_t>(frame_id);candidate.timestamp_ns=timestamp;
    candidate.world_from_camera.rotation={rw,rx,ry,rz};
    candidate.world_from_camera.translation={px,py,pz};
    candidate.tracking_state=tracking?arkernel::TrackingState::kTracking:
      arkernel::TrackingState::kLimited;
    candidate.tracked_features=static_cast<size_t>(tracked_features);
    candidate.sharpness=sharpness;candidate.clipped_pixel_ratio=clipped_ratio;
    candidate.translation_sigma_m=translation_sigma;
    candidate.rotation_sigma_degrees=rotation_sigma;
    candidate.object_foreground_ratio=foreground_ratio;
    if(has_object_center)candidate.object_center_world_m=arkernel::Vec3{center_x,center_y,center_z};
    candidate.image_reference="precopy://"+std::to_string(frame_id);
    const auto decision=reserve?planner->reserve(candidate):planner->consider(candidate);
    const auto coverage=planner->objectCoverage();
    const jlong output[7]={decision.accepted?1L:0L,
      static_cast<jlong>(decision.rejection),decision.object_view_bin?1L:0L,
      decision.object_view_bin?static_cast<jlong>(*decision.object_view_bin):0L,
      static_cast<jlong>(coverage.covered_bins),static_cast<jlong>(coverage.total_bins),
      static_cast<jlong>(decision.reservation_token)};
    const jdouble scalar_output[2]={decision.quality_score,coverage.ratio};
    env->SetLongArrayRegion(metadata,0,7,output);
    env->SetDoubleArrayRegion(values,0,2,scalar_output);
    return env->ExceptionCheck()?ARK_RESULT_INTERNAL_ERROR:ARK_RESULT_OK;
  }catch(...){return ARK_RESULT_INTERNAL_ERROR;}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_arena_arkernel_ArKernelReconstructionPlanner_nativeCommit(
    JNIEnv*,jobject,jlong handle,jlong token) {
  auto* planner=fromReconstructionPlannerHandle(handle);
  return planner&&token>0&&planner->commit(static_cast<uint64_t>(token))?JNI_TRUE:JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_arena_arkernel_ArKernelReconstructionPlanner_nativeCancel(
    JNIEnv*,jobject,jlong handle,jlong token) {
  auto* planner=fromReconstructionPlannerHandle(handle);
  return planner&&token>0&&planner->cancel(static_cast<uint64_t>(token))?JNI_TRUE:JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelReconstructionPlanner_nativeReset(
    JNIEnv*,jobject,jlong handle) {
  auto* planner=fromReconstructionPlannerHandle(handle);
  if(!planner)return ARK_RESULT_INVALID_ARGUMENT;
  planner->reset();return ARK_RESULT_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_arena_arkernel_ArKernelReconstructionPlanner_nativeDestroy(
    JNIEnv*,jobject,jlong handle) {
  delete fromReconstructionPlannerHandle(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_arena_arkernel_ReconstructionTexturedAssetPublisher_nativeExportEmbeddedGlb(
    JNIEnv* env,jobject,jobject positions,jint positions_offset,jint positions_remaining,
    jobject normals,jint normals_offset,jint normals_remaining,
    jobject texcoords,jint texcoords_offset,jint texcoords_remaining,
    jobject indices,jint indices_offset,jint indices_remaining,jbyteArray png,
    jint max_vertices,jint max_triangles,jint max_glb_bytes) {
  if(!positions||!texcoords||!indices||!png||positions_offset<0||positions_remaining<=0||
     normals_offset<0||normals_remaining<0||texcoords_offset<0||texcoords_remaining<=0||
     indices_offset<0||indices_remaining<=0||max_vertices<=0||max_vertices>5000000||
     max_triangles<=0||max_triangles>10000000||max_glb_bytes<=0||
     positions_remaining%3!=0||texcoords_remaining%2!=0||indices_remaining%3!=0||
     positions_remaining/3>max_vertices||indices_remaining/3>max_triangles||
     texcoords_remaining/2!=positions_remaining/3||
     (normals&&normals_remaining!=positions_remaining)||(!normals&&normals_remaining!=0))return nullptr;
  auto* position_data=static_cast<float*>(env->GetDirectBufferAddress(positions));
  auto* normal_data=normals?static_cast<float*>(env->GetDirectBufferAddress(normals)):nullptr;
  auto* texcoord_data=static_cast<float*>(env->GetDirectBufferAddress(texcoords));
  auto* index_data=static_cast<std::int32_t*>(env->GetDirectBufferAddress(indices));
  const jlong position_capacity=env->GetDirectBufferCapacity(positions);
  const jlong normal_capacity=normals?env->GetDirectBufferCapacity(normals):0;
  const jlong texcoord_capacity=env->GetDirectBufferCapacity(texcoords);
  const jlong index_capacity=env->GetDirectBufferCapacity(indices);
  if(!position_data||!texcoord_data||!index_data||position_capacity<0||texcoord_capacity<0||
     index_capacity<0||static_cast<jlong>(positions_offset)+positions_remaining>position_capacity||
     static_cast<jlong>(texcoords_offset)+texcoords_remaining>texcoord_capacity||
     static_cast<jlong>(indices_offset)+indices_remaining>index_capacity||
     (normals&&(!normal_data||normal_capacity<0||
      static_cast<jlong>(normals_offset)+normals_remaining>normal_capacity)))return nullptr;
  const jsize png_size=env->GetArrayLength(png);
  if(png_size<=0||png_size>max_glb_bytes)return nullptr;
  try{
    arkernel::ReconstructionMesh mesh;mesh.name="AR textured reconstruction";
    const std::size_t vertex_count=static_cast<std::size_t>(positions_remaining/3);
    mesh.positions_m.reserve(vertex_count);mesh.texcoords.reserve(vertex_count);
    if(normal_data)mesh.normals.reserve(vertex_count);
    for(std::size_t vertex=0;vertex<vertex_count;++vertex) {
      const std::size_t p=static_cast<std::size_t>(positions_offset)+vertex*3;
      const std::size_t uv=static_cast<std::size_t>(texcoords_offset)+vertex*2;
      mesh.positions_m.push_back({position_data[p],position_data[p+1],position_data[p+2]});
      mesh.texcoords.push_back({texcoord_data[uv],texcoord_data[uv+1]});
      if(normal_data) {
        const std::size_t n=static_cast<std::size_t>(normals_offset)+vertex*3;
        mesh.normals.push_back({normal_data[n],normal_data[n+1],normal_data[n+2]});
      }
    }
    mesh.triangle_indices.reserve(static_cast<std::size_t>(indices_remaining));
    for(jint i=0;i<indices_remaining;++i) {
      const std::int32_t value=index_data[static_cast<std::size_t>(indices_offset)+i];
      if(value<0)return nullptr;
      mesh.triangle_indices.push_back(static_cast<std::uint32_t>(value));
    }
    const std::size_t budget=static_cast<std::size_t>(max_glb_bytes);
    if(static_cast<std::size_t>(png_size)>=budget||
       !meshFitsGlbBudget(mesh,budget-static_cast<std::size_t>(png_size)))return nullptr;
    std::vector<std::uint8_t> encoded(static_cast<std::size_t>(png_size));
    env->GetByteArrayRegion(png,0,png_size,reinterpret_cast<jbyte*>(encoded.data()));
    if(env->ExceptionCheck())return nullptr;
    const auto glb=arkernel::exportGlbWithEmbeddedTexture(mesh,encoded,"image/png",
                                                          static_cast<std::size_t>(png_size));
    if(glb.size()>budget||glb.size()>static_cast<std::size_t>(std::numeric_limits<jsize>::max()))
      return nullptr;
    jbyteArray output=env->NewByteArray(static_cast<jsize>(glb.size()));
    if(!output)return nullptr;
    env->SetByteArrayRegion(output,0,static_cast<jsize>(glb.size()),
                            reinterpret_cast<const jbyte*>(glb.data()));
    return env->ExceptionCheck()?nullptr:output;
  }catch(...){return nullptr;}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_arena_arkernel_ReconstructionTexturePipeline_nativeBuild(
    JNIEnv* env,jobject,jobject positions,jint positions_offset,jint positions_remaining,
    jobject normals,jint normals_offset,jint normals_remaining,jobject indices,
    jint indices_offset,jint indices_remaining,jlongArray frame_ids,jobjectArray references,
    jdoubleArray camera_data,jintArray formats,jintArray widths,jintArray heights,
    jintArray strides,jobjectArray images,jint max_vertices,jint max_triangles,
    jint max_result_bytes,jint atlas_width,jint atlas_height,jboolean enable_photometry,
    jboolean enable_seam_blending) {
  constexpr jsize kCameraValues=17;
  constexpr std::uint32_t kMagic=0x54584B41U;
  constexpr std::uint32_t kVersion=1U;
  if(!positions||!indices||!frame_ids||!references||!camera_data||!formats||!widths||
     !heights||!strides||!images||positions_offset<0||positions_remaining<=0||
     normals_offset<0||normals_remaining<0||indices_offset<0||indices_remaining<=0||
     positions_remaining%3||indices_remaining%3||max_vertices<=0||max_vertices>5000000||
     max_triangles<=0||max_triangles>10000000||max_result_bytes<=0||atlas_width<16||
     atlas_width>4096||atlas_height<16||atlas_height>4096||positions_remaining/3>max_vertices||indices_remaining/3>max_triangles||
     (normals&&normals_remaining!=positions_remaining)||(!normals&&normals_remaining))return nullptr;
  const jsize count=env->GetArrayLength(frame_ids);
  if(count<=0||count>4096||env->GetArrayLength(references)!=count||
     env->GetArrayLength(camera_data)!=count*kCameraValues||env->GetArrayLength(formats)!=count||
     env->GetArrayLength(widths)!=count||env->GetArrayLength(heights)!=count||
     env->GetArrayLength(strides)!=count||env->GetArrayLength(images)!=count)return nullptr;
  auto* position_data=static_cast<float*>(env->GetDirectBufferAddress(positions));
  auto* normal_data=normals?static_cast<float*>(env->GetDirectBufferAddress(normals)):nullptr;
  auto* index_data=static_cast<std::int32_t*>(env->GetDirectBufferAddress(indices));
  const jlong position_capacity=env->GetDirectBufferCapacity(positions);
  const jlong normal_capacity=normals?env->GetDirectBufferCapacity(normals):0;
  const jlong index_capacity=env->GetDirectBufferCapacity(indices);
  if(!position_data||!index_data||position_capacity<0||index_capacity<0||
     static_cast<jlong>(positions_offset)+positions_remaining>position_capacity||
     static_cast<jlong>(indices_offset)+indices_remaining>index_capacity||
     (normals&&(!normal_data||normal_capacity<0||
      static_cast<jlong>(normals_offset)+normals_remaining>normal_capacity)))return nullptr;
  try {
    std::vector<jlong> ids(static_cast<std::size_t>(count));
    std::vector<jdouble> cameras_raw(static_cast<std::size_t>(count*kCameraValues));
    std::vector<jint> format_raw(static_cast<std::size_t>(count));
    std::vector<jint> width_raw(static_cast<std::size_t>(count));
    std::vector<jint> height_raw(static_cast<std::size_t>(count));
    std::vector<jint> stride_raw(static_cast<std::size_t>(count));
    env->GetLongArrayRegion(frame_ids,0,count,ids.data());
    env->GetDoubleArrayRegion(camera_data,0,count*kCameraValues,cameras_raw.data());
    env->GetIntArrayRegion(formats,0,count,format_raw.data());
    env->GetIntArrayRegion(widths,0,count,width_raw.data());
    env->GetIntArrayRegion(heights,0,count,height_raw.data());
    env->GetIntArrayRegion(strides,0,count,stride_raw.data());
    if(env->ExceptionCheck())return nullptr;
    arkernel::ReconstructionMesh mesh;mesh.name="AR reconstruction texture source";
    const std::size_t vertex_count=static_cast<std::size_t>(positions_remaining/3);
    mesh.positions_m.reserve(vertex_count);if(normal_data)mesh.normals.reserve(vertex_count);
    for(std::size_t v=0;v<vertex_count;++v){
      const std::size_t p=static_cast<std::size_t>(positions_offset)+v*3;
      mesh.positions_m.push_back({position_data[p],position_data[p+1],position_data[p+2]});
      if(normal_data){const std::size_t n=static_cast<std::size_t>(normals_offset)+v*3;
        mesh.normals.push_back({normal_data[n],normal_data[n+1],normal_data[n+2]});}
    }
    mesh.triangle_indices.reserve(static_cast<std::size_t>(indices_remaining));
    for(jint i=0;i<indices_remaining;++i){
      const auto value=index_data[static_cast<std::size_t>(indices_offset)+i];
      if(value<0)return nullptr;
      mesh.triangle_indices.push_back(static_cast<std::uint32_t>(value));
    }
    std::vector<arkernel::PinholeRadTanCamera> cameras;cameras.reserve(static_cast<std::size_t>(count));
    std::vector<std::string> refs;refs.reserve(static_cast<std::size_t>(count));
    std::vector<std::vector<std::uint8_t>> image_storage;image_storage.reserve(static_cast<std::size_t>(count));
    for(jsize i=0;i<count;++i){
      if(ids[static_cast<std::size_t>(i)]<=0||width_raw[static_cast<std::size_t>(i)]<=0||
         height_raw[static_cast<std::size_t>(i)]<=0||stride_raw[static_cast<std::size_t>(i)]<=0||
         (i>0&&ids[static_cast<std::size_t>(i)]<=ids[static_cast<std::size_t>(i-1)]))return nullptr;
      auto reference=static_cast<jstring>(env->GetObjectArrayElement(references,i));
      auto image=static_cast<jbyteArray>(env->GetObjectArrayElement(images,i));
      if(!reference||!image)return nullptr;
      const char* chars=env->GetStringUTFChars(reference,nullptr);if(!chars)return nullptr;
      refs.emplace_back(chars);env->ReleaseStringUTFChars(reference,chars);env->DeleteLocalRef(reference);
      const jsize image_size=env->GetArrayLength(image);if(image_size<=0)return nullptr;
      image_storage.emplace_back(static_cast<std::size_t>(image_size));
      env->GetByteArrayRegion(image,0,image_size,reinterpret_cast<jbyte*>(image_storage.back().data()));
      env->DeleteLocalRef(image);if(env->ExceptionCheck())return nullptr;
      const std::size_t b=static_cast<std::size_t>(i*kCameraValues);
      cameras.emplace_back(arkernel::CameraIntrinsics{width_raw[static_cast<std::size_t>(i)],
        height_raw[static_cast<std::size_t>(i)],cameras_raw[b],cameras_raw[b+1],
        cameras_raw[b+2],cameras_raw[b+3]},arkernel::RadTanDistortion{cameras_raw[b+4],
        cameras_raw[b+5],cameras_raw[b+6],cameras_raw[b+7],cameras_raw[b+8]});
    }
    std::vector<arkernel::TextureViewCandidate> candidates;candidates.reserve(static_cast<std::size_t>(count));
    std::vector<arkernel::TextureSourceImageView> source_images;source_images.reserve(static_cast<std::size_t>(count));
    for(jsize i=0;i<count;++i){
      const std::size_t n=static_cast<std::size_t>(i),b=n*static_cast<std::size_t>(kCameraValues);
      arkernel::TexturePixelFormat format;
      if(format_raw[n]==1)format=arkernel::TexturePixelFormat::kGray8;
      else if(format_raw[n]==2)format=arkernel::TexturePixelFormat::kRgba8;
      else if(format_raw[n]==3)format=arkernel::TexturePixelFormat::kI420;
      else return nullptr;
      arkernel::TextureViewCandidate candidate;candidate.frame_id=static_cast<std::uint64_t>(ids[n]);
      candidate.image_reference=refs[n];candidate.camera=&cameras[n];
      candidate.world_from_camera.rotation={cameras_raw[b+9],cameras_raw[b+10],cameras_raw[b+11],cameras_raw[b+12]};
      candidate.world_from_camera.translation={cameras_raw[b+13],cameras_raw[b+14],cameras_raw[b+15]};
      candidate.quality_score=cameras_raw[b+16];candidates.push_back(std::move(candidate));
      source_images.push_back({static_cast<std::uint64_t>(ids[n]),width_raw[n],height_raw[n],
        stride_raw[n],format,image_storage[n].data(),image_storage[n].size()});
    }
    arkernel::TexturePipelineConfig config;config.view_selection.require_depth_visibility=false;
    config.view_selection.max_vertices=static_cast<std::size_t>(max_vertices);
    config.view_selection.max_triangles=static_cast<std::size_t>(max_triangles);
    config.view_selection.max_candidates=static_cast<std::size_t>(count);
    config.label_optimization.max_triangles=static_cast<std::size_t>(max_triangles);
    config.atlas_layout.atlas_width=atlas_width;config.atlas_layout.atlas_height=atlas_height;
    config.atlas_layout.max_input_vertices=static_cast<std::size_t>(max_vertices);
    config.atlas_layout.max_input_triangles=static_cast<std::size_t>(max_triangles);
    config.atlas_layout.max_output_vertices=static_cast<std::size_t>(max_vertices);
    config.enable_photometric_correction=enable_photometry==JNI_TRUE;
    config.enable_seam_blending=enable_seam_blending==JNI_TRUE;
    const auto result=arkernel::buildTextureAtlas(mesh,candidates,source_images,config);
    const bool complete=result.stage==arkernel::TexturePipelineStage::kComplete;
    if(complete&&(result.mesh.positions_m.size()>static_cast<std::size_t>(max_vertices)||
       result.mesh.triangle_indices.size()/3>static_cast<std::size_t>(max_triangles)))return nullptr;
    std::size_t bytes=11U*4U+17U*8U+result.error.size();
    if(complete){
      bytes+=result.mesh.positions_m.size()*20U+result.mesh.normals.size()*12U+
        result.mesh.triangle_indices.size()*4U+result.rgba8.size();
    }
    if(bytes>static_cast<std::size_t>(max_result_bytes)||
       bytes>static_cast<std::size_t>(std::numeric_limits<jsize>::max()))return nullptr;
    std::vector<std::uint8_t> out;out.reserve(bytes);
    const auto u32=[&out](std::uint32_t v){for(int i=0;i<4;++i)out.push_back(static_cast<std::uint8_t>(v>>(8*i)));};
    const auto u64=[&out](std::uint64_t v){for(int i=0;i<8;++i)out.push_back(static_cast<std::uint8_t>(v>>(8*i)));};
    const auto f32=[&out,&u32](float value){std::uint32_t bits=0;std::memcpy(&bits,&value,4);u32(bits);};
    u32(kMagic);u32(kVersion);u32(static_cast<std::uint32_t>(result.stage));
    u32((result.input_valid?1U:0U)|(result.truncated?2U:0U));
    u32(complete?static_cast<std::uint32_t>(result.atlas_width):0U);
    u32(complete?static_cast<std::uint32_t>(result.atlas_height):0U);
    u32(complete?static_cast<std::uint32_t>(result.mesh.positions_m.size()):0U);
    u32(complete?static_cast<std::uint32_t>(result.mesh.normals.size()):0U);
    u32(complete?static_cast<std::uint32_t>(result.mesh.triangle_indices.size()):0U);
    u32(static_cast<std::uint32_t>(result.error.size()));
    u32(complete?static_cast<std::uint32_t>(result.rgba8.size()):0U);
    const auto& t=result.telemetry;
    const std::uint64_t telemetry[17]={t.candidate_views,t.projection_tests,t.assigned_triangles,
      t.unassigned_triangles,t.label_iterations,t.seams_before_labeling,t.seams_after_labeling,
      t.atlas_patches,t.atlas_vertices,t.atlas_triangles,t.photometry_equations,
      t.photometry_constrained_views,t.rasterized_pixels,t.padding_pixels,t.raster_pixel_tests,
      t.blended_seams,t.blended_pixel_updates};
    for(const auto value:telemetry)u64(value);
    out.insert(out.end(),result.error.begin(),result.error.end());
    if(complete){
      for(const auto& p:result.mesh.positions_m){f32(p.x);f32(p.y);f32(p.z);}
      for(const auto& n:result.mesh.normals){f32(n.x);f32(n.y);f32(n.z);}
      for(const auto& uv:result.mesh.texcoords){f32(uv.x);f32(uv.y);}
      for(const auto index:result.mesh.triangle_indices)u32(index);
      out.insert(out.end(),result.rgba8.begin(),result.rgba8.end());
    }
    if(out.size()!=bytes)return nullptr;
    auto output=env->NewByteArray(static_cast<jsize>(out.size()));if(!output)return nullptr;
    env->SetByteArrayRegion(output,0,static_cast<jsize>(out.size()),reinterpret_cast<const jbyte*>(out.data()));
    return env->ExceptionCheck()?nullptr:output;
  } catch(...) {return nullptr;}
}
