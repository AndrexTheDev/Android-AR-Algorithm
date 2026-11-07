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
typedef struct ark_visual_handle ark_visual_handle;

typedef enum ark_frontend_state {
  ARK_FRONTEND_INITIALIZING = 0,
  ARK_FRONTEND_TRACKING = 1,
  ARK_FRONTEND_LIMITED = 2,
  ARK_FRONTEND_REINITIALIZED = 3
} ark_frontend_state;

typedef enum ark_scene_geometry {
  ARK_GEOMETRY_INVALID = 0,
  ARK_GEOMETRY_GENERAL_3D = 1,
  ARK_GEOMETRY_PLANAR_OR_ROTATION = 2
} ark_scene_geometry;

typedef enum ark_plane_semantic {
  ARK_PLANE_UNKNOWN = 0,
  ARK_PLANE_FLOOR = 1,
  ARK_PLANE_CEILING = 2,
  ARK_PLANE_WALL = 3,
  ARK_PLANE_SLOPED = 4
} ark_plane_semantic;

typedef enum ark_semantic_projection_surface {
  ARK_SEMANTIC_SURFACE_ANY = 0,
  ARK_SEMANTIC_SURFACE_FLOOR = 1,
  ARK_SEMANTIC_SURFACE_WALL = 2
} ark_semantic_projection_surface;

typedef enum ark_mapping_gate_state {
  ARK_MAPPING_PAUSED = 0,
  ARK_MAPPING_RECOVERING = 1,
  ARK_MAPPING_ACTIVE = 2
} ark_mapping_gate_state;

typedef enum ark_mapping_pause_reason {
  ARK_MAPPING_REASON_NONE = 0,
  ARK_MAPPING_REASON_POSE_UNINITIALIZED = 1,
  ARK_MAPPING_REASON_POSE_LIMITED = 2,
  ARK_MAPPING_REASON_VISUAL_NOT_TRACKING = 3,
  ARK_MAPPING_REASON_TOO_FEW_TRACKS = 4,
  ARK_MAPPING_REASON_IMU_OVERFLOW = 5,
  ARK_MAPPING_REASON_RECOVERING = 6
} ark_mapping_pause_reason;

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

typedef struct ark_visual_config_v1 {
  uint32_t struct_size;
  uint32_t api_version;
  int32_t width;
  int32_t height;
  double fx;
  double fy;
  double cx;
  double cy;
  double distortion_k1;
  double distortion_k2;
  double distortion_p1;
  double distortion_p2;
  double distortion_k3;
  size_t max_active_features;
  size_t min_tracked_features;
  int64_t max_frame_gap_ns;
} ark_visual_config_v1;

typedef struct ark_gray_frame_v1 {
  uint32_t struct_size;
  int64_t timestamp_ns;
  int32_t width;
  int32_t height;
  int32_t row_stride;
  const uint8_t* data;
  size_t data_size;
  uint32_t has_rotation_prediction;
  double rotation_current_from_previous_w;
  double rotation_current_from_previous_x;
  double rotation_current_from_previous_y;
  double rotation_current_from_previous_z;
} ark_gray_frame_v1;

typedef struct ark_visual_result_v1 {
  uint32_t struct_size;
  int64_t timestamp_ns;
  uint32_t state;
  uint32_t geometry;
  size_t accepted_track_count;
  size_t active_feature_count;
  uint32_t keyframe;
  uint32_t relative_pose_valid;
  double relative_rotation_w;
  double relative_rotation_x;
  double relative_rotation_y;
  double relative_rotation_z;
  double relative_translation_direction_x;
  double relative_translation_direction_y;
  double relative_translation_direction_z;
  size_t positive_depth_count;
  double median_parallax_degrees;
} ark_visual_result_v1;

typedef struct ark_world_pose_v1 {
  uint32_t struct_size;
  int64_t timestamp_ns;
  double rotation_w;
  double rotation_x;
  double rotation_y;
  double rotation_z;
  double position_x_m;
  double position_y_m;
  double position_z_m;
} ark_world_pose_v1;

typedef struct ark_map_update_v1 {
  uint32_t struct_size;
  int64_t timestamp_ns;
  size_t created;
  size_t updated;
  size_t rejected;
  size_t pruned;
  size_t landmark_count;
  uint32_t plane_valid;
  size_t plane_inlier_count;
  double plane_normal_x;
  double plane_normal_y;
  double plane_normal_z;
  double plane_distance_from_origin_m;
  double plane_center_x_m;
  double plane_center_y_m;
  double plane_center_z_m;
  double plane_observed_area_m2;
  double plane_mean_absolute_error_m;
} ark_map_update_v1;

typedef struct ark_plane_v1 {
  uint32_t struct_size;
  uint64_t id;
  double normal_x;
  double normal_y;
  double normal_z;
  double distance_from_origin_m;
  double center_x_m;
  double center_y_m;
  double center_z_m;
  double observed_area_m2;
  double mean_absolute_error_m;
  size_t inlier_count;
} ark_plane_v1;

typedef struct ark_mapping_gate_v1 {
  uint32_t struct_size;
  uint32_t state;
  uint32_t reason;
  uint32_t integrate;
} ark_mapping_gate_v1;

typedef struct ark_room_layout_v1 {
  uint32_t struct_size;
  uint32_t has_floor;
  uint32_t has_ceiling;
  uint32_t height_valid;
  uint64_t floor_plane_id;
  uint64_t ceiling_plane_id;
  double floor_elevation_m;
  double ceiling_elevation_m;
  double room_height_m;
  double observed_floor_area_m2;
  double observed_ceiling_area_m2;
  double observed_wall_area_m2;
  size_t wall_count;
} ark_room_layout_v1;

typedef struct ark_plane_hit_v1 {
  uint32_t struct_size;
  double position_x_m;
  double position_y_m;
  double position_z_m;
  double normal_x;
  double normal_y;
  double normal_z;
  double distance_m;
  double plane_u_m;
  double plane_v_m;
} ark_plane_hit_v1;

typedef struct ark_relocalization_correspondence_v1 {
  uint32_t struct_size;
  double saved_x_m;
  double saved_y_m;
  double saved_z_m;
  double current_x_m;
  double current_y_m;
  double current_z_m;
} ark_relocalization_correspondence_v1;

typedef struct ark_relocalization_result_v1 {
  uint32_t struct_size;
  uint64_t correspondence_count;
  uint64_t inlier_count;
  double rmse_m;
  double rotation_w;
  double rotation_x;
  double rotation_y;
  double rotation_z;
  double translation_x_m;
  double translation_y_m;
  double translation_z_m;
} ark_relocalization_result_v1;

typedef struct ark_semantic_projection_v1 {
  uint32_t struct_size;
  uint32_t class_id;
  int64_t source_track_id;
  int64_t timestamp_ns;
  double pixel_x;
  double pixel_y;
  double confidence;
  uint32_t dynamic;
  uint32_t surface;
  double min_distance_m;
  double max_distance_m;
} ark_semantic_projection_v1;

typedef struct ark_semantic_observation_v1 {
  uint32_t struct_size;
  uint32_t class_id;
  int64_t source_track_id;
  int64_t timestamp_ns;
  double position_x_m;
  double position_y_m;
  double position_z_m;
  double confidence;
  uint32_t dynamic;
} ark_semantic_observation_v1;

typedef struct ark_semantic_update_v1 {
  uint32_t struct_size;
  size_t created;
  size_t updated;
  size_t rejected;
  size_t pruned;
  size_t object_count;
} ark_semantic_update_v1;

typedef struct ark_semantic_object_v1 {
  uint32_t struct_size;
  uint64_t id;
  uint32_t class_id;
  int64_t source_track_id;
  int64_t first_seen_ns;
  int64_t last_seen_ns;
  double position_x_m;
  double position_y_m;
  double position_z_m;
  double confidence;
  size_t observation_count;
  uint32_t dynamic;
} ark_semantic_object_v1;

typedef struct ark_anchor_v1 {
  uint32_t struct_size;
  uint64_t id;
  int64_t created_ns;
  double rotation_w;
  double rotation_x;
  double rotation_y;
  double rotation_z;
  double position_x_m;
  double position_y_m;
  double position_z_m;
} ark_anchor_v1;

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
ark_result ark_visual_default_config(int32_t width, int32_t height,
                                     double fx, double fy, double cx, double cy,
                                     ark_visual_config_v1* out_config);
ark_result ark_visual_create(const ark_visual_config_v1* config, ark_visual_handle** out_handle);
void ark_visual_destroy(ark_visual_handle* handle);
ark_result ark_visual_reset(ark_visual_handle* handle);
ark_result ark_visual_process_gray(ark_visual_handle* handle,
                                   const ark_gray_frame_v1* frame,
                                   ark_visual_result_v1* out_result);
size_t ark_visual_copy_last_error(ark_visual_handle* handle, char* buffer, size_t buffer_size);
ark_result ark_visual_update_mapping_gate(ark_visual_handle* handle,
                                          uint32_t pose_initialized,
                                          uint32_t pose_limited,
                                          uint32_t visual_tracking,
                                          size_t accepted_tracks,
                                          uint32_t imu_queue_overflow,
                                          ark_mapping_gate_v1* out_gate);
ark_result ark_visual_integrate_map(ark_visual_handle* handle,
                                    const ark_world_pose_v1* world_from_camera,
                                    ark_map_update_v1* out_update);
ark_result ark_visual_get_plane_count(ark_visual_handle* handle, size_t* out_count);
ark_result ark_visual_get_plane(ark_visual_handle* handle, size_t index,
                                ark_plane_v1* out_plane);
ark_result ark_visual_get_plane_semantic(ark_visual_handle* handle, size_t index,
                                         uint32_t* out_semantic);
ark_result ark_visual_get_room_layout(ark_visual_handle* handle,
                                      ark_room_layout_v1* out_layout);
ark_result ark_visual_project_semantic(ark_visual_handle* handle,
                                       const ark_semantic_projection_v1* projection,
                                       const ark_world_pose_v1* world_from_camera,
                                       int64_t now_ns,
                                       ark_semantic_update_v1* out_update);
ark_result ark_visual_integrate_semantics(ark_visual_handle* handle,
                                          const ark_semantic_observation_v1* observations,
                                          size_t observation_count,
                                          int64_t now_ns,
                                          ark_semantic_update_v1* out_update);
ark_result ark_visual_get_semantic_object_count(ark_visual_handle* handle, size_t* out_count);
ark_result ark_visual_get_semantic_object(ark_visual_handle* handle, size_t index,
                                          ark_semantic_object_v1* out_object);
ark_result ark_visual_raycast_plane(ark_visual_handle* handle,
                                    double origin_x_m, double origin_y_m, double origin_z_m,
                                    double direction_x, double direction_y, double direction_z,
                                    double min_distance_m, double max_distance_m,
                                    ark_plane_hit_v1* out_hit);
ark_result ark_visual_create_anchor(ark_visual_handle* handle,
                                    const ark_world_pose_v1* world_from_anchor,
                                    uint64_t* out_anchor_id);
ark_result ark_visual_remove_anchor(ark_visual_handle* handle, uint64_t anchor_id);
ark_result ark_visual_get_anchor(ark_visual_handle* handle, uint64_t anchor_id,
                                 ark_anchor_v1* out_anchor);
// With buffer == NULL, returns the required byte count through out_size.
// Otherwise capacity must be at least that size.
ark_result ark_visual_export_snapshot(ark_visual_handle* handle, int64_t created_ns,
                                      uint8_t* buffer, size_t capacity, size_t* out_size);
// restore_now_ns must be the current monotonic timestamp; persisted landmark
// freshness is rebased to it. The caller must establish world-frame alignment.
ark_result ark_visual_restore_snapshot(ark_visual_handle* handle,
                                       const uint8_t* buffer, size_t size,
                                       int64_t restore_now_ns);
// Estimates current_from_saved from robust metric correspondences and restores
// only when the geometric quality gates pass.
ark_result ark_visual_restore_snapshot_relocalized(
    ark_visual_handle* handle, const uint8_t* buffer, size_t size,
    const ark_relocalization_correspondence_v1* correspondences,
    size_t correspondence_count, int64_t restore_now_ns,
    ark_relocalization_result_v1* out_result);
// Matches persisted/current landmark descriptors, verifies the resulting
// metric 3D-3D alignment, and atomically restores on success.
ark_result ark_visual_restore_snapshot_matched(
    ark_visual_handle* handle, const uint8_t* buffer, size_t size,
    int64_t restore_now_ns, ark_relocalization_result_v1* out_result);

ark_result ark_vio_default_config(ark_vio_config_v1* out_config);
ark_result ark_vio_create(const ark_vio_config_v1* config, ark_vio_handle** out_handle);
// Additive full-extrinsic constructor; the v1 config layout remains unchanged.
ark_result ark_vio_create_with_extrinsics(const ark_vio_config_v1* config,
                                          double body_from_camera_tx_m,
                                          double body_from_camera_ty_m,
                                          double body_from_camera_tz_m,
                                          ark_vio_handle** out_handle);
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
