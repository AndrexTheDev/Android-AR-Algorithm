#include <exception>
#include <iomanip>
#include <iostream>
#include "arkernel/replay.h"

int main(int argc,char** argv){
  if(argc!=2){std::cerr<<"Usage: arkernel_replay <manifest.arkr>\n";return 2;}
  try{
    const auto dataset=arkernel::ReplayDataset::loadManifest(argv[1]);
    arkernel::PinholeRadTanCamera camera(dataset.intrinsics,dataset.distortion);
    arkernel::ReplayRunner runner(camera);
    const auto m=runner.run(dataset);
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
