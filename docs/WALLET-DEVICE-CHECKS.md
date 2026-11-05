# Wallet: instrumented regtest run and phone checklist

**Test networks only: regtest on the dev stack, testnet4 or signet on staging. Never mainnet keys, never a real recovery phrase.** The app offers no mainnet wallet (`ChainDefaults.offered`), and a test vault must never be given a phrase that has held real coins. Use throw-away phrases and faucet coins.

These are the notes for the two parts of the wallet close-out (ANDROID-PLAN §4 "Wallet", "Tests") that need devices. They were written from the repo, not run. Nothing here has been verified on a phone.

## What exists today

- No wallet `androidTest` exists. `app/src/androidTest/kotlin/com/vettid/app/` has `A3ExitTest`, `A4ExitTest` and `ReplacedWipeTest` only. The wallet's checks are JVM and Robolectric tests (`:core:chain`, `:core:wallet`, `:feature:wallet`, `app/src/testDebug/.../wallet`).
- The dev stack (`devstack/devstack.sh`) does **not** start an Esplora server or a Bitcoin node. The `devStack` build type only points at one: `BuildConfig.WALLET_REGTEST_ESPLORA` (default `http://127.0.0.1:18083`, change it with `-PdevStackEsplora=<url>`; loopback hosts only, `ChainServerUrl.loopbackOrNull`).
- `devstack.sh` runs vettid-vault at `VAULT_REF` (default `26b71409`, release/staging/5, VAULT-MESSAGING 0.21.1). That is older than the wallet's contract (§10.18, 0.56.0 and later: `wallet.*`, `wallet.caps.*`, `item.reveal{pin}`). **Open point for the lead:** the regtest run needs a vettid-vault commit whose devstack serves §10.18 and accepts a `regtest` network wallet (`VAULT_REF=<commit>` or `VAULT_SRC=<checkout>`). I could not check this; vettid-vault was not touched.

## A. Instrumented regtest run (dev stack)

### Stack

1. A regtest Bitcoin node plus an Esplora HTTP API on it (for example `bitcoind -regtest` with the Blockstream Esplora / electrs-esplora image). Not in the repo: pick one and note it here. Requirements: Esplora routes `/blocks/tip/height`, `/block-height/0`, `/address/<a>/txs`, `/tx/<txid>`, `/tx/<txid>/hex`, `/fee-estimates`, `POST /tx`. Block 0 must be regtest's genesis (the app checks it). The server must give fee estimates (on regtest set `fallbackfee` or mine a few blocks and pay a few fees).
2. The dev stack: `devstack/devstack.sh up` (one stack at a time, waits for 8 GB free; `ANDROID_SERIAL="<serial> <serial2>"` for several phones). Tear down with `devstack/devstack.sh down`.
3. Ports on the phone, through `adb reverse`:
   - 18080 relay, 18081 member API, 18082 dev control (done by `devstack.sh up`);
   - **18083 the Esplora**: `adb -s <serial> reverse tcp:18083 tcp:<esplora port on the machine>` (the dev stack does not do this one).
4. The wallet's chain server is the build's regtest default (no typing needed): Wallet, then the three dots, then Chain server shows it for the regtest network. "Use my own server" only takes https, except this loopback default.

### Build and install

```bash
export JAVA_HOME=/opt/android-studio/jbr
# the wallet is in the debug, devStack and staging builds (never in release unless -PvettidWalletInRelease=true)
./gradlew --max-workers=2 :app:assembleDevStack [-PdevStackEsplora=http://127.0.0.1:18083]
adb -s <serial> uninstall com.vettid.app.devstack   # fresh install; never touch com.vettid.app
adb -s <serial> install -r app/build/outputs/apk/devStack/*.apk
```

Enroll through the UI as in the A3 exit test: "Type the code instead", any email, code `DEVSTACK` (each email is its own member). Use a phone with a real Keystore attester (the dev device policy names `com.vettid.app.devstack` and the machine's debug signing digest).

### Accounts and funds

- One enrolled vault with a credential (password and PIN known to the tester; the PIN is needed for capped wallets).
- Regtest coins: from the wallet's Receive screen copy an address, then on the node `bitcoin-cli -regtest sendtoaddress <addr> 1` (or `generatetoaddress 101 <addr>` for a fresh node), then `generatetoaddress 1 <anyaddr>` to confirm. A second regtest address from the node (or a second wallet) is the payee.

### Instrumented test to write (not written, needs the stack)

A `WalletExitTest` beside `A4ExitTest`, run as
`ANDROID_SERIAL=<serial> ./gradlew --max-workers=2 -PvettidTestBuildType=devStack :app:connectedDevStackAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vettid.app.WalletExitTest`. It would cover the plan's list: create, back up and check; receive; import a phrase with history on both accounts; a send through inspect, sign and broadcast that confirms; each `invalid_psbt` text; `FLAG_SECURE` on the phrase and check screens; a limit set at creation, a payment over it refused with nothing signed, a raise with a wrong PIN refused and with the right ones applied. Until it exists, run the checklist below by hand on the `devStack` build.

## B. Phone checklist (staging, both test phones)

Build: `./gradlew --max-workers=2 :app:assembleStaging` (signing by `~/.vettid/staging-signing.properties` or `-PvettidStagingSigning=<path>`; do not read or copy it), install `com.vettid.app.staging`, adb `install -r`. Staging talks to the staging vault service, so no dev stack and no `adb reverse`. Chain servers are the defaults (`mempool.space` signet and testnet4). Coins come from a public signet or testnet4 faucet. Needs: both phones with an enrolled staging vault, the app's notifications allowed, a connection between the two members (for the payee and an address request).

The same steps run on the `devStack` build against regtest (section A); the chain differences are noted in brackets.

| # | Step | Expected |
|---|---|---|
| 1 | Drawer, Wallet (shown only while the vault is open) | Wallet list, test-network banner, empty state |
| 2 | New wallet: name, network testnet4 (then a second one on signet), limits step left at "No limit", password | Phrase screen with 24 words (or 12); screenshot attempt is blocked (`FLAG_SECURE`); Recents shows blank |
| 3 | Continue to the check: answer the three words (any case), wrong answer once | A wrong answer ends the check, "Not backed up" stays on the row; Back up later from the row, right answers clear it |
| 4 | Lock the vault, open it again | Wallet list reloads; "Not backed up" state is from this phone |
| 5 | Wallet details, Show recovery phrase (password; PIN too on a capped wallet) | Phrase shown with `FLAG_SECURE`; wrong password refused; wrong PIN refused. History shows "Recovery phrase shown: <name>" |
| 6 | Import a wallet: type a known test phrase, optional passphrase under Advanced, with an invalid word and a bad checksum first | Each bad word flagged as typed; bad checksum text before anything is sent; then password, then "Looking for this wallet's history…" and the balance and history appear [regtest: a phrase with funds on both accounts] |
| 7 | Receive: new address, QR, copy; type chosen (taproot or segwit) | Address matches the vault's list (no mismatch text); used address marked when funded |
| 8 | Fund from a faucet [regtest: `sendtoaddress`] | Balance shows unconfirmed, then confirmed after a block |
| 9 | Spending limits: set a per-payment limit at creation on a new wallet; later lower; raise with wrong PIN, then with right PIN and password | Limit set after creation; lower needs no PIN; raise with wrong PIN refused, right one applied; History shows the limit entries; `wallet.caps_raised` notification "A spending limit was raised or removed on <name>" (Vault activity) opens the limits screen |
| 10 | Send to the other phone's wallet: amount, fee choice, review, password | Review shows the vault's summary (payee, amount, change, fee); wrong password refused with nothing sent; approve signs and broadcasts; "Sent" with the txid |
| 11 | Send over the limit | The cap text for per-payment or per-period, nothing signed; `wallet.cap_refused` notification "A payment was refused by your spending limit" |
| 12 | Transactions: open the list and a row; Copy txid; pull to refresh; set the chain server to a wrong-network server (for a testnet4 wallet: a signet, testnet3 or regtest Esplora, never a mainnet one) | Rows read "Sent to tb1q…", "Received", "Moved within this wallet"; status and "Status from {host}"; detail shows total sent, fee, confirmations; wrong network refused (genesis check) |
| 13 | Notifications: after step 10 on phone A | `wallet.signed` notification "Bitcoin payment signed" on Vault activity; its tap opens the transaction while the Wallet entry is shown. `wallet.created` is History only: no notification is expected; History reads "Wallet created: <name>" |
| 14 | Settings, App lock on with the timeout "Immediately". Start a payment, approve it, and press Home at once (before the Sent screen shows), so that the cover is up when the `wallet.signed` item arrives. Timing-dependent: repeat if the notification came before the cover | The notification is the public form "New activity in VettID" (no wallet name, no txid). If it cannot be reproduced, the evidence is `FeedNotifierTest.theWalletsItemsNotifyOnVaultActivityAndNameTheWalletOnlyUncovered` |
| 15 | History: wallet entries | "Wallet created: <name>", "Bitcoin payment signed" (entry detail has "Open the transaction"), "Wallet deleted"; no address or amount anywhere |
| 16 | Delete a wallet (password) | Gone from the list; History "Wallet deleted" |
| 17 | After all of the above, lock the vault. On `devStack` (debuggable) only: write the search terms (the test phrase's words and the whole phrase, the password, the PIN, the payee and receive addresses, the txid) one per line into a temporary file `terms.txt`. Pull the data into a fresh scratch directory: `mkdir scratch && adb exec-out run-as com.vettid.app.devstack tar c . \| tar x -C scratch`. Search with `grep -raF -f terms.txt scratch`. Delete `terms.txt` and `scratch` afterwards | No match. Only `wallet-flags.bin` and `chain-servers.bin` are wallet files. `grep` finds plain text only: it misses hex and base64 forms, which `WalletDataDirectoryScanTest` covers. Never put the terms on the command line (shell history, `ps`). Staging is not debuggable: `run-as` is unavailable, so rely on the unit scan |

## To unblock the regtest run (follow-up, not done)

The instrumented regtest run cannot start with what is in the repo. Two pieces are needed:

1. **Dev stack bump.** `devstack/devstack.sh` pins vettid-vault at `26b71409` (VAULT-MESSAGING 0.21.1). It needs a commit whose `cmd/devstack` serves the §10.18 wallets (`wallet.*`, caps, `item.reveal{pin}`) and a `regtest` network. That is vettid-vault work for whoever owns the vault side; the Android side only changes `VAULT_REF` and the README, and `devstack/device-policy.json` stays as it is. The stack also needs a regtest service: `bitcoind -regtest` plus an Esplora API on it, started by `devstack.sh up` and stopped by `down`, forwarded with `adb reverse tcp:18083 tcp:<port>` (18083 is the default of `WALLET_REGTEST_ESPLORA`).
2. **`WalletExitTest`** (`app/src/androidTest/.../WalletExitTest.kt`, written like `A4ExitTest`, skipping without the stack):
   1. enroll through the UI as the A3 test does; mine 101 blocks to a funded address;
   2. create a wallet, read the phrase, answer the check, assert `FLAG_SECURE` on both screens;
   3. fund the receive address through the node, mine a block, assert the balance;
   4. import a known phrase with history on both accounts, assert the indices;
   5. set a limit at creation, send over it (cap text, nothing signed), raise with a wrong PIN (refused) and with the right ones (applied);
   6. send within the limit through inspect, sign and broadcast, mine a block, assert Confirmed; check each `invalid_psbt` text.

Run it as in section A.

Record the date, the build (version line in the drawer), the serials and any step that differs. Stop and report on a mismatch; do not work around it.
