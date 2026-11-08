# Open-source adoption plan

Research snapshot: 2026-11-07. An open-source license does not by itself make a component suitable for the mandatory offline, ARCore-independent Android fallback. Every adoption must also pass ABI coverage, bounded-memory behavior, deterministic replay, binary-size, transitive-license and same-device accuracy review.

## Decision rules

1. `core/` retains a dependency-free bounded implementation for essential capture, tracking and reconstruction behavior.
2. Large libraries enter behind optional adapters; absence or load failure must preserve the standalone path.
3. Desktop accelerators may improve final quality but may not be required to open or reconstruct a scan.
4. Pin source or CI actions to reviewed commits/releases, record license notices and verify artifacts. Do not track floating branches.
5. GPL/AGPL code is not linked or copied into the distributed AAR unless the whole distribution strategy is explicitly changed after legal review.
6. A library benchmark must compare accuracy, peak memory, latency, thermal load, AAR size and failure behavior against the portable backend before becoming a default.

## Geometry and optimization

| Project | License / platform finding | Decision |
|---|---|---|
| [Ceres Solver](https://github.com/ceres-solver/ceres-solver) | BSD-3-Clause text; official Android NDK instructions, C++17, Eigen and Abseil. Dense/iterative solvers work without SuiteSparse. SuiteSparse has mixed GPL/LGPL/commercial parts. | **Planned optional optimizer backend.** Build arm64-v8a/x86_64 with the same NDK, API and STL; disable SuiteSparse. Keep the current bounded PCG solver as fallback and benchmark both. |
| [GTSAM](https://github.com/borglab/gtsam) | Simplified BSD project with Eigen/MPL and additional bundled dependencies; broad factor-graph scope and larger integration surface. | **Do not add now.** Reconsider for incremental iSAM2 research after Ceres evaluation; excessive overlap for current pose graph. |
| [OpenCV](https://github.com/opencv/opencv) | Apache-2.0 and established Android packaging; ORB, calibration, matching and image primitives, but material AAR/native size and broad API surface. | **Optional vision adapter candidate.** Never make Camera2/VIO fallback depend on it. Compare ORB/relocalization against current BRIEF on recorded mobile data first. |
| [openMVG](https://github.com/openMVG/openMVG) | MPL-2.0; README states Android/iOS/Linux/macOS/Windows support; full SfM stack with nontrivial dependencies. | **Optional offline SfM candidate.** File-level copyleft and package size require isolated module and notices. Not immediate mobile fallback. |
| [COLMAP](https://github.com/colmap/colmap) | New BSD project; mature SfM/MVS, optional CUDA; bundled dependencies have separate licenses. Primarily desktop-oriented. | **Desktop benchmark/import-export tool.** Use to establish reconstruction baselines and cross-check datasets, not as required Android runtime. |
| [OpenMVS](https://github.com/cdcseacave/openMVS) | AGPL-3.0; complete dense reconstruction, mesh refinement and texture chain. | **Do not link into distributed AAR or proprietary host apps.** Permit documented user-operated external interoperability only after legal review. Algorithms remain a research reference. |
| [Open3D](https://github.com/isl-org/Open3D) | MIT; registration, TSDF, meshing and visualization; large desktop-oriented dependency/build footprint. | **Desktop validation/reference tool.** Consider a separately packaged accelerator, not the guaranteed Android backend. |
| [voxblox](https://github.com/ethz-asl/voxblox) | BSD-3-Clause; proven TSDF/ESDF concepts, but ROS/Eigen-oriented and includes third-party meshing provenance. | **Reference and possible isolated backend.** Audit transitive files before reuse; a small bounded tiled native TSDF remains preferable for the core AAR. |

## Mesh, texture and viewing

| Project | Finding | Decision |
|---|---|---|
| [xatlas](https://github.com/jpcy/xatlas) | MIT, C++11, no external dependencies; directly generates seam-aware unique UV atlases. | **High-priority adoption after meshing.** Vendor a pinned reviewed release/source snapshot with notice and wrap it behind bounded input/output validation. |
| [meshoptimizer](https://github.com/zeux/meshoptimizer) | MIT, compact C/C++ sources; simplification, cache/fetch optimization, quantization and glTF compression. Documentation warns that overdraw optimization may not help tiled mobile GPUs. | **High-priority adoption after meshing.** Use measured simplification/LOD and cache optimization; gate overdraw transforms by device benchmarks. |
| [Filament](https://github.com/google/filament) | Apache-2.0, first-class Android Maven artifacts, PBR and glTF loader; runtime material compiler is notably large. | **Optional Android viewer module.** Keep it outside the core AAR and use prebuilt materials; retain dependency-free WebGL viewer. |
| [TinyGLTF](https://github.com/syoyo/tinygltf) | MIT header-oriented glTF reader/writer with separately identified permissive components. | **No immediate adoption.** Current validated writer is smaller and controlled; reconsider for broad import support rather than replacing export. |

## Object detection and recognition

Licensing has four independent layers: **runtime code**, **model architecture/training code**, **pretrained weights and their terms**, and the **label/training datasets**. An Apache-2.0 runtime does not make arbitrary weights or labels Apache-2.0, and conversion to ONNX/TFLite does not erase upstream obligations. Every packaged model manifest must therefore retain artifact hash, byte size, SPDX/source information and labels; release review must separately retain weight and dataset provenance.

| Project / family | Android and deployment finding | Decision |
|---|---|---|
| [LiteRT](https://github.com/google-ai-edge/LiteRT) | Apache-2.0; Android CPU plus GPU/NPU delegate paths, quantized TFLite ecosystem. Delegate availability and operator support vary by device; bundled native ABIs materially affect AAR size. | **Preferred optional TFLite adapter.** Keep outside the portable decoder contract; test CPU fallback when delegate creation/execution fails. |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT; mature Android CPU and NNAPI/XNNPACK-style execution-provider options, broad operator coverage. A general build is larger than a reduced-operator build. | **Preferred optional ONNX adapter.** Pin model/operator set and benchmark reduced builds; never require it for tracking. |
| [MediaPipe](https://github.com/google-ai-edge/mediapipe) | Apache-2.0; convenient Tasks/stream graphs and Android acceleration, but a higher-level graph/runtime surface with its own output conventions and transitive footprint. | **Optional host integration**, not the core tensor ABI. Adapt accepted outputs to `SemanticDetection2d`. |
| [ncnn](https://github.com/Tencent/ncnn) | BSD-3-Clause license text; mobile-focused C++ runtime, ARM optimization and Vulkan, with relatively small dependency surface. Conversion fidelity and Vulkan/device behavior require model-specific tests. | **Strong optional native adapter candidate.** Particularly appropriate for YOLOX/NanoDet after measured APK/thermal review. |
| [MNN](https://github.com/alibaba/MNN) | Apache-2.0; mobile CPU, FP16/INT8 and OpenCL/Vulkan-oriented paths. Adds another converter/runtime lifecycle to validate. | **Viable optional adapter**, selected only when it beats LiteRT/ORT/ncnn on target hardware. |
| [ExecuTorch](https://github.com/pytorch/executorch) | BSD license; Android AAR and PyTorch export workflow with XNNPACK/Vulkan/vendor backends. Broader integration and generated-program surface than a raw tensor adapter. | **Optional PyTorch-oriented path**, not a default dependency. |
| OpenCV DNN | Apache-2.0 within current OpenCV; broad computer-vision package and generally substantial binary footprint. | **Deferred.** Adopt only if a measured reduced build justifies overlap with existing native vision code. |
| [YOLOX](https://github.com/Megvii-BaseDetection/YOLOX) | Apache-2.0 training/deployment repository; anchor-free rows and light variants, with ONNX/ncnn/MNN deployment references. | **Supported output family now** through bounded objectness-times-class decoding. Weight provenance remains model-specific. |
| SSD / EfficientDet `Detection_PostProcess` | Common four-output TFLite contract: boxes, classes, scores and valid count. The Google AutoML repository is Apache-2.0 but archived. | **Supported output family now.** Treat EfficientDet code as a format reference, not a new maintained dependency. |
| RT-DETR-style exports | Common normalized center boxes plus class probabilities or logits; PaddleDetection is Apache-2.0 and provides export workflows. Exact export tensor order is not universal. | **Supported row contract now** with explicit no-objectness and probability/sigmoid/softmax configuration. Validate each exported model exactly. |
| [NanoDet](https://github.com/RangiLyu/nanodet) / PicoDet | Apache-2.0 mobile detector families, often using multi-level distribution-focal box regression rather than plain four-coordinate rows. | **Supported merged and separated DFL contracts now.** Each concrete export must declare ordered feature strides, class activation, `reg_max`, column layout, exact row count and—when separated—the exact alternating class/regression output order. |
| [Ultralytics](https://github.com/ultralytics/ultralytics) | AGPL-3.0 plus separate enterprise terms. Export to ONNX/TFLite does not automatically resolve code/model licensing obligations. | **Excluded from permissive default distribution** unless a separate legal/commercial decision records applicable terms. |

The implemented `RawDetectionTensorSpec`, `AnchorFreeDetectionTensorBackend`, merged/separated distribution-focal backends and `SsdDetectionTensorBackend` preserve runtime/model separation. Raw tensors are direct and native-order; shapes, strides, element encodings, quantization, byte counts, class ranges, valid-count tensors and manifest labels are checked. YOLOX-sized raw outputs may exceed the native 4096-candidate NMS budget: rows are decoded under a 32,768-row/8-million-class-value ceiling, deterministically score-sorted, and only the best 4096 enter NMS. This avoids simply making quadratic NMS unbounded. All buffers are reused by verified backends, with 64 MiB per-tensor and 96 MiB multi-output ceilings.

Before making any runtime/model pair recommended, record arm64-v8a/x86_64 size delta, cold/warm latency, p50/p95, peak RSS, 30-minute thermal behavior, INT8/FP16 accuracy delta, delegate fallback, malformed-output response, and detection mAP/recall on a provenance-known evaluation set. Recognition/classification embeddings are not yet implemented; detector labels are not identity recognition.

## Device and CI testing

| Project | What it can prove | Decision |
|---|---|---|
| [AndroidX Test](https://github.com/android/android-test) | Instrumentation runner, JUnit integration, Espresso/UI Automator and process isolation on emulator or device. Apache-2.0. | **Adopted now.** API-35 instrumentation smoke test loads packaged x86_64 JNI, creates native VIO/planner owners and exercises lifecycle. |
| [AndroidX CameraX](https://github.com/androidx/androidx) | Apache-2.0; maintained Camera2 quirk corpus includes incorrect sizes/crops, YUV shifts, session failures and camera timebases that disagree with metadata. | **Knowledge source and optional adapter candidate.** Port behavior tests and prefer active detection; retain direct Camera2 precision control and use narrow versioned model rules only for non-probeable failures. |
| Android Camera ITS (AOSP CTS) | Apache-2.0 source; controlled tests cover unified timestamps, sensor events, jitter, IMU drift, crop, lens movement, exposure and format combinations. | **Adopt measurements into the physical rig.** Certification is useful baseline evidence, not proof of ARKernel trajectory or dimensional accuracy. |
| [Android Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner) | Starts hardware-accelerated Android emulators in GitHub Actions. Apache-2.0. | **Adopted now at commit `a421e43855164a8197daf9d8d40fe71c6996bb0d` (v2.38.0).** Separate CI job enables KVM and runs `connectedDebugAndroidTest`. |
| [Mobly](https://github.com/google/mobly) | Python host orchestration for multiple Android devices, custom hardware, RF equipment and instrumentation snippets. Apache-2.0; current README requires Python 3.14+. | **Adopt when a physical device bench exists.** Best fit for synchronized camera/IMU runs, calibration targets, thermal chambers and capability-set-cover device matrices. |
| [Cuttlefish](https://github.com/google/android-cuttlefish) | Scalable configurable virtual Android devices on local x86/arm64 or cloud hosts. It virtualizes framework behavior, not real optics/ISP/IMU noise. | **Future API/lifecycle/fault matrix.** Never count virtual runs as camera calibration, VIO accuracy or thermal evidence. |
| [GenPairs](https://github.com/TestCreator/GenPairs) | BSD-3-Clause pairwise covering-array generator. | **Tool/reference candidate.** Generate broad pairwise and targeted higher-strength capability combinations without creating a Cartesian device explosion. |
| [DeviceFarmer STF](https://github.com/DeviceFarmer/stf) | Browser/ADB access, inventory, reservation and health for USB-connected Android device racks. Apache-2.0 text. | **Infrastructure option only.** Useful after owning several devices; it does not create accuracy tests and has meaningful server/USB operational cost. |
| [Maestro](https://github.com/mobile-dev-inc/Maestro) | Apache-2.0 UI/E2E flows on emulators and physical Android devices. | **Use later for a sample scanning app.** Not suitable for validating VIO, sensor timestamps or metric geometry by itself. |
| [Android Emulator Container Scripts](https://github.com/google/android-emulator-container-scripts) | Apache-2.0 Docker/KVM emulator hosting for self-managed runners. | **Optional future self-hosted CI.** Current GitHub runner action is simpler; containers become useful for a persistent emulator pool. |
| [GoogleTest](https://github.com/google/googletest) / [Google Benchmark](https://github.com/google/benchmark) | Standard native test discovery and microbenchmark harnesses. BSD-3-Clause / Apache-2.0. | **Benchmark candidate, not urgent migration.** Existing dependency-free native tests already run strict, ASan, UBSan and replay; add Benchmark when stable device perf budgets exist. |

## What emulator CI does and does not validate

The new emulator job validates the Android package boundary that host JVM tests cannot:

- x86_64 native library is packaged and loadable;
- JNI names/signatures and native owner creation/destruction work under Android;
- instrumentation APK installation and API-35 runtime compatibility;
- basic pause/resume/reset lifecycle does not crash.

It **does not** validate physical camera or inertial behavior:

- Camera2 timestamp/exposure ordering and YUV stride diversity;
- camera-to-IMU extrinsics, rolling shutter or sensor clock drift;
- autofocus, motion blur, lighting and texture response;
- thermal throttling, sustained memory bandwidth and battery use;
- ARCore comparison or centimetre-level dimensional accuracy.

Those require owned physical devices and measured targets. Open-source orchestration can reduce test engineering work, but cannot replace the hardware or ground truth.

## Recommended rollout

1. Keep emulator instrumentation on every Android CI push.
2. Add a small sample/test APK that replays packaged camera/IMU fixtures through JNI on API 26, 29 and 35; shard only after CI duration is measured.
3. Establish an owned 8+ device matrix. Use Mobly to collect model/build/calibration, run repeated scans, pull trajectories/assets and record thermal state.
4. Evaluate Ceres in an optional module against the portable pose graph/BA using identical replay inputs and acceptance budgets.
5. Implement bounded MVS/TSDF fallback; use COLMAP/Open3D desktop results as comparative oracles.
6. After mesh extraction, integrate pinned xatlas and meshoptimizer with license notices, fuzzed boundary wrappers and before/after quality/size benchmarks.
7. Add optional Filament viewer artifact; never add it to the core AAR dependency graph.

## Acceptance evidence for an adopted library

Record the exact source revision, license and notices; enabled features; disabled copyleft/accelerator components; supported ABIs; added compressed/uncompressed bytes; peak RSS; median/p95 runtime; thermal run; deterministic output status; malformed-input behavior; and accuracy delta on the same recorded dataset. No adoption may weaken the existing failure semantics or turn a current optional capability into a required network/service dependency.
