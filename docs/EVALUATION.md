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

EuRoC images are PNG; conversion uses the free Pillow package when needed. IMU and calibration parsing use only Python's standard library. The converter preserves timestamps, maps EuRoC IMU order `(gyro, accel)` into the kernel's `(accel, gyro)` contract, converts frames to binary PGM, and sorts equal-time IMU before camera events.

## Interpretation limits

ATE/RPE do not measure relocalization, anchor jitter, depth quality, CPU, memory, energy or thermal throttling. Results are invalid if timestamps, sensor extrinsics, camera calibration or ground-truth frame conventions are wrong. Dataset scores must include sequence name, alignment mode, association threshold, build commit and device/backend configuration.
