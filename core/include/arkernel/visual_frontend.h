#pragma once

#include <cstdint>
#include <optional>
#include <vector>
#include "arkernel/essential_pose.h"
#include "arkernel/feature_detector.h"
#include "arkernel/geometry_model_selector.h"
#include "arkernel/homography_ransac.h"
#include "arkernel/motion_prediction.h"
#include "arkernel/optical_flow.h"

namespace arkernel {

enum class FrontendState { kInitializing, kTracking, kLimited, kReinitialized };

struct VisualFrontendConfig {
  FeatureDetectorConfig detector{};
  OpticalFlowConfig optical_flow{};
  EpipolarRansacConfig epipolar{};
  HomographyRansacConfig homography{};
  GeometrySelectionConfig model_selection{};
  EssentialPoseConfig pose{};
  std::size_t max_active_features{300};
  std::size_t min_tracked_features{12};
  double feature_exclusion_radius_px{8.0};
  double keyframe_median_motion_px{14.0};
  std::uint32_t max_frames_between_keyframes{30};
  TimestampNs max_frame_gap_ns{250000000};
};

struct VisualFrontendResult {
  TimestampNs timestamp_ns{0};
  FrontendState state{FrontendState::kInitializing};
  GeometrySelection geometry{};
  RelativePoseEstimate relative_pose{};
  std::vector<TrackedFeature> tracks;
  std::vector<std::uint32_t> accepted_feature_ids;
  std::size_t accepted_track_count{0};
  std::size_t active_feature_count{0};
  bool keyframe{false};
};

// Stateful, single-camera frontend. Calls must be serialized. Input buffers are
// copied because Android camera images are typically released after callback.
class VisualFrontend {
 public:
  VisualFrontend(PinholeRadTanCamera camera, VisualFrontendConfig config = {});
  VisualFrontendResult process(
      const GrayImageView& image,
      TimestampNs timestamp_ns,
      const std::optional<Quaternion>& rotation_current_from_previous = std::nullopt);
  void reset();
  const std::vector<ImageFeature>& activeFeatures() const { return active_features_; }

 private:
  VisualFrontendResult initialize(const GrayImageView& image, TimestampNs timestamp_ns,
                                  FrontendState state);
  void storeImage(const GrayImageView& image);
  GrayImageView storedImageView() const;
  void replenish(const GrayImageView& image, std::vector<ImageFeature>& retained);

  PinholeRadTanCamera camera_;
  VisualFrontendConfig config_;
  FastGridDetector detector_;
  PyramidalLucasKanade flow_;
  EpipolarRansac epipolar_;
  HomographyRansac homography_;
  GeometryModelSelector selector_;
  EssentialPoseSolver pose_solver_;
  std::vector<std::uint8_t> previous_pixels_;
  int previous_width_{0}, previous_height_{0};
  std::vector<ImageFeature> active_features_;
  TimestampNs previous_timestamp_{0};
  std::uint32_t next_feature_id_{1};
  std::uint32_t frames_since_keyframe_{0};
};

}  // namespace arkernel
