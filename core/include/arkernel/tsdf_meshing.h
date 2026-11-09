#pragma once

#include <cstddef>

#include "arkernel/reconstruction_asset.h"
#include "arkernel/sparse_tsdf.h"

namespace arkernel {

struct TsdfMeshingConfig {
  float iso_value_m{0.0F};
  float min_voxel_weight{0.10F};
  double min_triangle_area_m2{1e-10};
  std::size_t max_input_voxels{1000000};
  std::size_t max_cells{1000000};
  std::size_t max_vertices{1000000};
  std::size_t max_triangles{2000000};
};

struct TsdfMeshingResult {
  ReconstructionMesh mesh;
  std::size_t input_voxels{0};
  std::size_t examined_cells{0};
  std::size_t complete_cells{0};
  std::size_t surface_cells{0};
  std::size_t skipped_incomplete_cells{0};
  std::size_t skipped_low_weight_cells{0};
  std::size_t degenerate_triangles{0};
  bool input_valid{false};
  bool truncated{false};
};

// Deterministic bounded Marching Tetrahedra extraction over fully observed
// TSDF cells. Missing corners remain holes; no completion or watertightness is
// inferred. Output has metric positions and area-weighted normals but no UVs.
// Callers must preserve prior SparseTsdfUpdate::truncated evidence.
TsdfMeshingResult extractTsdfMesh(const SparseTsdfVolume& volume,
                                  TsdfMeshingConfig config = {});

}  // namespace arkernel
