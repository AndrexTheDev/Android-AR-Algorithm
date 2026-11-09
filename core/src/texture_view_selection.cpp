#include "arkernel/texture_view_selection.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <unordered_map>
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
      config.max_projection_tests > 100000000 ||
      config.max_alternatives_per_triangle == 0 ||
      config.max_alternatives_per_triangle > 16 ||
      config.max_alternative_records == 0 ||
      config.max_alternative_records > 16000000) {
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
  result.alternatives.reserve(std::min(
      config.max_alternative_records,
      triangle_count * config.max_alternatives_per_triangle));
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
    std::array<TextureViewAlternative,16> triangle_alternatives{};
    std::size_t triangle_alternative_count=0;
    const auto better_alternative=[](const TextureViewAlternative& first,
                                     const TextureViewAlternative& second) {
      return first.score > second.score + 1e-12 ||
          (std::abs(first.score - second.score) <= 1e-12 &&
           first.frame_id < second.frame_id);
    };

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
      TextureViewAlternative alternative;
      alternative.view_index = view_index;
      alternative.frame_id = candidate.frame_id;
      alternative.score = score;
      alternative.facing_cosine = facing;
      alternative.projected_area_px2 = projected_area;
      const auto& intrinsics = candidate.camera->intrinsics();
      const double width_scale = std::max(1, intrinsics.width - 1);
      const double height_scale = std::max(1, intrinsics.height - 1);
      for (std::size_t corner = 0; corner < 3; ++corner) {
        alternative.normalized_uv[corner] = {
            static_cast<float>(projected[corner].x / width_scale),
            static_cast<float>(projected[corner].y / height_scale)};
      }
      if(triangle_alternative_count<config.max_alternatives_per_triangle) {
        triangle_alternatives[triangle_alternative_count++]=alternative;
      } else if(better_alternative(
                    alternative,triangle_alternatives[triangle_alternative_count-1])) {
        triangle_alternatives[triangle_alternative_count-1]=alternative;
      }
      std::sort(triangle_alternatives.begin(),
                triangle_alternatives.begin()+
                    static_cast<std::ptrdiff_t>(triangle_alternative_count),
                better_alternative);
    }

    if (triangle_alternative_count>0) {
      const auto& best = triangle_alternatives[0];
      auto& selection = result.triangles[triangle];
      selection.assigned = true;
      selection.view_index = best.view_index;
      selection.frame_id = best.frame_id;
      selection.normalized_uv = best.normalized_uv;
      selection.score = best.score;
      selection.facing_cosine = best.facing_cosine;
      selection.projected_area_px2 = best.projected_area_px2;
      selection.alternative_offset = result.alternatives.size();
      const std::size_t remaining = config.max_alternative_records -
          std::min(config.max_alternative_records, result.alternatives.size());
      selection.alternative_count = std::min(remaining,
                                             triangle_alternative_count);
      result.alternatives.insert(result.alternatives.end(),
          triangle_alternatives.begin(),
          triangle_alternatives.begin()+
              static_cast<std::ptrdiff_t>(selection.alternative_count));
      if (selection.alternative_count < triangle_alternative_count)
        result.alternatives_truncated = true;
    }
  }

  result.assigned_triangles = static_cast<std::size_t>(std::count_if(
      result.triangles.begin(), result.triangles.end(),
      [](const TriangleTextureView& value) { return value.assigned; }));
  result.unassigned_triangles = triangle_count - result.assigned_triangles;
  return result;
}

TextureLabelOptimizationResult optimizeTextureViewLabels(
    const ReconstructionMesh& mesh,
    const TextureViewSelectionResult& selection,
    TextureLabelOptimizationConfig config) {
  if (!std::isfinite(config.data_weight) || config.data_weight <= 0.0 ||
      !std::isfinite(config.seam_penalty) || config.seam_penalty < 0.0 ||
      !std::isfinite(config.max_normal_angle_degrees) ||
      config.max_normal_angle_degrees <= 0.0 ||
      config.max_normal_angle_degrees >= 180.0 ||
      config.max_iterations == 0 || config.max_iterations > 100 ||
      config.max_triangles == 0 || config.max_triangles > 5000000 ||
      config.max_unique_edges == 0 || config.max_unique_edges > 6000000 ||
      config.max_label_evaluations == 0 ||
      config.max_label_evaluations > 1000000000) {
    throw std::invalid_argument("Invalid texture label optimization configuration");
  }

  TextureLabelOptimizationResult result;
  const auto mesh_validation = validateMesh(
      mesh, {5000000, config.max_triangles});
  if (!mesh_validation.valid) {
    result.error = mesh_validation.error;
    return result;
  }
  const std::size_t triangle_count = mesh.triangle_indices.size() / 3;
  if (!selection.input_valid || selection.triangles.size() != triangle_count ||
      selection.views.empty()) {
    result.error = "texture selection does not match mesh";
    return result;
  }
  for (const auto& triangle : selection.triangles) {
    if (!triangle.assigned) {
      if (triangle.alternative_count != 0) {
        result.error = "unassigned triangle has alternatives";
        return result;
      }
      continue;
    }
    if (triangle.view_index >= selection.views.size() ||
        selection.views[triangle.view_index].frame_id != triangle.frame_id ||
        triangle.alternative_offset > selection.alternatives.size() ||
        triangle.alternative_count > selection.alternatives.size() -
            triangle.alternative_offset) {
      result.error = "texture assignment is invalid";
      return result;
    }
    std::unordered_set<std::size_t> alternative_views;
    for (std::size_t i = 0; i < triangle.alternative_count; ++i) {
      const auto& alternative = selection.alternatives[
          triangle.alternative_offset + i];
      if (alternative.view_index >= selection.views.size() ||
          selection.views[alternative.view_index].frame_id != alternative.frame_id ||
          !std::isfinite(alternative.score) || alternative.score < 0.0 ||
          alternative.score > 1.000001 ||
          !alternative_views.insert(alternative.view_index).second) {
        result.error = "texture alternative is invalid";
        return result;
      }
      for (const auto& uv : alternative.normalized_uv) {
        if (!std::isfinite(uv.x) || !std::isfinite(uv.y)) {
          result.error = "texture alternative UV is invalid";
          return result;
        }
      }
    }
  }

  struct EdgeKey {
    std::uint32_t first{0};
    std::uint32_t second{0};
    bool operator==(const EdgeKey& other) const {
      return first == other.first && second == other.second;
    }
  };
  struct EdgeHash {
    std::size_t operator()(const EdgeKey& edge) const {
      return static_cast<std::size_t>(edge.first) * 0x9e3779b1U ^ edge.second;
    }
  };
  struct EdgeUse {
    std::size_t first_triangle{0};
    std::size_t second_triangle{0};
    std::size_t count{0};
  };
  const auto edge_key=[](std::uint32_t first,std::uint32_t second) {
    return first < second ? EdgeKey{first,second} : EdgeKey{second,first};
  };
  const std::size_t no_neighbor = std::numeric_limits<std::size_t>::max();
  std::vector<std::array<std::size_t,3>> adjacency(
      triangle_count, {no_neighbor,no_neighbor,no_neighbor});
  std::vector<std::size_t> adjacency_count(triangle_count, 0);
  std::unordered_map<EdgeKey,EdgeUse,EdgeHash> edges;
  edges.reserve(std::min(config.max_unique_edges, triangle_count * 3));
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    const std::array<std::uint32_t,3> indices{
      mesh.triangle_indices[triangle*3],mesh.triangle_indices[triangle*3+1],
      mesh.triangle_indices[triangle*3+2]};
    for (std::size_t side = 0; side < 3; ++side) {
      if (indices[side] == indices[(side+1)%3]) continue;
      const EdgeKey key=edge_key(indices[side],indices[(side+1)%3]);
      auto found=edges.find(key);
      if (found == edges.end()) {
        if (edges.size() >= config.max_unique_edges) {
          result.input_valid = true;
          result.truncated = true;
          result.error = "texture label edge budget exceeded";
          return result;
        }
        edges.emplace(key,EdgeUse{triangle,triangle,1});
      } else {
        if (found->second.count == 1) found->second.second_triangle=triangle;
        ++found->second.count;
      }
    }
  }
  std::vector<Vec3> face_normals(triangle_count);
  std::vector<bool> face_normal_valid(triangle_count,false);
  for(std::size_t triangle=0;triangle<triangle_count;++triangle) {
    const Vec3 a=toVec3(mesh.positions_m[mesh.triangle_indices[triangle*3]]);
    const Vec3 b=toVec3(mesh.positions_m[mesh.triangle_indices[triangle*3+1]]);
    const Vec3 c=toVec3(mesh.positions_m[mesh.triangle_indices[triangle*3+2]]);
    const Vec3 ab=b-a,ac=c-a;
    const Vec3 normal{ab.y*ac.z-ab.z*ac.y,ab.z*ac.x-ab.x*ac.z,
                      ab.x*ac.y-ab.y*ac.x};
    const double length=normal.norm();
    if(std::isfinite(length)&&length>1e-15) {
      face_normals[triangle]=normal*(1.0/length);face_normal_valid[triangle]=true;
    }
  }
  const double minimum_normal_cosine=std::cos(
      config.max_normal_angle_degrees*3.14159265358979323846/180.0);
  for (const auto& item : edges) {
    if (item.second.count == 2) {
      const std::size_t first=item.second.first_triangle;
      const std::size_t second=item.second.second_triangle;
      const double normal_cosine=face_normal_valid[first]&&face_normal_valid[second]?
          face_normals[first].x*face_normals[second].x+
          face_normals[first].y*face_normals[second].y+
          face_normals[first].z*face_normals[second].z:-1.0;
      if(normal_cosine<minimum_normal_cosine) {
        ++result.sharp_edges;
      } else if (first != second && adjacency_count[first] < 3 &&
                 adjacency_count[second] < 3) {
        adjacency[first][adjacency_count[first]++]=second;
        adjacency[second][adjacency_count[second]++]=first;
      }
    } else if (item.second.count > 2) {
      ++result.non_manifold_edges;
    }
  }

  result.input_valid = true;
  result.source_alternatives_truncated = selection.alternatives_truncated;
  result.triangles = selection.triangles;
  const auto seam_count=[&](const std::vector<TriangleTextureView>& triangles) {
    std::size_t seams=0;
    for (std::size_t triangle=0;triangle<triangle_count;++triangle) {
      if (!triangles[triangle].assigned) continue;
      for (std::size_t i=0;i<adjacency_count[triangle];++i) {
        const std::size_t neighbor=adjacency[triangle][i];
        if (neighbor>triangle&&triangles[neighbor].assigned&&
            triangles[neighbor].view_index!=triangles[triangle].view_index)++seams;
      }
    }
    return seams;
  };
  const auto patch_count=[&](const std::vector<TriangleTextureView>& triangles) {
    std::vector<std::size_t> parent(triangle_count);
    std::iota(parent.begin(),parent.end(),0);
    const auto find_root=[&](std::size_t value,auto&& self)->std::size_t {
      if(parent[value]!=value)parent[value]=self(parent[value],self);
      return parent[value];
    };
    for(std::size_t triangle=0;triangle<triangle_count;++triangle) {
      if(!triangles[triangle].assigned)continue;
      for(std::size_t i=0;i<adjacency_count[triangle];++i) {
        const std::size_t neighbor=adjacency[triangle][i];
        if(neighbor<=triangle||!triangles[neighbor].assigned||
           triangles[neighbor].view_index!=triangles[triangle].view_index)continue;
        const std::size_t first=find_root(triangle,find_root);
        const std::size_t second=find_root(neighbor,find_root);
        if(first!=second)parent[second]=first;
      }
    }
    std::unordered_set<std::size_t> roots;
    for(std::size_t triangle=0;triangle<triangle_count;++triangle)
      if(triangles[triangle].assigned)roots.insert(find_root(triangle,find_root));
    return roots.size();
  };
  result.seam_edges_before=seam_count(result.triangles);
  result.patches_before=patch_count(result.triangles);
  std::vector<std::size_t> original_labels(triangle_count);
  for(std::size_t triangle=0;triangle<triangle_count;++triangle)
    original_labels[triangle]=result.triangles[triangle].view_index;

  for (std::size_t iteration=0;iteration<config.max_iterations;++iteration) {
    bool changed=false;
    ++result.iterations;
    for (std::size_t triangle=0;triangle<triangle_count;++triangle) {
      const auto& source_triangle=selection.triangles[triangle];
      if (!source_triangle.assigned || source_triangle.alternative_count==0)continue;
      const TextureViewAlternative* best=nullptr;
      double best_energy=std::numeric_limits<double>::infinity();
      for(std::size_t i=0;i<source_triangle.alternative_count;++i) {
        if(result.label_evaluations>=config.max_label_evaluations) {
          result.truncated=true;
          break;
        }
        ++result.label_evaluations;
        const auto& alternative=selection.alternatives[
            source_triangle.alternative_offset+i];
        double energy=config.data_weight*(1.0-alternative.score);
        for(std::size_t n=0;n<adjacency_count[triangle];++n) {
          const auto& neighbor=result.triangles[adjacency[triangle][n]];
          if(neighbor.assigned&&neighbor.view_index!=alternative.view_index)
            energy+=config.seam_penalty;
        }
        if(!best||energy<best_energy-1e-12||
           (std::abs(energy-best_energy)<=1e-12&&
            alternative.frame_id<best->frame_id)) {
          best=&alternative;best_energy=energy;
        }
      }
      if(result.truncated)break;
      if(best&&best->view_index!=result.triangles[triangle].view_index) {
        auto& output=result.triangles[triangle];
        output.view_index=best->view_index;output.frame_id=best->frame_id;
        output.normalized_uv=best->normalized_uv;output.score=best->score;
        output.facing_cosine=best->facing_cosine;
        output.projected_area_px2=best->projected_area_px2;
        changed=true;
      }
    }
    if(result.truncated||!changed)break;
  }
  result.changed_labels=0;
  for(std::size_t triangle=0;triangle<triangle_count;++triangle)
    result.changed_labels+=result.triangles[triangle].view_index!=
                           original_labels[triangle];
  result.seam_edges_after=seam_count(result.triangles);
  result.patches_after=patch_count(result.triangles);
  return result;
}

}  // namespace arkernel
