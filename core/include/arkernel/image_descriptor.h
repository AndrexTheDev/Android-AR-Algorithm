#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <unordered_map>
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

struct LandmarkDescriptorStoreConfig {
  std::size_t max_entries{4096};
  std::size_t min_observations_for_snapshot{2};
  std::size_t max_accumulated_observations{1024};
};

// Aggregates repeated descriptors of confirmed landmarks with a bitwise
// majority vote. Entries disappear when their landmark is no longer confirmed.
class LandmarkDescriptorStore {
 public:
  explicit LandmarkDescriptorStore(LandmarkDescriptorStoreConfig config = {});
  void integrate(const std::vector<BinaryFeatureDescriptor>& descriptors,
                 const std::vector<std::uint32_t>& confirmed_feature_ids);
  std::vector<BinaryFeatureDescriptor> snapshot() const;
  bool restore(const std::vector<BinaryFeatureDescriptor>& descriptors,
               const std::vector<std::uint32_t>& confirmed_feature_ids);
  std::size_t size() const { return entries_.size(); }
  void reset();
 private:
  struct Entry {
    std::array<std::uint16_t,kBinaryDescriptorBytes*8> one_votes{};
    std::size_t observations{0};
  };
  LandmarkDescriptorStoreConfig config_;
  std::unordered_map<std::uint32_t,Entry> entries_;
};

}  // namespace arkernel
