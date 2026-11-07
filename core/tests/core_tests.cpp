#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <memory>
#include "arkernel/c_api.h"
#include "arkernel/camera_model.h"
#include "arkernel/clock_aligner.h"
#include "arkernel/core_engine.h"
#include "arkernel/depth_sampling.h"
#include "arkernel/feature_detector.h"
#include "arkernel/fixed_lag_smoother.h"
#include "arkernel/epipolar_ransac.h"
#include "arkernel/essential_pose.h"
#include "arkernel/geometry_model_selector.h"
#include "arkernel/homography_ransac.h"
#include "arkernel/image_descriptor.h"
#include "arkernel/imu_preintegrator.h"
#include "arkernel/optical_flow.h"
#include "arkernel/relocalization.h"
#include "arkernel/semantic_map.h"
#include "arkernel/sparse_map.h"
#include "arkernel/spatial_snapshot.h"
#include "arkernel/surfel_map.h"
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

  const PinholeRadTanCamera depth_camera({8,8,8,8,3.5,3.5});
  std::vector<float> metric_depth(8*8,2.0F),depth_confidence(8*8,0.9F);
  metric_depth[0]=std::numeric_limits<float>::quiet_NaN();
  const MetricDepthImageView depth_view{8,8,8,metric_depth.data(),metric_depth.size(),
    depth_confidence.data(),8,depth_confidence.size()};
  DepthSamplingConfig depth_sampling_config;depth_sampling_config.pixel_stride=2;
  const auto sampled_depth=sampleMetricDepthImage(
      depth_view,depth_camera,2000000000LL,depth_sampling_config);
  CHECK(sampled_depth.size()==15);
  CHECK(std::abs(sampled_depth.front().position_world_m.z-2.0)<1e-9);
  CHECK(sampled_depth.front().radius_m>=depth_sampling_config.min_radius_m);
  CHECK(std::any_of(sampled_depth.begin(),sampled_depth.end(),
                    [](const auto& value){return value.normal_valid;}));
  auto invalid_depth_view=depth_view;invalid_depth_view.row_stride_elements=7;
  CHECK(sampleMetricDepthImage(invalid_depth_view,depth_camera,2000000000LL).empty());

  std::vector<std::uint8_t> descriptor_image(96*80);
  for(int y=0;y<80;++y)for(int x=0;x<96;++x)
    descriptor_image[y*96+x]=static_cast<std::uint8_t>((x*17+y*31+(x*y)%97)&255);
  const GrayImageView descriptor_view{96,80,96,descriptor_image.data(),descriptor_image.size()};
  const std::vector<ImageFeature> descriptor_features{{30,30,1,11},{65,45,1,22},{2,2,1,33}};
  const auto descriptors=describeFeatures(descriptor_view,descriptor_features);
  CHECK(descriptors.size()==2);
  CHECK(descriptorHammingDistance(descriptors[0],descriptors[0])==0);
  auto trained_descriptors=descriptors;trained_descriptors[0].feature_id=101;
  trained_descriptors[1].feature_id=202;
  const auto descriptor_matches=matchDescriptors(descriptors,trained_descriptors);
  CHECK(descriptor_matches.size()==2);
  CHECK(descriptor_matches[0].query_feature_id==11&&descriptor_matches[0].train_feature_id==101);
  LandmarkDescriptorStore descriptor_store;
  auto noisy_descriptor=descriptors[0];noisy_descriptor.bytes[0]^=0x01;
  descriptor_store.integrate({descriptors[0]},{11});
  CHECK(descriptor_store.snapshot().empty());
  descriptor_store.integrate({noisy_descriptor},{11});
  descriptor_store.integrate({descriptors[0]},{11});
  const auto aggregated_descriptors=descriptor_store.snapshot();
  CHECK(aggregated_descriptors.size()==1);
  CHECK(aggregated_descriptors[0].bytes==descriptors[0].bytes);
  descriptor_store.integrate({descriptors[1]},{22});
  CHECK(descriptor_store.size()==1&&descriptor_store.snapshot().empty());
  CHECK(descriptor_store.restore(aggregated_descriptors,{11}));
  const auto restored_descriptors=descriptor_store.snapshot();
  CHECK(restored_descriptors.size()==1&&restored_descriptors[0].feature_id==11&&
        restored_descriptors[0].bytes==aggregated_descriptors[0].bytes);

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
  const auto frontend_map_observations=makeLandmarkObservations(
      frontend_second,frontend_camera,{});
  CHECK(frontend_map_observations.size()==frontend_second.accepted_track_count);
  const auto frontend_gap=frontend.process(frontend_view_b,2000000000LL);
  CHECK(frontend_gap.state==FrontendState::kReinitialized);
  CHECK(frontend_gap.keyframe);
  const auto frontend_collapse=frontend.process(flat_view,2033000000LL);
  CHECK(frontend_collapse.state==FrontendState::kReinitialized);
  CHECK(frontend_collapse.accepted_track_count<frontend_config.min_tracked_features);
  frontend.reset();
  CHECK(frontend.reserveFeatureIdsAbove(5000));
  const auto reserved_ids_frame=frontend.process(frontend_view_a,3000000000LL);
  CHECK(reserved_ids_frame.state==FrontendState::kInitializing);
  CHECK(!frontend.activeFeatures().empty());
  CHECK(frontend.activeFeatures().front().id>5000);
  CHECK(!frontend.reserveFeatureIdsAbove(std::numeric_limits<std::uint32_t>::max()));

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
  ark_mapping_gate_v1 c_mapping_gate{};c_mapping_gate.struct_size=sizeof(c_mapping_gate);
  CHECK(ark_visual_update_mapping_gate(visual_c_handle,0,0,1,30,0,
                                        &c_mapping_gate)==ARK_RESULT_OK);
  CHECK(c_mapping_gate.state==ARK_MAPPING_PAUSED &&
        c_mapping_gate.reason==ARK_MAPPING_REASON_POSE_UNINITIALIZED);
  for(int i=0;i<3;++i)
    CHECK(ark_visual_update_mapping_gate(visual_c_handle,1,0,1,30,0,
                                          &c_mapping_gate)==ARK_RESULT_OK);
  CHECK(c_mapping_gate.state==ARK_MAPPING_ACTIVE && c_mapping_gate.integrate);
  ark_world_pose_v1 c_world_pose{};c_world_pose.struct_size=sizeof(c_world_pose);
  c_world_pose.timestamp_ns=c_frame.timestamp_ns;c_world_pose.rotation_w=1;
  ark_map_update_v1 c_map_update{};c_map_update.struct_size=sizeof(c_map_update);
  CHECK(ark_visual_integrate_map(visual_c_handle,&c_world_pose,&c_map_update)==ARK_RESULT_OK);
  CHECK(c_map_update.timestamp_ns==c_frame.timestamp_ns);
  CHECK(ark_visual_integrate_map(visual_c_handle,&c_world_pose,&c_map_update)==ARK_RESULT_OUT_OF_ORDER);
  std::size_t c_plane_count=99;
  CHECK(ark_visual_get_plane_count(visual_c_handle,&c_plane_count)==ARK_RESULT_OK);
  CHECK(c_plane_count==0);
  ark_plane_v1 c_plane{};c_plane.struct_size=sizeof(c_plane);
  CHECK(ark_visual_get_plane(visual_c_handle,0,&c_plane)==ARK_RESULT_NOT_READY);
  std::uint32_t c_plane_semantic=99;
  CHECK(ark_visual_get_plane_semantic(visual_c_handle,0,&c_plane_semantic)==ARK_RESULT_NOT_READY);
  ark_room_layout_v1 c_room_layout{};c_room_layout.struct_size=sizeof(c_room_layout);
  CHECK(ark_visual_get_room_layout(visual_c_handle,&c_room_layout)==ARK_RESULT_OK);
  CHECK(!c_room_layout.has_floor && !c_room_layout.has_ceiling && c_room_layout.wall_count==0);
  ark_surfel_observation_v1 c_surfel{};c_surfel.struct_size=sizeof(c_surfel);
  c_surfel.timestamp_ns=c_world_pose.timestamp_ns;c_surfel.position_z_camera_m=2;
  c_surfel.normal_z_camera=1;c_surfel.confidence=0.9;c_surfel.radius_m=0.03;
  c_surfel.normal_valid=1;
  ark_surfel_update_v1 c_surfel_update{};c_surfel_update.struct_size=sizeof(c_surfel_update);
  CHECK(ark_visual_integrate_surfels(visual_c_handle,&c_surfel,1,&c_world_pose,
      c_surfel.timestamp_ns,&c_surfel_update)==ARK_RESULT_OK);
  CHECK(c_surfel_update.created==0&&c_surfel_update.surfel_count==0);
  CHECK(ark_visual_integrate_surfels(visual_c_handle,&c_surfel,1,&c_world_pose,
      c_surfel.timestamp_ns,&c_surfel_update)==ARK_RESULT_OK);
  CHECK(c_surfel_update.created==1&&c_surfel_update.surfel_count==1);
  std::size_t c_surfel_count=0;CHECK(ark_visual_get_surfel_count(visual_c_handle,&c_surfel_count)==ARK_RESULT_OK);
  CHECK(c_surfel_count==1);
  ark_surfel_v1 c_surfel_snapshot{};c_surfel_snapshot.struct_size=sizeof(c_surfel_snapshot);
  CHECK(ark_visual_get_surfel(visual_c_handle,0,&c_surfel_snapshot)==ARK_RESULT_OK);
  CHECK(c_surfel_snapshot.observation_count==2&&c_surfel_snapshot.normal_valid);
  std::vector<float> c_depth(static_cast<std::size_t>(flow_width*flow_height),2.0F);
  ark_metric_depth_image_v1 c_depth_image{};c_depth_image.struct_size=sizeof(c_depth_image);
  c_depth_image.width=flow_width;c_depth_image.height=flow_height;
  c_depth_image.row_stride_elements=flow_width;c_depth_image.depth_m=c_depth.data();
  c_depth_image.depth_element_count=c_depth.size();c_depth_image.timestamp_ns=c_world_pose.timestamp_ns;
  c_depth_image.pixel_stride=32;c_depth_image.min_depth_m=0.15;c_depth_image.max_depth_m=20;
  c_depth_image.min_confidence=0.2;c_depth_image.estimate_normals=1;
  CHECK(ark_visual_integrate_depth_image(visual_c_handle,&c_depth_image,&c_world_pose,
      c_depth_image.timestamp_ns,&c_surfel_update)==ARK_RESULT_OK);
  CHECK(ark_visual_integrate_depth_image(visual_c_handle,&c_depth_image,&c_world_pose,
      c_depth_image.timestamp_ns,&c_surfel_update)==ARK_RESULT_OK);
  CHECK(c_surfel_update.created>0&&c_surfel_update.surfel_count>1);
  ark_surfel_hit_v1 c_surfel_hit{};c_surfel_hit.struct_size=sizeof(c_surfel_hit);
  CHECK(ark_visual_raycast_surfel(visual_c_handle,0,0,0,0,0,1,0.05,10,
      &c_surfel_hit)==ARK_RESULT_OK);
  CHECK(c_surfel_hit.surfel_id!=0&&c_surfel_hit.distance_m>=1.5&&
        c_surfel_hit.distance_m<=2.0);
  CHECK(ark_visual_raycast_surfel(visual_c_handle,0,0,0,0,0,-1,0.05,10,
      &c_surfel_hit)==ARK_RESULT_NOT_READY);
  ark_semantic_projection_v1 c_projection{};c_projection.struct_size=sizeof(c_projection);
  c_projection.class_id=5;c_projection.source_track_id=77;c_projection.timestamp_ns=c_frame.timestamp_ns;
  c_projection.pixel_x=64;c_projection.pixel_y=48;c_projection.confidence=0.9;
  c_projection.surface=ARK_SEMANTIC_SURFACE_ANY;c_projection.min_distance_m=0.05;
  c_projection.max_distance_m=20;
  ark_semantic_update_v1 c_semantic_update{};c_semantic_update.struct_size=sizeof(c_semantic_update);
  CHECK(ark_visual_project_semantic(visual_c_handle,&c_projection,&c_world_pose,
      c_projection.timestamp_ns,&c_semantic_update)==ARK_RESULT_NOT_READY);
  ark_semantic_observation_v1 c_semantic{};c_semantic.struct_size=sizeof(c_semantic);
  c_semantic.class_id=5;c_semantic.source_track_id=77;c_semantic.timestamp_ns=c_frame.timestamp_ns;
  c_semantic.position_z_m=2;c_semantic.confidence=0.9;
  CHECK(ark_visual_integrate_semantics(visual_c_handle,&c_semantic,1,c_semantic.timestamp_ns,
                                        &c_semantic_update)==ARK_RESULT_OK);
  CHECK(c_semantic_update.object_count==0);
  c_semantic.timestamp_ns+=1;
  CHECK(ark_visual_integrate_semantics(visual_c_handle,&c_semantic,1,c_semantic.timestamp_ns,
                                        &c_semantic_update)==ARK_RESULT_OK);
  CHECK(c_semantic_update.created==1 && c_semantic_update.object_count==1);
  std::size_t c_semantic_count=0;
  CHECK(ark_visual_get_semantic_object_count(visual_c_handle,&c_semantic_count)==ARK_RESULT_OK);
  CHECK(c_semantic_count==1);
  ark_semantic_object_v1 c_semantic_object{};c_semantic_object.struct_size=sizeof(c_semantic_object);
  CHECK(ark_visual_get_semantic_object(visual_c_handle,0,&c_semantic_object)==ARK_RESULT_OK);
  CHECK(c_semantic_object.class_id==5 && c_semantic_object.observation_count==2);
  ark_plane_hit_v1 c_plane_hit{};c_plane_hit.struct_size=sizeof(c_plane_hit);
  CHECK(ark_visual_raycast_plane(visual_c_handle,0,0,0,0,0,1,0.05,10,
                                  &c_plane_hit)==ARK_RESULT_NOT_READY);
  std::uint64_t c_anchor_id=0;
  CHECK(ark_visual_create_anchor(visual_c_handle,&c_world_pose,&c_anchor_id)==ARK_RESULT_OK);
  CHECK(c_anchor_id!=0);
  ark_anchor_v1 c_anchor{};c_anchor.struct_size=sizeof(c_anchor);
  CHECK(ark_visual_get_anchor(visual_c_handle,c_anchor_id,&c_anchor)==ARK_RESULT_OK);
  CHECK(c_anchor.created_ns==c_world_pose.timestamp_ns);
  std::size_t c_snapshot_size=0;
  CHECK(ark_visual_export_snapshot(visual_c_handle,c_frame.timestamp_ns+1,nullptr,0,
                                   &c_snapshot_size)==ARK_RESULT_OK);
  CHECK(c_snapshot_size>32);
  std::vector<std::uint8_t> c_snapshot(c_snapshot_size);
  CHECK(ark_visual_export_snapshot(visual_c_handle,c_frame.timestamp_ns+1,c_snapshot.data(),
                                   c_snapshot.size()-1,&c_snapshot_size)==ARK_RESULT_INVALID_ARGUMENT);
  CHECK(ark_visual_export_snapshot(visual_c_handle,c_frame.timestamp_ns+1,c_snapshot.data(),
                                   c_snapshot.size(),&c_snapshot_size)==ARK_RESULT_OK);
  CHECK(ark_visual_remove_anchor(visual_c_handle,c_anchor_id)==ARK_RESULT_OK);
  CHECK(ark_visual_get_anchor(visual_c_handle,c_anchor_id,&c_anchor)==ARK_RESULT_NOT_READY);
  CHECK(ark_visual_restore_snapshot(visual_c_handle,c_snapshot.data(),c_snapshot.size(),c_frame.timestamp_ns+2)==ARK_RESULT_OK);
  CHECK(ark_visual_get_anchor(visual_c_handle,c_anchor_id,&c_anchor)==ARK_RESULT_OK);
  std::vector<ark_relocalization_correspondence_v1> c_correspondences;
  for(const auto& point:std::vector<Vec3>{{0,0,0},{1,0,0},{0,1,0},{0,0,1},
      {1,1,0},{1,0,1},{0,1,1},{1,1,1}}){
    ark_relocalization_correspondence_v1 value{};value.struct_size=sizeof(value);
    value.saved_x_m=point.x;value.saved_y_m=point.y;value.saved_z_m=point.z;
    value.current_x_m=point.x;value.current_y_m=point.y;value.current_z_m=point.z;
    c_correspondences.push_back(value);
  }
  ark_relocalization_result_v1 c_relocalization{};
  c_relocalization.struct_size=sizeof(c_relocalization);
  CHECK(ark_visual_restore_snapshot_matched(visual_c_handle,c_snapshot.data(),c_snapshot.size(),
      c_frame.timestamp_ns+3,&c_relocalization)==ARK_RESULT_NOT_READY);
  CHECK(ark_visual_restore_snapshot_relocalized(visual_c_handle,c_snapshot.data(),c_snapshot.size(),
      c_correspondences.data(),c_correspondences.size(),c_frame.timestamp_ns+3,
      &c_relocalization)==ARK_RESULT_OK);
  CHECK(c_relocalization.inlier_count==c_correspondences.size()&&c_relocalization.rmse_m<1e-8);
  c_snapshot[12]^=0x01;
  CHECK(ark_visual_restore_snapshot(visual_c_handle,c_snapshot.data(),c_snapshot.size(),c_frame.timestamp_ns+2)==
        ARK_RESULT_INVALID_ARGUMENT);
  CHECK(ark_visual_get_anchor(visual_c_handle,c_anchor_id,&c_anchor)==ARK_RESULT_OK);
  c_frame.timestamp_ns+=33000000LL;c_frame.data=frontend_a.data();
  CHECK(ark_visual_process_gray(visual_c_handle,&c_frame,&c_visual_result)==ARK_RESULT_OK);
  CHECK(c_visual_result.state==ARK_FRONTEND_INITIALIZING);
  CHECK(ark_visual_reset(visual_c_handle)==ARK_RESULT_OK);
  CHECK(ark_visual_get_semantic_object_count(visual_c_handle,&c_semantic_count)==ARK_RESULT_OK);
  CHECK(c_semantic_count==0);
  CHECK(ark_visual_get_surfel_count(visual_c_handle,&c_surfel_count)==ARK_RESULT_OK);
  CHECK(c_surfel_count==0);
  CHECK(ark_visual_get_anchor(visual_c_handle,c_anchor_id,&c_anchor)==ARK_RESULT_NOT_READY);
  ark_visual_destroy(visual_c_handle);

  // VIO starts only from a stationary window, propagates metric inertial state,
  // and accepts monocular scale only when inertial displacement agrees.
  // Stable feature IDs become metric landmarks, a plane and persistent anchors.
  SparseLandmarkMap sparse_map;
  std::vector<LandmarkObservation> map_first,map_second,map_third;
  const Pose map_camera_first{{},{0,0,0}};
  const Pose map_camera_second{{},{0.2,0,0}};
  const Pose map_camera_third{{},{0.4,0,0}};
  for(std::uint32_t id=1;id<=20;++id){
    const Vec3 point{-0.8+0.08*id,-0.5+0.1*(id%10),3.0};
    map_first.push_back({id,2100000000LL,point*(1.0/point.norm()),map_camera_first});
    const Vec3 ray2=point-map_camera_second.translation;
    map_second.push_back({id,2200000000LL,ray2*(1.0/ray2.norm()),map_camera_second});
    const Vec3 ray3=point-map_camera_third.translation;
    map_third.push_back({id,2300000000LL,ray3*(1.0/ray3.norm()),map_camera_third});
  }
  CHECK(sparse_map.integrate(map_first,2100000000LL).created==0);
  CHECK(sparse_map.integrate(map_second,2200000000LL).created==0);
  CHECK(sparse_map.size()==0);
  const auto map_update=sparse_map.integrate(map_third,2300000000LL);
  CHECK(map_update.created==20);
  CHECK(sparse_map.size()==20);
  SparseLandmarkMap unconfirmed_dynamic_map;
  const Vec3 candidate_point{0,0,3};
  const Vec3 candidate_ray2=candidate_point-map_camera_second.translation;
  CHECK(unconfirmed_dynamic_map.integrate({{99,2100000000LL,{0,0,1},map_camera_first}},
                                           2100000000LL).created==0);
  CHECK(unconfirmed_dynamic_map.integrate({{99,2200000000LL,
      candidate_ray2*(1.0/candidate_ray2.norm()),map_camera_second}},2200000000LL).created==0);
  const auto unconfirmed_update=unconfirmed_dynamic_map.integrate(
      {{99,2300000000LL,{1,0,0},map_camera_third}},2300000000LL);
  CHECK(unconfirmed_update.created==0 && unconfirmed_update.rejected==1);
  CHECK(unconfirmed_dynamic_map.size()==0);
  const auto landmark=sparse_map.find(7);
  CHECK(landmark.has_value());
  CHECK(landmark->observation_count==3);
  CHECK(std::abs(landmark->position_world_m.z-3.0)<1e-8);
  SparseLandmarkMap dynamic_rejection_map;
  CHECK(dynamic_rejection_map.restore({*landmark}));
  MapUpdate dynamic_update;
  for(int i=1;i<=3;++i){
    const LandmarkObservation inconsistent{7,2300000000LL+i*10000000LL,{1,0,0},map_camera_first};
    dynamic_update=dynamic_rejection_map.integrate({inconsistent},inconsistent.timestamp_ns);
  }
  CHECK(dynamic_update.dynamic_pruned==1);
  CHECK(dynamic_rejection_map.size()==0);

  SurfelMap surfel_map;
  const SurfelObservation surface_first{{1.01,2.01,3.01},{0,0,1},0.8,0.03,
                                         2400000000LL,true};
  auto surfel_update=surfel_map.integrate({surface_first},surface_first.timestamp_ns);
  CHECK(surfel_update.created==0&&surfel_map.size()==0&&surfel_map.snapshot().empty());
  auto surface_second=surface_first;surface_second.position_world_m={1.02,2.02,3.02};
  surface_second.normal_world={0,0,-1};surface_second.confidence=1.0;surface_second.timestamp_ns+=10000000LL;
  surfel_update=surfel_map.integrate({surface_second},surface_second.timestamp_ns);
  CHECK(surfel_update.created==1&&surfel_map.size()==1);
  const auto fused_surfels=surfel_map.snapshot();
  CHECK(fused_surfels.size()==1&&fused_surfels[0].observation_count==2);
  CHECK(fused_surfels[0].position_world_m.x>1.015&&fused_surfels[0].position_world_m.x<1.016);
  CHECK(fused_surfels[0].normal_valid&&std::abs(fused_surfels[0].normal_world.z-1)<1e-9);
  const auto surfel_hit=surfel_map.raycast({0,0,0},fused_surfels[0].position_world_m);
  CHECK(surfel_hit.has_value()&&surfel_hit->surfel_id==fused_surfels[0].id);
  CHECK(std::abs(surfel_hit->distance_m-fused_surfels[0].position_world_m.norm())<1e-8);
  CHECK(!surfel_map.raycast({0,0,0},{0,0,-1}).has_value());
  SurfelMapConfig spherical_config;spherical_config.min_confirming_observations=1;
  SurfelMap spherical_surfel_map(spherical_config);
  SurfelObservation spherical_observation{{0,0,2},{},0.9,0.1,2400000000LL,false};
  spherical_surfel_map.integrate({spherical_observation},spherical_observation.timestamp_ns);
  const auto spherical_hit=spherical_surfel_map.raycast({0,0,0},{0,0,1});
  CHECK(spherical_hit.has_value()&&!spherical_hit->normal_valid);
  CHECK(std::abs(spherical_hit->distance_m-1.975)<1e-8);
  auto stale_surface=surface_second;stale_surface.timestamp_ns=surface_first.timestamp_ns-1;
  CHECK(surfel_map.integrate({stale_surface},surface_second.timestamp_ns).rejected==1);
  auto invalid_surface=surface_second;invalid_surface.confidence=0.1;
  CHECK(surfel_map.integrate({invalid_surface},surface_second.timestamp_ns).rejected==1);
  SurfelMapConfig bounded_surfel_config;bounded_surfel_config.max_surfels=1;
  SurfelMap bounded_surfel_map(bounded_surfel_config);
  bounded_surfel_map.integrate({surface_first},surface_first.timestamp_ns);
  bounded_surfel_map.integrate({surface_second},surface_second.timestamp_ns);
  auto other_surface=surface_second;other_surface.position_world_m={4,4,4};
  CHECK(bounded_surfel_map.integrate({other_surface},other_surface.timestamp_ns).rejected==1);
  CHECK(bounded_surfel_map.size()==1);
  CHECK(surfel_map.integrate({},surface_second.timestamp_ns+31000000000LL).pruned==1);
  CHECK(surfel_map.size()==0);

  SemanticObjectMap semantic_map;
  const SemanticObjectObservation chair_first{7,42,{1,0,0},0.8,2400000000LL,false};
  CHECK(semantic_map.integrate({chair_first},chair_first.timestamp_ns).created==0);
  CHECK(semantic_map.size()==0 && semantic_map.snapshot().empty());
  const SemanticObjectObservation chair_second{7,42,{1.2,0,0},0.9,2500000000LL,false};
  CHECK(semantic_map.integrate({chair_second},chair_second.timestamp_ns).created==1);
  CHECK(semantic_map.size()==1);
  const auto semantic_objects=semantic_map.snapshot();
  CHECK(semantic_objects.size()==1 && semantic_objects.front().class_id==7);
  CHECK(semantic_objects.front().observation_count==2);
  CHECK(semantic_objects.front().position_world_m.x>1.10&&
        semantic_objects.front().position_world_m.x<1.11);
  const SemanticObjectObservation moving_person_first{3,9,{0,2,0},0.95,2600000000LL,true};
  const SemanticObjectObservation moving_person_second{3,9,{0.1,2,0},0.90,2700000000LL,true};
  semantic_map.integrate({moving_person_first},moving_person_first.timestamp_ns);
  CHECK(semantic_map.integrate({moving_person_second},moving_person_second.timestamp_ns).created==1);
  CHECK(semantic_map.size()==2);
  const auto semantic_prune=semantic_map.integrate({},4800000000LL);
  CHECK(semantic_prune.pruned==1 && semantic_map.size()==1);
  CHECK(semantic_map.integrate({{7,-1,{0,0,0},0.1,4900000000LL,false}},
                               4900000000LL).rejected==1);

  SparsePlaneDetector plane_detector({300,0.01,15,123});
  const auto plane=plane_detector.detect(sparse_map.snapshot());
  CHECK(plane.valid && plane.inlier_count==20);
  CHECK(plane.normal_world.z>0.999);
  CHECK(std::abs(plane.distance_from_origin_m+3.0)<1e-8);
  CHECK(plane.boundary_uv.size()>=3 && plane.observed_area_m2>0.1);
  CHECK(classifyPlane(plane,{0,0,1},Vec3{0,0,4})==PlaneSemantic::kFloor);
  CHECK(classifyPlane(plane,{0,0,1},Vec3{0,0,1})==PlaneSemantic::kCeiling);
  PlaneEstimate inverted_plane=plane;inverted_plane.normal_world=inverted_plane.normal_world*-1.0;
  inverted_plane.distance_from_origin_m=-inverted_plane.distance_from_origin_m;
  CHECK(classifyPlane(inverted_plane,{0,0,1},Vec3{0,0,4})==PlaneSemantic::kFloor);
  CHECK(classifyPlane(plane,{0,0,1},std::nullopt)==PlaneSemantic::kUnknown);
  PlaneEstimate sloped_plane;sloped_plane.valid=true;
  sloped_plane.normal_world=Vec3{1,0,1}*(1.0/std::sqrt(2.0));
  CHECK(classifyPlane(sloped_plane,{0,0,1},Vec3{})==PlaneSemantic::kSloped);
  std::vector<SparseLandmark> noisy_plane_points;
  std::uint32_t noisy_id=1;
  for(int row=0;row<9;++row)for(int column=0;column<9;++column){
    const double x=-1.0+0.25*column,y=-1.0+0.25*row;
    const int noise_code=(row*17+column*13)%7-3;
    const double z=1.0+0.2*x-0.1*y+0.003*noise_code;
    noisy_plane_points.push_back({noisy_id++,{x,y,z},3,0.005,2200000000LL});
  }
  for(int i=0;i<15;++i)
    noisy_plane_points.push_back({noisy_id++,{-1.0+0.14*i,0.8,1.5+0.05*i},2,0.01,2200000000LL});
  const auto refined_plane=SparsePlaneDetector({500,0.025,60,456}).detect(noisy_plane_points);
  const Vec3 expected_plane_normal=Vec3{-0.2,0.1,1.0}*(1.0/Vec3{-0.2,0.1,1.0}.norm());
  CHECK(refined_plane.valid && refined_plane.inlier_count==81);
  CHECK(refined_plane.normal_world.x*expected_plane_normal.x+
        refined_plane.normal_world.y*expected_plane_normal.y+
        refined_plane.normal_world.z*expected_plane_normal.z>0.9999);
  CHECK(refined_plane.mean_absolute_error_m<0.007);
  std::vector<SparseLandmark> room_planes;
  std::uint32_t room_id=1000;
  for(int row=0;row<5;++row)for(int column=0;column<5;++column)
    room_planes.push_back({room_id++,{-1.0+0.5*column,-1.0+0.5*row,2.0},3,0.001,2300000000LL});
  for(int row=0;row<5;++row)for(int column=0;column<5;++column)
    room_planes.push_back({room_id++,{2.0,-1.0+0.5*column,0.5+0.5*row},3,0.001,2300000000LL});
  SparsePlaneDetector room_detector({500,0.01,12,789});
  const auto multiple_planes=room_detector.detectMultiple(room_planes,4);
  CHECK(multiple_planes.size()==2);
  CHECK(multiple_planes[0].inlier_count>=20 && multiple_planes[1].inlier_count>=20);
  CHECK(multiple_planes[0].inlier_count+multiple_planes[1].inlier_count==50);
  SparsePlaneTracker plane_tracker({500,0.01,12,789},{4,8.0,0.1,0.5,{}});
  const auto tracked_first=plane_tracker.update(room_planes,{0,0,1},Vec3{0,0,1});
  CHECK(tracked_first.size()==2 && tracked_first[0].id!=tracked_first[1].id);
  CHECK(std::count_if(tracked_first.begin(),tracked_first.end(),[](const auto& tracked){
    return tracked.semantic==PlaneSemantic::kCeiling;})==1);
  CHECK(std::count_if(tracked_first.begin(),tracked_first.end(),[](const auto& tracked){
    return tracked.semantic==PlaneSemantic::kWall;})==1);
  const PlaneId first_plane_id=tracked_first[0].id,second_plane_id=tracked_first[1].id;
  for(auto& point:room_planes)point.position_world_m.y+=0.001;
  const auto tracked_second=plane_tracker.update(room_planes,{0,0,1},Vec3{0,0,1});
  CHECK(tracked_second.size()==2);
  CHECK(tracked_second[0].id==first_plane_id && tracked_second[1].id==second_plane_id);
  PlaneEstimate layout_floor=plane;layout_floor.distance_from_origin_m=0;
  layout_floor.center_world.z=0;layout_floor.observed_area_m2=12;
  PlaneEstimate layout_ceiling=layout_floor;layout_ceiling.distance_from_origin_m=-2.7;
  layout_ceiling.center_world.z=2.7;layout_ceiling.observed_area_m2=10;
  PlaneEstimate layout_wall=layout_floor;layout_wall.normal_world={1,0,0};
  layout_wall.distance_from_origin_m=-2;layout_wall.center_world={2,0,1.35};
  layout_wall.observed_area_m2=8;
  const std::vector<TrackedPlane> layout_planes{{10,layout_floor,PlaneSemantic::kFloor},
    {11,layout_ceiling,PlaneSemantic::kCeiling},{12,layout_wall,PlaneSemantic::kWall}};
  const auto room_layout=estimateRoomLayout(layout_planes);
  CHECK(room_layout.has_floor && room_layout.floor_plane_id==10);
  CHECK(room_layout.has_ceiling && room_layout.ceiling_plane_id==11);
  CHECK(room_layout.height_valid && std::abs(room_layout.room_height_m-2.7)<1e-9);
  CHECK(room_layout.wall_count==1 && std::abs(room_layout.observed_wall_area_m2-8)<1e-9);
  plane_tracker.reset();
  CHECK(plane_tracker.planes().empty());
  const auto plane_hit=plane.raycast({0,0,0},{0,0,1});
  CHECK(plane_hit.has_value());
  CHECK(std::abs(plane_hit->position_world.z-3.0)<1e-8);
  CHECK(!plane.raycast({5,0,0},{0,0,1}).has_value());
  CHECK(!plane.raycast({0,0,0},{1,0,0}).has_value());
  SemanticProjectionRequest projection_request;
  projection_request.class_id=8;projection_request.source_track_id=91;
  projection_request.pixel={64,48};projection_request.confidence=0.9;
  projection_request.timestamp_ns=2400000000LL;
  projection_request.surface=SemanticProjectionSurface::kFloor;
  const std::vector<TrackedPlane> projection_planes{{1,plane,PlaneSemantic::kFloor}};
  const auto projected_semantic=projectSemanticObservation(
      projection_request,frontend_camera,{},projection_planes);
  CHECK(projected_semantic.has_value());
  CHECK(std::abs(projected_semantic->position_world_m.z-3.0)<1e-8);
  projection_request.surface=SemanticProjectionSurface::kWall;
  CHECK(!projectSemanticObservation(projection_request,frontend_camera,{},projection_planes));
  projection_request.surface=SemanticProjectionSurface::kAny;
  projection_request.pixel={-1,48};
  CHECK(!projectSemanticObservation(projection_request,frontend_camera,{},projection_planes));
  AnchorStore anchors;
  const AnchorId anchor_id=anchors.create({{},plane_hit->position_world},2200000000LL);
  CHECK(anchor_id==1 && anchors.find(anchor_id).has_value());
  CHECK(anchors.remove(anchor_id));
  CHECK(!anchors.find(anchor_id).has_value());
  const AnchorId persisted_anchor=anchors.create({{},plane_hit->position_world},2300000000LL);
  const Pose expected_current_from_saved{
    Quaternion::fromRotationVector({0,0,1.5707963267948966}),{2,-1,0.5}};
  const std::vector<Vec3> saved_points{{0,0,0},{1,0,0},{0,1,0},{0,0,1},
    {1,1,0},{1,0,1},{0,1,1},{1,1,1}};
  std::vector<RelocalizationCorrespondence> relocalization_correspondences;
  for(const auto& point:saved_points)relocalization_correspondences.push_back(
    {point,expected_current_from_saved.rotation.rotate(point)+expected_current_from_saved.translation});
  relocalization_correspondences.push_back({{2,2,2},{-9,4,1}});
  relocalization_correspondences.push_back({{-1,2,0},{8,-3,2}});
  const auto relocalization=estimateRelocalization(relocalization_correspondences);
  CHECK(relocalization.valid && relocalization.inlier_count==saved_points.size());
  CHECK(relocalization.rmse_m<1e-8);
  CHECK((relocalization.current_from_saved.translation-
         expected_current_from_saved.translation).norm()<1e-8);
  std::vector<RelocalizationCorrespondence> degenerate_relocalization;
  for(int i=0;i<8;++i)degenerate_relocalization.push_back(
    {{static_cast<double>(i),0,0},{static_cast<double>(i)+1,0,0}});
  CHECK(!estimateRelocalization(degenerate_relocalization).valid);

  SpatialSnapshot spatial_snapshot{2400000000LL,sparse_map.snapshot(),anchors.snapshot(),{}};
  const std::array<std::size_t,8> descriptor_landmark_indices{{0,1,8,9,10,11,18,19}};
  for(std::size_t i=0;i<descriptor_landmark_indices.size();++i){
    BinaryFeatureDescriptor descriptor;
    descriptor.feature_id=spatial_snapshot.landmarks[descriptor_landmark_indices[i]].feature_id;
    for(std::size_t byte=0;byte<descriptor.bytes.size();++byte)
      descriptor.bytes[byte]=static_cast<std::uint8_t>((i*37+byte*11)&255);
    spatial_snapshot.landmark_descriptors.push_back(descriptor);
  }
  const auto encoded_snapshot=SpatialSnapshotCodec::encode(spatial_snapshot);
  CHECK(encoded_snapshot==SpatialSnapshotCodec::encode(spatial_snapshot));
  auto version_one_snapshot=SpatialSnapshotCodec::encode(
      {spatial_snapshot.created_ns,spatial_snapshot.landmarks,spatial_snapshot.anchors,{}});
  version_one_snapshot.erase(version_one_snapshot.begin()+28,version_one_snapshot.begin()+32);
  version_one_snapshot[8]=1;version_one_snapshot[9]=version_one_snapshot[10]=version_one_snapshot[11]=0;
  auto test_crc32=[](const std::uint8_t* data,std::size_t size){
    std::uint32_t crc=0xffffffffu;for(std::size_t i=0;i<size;++i){crc^=data[i];
      for(int bit=0;bit<8;++bit)crc=(crc>>1)^((crc&1u)?0xedb88320u:0u);}return ~crc;};
  const auto version_one_crc=test_crc32(version_one_snapshot.data(),version_one_snapshot.size()-4);
  for(std::size_t i=0;i<4;++i)version_one_snapshot[version_one_snapshot.size()-4+i]=
    static_cast<std::uint8_t>(version_one_crc>>(8*i));
  const auto decoded_version_one=SpatialSnapshotCodec::decode(version_one_snapshot);
  CHECK(decoded_version_one.valid&&decoded_version_one.snapshot.landmark_descriptors.empty());
  const auto decoded_snapshot=SpatialSnapshotCodec::decode(encoded_snapshot);
  CHECK(decoded_snapshot.valid);
  CHECK(decoded_snapshot.snapshot.landmarks.size()==20);
  CHECK(decoded_snapshot.snapshot.anchors.size()==1);
  CHECK(decoded_snapshot.snapshot.landmark_descriptors.size()==8);
  std::vector<SparseLandmark> current_relocalization_landmarks;
  std::vector<BinaryFeatureDescriptor> current_relocalization_descriptors;
  for(std::size_t i=0;i<8;++i){
    const auto saved_id=decoded_snapshot.snapshot.landmark_descriptors[i].feature_id;
    const auto saved_landmark=std::find_if(decoded_snapshot.snapshot.landmarks.begin(),
      decoded_snapshot.snapshot.landmarks.end(),[&](const auto& value){return value.feature_id==saved_id;});
    CHECK(saved_landmark!=decoded_snapshot.snapshot.landmarks.end());
    auto landmark=*saved_landmark;landmark.feature_id=1000+static_cast<std::uint32_t>(i);
    landmark.position_world_m=expected_current_from_saved.rotation.rotate(landmark.position_world_m)+
      expected_current_from_saved.translation;
    current_relocalization_landmarks.push_back(landmark);
    auto descriptor=decoded_snapshot.snapshot.landmark_descriptors[i];
    descriptor.feature_id=landmark.feature_id;current_relocalization_descriptors.push_back(descriptor);
  }
  const auto automatic_correspondences=matchRelocalizationLandmarks(decoded_snapshot.snapshot,
      current_relocalization_landmarks,current_relocalization_descriptors);
  CHECK(automatic_correspondences.size()==8);
  const auto automatic_relocalization=estimateRelocalization(automatic_correspondences);
  CHECK(automatic_relocalization.valid);
  const auto transformed_snapshot=transformSpatialSnapshot(
      decoded_snapshot.snapshot,expected_current_from_saved);
  CHECK((transformed_snapshot.landmarks.front().position_world_m-
      (expected_current_from_saved.rotation.rotate(
        decoded_snapshot.snapshot.landmarks.front().position_world_m)+
       expected_current_from_saved.translation)).norm()<1e-8);
  SparseLandmarkMap relocalized_map;AnchorStore relocalized_anchors;
  CHECK(restoreRelocalizedSpatialSnapshot(decoded_snapshot.snapshot,
      relocalization.current_from_saved,relocalized_map,relocalized_anchors));
  CHECK(relocalized_map.size()==20&&relocalized_anchors.find(persisted_anchor).has_value());
  SparseLandmarkMap restored_map;
  AnchorStore restored_anchors;
  CHECK(restoreSpatialSnapshot(decoded_snapshot.snapshot,restored_map,restored_anchors));
  CHECK(restored_map.size()==20);
  CHECK(restored_anchors.find(persisted_anchor).has_value());
  CHECK(restored_anchors.create({{},{}},2500000000LL)==persisted_anchor+1);
  SpatialSnapshot invalid_restore=decoded_snapshot.snapshot;
  invalid_restore.landmarks.clear();
  invalid_restore.anchors.push_back(invalid_restore.anchors.front());
  CHECK(!restoreSpatialSnapshot(invalid_restore,restored_map,restored_anchors));
  CHECK(restored_map.size()==20);
  auto corrupted_snapshot=encoded_snapshot;
  corrupted_snapshot[20]^=0x40;
  CHECK(!SpatialSnapshotCodec::decode(corrupted_snapshot).valid);
  corrupted_snapshot.pop_back();
  CHECK(!SpatialSnapshotCodec::decode(corrupted_snapshot).valid);

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

  VioConfig lever_config;lever_config.min_scale_displacement_m=0.001;
  lever_config.visual_position_gain=1.0;lever_config.body_from_camera_translation_m={1,0,0};
  VioEstimator lever_vio(lever_config);
  for(int i=0;i<80;++i)
    lever_vio.submitImu({vio_start+i*10000000LL,{0,0,gravity},{0,0,0}});
  RelativePoseEstimate lever_reference;lever_reference.valid=true;
  lever_reference.translation_direction_current={1,0,0};
  CHECK(!lever_vio.fuseVisual(lever_vio.state().timestamp_ns,lever_reference).rotation_applied);
  const TimestampNs lever_start=lever_vio.state().timestamp_ns;
  for(int i=1;i<=10;++i)
    CHECK(lever_vio.submitImu({lever_start+i*10000000LL,{0,0,gravity},{0,0,1}}));
  const Vec3 lever{1,0,0};
  const Vec3 camera_displacement=lever_vio.state().world_from_body.rotation.rotate(lever)-lever;
  const Vec3 camera_direction=camera_displacement*(1.0/camera_displacement.norm());
  RelativePoseEstimate lever_visual;lever_visual.valid=true;
  lever_visual.rotation_current_from_previous=lever_vio.state().world_from_body.rotation.inverse();
  lever_visual.translation_direction_current=lever_visual.rotation_current_from_previous.rotate(
      camera_direction)*-1.0;
  const auto lever_fused=lever_vio.fuseVisual(lever_vio.state().timestamp_ns,lever_visual);
  CHECK(lever_fused.scale_updated && lever_fused.position_applied);
  CHECK(std::abs(lever_fused.observed_scale_m-camera_displacement.norm())<1e-6);
  CHECK(lever_vio.state().world_from_body.translation.norm()<1e-6);

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
  ark_vio_handle* c_extrinsic_handle=nullptr;
  CHECK(ark_vio_create_with_extrinsics(&c_config,0.04,-0.01,0.02,
                                        &c_extrinsic_handle)==ARK_RESULT_OK);
  ark_vio_destroy(c_extrinsic_handle);
  CHECK(ark_vio_create_with_extrinsics(&c_config,std::numeric_limits<double>::quiet_NaN(),0,0,
                                        &c_extrinsic_handle)==ARK_RESULT_INVALID_ARGUMENT);
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
  FixedLagSmoother uncertain_smoother;
  VioState uncertainty_a;uncertainty_a.timestamp_ns=6000000000LL;
  VioState uncertainty_b=uncertainty_a;uncertainty_b.timestamp_ns+=100000000LL;
  auto uncertain_factor=constant_velocity_imu;
  uncertain_factor.covariance_diagonal.fill(1.0);
  CHECK(uncertain_smoother.addKeyframe(uncertainty_a));
  CHECK(uncertain_smoother.addKeyframe(uncertainty_b,uncertain_factor));
  CHECK(uncertain_smoother.optimize().covariance_downweighted_factor_count==1);

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

  ImuBias changed_bias{{0.01,-0.015,0.005},{0.001,-0.0015,0.002}};
  ImuPreintegrator linearized_imu;
  ImuPreintegrator reintegrated_imu(changed_bias);
  for(int i=0;i<=100;++i){
    const ImuSample sample{9000000000LL+i*10000000LL,
                           {0.2,-0.1,9.81},{0.01,-0.02,0.03}};
    CHECK(linearized_imu.add(sample));
    CHECK(reintegrated_imu.add(sample));
  }
  const auto bias_corrected=linearized_imu.value().corrected(changed_bias);
  CHECK((bias_corrected.delta_velocity-reintegrated_imu.value().delta_velocity).norm()<0.002);
  CHECK((bias_corrected.delta_position-reintegrated_imu.value().delta_position).norm()<0.001);
  const Vec3 corrected_axis=bias_corrected.delta_rotation.rotate({1,0,0});
  const Vec3 exact_axis=reintegrated_imu.value().delta_rotation.rotate({1,0,0});
  CHECK((corrected_axis-exact_axis).norm()<0.0001);
  CHECK(linearized_imu.value().covariance_diagonal[0]>0.0);
  CHECK(linearized_imu.value().covariance_diagonal[3]>0.0);
  CHECK(linearized_imu.value().covariance_diagonal[6]>0.0);
  CHECK(bias_corrected.covariance_diagonal==linearized_imu.value().covariance_diagonal);
  ImuPreintegrator noisy_imu({}, {0.30,0.030});
  for(int i=0;i<=100;++i)
    CHECK(noisy_imu.add({10000000000LL+i*10000000LL,{0.2,-0.1,9.81},{0.01,-0.02,0.03}}));
  CHECK(noisy_imu.value().covariance_diagonal[0]>
        linearized_imu.value().covariance_diagonal[0]*3.9);
  CHECK(noisy_imu.value().covariance_diagonal[3]>
        linearized_imu.value().covariance_diagonal[3]*3.9);

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

  MappingQualityGate mapping_gate;
  auto mapping_decision=mapping_gate.update(false,false,true,40,false);
  CHECK(!mapping_decision.integrate &&
        mapping_decision.reason==MappingPauseReason::kPoseUninitialized);
  mapping_decision=mapping_gate.update(true,false,true,40,false);
  CHECK(mapping_decision.state==MappingGateState::kRecovering && !mapping_decision.integrate);
  mapping_gate.update(true,false,true,40,false);
  mapping_decision=mapping_gate.update(true,false,true,40,false);
  CHECK(mapping_decision.state==MappingGateState::kActive && mapping_decision.integrate);
  mapping_decision=mapping_gate.update(true,true,true,40,false);
  CHECK(mapping_decision.state==MappingGateState::kPaused &&
        mapping_decision.reason==MappingPauseReason::kPoseLimited);
  mapping_gate.update(true,false,true,40,false);mapping_gate.update(true,false,true,40,false);
  mapping_decision=mapping_gate.update(true,false,true,40,false);
  CHECK(mapping_decision.integrate);
  mapping_decision=mapping_gate.update(true,false,true,40,true);
  CHECK(!mapping_decision.integrate &&
        mapping_decision.reason==MappingPauseReason::kImuOverflow);

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
