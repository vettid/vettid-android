package com.vettid.core.vault

import com.vettid.core.crypto.envelope.Inner
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** `call.end` as an event (VAULT-MESSAGING §10.10); (C2, "Glare") its `superseded_by` when it names a call. */
class CallEndEventTest {
    private val x = "01JB2Z6V9K3M4N5P6Q7R8S9T50"
    private val y = "01JB2Z6V9K3M4N5P6Q7R8S9T4Z"

    private fun ended(body: String): List<CallEvent> = runBlocking {
        val m = VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T63", type = "call.end", ts = java.time.Instant.parse("2026-11-12T00:00:00Z"), body = body.toByteArray()))
        callEventsIn(flowOf(m)).toList()
    }

    @Test
    fun supersededByIsReadWhenItNamesAnotherCall() {
        assertEquals(listOf(CallEvent.Ended(x, "busy", y)), ended("""{"call_id":"$x","reason":"busy","superseded_by":"$y"}"""))
        assertEquals(listOf(CallEvent.Ended(x, "busy")), ended("""{"call_id":"$x","reason":"busy"}"""))
        // Not a call id, or the call itself: as if absent.
        assertEquals(listOf(CallEvent.Ended(x, "busy")), ended("""{"call_id":"$x","reason":"busy","superseded_by":"nope"}"""))
        assertEquals(listOf(CallEvent.Ended(x, "busy")), ended("""{"call_id":"$x","reason":"busy","superseded_by":"$x"}"""))
        assertEquals(listOf(CallEvent.Ended(x, "busy")), ended("""{"call_id":"$x","reason":"busy","superseded_by":7}"""))
    }
}
