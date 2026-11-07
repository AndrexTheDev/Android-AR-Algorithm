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

## Calibrated Camera2 stream

`Camera2FrameSource` opens a back camera (or an explicit camera ID), requests the exact calibrated YUV size, consumes only the Y plane, and closes each `Image` after the synchronous native call. The host must pass intrinsics/distortion for that exact camera ID, output size, crop and stabilization mode; the adapter intentionally refuses to guess calibration. The requested size must be listed by that camera's `StreamConfigurationMap`.

## Coordinated session

`ArKernelSession` queues IMU samples and drains only samples with `timestamp <= Image.timestamp` before each visual frame. It then computes an IMU rotation prediction, runs the visual frontend, and feeds valid general-3D relative poses back into VIO. A calibrated `bodyFromCameraRotation` is mandatory because Android device axes and optical camera axes are generally not aligned; `bodyFromCameraTranslationMeters` carries the metric camera lever arm from the IMU/body origin. Result callbacks run on the camera worker thread.

A frame enters the sparse map at most once and only after `MappingQualityGate` accepts it. Uninitialized or limited VIO, non-tracking frontend state, fewer than 12 accepted tracks, or a new IMU queue overflow pause mapping immediately; three consecutive good frames are required before recovery. `ArKernelSessionResult.mappingGate` exposes `PAUSED`, `RECOVERING` or `ACTIVE` plus the precise reason. `ArKernelSessionResult.spatialMap` reports landmark changes and the bounded dominant plane for compatibility. `ArKernelSession.planes()` exposes all currently extracted surfaces with stable IDs, geometry statistics, observed area and gravity-aware `FLOOR`, `CEILING`, `WALL`, `SLOPED` or `UNKNOWN` semantics. Floor versus ceiling uses the current metric camera side of the plane rather than an arbitrary normal sign. `ArKernelSession.roomLayout()` reports selected floor/ceiling IDs and elevations, a plausibility-gated room height, and sums of observed—not extrapolated—floor, ceiling and wall areas. It does not claim that a partial scan is a closed room boundary. `raycastPlane` tests every active boundary and returns only the nearest valid hit inside observed plane geometry. `createAnchor`, `anchor`, and `removeAnchor` expose stable world-frame anchors. Stopping the Camera2 session resets this transient map and its anchors. `ArKernelSession.saveSpatialMap` exports the native snapshot into `ArKernelMapStore`, which uses Keystore AES-256-GCM, a fresh nonce, authenticated metadata, `AtomicFile`, and no-backup app storage. `restoreSpatialMapInCurrentWorld` is intentionally not automatic: the running session must first be externally relocalized into the saved world frame. Encryption authenticates bytes but cannot establish spatial alignment. The full calibrated camera-to-body transform is used: mapping composes the rotated metric lever arm into `world_from_camera`, and visual scale correction compares predicted camera-center motion before converting the corrected camera position back to the body origin.

## Semantic 3D observations

`projectSemanticDetection` accepts a calibrated detection pixel plus the exact frame-associated `worldFromCamera` returned in `ArKernelSessionResult`; it unprojects a ray and can require an observed floor or wall boundary. No hit means no object. `integrateSemanticObservation` accepts a model-manifest class ID, optional source track ID, confidence, dynamic flag and an already metric world position. The host must derive that position from trusted depth, triangulation or bounded raycasting; a 2D detection alone is insufficient. The session returns `null` while its geometric mapping gate is paused. `semanticObjects()` exposes only confirmed objects, while `pruneSemanticObjects()` advances static/dynamic expiry even when an inference frame has no detections. Labels remain adapter-owned and class `0` means unknown. Semantic objects are transient and are not yet written to `ARKMAP01`.
