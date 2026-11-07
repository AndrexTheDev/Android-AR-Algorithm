# Depth and bounded surfel fusion

The core does not require LiDAR and does not assume a particular depth model. Stereo, monocular metric-depth adapters, structured-light/ToF sensors, imported point clouds, or future ARCore depth adapters may submit calibrated camera-space samples through the same contract.

## Observation contract

Each sample contains a positive-depth metric point in the optical camera frame, confidence, radius, frame timestamp, and an optional camera-frame unit normal. The exact `world_from_camera` pose associated with that depth frame is supplied separately. JNI/native code rotates points and normals into the current world frame; timestamps must match, so delayed inference cannot silently use the latest pose.

Adapters must establish metric scale and calibration. Relative monocular depth values, disparity values, raw 16-bit sensor units, and image pixels are not metric 3D observations until converted by the adapter. `ArKernelDepthPostprocessor` provides a bounded conversion primitive for documented FLOAT32/INT8/UINT8 direct-depth or inverse-depth tensors: it dequantizes, applies the declared unit conversion, rejects invalid/range-violating samples, and invalid-aware resizes to camera resolution while producing interpolation-validity confidence. It does not make relative output metric. A surfel batch is limited to 100,000 observations.

## Aligned metric depth images

`sampleMetricDepthImage` and Android `integrateMetricDepthImage` provide the efficient dense path. Input is an optical-axis Z-depth `FloatArray` in metres, aligned to the exact calibrated camera width and height, with an explicit row stride and optional equally aligned confidence plane. Invalid, non-finite, out-of-range, or low-confidence pixels are skipped and never filled. Configurable pixel-stride sampling bounds CPU and memory before JNI creates surfel records.

Android provides both `MetricDepthImage` (`FloatArray`, convenient but copied at JNI) and `MetricDepthBuffer` (direct native-order `FloatBuffer`). The direct path passes the buffer's current element position and remaining element count to JNI, validates native capacity, offset, alignment, stride, and optional confidence storage, then samples synchronously without a full depth-array copy. The host must keep both direct buffers alive and immutable until the call returns; positions are not advanced by the core.

Each accepted pixel is distortion-aware unprojected. Radius is derived from depth, focal length, and sampling stride. A local normal is estimated from right/down sampled neighbours only when both depths remain within a 10% discontinuity gate; depth edges therefore remain points without fabricated cross-edge normals. Output is capped at 100,000 samples and then follows the same frame-pose transform and SurfelMap fusion path.

## Fusion and publication

`SurfelMap` quantizes world points into 5 cm voxels. Position, radius, confidence, and optional normal are confidence-weighted. A voxel needs two observations before publication. Defaults enforce confidence `>= 0.20`, radii from 2 mm to 50 cm, world-position norm up to 1 km, a 30-second lifetime, and a hard 50,000-surfel budget. Out-of-order observations within a voxel are rejected. Accumulated weights are bounded so old data cannot overflow or permanently prevent adaptation.

When full, deterministic eviction prefers unconfirmed, weakly observed, low-confidence, old surfels. IDs and query order are stable for the lifetime of an entry. Reset clears all surfels.

## Bounded surfel raycasts

`SurfelMap::raycast`, the C ABI, and Android `raycastSurfel` return only the nearest hit among confirmed entries and enforce finite origins/directions plus explicit minimum and maximum distance. Surfels with reliable normals are intersected as finite two-sided discs using their fused radius. Surfels without normals are conservatively intersected as spheres whose collision radius is capped to half a voxel, even when their sampling footprint is larger; the returned radial normal is marked as approximate through `normal_valid = false`. Parallel rays, misses outside a disc radius, unconfirmed entries, and out-of-range intersections are ignored. Stable Surfel ID and confidence are returned with the world-space hit.

Apps must not treat a sparse ray miss as proof that free space is observed.

## Screen-space metric depth

`SurfelMap::renderDepth`, the C ABI, and Android `renderSurfelDepth` rasterize confirmed surfels with reliable normals into a camera-aligned optical Z-depth image. For every candidate pixel the implementation distortion-aware unprojects a ray, intersects the actual finite surfel disc, rejects points outside its radius or configured depth interval, and retains the nearest Z value. It does not merely splat a center point. Surfels without reliable normals are omitted from this occlusion path because approximate spheres would create false screen-space occluders.

Depth `0` means unknown, never measured free space. Defaults cap output to 4 million pixels, each projected bounding radius to 64 pixels, and examined raster pixels to 4 million. Radius clipping or work-budget exhaustion sets `truncated`; consumers must then treat the image as incomplete. The convenient Android call returns a `FloatArray`. `renderSurfelDepthInto` instead writes synchronously from the output buffer's current position into a writable direct native-order `FloatBuffer` without allocating or copying a complete JNI result array; position is not advanced. The host must provide at least `cameraWidth * cameraHeight` remaining floats and keep the buffer alive and unmodified until return. This enables reuse of renderer staging memory.

The output is suitable as a metric CPU-side input to a future renderer adapter, but no GPU upload, temporal filtering, hole filling, or Filament material integration is claimed yet.

## Safety and current limits

Android session writes are allowed only while the geometric mapping gate is active. This prevents a depth model from painting geometry into an unstable world pose. The surfel map is transient and intentionally excluded from `ARKMAP01` until mesh schema, storage budgets, relocalized alignment, and privacy policy are finalized.

This is a bounded surface-point representation, not yet a TSDF, watertight mesh, hole-filling system, or rendering occlusion adapter. No bundled monocular model is claimed; model manifests must document metric conversion, license, quantization, confidence calibration, and device performance.
