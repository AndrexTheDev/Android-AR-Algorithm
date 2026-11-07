cmake_minimum_required(VERSION 3.22)
project(arkernel_android LANGUAGES CXX)
# Included by an Android library module. JNI bindings will link this target;
# keeping core sources in one place prevents Android/desktop algorithm drift.
set(ARKERNEL_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../core ${CMAKE_CURRENT_BINARY_DIR}/core)
