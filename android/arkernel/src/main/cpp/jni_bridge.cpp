#include <jni.h>
#include <cstdint>
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
Java_ai_arena_arkernel_ArKernelVio_nativeCreate(JNIEnv*,jobject) {
  ark_vio_handle* handle=nullptr;
  return ark_vio_create(nullptr,&handle)==ARK_RESULT_OK?toHandle(handle):0;
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
