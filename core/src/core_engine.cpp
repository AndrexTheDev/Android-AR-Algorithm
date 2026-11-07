#include "arkernel/core_engine.h"
#include <stdexcept>

namespace arkernel {

CoreEngine::CoreEngine(CoreConfig config,
    std::unique_ptr<IVisualInertialTracker> tracker,
    std::unique_ptr<ISemanticPerception> semantics,
    std::unique_ptr<IMonocularDepth> depth,
    std::unique_ptr<ISpatialMapper> mapper)
    : config_(config), tracker_(std::move(tracker)), semantics_(std::move(semantics)),
      depth_(std::move(depth)), mapper_(std::move(mapper)) {
  if (!tracker_) throw std::invalid_argument("A tracking backend is required");
  if (!config_.camera.valid()) throw std::invalid_argument("Invalid camera intrinsics");
  if (config_.semantic_interval_frames < 1 || config_.depth_interval_frames < 1)
    throw std::invalid_argument("Inference intervals must be positive");
  if (config_.max_imu_samples == 0 || config_.max_sensor_age_ns <= 0)
    throw std::invalid_argument("Sensor queue limits must be positive");
}

bool CoreEngine::submitImu(const ImuSample& sample) {
  std::lock_guard<std::mutex> lock(imu_mutex_);
  if (sample.timestamp_ns <= last_imu_timestamp_) return false;
  last_imu_timestamp_ = sample.timestamp_ns;
  imu_queue_.push_back(sample);
  while (imu_queue_.size() > config_.max_imu_samples) imu_queue_.pop_front();
  return true;
}

std::vector<ImuSample> CoreEngine::takeImuUntil(TimestampNs timestamp_ns) {
  std::lock_guard<std::mutex> lock(imu_mutex_);
  std::vector<ImuSample> result;
  while (!imu_queue_.empty() && imu_queue_.front().timestamp_ns <= timestamp_ns) {
    if (timestamp_ns - imu_queue_.front().timestamp_ns <= config_.max_sensor_age_ns)
      result.push_back(imu_queue_.front());
    imu_queue_.pop_front();
  }
  return result;
}

FrameResult CoreEngine::processFrame(const CameraFrame& frame) {
  if (!frame.data || frame.data_size == 0 || frame.width <= 0 || frame.height <= 0 ||
      frame.row_stride <= 0)
    throw std::invalid_argument("Invalid camera frame");
  if (frame.timestamp_ns <= last_frame_timestamp_)
    throw std::invalid_argument("Camera timestamps must be strictly increasing");
  last_frame_timestamp_ = frame.timestamp_ns;
  FrameResult result;
  auto imu = takeImuUntil(frame.timestamp_ns);
  result.tracking = tracker_->track(frame, imu, config_.camera);

  const bool stable = result.tracking.state == TrackingState::kTracking;
  if (semantics_ && frame_index_ % config_.semantic_interval_frames == 0) {
    result.detections = semantics_->infer(frame);
    result.semantics_updated = true;
  }
  if (depth_ && stable && frame_index_ % config_.depth_interval_frames == 0) {
    result.depth = depth_->infer(frame, config_.camera);
    result.depth_updated = true;
  }
  if (mapper_ && stable) {
    mapper_->integrate(frame, result.tracking,
        result.depth_updated ? &result.depth : nullptr,
        result.semantics_updated ? &result.detections : nullptr);
  }
  ++frame_index_;
  return result;
}

void CoreEngine::reset() {
  { std::lock_guard<std::mutex> lock(imu_mutex_); imu_queue_.clear(); last_imu_timestamp_ = 0; }
  last_frame_timestamp_ = 0;
  frame_index_ = 0;
  tracker_->reset();
  if (mapper_) mapper_->reset();
}

}  // namespace arkernel
