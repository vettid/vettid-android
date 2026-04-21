package com.vettid.app.core.nats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects peer presence heartbeats from OwnerSpaceClient and
 * maintains a per-connection "is the peer online?" signal.
 *
 * An observer considers a peer online if a heartbeat arrived within
 * the last [timeoutMs] (~2× the peer's publish interval). Absence of
 * heartbeats is not itself a signal — connections we've never heard
 * from stay neutral (not online, not explicitly offline). See
 * plans/luminous-unifying-manatee.md §15.
 */
@Singleton
class PresenceAggregator @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // connectionId → unix-seconds timestamp of most recent heartbeat
    private val _online = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Map of online peers keyed by connectionId. Value is the last heartbeat time. */
    val online: StateFlow<Map<String, Long>> = _online.asStateFlow()

    init {
        scope.launch {
            ownerSpaceClient.presenceHeartbeats.collect { hb ->
                val current = _online.value.toMutableMap()
                current[hb.connectionId] = hb.at
                _online.value = current
            }
        }
        // Sweeper: drop entries whose last heartbeat is older than
        // [timeoutMs]. Peers publish every ~30s; a 90s window is
        // forgiving enough to survive one missed beat without
        // flapping the ring off/on.
        scope.launch {
            while (isActive) {
                delay(15_000)
                val cutoff = (System.currentTimeMillis() / 1000) - (TIMEOUT_MS / 1000)
                val current = _online.value
                val pruned = current.filterValues { it > cutoff }
                if (pruned.size != current.size) {
                    _online.value = pruned
                }
            }
        }
    }

    /** True if we've seen a heartbeat from this connection within the timeout. */
    fun isOnline(connectionId: String): Boolean {
        val lastAt = _online.value[connectionId] ?: return false
        val cutoff = (System.currentTimeMillis() / 1000) - (TIMEOUT_MS / 1000)
        return lastAt > cutoff
    }

    companion object {
        private const val TIMEOUT_MS = 90_000L
    }
}
