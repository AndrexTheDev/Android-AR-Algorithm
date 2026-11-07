# Depth and bounded surfel fusion

The core does not require LiDAR and does not assume a particular depth model. Stereo, monocular metric-depth adapters, structured-light/ToF sensors, imported point clouds, or future ARCore depth adapters may submit calibrated camera-space samples through the same contract.

## Observation contract

Each sample contains a positive-depth metric point in the optical camera frame, confidence, radius, frame timestamp, and an optional camera-frame unit normal. The exact `world_from_camera` pose associated with that depth frame is supplied separately. JNI/native code rotates points and normals into the current world frame; timestamps must match, so delayed inference cannot silently use the latest pose.

Adapters must establish metric scale and calibration. Relative monocular depth values, disparity values, raw 16-bit sensor units, and image pixels are not metric 3D observations until converted by the adapter. A batch is limited to 100,000 observations.

## Aligned metric depth images

`sampleMetricDepthImage` and Android `integrateMetricDepthImage` provide the efficient dense path. Input is an optical-axis Z-depth `FloatArray` in metres, aligned to the exact calibrated camera width and height, with an explicit row stride and optional equally aligned confidence plane. Invalid, non-finite, out-of-range, or low-confidence pixels are skipped and never filled. Configurable pixel-stride sampling bounds CPU and memory before JNI creates surfel records.

Each accepted pixel is distortion-aware unprojected. Radius is derived from depth, focal length, and sampling stride. A local normal is estimated from right/down sampled neighbours only when both depths remain within a 10% discontinuity gate; depth edges therefore remain points without fabricated cross-edge normals. Output is capped at 100,000 samples and then follows the same frame-pose transform and SurfelMap fusion path.

## Fusion and publication

`SurfelMap` quantizes world points into 5 cm voxels. Position, radius, confidence, and optional normal are confidence-weighted. A voxel needs two observations before publication. Defaults enforce confidence `>= 0.20`, radii from 2 mm to 50 cm, world-position norm up to 1 km, a 30-second lifetime, and a hard 50,000-surfel budget. Out-of-order observations within a voxel are rejected. Accumulated weights are bounded so old data cannot overflow or permanently prevent adaptation.

When full, deterministic eviction prefers unconfirmed, weakly observed, low-confidence, old surfels. IDs and query order are stable for the lifetime of an entry. Reset clears all surfels.

## Safety and current limits

Android session writes are allowed only while the geometric mapping gate is active. This prevents a depth model from painting geometry into an unstable world pose. The surfel map is transient and intentionally excluded from `ARKMAP01` until mesh schema, storage budgets, relocalized alignment, and privacy policy are finalized.

This is a bounded surface-point representation, not yet a TSDF, watertight mesh, hole-filling system, or rendering occlusion adapter. No bundled monocular model is claimed; model manifests must document metric conversion, license, quantization, confidence calibration, and device performance.
