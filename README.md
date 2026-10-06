# VettID for Android

The Android client for the VettID vault: a fresh rewrite (2026) of the v1 app,
following the [Android plan](https://github.com/vettid/vettid.org/blob/master/docs/ANDROID-PLAN.md)
and the vault contract in
[VAULT-MESSAGING](https://github.com/vettid/vettid.org/blob/master/docs/VAULT-MESSAGING.md).

**Status: A0–A4 done; A5 (items) and A6 (hardening) to come.** Project skeleton,
design system, component gallery and CI (A0); the protocol crypto (suite 2 with post-quantum
HPKE MLKEM768X25519, passing the vettid-vault test vectors byte for byte),
Android Keystore keys and attestation checks (A1); the relay client, the member
API's alternate channel and the typed vault client (A2,
[devstack/README.md](devstack/README.md)); onboarding, unlock, the Protean
Credential, settings and the biometric app lock (A3); connections, messages and
approvals (A4). Since then: VAULT-MESSAGING 0.10.1–0.10.5 (commit-then-reveal
SAS, connection requests, invitation URLs, a peer's decline), recovery on a new
phone and direct transfer, a replaced phone erasing itself, the pinned
production manifest keys A and B, the daily owner check (VAULT-MESSAGING 0.13.0 §3.6), and
the `staging` build type, which enrolls
against the staging vault service (see *Staging build*). The Items screen is a
placeholder until A5. The v1 app is preserved at the tag `legacy-v1-final` and
the branch `legacy/v1`.

## v1 scope

Enrollment, unlock, the Protean Credential and settings; connections and
messaging; items (secrets and critical secrets, per VAULT-ITEMS); a biometric
app lock. Calls, desktop/agent pairing, wallet, location and presence come later.
Minimum Android 12 (API 31).

## Building

Requirements: JDK 17 or 21 (not 25) and the Android SDK (API 37).

```bash
export JAVA_HOME=/path/to/jdk-21          # e.g. Android Studio's bundled JBR
./gradlew :app:assembleDebug              # debug APK, application id com.vettid.app.dev
./gradlew testDebugUnitTest :core:crypto:test :core:relay:test   # unit tests (incl. the §16 and relay §9 vectors)
./gradlew detekt :app:lintDebug           # static analysis
./gradlew :app:assembleRelease            # R8-minified, unsigned release APK
./gradlew :app:assembleDevStack           # debug app pointed at the local dev stack
./gradlew :app:assembleStaging            # R8-minified app for the STAGING vault service
./gradlew :app:checkReleaseApk :app:checkStagingApk   # each APK carries only its own environment
```

The local dev stack (vettid-vault's integration stack plus a vaultctl peer)
and the instrumented tests against it are described in
[devstack/README.md](devstack/README.md).

Debug builds install next to a release build (`.dev` suffix) and include a
component gallery (drawer → Component gallery). Release builds are signed
outside this repository; no signing material is ever committed.

### Staging build

The `staging` build type is release-like (R8, not debuggable, no debug tools or
`:core:testing`) and talks to the staging vault service: the member API at
`https://account.staging.vettid.org`, the manifest at
`https://staging.vettid.org/.well-known/vettid/pcr-manifest.json` pinned to the
staging manifest key (`ManifestKeys.STAGING`, key_id `e9b3a403423120ac`), the
real AWS Nitro root and the production relay. It shows "VettID Staging" as its
name, `-staging` in the drawer's version line and `staging` under Settings →
attestation → Environment.

Staging images accept device attestation only from the package `com.vettid.app`
signed with the staging key, so the staging build uses the release application
id: it replaces a release install and cannot sit next to one (test phones only).
It is signed from a properties file outside the repository, by default
`~/.vettid/staging-signing.properties` (another path: `-PvettidStagingSigning=<path>`):

```properties
# Absolute, or relative to this file's directory.
storeFile=<keystore path>
# The passwords may be left out and set in the environment instead:
# VETTID_STAGING_STORE_PASSWORD, VETTID_STAGING_KEY_PASSWORD.
storePassword=<...>
keyAlias=<...>
keyPassword=<...>
```

```bash
adb -s <serial> uninstall com.vettid.app   # only if another signature is installed
ANDROID_SERIAL=<serial> ./gradlew --max-workers=2 :app:installStaging
```

Without the file the staging APK is built unsigned (as in CI) and there is no
`installStaging` task.

## Layout

| Module | Contents |
|---|---|
| `:app` | Activity, navigation shell (drawer + type-safe routes), DI root; debug-only gallery in `src/debugTools`; the `devStack` and `staging` build types |
| `:core:ui` | Theme (navy + gold, light/dark), typography, shapes, spacing, components |
| `:core:crypto` | Suite 2 crypto, envelopes, sessions (A1) |
| `:core:keystore` | Android Keystore keys, biometric-gated app-data key (A1) |
| `:core:attestation` | Nitro + Android key attestation (A1) |
| `:core:relay` | Relay client, RELAY-PROTOCOL 0.5.0 (A2; pure JVM) |
| `:core:altchan` | Member API (session, vault routes, recovery) and the alternate channel (A2) |
| `:core:vault` | Typed vault client: sessions, envelopes, outbox, credential, one function per §10 type (A2) |
| `:core:data` | Encrypted local storage, the vault session; repositories and caches (A2–A5) |
| `:core:testing` | TEST ONLY: dev-stack helpers and the A2 exit scenario (test configurations and `devStack` only) |
| `:feature:*` | onboarding, messages, connections, approvals, items, credential, settings |

Features depend only on `:core:*` modules. Shared build configuration lives in
convention plugins under `build-logic/`; versions in `gradle/libs.versions.toml`.

## Licence

AGPL-3.0 — see [LICENSE](LICENSE).
