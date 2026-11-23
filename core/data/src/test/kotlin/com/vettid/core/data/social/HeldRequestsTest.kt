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

    /** The whole `approval.pending` for an agent's request [body], with [top] members added, read as the app reads it. */
    private fun envelope(body: String, top: String = "") =
        """{"approval_id":"a1","device_id":"d1","role":"agent","name":"Claude","type":"agent.request","body":$body,"exp":"2026-11-22T12:00:00Z"$top}"""

    private fun held(body: String, top: String = ""): HeldRequest {
        val raw = envelope(body, top)
        val strict = runCatching { com.vettid.core.crypto.json.StrictJson.parseObject(raw) }.getOrNull()
        return HeldRequests.of("agent", "agent.request", VaultJson.parseObject(raw.toByteArray())["body"] as? kotlinx.serialization.json.JsonObject, strict)
    }

    private fun use(data: String, extra: String = "") =
        """{"op":"item.use","item_id":"i1","field_id":"f1","action":"hmac-sha256","data":"$data"$extra}"""

    @Test
    fun anItemUseReadsWithItsDataBytes() {
        val h = held(use(b64("challenge".toByteArray()))) as HeldRequest.ItemUse
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
            """{"op":"item.use","item_id":"i1","field_id":"f1","action":"sha1","data":"${b64(byteArrayOf(1))}"}""",
            """{"op":"item.use","field_id":"f1","action":"hmac-sha256","data":"${b64(byteArrayOf(1))}"}""",
            """{"op":"item.use","item_id":"i1","action":"hmac-sha256","data":"${b64(byteArrayOf(1))}"}""",
        )
        bad.forEach { assertEquals(HeldRequest.Unshown, held(it)) }
        // The largest allowed data reads.
        assertTrue(held(use(b64(ByteArray(HeldRequests.MAX_DATA)))).approvable)
    }

    @Test
    fun anItemGetNamesItsFieldsOrAllOfThem() {
        val some = held("""{"op":"item.get","item_id":"i1","fields":["f1","f2"]}""") as HeldRequest.ItemGet
        assertEquals(listOf("f1", "f2"), some.fieldIds)
        val all = held("""{"op":"item.get","item_id":"i1"}""") as HeldRequest.ItemGet
        assertNull(all.fieldIds)
        // A `fields` that is not a list of strings is not shown unseen.
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1","fields":[1]}"""))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1","fields":"f1"}"""))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get"}"""))
    }

    @Test
    fun anItemGetWithAnEmptyRepeatedOrTooLongFieldListIsDenyOnly() {
        fun get(fields: String) = held("""{"op":"item.get","item_id":"i1","fields":$fields}""")
        assertEquals(HeldRequest.Unshown, get("[]"))
        assertEquals(HeldRequest.Unshown, get("""["f1","f2","f1"]"""))
        val many = (1..65).joinToString(",", "[", "]") { "\"f$it\"" }
        assertEquals(HeldRequest.Unshown, get(many))
        val ok = (1..64).joinToString(",", "[", "]") { "\"f$it\"" }
        assertTrue(get(ok) is HeldRequest.ItemGet)
    }

    @Test
    fun theCatalogARunAndAProxyAreApprovable() {
        assertEquals(HeldRequest.Catalog, held("""{"op":"catalog"}"""))
        assertTrue(held(RUN).approvable)
        assertTrue(held(PROXY_BODY, PROXY_NOTE).approvable)
    }

    @Test
    fun anItemGetWithoutItsStrictEnvelopeIsDenyOnly() {
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body("""{"op":"item.get","item_id":"i1"}""")))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1"}""", ""","flags":["shell"]"""))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1"}""", ""","replaces":"a0""""))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1"}""", ""","proxy":{"host":"a.example.com","header":"X","prefix":""}"""))
        assertTrue("a body member is not judged for a reveal", held("""{"op":"item.get","item_id":"i1","extra":1}""") is HeldRequest.ItemGet)
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1"}""", ""","what":1"""))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1"}""", ""","previous_sha256":"$SHA2""""))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1"}""", ""","previous_path":"/x""""))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1"}""", ""","folders":{}"""))
        assertEquals(HeldRequest.Unshown, held("""{"op":"item.get","item_id":"i1","item_id":"i2"}"""))
        assertTrue(held("""{"op":"item.get","item_id":"i1"}""", ""","flags":["history"]""") is HeldRequest.ItemGet)
    }

    @Test
    fun aDesktopsStepUpAndAnythingElseAreDenyOnly() {
        assertEquals(HeldRequest.DesktopStepUp, HeldRequests.of("desktop", "item.put", body("""{"x":1}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "message.send", body("""{"connection_id":"c1","text":"hi"}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body("""{"op":"item.delete"}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", null))
        assertEquals(HeldRequest.Unshown, HeldRequests.of(null, "agent.request", body("""{"op":"catalog"}""")))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("app", "agent.request", body("""{"op":"catalog"}""")))
        listOf(HeldRequest.DesktopStepUp, HeldRequest.Unshown).forEach { assertFalse(it.approvable) }
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
        val h = held(use(b64("SECRETISH-CHALLENGE".toByteArray())))
        assertFalse(h.toString().contains("SECRET"))
        val get = held("""{"op":"item.get","item_id":"i-private","fields":["f-private"]}""")
        assertFalse(get.toString().contains("private"))
        val r = Approval.DeviceRequest("approval.pending", "h1", "Bot-name", "agent", "agent.request", java.time.Instant.EPOCH, null, deviceId = "dev-id", held = h)
        val text = r.toString()
        assertFalse(text, text.contains("SECRET") || text.contains("Bot-name") || text.contains("dev-id") || text.contains("h1"))
    }

    // --- AA8: runs and proxies, read strictly ---

    private companion object {
        const val SHA = "1a2b3c4d5e6f7a8b1a2b3c4d5e6f7a8b1a2b3c4d5e6f7a8b1a2b3c4d5e6f7a8b"
        const val SHA2 = "9f8e7d6c5b4a39281f8e7d6c5b4a39281f8e7d6c5b4a39281f8e7d6c5b4a3928"
        const val RUN = """{"op":"item.run","item_id":"i1","field_id":"f1","program":"/usr/bin/git","sha256":"$SHA","argv":["git","push","origin"],"how":"env","name":"GH_TOKEN","env":{"GIT_DIR":"/tmp/x","A":"b"}}"""
        const val PROXY_BODY = """{"op":"item.proxy","item_id":"i1","field_id":"f1","host":"api.example.com","seconds":3600}"""
        const val PROXY_NOTE = ""","proxy":{"host":"api.example.com","header":"Authorization","prefix":"Bearer "}"""
    }

    private fun run(change: (String) -> String = { it }, top: String = "") = held(change(RUN), top)

    @Test
    fun aRunReadsEveryMemberAsTheVaultSentIt() {
        val r = run(top = ""","flags":["shell","program_changed"],"previous_sha256":"$SHA2","previous_path":"/opt/git","replaces":"a0"""") as HeldRequest.ItemRun
        assertEquals("i1", r.itemId)
        assertEquals("f1", r.fieldId)
        assertEquals("/usr/bin/git", r.program)
        assertEquals(SHA, r.sha256)
        assertEquals(listOf("git", "push", "origin"), r.argv)
        assertEquals(SecretHow.ENV, r.how)
        assertEquals("GH_TOKEN", r.name)
        assertEquals("in the body's order", listOf("GIT_DIR" to "/tmp/x", "A" to "b"), r.env)
        assertEquals(setOf(RunFlag.SHELL, RunFlag.PROGRAM_CHANGED), r.flags)
        assertEquals(SHA2, r.previousSha256)
        assertEquals("/opt/git", r.previousPath)
        assertEquals("a0", r.replaces)
        assertNull(r.cwd)
        assertFalse(r.hasCwd)
        assertEquals("ItemRun", r.toString())
    }

    @Test
    fun aRunWithStdinNeedsNoNameAndACwdIsMarked() {
        val r = run({ it.replace(""""how":"env","name":"GH_TOKEN"""", """"how":"stdin","cwd":"/home/me"""") }) as HeldRequest.ItemRun
        assertEquals(SecretHow.STDIN, r.how)
        assertNull(r.name)
        assertEquals("/home/me", r.cwd)
        assertTrue("judged against the item's settings before approval", r.hasCwd)
        val f = run({ it.replace(""""how":"env"""", """"how":"file"""") }) as HeldRequest.ItemRun
        assertEquals(SecretHow.FILE, f.how)
    }

    /** ANDROID-PLAN 0.1.138 AA8 "Fail closed", one case per rule. */
    @Test
    fun aRunIsDenyOnlyForEveryFailClosedCase() {
        val cases = mapOf(
            "an unknown top-level member" to run(top = ""","later":1"""),
            "a duplicate member at the top" to run(top = ""","replaces":"a0","replaces":"a1""""),
            "a duplicate member in the body" to run({ it.replace(""""how":"env"""", """"how":"env","how":"stdin"""") }),
            "a duplicate member in env" to run({ it.replace(""""A":"b"""", """"A":"b","A":"c"""") }),
            "an unknown flag" to run(top = ""","flags":["new_flag"]"""),
            "history on a run" to run(top = ""","flags":["history"]"""),
            "a repeated flag" to run(top = ""","flags":["shell","shell"]"""),
            "flags not a list of strings" to run(top = ""","flags":[1]"""),
            "unbound with unrecorded" to run(top = ""","flags":["unbound_program","program_unrecorded"]"""),
            "unbound with changed" to run(top = ""","flags":["unbound_program","program_changed"],"previous_sha256":"$SHA2","previous_path":"/x""""),
            "unrecorded with changed" to run(top = ""","flags":["program_unrecorded","program_changed"],"previous_sha256":"$SHA2","previous_path":"/x""""),
            "no program" to run({ it.replace(""""program":"/usr/bin/git",""", "") }),
            "no sha256" to run({ it.replace(""""sha256":"$SHA",""", "") }),
            "no argv" to run({ it.replace(""""argv":["git","push","origin"],""", "") }),
            "an empty argv" to run({ it.replace("""["git","push","origin"]""", "[]") }),
            "a number in argv" to run({ it.replace("""["git","push","origin"]""", """["git",1]""") }),
            "env not an object" to run({ it.replace("""{"GIT_DIR":"/tmp/x","A":"b"}""", """["x"]""") }),
            "a number in env" to run({ it.replace(""""A":"b"""", """"A":1""") }),
            "cwd not a string" to run({ it.replace(""""how":"env"""", """"cwd":1,"how":"env"""") }),
            "an unknown how" to run({ it.replace(""""how":"env"""", """"how":"pipe"""") }),
            "no name for env" to run({ it.replace(""","name":"GH_TOKEN"""", "") }),
            "no name for file" to run({ it.replace(""""how":"env","name":"GH_TOKEN"""", """"how":"file"""") }),
            "an unknown body member" to run({ it.replace(""""how":"env"""", """"how":"env","shell":true""") }),
            "sha256 in capitals" to run({ it.replace(SHA, SHA.uppercase()) }),
            "sha256 of 63 digits" to run({ it.replace(SHA, SHA.drop(1)) }),
            "previous_sha256 not hex" to run(top = ""","flags":["program_changed"],"previous_sha256":"${SHA2.uppercase()}","previous_path":"/x""""),
            "program_changed alone" to run(top = ""","flags":["program_changed"]"""),
            "program_changed without previous_path" to run(top = ""","flags":["program_changed"],"previous_sha256":"$SHA2""""),
            "program_changed without previous_sha256" to run(top = ""","flags":["program_changed"],"previous_path":"/opt/git""""),
            "member_folder alone" to run(top = ""","flags":["member_folder"]"""),
            "member_files alone" to run(top = ""","flags":["member_files"]"""),
            "previous_* without program_changed" to run(top = ""","previous_sha256":"$SHA2","previous_path":"/x""""),
            "member_folder (AA9 not built)" to run(top = ""","flags":["member_folder"],"folders":{"cwd":{"path":"/home","write":false}}"""),
            "member_files (AA9 not built)" to run({ it.replace(""""how":"env"""", """"inputs":[{"path":"/a"}],"how":"env"""") }, ""","flags":["member_files"],"folders":{}"""),
            "folders without a flag" to run(top = ""","folders":{}"""),
            "inputs without member_files" to run({ it.replace(""""how":"env"""", """"inputs":[],"how":"env"""") }),
            "output without member_files" to run({ it.replace(""""how":"env"""", """"output":{"names":["a"]},"how":"env"""") }),
            "proxy on a run" to run(top = PROXY_NOTE),
            "an empty replaces" to run(top = ""","replaces":"""""),
            "a replaces that is not a string" to run(top = ""","replaces":1"""),
            "an empty item_id" to run({ it.replace(""""item_id":"i1"""", """"item_id":""""") }),
            "an empty field_id" to run({ it.replace(""""field_id":"f1"""", """"field_id":""""") }),
        )
        cases.forEach { (why, h) -> assertEquals(why, HeldRequest.Unshown, h) }
    }

    @Test
    fun aProxyIsShownOnlyFromItsNote() {
        val p = held(PROXY_BODY, PROXY_NOTE + ""","replaces":"a0"""") as HeldRequest.ItemProxy
        assertEquals("api.example.com", p.host)
        assertEquals("Authorization", p.header)
        assertEquals("Bearer ", p.prefix)
        assertEquals(3_600, p.seconds)
        assertEquals("a0", p.replaces)
        assertEquals("ItemProxy", p.toString())
        val noSeconds = held(PROXY_BODY.replace(""","seconds":3600""", ""), PROXY_NOTE) as HeldRequest.ItemProxy
        assertEquals(900, noSeconds.seconds)
        val emptyPrefix = held(PROXY_BODY, PROXY_NOTE.replace(""""prefix":"Bearer """", """"prefix":""""")) as HeldRequest.ItemProxy
        assertEquals("", emptyPrefix.prefix)
    }

    @Test
    fun aProxyIsDenyOnlyForEveryFailClosedCase() {
        val cases = mapOf(
            "no proxy note" to held(PROXY_BODY),
            "a note member this version does not know" to held(PROXY_BODY, PROXY_NOTE.replace("}", ""","port":443}""")),
            "a note without its prefix" to held(PROXY_BODY, PROXY_NOTE.replace(""","prefix":"Bearer """", "")),
            "an IP address" to held(PROXY_BODY, PROXY_NOTE.replace("api.example.com", "10.0.0.1")),
            "a host in capitals" to held(PROXY_BODY, PROXY_NOTE.replace("api.example.com", "API.example.com")),
            "a header that is not a token" to held(PROXY_BODY, PROXY_NOTE.replace("Authorization", "Auth orization")),
            "a prefix with a control" to held(PROXY_BODY, PROXY_NOTE.replace("Bearer ", "Bearer\t")),
            "a prefix over 32" to held(PROXY_BODY, PROXY_NOTE.replace("Bearer ", "x".repeat(33))),
            "seconds under 60" to held(PROXY_BODY.replace("3600", "59"), PROXY_NOTE),
            "seconds over 3,600" to held(PROXY_BODY.replace("3600", "3601"), PROXY_NOTE),
            "seconds as a fraction" to held(PROXY_BODY.replace("3600", "900.0"), PROXY_NOTE),
            "seconds as a string" to held(PROXY_BODY.replace("3600", "\"900\""), PROXY_NOTE),
            "any flag" to held(PROXY_BODY, PROXY_NOTE + ""","flags":["shell"]"""),
            "an unknown member beside an empty flags list" to held(PROXY_BODY, PROXY_NOTE + ""","flags":[],"later":1"""),
            "previous_* on a proxy" to held(PROXY_BODY, PROXY_NOTE + ""","previous_sha256":"$SHA2","previous_path":"/x""""),
            "folders on a proxy" to held(PROXY_BODY, PROXY_NOTE + ""","folders":{}"""),
            "an unknown body member" to held(PROXY_BODY.replace("}", ""","path":"/v1"}"""), PROXY_NOTE),
            "a duplicate in the note" to held(PROXY_BODY, PROXY_NOTE.replace(""""header":"Authorization"""", """"header":"Authorization","header":"X"""")),
        )
        cases.forEach { (why, h) -> assertEquals(why, HeldRequest.Unshown, h) }
        assertTrue("an empty flags list alone is fine", held(PROXY_BODY, PROXY_NOTE + ""","flags":[]""") is HeldRequest.ItemProxy)
    }

    @Test
    fun withoutItsSourceTextARunOrProxyIsDenyOnly() {
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body(RUN)))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body(PROXY_BODY)))
    }

    /** AA8: on an `item.use` or a `catalog`, a member that changes what approving means is deny only; another is ignored (§6.8). */
    @Test
    fun aUseOrACatalogWithAMemberThatChangesItsMeaningIsDenyOnly() {
        val u = use(b64("challenge".toByteArray()))
        val cat = """{"op":"catalog"}"""
        val meaningful = listOf(
            ""","replaces":"a0"""",
            PROXY_NOTE,
            ""","flags":["history"]""",
            ""","flags":[]""",
            ""","previous_sha256":"$SHA2"""",
            ""","previous_path":"/x"""",
            ""","folders":{}""",
        )
        for (top in meaningful) {
            assertEquals(top, HeldRequest.Unshown, held(u, top))
            assertEquals(top, HeldRequest.Unshown, held(cat, top))
        }
        assertEquals("all three on a catalog", HeldRequest.Unshown, held(cat, PROXY_NOTE + ""","replaces":"a0","flags":["shell"]"""))
        assertEquals("a duplicate data member", HeldRequest.Unshown, held(u.replace(""""action"""", """"data":"AAEC","action"""")))
        assertTrue("an unknown member is ignored", held(u, ""","later":1""") is HeldRequest.ItemUse)
        assertEquals(HeldRequest.Catalog, held(cat, ""","later":1"""))
        // Without its strict envelope: deny only.
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body(u)))
        assertEquals(HeldRequest.Unshown, HeldRequests.of("agent", "agent.request", body(cat)))
    }
}
