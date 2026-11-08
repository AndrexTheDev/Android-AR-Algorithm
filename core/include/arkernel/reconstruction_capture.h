#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "arkernel/reconstruction_asset.h"
#include "arkernel/types.h"

namespace arkernel {

enum class CaptureRejection {
  kNone,
  kInvalid,
  kNonMonotonicTimestamp,
  kTrackingUnreliable,
  kBlurred,
  kExposureClipped,
  kTooFewFeatures,
  kPoseUncertain,
  kInsufficientMotion,
  kObjectRoiInvalid,
  kObjectDistanceInvalid,
  kCapacityReached
};

struct CaptureQualityConfig {
  std::size_t max_keyframes{2000};
  TimestampNs min_interval_ns{100000000};
  double min_translation_m{0.04};
  double min_rotation_degrees{5.0};
  double min_sharpness{0.35};
  double max_clipped_pixel_ratio{0.08};
  std::size_t min_tracked_features{60};
  double max_translation_sigma_m{0.02};
  double max_rotation_sigma_degrees{1.0};
  double min_object_foreground_ratio{0.10};
  double max_object_foreground_ratio{0.90};
  double min_object_distance_m{0.15};
  double max_object_distance_m{3.0};
  double max_object_center_shift_m{0.02};
  std::size_t object_azimuth_bins{12};
  std::size_t object_elevation_bins{3};
};

// Image bytes remain owned by the platform archive. The portable core retains
// an opaque ID plus all metadata needed to reproduce selection and fusion.
struct ReconstructionFrameCandidate {
  std::uint64_t frame_id{0};
  TimestampNs timestamp_ns{0};
  Pose world_from_camera{};
  TrackingState tracking_state{TrackingState::kUninitialized};
  std::size_t tracked_features{0};
  double sharpness{0.0};
  double clipped_pixel_ratio{0.0};
  double translation_sigma_m{0.0};
  double rotation_sigma_degrees{0.0};
  double object_foreground_ratio{0.0};
  std::optional<Vec3> object_center_world_m;
  std::string image_reference;
};

struct SelectedReconstructionFrame {
  ReconstructionFrameCandidate frame;
  double quality_score{0.0};
  std::optional<std::size_t> object_view_bin;
};

struct CaptureDecision {
  bool accepted{false};
  CaptureRejection rejection{CaptureRejection::kNone};
  double quality_score{0.0};
  std::optional<std::size_t> object_view_bin;
};

struct ObjectCoverage {
  std::size_t covered_bins{0};
  std::size_t total_bins{0};
  double ratio{0.0};
};

class ReconstructionCapturePlanner {
 public:
  explicit ReconstructionCapturePlanner(ScanMode mode,
      CaptureQualityConfig config = {});
  CaptureDecision consider(const ReconstructionFrameCandidate& candidate);
  const std::vector<SelectedReconstructionFrame>& keyframes() const;
  ObjectCoverage objectCoverage() const;
  void reset();
 private:
  std::optional<std::size_t> objectBin(const ReconstructionFrameCandidate& candidate) const;
  ScanMode mode_;
  CaptureQualityConfig config_;
  std::vector<SelectedReconstructionFrame> keyframes_;
  std::vector<bool> object_bins_;
  std::optional<Vec3> reference_object_center_world_m_;
  TimestampNs last_seen_timestamp_ns_{0};
};

}  // namespace arkernel
