#pragma once

#include <cstddef>
#include <string>

#include "arkernel/reconstruction_asset.h"

namespace arkernel {

struct MeshComponentFilterConfig {
  std::size_t min_component_triangles{4};
  double min_component_area_m2{1e-6};
  double min_triangle_area_m2{1e-12};
  bool keep_largest_only{false};
  std::size_t max_input_vertices{1000000};
  std::size_t max_input_triangles{2000000};
  std::size_t max_unique_edges{3000000};
};

struct MeshComponentFilterResult {
  ReconstructionMesh mesh;
  std::size_t input_vertices{0};
  std::size_t input_triangles{0};
  std::size_t components{0};
  std::size_t retained_components{0};
  std::size_t removed_triangles{0};
  std::size_t removed_vertices{0};
  std::size_t degenerate_triangles{0};
  std::size_t boundary_edges{0};
  std::size_t manifold_edges{0};
  std::size_t non_manifold_edges{0};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Filters triangle islands connected by shared edges and compacts all indexed
// vertex attributes deterministically. Point-only contact does not merge two
// components. Boundary/non-manifold counts describe the retained output.
MeshComponentFilterResult filterMeshComponents(
    const ReconstructionMesh& input,
    MeshComponentFilterConfig config = {});

struct MeshSimplificationConfig {
  double cluster_cell_size_m{0.03};
  double max_position_error_m{0.02};
  double sharp_edge_angle_degrees{35.0};
  double max_uv_error{0.01};
  double min_triangle_area_m2{1e-12};
  bool preserve_boundaries{true};
  bool preserve_non_manifold{true};
  bool preserve_sharp_edges{true};
  bool preserve_uv_seams{true};
  std::size_t max_input_vertices{1000000};
  std::size_t max_input_triangles{2000000};
  std::size_t max_unique_edges{3000000};
  std::size_t max_clusters{1000000};
};

struct MeshSimplificationResult {
  ReconstructionMesh mesh;
  std::size_t input_vertices{0};
  std::size_t input_triangles{0};
  std::size_t output_vertices{0};
  std::size_t output_triangles{0};
  std::size_t locked_vertices{0};
  std::size_t boundary_edges{0};
  std::size_t sharp_edges{0};
  std::size_t non_manifold_edges{0};
  std::size_t rejected_merges{0};
  std::size_t removed_degenerate_triangles{0};
  std::size_t removed_duplicate_triangles{0};
  double max_observed_displacement_m{0.0};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Conservative deterministic vertex clustering. Feature vertices remain exact;
// only smooth interior vertices in the same edge-connected component may merge
// within position, normal and UV error bounds.
MeshSimplificationResult simplifyMeshConservative(
    const ReconstructionMesh& input,
    MeshSimplificationConfig config = {});

struct MeshQuadricLodConfig {
  // Plane quadrics are in metres; the error is the sum of squared distances
  // from the cluster representative to all incident triangle planes.
  double cluster_cell_size_m{0.03};
  double max_cluster_quadric_error_m2{1e-4};
  double max_displacement_m{0.02};
  double sharp_edge_angle_degrees{35.0};
  double max_uv_error{0.01};
  double min_triangle_area_m2{1e-12};
  double regularization{1e-9};
  bool preserve_boundaries{true};
  bool preserve_non_manifold{true};
  bool preserve_sharp_edges{true};
  bool preserve_uv_seams{true};
  std::size_t max_input_vertices{1000000};
  std::size_t max_input_triangles{2000000};
  std::size_t max_unique_edges{3000000};
  std::size_t max_clusters{1000000};
  std::size_t max_evaluations{10000000};
};

struct MeshQuadricLodResult {
  ReconstructionMesh mesh;
  std::size_t input_vertices{0};
  std::size_t input_triangles{0};
  std::size_t output_vertices{0};
  std::size_t output_triangles{0};
  std::size_t locked_vertices{0};
  std::size_t boundary_edges{0};
  std::size_t sharp_edges{0};
  std::size_t non_manifold_edges{0};
  std::size_t candidate_clusters{0};
  std::size_t accepted_clusters{0};
  std::size_t rejected_clusters{0};
  std::size_t removed_degenerate_triangles{0};
  std::size_t removed_duplicate_triangles{0};
  double initial_quadric_error_m2{0.0};
  double final_quadric_error_m2{0.0};
  double max_observed_displacement_m{0.0};
  bool input_valid{false};
  bool truncated{false};
  std::string error;
};

// Bounded deterministic quadric-error-guided vertex clustering. Each cluster
// representative minimizes the summed incident-plane quadric, is clamped to
// the cluster bounding box, and is accepted only under measured quadric,
// displacement, normal and UV bounds. Feature locks are preserved. This is a
// measured LOD, not a dynamic edge-collapse QEM topology optimizer.
MeshQuadricLodResult simplifyMeshQuadricError(
    const ReconstructionMesh& input,
    MeshQuadricLodConfig config = {});

}  // namespace arkernel
