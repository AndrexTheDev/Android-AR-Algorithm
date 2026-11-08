#include "arkernel/bundle_adjustment.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_set>

namespace arkernel { namespace {
constexpr double kDeg=0.01745329251994329577;
bool finite(const Vec3& v){return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z);}
bool validPose(const Pose& p){const auto&q=p.rotation;const double n=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
 return finite(p.translation)&&std::isfinite(n)&&n>1e-9;}
Pose inverse(const Pose&p){const auto r=p.rotation.inverse();return {r,r.rotate(p.translation*-1)};}
Pose compose(const Pose&a,const Pose&b){return {(a.rotation*b.rotation).normalized(),a.rotation.rotate(b.translation)+a.translation};}
Vec3 logRotation(Quaternion q){q=q.normalized();if(q.w<0)q={-q.w,-q.x,-q.y,-q.z};
 const double s=std::sqrt(q.x*q.x+q.y*q.y+q.z*q.z);if(s<1e-12)return {2*q.x,2*q.y,2*q.z};
 const double a=2*std::atan2(s,std::max(0.0,q.w));return {q.x*a/s,q.y*a/s,q.z*a/s};}
Pose perturb(Pose p,std::size_t d,double amount){if(d<3){if(d==0)p.translation.x+=amount;else if(d==1)p.translation.y+=amount;else p.translation.z+=amount;}
 else {Vec3 v{};if(d==3)v.x=amount;else if(d==4)v.y=amount;else v.z=amount;p.rotation=(Quaternion::fromRotationVector(v)*p.rotation).normalized();}return p;}
Vec3 perturb(Vec3 p,std::size_t d,double amount){if(d==0)p.x+=amount;else if(d==1)p.y+=amount;else p.z+=amount;return p;}
std::optional<std::array<double,2>> reprojection(const BundleKeyframe& k,const Vec3& point,const BundleObservation&o){
 const Pose camera_from_world=inverse(k.world_from_camera);const Vec3 pc=camera_from_world.rotation.rotate(point)+camera_from_world.translation;
 const auto pixel=PinholeRadTanCamera(k.intrinsics,k.distortion).project(pc);if(!pixel)return std::nullopt;
 return std::array<double,2>{(pixel->x-o.pixel.x)/o.sigma_px,(pixel->y-o.pixel.y)/o.sigma_px};}
std::array<double,6> priorResidual(const Pose&prior,const Pose&current,double ts,double rs){const Pose e=compose(inverse(prior),current);const Vec3 a=logRotation(e.rotation);
 return {e.translation.x/ts,e.translation.y/ts,e.translation.z/ts,a.x/rs,a.y/rs,a.z/rs};}
double dot(const std::vector<double>&a,const std::vector<double>&b){double s=0;for(std::size_t i=0;i<a.size();++i)s+=a[i]*b[i];return s;}
struct Factor {int a{-1},b{-1};std::size_t da{0},db{0},rows{0};std::vector<double> ja,jb,r;};
void jt(const std::vector<double>&j,std::size_t rows,std::size_t cols,const std::vector<double>&v,std::vector<double>&out,int offset){if(offset<0)return;
 for(std::size_t c=0;c<cols;++c){double s=0;for(std::size_t row=0;row<rows;++row)s+=j[row*cols+c]*v[row];out[static_cast<std::size_t>(offset)+c]+=s;}}
std::vector<double> jx(const std::vector<double>&j,std::size_t rows,std::size_t cols,const std::vector<double>&x,int offset){std::vector<double> out(rows);if(offset<0)return out;
 for(std::size_t row=0;row<rows;++row)for(std::size_t c=0;c<cols;++c)
   out[row]+=j[row*cols+c]*x[static_cast<std::size_t>(offset)+c];
 return out;}
} // namespace

BundleAdjustment::BundleAdjustment(BundleAdjustmentConfig c):config_(c){if(c.max_keyframes<2||c.max_keyframes>500||c.max_landmarks==0||c.max_landmarks>20000||
 c.max_observations==0||c.max_observations>100000||c.max_iterations==0||c.max_pcg_iterations==0||c.pose_translation_prior_sigma_m<=0||
 c.pose_rotation_prior_sigma_degrees<=0||c.huber_delta<=0||c.finite_difference_step<=0||c.damping<=0||c.convergence_step<=0||c.minimum_cost_reduction<0)
 throw std::invalid_argument("Invalid bundle adjustment configuration");}
bool BundleAdjustment::addKeyframe(BundleKeyframe k){if(k.id==0||!validPose(k.world_from_camera)||!k.intrinsics.valid()||keyframes_.size()>=config_.max_keyframes||keyframes_.count(k.id))return false;
 k.world_from_camera.rotation=k.world_from_camera.rotation.normalized();pose_priors_[k.id]=k.world_from_camera;keyframes_.emplace(k.id,k);return true;}
bool BundleAdjustment::addLandmark(BundleLandmark l){if(l.id==0||!finite(l.position_world_m)||landmarks_.size()>=config_.max_landmarks||landmarks_.count(l.id))return false;landmarks_.emplace(l.id,l);return true;}
bool BundleAdjustment::addObservation(BundleObservation o){if(o.keyframe_id==0||o.landmark_id==0||keyframes_.count(o.keyframe_id)==0||landmarks_.count(o.landmark_id)==0||
 observations_.size()>=config_.max_observations||!std::isfinite(o.pixel.x)||!std::isfinite(o.pixel.y)||!std::isfinite(o.sigma_px)||o.sigma_px<=0)return false;
 for(const auto&x:observations_)if(x.keyframe_id==o.keyframe_id&&x.landmark_id==o.landmark_id)return false;
 observations_.push_back(o);return true;}
std::optional<BundleKeyframe> BundleAdjustment::findKeyframe(BundleKeyframeId id)const{auto i=keyframes_.find(id);if(i==keyframes_.end())return std::nullopt;return i->second;}
std::optional<BundleLandmark> BundleAdjustment::findLandmark(BundleLandmarkId id)const{auto i=landmarks_.find(id);if(i==landmarks_.end())return std::nullopt;return i->second;}

BundleAdjustmentResult BundleAdjustment::optimize(){BundleAdjustmentResult result;result.keyframe_count=keyframes_.size();result.landmark_count=landmarks_.size();result.observation_count=observations_.size();
 if(keyframes_.size()<2||landmarks_.empty()||observations_.empty())return result;
 bool fixed=false;for(const auto&x:keyframes_)fixed|=x.second.fixed;if(!fixed)return result;
 std::unordered_map<BundleLandmarkId,std::size_t> counts;for(const auto&o:observations_)++counts[o.landmark_id];for(const auto&x:landmarks_)if(counts[x.first]<2)return result;
 auto keys=keyframes_;auto points=landmarks_;auto evaluate=[&](const auto&ks,const auto&ps,double*rmse){double cost=0,squared=0;std::size_t n=0;
  for(const auto&o:observations_){const auto r=reprojection(ks.at(o.keyframe_id),ps.at(o.landmark_id).position_world_m,o);if(!r)return std::numeric_limits<double>::infinity();
   const double q=(*r)[0]*(*r)[0]+(*r)[1]*(*r)[1],norm=std::sqrt(q);squared+=q;n+=2;cost+=norm<=config_.huber_delta?0.5*q:config_.huber_delta*(norm-0.5*config_.huber_delta);}
  for(const auto&x:ks)if(!x.second.fixed){const auto r=priorResidual(pose_priors_.at(x.first),x.second.world_from_camera,config_.pose_translation_prior_sigma_m,config_.pose_rotation_prior_sigma_degrees*kDeg);for(double v:r)cost+=0.5*v*v;}
  if(rmse)*rmse=std::sqrt(squared/static_cast<double>(n));
  return cost;};
 result.initial_cost=evaluate(keys,points,&result.initial_rmse_px);result.final_cost=result.initial_cost;result.final_rmse_px=result.initial_rmse_px;if(!std::isfinite(result.initial_cost))return result;
 if(result.initial_cost<=config_.minimum_cost_reduction){result.accepted=true;result.converged=true;return result;}
 std::vector<BundleKeyframeId> key_ids;for(const auto&x:keys)if(!x.second.fixed)key_ids.push_back(x.first);std::sort(key_ids.begin(),key_ids.end());
 std::vector<BundleLandmarkId> point_ids;for(const auto&x:points)point_ids.push_back(x.first);std::sort(point_ids.begin(),point_ids.end());
 std::unordered_map<BundleKeyframeId,int> ko;std::unordered_map<BundleLandmarkId,int> po;std::size_t dim=0;for(auto id:key_ids){ko[id]=static_cast<int>(dim);dim+=6;}for(auto id:point_ids){po[id]=static_cast<int>(dim);dim+=3;}
 for(std::size_t iteration=0;iteration<config_.max_iterations;++iteration){std::vector<Factor> factors;std::vector<double> gradient(dim),diagonal(dim,config_.damping);
  bool linear_valid=true;for(const auto&o:observations_){Factor f;auto ki=ko.find(o.keyframe_id);f.a=ki==ko.end()?-1:ki->second;f.b=po.at(o.landmark_id);f.da=6;f.db=3;f.rows=2;f.ja.resize(12);f.jb.resize(6);
   const auto base=reprojection(keys.at(o.keyframe_id),points.at(o.landmark_id).position_world_m,o);if(!base){linear_valid=false;break;}const double norm=std::hypot((*base)[0],(*base)[1]);const double scale=norm>config_.huber_delta?std::sqrt(config_.huber_delta/norm):1.0;f.r={(*base)[0]*scale,(*base)[1]*scale};
   if(f.a>=0)for(std::size_t c=0;c<6;++c){auto changed=keys.at(o.keyframe_id);changed.world_from_camera=perturb(changed.world_from_camera,c,config_.finite_difference_step);const auto r=reprojection(changed,points.at(o.landmark_id).position_world_m,o);if(!r){linear_valid=false;break;}for(std::size_t row=0;row<2;++row)f.ja[row*6+c]=((*r)[row]-(*base)[row])/config_.finite_difference_step*scale;}
   for(std::size_t c=0;c<3&&linear_valid;++c){const auto r=reprojection(keys.at(o.keyframe_id),perturb(points.at(o.landmark_id).position_world_m,c,config_.finite_difference_step),o);if(!r){linear_valid=false;break;}for(std::size_t row=0;row<2;++row)f.jb[row*3+c]=((*r)[row]-(*base)[row])/config_.finite_difference_step*scale;}if(!linear_valid)break;factors.push_back(std::move(f));}
  if(!linear_valid)break;
  for(auto id:key_ids){Factor f;f.a=ko.at(id);f.da=6;f.rows=6;f.ja.resize(36);const auto base=priorResidual(pose_priors_.at(id),keys.at(id).world_from_camera,config_.pose_translation_prior_sigma_m,config_.pose_rotation_prior_sigma_degrees*kDeg);f.r.assign(base.begin(),base.end());
   for(std::size_t c=0;c<6;++c){const auto r=priorResidual(pose_priors_.at(id),perturb(keys.at(id).world_from_camera,c,config_.finite_difference_step),config_.pose_translation_prior_sigma_m,config_.pose_rotation_prior_sigma_degrees*kDeg);for(std::size_t row=0;row<6;++row)f.ja[row*6+c]=(r[row]-base[row])/config_.finite_difference_step;}factors.push_back(std::move(f));}
  for(const auto&f:factors){jt(f.ja,f.rows,f.da,f.r,gradient,f.a);jt(f.jb,f.rows,f.db,f.r,gradient,f.b);for(std::size_t c=0;c<f.da&&f.a>=0;++c)for(std::size_t r=0;r<f.rows;++r)diagonal[static_cast<std::size_t>(f.a)+c]+=f.ja[r*f.da+c]*f.ja[r*f.da+c];for(std::size_t c=0;c<f.db&&f.b>=0;++c)for(std::size_t r=0;r<f.rows;++r)diagonal[static_cast<std::size_t>(f.b)+c]+=f.jb[r*f.db+c]*f.jb[r*f.db+c];}
  auto apply=[&](const std::vector<double>&x){std::vector<double>out(dim);for(std::size_t i=0;i<dim;++i)out[i]=config_.damping*x[i];for(const auto&f:factors){auto v=jx(f.ja,f.rows,f.da,x,f.a),w=jx(f.jb,f.rows,f.db,x,f.b);for(std::size_t i=0;i<f.rows;++i)v[i]+=w[i];jt(f.ja,f.rows,f.da,v,out,f.a);jt(f.jb,f.rows,f.db,v,out,f.b);}return out;};
  std::vector<double>step(dim),r(dim),z(dim),direction;for(std::size_t i=0;i<dim;++i){r[i]=-gradient[i];z[i]=r[i]/diagonal[i];}direction=z;double rz=dot(r,z);
  for(std::size_t k=0;k<config_.max_pcg_iterations&&rz>1e-24;++k){const auto ap=apply(direction);const double den=dot(direction,ap);if(!std::isfinite(den)||den<=1e-24)break;const double a=rz/den;for(std::size_t i=0;i<dim;++i){step[i]+=a*direction[i];r[i]-=a*ap[i];z[i]=r[i]/diagonal[i];}const double next=dot(r,z);if(next<1e-20){rz=next;break;}const double beta=next/rz;for(std::size_t i=0;i<dim;++i)direction[i]=z[i]+beta*direction[i];rz=next;}
  double step_norm=std::sqrt(dot(step,step));if(!std::isfinite(step_norm))break;bool improved=false;double alpha=1;for(int attempt=0;attempt<8&&!improved;++attempt,alpha*=0.5){auto ck=keys;auto cp=points;for(auto id:key_ids)for(std::size_t c=0;c<6;++c)ck.at(id).world_from_camera=perturb(ck.at(id).world_from_camera,c,step[static_cast<std::size_t>(ko.at(id))+c]*alpha);for(auto id:point_ids)for(std::size_t c=0;c<3;++c)cp.at(id).position_world_m=perturb(cp.at(id).position_world_m,c,step[static_cast<std::size_t>(po.at(id))+c]*alpha);double rmse=0;const double value=evaluate(ck,cp,&rmse);if(std::isfinite(value)&&value<result.final_cost){keys=std::move(ck);points=std::move(cp);result.final_cost=value;result.final_rmse_px=rmse;step_norm*=alpha;improved=true;}}
  result.iterations=iteration+1;if(!improved)break;if(step_norm<config_.convergence_step){result.converged=true;break;}}
 if(std::isfinite(result.final_cost)&&result.initial_cost-result.final_cost>=config_.minimum_cost_reduction){keyframes_=std::move(keys);landmarks_=std::move(points);result.accepted=true;}return result;}
void BundleAdjustment::reset(){keyframes_.clear();landmarks_.clear();observations_.clear();pose_priors_.clear();}
} // namespace arkernel
