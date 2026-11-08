#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>
#include "arkernel/image_descriptor.h"
#include "arkernel/sparse_map.h"

namespace arkernel {

struct SpatialSnapshot {
  TimestampNs created_ns{0};
  std::vector<SparseLandmark> landmarks;
  std::vector<WorldAnchor> anchors;
  std::vector<BinaryFeatureDescriptor> landmark_descriptors;
};

struct SpatialDecodeResult {
  SpatialSnapshot snapshot;
  std::string error;
  bool valid{false};
};

struct SpatialSnapshotLimits {
  std::size_t max_bytes{64*1024*1024};
  std::size_t max_landmarks{100000};
  std::size_t max_anchors{10000};
  std::size_t max_descriptors{100000};
};

// Portable integrity-checked serialization. CRC detects corruption, not
// malicious tampering. Encrypt/authenticate the returned bytes before storage.
bool restoreSpatialSnapshot(const SpatialSnapshot& snapshot,
                            SparseLandmarkMap& map,
                            AnchorStore& anchors);

class SpatialSnapshotCodec {
 public:
  static std::vector<std::uint8_t> encode(const SpatialSnapshot& snapshot,
                                          SpatialSnapshotLimits limits = {});
  static SpatialDecodeResult decode(const std::vector<std::uint8_t>& bytes,
                                    SpatialSnapshotLimits limits = {});
};

}  // namespace arkernel
