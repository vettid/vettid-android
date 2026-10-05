# Test vectors (copied, do not edit)

These files are copied verbatim from vettid-vault `testdata/vectors/`
(VAULT-MESSAGING 0.10.4 §16). They are the authoritative cross-implementation
vectors: the Kotlin code must reproduce every value byte for byte
(`VectorsTest` in `:core:crypto`, `ReleaseVectorsTest` in
`:core:attestation`). All keys and seeds in them are fixed, public and
TEST-ONLY.

- Source repository: github.com/vettid/vettid-vault
- Source commit (HEAD when copied): 19c03153e2ecda009f449cb26d13929f34085783
- Last commit touching testdata/vectors: 19c03153e2ecda009f449cb26d13929f34085783 (handshake.json for the 0.10.3 SAS commitment)
- Copied: 2026-10-05 (handshake.json; the other files are unchanged since 2026-10-04)

To refresh: copy the files again from a newer vettid-vault commit, update the
commits above, and run `./gradlew :core:crypto:test :core:attestation:testDebugUnitTest`.
A vector that no longer matches is a stop-and-report item, never a skip.

`sha256sum` of the copied files:

```
ba4776070f7babc465d4db23f860fe998f6d7f3accab5cac8a153ff0090d3914  altchan.json
3ab4f20eebb3ec621e97d3b03705c8b3851312e99e227d423e8f0e6066887360  envelope_sealed.json
8cbf6324c294e6c99011bd3bd5f875fcd329cbe7be1e354b7be06f3c03044827  envelope_session.json
9b69503b507fd117ab3d6a66368cb60bb24e09286867921a6f473fb476fb0577  handshake.json
08fa20d793577a27196f37e9fe5f5222bbcd90e5adabc015679be3d98b47b226  hpke.json
6d7be9ba65f8426070ee54f0ea6ed84fed1e49c54eb31e09a1e177d3d5286d2f  invite.json
6025085eb23f7ce100de09389d2a087ce8384b638461bc9891cfd5ad6db44570  keys.json
29060418b258c813a8304047b826f70ec87e66ae10ea1c4e4ffc10d213eebb96  release.json
```
