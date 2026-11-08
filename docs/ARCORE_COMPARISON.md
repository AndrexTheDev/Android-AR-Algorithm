# Standalone kernel versus ARCore: quality contract

This comparison separates current evidence from target architecture. ARCore is an optional runtime adapter, not a quality claim.

| Capability | ARCore on a supported/certified device | Standalone kernel now | Acceptance target |
|---|---|---|---|
| Live 6-DoF tracking | Mature, heavily device-tuned | Functional Camera2/IMU VIO; less device validation | Non-inferior ATE/RPE, loss and relocalization rates on the published device/scene matrix |
| Calibration | Device integration handled by ARCore | Explicit intrinsics and camera/IMU calibration required | Signed per-device profiles plus user calibration verification |
| Planes and local anchors | Mature | Implemented with finite observed boundaries | Non-inferior stability/jitter and false-plane rate |
| Depth | Depth API where supported; quality varies | Model/sensor-independent metric depth boundary and temporal filtering | Held-out depth RMSE/completeness reported per range/material |
| Final mesh | Not a general automatic textured export pipeline by itself | Canonical textured mesh and export exist; final TSDF/MVS mesher not complete | Globally optimized, watertight where evidence supports it, uncertainty/provenance retained |
| Object capture | Application-specific | Object ROI/coverage planner and colour keyframe archive foundations | Guided coverage, segmentation, turntable model, completeness score |
| Textures | Application-specific | Full-resolution timestamped I420 keyframes and texture-capable formats | Visibility-aware atlas, exposure compensation, seam optimization |
| Accuracy claim | No universal centimetre guarantee | No universal guarantee; evidence gates implemented | `centimeter-validated` only against independent targets and absolute error bounds |
| Device reach | ARCore-supported devices | ARCore-independent fallback with explicit support probe | Broad calibrated Camera2/IMU device matrix |
| Interoperability | AR session APIs | glTF/GLB, OBJ/MTL, PLY and local viewer | Additional USDZ/survey adapters from canonical asset |

## Required proof before “same or better”

A release may claim parity only for a named device/scene envelope and only when blind evaluation shows non-inferiority. At minimum record:

- EuRoC/TUM-VI style trajectory ATE and RPE, tracking-loss duration and relocalization success;
- measured length error against independent scale bars/targets at multiple ranges;
- depth RMSE, completeness and outlier rate by material and lighting;
- surface-to-ground-truth Chamfer distance and completeness;
- texture reprojection/seam error and held-out view quality;
- runtime FPS, latency, memory, thermal throttling and battery use;
- object and environment coverage, with transparent/reflective/textureless failures reported;
- the same metrics for ARCore on the same physical device and capture route where ARCore is available.

“Better” can mean higher final model quality at the cost of offline processing; it must not be confused with lower live latency. Until this matrix passes, ARCore remains expected to win live robustness on its certified devices, while the standalone kernel offers broader availability and a more controllable reconstruction/export pipeline.
