#include "arkernel/homography_ransac.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <stdexcept>

namespace arkernel {
namespace {
using M3=std::array<double,9>;

M3 multiply(const M3& a,const M3& b){
  M3 c{};for(int r=0;r<3;++r)for(int j=0;j<3;++j)for(int k=0;k<3;++k)c[r*3+j]+=a[r*3+k]*b[k*3+j];return c;
}
bool inverse(const M3& m,M3& out){
  const double det=m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]);
  if(!std::isfinite(det)||std::abs(det)<1e-12)return false;
  out={m[4]*m[8]-m[5]*m[7],m[2]*m[7]-m[1]*m[8],m[1]*m[5]-m[2]*m[4],
       m[5]*m[6]-m[3]*m[8],m[0]*m[8]-m[2]*m[6],m[2]*m[3]-m[0]*m[5],
       m[3]*m[7]-m[4]*m[6],m[1]*m[6]-m[0]*m[7],m[0]*m[4]-m[1]*m[3]};
  for(double& value:out)value/=det;
  return true;
}
bool project(const M3& h,const Vec2& p,Vec2& result){
  const double denominator=h[6]*p.x+h[7]*p.y+h[8];
  if(!std::isfinite(denominator)||std::abs(denominator)<1e-12)return false;
  result={(h[0]*p.x+h[1]*p.y+h[2])/denominator,
          (h[3]*p.x+h[4]*p.y+h[5])/denominator};
  return std::isfinite(result.x)&&std::isfinite(result.y);
}

struct Normalization{double mx{},my{},scale{1};M3 transform{},inverse_transform{};};
Normalization normalize(const std::vector<NormalizedCorrespondence>& points,
                        const std::vector<std::size_t>& indices,bool current){
  Normalization n;
  for(auto i:indices){const auto& p=current?points[i].current:points[i].previous;n.mx+=p.x;n.my+=p.y;}
  n.mx/=indices.size();n.my/=indices.size();double distance=0;
  for(auto i:indices){const auto& p=current?points[i].current:points[i].previous;distance+=std::hypot(p.x-n.mx,p.y-n.my);}
  distance/=indices.size();if(distance>1e-12)n.scale=std::sqrt(2.0)/distance;
  n.transform={n.scale,0,-n.scale*n.mx,0,n.scale,-n.scale*n.my,0,0,1};
  n.inverse_transform={1/n.scale,0,n.mx,0,1/n.scale,n.my,0,0,1};return n;
}

bool solve8(std::array<double,64> matrix,std::array<double,8> rhs,std::array<double,8>& solution){
  for(int column=0;column<8;++column){
    int pivot=column;double largest=std::abs(matrix[column*8+column]);
    for(int row=column+1;row<8;++row)if(std::abs(matrix[row*8+column])>largest){largest=std::abs(matrix[row*8+column]);pivot=row;}
    if(largest<1e-10||!std::isfinite(largest))return false;
    if(pivot!=column){for(int j=column;j<8;++j)std::swap(matrix[column*8+j],matrix[pivot*8+j]);std::swap(rhs[column],rhs[pivot]);}
    const double diagonal=matrix[column*8+column];
    for(int row=column+1;row<8;++row){const double factor=matrix[row*8+column]/diagonal;
      for(int j=column;j<8;++j)matrix[row*8+j]-=factor*matrix[column*8+j];
      rhs[row]-=factor*rhs[column];
    }
  }
  for(int row=7;row>=0;--row){double value=rhs[row];for(int j=row+1;j<8;++j)value-=matrix[row*8+j]*solution[j];solution[row]=value/matrix[row*8+row];}
  return true;
}

bool fit(const std::vector<NormalizedCorrespondence>& points,
         const std::vector<std::size_t>& indices,M3& homography){
  if(indices.size()<4)return false;
  const auto first=normalize(points,indices,false);
  const auto second=normalize(points,indices,true);
  std::array<double,64> normal{};std::array<double,8> rhs{};
  for(auto index:indices){
    const auto& pair=points[index];const double x=first.scale*(pair.previous.x-first.mx),y=first.scale*(pair.previous.y-first.my);
    const double u=second.scale*(pair.current.x-second.mx),v=second.scale*(pair.current.y-second.my);
    const std::array<double,8> row_u{x,y,1,0,0,0,-u*x,-u*y};
    const std::array<double,8> row_v{0,0,0,x,y,1,-v*x,-v*y};
    for(const auto* row:{&row_u,&row_v})for(int r=0;r<8;++r){rhs[r]+=(*row)[r]*(row==&row_u?u:v);for(int c=0;c<8;++c)normal[r*8+c]+=(*row)[r]*(*row)[c];}
  }
  std::array<double,8> h{};if(!solve8(normal,rhs,h))return false;
  const M3 normalized_h{h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7],1};
  homography=multiply(second.inverse_transform,multiply(normalized_h,first.transform));
  if(std::abs(homography[8])>1e-12)for(double& value:homography)value/=homography[8];
  M3 inverse_h{};return inverse(homography,inverse_h);
}

double transferError(const M3& h,const M3& inverse_h,const NormalizedCorrespondence& pair){
  Vec2 forward{},backward{};if(!project(h,pair.previous,forward)||!project(inverse_h,pair.current,backward))return std::numeric_limits<double>::infinity();
  const double fx=forward.x-pair.current.x,fy=forward.y-pair.current.y;
  const double bx=backward.x-pair.previous.x,by=backward.y-pair.previous.y;
  return 0.5*(fx*fx+fy*fy+bx*bx+by*by);
}
std::uint32_t nextRandom(std::uint32_t& state){state^=state<<13;state^=state>>17;state^=state<<5;return state;}
}  // namespace

HomographyRansac::HomographyRansac(HomographyRansacConfig config):config_(config){
  if(config_.max_iterations<1||config_.transfer_threshold<=0||config_.min_inliers<4)
    throw std::invalid_argument("Invalid homography RANSAC config");
}

double HomographyRansac::symmetricTransferError(const M3& h,const NormalizedCorrespondence& pair){
  M3 inverse_h{};return inverse(h,inverse_h)?transferError(h,inverse_h,pair):std::numeric_limits<double>::infinity();
}

HomographyVerification HomographyRansac::verify(const std::vector<NormalizedCorrespondence>& points) const{
  HomographyVerification best;best.inliers.assign(points.size(),0);if(points.size()<4)return best;
  for(const auto& p:points)if(!std::isfinite(p.previous.x)||!std::isfinite(p.previous.y)||!std::isfinite(p.current.x)||!std::isfinite(p.current.y))return best;
  const double threshold2=config_.transfer_threshold*config_.transfer_threshold;
  std::vector<std::size_t> population(points.size());std::iota(population.begin(),population.end(),0);
  std::uint32_t state=config_.random_seed?config_.random_seed:1u;double best_error=std::numeric_limits<double>::infinity();
  for(int iteration=0;iteration<config_.max_iterations;++iteration){
    auto candidates=population;std::vector<std::size_t> sample;sample.reserve(4);
    for(std::size_t i=0;i<4;++i){const std::size_t selected=i+nextRandom(state)%(candidates.size()-i);std::swap(candidates[i],candidates[selected]);sample.push_back(candidates[i]);}
    M3 h{},inverse_h{};if(!fit(points,sample,h)||!inverse(h,inverse_h))continue;
    std::vector<std::uint8_t> mask(points.size(),0);std::size_t count=0;double error_sum=0;
    for(std::size_t i=0;i<points.size();++i){const double error=transferError(h,inverse_h,points[i]);if(error<=threshold2){mask[i]=1;++count;error_sum+=error;}}
    if(count>best.inlier_count||(count==best.inlier_count&&error_sum<best_error)){
      best.matrix=h;best.inliers=std::move(mask);best.inlier_count=count;best_error=error_sum;
    }
  }
  if(best.inlier_count<config_.min_inliers)return best;
  std::vector<std::size_t> inlier_indices;for(std::size_t i=0;i<best.inliers.size();++i)if(best.inliers[i])inlier_indices.push_back(i);
  M3 refined{},inverse_refined{};if(!fit(points,inlier_indices,refined)||!inverse(refined,inverse_refined))return best;
  best.matrix=refined;best.inlier_count=0;std::vector<double> errors;
  for(std::size_t i=0;i<points.size();++i){const double error=transferError(refined,inverse_refined,points[i]);best.inliers[i]=error<=threshold2;if(best.inliers[i]){++best.inlier_count;errors.push_back(error);}}
  if(best.inlier_count<config_.min_inliers)return best;
  std::sort(errors.begin(),errors.end());best.median_symmetric_transfer_error=errors[errors.size()/2];best.valid=true;return best;
}

}  // namespace arkernel
