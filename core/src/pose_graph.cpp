#include "arkernel/pose_graph.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_set>

namespace arkernel {
namespace {
constexpr double kDegreesToRadians=0.01745329251994329577;
using Vector6=std::array<double,6>;
using Matrix6=std::array<double,36>;

bool finitePose(const Pose& pose){
  const auto& q=pose.rotation;
  const double norm=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
  return std::isfinite(norm)&&norm>1e-9&&std::isfinite(pose.translation.x)&&
    std::isfinite(pose.translation.y)&&std::isfinite(pose.translation.z);
}
Pose inverse(const Pose& pose){const Quaternion rotation=pose.rotation.inverse();
  return {rotation,rotation.rotate(pose.translation*-1.0)};}
Pose compose(const Pose& first,const Pose& second){return {
  (first.rotation*second.rotation).normalized(),
  first.rotation.rotate(second.translation)+first.translation};}
Vec3 rotationLog(Quaternion q){q=q.normalized();
  if(q.w<0)q={-q.w,-q.x,-q.y,-q.z};
  const double sine=std::sqrt(q.x*q.x+q.y*q.y+q.z*q.z);
  if(sine<1e-12)return {2*q.x,2*q.y,2*q.z};
  const double angle=2*std::atan2(sine,std::max(0.0,q.w));
  return {q.x*angle/sine,q.y*angle/sine,q.z*angle/sine};
}
Vector6 residual(const PoseGraphConstraint& edge,const Pose& reference,const Pose& current){
  const Pose predicted=compose(inverse(current),reference);
  const Pose error=compose(inverse(edge.current_from_reference),predicted);
  const Vec3 angle=rotationLog(error.rotation);
  const double translation_weight=1.0/edge.translation_sigma_m;
  const double rotation_weight=1.0/(edge.rotation_sigma_degrees*kDegreesToRadians);
  return {error.translation.x*translation_weight,error.translation.y*translation_weight,
    error.translation.z*translation_weight,angle.x*rotation_weight,
    angle.y*rotation_weight,angle.z*rotation_weight};
}
double squaredNorm(const Vector6& value){double sum=0;for(double item:value)sum+=item*item;return sum;}
double robustCost(const Vector6& value,const PoseGraphConstraint& edge,double delta){
  const double norm=std::sqrt(squaredNorm(value));
  if(edge.type!=PoseGraphConstraintType::kLoopClosure||norm<=delta)return 0.5*norm*norm;
  return delta*(norm-0.5*delta);
}
double robustScale(const Vector6& value,const PoseGraphConstraint& edge,double delta){
  const double norm=std::sqrt(squaredNorm(value));
  if(edge.type!=PoseGraphConstraintType::kLoopClosure||norm<=delta||norm<1e-12)return 1.0;
  return std::sqrt(delta/norm);
}
Pose perturb(Pose pose,std::size_t dimension,double amount){
  if(dimension<3){if(dimension==0)pose.translation.x+=amount;
    else if(dimension==1)pose.translation.y+=amount;else pose.translation.z+=amount;}
  else {Vec3 delta{};if(dimension==3)delta.x=amount;else if(dimension==4)delta.y=amount;
    else delta.z=amount;
    pose.rotation=(Quaternion::fromRotationVector(delta)*pose.rotation).normalized();}
  return pose;
}
double dot(const std::vector<double>& a,const std::vector<double>& b){
  double value=0;for(std::size_t i=0;i<a.size();++i)value+=a[i]*b[i];return value;}
struct LinearFactor {int first{-1},second{-1};Matrix6 j_first{},j_second{};Vector6 residual{};};
void multiplyTranspose(const Matrix6& matrix,const Vector6& value,std::vector<double>& output,int offset){
  if(offset<0)return;
  for(std::size_t column=0;column<6;++column){double sum=0;
    for(std::size_t row=0;row<6;++row)sum+=matrix[row*6+column]*value[row];
    output[static_cast<std::size_t>(offset)+column]+=sum;}
}
Vector6 multiply(const Matrix6& matrix,const std::vector<double>& value,int offset){
  Vector6 output{};if(offset<0)return output;
  for(std::size_t row=0;row<6;++row)for(std::size_t column=0;column<6;++column)
    output[row]+=matrix[row*6+column]*value[static_cast<std::size_t>(offset)+column];
  return output;
}
}  // namespace

PoseGraph::PoseGraph(PoseGraphConfig config):config_(config){
  if(config_.max_nodes<2||config_.max_nodes>1000||config_.max_constraints==0||
     config_.max_constraints>10000||config_.max_iterations==0||
     config_.max_pcg_iterations==0||config_.finite_difference_step<=0||
     config_.damping<=0||config_.loop_huber_delta<=0||config_.convergence_step<=0||
     config_.minimum_cost_reduction<0)throw std::invalid_argument("Invalid pose graph configuration");
}
bool PoseGraph::addNode(PoseGraphNode node){
  if(node.id==0||!finitePose(node.world_from_node)||nodes_.size()>=config_.max_nodes||
     nodes_.count(node.id)!=0)return false;
  node.world_from_node.rotation=node.world_from_node.rotation.normalized();
  nodes_.emplace(node.id,node);return true;
}
bool PoseGraph::addConstraint(PoseGraphConstraint edge){
  if(edge.reference_id==0||edge.current_id==0||edge.reference_id==edge.current_id||
     nodes_.count(edge.reference_id)==0||nodes_.count(edge.current_id)==0||
     constraints_.size()>=config_.max_constraints||!finitePose(edge.current_from_reference)||
     !std::isfinite(edge.translation_sigma_m)||edge.translation_sigma_m<=0||
     !std::isfinite(edge.rotation_sigma_degrees)||edge.rotation_sigma_degrees<=0)return false;
  for(const auto& existing:constraints_)if(existing.reference_id==edge.reference_id&&
     existing.current_id==edge.current_id&&existing.type==edge.type)return false;
  edge.current_from_reference.rotation=edge.current_from_reference.rotation.normalized();
  constraints_.push_back(edge);return true;
}
bool PoseGraph::addVerifiedLoop(const LoopClosureConstraint& loop,double rotation_sigma_degrees){
  if(!loop.valid||!std::isfinite(loop.rmse_m))return false;
  return addConstraint({loop.reference_keyframe_id,loop.current_keyframe_id,
    loop.current_from_reference,std::max(0.005,loop.rmse_m),rotation_sigma_degrees,
    PoseGraphConstraintType::kLoopClosure});
}
std::optional<PoseGraphNode> PoseGraph::findNode(PoseGraphNodeId id) const {
  const auto found=nodes_.find(id);if(found==nodes_.end())return std::nullopt;return found->second;
}
std::vector<PoseGraphNode> PoseGraph::nodes() const {std::vector<PoseGraphNode> output;
  output.reserve(nodes_.size());for(const auto& item:nodes_)output.push_back(item.second);
  std::sort(output.begin(),output.end(),[](const auto& a,const auto& b){return a.id<b.id;});return output;}

PoseGraphOptimizationResult PoseGraph::optimize(){
  PoseGraphOptimizationResult result;result.node_count=nodes_.size();
  result.constraint_count=constraints_.size();if(nodes_.size()<2||constraints_.empty())return result;
  bool has_fixed=false;for(const auto& item:nodes_)has_fixed=has_fixed||item.second.fixed;
  if(!has_fixed)return result;
  auto working=nodes_;
  auto cost=[&](const auto& poses){double total=0;for(const auto& edge:constraints_)
    total+=robustCost(residual(edge,poses.at(edge.reference_id).world_from_node,
      poses.at(edge.current_id).world_from_node),edge,config_.loop_huber_delta);return total;};
  result.initial_cost=cost(working);result.final_cost=result.initial_cost;
  if(!std::isfinite(result.initial_cost))return result;
  if(result.initial_cost<=config_.minimum_cost_reduction){result.accepted=true;result.converged=true;return result;}
  std::vector<PoseGraphNodeId> variable_ids;for(const auto& item:working)
    if(!item.second.fixed)variable_ids.push_back(item.first);
  std::sort(variable_ids.begin(),variable_ids.end());if(variable_ids.empty())return result;
  std::unordered_map<PoseGraphNodeId,int> offsets;for(std::size_t i=0;i<variable_ids.size();++i)
    offsets.emplace(variable_ids[i],static_cast<int>(i*6));
  const std::size_t dimension=variable_ids.size()*6;
  for(std::size_t iteration=0;iteration<config_.max_iterations;++iteration){
    std::vector<LinearFactor> factors;factors.reserve(constraints_.size());
    std::vector<double> gradient(dimension,0),diagonal(dimension,config_.damping);
    for(const auto& edge:constraints_){LinearFactor factor;
      const auto first_offset=offsets.find(edge.reference_id),second_offset=offsets.find(edge.current_id);
      factor.first=first_offset==offsets.end()?-1:first_offset->second;
      factor.second=second_offset==offsets.end()?-1:second_offset->second;
      const Pose& first=working.at(edge.reference_id).world_from_node;
      const Pose& second=working.at(edge.current_id).world_from_node;
      const Vector6 base=residual(edge,first,second);const double scale=robustScale(base,edge,config_.loop_huber_delta);
      for(std::size_t row=0;row<6;++row)factor.residual[row]=base[row]*scale;
      for(std::size_t column=0;column<6;++column){
        if(factor.first>=0){const auto changed=residual(edge,perturb(first,column,config_.finite_difference_step),second);
          for(std::size_t row=0;row<6;++row)factor.j_first[row*6+column]=
            (changed[row]-base[row])/config_.finite_difference_step*scale;}
        if(factor.second>=0){const auto changed=residual(edge,first,perturb(second,column,config_.finite_difference_step));
          for(std::size_t row=0;row<6;++row)factor.j_second[row*6+column]=
            (changed[row]-base[row])/config_.finite_difference_step*scale;}
      }
      multiplyTranspose(factor.j_first,factor.residual,gradient,factor.first);
      multiplyTranspose(factor.j_second,factor.residual,gradient,factor.second);
      for(std::size_t column=0;column<6;++column){
        if(factor.first>=0)for(std::size_t row=0;row<6;++row)
          diagonal[static_cast<std::size_t>(factor.first)+column]+=
            factor.j_first[row*6+column]*factor.j_first[row*6+column];
        if(factor.second>=0)for(std::size_t row=0;row<6;++row)
          diagonal[static_cast<std::size_t>(factor.second)+column]+=
            factor.j_second[row*6+column]*factor.j_second[row*6+column];
      }
      factors.push_back(factor);
    }
    auto apply=[&](const std::vector<double>& input){std::vector<double> output(dimension);
      for(std::size_t i=0;i<dimension;++i)output[i]=config_.damping*input[i];
      for(const auto& factor:factors){Vector6 combined=multiply(factor.j_first,input,factor.first);
        const Vector6 second=multiply(factor.j_second,input,factor.second);
        for(std::size_t row=0;row<6;++row)combined[row]+=second[row];
        multiplyTranspose(factor.j_first,combined,output,factor.first);
        multiplyTranspose(factor.j_second,combined,output,factor.second);}
      return output;};
    std::vector<double> step(dimension,0),r(dimension),z(dimension),direction;
    for(std::size_t i=0;i<dimension;++i){r[i]=-gradient[i];z[i]=r[i]/diagonal[i];}
    direction=z;double rz=dot(r,z);
    for(std::size_t pcg=0;pcg<config_.max_pcg_iterations&&rz>1e-24;++pcg){
      const auto product=apply(direction);const double denominator=dot(direction,product);
      if(!std::isfinite(denominator)||denominator<=1e-24)break;
      const double alpha=rz/denominator;for(std::size_t i=0;i<dimension;++i){
        step[i]+=alpha*direction[i];r[i]-=alpha*product[i];}
      for(std::size_t i=0;i<dimension;++i)z[i]=r[i]/diagonal[i];
      const double next=dot(r,z);if(next<1e-20){rz=next;break;}
      const double beta=next/rz;for(std::size_t i=0;i<dimension;++i)direction[i]=z[i]+beta*direction[i];rz=next;
    }
    double step_norm=std::sqrt(dot(step,step));if(!std::isfinite(step_norm))break;
    bool improved=false;double alpha=1.0;
    for(int attempt=0;attempt<8&&!improved;++attempt,alpha*=0.5){auto candidate=working;
      for(std::size_t node=0;node<variable_ids.size();++node)for(std::size_t axis=0;axis<6;++axis)
        candidate.at(variable_ids[node]).world_from_node=perturb(
          candidate.at(variable_ids[node]).world_from_node,axis,step[node*6+axis]*alpha);
      const double candidate_cost=cost(candidate);
      if(std::isfinite(candidate_cost)&&candidate_cost<result.final_cost){working=std::move(candidate);
        result.final_cost=candidate_cost;improved=true;step_norm*=alpha;}
    }
    result.iterations=iteration+1;if(!improved)break;
    if(step_norm<config_.convergence_step){result.converged=true;break;}
  }
  if(std::isfinite(result.final_cost)&&result.initial_cost-result.final_cost>=
     config_.minimum_cost_reduction){nodes_=std::move(working);result.accepted=true;}
  return result;
}
void PoseGraph::reset(){nodes_.clear();constraints_.clear();}

}  // namespace arkernel
