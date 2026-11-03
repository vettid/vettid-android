// Bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** ANDROID-PLAN 0.1.116 §4 AA5: what a held request is, and decision 18 (`item.use`'s data as text or hex). */
class HeldRequestsTest {
    private fun body(json: String) = VaultJson.parseObject(json.toByteArray())

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun use(data: String, extra: String = "") =
        body("""{"op":"item.use","item_id":"i1","field_id":"f1","action":"hmac-sha256","data":"$data"$extra}""")

    @Test
    fun anItemUseReadsWithItsDataBytes() {
        val h = HeldRequests.of("agent", "agent.request", use(b64("challenge".toByteArray()))) as HeldRequest.ItemUse
        assertEquals("i1", h.itemId)
        assertEquals("f1", h.fieldId)
        assertEquals("challenge", String(h.data))
        assertTrue(h.approvable)
    }

    @Test
    fun anItemUseThatDoesNotReadInFullIsNeverApprovable() {
        val bad = listOf(
            use("not base64!"),
            use(""), // 0 bytes
            use(b64(ByteArray(HeldRequests.MAX_DATA + 1))),
            body("""{"op":"item.use","item_id":"i1","field_id":"f1","action":"sha1","data":"${b64(byteArrayOf(1))}"}"""),
            body("""{"op":"item.use","field_id":"f1","action":"hmac-sha256","data":"${b64(byteArrayOf(1))}"}"""),
            body("""{"op":"item.use","item_id":"i1","action":"hmac-sha256","data":"${b64(byteArrayOf(1))}"}"""),
        )
        bad.forEach { assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", it)) }
        // The largest allowed data reads.
        assertTrue(HeldRequests.of("agent", "agent.request", use(b64(ByteArray(HeldRequests.MAX_DATA)))).approvable)
    }

    @Test
    fun anItemGetNamesItsFieldsOrAllOfThem() {
        val some = HeldRequests.of("agent", "agent.request", body("""{"op":"item.get","item_id":"i1","fields":["f1","f2"]}""")) as HeldRequest.ItemGet
        assertEquals(listOf("f1", "f2"), some.fieldIds)
        val all = HeldRequests.of("agent", "agent.request", body("""{"op":"item.get","item_id":"i1"}""")) as HeldRequest.ItemGet
        assertNull(all.fieldIds)
        // A `fields` that is not a list of strings is not shown unseen.
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body("""{"op":"item.get","item_id":"i1","fields":[1]}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body("""{"op":"item.get","item_id":"i1","fields":"f1"}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body("""{"op":"item.get"}""")))
    }

    @Test
    fun theCatalogIsApprovableAndRunAndProxyAreDenyOnly() {
        assertEquals(HeldRequest.Catalog, HeldRequests.of("agent", "agent.request", body("""{"op":"catalog"}""")))
        for (op in listOf("item.run", "item.proxy")) {
            val h = HeldRequests.of("agent", "agent.request", body("""{"op":"$op","item_id":"i1","field_id":"f1"}"""))
            assertTrue(h is HeldRequest.Later)
            assertEquals("i1", (h as HeldRequest.Later).itemId)
            assertFalse(h.approvable)
        }
    }

    @Test
    fun aDesktopsStepUpAndAnythingElseAreDenyOnly() {
        assertEquals(HeldRequest.DesktopStepUp, HeldRequests.of("desktop", "item.put", body("""{"x":1}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "message.send", body("""{"connection_id":"c1","text":"hi"}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body("""{"op":"item.delete"}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", null))
        assertEquals(HeldRequest.Unshown, HeldRequests.of(null, "agent.request", body("""{"op":"catalog"}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("app", "agent.request", body("""{"op":"catalog"}""")))
        listOf(HeldRequest.Later(null), HeldRequest.DesktopStepUp, HeldRequest.Unshown).forEach { assertFalse(it.approvable) }
    }

    // --- decision 18 ---

    @Test
    fun printableUtf8IsShownAsText() {
        assertEquals(DataView.Text("hello world"), DataView.of("hello world".toByteArray()))
        assertEquals(DataView.Text("line one\nline two\ttab"), DataView.of("line one\nline two\ttab".toByteArray()))
        assertEquals(DataView.Text("café 日本語 😀"), DataView.of("café 日本語 😀".toByteArray()))
    }

    @Test
    fun everythingElseIsShownAsHex() {
        assertEquals(DataView.Hex("00 01 ff"), DataView.of(byteArrayOf(0, 1, -1)))
        // Invalid UTF-8 (a lone continuation byte; an overlong form).
        assertTrue(DataView.of(byteArrayOf(0x80.toByte(), 0x41)) is DataView.Hex)
        assertTrue(DataView.of(byteArrayOf(0xC0.toByte(), 0xAF.toByte())) is DataView.Hex)
        // A control character, a NUL, an escape.
        assertTrue(DataView.of("a\u0000b".toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("a\u001bb".toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("a\rb".toByteArray()) is DataView.Hex)
        // A right-to-left override or isolate could reorder what the member reads; so could a zero-width character.
        assertTrue(DataView.of("pay ‮evil".toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("a⁦b⁩".toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("a​b".toByteArray()) is DataView.Hex)
        // Line and paragraph separators, a private-use and an unassigned code point.
        assertTrue(DataView.of("a b".toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("ab".toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("a͸b".toByteArray()) is DataView.Hex)
    }

    @Test
    fun hexIsLowerCaseTwoDigitsAByte() {
        assertEquals("de ad be ef", DataView.hex(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
    }

    @Test
    fun nothingTheAgentSentIsPrinted() {
        val h = HeldRequests.of("agent", "agent.request", use(b64("SECRETISH-CHALLENGE".toByteArray())))
        assertFalse(h.toString().contains("SECRET"))
        val get = HeldRequests.of("agent", "agent.request", body("""{"op":"item.get","item_id":"i-private","fields":["f-private"]}"""))
        assertFalse(get.toString().contains("private"))
        val r = Approval.DeviceRequest("approval.pending", "h1", "Bot-name", "agent", "agent.request", java.time.Instant.EPOCH, null, deviceId = "dev-id", held = h)
        val text = r.toString()
        assertFalse(text, text.contains("SECRET") || text.contains("Bot-name") || text.contains("dev-id") || text.contains("h1"))
    }
}
