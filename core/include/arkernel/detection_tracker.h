#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>
#include "arkernel/detection_postprocessor.h"

namespace arkernel {
struct DetectionTrackerConfig {
  double min_iou=0.30;
  std::size_t max_tracks=256;
  std::size_t max_missed_frames=5;
  std::int64_t max_frame_gap_ns=1'000'000'000;
};
struct TrackedDetection {
  DecodedDetection detection{};
  std::uint64_t track_id=0;
};
class DetectionTracker {
 public:
  explicit DetectionTracker(DetectionTrackerConfig config={});
  std::vector<TrackedDetection> update(std::int64_t timestamp_ns,
                                       const std::vector<DecodedDetection>& detections);
  void reset();
  std::size_t trackCount() const {return tracks_.size();}
 private:
  struct Track {std::uint64_t id=0;DecodedDetection detection{};
    std::int64_t last_seen_ns=0;std::size_t missed=0;};
  DetectionTrackerConfig config_;
  std::vector<Track> tracks_;
  std::uint64_t next_id_=1;
  std::int64_t last_timestamp_ns_=0;
};
}  // namespace arkernel
