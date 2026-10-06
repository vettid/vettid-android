# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

The VettID Android app, **rewritten from scratch in 2026** (phases A0–A4 done:
skeleton, design system, CI; crypto, keystore, attestation; relay, alternate
channel and vault client against a local dev stack; onboarding, unlock, credential,
settings screens and the biometric app lock; connections, invitations with QR code
and safety code, messages and approvals). The plan and decisions are in the vettid.org repo:
`docs/ANDROID-PLAN.md` (D1–D6, design language §3, screens §4, modules §5, phases §6).
The app's contract with the vault is `docs/VAULT-MESSAGING.md`; items/tags/share
rules are `docs/VAULT-ITEMS.md`. The v1 app (NATS, vettid.dev) is on tag
`legacy-v1-final` / branch `legacy/v1`; port code from there only where the plan
says so (attestation, crypto helpers, WebRTC, QR scanner), with its tests.

## Build

- JDK 17 or 21 (system JDK 25 does not work with AGP). Locally:
  `export JAVA_HOME=/opt/android-studio/jbr`.
- Shared machine: memory and workers are capped in `gradle.properties`. Run
  builds with `--max-workers=2`, check `free -g` first (wait if < 8 GB
  available), and `./gradlew --stop` when done. No emulator.
- `./gradlew :app:assembleDebug :app:assembleDevStack testDebugUnitTest :core:crypto:test :core:relay:test detekt :app:lintDebug :app:assembleRelease :app:assembleStaging :app:checkReleaseApk :app:checkStagingApk`
  is what CI runs (`.github/workflows/ci.yml`, plus gitleaks over full history, with
  `.gitleaks.toml` allowlisting the public §16 test-vector values).
- `:core:crypto` is a pure Kotlin/JVM module (`vettid.jvm.library`); its tests include the
  vettid-vault §16 vectors (`src/test/resources/vectors`, see `SOURCE.md` there), which must
  pass byte for byte: a vector that stops matching is a stop-and-report item, never a skip.
- Keystore instrumented tests run unattended on the locked test phone:
  `ANDROID_SERIAL=<serial> ./gradlew :core:keystore:connectedDebugAndroidTest`
  (also `:core:altchan:` for the real attester against the production policy).
- `:core:relay` is a JVM module too (`:core:relay:test`, RELAY-PROTOCOL §9 vectors).
- The local dev stack (`devstack/devstack.sh up|down`, see `devstack/README.md`):
  vettid-vault's `cmd/devstack` run by commit (`go run ...@<commit>`) with
  `devstack/device-policy.json` (Google roots, the dev packages, the debug signing
  digest, GrapheneOS boot keys), one LocalStack container (≤ 1.5 GB), reachable from the
  phone through `adb reverse` (ports 18080–18082). One stack at a time; tear it down when
  done. The A3 exit test (fresh install → onboarding → enrolled vault with credential with
  the phone's REAL attestation → lock/unlock, all through the UI):
  `adb uninstall com.vettid.app.devstack; ANDROID_SERIAL=<serial> ./gradlew -PvettidTestBuildType=devStack :app:connectedDevStackAndroidTest`
  (screenshots in `/data/local/tmp/a3-exit/`). The A4 exit test (VAULT-MESSAGING 0.10.3: invite → the
  vaultctl peer accepts → the same safety code on both sides → both approve; messages both ways; remove,
  then accept the peer's invitation URL, compare, both approve; a `vettid://connect#` link to an already
  connected peer → "already connected";
  member authentication and a grant request in Approvals; favourite) is
  `app/src/androidTest/.../A4ExitTest.kt`, run the same way with
  `-Pandroid.testInstrumentationRunnerArguments.class=com.vettid.app.A4ExitTest`
  (screenshots in `/data/local/tmp/a4-exit/`). `ReplacedWipeTest` (same way): a direct transfer to a
  test-process "new phone" (TEST attester), after which the app erases itself (owner decision 2026-10-05;
  `LocalWipe`, `HolderWatch`: only an authenticated `device.unlinked{transferred|replaced}` or a sealed
  `unknown_device` wipes; repeated relay `token_revoked` only sends the open app to the unlock screen, `RefusalWatch`).
  No relay error may end the process: `VaultDevice.handle` absorbs a refused answer to the vault, `start` absorbs
  the collector's terminal error, and the process scope carries `RelaySafetyNet`.
The A2 exit test:
  `ANDROID_SERIAL=<serial> ./gradlew :core:data:connectedDebugAndroidTest`; the JVM
  variant is `:core:vault:testDebugUnitTest --tests '*DevStackJvmTest*'`. All skip
  without the stack. Never modify the vettid-vault checkout.
- `:core:testing` is TEST ONLY (dev-stack helpers, the TEST attester, `FakeVault` for
  ViewModel tests); only test configurations and the debug-only `devStack` build type
  depend on it. The `devStack` app uses the real `KeystoreAttester`.
- Compose UI tests on the locked phone host their screens in an activity that draws over
  the keyguard (`feature/onboarding/src/androidTest`); Espresso is pinned to 3.7 (older
  versions break on API 37).

- The `staging` build type (README "Staging build"): release-like, application id
  `com.vettid.app`, talks to the staging vault service (`src/staging`, `Endpoints.STAGING`,
  `ManifestKeys.STAGING`). Signed only from the owner's properties file outside the repo
  (`~/.vettid/staging-signing.properties`); never create, read or ask for it or the
  keystore. Without it the APK is unsigned. `checkReleaseApk` / `checkStagingApk` fail if
  an APK carries another environment's endpoints or pins, or dev-stack/debug-tools code.

## Device testing

Debug builds use application id `com.vettid.app.dev` (`devStack`:
`com.vettid.app.devstack`); never install over `com.vettid.app`. Screenshot launch extras (debug only):
`adb shell am start -S -n com.vettid.app.dev/com.vettid.app.MainActivity --es vettid.theme dark|light --es vettid.start gallery|messages|connections|approvals|items|credential|settings|help --ez vettid.screenshot true`
(`vettid.screenshot` lets the debug app draw over the keyguard of a locked test phone; the phone stays locked).
Every A3 and A4 screen with sample state, no vault needed: `--es vettid.start screen:<name>` (names in
`app/src/debugTools/.../ScreenCatalog.kt`, e.g. `onboarding.backup_off`, `onboarding.setup_scan`, `onboarding.setup_type`, `onboarding.confirm_account`, `account_sheet`, `unlock.updated`,
`credential.alarm`, `settings.delete_confirm`, `messages.conversation`, `invite.request`,
`connections.detail`, `approvals.critical`; recovery and transfer: `recover.pin`, `recover.lost`,
`transfer_in.compare`, `settings.transfer_compare`, `settings.transfer_moved`; the member's erase of a phone the vault
did not recognise: `unlock.not_recognised`, `unlock.erase_confirm`, `unlock.erasing`; the open app sent back after
repeated relay refusals, `unlock.refused`; the transfer's 60 s wait for `hs.resp`, `transfer_in.no_answer`; the vault service paused for maintenance
(MEMBER-API 1.2.0), `unlock.paused`, `unlock.paused_preflight`; the canary manifest (VAULT-RELEASES §10.1 step 9),
`canary.confirm`, `canary.refused`, `settings.attestation_canary`). Debug builds expose Compose test tags as resource
ids, so `uiautomator dump` / `adb shell input` can drive two phones at once (`adb -s <serial>`).
Screenshots and the Proton reference images stay out of git (`local/`, or the
vettid.org repo's `local/android-ui/`).

## Conventions

- Kotlin, Compose + Material3, Hilt (KSP), coroutines/Flow, type-safe Navigation
  (`@Serializable` route objects, one `NavGraphBuilder.xDestination()` per feature).
- Features depend only on `:core:*`; no feature touches transport or crypto
  directly; one ViewModel per screen with immutable UI state. ViewModels use the
  `:core:data` repository interfaces (`AccountRepository`, `VaultRepository`,
  `CredentialRepository`, `PreferencesRepository`; A4: `ConnectionsRepository`,
  `MessagesRepository`, `ApprovalsRepository` in `core/data/.../social`), implemented by
  `VaultManager` and its `SocialManager` (Hilt bindings in `app/.../di/AppModule.kt`); failures are `VaultFailure(kind)` with
  member-facing text from `FailureKind.messageRes()`. The root of the UI follows
  `AppPhase` (one nav destination per phase, so leaving a phase drops its secrets).
- Preferences in DataStore (`PreferencesRepository`); device state and the account record
  (`user_guid`, `vault_id`, the masked email and the vault's account snapshot) in Keystore-encrypted
  files (`KeystoreFileStore`, no-backup dir). The app never signs in (VAULT-MESSAGING 0.15.0 §11.12):
  onboarding redeems the portal's setup code with the Keystore app key (`AppApiKey`), which signs every
  member API request (`X-VettID-App`, `AppRequestSigning`); membership is shown from the vault (`account.get`).
- Every user-visible string in `res/values/strings.xml` (prefixed with the module
  name, e.g. `messages_…`, `core_ui_…`); every icon-only control has a content
  description; touch targets ≥ 48dp.
- Colours only through `MaterialTheme.colorScheme` / `VettIdTheme.colors`:
  `primary` = gold content, `primaryContainer` = gold fill; never hard-code colours
  in screens. Gold (#FFC125, the website's `--gold`) is the single accent; on light
  surfaces gold text/icons use `--gold-ink-light` (#7F640A). Palette values follow
  the website tokens (`website/assets/site.css` in vettid.org). `ContrastTest` guards WCAG AA.
- Type: Plus Jakarta Sans for headings, Inter for body (as on the website), bundled
  as upstream variable TTFs in `core/ui/src/main/res/font` (sources, checksums and
  OFL licences in `core/ui/fonts/`). Icons: Material Icons Outlined.
- Connection tiles: indigo for all connections, teal for favourites (the owner's
  `favorite` flag), gold for the member's own avatar; use `ConnectionRow`.
- Reuse `:core:ui` components (top bar, drawer, list row, empty state, floating
  controls, pill bar, settings groups, avatar sheet, confirm dialog) before adding new ones;
  every new component goes into the debug gallery.
- Every destructive action uses `ConfirmDialog`; every critical action asks for the
  credential password. No backup/export of vault data (`allowBackup=false`).
- Commits: explicit paths only (never `git add -A`); PRs into `master`.
