# Calls C0: the media stack's key and frame format

Status: spike finding (CALLS-PLAN 0.1.1 §4 proof 3, decision D11). Not a specification yet: §10 lists what a
specification (VAULT-MESSAGING §10.10 / CALLING-SERVICE §7) has to add.

**What was read.** The frame cryptor of `io.github.webrtc-sdk:android:150.7871.01` (AAR SHA-256
`0a1627b1…79b0`). Upstream's change log names its source as `webrtc-sdk/webrtc` commit
`73cb8180f7258ee292878d6edd05177f41883962` (`webrtc-sdk/android` tag `v150.7871.01`, `CHANGES.md`); the files
below were read at that commit:

| Short name | File |
|---|---|
| `fct.cc` | `api/crypto/frame_crypto_transformer.cc` (989 lines; Copyright 2022 LiveKit, Apache-2.0) |
| `fct.h` | `api/crypto/frame_crypto_transformer.h` (536 lines; same) |
| `jni` | `sdk/android/src/jni/pc/frame_cryptor.cc`, `frame_cryptor_key_provider.cc` |
| `java` | `sdk/android/api/org/webrtc/FrameCryptor*.java` |

Every statement is marked **[S]** read from source (file and lines), **[I]** inferred, or **[D]** to be confirmed
on a device by the spike screen's "Format self-check" (it compares the native library's output with
`core/calls/…/FrameFormat.kt`, the same format written in Kotlin with tests).

That the published binary was built from exactly that commit rests on upstream's change log **[I]**; two things
corroborate it: the binary carries the log texts of `fct.cc` (` derived_key `, ` slat << `), and the self-check **[D]**.

## 1. Summary

| | |
|---|---|
| Cipher | **AES-128-GCM**, 16-byte tag, 12-byte IV. The AES key is always 128 bits, whatever the length of the key given |
| Key derivation | `frame_key = KDF(material, salt)[0..16]`; KDF is HKDF-SHA-256 with `info` = 128 zero bytes, or PBKDF2-HMAC-SHA-256 with 100,000 rounds (the default) |
| Frame | `clear header ‖ ciphertext ‖ tag(16) ‖ IV(12) ‖ 0x0C ‖ key_index` |
| Associated data | the clear header only |
| Audio clear header | 1 byte (for Opus, the TOC byte) |
| IV | `SSRC ‖ RTP timestamp ‖ (RTP timestamp − frame counter mod 65535)`, each 32 bits big-endian; carried in the frame |
| Growth | 30 bytes a frame (12 kbit/s at 50 Opus frames a second) |
| Replay protection | none of its own (SRTP underneath has it) |
| Ratchet | optional; `material' = KDF(material, salt)[0..32]` |

## 2. Objects

- **Key provider** (`FrameCryptorKeyProvider`, native `DefaultKeyProviderImpl`): holds, per participant id, a ring
  of `key_ring_size` key sets (default 16, at most 256); a key set is `{material, encryption_key}`. **[S]** `fct.h`
  34–35, 104–124, 239–371.
- **Frame cryptor** (`FrameCryptor`, native `FrameCryptorTransformer`): one per RTP sender and one per RTP
  receiver, installed as the sender's or receiver's frame transformer (`SetFrameTransformer`); it runs on encoded
  frames, after the encoder and before RTP packetization (and the reverse), so RTP headers, RTCP and the SRTP layer
  are untouched. **[S]** `jni` `frame_cryptor.cc` (create functions); `fct.cc` 420–448.
- A cryptor is created **disabled**, and a disabled cryptor passes frames through unchanged, in the clear, in both
  directions. **[S]** `jni` (`SetEnabled(false)` after creation); `fct.cc` 485–488, 608–611.

Options of the key provider (`FrameCryptorFactory.createFrameCryptorKeyProvider`, in the order of its
parameters) **[S]** `fct.h` 44–69, `jni`:

| Option | Meaning |
|---|---|
| `sharedKey` | one key for every participant id (§6) |
| `ratchetSalt` | the KDF's salt, for the frame key and for the ratchet alike |
| `ratchetWindowSize` | how many ratchet steps a receiver tries by itself after a failed decryption; 0 = none |
| `uncryptedMagicBytes` | if not empty: a received frame that ends with these bytes is taken as unencrypted and **played without decryption**, the bytes removed (`fct.cc` 613–635) |
| `failureTolerance` | decryption failures after which the key is marked invalid; negative = never (§8) |
| `keyRingSize` | size of the ring; a received key index at or above it is "missing key" |
| `discardFrameWhenCryptorNotReady` | applies to empty frames only (`fct.cc` 476–484, 598–607) |
| `keyDerivationAlgorithm` | `PBKDF2` (0, the default) or `HKDF` (1) |

## 3. From the key to the frame key

`setSharedKey(index, key)` / `setKey(participantId, index, key)` store `key` as the **material** and derive
`encryption_key = KDF(material, ratchet_salt, 128 bits)`. The length is the literal 128 in
`SetKeyFromMaterial`; there is no option for 256. **[S]** `fct.h` 163–166, 179–188, 201–208.

- **HKDF**: `HKDF-SHA-256(ikm = material, salt = ratchet_salt, info = 128 bytes of 0x00, L = 16)`. **[S]** `fct.cc`
  249–272. **[D]**
- **PBKDF2**: `PBKDF2-HMAC-SHA-256(password = material, salt = ratchet_salt, 100,000 iterations, L = 16)`. **[S]**
  `fct.cc` 274–297.
- The material may be any length; nothing checks it. **[S]** (no check in `SetKey`).
- AES-256-GCM exists in the code (`GetAesGcmAlgorithmFromKeySize` accepts 16 or 32 bytes, `fct.cc` 70–79) but is
  unreachable through the key provider, which only ever derives 16 bytes. **[S]**
- `key index`: the slot in the ring (0 … `keyRingSize` − 1). The sender uses the cryptor's `setKeyIndex` value
  (0 at creation) and writes it into every frame; the receiver takes the index from the frame. `setKey` with an
  index also makes that index the handler's current one. **[S]** `fct.h` 201–208, 423–426; `fct.cc` 494, 506, 517,
  649, 666.

A 32-byte key therefore becomes: material = the 32 bytes; frame key = the first 16 bytes of
`HKDF(material, salt, 0^128)`.

## 4. The encrypted frame

Sender **[S]** `fct.cc` 450–569:

```
header   = frame[0 .. u)                    u = clear bytes for this frame (table below)
iv       = makeIv(ssrc, rtp_timestamp)      12 bytes (§5)
sealed   = AES-128-GCM(frame_key, iv, aad = header, plaintext = frame[u ..))   ciphertext ‖ 16-byte tag
out      = header ‖ sealed ‖ iv ‖ 0x0C ‖ key_index
```

For H.264 and H.265 everything after the header (`sealed ‖ iv ‖ trailer`) is then RBSP-escaped
(`H264::WriteRbsp`), so the result contains no start code; the receiver un-escapes it if it finds an escape.
Other codecs are not escaped. **[S]** `fct.cc` 540–553, 698–708.

Clear bytes `u` **[S]** `fct.cc` 171–247:

| Media | Clear (and authenticated) |
|---|---|
| Audio, any codec | 1 byte. For Opus this is the TOC byte: mode, bandwidth, frame duration, channel count; no content |
| VP8 | 10 bytes of a key frame, 3 of a delta frame |
| H.264 | up to and including the first two bytes of the first slice NAL unit (IDR or non-IDR); parameter sets before it stay clear |
| H.265 | up to and including the two-byte header of the first slice NAL unit (only where the build has H.265) |
| AV1 | 0 |
| VP9 and anything else | 0 (no case for them) **[I]** from the absence |

Receiver **[S]** `fct.cc` 571–796: reads the last two bytes (IV length, key index); a length other than 12 →
state `DECRYPTIONFAILED`, frame dropped; a key index outside the ring or without a key → `MISSINGKEY`, dropped;
otherwise it takes the IV from the frame, decrypts with the header as associated data and passes
`header ‖ plaintext` on. A frame that fails authentication is **dropped**, never passed on: the decoder gets
nothing, so the listener hears silence, not noise. **[S]** `fct.cc` 775–783. **[D]** (the spike's wrong-key test)

Not authenticated: the two trailer bytes. (A changed IV fails the tag; a changed IV length or key index makes
the frame undecryptable. Nothing can be gained from either but a dropped frame.) **[I]**

An empty frame (length 0) is passed through as it is in both directions. **[S]** `fct.cc` 476–484, 598–607.

## 5. IV

```
iv = BE32(ssrc) ‖ BE32(rtp_timestamp) ‖ BE32(rtp_timestamp − (n mod 65535))
```

`n` is a per-SSRC frame counter of the sending cryptor. **[S]** `fct.cc` 810–826. The code draws a random start
for `n` and then overwrites it: the counter in fact starts at 0 for the first frame and continues 1, 2, …
(line 813 stores a random value, line 821 replaces it with `0 + 1`). **[S]**

- The IV holds no randomness. It is unique for a key as long as the pair (SSRC, RTP timestamp) does not repeat
  with the same `n mod 65535`. Within one RTP stream the timestamp is monotonic until it wraps (2^32 / 48,000 Hz
  ≈ 24.8 hours of Opus), so a stream does not repeat an IV within a call that is shorter than that. **[I]**
- With **one shared key for both directions**, the two senders are told apart by their SSRCs alone (random 32-bit
  numbers chosen by each side). Equal SSRCs on both sides together with equal timestamps and counters would
  repeat an IV under one key, which for GCM is fatal for those frames. It is improbable, not excluded. **[I]** →
  §10 item 1 removes the case.
- The receiver does not check that IVs are new or increasing: **no replay protection** at this layer. **[S]** (no
  such state in `decryptFrame`). SRTP's replay window underneath covers the network attacker; a TURN server
  holds no SRTP key either. **[I]**

## 6. Shared key and per-participant keys

- **Shared** (`sharedKey = true`): `setSharedKey(index, key)` sets one key set under the name `"shared"` and
  copies it to every participant id already seen; a cryptor with a new participant id gets a clone on first use.
  Every sender and receiver cryptor on that provider uses the same material. `setKey` is not used. **[S]** `fct.h`
  245–263, 295–309; `fct.cc` 490–492, 662–664.
- **Per participant** (`sharedKey = false`): `setKey(participantId, index, key)`; a cryptor uses the keys of the
  participant id it was created with, and `MISSINGKEY` until one is set. **[S]** `fct.h` 312–336.

The participant id is a local name only: it selects keys in the provider and is reported in state callbacks. It
is not in the frame and not in the key derivation. **[S]** Two devices therefore need not agree on ids, only on
which key each direction uses.

## 7. Ratchet

`ratchetSharedKey(index)` / `ratchetKey(participantId, index)`: `material' = KDF(material, ratchet_salt, 256 bits)`,
stored as the new material, and returned to the caller. **[S]** `fct.h` 136–152, 168–177. **[D]** (HKDF)

A receiver with `ratchetWindowSize` > 0 that fails to decrypt tries up to that many ratchet steps, keeps the
first that works (state `KEYRATCHETED`), and otherwise goes back to the key it had. **[S]** `fct.cc` 719–773.

Key separation is weak: the frame key and the next material are the same KDF over the same input, salt and info,
16 and 32 bytes long, so **the frame key is the first half of the next material** (true for HKDF and for one
block of PBKDF2). **[S]** from the two calls; covered by `FrameFormatTest`. VettID's 1:1 calls use a fresh key a
call and need no ratchet: leave `ratchetWindowSize` at 0 and never call the ratchet functions.

## 8. States and failures

`FrameCryptor.Observer.onFrameCryptionStateChanged(participantId, state)`: `NEW`, `OK`, `ENCRYPTIONFAILED`,
`DECRYPTIONFAILED`, `MISSINGKEY`, `KEYRATCHETED`, `INTERNALERROR`; reported on change only. **[S]** `fct.h`
373–381; `fct.cc` 798–808.

- `DECRYPTIONFAILED` after a failed tag is reported **only if `failureTolerance` ≥ 0**, and only once the number
  of failures exceeds it. With a negative tolerance (the default) a wrong key gives silence and no state change.
  **[S]** `fct.h` 210–223; `fct.cc` 775–782.
- The failure count is **not consecutive**: only setting a key (or a successful ratchet) resets it; a
  successful decryption does not. **[S]** `fct.h` 195–199.
- Once the key is marked invalid the receiver **stops trying**: every further frame is dropped unread until a key
  is set again on that device, even if the sender has meanwhile gone back to the right key. **[S]** `fct.cc`
  679–683. For a call this is the right end state (hang up); the spike screen has "Set the key again" to see the
  recovery.
- A sender without a key sends **nothing** (`MISSINGKEY`), not clear frames. **[S]** `fct.cc` 494–504.

## 9. Findings that bind the app

1. **Key material in the log.** Both KDF functions log the input key, the salt and the derived key at severity
   INFO (`RTC_LOG(LS_INFO) << "secret " … " derived_key "`), and the texts are in the shipped binary. **[S]**
   `fct.cc` 264–269, 288–294; `strings` on the arm64 library. The app must never raise the stack's log severity
   to INFO or below and never install an injectable logger at such a level (`Logging.enableLogToDebugOutput`,
   `InitializationOptions.setInjectableLogger`), in any build that handles a real key. Whether the library's
   default leaves these lines unprinted was not observed here (no device): **[I]** from libwebrtc's default of no
   debug output in release builds; C1 should check `logcat` once during a call.
2. **Enable before media.** Create both cryptors and call `setEnabled(true)` before the offer or answer is
   applied; never disable one during a call. Keep `uncryptedMagicBytes` empty. Then nothing that does not
   decrypt under the call's key is played (CALLS-PLAN §3 "Rule for the app"). **[S]** §2, §4.
3. **A sender cryptor needs a track.** Creating one for an RTP sender without a track dereferences null
   (`rtpSender->track()->kind()`). **[S]** `jni` `frame_cryptor.cc`.
4. **Short or hostile frames.** `decryptFrame` reads the trailer and the IV by offsets from the end of the frame
   without checking that the frame is long enough (`data_in[size − 2]`, `size − 2 − ivLength`,
   `encrypted_buffer.size() − ivLength − 2`, `.last(16)`). A peer in the call (the only party that can produce
   valid SRTP) could send frames that make the receiving process read out of bounds or abort. **[I]** from `fct.cc`
   645–716; not tested. It is a denial of service by a connection the member accepted a call from, not a key or
   content exposure; a key ring of 1 makes an accidental hit rarer (the last byte must be a valid key index).
   Worth an upstream report before production.
5. **128-bit keys.** See §11.

## 10. What a specification has to add (proposal for C1)

1. **Two keys, one per direction**, in per-participant mode, so that an IV can never repeat across the two
   senders: e.g. `k_a = HKDF-SHA-256(k_call, salt = "vettid-call-v1", info = "frame caller→callee")` and
   `k_b = … "frame callee→caller"`, 32 bytes each; the caller sets `k_a` for its sender and `k_b` for its
   receiver, the callee the reverse.
2. The provider options, fixed for every platform: per-participant keys, HKDF, `ratchetSalt` (a fixed label, or
   empty), `ratchetWindowSize` 0, no magic bytes, `keyRingSize` 1, key index 0, a `failureTolerance` ≥ 0, and
   the call ended on `DECRYPTIONFAILED`.
3. Test vectors: key → frame key, and one sealed audio frame (`FrameFormat.seal` produces them; the device
   self-check ties them to the native library).
4. A new offer/answer inside a call (`call.update`, V7) keeps the same keys only if the RTP streams keep their
   SSRCs and timestamps moving forward; otherwise new keys.
5. The text of CALLING-SERVICE §7 (§11 below).

## 11. Compared with CALLING-SERVICE 0.1.1 §7, and what D11 gains and loses

| | CALLING-SERVICE §7 (0.1.1) | The stack's format (D11) |
|---|---|---|
| Cipher | ChaCha20-Poly1305 (256-bit key) | AES-128-GCM |
| Shape | "SFrame-style" (RFC 9605: header with key id and counter in front) | LiveKit's own: IV and key index behind the ciphertext; not SFrame, no RFC |
| Nonce | per-sender frame counter | SSRC, RTP timestamp, timestamp − counter; no randomness |
| Keys | `k_call`, ratchet forward per epoch | whatever the app sets; ratchet optional and weak (§7) |
| Replay | not stated | none (SRTP below) |
| Who writes it | VettID, in C++ behind the bare encryptor hooks, per platform | the media stack, the same C++ on every platform |

**Gains.** No security-critical native code of VettID's own; one implementation shared by Android, iOS/macOS and
desktop builds of the same library family (below), so frames interoperate by construction; it exists and is used
in production by LiveKit's SDKs **[I]**; hardware AES on every arm64 phone.

**Loses.**
- **Key strength: 128 bits instead of 256.** `k_call` comes from MLKEM768X25519 (NIST category 3); AES-128 is
  category 1, so the frame cipher becomes the weakest part of a call's confidentiality, classically and against
  a quantum attacker (Grover). It is still the level NIST treats as acceptable; it is not what "post-quantum,
  256-bit" in CALLING-SERVICE §7 suggests, and the documents must say AES-128. Getting 256 bits needs a patch to
  the library (one literal), which defeats the reason for D11 unless upstream takes it.
- A documented standard (SFrame) for a vendor format whose only description is its source and this file.
- GCM's sensitivity to IV reuse, with a deterministic IV (handled by §10 item 1), where ChaCha20-Poly1305 with a
  counter has the same requirement but the §7 design controlled the counter itself.
- The findings of §9 (log lines, bounds) are someone else's code to get fixed.

**Other platforms of the same family** (checked 2026-10-12 by `git ls-remote` and raw file fetches; versions as
found): iOS/macOS/tvOS/visionOS `WebRTC-SDK` (CocoaPods and Swift package, `webrtc-sdk/Specs` tag `150.7871.01`),
with `sdk/objc/api/peerconnection/RTCFrameCryptor.h` in the same source commit; desktop C++ wrapper
`webrtc-sdk/libwebrtc` (`include/rtc_frame_cryptor.h`; release `libwebrtc.m150.7871.04` with Linux and Windows
x64/arm64 assets, no macOS asset seen); Rust `livekit/rust-sdks` (`webrtc-sys/src/frame_cryptor.rs`, built from
`webrtc-sdk/webrtc` `m150_release`). All wrap the same `frame_crypto_transformer.cc`. **[S]** for existence;
that each wrapper exposes HKDF and per-participant keys the same way was not read: **[I]**. A browser client
would need LiveKit's JavaScript worker or an own implementation of §3–§5: not checked.
