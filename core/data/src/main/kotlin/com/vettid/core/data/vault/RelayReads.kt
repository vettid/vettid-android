package com.vettid.core.data.vault

import com.vettid.core.vault.VaultStatusInfo

/**
 * (VAULT-MESSAGING 0.84.0 §3.4, §10.2) A `vault.status` read outside the owner check that decides the relay notice too, as
 * vettid-ios #221 hears every status read: the Settings overview's (`VaultManager.overview`). Kept out of the manager so
 * that its rules are tested (review of #301, F3).
 */
internal object RelayReads {
    /**
     * Stamps the read ([OwnerCheckManager.beginStatusRead]) before [read] sends it, so a lock or a newer read since drops
     * its answer, and applies the answer's `relay` ([OwnerCheckManager.noteRelay]). Returns the status, or null when
     * [read] got none (nothing is applied then).
     */
    suspend fun read(ownerCheck: OwnerCheckManager, read: suspend () -> VaultStatusInfo?): VaultStatusInfo? {
        val stamp = ownerCheck.beginStatusRead()
        val status = read() ?: return null
        ownerCheck.noteRelay(stamp, status.relayRefused)
        return status
    }
}
