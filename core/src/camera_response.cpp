#include "arkernel/camera_response.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <set>
#include <stdexcept>
#include <utility>
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

constexpr double kCurveMinResponseGap = 1e-6;
constexpr double kCurveValueLimit = 8.0;

struct BinCoefficients {
  std::array<std::size_t, 2> index{};
  std::array<double, 2> weight{};
};

struct CurveSample {
  double target{0.0};
  BinCoefficients low{};
  BinCoefficients high{};
};

struct CurveMetrics {
  double max_residual{0.0};
  double rmse{0.0};
};

BinCoefficients mapIntensity(double intensity, std::size_t bins) {
  const double position = intensity * static_cast<double>(bins - 1);
  std::size_t first = static_cast<std::size_t>(std::floor(position));
  if (first + 1 >= bins) first = bins - 2;
  const double fraction = position - static_cast<double>(first);
  return {{first, first + 1}, {1.0 - fraction, fraction}};
}

double interpolate(const std::vector<double>& curve,
                   const BinCoefficients& coefficients) {
  return coefficients.weight[0] * curve[coefficients.index[0]] +
         coefficients.weight[1] * curve[coefficients.index[1]];
}

// Pool-adjacent-violators projection onto a monotonic cone with a minimum
// slope. The LUT is re-anchored at zero after projection.
bool projectMonotonic(std::vector<double>& curve, double min_slope) {
  const std::size_t count = curve.size();
  const std::vector<double> before = curve;
  std::vector<double> adjusted(count);
  for (std::size_t i = 0; i < count; ++i) {
    adjusted[i] = curve[i] - min_slope * static_cast<double>(i);
  }
  struct Block {
    double sum{0.0};
    std::size_t count{0};
    std::size_t start{0};
  };
  std::vector<Block> blocks;
  bool merged = false;
  for (std::size_t i = 0; i < count; ++i) {
    Block block{adjusted[i], 1, i};
    while (!blocks.empty() &&
           blocks.back().sum / static_cast<double>(blocks.back().count) >
               block.sum / static_cast<double>(block.count)) {
      block.sum += blocks.back().sum;
      block.count += blocks.back().count;
      block.start = blocks.back().start;
      blocks.pop_back();
      merged = true;
    }
    blocks.push_back(block);
  }
  for (const auto& block : blocks) {
    const double average = block.sum / static_cast<double>(block.count);
    for (std::size_t j = block.start; j < block.start + block.count; ++j) {
      adjusted[j] = average;
    }
  }
  for (std::size_t i = 0; i < count; ++i) {
    curve[i] = adjusted[i] + min_slope * static_cast<double>(i);
  }
  const double offset = curve[0];
  for (double& value : curve) value -= offset;
  bool changed = merged;
  for (std::size_t i = 0; i < count; ++i) {
    if (std::fabs(curve[i] - before[i]) > 1e-12) changed = true;
  }
  return changed;
}

CurveMetrics evaluateCurve(const std::vector<double>& curve,
                           const std::vector<CurveSample>& samples) {
  CurveMetrics metrics;
  double sum_squared = 0.0;
  for (const auto& sample : samples) {
    const double residual =
        (interpolate(curve, sample.low) - interpolate(curve, sample.high)) -
        sample.target;
    const double magnitude = std::fabs(residual);
    metrics.max_residual = std::max(metrics.max_residual, magnitude);
    sum_squared += residual * residual;
  }
  if (!samples.empty()) {
    metrics.rmse = std::sqrt(sum_squared / static_cast<double>(samples.size()));
  }
  return metrics;
}

bool validCurveConfig(const CameraResponseCurveConfig& config) {
  const auto finite_positive = [](double value) {
    return finite(value) && value > 0.0;
  };
  return config.bins >= 16 && config.bins <= 256 &&
         config.min_samples >= 8 && config.min_samples <= config.max_samples &&
         config.max_samples <= 1024 &&
         config.max_iterations >= 16 && config.max_iterations <= 1024 &&
         config.holdout_stride >= 2 && config.holdout_stride <= 16 &&
         config.min_covered_bins >= 4 &&
         config.min_covered_bins <= config.bins &&
         finite(config.min_intensity) && finite(config.max_intensity) &&
         config.min_intensity >= 0.0 &&
         config.min_intensity < config.max_intensity &&
         config.max_intensity <= 1.0 &&
         finite_positive(config.min_exposure_ratio) &&
         config.min_exposure_ratio > 1.0 &&
         finite(config.max_exposure_ratio) &&
         config.max_exposure_ratio > config.min_exposure_ratio &&
         config.max_exposure_ratio <= 16.0 &&
         finite_positive(config.min_intensity_span) &&
         config.min_intensity_span <= 1.0 &&
         finite(config.smoothness) && config.smoothness >= 0.0 &&
         config.smoothness <= 0.25 &&
         finite_positive(config.learning_rate) &&
         config.learning_rate <= 1.0 &&
         finite(config.min_slope) && config.min_slope >= 0.0 &&
         config.min_slope <= 1.0 &&
         finite_positive(config.convergence_rmse) &&
         config.convergence_rmse <= 1.0 &&
         finite_positive(config.convergence_delta) &&
         config.convergence_delta <= 1.0 &&
         finite_positive(config.max_residual) &&
         config.max_residual <= 1.0 &&
         finite_positive(config.max_rmse) && config.max_rmse <= 1.0 &&
         finite_positive(config.max_cross_validation_residual) &&
         config.max_cross_validation_residual <= 1.0 &&
         finite_positive(config.max_cross_validation_rmse) &&
         config.max_cross_validation_rmse <= 1.0 &&
         finite_positive(config.max_cross_validation_ratio) &&
         config.max_cross_validation_ratio <= 32.0;
}

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

CameraResponseCurveEstimate estimateCameraResponseCurve(
    const std::vector<CameraResponsePair>& samples,
    CameraResponseCurveConfig config) {
  if (!validCurveConfig(config)) {
    throw std::invalid_argument("Invalid camera response curve configuration");
  }
  CameraResponseCurveEstimate result;
  result.sample_count = samples.size();
  result.bin_count = config.bins;
  if (samples.size() < config.min_samples) {
    result.rejection = CameraResponseRejection::kTooFewSamples;
    return result;
  }
  if (samples.size() > config.max_samples) {
    result.rejection = CameraResponseRejection::kTooManySamples;
    return result;
  }

  std::set<std::uint64_t> ids;
  std::set<long long> ratio_bins;
  std::vector<CurveSample> all_samples;
  all_samples.reserve(samples.size());
  double min_intensity_seen = std::numeric_limits<double>::max();
  double max_intensity_seen = std::numeric_limits<double>::lowest();
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
    if (!finite(ratio) || ratio < config.min_exposure_ratio ||
        ratio > config.max_exposure_ratio) {
      result.rejection = CameraResponseRejection::kInvalidExposureRatio;
      return result;
    }
    if (sample.intensity_low < config.min_intensity ||
        sample.intensity_low > config.max_intensity ||
        sample.intensity_high < config.min_intensity ||
        sample.intensity_high > config.max_intensity ||
        sample.intensity_high - sample.intensity_low <= kCurveMinResponseGap) {
      result.rejection = CameraResponseRejection::kInvalidIntensity;
      return result;
    }
    ratio_bins.insert(std::llround(ratio * 1000.0));
    min_intensity_seen = std::min(min_intensity_seen, sample.intensity_low);
    min_intensity_seen = std::min(min_intensity_seen, sample.intensity_high);
    max_intensity_seen = std::max(max_intensity_seen, sample.intensity_low);
    max_intensity_seen = std::max(max_intensity_seen, sample.intensity_high);
    // For a monotonic inverse response g, two exposures of the same static
    // radiance satisfy g(I_low) - g(I_high) = -log(E_high / E_low). Each pair
    // therefore constrains the difference of two interpolated LUT values.
    CurveSample curve_sample;
    curve_sample.target = -std::log(ratio);
    curve_sample.low = mapIntensity(sample.intensity_low, config.bins);
    curve_sample.high = mapIntensity(sample.intensity_high, config.bins);
    all_samples.push_back(curve_sample);
  }
  if (ratio_bins.size() < 2) {
    result.rejection = CameraResponseRejection::kInsufficientRatioDiversity;
    return result;
  }
  if (!finite(max_intensity_seen - min_intensity_seen) ||
      max_intensity_seen - min_intensity_seen < config.min_intensity_span) {
    result.rejection = CameraResponseRejection::kInsufficientIntensitySpan;
    return result;
  }

  std::vector<CurveSample> training;
  std::vector<CurveSample> holdout;
  std::vector<bool> training_bins(config.bins, false);
  for (std::size_t index = 0; index < all_samples.size(); ++index) {
    if (index % config.holdout_stride == 0) {
      holdout.push_back(all_samples[index]);
    } else {
      training.push_back(all_samples[index]);
      for (const auto bin : all_samples[index].low.index) {
        training_bins[bin] = true;
      }
      for (const auto bin : all_samples[index].high.index) {
        training_bins[bin] = true;
      }
    }
  }
  result.training_samples = training.size();
  result.holdout_samples = holdout.size();
  result.covered_bins = static_cast<std::size_t>(
      std::count(training_bins.begin(), training_bins.end(), true));
  if (training.size() < config.min_samples) {
    result.rejection = CameraResponseRejection::kTooFewSamples;
    return result;
  }
  if (holdout.size() < 2) {
    result.rejection = CameraResponseRejection::kInsufficientHoldoutEvidence;
    return result;
  }
  if (result.covered_bins < config.min_covered_bins) {
    result.rejection = CameraResponseRejection::kInsufficientBinCoverage;
    return result;
  }

  std::vector<double> curve(config.bins);
  for (std::size_t i = 0; i < config.bins; ++i) {
    curve[i] = std::log((static_cast<double>(i) + 0.5) /
                        static_cast<double>(config.bins));
  }
  const double initial_offset = curve[0];
  for (double& value : curve) value -= initial_offset;
  projectMonotonic(curve, config.min_slope);

  bool converged = false;
  for (std::size_t iteration = 0; iteration < config.max_iterations;
       ++iteration) {
    const std::vector<double> previous = curve;
    for (const auto& sample : training) {
      const double residual =
          (interpolate(curve, sample.low) - interpolate(curve, sample.high)) -
          sample.target;
      const std::array<std::size_t, 4> indices{
          {sample.low.index[0], sample.low.index[1], sample.high.index[0],
           sample.high.index[1]}};
      const std::array<double, 4> coefficients{
          {sample.low.weight[0], sample.low.weight[1],
           -sample.high.weight[0], -sample.high.weight[1]}};
      double denominator = 0.0;
      for (const double coefficient : coefficients) {
        denominator += coefficient * coefficient;
      }
      if (!(denominator > 1e-12)) continue;
      const double step = config.learning_rate * residual / denominator;
      for (std::size_t k = 0; k < indices.size(); ++k) {
        double& value = curve[indices[k]];
        value -= step * coefficients[k];
        if (!finite(value)) value = previous[indices[k]];
        value = std::max(-kCurveValueLimit,
                         std::min(kCurveValueLimit, value));
      }
    }
    std::vector<double> smoothed(curve);
    for (std::size_t i = 1; i + 1 < config.bins; ++i) {
      smoothed[i] = (1.0 - config.smoothness) * curve[i] +
                    config.smoothness * 0.5 * (curve[i - 1] + curve[i + 1]);
    }
    curve = std::move(smoothed);
    if (projectMonotonic(curve, config.min_slope)) {
      ++result.monotonic_projections;
    }
    double delta = 0.0;
    for (std::size_t i = 0; i < config.bins; ++i) {
      delta = std::max(delta, std::fabs(curve[i] - previous[i]));
    }
    const CurveMetrics metrics = evaluateCurve(curve, training);
    result.iterations = iteration + 1;
    if ((finite(metrics.rmse) && metrics.rmse <= config.convergence_rmse) ||
        (finite(delta) && delta <= config.convergence_delta)) {
      converged = true;
      break;
    }
  }
  result.solver_converged = converged;
  if (!converged) {
    result.rejection = CameraResponseRejection::kSolverDidNotConverge;
    return result;
  }

  bool monotonic = finite(curve[0]) && std::fabs(curve[0]) <= 1e-9;
  for (std::size_t i = 1; i < config.bins; ++i) {
    if (!finite(curve[i]) ||
        curve[i] < curve[i - 1] + config.min_slope - 1e-9) {
      monotonic = false;
      break;
    }
  }
  if (!monotonic) {
    result.rejection = CameraResponseRejection::kNonMonotonicResponse;
    return result;
  }

  const CurveMetrics training_metrics = evaluateCurve(curve, training);
  const CurveMetrics holdout_metrics = evaluateCurve(curve, holdout);
  result.max_residual = training_metrics.max_residual;
  result.rmse = training_metrics.rmse;
  result.cross_validation_rmse = holdout_metrics.rmse;
  result.max_cross_validation_residual = holdout_metrics.max_residual;
  result.cross_validation_ratio = holdout_metrics.rmse /
      std::max(training_metrics.rmse, 1e-6);
  if (!finite(result.max_residual) ||
      result.max_residual > config.max_residual) {
    result.rejection = CameraResponseRejection::kCurveResidualTooLarge;
    return result;
  }
  if (!finite(result.rmse) || result.rmse > config.max_rmse) {
    result.rejection = CameraResponseRejection::kCurveRmseTooLarge;
    return result;
  }
  if (!finite(result.max_cross_validation_residual) ||
      result.max_cross_validation_residual >
          config.max_cross_validation_residual) {
    result.rejection = CameraResponseRejection::kCrossValidationResidualTooLarge;
    return result;
  }
  if (!finite(result.cross_validation_rmse) ||
      result.cross_validation_rmse > config.max_cross_validation_rmse) {
    result.rejection = CameraResponseRejection::kCrossValidationRmseTooLarge;
    return result;
  }
  if (!finite(result.cross_validation_ratio) ||
      result.cross_validation_ratio > config.max_cross_validation_ratio) {
    result.rejection = CameraResponseRejection::kCrossValidationRatioTooLarge;
    return result;
  }
  result.inverse_response = curve;
  result.accepted = true;
  result.rejection = CameraResponseRejection::kNone;
  result.quality_grade = "unverified";
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
    case CameraResponseRejection::kInsufficientBinCoverage:
      return "insufficient response bin coverage";
    case CameraResponseRejection::kInsufficientHoldoutEvidence:
      return "insufficient holdout evidence";
    case CameraResponseRejection::kNonMonotonicResponse:
      return "non-monotonic response estimate";
    case CameraResponseRejection::kCurveResidualTooLarge:
      return "response curve residual too large";
    case CameraResponseRejection::kCurveRmseTooLarge:
      return "response curve rmse too large";
    case CameraResponseRejection::kCrossValidationResidualTooLarge:
      return "cross-validation residual too large";
    case CameraResponseRejection::kCrossValidationRmseTooLarge:
      return "cross-validation rmse too large";
    case CameraResponseRejection::kCrossValidationRatioTooLarge:
      return "cross-validation ratio too large";
    case CameraResponseRejection::kSolverDidNotConverge:
      return "response curve solver did not converge";
  }
  return "unknown rejection";
}

}  // namespace arkernel
