# Signature verification (`ARKSIGN1`)

`ARKSIGN1` binds the SHA-256 digests of one or more artefacts into one signed
manifest, so that a list of measurement files can be checked for integrity
later — by the app itself and by anyone who has the public key.

## Key handling

The signing key is an **EC P-256** key (secp256r1) and the signature is
**SHA-256 with ECDSA**, DER-encoded and carried base64 in the manifest.

- The private key is generated in the **Android keystore** where the device
  offers one, so it cannot be exported.
- Only if that fails the app falls back to a **software key** stored
  base64-encoded in its own private files directory. The sandbox protects it,
  but it is not hardware backed.
- Which of the two was used is written into every manifest as `key_origin`
  (`android_keystore` or `software`), so a weak key is never silently
  presented as a strong one.

## What is signed

The digest pre-image is the canonical manifest with `manifest_digest=` and
`signature=` left empty, so the digest is not circular:

1. `preImage` = canonical block, `manifest_digest=` empty, `signature=` empty;
2. `manifest_digest` = SHA-256 of `preImage`;
3. `signedText` = same block with the digest filled in, `signature=` still
   empty;
4. `signature` = ECDSA-SHA-256 over `signedText`.

Verification rebuilds `signedText` from the parsed entries, checks the digest
of `preImage` against the declared `manifest_digest` (on parse) and then
checks the signature. The fingerprint of the public key is part of the signed
text, so a manifest cannot be re-attributed to a different key.

## What this proves — and what it does not

**It proves:**

- the entries were not changed after signing;
- the digest list was not swapped;
- the same key keeps signing, because the same fingerprint keeps appearing.

**It does not prove:**

- **who owns the key.** There is no certificate authority, no trust chain and
  no device attestation. A rooted device or a copied software key file
  produces a perfectly valid signature.
- **anything about measurement quality.** A verified manifest says the bytes
  are unchanged, not that the numbers are right. The grade stays
  `unverified` and `metric_accuracy_claimed` stays false.

The public key is deliberately **not** part of the manifest; it is exported as
its own file and has to be pinned by whoever verifies. Verification reports
`keyFingerprintMatches` and `signatureValid` separately, so "signed by a
different key" and "signature broken" are never conflated.

## In the diagnostics app

Button **15. Signatur & Prüfung (ARKSIGN1)** opens `SignatureActivity`:

1. **Schlüssel erzeugen** — shows origin and fingerprint;
2. **Öffentlichen Schlüssel speichern** — writes
   `ARKSIGN1_pubkey_<fingerprint8>.txt` to `Downloads/ARKernelDiagnostics`;
   upload it once so a verifier can pin it;
3. per artefact: enter a name, paste the `ARK…` text, **Eintrag hinzufügen**
   (up to 32 entries, names and texts must be unique);
4. **Manifest signieren** — the app signs and immediately self-verifies before
   it lets you save;
5. **ARKSIGN1 speichern** / **teilen**;
6. to check someone else's manifest: paste it and press **Manifest prüfen**;
   optionally paste a foreign public key, otherwise the pinned local key is
   used.

## Example

`docs/examples/ARKSIGN1_example.txt` (873 bytes including the trailing
newline) binds two artefact digests and carries a real ECDSA signature over
the canonical text. `docs/examples/ARKSIGN1_publickey.txt` holds the matching
public key (base64 SPKI) with its fingerprint
`801e799a4788a4cb4e93d0470c63d72c1363a39c17dcbf02bc836dbcd661a338`. The
signature verifies against that key; the key pair was generated for this
example only and signs nothing else.
