#include "arkernel/texture_baking.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>

namespace arkernel {
namespace {

std::uint8_t clampByte(int value) {
  return static_cast<std::uint8_t>(std::max(0, std::min(255, value)));
}

bool validPackedRows(const TextureSourceImageView& image, int bytes_per_pixel) {
  if (image.width <= 0 || image.height <= 0 || image.row_stride_bytes <
      image.width * bytes_per_pixel || !image.data) return false;
  const std::size_t row = static_cast<std::size_t>(image.height - 1);
  const std::size_t stride = static_cast<std::size_t>(image.row_stride_bytes);
  const std::size_t tail = static_cast<std::size_t>(image.width) * bytes_per_pixel;
  return row <= (std::numeric_limits<std::size_t>::max() - tail) / stride &&
         row * stride + tail <= image.data_size;
}

bool validImage(const TextureSourceImageView& image) {
  if (image.frame_id == 0 || image.width <= 0 || image.height <= 0 ||
      image.width > 32768 || image.height > 32768) return false;
  switch (image.format) {
    case TexturePixelFormat::kGray8: return validPackedRows(image, 1);
    case TexturePixelFormat::kRgb8: return validPackedRows(image, 3);
    case TexturePixelFormat::kRgba8: return validPackedRows(image, 4);
    case TexturePixelFormat::kI420: {
      if (!image.data || (image.width & 1) || (image.height & 1) ||
          image.row_stride_bytes != image.width) return false;
      const std::size_t pixels = static_cast<std::size_t>(image.width) * image.height;
      return pixels <= std::numeric_limits<std::size_t>::max() / 3 * 2 &&
             image.data_size == pixels + pixels / 2;
    }
  }
  return false;
}

std::array<std::uint8_t,4> sample(const TextureSourceImageView& image,
                                  double x, double y) {
  const int ix = std::max(0, std::min(image.width - 1,
      static_cast<int>(std::llround(x))));
  const int iy = std::max(0, std::min(image.height - 1,
      static_cast<int>(std::llround(y))));
  if (image.format == TexturePixelFormat::kI420) {
    const std::size_t pixels = static_cast<std::size_t>(image.width) * image.height;
    const int chroma_width = image.width / 2;
    const std::size_t chroma_offset = static_cast<std::size_t>(iy / 2) * chroma_width + ix / 2;
    const int yy = image.data[static_cast<std::size_t>(iy) * image.width + ix];
    const int uu = image.data[pixels + chroma_offset];
    const int vv = image.data[pixels + pixels / 4 + chroma_offset];
    const int c = std::max(0, yy - 16), d = uu - 128, e = vv - 128;
    return {clampByte((298*c + 409*e + 128) >> 8),
            clampByte((298*c - 100*d - 208*e + 128) >> 8),
            clampByte((298*c + 516*d + 128) >> 8), 255};
  }
  const int channels = image.format == TexturePixelFormat::kGray8 ? 1 :
                       image.format == TexturePixelFormat::kRgb8 ? 3 : 4;
  const std::size_t offset = static_cast<std::size_t>(iy) * image.row_stride_bytes +
                             static_cast<std::size_t>(ix) * channels;
  if (channels == 1) return {image.data[offset],image.data[offset],image.data[offset],255};
  return {image.data[offset],image.data[offset+1],image.data[offset+2],
          channels == 4 ? image.data[offset+3] : static_cast<std::uint8_t>(255)};
}

}  // namespace

TextureBakingResult bakeTextureAtlasWithCorrections(
    const TextureAtlasLayoutResult& layout,
    const std::vector<TextureViewReference>& views,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<TextureSourceImageView>& images,
    const std::vector<TextureViewColorCorrection>& corrections,
    TextureBakingConfig config) {
  if (config.max_source_images == 0 || config.max_source_images > 4096 ||
      config.max_atlas_pixels == 0 || config.max_atlas_pixels > 268435456 ||
      config.max_pixel_tests == 0 || config.max_pixel_tests > 1000000000 ||
      config.max_padding_pixels > 268435456) {
    throw std::invalid_argument("Invalid texture baking configuration");
  }
  TextureBakingResult result;
  const std::size_t assigned_labels=static_cast<std::size_t>(std::count_if(
      labels.begin(),labels.end(),[](const TriangleTextureView& value){return value.assigned;}));
  if (!layout.input_valid || layout.truncated || !layout.error.empty() ||
      layout.atlas_width <= 0 || layout.atlas_height <= 0 ||
      layout.padding_px < 0 || layout.padding_px > 256 ||
      labels.size() != layout.source_triangle_patch.size() ||
      assigned_labels != layout.assigned_triangles ||
      layout.mesh.triangle_indices.size() / 3 != layout.assigned_triangles ||
      layout.patches.empty() || views.empty()) {
    result.error = "invalid texture atlas layout";
    return result;
  }
  const std::size_t atlas_pixels = static_cast<std::size_t>(layout.atlas_width) *
                                   layout.atlas_height;
  if (atlas_pixels > config.max_atlas_pixels || atlas_pixels >
      std::numeric_limits<std::size_t>::max() / 4) {
    result.truncated = true; result.error = "texture atlas pixel budget exceeded";
    return result;
  }
  if (images.empty() || images.size() > config.max_source_images) {
    result.error = "invalid texture source image count";
    return result;
  }
  if(corrections.size()!=views.size()) {
    result.error="invalid texture color corrections";return result;
  }
  for(std::size_t i=0;i<corrections.size();++i) {
    const auto& correction=corrections[i];
    if(correction.view_index!=i||correction.frame_id!=views[i].frame_id||
       std::any_of(correction.rgb_gain.begin(),correction.rgb_gain.end(),
                   [](double gain){return !std::isfinite(gain)||gain<=0.0||gain>8.0;})) {
      result.error="invalid texture color correction";return result;
    }
  }
  std::unordered_map<std::uint64_t,std::size_t> image_by_frame;
  image_by_frame.reserve(images.size());
  for (std::size_t i=0;i<images.size();++i) {
    if (!validImage(images[i]) || !image_by_frame.emplace(images[i].frame_id,i).second) {
      result.error = "invalid texture source image"; return result;
    }
  }
  for (std::size_t patch_index=0;patch_index<layout.patches.size();++patch_index) {
    const auto& patch=layout.patches[patch_index];
    if (patch.patch_id != patch_index || patch.view_index >= views.size() ||
        views[patch.view_index].frame_id != patch.frame_id ||
        patch.atlas_x_px < 0 || patch.atlas_y_px < 0 || patch.atlas_width_px <= 0 ||
        patch.atlas_height_px <= 0 ||
        static_cast<std::int64_t>(patch.atlas_x_px)+patch.atlas_width_px>
            layout.atlas_width ||
        static_cast<std::int64_t>(patch.atlas_y_px)+patch.atlas_height_px>
            layout.atlas_height) {
      result.error = "invalid texture atlas patch"; return result;
    }
    const auto found=image_by_frame.find(patch.frame_id);
    if(found==image_by_frame.end()||images[found->second].width!=views[patch.view_index].width||
       images[found->second].height!=views[patch.view_index].height) {
      result.error = "missing or mismatched texture source image"; return result;
    }
  }

  result.input_valid=true;result.width=layout.atlas_width;result.height=layout.atlas_height;
  result.rgba8.assign(atlas_pixels*4,0);
  std::vector<std::uint32_t> owner(atlas_pixels,0);
  std::vector<std::uint16_t> distance(atlas_pixels,0);
  std::size_t output_triangle=0;
  bool stop=false;
  const std::size_t no_patch=std::numeric_limits<std::size_t>::max();
  for(std::size_t source_triangle=0;source_triangle<labels.size()&&!stop;++source_triangle) {
    if(!labels[source_triangle].assigned)continue;
    const std::size_t patch_id=layout.source_triangle_patch[source_triangle];
    if(patch_id==no_patch||patch_id>=layout.patches.size()) {
      result.error="texture triangle patch is invalid";result.rgba8.clear();return result;
    }
    const auto& patch=layout.patches[patch_id];
    const auto& image=images[image_by_frame.at(patch.frame_id)];
    std::array<double,3> ax{},ay{},sx{},sy{};
    for(std::size_t corner=0;corner<3;++corner) {
      const std::uint32_t vertex=layout.mesh.triangle_indices[output_triangle*3+corner];
      const auto& uv=layout.mesh.texcoords[vertex];
      ax[corner]=uv.x*(layout.atlas_width-1);ay[corner]=uv.y*(layout.atlas_height-1);
      sx[corner]=labels[source_triangle].normalized_uv[corner].x*(image.width-1);
      sy[corner]=labels[source_triangle].normalized_uv[corner].y*(image.height-1);
    }
    ++output_triangle;
    const double denominator=(ay[1]-ay[2])*(ax[0]-ax[2])+
                             (ax[2]-ax[1])*(ay[0]-ay[2]);
    if(!std::isfinite(denominator)||std::abs(denominator)<1e-12)continue;
    const int min_x=std::max(patch.atlas_x_px,static_cast<int>(std::floor(
        std::min({ax[0],ax[1],ax[2]}))));
    const int max_x=std::min(patch.atlas_x_px+patch.atlas_width_px-1,
        static_cast<int>(std::ceil(std::max({ax[0],ax[1],ax[2]}))));
    const int min_y=std::max(patch.atlas_y_px,static_cast<int>(std::floor(
        std::min({ay[0],ay[1],ay[2]}))));
    const int max_y=std::min(patch.atlas_y_px+patch.atlas_height_px-1,
        static_cast<int>(std::ceil(std::max({ay[0],ay[1],ay[2]}))));
    for(int y=min_y;y<=max_y&&!stop;++y)for(int x=min_x;x<=max_x;++x) {
      if(result.pixel_tests>=config.max_pixel_tests){result.truncated=true;stop=true;break;}
      ++result.pixel_tests;
      const double px=x+0.5,py=y+0.5;
      const double w0=((ay[1]-ay[2])*(px-ax[2])+(ax[2]-ax[1])*(py-ay[2]))/denominator;
      const double w1=((ay[2]-ay[0])*(px-ax[2])+(ax[0]-ax[2])*(py-ay[2]))/denominator;
      const double w2=1.0-w0-w1;
      if(w0<-1e-7||w1<-1e-7||w2<-1e-7)continue;
      const std::size_t pixel=static_cast<std::size_t>(y)*layout.atlas_width+x;
      if(owner[pixel]!=0)++result.overlap_pixels;else ++result.rasterized_pixels;
      owner[pixel]=static_cast<std::uint32_t>(patch_id+1);
      auto color=sample(image,w0*sx[0]+w1*sx[1]+w2*sx[2],
                              w0*sy[0]+w1*sy[1]+w2*sy[2]);
      const auto& correction=corrections[patch.view_index];
      for(std::size_t channel=0;channel<3;++channel)
        color[channel]=clampByte(static_cast<int>(std::llround(
            color[channel]*correction.rgb_gain[channel])));
      for(int channel=0;channel<4;++channel)result.rgba8[pixel*4+channel]=color[channel];
    }
  }
  if(result.truncated)return result;

  std::vector<std::size_t> queue;
  queue.reserve(result.rasterized_pixels+std::min(config.max_padding_pixels,atlas_pixels));
  for(std::size_t pixel=0;pixel<atlas_pixels;++pixel)if(owner[pixel])queue.push_back(pixel);
  for(std::size_t head=0;head<queue.size();++head) {
    const std::size_t pixel=queue[head];
    if(distance[pixel]>=layout.padding_px)continue;
    const std::size_t patch_id=owner[pixel]-1;
    const auto& patch=layout.patches[patch_id];
    const int x=static_cast<int>(pixel%layout.atlas_width);
    const int y=static_cast<int>(pixel/layout.atlas_width);
    const std::array<std::array<int,2>,4> offsets{{{{-1,0}},{{1,0}},{{0,-1}},{{0,1}}}};
    for(const auto& offset:offsets) {
      const int nx=x+offset[0],ny=y+offset[1];
      if(nx<patch.atlas_x_px||ny<patch.atlas_y_px||
         nx>=patch.atlas_x_px+patch.atlas_width_px||
         ny>=patch.atlas_y_px+patch.atlas_height_px)continue;
      const std::size_t target=static_cast<std::size_t>(ny)*layout.atlas_width+nx;
      if(owner[target])continue;
      if(result.padding_pixels>=config.max_padding_pixels) {
        result.truncated=true;return result;
      }
      owner[target]=owner[pixel];distance[target]=static_cast<std::uint16_t>(distance[pixel]+1);
      std::copy_n(result.rgba8.begin()+static_cast<std::ptrdiff_t>(pixel*4),4,
                  result.rgba8.begin()+static_cast<std::ptrdiff_t>(target*4));
      ++result.padding_pixels;queue.push_back(target);
    }
  }
  return result;
}

TextureBakingResult bakeTextureAtlas(
    const TextureAtlasLayoutResult& layout,
    const std::vector<TextureViewReference>& views,
    const std::vector<TriangleTextureView>& labels,
    const std::vector<TextureSourceImageView>& images,
    TextureBakingConfig config) {
  std::vector<TextureViewColorCorrection> identity(views.size());
  for(std::size_t i=0;i<views.size();++i) {
    identity[i].view_index=i;identity[i].frame_id=views[i].frame_id;
  }
  return bakeTextureAtlasWithCorrections(layout,views,labels,images,identity,config);
}

}  // namespace arkernel
