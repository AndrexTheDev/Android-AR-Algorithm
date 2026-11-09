#include "arkernel/mesh_processing.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <numeric>
#include <optional>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace arkernel {
namespace {

struct EdgeKey {
  std::uint32_t first{0};
  std::uint32_t second{0};
  bool operator==(const EdgeKey& other) const {
    return first == other.first && second == other.second;
  }
};

struct EdgeHash {
  std::size_t operator()(const EdgeKey& edge) const {
    std::size_t seed = static_cast<std::size_t>(edge.first);
    seed ^= static_cast<std::size_t>(edge.second) + 0x9e3779b9U + (seed << 6) +
            (seed >> 2);
    return seed;
  }
};

EdgeKey edgeKey(std::uint32_t first, std::uint32_t second) {
  if (second < first) std::swap(first, second);
  return {first, second};
}

struct EdgeUse {
  std::size_t first_triangle{0};
  std::size_t count{0};
};

class DisjointSet {
 public:
  explicit DisjointSet(std::size_t size) : parent_(size), rank_(size, 0) {
    std::iota(parent_.begin(), parent_.end(), 0);
  }

  std::size_t find(std::size_t value) {
    std::size_t root = value;
    while (parent_[root] != root) root = parent_[root];
    while (parent_[value] != value) {
      const std::size_t next = parent_[value];
      parent_[value] = root;
      value = next;
    }
    return root;
  }

  void unite(std::size_t first, std::size_t second) {
    first = find(first);
    second = find(second);
    if (first == second) return;
    if (rank_[first] < rank_[second]) std::swap(first, second);
    parent_[second] = first;
    if (rank_[first] == rank_[second]) ++rank_[first];
  }

 private:
  std::vector<std::size_t> parent_;
  std::vector<std::uint8_t> rank_;
};

struct ComponentInfo {
  std::size_t triangle_count{0};
  double area_m2{0.0};
  std::size_t first_triangle{std::numeric_limits<std::size_t>::max()};
};

double triangleArea(const ReconstructionMesh& mesh, std::size_t triangle) {
  const auto ia = mesh.triangle_indices[triangle * 3];
  const auto ib = mesh.triangle_indices[triangle * 3 + 1];
  const auto ic = mesh.triangle_indices[triangle * 3 + 2];
  if (ia == ib || ia == ic || ib == ic) return 0.0;
  const auto& a = mesh.positions_m[ia];
  const auto& b = mesh.positions_m[ib];
  const auto& c = mesh.positions_m[ic];
  const double abx = static_cast<double>(b.x) - a.x;
  const double aby = static_cast<double>(b.y) - a.y;
  const double abz = static_cast<double>(b.z) - a.z;
  const double acx = static_cast<double>(c.x) - a.x;
  const double acy = static_cast<double>(c.y) - a.y;
  const double acz = static_cast<double>(c.z) - a.z;
  const double nx = aby * acz - abz * acy;
  const double ny = abz * acx - abx * acz;
  const double nz = abx * acy - aby * acx;
  return 0.5 * std::sqrt(nx * nx + ny * ny + nz * nz);
}

}  // namespace

MeshComponentFilterResult filterMeshComponents(
    const ReconstructionMesh& input, MeshComponentFilterConfig config) {
  if (config.min_component_triangles == 0 ||
      !std::isfinite(config.min_component_area_m2) ||
      config.min_component_area_m2 < 0.0 ||
      !std::isfinite(config.min_triangle_area_m2) ||
      config.min_triangle_area_m2 <= 0.0 ||
      config.max_input_vertices == 0 || config.max_input_vertices > 5000000 ||
      config.max_input_triangles == 0 || config.max_input_triangles > 5000000 ||
      config.max_unique_edges == 0 || config.max_unique_edges > 6000000) {
    throw std::invalid_argument("Invalid mesh component filter configuration");
  }

  MeshComponentFilterResult result;
  result.input_vertices = input.positions_m.size();
  result.input_triangles = input.triangle_indices.size() / 3;
  const auto validation = validateMesh(
      input, {config.max_input_vertices, config.max_input_triangles});
  if (!validation.valid) {
    result.error = validation.error;
    return result;
  }
  result.input_valid = true;
  const std::size_t triangle_count = result.input_triangles;
  DisjointSet components(triangle_count);
  std::vector<bool> active(triangle_count, true);
  std::vector<double> areas(triangle_count, 0.0);
  std::unordered_map<EdgeKey, EdgeUse, EdgeHash> edges;
  edges.reserve(std::min(config.max_unique_edges, triangle_count * 3));

  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    areas[triangle] = triangleArea(input, triangle);
    if (!std::isfinite(areas[triangle]) ||
        areas[triangle] < config.min_triangle_area_m2) {
      active[triangle] = false;
      ++result.degenerate_triangles;
      continue;
    }
    const std::array<std::uint32_t, 3> indices{
        input.triangle_indices[triangle * 3],
        input.triangle_indices[triangle * 3 + 1],
        input.triangle_indices[triangle * 3 + 2],
    };
    for (int edge = 0; edge < 3; ++edge) {
      const EdgeKey key = edgeKey(indices[edge], indices[(edge + 1) % 3]);
      auto existing = edges.find(key);
      if (existing == edges.end()) {
        if (edges.size() >= config.max_unique_edges) {
          result.truncated = true;
          result.error = "mesh edge budget exceeded";
          return result;
        }
        edges.emplace(key, EdgeUse{triangle, 1});
      } else {
        components.unite(triangle, existing->second.first_triangle);
        ++existing->second.count;
      }
    }
  }

  std::unordered_map<std::size_t, ComponentInfo> component_info;
  component_info.reserve(triangle_count);
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    if (!active[triangle]) continue;
    const std::size_t root = components.find(triangle);
    auto& info = component_info[root];
    ++info.triangle_count;
    info.area_m2 += areas[triangle];
    info.first_triangle = std::min(info.first_triangle, triangle);
  }
  result.components = component_info.size();

  std::unordered_map<std::size_t, bool> retained;
  retained.reserve(component_info.size());
  std::optional<std::size_t> largest_root;
  for (const auto& item : component_info) {
    const bool passes =
        item.second.triangle_count >= config.min_component_triangles &&
        item.second.area_m2 >= config.min_component_area_m2;
    retained.emplace(item.first, passes);
    if (!passes) continue;
    if (!largest_root) {
      largest_root = item.first;
      continue;
    }
    const auto& current = component_info.at(*largest_root);
    const auto& candidate = item.second;
    if (candidate.triangle_count > current.triangle_count ||
        (candidate.triangle_count == current.triangle_count &&
         (candidate.area_m2 > current.area_m2 ||
          (candidate.area_m2 == current.area_m2 &&
           candidate.first_triangle < current.first_triangle)))) {
      largest_root = item.first;
    }
  }
  if (config.keep_largest_only) {
    for (auto& item : retained) item.second = largest_root && item.first == *largest_root;
  }
  result.retained_components = static_cast<std::size_t>(std::count_if(
      retained.begin(), retained.end(), [](const auto& item) { return item.second; }));

  result.mesh.name = input.name;
  result.mesh.base_color_texture = input.base_color_texture;
  std::vector<std::uint32_t> remap(input.positions_m.size(),
                                   std::numeric_limits<std::uint32_t>::max());
  const bool has_normals = !input.normals.empty();
  const bool has_texcoords = !input.texcoords.empty();
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    bool keep = false;
    if (active[triangle]) {
      const auto found = retained.find(components.find(triangle));
      keep = found != retained.end() && found->second;
    }
    if (!keep) {
      ++result.removed_triangles;
      continue;
    }
    for (int corner = 0; corner < 3; ++corner) {
      const std::uint32_t old_index = input.triangle_indices[triangle * 3 + corner];
      std::uint32_t& new_index = remap[old_index];
      if (new_index == std::numeric_limits<std::uint32_t>::max()) {
        new_index = static_cast<std::uint32_t>(result.mesh.positions_m.size());
        result.mesh.positions_m.push_back(input.positions_m[old_index]);
        if (has_normals) result.mesh.normals.push_back(input.normals[old_index]);
        if (has_texcoords) result.mesh.texcoords.push_back(input.texcoords[old_index]);
      }
      result.mesh.triangle_indices.push_back(new_index);
    }
  }
  result.removed_vertices = input.positions_m.size() - result.mesh.positions_m.size();

  std::unordered_map<EdgeKey, std::size_t, EdgeHash> output_edges;
  output_edges.reserve(std::min(config.max_unique_edges,
                                result.mesh.triangle_indices.size()));
  for (std::size_t i = 0; i < result.mesh.triangle_indices.size(); i += 3) {
    const std::array<std::uint32_t, 3> indices{
        result.mesh.triangle_indices[i], result.mesh.triangle_indices[i + 1],
        result.mesh.triangle_indices[i + 2],
    };
    for (int edge = 0; edge < 3; ++edge) {
      ++output_edges[edgeKey(indices[edge], indices[(edge + 1) % 3])];
    }
  }
  for (const auto& item : output_edges) {
    if (item.second == 1) ++result.boundary_edges;
    else if (item.second == 2) ++result.manifold_edges;
    else ++result.non_manifold_edges;
  }
  return result;
}

}  // namespace arkernel
