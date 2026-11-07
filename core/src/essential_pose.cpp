#include "arkernel/essential_pose.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace arkernel {
namespace {
using M3=std::array<double,9>;

Vec3 column(const M3& m,int c) { return {m[c],m[3+c],m[6+c]}; }
void setColumn(M3& m,int c,const Vec3& v) { m[c]=v.x; m[3+c]=v.y; m[6+c]=v.z; }
double dot(const Vec3& a,const Vec3& b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
Vec3 cross(const Vec3& a,const Vec3& b) {
  return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};
}
Vec3 normalized(const Vec3& v) { const double n=v.norm(); return n>1e-15?v*(1.0/n):Vec3{}; }
M3 transpose(const M3& a) { return {a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8]}; }
M3 multiply(const M3& a,const M3& b) {
  M3 c{}; for(int r=0;r<3;++r)for(int j=0;j<3;++j)for(int k=0;k<3;++k)c[r*3+j]+=a[r*3+k]*b[k*3+j]; return c;
}
Vec3 multiply(const M3& a,const Vec3& v) {
  return {a[0]*v.x+a[1]*v.y+a[2]*v.z,
          a[3]*v.x+a[4]*v.y+a[5]*v.z,
          a[6]*v.x+a[7]*v.y+a[8]*v.z};
}
double determinant(const M3& m) {
  return m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]);
}

// Jacobi eigensolver for symmetric 3x3 matrices. Eigenvectors are columns.
void symmetricEigen(M3 a,M3& vectors,std::array<double,3>& values) {
  vectors={1,0,0,0,1,0,0,0,1};
  for(int sweep=0;sweep<40;++sweep){
    int p=0,q=1; double largest=std::abs(a[1]);
    if(std::abs(a[2])>largest){largest=std::abs(a[2]);p=0;q=2;}
    if(std::abs(a[5])>largest){largest=std::abs(a[5]);p=1;q=2;}
    if(largest<1e-14)break;
    const double angle=0.5*std::atan2(2*a[p*3+q],a[q*3+q]-a[p*3+p]);
    const double c=std::cos(angle),s=std::sin(angle);
    for(int k=0;k<3;++k){double ap=a[p*3+k],aq=a[q*3+k];a[p*3+k]=c*ap-s*aq;a[q*3+k]=s*ap+c*aq;}
    for(int k=0;k<3;++k){
      double ap=a[k*3+p],aq=a[k*3+q];a[k*3+p]=c*ap-s*aq;a[k*3+q]=s*ap+c*aq;
      double vp=vectors[k*3+p],vq=vectors[k*3+q];vectors[k*3+p]=c*vp-s*vq;vectors[k*3+q]=s*vp+c*vq;
    }
  }
  values={a[0],a[4],a[8]};
  std::array<int,3> order{0,1,2};
  std::sort(order.begin(),order.end(),[&](int i,int j){return values[i]>values[j];});
  M3 sorted{}; std::array<double,3> sorted_values{};
  for(int c=0;c<3;++c){setColumn(sorted,c,column(vectors,order[c]));sorted_values[c]=values[order[c]];}
  vectors=sorted;values=sorted_values;
}

bool svd(const M3& a,M3& u,std::array<double,3>& singular,M3& v) {
  const M3 ata=multiply(transpose(a),a);
  std::array<double,3> eigenvalues{}; symmetricEigen(ata,v,eigenvalues);
  for(int i=0;i<3;++i)singular[i]=std::sqrt(std::max(0.0,eigenvalues[i]));
  if(!std::isfinite(singular[0])||singular[0]<1e-12||singular[1]<1e-12)return false;
  Vec3 u0=normalized(multiply(a,column(v,0)));
  Vec3 raw1=multiply(a,column(v,1));
  Vec3 u1=normalized(raw1-u0*dot(u0,raw1));
  if(u0.norm()<0.9||u1.norm()<0.9)return false;
  Vec3 u2=normalized(cross(u0,u1));
  u={};setColumn(u,0,u0);setColumn(u,1,u1);setColumn(u,2,u2);
  // SVD sign conventions are arbitrary; proper U and V simplify pose formulas.
  if(determinant(u)<0)setColumn(u,2,column(u,2)*-1.0);
  if(determinant(v)<0)setColumn(v,2,column(v,2)*-1.0);
  return true;
}

Quaternion quaternionFromMatrix(const M3& m) {
  Quaternion q;
  const double trace=m[0]+m[4]+m[8];
  if(trace>0){const double s=std::sqrt(trace+1.0)*2;q.w=0.25*s;q.x=(m[7]-m[5])/s;q.y=(m[2]-m[6])/s;q.z=(m[3]-m[1])/s;}
  else if(m[0]>m[4]&&m[0]>m[8]){const double s=std::sqrt(1+m[0]-m[4]-m[8])*2;q.w=(m[7]-m[5])/s;q.x=.25*s;q.y=(m[1]+m[3])/s;q.z=(m[2]+m[6])/s;}
  else if(m[4]>m[8]){const double s=std::sqrt(1+m[4]-m[0]-m[8])*2;q.w=(m[2]-m[6])/s;q.x=(m[1]+m[3])/s;q.y=.25*s;q.z=(m[5]+m[7])/s;}
  else{const double s=std::sqrt(1+m[8]-m[0]-m[4])*2;q.w=(m[3]-m[1])/s;q.x=(m[2]+m[6])/s;q.y=(m[5]+m[7])/s;q.z=.25*s;}
  return q.normalized();
}

struct CandidateScore { std::size_t positive{}; std::vector<double> parallax; };
CandidateScore score(const M3& rotation,const Vec3& translation,
                     const std::vector<NormalizedCorrespondence>& points,
                     const std::vector<std::uint8_t>& mask) {
  CandidateScore result;
  const M3 inverse_rotation=transpose(rotation);
  for(std::size_t i=0;i<points.size();++i){
    if(!mask.empty()&&!mask[i])continue;
    const Vec3 ray1=normalized({points[i].previous.x,points[i].previous.y,1});
    const Vec3 ray2=normalized({points[i].current.x,points[i].current.y,1});
    const Vec3 rotated_ray1=multiply(rotation,ray1);
    // Solve [R*r1, -r2] [depth1 depth2]^T = -t in least squares.
    const double a=dot(rotated_ray1,rotated_ray1),b=-dot(rotated_ray1,ray2),d=dot(ray2,ray2);
    const double rhs0=-dot(rotated_ray1,translation),rhs1=dot(ray2,translation);
    const double det=a*d-b*b;if(det<1e-10)continue;
    const double depth1=(rhs0*d-b*rhs1)/det;
    const double depth2=(a*rhs1-b*rhs0)/det;
    if(depth1<=1e-6||depth2<=1e-6)continue;
    ++result.positive;
    const Vec3 ray2_in_first=multiply(inverse_rotation,ray2);
    const double cosine=std::max(-1.0,std::min(1.0,dot(ray1,ray2_in_first)));
    result.parallax.push_back(std::acos(cosine)*180.0/3.14159265358979323846);
  }
  return result;
}
}  // namespace

EssentialPoseSolver::EssentialPoseSolver(EssentialPoseConfig config):config_(config){
  if(config_.min_positive_depth<1||config_.min_positive_depth_ratio<=0||config_.min_positive_depth_ratio>1||
     config_.min_median_parallax_degrees<0)throw std::invalid_argument("Invalid essential pose config");
}

RelativePoseEstimate EssentialPoseSolver::solve(const M3& input,
    const std::vector<NormalizedCorrespondence>& points,
    const std::vector<std::uint8_t>& mask) const {
  RelativePoseEstimate result;
  if(points.size()<8||(!mask.empty()&&mask.size()!=points.size()))return result;
  for(double value:input)if(!std::isfinite(value))return result;
  for(const auto& point:points)
    if(!std::isfinite(point.previous.x)||!std::isfinite(point.previous.y)||
       !std::isfinite(point.current.x)||!std::isfinite(point.current.y))return result;
  for(auto value:mask)if(value>1)return result;
  M3 u{},v{};std::array<double,3>s{};
  if(!svd(input,u,s,v))return result;
  const double sigma=.5*(s[0]+s[1]);
  const M3 diagonal{sigma,0,0,0,sigma,0,0,0,0};
  result.constrained_essential=multiply(u,multiply(diagonal,transpose(v)));
  const M3 w{0,-1,0,1,0,0,0,0,1};
  const M3 wt=transpose(w);
  std::array<M3,2> rotations{multiply(u,multiply(w,transpose(v))),multiply(u,multiply(wt,transpose(v)))};
  for(auto& r:rotations)if(determinant(r)<0)for(double& value:r)value=-value;
  const Vec3 base_translation=normalized(column(u,2));
  result.evaluated_count=mask.empty()?points.size():static_cast<std::size_t>(std::count(mask.begin(),mask.end(),1));
  CandidateScore best;M3 best_rotation{};Vec3 best_translation{};
  for(const auto& rotation:rotations)for(double sign:{1.0,-1.0}){
    const Vec3 translation=base_translation*sign;
    auto candidate=score(rotation,translation,points,mask);
    if(candidate.positive>best.positive){best=std::move(candidate);best_rotation=rotation;best_translation=translation;}
  }
  result.positive_depth_count=best.positive;
  if(!best.parallax.empty()){std::sort(best.parallax.begin(),best.parallax.end());result.median_parallax_degrees=best.parallax[best.parallax.size()/2];}
  result.rotation_current_from_previous=quaternionFromMatrix(best_rotation);
  result.translation_direction_current=best_translation;
  const double ratio=result.evaluated_count?static_cast<double>(best.positive)/result.evaluated_count:0.0;
  result.valid=best.positive>=config_.min_positive_depth&&ratio>=config_.min_positive_depth_ratio&&
               result.median_parallax_degrees>=config_.min_median_parallax_degrees;
  return result;
}

}  // namespace arkernel
