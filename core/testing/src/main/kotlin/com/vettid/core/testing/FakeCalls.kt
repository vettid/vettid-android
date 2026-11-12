package com.vettid.core.testing

import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistory
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallPermissions
import com.vettid.core.data.calls.ConnectionCalls
import kotlinx.coroutines.flow.MutableStateFlow

/** TEST ONLY. What every build knows of calls, in memory: the availability a test sets, the calls it was asked for. */
class FakeCalls(availability: CallAvailability = CallAvailability.READY) : CallAccess {
    override val availability = MutableStateFlow(availability)
    val called = mutableListOf<String>()
    var entries: List<CallHistoryEntry> = emptyList()

    /** Whether [history] says some calls could not be shown. */
    var someNotShown = false
    var historyReads = 0

    override fun call(connectionId: String) {
        called += connectionId
    }

    override suspend fun history(connectionId: String): CallHistory {
        historyReads++
        return CallHistory(entries.filter { it.connectionId == connectionId }, someNotShown)
    }

    override val permissions = MutableStateFlow(CallPermissions())

    /** What [setAllowed] was asked, in order; [setFailure] is thrown instead when set. */
    val allowedSet = mutableListOf<Pair<String, Boolean>>()
    var setFailure: Exception? = null

    override suspend fun setAllowed(connectionId: String, allowed: Boolean) {
        setFailure?.let { throw it }
        allowedSet += connectionId to allowed
        val p = permissions.value
        val now = p.of(connectionId).copy(allowed = allowed)
        permissions.value = p.copy(differing = if (now == ConnectionCalls()) p.differing - connectionId else p.differing + (connectionId to now))
    }
}
