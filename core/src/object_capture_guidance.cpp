#include "arkernel/object_capture_guidance.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
constexpr double kPi = 3.14159265358979323846;

bool finite(double value) { return std::isfinite(value); }
bool finite(const Vec3& value) {
  return finite(value.x) && finite(value.y) && finite(value.z);
}
bool validPose(const Pose& pose) {
  const Quaternion& q = pose.rotation;
  const double norm = std::sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
  return finite(pose.translation) && finite(q.w) && finite(q.x) && finite(q.y) &&
         finite(q.z) && norm > 1e-9;
}
double clamp(double value, double low, double high) {
  return std::min(high, std::max(low, value));
}
}  // namespace

ObjectCaptureGuidancePlanner::ObjectCaptureGuidancePlanner(
    ObjectCaptureGuidanceConfig config)
    : config_(config) {
  const bool valid =
      config_.azimuth_bins > 0 && config_.azimuth_bins <= 360 &&
      config_.elevation_bins > 0 && config_.elevation_bins <= 180 &&
      config_.azimuth_bins <= std::numeric_limits<std::size_t>::max() /
                                  config_.elevation_bins &&
      config_.max_observations > 0 && config_.max_observations <= 1000000 &&
      config_.min_stable_center_observations > 0 &&
      config_.min_stable_center_observations <= config_.max_observations &&
      finite(config_.min_foreground_ratio) && finite(config_.max_foreground_ratio) &&
      config_.min_foreground_ratio >= 0.0 && config_.max_foreground_ratio <= 1.0 &&
      config_.max_foreground_ratio > config_.min_foreground_ratio &&
      finite(config_.min_distance_m) && finite(config_.max_distance_m) &&
      config_.min_distance_m > 0.0 && config_.max_distance_m > config_.min_distance_m &&
      finite(config_.target_distance_m) &&
      config_.target_distance_m >= config_.min_distance_m &&
      config_.target_distance_m <= config_.max_distance_m &&
      finite(config_.distance_tolerance_m) && config_.distance_tolerance_m >= 0.0 &&
      finite(config_.max_center_shift_m) && config_.max_center_shift_m >= 0.0 &&
      finite(config_.min_coverage_ratio) && config_.min_coverage_ratio > 0.0 &&
      config_.min_coverage_ratio <= 1.0 && finite(config_.elevation_step_degrees) &&
      config_.elevation_step_degrees > 0.0 && config_.elevation_step_degrees <= 180.0;
  if (!valid) throw std::invalid_argument("Invalid object capture guidance configuration");
  bins_.assign(config_.azimuth_bins * config_.elevation_bins, false);
  guidance_.total_bins = bins_.size();
}

ObjectCaptureGuidancePlanner::ViewAngles ObjectCaptureGuidancePlanner::viewAngles(
    const Vec3& delta) const {
  ViewAngles output;
  const double distance = delta.norm();
  double azimuth = std::atan2(delta.y, delta.x);
  if (azimuth < 0.0) azimuth += 2.0 * kPi;
  const double elevation = std::asin(clamp(delta.z / distance, -1.0, 1.0));
  output.azimuth_degrees = azimuth * 180.0 / kPi;
  output.elevation_degrees = elevation * 180.0 / kPi;
  output.azimuth_bin = std::min(config_.azimuth_bins - 1,
      static_cast<std::size_t>(azimuth / (2.0 * kPi) * config_.azimuth_bins));
  output.elevation_bin = std::min(config_.elevation_bins - 1,
      static_cast<std::size_t>((elevation + kPi / 2.0) / kPi * config_.elevation_bins));
  output.bin = output.elevation_bin * config_.azimuth_bins + output.azimuth_bin;
  return output;
}

double ObjectCaptureGuidancePlanner::shortestAzimuthStepDegrees(double from, double to) {
  double step = to - from;
  while (step > 180.0) step -= 360.0;
  while (step < -180.0) step += 360.0;
  return step;
}

std::size_t ObjectCaptureGuidancePlanner::coveredBins() const {
  return static_cast<std::size_t>(std::count(bins_.begin(), bins_.end(), true));
}

std::size_t ObjectCaptureGuidancePlanner::coveredRings() const {
  std::size_t rings = 0;
  for (std::size_t elevation = 0; elevation < config_.elevation_bins; ++elevation) {
    for (std::size_t azimuth = 0; azimuth < config_.azimuth_bins; ++azimuth) {
      if (bins_[elevation * config_.azimuth_bins + azimuth]) {
        ++rings;
        break;
      }
    }
  }
  return rings;
}

std::size_t ObjectCaptureGuidancePlanner::nextTargetBin(
    std::size_t current_azimuth_bin) const {
  for (std::size_t elevation = 0; elevation < config_.elevation_bins; ++elevation) {
    for (std::size_t offset = 0; offset < config_.azimuth_bins; ++offset) {
      const std::size_t azimuth =
          (current_azimuth_bin + offset) % config_.azimuth_bins;
      const std::size_t bin = elevation * config_.azimuth_bins + azimuth;
      if (!bins_[bin]) return bin;
    }
  }
  return bins_.size();
}

ObjectCaptureGuidance ObjectCaptureGuidancePlanner::observe(
    const ObjectCaptureObservation& observation) {
  if (guidance_.complete) return guidance_;

  ObjectCaptureGuidance output = guidance_;
  output.phase = ObjectCapturePhase::kBlocked;
  output.instruction = ObjectCaptureInstruction::kRetryTracking;
  output.rejection = ObjectCaptureRejection::kInvalid;
  output.complete = false;

  auto publish = [&]() {
    guidance_ = output;
    return output;
  };
  auto fillCommon = [&]() {
    output.observations = observations_;
    output.stable_center_observations = stable_center_observations_;
    output.total_bins = bins_.size();
    output.covered_bins = coveredBins();
    output.covered_rings = coveredRings();
    output.coverage_ratio = output.total_bins == 0
                                ? 0.0
                                : static_cast<double>(output.covered_bins) /
                                      static_cast<double>(output.total_bins);
  };

  if (observation.timestamp_ns <= 0 || !finite(observation.foreground_ratio) ||
      !validPose(observation.world_from_camera) ||
      (observation.object_center_world_m &&
       !finite(*observation.object_center_world_m))) {
    output.rejection = ObjectCaptureRejection::kInvalid;
    fillCommon();
    return publish();
  }
  if (last_timestamp_ns_ != 0 &&
      observation.timestamp_ns <= last_timestamp_ns_) {
    output.rejection = ObjectCaptureRejection::kNonMonotonicTimestamp;
    fillCommon();
    return publish();
  }
  last_timestamp_ns_ = observation.timestamp_ns;
  if (!observation.tracking_reliable) {
    output.rejection = ObjectCaptureRejection::kTrackingUnreliable;
    fillCommon();
    return publish();
  }
  if (observation.foreground_ratio < config_.min_foreground_ratio ||
      observation.foreground_ratio > config_.max_foreground_ratio) {
    output.phase = ObjectCapturePhase::kAcquireObject;
    output.instruction = ObjectCaptureInstruction::kCenterObject;
    output.rejection = ObjectCaptureRejection::kForegroundInvalid;
    fillCommon();
    return publish();
  }
  if (!observation.object_center_world_m) {
    output.phase = ObjectCapturePhase::kAcquireObject;
    output.instruction = ObjectCaptureInstruction::kCenterObject;
    output.rejection = ObjectCaptureRejection::kObjectCenterMissing;
    fillCommon();
    return publish();
  }
  if (observations_ >= config_.max_observations) {
    output.instruction = ObjectCaptureInstruction::kStop;
    output.rejection = ObjectCaptureRejection::kCoverageIncomplete;
    fillCommon();
    return publish();
  }

  ++observations_;
  const Vec3 center = *observation.object_center_world_m;
  if (!reference_center_world_m_) {
    reference_center_world_m_ = center;
    stable_center_observations_ = 1;
  } else if ((center - *reference_center_world_m_).norm() <=
             config_.max_center_shift_m) {
    ++stable_center_observations_;
  } else {
    reference_center_world_m_ = center;
    stable_center_observations_ = 1;
    std::fill(bins_.begin(), bins_.end(), false);
  }

  const Vec3 delta = observation.world_from_camera.translation -
                     *reference_center_world_m_;
  const double distance = delta.norm();
  if (!finite(distance) || distance <= 0.0) {
    output.rejection = ObjectCaptureRejection::kInvalid;
    fillCommon();
    return publish();
  }
  const ViewAngles angles = viewAngles(delta);
  output.current_azimuth_degrees = angles.azimuth_degrees;
  output.current_elevation_degrees = angles.elevation_degrees;
  output.target_azimuth_degrees = angles.azimuth_degrees;
  output.target_elevation_degrees = angles.elevation_degrees;
  output.measured_distance_m = distance;

  if (distance < config_.min_distance_m || distance > config_.max_distance_m) {
    output.phase = ObjectCapturePhase::kSetDistance;
    output.instruction = distance > config_.max_distance_m
                             ? ObjectCaptureInstruction::kMoveCloser
                             : ObjectCaptureInstruction::kMoveFarther;
    output.rejection = ObjectCaptureRejection::kDistanceInvalid;
    fillCommon();
    return publish();
  }
  if (stable_center_observations_ < config_.min_stable_center_observations) {
    output.phase = ObjectCapturePhase::kStabilizeCenter;
    output.instruction = ObjectCaptureInstruction::kHoldStill;
    output.rejection = ObjectCaptureRejection::kCenterUnstable;
    fillCommon();
    return publish();
  }
  if (std::abs(distance - config_.target_distance_m) >
      config_.distance_tolerance_m) {
    output.phase = ObjectCapturePhase::kSetDistance;
    output.instruction = distance > config_.target_distance_m
                             ? ObjectCaptureInstruction::kMoveCloser
                             : ObjectCaptureInstruction::kMoveFarther;
    output.rejection = ObjectCaptureRejection::kDistanceInvalid;
    fillCommon();
    return publish();
  }

  bins_[angles.bin] = true;
  fillCommon();
  const std::size_t required_bins = std::max<std::size_t>(1,
      std::min(bins_.size(), static_cast<std::size_t>(std::ceil(
          config_.min_coverage_ratio * static_cast<double>(bins_.size())))));
  const bool complete = output.covered_bins >= required_bins &&
                        output.covered_rings == config_.elevation_bins;
  output.complete = complete;
  output.rejection = ObjectCaptureRejection::kNone;
  output.turntable_step_degrees = 0.0;
  if (complete) {
    output.phase = ObjectCapturePhase::kComplete;
    output.instruction = ObjectCaptureInstruction::kComplete;
    output.target_azimuth_degrees = angles.azimuth_degrees;
    output.target_elevation_degrees = angles.elevation_degrees;
    return publish();
  }

  const std::size_t target_bin = nextTargetBin(angles.azimuth_bin);
  if (target_bin >= bins_.size()) {
    output.phase = ObjectCapturePhase::kBlocked;
    output.instruction = ObjectCaptureInstruction::kStop;
    output.rejection = ObjectCaptureRejection::kCoverageIncomplete;
    return publish();
  }
  const std::size_t target_elevation = target_bin / config_.azimuth_bins;
  const std::size_t target_azimuth = target_bin % config_.azimuth_bins;
  output.phase = ObjectCapturePhase::kOrbit;
  output.target_azimuth_degrees =
      (static_cast<double>(target_azimuth) + 0.5) * 360.0 /
      static_cast<double>(config_.azimuth_bins);
  output.target_elevation_degrees =
      -90.0 + (static_cast<double>(target_elevation) + 0.5) * 180.0 /
                  static_cast<double>(config_.elevation_bins);
  output.turntable_step_degrees = shortestAzimuthStepDegrees(
      angles.azimuth_degrees, output.target_azimuth_degrees);
  const double elevation_delta =
      output.target_elevation_degrees - angles.elevation_degrees;
  if (elevation_delta > config_.elevation_step_degrees) {
    output.instruction = ObjectCaptureInstruction::kRaiseCamera;
  } else if (elevation_delta < -config_.elevation_step_degrees) {
    output.instruction = ObjectCaptureInstruction::kLowerCamera;
  } else {
    output.instruction = ObjectCaptureInstruction::kOrbitToNextView;
  }
  return publish();
}

ObjectCaptureGuidance ObjectCaptureGuidancePlanner::guidance() const {
  return guidance_;
}

void ObjectCaptureGuidancePlanner::reset() {
  std::fill(bins_.begin(), bins_.end(), false);
  reference_center_world_m_.reset();
  observations_ = 0;
  stable_center_observations_ = 0;
  last_timestamp_ns_ = 0;
  guidance_ = ObjectCaptureGuidance{};
  guidance_.total_bins = bins_.size();
}

}  // namespace arkernel
