cmake_minimum_required(VERSION 3.22)
project(ARAlgorithmKernel VERSION 0.1.0 LANGUAGES CXX)
option(ARKERNEL_BUILD_TESTS "Build dependency-free native tests" ON)
add_subdirectory(core)
