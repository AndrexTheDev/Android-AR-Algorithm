# Metric reconstruction, object scanning, textures and export

## Accuracy contract

No camera-only Android implementation can truthfully guarantee absolute centimetre accuracy for every device, material, distance and capture path. Monocular scale can drift; learned depth can be biased; rolling shutter, thermal calibration changes, motion blur, reflective or textureless surfaces and incomplete viewpoints are observable failure modes. The kernel therefore separates **metric units** from **validated metric accuracy**.

`assessReconstructionQuality` emits:

- `unverified`: no independently observed metric scale or unusable capture;
- `approximate`: exportable geometry in metres, but an accuracy gate failed;
- `centimeter-validated`: every configured evidence gate passed for this capture.

The default policy requires calibrated intrinsics and camera/IMU extrinsics, independent metric scale, converged global optimization, reprojection RMSE <= 1 px, metric-depth RMSE <= 0.01 m, scale standard deviation <= 0.5%, and observed coverage >= 70% for environments or 85% for objects. These gates are not metrology certification. Validation must use held-out scale bars, surveyed targets or sensor ground truth. Apps must retain the metrics with each export and must not relabel `approximate` output. The additive `exportAssessedGltf`, `exportAssessedGlb`, `exportAssessedObj`, and `exportAssessedPly` paths recompute the assessment from raw quality evidence, reject output when `export_allowed` is false, and embed grade, held-out scalar metrics and quality failures in glTF extras or format-native comments. Legacy geometry-only exporters remain compatible but label output `unverified`.

For a 2 m object, 0.5% scale uncertainty alone is 1 cm. `assessed_extent_m * scale_stddev_ratio` is therefore also gated against a 1 cm absolute limit. Larger rooms automatically need a stricter relative-scale result.

`evaluateHeldOutDimensions` now supplies the required direct dimensional check. It computes reconstructed distances itself from bounded 3D point pairs and compares them with independent reference distances. The same evaluator is exposed through additive versioned C structs in `ark_evaluate_held_out_dimensions` and through Android's `ArKernelDimensionalValidator`; both return stable reason flags/enums plus the complete scalar report rather than parsing diagnostic strings. Every observation carries a unique bounded evidence ID, explicit scale-bar/survey/sensor-ground-truth provenance, reference uncertainty, and an assertion that the pair influenced neither calibration nor optimization. Unknown provenance, reused IDs, non-finite points, excessive uncertainty, non-held-out data, budget overflow, insufficient observations or insufficient dimension diversity fail the report. Default acceptance requires at least three observations across at least two 0.25 m dimension bins, raw mean absolute error <= 1 cm, uncertainty-adjusted maximum absolute error <= 1 cm, and maximum relative error <= 2%. The largest reference dimension must also cover the asset extent being assessed. `assessReconstructionQuality` always runs this evaluator and cannot emit `centimeter-validated` without a passing report; missing evidence yields at most `approximate` when export remains otherwise safe. `ArKernelSurveyMeasurementCsv` is a bounded transport adapter for this evidence: a canonical header, strict input/line/row budgets, unique bounded IDs, exact provenance enums, finite numeric fields and explicit held-out flags fail closed on malformed input and can be encoded/parsed deterministically. It neither measures a survey nor runs the evaluator or grants a quality grade. `ArKernelSurveyMeasurementJsonl` adds a bounded canonical JSON Lines transport adapter with fixed key order, no whitespace/nesting/escaping, one optional final line separator, strict budgets, unique IDs, exact provenance, finite JSON numbers and fail-closed deterministic encode/parse; it likewise neither measures evidence, runs the evaluator nor grants a quality grade. `ArKernelSurveyMeasurementTsv` adds a third bounded canonical transport adapter with an `ARKSURVEYTSV1` marker, an exact tab-separated header, strict budgets, unique IDs, exact provenance, finite numbers and fail-closed deterministic encode/parse; it likewise measures no evidence, runs no evaluator and grants no quality grade. A bounded canonical `ARKSURVEYBIN1` transport adapter is now implemented as a fixed 24-byte header, 1–64 fixed 200-byte records and a little-endian CRC-32 trailer; it has exact magic/version, strict byte/row budgets, zero-padded ASCII IDs, exact reference-kind/held-out bytes, finite little-endian doubles, deterministic encode/parse and stable fail-closed rejections. CRC-32 detects accidental corruption, not tampering; the adapter measures no evidence, runs no evaluator, performs no I/O and grants no quality grade. USDZ, other external survey formats, real survey evidence, physical validation and the Filament viewer remain open.

## Two capture modes

### Environment

Live VIO supplies immediate poses, planes and anchors. Final reconstruction uses sharp keyframes and depth, loop closure and global pose optimization, then bounded volumetric fusion. Large scenes are spatially tiled. Dynamic masks exclude people, vehicles, displays and moving regions. Unknown boundaries remain explicit; holes are not silently closed.

### Isolated object

Object mode owns a bounded object frame and region of interest. Foreground segmentation excludes support surface and background before feature, depth and texture fusion. Capture guidance requires azimuth/elevation rings and top, side and where possible underside coverage. Turntable mode must model object rotation rather than treating it as a static world. Completeness, blur, exposure consistency, triangulation angle and distance are independent gates.

Object mode cannot reuse environment assumptions blindly: an object may move, be textureless, reflective, thin, symmetric or occluded. Each case lowers confidence or blocks a validated result.

`ReconstructionCapturePlanner` now implements the portable selection layer. It rejects non-monotonic timestamps, weak tracking, blur, clipped exposure, too few features, excessive pose uncertainty, repeated viewpoints, invalid ROI, unsafe object distance and capacity overflow. Accepted image bytes stay in a platform archive; the core stores only an opaque reference and reproducibility metadata. Object views populate bounded azimuth/elevation bins around a stable object center, exposing measured directional coverage. This is capture coverage, not proof that every surface reconstructed successfully.

Android provides `ReconstructionFrameArchive` behind those references. Every accepted frame is an independent, versioned `ARKFRM01` envelope written with `AtomicFile`; a failed write cannot invalidate earlier keyframes. The envelope binds frame ID, hardware timestamp, dimensions/stride, encoding, exposure time, ISO, full pose, pose uncertainty and image bytes with CRC32. Frame and total-byte budgets evict oldest files deterministically, while session IDs and references cannot escape app-private no-backup storage. CRC32 detects accidental corruption but is not authentication. With opt-in `authenticatedEncryption`, each colour frame and its frame-owned visibility-depth companion is wrapped in a strict `ARKFEAD1` AES-256-GCM envelope. The non-exportable key lives in Android Keystore and the GCM tag authenticates a domain separator, session ID, file name and colour/depth role, so tampering, file swapping or key deletion fail closed on load. `clear(deleteKey = true)` and `deleteEncryptionKey()` are explicit host retention actions; `ReconstructionFrameRetentionController` turns a terminal publication outcome or an explicit release into a bounded action. `ArKernelRetentionStrings` adds resource-qualified German/English dialog text, three UI-neutral deletion choices, encryption-key availability and a fail-closed selection contract. `ArKernelRetentionDialog` renders those strings in a bounded platform dialog with dynamic key-deletion availability and explicit confirm/cancel; invalid choices stay open and report an error. It requires an Activity context with a window token and explicit confirmation, never deletes data by itself and leaves full app UX composition to the host. `ArKernelRetentionUiComposition` adds a bounded host-owned composition of one dialog at a time, fail-closed policy confirmation, explicit publication/release actions and localized result text over an `ArKernelRetentionActionSink`; hosts still apply actions and own full app UX and physical-device lifecycle validation. These protect bytes at rest only: physical-device lifecycle validation remains open, and the separate `ARKSES01` state archive remains CRC-only.

`ArKernelReconstructionPlanner` now owns the portable C++ capture planner through a lifecycle-safe JNI handle. Android sends the exact world pose, tracking state, accepted feature count, pre-copy sharpness/clipping, pose uncertainty and optional object center/foreground ratio. The result contains an explicit rejection reason, quality score, object view bin and directional coverage. For camera pipelines, `reserve()` performs the pre-copy decision without changing keyframes or coverage; exactly one bounded reservation may exist. `commit(token)` publishes it only after durable archive storage, while `cancel(token)` handles copy, backpressure or storage failure and still preserves monotonic timestamp semantics. The immediate `consider()` path remains only for payloads that are already durable. Android exposes all three operations through JNI. `ReconstructionScanController` now supplies the session quality-gate and colour-frame callbacks, owns one bounded writer, commits only from the successful archive callback, cancels on conversion/queue/storage failure, and cancels an unfulfilled image reservation when the next candidate arrives or the controller closes. Host callbacks are isolated so application UI exceptions cannot corrupt planner/storage state.

`ObjectCaptureGuidancePlanner` adds a separate bounded deterministic state machine for isolated-object scans. It consumes only host-measured foreground ratios, a finite object centre, a calibrated camera pose and tracking reliability; it does not segment pixels, estimate the centre, actuate a turntable or infer surface reconstruction success. A stable centre gate, explicit distance/ROI gates, measured azimuth/elevation bins, per-ring coverage and a strict observation budget drive UI-neutral phases and instructions. The bounded turntable target is a deterministic desired camera position around the stable centre, including next view azimuth/elevation and the shortest orbit step; it is not physical motion control or device evidence. `ArKernelObjectCaptureGuidance` exposes the same state through lifecycle-safe JNI with exact phase/instruction/rejection and telemetry arrays. `ArKernelObjectCaptureGuidancePrompts` adds bounded German/English UI-neutral prompt formatting with measured coverage detail and exact tone, while the native telemetry stays authoritative. `ArKernelTurntableActuationController` adds a bounded host-owned actuation protocol: one pending command at a time, exact IDs, monotonic timestamp/deadline checks, step/target integrity and measured executed-step feedback fail closed on adapter or feedback faults until an explicit reset. The app implements `ArKernelTurntableActuator`; the library never controls hardware. Coverage completion remains measured view coverage only and never upgrades the separate `unverified` quality contract. Physical turntable/rig evidence and real-device validation remain open.

The Camera2 bridge now associates exposure time and ISO by exact `SENSOR_TIMESTAMP`, never by callback order. Native visual keyframes can be copied from arbitrary `YUV_420_888` row/pixel strides into canonical I420 while the Android `Image` is alive; session VIO then contributes the world pose with the identical timestamp. If the exact `CaptureResult` arrives after its image callback, at most two already-owned keyframes wait in a timestamp-keyed bounded handoff; overflow drops the oldest. A frame is never given guessed metadata. `BoundedReconstructionFrameWriter` copies ownership, performs archive I/O on one worker, drains accepted frames on close, and returns `false` immediately when its bounded queue is saturated. This explicit frame drop is preferable to camera-thread stalls and unbounded memory growth.

## Reconstruction stages

1. **Calibrate and synchronize**: versioned intrinsics, distortion, rolling shutter/readout, camera/IMU transform and hardware timestamps.
2. **Capture**: retain full-resolution YUV/RGB keyframes, exposure metadata, exact pose and uncertainty. Never texture from inference-resized frames.
3. **Quality selection**: reject blur, clipping, low texture, repeated poses and excessive covariance; retain useful baseline and view angle.
4. **Global geometry**: bounded appearance place recognition proposes temporally separated weighted keyframe candidates. Metric loop verification requires descriptor uniqueness, robust non-degenerate 3D–3D geometry, inlier/ratio/RMSE gates and bounded disagreement from the VIO relative pose. Only final geometric inlier pairs can drive deterministic complete-link landmark merging. A bounded transactional SE(3) pose graph distributes accepted loop corrections across uncertainty-weighted odometry while fixing the world gauge. Bounded joint bundle adjustment then refines poses and multi-view landmarks against robust distortion-aware reprojection while retaining metric pose priors. VIO and appearance similarity are priors, not final geometry. `ArKernelLoopClosureCandidateBundle` adds only a bounded canonical `ARKLOOP1` text ledger for host-proposed candidates: opaque keyframe IDs, finite non-negative residual/uncertainty scalars, bounded inlier counts and the fixed state `proposed`. It performs no descriptor matching, geometric verification, pose-graph optimization, global bundle adjustment, map mutation or quality upgrade and stays `unverified`. `ArKernelLoopClosureGeometricGate` adds a separate bounded host-owned `ARKLOOPGC1` consistency envelope for one `ARKLOOP1` candidate: canonical nineteen-key metadata, 1–64 opaque 3D–3D correspondences, a unit quaternion/finite translation transform and an explicit policy gate residuals, RMSE, inlier count/ratio, prior disagreement and spatial spread. It can cross-check the candidate's exact ID/keyframe pair, inlier budget and conservative residual/uncertainty bound, but carries no descriptors or images, does not itself run RANSAC, produces no verified loop, optimizes no pose graph, mutates no map, performs no I/O and stays `unverified`.
5. **Optimized archive view**: globally optimized keyframe poses are atomically published as a versioned CRC-protected `ARKPOS01` manifest. Immutable frame envelopes remain the audit/fallback source; dense consumers resolve entries only by matching frame ID and timestamp. Generation rollback, duplicate identities, missing frames and partial manifests are rejected, while frame eviction or deletion invalidates the manifest.
6. **Dense depth**: the portable fallback now performs bounded calibrated inverse-depth plane sweeping over globally posed grayscale keyframes. Distortion-aware fronto-parallel patch reprojection, baseline gates, minimum supporting views, winner margin and hypothesis-spacing uncertainty produce sparse valid metric depth while leaving ambiguity as NaN. Matching is explicitly selectable as ZNCC, ternary Census or a weighted hybrid (the default). Census compares each warped patch sample against its patch centre, tolerates bounded intensity ties and requires a configurable minimum number of informative comparisons in both images; this adds robustness to monotonic camera-response and exposure changes without accepting textureless patches. All three costs share a normalized `[-1, 1]` score gate; the historical `min_zncc` field name is retained for source compatibility. A bounded coarse-to-fine stage refines each accepted coarse inverse-depth mode for up to three local levels with at most 33 hypotheses per level. It preserves the coarse non-neighbour peak for ambiguity gating, because adjacent fine samples belong to the same mode, and derives final sampling uncertainty from the refined spacing. A separate bounded consistency pass consumes cached source-view depth maps rather than recursively recomputing them. It rejects a reference sample unless enough baseline-qualified views agree in source-camera depth and return within a configured reprojection radius. The depth tolerance is the maximum of absolute, relative and uncertainty-propagated bounds; low-confidence or malformed source samples cannot vote. Surviving confidence is reduced by the consistent/evaluated-view ratio, while tested and rejected counts remain observable. This synthetic coverage is not a substitute for physical multi-camera/exposure testing. Sensor or learned depth is fused only with uncertainty; learned monocular depth cannot establish absolute scale alone.
7. **Fusion and meshing**: the portable core includes a bounded narrow-band sparse TSDF with fixed 8×8×8 blocks, calibrated projective integration, confidence/uncertainty weighting, deterministic voxel snapshots and explicit pixel, ray-sample, voxel-visit, block and snapshot budgets. It allocates only near observed surfaces and deliberately does not claim complete free-space carving. Block-capacity or work-budget exhaustion is reported as truncation rather than silently growing memory. `SurfelMap` remains the live mapping representation, while TSDF input is intended for globally optimized, consistency-filtered depth. Deterministic bounded Marching Tetrahedra extracts shared indexed metric vertices and area-weighted normals only from fully observed, sufficiently weighted cells; missing evidence remains a hole and cell/vertex/triangle exhaustion remains explicit. A bounded edge-connected component pass removes small triangle/area islands, compacts all indexed attributes, supports deterministic largest-only selection, and reports boundary/manifold/non-manifold edge counts. A conservative deterministic clustering fallback now reduces smooth interiors within metric position, normal and UV error bounds while locking boundary, sharp, non-manifold and topologically ambiguous vertices. An optional measured quadric-error-guided clustering LOD additionally chooses plane-optimal cluster representatives under an explicit squared-distance budget and reports initial/final quadric error and maximum displacement; it is not an unconstrained dynamic edge-collapse QEM topology optimizer. Both clustering stages remove duplicate/degenerate triangles and recompute normals, but do not guarantee a target ratio, watertightness or the quality of a full edge-collapse QEM. A separate bounded deterministic edge-collapse QEM delivery simplifier now changes topology: after each accepted collapse it rebuilds plane quadrics, adjacency, feature locks and candidate priorities from the current triangles. Only manifold interior edges with the exact two-vertex link condition may collapse, under measured quadric, cumulative displacement/UV, normal, area and topology bounds. It is opt-in through the native pipeline and JNI/Kotlin, reports separate telemetry including maximum cumulative UV error and stays `unverified`; it is not an unconstrained topology optimizer, a benchmark or a quality upgrade. This is still not a final watertight mesh.

`ReconstructionFusionPipeline` composes these independent stages without hiding their telemetry. The portable owner enforces strictly increasing accepted depth timestamps, a capture-frame ceiling, cumulative counters and sticky evidence of any fusion truncation. A build retains the filtered source mesh separately from its simplified copy and marks completeness false if fusion, meshing, filtering, simplification, optional quadric LOD or optional edge-collapse QEM exhausted a budget. Android's lifecycle-safe owner passes direct metric depth/confidence/uncertainty buffers synchronously and emits both representations as bounded GLB byte arrays. It does not perform model selection, infer calibration, close holes, persist sensitive scans or upgrade dimensional accuracy.
8. **Texture**: visibility tests, view scoring, exposure compensation, UV atlas, seam optimization and atlas baking. Retain source-frame provenance.
9. **Validate**: held-out targets, residuals, scale uncertainty, loop consistency, completeness and held-out view rendering.
10. **Export**: write metres and quality metadata; preserve the non-decimated original.

## Formats

The dependency-free `ReconstructionMesh` carries indexed triangle positions in metres, optional normals, optional UV coordinates and a base-colour texture URI. Vertex and triangle budgets, finite values and every index are validated.

`selectTextureViews` now performs the bounded first stage of multi-view texturing without mutating that mesh. For each triangle it evaluates uniquely identified archived image references in deterministic frame-ID order using calibrated distorted projection, geometric front-facing angle, full-corner image support, projected pixel area, capture quality and optional aligned metric Z-depth. The depth gate checks four interior barycentric locations with absolute, relative and uncertainty-derived tolerance. Missing depth is unknown rather than visible; depth-backed visibility is required by default. Output records the selected frame, source-image normalized corner coordinates and score per triangle plus explicit rejection/truncation telemetry. Candidate, vertex, triangle and projection-test budgets bound memory and work.

The selector retains a bounded top-K table of visibility-approved alternatives per triangle. `optimizeTextureViewLabels` then performs deterministic bounded iterated-conditional-modes updates over shared manifold edges, balancing retained per-face score against a configurable label-seam penalty. It never invents visibility or selects a rejected view. Open boundaries, non-manifold edges and folds above a configurable normal-angle threshold do not connect patches. Telemetry reports changed labels, iterations/evaluations, seams, connected same-view patches, sharp/non-manifold edges and budget truncation. This local method is reproducible and portable but does not claim the global optimum of a full graph-cut/alpha-expansion solver.

`layoutTextureAtlas` now converts the coherent labels into bounded UV islands and a deterministic single-page layout. Same-view triangles join only across smooth manifold edges; open, sharp and non-manifold boundaries split patches. Source-image pixel bounds are packed by a deterministic height/width-ordered shelf layout, with padding and a bounded uniform downscale when one-to-one source texel density does not fit. The resulting canonical mesh reuses vertices inside each patch and duplicates them only where patch-specific UVs require a seam. Patch records retain frame/image provenance plus source and atlas rectangles. Missing triangle labels fail closed by default, and edge, patch, input/output mesh and atlas-dimension budgets are explicit.

`bakeTextureAtlas` now rasterizes the assigned source triangles into that layout as tightly packed top-left-origin RGBA8. It accepts bounded Gray8, RGB8, RGBA8 and the exact tightly packed planar I420 emitted by Android's `ReconstructionI420Packer`; JPEG/PNG decoding remains a platform adapter. Barycentric source coordinates select pixels deterministically, and a bounded breadth-first dilation copies edge colour only inside the owning patch rectangle to populate configured padding without bleeding across atlas islands. Atlas-pixel, raster-test, padding-pixel and source-image budgets are explicit. The result remains raw pixels: a host must successfully encode PNG/JPEG before setting `ReconstructionMesh::base_color_texture`, so failed encoding cannot produce a false texture URI.

`estimateTexturePhotometry` adds a bounded image-driven exposure-normalization stage before corrected baking. It samples corresponding points along manifold edges whose adjacent faces use different source views, rejects transparent, near-black and clipped observations, and takes a robust median log-ratio independently per RGB channel. Deterministic connected-component solving anchors the lowest view-table index in each evidence graph, clamps gains to configured limits and leaves disconnected views explicitly at identity. `bakeTextureAtlasWithCorrections` validates correction order and frame provenance before applying these linear gains while preserving alpha. This is relative seam evidence, not calibrated camera-response recovery: it cannot infer corrections where no usable cross-view edge exists.

`estimateTexturePhotometry` now additionally separates seam equations into a deterministic training set and holdout set. A bounded Huber-weighted Jacobi solve downweights inconsistent training equations, while opt-in strict validation gates convergence, training RMSE, holdout maximum residual/RMSE and the train/holdout ratio. Stable `TexturePhotometryRejection` reasons and `texturePhotometryRejectionText` describe every failure; rejected estimates reset all corrections to identity so the transaction graph cannot publish partial gains. Strict validation is a core config option, not a new Android/JNI/Kotlin envelope. The result remains explicitly `unverified`: this is synthetic seam consistency evidence, not a full Poisson/multiband seam solve, physical camera calibration, a full calibrated camera-response model or a reconstruction quality upgrade.

An optional core-only synthetic inverse-response LUT can now be supplied to `estimateTexturePhotometry`, for example from the bounded `estimateCameraResponseCurve`. The LUT is validated for bin count, finite bounds, minimum monotonic slope and bounded radiance expansion, then maps observed RGB channels to relative linear radiance before log-ratio equations. Invalid or over-expanding LUTs produce stable rejection reasons, an empty linearized-sample count and identity corrections. This bridges the two bounded synthetic estimators without exposing a new C-API/JNI/Kotlin envelope; it is not physical camera calibration, a full calibrated camera-response model or a quality upgrade. The full global Poisson/multiband seam solve, physical calibration, calibrated response model and unconstrained dynamic QEM remain open.

`blendTextureAtlasSeams` adds a conservative local post-bake seam pass. It identifies manifold source edges whose adjacent triangles map to different atlas patches, follows the explicit source-to-output triangle mapping, and moves RGB values on both patch copies toward their paired edge average with distance-based falloff. Writes remain inside each owning patch rectangle, alpha is preserved, and edge, seam, pixel-test and pixel-update budgets fail observably. Open and non-manifold boundaries are not blended because they lack an unambiguous pair.

Android now provides codec and archive-resolution adapters around the portable texturing boundary. `ReconstructionFrameArchive` can atomically store a CRC-framed, tightly packed optical-Z depth companion with optional confidence and uncertainty beside a colour frame; the existing opaque `arkf://` reference remains the only lookup key. Companion provenance must match frame ID, timestamp and dimensions, frame deletion/eviction also removes depth, and colour-plus-depth bytes share the archive budget. `ReconstructionTextureSourceLoader` resolves the core's unique ascending frame/view table against those references as one fail-closed operation, verifies frame ID and dimensions, rejects duplicate references, independently bounds canonical colour and visibility-depth memory, and returns loader-owned Gray8, RGBA8 or canonical I420 bytes plus direct canonical depth buffers when archived. It never duplicates reference strings per triangle or silently substitutes a missing frame. `ReconstructionTextureSourceDecoder` bounds archived JPEG/PNG dimensions and RGBA allocation, verifies decoded dimensions and MIME type against frame metadata, and emits tightly packed top-left-origin RGBA8 with the original frame ID; Gray8 and canonical I420 continue to enter the core directly without a codec round trip. `ReconstructionTexturePngEncoder` converts the core's exact top-left-origin straight-alpha RGBA8 contract to Android ARGB pixels and losslessly compresses them as PNG. Pixel count, input size and encoded output are checked before an `EncodedReconstructionTexture` is returned. The portable exporter accepts only structurally valid, bounded PNG/JPEG payloads through `exportGlbWithEmbeddedTexture` (and its assessed variant), appends the encoded image as a GLB buffer view and creates the image/material relationship only after those encoded bytes exist. It rejects a simultaneous external texture URI, missing UVs, malformed image framing and encoded-byte budget overflow; raw atlas pixels alone still cannot create a texture reference.

`buildTextureAtlas` now composes the portable stages as one fail-closed transaction: calibrated visibility selection, coherent labels, deterministic layout, optional relative photometry, corrected pixel baking/padding, optional local seam blending and optional bounded global least-squares seam optimization. A truncated or invalid intermediate stage stops the graph and returns its exact stage/error telemetry without exposing a partial mesh or atlas; only `kComplete` carries seam-aware geometry and RGBA pixels, still with an empty texture URI. `ReconstructionTexturePipeline` exposes this transaction through a bounded JNI envelope, copies its successful mesh into direct native-order buffers, preserves exact stage/error telemetry on failure, and can feed the result directly to `ReconstructionTexturedAssetPublisher`. Candidates may carry aligned direct optical-axis depth, confidence and uncertainty buffers. Android verifies dimensions, strides, ranges and archived-frame timestamps before JNI; native code revalidates every direct-buffer range and borrows it only for the synchronized call. Requiring visibility depth rejects any candidate without it, while optional mode still never interprets absent or non-finite depth as free space. Individual stage APIs remain available for testing and higher-quality optional adapters.

`ReconstructionTexturedAssetPublisher` owns the final Android handoff once a seam-aware mesh and baked RGBA atlas exist. It requires direct native-order position/normal/UV/index buffers, preserves their positions, bounds mesh and output sizes, encodes the atlas to PNG, invokes the portable embedded-texture GLB exporter through JNI, and publishes only the successfully completed GLB. It does not invent UVs or upgrade quality evidence: this convenience path remains explicitly `unverified`, while assessed export still requires the separate held-out quality contract. Upstream view selection, atlas construction and baking remain core responsibilities.

`ReconstructionAssetArchive` publishes final GLBs as individual `AtomicFile`s in app-private no-backup storage. Session/asset identifiers and opaque `arkglb://` references are constrained, every load revalidates the GLB 2.0 header and exact JSON/BIN chunk framing, and per-asset/count/total-byte budgets evict the oldest generation deterministically. A failed write cannot expose a partial replacement, and the stored file remains directly consumable GLB rather than a private envelope. Atomicity and framing detect interrupted/truncated publication; they are not content authentication or encryption.

`ReconstructionTextureSourceLoader` now carries exact archived frame metadata with every canonical source, including an applied optimized pose, pose uncertainty and the pose-manifest generation. `ReconstructionTexturedSessionPipeline` joins these pieces into one bounded Android transaction: opaque archived references are resolved once with independent colour/depth budgets and strict frame-ID, timestamp, dimension and depth-companion provenance; the same calibrated depth and pose drive direct metric fusion; only a completely built fused source mesh enters the full texture graph; and only a `COMPLETE` atlas mesh with baked RGBA pixels proceeds to PNG encoding, embedded GLB export and atomic publication. The transaction returns exact stage/error and source-load, fusion, raw-mesh, texture and publication telemetry on failure without exposing a partial mesh, atlas or asset. Every accepted run retains per-frame provenance and uncertainty, and the resulting canonical GLB remains explicitly `unverified` because no held-out physical accuracy evidence is asserted. This completes one archived-frame/fused-mesh/texturing/publication path; it does not replace assessed export, a full Poisson/multiband seam solve, a calibrated camera-response model or an unconstrained dynamic edge-collapse QEM topology optimizer.

`ReconstructionSessionStateArchive` adds bounded durable multi-session state. Each `ARKSES01` envelope is an independent `AtomicFile` addressed by an opaque `arkses://` reference and records session/asset identity, strictly increasing generation, exact frame/pose/uncertainty provenance, complete or failed stage/error, source-load/fusion/mesh/texture telemetry and the final asset reference. Session, per-session generation and aggregate byte budgets evict deterministically; every load revalidates the version, CRC, string bounds, enum values and reference/generation provenance. No colour/depth pixels, dense trajectories or pose graphs are duplicated; only terminal per-frame pose/uncertainty provenance is retained. CRC detects accidental corruption, not tampering: this state archive remains CRC-only, while optional authenticated encryption applies separately to sensitive retained keyframes in `ReconstructionFrameArchive`. The archive accepts only the explicit `unverified` quality grade until an assessed export supplies separate held-out physical evidence.

`ArKernelFieldDiagnosticsBundle` adds an optional, host-owned canonical `ARKFDIAG1` text report for field diagnosis. It derives only bounded scalar summaries from an already validated terminal `ReconstructionSessionStateRecord`: exact stage/completion/error and texture-stage state, frame/fusion counts, byte budgets, truncation flags, mesh/atlas/asset counters and the 17 texture telemetry values. Error texts and the opaque asset reference are canonical lowercase hex UTF-8; optional capability/calibration SHA-256 labels and an independent-target reference remain opaque host data and are not verified here. A fixed key order, strict input/line budgets, canonical booleans/numbers/enums, fail-closed parsing and an explicit `unverified` grade keep the report deterministic. It performs no I/O, collection, redaction or upload; images, per-frame references/poses, calibration arrays, fusion trajectories, colour/depth pixels and dense geometry are never included. `ArKernelFieldDiagnosticsPolicyBundle` adds a bounded canonical `ARKFDPOL1` host policy contract with explicit opt-in, fixed fail-closed safety invariants, strict input/line budgets and a permanent `unverified` grade; it performs no collection, redaction, upload, signature verification or device validation. A bounded host diagnostic app now writes these text contracts after explicit opt-in; actual field collection/redaction/upload workflows, cryptographic signature verification, independent physical targets, device/rig evidence and centimeter validation remain open. `ArKernelFieldDiagnosticsRunLog` defines a bounded canonical `ARKFDLOG1` text-only run log gated by an opted-in `ARKFDPOL1` policy; it has fixed metadata/event order, strict budgets, monotonic opaque event tokens and an explicit `unverified` grade. It performs no I/O and never includes images, per-frame poses, trajectories, calibration arrays, pixels or dense geometry, so it can supply bounded runtime facts but not physical ground truth. Additional bounded catalog, guided-step, stillness, frame, storage, compute, thermal-soak, snapshot and `ARKFDPLAN1` plan-metadata kinds carry model-independent structural self-test scalars only; `ArKernelPhysicalSelfTestMetrics` derives them from finite/monotonic/gap/variance/CRC invariants without a device model, external target or assessed quality claim. `ArKernelFieldDiagnosticsTestPlan` adds a bounded canonical plan contract for the single installed diagnostic app: it selects seventeen implemented operators and bounded parameters as text, rejects unknown operators and unsafe invariants fail-closed, records a plan CRC-32 in the run log and stays `unverified`. It cannot add a new Android API or physical ground truth; a genuinely new API would require an update of the same app, not a new app object or repeated installs.

An optional bounded global least-squares seam optimization now shares one regularized per-patch RGB gain/offset across every sampled manifold seam correspondence and reports residual, iterations, constrained patches and pixel updates under explicit budgets; an optional measured quadric-error-guided clustering LOD refines only the delivery mesh. Neither is a full Poisson/multiband seam solve, a calibrated camera-response model or an unconstrained dynamic edge-collapse QEM topology optimizer; a separate bounded delivery-mesh QEM simplifier is now available. `ReconstructionFusionPipeline.buildSourceMesh()` now exposes the filtered metric source mesh directly as bounded native-order position/normal/index buffers, avoiding a GLB encode/decode round trip before texturing. The versioned JNI envelope returns no mesh unless fusion, meshing, component filtering, simplification, optional quadric LOD and optional edge-collapse QEM all completed without truncation; incomplete builds retain truncation flags and an explicit error. The source-mesh JNI path applies the same completeness gate but returns only source telemetry. A bounded session-level orchestration transaction and a durable multi-session state archive are now available. The dependency-free shelf packer is a deterministic fallback, not a utilization-quality claim equivalent to xatlas. OpenMVS confirms the useful separation between visibility-aware face/view quality, coherent labeling, seam leveling and atlas packing, but its AGPL implementation is not linked or copied. MIT-licensed xatlas remains a candidate for a separately reviewed higher-quality optional parameterization/packing adapter.

`optimizeTextureAtlasSeamsGlobally` now also offers opt-in deterministic holdout validation of its bounded per-patch gain/offset solve. With strict validation disabled, all sampled seam correspondences still train the existing least-squares solve, so current callers keep their measured behavior. With validation enabled, sample index `(i + 1) % holdout_stride == 0` is withheld; training RMSE, holdout maximum residual/RMSE and the holdout/training ratio are measured against explicit minimum-evidence and error gates. Stable `TextureGlobalSeamRejection` reasons and `textureGlobalSeamRejectionText` describe every rejection. A rejected estimate returns the exact input RGBA atlas with zero pixel updates and constrained patches; `buildTextureAtlas` stops at the seam-blending stage, clears partial mesh/pixels and retains the exact failing stage telemetry. This is a core-only config option, not a new C-API/JNI/Kotlin envelope. It is synthetic seam consistency evidence and remains `unverified`: not a full Poisson/multiband seam solve, calibrated camera-response model, physical ground truth, independent benchmark or reconstruction quality upgrade. Those larger items remain open.

A bounded native `estimateCameraResponseLinearity` now checks paired-exposure samples against a linear black-level response. It requires unique nonzero IDs, at most 64 and at least 8 samples, finite positive exposures, unsaturated normalized intensities, at least two distinct exposure-ratio bins, a measured low-intensity span, a robust black-level median, positive response slopes and residual/RMSE gates. Every rejection carries a stable reason; accepted estimates still report `unverified`. This is synthetic consistency evidence only: it does not calibrate a physical camera, estimate a nonlinear response, replace a full calibrated camera-response model, or upgrade reconstruction quality.

A separate bounded native `estimateCameraResponseCurve` estimates a monotonic piecewise-linear inverse-response LUT from paired-exposure observations. Each pair constrains two interpolated log-irradiance bins by `g(I_low) - g(I_high) = -log(E_high / E_low)`; bounded Kaczmarz updates, local smoothing and isotonic projection enforce the configured minimum slope. Budgets cover samples, bins, iterations, covered bins, convergence, residual, RMSE and a deterministic holdout residual/RMSE/ratio split. Invalid configurations throw `std::invalid_argument`; every rejected estimate has a stable reason and an empty LUT. Accepted estimates remain explicitly `unverified`. This standalone core estimator is not wired into the Android/JNI texture graph, is not physical camera calibration or a full calibrated camera-response model, and does not replace the full global Poisson/multiband seam solve, unconstrained dynamic QEM or an assessed quality upgrade.

Core exports:

- **glTF 2.0 (`.gltf` + `.bin` + PNG/JPEG)**: primary interchange/viewer format with indexed geometry, normals, UVs and PBR base colour;
- **GLB (`.glb`)**: single binary geometry/material container with bounded encoded PNG/JPEG buffer-view embedding for true one-file textured assets;
- **OBJ + MTL + texture**: broad DCC compatibility; units are documented as metres because OBJ has no authoritative unit field;
- **PLY**: inspection pipeline, currently ASCII triangles and normals.

Planned adapters include GLB, USDZ, untextured STL, LAS/LAZ/E57 and 3D Tiles. STL cannot preserve textures. Convert from the validated canonical mesh, never repeatedly from lossy exports.

## Viewer

`tools/viewer/index.html` is a dependency-free WebGL 2 viewer for the generated glTF subset. Select one `.glb`, or select `.gltf`, referenced `.bin` and optional texture together. It supports orbit, zoom, normals, indexed triangles and base-colour textures without uploads or network access. It also displays the embedded accuracy grade plus held-out observation count and maximum validated dimension; it does not infer or upgrade quality when metadata is absent. Unsupported component encodings are rejected.

An Android product viewer should be a separate optional module and load glTF/GLB through Filament `gltfio`; this keeps a renderer out of the headless core AAR. `ArKernelAssetViewerPlanBundle` adds only a bounded host-owned canonical `ARKVIEW1` inspection-plan envelope for such a future module: fixed metadata order, 1–16 views, opaque internal `arkglb://` references, exact view/color modes, bounded camera/frame values, four fail-closed safety flags and a pure structural summarizer. It loads no asset, initializes no renderer, imports no USDZ or external format, uploads nothing, performs no I/O, infers no quality and stays `unverified`; the viewer module itself remains host work.

## Research basis

- COLMAP separates Structure-from-Motion, global optimization and Multi-View Stereo; it is a final-reconstruction reference rather than an Android runtime dependency.
- OpenMVS implements dense reconstruction and separates projected visibility/face-view quality, coherent labeling, seam leveling and atlas packing; its AGPL code is a design reference, not a linked dependency.
- xatlas provides MIT-licensed mesh parameterization/UV unwrapping and is a candidate for a separately reviewed optional atlas adapter.
- Open3D provides references for registration, reconstruction, processing, visualization and PBR.
- Khronos glTF 2.0 is royalty-free and designed for efficient loading and broad tool interoperability.
- Filament is a mobile PBR renderer with Android and glTF/GLB support and belongs in an optional viewer adapter.
- Neural radiance fields help novel-view rendering but do not replace metric geometry and scale validation.

Every adopted dependency and transitive license requires review. The current exporter and browser viewer add no third-party runtime dependency.

## Physical calibration capture and the quality-upgrade pathway

A quality upgrade cannot be granted by a flag. `unverified`, `approximate` and
`centimeter-validated` remain outcomes of `assessReconstructionQuality`, which
always runs `evaluateHeldOutDimensions` and requires calibrated intrinsics and
camera/IMU extrinsics, independent metric scale, converged global optimization,
reprojection RMSE <= 1 px, metric-depth RMSE <= 0.01 m, scale standard deviation
<= 0.5%, observed coverage >= 70% for environments or 85% for objects, at least
three held-out observations across at least two 0.25 m dimension bins, raw mean
absolute error <= 1 cm, uncertainty-adjusted maximum absolute error <= 1 cm,
maximum relative error <= 2% and a largest reference dimension covering the
asset extent. These gates are not metrology certification.

`ArKernelCalibrationEvidenceAdapter` adds a bounded canonical `ARKCALIB1`
transport contract for the evidence that must feed those gates: twelve exact
metadata keys in order, six fail-closed invariants (explicit capture and pose
opt-in, no images, no automatic calibration, no automatic upload, permanent
`unverified`), 1–16 unique held-out independent scale observations with exact
`scale_bar` / `survey` / `sensor_ground_truth` provenance, canonical finite
decimals and bounded uncertainty, plus 0–32 bounded pose or orientation-only
samples with strictly increasing timestamps and unit quaternions. Strict
input/line budgets, BOM/CRLF tolerance, one optional final separator and a
fail-closed `END` reject malformed evidence deterministically. `summarize()`
computes only arithmetic on host-supplied numbers and a readiness pre-check;
`evaluationReady` is a transport sanity filter over a strict subset of the
gates above, **not** an assessment, **not** a measurement and **not** an
upgrade. `qualityGrade` stays `unverified` in every report.

The diagnostics app version `2.2-calibration` provides a separate, explicitly
opted-in calibration screen: the user performs the physical measurement,
enters an independently known reference length, the reconstruction-measured
length, reference uncertainty, provenance, held-out status and asset extent,
and may record a real device rotation-vector orientation sample. It writes a
bounded `ARKCALIB1_<run>.txt` text file and shares it for host-side evaluation.
Orientation samples carry no metric world position and cannot establish scale;
an orientation-only sample must assert zero translation. There is no universal
calibration valid for every device: calibration is per device and per capture
and needs real independent targets. The app captures no image, pixel, frame or
dense geometry, infers no intrinsics or extrinsics, performs no automatic
calibration, verifies no signature, uploads nothing and grants no quality
grade. No upgrade was performed or claimed here; real field evidence,
independent targets and centimetre validation remain open.

## Device-adaptive onboarding, not universal calibration

`ArKernelDeviceAdaptiveProfileAdapter` adds a bounded canonical `ARKADAPT1`
profile that adapts a **systematic procedure** to host-read device facts:
Camera2 hardware level, camera count, gyroscope and rotation-vector presence,
thermal monitoring availability, intrinsics trust, RAM class and API level.
`selectProfile()` is deterministic and repeatable; unknown or weak devices
receive the most conservative step list with fewer scale-bar checks. Fifteen
exact metadata keys in order, five fail-closed invariants (no images,
automatic profile selection, metric calibration requires the user, physical
target required, no automatic upload), 1–16 unique contiguous steps with exact
operators and host-reported statuses, strict input/line budgets, BOM/CRLF
tolerance and a fail-closed `END` reject malformed profiles deterministically.
`summarize()` reports structural counts only; `profileComplete` is a
completeness pre-check, **not** an assessment, **not** a measurement and
**not** an upgrade. `qualityGrade` stays `unverified`.

Diagnostics app version `2.3-adaptive` adds an onboarding screen that reads
those facts on every start, selects the profile automatically and guides the
user through the steps with explicit confirmations, writing an
`ARKADAPT1_<run>.txt` file. Android provides no install-time calibration
hook, no calibration can be derived without independent physical targets, and
there is no universal calibration valid for every device model: the portable
part is the protocol, while calibration values remain per device and per
capture. A present `LENS_INTRINSIC_CALIBRATION` field is deliberately not
treated as a trusted profile because the screen verifies no signature. The
screen captures no images, pixels, poses or trajectories, infers no
intrinsics, extrinsics or metric scale, performs no automatic calibration,
verifies no signature, uploads nothing, performs no I/O in the kernel and
grants no quality grade. The authoritative grade remains
`assessReconstructionQuality` with `evaluateHeldOutDimensions`. No upgrade was
performed or claimed here; real field evidence, independent targets and
centimetre validation remain open.

## Systematic portable measurement campaign, not a universal calibration

`ArKernelCampaignPlanAdapter` adds a bounded canonical `ARKCAMPAIGN1` plan
with seventeen exact metadata keys, eight fail-closed invariants, 1–16 unique
scenes across nine fixed scene kinds, repeat budgets and exactly ten canonical
systematic questions with fixed evidence depths. `canonicalPlan()` derives the
scene list from the `ARKADAPT1` device class: the portable protocol is
identical on every Android device, while weaker or unknown devices receive
fewer scenes and explicitly report unsupported questions. `summarize()`
computes structural counts only; `campaignEvidenceComplete` means a host
reported completed scenes of the required kinds and depths plus confirmed
questions. It is **not** an answer, **not** accuracy, **not** centimetre
validation and **not** an upgrade. `qualityGrade` stays `unverified`.

Diagnostics app version `2.4-campaign` adds a campaign screen that reads the
device class, builds the canonical plan, guides explicit scene completions and
question confirmations and writes an `ARKCAMPAIGN1_<run>.txt` file. The screen
captures no images, pixels, poses or trajectories, measures no physical
target, infers no intrinsics, extrinsics or metric scale, performs no
automatic or universal calibration, verifies no signature, uploads nothing,
performs no I/O in the kernel and grants no quality grade. A tape measure, an
independent held-out target and real field measurements remain host work. The
authoritative grade remains `assessReconstructionQuality` with
`evaluateHeldOutDimensions`. No upgrade was performed or claimed here.

## Fail-closed session binding across the three field artifacts

`ArKernelSessionBindingAdapter` adds a bounded canonical `ARKBIND1` envelope
with nineteen exact metadata keys, six fail-closed invariants and three
lowercase 64-character SHA-256 digests. `create()` parses host-supplied
`ARKADAPT1`, `ARKCALIB1` and `ARKCAMPAIGN1` texts, re-encodes each to its
canonical form before hashing (so BOM/CRLF presentation variants bind
identically), requires one identical device token and one identical device
class and records bounded scene/question/observation/pose counts. `verify()`
recomputes digests, tokens, campaign ID, counts and class, then reports only
structural consistency plus the existing `evaluationReady` and
`campaignEvidenceComplete` pre-checks. `bindingConsistent` is **not** an
answer, **not** accuracy, **not** centimetre validation and **not** an
upgrade. `qualityGrade` stays `unverified`.

Diagnostics app version `2.5-binding` adds an opt-in screen that pastes the
three text files, binds them and writes an `ARKBIND1_<run>.txt` file. Nothing
is uploaded automatically, no signature is verified, no image, pixel, pose or
trajectory is captured, no physical target is measured, no intrinsic,
extrinsic or metric scale is inferred, no automatic/universal calibration is
performed, no I/O occurs in the kernel and no quality grade is granted. Real
independent targets, manual redaction/upload, cryptographic signature
verification and physical validation remain host work. The authoritative grade
remains `assessReconstructionQuality` with `evaluateHeldOutDimensions`. No
upgrade was performed or claimed here.

## Bounded cross-device comparison of declared sessions

`ArKernelCrossDeviceComparisonAdapter` adds a bounded canonical `ARKCROSS1`
envelope with fourteen exact metadata keys, eight fail-closed invariants and
1–16 unique session rows. `create()` parses host-supplied `ARKBIND1` envelopes,
re-encodes each to its canonical form, hashes those bytes with SHA-256 and
requires unique device tokens and unique binding digests. `verify()` recomputes
digests and counts; `summarize()` reports only structural counts such as
distinct devices, device classes, campaigns and scene/observation ranges.
`comparisonConsistent` is **not** an answer, **not** accuracy, **not**
centimetre validation and **not** an upgrade. `qualityGrade` stays
`unverified`.

Crucially this contract binds **declared** session envelopes; it does not
re-run `ARKBIND1.verify()` against the three underlying source texts, which is
reported explicitly as `sourceReverificationPerformed=false`. The portable
part is the ten-question protocol, not a calibration value:
`per_device_calibration` and `universal_calibration_disabled` are mandatory.
Diagnostics app version `2.6-cross` pastes 1–16 binding texts opt-in and
writes an `ARKCROSS1_<run>.txt` file. Nothing is uploaded automatically, no
signature is verified, no image, pixel, pose or trajectory is captured, no
physical target is measured, no automatic/universal calibration is performed,
no file I/O occurs in the kernel and no quality grade is granted. Real
independent targets, manual redaction/upload, cryptographic signature
verification and physical validation remain host work. The authoritative grade
remains `assessReconstructionQuality` with `evaluateHeldOutDimensions`. No
upgrade was performed or claimed here.

## Bounded real-View retention UX record

`ArKernelRetentionRunLedgerAdapter` adds a bounded canonical `ARKRETENT1`
envelope with fourteen exact metadata keys, three fail-closed invariants
(explicit confirmation required, dry run, physical-device validation disabled),
three retention modes, three preview action kinds, strict input/line budgets,
BOM/CRLF tolerance and fail-closed `END` parsing. `summarize()` reports only
structural facts; the grade stays `unverified`.

Diagnostics app version `2.7-retention` adds a real Android View screen around
the existing `ArKernelRetentionUiComposition`: one localized `AlertDialog`,
explicit publication/release previews, a dry-run sink that deletes nothing,
lifecycle state restoration, navigation back to the overview,
`contentDescription` labels and `announceForAccessibility` announcements. It
writes an opted-in `ARKRETENT1_<run>.txt` file. No frame, key or storage is
touched, no image, pixel, pose or trajectory is captured, no physical target is
measured, no automatic/universal calibration is performed, no signature is
verified, nothing is uploaded automatically and no quality grade is granted.
Physical encrypted keyframe lifecycle validation on real devices remains open.
The authoritative grade remains `assessReconstructionQuality` with
`evaluateHeldOutDimensions`. No upgrade was performed or claimed here.

## Bounded host-side structural review of field diagnostics triples

`tools/field_review/verify_arkfd_triple.py` adds a bounded canonical
`ARKFDREVIEW1` envelope. It re-parses one `ARKFDPOL1` policy, one
`ARKFDPLAN1` plan and one `ARKFDLOG1` run log through an independent replica of
the shipped Kotlin contracts, canonicalizes all three texts, recomputes their
SHA-256 digests and the plan CRC-32, and checks structural bindings only:
policy ID equality, plan CRC binding (`crc_bound`, `crc_mismatch` or
`unbound`), plan step/event counts and the opaque host-reported plan result
token. Twenty-six exact metadata keys and six mandatory invariant flags keep
the result fail-closed; `source_reverification_performed=true` means the three
source texts were re-parsed, not that any signature was checked.

The tool reads three host-supplied files, writes nothing unless `--out` is
given explicitly. A deterministic `--batch DIR` mode groups 1–16 complete
triples by opaque filename suffix and emits a structural comparison as
JSON (digests, binding states, counts, result tokens, uniqueness flags),
rejecting incomplete groups fail-closed and emitting no device identifiers,
session IDs or raw event details. It performs no upload, no redaction, no
signature verification, no physical target measurement, no calibration, no
encrypted keyframe lifecycle validation and no quality assessment. Raw logs
are never copied into the repository; `docs/FIELD_RUN_REVIEW.md` records
digests only. The grade
stays `unverified`. The authoritative quality path remains
`assessReconstructionQuality` with `evaluateHeldOutDimensions`; no upgrade was
performed or claimed here.

## Bounded guided measurement execution

`ArKernelMeasurementAdapter` adds a bounded canonical `ARKMEAS1` ledger with
twenty exact metadata keys, eleven mandatory fail-closed invariants and 1–16
unique scene rows. Each row binds one host-supplied `ARKCALIB1` envelope by the
lowercase SHA-256 digest of its canonical re-encoding, carries one of the nine
canonical campaign scene kinds, one of six environmental conditions, an
explicit user confirmation and an optional held-out flag. `create()` requires
unique scene IDs, unique digests, confirmation of every scene and at least one
held-out scene. `summarize()` reports structural counts only and
`evidenceComplete` is a completeness fact, not an answer, accuracy judgement or
grade. `verify()` is structural and deliberately does not re-parse the source
texts (`sourceReverificationPerformed=false`).

Diagnostics app version `2.8-measure` adds a guided nine-scene screen that
takes 1–9 physical `ARKCALIB1` texts opt-in and writes `ARKMEAS1_<run>.txt`.
It performs no measurement itself, captures no image, pixel, frame, pose or
trajectory, computes no accuracy, performs no automatic or universal
calibration, verifies no signature, uploads nothing and grants no quality
grade. **No real AI model, no inference, no weights and no network are
included**: a model could only serve as a versioned conformance admission
test, never as a substitute for an independent physical reference. Physical
validation and centimetre accuracy remain open host work. The authoritative
grade remains `assessReconstructionQuality` with `evaluateHeldOutDimensions`;
no upgrade was performed or claimed here.

## Bounded live camera measurement feed

`ArKernelMeasurementFeedAdapter` adds a bounded canonical `ARKFEED1` record
with twenty-seven exact metadata keys, eleven mandatory fail-closed
invariants, four ordered marker rows and a self-consistent screen-ratio
geometry. Each capture binds four user-placed overlay markers, one
independently measured reference length and the counted preview frames to a
single record. `frames_exported` is always zero and
`metric_accuracy_claimed` is always false: `estimated_target_m` is a
screen-ratio estimate that assumes a fronto-parallel plane at equal depth,
not a metric measurement. `parse()` re-checks derived distances, scale and
estimate with a small numeric tolerance; `summarize()` reports structural
completeness only.

Diagnostics app version `2.9-feed` adds an opt-in Camera2 rear preview with
a marker overlay and writes `ARKFEED1_<run>_<index>.txt`. It exports no
frame, image, pixel, pose or trajectory, computes no metric accuracy,
performs no automatic or universal calibration, verifies no signature,
uploads nothing and grants no quality grade. **No real AI model, inference,
weights or network are included**: a model could only act as a versioned
conformance admission test, never as a substitute for an independent
physical reference. Physical validation, centimetre accuracy and a real
depth/ARCore-equivalent measurement remain open. The authoritative grade
remains `assessReconstructionQuality` with `evaluateHeldOutDimensions`; no
upgrade was performed or claimed here.

## Bounded automatic folding-ruler recognition

`ArKernelRulerScaleAdapter` adds a bounded canonical `ARKSCALE1` record and
`ArKernelRulerTickDetector` a pure, deterministic tick detector. The app
samples luma along the roughly indicated reference line from a preview frame
held in memory, finds the dark centimetre marks with integer thresholds and
snaps the reference endpoints onto the nearest detected tick. The frame is
discarded immediately: `frames_exported=0` and
`frame_persistence_disabled=true`.

Thirty-five exact metadata keys, thirteen fail-closed invariants, four marker
rows and 2–200 ordered tick rows are enforced. `expected_tick_count` is
derived from the length the user states they folded out (100 per metre), so
`tick_count_consistent` exposes a wrong ruler, poor illumination or a wrongly
entered length. `tick_regularity` is a structural property of the detected
pattern, not an accuracy judgement.

This improves **endpoint repeatability**, not metric accuracy: depth,
parallax, perspective, rolling shutter and lens distortion are not modelled,
so `metric_accuracy_claimed=false` and `estimated_target_m` remains a
screen-ratio estimate assuming ruler and target share one fronto-parallel
plane at equal depth. Diagnostics app version `2.10-ruler` adds button 13 and
also fixes the missing rotation transform on the `2.9-feed` preview. **No real
AI model, inference, weights or network are included**: only fixed,
deterministic luma thresholds. Physical validation and centimetre accuracy
remain open. The authoritative grade remains `assessReconstructionQuality`
with `evaluateHeldOutDimensions`; no upgrade was performed or claimed here.

## Usability pass: system bars, sections and a test checklist

Diagnostics app version `2.11-guide` is a usability-only change: no contract,
no measurement path, no inference and no quality claim were added or altered.

Android 15 enforces edge-to-edge for apps targeting API 35, so the navigation
bar (Home / Back / Recents) covered the last button on every screen and the
status bar covered the first. `DiagnosticsActivity` opts into drawing behind
the system bars and then re-applies the system-bar insets as padding on the
content view, capturing each screen's original padding once so the insets are
recomputed rather than accumulated on rotation, keyboard or navigation-mode
changes. All ten activities inherit it.

The main screen now groups its thirteen buttons into three named sections and
adds a collapsible checklist giving the full test order across
`ARKFDPOL1`/`ARKFDPLAN1`/`ARKFDLOG1`, `ARKADAPT1`, `ARKCAMPAIGN1`,
`ARKCALIB1`, `ARKSCALE1`, `ARKFEED1`, `ARKMEAS1`, `ARKBIND1`, `ARKRETENT1`
and `ARKCROSS1`. The checklist is a manual guide, not a verification: the app
does not inspect which files exist, evaluates no measurement and grants no
approval. The grade stays `unverified`.
