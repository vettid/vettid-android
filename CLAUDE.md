# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

The VettID Android app, **rewritten from scratch in 2026** (phases A0–A2 done:
skeleton, design system, CI; crypto, keystore, attestation; relay, alternate
channel and vault client against a local dev stack). The plan and decisions are in the vettid.org repo:
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
- `./gradlew :app:assembleDebug :app:assembleDevStack testDebugUnitTest :core:crypto:test :core:relay:test detekt :app:lintDebug :app:assembleRelease`
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
  vettid-vault's integration stack plus a vaultctl peer, one LocalStack container
  (≤ 1.5 GB), reachable from the phone through `adb reverse` (ports 18080–18082).
  Tear it down when done. The A2 exit test runs against it:
  `ANDROID_SERIAL=<serial> ./gradlew :core:data:connectedDebugAndroidTest`; the JVM
  variant is `:core:vault:testDebugUnitTest --tests '*DevStackJvmTest*'`. Both skip
  without the stack. Never modify the vettid-vault checkout: the script runs a snapshot.
- `:core:testing` is TEST ONLY (dev-stack helpers, the TEST attester); only test
  configurations and the debug-only `devStack` build type depend on it.

## Device testing

Debug builds use application id `com.vettid.app.dev` (`devStack`:
`com.vettid.app.devstack`); never install over `com.vettid.app`. Screenshot launch extras (debug only):
`adb shell am start -S -n com.vettid.app.dev/com.vettid.app.MainActivity --es vettid.theme dark|light --es vettid.start gallery|messages|connections|approvals|items|credential|settings|help --ez vettid.screenshot true`
(`vettid.screenshot` lets the debug app draw over the keyguard of a locked test phone; the phone stays locked).
Screenshots and the Proton reference images stay out of git (`local/`, or the
vettid.org repo's `local/android-ui/`).

## Conventions

- Kotlin, Compose + Material3, Hilt (KSP), coroutines/Flow, type-safe Navigation
  (`@Serializable` route objects, one `NavGraphBuilder.xDestination()` per feature).
- Features depend only on `:core:*`; no feature touches transport or crypto
  directly; one ViewModel per screen with immutable UI state.
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
