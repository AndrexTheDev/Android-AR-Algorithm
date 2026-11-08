#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <unordered_map>
#include <vector>

#include "arkernel/camera_model.h"
#include "arkernel/types.h"

namespace arkernel {

using BundleKeyframeId=std::uint64_t;
using BundleLandmarkId=std::uint64_t;

struct BundleKeyframe {
  BundleKeyframeId id{0};
  Pose world_from_camera{};
  CameraIntrinsics intrinsics{};
  RadTanDistortion distortion{};
  bool fixed{false};
};
struct BundleLandmark {BundleLandmarkId id{0};Vec3 position_world_m{};};
struct BundleObservation {
  BundleKeyframeId keyframe_id{0};
  BundleLandmarkId landmark_id{0};
  Vec2 pixel{};
  double sigma_px{1.0};
};
struct BundleAdjustmentConfig {
  std::size_t max_keyframes{128};
  std::size_t max_landmarks{5000};
  std::size_t max_observations{20000};
  std::size_t max_iterations{15};
  std::size_t max_pcg_iterations{80};
  double pose_translation_prior_sigma_m{0.10};
  double pose_rotation_prior_sigma_degrees{5.0};
  double huber_delta{2.5};
  double finite_difference_step{1e-6};
  double damping{1e-5};
  double convergence_step{1e-7};
  double minimum_cost_reduction{1e-9};
};
struct BundleAdjustmentResult {
  std::size_t keyframe_count{0},landmark_count{0},observation_count{0},iterations{0};
  double initial_cost{0},final_cost{0},initial_rmse_px{0},final_rmse_px{0};
  bool accepted{false},converged{false};
};

// Joint bounded keyframe/landmark refinement. Pose-graph poses are retained as
// metric priors, and public state changes only after finite cost reduction.
class BundleAdjustment {
 public:
  explicit BundleAdjustment(BundleAdjustmentConfig config={});
  bool addKeyframe(BundleKeyframe keyframe);
  bool addLandmark(BundleLandmark landmark);
  bool addObservation(BundleObservation observation);
  std::optional<BundleKeyframe> findKeyframe(BundleKeyframeId id) const;
  std::optional<BundleLandmark> findLandmark(BundleLandmarkId id) const;
  BundleAdjustmentResult optimize();
  void reset();
 private:
  BundleAdjustmentConfig config_;
  std::unordered_map<BundleKeyframeId,BundleKeyframe> keyframes_;
  std::unordered_map<BundleLandmarkId,BundleLandmark> landmarks_;
  std::vector<BundleObservation> observations_;
  std::unordered_map<BundleKeyframeId,Pose> pose_priors_;
};

}  // namespace arkernel
