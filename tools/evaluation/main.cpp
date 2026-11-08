#include <exception>
#include <iomanip>
#include <iostream>
#include <string>
#include "arkernel/trajectory_metrics.h"

int main(int argc,char** argv){
  if(argc<3||argc>4){
    std::cerr<<"Usage: arkernel_trajectory_eval <estimated.arktraj> <euroc_groundtruth.csv> [se3|sim3|none]\n";
    return 2;
  }
  try{
    arkernel::TrajectoryEvaluationConfig config;
    const std::string mode=argc==4?argv[3]:"se3";
    if(mode=="sim3")config.alignment=arkernel::TrajectoryAlignment::kSim3;
    else if(mode=="none")config.alignment=arkernel::TrajectoryAlignment::kNone;
    else if(mode!="se3")throw std::invalid_argument("Alignment must be se3, sim3, or none");
    const auto estimated=arkernel::loadArkTrajectory(argv[1]);
    const auto truth=arkernel::loadEurocGroundTruthCsv(argv[2]);
    const auto m=arkernel::evaluateTrajectory(estimated,truth,config);
    if(!m.valid){std::cerr<<"Not enough associated trajectory samples\n";return 3;}
    std::cout<<std::setprecision(10)<<"{\n"
      <<"  \"associated_samples\": "<<m.associated_samples<<",\n"
      <<"  \"alignment_scale\": "<<m.alignment_scale<<",\n"
      <<"  \"ate_rmse_m\": "<<m.ate_rmse_m<<",\n"
      <<"  \"ate_mean_m\": "<<m.ate_mean_m<<",\n"
      <<"  \"ate_median_m\": "<<m.ate_median_m<<",\n"
      <<"  \"ate_max_m\": "<<m.ate_max_m<<",\n"
      <<"  \"rpe_translation_rmse_m\": "<<m.rpe_translation_rmse_m<<",\n"
      <<"  \"rpe_rotation_rmse_degrees\": "<<m.rpe_rotation_rmse_degrees<<"\n}\n";
    return 0;
  }catch(const std::exception& error){std::cerr<<"Evaluation failed: "<<error.what()<<"\n";return 1;}
}
