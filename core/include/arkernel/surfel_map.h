#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <unordered_map>
#include <vector>
#include "arkernel/types.h"

namespace arkernel {

using SurfelId = std::uint64_t;

struct SurfelObservation {
  Vec3 position_world_m{};
  Vec3 normal_world{};
  double confidence{0.0};
  double radius_m{0.0};
  TimestampNs timestamp_ns{0};
  bool normal_valid{false};
};

struct Surfel {
  SurfelId id{0};
  Vec3 position_world_m{};
  Vec3 normal_world{};
  double confidence{0.0};
  double radius_m{0.0};
  std::size_t observation_count{0};
  TimestampNs first_seen_ns{0};
  TimestampNs last_seen_ns{0};
  bool normal_valid{false};
  bool confirmed{false};
  double accumulated_weight{0.0};
  double accumulated_normal_weight{0.0};
};

struct SurfelMapConfig {
  std::size_t max_surfels{50000};
  std::size_t max_observations_per_update{100000};
  std::size_t min_confirming_observations{2};
  double voxel_size_m{0.05};
  double min_observation_confidence{0.20};
  double max_position_norm_m{1000.0};
  double min_radius_m{0.002};
  double max_radius_m{0.50};
  TimestampNs lifetime_ns{30000000000};
};

struct SurfelMapUpdate {
  std::size_t created{0};
  std::size_t updated{0};
  std::size_t rejected{0};
  std::size_t pruned{0};
};

struct SurfelHit {
  SurfelId surfel_id{0};
  Vec3 position_world_m{};
  Vec3 normal_world{};
  double distance_m{0.0};
  double confidence{0.0};
  bool normal_valid{false};
};

// Bounded model-independent surface fusion. Adapters may derive observations
// from stereo, monocular depth, structured light, ToF or imported point clouds.
class SurfelMap {
 public:
  explicit SurfelMap(SurfelMapConfig config = {});
  SurfelMapUpdate integrate(const std::vector<SurfelObservation>& observations,
                            TimestampNs now_ns);
  std::vector<Surfel> snapshot(bool include_unconfirmed = false) const;
  std::optional<SurfelHit> raycast(const Vec3& ray_origin_world,
                                   const Vec3& ray_direction_world,
                                   double min_distance_m = 0.05,
                                   double max_distance_m = 20.0) const;
  std::size_t size() const;
  void reset();
 private:
  struct VoxelKey {
    std::int32_t x{0},y{0},z{0};
    bool operator==(const VoxelKey& other) const {
      return x==other.x&&y==other.y&&z==other.z;
    }
  };
  struct VoxelHash {
    std::size_t operator()(const VoxelKey& key) const;
  };
  VoxelKey voxelFor(const Vec3& position) const;
  bool makeRoom(SurfelMapUpdate& update);
  SurfelMapConfig config_;
  SurfelId next_id_{1};
  std::unordered_map<VoxelKey,Surfel,VoxelHash> surfels_;
};

}  // namespace arkernel
