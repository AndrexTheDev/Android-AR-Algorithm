#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/feature_detector.h"

namespace arkernel {

constexpr std::size_t kBinaryDescriptorBytes = 32;

struct BinaryFeatureDescriptor {
  std::uint32_t feature_id{0};
  std::array<std::uint8_t,kBinaryDescriptorBytes> bytes{};
};

struct DescriptorMatch {
  std::uint32_t query_feature_id{0};
  std::uint32_t train_feature_id{0};
  std::uint16_t hamming_distance{0};
};

struct DescriptorMatcherConfig {
  std::uint16_t max_hamming_distance{72};
  double ratio_threshold{0.80};
  bool mutual_best{true};
  std::size_t max_descriptors{4096};
};

// Dependency-free oriented BRIEF descriptor. Features whose complete sampling
// patch is outside the image are omitted rather than padded with invented data.
std::vector<BinaryFeatureDescriptor> describeFeatures(
    const GrayImageView& image,const std::vector<ImageFeature>& features,
    std::size_t max_descriptors=4096);

std::uint16_t descriptorHammingDistance(const BinaryFeatureDescriptor& a,
                                        const BinaryFeatureDescriptor& b);

// Deterministic nearest-neighbor matching with ratio and optional mutual-best
// gates. Output is sorted by query feature ID.
std::vector<DescriptorMatch> matchDescriptors(
    const std::vector<BinaryFeatureDescriptor>& query,
    const std::vector<BinaryFeatureDescriptor>& train,
    DescriptorMatcherConfig config = {});

}  // namespace arkernel
