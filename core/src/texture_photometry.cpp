#include "arkernel/texture_photometry.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <map>
#include <queue>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace arkernel {
namespace {

struct EdgeKey {
  std::uint32_t a{0},b{0};
  bool operator<(const EdgeKey& other) const {
    return a<other.a||(a==other.a&&b<other.b);
  }
};
struct EdgeUse {std::size_t triangle{0};std::size_t corner_a{0};std::size_t corner_b{0};};
struct Equation {
  std::size_t a{0},b{0};
  std::array<double,3> delta{}; // log(gain_b)-log(gain_a)
};

bool validPacked(const TextureSourceImageView& image,int channels) {
  if(!image.data||image.width<=0||image.height<=0||image.width>32768||image.height>32768||
     image.row_stride_bytes<image.width*channels)return false;
  const std::size_t row=static_cast<std::size_t>(image.height-1);
  const std::size_t stride=static_cast<std::size_t>(image.row_stride_bytes);
  const std::size_t tail=static_cast<std::size_t>(image.width)*channels;
  return row<=(std::numeric_limits<std::size_t>::max()-tail)/stride&&
         row*stride+tail<=image.data_size;
}

bool validImage(const TextureSourceImageView& image) {
  if(image.frame_id==0)return false;
  if(image.format==TexturePixelFormat::kGray8)return validPacked(image,1);
  if(image.format==TexturePixelFormat::kRgb8)return validPacked(image,3);
  if(image.format==TexturePixelFormat::kRgba8)return validPacked(image,4);
  if(!image.data||image.width<=0||image.height<=0||image.width>32768||image.height>32768||
     (image.width&1)||(image.height&1)||image.row_stride_bytes!=image.width)return false;
  const std::size_t pixels=static_cast<std::size_t>(image.width)*image.height;
  return image.data_size==pixels+pixels/2;
}

std::array<double,4> sample(const TextureSourceImageView& image,double u,double v) {
  const int x=std::max(0,std::min(image.width-1,static_cast<int>(std::llround(
      u*(image.width-1)))));
  const int y=std::max(0,std::min(image.height-1,static_cast<int>(std::llround(
      v*(image.height-1)))));
  if(image.format==TexturePixelFormat::kI420) {
    const std::size_t pixels=static_cast<std::size_t>(image.width)*image.height;
    const std::size_t chroma=static_cast<std::size_t>(y/2)*(image.width/2)+x/2;
    const int yy=image.data[static_cast<std::size_t>(y)*image.width+x];
    const int uu=image.data[pixels+chroma],vv=image.data[pixels+pixels/4+chroma];
    const int c=std::max(0,yy-16),d=uu-128,e=vv-128;
    const auto clamp=[](int value){return static_cast<double>(std::max(0,std::min(255,value)));};
    return {clamp((298*c+409*e+128)>>8),clamp((298*c-100*d-208*e+128)>>8),
            clamp((298*c+516*d+128)>>8),255.0};
  }
  const int channels=image.format==TexturePixelFormat::kGray8?1:
                     image.format==TexturePixelFormat::kRgb8?3:4;
  const std::size_t offset=static_cast<std::size_t>(y)*image.row_stride_bytes+
                           static_cast<std::size_t>(x)*channels;
  if(channels==1)return {static_cast<double>(image.data[offset]),
      static_cast<double>(image.data[offset]),static_cast<double>(image.data[offset]),255.0};
  return {static_cast<double>(image.data[offset]),static_cast<double>(image.data[offset+1]),
          static_cast<double>(image.data[offset+2]),
          channels==4?static_cast<double>(image.data[offset+3]):255.0};
}

double median(std::vector<double>& values) {
  std::sort(values.begin(),values.end());
  const std::size_t middle=values.size()/2;
  return values.size()%2?values[middle]:(values[middle-1]+values[middle])*0.5;
}

}  // namespace

TexturePhotometryResult estimateTexturePhotometry(
    const ReconstructionMesh& mesh,const std::vector<TextureViewReference>& views,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<TextureSourceImageView>& images,TexturePhotometryConfig config) {
  if(config.samples_per_seam_edge<3||config.samples_per_seam_edge>129||
     config.max_views==0||config.max_views>65536||
     config.max_triangles==0||config.max_triangles>10000000||
     config.max_unique_edges==0||config.max_unique_edges>10000000||
     config.max_sample_tests==0||config.max_sample_tests>1000000000||
     config.max_equations==0||config.max_equations>10000000||
     config.solver_iterations==0||config.solver_iterations>100||
     !std::isfinite(config.min_usable_channel)||!std::isfinite(config.max_usable_channel)||
     config.min_usable_channel<0||config.max_usable_channel>255||
     config.min_usable_channel>=config.max_usable_channel||
     !std::isfinite(config.min_gain)||!std::isfinite(config.max_gain)||
     config.min_gain<=0||config.min_gain>1||config.max_gain<1||config.max_gain>8) {
    throw std::invalid_argument("Invalid texture photometry configuration");
  }
  TexturePhotometryResult result;
  result.corrections.resize(views.size());
  for(std::size_t i=0;i<views.size();++i) {
    result.corrections[i].view_index=i;result.corrections[i].frame_id=views[i].frame_id;
  }
  if(views.empty()||views.size()>config.max_views||images.size()>config.max_views||
     labels.empty()||labels.size()>config.max_triangles||
     mesh.triangle_indices.size()%3!=0||labels.size()!=mesh.triangle_indices.size()/3||
     mesh.positions_m.empty()||images.empty()) {
    result.error="invalid texture photometry input";return result;
  }
  std::unordered_map<std::uint64_t,std::size_t> image_by_frame;
  for(std::size_t i=0;i<images.size();++i) {
    if(!validImage(images[i])||!image_by_frame.emplace(images[i].frame_id,i).second) {
      result.error="invalid texture photometry image";return result;
    }
  }
  for(const auto& view:views) {
    const auto found=image_by_frame.find(view.frame_id);
    if(view.frame_id==0||view.width<=0||view.height<=0||found==image_by_frame.end()||
       images[found->second].width!=view.width||images[found->second].height!=view.height) {
      result.error="missing or mismatched texture photometry image";return result;
    }
  }
  std::map<EdgeKey,std::vector<EdgeUse>> edges;
  for(std::size_t triangle=0;triangle<labels.size();++triangle) {
    const auto& label=labels[triangle];
    if(!label.assigned)continue;
    if(label.view_index>=views.size()||label.frame_id!=views[label.view_index].frame_id||
       std::any_of(label.normalized_uv.begin(),label.normalized_uv.end(),[](const Vec2f& uv){
         return !std::isfinite(uv.x)||!std::isfinite(uv.y)||uv.x<0.0F||uv.x>1.0F||
                uv.y<0.0F||uv.y>1.0F;
       })) {
      result.error="invalid texture photometry label";return result;
    }
    for(std::size_t corner=0;corner<3;++corner) {
      const std::size_t next=(corner+1)%3;
      const auto va=mesh.triangle_indices[triangle*3+corner];
      const auto vb=mesh.triangle_indices[triangle*3+next];
      if(va>=mesh.positions_m.size()||vb>=mesh.positions_m.size()||va==vb) {
        result.error="invalid texture photometry mesh";return result;
      }
      const EdgeKey key{std::min(va,vb),std::max(va,vb)};
      auto found=edges.find(key);
      if(found==edges.end()&&edges.size()>=config.max_unique_edges) {
        result.input_valid=true;result.truncated=true;
        result.error="texture photometry edge budget exceeded";return result;
      }
      edges[key].push_back({triangle,corner,next});
    }
  }
  result.unique_edges=edges.size();result.input_valid=true;
  std::vector<Equation> equations;
  for(const auto& entry:edges) {
    if(entry.second.size()!=2)continue;
    const auto first=entry.second[0],second=entry.second[1];
    const auto& a=labels[first.triangle];const auto& b=labels[second.triangle];
    if(a.view_index==b.view_index)continue;
    ++result.seam_edges;
    if(equations.size()>=config.max_equations) {
      result.truncated=true;result.error="texture photometry equation budget exceeded";break;
    }
    std::array<std::vector<double>,3> ratios;
    const auto& image_a=images[image_by_frame.at(a.frame_id)];
    const auto& image_b=images[image_by_frame.at(b.frame_id)];
    for(std::size_t sample_index=0;sample_index<config.samples_per_seam_edge;++sample_index) {
      if(result.sample_tests>=config.max_sample_tests) {
        result.truncated=true;result.error="texture photometry sample budget exceeded";break;
      }
      ++result.sample_tests;
      const double t=(sample_index+0.5)/config.samples_per_seam_edge;
      const auto vertex=entry.first.a;
      const bool first_forward=mesh.triangle_indices[first.triangle*3+first.corner_a]==vertex;
      const bool second_forward=mesh.triangle_indices[second.triangle*3+second.corner_a]==vertex;
      const double ta=first_forward?t:1.0-t,tb=second_forward?t:1.0-t;
      const auto& au0=a.normalized_uv[first.corner_a];const auto& au1=a.normalized_uv[first.corner_b];
      const auto& bu0=b.normalized_uv[second.corner_a];const auto& bu1=b.normalized_uv[second.corner_b];
      const auto ca=sample(image_a,(1-ta)*au0.x+ta*au1.x,(1-ta)*au0.y+ta*au1.y);
      const auto cb=sample(image_b,(1-tb)*bu0.x+tb*bu1.x,(1-tb)*bu0.y+tb*bu1.y);
      if(ca[3]<254||cb[3]<254) {++result.rejected_samples;continue;}
      bool usable=true;
      for(std::size_t channel=0;channel<3;++channel)
        usable=usable&&ca[channel]>=config.min_usable_channel&&ca[channel]<=config.max_usable_channel&&
                       cb[channel]>=config.min_usable_channel&&cb[channel]<=config.max_usable_channel;
      if(!usable){++result.rejected_samples;continue;}
      for(std::size_t channel=0;channel<3;++channel)
        ratios[channel].push_back(std::log(ca[channel])-std::log(cb[channel]));
    }
    if(result.truncated)break;
    if(ratios[0].empty())continue;
    Equation equation;
    const bool input_order=a.view_index<b.view_index;
    equation.a=input_order?a.view_index:b.view_index;
    equation.b=input_order?b.view_index:a.view_index;
    for(std::size_t channel=0;channel<3;++channel) {
      const double raw=median(ratios[channel]);
      equation.delta[channel]=input_order?raw:-raw;
    }
    equations.push_back(equation);
  }
  result.equations=equations.size();
  std::vector<std::vector<std::size_t>> adjacency(views.size());
  for(std::size_t i=0;i<equations.size();++i) {
    adjacency[equations[i].a].push_back(i);adjacency[equations[i].b].push_back(i);
    ++result.corrections[equations[i].a].supporting_equations;
    ++result.corrections[equations[i].b].supporting_equations;
  }
  std::vector<int> component(views.size(),-1);std::vector<std::size_t> anchors;
  std::vector<std::array<double,3>> logs(views.size());
  for(std::size_t start=0;start<views.size();++start)if(component[start]<0&&!adjacency[start].empty()) {
    anchors.push_back(start);std::queue<std::size_t> pending;pending.push(start);
    component[start]=static_cast<int>(anchors.size()-1);
    while(!pending.empty()) {const auto current=pending.front();pending.pop();
      for(const auto equation_index:adjacency[current]) {const auto& equation=equations[equation_index];
        const bool current_is_a=equation.a==current;
        const auto next=current_is_a?equation.b:equation.a;
        if(component[next]<0){
          component[next]=component[current];
          for(std::size_t channel=0;channel<3;++channel)
            logs[next][channel]=logs[current][channel]+
                (current_is_a?equation.delta[channel]:-equation.delta[channel]);
          pending.push(next);
        }
      }
    }
  }
  result.connected_components=anchors.size();
  for(std::size_t iteration=0;iteration<config.solver_iterations;++iteration) {
    auto next=logs;
    for(std::size_t view=0;view<views.size();++view) {
      if(component[view]<0||anchors[static_cast<std::size_t>(component[view])]==view)continue;
      std::array<double,3> sums{};
      for(const auto index:adjacency[view]) {const auto& equation=equations[index];
        const bool is_b=equation.b==view;const auto neighbor=is_b?equation.a:equation.b;
        for(std::size_t channel=0;channel<3;++channel)
          sums[channel]+=logs[neighbor][channel]+(is_b?equation.delta[channel]:-equation.delta[channel]);
      }
      for(std::size_t channel=0;channel<3;++channel)
        next[view][channel]=sums[channel]/adjacency[view].size();
    }
    logs.swap(next);
  }
  for(std::size_t view=0;view<views.size();++view)if(component[view]>=0) {
    result.corrections[view].constrained=true;++result.constrained_views;
    for(std::size_t channel=0;channel<3;++channel) {
      const double raw=std::exp(logs[view][channel]);
      const double bounded=std::max(config.min_gain,std::min(config.max_gain,raw));
      if(bounded!=raw)++result.clamped_channels;
      result.corrections[view].rgb_gain[channel]=bounded;
    }
  }
  return result;
}

}  // namespace arkernel
