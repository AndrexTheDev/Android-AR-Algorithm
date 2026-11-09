#include "arkernel/texture_atlas.h"

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

struct DisjointSet {
  explicit DisjointSet(std::size_t size) : parent(size), rank(size, 0) {
    std::iota(parent.begin(), parent.end(), 0);
  }
  std::size_t find(std::size_t value) {
    if (parent[value] != value) parent[value] = find(parent[value]);
    return parent[value];
  }
  void unite(std::size_t first, std::size_t second) {
    first = find(first); second = find(second);
    if (first == second) return;
    if (rank[first] < rank[second]) std::swap(first, second);
    parent[second] = first;
    if (rank[first] == rank[second]) ++rank[first];
  }
  std::vector<std::size_t> parent;
  std::vector<std::uint8_t> rank;
};

Vec3 toVec3(const Vec3f& value) { return {value.x, value.y, value.z}; }

}  // namespace

TextureAtlasLayoutResult layoutTextureAtlas(
    const ReconstructionMesh& mesh,
    const std::vector<TextureViewReference>& views,
    const std::vector<TriangleTextureView>& labels,
    TextureAtlasLayoutConfig config) {
  if (config.atlas_width < 16 || config.atlas_width > 16384 ||
      config.atlas_height < 16 || config.atlas_height > 16384 ||
      config.padding_px < 0 || config.padding_px > 256 ||
      config.padding_px * 2 + 1 >= config.atlas_width ||
      config.padding_px * 2 + 1 >= config.atlas_height ||
      !std::isfinite(config.min_uniform_scale) ||
      config.min_uniform_scale <= 0.0 || config.min_uniform_scale > 1.0 ||
      !std::isfinite(config.max_normal_angle_degrees) ||
      config.max_normal_angle_degrees <= 0.0 ||
      config.max_normal_angle_degrees >= 180.0 ||
      config.max_patches == 0 || config.max_patches > 1000000 ||
      config.max_input_vertices == 0 || config.max_input_vertices > 5000000 ||
      config.max_input_triangles == 0 || config.max_input_triangles > 5000000 ||
      config.max_unique_edges == 0 || config.max_unique_edges > 6000000 ||
      config.max_output_vertices < 3 || config.max_output_vertices > 10000000) {
    throw std::invalid_argument("Invalid texture atlas layout configuration");
  }

  TextureAtlasLayoutResult result;
  const auto validation = validateMesh(
      mesh, {config.max_input_vertices, config.max_input_triangles});
  if (!validation.valid) {
    result.error = validation.error;
    return result;
  }
  const std::size_t triangle_count = mesh.triangle_indices.size() / 3;
  if (labels.size() != triangle_count || views.empty()) {
    result.error = "texture labels do not match mesh";
    return result;
  }
  std::unordered_set<std::uint64_t> frame_ids;
  for (const auto& view : views) {
    if (view.frame_id == 0 || view.image_reference.empty() ||
        view.width < 2 || view.width > 32768 ||
        view.height < 2 || view.height > 32768 ||
        !frame_ids.insert(view.frame_id).second) {
      result.error = "invalid texture view reference";
      return result;
    }
  }
  for (const auto& label : labels) {
    if (!label.assigned) continue;
    if (label.view_index >= views.size() ||
        views[label.view_index].frame_id != label.frame_id) {
      result.error = "invalid texture label";
      return result;
    }
    for (const auto& uv : label.normalized_uv) {
      if (!std::isfinite(uv.x) || !std::isfinite(uv.y) ||
          uv.x < -1e-6F || uv.x > 1.000001F ||
          uv.y < -1e-6F || uv.y > 1.000001F) {
        result.error = "texture source UV is invalid";
        return result;
      }
    }
  }

  result.input_valid = true;
  const std::size_t no_patch = std::numeric_limits<std::size_t>::max();
  result.source_triangle_patch.assign(triangle_count, no_patch);
  DisjointSet components(triangle_count);
  std::vector<Vec3> face_normals(triangle_count);
  std::vector<bool> face_normal_valid(triangle_count, false);
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    if (!labels[triangle].assigned) {
      ++result.omitted_unassigned_triangles;
      continue;
    }
    ++result.assigned_triangles;
    const Vec3 a = toVec3(mesh.positions_m[mesh.triangle_indices[triangle * 3]]);
    const Vec3 b = toVec3(mesh.positions_m[mesh.triangle_indices[triangle * 3 + 1]]);
    const Vec3 c = toVec3(mesh.positions_m[mesh.triangle_indices[triangle * 3 + 2]]);
    const Vec3 ab = b - a, ac = c - a;
    const Vec3 normal{ab.y * ac.z - ab.z * ac.y,
                      ab.z * ac.x - ab.x * ac.z,
                      ab.x * ac.y - ab.y * ac.x};
    const double length = normal.norm();
    if (std::isfinite(length) && length > 1e-15) {
      face_normals[triangle] = normal * (1.0 / length);
      face_normal_valid[triangle] = true;
    }
  }
  if (config.require_all_triangles_assigned &&
      result.omitted_unassigned_triangles != 0) {
    result.error = "atlas requires every triangle to have a texture label";
    return result;
  }
  if (result.assigned_triangles == 0) {
    result.error = "atlas has no assigned triangles";
    return result;
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
  std::unordered_map<EdgeKey, EdgeUse, EdgeHash> edges;
  edges.reserve(std::min(config.max_unique_edges, triangle_count * 3));
  const auto make_edge = [](std::uint32_t first, std::uint32_t second) {
    return first < second ? EdgeKey{first, second} : EdgeKey{second, first};
  };
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    if (!labels[triangle].assigned) continue;
    const std::array<std::uint32_t, 3> indices{
        mesh.triangle_indices[triangle * 3],
        mesh.triangle_indices[triangle * 3 + 1],
        mesh.triangle_indices[triangle * 3 + 2]};
    for (std::size_t side = 0; side < 3; ++side) {
      if (indices[side] == indices[(side + 1) % 3]) continue;
      const EdgeKey key = make_edge(indices[side], indices[(side + 1) % 3]);
      auto found = edges.find(key);
      if (found == edges.end()) {
        if (edges.size() >= config.max_unique_edges) {
          result.truncated = true;
          result.error = "texture atlas edge budget exceeded";
          return result;
        }
        edges.emplace(key, EdgeUse{triangle, triangle, 1});
      } else {
        if (found->second.count == 1) found->second.second_triangle = triangle;
        ++found->second.count;
      }
    }
  }
  const double minimum_normal_cosine = std::cos(
      config.max_normal_angle_degrees * 3.14159265358979323846 / 180.0);
  for (const auto& item : edges) {
    if (item.second.count == 1) {
      ++result.boundary_edges;
      continue;
    }
    if (item.second.count > 2) {
      ++result.non_manifold_edges;
      continue;
    }
    const std::size_t first = item.second.first_triangle;
    const std::size_t second = item.second.second_triangle;
    const double cosine = face_normal_valid[first] && face_normal_valid[second] ?
        face_normals[first].x * face_normals[second].x +
        face_normals[first].y * face_normals[second].y +
        face_normals[first].z * face_normals[second].z : -1.0;
    if (cosine < minimum_normal_cosine) {
      ++result.sharp_edges;
      continue;
    }
    if (labels[first].view_index == labels[second].view_index)
      components.unite(first, second);
  }

  struct PatchData {
    std::size_t view_index{0};
    double min_x{std::numeric_limits<double>::infinity()};
    double min_y{std::numeric_limits<double>::infinity()};
    double max_x{-std::numeric_limits<double>::infinity()};
    double max_y{-std::numeric_limits<double>::infinity()};
  };
  std::unordered_map<std::size_t, std::size_t> root_to_patch;
  root_to_patch.reserve(std::min(config.max_patches, result.assigned_triangles));
  std::vector<PatchData> patch_data;
  patch_data.reserve(std::min(config.max_patches, result.assigned_triangles));
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    if (!labels[triangle].assigned) continue;
    const std::size_t root = components.find(triangle);
    auto found = root_to_patch.find(root);
    if (found == root_to_patch.end()) {
      if (patch_data.size() >= config.max_patches) {
        result.truncated = true;
        result.error = "texture atlas patch budget exceeded";
        return result;
      }
      const std::size_t patch = patch_data.size();
      found = root_to_patch.emplace(root, patch).first;
      patch_data.push_back(PatchData{labels[triangle].view_index});
    }
    const std::size_t patch = found->second;
    result.source_triangle_patch[triangle] = patch;
    PatchData& data = patch_data[patch];
    if (data.view_index != labels[triangle].view_index) {
      result.error = "texture patch contains multiple source views";
      return result;
    }
    const auto& view = views[data.view_index];
    for (const auto& uv : labels[triangle].normalized_uv) {
      const double x = std::max(0.0, std::min(1.0, static_cast<double>(uv.x))) *
                       (view.width - 1);
      const double y = std::max(0.0, std::min(1.0, static_cast<double>(uv.y))) *
                       (view.height - 1);
      data.min_x = std::min(data.min_x, x); data.max_x = std::max(data.max_x, x);
      data.min_y = std::min(data.min_y, y); data.max_y = std::max(data.max_y, y);
    }
  }

  struct PackedRect { int x{0}, y{0}, width{0}, height{0}; };
  const auto pack = [&](double scale, std::vector<PackedRect>* output) {
    std::vector<PackedRect> rectangles(patch_data.size());
    std::vector<std::size_t> order(patch_data.size());
    std::iota(order.begin(), order.end(), 0);
    for (std::size_t patch = 0; patch < patch_data.size(); ++patch) {
      const auto& data = patch_data[patch];
      const int content_width = std::max(1, static_cast<int>(
          std::ceil((data.max_x - data.min_x) * scale)) + 1);
      const int content_height = std::max(1, static_cast<int>(
          std::ceil((data.max_y - data.min_y) * scale)) + 1);
      rectangles[patch].width = content_width + config.padding_px * 2;
      rectangles[patch].height = content_height + config.padding_px * 2;
      if (rectangles[patch].width > config.atlas_width ||
          rectangles[patch].height > config.atlas_height) return false;
    }
    std::sort(order.begin(), order.end(), [&](std::size_t first, std::size_t second) {
      if (rectangles[first].height != rectangles[second].height)
        return rectangles[first].height > rectangles[second].height;
      if (rectangles[first].width != rectangles[second].width)
        return rectangles[first].width > rectangles[second].width;
      return first < second;
    });
    int x = 0, y = 0, shelf_height = 0;
    for (const std::size_t patch : order) {
      auto& rectangle = rectangles[patch];
      if (x + rectangle.width > config.atlas_width) {
        x = 0; y += shelf_height; shelf_height = 0;
      }
      if (y + rectangle.height > config.atlas_height) return false;
      rectangle.x = x; rectangle.y = y;
      x += rectangle.width;
      shelf_height = std::max(shelf_height, rectangle.height);
    }
    if (output) *output = std::move(rectangles);
    return true;
  };

  double scale = 1.0;
  std::vector<PackedRect> packed;
  if (!pack(scale, &packed)) {
    if (!pack(config.min_uniform_scale, nullptr)) {
      result.truncated = true;
      result.error = "texture atlas capacity exceeded";
      return result;
    }
    double low = config.min_uniform_scale, high = 1.0;
    for (int iteration = 0; iteration < 24; ++iteration) {
      const double middle = (low + high) * 0.5;
      if (pack(middle, nullptr)) low = middle; else high = middle;
    }
    scale = low;
    if (!pack(scale, &packed)) {
      result.truncated = true;
      result.error = "texture atlas packing failed";
      return result;
    }
  }
  result.atlas_width = config.atlas_width;
  result.atlas_height = config.atlas_height;
  result.padding_px = config.padding_px;
  result.uniform_scale = scale;
  result.patches.reserve(patch_data.size());
  for (std::size_t patch = 0; patch < patch_data.size(); ++patch) {
    const auto& data = patch_data[patch];
    const auto& view = views[data.view_index];
    const auto& rectangle = packed[patch];
    result.patches.push_back({patch, data.view_index, view.frame_id,
      data.min_x, data.min_y, data.max_x, data.max_y,
      rectangle.x, rectangle.y, rectangle.width, rectangle.height});
  }

  struct VertexKey {
    std::uint32_t source{0};
    std::size_t patch{0};
    bool operator==(const VertexKey& other) const {
      return source == other.source && patch == other.patch;
    }
  };
  struct VertexHash {
    std::size_t operator()(const VertexKey& key) const {
      return static_cast<std::size_t>(key.source) * 0x9e3779b1U ^ key.patch;
    }
  };
  std::unordered_map<VertexKey, std::uint32_t, VertexHash> remap;
  remap.reserve(std::min(config.max_output_vertices,
                         result.assigned_triangles * 3));
  std::vector<bool> used_source_vertices(mesh.positions_m.size(), false);
  result.mesh.name = mesh.name + " atlas layout";
  for (std::size_t triangle = 0; triangle < triangle_count; ++triangle) {
    if (!labels[triangle].assigned) continue;
    const std::size_t patch = result.source_triangle_patch[triangle];
    const auto& data = patch_data[patch];
    const auto& rectangle = packed[patch];
    const auto& view = views[data.view_index];
    for (std::size_t corner = 0; corner < 3; ++corner) {
      const std::uint32_t source = mesh.triangle_indices[triangle * 3 + corner];
      const double source_x = labels[triangle].normalized_uv[corner].x *
                              (view.width - 1);
      const double source_y = labels[triangle].normalized_uv[corner].y *
                              (view.height - 1);
      const Vec2f atlas_uv{
        static_cast<float>((rectangle.x + config.padding_px +
            (source_x - data.min_x) * scale) / (config.atlas_width - 1)),
        static_cast<float>((rectangle.y + config.padding_px +
            (source_y - data.min_y) * scale) / (config.atlas_height - 1))};
      const VertexKey key{source, patch};
      auto found = remap.find(key);
      std::uint32_t output = 0;
      if (found == remap.end()) {
        if (result.mesh.positions_m.size() >= config.max_output_vertices) {
          result.mesh = {};
          result.truncated = true;
          result.error = "texture atlas output vertex budget exceeded";
          return result;
        }
        output = static_cast<std::uint32_t>(result.mesh.positions_m.size());
        remap.emplace(key, output);
        result.mesh.positions_m.push_back(mesh.positions_m[source]);
        if (!mesh.normals.empty()) result.mesh.normals.push_back(mesh.normals[source]);
        result.mesh.texcoords.push_back(atlas_uv);
        used_source_vertices[source] = true;
      } else {
        output = found->second;
        const Vec2f existing = result.mesh.texcoords[output];
        if (std::abs(existing.x - atlas_uv.x) > 1e-5F ||
            std::abs(existing.y - atlas_uv.y) > 1e-5F) {
          result.mesh = {};
          result.error = "inconsistent UV inside texture patch";
          return result;
        }
      }
      result.mesh.triangle_indices.push_back(output);
    }
  }
  const std::size_t used_vertices = static_cast<std::size_t>(std::count(
      used_source_vertices.begin(), used_source_vertices.end(), true));
  result.duplicated_vertices = result.mesh.positions_m.size() - used_vertices;
  const auto output_validation = validateMesh(
      result.mesh, {config.max_output_vertices, config.max_input_triangles});
  if (!output_validation.valid) {
    result.mesh = {};
    result.error = output_validation.error;
  }
  return result;
}

}  // namespace arkernel
