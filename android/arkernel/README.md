# Android adapter (in progress)

This directory is the boundary for the future AAR: Camera2/SensorManager ingestion, JNI and lifecycle handling. It intentionally contains no fake Android API yet. The portable algorithm target is imported through `CMakeLists.txt`; Phase 1 adds the versioned C ABI and Kotlin facade after replay tests define its behavior.
