/**
 * The member API's alternate channel (VAULT-MESSAGING §11, MEMBER-API
 * "Vault"): the member session (magic link + PIN, httpOnly cookies, CSRF
 * header, server-side refresh), the vault routes (enclave descriptor,
 * enroll, unlock, lock, response slots), the release manifest by hash,
 * sealed enroll/unlock/recovery requests and their results, device
 * attestation from the Android Keystore, release-update approval, and the
 * retry rules of §11.9 (instance_moved, etk_unknown, expired slots,
 * release_starting, a `manifest` result).
 */
package com.vettid.core.altchan
