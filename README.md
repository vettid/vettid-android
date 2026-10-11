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
./gradlew :app:assembleFullRelease        # R8-minified, unsigned release APK (Google Play, VettID's releases)
./gradlew :app:assembleFossRelease        # the same app without any Google code (F-Droid; see "F-Droid build")
./gradlew :app:assembleDevStack           # debug app pointed at the local dev stack
./gradlew :app:assembleStaging            # R8-minified app for the STAGING vault service
./gradlew :app:checkReleaseApk :app:checkStagingApk   # each APK carries only its own environment
./gradlew :app:checkFossReleaseApk        # the F-Droid APK carries nothing of Google
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

### Google push

Settings → App → Notifications offers three modes: the on-phone service (the
default), **Google push** and Off (`docs/ANDROID-PLAN.md` §4 "Notification
modes" in the vettid.org repository). Google push uses Firebase Cloud Messaging
for wakes that carry no content: the app sends its push token to the member's own
vault (`push.register`), the vault asks the VettID push gateway for a wake, and
on a wake the app collects from its relay and decrypts on the phone as the
on-phone service does. The app never talks to the gateway.

There is no `google-services.json` and no Google Services Gradle plugin. A build
gets Google push from four **Gradle properties per build type** (the values of
the Firebase console's app settings; they identify the app and are not secrets,
but they are not committed):

| Gradle property (`<Type>` = `Debug`, `DevStack`, `Staging`, `Release`) | Firebase option |
|---|---|
| `vettidFcm<Type>ApplicationId` | the app's Firebase application id (`1:…:android:…`) |
| `vettidFcm<Type>ProjectId` | the project id (`vettid-staging`, `vettid-prod`) |
| `vettidFcm<Type>ApiKey` | the Android API key |
| `vettidFcm<Type>SenderId` | the sender id (project number) |

Set them in `~/.gradle/gradle.properties`, with `-P` on the command line, or in
CI as secrets exported as `ORG_GRADLE_PROJECT_<property>` environment variables
(for example `ORG_GRADLE_PROJECT_vettidFcmStagingProjectId`). All four of a build
type or none: a partial set fails the build. Staging and production use separate
Firebase projects (PUSH-GATEWAY §10.4); `checkReleaseApk` and `checkStagingApk`
fail if an APK carries the other environment's Firebase application id.

**Without the properties** (the default, and CI today) the build is as before:
Google push is shown as "Not available yet", Firebase is never initialised, its
push receiver and services are disabled in the manifest, and nothing contacts
Google. With them, Google is first contacted when the member chooses Google push.
On a phone without Google Play services (GrapheneOS without sandboxed Play) the
choice reads "Needs Google Play services, which this phone does not have".

Google push exists in the `full` distribution only; the `foss` distribution (see
*F-Droid build*) has no Firebase at all. In `full`, only `firebase-messaging` is linked (`:core:push-fcm`): no Analytics, no
Crashlytics, no `FirebaseInitProvider`, automatic token creation and delivery
metrics export off. `./gradlew :app:check<Variant>PushManifest` (run by
`checkReleaseApk` and `checkStagingApk`) fails if a merged manifest carries
anything of Firebase or Google beyond that.

### F-Droid build

The app is built in two **distributions** (a product flavour dimension,
`distribution`), with the same application id, version and source:

| | `full` | `foss` |
|---|---|---|
| For | Google Play, VettID's own releases | F-Droid |
| Google push | linked (`:core:push-fcm`: Firebase Cloud Messaging, Google Play services' availability check, WorkManager), off unless the build has the Firebase options | not in the build |
| Build types | debug, devStack, staging, release | release only |
| Release APK | `app/build/outputs/apk/full/release/app-full-release-unsigned.apk` | `app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk` |

```bash
./gradlew :app:assembleFossRelease :app:checkFossReleaseApk
```

The `foss` APK contains **no Google proprietary code**: no Firebase, no Google
Play services, no datatransport, no Play library, no WorkManager, no
`c2dm` permission and no Google component in its manifest. Notifications reach
the phone through the **on-phone service**, which is complete in this build;
under Settings → App → Notifications, *Google push* is shown disabled with
"Not available in this build of VettID, which contains no Google software."
Everything else is the same code.

`checkFossReleaseApk` fails the build if that stops being true. It reads the
variant's resolved dependency graph (any module of `com.google.firebase`,
`com.google.android.gms`, datatransport, Play, ML Kit, install referrer,
billing, or `androidx.work`), the APK's dex (classes of those packages, the
push permission's name), the APK's file list (the `firebase-*.properties` and
`play-services-*.properties` those libraries ship) and the merged manifest (any
`com.google.*` permission, component, registrar, package query or intent
action). One Google name is allowed in the dex: the string
`com.google.android.gms.org.conscrypt`, which OkHttp (Apache-2.0) carries to
look for a TLS provider by reflection; it is no Google code.

How the two are kept apart:

- `app/src/full` and `app/src/foss` each hold a `DistributionApplication` (the
  application's base class; WorkManager's configuration in `full`) and a
  `DistributionModule` (Hilt: the `PushProvider` and what runs for it at
  start). `foss` binds `NoGooglePushProvider` (`:core:notify`), whose
  availability is `NOT_IN_THIS_BUILD`. Firebase's manifest entries are in
  `app/src/full/AndroidManifest.xml`.
- `:core:push-fcm` is the only place that names the Google libraries (their
  coordinates and versions are in its `build.gradle.kts`, not in the version
  catalogue), and only `fullImplementation` depends on it. `settings.gradle.kts`
  includes the module only if its directory exists, so an F-Droid build can
  delete `core/push-fcm` before building and has no proprietary coordinate left
  in the tree.
- Only `fossRelease` exists of `foss` (`androidComponents.beforeVariants`):
  debug, devStack and staging are `full` only, so nothing is built or tested
  twice. The `full` tasks keep their earlier names through aliases
  (`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:installStaging`,
  `:app:connectedDevStackAndroidTest`, …), and `checkReleaseApk` /
  `checkStagingApk` are the `full` checks as before.

**Reproducible builds.** F-Droid is to publish the APK that VettID signs,
after building the same commit itself and finding its own APK identical apart
from the signature (owner decision 2026-10-11). The vault accepts an app only
if its signing certificate is one the vault release pins (`android_signers`),
so an APK signed with F-Droid's own key could not open a vault; with this route
the F-Droid APK has VettID's signature. For that:

- The unsigned `fossRelease` APK must be the same bytes wherever it is built.
  It is, for a given JDK and commit: no build time, path, user, locale or time
  zone goes into it, the release build types carry no
  `META-INF/version-control-info.textproto` (`vcsInfo.include = false`: its
  content depends on how the source was checked out), and there is no
  dependency metadata block (`dependenciesInfo`, off for the APK and the
  bundle). Check it with two clean builds:
  `./gradlew --no-build-cache :app:assembleFossRelease` in two fresh clones,
  then `sha256sum` the two APKs. Build releases with **JDK 21**, as the recipe
  does.
- Each release is a tag `v<versionName>` on the commit that sets `versionCode`
  and `versionName` in `app/build.gradle.kts` (literals: F-Droid's update check
  reads them from the tag).
- The **signed** `foss` APK of each release is published at a stable URL (a
  GitHub release asset, `vettid-<versionName>-foss.apk`). It is signed outside
  this repository, like every release, with a certificate the production vault
  release pins.
- The draft F-Droid metadata is [docs/fdroid/com.vettid.app.yml](docs/fdroid/com.vettid.app.yml)
  (`Binaries`, `AllowedAPKSigningKeys`); it has not been submitted.

There is no store listing text in this repository yet. F-Droid reads it from
`fastlane/metadata/android/en-US/` (`title.txt`, `short_description.txt` of at
most 80 characters, `full_description.txt`, `images/icon.png`,
`images/phoneScreenshots/`, `changelogs/<versionCode>.txt`).

## Layout

| Module | Contents |
|---|---|
| `:app` | Activity, navigation shell (drawer + type-safe routes), DI root; debug-only gallery in `src/debugTools`; the `devStack` and `staging` build types; the `full` and `foss` distributions (`src/full`, `src/foss`) |
| `:core:ui` | Theme (navy + gold, light/dark), typography, shapes, spacing, components |
| `:core:crypto` | Suite 2 crypto, envelopes, sessions (A1) |
| `:core:keystore` | Android Keystore keys, biometric-gated app-data key (A1) |
| `:core:attestation` | Nitro + Android key attestation (A1) |
| `:core:relay` | Relay client, RELAY-PROTOCOL 0.5.0 (A2; pure JVM) |
| `:core:altchan` | Member API (session, vault routes, recovery) and the alternate channel (A2) |
| `:core:vault` | Typed vault client: sessions, envelopes, outbox, credential, one function per §10 type (A2) |
| `:core:data` | Encrypted local storage, the vault session; repositories and caches (A2–A5) |
| `:core:notify` | The notification modes: the on-phone service, channels, the `PushProvider` interface, the registration with the vault |
| `:core:push-fcm` | Google push (Firebase Cloud Messaging): the `full` distribution only, the only module with Google code |
| `:core:testing` | TEST ONLY: dev-stack helpers and the A2 exit scenario (test configurations and `devStack` only) |
| `:feature:*` | onboarding, messages, connections, approvals, items, credential, settings |

Features depend only on `:core:*` modules. Shared build configuration lives in
convention plugins under `build-logic/`; versions in `gradle/libs.versions.toml`.

## Licence

AGPL-3.0 — see [LICENSE](LICENSE).
