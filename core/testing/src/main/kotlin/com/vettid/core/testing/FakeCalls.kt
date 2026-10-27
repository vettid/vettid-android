package com.vettid.core.testing

import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow

/** TEST ONLY. What every build knows of calls, in memory: the availability a test sets, the calls it was asked for. */
class FakeCalls(availability: CallAvailability = CallAvailability.READY) : CallAccess {
    override val availability = MutableStateFlow(availability)
    val called = mutableListOf<String>()
    var entries: List<CallHistoryEntry> = emptyList()
    var historyReads = 0

    override fun call(connectionId: String) {
        called += connectionId
    }

    override suspend fun history(connectionId: String): List<CallHistoryEntry> {
        historyReads++
        return entries.filter { it.connectionId == connectionId }
    }
}
