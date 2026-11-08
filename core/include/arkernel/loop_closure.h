#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "arkernel/image_descriptor.h"
#include "arkernel/relocalization.h"
#include "arkernel/types.h"

namespace arkernel {

using LoopKeyframeId = std::uint64_t;

struct LoopLandmark {
  std::uint32_t feature_id{0};
  Vec3 position_keyframe_m{};
};

// Landmarks are expressed in this keyframe's local camera coordinate frame.
struct LoopKeyframe {
  LoopKeyframeId id{0};
  TimestampNs timestamp_ns{0};
  Pose world_from_keyframe{};
  std::vector<LoopLandmark> landmarks;
  std::vector<BinaryFeatureDescriptor> descriptors;
};

enum class LoopClosureRejectionReason : std::uint32_t {
  kNone=0,
  kInvalidInput=1,
  kTemporallyNear=2,
  kInsufficientMatches=3,
  kGeometryRejected=4,
  kPoseDisagreement=5,
};

struct LoopClosureConfig {
  std::size_t max_landmarks_per_keyframe{4096};
  TimestampNs min_temporal_separation_ns{5000000000LL};
  DescriptorMatcherConfig matcher{};
  RelocalizationConfig geometry{};
  double max_translation_disagreement_m{2.0};
  double max_rotation_disagreement_degrees{35.0};
};

struct LoopClosureConstraint {
  LoopKeyframeId reference_keyframe_id{0};
  LoopKeyframeId current_keyframe_id{0};
  Pose current_from_reference{};
  std::size_t descriptor_match_count{0};
  std::size_t inlier_count{0};
  double inlier_ratio{0.0};
  double rmse_m{0.0};
  double translation_disagreement_m{0.0};
  double rotation_disagreement_degrees{0.0};
  bool valid{false};
  LoopClosureRejectionReason rejection_reason{LoopClosureRejectionReason::kInvalidInput};
};

// Verifies an appearance candidate. Only valid results are safe to forward as
// robust pose-graph constraints; appearance retrieval alone is never enough.
LoopClosureConstraint verifyLoopClosure(
    const LoopKeyframe& reference,
    const LoopKeyframe& current,
    LoopClosureConfig config = {});

}  // namespace arkernel
