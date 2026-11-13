# Independent physical targets (`ARKTARGET1`)

The roadmap has said for a long time that centimetre validation is bound to
independent targets. Nothing in the tree recorded what "independent" meant,
so the word was doing no work: a validation could compare a reconstruction
against the very target that was used to set its scale and call the result
evidence.

`ARKTARGET1` fixes the bookkeeping. It is a bounded registry of physical
reference targets in which every target declares two things about itself:

- **did it set the scale?** (`used_for_scale`) — whether its value was fed into
  the scale estimate;
- **was it held out?** (`held_out`) — whether its measured value was withheld
  from calibration.

A target that did both is a contradiction and is rejected with
`ROLE_CONFLICT`: a value that set the scale was by definition not withheld.
Only targets that did **neither** — consistent, independent and held out —
count towards `usable_check_count`, and only when enough of them survive does
the registry report `independence_sufficient=true`.

## Non-circularity is all it claims

`independence_sufficient` does **not** say the reconstruction is accurate. It
says a later check would not be circular. That is a precondition for evidence,
not evidence.

Likewise `targets_consistent` describes the **physical objects**, not the app.
The app never measures a target, never sees a camera frame and never receives
a pose here. Every millimetre in the record was typed in by a human holding a
tool.

## Catching a misprinted sheet

Each row carries a nominal dimension (what the target is supposed to measure),
a measured dimension (what the operator actually measured on the real object)
and a tolerance. The gap between nominal and measured is the target's own
error.

This is where a printed reference that came out of the printer scaled to fit
gets caught. An A4 checkerboard printed at "fit to page" instead of 100 % has
squares that are not the size the file claims; every measurement taken against
it inherits that error silently, and nothing downstream can tell the
difference between a bad reconstruction and a bad sheet. Here the deviation
shows up as a row that fails its own tolerance, the target drops out of
`usable_check_count`, and the registry says so.

## Format

`ARKTARGET1` carries 24 fixed metadata keys — registry, device and campaign
tokens, the required number of independent check targets, the declared counts,
the two verdicts, the units and eight fail-closed invariants — followed by one
row per target and `END`:

```text
target=<index>|<id>|<kind>|<nominalMm>|<measuredMm>|<toleranceMm>|<source>|<usedForScale>|<heldOut>|<repeatCount>
```

Eight target kinds are recognised: a printed checkerboard, an AprilTag sheet,
an ArUco sheet, a machined bar, a gauge block, a tape span, a room diagonal and
a floor tile. Five sources record how the nominal dimension is known:
manufacturer specification, caliper, tape measure, laser distance meter and a
certified gauge. Every one of those is a **human, physical** source.

Limits: 1–16 targets, dimensions of 1–100,000 mm, tolerances of 1–10,000 mm,
1–64 repeats, and 1–8 required independent check targets.

Parsing recomputes every declared count and both verdicts and rejects the file
on any disagreement (`STATISTICS_MISMATCH`, `INDEPENDENCE_MISMATCH`), so a
registry cannot claim independence it did not earn.

## In the diagnostics app

Button **17. Unabhängige Targets (ARKTARGET1)** opens
`IndependentTargetsActivity`: name the registry, device and campaign, say how
many independent check targets you need, then measure each object and add it
with its role. The list shows the deviation per target and flags any that
exceeds its tolerance. `ARKTARGET1_<timestamp>.txt` is written to
`Downloads/ARKernelDiagnostics`.

## Example

`docs/examples/ARKTARGET1_example.txt` (898 bytes including the trailing
newline) holds four targets and is deliberately mixed:

| target | role | deviation | verdict |
| --- | --- | --- | --- |
| `checker.a4` | set the scale | 0 mm | consistent, but never a check |
| `gauge.100` | independent, held out | 0 mm | usable check |
| `bar.500` | independent, held out | 2 mm | usable check |
| `tile.300` | neither | 21 mm | **inconsistent** — unusable |

So the example reports `independence_sufficient=true` and
`targets_consistent=false` at the same time: there are enough usable check
targets for a non-circular validation, and one object in the room is not
trustworthy as a reference. The values are **example values from a synthetic
run, not measurements of a real device or room**.
