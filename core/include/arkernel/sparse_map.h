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
  std::size_t min_confirming_observations{3};
  std::size_t max_consecutive_rejections{3};
  TimestampNs pending_observation_lifetime_ns{2000000000};
  TimestampNs landmark_lifetime_ns{30000000000};
};

struct MapUpdate {
  std::size_t created{0};
  std::size_t updated{0};
  std::size_t rejected{0};
  std::size_t pruned{0};
  std::size_t dynamic_pruned{0};
};

class SparseLandmarkMap {
 public:
  explicit SparseLandmarkMap(SparseMapConfig config = {});
  MapUpdate integrate(const std::vector<LandmarkObservation>& observations,
                      TimestampNs now_ns);
  std::optional<SparseLandmark> find(std::uint32_t feature_id) const;
  std::vector<SparseLandmark> snapshot() const;
  std::size_t size() const { return landmarks_.size(); }
  bool restore(const std::vector<SparseLandmark>& landmarks);
  void reset();

 private:
  struct Pending {
    LandmarkObservation observation;
    std::optional<Vec3> candidate_position_world_m;
    std::size_t confirming_observations{1};
    double accumulated_residual_m{0.0};
  };
  std::optional<std::pair<Vec3,double>> triangulate(
      const LandmarkObservation& first,
      const LandmarkObservation& second) const;
  SparseMapConfig config_;
  std::unordered_map<std::uint32_t,Pending> pending_;
  std::unordered_map<std::uint32_t,SparseLandmark> landmarks_;
  std::unordered_map<std::uint32_t,std::size_t> consecutive_rejections_;
};

struct PlaneHit {
  Vec3 position_world{};
  Vec3 normal_world{};
  double distance_m{0.0};
  Vec2 plane_uv{};
};

struct PlaneEstimate {
  Vec3 normal_world{};
  double distance_from_origin_m{0.0};
  Vec3 center_world{};
  Vec3 basis_u_world{};
  Vec3 basis_v_world{};
  std::vector<Vec2> boundary_uv;
  double observed_area_m2{0.0};
  std::size_t inlier_count{0};
  double mean_absolute_error_m{0.0};
  std::vector<std::uint32_t> landmark_ids;
  bool valid{false};

  std::optional<PlaneHit> raycast(const Vec3& ray_origin_world,
                                  const Vec3& ray_direction_world,
                                  double min_distance_m = 0.05,
                                  double max_distance_m = 20.0) const;
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
  std::vector<PlaneEstimate> detectMultiple(const std::vector<SparseLandmark>& landmarks,
                                            std::size_t max_planes) const;
 private:
  PlaneDetectorConfig config_;
};

using PlaneId = std::uint64_t;
enum class PlaneSemantic : std::uint32_t { kUnknown, kFloor, kCeiling, kWall, kSloped };

struct PlaneClassificationConfig {
  double horizontal_tolerance_degrees{15.0};
  double vertical_tolerance_degrees{15.0};
  double min_observer_separation_m{0.20};
};

PlaneSemantic classifyPlane(const PlaneEstimate& plane,
                            const Vec3& world_up,
                            const std::optional<Vec3>& observer_world,
                            PlaneClassificationConfig config = {});

struct TrackedPlane {
  PlaneId id{0};
  PlaneEstimate estimate{};
  PlaneSemantic semantic{PlaneSemantic::kUnknown};
};

struct PlaneTrackerConfig {
  std::size_t max_planes{6};
  double max_normal_angle_degrees{12.0};
  double max_offset_change_m{0.20};
  double max_center_shift_m{2.0};
  PlaneClassificationConfig classification{};
};

struct RoomLayoutEstimate {
  PlaneId floor_plane_id{0};
  PlaneId ceiling_plane_id{0};
  double floor_elevation_m{0.0};
  double ceiling_elevation_m{0.0};
  double room_height_m{0.0};
  double observed_floor_area_m2{0.0};
  double observed_ceiling_area_m2{0.0};
  double observed_wall_area_m2{0.0};
  std::size_t wall_count{0};
  bool has_floor{false};
  bool has_ceiling{false};
  bool height_valid{false};
};

struct RoomLayoutConfig {
  double min_room_height_m{1.5};
  double max_room_height_m{10.0};
};

RoomLayoutEstimate estimateRoomLayout(const std::vector<TrackedPlane>& planes,
                                      const Vec3& world_up = {0,0,1},
                                      RoomLayoutConfig config = {});

class SparsePlaneTracker {
 public:
  SparsePlaneTracker(PlaneDetectorConfig detector_config = {},
                     PlaneTrackerConfig tracker_config = {});
  const std::vector<TrackedPlane>& update(
      const std::vector<SparseLandmark>& landmarks,
      const Vec3& world_up = {0,0,1},
      const std::optional<Vec3>& observer_world = std::nullopt);
  const std::vector<TrackedPlane>& planes() const { return planes_; }
  void reset();
 private:
  SparsePlaneDetector detector_;
  PlaneTrackerConfig config_;
  PlaneId next_id_{1};
  std::vector<TrackedPlane> planes_;
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
  bool restore(const std::vector<WorldAnchor>& anchors);
  void reset();
 private:
  AnchorId next_id_{1};
  std::unordered_map<AnchorId,WorldAnchor> anchors_;
};

}  // namespace arkernel
