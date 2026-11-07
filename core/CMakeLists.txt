add_library(arkernel STATIC
    src/c_api.cpp
    src/camera_model.cpp
    src/clock_aligner.cpp
    src/core_engine.cpp
    src/feature_detector.cpp
    src/fixed_lag_smoother.cpp
    src/geometry_model_selector.cpp
    src/homography_ransac.cpp
    src/image_descriptor.cpp
    src/epipolar_ransac.cpp
    src/essential_pose.cpp
    src/imu_preintegrator.cpp
    src/optical_flow.cpp
    src/motion_prediction.cpp
    src/relocalization.cpp
    src/semantic_map.cpp
    src/sparse_map.cpp
    src/spatial_snapshot.cpp
    src/tracking_quality.cpp
    src/visual_frontend.cpp
    src/vio_estimator.cpp
)
target_compile_features(arkernel PUBLIC cxx_std_17)
target_include_directories(arkernel PUBLIC include)
target_compile_options(arkernel PRIVATE -Wall -Wextra -Wpedantic)

if(ARKERNEL_BUILD_TESTS)
  enable_testing()
  add_executable(arkernel_tests tests/core_tests.cpp)
  target_link_libraries(arkernel_tests PRIVATE arkernel)
  add_test(NAME arkernel_tests COMMAND arkernel_tests)

  add_library(arkernel_replay_support STATIC src/replay.cpp src/trajectory_metrics.cpp)
  target_compile_features(arkernel_replay_support PUBLIC cxx_std_17)
  target_include_directories(arkernel_replay_support PUBLIC include)
  target_link_libraries(arkernel_replay_support PUBLIC arkernel)
  add_executable(arkernel_replay ../tools/replay/main.cpp)
  target_link_libraries(arkernel_replay PRIVATE arkernel_replay_support)
  add_executable(arkernel_trajectory_eval ../tools/evaluation/main.cpp)
  target_link_libraries(arkernel_trajectory_eval PRIVATE arkernel_replay_support)
endif()
