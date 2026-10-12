# Calls: media suites, keys and the frame format

Status: **proposal for the specification** (CALLS-PLAN 0.1.1 phase C1, decisions D2 and D11). It is what this app
implements (`:core:crypto` `call/CallMedia.kt`, `call/CallFrames.kt`, `:core:vault` `CallApi.kt`) and what an iOS or
desktop client has to match frame for frame. It is meant to be lifted into VAULT-MESSAGING §10.10 and
CALLING-SERVICE §7; §9 lists the edits. It replaces the C0 spike's `C0-FRAME-FORMAT.md` (draft PR #123), whose
findings about the media stack are kept in §5–§8.

Marks: **[S]** read from the media stack's source, **[I]** inferred, **[D]** confirmed on two phones in the C0 test
(2026-10-12, Pixel 7 and Pixel 10 Pro), **[P]** proposed here.

## 1. Summary

| | |
|---|---|
| Call key | `k_call`, 32 bytes, agreed between the two devices (VAULT-MESSAGING §10.10; unchanged) |
| Media suite | a number from one closed registry (§2). **1 = AES-128-GCM frames**, the suite of every call today; **2 = AES-256-GCM frames**, registered, offered by no engine yet |
| Negotiation | in the SDP: the caller lists its suites in order of preference, the answerer names one (§2.1) |
| Downgrade protection | the choice and the caller's whole list are in the key derivation: after a changed attribute nothing is heard, on any suite; audio may be sent under the weaker suite's keys until the failure is seen (§2.2) |
| SDP shape | exactly one media section: audio, Opus alone; anything else is refused before the media stack sees it (§2.1, §7) |
| Keys | two direction keys of 32 bytes from `k_call` (§3) |
| Frame key | the suite's first 16 or 32 bytes of `HKDF-SHA-256(direction key, "vettid/vms/2/call-frame", 128 zero bytes)` (§4) |
| Frame | `clear header ‖ ciphertext ‖ tag(16) ‖ IV(12) ‖ 0x0C ‖ 0x00` (§5) |

## 2. Media suites [P]

A media suite fixes everything two devices must agree on to encrypt a call's media frames. The registry is closed
and versioned: a number is never reused or redefined, and a device uses only suites it finds in its own copy
(`MediaSuite` in `:core:crypto`).

| id | Frame cipher | Frame key | KDF | Label of the direction keys | Label of the frame key | Transport | State |
|---|---|---|---|---|---|---|---|
| 1 | AES-GCM, the media stack's frame-cryptor format (§5) | 128 bits | HKDF-SHA-256 | `vettid/vms/2/call-media` | `vettid/vms/2/call-frame` | DTLS-SRTP as the stack negotiates it; no post-quantum DTLS required | **in use** |
| 2 | the same format | 256 bits | HKDF-SHA-256 | `vettid/vms/2/call-media` | `vettid/vms/2/call-frame` | the same | **registered, not offered**: the stack's key provider derives 128-bit keys only until a release carries its 256-bit option (webrtc-sdk/webrtc PR #302). Its frame-key derivation (§4 with L = 32) is to be confirmed against that release before an engine offers it |

What a device **offers** is the registry cut down to what its media engine can do, in the registry's order of
preference (2 before 1). Today every engine can do suite 1 only. Adding a suite is a registry entry and the
engine's capability (for suite 2: the key provider's key-size parameter); the negotiation, the derivation and the
signalling do not change. "Transport" is part of the suite so that a later suite can require, for example, a
post-quantum DTLS key exchange: an engine whose transport cannot meet a suite's expectation does not offer it. No
suite requires it today, and the post-quantum DTLS field trial of the stack is off in every build (a debug-only
switch exists for measurement).

### 2.1 Negotiation

- The **offer**'s SDP carries one session-level attribute (before the first `m=` line):
  `a=vettid-media-suites:<n> <n> …` — the caller's suites as decimal numbers without leading zeros, 1 to 8 of them,
  each 1–65535, no repeats, separated by exactly one space, the preferred one first.
- The **answer**'s SDP carries one session-level attribute `a=vettid-media-suite:<n>`: the first suite of the
  caller's list, **in the caller's order**, that the answerer can do. Numbers the answerer does not know are
  skipped (a newer caller may list suites an older answerer has never heard of).
- **One audio section.** An offer or an answer has exactly one `m=` line; it is `m=audio`, and every payload type
  on it has an `a=rtpmap` whose codec is `opus`. A device MUST refuse any other SDP before it reaches its media
  stack: a second audio section, video or a data channel would be media that the frame encryption prepared for the
  one audio section does not cover (§7 rule 2).
- The attribute names are case-sensitive and lower-case as written; lines end in CRLF or LF. `offered` in the key
  derivation (§3) is the **parsed list written again** (decimal numbers joined by one space), which for a
  well-formed attribute is its value byte for byte; a value that is not in that form is malformed.
- **What a callee's device does with an offer it cannot use** (attribute missing, repeated, not at session level
  or malformed; more or other than one Opus audio section; no suite in common): it does not ring and **sends
  nothing**, as for a share that does not verify (§10.10). Another of the member's devices may be able to take the
  call; if none does, the caller's ring time runs out and it sends `call.end{timeout}`.
- **What a caller's device does with an answer it cannot use** (the same faults; a suite that is not in the list
  **it sent**, its own copy, never a list read back from anywhere; a suite not in its registry): it uses nothing
  of the answer, sends `call.end{failed}` and tells the member that the call could not be encrypted.
- A call never continues on another suite or without one.
- A device hands the SDP to its media stack without these attributes (they are VettID's, not the stack's).
- Why the SDP: at S10 the vault builds `call.offer` and `call.answer` from named members and passes `sdp` through as
  an opaque string, so this is the only place that reaches the other device without a vault change. Unknown SDP
  attributes are ignored by SDP parsers (RFC 8866 §5.13).

### 2.2 What protects the negotiation

**At S10 the attribute is not signed by anyone.** The relay and the hosts cannot change it (the SDP travels inside
the end-to-end sessions), but either vault can: the member's own, which is trusted for the member's calls anyway
(§10.10 "Residual"), and the connection's. So the protection must not depend on the attribute arriving intact. It is
this: the chosen suite **and the caller's whole list** are inputs of the direction keys (§3). The caller derives
from the list it sent and the choice it read; the answerer from the list it read and the choice it made.

| Someone on the path… | Result |
|---|---|
| cuts the offer's list down (`2 1` → `1`) or reorders it | the answerer picks from the changed list; the caller derives with the list it sent. Different keys on the two devices: no frame decrypts, the receivers report it and the call ends `failed` (§7). **Nothing is heard on suite 1** |
| rewrites the answer's choice (`2` → `1`) | the answerer encrypts for 2, the caller derives for 1: different keys, the same ending |
| rewrites the choice to a suite that was not offered, or to an unknown number, or removes an attribute | refused when it is read; no ringing, or no media |

**Can an attacker who can alter the SDP make two devices that both support suite 2 talk on suite 1?** Not so that
anything is **heard**: a call in which audio plays has, on both devices, the same suite and the same view of what
was offered. But the claim is about audio played, not audio **sent**. After a cut list both devices hold suite 1
keys (different ones) and each starts sending microphone audio encrypted under its own; the tampering is noticed
only when a receiver fails to decrypt the other's frames. An attacker who also **drops the media packets** keeps
both receivers from ever seeing a frame, so neither reports a failure, and both devices go on sending audio under
suite 1 keys until someone hangs up. The attacker does not hold those keys (they come from `k_call`), so this
gains them ciphertext under the weaker suite, not the audio; it is still more than "no call". At S10 this cannot
matter: only suite 1 is offered. What else it costs at S10: a tampered negotiation is discovered when media fails
to decrypt, not at signalling time, and the devices cannot tell tampering from a fault.

**Required before any device offers a second suite** (not built in C1): no microphone audio under keys the other
device has not confirmed. Either of:

1. *Key confirmation in the answer.* The SDP answer carries `a=vettid-media-confirm:<base64>` =
   HMAC-SHA-256 under a key derived from `k_call` (its own HKDF label) over `chosen || 0x00 || offered-as-seen ||
   0x00 || call_id`. The caller checks it when it takes the answer and refuses a mismatch before it sets a key;
   the callee keeps its microphone track disabled until its receiving cryptor first reports OK (which it does only
   for frames under the keys it derived).
2. *C2's signed members*, below, which make the list and the choice unalterable in the first place.

**What C2's signed field changes.** Carried as signed members (in the share's signed bytes: the caller's list in
the offer share, the choice in the answer share), the negotiation is authenticated by the two devices' identity
keys and vouched for by their vaults, like the key-exchange shares: a changed list or choice is then rejected by
the signature check before the callee rings or the caller uses the answer, with a reason, and no longer costs a
failed call. It also removes VettID's attributes from the SDP. It does not change the derivation of §3, which
keeps binding the keys to what was negotiated.

## 3. The direction keys [P]

```
k_dir = HKDF-SHA-256(ikm  = k_call,
                     salt = the chosen suite's direction-key label     ("vettid/vms/2/call-media"),
                     info = chosen || 0x00 || offered || 0x00 || direction || 0x00 || call_id,
                     L    = 32)

chosen    the suite of the answer, decimal ASCII                          e.g. "1"
offered   the caller's list as sent: decimal numbers joined by one space  e.g. "2 1"
          (numbers the answerer does not know included)
direction "caller" for frames the calling device sends,
          "callee" for frames the answering device sends
call_id   the call's id (a ULID), as in the signalling
```

- The calling device encrypts with `k_dir("caller")` and decrypts with `k_dir("callee")`; the answering device the
  reverse. Each device wipes `k_call` once it has the two keys, and both keys when the call ends.
- A direction key is 32 bytes for every suite: it is key *material* for the suite's frame-key derivation (§4).
- Two keys, because the frame cipher's IV holds no randomness (§6): with one key for both directions its
  uniqueness would rest on the two senders' SSRCs differing.

## 4. The frame cryptor's configuration and the frame key

The media stack is `io.github.webrtc-sdk:android:150.7871.01` (AAR SHA-256 `0a1627b1…79b0`); its frame cryptor is
`api/crypto/frame_crypto_transformer.{h,cc}` of `webrtc-sdk/webrtc` at `73cb8180` **[S]**. The same C++ is wrapped
by the family's iOS/macOS, desktop and Rust builds.

Key provider options, fixed for every platform **[P]**:

| Option | Value | Why |
|---|---|---|
| `sharedKey` | false (per-participant keys) | one key per direction |
| `keyDerivationAlgorithm` | HKDF | not PBKDF2 (100,000 rounds for nothing: the input is a key, not a password) |
| `ratchetSalt` | the ASCII bytes of `vettid/vms/2/call-frame` | the frame-key derivation's salt |
| `ratchetWindowSize` | 0 | no ratchet: one fresh key a call |
| `uncryptedMagicBytes` | empty | otherwise a frame ending in them is played without decryption **[S]** |
| `failureTolerance` | 10 | decryption failures after which the receiver reports `DECRYPTIONFAILED` (§7) |
| `keyRingSize` | 1 | key index 0 only |
| `discardFrameWhenCryptorNotReady` | true | belt only: it concerns empty frames (§5), and upstream's `KeyProviderOptions` copy constructor does not copy it, so its value inside the provider is not determined **[S]**. Nothing here relies on it: "a sender without a key sends nothing" (§7 rule 3) rests on the missing-key path, which drops every non-empty frame whatever this option is |

One sender cryptor (on the audio sender, participant id `send`) and one receiver cryptor (on the audio receiver,
participant id `receive`); `setKey("send", 0, k_dir(mine))`, `setKey("receive", 0, k_dir(theirs))`. The participant
id only selects a key in the provider: it is not in the frame and not in any derivation **[S]**.

```
frame_key = HKDF-SHA-256(ikm = k_dir, salt = the suite's frame-key label, info = 128 bytes of 0x00, L = 16 (suite 1) | 32 (suite 2))
```

In the library release in use the length 16 is a literal (`SetKeyFromMaterial`), whatever the length of the
material **[S]**, **[D]** (the C0 self-check compared the native output with this derivation): that is suite 1.
AES-256-GCM exists in the code but cannot be reached through the key provider **[S]**; upstream PR #302 adds a key
size to `createFrameCryptorKeyProvider` (128 by default). Suite 2 is that option, once a release has it.

## 5. The encrypted frame [S] [D]

```
header   = frame[0 .. u)                    u = clear bytes (audio: 1, for Opus the TOC byte)
iv       = BE32(ssrc) || BE32(rtp_timestamp) || BE32(rtp_timestamp - (n mod 65535))      n = per-SSRC frame counter, from 0
sealed   = AES-GCM(frame_key, iv, aad = header, plaintext = frame[u ..))                 ciphertext || 16-byte tag
out      = header || sealed || iv || 0x0C || key_index                                   key_index = 0x00
```

- It runs on encoded frames, after the encoder and before RTP packetization, so RTP headers, RTCP and DTLS-SRTP
  underneath are untouched. A frame grows by 30 bytes (12 kbit/s at 50 Opus frames a second).
- Video (C4): VP8 keeps 10 bytes of a key frame and 3 of a delta frame clear; H.264 keeps everything up to the
  first two bytes of the first slice NAL unit clear and RBSP-escapes the rest; AV1 and VP9 keep nothing clear.
- The receiver reads the IV and the key index from the frame. A frame that fails authentication is **dropped**: the
  decoder gets nothing, so the listener hears silence, never noise or clear audio.
- The two trailer bytes are not authenticated (changing them only makes the frame undecryptable) **[I]**.
- An empty frame (length 0) passes through unchanged in both directions, with or without a key: it carries
  nothing.

## 6. Properties and limits

- **Suite 1 has 128-bit frame keys.** `k_call` comes from MLKEM768X25519 (NIST category 3); AES-128 is category 1,
  so the frame cipher is the weakest part of a call's confidentiality. The owner accepted it for a first
  production release of calls (2026-10-12); suite 2 follows when the media stack can do it (§2).
- **The IV is deterministic.** It is unique under a key while (SSRC, RTP timestamp) does not repeat with the same
  counter value: within one RTP stream until the timestamp wraps (about 24.8 hours of Opus at 48 kHz). One key per
  direction removes the cross-sender case. **A device SHOULD end a call 12 hours after it was answered**
  (`call.end{hangup}`): the vault stops counting the call as live then, and it keeps every call well inside the
  wrap. (Not built in C1 PR 2; it comes with the service in PR 3.)
- **No replay protection** at this layer (SRTP's replay window underneath covers the network; a TURN server holds
  no SRTP key) **[S]**.
- **A new offer/answer inside a call** (`call.update`, C4) may keep the keys only if the RTP streams keep their
  SSRCs and their timestamps keep moving forward; otherwise it needs new keys.
- **Hostile frames.** The stack's `decryptFrame` reads the trailer and the IV by offsets from the end of a frame
  without checking its length **[I]** (not tested). Only the other device of the call can produce valid SRTP, so it
  is a possible crash by a connection the member took a call from, not a key or content exposure. To be reported
  upstream before production.

## 7. Rules for an implementation

1. **Never enable the media stack's logging.** Both key derivations log the input key, the salt and the derived key
   at severity INFO **[S]**. No `Logging.enableLogToDebugOutput`, no injectable logger, in any build. (C0: nothing
   in `logcat` with logging off **[D]**.)
2. **Both cryptors are created and enabled before any SDP is applied**, and never disabled during a call: a
   disabled cryptor passes clear audio in both directions **[S]**. A sender cryptor needs its track first (it
   dereferences the sender's track) **[S]**. **No media exists outside them:** the SDP is checked to be one Opus
   audio section before it is applied (§2.1), and after the other side's SDP has been applied the device checks
   that its peer connection has exactly one transceiver and that it is the one carrying the cryptors; anything
   else ends the call as not encrypted. (An offer with a second audio section would otherwise get a receiver of
   its own, with no cryptor, whose audio plays under DTLS-SRTP alone while the encrypted section stays silent and
   reports nothing.)
   **And inside the one section, no stream without them:** a device hands the other side's SDP to its stack
   **without the `a=ssrc:` and `a=ssrc-group:` lines**. With an SSRC announced, the stack attaches the receiver's
   frame decryption to that one stream; RTP that arrives on any other SSRC of the section gets a default stream
   created with the section's *default* transformer, which is then none, and is decoded and played **[S]**
   (`pc/audio_rtp_receiver.cc` `Reconfigure`, `media/engine/webrtc_voice_engine.cc`
   `SetDepacketizerToDecoderFrameTransformer`, `MaybeCreateDefaultReceiveStream`, `AddRecvStream`, at the pinned
   commit). A vault able to rewrite the unsigned SDP's fingerprint and candidates could so pass the genuine
   encrypted audio on and add clear audio of its own. With no SSRC announced, the receiver is "unsignalled": its
   decryption is stored as the section's default and is given to the first and to every later stream **[S]**, so
   audio on any SSRC is decrypted under the call's key or dropped. Second lock: a device that ever sees more than
   one received audio stream in its statistics ends the call as not encrypted. **[D]** still to be shown on two
   phones (the test builds can leave the lines in, and can announce other SSRCs than they send on).
3. **A sender without a key sends nothing** (`MISSINGKEY`) **[S]**: the calling device, which has no key before the
   answer, therefore sends no audio until it has set its key.
4. **Media that does not decrypt under the call's keys is never played**, and the call does not continue without
   it: on `DECRYPTIONFAILED` of the receiver, on any cryptor error, or on a `MISSINGKEY` report once the keys
   have been set and media flows (the first one: with keys set it cannot be a transient state), the device ends
   the call with `call.end{failed}` and says that the call could not be encrypted. A `MISSINGKEY` before the keys
   are set is the expected state of rule 3 and ends nothing. It does not set the key again: a receiver that
   reported `DECRYPTIONFAILED` drops every further frame until its key is set **[S]** **[D]**, and with one fresh
   key a call a second attempt with the same key cannot succeed where the first did not.
5. The failure count is not consecutive: only setting a key resets it **[S]**. With a tolerance of 10 a wrong key is
   reported after about a fifth of a second of audio.

## 8. Test vectors

From vettid-vault's `testdata/vectors/calls.json` (`call_id` `01JB2Z6V9K3M4N5P6Q7R8S9T50`,
`k_call` `249a59aa1f48257c5fcf41f064b7ea6e39266b392f7f013e5416ee1a016ab528`). The values below were computed twice,
by this module (`CallVectorsTest`) and independently with Python's `hmac` and `cryptography`.

| | |
|---|---|
| offered `1`, chosen `1`: `k_dir("caller")` | `df07dfb74e30ab8b952a864b0214911649859b9d371d50cc04daa84d5c952c08` |
| … its frame key | `df487affce123283952f691628c1c2de` |
| offered `1`, chosen `1`: `k_dir("callee")` | `8e8b93784e37abb2257c2d42e47a0839e5aee933610f511c218199ebb7da637e` |
| … its frame key | `81e8788d323103611d582bd089af9be4` |
| offered `2 1`, chosen `2`: `k_dir("caller")` | `129e7ea015351c448613b72d821141780fbc2681c9562f2b47894d999c609617` |
| … its frame key (suite 2, 32 bytes; provisional, §2) | `e2637ed9e4ab88d26b3f34869104e58d355e1eb7b6c5b7d3aea521bda4fd83da` |
| offered `2 1`, chosen `1`: `k_dir("caller")` | `eb7c3d3b7c0d8d3a42b85e908c9f9dbf5b76a56a11e91d0e9138134c7328aec0` |

One audio frame under the first frame key: frame `78797a7b7c7d7e7f808182838485868788898a8b` (20 bytes, the first
one clear), SSRC `0x11223344`, RTP timestamp 960, counter 0:

```
iv     11223344000003c0000003c0
sealed 780a14a5d44d9ba28ccf9ad78a82e7855c69d9584297c8aef4c5bf19d8a503b6d6aae36a11223344000003c0000003c00c00
```

## 9. Edits this needs in vettid.org's documents

- **VAULT-MESSAGING §10.10**: a subsection "Media suites and keys" with §2–§4 (the registry, the two SDP
  attributes, one Opus audio section, the refusal rules and what each side sends, what protects the negotiation at
  S10 and the requirement before a second suite is offered, the derivation); a device ends a call 12 h after the
  answer; a `call.start` that fails after it may have left is followed by `call.end{hangup}`; "the callee gathers no candidates before answering"; "media that does not decrypt is never
  played, and the call ends `failed`"; the calling device sends `call.end{timeout}` at `exp` and the callee's
  devices do not; a device ends its own stale calls after unlock (until V2 and V3 are in the vault).
- **VAULT-MESSAGING §10 (the call key)**: `k_call` is input to the direction keys, not a frame key.
- **VAULT-MESSAGING §11.10.8**: the app learns that a release has no `calls` from `unsupported_type` to `call.list`
  (already "No list in the protocol"; say that this is the intended probe).
- **VAULT-MESSAGING §16**: `calls.json` (vettid-vault PR #67) and the vectors of §8.
- **CALLING-SERVICE §7**: items 2 and 3 replaced by §3–§6 (AES-128-GCM in the stack's format, not ChaCha20-Poly1305
  SFrame; no ratchet); the 128-bit statement of §6.
- **CALLS-PLAN §6.1**: a vault item for C2: the media suites as signed members of the shares (§2.2, last
  paragraph).
