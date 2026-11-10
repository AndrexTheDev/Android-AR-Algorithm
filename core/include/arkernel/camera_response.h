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

/** Stable fail-closed rejection reason for the bounded linearity estimator. */
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

CameraResponseEstimate estimateCameraResponseLinearity(
    const std::vector<CameraResponsePair>& samples);

const char* cameraResponseRejectionText(CameraResponseRejection rejection);

}  // namespace arkernel
