# D #144 — Encrypted persistent message cache (design proposal)

**Status:** Design proposal. Implementation deferred pending architect
review per the wipe-lifecycle concern flagged in the tech-preview
execution-order doc.

**Author:** Track D sweep, 2026-05-17.

## Why

Today, the Conversation screen loads message history from the vault on
every open via `connection.message.list`. On a slow network the user
sees a blank screen for 300-800ms before messages render. The
ChatRepository in-memory cache absorbs back-to-back opens of the same
conversation but doesn't survive process restarts, so the
first-open-after-launch is always cold.

D #141 + D #142 + D #143 already collapsed the redundant
`connection.list` calls across the connection/feed surfaces. The
remaining cold-start cost lives in the per-conversation message list.

## Proposed shape

Add an `EncryptedMessageCache` (singleton, Hilt-provided) backed by
the same `EncryptedSharedPreferences` pattern the credential store and
PCR config manager already use. Per-conversation, store the **last N**
messages decrypted at the time of receipt:

```
EncryptedSharedPreferences {
  "msg_cache/<connection_id>" → JSON.encode(List<CachedMessage>)
}
data class CachedMessage(
  val messageId: String,
  val direction: "sent" | "received",
  val ciphertext: String,   // server-encrypted form, not plaintext
  val createdAt: Long,
)
```

**Key decision: cache ciphertext, not plaintext.** The conversation
ViewModel already has the session key for decrypt; persisting
ciphertext means the cache file leaks no sensitive content even if the
device is rooted and the EncryptedSharedPreferences master key is
extracted. Decrypt happens in-memory on cache read, no second
encryption layer to mismanage.

Bounded at N = 50 entries per conversation (covers ~1 page on screen);
trimmed via tail-drop on insert. Per-conversation file lets us GC
individual conversations without rewriting the whole cache.

## The wipe lifecycle (the architect-review hook)

Five trigger points where the cache MUST be cleared:

1. **Vault lock / app background-then-foreground past session TTL.**
   Already an existing observer in `AppViewModel.observeSessionExpiry`
   and `observeVaultLock`. Add the cache-clear callback there.

2. **Explicit logout.** `LocalDataWiper` already runs on logout; this
   becomes one more entry in its sweep list.

3. **Decommission.** Same path as logout — `scripts/decommission-vault.sh`
   shells out to `adb shell pm clear` which nukes
   EncryptedSharedPreferences along with everything else.

4. **Connection revoke.** When `connection.revoked` fires for a
   peer, drop their cache file. Avoids residual messages from a
   peer the user explicitly disconnected.

5. **Per-conversation session-key rotation.** The vault rotates the
   E2E session key periodically; the ciphertext stored under the old
   key can no longer be decrypted. Either:
   - (a) drop cache on key rotation, or
   - (b) re-encrypt cache entries under the new key during rotation.
   (a) is simpler; the user takes one cold load post-rotation. (b)
   preserves UX continuity but doubles the rotation cost. **Recommend (a).**

## Out of scope for D #144

- **Search.** The full audit FTS5 index already covers history search.
  Cache is for render-on-open only.
- **Cross-device sync.** Cache is device-local; the vault remains
  source of truth.
- **Larger windows.** 50 entries is the floor — bumping it to 200+
  means re-evaluating the trim policy and per-file size limits.

## Decision points needing review

1. **Cache ciphertext vs plaintext?** Proposal: ciphertext. Alternative:
   plaintext-with-additional-encryption-key. The latter doubles attack
   surface (a second key to manage); ciphertext-only sidesteps it.
2. **N = 50 messages per conversation?** Could be lower (20) for memory,
   higher (100) for typical scroll-back depth. 50 is a guess based on
   "fills the screen + one scroll."
3. **Per-conversation file vs single combined file?** Proposal:
   per-conversation. Combined would simplify whole-app wipe but
   complicate per-conversation drop on revoke.
4. **Rotation handling — drop vs re-encrypt?** Proposal: drop. Two
   rotation paths exist today (manual + automatic on
   `connection.rotated`); both can call
   `EncryptedMessageCache.dropForConnection(connId)`.

## Estimate

Implementation including tests: ~2-3 days as the execution-order
estimate stated. Per-trigger wipe wiring is the bulk of the integration
work — the cache class itself is ~150 LOC.

## Status until implemented

The ChatRepository in-memory cache continues to absorb back-to-back
opens. First-open-after-launch is the only path that pays the full
vault round-trip; D #142's `personalDataStore.hydrate()` pre-warm is
sequential with NATS connect so the round-trip overlaps with the
connection establishment anyway. The actual UX cost is bounded at the
typical 300-800ms blank-screen pause on the first conversation open.
