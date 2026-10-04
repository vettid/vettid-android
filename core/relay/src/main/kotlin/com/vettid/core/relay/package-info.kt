/**
 * Relay client (RELAY-PROTOCOL 0.5.0), mirroring vettid-relay's `relayclient`
 * and `relayauth`: mailbox ids, signed requests (Ed25519 over the canonical
 * digest, millisecond timestamps), PASETO v4.public deposit tokens (standing
 * and one-shot open, `iat` backdated 60 s), register, deposit, collect
 * (long-poll and WebSocket) with ack, denylist, rotation, mailbox deletion,
 * claims and blobs, with retries and backoff per the relay's CLIENT-NOTES.
 */
package com.vettid.core.relay
