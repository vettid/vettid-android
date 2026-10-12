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
| Media suite | a number; **1 = AES-128-GCM frames in the media stack's frame-cryptor format** (the only one today) |
| Negotiation | in the SDP: the caller lists its suites, the answerer names one (§2) |
| Keys | two direction keys of 32 bytes from `k_call`, with the suite and the offered list in the derivation (§3) |
| Frame key (suite 1) | first 16 bytes of `HKDF-SHA-256(direction key, "vettid/vms/2/call-frame", 128 zero bytes)` (§4) |
| Frame (suite 1) | `clear header ‖ ciphertext ‖ tag(16) ‖ IV(12) ‖ 0x0C ‖ 0x00` (§5) |

## 2. Negotiating the suite [P]

- The **offer**'s SDP carries one session-level attribute (before the first `m=` line):
  `a=vettid-media-suites:<n> <n> …` — the caller's suites as decimal numbers without leading zeros, 1 to 8 of them,
  each 1–65535, no repeats, separated by exactly one space, the preferred one first.
- The **answer**'s SDP carries one session-level attribute `a=vettid-media-suite:<n>`: the first suite of the
  caller's list, in the caller's order, that the answerer supports.
- A device MUST NOT ring for an offer, and MUST NOT use an answer, in which the attribute is missing, repeated, not
  at session level or malformed, or (answer) names a suite that was not offered. A callee that supports none of the
  offered suites ends the call (`call.end{failed}`).
- Why the SDP: at S10 the vault builds `call.offer` and `call.answer` from named members and passes `sdp` through as
  an opaque string, so this is the only place that reaches the other device without a vault change. Unknown SDP
  attributes are ignored by every SDP parser (RFC 8866 §5.13).
- **The SDP is not signed.** A vault on the path (the member's own, or the connection's) can change the attribute.
  That is why the chosen suite **and the caller's whole list** are part of the key derivation (§3): if either was
  changed on the way, the two devices derive different keys, no frame decrypts, and the call ends (§7). A list cut
  down to a weaker suite therefore cannot produce a working call. With one suite there is nothing to downgrade to;
  the binding is there so that adding a suite later changes no derivation.
- **C2:** carry the list and the choice as signed members of `call.start` / `call.offer` / `call.answer` (in the
  share's signed bytes), which needs a vault release. The derivation of §3 stays as it is.

## 3. The direction keys [P]

```
k_dir = HKDF-SHA-256(ikm  = k_call,
                     salt = "vettid/vms/2/call-media",
                     info = chosen || 0x00 || offered || 0x00 || direction || 0x00 || call_id,
                     L    = 32)

chosen    the suite of the answer, decimal ASCII                          e.g. "1"
offered   the caller's list as sent: decimal numbers joined by one space  e.g. "2 1"
direction "caller" for frames the calling device sends,
          "callee" for frames the answering device sends
call_id   the call's id (a ULID), as in the signalling
```

- The calling device encrypts with `k_dir("caller")` and decrypts with `k_dir("callee")`; the answering device the
  reverse. Each device wipes `k_call` once it has the two keys, and both keys when the call ends.
- A direction key is 32 bytes for every suite: it is key *material* for the suite's own frame-key derivation (§4).
  A 256-bit suite is then a new suite number and a longer frame key; nothing in §2 or §3 changes.
- Two keys, because suite 1's IV holds no randomness (§6): with one key for both directions its uniqueness would
  rest on the two senders' SSRCs differing.

## 4. Suite 1: the frame cryptor's configuration and the frame key

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
| `discardFrameWhenCryptorNotReady` | true | |

One sender cryptor (on the audio sender, participant id `send`) and one receiver cryptor (on the audio receiver,
participant id `receive`); `setKey("send", 0, k_dir(mine))`, `setKey("receive", 0, k_dir(theirs))`. The participant
id only selects a key in the provider: it is not in the frame and not in any derivation **[S]**.

```
frame_key = HKDF-SHA-256(ikm = k_dir, salt = "vettid/vms/2/call-frame", info = 128 bytes of 0x00, L = 16)
```

The length 16 is a literal in the stack (`SetKeyFromMaterial`), whatever the length of the material **[S]**, **[D]**
(the C0 self-check compared the native output with this derivation). AES-256-GCM exists in the code but cannot be
reached through the key provider **[S]**.

## 5. Suite 1: the encrypted frame [S] [D]

```
header   = frame[0 .. u)                    u = clear bytes (audio: 1, for Opus the TOC byte)
iv       = BE32(ssrc) || BE32(rtp_timestamp) || BE32(rtp_timestamp - (n mod 65535))      n = per-SSRC frame counter, from 0
sealed   = AES-128-GCM(frame_key, iv, aad = header, plaintext = frame[u ..))             ciphertext || 16-byte tag
out      = header || sealed || iv || 0x0C || key_index                                   key_index = 0x00
```

- It runs on encoded frames, after the encoder and before RTP packetization, so RTP headers, RTCP and DTLS-SRTP
  underneath are untouched. A frame grows by 30 bytes (12 kbit/s at 50 Opus frames a second).
- Video (C4): VP8 keeps 10 bytes of a key frame and 3 of a delta frame clear; H.264 keeps everything up to the
  first two bytes of the first slice NAL unit clear and RBSP-escapes the rest; AV1 and VP9 keep nothing clear.
- The receiver reads the IV and the key index from the frame. A frame that fails authentication is **dropped**: the
  decoder gets nothing, so the listener hears silence, never noise or clear audio.
- The two trailer bytes are not authenticated (changing them only makes the frame undecryptable) **[I]**.
- An empty frame passes through unchanged in both directions.

## 6. Properties and limits of suite 1

- **128-bit frame keys.** `k_call` comes from MLKEM768X25519 (NIST category 3); AES-128 is category 1, so the frame
  cipher is the weakest part of a call's confidentiality. The owner accepted it for now (2026-10-12); a 256-bit
  suite is a separate piece of work (§3 keeps it a parameter change).
- **The IV is deterministic.** It is unique under a key while (SSRC, RTP timestamp) does not repeat with the same
  counter value: within one RTP stream until the timestamp wraps (about 24.8 hours of Opus at 48 kHz). The vault
  stops counting a call as live after 12 hours. One key per direction removes the cross-sender case.
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
   dereferences the sender's track) **[S]**.
3. **A sender without a key sends nothing** (`MISSINGKEY`) **[S]**: the calling device, which has no key before the
   answer, therefore sends no audio until it has set its key.
4. **Media that does not decrypt under the call's keys is never played**, and the call does not continue without
   it: on `DECRYPTIONFAILED` (or a persistent `MISSINGKEY`) of the receiver the device ends the call with
   `call.end{failed}` and says that the call could not be encrypted. It does not set the key again: a receiver that
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
| offered `2 1`, chosen `1`: `k_dir("caller")` | `eb7c3d3b7c0d8d3a42b85e908c9f9dbf5b76a56a11e91d0e9138134c7328aec0` |

One audio frame under the first frame key: frame `78797a7b7c7d7e7f808182838485868788898a8b` (20 bytes, the first
one clear), SSRC `0x11223344`, RTP timestamp 960, counter 0:

```
iv     11223344000003c0000003c0
sealed 780a14a5d44d9ba28ccf9ad78a82e7855c69d9584297c8aef4c5bf19d8a503b6d6aae36a11223344000003c0000003c00c00
```

## 9. Edits this needs in vettid.org's documents

- **VAULT-MESSAGING §10.10**: a subsection "Media keys" with §2–§4 (the two SDP attributes, the derivation, the
  refusal rules); "the callee gathers no candidates before answering"; "media that does not decrypt is never
  played, and the call ends `failed`"; the calling device sends `call.end{timeout}` at `exp` and the callee's
  devices do not; a device ends its own stale calls after unlock (until V2 and V3 are in the vault).
- **VAULT-MESSAGING §10 (the call key)**: `k_call` is input to the direction keys, not a frame key.
- **VAULT-MESSAGING §11.10.8**: the app learns that a release has no `calls` from `unsupported_type` to `call.list`
  (already "No list in the protocol"; say that this is the intended probe).
- **VAULT-MESSAGING §16**: `calls.json` (vettid-vault PR #67) and the vectors of §8.
- **CALLING-SERVICE §7**: items 2 and 3 replaced by §3–§6 (AES-128-GCM in the stack's format, not ChaCha20-Poly1305
  SFrame; no ratchet); the 128-bit statement of §6.
- **CALLS-PLAN §6.1**: a vault item for C2: the media suites as signed members (§2, last bullet).
