#include "arkernel/epipolar_ransac.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <stdexcept>

namespace arkernel {
namespace {
using Matrix3 = std::array<double,9>;

Matrix3 multiply(const Matrix3& a, const Matrix3& b) {
  Matrix3 c{};
  for (int r=0; r<3; ++r) for (int col=0; col<3; ++col)
    for (int k=0; k<3; ++k) c[r*3+col] += a[r*3+k]*b[k*3+col];
  return c;
}
Matrix3 transpose(const Matrix3& a) {
  return {a[0],a[3],a[6], a[1],a[4],a[7], a[2],a[5],a[8]};
}

struct PointNormalization { double mean_x{}, mean_y{}, scale{1.0}; Matrix3 matrix{}; };
PointNormalization normalization(const std::vector<NormalizedCorrespondence>& points,
                                 const std::vector<std::size_t>& indices, bool current) {
  PointNormalization n;
  for (auto i : indices) {
    const auto& p = current ? points[i].current : points[i].previous;
    n.mean_x += p.x; n.mean_y += p.y;
  }
  n.mean_x /= indices.size(); n.mean_y /= indices.size();
  double mean_distance = 0.0;
  for (auto i : indices) {
    const auto& p = current ? points[i].current : points[i].previous;
    mean_distance += std::hypot(p.x-n.mean_x, p.y-n.mean_y);
  }
  mean_distance /= indices.size();
  if (mean_distance > 1e-12) n.scale = std::sqrt(2.0)/mean_distance;
  n.matrix = {n.scale,0,-n.scale*n.mean_x, 0,n.scale,-n.scale*n.mean_y, 0,0,1};
  return n;
}

bool smallestEigenvector(const std::array<double,81>& input, std::array<double,9>& vector) {
  auto a = input;
  std::array<double,81> eigenvectors{};
  for (int i=0; i<9; ++i) eigenvectors[i*9+i] = 1.0;
  for (int sweep=0; sweep<120; ++sweep) {
    int p=0, q=1; double largest=0.0;
    for (int r=0; r<9; ++r) for (int c=r+1; c<9; ++c) {
      const double value = std::abs(a[r*9+c]);
      if (value > largest) { largest=value; p=r; q=c; }
    }
    if (largest < 1e-12) break;
    const double angle = 0.5*std::atan2(2.0*a[p*9+q], a[q*9+q]-a[p*9+p]);
    const double cs=std::cos(angle), sn=std::sin(angle);
    for (int k=0; k<9; ++k) {
      const double apk=a[p*9+k], aqk=a[q*9+k];
      a[p*9+k]=cs*apk-sn*aqk; a[q*9+k]=sn*apk+cs*aqk;
    }
    for (int k=0; k<9; ++k) {
      const double akp=a[k*9+p], akq=a[k*9+q];
      a[k*9+p]=cs*akp-sn*akq; a[k*9+q]=sn*akp+cs*akq;
      const double vkp=eigenvectors[k*9+p], vkq=eigenvectors[k*9+q];
      eigenvectors[k*9+p]=cs*vkp-sn*vkq;
      eigenvectors[k*9+q]=sn*vkp+cs*vkq;
    }
  }
  std::array<double,9> eigenvalues{};
  for (int i=0; i<9; ++i) eigenvalues[i]=a[i*9+i];
  auto ordered=eigenvalues;
  std::sort(ordered.begin(),ordered.end());
  // More than one near-null direction means the sample cannot uniquely define
  // an epipolar model (duplicates, collinear points, or insufficient spread).
  if (!std::isfinite(ordered[8]) || ordered[8] <= 1e-12 ||
      ordered[1] <= ordered[8]*1e-10) return false;
  int smallest=0;
  for (int i=1; i<9; ++i) if (eigenvalues[i] < eigenvalues[smallest]) smallest=i;
  double norm=0.0;
  for (int i=0; i<9; ++i) { vector[i]=eigenvectors[i*9+smallest]; norm+=vector[i]*vector[i]; }
  norm=std::sqrt(norm);
  if (!std::isfinite(norm) || norm < 1e-12) return false;
  for (double& value : vector) value /= norm;
  return true;
}

bool fitModel(const std::vector<NormalizedCorrespondence>& points,
              const std::vector<std::size_t>& indices, Matrix3& output) {
  if (indices.size() < 8) return false;
  const auto first = normalization(points,indices,false);
  const auto second = normalization(points,indices,true);
  std::array<double,81> ata{};
  for (auto index : indices) {
    const auto& pair=points[index];
    const double x=first.scale*(pair.previous.x-first.mean_x);
    const double y=first.scale*(pair.previous.y-first.mean_y);
    const double u=second.scale*(pair.current.x-second.mean_x);
    const double v=second.scale*(pair.current.y-second.mean_y);
    const std::array<double,9> row{u*x,u*y,u, v*x,v*y,v, x,y,1.0};
    for (int r=0; r<9; ++r) for (int c=r; c<9; ++c) {
      ata[r*9+c] += row[r]*row[c];
      if (r != c) ata[c*9+r] = ata[r*9+c];
    }
  }
  Matrix3 normalized_model{};
  if (!smallestEigenvector(ata,normalized_model)) return false;
  output = multiply(transpose(second.matrix), multiply(normalized_model,first.matrix));
  double norm=0.0; for (double value:output) norm+=value*value;
  norm=std::sqrt(norm);
  if (!std::isfinite(norm) || norm < 1e-12) return false;
  for (double& value:output) value/=norm;
  return true;
}

std::uint32_t randomNext(std::uint32_t& state) {
  state ^= state << 13; state ^= state >> 17; state ^= state << 5; return state;
}
}  // namespace

EpipolarRansac::EpipolarRansac(EpipolarRansacConfig config) : config_(config) {
  if (config_.max_iterations < 1 || config_.sampson_threshold <= 0.0 ||
      config_.min_inliers < 8) throw std::invalid_argument("Invalid epipolar RANSAC config");
}

double EpipolarRansac::sampsonError(const Matrix3& e,
                                    const NormalizedCorrespondence& c) {
  const double ex0=e[0]*c.previous.x+e[1]*c.previous.y+e[2];
  const double ex1=e[3]*c.previous.x+e[4]*c.previous.y+e[5];
  const double ex2=e[6]*c.previous.x+e[7]*c.previous.y+e[8];
  const double etx0=e[0]*c.current.x+e[3]*c.current.y+e[6];
  const double etx1=e[1]*c.current.x+e[4]*c.current.y+e[7];
  const double residual=c.current.x*ex0+c.current.y*ex1+ex2;
  const double denominator=ex0*ex0+ex1*ex1+etx0*etx0+etx1*etx1;
  return denominator > 1e-18 ? residual*residual/denominator
                             : std::numeric_limits<double>::infinity();
}

EpipolarVerification EpipolarRansac::verify(
    const std::vector<NormalizedCorrespondence>& correspondences) const {
  EpipolarVerification best;
  best.inliers.assign(correspondences.size(),0);
  if (correspondences.size() < 8) return best;
  for (const auto& pair : correspondences)
    if (!std::isfinite(pair.previous.x) || !std::isfinite(pair.previous.y) ||
        !std::isfinite(pair.current.x) || !std::isfinite(pair.current.y)) return best;
  const double threshold_squared=config_.sampson_threshold*config_.sampson_threshold;
  std::uint32_t random_state=config_.random_seed ? config_.random_seed : 1u;
  std::vector<std::size_t> population(correspondences.size());
  std::iota(population.begin(),population.end(),0);

  for (int iteration=0; iteration<config_.max_iterations; ++iteration) {
    auto candidates=population;
    std::vector<std::size_t> sample_indices;
    sample_indices.reserve(8);
    for (std::size_t i=0; i<8; ++i) {
      const std::size_t selected=i+randomNext(random_state)%(candidates.size()-i);
      std::swap(candidates[i],candidates[selected]); sample_indices.push_back(candidates[i]);
    }
    Matrix3 model{};
    if (!fitModel(correspondences,sample_indices,model)) continue;
    std::vector<std::uint8_t> mask(correspondences.size(),0);
    std::size_t count=0; double error_sum=0.0;
    for (std::size_t i=0; i<correspondences.size(); ++i) {
      const double error=sampsonError(model,correspondences[i]);
      if (error <= threshold_squared) { mask[i]=1; ++count; error_sum+=error; }
    }
    const double best_sum = best.median_sampson_error*best.inlier_count;
    if (count > best.inlier_count || (count == best.inlier_count && error_sum < best_sum)) {
      best.matrix=model; best.inliers=std::move(mask); best.inlier_count=count;
      best.median_sampson_error=count ? error_sum/count : std::numeric_limits<double>::infinity();
    }
  }
  if (best.inlier_count < config_.min_inliers) return best;
  std::vector<std::size_t> inlier_indices;
  for (std::size_t i=0; i<best.inliers.size(); ++i) if (best.inliers[i]) inlier_indices.push_back(i);
  Matrix3 refined{};
  if (!fitModel(correspondences,inlier_indices,refined)) return best;
  best.matrix=refined;
  best.inlier_count=0;
  std::vector<double> errors;
  for (std::size_t i=0; i<correspondences.size(); ++i) {
    const double error=sampsonError(refined,correspondences[i]);
    best.inliers[i]=error <= threshold_squared;
    if (best.inliers[i]) { ++best.inlier_count; errors.push_back(error); }
  }
  if (best.inlier_count < config_.min_inliers) return best;
  std::sort(errors.begin(),errors.end());
  best.median_sampson_error=errors[errors.size()/2];
  best.valid=true;
  return best;
}

}  // namespace arkernel
