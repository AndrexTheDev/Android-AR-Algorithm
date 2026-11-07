#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <optional>
#include "arkernel/types.h"

namespace arkernel {

struct ClockAlignment {
  double scale{1.0};
  double offset_ns{0.0};
  double rms_error_ns{0.0};
  std::size_t observation_count{0};
};

// Estimates core_time = scale * source_time + offset from paired timestamp
// observations. Centered least squares avoids precision loss at boot-time-sized
// nanosecond values; residual trimming rejects delayed callback outliers.
class ClockAligner {
 public:
  explicit ClockAligner(std::size_t capacity = 64, std::size_t min_observations = 6);
  bool addObservation(TimestampNs source_ns, TimestampNs core_ns);
  std::optional<TimestampNs> toCoreTime(TimestampNs source_ns) const;
  std::optional<ClockAlignment> alignment() const;
  void reset();

 private:
  struct Pair { TimestampNs source; TimestampNs core; };
  std::optional<ClockAlignment> fit(bool trim_outliers) const;
  std::size_t capacity_;
  std::size_t min_observations_;
  std::deque<Pair> observations_;
};

}  // namespace arkernel
