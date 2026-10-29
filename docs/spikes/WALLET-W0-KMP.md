# Wallet W0 spike: ACINQ bitcoin-kmp

Second spike of 2026-10-29 for ANDROID-PLAN §4 "Wallet", question 19, on equal footing with the BDK spike
(`docs/spikes/WALLET-W0.md` on `spike/wallet-w0`, draft PR #217). Branch `spike/wallet-w0-kmp`, **not for merge**. Same rules: the
vault holds the keys and signs, the phone is watch-only (VAULT-MESSAGING §10.18); all test data is public (the BIP39
"abandon ... about" phrase, the BIP84 and BIP86 vectors); no real phrase, no funds, no phones. Tests:
`core/chain/src/test/.../BitcoinKmpSpikeTest.kt` and `SimVault.kt` (`./gradlew :core:chain:testDebugUnitTest`, JVM, 12 tests, all
pass, no network).

## Verdict

**bitcoin-kmp 0.32.0 passes all four criteria, at one seventh of the APK cost and with a much smaller F-Droid problem, in exchange
for about 900 lines of wallet code of our own (BDK: about 100).** Recommendation: **bitcoin-kmp**, behind a small `:core:chain`
interface, with conditions at the end.

| Criterion | Result |
|---|---|
| Full previous transaction (`non_witness_utxo`) on every input, taproot included | **Passes with no workaround.** `Psbt.updateWitnessInputTx(prevTx, vout, ...)` always sets `non_witness_utxo` (and `witness_utxo`), for P2WPKH and P2TR alike. (BDK never does it for its own taproot inputs; the BDK spike needed a foreign-input workaround.) Tested, and the simulated vault refuses the witness-only variant with `previous_tx`. |
| Both accounts in one PSBT | **Passes, natively.** A PSBT is a list of inputs; each input carries its own derivation. No "one wallet per descriptor" limit, so no foreign-input trick: our `Coin` knows its `Account`. Tested: BIP84 + BIP86 coins, `non_witness_utxo` on both, both derivations, change with its derivation, signed by the simulated vault, finalised, extracted. |
| `foss` flavour | **Passes, with an existing F-Droid recipe.** The only native code is `libsecp256k1-jni.so` (C, 1.3 to 1.5 MB per ABI, MIT library under an Apache-2.0 wrapper). F-Droid already ships an app that builds exactly this library from source: `com.gorunjinian.metrovault`. `:app:checkFossReleaseApk` passes with it in. Details in section 2. |
| Fit | Compiles and tests with AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8. Both APKs build. **+5.9 MB (+10.0%) on `full` staging.** The 64-bit libraries are 16 KB-aligned; the two 32-bit ones are not (not required). |

## Side by side with BDK 3.1.1

| | BDK (`bdk-android` 3.1.1) | bitcoin-kmp 0.32.0 + secp256k1-kmp 0.24.0 |
|---|---|---|
| Nature | Rust core, UniFFI Kotlin bindings, JNA | Kotlin (JVM 8 bytecode, Kotlin multiplatform), one small C JNI library |
| Native code | 3 x `libbdkffi.so` 11 to 16 MB, plus JNA's `libjnidispatch` | 4 x `libsecp256k1-jni.so` 1.3 to 1.5 MB (no JNA) |
| APK, `full` staging (baseline 59,463,318) | 102,337,326: **+42.9 MB (+72%)** | 65,394,635: **+5.93 MB (+10.0%)** (.so 5.52 MB, dex 0.37 MB) |
| APK, `foss` release | 53,084,801 | 16,142,086 (the same app; difference to BDK's about 36.9 MB, as expected from the staging numbers) |
| 16 KB alignment | libs yes; JNA needs a pin to 5.19.1 | arm64-v8a and x86_64 yes; armeabi-v7a and x86 no (4 KB) |
| F-Droid | prebuilt `.so` not accepted; a Rust + NDK build recipe that we write and keep (Clench does it); every BDK bump is a new native build | C library built by F-Droid's own recipe, existing and recent (MetroVault, secp256k1-kmp 0.24.0, NDK r28c); nothing to invent |
| Licence | MIT OR Apache-2.0; JNA LGPL-2.1 / Apache-2.0 | Apache-2.0 (both); libsecp256k1 MIT |
| `non_witness_utxo` on taproot | no (we add 6 lines, foreign input) | yes |
| Both accounts in one PSBT | foreign-input workaround | native |
| Descriptor parsing | yes (public descriptors) | **no**: ours, 100 lines for the two shapes |
| Coin selection | yes (BnB, largest first, oldest, random) | **no**: ours |
| PSBT building / finalising | yes | updater, signer, finaliser, extractor roles yes (v0), the building and the fee logic are ours |
| Script check on extract | `extractTx` checks the fee rate | `extract()` runs the **script interpreter** with standard flags over every input (taproot included) |
| Esplora client | BDK's fails against mempool.space; use OkHttp | none; use OkHttp (same, not counted) |
| Our own code for production | about 100 lines + an OkHttp Esplora client | **about 900 lines** + the same Esplora client |
| API stability | majors 3.0 to 3.1 in weeks, `Persister`/`Update`/constructors changed | 0.x, minor releases with breaking changes every 2 to 3 months (0.29 Dec 2025, 0.30 Mar, 0.31 Jun, 0.32 Sep 2026); the PSBT/Transaction/KeyPath surface we use is the old, stable part; pinned, we choose when to move |
| Maintainer | BDK foundation | ACINQ (Phoenix); the library is the base of their production wallet and Lightning node |
| Main risks | 41 MB cost; Rust toolchain in F-Droid; JNA layer; second TLS stack | **we own wallet logic** (selection, fees, descriptors); 0.x API; the 32-bit `.so` unaligned |

## 1. Fit with the build

- **Version:** `fr.acinq.bitcoin:bitcoin-kmp:0.32.0` (latest stable, 2026-09-22, Kotlin 2.3.10 stdlib which resolves up to ours) with
  `fr.acinq.secp256k1:secp256k1-kmp:0.24.0` (2026-08-13) as its dependency and
  `fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0` chosen by us (ACINQ's rule: the application picks the JNI binding). Pinned in
  `gradle/libs.versions.toml`. SHA-256 of `secp256k1-kmp-jni-android-0.24.0.aar`:
  `a4d75fc5221aee205de869c495983699a943e44ee0331142494296303037e090`; of `bitcoin-kmp-jvm-0.32.0.jar`:
  `581d9285427fc218c675cfccd0b41956a37e277cd4ac31d70eabf72e539a8ea9`. (Pin them as `:core:calls` does for libwebrtc, if adopted.)
- **minSdk / compileSdk:** the AAR's manifest says minSdk 21, the Kotlin classes are Java 8 bytecode (major 52); ours is 31 / 37.
  bitcoin-kmp publishes no Android variant; Gradle resolves its `jvm` variant for the Android module without any trick.
- **Builds with this repo:** AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8: `:core:chain` (new module) compiles, 12 unit tests pass,
  `:app:assembleFullStaging` (R8 on) and `:app:assembleFossRelease` build, `:app:checkFossReleaseApk` passes. No R8 flag was needed
  beyond the library's own `proguard.txt` (keeps `fr.acinq.secp256k1.jni.**`). One thing to know: with R8 and nothing calling the library,
  R8 removes bitcoin-kmp altogether (dex +2 KB), so the measurement below uses `-keep class fr.acinq.** { *; }` in
  `core/chain/consumer-rules.pro` (spike only) to show the full cost; real use will keep less (the 2,048-word BIP39 list and the script
  interpreter are the large parts; a wallet that never builds a mnemonic could shed the list).
- **Native library:** `libsecp256k1-jni.so`, stripped, no debug sections, no build-machine paths (0 hits), with a GNU build id:
  arm64-v8a 1,403,192; armeabi-v7a 1,282,296; x86_64 1,483,560; x86 1,353,248 bytes; needs only `libm`, `libdl`, `libc`.
  **Alignment (LOAD segment alignment, read with `readelf`)**: arm64-v8a and x86_64 `0x4000` (16 KB); armeabi-v7a and x86 `0x1000`
  (4 KB). Google Play's 16 KB requirement concerns 64-bit builds (stated from my knowledge of the policy, not re-checked here), so
  this is expected to be fine; if the owner wants all four aligned, the F-Droid recipe builds the library anyway and can set
  `-Wl,-z,max-page-size=16384` (a one-line CMake change, the same place MetroVault edits). ACINQ's loader finds the library with
  `System.loadLibrary`, no JNA.
- **APK size, `full` staging** (universal, native libraries stored uncompressed, as today):

| | bytes |
|---|---|
| Staging without bitcoin-kmp (my baseline at master 7ae1f912; equals the BDK spike's baseline at 87f9f7ce) | 59,463,318 |
| Staging with bitcoin-kmp 0.32.0 + secp256k1 JNI 0.24.0, whole library kept | 65,394,635 |
| **Increase** | **5,931,317 (about 5.66 MiB, +10.0%)** |

  Of that: four `.so` 5,522,296; dex 8,122,332 to 8,492,152 (+369,820); the rest is zip and metadata. `foss` release with it: 16,142,086
  bytes (no baseline built; the part that belongs to the library is the same 5.9 MB). Per-ABI (a Play AAB split) the cost is
  1.3 to 1.5 MB of native code plus the dex. On the wire the `.so` files compress to about half (inferred, not measured).

## 2. F-Droid `foss`

- **Native code:** yes. bitcoin-kmp itself is pure Kotlin (no JNI); its dependency secp256k1-kmp wraps bitcoin-core's libsecp256k1
  (C) and ships `secp256k1-kmp-jni-android` with the JNI `.so` for four ABIs (this is the only native code). The JNI `.so` is
  built by ACINQ's CI from `native/build.sh` and `jni/` with CMake and the NDK (clang 19.0.1, which is NDK r28, read from the binary).
- **Can F-Droid build it from source, and does any F-Droid app ship it? Yes to both.** `com.gorunjinian.metrovault` (MetroVault, GPL-3.0,
  a Bitcoin wallet) is in F-Droid (fdroiddata `metadata/com.gorunjinian.metrovault.yml`, last build 4.0.0). Its dependencies are exactly
  `secp256k1-kmp-jni-android 0.24.0` (and `secp256k1-kmp-jni-jvm 0.24.0` for tests). Its recipe: `srclibs: Secp256k1Kmp@v0.24.0`
  (git `ACINQ/secp256k1-kmp`, `Prepare: git submodule update --init --recursive`, so libsecp256k1 comes from source),
  `sudo: apt-get install make g++ cmake`, `sdkmanager "cmake;3.31.5"`, `ndk: r28c`, then `gradle :jni:android:publishToMavenLocal`
  inside the srclib, the app's `settings.gradle.kts` gets `mavenLocal()` first; `prebuild` adds `-Wl,--build-id=none` to the CMake link
  options (so the library has no varying build id) and two CMake defines (`SECP256K1_ECMULT_WINDOW_SIZE=4`, `ECMULT_GEN_KB=2`, a size
  trade). The metadata also lists `Binaries:` (a reference APK) with `AllowedAPKSigningKeys`, which is F-Droid's reproducible-build
  verification. We can copy this recipe. Not verified: I did not run F-Droid's build server, and I did not confirm that the
  reproducibility check for MetroVault currently passes (inferred from the metadata).
  (No other F-Droid app in the metadata mentions secp256k1 or `fr.acinq`; Phoenix itself is not there.)
  bitcoin-kmp (Kotlin, Maven Central jar) needs no native step: F-Droid builds Kotlin dependencies from source or accepts them from
  Maven Central as it does for the rest of our tree (our existing rule for libwebrtc is stricter and unchanged).
- **Licences:** bitcoin-kmp Apache-2.0 (POM and repo `LICENSE`); secp256k1-kmp Apache-2.0; libsecp256k1 MIT (the C library, from its
  upstream; the MIT text is not in the AAR, so the app's notices page must carry it). Kotlin stdlib Apache-2.0. All free.
- **Google or non-free dependencies:** none. bitcoin-kmp's POM: secp256k1-kmp-jvm and kotlin-stdlib. The AAR: kotlin-stdlib and
  secp256k1-kmp-jni-common. Zero `android` or `google` strings in bitcoin-kmp's jar. `:app:checkFossReleaseApk` passes with the module
  linked into the app: "185 modules, none proprietary; no Google class, file or push permission in the APK" (verified).
- **Reproducible:**
  - *Verified:* the `.so` files in the APK are byte-identical to the ones in the Maven AAR (not re-stripped by AGP, same sizes and
    content), carry no build paths, and have no debug sections. `foss` builds and its checks pass.
  - *Plausible, not verified:* rebuilding the library from source gives the same bytes on F-Droid as MetroVault's recipe shows
    (pinned NDK r28c and CMake 3.31.5, `--build-id=none`); that F-Droid's own `.so` will not equal the Maven one (different toolchain),
    the same position as BDK's. A two-clone `foss` APK equality test was not run (needs no NDK for the Maven-AAR path; the From-source path
    needs the NDK, not installed here). Our `jniLibs.keepDebugSymbols` line (used for libwebrtc) would be added for this library when
    adopted, so that a machine with an NDK does not strip it differently.
  - The Kotlin side is ordinary Gradle output; bitcoin-kmp has no code generation.

## 3. What the app needs, with the vault holding the keys

| Need | bitcoin-kmp | Notes |
|---|---|---|
| Watch-only account from `wallet.get` | **Ours (`Account`, 100 lines)**: a regex for the two shapes the vault gives (`wpkh([fp/84'/c'/0']xpub/0/*)`, `tr(...)`, optional `#checksum`), checksum verified with the library's `Descriptor.checksum`, key decoded with `ExtendedPublicKey.decode` | Refuses anything the vault would not sign: other origins than m/84'/c'/0' or m/86'/c'/0', a key version that does not match the network, a non-account key, receive/change that differ. The library has descriptors only as a writer for BIP84 (`Descriptor.BIP84Descriptors`), no BIP86, no parser. |
| Address derivation | **Yes**, public derivation `derivePublicKey`, `Bitcoin.computeBIP84Address` / `computeBIP86Address` | Vectors pass: BIP84 `bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu` (index 1 and change 0 too), BIP86 `bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr` (index 1 and change 0 too), testnet `tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl` with fingerprint `73c5da0a`, the same strings as `BdkSpikeTest`. This is the plan's "compare with `wallet.address.list`" check. |
| Networks | `Block.Testnet4GenesisBlock`, `Testnet3`, `SignetGenesisBlock`, `LivenetGenesisBlock`; address encodings keyed by genesis hash | **Testnet4 and signet exist** (section 4). |
| Sync | Not in the library | OkHttp Esplora client (not counted). The phone needs `/address/:a/utxo`-style data and **the full previous transaction** (`/tx/:txid/hex`) per coin; `Coin` refuses an output whose script is not the wallet's address at that index. |
| Coin selection | **Ours.** `Spender.build`: largest first, fee from weight, change dropped under 330 sats | Production adds branch-and-bound, manual selection, drain, the 64-input cap. |
| Unsigned PSBT with the vault's fields | **Ours around the library's updater**, tested | P2WPKH input: `non_witness_utxo` + `witness_utxo` + BIP32 derivation `m/84'/c'/0'/{0,1}/i` with fingerprint `73c5da0a`. P2TR input: `non_witness_utxo` + `witness_utxo` + taproot origin (fingerprint, `m/86'/c'/0'/{0,1}/i`, **no leaf hashes**) + internal key. Change output: BIP32 or taproot origin (+ internal key), so the vault does not count it as a payment. RBF sequence, version 2. Standard base64 of BIP174 v0 (`cHNidP8`); round trip byte for byte. |
| Finalise a signed PSBT, extract, broadcast | **Ours (30 lines) around `finalizeWitnessInput` and `extract`** | The vault's `wallet.sign` already returns `tx`; this path is for a PSBT. P2WPKH witness is `[sig, pubkey]`; P2TR key path is the 64-byte signature (65 with a non-default sighash). `extract()` verifies every input with the library's script interpreter. |
| Watch-only cannot sign | By construction | The phone's classes hold an xpub and never a private key; PSBT inputs of the phone have no signatures (tested). |

### What was NOT done / not verified
- Against the dev stack's real `wallet.psbt.inspect`: not run (no dev stack). `SimVault.check` re-implements the reasons of §10.18 that
  this spike's PSBTs can hit (`previous_tx`, `witness_utxo`, `foreign_input`, `derivation_path`, `derivation_key`,
  `derivation_script`, `internal_key`, `input_script`, `output_amount`, `fee`, `fee_rate`, leaf hashes) from the text, so a
  difference with the real vault is possible. **First W1 task, as for BDK: run these PSBTs through the real `wallet.psbt.inspect`.**
- The 65,536-byte PSBT limit: the two-input test PSBT is 644 bytes with tiny parents; real parents are larger. The app must cap
  inputs (not tested, same as BDK).
- Script execution against a node: `extract()` ran bitcoin-kmp's interpreter (taproot key path included), which is not Bitcoin Core.
- Live Esplora sync, mempool.space behaviour, on-device test, F-Droid build server, two-clone reproducible APK.

## 4. Testnet4 and signet

`Block.Testnet4GenesisBlock.hash` = `00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043` (display order; the library
prints a hash in byte order, reversed, so compare with `value.reversed()`), testnet3 `000000000933ea01...f4943`, signet
`00000008819873e9...e1ef6`, mainnet `000000000019d6689c08...e26f`: all four asserted in the test. Address encodings: `bc` for
mainnet, `tb` for testnet3, testnet4 and signet (the library picks by genesis hash; testnet4's `Chain.Testnet4` and `Block.Testnet4GenesisBlock`
exist in 0.32.0). Keys: `tpub` and coin type 1 for all three, as §10.18 says, so the descriptor and the address cannot tell testnet3,
testnet4 and signet apart; **the genesis hash of the Esplora server (the plan's own-server check) is what does**. Proof of the
Esplora URL choice remains a W1 task.

## 5. What we must write ourselves

Built here (main code 290 lines, `core/chain/src/main`; tests 363 lines):

| Piece | Spike | Production estimate |
|---|---|---|
| Descriptor/account parser and validation, network key rules, path/derivation helpers | 100 | 150 |
| Address/script derivation for both account types, gap-limit scan helper (find the highest used index) | 40 | 150 |
| Coin model (script check, weights) and the chain-source mapping (Esplora JSON to `Coin`, previous transactions, confirmations, caching) | 30 | 200 |
| Coin selection: largest first (spike); branch-and-bound, manual selection, drain, the vault's 64-input and PSBT size caps | 50 | 250 |
| PSBT construction with the vault's fields, payee checks (network, dust 330, standard script), RBF, locktime, change target | 70 | 150 |
| Finalise, extract, parse the vault's answer (`tx` or PSBT), txid and display | 30 | 80 |
| Fee estimate/limits (1,000 sat/vB check, server fee-estimate parsing), `bitcoin:` URI parsing for pasted payees | 0 | 100 |
| **Total** | **~290** | **~900 (range 700 to 1,200), plus tests of about the same size** |

Not counted for either library: the OkHttp Esplora client (about 300 to 400 lines: https only, redirects refused, genesis check,
privacy note), which the BDK spike also concluded is needed. BDK's equivalent of the table is about 100 lines (a constructor, six
lines for taproot `non_witness_utxo`, mapping).

Why 900 lines is acceptable here: **the vault re-derives every key and script, requires every previous transaction, checks the fee cap,
and shows its own summary to the member before the password** (§10.18). A bug in our selection or builder can make a PSBT the vault
refuses (a visible error) or choose a poor coin set; it cannot send to an address the member did not see or hide an amount. What it
can get wrong silently is the fee within the vault's 1,000 sat/vB cap and privacy-poor selection, so the test list for production
should include fee-rate properties and selection properties.

## 6. Proof in code

`BitcoinKmpSpikeTest` (12 tests, all pass): BIP84 and BIP86 mainnet vectors; testnet vector and the vault's descriptors (made by
`SimVault.descriptors` as `wallet.get` gives them, parsed back); genesis hashes of testnet4, testnet3, signet, mainnet; descriptor
parser accepts the vault's shapes (also `h` and a valid checksum) and refuses bad checksum, swapped or equal chains, different
accounts, another purpose, a test key on mainnet, other script types and non-wildcards; P2WPKH PSBT carries the full previous
transaction, derivation with the master fingerprint, change derivation; **P2TR PSBT carries `non_witness_utxo`**, taproot origin
without leaf hashes, internal key, change origin; the vault refuses the witness-only taproot PSBT, a lying previous transaction
and a foreign fingerprint; the builder refuses an overspend, a mainnet payee, a dust payee, and drops dust change; **the two-account
PSBT**, signed by the simulated vault (key from the test phrase, at the path in the PSBT), finalised, extracted. For that PSBT:
fee 396 sats at a request of 2 sat/vB, vsize 198 (real fee rate about 2.0), psbt 644 bytes, extracted witnesses `[sig, key]` and
a single 64-byte Schnorr signature; txid `be3e2d439c2f6c4b2ff9668f807434424000f643121d6b0dbb3d16c98aa19d7a`, the same on a second
run (deterministic signing). The BDK test asserts structure and the fee rate band (1.9 to 2.3), not a txid, and BDK shuffles and
selects differently, so the two txids are not comparable; the checks that are comparable (both inputs have `non_witness_utxo`,
both derivations present, change with derivation, base64 prefix, fee rate band, witness shapes) pass with the same bounds.

## 7. Recommendation

**Take bitcoin-kmp for the wallet, keep BDK as the fallback.**

- It costs 5.9 MB, not 42.9 MB, on the universal APK, adds no JNA, no Rust and no second TLS stack, and the one native piece is a
  small C library that F-Droid already builds from source for a published wallet. The BDK spike's own conclusion was that the F-Droid
  Rust recipe is "the single costliest unknown"; here that unknown is a copy of a working recipe.
- It satisfies both vault requirements without workaround (taproot `non_witness_utxo`, mixed accounts in one PSBT), and verifies the
  final transaction with a script interpreter.
- The price is our own wallet logic, about 900 lines, which the vault's policy makes safe to get wrong (fail-closed).
- Conditions: (1) keep all of it behind a `:core:chain` interface with no bitcoin-kmp types outside, so BDK could replace it;
  (2) pin the versions and hashes and review the 0.x API on each bump (changelog breaks every 2 to 3 months); (3) run the W1 vault
  conformance task first (real `wallet.psbt.inspect`); (4) copy MetroVault's recipe into `docs/fdroid/com.vettid.app.yml` when the
  wallet ships in `foss` (and decide whether the 32-bit `.so` alignment matters to the owner); (5) the OkHttp Esplora client and
  its gap-limit scan are W1 work whichever library wins; (6) carry the libsecp256k1 MIT notice in the licence screen.
- Choose BDK instead if the owner values audited coin selection and descriptor handling more than 37 MB and a Rust F-Droid recipe.

## What I verified and what I inferred

**Verified by running it:** the 12 tests; builds of `:app:assembleFullStaging`, `:app:assembleFossRelease` and
`:app:checkFossReleaseApk` with AGP 9.4.1 / Kotlin 2.4.20 / Gradle 9.8; the baseline 59,463,318 and the with-library 65,394,635 byte
sizes and the dex sizes; `.so` sizes, alignment (readelf), absence of debug sections and build paths, and byte identity between
AAR and APK; POM dependencies and licence fields; Maven release dates; the F-Droid metadata (cloned from fdroiddata on 2026-10-29)
and MetroVault's build recipe and its dependency on `secp256k1-kmp-jni-android 0.24.0`; the library's source (read) for the PSBT
updater behaviour.
**Inferred or from memory:** that Play's 16 KB rule is for 64-bit ABIs only; that MetroVault's reproducible check passes today; that the
F-Droid recipe works for us unchanged; compression ratio of the `.so` on the wire; the production line counts; the real
`wallet.psbt.inspect` behaviour (simulated from the text of §10.18).
