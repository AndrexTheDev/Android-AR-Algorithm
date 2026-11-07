#include <cmath>
#include <cstdlib>
#include <iostream>
#include <memory>
#include "arkernel/camera_model.h"
#include "arkernel/clock_aligner.h"
#include "arkernel/core_engine.h"
#include "arkernel/feature_detector.h"
#include "arkernel/epipolar_ransac.h"
#include "arkernel/imu_preintegrator.h"
#include "arkernel/optical_flow.h"
#include "arkernel/motion_prediction.h"
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

  // IMU rotation prediction supplies LK with physically meaningful initial guesses.
  const std::vector<ImageFeature> prediction_features{{320,240,10,1},{420,260,9,2}};
  const auto identity_predictions = predictRotationFlow(prediction_features, camera, {});
  CHECK(std::abs(identity_predictions[0].x-320.0) < 1e-7);
  CHECK(std::abs(identity_predictions[0].y-240.0) < 1e-7);
  const auto rotated_predictions = predictRotationFlow(
      prediction_features, camera, Quaternion::fromRotationVector({0,0.04,0}));
  CHECK(rotated_predictions[0].x > identity_predictions[0].x+15.0);

  // Calibrated epipolar RANSAC recovers static-scene matches despite outliers.
  std::vector<NormalizedCorrespondence> epipolar_matches;
  for (int i=0; i<80; ++i) {
    const Vec3 world{-1.2+0.17*(i%15), -0.7+0.14*((i*7)%11), 3.0+0.11*(i%17)};
    const Vec3 moved = Quaternion::fromRotationVector({0.01,-0.025,0.008}).rotate(world) +
                       Vec3{0.12,-0.015,0.025};
    NormalizedCorrespondence match{{world.x/world.z,world.y/world.z},
                                    {moved.x/moved.z,moved.y/moved.z}};
    if (i >= 60) {
      match.current = {-0.45+0.047*(i-60), 0.38-0.031*((i*3)%17)};
    }
    epipolar_matches.push_back(match);
  }
  EpipolarRansac epipolar({500,0.0005,40,12345});
  const auto verified = epipolar.verify(epipolar_matches);
  CHECK(verified.valid);
  CHECK(verified.inlier_count >= 58);
  CHECK(verified.inlier_count <= 64);
  std::size_t true_inliers = 0, rejected_outliers = 0;
  for (std::size_t i=0; i<verified.inliers.size(); ++i) {
    if (i < 60 && verified.inliers[i]) ++true_inliers;
    if (i >= 60 && !verified.inliers[i]) ++rejected_outliers;
  }
  CHECK(true_inliers >= 58);
  CHECK(rejected_outliers >= 18);
  std::vector<NormalizedCorrespondence> collinear(20);
  for (int i=0; i<20; ++i) {
    const double x=-0.4+i*0.04;
    collinear[i]={{x,2.0*x+0.1},{x+0.02,2.0*(x+0.02)+0.1}};
  }
  CHECK(!epipolar.verify(collinear).valid);

  // Coarse-to-fine replay: a textured frame is translated by (3,2) pixels.
  constexpr int flow_width = 128, flow_height = 96;
  std::vector<std::uint8_t> flow_a(flow_width*flow_height);
  std::vector<std::uint8_t> flow_b(flow_width*flow_height, 0);
  for (int y=0; y<flow_height; ++y) for (int x=0; x<flow_width; ++x) {
    const double value = 127.0 + 42.0*std::sin(x*0.19) + 38.0*std::cos(y*0.23) +
                         30.0*std::sin((x+y)*0.11);
    flow_a[y*flow_width+x] = static_cast<std::uint8_t>(std::max(0.0,std::min(255.0,value)));
  }
  for (int y=2; y<flow_height; ++y) for (int x=3; x<flow_width; ++x)
    flow_b[y*flow_width+x] = flow_a[(y-2)*flow_width+x-3];
  GrayImageView view_a{flow_width,flow_height,flow_width,flow_a.data(),flow_a.size()};
  GrayImageView view_b{flow_width,flow_height,flow_width,flow_b.data(),flow_b.size()};
  PyramidalLucasKanade optical_flow({4,4,20,0.005,1.0,20.0,0.5});
  const auto tracks = optical_flow.track(view_a, view_b, {{64,48,100,77}});
  CHECK(tracks.size() == 1);
  CHECK(tracks[0].status == FlowStatus::kTracked);
  CHECK(std::abs(tracks[0].current.x-67.0) < 0.15);
  CHECK(std::abs(tracks[0].current.y-50.0) < 0.15);
  CHECK(tracks[0].forward_backward_error < 0.15);
  std::vector<std::uint8_t> flat(flow_width*flow_height, 100);
  GrayImageView flat_view{flow_width,flow_height,flow_width,flat.data(),flat.size()};
  const auto flat_track = optical_flow.track(flat_view, flat_view, {{64,48,1,1}});
  CHECK(flat_track[0].status == FlowStatus::kLowTexture);
  const std::vector<ImageFeature> edge_features = {
      {0,0,1,1}, {5,5,1,2}, {127,95,1,3}, {122,90,1,4}};
  const auto edge_tracks = optical_flow.track(view_a, view_b, edge_features);
  CHECK(edge_tracks.size() == edge_features.size());
  for (const auto& track : edge_tracks) CHECK(track.status != FlowStatus::kTracked);

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
