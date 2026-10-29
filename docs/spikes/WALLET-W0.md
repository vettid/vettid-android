# Wallet W0 spike: BDK (`bdk-android`)

Spike of 2026-10-28 for ANDROID-PLAN §4 "Wallet", question 19. Branch `spike/wallet-w0`, **not for merge**. Owner decisions
of 2026-10-17 (16 to 21) are taken as given. All test data is public (the BIP39 "abandon ... about" phrase of the BIP84
and BIP86 specs); no real phrase, no funds. Tests: `core/chain/src/test/.../BdkSpikeTest.kt`
(`./gradlew :core:chain:testDebugUnitTest`, JVM, 8 tests, no network unless `VETTID_W0_LIVE=1`).

## Verdict

**Yes, BDK 3.1.1, with three conditions.** On the plan's pass criteria:

| Criterion | Result |
|---|---|
| Full previous transaction (`non_witness_utxo`) on every input, taproot too | **Passes, with 6 lines of our code.** BDK never adds it to its *own* taproot inputs (only `witness_utxo`); P2WPKH inputs get it. Taproot coins passed as *foreign* inputs, with `Input.nonWitnessUtxo` set by us, carry it. Tested. |
| Both accounts in one PSBT | **Passes.** One BDK `Wallet` is one descriptor pair, so the PSBT is built by one wallet with the other account's coins added as foreign inputs (`addForeignUtxo`, weight 66 for a taproot key-path input). Both inputs keep their derivations (BIP32 for P2WPKH; taproot origin without leaf hashes, with the wallet's fingerprint and the internal key); change goes to the builder wallet's account and carries its derivation. Tested, including simulated vault signing of both inputs, finalising and extracting. Change to the *other* account: build with that account's wallet as the builder. |
| Reproducible `foss` build with the library | **Plausible, not verified.** See "F-Droid". The `foss` APK builds with BDK and `checkFossReleaseApk` passes. A byte-for-byte rebuild of the native library was not possible here (no NDK), and F-Droid will need it built from source, which is a real piece of work (Clench does it). |

Conditions: (1) the app's own HTTP client (OkHttp) does the Esplora calls, not BDK's (below); (2) JNA pinned to 5.19.1;
(3) a decision on F-Droid native code before the `foss` wallet ships: build `libbdkffi.so` from source in the F-Droid recipe,
or ship the wallet in `full` first. If condition 3 cannot be met, fall back to bitcoin-kmp (below).

## 1. Fit with the build

- **Version:** `org.bitcoindevkit:bdk-android:3.1.1` (latest stable, released 2026-10-08; bdk-ffi tag v3.1.1, Rust `bdk_wallet` 3.1.0). Pinned in `gradle/libs.versions.toml` (`bdk`). AAR SHA-256 `ef1eb6b4b2417a0e0dff74250064a20632bc5a00b276fec02079e3dd0580a48f`. (Pin the hash as `:core:calls` does for libwebrtc, if adopted.)
- **minSdk / compileSdk:** AAR manifest minSdk 24, built with compileSdk 36; ours is 31 / 37. Fine.
- **Builds with this repo:** AGP 9.4.1, Kotlin 2.4.20 (BDK's stdlib is 2.3.10), Gradle 9.8: `:core:chain` (new module, spike) compiles, unit tests run, `:app:assembleFullStaging` (R8 on) and `:app:assembleFossRelease` build.
  - R8 needs `-dontwarn java.awt.**` (JNA's desktop-only AWT helper); in `core/chain/consumer-rules.pro`.
  - BDK's POM drags in `slf4j-api 1.7.30`, `appcompat 1.4.0`, `core-ktx 1.7.0`, `coroutines 1.8.1` (all resolve upward to our versions or are small) and JNA.
- **Native libraries** (`libbdkffi.so`, Rust, stripped of debuginfo only): arm64-v8a 15.6 MB, armeabi-v7a 10.9 MB, x86_64 14.4 MB. **No x86 (32-bit)**. All three have 16 KB ELF alignment.
- **JNA** (BDK talks to Rust through it): 5.14.0 is what BDK asks for, and its `libjnidispatch.so` is 4 KB-aligned for armeabi-v7a and x86_64. **Pin 5.19.1** (`implementation("net.java.dev.jna:jna:5.19.1@aar")`): all ABIs 16 KB-aligned. Verified in the staging APK. It also carries `libjnidispatch` for armeabi, mips, mips64 and x86 (~0.6 MB, useless; can be excluded with `packaging.jniLibs.excludes`).
- **APK size, `full` staging** (universal, native libraries stored uncompressed, as today):

| | bytes |
|---|---|
| Staging without BDK (origin/master 87f9f7ce) | 59,463,318 |
| Staging with BDK 3.1.1 + JNA 5.19.1 | 102,337,326 |
| **Increase** | **42,874,008 (about 40.9 MiB, +72%)** |

  Of that: the three `libbdkffi.so` 40.9 MB, JNA libs about 1.0 MB, dex about +1.0 MB (8,121,228 to 9,137,052). `foss` release with BDK: 53,084,801 bytes (no baseline built; the BDK part is the same 42 MB). Per-ABI the cost would be 11 to 16 MB; the owner's rule is a universal APK (calls), and Play delivers per-ABI splits from an AAB anyway. Download size on the wire will be smaller (the .so files compress to roughly 40%, inferred, not measured).

## 2. F-Droid `foss`

- **FOSS and license:** bdk-ffi `MIT OR Apache-2.0` (POM and `Cargo.toml`), BDK crates the same. Dependencies (rust-bitcoin, miniscript, rustls, ring, uniffi, bdk_kyoto) are permissive FOSS (checked by name; a `cargo deny`/`cargo license` pass is still to do). JNA is LGPL-2.1 / Apache-2.0 (dual).
- **Google or non-free dependency:** none. The BDK POM has no Google Play, Firebase or GMS artifact (`androidx.appcompat/core-ktx` are Apache-2.0 androidx and already common). `:app:checkFossReleaseApk` passes with BDK in (verified, all of its checks including the forbidden strings and the dependency graph).
- **Native code, source or prebuilt:** the Maven Central AAR is a **prebuilt** `.so` built by upstream CI. F-Droid's policy is to build from source; a prebuilt binary dependency is not accepted for native code. **Evidence it works:** F-Droid ships `net.clench.wallet` (fdroiddata `metadata/net.clench.wallet.yml`), which uses `bdk-android` 3.0.0 and, in its `prebuild`, runs `scripts/native/prepare-bdk.py`: it takes the pinned upstream AAR (SHA-256 checked) for the Kotlin bindings, rebuilds the three `libbdkffi.so` from source with the Rust toolchain and an NDK (28.2), and repackages a private local Maven artifact; `scanignore`/`scandelete` cover the build directory and scripts, a `reproducible-apk-tools` `zipalign` post-build step makes the APK deterministic. So: **possible, with a Rust+NDK build recipe we would write and keep up.** Inferred from that metadata; I did not run F-Droid's build server.
- **Reproducible build:**
  - *Verified:* the `.so` files in our APK are byte-identical to the ones in the pinned AAR (they are not re-stripped or rebuilt by AGP here; no NDK on this machine). The `foss` APK builds and its checks pass. The upstream binary embeds build-machine paths (461 `/home/runner...` or `/Users/...` strings), so **a rebuild from source will not equal the Maven binary** unless built with the same toolchain and `--remap-path-prefix`: the F-Droid APK would carry F-Droid's own `.so`, and "reproducible" then means *our* recipe gives the same bytes everywhere, which is how Clench does it (they cite it as the reason for the patched build).
  - *Not verified:* a bit-for-bit rebuild of `libbdkffi.so` (no NDK or cargo-ndk installed; not attempted), and two-clone `foss` APK equality (that is the README's check, and needs the same extra time). `jniLibs.keepDebugSymbols` for the two libraries is added in `app/build.gradle.kts` (as for libwebrtc) so that an NDK on the build machine cannot strip them differently; the line configures and `mergeFossReleaseNativeLibs` runs, but the effect is untested without an NDK.
  - Rust reproducibility needs pinned toolchain version, `Cargo.lock`, `--locked`, `-C strip`, `--remap-path-prefix`, fixed NDK; generally achievable, but it is the single costliest unknown.
- **16 KB pages / Play:** libs are already 16 KB-aligned (with JNA 5.19.1).

## 3. What the app needs from BDK, with the vault holding the keys

All tested in `BdkSpikeTest` (BDK 3.1.1 through `bdk-jvm`, the same Rust and bindings as `bdk-android`).

| Need | BDK | Notes |
|---|---|---|
| Watch-only wallet from the vault's descriptors | **Yes.** `Wallet(Descriptor(recv, kind), Descriptor(change, kind), network, Persister.newInMemory())` | Takes `wpkh([73c5da0a/84'/1'/0']tpub.../0/*)` and `tr(...)` exactly as VAULT-MESSAGING §10.18 `wallet.get` gives them (`accounts[].descriptors.receive/change`). Two accounts = two `Wallet`s on the phone (receive+change each). No xpub parsing of our own. A wallet built from public descriptors has no signer: `sign()` returns false and leaves the PSBT unsigned (tested). |
| Address derivation | **Yes** | Vectors pass: BIP84 `bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu` (and index 1, change 0), BIP86 `bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr` (index 1, change 0), and testnet `tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl` (coin type 1, fingerprint `73c5da0a`, matching the §10.18 example). This is the plan's "compare with `wallet.address.list`" check. |
| Network | `Network.BITCOIN, TESTNET, TESTNET4, SIGNET, REGTEST` | **TESTNET4 exists** and selects testnet4's genesis (`00000000da84f2ba...f043`, tested), TESTNET3 and SIGNET theirs. The Esplora client takes only a URL, so testnet4 depends on the server. `Descriptor(..., NetworkKind.TEST)` for all test networks (same `tpub`). |
| Sync from an Esplora URL | **Yes, with caveats** | `EsploraClient(url, proxy).fullScan(wallet.startFullScan().build(), stopGap, parallel)` then `wallet.applyUpdate`. Live: works against `blockstream.info/signet/api` (tested). **Against mempool.space (the owner-accepted default) BDK's client does not work:** `getFeeEstimates()` fails (`HttpResponse` 203: mempool.space answers `/fee-estimates` with 203 and BDK takes only 200); `fullScan` against both `testnet4` and `signet` did not finish in 90 s (cause not found; `getHeight` works). `curl` shows normal 200s for the other endpoints, so the server is fine. Also BDK does its own TLS (rustls + ring, bundled roots), redirects and proxying, which bypass the plan's rules (https only, redirects refused, own-server genesis check, privacy note). **Recommendation: our own Esplora client on OkHttp in `:core:chain` (the plan already lists one).** Feed BDK what it returns: full transactions via `wallet.applyUnconfirmedTxs` (what the tests do; the PSBT builder spends them) or, to keep confirmation heights, BDK's `Update` types (not constructible from Kotlin, so heights/confirmed balance come from our client). Then BDK is a descriptor, address, coin selection and PSBT engine, not a network client. |
| Balance, UTXOs | **Yes** | `wallet.balance()`, `listUnspent()` (outpoint, txout, keychain, derivation index). With our own client the confirmed/unconfirmed split comes from Esplora's `status`. |
| Unsigned PSBT with fee rate and coin selection | **Yes** | `TxBuilder().addRecipient(script, amount).feeRate(FeeRate.fromSatPerVb(n)).coinSelection(BRANCH_AND_BOUND, LARGEST_FIRST, OLDEST_FIRST, SINGLE_RANDOM_DRAW).finish(wallet)`; also `addUtxos`, `manuallySelectedOnly`, `drainWallet/drainTo`, `excludeUnconfirmed`, `doNotSpendChange`, RBF sequence, nLockTime. `Psbt.serialize()` is standard base64 BIP174 v0 (starts `cHNidP8`), what `wallet.psbt.inspect` and `wallet.sign` take; fee reached ~2.0 sat/vB for a request of 2. |
| Vault policy fields | **Yes, after the taproot fix** | P2WPKH input: `non_witness_utxo` + `witness_utxo` (equal; allowed) + BIP32 derivation with the wallet's fingerprint. P2TR input: taproot origin (fingerprint, `86'/1'/0'/0/i`, **no leaf hashes**) and internal key, but **no `non_witness_utxo`** (fixed as above). Change output: derivation present (BIP32 or taproot origin). No script-path data is added. The 65,536-byte PSBT limit: `non_witness_utxo` carries whole previous transactions, so a spend from many large-parent coins can hit it; the app must cap inputs (not tested). |
| Finalise a PSBT the vault signed, extract, broadcast | **Yes** | The vault's `wallet.sign` already returns the final `tx`; the app only needs `Transaction(bytes)` and a broadcast (`POST /tx` in our client). If it returns a PSBT instead: `psbt.finalize()` then `extractTx()` (tested with a simulated signer using BDK's own signer on the test phrase; `extractTx()` also checks the fee-rate limit). |

Everything in the phone runs without any private key. (The signer used by the tests, built from the public test phrase, stands in for the vault.)

### Not done / not verified
- Against the dev stack's `wallet.psbt.inspect` (the plan asks for it): not run; no dev stack in this spike. The fields were checked against the §10.18 signing policy by reading, and the simulated signer accepted the PSBT. **First task of W1:** run these PSBTs through the real `wallet.psbt.inspect`.
- Script execution of the extracted transaction against a node.
- On-device test (no phones, as instructed).
- mempool.space `fullScan` hang cause.

## 4. Proof in code

`core/chain/src/test/kotlin/com/vettid/core/chain/BdkSpikeTest.kt` (8 tests, all pass; the live one is skipped unless `VETTID_W0_LIVE=1`): BIP84, BIP86 and testnet vectors; testnet4/testnet3/signet genesis; watch-only wallet cannot sign; P2WPKH PSBT has `non_witness_utxo`; own P2TR input does not (finding), but has the wanted origin; the two-account PSBT with full previous transactions on both inputs, signed, finalised and extracted. `core/chain/src/main/.../WatchOnly.kt` is the 5-line constructor. `app/build.gradle.kts` links `:core:chain` into every build type only to measure the APK; drop it when the spike is closed.

## 5. Risks and alternatives

BDK's risks: a 41 MB native cost on a universal APK, a Rust toolchain in the F-Droid recipe (the largest cost, and every BDK bump means a new native build and a new inspection), JNA as a runtime layer (and its page-alignment history), a fast-moving API (3.0 to 3.1 within weeks; `Persister`, `Update` and the constructors changed between majors), and a second HTTP/TLS stack inside the process unless we avoid its client. **Alternative, ACINQ bitcoin-kmp (+ secp256k1-kmp)**: Kotlin, Apache-2.0, ships a JNI secp256k1 (small, C; F-Droid-buildable from source, as other Lightning wallets show) and is much smaller (about 2 to 3 MB), has descriptors-free derivation (BIP32/39, bech32m, taproot key-path hashing, PSBT v0 types) but **no descriptor parser, no coin selection, no Esplora client in the core**: we would write the descriptor subset the vault emits (two shapes), coin selection, and the PSBT fields above. That is a few hundred lines of our own code, all testable against the same vectors, and it keeps the wallet pure Kotlin on the phone. By-hand secp256k1 plus our own PSBT code is the same plus more risk, and not recommended. I did not run bitcoin-kmp, so its claims here are from memory and need their own check if BDK is rejected. My advice: take BDK for `full` now and put the F-Droid native build on the W1 task list; if the F-Droid recipe turns out heavier than the owner wants, switch the library under the same `:core:chain` interface (keep BDK types out of the rest of the app).
