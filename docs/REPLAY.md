# Deterministic sensor replay

Replay makes camera/IMU regressions reproducible without a physical Android device. It uses the same ordering rule as `ArKernelSession`: all IMU rows with `timestamp <= frame timestamp` must appear before that frame.

## Manifest v1

Text manifest (`.arkr`), paths relative to the manifest:

```text
ARK_REPLAY_V1
CAMERA width height fx fy cx cy k1 k2 p1 p2 k3
IMU timestamp_ns ax ay az gx gy gz
FRAME timestamp_ns image.pgm
```

Constraints:

- exactly one `CAMERA`, before events;
- globally monotonic boot-time-like nanosecond timestamps;
- each IMU and frame stream strictly increasing;
- at equal timestamps, IMU precedes FRAME;
- finite SI values: m/s² and rad/s;
- binary P5 PGM, 8 bit, exact calibrated dimensions;
- no implicit resizing, crop or calibration guessing.

## Generate and execute the synthetic fixture

```bash
python3 tools/replay/generate_synthetic.py /tmp/ark-replay
c++ -std=c++17 -O2 -Icore/include core/src/*.cpp tools/replay/main.cpp \
  -pthread -o /tmp/arkernel_replay
/tmp/arkernel_replay /tmp/ark-replay/manifest.arkr
```

The JSON result reports frame states, geometry selection, visual fusions, peak feature count, final VIO status and a deterministic FNV-1a hash over quantized algorithm outputs. CI executes the same replay twice and requires byte-identical JSON. The hash is a regression signal, not a cryptographic integrity check and not an accuracy metric.

## Real-device recording policy

A future recorder should write sensor events and Y planes on a bounded background queue, preserve original timestamps, camera ID/crop/calibration and device metadata, and redact RGB by default. Recorded user environments must not be committed to Git without explicit consent. Accuracy datasets should live in external storage with hashes and licenses recorded in a manifest.
