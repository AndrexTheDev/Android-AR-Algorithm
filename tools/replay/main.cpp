#include <exception>
#include <iomanip>
#include <iostream>
#include <string>
#include "arkernel/replay.h"

int main(int argc,char** argv){
  if(argc!=2&&argc!=4){
    std::cerr<<"Usage: arkernel_replay <manifest.arkr> [--trajectory output.arktraj]\n";
    return 2;
  }
  if(argc==4&&std::string(argv[2])!="--trajectory"){
    std::cerr<<"Expected --trajectory before output path\n";return 2;
  }
  try{
    const auto dataset=arkernel::ReplayDataset::loadManifest(argv[1]);
    arkernel::PinholeRadTanCamera camera(dataset.intrinsics,dataset.distortion);
    arkernel::VioConfig vio_config;
    vio_config.body_from_camera_rotation=dataset.body_from_camera_rotation;
    vio_config.body_from_camera_translation_m=dataset.body_from_camera_translation_m;
    arkernel::ReplayRunner runner(camera,{},vio_config);
    const auto m=runner.run(dataset);
    if(argc==4)arkernel::writeArkTrajectory(argv[3],m.trajectory);
    std::cout<<"{\n"
      <<"  \"imu_samples\": "<<m.imu_samples<<",\n"
      <<"  \"frames\": "<<m.frames<<",\n"
      <<"  \"tracking_frames\": "<<m.tracking_frames<<",\n"
      <<"  \"limited_frames\": "<<m.limited_frames<<",\n"
      <<"  \"reinitializations\": "<<m.reinitializations<<",\n"
      <<"  \"general_3d_frames\": "<<m.general_3d_frames<<",\n"
      <<"  \"planar_frames\": "<<m.planar_frames<<",\n"
      <<"  \"visual_fusions\": "<<m.visual_fusions<<",\n"
      <<"  \"peak_active_features\": "<<m.peak_active_features<<",\n"
      <<"  \"final_vio_status\": "<<static_cast<int>(m.final_vio_status)<<",\n"
      <<"  \"deterministic_hash\": \""<<std::hex<<std::setw(16)<<std::setfill('0')
      <<m.deterministic_hash<<"\"\n}\n";
    return m.frames?0:3;
  }catch(const std::exception& error){std::cerr<<"Replay failed: "<<error.what()<<"\n";return 1;}
}
