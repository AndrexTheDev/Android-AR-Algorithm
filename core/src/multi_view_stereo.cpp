#include "arkernel/multi_view_stereo.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel { namespace {
bool finitePose(const Pose&p){const auto&q=p.rotation;const double n=std::sqrt(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z);
 return std::isfinite(n)&&n>1e-9&&std::isfinite(p.translation.x)&&std::isfinite(p.translation.y)&&std::isfinite(p.translation.z);}
Pose inverse(const Pose&p){const auto r=p.rotation.inverse();return {r,r.rotate(p.translation*-1.0)};}
double bilinear(const GrayImageView& image,double x,double y){const int x0=static_cast<int>(std::floor(x)),y0=static_cast<int>(std::floor(y));
 const double dx=x-x0,dy=y-y0;return (1-dx)*(1-dy)*image.at(x0,y0)+dx*(1-dy)*image.at(x0+1,y0)+(1-dx)*dy*image.at(x0,y0+1)+dx*dy*image.at(x0+1,y0+1);}
bool inside(const GrayImageView& image,double x,double y){return x>=0&&y>=0&&x<image.width-1&&y<image.height-1;}
std::optional<double> patchZncc(const MultiViewStereoFrame& reference,const MultiViewStereoFrame& source,
 int center_x,int center_y,double depth,int radius){const Pose source_from_world=inverse(source.world_from_camera);
 double sum_a=0,sum_b=0,sum_aa=0,sum_bb=0,sum_ab=0;std::size_t count=0;
 for(int oy=-radius;oy<=radius;++oy)for(int ox=-radius;ox<=radius;++ox){const Vec2 ref_pixel{static_cast<double>(center_x+ox),static_cast<double>(center_y+oy)};
   const auto ray=reference.camera->unproject(ref_pixel);if(!ray||ray->z<=1e-9)return std::nullopt;
   const Vec3 ref_point=*ray*(depth/ray->z);const Vec3 world=reference.world_from_camera.rotation.rotate(ref_point)+reference.world_from_camera.translation;
   const Vec3 source_point=source_from_world.rotation.rotate(world)+source_from_world.translation;
   const auto projected=source.camera->project(source_point);if(!projected||!inside(source.image,projected->x,projected->y))return std::nullopt;
   const double a=reference.image.at(center_x+ox,center_y+oy),b=bilinear(source.image,projected->x,projected->y);
   sum_a+=a;sum_b+=b;sum_aa+=a*a;sum_bb+=b*b;sum_ab+=a*b;++count;}
 const double inverse_count=1.0/static_cast<double>(count);const double covariance=sum_ab-sum_a*sum_b*inverse_count;
 const double variance_a=sum_aa-sum_a*sum_a*inverse_count,variance_b=sum_bb-sum_b*sum_b*inverse_count;
 const double denominator=std::sqrt(std::max(0.0,variance_a*variance_b));
 if(!std::isfinite(denominator)||denominator<1e-6)return std::nullopt;
 return std::max(-1.0,std::min(1.0,covariance/denominator));}
} // namespace

MultiViewStereoDepth estimateMultiViewDepth(const MultiViewStereoFrame& reference,
 const std::vector<MultiViewStereoFrame>& sources,MultiViewStereoConfig config){
 if(config.min_depth_m<=0||config.max_depth_m<=config.min_depth_m||config.depth_hypotheses<3||
    config.depth_hypotheses>512||config.pixel_stride<=0||config.patch_radius<1||config.patch_radius>5||
    config.min_supporting_views==0||config.max_source_views==0||config.min_supporting_views>config.max_source_views||
    config.max_output_pixels==0||config.min_baseline_m<=0||config.max_baseline_m<=config.min_baseline_m||
    config.min_zncc<=-1||config.min_zncc>=1||config.min_winner_margin<=0||config.min_winner_margin>=2)
   throw std::invalid_argument("Invalid multi-view stereo configuration");
 MultiViewStereoDepth result;
 if(!reference.image.valid()||reference.camera==nullptr||!finitePose(reference.world_from_camera)||
    sources.empty()||sources.size()>config.max_source_views)return result;
 const std::size_t pixels=static_cast<std::size_t>(reference.image.width)*reference.image.height;
 if(pixels>config.max_output_pixels)return result;
 std::vector<const MultiViewStereoFrame*> usable;usable.reserve(sources.size());
 for(const auto& source:sources){if(!source.image.valid()||source.camera==nullptr||!finitePose(source.world_from_camera))return result;
   const double baseline=(source.world_from_camera.translation-reference.world_from_camera.translation).norm();
   if(baseline>=config.min_baseline_m&&baseline<=config.max_baseline_m)usable.push_back(&source);}
 if(usable.size()<config.min_supporting_views)return result;
 result.width=reference.image.width;result.height=reference.image.height;
 const float nan=std::numeric_limits<float>::quiet_NaN();result.depth_m.assign(pixels,nan);
 result.confidence.assign(pixels,0);result.uncertainty_m.assign(pixels,nan);
 const int border=config.patch_radius+1;const double inverse_near=1.0/config.min_depth_m,inverse_far=1.0/config.max_depth_m;
 const double inverse_step=(inverse_near-inverse_far)/static_cast<double>(config.depth_hypotheses-1);
 for(int y=border;y<reference.image.height-border;y+=config.pixel_stride)for(int x=border;x<reference.image.width-border;x+=config.pixel_stride){
   std::vector<double> scores(config.depth_hypotheses,-2.0);std::vector<std::size_t> supports(config.depth_hypotheses);
   for(std::size_t hypothesis=0;hypothesis<config.depth_hypotheses;++hypothesis){const double depth=1.0/(inverse_near-inverse_step*hypothesis);
     double score=0;std::size_t support=0;for(const auto* source:usable){const auto value=patchZncc(reference,*source,x,y,depth,config.patch_radius);
       if(value){score+=*value;++support;}}
     if(support>=config.min_supporting_views){scores[hypothesis]=score/static_cast<double>(support);supports[hypothesis]=support;}}
   const auto best_iterator=std::max_element(scores.begin(),scores.end());const std::size_t best=static_cast<std::size_t>(best_iterator-scores.begin());
   const double best_score=*best_iterator;if(best_score<config.min_zncc)continue;double second=-2;
   for(std::size_t i=0;i<scores.size();++i)if((i+1<best||i>best+1)&&scores[i]>second)second=scores[i];
   const double margin=best_score-second;if(second>-1.5&&margin<config.min_winner_margin)continue;
   const double inverse_depth=inverse_near-inverse_step*best;const double depth=1.0/inverse_depth;
   const double neighboring_inverse=best+1<config.depth_hypotheses?inverse_depth-inverse_step:inverse_depth+inverse_step;
   const double sampling_uncertainty=std::abs(depth-1.0/neighboring_inverse)*0.5;
   const double score_confidence=(best_score-config.min_zncc)/(1.0-config.min_zncc);
   const double margin_confidence=second<=-1.5?1.0:std::min(1.0,margin/config.min_winner_margin);
   const double view_confidence=static_cast<double>(supports[best])/usable.size();
   const float confidence=static_cast<float>(std::max(0.0,std::min(1.0,score_confidence*margin_confidence*view_confidence)));
   const std::size_t index=static_cast<std::size_t>(y)*result.width+x;result.depth_m[index]=static_cast<float>(depth);
   result.confidence[index]=confidence;result.uncertainty_m[index]=static_cast<float>(sampling_uncertainty/std::max(0.1F,confidence));++result.valid_pixels;
 }
 result.valid=true;return result;
}
} // namespace arkernel
