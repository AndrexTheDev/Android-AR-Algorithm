#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "arkernel/mesh_processing.h"
#include "arkernel/sparse_tsdf.h"
#include "arkernel/tsdf_meshing.h"

namespace arkernel {

enum class ReconstructionIntegrationRejection {
  kNone,
  kInvalidTimestamp,
  kNonMonotonicTimestamp,
  kFrameBudgetExceeded,
  kInvalidDepthInput,
};

struct ReconstructionPipelineConfig {
  SparseTsdfConfig fusion{};
  TsdfMeshingConfig meshing{};
  MeshComponentFilterConfig component_filter{};
  MeshSimplificationConfig simplification{};
  std::size_t max_integrated_frames{2000};
};

struct ReconstructionIntegrationResult {
  SparseTsdfUpdate update{};
  ReconstructionIntegrationRejection rejection{
      ReconstructionIntegrationRejection::kNone};
  std::size_t accepted_frames{0};
  std::size_t rejected_frames{0};
  std::size_t cumulative_integrated_pixels{0};
  std::size_t cumulative_voxel_updates{0};
  bool accepted{false};
  bool fusion_truncated{false};
};

struct ReconstructionBuildTelemetry {
  std::size_t fused_frames{0};
  std::size_t fused_blocks{0};
  std::size_t fused_voxels{0};
  std::size_t meshing_input_voxels{0};
  std::size_t meshing_examined_cells{0};
  std::size_t meshing_complete_cells{0};
  std::size_t meshing_surface_cells{0};
  std::size_t meshing_skipped_incomplete_cells{0};
  std::size_t source_components{0};
  std::size_t retained_components{0};
  std::size_t source_vertices{0};
  std::size_t source_triangles{0};
  std::size_t simplified_vertices{0};
  std::size_t simplified_triangles{0};
  double max_simplification_displacement_m{0.0};
  bool fusion_truncated{false};
  bool meshing_truncated{false};
  bool filtering_truncated{false};
  bool simplification_truncated{false};
  bool complete{false};
};

struct ReconstructionBuildResult {
  // Filtered metric source representation. This remains independent from and
  // is never overwritten by the simplified delivery representation.
  ReconstructionMesh source_mesh;
  ReconstructionMesh simplified_mesh;
  ReconstructionBuildTelemetry telemetry{};
  std::string error;
};

// Stateful bounded fusion owner for Android and portable hosts. It does not
// estimate depth, choose an inference runtime, fill unknown space, or claim
// watertightness. Every accepted depth frame must have a strictly increasing
// hardware-domain timestamp and a pose in the same metric world frame.
class ReconstructionFusionPipeline {
 public:
  explicit ReconstructionFusionPipeline(ReconstructionPipelineConfig config = {});

  ReconstructionIntegrationResult integrate(
      std::int64_t timestamp_ns, const TsdfDepthImageView& depth,
      const PinholeRadTanCamera& camera, const Pose& world_from_camera);
  ReconstructionBuildResult build() const;
  void reset();

  std::size_t acceptedFrames() const { return accepted_frames_; }
  std::size_t rejectedFrames() const { return rejected_frames_; }
  bool fusionTruncated() const { return fusion_truncated_; }

 private:
  ReconstructionIntegrationResult status(
      ReconstructionIntegrationRejection rejection,
      const SparseTsdfUpdate& update = {}) const;

  ReconstructionPipelineConfig config_;
  SparseTsdfVolume volume_;
  std::int64_t last_timestamp_ns_{0};
  std::size_t accepted_frames_{0};
  std::size_t rejected_frames_{0};
  std::size_t cumulative_integrated_pixels_{0};
  std::size_t cumulative_voxel_updates_{0};
  bool fusion_truncated_{false};
};

}  // namespace arkernel
