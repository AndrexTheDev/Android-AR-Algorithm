# Device-farm corpora (`ARKCORPUS1`)

A corpus can hold thousands of samples and still be unable to compare
anything, because every cell in it was captured on a single phone.

`ARKCORPUS1` is a bounded index that asks the two questions which decide
whether a cross-device comparison is possible at all, before anyone runs one:

1. **Is the same cell present on more than one device?** A (condition, scene)
   cell captured by one phone only carries no comparison, no matter how many
   samples it holds.
2. **Does one device dominate?** A "device farm" in which one phone supplies
   nine samples in ten is a single-device corpus with extra steps.

## Matched cells

The corpus is indexed over three axes — device, condition, scene kind — and
grouped into **cells**: the distinct (condition, scene kind) pairs. A cell is
*matched* when at least two different devices captured it. `matchedCells` counts
those; `cross_device_ready` gates on a declared minimum.

This is the number that matters and the one nobody writes down. A corpus with
one device and a hundred thousand samples has `matchedCells = 0`.

`unmatchedCells()` lists the cells no comparison can use, so the gap is
actionable: it tells you exactly which condition/scene combination to go and
capture on a second device.

## Dominance

`observed_max_device_share_permille` is the largest share of the corpus
contributed by any single device, in per mille, recomputed from the sample
counts and compared against a ceiling the operator declared
(`max_device_share_permille`). `dominantDeviceTokens()` names the devices
holding that share.

## What it explicitly does not say

`corpus_sufficient` is a **precondition check, not a quality grade**. It says
the corpus is spread over enough devices, conditions and scenes, is not
dominated by one device, and has enough cells that two devices share — which
means a cross-device comparison is *possible*. It says nothing about whether
that comparison would mean anything, and nothing at all about accuracy.

The index also does not contain the captures. `captureId` is an opaque
host-owned label for a recording that lives somewhere else; `ARKDSMAN1` remains
the place for the files and their digests.

## Format

`ARKCORPUS1` carries 30 fixed metadata keys — the corpus id and revision, the
declared requirements, the recomputed counts, the five verdicts and six
fail-closed invariants — then one row per entry and `END`:

```text
entry=<index>|<captureId>|<deviceToken>|<deviceClass>|<condition>|<sceneKind>|<sampleCount>
```

Recognised values come from the enums already used elsewhere in the tree: four
device classes (`full`, `limited`, `legacy`, `unknown`), six conditions
(`indoor`, `outdoor`, `day`, `night`, `thermal`, `still`) and nine scene kinds
(`indoor_scale`, `outdoor_scale`, `right_angle`, `stillness`, `thermal_repeat`,
`day_night`, `held_out`, `cross_session`, `cross_device`).

Limits: 1–64 entries with unique capture ids, 1–1,000,000 samples per entry,
1–16 required devices, 1–6 required conditions, 1–9 required scenes, 0–64
required matched cells and a share ceiling of 1–1000 ‰.

Every declared count and all five verdicts are recomputed on parse and any
disagreement is rejected (`STATISTICS_MISMATCH`, `CORPUS_MISMATCH`), so a
corpus cannot claim coverage or balance it does not have.

## In the diagnostics app

Button **19. Device-Farm-Korpus (ARKCORPUS1)** opens
`DeviceFarmCorpusActivity`. Name the corpus, state what it has to be able to
support, add one entry per existing capture, and read the verdicts.
`ARKCORPUS1_<timestamp>.txt` goes to `Downloads/ARKernelDiagnostics`.

## Example

`docs/examples/ARKCORPUS1_example.txt` (987 bytes including the trailing
newline) is deliberately a corpus that looks substantial and is not: six
entries, three devices, three device classes, two conditions, three scenes,
10 000 samples in total.

Then the two questions:

- `phone.a` supplies 8 700 of those 10 000 samples — 870 ‰ against a ceiling of
  500 ‰, so `balance_ok=false`;
- of five cells, only one (`day/indoor_scale`, on `phone.a` and `phone.b`) was
  captured by two devices, so `matched_cells=1` against a required 3 and
  `cross_device_ready=false`.

`corpus_sufficient=false`. The unmatched list names exactly what to capture
next: `day/outdoor_scale`, `day/right_angle`, `night/indoor_scale`,
`night/right_angle`.

The values are **example values from a synthetic corpus, not a recording of a
real device farm**.
