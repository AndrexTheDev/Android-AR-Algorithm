#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <unordered_map>
#include <vector>

#include "arkernel/loop_closure.h"
#include "arkernel/types.h"

namespace arkernel {

using PoseGraphNodeId = std::uint64_t;

enum class PoseGraphConstraintType : std::uint32_t { kOdometry=0, kLoopClosure=1 };

struct PoseGraphNode {
  PoseGraphNodeId id{0};
  Pose world_from_node{};
  bool fixed{false};
};

struct PoseGraphConstraint {
  PoseGraphNodeId reference_id{0};
  PoseGraphNodeId current_id{0};
  Pose current_from_reference{};
  double translation_sigma_m{0.05};
  double rotation_sigma_degrees{2.0};
  PoseGraphConstraintType type{PoseGraphConstraintType::kOdometry};
};

struct PoseGraphConfig {
  std::size_t max_nodes{256};
  std::size_t max_constraints{1024};
  std::size_t max_iterations{20};
  std::size_t max_pcg_iterations{80};
  double finite_difference_step{1e-6};
  double damping{1e-5};
  double loop_huber_delta{2.5};
  double convergence_step{1e-7};
  double minimum_cost_reduction{1e-9};
};

struct PoseGraphOptimizationResult {
  std::size_t node_count{0};
  std::size_t constraint_count{0};
  std::size_t iterations{0};
  double initial_cost{0.0};
  double final_cost{0.0};
  bool accepted{false};
  bool converged{false};
};

// Bounded dependency-free SE(3) pose graph. Optimization is transactional:
// node poses change only when a finite lower-cost solution is found.
class PoseGraph {
 public:
  explicit PoseGraph(PoseGraphConfig config = {});
  bool addNode(PoseGraphNode node);
  bool addConstraint(PoseGraphConstraint constraint);
  bool addVerifiedLoop(const LoopClosureConstraint& loop,
                       double rotation_sigma_degrees=2.0);
  std::optional<PoseGraphNode> findNode(PoseGraphNodeId id) const;
  std::vector<PoseGraphNode> nodes() const;
  std::size_t constraintCount() const { return constraints_.size(); }
  PoseGraphOptimizationResult optimize();
  void reset();

 private:
  PoseGraphConfig config_;
  std::unordered_map<PoseGraphNodeId,PoseGraphNode> nodes_;
  std::vector<PoseGraphConstraint> constraints_;
};

}  // namespace arkernel
