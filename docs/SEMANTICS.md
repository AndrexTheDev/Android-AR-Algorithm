# Semantic 3D object map

The core does not claim that sparse geometry alone recognizes furniture or arbitrary room contents. `SemanticObjectMap` is the model-independent fusion boundary for observations produced by a separately licensed on-device detector, tracker and depth/raycast adapter.

## Observation contract

A `SemanticObjectObservation` contains:

- a `class_id` from the host's versioned model manifest (`0` is unknown);
- an optional model-local `source_track_id`;
- a finite metric world position;
- confidence in `[0,1]`;
- monotonic timestamp;
- a dynamic/static flag.

The adapter is responsible for converting image detections into metric 3D observations using trusted depth, triangulation or a bounded plane raycast. A 2D box alone is not a 3D object position.

For plane-supported objects, `projectSemanticObservation` unprojects an in-image calibrated pixel, rotates its ray with the frame-associated `world_from_camera` pose, and chooses the nearest bounded hit. The request can allow any plane or require `FLOOR`/`WALL`; a semantic point is never created outside an observed polygon or when the requested surface class is absent. The adapter chooses the meaningful pixel (for example, bottom-center for a floor-supported detection) and must retain the exact camera pose from that detection frame.

## Conservative fusion

The bounded map first prefers a matching class/track pair subject to a maximum jump. Otherwise it uses nearest-neighbor association only within the same class and dynamic state. Positions are confidence-weighted. Objects remain unpublished until a configurable number of observations confirms them. Capacity eviction is deterministic and prioritizes unconfirmed, dynamic, weak, sparsely observed and old entries.

Defaults:

- 256 total candidates/objects;
- 2 observations before publication;
- minimum confidence `0.25`;
- untracked association radius `0.60 m`;
- tracked jump gate `1.50 m`;
- maximum world-position norm `1000 m`;
- static lifetime `30 s`;
- dynamic lifetime `2 s`.

Dynamic and static instances never merge. Dynamic objects expire quickly and are not used as structural geometry. The Android session accepts semantic writes only while the geometric mapping gate is active.

## Labels, models and privacy

The kernel stores numeric class IDs, not a hard-coded taxonomy. Every inference adapter must expose the validated Android `ModelManifest` with model hash, license, label mapping, quantization, expected input normalization and measured device profile; scheduling and result validation are defined in `INFERENCE.md`. Unknown or unsupported content remains class `0`; applications must not imply broader recognition than their model provides.

Semantic objects are currently transient and are deliberately excluded from `ARKMAP01`. Persisting labels can reveal sensitive room contents and requires a versioned schema, explicit product policy and the same authenticated storage controls as geometry. No semantic data is uploaded by this core.
