package com.vettid.core.notify

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.BackgroundVault
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the on-phone service shows in its own notification and in Settings → Notifications. */
enum class KeeperStatus { CONNECTING, CONNECTED, WAITING_FOR_NETWORK, LOCKED, CHECK_DUE }

/**
 * The on-phone service's work (ANDROID-PLAN 0.1.23, Notification modes 3): keeps the app's own relay connection (the
 * `MailboxCollector` the app already runs, one per process) while the vault is open; reconnects with [backoff] when
 * the vault cannot be reached, at once when a validated network comes back, and not while there is none.
 *
 * A locked vault deposits nothing: while locked no relay connection stays open ([BackgroundVault.pauseRelay]), and
 * the vault's state is read at most every [statusEveryMs] (`GET /api/vault/status`, which also finds an unsignalled
 * lock while open); after an unlock in the app it reconnects. [run] returns when the phone has no vault.
 */
class ConnectionKeeper(
    private val vault: BackgroundVault,
    private val network: StateFlow<Boolean>,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val statusEveryMs: Long = STATUS_EVERY_MS,
) {
    private val statusFlow = MutableStateFlow(KeeperStatus.CONNECTING)
    val status: StateFlow<KeeperStatus> = statusFlow.asStateFlow()

    /** Keeps the connection until the phone has no vault (signed out, wiped, a setup in progress). */
    suspend fun run() = coroutineScope {
        // A validated network after none: retry at once, and reconnect a collection that is waiting out a backoff.
        val retries = launch {
            network.drop(1).filter { it }.collect {
                backoff.reset()
                if (vault.phase.value == AppPhase.Unlocked) vault.reconnectNow()
            }
        }
        try {
            while (step()) Unit
        } finally {
            retries.cancel()
            vault.resumeRelay()
        }
    }

    /** One turn of the loop; false when there is nothing to keep. */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun step(): Boolean {
        val p = vault.phase.value
        statusFlow.value = statusOf(p, network.value, vault.held())
        when {
            ModeController.enrolled(p) == false -> return false
            !network.value -> {
                backoff.onDropped()
                network.first { it }
            }
            p == AppPhase.Unlocked -> {
                vault.resumeRelay()
                backoff.onConnected()
                if (!changes(p)) runCatching { vault.refresh() }
            }
            p == AppPhase.Locked -> {
                vault.pauseRelay()
                if (!changes(p)) runCatching { vault.refresh() }
            }
            else -> {
                // Starting, or unreachable: read the vault's state again after the backoff (or a validated network).
                backoff.onDropped()
                withTimeoutOrNull(backoff.nextDelayMs()) { network.drop(1).first { it } }
                runCatching { vault.refresh() }
            }
        }
        return true
    }

    /** Waits up to [statusEveryMs] for the phase or the network to change; false when the time ran out. */
    private suspend fun changes(p: AppPhase): Boolean = withTimeoutOrNull(statusEveryMs) {
        combine(vault.phase, network) { q, n -> q != p || !n }.first { it }
    } != null

    companion object {
        /** At most one status read an hour while nothing changes (ANDROID-PLAN 0.1.23, Notification modes 3). */
        const val STATUS_EVERY_MS = 60L * 60 * 1000

        fun statusOf(p: AppPhase, network: Boolean, held: Boolean): KeeperStatus = when {
            !network -> KeeperStatus.WAITING_FOR_NETWORK
            p == AppPhase.Locked -> KeeperStatus.LOCKED
            p == AppPhase.Unlocked && held -> KeeperStatus.CHECK_DUE
            p == AppPhase.Unlocked -> KeeperStatus.CONNECTED
            else -> KeeperStatus.CONNECTING
        }
    }
}
