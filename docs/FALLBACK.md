# ARCore-independent Android fallback

## Decision

ARKernel does not link against or require Google Play Services for AR. The guaranteed fallback is the existing standalone stack:

`Camera2 YUV -> native visual frontend -> SensorManager IMU -> VIO -> sparse map/planes/anchors -> optional metric depth/surfels -> optional semantics`

`ArRuntimeSelector` may select an application-provided optional runtime first, but any unsupported, absent, or failed optional adapter falls back to `StandaloneArRuntime`. The optional boundary uses no ARCore classes, so unsupported phones can install and load the same core AAR.

## Research basis

- The open ARCore Android SDK describes motion tracking and environmental understanding, but it is a Google Play Services for AR runtime with additional terms and privacy disclosure requirements. Its repository also documents runtime/ABI compatibility constraints: <https://github.com/google-ar/arcore-android-sdk>.
- Kimera-VIO demonstrates that accurate state estimation is fundamentally a camera-plus-IMU pipeline and supports mono+IMU as well as stereo+IMU: <https://github.com/MIT-SPARK/Kimera-VIO>.
- OpenVINS provides an open visual-inertial navigation design and now contains Android-oriented work, reinforcing Camera2/IMU as a viable independent sensor boundary: <https://github.com/rpng/open_vins>.

The practical conclusion is not to imitate ARCore through undocumented APIs. A portable fallback must own camera/IMU timing, calibration, VIO, mapping, depth and semantics, while treating ARCore as an optional adapter.

## Runtime selection

1. The host may provide `OptionalExternalArRuntimeFactory` from a separately packaged ARCore module.
2. The factory performs the authoritative ARCore support/install/session check because only that module knows the linked SDK version.
3. If support is false, the module is absent, or creation throws, selection continues to standalone.
4. `StandaloneArSupportProbe` requires an accelerometer, gyroscope, back Camera2 YUV stream matching the calibrated dimensions, and a valid explicit calibration/extrinsic profile.
5. Failure is explicit with blocking reasons; selection never silently substitutes uncalibrated axes or arbitrary camera dimensions.

Checking only whether package `com.google.ar.core` exists is intentionally insufficient: package presence is not a trustworthy session-capability result and would reintroduce a Google dependency into the fallback.

## Capability comparison

| Capability | Standalone ARKernel | Optional ARCore adapter |
|---|---:|---:|
| Local 6-DoF tracking | Yes | Adapter-dependent |
| Camera2 + IMU without Play Services for AR | Yes | No |
| Plane detection and bounded raycasts | Yes | Adapter-dependent |
| Local anchors | Yes | Adapter-dependent |
| Encrypted local map persistence/relocalization | Yes | Adapter-dependent |
| LiDAR required | No | No |
| Metric depth | Optional sensor/model input | Adapter-dependent |
| Semantics/room contents | Optional verified model backend | Adapter-dependent |
| Dynamic masking and temporal depth | Yes when configured | Adapter-dependent |
| Light estimation | Not implemented | Often available |
| Augmented-image database | Not implemented | Often available |
| Cloud/Geospatial anchors | Not implemented | Often available |

The fallback is comparable for local tracking, planes, anchors, scanning and on-device understanding, but it must not claim feature parity for cloud/geospatial services or light estimation.

## Calibration is non-negotiable

Camera2 may omit `LENS_INTRINSIC_CALIBRATION`, and Android does not provide a universally reliable camera-to-IMU extrinsic transform. `StandaloneArRuntimeConfig` therefore requires calibrated intrinsics and a body-from-camera rotation, with optional translation. The probe warns when Camera2 lacks published intrinsics and always reports that extrinsics must be device-calibrated. Shipping a broad device fallback requires a signed/versioned calibration profile per supported camera configuration or an explicit calibration flow.

## Indoor and outdoor scope

Indoor local scanning is the primary standalone target. Outdoor local VIO can operate in textured, well-lit scenes, but long-distance/global outdoor positioning still needs GNSS/barometer/georeferencing factors. The selector does not turn local VIO into ARCore Geospatial and does not hide drift behind a capability flag.
