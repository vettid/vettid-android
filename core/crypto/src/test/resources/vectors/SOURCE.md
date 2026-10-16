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

`calls.json` (VAULT-MESSAGING §10.10, CALLS-PLAN phase C1) was added later and has its own source:

- Source commit: 079cde77a4792f15812c5908d0fa51d68ee2313f (vettid-vault `main`, the merge of PR #67 "Call test
  vectors"; the file is byte for byte the one copied from the PR's commit daac0d32)
- The code it was generated through (`vms/callwire`, `vms/suite`) is identical at that commit and at the tag
  `release/staging/10` (8678752): the vectors describe what staging release 10 does.
- Copied: 2026-10-12. Checked by `CallVectorsTest` in `:core:crypto`.

`unlock.json`, `recovery.json` and `verdicts.json` (VAULT-MESSAGING 0.33.0 §11.14, release R; `verdicts.json` is
vettid-vault's `testdata/passwords/verdicts.json`) come from the **draft** vettid-vault PR #101 (release R 3/3), not
yet merged; copy them again from `main` once it is:

- Source commit: f80e968d5429c9d8165cdecdd2878460be5dc01b (branch `rel-r/3-unlock`)
- Copied: 2026-10-15. Checked by `ReleaseRVectorsTest` (the phone's unlock secret per epoch, the recovery `secrets`
  string, the recovery open's signing string) and `PasswordRulesTest` (every verdict, at the vault's floor and at the
  app's) in `:core:crypto`. The estimator's word lists in `src/main/resources/passwords/` are vettid-vault's
  `vms/recovery/common-passwords.txt` and `estimator-words.txt` at the same commit (last changed in 49525df).

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
94cb55fca6b63e5983474a7b9715acd4f4d9ce8930a1a801d09efb9e73d54d01  calls.json
0073c578252ba87960b74f6a1825b6dbb06f8762c77b7f8003d380b48c373905  unlock.json
157f89fb392ffb74f5cc76671f19b011cf8edd048faecc2ebf3a3d4b948d5eb4  recovery.json
09540a0c78b03fb03cfdee762066a9f1171419d2958798b5b4cf054f7dda66a5  verdicts.json
```
