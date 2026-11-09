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

}  // namespace arkernel
