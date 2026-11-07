#include <cmath>
#include <cstdlib>
#include <iostream>
#include <memory>
#include "arkernel/core_engine.h"
#include "arkernel/imu_preintegrator.h"
#include "arkernel/tracking_quality.h"

using namespace arkernel;
#define CHECK(x) do { if (!(x)) { std::cerr << "FAIL line " << __LINE__ << ": " #x "\n"; return 1; } } while (0)

class FakeTracker final : public IVisualInertialTracker {
 public:
  std::size_t imu_count{};
  TrackingEstimate track(const CameraFrame& f, const std::vector<ImuSample>& imu,
                         const CameraIntrinsics&) override {
    imu_count = imu.size(); return {f.timestamp_ns, {}, TrackingState::kTracking, 100, 80, 0.5, 0.9};
  }
  void reset() override { imu_count = 0; }
};

int main() {
  ImuPreintegrator p;
  CHECK(p.add({1000000000, {1,0,0}, {0,0,0}}));
  CHECK(p.add({1010000000, {1,0,0}, {0,0,0}}));
  CHECK(std::abs(p.value().delta_velocity.x - 0.01) < 1e-9);
  CHECK(std::abs(p.value().delta_position.x - 0.00005) < 1e-9);
  CHECK(!p.add({1010000000, {}, {}}));

  TrackingQualityGate gate;
  CHECK(gate.update(40, 1.0) != TrackingState::kTracking);
  gate.update(40, 1.0);
  CHECK(gate.update(40, 1.0) == TrackingState::kTracking);
  for (int i=0; i<5; ++i) gate.update(1, 20.0);
  CHECK(gate.state() == TrackingState::kLost);

  auto fake = std::make_unique<FakeTracker>();
  FakeTracker* raw = fake.get();
  CoreConfig cfg; cfg.camera = {640, 480, 500, 500, 320, 240};
  CoreEngine engine(cfg, std::move(fake));
  CHECK(engine.submitImu({10, {}, {}}));
  CHECK(!engine.submitImu({10, {}, {}}));
  CHECK(engine.submitImu({20, {}, {}}));
  std::uint8_t pixel = 0;
  auto result = engine.processFrame({30, 1, 1, 1, PixelFormat::kGray8, &pixel, 1});
  CHECK(raw->imu_count == 2);
  CHECK(result.tracking.state == TrackingState::kTracking);
  std::cout << "All AR kernel tests passed\n";
  return 0;
}
