#include "arkernel/sparse_tsdf.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <utility>

namespace arkernel {
namespace {

bool finite(const Vec3& value) {
  return std::isfinite(value.x) && std::isfinite(value.y) && std::isfinite(value.z);
}

bool finitePose(const Pose& pose) {
  const auto& q = pose.rotation;
  const double norm = std::sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
  return std::isfinite(norm) && norm > 1e-9 && finite(pose.translation);
}

bool validLayout(int width, int height, int stride, std::size_t count) {
  if (width <= 0 || height <= 0 || stride < width) return false;
  const std::size_t rows = static_cast<std::size_t>(height - 1);
  const std::size_t row_stride = static_cast<std::size_t>(stride);
  if (rows > std::numeric_limits<std::size_t>::max() / row_stride) return false;
  const std::size_t offset = rows * row_stride;
  return static_cast<std::size_t>(width) <= count &&
         offset <= count - static_cast<std::size_t>(width);
}

bool validOptionalLayout(const float* data, int width, int height, int stride,
                         std::size_t count) {
  if (data == nullptr) return stride == 0 && count == 0;
  return validLayout(width, height, stride, count);
}

std::pair<std::int32_t, int> blockAndLocal(std::int32_t coordinate) {
  std::int32_t block = coordinate / SparseTsdfVolume::kBlockSide;
  int local = coordinate % SparseTsdfVolume::kBlockSide;
  if (local < 0) {
    --block;
    local += SparseTsdfVolume::kBlockSide;
  }
  return {block, local};
}

}  // namespace

std::size_t SparseTsdfVolume::BlockHash::operator()(const BlockKey& key) const {
  std::size_t seed = 0x9e3779b97f4a7c15ULL;
  const auto mix = [&seed](std::uint32_t value) {
    seed ^= static_cast<std::size_t>(value) + 0x9e3779b9U + (seed << 6) + (seed >> 2);
  };
  mix(static_cast<std::uint32_t>(key.x));
  mix(static_cast<std::uint32_t>(key.y));
  mix(static_cast<std::uint32_t>(key.z));
  return seed;
}

SparseTsdfVolume::SparseTsdfVolume(SparseTsdfConfig config) : config_(config) {
  if (!std::isfinite(config_.voxel_size_m) || config_.voxel_size_m < 0.001 ||
      config_.voxel_size_m > 1.0 ||
      !std::isfinite(config_.truncation_distance_m) ||
      config_.truncation_distance_m < config_.voxel_size_m ||
      config_.truncation_distance_m > 2.0 ||
      !std::isfinite(config_.min_depth_m) || config_.min_depth_m <= 0.0 ||
      !std::isfinite(config_.max_depth_m) ||
      config_.max_depth_m <= config_.min_depth_m ||
      !std::isfinite(config_.min_confidence) || config_.min_confidence < 0.0 ||
      config_.min_confidence > 1.0 ||
      !std::isfinite(config_.default_uncertainty_m) ||
      config_.default_uncertainty_m <= 0.0 ||
      !std::isfinite(config_.uncertainty_floor_m) ||
      config_.uncertainty_floor_m <= 0.0 ||
      !std::isfinite(config_.max_weight) || config_.max_weight <= 0.0 ||
      config_.max_weight > 1e6 ||
      !std::isfinite(config_.max_position_norm_m) ||
      config_.max_position_norm_m <= 0.0 || config_.pixel_stride <= 0 ||
      config_.pixel_stride > 64 || config_.max_blocks == 0 ||
      config_.max_blocks > 65536 || config_.max_depth_pixels_per_update == 0 ||
      config_.max_depth_pixels_per_update > 4194304 ||
      config_.max_voxel_visits_per_update == 0 ||
      config_.max_voxel_visits_per_update > 16777216 ||
      config_.max_ray_samples < 3 || config_.max_ray_samples > 512 ||
      config_.max_snapshot_voxels == 0 ||
      config_.max_snapshot_voxels > 4194304 ||
      config_.max_position_norm_m / config_.voxel_size_m >
          static_cast<double>(std::numeric_limits<std::int32_t>::max() / 2)) {
    throw std::invalid_argument("Invalid sparse TSDF configuration");
  }
  const double ray_step = config_.voxel_size_m * 0.5;
  const std::size_t half_steps =
      static_cast<std::size_t>(std::ceil(config_.truncation_distance_m / ray_step));
  if (half_steps > (config_.max_ray_samples - 1) / 2) {
    throw std::invalid_argument("TSDF truncation band exceeds ray sample budget");
  }
  blocks_.reserve(config_.max_blocks);
}

std::optional<SparseTsdfVolume::VoxelAddress> SparseTsdfVolume::addressFor(
    const Vec3& position_world_m) const {
  if (!finite(position_world_m) ||
      position_world_m.norm() > config_.max_position_norm_m) {
    return std::nullopt;
  }
  const double gx = std::floor(position_world_m.x / config_.voxel_size_m);
  const double gy = std::floor(position_world_m.y / config_.voxel_size_m);
  const double gz = std::floor(position_world_m.z / config_.voxel_size_m);
  constexpr double minimum = static_cast<double>(std::numeric_limits<std::int32_t>::min());
  constexpr double maximum = static_cast<double>(std::numeric_limits<std::int32_t>::max());
  if (gx < minimum || gx > maximum || gy < minimum || gy > maximum ||
      gz < minimum || gz > maximum) {
    return std::nullopt;
  }
  const auto x = static_cast<std::int32_t>(gx);
  const auto y = static_cast<std::int32_t>(gy);
  const auto z = static_cast<std::int32_t>(gz);
  const auto [block_x, local_x] = blockAndLocal(x);
  const auto [block_y, local_y] = blockAndLocal(y);
  const auto [block_z, local_z] = blockAndLocal(z);
  const std::size_t index =
      (static_cast<std::size_t>(local_z) * kBlockSide +
       static_cast<std::size_t>(local_y)) * kBlockSide +
      static_cast<std::size_t>(local_x);
  return VoxelAddress{x, y, z, {block_x, block_y, block_z}, index};
}

Vec3 SparseTsdfVolume::centerFor(std::int32_t x, std::int32_t y,
                                 std::int32_t z) const {
  return {(static_cast<double>(x) + 0.5) * config_.voxel_size_m,
          (static_cast<double>(y) + 0.5) * config_.voxel_size_m,
          (static_cast<double>(z) + 0.5) * config_.voxel_size_m};
}

SparseTsdfUpdate SparseTsdfVolume::integrate(
    const TsdfDepthImageView& depth, const PinholeRadTanCamera& camera,
    const Pose& world_from_camera) {
  SparseTsdfUpdate update;
  const auto& intrinsics = camera.intrinsics();
  if (!finitePose(world_from_camera) || depth.width != intrinsics.width ||
      depth.height != intrinsics.height || depth.depth_z_m == nullptr ||
      !validLayout(depth.width, depth.height, depth.depth_row_stride_elements,
                   depth.depth_element_count) ||
      !validOptionalLayout(depth.confidence, depth.width, depth.height,
                           depth.confidence_row_stride_elements,
                           depth.confidence_element_count) ||
      !validOptionalLayout(depth.uncertainty_m, depth.width, depth.height,
                           depth.uncertainty_row_stride_elements,
                           depth.uncertainty_element_count)) {
    return update;
  }
  update.input_valid = true;
  const Quaternion camera_from_world = world_from_camera.rotation.inverse();
  const double ray_step = config_.voxel_size_m * 0.5;
  const int half_steps = static_cast<int>(
      std::ceil(config_.truncation_distance_m / ray_step));

  for (int y = 0; y < depth.height; y += config_.pixel_stride) {
    for (int x = 0; x < depth.width; x += config_.pixel_stride) {
      if (update.visited_pixels >= config_.max_depth_pixels_per_update) {
        update.truncated = true;
        return update;
      }
      ++update.visited_pixels;
      const std::size_t depth_index =
          static_cast<std::size_t>(y) * depth.depth_row_stride_elements + x;
      const double observed_depth = depth.depth_z_m[depth_index];
      const double confidence = depth.confidence
          ? depth.confidence[static_cast<std::size_t>(y) *
                                 depth.confidence_row_stride_elements + x]
          : 1.0;
      const double uncertainty = depth.uncertainty_m
          ? depth.uncertainty_m[static_cast<std::size_t>(y) *
                                    depth.uncertainty_row_stride_elements + x]
          : config_.default_uncertainty_m;
      if (!std::isfinite(observed_depth) || observed_depth < config_.min_depth_m ||
          observed_depth > config_.max_depth_m || !std::isfinite(confidence) ||
          confidence < config_.min_confidence || confidence > 1.0 ||
          !std::isfinite(uncertainty) || uncertainty <= 0.0) {
        ++update.rejected_pixels;
        continue;
      }
      const auto ray = camera.unproject(
          {static_cast<double>(x), static_cast<double>(y)});
      if (!ray || ray->z <= 1e-9) {
        ++update.rejected_pixels;
        continue;
      }
      const Vec3 surface_camera = *ray * (observed_depth / ray->z);
      const Vec3 surface_world =
          world_from_camera.rotation.rotate(surface_camera) +
          world_from_camera.translation;
      if (!finite(surface_world) ||
          surface_world.norm() > config_.max_position_norm_m) {
        ++update.rejected_pixels;
        continue;
      }
      const double uncertainty_ratio =
          std::min(1.0, config_.uncertainty_floor_m / uncertainty);
      const double observation_weight =
          confidence * uncertainty_ratio * uncertainty_ratio;
      if (!std::isfinite(observation_weight) || observation_weight <= 0.0) {
        ++update.rejected_pixels;
        continue;
      }
      bool integrated_pixel = false;
      std::optional<VoxelAddress> previous_address;

      for (int step = -half_steps; step <= half_steps; ++step) {
        const double offset = static_cast<double>(step) * ray_step;
        if (std::abs(offset) > config_.truncation_distance_m + 1e-12) continue;
        const double sample_depth = observed_depth + offset;
        if (sample_depth <= 0.0) continue;
        const Vec3 sample_camera = *ray * (sample_depth / ray->z);
        const Vec3 sample_world =
            world_from_camera.rotation.rotate(sample_camera) +
            world_from_camera.translation;
        const auto address = addressFor(sample_world);
        if (!address) continue;
        if (previous_address && previous_address->x == address->x &&
            previous_address->y == address->y && previous_address->z == address->z) {
          continue;
        }
        previous_address = address;
        const Vec3 center_world = centerFor(address->x, address->y, address->z);
        const Vec3 center_camera =
            camera_from_world.rotate(center_world - world_from_camera.translation);
        const double signed_distance = observed_depth - center_camera.z;
        if (!std::isfinite(signed_distance) ||
            std::abs(signed_distance) > config_.truncation_distance_m) {
          continue;
        }
        if (update.voxel_visits >= config_.max_voxel_visits_per_update) {
          if (integrated_pixel) ++update.integrated_pixels;
          else ++update.rejected_pixels;
          update.truncated = true;
          return update;
        }
        ++update.voxel_visits;
        auto block = blocks_.find(address->block);
        if (block == blocks_.end()) {
          if (blocks_.size() >= config_.max_blocks) {
            ++update.capacity_rejections;
            update.truncated = true;
            continue;
          }
          block = blocks_.emplace(address->block, Block{}).first;
          ++update.allocated_blocks;
        }
        Voxel& voxel = block->second.voxels[address->local_index];
        if (voxel.weight == 0.0F) ++voxel_count_;
        const double previous_weight = voxel.weight;
        const double combined_weight = previous_weight + observation_weight;
        voxel.signed_distance_m = static_cast<float>(
            (previous_weight * voxel.signed_distance_m +
             observation_weight * signed_distance) /
            combined_weight);
        voxel.weight = static_cast<float>(
            std::min(config_.max_weight, combined_weight));
        ++update.voxel_updates;
        integrated_pixel = true;
      }
      if (integrated_pixel) ++update.integrated_pixels;
      else ++update.rejected_pixels;
    }
  }
  return update;
}

std::optional<TsdfVoxelSample> SparseTsdfVolume::sample(
    const Vec3& position_world_m) const {
  const auto address = addressFor(position_world_m);
  if (!address) return std::nullopt;
  const auto block = blocks_.find(address->block);
  if (block == blocks_.end()) return std::nullopt;
  const Voxel& voxel = block->second.voxels[address->local_index];
  if (voxel.weight <= 0.0F) return std::nullopt;
  return TsdfVoxelSample{address->x, address->y, address->z,
                         centerFor(address->x, address->y, address->z),
                         voxel.signed_distance_m, voxel.weight};
}

std::vector<TsdfVoxelSample> SparseTsdfVolume::snapshot() const {
  if (voxel_count_ > config_.max_snapshot_voxels) {
    throw std::length_error("TSDF snapshot exceeds voxel budget");
  }
  std::vector<TsdfVoxelSample> output;
  output.reserve(voxel_count_);
  for (const auto& item : blocks_) {
    for (int local_z = 0; local_z < kBlockSide; ++local_z) {
      for (int local_y = 0; local_y < kBlockSide; ++local_y) {
        for (int local_x = 0; local_x < kBlockSide; ++local_x) {
          const std::size_t index =
              (static_cast<std::size_t>(local_z) * kBlockSide +
               static_cast<std::size_t>(local_y)) * kBlockSide +
              static_cast<std::size_t>(local_x);
          const Voxel& voxel = item.second.voxels[index];
          if (voxel.weight <= 0.0F) continue;
          const std::int32_t x = item.first.x * kBlockSide + local_x;
          const std::int32_t y = item.first.y * kBlockSide + local_y;
          const std::int32_t z = item.first.z * kBlockSide + local_z;
          output.push_back({x, y, z, centerFor(x, y, z),
                            voxel.signed_distance_m, voxel.weight});
        }
      }
    }
  }
  std::sort(output.begin(), output.end(), [](const auto& a, const auto& b) {
    if (a.z != b.z) return a.z < b.z;
    if (a.y != b.y) return a.y < b.y;
    return a.x < b.x;
  });
  return output;
}

void SparseTsdfVolume::reset() {
  blocks_.clear();
  voxel_count_ = 0;
}

}  // namespace arkernel
