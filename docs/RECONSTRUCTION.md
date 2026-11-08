# Metric reconstruction, object scanning, textures and export

## Accuracy contract

No camera-only Android implementation can truthfully guarantee absolute centimetre accuracy for every device, material, distance and capture path. Monocular scale can drift; learned depth can be biased; rolling shutter, thermal calibration changes, motion blur, reflective or textureless surfaces and incomplete viewpoints are observable failure modes. The kernel therefore separates **metric units** from **validated metric accuracy**.

`assessReconstructionQuality` emits:

- `unverified`: no independently observed metric scale or unusable capture;
- `approximate`: exportable geometry in metres, but an accuracy gate failed;
- `centimeter-validated`: every configured evidence gate passed for this capture.

The default policy requires calibrated intrinsics and camera/IMU extrinsics, independent metric scale, converged global optimization, reprojection RMSE <= 1 px, metric-depth RMSE <= 0.01 m, scale standard deviation <= 0.5%, and observed coverage >= 70% for environments or 85% for objects. These gates are not metrology certification. Validation must use held-out scale bars, surveyed targets or sensor ground truth. Apps must retain the metrics with each export and must not relabel `approximate` output.

For a 2 m object, 0.5% scale uncertainty alone is 1 cm. `assessed_extent_m * scale_stddev_ratio` is therefore also gated against a 1 cm absolute limit. Larger rooms automatically need a stricter relative-scale result. This is still a conservative uncertainty proxy; the future held-out target evaluator must report dimensional error directly.

## Two capture modes

### Environment

Live VIO supplies immediate poses, planes and anchors. Final reconstruction uses sharp keyframes and depth, loop closure and global pose optimization, then bounded volumetric fusion. Large scenes are spatially tiled. Dynamic masks exclude people, vehicles, displays and moving regions. Unknown boundaries remain explicit; holes are not silently closed.

### Isolated object

Object mode owns a bounded object frame and region of interest. Foreground segmentation excludes support surface and background before feature, depth and texture fusion. Capture guidance requires azimuth/elevation rings and top, side and where possible underside coverage. Turntable mode must model object rotation rather than treating it as a static world. Completeness, blur, exposure consistency, triangulation angle and distance are independent gates.

Object mode cannot reuse environment assumptions blindly: an object may move, be textureless, reflective, thin, symmetric or occluded. Each case lowers confidence or blocks a validated result.

`ReconstructionCapturePlanner` now implements the portable selection layer. It rejects non-monotonic timestamps, weak tracking, blur, clipped exposure, too few features, excessive pose uncertainty, repeated viewpoints, invalid ROI, unsafe object distance and capacity overflow. Accepted image bytes stay in a platform archive; the core stores only an opaque reference and reproducibility metadata. Object views populate bounded azimuth/elevation bins around a stable object center, exposing measured directional coverage. This is capture coverage, not proof that every surface reconstructed successfully.

Android provides `ReconstructionFrameArchive` behind those references. Every accepted frame is an independent, versioned `ARKFRM01` envelope written with `AtomicFile`; a failed write cannot invalidate earlier keyframes. The envelope binds frame ID, hardware timestamp, dimensions/stride, encoding, exposure time, ISO, full pose, pose uncertainty and image bytes with CRC32. Frame and total-byte budgets evict oldest files deterministically, while session IDs and references cannot escape app-private no-backup storage. CRC32 detects accidental corruption but is not authentication or encryption. Camera imagery is sensitive: hosts must expose explicit retention/deletion controls, call `clear()` when a temporary scan is abandoned, and apply stronger application-level encryption where the threat model requires protection beyond Android app-private file-based storage.

`ArKernelReconstructionPlanner` now owns the portable C++ capture planner through a lifecycle-safe JNI handle. Android sends the exact world pose, tracking state, accepted feature count, pre-copy sharpness/clipping, pose uncertainty and optional object center/foreground ratio. The result contains an explicit rejection reason, quality score, object view bin and directional coverage. For camera pipelines, `reserve()` performs the pre-copy decision without changing keyframes or coverage; exactly one bounded reservation may exist. `commit(token)` publishes it only after durable archive storage, while `cancel(token)` handles copy, backpressure or storage failure and still preserves monotonic timestamp semantics. The immediate `consider()` path remains only for payloads that are already durable. Android exposes all three operations through JNI. `ReconstructionScanController` now supplies the session quality-gate and colour-frame callbacks, owns one bounded writer, commits only from the successful archive callback, cancels on conversion/queue/storage failure, and cancels an unfulfilled image reservation when the next candidate arrives or the controller closes. Host callbacks are isolated so application UI exceptions cannot corrupt planner/storage state.

The Camera2 bridge now associates exposure time and ISO by exact `SENSOR_TIMESTAMP`, never by callback order. Native visual keyframes can be copied from arbitrary `YUV_420_888` row/pixel strides into canonical I420 while the Android `Image` is alive; session VIO then contributes the world pose with the identical timestamp. If the exact `CaptureResult` arrives after its image callback, at most two already-owned keyframes wait in a timestamp-keyed bounded handoff; overflow drops the oldest. A frame is never given guessed metadata. `BoundedReconstructionFrameWriter` copies ownership, performs archive I/O on one worker, drains accepted frames on close, and returns `false` immediately when its bounded queue is saturated. This explicit frame drop is preferable to camera-thread stalls and unbounded memory growth.

## Reconstruction stages

1. **Calibrate and synchronize**: versioned intrinsics, distortion, rolling shutter/readout, camera/IMU transform and hardware timestamps.
2. **Capture**: retain full-resolution YUV/RGB keyframes, exposure metadata, exact pose and uncertainty. Never texture from inference-resized frames.
3. **Quality selection**: reject blur, clipping, low texture, repeated poses and excessive covariance; retain useful baseline and view angle.
4. **Global geometry**: bounded appearance place recognition proposes temporally separated weighted keyframe candidates. Metric loop verification requires descriptor uniqueness, robust non-degenerate 3D–3D geometry, inlier/ratio/RMSE gates and bounded disagreement from the VIO relative pose. A bounded transactional SE(3) pose graph now distributes accepted loop corrections across uncertainty-weighted odometry while fixing the world gauge. Global bundle adjustment follows. VIO and appearance similarity are priors, not final geometry.
5. **Dense depth**: calibrated multi-view stereo; fuse sensor or learned depth only with uncertainty. Learned monocular depth cannot establish absolute scale alone.
6. **Fusion and meshing**: sparse tiled TSDF, confidence weighting, Marching Cubes or dual contour extraction, component filtering and edge-aware simplification. Current `SurfelMap` is a live precursor, not the final mesh.
7. **Texture**: visibility tests, view scoring, exposure compensation, UV atlas, seam optimization and atlas baking. Retain source-frame provenance.
8. **Validate**: held-out targets, residuals, scale uncertainty, loop consistency, completeness and held-out view rendering.
9. **Export**: write metres and quality metadata; preserve the non-decimated original.

## Formats

The dependency-free `ReconstructionMesh` carries indexed triangle positions in metres, optional normals, optional UV coordinates and a base-colour texture URI. Vertex and triangle budgets, finite values and every index are validated.

Core exports:

- **glTF 2.0 (`.gltf` + `.bin` + PNG/JPEG)**: primary interchange/viewer format with indexed geometry, normals, UVs and PBR base colour;
- **GLB (`.glb`)**: single binary geometry/material container; current image URI stays external, while a data URI or later image-embedding adapter can make textures single-file too;
- **OBJ + MTL + texture**: broad DCC compatibility; units are documented as metres because OBJ has no authoritative unit field;
- **PLY**: inspection pipeline, currently ASCII triangles and normals.

Planned adapters include GLB, USDZ, untextured STL, LAS/LAZ/E57 and 3D Tiles. STL cannot preserve textures. Convert from the validated canonical mesh, never repeatedly from lossy exports.

## Viewer

`tools/viewer/index.html` is a dependency-free WebGL 2 viewer for the generated glTF subset. Select one `.glb`, or select `.gltf`, referenced `.bin` and optional texture together. It supports orbit, zoom, normals, indexed triangles and base-colour textures without uploads or network access. Unsupported component encodings are rejected.

An Android product viewer should be a separate optional module and load glTF/GLB through Filament `gltfio`; this keeps a renderer out of the headless core AAR.

## Research basis

- COLMAP separates Structure-from-Motion, global optimization and Multi-View Stereo; it is a final-reconstruction reference rather than an Android runtime dependency.
- OpenMVS implements dense reconstruction, mesh reconstruction/refinement and multi-view texturing from poses and sparse geometry.
- Open3D provides references for registration, reconstruction, processing, visualization and PBR.
- Khronos glTF 2.0 is royalty-free and designed for efficient loading and broad tool interoperability.
- Filament is a mobile PBR renderer with Android and glTF/GLB support and belongs in an optional viewer adapter.
- Neural radiance fields help novel-view rendering but do not replace metric geometry and scale validation.

Every adopted dependency and transitive license requires review. The current exporter and browser viewer add no third-party runtime dependency.
