# Broad Android device compatibility without testing every model

Research snapshot: 2026-11-07.

## Executive conclusion

No Android library can honestly guarantee identical metric AR quality on every phone without observing the camera, IMU and compute path. Android API compatibility is not optical calibration, and a device name is not a stable hardware contract. A firmware update can change camera HAL, crop, timestamps, stabilization or accelerator drivers without changing the marketing model.

Broad coverage is nevertheless practical if the guarantee is split into levels:

1. **Safety guarantee:** malformed metadata, unsupported capabilities, driver/runtime failures and resource pressure do not crash, corrupt a map or silently invent metric accuracy.
2. **Functional guarantee:** a compatible camera/IMU/CPU combination is discovered by behavior, not an allowlist; unsupported accelerators fall back automatically.
3. **Quality envelope:** tracking and reconstruction features are enabled only after static and active health checks pass.
4. **Metric claim:** centimetre-level status is emitted only for a calibrated capability signature and measured target evidence. Final reconstruction additionally requires the bounded held-out dimensional evaluator to pass on unique independent scale-bar, surveyed-distance or sensor-ground-truth point pairs across the assessed extent. Other devices remain useful but explicitly `approximate` or `unverified`.

The recommended architecture is therefore **probe → fingerprint → self-test → select tier → continuously monitor → degrade safely**. A small physical matrix validates representative behavioral classes; replay, synthetic generation, combinatorial testing and fault injection cover the much larger state space.

## Current implementation gap audit

The existing implementation already has strong bounded buffers, exact configured YUV size checks, capture-result/image association by exact sensor timestamp, explicit calibration input, native clock-alignment primitives, deterministic replay, mapping quality gates, CPU fallbacks and thermal inference control. The remaining broad-device risks are concrete:

- `Camera2FrameSource` now binds a signed physical-camera ID through an API-28 `OutputConfiguration`, rejects unavailable/API-inexpressible routes before opening, and consumes that physical sensor's capture metadata; physical Camera2 routing still requires representative HAL/device validation.
- profile-bound focus distance, focal length, crop, distortion correction and OIS/EIS are requested and continuously checked; optional profile-bound rolling-shutter skew is also checked against an explicit nanosecond tolerance;
- image/capture timestamps are matched exactly, but the declared/observed camera timebase is not checked against IMU/boot time before VIO starts;
- the native `ClockAligner` estimates affine offset/drift but is not yet part of Android admission and continuous camera/IMU health monitoring;
- `AndroidImuSource` combines the most recent accelerometer and gyro values at the timestamp of whichever event arrives. This bounded path needs interpolation/resampling to a declared common timestamp rather than silently reusing an asynchronously aged component;
- `StandaloneSupportReport` is currently supported/not-supported plus warnings, not a versioned tier with stable machine-readable reason codes;
- `InferenceDeviceProfile` now rejects model-name patterns and binds measurements to an exact capability-signature SHA-256, backend ID/version and a canonical typed driver identity over observed API/provider/version/feature-level plus a hashed accelerator name; populating those profiles with representative sustained device-farm measurements remains open.

These are P0/P1 engineering targets, not reasons to replace the standalone architecture.

## Why model-by-model support lists do not scale

A marketing model can contain different:

- camera modules, lens suppliers or sensor revisions by region;
- SoCs and image signal processors;
- Camera HAL and vendor driver versions after an OTA update;
- logical/physical camera routing decisions;
- RAM/storage tiers and thermal envelopes;
- GPU/NPU driver versions;
- Android framework and vendor partitions.

Conversely, many nominally different phones share the same SoC, camera HAL behavior, timestamp failure, YUV layout or accelerator stack. The useful unit is a **behavioral capability signature**, not `Build.MODEL` alone.

A model denylist remains necessary for failures that cannot be probed safely, such as a repeatable native driver crash during session creation. It must be the last layer, narrowly versioned by build/driver range, signed, reversible and backed by evidence.

## Compatibility axes

A capability signature should contain normalized, privacy-safe fields. It must not contain a serial number, advertising ID, image or raw sensor trace.

### Platform

- Android API level, ABI set and page-size/runtime constraints;
- build fingerprint hash or normalized vendor/product/build tuple;
- Camera2 hardware level and declared capabilities;
- available memory class, low-RAM status and storage budget;
- thermal API availability;
- GPU/API/driver and optional ML-runtime/delegate versions.

### Exact camera configuration

- logical camera ID and selected physical camera ID where exposed;
- lens facing, sensor orientation, active/pre-correction array and pixel array;
- exact `YUV_420_888` width/height, minimum frame duration and stall duration;
- observed plane count, row strides, pixel strides and buffer extents;
- focal lengths, focus-distance behavior, intrinsic/distortion metadata and calibration confidence;
- timestamp source, observed clock offset/drift and capture-result/image timestamp agreement;
- rolling-shutter skew and exposure duration;
- OIS/EIS, distortion-correction, edge/noise-reduction, crop/zoom and stream-use-case modes;
- AE/AF behavior and whether focal length/intrinsics change materially with focus.

### IMU

- accelerometer and uncalibrated/calibrated gyroscope availability;
- reporting mode, wake-up status, resolution and declared range;
- observed median/p05 rate, jitter, batching latency and monotonicity;
- timestamp offset/drift relative to `elapsedRealtimeNanos`;
- saturation, duplicate and out-of-order event rates;
- Android high-rate permission availability where rates above the platform limit are needed.

### Workload

- camera frame rate and resolution actually sustained with IMU active;
- visual feature count, spatial coverage, blur and clipping rates;
- VIO innovation/residual, gravity consistency and tracking-loss rate;
- CPU baseline latency, memory and thermal trend;
- optional GPU/NPU delegate conformance and latency.

The serialized signature needs a schema version. A firmware, camera mode, physical camera, crop, stabilization or driver change must produce a different signature or invalidate the dependent calibration.

## Multi-stage runtime admission

### Stage 0: static contract probe

Before opening a session:

- require back camera, accelerometer and gyroscope;
- enumerate only advertised YUV outputs and supported capture-session combinations;
- select a conservative non-high-resolution stream first;
- inspect hardware level, timestamp source, intrinsics, distortion, rolling-shutter metadata, focal lengths and stabilization modes;
- calculate all buffer and memory budgets with checked arithmetic;
- require a portable CPU backend and supported ABI;
- never infer support from ARCore package presence or device model alone.

A missing optional field lowers confidence; it must not be replaced with a fabricated value.

### Stage 1: active camera/IMU smoke test

Run a short bounded test before mapping writes:

- create and tear down the exact production session;
- verify image and capture-result timestamps are monotonic and match under a tolerance;
- compare camera timestamps with both uptime and realtime domains instead of trusting only `SENSOR_INFO_TIMESTAMP_SOURCE`;
- measure frame interval, jitter, drops, duplicate timestamps and startup latency;
- validate every plane extent/stride against the delivered buffer;
- verify exposure, crop, focal length and stabilization state remain compatible with calibration;
- measure actual IMU rates, jitter, ordering and camera-overlap coverage;
- exercise pause/resume and camera loss once without persisting a map.

This catches behavior classes, including new devices, without knowing their names.

### Stage 2: guided observability/self-calibration check

A short optional user-guided motion sequence can establish much more than metadata. The implemented bounded `GuidedRotationObservabilityProbe` receives the visual frame-to-frame rotation and the camera-axis IMU prediction supplied to that exact frame. It requires enough signed, moderate motion and checks direction correlation and magnitude agreement; it deliberately rejects rather than estimates when motion is insufficient. `GuidedTimeOffsetObservabilityProbe` additionally retains bounded raw gyro and visual angular-rate observations, transforms Android body axes through the supplied extrinsics, and searches a fixed ±100 ms offset grid. It accepts only changing multi-axis motion with a strong, unique, interior correlation peak. The reported uncertainty and variance come conservatively from the near-peak grid width; they are not a full calibration covariance. `GuidedTimeOffsetDriftProbe` can combine up to 32 independently accepted windows over at least 60 seconds. It uses robust pairwise offset slopes plus a median intercept, combines slope dispersion with endpoint timing uncertainty, and rejects excessive residual, uncertainty or absolute ppm drift. Rejected windows are counted but cannot contaminate the fit. None of these probes rewrites live timestamps or self-installs calibration.

- pure rotations at moderate angular velocity correlate gyro motion with visual rotation;
- estimate camera/IMU time offset and drift from angular-rate alignment;
- verify camera-to-body rotation against the signed profile;
- detect swapped axes, sign errors, severe rolling shutter and timestamp lies;
- translational/parallax motion checks metric scale/gravity consistency and feature geometry;
- reject calibration updates when motion does not make the parameters observable.

The implemented `GuidedMetricConsistencyProbe` now keeps these claims separate. A bounded stationary window checks accelerometer gravity magnitude/noise and gyroscope stillness. Additive native/JNI visual-fusion diagnostics expose predicted camera displacement, signed translation-direction cosine, and whether inertial scale passed the native fusion gate; the session feeds these into a bounded runtime-consistency report. Absolute dimensional status remains unavailable unless the host supplies at least three physical known-distance observations with reference uncertainty. Runtime agreement alone therefore cannot self-award centimetre validation.

`GuidedMotionAdmissionProbe` is the opt-in terminal composition for hosts that require the complete short guided sequence before mapping. It owns the active stream probe and the bounded rotation, time-offset and metric-consistency probes, receives the exact observations already produced by `ArKernelSession`, and grants runtime metric admission only when every component passes. Until the host explicitly finishes the sequence, session mapping remains pending; a terminal failure keeps mapping rejected. The combined report exposes dimensional validation separately, so successful runtime observability without physical known-distance targets can never be presented as centimetre evidence. Individual probes remain available as diagnostics, but a session cannot mix them with the combined owner. `GuidedMotionTestController` adds a bounded UI-neutral host sequence: hold still, rotate around multiple axes, translate in multiple directions, and optionally measure known distances. It emits stable guidance states, has a hard aggregate deadline, and invokes terminal completion at most once. The schedule is only user guidance; elapsed time never substitutes for the probe thresholds, so an under-observed sequence still fails closed. `ArKernelGuidedMotionPrompts` now supplies bounded German/English host copy for every guidance state, stable terminal blocking reason, cancellation, action labels and text-only accessibility descriptions; accepted result text still says `Quality: unverified`. `ArKernelGuidedMotionHostSession` composes that copy into a strict host-owned UI state machine with explicit START/FINISH/CANCEL/RETRY actions, at-most-once terminal completion and a fresh-probe requirement after retry. Neither renders Android views, terminates the probe, closes a controller, actuates hardware or asserts a quality grade. Applications remain responsible for full app UX integration, real accessibility semantics, lifecycle handling and any physical target workflow.

Online refinement must start from bounded priors, report covariance and never turn an unobservable estimate into a permanent trusted profile. Full intrinsic/extrinsic calibration remains a guided target-based or laboratory operation. [Kalibr](https://github.com/ethz-asl/kalibr) is a useful BSD-licensed external reference/oracle, not a proposed runtime dependency.

### Stage 3: compute/backend self-test

Every optional ML/GPU path should carry small golden inputs and expected output hashes/tolerances:

1. instantiate the delegate/backend in an isolated adapter;
2. run shape/type/operator conformance;
3. compare finite output statistics and selected values with the CPU oracle;
4. apply a strict startup timeout and memory budget;
5. cache success only for the exact model hash plus runtime/driver capability signature;
6. fall back to CPU after mismatch, timeout or recoverable failure.

`AdmissionEvidenceStore` now provides the corresponding app-private atomic evidence cache for device admission. Entries bind the capability signature, calibration hash, software/policy revision and expiry to the active timing result and optional guided-rotation result. CRC catches accidental corruption. A mismatch or expiry is a cache miss, and cached evidence never disables continuous session monitoring or constitutes signed dimensional-validation evidence.

An accelerator success must never be required to start tracking. Native driver crashes cannot always be caught in-process; risky optional adapters should be considered for a separate Android service/process so the host can recover and disable them.

### Stage 4: continuous health monitoring

Admission is not permanent. Monitor:

- frame/IMU delay and timestamp residual;
- VIO residuals, feature count, gravity/scale consistency and covariance;
- exposure/focal/crop/stabilization changes;
- inference p95, failures, memory pressure and thermal state;
- reconstruction uncertainty and dimensional evidence.

Use hysteresis to avoid oscillation. Pause mapping before changing camera mode or calibration. A quality downgrade must invalidate claims derived from the stronger tier, not merely change a UI badge. The implemented `CameraCalibrationRuntimeMonitor` now compares every capture result with the signed stream binding: crop, video/OIS modes, distortion correction, and optionally focal length, focus distance and rolling-shutter skew with explicit tolerances. For a logical multi-camera on API 28+, `Camera2FrameSource` pins the requested advertised physical camera on the output configuration and reads its physical capture result; unsupported API levels, unadvertised IDs, or a missing physical result fail closed rather than falling back to automatic lens selection. The source terminally stops frames on missing or changed required metadata; `ArKernelSession` immediately exposes `PAUSED/CALIBRATION_CHANGED`. It never hot-swaps calibration inside an active map.

## Operational tiers

| Tier | Admission | Enabled behavior | Allowed claim |
|---|---|---|---|
| A — validated metric | Signed exact capability/calibration profile, active checks pass, measured target evidence for this envelope | Full VIO, metric fusion, final reconstruction and validated exports | `centimeter-validated` only within measured range/scene bounds |
| B — runtime-verified metric | Good metadata/profile plus successful timebase/extrinsic/scale health checks, but no release-grade dimensional evidence | Full scanning with conservative uncertainty and quality gates | `approximate`, never universal centimetre claim |
| C — degraded visual/inertial | Camera and sensors function, but calibration/timebase confidence is insufficient | Local tracking/capture assistance; avoid trusted metric fusion or require guided calibration | `unverified` |
| D — capture/export fallback | VIO admission fails but safe Camera2 capture works | Timestamped keyframe capture for later/off-device processing | no live metric AR claim |
| Unsupported | Session, buffers or mandatory sensors violate contract | explicit diagnosis; no mapping writes | none |

This tiering is how functionality can remain broad without lowering correctness standards.

## Learning from Android conformance and CameraX

### CTS and Camera ITS

Android compatibility testing provides valuable baseline evidence but is not a proof for this product. Camera ITS contains tests for unified timestamps, sensor events, jitter, IMU drift, crop regions, lens movement, format combinations, exposure and capture metadata. Reuse the **test ideas and acceptance measurements** in our own harness, pinned to reviewed Apache-2.0 AOSP sources where code is reused.

Limitations:

- certification covers Android contracts, not our trajectory or reconstruction error;
- vendor waivers and firmware regressions can exist;
- many tests use controlled charts and do not reproduce long VIO scans;
- non-GMS/AOSP-derived devices may not have equivalent certification evidence.

### CameraX quirks

The Apache-2.0 AndroidX source contains a large maintained Camera2 quirk corpus: excluded/extra sizes, crop errors, YUV one-pixel shifts, session closure/stalls, surface combinations and inconsistent camera timebases. This proves that static API declarations are not sufficient.

Recommended use:

- periodically diff and classify relevant CameraX quirks;
- add regression fixtures for behavior affecting grayscale geometry, timestamps and lifecycle;
- optionally offer a CameraX/Camera2Interop capture adapter for applications prioritizing reach;
- retain direct Camera2 as the precision path because exact capture results, physical camera selection and calibrated control modes remain necessary;
- do not copy CameraX's entire model list into the kernel. Prefer active detection; use a narrow reviewed rule only where probing itself can crash or corrupt.

A concrete AndroidX example, `CameraUseInconsistentTimebaseQuirk`, documents devices/SoCs whose actual camera timebase differs from `SENSOR_INFO_TIMESTAMP_SOURCE`. Therefore ARKernel must estimate/verify the relationship at runtime.

## Reducing physical test count systematically

### 1. Behavioral equivalence partitions

Partition devices along axes that affect behavior, for example:

- API 26–28 / 29–30 / 31–34 / 35+;
- LEGACY / LIMITED / FULL / LEVEL_3 / EXTERNAL camera level;
- timestamp source REALTIME / UNKNOWN / observed-inconsistent;
- Y-only contiguous/padded and chroma planar/interleaved layouts;
- rolling-shutter metadata present/absent and low/high skew;
- fixed/variable focus, OIS/EIS combinations;
- calibrated/uncalibrated gyro, low/medium/high sustained sensor rates;
- ARM generations and low/normal/high memory;
- CPU-only / NNAPI / GPU / vendor-NPU paths;
- major SoC/ISP/GPU/driver families.

A device can move partition after an OTA. Results attach to the capability signature and software/model hash.

### 2. Pairwise and higher-strength covering arrays

Generate pairwise combinations for broad CI and targeted 3-/4-way combinations for high-risk interactions such as:

`API × camera level × timestamp behavior × stabilization × YUV layout × lifecycle`.

Pairwise testing does not prove arbitrary driver correctness, but it prevents an impossible Cartesian product while covering most configuration-interaction bugs. Open tools such as BSD-licensed GenPairs can generate cases; generated vectors can also be produced internally to avoid a runtime dependency.

### 3. Set-cover physical selection

Select the smallest owned/cloud device set that covers weighted capability partitions, not the latest popular model list. Weight by:

- active-install/target-market share;
- novelty of SoC/ISP/HAL/GPU family;
- known quirk density;
- failure severity;
- camera/IMU behavior not represented elsewhere.

Recompute the set each release. A practical lab may contain tens of carefully chosen devices while representing hundreds of models sharing behavior. Keep a few deliberate outliers: low-end/low-RAM, old API, unusual vendor, high-resolution/rolling-shutter, and non-GMS AOSP-derived device.

### 4. Cloud device farms

Use physical cloud devices for:

- install, permission and lifecycle coverage;
- camera open/close and supported-stream smoke tests;
- ABI/native loading;
- metadata/capability inventory;
- CPU/delegate golden inference tests;
- short stationary sensor sanity tests.

Do **not** treat an unattended cloud phone as proof of VIO, dimensional accuracy, camera/IMU extrinsics or thermal scan endurance. It lacks controlled motion, scene ground truth and often predictable camera access.

### 5. Small robotic calibration lab

Use Mobly (Apache-2.0) to orchestrate a compact representative matrix with:

- programmable rotation/translation rig or sensor-fusion box;
- AprilTag/checkerboard/scale targets with surveyed dimensions;
- controlled lighting, texture and distance;
- thermal logging and repeated 30-minute scans;
- automatic capture of build/capability signature and diagnostic bundle.

The rig supplies ground truth that cloud quantity cannot. DeviceFarmer STF can manage inventory/reservations; it does not replace Mobly measurement logic. `DeviceThermalRunPlanner` and `DeviceThermalRunLedger` now provide only a bounded deterministic 30-minute slot schedule and fail-closed accounting for host-supplied thermal-state/frame-rate samples, with explicit `unverified`. They do not measure temperature, execute scans, connect to a rig or prove thermal endurance; those remain physical lab work.

### 6. Virtual and synthetic testing

Android Emulator and Cuttlefish are useful for API levels, permissions, lifecycle, process death, storage, malformed metadata adapters and ABI packaging. Add deterministic virtual camera/IMU sources that inject:

- timestamp offset/drift, jitter, duplicates, reordering and gaps;
- rolling shutter and exposure delay;
- YUV padding/crop/orientation variants;
- IMU bias, noise, saturation and axis/sign swaps;
- camera disconnect/reopen and process recreation;
- memory pressure, slow storage, inference timeout and thermal transitions.

They cannot validate a real lens, sensor, ISP or temperature curve.

### 7. Record/replay and metamorphic tests

Maintain a provenance-controlled corpus of raw camera/IMU streams from each behavioral partition. Run every kernel change against it with trajectory and reconstruction regression budgets.

Metamorphic properties catch errors even without perfect ground truth:

- equivalent row padding must not change grayscale/features;
- legal image rotation plus transformed calibration must transform, not corrupt, trajectory;
- uniform timestamp shift must not change relative motion after alignment;
- frame dropping should increase uncertainty rather than create scale;
- adding bounded sensor noise should degrade confidence monotonically;
- CPU and accepted accelerator outputs must agree within declared tolerances;
- replay must remain deterministic for identical inputs/configuration.

### 8. Property testing, fuzzing and fault injection

Fuzz metadata, dimensions, strides, timestamps, quantization and lifecycle ordering at every native/JNI/Kotlin boundary. Use sanitizers on native replay. Add state-machine tests for pause/resume/close, camera loss, callback races and partial runtime outputs. Most combinations are cheaper and more reproducible here than on hardware.

### 9. Privacy-preserving field evidence

With explicit opt-in, collect a compact diagnostic record—not images or trajectories by default:

- hashed capability signature and software/model versions;
- admission tier/reason;
- timestamp/frame/IMU aggregate statistics;
- tracking loss, residual and uncertainty histograms;
- delegate fallback reason, latency/thermal aggregates;
- crash-free session and explicit user quality feedback.

Use cohort minimums, retention limits and deletion controls. Field telemetry discovers unknown clusters; it does not by itself validate metric accuracy. Promote a cluster only after controlled evidence.

## Quirk and rollout control plane

Use a signed, versioned compatibility policy with these rules:

- capability and active behavior rules are primary;
- exact model/build rules are narrowly scoped fallbacks;
- policies can only disable or lower a tier by default, not grant centimetre validation;
- cached last-known-good policy and built-in conservative defaults work offline;
- signatures, expiry, rollback protection and audit history are mandatory;
- staged rollout/canary cohorts detect regressions;
- a kill switch can disable one delegate, camera mode or optimization without disabling CPU capture/tracking;
- calibration profiles are separately signed and bound to exact capability signatures. The implemented `CalibrationProfileVerifier` accepts only bounded canonical profiles with a trusted P-256/SHA-256 signature, validity interval, monotonic revision floor, exact capability hash, camera/physical-camera identity, stream size, crop, stabilization and distortion-correction modes. `CalibrationTrustPolicy` adds bounded key validity windows, explicit revocation, policy revision rollback protection and per-profile revision floors while retaining conservative offline operation. Intrinsics, distortion, camera/IMU transform, offset uncertainty and measurement evidence are inside the signed payload. Issuer class and metric claim remain distinct: user-measured or manufacturer identity cannot encode an independent-lab centimetre claim.

Remote configuration must never be a mandatory network dependency for basic standalone operation.

## Camera and calibration policy

### Stream policy

Start from a conservative YUV resolution/FPS known to fit memory and latency. Query the exact configuration; never select by aspect ratio alone. Prefer one physical back camera and avoid automatic logical-camera switching. Disable video stabilization and OIS where possible for the calibrated precision mode, or model their effect explicitly. Lock crop/zoom and distortion-correction mode.

Capture result and image must be associated by sensor timestamp. Record exposure, sensitivity, focal length, focus distance, crop, rolling-shutter skew and stabilization state with each retained keyframe when available.

### Calibration sources, in descending trust

1. independent target/rig validation for the exact capability signature;
2. signed manufacturer/lab profile with uncertainty;
3. Camera2 factory intrinsics/distortion checked by an active reprojection test;
4. guided user target calibration with observability and residual gates;
5. bounded online refinement initialized by a valid prior;
6. approximate FOV-derived intrinsics only for non-metric/capture fallback, never for centimetre claims.

Camera-to-IMU time offset and extrinsics require the same provenance/uncertainty discipline. Do not share a profile across logical cameras, physical IDs, resolutions/crops, stabilization modes or firmware when evidence says those alter geometry.

## Redundant estimators and differential oracles

Device robustness improves when independent signals can veto each other:

- compare integrated gyro rotation with calibrated visual rotation over observable windows;
- compare Camera2 timestamps with both realtime/uptime hypotheses and image/capture-result pairing;
- compare visual-inertial scale/gravity with known target dimensions when available;
- compare model depth with calibrated multi-view depth and temporal reprojection, preserving unknown where they disagree;
- compare CPU and accelerator tensor outputs on golden and sampled live inputs;
- when ARCore is installed and usable, optionally run it as a same-device differential baseline during diagnostics. Agreement is useful but ARCore is not ground truth and must not become required;
- use forward/backward optical flow, loop residuals, reprojection residuals and map consistency as independent health gates.

No single oracle awards metric validation. Disagreement lowers confidence or tier; it does not get averaged into a plausible-looking answer.

## Packaging and update reach

- Keep the core AAR runtime-free and CPU-capable.
- Package LiteRT/ONNX/ncnn/MNN/graphics accelerators as mutually optional artifacts or dynamic features; applications include only measured choices.
- Use Android App Bundle ABI splits where the product distribution permits them, while retaining a documented universal/offline package strategy.
- Bind model, decoder contract and runtime adapter versions in one signed deployment manifest.
- Roll out new models, delegates and camera policies independently with canaries and immediate rollback.
- Never download executable/model content silently; verify hash/signature/license metadata and retain a usable built-in fallback.

## Statistical release confidence

Testing representative partitions estimates risk; it cannot prove absence of every vendor defect. Release reports should therefore state:

- partition and weighted market coverage;
- number of exact capability signatures and firmware versions observed;
- session hours and independent scan repetitions;
- upper confidence bound for crash/admission/tracking-loss rates;
- untested/high-risk partitions;
- separate functional, trajectory, dimensional and thermal evidence.

Use stratified sampling and oversample rare/high-severity partitions. A large undifferentiated device count is less informative than a smaller set with explicit behavior coverage and ground truth.

## Compute portability

- Keep the portable bounded C++/CPU path as the mandatory baseline.
- Build Android ABIs intentionally; arm64-v8a is the primary production baseline, while any additional ABI must pass the same tests.
- Dispatch SIMD by runtime CPU feature, with scalar/reference comparison tests.
- Treat Vulkan/OpenGL/NNAPI/GPU/NPU as optional accelerators.
- Use Vulkan Profiles/feature queries and small shader self-tests instead of GPU model lists where possible.
- ANGLE can normalize some OpenGL behavior in an optional viewer/rendering module, but adds size and does not solve camera/IMU quality.
- Bound allocations before session start, expose low-memory tiers and avoid relying on allocation failure recovery.
- Preserve single-flight inference, adaptive cadence and thermal shutdown already present in ARKernel.

## What can and cannot be guaranteed

| Statement | Achievable without testing every model? | Mechanism |
|---|---|---|
| No unchecked tensor/buffer shape enters the kernel | Yes | checked contracts, fuzzing, bounded arithmetic |
| Unsupported accelerator falls back to CPU | Largely yes | golden self-test, timeout/isolation, CPU baseline |
| Camera/session failures do not corrupt a map | Largely yes | admission gate, transactional map writes, lifecycle fault tests |
| Works on every API-compliant camera | No absolute guarantee | API/HAL bugs exist; active probes and quirks reduce risk |
| Stable local tracking on most capable phones | Evidence-based broad target | behavior partitions, health gates, representative rigs/replay |
| Centimetre accuracy on every phone | No | requires exact calibration, observable motion and measured ground truth |
| Useful fallback on unvalidated phones | Yes as a product goal | tiers C/D, capture/offline reconstruction and explicit uncertainty |

## Recommended implementation sequence

### P0 — make hidden assumptions observable

1. [Implemented foundation] Use the versioned `DeviceCapabilitySignature` and `DeviceCompatibilityReport` with stable reason codes; the static probe intentionally caps its provisional result at degraded tracking until active tests exist.
2. Extend Camera2 probing with timestamp source, rolling-shutter skew, active/pre-correction arrays, distortion, focal/focus, stabilization, crop and physical camera identity.
3. [Implemented foundation] Feed bounded Camera2 image/result and raw IMU timing hooks into `ActiveDeviceCompatibilityProbe`; it checks monotonicity, rates, gaps, timestamp pairing, overlap and realtime-versus-uptime behavior.
4. [Implemented foundation] An attached unfinished or failed active probe forces the session mapping gate to `DEVICE_COMPATIBILITY_PENDING` or `DEVICE_COMPATIBILITY_REJECTED`; only a completed calibrated/extrinsics-trusted report can promote to runtime-verified metric mapping.
5. Export a privacy-reviewed diagnostic bundle for user-run support tests.

### P1 — safe adaptation

1. Add runtime camera/IMU offset monitoring and bounded estimator with observability checks. Independent Android accelerometer/gyro callbacks are synchronized at a common observed timestamp by bounded interpolation without extrapolation. `CameraTimestampNormalizer` now classifies realtime, uptime or practically equivalent session clocks from bounded callback observations and maps uptime with one robust fixed offset; locked sessions now monitor uptime offset, realtime callback residual and equivalent-clock divergence; a threshold violation terminally invalidates normalization and immediately pauses mapping. A subsampled bounded native `ClockAligner` now robustly fits callback core time against raw camera time over a minimum ten-second span, exposes drift in ppm and residual RMSE, and triggers the same terminal mapping stop when policy bounds fail. Callback latency still makes this a health detector, not a calibration oracle.
2. [Implemented runtime wiring, calibrated alternatives and terminal restart guard] `RuntimeResourcePolicy` selects performance, balanced, conservative, capture-only or unsupported behavior from the exact capability signature, active camera timing, bounded processing p95, memory pressure and thermal state—not model names. Each tier fixes pixel/FPS/queue/reconstruction/inference budgets; a stream above the tier budget is never resized heuristically. `CalibratedStreamProfileSelector` instead verifies and ranks up to 64 separately signed profiles, each paired with observations for that exact stream. A stream-size-only capture fallback remembers the lower mapping tier that a fitting verified profile can re-admit; thermal, memory and compatibility capture decisions cannot be promoted this way. Initial mapping admission applies the camera output ceiling, IMU queue and pending-reconstruction limits. Missing IMU or a true capture-only decision selects a distinct `CameraOnlyArRuntime` that never constructs VIO, the visual tracker or SensorManager and emits pose-free calibrated I420/exposure/ISO/quality evidence for offline processing. It does not claim live AR. During execution, `RuntimeResourceAdmissionCoordinator` recomputes policy from immutable startup reports/profile identity plus bounded p95, memory and thermal evidence; `AndroidRuntimeResourceMonitor` supplies API memory callbacks and API-29+ thermal status without treating API 26–28's missing signal as endurance proof. Every refresh passes through `RuntimeExecutionPlanController`, which accepts only diagnostic refreshes of the same executable plan, rejects upgrades and turns any budget degradation or profile-hash change into a sticky restart requirement. Mapping moves to `PAUSED/RESOURCE_RESTART_REQUIRED`, capture emission stops, and the invalidated owner cannot restart; clean re-admission and a new owner are mandatory.
3. [Implemented tensor and identity foundations] `TensorRuntimeConformanceSelector` and `MultiOutputTensorRuntimeConformanceSelector` compare fresh adapter-neutral runtimes on at most 16 exact-shape inputs and 32 MiB total input data. Up to eight output heads each carry an exact byte count, encoding and tolerance, with 32 MiB aggregate output. FLOAT32 values must be finite and satisfy explicit combined absolute/relative tolerances; INT8/UINT8 use an explicit integer tolerance. Delegate exceptions, output-count/size mismatches, non-finite values or numerical divergence in any head close the complete delegate and deterministically retain CPU; an invalid CPU oracle closes both and aborts. Above this, `VerifiedInferenceIdentity` requires exact artifact byte-count/SHA-256 verification and the backend identity gate compares the complete manifest and exact task set before admitting a delegate. The bounded semantic selector now canonically orders detections independently of backend emission order; compares exact semantic identity plus bounded boxes, anchors and confidence; and compares visible finite metric-depth/confidence pixels across either array or positioned direct-buffer representation without mutation. `VerifiedInferenceBackendAdmission` supplies independent read-only views of the same verified artifact to separately packaged CPU/delegate factories and exposes explicit CPU-only, delegate-creation-failed, rejected, or accepted outcomes while preserving single ownership. Accepted inference reports can now be atomically cached only against a canonical full-manifest hash, exact capability signature, calibration hash, Golden-suite/tolerance hashes, adapter ID/version, canonical typed driver-identity SHA-256, software/policy revision and expiry; exact cache hits may skip the full repeated suite but still rehash the artifact, reconstruct both backends, require the delegate's self-reported execution identity to match the evidence key, and run one decoded CPU/delegate startup sentinel; sentinel failure retains CPU, while mismatch, expiry or corruption runs full conformance. Device admission reports remain separately bound to exact capability, calibration, software/policy revision and expiry. Real runtime adapters and device-farm corpus results remain open.
4. [Implemented disable-only policy and atomic local installation] `CompatibilitySafetyPolicy` canonically encodes bounded rules over observable API, Camera2 hardware/timestamp, RAM and active-failure classes. P-256 root verification, validity, revision floors and a permanently valid built-in offline fallback fail closed. `CompatibilitySafetyPolicyStore` atomically persists only a strictly newer currently valid signed envelope in app-private no-backup storage; malformed existing storage blocks replacement rather than silently resetting its revision, while loading always falls back offline. Remote actions can only disable dense depth, semantics or mapping, force capture-only, or mark unsupported; they cannot promote admission, alter calibration or increase budgets. Matching runtime changes flow through the terminal restart guard. Authenticated network retrieval and staged distribution remain application/backend work.
5. [Implemented timing faults and Camera2 lifecycle state foundation] `SyntheticCompatibilityFaultSource` drives the production admission probe with bounded deterministic healthy, single-fault and all two-fault scenarios for camera/IMU timestamp regressions, excessive frame gaps, CaptureResult dropout, per-sensor dropout, callback latency, ambiguous camera clocks and disjoint windows. JVM covering tests require each single fault to map to its observable reason and prove all 45 fault pairs remain bounded and cannot promote admission. The production `Camera2FrameSource` now uses a pure generation-aware `Camera2LifecycleStateMachine`: stale open/configure callbacks cannot attach to a restarted owner, disconnect/device-error/configure/start failures report once and terminally tear down resources, and exhaustive five-event callback sequences verify terminal invariants. Recognized Camera2 service/permission/busy/disconnect codes now map to a distinct `SERVICE_DEATH` lifecycle failure, while ordinary device errors keep their existing classification. A pure seed-deterministic `Camera2ServiceDeathFuzzHarness` replays bounded service-death, failure, stale-callback and restart sequences against the generation-aware state machine, and `SyntheticCompatibilityFuzzCatalog` extends the scalar replay with reproducible bounded fault subsets and sample budgets. Both are synthetic replay infrastructure: real Binder/framework service-death injection, coverage-guided or unbounded fuzzing and device-farm corpora remain open.

### P2 — evidence at scale

`CapabilityCoveragePlanner` now provides the deterministic inventory-selection foundation. It classifies at most 256 opaque candidates by observable API, ABI, RAM, camera presence/hardware/timestamp/frame-duration/logical-camera/metadata and IMU classes, generates bounded observed single and all dimension-pair requirements, weights high-risk partitions, and greedily selects at most 64 candidates with stable tie-breaking. Build fingerprint, camera ID and model name are deliberately excluded from coverage dimensions. The plan minimizes redundant cloud/lab runs; it does not prove optical calibration, environmental robustness, endurance or accuracy.

1. Cluster diagnostic signatures into behavioral partitions.
2. Generate pairwise/high-risk covering arrays.
3. [Selection foundation implemented] Feed provider inventories into the set-cover planner, then build Mobly rig automation for the selected matrix.
4. Port relevant Camera ITS measurements: unified timestamps, jitter, sensor events, IMU drift, crop and lens movement.
5. Build calibrated replay corpus and release dashboards for ATE/RPE, scale, depth, thermal and failure reasons.

### P3 — calibration reach

1. [Implemented foundation] Add guided rotation verification, bounded observable time-offset search, and robust multi-window ppm drift estimation with conservative uncertainty; continue with laboratory covariance validation.
2. [Implemented measurement foundation] Keep runtime gravity/translation consistency separate from physical known-distance evidence. `CalibrationTargetCaptureProbe` requires bounded high-quality target observations across multiple view and distance bins before emitting aggregate evidence. `CalibrationTargetPoseAdapter` is a narrow synchronous SPI for optional OpenCV/AprilTag/custom implementations: direct Y memory is never retained, target identity/timestamp/corner count/pixel bounds/pose scalars are validated, coverage and frame sharpness are computed by the core, and malformed adapters fail closed. `CalibrationGuidanceController` deterministically requests target reacquisition, better quality, distance change, view tilt, more captures, or completion. Target-specific UI wording and detector packaging remain application work.
3. [Implemented profile-verification foundation] Support signed calibration profiles with uncertainty, expiry, exact firmware/camera/mode binding, trusted-key validity/revocation, policy/profile anti-rollback floors and offline operation. `CalibrationTrustPolicyStore` now verifies a bounded canonical policy against a separate host root key, atomically installs only a strictly newer revision, refuses replacement when stored state is corrupt, enforces a host revision floor, and returns no policy outside its validity window. Update transport and root-key lifecycle remain application/distribution responsibilities.
4. Add bounded per-session refinement that can lower but never self-award validation status.
5. Validate optional CameraX capture adapter against direct Camera2 precision mode.

## Open-source components and references

- [AndroidX CameraX](https://github.com/androidx/androidx), Apache-2.0: maintained Camera2 quirk knowledge and test patterns. Review/pin relevant source; do not make the precision core depend blindly on all CameraX behavior.
- Android Camera ITS in AOSP CTS, Apache-2.0 source: reference measurements for camera metadata, timing, crop, sensor and optical behavior.
- [Mobly](https://github.com/google/mobly), Apache-2.0: host orchestration for devices and custom motion/light/thermal hardware.
- [Cuttlefish host support](https://github.com/google/android-cuttlefish): scalable virtual Android compatibility/lifecycle tests, not optical validation.
- [Kalibr](https://github.com/ethz-asl/kalibr), BSD-style license with acknowledgement clause: external camera/IMU calibration oracle and lab baseline.
- [Vulkan Profiles](https://github.com/KhronosGroup/Vulkan-Profiles): capability-based GPU feature profiles.
- [ANGLE](https://github.com/google/angle): optional graphics portability layer, not a VIO compatibility solution.
- [DeviceFarmer STF](https://github.com/DeviceFarmer/stf), Apache-2.0 license text in repository: optional owned-device inventory/control plane.
- [GenPairs](https://github.com/TestCreator/GenPairs), BSD-3-Clause: pairwise covering-array reference/tool.

## Decision

Do not promise universal quality from an Android version or model list. Promise a universal **safety and truthful degradation protocol**, then expand validated quality envelopes through behavioral evidence. This gives broader real-world reach than either an ARCore-only allowlist or an unverified “works everywhere” claim, while preserving the project's centimetre-level target where it is actually demonstrated.
