# Spatial map persistence

`SpatialSnapshotCodec` serializes sparse landmarks and world anchors into a portable `ARKMAP01` byte sequence. It intentionally performs no file I/O and no encryption, so Android, desktop and test hosts can choose their own durable and authenticated storage.

## Binary contract (version 1)

All integers and IEEE-754 doubles are little-endian:

```text
magic[8] = ARKMAP01
uint32 version = 1
int64 created_ns
uint32 landmark_count
uint32 anchor_count
landmark records
anchor records
uint32 crc32
```

Landmarks store feature ID, metric world position, observation count, mean ray residual and last-seen timestamp. Anchors store ID, metric pose and creation timestamp. Plane estimates are recomputed from landmarks and are not persisted as stale derived state.

## Validation and limits

Encoding and decoding enforce:

- positive snapshot and record timestamps;
- finite positions, residuals and quaternions;
- nonzero normalized anchor rotations;
- unique landmark and anchor IDs;
- minimum landmark observation count;
- configurable byte, landmark and anchor limits;
- exact payload length with no trailing fields;
- magic, version and CRC32 validation;
- atomic restore semantics: invalid input never partially replaces a live map.

CRC32 detects accidental corruption only. It does **not** authenticate hostile bytes.

## Android storage requirement

Before writing snapshot bytes to disk, the Android adapter must encrypt and authenticate them with an app-specific AES-GCM key held by Android Keystore. Store nonce, ciphertext and GCM tag in a host-owned envelope; never reuse a nonce with the same key. Decode only after successful GCM authentication. User-controlled maps should be deletable and should not be uploaded by default.
