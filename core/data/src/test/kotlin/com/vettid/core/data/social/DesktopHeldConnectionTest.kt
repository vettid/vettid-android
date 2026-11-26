// Bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANDROID-PLAN 0.1.145 §4 "Requests held for approval": a desktop's held `block.add{connection_id, note?}` and
 * `connection.remove{connection_id}`, read strictly ("The desktop block and removal rows fail closed").
 */
class DesktopHeldConnectionTest {
    private fun held(type: String, body: String, top: String = """"device_id":"desk1","name":"Laptop","exp":"2026-11-26T12:05:00Z"""", role: String = "desktop"): HeldRequest {
        val raw = """{"approval_id":"a1","role":"$role","type":"$type","body":$body,$top}"""
        val strict = runCatching { StrictJson.parseObject(raw) }.getOrNull()
        return HeldRequests.of(role, type, VaultJson.parseObject(raw.toByteArray())["body"] as? JsonObject, strict)
    }

    @Test
    fun aBlockAndARemovalReadWithTheirConnectionAndNote() {
        assertEquals(HeldRequest.DesktopConnection(false, "c1", null), held("block.add", """{"connection_id":"c1"}"""))
        assertEquals(HeldRequest.DesktopConnection(false, "c1", "spam\nagain"), held("block.add", """{"connection_id":"c1","note":"spam\nagain"}"""))
        assertEquals(HeldRequest.DesktopConnection(true, "c1", null), held("connection.remove", """{"connection_id":"c1"}"""))
        // A note of exactly 256 bytes is allowed (VAULT-MESSAGING §10.4).
        val note = "é".repeat(128)
        assertEquals(HeldRequest.DesktopConnection(false, "c1", note), held("block.add", """{"connection_id":"c1","note":"$note"}"""))
        listOf(held("block.add", """{"connection_id":"c1"}"""), held("connection.remove", """{"connection_id":"c1"}""")).forEach {
            assertTrue(it.approvable)
            assertTrue(HeldRequests.nameable(it, emptyList()))
        }
    }

    @Test
    fun anythingElseIsTheDenyOnlyDesktopRow() {
        val ok = """"device_id":"desk1","name":"Laptop","exp":"2026-11-26T12:05:00Z""""
        val cases = listOf(
            // The body: another member, a pending_id, no id, an id that is not a string or is empty, a duplicate.
            held("block.add", """{"connection_id":"c1","pending_id":"p1"}"""),
            held("block.add", """{"connection_id":"c1","x":1}"""),
            held("block.add", """{}"""),
            held("block.add", """{"connection_id":5}"""),
            held("block.add", """{"connection_id":""}"""),
            held("block.add", """{"connection_id":"c1","connection_id":"c2"}"""),
            // A removal carries no note.
            held("connection.remove", """{"connection_id":"c1","note":"x"}"""),
            // A note that is not a string, or over 256 bytes.
            held("block.add", """{"connection_id":"c1","note":7}"""),
            held("block.add", """{"connection_id":"c1","note":"${"é".repeat(128)}a"}"""),
            // The envelope: AA8 "Fail closed"'s 0.1.139 rule, an agent's member, a duplicate.
            held("block.add", """{"connection_id":"c1"}""", """"name":"Laptop","exp":"2026-11-26T12:05:00Z""""),
            held("block.add", """{"connection_id":"c1"}""", """"device_id":"desk1","name":" ","exp":"2026-11-26T12:05:00Z""""),
            held("block.add", """{"connection_id":"c1"}""", """"device_id":"desk1","name":"Laptop""""),
            held("block.add", """{"connection_id":"c1"}""", """"device_id":"desk1","name":"Laptop","exp":"soon""""),
            held("block.add", """{"connection_id":"c1"}""", """$ok,"flags":["history"]"""),
            held("block.add", """{"connection_id":"c1"}""", """$ok,"replaces":"a0""""),
            held("block.add", """{"connection_id":"c1"}""", """$ok,"name":"Other""""),
            // Another step-up type, even with a body of only a connection id.
            held("block.remove", """{"block_id":"b1"}"""),
            held("block.remove", """{"connection_id":"c1"}"""),
            held("settings.set", """{"connection_id":"c1"}"""),
            held("connection.update", """{"connection_id":"c1"}"""),
        )
        cases.forEachIndexed { i, h -> assertEquals("case $i", HeldRequest.DesktopStepUp, h) }
        // Without the strict reading (its source text not known) it is deny-only too.
        assertEquals(HeldRequest.DesktopStepUp, HeldRequests.of("desktop", "block.add", VaultJson.parseObject("""{"connection_id":"c1"}""".toByteArray())))
        // Only a desktop's: any other role, absent included, is not read as one.
        for (role in listOf("agent", "app", "Desktop", "")) {
            assertEquals(role, HeldRequest.Unshown, held("block.add", """{"connection_id":"c1"}""", role = role))
        }
        val raw = """{"approval_id":"a1","type":"block.add","body":{"connection_id":"c1"},"device_id":"desk1","name":"Laptop","exp":"2026-11-26T12:05:00Z"}"""
        assertEquals("no role", HeldRequest.Unshown, HeldRequests.of(null, "block.add", VaultJson.parseObject("""{"connection_id":"c1"}""".toByteArray()), StrictJson.parseObject(raw)))
        // The role and type of the strict reading must be the desktop's and the same as the lenient one's.
        assertEquals("strict role", HeldRequest.DesktopStepUp, HeldRequests.of("desktop", "block.add", VaultJson.parseObject("""{"connection_id":"c1"}""".toByteArray()), StrictJson.parseObject(raw)))
        val asRemove = raw.replace(""""type":"block.add"""", """"type":"connection.remove","role":"desktop"""")
        assertEquals("strict type", HeldRequest.DesktopStepUp, HeldRequests.of("desktop", "block.add", VaultJson.parseObject("""{"connection_id":"c1"}""".toByteArray()), StrictJson.parseObject(asRemove)))
    }
}
