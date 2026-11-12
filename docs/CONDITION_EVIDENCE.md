# Controlled-condition evidence (`ARKCOND1`)

`ARKMEAS1` records a condition *label* per scene — indoor, outdoor, day, night,
thermal, still — typed by the operator. Nothing records what was behind the
label: whether a rig was used, whether the device was thermally throttled while
the scene was captured, or how bright it actually was. A run labelled `night`
that was shot under a streetlight and a run labelled `night` in a pitch-black
room look identical in the record.

`ARKCOND1` is the evidence behind the label. Each sample records the rig, the
pose, the repetition, the device's thermal status and headroom, and the ambient
light level.

## What is read from the device and what is typed

Two values come from sensors rather than from the keyboard:

- **thermal status and headroom** from the platform thermal API
  (`PowerManager.getCurrentThermalStatus()` / `getThermalHeadroom()`);
- **ambient light** from `Sensor.TYPE_LIGHT`, sampled for about a second and
  reduced to the median.

Both are readings from **this device at this moment**, not a calibrated
measurement of the scene. A phone in a pocket reads darkness at noon; a lamp
pointed at the sensor reads daylight at midnight. That is why the format calls
the thermal source `host_reported` even when the app read it itself.

The condition label is always the operator's choice.

## The label check that is not a truth check

The ledger cannot verify a label against reality. What it *can* do is check the
label against two lux thresholds the operator also declared:

- `day_min_lux_milli` — a sample labelled `day` below this is a conflict;
- `night_max_lux_milli` — a sample labelled `night` above this is a conflict.

So the thresholds are the operator's own, and the app only reports the
disagreement. A run that was mislabelled by mistake — a `day` capture taken in
a dark corridor, a `night` capture under a floodlight — shows up as
`label_conflicts > 0` instead of passing silently. Only `day` and `night` are
checked; `indoor`, `outdoor`, `thermal` and `still` have no threshold to
contradict.

## The five verdicts

| verdict | means |
| --- | --- |
| `day_night_covered` | both `day` and `night` have at least the declared minimum of samples |
| `labels_coherent` | no sample contradicts the operator's own lux thresholds |
| `thermal_stable` | the worst thermal status seen is within the declared band |
| `repeatability_documented` | at least one pose was captured twice **on the same rig and condition** |
| `evidence_sufficient` | all four above, plus enough conditions met the minimum |

`evidence_sufficient` is a **completeness gate, not a quality grade**. It says
the conditions behind the measurement are documented. It says nothing about
whether any of it improved accuracy.

The repeatability rule is deliberately strict: a repeat that moved to a
different rig does not document repeatability, because a different rig is a
different setup.

## Format

`ARKCOND1` carries 34 fixed metadata keys — the ledger, device and campaign
tokens, the declared thresholds, the recomputed counts, the five verdicts, the
units and five fail-closed invariants — then one row per sample and `END`:

```text
sample=<index>|<condition>|<rigId>|<rigKind>|<poseIndex>|<repeatIndex>|<thermalStatus>|<headroomPermille>|<luxMilli>|<timestampMs>
```

Light is stored in **milli-lux** (`lux_unit=mlux`) so fractional lux values
survive: the example carries 6 900 for a 6.9 lux night scene. Five rig kinds
are recognised: `handheld`, `tripod`, `turntable`, `linear.rail`, `gantry`.

Limits: 1–64 samples, poses 0–1023, repeats 0–63, thermal status 0–6, headroom
0–1000 ‰, light up to 200 000 lux, and strictly increasing timestamps. The
quadruple (rig, condition, pose, repeat) must be unique — the same physical
capture cannot be recorded twice.

Every declared count and all five verdicts are recomputed on parse and any
disagreement is rejected (`STATISTICS_MISMATCH`, `EVIDENCE_MISMATCH`), so a
ledger cannot claim coverage it did not collect.

## In the diagnostics app

Button **18. Bedingungen: Rig, Thermik, Tag/Nacht (ARKCOND1)** opens
`ConditionEvidenceActivity`. Name the ledger, set the two lux thresholds, then
for each capture choose condition, rig, pose and repetition and press
**Thermik & Licht messen** — the app fills thermal status, thermal headroom and
ambient light from the device. `ARKCOND1_<timestamp>.txt` goes to
`Downloads/ARKernelDiagnostics`.

## Example

`docs/examples/ARKCOND1_example.txt` (1 158 bytes including the trailing
newline) holds six samples: two day, two night, two in a deliberate thermal
soak — each pose once on a tripod and once on a turntable, every pose repeated.

Everything looks complete: day and night are both covered, there are no label
conflicts, and repeatability is documented. But the thermal soak pushed the
device to status 4 while the declared band allows at most 2, so
`thermal_stable=false` and therefore `evidence_sufficient=false`. That is the
point: those runs cannot be compared to the cool ones, and the file says so
instead of quietly averaging them in.

The values are **example values from a synthetic run, not measurements from a
real device or room**.
