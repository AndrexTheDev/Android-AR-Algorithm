#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <unordered_map>
#include <vector>

#include "arkernel/camera_model.h"
#include "arkernel/types.h"

namespace arkernel {

struct TsdfDepthImageView {
  int width{0};
  int height{0};
  int depth_row_stride_elements{0};
  const float* depth_z_m{nullptr};
  std::size_t depth_element_count{0};
  const float* confidence{nullptr};
  int confidence_row_stride_elements{0};
  std::size_t confidence_element_count{0};
  const float* uncertainty_m{nullptr};
  int uncertainty_row_stride_elements{0};
  std::size_t uncertainty_element_count{0};
};

struct SparseTsdfConfig {
  double voxel_size_m{0.02};
  double truncation_distance_m{0.06};
  double min_depth_m{0.15};
  double max_depth_m{10.0};
  double min_confidence{0.20};
  double default_uncertainty_m{0.03};
  double uncertainty_floor_m{0.005};
  double max_weight{100.0};
  double max_position_norm_m{1000.0};
  int pixel_stride{2};
  std::size_t max_blocks{4096};
  std::size_t max_depth_pixels_per_update{262144};
  std::size_t max_voxel_visits_per_update{1048576};
  std::size_t max_ray_samples{64};
  std::size_t max_snapshot_voxels{1000000};
};

struct SparseTsdfUpdate {
  std::size_t visited_pixels{0};
  std::size_t integrated_pixels{0};
  std::size_t rejected_pixels{0};
  std::size_t voxel_visits{0};
  std::size_t voxel_updates{0};
  std::size_t allocated_blocks{0};
  std::size_t capacity_rejections{0};
  bool input_valid{false};
  bool truncated{false};
};

struct TsdfVoxelSample {
  std::int32_t x{0};
  std::int32_t y{0};
  std::int32_t z{0};
  Vec3 center_world_m{};
  float signed_distance_m{0.0F};
  float weight{0.0F};
};

// Narrow-band sparse TSDF. It allocates fixed 8x8x8 blocks only near observed
// surfaces; it does not infer unobserved free space or synthesize missing depth.
class SparseTsdfVolume {
 public:
  static constexpr int kBlockSide = 8;
  static constexpr std::size_t kVoxelsPerBlock =
      static_cast<std::size_t>(kBlockSide * kBlockSide * kBlockSide);

  explicit SparseTsdfVolume(SparseTsdfConfig config = {});

  SparseTsdfUpdate integrate(const TsdfDepthImageView& depth,
                             const PinholeRadTanCamera& camera,
                             const Pose& world_from_camera);
  std::optional<TsdfVoxelSample> sample(const Vec3& position_world_m) const;
  std::vector<TsdfVoxelSample> snapshot() const;
  double voxelSizeMeters() const { return config_.voxel_size_m; }
  double truncationDistanceMeters() const { return config_.truncation_distance_m; }
  std::size_t blockCount() const { return blocks_.size(); }
  std::size_t voxelCount() const { return voxel_count_; }
  void reset();

 private:
  struct BlockKey {
    std::int32_t x{0};
    std::int32_t y{0};
    std::int32_t z{0};
    bool operator==(const BlockKey& other) const {
      return x == other.x && y == other.y && z == other.z;
    }
  };
  struct BlockHash {
    std::size_t operator()(const BlockKey& key) const;
  };
  struct Voxel {
    float signed_distance_m{0.0F};
    float weight{0.0F};
  };
  struct Block {
    std::array<Voxel, kVoxelsPerBlock> voxels{};
  };
  struct VoxelAddress {
    std::int32_t x{0};
    std::int32_t y{0};
    std::int32_t z{0};
    BlockKey block{};
    std::size_t local_index{0};
  };

  std::optional<VoxelAddress> addressFor(const Vec3& position_world_m) const;
  Vec3 centerFor(std::int32_t x, std::int32_t y, std::int32_t z) const;

  SparseTsdfConfig config_;
  std::unordered_map<BlockKey, Block, BlockHash> blocks_;
  std::size_t voxel_count_{0};
};

}  // namespace arkernel
