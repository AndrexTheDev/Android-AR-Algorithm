#include "arkernel/tsdf_meshing.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace arkernel {
namespace {

struct GridKey {
  std::int32_t x{0};
  std::int32_t y{0};
  std::int32_t z{0};
  bool operator==(const GridKey& other) const {
    return x == other.x && y == other.y && z == other.z;
  }
};

bool less(const GridKey& a, const GridKey& b) {
  if (a.z != b.z) return a.z < b.z;
  if (a.y != b.y) return a.y < b.y;
  return a.x < b.x;
}

struct GridHash {
  std::size_t operator()(const GridKey& key) const {
    std::size_t seed = 0x9e3779b97f4a7c15ULL;
    const auto mix = [&seed](std::uint32_t value) {
      seed ^= static_cast<std::size_t>(value) + 0x9e3779b9U + (seed << 6) +
              (seed >> 2);
    };
    mix(static_cast<std::uint32_t>(key.x));
    mix(static_cast<std::uint32_t>(key.y));
    mix(static_cast<std::uint32_t>(key.z));
    return seed;
  }
};

struct VertexKey {
  GridKey first{};
  GridKey second{};
  bool operator==(const VertexKey& other) const {
    return first == other.first && second == other.second;
  }
};

struct VertexHash {
  std::size_t operator()(const VertexKey& key) const {
    const GridHash hash;
    std::size_t seed = hash(key.first);
    seed ^= hash(key.second) + 0x9e3779b9U + (seed << 6) + (seed >> 2);
    return seed;
  }
};

struct Intersection {
  VertexKey key{};
  Vec3 position{};
};

Vec3 cross(const Vec3& a, const Vec3& b) {
  return {a.y * b.z - a.z * b.y,
          a.z * b.x - a.x * b.z,
          a.x * b.y - a.y * b.x};
}

double dot(const Vec3& a, const Vec3& b) {
  return a.x * b.x + a.y * b.y + a.z * b.z;
}

VertexKey canonicalVertexKey(GridKey a, GridKey b, double interpolation) {
  constexpr double endpoint_epsilon = 1e-9;
  if (interpolation <= endpoint_epsilon) return {a, a};
  if (interpolation >= 1.0 - endpoint_epsilon) return {b, b};
  if (less(b, a)) std::swap(a, b);
  return {a, b};
}

}  // namespace

TsdfMeshingResult extractTsdfMesh(const SparseTsdfVolume& volume,
                                  TsdfMeshingConfig config) {
  if (!std::isfinite(config.iso_value_m) ||
      std::abs(config.iso_value_m) > volume.truncationDistanceMeters() ||
      !std::isfinite(config.min_voxel_weight) || config.min_voxel_weight <= 0.0F ||
      config.min_voxel_weight > 1e6F ||
      !std::isfinite(config.min_triangle_area_m2) ||
      config.min_triangle_area_m2 <= 0.0 || config.min_triangle_area_m2 > 1e6 ||
      config.max_input_voxels == 0 || config.max_input_voxels > 4194304 ||
      config.max_cells == 0 || config.max_cells > 4194304 ||
      config.max_vertices < 3 || config.max_vertices > 10000000 ||
      config.max_triangles == 0 || config.max_triangles > 20000000) {
    throw std::invalid_argument("Invalid TSDF meshing configuration");
  }

  TsdfMeshingResult result;
  result.mesh.name = "TSDF surface";
  const std::vector<TsdfVoxelSample> samples = volume.snapshot();
  result.input_voxels = samples.size();
  result.input_valid = true;
  if (samples.size() > config.max_input_voxels) {
    result.truncated = true;
    return result;
  }
  if (samples.empty()) return result;

  std::unordered_map<GridKey, const TsdfVoxelSample*, GridHash> nodes;
  nodes.reserve(samples.size());
  for (const auto& sample : samples) {
    nodes.emplace(GridKey{sample.x, sample.y, sample.z}, &sample);
  }

  std::unordered_map<VertexKey, std::uint32_t, VertexHash> vertices;
  vertices.reserve(std::min(config.max_vertices, samples.size() * 2));
  std::vector<Vec3> normal_sums;
  std::vector<Vec3> normal_fallbacks;
  bool stop = false;

  const auto add_vertex = [&](const Intersection& intersection) {
    const auto existing = vertices.find(intersection.key);
    if (existing != vertices.end()) return existing->second;
    const auto index = static_cast<std::uint32_t>(result.mesh.positions_m.size());
    result.mesh.positions_m.push_back(
        {static_cast<float>(intersection.position.x),
         static_cast<float>(intersection.position.y),
         static_cast<float>(intersection.position.z)});
    normal_sums.push_back({});
    normal_fallbacks.push_back({});
    vertices.emplace(intersection.key, index);
    return index;
  };

  const auto emit_triangle = [&](Intersection a, Intersection b, Intersection c,
                                 const Vec3& positive_direction) {
    if (a.key == b.key || a.key == c.key || b.key == c.key) {
      ++result.degenerate_triangles;
      return true;
    }
    Vec3 area_normal = cross(b.position - a.position, c.position - a.position);
    if (dot(area_normal, positive_direction) < 0.0) {
      std::swap(b, c);
      area_normal = area_normal * -1.0;
    }
    const double doubled_area = area_normal.norm();
    if (!std::isfinite(doubled_area) ||
        doubled_area * 0.5 < config.min_triangle_area_m2) {
      ++result.degenerate_triangles;
      return true;
    }
    if (result.mesh.triangle_indices.size() / 3 >= config.max_triangles) {
      result.truncated = true;
      return false;
    }
    std::size_t missing_vertices = 0;
    missing_vertices += vertices.find(a.key) == vertices.end();
    missing_vertices += vertices.find(b.key) == vertices.end();
    missing_vertices += vertices.find(c.key) == vertices.end();
    if (result.mesh.positions_m.size() + missing_vertices > config.max_vertices) {
      result.truncated = true;
      return false;
    }
    const std::uint32_t ia = add_vertex(a);
    const std::uint32_t ib = add_vertex(b);
    const std::uint32_t ic = add_vertex(c);
    result.mesh.triangle_indices.insert(result.mesh.triangle_indices.end(),
                                        {ia, ib, ic});
    for (const auto index : {ia, ib, ic}) {
      normal_sums[index] += area_normal;
      if (normal_fallbacks[index].norm() < 1e-12) {
        normal_fallbacks[index] = area_normal;
      }
    }
    return true;
  };

  constexpr std::array<std::array<int, 3>, 8> corner_offsets{{
      {{0, 0, 0}}, {{1, 0, 0}}, {{0, 1, 0}}, {{1, 1, 0}},
      {{0, 0, 1}}, {{1, 0, 1}}, {{0, 1, 1}}, {{1, 1, 1}},
  }};
  constexpr std::array<std::array<int, 4>, 6> tetrahedra{{
      {{0, 1, 3, 7}}, {{0, 3, 2, 7}}, {{0, 2, 6, 7}},
      {{0, 6, 4, 7}}, {{0, 4, 5, 7}}, {{0, 5, 1, 7}},
  }};

  for (const auto& minimum_sample : samples) {
    if (result.examined_cells >= config.max_cells) {
      result.truncated = true;
      break;
    }
    ++result.examined_cells;
    std::array<const TsdfVoxelSample*, 8> corners{};
    std::array<GridKey, 8> corner_keys{};
    bool complete = true;
    for (std::size_t i = 0; i < corners.size(); ++i) {
      corner_keys[i] = {
          static_cast<std::int32_t>(minimum_sample.x + corner_offsets[i][0]),
          static_cast<std::int32_t>(minimum_sample.y + corner_offsets[i][1]),
          static_cast<std::int32_t>(minimum_sample.z + corner_offsets[i][2]),
      };
      const auto node = nodes.find(corner_keys[i]);
      if (node == nodes.end()) {
        complete = false;
        break;
      }
      corners[i] = node->second;
    }
    if (!complete) {
      ++result.skipped_incomplete_cells;
      continue;
    }
    ++result.complete_cells;
    if (std::any_of(corners.begin(), corners.end(), [&](const auto* corner) {
          return corner->weight < config.min_voxel_weight;
        })) {
      ++result.skipped_low_weight_cells;
      continue;
    }
    const bool has_negative = std::any_of(corners.begin(), corners.end(), [&](const auto* corner) {
      return corner->signed_distance_m < config.iso_value_m;
    });
    const bool has_positive = std::any_of(corners.begin(), corners.end(), [&](const auto* corner) {
      return corner->signed_distance_m >= config.iso_value_m;
    });
    if (!has_negative || !has_positive) continue;
    ++result.surface_cells;

    for (const auto& tetrahedron : tetrahedra) {
      std::array<int, 4> negative{};
      std::array<int, 4> positive{};
      std::size_t negative_count = 0;
      std::size_t positive_count = 0;
      Vec3 negative_centroid{};
      Vec3 positive_centroid{};
      for (const int corner_index : tetrahedron) {
        if (corners[corner_index]->signed_distance_m < config.iso_value_m) {
          negative[negative_count++] = corner_index;
          negative_centroid += corners[corner_index]->center_world_m;
        } else {
          positive[positive_count++] = corner_index;
          positive_centroid += corners[corner_index]->center_world_m;
        }
      }
      if (negative_count == 0 || positive_count == 0) continue;
      negative_centroid = negative_centroid * (1.0 / negative_count);
      positive_centroid = positive_centroid * (1.0 / positive_count);
      const Vec3 positive_direction = positive_centroid - negative_centroid;

      const auto intersection = [&](int first, int second) {
        const double first_value = corners[first]->signed_distance_m;
        const double second_value = corners[second]->signed_distance_m;
        const double denominator = second_value - first_value;
        const double interpolation = std::clamp(
            (static_cast<double>(config.iso_value_m) - first_value) / denominator,
            0.0, 1.0);
        const Vec3 position = corners[first]->center_world_m +
            (corners[second]->center_world_m - corners[first]->center_world_m) *
                interpolation;
        return Intersection{canonicalVertexKey(corner_keys[first], corner_keys[second],
                                               interpolation),
                            position};
      };

      if (negative_count == 1) {
        const Intersection a = intersection(negative[0], positive[0]);
        const Intersection b = intersection(negative[0], positive[1]);
        const Intersection c = intersection(negative[0], positive[2]);
        if (!emit_triangle(a, b, c, positive_direction)) {
          stop = true;
          break;
        }
      } else if (negative_count == 3) {
        const Intersection a = intersection(negative[0], positive[0]);
        const Intersection b = intersection(negative[1], positive[0]);
        const Intersection c = intersection(negative[2], positive[0]);
        if (!emit_triangle(a, b, c, positive_direction)) {
          stop = true;
          break;
        }
      } else {
        const Intersection ac = intersection(negative[0], positive[0]);
        const Intersection ad = intersection(negative[0], positive[1]);
        const Intersection bc = intersection(negative[1], positive[0]);
        const Intersection bd = intersection(negative[1], positive[1]);
        if (!emit_triangle(ac, bc, ad, positive_direction) ||
            !emit_triangle(ad, bc, bd, positive_direction)) {
          stop = true;
          break;
        }
      }
    }
    if (stop) break;
  }

  if (!result.mesh.positions_m.empty()) {
    result.mesh.normals.reserve(result.mesh.positions_m.size());
    for (std::size_t i = 0; i < normal_sums.size(); ++i) {
      Vec3 normal = normal_sums[i];
      double norm = normal.norm();
      if (!std::isfinite(norm) || norm < 1e-12) {
        normal = normal_fallbacks[i];
        norm = normal.norm();
      }
      if (!std::isfinite(norm) || norm < 1e-12) {
        result.mesh.normals.clear();
        break;
      }
      result.mesh.normals.push_back({static_cast<float>(normal.x / norm),
                                     static_cast<float>(normal.y / norm),
                                     static_cast<float>(normal.z / norm)});
    }
  }
  return result;
}

}  // namespace arkernel
