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
