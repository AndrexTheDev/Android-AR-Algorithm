#include "arkernel/texture_view_selection.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <unordered_set>

namespace arkernel {
namespace {

bool finiteVec3(const Vec3& value) {
  return std::isfinite(value.x) && std::isfinite(value.y) &&
         std::isfinite(value.z);
}

bool validPose(const Pose& pose) {
  const auto& q = pose.rotation;
  const double norm_squared = q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z;
  return finiteVec3(pose.translation) && std::isfinite(norm_squared) &&
         norm_squared > 1e-18;
}

bool planeStorageValid(int width, int height, int stride,
                       const float* data, std::size_t count) {
  if (!data) return stride == 0 && count == 0;
  if (width <= 0 || height <= 0 || stride < width) return false;
  const std::size_t row = static_cast<std::size_t>(height - 1);
  const std::size_t stride_size = static_cast<std::size_t>(stride);
  if (row > (std::numeric_limits<std::size_t>::max() -
             static_cast<std::size_t>(width)) / stride_size) return false;
  return row * stride_size + static_cast<std::size_t>(width) <= count;
}

bool validDepthView(const TsdfDepthImageView& depth,
                    const CameraIntrinsics& intrinsics) {
  return depth.depth_z_m != nullptr &&
      depth.width == intrinsics.width && depth.height == intrinsics.height &&
      planeStorageValid(depth.width, depth.height,
                        depth.depth_row_stride_elements,
                        depth.depth_z_m, depth.depth_element_count) &&
      planeStorageValid(depth.width, depth.height,
                        depth.confidence_row_stride_elements,
                        depth.confidence, depth.confidence_element_count) &&
      planeStorageValid(depth.width, depth.height,
                        depth.uncertainty_row_stride_elements,
                        depth.uncertainty_m, depth.uncertainty_element_count);
}

Vec3 toVec3(const Vec3f& value) {
  return {value.x, value.y, value.z};
}

Vec3 weightedPoint(const std::array<Vec3, 3>& points,
                   const std::array<double, 3>& weights) {
  return points[0] * weights[0] + points[1] * weights[1] +
         points[2] * weights[2];
}

struct DepthCheck {
  bool found{false};
  bool consistent{false};
};

DepthCheck checkDepth(const TextureViewCandidate& candidate,
                      const Vec3& world_point,
                      const TextureViewSelectionConfig& config) {
  if (!candidate.visibility_depth) return {};
  const Pose& pose = candidate.world_from_camera;
  const Vec3 camera_point = pose.rotation.inverse().rotate(
      world_point - pose.translation);
  const auto projected = candidate.camera->project(camera_point);
  if (!projected) return {};
  const auto& depth = *candidate.visibility_depth;
  const int center_x = static_cast<int>(std::llround(projected->x));
  const int center_y = static_cast<int>(std::llround(projected->y));
  int selected_x = 0;
  int selected_y = 0;
  int selected_distance_squared = std::numeric_limits<int>::max();
  for (int dy = -config.depth_search_radius_px;
       dy <= config.depth_search_radius_px; ++dy) {
    for (int dx = -config.depth_search_radius_px;
         dx <= config.depth_search_radius_px; ++dx) {
      const int x = center_x + dx;
      const int y = center_y + dy;
      if (x < 0 || y < 0 || x >= depth.width || y >= depth.height) continue;
      const std::size_t offset = static_cast<std::size_t>(y) *
          static_cast<std::size_t>(depth.depth_row_stride_elements) +
          static_cast<std::size_t>(x);
      const float value = depth.depth_z_m[offset];
      if (!std::isfinite(value) || value <= 0.0F) continue;
      if (depth.confidence) {
        const std::size_t confidence_offset = static_cast<std::size_t>(y) *
            static_cast<std::size_t>(depth.confidence_row_stride_elements) +
            static_cast<std::size_t>(x);
        const float confidence = depth.confidence[confidence_offset];
        if (!std::isfinite(confidence) ||
            confidence < config.min_depth_confidence) continue;
      }
      const int distance_squared = dx * dx + dy * dy;
      if (distance_squared < selected_distance_squared) {
        selected_distance_squared = distance_squared;
        selected_x = x;
        selected_y = y;
      }
    }
  }
  if (selected_distance_squared == std::numeric_limits<int>::max()) return {};
  const std::size_t selected_offset = static_cast<std::size_t>(selected_y) *
      static_cast<std::size_t>(depth.depth_row_stride_elements) +
      static_cast<std::size_t>(selected_x);
  const double observed = depth.depth_z_m[selected_offset];
  double uncertainty = config.default_depth_uncertainty_m;
  if (depth.uncertainty_m) {
    const std::size_t uncertainty_offset = static_cast<std::size_t>(selected_y) *
        static_cast<std::size_t>(depth.uncertainty_row_stride_elements) +
        static_cast<std::size_t>(selected_x);
    const double measured_uncertainty = depth.uncertainty_m[uncertainty_offset];
    if (std::isfinite(measured_uncertainty) && measured_uncertainty >= 0.0)
      uncertainty = measured_uncertainty;
  }
  const double tolerance = config.max_absolute_depth_error_m +
      config.max_relative_depth_error * std::max(observed, camera_point.z) +
      config.uncertainty_sigma * uncertainty;
  return {true, std::abs(observed - camera_point.z) <= tolerance};
}

}  // namespace

TextureViewSelectionResult selectTextureViews(
    const ReconstructionMesh& mesh,
    const std::vector<TextureViewCandidate>& candidates,
    TextureViewSelectionConfig config) {
  if (!std::isfinite(config.min_facing_cosine) ||
      config.min_facing_cosine < 0.0 || config.min_facing_cosine >= 1.0 ||
      !std::isfinite(config.min_projected_area_px2) ||
      config.min_projected_area_px2 <= 0.0 ||
      !std::isfinite(config.image_border_px) || config.image_border_px < 0.0 ||
      !std::isfinite(config.max_absolute_depth_error_m) ||
      config.max_absolute_depth_error_m < 0.0 ||
      !std::isfinite(config.max_relative_depth_error) ||
      config.max_relative_depth_error < 0.0 ||
      !std::isfinite(config.uncertainty_sigma) || config.uncertainty_sigma < 0.0 ||
      !std::isfinite(config.default_depth_uncertainty_m) ||
      config.default_depth_uncertainty_m <= 0.0 ||
      !std::isfinite(config.min_depth_confidence) ||
      config.min_depth_confidence < 0.0 || config.min_depth_confidence > 1.0 ||
      !std::isfinite(config.target_projected_edge_px) ||
      config.target_projected_edge_px <= 0.0 ||
      !std::isfinite(config.quality_weight) || config.quality_weight < 0.0 ||
      !std::isfinite(config.facing_weight) || config.facing_weight < 0.0 ||
      !std::isfinite(config.resolution_weight) || config.resolution_weight < 0.0 ||
      config.quality_weight + config.facing_weight + config.resolution_weight <= 0.0 ||
      config.depth_search_radius_px < 0 || config.depth_search_radius_px > 4 ||
      config.min_consistent_depth_samples == 0 ||
      config.min_consistent_depth_samples > 4 || config.max_candidates == 0 ||
      config.max_candidates > 4096 || config.max_image_reference_bytes == 0 ||
      config.max_image_reference_bytes > 16384 || config.max_vertices == 0 ||
      config.max_vertices > 5000000 || config.max_triangles == 0 ||
      config.max_triangles > 5000000 || config.max_projection_tests == 0 ||
      config.max_projection_tests > 100000000) {
    throw std::invalid_argument("Invalid texture view selection configuration");
  }

  TextureViewSelectionResult result;
  const auto validation = validateMesh(
      mesh, {config.max_vertices, config.max_triangles});
  if (!validation.valid) {
    result.error = validation.error;
    return result;
  }
  const std::size_t triangle_count = mesh.triangle_indices.size() / 3;
  if (triangle_count > config.max_triangles) {
    result.error = "mesh triangle budget exceeded";
    return result;
  }
  if (candidates.empty() || candidates.size() > config.max_candidates) {
    result.error = candidates.empty() ? "no texture view candidates" :
                                        "texture view candidate budget exceeded";
    return result;
  }

  std::unordered_set<std::uint64_t> frame_ids;
  std::vector<std::size_t> order(candidates.size());
  std::iota(order.begin(), order.end(), 0);
  for (const auto& candidate : candidates) {
    if (candidate.frame_id == 0 || candidate.image_reference.empty() ||
        candidate.image_reference.size() > config.max_image_reference_bytes ||
        !candidate.camera || !validPose(candidate.world_from_camera) ||
        !std::isfinite(candidate.quality_score) ||
        candidate.quality_score < 0.0 || candidate.quality_score > 1.0 ||
        !frame_ids.insert(candidate.frame_id).second ||
        (candidate.visibility_depth &&
         !validDepthView(*candidate.visibility_depth,
                         candidate.camera->intrinsics()))) {
      result.error = "invalid texture view candidate";
      return result;
    }
  }
  std::sort(order.begin(), order.end(), [&](std::size_t first, std::size_t second) {
    return candidates[first].frame_id < candidates[second].frame_id;
  });

  result.input_valid = true;
  result.candidates = candidates.size();
  result.views.reserve(candidates.size());
  for (const std::size_t candidate_index : order) {
    result.views.push_back({candidates[candidate_index].frame_id,
                            candidates[candidate_index].image_reference});
  }
  result.triangles.resize(triangle_count);
  const double weight_sum = config.quality_weight + config.facing_weight +
                            config.resolution_weight;
  const std::array<std::array<double, 3>, 4> depth_weights{{
      {{1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0}},
      {{0.6, 0.2, 0.2}}, {{0.2, 0.6, 0.2}}, {{0.2, 0.2, 0.6}},
  }};

  bool stop = false;
  for (std::size_t triangle = 0; triangle < triangle_count && !stop; ++triangle) {
    const std::array<Vec3, 3> world_points{
        toVec3(mesh.positions_m[mesh.triangle_indices[triangle * 3]]),
        toVec3(mesh.positions_m[mesh.triangle_indices[triangle * 3 + 1]]),
        toVec3(mesh.positions_m[mesh.triangle_indices[triangle * 3 + 2]])};
    const Vec3 edge_ab = world_points[1] - world_points[0];
    const Vec3 edge_ac = world_points[2] - world_points[0];
    const Vec3 unnormalized_normal{
        edge_ab.y * edge_ac.z - edge_ab.z * edge_ac.y,
        edge_ab.z * edge_ac.x - edge_ab.x * edge_ac.z,
        edge_ab.x * edge_ac.y - edge_ab.y * edge_ac.x};
    const double normal_norm = unnormalized_normal.norm();
    if (!std::isfinite(normal_norm) || normal_norm < 1e-15) continue;
    const Vec3 normal = unnormalized_normal * (1.0 / normal_norm);
    const Vec3 centroid = weightedPoint(world_points, depth_weights[0]);

    for (std::size_t view_index = 0; view_index < order.size(); ++view_index) {
      const std::size_t candidate_index = order[view_index];
      if (result.projection_tests >= config.max_projection_tests) {
        result.truncated = true;
        stop = true;
        break;
      }
      ++result.projection_tests;
      const auto& candidate = candidates[candidate_index];
      const Vec3 view_direction = candidate.world_from_camera.translation - centroid;
      const double view_norm = view_direction.norm();
      if (!std::isfinite(view_norm) || view_norm < 1e-12) {
        ++result.rejected_back_facing;
        continue;
      }
      const double facing = std::max(-1.0, std::min(1.0,
          (normal.x * view_direction.x + normal.y * view_direction.y +
           normal.z * view_direction.z) / view_norm));
      if (facing < config.min_facing_cosine) {
        ++result.rejected_back_facing;
        continue;
      }

      std::array<Vec2, 3> projected{};
      bool inside = true;
      const Pose& pose = candidate.world_from_camera;
      for (std::size_t corner = 0; corner < 3; ++corner) {
        const Vec3 camera_point = pose.rotation.inverse().rotate(
            world_points[corner] - pose.translation);
        const auto pixel = candidate.camera->project(camera_point);
        if (!pixel || !candidate.camera->isInside(*pixel, config.image_border_px)) {
          inside = false;
          break;
        }
        projected[corner] = *pixel;
      }
      if (!inside) {
        ++result.rejected_outside_image;
        continue;
      }
      const double projected_area = 0.5 * std::abs(
          (projected[1].x - projected[0].x) *
              (projected[2].y - projected[0].y) -
          (projected[1].y - projected[0].y) *
              (projected[2].x - projected[0].x));
      if (!std::isfinite(projected_area) ||
          projected_area < config.min_projected_area_px2) {
        ++result.rejected_small_projection;
        continue;
      }

      if (config.require_depth_visibility && !candidate.visibility_depth) {
        ++result.rejected_missing_depth;
        continue;
      }
      if (candidate.visibility_depth) {
        std::size_t consistent_samples = 0;
        bool inconsistent = false;
        for (const auto& weights : depth_weights) {
          const DepthCheck check = checkDepth(candidate,
              weightedPoint(world_points, weights), config);
          if (!check.found) continue;
          if (!check.consistent) {
            inconsistent = true;
            break;
          }
          ++consistent_samples;
        }
        if (inconsistent) {
          ++result.rejected_depth_inconsistent;
          continue;
        }
        if (consistent_samples < config.min_consistent_depth_samples) {
          ++result.rejected_missing_depth;
          continue;
        }
      }

      const double resolution = std::min(1.0,
          std::sqrt(projected_area) / config.target_projected_edge_px);
      const double score = (config.quality_weight * candidate.quality_score +
                            config.facing_weight * facing +
                            config.resolution_weight * resolution) / weight_sum;
      auto& selection = result.triangles[triangle];
      if (!selection.assigned || score > selection.score + 1e-12 ||
          (std::abs(score - selection.score) <= 1e-12 &&
           candidate.frame_id < selection.frame_id)) {
        selection.assigned = true;
        selection.view_index = view_index;
        selection.frame_id = candidate.frame_id;
        selection.score = score;
        selection.facing_cosine = facing;
        selection.projected_area_px2 = projected_area;
        const auto& intrinsics = candidate.camera->intrinsics();
        const double width_scale = std::max(1, intrinsics.width - 1);
        const double height_scale = std::max(1, intrinsics.height - 1);
        for (std::size_t corner = 0; corner < 3; ++corner) {
          selection.normalized_uv[corner] = {
              static_cast<float>(projected[corner].x / width_scale),
              static_cast<float>(projected[corner].y / height_scale)};
        }
      }
    }
  }

  result.assigned_triangles = static_cast<std::size_t>(std::count_if(
      result.triangles.begin(), result.triangles.end(),
      [](const TriangleTextureView& value) { return value.assigned; }));
  result.unassigned_triangles = triangle_count - result.assigned_triangles;
  return result;
}

}  // namespace arkernel
