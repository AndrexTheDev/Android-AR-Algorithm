#include "arkernel/camera_response.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <set>
#include <vector>

namespace arkernel {
namespace {

constexpr std::size_t kMinSamples = 8;
constexpr std::size_t kMaxSamples = 64;
constexpr double kMinExposureRatio = 1.10;
constexpr double kMaxExposureRatio = 8.0;
constexpr double kMinIntensity = 0.02;
constexpr double kMaxIntensity = 0.98;
constexpr double kMinIntensitySpan = 0.30;
constexpr double kMinBlackLevel = 0.0;
constexpr double kMaxBlackLevel = 0.20;
constexpr double kMaxBlackDeviation = 0.02;
constexpr double kMaxResidual = 0.02;
constexpr double kMaxRmse = 0.01;

bool finite(double value) { return std::isfinite(value); }

}  // namespace

CameraResponseEstimate estimateCameraResponseLinearity(
    const std::vector<CameraResponsePair>& samples) {
  CameraResponseEstimate result;
  result.sample_count = samples.size();
  if (samples.size() < kMinSamples) {
    result.rejection = CameraResponseRejection::kTooFewSamples;
    return result;
  }
  if (samples.size() > kMaxSamples) {
    result.rejection = CameraResponseRejection::kTooManySamples;
    return result;
  }

  std::set<std::uint64_t> ids;
  std::set<long long> ratio_bins;
  std::vector<double> ratios;
  std::vector<double> low_intensity;
  std::vector<double> high_intensity;
  std::vector<double> low_exposure;
  std::vector<double> black_estimates;
  ratios.reserve(samples.size());
  low_intensity.reserve(samples.size());
  high_intensity.reserve(samples.size());
  low_exposure.reserve(samples.size());
  black_estimates.reserve(samples.size());
  double min_low_intensity = std::numeric_limits<double>::max();
  double max_low_intensity = std::numeric_limits<double>::lowest();

  for (const auto& sample : samples) {
    if (sample.id == 0) {
      result.rejection = CameraResponseRejection::kInvalidId;
      return result;
    }
    if (!ids.insert(sample.id).second) {
      result.rejection = CameraResponseRejection::kDuplicateId;
      return result;
    }
    if (!finite(sample.exposure_low_s) || !finite(sample.exposure_high_s) ||
        !finite(sample.intensity_low) || !finite(sample.intensity_high)) {
      result.rejection = CameraResponseRejection::kNonFiniteInput;
      return result;
    }
    if (sample.exposure_low_s <= 0.0 ||
        sample.exposure_high_s <= sample.exposure_low_s) {
      result.rejection = CameraResponseRejection::kInvalidExposure;
      return result;
    }
    const double ratio = sample.exposure_high_s / sample.exposure_low_s;
    if (!finite(ratio) || ratio < kMinExposureRatio ||
        ratio > kMaxExposureRatio) {
      result.rejection = CameraResponseRejection::kInvalidExposureRatio;
      return result;
    }
    if (sample.intensity_low < kMinIntensity ||
        sample.intensity_low > kMaxIntensity ||
        sample.intensity_high < kMinIntensity ||
        sample.intensity_high > kMaxIntensity) {
      result.rejection = CameraResponseRejection::kInvalidIntensity;
      return result;
    }
    const double black_estimate =
        (ratio * sample.intensity_low - sample.intensity_high) / (ratio - 1.0);
    if (!finite(black_estimate)) {
      result.rejection = CameraResponseRejection::kInconsistentBlackLevel;
      return result;
    }
    ratios.push_back(ratio);
    low_intensity.push_back(sample.intensity_low);
    high_intensity.push_back(sample.intensity_high);
    low_exposure.push_back(sample.exposure_low_s);
    black_estimates.push_back(black_estimate);
    ratio_bins.insert(std::llround(ratio * 1000.0));
    min_low_intensity = std::min(min_low_intensity, sample.intensity_low);
    max_low_intensity = std::max(max_low_intensity, sample.intensity_low);
  }

  if (ratio_bins.size() < 2) {
    result.rejection = CameraResponseRejection::kInsufficientRatioDiversity;
    return result;
  }
  if (max_low_intensity - min_low_intensity < kMinIntensitySpan) {
    result.rejection = CameraResponseRejection::kInsufficientIntensitySpan;
    return result;
  }

  std::sort(black_estimates.begin(), black_estimates.end());
  const std::size_t middle = black_estimates.size() / 2;
  const double black_level =
      (black_estimates.size() % 2 == 0)
          ? 0.5 * (black_estimates[middle - 1] + black_estimates[middle])
          : black_estimates[middle];
  if (!finite(black_level) || black_level < kMinBlackLevel ||
      black_level > kMaxBlackLevel) {
    result.rejection = CameraResponseRejection::kInconsistentBlackLevel;
    return result;
  }
  for (double estimate : black_estimates) {
    if (estimate < kMinBlackLevel || estimate > kMaxBlackLevel ||
        std::fabs(estimate - black_level) > kMaxBlackDeviation) {
      result.rejection = CameraResponseRejection::kInconsistentBlackLevel;
      return result;
    }
  }

  double sum_squared_residual = 0.0;
  double max_residual = 0.0;
  double min_slope = std::numeric_limits<double>::max();
  double max_slope = std::numeric_limits<double>::lowest();
  for (std::size_t i = 0; i < samples.size(); ++i) {
    const double predicted =
        black_level + ratios[i] * (low_intensity[i] - black_level);
    const double residual = std::fabs(predicted - high_intensity[i]);
    if (!finite(residual)) {
      result.rejection = CameraResponseRejection::kResidualTooLarge;
      return result;
    }
    const double slope =
        (low_intensity[i] - black_level) / low_exposure[i];
    if (!finite(slope) || slope <= 0.0) {
      result.rejection = CameraResponseRejection::kNonPositiveSlope;
      return result;
    }
    max_residual = std::max(max_residual, residual);
    sum_squared_residual += residual * residual;
    min_slope = std::min(min_slope, slope);
    max_slope = std::max(max_slope, slope);
  }
  const double rmse = std::sqrt(
      sum_squared_residual / static_cast<double>(samples.size()));

  result.black_level = black_level;
  result.min_slope = min_slope;
  result.max_slope = max_slope;
  result.max_residual = max_residual;
  result.rmse = rmse;
  if (max_residual > kMaxResidual) {
    result.rejection = CameraResponseRejection::kResidualTooLarge;
    return result;
  }
  if (!finite(rmse) || rmse > kMaxRmse) {
    result.rejection = CameraResponseRejection::kRmseTooLarge;
    return result;
  }
  result.accepted = true;
  result.rejection = CameraResponseRejection::kNone;
  return result;
}

const char* cameraResponseRejectionText(CameraResponseRejection rejection) {
  switch (rejection) {
    case CameraResponseRejection::kNone: return "none";
    case CameraResponseRejection::kTooFewSamples: return "too few samples";
    case CameraResponseRejection::kTooManySamples: return "too many samples";
    case CameraResponseRejection::kInvalidId: return "invalid sample id";
    case CameraResponseRejection::kDuplicateId: return "duplicate sample id";
    case CameraResponseRejection::kNonFiniteInput: return "non-finite input";
    case CameraResponseRejection::kInvalidExposure: return "invalid exposure";
    case CameraResponseRejection::kInvalidExposureRatio:
      return "invalid exposure ratio";
    case CameraResponseRejection::kInvalidIntensity:
      return "invalid intensity";
    case CameraResponseRejection::kInsufficientRatioDiversity:
      return "insufficient exposure-ratio diversity";
    case CameraResponseRejection::kInsufficientIntensitySpan:
      return "insufficient intensity span";
    case CameraResponseRejection::kInconsistentBlackLevel:
      return "inconsistent black level";
    case CameraResponseRejection::kNonPositiveSlope:
      return "non-positive response slope";
    case CameraResponseRejection::kResidualTooLarge:
      return "linearity residual too large";
    case CameraResponseRejection::kRmseTooLarge:
      return "linearity rmse too large";
  }
  return "unknown rejection";
}

}  // namespace arkernel
