#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>
#include <memory>
#include "arkernel/bundle_adjustment.h"
#include "arkernel/c_api.h"
#include "arkernel/camera_model.h"
#include "arkernel/clock_aligner.h"
#include "arkernel/core_engine.h"
#include "arkernel/depth_postprocessor.h"
#include "arkernel/depth_sampling.h"
#include "arkernel/detection_postprocessor.h"
#include "arkernel/detection_tracker.h"
#include "arkernel/feature_detector.h"
#include "arkernel/fixed_lag_smoother.h"
#include "arkernel/epipolar_ransac.h"
#include "arkernel/essential_pose.h"
#include "arkernel/geometry_model_selector.h"
#include "arkernel/homography_ransac.h"
#include "arkernel/image_descriptor.h"
#include "arkernel/imu_preintegrator.h"
#include "arkernel/landmark_merge.h"
#include "arkernel/loop_closure.h"
#include "arkernel/mesh_processing.h"
#include "arkernel/multi_view_stereo.h"
#include "arkernel/optical_flow.h"
#include "arkernel/place_recognition.h"
#include "arkernel/pose_graph.h"
#include "arkernel/relocalization.h"
#include "arkernel/reconstruction_asset.h"
#include "arkernel/reconstruction_capture.h"
#include "arkernel/reconstruction_pipeline.h"
#include "arkernel/semantic_map.h"
#include "arkernel/sparse_map.h"
#include "arkernel/spatial_snapshot.h"
#include "arkernel/sparse_tsdf.h"
#include "arkernel/surfel_map.h"
#include "arkernel/tensor_preprocessor.h"
#include "arkernel/temporal_depth_filter.h"
#include "arkernel/texture_atlas.h"
#include "arkernel/texture_view_selection.h"
#include "arkernel/motion_prediction.h"
#include "arkernel/tracking_quality.h"
#include "arkernel/trajectory_metrics.h"
#include "arkernel/tsdf_meshing.h"
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
  // Centimeter claims are evidence-based and object scans require stronger coverage.
  ReconstructionQuality reconstruction_quality;
  reconstruction_quality.calibrated_intrinsics=true;
  reconstruction_quality.calibrated_camera_imu=true;
  reconstruction_quality.metric_scale_observed=true;
  reconstruction_quality.globally_optimized=true;
  reconstruction_quality.reprojection_rmse_px=0.6;
  reconstruction_quality.depth_rmse_m=0.008;
  reconstruction_quality.scale_stddev_ratio=0.003;
  reconstruction_quality.assessed_extent_m=2.0;
  reconstruction_quality.surface_coverage_ratio=0.90;
  reconstruction_quality.held_out_dimensions={
      {"held-out-0.5m",{0,0,0},{0.502,0,0},0.5,0.001,
       DimensionalReferenceKind::kHeldOutScaleBar,true},
      {"surveyed-1m",{0,0,0},{1.003,0,0},1.0,0.001,
       DimensionalReferenceKind::kSurveyedDistance,true},
      {"ground-truth-2m",{0,0,0},{2.004,0,0},2.0,0.001,
       DimensionalReferenceKind::kSensorGroundTruth,true}};
  auto accuracy=assessReconstructionQuality(reconstruction_quality,ScanMode::kObject);
  CHECK(accuracy.grade==MetricAccuracyGrade::kCentimeterValidated&&accuracy.export_allowed);
  CHECK(accuracy.dimensional_validation.passed&&
        accuracy.dimensional_validation.accepted_observations==3&&
        accuracy.dimensional_validation.dimension_bins>=2);
  const auto validated_reconstruction_quality=reconstruction_quality;
  reconstruction_quality.assessed_extent_m=4.0;
  accuracy=assessReconstructionQuality(reconstruction_quality,ScanMode::kObject);
  CHECK(accuracy.grade==MetricAccuracyGrade::kApproximate&&accuracy.export_allowed);
  reconstruction_quality.assessed_extent_m=2.0;
  reconstruction_quality.depth_rmse_m=0.02;
  accuracy=assessReconstructionQuality(reconstruction_quality,ScanMode::kObject);
  CHECK(accuracy.grade==MetricAccuracyGrade::kApproximate&&accuracy.export_allowed);
  reconstruction_quality.depth_rmse_m=0.008;
  const auto held_out_dimensions=reconstruction_quality.held_out_dimensions;
  reconstruction_quality.held_out_dimensions.clear();
  accuracy=assessReconstructionQuality(reconstruction_quality,ScanMode::kObject);
  CHECK(accuracy.grade==MetricAccuracyGrade::kApproximate&&accuracy.export_allowed&&
        !accuracy.dimensional_validation.passed);
  auto invalid_dimensions=held_out_dimensions;
  invalid_dimensions[0].held_out_from_calibration_and_optimization=false;
  invalid_dimensions[1].evidence_id=invalid_dimensions[2].evidence_id;
  const auto invalid_validation=evaluateHeldOutDimensions(invalid_dimensions);
  CHECK(!invalid_validation.passed&&invalid_validation.accepted_observations==1);
  auto inaccurate_dimensions=held_out_dimensions;
  inaccurate_dimensions[2].reconstructed_b_m={2.05,0,0};
  const auto inaccurate_validation=evaluateHeldOutDimensions(inaccurate_dimensions);
  CHECK(!inaccurate_validation.passed&&
        inaccurate_validation.maximum_effective_absolute_error_m>0.04);
  const char* c_dimension_ids[3]={"held-out-0.5m","surveyed-1m","ground-truth-2m"};
  ark_held_out_dimension_v1 c_dimensions[3]{};
  for(std::size_t i=0;i<3;++i){
    c_dimensions[i].struct_size=sizeof(c_dimensions[i]);
    c_dimensions[i].evidence_id=c_dimension_ids[i];
    c_dimensions[i].evidence_id_length=std::strlen(c_dimension_ids[i]);
    c_dimensions[i].reconstructed_b_x_m=held_out_dimensions[i].reconstructed_b_m.x;
    c_dimensions[i].reference_distance_m=held_out_dimensions[i].reference_distance_m;
    c_dimensions[i].reference_uncertainty_m=held_out_dimensions[i].reference_uncertainty_m;
    c_dimensions[i].reference_kind=static_cast<uint32_t>(i+1);
    c_dimensions[i].held_out_from_calibration_and_optimization=1;
  }
  ark_dimensional_validation_report_v1 c_dimension_report{};
  c_dimension_report.struct_size=sizeof(c_dimension_report);
  CHECK(ark_evaluate_held_out_dimensions(c_dimensions,3,nullptr,&c_dimension_report)==
        ARK_RESULT_OK);
  CHECK(c_dimension_report.passed==1&&c_dimension_report.failure_flags==0&&
        c_dimension_report.accepted_observations==3);
  c_dimensions[0].held_out_from_calibration_and_optimization=0;
  CHECK(ark_evaluate_held_out_dimensions(c_dimensions,3,nullptr,&c_dimension_report)==
        ARK_RESULT_OK);
  CHECK(c_dimension_report.passed==0&&
        (c_dimension_report.failure_flags&ARK_DIMENSIONAL_FAILURE_INVALID_OBSERVATION)!=0);
  reconstruction_quality.metric_scale_observed=false;
  accuracy=assessReconstructionQuality(reconstruction_quality,ScanMode::kObject);
  CHECK(accuracy.grade==MetricAccuracyGrade::kUnverified&&!accuracy.export_allowed);

  // Bounded object capture retains only sharp, well-tracked, novel views and
  // measures directional coverage without retaining image bytes in the core.
  CaptureQualityConfig capture_config;capture_config.max_keyframes=2;
  capture_config.min_interval_ns=100;capture_config.min_translation_m=0.05;
  capture_config.object_azimuth_bins=4;capture_config.object_elevation_bins=2;
  ReconstructionCapturePlanner object_capture(ScanMode::kObject,capture_config);
  ReconstructionCapturePlanner environment_capture(ScanMode::kEnvironment,capture_config);
  CHECK(environment_capture.objectCoverage().total_bins==0);
  ReconstructionFrameCandidate candidate;candidate.frame_id=1;candidate.timestamp_ns=1000;
  candidate.world_from_camera.translation={1,0,0};candidate.tracking_state=TrackingState::kTracking;
  candidate.tracked_features=100;candidate.sharpness=0.8;candidate.clipped_pixel_ratio=0.01;
  candidate.translation_sigma_m=0.005;candidate.rotation_sigma_degrees=0.2;
  candidate.object_foreground_ratio=0.5;candidate.object_center_world_m=Vec3{};
  candidate.image_reference="frames/1.yuv";
  ReconstructionCapturePlanner transactional_capture(ScanMode::kObject,capture_config);
  auto reserved=transactional_capture.reserve(candidate);
  CHECK(reserved.accepted&&reserved.reservation_token!=0&&
        transactional_capture.hasPendingReservation());
  CHECK(transactional_capture.keyframes().empty()&&
        transactional_capture.objectCoverage().covered_bins==0);
  CHECK(!transactional_capture.commit(reserved.reservation_token+1));
  CHECK(transactional_capture.commit(reserved.reservation_token));
  CHECK(transactional_capture.keyframes().size()==1&&
        transactional_capture.objectCoverage().covered_bins==1);
  transactional_capture.reset();
  reserved=transactional_capture.reserve(candidate);
  CHECK(reserved.accepted&&transactional_capture.cancel(reserved.reservation_token));
  CHECK(transactional_capture.keyframes().empty()&&
        transactional_capture.objectCoverage().covered_bins==0);
  CHECK(!transactional_capture.commit(reserved.reservation_token));
  auto capture=object_capture.consider(candidate);
  CHECK(capture.accepted&&capture.object_view_bin.has_value());
  candidate.frame_id=2;candidate.timestamp_ns=1200;candidate.image_reference="frames/2.yuv";
  capture=object_capture.consider(candidate);
  CHECK(!capture.accepted&&capture.rejection==CaptureRejection::kInsufficientMotion);
  candidate.frame_id=3;candidate.timestamp_ns=1400;candidate.world_from_camera.translation={0,1,0};
  candidate.image_reference="frames/3.yuv";capture=object_capture.consider(candidate);
  CHECK(capture.accepted&&object_capture.objectCoverage().covered_bins==2);
  candidate.frame_id=4;candidate.timestamp_ns=1600;candidate.world_from_camera.translation={-1,0,0};
  candidate.object_center_world_m=Vec3{0.1,0,0};candidate.image_reference="frames/4.yuv";
  capture=object_capture.consider(candidate);
  CHECK(!capture.accepted&&capture.rejection==CaptureRejection::kObjectRoiInvalid);
  candidate.frame_id=5;candidate.timestamp_ns=1800;candidate.object_center_world_m=Vec3{};
  candidate.image_reference="frames/5.yuv";capture=object_capture.consider(candidate);
  CHECK(!capture.accepted&&capture.rejection==CaptureRejection::kCapacityReached);
  CHECK(object_capture.keyframes().size()==2&&object_capture.objectCoverage().total_bins==8);
  object_capture.reset();CHECK(object_capture.keyframes().empty()&&object_capture.objectCoverage().covered_bins==0);
  candidate.frame_id=5;candidate.timestamp_ns=2000;candidate.sharpness=0.1;
  candidate.image_reference="frames/5.yuv";capture=object_capture.consider(candidate);
  CHECK(!capture.accepted&&capture.rejection==CaptureRejection::kBlurred);
  candidate.frame_id=6;candidate.timestamp_ns=1900;candidate.sharpness=0.8;
  candidate.image_reference="frames/6.yuv";capture=object_capture.consider(candidate);
  CHECK(!capture.accepted&&capture.rejection==CaptureRejection::kNonMonotonicTimestamp);

  // Portable, textured indexed meshes export to glTF, OBJ/MTL and PLY.
  ReconstructionMesh mesh;
  mesh.name="test triangle";
  mesh.positions_m={{0,0,0},{1,0,0},{0,1,0}};
  mesh.normals={{0,0,1},{0,0,1},{0,0,1}};
  mesh.texcoords={{0,0},{1,0},{0,1}};
  mesh.triangle_indices={0,1,2};
  mesh.base_color_texture={"atlas.png","image/png"};
  CHECK(validateMesh(mesh).valid);
  const auto gltf=exportGltf(mesh,"triangle.bin");
  CHECK(gltf.binary.size()==3*sizeof(Vec3f)+3*sizeof(Vec3f)+3*sizeof(Vec2f)+3*sizeof(std::uint32_t));
  CHECK(gltf.json.find("\"version\":\"2.0\"")!=std::string::npos);
  CHECK(gltf.json.find("atlas.png")!=std::string::npos);
  CHECK(gltf.json.find("\"accuracy\":\"unverified\"")!=std::string::npos);
  const auto assessed_gltf=exportAssessedGltf(mesh,validated_reconstruction_quality,
    ScanMode::kObject,AccuracyPolicy{},"triangle.bin");
  CHECK(assessed_gltf.json.find("\"accuracy\":\"centimeter-validated\"")!=
        std::string::npos);
  CHECK(assessed_gltf.json.find("\"acceptedObservations\":3")!=std::string::npos);
  const auto glb=exportGlb(mesh);
  CHECK(glb.size()>28&&glb[0]=='g'&&glb[1]=='l'&&glb[2]=='T'&&glb[3]=='F');
  const auto read_u32=[&glb](std::size_t offset){return static_cast<std::uint32_t>(glb[offset])|
    static_cast<std::uint32_t>(glb[offset+1])<<8U|
    static_cast<std::uint32_t>(glb[offset+2])<<16U|
    static_cast<std::uint32_t>(glb[offset+3])<<24U;};
  CHECK(read_u32(4)==2&&read_u32(8)==glb.size()&&read_u32(16)==0x4E4F534AU);
  const auto assessed_glb=exportAssessedGlb(mesh,validated_reconstruction_quality,
    ScanMode::kObject);
  const std::string assessed_glb_text(assessed_glb.begin(),assessed_glb.end());
  CHECK(assessed_glb_text.find("centimeter-validated")!=std::string::npos);
  CHECK(exportObj(mesh).find("f 1/1/1 2/2/2 3/3/3")!=std::string::npos);
  const auto assessed_obj=exportAssessedObj(mesh,validated_reconstruction_quality,
    ScanMode::kObject);
  CHECK(assessed_obj.find("# arkernel accuracy centimeter-validated")!=std::string::npos);
  CHECK(exportMtl(mesh).find("map_Kd atlas.png")!=std::string::npos);
  CHECK(exportPly(mesh).find("element face 1")!=std::string::npos);
  const auto assessed_ply=exportAssessedPly(mesh,validated_reconstruction_quality,
    ScanMode::kObject);
  CHECK(assessed_ply.find("comment arkernel accuracy centimeter-validated")!=
        std::string::npos);
  bool rejected_unverified_export=false;
  try{exportAssessedGltf(mesh,reconstruction_quality,ScanMode::kObject);}
  catch(const std::invalid_argument&){rejected_unverified_export=true;}
  CHECK(rejected_unverified_export);
  mesh.triangle_indices[2]=3;
  CHECK(!validateMesh(mesh).valid);

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
  const auto clock_model = aligner.alignment();
  CHECK(clock_model.has_value() && clock_model->source_span_ns == 190000000LL &&
        clock_model->observation_count >= 19);
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
  auto masked_depth_config=depth_sampling_config;
  masked_depth_config.exclusion_rects.push_back({0.0,0.0,4.0,4.0});
  const auto masked_depth=sampleMetricDepthImage(depth_view,depth_camera,2000000000LL,
                                                 masked_depth_config);
  CHECK(masked_depth.size()==12);
  CHECK(std::none_of(masked_depth.begin(),masked_depth.end(),[](const auto& value){
    return value.position_world_m.x<0&&value.position_world_m.y<0;}));
  auto invalid_depth_view=depth_view;invalid_depth_view.row_stride_elements=7;
  CHECK(sampleMetricDepthImage(invalid_depth_view,depth_camera,2000000000LL).empty());

  // Bounded sparse TSDF integrates only a narrow uncertainty-weighted band.
  std::vector<float> tsdf_uncertainty(8*8,0.01F);
  const TsdfDepthImageView tsdf_depth_view{8,8,8,metric_depth.data(),metric_depth.size(),
    depth_confidence.data(),8,depth_confidence.size(),tsdf_uncertainty.data(),8,
    tsdf_uncertainty.size()};
  SparseTsdfConfig tsdf_config;tsdf_config.voxel_size_m=0.1;
  tsdf_config.truncation_distance_m=0.2;tsdf_config.pixel_stride=2;
  tsdf_config.max_blocks=128;tsdf_config.max_ray_samples=16;
  SparseTsdfVolume tsdf_volume(tsdf_config);
  const auto tsdf_update=tsdf_volume.integrate(tsdf_depth_view,depth_camera,{});
  CHECK(tsdf_update.input_valid&&!tsdf_update.truncated&&
        tsdf_update.integrated_pixels>0&&tsdf_update.voxel_updates>0&&
        tsdf_update.voxel_visits>=tsdf_update.voxel_updates&&
        tsdf_update.allocated_blocks==tsdf_volume.blockCount()&&tsdf_volume.voxelCount()>0);
  const auto tsdf_snapshot=tsdf_volume.snapshot();
  CHECK(tsdf_snapshot.size()==tsdf_volume.voxelCount()&&
        std::any_of(tsdf_snapshot.begin(),tsdf_snapshot.end(),
                    [](const auto& voxel){return voxel.signed_distance_m>0.0F;})&&
        std::any_of(tsdf_snapshot.begin(),tsdf_snapshot.end(),
                    [](const auto& voxel){return voxel.signed_distance_m<0.0F;}));
  const auto tsdf_before=tsdf_volume.sample(tsdf_snapshot.front().center_world_m);
  CHECK(tsdf_before.has_value());
  std::vector<float> high_tsdf_uncertainty(8*8,0.1F);
  auto high_uncertainty_tsdf_view=tsdf_depth_view;
  high_uncertainty_tsdf_view.uncertainty_m=high_tsdf_uncertainty.data();
  SparseTsdfVolume high_uncertainty_tsdf(tsdf_config);
  CHECK(high_uncertainty_tsdf.integrate(
    high_uncertainty_tsdf_view,depth_camera,{}).input_valid);
  const auto high_uncertainty_sample=
    high_uncertainty_tsdf.sample(tsdf_snapshot.front().center_world_m);
  CHECK(high_uncertainty_sample.has_value()&&
        high_uncertainty_sample->weight<tsdf_before->weight);
  const auto second_tsdf_update=tsdf_volume.integrate(tsdf_depth_view,depth_camera,{});
  const auto tsdf_after=tsdf_volume.sample(tsdf_snapshot.front().center_world_m);
  CHECK(second_tsdf_update.input_valid&&tsdf_after.has_value()&&
        tsdf_after->weight>tsdf_before->weight);
  auto capacity_tsdf_config=tsdf_config;capacity_tsdf_config.max_blocks=1;
  SparseTsdfVolume capacity_tsdf(capacity_tsdf_config);
  const auto capacity_tsdf_update=capacity_tsdf.integrate(tsdf_depth_view,depth_camera,{});
  CHECK(capacity_tsdf_update.input_valid&&capacity_tsdf_update.truncated&&
        capacity_tsdf_update.capacity_rejections>0&&capacity_tsdf.blockCount()==1);
  auto work_limited_tsdf_config=tsdf_config;
  work_limited_tsdf_config.max_voxel_visits_per_update=1;
  SparseTsdfVolume work_limited_tsdf(work_limited_tsdf_config);
  const auto work_limited_update=
    work_limited_tsdf.integrate(tsdf_depth_view,depth_camera,{});
  CHECK(work_limited_update.input_valid&&work_limited_update.truncated&&
        work_limited_update.voxel_visits==1&&work_limited_update.voxel_updates<=1);
  auto snapshot_limited_tsdf_config=tsdf_config;
  snapshot_limited_tsdf_config.max_snapshot_voxels=1;
  SparseTsdfVolume snapshot_limited_tsdf(snapshot_limited_tsdf_config);
  CHECK(snapshot_limited_tsdf.integrate(tsdf_depth_view,depth_camera,{}).input_valid);
  bool rejected_tsdf_snapshot=false;
  try{snapshot_limited_tsdf.snapshot();}
  catch(const std::length_error&){rejected_tsdf_snapshot=true;}
  CHECK(rejected_tsdf_snapshot);
  auto invalid_tsdf_view=tsdf_depth_view;invalid_tsdf_view.depth_row_stride_elements=7;
  CHECK(!tsdf_volume.integrate(invalid_tsdf_view,depth_camera,{}).input_valid);
  auto invalid_tsdf_config=tsdf_config;invalid_tsdf_config.max_ray_samples=3;
  bool rejected_tsdf_config=false;
  try{SparseTsdfVolume invalid_tsdf(invalid_tsdf_config);}
  catch(const std::invalid_argument&){rejected_tsdf_config=true;}
  CHECK(rejected_tsdf_config);
  tsdf_volume.reset();CHECK(tsdf_volume.blockCount()==0&&tsdf_volume.voxelCount()==0);

  // Deterministic bounded Marching Tetrahedra extracts a metric plane only
  // where all eight TSDF cell corners are observed with sufficient weight.
  constexpr int mesh_depth_width=32,mesh_depth_height=32;
  std::vector<float> mesh_plane_depth(mesh_depth_width*mesh_depth_height,2.0F);
  std::vector<float> mesh_plane_confidence(mesh_plane_depth.size(),1.0F);
  std::vector<float> mesh_plane_uncertainty(mesh_plane_depth.size(),0.005F);
  const PinholeRadTanCamera mesh_depth_camera(
    {mesh_depth_width,mesh_depth_height,40,40,15.5,15.5});
  const TsdfDepthImageView mesh_depth_view{mesh_depth_width,mesh_depth_height,
    mesh_depth_width,mesh_plane_depth.data(),mesh_plane_depth.size(),
    mesh_plane_confidence.data(),mesh_depth_width,mesh_plane_confidence.size(),
    mesh_plane_uncertainty.data(),mesh_depth_width,mesh_plane_uncertainty.size()};
  SparseTsdfConfig mesh_tsdf_config;mesh_tsdf_config.voxel_size_m=0.05;
  mesh_tsdf_config.truncation_distance_m=0.10;mesh_tsdf_config.pixel_stride=1;
  mesh_tsdf_config.max_blocks=512;mesh_tsdf_config.max_ray_samples=16;
  SparseTsdfVolume mesh_tsdf(mesh_tsdf_config);
  const auto mesh_tsdf_update=mesh_tsdf.integrate(mesh_depth_view,mesh_depth_camera,{});
  CHECK(mesh_tsdf_update.input_valid&&!mesh_tsdf_update.truncated);
  TsdfMeshingConfig meshing_config;meshing_config.min_voxel_weight=0.1F;
  const auto extracted_mesh=extractTsdfMesh(mesh_tsdf,meshing_config);
  CHECK(extracted_mesh.input_valid&&!extracted_mesh.truncated&&
        extracted_mesh.complete_cells>0&&extracted_mesh.surface_cells>0&&
        !extracted_mesh.mesh.positions_m.empty()&&validateMesh(extracted_mesh.mesh).valid);
  CHECK(std::all_of(extracted_mesh.mesh.positions_m.begin(),
                    extracted_mesh.mesh.positions_m.end(),[](const auto& position){
                      return std::abs(position.z-2.0F)<0.001F;}));
  CHECK(std::all_of(extracted_mesh.mesh.normals.begin(),
                    extracted_mesh.mesh.normals.end(),[](const auto& normal){
                      return normal.z< -0.99F;}));
  const auto repeated_mesh=extractTsdfMesh(mesh_tsdf,meshing_config);
  CHECK(repeated_mesh.mesh.triangle_indices==extracted_mesh.mesh.triangle_indices&&
        repeated_mesh.mesh.positions_m.size()==extracted_mesh.mesh.positions_m.size());
  for(std::size_t i=0;i<extracted_mesh.mesh.positions_m.size();++i){
    CHECK(repeated_mesh.mesh.positions_m[i].x==extracted_mesh.mesh.positions_m[i].x&&
          repeated_mesh.mesh.positions_m[i].y==extracted_mesh.mesh.positions_m[i].y&&
          repeated_mesh.mesh.positions_m[i].z==extracted_mesh.mesh.positions_m[i].z);}
  std::fill(mesh_plane_depth.begin(),mesh_plane_depth.end(),2.025F);
  SparseTsdfVolume endpoint_tsdf(mesh_tsdf_config);
  CHECK(endpoint_tsdf.integrate(mesh_depth_view,mesh_depth_camera,{}).input_valid);
  const auto endpoint_mesh=extractTsdfMesh(endpoint_tsdf,meshing_config);
  CHECK(endpoint_mesh.input_valid&&!endpoint_mesh.mesh.positions_m.empty()&&
        validateMesh(endpoint_mesh.mesh).valid);
  auto limited_meshing_config=meshing_config;limited_meshing_config.max_triangles=1;
  const auto limited_mesh=extractTsdfMesh(mesh_tsdf,limited_meshing_config);
  CHECK(limited_mesh.input_valid&&limited_mesh.truncated&&
        limited_mesh.mesh.triangle_indices.size()==3&&validateMesh(limited_mesh.mesh).valid);
  auto strict_weight_meshing_config=meshing_config;
  strict_weight_meshing_config.min_voxel_weight=1000.0F;
  const auto strict_weight_mesh=extractTsdfMesh(mesh_tsdf,strict_weight_meshing_config);
  CHECK(strict_weight_mesh.input_valid&&strict_weight_mesh.mesh.positions_m.empty()&&
        strict_weight_mesh.skipped_low_weight_cells>0);
  auto invalid_meshing_config=meshing_config;invalid_meshing_config.max_vertices=2;
  bool rejected_meshing_config=false;
  try{extractTsdfMesh(mesh_tsdf,invalid_meshing_config);}
  catch(const std::invalid_argument&){rejected_meshing_config=true;}
  CHECK(rejected_meshing_config);

  // Edge-connected component filtering removes a detached one-triangle island
  // and compacts every indexed attribute without merging point-only contact.
  auto island_mesh=extracted_mesh.mesh;
  const auto island_first=static_cast<std::uint32_t>(island_mesh.positions_m.size());
  island_mesh.positions_m.insert(island_mesh.positions_m.end(),
    {{10,10,10},{10.1F,10,10},{10,10.1F,10}});
  island_mesh.normals.insert(island_mesh.normals.end(),{{0,0,-1},{0,0,-1},{0,0,-1}});
  island_mesh.texcoords.assign(island_mesh.positions_m.size(),{0.5F,0.5F});
  island_mesh.base_color_texture={"component-atlas.png","image/png"};
  island_mesh.triangle_indices.insert(island_mesh.triangle_indices.end(),
    {island_first,island_first+1,island_first+2});
  MeshComponentFilterConfig component_config;component_config.min_component_triangles=2;
  component_config.min_component_area_m2=0.0;
  const auto filtered_mesh=filterMeshComponents(island_mesh,component_config);
  CHECK(filtered_mesh.input_valid&&!filtered_mesh.truncated&&filtered_mesh.components==2&&
        filtered_mesh.retained_components==1&&filtered_mesh.removed_triangles==1&&
        filtered_mesh.removed_vertices==3&&filtered_mesh.boundary_edges>0&&
        filtered_mesh.manifold_edges>0&&filtered_mesh.non_manifold_edges==0&&
        validateMesh(filtered_mesh.mesh).valid&&
        filtered_mesh.mesh.texcoords.size()==filtered_mesh.mesh.positions_m.size()&&
        filtered_mesh.mesh.base_color_texture.uri=="component-atlas.png");
  CHECK(std::none_of(filtered_mesh.mesh.positions_m.begin(),filtered_mesh.mesh.positions_m.end(),
                     [](const auto& position){return position.x>5.0F;}));
  const auto repeated_filtered_mesh=filterMeshComponents(island_mesh,component_config);
  CHECK(repeated_filtered_mesh.mesh.triangle_indices==filtered_mesh.mesh.triangle_indices&&
        repeated_filtered_mesh.mesh.positions_m.size()==filtered_mesh.mesh.positions_m.size());
  ReconstructionMesh point_touch_mesh;
  point_touch_mesh.positions_m={{0,0,0},{1,0,0},{0,1,0},{-1,0,0},{0,-1,0}};
  point_touch_mesh.triangle_indices={0,1,2,0,3,4};
  auto point_touch_config=component_config;point_touch_config.min_component_triangles=1;
  const auto point_touch_result=filterMeshComponents(point_touch_mesh,point_touch_config);
  CHECK(point_touch_result.input_valid&&point_touch_result.components==2&&
        point_touch_result.retained_components==2);
  point_touch_config.keep_largest_only=true;
  const auto largest_only_mesh=filterMeshComponents(point_touch_mesh,point_touch_config);
  CHECK(largest_only_mesh.retained_components==1&&
        largest_only_mesh.mesh.triangle_indices.size()==3&&
        largest_only_mesh.mesh.positions_m.size()==3);
  ReconstructionMesh non_manifold_mesh;
  non_manifold_mesh.positions_m={{0,0,0},{1,0,0},{0,1,0},{0,-1,0},{0,0,1}};
  non_manifold_mesh.triangle_indices={0,1,2,1,0,3,0,1,4};
  point_touch_config.keep_largest_only=false;
  const auto non_manifold_result=filterMeshComponents(non_manifold_mesh,point_touch_config);
  CHECK(non_manifold_result.input_valid&&non_manifold_result.components==1&&
        non_manifold_result.non_manifold_edges==1);
  auto edge_limited_config=component_config;edge_limited_config.max_unique_edges=1;
  const auto edge_limited_mesh=filterMeshComponents(island_mesh,edge_limited_config);
  CHECK(edge_limited_mesh.input_valid&&edge_limited_mesh.truncated&&
        edge_limited_mesh.error=="mesh edge budget exceeded");
  auto invalid_component_config=component_config;invalid_component_config.max_input_vertices=0;
  bool rejected_component_config=false;
  try{filterMeshComponents(island_mesh,invalid_component_config);}
  catch(const std::invalid_argument&){rejected_component_config=true;}
  CHECK(rejected_component_config);

  // Conservative clustering reduces smooth interiors while preserving the
  // filtered plane's boundary, UV alignment, texture reference and error bound.
  MeshSimplificationConfig simplification_config;
  simplification_config.cluster_cell_size_m=0.15;
  simplification_config.max_position_error_m=0.12;
  simplification_config.max_uv_error=0.1;
  const auto simplified_mesh=simplifyMeshConservative(filtered_mesh.mesh,
                                                       simplification_config);
  CHECK(simplified_mesh.input_valid&&!simplified_mesh.truncated&&
        simplified_mesh.error.empty()&&
        simplified_mesh.output_vertices<simplified_mesh.input_vertices&&
        simplified_mesh.output_triangles<=simplified_mesh.input_triangles&&
        simplified_mesh.locked_vertices>0&&simplified_mesh.boundary_edges>0&&
        simplified_mesh.max_observed_displacement_m<=0.120001&&
        simplified_mesh.mesh.texcoords.size()==simplified_mesh.mesh.positions_m.size()&&
        simplified_mesh.mesh.base_color_texture.uri=="component-atlas.png"&&
        validateMesh(simplified_mesh.mesh).valid);
  const auto repeated_simplified_mesh=simplifyMeshConservative(
    filtered_mesh.mesh,simplification_config);
  CHECK(repeated_simplified_mesh.mesh.triangle_indices==
          simplified_mesh.mesh.triangle_indices&&
        repeated_simplified_mesh.mesh.positions_m.size()==
          simplified_mesh.mesh.positions_m.size());
  auto strict_error_config=simplification_config;
  strict_error_config.max_position_error_m=0.01;
  const auto strict_error_mesh=simplifyMeshConservative(filtered_mesh.mesh,
                                                         strict_error_config);
  CHECK(strict_error_mesh.input_valid&&strict_error_mesh.rejected_merges>0&&
        strict_error_mesh.max_observed_displacement_m<=0.010001);
  ReconstructionMesh crease_mesh;
  crease_mesh.positions_m={{0,0,0},{1,0,0},{0,1,0},{0,0,1}};
  crease_mesh.triangle_indices={0,1,2,1,0,3};
  auto crease_config=simplification_config;crease_config.preserve_boundaries=false;
  const auto crease_result=simplifyMeshConservative(crease_mesh,crease_config);
  CHECK(crease_result.input_valid&&crease_result.sharp_edges==1&&
        crease_result.locked_vertices==2&&validateMesh(crease_result.mesh).valid);
  auto cluster_limited_config=simplification_config;cluster_limited_config.max_clusters=1;
  const auto cluster_limited_mesh=simplifyMeshConservative(filtered_mesh.mesh,
                                                            cluster_limited_config);
  CHECK(cluster_limited_mesh.input_valid&&cluster_limited_mesh.truncated&&
        cluster_limited_mesh.error=="mesh cluster budget exceeded");
  auto invalid_simplification_config=simplification_config;
  invalid_simplification_config.sharp_edge_angle_degrees=180.0;
  bool rejected_simplification_config=false;
  try{simplifyMeshConservative(filtered_mesh.mesh,invalid_simplification_config);}
  catch(const std::invalid_argument&){rejected_simplification_config=true;}
  CHECK(rejected_simplification_config);

  // The fusion pipeline owns temporal ordering, sticky truncation evidence and
  // both the filtered source mesh and its independent delivery simplification.
  ReconstructionPipelineConfig pipeline_config;
  pipeline_config.fusion=mesh_tsdf_config;
  pipeline_config.meshing=meshing_config;
  pipeline_config.component_filter.min_component_triangles=2;
  pipeline_config.component_filter.min_component_area_m2=0.0;
  pipeline_config.simplification.cluster_cell_size_m=0.15;
  pipeline_config.simplification.max_position_error_m=0.12;
  pipeline_config.max_integrated_frames=2;
  ReconstructionFusionPipeline reconstruction_pipeline(pipeline_config);
  const auto pipeline_first=reconstruction_pipeline.integrate(
      1000,mesh_depth_view,mesh_depth_camera,{});
  CHECK(pipeline_first.accepted&&pipeline_first.accepted_frames==1&&
        pipeline_first.rejected_frames==0&&!pipeline_first.fusion_truncated);
  const auto pipeline_repeated=reconstruction_pipeline.integrate(
      1000,mesh_depth_view,mesh_depth_camera,{});
  CHECK(!pipeline_repeated.accepted&&pipeline_repeated.rejection==
          ReconstructionIntegrationRejection::kNonMonotonicTimestamp&&
        pipeline_repeated.accepted_frames==1&&pipeline_repeated.rejected_frames==1);
  const auto pipeline_second=reconstruction_pipeline.integrate(
      2000,mesh_depth_view,mesh_depth_camera,{});
  CHECK(pipeline_second.accepted&&pipeline_second.accepted_frames==2);
  const auto pipeline_over_budget=reconstruction_pipeline.integrate(
      3000,mesh_depth_view,mesh_depth_camera,{});
  CHECK(!pipeline_over_budget.accepted&&pipeline_over_budget.rejection==
          ReconstructionIntegrationRejection::kFrameBudgetExceeded);
  const auto pipeline_mesh=reconstruction_pipeline.build();
  CHECK(pipeline_mesh.error.empty()&&pipeline_mesh.telemetry.complete&&
        pipeline_mesh.telemetry.fused_frames==2&&
        pipeline_mesh.telemetry.source_vertices==pipeline_mesh.source_mesh.positions_m.size()&&
        pipeline_mesh.telemetry.simplified_vertices==
          pipeline_mesh.simplified_mesh.positions_m.size()&&
        !pipeline_mesh.source_mesh.positions_m.empty()&&
        validateMesh(pipeline_mesh.source_mesh).valid&&
        validateMesh(pipeline_mesh.simplified_mesh).valid);
  const auto pipeline_mesh_repeated=reconstruction_pipeline.build();
  CHECK(pipeline_mesh_repeated.source_mesh.triangle_indices==
          pipeline_mesh.source_mesh.triangle_indices&&
        pipeline_mesh_repeated.simplified_mesh.triangle_indices==
          pipeline_mesh.simplified_mesh.triangle_indices);
  reconstruction_pipeline.reset();
  CHECK(reconstruction_pipeline.acceptedFrames()==0&&
        reconstruction_pipeline.rejectedFrames()==0&&
        !reconstruction_pipeline.fusionTruncated());
  auto truncated_pipeline_config=pipeline_config;
  truncated_pipeline_config.fusion.max_blocks=1;
  ReconstructionFusionPipeline truncated_pipeline(truncated_pipeline_config);
  const auto truncated_pipeline_update=truncated_pipeline.integrate(
      1000,mesh_depth_view,mesh_depth_camera,{});
  CHECK(truncated_pipeline_update.accepted&&truncated_pipeline_update.fusion_truncated&&
        truncated_pipeline.build().telemetry.fusion_truncated);
  auto invalid_pipeline_config=pipeline_config;
  invalid_pipeline_config.max_integrated_frames=0;
  bool rejected_pipeline_config=false;
  try{ReconstructionFusionPipeline invalid_pipeline(invalid_pipeline_config);}
  catch(const std::invalid_argument&){rejected_pipeline_config=true;}
  CHECK(rejected_pipeline_config);
  invalid_pipeline_config=pipeline_config;
  invalid_pipeline_config.simplification.sharp_edge_angle_degrees=180.0;
  rejected_pipeline_config=false;
  try{ReconstructionFusionPipeline invalid_pipeline(invalid_pipeline_config);}
  catch(const std::invalid_argument&){rejected_pipeline_config=true;}
  CHECK(rejected_pipeline_config);

  // Texture source selection uses calibrated projection, facing, image support
  // and measured depth visibility without mutating the source mesh or building an atlas.
  ReconstructionMesh texture_mesh;
  texture_mesh.positions_m={{-0.5F,-0.5F,2.0F},{0.5F,-0.5F,2.0F},
                            {0.5F,0.5F,2.0F},{-0.5F,0.5F,2.0F}};
  texture_mesh.triangle_indices={0,2,1,0,3,2};
  const PinholeRadTanCamera texture_camera({100,100,80,80,49.5,49.5});
  std::vector<float> texture_depth(10000,2.0F);
  std::vector<float> texture_confidence(10000,1.0F);
  std::vector<float> texture_uncertainty(10000,0.005F);
  const TsdfDepthImageView texture_depth_view{100,100,100,texture_depth.data(),
    texture_depth.size(),texture_confidence.data(),100,texture_confidence.size(),
    texture_uncertainty.data(),100,texture_uncertainty.size()};
  std::vector<float> occluding_depth(10000,1.0F);
  auto occluding_depth_view=texture_depth_view;
  occluding_depth_view.depth_z_m=occluding_depth.data();
  Pose outside_texture_pose;outside_texture_pose.translation={5,0,0};
  Pose back_texture_pose;back_texture_pose.rotation={0,0,1,0};
  back_texture_pose.translation={0,0,3};
  std::vector<TextureViewCandidate> texture_candidates{
    {3,"missing-depth.i420",&texture_camera,{},1.0,nullptr},
    {2,"occluded.i420",&texture_camera,{},1.0,&occluding_depth_view},
    {1,"visible.i420",&texture_camera,{},0.8,&texture_depth_view},
    {5,"back.i420",&texture_camera,back_texture_pose,1.0,&texture_depth_view},
    {4,"outside.i420",&texture_camera,outside_texture_pose,1.0,&texture_depth_view}};
  const auto texture_selection=selectTextureViews(texture_mesh,texture_candidates);
  CHECK(texture_selection.input_valid&&!texture_selection.truncated&&
        texture_selection.assigned_triangles==2&&
        texture_selection.unassigned_triangles==0&&
        texture_selection.rejected_depth_inconsistent>0&&
        texture_selection.rejected_missing_depth>0&&
        texture_selection.rejected_back_facing>0&&
        texture_selection.rejected_outside_image>0&&
        texture_selection.views.size()==5&&texture_selection.alternatives.size()==2&&
        !texture_selection.alternatives_truncated&&
        texture_selection.views[0].image_reference=="visible.i420");
  CHECK(std::all_of(texture_selection.triangles.begin(),
                    texture_selection.triangles.end(),[](const auto& triangle){
    return triangle.assigned&&triangle.frame_id==1&&triangle.view_index==0&&
      triangle.score>0.0&&
      std::all_of(triangle.normalized_uv.begin(),triangle.normalized_uv.end(),
                  [](const auto& uv){return uv.x>=0&&uv.x<=1&&uv.y>=0&&uv.y<=1;});}));
  std::reverse(texture_candidates.begin(),texture_candidates.end());
  const auto repeated_texture_selection=selectTextureViews(texture_mesh,texture_candidates);
  CHECK(repeated_texture_selection.triangles[0].frame_id==
          texture_selection.triangles[0].frame_id&&
        repeated_texture_selection.triangles[0].normalized_uv[0].x==
          texture_selection.triangles[0].normalized_uv[0].x);
  TextureViewSelectionConfig no_depth_texture_config;
  no_depth_texture_config.require_depth_visibility=false;
  const std::vector<TextureViewCandidate> no_depth_candidate{
    {7,"fallback.i420",&texture_camera,{},0.7,nullptr}};
  const auto no_depth_texture_selection=selectTextureViews(
      texture_mesh,no_depth_candidate,no_depth_texture_config);
  CHECK(no_depth_texture_selection.input_valid&&
        no_depth_texture_selection.assigned_triangles==2&&
        no_depth_texture_selection.triangles[0].frame_id==7);
  std::vector<float> unknown_texture_depth(10000,
      std::numeric_limits<float>::quiet_NaN());
  auto unknown_texture_depth_view=texture_depth_view;
  unknown_texture_depth_view.depth_z_m=unknown_texture_depth.data();
  const std::vector<TextureViewCandidate> unknown_texture_candidate{
    {8,"unknown.i420",&texture_camera,{},1.0,&unknown_texture_depth_view}};
  const auto unknown_texture_selection=selectTextureViews(
      texture_mesh,unknown_texture_candidate);
  CHECK(unknown_texture_selection.input_valid&&
        unknown_texture_selection.assigned_triangles==0&&
        unknown_texture_selection.rejected_missing_depth==2);
  TsdfDepthImageView malformed_texture_depth;
  malformed_texture_depth.width=100;malformed_texture_depth.height=100;
  const std::vector<TextureViewCandidate> malformed_texture_candidate{
    {9,"malformed.i420",&texture_camera,{},1.0,&malformed_texture_depth}};
  const auto malformed_texture_selection=selectTextureViews(
      texture_mesh,malformed_texture_candidate);
  CHECK(!malformed_texture_selection.input_valid&&
        malformed_texture_selection.error=="invalid texture view candidate");
  auto limited_texture_config=no_depth_texture_config;
  limited_texture_config.max_projection_tests=1;
  const auto limited_texture_selection=selectTextureViews(
      texture_mesh,no_depth_candidate,limited_texture_config);
  CHECK(limited_texture_selection.input_valid&&limited_texture_selection.truncated&&
        limited_texture_selection.projection_tests==1&&
        limited_texture_selection.assigned_triangles==1&&
        limited_texture_selection.unassigned_triangles==1);
  auto alternative_limited_config=no_depth_texture_config;
  alternative_limited_config.max_alternative_records=1;
  const auto alternative_limited_selection=selectTextureViews(
      texture_mesh,no_depth_candidate,alternative_limited_config);
  CHECK(alternative_limited_selection.input_valid&&
        !alternative_limited_selection.truncated&&
        alternative_limited_selection.alternatives_truncated&&
        alternative_limited_selection.assigned_triangles==2&&
        alternative_limited_selection.alternatives.size()==1);
  auto duplicate_texture_candidates=no_depth_candidate;
  duplicate_texture_candidates.push_back(no_depth_candidate.front());
  const auto duplicate_texture_selection=selectTextureViews(
      texture_mesh,duplicate_texture_candidates,no_depth_texture_config);
  CHECK(!duplicate_texture_selection.input_valid&&
        duplicate_texture_selection.error=="invalid texture view candidate");
  auto invalid_texture_config=no_depth_texture_config;
  invalid_texture_config.min_consistent_depth_samples=5;
  bool rejected_texture_config=false;
  try{selectTextureViews(texture_mesh,no_depth_candidate,invalid_texture_config);}
  catch(const std::invalid_argument&){rejected_texture_config=true;}
  CHECK(rejected_texture_config);

  // Coherent labeling may switch only to visibility-approved alternatives and
  // reduces shared-edge seams without connecting non-manifold regions.
  TextureViewSelectionResult manual_texture_selection;
  manual_texture_selection.input_valid=true;
  manual_texture_selection.views={{10,"view-a.i420",100,100},
                                  {20,"view-b.i420",100,100}};
  manual_texture_selection.alternatives={
    {0,10,{},0.90,1.0,100.0},{1,20,{},0.85,1.0,100.0},
    {1,20,{},0.90,1.0,100.0},{0,10,{},0.88,1.0,100.0}};
  manual_texture_selection.triangles.resize(2);
  manual_texture_selection.triangles[0].assigned=true;
  manual_texture_selection.triangles[0].view_index=0;
  manual_texture_selection.triangles[0].frame_id=10;
  manual_texture_selection.triangles[0].score=0.90;
  manual_texture_selection.triangles[0].alternative_offset=0;
  manual_texture_selection.triangles[0].alternative_count=2;
  manual_texture_selection.triangles[1].assigned=true;
  manual_texture_selection.triangles[1].view_index=1;
  manual_texture_selection.triangles[1].frame_id=20;
  manual_texture_selection.triangles[1].score=0.90;
  manual_texture_selection.triangles[1].alternative_offset=2;
  manual_texture_selection.triangles[1].alternative_count=2;
  TextureLabelOptimizationConfig label_config;label_config.seam_penalty=0.20;
  const auto coherent_labels=optimizeTextureViewLabels(
      texture_mesh,manual_texture_selection,label_config);
  CHECK(coherent_labels.input_valid&&!coherent_labels.truncated&&
        coherent_labels.seam_edges_before==1&&coherent_labels.seam_edges_after==0&&
        coherent_labels.patches_before==2&&coherent_labels.patches_after==1&&
        coherent_labels.changed_labels==1&&coherent_labels.iterations>=1&&
        coherent_labels.triangles[0].frame_id==20&&
        coherent_labels.triangles[1].frame_id==20);
  auto data_only_label_config=label_config;data_only_label_config.seam_penalty=0.0;
  const auto data_only_labels=optimizeTextureViewLabels(
      texture_mesh,manual_texture_selection,data_only_label_config);
  CHECK(data_only_labels.input_valid&&data_only_labels.changed_labels==0&&
        data_only_labels.seam_edges_after==1);
  auto label_budget_config=label_config;label_budget_config.max_label_evaluations=1;
  const auto limited_labels=optimizeTextureViewLabels(
      texture_mesh,manual_texture_selection,label_budget_config);
  CHECK(limited_labels.input_valid&&limited_labels.truncated&&
        limited_labels.label_evaluations==1);
  auto edge_limited_label_config=label_config;edge_limited_label_config.max_unique_edges=1;
  const auto edge_limited_labels=optimizeTextureViewLabels(
      texture_mesh,manual_texture_selection,edge_limited_label_config);
  CHECK(edge_limited_labels.input_valid&&edge_limited_labels.truncated&&
        edge_limited_labels.error=="texture label edge budget exceeded");
  ReconstructionMesh texture_non_manifold_mesh;
  texture_non_manifold_mesh.positions_m={{0,0,0},{1,0,0},{0,1,0},{0,-1,0},{0,0,1}};
  texture_non_manifold_mesh.triangle_indices={0,1,2,1,0,3,0,1,4};
  auto non_manifold_selection=manual_texture_selection;
  non_manifold_selection.triangles.push_back(non_manifold_selection.triangles.back());
  non_manifold_selection.triangles.back().alternative_offset=0;
  non_manifold_selection.triangles.back().alternative_count=0;
  const auto non_manifold_labels=optimizeTextureViewLabels(
      texture_non_manifold_mesh,non_manifold_selection,label_config);
  CHECK(non_manifold_labels.input_valid&&non_manifold_labels.non_manifold_edges==1);
  auto sharp_label_config=label_config;sharp_label_config.max_normal_angle_degrees=45.0;
  const auto sharp_labels=optimizeTextureViewLabels(
      crease_mesh,manual_texture_selection,sharp_label_config);
  CHECK(sharp_labels.input_valid&&sharp_labels.sharp_edges==1&&
        sharp_labels.changed_labels==0&&sharp_labels.patches_after==2);
  auto invalid_label_config=label_config;invalid_label_config.max_iterations=0;
  bool rejected_label_config=false;
  try{optimizeTextureViewLabels(texture_mesh,manual_texture_selection,invalid_label_config);}
  catch(const std::invalid_argument&){rejected_label_config=true;}
  CHECK(rejected_label_config);

  // Atlas layout turns same-view connected labels into UV islands, duplicates
  // vertices only at patch boundaries, and leaves pixel baking as a later stage.
  TextureAtlasLayoutConfig atlas_config;atlas_config.atlas_width=256;
  atlas_config.atlas_height=256;
  const auto atlas_layout=layoutTextureAtlas(
      texture_mesh,texture_selection.views,texture_selection.triangles,atlas_config);
  CHECK(atlas_layout.input_valid&&!atlas_layout.truncated&&atlas_layout.error.empty()&&
        atlas_layout.patches.size()==1&&atlas_layout.assigned_triangles==2&&
        atlas_layout.omitted_unassigned_triangles==0&&
        atlas_layout.mesh.positions_m.size()==4&&atlas_layout.duplicated_vertices==0&&
        atlas_layout.mesh.base_color_texture.uri.empty()&&
        atlas_layout.mesh.texcoords.size()==atlas_layout.mesh.positions_m.size()&&
        validateMesh(atlas_layout.mesh).valid);
  CHECK(std::all_of(atlas_layout.mesh.texcoords.begin(),atlas_layout.mesh.texcoords.end(),
                    [](const auto& uv){return uv.x>=0&&uv.x<=1&&uv.y>=0&&uv.y<=1;}));
  const auto repeated_atlas_layout=layoutTextureAtlas(
      texture_mesh,texture_selection.views,texture_selection.triangles,atlas_config);
  CHECK(repeated_atlas_layout.mesh.triangle_indices==atlas_layout.mesh.triangle_indices&&
        repeated_atlas_layout.mesh.texcoords.size()==atlas_layout.mesh.texcoords.size()&&
        std::equal(repeated_atlas_layout.mesh.texcoords.begin(),
          repeated_atlas_layout.mesh.texcoords.end(),atlas_layout.mesh.texcoords.begin(),
          [](const auto& first,const auto& second){
            return first.x==second.x&&first.y==second.y;})&&
        repeated_atlas_layout.patches[0].atlas_x_px==atlas_layout.patches[0].atlas_x_px&&
        repeated_atlas_layout.patches[0].atlas_y_px==atlas_layout.patches[0].atlas_y_px);
  const auto split_atlas_layout=layoutTextureAtlas(
      texture_mesh,manual_texture_selection.views,manual_texture_selection.triangles,
      atlas_config);
  CHECK(split_atlas_layout.input_valid&&split_atlas_layout.patches.size()==2&&
        split_atlas_layout.mesh.positions_m.size()==6&&
        split_atlas_layout.duplicated_vertices==2&&validateMesh(split_atlas_layout.mesh).valid);
  auto scaled_atlas_config=atlas_config;scaled_atlas_config.atlas_width=40;
  scaled_atlas_config.atlas_height=40;
  const auto scaled_atlas_layout=layoutTextureAtlas(
      texture_mesh,texture_selection.views,texture_selection.triangles,scaled_atlas_config);
  CHECK(scaled_atlas_layout.input_valid&&!scaled_atlas_layout.truncated&&
        scaled_atlas_layout.uniform_scale<1.0&&
        scaled_atlas_layout.uniform_scale>=scaled_atlas_config.min_uniform_scale);
  auto capacity_atlas_config=atlas_config;capacity_atlas_config.atlas_width=16;
  capacity_atlas_config.atlas_height=16;capacity_atlas_config.min_uniform_scale=0.5;
  const auto capacity_atlas_layout=layoutTextureAtlas(
      texture_mesh,texture_selection.views,texture_selection.triangles,
      capacity_atlas_config);
  CHECK(capacity_atlas_layout.input_valid&&capacity_atlas_layout.truncated&&
        capacity_atlas_layout.error=="texture atlas capacity exceeded");
  auto unassigned_atlas_labels=texture_selection.triangles;
  unassigned_atlas_labels[1].assigned=false;
  unassigned_atlas_labels[1].alternative_count=0;
  const auto unassigned_atlas_layout=layoutTextureAtlas(
      texture_mesh,texture_selection.views,unassigned_atlas_labels,atlas_config);
  CHECK(unassigned_atlas_layout.input_valid&&
        unassigned_atlas_layout.error==
          "atlas requires every triangle to have a texture label");
  auto edge_limited_atlas_config=atlas_config;
  edge_limited_atlas_config.max_unique_edges=1;
  const auto edge_limited_atlas_layout=layoutTextureAtlas(
      texture_mesh,texture_selection.views,texture_selection.triangles,
      edge_limited_atlas_config);
  CHECK(edge_limited_atlas_layout.input_valid&&edge_limited_atlas_layout.truncated&&
        edge_limited_atlas_layout.error=="texture atlas edge budget exceeded");
  auto sharp_atlas_labels=manual_texture_selection.triangles;
  sharp_atlas_labels[1].view_index=0;sharp_atlas_labels[1].frame_id=10;
  TextureAtlasLayoutConfig sharp_atlas_config=atlas_config;
  sharp_atlas_config.max_normal_angle_degrees=45.0;
  const auto sharp_atlas_layout=layoutTextureAtlas(
      crease_mesh,manual_texture_selection.views,sharp_atlas_labels,sharp_atlas_config);
  CHECK(sharp_atlas_layout.input_valid&&sharp_atlas_layout.sharp_edges==1&&
        sharp_atlas_layout.patches.size()==2&&sharp_atlas_layout.duplicated_vertices==2);
  auto invalid_atlas_config=atlas_config;invalid_atlas_config.atlas_width=8;
  bool rejected_atlas_config=false;
  try{layoutTextureAtlas(texture_mesh,texture_selection.views,
                         texture_selection.triangles,invalid_atlas_config);}
  catch(const std::invalid_argument&){rejected_atlas_config=true;}
  CHECK(rejected_atlas_config);

  // Calibrated inverse-depth plane sweep recovers a synthetic textured plane.
  constexpr int mvs_width=64,mvs_height=48;
  auto mvs_texture=[](double x,double y){return static_cast<std::uint8_t>(std::max(0.0,
    std::min(255.0,128.0+48.0*std::sin(0.17*x)+37.0*std::cos(0.13*y)+
      31.0*std::sin(0.07*x*y))));};
  std::vector<std::uint8_t> mvs_reference(mvs_width*mvs_height),mvs_left(mvs_width*mvs_height),
    mvs_right(mvs_width*mvs_height);
  for(int y=0;y<mvs_height;++y)for(int x=0;x<mvs_width;++x){
    mvs_reference[y*mvs_width+x]=mvs_texture(x,y);
    mvs_left[y*mvs_width+x]=mvs_texture(x+2.0,y);
    mvs_right[y*mvs_width+x]=mvs_texture(x-2.0,y);}
  const PinholeRadTanCamera mvs_camera({mvs_width,mvs_height,40,40,31.5,23.5});
  const GrayImageView mvs_reference_view{mvs_width,mvs_height,mvs_width,mvs_reference.data(),mvs_reference.size()};
  const GrayImageView mvs_left_view{mvs_width,mvs_height,mvs_width,mvs_left.data(),mvs_left.size()};
  const GrayImageView mvs_right_view{mvs_width,mvs_height,mvs_width,mvs_right.data(),mvs_right.size()};
  const MultiViewStereoFrame mvs_reference_frame{mvs_reference_view,&mvs_camera,{}};
  const std::vector<MultiViewStereoFrame> mvs_sources{
    {mvs_left_view,&mvs_camera,{{},{0.1,0,0}}},
    {mvs_right_view,&mvs_camera,{{},{-0.1,0,0}}}};
  MultiViewStereoConfig mvs_config;mvs_config.min_depth_m=1;mvs_config.max_depth_m=4;
  mvs_config.depth_hypotheses=31;mvs_config.pixel_stride=4;mvs_config.min_zncc=0.85;
  mvs_config.min_winner_margin=0.005;
  auto coarse_mvs_config=mvs_config;coarse_mvs_config.refinement_levels=0;
  const auto coarse_mvs_depth=estimateMultiViewDepth(mvs_reference_frame,mvs_sources,
    coarse_mvs_config);
  const auto mvs_depth=estimateMultiViewDepth(mvs_reference_frame,mvs_sources,mvs_config);
  CHECK(coarse_mvs_depth.valid&&mvs_depth.valid&&mvs_depth.valid_pixels>40);
  const std::size_t mvs_center=23*mvs_width+31;
  CHECK(std::isfinite(mvs_depth.depth_m[mvs_center])&&
        std::abs(mvs_depth.depth_m[mvs_center]-2.0F)<0.12F&&
        mvs_depth.confidence[mvs_center]>0.5F&&mvs_depth.uncertainty_m[mvs_center]>0);
  CHECK(std::isfinite(coarse_mvs_depth.uncertainty_m[mvs_center])&&
        mvs_depth.uncertainty_m[mvs_center]<coarse_mvs_depth.uncertainty_m[mvs_center]);

  // Census ordering remains usable under a strong monotonic response change
  // that a deliberately strict linear-correlation gate rejects.
  auto gamma_response=[](std::uint8_t value){const double normalized=value/255.0;
    const double squared=normalized*normalized;
    return static_cast<std::uint8_t>(std::lround(255.0*squared*squared*normalized));};
  std::vector<std::uint8_t> mvs_left_gamma(mvs_left.size()),mvs_right_gamma(mvs_right.size());
  std::transform(mvs_left.begin(),mvs_left.end(),mvs_left_gamma.begin(),gamma_response);
  std::transform(mvs_right.begin(),mvs_right.end(),mvs_right_gamma.begin(),gamma_response);
  const GrayImageView mvs_left_gamma_view{mvs_width,mvs_height,mvs_width,
    mvs_left_gamma.data(),mvs_left_gamma.size()};
  const GrayImageView mvs_right_gamma_view{mvs_width,mvs_height,mvs_width,
    mvs_right_gamma.data(),mvs_right_gamma.size()};
  const std::vector<MultiViewStereoFrame> mvs_gamma_sources{
    {mvs_left_gamma_view,&mvs_camera,{{},{0.1,0,0}}},
    {mvs_right_gamma_view,&mvs_camera,{{},{-0.1,0,0}}}};
  auto census_mvs_config=mvs_config;
  census_mvs_config.matching_cost=MultiViewStereoMatchingCost::kCensus;
  census_mvs_config.census_intensity_tolerance=0.0;census_mvs_config.min_census_bits=6;
  census_mvs_config.min_zncc=0.90;
  const auto census_mvs_depth=estimateMultiViewDepth(mvs_reference_frame,mvs_gamma_sources,
    census_mvs_config);
  const std::size_t census_probe=31*mvs_width+35;
  CHECK(census_mvs_depth.valid&&census_mvs_depth.valid_pixels>40&&
        std::isfinite(census_mvs_depth.depth_m[census_probe])&&
        std::abs(census_mvs_depth.depth_m[census_probe]-2.0F)<0.12F);
  auto strict_zncc_mvs_config=census_mvs_config;
  strict_zncc_mvs_config.matching_cost=MultiViewStereoMatchingCost::kZncc;
  strict_zncc_mvs_config.min_zncc=0.90;
  const auto strict_zncc_mvs_depth=estimateMultiViewDepth(mvs_reference_frame,mvs_gamma_sources,
    strict_zncc_mvs_config);
  CHECK(strict_zncc_mvs_depth.valid&&
        !std::isfinite(strict_zncc_mvs_depth.depth_m[census_probe]));

  // Cached source-view depth must agree metrically and survive a geometric
  // source-to-reference round trip. One inconsistent view reduces confidence;
  // requiring both views rejects the same reference sample.
  MultiViewStereoDepth consistent_source_depth;
  consistent_source_depth.width=mvs_width;consistent_source_depth.height=mvs_height;
  consistent_source_depth.depth_m.assign(mvs_width*mvs_height,2.0F);
  consistent_source_depth.confidence.assign(mvs_width*mvs_height,1.0F);
  consistent_source_depth.uncertainty_m.assign(mvs_width*mvs_height,0.01F);
  consistent_source_depth.valid_pixels=mvs_width*mvs_height;
  consistent_source_depth.valid=true;
  auto inconsistent_source_depth=consistent_source_depth;
  inconsistent_source_depth.depth_m.assign(mvs_width*mvs_height,3.0F);
  const std::vector<MultiViewStereoDepthFrame> consistency_sources{
    {mvs_sources[0],&consistent_source_depth},
    {mvs_sources[1],&inconsistent_source_depth}};
  MultiViewStereoConsistencyConfig consistency_config;
  consistency_config.search_radius_px=1;consistency_config.min_source_confidence=0.5;
  const auto consistency_depth=filterMultiViewDepthConsistency(
    mvs_reference_frame,mvs_depth,consistency_sources,consistency_config);
  CHECK(consistency_depth.valid&&
        consistency_depth.consistency_tested_pixels==mvs_depth.valid_pixels&&
        consistency_depth.consistency_rejected_pixels<consistency_depth.consistency_tested_pixels&&
        std::isfinite(consistency_depth.depth_m[mvs_center])&&
        consistency_depth.confidence[mvs_center]<mvs_depth.confidence[mvs_center]);
  auto strict_consistency_config=consistency_config;
  strict_consistency_config.min_consistent_views=2;
  const auto strict_consistency_depth=filterMultiViewDepthConsistency(
    mvs_reference_frame,mvs_depth,consistency_sources,strict_consistency_config);
  CHECK(strict_consistency_depth.valid&&strict_consistency_depth.valid_pixels==0&&
        strict_consistency_depth.consistency_rejected_pixels==
          strict_consistency_depth.consistency_tested_pixels&&
        !std::isfinite(strict_consistency_depth.depth_m[mvs_center]));

  auto shifted_source_depth=consistent_source_depth;
  const float mvs_nan=std::numeric_limits<float>::quiet_NaN();
  shifted_source_depth.depth_m.assign(mvs_width*mvs_height,mvs_nan);
  shifted_source_depth.confidence.assign(mvs_width*mvs_height,0.0F);
  shifted_source_depth.uncertainty_m.assign(mvs_width*mvs_height,mvs_nan);
  shifted_source_depth.valid_pixels=1;
  const auto center_ray=mvs_camera.unproject({31.0,23.0});
  CHECK(center_ray.has_value());
  const Vec3 center_point=*center_ray*(mvs_depth.depth_m[mvs_center]/center_ray->z);
  const Vec3 left_camera_point=center_point-Vec3{0.1,0,0};
  const auto left_projection=mvs_camera.project(left_camera_point);
  CHECK(left_projection.has_value());
  const int shifted_x=static_cast<int>(std::lround(left_projection->x))+1;
  const int shifted_y=static_cast<int>(std::lround(left_projection->y));
  const std::size_t shifted_index=shifted_y*mvs_width+shifted_x;
  shifted_source_depth.depth_m[shifted_index]=static_cast<float>(left_camera_point.z);
  shifted_source_depth.confidence[shifted_index]=1.0F;
  shifted_source_depth.uncertainty_m[shifted_index]=0.01F;
  auto round_trip_config=consistency_config;
  round_trip_config.search_radius_px=2;round_trip_config.max_round_trip_error_px=0.25;
  const auto round_trip_depth=filterMultiViewDepthConsistency(mvs_reference_frame,mvs_depth,
    {{mvs_sources[0],&shifted_source_depth}},round_trip_config);
  CHECK(round_trip_depth.valid&&!std::isfinite(round_trip_depth.depth_m[mvs_center]));

  auto invalid_consistency_config=consistency_config;
  invalid_consistency_config.search_radius_px=9;
  bool rejected_consistency_config=false;
  try{filterMultiViewDepthConsistency(mvs_reference_frame,mvs_depth,consistency_sources,
                                      invalid_consistency_config);}
  catch(const std::invalid_argument&){rejected_consistency_config=true;}
  CHECK(rejected_consistency_config);

  CHECK(!estimateMultiViewDepth(mvs_reference_frame,{mvs_sources.front()},mvs_config).valid);
  auto invalid_census=mvs_config;invalid_census.min_census_bits=25;
  bool rejected_census=false;
  try{estimateMultiViewDepth(mvs_reference_frame,mvs_sources,invalid_census);}
  catch(const std::invalid_argument&){rejected_census=true;}
  CHECK(rejected_census);
  auto invalid_refinement=mvs_config;invalid_refinement.refinement_hypotheses=2;
  bool rejected_refinement=false;
  try{estimateMultiViewDepth(mvs_reference_frame,mvs_sources,invalid_refinement);}
  catch(const std::invalid_argument&){rejected_refinement=true;}
  CHECK(rejected_refinement);

  TemporalDepthFilterConfig temporal_config;temporal_config.max_absolute_difference_m=0.5;
  temporal_config.max_relative_difference=0.5;temporal_config.history_weight=0.5;
  TemporalDepthFilter temporal_filter(temporal_config);
  const PinholeRadTanCamera temporal_camera({2,1,1.0,1.0,0.0,0.0});
  const std::array<float,2> temporal_first{2.0F,3.0F};
  TemporalDepthFrameView temporal_frame;temporal_frame.width=2;temporal_frame.height=1;
  temporal_frame.row_stride_elements=2;temporal_frame.depth_m=temporal_first.data();
  temporal_frame.depth_elements=2;temporal_frame.timestamp_ns=100;
  auto temporal_result=temporal_filter.update(temporal_frame,temporal_camera);
  CHECK(!temporal_result.history_used&&temporal_result.blended_pixels==0);
  const std::array<float,2> temporal_second{2.2F,6.0F};
  temporal_frame.depth_m=temporal_second.data();temporal_frame.timestamp_ns=200;
  temporal_result=temporal_filter.update(temporal_frame,temporal_camera);
  CHECK(temporal_result.history_used&&temporal_result.blended_pixels==1&&
        temporal_result.rejected_history_pixels==1);
  CHECK(std::abs(temporal_result.depth_m[0]-2.1333334F)<1e-5F&&
        temporal_result.depth_m[1]==6.0F);
  const std::array<float,2> temporal_confidence{0.0F,1.0F};
  temporal_frame.confidence=temporal_confidence.data();
  temporal_frame.confidence_row_stride_elements=2;temporal_frame.confidence_elements=2;
  temporal_frame.timestamp_ns=300;
  temporal_result=temporal_filter.update(temporal_frame,temporal_camera);
  CHECK(temporal_result.depth_m[0]==0.0F);
  bool temporal_order_rejected=false;
  try{(void)temporal_filter.update(temporal_frame,temporal_camera);}
  catch(const std::invalid_argument&){temporal_order_rejected=true;}
  CHECK(temporal_order_rejected);

  const std::array<float,4> raw_depth{1.0F,2.0F,3.0F,4.0F};
  const DepthTensorView depth_tensor{2,2,2,DepthTensorElementType::kFloat32,
    raw_depth.data(),sizeof(raw_depth),1.0,0};
  DepthPostprocessConfig depth_post_config;depth_post_config.output_width=1;
  depth_post_config.output_height=1;depth_post_config.max_relative_discontinuity=10;
  const auto resized_depth=postprocessDepthTensor(depth_tensor,depth_post_config);
  CHECK(resized_depth.valid&&resized_depth.depth_meters.size()==1);
  CHECK(std::abs(resized_depth.depth_meters[0]-2.5F)<1e-6F);
  CHECK(resized_depth.confidence[0]==1.0F);
  const std::array<float,4> sparse_raw_depth{1.0F,0.0F,
    std::numeric_limits<float>::quiet_NaN(),3.0F};
  const DepthTensorView sparse_depth_tensor{2,2,2,DepthTensorElementType::kFloat32,
    sparse_raw_depth.data(),sizeof(sparse_raw_depth),1.0,0};
  const auto sparse_resized_depth=postprocessDepthTensor(sparse_depth_tensor,depth_post_config);
  CHECK(std::abs(sparse_resized_depth.depth_meters[0]-2.0F)<1e-6F);
  CHECK(std::abs(sparse_resized_depth.confidence[0]-0.5F)<1e-6F);
  depth_post_config.max_relative_discontinuity=0.1;
  const auto edge_safe_depth=postprocessDepthTensor(depth_tensor,depth_post_config);
  CHECK(std::abs(edge_safe_depth.depth_meters[0]-1.0F)<1e-6F);
  const std::array<std::uint8_t,3> quantized_inverse{2,4,99};
  const DepthTensorView quantized_depth_tensor{2,1,3,DepthTensorElementType::kUInt8,
    quantized_inverse.data(),quantized_inverse.size(),0.5,0};
  depth_post_config.output_width=2;depth_post_config.output_height=1;
  depth_post_config.transform=DepthValueTransform::kInverse;
  depth_post_config.unit_to_meters=2.0;
  const auto inverse_depth=postprocessDepthTensor(quantized_depth_tensor,depth_post_config);
  CHECK(std::abs(inverse_depth.depth_meters[0]-2.0F)<1e-6F);
  CHECK(std::abs(inverse_depth.depth_meters[1]-1.0F)<1e-6F);
  bool depth_budget_rejected=false;depth_post_config.max_output_elements=1;
  try{(void)postprocessDepthTensor(quantized_depth_tensor,depth_post_config);}
  catch(const std::length_error&){depth_budget_rejected=true;}
  CHECK(depth_budget_rejected);
  ark_depth_tensor_v1 c_depth_tensor{};c_depth_tensor.struct_size=sizeof(c_depth_tensor);
  c_depth_tensor.width=2;c_depth_tensor.height=2;c_depth_tensor.row_stride_elements=2;
  c_depth_tensor.element_type=ARK_TENSOR_FLOAT32;c_depth_tensor.data=raw_depth.data();
  c_depth_tensor.data_size_bytes=sizeof(raw_depth);c_depth_tensor.quantization_scale=1;
  ark_depth_postprocess_config_v1 c_depth_config{};
  c_depth_config.struct_size=sizeof(c_depth_config);c_depth_config.output_width=1;
  c_depth_config.output_height=1;c_depth_config.transform=ARK_DEPTH_DIRECT;
  c_depth_config.unit_to_meters=1;c_depth_config.min_depth_meters=0.05;
  c_depth_config.max_depth_meters=20;c_depth_config.max_relative_discontinuity=10;
  c_depth_config.max_output_elements=16;
  ark_depth_postprocess_output_v1 c_depth_output{};
  c_depth_output.struct_size=sizeof(c_depth_output);float c_depth_value=0,c_depth_confidence=0;
  CHECK(ark_postprocess_depth_tensor(&c_depth_tensor,&c_depth_config,&c_depth_value,
    &c_depth_confidence,1,&c_depth_output)==ARK_RESULT_OK);
  CHECK(std::abs(c_depth_value-2.5F)<1e-6F&&c_depth_confidence==1.0F&&
        c_depth_output.required_elements==1);

  const std::array<float,24> detection_rows{
    0.10F,0.10F,0.50F,0.50F,0.90F,1.0F,
    0.12F,0.12F,0.48F,0.48F,0.80F,1.0F,
    0.12F,0.12F,0.48F,0.48F,0.85F,2.0F,
    0.20F,0.20F,0.10F,0.30F,0.95F,3.0F};
  const DetectionTensorView detection_tensor{4,6,6,DetectionTensorElementType::kFloat32,
    detection_rows.data(),sizeof(detection_rows),1.0,0};
  DetectionPostprocessConfig detection_config;detection_config.image_width=100;
  detection_config.image_height=50;detection_config.score_threshold=0.5;
  const auto decoded=decodeDetectionTensor(detection_tensor,detection_config);
  CHECK(decoded.size()==2&&decoded[0].class_id==1&&decoded[1].class_id==2);
  CHECK(std::abs(decoded[0].left-10.0)<1e-5&&std::abs(decoded[0].bottom-25.0)<1e-5);
  DetectionTracker detection_tracker;
  const auto tracked_detections_first=detection_tracker.update(1000,decoded);
  CHECK(tracked_detections_first.size()==2&&tracked_detections_first[0].track_id>0&&
        tracked_detections_first[1].track_id!=tracked_detections_first[0].track_id);
  auto moved_detections=decoded;moved_detections[0].left+=1;moved_detections[0].right+=1;
  const auto tracked_detections_second=detection_tracker.update(2000,moved_detections);
  CHECK(tracked_detections_second[0].track_id==tracked_detections_first[0].track_id&&
        tracked_detections_second[1].track_id==tracked_detections_first[1].track_id);
  CHECK(detection_tracker.update(3000,{}).empty());
  const auto tracked_reappeared=detection_tracker.update(4000,moved_detections);
  CHECK(tracked_reappeared[0].track_id==tracked_detections_first[0].track_id);
  bool tracker_order_rejected=false;
  try{(void)detection_tracker.update(4000,moved_detections);}
  catch(const std::invalid_argument&){tracker_order_rejected=true;}
  CHECK(tracker_order_rejected);
  detection_config.class_aware_nms=false;
  CHECK(decodeDetectionTensor(detection_tensor,detection_config).size()==1);
  const std::array<std::uint8_t,8> quantized_detection{50,50,20,40,80,100,7,7};
  const DetectionTensorView quantized_detection_tensor{1,6,8,
    DetectionTensorElementType::kUInt8,quantized_detection.data(),
    quantized_detection.size(),0.01,0};
  detection_config.class_aware_nms=true;detection_config.box_format=DetectionBoxFormat::kCenterXywh;
  const auto quantized_decoded=decodeDetectionTensor(quantized_detection_tensor,detection_config);
  CHECK(quantized_decoded.size()==1&&quantized_decoded[0].class_id==1);
  CHECK(std::abs(quantized_decoded[0].left-40.0)<1e-6&&
        std::abs(quantized_decoded[0].top-15.0)<1e-6);
  bool candidate_budget_rejected=false;detection_config.max_candidates=0;
  try{(void)decodeDetectionTensor(detection_tensor,detection_config);}
  catch(const std::invalid_argument&){candidate_budget_rejected=true;}
  CHECK(candidate_budget_rejected);
  ark_detection_tensor_v1 c_detection_tensor{};
  c_detection_tensor.struct_size=sizeof(c_detection_tensor);c_detection_tensor.rows=4;
  c_detection_tensor.columns=6;c_detection_tensor.row_stride_elements=6;
  c_detection_tensor.element_type=ARK_TENSOR_FLOAT32;c_detection_tensor.data=detection_rows.data();
  c_detection_tensor.data_size_bytes=sizeof(detection_rows);c_detection_tensor.quantization_scale=1;
  ark_detection_postprocess_config_v1 c_detection_config{};
  c_detection_config.struct_size=sizeof(c_detection_config);c_detection_config.image_width=100;
  c_detection_config.image_height=50;c_detection_config.x0_column=0;
  c_detection_config.y0_column=1;c_detection_config.x1_or_width_column=2;
  c_detection_config.y1_or_height_column=3;c_detection_config.score_column=4;
  c_detection_config.class_column=5;c_detection_config.box_format=ARK_DETECTION_XYXY;
  c_detection_config.normalized_coordinates=1;c_detection_config.class_aware_nms=1;
  c_detection_config.score_threshold=0.5;c_detection_config.iou_threshold=0.45;
  c_detection_config.max_candidates=4096;c_detection_config.max_detections=256;
  ark_detection_postprocess_output_v1 c_detection_output{};
  c_detection_output.struct_size=sizeof(c_detection_output);
  std::array<std::uint32_t,2> c_class_ids{};std::array<double,10> c_detection_values{};
  CHECK(ark_postprocess_detection_tensor(&c_detection_tensor,&c_detection_config,
    c_class_ids.data(),c_detection_values.data(),2,&c_detection_output)==ARK_RESULT_OK);
  CHECK(c_detection_output.detection_count==2&&c_class_ids[0]==1&&c_class_ids[1]==2);

  const std::vector<std::uint8_t> tensor_rgb{
    255,0,0, 0,255,0,
    0,0,255, 255,255,255};
  const TensorImageView tensor_view{2,2,6,TensorImageFormat::kRgb888,
    tensor_rgb.data(),tensor_rgb.size()};
  TensorPreprocessConfig tensor_config;tensor_config.output_width=1;
  tensor_config.output_height=1;tensor_config.output_channels=3;
  const auto float_tensor=preprocessTensorImage(tensor_view,tensor_config);
  CHECK(float_tensor.valid&&float_tensor.elementCount()==3);
  CHECK(std::abs(float_tensor.float_values[0]-0.5F)<1e-6F);
  CHECK(std::abs(float_tensor.float_values[1]-0.5F)<1e-6F);
  CHECK(std::abs(float_tensor.float_values[2]-0.5F)<1e-6F);
  tensor_config.element_type=TensorElementType::kUInt8;
  tensor_config.quantization_scale=1.0/255.0;
  const auto quantized_tensor=preprocessTensorImage(tensor_view,tensor_config);
  CHECK(quantized_tensor.uint8_values.size()==3&&quantized_tensor.uint8_values[0]==128&&
        quantized_tensor.uint8_values[1]==128&&quantized_tensor.uint8_values[2]==128);
  tensor_config.element_type=TensorElementType::kFloat32;tensor_config.swap_red_blue=true;
  tensor_config.output_width=2;tensor_config.output_height=2;
  const auto swapped_tensor=preprocessTensorImage(tensor_view,tensor_config);
  CHECK(swapped_tensor.float_values[0]==0.0F&&swapped_tensor.float_values[2]==1.0F);
  bool tensor_budget_rejected=false;tensor_config.max_output_elements=1;
  try{(void)preprocessTensorImage(tensor_view,tensor_config);}
  catch(const std::length_error&){tensor_budget_rejected=true;}
  CHECK(tensor_budget_rejected);
  const std::vector<std::uint8_t> padded_gray{0,255,77};
  const TensorImageView gray_tensor_view{2,1,3,TensorImageFormat::kGray8,
    padded_gray.data(),padded_gray.size()};
  TensorPreprocessConfig gray_tensor_config;gray_tensor_config.output_width=2;
  gray_tensor_config.output_height=1;gray_tensor_config.output_channels=3;
  gray_tensor_config.mean={0.5,0.5,0.5,0.0};
  gray_tensor_config.standard_deviation={0.5,0.5,0.5,1.0};
  const auto gray_tensor=preprocessTensorImage(gray_tensor_view,gray_tensor_config);
  CHECK(gray_tensor.float_values[0]==-1.0F&&gray_tensor.float_values[1]==-1.0F&&
        gray_tensor.float_values[2]==-1.0F&&gray_tensor.float_values[3]==1.0F);
  gray_tensor_config.element_type=TensorElementType::kInt8;
  gray_tensor_config.quantization_scale=0.001;
  const auto clamped_tensor=preprocessTensorImage(gray_tensor_view,gray_tensor_config);
  CHECK(clamped_tensor.int8_values.front()==-128&&clamped_tensor.int8_values.back()==127);
  bool malformed_tensor_rejected=false;
  try{const TensorImageView malformed{2,1,3,TensorImageFormat::kGray8,
      padded_gray.data(),1};(void)preprocessTensorImage(malformed,gray_tensor_config);}
  catch(const std::invalid_argument&){malformed_tensor_rejected=true;}
  CHECK(malformed_tensor_rejected);
  ark_tensor_image_v1 c_tensor_image{};c_tensor_image.struct_size=sizeof(c_tensor_image);
  c_tensor_image.width=2;c_tensor_image.height=2;c_tensor_image.row_stride_bytes=6;
  c_tensor_image.format=ARK_TENSOR_RGB888;c_tensor_image.data=tensor_rgb.data();
  c_tensor_image.data_size=tensor_rgb.size();
  ark_tensor_preprocess_config_v1 c_tensor_config{};
  c_tensor_config.struct_size=sizeof(c_tensor_config);c_tensor_config.output_width=1;
  c_tensor_config.output_height=1;c_tensor_config.output_channels=3;
  for(int i=0;i<4;++i)c_tensor_config.standard_deviation[i]=1;
  c_tensor_config.element_type=ARK_TENSOR_FLOAT32;c_tensor_config.quantization_scale=1;
  c_tensor_config.max_output_elements=16;
  ark_tensor_output_v1 c_tensor_output{};c_tensor_output.struct_size=sizeof(c_tensor_output);
  CHECK(ark_preprocess_tensor(&c_tensor_image,&c_tensor_config,nullptr,0,
                              &c_tensor_output)==ARK_RESULT_OK);
  CHECK(c_tensor_output.required_bytes==3*sizeof(float));
  std::array<float,3> c_tensor_values{};
  CHECK(ark_preprocess_tensor(&c_tensor_image,&c_tensor_config,c_tensor_values.data(),
                              sizeof(c_tensor_values),&c_tensor_output)==ARK_RESULT_OK);
  CHECK(std::abs(c_tensor_values[0]-0.5F)<1e-6F);

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

  // Bounded appearance retrieval finds old keyframes but never certifies loops.
  const auto make_place_descriptors=[](std::uint32_t seed){
    std::vector<BinaryFeatureDescriptor> output;
    for(std::uint32_t i=0;i<24;++i){BinaryFeatureDescriptor descriptor;descriptor.feature_id=i+1;
      for(std::size_t byte=0;byte<descriptor.bytes.size();++byte)
        descriptor.bytes[byte]=static_cast<std::uint8_t>(seed+i*37+byte*13+(i*byte)%17);
      output.push_back(descriptor);}
    return output;
  };
  PlaceRecognitionConfig place_config;place_config.max_keyframes=2;
  place_config.max_candidates=2;place_config.min_shared_words=10;
  place_config.min_temporal_separation_ns=100;place_config.min_similarity=0.20;
  PlaceRecognitionDatabase place_database(place_config);
  const auto place_a=make_place_descriptors(11),place_b=make_place_descriptors(97);
  CHECK(place_database.insert(1,1000,place_a));
  CHECK(place_database.insert(2,2000,place_b));
  CHECK(!place_database.insert(1,3000,place_a));
  const auto temporally_near=place_database.query(1050,place_a);
  CHECK(std::none_of(temporally_near.begin(),temporally_near.end(),
                     [](const auto& value){return value.keyframe_id==1;}));
  const auto recognized_place=place_database.query(5000,place_a);
  CHECK(!recognized_place.empty()&&recognized_place.front().keyframe_id==1&&
        recognized_place.front().similarity>0.99&&recognized_place.front().shared_words>=10);
  CHECK(place_database.insert(3,3000,make_place_descriptors(173)));
  CHECK(place_database.size()==2);
  const auto after_eviction=place_database.query(5000,place_a);
  CHECK(std::none_of(after_eviction.begin(),after_eviction.end(),
                     [](const auto& value){return value.keyframe_id==1;}));
  place_database.reset();CHECK(place_database.size()==0&&place_database.query(5000,place_a).empty());

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
  ark_surfel_depth_render_v1 c_depth_render{};c_depth_render.struct_size=sizeof(c_depth_render);
  CHECK(ark_visual_render_surfel_depth(visual_c_handle,&c_world_pose,0.05,10,64,1000000,
      nullptr,0,&c_depth_render)==ARK_RESULT_OK);
  CHECK(c_depth_render.required_pixel_count==static_cast<std::uint64_t>(flow_width*flow_height));
  std::vector<float> c_rendered_depth(c_depth_render.required_pixel_count);
  CHECK(ark_visual_render_surfel_depth(visual_c_handle,&c_world_pose,0.05,10,64,1000000,
      c_rendered_depth.data(),c_rendered_depth.size(),&c_depth_render)==ARK_RESULT_OK);
  CHECK(std::any_of(c_rendered_depth.begin(),c_rendered_depth.end(),
                    [](float depth){return depth>0;}));
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
  SurfelMap render_surfel_map(spherical_config);
  SurfelObservation render_surface{{0,0,2},{0,0,1},0.9,0.2,2400000000LL,true};
  render_surfel_map.integrate({render_surface},render_surface.timestamp_ns);
  const PinholeRadTanCamera render_camera({64,48,50,50,32,24});
  const auto rendered_depth=render_surfel_map.renderDepth(render_camera,{});
  CHECK(rendered_depth.valid&&rendered_depth.projected_surfel_count==1);
  CHECK(std::abs(rendered_depth.depth_z_m[24*64+32]-2.0F)<1e-6F);
  CHECK(std::count_if(rendered_depth.depth_z_m.begin(),rendered_depth.depth_z_m.end(),
                      [](float depth){return depth>0;})>20);
  SurfelDepthRenderConfig tiny_render_budget;tiny_render_budget.max_rasterized_pixels=1;
  CHECK(render_surfel_map.renderDepth(render_camera,{},tiny_render_budget).truncated);
  const auto no_normal_depth=spherical_surfel_map.renderDepth(render_camera,{});
  CHECK(std::none_of(no_normal_depth.depth_z_m.begin(),no_normal_depth.depth_z_m.end(),
                     [](float depth){return depth>0;}));
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

  // Appearance candidates become loops only after metric geometry and pose-prior gates.
  LoopKeyframe loop_reference;loop_reference.id=41;loop_reference.timestamp_ns=1000000000LL;
  LoopKeyframe loop_current;loop_current.id=84;loop_current.timestamp_ns=8000000000LL;
  const Pose loop_transform{Quaternion::fromRotationVector({0.08,-0.04,0.12}),{0.3,-0.2,0.1}};
  loop_current.world_from_keyframe.rotation=loop_transform.rotation.inverse();
  loop_current.world_from_keyframe.translation=
      loop_current.world_from_keyframe.rotation.rotate(loop_transform.translation*-1.0);
  for(std::size_t i=0;i<saved_points.size();++i){
    const auto reference_id=static_cast<std::uint32_t>(100+i);
    const auto current_id=static_cast<std::uint32_t>(200+i);
    loop_reference.landmarks.push_back({reference_id,saved_points[i]});
    loop_current.landmarks.push_back({current_id,
      loop_transform.rotation.rotate(saved_points[i])+loop_transform.translation});
    BinaryFeatureDescriptor reference_descriptor;reference_descriptor.feature_id=reference_id;
    for(std::size_t byte=0;byte<reference_descriptor.bytes.size();++byte)
      reference_descriptor.bytes[byte]=static_cast<std::uint8_t>(i*41+byte*17+3);
    auto current_descriptor=reference_descriptor;current_descriptor.feature_id=current_id;
    loop_reference.descriptors.push_back(reference_descriptor);
    loop_current.descriptors.push_back(current_descriptor);
  }
  for(std::uint32_t i=0;i<2;++i){
    const std::uint32_t reference_id=108+i,current_id=208+i;
    loop_reference.landmarks.push_back({reference_id,{2.0+i,2.0-i,1.5}});
    loop_current.landmarks.push_back({current_id,{-5.0+i,4.0,3.0-i}});
    BinaryFeatureDescriptor descriptor;descriptor.feature_id=reference_id;
    for(std::size_t byte=0;byte<descriptor.bytes.size();++byte)
      descriptor.bytes[byte]=static_cast<std::uint8_t>(191+i*29+byte*7);
    auto current_descriptor=descriptor;current_descriptor.feature_id=current_id;
    loop_reference.descriptors.push_back(descriptor);loop_current.descriptors.push_back(current_descriptor);
  }
  const auto verified_loop=verifyLoopClosure(loop_reference,loop_current);
  CHECK(verified_loop.valid&&verified_loop.rejection_reason==LoopClosureRejectionReason::kNone);
  CHECK(verified_loop.descriptor_match_count==saved_points.size()+2&&
        verified_loop.inlier_count==saved_points.size()&&verified_loop.inlier_ratio==0.8&&
        verified_loop.inlier_landmark_matches.size()==saved_points.size()&&
        verified_loop.rmse_m<1e-8);
  CHECK(std::none_of(verified_loop.inlier_landmark_matches.begin(),
        verified_loop.inlier_landmark_matches.end(),[](const auto& match){
          return match.reference_feature_id>=108||match.current_feature_id>=208;}));
  CHECK((verified_loop.current_from_reference.translation-loop_transform.translation).norm()<1e-8);
  auto near_loop=loop_current;near_loop.timestamp_ns=loop_reference.timestamp_ns+1000;
  CHECK(verifyLoopClosure(loop_reference,near_loop).rejection_reason==
        LoopClosureRejectionReason::kTemporallyNear);
  auto inconsistent_loop=loop_current;inconsistent_loop.world_from_keyframe={};
  LoopClosureConfig strict_loop_config;strict_loop_config.max_translation_disagreement_m=0.1;
  strict_loop_config.max_rotation_disagreement_degrees=5.0;
  CHECK(verifyLoopClosure(loop_reference,inconsistent_loop,strict_loop_config).rejection_reason==
        LoopClosureRejectionReason::kPoseDisagreement);
  auto insufficient_loop=loop_current;insufficient_loop.descriptors.resize(5);
  CHECK(verifyLoopClosure(loop_reference,insufficient_loop).rejection_reason==
        LoopClosureRejectionReason::kInsufficientMatches);
  auto invalid_loop=loop_current;invalid_loop.descriptors.front().feature_id=999999;
  CHECK(verifyLoopClosure(loop_reference,invalid_loop).rejection_reason==
        LoopClosureRejectionReason::kInvalidInput);
  auto degenerate_loop=loop_current;
  for(std::size_t i=0;i<degenerate_loop.landmarks.size();++i)
    degenerate_loop.landmarks[i].position_keyframe_m={static_cast<double>(i),0,0};
  CHECK(verifyLoopClosure(loop_reference,degenerate_loop).rejection_reason==
        LoopClosureRejectionReason::kGeometryRejected);

  // Complete-link merging combines true duplicates without chaining nearby points.
  LandmarkMergeConfig merge_config;merge_config.max_cluster_diameter_m=0.03;
  const std::vector<GlobalLandmark> merge_input{
    {10,{0,0,2},2,0.02,1000},{20,{0.02,0,2},3,0.01,2000},
    {30,{0.04,0,2},4,0.03,3000},{40,{1,0,2},2,0.01,4000}};
  const auto merge_result=mergeGlobalLandmarks(merge_input,{{20,30},{10,20}},merge_config);
  CHECK(merge_result.valid&&merge_result.accepted_associations==1&&
        merge_result.rejected_associations==1&&merge_result.landmarks.size()==3);
  const auto merged_landmark=std::find_if(merge_result.landmarks.begin(),merge_result.landmarks.end(),
    [](const auto& value){return value.id==10;});
  CHECK(merged_landmark!=merge_result.landmarks.end()&&merged_landmark->observation_count==5);
  CHECK(std::abs(merged_landmark->position_world_m.x-0.012)<1e-12);
  const auto alias20=std::find_if(merge_result.aliases.begin(),merge_result.aliases.end(),
    [](const auto& value){return value.source_id==20;});
  CHECK(alias20!=merge_result.aliases.end()&&alias20->canonical_id==10);

  // A fixed gauge plus a verified loop distributes accumulated odometry drift.
  PoseGraphConfig graph_config;graph_config.minimum_cost_reduction=1e-10;
  PoseGraph pose_graph(graph_config);
  for(std::uint64_t i=0;i<5;++i)
    CHECK(pose_graph.addNode({i+1,{{},{static_cast<double>(i)*1.1,0,0}},i==0}));
  for(std::uint64_t i=0;i<4;++i)
    CHECK(pose_graph.addConstraint({i+1,i+2,{{},{-1.1,0,0}},0.08,2.0,
                                    PoseGraphConstraintType::kOdometry}));
  LoopClosureConstraint graph_loop;graph_loop.reference_keyframe_id=1;
  graph_loop.current_keyframe_id=5;graph_loop.current_from_reference={{},{-4,0,0}};
  graph_loop.inlier_count=8;graph_loop.inlier_ratio=0.8;graph_loop.rmse_m=0.01;graph_loop.valid=true;
  graph_loop.rejection_reason=LoopClosureRejectionReason::kNone;
  CHECK(pose_graph.addVerifiedLoop(graph_loop));
  const auto graph_before=pose_graph.findNode(5);CHECK(graph_before.has_value());
  const auto graph_result=pose_graph.optimize();
  CHECK(graph_result.accepted&&graph_result.final_cost<graph_result.initial_cost);
  const auto graph_anchor=pose_graph.findNode(1),graph_end=pose_graph.findNode(5);
  CHECK(graph_anchor.has_value()&&graph_end.has_value());
  CHECK(std::abs(graph_anchor->world_from_node.translation.x)<1e-12);
  CHECK(std::abs(graph_end->world_from_node.translation.x-4.0)<0.02);
  CHECK(pose_graph.nodes().size()==5&&pose_graph.constraintCount()==5);
  CHECK(!pose_graph.addVerifiedLoop(LoopClosureConstraint{}));
  pose_graph.reset();CHECK(pose_graph.nodes().empty()&&pose_graph.constraintCount()==0);

  // Joint refinement lowers robust reprojection error while retaining metric pose priors.
  BundleAdjustmentConfig bundle_config;bundle_config.pose_translation_prior_sigma_m=0.2;
  BundleAdjustment bundle(bundle_config);
  const CameraIntrinsics bundle_intrinsics{640,480,500,500,320,240};
  for(std::uint64_t i=0;i<3;++i){Pose initial;initial.translation={0.5*i,i==0?0.0:0.03,0};
    CHECK(bundle.addKeyframe({i+1,initial,bundle_intrinsics,{},i==0}));}
  std::vector<Vec3> true_bundle_points;
  for(std::uint64_t i=0;i<8;++i){const Vec3 point{-0.7+0.2*i,-0.35+0.12*(i%4),3.5+0.15*(i%3)};
    true_bundle_points.push_back(point);CHECK(bundle.addLandmark({i+1,point+Vec3{0.08,-0.05,0.10}}));
    for(std::uint64_t camera_id=1;camera_id<=3;++camera_id){const Vec3 camera_point=point-Vec3{0.5*(camera_id-1),0,0};
      const auto pixel=PinholeRadTanCamera(bundle_intrinsics).project(camera_point);CHECK(pixel.has_value());
      CHECK(bundle.addObservation({camera_id,i+1,*pixel,1.0}));}}
  const auto bundle_before=bundle.findLandmark(1);CHECK(bundle_before.has_value());
  const auto bundle_result=bundle.optimize();
  CHECK(bundle_result.accepted&&bundle_result.final_cost<bundle_result.initial_cost&&
        bundle_result.final_rmse_px<bundle_result.initial_rmse_px);
  const auto bundle_anchor=bundle.findKeyframe(1);
  const auto bundle_after=bundle.findLandmark(1);
  CHECK(bundle_anchor.has_value()&&bundle_after.has_value());
  CHECK(bundle_anchor->world_from_camera.translation.norm()<1e-12);
  CHECK((bundle_after->position_world_m-true_bundle_points[0]).norm()<
        (bundle_before->position_world_m-true_bundle_points[0]).norm());
  bundle.reset();CHECK(!bundle.findKeyframe(1).has_value()&&!bundle.findLandmark(1).has_value());

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
  CHECK(fused.predicted_displacement_m>=fused.observed_scale_m);
  CHECK(fused.translation_direction_cosine>0.9);
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
