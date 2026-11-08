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

`SingleInputTensorRuntime` is the narrow dependency boundary for LiteRT, ONNX Runtime, NNAPI, or a host engine: one direct native-order input, one direct native-order output, serialized execution, exact written-byte count, and no buffer retention. `MetricDepthTensorBackend.createVerified` composes this runtime with the shared preprocessor and depth postprocessor. Construction first checks the exact model bytes against the manifest SHA-256 and size, then allocates one reusable input tensor and one reusable raw-output tensor, each capped at 64 MiB. Every inference preserves the frame timestamp and pose, rejects partial runtime output, converts only a manifest-declared metric-depth model, and returns camera-sized direct metric depth/confidence for the normal scheduler and mapping gate.

The runtime adapter remains responsible for selecting delegates and binding the correct model input/output indices. It receives model bytes only during factory creation and must own any state required afterward. The backend owns and closes the runtime exactly once. This provides a usable end-to-end depth backend without forcing an ML runtime dependency or pretending that all engines share a delegate/device policy.

## Bounded scheduler

`BoundedInferenceScheduler` owns one worker and permits exactly one in-flight request with no hidden queue. Submissions report `ACCEPTED`, `RATE_LIMITED`, `BUSY`, `OUT_OF_ORDER`, or `CLOSED`. The default minimum interval is 100 ms (10 Hz). This explicit dropping policy prevents camera-rate input from growing latency and memory without bound. Frames must be monotonic. Backend calls are serialized, callback execution occurs on the inference worker, and close safely defers backend destruction until an in-flight call returns.

Outputs are checked against the backend manifest and source frame: exact timestamp/pose, at most 256 detections, known class IDs (or Unknown), in-image boxes/anchor pixels, matching calibrated metric-depth dimensions, and declared task capability. Relative depth cannot appear as `MetricDepthImage`.

## Session integration

`ArKernelSession.integrateInferenceOutput` routes only validated products:

- metric `FloatArray` or direct `FloatBuffer` depth through calibrated sampling and the mapping gate;
- semantic detections through bounded plane projection using the exact inference-frame pose.

A 2D detection that has no requested observed surface produces a semantic miss, not an invented 3D object. Mapping pause/recovery can also reject delayed outputs. The integration result reports depth update plus semantic updates/misses.

## Still adapter-specific

This SPI does not implement model-specific detection/segmentation decoding, FLOAT16/four-channel tensor encoding, GPU delegates, NNAPI selection, model download/update, signature verification, segmentation-mask fusion, or device benchmark collection. Those belong in explicit backend modules with their own dependencies and license notices. The core remains usable without any ML runtime.
