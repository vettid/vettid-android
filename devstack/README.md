# Local dev stack (phases A2–A4)

The vault side of the app, running on the development machine: vettid-vault's
`cmd/devstack`, reachable from a USB-connected phone through `adb reverse`.
It is what the instrumented exit tests and the `devStack` build type talk to.

| Part | What runs |
|---|---|
| Relay | the real `vettid-relay` binary, at the version vettid-vault's `go.mod` names |
| AWS | LocalStack (S3, SQS, DynamoDB, SSM) from vettid-vault's `integration/docker-compose.yml`, one container capped at 1.5 GB |
| Host | `vault-parent` in TCP mode (release build) |
| Enclave | `vault-enclave` dev build: fake NSM and KMS, TEST-ONLY roots, release 3, with the dev device policy below |
| Member API | vettid-vault's stand-in for the vault routes (`internal/memberapitest`): the member is `Authorization: Bearer <user_guid>` |
| Peer | a second member's vault, enrolled with `vaultctl`, driven through the control port |

Every key in the stack is TEST-ONLY (fixed public seeds in vettid-vault). Nothing persists across restarts.

## Running it

```bash
devstack/devstack.sh up      # go run ...cmd/devstack@<commit>, adb reverse; prints ready.json
devstack/devstack.sh status
devstack/devstack.sh down    # stop everything and remove the adb reverse rules
```

`up` runs

```bash
go run -tags devenclave github.com/vettid/vettid-vault/cmd/devstack@ca10a72 \
  -dev-device-policy devstack/device-policy.json
```

(`VAULT_REF` picks another commit; `VAULT_SRC=<checkout>` runs `./cmd/devstack` from a local
vettid-vault checkout instead) and `adb reverse` of ports 18080–18082 on every connected phone (`ANDROID_SERIAL`, space-separated,
picks some). The checkout, if any, is
never modified. Requirements: Go ≥ 1.26, podman (or docker) with compose, the LocalStack image
(pulled on first use), and network access the first time (module and relay builds).

The machine is shared: `up` waits until 8 GB of memory are available (re-checking every 60 s),
and the stack runs its processes with `GOMAXPROCS=2`. One stack at a time; tear it down when
done. Logs: `~/.cache/vettid-android-devstack/devstack.log` and
`~/.cache/vettid-devstack/run/logs/` (relay, parent, enclave, compose; request lines carry
method, path and status only).

## The dev device policy (`device-policy.json`)

The dev enclave verifies device attestation (VAULT-MESSAGING §11.7) against vettid-vault's TEST
policy. `device-policy.json` extends it so that the phone's **real** Keystore attestation enrolls:

- `google_attestation_roots`: Google's hardware attestation roots, including Key Attestation CA1;
- `android_packages`: `com.vettid.app.dev` and `com.vettid.app.devstack`;
- `android_signers_sha256`: the SHA-256 of the debug signing certificate of the machine that
  builds the APK (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256`);
  another developer adds theirs, or points `DEVICE_POLICY` at their own file;
- `grapheneos_boot_keys`: the pinned GrapheneOS verified-boot keys (`SelfSigned` boot state).

The policy only adds: the TEST attester (`:core:testing` `TestAndroidAttester`, used by the JVM
tests and the A2 test) keeps working. A release build never carries any of this.

## Ports (127.0.0.1, also on the phone through `adb reverse`)

| Port | Service |
|---|---|
| 18080 | The relay, plain HTTP. Tokens name the relay `https://relay.vettid.test`; clients map that origin to this port (`OriginMapInterceptor`). |
| 18081 | The member API stand-in: `/api/vault/{status,enclave,enroll,unlock,lock,requests/<id>}` and `/.well-known/vettid/pcr-manifest.json`. |
| 18082 | Dev control (TEST-ONLY): `GET /dev/health`, `/dev/info`, `/dev/trust`, `POST /dev/peer/request`, `/dev/peer/event`. |

## The `devStack` build type

`./gradlew :app:assembleDevStack` builds the debug app (application id `com.vettid.app.devstack`)
pointed at the stack: endpoints from `BuildConfig` (`DEV_STACK_API`, `DEV_STACK_RELAY`,
`DEV_STACK_CTL`), cleartext to 127.0.0.1 only, the stack's TEST trust anchors from the control
port, and **the phone's real Keystore attester** (StrongBox). It is a build type of its own,
initialised from `debug`; no release variant can carry it.

The app never signs in (VAULT-MESSAGING 0.15.0 §11.12, MEMBER-API 2.0.0), and the stand-in has
no setup codes yet, so the `devStack` build simulates the typed redeem in the app
(`app/src/devStack/.../DevMemberGateway.kt`): choose "Type the code instead" and enter any
email with the code `DEVSTACK`. Each address is its own member (`user_guid` derived from it);
requests carry the stand-in's `Authorization: Bearer <user_guid>` instead of `X-VettID-App`.
Scanning a setup QR and a recovery's claim need the stand-in's 2.0.0 routes. Until vettid-vault's
dev enclave implements 0.15.0, it may refuse the sealed enrollment's `app.api_key`.

## Tests against it

```bash
# The A3 exit test: a fresh install of the devStack build, onboarding through the UI with a new
# test member, enrollment with the phone's real attestation, credential, lock and unlock.
adb uninstall com.vettid.app.devstack
ANDROID_SERIAL=<serial> ./gradlew --max-workers=2 -PvettidTestBuildType=devStack :app:connectedDevStackAndroidTest
adb pull /data/local/tmp/a3-exit/ <dir>     # a screenshot of every step

# The A4 exit test: a fresh install onboards, invites the vaultctl peer (QR code and link shown; the
# peer accepts the link), approves after the 6-digit safety code, exchanges messages both ways
# (delivered and read receipts), removes the connection and accepts the peer's invitation link
# (the invitee side), decides a member-authentication request (credential password) and a grant
# request in Approvals, and marks the connection a favourite.
adb uninstall com.vettid.app.devstack
ANDROID_SERIAL=<serial> ./gradlew --max-workers=2 -PvettidTestBuildType=devStack :app:connectedDevStackAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vettid.app.A4ExitTest
adb pull /data/local/tmp/a4-exit/ <dir>

# The A2 exit test (vault client only, TEST attester):
ANDROID_SERIAL=<serial> ./gradlew --max-workers=2 :core:data:connectedDebugAndroidTest
# JVM, on the host:
./gradlew --max-workers=2 :core:vault:testDebugUnitTest --tests '*DevStackJvmTest*'
```

All skip when the stack is not reachable. The phone stays locked: the test activity draws over
the keyguard (`vettid.screenshot`).

## Manual check: the biometric app lock

BiometricPrompt needs a finger (or the screen lock) and cannot be automated. On an unlocked
phone with the `devStack` (or debug) build and an enrolled vault:

1. Settings → Security → Biometric app lock: turn on; the prompt appears; authenticate. The
   switch stays on and "Lock after" appears (default 5 minutes).
2. Set "Lock after" to Immediately; leave the app (home) and return: the lock screen covers the
   app and the prompt opens; cancel it, then tap Unlock and authenticate: the app is as you left it.
3. Force-stop the app and open it: it starts locked.
4. Add a new fingerprint in system settings and return: the prompt cannot open (the key was
   invalidated), the lock turns itself off, and Settings says so.
5. Turn the lock off: no prompt; restarting the app no longer asks.

## Two phones

Both phones reach the same stack (`devstack.sh up` reverses the ports on every connected phone).
Install the `devStack` build on each (`adb -s <serial> install -r app/build/outputs/apk/devStack/app-devStack.apk`)
and onboard each with its own address (each address is its own member and vault). Then phone A:
drawer → Invite a connection → Create invitation; phone B: Connections → Add a connection → Scan a
QR code (point it at A's screen) or Paste an invitation link (A's Copy/Share link). A sees the
request with its safety code and approves; messages and approvals then flow between the two. A
locked test phone stays locked: launch with `--ez vettid.screenshot true`. Debug builds expose test
tags as resource ids, so the flow can be driven with `adb -s <serial> exec-out uiautomator dump /dev/tty`
and `adb -s <serial> shell input tap|text`.
