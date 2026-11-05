# Google Play submission pack

The owner's working document for the task "Play internal track and pre-launch report": the foreground-service
declarations, the Data safety form, the pre-launch report and an internal testing build. Everything here is read from
this repository at master `3133e66b` and from the vettid.org docs; citations are `path:line`. Nothing was built to
write it. **To confirm** marks what the code cannot settle.

The owner holds the Play Console, the release signing and the test phone. Nothing in this document has been done in
the Console.

**Two facts that shape everything below** (vettid.org `docs/ANDROID-PLAN.md` §7, around lines 7207-7215;
`docs/W10-READINESS.md` R7, lines 328-336):

- A Play install is signed by Google's app-signing key. No production vault release pins that key yet, so **an app
  installed from Google Play cannot enroll, receive a transfer or register for a recovery** until a production
  release pins it (class `google_play`). An internal-track install will stop at onboarding for every tester.
- The Play listing must not go live on any track that members can install from before that release is `active`.
  **No member-installable track (closed, open or production) opens before then.** Pinning takes a vault release:
  `{digest, class: "google_play"}` added to vettid-vault's `enclave/releasecfg/prod.json` (today it pins one
  `vettid` signer), that release reaching `active`, and the same digest added to the App Links documents
  (`assetlinks.json` on account.vettid.org, and the relay's per ANDROID-PLAN §7). See step 3 in section 6 and Q8.

---

## 1. Release build facts

Upload the `full` distribution's release build only. `fossRelease` is the F-Droid build (`app/build.gradle.kts:175-191`,
`:979`; `CLAUDE.md:101-111`).

| Fact | Value | Source |
|---|---|---|
| Application id | `com.vettid.app` | `app/build.gradle.kts:51`, `:59` (same id as v1, ANDROID-PLAN D1) |
| `versionCode` | `102` | `app/build.gradle.kts:63` (v1 ended at 7; the rewrite starts at 100, `:60-62`) |
| `versionName` | `2.0.0-a2` | `app/build.gradle.kts:64` (release adds no suffix; debug, devStack and staging do, `:90`, `:98`, `:147`) |
| `minSdk` | 31 (Android 12) | `build-logic/convention/src/main/kotlin/VettIdBuild.kt:10`, applied at `vettid.android.application.gradle.kts:30` |
| `targetSdk` / `compileSdk` | 37 / 37 | `VettIdBuild.kt:8-9`, `vettid.android.application.gradle.kts:28,31` |
| ABIs | `arm64-v8a`, `armeabi-v7a` only | `ndk { abiFilters += AbiRules.RELEASE }` `app/build.gradle.kts:126`, `AbiRules` `:598-601`; `checkReleaseAbi` fails on `lib/x86*` (`:603-622`, `:1051`) |
| Shrinking | R8 and resource shrinking on | `app/build.gradle.kts:127-129` |
| Signing | none in the repository: the bundle comes out unsigned | `app/build.gradle.kts:134`; README "Building" |
| Backup | `allowBackup="false"` | `app/src/main/AndroidManifest.xml:23` |
| Dependency metadata | not in the bundle (`includeInBundle = false`), for reproducible builds | `app/build.gradle.kts:195-198`. The Console may note that the bundle has no dependency information; expected. |

### What is not in a production build

- **Calls.** `:core:calls` and `:feature:calls` are linked into debug, devStack and staging only
  (`app/build.gradle.kts:1114-1118`); release compiles `src/noCalls` (`:231-233`). `checkReleaseCalls` fails a release
  APK with the media stack, call classes, call permissions (`RECORD_AUDIO`, `FOREGROUND_SERVICE_PHONE_CALL`,
  `FOREGROUND_SERVICE_MICROPHONE`, `MANAGE_OWN_CALLS`, `USE_FULL_SCREEN_INTENT`, …) or call components
  (`:702-786`, `:1027-1033`); `callsMarkers` are forbidden strings in the dex (`:928-931`, `:1058`).
- **The wallet.** `:core:chain`, `:core:wallet`, `:feature:wallet` are debug, devStack and staging only (`:1119-1124`);
  release compiles `src/noWallet` (`:234-238`). `checkReleaseWallet` (`:630-690`, `:1035-1041`) and `walletMarkers`
  (`:940-943`) hold it.
- **Debug tools.** Release compiles `src/noDebugTools` (`:227`); the component gallery, screenshot launch extras and
  dev-stack addresses are forbidden strings (`devOnly`, `:972`); `:core:testing` is devStack only (`:1168`).
- **Background location.** Never declared: `check<Variant>BackgroundLocation` runs with every assemble, bundle and
  unit-test task (`:336-369`, `:998-1005`).
- **Media and storage permissions.** None: `check<Variant>MediaPermissions` (`:376-406`, `:995-1005`).
- **Google push** is linked but off unless the build has the four `vettidFcmRelease*` Gradle properties
  (`app/build.gradle.kts:29-44`, `:166-168`; README "Google push"). Without them the push components are disabled in the
  manifest and Firebase is never initialized. See question Q4.

**Never pass `-PvettidCallsInRelease=true` or `-PvettidWalletInRelease=true` for the Play build**: either one changes
the application id to `com.vettid.app.callscheck`, a check build that is never published (`app/build.gradle.kts:115-123`).

### Building the App Bundle

The variant is `fullRelease`, so AGP's bundle task is `:app:bundleFullRelease`. There is no `bundleRelease` alias in
the build: the aliases cover test, lint, install, uninstall and connected tests only (`app/build.gradle.kts:1085-1104`).

On a quiet machine (`CLAUDE.md:25-26`: check `free -g`, `--max-workers=2`, `./gradlew --stop` after):

```bash
export JAVA_HOME=/opt/android-studio/jbr     # JDK 17 or 21 (CLAUDE.md:20-21)
# 1. What CI runs for the release (CLAUDE.md:27; .github/workflows/ci.yml), the relevant part:
./gradlew --max-workers=2 testDebugUnitTest :core:crypto:test :core:relay:test detekt :app:lintDebug
# 2. The release APK of the same variant, and its checks:
./gradlew --max-workers=2 :app:assembleFullRelease :app:checkReleaseApk
# 3. The bundle:
./gradlew --max-workers=2 :app:bundleFullRelease
./gradlew --stop
```

- `checkReleaseApk` runs `checkReleasePushManifest`, `checkReleaseApplicationId`, `checkReleaseCalls`,
  `checkReleaseWallet` and `checkReleaseAbi` first (`app/build.gradle.kts:1052-1062`), and checks the production
  endpoints and pins are present and the staging ones absent (`:959-972`).
- These checks read the **APK** of `fullRelease` (`SingleArtifact.APK`), not the `.aab`. The bundle is built from the
  same variant's dex and resources, but no task checks the `.aab` file itself. `bundleFullRelease` does run
  `checkFullReleaseMediaPermissions` and `checkFullReleaseBackgroundLocation` (`:1003-1005`).
- The bundle should be at `app/build/outputs/bundle/fullRelease/app-full-release.aab` (AGP's default; **To confirm**
  after the build).
- **Signing is the owner's**: sign the `.aab` with the dedicated Play upload key, outside the repository (ANDROID-PLAN
  §7 "The keys"; never VettID's release key). No signing file is read or created by this repository for release.

### Producing the merged manifest

Section 2 is read from the source manifests. To see the exact merged manifest of this commit:

```bash
./gradlew --max-workers=2 :app:processFullReleaseManifest
# app/build/intermediates/merged_manifests/fullRelease/processFullReleaseManifest/AndroidManifest.xml
# app/build/outputs/logs/manifest-merger-full-release-report.txt   (where each entry came from)
```

or, from the bundle, `bundletool dump manifest --bundle app-full-release.aab`. The library entries below were
cross-checked against the merger report of an earlier local `fullRelease` build (another worktree, not this commit);
**To confirm** against this commit's report before filling the Console.

---

## 2. Permissions in the merged release manifest

| Permission | Declared by | Why | Play |
|---|---|---|---|
| `INTERNET` | `app/src/main/AndroidManifest.xml:6` (also Firebase) | The relay and the member API | — |
| `ACCESS_NETWORK_STATE` | app `:11`, `core/notify/src/main/AndroidManifest.xml:10` (also WorkManager) | Retry after Doze when a network is usable; the on-phone service watches for a validated network (`VaultConnectionService.kt:188-214`) | — |
| `USE_BIOMETRIC` | app `:13` | The biometric app lock | — |
| `POST_NOTIFICATIONS` | app `:15`, notify `:12` | Notifications, asked for in context | Runtime permission |
| `RECEIVE_BOOT_COMPLETED` | app `:17`, notify `:8` (also WorkManager) | The pending-lock job after a restart; the on-phone service after boot and after an app update (`BootReceiver`, `VaultConnectionService.kt:216-237`) | — |
| `FOREGROUND_SERVICE` | notify `:5`, `core/location/src/main/AndroidManifest.xml:11` (also WorkManager) | The two foreground services | — |
| `FOREGROUND_SERVICE_SPECIAL_USE` | notify `:6` | The on-phone service (`specialUse`) | **Console declaration** (section 3.1) |
| `FOREGROUND_SERVICE_LOCATION` | location `:12` | Location sharing (`location`) | **Console declaration** (section 3.2) |
| `ACCESS_COARSE_LOCATION` | location `:6` (also MapLibre) | Location sharing, Approximate and City | **Sensitive**: runtime permission; Data safety (Location). Foreground only, so no background-location declaration |
| `ACCESS_FINE_LOCATION` | location `:7` (also MapLibre) | Location sharing, Exact | **Sensitive**: as above |
| `CAMERA` | `core/ui/src/main/AndroidManifest.xml:4` | QR codes (invitations, transfer, recovery) and the in-app profile photo | Runtime permission; `uses-feature camera.any` not required (`:5`) |
| `WAKE_LOCK` | WorkManager, Firebase Messaging (merger report) | Google push's expedited work | — |
| `com.google.android.c2dm.permission.RECEIVE` | Firebase Messaging (merger report) | Receiving Google pushes | — (the push components are disabled without the Firebase options, `app/src/full/AndroidManifest.xml:32-42`) |
| `com.vettid.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX Core (merger report) | Its own, signature level, for runtime receivers | — |

Removed on purpose: `ACCESS_WIFI_STATE` and the `android.hardware.wifi` feature that MapLibre adds
(`app/src/main/AndroidManifest.xml:8-9`). Not present: `ACCESS_BACKGROUND_LOCATION`, any media or storage permission,
`AD_ID` (`PushManifestCheck` fails on it, `app/build.gradle.kts:446`), `SCHEDULE_EXACT_ALARM` (the cover alarm is
inexact, `CLAUDE.md:181`), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the app opens the system list instead,
`NotificationSettings.kt:234-235`), and every call permission.

Other manifest entries worth knowing when the Console asks:

- `<queries>` for `com.google.android.gms` (`core/push-fcm/src/main/AndroidManifest.xml:4-6`): whether Google Play
  services are present. Not `QUERY_ALL_PACKAGES`.
- WorkManager's `SystemForegroundService` is in the merged manifest **without a `foregroundServiceType`** (merger
  report). The app never makes WorkManager run a foreground service: the push wake is expedited work
  (`core/push-fcm/.../VettIdMessagingService.kt:74-78`), which on API 31+ runs as an expedited job, not a foreground
  service, and minSdk is 31. The Console asks for a declaration per *type*; this service has none. **To confirm** that
  the Console does not list it.

---

## 3. Foreground-service declarations

The release declares exactly two foreground-service types: `specialUse` (`core/notify/src/main/AndroidManifest.xml:18-25`)
and `location` (`core/location/src/main/AndroidManifest.xml:20-23`). The only other foreground service in any module
is `CallService` of type `phoneCall|microphone` (`core/calls/src/main/AndroidManifest.xml:20-23`), which is not linked
into release (section 1). So the release needs no `phoneCall`, `microphone` or `dataSync` declaration.

### 3.1 `specialUse`: the on-phone service

**Feature.** Notifications without a push service. `VaultConnectionService` keeps the app's own end-to-end encrypted
relay connection open, so that messages from the member's vault are collected and decrypted on the phone and turned
into local notifications while VettID is in the background (`VaultConnectionService.kt:42-48`; ANDROID-PLAN §4
"Notification modes" 3). It is the default mode (`Preferences.kt:53-58`, `:100`, `:128`) and the only mode in
builds without Google push.

**Subtype in the manifest:** "Keeps the user's own end-to-end encrypted message connection open to show notifications
on devices without a push service" (`core/notify/src/main/AndroidManifest.xml:22-24`).

**Why this type** (ANDROID-PLAN §4 "Notification modes" 3, "Foreground-service type"; §9 question 11, answered
2026-10-09; `core/notify/src/main/AndroidManifest.xml:15-17`):

- Not `dataSync`: Android 15 limits it to 6 hours a day and forbids starting it from `BOOT_COMPLETED`. The service must
  run all day and start after boot.
- Not `remoteMessaging`: that type is for continuing a messaging task on another device, which this is not, and Play
  reviews it against that use. It is the fallback only if Play refuses `specialUse`.
- Never `dataSync` even as a fallback.

**What the user sees.** One silent ongoing notification on the **Connection** channel, importance MIN (no status-bar
icon; collapsed at the bottom of the shade), private on the lock screen, never naming a connection
(`VaultConnectionService.kt:107-119`; `Channels.kt:60`, `:82`). Its title is the state: "VettID is connected",
"VettID is connecting", "Waiting for network", "Vault locked", "Daily check due"
(`core/notify/src/main/res/values/strings.xml:79-83`). Tapping it opens Settings → Notifications
(`VaultConnectionService.kt:118`, `AndroidPoster.kt:139-142`, `MainActivity.kt:194`). Without the notification
permission Android shows the service only in its task manager (ANDROID-PLAN §4 Notification modes 2).

**How it starts** (`ModeController.kt:70-85`):

- at process start, when the phone has a vault and the mode is the on-phone service (`VettIdApplication.kt:190`,
  `AppModule.kt:338-346`);
- when the member chooses **On-phone service** in Settings → Notifications (`NotificationSettings.kt:256-261`);
- after boot and after an app update (`BootReceiver`, `VaultConnectionService.kt:216-237`; `AppModule.kt:354-361`);
- as the fallback while Google push is chosen but cannot deliver (`ModeController.kt:72-76`);
- a start Android refuses in the background is tried again when the app comes to the front
  (`VaultConnectionService.kt:167-175`, `VettIdApplication.kt:206`).

It never starts on a phone without an enrolled vault (`ModeController.kt:128-132`).

**How it stops:** the member chooses **Off** (confirmed: "Turn notifications off?" → **Turn off**,
`NotificationSettings.kt:269-275`, `:309-321`) or **Google push** once the vault holds the registration
(`ModeController.kt:115-118`); or the phone has no vault any more (signed out, wiped, deleted): the service removes its
notification and stops itself (`VaultConnectionService.kt:73-75`). It is `START_STICKY` (`:78`). While the vault is
locked it keeps no relay connection open and only reads the vault's state now and then (`ConnectionKeeper.kt:23-26`).

**Draft of the Console description (specialUse):**

> VettID is an end-to-end encrypted messenger and personal vault. Messages are delivered to the user's own vault, and
> the app must fetch and decrypt them on the phone to show a notification. Many of our users run phones without
> Google Play services (for example GrapheneOS), and some choose not to use Google push for privacy. For them the app
> offers "On-phone service" (Settings → Notifications), the default: a foreground service that keeps the app's own
> encrypted connection to the user's vault open, so that new messages, requests and security alarms are notified
> while the app is in the background. It must run continuously and restart after the phone restarts; no other
> foreground-service type fits this: dataSync is limited to 6 hours a day and cannot start after boot, and
> remoteMessaging is for continuing a messaging task on another device. The service shows a silent, minimal
> notification with its connection state. The user turns it off at any time in Settings → Notifications (Off, or
> Google push where available). If the service is stopped or deferred, the user is not told about new messages,
> connection requests or security alarms (for example a sign-in attempt on their vault) until they open the app.

**Video script (on-phone service), about 45 s.** Setup: a test phone with an enrolled vault on a production build
(a Play install cannot enroll yet, see the top of this document), notifications allowed for VettID, a second phone or
member connected to it, the mode left at **On-phone service**. Turn on **Settings → App → Allow screenshots** first:
the app's window is `FLAG_SECURE` and records as black otherwise (`MainActivity.kt:150-157`; `CLAUDE.md:731-733`). It
turns itself off after 1 hour or when the vault locks (`feature/settings/src/main/res/values/strings.xml:49`).

1. Open VettID, unlocked. Open the drawer → **Settings**; scroll to **App** → **Notifications** (row shows "On-phone
   service", `SettingsScreen.kt:622`, `:640-647`).
2. Show **How notifications reach this phone** with **On-phone service** selected and **Status: Connected**
   (`NotificationSettings.kt:251-278`).
3. Pull down the shade: show the "VettID is connected" notification at the bottom. Close the shade.
4. Press Home. On the second phone, send a message to the test phone's member. Show the message notification arriving
   on the test phone while VettID is in the background.
5. Open VettID again (to Settings → Notifications). Tap **Off** → **Turn off**. Pull down the shade: the "VettID is
   connected" notification is gone.
6. Tap **On-phone service**. Pull down the shade: "VettID is connecting", then "VettID is connected".
7. Tap that notification: it opens Settings → Notifications. End.

### 3.2 `location`: sharing the member's location

**Feature.** The member shares their location with one connection, once or for a while (15 min to 7 days). The
foreground service keeps positions going with the screen off (`LocationService.kt:41-49`; ANDROID-PLAN §4 "Location";
`CLAUDE.md:141`).

**Why this type.** It is a location task the user started and can see. `location` is the type Android and Play
provide for it. The share starts only from the member's tap while the app is visible, so the while-in-use permission is
enough and `ACCESS_BACKGROUND_LOCATION` is never declared (`core/location/src/main/AndroidManifest.xml:8-10`;
`app/build.gradle.kts:328-335`). ANDROID-PLAN §4 "Location" (around line 4740) notes a `location` foreground service
needs the foreground-service declaration but not the background-location review.

**What the user sees.** A silent ongoing notification on the **Location sharing** channel (low importance,
`Channels.kt:61`): "Sharing your location with {First} until {time}" or "with {n} people", "…again, until {time}."
after a restart of the share, with a **Stop all** action (`core/location/src/main/res/values/strings.xml:6-16`;
`LocationService.kt:108-147`). The lock screen version never names anyone (`LocationService.kt:120-127`). While the
daily check is due it says "Paused until your daily check." with **Check now**; when a permission or the phone's
location switch is off it adds **Open settings** (`LocationService.kt:130-138`). Its tap opens the app.

**How it starts:** only from the member's tap on **Start sharing** in the **Share my location** sheet, with the app
visible (`ShareLocation.kt:513`; `LocationService.kt:165-169`; `AndroidLocationPlatform.kt:64`;
`LocationSharing.kt:225`); the location permission is asked at that tap, with the text "To share your location, VettID
needs to know where this phone is. It asks only while you share, and sends nothing to anyone else."
(`ShareLocation.kt:451-455`). After a lock or a restart it is taken up again when the app is in front
(`CLAUDE.md:141`, `resume()`, `onForeground()`). It is `START_NOT_STICKY` (`LocationService.kt:58`).

**How it stops:** the card's **Stop** on the connection page (`ShareLocation.kt:642-647`; `LocationSharing.kt:294`),
**Stop all** on the notification (`LocationService.kt:63`, `:139-145`; `LocationSharing.kt:317`), the share's end time,
the vault lock, sign-out or wipe (`CLAUDE.md:141`). The service ends by an `ACTION_STOP` it handles after its own
`startForeground` (`LocationService.kt:65-69`, `:171-180`; `AndroidLocationPlatform.kt:66`).

**Draft of the Console description (location):**

> The user can share their location with one of their contacts in VettID, once or for a chosen time (15 minutes to
> 7 days). The share starts only when the user taps "Start sharing" with the app open, and the location permission is
> asked at that moment (foreground only; the app never requests background location). While a share runs, a
> foreground service of type location keeps sending the user's position to the chosen contact with the screen off,
> end-to-end encrypted, at the interval the user picked. A silent ongoing notification says with whom the location is
> shared and until when, and has a "Stop all" button; the share also ends at its end time or from the "Stop" button
> in the app. If the service is deferred or stopped, the contact stops receiving the user's position, and the app
> tells the user that their location is not getting through.

**Video script (location sharing), about 45 s.** Setup as in 3.1 (production build, enrolled vault, **Allow
screenshots** on), a person connection that is active, and a vault that supports location (the card is hidden
otherwise, `CLAUDE.md:141`). Location permission not yet granted, to show the prompt.

1. Open VettID → drawer → **Connections** → tap the connection. Show the **Share my location** button on its page
   (`ShareLocation.kt:604-619`; `ConnectionDetailScreen.kt:262`).
2. Tap **Share my location**. In the sheet choose **For a while** → **15 min**, **Precision: Approximate**.
3. Tap **Start sharing**. Android asks for location; choose **While using the app**.
4. The sheet says "Sharing your location with {First} until {time}." and "Finding your location…", then closes. The
   card now reads "Sharing your location until {time}" with **Stop**.
5. Press Home and turn the screen off for a few seconds, then on. Pull down the shade: show "Sharing your location
   with {First} until {time}" and **Stop all**.
6. Tap **Stop all**. The notification disappears. Open VettID: the card shows **Share my location** again.
7. (Optional, 10 s) Start a second share and end it with the card's **Stop**. End.

---

## 4. Data safety form (draft)

**How data leaves the phone** (VAULT-MESSAGING §2.2, lines 2841-2848 in vettid.org; `CLAUDE.md:704-706`):

- **The member's vault** (a VettID-operated enclave): everything the app sends it is sealed end-to-end with the vault's
  keys. Over the relay, or through the member API's alternate channel as a sealed envelope
  (`MemberApiClient.kt:324-341`).
- **The relay** (`relay.vettid.org`, `VaultSession.kt:31`) sees mailbox ids, relay keys, timing, padded sizes and the
  client kind; never message types, content or which identity a key belongs to.
- **The member API** (`account.vettid.org`, `MemberApiClient.kt:442`) sees in the clear: which member enrolled,
  unlocked or locked and when, `vault_id`, the member's `user_guid`, the app key's public key, the setup code being
  redeemed and, when the member types the code instead of scanning it, the **email address** they type
  (`MemberApiClient.kt:230-236`). Every request is signed with the app's Keystore key (`CLAUDE.md:704-706`).
- **The VettID host** (outside the enclave) also sees the account snapshot (email, first and last name, membership)
  in enroll and unlock messages, and name-change requests (VAULT-MESSAGING §2.2 line 2845). That data comes from the
  account site; the app's part is the name change it sends to the vault.
- **`vettid.org`**: the pinned release manifest is read (`MemberApiClient.kt:443`). **`votes.vettid.org`**: read only,
  no cookies, session or identifier (`VaultSession.kt:32`; `CLAUDE.md:413-419`; no `POST` to it exists).
- **OpenFreeMap** (`tiles.openfreemap.org`, `MapServer.kt:18`), a third party, receives an **approximate location**:
  the map tiles around a position another member shares with this member. When exactly:
  - only on the position screen of a **received** share (the map is composed only with a received position, and goes
    with the share's end, the lock and the daily check; `LocationViews.kt:834-837`). Nothing of the member's own
    shares, and no map anywhere else;
  - only after the member taps **Show map** on the privacy note naming the server's host (`LocationViews.kt:671-674`;
    `MapSdk.kt:80`, `prepare` is called by Show map and by the map itself, never before). Nothing is fetched while
    the note is up, and **Not now** fetches nothing for that visit;
  - the answer is stored per host (`mapNoteHost`, `MapServer.kt:85`): **after one Show map, later position screens for
    that host load the map without asking again**, until the member changes the server;
  - requests go only to that host, over HTTPS, with a generic `Mozilla/5.0` User-Agent and no cache
    (`CLAUDE.md:143`). OpenFreeMap sees the phone's IP address and which tiles are fetched. A member who sets their
    own server (Settings → Privacy → Map server) sends the same to that server instead.
- **No chain server** (Esplora, mempool.space) is contacted by a release build: the wallet is not in it (section 1).
- **Google (Firebase)**: only in a build with the Firebase options and only after the member chooses Google push. The
  push token goes to the member's vault (`push.register`) and from it to VettID's push gateway, which asks Firebase
  for content-less wakes (README "Google push"; `CLAUDE.md:112-122`).

The app has no analytics, no advertising id, no crash reporting of its own (`CrashScrubber.kt` only hands a scrubbed
crash to Android's own handler), no Room database, and no backup (`allowBackup="false"`).

**Never leaves the phone:** the Keystore private keys (app key, attestation key, app-lock key); the biometric check
(the system's); the phone's own position used for a received share's distance and direction (one `getCurrentLocation`,
dropped after the offset is worked out, `CLAUDE.md:142`); camera frames other than a photo the member takes.

**Never written to the phone's storage:** positions sent (`Fix` held only until sent, `CLAUDE.md:141`; checked by
`DataDirectoryScanTest`) and received (`CLAUDE.md:142`). **Memory only:** presence readings and policy
(`CLAUDE.md:140`), received positions and trails, the devices list, votes, the "Allow screenshots" choice; the map's
tile cache is held at 0 (`CLAUDE.md:143`).

**Correction to the brief:** location positions *do* leave the phone. Each position goes to the member's own vault as
an ephemeral, end-to-end encrypted `location.update`; the vault forwards it, reduced to the share's precision, to the
chosen connection, and never writes it to vault state (VAULT-MESSAGING §8.5, lines 6711-6719, and line 6947). What
never leaves the phone is listed above.

**Gap between the in-app wording and the declaration.** The location permission text says "It asks only while you
share, and sends nothing to anyone else." (`connections_location_permission`,
`feature/connections/src/main/res/values/strings.xml:324`; the same words in `approvals_location_permission`,
`feature/approvals/src/main/res/values/strings.xml:257`). The Data safety form declares location as collected (sent
to the member's VettID-run vault) and shared (map tiles to OpenFreeMap). The sentence is true in the sense that only
the chosen connection receives the position, but a reviewer may read it against the form. It is the plan's DRAFT text,
shared with the iPhone app, so it is not reworded here: the owner's call (Q10).

### The key decision: data end-to-end encrypted to the member's own vault

Most of what the app sends (messages, photos, vault items, location, connections, the attestation chain) goes only to
the member's vault, end-to-end encrypted, where VettID cannot read it. Whether Play counts that as "collected" by the
developer is **To confirm** against Play's Data safety help at submission (question Q1). The table below gives the
conservative answers (declared as collected, purpose App functionality, not shared) and says for each row what the
alternative would be.

### Answers per data type

"Shared" is No **except Location: Approximate** (OpenFreeMap, above). Transfers to another member happen only by the
member's own action (a message, a share), which Play does not count as sharing (**To confirm**), and no data is sold.
"Encrypted in transit": Yes for everything (HTTPS/TLS to every endpoint; the map server must be HTTPS,
`CLAUDE.md:143`; no cleartext in the release manifest). "Deletion": the member can delete the vault in the app
(Settings → Account → **Delete vault**, `SettingsScreen.kt:658-660`) and request account deletion at
`https://account.vettid.org/account/delete/` (Settings → Account → **Delete your VettID account**,
`SettingsScreen.kt:663-668`; `Repositories.kt:16`; `CLAUDE.md:22`). That URL answers with a 302 redirect: **To
confirm** that the page it lands on is acceptable to Play as the deletion page (Play expects a page that names the app
and explains the deletion).

| Play data type | Collected | Shared | Optional | Purposes | Basis in the code / docs |
|---|---|---|---|---|---|
| Location: Approximate | Yes, processed ephemerally (E2E to the vault) | **Yes**: OpenFreeMap receives the tile area around a received position, after Show map | Yes | App functionality | `location.update` (`CLAUDE.md:141`), never stored on the phone or in vault state; the map fetch as described above |
| Location: Precise | Yes, processed ephemerally (E2E) | No | Yes (Exact only) | App functionality | Same; Exact needs fine location. The map draws a dot only for Exact, but the tiles fetched are an area, not a point (`CLAUDE.md:143`) |
| Personal info: Name | Yes | No | No (account) | App functionality, Account management | Account snapshot and name changes (VAULT-MESSAGING §2.2); shared profile name sent E2E |
| Personal info: Email address | Yes | No | Yes (typed setup code only; the account site already has it) | Account management | `MemberApiClient.kt:234-236` |
| Personal info: User IDs | Yes | No | No | App functionality, Account management, Security | `user_guid`, `vault_id`, app public key (`MemberApiClient.kt:238-241`; VAULT-MESSAGING §2.2) |
| Personal info: Address, Phone number, Other info | Yes if the member stores them as vault items (E2E) | No | Yes | App functionality | Item templates `postal_address`, `phone_number`, passwords and secrets (`CLAUDE.md:635`) |
| Personal info: Political or religious beliefs | **To confirm** | No | Yes | App functionality | Votes: the member's choice goes through their vault to the service that runs the vote (`CLAUDE.md:402-423`) |
| Financial info: User payment info | Yes if stored as a vault item (E2E) | No | Yes | App functionality | The card item template (`items.edit_card`, `CLAUDE.md:635`). The wallet is not in release |
| Messages: Other in-app messages | Yes (E2E) | No | Yes | App functionality | Messages through the vault (RELAY-PROTOCOL; `CLAUDE.md:7-10`) |
| Photos and videos: Photos | Yes (E2E) | No | Yes | App functionality | Message photos (Photo Picker) and the profile photo taken in-app (`core/media/src/main/AndroidManifest.xml:2-3`; `CLAUDE.md:635`) |
| Contacts | **To confirm** (No phone contacts are read; the VettID connections list lives in the vault, E2E) | No | — | App functionality | No `READ_CONTACTS` in any manifest |
| App activity: Other user-generated content | Yes (E2E) | No | Yes | App functionality | Vault items, tags, notes |
| App activity: App interactions | Yes | No | No | App functionality, Security | The member API sees in the clear when the member enrolls, unlocks and locks (`MemberApiClient.kt:336-359`; VAULT-MESSAGING §2.2 line 2846). The vault's History of the member's actions stays inside the enclave |
| Device or other IDs | Yes (E2E to the enclave); plus the push token when Google push is on | No | No / Yes (push) | Security (fraud prevention), App functionality | The attestation chain is a stable device identifier, kept inside the enclave (VAULT-MESSAGING §11.7, lines 14986-14988); push token (README "Google push") |
| Audio, Files and docs, Calendar, Health and fitness, Web browsing, Installed apps, In-app search history | No | — | — | — | No such permission or code path in release (calls are not in release; History export is saved only on the phone) |
| App info and performance: Crash logs, Diagnostics | No (by VettID) | — | — | — | No crash or analytics SDK (`app/build.gradle.kts:409-506`). Android vitals are Google's |

**Also To confirm:** whether VettID's servers keep IP addresses (relay, member API, account site) and for how long;
the relay's documented logs name only the route (vettid.org `docs/RELAY-PROTOCOL.md:856-873`). If they are kept,
Play may expect them under Device or other IDs or Approximate location.

**Security practices:** encrypted in transit: Yes. Deletion can be requested: Yes (above). Independent security
review: owner's answer.

**Privacy policy URL: proposed `https://vettid.org/privacy/`** (it answers 200). Before filing, the owner checks that it
covers at least:

- **location forwarding**: positions go, end-to-end encrypted, to the member's own vault, which forwards them reduced
  to the share's precision to the chosen connection and does not store them;
- **OpenFreeMap**: the map tiles around a received position are fetched from OpenFreeMap (or the member's own map
  server) after Show map, which sees the phone's IP address and the area;
- **Play signing**: the Google Play version is signed by Google through Play App Signing, and the F-Droid and direct
  versions by VettID (the listing sentence in ANDROID-PLAN §7);
- what VettID's servers see in the clear (email, name, ids, enroll/unlock/lock times; section 4 above), the end-to-end
  encryption of everything else, and account deletion at `https://account.vettid.org/account/delete/`.

---

## 5. The pre-launch report

**What it is.** When a build is uploaded to a testing track (internal included), Play installs it on a set of real
and virtual devices and runs an automated crawler over it for a few minutes, then reports crashes, ANRs, performance,
accessibility and security warnings, with screenshots (**To confirm** the current device set and settings in the
Console). It can be given sign-in credentials for a username-and-password screen, or a test script.

**What will likely fail or be empty, and why:**

1. **Every screenshot is black.** The window is `FLAG_SECURE` from before the first frame in every build
   (`MainActivity.kt:150-157`; `CLAUDE.md:731`). "Allow screenshots" exists only in Settings after enrollment
   (`SettingsScreen.kt:649`, `:731-740`) and lasts at most an hour, so the crawler cannot turn it on.
2. **The crawler stops at onboarding.** Without an enrolled vault the app offers only **Get started** (scan a setup QR
   code, or type the account's email and an 8-character code), **Move from my old phone** and **I lost my phone**
   (`feature/onboarding/src/main/res/values/strings.xml:4-30`). A setup code works once, for 5 minutes (`:14`), and
   comes from the member's account site; the other two need the old phone or a recovery code. Random input is refused
   by the member API (`404 invalid_code`, `MemberApiClient.kt:226-229`); **To confirm** whether repeated crawler
   attempts trip the member API's rate limit for that device.
3. **Attestation and the signer.** Even with a valid code, enrollment sends the phone's key attestation, and a
   production vault refuses an app signed by Google's app-signing key until a release pins it (top of this document;
   `CLAUDE.md:86-97`). Test devices with an unlocked bootloader or an emulator would also fail attestation.
4. **Fewer devices.** The release carries ARM libraries only (section 1), so x86 virtual devices cannot install it
   (**To confirm** whether the report's virtual devices are x86), and minSdk 31 excludes older phones.
5. **Accessibility warnings** on the onboarding screens it can reach. The A6 close-out left 4 accessibility findings
   Partly and 22 Open (`docs/A6-CLOSEOUT.md:42-47`, table at `:88-118`). Likely to show: low contrast of outlines and
   switch tracks (A18, Open, an owner decision on the palette, `docs/A6-CLOSEOUT.md:109`) and failures not announced
   on onboarding (A1, Partly, `OnboardingScreens.kt:163`, `docs/A6-CLOSEOUT.md:92`).
6. **Camera prompt.** **Get started** opens the QR scanner, which asks for the camera; the crawler may deny it, and the
   typed-code path still works.

**Credentials or a demo path.** None exists. The app never signs in with a username and password (`CLAUDE.md:704`),
there is no demo mode or reviewer account in the code, and adding a way past enrollment would be a backdoor. **The
crawler will see only onboarding.** The owner's options (question Q3):

- (a) Accept an onboarding-only report: it still catches crashes at start, the onboarding screens' accessibility, and
  install problems on the device set.
- (b) Give Play's review team (App content → App access) written instructions and the two videos, explaining that
  access needs a membership and a one-time setup code from the account site; no credentials can be given.
- (c) Later, once a production release pins the Play certificate, a tester account on a real phone could give a
  manual report; that is not the pre-launch report.

---

## 6. The owner's steps, in order

1. **Give the go on the declarations** (section 3, question Q2): `specialUse` with its text and video, `location` with
   its text and video. Without it nothing below is filed.
2. **Create or confirm the app** in the Play Console as `com.vettid.app` (question Q5: whether the v1 listing still
   exists). Enroll in Play App Signing with Google's generated key; register the dedicated **upload key** (ANDROID-PLAN
   §7). Note the app-signing certificate's SHA-256 for the vault release that will pin it (**To confirm** whether the
   Console shows it before the first upload).
3. **Pin the Play key in a vault release** (Q8). The next production vault release adds
   `{digest, class: "google_play"}` to `enclave/releasecfg/prod.json` and must reach `active`; the same digest goes into
   account.vettid.org's `/.well-known/assetlinks.json` (and the relay's, ANDROID-PLAN §7). Until then a Play install
   gets `signer_refused` and stops at onboarding, and **no member-installable track opens**. Steps 4-8 can go ahead on
   the internal track meanwhile (Q7).
4. **Build and sign** the bundle (section 1): run the checks, `:app:bundleFullRelease`, sign with the upload key.
5. **Upload to Internal testing** with a tester list of the owner and named testers only.
6. **Fill App content:** the two foreground-service declarations (section 3), the Data safety form (section 4), App
   access (section 5, option b), Ads: none, the content rating questionnaire (users communicate with each other and
   share location; no user-generated public content; **To confirm** the answers in the questionnaire), target
   audience (Q9), the privacy policy URL (`https://vettid.org/privacy/`, section 4), and the account deletion URL
   `https://account.vettid.org/account/delete/`.
7. **Record the two videos** with the scripts in 3.1 and 3.2 on the test phone, with **Allow screenshots** on; upload
   them where the Console accepts a link (**To confirm**: YouTube unlisted or another link the Console takes).
8. **Read the pre-launch report** when it appears (usually within an hour of the upload, **To confirm**) and send me the
   blocking items: crashes, ANRs, security warnings, and accessibility findings with their screens.

---

## 7. Open questions for the owner

**Q1. Data end-to-end encrypted to the member's own vault: declare it as collected?**
(a) Declare it (as in section 4): over-declaring is allowed, users see a longer list, and the privacy policy explains
the end-to-end encryption. (b) Declare only what VettID's servers read in the clear (email, name, ids): shorter, but if
Play counts a VettID-operated enclave as the developer's server, it is under-declaration.
**Recommendation: (a)**, unless Play's current help text explicitly exempts end-to-end encrypted data.

**Q2. Go on the `specialUse` declaration as drafted?**
(a) Yes, file `specialUse` with the text and video of 3.1. (b) Change the text first. (c) File `remoteMessaging`
instead (the plan allows it only after a refusal).
**Recommendation: (a)**; fall back to `remoteMessaging` only if Play refuses, never `dataSync` (question 11).

**Q3. The pre-launch report can only see onboarding. Which path?**
(a) Accept it as is. (b) Also fill App access with written instructions and the videos. (c) Ask for a reviewer path
in the app (a design change, outside this task, with its own security review).
**Recommendation: (a) + (b)**; no reviewer path.

**Q4. Google push in the Play build?**
(a) No: build without the `vettidFcmRelease*` properties; the on-phone service is the only mode, Data safety has no
push token. (b) Yes: set them; the on-phone service is still the default, and Data safety adds the push token
(Device or other IDs) and Google as a service provider.
**Recommendation: (a)** for the internal build: the gateway has no platform enabled yet (ANDROID-PLAN §8 risk 4).

**Q5. Is there still a v1 listing for `com.vettid.app`?**
ANDROID-PLAN D1 says the rewrite keeps the v1 listing and signing key; §7 says Google generates the app-signing key
"at enrollment". (a) There is a listing: confirm which app-signing key it has; if it is not Google-generated, the §7
plan needs revisiting before upload. (b) There is none: create the app fresh.
**Recommendation:** check in the Console before step 2 and tell me which; nothing is uploaded until it is known.

**Q6. Which build records the videos?**
(a) The VettID-signed production release on the owner's phone (same code; a Play install cannot enroll yet).
(b) The staging build (`com.vettid.app.staging`, white band on the icon, staging vault).
**Recommendation: (a).** Note that the Play install and the VettID-signed install cannot be on one phone at once
(same id, different signer).

**Q7. Is an internal track acceptable before a vault release pins the Play certificate?**
The plan forbids going live on "any track that members can install from" before that. (a) Yes, internal testing with
named testers only is not a member track. (b) No, wait.
**Recommendation: (a)**, with testers limited to the owner and named testers, knowing every Play install stops at
onboarding until the pin.

**Q8. Which vault release pins the Play app-signing key?**
It adds `{digest, class: "google_play"}` to `prod.json`, must reach `active`, and goes with the `assetlinks.json`
change. (a) The next production release after the Play app-signing certificate is known. (b) A dedicated small release
for it. (c) Later, with a planned feature release.
**Recommendation: (a)**, as soon as the digest is shown in the Console; nothing member-installable opens before it.

**Q9. Target audience: Adults (18+) only?** (yes / no) **Recommendation: yes.**

**Q10. The location permission text "…and sends nothing to anyone else."** (section 4, the gap) (a) Keep it.
(b) Reword it with the iPhone app (it is the plan's shared DRAFT), for example to say that only the chosen connection
receives the position. **Recommendation: (b)**, before the listing goes live; not a blocker for the internal track.
