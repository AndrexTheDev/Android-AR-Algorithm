#include "arkernel/mesh_processing.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <numeric>
#include <optional>
#include <stdexcept>
#include <utility>
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

// Symmetric 4x4 plane quadric stored as q00,q01,q02,q03,q11,q12,q13,q22,q23,q33.
struct Quadric {
  std::array<double, 10> q{};
  Quadric& operator+=(const Quadric& other) {
    for (std::size_t i = 0; i < q.size(); ++i) q[i] += other.q[i];
    return *this;
  }
};

Quadric planeQuadric(const Vec3& normal, const Vec3f& point) {
  const double nx = normal.x, ny = normal.y, nz = normal.z;
  const double d = -(nx * point.x + ny * point.y + nz * point.z);
  Quadric q;
  q.q = {nx * nx, nx * ny, nx * nz, nx * d, ny * ny, ny * nz, ny * d,
         nz * nz, nz * d, d * d};
  return q;
}

double quadricError(const Quadric& q, const Vec3& point) {
  const double x = point.x, y = point.y, z = point.z;
  return q.q[0] * x * x + 2.0 * q.q[1] * x * y + 2.0 * q.q[2] * x * z +
         2.0 * q.q[3] * x + q.q[4] * y * y + 2.0 * q.q[5] * y * z +
         2.0 * q.q[6] * y + q.q[7] * z * z + 2.0 * q.q[8] * z + q.q[9];
}

Vec3 optimalQuadricPosition(const Quadric& q, const Vec3& fallback,
                            double regularization) {
  double matrix[3][4] = {
      {q.q[0] + regularization, q.q[1], q.q[2], -q.q[3]},
      {q.q[1], q.q[4] + regularization, q.q[5], -q.q[6]},
      {q.q[2], q.q[5], q.q[7] + regularization, -q.q[8]},
  };
  for (int column = 0; column < 3; ++column) {
    int pivot = column;
    for (int row = column + 1; row < 3; ++row) {
      if (std::abs(matrix[row][column]) > std::abs(matrix[pivot][column]))
        pivot = row;
    }
    if (!std::isfinite(matrix[pivot][column]) ||
        std::abs(matrix[pivot][column]) < 1e-12)
      return fallback;
    if (pivot != column) {
      for (int i = 0; i < 4; ++i)
        std::swap(matrix[pivot][i], matrix[column][i]);
    }
    const double divisor = matrix[column][column];
    for (int i = 0; i < 4; ++i) matrix[column][i] /= divisor;
    for (int row = 0; row < 3; ++row) {
      if (row == column) continue;
      const double factor = matrix[row][column];
      for (int i = 0; i < 4; ++i) matrix[row][i] -= factor * matrix[column][i];
    }
  }
  const Vec3 result{matrix[0][3], matrix[1][3], matrix[2][3]};
  return std::isfinite(result.x) && std::isfinite(result.y) &&
                 std::isfinite(result.z)
             ? result
             : fallback;
}

Vec3 clampToBox(const Vec3& point, const Vec3& minimum, const Vec3& maximum) {
  return {std::max(minimum.x, std::min(maximum.x, point.x)),
          std::max(minimum.y, std::min(maximum.y, point.y)),
          std::max(minimum.z, std::min(maximum.z, point.z))};
}

struct FaceGeometry {
  Vec3 normal{};
  double normal_norm{0.0};
  double area_m2{0.0};
};

FaceGeometry faceGeometry(const std::array<std::uint32_t, 3>& triangle,
                          const std::vector<Vec3>& positions,
                          std::uint32_t override_vertex,
                          const Vec3& override_position) {
  const auto corner = [&](std::size_t index) -> Vec3 {
    const std::uint32_t vertex = triangle[index];
    return vertex == override_vertex ? override_position : positions[vertex];
  };
  const Vec3 a = corner(0), b = corner(1), c = corner(2);
  const Vec3 ab = b - a, ac = c - a;
  const Vec3 normal{ab.y * ac.z - ab.z * ac.y,
                    ab.z * ac.x - ab.x * ac.z,
                    ab.x * ac.y - ab.y * ac.x};
  const double normal_norm = normal.norm();
  return {normal, normal_norm, 0.5 * normal_norm};
}

Vec3 unitNormal(const FaceGeometry& geometry) {
  return geometry.normal_norm > 1e-12
             ? geometry.normal * (1.0 / geometry.normal_norm)
             : Vec3{};
}

Vec3f toVec3f(const Vec3& value) {
  return {static_cast<float>(value.x), static_cast<float>(value.y),
          static_cast<float>(value.z)};
}

Vec3 vec3Min(const Vec3& first, const Vec3& second) {
  return {std::min(first.x, second.x), std::min(first.y, second.y),
          std::min(first.z, second.z)};
}

Vec3 vec3Max(const Vec3& first, const Vec3& second) {
  return {std::max(first.x, second.x), std::max(first.y, second.y),
          std::max(first.z, second.z)};
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

MeshQuadricLodResult simplifyMeshQuadricError(
    const ReconstructionMesh& input, MeshQuadricLodConfig config) {
  if (!std::isfinite(config.cluster_cell_size_m) ||
      config.cluster_cell_size_m < 1e-5 || config.cluster_cell_size_m > 10.0 ||
      !std::isfinite(config.max_cluster_quadric_error_m2) ||
      config.max_cluster_quadric_error_m2 <= 0.0 ||
      config.max_cluster_quadric_error_m2 > 100.0 ||
      !std::isfinite(config.max_displacement_m) ||
      config.max_displacement_m <= 0.0 || config.max_displacement_m > 10.0 ||
      !std::isfinite(config.sharp_edge_angle_degrees) ||
      config.sharp_edge_angle_degrees <= 0.0 ||
      config.sharp_edge_angle_degrees >= 180.0 ||
      !std::isfinite(config.max_uv_error) || config.max_uv_error < 0.0 ||
      config.max_uv_error > 2.0 ||
      !std::isfinite(config.min_triangle_area_m2) ||
      config.min_triangle_area_m2 <= 0.0 ||
      !std::isfinite(config.regularization) || config.regularization <= 0.0 ||
      config.regularization > 1.0 || config.max_input_vertices == 0 ||
      config.max_input_vertices > 5000000 || config.max_input_triangles == 0 ||
      config.max_input_triangles > 5000000 || config.max_unique_edges == 0 ||
      config.max_unique_edges > 6000000 || config.max_clusters == 0 ||
      config.max_clusters > 5000000 || config.max_evaluations == 0 ||
      config.max_evaluations > 100000000) {
    throw std::invalid_argument("Invalid quadric-error LOD configuration");
  }

  MeshQuadricLodResult result;
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
  const bool has_texcoords = !input.texcoords.empty();
  const bool has_normals = !input.normals.empty();
  const double sharp_cosine = std::cos(
      config.sharp_edge_angle_degrees * 3.14159265358979323846 / 180.0);

  struct QuadricEdgeUse {
    std::size_t first_triangle{0};
    std::size_t second_triangle{0};
    std::size_t count{0};
  };
  std::vector<Vec3> face_normals(triangle_count);
  std::vector<bool> valid_faces(triangle_count, false);
  DisjointSet triangle_components(triangle_count);
  std::unordered_map<EdgeKey, QuadricEdgeUse, EdgeHash> edges;
  edges.reserve(std::min(config.max_unique_edges, triangle_count * 3));
  std::vector<Quadric> vertex_quadrics(vertex_count);
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
      normal = normal * (1.0 / norm);
      face_normals[triangle] = normal;
      valid_faces[triangle] = true;
      const Quadric plane = planeQuadric(normal, a);
      vertex_quadrics[ia] += plane;
      vertex_quadrics[ib] += plane;
      vertex_quadrics[ic] += plane;
    }
    const std::array<std::uint32_t, 3> indices{ia, ib, ic};
    for (int edge = 0; edge < 3; ++edge) {
      const EdgeKey key = edgeKey(indices[edge], indices[(edge + 1) % 3]);
      auto existing = edges.find(key);
      if (existing == edges.end()) {
        if (edges.size() >= config.max_unique_edges) {
          result.truncated = true;
          result.error = "quadric LOD edge budget exceeded";
          return result;
        }
        edges.emplace(key, QuadricEdgeUse{triangle, triangle, 1});
      } else {
        triangle_components.unite(triangle, existing->second.first_triangle);
        if (existing->second.count == 1)
          existing->second.second_triangle = triangle;
        ++existing->second.count;
      }
    }
  }
  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    const auto& position = input.positions_m[vertex];
    result.initial_quadric_error_m2 += quadricError(
        vertex_quadrics[vertex],
        Vec3{position.x, position.y, position.z});
  }

  std::vector<bool> locked(vertex_count, false);
  for (const auto& item : edges) {
    const bool boundary = item.second.count == 1;
    const bool non_manifold = item.second.count > 2;
    bool sharp = false;
    if (item.second.count == 2 &&
        valid_faces[item.second.first_triangle] &&
        valid_faces[item.second.second_triangle]) {
      const Vec3& first = face_normals[item.second.first_triangle];
      const Vec3& second = face_normals[item.second.second_triangle];
      sharp = first.x * second.x + first.y * second.y + first.z * second.z <
              sharp_cosine;
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
  std::vector<std::size_t> vertex_component(
      vertex_count, std::numeric_limits<std::size_t>::max());
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
    if (vertex_component[vertex] == std::numeric_limits<std::size_t>::max())
      locked[vertex] = true;
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
    Quadric quadric_sum{};
    Vec3 position_sum{};
    Vec3 normal_sum{};
    Vec3 minimum{};
    Vec3 maximum{};
    double uv_sum_x{0.0};
    double uv_sum_y{0.0};
    double uv_min_x{0.0};
    double uv_max_x{0.0};
    double uv_min_y{0.0};
    double uv_max_y{0.0};
    std::size_t count{0};
  };
  std::vector<Cluster> clusters;
  clusters.reserve(std::min(config.max_clusters, vertex_count));
  std::unordered_map<ClusterKey, std::size_t, ClusterHash> cluster_lookup;
  cluster_lookup.reserve(std::min(config.max_clusters, vertex_count));
  std::vector<std::size_t> vertex_cluster(vertex_count);
  const auto grid_coordinate = [&](float value) -> std::optional<std::int64_t> {
    const double coordinate = std::floor(
        static_cast<double>(value) / config.cluster_cell_size_m);
    if (!std::isfinite(coordinate) ||
        coordinate < static_cast<double>(std::numeric_limits<std::int64_t>::min()) ||
        coordinate > static_cast<double>(std::numeric_limits<std::int64_t>::max()))
      return std::nullopt;
    return static_cast<std::int64_t>(coordinate);
  };
  const auto create_cluster = [&](std::size_t vertex) -> std::optional<std::size_t> {
    if (clusters.size() >= config.max_clusters) return std::nullopt;
    const auto& position = input.positions_m[vertex];
    Cluster cluster;
    cluster.quadric_sum = vertex_quadrics[vertex];
    cluster.position_sum = {position.x, position.y, position.z};
    cluster.minimum = cluster.maximum = cluster.position_sum;
    if (has_normals) {
      const auto& normal = input.normals[vertex];
      cluster.normal_sum = {normal.x, normal.y, normal.z};
    }
    if (has_texcoords) {
      const auto& uv = input.texcoords[vertex];
      cluster.uv_sum_x = cluster.uv_min_x = cluster.uv_max_x = uv.x;
      cluster.uv_sum_y = cluster.uv_min_y = cluster.uv_max_y = uv.y;
    }
    cluster.count = 1;
    clusters.push_back(cluster);
    return std::optional<std::size_t>{clusters.size() - 1};
  };
  const auto fail_truncated = [&](const char* error) {
    result.truncated = true;
    result.error = error;
  };
  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    const auto& position = input.positions_m[vertex];
    if (locked[vertex]) {
      const auto created = create_cluster(vertex);
      if (!created) {
        fail_truncated("quadric LOD cluster budget exceeded");
        return result;
      }
      vertex_cluster[vertex] = *created;
      continue;
    }
    const auto grid_x = grid_coordinate(position.x);
    const auto grid_y = grid_coordinate(position.y);
    const auto grid_z = grid_coordinate(position.z);
    if (!grid_x || !grid_y || !grid_z) {
      result.error = "quadric LOD position exceeds clustering coordinate range";
      return result;
    }
    const ClusterKey key{*grid_x, *grid_y, *grid_z, vertex_component[vertex]};
    auto found = cluster_lookup.find(key);
    if (found == cluster_lookup.end()) {
      const auto created = create_cluster(vertex);
      if (!created) {
        fail_truncated("quadric LOD cluster budget exceeded");
        return result;
      }
      cluster_lookup.emplace(key, *created);
      vertex_cluster[vertex] = *created;
      continue;
    }
    if (result.candidate_clusters >= config.max_evaluations) {
      fail_truncated("quadric LOD evaluation budget exceeded");
      return result;
    }
    ++result.candidate_clusters;
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
                      config.max_displacement_m;
    if (compatible && has_normals) {
      const auto& normal = input.normals[vertex];
      const double cluster_norm = cluster.normal_sum.norm();
      const double normal_norm = std::sqrt(
          static_cast<double>(normal.x) * normal.x +
          static_cast<double>(normal.y) * normal.y +
          static_cast<double>(normal.z) * normal.z);
      if (cluster_norm > 1e-12 && normal_norm > 1e-12) {
        const double cosine =
            (cluster.normal_sum.x * normal.x + cluster.normal_sum.y * normal.y +
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
    if (compatible) {
      Quadric candidate_quadric = cluster.quadric_sum;
      candidate_quadric += vertex_quadrics[vertex];
      const Vec3 candidate_centroid{
          (cluster.position_sum.x + position.x) / (cluster.count + 1),
          (cluster.position_sum.y + position.y) / (cluster.count + 1),
          (cluster.position_sum.z + position.z) / (cluster.count + 1)};
      const Vec3 optimal = optimalQuadricPosition(
          candidate_quadric, candidate_centroid, config.regularization);
      const Vec3 clamped = clampToBox(optimal, candidate_minimum, candidate_maximum);
      const double error = quadricError(candidate_quadric, clamped);
      compatible = std::isfinite(error) &&
                   error <= config.max_cluster_quadric_error_m2;
    }
    if (!compatible) {
      ++result.rejected_clusters;
      const auto created = create_cluster(vertex);
      if (!created) {
        fail_truncated("quadric LOD cluster budget exceeded");
        return result;
      }
      vertex_cluster[vertex] = *created;
      continue;
    }
    cluster.quadric_sum += vertex_quadrics[vertex];
    cluster.minimum = candidate_minimum;
    cluster.maximum = candidate_maximum;
    cluster.position_sum += Vec3{position.x, position.y, position.z};
    if (has_normals) {
      const auto& normal = input.normals[vertex];
      cluster.normal_sum += Vec3{normal.x, normal.y, normal.z};
    }
    if (has_texcoords) {
      const auto& uv = input.texcoords[vertex];
      cluster.uv_sum_x += uv.x;
      cluster.uv_sum_y += uv.y;
      cluster.uv_min_x = std::min(cluster.uv_min_x, static_cast<double>(uv.x));
      cluster.uv_max_x = std::max(cluster.uv_max_x, static_cast<double>(uv.x));
      cluster.uv_min_y = std::min(cluster.uv_min_y, static_cast<double>(uv.y));
      cluster.uv_max_y = std::max(cluster.uv_max_y, static_cast<double>(uv.y));
    }
    ++cluster.count;
    vertex_cluster[vertex] = found->second;
  }
  result.accepted_clusters = static_cast<std::size_t>(std::count_if(
      clusters.begin(), clusters.end(),
      [](const Cluster& cluster) { return cluster.count > 1; }));

  std::vector<Vec3> cluster_positions(clusters.size());
  std::vector<Vec2f> cluster_uvs(has_texcoords ? clusters.size() : 0);
  for (std::size_t cluster = 0; cluster < clusters.size(); ++cluster) {
    const double inverse_count = 1.0 / clusters[cluster].count;
    const Vec3 centroid{clusters[cluster].position_sum.x * inverse_count,
                        clusters[cluster].position_sum.y * inverse_count,
                        clusters[cluster].position_sum.z * inverse_count};
    const Vec3 optimal = optimalQuadricPosition(
        clusters[cluster].quadric_sum, centroid, config.regularization);
    cluster_positions[cluster] = clampToBox(
        optimal, clusters[cluster].minimum, clusters[cluster].maximum);
    const double error = quadricError(
        clusters[cluster].quadric_sum, cluster_positions[cluster]);
    if (!std::isfinite(error)) {
      result.error = "quadric LOD representative is non-finite";
      return result;
    }
    result.final_quadric_error_m2 += error;
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
    bool operator==(const TriangleKey& other) const {
      return indices == other.indices;
    }
  };
  struct TriangleHash {
    std::size_t operator()(const TriangleKey& triangle) const {
      std::size_t seed = triangle.indices[0];
      for (int i = 1; i < 3; ++i)
        seed ^= triangle.indices[i] + 0x9e3779b9U + (seed << 6) + (seed >> 2);
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
    if (mapped[0] == mapped[1] || mapped[0] == mapped[2] ||
        mapped[1] == mapped[2]) {
      ++result.removed_degenerate_triangles;
      continue;
    }
    const auto& a = cluster_positions[mapped[0]];
    const auto& b = cluster_positions[mapped[1]];
    const auto& c = cluster_positions[mapped[2]];
    const Vec3 ab{b.x - a.x, b.y - a.y, b.z - a.z};
    const Vec3 ac{c.x - a.x, c.y - a.y, c.z - a.z};
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
  std::vector<std::uint32_t> cluster_remap(
      clusters.size(), std::numeric_limits<std::uint32_t>::max());
  for (const auto& triangle : retained_triangles) {
    for (const std::size_t cluster : triangle) {
      std::uint32_t& output_index = cluster_remap[cluster];
      if (output_index == std::numeric_limits<std::uint32_t>::max()) {
        output_index = static_cast<std::uint32_t>(result.mesh.positions_m.size());
        const auto& position = cluster_positions[cluster];
        result.mesh.positions_m.push_back({static_cast<float>(position.x),
                                           static_cast<float>(position.y),
                                           static_cast<float>(position.z)});
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
      if (normal_fallbacks[index].norm() < 1e-12)
        normal_fallbacks[index] = normal;
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


// Bounded deterministic edge-collapse QEM simplification. Unlike the measured
// clustering LOD, this stage changes topology: each accepted collapse replaces
// one endpoint, removes the two incident triangles, rebuilds plane quadrics
// and recomputes feature locks from the current triangles. Cumulative original
// position/UV bounds prevent representative chains from drifting past a
// per-collapse budget.
MeshEdgeCollapseQemResult simplifyMeshEdgeCollapseQem(
    const ReconstructionMesh& input, MeshEdgeCollapseQemConfig config) {
  if (!std::isfinite(config.max_edge_quadric_error_m2) ||
      config.max_edge_quadric_error_m2 <= 0.0 ||
      config.max_edge_quadric_error_m2 > 100.0 ||
      !std::isfinite(config.max_displacement_m) ||
      config.max_displacement_m <= 0.0 || config.max_displacement_m > 10.0 ||
      !std::isfinite(config.sharp_edge_angle_degrees) ||
      config.sharp_edge_angle_degrees <= 0.0 ||
      config.sharp_edge_angle_degrees >= 180.0 ||
      !std::isfinite(config.max_uv_error) || config.max_uv_error < 0.0 ||
      config.max_uv_error > 2.0 ||
      !std::isfinite(config.min_triangle_area_m2) ||
      config.min_triangle_area_m2 <= 0.0 ||
      !std::isfinite(config.regularization) || config.regularization <= 0.0 ||
      config.regularization > 1.0 ||
      !std::isfinite(config.min_normal_dot) ||
      config.min_normal_dot < -1.0 || config.min_normal_dot > 1.0 ||
      config.max_input_vertices == 0 ||
      config.max_input_vertices > 5000000 ||
      config.max_input_triangles == 0 ||
      config.max_input_triangles > 5000000 ||
      config.max_unique_edges == 0 ||
      config.max_unique_edges > 6000000 || config.max_collapses == 0 ||
      config.max_collapses > 1000000 || config.max_evaluations == 0 ||
      config.max_evaluations > 100000000) {
    throw std::invalid_argument("Invalid edge-collapse QEM configuration");
  }

  MeshEdgeCollapseQemResult result;
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
  const bool has_texcoords = !input.texcoords.empty();
  const double sharp_cosine = std::cos(
      config.sharp_edge_angle_degrees * 3.14159265358979323846 / 180.0);
  const std::uint32_t kNoOverride =
      std::numeric_limits<std::uint32_t>::max();

  struct EdgeCollapseUse {
    std::size_t first_triangle{0};
    std::size_t second_triangle{0};
    std::size_t count{0};
  };
  struct Candidate {
    double error{0.0};
    std::uint32_t u{0};
    std::uint32_t v{0};
    Vec3 position{};
    std::size_t first_triangle{0};
    std::size_t second_triangle{0};
  };
  struct TriangleKey {
    std::array<std::uint32_t, 3> indices{};
    bool operator==(const TriangleKey& other) const {
      return indices == other.indices;
    }
  };
  struct TriangleHash {
    std::size_t operator()(const TriangleKey& triangle) const {
      std::size_t seed = triangle.indices[0];
      for (int i = 1; i < 3; ++i) {
        seed ^= static_cast<std::size_t>(triangle.indices[i]) +
                0x9e3779b9U + (seed << 6) + (seed >> 2);
      }
      return seed;
    }
  };

  std::vector<Vec3> positions;
  positions.reserve(vertex_count);
  for (const auto& position : input.positions_m) {
    positions.push_back({position.x, position.y, position.z});
  }
  std::vector<std::array<std::uint32_t, 3>> active_triangles;
  active_triangles.reserve(triangle_count);
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    active_triangles.push_back(
        {input.triangle_indices[triangle * 3],
         input.triangle_indices[triangle * 3 + 1],
         input.triangle_indices[triangle * 3 + 2]});
  }
  std::vector<std::uint32_t> merged_into(vertex_count);
  std::iota(merged_into.begin(), merged_into.end(), 0U);
  std::vector<std::vector<std::uint32_t>> group_members(vertex_count);
  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    group_members[vertex].push_back(static_cast<std::uint32_t>(vertex));
  }
  std::vector<bool> alive(vertex_count, true);
  std::vector<Quadric> quadrics(vertex_count);

  const auto recomputeQuadrics = [&]() {
    quadrics.assign(vertex_count, Quadric{});
    for (const auto& triangle : active_triangles) {
      const FaceGeometry geometry =
          faceGeometry(triangle, positions, kNoOverride, Vec3{});
      if (!std::isfinite(geometry.normal_norm) ||
          geometry.area_m2 < config.min_triangle_area_m2) {
        continue;
      }
      const Quadric plane = planeQuadric(
          unitNormal(geometry), toVec3f(positions[triangle[0]]));
      for (const std::uint32_t vertex : triangle) {
        quadrics[vertex] += plane;
      }
    }
  };
  const auto uvDistance = [&](std::uint32_t first,
                              std::uint32_t second) -> double {
    if (!has_texcoords) return 0.0;
    return std::hypot(
        static_cast<double>(input.texcoords[first].x) -
            input.texcoords[second].x,
        static_cast<double>(input.texcoords[first].y) -
            input.texcoords[second].y);
  };
  const auto originalPosition = [&](std::uint32_t vertex) -> Vec3 {
    return {input.positions_m[vertex].x, input.positions_m[vertex].y,
            input.positions_m[vertex].z};
  };
  const auto truncatedResult =
      [&](const char* message) -> MeshEdgeCollapseQemResult {
    result.truncated = true;
    result.error = message;
    return result;
  };
  const auto addCandidateEdges = [&](std::size_t value) -> bool {
    if (value > std::numeric_limits<std::size_t>::max() -
                   result.candidate_edges) {
      result.truncated = true;
      result.error = "edge-collapse QEM evaluation budget exceeded";
      return false;
    }
    result.candidate_edges += value;
    return true;
  };

  recomputeQuadrics();
  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    result.initial_quadric_error_m2 +=
        quadricError(quadrics[vertex], positions[vertex]);
  }

  bool first_iteration = true;
  while (result.accepted_collapses < config.max_collapses) {
    std::vector<FaceGeometry> face_geometries(active_triangles.size());
    std::vector<bool> valid_faces(active_triangles.size(), false);
    DisjointSet triangle_components(active_triangles.size());
    std::vector<std::unordered_set<std::uint32_t>> neighbors(vertex_count);
    std::unordered_map<EdgeKey, EdgeCollapseUse, EdgeHash> edges;
    edges.reserve(std::min(config.max_unique_edges,
                           active_triangles.size() * 3));
    for (std::size_t triangle = 0; triangle < active_triangles.size();
         ++triangle) {
      const auto& indices = active_triangles[triangle];
      const FaceGeometry geometry =
          faceGeometry(indices, positions, kNoOverride, Vec3{});
      face_geometries[triangle] = geometry;
      valid_faces[triangle] = std::isfinite(geometry.normal_norm) &&
                              geometry.area_m2 >=
                                  config.min_triangle_area_m2;
      for (int edge = 0; edge < 3; ++edge) {
        const EdgeKey key =
            edgeKey(indices[edge], indices[(edge + 1) % 3]);
        auto existing = edges.find(key);
        if (existing == edges.end()) {
          if (edges.size() >= config.max_unique_edges) {
            return truncatedResult("edge-collapse QEM edge budget exceeded");
          }
          edges.emplace(key,
                        EdgeCollapseUse{triangle, triangle, 1});
        } else {
          triangle_components.unite(
              triangle, existing->second.first_triangle);
          if (existing->second.count == 1) {
            existing->second.second_triangle = triangle;
          }
          ++existing->second.count;
        }
        neighbors[key.first].insert(key.second);
        neighbors[key.second].insert(key.first);
      }
    }

    std::vector<bool> locked(vertex_count, false);
    for (const auto& item : edges) {
      const std::uint32_t u = item.first.first;
      const std::uint32_t v = item.first.second;
      const bool boundary = item.second.count == 1;
      const bool non_manifold = item.second.count > 2;
      bool sharp = false;
      if (item.second.count == 2 &&
          valid_faces[item.second.first_triangle] &&
          valid_faces[item.second.second_triangle]) {
        const Vec3 first_normal =
            unitNormal(face_geometries[item.second.first_triangle]);
        const Vec3 second_normal =
            unitNormal(face_geometries[item.second.second_triangle]);
        const double cosine =
            first_normal.x * second_normal.x +
            first_normal.y * second_normal.y +
            first_normal.z * second_normal.z;
        sharp = cosine < sharp_cosine;
      }
      const bool uv_seam = has_texcoords &&
                           config.preserve_uv_seams &&
                           uvDistance(u, v) > config.max_uv_error;
      if (first_iteration) {
        if (boundary) ++result.boundary_edges;
        if (non_manifold) ++result.non_manifold_edges;
        if (sharp) ++result.sharp_edges;
      }
      if ((boundary && config.preserve_boundaries) ||
          (non_manifold && config.preserve_non_manifold) ||
          (sharp && config.preserve_sharp_edges) ||
          (uv_seam && config.preserve_uv_seams)) {
        locked[u] = true;
        locked[v] = true;
      }
    }
    std::vector<std::size_t> vertex_component(
        vertex_count, std::numeric_limits<std::size_t>::max());
    for (std::size_t triangle = 0; triangle < active_triangles.size();
         ++triangle) {
      const std::size_t root = triangle_components.find(triangle);
      for (int corner = 0; corner < 3; ++corner) {
        const std::uint32_t vertex = active_triangles[triangle][corner];
        if (vertex_component[vertex] ==
            std::numeric_limits<std::size_t>::max()) {
          vertex_component[vertex] = root;
        } else if (vertex_component[vertex] != root) {
          locked[vertex] = true;
        }
      }
    }
    for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
      if (vertex_component[vertex] ==
          std::numeric_limits<std::size_t>::max()) {
        locked[vertex] = true;
      }
    }
    if (first_iteration) {
      result.locked_vertices = static_cast<std::size_t>(
          std::count(locked.begin(), locked.end(), true));
      first_iteration = false;
    }

    std::vector<Candidate> candidates;
    for (const auto& item : edges) {
      if (item.second.count != 2) continue;
      const std::uint32_t u = item.first.first;
      const std::uint32_t v = item.first.second;
      if (!alive[u] || !alive[v] || locked[u] || locked[v]) continue;
      bool sharp = false;
      if (valid_faces[item.second.first_triangle] &&
          valid_faces[item.second.second_triangle]) {
        const Vec3 first_normal =
            unitNormal(face_geometries[item.second.first_triangle]);
        const Vec3 second_normal =
            unitNormal(face_geometries[item.second.second_triangle]);
        const double cosine =
            first_normal.x * second_normal.x +
            first_normal.y * second_normal.y +
            first_normal.z * second_normal.z;
        sharp = cosine < sharp_cosine;
      }
      const bool uv_seam = has_texcoords &&
                           config.preserve_uv_seams &&
                           uvDistance(u, v) > config.max_uv_error;
      if ((config.preserve_sharp_edges && sharp) ||
          (config.preserve_uv_seams && uv_seam)) {
        continue;
      }
      Quadric combined = quadrics[u];
      combined += quadrics[v];
      const Vec3 midpoint = (positions[u] + positions[v]) * 0.5;
      const Vec3 optimum = optimalQuadricPosition(
          combined, midpoint, config.regularization);
      const Vec3 clamped = clampToBox(
          optimum, vec3Min(positions[u], positions[v]),
          vec3Max(positions[u], positions[v]));
      const double error = quadricError(combined, clamped);
      if (!std::isfinite(error) ||
          error > config.max_edge_quadric_error_m2 ||
          (clamped - positions[u]).norm() > config.max_displacement_m ||
          (clamped - positions[v]).norm() > config.max_displacement_m) {
        continue;
      }
      bool cumulative_ok = true;
      for (const std::uint32_t member : group_members[u]) {
        if ((clamped - originalPosition(member)).norm() >
            config.max_displacement_m) {
          cumulative_ok = false;
          break;
        }
      }
      if (cumulative_ok) {
        for (const std::uint32_t member : group_members[v]) {
          if ((clamped - originalPosition(member)).norm() >
              config.max_displacement_m) {
            cumulative_ok = false;
            break;
          }
        }
      }
      if (!cumulative_ok) continue;
      // A group representative can change when its endpoint is the larger
      // index of a later collapse. Check every original UV in both groups
      // against the new representative UV, not only the two endpoint UVs.
      if (has_texcoords && config.preserve_uv_seams) {
        bool cumulative_uv_ok = true;
        for (const std::uint32_t member : group_members[u]) {
          if (uvDistance(member, u) > config.max_uv_error) {
            cumulative_uv_ok = false;
            break;
          }
        }
        if (cumulative_uv_ok) {
          for (const std::uint32_t member : group_members[v]) {
            if (uvDistance(member, u) > config.max_uv_error) {
              cumulative_uv_ok = false;
              break;
            }
          }
        }
        if (!cumulative_uv_ok) continue;
      }
      candidates.push_back(Candidate{error, u, v, clamped,
                                     item.second.first_triangle,
                                     item.second.second_triangle});
    }
    if (!addCandidateEdges(candidates.size())) {
      return truncatedResult("edge-collapse QEM evaluation budget exceeded");
    }
    std::sort(candidates.begin(), candidates.end(),
              [](const Candidate& first, const Candidate& second) {
                if (first.error != second.error) {
                  return first.error < second.error;
                }
                if (first.u != second.u) return first.u < second.u;
                return first.v < second.v;
              });

    bool accepted_this_iteration = false;
    for (const auto& candidate : candidates) {
      if (result.evaluated_collapses >= config.max_evaluations) {
        return truncatedResult(
            "edge-collapse QEM evaluation budget exceeded");
      }
      ++result.evaluated_collapses;

      const std::array<std::size_t, 2> incident{
          candidate.first_triangle, candidate.second_triangle};
      std::uint32_t opposite[2]{0, 0};
      std::size_t opposite_count = 0;
      bool link_ok = true;
      for (const std::size_t triangle : incident) {
        const auto& indices = active_triangles[triangle];
        std::uint32_t third = kNoOverride;
        int endpoint_count = 0;
        for (const std::uint32_t vertex : indices) {
          if (vertex == candidate.u || vertex == candidate.v) {
            ++endpoint_count;
          } else {
            third = vertex;
          }
        }
        if (endpoint_count != 2 || third == kNoOverride) {
          link_ok = false;
          break;
        }
        opposite[opposite_count++] = third;
      }
      if (!link_ok || opposite_count != 2 ||
          opposite[0] == opposite[1]) {
        ++result.rejected_collapses;
        continue;
      }
      std::vector<std::uint32_t> common;
      for (const std::uint32_t neighbor : neighbors[candidate.u]) {
        if (neighbors[candidate.v].find(neighbor) !=
            neighbors[candidate.v].end()) {
          common.push_back(neighbor);
        }
      }
      std::sort(common.begin(), common.end());
      const std::uint32_t opposite_low =
          std::min(opposite[0], opposite[1]);
      const std::uint32_t opposite_high =
          std::max(opposite[0], opposite[1]);
      if (common.size() != 2 || common[0] != opposite_low ||
          common[1] != opposite_high) {
        ++result.rejected_collapses;
        continue;
      }

      std::vector<std::array<std::uint32_t, 3>> tentative;
      tentative.reserve(active_triangles.size());
      std::unordered_set<TriangleKey, TriangleHash> unique;
      unique.reserve(active_triangles.size());
      std::size_t removed_degenerate = 0;
      std::size_t removed_duplicate = 0;
      bool collapse_ok = true;
      for (std::size_t triangle = 0; triangle < active_triangles.size();
           ++triangle) {
        const auto original = active_triangles[triangle];
        auto mapped = original;
        bool had_v = false;
        bool had_u = false;
        for (int corner = 0; corner < 3; ++corner) {
          if (original[corner] == candidate.v) {
            mapped[corner] = candidate.u;
            had_v = true;
          }
          if (original[corner] == candidate.u) had_u = true;
        }
        const bool had_both = had_v && had_u;
        if (had_both) {
          ++removed_degenerate;
          continue;
        }
        if (has_texcoords && config.preserve_uv_seams && had_v) {
          const double delta_u =
              static_cast<double>(input.texcoords[candidate.v].x) -
              input.texcoords[candidate.u].x;
          const double delta_v =
              static_cast<double>(input.texcoords[candidate.v].y) -
              input.texcoords[candidate.u].y;
          if (std::hypot(delta_u, delta_v) > config.max_uv_error) {
            collapse_ok = false;
            break;
          }
        }
        if (mapped[0] == mapped[1] || mapped[0] == mapped[2] ||
            mapped[1] == mapped[2]) {
          ++removed_degenerate;
          continue;
        }
        const FaceGeometry original_geometry = face_geometries[triangle];
        const FaceGeometry tentative_geometry = faceGeometry(
            mapped, positions, candidate.u, candidate.position);
        if (!std::isfinite(tentative_geometry.normal_norm) ||
            tentative_geometry.area_m2 < config.min_triangle_area_m2) {
          collapse_ok = false;
          break;
        }
        bool contains_u = false;
        for (const std::uint32_t vertex : mapped) {
          if (vertex == candidate.u) {
            contains_u = true;
            break;
          }
        }
        if (contains_u && valid_faces[triangle]) {
          const Vec3 original_unit = unitNormal(original_geometry);
          const Vec3 tentative_unit = unitNormal(tentative_geometry);
          const double dot =
              original_unit.x * tentative_unit.x +
              original_unit.y * tentative_unit.y +
              original_unit.z * tentative_unit.z;
          if (dot < config.min_normal_dot) {
            collapse_ok = false;
            break;
          }
        }
        auto canonical = mapped;
        std::sort(canonical.begin(), canonical.end());
        if (!unique.emplace(TriangleKey{canonical}).second) {
          ++removed_duplicate;
          collapse_ok = false;
          break;
        }
        tentative.push_back(mapped);
      }
      if (!collapse_ok || tentative.empty()) {
        ++result.rejected_collapses;
        continue;
      }

      std::unordered_map<EdgeKey, std::size_t, EdgeHash> tentative_edges;
      tentative_edges.reserve(std::min(config.max_unique_edges,
                                       tentative.size() * 3));
      for (const auto& indices : tentative) {
        for (int edge = 0; edge < 3; ++edge) {
          const EdgeKey key =
              edgeKey(indices[edge], indices[(edge + 1) % 3]);
          auto found = tentative_edges.find(key);
          if (found == tentative_edges.end()) {
            if (tentative_edges.size() >= config.max_unique_edges) {
              return truncatedResult(
                  "edge-collapse QEM edge budget exceeded");
            }
            tentative_edges.emplace(key, std::size_t{1});
          } else {
            ++found->second;
          }
        }
      }
      for (const auto& item : tentative_edges) {
        if (item.second > 2) {
          collapse_ok = false;
          break;
        }
      }
      if (!collapse_ok) {
        ++result.rejected_collapses;
        continue;
      }

      ++result.accepted_collapses;
      result.removed_degenerate_triangles += removed_degenerate;
      result.removed_duplicate_triangles += removed_duplicate;
      active_triangles = std::move(tentative);
      positions[candidate.u] = candidate.position;
      alive[candidate.v] = false;
      merged_into[candidate.v] = candidate.u;
      for (const std::uint32_t member : group_members[candidate.v]) {
        group_members[candidate.u].push_back(member);
      }
      group_members[candidate.v].clear();
      recomputeQuadrics();
      accepted_this_iteration = true;
      break;
    }
    if (!accepted_this_iteration) break;
    if (result.accepted_collapses >= config.max_collapses) {
      return truncatedResult("edge-collapse QEM collapse budget exceeded");
    }
  }

  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    if (alive[vertex]) {
      result.final_quadric_error_m2 +=
          quadricError(quadrics[vertex], positions[vertex]);
    }
  }
  for (std::size_t vertex = 0; vertex < vertex_count; ++vertex) {
    std::uint32_t root = static_cast<std::uint32_t>(vertex);
    std::size_t steps = 0;
    while (merged_into[root] != root) {
      root = merged_into[root];
      if (++steps > vertex_count) {
        result.error = "edge-collapse QEM representative chain is invalid";
        return result;
      }
    }
    if (!alive[root]) {
      result.error = "edge-collapse QEM representative is inactive";
      return result;
    }
    const Vec3 original =
        originalPosition(static_cast<std::uint32_t>(vertex));
    result.max_observed_displacement_m = std::max(
        result.max_observed_displacement_m,
        (positions[root] - original).norm());
    if (has_texcoords) {
      result.max_observed_uv_error = std::max(
          result.max_observed_uv_error,
          uvDistance(static_cast<std::uint32_t>(vertex), root));
    }
  }

  result.mesh.name = input.name;
  result.mesh.base_color_texture = input.base_color_texture;
  std::unordered_set<TriangleKey, TriangleHash> final_unique;
  final_unique.reserve(active_triangles.size());
  std::vector<std::array<std::uint32_t, 3>> final_triangles;
  final_triangles.reserve(active_triangles.size());
  for (const auto& indices : active_triangles) {
    if (indices[0] == indices[1] || indices[0] == indices[2] ||
        indices[1] == indices[2]) {
      ++result.removed_degenerate_triangles;
      continue;
    }
    const FaceGeometry geometry =
        faceGeometry(indices, positions, kNoOverride, Vec3{});
    if (!std::isfinite(geometry.normal_norm) ||
        geometry.area_m2 < config.min_triangle_area_m2) {
      ++result.removed_degenerate_triangles;
      continue;
    }
    auto canonical = indices;
    std::sort(canonical.begin(), canonical.end());
    if (!final_unique.emplace(TriangleKey{canonical}).second) {
      ++result.removed_duplicate_triangles;
      continue;
    }
    final_triangles.push_back(indices);
  }
  if (final_triangles.empty()) {
    result.error = "edge-collapse QEM produced an empty mesh";
    return result;
  }

  std::vector<std::uint32_t> remap(vertex_count, kNoOverride);
  for (const auto& indices : final_triangles) {
    for (int corner = 0; corner < 3; ++corner) {
      const std::uint32_t old_index = indices[corner];
      std::uint32_t& new_index = remap[old_index];
      if (new_index == kNoOverride) {
        new_index = static_cast<std::uint32_t>(
            result.mesh.positions_m.size());
        result.mesh.positions_m.push_back(
            toVec3f(positions[old_index]));
        if (has_texcoords) {
          result.mesh.texcoords.push_back(input.texcoords[old_index]);
        }
      }
      result.mesh.triangle_indices.push_back(new_index);
    }
  }

  std::vector<Vec3> normal_sums(result.mesh.positions_m.size());
  std::vector<Vec3> normal_fallbacks(result.mesh.positions_m.size());
  for (const auto& indices : final_triangles) {
    const FaceGeometry geometry =
        faceGeometry(indices, positions, kNoOverride, Vec3{});
    const std::uint32_t ia = remap[indices[0]];
    const std::uint32_t ib = remap[indices[1]];
    const std::uint32_t ic = remap[indices[2]];
    for (const std::uint32_t index : {ia, ib, ic}) {
      normal_sums[index] += geometry.normal;
      if (normal_fallbacks[index].norm() < 1e-12) {
        normal_fallbacks[index] = geometry.normal;
      }
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
  if (result.mesh.positions_m.empty()) {
    result.error = "edge-collapse QEM produced an empty mesh";
    return result;
  }
  const auto output_validation = validateMesh(
      result.mesh, {config.max_input_vertices, config.max_input_triangles});
  if (!output_validation.valid) result.error = output_validation.error;
  return result;
}

}  // namespace arkernel
