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

}  // namespace arkernel
