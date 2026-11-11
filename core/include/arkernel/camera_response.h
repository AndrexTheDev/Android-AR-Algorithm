#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace arkernel {

/**
 * One paired-exposure observation of the same static radiance. Intensities are
 * normalized linear pixel values in [0, 1]; this is synthetic consistency
 * evidence, not a physical camera calibration or a reconstruction quality
 * grade.
 */
struct CameraResponsePair {
  std::uint64_t id{0};
  double exposure_low_s{0.0};
  double exposure_high_s{0.0};
  double intensity_low{0.0};
  double intensity_high{0.0};
};

/** Stable fail-closed rejection reason for the bounded response estimators. */
enum class CameraResponseRejection {
  kNone,
  kTooFewSamples,
  kTooManySamples,
  kInvalidId,
  kDuplicateId,
  kNonFiniteInput,
  kInvalidExposure,
  kInvalidExposureRatio,
  kInvalidIntensity,
  kInsufficientRatioDiversity,
  kInsufficientIntensitySpan,
  kInconsistentBlackLevel,
  kNonPositiveSlope,
  kResidualTooLarge,
  kRmseTooLarge,
  kInsufficientBinCoverage,
  kInsufficientHoldoutEvidence,
  kNonMonotonicResponse,
  kCurveResidualTooLarge,
  kCurveRmseTooLarge,
  kCrossValidationResidualTooLarge,
  kCrossValidationRmseTooLarge,
  kCrossValidationRatioTooLarge,
  kSolverDidNotConverge,
};

/**
 * Bounded deterministic estimate of a linear black-level camera response.
 * `accepted` is true only when every budget, diversity, black-level, slope and
 * residual gate passes. The result never upgrades the explicit `unverified`
 * quality contract.
 */
struct CameraResponseEstimate {
  bool accepted{false};
  CameraResponseRejection rejection{CameraResponseRejection::kTooFewSamples};
  std::size_t sample_count{0};
  double black_level{0.0};
  double min_slope{0.0};
  double max_slope{0.0};
  double max_residual{0.0};
  double rmse{0.0};
  const char* quality_grade{"unverified"};
};

/**
 * Bounded configuration for a deterministic nonlinear inverse-response curve.
 * All work and acceptance budgets are explicit; invalid configurations throw
 * std::invalid_argument rather than silently weakening a gate.
 */
struct CameraResponseCurveConfig {
  std::size_t bins{64};
  std::size_t min_samples{12};
  std::size_t max_samples{256};
  std::size_t max_iterations{256};
  std::size_t holdout_stride{5};
  std::size_t min_covered_bins{8};
  double min_intensity{0.02};
  double max_intensity{0.98};
  double min_exposure_ratio{1.10};
  double max_exposure_ratio{8.0};
  double min_intensity_span{0.30};
  double smoothness{0.02};
  double learning_rate{0.5};
  double min_slope{1e-4};
  double convergence_rmse{0.005};
  double convergence_delta{1e-7};
  double max_residual{0.05};
  double max_rmse{0.02};
  double max_cross_validation_residual{0.06};
  double max_cross_validation_rmse{0.03};
  double max_cross_validation_ratio{4.0};
};

/**
 * Bounded deterministic estimate of a monotonic piecewise-linear inverse
 * camera response. Paired-exposure equations constrain the difference of
 * log-irradiance at two intensity bins; smoothness and isotonic projection keep
 * the LUT monotonic. A deterministic holdout split provides cross-validation.
 * The estimate is synthetic consistency evidence only: it is not a physical
 * camera calibration, a full response model or a reconstruction quality grade.
 */
struct CameraResponseCurveEstimate {
  bool accepted{false};
  bool solver_converged{false};
  CameraResponseRejection rejection{CameraResponseRejection::kTooFewSamples};
  std::size_t sample_count{0};
  std::size_t training_samples{0};
  std::size_t holdout_samples{0};
  std::size_t bin_count{0};
  std::size_t covered_bins{0};
  std::size_t iterations{0};
  std::size_t monotonic_projections{0};
  double max_residual{0.0};
  double rmse{0.0};
  double cross_validation_rmse{0.0};
  double max_cross_validation_residual{0.0};
  double cross_validation_ratio{0.0};
  // Log-irradiance LUT anchored at zero; empty unless accepted.
  std::vector<double> inverse_response;
  const char* quality_grade{"unverified"};
};

CameraResponseEstimate estimateCameraResponseLinearity(
    const std::vector<CameraResponsePair>& samples);

CameraResponseCurveEstimate estimateCameraResponseCurve(
    const std::vector<CameraResponsePair>& samples,
    CameraResponseCurveConfig config = {});

const char* cameraResponseRejectionText(CameraResponseRejection rejection);

}  // namespace arkernel
