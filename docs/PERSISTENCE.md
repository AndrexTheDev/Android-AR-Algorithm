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

## Android authenticated storage

`ArKernelMapStore` implements the Android storage boundary. It generates a non-exportable 256-bit AES key in Android Keystore, requests a fresh provider-generated 96-bit GCM nonce for every write, authenticates the envelope header as AAD, and writes nonce, ciphertext and 128-bit tag through `AtomicFile`. Files live under `noBackupFilesDir`, are limited to the same 64 MiB plaintext budget, and can be deleted together with their key. Decryption happens only after strict magic/version/length checks; a bad GCM tag is surfaced as a security failure.

The native visual runtime exposes bounded two-pass snapshot export and atomic restore through the C ABI/JNI. Restore rebases landmark freshness timestamps into the current Android boot-time clock, clears active frontend tracks, and reserves subsequent feature IDs above all imported IDs. This one-frame tracking reinitialization prevents current session-local IDs from colliding with imported landmarks.

Encryption does not solve spatial relocalization. A snapshot may be restored into `ArKernelSession` only after the running session has been relocalized into the same world frame. The current project does not yet provide that relocalizer; `restoreSpatialMapInCurrentWorld` names this precondition explicitly. Loading an old map into a fresh unrelated VIO origin would produce geometrically valid but wrongly placed anchors and is therefore not an automatic lifecycle action. User-controlled maps are not uploaded by default.
