#include <cmath>
#include <cstdlib>
#include <iostream>
#include <memory>
#include "arkernel/c_api.h"
#include "arkernel/camera_model.h"
#include "arkernel/clock_aligner.h"
#include "arkernel/core_engine.h"
#include "arkernel/feature_detector.h"
#include "arkernel/fixed_lag_smoother.h"
#include "arkernel/epipolar_ransac.h"
#include "arkernel/essential_pose.h"
#include "arkernel/geometry_model_selector.h"
#include "arkernel/homography_ransac.h"
#include "arkernel/imu_preintegrator.h"
#include "arkernel/optical_flow.h"
#include "arkernel/motion_prediction.h"
#include "arkernel/tracking_quality.h"
#include "arkernel/trajectory_metrics.h"
#include "arkernel/visual_frontend.h"
#include "arkernel/vio_estimator.h"

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
  std::vector<std::uint8_t> padded_camera_buffer(63*96+80,0);
  CHECK((GrayImageView{80,64,96,padded_camera_buffer.data(),padded_camera_buffer.size()}.valid()));
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
  EssentialPoseSolver pose_solver({30,0.7,0.1});
  const auto relative_pose = pose_solver.solve(
      verified.matrix,epipolar_matches,verified.inliers);
  CHECK(relative_pose.valid);
  CHECK(relative_pose.positive_depth_count >= 55);
  const Vec3 expected_ray = Quaternion::fromRotationVector({0.01,-0.025,0.008})
                                .rotate({0.2,-0.1,1.0});
  const Vec3 recovered_ray = relative_pose.rotation_current_from_previous
                                 .rotate({0.2,-0.1,1.0});
  CHECK((expected_ray-recovered_ray).norm() < 0.03);
  const Vec3 expected_translation = Vec3{0.12,-0.015,0.025} *
      (1.0/Vec3{0.12,-0.015,0.025}.norm());
  CHECK(relative_pose.translation_direction_current.x*expected_translation.x +
        relative_pose.translation_direction_current.y*expected_translation.y +
        relative_pose.translation_direction_current.z*expected_translation.z > 0.9);
  // Two-view translation cannot be trusted when parallax is effectively zero.
  std::vector<NormalizedCorrespondence> tiny_parallax;
  for (int i=0; i<30; ++i) {
    const Vec3 point3d{-1.0+0.08*i,-0.5+0.09*(i%9),3.0+0.07*(i%7)};
    tiny_parallax.push_back({{point3d.x/point3d.z,point3d.y/point3d.z},
                             {(point3d.x+1e-6)/point3d.z,point3d.y/point3d.z}});
  }
  const std::array<double,9> x_translation_essential{0,0,0,0,0,-1,0,1,0};
  CHECK(!pose_solver.solve(x_translation_essential,tiny_parallax).valid);
  std::vector<NormalizedCorrespondence> collinear(20);
  for (int i=0; i<20; ++i) {
    const double x=-0.4+i*0.04;
    collinear[i]={{x,2.0*x+0.1},{x+0.02,2.0*(x+0.02)+0.1}};
  }
  CHECK(!epipolar.verify(collinear).valid);

  // Planar scenes are explained by one homography and must not initialize a
  // falsely confident general-3D translation.
  const std::array<double,9> known_h{1.01,0.03,0.04,-0.02,0.98,-0.025,0.04,-0.03,1.0};
  std::vector<NormalizedCorrespondence> planar_matches;
  for (int i=0; i<65; ++i) {
    const Vec2 source{-0.5+0.09*(i%12),-0.35+0.08*((i*5)%10)};
    const double denominator=known_h[6]*source.x+known_h[7]*source.y+known_h[8];
    Vec2 target{(known_h[0]*source.x+known_h[1]*source.y+known_h[2])/denominator,
                (known_h[3]*source.x+known_h[4]*source.y+known_h[5])/denominator};
    if (i>=50) target={0.4-0.051*(i-50),-0.3+0.039*((i*7)%13)};
    planar_matches.push_back({source,target});
  }
  HomographyRansac homography({400,0.0001,35,9876});
  const auto planar_h=homography.verify(planar_matches);
  CHECK(planar_h.valid);
  CHECK(planar_h.inlier_count>=49 && planar_h.inlier_count<=53);
  const auto planar_e=epipolar.verify(planar_matches);
  CHECK(planar_e.valid);
  GeometryModelSelector model_selector({0.85,8});
  CHECK(model_selector.select(planar_e,planar_h).geometry==
        SceneGeometry::kPlanarOrRotationDominant);
  const auto general_h=homography.verify(epipolar_matches);
  CHECK(model_selector.select(verified,general_h).geometry==SceneGeometry::kGeneral3d);
  CHECK(!homography.verify(collinear).valid);

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

  // Stateful frontend keeps IDs, classifies image translation as planar, and
  // reinitializes instead of propagating stale state over a camera time gap.
  std::vector<std::uint8_t> frontend_a(flow_width*flow_height);
  std::vector<std::uint8_t> frontend_b(flow_width*flow_height,0);
  for (int y=0;y<flow_height;++y) for (int x=0;x<flow_width;++x) {
    const int checker=((x/8)+(y/8))&1;
    frontend_a[y*flow_width+x]=static_cast<std::uint8_t>(35+checker*170+((x*7+y*3)%29));
  }
  for (int y=2;y<flow_height;++y) for (int x=3;x<flow_width;++x)
    frontend_b[y*flow_width+x]=frontend_a[(y-2)*flow_width+x-3];
  PinholeRadTanCamera frontend_camera({flow_width,flow_height,110,110,64,48});
  VisualFrontendConfig frontend_config;
  frontend_config.detector={12,8,6,6,4};
  frontend_config.optical_flow={4,4,20,0.005,2.0,30.0,0.8};
  frontend_config.epipolar={300,0.008,12,44};
  frontend_config.homography={300,0.008,10,55};
  frontend_config.min_tracked_features=10;
  frontend_config.max_active_features=180;
  VisualFrontend frontend(frontend_camera,frontend_config);
  GrayImageView frontend_view_a{flow_width,flow_height,flow_width,frontend_a.data(),frontend_a.size()};
  GrayImageView frontend_view_b{flow_width,flow_height,flow_width,frontend_b.data(),frontend_b.size()};
  const auto frontend_first=frontend.process(frontend_view_a,1000000000LL);
  CHECK(frontend_first.state==FrontendState::kInitializing);
  CHECK(frontend_first.active_feature_count>=20);
  std::vector<std::uint32_t> first_feature_ids;
  for (const auto& feature:frontend.activeFeatures()) first_feature_ids.push_back(feature.id);
  const auto frontend_second=frontend.process(frontend_view_b,1033000000LL);
  CHECK(frontend_second.state==FrontendState::kTracking);
  CHECK(frontend_second.geometry.geometry==SceneGeometry::kPlanarOrRotationDominant);
  CHECK(frontend_second.accepted_track_count>=10);
  CHECK(frontend_second.accepted_feature_ids.size()==frontend_second.accepted_track_count);
  for (auto id:frontend_second.accepted_feature_ids)
    CHECK(std::find(first_feature_ids.begin(),first_feature_ids.end(),id)!=first_feature_ids.end());
  CHECK(frontend_second.active_feature_count>=frontend_second.accepted_track_count);
  const auto frontend_gap=frontend.process(frontend_view_b,2000000000LL);
  CHECK(frontend_gap.state==FrontendState::kReinitialized);
  CHECK(frontend_gap.keyframe);
  const auto frontend_collapse=frontend.process(flat_view,2033000000LL);
  CHECK(frontend_collapse.state==FrontendState::kReinitialized);
  CHECK(frontend_collapse.accepted_track_count<frontend_config.min_tracked_features);

  ark_visual_config_v1 visual_c_config{};
  CHECK(ark_visual_default_config(flow_width,flow_height,110,110,64,48,
                                  &visual_c_config)==ARK_RESULT_OK);
  visual_c_config.min_tracked_features=10;
  visual_c_config.max_active_features=180;
  ark_visual_handle* visual_c_handle=nullptr;
  CHECK(ark_visual_create(&visual_c_config,&visual_c_handle)==ARK_RESULT_OK);
  ark_gray_frame_v1 c_frame{};
  c_frame.struct_size=sizeof(c_frame);c_frame.width=flow_width;c_frame.height=flow_height;
  c_frame.row_stride=flow_width;c_frame.data_size=frontend_a.size();
  ark_visual_result_v1 c_visual_result{};c_visual_result.struct_size=sizeof(c_visual_result);
  c_frame.timestamp_ns=9000000000LL;c_frame.data=frontend_a.data();
  CHECK(ark_visual_process_gray(visual_c_handle,&c_frame,&c_visual_result)==ARK_RESULT_OK);
  CHECK(c_visual_result.state==ARK_FRONTEND_INITIALIZING);
  c_frame.timestamp_ns+=33000000LL;c_frame.data=frontend_b.data();
  CHECK(ark_visual_process_gray(visual_c_handle,&c_frame,&c_visual_result)==ARK_RESULT_OK);
  CHECK(c_visual_result.state==ARK_FRONTEND_TRACKING);
  CHECK(c_visual_result.geometry==ARK_GEOMETRY_PLANAR_OR_ROTATION);
  CHECK(ark_visual_process_gray(visual_c_handle,&c_frame,&c_visual_result)==ARK_RESULT_OUT_OF_ORDER);
  ark_visual_destroy(visual_c_handle);

  // VIO starts only from a stationary window, propagates metric inertial state,
  // and accepts monocular scale only when inertial displacement agrees.
  VioConfig vio_config;
  vio_config.min_scale_displacement_m=0.001;
  vio_config.visual_rotation_gain=0.5;
  VioEstimator vio(vio_config);
  constexpr double gravity=9.80665;
  const TimestampNs vio_start=3000000000LL;
  for (int i=0;i<80;++i)
    vio.submitImu({vio_start+i*10000000LL,{0,0,gravity},{0,0,0}});
  CHECK(vio.initialized());
  CHECK(vio.state().status==VioStatus::kInertial);
  CHECK(vio.state().gyroscope_bias_rps.norm()<1e-12);
  RelativePoseEstimate visual_reference;
  visual_reference.valid=true;
  visual_reference.rotation_current_from_previous={};
  visual_reference.translation_direction_current={-1,0,0};
  CHECK(!vio.fuseVisual(vio.state().timestamp_ns,visual_reference).rotation_applied);
  const TimestampNs initialized_at=vio.state().timestamp_ns;
  for (int i=1;i<=10;++i)
    CHECK(vio.submitImu({initialized_at+i*10000000LL,{1,0,gravity},{0,0,0}}));
  RelativePoseEstimate visual_motion=visual_reference;
  visual_motion.rotation_current_from_previous=Quaternion::fromRotationVector({0,0,-0.1});
  const auto fused=vio.fuseVisual(vio.state().timestamp_ns,visual_motion);
  CHECK(fused.rotation_applied);
  CHECK(fused.scale_updated && fused.position_applied);
  CHECK(std::abs(fused.observed_scale_m-0.005)<0.001);
  CHECK(vio.state().visual_translation_scale_valid);
  CHECK(vio.state().world_from_body.rotation.rotate({1,0,0}).y>0.03);

  VioEstimator moving_initializer;
  for (int i=0;i<100;++i)
    moving_initializer.submitImu({vio_start+i*10000000LL,{0,0,gravity},
                                  {0.2*(i&1?1.0:-1.0),0,0}});
  CHECK(!moving_initializer.initialized());

  // Versioned C ABI owns exceptions, validates struct sizes and preserves
  // nanosecond timestamps without converting them to floating point.
  CHECK(ark_c_api_version()==ARKERNEL_C_API_VERSION);
  ark_vio_config_v1 c_config{};
  CHECK(ark_vio_default_config(&c_config)==ARK_RESULT_OK);
  c_config.initialization_samples=10;
  c_config.initialization_duration_s=0.08;
  ark_vio_handle* c_handle=nullptr;
  CHECK(ark_vio_create(&c_config,&c_handle)==ARK_RESULT_OK);
  CHECK(c_handle!=nullptr);
  ark_imu_sample_v1 c_sample{};
  c_sample.struct_size=sizeof(c_sample);
  c_sample.acceleration_z_mps2=gravity;
  for (int i=0;i<10;++i) {
    c_sample.timestamp_ns=7000000000LL+i*10000000LL;
    const auto result=ark_vio_submit_imu(c_handle,&c_sample);
    CHECK(result==(i==9?ARK_RESULT_OK:ARK_RESULT_NOT_READY));
  }
  CHECK(ark_vio_submit_imu(c_handle,&c_sample)==ARK_RESULT_OUT_OF_ORDER);
  char c_error[64]{};
  CHECK(ark_vio_copy_last_error(c_handle,c_error,sizeof(c_error))>1);
  CHECK(std::string(c_error).find("timestamp")!=std::string::npos);
  ark_vio_state_v1 c_state{};
  c_state.struct_size=sizeof(c_state);
  CHECK(ark_vio_get_state(c_handle,&c_state)==ARK_RESULT_OK);
  CHECK(c_state.status==ARK_VIO_INERTIAL);
  CHECK(c_state.timestamp_ns==c_sample.timestamp_ns);
  CHECK(ark_vio_reset(c_handle)==ARK_RESULT_OK);
  CHECK(ark_vio_get_state(c_handle,&c_state)==ARK_RESULT_NOT_READY);
  ark_vio_destroy(c_handle);

  // The fixed-lag window reduces adjacent IMU/vision residuals and remains
  // bounded by promoting an optimized state to the prior on overflow.
  FixedLagConfig smoother_config;
  smoother_config.max_keyframes=4;
  smoother_config.optimization_iterations=10;
  FixedLagSmoother smoother(smoother_config);
  RelativePoseEstimate forward_visual;
  forward_visual.valid=true;
  forward_visual.rotation_current_from_previous={};
  forward_visual.translation_direction_current={-1,0,0};
  PreintegratedImu constant_velocity_imu;
  constant_velocity_imu.duration_s=0.1;
  constant_velocity_imu.delta_rotation={};
  constant_velocity_imu.delta_velocity={0,0,gravity*0.1};
  constant_velocity_imu.delta_position={0,0,0.5*gravity*0.01};
  constant_velocity_imu.sample_count=2;
  for (int i=0;i<6;++i) {
    VioState keyframe;
    keyframe.timestamp_ns=5000000000LL+i*100000000LL;
    keyframe.world_from_body.translation={0.1*i,(i&1)?-0.18:0.18,0};
    keyframe.world_from_body.rotation=Quaternion::fromRotationVector(
        {0,0,(i&1)?-0.04:0.04});
    keyframe.velocity_world_mps={1,0,0};
    keyframe.status=VioStatus::kVisualInertial;
    CHECK(smoother.addKeyframe(keyframe,
          i==0?std::optional<PreintegratedImu>{}:constant_velocity_imu,
          i==0?std::optional<RelativePoseEstimate>{}:forward_visual));
  }
  CHECK(smoother.keyframes().size()==4);
  CHECK(smoother.marginalizedCount()==2);
  const auto smoothing=smoother.optimize();
  CHECK(smoothing.imu_factor_count==3);
  CHECK(smoothing.visual_factor_count==3);
  CHECK(smoothing.final_cost<smoothing.initial_cost*0.2);
  CHECK(smoothing.marginalized_keyframe_count==2);

  // Sim(3) trajectory alignment recovers known scale, rigid transform and RPE.
  std::vector<TrajectorySample> estimated_trajectory,ground_truth_trajectory;
  const Quaternion trajectory_alignment=Quaternion::fromRotationVector({0.2,-0.1,0.3});
  const Vec3 trajectory_offset{1.5,-0.8,0.4};
  for(int i=0;i<20;++i){
    TrajectorySample estimate;estimate.timestamp_ns=8000000000LL+i*50000000LL;
    estimate.world_from_body.translation={0.1*i,std::sin(i*0.2),0.03*i*i};
    estimate.world_from_body.rotation=Quaternion::fromRotationVector({0.01*i,-0.005*i,0.008*i});
    estimated_trajectory.push_back(estimate);
    TrajectorySample truth;truth.timestamp_ns=estimate.timestamp_ns+1000000LL;
    truth.world_from_body.translation=trajectory_alignment.rotate(
        estimate.world_from_body.translation)*2.0+trajectory_offset;
    truth.world_from_body.rotation=(trajectory_alignment*estimate.world_from_body.rotation).normalized();
    ground_truth_trajectory.push_back(truth);
  }
  const auto sim3_metrics=evaluateTrajectory(estimated_trajectory,ground_truth_trajectory,
      {TrajectoryAlignment::kSim3,2000000LL,2});
  CHECK(sim3_metrics.valid);
  CHECK(sim3_metrics.associated_samples==20);
  CHECK(std::abs(sim3_metrics.alignment_scale-2.0)<1e-8);
  CHECK(sim3_metrics.ate_rmse_m<1e-8);
  CHECK(sim3_metrics.rpe_translation_rmse_m<1e-8);
  CHECK(sim3_metrics.rpe_rotation_rmse_degrees<1e-6);
  const auto se3_metrics=evaluateTrajectory(estimated_trajectory,ground_truth_trajectory,
      {TrajectoryAlignment::kSe3,2000000LL,1});
  CHECK(se3_metrics.valid);
  CHECK(se3_metrics.ate_rmse_m>0.5);

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
