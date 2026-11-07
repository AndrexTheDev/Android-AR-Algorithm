#ifndef ARKERNEL_C_API_H
#define ARKERNEL_C_API_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ARKERNEL_C_API_VERSION 1u

typedef enum ark_result {
  ARK_RESULT_OK = 0,
  ARK_RESULT_INVALID_ARGUMENT = 1,
  ARK_RESULT_OUT_OF_ORDER = 2,
  ARK_RESULT_NOT_READY = 3,
  ARK_RESULT_INTERNAL_ERROR = 4
} ark_result;

typedef enum ark_vio_status {
  ARK_VIO_UNINITIALIZED = 0,
  ARK_VIO_INERTIAL = 1,
  ARK_VIO_VISUAL_INERTIAL = 2,
  ARK_VIO_LIMITED = 3
} ark_vio_status;

typedef struct ark_vio_handle ark_vio_handle;

typedef struct ark_vio_config_v1 {
  uint32_t struct_size;
  uint32_t api_version;
  size_t initialization_samples;
  double initialization_duration_s;
  double gravity_magnitude_mps2;
  double gravity_tolerance_mps2;
  double max_stationary_gyro_std_rps;
  double max_stationary_accel_std_mps2;
  double max_imu_gap_s;
  int64_t visual_sync_tolerance_ns;
  double visual_rotation_gain;
  double visual_position_gain;
  double min_scale_displacement_m;
  double min_scale_direction_cosine;
  double body_from_camera_w;
  double body_from_camera_x;
  double body_from_camera_y;
  double body_from_camera_z;
} ark_vio_config_v1;

typedef struct ark_imu_sample_v1 {
  uint32_t struct_size;
  int64_t timestamp_ns;
  double acceleration_x_mps2;
  double acceleration_y_mps2;
  double acceleration_z_mps2;
  double angular_velocity_x_rps;
  double angular_velocity_y_rps;
  double angular_velocity_z_rps;
} ark_imu_sample_v1;

typedef struct ark_relative_pose_v1 {
  uint32_t struct_size;
  int64_t timestamp_ns;
  double rotation_w;
  double rotation_x;
  double rotation_y;
  double rotation_z;
  double translation_direction_x;
  double translation_direction_y;
  double translation_direction_z;
  uint32_t valid;
} ark_relative_pose_v1;

typedef struct ark_vio_state_v1 {
  uint32_t struct_size;
  int64_t timestamp_ns;
  uint32_t status;
  double rotation_w;
  double rotation_x;
  double rotation_y;
  double rotation_z;
  double position_x_m;
  double position_y_m;
  double position_z_m;
  double velocity_x_mps;
  double velocity_y_mps;
  double velocity_z_mps;
  double accelerometer_bias_x_mps2;
  double accelerometer_bias_y_mps2;
  double accelerometer_bias_z_mps2;
  double gyroscope_bias_x_rps;
  double gyroscope_bias_y_rps;
  double gyroscope_bias_z_rps;
  double visual_translation_scale_m;
  uint32_t visual_translation_scale_valid;
} ark_vio_state_v1;

uint32_t ark_c_api_version(void);
ark_result ark_vio_default_config(ark_vio_config_v1* out_config);
ark_result ark_vio_create(const ark_vio_config_v1* config, ark_vio_handle** out_handle);
void ark_vio_destroy(ark_vio_handle* handle);
ark_result ark_vio_reset(ark_vio_handle* handle);
ark_result ark_vio_submit_imu(ark_vio_handle* handle, const ark_imu_sample_v1* sample);
ark_result ark_vio_fuse_visual(ark_vio_handle* handle, const ark_relative_pose_v1* pose);
ark_result ark_vio_get_state(ark_vio_handle* handle, ark_vio_state_v1* out_state);
// Returns required bytes including NUL. Copies a truncated NUL-terminated value
// when buffer is non-null and buffer_size > 0.
size_t ark_vio_copy_last_error(ark_vio_handle* handle, char* buffer, size_t buffer_size);

#ifdef __cplusplus
}  // extern "C"
#endif
#endif  // ARKERNEL_C_API_H
