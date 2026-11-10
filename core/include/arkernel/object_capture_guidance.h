#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

#include "arkernel/types.h"

namespace arkernel {

enum class ObjectCapturePhase {
  kAcquireObject,
  kStabilizeCenter,
  kSetDistance,
  kOrbit,
  kComplete,
  kBlocked,
};

enum class ObjectCaptureInstruction {
  kCenterObject,
  kHoldStill,
  kMoveCloser,
  kMoveFarther,
  kOrbitToNextView,
  kRaiseCamera,
  kLowerCamera,
  kComplete,
  kRetryTracking,
  kStop,
};

enum class ObjectCaptureRejection {
  kNone,
  kInvalid,
  kNonMonotonicTimestamp,
  kTrackingUnreliable,
  kForegroundInvalid,
  kObjectCenterMissing,
  kCenterUnstable,
  kDistanceInvalid,
  kCapacityReached,
  kCoverageIncomplete,
};

struct ObjectCaptureGuidanceConfig {
  std::size_t azimuth_bins{12};
  std::size_t elevation_bins{3};
  std::size_t max_observations{512};
  std::size_t min_stable_center_observations{3};
  double min_foreground_ratio{0.10};
  double max_foreground_ratio{0.90};
  double min_distance_m{0.15};
  double max_distance_m{3.0};
  double target_distance_m{0.8};
  double distance_tolerance_m{0.15};
  double max_center_shift_m{0.02};
  double min_coverage_ratio{0.80};
  double elevation_step_degrees{10.0};
};

// Host-supplied observations only. The core does not segment foreground,
// estimate an object centre, actuate a turntable or claim reconstruction
// quality from coverage alone.
struct ObjectCaptureObservation {
  TimestampNs timestamp_ns{0};
  Pose world_from_camera{};
  bool tracking_reliable{false};
  double foreground_ratio{0.0};
  std::optional<Vec3> object_center_world_m{};
};

struct ObjectCaptureGuidance {
  ObjectCapturePhase phase{ObjectCapturePhase::kAcquireObject};
  ObjectCaptureInstruction instruction{ObjectCaptureInstruction::kCenterObject};
  ObjectCaptureRejection rejection{ObjectCaptureRejection::kNone};
  bool complete{false};
  std::size_t observations{0};
  std::size_t stable_center_observations{0};
  std::size_t covered_bins{0};
  std::size_t total_bins{0};
  std::size_t covered_rings{0};
  double coverage_ratio{0.0};
  double current_azimuth_degrees{0.0};
  double current_elevation_degrees{0.0};
  double target_azimuth_degrees{0.0};
  double target_elevation_degrees{0.0};
  double turntable_step_degrees{0.0};
  double measured_distance_m{0.0};
};

/**
 * Bounded deterministic guidance state machine for isolated-object scans.
 * It accepts host-measured foreground ratios and object centres, tracks
 * azimuth/elevation coverage around a stable centre and emits UI-neutral
 * instructions plus a bounded turntable target. It is not a physical motion
 * controller and never upgrades reconstruction quality evidence.
 */
class ObjectCaptureGuidancePlanner {
 public:
  explicit ObjectCaptureGuidancePlanner(ObjectCaptureGuidanceConfig config = {});
  ObjectCaptureGuidance observe(const ObjectCaptureObservation& observation);
  ObjectCaptureGuidance guidance() const;
  void reset();

 private:
  struct ViewAngles {
    double azimuth_degrees{0.0};
    double elevation_degrees{0.0};
    std::size_t azimuth_bin{0};
    std::size_t elevation_bin{0};
    std::size_t bin{0};
  };

  ViewAngles viewAngles(const Vec3& delta) const;
  static double shortestAzimuthStepDegrees(double from, double to);
  std::size_t coveredBins() const;
  std::size_t coveredRings() const;
  std::size_t nextTargetBin(std::size_t current_azimuth_bin) const;

  ObjectCaptureGuidanceConfig config_;
  std::vector<bool> bins_;
  std::optional<Vec3> reference_center_world_m_;
  std::size_t observations_{0};
  std::size_t stable_center_observations_{0};
  TimestampNs last_timestamp_ns_{0};
  ObjectCaptureGuidance guidance_;
};

}  // namespace arkernel
