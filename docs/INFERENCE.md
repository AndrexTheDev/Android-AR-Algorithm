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

## Bounded scheduler

`BoundedInferenceScheduler` owns one worker and permits exactly one in-flight request with no hidden queue. Submissions report `ACCEPTED`, `RATE_LIMITED`, `BUSY`, `OUT_OF_ORDER`, or `CLOSED`. The default minimum interval is 100 ms (10 Hz). This explicit dropping policy prevents camera-rate input from growing latency and memory without bound. Frames must be monotonic. Backend calls are serialized, callback execution occurs on the inference worker, and close safely defers backend destruction until an in-flight call returns.

Outputs are checked against the backend manifest and source frame: exact timestamp/pose, at most 256 detections, known class IDs (or Unknown), in-image boxes/anchor pixels, matching calibrated metric-depth dimensions, and declared task capability. Relative depth cannot appear as `MetricDepthImage`.

## Session integration

`ArKernelSession.integrateInferenceOutput` routes only validated products:

- metric `FloatArray` or direct `FloatBuffer` depth through calibrated sampling and the mapping gate;
- semantic detections through bounded plane projection using the exact inference-frame pose.

A 2D detection that has no requested observed surface produces a semantic miss, not an invented 3D object. Mapping pause/recovery can also reject delayed outputs. The integration result reports depth update plus semantic updates/misses.

## Still adapter-specific

This SPI does not implement model-specific postprocessing, FLOAT16/four-channel tensor encoding, GPU delegates, NNAPI selection, model download/update, signature verification, segmentation-mask fusion, or device benchmark collection. Those belong in explicit backend modules with their own dependencies and license notices. The core remains usable without any ML runtime.
