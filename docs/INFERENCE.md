# On-device inference SPI and scheduling

The Android library defines an inference contract but does not silently download, bundle, or claim a particular model. LiteRT, ONNX Runtime, MediaPipe, or application-specific engines implement `OnDeviceInferenceBackend` and remain separately licensed/adapted components.

## Model manifest

Every backend exposes a validated `ModelManifest` with schema version, stable model/version IDs, lowercase SHA-256, SPDX license identifier, HTTPS source, explicit tasks, tensor dimensions/channels, normalization vectors, quantization (including mandatory scale and zero point for INT8/UINT8), byte size, labels, metric-depth conversion, and measured device profiles. Semantic manifests must define class `0` (Unknown). Label IDs are unsigned 32-bit values. Metric and relative depth are mutually exclusive; only a metric-depth manifest may declare `depthUnitToMeters` or feed the spatial map. The backend must apply that declared conversion before returning the metre-valued `MetricDepthImage`/`MetricDepthBuffer` contract.

A manifest documents a build; it does not prove a license by itself. `verifyModelBytes` hashes the caller buffer's remaining bytes with SHA-256 without changing its position and checks both declared byte size and digest; packaging/loading code must call it for the actual model and still ship required notices.

## Frame and ownership contract

`PerceptionFrame.wrap` captures a read-only slice of a direct image buffer, exact camera calibration, pixel format/row stride, timestamp, and the frame-associated world pose. Dimensions, visible byte count, and timestamp equality are checked. The underlying memory remains host-owned and must stay alive and immutable until the result/error callback. A backend may resize or normalize into its own tensor according to its manifest, but may not replace the associated pose.

## Shared native tensor preprocessing

`ArKernelTensorPreprocessor` gives backend adapters a dependency-free, deterministic path from a direct `GRAY8`, `RGB888`, or `RGBA8888` frame to a direct NHWC tensor. It performs half-pixel bilinear resize, Rec. 601 grayscale conversion, optional red/blue swap, `(pixel - mean) / standardDeviation`, and FLOAT32, INT8, or UINT8 encoding. Quantized values use `round(normalized / scale) + zeroPoint` and saturate to the destination range. The native core also exposes the same operation through `tensor_preprocessor.h` and the versioned C ABI.

The implementation validates row stride and visible input extent, finite normalization, quantization parameters/zero-point range, output shape, direct-buffer offsets/capacity, and a hard output-element budget (16 million on Android). It supports one- or three-channel output; FLOAT16 and four-channel model input remain adapter-specific. `preprocessInto` writes at the caller buffer's current position without advancing either buffer. `allocateAndPreprocess` is the bounded convenience path; latency-sensitive adapters should reuse a direct output buffer.

## Metric-depth tensor output

`ArKernelDepthPostprocessor` converts a direct one-channel FLOAT32, INT8, or UINT8 output tensor into camera-sized `MetricDepthBuffer` depth and confidence. It supports direct depth and explicitly declared inverse-depth conversion, applies unit-to-metre scaling, rejects non-finite/out-of-range samples, and uses invalid-aware half-pixel bilinear resizing with a configurable relative discontinuity gate that conservatively preserves the nearer surface at depth edges. A valid-neighbour weight of zero yields unknown depth (`0`); the accumulated valid interpolation weight becomes confidence. Input/output buffer positions are not changed, row padding and quantization are explicit, and output remains capped at 16 million pixels. The same operation is exposed by `depth_postprocessor.h` and the versioned C ABI.

Adapters must supply the actual output-tensor encoding and transform from the model's documented signature. They must not guess that relative monocular output is metric. Only a model manifest declaring `METRIC_DEPTH`, with a verified conversion, may route this result into spatial fusion.

## Runtime composition

`SingleInputTensorRuntime` is the narrow dependency boundary for LiteRT, ONNX Runtime, NNAPI, or a host engine: one direct native-order input, one direct native-order output, serialized execution, exact written-byte count, and no buffer retention. `MetricDepthTensorBackend.createVerified` composes this runtime with the shared preprocessor and depth postprocessor. `SemanticDetectionTensorBackend.createVerified` uses the same bounded composition for explicit row-based detection outputs. For genuine multi-head models, `MultiOutputTensorRuntime` and `DepthAndDetectionTensorBackend.createVerified` execute preprocessing and the model exactly once, then decode output index 0 as depth and index 1 as detections tied to the same frame pose and timestamp. Boxes whose manifest-known class is configured dynamic conservatively zero both depth and confidence inside a padded image-space mask before the result leaves the backend; mask union work is capped (default 4,194,304 pixels). This prevents people or moving objects from being fused as static surfels, while acknowledging that a coarse box can also suppress static background. Construction first checks the exact model bytes against the manifest SHA-256 and size, then allocates one reusable input tensor and one reusable raw-output tensor, each capped at 64 MiB. Every inference preserves the frame timestamp and pose and rejects partial runtime output. The depth composition converts only a manifest-declared metric-depth model and returns camera-sized direct metric depth/confidence; the detection composition accepts only a manifest-declared semantic model and returns bounded manifest-known boxes for the normal scheduler and mapping gate.

Detection decoding supports explicitly configured XYXY or center-XYWH columns, normalized or pixel coordinates, FLOAT32/INT8/UINT8 tensors, confidence filtering, clipping, deterministic class-aware or class-agnostic NMS, at most 4,096 candidates, and at most 256 results. Class IDs must be integral and present in the manifest. Backend configuration can mark known classes dynamic and request floor/wall/any plane projection; it does not invent a 3D position from a box.

The runtime adapter remains responsible for binding the correct model input/output indices. `VerifiedInferenceBackendAdmission.createAndSelect` is the runtime-neutral selection boundary: it verifies the artifact once, gives CPU and optional delegate factories independent read-only views of exactly those remaining bytes, verifies the CPU backend's complete manifest/task identity, and transfers both complete owners to the bounded decoded semantic selector. A missing delegate factory produces explicit `CPU_ONLY`; delegate construction failure retains CPU; and a constructed delegate is returned only after identity plus golden detection/metric-depth conformance. The source model buffer position is unchanged, and a factory cannot mutate bytes seen by the other factory. Factory implementations remain responsible for cleaning internal partial construction before throwing; once a complete backend is returned, admission owns it.

`InferenceAdmissionEvidenceStore` can atomically persist only an accepted delegate report in app-private no-backup storage. The entry is keyed by a canonical SHA-256 over every manifest field, the exact capability signature (including hashed build identity), calibration-profile SHA-256, adapter ID/version, software revision, policy revision, and a maximum 30-day expiry. CRC detects accidental corruption. Any mismatch, expiry, malformed file, rejection, CPU-only result, or delegate-construction failure is a cache miss and cannot be converted into successful evidence. This cache avoids treating a model SHA alone as sufficient identity and is not device-farm accuracy evidence; applications should still retain runtime health/failure fallback.

In the combined backend the order is an explicit contract: depth first, detections second; both exact byte counts are checked. Each raw tensor is capped at 64 MiB and combined raw outputs at 96 MiB. The adapter receives model bytes only during factory creation and must own any state required afterward. The backend owns and closes the runtime exactly once. This provides a usable end-to-end depth backend without forcing an ML runtime dependency or pretending that all engines share a delegate/device policy.

## Temporal depth backend decorator

`TemporalDepthInferenceBackend` owns and decorates any manifest-declared metric-depth backend. It verifies that the delegate preserves the exact inference-frame pose, timestamp, and calibrated dimensions, converts the legacy array depth form to a direct contiguous buffer when necessary, applies `TemporalMetricDepthFilter`, and passes detections through unchanged. `lastFilterResult` exposes blend/rejection telemetry, `resetHistory` handles explicit discontinuities, and closing the decorator closes both filter and delegate exactly once. Wrapping the backend before constructing `BoundedInferenceScheduler` therefore places conservative pose-compensated filtering in the normal inference path rather than relying on application-side ordering.

## Optional detection tracking

`TrackedInferenceBackend` decorates any semantic backend with `BoundedSemanticDetectionTracker`. Detections that already carry a non-negative model track ID pass through unchanged; untracked detections receive deterministic class-aware IoU IDs from a native tracker capped at 256 active tracks, five missed frames by default, and a one-second maximum frame gap. This improves downstream 3D association without presenting IoU matching as object recognition or long-term re-identification. Track state can be reset explicitly and is closed with the delegate.

## Owned perception pipeline

`ArKernelPerceptionPipeline.create` assembles the safe default order around any backend: optional bounded detection tracking, then optional pose-compensated temporal depth, then adaptive single-flight scheduling, and finally the optional Android thermal monitor. Dynamic masking remains inside the combined backend and therefore occurs before temporal filtering. The pipeline owns the entire chain, provides submit and submit-plus-session-integration entry points, exposes combined cadence/temporal telemetry, resets tracker and depth history together, and closes each owned component once. Callbacks still run on the inference worker; no dispatcher or hidden queue is introduced. Features activate only when the manifest declares their task, and applications can disable each decorator explicitly.

## Bounded scheduler

`BoundedInferenceScheduler` owns one worker and permits exactly one in-flight request with no hidden queue. Submissions report `ACCEPTED`, `RATE_LIMITED`, `THERMAL_PAUSED`, `BUSY`, `OUT_OF_ORDER`, or `CLOSED`. The default fixed minimum interval is 100 ms (10 Hz). This explicit dropping policy prevents camera-rate input from growing latency and memory without bound. Frames must be monotonic. Backend calls are serialized, callback execution occurs on the inference worker, and close safely defers backend destruction until an in-flight call returns.

An optional bounded `InferenceCadenceController` measures backend-plus-validation latency (not callback time), keeps a 64-sample p95 and EWMA, and raises the effective interval to respect a configurable target duty cycle. `WARM` and `HOT` multiply that interval; `CRITICAL` returns `THERMAL_PAUSED` without accepting work. Repeated failures back off by a bounded factor. Snapshots expose sample count, EWMA, p95, recommendation, failure streak, and pause state.

On Android API 29+, `AndroidInferenceThermalMonitor` can lifecycle-safely register `PowerManager.OnThermalStatusChangedListener` and feed the scheduler. NONE maps to `NORMAL`, LIGHT/MODERATE to `WARM`, SEVERE to `HOT`, and CRITICAL/EMERGENCY/SHUTDOWN to `CRITICAL`; unknown future values conservatively map to `HOT`. API 26–28 use `NORMAL` because the platform callback does not exist. The API-29 references are isolated behind a runtime-gated helper to keep older-device class loading safe. The host still owns monitor lifetime and may instead supply product-specific thermal policy directly.

Outputs are checked against the backend manifest and source frame: exact timestamp/pose, at most 256 detections, known class IDs (or Unknown), in-image boxes/anchor pixels, matching calibrated metric-depth dimensions, and declared task capability. Relative depth cannot appear as `MetricDepthImage`.

## Session integration

`ArKernelSession.integrateInferenceOutput` routes only validated products:

- metric `FloatArray` or direct `FloatBuffer` depth through calibrated sampling and the mapping gate;
- semantic detections through bounded plane projection using the exact inference-frame pose.

A 2D detection that has no requested observed surface produces a semantic miss, not an invented 3D object. Mapping pause/recovery can also reject delayed outputs. The integration result reports depth update plus semantic updates/misses.

## Still adapter-specific

`ArKernelClassMaskPostprocessor` provides the bounded generic part of segmentation handling for models that already emit a one-channel class-index tensor. It accepts direct FLOAT32/INT8/UINT8 tensors with explicit quantization and row stride, nearest-resizes to camera depth, and zeros depth/confidence for a manifest-owned set of dynamic class IDs while preserving all buffer positions. Input dimensions are capped at 4096, output at 16 million pixels, dynamic classes at 256, and masked writes at a caller budget (default 4,194,304). The full mask is validated before depth is mutated.

This SPI does not implement model-specific YOLO/SSD score fusion, logits/argmax or instance-mask decoding, FLOAT16/four-channel tensor encoding, GPU delegates, NNAPI selection, model download/update, signature verification, or device benchmark collection. Models whose boxes are not already explicit one-row-per-box records, or whose masks are not class-index tensors, need a small model-specific adapter before the shared postprocessors. Those belong in explicit backend modules with their own dependencies and license notices. The core remains usable without any ML runtime.
