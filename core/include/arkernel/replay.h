#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include "arkernel/camera_model.h"
#include "arkernel/visual_frontend.h"
#include "arkernel/vio_estimator.h"

namespace arkernel {

enum class ReplayEventType { kImu, kFrame };

struct ReplayFrame {
  int width{0}, height{0}, row_stride{0};
  std::vector<std::uint8_t> gray;
};

struct ReplayEvent {
  ReplayEventType type{ReplayEventType::kImu};
  TimestampNs timestamp_ns{0};
  ImuSample imu{};
  ReplayFrame frame{};
};

struct ReplayDataset {
  CameraIntrinsics intrinsics{};
  RadTanDistortion distortion{};
  std::vector<ReplayEvent> events;

  static ReplayDataset loadManifest(const std::string& manifest_path);
};

struct ReplayMetrics {
  std::size_t imu_samples{0};
  std::size_t frames{0};
  std::size_t tracking_frames{0};
  std::size_t limited_frames{0};
  std::size_t reinitializations{0};
  std::size_t general_3d_frames{0};
  std::size_t planar_frames{0};
  std::size_t visual_fusions{0};
  std::size_t peak_active_features{0};
  VioStatus final_vio_status{VioStatus::kUninitialized};
  Pose final_world_from_body{};
  std::uint64_t deterministic_hash{1469598103934665603ULL};
};

// Offline equivalent of Android session ordering. Manifest events must be
// monotonic; every frame sees all and only IMU samples up to its timestamp.
class ReplayRunner {
 public:
  ReplayRunner(PinholeRadTanCamera camera,
               VisualFrontendConfig visual_config = {},
               VioConfig vio_config = {});
  ReplayMetrics run(const ReplayDataset& dataset);
 private:
  Quaternion cameraRotationPrediction(const Quaternion& current_world_from_body) const;
  PinholeRadTanCamera camera_;
  VisualFrontendConfig visual_config_;
  VioConfig vio_config_;
  Quaternion body_from_camera_{};
  Quaternion previous_world_from_body_{};
  bool has_previous_camera_pose_{false};
};

}  // namespace arkernel
