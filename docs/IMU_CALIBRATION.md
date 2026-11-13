# Inertial intrinsic calibration (`ARKIMUCAL1`)

A visual-inertial kernel integrates the accelerometer and the gyroscope
between camera frames. If the accelerometer reads 1 % high or the gyroscope
6 % high, the scale and the orientation are wrong before a single image is
looked at — and nothing in the tree measured either.

`ARKIMUCAL1` is a bounded, deterministic calibration of one device's inertial
sensors, produced by four test series that need nothing but the phone, a flat
table and a right angle. It is the one part of a camera calibration that is
genuinely measurable without capturing an image.

## The four series

| # | Series | What it records | What it yields |
|---|---|---|---|
| 1 | **Cadence** | three seconds of raw delivery | rate, gaps, duplicate timestamps, clock domain |
| 2 | **Static** | the device at rest | gyroscope bias, noise of both triads, bias stability |
| 3 | **Tumble** | six orientations, each axis up and down | accelerometer bias, scale, misalignment, repeatability |
| 4 | **Turn** | rotations through an angle a jig imposed | gyroscope scale factor per axis, cross-axis coupling |

They have to be run in that order: the turn series subtracts the bias the
static series measured, and the tumble is what makes the accelerometer model
identifiable at all.

### 1. Cadence

This is not physics, it is plumbing. A stream that promises 200 Hz and
delivers 190 Hz with occasional 40 ms holes produces a wrong integral before
any calibration is applied, and a stream whose timestamps are not on the clock
the kernel aligns against cannot be merged with another stream at all.

`domain` records which of the three Android clocks the timestamps came from,
decided by taking whichever is closest within two seconds; if none is, the
domain is `unknown`. A gap is an interval more than twice the median interval,
which is a statement about the delivery, not about the sensor.

### 2. Static

From a run at rest come four things:

- **Gyroscope bias** — the mean of the gyroscope, sample-weighted across runs.
- **Noise density** — the isotropic noise of a triad, `rms = sqrt(mean per-axis
  variance)`, converted to a density as `sigma * sqrt(2 / rate)`. A sampled
  white-noise process spreads its variance over the Nyquist band, so the
  single-sided density carries that factor. The accelerometer is reported in
  mg/√Hz, the gyroscope as an angle random walk in °/√h (`density * 60`).
- **Bias stability** — the standard deviation of block means over ten blocks.
  This is how much the *average* wanders, which is a different thing from how
  noisy one sample is, and it is what limits a long integration.
- **Temperature** — the battery temperature, a proxy for how warm the device
  is. It is not the sensor die temperature and it moves slowly.

### 3. Tumble

With `a = M · gAxis + b`, the mean of two opposite poses gives `b` (gravity
cancels) and their difference gives one column of `M`. Six poses therefore
determine all twelve parameters — three biases, three scale factors, six
misalignment terms — in closed form, with no iteration and no solver.

Two checks come out of the same data and cannot be faked by the fit:

- **Gravity magnitude.** After removing the fitted bias, the length of each
  column has to come out equal to the declared local `g`. Every column is
  derived from the same measurements, but its *length* is not something the
  fit chose.
- **Repeatability.** The deviation of each pose from the mean of its own
  direction. With exactly six poses the fit is exact and the residual is zero,
  which is why `accel_repeat_checked` exists separately: a single cycle cannot
  tell you whether the tumble is repeatable, only that it is arithmetically
  solvable. **Run the six poses twice.**

The gyroscope means taken in the six orientations also give
`gyro_bias_spread_dps` — how much the bias moves between orientations, which
mixes g-sensitivity with thermal drift and is reported as a spread, not
attributed to a cause.

### 4. Turn

The gyroscope is never asked how large its own angle was. The operator imposes
an angle with a jig — a right-angle box, a mitre saw, a turntable with marks —
and the ratio of the integrated angle to the imposed angle is the scale
factor. The integration is trapezoid over bias-corrected samples, and the
angle integrated about the *other* two axes is reported as cross-axis
coupling: it should be zero and it is not.

A turn whose peak rate exceeds `max_turn_rate_dps` is rejected from the
verdict, because a rotation done too fast leaves the sensor's linear range and
the scale estimate degrades. `outstandingSteps()` reports it as `turn_rate`.

## Free-integration drift

Two derived numbers say what the kernel may attempt without correction:

- `angle_drift_deg_per_min` — bias grows linearly with time, noise as the
  square root of time; the two are combined in quadrature.
- `position_drift_m_per_min` — a residual acceleration grows as `t²/2`,
  accelerometer white noise as `t^1.5 / sqrt(3)`.

For the example device below these are 20 °/min and 7.5 m/min. That is what
"the estimate degrades between frames" means in units you can hold.

## Verdicts

Eleven, all recomputed on parse; any disagreement with the declared value
rejects the block.

| Verdict | Requires |
|---|---|
| `cadence_ok` | at least one stream, no gaps / duplicates / backwards timestamps, gap and rate error inside their gates |
| `duration_ok` | at least one static run and one turn, both long enough |
| `gravity_ok` | six directions present and the at-rest magnitude within `max_gravity_error_mps2` of `g` |
| `accel_fit_ok` | six directions present, bias, scale error and misalignment inside their gates |
| `accel_repeat_checked` | at least `min_repeat_cycles` cycles and the residual inside its gate |
| `gyro_scale_ok` | at least one turn, no clipped rate, scale error and cross-axis coupling inside their gates |
| `bias_ok` | at least one static run and the gyroscope bias inside its gate |
| `noise_ok` | at least one static run and both noise densities inside their gates |
| `stability_ok` | at least one static run and both stabilities inside their gates |
| `thermal_covered` | observed temperature span at least the required span |
| `calibration_sufficient` | all of the above |

## Format

`ARKIMUCAL1` carries 74 fixed metadata keys in exact order — the ids, the
declared local gravity, seventeen gates, the four row counts, thirty-one
recomputed statistics, the eleven verdicts and six fail-closed invariants —
then the rows and `END`:

```text
stream=<index>|<label>|<nominalHz>|<measuredHz>|<minDtUs>|<maxDtUs>|<gapCount>|<duplicateCount>|<monotonicViolations>|<domain>
static=<index>|<label>|<durationMs>|<sampleCount>|<gyroSampleCount>|<meanGx>|<meanGy>|<meanGz>|<rmsAcc>|<rmsGyr>|<stabAcc>|<stabGyr>|<maxGapMs>|<temperatureC>
pose=<index>|<label>|<direction>|<sampleCount>|<meanX>|<meanY>|<meanZ>|<meanGx>|<meanGy>|<meanGz>|<temperatureC>
turn=<index>|<label>|<axis>|<referenceAngleDeg>|<integratedAngleDeg>|<offAxisAngleDeg>|<durationMs>|<sampleCount>|<peakRateDps>
```

The accelerometer and the gyroscope each carry their own sample count, because
they frequently run at different rates on the same phone; using one count for
both would put a 40 % error on the noise density.

Budgets: 4 streams, 8 static runs, 12 poses, 16 turns, 256 characters per
line, 16 384 characters per block. Directions are `px nx py ny pz nz` (which
device axis points **up**), axes `x y z`, domains `elapsed_realtime uptime
wall unknown`.

## What it explicitly does not do

It says nothing about the camera. No image is captured, no extrinsic between
camera and IMU is estimated, no time offset between the two clocks is
searched, and no distortion model is fitted. Everything the device reports
about its own camera is a claim by the manufacturer, not a measurement, and
`ARKIMUCAL1` does not repeat it.

It is also not a quality grade. A well-calibrated inertial sensor is a
precondition for a working visual-inertial system, not evidence that one
works: a device can have a beautiful accelerometer and still reconstruct a
room the wrong size, because the scale error lives in the camera and the time
offset, neither of which this contract can see.

`calibration_sufficient` is a completeness gate. The grade stays `unverified`.

## Example

`docs/examples/ARKIMUCAL1_example.txt` (3 133 bytes) is a plausible mid-range
phone, not a good one. Nine of the eleven verdicts pass. Two fail, and they
are the two a real first run usually fails:

- `accel_repeat_checked = false` — one tumble cycle. The fit is exact and the
  residual is zero, which is exactly the problem: nothing has been repeated,
  so nothing is known to be repeatable.
- `gyro_scale_ok = false` — `gyro_scale_error_max_permille = 62`. The
  gyroscope reads 6.2 % fast about the X axis. A kernel that trusts it turns
  96° when the world turns 90°.

Its accelerometer is fine by comparison: bias 0.069 m/s², scale errors within
5 ‰, misalignment 4 ‰, noise 2.8 mg/√Hz, and the at-rest magnitude within
0.038 m/s² of the declared `g`. Its orientation runs away at 20 °/min and its
position at 7.5 m/min if nothing corrects it.

`outstandingSteps()` answers `repeat` — do the six poses again.
