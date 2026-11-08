#pragma once
#include "arkernel/types.h"

namespace arkernel {

class IVisualInertialTracker {
 public:
  virtual ~IVisualInertialTracker() = default;
  virtual TrackingEstimate track(const CameraFrame& frame,
                                 const std::vector<ImuSample>& imu_batch,
                                 const CameraIntrinsics& intrinsics) = 0;
  virtual void reset() = 0;
};

class ISemanticPerception {
 public:
  virtual ~ISemanticPerception() = default;
  virtual std::vector<Detection> infer(const CameraFrame& frame) = 0;
};

class IMonocularDepth {
 public:
  virtual ~IMonocularDepth() = default;
  virtual DepthMap infer(const CameraFrame& frame, const CameraIntrinsics& intrinsics) = 0;
};

class ISpatialMapper {
 public:
  virtual ~ISpatialMapper() = default;
  virtual void integrate(const CameraFrame& frame, const TrackingEstimate& tracking,
                         const DepthMap* depth,
                         const std::vector<Detection>* detections) = 0;
  virtual void reset() = 0;
};

}  // namespace arkernel
