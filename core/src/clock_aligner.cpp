#include "arkernel/clock_aligner.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <vector>

namespace arkernel {

ClockAligner::ClockAligner(std::size_t capacity, std::size_t min_observations)
    : capacity_(capacity), min_observations_(min_observations) {
  if (capacity_ < 2 || capacity_ > 4096 || min_observations_ < 2 ||
      min_observations_ > capacity_)
    throw std::invalid_argument("Invalid clock aligner window");
}

bool ClockAligner::addObservation(TimestampNs source_ns, TimestampNs core_ns) {
  if (!observations_.empty() && source_ns <= observations_.back().source) return false;
  observations_.push_back({source_ns, core_ns});
  while (observations_.size() > capacity_) observations_.pop_front();
  return true;
}

std::optional<ClockAlignment> ClockAligner::fit(bool trim_outliers) const {
  if (observations_.size() < min_observations_) return std::nullopt;
  std::vector<Pair> points(observations_.begin(), observations_.end());

  auto solve = [](const std::vector<Pair>& p) -> std::optional<ClockAlignment> {
    const long double x0 = p.front().source;
    const long double y0 = p.front().core;
    long double mean_x = 0.0L, mean_y = 0.0L;
    for (const auto& v : p) { mean_x += v.source - x0; mean_y += v.core - y0; }
    mean_x /= p.size(); mean_y /= p.size();
    long double xx = 0.0L, xy = 0.0L;
    for (const auto& v : p) {
      const long double dx = (v.source - x0) - mean_x;
      const long double dy = (v.core - y0) - mean_y;
      xx += dx*dx; xy += dx*dy;
    }
    if (xx < 1.0L) return std::nullopt;
    const long double scale = xy / xx;
    const long double offset = (y0 + mean_y) - scale*(x0 + mean_x);
    long double squared = 0.0L;
    for (const auto& v : p) {
      const long double e = scale*v.source + offset - v.core;
      squared += e*e;
    }
    if (!std::isfinite(static_cast<double>(scale)) || scale < 0.9L || scale > 1.1L)
      return std::nullopt;
    return ClockAlignment{static_cast<double>(scale), static_cast<double>(offset),
      std::sqrt(static_cast<double>(squared / p.size())), p.size(),
      p.back().source-p.front().source};
  };

  auto initial = solve(points);
  if (!initial || !trim_outliers || points.size() < 10) return initial;
  std::vector<double> residuals;
  residuals.reserve(points.size());
  for (const auto& p : points)
    residuals.push_back(std::abs(initial->scale*p.source + initial->offset_ns - p.core));
  auto sorted = residuals;
  std::sort(sorted.begin(), sorted.end());
  const double median = sorted[sorted.size()/2];
  const double threshold = std::max(1000.0, median * 4.0);
  std::vector<Pair> kept;
  for (std::size_t i=0; i<points.size(); ++i)
    if (residuals[i] <= threshold) kept.push_back(points[i]);
  return kept.size() >= min_observations_ ? solve(kept) : initial;
}

std::optional<ClockAlignment> ClockAligner::alignment() const { return fit(true); }

std::optional<TimestampNs> ClockAligner::toCoreTime(TimestampNs source_ns) const {
  const auto model = alignment();
  if (!model) return std::nullopt;
  const long double mapped = model->scale*static_cast<long double>(source_ns) + model->offset_ns;
  if (mapped > std::numeric_limits<TimestampNs>::max() ||
      mapped < std::numeric_limits<TimestampNs>::min()) return std::nullopt;
  return static_cast<TimestampNs>(std::llround(mapped));
}

void ClockAligner::reset() { observations_.clear(); }

}  // namespace arkernel
