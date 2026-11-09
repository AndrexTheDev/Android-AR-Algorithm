#include "arkernel/multi_view_stereo.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <optional>
#include <stdexcept>

namespace arkernel {
namespace {

bool finitePose(const Pose& pose) {
  const auto& rotation = pose.rotation;
  const double norm = std::sqrt(rotation.w * rotation.w + rotation.x * rotation.x +
                                rotation.y * rotation.y + rotation.z * rotation.z);
  return std::isfinite(norm) && norm > 1e-9 &&
         std::isfinite(pose.translation.x) && std::isfinite(pose.translation.y) &&
         std::isfinite(pose.translation.z);
}

Pose inverse(const Pose& pose) {
  const auto rotation = pose.rotation.inverse();
  return {rotation, rotation.rotate(pose.translation * -1.0)};
}

double bilinear(const GrayImageView& image, double x, double y) {
  const int x0 = static_cast<int>(std::floor(x));
  const int y0 = static_cast<int>(std::floor(y));
  const double dx = x - x0;
  const double dy = y - y0;
  return (1.0 - dx) * (1.0 - dy) * image.at(x0, y0) +
         dx * (1.0 - dy) * image.at(x0 + 1, y0) +
         (1.0 - dx) * dy * image.at(x0, y0 + 1) +
         dx * dy * image.at(x0 + 1, y0 + 1);
}

bool inside(const GrayImageView& image, double x, double y) {
  return x >= 0.0 && y >= 0.0 && x < image.width - 1 && y < image.height - 1;
}

int censusState(double difference, double tolerance) {
  if (difference > tolerance) return 1;
  if (difference < -tolerance) return -1;
  return 0;
}

bool usesCensus(MultiViewStereoMatchingCost cost) {
  return cost == MultiViewStereoMatchingCost::kCensus ||
         cost == MultiViewStereoMatchingCost::kHybrid;
}

bool validMatchingCost(MultiViewStereoMatchingCost cost) {
  return cost == MultiViewStereoMatchingCost::kZncc ||
         cost == MultiViewStereoMatchingCost::kCensus ||
         cost == MultiViewStereoMatchingCost::kHybrid;
}

std::optional<double> patchMatchingScore(const MultiViewStereoFrame& reference,
                                         const MultiViewStereoFrame& source,
                                         int center_x, int center_y, double depth,
                                         const MultiViewStereoConfig& config) {
  const Pose source_from_world = inverse(source.world_from_camera);
  const double reference_center = reference.image.at(center_x, center_y);
  double source_center = 0.0;
  std::size_t center_index = 0;
  std::array<double, 121> reference_values{};
  std::array<double, 121> source_values{};
  double sum_a = 0.0;
  double sum_b = 0.0;
  double sum_aa = 0.0;
  double sum_bb = 0.0;
  double sum_ab = 0.0;
  std::size_t count = 0;
  std::size_t census_mismatches = 0;
  std::size_t informative_reference = 0;
  std::size_t informative_source = 0;

  for (int oy = -config.patch_radius; oy <= config.patch_radius; ++oy) {
    for (int ox = -config.patch_radius; ox <= config.patch_radius; ++ox) {
      const Vec2 reference_pixel{static_cast<double>(center_x + ox),
                                 static_cast<double>(center_y + oy)};
      const auto ray = reference.camera->unproject(reference_pixel);
      if (!ray || ray->z <= 1e-9) return std::nullopt;
      const Vec3 reference_point = *ray * (depth / ray->z);
      const Vec3 world = reference.world_from_camera.rotation.rotate(reference_point) +
                         reference.world_from_camera.translation;
      const Vec3 source_point = source_from_world.rotation.rotate(world) +
                                source_from_world.translation;
      const auto projected = source.camera->project(source_point);
      if (!projected || !inside(source.image, projected->x, projected->y)) {
        return std::nullopt;
      }
      const double a = reference.image.at(center_x + ox, center_y + oy);
      const double b = bilinear(source.image, projected->x, projected->y);
      reference_values[count] = a;
      source_values[count] = b;
      if (ox == 0 && oy == 0) {
        source_center = b;
        center_index = count;
      }
      sum_a += a;
      sum_b += b;
      sum_aa += a * a;
      sum_bb += b * b;
      sum_ab += a * b;
      ++count;
    }
  }

  std::optional<double> zncc;
  const double inverse_count = 1.0 / static_cast<double>(count);
  const double covariance = sum_ab - sum_a * sum_b * inverse_count;
  const double variance_a = sum_aa - sum_a * sum_a * inverse_count;
  const double variance_b = sum_bb - sum_b * sum_b * inverse_count;
  const double denominator = std::sqrt(std::max(0.0, variance_a * variance_b));
  if (std::isfinite(denominator) && denominator >= 1e-6) {
    zncc = std::max(-1.0, std::min(1.0, covariance / denominator));
  }

  std::optional<double> census;
  if (usesCensus(config.matching_cost)) {
    for (std::size_t i = 0; i < count; ++i) {
      if (i == center_index) continue;
      const int reference_state = censusState(reference_values[i] - reference_center,
                                               config.census_intensity_tolerance);
      const int source_state = censusState(source_values[i] - source_center,
                                           config.census_intensity_tolerance);
      informative_reference += reference_state != 0;
      informative_source += source_state != 0;
      census_mismatches += reference_state != source_state;
    }
    const std::size_t census_comparisons = count - 1;
    if (informative_reference >= config.min_census_bits &&
        informative_source >= config.min_census_bits && census_comparisons > 0) {
      census = 1.0 - 2.0 * static_cast<double>(census_mismatches) /
                           static_cast<double>(census_comparisons);
    }
  }

  switch (config.matching_cost) {
    case MultiViewStereoMatchingCost::kZncc:
      return zncc;
    case MultiViewStereoMatchingCost::kCensus:
      return census;
    case MultiViewStereoMatchingCost::kHybrid:
      if (!zncc || !census) return std::nullopt;
      return (1.0 - config.census_weight) * *zncc + config.census_weight * *census;
  }
  return std::nullopt;
}

}  // namespace

MultiViewStereoDepth estimateMultiViewDepth(const MultiViewStereoFrame& reference,
                                            const std::vector<MultiViewStereoFrame>& sources,
                                            MultiViewStereoConfig config) {
  const std::size_t patch_comparisons =
      config.patch_radius >= 1 && config.patch_radius <= 5
          ? static_cast<std::size_t>((2 * config.patch_radius + 1) *
                                     (2 * config.patch_radius + 1) - 1)
          : 0;
  if (!std::isfinite(config.min_depth_m) || !std::isfinite(config.max_depth_m) ||
      !std::isfinite(config.refinement_radius_steps) ||
      !std::isfinite(config.min_baseline_m) || !std::isfinite(config.max_baseline_m) ||
      !std::isfinite(config.census_weight) ||
      !std::isfinite(config.census_intensity_tolerance) ||
      !std::isfinite(config.min_zncc) || !std::isfinite(config.min_winner_margin) ||
      config.min_depth_m <= 0.0 || config.max_depth_m <= config.min_depth_m ||
      config.depth_hypotheses < 3 || config.depth_hypotheses > 512 ||
      config.refinement_levels > 3 ||
      (config.refinement_levels > 0 &&
       (config.refinement_hypotheses < 3 || config.refinement_hypotheses > 33 ||
        config.refinement_radius_steps <= 0.0 || config.refinement_radius_steps > 4.0)) ||
      config.pixel_stride <= 0 || config.patch_radius < 1 || config.patch_radius > 5 ||
      config.min_supporting_views == 0 || config.max_source_views == 0 ||
      config.min_supporting_views > config.max_source_views ||
      config.max_output_pixels == 0 || config.min_baseline_m <= 0.0 ||
      config.max_baseline_m <= config.min_baseline_m ||
      !validMatchingCost(config.matching_cost) || config.census_weight < 0.0 ||
      config.census_weight > 1.0 || config.census_intensity_tolerance < 0.0 ||
      config.census_intensity_tolerance > 255.0 ||
      (usesCensus(config.matching_cost) &&
       (config.min_census_bits == 0 || config.min_census_bits > patch_comparisons)) ||
      config.min_zncc <= -1.0 || config.min_zncc >= 1.0 ||
      config.min_winner_margin <= 0.0 || config.min_winner_margin >= 2.0) {
    throw std::invalid_argument("Invalid multi-view stereo configuration");
  }

  MultiViewStereoDepth result;
  if (!reference.image.valid() || reference.camera == nullptr ||
      !finitePose(reference.world_from_camera) || sources.empty() ||
      sources.size() > config.max_source_views) {
    return result;
  }
  const std::size_t pixels =
      static_cast<std::size_t>(reference.image.width) * reference.image.height;
  if (pixels > config.max_output_pixels) return result;

  std::vector<const MultiViewStereoFrame*> usable;
  usable.reserve(sources.size());
  for (const auto& source : sources) {
    if (!source.image.valid() || source.camera == nullptr ||
        !finitePose(source.world_from_camera)) {
      return result;
    }
    const double baseline =
        (source.world_from_camera.translation - reference.world_from_camera.translation).norm();
    if (baseline >= config.min_baseline_m && baseline <= config.max_baseline_m) {
      usable.push_back(&source);
    }
  }
  if (usable.size() < config.min_supporting_views) return result;

  result.width = reference.image.width;
  result.height = reference.image.height;
  const float nan = std::numeric_limits<float>::quiet_NaN();
  result.depth_m.assign(pixels, nan);
  result.confidence.assign(pixels, 0.0F);
  result.uncertainty_m.assign(pixels, nan);
  const int border = config.patch_radius + 1;
  const double inverse_near = 1.0 / config.min_depth_m;
  const double inverse_far = 1.0 / config.max_depth_m;
  const double inverse_step =
      (inverse_near - inverse_far) / static_cast<double>(config.depth_hypotheses - 1);

  for (int y = border; y < reference.image.height - border; y += config.pixel_stride) {
    for (int x = border; x < reference.image.width - border; x += config.pixel_stride) {
      auto scoreAt = [&](double inverse_depth, std::size_t& support) {
        if (!std::isfinite(inverse_depth) || inverse_depth < inverse_far ||
            inverse_depth > inverse_near) {
          support = 0;
          return -2.0;
        }
        const double depth = 1.0 / inverse_depth;
        double score = 0.0;
        support = 0;
        for (const auto* source : usable) {
          const auto value = patchMatchingScore(reference, *source, x, y, depth, config);
          if (value) {
            score += *value;
            ++support;
          }
        }
        return support >= config.min_supporting_views
                   ? score / static_cast<double>(support)
                   : -2.0;
      };

      std::vector<double> scores(config.depth_hypotheses, -2.0);
      std::vector<std::size_t> supports(config.depth_hypotheses);
      for (std::size_t hypothesis = 0; hypothesis < config.depth_hypotheses;
           ++hypothesis) {
        const double inverse_depth = inverse_near - inverse_step * hypothesis;
        scores[hypothesis] = scoreAt(inverse_depth, supports[hypothesis]);
      }
      const auto coarse_best = std::max_element(scores.begin(), scores.end());
      const std::size_t coarse_index =
          static_cast<std::size_t>(coarse_best - scores.begin());
      double best_inverse = inverse_near - inverse_step * coarse_index;
      double best_score = *coarse_best;
      std::size_t best_support = supports[coarse_index];
      double second_score = -2.0;
      double final_inverse_step = inverse_step;
      for (std::size_t i = 0; i < scores.size(); ++i) {
        if ((i + 1 < coarse_index || i > coarse_index + 1) &&
            scores[i] > second_score) {
          second_score = scores[i];
        }
      }

      double search_step = inverse_step;
      for (std::size_t level = 0;
           level < config.refinement_levels && best_score > -1.5; ++level) {
        const double radius = search_step * config.refinement_radius_steps;
        const double lower = std::max(inverse_far, best_inverse - radius);
        const double upper = std::min(inverse_near, best_inverse + radius);
        if (!(upper > lower)) break;
        const double step =
            (upper - lower) / static_cast<double>(config.refinement_hypotheses - 1);
        std::vector<double> refined_scores(config.refinement_hypotheses, -2.0);
        std::vector<std::size_t> refined_supports(config.refinement_hypotheses);
        for (std::size_t i = 0; i < config.refinement_hypotheses; ++i) {
          refined_scores[i] = scoreAt(lower + step * i, refined_supports[i]);
        }
        const auto refined_best =
            std::max_element(refined_scores.begin(), refined_scores.end());
        const std::size_t refined_index =
            static_cast<std::size_t>(refined_best - refined_scores.begin());
        best_inverse = lower + step * refined_index;
        best_score = *refined_best;
        best_support = refined_supports[refined_index];
        // Nearby fine samples describe the selected mode, not an independent
        // ambiguity competitor. Keep the coarse non-neighbour peak above.
        final_inverse_step = step;
        search_step = step;
      }

      if (best_score < config.min_zncc) continue;
      const double margin = best_score - second_score;
      if (second_score > -1.5 && margin < config.min_winner_margin) continue;
      const double depth = 1.0 / best_inverse;
      const double neighboring_inverse =
          best_inverse - final_inverse_step >= inverse_far
              ? best_inverse - final_inverse_step
              : best_inverse + final_inverse_step;
      if (neighboring_inverse <= 0.0 || neighboring_inverse > inverse_near) continue;
      const double sampling_uncertainty =
          std::abs(depth - 1.0 / neighboring_inverse) * 0.5;
      const double score_confidence =
          (best_score - config.min_zncc) / (1.0 - config.min_zncc);
      const double margin_confidence =
          second_score <= -1.5
              ? 1.0
              : std::min(1.0, margin / config.min_winner_margin);
      const double view_confidence =
          static_cast<double>(best_support) / static_cast<double>(usable.size());
      const float confidence = static_cast<float>(std::max(
          0.0, std::min(1.0, score_confidence * margin_confidence * view_confidence)));
      const std::size_t index = static_cast<std::size_t>(y) * result.width + x;
      result.depth_m[index] = static_cast<float>(depth);
      result.confidence[index] = confidence;
      result.uncertainty_m[index] = static_cast<float>(
          sampling_uncertainty / std::max(0.1F, confidence));
      ++result.valid_pixels;
    }
  }
  result.valid = true;
  return result;
}

}  // namespace arkernel
