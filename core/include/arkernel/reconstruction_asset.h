#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "arkernel/types.h"

namespace arkernel {

enum class ScanMode { kEnvironment, kObject };
enum class MetricAccuracyGrade { kUnverified, kApproximate, kCentimeterValidated };
enum class DimensionalReferenceKind {
  kUnknown,
  kHeldOutScaleBar,
  kSurveyedDistance,
  kSensorGroundTruth,
};

struct HeldOutDimensionObservation {
  std::string evidence_id;
  Vec3 reconstructed_a_m{};
  Vec3 reconstructed_b_m{};
  double reference_distance_m{0.0};
  double reference_uncertainty_m{0.0};
  DimensionalReferenceKind reference_kind{DimensionalReferenceKind::kUnknown};
  // Must be true only when this target/pair influenced neither calibration nor optimization.
  bool held_out_from_calibration_and_optimization{false};
};

struct DimensionalValidationPolicy {
  std::size_t max_observations{64};
  std::size_t min_observations{3};
  std::size_t min_dimension_bins{2};
  double dimension_bin_width_m{0.25};
  double max_reference_uncertainty_m{0.002};
  double max_mean_absolute_error_m{0.01};
  double max_absolute_error_m{0.01};
  double max_relative_error{0.02};
};

struct DimensionalValidationReport {
  bool passed{false};
  std::size_t accepted_observations{0};
  std::size_t dimension_bins{0};
  double mean_absolute_error_m{0.0};
  double maximum_effective_absolute_error_m{0.0};
  double maximum_effective_relative_error{0.0};
  double maximum_validated_dimension_m{0.0};
  std::vector<std::string> failures;
};

DimensionalValidationReport evaluateHeldOutDimensions(
    const std::vector<HeldOutDimensionObservation>& observations,
    const DimensionalValidationPolicy& policy = {});

struct ReconstructionQuality {
  bool calibrated_intrinsics{false};
  bool calibrated_camera_imu{false};
  bool metric_scale_observed{false};
  bool globally_optimized{false};
  double reprojection_rmse_px{0.0};
  double depth_rmse_m{0.0};
  double scale_stddev_ratio{0.0};
  // Largest validated dimension of the reconstructed asset. Combined with
  // scale uncertainty, this bounds absolute dimensional error.
  double assessed_extent_m{0.0};
  double surface_coverage_ratio{0.0};
  std::vector<HeldOutDimensionObservation> held_out_dimensions;
};

struct AccuracyPolicy {
  double max_reprojection_rmse_px{1.0};
  double max_depth_rmse_m{0.01};
  double max_scale_stddev_ratio{0.005};
  double max_absolute_scale_error_m{0.01};
  double min_environment_coverage_ratio{0.70};
  double min_object_coverage_ratio{0.85};
  DimensionalValidationPolicy dimensional_validation{};
};

struct AccuracyAssessment {
  MetricAccuracyGrade grade{MetricAccuracyGrade::kUnverified};
  bool export_allowed{false};
  DimensionalValidationReport dimensional_validation{};
  std::vector<std::string> failures;
};

AccuracyAssessment assessReconstructionQuality(
    const ReconstructionQuality& quality, ScanMode mode,
    const AccuracyPolicy& policy = {});

struct Vec2f { float x{0.0F}; float y{0.0F}; };
struct Vec3f { float x{0.0F}; float y{0.0F}; float z{0.0F}; };

struct TextureReference {
  std::string uri;
  std::string mime_type{"image/png"};
};

// Indexed triangle mesh in meters. UV origin follows glTF (top-left image data;
// V coordinates are not silently flipped by the exporter).
struct ReconstructionMesh {
  std::vector<Vec3f> positions_m;
  std::vector<Vec3f> normals;
  std::vector<Vec2f> texcoords;
  std::vector<std::uint32_t> triangle_indices;
  TextureReference base_color_texture;
  std::string name{"AR reconstruction"};
};

struct MeshValidationLimits {
  std::size_t max_vertices{5000000};
  std::size_t max_triangles{10000000};
};

struct MeshValidationResult {
  bool valid{false};
  std::string error;
};

MeshValidationResult validateMesh(const ReconstructionMesh& mesh,
                                  const MeshValidationLimits& limits = {});

// Dependency-free glTF 2.0 output. JSON references bin_file_name and the
// optional texture URI. The binary contains aligned POSITION, NORMAL,
// TEXCOORD_0 and uint32 index streams.
struct GltfAsset {
  std::string json;
  std::vector<std::uint8_t> binary;
};

GltfAsset exportGltf(const ReconstructionMesh& mesh,
                     const std::string& bin_file_name = "scene.bin");
// glTF Binary 2.0 container. External texture URIs remain external; callers
// can use data URIs or a later image-embedding adapter when one-file textures
// are required.
std::vector<std::uint8_t> exportGlb(const ReconstructionMesh& mesh);
std::string exportObj(const ReconstructionMesh& mesh,
                      const std::string& material_file_name = "scene.mtl");
std::string exportMtl(const ReconstructionMesh& mesh);
std::string exportPly(const ReconstructionMesh& mesh);

}  // namespace arkernel
