#include "arkernel/reconstruction_pipeline.h"

#include <limits>
#include <stdexcept>
#include <utility>

namespace arkernel {
namespace {

bool checkedAdd(std::size_t value, std::size_t* total) {
  if (value > std::numeric_limits<std::size_t>::max() - *total) return false;
  *total += value;
  return true;
}

}  // namespace

ReconstructionFusionPipeline::ReconstructionFusionPipeline(
    ReconstructionPipelineConfig config)
    : config_(std::move(config)), volume_(config_.fusion) {
  if (config_.max_integrated_frames == 0 ||
      config_.max_integrated_frames > 1000000) {
    throw std::invalid_argument("Invalid reconstruction frame budget");
  }
  // Reuse each independent stage's validation at construction, before a long
  // capture can end with a configuration error. Empty-input results are ignored.
  const ReconstructionMesh empty_mesh;
  (void)extractTsdfMesh(volume_, config_.meshing);
  (void)filterMeshComponents(empty_mesh, config_.component_filter);
  (void)simplifyMeshConservative(empty_mesh, config_.simplification);
  if (config_.enable_quadric_error_lod) {
    (void)simplifyMeshQuadricError(empty_mesh, config_.quadric_lod);
  }
  if (config_.enable_edge_collapse_qem) {
    (void)simplifyMeshEdgeCollapseQem(empty_mesh, config_.edge_collapse_qem);
  }
}

ReconstructionIntegrationResult ReconstructionFusionPipeline::status(
    ReconstructionIntegrationRejection rejection,
    const SparseTsdfUpdate& update) const {
  ReconstructionIntegrationResult result;
  result.update = update;
  result.rejection = rejection;
  result.accepted = rejection == ReconstructionIntegrationRejection::kNone &&
                    update.input_valid;
  result.accepted_frames = accepted_frames_;
  result.rejected_frames = rejected_frames_;
  result.cumulative_integrated_pixels = cumulative_integrated_pixels_;
  result.cumulative_voxel_updates = cumulative_voxel_updates_;
  result.fusion_truncated = fusion_truncated_;
  return result;
}

ReconstructionIntegrationResult ReconstructionFusionPipeline::integrate(
    std::int64_t timestamp_ns, const TsdfDepthImageView& depth,
    const PinholeRadTanCamera& camera, const Pose& world_from_camera) {
  if (timestamp_ns <= 0) {
    ++rejected_frames_;
    return status(ReconstructionIntegrationRejection::kInvalidTimestamp);
  }
  if (last_timestamp_ns_ > 0 && timestamp_ns <= last_timestamp_ns_) {
    ++rejected_frames_;
    return status(ReconstructionIntegrationRejection::kNonMonotonicTimestamp);
  }
  if (accepted_frames_ >= config_.max_integrated_frames) {
    ++rejected_frames_;
    return status(ReconstructionIntegrationRejection::kFrameBudgetExceeded);
  }

  const SparseTsdfUpdate update = volume_.integrate(
      depth, camera, world_from_camera);
  if (!update.input_valid) {
    ++rejected_frames_;
    return status(ReconstructionIntegrationRejection::kInvalidDepthInput, update);
  }

  // A valid frame consumes its timestamp and frame budget even if no pixel
  // passed confidence/depth gates. Replaying it later must not mutate state.
  last_timestamp_ns_ = timestamp_ns;
  ++accepted_frames_;
  fusion_truncated_ = fusion_truncated_ || update.truncated;
  if (!checkedAdd(update.integrated_pixels, &cumulative_integrated_pixels_) ||
      !checkedAdd(update.voxel_updates, &cumulative_voxel_updates_)) {
    fusion_truncated_ = true;
  }
  return status(ReconstructionIntegrationRejection::kNone, update);
}

ReconstructionBuildResult ReconstructionFusionPipeline::build() const {
  ReconstructionBuildResult result;
  result.telemetry.fused_frames = accepted_frames_;
  result.telemetry.fused_blocks = volume_.blockCount();
  result.telemetry.fused_voxels = volume_.voxelCount();
  result.telemetry.fusion_truncated = fusion_truncated_;

  TsdfMeshingResult meshing = extractTsdfMesh(volume_, config_.meshing);
  result.telemetry.meshing_input_voxels = meshing.input_voxels;
  result.telemetry.meshing_examined_cells = meshing.examined_cells;
  result.telemetry.meshing_complete_cells = meshing.complete_cells;
  result.telemetry.meshing_surface_cells = meshing.surface_cells;
  result.telemetry.meshing_skipped_incomplete_cells =
      meshing.skipped_incomplete_cells;
  result.telemetry.meshing_truncated = meshing.truncated;
  if (!meshing.input_valid) {
    result.error = "TSDF meshing input is invalid";
    return result;
  }

  MeshComponentFilterResult filtered = filterMeshComponents(
      meshing.mesh, config_.component_filter);
  result.telemetry.source_components = filtered.components;
  result.telemetry.retained_components = filtered.retained_components;
  result.telemetry.filtering_truncated = filtered.truncated;
  if (!filtered.input_valid || !filtered.error.empty()) {
    result.error = filtered.error.empty() ?
        "Extracted mesh is invalid" : filtered.error;
    return result;
  }
  result.source_mesh = std::move(filtered.mesh);
  result.telemetry.source_vertices = result.source_mesh.positions_m.size();
  result.telemetry.source_triangles =
      result.source_mesh.triangle_indices.size() / 3;

  MeshSimplificationResult simplified = simplifyMeshConservative(
      result.source_mesh, config_.simplification);
  result.telemetry.simplification_truncated = simplified.truncated;
  result.telemetry.max_simplification_displacement_m =
      simplified.max_observed_displacement_m;
  if (!simplified.input_valid || !simplified.error.empty()) {
    result.error = simplified.error.empty() ?
        "Filtered source mesh is invalid" : simplified.error;
    return result;
  }
  result.simplified_mesh = std::move(simplified.mesh);
  result.telemetry.simplified_vertices =
      result.simplified_mesh.positions_m.size();
  result.telemetry.simplified_triangles =
      result.simplified_mesh.triangle_indices.size() / 3;
  if (config_.enable_quadric_error_lod) {
    MeshQuadricLodResult lod = simplifyMeshQuadricError(
        result.simplified_mesh, config_.quadric_lod);
    result.telemetry.quadric_lod_enabled = true;
    result.telemetry.quadric_lod_truncated = lod.truncated;
    result.telemetry.quadric_lod_input_vertices = lod.input_vertices;
    result.telemetry.quadric_lod_output_vertices = lod.output_vertices;
    result.telemetry.quadric_lod_output_triangles = lod.output_triangles;
    result.telemetry.quadric_lod_accepted_clusters = lod.accepted_clusters;
    result.telemetry.quadric_lod_rejected_clusters = lod.rejected_clusters;
    result.telemetry.quadric_lod_locked_vertices = lod.locked_vertices;
    result.telemetry.quadric_lod_removed_degenerate_triangles =
        lod.removed_degenerate_triangles;
    result.telemetry.quadric_lod_initial_error_m2 =
        lod.initial_quadric_error_m2;
    result.telemetry.quadric_lod_final_error_m2 = lod.final_quadric_error_m2;
    result.telemetry.quadric_lod_max_displacement_m =
        lod.max_observed_displacement_m;
    if (!lod.input_valid || !lod.error.empty() || lod.truncated) {
      result.error = lod.error.empty() ?
          "Quadric-error LOD did not complete" : lod.error;
      return result;
    }
    result.simplified_mesh = std::move(lod.mesh);
    result.telemetry.simplified_vertices =
        result.simplified_mesh.positions_m.size();
    result.telemetry.simplified_triangles =
        result.simplified_mesh.triangle_indices.size() / 3;
  }
  if (config_.enable_edge_collapse_qem) {
    MeshEdgeCollapseQemResult qem = simplifyMeshEdgeCollapseQem(
        result.simplified_mesh, config_.edge_collapse_qem);
    result.telemetry.edge_collapse_qem_enabled = true;
    result.telemetry.edge_collapse_qem_truncated = qem.truncated;
    result.telemetry.edge_collapse_qem_input_vertices = qem.input_vertices;
    result.telemetry.edge_collapse_qem_output_vertices =
        qem.output_vertices;
    result.telemetry.edge_collapse_qem_output_triangles =
        qem.output_triangles;
    result.telemetry.edge_collapse_qem_accepted_collapses =
        qem.accepted_collapses;
    result.telemetry.edge_collapse_qem_rejected_collapses =
        qem.rejected_collapses;
    result.telemetry.edge_collapse_qem_evaluated_collapses =
        qem.evaluated_collapses;
    result.telemetry.edge_collapse_qem_locked_vertices =
        qem.locked_vertices;
    result.telemetry.edge_collapse_qem_removed_degenerate_triangles =
        qem.removed_degenerate_triangles;
    result.telemetry.edge_collapse_qem_removed_duplicate_triangles =
        qem.removed_duplicate_triangles;
    result.telemetry.edge_collapse_qem_initial_error_m2 =
        qem.initial_quadric_error_m2;
    result.telemetry.edge_collapse_qem_final_error_m2 =
        qem.final_quadric_error_m2;
    result.telemetry.edge_collapse_qem_max_displacement_m =
        qem.max_observed_displacement_m;
    result.telemetry.edge_collapse_qem_max_uv_error =
        qem.max_observed_uv_error;
    if (!qem.input_valid || !qem.error.empty() || qem.truncated) {
      result.error = qem.error.empty() ?
          "Edge-collapse QEM did not complete" : qem.error;
      return result;
    }
    result.simplified_mesh = std::move(qem.mesh);
    result.telemetry.simplified_vertices =
        result.simplified_mesh.positions_m.size();
    result.telemetry.simplified_triangles =
        result.simplified_mesh.triangle_indices.size() / 3;
  }
  result.telemetry.complete = !result.telemetry.fusion_truncated &&
      !result.telemetry.meshing_truncated &&
      !result.telemetry.filtering_truncated &&
      !result.telemetry.simplification_truncated &&
      !result.telemetry.quadric_lod_truncated &&
      !result.telemetry.edge_collapse_qem_truncated;
  return result;
}

void ReconstructionFusionPipeline::reset() {
  volume_.reset();
  last_timestamp_ns_ = 0;
  accepted_frames_ = 0;
  rejected_frames_ = 0;
  cumulative_integrated_pixels_ = 0;
  cumulative_voxel_updates_ = 0;
  fusion_truncated_ = false;
}

}  // namespace arkernel
