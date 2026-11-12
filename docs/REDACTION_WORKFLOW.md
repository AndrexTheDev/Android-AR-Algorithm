# Redaction and upload workflow (`ARKREDRUN1`)

The library already had `ARKREDACT1`, a bounded *plan* format: a list of rules
naming a field, an action and an opaque token, with fail-closed safety flags.
It performed no redaction — by design. `ARKREDRUN1` is the missing half: it
**applies** such a plan to real artefact texts and reports exactly what
happened, so that an upload can be gated on something other than good
intentions.

## The detector catalogue

A plan rule has to name one of six built-in detectors. A rule naming anything
else is rejected with `UNKNOWN_DETECTOR` rather than silently left unapplied.

| id | recognises |
| --- | --- |
| `net.mac` | `a4:5e:60:1f:2b:3c` |
| `net.ipv4` | `192.168.1.23` |
| `id.hex16` | a sixteen-character hexadecimal identifier |
| `id.decimal15` | a fifteen-digit device identifier |
| `path.absolute` | `/storage/emulated/0/…` |
| `contact.email` | `operator@example.com` |

`id.hex16` is anchored to exactly sixteen characters with word boundaries on
both sides on purpose. A detector that matched any long hexadecimal run would
flag the SHA-256 digests that every artefact legitimately carries.

## The two actions

- `drop` replaces the match with the rule's opaque token. All matches collapse
  to the same placeholder, so correlation is lost.
- `hash` replaces the match with `token.h` plus the first fifteen hex
  characters of the SHA-256 of the matched value. Two occurrences of the same
  value stay recognisable as equal without revealing the value. The `h` prefix
  is not a hex digit, so a hash token can never look like a hexadecimal
  identifier to the residual scan.

## The residual scan

After the rules have run, every detector **without** a rule is scanned again
over the redacted text. Anything still matching becomes a residual row, and
`upload_admitted` stays `false`. This is the part that makes the workflow
fail-closed: a plan that covers two detectors out of six will not quietly pass
a file that still contains an e-mail address and an absolute path.

## What `upload_admitted` does not mean

It is a **filter, not a promise**. It says: none of the six built-in detectors
matches anywhere in the redacted output. It does not say the file contains
nothing identifying. A value this catalogue does not recognise, a person's
name typed into a scene ID, or a number that only identifies a device in
context passes straight through. Nothing here inspects the *meaning* of a
number.

`upload_admitted` is recomputed on parse and a ledger whose declared value
disagrees with its rows is rejected with `STATISTICS_MISMATCH`.

## Format

`ARKREDRUN1` carries the run id, the plan id and revision, the rule count and
three row kinds:

- `artefact=<index>|<name>|<sha256 before>|<sha256 after>|<changed>` — the
  redacted text itself is never part of the ledger;
- `finding=<index>|<artefact>|<detector>|<action>|<count>` — what a rule
  replaced;
- `residual=<index>|<artefact>|<detector>|<count>` — what a plan gap left
  behind.

Four fail-closed invariants are enforced: automatic upload disabled, manual
upload only, redacted output only, core upload disabled. The grade stays
`unverified`, and parsing rebuilds the artefact rows **without** the redacted
text, so a ledger can never leak what it describes.

## In the diagnostics app

Button **16. Redaktion & Upload (ARKREDRUN1)** opens `RedactionActivity`:
check the plan (the default covers all six detectors), add artefacts, run the
redaction, and only when the result says *freigegeben* write
`redacted_<name>` files plus `ARKREDRUN1_<timestamp>.txt` to
`Downloads/ARKernelDiagnostics`. **The upload itself stays manual.**

## Example

`docs/examples/ARKREDRUN1_example.txt` (815 bytes including the trailing
newline) shows a deliberately partial plan with only two rules: it redacts one
MAC address and one IPv4 address and then reports three residuals — an
absolute path and an e-mail address in the first artefact, a sixteen-character
identifier in the second. `upload_admitted=false`. With the default six-rule
plan the same input comes out admitted. The values are **example values, not
real device data**.
