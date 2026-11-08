#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "arkernel/types.h"

namespace arkernel {

using GlobalLandmarkId=std::uint64_t;

struct GlobalLandmark {
  GlobalLandmarkId id{0};
  Vec3 position_world_m{};
  std::size_t observation_count{0};
  double mean_residual_m{0.0};
  TimestampNs last_seen_ns{0};
};
struct LandmarkMergeAssociation {
  GlobalLandmarkId first_id{0};
  GlobalLandmarkId second_id{0};
};
struct LandmarkAlias {
  GlobalLandmarkId source_id{0};
  GlobalLandmarkId canonical_id{0};
};
struct LandmarkMergeConfig {
  std::size_t max_landmarks{10000};
  std::size_t max_associations{20000};
  double max_cluster_diameter_m{0.05};
};
struct LandmarkMergeResult {
  std::vector<GlobalLandmark> landmarks;
  std::vector<LandmarkAlias> aliases;
  std::size_t accepted_associations{0};
  std::size_t rejected_associations{0};
  bool valid{false};
};

// Deterministically merges globally transformed duplicates. Associations must
// come from a valid loop; complete-link distance prevents chaining collapse.
LandmarkMergeResult mergeGlobalLandmarks(
    const std::vector<GlobalLandmark>& landmarks,
    const std::vector<LandmarkMergeAssociation>& verified_associations,
    LandmarkMergeConfig config={});

}  // namespace arkernel
