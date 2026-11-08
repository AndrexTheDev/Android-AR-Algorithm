#pragma once

#include <cstddef>
#include <cstdint>
#include <unordered_map>
#include <vector>

#include "arkernel/image_descriptor.h"
#include "arkernel/types.h"

namespace arkernel {

using RecognitionKeyframeId = std::uint64_t;

struct PlaceRecognitionConfig {
  std::size_t max_keyframes{500};
  std::size_t max_descriptors_per_keyframe{2048};
  std::size_t vocabulary_words{4096};
  std::size_t max_candidates{5};
  std::size_t min_shared_words{8};
  TimestampNs min_temporal_separation_ns{5000000000LL};
  double min_similarity{0.15};
};

struct PlaceRecognitionCandidate {
  RecognitionKeyframeId keyframe_id{0};
  TimestampNs timestamp_ns{0};
  double similarity{0.0};
  std::size_t shared_words{0};
};

// Bounded appearance-only retrieval. Results must pass independent geometric
// verification before becoming loop-closure or pose-graph constraints.
class PlaceRecognitionDatabase {
 public:
  explicit PlaceRecognitionDatabase(PlaceRecognitionConfig config = {});
  bool insert(RecognitionKeyframeId keyframe_id, TimestampNs timestamp_ns,
              const std::vector<BinaryFeatureDescriptor>& descriptors);
  std::vector<PlaceRecognitionCandidate> query(
      TimestampNs timestamp_ns,
      const std::vector<BinaryFeatureDescriptor>& descriptors) const;
  std::size_t size() const { return entries_.size(); }
  void reset();

 private:
  struct WordCount { std::uint32_t word{0}; std::uint32_t count{0}; };
  struct Entry {
    RecognitionKeyframeId id{0};
    TimestampNs timestamp_ns{0};
    std::vector<WordCount> words;
  };
  std::vector<WordCount> makeWords(
      const std::vector<BinaryFeatureDescriptor>& descriptors) const;
  void evictOne();

  PlaceRecognitionConfig config_;
  std::unordered_map<RecognitionKeyframeId,Entry> entries_;
  std::vector<std::size_t> document_frequency_;
};

}  // namespace arkernel
