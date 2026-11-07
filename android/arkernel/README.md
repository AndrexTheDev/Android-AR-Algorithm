# Android AAR boundary

The Android library now contains:

- a versioned, opaque-handle C ABI (`arkernel/c_api.h`);
- JNI ownership and data marshalling without nanosecond-to-Double conversion;
- `ArKernelVio`, a synchronized Kotlin `AutoCloseable` facade;
- `AndroidImuSource`, a dedicated-thread SensorManager adapter;
- pause/resume/reset behavior that never mixes sensor sessions;
- NDK/CMake wiring for `libarkernel_jni.so`.

Host apps own runtime permissions and lifecycle calls. Typical use:

```kotlin
val vio = ArKernelVio()
val imu = AndroidImuSource(context, vio)

override fun onResume() { super.onResume(); imu.start() }
override fun onPause() { imu.stop(); super.onPause() }
override fun onDestroy() { imu.close(); vio.close(); super.onDestroy() }
```

The next adapter will feed Camera2 Y-plane frames into the native visual frontend. The current module deliberately does not pretend that camera ingestion is complete.

## Calibrated Camera2 stream

`Camera2FrameSource` opens a back camera (or an explicit camera ID), requests the exact calibrated YUV size, consumes only the Y plane, and closes each `Image` after the synchronous native call. The host must pass intrinsics/distortion for that exact camera ID, output size, crop and stabilization mode; the adapter intentionally refuses to guess calibration. The requested size must be listed by that camera's `StreamConfigurationMap`.
