#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <unordered_map>
#include <vector>
#include "arkernel/types.h"

namespace arkernel {

using SemanticObjectId = std::uint64_t;

struct SemanticObjectObservation {
  std::uint32_t class_id{0};
  std::int64_t source_track_id{-1};
  Vec3 position_world_m{};
  double confidence{0.0};
  TimestampNs timestamp_ns{0};
  bool dynamic{false};
};

struct SemanticObject {
  SemanticObjectId id{0};
  std::uint32_t class_id{0};
  std::int64_t source_track_id{-1};
  Vec3 position_world_m{};
  double confidence{0.0};
  std::size_t observation_count{0};
  TimestampNs first_seen_ns{0};
  TimestampNs last_seen_ns{0};
  bool dynamic{false};
  bool confirmed{false};
  double accumulated_weight{0.0};
};

struct SemanticMapConfig {
  std::size_t max_objects{256};
  std::size_t min_confirming_observations{2};
  double min_observation_confidence{0.25};
  double association_distance_m{0.60};
  double max_tracked_jump_m{1.50};
  double max_position_norm_m{1000.0};
  TimestampNs static_lifetime_ns{30000000000};
  TimestampNs dynamic_lifetime_ns{2000000000};
};

struct SemanticMapUpdate {
  std::size_t created{0};
  std::size_t updated{0};
  std::size_t rejected{0};
  std::size_t pruned{0};
};

// Model-independent bounded 3D object fusion. class_id values and labels are
// defined by the host's versioned model manifest; class_id zero means unknown.
class SemanticObjectMap {
 public:
  explicit SemanticObjectMap(SemanticMapConfig config = {});
  SemanticMapUpdate integrate(const std::vector<SemanticObjectObservation>& observations,
                              TimestampNs now_ns);
  std::optional<SemanticObject> find(SemanticObjectId id) const;
  std::vector<SemanticObject> snapshot(bool include_unconfirmed = false) const;
  std::size_t size() const;
  void reset();
 private:
  void makeRoom(SemanticMapUpdate& update);
  SemanticObject* associate(const SemanticObjectObservation& observation);
  SemanticMapConfig config_;
  SemanticObjectId next_id_{1};
  std::unordered_map<SemanticObjectId,SemanticObject> objects_;
};

}  // namespace arkernel
