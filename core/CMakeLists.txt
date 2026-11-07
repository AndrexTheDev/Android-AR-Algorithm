add_library(arkernel STATIC
    src/camera_model.cpp
    src/clock_aligner.cpp
    src/core_engine.cpp
    src/feature_detector.cpp
    src/geometry_model_selector.cpp
    src/homography_ransac.cpp
    src/epipolar_ransac.cpp
    src/essential_pose.cpp
    src/imu_preintegrator.cpp
    src/optical_flow.cpp
    src/motion_prediction.cpp
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
endif()
