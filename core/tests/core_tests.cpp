#include <cmath>
#include <cstdlib>
#include <iostream>
#include <memory>
#include "arkernel/camera_model.h"
#include "arkernel/clock_aligner.h"
#include "arkernel/core_engine.h"
#include "arkernel/feature_detector.h"
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
  // Calibrated projection/unprojection remains numerically consistent.
  PinholeRadTanCamera camera({640,480,510,508,319.5,239.5},
                             {-0.12,0.03,0.001,-0.002,0.0});
  const Vec3 point{0.3,-0.2,2.0};
  const auto pixel = camera.project(point);
  CHECK(pixel.has_value());
  CHECK(camera.isInside(*pixel));
  const auto ray = camera.unproject(*pixel);
  CHECK(ray.has_value());
  CHECK(std::abs(ray->x/ray->z - point.x/point.z) < 1e-7);
  CHECK(std::abs(ray->y/ray->z - point.y/point.z) < 1e-7);
  CHECK(!camera.project({0,0,-1}).has_value());

  // Clock fitting handles boot-time-sized values and one delayed callback.
  ClockAligner aligner(32, 6);
  constexpr TimestampNs base = 1000000000000000LL;
  for (int i=0; i<20; ++i) {
    const TimestampNs source = base + i*10000000LL;
    const TimestampNs outlier = (i == 9) ? 5000000LL : 0LL;
    CHECK(aligner.addObservation(source, source + 123456LL + outlier));
  }
  const auto aligned = aligner.toCoreTime(base + 250000000LL);
  CHECK(aligned.has_value());
  CHECK(std::llabs(*aligned - (base + 250000000LL + 123456LL)) < 100);
  CHECK(!aligner.addObservation(base, base));

  // Synthetic high-contrast rectangles produce bounded, distributed corners.
  std::vector<std::uint8_t> image(80*60, 0);
  for (int y=8; y<22; ++y) for (int x=8; x<22; ++x) image[y*80+x] = 255;
  for (int y=35; y<52; ++y) for (int x=50; x<72; ++x) image[y*80+x] = 255;
  FastGridDetector detector({15,4,3,2,4});
  const auto features = detector.detect({80,60,80,image.data(),image.size()});
  CHECK(features.size() >= 4);
  CHECK(features.size() <= 4u*3u*2u);
  bool left = false, right = false;
  for (const auto& f : features) { left |= f.x < 40; right |= f.x >= 40; }
  CHECK(left && right);

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
  std::uint8_t frame_pixel = 0;
  auto result = engine.processFrame({30, 1, 1, 1, PixelFormat::kGray8, &frame_pixel, 1});
  CHECK(raw->imu_count == 2);
  CHECK(result.tracking.state == TrackingState::kTracking);
  std::cout << "All AR kernel tests passed\n";
  return 0;
}
