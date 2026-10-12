# A6 audit: hardening and polish

Audit of the Android app for phase A6 of
[ANDROID-PLAN](https://github.com/vettid/vettid.org/blob/master/docs/ANDROID-PLAN.md) §6
("accessibility pass, notifications, offline behaviour, error states, Play pre-launch report";
notifications are done and are not covered here). This is an audit only: nothing in the app was
changed.

- **Audited commit:** `4e793592` (master, 2026-10-11).
- **Method:** reading the code of every `:feature:*`, `:core:*` and `:app` main source set; contrast
  ratios computed from `core/ui/.../theme/Color.kt`; the merged manifests of `fullRelease` and
  `fossRelease` generated once (`:app:processFullReleaseMainManifest`,
  `:app:processFossReleaseMainManifest`). No phone, emulator, adb, dev stack or AWS was used, so
  nothing here was observed running.
- **What the vault, relay and member API do** is taken from VAULT-MESSAGING and MEMBER-API, never
  from server code. Where a finding depends on that, it says "per spec".
- **Store policy statements** (Play, F-Droid) are from memory, not from the repo: check each one in
  Play Console or with `fdroid scanner` before acting on it.

## How to read the findings

**Severity**

| Severity | Meaning |
|---|---|
| **Blocker** | For the first members (owner plus up to ten testers): data loss, lock-out, a security or privacy leak, or a core flow that cannot be used with TalkBack. |
| **Public** | Should be fixed before a public listing (Play, F-Droid). |
| **Polish** | Worth doing; nobody is harmed if it waits. |

**Status**

| Mark | Meaning |
|---|---|
| **V** | Verified in code: every step of the claim was read at the cited lines. |
| **C** | Computed (contrast ratios; the numbers are shown). |
| **S** | Suspected: the code path is read, but the visible effect needs a phone. The checklist in [Needs a real phone](#needs-a-real-phone) says what to try. |
| **V/S** | The code is verified; how it behaves at run time is suspected. |

**Fix size:** S = under a day, M = a few days, L = a week or more.

**Paths** are shortened. `onboarding/X.kt` is `feature/onboarding/src/main/kotlin/com/vettid/feature/onboarding/X.kt`
(the same for `settings`, `items`, `connections`, `messages`, `approvals`, `history`,
`notifications`, `credential`). `ui/X.kt` is `core/ui/src/main/kotlin/com/vettid/core/ui/components/X.kt`.
`data/…`, `vault/…`, `relay/…`, `altchan/…`, `notify/…` are under
`core/<module>/src/main/kotlin/com/vettid/core/<module>/`. `app/…` is `app/src/main/kotlin/com/vettid/app/…`.

## Counts

| Area | Blocker | Public | Polish |
|---|---|---|---|
| 1. Accessibility (A) | 0 | 18 | 9 |
| 2. Offline and poor network (N) | 3 | 8 | 8 |
| 3. Error states (E) | 1 | 10 | 13 |
| 4. Robustness (R) | 1 | 8 | 11 |
| 5. Client security hygiene (S) | 2 | 9 | 10 |
| 6. Performance and battery (P) | 0 | 5 | 5 |
| 7. Play and F-Droid readiness (G) | 0 | 9 | 3 |
| 8. Tests (T) | 0 | 3 | 5 |
| **Total** | **7** | **70** | **64** |

No accessibility blocker was verified. Two accessibility findings (A1, A2) become blockers if the
phone check confirms them and one of the first testers uses TalkBack.

---

## Blockers for the first members

Each of these was re-read line by line after it was first found.

### N1. A transfer to a new phone cannot be resumed once the safety code is shown; an approved transfer can leave neither phone holding the vault

- **Status:** V on the new phone's side; the old phone's and the vault's behaviour per spec (VAULT-MESSAGING §6.7.1).
- **Evidence**
  - `device.paired` only completes the pairing inside one in-memory wait: `st.deviceId` is set in
    `awaitPaired` (`vault/VaultDevice.kt:592-607`). A `device.paired` that arrives with no waiter
    only stores the token (`:921`) and is acked.
  - A new phone without `deviceId` and `userGuid` starts at the welcome screen, with the comment
    "A transfer that did not complete leaves nothing to resume" (`data/vault/VaultManager.kt:546-549`).
  - The app's own 10-minute timer deletes the keyring: `catch (e: TimeoutCancellationException) { s.device.abandonTransfer() }`
    (`VaultManager.kt:1226-1230`; `abandonTransfer` at `VaultDevice.kt:690-696`). Back on the
    compare screen does the same, with no confirmation (`onboarding/TransferInViewModel.kt:123-126`).
  - The wait runs in `viewModelScope` (`TransferInViewModel.kt:154`), so R1 also ends it. Nothing
    keeps the screen on.
  - If `device.paired` was applied but `credentialTakeOver()` (`VaultManager.kt:1241`, its only
    caller) failed, the next start routes to `NEEDS_CREDENTIAL` (`:572`), whose `credential.create`
    the vault answers `exists` per spec, shown as "Something went wrong".
- **Effect:** the member compares the code, then types PIN and password on the old phone. If the new
  phone's process dies, is rotated (R1), gets a back gesture, or is more than 10 minutes late, the
  vault completes the transfer, the old phone erases itself by design, and the new phone shows
  Welcome (or a credential form that cannot succeed). The way back is a 24 h recovery, which does
  not exist with credential backup off.
- **Fix (M):** persist a "transfer pending" record at `hs.fin`; apply `device.paired{transfer}` in
  the message handler, not in the waiter; on start with a pending transfer show a resume screen;
  never delete the keyring on the local timer (only on `device.pair.rejected` or an explicit
  cancel with a confirmation); treat "paired by transfer, no credential" as take-over, not create;
  keep the screen on during compare.

### N2. A request that fails in transport is reported as failed, but stays in the persisted outbox and is executed later

- **Status:** V on the client; what the vault does with the late request per spec.
- **Evidence**
  - `sendLocked` does `st.outbox.add(entry); save(); deliver(entry)` (`vault/VaultDevice.kt:716-718`).
  - `deliver` removes the entry only on success or a terminal relay error; a transport
    `IOException` leaves it and propagates (`:727-743`). `request()` then fails and the screen
    shows "VettID could not reach the service".
  - `flushOutbox` re-deposits whatever is left (`:766-775`) at collector start (`:362`), on every
    return to the foreground (`data/vault/VaultManager.kt:512`) and after every unlock (`:784`).
  - Entries carry no expiry (`sendLocked(…, null)`), and the outbox has no age or size limit.
- **Effect**
  - A message sent offline shows an error and keeps the draft; the member sends again; the peer
    gets it twice (`message.send` has no client id, `vault/VaultApi.kt:643-646`).
  - Writes the member was told had failed are applied later, unannounced: `pin.change`,
    `vault.delete`, a `vault.lock` flushed right after the next unlock.
  - A credential operation that failed offline and was then retried successfully is followed by a
    replay of the first attempt carrying the previous credential blob. Per spec (§3.4) that is the
    clone signature: `credential_frozen`, an alarm and a forced rotation.
  - Wrong-password owner checks tried offline are all replayed, each counting toward the
    ten-failure lock.
- **Fix (M):** when a request gives up (transport error, cancellation, no response), remove its
  outbox entry. Keep outbox persistence for fire-and-forget protocol messages only. Add a client id
  to creating writes (N11).

### N3. Enrolment is not resumable: a network failure after the enrol request turns "Try again" into "Your account already has a vault"

- **Status:** V on the client; the 24 h rule per spec (§11.3).
- **Evidence**
  - `enroll` always starts with `s.alt.enroll(…)` and has no "already enrolling" check
    (`data/vault/VaultManager.kt:654-664`).
  - Each attempt overwrites the enrolment nonce (`vault/VaultDevice.kt:470`), so a `vault.enrolled`
    from the first attempt fails its check (`:995-1009`) and is dropped.
  - The retry's `vault_exists` goes to the "vault elsewhere" screen ("Another phone is that app",
    `onboarding/OnboardingViewModel.kt:408-410`). "Use another code" then drops the device keys
    (`VaultManager.kt:619-625`), orphaning the provisional vault.
  - The enrolment job runs in `viewModelScope` (`OnboardingViewModel.kt:387`), so R1 cancels it on a
    rotation.
- **Triggers:** one dropped poll request; the relay unreachable for 90 s during `awaitEnrolled`;
  the app killed or rotated between the request and `vault.enrolled`. A cold vault host (about
  80 s) makes the window long.
- **Effect:** a first-time member is told another phone holds their vault. Unless the first
  `vault.enrolled` had already been accepted, they wait 24 h and need a new setup code.
- **Fix (M):** persist "enrolment outstanding (request id, nonce)"; on retry or restart wait for
  `vault.enrolled` and re-poll the same slot instead of posting again; on `vault_exists` with an
  outstanding enrolment of this install, wait and refresh; run the enrolment in the app scope.

### E1. A registered recovery cannot be left: the new phone is stuck on "Enter your vault PIN"

- **Status:** V.
- **Evidence**
  - Back is enabled only on INTRO, LOADING, SCAN and TYPE (`onboarding/MoveScreens.kt:169-172`;
    `RecoverViewModel.back()` at `onboarding/RecoverViewModel.kt:244-254`). The PIN and password
    steps have no back and no secondary action.
  - Every start returns `Setup(RECOVERING)` while `d.recovering` (`data/vault/VaultManager.kt:544`).
  - `abandonRecovery()` has one caller, the no-backup outcome (`RecoverViewModel.kt:505`).
- **Effect:** when the recovery is cancelled or voided (including the owner cancelling a hostile
  recovery by unlocking), when the register request never reached the enclave, or when the member
  forgot the PIN, the phone shows the PIN step at every launch. It cannot scan a new recovery
  code, redeem a setup code or start a transfer until the app's data is cleared in Android
  settings. The same holds for "Your vault has no credential … Contact VettID support."
- **Fix (S):** a confirmed "Start over" on the PIN and PASSWORD steps that calls
  `abandonRecovery()`; make it the primary action after `recover_pin_cancelled`.

### R1. The root navigation rebuilds the current phase on every activity recreation and every app-lock unlock

- **Status:** V/S. The code is verified; check it on a phone first (two minutes, checklist item 1).
- **Evidence:** `app/ui/VettIdApp.kt:102-108`:
  `LaunchedEffect(target, locked) { if (locked) return@LaunchedEffect; nav.navigate(target) { popUpTo(0) { inclusive = true }; launchSingleTop = true } }`.
  The effect runs again in every new composition (the manifest declares no `configChanges`:
  rotation, fold and unfold, dark/light, font scale, locale, multi-window resize) and each time
  `locked` turns false. `popUpTo(0)` empties the back stack before `launchSingleTop` is evaluated,
  so the existing entry is destroyed with its ViewModels and a new one is made. This contradicts
  the comment at `app/ui/AppLockGate.kt:23-25` ("ViewModels live on in their back stack entries").
- **Effect:** rotating, unfolding the Pixel Fold or returning through the app lock sends the member
  back to Messages and drops item and rule drafts, the message draft, a history export and a
  transfer-out. In onboarding the typed PIN and password are lost and a running enrolment is
  cancelled (N3). In transfer-in the wait for `device.paired` is cancelled (N1). In recovery the
  48 typed characters are lost.
- **Fix (S):** navigate only when the top destination is not already `target`. Add a Robolectric
  test that recreates the activity on each phase.

### S1. Vault content is visible in Recents, screenshots and screen recordings, and the app lock does not cover it

- **Status:** V that nothing protects it; what the Recents card shows is S.
- **Evidence:** the only `FLAG_SECURE` is the typed recovery code (`onboarding/RecoverTypeScreen.kt:59`
  through `ui/SecureWindow.kt:30`). There is no `setRecentsScreenshotEnabled` and no cover on pause.
  The app lock records a time in `onBackground` and locks only in `onForeground`
  (`data/lock/AppLock.kt:288-298`), also with the timeout "Immediately". Revealed item values are
  dropped at `ON_STOP` (`items/ItemDetailScreen.kt:89`), after the frame Android snapshots.
- **Effect:** with the app lock on, the Recents card still shows the last screen: a revealed
  password, a critical item, a conversation, a PIN in "show" mode. A screen recorder or cast the
  member allowed captures the same. Precondition: the phone unlocked in someone else's hands, or
  an approved capture app; that is what the app lock is for.
- **Fix (S–M):** `FLAG_SECURE` on the activity window in release-like builds (the plan, §4, left
  this as an open point for the owner: decide whether a "Allow screenshots" setting is wanted);
  set the secure policy explicitly on dialogs and sheets that take a password
  (`credential/CredentialScreens.kt:386`). The table of every screen that shows or accepts a
  secret is in [Appendix B](#appendix-b-screens-that-show-or-accept-a-secret).

### S2. Free-text values of secret and critical items are typed with autocorrect and keyboard learning on

- **Status:** V.
- **Evidence:** `items/ItemEditScreen.kt:747` `val free = f.kind == FieldKinds.TEXT || multi`;
  `:766` `autoCorrectEnabled = free`; `:768` sentence capitalisation. The critical "recovery
  phrase" template's words are a `MULTILINE` field (`items/ItemTemplates.kt:218-221`), "recovery
  codes" likewise (`:229-230`). The notes field has no `KeyboardOptions` (`ItemEditScreen.kt:296-310`).
  A TOTP seed is typed on an ordinary ASCII keyboard (`:735`). No field anywhere sets the
  no-personalised-learning flag.
- **Effect:** wallet recovery words, recovery codes, card numbers, secure notes and TOTP seeds go
  through the keyboard's suggestion and learning pipeline, and next-word prediction can replay
  them later. No malicious app is needed, only a keyboard that learns (Gboard's default on the
  Pixel 7).
- **Fix (S):** for items whose protection is secret or critical, and always for `otp`: the
  password keyboard class with visible text (as the typed recovery code already does),
  `autoCorrectEnabled = false`, and `IME_FLAG_NO_PERSONALIZED_LEARNING` through
  `PlatformImeOptions`; the same for notes.

---

## 1. Accessibility

**What the plan requires.** CLAUDE.md: "every icon-only control has a content description; touch
targets ≥ 48dp"; `ContrastTest` guards WCAG AA. ANDROID-PLAN §4 for the typed recovery code: the
count and every refusal are announced (a live region), a refused character names its group, the
layout holds at the largest font scale, the entry survives rotation.

**What the code does.** The basics are in place across all modules: every icon-only control is
labelled, custom rows carry roles, no clickable is under 48dp, every screen scrolls, all text is
in sp, every secret uses one standard field (`SecretField`) with a labelled show/hide toggle, and
there is no custom PIN pad to get wrong. The gaps are of three kinds: results and step changes are
almost never announced, a handful of fixed heights and line limits break at 200%, and the theme's
`outline` colour is too faint for control boundaries.

### Public

| ID | Finding | Evidence | Effect | Fix | Status |
|---|---|---|---|---|---|
| A1 | The result of an attempt is never announced: wrong PIN, wrong password, checks left, backoff, failure cards. | `SecretField`'s error text has no live region (`ui/Forms.kt:103`); `NoticeCard` has none (`ui/Forms.kt:305-340`). Silent at `onboarding/UnlockScreen.kt:111-118,163,181-199`; `onboarding/OwnerCheckScreen.kt:138-172`; `onboarding/MoveScreens.kt:122-131`; `onboarding/OnboardingScreens.kt:139-144`; `credential/CredentialScreens.kt:398,489-493`; `history/HistoryExportScreen.kt:312-329`; `items/CredentialPassword.kt:109-125`; `approvals/ApprovalsScreen.kt:389-396`; `settings/TransferOutScreen.kt:146-166,322-331`; `messages/MessagesScreen.kt:301-309`. The ViewModels also clear the entry on failure (`onboarding/UnlockViewModel.kt:250`, `onboarding/OwnerCheckViewModel.kt:152`). | After "Unlock" or "Confirm" a TalkBack user hears nothing and finds an empty field. In the owner check each silent failure spends one of ten checks before the vault locks. | S–M: polite live region on the field's error text and a `live` flag on `NoticeCard`; never on a ticking countdown. | V |
| A2 | On the transfer-in compare step a polite live region changes every second for ten minutes. | `onboarding/MoveScreens.kt:113` (live `Waiting` row), used at `:496` with "Waiting for your old phone (%1$s left)"; `onboarding/TransferInViewModel.kt:200-208` ticks each 1000 ms. | TalkBack is likely to re-announce the wait every second on the screen where six digits must be heard and compared. | S: static text in the live region, the time in a separate text; announce whole minutes and the last 30 s only. | V/S |
| A3 | Step changes inside a flow are not announced; no `paneTitle` anywhere. | Flows swap a whole `FormScaffold` per step: `onboarding/OnboardingScreens.kt:121-135`, `onboarding/MoveScreens.kt:174-240,429-535`, `onboarding/ReleaseUpdateScreen.kt:185-285`, `settings/TransferOutScreen.kt:71-196`, `connections/ConnectFlowScreens.kt:97-123,474-580`, `history/HistoryExportScreen.kt:133-188`. The title has `heading()` only (`ui/Forms.kt:213-219`). Several swaps are triggered by the other phone. | A TalkBack user on "Waiting for your new phone" may be told nothing when the safety code appears while the 10-minute window runs; focus can sit on a removed node. | S: `paneTitle = title` on the `FormScaffold` root. | S |
| A4 | The backoff countdown is hidden whenever "wrong PIN" is shown (all users). | `ui/Forms.kt:103` `supportingText = (error ?: supporting)`; callers pass both: `onboarding/UnlockScreen.kt:163-164`, `onboarding/MoveScreens.kt:203-208,377-378`, `onboarding/ReleaseUpdateScreen.kt:306-307`. `BadPin` sets the message and starts the wait (`onboarding/UnlockViewModel.kt:250-251`); the message clears only in `setPin` (`:206`), and the field is disabled while waiting (`:97-101`). | For the whole wait the member sees "That PIN is not correct." under a disabled field and a disabled button, with no "Try again in m:ss". It looks frozen. | S: show both lines, or prefer the wait while it runs. | V |
| A5 | The keyboard's "Next" does nothing on any secret field. | `ui/Forms.kt:110` `keyboardActions = KeyboardActions(onAny = { onImeAction() })` with the default `{}` (`:92`) replaces the default focus move. Eight fields use `ImeAction.Next`: `onboarding/UnlockScreen.kt:165-166`, `onboarding/OwnerCheckScreen.kt:139`, `onboarding/OnboardingScreens.kt:424`, `credential/CredentialScreens.kt:519,526`, `credential/NewCredential.kt:159,161,166`. | PIN → password and password → confirm: Next (or Enter on a hardware keyboard) is a dead key. | S: move focus down when the action is Next. | V/S |
| A6 | Enrolment and release-update progress is silent. | `StepList` rows are `clearAndSetSemantics { contentDescription = "$label: $stateLabel" }` with no live region (`ui/Forms.kt:444-448`); used at `onboarding/OnboardingScreens.kt:566`, `onboarding/ReleaseUpdateScreen.kt:200`. | A TalkBack user hears nothing while the vault is created (80 s or more on a cold host), and nothing when it fails. | S: one polite live text for the current step. | V |
| A7 | The one-time code is read as two numbers, has no copy action, and the time left is "12 s". | `items/ItemDetailScreen.kt:371-403`: `chunked(3).joinToString(" ")` (`:390`), no content description; `items_otp_left` is "%1$d s". The copy button copies the seed (S3). | A TalkBack or switch user must memorise the code within its 30 s. | S: digits spaced in the description (as `SafetyCode` does), "N seconds left", a "Copy code" button. | V |
| A8 | Security values are cut at two lines and cannot be selected. | `ui/Settings.kt:203-204,217-218` (`maxLines = 2` + ellipsis). Affected: boot key fingerprint and release fingerprint (`settings/SettingsDetailScreens.kt:381-397`), vault id (`:169-173`), email and membership dates in the account sheet (`app/ui/AccountSheet.kt:161,197-208`). | At large font (possibly at 100% for a 64-hex value) the fingerprint a GrapheneOS member would compare ends in "…". | S: no line limit on info rows; monospace and selectable for fingerprints. | V |
| A9 | Unlabelled switch in the sharing-rule editor. | `items/RuleEditScreen.kt:404-413`: label and note are plain texts next to a separate `Switch`. | TalkBack says "Switch, off" with no name. | S: `Row.toggleable(role = Role.Switch)` as `SettingsSwitchRow` does. | V |
| A10 | Saving an item with invalid fields gives no feedback where the member is. | `items/ItemsViewModels.kt:683` only sets `showErrors`; Save is in the top bar (`items/ItemEditScreen.kt:219-227`); errors are field texts and a card at the bottom of a long scroll (`:324-332`). | "Save item" appears to do nothing, also for sighted members at 200%. | M: scroll to and focus the first invalid field; announce the count. | V/S |
| A11 | The transfer-out window is 10 minutes, cannot be extended, and its time left is never announced. | `settings/TransferOutViewModel.kt:289` (`WINDOW`), ticker `:152-166`; plain text at `settings/TransferOutScreen.kt:243,286-293`. Inside it: compare, read the signer notice, PIN, password, confirm; a PIN backoff runs inside the same window. | A slow reader can run out; restart needs the new phone to rescan. | S–M: announce at 2 min and 30 s; consider a longer approve window after the code is confirmed. | V |
| A12 | A new incoming message is not announced in an open conversation; the send button loses its label while sending. | `messages/MessagesScreen.kt:326-353` (no live region, only auto-scroll `:274-277`); `:395-399` swaps the labelled icon for a bare progress ring. | TalkBack users miss replies; the busy button has no name. | S. | V |
| A13 | Masked values are read as bullets; label and value are separate stops. | `items_masked_value` "••••••••" shown at `items/ItemDetailScreen.kt:331` with no description; `FieldRow` (`:325-346`) does not merge. | "bullet" eight times, two swipes per field. | S: "Hidden" as the description; merge label and value. | V |
| A14 | Dialog and sheet bodies do not scroll. | Fixed columns in `AlertDialog`s: `settings/SettingsScreen.kt:605,657`; `items/ItemDetailScreen.kt:431`; `items/TagsScreen.kt:472`; `items/ItemEditScreen.kt:871,1022`; `items/SharingScreens.kt:495`; `items/RuleEditScreen.kt:636-646` (time picker); `credential/CredentialScreens.kt:386-415,446-486` (the backup-off acknowledgement checkbox); `ui/ConfirmDialog.kt:35`. Sheets: `notifications/NotificationsScreen.kt:634`, `connections/ConnectionsScreen.kt:447`. | At 200% or with the keyboard up, a checkbox or field can be clipped while the confirm button stays disabled. | S each: `verticalScroll`. | V/S |
| A15 | Fixed heights and non-wrapping rows that break at 200%. | Drawer rows `.height(56.dp)` (`ui/Drawer.kt:110`); top bar `.height(72.dp)` with a one-line title (`ui/TopBars.kt:65,76-77`) and search (`ui/TopBarSearch.kt:89`); the safety code is a non-wrapping row at 40sp (`ui/Conversation.kt:106-120`), used on both transfer screens, the connect flow and approvals. | Drawer labels clip, titles read "Conn…", the second group of the safety code is squeezed or cut on a 360dp-wide screen. | S: `heightIn(min = …)`; wrap or scale the code by group. | V/S |
| A16 | Typed recovery code: one refusal path is not announced, and the 4×3 grid goes ragged at large fonts. | Announced: the typed problem (`onboarding/RecoverTypeScreen.kt:110`, assertive) and the code refusal (`onboarding/MoveScreens.kt:333`). Not announced: `ClaimFailure` (`MoveScreens.kt:281-288`), which covers network failures and the 429 wait. A hard `"\n"` after every fourth group (`RecoverTypeScreen.kt:145`) soft-wraps into 3 + 1 groups from about 150% on a 412dp screen. A second refused character in the same group changes no state, so it is not re-announced (`onboarding/RecoverViewModel.kt:282-289`). | The plan's "every refusal is announced" holds only in part; the grid stops matching the account site. | S–M. | V; wrap S |
| A17 | The app-lock layer swallows every key event. | `app/ui/AppLockGate.kt:52-60`: `.focusable().onKeyEvent { true }`. | With a hardware keyboard the "Unlock" button cannot be reached after the automatic prompt is cancelled. TalkBack and Switch Access are not affected. This layer is the keystroke-leak fix: keep printable keys blocked. | S: let Tab, D-pad, Enter and Space through. | V/S |
| A18 | The theme's `outline` is far below 3:1 on every surface, and it is what Material draws control boundaries with. | Computed, see [Contrast](#contrast). `outline` on `background`: 1.81 (dark), 1.41 (light). An unchecked `Switch` draws its thumb in `outline` on a `surfaceContainerHighest` track: both are Navy600 (dark) or Mist300 (light), 1.00:1. No `SwitchDefaults` or text-field colour override exists; 26 `OutlinedTextField`s, 35 `SecretField`s and every settings switch (`ui/Settings.kt:137`) use the defaults. | Unfocused text-field borders are barely visible; the thumb of a switch that is off cannot be seen (the Material token names are from memory: confirm on a phone). | S: give `outline` a 3:1 colour (for example InkBody in dark, InkMuteLight in light) and keep the faint one as `outlineVariant`; extend `ContrastTest`. | C |

### Polish

| ID | Finding | Evidence | Fix | Status |
|---|---|---|---|---|
| A19 | Two text pairs slightly under 4.5:1. | The time under an incoming bubble: `onSurfaceVariant` at 80% on `surfaceContainerHigh` = 4.03 (dark), 4.25 (light) (`ui/Conversation.kt:75,124`). `TagLabel` on a grouped background in light: 4.15 (today it is only used on cards, 4.52). | S: drop the alpha. | C |
| A20 | Repeated, context-free button labels. | "Revoke" (`items/SharingScreens.kt:320`), "Change" (`:257`), "Fetch"/"Give up" (`:434-445`), "Remove" (`settings/ProfileScreens.kt:255`), delete-rule icon (`connections/ConnectionSharingCards.kt:353-357`), cancel-invitation icon (`connections/ConnectionsScreen.kt:437-439`). | S: name the item in the description. | V |
| A21 | Section titles without `heading()` (15) and radio rows outside a `selectableGroup` (5). | `items/ItemEditScreen.kt:281,424,451`; `items/RuleEditScreen.kt:215,322,337,369,439,495`; `history/HistoryExportScreen.kt:255`; `settings/ProfileScreens.kt:154,218,237,507`; `connections/ConnectionDetailScreen.kt:261`. Groups: `items/ItemDetailScreen.kt:431`, `items/ItemEditScreen.kt:425`, `items/RuleEditScreen.kt:323-328`, `history/HistoryExportScreen.kt:256-257`, `history/HistoryScreen.kt:512`. | S. | V |
| A22 | Fields with a weak name. | Custom dropdowns announced as edit boxes (`items/ItemEditScreen.kt:367-378,1054-1065`); search and compose fields labelled only by a placeholder (`history/HistoryScreen.kt:350-371`, `messages/MessagesScreen.kt:375-384`); the month picker's year change is silent (`items/ItemEditScreen.kt:873-893`); the address error is a red text with no `isError` (`:623-630`). | S each. | V |
| A23 | Confirmations that are not announced or too short. | "Copied" swaps the button text with no live region (`settings/TransferOutScreen.kt:266-280`, `connections/ConnectFlowScreens.kt:251-265`); item copy has no in-app confirmation (`items/ItemDetailScreen.kt:360`); the Undo snackbar after archiving is `Short` (`notifications/NotificationsScreen.kt:218`). | S. | V |
| A24 | State shown by colour or weight only. | The release-update row's urgency is the icon tint (`settings/SettingsScreen.kt:437-441`); an approval that needs a decision is bold only (`approvals/ApprovalsScreen.kt:208`); `NoticeCard`'s severity is icon and colour with a null description (`ui/Forms.kt:313-327`); the expandable signer line has no expanded state (`settings/SettingsDetailScreens.kt:160-166`). | S: a word in the description or `stateDescription`. | V |
| A25 | Roles and labels on custom clickables. | The FAB is a `Surface(onClick)` without `Role.Button` (`ui/FloatingControls.kt:84-91`); list rows have no role or click label (`ui/ListRow.kt:63`); drawer destinations are `Role.Tab` (`ui/Drawer.kt:112`); the message bubble exposes an empty click and an unnamed long-press, the only way to delete (`ui/Conversation.kt`, `messages/MessagesScreen.kt:349`); a no-op button in the account sheet (`app/ui/AccountSheet.kt:95-101`); busy buttons keep their label with no state (`ui/Forms.kt:276-280`). | S. | V |
| A26 | Truncation at any font size. | Notification rows: title 2 lines, second line 1, and the detail sheet never shows the second line (`notifications/NotificationsScreen.kt:512,520,633-667`); list rows are one line (`ui/ListRow.kt`), which cuts item names and approval summaries; skipped releases' notes are one line (`onboarding/WhatsNew.kt:109-110`). | S: add the text to the sheet; two lines in rows. | V |
| A27 | Smaller layout risks at 200%. | The urgent banner's live region sits on an unmerged row and may never be spoken (`ui/Forms.kt:413`); tag pills can be narrower than 48dp (`items/RuleEditScreen.kt:264-268`); colour swatches are a fixed 256dp row (`items/TagsScreen.kt:415-435`); the avatar letter is in sp inside a dp box (`ui/InitialTile.kt:59,78`); up to five banners stack without a bound (`app/ui/AppShell.kt:229-271`); the profile photo preview has no description (`settings/ProfileScreens.kt:510`). | S each. | V/S |

### Contrast

Ratios computed with the WCAG 2 formula from `core/ui/.../theme/Color.kt`. Bold marks a value
under the minimum for its use (4.5 for text, 3.0 for large text, icons and control boundaries).

| Foreground on → | background | surfaceContainerLow | surfaceContainer / card (dark) | surfaceContainerHigh | grouped |
|---|---|---|---|---|---|
| **Dark** onSurface | 16.88 | 15.53 | 13.47 | 11.58 | 15.53 |
| onSurfaceVariant | 7.86 | 7.23 | 6.27 | 5.39 | 7.23 |
| primary (gold) | 11.10 | 10.21 | 8.85 | 7.61 | 10.21 |
| error | 6.51 | 5.98 | 5.19 | **4.46** | 5.98 |
| success | 9.84 | 9.05 | 7.85 | 6.75 | 9.05 |
| warning | 9.87 | 9.08 | 7.87 | 6.77 | 9.08 |
| outline | **1.81** | **1.67** | **1.45** | **1.24** | **1.67** |
| **Light** onSurface | 18.93 | 17.70 | 17.23 | 15.25 | 17.23 |
| onSurfaceVariant | 8.46 | 7.91 | 7.70 | 6.82 | 7.70 |
| primary (gold ink) | 5.63 | 5.27 | 5.13 | 4.54 | 5.13 |
| error | 6.54 | 6.11 | 5.95 | 5.27 | 5.95 |
| success | 5.35 | 5.00 | 4.87 | **4.31** | 4.87 |
| warning | 5.93 | 5.54 | 5.40 | 4.77 | 5.40 |
| outline | **1.41** | **1.31** | **1.28** | **1.13** | **1.28** |

In light, the card is white, so its column equals `background`.

| Other pairs | Dark | Light |
|---|---|---|
| onPrimaryContainer on primaryContainer (gold button, own bubble) | 11.63 | 11.63 |
| onPrimary on primary | 11.63 | 5.63 |
| onError on error (destructive button) | 6.82 | 6.54 |
| Letter on connection tile / favourite tile / own avatar | 10.63 / 5.18 / 11.63 | 11.37 / 5.18 / 11.63 |
| Time under an own bubble (80% alpha) | 7.54 | 7.54 |
| Time under an incoming bubble (80% alpha) | **4.03** | **4.25** |
| `TagLabel` text on its tint, over card / grouped | 6.23 / 7.22 | 4.52 / **4.15** |
| Drawer label, unselected (87% alpha) | 11.98 | 12.59 |
| Disabled text (Material's 38% alpha), exempt from WCAG | 3.41 | 2.48 |
| Switch off: thumb (`outline`) on track (`surfaceContainerHighest`) | **1.00** | **1.00** |
| Gold fill against the screen (button and FAB edge) | 11.10 | 1.63 (the label inside is 11.63) |

The two failing cells in the first table (`error` and light `success` on `surfaceContainerHigh`)
are pairs no screen uses today.

**What `ContrastTest` covers:** `onSurface`, `onSurfaceVariant` and `primary` on `background`,
`surfaceContainerLow` and `surfaceContainer`; `onSurface` and `onSurfaceVariant` on the card;
`onSurfaceVariant` on the grouped background; `onSurface` and `primary` on `surfaceContainerHigh`;
the gold container pair; the three tile pairs; the favourite tile against the screen; `error` on
`background`. `TagColorsTest` and `CategoryColorsTest` cover the tag and History palettes.

**What it does not cover:** `outline` and every control boundary; `success` and `warning`;
`error` on cards and containers; `onPrimary`/`primary` and `onError`/`error`; any colour used with
an alpha (bubble times, `TagLabel`); the switch and text-field defaults.

### Checked and sound

- **Content descriptions:** all 48 `contentDescription = null` sites are decorative or sit inside a
  labelled or merged parent. QR codes have a description, `Role.Image`, and the same link as text
  with Copy and Share; every scan step has a type-or-paste alternative.
- **Secret fields:** one component, a standard text field with the password transformation and the
  platform's password semantics; the show/hide button is named "Show <label>" / "Hide <label>";
  excluded from autofill. No custom PIN dots, so nothing is silent or leaks digits.
- **Safety code:** read digit by digit on all uses (`ui/Conversation.kt:105-107`).
- **Typed recovery code:** the count is a live region, a refused character is announced with its
  group, groups are spoken character by character, the entry lives in the ViewModel (but see R1
  for rotation), and the screen sets `FLAG_SECURE`.
- **Touch targets:** no explicit clickable under 48dp; `minimumInteractiveComponentSize` is never
  disabled.
- **Swipe and long-press alternatives:** notification rows expose archive, read, move back and
  delete as custom actions; field reorder is a menu, not a drag; the photo crop has nudge actions.
- **Camera capture:** shutter, lens switch, zoom, reset, retake and use are labelled.
- **Reduced motion:** there is no custom animation in the app (no infinite transitions); only
  Material's own, which follow the system setting.
- **Timeouts:** apart from the two transfers (A2, A11) nothing times out under the reader:
  revealed values have no auto-hide timer, the recovery screens have none, invitations last as
  long as the member chose.
- **Colour alone:** tag swatches are named and show a check; History categories have an icon and a
  label; every red error line also carries words. The exceptions are in A24.

---

## 2. Offline and poor network

**What the plan requires.** Only the phase line ("offline behaviour"). The spec expects the phone
to come back and collect what waits in its mailbox, and to show a backoff's countdown (§10.1).

**What the code does.** There is no local cache of lists: connections, messages, items, the feed
and history live in memory and are cleared on lock, so nothing stale is shown as fresh. The
"outbox" is a transport retry buffer, not a queue the member sees: nothing is shown as pending.
One OkHttp client serves everything (connect and write 10 s, read 70 s, no call timeout). With no
network a relay action fails after about 29 s and a member-API action after about 9 s, both with
"VettID could not reach the service. Check your connection and try again." Collection is a 25 s
long poll; the websocket path exists and is unused.

### Operations under bad conditions

| Operation | No network | Relay down, API up | API down, relay up | Cold vault (~80 s) | Killed mid-way |
|---|---|---|---|---|---|
| App start | "VettID cannot connect" with Retry; re-read on foreground | Opens if the vault is unlocked; every list then errors | "Cannot connect" although the vault is reachable | – | – |
| Setup code | Error after ~9 s | Works | Error | – | A lost answer burns the code |
| Enrol | Error | Enrol request succeeds, the wait times out at 90 s; retry → `vault_exists` (N3) | Error | Waits up to 10 min with no "starting" text | Restart → second enrol → `vault_exists` (N3) |
| Create credential | Error | Error | Works | 3 × 30 s covers it | Resumes |
| Unlock | Error, "Try again" | Unlocks; lists fail | Error | Bare spinner, no deadline (N8) | Resumes |
| Lock | Fails silently (N10) | Falls back to the API after 10 s; the stale lock stays queued (N2) | Works | Falls back | – |
| Owner check | Error; request stays queued (N2) | Error | Works | 90 s | N2 |
| Recovery | Error | Works (API only) until the handshake | Error | Waits | Lands on the PIN step either way (E1) |
| Transfer in | Error; the claim is never retried | Fails | Works | The vault is warm | Not resumable (N1) |
| Transfer out | Error | Error | Works | 90 s | The vault completes it |
| Release update | Failed step | Works through the API | Failed | Handled, with a "starting" line | The next unlock follows the move |
| Send a message | ~29 s spinner, error, draft kept; sent later anyway (N2) | Same | Works | 90 s | Re-flushed with the same id |
| Items, sharing, approvals | Error card with Retry; writes as N2 | Same | Works | 90 s | Update replays answer `conflict`; a create has no id (N11) |
| History, export | Error with Retry | Same | Works | 90 s | Read-only |
| Feed collection | Backs off | Same | Works | – | Ack only after persist |

### Timeouts and retries

| What | Value | Bounded | Backoff |
|---|---|---|---|
| OkHttp connect / write / read | 10 s / 10 s / 70 s; no call timeout (`app/di/AppModule.kt:92,124-128`) | yes | – |
| `TransportRetry` | 2 retries; waits up to 8 s for a network; 0.25 s, 1 s (`relay/TransportRetry.kt:128-130`) | yes | fixed |
| Relay request | 3 attempts (`relay/RelayClient.kt:380`) | yes | 250 ms × 2ⁿ, jitter, honours `retry_after` |
| Mailbox collector | forever, by design (`relay/MailboxCollector.kt:50-71`) | – | 250 ms × 2ⁿ, cap 30 s, jitter |
| Collector supervisor | 1, 5, 15, 60, 300 s | repeats the last | stepped |
| Vault request | 30 s × 3, same id (`vault/VaultDevice.kt:782-798`) | 90 s | fixed |
| Enrolled / event wait | 90 s | yes | – |
| Member-API slot poll | every 600 ms, **no client deadline** (`altchan/MemberApiClient.kt:323-329`) | no | fixed |
| "Release starting" wait | 2 s steps, 10 min in all | yes | fixed |
| Lock through the relay | 10 s, then the API | yes | – |
| Transfer: answer wait / window | 60 s / 10 min | yes | – |
| On-phone service reconnect | 1 s × 2ⁿ, full jitter, cap 5 min; status read hourly | loop | exponential |
| Push registration retry | 60 s × 2ⁿ, cap 1 h | loop | exponential |

Every screen action runs in `viewModelScope` and cancels its HTTP call when the screen is left; the
release update and the wipe run in the app scope on purpose.

N7 is not used: the recovery registration that cannot be resumed is part of E1.

### Public

| ID | Finding | Evidence | Effect | Fix | Status |
|---|---|---|---|---|---|
| N4 | Member-API GETs are re-sent with the same signature after a dropped connection. | `relay/TransportRetry.kt:133-134,144` retries a GET that already went out; only relay requests carry a `RequestSigner` (`:39-42`); `altchan/MemberApiClient.kt:144` signs once. Per spec a nonce seen twice within 600 s answers `401`. `401` maps to UNAUTHORIZED, which at unlock sets `notRecognised` and offers "Erase VettID from this phone" (`onboarding/UnlockViewModel.kt:278-279`). | On a dropping connection the member can be told "this phone was replaced" and offered an erase. A second try works, but the text invites a destructive choice. | S: sign afresh per attempt (as the relay client does), and do not offer the erase on a single 401. | V; trigger S |
| N5 | A wrong phone clock gives misleading messages and no "check the date and time". | Over 5 min off: every member-API call answers 401 → the "replaced" text. 90 s to 5 min: the relay's `timestamp_stale` maps to "Check your connection" (`data/vault/Guard.kt:52-53`). Phone behind: vault messages fail the time check and are dropped (`vault/VaultDevice.kt:914`) → "did not answer in time". A stale enclave attestation → "This phone could not be verified … locked, verified boot" (E6). A phone 10 min ahead refuses transfer and invitation codes as expired locally. | The member is sent after the wrong cause, in one case toward erasing the phone. | M: take the skew from any HTTPS `Date` header and show a clock hint when it is large; never show the "replaced" text in that state. | V |
| N6 | An unauthenticated status answer without a vault makes the app destroy its keys. | `if (st == null) { dropSession(forget = true); saveLocal(LocalAccount()); … SignedOut }` (`data/vault/VaultManager.kt:559-563`); `st` is null for a 200 with an empty body or a body without `vault` (`altchan/MemberApiClient.kt:149,215-217`). The replaced-phone wipe needs an authenticated proof; this path does not. | One bad 200 (a backend or edge anomaly) locks the member out until a recovery. | S: a blocking screen with Retry and an explicit "start over"; never forget on an empty body. | V; trigger S |
| N8 | The unlock's slot poll has no deadline, cannot be cancelled, and a cold start is not explained. | `while (true)` in `poll` (`altchan/MemberApiClient.kt:323-329`); the unlock screen has no cancel; `onStarting` is wired only in the update flow (`data/vault/VaultManager.kt:713-719`). | With a cold host the member watches a bare spinner for 80 s or more; in the worst case it ends in "Nothing was found." | S: a 3-minute deadline; "Starting your vault… this can take a minute or two" after 10 s; Cancel. | V |
| N9 | Any failed phase re-read turns an open app into the full-screen "VettID cannot connect" and clears its lists. | `refresh()` publishes `Unreachable` on any failure (`data/vault/VaultManager.kt:522-530`); it runs hourly in the on-phone service (`notify/ConnectionKeeper.kt:65-69`) and after a failed lock; lists are cleared for every phase but Unlocked (`:284-289`). | A member-API blip takes the member out of an item being edited or a conversation. | S: keep the phase on a network or 5xx failure; a banner at most. | V |
| N10 | Locking while offline fails silently and the vault stays open. | `lockVault()` swallows the failure (`app/ui/RootViewModel.kt:51-59`; also `onboarding/OwnerCheckViewModel.kt:157-166`). | The member believes the vault is locked. | S: say so, offer the app lock, drop the queued lock. | V |
| N11 | Creating writes carry no idempotency key. | `message.send {connection_id, text}` (`vault/VaultApi.kt:643-646`); a new `item.put` (`:265-274`); `connection.invite.create` (`:526-528`). | A response lost for over 90 s plus a retry makes a second message or item (also without N2). | M, needs the spec: a `client_id`, or reuse the inner id of the failed send. | V |
| N12 | The device mutex is held across network I/O. | `sendLocked` deposits inside `lock.withLock` (`vault/VaultDevice.kt:705,787`); `handle()` takes the same lock (`:853`). | On a slow link one deposit (up to 29 s, or 70 s) stops all incoming messages; about ten list reads start at once after an unlock and time out together. | M: seal and persist under the lock, deposit outside it. | V |

### Polish

| ID | Finding | Evidence | Fix | Status |
|---|---|---|---|---|
| N13 | A 2xx relay answer that is not the expected JSON ends the process. | `StrictJson.parseObject` (`relay/RelayClient.kt:240-247`) throws `CryptoException`, which the collector (`relay/MailboxCollector.kt:57-70`) and the outbox flush (`vault/VaultDevice.kt:771`) do not catch; `RelaySafetyNet` treats it as fatal. Needs a relay or edge anomaly. | S: rethrow as `IOException`. | V |
| N14 | No connectivity awareness in the UI. | `ConnectivityGate` feeds only the retry interceptor (`app/di/AppModule.kt:113-114`); no offline banner, no retry on reconnect, 29 s to fail when the phone knows it is offline. | S–M. | V |
| N15 | Failure kinds are coarse at the edges. | Every relay error is NETWORK (`data/vault/Guard.kt:52-53`), also `rate_limited` and `quota_exceeded`; a 502 with an HTML body is "Something went wrong"; `stale_credential` is "did not answer in time". | S. | V |
| N16 | A lost `hs.fin` burns a transfer code. | `vault/VaultDevice.kt:1028-1031`; the failure is swallowed and the message acked (`:870-876`). | S: retry the deposit. | V |
| N17 | A lost `credential.ack` reports a successful operation as failed. | `vault/Credential.kt:53-54,108`. The state is safe (the blob is stored first). | S: send the ack best effort. | V |
| N18 | Release-update progress is memory-only. | `data/vault/ReleaseUpdate.kt:252`. After a kill the "return to the previous release" choice is gone. | S. | S |
| N19 | Two identical 60 s timers race in transfer-in; a UTK is spent on every attempt that never left the phone. | `onboarding/TransferInViewModel.kt:157` and `data/vault/VaultManager.kt:1215`; `vault/VaultDevice.kt:1157-1168`. | S each. | V |
| N20 | The 70 s read timeout is shorter than an 80 s cold start. | `app/di/AppModule.kt:92`. Matters only if the member API holds one request open while the host starts. | Check on the first unlock after an idle night. | S |

### Checked and sound

- **Ack after persist:** a message is recorded, processed and saved before the relay ack; the
  state file is written to a temp file, synced and renamed.
- **New credential blob:** stored before `credential.ack` is sent; a death in between is covered
  by `stale_credential` plus `credential.get` (N2 is what breaks this).
- **The app's own retries** reuse the inner id, so they cannot duplicate a write; relay signatures
  are made afresh for every attempt.
- **Captive portal:** TLS failures and an HTML body from the member API both map to the network
  text; certificate mismatches are not retried.
- **No wipe from unauthenticated failures** on the unlock path; a paused service gets a countdown
  and no auto-retry; `release_starting` is waited for with a bound.
- **List screens with a proper failed state:** Connections, the Messages list, Items, History,
  Notifications.
- **Reconnect:** jittered and capped at every level; resets when a validated network appears.

---

## 3. Error states

**What the plan requires.** CLAUDE.md: failures are `VaultFailure(kind)` with member-facing text
from `FailureKind.messageRes()`. §3: an empty state is one illustration, a title and one line.

**What the code does.** 33 failure kinds, each with its own English sentence; most are specific and
say what to do. No exception message, URL, stack trace or id reaches a text on screen. Form
validation is at the field and blocks submit. The weak points are a few catch-alls that name the
wrong cause, failures swallowed where the member needs to know, and lists that say "nothing here"
when they could not be read. The table of every kind is in
[Appendix A](#appendix-a-failure-kinds).

### Public

| ID | Finding | Evidence | Effect | Fix | Status |
|---|---|---|---|---|---|
| E2 | Approvals shows "Nothing to approve" when its lists could not be read. | `data/social/SocialManager.kt:666-693` catches every list failure and publishes an empty list; `approvals/ApprovalsScreen.kt:142-162` shows the empty state; the list branch (`:163-185`) never shows `state.error`. | Offline or on a vault error, pending grant, critical-use and share decisions vanish and the badge follows. | S–M: rethrow when the lists fail; show the error card. | V |
| E3 | Other lists present "could not load" as "nothing here". | "You share nothing with %1$s by tag." / "%1$s can fetch nothing from your vault." after a failed load (`items/SharingScreens.kt:151-172,203-209`); "%1$s shares nothing with you." (`:371-385`); "No tags yet" (`items/TagsScreen.kt:315-332`); the new-message picker never renders its error (`messages/MessagesScreen.kt:417-423`); a conversation shows "Say hello" with an OK-only card (`:291-306`). | The sharing ones are false statements about what the member shares. | S each: a failed state with a retry. | V |
| E4 | "Cancel deletion" on the pending start-over banner fails silently. | `app/ui/PendingDeletion.kt:31-43`: `catch (_: VaultFailure) { // the banner stays }`; the result is ignored too. | The one control that stops the vault being deleted says nothing when it did not work. | S. | V |
| E5 | The QR scanners resubmit the same code on every frame after a failure. | `ui/QrScanner.kt:70` calls back per decoded frame; the ViewModels guard only on `busy` (`onboarding/OnboardingViewModel.kt:244-257`, `onboarding/RecoverViewModel.kt:260-271`, `onboarding/TransferInViewModel.kt:136-139`). | An expired setup QR left in view is redeemed until the API rate-limits, replacing the accurate text with "Too many attempts". In recovery, a scanned wrong code could count toward the five that void it. | S: ignore the last refused text until another is seen. | V/S |
| E6 | Every attestation exception reads "This phone could not be verified". | `data/vault/Guard.kt:40-41` maps all `AttestationException` kinds (enclave chain, PCR, manifest key and serial, stale) to one text about the phone's hardware. The unlock preflight case is known and being fixed; the same line serves enrolment, unlock, recovery and the update. | A member with a skewed clock or during a manifest problem is told their phone lacks hardware attestation. | S: split by kind (manifest → manifest text, stale → clock text, the rest → "could not verify the vault service"). | V; known, in progress for the preflight |
| E7 | Unlock and "cannot connect" have states with no way forward. | With `RELEASE_ENDED`, a release rollback or a state rollback the primary button is null and there is no other control (`onboarding/UnlockScreen.kt:65-74`). A preflight failure hides the PIN field, so a phone whose key was replaced never reaches the erase offer. The Unreachable screen is titled "VettID cannot connect" for every kind and its retry shows no progress (`app/ui/VettIdApp.kt:133-141`). | A transient rollback can only be retried by killing the app. | S–M. | V; the preflight 401 case S |
| E8 | On the Vault screens every unclassified failure reads "Your vault refused this item. Check its fields and try again." | `items/ItemsText.kt:116`, used for the list load (`items/ItemsScreen.kt:185-186`), detail, tags and sharing. | A Keystore error tells the member to check an item's fields on a screen with no item. | S. | V |
| E9 | Editing an item whose load failed shows an empty form titled "Not saved". | `items/ItemsViewModels.kt:480-488`; `items/ItemEditScreen.kt:203-246`. | The item looks empty; there is no retry. | S. | V |
| E10 | A vault answer the app cannot decode crashes the app. | `vaultGuard` has no branch for `SerializationException`, `IllegalArgumentException` or `IllegalStateException` (`data/vault/Guard.kt:25-60`); 33 decode sites into classes with required fields (`vault/VaultApi.kt:33`); ViewModels catch only `VaultFailure` (93 sites). | One unexpected entry from a newer vault release crashes a screen at every open. | S: map to a failure; decode lists element by element. | V; trigger S |
| E11 | The recovery and update flows flatten important unlock answers. | `data/vault/VaultManager.kt:854` (recovery pending → "Something went wrong"); `:856` (state rollback → the release-rollback text, wrong on both counts); `onboarding/RecoverViewModel.kt:437-440` (an update refused although the vault opened → the same generic text, and each retry is refused again). | A recovery someone else started is invisible during an update; a recovery can loop. | S. | V |

### Polish

| ID | Finding | Evidence | Fix | Status |
|---|---|---|---|---|
| E12 | Wire codes and raw states shown to members. | "The update was refused (%1$s)" (`onboarding/UnlockScreen.kt:184`); "Your vault did not move (%1$s)." (`onboarding/ReleaseUpdateScreen.kt:342`); "Release %1$d is %2$s" with `deprecated` (`UnlockScreen.kt:332`); raw recovery state (`settings/SettingsDetailScreens.kt:192,310`); "Verified"/"SelfSigned" (`data/vault/VaultManager.kt:1521-1524`). | S. | V |
| E13 | Backoff without the wait. | Change PIN, delete vault, change password, rotate, the unlock-window dialog and new credential drop `retryAfterSeconds` (`settings/SettingsViewModels.kt:258-260,324-326`; `credential/CredentialViewModels.kt:83,161,196,278`; `credential/NewCredential.kt:116`). | S: reuse the countdown. | V |
| E14 | "What you entered is kept" is false on forms that clear it. | `data_failure_owner_check_required` shown by approvals, change PIN, delete vault, new credential, transfer-out. | S. | V |
| E15 | Smaller wrong-cause mappings. | Any `bad_request` in export → "needs a newer vault release" (`history/HistoryExportViewModel.kt:304`); `ttl_not_allowed` → "does not support this yet"; member API 404 at unlock → "Nothing was found."; a malformed API answer → "Check your connection"; transfer-out failure while waiting → "expired" (`settings/TransferOutViewModel.kt:134-143`). | S. | V |
| E16 | Screens that go blank or cannot be acted on. | Connection detail when the fetch fails (`connections/ConnectionDetailScreen.kt:135-139`); History's sticky "does not match" banner whose retry never clears it (`history/HistoryScreen.kt:253-259`). | S. | V |
| E17 | A failed refresh is silent when cached rows exist. | Messages, Connections, Items show the failure only when the list is empty (`items/ItemsScreen.kt:182`). | S. | V |
| E18 | Erase: an incomplete wipe looks complete; a marker write failure leaves "Erasing…" spinning. | `data/vault/VaultManager.kt:1337`; `data/wipe/LocalWipe.kt:56-63`. | S. | S |
| E19 | Swallowed failures of smaller consequence. | A shared canary manifest that cannot be read: nothing happens (`app/MainActivity.kt:164-171`); the app-lock toast gives one cause for three failures (`:224-232`); "backup is off" warning hidden when status cannot be read (`settings/SettingsViewModels.kt:206`); the dry run of what a save will share (`items/ItemsViewModels.kt:444-451`); "Turn hold on" from the banner (`app/ui/OwnerCheckGate.kt:78`); opening system settings (`settings/NotificationSettings.kt:218`, `settings/SettingsScreen.kt:161`). | S each. | V |
| E20 | A failed stage read restarts a recovery at the intro. | `onboarding/RecoverViewModel.kt:216-220`. | S. | S |
| E21 | Stale copy on the last onboarding screen. | `feature/onboarding/src/main/res/values/strings.xml:96`: "…Connections arrive in the next update." | S. | V |
| E22 | Texts that cannot be acted on. | "You blocked the vault that sent this invitation. Unblock it first" (there is no block list); "Accept the current membership terms on the account site first" with no link. | S. | V |
| E23 | The unlabelled `REJECTED` and generic `NOT_FOUND` texts. | "Rejected on your phone." could surface as "Setup stopped: Rejected on your phone."; "Nothing was found." names no thing. | S. | V |
| E24 | `runCatching` in suspend code also catches cancellation (about 60 sites). | For example `data/social/SocialManager.kt:177-179`, `data/vault/VaultManager.kt:262,281`. None was found harmful: each is followed by a return or a state write. | Leave; prefer a helper that rethrows cancellation in new code. | V |

### Empty states

| List | Empty | Load failed | No match |
|---|---|---|---|
| Conversations | Yes, three variants | Card with retry, only when empty (E17) | Distinct |
| A conversation | "Say hello" | Not distinct (E3) | – |
| New-message picker | Yes | Not distinct (E3) | – |
| Connections | Yes | Card with retry | Distinct |
| Connection detail, sharing cards | Yes | Distinct ("could not be read just now") | – |
| Approvals | Yes | Swallowed (E2) | – |
| Items | Yes | Distinct, wrong body text (E8) | Distinct |
| Tags, sharing rules, shared with you | Yes | Not distinct (E3) | – |
| History | Yes | Distinct | Distinct |
| Notifications | Yes, three variants | Distinct | Distinct |
| Credential, vault status, recovery | Yes | Card with retry | – |

### Checked and sound

- Cancellation is rethrown by `vaultGuard` and by every explicit catch; timeouts become "did not
  answer in time".
- Unlock keeps wrong PIN, backoff, paused service, recovery pending, state rollback, signer
  refused and "not recognised" as separate states; an unreadable result never erases by itself.
- Onboarding progress marks the failing step and "Try again" resumes; `vault_exists` has its own
  screen with three ways on.
- The 27 named limits render with their bounds.
- The typed recovery code's problems are local, specific and name the group.
- An invalidated app-lock key turns the lock off with a notice; it cannot lock the member out.

---

## 4. Robustness

**What the plan requires.** CLAUDE.md: "one nav destination per phase, so leaving a phase drops
its secrets"; every user-visible string in `strings.xml`. §4: the typed recovery entry survives
rotation.

**What the code does.** No ViewModel writes to saved state; forms live in ViewModel state, and
after process death the phase is recomputed from disk and the vault. **No secret is in saved
state**: all 31 `rememberSaveable` sites, every `SavedStateHandle` use (reads of route arguments
only) and every route were checked; they hold booleans, enums and ids. The exceptions of note are
an invitation link in a route and two date pickers (R6, R18). One exported activity, `singleTask`,
no `configChanges`, no orientation lock, predictive back enabled, RTL supported.

### After process death

| Flow | The member lands on |
|---|---|
| Setup code typed or scanned | Welcome; input gone |
| After redeem | "Confirm account"; PIN and password retyped |
| Enrolment running | Password step if enrolled, else "Confirm account" (N3) |
| Unlock | Unlock, empty PIN |
| Recovery | Resumes at intro, PIN or password from stored state (E1) |
| Transfer in | Welcome (N1) |
| Transfer out | Messages; the open transfer expires in 10 minutes |
| Owner check | The gate again if due |
| Release update | Unlock; the next unlock follows a moved vault |
| Item or rule editor, message draft, name change | Messages; unsaved work lost (R4) |
| History export | History; see R8 |

No crash path on restore was found: there is no `!!` or `checkNotNull` on an in-memory
prerequisite.

### Public

| ID | Finding | Evidence | Effect | Fix | Status |
|---|---|---|---|---|---|
| R2 | The relay request timestamp is formatted in the phone's default locale. | `relay/RelayAuth.kt:74`: `"%03d".format(frac)`. In Arabic, Persian, Bengali and similar locales `%d` yields native digits, making the signed header non-ASCII. `:core:relay` is a JVM module, so Android lint's locale check never sees it. | Relay requests fail (and may crash the collector) for any member whose phone language uses non-Latin digits. A blocker for such a tester. | S: `Locale.ROOT`, plus a test under a Persian default locale. | V/S |
| R3 | With app lock "Immediately", rotation and every system dialog lock the app. | `app/MainActivity.kt:187-190` calls `appLock.onBackground()` in every `onStop` with no `isChangingConfigurations` check; `data/lock/AppLock.kt:293-298`. Affects the export's "Save to…" picker, the share sheet, the browser. | With R1, a History export cannot finish in this mode. | S. | V/S |
| R4 | Unsaved work is dropped whenever the phase leaves Unlocked. | The item editor, rule editor and message draft live only in ViewModels under the main destination; triggers are `vault.locking`, a release update, relay refusals. | A vault timeout during a long edit loses it without a word. | M: keep drafts in a Keystore-encrypted file, or at least say what happened. | V |
| R5 | The accept screen re-sends `connection.invite.accept` whenever its ViewModel is rebuilt from saved state. | `connections/ConnectionsViewModels.kt:351-357`. Masked today by R1; appears once R1 is fixed. | A used invitation shows an error instead of the safety code. | S: fix with R1. | V/S |
| R6 | Deep-link input has no length bound before it enters a route argument. | `app/MainActivity.kt:150-151` → `app/ui/AppShell.kt:420` (`AcceptRoute(link)`); the setup path is bounded (`altchan/SetupCode.kt:76`). | A very long `vettid://connect#…` lands in the saved-state bundle; a `TransactionTooLargeException` on stop is possible. | S: drop data over 4 KB. | V/S |
| R7 | A setup link opened on a phone that already has a vault stays queued in memory. | `app/MainActivity.kt:151` offers any non-connect URL; it is consumed only by onboarding (`onboarding/OnboardingViewModel.kt:260-262`). | No feedback; if the phone becomes signed out in the same process the link is redeemed without being asked for ("Confirm account" still follows). | S. | V |
| R8 | History export leaves broken files behind. | A lost ViewModel while "Save to…" is open returns silently (`history/HistoryExportViewModel.kt:253`), leaving the empty document; Back during writing cancels without discarding the file (`:270-292`). | Empty or truncated exports in the member's folder. | S. | V/S |
| R9 | Transfer-out cancels on any Back, also while the approval is in flight. | `settings/TransferOutScreen.kt:70`; `settings/TransferOutViewModel.kt:269-286`. | A stray back gesture makes both phones start over, or races the PIN and password already sent. | S: ignore Back while busy; confirm otherwise. | V |

### Polish

| ID | Finding | Evidence | Fix | Status |
|---|---|---|---|---|
| R10 | Relaunch from Recents may re-process the launching intent. | `app/MainActivity.kt:89` checks only `savedInstanceState == null`. | S: skip when launched from history. | S |
| R11 | Internal notification actions are accepted from any app. | `app/MainActivity.kt:138-148`; the effect is navigation and marking one item read. | S: a per-process token extra. | V |
| R12 | The canary share target accepts any URI scheme and prompts in any phase. | `app/MainActivity.kt:163-167`; the dialog also shows over onboarding and unlock (`app/ui/VettIdApp.kt:165-167`). The read is bounded (90 KB), off the main thread, verified under pinned keys. | S: require `content://`. | V |
| R13 | Backoff countdowns count ticks, not a deadline. | `onboarding/UnlockViewModel.kt` (`startBackoff`), `onboarding/RecoverViewModel.kt:338-348,452-461`, `settings/TransferOutViewModel.kt:258-266`. A frozen process returns to a stale wait. | S: store an `Instant`. | V/S |
| R14 | The reveal state of a secret field survives rotation and the app lock. | `ui/Forms.kt:95` (`rememberSaveable`). | S: plain `remember`; reset on stop. | V |
| R15 | A forced theme does not cover the splash or the first frame. | `values/themes.xml`, `values-night/themes.xml`; `app/MainActivity.kt:94`. | S. | V/S |
| R16 | Large screens, landscape and narrow windows. | Forms and their button have no maximum width (`ui/Forms.kt:191-235`); the drawer is a fixed 312dp (`ui/Drawer.kt:56`); no display-cutout inset is applied. | M. | V/S |
| R17 | Invitation links carry no environment check, and every build type claims the production relay host. | `app/src/main/AndroidManifest.xml:57-69`; `data/social/InviteLinks.kt:100-109`. | S. | V |
| R18 | Date-picker values of the item being edited are saveable. | `items/ItemEditScreen.kt:844,864-865`. | S. | V |
| R19 | A restored main destination may compose for a frame before the phase is known. | Fix with R1. | S. | S |
| R20 | Translation readiness. | See [Appendix D](#appendix-d-translation-readiness). No `values-xx` folder exists; the list is short. | S–M. | V |

### Checked and sound

- **Links while locked** are queued and acted on only after the app lock and the vault unlock; an
  opened invitation always waits for the member's confirmation.
- **Setup links** are strict: at most 1024 characters, the host and path of this build's own
  environment, a 22-character secret; another environment's link is "not a code".
- **Intents are consumed once** across rotation and restore; `getIntent` is never re-read.
- **Notification taps:** immutable pending intents carrying an id only; private lock-screen
  visibility with a generic public version.
- **Back:** the editors confirm a discard; the update flow ignores Back while a step runs; the
  owner-check gate cannot be dismissed.
- **RTL:** no left/right padding or alignment; every directional icon is auto-mirrored; names are
  bidi-isolated.
- **Case and wire formats:** Kotlin's `lowercase()` is locale-invariant; `Locale.ROOT` where a
  locale overload is used; wire date formatters are numeric with UTC (R2 is the one exception).

---

## 5. Client security hygiene

**What the plan requires.** §4: the typed recovery code is never written to disk, logs, the
clipboard or saved state, and its screen sets `FLAG_SECURE` ("the first screen of the app to set
it, an open point for the owner"). CLAUDE.md: `allowBackup=false`; every critical action asks for
the credential password.

**What the code does.** Manifests, backup rules, logging, debug hooks and the wipe are in good
shape. The gaps are on the screen and the keyboard (S1, S2), the clipboard, and a few places where
the app lock or an erase does less than its name says.

### Merged manifests (generated for this audit)

| | `fullRelease` | `fossRelease` |
|---|---|---|
| SDK | min 31, target 37 | same |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, USE_BIOMETRIC, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, RECEIVE_BOOT_COMPLETED, CAMERA, WAKE_LOCK, `c2dm.permission.RECEIVE`, the `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` signature permission | the same without WAKE_LOCK and `c2dm` |
| Exported, reachable | `MainActivity` only | `MainActivity` only |
| Exported, permission-guarded | `SystemJobService` (BIND_JOB_SERVICE), WorkManager `DiagnosticsReceiver` (DUMP), `ProfileInstallReceiver` (DUMP), `FirebaseInstanceIdReceiver` (`c2dm.permission.SEND`, **disabled** without the Firebase options) | `ProfileInstallReceiver` (DUMP) |
| Not exported | `VaultConnectionService` (specialUse), `BootReceiver`, `VettIdMessagingService` (disabled), WorkManager, Room, Firebase discovery, datatransport, `GoogleApiActivity`, CameraX metadata | `VaultConnectionService`, `BootReceiver`, CameraX metadata |
| Providers | `androidx.startup` only (emoji2, lifecycle, profile installer, OkHttp); no `FirebaseInitProvider`, no WorkManager initialiser | the same |
| Application | `allowBackup=false`, extraction rules exclude every domain for cloud backup and device transfer, no `debuggable`, no `testOnly`, no network security config (platform default: no cleartext, no user CAs) | the same |
| `queries` | `com.google.android.gms` | none |

`MainActivity`'s filters: launcher; `https://account.vettid.org/vault/enroll/` (auto-verify);
`https://relay.vettid.org/connect` (auto-verify; the relay does not serve `assetlinks.json` yet);
`vettid://connect`; `SEND application/json`. There is no file provider and no WebView.

### Public

| ID | Finding | Evidence | Effect | Fix | Status |
|---|---|---|---|---|---|
| S3 | "Copy" on a one-time-code field copies the TOTP seed, not the code. | `items/ItemDetailScreen.kt:360` `copy(context, f.label, text, sensitive = masked)` where `text` is the field value; `:365` passes the same `text` to `OtpCode(seed)`. | The member pastes a long-lived seed where a 6-digit code was meant. A visible functional bug for testers. | S: copy the current code; offer the seed only behind the eye. | V |
| S4 | Clipboard hygiene. | Only `password` and `otp` kinds get the sensitive flag (`items/ItemDetailScreen.kt:473`); a card number or recovery phrase does not. The transfer link is copied plain (`settings/TransferOutScreen.kt:268`). Nothing is ever cleared. | Values show in the clipboard preview and stay for clipboard-history keyboards. | S: flag by the item's protection; flag the transfer link; clear after about 60 s if still ours. | V |
| S5 | "Delete vault / start over" is not a wipe. | `data/vault/VaultManager.kt:919-923` (`dropSession(forget = true)`, an empty account record); `:405-411` clears social, items, feed and the owner check only. | Left behind: preferences including push memory, the canary manifest, release state, the cached own profile (name, photo) and the History cache, which a new vault set up in the same process could briefly show. | S–M: route through `LocalWipe`, or clear those too. | V |
| S6 | The app-lock timeout uses the wall clock. | `data/lock/AppLock.kt:120` (`System::currentTimeMillis`), used at `:290,297`. | Setting the clock back while the app is in the background keeps it unlocked. Needs the unlocked phone in hand. | S: `SystemClock.elapsedRealtime()`. | V |
| S7 | Leaving the app while a prompt shows may skip the timeout. | `data/lock/AppLock.kt:289` `if (authenticating) return`; `:295` `val at = backgroundAt ?: return`. | No background time is recorded, so the next return does not lock. | S: record the time regardless. | S |
| S8 | No tapjacking protection on one-tap approvals. | No `filterTouchesWhenObscured` or `setHideOverlayWindows`; one-tap actions at `approvals/ApprovalsScreen.kt:356-357`, `connections/ConnectFlowScreens.kt:525-526`, `settings/TransferOutScreen.kt:96-97` ("codes match"). Needs a malicious app with the overlay permission. Critical uses and the transfer approval need the password and are not affected. | S: `HIDE_OVERLAY_WINDOWS` and `window.setHideOverlayWindows(true)`. | V |
| S9 | The release APK checks leave gaps. | `checkReleaseApk` and its siblings (`app/build.gradle.kts:189-214,442-455,495-516`) assert strings, ids, hosts and the Firebase set. They do not assert: `vettid.theme`, any `:core:testing` class, `debuggable`, the set of exported components, the absence of a network security config, or that R8 ran. | Today's wiring is right; a regression would pass CI. | S: add the strings and a merged-manifest snapshot (T-list, item 10). | V |
| S10 | The canary-manifest share target ships to every member. | `app/src/main/AndroidManifest.xml:73-77`; `app/MainActivity.kt:161-172`. | VettID appears in every JSON share sheet, and any app can make it read a URI it supplies (bounded, verified, confirmed, never echoed). | S: `content://` only; consider an activity alias a setting enables. | V |
| S11 | A setup link is redeemed as soon as it arrives. | `onboarding/OnboardingViewModel.kt:260-269`. | A web page can open the enrol link with its own secret on a phone at the welcome screen; the only barrier to setting up under someone else's account is the masked-email confirm. | S: ask "Use this setup link?" first. | V |

### Polish

| ID | Finding | Evidence | Fix | Status |
|---|---|---|---|---|
| S12 | The app lock is a UI gate only, and turning it off needs no authentication. | `AppLock.appDataKey()` (`data/lock/AppLock.kt:301`) has no caller; local files use a key with no user authentication (`data/KeystoreFileStore.kt:14-16`); `settings/SettingsScreen.kt:152`. | S: require the prompt to disable; correct the class comment. | V |
| S13 | "Reveal" is ungated on a phone with no screen lock. | `app/MainActivity.kt:247` returns true when nothing can authenticate. | A one-time hint. | V |
| S14 | Shared-with-you content shows `password` and `otp` values unmasked. | `items/SharingScreens.kt:543-555`. | S. | V |
| S15 | A visible secret field allows copy and cut. | `ui/Forms.kt:104`. | S. | S |
| S16 | One log line can carry relay-controlled text. | `app/di/AppModule.kt:104` `Log.w("VettID", "relay error not handled: ${e.message}")`. | S: log the class name, as the other six do. | V |
| S17 | Wipe leftovers other than the known notification. | Notification channels (never deleted); the WorkManager wake job (its database is deleted under a live instance); an orphaned FCM token when a wipe finishes after a process death; in-memory flows not reset (`deletionFlow`, `pausedFlow`, the update offer, `NotificationOpenInbox`); `vettid.app_api.v1` missing from `KNOWN_ALIASES` (`data/wipe/LocalWipe.kt:152-157`; covered by the alias enumeration). | S. | V |
| S18 | The own profile and the History cache stay in memory after a vault lock. | `data/vault/VaultManager.kt:284-288` clears items, sharing and feed. Not shown while locked. | S. | V |
| S19 | The invitation link travels as a route argument. | `connections/ConnectionsScreen.kt:97` (see R6). Not a secret on its own. | S. | V |
| S20 | External links are opened without a guard. | `LocalUriHandler.openUri` at 8 sites (`app/ui/VettIdApp.kt:142`, `app/ui/AccountSheet.kt:105`, `app/ui/AppShell.kt:247,348`, `settings/HelpScreen.kt:51,58,67`, `onboarding/WhatsNew.kt:64`, `onboarding/MoveScreens.kt:224`). Throws when no browser handles the link. | S: one safe helper. | V |
| S21 | Noted, no recommendation. | No TLS pinning (trust comes from the pinned manifest keys and attestation); no StrictMode; no root or debugger check. | – | V |

### Secret fields and the keyboard

| Field | Keyboard | Autocorrect off | Masked | Note |
|---|---|---|---|---|
| Every PIN and password (`ui/Forms.kt:83`) | Number password / password | Yes | Yes, with a toggle | Autofill excluded; password class implies no learning |
| Recovery code (`onboarding/RecoverTypeScreen.kt:75`) | Password, visible | Yes | No, grouped | Paste only, `FLAG_SECURE` |
| Setup code (`onboarding/OnboardingScreens.kt:257`) | ASCII | Yes | No | Single use, needs the email |
| Transfer code (`onboarding/MoveScreens.kt:464`) | URI | Yes | No | |
| Item field `password` | Password | Yes | Yes | |
| Item field `otp` (the seed) | ASCII | Yes | **No** | S2 |
| Item fields `text`, `multiline`, notes, address | Text | **No** | No | S2 |

Every value is an immutable `String` in a ViewModel; nothing is zeroized. The old keystroke leak is
closed by construction: while locked, the lock screen is composed *instead of* the content, focus
is cleared and the keyboard hidden (`app/ui/AppLockGate.kt:43-63`).

### What remains after an erase

| Store | Wipe (`LocalWipe`) | Delete vault / start over |
|---|---|---|
| Device state, social, owner-check files (no-backup dir) | Removed | Removed or emptied |
| Account record | Removed | Overwritten with an empty record |
| Canary manifest, release state | Removed | **Kept** (public data) |
| Preferences (theme, lock, notification mode, push memory) | Removed | **Kept** |
| Wrapped device seeds | Removed | Regenerated |
| Keystore aliases | Every alias enumerated and deleted | App key regenerated; others kept |
| Cache, databases, shared preferences (Firebase, WorkManager) | Removed on disk | Kept |
| Posted notifications | `cancelAll()`; a leftover after a transfer away is **known, in progress** | Cancelled when the vault closes |
| Notification channels | **Kept** | Kept |
| Foreground service | Stops with the phase | Stops |
| FCM token | Deleted while the process lives | Deleted |
| History export file | Kept by design (the member's own document) | Kept |

### Checked and sound

- **Logging:** a release build logs exactly seven statements; none carries a PIN, password, code,
  token, vault id, user id, email or message content. No OkHttp logging interceptor, no crash
  reporting, no analytics.
- **Debug hooks:** the screen catalog, the launch extras and the draw-over-keyguard switch are
  compiled only into `debug` and `devStack`; `:core:testing` is test and `devStack` only; test
  tags as resource ids are behind `BuildConfig.DEBUG`. In `main`, `MainActivity` reads only the
  item id, the shared stream and the intent data.
- **App lock:** the framework prompt with a `CryptoObject`; strong biometrics or device
  credential; the key is invalidated by a new biometric enrolment; the lock starts locked at
  every process start; nothing of the app is composed beneath it.
- **Wipe:** crash-safe (a marker first, resumed before injection at the next start).
- **Clipboard on the recovery screen:** paste only; copy and cut removed.
- **No files at rest outside the encrypted store;** the profile photo and QR frames stay in
  memory.

---

## 6. Performance and battery

**What the code does.** All vault and crypto work runs off the main thread; reconnects back off
with jitter; there is no wake lock, no alarm and no battery-optimisation request. R8 and resource
shrinking are on for release, with no keep rule at all (nothing keeps too much). Every lazy list
has stable keys. The findings are the long poll, three start-up risks and the Messages tab.

| ID | Sev. | Finding | Evidence | Effect | Fix | Status |
|---|---|---|---|---|---|---|
| P1 | Public | The on-phone service keeps the relay on a 25 s long poll all day. | `vault/VaultDevice.kt:131-132` (long poll by default); `relay/RelayClient.kt:381` (`DEFAULT_WAIT_S = 25`); `data/vault/VaultManager.kt:381` overrides neither; the on-phone service is the default mode (`data/prefs/Preferences.kt:85`). The websocket mode exists and nothing selects it. | About 3,450 signed HTTPS requests a day while the vault is unlocked; on mobile data the radio never rests. | M: the websocket with a ping of several minutes, or a longer server wait. | V; cost S |
| P2 | Public | `startForeground` in `onStartCommand` is unguarded, and the service is sticky. | `notify/VaultConnectionService.kt:65,81,96`; the service's scope and `BootReceiver`'s have no exception handler. | A background restart can throw `ForegroundServiceStartNotAllowedException` and crash in the background (Play vitals). | S. | V; crash S |
| P3 | Public | A Keystore decrypt and a file read run on the main thread at every cold start of an enrolled phone. | `data/vault/VaultManager.kt:116` (`local = loadLocal()` in the constructor, built during injection in `Application.onCreate`); `loadLocal` catches only two exception types (`:355-361`). | Slower start; a misbehaving Keystore throwing `ProviderException` would crash at every launch. | M: load in the app scope. | V |
| P4 | Public | A corrupt preferences file leaves the app on "Starting…" for good. | `data/prefs/Preferences.kt:121` (no corruption handler); `app/VettIdApplication.kt:60-63` runs `appLock.start()` then `account.refresh()` in one coroutine; `appLock.start()` reads the preferences (`data/lock/AppLock.kt:151`); `RelaySafetyNet` swallows every `IOException` as a relay error (`data/vault/RelaySafetyNet.kt:23`). | A lock-out of low probability; only clearing app data helps. | S: a replace-on-corruption handler; separate the two launches. | V |
| P5 | Public | Messages does one round trip per connection and one per unread message; older messages cannot be read. | `data/social/SocialManager.kt:596-599,645-656`; `HISTORY = 200` (`:933`) is the only window. | 30 connections = 30 sequential requests; a conversation over 200 messages cannot be scrolled back. | M. | V |
| P6 | Polish | Profile photos are decoded on the main thread inside list rows, uncached. | `ui/ProfilePhotos.kt:143-144`. | – | S. | V |
| P7 | Polish | App-lock Keystore operations on the main thread; one uncaught exception type. | `app/MainActivity.kt:203-204,223`; `data/lock/AppLock.kt:230-237`. | – | S. | V |
| P8 | Polish | No app baseline profile. | No module, no `baseline-prof.txt`. Check against `fossRelease` reproducibility before adding one. | – | M. | V |
| P9 | Polish | Size trimmings. | BouncyCastle `.properties` (96 KB), `kotlin_builtins`, `DebugProbesKt.bin` not excluded (`app/build.gradle.kts:158`); four ABIs of three small native libraries. Measured on a stale release APK of 2026-10-05 (6.4 MB: 4.7 MB dex, 1.06 MB fonts). | – | S. | V |
| P10 | Polish | Non-lazy lists inside a scrolling column. | `items/SharingScreens.kt:174-175,211,386,460`; `approvals/ApprovalsScreen.kt:604,923`. Bounded by vault limits. | – | Leave for now. | V |

**Checked and sound:** no `runBlocking` on the main thread (the two uses are on Firebase's worker
thread); the earlier pool-eviction crash is fixed (`app/net/PoolEviction.kt`); all state is
collected with the lifecycle (67 sites, none without); countdown tickers update one field; the boot
receiver starts nothing without an enrolled vault; photos are re-encoded to at most 64 KB with no
EXIF.

---

## 7. Play pre-launch and store policy

Policy statements are from memory. **Verify each in Play Console.**

**SDK levels:** compile 37, target 37, min 31. Confirm that Play accepts uploads targeting 37 today.

| ID | Sev. | Finding | Evidence | Effect | Fix | Status |
|---|---|---|---|---|---|---|
| G1 | Public | The QR scanner crashes when there is no back camera, and it is the first screen after "Start". | `ui/QrScanner.kt:66` (`providerFuture.get()` uncaught) and `:73` (`bindToLifecycle(…, DEFAULT_BACK_CAMERA, …)` uncaught), in a main-executor listener. `ui/PhotoCamera.kt:220-257` guards exactly these; the scanner does not. The manifest marks the camera as not required, so Play offers the app to front-camera tablets and Chromebooks, which the pre-launch robot also uses. | A crash in the pre-launch report and for any such device. | S: port the photo camera's guards; show the "type the code" path. | V; crash S |
| G2 | Public | The camera permission dialog fires on entry, and a permanent denial has no settings link. | `onboarding/MoveScreens.kt:84`, `connections/ConnectFlowScreens.kt:596`; `rememberCameraPermission` (`ui/QrScanner.kt:33-40`) has no blocked state, while `rememberCameraAccess` (`ui/PhotoCamera.kt:74-112`) has one. Typing the code is always offered, so nobody is stuck. | "Allow" does nothing after "don't ask again". | S: reuse `rememberCameraAccess`; ask on a tap. | V |
| G3 | Public | Prepare the `specialUse` foreground-service declaration. | Type, subtype text and permissions are present (`core/notify/src/main/AndroidManifest.xml`). Play will ask for a description, a video and why no alternative fits. Weak points: the service is the default for every enrolled phone and starts at boot; the subtype says "on devices without a push service" while a Play install has one and the `full` build can offer Google push. | The declaration may be refused. | S (wording, and a product decision on the Play default). | V; outcome unknown |
| G4 | Public | User content with no block for a connection and no report. | `ConnectionsRepository.block` exists (`data/social/SocialManager.kt:554`) but no screen calls it for an established connection; no "report" string exists. | Play's user-generated-content policy expects both where users exchange messages, names and photos. | S–M: put Block back; "Report" as a link to the contact page. | V |
| G5 | Public | Account deletion is not named in the app. | "Delete vault" is a real server-side erase of vault content (`data/vault/VaultManager.kt:913-923`). The account (email, names, membership) lives on the account site; the app only has "Open account portal". | Play requires an in-app path to request account deletion and a public web URL. | S in the app, plus the web page. | V |
| G6 | Public | No privacy policy link and no third-party licences screen. | Help has About, Security, Contact (`settings/HelpScreen.kt:36-73`); `packaging` strips licence files (`app/build.gradle.kts:158`); the OFL texts are in the repo but not in the APK. | Play listing requirement; Apache-2.0, MIT and OFL notices. | S–M: a static screen from a checked-in asset (no Google plugin, to keep `foss` clean). | V |
| G7 | Public | App access for review. | The app is unusable without a membership setup code; before enrolment only Welcome, setup, recover and transfer-in are reachable. | The robot covers those screens only. Provide a reviewer account, written steps, and a way to mint a fresh code per review. | – | V |
| G8 | Public | The F-Droid scanner may flag `app/build.gradle.kts`. | The `foss` checks carry non-free names as string literals in a Gradle file (`app/build.gradle.kts:417-438`). | A scanner failure. | S: move the checks into `build-logic` Kotlin, or a reviewed `scanignore`. Try `fdroid scanner`. | S |
| G9 | Public | F-Droid anti-features and recipe. | `docs/fdroid/com.vettid.app.yml` lists no anti-features; endpoints are fixed and a membership is required, so reviewers will likely ask for `TetheredNet` (and `NonFreeNet` if any server part is not free). The recipe installs Debian's OpenJDK 21 while the published APK is built with Temurin 21, a reproducibility risk; `versionName` "2.0.0-a0" does not match the tag pattern yet; no `fastlane/metadata`. | Review questions; a failed reproducible build. | S. | V |
| G10 | Polish | The notification permission is asked without a sentence before it. | `app/ui/AppShell.kt:166-189`. Denial is handled well in Settings. | – | S. | V |
| G11 | Polish | Lint warnings worth clearing. | Four `PluralsCandidate` and one `ModifierParameter` (from a lint report of 2026-10-05). | – | S. | V |
| G12 | Polish | The relay App Link is unverified until the relay serves `assetlinks.json`. | Manifest comment at `app/src/main/AndroidManifest.xml:53-56`. | – | Relay follow-up. | V |

### Data-safety form inputs

| Data | Sent to | End to end (app ↔ the member's enclave) |
|---|---|---|
| Email (typed with the setup code) | Member API | No (TLS) |
| Names, profile photo (≤ 64 KB, no EXIF) | The vault; shared with connections | Yes |
| Messages, vault items, tags, share rules | The vault | Yes |
| PIN, credential password | The enclave, sealed to its attested key | Yes |
| Device model name | The vault's device list | Yes |
| Key-attestation chain (boot state, patch level, package, signer; no IMEI or serial) | The enclave | TLS |
| App key id, vault id, timestamp, nonce on every member-API request | Member API | No (TLS) |
| Mailbox id, request timing, IP address | Relay | – |
| FCM token (only if the member chooses Google push) | Google, then the member's vault | The push carries no content |
| Diagnostics, crash logs, analytics, advertising id | None | – |
| Camera frames, biometrics | On the device only | – |

Other declarations: no ads; adults; user-to-user messaging (yes); the app ships its own
cryptography (BouncyCastle), so the "only uses OS encryption" exemption does not apply (take
advice on the export question).

**Checked and sound:** the only reachable exported component is `MainActivity`; Firebase's
components are disabled without the build options and its init provider is removed;
`checkFossReleaseApk` checks the dependency graph, the dex, the file list and the manifest for
anything of Google or WorkManager; no self-update code; the only binaries in the repo are the
Gradle wrapper and two fonts with sources and checksums; no `!!` or unguarded parsing was found on
the screens reachable without an account (the scanner, G1, is the exception).

---

## 8. Tests

About 1,176 unit tests in 154 files (28 files use Robolectric) and 21 instrumented tests in 10
files. CI runs `detekt`, `testDebugUnitTest :core:crypto:test :core:relay:test`, `:app:lintDebug`,
the four assembles, and the three APK checks.

| ID | Sev. | Finding | Evidence | Fix | Status |
|---|---|---|---|---|---|
| T1 | Public | No instrumented test is compiled in CI, and three cannot compile in the default variant. | `.github/workflows/ci.yml` has no `assemble*AndroidTest`; `app/src/androidTest` uses `BuildConfig.DEV_STACK_API`, which exists only on `devStack`. The README's "compiled in CI" does not match the workflow. | S: add the five `assemble…AndroidTest` tasks. | V |
| T2 | Public | `VaultManager` (1,636 lines: phases, enrol, unlock, wipe, transfer) has no unit test. | Tests reach only its companion mappers. Its constructor needs a `Context`, the Keystore and OkHttp. N1, N3, N6 and N9 all live here. | L: extract the phase machine, or Robolectric with fakes. | V |
| T3 | Public | Tests that silently never run in CI. | `core/vault/src/test/.../DevStackJvmTest.kt` (3, `assumeTrue`), `core/data/src/test/.../items/ItemsDevStackTest.kt` (1), and all 21 instrumented tests. Nothing reports skipped counts. | S: print skipped tests; schedule a dev-stack run. | V |
| T4 | Polish | Real-time waits in unit tests. | `ReleaseNotesClientTest.kt:110-115` waits about 10 s of wall clock; `FeedManagerTest`, `OwnerCheckManagerTest`, `HistoryViewModelTest` poll with `Thread.sleep(10)` because `vaultGuard` hard-codes `Dispatchers.IO` (`data/vault/Guard.kt:26`); `VaultDeviceTest` asserts absence by 200–300 ms timeouts. | M: inject the dispatcher. | V |
| T5 | Polish | No screenshot tests and no accessibility checks in tests. | No Paparazzi or Roborazzi, no `AccessibilityChecks.enable()`; the screen catalog (1,294 lines, debug only) is used by no test. | See the list below, item 1. | V |
| T6 | Polish | `ContrastTest` covers the base scheme only. | See [Contrast](#contrast). | S, with A18. | V |
| T7 | Polish | Lint and detekt blind spots. | Only `:app:lintDebug` runs; `src/release`, `src/staging`, `src/foss`, `src/noDebugTools` get lint-vital only; detekt omits `src/staging`, `src/noDebugTools`, `src/androidTest`; warnings do not fail CI. `:core:relay` and `:core:crypto` are JVM modules that Android lint never sees (R2). | S. | V |
| T8 | Polish | Dead test wiring. | `feature/credential` and `feature/settings` declare `androidTestImplementation` with no `src/androidTest`. | S. | V |

### Instrumented tests (none run in CI)

| Class | Module | Covers | Needs |
|---|---|---|---|
| `A3ExitTest` | app | Fresh install → enrolled vault with credential → lock/unlock, through the UI | Dev stack, `devStack` build, a phone with real attestation |
| `A4ExitTest` | app | Invite, safety code, messages both ways, remove, approvals | Dev stack with a peer, phone |
| `ReplacedWipeTest` | app | A transfer away, then the app erases itself | Dev stack, phone |
| `KeystoreAttesterDeviceTest` | core:altchan | The real attester against the production policy | The locked test phone |
| `A2ExitTest` | core:data | Enrol, unlock, critical item, connect, message, without UI | Dev stack, phone |
| `KeystoreFileStoreTest` | core:data | Round trip, tamper, purpose binding | Any device |
| `AttestationChainDumpTest` | core:keystore | Dumps a real chain (by hand) | Phone |
| `KeystoreDeviceTest` (4) | core:keystore | Attestation challenge, seed wrapping, the auth-bound key | The locked test phone |
| `OnboardingScreensTest` (8) | feature:onboarding | Backup-off acknowledgement, rollback hides the PIN, What's new, erase offer | Any device |
| `RecoverTypeScreenTest` (2) | feature:onboarding | Typed-code grouping; `FLAG_SECURE` only while shown | Any device |

**Untested classes of note:** `VaultManager`; `RootViewModel`, `ShellViewModel`,
`OwnerCheckGateViewModel`; `AndroidWipeTargets`; `DataStorePreferencesRepository`;
`VaultConnectionService`, `BootReceiver`; `FailureKind.messageRes`.

### The ten highest-value missing tests

1. **Catalog smoke** (Robolectric): every screen-catalog entry × font scale 1.0 and 2.0 × LTR and
   RTL × light and dark composes without throwing. Needs the catalog reachable from a test source
   set.
2. **Activity recreation per phase:** type into a form, recreate, assert the ViewModel and its
   state are the same (R1).
3. **Transfer-in resume:** `device.paired` arriving with no waiter, after a restart, and after the
   local timer (N1).
4. **Outbox:** a request that failed in transport is not re-deposited by the next flush (N2).
5. **Enrolment retry:** a failed poll followed by "Try again" does not post a second enrol (N3).
6. **Scanner without a camera:** no camera, front only, permission denied → the "type the code"
   path, no crash (G1).
7. **Start-up resilience:** a corrupt preferences file and an undecryptable account file reach
   Welcome or Unlock, not a crash or a permanent "Starting" (P3, P4).
8. **Wipe leaves nothing:** after `LocalWipe.run()` with the real targets, the no-backup dir, the
   DataStore, shared preferences and Keystore aliases are empty.
9. **Wrong clock and locale:** the relay timestamp under a Persian default locale (R2); a 401 with
   a large skew shows the clock hint (N5).
10. **Merged-manifest snapshot** per release variant: exported components, permissions, the
    service type, `allowBackup=false` (S9).

---

## Needs a real phone

Phones: the Pixel 10 Pro (GrapheneOS) and the Pixel 7 (stock), with a staging build
(`com.vettid.app.staging`) and a staging vault. Use `adb -s <serial>` when both are attached. Each
step names the finding it settles. Do item 1 first: it decides how urgent R1 is.

### 1. Rotation and the app lock (R1, R3) — two minutes

1. Open Settings → Vault status. Rotate the phone. **Expect:** still on Vault status. If it shows
   Messages, R1 is confirmed.
2. Turn the app lock on with the timeout "Immediately". Open an item, tap Edit, type a word. Press
   Home, return, unlock. **Expect:** the editor with the word. Messages means R1.
3. On a fresh install, reach "Create PIN", type four digits, rotate. **Expect:** the digits still
   there.
4. With the app lock on "Immediately", open any screen and rotate. **Expect:** no lock prompt
   (R3).

### 2. Process death ("Don't keep activities")

Developer options → "Don't keep activities" on. For each flow below: reach the step, press Home,
reopen from Recents, and note where you land.

1. Onboarding at "Confirm account", "Create PIN", "Credential password".
2. The enrolment progress screen, right after it starts (N3). **Expect:** it finishes or resumes;
   "Your account already has a vault" confirms N3.
3. The typed recovery code with six groups typed.
4. Transfer-in at "Compare the code" (N1; use a test vault, this can strand it).
5. An item editor with unsaved changes; a half-typed message (R4).
6. History → Export → "Save to…": pick a folder (R8). **Expect:** a file with content, or a clear
   message; an empty file confirms R8.

Then, with the phone attached: `adb -s <serial> shell am kill com.vettid.app.staging` while the app
is in the background, and repeat steps 2 and 4.

### 3. Transfer between the two phones (N1, A2, A3, A11, R9)

Use a vault you can afford to recover.

1. Start a transfer. When the safety code shows on the new phone, force-stop the app there.
   Approve on the old phone (PIN, password). Reopen the new phone. **Expect:** it finishes the
   transfer. Welcome confirms N1.
2. Repeat, but instead of force-stop: airplane mode on the new phone at the safety code, approve on
   the old one, wait 11 minutes, airplane mode off.
3. Repeat, but swipe Back on the new phone at the safety code. **Expect:** a confirmation.
4. On the old phone at "Compare the code", swipe Back. **Expect:** a confirmation (R9).

### 4. Airplane mode at each step (N2, N4, N8, N9, N10, E2, E3)

1. **Send:** airplane mode on, send "test 1", wait for the error, airplane mode off, send "test 1"
   again, background and foreground the app. Count the copies on the peer. Two or more confirms
   N2.
2. **Clone alarm (use a test vault):** airplane mode on, open a critical item with the password
   (error), airplane mode off, retry (works), background and foreground. A credential alarm
   confirms the N2 consequence.
3. **Enrol:** on the progress screen toggle airplane mode for 5 s right after it starts, then tap
   "Try again" (N3).
4. **Unlock on a flaky link:** during the unlock spinner switch Wi-Fi to mobile data several times.
   "This phone was replaced" or an erase offer confirms N4.
5. **Lock:** airplane mode on, Lock vault from the avatar sheet. Note what is said (N10).
6. **Approvals:** airplane mode on, open Approvals. "Nothing to approve" confirms E2. Open a
   connection → sharing: "You share nothing with …" confirms E3.
7. **Cold vault:** unlock first thing after the vault has been idle overnight. Time the spinner and
   note any text (N8, N20).
8. **Relay down, API up:** block `relay.vettid.org` (or its staging host) with a private DNS or
   firewall rule, open the app, unlock, open each list. Then the reverse with the account host.

### 5. Wrong clock (N5, E6)

Turn automatic time off. For each of +10 minutes, −10 minutes and +1 day: open the app, try an
unlock, try scanning a transfer code, open Messages. Write down the exact text each time.
**Expect** a mention of the date and time; "this phone was replaced", "could not be verified" or
"check your connection" confirm N5 and E6. Turn automatic time back on.

### 6. TalkBack walkthrough of each core flow

TalkBack on (on GrapheneOS install it first). For each flow, listen for the things in brackets.

1. **Onboarding:** Welcome → scan → type the code → confirm account → PIN → password → backup →
   progress → done. [Is each new title spoken (A3)? Is the progress spoken (A6)? Does "Next" on the
   keyboard move on (A5)?]
2. **Unlock with a wrong PIN.** [Is "That PIN is not correct" spoken (A1)? Is a wait shown and
   spoken (A4)?]
3. **Owner check with a wrong password.** [Is the failure and "N checks left" spoken (A1)?]
4. **Transfer in:** reach "Compare the code" and listen for 15 s. [Does it repeat the wait every
   second (A2)? Are the six digits read one by one?]
5. **Transfer out:** leave focus on the QR; scan from the other phone. [Is the safety-code screen
   announced (A3)?]
6. **Typed recovery code:** type a full wrong code; type "U" twice. [Is each refusal spoken with
   its group (A16)?]
7. **An item with a password and a one-time code:** reveal, read, copy. [Bullets (A13)? The code
   read as digits (A7)? What does paste give: six digits or the seed (S3)?]
8. **A conversation:** send, and receive a reply while it is open. [Is the reply announced (A12)?]
9. **Approvals:** decide a request with a wrong password (A1).
10. **History → Export** with a wrong PIN (A1).
11. **Sharing rule editor:** find the switch. [Does it have a name (A9)?]
12. **App lock with a hardware keyboard:** lock, press Esc on the prompt, then Tab and Enter (A17).

### 7. Font scale 200% and largest display size

Settings → Display → font size and display size at maximum, on the Pixel 7. Look at:

1. The drawer and the top bar on Messages and Connections (A15).
2. "Compare the code" in a transfer and in a connection (A15).
3. Credential → turn backup off: is the checkbox reachable (A14)?
4. Tags → edit a tag; the tag colour dialog; a sharing rule's end-time picker (A14, A27).
5. Settings → Vault → Attestation: are the fingerprints shown in full (A8)?
6. The typed recovery code with all twelve groups (A16).
7. The owner check with the keyboard up.

### 8. Dark and light

In each theme: Settings → Notifications (a switch that is off: can you see the thumb?), any form
with an empty unfocused text field (can you see its border?), a conversation (the time under an
incoming message) (A18, A19). Force Light on a dark system and cold-start the app: note any dark
flash (R15).

### 9. Security checks (S1, S7, S15)

1. App lock on, "Immediately". Reveal a secret item with its password shown. Press Recents. Is the
   value readable on the card? Repeat on a conversation (S1).
2. Take a screenshot on the unlock screen with the PIN shown (S1).
3. App lock on "Immediately": open a secret item, tap Reveal, press Home while the system prompt
   shows, wait, reopen. A missing lock prompt confirms S7.
4. Show a password in any field, long-press it. Is Copy offered (S15)?
5. Type a recovery phrase into a new "Recovery phrase" item with Gboard on the Pixel 7, save, then
   type the first word in another app. Suggested following words confirm S2.

### 10. Other

1. **Locale:** set the system language to فارسی or العربية (مصر), open the app, unlock, open
   Messages (R2).
2. **Expired setup QR:** scan one and hold it in frame for 15 s. "Too many attempts" confirms E5.
3. **Battery:** `adb shell dumpsys batterystats --charged com.vettid.app.staging` after 8 h idle
   and unlocked, on Wi-Fi and on mobile data; compare with the service off (P1).
4. **Sticky restart:** `adb shell am kill com.vettid.app.staging` with the service running in the
   background; watch logcat for a foreground-service exception (P2).
5. **Pixel Fold:** unfold on an item editor (R1); look at form widths unfolded (R16).
6. **No back camera:** an emulator image with the back camera set to "None" is enough for G1 (the
   one check that needs an emulator rather than a phone).

---

## Proposed order of work

The smallest set that makes the app ready for the first ten members: the seven blockers, plus the
small fixes whose failure mode is a lock-out, a crash on the first screen, or an invitation to
erase the phone. Grouped into PR-sized pieces, in order.

| PR | Contents | Findings | Size |
|---|---|---|---|
| 1 | **Root navigation keeps its destination.** Navigate only when the phase's destination changes; skip the app-lock background mark on configuration changes; a recreation test per phase. Do the phone check (item 1) first. | R1, R3, R5 | S |
| 2 | **Screen and keyboard privacy.** `FLAG_SECURE` on the window in release-like builds; password-class keyboard and no learning for secret and critical item values, notes and the TOTP seed; copy the code, not the seed; sensitive flag by protection. | S1, S2, S3, S4 | S–M |
| 3 | **Recovery can be left.** "Start over" on the PIN and password steps. | E1 | S |
| 4 | **Failed requests leave the outbox.** Remove the entry when the caller is told it failed; a test. | N2 | M |
| 5 | **Enrolment resumes.** Persist the outstanding enrolment, re-poll instead of re-posting, run it in the app scope; explain a cold start and give the unlock poll a deadline. | N3, N8 | M |
| 6 | **Transfer-in resumes.** Persist the pending transfer, apply `device.paired` in the handler, no keyring deletion on the local timer, confirm on Back (both phones), keep the screen on. | N1, R9 | M |
| 7 | **No false "replaced", no silent key drop, no start-up dead end.** Fresh signature per member-API attempt and no erase offer on one 401; never forget on an empty status body; a corruption handler for preferences; guard the QR scanner; `Locale.ROOT` in the relay timestamp. | N4, N6, P4, G1, R2 | S each |
| 8 | **Unlock tells the truth.** Show the wait together with "wrong PIN"; announce attempt results; the static waiting text on transfer-in; `paneTitle` on forms. | A4, A1, A2, A3 | S–M |

After those, before a public listing, roughly in this order: E2–E4 and E6 (errors that mislead);
N5 (clock hint); N9, N10 (phase and lock honesty); A5–A18 (the accessibility pass proper, with
`ContrastTest` extended); P1, P2 (battery and the sticky service); G2–G9 (store paperwork and the
missing screens: block and report, account deletion, privacy, licences); T1–T3 (CI compiles the
instrumented tests; a test harness for `VaultManager`).

---

## Appendix A: failure kinds

Text from `core/data/src/main/res/values/strings.xml`; mapping in `data/vault/FailureText.kt:10`,
`data/vault/Guard.kt:25-60` and `data/vault/VaultManager.kt:1527-1567`. Shown wherever a screen
calls `messageRes()` (about 40 notice cards).

| Kind | English text | Verdict |
|---|---|---|
| NETWORK | "VettID could not reach the service. Check your connection and try again." | Good; wrong for a malformed answer and for every relay refusal (N15) |
| UNAUTHORIZED | "VettID's account service does not accept this phone for your vault any more. If you moved VettID to another phone, this one was replaced. …" | Specific; wrong on a replayed signature or a wrong clock (N4, N5) |
| SETUP_CODE_INVALID | "That setup code did not work. Get a new one on the account site." | Good |
| TERMS_REQUIRED | "Accept the current membership terms on the account site first." | No link; also used for "not a member" |
| RATE_LIMITED | "Too many attempts. Wait a little and try again." | Good; countdown only in recovery |
| VAULT_UNAVAILABLE | "The vault service is not available yet. Try again later." | Good |
| SERVICE_PAUSED | "The vault service is paused for maintenance. Try again later." | Good |
| RELEASE_ENDED | "The vault software release your vault is sealed to has ended. The vault can no longer be opened." | Accurate; a dead end (E7) |
| BAD_PIN / BAD_PASSWORD | "That PIN is not correct." / "That credential password is not correct." | Good |
| BACKOFF | "Too many wrong attempts. Wait before trying again." | No duration on six forms (E13) |
| CREDENTIAL_FROZEN | "Your credential is frozen after a clone alert. Answer the alert first." | Good |
| ROTATION_REQUIRED | "Your credential must be rotated before it can be used again." | Good |
| NOT_SUPPORTED | "Your vault's software release does not support this yet." | Wrong for `ttl_not_allowed` and one grant case (E15) |
| NO_RESPONSE | "Your vault did not answer in time. Try again." | Wrong cause for `stale_credential`, `utk_invalid` |
| VAULT_EXISTS | "Your account already has a vault, held by another app." | Wrong after a half-finished transfer or enrolment on this phone (N1, N3) |
| ATTESTATION | "This phone could not be verified. VettID needs a phone with hardware key attestation and a locked, verified boot." | Wrong except for the sealed result code (E6) |
| ROLLBACK | "The vault is served by an older software release than this phone last used. VettID did not send your PIN." | Wrong for a state rollback in the update flow (E11) |
| MANIFEST | "The vault software list could not be verified. Try again later." | Good |
| NOT_FOUND | "Nothing was found." | Generic |
| CONNECTION_UNAVAILABLE | "This connection cannot be reached right now. Try again later." | Good |
| INVITE_INVALID / EXPIRED / NOT_CONNECTION / UNAVAILABLE | Four specific texts | Good |
| BLOCKED | "You blocked the vault that sent this invitation. Unblock it first to connect." | No block list to act on (E22) |
| CREDENTIAL_LOCKED | "Your credential is locked. Enter your credential password to continue." | Good |
| CONFLICT | "This changed on another device. Look again and retry." | Good |
| LIMIT | 27 named texts with bounds; else "A limit was reached. Remove something first." | Good |
| IN_USE | "A sharing rule uses this tag. Change or delete the rule first." | Good |
| OWNER_CHECK_REQUIRED | "Your daily check is due. What you entered is kept: the check opens when you leave this screen." | False where the form clears (E14) |
| REJECTED | "Rejected on your phone." | Effectively never shown |
| SIGNER_REFUSED | "This vault no longer accepts this installation of VettID. Update the app, or recover your vault with a current version." | Good |
| OTHER | "Something went wrong. Try again." | The fallback |

**What falls into OTHER:** vault codes `bad_request`, `forbidden`, `internal`, `relay_error`,
`approve_failed`, `exists`, `too_soon`, `credential_required`, `session_required`, `denied`,
`approval_timeout`, `busy`, `unavailable`, `one_app`, `credential_lost`, `transfer_pending` (each
outside the one flow that handles it); member-API codes `instance_moved`, `vault_busy`,
`duplicate_request`, `recovery_active`, `forbidden`, `conflict`, `csrf`, `bad_request`,
`internal` and any non-JSON body; an unreadable sealed result; Keystore and crypto exceptions.

The other error types (`UnlockAttempt`, `CodeRefusal`, `TypedProblem`, `ScanRefusal`,
`TransferInStep`, `TransferOutStep`, `OwnerCheckOutcome`, `NameChangeOutcome`, `ExportStep`,
`UpdateStepResult`, `PushRegState`, `FetchOutcome`, the draft and policy problems) each have one
specific text per value; their defects are E11, E12 and E15.

## Appendix B: screens that show or accept a secret

Today only the typed recovery code sets `FLAG_SECURE`. The simplest fix covers all rows at once:
the flag on the activity window in release-like builds (S1).

| Screen | Secret | Recommendation |
|---|---|---|
| PIN create and confirm, password create (`onboarding/OnboardingScreens.kt:353,375,409,441`) | PIN, password, with a show toggle | Protect |
| Unlock (`onboarding/UnlockScreen.kt:157,171`), owner check (`onboarding/OwnerCheckScreen.kt:132,142`) | PIN, password | Protect |
| Release update (`onboarding/ReleaseUpdateScreen.kt:94,300`) | PIN | Protect |
| Recovery PIN and password (`onboarding/MoveScreens.kt:199,371`) | PIN, password | Protect |
| Change PIN, delete vault (`settings/SettingsDetailScreens.kt:248-261,491-493`) | PIN, password | Protect |
| Transfer-out approve (`settings/TransferOutScreen.kt:146,158`), name change (`settings/ProfileScreens.kt:413,423`) | PIN, password | Protect |
| History export PIN (`history/HistoryExportScreen.kt:319`) | PIN | Protect |
| Credential screens and the password dialog (`credential/NewCredential.kt:159-171`, `credential/CredentialScreens.kt:375,497,542,636`) | PIN, password | Protect; the dialog is a separate window: set its secure policy explicitly |
| Critical-item password (`items/CredentialPassword.kt:116`), approvals (`approvals/ApprovalsScreen.kt:372`) | Password | Protect |
| Setup code typed (`onboarding/OnboardingScreens.kt:246,257`) | 8-symbol code and email | Protect (cheap; single use) |
| Setup, recovery and transfer scan | Camera preview only | Not needed |
| Typed recovery code (`onboarding/RecoverTypeScreen.kt:52`) | Recovery code | Protected today |
| Transfer code shown (`settings/TransferOutScreen.kt:235,256`) and pasted (`onboarding/MoveScreens.kt:464`) | The transfer offer | Protect: a photographed code lets a third party start the handshake (the safety code, PIN and password still stand in the way) |
| Safety codes (both transfers, connect flow, approvals) | Not secret | Not needed |
| Invitation shown or typed (`connections/ConnectFlowScreens.kt:193,472`) | Made to be shared | Not needed |
| Item detail (`items/ItemDetailScreen.kt:144`): revealed values, TOTP seed and code, critical values | Highest value | **Protect** |
| Item editor (`items/ItemEditScreen.kt:296,706,921`) | New values, visible unless the kind is `password` | Protect |
| Shared with you (`items/SharingScreens.kt:360,543`) | A connection's shared values | Protect |
| Conversations and the composer (`messages/MessagesScreen.kt:216,271,375`) | Message content | Protect, or the app-wide default |
| History, notifications, account sheet | Activity metadata, names, email | Covered by an app-wide default |
| History export | Never drawn; written to the member's chosen document | Not needed |
| Profile photo capture | Camera preview | Not needed |

## Appendix C: polling loops

| Where | Interval | Stops in the background |
|---|---|---|
| Mailbox collector | 25 s long poll | No: process scope, kept alive by the service (P1) |
| On-phone service keeper | Status hourly; reconnect backoff to 5 min | Service lifetime; the relay is paused while the vault is locked |
| Owner-check gate clock / release banner clock | 15 s / 60 s | Yes |
| Countdown tickers in ViewModels | 1 s | End at their deadline |
| OTP tick, approvals backoff, connect flow, export | 1 s | Leave with the screen |
| Member-API slot poll | 600 ms | Bounded by the screen, not by a deadline (N8) |

## Appendix D: translation readiness

No `values-xx` folder, no `localeConfig`. The app is close to ready; the full list of exceptions:

**Hard-coded text or raw wire values on screen**
- `data/vault/VaultManager.kt:1521-1524`: "Verified", "SelfSigned", "Unverified", "Failed" (shown as Verified boot).
- `onboarding/OnboardingScreens.kt:261`: placeholder "XXXX-XXXX".
- `approvals/ApprovalsScreen.kt:923`: `"• ${it.name} (${…})"`; `:927` raw role; `:311` raw request type.
- `data/items/ItemCategories.kt:58`: an English label derived from an identifier.
- Raw states: `app/ui/CanaryManifestDialog.kt:45`; `settings/SettingsDetailScreens.kt:178,192,310,431`; `onboarding/UnlockScreen.kt:332`; `onboarding/ReleaseUpdateScreen.kt:142,149`.
- Placeholders "?" and "-": `approvals/ApprovalsScreen.kt:207,927`; `items/SharingScreens.kt:462`; `ui/InitialTile.kt:30`; `credential/CredentialScreens.kt:590`; `settings/SettingsDetailScreens.kt:99,171,186,204,210,365,371,383,392,395`.
- "99+" built in code: `ui/Badges.kt:38`, `ui/NotificationBell.kt:71`, `connections/ConnectionSharingCards.kt:246`.
- English tag names written into new items by templates (`items/ItemTemplates.kt:102-229`); these become member data.

**Assembled in code**
- " · " joins at 22 sites, for example `connections/ConnectionSharingCards.kt:332,339,374,412`, `items/SharingScreens.kt:246,300,315,574`, `approvals/ApprovalsScreen.kt:205,221,309,878`, `settings/OwnerCheckSettings.kt:118`, `messages/MessagesScreen.kt:347`.
- `ui/Forms.kt:448`: `"$label: $stateLabel"` (spoken).
- Names as `"${first} ${last}"`: `settings/ProfileScreens.kt:95,348,462`.
- Case changed in code: `history/HistoryEntryScreen.kt:145`, `history/HistoryText.kt:29`, `history/HistoryScreen.kt:605`.

**Should be plurals or carry their unit in the string**
`items_rule_preview_more`, `items_otp_left`, `items_password_backoff`, `approvals_backoff`,
`history_export_pin_backoff`, `credential_ttl_seconds`, `history_export_reading_progress`,
`history_export_done_partial`, `connections_expires_in`, `data_pin_problem_short`,
`data_pin_problem_long`, `recover_type_groups`, `recover_bad_code` (the literal "5" twice),
`items_size_left`, `owner_check_notice_more` (`app/ui/OwnerCheckGate.kt:161`, an `if (count > 1)`).

**Locale-sensitive formatting**
- Wire: `relay/RelayAuth.kt:74` (R2), the only one.
- Display with a fixed shape: `"%d:%02d"` at `onboarding/UnlockScreen.kt:56`, `settings/ProfileScreens.kt:490`, `settings/TransferOutScreen.kt:60`; `"%.1f"` at `settings/PhotoCaptureScreens.kt:215`.
- A fixed date pattern: `notifications/NotificationsViewModel.kt:62` ("EEE d MMM"), wrong order for en-US, ja, zh.
- Sorting by `lowercase()` instead of a `Collator` at seven sites (`data/items/Items.kt:179`, `connections/ConnectionsViewModels.kt:80,580-581`, and others).
- Dates shown to the member use localized styles with the system time zone; deadlines are computed from instants.
