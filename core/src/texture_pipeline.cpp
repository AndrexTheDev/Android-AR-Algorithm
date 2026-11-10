#include "arkernel/texture_pipeline.h"

#include <utility>

namespace arkernel {
namespace {

void fail(TexturePipelineResult& result,TexturePipelineStage stage,bool input_valid,
          bool truncated,const std::string& error) {
  result.stage=stage;result.input_valid=input_valid;result.truncated=truncated;
  result.error=error.empty()?"texture pipeline stage failed":error;
  result.mesh={};result.rgba8.clear();result.global_seam={};
}

}  // namespace

TexturePipelineResult buildTextureAtlas(
    const ReconstructionMesh& source_mesh,
    const std::vector<TextureViewCandidate>& candidates,
    const std::vector<TextureSourceImageView>& images,
    TexturePipelineConfig config) {
  TexturePipelineResult result;
  result.stage=TexturePipelineStage::kViewSelection;
  auto selection=selectTextureViews(source_mesh,candidates,config.view_selection);
  result.telemetry.candidate_views=selection.candidates;
  result.telemetry.projection_tests=selection.projection_tests;
  result.telemetry.assigned_triangles=selection.assigned_triangles;
  result.telemetry.unassigned_triangles=selection.unassigned_triangles;
  if(!selection.input_valid||selection.truncated||selection.alternatives_truncated||
     !selection.error.empty()) {
    fail(result,TexturePipelineStage::kViewSelection,selection.input_valid,
         selection.truncated||selection.alternatives_truncated,selection.error.empty()?
           "texture view selection did not complete":selection.error);
    return result;
  }
  result.input_valid=true;result.views=selection.views;

  result.stage=TexturePipelineStage::kLabelOptimization;
  auto labels=optimizeTextureViewLabels(source_mesh,selection,config.label_optimization);
  result.telemetry.label_iterations=labels.iterations;
  result.telemetry.seams_before_labeling=labels.seam_edges_before;
  result.telemetry.seams_after_labeling=labels.seam_edges_after;
  if(!labels.input_valid||labels.truncated||labels.source_alternatives_truncated||
     !labels.error.empty()) {
    fail(result,TexturePipelineStage::kLabelOptimization,labels.input_valid,
         labels.truncated||labels.source_alternatives_truncated,labels.error.empty()?
           "texture label optimization did not complete":labels.error);
    return result;
  }

  result.stage=TexturePipelineStage::kAtlasLayout;
  auto layout=layoutTextureAtlas(source_mesh,result.views,labels.triangles,
                                 config.atlas_layout);
  result.telemetry.atlas_patches=layout.patches.size();
  result.telemetry.atlas_vertices=layout.mesh.positions_m.size();
  result.telemetry.atlas_triangles=layout.mesh.triangle_indices.size()/3;
  if(!layout.input_valid||layout.truncated||!layout.error.empty()) {
    fail(result,TexturePipelineStage::kAtlasLayout,layout.input_valid,layout.truncated,
         layout.error.empty()?"texture atlas layout did not complete":layout.error);
    return result;
  }

  result.stage=TexturePipelineStage::kPhotometry;
  if(config.enable_photometric_correction) {
    auto photometry=estimateTexturePhotometry(source_mesh,result.views,labels.triangles,
                                               images,config.photometry);
    result.telemetry.photometry_equations=photometry.equations;
    result.telemetry.photometry_constrained_views=photometry.constrained_views;
    if(!photometry.input_valid||photometry.truncated||!photometry.error.empty()) {
      fail(result,TexturePipelineStage::kPhotometry,photometry.input_valid,
           photometry.truncated,photometry.error.empty()?
             "texture photometry did not complete":photometry.error);
      return result;
    }
    result.corrections=std::move(photometry.corrections);
  } else {
    result.corrections.resize(result.views.size());
    for(std::size_t i=0;i<result.views.size();++i) {
      result.corrections[i].view_index=i;
      result.corrections[i].frame_id=result.views[i].frame_id;
    }
  }

  result.stage=TexturePipelineStage::kBaking;
  auto baking=bakeTextureAtlasWithCorrections(layout,result.views,labels.triangles,
                                               images,result.corrections,config.baking);
  result.telemetry.rasterized_pixels=baking.rasterized_pixels;
  result.telemetry.padding_pixels=baking.padding_pixels;
  result.telemetry.raster_pixel_tests=baking.pixel_tests;
  if(!baking.input_valid||baking.truncated||!baking.error.empty()) {
    fail(result,TexturePipelineStage::kBaking,baking.input_valid,baking.truncated,
         baking.error.empty()?"texture baking did not complete":baking.error);
    return result;
  }

  if(config.enable_seam_blending) {
    result.stage=TexturePipelineStage::kSeamBlending;
    auto blending=blendTextureAtlasSeams(source_mesh,layout,labels.triangles,baking,
                                         config.seam_blending);
    result.telemetry.blended_seams=blending.seam_edges;
    result.telemetry.blended_pixel_updates=blending.pixel_updates;
    if(!blending.input_valid||blending.truncated||!blending.error.empty()) {
      fail(result,TexturePipelineStage::kSeamBlending,blending.input_valid,
           blending.truncated,blending.error.empty()?
             "texture seam blending did not complete":blending.error);
      return result;
    }
    result.rgba8=std::move(blending.rgba8);
  } else {
    result.rgba8=std::move(baking.rgba8);
  }
  if(config.enable_global_seam_optimization) {
    result.stage=TexturePipelineStage::kSeamBlending;
    auto global=optimizeTextureAtlasSeamsGlobally(source_mesh,layout,
        labels.triangles,result.rgba8,layout.atlas_width,layout.atlas_height,
        config.global_seam);
    result.global_seam=global.telemetry;
    result.global_seam.enabled=true;
    result.telemetry.global_seam_pairs=global.telemetry.seam_pairs;
    result.telemetry.global_seam_samples=global.telemetry.sampled_pairs;
    result.telemetry.global_seam_iterations=global.telemetry.iterations;
    result.telemetry.global_seam_pixel_updates=global.telemetry.pixel_updates;
    result.telemetry.blended_seams+=global.telemetry.seam_pairs;
    result.telemetry.blended_pixel_updates+=global.telemetry.pixel_updates;
    if(!global.telemetry.input_valid||global.telemetry.truncated||
       !global.telemetry.error.empty()) {
      fail(result,TexturePipelineStage::kSeamBlending,
           global.telemetry.input_valid,global.telemetry.truncated,
           global.telemetry.error.empty()?
             "global texture seam optimization did not complete":
             global.telemetry.error);
      return result;
    }
    result.rgba8=std::move(global.rgba8);
  }
  result.mesh=std::move(layout.mesh);
  result.atlas_width=layout.atlas_width;result.atlas_height=layout.atlas_height;
  result.mesh.base_color_texture={};
  result.stage=TexturePipelineStage::kComplete;result.input_valid=true;
  result.truncated=false;result.error.clear();
  return result;
}

}  // namespace arkernel
