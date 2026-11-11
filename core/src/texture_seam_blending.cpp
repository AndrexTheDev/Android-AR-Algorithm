#include "arkernel/texture_seam_blending.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <map>
#include <stdexcept>
#include <vector>

namespace arkernel {
namespace {

struct EdgeKey {
  std::uint32_t a{0},b{0};
  bool operator<(const EdgeKey& other) const {
    return a<other.a||(a==other.a&&b<other.b);
  }
};
struct EdgeUse {
  std::size_t source_triangle{0};
  std::size_t output_triangle{0};
  std::size_t corner_a{0};
  std::size_t corner_b{0};
};
struct Point {double x{0.0},y{0.0};};

Point atlasPoint(const TextureAtlasLayoutResult& layout,const EdgeUse& use,
                 std::size_t corner) {
  const auto vertex=layout.mesh.triangle_indices[use.output_triangle*3+corner];
  const auto& uv=layout.mesh.texcoords[vertex];
  return {uv.x*(layout.atlas_width-1),uv.y*(layout.atlas_height-1)};
}

std::array<double,4> colorAt(const TextureBakingResult& baked,const Point& point) {
  const int x=std::max(0,std::min(baked.width-1,static_cast<int>(std::llround(point.x))));
  const int y=std::max(0,std::min(baked.height-1,static_cast<int>(std::llround(point.y))));
  const std::size_t offset=(static_cast<std::size_t>(y)*baked.width+x)*4;
  return {static_cast<double>(baked.rgba8[offset]),
          static_cast<double>(baked.rgba8[offset+1]),
          static_cast<double>(baked.rgba8[offset+2]),
          static_cast<double>(baked.rgba8[offset+3])};
}

}  // namespace

TextureSeamBlendingResult blendTextureAtlasSeams(
    const ReconstructionMesh& source_mesh,const TextureAtlasLayoutResult& layout,
    const std::vector<TriangleTextureView>& labels,const TextureBakingResult& baked,
    TextureSeamBlendingConfig config) {
  if(config.blend_radius_px<=0||config.blend_radius_px>32||
     config.max_triangles==0||config.max_triangles>10000000||
     config.max_unique_edges==0||config.max_unique_edges>10000000||
     config.max_seam_edges==0||config.max_seam_edges>10000000||
     config.max_pixel_tests==0||config.max_pixel_tests>1000000000||
     config.max_pixel_updates==0||config.max_pixel_updates>268435456) {
    throw std::invalid_argument("Invalid texture seam blending configuration");
  }
  TextureSeamBlendingResult result;
  const std::size_t source_triangles=source_mesh.triangle_indices.size()/3;
  const std::size_t atlas_pixels=static_cast<std::size_t>(layout.atlas_width)*layout.atlas_height;
  if(source_mesh.triangle_indices.size()%3!=0||source_triangles==0||
     source_triangles>config.max_triangles||labels.size()!=source_triangles||
     !layout.input_valid||layout.truncated||!layout.error.empty()||
     layout.atlas_width<=0||layout.atlas_height<=0||layout.patches.empty()||
     layout.source_triangle_patch.size()!=source_triangles||
     layout.source_triangle_output_triangle.size()!=source_triangles||
     layout.mesh.triangle_indices.size()%3!=0||
     layout.mesh.triangle_indices.size()/3!=layout.assigned_triangles||
     layout.mesh.texcoords.size()!=layout.mesh.positions_m.size()||
     !baked.input_valid||baked.truncated||!baked.error.empty()||
     baked.width!=layout.atlas_width||baked.height!=layout.atlas_height||
     atlas_pixels>std::numeric_limits<std::size_t>::max()/4||
     baked.rgba8.size()!=atlas_pixels*4) {
    result.error="invalid texture seam blending input";return result;
  }
  if(std::any_of(layout.mesh.triangle_indices.begin(),layout.mesh.triangle_indices.end(),
                 [&](std::uint32_t index){return index>=layout.mesh.texcoords.size();})||
     std::any_of(layout.mesh.texcoords.begin(),layout.mesh.texcoords.end(),[](const Vec2f& uv){
       return !std::isfinite(uv.x)||!std::isfinite(uv.y)||uv.x<0.0F||uv.x>1.0F||
              uv.y<0.0F||uv.y>1.0F;
     })) {
    result.error="invalid texture seam atlas mesh";return result;
  }
  const std::size_t no_patch=std::numeric_limits<std::size_t>::max();
  std::vector<bool> output_seen(layout.assigned_triangles,false);
  std::size_t mapped_outputs=0;
  for(std::size_t triangle=0;triangle<source_triangles;++triangle) {
    const auto patch=layout.source_triangle_patch[triangle];
    const auto output=layout.source_triangle_output_triangle[triangle];
    if(!labels[triangle].assigned) {
      if(patch!=no_patch||output!=no_patch) {
        result.error="invalid texture seam triangle mapping";return result;
      }
      continue;
    }
    if(patch>=layout.patches.size()||output>=layout.assigned_triangles||
       output_seen[output]||layout.patches[patch].view_index!=labels[triangle].view_index||
       layout.patches[patch].frame_id!=labels[triangle].frame_id) {
      result.error="invalid texture seam triangle mapping";return result;
    }
    output_seen[output]=true;++mapped_outputs;
  }
  if(mapped_outputs!=layout.assigned_triangles) {
    result.error="invalid texture seam triangle mapping";return result;
  }
  for(std::size_t patch_index=0;patch_index<layout.patches.size();++patch_index) {
    const auto& patch=layout.patches[patch_index];
    if(patch.patch_id!=patch_index||patch.atlas_x_px<0||patch.atlas_y_px<0||
       patch.atlas_width_px<=0||patch.atlas_height_px<=0||
       static_cast<std::int64_t>(patch.atlas_x_px)+patch.atlas_width_px>layout.atlas_width||
       static_cast<std::int64_t>(patch.atlas_y_px)+patch.atlas_height_px>layout.atlas_height) {
      result.error="invalid texture seam patch";return result;
    }
  }
  std::map<EdgeKey,std::vector<EdgeUse>> edges;
  for(std::size_t triangle=0;triangle<source_triangles;++triangle) {
    if(!labels[triangle].assigned)continue;
    for(std::size_t corner=0;corner<3;++corner) {
      const std::size_t next=(corner+1)%3;
      const auto va=source_mesh.triangle_indices[triangle*3+corner];
      const auto vb=source_mesh.triangle_indices[triangle*3+next];
      if(va>=source_mesh.positions_m.size()||vb>=source_mesh.positions_m.size()||va==vb) {
        result.error="invalid texture seam source mesh";return result;
      }
      const EdgeKey key{std::min(va,vb),std::max(va,vb)};
      auto found=edges.find(key);
      if(found==edges.end()&&edges.size()>=config.max_unique_edges) {
        result.input_valid=true;result.truncated=true;
        result.error="texture seam edge budget exceeded";return result;
      }
      edges[key].push_back({triangle,
          layout.source_triangle_output_triangle[triangle],corner,next});
    }
  }
  result.input_valid=true;result.unique_edges=edges.size();result.width=baked.width;
  result.height=baked.height;result.rgba8=baked.rgba8;
  for(const auto& entry:edges) {
    if(entry.second.size()!=2)continue;
    const auto& first=entry.second[0];const auto& second=entry.second[1];
    const auto first_patch=layout.source_triangle_patch[first.source_triangle];
    const auto second_patch=layout.source_triangle_patch[second.source_triangle];
    if(first_patch==second_patch)continue;
    if(result.seam_edges>=config.max_seam_edges) {
      result.truncated=true;result.error="texture seam count budget exceeded";return result;
    }
    ++result.seam_edges;
    std::array<EdgeUse,2> uses{{first,second}};
    std::array<std::size_t,2> patch_ids{{first_patch,second_patch}};
    std::array<Point,2> starts{},ends{};
    for(std::size_t side=0;side<2;++side) {
      auto start=atlasPoint(layout,uses[side],uses[side].corner_a);
      auto end=atlasPoint(layout,uses[side],uses[side].corner_b);
      const auto source_start=source_mesh.triangle_indices[
          uses[side].source_triangle*3+uses[side].corner_a];
      if(source_start!=entry.first.a)std::swap(start,end);
      starts[side]=start;ends[side]=end;
    }
    for(std::size_t side=0;side<2;++side) {
      const auto& patch=layout.patches[patch_ids[side]];
      const Point delta{ends[side].x-starts[side].x,ends[side].y-starts[side].y};
      const double length_squared=delta.x*delta.x+delta.y*delta.y;
      if(!std::isfinite(length_squared)||length_squared<1e-12)continue;
      const double radius=config.blend_radius_px;
      const int min_x=std::max(patch.atlas_x_px,static_cast<int>(std::floor(
          std::min(starts[side].x,ends[side].x)-radius)));
      const int max_x=std::min(patch.atlas_x_px+patch.atlas_width_px-1,
          static_cast<int>(std::ceil(std::max(starts[side].x,ends[side].x)+radius)));
      const int min_y=std::max(patch.atlas_y_px,static_cast<int>(std::floor(
          std::min(starts[side].y,ends[side].y)-radius)));
      const int max_y=std::min(patch.atlas_y_px+patch.atlas_height_px-1,
          static_cast<int>(std::ceil(std::max(starts[side].y,ends[side].y)+radius)));
      for(int y=min_y;y<=max_y;++y)for(int x=min_x;x<=max_x;++x) {
        if(result.pixel_tests>=config.max_pixel_tests) {
          result.truncated=true;result.error="texture seam pixel-test budget exceeded";
          return result;
        }
        ++result.pixel_tests;
        const double px=x+0.5,py=y+0.5;
        const double t=std::max(0.0,std::min(1.0,
            ((px-starts[side].x)*delta.x+(py-starts[side].y)*delta.y)/length_squared));
        const Point nearest{starts[side].x+t*delta.x,starts[side].y+t*delta.y};
        const double distance=std::hypot(px-nearest.x,py-nearest.y);
        if(distance>radius)continue;
        const Point own_point{starts[side].x+t*delta.x,starts[side].y+t*delta.y};
        const Point other_point{starts[1-side].x+t*(ends[1-side].x-starts[1-side].x),
                                starts[1-side].y+t*(ends[1-side].y-starts[1-side].y)};
        const auto own=colorAt(baked,own_point);const auto other=colorAt(baked,other_point);
        const std::size_t offset=(static_cast<std::size_t>(y)*baked.width+x)*4;
        if(own[3]<1.0||other[3]<1.0||baked.rgba8[offset+3]==0) {
          ++result.skipped_transparent_samples;continue;
        }
        if(result.pixel_updates>=config.max_pixel_updates) {
          result.truncated=true;result.error="texture seam pixel-update budget exceeded";
          return result;
        }
        const double weight=std::max(0.0,std::min(1.0,1.0-distance/radius));
        if(weight<=0.0)continue;
        for(std::size_t channel=0;channel<3;++channel) {
          const double target=(own[channel]+other[channel])*0.5;
          const double current=result.rgba8[offset+channel];
          result.rgba8[offset+channel]=static_cast<std::uint8_t>(std::llround(
              std::max(0.0,std::min(255.0,current+weight*(target-current)))));
        }
        ++result.pixel_updates;
      }
    }
  }
  return result;
}

namespace {

std::array<double,4> colorAt(const std::vector<std::uint8_t>& rgba8,int width,int height,
                             const Point& point,const TextureAtlasPatch& patch) {
  const int min_x=std::max(0,patch.atlas_x_px);
  const int max_x=std::min(width-1,patch.atlas_x_px+patch.atlas_width_px-1);
  const int min_y=std::max(0,patch.atlas_y_px);
  const int max_y=std::min(height-1,patch.atlas_y_px+patch.atlas_height_px-1);
  if(min_x>max_x||min_y>max_y)return {0.0,0.0,0.0,0.0};
  const int x=std::max(min_x,std::min(max_x,
      static_cast<int>(std::llround(point.x))));
  const int y=std::max(min_y,std::min(max_y,
      static_cast<int>(std::llround(point.y))));
  const std::size_t offset=(static_cast<std::size_t>(y)*width+x)*4;
  return {static_cast<double>(rgba8[offset]),static_cast<double>(rgba8[offset+1]),
          static_cast<double>(rgba8[offset+2]),static_cast<double>(rgba8[offset+3])};
}

struct SeamSample {
  std::size_t patch_a{0};
  std::size_t patch_b{0};
  std::array<double,3> color_a{{0.0,0.0,0.0}};
  std::array<double,3> color_b{{0.0,0.0,0.0}};
};

struct PatchSystem {
  std::array<double,3> a00{{0.0,0.0,0.0}};
  std::array<double,3> a01{{0.0,0.0,0.0}};
  std::array<double,3> a11{{0.0,0.0,0.0}};
  std::array<double,3> b0{{0.0,0.0,0.0}};
  std::array<double,3> b1{{0.0,0.0,0.0}};
};

void addEquation(PatchSystem& system,std::size_t channel,double value,double target) {
  system.a00[channel]+=value*value;
  system.a01[channel]+=value;
  system.a11[channel]+=1.0;
  system.b0[channel]+=value*target;
  system.b1[channel]+=target;
}

double seamResidual(const std::vector<SeamSample>& samples,
                    const std::vector<std::array<double,3>>& gains,
                    const std::vector<std::array<double,3>>& offsets) {
  if(samples.empty())return 0.0;
  double sum=0.0;
  for(const auto& sample:samples)for(std::size_t channel=0;channel<3;++channel) {
    const double a=gains[sample.patch_a][channel]*sample.color_a[channel]+
                   offsets[sample.patch_a][channel];
    const double b=gains[sample.patch_b][channel]*sample.color_b[channel]+
                   offsets[sample.patch_b][channel];
    sum+=(a-b)*(a-b);
  }
  return sum/(static_cast<double>(samples.size())*3.0);
}

struct SeamMetrics {
  double rmse{0.0};
  double max_residual{0.0};
};

SeamMetrics seamMetrics(const std::vector<SeamSample>& samples,
                        const std::vector<std::array<double,3>>& gains,
                        const std::vector<std::array<double,3>>& offsets) {
  SeamMetrics metrics;
  if(samples.empty())return metrics;
  double sum=0.0;
  for(const auto& sample:samples)for(std::size_t channel=0;channel<3;++channel) {
    const double a=gains[sample.patch_a][channel]*sample.color_a[channel]+
                   offsets[sample.patch_a][channel];
    const double b=gains[sample.patch_b][channel]*sample.color_b[channel]+
                   offsets[sample.patch_b][channel];
    const double residual=a-b;
    sum+=residual*residual;
    metrics.max_residual=std::max(metrics.max_residual,std::abs(residual));
  }
  metrics.rmse=std::sqrt(sum/(static_cast<double>(samples.size())*3.0));
  return metrics;
}

}  // namespace

TextureGlobalSeamResult optimizeTextureAtlasSeamsGlobally(
    const ReconstructionMesh& source_mesh,const TextureAtlasLayoutResult& layout,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<std::uint8_t>& rgba8,int width,int height,
    TextureGlobalSeamConfig config) {
  if(config.samples_per_seam<=0||config.samples_per_seam>64||
     config.max_triangles==0||config.max_triangles>10000000||
     config.max_unique_edges==0||config.max_unique_edges>10000000||
     config.max_seam_pairs==0||config.max_seam_pairs>10000000||
     config.max_seam_samples==0||config.max_seam_samples>1000000000||
     config.max_patches==0||config.max_patches>1000000||
     config.max_iterations==0||config.max_iterations>256||
     config.max_pixel_updates==0||config.max_pixel_updates>268435456||
     !std::isfinite(config.convergence_delta)||config.convergence_delta<=0.0||
     !std::isfinite(config.regularization)||config.regularization<0.0||
     !std::isfinite(config.min_gain)||!std::isfinite(config.max_gain)||
     config.min_gain<=0.0||config.min_gain>=config.max_gain||
     !std::isfinite(config.min_offset)||!std::isfinite(config.max_offset)||
     config.min_offset>=config.max_offset||
     config.holdout_stride<2||config.holdout_stride>16||
     config.min_training_samples==0||config.min_training_samples>1000000000||
     config.min_holdout_samples>1000000000||
     !std::isfinite(config.max_training_rmse)||config.max_training_rmse<=0.0||
     config.max_training_rmse>1.0||
     !std::isfinite(config.max_holdout_residual)||
     config.max_holdout_residual<=0.0||config.max_holdout_residual>1.0||
     !std::isfinite(config.max_holdout_rmse)||config.max_holdout_rmse<=0.0||
     config.max_holdout_rmse>1.0||
     !std::isfinite(config.max_holdout_ratio)||config.max_holdout_ratio<=0.0||
     config.max_holdout_ratio>32.0) {
    throw std::invalid_argument("Invalid global texture seam configuration");
  }
  TextureGlobalSeamResult result;
  result.width=width;result.height=height;
  result.telemetry.enabled=true;
  const std::size_t source_triangles=source_mesh.triangle_indices.size()/3;
  if(width<=0||height<=0||static_cast<std::size_t>(width)>4096||
     static_cast<std::size_t>(height)>4096||
     source_mesh.triangle_indices.size()%3!=0||source_triangles==0||
     source_triangles>config.max_triangles||labels.size()!=source_triangles||
     !layout.input_valid||layout.truncated||!layout.error.empty()||
     layout.atlas_width!=width||layout.atlas_height!=height||
     layout.patches.empty()||layout.patches.size()>config.max_patches||
     layout.source_triangle_patch.size()!=source_triangles||
     layout.source_triangle_output_triangle.size()!=source_triangles||
     layout.mesh.triangle_indices.size()%3!=0||
     layout.mesh.triangle_indices.size()/3!=layout.assigned_triangles||
     layout.mesh.texcoords.size()!=layout.mesh.positions_m.size()||
     rgba8.size()!=static_cast<std::size_t>(width)*height*4) {
    result.telemetry.error="invalid global texture seam input";return result;
  }
  if(std::any_of(layout.mesh.triangle_indices.begin(),layout.mesh.triangle_indices.end(),
                 [&](std::uint32_t index){return index>=layout.mesh.texcoords.size();})||
     std::any_of(layout.mesh.texcoords.begin(),layout.mesh.texcoords.end(),[](const Vec2f& uv){
       return !std::isfinite(uv.x)||!std::isfinite(uv.y)||uv.x<0.0F||uv.x>1.0F||
              uv.y<0.0F||uv.y>1.0F;
     })) {
    result.telemetry.error="invalid global texture seam atlas mesh";return result;
  }
  const std::size_t no_patch=std::numeric_limits<std::size_t>::max();
  std::vector<bool> output_seen(layout.assigned_triangles,false);
  std::size_t mapped_outputs=0;
  for(std::size_t triangle=0;triangle<source_triangles;++triangle) {
    const auto patch=layout.source_triangle_patch[triangle];
    const auto output=layout.source_triangle_output_triangle[triangle];
    if(!labels[triangle].assigned) {
      if(patch!=no_patch||output!=no_patch) {
        result.telemetry.error="invalid global texture seam triangle mapping";
        return result;
      }
      continue;
    }
    if(patch>=layout.patches.size()||output>=layout.assigned_triangles||
       output_seen[output]||layout.patches[patch].view_index!=labels[triangle].view_index||
       layout.patches[patch].frame_id!=labels[triangle].frame_id) {
      result.telemetry.error="invalid global texture seam triangle mapping";
      return result;
    }
    output_seen[output]=true;++mapped_outputs;
  }
  if(mapped_outputs!=layout.assigned_triangles) {
    result.telemetry.error="invalid global texture seam triangle mapping";return result;
  }
  for(std::size_t patch_index=0;patch_index<layout.patches.size();++patch_index) {
    const auto& patch=layout.patches[patch_index];
    if(patch.patch_id!=patch_index||patch.atlas_x_px<0||patch.atlas_y_px<0||
       patch.atlas_width_px<=0||patch.atlas_height_px<=0||
       static_cast<std::int64_t>(patch.atlas_x_px)+patch.atlas_width_px>width||
       static_cast<std::int64_t>(patch.atlas_y_px)+patch.atlas_height_px>height) {
      result.telemetry.error="invalid global texture seam patch";return result;
    }
  }
  result.telemetry.input_valid=true;
  std::map<EdgeKey,std::vector<EdgeUse>> edges;
  for(std::size_t triangle=0;triangle<source_triangles;++triangle) {
    if(!labels[triangle].assigned)continue;
    for(std::size_t corner=0;corner<3;++corner) {
      const std::size_t next=(corner+1)%3;
      const auto va=source_mesh.triangle_indices[triangle*3+corner];
      const auto vb=source_mesh.triangle_indices[triangle*3+next];
      if(va>=source_mesh.positions_m.size()||vb>=source_mesh.positions_m.size()||va==vb) {
        result.telemetry.error="invalid global texture seam source mesh";return result;
      }
      const EdgeKey key{std::min(va,vb),std::max(va,vb)};
      auto found=edges.find(key);
      if(found==edges.end()&&edges.size()>=config.max_unique_edges) {
        result.telemetry.truncated=true;
        result.telemetry.error="global texture seam edge budget exceeded";return result;
      }
      edges[key].push_back({triangle,
          layout.source_triangle_output_triangle[triangle],corner,next});
    }
  }
  result.unique_edges=edges.size();
  std::vector<SeamSample> samples;
  for(const auto& entry:edges) {
    if(entry.second.size()!=2)continue;
    const auto& first=entry.second[0];const auto& second=entry.second[1];
    const auto first_patch=layout.source_triangle_patch[first.source_triangle];
    const auto second_patch=layout.source_triangle_patch[second.source_triangle];
    if(first_patch==second_patch)continue;
    if(result.telemetry.seam_pairs>=config.max_seam_pairs) {
      result.telemetry.truncated=true;
      result.telemetry.error="global texture seam count budget exceeded";return result;
    }
    ++result.telemetry.seam_pairs;
    std::array<EdgeUse,2> uses{{first,second}};
    std::array<std::size_t,2> patch_ids{{first_patch,second_patch}};
    std::array<Point,2> starts{},ends{};
    for(std::size_t side=0;side<2;++side) {
      auto start=atlasPoint(layout,uses[side],uses[side].corner_a);
      auto end=atlasPoint(layout,uses[side],uses[side].corner_b);
      const auto source_start=source_mesh.triangle_indices[
          uses[side].source_triangle*3+uses[side].corner_a];
      if(source_start!=entry.first.a)std::swap(start,end);
      starts[side]=start;ends[side]=end;
    }
    for(int sample=0;sample<config.samples_per_seam;++sample) {
      if(samples.size()>=config.max_seam_samples) {
        result.telemetry.truncated=true;
        result.telemetry.error="global texture seam sample budget exceeded";
        return result;
      }
      const double t=(static_cast<double>(sample)+0.5)/config.samples_per_seam;
      const Point point_a{starts[0].x+t*(ends[0].x-starts[0].x),
                          starts[0].y+t*(ends[0].y-starts[0].y)};
      const Point point_b{starts[1].x+t*(ends[1].x-starts[1].x),
                          starts[1].y+t*(ends[1].y-starts[1].y)};
      const auto color_a=colorAt(rgba8,width,height,point_a,layout.patches[patch_ids[0]]);
      const auto color_b=colorAt(rgba8,width,height,point_b,layout.patches[patch_ids[1]]);
      if(color_a[3]<255.0||color_b[3]<255.0) {
        ++result.telemetry.skipped_transparent_samples;continue;
      }
      SeamSample item;
      item.patch_a=patch_ids[0];item.patch_b=patch_ids[1];
      for(std::size_t channel=0;channel<3;++channel) {
        item.color_a[channel]=color_a[channel]/255.0;
        item.color_b[channel]=color_b[channel]/255.0;
      }
      samples.push_back(item);
    }
  }
  result.telemetry.sampled_pairs=samples.size();
  std::vector<SeamSample> training,holdout;
  if(config.require_holdout_validation) {
    training.reserve(samples.size());holdout.reserve(samples.size());
    for(std::size_t i=0;i<samples.size();++i) {
      if((i+1)%config.holdout_stride==0)holdout.push_back(samples[i]);
      else training.push_back(samples[i]);
    }
  } else {
    // Preserve the existing bounded global solve exactly when strict
    // cross-validation is not requested: no sample is withheld by default.
    training=samples;
  }
  result.telemetry.training_samples=training.size();
  result.telemetry.holdout_samples=holdout.size();
  result.rgba8=rgba8;

  const std::size_t patch_count=layout.patches.size();
  std::vector<std::array<double,3>> gains(patch_count),offsets(patch_count);
  for(std::size_t patch=0;patch<patch_count;++patch)for(std::size_t channel=0;channel<3;++channel) {
    gains[patch][channel]=1.0;offsets[patch][channel]=0.0;
  }
  result.telemetry.residual_before=seamResidual(training,gains,offsets);
  if(!training.empty()) {
    for(std::size_t iteration=0;iteration<config.max_iterations;++iteration) {
      std::vector<PatchSystem> systems(patch_count);
      for(std::size_t patch=0;patch<patch_count;++patch)for(std::size_t channel=0;channel<3;++channel) {
        systems[patch].a00[channel]=config.regularization;
        systems[patch].a11[channel]=config.regularization;
        systems[patch].b0[channel]=config.regularization;
      }
      for(const auto& sample:training)for(std::size_t channel=0;channel<3;++channel) {
        const double target_a=gains[sample.patch_b][channel]*sample.color_b[channel]+
                              offsets[sample.patch_b][channel];
        const double target_b=gains[sample.patch_a][channel]*sample.color_a[channel]+
                              offsets[sample.patch_a][channel];
        addEquation(systems[sample.patch_a],channel,sample.color_a[channel],target_a);
        addEquation(systems[sample.patch_b],channel,sample.color_b[channel],target_b);
      }
      std::vector<std::array<double,3>> next_gains=gains,next_offsets=offsets;
      double maximum_delta=0.0;
      for(std::size_t patch=0;patch<patch_count;++patch)for(std::size_t channel=0;channel<3;++channel) {
        const auto& system=systems[patch];
        const double determinant=system.a00[channel]*system.a11[channel]-
                                 system.a01[channel]*system.a01[channel];
        double gain=gains[patch][channel],offset=offsets[patch][channel];
        if(std::isfinite(determinant)&&determinant>1e-12) {
          gain=(system.b0[channel]*system.a11[channel]-
                system.b1[channel]*system.a01[channel])/determinant;
          offset=(system.a00[channel]*system.b1[channel]-
                  system.a01[channel]*system.b0[channel])/determinant;
        }
        if(!std::isfinite(gain))gain=gains[patch][channel];
        if(!std::isfinite(offset))offset=offsets[patch][channel];
        gain=std::max(config.min_gain,std::min(config.max_gain,gain));
        offset=std::max(config.min_offset,std::min(config.max_offset,offset));
        maximum_delta=std::max(maximum_delta,std::abs(gain-gains[patch][channel]));
        maximum_delta=std::max(maximum_delta,std::abs(offset-offsets[patch][channel]));
        next_gains[patch][channel]=gain;next_offsets[patch][channel]=offset;
      }
      gains=std::move(next_gains);offsets=std::move(next_offsets);
      result.telemetry.iterations=iteration+1;
      if(maximum_delta<=config.convergence_delta)break;
    }
  }
  result.telemetry.residual_after=seamResidual(training,gains,offsets);
  const auto training_metrics=seamMetrics(training,gains,offsets);
  const auto holdout_metrics=seamMetrics(holdout,gains,offsets);
  result.telemetry.training_rmse=training_metrics.rmse;
  result.telemetry.holdout_max_residual=holdout_metrics.max_residual;
  result.telemetry.holdout_rmse=holdout_metrics.rmse;
  result.telemetry.holdout_ratio=holdout_metrics.rmse/
      std::max(training_metrics.rmse,1e-6);
  std::vector<bool> constrained(patch_count,false);
  for(const auto& sample:training) {
    constrained[sample.patch_a]=true;constrained[sample.patch_b]=true;
  }
  for(std::size_t patch=0;patch<patch_count;++patch) {
    if(!constrained[patch])continue;
    bool changed=false;
    for(std::size_t channel=0;channel<3;++channel) {
      changed=changed||std::abs(gains[patch][channel]-1.0)>1e-12||
                        std::abs(offsets[patch][channel])>1e-12;
    }
    if(changed)++result.telemetry.constrained_patches;
  }
  if(config.require_holdout_validation) {
    result.telemetry.holdout_validated=true;
    const auto reject=[&](TextureGlobalSeamRejection reason,const char* error) {
      result.telemetry.holdout_accepted=false;
      result.telemetry.rejection=reason;
      result.telemetry.error=error;
      result.telemetry.constrained_patches=0;
      result.telemetry.pixel_updates=0;
      result.rgba8=rgba8;
    };
    if(training.size()<config.min_training_samples) {
      reject(TextureGlobalSeamRejection::kInsufficientTrainingEvidence,
             "global texture seam insufficient training evidence");
    } else if(holdout.size()<config.min_holdout_samples) {
      reject(TextureGlobalSeamRejection::kInsufficientHoldoutEvidence,
             "global texture seam insufficient holdout evidence");
    } else if(!std::isfinite(result.telemetry.training_rmse)||
              result.telemetry.training_rmse>config.max_training_rmse) {
      reject(TextureGlobalSeamRejection::kTrainingRmseTooLarge,
             "global texture seam training rmse too large");
    } else if(!std::isfinite(result.telemetry.holdout_max_residual)||
              result.telemetry.holdout_max_residual>config.max_holdout_residual) {
      reject(TextureGlobalSeamRejection::kHoldoutResidualTooLarge,
             "global texture seam holdout residual too large");
    } else if(!std::isfinite(result.telemetry.holdout_rmse)||
              result.telemetry.holdout_rmse>config.max_holdout_rmse) {
      reject(TextureGlobalSeamRejection::kHoldoutRmseTooLarge,
             "global texture seam holdout rmse too large");
    } else if(!std::isfinite(result.telemetry.holdout_ratio)||
              result.telemetry.holdout_ratio>config.max_holdout_ratio) {
      reject(TextureGlobalSeamRejection::kHoldoutRatioTooLarge,
             "global texture seam holdout ratio too large");
    } else {
      result.telemetry.holdout_accepted=true;
      result.telemetry.rejection=TextureGlobalSeamRejection::kNone;
    }
  } else {
    result.telemetry.holdout_accepted=true;
    result.telemetry.holdout_validated=false;
    result.telemetry.rejection=TextureGlobalSeamRejection::kNone;
  }
  if(!result.telemetry.holdout_accepted)return result;
  if(training.empty())return result;
  for(std::size_t patch_index=0;patch_index<patch_count;++patch_index) {
    const auto& patch=layout.patches[patch_index];
    for(int y=patch.atlas_y_px;y<patch.atlas_y_px+patch.atlas_height_px;++y) {
      for(int x=patch.atlas_x_px;x<patch.atlas_x_px+patch.atlas_width_px;++x) {
        const std::size_t offset=(static_cast<std::size_t>(y)*width+x)*4;
        if(result.rgba8[offset+3]==0)continue;
        if(result.telemetry.pixel_updates>=config.max_pixel_updates) {
          result.telemetry.truncated=true;
          result.telemetry.error="global texture seam pixel-update budget exceeded";
          return result;
        }
        for(std::size_t channel=0;channel<3;++channel) {
          const double current=rgba8[offset+channel]/255.0;
          const double corrected=gains[patch_index][channel]*current+
                                 offsets[patch_index][channel];
          result.rgba8[offset+channel]=static_cast<std::uint8_t>(std::llround(
              std::max(0.0,std::min(255.0,corrected*255.0))));
        }
        ++result.telemetry.pixel_updates;
      }
    }
  }
  return result;
}

const char* textureGlobalSeamRejectionText(
    TextureGlobalSeamRejection rejection) {
  switch(rejection) {
    case TextureGlobalSeamRejection::kNone:
      return "none";
    case TextureGlobalSeamRejection::kInsufficientTrainingEvidence:
      return "insufficient training evidence";
    case TextureGlobalSeamRejection::kInsufficientHoldoutEvidence:
      return "insufficient holdout evidence";
    case TextureGlobalSeamRejection::kTrainingRmseTooLarge:
      return "training rmse too large";
    case TextureGlobalSeamRejection::kHoldoutResidualTooLarge:
      return "holdout residual too large";
    case TextureGlobalSeamRejection::kHoldoutRmseTooLarge:
      return "holdout rmse too large";
    case TextureGlobalSeamRejection::kHoldoutRatioTooLarge:
      return "holdout ratio too large";
  }
  return "unknown rejection";
}

}  // namespace arkernel
