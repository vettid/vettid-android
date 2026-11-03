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
    fun anItemGetWithAnEmptyRepeatedOrTooLongFieldListIsDenyOnly() {
        fun get(fields: String) = HeldRequests.of("agent", "agent.request", body("""{"op":"item.get","item_id":"i1","fields":$fields}"""))
        assertEquals(HeldRequest.Unshown, get("[]"))
        assertEquals(HeldRequest.Unshown, get("""["f1","f2","f1"]"""))
        val many = (1..65).joinToString(",", "[", "]") { "\"f$it\"" }
        assertEquals(HeldRequest.Unshown, get(many))
        val ok = (1..64).joinToString(",", "[", "]") { "\"f$it\"" }
        assertTrue(get(ok) is HeldRequest.ItemGet)
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
    fun hostileInvisibleTextIsShownAsHex() {
        val hostile = listOf(
            "ab\u115Fcd", "ab\u1160cd", "ab\u3164cd", "ab\uFFA0cd", // Hangul fillers
            "ab\u00ADcd", "ab\uFE0Fcd", "ab\uFE00cd", "ab\uDB40\uDD00cd", // variation selectors (the last is VS17)
            "ab\u034Fcd", // combining grapheme joiner
            "ab\uD83D\uDE00\uDB40\uDC41cd", // a tag character
        )
        hostile.forEach { assertTrue(it, DataView.of(it.toByteArray()) is DataView.Hex) }
    }

    @Test
    fun zalgoIsShownAsHexButOrdinaryAccentsAreText() {
        // More than 2 combining marks on one base.
        assertTrue(DataView.of("a\u0300\u0301\u0302".toByteArray()) is DataView.Hex)
        val zalgo = "z" + "\u0300\u0301\u0302\u0303\u0304\u0305"
        assertTrue(DataView.of(zalgo.toByteArray()) is DataView.Hex)
        // Two marks are fine, and the count restarts after a base.
        assertTrue(DataView.of("a\u0300\u0301 b\u0300\u0301".toByteArray()) is DataView.Text)
        assertTrue(DataView.of("e\u0301".toByteArray()) is DataView.Text)
    }

    @Test
    fun moreThanEightLinesIsShownAsHex() {
        val eight = (1..8).joinToString("\n") { "line $it" }
        assertTrue(DataView.of(eight.toByteArray()) is DataView.Text)
        val nine = (1..9).joinToString("\n") { "line $it" }
        assertTrue(DataView.of(nine.toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("\n".repeat(200).toByteArray()) is DataView.Hex)
    }

    @Test
    fun aLongTextOnOneLineIsStillText() {
        val long = "a".repeat(HeldRequests.MAX_DATA)
        assertTrue(DataView.of(long.toByteArray()) is DataView.Text)
    }

    @Test
    fun nameableNeedsTheItemAndEveryFieldInTheCache() {
        val items = listOf(
            com.vettid.core.data.items.ItemSummary(
                "i1", 1, "Bank", "other", com.vettid.core.data.items.Sensitivity.SECRET,
                fieldLabels = listOf(com.vettid.core.data.items.FieldLabel("f1", "PIN", "password")),
            ),
        )
        assertTrue(HeldRequests.nameable(HeldRequest.ItemUse("i1", "f1", byteArrayOf(1)), items))
        assertFalse(HeldRequests.nameable(HeldRequest.ItemUse("i1", "f9", byteArrayOf(1)), items))
        assertFalse(HeldRequests.nameable(HeldRequest.ItemUse("x", "f1", byteArrayOf(1)), items))
        assertTrue(HeldRequests.nameable(HeldRequest.ItemGet("i1", null), items))
        assertTrue(HeldRequests.nameable(HeldRequest.ItemGet("i1", listOf("f1")), items))
        assertFalse(HeldRequests.nameable(HeldRequest.ItemGet("i1", listOf("f1", "f9")), items))
        assertFalse(HeldRequests.nameable(HeldRequest.ItemGet("x", null), items))
        assertFalse(HeldRequests.nameable(HeldRequest.ItemGet("i1", null), emptyList()))
        assertTrue(HeldRequests.nameable(HeldRequest.Catalog, emptyList()))
        assertFalse(HeldRequests.nameable(HeldRequest.Unshown, items))
    }

    @Test
    fun nameableNeedsNamesThatCleanToSomething() {
        val items = listOf(
            com.vettid.core.data.items.ItemSummary(
                "i1", 1, "\u202E\u2066\u2069", "other", com.vettid.core.data.items.Sensitivity.SECRET,
                fieldLabels = listOf(com.vettid.core.data.items.FieldLabel("f1", "PIN", "password")),
            ),
            com.vettid.core.data.items.ItemSummary(
                "i2", 1, "Bank", "other", com.vettid.core.data.items.Sensitivity.SECRET,
                fieldLabels = listOf(
                    com.vettid.core.data.items.FieldLabel("f1", "\u0007\u200B", "password"),
                    com.vettid.core.data.items.FieldLabel("f2", "PIN", "password"),
                ),
            ),
        )
        assertFalse("an item whose name cleans to nothing", HeldRequests.nameable(HeldRequest.ItemUse("i1", "f1", byteArrayOf(1)), items))
        assertFalse(HeldRequests.nameable(HeldRequest.ItemGet("i1", null), items))
        assertFalse("a field whose label cleans to nothing", HeldRequests.nameable(HeldRequest.ItemUse("i2", "f1", byteArrayOf(1)), items))
        assertFalse(HeldRequests.nameable(HeldRequest.ItemGet("i2", listOf("f1", "f2")), items))
        assertTrue(HeldRequests.nameable(HeldRequest.ItemUse("i2", "f2", byteArrayOf(1)), items))
    }

    @Test
    fun theBrailleBlankAndLongBlankRunsAreShownAsHex() {
        assertTrue(DataView.of("a\u2800b".toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("\u2800".toByteArray()) is DataView.Hex)
        for (space in listOf(" ", "\u00A0", "\u2000", "\u200A", "\u202F", "\u205F", "\u3000", "\t")) {
            assertTrue("8 of ${space.codePointAt(0)} are text", DataView.of("a${space.repeat(8)}b".toByteArray()) is DataView.Text)
            assertTrue("9 of ${space.codePointAt(0)} are hex", DataView.of("a${space.repeat(9)}b".toByteArray()) is DataView.Hex)
        }
        // A mixed run counts together; a visible character breaks it.
        assertTrue(DataView.of("a \u00A0\u2003\t \u3000 \u202F b".toByteArray()) is DataView.Hex)
        assertTrue(DataView.of("a        b        c".toByteArray()) is DataView.Text)
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
