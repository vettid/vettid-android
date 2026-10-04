# VettID for Android

The Android client for the VettID vault: a fresh rewrite (2026) of the v1 app,
following the [Android plan](https://github.com/vettid/vettid.org/blob/master/docs/ANDROID-PLAN.md)
and the vault contract in
[VAULT-MESSAGING](https://github.com/vettid/vettid.org/blob/master/docs/VAULT-MESSAGING.md).

**Status: phase A0** — project skeleton, design system, component gallery and CI.
There is no backend connection yet. The v1 app is preserved at the tag
`legacy-v1-final` and the branch `legacy/v1`.

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
./gradlew testDebugUnitTest               # unit tests
./gradlew detekt :app:lintDebug           # static analysis
./gradlew :app:assembleRelease            # R8-minified, unsigned release APK
```

Debug builds install next to a release build (`.dev` suffix) and include a
component gallery (drawer → Component gallery). Release builds are signed
outside this repository; no signing material is ever committed.

## Layout

| Module | Contents |
|---|---|
| `:app` | Activity, navigation shell (drawer + type-safe routes), DI root; debug-only gallery in `src/debug` |
| `:core:ui` | Theme (navy + gold, light/dark), typography, shapes, spacing, components |
| `:core:crypto` | Suite 2 crypto, envelopes, sessions (A1) |
| `:core:keystore` | Android Keystore keys, biometric-gated app-data key (A1) |
| `:core:attestation` | Nitro + Android key attestation (A1) |
| `:core:relay` | Relay client (A2) |
| `:core:altchan` | Member-API alternate channel (A2) |
| `:core:vault` | Typed vault client (A2) |
| `:core:data` | Repositories and encrypted caches (A2–A5) |
| `:feature:*` | onboarding, messages, connections, approvals, items, credential, settings |

Features depend only on `:core:*` modules. Shared build configuration lives in
convention plugins under `build-logic/`; versions in `gradle/libs.versions.toml`.

## Licence

AGPL-3.0 — see [LICENSE](LICENSE).
