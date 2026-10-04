/**
 * The typed vault client (VAULT-MESSAGING §6–§10), modelled on vettid-vault's
 * reference client (`client.Device`): the device's keys and state, the first
 * app's enrollment and handshake, sessions with the vault (keyring, epochs,
 * vault-initiated rekeys), envelopes in and out, msg-id and inner-id dedupe,
 * an outbox, token refresh in both directions, the Protean Credential (UTK
 * pool, sealed payloads, reply keys) and one function per §10 type the v1
 * app needs ([VaultApi]); events and sync events as Flows.
 */
package com.vettid.core.vault
