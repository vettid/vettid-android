# Local dev stack (phase A2)

The vault side of the app, running on the development machine: vettid-vault's
integration stack, reachable from a USB-connected phone through `adb reverse`.
It is what the A2 exit test and the `devStack` build type talk to.

| Part | What runs |
|---|---|
| Relay | the real `vettid-relay` binary, at the version vettid-vault's `go.mod` names (`internal/relaytest`) |
| AWS | LocalStack 4.9 (S3, SQS, DynamoDB) from vettid-vault's `integration/docker-compose.yml`, `mem_limit: 1536m` |
| Host | `vault-parent` in TCP mode (release build) |
| Enclave | `vault-enclave` dev build: fake NSM and KMS, TEST-ONLY roots, release 3 |
| Member API | vettid-vault's stand-in for the vault routes (`internal/memberapitest`): the member is `Authorization: Bearer <user_guid>` |
| Peer | a second member's vault, enrolled with `vaultctl api-enroll`, driven through the control port |

Every key in the stack is TEST-ONLY (fixed public seeds in vettid-vault
`internal/enclavetest`).

## Running it

```bash
devstack/devstack.sh up      # build, start, adb reverse; prints the ready file
devstack/devstack.sh status
devstack/devstack.sh down    # stop everything and remove the adb reverse rules
```

Requirements: Go ≥ 1.26, podman (or docker) with compose, the LocalStack image
(pulled on first use), a vettid-vault checkout next to this repository
(`VAULT_DIR` overrides it, `VAULT_REF` picks a commit; default `HEAD`), and
network access the first time the relay binary is built.

`up` checks that at least 8 GB of memory are available (the machine is shared;
it waits and re-checks every 60 s), runs one LocalStack container capped at
1.5 GB and the Go processes with `GOMAXPROCS=2`. Logs are in
`~/.cache/vettid-android-devstack/run/logs/` (`devstack.log`, `parent.log`,
`enclave.log`); request logs carry method, path and status only.

The vettid-vault checkout is never modified: `up` extracts a snapshot of the
commit (`git archive`) into `~/.cache/vettid-android-devstack/vault-<commit>`
and copies the runner, `go/devstack_test.go`, into it as
`devstack/android/`. The runner is a Go test (build tags `devenclave
integration androiddevstack`) because it reuses vettid-vault's internal test
packages, which only code inside that module may import.

## Ports (127.0.0.1, also on the phone through `adb reverse`)

| Port | Service |
|---|---|
| 18080 | The relay, plain HTTP. Tokens name the relay as `https://relay.vettid.test` (`aud`); clients map that origin to this port (`OriginMapInterceptor`). Request signatures cover method, path and body, not the host, so the mapping changes nothing the relay checks. The enclave and vaultctl reach the same relay through a TLS front with the test TLS root. |
| 18081 | The member API stand-in: `/api/vault/{status,enclave,enroll,unlock,lock,requests/<id>}` and `/.well-known/vettid/pcr-manifest.json`. |
| 18082 | Dev control (TEST-ONLY): `GET /dev/trust` (the test Nitro root and manifest key), `POST /dev/peer/request {type, body}` (`vaultctl request`), `POST /dev/peer/event {type, match, timeout_s}` (waits for a peer event). |

## Tests against it

```bash
# JVM, on the host (skipped when the stack is not running, so CI skips it):
./gradlew --max-workers=2 :core:vault:testDebugUnitTest --tests '*DevStackJvmTest*'

# On the phone (the A2 exit test; skipped when the stack is not reachable):
ANDROID_SERIAL=<serial> ./gradlew --max-workers=2 :core:data:connectedDebugAndroidTest
# The real Keystore attester against the production policy (no stack needed):
ANDROID_SERIAL=<serial> ./gradlew --max-workers=2 :core:altchan:connectedDebugAndroidTest
```

The exit test (`core/data/src/androidTest/.../A2ExitTest.kt`, scenario in
`core/testing/.../ExitScenario.kt`) enrolls a vault through the member API
(PIN), runs the first handshake, creates the Protean Credential (password) and
confirms the vault, locks and unlocks it with the PIN, stores a critical item
and reveals it with the password, connects to the vaultctl peer through an
invitation (the app invites, the peer accepts, the app approves after the SAS),
and exchanges a message each way. The device keys are wrapped under the
Keystore and the device state is in a Keystore-encrypted file, as in the app.
Instrumentation arguments `devstackApi`, `devstackRelay` and `devstackCtl`
override the default addresses.

The phone stays locked: nothing in these tests needs the screen.

## The `devStack` build type

`./gradlew :app:assembleDevStack` builds the debug app (application id
`com.vettid.app.devstack`) pointed at the stack: endpoints from `BuildConfig`
(`DEV_STACK_API`, `DEV_STACK_RELAY`, `DEV_STACK_CTL`, `DEV_STACK_GUID`, the
last overridable with `-PdevStackGuid=`), cleartext allowed to 127.0.0.1 only,
the stack's TEST trust anchors from the control port, and the TEST attester
(below). It is a build type of its own, initialised from `debug`; no release
variant can carry it, and the release APK contains none of it (checked: no
`core/testing`, `OriginMapInterceptor` or `relay.vettid.test` in its dex).
The app uses it from A3 on (`app/src/main/kotlin/.../env/AppEnvironment.kt`).

## Device attestation: why the tests use a TEST attester

The dev enclave verifies device attestation (VAULT-MESSAGING §11.7) against
the TEST policy of vettid-vault `internal/enclavetest.Policy()`: only the TEST
Android attestation CA (root P-384 scalar 48 × 0x41, intermediate P-256 32 ×
0x42), the package `com.vettid.app`, the TEST signing digest
SHA-256("VettID TEST ONLY app signing certificate"), and the TEST GrapheneOS
boot key. The policy is fixed in code; the dev enclave has no flag to extend
it. A real phone's attestation therefore cannot pass it: its chain ends at a
Google root, its package is `com.vettid.app.dev` / `.devstack` (or the test
APK's), its signing digest is the debug key's, and on GrapheneOS its boot
state is `SelfSigned` with the real GrapheneOS key.

So the instrumented tests use `TestAndroidAttester` (`:core:testing`, a
port of enclavetest's Android attester): a software P-256 key whose chain the
TEST CA issues, with a StrongBox / Verified / locked key description. It
lives in test code and the `devStack` build type only.

The real Keystore attester is tested on its own
(`KeystoreAttesterDeviceTest`): on the test phone (Pixel 10 Pro, GrapheneOS)
the StrongBox key's chain ends at Google's pinned *Key Attestation CA1*
(the 2025 EC root), the key description meets every §11.7 requirement, and
the boot state is `SelfSigned` with verified boot key
`4e8ee8f7…8de093`, the pinned GrapheneOS key for the Pixel 10 Pro. A release
build (package `com.vettid.app`, the release signing key) on this phone would
pass the production enclave's policy.

**Proposed vettid-vault change** (not made here): a dev-enclave option, e.g.
`vault-enclave -dev-device-policy FILE`, adding Google's attestation roots, the
debug package names and the debug signing digest, and the real GrapheneOS
keys (`pins.GrapheneOSVerifiedBootKeys()`) to the TEST policy, so that the
`devStack` app can enroll with the phone's real StrongBox key. Also useful
upstream: making this runner a `cmd/devstack` of vettid-vault (it needs the
module's internal test packages).
