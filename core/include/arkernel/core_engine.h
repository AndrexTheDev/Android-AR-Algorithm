#pragma once
#include <deque>
#include <memory>
#include <mutex>
#include "arkernel/modules.h"

namespace arkernel {

struct CoreConfig {
  CameraIntrinsics camera;
  int semantic_interval_frames{6};
  int depth_interval_frames{3};
  std::size_t max_imu_samples{2048};
  TimestampNs max_sensor_age_ns{500000000};
};

// Platform-neutral orchestrator. Android camera/sensor code and concrete
// ARCore/OpenCV/ONNX backends are adapters, never hard dependencies.
class CoreEngine {
 public:
  CoreEngine(CoreConfig config, std::unique_ptr<IVisualInertialTracker> tracker,
             std::unique_ptr<ISemanticPerception> semantics = nullptr,
             std::unique_ptr<IMonocularDepth> depth = nullptr,
             std::unique_ptr<ISpatialMapper> mapper = nullptr);
  bool submitImu(const ImuSample& sample);
  FrameResult processFrame(const CameraFrame& frame);
  void reset();
 private:
  std::vector<ImuSample> takeImuUntil(TimestampNs timestamp_ns);
  CoreConfig config_;
  std::unique_ptr<IVisualInertialTracker> tracker_;
  std::unique_ptr<ISemanticPerception> semantics_;
  std::unique_ptr<IMonocularDepth> depth_;
  std::unique_ptr<ISpatialMapper> mapper_;
  std::deque<ImuSample> imu_queue_;
  std::mutex imu_mutex_;
  TimestampNs last_imu_timestamp_{0};
  TimestampNs last_frame_timestamp_{0};
  std::uint64_t frame_index_{0};
};

}  // namespace arkernel
