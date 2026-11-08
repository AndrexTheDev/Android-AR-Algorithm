#include "arkernel/fixed_lag_smoother.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace arkernel {
namespace {
double dot(const Vec3& a,const Vec3& b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 normalized(const Vec3& value){const double n=value.norm();return n>1e-12?value*(1.0/n):Vec3{};}
Vec3 rotationResidual(const Quaternion& target,const Quaternion& actual){
  Quaternion error=(target.inverse()*actual).normalized();
  if(error.w<0)error={-error.w,-error.x,-error.y,-error.z};
  const double vector_norm=std::sqrt(error.x*error.x+error.y*error.y+error.z*error.z);
  if(vector_norm<1e-12)return {2*error.x,2*error.y,2*error.z};
  const double angle=2*std::atan2(vector_norm,std::max(0.0,error.w));
  return {error.x*angle/vector_norm,error.y*angle/vector_norm,error.z*angle/vector_norm};
}
double huberCost(double norm,double delta){return norm<=delta?0.5*norm*norm:delta*(norm-0.5*delta);}
double huberWeight(double norm,double delta){return norm<=delta||norm<1e-12?1.0:delta/norm;}
double covarianceConfidence(const std::array<double,9>& diagonal,int offset,double floor,bool enabled){
  if(!enabled)return 1.0;
  const double variance=(diagonal[offset]+diagonal[offset+1]+diagonal[offset+2])/3.0;
  if(!std::isfinite(variance)||variance<0)return 0.0;
  return variance<=floor?1.0:floor/variance;
}
}  // namespace

FixedLagSmoother::FixedLagSmoother(FixedLagConfig config):config_(config){
  if(config_.max_keyframes<2||config_.optimization_iterations<1||
     config_.imu_position_weight<0||config_.imu_velocity_weight<0||
     config_.imu_rotation_weight<0||config_.visual_rotation_weight<0||
     config_.visual_direction_weight<0||config_.huber_delta_position_m<=0||
     config_.huber_delta_rotation_rad<=0||config_.update_gain<=0||config_.update_gain>1||
     config_.rotation_variance_floor<=0||config_.velocity_variance_floor<=0||
     config_.position_variance_floor<=0||
     !std::isfinite(config_.gravity_world_mps2.x)||!std::isfinite(config_.gravity_world_mps2.y)||
     !std::isfinite(config_.gravity_world_mps2.z))
    throw std::invalid_argument("Invalid fixed-lag smoother configuration");
}

bool FixedLagSmoother::addKeyframe(const VioState& state,
    const std::optional<PreintegratedImu>& imu,
    const std::optional<RelativePoseEstimate>& visual){
  if(state.timestamp_ns<=0||(!keyframes_.empty()&&state.timestamp_ns<=keyframes_.back().timestamp_ns))return false;
  if(keyframes_.empty()){
    if(imu)return false;
  }else{
    if(!imu||imu->duration_s<=0||imu->sample_count<2)return false;
    if(visual&&!visual->valid)return false;
  }
  keyframes_.push_back({state.timestamp_ns,state.world_from_body,state.velocity_world_mps,
                        state.accelerometer_bias_mps2,state.gyroscope_bias_rps});
  if(keyframes_.size()>1)edges_.push_back({*imu,visual});
  while(keyframes_.size()>config_.max_keyframes){
    keyframes_.pop_front();edges_.pop_front();++marginalized_count_;
  }
  return true;
}

double FixedLagSmoother::calculateCost(std::size_t* downweighted) const{
  double cost=0;if(downweighted)*downweighted=0;
  const Vec3 gravity=config_.gravity_world_mps2;
  for(std::size_t i=0;i<edges_.size();++i){
    const auto& a=keyframes_[i];const auto& b=keyframes_[i+1];const auto& edge=edges_[i];
    const auto imu=edge.imu.corrected({a.accelerometer_bias_mps2,a.gyroscope_bias_rps});
    const double dt=imu.duration_s;
    const Quaternion predicted_rotation=(a.world_from_body.rotation*imu.delta_rotation).normalized();
    const Vec3 predicted_velocity=a.velocity_world_mps+
        a.world_from_body.rotation.rotate(imu.delta_velocity)+gravity*dt;
    const Vec3 predicted_position=a.world_from_body.translation+a.velocity_world_mps*dt+
        a.world_from_body.rotation.rotate(imu.delta_position)+gravity*(0.5*dt*dt);
    const double position_norm=(b.world_from_body.translation-predicted_position).norm();
    const double velocity_norm=(b.velocity_world_mps-predicted_velocity).norm();
    const double rotation_norm=rotationResidual(predicted_rotation,b.world_from_body.rotation).norm();
    const double rotation_confidence=covarianceConfidence(
        imu.covariance_diagonal,0,config_.rotation_variance_floor,config_.use_imu_covariance);
    const double velocity_confidence=covarianceConfidence(
        imu.covariance_diagonal,3,config_.velocity_variance_floor,config_.use_imu_covariance);
    const double position_confidence=covarianceConfidence(
        imu.covariance_diagonal,6,config_.position_variance_floor,config_.use_imu_covariance);
    cost+=config_.imu_position_weight*position_confidence*
          huberCost(position_norm,config_.huber_delta_position_m);
    cost+=config_.imu_velocity_weight*velocity_confidence*
          huberCost(velocity_norm,config_.huber_delta_position_m);
    cost+=config_.imu_rotation_weight*rotation_confidence*
          huberCost(rotation_norm,config_.huber_delta_rotation_rad);
    if(downweighted){
      *downweighted+=position_norm>config_.huber_delta_position_m;
      *downweighted+=velocity_norm>config_.huber_delta_position_m;
      *downweighted+=rotation_norm>config_.huber_delta_rotation_rad;
    }
    if(edge.visual){
      const auto& visual=*edge.visual;
      const Quaternion visual_target=(a.world_from_body.rotation*
          visual.rotation_current_from_previous.inverse()).normalized();
      const double visual_rotation_norm=rotationResidual(visual_target,b.world_from_body.rotation).norm();
      const Vec3 direction_previous=visual.rotation_current_from_previous.inverse().rotate(
          visual.translation_direction_current*-1.0);
      const Vec3 direction_world=normalized(a.world_from_body.rotation.rotate(direction_previous));
      const Vec3 displacement=b.world_from_body.translation-a.world_from_body.translation;
      const Vec3 perpendicular=displacement-direction_world*dot(displacement,direction_world);
      cost+=config_.visual_rotation_weight*huberCost(visual_rotation_norm,config_.huber_delta_rotation_rad);
      cost+=config_.visual_direction_weight*huberCost(perpendicular.norm(),config_.huber_delta_position_m);
      if(downweighted){
        *downweighted+=visual_rotation_norm>config_.huber_delta_rotation_rad;
        *downweighted+=perpendicular.norm()>config_.huber_delta_position_m;
      }
    }
  }
  return cost;
}

SmootherDiagnostics FixedLagSmoother::optimize(){
  SmootherDiagnostics diagnostics;diagnostics.keyframe_count=keyframes_.size();
  diagnostics.imu_factor_count=edges_.size();diagnostics.marginalized_keyframe_count=marginalized_count_;
  for(const auto& edge:edges_){
    diagnostics.visual_factor_count+=edge.visual.has_value();
    const bool covariance_downweighted=
        covarianceConfidence(edge.imu.covariance_diagonal,0,config_.rotation_variance_floor,
                             config_.use_imu_covariance)<0.999999||
        covarianceConfidence(edge.imu.covariance_diagonal,3,config_.velocity_variance_floor,
                             config_.use_imu_covariance)<0.999999||
        covarianceConfidence(edge.imu.covariance_diagonal,6,config_.position_variance_floor,
                             config_.use_imu_covariance)<0.999999;
    diagnostics.covariance_downweighted_factor_count+=covariance_downweighted;
  }
  diagnostics.initial_cost=calculateCost();
  const Vec3 gravity=config_.gravity_world_mps2;
  for(int iteration=0;iteration<config_.optimization_iterations;++iteration){
    // The first node is a fixed prior. Forward sweeps are deterministic and
    // converge for this adjacent-factor bootstrap without a dense Hessian.
    for(std::size_t i=0;i<edges_.size();++i){
      const auto& a=keyframes_[i];auto& b=keyframes_[i+1];const auto& edge=edges_[i];
      const auto imu=edge.imu.corrected({a.accelerometer_bias_mps2,a.gyroscope_bias_rps});
      const double dt=imu.duration_s;
      const Quaternion imu_rotation=(a.world_from_body.rotation*imu.delta_rotation).normalized();
      const Vec3 imu_velocity=a.velocity_world_mps+
          a.world_from_body.rotation.rotate(imu.delta_velocity)+gravity*dt;
      const Vec3 imu_position=a.world_from_body.translation+a.velocity_world_mps*dt+
          a.world_from_body.rotation.rotate(imu.delta_position)+gravity*(0.5*dt*dt);
      const Vec3 position_error=b.world_from_body.translation-imu_position;
      const Vec3 velocity_error=b.velocity_world_mps-imu_velocity;
      const double rotation_confidence=covarianceConfidence(
          imu.covariance_diagonal,0,config_.rotation_variance_floor,config_.use_imu_covariance);
      const double velocity_confidence=covarianceConfidence(
          imu.covariance_diagonal,3,config_.velocity_variance_floor,config_.use_imu_covariance);
      const double position_confidence=covarianceConfidence(
          imu.covariance_diagonal,6,config_.position_variance_floor,config_.use_imu_covariance);
      const double position_gain=config_.update_gain*config_.imu_position_weight*
          position_confidence*huberWeight(position_error.norm(),config_.huber_delta_position_m);
      const double velocity_gain=config_.update_gain*config_.imu_velocity_weight*
          velocity_confidence*huberWeight(velocity_error.norm(),config_.huber_delta_position_m);
      b.world_from_body.translation+=position_error*(-std::min(1.0,position_gain));
      b.velocity_world_mps+=velocity_error*(-std::min(1.0,velocity_gain));
      const double imu_angle=rotationResidual(imu_rotation,b.world_from_body.rotation).norm();
      const double imu_gain=std::min(1.0,config_.update_gain*config_.imu_rotation_weight*
          rotation_confidence*huberWeight(imu_angle,config_.huber_delta_rotation_rad));
      b.world_from_body.rotation=Quaternion::slerp(b.world_from_body.rotation,imu_rotation,imu_gain);
      if(edge.visual){
        const auto& visual=*edge.visual;
        const Quaternion visual_rotation=(a.world_from_body.rotation*
            visual.rotation_current_from_previous.inverse()).normalized();
        const double visual_angle=rotationResidual(visual_rotation,b.world_from_body.rotation).norm();
        const double rotation_gain=std::min(1.0,config_.update_gain*config_.visual_rotation_weight*
            huberWeight(visual_angle,config_.huber_delta_rotation_rad));
        b.world_from_body.rotation=Quaternion::slerp(b.world_from_body.rotation,visual_rotation,rotation_gain);
        const Vec3 direction_previous=visual.rotation_current_from_previous.inverse().rotate(
            visual.translation_direction_current*-1.0);
        const Vec3 direction_world=normalized(a.world_from_body.rotation.rotate(direction_previous));
        const Vec3 displacement=b.world_from_body.translation-a.world_from_body.translation;
        const Vec3 perpendicular=displacement-direction_world*dot(displacement,direction_world);
        const double direction_gain=std::min(1.0,config_.update_gain*config_.visual_direction_weight*
            huberWeight(perpendicular.norm(),config_.huber_delta_position_m));
        b.world_from_body.translation+=perpendicular*(-direction_gain);
      }
    }
  }
  diagnostics.final_cost=calculateCost(&diagnostics.downweighted_residual_count);return diagnostics;
}

void FixedLagSmoother::reset(){keyframes_.clear();edges_.clear();marginalized_count_=0;}

}  // namespace arkernel
