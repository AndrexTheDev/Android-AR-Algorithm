#include <jni.h>
#include <cstdint>
#include "arkernel/c_api.h"

namespace {
ark_vio_handle* fromHandle(jlong value) {
  return reinterpret_cast<ark_vio_handle*>(static_cast<intptr_t>(value));
}
jlong toHandle(ark_vio_handle* value) {
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
  ark_vio_destroy(fromHandle(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeReset(JNIEnv*,jobject,jlong handle) {
  return ark_vio_reset(fromHandle(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeSubmitImu(JNIEnv*,jobject,jlong handle,
    jlong timestamp,jdouble ax,jdouble ay,jdouble az,jdouble gx,jdouble gy,jdouble gz) {
  ark_imu_sample_v1 sample{};sample.struct_size=sizeof(sample);sample.timestamp_ns=timestamp;
  sample.acceleration_x_mps2=ax;sample.acceleration_y_mps2=ay;sample.acceleration_z_mps2=az;
  sample.angular_velocity_x_rps=gx;sample.angular_velocity_y_rps=gy;sample.angular_velocity_z_rps=gz;
  return ark_vio_submit_imu(fromHandle(handle),&sample);
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeFuseVisual(JNIEnv*,jobject,jlong handle,jlong timestamp,
    jdouble rw,jdouble rx,jdouble ry,jdouble rz,jdouble tx,jdouble ty,jdouble tz) {
  ark_relative_pose_v1 pose{};pose.struct_size=sizeof(pose);pose.timestamp_ns=timestamp;
  pose.rotation_w=rw;pose.rotation_x=rx;pose.rotation_y=ry;pose.rotation_z=rz;
  pose.translation_direction_x=tx;pose.translation_direction_y=ty;
  pose.translation_direction_z=tz;pose.valid=1;
  return ark_vio_fuse_visual(fromHandle(handle),&pose);
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_arena_arkernel_ArKernelVio_nativeGetState(JNIEnv* env,jobject,jlong handle,
                                                   jlongArray metadata,jdoubleArray values) {
  if(!metadata||!values||env->GetArrayLength(metadata)<3||env->GetArrayLength(values)<17)
    return ARK_RESULT_INVALID_ARGUMENT;
  ark_vio_state_v1 state{};state.struct_size=sizeof(state);
  const ark_result result=ark_vio_get_state(fromHandle(handle),&state);
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
