// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

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

/** `call.permission.list` (C2), read strictly: an entry that does not read makes the whole answer unreadable. */
class CallPermissionListTest {
    private fun read(json: String) = callPermissionsOf(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)

    @Test
    fun theDifferingConnectionsAreRead() {
        assertEquals(
            listOf(CallPermission("c1", allowed = false, limited = false), CallPermission("c2", allowed = true, limited = true)),
            read("""{"connections":[{"connection_id":"c1","allowed":false,"limited":false},{"connection_id":"c2","allowed":true,"limited":true}]}"""),
        )
        assertEquals(emptyList<CallPermission>(), read("""{"connections":[]}"""))
    }

    @Test
    fun anEntryThatDoesNotReadMakesItUnreadable() {
        for (bad in listOf(
            """{}""",
            """{"connections":{}}""",
            """{"connections":[{"connection_id":"c1","allowed":"false","limited":false}]}""",
            """{"connections":[{"connection_id":"c1","limited":false}]}""",
            """{"connections":[{"connection_id":"","allowed":false,"limited":false}]}""",
            """{"connections":[7]}""",
            // A connection named twice: one could hide the other's "off".
            """{"connections":[{"connection_id":"c1","allowed":false,"limited":false},{"connection_id":"c1","allowed":true,"limited":false}]}""",
        )) {
            org.junit.Assert.assertThrows(bad, CallMessageException::class.java) { read(bad) }
        }
    }

    @Test
    fun aChangeNamesItsConnection() {
        fun msg(type: String, body: String) = VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T63", type = type, ts = java.time.Instant.parse("2026-11-12T00:00:00Z"), body = body.toByteArray()))
        assertEquals("c1", callPermissionChange(msg("sync.event", """{"kind":"call.permission","connection_id":"c1"}""")))
        assertEquals(null, callPermissionChange(msg("sync.event", """{"kind":"presence.changed"}""")))
        assertEquals(null, callPermissionChange(msg("call.end", """{"kind":"call.permission","connection_id":"c1"}""")))
    }
}
