# A6 close-out: accessibility, offline and error states

This is the close-out of the A6 audit's first three sections ([A6-AUDIT](A6-AUDIT.md) §1 Accessibility,
§2 Offline and poor network, §3 Error states). It covers two things:

- every A, N and E finding of the audit, re-checked against current master;
- the screens added since 2026-10-11, audited for the same three areas.

Sections 4–8 of the audit (R, S, P, G, T) are not covered here. Nothing in the app was changed.

- **Audited commit:** `1981bf36` (master, 2026-11-05).
- **Method:** reading the code of the `:feature:*`, `:core:*` and `:app` main source sets, plus `git log` and
  `git log -S` to find when something changed.
  - No build, test run, phone, emulator, adb, dev stack or AWS was used, so nothing here was observed running.
  - Where only a phone can tell how something behaves (what TalkBack says, what clips at 200%), the finding says
    **Suspected**, and the [Needs a real phone](#needs-a-real-phone) checklist says what to try.
- **Conventions** are the audit's:
  - severity: Blocker, Public, Polish;
  - status: V (verified in code), C (computed), S (suspected), V/S (code verified, run-time effect suspected);
  - fix size: S, M, L;
  - paths shortened as described there.
- **Path shortenings added here:**
  - `devices/`, `votes/` and `wallet/` are `feature/<x>/src/main/kotlin/com/vettid/feature/<x>/`;
  - `map/` is `core/map/src/main/kotlin/com/vettid/core/map/`;
  - `core-wallet/` is `core/wallet/src/main/kotlin/com/vettid/core/wallet/`;
  - `chain/` is `core/chain/src/main/kotlin/com/vettid/core/chain/`;
  - `ui/` stays `core/ui/src/main/kotlin/com/vettid/core/ui/components/`.
- **Numbering:** new findings continue at **A28**, **N21** and **E25**. N20 is already taken in the audit (the 70 s
  read timeout), so the new N findings start at N21.
- **The wallet** is not in production builds (debug and staging only; CLAUDE.md "Wallet"). Its Public findings
  mean "before the wallet ships to members", and are marked *(wallet)*.
- **Owners:**
  - **al-cal**: app code;
  - **lead**: the phone checks;
  - **owner**: decisions, and any new or changed user-facing text. Texts marked DRAFT or approved are the owner's
    to change.

## Summary

**The audit's findings in sections 1–3 (70 in all)**

| Area | Fixed | Partly | Open | Obsolete |
|---|---|---|---|---|
| A (27) | 1 | 4 | 22 | 0 |
| N (19; N7 was never used) | 7 | 1 | 11 | 0 |
| E (24) | 3 | 7 | 14 | 0 |
| **Total** | **11** | **12** | **47** | **0** |

All four blockers in these sections are fixed: N1 (#187), N2 (#185), N3 (#186) and E1 (#121). Few commits since the
audit target accessibility. Only #118, #120, #122, #127 and #160 touch these findings.

**New findings (screens added since 2026-10-11)**

| Area | Blocker | Public | Polish |
|---|---|---|---|
| A28–A47 | 0 | 10 (3 of them wallet) | 10 |
| N21–N27 | 0 | 2 | 5 |
| E25–E41 | 0 | 4 | 13 |
| **Total** | **0** | **16** | **28** |

**What the new screens have in common.** The shared components improved:

- `SecretField`'s error is a polite live region;
- `NoticeCard` has an opt-in `announce`;
- touch targets, labels on icon-only controls and words for states are done well throughout.

What did not improve is what new screens inherit:

- Only 7 of about 190 `NoticeCard` calls announce, all on unlock, the owner check and one settings screen.
- There is still no `paneTitle` anywhere.
- Dialog bodies do not scroll (the `ShellOverlays.kt` wrappers only hide while covered).
- `outline` is unchanged at 1.81:1 (dark) and 1.41:1 (light).

Fixing A1/A3/A14/A18 in the shared components would close much of A29, A30, A36 and A41 at the same time.

**A regression of the A1 fix (A28).** Since `SecretField`'s error became a live region, two screens put a
ticking countdown into it:

- the critical-item password: "Try again in %1$d s", each second;
- History export's PIN.

The audit's own A1 fix said this must never happen.

---

## Status of the audit's findings

### 1. Accessibility (A1–A27)

| ID | Sev. | Status | Evidence (master `1981bf36`) | What remains | Owner |
|---|---|---|---|---|---|
| A1 | Public | **Partly** `e58423ab` (`MoveScreens` `Failure`/`ClaimFailure`); still silent: `OnboardingScreens.kt:163`, `TransferOutScreen.kt:343`, `ApprovalsScreen.kt:527-533`, `MessagesScreen.kt:343,355` | #122 `047cd1af`: `SecretField` error polite (`ui/Forms.kt:121`; `SecretFieldMessagesTest.theErrorAndTheWaitAreBothShownAndOnlyTheErrorIsAnnounced`). #127: `NoticeCard(announce=…)` (`ui/Forms.kt:383,404`), used on unlock (`onboarding/UnlockScreen.kt:201,210,219,281,342`), owner check (`onboarding/OwnerCheckScreen.kt:186,193,264,314`), `settings/SettingsDetailScreens.kt:453` | Silent: `onboarding/MoveScreens.kt:129-138` (`Failure`) and `:377` (`ClaimFailure`), `onboarding/OnboardingScreens.kt:163`, `settings/TransferOutScreen.kt:343`, `approvals/ApprovalsScreen.kt:527-533`, `messages/MessagesScreen.kt:343,355`. See also A28 (a regression) and A29 (the new screens) | al-cal |
| A2 | Public | Open | `onboarding/MoveScreens.kt:118-126`: live `Waiting` row; at `:633` with `transfer_in_waiting` "Waiting for your old phone (%1$s left)"; `onboarding/TransferInViewModel.kt:268,316` ticks every 1000 ms | Static text in the live region, the time outside it. Needs a time-less waiting text (**owner**). Effect Suspected | al-cal, owner (text), lead |
| A3 | Public | **Fixed** `ed891c76` (`paneTitle` on `FormScaffold`) | No `paneTitle` in the repo; `FormScaffold` title has `heading()` only (`ui/Forms.kt:263-281`) | `paneTitle = title` on the `FormScaffold` root. Now also covers A30 | al-cal |
| A4 | Public | **Fixed** | #122 `047cd1af`: error and wait shown together (`ui/Forms.kt:114-126`); `SecretFieldMessagesTest` (3 tests) | – | – |
| A5 | Public | **Fixed** `ed891c76` | `ui/Forms.kt:134` `KeyboardActions(onAny = { onImeAction() })` with default `{}`. `ImeAction.Next` with no focus move at `credential/NewCredential.kt:249,251,256`, `credential/CredentialScreens.kt:570,577,621`, `onboarding/OwnerCheckScreen.kt:235`, `onboarding/OnboardingScreens.kt:447,482`, `onboarding/UnlockScreen.kt:362-363`, `settings/SettingsDetailScreens.kt:473,477,488-489,747`, `settings/TransferOutScreen.kt:158`, `settings/NewPhoneSecret.kt:111` | Move focus down on Next | al-cal |
| A6 | Public | Open | `StepList` rows `clearAndSetSemantics`, no live region (`ui/Forms.kt:504-543`); used at `onboarding/OnboardingScreens.kt:605`, `onboarding/ReleaseUpdateScreen.kt:210` | One polite live text for the current step | al-cal |
| A7 | Public | **Partly** | #120 `146179bc`: "Copy the current code" (`items/ItemDetailScreen.kt:419`) | Code still `chunked(3).joinToString(" ")` with no description (`:406`); `items_otp_left` still "%1$d s" (`:414`). The fix needs a "N seconds left" text (**owner**) | al-cal, owner (text) |
| A8 | Public | **Fixed** `ed891c76` (no line limit); selectable fingerprints remain | `ui/Settings.kt:203,217` `maxLines = 2` + ellipsis; vault id (`settings/SettingsDetailScreens.kt:219`), boot key (`:637`); no `SelectionContainer`, no monospace | Same fix | al-cal |
| A9 | Public | **Fixed** `e58423ab` | `items/RuleEditScreen.kt:476-487` `ExistingSwitch`: texts beside a bare `Switch` | `Row.toggleable(role = Role.Switch)` | al-cal |
| A10 | Public | Open | `items/ItemsViewModels.kt:729` only sets `showErrors`; no scroll or focus (`items/ItemEditScreen.kt:235`) | Same fix (M) | al-cal |
| A11 | Public | Open | `settings/TransferOutViewModel.kt:322` `WINDOW` 10 min; ticker `:167-178`; plain `Window()` text (`settings/TransferOutScreen.kt:256,299-306`) | Announce at 2 min and 30 s; window length is an **owner** decision | al-cal, owner |
| A12 | Public | **Partly** `e58423ab` (spinner named); a live region for a new message remains | No live region for a new message (`messages/MessagesScreen.kt:393`, scroll only); send button becomes a bare `CircularProgressIndicator` (`:623-624`) | Same fix | al-cal |
| A13 | Public | Open | `items_masked_value` "••••••••" with no description (`items/ItemDetailScreen.kt:332`, `items/SharingScreens.kt:603`); `FieldRow` (`:313`) not merged | Same fix. "Hidden" is a new text (**owner**) | al-cal, owner (text) |
| A14 | Public | **Fixed** `ed891c76` | `ui/ShellOverlays.kt:27-73` wrappers add no scroll; `ui/ConfirmDialog.kt:35` plain `Text`; the audited dialogs unchanged (e.g. `settings/SettingsScreen.kt:729,780`, `items/ItemDetailScreen.kt:447`, `credential/CredentialScreens.kt:423,456,486`, `items/SharingScreens.kt:519`) | `verticalScroll` in the `AlertDialog` wrapper's `text` slot and in `ConfirmDialog` covers most sites; also A41. Clipping Suspected | al-cal, lead |
| A15 | Public | **Partly** `ed891c76` (drawer row and top bars); the safety code row remains | `ui/Drawer.kt:110` `.height(56.dp)`; `ui/TopBars.kt:65` `.height(72.dp)`, `:76` `maxLines = 1`; `ui/TopBarSearch.kt:89`; safety code a non-wrapping 40sp `Row` (`ui/Conversation.kt:102-121`) | `heightIn(min = …)`; wrap or scale the code. Effect Suspected | al-cal, lead |
| A16 | Public | **Partly** `e58423ab` (`Failure`/`ClaimFailure` announced); the hard `"\n"` and the second refusal in a group remain | `ClaimFailure`/`Failure` not announced (`onboarding/MoveScreens.kt:129-138,377-384`); hard `"\n"` (`onboarding/RecoverTypeScreen.kt:148`); a second refusal in a group not re-announced (`onboarding/RecoverViewModel.kt:353`) | Same fix | al-cal |
| A17 | Public | **Fixed** `095cff60` | `app/ui/AppLockGate.kt:57-58` `.focusable().onKeyEvent { true }`. The lock screen is composed *instead of* the content, so nothing is beneath it | Let Tab, D-pad, Enter, Space through | al-cal |
| A18 | Public | Open | `theme/Color.kt:144` (dark `Navy600`), `:178` (light `Mist300`) unchanged; no `SwitchDefaults` override. Numbers in the audit stand (C) | A 3:1 `outline` is a palette change against the website tokens: **owner** to approve the colour; then al-cal, and extend `ContrastTest` | owner, al-cal |
| A19 | Polish | Open | `ui/Conversation.kt:75,130` `META_ALPHA = 0.8f`; `TagLabel` unchanged (C) | Drop the alpha | al-cal |
| A20 | Polish | Open | `items/SharingScreens.kt:341,465,471`; `connections/ConnectionSharingCards.kt:355`; `connections/ConnectionsScreen.kt:555` | Names in descriptions (new texts, **owner**) | al-cal, owner |
| A21 | Polish | Open | No `heading()` in `items/ItemEditScreen.kt`, `items/RuleEditScreen.kt`, `settings/ProfileScreens.kt`, `history/*`; only `selectableGroup` is `items/RuleEditScreen.kt:300` (pre-audit) | Same fix | al-cal |
| A22 | Polish | Open | Placeholder-only labels `history/HistoryScreen.kt:396`, `messages/MessagesScreen.kt:601-609`; no change in the files for the dropdowns, month picker or address error | Same fix | al-cal |
| A23 | Polish | Open | "Copied" swaps text without live region (`settings/TransferOutScreen.kt:236,282,290`; `connections/ConnectFlowScreens.kt:200,248,256`); Undo snackbar `Short` (`notifications/NotificationsScreen.kt:223`) | Same fix | al-cal |
| A24 | Polish | **Partly** | #118 `ea070bd3`: signer line has `stateDescription` and expand/collapse actions (`settings/SettingsDetailScreens.kt:187-205`) | Release-update urgency by tint (`settings/SettingsScreen.kt:493-497`); approvals bold only (`approvals/ApprovalsScreen.kt:286`); `NoticeCard` icon description null (`ui/Forms.kt:399`) | al-cal |
| A25 | Polish | Open | FAB `Surface(onClick)` no role (`ui/FloatingControls.kt:84-91`); `ListRow` no role (`ui/ListRow.kt:63`); drawer `Role.Tab` (`ui/Drawer.kt:112`); bubble (`ui/Conversation.kt:67`); no-op row (`app/ui/AccountSheet.kt:132`); busy `PrimaryButton` no state (`ui/Forms.kt:306-309`) | Same fix | al-cal |
| A26 | Polish | **Partly** | #160 `219e546b`: notification sheet shows the second line (`notifications/NotificationsScreen.kt:636-667`); urgent rows 3 lines (`:575-577`) | `ListRow` 1 line (`ui/ListRow.kt:77,97`); other notification rows 1 line; `onboarding/WhatsNew.kt:109` | al-cal |
| A27 | Polish | Open | `ui/Forms.kt:489-492` urgent banner live on an unmerged row; `items/TagsScreen.kt:425` swatch row; `ui/InitialTile.kt:66,78`; banners unbounded (`app/ui/AppShell.kt:297-330`) | Same fix. Effects Suspected | al-cal, lead |

**Contrast.**

- `Color.kt` has not changed since the audit. The only theme commit is #122's `TagColors` hex (R2).
- `ContrastTest` (`core/ui/src/test/kotlin/com/vettid/core/ui/theme/ContrastTest.kt:42-64`) covers the same pairs
  as at the audit.
- It still does not cover:
  - `outline` and control boundaries;
  - `success` and `warning`;
  - `error` off the background;
  - `onPrimary`/`primary` and `onError`/`error`;
  - colours with alpha.
- The new features add no hard-coded colour; the only ones are the QR code's black and white
  (`ui/QrCode.kt:89-90`).
- New contrast concerns are the agent data box (A36, which uses `outline`) and the map's circle (A38).

### 2. Offline and poor network (N1–N20)

| ID | Sev. | Status | Evidence | What remains | Owner |
|---|---|---|---|---|---|
| N1 | Blocker | **Fixed** | #187 `ff458d20`: `TransferInResume.decide` in `data/vault/VaultManager.kt` `evaluate`; take-over, never create. `VaultDeviceTest.aTransferKilledAfterTheCodeIsCompletedByALateDevicePaired` and the others listed 10-23 | Phone check 3 (lead) | lead |
| N2 | Blocker | **Fixed** | #185 `304f95ce`: requests deposited from memory, never written to the outbox (`vault/VaultDevice.kt:1268-1283`); `VaultDeviceTest.aRequestThatFailedInTransportIsNeverDeliveredLater` | N11 open | – |
| N3 | Blocker | **Fixed** | #186 `ee5b211d`: `pendingEnroll` → `Setup(ENROLLING)`; `altchan/AltChannelFlow.kt:262-275`; `EnrolmentResumeTest`, `OnboardingViewModelTest.anOutstandingEnrollmentAfterARestartIsResumedWithoutANewPin` | N8 open | lead (phone 2.2, 4.3) |
| N4 | Public | **Fixed** | #122; `MemberApiFreshSignatureTest` | – | – |
| N5 | Public | **Partly** | 401 with skew is `CLOCK` (`data/vault/VaultManager.kt:4938`); Unreachable has a clock title (`app/ui/UnreachableScreen.kt:129`) | Relay `timestamp_stale` still `NETWORK` (`data/vault/Guard.kt:67`); a stale attestation is `ENCLAVE` (`Guard.kt:84-91`); vault time drops; no skew from `Date` outside the 401 path | al-cal |
| N6 | Public | **Fixed** | #122: `Unreachable(NO_VAULT)` with a confirmed Start over, no key drop (`VaultManager.kt:1895`, `app/ui/UnreachableScreen.kt:144`); `UnreachableScreenTest`, `RootViewModelTest` | – | – |
| N8 | Public | Open | `altchan/MemberApiClient.kt:379-387` `while (true)` with optional `atMost`; only the lock passes it (`altchan/AltChannelFlow.kt:387`); unlock unbounded (`:339`); `onStarting` only in `data/vault/ReleaseUpdate.kt`; no Cancel in `onboarding/UnlockViewModel.kt:472-474` | Deadline, Cancel; the "Starting your vault…" text is new (**owner**) | al-cal, owner (text) |
| N9 | Public | Open | `refresh()` publishes `Unreachable` on any `VaultFailure` (`VaultManager.kt:1792-1806`); hourly from `notify/ConnectionKeeper.kt:70` | Keep the phase on network/5xx | al-cal |
| N10 | Public | **Fixed** (fail secure) | #127, #140, #181; `LockKeeperTest.aPendingLockSurvivesTheProcess` | – | – |
| N11 | Public | Open | No client id on `message.send` / new `item.put` | Needs the spec (**owner** / vault) | owner |
| N12 | Public | Open | Deposits inside `lock.withLock` (`vault/VaultDevice.kt:1187,1227,1353,1379,1384,1941,1946`); `handle()` same mutex (`:1530`) | M | al-cal |
| N13 | Polish | Open | `relay/RelayClient.kt:254,262` `StrictJson.parseObject`; `CryptoException` is not an `IOException`; collector and `flushOutbox` (`vault/VaultDevice.kt:1354`) catch `IOException` only | Rethrow as `IOException` | al-cal |
| N14 | Polish | Open | `ConnectivityGate` only in `app/di/AppModule.kt:137-138` | Offline banner (new text, **owner**) | al-cal, owner |
| N15 | Polish | Open | Every `RelayException` is `NETWORK` (`Guard.kt:67`); `stale_credential`, `utk_invalid` → `NO_RESPONSE` (`VaultManager.kt:4982`) | – | al-cal |
| N16 | Polish | Open | `hs.fin` deposit failure swallowed and acked (`vault/VaultDevice.kt:1554,1739`); code is stored first since N1 | Retry the deposit | al-cal |
| N17 | Polish | Open | `credential.ack` failure throws (`vault/Credential.kt:83`) | Best effort | al-cal |
| N18 | Polish | **Fixed in effect** | #159 `e8a3c1e2`; `MoveMarkersTest`, `UnlockReturnTest` | – | – |
| N19 | Polish | Open | `onboarding/TransferInViewModel.kt:198` and `VaultManager.kt:508`; claim fetched per attempt (`vault/VaultDevice.kt:961`) | – | al-cal |
| N20 | Polish | Open (S) | `READ_TIMEOUT_S = 70`, no call timeout (`app/di/AppModule.kt:109,152`) | Phone check 4.7 | lead |

### 3. Error states (E1–E24)

| ID | Sev. | Status | Evidence | What remains | Owner |
|---|---|---|---|---|---|
| E1 | Blocker | **Fixed** | #121 `00f13564`; `RecoverLeaveScreenTest`, `RecoverViewModelTest` | – | – |
| E2 | Public | **Partly** | #173/#176: a failed list keeps what was known. #225 `838fedd2`: `pending.list` failure shows `OpenAsksNotice` with Try again (`approvals/ApprovalsScreen.kt:202,212,253-264`); `PendingListTest` | `grant.list`, `critical_use.list` and pending shares still swallow failures (`data/social/SocialManager.kt:1462-1487`); with approvals memory-only (#204), after a restart they read "Nothing to approve" | al-cal |
| E3 | Public | Open | `items/SharingScreens.kt:168,186,240-246,391,405-409`; `items/TagsScreen.kt:324-341`; new-message picker never renders `state.error` (`messages/MessagesScreen.kt:634-660`); "Say hello" + OK-only card (`:422-426,441-451`) | A failed state with retry each. Some may need texts (**owner**) | al-cal |
| E4 | Public | **Fixed** `095cff60` | `app/ui/PendingDeletion.kt:32-44` `catch (_: VaultFailure) { // the banner stays }` | Say it with `failureText(kind.messageRes())` | al-cal |
| E5 | Public | Open (V/S) | `ui/QrScanner.kt` calls back per frame; guards are `busy` only (`onboarding/OnboardingViewModel.kt:310-312`, `onboarding/RecoverViewModel.kt:324-326`, `onboarding/TransferInViewModel.kt:172-173`) | Ignore the last refused text | al-cal |
| E6 | Public | **Partly** | #118 `ea070bd3`: `RELEASE_UNKNOWN`, `MANIFEST`, `ENCLAVE` split (`Guard.kt:84-91`) | `AttestationException.Stale` still `ENCLAVE`, not a clock hint | al-cal |
| E7 | Public | **Mostly fixed** | #142 `600a2f5d`; `UnlockStuckTest`, `StuckScreensTest` | No Try again for a transient rollback (`onboarding/UnlockScreen.kt:100`); Unreachable titled "cannot connect" for most kinds (`app/ui/UnreachableScreen.kt:128-134`) | al-cal |
| E8 | Public | **Partly** | #161 `26670193`: unmapped vault codes shown with the generic text (`items/ItemsText.kt:121-122`) | Local failures still "Your vault refused this item…" (`:123`) | al-cal |
| E9 | Public | Open | `items/ItemsViewModels.kt:512-521`; empty draft under "Not saved", Save enabled (`items/ItemEditScreen.kt:209-250`) | Retry, Save disabled | al-cal |
| E10 | Public | Open (trigger S) | `vaultGuard` (`Guard.kt:33-79`) has no `SerializationException` branch; `vault/VaultJson.kt:35` | Same fix | al-cal |
| E11 | Public | Open | `VaultManager.kt:2703,2705`; `onboarding/RecoverViewModel.kt:569-571` | Same fix | al-cal |
| E12 | Polish | **Partly** | Vault state translated (`settings/SettingsDetailScreens.kt:177-182`); "Release %1$d is %2$s" gone | Raw `%1$s` in onboarding strings `:151,:338`; recovery state (`settings/SettingsDetailScreens.kt:561`); "Verified"/"SelfSigned" (`VaultManager.kt:4915-4920`) | al-cal, owner (texts) |
| E13 | Polish | Open | No `retryAfter` in `settings/SettingsViewModels.kt`, `credential/CredentialViewModels.kt`, `credential/NewCredential.kt` | Same fix | al-cal |
| E14 | Polish | Open | Owner decision (a) kept the wording; forms still clear secrets | Owner's | owner |
| E15 | Polish | Open | `history/HistoryExportViewModel.kt:325`; `VaultManager.kt:4981`; `settings/TransferOutViewModel.kt:157-165` | Same fix | al-cal |
| E16 | Polish | Open | `connections/ConnectionDetailScreen.kt:166-170` blank on fetch failure; History `chainBroken` only OR-ed (`history/HistoryViewModels.kt:355`, `history/HistoryPager.kt:103,138`) | Same fix | al-cal |
| E17 | Polish | **Partly** | #280 `d4f62c63`: Messages shows a retry notice over rows (`messages/MessagesScreen.kt:259-268`); `MessagesLoadedTest` | Items only when empty (`items/ItemsScreen.kt:189`); Connections an OK-only card over rows (`connections/ConnectionsScreen.kt:445-455`) | al-cal |
| E18 | Polish | **Fixed** | #139, #141, #164; `WipeRunTest` | – | – |
| E19 | Polish | Open | Canary read silent (`app/MainActivity.kt:203-214`); "Turn hold on" swallowed (`app/ui/OwnerCheckGate.kt:143-151`); backup-off warning hidden (`settings/SettingsViewModels.kt:324-328`); dry run (`items/ItemsViewModels.kt:811`) | Same fix | al-cal |
| E20 | Polish | Open | `onboarding/RecoverViewModel.kt:279-290` | – | al-cal |
| E21 | Polish | **Fixed** | #137 `37461836` removed the stale line | – | – |
| E22 | Polish | **Partly** | #271 `49ac89ec` reworded BLOCKED (`data` strings `:49`) | The app offers no unblocking, so it still cannot be acted on; TERMS_REQUIRED has no link (`:6`) | owner (text) |
| E23 | Polish | Open | "Nothing was found.", "Rejected on your phone." (`data` strings `:28,:52`) | Texts (**owner**) | owner |
| E24 | Polish | Open (leave) | 286 `runCatching` sites; none found harmful | Prefer a cancellation-safe helper in new code | – |

**Empty states, updated.** The changes since the audit:

- **Conversations:** with rows, a failed read shows a notice with retry above them, or "Messages are on hold" while
  the check is held (#280, #270).
- **History:** "History is on hold" while held (#270).
- **Approvals:** partly fixed (E2).
- **Unchanged:** a conversation, the new-message picker, tags, sharing rules and shared with you (E3); Items with
  rows (E17).
- **New lists:**
  - **Votes:** a failed read shows a retry, and so does a refresh over rows. That notice is not announced (A29).
  - **Devices:** retry when it holds more than this phone. "No agents yet" hides a failure (E29).
  - **Location:** "No location sharing" hides a failure (E35).
  - **Wallet list and Transactions:** retry. A failed refresh hides the transactions (E39).

**Appendix A of the audit is out of date.**

- New kinds: `CLOCK`, `NO_VAULT`, `LOCAL_STATE`, `ERASE_FAILED`, `RELEASE_UNKNOWN`, `MANIFEST_UNAVAILABLE`,
  `ENCLAVE` and `SERVICE_FORBIDDEN`.
- `ATTESTATION` now means only the vault refusing the phone.
- `OTHER` shows unmapped vault codes (#161).
- Still wrong:
  - `NO_RESPONSE` for `stale_credential`;
  - `NOT_SUPPORTED` for `ttl_not_allowed`;
  - the `REJECTED` and `NOT_FOUND` texts.

---

## New findings

These cover the screens added since 2026-10-11:

- Votes;
- Devices & agents: pairing, permissions, agent rules, approvals for devices;
- Wallet;
- Presence;
- Location: share sheet, incoming card, Location screen, position screen, map, map server;
- Service connections;
- History export (re-read);
- the owner check gate;
- units and dates.

### Accessibility

#### Public

| ID | Finding | Evidence | Scenario | Fix | Status | Size | Owner |
|---|---|---|---|---|---|---|---|
| A28 | A ticking countdown sits in a live region (a regression of the A1 fix). | `items/CredentialPassword.kt:115` puts `items_password_backoff` ("…Try again in %1$d s") in `SecretField`'s `error`, which is polite live (`ui/Forms.kt:121`); the same at `history/HistoryExportScreen.kt:317` (`history_export_pin_backoff`). The countdown ticks each second | After too many wrong passwords on a critical item, TalkBack may re-read "Too many wrong passwords. Try again in 41 s" every second for the whole wait | Pass the existing `_undated` text as `error` (said once) and the counting text as `supporting`, as the unlock screen does since #122. No new text | V/S; **Fixed** `e58423ab` | S | al-cal |
| A29 | Failures and status changes in the new screens are not announced (the A1 class). | Devices: `devices/DevicePageScreen.kt:205,252-264` (End access, Remove, Resume, permission remove), `:306-308,364-367`; `devices/DevicesScreen.kt:139-149`. Device requests: plain red text `approvals/DeviceAsksScreen.kt:192-199`. Agent rule: `items/RuleEditScreen.kt:210-226`. Votes: `votes/VoteScreen.kt:340-346,352,631-633`; `votes/VotesScreen.kt:133-143`. Wallet: wrong check word `wallet/WalletScreens.kt:422-427`; Send problems and outcomes `wallet/SendScreen.kt:182-184,383-391,461-473,697-702,719-763`; `wallet/ReceiveScreen.kt:214`; `wallet/WalletDetailScreen.kt:153-164,251-252,369-376`; `wallet/SpendingLimitsScreen.kt:129-138`; `wallet/ChainServerScreen.kt:84-90`; `wallet/ImportScreen.kt:104-110,130-139`; `wallet/TransactionsScreen.kt:207-215`. Location and presence: `connections/PresenceViews.kt:60-65`, `messages/MessagesScreen.kt:690-696`, `connections/LocationViews.kt:212,225-237,463-468,765`, `connections/ShareLocation.kt:326-344,424-465`, `approvals/LocationRequestScreen.kt:124-131,150-153`, `settings/MapServerSettings.kt:142-149`, `settings/PresenceSettings.kt:179-187`, `app/ui/AccountSheet.kt:161-167` | A TalkBack user taps Remove, Allow, Cast, Approve (send) or Stop. The request fails, or the payment is sent, and nothing is spoken. A wrong word ends the wallet backup check silently. On the connection page the error card is at the top (`connections/ConnectionDetailScreen.kt:228-237`), far from a failed switch | `announce = LiveRegionMode.Polite` on these `NoticeCard`s (Assertive for the wallet's `wrong_seed` alarm); a live region on the plain texts and on the presence line. No ticking text is involved. No new text | V; **Partly** `e58423ab`, `37212b31`: the start-list files (onboarding move, device requests, device page, votes, share sheet, location views, wallet send/check) are announced; the rest of the row remains (`DevicesScreen`, `RuleEditScreen`, `VotesScreen`, `ReceiveScreen`, `WalletDetailScreen`, `SpendingLimitsScreen`, `ChainServerScreen`, `ImportScreen`, `TransactionsScreen`, `MessagesScreen:690-696`, `LocationRequestScreen`, `MapServerSettings`, `PresenceSettings`, `AccountSheet`) | S–M | al-cal |
| A30 | Step changes nobody tapped for are silent (A3, inherited by new flows). | No `paneTitle` (A3). Pairing: Code → Compare (`devices/PairScreen.kt:122`) and Code → expired (`:114`) arrive from the agent or the clock. Device request outcome becomes the title (`approvals/DeviceAsksScreen.kt:136-139,323-329`). Wallet check: the next "Which is word n?" changes only the body, and focus stays on the tapped candidate (`wallet/WalletScreens.kt:414,435-443`). Send keeps the title "Send" for every step (`wallet/SendScreen.kt:98,156,174,240,685`). Share sheet form → "Finding your location…" (`connections/ShareLocation.kt:326-344`). The owner check cover clears the shell's semantics (`app/ui/AppShell.kt:275,486-505`) and announces nothing | A TalkBack member waiting for the pairing code hears nothing when the six digits appear | `paneTitle` on `FormScaffold` (with A3); a polite live region on the wallet check's question; a polite title once on the owner check cover. A distinct title per Send step would be new text (**owner**) | V/S; **Partly** `ed891c76`: `paneTitle` done; the wallet check question's live region and the owner check cover's title remain | S | al-cal |
| A31 | The owner check's key layer eats Tab, D-pad, Enter and Space (A17 again, on a gate that is now required). | `app/ui/AppShell.kt:492` focuses the layer; `app/ui/OwnerCheckGate.kt:297-303` `PASSING_KEYS` has only Back, volume, media and call keys | With a hardware keyboard the member cannot move between the check's password field and its buttons. TalkBack and Switch Access gestures are not affected | Let the navigation keys through **and** confine focus to the check. Unlike the app lock, the shell is still composed under the cover, so Tab alone could reach a field beneath and then printable keys would type into it. Not in "fix now" for that reason | V/S | M | al-cal |
| A32 | Two unnamed switches *(wallet)*. | "Include unconfirmed coins" `wallet/SendScreen.kt:452-456`; "Remove this limit" `wallet/SpendingLimitsScreen.kt:223-226` | TalkBack: "Switch, off" | `Row.toggleable(role = Role.Switch)` as `SettingsSwitchRow` | V; **Fixed** `e58423ab` | S | al-cal |
| A33 | The BTC/sats unit is shown by button fill only, and the amount has no unit *(wallet)*. | `UnitButton` swaps `Button`/`OutlinedButton` with no selected state (`wallet/SendScreen.kt:354-378`); label "Amount" (`:343`) | A TalkBack user types 50000 meaning sats while BTC is selected. The review shows the true amount, but this is a money field | Unit suffix as `LimitField` (`wallet/Limits.kt:209`) with the existing `wallet_unit_btc`/`wallet_unit_sats`; `selectable(role = RadioButton)` in a `selectableGroup` | V; **Fixed** `e58423ab` | S | al-cal |
| A34 | The recovery phrase grid may be read across columns *(wallet)*. | Two `Column`s in a `Row`, no traversal group (`wallet/WalletScreens.kt:367-380`) | TalkBack may read "1, 13, 2, 14…"; at 200% "24. word" wraps in a half-width column | `isTraversalGroup` on each column, or one column at a large font scale | V/S | S | al-cal, lead |
| A35 | An agent rule row's spoken name hides its mode and the privacy warning. | `devices/DevicePageScreen.kt:425-431` sets `contentDescription` "%1$s: edit this rule" on a merged row, replacing the row's text: "Asks you each time"/"Allowed automatically", limits, and `core_ui_agent_ways_warning` (`:452-458`) | TalkBack never says that the agent may receive item values | Keep the merged text; move "edit this rule" to `onClick(label = …)` | V/S; **Fixed** `e58423ab` (the existing "%1$s: edit this rule" text is the click label) | S | al-cal |
| A36 | The box that sets an agent's data apart is faint and not a unit for TalkBack. | `approvals/DeviceAsksScreen.kt:245-277`, border `colorScheme.outline` (`:252`; 1.81 / 1.41:1, A18) | Agent-supplied text such as "VettID checked this request" is read as separate plain lines, like app text | Merge label, byte count and data into one node now; the border follows A18 | V; C; **Fixed** `e58423ab` (semantics merge; the border colour stays with A18) | S | al-cal (merge), owner (A18 colour) |
| A37 | The presence "Check again" button can be squeezed to zero width. | `connections/PresenceViews.kt:59-68`: an unweighted `Text` before `IconButton(Modifier.size(48.dp))` in a `Row` | Offline, the line is the full failure sentence and takes the width, and the button meant for that case disappears. At 200% font, the same for a dated line | `Modifier.weight(1f, fill = false)` on the text, or the failure on its own line | V/S; **Fixed** `e58423ab` | S | al-cal |

#### Polish

| ID | Finding | Evidence | Fix | Status | Size | Owner |
|---|---|---|---|---|---|---|
| A38 | Map circle under 3:1 on light tiles in the dark theme. | `map/MapLibreSurface.kt:60,128-139`: `primary` #FFC125 at 20% fill, 1 px line; OpenFreeMap Liberty is light in both themes; #FFC125 on near-white is about 1.6:1 | 2 px line in a darker colour (the gold ink) on the map whatever the theme | C/S | S | al-cal, lead |
| A39 | The map with TalkBack and large text. | Attribution `labelSmall`, up to 200 chars, no `maxLines`, in a fixed 280 dp box (`map/MapLibreSurface.kt:207-217`; `connections/LocationViews.kt:800,839`); "Map showing {name}'s position" set also when the map failed or is loading (`:837-843`); the map takes every drag (`map/MapLibreSurface.kt:227-230`) | Bound the attribution; description only when drawn; a drag handle or two-finger pan | S | S | al-cal, lead |
| A40 | Choice semantics and headings in new code (A21 class). | Missing `heading()`: `items/AgentRuleEditor.kt:58,101,159`, `devices/PairScreen.kt:392`, `wallet/SendScreen.kt:382`, `wallet/ImportScreen.kt:251`, `wallet/ChainServerScreen.kt:73`, `wallet/WalletDetailScreen.kt:353`, `history/HistoryExportScreen.kt:259`. No `selectableGroup`: `items/AgentRuleEditor.kt:66-73`, `devices/PairScreen.kt:398-416`, `wallet/SendScreen.kt:393-419`, `connections/ShareLocation.kt:349-370`. Single choices as `FilterChip`s (read as checkboxes): `items/AgentRuleEditor.kt:160-168`, `wallet/ImportScreen.kt:252-261`, `wallet/Limits.kt:151-164`, `connections/ShareLocation.kt:353-363,386-396`. Address type by bold only (`wallet/WalletDetailScreen.kt:440-455`); Send all as a checkbox-role `selectable` (`wallet/SendScreen.kt:360-367`); transaction row no role (`wallet/TransactionsScreen.kt:106`) | Same fixes as A21/A24/A25 | V | S | al-cal |
| A41 | New dialogs with long bodies do not scroll (A14). | Remove agent (`devices/DevicePageScreen.kt:476-497`), remove permission (`:503-514`), vote Release with PIN field (`votes/VoteScreen.kt:665-683`), Restore (`:628-634`), Ask for location (`connections/LocationViews.kt:205-240`) | With A14's wrapper fix | V/S; **Fixed** `ed891c76` | S | al-cal |
| A42 | Wallet layout and reading *(wallet)*. | Rows that do not wrap at 200%: `wallet/ImportScreen.kt:252`, `wallet/Limits.kt:151`, `wallet/WalletDetailScreen.kt:354-361`. Addresses and txids shown and read as one blob on the review and transactions (`wallet/SendScreen.kt:569,605-610`; `wallet/TransactionsScreen.kt:282,302`), while Receive groups them (`wallet/ReceiveScreen.kt:176-182`). Unlabelled spinners (`wallet/WalletScreens.kt:140`, `wallet/WalletDetailScreen.kt:99`, `wallet/ReceiveScreen.kt:92`, `wallet/TransactionsScreen.kt:72-74,252`, `wallet/SpendingLimitsScreen.kt:91-93`); Send's loading state is a blank form (`wallet/SendScreen.kt:97-103`) | `FlowRow`; group addresses with a grouped description; a loading label (check `core_ui` for an existing one) | V/S | S | al-cal |
| A43 | Abbreviations and codes read badly. | "%1$d h", "%1$d d", "%1$d min", "30 s", "%1$d m" (meters or minutes?) in `core/data` strings `:301-305`; ages without "ago": list glance (`connections/LocationViews.kt:281`), Location rows (`:486-492`), incoming card ("{First}'s location · 5 min · within 50 m"); `ik` fingerprints read as numbers, label and value separate (`ui/ServiceRequest.kt:41-47`, `connections/ConnectionDetailScreen.kt:393-398`) | `TtsSpan` or plural full-word descriptions; character-by-character description for fingerprints as `SafetyCode`. "Updated … ago" variants are new texts (**owner**) | S | M | al-cal, owner (texts) |
| A44 | Copy gives no in-app or spoken confirmation. | `devices/PairScreen.kt:253-256` (text swap, no live region); `wallet/ReceiveScreen.kt:184`, `wallet/TransactionsScreen.kt:304` (nothing before Android 13's overlay); vote "Check with the service" has no busy state (`votes/VoteScreen.kt:318-331`) | Live region on the swap; "Copied" for the wallet is a new text (**owner**) | V | S | al-cal |
| A45 | Two time formats on one screen. | `ui/../format/Times.kt:15-18` `ofLocalizedTime(SHORT)` ignores the phone's 24-hour setting; used for "Access allowed until …" (`approvals/DeviceAsksScreen.kt:337`) and the request expiry (`approvals/LocationRequestScreen.kt:113`); `data/location/ShareTime.kt:15` honours it. Fixed `"%d:%02d"` countdowns remain (`devices/PairScreen.kt:217`, `onboarding/UnlockScreen.kt:73`, `settings/TransferOutScreen.kt:63`, `settings/ProfileScreens.kt:507`, `onboarding/RecoveryOpen.kt:816`, `calls/CallTexts.kt:61`) | `DateFormat.is24HourFormat` in `Times` | V | S | al-cal |
| A46 | British compass words. | "north-east", "south-east", "south-west", "north-west" (`core/data/src/main/res/values/strings.xml:316-322`); `AmericanEnglishTextTest` does not catch them | "northeast"…, and add them to the test. The texts are NEW (not approved). The owner's rule of 2026-11-05 already covers spelling | V; **Fixed** `095cff60` (texts and guard test) | S | al-cal |
| A47 | Distances were metric only, also for en-US. **Resolved after this audit:** #292 (`144823ad`, owner decision 2026-11-05): Settings → Units, US/LR/MM miles and feet by default, everything else kilometers; every distance follows it. | `data/location/PositionText.kt`, `LengthSystem.kt` | Done | V | M | — |

### Offline and poor network

| ID | Sev. | Finding | Evidence | Scenario | Fix | Status | Size | Owner |
|---|---|---|---|---|---|---|---|---|
| N21 | Public | A location share whose positions never get through is never reported. | `data/location/LocationSharing.kt:864-874` `deliver()` catches every failure and returns false ("one position missed"). The share sheet's "Finding your location…" has no deadline (`connections/ShareLocation.kt:153,326-344`); the card keeps "Sharing your location until …"; Share once from Approvals closes as done after `start` (`approvals/ApprovalsViewModels.kt:638-639`); a once share that never got through reads "No position was found, so nothing was sent." (`LocationSharing.kt:376-382`) | No network, or the relay down, after a share starts: for up to 7 days the member believes the person sees them | A `SendIssue` (e.g. `NOT_DELIVERED`) after a few failed sends or about 60 s, on the card, the notification and the sheet; a deadline on "Finding…"; a missed-once text that does not blame the position. New texts (**owner**) | V | M | al-cal, owner |
| N22 | Public | Cancel in the vote password prompt works while the cast is being sent. | `votes/VoteScreen.kt:587` (sheet dismiss, no SENDING guard), `:614` (Cancel has no `enabled`); `votes/VoteViewModel.kt:203` | On a slow network the member taps Cancel. The screen returns to choosing with Cast enabled while `vote.cast` is in flight; the vote may be recorded; a second Cast meets `already_voted` | As `ConfirmSheet` (`:545`) and `items/AgentRuleEditor.kt:301`: no dismiss and Cancel disabled while SENDING | V; **Fixed** `37212b31` | S | al-cal |
| N23 | Polish | The 20 s recent-payments wait can run to about 50 s *(wallet)*. | `withTimeoutOrNull` (`wallet/SendViewModel.kt:237-239`) around blocking `execute()` (`chain/Esplora.kt:364-368`, call timeout 30 s at `:426`) | Review stays off and "Checking your recent payments…" has no progress | A cancellable `call()` (`enqueue` plus `call.cancel()`) | V/S | M | al-cal |
| N24 | Polish | Long silent waits while Back is swallowed *(wallet)*. | `swallowsBack` (`wallet/SendViewModel.kt:138`); `checkSent` is `NonCancellable` (`core-wallet/WalletsManager.kt:1765-1793`), up to about 2 min; approve is unlock, sign and broadcast at 30 s each | Only a small spinner in the button. Bounded and deliberate for payment safety | A visible, announced progress line ("Signing…", "Sending…"). New texts (**owner**) | V/S | S | al-cal, owner |
| N25 | Polish | Remove and End access give no progress. | `devices/DevicesViewModels.kt:296-304` reads grants before the dialog; `devices/DevicePageScreen.kt:142-147` pill neither busy nor disabled; `devices/DevicesViewModels.kt:338-363` | On a slow link a tap does nothing visible for seconds; further taps are ignored | Busy state on the pill and the access card | V | S | al-cal |
| N26 | Polish | Presence and location busy states. | `settings/PresenceSettings.kt:143-156` (rows disabled, no indicator); presence card Share / Not now have no busy state, so a double tap sends twice (`connections/ConnectionsViewModels.kt:193-200`, `connections/PresenceViews.kt:132-139`); Ask only disabled (`connections/LocationViews.kt:247`); avatar sheet Status row busy by 0.38 alpha only and still clickable (`app/ui/AccountSheet.kt:153-158`) | Busy state and a guard against the double send | V | S | al-cal |
| N27 | Polish | The map offline. | A failed style load says "…Check the map server in Settings." (`map/MapLibreSurface.kt:170,202-205`), with no Try again and no announcement; tiles that fail after the style raise no event (S); the box is empty while the SDK prepares (`:75`) | A network-neutral text and Try again. New text (**owner**) | V/S | M | al-cal, owner |

### Error states

| ID | Sev. | Finding | Evidence | Scenario | Fix | Status | Size | Owner |
|---|---|---|---|---|---|---|---|---|
| E25 | Public | "Is this your vote?" is a dead end offline. | `votes/VoteScreen.kt:180` shows it while `restoreAsked` (`votes/VoteViewModel.kt:100`: `confirmPending` and a label); `onDismissRequest = {}` (`votes/VoteScreen.kt:626`); both answers need the vault; `restoreDialog` (`votes/VoteViewModel.kt:68,312`) is never read | Offline, Yes and No fail with `restoreProblem`; Back and the scrim do nothing; the member is stuck until the network returns. Suspected also after a good answer whose quiet re-read fails | A "Back" button with the existing `vote_confirm_back` that closes the dialog for this visit, or let Back dismiss it | V; **Fixed** `37212b31` | S | al-cal |
| E26 | Public | A vote that cannot be read shows an empty page. | `votes/VoteViewModel.kt:163-166,190` set `loadError`; `votes/VoteScreen.kt:151-174` shows it only when `summary == null`; `VoteContent` (`:195-356`) never reads it | The list is in memory, the network drops, the member opens a vote: a title, no choices, no Cast, no error | In `VoteContent`, with `detail == null && loadError != null`, the same `NoticeCard` with Retry. Existing texts | V; **Fixed** `37212b31` | S | al-cal |
| E27 | Public | The share sheet is a dead end while the vault's location support is unknown. | `connections/ShareLocation.kt:195-206` (`open()` reads while UNKNOWN), `:470` (`enabled = !busy && state.available`), error card without actions `:452-457` | Offline at unlock and at the sheet: Start sharing stays disabled with no Try again and no reason while the read runs | Try again (existing `connections_retry`) calling `open()`; a progress row while reading | V; **Partly** `e58423ab` (Try again); the progress row needs a text and remains | S | al-cal |
| E28 | Public | Action failures are titled "Could not load connections". | `connections_error_title` at `connections/ShareLocation.kt:454`, `connections/LocationViews.kt:420,742`, `connections/ConnectionDetailScreen.kt:233`, `connections/ConnectionsScreen.kt:447-454` (presence card) | A failed Stop sharing or presence switch says connections could not be loaded | A neutral or per-action title. New text (**owner**) | V | S | al-cal, owner |
| E29 | Polish | The devices list hides a failed refresh behind "No agents yet". | `devices/DevicesScreen.kt:124-136` takes `onlyThisPhone` (`devices/DevicesViewModels.kt:45`) before reading `state.error`; the error exists only in the list branch (`:139`) | Show the error card above the empty state. Existing texts | V | S | al-cal |
| E30 | Polish | A failed re-read of "What it may use" is hidden. | `devices/DevicePageScreen.kt:305-316` shows it only `if (!state.rulesLoaded)` | As the permissions card (`:360-367`) with the existing `devices_rules_failed` | V | S | al-cal |
| E31 | Polish | A held request says "an item you no longer have" when the item cache failed. | `approvals/ApprovalsViewModels.kt:378` ignores `items.refresh()`'s result; `:80-81` counts a FAILED load as settled; `approvals/DeviceAsks.kt:84-95` → `NoItem` | Fails safe (Deny only), but the sentence is false and there is no retry within 5 min. A "couldn't load your items" state is new text (**owner**) | V | S | al-cal, owner |
| E32 | Polish | The rule editor's preview is stale after a failed dry run. | `items/SharingViewModels.kt:506-508` keeps the old `preview`; `items/RuleEditScreen.kt:571-572` | Clear it on failure and say so | V | S | al-cal |
| E33 | Polish | A failed Stop says "try again" with nothing to retry. | `data/location/LocationSharing.kt:279-300` marks stopping, retries every 5 s and throws; `connections/LocationViews.kt:342-355,461-468` shows "Stopping…" (disabled) beside "Check your connection and try again" | "Stopping. VettID keeps trying." New text (**owner**) | V | S | al-cal, owner |
| E34 | Polish | Presence settings can spin for good. | `settings/PresenceSettings.kt:179-195`: with no policy and a failed read the card offers only Done, which leaves a spinner | Try again instead of Done | V (reach S) | S | al-cal |
| E35 | Polish | The Location screen says "No location sharing" while its list cannot be read. | `connections/LocationViews.kt:398-404` | An unreadable state with Try again. New text (**owner**) | V/S | S | al-cal, owner |
| E36 | Polish | "Open settings" fails silently (the E19 class). | `runCatching { context.startActivity(intent) }` at `connections/ShareLocation.kt:446,583`, `approvals/LocationRequestScreen.kt:156` | One safe helper with a fallback text | V | S | al-cal |
| E37 | Polish | Vault strings shown verbatim *(wallet)*. | `invalid_psbt` reason is the error `message` (`core-wallet/WalletsManager.kt:2016-2020`), shown uncleaned in `wallet_psbt_other` and under Details (`wallet/SendScreen.kt:511,518-521`); raw `cap.reason` (`wallet/SendEngine.kt:154`); an unknown `cap_exceeded` reason renders "(other)" (`wallet/SendViewModel.kt:611-613`, `wallet/SendScreen.kt:545`) | Clean, isolate, cap (as Approvals does); a variant without the parenthesis is new text (**owner**) | V | S | al-cal, owner |
| E38 | Polish | One message for every chain-server failure *(wallet)*. | `wallet/ChainServerViewModel.kt:118-121`, `wallet/WalletDetailScreen.kt:309-313`: redirect, 404, TLS and malformed are all "That server didn't answer." | Split by `ChainError`. New texts (**owner**) | V | S | al-cal, owner |
| E39 | Polish | Transactions: a failed refresh hides the list, and Try again gives no feedback *(wallet)*. | `wallet/TransactionsViewModels.kt:68-81` sets `list = null` and keeps `problem` while loading; `wallet/TransactionsScreen.kt:69-71`; Send's block Try again (`wallet/SendViewModel.kt:276-283`, `wallet/SendScreen.kt:152-165`) | Keep the list; clear the problem or show busy on retry | V | S | al-cal |
| E40 | Polish | Spending limits can spin for good *(wallet)*. | Opened from a notification before the list was read, a failed read is swallowed (`wallet/SpendingLimitsViewModel.kt:131-138`); availability stays UNKNOWN (`:110-117`, `wallet/SpendingLimitsScreen.kt:91-93`) | A failed state with `FailureKind` text and Try again | V/S | S | al-cal |
| E41 | Polish | "Raise or remove a limit" looks enabled but does nothing while not loaded *(wallet)*. | `wallet/SpendingLimitsScreen.kt:84-86` | Disable it | V | S | al-cal |

Also noted and not given a new ID:

- **History export:** nothing changed for A1, A3, R8 or E15 since `4e793592`. Its
  Failed step offers only Close, with no Try again for a network failure (`history/HistoryExportScreen.kt:178-191`).
- **"Turn hold on"** from the hold-off banner is still swallowed (E19).
- **Connection detail** is still blank on a failed fetch (E16).

### Checked and sound

- **Labels and targets.** Icon-only controls in the new screens are labelled:
  - the device page's ⋯ per permission;
  - wallet ⋯, copy icons and the FAB;
  - the presence refresh;
  - the location glance icon;
  - the QR codes, which have descriptions and `Role.Image`.

  New text buttons and rows are at least 48 dp.
- **Pairing.**
  - The six digits are spoken one by one (`devices/PairScreen.kt:351-375`).
  - The countdown's live region changes once a minute, not every second (`:224-228`).
  - Leaving the screen rejects the pairing.
- **Votes.**
  - Choices are a `selectableGroup` with `Role.RadioButton`.
  - The notes are polite live.
  - States are words.
  - A cast with no answer has its own text.
- **Device requests.**
  - Session lengths are a `selectableGroup` (`approvals/DeviceAsksScreen.kt:220-235`).
  - Remove falls back to the worst-case time when grants cannot be read.
- **Presence.**
  - The per-person switch is a named `toggleable` (`Role.Switch`); settings use radio groups and headings.
  - A query ends by `exp`, so "Checking" never stays.
  - Failed is told apart from Unknown.
- **Location.**
  - The position screen has Try again.
  - None, waiting and position are distinct.
  - Nothing is shown while the check is held.
  - The map server form checks locally.
  - `ShareTime` follows 12/24 h.
  - Coordinates use `Locale.ROOT`.
- **Map.**
  - Nothing is fetched before the note is answered.
  - The SDK set-up times out to a failed state.
  - The attribution's contrast holds (surface at 0.9 alpha).
  - The dot is drawn for Exact only.
- **Wallet.**
  - Wrong PIN and password are announced through `SecretField`.
  - Nothing is conveyed by colour alone ("+"/"−" and words).
  - Sats use the locale's grouping.
  - Every chain and vault wait is bounded.
  - Broadcast failures name the host and tell "no answer" from "refused".
  - The phrase screens stay readable by TalkBack while out of capture.
- **Owner-check cover.**
  - The undecided frame clears semantics and takes touches.
  - The held lists (#270) show an INFO notice distinct from the error, with and without rows.
- **Services.**
  - "Service" is a word plus an icon.
  - The Services header is a heading.

---

## What to fix now

These are the Blocker and Public items that are small (S), and safe to do in app code without an owner decision or a
new text. There are no Blockers. All are **al-cal**'s.

*Status (branch `a6-closeout`): done in `ed891c76` (core/ui), `e58423ab` (features), `37212b31` (votes) and `095cff60`
(app, texts); each finding's Status cell says what is fixed and what remains. Not done in full: the A29 files beyond the
start list, A1/A16's other silent texts, A15's safety code, A8's selectable fingerprints, E27's progress row.*

1. **A28.** Move the ticking backoff out of the live error:
   - `items/CredentialPassword.kt:115-116` and `history/HistoryExportScreen.kt:317-318`;
   - `error` = the existing `items_password_backoff_undated` / `history_export_pin_backoff_undated`;
   - `supporting` = the counting text.
2. **A3, A30.** `paneTitle = title` on the `FormScaffold` root (`ui/Forms.kt:235-283`). This covers onboarding,
   transfers, pairing, the device request result, History export and the share sheet at once.
3. **A1, A16, A29.** Add `announce = LiveRegionMode.Polite`, or a polite live region on the plain text, to the
   failure and outcome texts listed in A1 and A29. Never on a ticking text. Start with:
   - `onboarding/MoveScreens.kt:129-138,377-384`;
   - `approvals/DeviceAsksScreen.kt:192-199`;
   - `devices/DevicePageScreen.kt:205`;
   - `votes/VoteScreen.kt:340-346,631-633`;
   - `connections/ShareLocation.kt:452-457`;
   - `connections/LocationViews.kt:212,225-237`;
   - `wallet/SendScreen.kt:697-763`;
   - `wallet/WalletScreens.kt:422-427`.
4. **A5.** Make Next move focus down in `SecretField` (`ui/Forms.kt:134`).
5. **A9.** `toggleable(role = Role.Switch)` on `ExistingSwitch` (`items/RuleEditScreen.kt:476-487`).
6. **A32** *(wallet)*. The same for `wallet/SendScreen.kt:452-456` and `wallet/SpendingLimitsScreen.kt:223-226`.
7. **A17.** Let Tab, the D-pad keys, Enter and Space through the app-lock layer (`app/ui/AppLockGate.kt:57-58`).
   This is safe there because the lock screen is composed instead of the content. A31, the owner check, is
   deliberately not in this list.
8. **A33** *(wallet)*. On the amount field (`wallet/SendScreen.kt:343,354-378`):
   - a unit suffix with the existing `wallet_unit_btc` / `wallet_unit_sats`;
   - radio semantics on the unit buttons.
9. **A35.** "edit this rule" as an `onClick` label, not a content description (`devices/DevicePageScreen.kt:425-431`).
10. **A36** (semantics only). Merge the data box into one node (`approvals/DeviceAsksScreen.kt:245-277`).
11. **A37.** `weight(1f, fill = false)` on the presence line (`connections/PresenceViews.kt:59-68`).
12. **A8.** No line limit on info rows; fingerprints selectable (`ui/Settings.kt:203,217`).
13. **A15.** `heightIn(min = …)` for the drawer row and the top bars (`ui/Drawer.kt:110`, `ui/TopBars.kt:65`,
    `ui/TopBarSearch.kt:89`).
14. **A14, A41.** `verticalScroll` in the `AlertDialog` wrapper's text slot (`ui/ShellOverlays.kt`) and in
    `ConfirmDialog` (`ui/ConfirmDialog.kt:35`).
15. **A12.** Give the sending spinner the send button's existing label (`messages/MessagesScreen.kt:623-624`).
16. **N22.** No dismiss and Cancel disabled while SENDING (`votes/VoteScreen.kt:587,614`;
    `votes/VoteViewModel.kt:203`).
17. **E25.** Close "Is this your vote?" with the existing `vote_confirm_back` (`votes/VoteScreen.kt:626`).
18. **E26.** Show `loadError` with Retry inside `VoteContent` (`votes/VoteScreen.kt:195-356`).
19. **E27.** Try again with the existing `connections_retry` on the share sheet's error card
    (`connections/ShareLocation.kt:452-457`).
20. **E4.** Say a failed "Cancel deletion" with `failureText(kind.messageRes())` (`app/ui/PendingDeletion.kt:32-44`).

**Public items that need the owner first:**

- **A18** (the `outline` colour; A36's border follows it);
- **A2, A7, A13** (texts);
- **A11** (window length);
- **N8** (the "Starting your vault…" text);
- **N21** (location send-failure texts; the most consequential new finding);
- **E28** (a neutral title);
- **N11** (spec).

**Public items that are larger (M):** A10, A31, N9, N12, N21, and E2's remaining lists.

---

## Needs a real phone

For the **lead**. Phones: the Pixel 10 Pro (GrapheneOS) and the Pixel 7 (stock), with a staging build
(`com.vettid.app.staging`; the wallet is in staging) and a staging vault. Each step names the finding it settles.
Write down what TalkBack says, word for word.

### TalkBack

1. **Critical item, wrong password until the backoff.** Listen for 15 s. Repeated "Try again in N s" every second
   confirms A28. Repeat with History → Export → wrong PIN.
2. **Unlock, wrong PIN; owner check, wrong password.** Is the error spoken once? This confirms the A1 and A4 fixes.
3. **Transfer in, "Compare the code".** Listen for 15 s. Repeated wait text confirms A2.
4. **Pair an agent** (Devices → +). Wait for the agent to send its code with focus on the QR. Silence when the six
   digits appear confirms A30/A3. Are the digits read one by one?
5. **Device page of an agent.** Swipe through a rule row. Is "Allowed automatically…" and the warning read (A35)?
   Then Remove with the network off. Is the failure spoken (A29)? Is there feedback while it waits (N25)?
6. **A held approval with data** (needs an agent). Is the data box read as one unit, and can you tell it is the
   agent's text (A36)?
7. **Votes.**
   1. Cast with a wrong password: spoken?
   2. Tap Cancel during sending on a slow link (N22).
   3. With the network off, open a vote whose list was read: empty page (E26)?
   4. Reach "Is this your vote?" offline: can you leave it (E25)?
8. **Share my location**, then airplane mode. Does the card or the sheet ever say nothing is getting through (N21)?
   What does TalkBack say on the swap to "Finding your location…" (A30)?
9. **Presence line** on a person's page with the network off. Is the "Check again" button still there and reachable
   (A37)?
10. **The map** (Show map). What is spoken for the map? Can you scroll the page past it with one finger (A39)?
11. **Wallet (staging).**
    1. New wallet: does TalkBack read the phrase in order 1, 2, 3… (A34)?
    2. The check with one wrong word: spoken (A29)?
    3. Send: what is said for the BTC/sats buttons and the amount (A33)?
    4. "Include unconfirmed coins" (A32).
    5. Send to a bad server: is the outcome spoken (A29)?
12. **Owner check due**, with a hardware keyboard. Can you Tab to the password field and Enter to confirm (A31)? Can
    focus reach anything under the cover?
13. **App lock with a hardware keyboard.** Esc on the prompt, then Tab and Enter (A17).
14. **Rule editor (agent and connection).** Find the existing-rule switch (A9).

### Font scale 200% and largest display size (Pixel 7)

1. The drawer, the top bars and the safety code (A15).
2. Dialogs:
   - Remove agent, Remove permission;
   - vote Release with its PIN field;
   - Ask for location with the keyboard up;
   - the backup-off checkbox.

   Is the confirm or the field clipped (A14, A41)?
3. The presence line offline and with a dated "last seen" (A37).
4. The position screen with the map: how much of the map does the attribution cover (A39)?
5. Wallet:
   - the network chips on Import;
   - the limit chips;
   - Testnet4/Testnet3;
   - the phrase grid.

   Clipped or squeezed (A42, A34)?
6. Settings → Vault → Attestation fingerprints (A8).

### Contrast (dark and light)

1. A switch that is off; an empty text field's border (A18).
2. The agent data box border (A36).
3. The map circle in the dark theme over light tiles (A38).

### Offline and slow network

1. Airplane mode, then:
   - Devices list with only this phone (E29);
   - an agent page after a return (E30);
   - Location drawer screen (E35);
   - Presence settings, then Done (E34);
   - Spending limits from a notification (E40).

   Note each text.
2. Throttle the network (or a bad chain server): Send → note how long "Checking your recent payments…" lasts
   (N23), and how long the approve can hang with Back swallowed (N24).
3. Map with the network off after Show map: the text and whether there is Try again (N27).
4. The audit's own section 4 steps for N8, N9 and E2/E3 still apply.

### 24-hour time

Set the phone to 24-hour. Open a device access request and allow it; open a location request. Note whether "until …"
and the request expiry use 12-hour with AM/PM while the share times use 24-hour (A45).

---

## Owners at a glance

| Owner | Items |
|---|---|
| **al-cal** | Everything in "What to fix now"; then the remaining A, N and E items marked al-cal above, the M items first (A10, A31, N9, N12, N21 code, E2) |
| **lead** | The phone checklist above, and the audit's checklist items for N1, N3, N8 and N20 |
| **owner** | The `outline` colour (A18, A36); the transfer windows (A11); new texts for A2, A7, A13, A20, A24, A43, A44, N8, N14, N21, N24, N27, E3, E12, E22, E23, E28, E31, E33, E35, E37, E38; the spec question N11; E14's wording |
