#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <unordered_map>
#include <vector>
#include "arkernel/camera_model.h"
#include "arkernel/types.h"

namespace arkernel {

struct VisualFrontendResult;

struct LandmarkObservation {
  std::uint32_t feature_id{0};
  TimestampNs timestamp_ns{0};
  Vec3 unit_ray_camera{};
  Pose world_from_camera{};
};

struct SparseLandmark {
  std::uint32_t feature_id{0};
  Vec3 position_world_m{};
  std::size_t observation_count{0};
  double mean_ray_residual_m{0.0};
  TimestampNs last_seen_ns{0};
};

std::vector<LandmarkObservation> makeLandmarkObservations(
    const VisualFrontendResult& frontend_result,
    const PinholeRadTanCamera& camera,
    const Pose& world_from_camera);

struct SparseMapConfig {
  std::size_t max_landmarks{2000};
  double min_triangulation_baseline_m{0.03};
  double min_triangulation_parallax_degrees{1.0};
  double max_ray_residual_m{0.05};
  TimestampNs pending_observation_lifetime_ns{2000000000};
  TimestampNs landmark_lifetime_ns{30000000000};
};

struct MapUpdate {
  std::size_t created{0};
  std::size_t updated{0};
  std::size_t rejected{0};
  std::size_t pruned{0};
};

class SparseLandmarkMap {
 public:
  explicit SparseLandmarkMap(SparseMapConfig config = {});
  MapUpdate integrate(const std::vector<LandmarkObservation>& observations,
                      TimestampNs now_ns);
  std::optional<SparseLandmark> find(std::uint32_t feature_id) const;
  std::vector<SparseLandmark> snapshot() const;
  std::size_t size() const { return landmarks_.size(); }
  void reset();

 private:
  struct Pending { LandmarkObservation observation; };
  std::optional<std::pair<Vec3,double>> triangulate(
      const LandmarkObservation& first,
      const LandmarkObservation& second) const;
  SparseMapConfig config_;
  std::unordered_map<std::uint32_t,Pending> pending_;
  std::unordered_map<std::uint32_t,SparseLandmark> landmarks_;
};

struct PlaneEstimate {
  Vec3 normal_world{};
  double distance_from_origin_m{0.0};
  std::size_t inlier_count{0};
  double mean_absolute_error_m{0.0};
  std::vector<std::uint32_t> landmark_ids;
  bool valid{false};
};

struct PlaneDetectorConfig {
  int ransac_iterations{200};
  double inlier_threshold_m{0.03};
  std::size_t min_inliers{12};
  std::uint32_t random_seed{0x504C4E31u};
};

class SparsePlaneDetector {
 public:
  explicit SparsePlaneDetector(PlaneDetectorConfig config = {});
  PlaneEstimate detect(const std::vector<SparseLandmark>& landmarks) const;
 private:
  PlaneDetectorConfig config_;
};

using AnchorId = std::uint64_t;
struct WorldAnchor {
  AnchorId id{0};
  Pose world_from_anchor{};
  TimestampNs created_ns{0};
};

class AnchorStore {
 public:
  AnchorId create(const Pose& world_from_anchor,TimestampNs timestamp_ns);
  bool remove(AnchorId id);
  std::optional<WorldAnchor> find(AnchorId id) const;
  std::vector<WorldAnchor> snapshot() const;
  void reset();
 private:
  AnchorId next_id_{1};
  std::unordered_map<AnchorId,WorldAnchor> anchors_;
};

}  // namespace arkernel
