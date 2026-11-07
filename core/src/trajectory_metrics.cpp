#include "arkernel/trajectory_metrics.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <numeric>
#include <sstream>
#include <stdexcept>

namespace arkernel {
namespace {
double dot(const Vec3& a,const Vec3& b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 meanPosition(const std::vector<Vec3>& values){
  Vec3 mean{};for(const auto& value:values)mean+=value;return mean*(1.0/values.size());
}
bool finitePose(const Pose& p){return std::isfinite(p.translation.x)&&std::isfinite(p.translation.y)&&
  std::isfinite(p.translation.z)&&std::isfinite(p.rotation.w)&&std::isfinite(p.rotation.x)&&
  std::isfinite(p.rotation.y)&&std::isfinite(p.rotation.z);}

double quaternionAngle(const Quaternion& input){
  const auto q=input.normalized();const double w=std::abs(q.w);
  return 2.0*std::acos(std::max(-1.0,std::min(1.0,w)));
}

Quaternion hornRotation(const std::vector<Vec3>& source,const std::vector<Vec3>& target,
                        const Vec3& source_mean,const Vec3& target_mean){
  std::array<double,9> m{};
  for(std::size_t i=0;i<source.size();++i){
    const Vec3 x=source[i]-source_mean,y=target[i]-target_mean;
    m[0]+=x.x*y.x;m[1]+=x.x*y.y;m[2]+=x.x*y.z;
    m[3]+=x.y*y.x;m[4]+=x.y*y.y;m[5]+=x.y*y.z;
    m[6]+=x.z*y.x;m[7]+=x.z*y.y;m[8]+=x.z*y.z;
  }
  const double trace=m[0]+m[4]+m[8];
  std::array<double,16> n{
    trace,m[5]-m[7],m[6]-m[2],m[1]-m[3],
    m[5]-m[7],m[0]-m[4]-m[8],m[1]+m[3],m[2]+m[6],
    m[6]-m[2],m[1]+m[3],-m[0]+m[4]-m[8],m[5]+m[7],
    m[1]-m[3],m[2]+m[6],m[5]+m[7],-m[0]-m[4]+m[8]};
  std::array<double,16> vectors{};for(int i=0;i<4;++i)vectors[i*4+i]=1;
  for(int sweep=0;sweep<80;++sweep){
    int p=0,q=1;double largest=0;
    for(int r=0;r<4;++r)for(int c=r+1;c<4;++c)if(std::abs(n[r*4+c])>largest){largest=std::abs(n[r*4+c]);p=r;q=c;}
    if(largest<1e-14)break;
    const double angle=0.5*std::atan2(2*n[p*4+q],n[q*4+q]-n[p*4+p]);
    const double cs=std::cos(angle),sn=std::sin(angle);
    for(int k=0;k<4;++k){const double a=n[p*4+k],b=n[q*4+k];n[p*4+k]=cs*a-sn*b;n[q*4+k]=sn*a+cs*b;}
    for(int k=0;k<4;++k){
      const double a=n[k*4+p],b=n[k*4+q];n[k*4+p]=cs*a-sn*b;n[k*4+q]=sn*a+cs*b;
      const double va=vectors[k*4+p],vb=vectors[k*4+q];vectors[k*4+p]=cs*va-sn*vb;vectors[k*4+q]=sn*va+cs*vb;
    }
  }
  int largest=0;for(int i=1;i<4;++i)if(n[i*4+i]>n[largest*4+largest])largest=i;
  return Quaternion{vectors[largest],vectors[4+largest],vectors[8+largest],vectors[12+largest]}.normalized();
}

std::vector<std::string> splitCsv(const std::string& line){
  std::vector<std::string> fields;std::stringstream stream(line);std::string field;
  while(std::getline(stream,field,','))fields.push_back(field);
  return fields;
}
void validateTrajectory(const std::vector<TrajectorySample>& values){
  TimestampNs previous=0;
  for(const auto& value:values){
    if(value.timestamp_ns<=previous||!finitePose(value.world_from_body))
      throw std::runtime_error("Trajectory contains invalid or non-monotonic samples");
    previous=value.timestamp_ns;
  }
}
}  // namespace

std::vector<TrajectorySample> loadEurocGroundTruthCsv(const std::string& path){
  std::ifstream input(path);if(!input)throw std::runtime_error("Cannot open EuRoC ground truth");
  std::vector<TrajectorySample> result;std::string line;
  while(std::getline(input,line)){
    if(line.empty()||line[0]=='#')continue;
    const auto f=splitCsv(line);
    if(f.size()<8)throw std::runtime_error("Malformed EuRoC ground truth row");
    TrajectorySample sample;sample.timestamp_ns=std::stoll(f[0]);
    sample.world_from_body.translation={std::stod(f[1]),std::stod(f[2]),std::stod(f[3])};
    sample.world_from_body.rotation={std::stod(f[4]),std::stod(f[5]),std::stod(f[6]),std::stod(f[7])};
    result.push_back(sample);
  }
  validateTrajectory(result);return result;
}

std::vector<TrajectorySample> loadArkTrajectory(const std::string& path){
  std::ifstream input(path);if(!input)throw std::runtime_error("Cannot open ARK trajectory");
  std::string line;if(!std::getline(input,line)||line!="ARK_TRAJECTORY_V1")
    throw std::runtime_error("Unsupported trajectory version");
  std::vector<TrajectorySample> result;
  while(std::getline(input,line)){
    if(line.empty()||line[0]=='#')continue;
    std::istringstream row(line);TrajectorySample sample;
    row>>sample.timestamp_ns>>sample.world_from_body.translation.x>>sample.world_from_body.translation.y>>
      sample.world_from_body.translation.z>>sample.world_from_body.rotation.w>>sample.world_from_body.rotation.x>>
      sample.world_from_body.rotation.y>>sample.world_from_body.rotation.z;
    if(!row)throw std::runtime_error("Malformed ARK trajectory row");
    result.push_back(sample);
  }
  validateTrajectory(result);return result;
}

void writeArkTrajectory(const std::string& path,const std::vector<TrajectorySample>& trajectory){
  validateTrajectory(trajectory);std::ofstream output(path);if(!output)throw std::runtime_error("Cannot write trajectory");
  output<<"ARK_TRAJECTORY_V1\n"<<std::setprecision(17);
  for(const auto& s:trajectory)output<<s.timestamp_ns<<' '<<s.world_from_body.translation.x<<' '<<
    s.world_from_body.translation.y<<' '<<s.world_from_body.translation.z<<' '<<s.world_from_body.rotation.w<<' '<<
    s.world_from_body.rotation.x<<' '<<s.world_from_body.rotation.y<<' '<<s.world_from_body.rotation.z<<'\n';
}

TrajectoryMetrics evaluateTrajectory(const std::vector<TrajectorySample>& estimated,
    const std::vector<TrajectorySample>& ground_truth,TrajectoryEvaluationConfig config){
  TrajectoryMetrics result;
  if(config.max_association_delta_ns<0||config.rpe_delta_samples<1||estimated.empty()||ground_truth.empty())return result;
  validateTrajectory(estimated);validateTrajectory(ground_truth);
  std::vector<Pose> estimates,truth;std::size_t j=0;
  for(const auto& e:estimated){
    while(j+1<ground_truth.size()&&ground_truth[j+1].timestamp_ns<=e.timestamp_ns)++j;
    std::size_t best=j;
    if(j+1<ground_truth.size()&&std::llabs(ground_truth[j+1].timestamp_ns-e.timestamp_ns)<
       std::llabs(ground_truth[j].timestamp_ns-e.timestamp_ns))best=j+1;
    if(std::llabs(ground_truth[best].timestamp_ns-e.timestamp_ns)<=config.max_association_delta_ns){
      estimates.push_back(e.world_from_body);truth.push_back(ground_truth[best].world_from_body);}
  }
  result.associated_samples=estimates.size();if(estimates.size()<3)return result;
  std::vector<Vec3> source,target;for(std::size_t i=0;i<estimates.size();++i){source.push_back(estimates[i].translation);target.push_back(truth[i].translation);}
  const Vec3 source_mean=meanPosition(source),target_mean=meanPosition(target);
  Quaternion alignment_rotation{};double scale=1.0;
  if(config.alignment!=TrajectoryAlignment::kNone){
    alignment_rotation=hornRotation(source,target,source_mean,target_mean);
    if(config.alignment==TrajectoryAlignment::kSim3){double numerator=0,denominator=0;
      for(std::size_t i=0;i<source.size();++i){const Vec3 x=source[i]-source_mean,y=target[i]-target_mean;
        numerator+=dot(y,alignment_rotation.rotate(x));denominator+=dot(x,x);}
      if(denominator<1e-12)return result;
      scale=numerator/denominator;
      if(!std::isfinite(scale)||scale<=0)return result;
    }
    result.ground_from_estimated_world.rotation=alignment_rotation;
    result.ground_from_estimated_world.translation=target_mean-alignment_rotation.rotate(source_mean)*scale;
  }
  result.alignment_scale=scale;
  std::vector<Pose> aligned(estimates.size());std::vector<double> errors;
  for(std::size_t i=0;i<estimates.size();++i){
    aligned[i].translation=alignment_rotation.rotate(estimates[i].translation)*scale+
                           result.ground_from_estimated_world.translation;
    aligned[i].rotation=(alignment_rotation*estimates[i].rotation).normalized();
    const double error=(aligned[i].translation-truth[i].translation).norm();errors.push_back(error);
    result.ate_rmse_m+=error*error;result.ate_mean_m+=error;result.ate_max_m=std::max(result.ate_max_m,error);
  }
  result.ate_rmse_m=std::sqrt(result.ate_rmse_m/errors.size());result.ate_mean_m/=errors.size();
  auto sorted=errors;std::sort(sorted.begin(),sorted.end());result.ate_median_m=sorted[sorted.size()/2];
  const std::size_t delta=config.rpe_delta_samples;if(delta>=aligned.size())return result;
  std::size_t rpe_count=0;
  for(std::size_t i=0;i+delta<aligned.size();++i){const std::size_t k=i+delta;
    const Quaternion estimated_rotation_delta=aligned[i].rotation.inverse()*aligned[k].rotation;
    const Quaternion truth_rotation_delta=truth[i].rotation.inverse()*truth[k].rotation;
    const Vec3 estimated_delta=aligned[i].rotation.inverse().rotate(
        aligned[k].translation-aligned[i].translation);
    const Vec3 truth_delta=truth[i].rotation.inverse().rotate(
        truth[k].translation-truth[i].translation);
    const double translation_error=(estimated_delta-truth_delta).norm();
    const double rotation_error=quaternionAngle(truth_rotation_delta.inverse()*estimated_rotation_delta)*180.0/3.14159265358979323846;
    result.rpe_translation_rmse_m+=translation_error*translation_error;
    result.rpe_rotation_rmse_degrees+=rotation_error*rotation_error;++rpe_count;
  }
  result.rpe_translation_rmse_m=std::sqrt(result.rpe_translation_rmse_m/rpe_count);
  result.rpe_rotation_rmse_degrees=std::sqrt(result.rpe_rotation_rmse_degrees/rpe_count);
  result.valid=true;return result;
}

}  // namespace arkernel
