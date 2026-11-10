# Trajectory evaluation: ATE and RPE

## Supported inputs

Estimated trajectories use the dependency-free format:

```text
ARK_TRAJECTORY_V1
timestamp_ns px py pz qw qx qy qz
```

Ground truth can be loaded directly from EuRoC's `mav0/state_groundtruth_estimate0/data.csv` (`p_RS_R` and `q_RS`). Samples are nearest-neighbor associated under a configurable maximum timestamp difference; one ground-truth clock domain must already match the estimate.

## Alignment modes

- `none`: no alignment;
- `se3`: rigid rotation and translation, appropriate for metric VIO;
- `sim3`: rotation, translation and one positive global scale, appropriate for diagnosing monocular scale ambiguity.

Alignment uses Horn's closed-form quaternion method over associated positions. Reported metrics:

- ATE translation RMSE, mean, median and maximum in meters;
- RPE translation RMSE in the local coordinate frame over a configurable sample delta;
- RPE rotation RMSE in degrees;
- associated sample count and recovered alignment scale.

A production benchmark should report **SE(3)** as the primary VIO score. Sim(3) can hide scale errors and must be labeled clearly.

## Commands

Export a trajectory while replaying:

```bash
arkernel_replay manifest.arkr --trajectory estimated.arktraj
```

Evaluate against EuRoC:

```bash
arkernel_trajectory_eval estimated.arktraj \
  mav0/state_groundtruth_estimate0/data.csv se3
```

Convert an extracted EuRoC sequence into replay form:

```bash
python3 tools/datasets/euroc_to_replay.py /data/MH_01_easy/mav0 /tmp/mh01
```

Run conversion, optimized replay, trajectory export and both evaluations in one command:

```bash
tools/benchmarks/run_euroc.sh /data/MH_01_easy/mav0 /tmp/mh01-baseline
```

EuRoC images are PNG; conversion uses the free Pillow package when needed. IMU and calibration parsing use only Python's standard library. The converter preserves timestamps, maps EuRoC IMU order `(gyro, accel)` into the kernel's `(accel, gyro)` contract, converts frames to binary PGM, sorts equal-time IMU before camera events, and converts the camera `T_BS` rotation and translation into the required `body_from_camera` transform. Ignoring either the axis rotation or the metric camera lever arm would make visual-inertial fusion and any resulting ATE physically inconsistent.

## Interpretation limits

ATE/RPE do not measure relocalization, anchor jitter, depth quality, CPU, memory, energy or thermal throttling. Results are invalid if timestamps, sensor extrinsics, camera calibration or ground-truth frame conventions are wrong. Dataset scores must include sequence name, alignment mode, association threshold, build commit and device/backend configuration.

## Bounded host-supplied regression trends

`ArKernelRegressionTrendBundle` adds a canonical `ARKREGR1` text ledger for
already computed, host-supplied scalar results. It has a fixed metadata order,
bounded input/line/run budgets, unique opaque run IDs, contiguous indices,
finite decimal values and uncertainties, BOM/CRLF tolerance with one optional
final separator and a fail-closed `END`. Metadata bind `LOWER_IS_BETTER` or
`HIGHER_IS_BETTER`, an absolute budget, a relative budget relative to the
baseline magnitude, a minimum sample count and the permanent grade
`unverified`.

The pure evaluator reports the first/last delta, direction-dependent
degradation and improvement, relative degradation, an uncertainty-derived
noise floor and one of `IMPROVING`, `STABLE`, `DEGRADING` or
`BUDGET_VIOLATION`. A budget violation is a structural regression signal, not
an assessed accuracy claim. The adapter performs no I/O, replay, trajectory
evaluation, dataset download, upload, signature verification or quality
upgrade. Real MH_01/TUM-VI/KITTI measurements and external dataset corpora
remain physical/operational work; `ARKREGR1` can only compare bounded scalars
that a host has already supplied.

## Bounded host-supplied dataset corpus manifests

`ArKernelDatasetManifestBundle` adds a canonical `ARKDSMAN1` text manifest for
bounded host-supplied corpus metadata. It has a fixed nine-key metadata order,
1–64 entries, unique opaque dataset IDs, contiguous indices, opaque license and
role tokens, lowercase 64-character SHA-256 hex digests, positive byte sizes,
input/line budgets, BOM/CRLF tolerance with one optional final separator and a
fail-closed `END`. Four safety flags keep dataset loading and automatic budget
derivation disabled, while host signature verification and independent targets
remain explicit host requirements rather than implemented capabilities; the
grade remains `unverified`.

The pure summarizer reports only entry count, total bytes and distinct license
and role counts. It performs no I/O, corpus loading, replay, benchmark run,
upload, signature verification, automatic budget derivation from real data,
device validation or quality upgrade. Real MH_01/TUM-VI/KITTI measurements,
real external corpora with license/hash manifests and real benchmark runs
remain open; `ARKDSMAN1` is only a bounded transport shape for metadata a host
has already supplied.

## Bounded host-supplied device-farm scene matrices

`ArKernelDeviceFarmScenePlanBundle` adds a canonical `ARKSCENE1` text plan for
bounded host-supplied device-farm scene metadata. It has a fixed nine-key
metadata order, 1–24 scenes, unique opaque scene IDs, contiguous indices,
opaque scene-kind and capability-signature tokens, bounded minute durations and
a `0`/`1` thermal-run marker. Input/line/scene budgets, BOM/CRLF tolerance with
one optional final separator and a fail-closed `END` are enforced. Four safety
flags keep provider binding disabled, while rig evidence, thermal-run evidence
and independent targets remain explicit host requirements rather than
implemented capabilities; the grade remains `unverified`.

The pure summarizer reports only scene count, total minutes, thermal-run count
and distinct scene kinds/capability signatures. It performs no provider
binding, Mobly rig control, real thermal run, day/night/weather capture, I/O,
upload, evidence verification, device validation or quality upgrade. Real
Cloud/Lab provider integration, physical rig evidence, real 30-minute thermal
runs and real day/night/weather scenes remain open; `ARKSCENE1` is only a
bounded transport shape for a scene matrix a host has already supplied.

## Bounded host-supplied runtime-adapter deployment manifests

`ArKernelRuntimeAdapterDeploymentBundle` adds a canonical `ARKRTAD1` text
manifest for bounded host-declared separately packaged runtime adapters. It has
a fixed ten-key metadata order, 1–16 adapters, unique opaque adapter IDs,
opaque versions and backend/driver profiles, lowercase 64-character SHA-256 hex
digests, positive bounded byte sizes, contiguous indices and the fixed state
`declared`. Input/line/adapter budgets, BOM/CRLF tolerance with one optional
final separator and a fail-closed `END` are enforced. Five safety flags keep
CPU fallback, startup sentinel, read-only factory admission, model-name-free
identity and separate packaging as explicit host requirements rather than
implemented capabilities; the grade remains `unverified`.

The pure summarizer reports only adapter count, total bytes, declared count and
distinct backend/driver profiles. It performs no adapter loading, artifact read,
signature verification, factory construction, startup sentinel, CPU-fallback
execution, device-farm run, I/O, upload or quality upgrade. Concrete adapter
artifacts, factory implementations and device-farm corpus results remain open;
`ARKRTAD1` is only a bounded transport shape for metadata a host has already
supplied.
