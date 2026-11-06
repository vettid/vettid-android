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
- Settings → Account has "Delete your VettID account" (`settings_delete_account`, wording PENDING owner approval; Google Play requires it): it opens `AccountSite.url(account.apiOrigin, DELETE_ACCOUNT_PATH)` (`/account/delete/` on the build's account origin, as recovery's start-over link) in the browser, with no in-app confirmation.
- The apps don't offer blocking (owner 2026-11-03): a connection request is declined and a connection is removed. The vault's block ops (`VaultApi.blockAdd`/`blockRemove`/`blockList`, `block.added`/`block.removed` events) are kept for old data, as are the "Blocked" state label and the History titles for blocked/unblocked connections.
- Shared machine: memory and workers are capped in `gradle.properties`. Run
  builds with `--max-workers=2`, check `free -g` first (wait if < 8 GB
  available), and `./gradlew --stop` when done. No emulator.
- `./gradlew :app:assembleDebug :app:assembleDevStack testDebugUnitTest :core:crypto:test :core:relay:test detekt :app:lintDebug :app:assembleRelease :app:assembleStaging :app:checkReleaseApk :app:checkStagingApk :app:checkFossReleaseApk`
  is what CI runs (`:app:assembleRelease` builds both distributions' release APKs) (`.github/workflows/ci.yml`, plus gitleaks over full history, with
  `.gitleaks.toml` allowlisting the public §16 test-vector values).
- `:core:crypto` is a pure Kotlin/JVM module (`vettid.jvm.library`); its tests include the
  vettid-vault §16 vectors (`src/test/resources/vectors`, see `SOURCE.md` there), which must
  pass byte for byte: a vector that stops matching is a stop-and-report item, never a skip.
- Keystore instrumented tests run unattended on the locked test phone:
  `ANDROID_SERIAL=<serial> ./gradlew :core:keystore:connectedDebugAndroidTest`
  (also `:core:altchan:` for the real attester against the production policy).
- `:core:relay` is a JVM module too (`:core:relay:test`, RELAY-PROTOCOL §9 vectors).
- A credential change whose answer may be lost is stored as an `UnresolvedChange` marker (no secret) before it is sent and decided by
  `ChangeResolver`, never repeated: `pin.change`, `credential.password.change`, and (VAULT-MESSAGING 0.48.0, ANDROID-PLAN 0.1.51)
  `credential.reset`, which goes through `CredentialOps.change` like the others. A reset's marker carries `heldKey` (the `key` of the
  `credential.version` answer it was sent after; an answer without one stops it with nothing sent); it is decided by the key (another
  key, or a lower version: applied) and a reset's answer keeps its blob although its version restarts at 1. Texts marked DRAFT in
  `strings*.xml` are shared with the iPhone app: do not reword them alone.
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
  `com.vettid.app.staging` (ANDROID-PLAN 0.1.35, from staging release S10; installs next to a
  release build; release stays `com.vettid.app`), talks to the staging vault service (`src/staging`,
  `Endpoints.STAGING`, `ManifestKeys.STAGING`); its only account App Link host is
  `account.staging.vettid.org` (manifest placeholder `vettidAccountHost`). Signed only from the
  owner's properties file outside the repo
  (`~/.vettid/staging-signing.properties`); never create, read or ask for it or the
  keystore. Without it the APK is unsigned. `checkReleaseApk` / `checkStagingApk` fail if
  an APK carries another environment's endpoints or pins, or dev-stack/debug-tools code, or
  (`check<Variant>ApplicationId`) another application id, an authority or permission outside its
  id, or the other environment's account host.
- Signer binding (README "Signer binding"; VAULT-MESSAGING 0.27.0 §11.7.1, ANDROID-PLAN 0.1.36 §7): the unlock's
  `device_assertion.chain` (`Attester.chain()`, sent while `VaultDevice.signerSeen` is false, i.e. until the app's
  own `device.list` entry shows `signer`, and again after an unlock answered `signer_refused`; never on a release
  approval; a real chain is captured by hand with `AttestationChainDumpTest`, README), `UnlockAttempt.SignerRefused` /
  `FailureKind.SIGNER_REFUSED` (not a PIN failure), `SignerWire` / `SignerClass` in `:core:data`, the "Installed
  from" line (`VaultOverview.signer`), `TransferOutStep.SIGNER` and `signer` in `device.transfer.approve`
  (`signer_unconfirmed` gives the UTK back). The vault names classes; every word is the app's (`SignerText.kt`,
  `FeedKinds.signerLabel`). Screens: `unlock.signer_refused`, `unlock.signer_refused_help`,
  `settings.status_signer_vettid`, `settings.status_signer_google_play`, `settings.status_signer_development`,
  `settings.status_signer_details`, `settings.transfer_signer`, `settings.transfer_signer_reverse`,
  `settings.transfer_signer_development`, `settings.transfer_signer_unknown`, `settings.transfer_signer_unconfirmed`,
  `history.signer`, `history.entry_signer`, `notifications.signer_changed`.

- Held lists (owner-approved 2026-11-03, as vettid-ios #201): while the daily owner check is due, Messages' and History's lists show an INFO notice ("Messages are on hold" / "History is on hold", `*_held_title`/`*_held_body`), not the error, with no "Try again", above the rows when there are any; both ViewModels re-read when `checkCleared()` fires (History with rows reads the newest page again, rows kept; shown again while held it keeps the rows). The conversation and History-entry screens are not changed.

- Two distributions (README "F-Droid build"; flavour dimension `distribution`): `full` (Google Play, VettID's
  releases; every build type) and `foss` (F-Droid; `fossRelease` only), same application id and version. `foss`
  contains no Google proprietary code: `:app:checkFossReleaseApk` fails on any Firebase, Google Play services,
  datatransport, Play or WorkManager module, class, file or manifest entry. Distribution-specific code lives only in
  `app/src/full` and `app/src/foss` (`DistributionApplication`, `DistributionModule`); anything of Google goes into
  `:core:push-fcm` (its coordinates in that module's build file, never in the version catalogue) and nowhere else.
  `foss` binds `NoGooglePushProvider` (`PushAvailability.NOT_IN_THIS_BUILD`, `settings.notifications_push_not_in_build`).
  The `full` tasks keep their old names by alias (`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:installStaging`,
  `:app:connectedDevStackAndroidTest`); the APKs are `app/build/outputs/apk/<full|foss>/<type>/`. The `fossRelease`
  APK must stay reproducible (F-Droid publishes the VettID-signed APK after rebuilding it): nothing of the build
  time, path, machine or git state may go into a release APK.
- Google push (ANDROID-PLAN 0.1.30, Notification modes 4; README "Google push"; the `full` distribution): `:core:push-fcm` is the only module
  with Firebase (firebase-messaging alone; no google-services.json, no Google Services plugin). The Firebase options
  come from the Gradle properties `vettidFcm<BuildType>{ApplicationId,ProjectId,ApiKey,SenderId}`; never commit
  values, and use obviously fake ones in tests. Without them (the default, CI) the provider is "not available yet",
  Firebase is never initialised and its push components are disabled in the manifest
  (`:app:check<Variant>PushManifest`, run by `checkReleaseApk` / `checkStagingApk`). The registration
  (`PushRegistrar`, `:core:notify`) sends the token to the member's vault only (`push.register`); a wake only
  starts the app's usual collect (`VaultManager.collectForWake`), and the notifications come from the feed as in
  the on-phone service mode. Screens: `settings.notifications_push_available`, `settings.notifications_push`,
  `settings.notifications_push_again`, `settings.notifications_push_unreachable`,
  `settings.notifications_push_fallback`, `settings.notifications_push_not_yet`. Google push chosen but the push
  service no longer usable on the phone (0.1.32, owner decision 2026-10-11; `ModeController`): the on-phone service
  runs as the fallback, the mode preference is never changed, `push.unregister` goes to the vault at its next open if
  a registration may still exist, and the registration resumes when the push service is back
  (`settings.notifications_push_not_on_phone`).
- Service connections (ANDROID-PLAN 0.1.90, VAULT-MESSAGING 0.69.0 §6.4; texts DRAFT in `core/ui` `strings.xml`, `service_forbidden` in
  `core/data`): `peer_kind == "service"` (`ApprovalParser.isService`; absent or any other value is a person; never inferred) is read from
  `connection.get`/`.list` (`ConnectionInfo.service`), `connection.invite.accept` (`AcceptedConnection.service`) and the outgoing request
  (`Approval.OutgoingRequest.service`, event and list entry). The outgoing request's compare screens (`AcceptScreen` COMPARE/APPROVED, the
  Approvals decision) show "Name given by the service", `ServiceRequestLine` (line, caveat, `ik` fingerprint when the connection list has
  it) above the code. Messages never lists a service (`MessagesViewModel`, `NewMessageViewModel`; a service conversation has no composer or
  call, `ConversationUiState.noComposer`); Connections has a "Services" section below the people (`ConnectionsUiState.services`, starred
  first, `ConnectionRow(serviceLabel)`); Approvals (`Approval.connectionService`), History and Notifications rows append "Service". A service's
  detail has the label, the names as "Name given by the service", the fingerprint, "What this service can ask" (the `_votes` line where
  `VotesAvailability.AVAILABLE`), asks, History and remove, and none of message, Call, sharing in either direction, authentication.
  `forbidden` to an authentication request or a message is `FailureKind.SERVICE_FORBIDDEN` ("Services can't do that."), shown once; the
  state remembers it (`authRefused`, `serviceRefused`) and the action is gone. `service_no_calls` and `services_empty` are unused.
  Screens: `connections.services`, `connections.service_detail`, `invite.request_service`.
- Presence (ANDROID-PLAN §4 "Presence", P1; VAULT-MESSAGING §10.17; owner chose the recommended answers to questions 22 and 23: no presence on the Connections list, and the one-time card): `PresenceApi.kt` in `:core:vault` (`presence.get`, `presence.set{version, state?, share?, except?}`, `presence.query`), `PresenceManager` / `PresenceRepository` in `:core:data` `presence/` (`VaultManager.presence`, `PhaseSteps.PRESENCE`, `NoPresence` as a screen's default). **Whether the vault has presence** is learnt by asking (no message lists a release's features, as for calls and votes): `PresenceManager.start()` after every unlock and at the end of a hold sends `presence.get`; an answer is `PresenceAvailability.AVAILABLE`, `unsupported_type` is `UNSUPPORTED` (until the next unlock) and **nothing of presence is shown or sent** (no Privacy row, no sheet row, no line, no switch, no card). **Memory only:** the policy (`PresencePolicy`: `state`, `share`, `except` sorted and deduplicated, at most 1,024) and the readings (`PresenceReading`: `Checking`, `Unknown`, `Known(state, lastActive)`) are cleared by `clear()` at lock, sign-out, wipe and the hold (same places as `votes.clear()`); the policy is re-read at unlock and on `sync.event{presence.changed}`. **The hold:** `closed()` (phase not unlocked, or `OwnerCheckView.gated(now)`: due or held) means no `presence.*` is sent: a query reads `Unknown` at once, `update`/`refreshPolicy` throw `owner_check_required`. **Asking:** `query(connectionId)` (never throws) makes the reading `Checking` and sends `presence.query`; `presence.result` with that `ping_id` (`onEvent`, also one that arrives before the answer) makes it `Known`; at `exp` (at most 30 s) it becomes `Unknown` and a later result is dropped; a second query for the connection within a minute sends nothing (the vault would repeat its first `ping_id`). Asked **only** when a person's page or conversation opens (`ConnectionDetailViewModel.init`, `ConversationViewModel.init`, through `queryWhenKnown`, which waits for the first `presence.get`) and on the page's refresh icon; never from a list; never for a service (`ConnectionInfo.service`) or a connection that is not active. **Changing the policy:** `update(change)` applies a pure function to the current policy and sends only the members that differ with its `version`; on `conflict` the policy is read again and `change` applied once more, a second `conflict` is thrown. Changing `share` starts the exceptions again (`withShare`: the same ids would mean the opposite); the switch is `withSharedWith(id, shared)` (adds or removes the id in `except` whichever way `share` goes). Screens: Settings → Privacy → **Presence** (`PresenceSettings.kt` in `:feature:settings`, shown only with the feature; services are not offered as exceptions; **every choice is sent at once, so nothing is held and the screen reports no `HoldsInput`** (`PresenceSettingsUiState.holdsInput` is false, unit-tested); a choice there also marks the one-time card answered), the avatar sheet's "Status: Available" row (`StatusGroup` in `AccountSheet.kt`, the group right after Lock vault, which stays first under the name (owner 2026-10-08); picker = `ChoiceDialog`; a refused pick shows its failure text in the sheet, `ShellViewModel.presenceError`), the connection page's line, refresh icon and **Share my status with <First>** (`PresenceViews.kt` in `:feature:connections`; the switch needs the first name), the conversation's smaller line (`ConversationPresenceLine`), and the one-time card on Connections (shown when the vault has presence, `share` is `all` and `AppPreferences.presenceCardAnswered` is false; **Share with everyone** only marks it answered, **Not now** sends `share: none`). `presenceCardAnswered` (DataStore) is the only thing on disk: a choice about what this phone shows, not presence data. The words of a line (`PresenceText`, `data_presence_*` in `:core:data`): "last seen {n} min ago" up to the hour, "last seen just now" under 5 minutes (the vault rounds down to 5), "last seen today" from an hour on the same calendar day, the date (below) for an earlier day, nothing without `last_active`. Texts (in `:core:data`, `:feature:settings` (`strings_presence.xml`), `:feature:connections` and `:app` strings) are all owner-approved 2026-11-03 (`<!-- Owner-approved 2026-11-03 -->`, the "last seen" ones with their own note). A `last_active` from an earlier calendar day reads "last seen 2 Nov" (`data_presence_active_date`, owner 2a: day and month in the device's locale through `DateFormat.getBestDateTimePattern`, with the year when it is not this year; `PresenceAge.Date`). A `Known` reading is dropped after a minute; readings are told apart (`Checking`: not asked yet or being asked; an open page whose reading went missing asks again, but only while the screen is at least STARTED (`setVisible` from `ON_START`/`ON_STOP`, `queryWhileAvailable(id, visible)`): a question counts as the member's activity (§10.17), so a page left open in the background sends nothing, and coming back asks once; `Unknown`: asked and no result by `exp`, the only case with "Status unknown" and the vault-didn't-answer sentence; `Failed`: our side failed, shown with the failure's text; `Closed`: owner check due or held, nothing sent and no line), `PresenceRepository.recent` disables the refresh icon for a minute after an ask, `PresenceManager.watchClosed` starts the probe again whenever `closed()` turns false, an `except` entry that does not read (or over 1,024) makes the policy unreadable, and with the member Invisible the per-person switch is off and disabled; the opening `presence.get` is retried (5, 15, 60 s, then every 60 s) while the vault is open and the state unknown; an unknown `state`/`share` from the vault is no policy. Dev stack: `PresenceDevStackTest` (skips without the stack). Catalog: `settings.presence`, `settings.presence_invisible`, `settings.presence_error`, `account_sheet.status`, `connections.detail_presence`, `connections.detail_presence_busy`, `connections.detail_presence_away`, `connections.detail_presence_checking`, `connections.detail_presence_unknown`, `connections.detail_presence_not_shared`, `connections.presence_card`.
- Location, sharing (ANDROID-PLAN §4 "Location", L1a; VAULT-MESSAGING §10.16; owner chose the recommended answers to questions 24-27: foreground only, no map in L1, requests in Approvals): **in production, both distributions** (no `-P` switch, no `src/noX`). Only the sending side is built; receiving, the incoming card and glance, requests in Approvals, the Location drawer screen, History and feed labels, distance and direction are L1b. `LocationApi.kt` in `:core:vault` (`location.share.start` with the vault's bounds checked first (a once share has neither duration nor interval; 5 min to 7 days; 10 s to 1 hour), the ephemeral `location.update` through `VaultDevice.sendOnce` with `exp` from the vault's clock, `location.share.stop`, `location.share.list`; a `LocationSample` prints its type name only). `LocationSharing` / `LocationRepository` in `:core:data` `location/` (`VaultManager.location`; `NoLocation` as a screen's default; the foreground service and positions are `LocationPlatform`, attached by the app in `AppModule.locationRepository`, which `VettIdApplication` injects so that the platform exists before the first unlock). **Who sends:** this phone, for the active shares it is the source of (it started them, or `location.share.list` names its `device_id`, or the share id is in the stored settings); nothing is sent when it is the source of none, while the vault is locked, or while the daily check is due or held. **Cadence:** the platform is asked for the shortest interval among the continuous shares (`PositionRequest`); each fix is one ephemeral `location.update` (`exp` = now + max(2 x interval, 60 s)) with `altitude_m`, `speed_mps` and `heading_deg` only when some share is exact; at most one per 0.9 x the interval; a fix older than 5 minutes is dropped, and `at` is the fix time; a once share waits up to 60 s for 100 m or better and sends the best it has (or the first that arrives), and that one position delivers it (the share stays listed for its 15 minutes, the phone sends nothing more for it). **Ending:** the app's own timer at `expires_at` (and a look at the date before every send: the timer runs on uptime), `stop` (sending ends at once and the stop is written to the stored settings first (`StoredShare.stopping`, ids only); the vault is told, and `location.share.stop` is allowed while the check is due or held (VAULT-MESSAGING 0.37.0 §3.6.3; the plan's "refuses" is out of date); a stop that fails is thrown and tried again every 5 s, and a share marked stopping is never taken up again after a lock or a restart: `resume()` sends the stop for it and clears the mark on `{}` or `not_found`), `stopAll` (the notification), `location.event{stopped, direction: out}`, `sync.event{location.share.changed, state: ended}`. **Lock / due / held:** `clear()` at every lock, sign-out and wipe (same places as `presence.clear()`; `PhaseSteps.LOCATION`) stops the service and the platform's request and clears memory, the stored ids stay; `resume()` after each unlock and at the app's start with the vault open reads `location.share.list` and takes up the shares whose `device_id` is this phone's (or whose id is stored) with `ServiceState.resumed` ("…again, until {time}."); a due or held check **pauses** (service kept, nothing sent, "Paused until your daily check." with **Check now**; a 5 s watcher plus `VaultManager`'s owner-check collector call `onGateChanged()`); the app lock does not stop it. A service start Android refused in the background is tried again when the app comes to the front (`onForeground()`). **Positions are never stored:** a `Fix` is held only until it is sent (a once share's best fix for at most 60 s), prints its type name, and is in no Room table, file, DataStore, `SavedStateHandle`, log or crash report; the only thing on disk is `StoredShare` (`share_id`, `connection_id`, `expires_at_ms`, `stopping`) in `location-shares.bin`, Keystore-encrypted in `noBackupFilesDir` (`KeystoreShareStore`), erased by the wipe with the directory. `:core:location` (Android library; no Google code, so the `foss` build works as `full` does): `PlatformPositionProvider` over the platform `LocationManager` (`fused` provider, which minSdk 31 guarantees; GPS and network where it is missing), `LocationService` (foreground type `location`, started only from the member's tap while the app is visible), `AndroidLocationPlatform`. Its manifest asks for `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION` and **never `ACCESS_BACKGROUND_LOCATION`**: `:app:check<Variant>BackgroundLocation` (`BackgroundLocationCheck`; run by every variant's assemble, bundle and unit-test task) fails a merged manifest that has it or lacks what sharing needs. The notification is on the new **Location sharing** channel (`Channels.LOCATION`, low, silent; private on the lock screen, the public version names nobody): "Sharing your location with {First} until {time}" / "with {n} people" / "…again, until {time}." / "Paused until your daily check.", **Stop all** (and **Check now** while paused); names are said only while no cover is up (the app lock's `coverFlow`) and the member's notifications show names, else "Sharing your location". **Its tap and Check now open the app's main screen** (the launch intent), because the Location screen is L1b. Screens (`ShareLocation.kt` in `:feature:connections`, `ShareLocationViewModel`, opened from the connection page's **Share my location** card, which becomes "Sharing your location until {time}" with **Stop** (`DetailLocation`, `ConnectionDetailViewModel.stopSharingLocation`); person connections that are active only): **Once** / **For a while** (15 min, 1 hour (default), 8 hours, 1 day, 7 days), **Precision** (Approximate (default), Exact, City, with the precision note), **Update every** (30 s, 1 min (default), 5 min, 15 min), **Let {First} keep the trail** (off; not offered for once); the permission is asked at **Start sharing** with the plan's text (shown while it is not granted); with only approximate access Exact is disabled with "Exact needs precise location in Android settings."; after the share starts the sheet says "Sharing your location with {First} until {time}." and "Finding your location…" until the first position is sent, then closes. The sheet reports `HoldsInput` while it is open (`ShareLocationUiState.holdsInput`, unit-tested; the file is on `ShellOverlayRulesTest`'s list). `POST_NOTIFICATIONS` is the shell's existing request; sharing does not ask for it. Whether the vault has location (`LocationRepository.availability`: AVAILABLE, UNSUPPORTED, UNKNOWN) is learnt from the unlock's `location.share.list` or the first start; the connection page's card is hidden on UNSUPPORTED, and the sheet reads the list first and asks the phone's permission only once the vault is known to have location. Positions are collected only while the service runs or the app is in front (`setInFront`); a service that ended itself reports `onServiceEnded()` and is started again when the app is in front; the service is ended by an `ACTION_STOP` intent it handles after `startForeground`. A fix's age is judged by its `elapsedRealtimeNanos`. **While any stop is pending nothing is sent at all** (the vault forwards each position to every share this phone sources): the state says `SendIssue.STOP_PENDING` ("Not sending until your vault has stopped a share."). `ServiceState.issue` (`SendIssue`: `STOP_PENDING`, `NO_PERMISSION`, `LOCATION_OFF`, `SERVICE`; `LocationPlatform.issue()` reads the permission and the phone's location switch at every 5 s look, and a `SecurityException` from the provider sets `NO_PERMISSION`) is shown on the notification and the card with **Stop** and, for the two Android settings, **Open settings** (`settingsIntent`); the card also shows `Paused until your daily check.` and, for a once share that found no position, `LocationRepository.missedOnce` ("No position was found, so nothing was sent."). A service connection is never sent a location (`LocationSharing.start` refuses it with `FailureKind.SERVICE_FORBIDDEN`, nothing sent; `VaultManager` passes `isService` from the connection list). A share is not started that cannot send (`LocationSharing.start` refuses with a `send_issue_*` failure code unless the app is in front, a platform is attached, and the permission and location switch are on; the sheet says why, with **Open settings**); a stopped share's own card says "Stopping…" (`ServiceState.stopping`); `refused` is cleared at the 5 s look or when the app comes to the front once the platform reports no issue, and the positions are asked for again; the `SERVICE` issue also posts a plain notice on the Location channel (`LocationPlatform.showNotRunning`, "Not sending: open VettID to carry on.", opens the app), and the lock-screen version of the ongoing notification says paused / not sending (never a name). Stop marks are loaded and sent at unlock without waiting for the owner check, retried with the `retryDelays` backoff, and forgotten with the stored settings at a wipe, sign-out or deletion (`LocationSharing.forget()`); a list read in flight when a share ended cannot bring it back (`endedAt` against the read's epoch); `location.share.list` is read strictly (an entry out of the vault's bounds makes the whole list unreadable). **A share that does not get through is said (A6 N21, owner decision 2026-11-05):** each tracked share keeps a stall clock (`Tracked.accepted`, a time only, memory only, on the uptime clock `elapsedNanos`: a wall-clock jump makes or hides nothing, deep sleep counts) that starts when the share starts or is taken up and restarts whenever `ops().update` returned. **"Accepted" means the relay took the deposit** (`location.update` is `sendOnce`, no answer), so a drop on the vault's side is not seen (follow-up: compare with `location.share.list`'s `last_sent_at`); a fix taken, dropped for its age or refused does not count. When elapsed time since it reaches the share's interval plus `STALL_GRACE` (15 minutes), `ServiceState.stalled` (connection id to whole minutes) names it, re-published as the minutes grow (the 5 s look). While the vault is locked or the check is due or held every clock is moved to now at each look, and the first look after the pause, and `onGateChanged(lifted = true)` when a pause lifts (`pauseLifted(previous, next, now)` in the owner-check collector: the previous value looked at again now, so a deadline that passed with no new value counts; an early pass, an idle-lock change or a failure count change lifts nothing and leaves a real stall alone), start them over (`wasGated`; a clock could be hours old), so paused time never counts and nothing is stalled then. A killed process reports nothing (memory is lost and the clock restarts at resume; only the receiver sees the gap); with location off and the screen off a stall is noticed when the phone wakes, with the right minutes. A once share is never in `stalled`: `missedOnce` says it, once, at its `expires_at`, and not earlier because a late fix after the 60 s wait still delivers it; a share another device sends for is not stalled either. The card shows "Your location hasn't reached {First} for {n} minutes." (`DetailLocation.stalledMinutes`, `data_location_stalled`) **above** the issue line, so the member reads it first; the issue line and its **Open settings** (the fix-it) follow and are not duplicated. `LocationPlatform.showStalled` posts one notice per stall on the Location channel, once (a dismissed one is not posted again); `updateStalled` changes its minutes only while it is still showing, and the card carries the live minutes; it names {First} only while the notification names map has the connection (re-posted without the name when the cover comes up, only if still showing), else `data_location_stalled_unnamed` "Your location isn't getting through."; the lock-screen version never names and has no tap; a tap opens that connection's page (`NotifyIntents.connection`: `ACTION_OPEN_CONNECTION` with the connection id, an explicit immutable intent to the launcher activity; `MainActivity` hands it to `NotificationOpenInbox.Open.Connection` and the shell navigates to `ConnectionDetailRoute`, behind the app lock and the owner check like every notification tap; a connection that is gone closes its page back; an id without the shape of a vault id (`NotificationOpenInbox.plausibleId`: 1-128 of letters, digits and `._:-`) is never offered, and a tap not yet taken is dropped by the member's own lock on this phone (`vaultClosed()` from `VaultManager.onBeforeLock`, registered in `AppModule`) and by a wipe (a `WipeHook`), never by a lock the phone only learns of (the vault locked itself or elsewhere: the tap that woke the app may come just before it) or at a cold start); `AndroidLocationPlatform` cancels the stall and "not running" notices a dead process left at its start. Cleared when a position is accepted, the share ends or is dropped, the check pauses it or the vault locks. The ongoing notification is not posted again for the minutes (`LocationService` ignores `stalled`). Catalog: `connections.detail_location_stalled`, `connections.detail_location_stalled_off`. Dev stack: `LocationDevStackTest` (skips without the stack). Tests: `LocationSharingTest`, `LocationApiTest`, `LocationPrivacyTest` (the stored schema; no logging call) and `LocationModulePrivacyTest`, `DataDirectoryScanTest` (Robolectric: a fake share along a test route, then the app's whole data directory scanned for the coordinates as text and binary), `LocationManifestTest`, `ShareLocationTest`. Texts are marked `DRAFT: ANDROID-PLAN §4 Location` (the plan's) or `NEW: not in the plan` in `feature/connections/.../strings.xml`, `core/location/.../strings.xml` and `core/notify/.../strings.xml`; none is approved. Catalog: `location.share`, `location.share_permission`, `location.share_permission_denied`, `location.share_once`, `location.share_exact_disabled`, `location.share_finding`, `connections.detail_location`, `connections.detail_location_paused`, `connections.detail_location_stop_pending`, `connections.detail_location_no_permission`, `connections.detail_location_off`, `connections.detail_location_missed`.
- Location, receiving (ANDROID-PLAN §4 "Location", L1b; VAULT-MESSAGING §10.16; texts DRAFT (the plan's) or NEW in each module's `strings.xml`, none approved): `LocationReceiving` (`:core:data` `location/`, owned by `LocationSharing`, which exposes it through `LocationRepository`: `incoming`, `open`, `stopReceiving`, `ask`, `offsetFromHere`). **Memory only**: `IncomingShare` (`last`, the trail, a snapshot) and `ReceivedPosition` print their type names, are in no Room table, file, DataStore, `SavedStateHandle`, log or crash report (`LocationPrivacyTest`, `DataDirectoryScanTest.noReceivedPositionIsLeftOnDisk`). **Sources:** `location.share.list` `incoming` (read strictly: one entry out of the vault's bounds makes the whole list unreadable; `LocationSharing.read` hands the same answer to `applyList`), `location.event{started | stopped | snapshot, direction: in}`, the feed item `location.shared` (reads the list for an unknown share), the ephemeral `location.update` (only for a known share of the connection it names; a position out of range is dropped; the newest `at` wins; the trail only when `history`), and `location.get{connection_id, history}` when the connection page or the position screen opens (`not_found` ends the share here). A new share from a connection replaces the old. **Expiry:** the app's own timer at `expires_at` plus a look at the date at every write. **Lock races:** every write from a read or an event is checked under `LocationReceiving.lock` against the `lockEpoch` it began under (`begin()` / `Token`); `clear()` (lock, sign-out, wipe, and the **hold**: `VaultManager` calls `location.clearReceived()` with the other clears when the check is held) bumps it, so nothing from before a lock lands after it; `SocialManager` has `lockEpoch` and `holdEpoch` (#285) and the location requests follow its rules; the receiving side keeps its own single epoch, bumped by `clear()` at lock, wipe and the hold, which is equivalent because both drop the same data. An event takes the epoch at entry (`handle(m, epoch)`) and checks it under the lock at each write; the end of a share (`stopped`, `ended`) is taken whatever the gate says (it only removes); a share that ended in this session is not brought back by an event; expiry is also swept by the wall clock at `open`, `applyList`, `onSnapshot`, `onUpdate` and when the app comes to the front; a sample more than 5 minutes ahead is refused; `reopenReceived()` retries the list read with the resume backoff. Nothing is read, taken from an event or asked while the vault is locked or the check is due or held (`gated`); `location.share.stop` of an incoming share is allowed then (§3.6.3); when the check passes or the hold ends `reopenReceived()` reads the list again. **Ask for location:** `location.request{connection_id, note?}` (note at most 256 bytes of UTF-8, kept while typing and checked again; never for a service: `SERVICE_FORBIDDEN`); `limit` becomes `AskResult.Limited(minutes)` (the minutes left of 10 from this phone's last ask, else 10). **Distance and direction** (`LocationPlatform.currentFix()`, `PositionOffset`): only when the member has already allowed location and the phone's location is on; the permission is never asked for there; one `getCurrentLocation` (5 s at most), never a remembered position (`getLastKnownLocation` stays banned in `:core:location`), dropped after the offset is worked out. **Requests:** `Approval.LocationRequest` (`location:<request_id>`; `location.request.pending`, `pending.list` kind `location`, closed by `sync.event{location.request.closed}` which also reads its feed item) goes through `SocialManager` like the other asks (events, merge, lock and hold clears); the screen (`LocationRequestScreen` in `:feature:approvals`) is "{First} asks where you are" with the note, **Share once** (one Approximate once share with the request's `request_id`, the phone's permission asked first, the reason shown when nothing can be sent), **Share for a while…** (opens the connection with the share sheet and the request's id: `ConnectionDetailRoute(connectionId, shareRequestId)`) and **Not now** (`ApprovalsRepository.dismissLocationRequest`: nothing is sent; the request leaves the list until the vault locks or the hold, and ends by itself after 24 h); a request from a service offers only Not now. A notification of `location.request` opens its Approvals entry (`FeedTarget.ApprovalEntry`), `location.shared` opens the connection; no names under the cover as for every item. **Screens** (`LocationViews.kt` in `:feature:connections`; listed in `ShellOverlayRulesTest`): the connection page's incoming card ("{First}'s location · {age} · within {accuracy}" / "Until {time}", **Stop receiving**, **Show position**; **Ask for location** while the connection does not share; the ask dialog reports `HoldsInput`), the Connections list glance (icon and age under the row), the Location drawer screen (`LocationRoute`, shown while the vault has location: **You're sharing with** (precision, until, "Last sent …", Stop), **Shared with you**, **Requests**), and the position as text (`PositionRoute`: coordinates marked "Center of an area, not an exact spot." and the precision (Approximate, Exact, City, with `precisionLabel`) when the share is not Exact, the same precision on the incoming card; the offset's "Near this phone" threshold is the larger of the accuracy and the share's nominal radius (Approximate ~1 km, City ~11 km, `PositionText.nominalRadius`); the offset is cleared before each recompute and with the position; while the check is due, held or overdue locally nothing of the share is shown; Share once and Share for a while are both refused by the view models (`mayShare`, `shareWhileTarget`, the sheet opened from a request) while a share to the asker is active (`activeShareTo` in `:core:data` `location/`, the one rule for Approvals and the sheet: not past `expires_at`, not a delivered once share, and not being stopped, judged per share id through `ServiceState.stoppingShares`; of several, the one that lasts longest) or once the request is no longer listed; a live `location.update` needs `accuracy_m` like `location.get`; coordinates, "Within …", "Updated … ago", the offset when known, **Open in maps app** after "This hands the position to another app." with a `geo:` intent; the confirm reports `HoldsInput`). History titles (`AuditKinds.title(kind, ref, direction)`) are the plan's, by direction, naming "First Last"; `drop.location*` read "Location from … dropped"; History never shows a position. Dev stack: `LocationReceiveDevStackTest` (skips without the stack; a vaultctl peer starts the share, it has no device to send positions). Catalog: `location.incoming`, `location.incoming_waiting`, `location.ask`, `location.ask_limit`, `location.ask_sent`, `location.screen`, `location.screen_empty`, `location.position`, `location.position_no_offset`, `location.request`.
- Location, map (ANDROID-PLAN §4 "Location", L2; owner decision 2026-11-05, signed: (b) OpenFreeMap is the default tile server, style `https://tiles.openfreemap.org/styles/liberty`; texts NEW, none approved except the note): **`:core:map`** is the only module with MapLibre Native (`org.maplibre.gl:android-sdk-opengl` 13.7.1, published 2026-10-15; the plain `android-sdk` is the Vulkan build, which needs a Vulkan device; BSD-2-Clause, no Google code: `:app:checkFossReleaseApk` reads its classes and its four native libraries; `libmaplibre.so` is in `keepDebugSymbols` like the other prebuilt libraries so the APK stays reproducible; ABIs: see the next sentence). **ABIs (owner decision 2026-11-05): a release carries ARM only** (`arm64-v8a`, `armeabi-v7a`): `ndk { abiFilters }` on the `release` build type (both distributions and the `-PvettidCallsInRelease=true` check build; staging clears the inherited filter and keeps all four, `checkStagingCalls` pins them; debug and devStack keep all), and `check<Variant>Abi` (`ReleaseAbiCheck`, run by `checkReleaseApk` and `checkFossReleaseApk`) fails a release APK with `lib/x86/` or `lib/x86_64/`; the calls and wallet checks pin the libraries per ABI (`AbiRules`). **No location component, engine or MapLibre permission use**: the received position is drawn by us (a GeoJSON circle of metres, the trail line and a dot; `MapShapes`). **Memory only, nothing on disk:** `MapSdk.ensure` sets the SDK's logging to NONE first (it could log coordinates), then holds the ambient (tile) cache to `AMBIENT_CACHE_BYTES = 0` and clears it before any map exists; no offline region is ever made; the view's state is not saved (`isSaveEnabled = false`, no `onSaveInstanceState`, no `rememberSaveable`, no `SavedStateHandle` for a position or the camera); the map is destroyed with its composition. The SDK may leave its (tile-less) database file in the app's files directory: its Java API cannot point the cache at memory (it refuses a path that is not a writable file). `MapSdkTest` and `MapPrivacyTest` hold all of this and "no logging call in the map code" (the one `Logger.setVerbosity(Logger.NONE)` line excepted). Unit tests never load native code: the screen asks for a map through `MapSurface` (`PositionScreen(..., map = MapLibreSurface)`), and tests pass a recording fake. **Settings:** `MapServer` (`:core:data` `map/`; `AppPreferences.mapStyleUrl` null = default, `mapNoteHost`; `PreferencesRepository.setMapStyleUrl` / `setMapNoteHost`; settings, not location data): HTTPS only, no user name or password (an `@` before the path), a host, at most 2048 characters (`MapServer.Problem`: NOT_A_URL, NOT_HTTPS, CREDENTIALS, NO_HOST); a stored address that no longer passes reads as the default. Settings → Privacy → **Map server** (`MapServerSettings.kt`, mirrors the wallet's chain server form): the address, **Use default** (stored at once) and **Save**; reports `HoldsInput` while the typed address differs from the one in force (`MapServerUiState.holdsInput`, unit-tested; the file is on `ShellOverlayRulesTest`'s list). **The note** (owner-approved wording) with the host line "This map's server is %1$s." and **Show map** / **Not now** stands in place of the map on the position screen until it is answered **for the current host** (`MapSetting.noteAnswered` compares `mapNoteHost` with the host in force, so a changed server, and going back to the default, ask again; the same host with another path does not); nothing is fetched before Show map (the map is not composed). **Not now** hides the map for that visit only (memory; the note comes again). **On the screen** the map is above the unchanged text (coordinates, precision, Within, Updated, offset, Open in maps app): the latest position, a circle of radius `max(accuracy_m, PositionText.nominalRadius(precision))` (`MapCircle.radiusM`), and the trail as a line when the share allows history (`IncomingShare.trail`) and two points or more are held (snapshot, trail and latest, sorted by time); the age is the "Updated … ago" line under it. It is composed only with a position, so it goes with `share.takeIf { !h }` (the daily check due, held or overdue), with the share's end and with the lock. Attribution: a plain, non-linked line over the map's bottom-left, always shown, readable through the theme's colors: **the fixed credit "© OpenMapTiles © OpenStreetMap contributors" always first**, then only what the style's sources' own credit adds to it (`MapAttribution.combine`: HTML decoded by `HtmlCompat`, words the fixed credit already has dropped, at most 200 characters in all, read again when the map has loaded); MapLibre's own (i) dialog and its logo are off (the dialog builds feedback links from the camera's position and opens links; the logo would sit on our line). **The dot at the position is drawn only for an Exact share** (`MapContent.showPoint`); an Approximate or City share is its circle alone, never a spot. **Network policy** (`MapRequestPolicy`, `MapNetwork`, set in `MapSdk` before any map exists): the SDK loads with a client that **derives from the app's shared one** (`MapNetwork.install(http)` in `AppModule.locationRepository`; `base.newBuilder()` keeps its pool, `NetworkGate` and retry; `HttpClientSourceTest` allows no other client; without an installed client the set-up is FAILED and a fresh client is never made), with the cache cleared, no redirect followed, and our interceptor added **after** the shared ones, so the **User-Agent is a generic "Mozilla/5.0"** and not `VettID-Android` (tested through the derived client). The interceptor checks `HttpUrl.isHttps`, host and port: the map may reach **only the noted server**, https and exactly the style address's host:port (an explicit port other than 443 is another server, and the note is asked again for it: `MapServer.Check.Ok.host` is `host` or `host:port`); a sprite, glyph, TileJSON or tile on another host, and `mapbox:` / `maptiler:` after the SDK turns them into api.mapbox.com / api.maptiler.com, never go out. **Known limit:** the SDK's resource transform sees only the online file source, so `file:` and `asset:` addresses in a style are not stopped by it (it only rewrites the network ones to a refused address): a style could make MapLibre read a local or packaged file to draw it, with nothing sent anywhere. Scanning the style JSON before `setStyle` was judged not solid (sprite and TileJSON addresses inside it are fetched later by the SDK), so it is not done. The default style uses `tiles.openfreemap.org` only (style, sprite, glyphs, TileJSON, tiles; checked 2026-11-05). **Set-up:** `MapSdk.prepare` (called by Show map and by the map itself, never before) silences logging, starts the SDK, installs the network rule and holds the cache to 0; the map is made only when that worked (`MapSdkState.READY`; a cache setting that does not answer within 10 s is FAILED), else "The map couldn't be started."; a map that fails to load says so. `MapController` (with a `MapDrawer` seam) draws every new content and frames the camera for a new share (`MapContent.shareKey` is the incoming share's id) and, after that, follows new positions only until the member pans or zooms. Catalog: `location.map_note`, `location.map`, `settings.map_server`, `settings.map_server_problem`. Settings → Privacy → Map server is hidden while the vault has no location. Tests: `PositionMapTest`, `MapServerTest`, `MapServerSettingsTest`, `MapSdkTest`, `MapSdkSetupTest`, `MapPrivacyTest`, `MapShapesTest`, `MapRequestPolicyTest`, `MapControllerTest`, `MapAttributionTest`, `LocationManifestTest` (no Wi-Fi permission or feature from the map library). **Units (owner decision 2026-11-05):** every distance shown (the offset's "About {d} {dir} of this phone", "Within {d}", the incoming card's "within …", the share sheet's precision notes) is in miles and feet or kilometers and meters. `DistanceUnits` (AUTOMATIC / MILES / KILOMETERS; `AppPreferences.distanceUnits`, DataStore, a display preference, not location data) is set in Settings → App → **Units** (`ChoiceDialog`, applied at once, no `HoldsInput`). `LengthSystems.resolve` (`:core:data` `location/`) turns it into a `LengthSystem` (METRIC / IMPERIAL): Automatic is **the owner's list alone**: region US, LR or MM = miles, every other region (the UK included) = kilometers, and **a locale with no region (plain "en") = kilometers** (no likely region is filled in, no ICU measurement data is read). The view models (`PositionViewModel`, `ConnectionDetailViewModel`, `ShareLocationViewModel`) keep the `DistanceUnits` choice in their state as `units`; the composables resolve it with `rememberLengthSystem` from `LocalConfiguration`'s first locale, so a locale change while a screen is open switches at once. `PositionText` (`distance`, `accuracy`, `offset`) takes the `LengthSystem`; meters round first (995 m is 1 km), and meters, feet and miles use the locale's digits. Imperial text: the nearest 10 ft under 0.1 mi, then miles with one decimal under 10, whole above (locale digits). The "Near this phone" thresholds and the nominal radii stay in meters. The share sheet's notes have `_mi` twins (`connections_location_*_note_mi`). The map draws no distance text (no scale bar). Tests: `PositionUnitsTest`, `DistanceUnitsPreferenceTest`, `UnitsRowTest`, and the units cases in `ReceiveLocationTest` / `ShareLocationTest`. Catalog: `settings.units`, `location.position_miles`, `location.incoming_miles`, `location.share_miles`.
- Notifications and the app lock (owner decisions 2026-11-02): no names whenever opening VettID would show the app lock.
  `AppLock.wouldShowLock()` is true when the state is `LOCKED` or `PENDING`, or the lock is on, the app is in the background
  and the background is due (one shared decision with `onForeground()`, which still alone engages the lock; see the clock rule below); `DISABLED` is never
  covered; a prompt in progress (`authenticating`) does not change that: a prompt left up cannot keep names showing past the timeout.
  The prompt rule (2026-11-03): `onBackground` always records the mark (not on a configuration change) and `onForeground` never
  clears it while a prompt is up; `promptEnded(outcome, inForeground)` decides. Success in the foreground clears it. Success in the
  background sets it to now with a 3 s grace for the late `onStart` (so "Immediately" does not lock right after an unlock); if
  that `onStart` comes later than the grace and the return locks, it locks without the automatic prompt
  (`UnlockPromptGate.holdAutomatic`, as iOS #203): the member taps Unlock once, never a lock-and-prompt loop. A late success for
  an enable already dropped does nothing (`completeEnable` returns when nothing is pending).
  Cancelled or dismissed in the foreground runs the return decision (lock if due, then clear; with "Immediately" this locks after a
  presence check whose screen stopped the activity); in the background it keeps the mark, or records one. The prompt of an enable or
  a method change (`promptStarted(enable = true)`) that does not succeed also drops its pending key (`enableCancelled()`) and then
  follows the same decision as any prompt. The app lock key of a method change is made under a second alias
  (`vettid.app_data.v1.next`, same Keystore parameters) and `completeEnable` swaps it in only after the proof worked
  (`AppLockKeys.promote`); a cancel, a failure or a rotation deletes it and keeps the old key, the old method and the lock on, and a
  first enable that is cancelled stays off with no key left over. A process that died mid-change leaves both keys: `start` calls `recover(appLockKeySlot)`, which keeps the key of the
  persisted slot marker and deletes the other (the lock stays on with the old method).
  `completeEnable` writes enabled, method and the new slot in one DataStore edit (`setAppLock`) and only then deletes the old key,
  so a crash after the edit also leaves both keys and `recover` keeps the marked new one; a marker never written is slot 0 (the
  alias `vettid.app_data.v1`). Turning the lock off in Settings first asks for the phone's biometric or screen lock (`SettingsHost.confirmAppLockOff`, the lock prompt's texts); a refused check leaves it on. `abandonPrompt()` (rotation) clears `authenticating`, drops a pending key and keeps the mark. A callback that arrives after `onDestroy` is ignored (`UnlockPromptGate.finished(owner, outcome)` is false for a
  destroyed owner, and `MainActivity` checks `isDestroyed`), so a rotation records no mark. Accepted fail-closed false locks:
  cancelling a credential presence check after the PIN screen was up at least the timeout locks (the time counts from the first
  stop); "Immediately" + a credential presence check + a rotation on the PIN screen locks after the PIN; "Immediately" + a
  method change + cancelling the PIN screen locks (the old key unlocks); a marked slot whose key was lost from outside the app
  makes `recover` keep the other slot's key, which may be of the other method (a fingerprint may then fail and the phone's PIN
  unlocks); and `authenticating` has no time limit, so a callback lost in the background (no cancel, no `onDestroy`) keeps the
  cover down until the next `promptEnded`. `AppLock.coverFlow(scope)` is the same as a flow (true until read) with
  a timer that turns it true when the monotonic timeout elapses in the background (cancelled on return or lock off; it does not survive
  the process, whose next start is `LOCKED`, and it stops while the process is frozen (a cached process on Android 14+) as well as in deep sleep); `NotifySource.covered` is that flow, `coveredNow()` the direct read. `FeedNotifier`
  reads `coveredNow()` at post time inside its lock: item notifications are then in public form only
  (`LocalNotification.publicForm(quiet)`: the lock screen's "New activity in VettID" / "VettID needs your attention", no names, no
  text, no names or message-text read; a new arrival keeps its sound and heads-up). When the flow turns true every tag still in
  `posted` is re-posted in that form, quietly, if the cover still holds. A real dismissal takes its tag out of `posted` first
  (`NotifyIntents.dismissed`: the delete intent, an immutable broadcast to this app's package carrying only the tag, received by a
  not-exported receiver in `VettIdApplication`, `FeedNotifier.onDismissed`, also called when a tap on an item notification reaches `NotificationOpenInbox`); a snoozed one is still in `posted`, and posting to a
  snoozed tag updates it without showing it, so the named original does not come back; it is also cancelled at the vault lock.
  Clock rule (the lead's decision of 2026-11-03, same as IOS-PLAN §6.4; pending the ANDROID-PLAN erratum): the background time is measured with `SystemClock.elapsedRealtime()`, never the wall clock. `onBackground()` records both (`Background(elapsedMs, wallMs)`); the background is due when `elapsed() - elapsedMs >= timeout` OR the wall clock went back, strict reading (fail secure): `now() - wallMs < (elapsed() - elapsedMs) - 5 s` (`WALL_BACK_TOLERANCE_MS`, only so that a small network time correction does not lock a phone that was away a moment), which also catches a date set back to a time after `wallMs`; it is sticky (`Background.wentBack`, set by any look including `onClockChanged()`, cleared only by `onForeground()` or a new `onBackground()`), so a date set back and then restored still locks. The `elapsed` and `now` clocks of the constructor are passed by name. A date set forward does not lock by itself. `onForeground()` and `wouldShowLock()` call the one function `backgroundDue`; `onForeground()` still locks before clearing the background mark. A reboot resets `elapsedRealtime`, but it ends the process and a new one starts `LOCKED` (`start()`), so nothing compares across a reboot. `Intent.ACTION_TIME_CHANGED` is registered in `VettIdApplication` (`ClockChangedReceiver`): it calls `AppLock.onClockChanged()` (the cover flow decides again at once) and `FeedNotifier.recheckCover()`. The timer runs on monotonic time (stops in deep sleep or while frozen), so the clock is also looked at (`FeedNotifier.recheckCover()`, once per cover) on every arrival and when the screen comes on or the phone is unlocked (`VettIdApplication`'s receiver). The timer looks again when it fires early, backing off from 1 s, doubling to 60 s. For a process that is frozen or asleep there is also an alarm (`CoverAlarm`, `AppLock.syncCoverAlarm`; `AndroidCoverAlarm`, `CoverAlarmReceiver` in `:app` `lock/`): while the lock is on and unlocked, the app in the background and the timeout above 0, an inexact, non-waking `AlarmManager.set(ELAPSED_REALTIME, backgroundElapsed + timeout)` (`CoverAlarm.set(atElapsedMs)`) (no exact-alarm permission; its PendingIntent is immutable and explicit to the not-exported receiver, carrying nothing), cancelled on return, lock, disable and set again when the timeout changes; the receiver calls `FeedNotifier.recheckCover()`, which asks `wouldShowLock()` itself, so an early or late alarm is harmless (an inexact `set` may fire up to about 75% of the wait late, 45 minutes for an hour, so from a wait of 15 minutes `setWindow(ELAPSED_REALTIME, deadline, 10 min)` bounds the lateness to 10 minutes after the deadline, the shortest window without the exact-alarm permission; Doze may still defer it to a maintenance window). A dead process: the receiver starts a new one, whose app lock starts locked; nothing is tracked yet (`posted` is memory), and whatever the old process left showing is cancelled as before (`cancelItems` / `cancelLeftovers` when the vault is seen not open). Not restored when the cover goes. The ongoing call notice (`CallService`) keeps the caller's name under the app lock (owner decision 2026-11-02): by design, not a gap; `CallService` is not changed.
  The withdrawn-request mute (`ApprovalDetailUiState.canMute`) needs a named connection, as History does.

- Calls (CALLS-PLAN 0.1.1 phase C1, in progress; `docs/calls/CALL-MEDIA.md` is the app's note; VAULT-MESSAGING §10.10 is normative): `:core:crypto` `call/`
  (`CallWire` against vettid-vault's `calls.json`, `MediaSuite`: the closed registry of media suites, `CallMedia`: the
  suite in the SDP and the two direction keys, `CallFrames`), `:core:vault` `CallApi.kt` (the seven `call.*` types; call
  signalling is sent with `VaultDevice.sendOnce`, never through the outbox, except `call.end`), `:core:calls`
  (`MediaEngine` / `WebRtcMediaEngine`, `CallMachine` over `CallSignalling`). Rules that are not to be broken:
  **never switch the media stack's logging on** (it logs key material at INFO: `MediaLogGuard`, and `MediaGuardsTest`
  reads every source file for the calls that would); frame cryptors are created and enabled before any SDP is
  applied and never disabled; a failed decryption ends the call (`CallEnd.NOT_ENCRYPTED`), the key is not set again;
  the callee creates no media engine (gathers no address) before the member answers, and never sends
  `call.end{timeout}` (the caller does); a new media suite is a `MediaSuite` entry plus the engine's `suites`, nothing
  else. Calls are **not in production builds**: `:core:calls` is linked into debug, devStack and staging only
  (`-PvettidCallsInRelease=true` links it into release for CI's "F-Droid APK with calls" step), the APK with calls has
  every ABI in debug and staging (a release is ARM only: see "Location, map (L2)"), and `:app:check<Variant>Calls` (run by `checkReleaseApk`, `checkStagingApk`,
  `checkFossReleaseApk`) fails a release APK with any of it and a staging APK whose native libraries are not the
  pinned ones. The post-quantum DTLS field trial (`MediaConfig.dtlsPqcTrial`) is off; only a debug switch may set it.
  `WebRtcMediaEngine` has no unit test (it needs the native library): two phones are its test.
  In the app: `CallAccess` (`:core:data`) is all that every build knows of calls (`NoCallAccess` in production,
  `app/src/noCalls`); `app/src/calls` wires `CallsController` (`:core:calls`: the `call.list` probe after unlock,
  which is how the app learns that a vault has calls; the stale-call sweep; microphone, audio route, tones,
  `CallService` of types `phoneCall|microphone`; the end at lock and after 12 h) on `VaultManager.vaultApi()`.
  `VaultCallSignalling` is where a call's checks are asked for: nothing reaches `CallMachine` that has not passed
  `callIncoming` / `callAccept`, each against the fingerprint the connection's details show. Only `CallApi` makes
  `IncomingCall`, `OutgoingCall`, `AcceptedCall`, `CallKeys` (`@MadeByCallApi`; tests opt in, production code never).
  A release APK must not contain the call strings of `callsMarkers` (app/build.gradle.kts): no code that ships in
  release may reach `CallApi`. A release built with `-PvettidCallsInRelease=true` has the application id
  `com.vettid.app.callscheck`: a check build, never published.
  The other side's SDP reaches the media stack without `a=ssrc` lines (a signalled SSRC would leave every other
  SSRC of the section outside the frame decryption); `MediaFaults` and the DTLS trial are for test builds only
  (`BuildConfig.CALLS_TEST`: debuggable builds, or staging built with `-PvettidCallsTest=true` for the two-phone
  tests, which need a build the staging vaults accept). `VaultManager.onBeforeLock`: a live call's hang-up reaches
  the vault before the lock (2 s at most). `CallSignalling` and `CallMachine` are internal to `:core:calls`.
  Screens (`:feature:calls`, texts approved by the owner 2026-10-12, `CallTextsTest` holds them word for word; a
  changed text is the owner's to approve): `CallOverlay` over the open app's shell (`CallsHost` in `app/src/calls`,
  inside the app lock and the unlocked destination: a call rings only once the vault is open on this phone, and no
  call screen is ever in front of a locked app), `CallScreen`, `MicrophonePrompt`; the call button and the call
  entries of a conversation and the detail's "Call" use `CallAccess` only. Catalog: `calls.outgoing_calling`,
  `calls.outgoing_ringing`, `calls.connecting`, `calls.incoming`, `calls.incoming_no_name`, `calls.in_call`,
  `calls.in_call_muted_speaker`, `calls.in_call_reconnecting`, `calls.ended_hung_up`, `calls.ended_peer_hung_up`,
  `calls.ended_declined`, `calls.ended_busy`, `calls.ended_no_answer`, `calls.ended_missed`,
  `calls.ended_no_address`, `calls.ended_could_not_connect`, `calls.ended_connection_lost`,
  `calls.ended_not_encrypted`, `calls.ended_owner_check`, `calls.ended_failed`, `calls.microphone`, `calls.test`,
  `calls.test_refused`, `messages.conversation_calls`, `messages.conversation_in_call`,
  `messages.conversation_calls_not_available`, `connections.detail_call`. The call-test build (README "Calls and
  the call-test build"): `-PvettidCallsTest=true`, checked by `:app:checkStagingCallsTest` (marker in manifest and
  dex agree; `-PvettidCallsTestChecked=true` to pass; signed with the automation key only), a banner, and the test
  screen (`CallTest`: stable, untranslated labels for `uiautomator dump`), whose switches work only while the vault
  reports this app's signer class as `development` (`CallsController.testsAllowed`).

- Devices & agents (ANDROID-PLAN 0.1.53 "Devices & agents", step AA1 of AA1-AA5; `:feature:devices`, `DevicesManager` /
  `DevicesRepository` in `:core:data` `devices/`, `VaultManager.devices`): the drawer destination after Vault, the list
  (`device.list`, `leash.grant.list` for the paused agents; memory only, cleared with the feed at lock and at the hold,
  `PhaseSteps.DEVICES`; re-read on `sync.event` `device.paired|unlinked|session`, `leash.agent.suspended`), a device's
  page, **End access** (`device.session.end`) and **Remove** (`device.unlink`), both confirmed, neither signing anything
  (no unlock window, no password). This phone's entry has no actions and nothing is sent for it. Ending access or removing
  takes the device's entries out of Approvals (`ApprovalsRepository.dropDeviceRequests`). The remove text names the
  longest `status_ttl` of the agent's grants plus 60 s, the worst case (3,660 s) when the vault cannot be asked. A device's
  name is its own, self-asserted and never edited. Pairing an agent (AA2; `PairingManager` / `PairingRepository` in
  `:core:data` `devices/`, `VaultManager.pairing`, `PairViewModel` / `PairScreen`, `PairRoute`): the floating button on the
  list; `device.pair.create{role: "agent"}` (the link must parse as an agent pairing, else it is never shown), the QR and
  the bare code (Copy marked sensitive and cleared by `PairingClipboard`, which keeps only a hash and clears when the
  pairing ends or on the next window focus after the code expired; Share sends the code alone), `FLAG_SECURE` over the
  whole flow, the countdown to `exp` (never more than 10 min + 1); `device.pair.pending` for the pairing this phone made
  shows the six digits and the agent's sanitised name, and only **The codes match** opens the sheet (first access 1 h
  preselected) which sends `device.pair.approve{pairing_id, session_seconds?}` with no grants (no unlock window); a
  pending that is not for an agent or has no six-digit code offers only Reject; any refused approval (the owner check
  excepted, which keeps the comparison) is "This pairing has ended" and nothing is paired; leaving the screen rejects
  (`PairViewModel.onCleared`; `navigateTopLevel` pops `PairRoute` so state saving never keeps it alive); a pairing taken away
  by a lock shows "This pairing has ended" (`PairUiState.interrupted`); an approval with no answer is "Pairing not
  confirmed" unless the feed item or the list shows the new agent; the pairing is cleared with the list (`DevicesManager.onClear`). The new agent's id comes
  from the `device.paired` feed item, else the one new agent of that name in `device.list`, else the list opens.
  Permissions (AA3) is below; Approvals for devices (AA5) is below; screens are in the catalog (`devices*`).
  **Pair a desktop** (owner decision 2026-11-05, reversing ANDROID-PLAN decision 9; VAULT-MESSAGING §6.7, §6.7.1, §6.8, §10.3):
  the list's floating button (`pair_new`, "Pair a device") opens a menu with **Pair an agent** and **Pair a desktop**
  (`PairChooser`); the same flow, parameterised by `PairRole` (`AGENT`/`DESKTOP`: the `role` sent by `device.pair.create`
  via `devicePairCreate(role)`, the `device.pair.pending` role the SAS is read for, the link kind `t` = `a`/`d`, the
  `device_id` search by role). `PairRoute(desktop)`, `PairViewModel` starts that role (a pairing still held for the other
  role is cancelled first) and every `PairingState` carries `role`, so the screens say what is held, never what the button
  was. A link of the other kind (an agent link for a desktop, or the reverse) is "unreadable" and never shown. The spec
  treats the two alike: the six-digit SAS compare applies to a desktop (§6.7 flow, the `sas_commit` is for purposes `app`,
  `desktop`, `agent`, `connection`), `session_seconds` (60-86,400) applies to a desktop (§10.3), so the first-access sheet
  keeps the same choices; `grants` is for an agent only, and this app never sends any. A desktop's paired screen says it is
  paired and that its access requests come to Approvals; it offers only **Done** (no "Choose what it may use", no rules or
  permissions). Desktops are listed, ended and removed as before and their session requests are approved as before. The
  desktop's texts are `*_desktop` strings marked `NEW: not in the plan`; the agent's are unchanged. Catalog: `devices.pair_desktop_*`.
  **The role switch is atomic** in `PairingManager.start(role)` (under its lock): a pairing held for the other role (a live or
  expired code, a comparison not being approved) is replaced and its id rejected in the background; a create of the other role
  still in flight is superseded (`generation`), its answer is dropped and rejected when it arrives (any dropped create is
  rejected, a lock's too); an approval in flight is not switched. `PairViewModel` owns its role and never takes it from a
  cleared state (a lock or hold clears the repository to AGENT): the route's role, or for a held screen the role of the last
  non-idle pairing it saw (`seenRole`), also for "New code" and for the words of "This pairing has ended". The chooser button
  announces a menu (`Role.DropdownList`, "Menu open/closed"). Approvals: a held desktop pairing's entry says "desktop"
  (`ApprovalDetailUiState.pairingDesktop`, `approvals_pair_compare_body_desktop`).

- What an agent may use (ANDROID-PLAN 0.1.116 §4 AA4, decisions 1-8, 17 and 18; VAULT-MESSAGING 0.83.2 §10.12, §10.11, §11.10.9):
  an agent's share rules, on the agent's page under **What it may use** (`share.rule.list{agent_id}` via
  `SharingRepository.agentRules`, read on opening and on every return to the page; `ShareRule.agentId`, `statusTtl`) and in the
  connection rule editor, reused (`RuleEditViewModel` / `RuleEditScreen` in `:feature:items`; the route `AgentRuleRoute` is in
  `:core:data` `devices/` so `:feature:devices` can open it). One tag per rule as for connections (0.1.22). **The password is asked for
  every save**, even with the unlock window open (decision 4): `SharingRepository.saveAgentRule(draft, password)` checks the rule first
  (nothing is unlocked for a rule the app would refuse), sends `credential.unlock`, then `share.rule.set` (agent subject, `per_hour` /
  `per_day` always, `status_ttl`), then `credential.lock` **only if the window was closed before the save**, in a `finally` that is
  not cancellable: so after a refused save too, and after an unlock whose answer was lost (it may have opened the window); not after a
  definite refusal of the unlock (six: `bad_password`, `backoff`, `credential_frozen`, `rotation_required`, `owner_check_required`,
  `credential_unsettled`). The window counts as open only with more than 30 s left (clock skew), so a borderline one is locked by the
  save that reopened it. Known limit, not built: no app-wide mutex with the votes' or the wallet's window use, so a lock after a
  rule save can end a window another flow opened a moment before (it then gets `credential_locked` and asks again). A window that was
  already open is left as it was. A lock that fails is `SharingRepository.windowNotClosed` (the votes' notice, **Close it now**).
  The password is in `RuleEditUiState` only while the request runs; the state prints only its type name. `credential_locked`
  answers the prompt again and sends the same request once; a second one is "Your credential locked again before the rule was saved.
  Try again."; `conflict` re-reads the rule ("Another device changed this rule. Here is the current one."); a wrong password, a
  backoff and an open credential alarm keep the prompt (the alarm with a link to it). The dry run (preview) is sent without the
  window; its summary counts only what the vault's `outcome`s say (an answer without them is "0 will be asked about, 0 included now"; the
  connection editor still guesses from its mode). Deleting a rule (`Stop sharing`) asks no password. **`item_ways` (decision 17):** `Capabilities.ITEM_WAYS` /
  `ReleasePath.itemWays(local.capabilities)` / `SharingRepository.itemWays`; without it **Include tagged items automatically** is not
  offered: `saveAgentRule` refuses `auto` for every rule, new or existing, before anything is unlocked, and an agent's draft cannot go
  through `saveRule`. Owner decision 2026-11-02 (b): an existing automatic agent rule on a vault without `item_ways` opens as Ask
  (Automatic shown, disabled, with "Save to make this rule ask you first.", and the editor is dirty, since the vault still includes automatically until it is saved; the agent page's row shows the stored mode)
  and saves only as ask (`RuleEditUiState.autoShown` / `autoEnabled` / `autoDemoted`, `RuleDraft.storedMode`). The editor and every row carry "On this vault, the agent
  may receive the values of the items it uses, and its app may keep them in its history." (`core_ui_agent_ways_warning`). Texts in
  `strings_sharing.xml` (`items_agent_*`), `feature/devices/.../strings.xml` (`devices_rule*`, `pair_*`) are marked `draft` (the plan's, word
  for word; `AgentRuleTextsTest` in both modules holds them) or `new`; none is approved. The paired screen now offers **Choose what it
  may use** (the agent's page with the editor on top; the list when the id is unknown) and **Later**. Left to later PRs: how the agent may use each
  item (AA7). Dev stack: `AgentRulesDevStackTest` (skips without the stack; the
  full flow needs a `vaultctl` agent paired to the app's vault). Catalog: `items.agent_rule_new`, `items.agent_rule_new_ways`,
  `items.agent_rule_edit_auto`, `items.agent_rule_auto_no_ways`, `items.agent_rule_preview`, `items.agent_rule_more`, `items.agent_rule_invalid`,
  `items.agent_rule_password`, `items.agent_rule_password_wrong`, `items.agent_rule_password_backoff`, `items.agent_rule_password_alarm`,
  `items.agent_rule_sending`, `items.agent_rule_locked_again`, `items.agent_rule_conflict`, `items.agent_rule_window_open`,
  `items.agent_rule_limit_grants`, `items.agent_rule_limit_subject`, `items.agent_rule_limit_vault`, `items.agent_rule_not_paired`,
  `items.agent_rule_owner_check`, `items.agent_rule_delete_confirm`, `devices.page_agent_rules`, `devices.page_agent_rules_ways`,
  `devices.page_agent_rules_warning`, `devices.page_agent_rules_none`, `devices.page_agent_rules_failed`, `devices.page_agent_rules_limit`,
  `devices.page_agent_rule_saved`, `devices.pair_paired_unknown`.

- An agent's permissions (ANDROID-PLAN 0.1.117 §4 AA3, decisions 3a, 7, 8; VAULT-MESSAGING 0.83.3 §10.11): on the agent's page,
  **Other permissions** = the agent's grants of `leash.grant.list{agent_id}` that are not `items.read` (`PermissionWire.permissions`;
  `DevicesRepository.permissions`, `refreshPermissions`, memory only, cleared with the list at lock and hold; an older answer never
  replaces a newer one: a read number per agent). **One source per card:** **What it may use** stays `share.rule.list` (AA4, richer
  rows); the `items.read` grants (`grant_id` = `rule_id`) are dropped from the permissions, never listed twice. The "Other permissions"
  heading is shown **only when the agent has such grants, or the latest read failed, or `suspended` was never read for the agent
  (`DeviceInfo.pausedKnown`)**, and it **never has an add action** and there is no editor (decision 3a; a row's tap opens nothing); with no such grants the card is not shown at all. When the **latest** read failed
  (even after an earlier success: the earlier rows stay below) or the agent's pause is unknown, the card says "Other permissions
  could not be loaded." with Try again (`DeviceUiState.permissionsUnreadable`), so a grant issued elsewhere never goes unseen.
  Grants that do not read are **not dropped**: each is a row "Another permission" (a grant without `grant_id` has no ⋯ menu, it
  cannot be revoked). A row: the scope's label (nine labels, "Another permission" for a scope
  the app has no label for), "Asks you each time" or "Allowed automatically, up to N an hour and M a day without asking" (an `auto`
  grant without limits shows the vault's defaults 60 and 1,000; an absent `approval` is ask, the vault's default; one the app does
  not know is `GrantApproval.UNKNOWN`: no approval line is shown, never "Asks you each time"), then ", until
  <date>" and ", only with <names>" (`DeviceUiState.connectionNames` from `ConnectionsRepository`; "Removed connection" for an id not
  listed **only once the list was read** (`connectionsLoaded` = the page's own `connections.refresh()` at opening answered; a cached list never counts, so a
  new connection is not called removed; before that, or when it fails, no names are said); "Name not shared yet" for a blank name). Trailing ⋯ → **Remove** (`ConfirmDialog`, destructive: "<name> can no longer <label,
  lower case> from now on. Services it showed this permission to may accept it for up to <that grant's status_ttl + 1 min>.";
  a scope without a label has its own sentence): `leash.grant.revoke{grant_id}` with **no unlock window and no password** (the
  plan, not the task title); `{}` "Permission removed", `not_found` "It was already removed.", and the view model reads the grants again after both
  (`DevicesManager.removePermission` itself reads nothing; a re-read that fails is shown on the card and does not turn the done
  removal into a failure); any other failure shows its error, keeps the row and reads nothing. **Paused:** a banner at the top of the page (`NoticeKind.URGENT`, "Paused by your
  vault") whenever `DeviceInfo.paused`. `paused` has **one ordering**: the list read and the permissions read share one read numbering
  (`DevicesManager.permissionReads`, `pausedApplied` per agent) and a read never overwrites a `paused` set by a newer one, so a
  list read that started before Resume's re-read cannot bring the pause back; a list read whose `grants(null)` call fails keeps the
  `paused` values it held (fail closed), and with nothing held `paused` is **unknown** (`pausedKnown = false`), never "not
  paused": the page then shows the failed card; **Resume** = `leash.agent.resume{agent_id}`,
  no unlock window, no confirmation: `{}` "Resumed", `not_found` "It is no longer paused", read again after both by the view
  model (if that re-read fails the banner stays up and the failure shows). **Re-reads:**
  `DevicesManager.onEvent` emits `AgentChange` (`changes`): `sync.event` `leash.grant.changed|revoked`, `leash.agent.suspended` with
  this `agent_id`, and `share.rule.changed|deleted` (only `rule_id`: the app knows no rule it can tell is another subject's, so every
  one re-reads the permissions and the rules); also on opening and on every return to the page. **`leash.grant.issue`, core only (no
  UI):** `LeashApi.kt` (`LeashGrantRequest`, `leashGrantIssue`, prints nothing) and `GrantIssueAnswers` / `GrantIssueResult` in
  `:core:data` `devices/`: `Saved(grant)`, `CredentialLocked` (the caller prompts once and resends once; a second one is `LockedAgain`,
  `refused(e, resent = true)`), `Conflict`, `Limit` (`agent_grants`), `NotPaired`, `BadRequest(field?)` (the field only when it reads
  as a plain member name), `OwnerCheck`, `AlarmOpen` (`unlockRefused`: `credential_frozen` / `rotation_required` from the prompt's
  unlock), `Failed(failure)`. The plan's answer texts are not in strings (unused). Texts in `feature/devices/.../strings.xml`
  (`devices_perm*`, `devices_scope_*`, `devices_paused_*`, `devices_resume*`) are **approved by the owner 2026-11-03**, the plan's words and the ones the plan
  does not give (`AgentPermissionTextsTest` holds them). Dev stack: `AgentPermissionsDevStackTest` (skips without the stack; the paired-agent branch needs a
  `vaultctl` agent, and a real suspension needs an agent that sends 30 refused requests: never run). Catalog:
  `devices.page_agent_permissions`, `devices.page_agent_permissions_failed`, `devices.page_agent_permissions_failed_rows`, `devices.page_agent_permissions_unreadable_grant`, `devices.page_agent_paused`,
  `devices.page_agent_paused_failed`, `devices.page_agent_permission_remove`, `devices.page_agent_permission_remove_unknown`,
  `devices.page_agent_permission_remove_failed`.

- Approvals for devices and agents (ANDROID-PLAN 0.1.116 §4 AA5, decisions 6, 9, 10, 15, 16, 18; VAULT-MESSAGING 0.83.2 §6.8, §10.3,
  §10.4.2, §10.11): a device request is `Approval.DeviceRequest` (`seconds` of `device.session.pending`; `held`: `HeldRequests.of(role,
  type, body)` of `approval.pending`, a `HeldRequest` that prints nothing; `fromFeed`). **Access request:** the screen offers 15 minutes,
  1 hour, 4, 8, 24 hours with the requested length added and preselected (1 hour when unknown; `DeviceAsks`), **Allow** =
  `device.session.approve{request_id, seconds}` (answer `expires_at` -> "Access allowed until …"), **Deny** = `device.session.deny`; a renewal
  keeps #226/#227 (notice, Deny and end access now, `Renewal`, the end failure) and adds Allow. **Held approval:** Approve =
  `approval.decide{approve: true}`, Deny = `{approve: false}`; the answer's `result` is said (`ok` Approved, `forbidden`, `session_required`
  or `denied`, `way_not_allowed`, any other with the code cleaned and isolated); only `not_found` (VAULT-MESSAGING 0.83.0 §10.3) to either call is
  "This request is no longer waiting." and the entry goes (`decideRun`); any other failure (`internal`, `bad_request`, `forbidden`,
  `rate_limited`, the owner check, transport) shows its error and keeps the entry. A missing `result` is "Your vault didn't say whether it was
  done." (not Approved) and a session answer without `expires_at` is "Access allowed". `item.use` shows its item and field (names from the item cache, `ItemSummary.fieldLabels`) and `data` decoded
  (`DataView`: strict UTF-8 is text unless it has a control (other than LF and tab), format (bidi), Default_Ignorable (Hangul fillers, variation
  selectors, CGJ, tags), separator, private-use or unassigned code point, more than 2 combining marks in a row, or more than 8 lines; else
  lower-case hex; the braille blank U+2800 and a run of more than 8 blanks (tabs, Zs) too; some scripts (Burmese, Tibetan, Hebrew with cantillation) legitimately have 3 or more marks on one base and fall back to hex), shown in a bordered box with "N bytes",
  `item.get` its fields (an empty list, a repeated id or more than 64 are Unshown) or "All fields and notes" and always the history line, the catalog its line; `item.run` / `item.proxy` (decision 15),
  a desktop's step-up (decision 9), any other type or op, a body that does not read in full, and an item or field the cache cannot name ("an
  item you no longer have"; "Loading…" while the cache is still being read) are **Deny only**, enforced in
  `SocialManager.approveDeviceRequest` too (`NOT_SUPPORTED`, nothing sent): it reads the item cache (`itemCache`, `HeldRequests.nameable`) and, for a
  feed-only session request, `device.list` (`deviceListed`: read, and the device in it). The delegable owner-type row is not built (needs AA3's labels; it is "any other type": deny-only). **Known only from the feed item**
  (`FeedOnlyAsks`, `SocialManager.setFeedItems` from `VaultManager`): after a restart the feed item's `ref` / `device_id` / `at` make an entry
  only while no `pending.list` was merged since the unlock (`listReadFlow`; the list is authoritative once read for sessions and approvals, not for
  pairings, which the vault does not list yet (0.83.0): their entries stay until their 10 minutes, and VAULT-MESSAGING 0.70.0 is why
  decision 14a is now the fallback for an unsupported or failed list), not once archived or deleted (read stays), not after a close event or a
  decision here, and only 10 minutes (session, pairing) or 5 minutes (held) from `at`. A feed-only approval is Deny-only with "… asked for
  something this phone can no longer show. Deny it, or let it expire."; a feed-only session request names the device from `device.list`
  (`DeviceAsks.named`) and is Allow/Deny with 1 hour preselected, Deny-only when the device is not listed (or the list not read). A
  `device.pair.pending` feed item is an entry whose only button is **Reject** (owner decision 2026-11-03: `device.pair.reject{pairing_id: <the item's ref, the request's pending_id>}` per VAULT-MESSAGING 0.83.0, `SocialManager.rejectPairingRequest`; the ViewModel's `deny()` routes there; only `not_found` is "This request is no longer waiting.", any other failure shows its error and keeps the entry, nothing is sent without a ref; no confirm dialog, as Deny has none; nothing is ever approved or paired from it) under "This pairing can't be checked on this phone any more …" (`approvals_pair_body`, now "… so it can't be approved here. Reject it, or let it expire …", owner-approved 2026-11-03), or, while
  `PairingRepository` holds the comparison and the item's `ref` is the held pairing's `pending_id` (`PendingPairing.pendingId`; the entry goes when that comparison is left, approved, rejected, ended or cleared: `PairingManager.onCompareLeft` -> `SocialManager.closePairing`; any other
  pairing item gets the unnamed title and no Compare), **Compare the code** (`DecisionActions.onOpenPairing`, `PairRoute(held = true)`). **Feed and notifications:**
  titles with the device's name from `FeedNames.devices` (`device.list`, cleaned and isolated; fallbacks without a name) and
  `FeedNames.pairingName` / `pairingPendingId`; `leash.agent.suspended` and `leash.referrals_limited` are on **Security alarms** (decision 16,
  `NotificationMapping`; the lock screen says "VettID needs your attention") and, with `leash.rate_limited`, open the agent's page
  (`FeedTarget.Device`; no longer "not available"); under the app lock's cover (`coveredNow()`) names are never read, as for every item.
  Catalog: `approvals.device`, `approvals.device_agent`, `approvals.device_renewal`, `approvals.device_feed`, `approvals.device_feed_unlisted`,
  `approvals.device_allowed`, `approvals.device_gone`, `approvals.held_use`, `approvals.held_use_hex`, `approvals.held_get`,
  `approvals.held_get_all`, `approvals.held_catalog`, `approvals.held_later`, `approvals.held_desktop`, `approvals.held_other`,
  `approvals.held_no_item`, `approvals.held_gone`, `approvals.held_approved`, `approvals.held_denied`, `approvals.held_forbidden`,
  `approvals.held_session_ended`, `approvals.held_way_not_allowed`, `approvals.held_other_result`, `approvals.pair_gone`,
  `approvals.pair_held`, `approvals.device_list`, `notifications.devices`, `notifications.devices_unnamed`. Texts are marked `draft` (the
  plan's, word for word) or `new` in `feature/approvals/.../strings.xml` and `core/data/.../strings.xml` (`data_feed_*_named`); none is approved.
- **AA6 part 1:** the detail sheet of `leash.item.run` / `leash.item.proxy` keeps its title and adds History's approved detail line (`data_feed_leash_item_run_detail` / `_proxy_detail` in `core/data/.../strings.xml`, copies of `history_detail_leash_item_run` / `_proxy`, word for word; `LeashItemDetailTextTest` in `:feature:history` holds both copies) and "Open the item" (`notifications_sheet_open_item`, the same text as `history_detail_open_item`; `NotificationsViewModel.openSheetItem` opens `FeedTarget.Item`), shown only while `FeedNames.items` knows the `ref` (`FeedKinds.sheetItemId`); a deleted item gets the line and no action. Catalog: `notifications.sheet_leash_run`, `notifications.sheet_leash_proxy`, `notifications.sheet_leash_deleted`. **AA6 parts 2-3** (texts approved by the owner 2026-11-03): `leash.item.read` is "An agent read %1$s" / "An agent read an item" in the feed (`data_feed_leash_item_read`, `_read_any`; name from `names.items[ref]`, none under the app-lock cover) and History (`history_kind_leash_item_read_named`, plain `history_kind_leash_item_read` is the no-name form and the hourly summary's), with the detail line "The agent was given the item's values. Its app may keep them in its history." (`data_feed_leash_item_read_detail`, `history_detail_leash_item_read`; its sheet opens the item like run/proxy; the catalog `notifications.sheet_leash_read`); `leash.item.used` in History is "An agent used %1$s without seeing it" (`history_kind_leash_item_used_named`; plain is the no-name form and the summary's) with "Your vault gave the agent a code worked out from the value, not the value itself." (`history_detail_leash_item_used`); the hourly `.summary` entries show the no-name title plus "(hourly summary)" and no detail line; the other History agent titles (owner-approved 2026-11-03, `history_kind_leash_*`): "An agent was paused / was resumed / reached a limit / asked too often", "An agent's requests were slowed down", "You gave an agent access", "You changed an agent's access", "An agent's access was removed", "An agent's request was allowed / was refused"; the hourly summaries keep " (hourly summary)" after them.

- Votes (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes", item 5.1; VAULT-MESSAGING 0.81.3 §10.19; `:feature:votes`, `VotesManager` /
  `VotesRepository` in `:core:data` `votes/`, `VoteApi.kt` in `:core:vault`, `VaultManager.votes`): the drawer entry after Approvals
  (shown only when the vault answers `vote.list` and holds a vote or a `reconnect` connection; its badge counts open votes not yet
  voted on), the list, a vote (the service's words as plain text, the choices as sent, the fixed disclosure, the confirm sheet, the
  unlock window prompt when the window is closed, the states in words, the results only after the vault's check, **Save the proof**,
  **Release** with the vault PIN, "Is this your vote?"), "Votes from <name>" and "Connect again to vote" on connections, the feed
  items and History titles. A vote is identified by the pair (`connection_id`, `proposal_id`) everywhere (`VoteKeys`); memory only,
  cleared at lock and hold with the devices list (`PhaseSteps.VOTES`). `VoteRules` (pure) maps what the vault decided to a chip and
  to notes (`VoteNote`); every text of the plan's table is in `feature/votes/.../strings.xml` and `VoteTextsTest` holds the approved
  ones word for word (a changed text is the owner's to approve; the strings under "Proposed" there are not yet approved). The
  vote screen is `FLAG_SECURE`; the proof is written only where the member's "Save to…" points, only on their action, never to a
  log, cache or share sheet. The app's work with votes.vettid.org (`VoteSite`, `VoteSync`, `VotesManager.startSync`: hourly while
  the vault is open and unlocked, no cookies, session or identifier; `vote.site.sync` within the per-call limits, a request over
  245,760 bytes as a claim-check blob; (VAULT-MESSAGING 0.81.5) each site item checked against the vault's shape limits first and
  left out or replaced if it would make the vault refuse the call (`VoteSync.shaped`, `shapedIndex`, `sendable`, `submittable`), and
  the lists, the entries and each results submit as separate stages, so one bad item stops nothing else; the member's row and its
  Merkle proof from `rows.json` for `vote.results.submit`, `VoteRows`
  in `:core:crypto` against vettid-vault's `vote.json` vectors) never decides anything: the vault checks all of it again.
  `VoteRows.read` reads `results.json` and `rows.json` (`RowsReader`: strict JSON, nested at most 4 deep, **no text kept**, only
  each row's decoded tag, leaf hash and, when they have the protocol's lengths (32, 64, 1 to 32 bytes), `vk`, `ballot_sig` and
  `choice_id`: at most 208 bytes a row whatever the input; a ballot of other lengths is only flagged (`ballot_shape`), except the
  member's own row, kept as published; rows.json at most
  `VoteRows.rowsCap(counts.eligible)` = 512 × eligible + 4,096, never over 51,204,096 bytes, the download capped the same way).
  **Only what VAULT-MESSAGING 0.86.4 §10.19.6 step 1 names stops the submit of a row**: the cap, the depth, a row that cannot
  be made into a leaf, and what the vault's check of the row needs (`counts.eligible` rows reaching `rows_root`; the member's
  row, the first under its tag; a valid own `choice_id`). `PublishedResults.observe` is the rest of the observer's check
  (BALLOT-PROTOCOL §8.4) and also reports the `rows_hash`, the tag order and duplicates, the field lengths, members other than
  §8.5's (`pad` is not allowed in rows.json), the documents naming another proposal and rows.json not exactly `{v: 1,
  proposal_id, rows}`. `checkResults` runs the reading, the proof and `observe` in one `withContext(compute)` (Dispatchers.Default; the
  parse of results.json for `rowsCap`, needed before rows.json is fetched, runs on `compute` too),
  with `ensureActive()` every 256 rows and tree nodes (a cancellation stops it there: leaving the screen ends its
  `viewModelScope`, a lock cancels the sync job and so the due checks; `clear()` also cancels every check in flight, Check again
  included: each runs in its own child job (`checks`), so its caller is not cancelled but answered `ResultsOutcome.Voided`,
  and a `generation` taken at its start voids whatever still returns: no hint is written and nothing is submitted after a
  lock or clear; `VoteViewModel.checkWithService` resets `checking` in a `finally`, so no ending leaves Check again disabled), under a lock per vote (one vote's long check holds up no other), and
  compares the vault's own `tally` and `counts` (`vote.get`, when it holds them) too; any exception in the observer's work is an
  observer failure, and so is an `observe` that runs past its time box in one attempt (`VotesManager.observeLimitFor`:
  `max(60 s, counts.eligible x 3 ms)`, 300 s at 100,000 rows; the 60 s floor is the injectable `observeLimit`;
  `withTimeoutOrNull` around the observer alone, so results it cannot finish in time still get submitted, flagged). It **still submits** whenever the inputs above hold (0.83.2): a failure only sets `VoteHint.observerFailed`
  (set before the submit, so a refused or lost one keeps it; an attempt that submits nothing clears it), and a submit the vault
  answered sets `VoteHint.verdict` (`ObserverVerdict`), which nothing else changes. With a `verified` or `unconfirmed` check and
  the flag, the screen shows the state, then `vote_results_dont_add_up` once as a plain note (`VoteNote.RESULTS_DONT_ADD_UP`);
  numbers show only with a `PASSED` verdict (`VoteRules.showNumbers`), so none after a failed verdict, and none with no verdict
  in memory (after a lock or a restart, until a check runs again); it is not the results-problem text.
  **0.83.6 (and 0.83.3, 0.84.1):** every `choice_id` the app sends carries `pad` (`VotePad`: ASCII `0` × (32 − its UTF-8
  bytes), empty from 32): `vote.cast` (`voteCastBody`, one size for ids of 1 to 32 bytes) and each submitted `row.ballot`;
  `pad` is never read or checked anywhere (§5.3 exception). With the release capability `vote_rows`
  (`Capabilities.VOTE_ROWS`, `ReleasePath.voteRows`; without it the app keeps 0.83.2's behaviour): further rows under the
  member's tag go as `other_rows` (at most 4, kept as published by `RowsReader.ownExtras`, `PublishedResults.otherRows`
  with their proofs), and when the member's row cannot be had (rows.json over its cap, deeper than 4, a row that cannot be
  made a leaf, `counts.eligible` rows not reaching `rows_root`, no row for the tag, an own `choice_id` the vault would
  refuse, a `counts.eligible` that is an integer outside 1 to 100,000, or a definite 404 for rows.json while the signed
  results.json is there) the app submits `rows_unavailable: true` with the results, signature and bundle and no row. Extra
  rows too large for the request also fall back to `rows_unavailable` (the lead, 2026-11-06: the record then stays in the
  problem set). A rows.json that cannot be reached (no answer, a timeout, a 5xx) is tried again, not `rows_unavailable`; a
  404 counts as rows not had, so the next re-check fetches it again. `pending` or `gave_up` with reason `rows_unavailable`
  shows the results-problem text. **A `gave_up` check is not final** (owner decision a, thread 01M6HK8A1VMV41M8TTASXV4BSK):
  `submitDue` runs it again whenever the app opens, at most once a day per record, keyed off the state, and looks at the
  daily limit again under the vote's lock (`checkDue`, review 1 L2); the member's **Check again** is not held by the daily
  limit but is by the skip below, and an `Unchanged` one keeps the last attempt's time, so it does not defer the daily re-check (#314 item 3), and shows
  nothing more: it sent nothing and contacted nobody, and
  the vote's own state line already says where it stands (the lead's review of #312, M1, replacing review 1's L5 notice). The last attempt per vote is kept on disk (`ResultsAttempt` in
  `vote-attempts.bin`, `KeystoreResultsAttemptStore`, no-backup dir): the time, the SHA-256 of the `results.json`, `.sig`
  and `.sigstore.json` read (the last two always fetched, small; review 1 M4, the coordinator's call, a step past
  §10.19.6's literal list), whether rows.json was had, whether the vault answered the submission (M2), whether that
  submission carried the member's real row and not `rows_unavailable` (`sentRow`, the lead's review of #312, M2: a broken
  rows.json may be corrected under the same results.json) and whether its answer was on its side (`trust_root`,
  `service_list`, `clock`); **never a choice, row or proof**. With the same three hashes, rows had, an answered
  submission of a real row and no vault-side answer, a `gave_up` re-check downloads no rows.json and sends
  nothing (`ResultsOutcome.Unchanged`); an attempt that failed (no answer) counts as rows not had, and one cancelled or
  voided by a lock after it downloaded rows.json still counts toward the daily limit, as rows not had (L1). So does an
  attempt cut off (a lock, `clear()`, backgrounding, the time box) after **any** rows.json bytes arrived, whole or partway
  (#322, shared with iOS #224): it is recorded with `lastRowsMs` (the weekly metered cap counts it) and as what it
  achieved (nothing answered or sent), under the store epoch and only over a readable store; one cut off before any rows
  byte records nothing. The entry goes
  when the check is final or the vote leaves a whole list. **A wipe, sign-out or deletion** calls `votes.forgetStored()`
  after `clear()`: the attempts go from memory and disk, and a store epoch drops any write still on its way (M1); a lock
  does not touch them. A file it could not delete is logged (`VotesManager` tag, the file's name and the exception type
  only) and the wipe goes on; `LocalWipe` erases the no-backup directory after (#322). **Unreadable vs unavailable**
  (#322, iOS #222's rule; both this store and `vote-rechecks.bin`, through `readKept`): a corrupt or undecryptable file
  (`KeystoreFileStore.isCorrupt`: malformed, or an AES-GCM tag failure; or JSON that does not parse) loads as empty and the
  next write replaces it; any other failure (a `KeyStoreException`, a `ProviderException`, a key not usable now, the file
  not read) throws `StoreUnavailable`: `VotesManager` then neither caches it nor writes over it, and the next access
  reads the file again. Meanwhile it **waits**, as iOS #222 does: the automatic daily re-check of a `gave_up` record is not
  due (`dailyDue`: no fetch, no submit), and the recheck on open downloads no rows.json on a metered or unknown network
  (`recheckRowsWait`: the not-rechecked line), only on Wi-Fi; a Check again runs as always, reading the store as empty,
  and its attempt and stamp are dropped. With `vote_rows`, extra rows too large for the request are dropped largest first, down to one,
  before the fallback to `rows_unavailable` (L3). **Wi-Fi only after a week** (#314 item 1, owner decision a, thread
  01M6RS33MQVX7KZMSJE0VBFYBR): for 7 days from a `gave_up` record's first re-check (`ResultsAttempt.firstRecheckMs`; a check back in `pending` starts
  them again, #319 item 3; the `vote.list` summary carries no give-up time, only `check.due_at`, which a `gave_up` check
  does not have, so a new give-up is seen only through that state, #321 L2) the
  daily rule alone; after them the small fetches (results.json, sig, bundle) stay daily, rows.json is downloaded on an
  unmetered network (`NetworkMeter`: `NET_CAPABILITY_NOT_METERED`; unknown counts as metered; the `unmetered` seam) by
  the daily rule, and on a metered one only when the last rows download (`ResultsAttempt.lastRowsMs`) is at least 7 days
  old (`METERED_ROWS_EVERY`): a weekly cap, not a block; any rows bytes received count, also of a download cut off
  partway (`VoteSite.getCounted`, #319 item 1). A deferred download ends as `ResultsOutcome.Deferred`: nothing
  sent, no new text, the vote's own state line; it keeps the last attempt's record (only its time moves). The member's **Check again is not held by the network cap** (the lead's call on #317 Q2: they asked, so
  rows.json is downloaded on any network); it still follows the unchanged-results skip, its time rule is unchanged
  (an `Unchanged` one keeps the last attempt's time, #314 item 3), and its download moves `lastRowsMs` (data was spent, so the weekly mobile cap counts from it). Both times are wiped with the attempts. Erratum due (the lead writes it):
  ANDROID-PLAN §4 "Votes" ("A `gave_up` check run again") and VAULT-MESSAGING §10.19.6 ("at most once a day per record")
  gain the 7-day / unmetered / weekly-on-metered rule for the rows.json download, and ANDROID-PLAN's "Recheck on open"
  gains its own rule (#319 item 2, the lead's call (a)): on metered or unknown networks rows.json at most daily per vote
  for 7 days after `closes_at`, then weekly; Wi-Fi never capped; behind the cap `vote_results_not_rechecked`. **Recheck on open** (ANDROID-PLAN 0.1.121): `VoteViewModel.setVisible` (ON_START/ON_STOP) runs
  `VotesRepository.recheck` for a `verified` or `unconfirmed` vote with no verdict in memory (`VoteRules.recheckOnOpen`):
  the observer's check alone (a check in flight like `checkResults`: `asCheck`, cancelled by `clear()` and then void, the cap
  read in the compute block, the observer under the same `observeLimitFor` time box), nothing submitted or stored; its
  verdict sets `VoteHint.verdict` (a recheck queued behind a check that set one returns it and downloads nothing, L4); on a
  metered or unknown network it downloads rows.json at most once a day per vote for 7 days after the vote's `closes_at`
  (as extended), then at most weekly, and weekly when the close is not known (`recheckRowsWait`, `recheckRowsAt`: kept on
  disk in `vote-rechecks.bin` (`RecheckStamp`: a key and a time; `KeystoreRecheckStampStore`, no-backup dir; #321 M1, so a
  cold start does not reset the cap), kept across a lock, pruned with the list, cleared by a wipe, a stamp only under the
  store epoch it began in, once per download at its first bytes and whatever the job's state (a lock partway still spent
  them, #321 L1); a results check's rows download (Check again, the daily re-check) stamps it too, #321 L3; #319 item 2, the lead's
  call (a): the verdict is forgotten at every lock, so each open would download again); on Wi-Fi every open downloads;
  behind the cap the not-rechecked line and no number, and a
  site that cannot be
  reached sets `VoteHint.notRechecked` (`vote_results_not_rechecked`, owner-approved 2026-11-03), as does a recheck that could
  check nothing (no `record_tag`, results.json over its limit, a `counts.eligible` cap that does not read; #314 item 2):
  no verdict, no number, never "don't add up". **The give-up feed item**
  (owner decision a, 2026-11-06, thread 01M6RH58PE19ZBEHX3JK5ZW06A): a `vote.result` item carries no check state, so the app
  looks the vote up in the list in memory (`FeedNames.votesGaveUp`, from `vote.list`'s `gave_up` checks): then its title is
  "Vote results not checked yet" (`data_feed_vote_result_gave_up`), the second line "<service>: <vote title>" as for every
  vote item, and the sheet's detail "VettID couldn't check the published results of this vote yet. It will check them again
  by itself when it can. You don't need to do anything." (`data_feed_vote_result_gave_up_detail`); a vote the list does not
  have (or under the app lock's cover, no names) reads as a result, "Vote results are out". The lookup is the current
  state: once the check is final an older give-up item reads as a result. The notification of a `vote.result` is titled
  only after the votes list is read again (`FeedNotifier`, `NotifySource.refreshVotes`, at most 3 s,
  `VOTE_LIST_WAIT_MS`); if that read does not go through, the generic "Vote results are out" (review 1 M3). Catalog:
  `notifications.sheet_vote_gave_up`.
  Every vote signature the app checks goes through
  `Ed25519.verifyStrict` (the four rules of BALLOT-PROTOCOL §2: the `y < p` and small-order rules for `A` and `R` are a
  byte table (`strictPoint`), then `S < L` and the cofactorless equation are Tink's pure-Java `subtle.Ed25519Verify`
  (`com.google.crypto.tink:tink-android`, pinned in `libs.versions.toml`, used by `:core:crypto` for this alone; owner
  decision a, #310), which recomputes `R` and compares encodings. Tink alone takes an `A` of small order or with `y >= p`
  (read mod p), and an `R` of small order whose equation holds: the table refuses them first. Bouncy Castle stays for every
  other signature (`verify`, `verifyRaw`, signing), not for votes). The reserved `abstain` choice is always "Abstain" (`vote_choice_abstain`, `VoteTexts.choiceLabel`) wherever a
  choice label appears (the choices, the confirm sheet, the member's own choice, the restore question, the Results rows), and a
  real choice whose label reads "Abstain" (ANDROID-PLAN 0.1.117 matching) is shown by its id (`VoteWire.readsAbstain`). Both
  strings are owner-approved (ANDROID-PLAN 0.1.116 table, owner decision a, 2026-11-02).
  `Endpoints.votesBase` is `https://votes.vettid.org` (production) or `https://votes.staging.vettid.org` (staging), none on the dev
  stack. Screens: `votes`, `votes.notices`, `votes.empty`, `vote`, `vote.confirm`, `vote.password`, `vote.recorded`, `vote.sending`,
  `vote.waiting`, `vote.results`, `vote.conflict_after_verified`, `vote.failed`, `vote.checking_clock`, `vote.checking_list`,
  `vote.site_unreachable`, `vote.refused`, `vote.rejected`, `vote.results_problem`, `vote.results_dont_add_up`,
  `vote.results_not_rechecked`, `vote.rows_unavailable`.

- Wallet (ANDROID-PLAN §4 "Wallet", W1; VAULT-MESSAGING §10.18): **not in production builds**, laid out like calls. `:core:chain` (bitcoin-kmp
  behind the module, PSBT, the Esplora client, `ChainServers`: the chain server per network in `chain-servers.bin`, block 0 checked
  once per session), `WalletApi.kt` in `:core:vault` (reached only from `:core:wallet`; `walletMarkers` in app/build.gradle.kts fail a
  release APK that carries its type names), `:core:wallet` (`WalletsManager`, memory only; `AddressCheck` before any look-up;
  `WalletFlags` in `wallet-flags.bin`), `WalletAccess` / `NoWalletAccess` in `:core:data` (`app/src/wallet` / `app/src/noWallet`),
  multibound `WipeHook`s (`WalletWipe`) that run before a wipe erases anything. Nothing of a wallet in a log: every wallet type
  prints its type name only, and `ChainPrivacyTest` / `WalletPrivacyTest` scan for logging calls (`WalletUiPrivacyTest` in `:feature:wallet`).
  Screens (W1c-2a; `:feature:wallet`, `walletDestinations` in `app/src/wallet`, the drawer entry after Vault shown only while
  `WalletAccess.shown`): the list (`wallet.list`, the test-network banner, "Not backed up" from this phone's flags, Try again),
  new wallet, the phrase and the check (both `SecureWindow()`, out of autofill and content capture; three words asked, any case; the
  words and any passphrase in `CharArray`s (`PhraseWords`), wiped when the flow ends or the vault closes; a wrong answer ends the
  check; `NewWalletRoute` / `BackupRoute` are in `:core:data` and `navigateTopLevel` pops them (`popWalletSecrets`), so state
  saving never keeps the words behind another screen),
  back up later from a row, the Credential screen's "Bitcoin wallet: <name>" rows (`WalletAccess.wallets`) and History's wallet
  titles. Texts in `feature/wallet/.../strings.xml` are marked draft (the plan's) or new; `WalletTextsTest` holds them. Catalog:
  `wallet`, `wallet.empty`, `wallet.failed`, `wallet.new`, `wallet.new_bad_name`, `wallet.new_limit`, `wallet.new_bad_password`,
  `wallet.password`, `wallet.password_pin_required`, `wallet.phrase`, `wallet.phrase_passphrase`, `wallet.check`, `wallet.check_wrong`, `wallet.done`, `wallet.done_save_failed`, `wallet.done_reveal_failed`,
  `credential.wallets`; W1c-2b adds `wallet.detail`, `wallet.detail_incoming`, `wallet.detail_needs_network`, `wallet.detail_testnet3`,
  `wallet.detail_no_server`, `wallet.detail_server_unreadable`, `wallet.detail_wrong_network`, `wallet.detail_server_failed`,
  `wallet.detail_mismatch`, `wallet.detail_vault_failed`, `wallet.detail_privacy_note`, `wallet.detail_not_backed_up`,
  `wallet.address_type`, `wallet.delete_confirm`, `wallet.delete_password`, `wallet.delete_wrong_password`, `wallet.receive`,
  `wallet.receive_amount`, `wallet.receive_not_backed_up`, `wallet.receive_not_offered`, `wallet.receive_mismatch`, `wallet.detail_change_network`, `wallet.chain_server`, `wallet.chain_server_own`,
  `wallet.chain_server_not_set`, `wallet.chain_server_unreadable`, `wallet.chain_server_checking`, `wallet.chain_server_saved`,
  `wallet.chain_server_bad_url`, `wallet.chain_server_not_https`, `wallet.chain_server_credentials`, `wallet.chain_server_query`,
  `wallet.chain_server_failed`, `wallet.chain_server_wrong_network`, `wallet.chain_server_not_written`, `wallet.details`
  (`WalletCatalog.kt`).
  W1d (spending limits at creation, import; VAULT-MESSAGING §10.18 0.56.0): `WalletApi.kt` adds `walletCapsSet` (a normal op, a null
  member absent), `walletCapsRaise` (a credential operation, sealed `{pin, password, item_id, payload_sha256}`; the hash is
  `WalletCapsBinding`, tested against the §16 vector, a mismatch is a stop-and-report item), `wallet.create{item}` (`WalletImport`) and
  `item.reveal{pin}`; the names are in `walletMarkers`. `Bip39` in `:core:chain` (bitcoin-kmp's list and checksum). The limits chosen at
  creation are **pending per `wallet_id` in `WalletFlags`** (`pending_caps`, optional so old files read): the wallet is created without
  caps, the phrase shown and checked, and only then `wallet.caps.set` is sent (`settleCaps`); after a restart `open()` sends it first,
  and until it is set the wallet has no Receive (`capsPending`, `ReceiveResult.CapsPending`) and nothing else is asked of it before the
  send. **Fail secure**: the pending retry only ever sends `wallet.caps.set`; `raiseCaps` (PIN and password, one credential operation)
  changes nothing here until the vault answers `ok`, and a `conflict` is not retried with the typed secrets. Screens: "Set a spending
  limit?" after the name and network for New wallet and Import (`LimitsForm`, `LimitsInput`: sats with a thousands separator, a toggle
  to BTC, "No limit" the default, nothing suggested), **Import a wallet** from the list's floating button (`ImportWalletRoute`, popped
  like `NewWalletRoute`; `ImportViewModel`: words checked against the BIP39 list as typed and the checksum before anything is sent,
  passphrase behind "Advanced", words in `CharArray`s, `SecureWindow`, password class with no learning, no saved state), and the PIN
  field beside the password for the phrase of a capped wallet. Catalog adds `wallet.new_limits`, `wallet.new_limits_set`,
  `wallet.new_limits_btc`, `wallet.new_limits_bad`, `wallet.new_password`, `wallet.password_bad_pin`, `wallet.done_caps_failed`,
  `wallet.import`, `wallet.import_words`, `wallet.import_unknown_word`, `wallet.import_invalid`, `wallet.import_advanced`,
  `wallet.import_bad_passphrase`, `wallet.import_limits`, `wallet.import_password`, `wallet.import_exists`,
  `wallet.import_done_caps_failed`, `wallet.receive_caps_pending`, `wallet.detail_caps_pending`.
  W1d-2 (Spending limits screen): wallet ⋯ → **Spending limits** (`SpendingLimitsRoute` in `:core:data`, shown only when `capsSupported`;
  `SpendingLimitsViewModel`, `CapsEdit` pure rules): the two limits in sats or "No limit", "Used in the last 24 hours" from
  `caps.spent_sats` (`VaultWallet.spentSats`; the plan says "last hour" on staging, the owner kept "24 hours" there too), **Lower a
  limit** (only lower, or a limit where there was none; `wallet.caps.set`, confirm, no password) and **Raise or remove a limit**
  (PIN and password, `wallet.caps.raise`; a form that only lowers is sent as `set`, one that raises or does both as `raise` with the
  lowered value in it). Any error leaves the limits and the wallet is read again. `raiseCaps` never goes over a pending `set`: it sends
  the set and then refuses (`CAPS_SETTLED`, the form was chosen against the old limits), or refuses (`CAPS_PENDING`). Feed titles and
  `FeedTarget.WalletLimits` for `wallet.cap_refused|caps_lowered|caps_raised` (a notification opens the screen while the Wallet entry
  is shown). **Reset wallet settings on this phone** (`WalletsManager.resetSettings`, `WalletFlags.reset`: the one sanctioned overwrite
  of an unreadable `wallet-flags.bin`, offered only while it is unreadable, behind a `ConfirmDialog`, nothing sent to the vault; the
  local flags are forgotten, so wallets show "Not backed up"): an uncapped wallet of a vault with `caps` is then asked "Set a spending
  limit?" before Receive (`ask_caps` (the wallets the vault held at the reset; `ask_next` fills it from the first list read if none was loaded), `answered_caps` in the flags, decided per wallet at Receive, `ReceiveResult.LimitsAsk`, `answerLimits`; limits pending in this session are carried into the fresh file); a limit goes through the pending path.
  Catalog adds `wallet.limits`, `wallet.limits_none`, `wallet.limits_one`, `wallet.limits_unsupported`, `wallet.limits_lower`,
  `wallet.limits_lower_bad`, `wallet.limits_lower_confirm`, `wallet.limits_raise`, `wallet.limits_raise_confirm`,
  `wallet.limits_remove`, `wallet.limits_bad_pin`, `wallet.limits_backoff`, `wallet.limits_conflict`, `wallet.detail_reset`,
  `wallet.detail_reset_confirm`, `wallet.receive_reset`, `wallet.receive_limits_ask`. The review of a send is W2-2 (below).
  W2-1 (sending, no screens; ANDROID-PLAN §4 "Sending", VAULT-MESSAGING §10.18): `WalletApi.kt` adds `walletPsbtInspect`, `walletSign` (a
  credential operation sealed `{password, item_id: wallet_id, payload_sha256}`, the hash of the PSBT's **decoded bytes**,
  `WalletSignBinding`; no `broadcast`: the app broadcasts) and `walletHistory`; `walletMarkers` gains `wallet.psbt.inspect` and
  `wallet.history`, **not** `wallet.sign` (the substring is in the `wallet.signed` labels that stay in release). `wallet.sign` goes through
  `CredentialOps.credOpKept`, not `credOp`: the sealed body is built once and kept in memory as a `KeptChange` (the lost-answer machinery of
  `pin.change`), so a lost or late answer is **resent as the same request** (`walletSignResend`: same inner id and body, the vault answers
  from its cache) and a spending limit counts it once; never a second `walletSign` for one payment. `:core:chain`: `CoinSelection` (pure:
  confirmed coins only unless asked, branch and bound, then largest first, fee `ceil(rate * vsize)` with the vault's own vsize, change
  at least 330 sats or the remainder to the fee and `toFeeSats` says so, Send all, 1 to 1,000 sat/vB), `Payee` (the address for the
  wallet's network), `SummaryCompare` (the vault's summary against what was built and entered; `vsize` is not compared). `WalletsManager`:
  `prepareSend` (hold as Receive: pending `wallet.caps.set` first via `settleFirst`, an unreadable flags file holds; address, rate, coins of
  both accounts from the chain server, build, `wallet.psbt.inspect`, compare; any difference is `PrepareResult.Different`, code
  `summary_mismatch`, nothing is unlocked or signed), `approveSend(prepared, password)` (`credential.unlock` through the app's own window
  path when the window is closed, and `credential_locked` once more; sign; the signed txid must be the reviewed one; `POST /tx`; a window
  the app opened is closed again), `retrySign` (the same request), `retryBroadcast` (the same bytes, the chain server opened afresh),
  `discardUnsent`. The signed `tx` is **memory only** (`keptTx`, never on disk, dropped by `clear()` at lock, hold and wipe, as are the
  unanswered signings). Errors are `SendResult` / `PrepareResult` kinds with codes (`SendCodes`): `cap_exceeded` (`per_tx` /
  `per_period`, `limit_sats`, `remaining_sats`, `allowed_after`), `invalid_psbt` with its reason (`wrong_seed` is an alarm),
  `credential_locked`, `duplicate_wallet`, a broadcast failure with its reason (`BroadcastCause`, the transaction kept). Texts are W2-2's.
  Review fixes: a reviewed payment (its unsigned txid) is **consumed** when its `wallet.sign` goes out and is signed once (`SendResult.AlreadySigned`;
  any vault refusal of the signing (final, counts nothing) and any failure from before the request went out (`SignOutcome.NotSent`) give it back; an
  answer that does not read, or a failure after the send without an answer, does not); any approve is refused while the wallet holds a signed `tx`. A cached or late answer keeps its UTKs but never an older credential blob
  (`CredentialOps.keepIfNewer`: the blob only while the credential held is still the one the request was sent at). `wallet.sign` is marked durably (`SignMark` in `DeviceState.unsettledSigns`) and holds new sends for that wallet
  (`SendHold.SIGN_UNSETTLED`) across lock, unlock and restart until `credential.version` passes the version it was sent at or its `exp` passes
  (`walletSignsOutstanding`). A lock drops the kept request (`forget`) and nothing is broadcast after one.
  W2-2 (the send screens; `SendRoute` in `:core:data`, `SendViewModel`, `SendScreen`, `SendModels`, `SendEngine` in `:feature:wallet`): **Send**
  beside Receive on the detail (off with Receive's holds and while the chain server's note is unanswered); the screen shows a notice
  instead of the form for the phrase not backed up, a pending limit (it is sent first, as for Receive), settings that cannot be read, a
  network not chosen or not offered, and every `SendHold` (`SIGN_UNSETTLED`: "your vault may have signed a payment … check before you send
  again", which is also what a sign answer lost across a restart shows). `SendRoute` holds the password and a prepared payment: never
  saved with the stack, `popWalletSecrets` pops it. `SecureWindow()`; the password is typed once, in the state only until `approve()`
  takes it (blanked at once; N1: the password and PIN then travel as `CharArray`s (`Taken`, `Job.wipeWhenDone` in `:feature:wallet`) through `WalletsManager`, which wipes each array it is given when its call ends, to `WalletOps`/`WalletApi.kt`, where one `String` per call is made outside the payload lambda (it runs several times per attempt: the gate, the sealing, a retry) and captured; `String` copies remain in the text field and state before it is taken, in `credential.unlock` (`openWindow`, the app's own path), in the vault client's `item.reveal`/`item.delete`, and in the vault client itself, which this work leaves as it was: `VaultJson.bytes` encodes the payload to a JSON `String` (the password in it) before the bytes, and only the bytes are wiped; on a release-R vault `Normalizer.normalize` in `nfcPasswords` makes one more; a stale retry repeats both), and the PSBT lives in `ManagerSendEngine` (a `SendEngine` the tests replace) and `:core:wallet` only.
  Form: the payee is **trimmed at both ends** as typed or pasted (`SendInput.parse`; `Payee` refuses spaces), a `bitcoin:` URI gives its
  address and `amount` (other parameters ignored; a capital bech32 address is read in lower case), `QrScanner` for the scan, the network
  checked as typed with `Payee` ("This is a {network} address. This wallet is on {other}."), BTC or sats (a valid amount is converted
  when the unit changes), Send all, Slow, Normal, Fast from `FeeEstimates`/`FeeTargets` or Custom (1 to 1,000 sat/vB), above twice Fast a
  `ConfirmDialog` ("This fee is much higher than needed right now. Pay it anyway?"), "Include unconfirmed coins" off. Review shows
  **the vault's summary** (`Review` built from `PreparedSend.vault`: payee, change, fee in sats and sat/vB from its `vsize`, total
  leaving = `sending_sats + fee_sats`), the dust remainder that went to the fee, and, only when the inspect returned a `cap` and the wallet
  has `per_period_sats`, "Counts toward your limit for the last 24 hours: {outflow}. Left after this: {left}" (from `spent_sats`);
  a `cap` with `ok: false` shows the `cap_exceeded` text and no password field. `PrepareResult.Different` is the plan's "Your vault saw a
  different payment…". **Self-send** (review note 6): `PrepareResult.SelfSend`, detected in `prepareSend` **before** the build and the
  compare (`isOwnAddress`: both accounts, both chains, up to the vault's next index plus the gap), so nothing is built or sent to any server;
  checked once more after `collectCoins`, with its `lastUsed` per chain, over the range the walk covers (`max(next, lastUsed + 1) + gap`; no look-up of its own, still before the build); the screen has its own text; the second check uses the wallet as reloaded; an own address beyond that range still ends in `summary_mismatch`. Results: Sent ("in about" 20 minutes,
  an hour or a day from the fee target, a custom rate by the quickest target it reaches), Broadcast failed ("Signed, but {host} didn't
  accept it: {reason}…", Try again = `retryBroadcast`, a way to Chain server), the `cap_exceeded` and `invalid_psbt` texts, and plain
  notices for `SignedDiffers`, `AlreadySigned`, `Unanswered`, a lapse, a superseded payment and `duplicate_wallet`; **Discard is on every
  ending that may have signed, except while a signed payment may be on the network**. Review note 2 (after review): a kept transaction has
  a `mayBeOnNetwork` flag in `WalletsManager`, set by any broadcast that ended without a definite answer (`BroadcastCause.Server`) and kept
  for that transaction through later attempts (another server that cannot be opened, or refuses on its own policy, clears nothing).
  `discardUnsent` returns `DiscardResult.MUST_CHECK` while it is set; only `checkSent` clears it: `GET /tx/{txid}`
  (`EsploraClient.knowsTransaction`, `SpendAccess.knows`), known is Sent, and a "not found" is asked again after `settleMs` (4 s) before it
  is believed; a second question that fails decides nothing. The view model asks `checkSent` on entering any ending (BroadcastFailed,
  AlreadySigned, Unsent, ...) when `SendEngine.needsCheck`, keeps `discardLocked`, and refuses `askDiscard()`/`discard()` itself.
  Review note 1 (after review): an unanswered signing is given up (`SendResult.SignLapsed`) only once the vault's clock is past its `exp`
  plus the settle margin (`walletSignExpired`); before that it is kept even if `credential.version` moved on (the vault may have applied it
  and answers a resend from its cache), and past `exp` a late answer is taken by one more resend before the request is forgotten.
  W2-3 (Transactions; ANDROID-PLAN §4 "Wallet", "Transactions"; VAULT-MESSAGING §10.18 `wallet.history`): `:core:chain` `EsploraClient.transaction(txid)`
  (`GET /tx/<txid>`, null on 404; `transactions(target)` (`/address/<a>/txs`, then `/txs/chain/<last_seen>`) and `tipHeight()` were already there).
  `ChainOpen.Ready` gains `history: HistoryAccess?` (`transactions(address)`, `transaction(txid)`, `tipHeight()`).
  `WalletsManager.transactions(walletId)` (`TransactionsResult`: Done, NeedsNetwork, NetworkNotOffered, NoServer(server), ServerWrongNetwork,
  ServerFailed, AddressMismatch, VaultFailed, NoteNeeded, Cleared, as the look-up's) runs **the look-up's own `scan` walk** (no second walk),
  asks each address with history for its transactions (all pages, batches of `BATCH`), reads `wallet.history` (200; an entry whose txid is not
  64 hex digits is dropped) and the vault's clock, asks the server for any signed payment the lists do not show (`GET /tx/<txid>`; a server that
  cannot answer fails the list, never "Not broadcast"), the tip only when something is confirmed, and merges by `txid` in `TransactionMerge`
  (pure). **The vault's figures win for a payment it signed** (direction SENT, or SELF when every payee is the wallet's own, never RECEIVED;
  amount, fee, payees, net from `wallet.history`): the server gives only the status, height and block time, so a lying server changes a status at
  most. A transaction only the server lists has its direction and `netSats` computed from the wallet's own inputs and outputs (own = the
  addresses the walk found with history); one that touches none is dropped; with own and foreign inputs together the amount is the net change
  and no fee is shown. Sums use `Math.addExact`; an overflow is `ChainError.Malformed` (the list fails as `ServerFailed`). Status: Confirmed
  (confirmations = tip - height + 1, at least 1) / Waiting (mempool) / Not broadcast (signed by the vault, unknown to the server, and either
  **older than 24 h by the vault's clock**, as `recentSends` (`at` not after the cutoff), or a **kept unsent** payment of this wallet
  (`keptTx` with `!mayBeOnNetwork`)) / **Not seen by {host} yet** (signed under 24 h ago and unknown to the server; an entry with no `at` counts
  as recent, as in `recentSends`, so it stays here until the vault gives it a time). The list is kept per wallet in `transactionsKnown` (memory
  only; cleared by `clear()` at lock, hold and wipe, and when a wallet is deleted); `walletOfTransaction(txid)` reads the wallet list first if
  it is not read yet (a list that cannot be read is a failure, not "none") and finds the wallet from `wallet.history` (a `wallet.signed` item
  carries the `txid` only). Screens (`:feature:wallet`): wallet detail → **Transactions** (`TransactionsRoute(walletId)`, off while the privacy
  note is unanswered), rows "Sent to tb1qpa…x7k2" / "Received" / "Moved within this wallet", the status and date, the amount, and "Status from
  {host}" above the list; a row opens `TransactionRoute(txid, walletId?)` (both routes are in `:core:data`, memory only: never saved with the
  stack, `popWalletSecrets` pops them): amounts, "Total sent" and the fee for a sent payment, confirmations, block time, "Signed", the txid
  with Copy (public, an ordinary non-sensitive copy), and "Status from {host}" over the status. Loading, empty and every failed look-up (the
  detail's texts) with Try again; pull to refresh. **"Open in a block explorer" is deferred until the chain server settings have an explorer field; where it comes from is for the
  owner to decide:** `ChainServers` / `chain-servers.bin` hold the server's URL only, and nothing is built for it. Notifications: `wallet.signed` is no
  longer "not available": its title is "Bitcoin payment signed" and `FeedTarget.WalletTransaction(txid)` opens the transaction while the Wallet
  entry is shown (else the Notifications list, as for limits); History's entry detail has "Open the transaction" (`HistoryHost.onOpenTransaction`,
  null where the wallet is not shown). Catalog adds `wallet.transactions`, `wallet.transactions_loading`, `wallet.transactions_empty`,
  `wallet.transactions_no_server`, `wallet.transactions_server_unreadable`, `wallet.transactions_wrong_network`,
  `wallet.transactions_server_failed`, `wallet.transactions_mismatch`, `wallet.transactions_vault_failed`, `wallet.transactions_note_needed`,
  `wallet.transactions_not_offered`, `wallet.transaction_sent`, `wallet.transaction_received`, `wallet.transaction_self`,
  `wallet.transaction_not_broadcast`, `wallet.transaction_not_seen`, `wallet.transaction_loading`, `wallet.transaction_not_found`,
  `wallet.transaction_server_failed`. Out of scope: mainnet, fee bumping, labels, fiat.
  An `ok` that does not read is `SendResult.SignedUnreadable` (consumed, "may have been signed"), never an exception to the screen; the view
  model also turns any other exception of approve/retry into that notice, and of prepare into a form problem. While a request is out back and
  the arrow do nothing (`SendUiState.swallowsBack`). `isOwnAddress` runs on `Dispatchers.Default`; `giveBack` is guarded by the generation.
  `ManagerSendEngine.reviewFrom` builds the `Review` from the vault's summary alone (no summary payee: no review, no fallback).
  Second review: a `VaultFailure` after the sign request went out ends as `SendResult.SignedUnreadable` (consumed), never as a password
  error (one from before is `NotSent`; `api()` is inside the sign's try); `prepareSend` refuses a new payment while the wallet has a kept
  transaction or an unanswered signing (`PrepareResult.Unsent`, shown as the Unsent notice under B1's Discard rule); Try again on the
  chain server's note re-checks it (`NOTE_NEEDED` is a gate); `InspectSummary`, `SummaryInput` and `SummaryOutput` print their type names.
  Owner's answers (#254): a `bitcoin:` link with an unknown `req-*` parameter, a bad (negative, over 8 decimals, not a number) or doubled
  `amount` is refused whole with a notice and fills nothing in (`SendInput.Parsed.refused`); a valid link without an amount clears the
  amount field; other unknown parameters are ignored. **Recent payments** (`WalletsManager.recentSends`, memory only): when Send opens
  (after the holds) the vault's `wallet.history` of the last 24 hours (by `at`; none counts as recent; at most 10 asked) is checked against
  the chain server, one `GET /tx/{txid}` each (`EsploraClient.transactionState`: confirmed, in the mempool, not found); not found is
  "may have gone through", a history or server that cannot be read is "couldn't check" (fail secure), in the mempool is "waiting for
  confirmation"; every one of them needs "Send anyway" before a new payment is prepared (`canReview`), waits at most 20 s, and never
  blocks for good. R3 (owner): when nothing is pending, the confirmed payments of the 24 h window (the vault's clock; the same per-txid `GET /tx`, no further call) make one soft note naming the newest (`RecentSends.CONFIRMED`: not a hold, no "Send anyway", Review stays on; dismissed it does not return in the visit). Third review: the check runs whenever a hold clears (first look, Try again, a hold lifting) and nothing is reviewed or
  prepared while it runs (`recentChecking`; "Checking your recent payments…"); the core enforces it too: `WalletsManager.prepareSend`
  returns `PrepareResult.RecentUnchecked` until `recentSends(walletId)` ran in this session and, if it found something,
  `acknowledgeRecent(walletId)` was called (per wallet, memory only, cleared at lock). `recentSends` returns a `RecentReport` that names
  the newest payment (time, amount, payee) and how many more, and maps any exception to UNREADABLE; the view model does the same. A
  refused `bitcoin:` link clears the address and the amount and blocks Review until the address is edited. The review asks about the 10%
  fee again, naming the fee, when the vault's fee is more than 1.5 times the one agreed to, or the vault's `payeeSats` is below the one the agreement was for (`tenConfirmedPayee`, taken when the question is asked as `bigFeePayee`; Send all's payee shrinks with the fee unchanged; an agreement with no basis asks again; edits and `edit()` drop an open question). One fee dialog for two guards: above twice Fast, or more than 10% of the amount sent (asked before the build when even
  110 vB at that rate is over 10% of a typed amount, else on the review from the vault's own fee, e.g. Send all; both: the 10% text). Review note 3: `PreparedSend.signCount`
  against a per-wallet counter bumped when a `wallet.sign` goes out and restored when it is given back; a payment prepared before another
  signing is `SendResult.Superseded`. **`WalletOps.sign` returns a `SignOutcome`** (`NotSent(failure)` / `Answered(SignReply.Ok(body) |
  Refused(error))` / `Unanswered(forget, resend)`): the `SignNotSent` marker and the cause-chain walk (`givesBack`) are gone, nothing depends
  on a suppressed exception surviving a coroutine's copy; the rules are unchanged (`NotSent` and any refusal give the payment back; `ok`,
  even one that does not read, and `Unanswered` keep it consumed; a failure thrown after the send keeps it consumed). Texts are marked
  `draft` (the plan's, word for word) or `new`, none approved; `WalletTextsTest` holds the drafts. Catalog: `wallet.send`, `wallet.send_filled`,
  `wallet.send_btc`, `wallet.send_all`, `wallet.send_scan`, `wallet.send_scan_unavailable`, `wallet.send_wrong_network`,
  `wallet.send_not_an_address`, `wallet.send_fee_custom`, `wallet.send_fee_custom_bad`, `wallet.send_fee_much_higher`,
  `wallet.send_no_estimates`, `wallet.send_not_enough`, `wallet.send_not_enough_confirmed`, `wallet.send_fee_ten_percent`, `wallet.send_review_fee_ten_percent`, `wallet.send_link_refused`, `wallet.send_recent_waiting`, `wallet.send_recent_checking`, `wallet.send_recent_confirmed`, `wallet.send_recent_gone`, `wallet.send_recent_unreadable`, `wallet.send_note_needed`, `wallet.send_self_send`, `wallet.send_different`, `wallet.send_server_failed`,
  `wallet.send_not_backed_up`, `wallet.send_caps_pending`, `wallet.send_caps_duplicate`, `wallet.send_settings_unreadable`,
  `wallet.send_sign_unsettled`, `wallet.send_review`, `wallet.send_review_password`, `wallet.send_review_wrong_password`,
  `wallet.send_review_limit`, `wallet.send_review_dust`, `wallet.send_review_no_caps`, `wallet.send_review_cap`, `wallet.send_sent`,
  `wallet.send_broadcast_failed`, `wallet.send_broadcast_checking`, `wallet.send_broadcast_unknown`, `wallet.send_broadcast_not_there`,
  `wallet.send_cap_per_tx`, `wallet.send_cap_per_period`, `wallet.send_cap_per_period_none`, `wallet.send_invalid_fee_rate`,
  `wallet.send_invalid_output_amount`, `wallet.send_invalid_technical`, `wallet.send_invalid_wrong_seed`, `wallet.send_invalid_other`,
  `wallet.send_signed_differs`, `wallet.send_already_signed`, `wallet.send_already_signed_locked`, `wallet.send_unreadable`, `wallet.send_unanswered`, `wallet.send_lapsed`, `wallet.send_superseded`,
  `wallet.send_duplicate`, `wallet.send_unsent`, `wallet.send_discard`.
  Close-out (data directory scan; device parts in `docs/WALLET-DEVICE-CHECKS.md`): `WalletDataDirectoryScanTest` in `:core:wallet` (manager: create, check, reveal with PIN, import, send with the PSBT and the signed transaction) and in `:feature:wallet` (the view models), Robolectric, real `WalletFlags` over a `KeystoreFileStore` with a test key; the whole data directory is scanned (`SecretScan`: UTF-8, UTF-16, hex, base64 at three alignments, raw bytes; each word and the phrase) and `wallet-flags.bin` (+ `chain-servers.bin`) are the only wallet files. Feed `wallet.signed|cap_refused|caps_raised` are on Vault activity and name the wallet only uncovered (`FeedNotifierTest`); `wallet.created` is History only.

## Device testing

Debug builds use application id `com.vettid.app.dev` (`devStack`:
`com.vettid.app.devstack`; `staging`: `com.vettid.app.staging`); never install over `com.vettid.app`. Screenshot launch extras (debug only):
`adb shell am start -S -n com.vettid.app.dev/com.vettid.app.MainActivity --es vettid.theme dark|light --es vettid.start gallery|messages|connections|approvals|items|history|credential|settings|help --ez vettid.screenshot true`
(`vettid.screenshot` lets the debug app draw over the keyguard of a locked test phone; the phone stays locked).
Every A3 and A4 screen with sample state, no vault needed: `--es vettid.start screen:<name>` (names in
`app/src/debugTools/.../ScreenCatalog.kt`, e.g. `onboarding.backup_off`, `onboarding.setup_scan`, `onboarding.setup_type`, `onboarding.confirm_account`, `account_sheet`, `unlock.updated`,
`credential.alarm`, `settings.delete_confirm`, `messages.conversation`, `invite.request`,
`connections.detail`, `approvals.critical`, `messages.held`, `messages.held_empty`, `history.held`, `history.held_empty`; recovery and transfer: `recover.pin`, `recover.no_backup`; the recovery code typed instead of scanned (ANDROID-PLAN 0.1.33, VAULT-MESSAGING 0.25.0 §11.11.10: `RecoveryCodes` / `TypedRecoveryCode` in `:core:altchan`, the claim by locator, `RecoverStep.TYPE`; the real screen sets `FLAG_SECURE` through `SecureWindow`, the catalog's does not), `recover.type`, `recover.type_partial`, `recover.type_complete`, `recover.type_refused`, `recover.type_bad_check`, `recover.type_short_code`, `recover.type_not_found`, `recover.type_bad_code`, `recover.type_rate_limited`;
`transfer_in.compare`, `transfer_in.checking`, `transfer_in.session_ended`, `settings.transfer_compare`, `settings.transfer_moved`; the member's erase of a phone the vault
did not recognize: `unlock.not_recognised`, `unlock.erase_confirm`, `unlock.erasing`; the open app sent back after
repeated relay refusals, `unlock.refused`; the transfer's 60 s wait for `hs.resp`, `transfer_in.no_answer`; the vault service paused for maintenance
(MEMBER-API 1.2.0), `unlock.paused`, `unlock.paused_preflight`; the app's own checks of the vault's software failing (owner report 2026-10-11: `FailureKind.RELEASE_UNKNOWN`, `MANIFEST_UNAVAILABLE`, `ENCLAVE`, never the phone's `ATTESTATION` text; `enclaveFailure` in `Guard.kt`), `unlock.release_unknown`, `unlock.manifest_unavailable`, `unlock.enclave_unverified`; the canary manifest (VAULT-RELEASES §10.1 step 9),
`canary.confirm`, `canary.refused`, `settings.attestation_canary`; the daily owner check (VAULT-MESSAGING 0.13.0 §3.6),
`owner_check.held`, `owner_check.due`, `owner_check.unknown`, `owner_check.bad_password`, `owner_check.backoff`, `owner_check.voluntary`,
`owner_check.hold_off`, `unlock.owner_check`, `unlock.owner_check_locked`, `settings.owner_check_hold_off`,
`settings.owner_check_offer`, `shell.owner_check_banners`; a new credential (0.15.2), `credential.new`, `credential.new_confirm`, `credential.new_checking`, `credential.new_unreachable`, `credential.new_gate`, `credential.new_ended`, `credential.new_not_made`, `credential.new_done`; a start-over pending on the portal (0.16.0), `shell.deletion`; the account names and the shared profile (0.18.0, ANDROID-PLAN 0.1.10), `connections.detail_not_shared`, `account_sheet.name_pending`, `settings.shared_profile`, `settings.change_name`, `settings.change_name_confirm`, `settings.change_name_pending`, `settings.change_name_refused`; History and the profile photo (ANDROID-PLAN 0.1.11), `history`, `history.filtered`, `history.search_local`, `history.empty`, `history.no_match`, `history.chain_broken`, `history.entry`, `history.entry_unknown`, `settings.shared_profile_photo`, `settings.shared_profile_photo_preview`; the in-app camera for the profile photo (owner feedback 2026-10-08: taken, never chosen; fake preview), `settings.photo_capture`, `settings.photo_capture_rear`, `settings.photo_capture_one_lens`, `settings.photo_capture_failed`, `settings.photo_capture_review`, `settings.photo_capture_review_zoomed`, `settings.photo_capture_permission`, `settings.photo_capture_blocked`, `settings.photo_capture_no_camera` (the avatar sheet, `account_sheet`, has Lock vault first and scrolls); the simplified connection detail (owner decision 2026-10-08: no safety code, alias, note, edit or block; the vault key fingerprint stays), `connections.detail_remove`; the Vault (A5a, items of VAULT-MESSAGING §10.7, `ItemsCatalog.kt`), `items`, `items.filtered`, `items.empty`, `items.no_match`, `items.error`, `items.templates`, `items.detail`, `items.detail_profile`, `items.detail_secret_hidden`, `items.detail_secret_revealed`, `items.detail_critical_hidden`, `items.detail_critical_open`, `items.detail_critical_password`, `items.detail_critical_backoff`, `items.detail_protection`, `items.detail_leave_critical`, `items.detail_delete`, `items.detail_missing`, `items.edit_new`, `items.edit_problems`, `items.edit_profile`, `items.edit_conflict`, `items.edit_critical_password`; tags and sharing (A5b, §10.8, §10.12), `items.tags`, `items.tags_edit`, `items.tags_rename`, `items.tags_in_use`, `items.tags_colour`, `items.tags_colour_profile` (tag colours, ANDROID-PLAN 0.1.25), `items.sharing`, `items.sharing_empty`, `items.rule_new`, `items.rule_edit`, `items.shared_with_you`, `items.shared_with_you_empty`, `items.edit_share_impact`, `approvals.share_partial`, `settings.shared_profile_items`; grants, critical-item uses and History's items (A5c), `approvals.grant_decide`, `approvals.critical_backoff`, `approvals.critical_result`, `approvals.critical_unsuitable`, `history.items`, `history.entry_item`, `items.shared_ask`; VAULT-MESSAGING 0.21.0: edits with kept values and the room left, named limits, the suitability notice, `items.edit_secret_kept`, `items.edit_critical_kept`, `items.edit_limit`, `approvals.critical_suitable`, `approvals.critical_unsuitable_kind`; value-first item fields and member-defined categories (owner feedback 2026-10-08: one value input captioned by the field's label, a ⋯ menu to rename, retype an unsaved field, move or remove, the label asked for when adding; "New category…" derives a §10.7 identifier from a typed name), `items.edit_card`, `items.edit_add_field`, `items.edit_add_field_long`, `items.edit_rename_field`, `items.edit_field_type`, `items.edit_new_category`, `items.edit_new_category_bad`, `items.edit_custom_category`; one item per contact point and the shared profile as a member choice (VAULT-ITEMS 0.1.1, owner decision 2026-10-08: templates `email_address`, `phone_number`, `postal_address`, `website` replace `contact_card`, no template adds `@profile`; "Shared profile" is a built-in tag choice for standard items only; a blank item starts with no field), `items.edit_blank`, `items.edit_address`; sharing both ways on the connection detail, no pre-typed example text, formats enforced while typing, the protection changed in the editor, and the top bar's search icon (owner requests 2026-10-08), `connections.detail` (both sharing cards), `connections.detail_sharing_empty`, `items.edit_card` (month-and-year expiry, name placeholder), `items.edit_protection`, `items.edit_leave_critical`, `items.edit_saved_protection_not`, `items.list`, `items.list_search`, `messages.search`, `messages.search_none`, `connections.search`, `connections.search_none`; History export (ANDROID-PLAN 0.1.17, VAULT-MESSAGING 0.22.0 §10.9: History ⋯ → "Export…", the confirm step with count, range, CSV/JSON and the unencrypted-file notice, the vault PIN, reading, "Save to…"; `history` shows an `audit.exported` row), `history.export_preparing`, `history.export_confirm`, `history.export_confirm_json`, `history.export_confirm_more`, `history.export_nothing`, `history.export_alarm`, `history.export_old_vault`, `history.export_pin`, `history.export_pin_wrong`, `history.export_pin_backoff`, `history.export_reading`, `history.export_save`, `history.export_writing`, `history.export_done`, `history.export_done_partial`, `history.export_failed`, `history.export_save_failed`, `history.export_owner_check`; proactive release updates (ANDROID-PLAN 0.1.19, owner decision 2026-10-09: the shell's banner, dismissed for 24 h at most, the end-date warning, one local "Vault updates" notification per release, Settings → Vault → "Update available", and the one-step update: lock → unlock with the approved `release_update` → `moved` → unlock at N+1, shown over every phase by `ReleaseUpdateManager`), `shell.release_banner`, `shell.release_banner_ending`, `shell.release_banner_ended`, `release_update`, `release_update.ending`, `release_update.check_first`, `release_update.none`, `release_update.progress`, `release_update.starting`, `release_update.done`, `release_update.refused`, `release_update.bad_pin`, `release_update.failed`, `release_update.new_failed`, `release_update.abandon_confirm`, `release_update.abandoning`, `release_update.abandoned`, `settings.release_update_row`; the app lock's method (owner request 2026-10-09: "App lock" with Biometrics or Phone screen lock, no VettID passcode), `app_lock.screen_lock`, `settings.app_lock_screen_lock`, `settings.app_lock_method`, `settings.app_lock_method_no_screen_lock`, `connections.detail_rule_delete`, `connections.detail_rule_limit`, `items.sharing_limit`, `items.rule_overlap`, `items.rule_custom_end`, `items.rule_custom_date`, `items.rule_custom_time`, `items.rule_end_bad`, `items.rule_limit`, `items.rule_limit_error`, `items.no_match_filtered`, `items.edit_saving`, `items.edit_discard`, `items.edit_phone`, `items.detail_phone`; VAULT-MESSAGING 0.23.0 and the owner's requests of 2026-10-09 (one tag per rule with a gold-outlined, checked choice and "Already shared — edit its rule", per-hour and per-day limits, `ask` wins in the dry run and the questions, rules named by their tags, a read-only multi-tag rule, "Save rule" in the top bar with a discard prompt, "Try again in …" for `rate_limited`, a connection's asks muted or paused, a batch of asks as one entry, History's category colours), `items.rule_selected`, `items.rule_ask_wins`, `items.rule_limits_bad`, `items.rule_read_only`, `items.rule_discard`, `items.shared_rate_limited`, `connections.detail_asks_paused`, `connections.detail_asks_muted`, `approvals.paused`, `approvals.share_ask_wins`). Debug builds expose Compose test tags as resource
ids, so `uiautomator dump` / `adb shell input` can drive two phones at once (`adb -s <serial>`).
Screenshots and the Proton reference images stay out of git (`local/`, or the
vettid.org repo's `local/android-ui/`).

## Conventions

- American English in all user-facing text (owner 2026-11-05), guarded by `AmericanEnglishTextTest` (`:app`).
- The daily owner check in the shell (VAULT-MESSAGING §3.6.5, owner decision 2026-11-03; iOS does the same): when it comes due
  (`gate.gated`) it covers the app at once on every screen: read-only pushed ones (a detail, a History entry, the photo viewer,
  Notifications, a device, a vote, a wallet), a drawer screen, and the return from the background. It waits only for what §3.6.5
  says it must not interrupt: **input or an operation in progress** on a pushed screen. It waits until the
  member leaves that screen, or until `OWNER_CHECK_DEFERRAL_S` (5 minutes, divided by the vault's time scale) after the vault's
  deadline (`OwnerCheckGateState.overdue`, derived from the deadline, so a recreation, an app lock, the background or a new
  process never restart the wait, and a return after the deadline plus 5 minutes is covered at once). A drawer screen never
  waits. A call does not defer it: the call screen (`CallOverlay`) is drawn above the whole shell, check included, and the app never ends a call. One signal serves the waiting: a screen reports with `HoldsInput(active)` (`:core:ui` `InputGuard.kt`; the shell provides
  `LocalInputGuard`). Reported: the item editor (`dirty`), the share-rule editors, a conversation with a draft or photos, Accept
  (typed or pasted text, not a link's), the tag form and ask dialogs, wallet Send, Import, a new wallet (name, limits, a word of
  the check; the shown phrase is covered at once), Spending limits and Chain server forms, the shared profile (edited name or
  photo), change name (a changed name), delete vault (the phrase), **an approval open for a decision, typed or not** (any
  kind, devices and agents too: `ApprovalDetailUiState.openForDecision`), a transfer out under way, and **every `SecretField`
  on screen, typed or not** (a PIN or password prompt is a credential operation in progress: credential change, new
  credential, rotate, alarm, change PIN, release update, History export's PIN step, the unlock-window prompts of votes,
  wallets and agent rules, a critical-item use; `holdsInput = false` for a secret item's value in the editor). Search boxes
  and a Receive amount are not input in progress. A new form screen with typed input, or a new credential flow without a
  `SecretField`, must call `HoldsInput`; put the condition in a UI-state property (`holdsInput`) and unit-test it.
  The check is drawn over the shell (`OwnerCheckRoute` after the `ModalNavigationDrawer`, never in place of the NavHost), so
  the screen stays composed and what was typed is there when the check passes. When the check is to cover, **secrets typed and
  not sent are blanked first** (§3.6.5's zeroize rule, as iOS): every `SecretField` with `holdsInput` calls its owner's setter
  with `""` (the view model's state is cleared, not only the text field), and the recovery words typed in Import and in the
  backup check do the same (`WipesOnCover`, `InputGuard.wipeSecrets()`); a secret item's value in the item editor is a draft
  and stays. Drafts that are not secrets (messages, item and rule editors, forms) are kept. The wipe runs before dialogs
  hide. Then the check is latched GATED (saved): input appearing under it cannot take it away, and a short dip of `gate.gated`
  does not either; it is let go when `OwnerCheckRoute` says it passed (`onDone`) or after `OWNER_CHECK_UNLATCH_MS` of `gated`
  false. On a new composition one frame is undecided (the shell is drawn at alpha 0) so that no vault content is shown before
  the screens have reported. It takes the focus and the
  keyboard, with a layer that eats keys (as `AppLockGate`), and its own subtree gets a null `LocalInputGuard`. Dialogs and
  sheets are windows above it, so screens import `AlertDialog`, `ModalBottomSheet` and `DatePickerDialog` from
  `com.vettid.core.ui.components` (`ShellOverlays.kt`), which are not composed while `LocalCheckCovered`; their state lives in
  the view models and comes back after the check. Dropdown menus are wrapped too (`DropdownMenu` in `ShellOverlays.kt`), and detekt's `ForbiddenImport` refuses Material's
  `AlertDialog`, `ModalBottomSheet`, `DatePickerDialog` and `DropdownMenu` outside `core/ui`. The check also waits through a
  recreation (rotation, an app-lock unlock, a restored process): until one frame has passed after a new composition it is not
  shown, because the screens report their input in effects after the first frame. The gate's clock wakes at the next boundary
  (warning, deadline, deadline plus the deferral), not only every 15 s. The key layer lets through Back (a voluntary check closes with it on API 31-32) and the phone's own keys (`ownerCheckLayerEats`:
  volume up, down and mute, the headset hook, media play, pause, play/pause, stop, next and previous, call and end call), so a
  call or music keeps its keys; every other key is eaten (the app lock's `AppLockGate` layer lets Tab, the D-pad keys, Enter and Space through to the lock screen, `appLockLayerEats`, and eats every other key: nothing is composed under the lock). While a
  call screen is shown (`LocalCallScreenShown`, provided by `CallOverlay`) the check's own Back handler is off, so Back is the
  call screen's. A check that said it passed keeps the shell uncovered while `gated` is still true for at most
  `OWNER_CHECK_PASSED_MS` (10 s) and not for a new deadline (saved). The undecided frame also clears semantics and takes every
  touch. **Known limit of the wipe:** only screens composed when the check latches are wiped; a typed secret in a screen under
  another on the back stack, or in a dialog a voluntary (asked) check had already hidden, stays in its view model (a form is
  normally popped, not stacked under). `ShellOverlayRulesTest` lists the files that may call `HoldsInput` /
  `WipesOnCover` and refuses fully qualified windows of their own outside `VettIdApp.kt`. Rule: `ownerCheckMode()` /
  `rememberOwnerCheckMode()` in `OwnerCheckGate.kt` (`OwnerCheckInputTest`). The refused-request text "What you entered is
  kept: the check opens when you leave this screen" is unchanged (owner decision a).
  Failed-check texts (owner decision 2026-11-05, signed): the vault counts a wrong PIN on a credential operation that checks the PIN as an owner check does (VAULT-MESSAGING §3.6.1; e.g. a wallet limit raise, `item.reveal` of a capped wallet) as a failed check and audits `owner_check.failed` (`ref` `pin`), so the PIN variant is not "daily": the shell banner (`owner_check_notice_failed_pin`) reads "A PIN check failed: wrong PIN.", the feed (`data_feed_check_failed_pin`) "A PIN check failed: wrong PIN" and History (`history_kind_owner_check_failed_pin`, `AuditKinds.title` for `ref` `pin`) the same; the password variant ("A daily check failed: wrong credential password.") and History's "Daily check failed" for other refs are unchanged; the banner for any other `ref`, or none, is "A daily check failed." (`owner_check_notice_failed`, owner-approved 2026-11-05 as iOS #216; `failedCheckText`), never the PIN text. The wallet detail's button under "Receiving is off until your spending limit is set." is "Set the limit now" (`wallet_caps_set_now`, same `retryCaps`).
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
- Brand assets follow the brand kit (vettid.org `local/brand/kit`, `BRAND.md`): the launcher icon is the kit's app
  icon (gold rook, keyhole cut out, on VettID Blue #2E2D88; `ic_launcher_*`, legacy PNGs in `mipmap-<density>`); in the
  app, `RookLogo` (the rook alone, any size) or `VettIdLogo` (the horizontal logo, never under 160dp wide, clear space
  `VettIdLogoDefaults.clearSpace`; gold wordmark on dark surfaces, the kit's `-on-light` artwork with the VettID Blue
  wordmark on light ones, picked by the app's theme, never by a `-night` resource: the member can force a theme). Never type "VettID" next to the rook as a logo, never the tagline on a screen, never
  recolour the artwork. The rook's shapes are the kit's paths, guarded by `RookKeyholeTest`. Non-production builds
  (debug, devStack, staging) add a white band to the launcher icon's background (`app/src/nonProduction`, not part of
  the kit).
- Connection tiles: indigo for all connections, teal for favorites (the owner's
  `favorite` flag), gold for the member's own avatar; use `ConnectionRow`.
- Reuse `:core:ui` components (top bar, drawer, list row, empty state, floating
  controls, pill bar, settings groups, avatar sheet, confirm dialog) before adding new ones;
  every new component goes into the debug gallery.
- The activity window is `FLAG_SECURE` in every build (A6 audit S1): no screenshots, screen recording or Recents
  preview. Settings → App → "Allow screenshots" (`ScreenshotAllowance`, memory only) lifts it until the vault locks,
  at most 1 hour; debug and devStack builds start with it on (the catalog is screenshotted). A screen that must stay
  protected regardless calls `SecureWindow()`. A field that holds a secret the member must see uses
  `visibleSecretKeyboard()` inside `NoKeyboardLearning { }` (S2); copy secrets with `textClip(…, sensitive = true)`.
  "Shared with you" masks a connection's shared passwords and one-time codes (`FieldKinds.masked`, as in the item
  detail) until the member taps the field's eye; the shown state is in memory only (never `SavedStateHandle`), so
  leaving the screen masks them again (VAULT-MESSAGING §10.7, §10.12).
- The member's lock fails secure (owner decision 2026-10-12; `LockGuard`, `LockKeeper`, `LockSequence`,
  `VaultManager.lock()`): the phone is `AppPhase.Locked` from the moment the member asks, in every outcome, and the
  vault is *known* locked only when it answered the relay `vault.lock`, a status read no longer says `unlocked`, or
  its own `vault.locking` from after the last unlock arrived. **The member API's answer to a lock request is never
  proof** (the enclave answers `done` whatever became of its lock). Until then the lock is **pending**: a record in
  the account record (`lockAskedAtMs`, written by `LockKeeper.begin()` before the lock's hooks and its first
  attempt, so a new process starts locked), retries (1, 2, 5, 10 s, four times 30 s, then every 60 s: under the
  member API's 30 locks in 15 minutes; in the process, and (release L's Q4, owner decision 2026-10-23) across its
  death: `PendingLockJob`, a persisted framework job scheduled by `PendingLockNotifier` while a lock is pending,
  starts the process, whose `LockKeeper.resume()` goes on; the notifier also posts "Your vault may still be open" on
  Security while it is in doubt and VettID is not in front), `VaultRepository.lockNotice` on the unlock screen (`unlock.lock_locking` while nothing has failed,
  `unlock.lock_unconfirmed` from the first failed try, `unlock.lock_confirmed`). The four rules of a pending lock
  live in `LockGuard` and are tested there: what confirms; locked whatever the status says; nothing sent to the
  vault but the lock, a pending `call.end` and (release L, "Lock it now") one `vault.status`
  (`LockTimeUse.LOCK_STATUS`); nothing it sends taken in. In `VaultManager` the vault client is a
  private member of `Session`, reachable only as `openApi` (refuses with `lock_pending`) and
  `lockTimeApi(LockTimeUse)`: do not add a third way, and send nothing through `device` directly (`VaultClientAccessTest`). A vault has **no time after which
  it locks by itself**: never promise one. Every unlock goes through `LockKeeper.unlocking` (no lock attempt beside
  it, none of an older lock after it, and first the wait for an unanswered relay lock: `relayLockUntilMs`);
  `vault.lock` is sent once with an `exp` of 30 s and never through the outbox (a late lock would lock a vault the
  member opened again). **Release L (VAULT-MESSAGING 0.29.0 §10.2 "Locks"; gated):** when the vault announced an
  `unlock_id` (enroll, unlock or recovery-open result, `vault.status`; `VaultDevice.unlockId`, memory only; `""` is
  absent, `UnlockIds`), the lock is **bound**: `LockRecord.unlockId` (the lock file's version 2), `vault.lock
  {unlock_id}` at every attempt with no wait before an unlock, `POST /api/vault/lock {unlock_id}`, the slot's
  `locked` / `not_running` confirm, `stale_lock` / `stale` end it as `LockNotice.OpenedAgain` (not locked; this
  phone held locked until an unlock here, `LockKeeper.holdsPhone`), and a `vault.locking` counts by its `unlock_id`
  (`LockNotices`). Without the value everything above is as #127 built it. **The idle lock (release L,
  VAULT-MESSAGING 0.29.0 §3.6.8; gated on the vault):**
  `OwnerCheckView.idleLockAt` (`idle_lock_at` of `vault.status` / `vault.held`, shown on the gated check),
  `OwnerCheckRepository.idleLocked` (`vault.locking{reason: "idle"}`, kept until the vault opens; without it
  `idleLockedNow` reads a time past `idle_lock_at`) for the unlock screen (`unlock.idle_locked`) and the Security
  notification, `idleLockSetting` (`idle_lock.after_seconds` via `settings.get` / `settings.set`; no row for a vault
  that does not list the key; a longer value is in force only after the next check, the lowest since then stays),
  History's "Vault locked itself (not checked in)" for `vault.locked` with `ref` `idle`. Do not set the phase to
  `Unlocked` anywhere without thinking about a pending lock
  (`evaluate()` asks `LockGuard.lockedWhatever`; `lock()` bumps `generation`).
- Every destructive action uses `ConfirmDialog`; every critical action asks for the
  credential password. No backup/export of vault data (`allowBackup=false`); the one exception is History's export of activity metadata (ANDROID-PLAN 0.1.17: the vault PIN, then "Save to…" only).
- Transfer to this phone past its 11-minute deadline (the lead's pending erratum on the settlement window; today's rules are VAULT-MESSAGING 0.85.2 §11.14.10 rule 5 and ANDROID-PLAN §4 "Transfer (new phone)"): the settlement window (`TransferInResume.atDeadline`, wiring in `TransferSettlement.kt`, run once per transfer in the manager's scope) collects for the relay's visibility timeout V (0-300 s, 60 if absent) + 25 s + 5 s (the first end F), never cancels its own request while it is out, and ends with one `wait=0` collect. **The extension rule (owner decision a, 2026-11-05; one rule for both apps; the lead's calls A-C of the #307 follow-up):** on a cut or a detected freeze the end moves to `min(max(end, lease end + 15 s), cap)`, the cap being **F + V + 35 s** (long-poll cap 25 s + handling margin 5 s + 5 s), F and the cap fixed on the window's clock (`SystemClock.elapsedRealtime()`, which runs through sleep); nothing shifts F, frozen time counts (a freeze also moves the end to at least the wake + V). *A lease* runs from its start + V; the start is s + wait, s the send time and wait the long-poll wait of the request that may hold it (the relay can hand a parked long poll a message up to its wait after the send, also after a failure on our side). The device stamps every attempt as it is written (OkHttp's `requestHeadersStart` through `RelayClient.Call.onSent`, after any wait for the network and the connect, retries included). For a cut (call A, with review F1) the start is `max(s + wait, notice)`, never the send alone, since a request may reach the relay as late as its failure is seen: ours (abandoned or failed: `max(now, s + wait)`, recorded with the wait it was actually sent with, 0 while the collection runs), or the collector's (`VaultDevice.collectCutsMs`: each cut's number, send and wait; only cuts counted since the window began count, so a device's older cuts are not the window's; a cut not described is taken as now + 25 s). For a freeze it is s + wait of the latest request sent at or before the freeze began, ours or the collector's (the wake + wait for a send since one heartbeat before the last heartbeat tick before the freeze, whose bytes may reach the relay only at the wake (a tick can fall between the send and the freeze): the lead's call R2), kept after the request ended (`VaultDevice.collectLastSendAgeMs`, `collectSendsMs`, on `DeviceConfig.elapsedMs` = `elapsedRealtime` so a suspend counts). A cut of a request written at or before a freeze belongs to the freeze (call C): it reached the relay before it, so its lease starts by its s + wait, within the freeze's own, and moves nothing. *A cut:* a request of ours abandoned or failed with anything but a relay status; or the collector's in-flight request cut or lost (`VaultDevice.collectorCuts`, counted by `MailboxCollector`'s `onLost`: the collection stopped or its scope ended with a request out, every lost attempt of the client's own retries (an I/O error, a 5xx; the poll client has OkHttp's silent resend off so none is hidden), a 200 whose body did not parse, anything else that aborted a round (a refused ack or a handler failure) once messages had arrived; a 4xx that is the relay's own answer on the request itself is not a cut). A stopped collection's round that ends late (after a new `start()`) no longer clears the new round's request (a collection number). *A freeze:* a 1 s heartbeat ticker (alive only while the window runs) shows a gap of more than 10 s between ticks, whatever phase it falls in (a process freeze or a suspend). The check is made after the final collect too. **The last collect (hold):** a lease whose end + 15 s runs past the cap is recorded however and whenever it was noticed (before the cap or after it); the final `wait=0` collect goes at `max(now, latest lease end + 15 s)` (the margin covers the network between the write of a request and the relay, and our own request's `sentAt`, the one send still noted before the transport retry and the connect; a cut or a failure of ours never leases earlier than its notice). **At most ONE extra per settlement:** the hold target moves once (a cut or freeze noticed during the hold; setting the first hold is no move; a later move is ignored, since a collector failing offline would otherwise keep the pin going for ever), or one more held final collect after the final send, for a lease noticed during it that outlives its start (past the cap, or within it but ending after the send began), or for a final collect the relay refused (call B: 429, or 401 with a stale signature; any other 4xx is no cut). In production `RelayClient.execute` first retries a 429 or a stale 401 itself (up to 3 attempts, each signed afresh, honoring `retry_after`): a refusal reaches the window only after those, and is then held for its `retry_after`, at most V, and sent as a new request; a `retry_after` longer than the request's bound (wait + 30 s) ends it as an abandoned request first, a cut held to max(now, s + wait) + V + 15 s (review F4). Once the extra is used at or after the cap, nothing raises a hold past what was due when it was taken (a background or a return grants no new hold); an extra used before the cap (at a first end the window then moved past) leaves the first hold for a lease past the cap due (review F2), but a freeze during the last send (the app in the background) earns one more `wait=0` look, never a drop after only a look made before the freeze: at once (held for nothing) when a lease hold was already honored or the extra was taken at or after the cap; after an extra taken before the cap with no lease hold yet, held by the free first lease hold (the freeze's lease + 15 s, no move; recheck 4, B1′). A cut during the last send after an extra taken before the cap, with no lease hold honored yet, gets the same free first lease hold: one more `wait=0` collect at its lease end + 15 s (no extra, no ceiling; spent once honored, so it fires once; a freeze in that collect then earns the one look, at once). Then the drop. The collector's newest long poll is not cancelled before the drop (iOS's `settlingSession`): the drop runs under the device lock the handler takes, a `device.paired` handled first is kept, and cancelling would only cut a lease. **The bound is cap + 2V + 648 s**, plus any frozen time (cap = F + V + 35 s): the last ordinary step ends within 148 s of the cap, then the hold V + 40 s (the lease starts by max(s + wait, notice), at most the notice + 25 s; then the 15 s margin; the history holds every attempt, retries inside the client's included), the final send 140 s, the extra V + 40 s, a second final send 140 s, and the one look after a freeze in the last send 140 s, at once (held for nothing), for every V (0-300 s). An extra taken before the cap ends before it: its `retry_after` hold ends by cap + V, then the free first lease hold V + 40 s (before the last send, or after it for a freeze in it: it is spent once), the last send 140 s and the look 140 s (or, if a cut moved the end again, ordinary steps to the cap, 148 s, and the same), at most cap + max(2V + 320, V + 468) s (recheck 2, B1); with the free lease hold after the last send for a cut in it and then the look for a freeze in the held collect, the last send 140 s, the hold V + 40 s, the held collect 140 s and the look 140 s: at most cap + max(2V + 460, V + 608) s, inside the main bound for every V. A request that ended normally before a freeze is still taken as s (its send time is kept): conservative. The app collects with LONG_POLL only; a WebSocket stream would need its own lease rule (s = the cut time on a cut, the wake on a freeze while it is open, now at `stop()`; wait 0), and `SettleStreamGuardTest` fails if anything in the app's sources sets `WEBSOCKET` or the default changes.
- Commits: explicit paths only (never `git add -A`); PRs into `master`.
