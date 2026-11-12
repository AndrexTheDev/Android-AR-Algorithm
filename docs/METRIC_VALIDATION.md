# Metric validation (`ARKVALID1`)

`ARKVALID1` is the first increment that turns "we cannot measure" into "here
is how far off we are". It does **not** create accuracy; it measures the
deviation of the app's own estimate against a length the user measured with a
tape measure.

## What it does

For every scene the user supplies two numbers:

- **ground truth** — the length of the target in whole millimetres, read off a
  folding rule or tape measure, typed in by a human;
- **estimate** — `estimated_target_m` taken from one pasted `ARKFEED1` or
  `ARKSCALE1` artefact, scaled to whole millimetres.

Each row is bound to the lowercase SHA-256 digest of the pasted artefact text.
The artefact text itself is never stored in the ledger, so two rows can never
silently reuse the same measurement: identical digests are rejected.

The adapter computes five statistics in integer millimetres:

| key | meaning |
| --- | --- |
| `mean_abs_error_mm` | sum of absolute errors divided by scene count, truncated |
| `bias_mm` | sum of signed errors divided by scene count, truncated toward zero (negative = on average too short) |
| `max_abs_error_mm` | largest absolute error |
| `p95_abs_error_mm` | linear interpolation between the neighbours at position `0.95 * (n - 1)` of the ascending absolute errors, rounded half up |
| `within_threshold_count` | scenes whose absolute error is at most `threshold_mm` |

`within_threshold` is true only when **every** scene is inside the threshold.
The threshold is declared by the user (1 – 10 000 mm, default 10 mm) and is
part of the artefact, so a result can never be read without its tolerance.

## What it deliberately does not do

- **No accuracy claim.** `metric_accuracy_claimed=false` is a hard invariant
  and cannot be encoded as true. A self-reported run whose ground truth was
  typed in by the same person who took the measurement is evidence, not a
  certification. The ground truth is never re-verified.
- **No quality grade.** `quality_grade=unverified`.
- **No automatic ground truth.** `ground_truth_manual=true` and
  `ground_truth_source=operator_tape_measure` are fixed.
- **No depth, no ARCore.** Perspective, parallax, rolling shutter and lens
  distortion are still not modelled, so the underlying estimate remains a
  screen-ratio estimate.
- **No signature verification, no upload, no image, pixel, pose or
  trajectory handling, no file I/O inside the adapter.**

Nine fail-closed invariants are enforced on encode, on create and on parse:
manual ground truth, disabled automatic calibration, disabled automatic
upload, disabled signature verification, disabled universal calibration,
per-device calibration, manual upload only, disabled images and a required
held-out scene. At least one scene must be marked held-out.

## Consistency

`parse()` recomputes every statistic from the scene rows and rejects the
ledger with `STATISTICS_MISMATCH` if a declared value disagrees. Scene IDs and
source digests must be unique, row indices contiguous, the key order fixed and
the file terminated by `END`.

## In the diagnostics app

Button **14. Metrische Validierung in mm (ARKVALID1)** opens
`MetricValidationActivity`:

1. measure with button 12 (`ARKFEED1`) or 13 (`ARKSCALE1`) and share the
   artefact;
2. paste it into the field and tap **Artefakt lesen** — the app parses it,
   shows the estimate in millimetres and pre-selects scene kind, condition and
   the held-out flag;
3. measure the same target with the tape measure and type the length in
   millimetres;
4. tap **Szene übernehmen**;
5. repeat for all scenes, marking at least one as held out;
6. tap **Validierung berechnen**, then save or share
   `ARKVALID1_<timestamp>.txt` from `Downloads/ARKernelDiagnostics`.

Scene records live in memory for the lifetime of the screen and are cleared on
a fresh start or with **Szenen zurücksetzen**.

## Example

`docs/examples/ARKVALID1_example.txt` (1018 bytes including the trailing
newline) shows three scenes: two inside a 10 mm tolerance, one held-out scene
20 mm off. It reports `mean_abs_error_mm=7`, `bias_mm=-7`,
`max_abs_error_mm=20`, `p95_abs_error_mm=18`, `within_threshold_count=2` and
`within_threshold=false`. The numbers are **example values, not a real
measurement**; the digests are synthetic.
