#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "arkernel/camera_model.h"
#include "arkernel/reconstruction_asset.h"
#include "arkernel/sparse_tsdf.h"

namespace arkernel {

struct TextureViewCandidate {
  std::uint64_t frame_id{0};
  std::string image_reference;
  const PinholeRadTanCamera* camera{nullptr};
  Pose world_from_camera{};
  double quality_score{0.0};
  // Optional optical-axis Z-depth aligned to camera. Memory is borrowed only
  // for selectTextureViews(). Unknown/non-finite samples remain unknown.
  const TsdfDepthImageView* visibility_depth{nullptr};
};

struct TextureViewSelectionConfig {
  double min_facing_cosine{0.20};
  double min_projected_area_px2{1.0};
  double image_border_px{1.0};
  double max_absolute_depth_error_m{0.03};
  double max_relative_depth_error{0.02};
  double uncertainty_sigma{2.0};
  double default_depth_uncertainty_m{0.03};
  double min_depth_confidence{0.10};
  double target_projected_edge_px{32.0};
  double quality_weight{0.35};
  double facing_weight{0.35};
  double resolution_weight{0.30};
  bool require_depth_visibility{true};
  int depth_search_radius_px{1};
  std::size_t min_consistent_depth_samples{1};
  std::size_t max_candidates{256};
  std::size_t max_image_reference_bytes{1024};
  std::size_t max_vertices{1000000};
  std::size_t max_triangles{2000000};
  std::size_t max_projection_tests{16000000};
  std::size_t max_alternatives_per_triangle{4};
  std::size_t max_alternative_records{1000000};
};

struct TextureViewReference {
  std::uint64_t frame_id{0};
  std::string image_reference;
};

struct TextureViewAlternative {
  std::size_t view_index{0};
  std::uint64_t frame_id{0};
  std::array<Vec2f, 3> normalized_uv{};
  double score{0.0};
  double facing_cosine{0.0};
  double projected_area_px2{0.0};
};

struct TriangleTextureView {
  bool assigned{false};
  // Index into TextureViewSelectionResult::views.
  std::size_t view_index{0};
  std::uint64_t frame_id{0};
  std::array<Vec2f, 3> normalized_uv{};
  double score{0.0};
  double facing_cosine{0.0};
  double projected_area_px2{0.0};
  // Range in TextureViewSelectionResult::alternatives. Every record in this
  // range passed all geometric/depth gates for this triangle.
  std::size_t alternative_offset{0};
  std::size_t alternative_count{0};
};

struct TextureViewSelectionResult {
  // Unique references stored once in ascending frame-ID order. Triangle
  // assignments index this table rather than duplicating strings per face.
  std::vector<TextureViewReference> views;
  std::vector<TriangleTextureView> triangles;
  std::vector<TextureViewAlternative> alternatives;
  std::size_t candidates{0};
  std::size_t projection_tests{0};
  std::size_t assigned_triangles{0};
  std::size_t unassigned_triangles{0};
  std::size_t rejected_back_facing{0};
  std::size_t rejected_outside_image{0};
  std::size_t rejected_small_projection{0};
  std::size_t rejected_missing_depth{0};
  std::size_t rejected_depth_inconsistent{0};
  bool input_valid{false};
  bool truncated{false};
  bool alternatives_truncated{false};
  std::string error;
};

// Selects one source frame per triangle. This stage does not build an atlas,
// blend pixels, compensate exposure, or mutate the mesh. Candidate order does
// not affect output: valid unique frame IDs are evaluated in ascending order.
TextureViewSelectionResult selectTextureViews(
    const ReconstructionMesh& mesh,
    const std::vector<TextureViewCandidate>& candidates,
    TextureViewSelectionConfig config = {});

struct TextureLabelOptimizationConfig {
  double data_weight{1.0};
  double seam_penalty{0.15};
  double max_normal_angle_degrees{75.0};
  std::size_t max_iterations{8};
  std::size_t max_triangles{2000000};
  std::size_t max_unique_edges{3000000};
  std::size_t max_label_evaluations{32000000};
};

struct TextureLabelOptimizationResult {
  std::vector<TriangleTextureView> triangles;
  std::size_t iterations{0};
  std::size_t changed_labels{0};
  std::size_t seam_edges_before{0};
  std::size_t seam_edges_after{0};
  std::size_t patches_before{0};
  std::size_t patches_after{0};
  std::size_t non_manifold_edges{0};
  std::size_t sharp_edges{0};
  std::size_t label_evaluations{0};
  bool input_valid{false};
  bool truncated{false};
  bool source_alternatives_truncated{false};
  std::string error;
};

// Deterministic bounded ICM labeling over shared manifold edges. It may choose
// only alternatives that already passed selectTextureViews visibility gates.
// Non-manifold edges do not connect patches and unassigned faces remain so.
TextureLabelOptimizationResult optimizeTextureViewLabels(
    const ReconstructionMesh& mesh,
    const TextureViewSelectionResult& selection,
    TextureLabelOptimizationConfig config = {});

}  // namespace arkernel
