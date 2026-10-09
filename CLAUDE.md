# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

The VettID Android app, **rewritten from scratch in 2026** (phases A0–A4 done:
skeleton, design system, CI; crypto, keystore, attestation; relay, alternate
channel and vault client against a local dev stack; onboarding, unlock, credential,
settings screens and the biometric app lock; connections, invitations with QR code
and safety code, messages and approvals; A5: the Vault's items, tags, sharing, grants and critical-item uses, `:feature:items`). The plan and decisions are in the vettid.org repo:
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
`adb shell am start -S -n com.vettid.app.dev/com.vettid.app.MainActivity --es vettid.theme dark|light --es vettid.start gallery|messages|connections|approvals|items|history|credential|settings|help --ez vettid.screenshot true`
(`vettid.screenshot` lets the debug app draw over the keyguard of a locked test phone; the phone stays locked).
Every A3 and A4 screen with sample state, no vault needed: `--es vettid.start screen:<name>` (names in
`app/src/debugTools/.../ScreenCatalog.kt`, e.g. `onboarding.backup_off`, `onboarding.setup_scan`, `onboarding.setup_type`, `onboarding.confirm_account`, `account_sheet`, `unlock.updated`,
`credential.alarm`, `settings.delete_confirm`, `messages.conversation`, `invite.request`,
`connections.detail`, `approvals.critical`; recovery and transfer: `recover.pin`, `recover.no_backup`,
`transfer_in.compare`, `settings.transfer_compare`, `settings.transfer_moved`; the member's erase of a phone the vault
did not recognise: `unlock.not_recognised`, `unlock.erase_confirm`, `unlock.erasing`; the open app sent back after
repeated relay refusals, `unlock.refused`; the transfer's 60 s wait for `hs.resp`, `transfer_in.no_answer`; the vault service paused for maintenance
(MEMBER-API 1.2.0), `unlock.paused`, `unlock.paused_preflight`; the canary manifest (VAULT-RELEASES §10.1 step 9),
`canary.confirm`, `canary.refused`, `settings.attestation_canary`; the daily owner check (VAULT-MESSAGING 0.13.0 §3.6),
`owner_check.held`, `owner_check.due`, `owner_check.unknown`, `owner_check.bad_password`, `owner_check.backoff`, `owner_check.voluntary`,
`owner_check.hold_off`, `unlock.owner_check`, `unlock.owner_check_locked`, `settings.owner_check_hold_off`,
`settings.owner_check_offer`, `shell.owner_check_banners`; a new credential (0.15.2), `credential.new`, `credential.new_confirm`; a start-over pending on the portal (0.16.0), `shell.deletion`; the account names and the shared profile (0.18.0, ANDROID-PLAN 0.1.10), `connections.detail_not_shared`, `account_sheet.name_pending`, `settings.shared_profile`, `settings.change_name`, `settings.change_name_confirm`, `settings.change_name_pending`, `settings.change_name_refused`; History and the profile photo (ANDROID-PLAN 0.1.11), `history`, `history.filtered`, `history.search_local`, `history.empty`, `history.no_match`, `history.chain_broken`, `history.entry`, `history.entry_unknown`, `settings.shared_profile_photo`, `settings.shared_profile_photo_preview`; the in-app camera for the profile photo (owner feedback 2026-10-08: taken, never chosen; fake preview), `settings.photo_capture`, `settings.photo_capture_rear`, `settings.photo_capture_one_lens`, `settings.photo_capture_failed`, `settings.photo_capture_review`, `settings.photo_capture_review_zoomed`, `settings.photo_capture_permission`, `settings.photo_capture_blocked`, `settings.photo_capture_no_camera` (the avatar sheet, `account_sheet`, has Lock vault first and scrolls); the simplified connection detail (owner decision 2026-10-08: no safety code, alias, note, edit or block; the vault key fingerprint stays), `connections.detail_remove`; the Vault (A5a, items of VAULT-MESSAGING §10.7, `ItemsCatalog.kt`), `items`, `items.filtered`, `items.empty`, `items.no_match`, `items.error`, `items.templates`, `items.detail`, `items.detail_profile`, `items.detail_secret_hidden`, `items.detail_secret_revealed`, `items.detail_critical_hidden`, `items.detail_critical_open`, `items.detail_critical_password`, `items.detail_critical_backoff`, `items.detail_protection`, `items.detail_leave_critical`, `items.detail_delete`, `items.detail_missing`, `items.edit_new`, `items.edit_problems`, `items.edit_profile`, `items.edit_conflict`, `items.edit_critical_password`; tags and sharing (A5b, §10.8, §10.12), `items.tags`, `items.tags_edit`, `items.tags_rename`, `items.tags_in_use`, `items.sharing`, `items.sharing_empty`, `items.rule_new`, `items.rule_edit`, `items.shared_with_you`, `items.shared_with_you_empty`, `items.edit_share_impact`, `approvals.share_partial`, `settings.shared_profile_items`; grants, critical-item uses and History's items (A5c), `approvals.grant_decide`, `approvals.critical_backoff`, `approvals.critical_result`, `approvals.critical_unsuitable`, `history.items`, `history.entry_item`, `items.shared_ask`; VAULT-MESSAGING 0.21.0: edits with kept values and the room left, named limits, the suitability notice, `items.edit_secret_kept`, `items.edit_critical_kept`, `items.edit_limit`, `approvals.critical_suitable`, `approvals.critical_unsuitable_kind`; value-first item fields and member-defined categories (owner feedback 2026-10-08: one value input captioned by the field's label, a ⋯ menu to rename, retype an unsaved field, move or remove, the label asked for when adding; "New category…" derives a §10.7 identifier from a typed name), `items.edit_card`, `items.edit_add_field`, `items.edit_add_field_long`, `items.edit_rename_field`, `items.edit_field_type`, `items.edit_new_category`, `items.edit_new_category_bad`, `items.edit_custom_category`; one item per contact point and the shared profile as a member choice (VAULT-ITEMS 0.1.1, owner decision 2026-10-08: templates `email_address`, `phone_number`, `postal_address`, `website` replace `contact_card`, no template adds `@profile`; "Shared profile" is a built-in tag choice for standard items only; a blank item starts with no field), `items.edit_blank`, `items.edit_address`; sharing both ways on the connection detail, no pre-typed example text, formats enforced while typing, the protection changed in the editor, and the top bar's search icon (owner requests 2026-10-08), `connections.detail` (both sharing cards), `connections.detail_sharing_empty`, `items.edit_card` (month-and-year expiry, name placeholder), `items.edit_protection`, `items.edit_leave_critical`, `items.edit_saved_protection_not`, `items.list`, `items.list_search`, `messages.search`, `messages.search_none`, `connections.search`, `connections.search_none`; History export (ANDROID-PLAN 0.1.17, VAULT-MESSAGING 0.22.0 §10.9: History ⋯ → "Export…", the confirm step with count, range, CSV/JSON and the unencrypted-file notice, the vault PIN, reading, "Save to…"; `history` shows an `audit.exported` row), `history.export_preparing`, `history.export_confirm`, `history.export_confirm_json`, `history.export_confirm_more`, `history.export_nothing`, `history.export_alarm`, `history.export_old_vault`, `history.export_pin`, `history.export_pin_wrong`, `history.export_pin_backoff`, `history.export_reading`, `history.export_save`, `history.export_writing`, `history.export_done`, `history.export_done_partial`, `history.export_failed`, `history.export_save_failed`, `history.export_owner_check`; proactive release updates (ANDROID-PLAN 0.1.19, owner decision 2026-10-09: the shell's banner, dismissed for 24 h at most, the end-date warning, one local "Vault updates" notification per release, Settings → Vault → "Update available", and the one-step update: lock → unlock with the approved `release_update` → `moved` → unlock at N+1, shown over every phase by `ReleaseUpdateManager`), `shell.release_banner`, `shell.release_banner_ending`, `shell.release_banner_ended`, `release_update`, `release_update.ending`, `release_update.check_first`, `release_update.none`, `release_update.progress`, `release_update.starting`, `release_update.done`, `release_update.refused`, `release_update.bad_pin`, `release_update.failed`, `release_update.new_failed`, `release_update.abandon_confirm`, `release_update.abandoning`, `release_update.abandoned`, `settings.release_update_row`; the app lock's method (owner request 2026-10-09: "App lock" with Biometrics or Phone screen lock, no VettID passcode), `app_lock.screen_lock`, `settings.app_lock_screen_lock`, `settings.app_lock_method`, `settings.app_lock_method_no_screen_lock`). Debug builds expose Compose test tags as resource
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
  `MessagesRepository`, `ApprovalsRepository` in `core/data/.../social`; A5: `ItemsRepository` and `SharingRepository` in `core/data/.../items`,
  `ItemsManager` / `SharingManager` over `ItemsOps` / `SharingOps`), implemented by
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
  credential password. No backup/export of vault data (`allowBackup=false`); the one exception is History's export of activity metadata (ANDROID-PLAN 0.1.17: the vault PIN, then "Save to…" only).
- Commits: explicit paths only (never `git add -A`); PRs into `master`.
