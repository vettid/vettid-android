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
phone (the account site's recovery QR code scanned, or the 48 characters under it
typed: VAULT-MESSAGING 0.25.0 §11.11.10, see *Recovery: typing the code*) and direct transfer, a replaced phone erasing itself, the pinned
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
attestation → Environment. Its launcher icon, like the debug and dev-stack
builds', carries a white band across the bottom (`app/src/nonProduction`; the
production icon is the brand kit's, unmarked).

The staging build's application id is **`com.vettid.app.staging`**
(`applicationIdSuffix ".staging"`; ANDROID-PLAN 0.1.35 §5, VAULT-MESSAGING
0.26.0 §11.10.8), from the build that goes with staging release S10: staging
images accept device attestation from exactly that package, signed with a
staging key, and production images from `com.vettid.app`. The staging and the
production app therefore install side by side on one phone: nothing is shared
between them (every provider authority and declared permission is under the
application id, the notifications' intents are explicit, `push.register` sends
the application id as `app_id`). What follows the id:

| | release | staging |
|---|---|---|
| Application id | `com.vettid.app` | `com.vettid.app.staging` |
| Same-device setup link (App Link) | `account.vettid.org/vault/enroll/` | `account.staging.vettid.org/vault/enroll/` only (`vettidAccountHost`); verified by `https://account.staging.vettid.org/.well-known/assetlinks.json`, which names `com.vettid.app.staging` |
| Invitation links (`relay.vettid.org/connect`, `vettid://connect`) | verified App Link | claimed, not verified (the relay's statement names production's package): the relay's page opens, and Android asks which app when both are installed |
| Firebase app (`vettidFcm<Type>ApplicationId`) | production's project, package `com.vettid.app` | the staging project's Android app registered for `com.vettid.app.staging` |

`checkStagingApk` fails unless the staging build's merged manifest has that
id, authorities and permissions under it, and the staging account site as its
only account App Link host; `checkReleaseApk` and `checkFossReleaseApk` hold
the release builds to `com.vettid.app` and `account.vettid.org` the same way
(`:app:check<Variant>ApplicationId`). Debug builds keep `com.vettid.app.dev`
and the dev-stack build `com.vettid.app.devstack` (the dev stack's device
policy names those two). The staging id is never published to a store.

A staging build with the old id (`com.vettid.app`) is, to Android, another
app: no update carries its vault to the new build. It keeps unlocking its
vault and can approve the move to S10, but on S10 it can no longer enroll,
receive a transfer or register a recovery. Per vault: move it to S10 with the
old build, install the new build beside it (do not uninstall the old one
first), attach the vault to the new build by a direct transfer (another
phone), a recovery (the code typed or scanned) or a new enrollment, then
uninstall the old build (VAULT-MESSAGING §11.10.8, "Moving the existing
staging vaults").

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
adb -s <serial> uninstall com.vettid.app.staging   # only if another signature is installed
ANDROID_SERIAL=<serial> ./gradlew --max-workers=2 :app:installStaging
```

Without the file the staging APK is built unsigned (as in CI) and there is no
`installStaging` task.

### Signer binding

From VAULT-MESSAGING 0.27.0 (§11.7.1; ANDROID-PLAN 0.1.36 §7) each vault is
bound to the signing certificate of its app, which the enclave reads from the
key attestation. Google Play installs are signed by Google, F-Droid and direct
installs by VettID; the vault names the class (`vettid`, `google_play`,
`development`) and the app supplies the words.

- **The chain at unlock.** A vault enrolled before 0.27.0 has no recorded
  signer. The app sends the device key's certificate chain in the unlock's
  `device_assertion.chain` (the same certificates as at attestation, read from
  Android Keystore: `Attester.chain()`, `DeviceAttestationKey.chain()`) at
  every unlock until its own `device.list` entry shows a `signer`
  (`VaultDevice.signerSeen`, kept with the device state, false again after
  every new attestation, and false again after an unlock answered
  `signer_refused`: a host can serve a header from before the binding, so the
  next unlock carries the chain whatever `device.list` showed earlier). Never
  on a release approval; not part of any signing string; a chain that would
  not fit the request is left out.
- **`signer_refused`** is an unlock result of its own (`UnlockAttempt.SignerRefused`):
  not a wrong PIN, no count and no wait. The unlock screen says "This vault no
  longer accepts this installation of VettID. Update the app, or recover your
  vault with a current version." and offers "How to recover your vault".
- **Settings → Vault status** shows "Installed from: Google Play (signed by
  Google)", "Installed from: F-Droid or direct (signed by VettID)" or
  "Development build"; tapping it explains the two versions and shows the
  certificate's digest. No line when the vault reports no signer.
- **A transfer to an app with another signer** (`device.transfer.pending`
  with `signer_changed`): the old phone names the change before the PIN, with
  Continue and Cancel, and the approval carries `signer`. `signer_unconfirmed`
  (nothing spent or counted, the transfer still open) shows the confirmation
  and retries with the same UTK.
- History and the notifications name `device.signer_bound`,
  `device.signer_changed` and `device.signer_refused` in words.

The vault relies on Android Keystore returning later the chain it returned at
attestation (same leaf key, same serial numbers). On a phone,
`ANDROID_SERIAL=<serial> ./gradlew :core:keystore:connectedDebugAndroidTest`
(`theKeystoreReturnsTheSameChainLaterThatItReturnedAtAttestation`: byte for
byte after signing, through a new `KeyStore` instance and a new key object)
and `:core:altchan:connectedDebugAndroidTest` (`KeystoreAttester.chain()`
against the attestation) check it within one run; they are compiled in CI and
run by hand.

**Capturing a real attestation chain** (debug only, by hand, one phone; no
vault is touched). `AttestationChainDumpTest` makes a throwaway key with the
app's own key generation (`DeviceAttestationKey.generate`: the same
`KeyGenParameterSpec`, StrongBox where the phone has it, a 32-byte challenge,
no attest-key alias), asserts that only the leaf carries the KeyDescription
extension (OID 1.3.6.1.4.1.11129.2.1.17) and that a second read of the chain
is byte-identical, writes the chain as PEM (leaf first) and deletes the key:

```bash
adb -s <serial> logcat -c
ANDROID_SERIAL=<serial> ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g \
  :core:keystore:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vettid.core.keystore.AttestationChainDumpTest
adb -s <serial> logcat -d -s VettIDChainDump:I    # the PEM, between the BEGIN/END VETTID ATTESTATION CHAIN lines
```

The PEM is also collected from the phone into
`core/keystore/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/<device>/vettid-attestation-chain.pem`.
A certificate chain is public data; the test logs and writes nothing else of
the phone. The test installs and removes only the module's own test package
(`com.vettid.core.keystore.test`), never the app.

Binding an existing vault by its chain cannot be observed on the staging
phones: the vaults enrolled before S10 belong to the build with the old
application id, whose chain names the old package (the enclave would answer
`attestation`, so that build must not send one, and it does not: it predates
this code), and every vault the `com.vettid.app.staging` build holds was
attested on S10 or later and is bound from the start. What a phone shows
instead: after enrolling, transferring or recovering to the new build, the
first unlocks carry the chain (the vault ignores it for a bound key) and
succeed, and Settings → Vault status then shows the "Installed from" line. An
unlock answered "This phone could not be verified" there would mean a chain
was judged and failed.

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
If Google push was chosen and stops being usable (Google Play services disabled,
removed or outdated, or an update to a build without it), the on-phone service
runs instead, the status line reads "Google push is not available on this phone,
using the on-phone service", and the vault is told to drop the registration when
it next opens. The choice stays Google push and registers again by itself once
the push service is back.

Google push exists in the `full` distribution only; the `foss` distribution (see
*F-Droid build*) has no Firebase at all. In `full`, only `firebase-messaging` is linked (`:core:push-fcm`): no Analytics, no
Crashlytics, no `FirebaseInitProvider`, automatic token creation and delivery
metrics export off. `./gradlew :app:check<Variant>PushManifest` (run by
`checkReleaseApk` and `checkStagingApk`) fails if a merged manifest carries
anything of Firebase or Google beyond that.

### Recovery: typing the code

A new phone that cannot scan the account site's recovery QR code takes the code
typed (VAULT-MESSAGING 0.25.0 §11.11.10, ANDROID-PLAN 0.1.33): **Type the code
instead** on the recover screens. The site shows 48 characters in 12 groups of
four: a 12-character locator from the member API, the 32-character code and 4
check characters.

- `RecoveryCodes` (`:core:altchan`) normalizes the input (case, spaces, hyphens,
  I and L as 1, O as 0; a U or any other character is refused in the field, never
  guessed) and computes the check characters; `TypedRecoveryCode.parse` verifies
  them with the app's own built-in member API origin. A string that fails is
  never sent and is not a wrong code; it also catches another environment's code
  (a staging code in the production app). The §16 vector is in `RecoveryTypedTest`.
- `MemberApiClient.recoveryClaimByLocator` posts `{locator, app_key}` to
  `/api/vault/recovery/claim`, signed with an empty `vault`; the answer names the
  vault and the recovery, and with the typed code that is the `RecoveryCode` a
  scan gives. From there on (`VaultManager.registerTypedRecovery`) it is the
  scanned path: register, PIN, credential password, the same texts and the same
  count of wrong codes.
- The entry lives in the view model only (never saved state, disk, logs or the
  clipboard: the field offers no copy or cut) and its screen sets `FLAG_SECURE`
  while it is shown (`SecureWindow`, `:core:ui`); no other screen does.

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
the F-Droid APK has VettID's signature, from a key that only VettID holds.
Google Play installs are signed by Google with a different key that Google
holds (Play App Signing), so Google can never sign an update for an F-Droid or
direct install, and moving between Google Play and F-Droid means a transfer or
a recovery, not an update (owner decision 2026-10-11). For that:

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
