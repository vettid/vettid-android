package com.vettid.core.testing

import com.vettid.core.data.presence.PresenceAvailability
import com.vettid.core.data.presence.PresencePolicy
import com.vettid.core.data.presence.PresenceReading
import com.vettid.core.data.presence.PresenceRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TEST ONLY. An in-memory Presence repository (ANDROID-PLAN §4 "Presence") for ViewModel tests: [policy] is the member's
 * policy, [readings] what the connections answer; [update] applies the change at once (a failure in [fail] is thrown
 * instead); [queries] lists the connections asked.
 */
class FakePresence(available: Boolean = true, initial: PresencePolicy? = PresencePolicy(version = 1)) : PresenceRepository {
    val queries = mutableListOf<String>()
    var fail: VaultFailure? = null

    override val availability = MutableStateFlow(if (available) PresenceAvailability.AVAILABLE else PresenceAvailability.UNSUPPORTED)
    override val policy = MutableStateFlow(initial)
    override val readings = MutableStateFlow<Map<String, PresenceReading>>(emptyMap())

    /** The connections asked in the last minute; a test sets it (a query here does not). */
    override val recent = MutableStateFlow<Set<String>>(emptySet())

    /** How many times the policy was read (`presence.get`). */
    var refreshes = 0

    override suspend fun refreshPolicy() {
        refreshes++
        fail?.let { throw it }
    }

    override suspend fun update(change: (PresencePolicy) -> PresencePolicy) {
        fail?.let { fail = null; throw it }
        val current = policy.value ?: throw VaultFailure(FailureKind.OTHER, "not_read")
        val next = change(current).let { it.copy(except = PresencePolicy.cleaned(it.except)) }
        if (next == current) return // nothing to send, as the real manager
        policy.value = next.copy(version = current.version + 1)
    }

    override suspend fun query(connectionId: String) {
        queries += connectionId
        readings.value = readings.value + (connectionId to (readings.value[connectionId] ?: PresenceReading.Checking))
    }
}
