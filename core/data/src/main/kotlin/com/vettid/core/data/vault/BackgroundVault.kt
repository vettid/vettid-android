package com.vettid.core.data.vault

import kotlinx.coroutines.flow.StateFlow

/**
 * What the on-phone notification service needs of the vault on this phone (ANDROID-PLAN 0.1.23, Notification modes
 * 3): its phase, whether it is held, a status read, and the relay connection, which stays closed while the vault is
 * locked (a locked vault deposits nothing) and comes back after an unlock. Implemented by [VaultManager].
 */
interface BackgroundVault {
    val phase: StateFlow<AppPhase>

    /** Held or due now (VAULT-MESSAGING §3.6.3). */
    fun held(): Boolean

    /** Reads the vault's state again (`GET /api/vault/status`, signed by the app key); finds an unsignalled lock. */
    suspend fun refresh()

    /** Closes the relay connection while the vault is locked. */
    fun pauseRelay()

    /** Opens it again (after an unlock, or when the service stops). */
    fun resumeRelay()

    /** A validated network came back: collect at once instead of waiting out a backoff. */
    fun reconnectNow()
}
