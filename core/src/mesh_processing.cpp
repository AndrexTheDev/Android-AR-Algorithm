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
#include <unordered_set>
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

MeshSimplificationResult simplifyMeshConservative(
    const ReconstructionMesh& input, MeshSimplificationConfig config) {
  if (!std::isfinite(config.cluster_cell_size_m) ||
      config.cluster_cell_size_m < 1e-5 || config.cluster_cell_size_m > 10.0 ||
      !std::isfinite(config.max_position_error_m) ||
      config.max_position_error_m <= 0.0 || config.max_position_error_m > 10.0 ||
      !std::isfinite(config.sharp_edge_angle_degrees) ||
      config.sharp_edge_angle_degrees <= 0.0 ||
      config.sharp_edge_angle_degrees >= 180.0 ||
      !std::isfinite(config.max_uv_error) || config.max_uv_error < 0.0 ||
      config.max_uv_error > 2.0 || !std::isfinite(config.min_triangle_area_m2) ||
      config.min_triangle_area_m2 <= 0.0 || config.max_input_vertices == 0 ||
      config.max_input_vertices > 5000000 || config.max_input_triangles == 0 ||
      config.max_input_triangles > 5000000 || config.max_unique_edges == 0 ||
      config.max_unique_edges > 6000000 || config.max_clusters == 0 ||
      config.max_clusters > 5000000) {
    throw std::invalid_argument("Invalid conservative mesh simplification configuration");
  }

  MeshSimplificationResult result;
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
  const std::size_t vertex_count = result.input_vertices;

  struct SimplifyEdgeUse {
    std::size_t first_triangle{0};
    std::size_t second_triangle{0};
    std::size_t count{0};
  };
  std::vector<Vec3> face_normals(triangle_count);
  std::vector<bool> valid_faces(triangle_count, false);
  DisjointSet triangle_components(triangle_count);
  std::unordered_map<EdgeKey, SimplifyEdgeUse, EdgeHash> edges;
  edges.reserve(std::min(config.max_unique_edges, triangle_count * 3));
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    const std::uint32_t ia = input.triangle_indices[triangle * 3];
    const std::uint32_t ib = input.triangle_indices[triangle * 3 + 1];
    const std::uint32_t ic = input.triangle_indices[triangle * 3 + 2];
    const auto& a = input.positions_m[ia];
    const auto& b = input.positions_m[ib];
    const auto& c = input.positions_m[ic];
    const Vec3 ab{static_cast<double>(b.x) - a.x,
                  static_cast<double>(b.y) - a.y,
                  static_cast<double>(b.z) - a.z};
    const Vec3 ac{static_cast<double>(c.x) - a.x,
                  static_cast<double>(c.y) - a.y,
                  static_cast<double>(c.z) - a.z};
    Vec3 normal{ab.y * ac.z - ab.z * ac.y,
                ab.z * ac.x - ab.x * ac.z,
                ab.x * ac.y - ab.y * ac.x};
    const double norm = normal.norm();
    if (ia != ib && ia != ic && ib != ic && std::isfinite(norm) &&
        norm * 0.5 >= config.min_triangle_area_m2) {
      face_normals[triangle] = normal * (1.0 / norm);
      valid_faces[triangle] = true;
    }
    const std::array<std::uint32_t, 3> indices{ia, ib, ic};
    for (int edge = 0; edge < 3; ++edge) {
      const EdgeKey key = edgeKey(indices[edge], indices[(edge + 1) % 3]);
      auto existing = edges.find(key);
      if (existing == edges.end()) {
        if (edges.size() >= config.max_unique_edges) {
          result.truncated = true;
          result.error = "mesh edge budget exceeded";
          return result;
        }
        edges.emplace(key, SimplifyEdgeUse{triangle, triangle, 1});
      } else {
        triangle_components.unite(triangle, existing->second.first_triangle);
        if (existing->second.count == 1) existing->second.second_triangle = triangle;
        ++existing->second.count;
      }
    }
  }

  std::vector<bool> locked(vertex_count, false);
  const double sharp_cosine = std::cos(
      config.sharp_edge_angle_degrees * 3.14159265358979323846 / 180.0);
  const bool has_texcoords = !input.texcoords.empty();
  for (const auto& item : edges) {
    const bool boundary = item.second.count == 1;
    const bool non_manifold = item.second.count > 2;
    bool sharp = false;
    if (item.second.count == 2 &&
        valid_faces[item.second.first_triangle] &&
        valid_faces[item.second.second_triangle]) {
      const Vec3& first = face_normals[item.second.first_triangle];
      const Vec3& second = face_normals[item.second.second_triangle];
      const double cosine = first.x * second.x + first.y * second.y + first.z * second.z;
      sharp = cosine < sharp_cosine;
    }
    result.boundary_edges += boundary;
    result.non_manifold_edges += non_manifold;
    result.sharp_edges += sharp;
    if ((boundary && (config.preserve_boundaries ||
                      (config.preserve_uv_seams && has_texcoords))) ||
        (non_manifold && config.preserve_non_manifold) ||
        (sharp && config.preserve_sharp_edges)) {
      locked[item.first.first] = true;
      locked[item.first.second] = true;
    }
  }

  std::vector<std::size_t> vertex_component(vertex_count,
      std::numeric_limits<std::size_t>::max());
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    const std::size_t root = triangle_components.find(triangle);
    for (int corner = 0; corner < 3; ++corner) {
      const std::uint32_t vertex = input.triangle_indices[triangle * 3 + corner];
      if (vertex_component[vertex] == std::numeric_limits<std::size_t>::max()) {
        vertex_component[vertex] = root;
      } else if (vertex_component[vertex] != root) {
        locked[vertex] = true;
      }
    }
  }
  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    if (vertex_component[vertex] == std::numeric_limits<std::size_t>::max()) {
      locked[vertex] = true;
    }
  }
  result.locked_vertices = static_cast<std::size_t>(
      std::count(locked.begin(), locked.end(), true));

  struct ClusterKey {
    std::int64_t x{0};
    std::int64_t y{0};
    std::int64_t z{0};
    std::size_t component{0};
    bool operator==(const ClusterKey& other) const {
      return x == other.x && y == other.y && z == other.z &&
             component == other.component;
    }
  };
  struct ClusterHash {
    std::size_t operator()(const ClusterKey& key) const {
      std::size_t seed = static_cast<std::size_t>(key.x);
      const auto mix = [&seed](std::size_t value) {
        seed ^= value + 0x9e3779b9U + (seed << 6) + (seed >> 2);
      };
      mix(static_cast<std::size_t>(key.y));
      mix(static_cast<std::size_t>(key.z));
      mix(key.component);
      return seed;
    }
  };
  struct Cluster {
    Vec3 position_sum{};
    Vec3 normal_sum{};
    double uv_sum_x{0.0};
    double uv_sum_y{0.0};
    double uv_min_x{0.0};
    double uv_min_y{0.0};
    double uv_max_x{0.0};
    double uv_max_y{0.0};
    Vec3 minimum{};
    Vec3 maximum{};
    std::size_t count{0};
  };

  std::vector<Cluster> clusters;
  clusters.reserve(std::min(config.max_clusters, vertex_count));
  std::unordered_map<ClusterKey, std::size_t, ClusterHash> cluster_lookup;
  cluster_lookup.reserve(std::min(config.max_clusters, vertex_count));
  std::vector<std::size_t> vertex_cluster(vertex_count);
  const bool has_normals = !input.normals.empty();
  const auto grid_coordinate = [&](float value) -> std::optional<std::int64_t> {
    const double coordinate = std::floor(
        static_cast<double>(value) / config.cluster_cell_size_m);
    if (!std::isfinite(coordinate) ||
        coordinate < static_cast<double>(std::numeric_limits<std::int64_t>::min()) ||
        coordinate > static_cast<double>(std::numeric_limits<std::int64_t>::max())) {
      return std::nullopt;
    }
    return static_cast<std::int64_t>(coordinate);
  };

  const auto create_cluster = [&](std::size_t vertex) {
    if (clusters.size() >= config.max_clusters) return std::optional<std::size_t>{};
    const auto& position = input.positions_m[vertex];
    Cluster cluster;
    cluster.position_sum = {position.x, position.y, position.z};
    cluster.minimum = cluster.maximum = cluster.position_sum;
    if (has_normals) {
      const auto& normal = input.normals[vertex];
      cluster.normal_sum = {normal.x, normal.y, normal.z};
    }
    if (has_texcoords) {
      cluster.uv_sum_x = input.texcoords[vertex].x;
      cluster.uv_sum_y = input.texcoords[vertex].y;
      cluster.uv_min_x = cluster.uv_max_x = input.texcoords[vertex].x;
      cluster.uv_min_y = cluster.uv_max_y = input.texcoords[vertex].y;
    }
    cluster.count = 1;
    clusters.push_back(cluster);
    return std::optional<std::size_t>{clusters.size() - 1};
  };

  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    const auto& position = input.positions_m[vertex];
    if (locked[vertex]) {
      const auto created = create_cluster(vertex);
      if (!created) {
        result.truncated = true;
        result.error = "mesh cluster budget exceeded";
        return result;
      }
      vertex_cluster[vertex] = *created;
      continue;
    }
    const auto grid_x = grid_coordinate(position.x);
    const auto grid_y = grid_coordinate(position.y);
    const auto grid_z = grid_coordinate(position.z);
    if (!grid_x || !grid_y || !grid_z) {
      result.error = "mesh position exceeds clustering coordinate range";
      return result;
    }
    const ClusterKey key{*grid_x, *grid_y, *grid_z, vertex_component[vertex]};
    const auto found = cluster_lookup.find(key);
    if (found == cluster_lookup.end()) {
      const auto created = create_cluster(vertex);
      if (!created) {
        result.truncated = true;
        result.error = "mesh cluster budget exceeded";
        return result;
      }
      cluster_lookup.emplace(key, *created);
      vertex_cluster[vertex] = *created;
      continue;
    }
    Cluster& cluster = clusters[found->second];
    const Vec3 candidate_minimum{
        std::min(cluster.minimum.x, static_cast<double>(position.x)),
        std::min(cluster.minimum.y, static_cast<double>(position.y)),
        std::min(cluster.minimum.z, static_cast<double>(position.z))};
    const Vec3 candidate_maximum{
        std::max(cluster.maximum.x, static_cast<double>(position.x)),
        std::max(cluster.maximum.y, static_cast<double>(position.y)),
        std::max(cluster.maximum.z, static_cast<double>(position.z))};
    bool compatible = (candidate_maximum - candidate_minimum).norm() <=
                      config.max_position_error_m;
    if (compatible && has_normals) {
      const auto& normal = input.normals[vertex];
      const double cluster_norm = cluster.normal_sum.norm();
      const double normal_norm = std::sqrt(static_cast<double>(normal.x) * normal.x +
                                           static_cast<double>(normal.y) * normal.y +
                                           static_cast<double>(normal.z) * normal.z);
      if (cluster_norm > 1e-12 && normal_norm > 1e-12) {
        const double cosine = (cluster.normal_sum.x * normal.x +
                               cluster.normal_sum.y * normal.y +
                               cluster.normal_sum.z * normal.z) /
                              (cluster_norm * normal_norm);
        compatible = cosine >= sharp_cosine;
      }
    }
    if (compatible && has_texcoords && config.preserve_uv_seams) {
      const auto& uv = input.texcoords[vertex];
      const double minimum_u = std::min(cluster.uv_min_x, static_cast<double>(uv.x));
      const double minimum_v = std::min(cluster.uv_min_y, static_cast<double>(uv.y));
      const double maximum_u = std::max(cluster.uv_max_x, static_cast<double>(uv.x));
      const double maximum_v = std::max(cluster.uv_max_y, static_cast<double>(uv.y));
      compatible = std::hypot(maximum_u - minimum_u, maximum_v - minimum_v) <=
                   config.max_uv_error;
    }
    if (!compatible) {
      ++result.rejected_merges;
      const auto created = create_cluster(vertex);
      if (!created) {
        result.truncated = true;
        result.error = "mesh cluster budget exceeded";
        return result;
      }
      vertex_cluster[vertex] = *created;
      continue;
    }
    cluster.minimum = candidate_minimum;
    cluster.maximum = candidate_maximum;
    cluster.position_sum += Vec3{position.x, position.y, position.z};
    if (has_normals) {
      const auto& normal = input.normals[vertex];
      cluster.normal_sum += Vec3{normal.x, normal.y, normal.z};
    }
    if (has_texcoords) {
      cluster.uv_sum_x += input.texcoords[vertex].x;
      cluster.uv_sum_y += input.texcoords[vertex].y;
      cluster.uv_min_x = std::min(cluster.uv_min_x,
                                  static_cast<double>(input.texcoords[vertex].x));
      cluster.uv_min_y = std::min(cluster.uv_min_y,
                                  static_cast<double>(input.texcoords[vertex].y));
      cluster.uv_max_x = std::max(cluster.uv_max_x,
                                  static_cast<double>(input.texcoords[vertex].x));
      cluster.uv_max_y = std::max(cluster.uv_max_y,
                                  static_cast<double>(input.texcoords[vertex].y));
    }
    ++cluster.count;
    vertex_cluster[vertex] = found->second;
  }

  std::vector<Vec3f> cluster_positions(clusters.size());
  std::vector<Vec2f> cluster_uvs(has_texcoords ? clusters.size() : 0);
  for (std::size_t cluster = 0; cluster < clusters.size(); ++cluster) {
    const double inverse_count = 1.0 / clusters[cluster].count;
    cluster_positions[cluster] = {
        static_cast<float>(clusters[cluster].position_sum.x * inverse_count),
        static_cast<float>(clusters[cluster].position_sum.y * inverse_count),
        static_cast<float>(clusters[cluster].position_sum.z * inverse_count)};
    if (has_texcoords) {
      cluster_uvs[cluster] = {
          static_cast<float>(clusters[cluster].uv_sum_x * inverse_count),
          static_cast<float>(clusters[cluster].uv_sum_y * inverse_count)};
    }
  }
  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    const auto& original = input.positions_m[vertex];
    const auto& simplified = cluster_positions[vertex_cluster[vertex]];
    result.max_observed_displacement_m = std::max(
        result.max_observed_displacement_m,
        std::sqrt(std::pow(static_cast<double>(original.x) - simplified.x, 2) +
                  std::pow(static_cast<double>(original.y) - simplified.y, 2) +
                  std::pow(static_cast<double>(original.z) - simplified.z, 2)));
  }

  struct TriangleKey {
    std::array<std::size_t, 3> indices{};
    bool operator==(const TriangleKey& other) const { return indices == other.indices; }
  };
  struct TriangleHash {
    std::size_t operator()(const TriangleKey& triangle) const {
      std::size_t seed = triangle.indices[0];
      for (int i = 1; i < 3; ++i) {
        seed ^= triangle.indices[i] + 0x9e3779b9U + (seed << 6) + (seed >> 2);
      }
      return seed;
    }
  };
  std::unordered_set<TriangleKey, TriangleHash> unique_triangles;
  unique_triangles.reserve(triangle_count);
  std::vector<std::array<std::size_t, 3>> retained_triangles;
  retained_triangles.reserve(triangle_count);
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    std::array<std::size_t, 3> mapped{
        vertex_cluster[input.triangle_indices[triangle * 3]],
        vertex_cluster[input.triangle_indices[triangle * 3 + 1]],
        vertex_cluster[input.triangle_indices[triangle * 3 + 2]],
    };
    if (mapped[0] == mapped[1] || mapped[0] == mapped[2] || mapped[1] == mapped[2]) {
      ++result.removed_degenerate_triangles;
      continue;
    }
    const auto& a = cluster_positions[mapped[0]];
    const auto& b = cluster_positions[mapped[1]];
    const auto& c = cluster_positions[mapped[2]];
    const Vec3 ab{static_cast<double>(b.x) - a.x,
                  static_cast<double>(b.y) - a.y,
                  static_cast<double>(b.z) - a.z};
    const Vec3 ac{static_cast<double>(c.x) - a.x,
                  static_cast<double>(c.y) - a.y,
                  static_cast<double>(c.z) - a.z};
    const Vec3 area_normal{ab.y * ac.z - ab.z * ac.y,
                           ab.z * ac.x - ab.x * ac.z,
                           ab.x * ac.y - ab.y * ac.x};
    if (area_normal.norm() * 0.5 < config.min_triangle_area_m2) {
      ++result.removed_degenerate_triangles;
      continue;
    }
    auto canonical = mapped;
    std::sort(canonical.begin(), canonical.end());
    if (!unique_triangles.emplace(TriangleKey{canonical}).second) {
      ++result.removed_duplicate_triangles;
      continue;
    }
    retained_triangles.push_back(mapped);
  }

  result.mesh.name = input.name;
  result.mesh.base_color_texture = input.base_color_texture;
  std::vector<std::uint32_t> cluster_remap(clusters.size(),
      std::numeric_limits<std::uint32_t>::max());
  for (const auto& triangle : retained_triangles) {
    for (const std::size_t cluster : triangle) {
      std::uint32_t& output_index = cluster_remap[cluster];
      if (output_index == std::numeric_limits<std::uint32_t>::max()) {
        output_index = static_cast<std::uint32_t>(result.mesh.positions_m.size());
        result.mesh.positions_m.push_back(cluster_positions[cluster]);
        if (has_texcoords) result.mesh.texcoords.push_back(cluster_uvs[cluster]);
      }
      result.mesh.triangle_indices.push_back(output_index);
    }
  }

  std::vector<Vec3> normal_sums(result.mesh.positions_m.size());
  std::vector<Vec3> normal_fallbacks(result.mesh.positions_m.size());
  for (std::size_t i = 0; i < result.mesh.triangle_indices.size(); i += 3) {
    const std::uint32_t ia = result.mesh.triangle_indices[i];
    const std::uint32_t ib = result.mesh.triangle_indices[i + 1];
    const std::uint32_t ic = result.mesh.triangle_indices[i + 2];
    const auto& a = result.mesh.positions_m[ia];
    const auto& b = result.mesh.positions_m[ib];
    const auto& c = result.mesh.positions_m[ic];
    const Vec3 ab{static_cast<double>(b.x) - a.x,
                  static_cast<double>(b.y) - a.y,
                  static_cast<double>(b.z) - a.z};
    const Vec3 ac{static_cast<double>(c.x) - a.x,
                  static_cast<double>(c.y) - a.y,
                  static_cast<double>(c.z) - a.z};
    const Vec3 normal{ab.y * ac.z - ab.z * ac.y,
                      ab.z * ac.x - ab.x * ac.z,
                      ab.x * ac.y - ab.y * ac.x};
    for (const std::uint32_t index : {ia, ib, ic}) {
      normal_sums[index] += normal;
      if (normal_fallbacks[index].norm() < 1e-12) normal_fallbacks[index] = normal;
    }
  }
  result.mesh.normals.reserve(result.mesh.positions_m.size());
  for (std::size_t vertex = 0; vertex < normal_sums.size(); ++vertex) {
    Vec3 normal = normal_sums[vertex];
    double norm = normal.norm();
    if (!std::isfinite(norm) || norm < 1e-12) {
      normal = normal_fallbacks[vertex];
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
  result.output_vertices = result.mesh.positions_m.size();
  result.output_triangles = result.mesh.triangle_indices.size() / 3;
  if (!result.mesh.positions_m.empty()) {
    const auto output_validation = validateMesh(
        result.mesh, {config.max_input_vertices, config.max_input_triangles});
    if (!output_validation.valid) result.error = output_validation.error;
  }
  return result;
}

}  // namespace arkernel
