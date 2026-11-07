#pragma once

#include <cstddef>
#include <string>
#include <vector>
#include "arkernel/types.h"

namespace arkernel {

struct TrajectorySample {
  TimestampNs timestamp_ns{0};
  Pose world_from_body{};
};

enum class TrajectoryAlignment { kNone, kSe3, kSim3 };

struct TrajectoryEvaluationConfig {
  TrajectoryAlignment alignment{TrajectoryAlignment::kSe3};
  TimestampNs max_association_delta_ns{20000000};
  std::size_t rpe_delta_samples{1};
};

struct TrajectoryMetrics {
  std::size_t associated_samples{0};
  double alignment_scale{1.0};
  Pose ground_from_estimated_world{};
  double ate_rmse_m{0.0};
  double ate_mean_m{0.0};
  double ate_median_m{0.0};
  double ate_max_m{0.0};
  double rpe_translation_rmse_m{0.0};
  double rpe_rotation_rmse_degrees{0.0};
  bool valid{false};
};

// Loads EuRoC state_groundtruth_estimate0/data.csv fields:
// timestamp, p_RS_R_x/y/z, q_RS_w/x/y/z, ...
std::vector<TrajectorySample> loadEurocGroundTruthCsv(const std::string& path);

// Loads/writes the dependency-free exchange format:
// ARK_TRAJECTORY_V1 then timestamp_ns px py pz qw qx qy qz.
std::vector<TrajectorySample> loadArkTrajectory(const std::string& path);
void writeArkTrajectory(const std::string& path,
                        const std::vector<TrajectorySample>& trajectory);

TrajectoryMetrics evaluateTrajectory(
    const std::vector<TrajectorySample>& estimated,
    const std::vector<TrajectorySample>& ground_truth,
    TrajectoryEvaluationConfig config = {});

}  // namespace arkernel
