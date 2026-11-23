// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.devices

import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANDROID-PLAN 0.1.137 AA7: item_ways read strictly, checked as the vault checks them, compared for a widening. */
class ItemWaysTest {
    private val bs = '\\'

    /** A Windows path joined with backslashes (kept out of string literals). */
    private fun win(vararg parts: String) = parts.joinToString(bs.toString())

    private val sha = "ab".repeat(32)

    private fun item(body: String) = """{"agent_id":"a1","item_id":"i1",$body}"""

    private val full = item(
        """"version":3,"ways":["use","run","proxy","reveal"],"run":{"programs":[{"program":"git","sha256":"$sha","path":"/usr/bin/git","env":["GH_HOST"]},""" +
            """{"program":"/usr/bin/gh","env":[]}],"each":false,"folders":[]},"proxy":{"hosts":["api.example.com","b.example.org:8443"],""" +
            """"header":"Authorization","prefix":"Bearer "},"updated_at":"2026-11-22T10:00:00Z"""",
    )

    @Test
    fun readsEverySettingAndSendsItBackAsRead() {
        val r = ItemWaysRules.one(full.toByteArray())!!
        val w = r.ways!!
        assertFalse(r.readOnly)
        assertTrue(r.foldersKnown)
        assertEquals(3L, w.version)
        assertEquals(setOf(ItemWay.USE, ItemWay.RUN, ItemWay.PROXY, ItemWay.REVEAL), w.ways)
        assertEquals(RunProgram("git", sha, "/usr/bin/git", listOf("GH_HOST")), w.run!!.programs[0])
        assertFalse(w.run!!.each)
        assertEquals(ProxySettings(listOf("api.example.com", "b.example.org:8443"), "Authorization", "Bearer "), w.proxy)
        // Round trip: the body the app sends reads back to the same settings.
        val body = w.setBody()
        val back = ItemWaysRules.one(VaultJson.bytes(body))!!.ways!!
        assertEquals(w, back)
        assertNull(body["updated_at"])
        assertEquals(JsonArray(listOf("use", "run", "proxy", "reveal").map { JsonPrimitive(it) }), body["ways"])
    }

    @Test
    fun anItemWithNoSettingsIsUseOnly() {
        val r = ItemWaysRules.one(item(""""ways":["use"]""").toByteArray())!!
        assertEquals(ItemWays("a1", "i1"), r.ways)
        assertNull(r.ways!!.setBody()["version"])
        assertFalse(r.foldersKnown)
        // A run binding from a vault before 0.93.0 (no folders): sent back without them.
        val old = ItemWaysRules.one(item(""""ways":["use","run"],"run":{"programs":[],"each":true}""").toByteArray())!!
        assertFalse(old.foldersKnown)
        assertNull((old.ways!!.setBody()["run"] as JsonObject)["folders"])
    }

    /** AA7 "Fail closed": each of these makes the page read only. */
    @Test
    fun whatTheAppDoesNotUnderstandIsReadOnly() {
        val cases = mapOf(
            "unknown top" to item(""""ways":["use"],"extra":1"""),
            "unknown way" to item(""""ways":["use","teleport"]"""),
            "no use" to item(""""ways":["run"],"run":{"programs":[],"each":true}"""),
            "duplicate way" to item(""""ways":["use","use"]"""),
            "run without way" to item(""""ways":["use"],"run":{"programs":[],"each":true}"""),
            "way without run" to item(""""ways":["use","run"]"""),
            "proxy without way" to item(""""ways":["use"],"proxy":{"hosts":["a.example.com"],"header":"X"}"""),
            "unknown run member" to item(""""ways":["use","run"],"run":{"programs":[],"each":true,"x":1}"""),
            "unknown program member" to item(""""ways":["use","run"],"run":{"programs":[{"program":"git","x":1}],"each":true}"""),
            // Any folder is read only until AA9, so this row can't yet be told from the next one.
            "unknown folder member" to item(""""ways":["use","run"],"run":{"programs":[],"each":true,"folders":[{"path":"/a/b","write":false,"x":1}]}"""),
            "unknown proxy member" to item(""""ways":["use","proxy"],"proxy":{"hosts":["a.example.com"],"header":"X","x":1}"""),
            "folders (no AA9 yet)" to item(""""ways":["use","run"],"run":{"programs":[],"each":true,"folders":[{"path":"/a/b","write":false}]}"""),
            "sha without path" to item(""""ways":["use","run"],"run":{"programs":[{"program":"git","sha256":"$sha"}],"each":true}"""),
            "capital sha" to item(""""ways":["use","run"],"run":{"programs":[{"program":"git","sha256":"${sha.uppercase()}","path":"/usr/bin/git"}],"each":true}"""),
            "63-digit sha" to item(""""ways":["use","run"],"run":{"programs":[{"program":"git","sha256":"${sha.drop(1)}","path":"/usr/bin/git"}],"each":true}"""),
            "each false unbound" to item(""""ways":["use","run"],"run":{"programs":[],"each":false}"""),
            "string each" to item(""""ways":["use","run"],"run":{"programs":[],"each":"true"}"""),
            "bad program" to item(""""ways":["use","run"],"run":{"programs":[{"program":"a/b"}],"each":true}"""),
            "duplicate programs" to item(""""ways":["use","run"],"run":{"programs":[{"program":"git"},{"program":"GIT.exe"}],"each":true}"""),
            "bad env" to item(""""ways":["use","run"],"run":{"programs":[{"program":"git","env":["lower"]}],"each":true}"""),
            "no hosts" to item(""""ways":["use","proxy"],"proxy":{"hosts":[],"header":"X"}"""),
            "IP host" to item(""""ways":["use","proxy"],"proxy":{"hosts":["10.0.0.1"],"header":"X"}"""),
            "bad header" to item(""""ways":["use","proxy"],"proxy":{"hosts":["a.example.com"],"header":"Host"}"""),
            "bad prefix" to item(""""ways":["use","proxy"],"proxy":{"hosts":["a.example.com"],"header":"X","prefix":"${"x".repeat(33)}"}"""),
            "version 0" to item(""""version":0,"ways":["use"]"""),
            "string version" to item(""""version":"1","ways":["use"]"""),
            "duplicate member" to """{"agent_id":"a1","item_id":"i1","ways":["use"],"ways":["use"]}""",
            "duplicate hosts" to item(""""ways":["use","proxy"],"proxy":{"hosts":["a.example.com","a.example.com:443"],"header":"X"}"""),
            "duplicate variables" to item(""""ways":["use","run"],"run":{"programs":[{"program":"git","env":["A","A"]}],"each":true}"""),
            "empty agent" to """{"agent_id":"","item_id":"i1","ways":["use"]}""",
            "empty item" to """{"agent_id":"a1","item_id":"","ways":["use"]}""",
            "malformed updated_at" to item(""""ways":["use"],"updated_at":"yesterday""""),
            "pin path not absolute" to item(""""ways":["use","run"],"run":{"programs":[{"program":"git","sha256":"$sha","path":"bin/git"}],"each":true}"""),
            "17 programs" to item(""""ways":["use","run"],"run":{"programs":[${(1..17).joinToString(",") { "{\"program\":\"p$it\"}" }}],"each":true}"""),
        )
        for ((name, json) in cases) {
            val r = ItemWaysRules.one(json.toByteArray())
            assertNotNull(name, r)
            assertTrue("$name is editable", r!!.readOnly)
        }
    }

    /** A read-only page turns a way off: the rest exactly as read, unknown members included; never `use`. */
    @Test
    fun aReadOnlyPageTurnsAWayOffAndSendsTheRestAsRead() {
        val r = ItemWaysRules.one(item(""""version":2,"ways":["use","run","reveal"],"run":{"programs":[],"each":true,"folders":[{"path":"/a/b","write":true}]},"extra":{"k":1}""").toByteArray())!!
        assertTrue(r.readOnly)
        val body = ItemWaysRules.withoutWay(r, "run")!!
        assertEquals(JsonArray(listOf(JsonPrimitive("use"), JsonPrimitive("reveal"))), body["ways"])
        assertNull(body["run"])
        assertEquals("{\"k\":1}", body["extra"].toString())
        assertEquals(JsonPrimitive(2), body["version"])
        assertNull(ItemWaysRules.withoutWay(r, "use"))
        assertNull(ItemWaysRules.withoutWay(r, "proxy"))
    }

    /** Unknown members go back as their exact source text: a number as written, a big integer whole. */
    @Test
    fun aTurnOffSendsUnknownValuesAsWritten() {
        val r = ItemWaysRules.one(item(""""version":2,"ways":["use","reveal","x_way"],"x_way":{"n":1e2,"big":123456789012345678901234567890,"f":0.10}""").toByteArray())!!
        val sent = String(VaultJson.bytes(ItemWaysRules.withoutWay(r, "reveal")!!))
        assertTrue(sent, sent.contains("\"n\":1e2") && sent.contains("\"big\":123456789012345678901234567890") && sent.contains("\"f\":0.10"))
        assertTrue(sent, sent.contains("\"ways\":[\"use\",\"x_way\"]"))
    }

    /** No turn-off when the vault's copy is not known (a duplicate member) or the item is another agent's. */
    @Test
    fun noTurnOffWithoutAStrictReadingOrForAnotherAgent() {
        val dup = ItemWaysRules.one(item(""""ways":["use","run"],"run":{"programs":[],"each":true},"run":{"programs":[{"program":"git"}],"each":false}""").toByteArray())!!
        assertTrue(dup.readOnly)
        assertNull(ItemWaysRules.withoutWay(dup, "run"))
        val foreign = ItemWaysRules.list("""{"items":[{"agent_id":"a2","item_id":"i1","ways":["use","reveal"]}]}""".toByteArray(), "a1")!!.single()
        assertTrue(foreign.foreign && foreign.readOnly)
        assertNull(ItemWaysRules.withoutWay(foreign, "reveal"))
    }

    /** AA9's widenings, as the vault's: a folder added, or its write turned on. */
    @Test
    fun foldersWiden() {
        val cur = bound(RunProgram("git")).let { it.copy(run = it.run!!.copy(folders = listOf(RunFolder("/a", write = false)))) }
        fun with(vararg f: RunFolder) = cur.copy(run = cur.run!!.copy(folders = f.toList()))
        assertTrue(ItemWaysRules.widens(cur, with(RunFolder("/a", false), RunFolder("/b", false))))
        assertTrue(ItemWaysRules.widens(cur, with(RunFolder("/a", true))))
        assertFalse(ItemWaysRules.widens(cur, with()))
        assertFalse(ItemWaysRules.widens(with(RunFolder("/a", true)), cur))
    }

    @Test
    fun aListThatDoesNotReadStrictlyIsReadOnlyThroughout() {
        val list = ItemWaysRules.list("""{"items":[{"agent_id":"a1","item_id":"i1","ways":["use"]},{"agent_id":"a1","item_id":"i2","ways":["use"],"ways":["use"]}]}""".toByteArray())!!
        assertEquals(2, list.size)
        assertTrue(list.all { it.readOnly })
        val ok = ItemWaysRules.list("""{"items":[{"agent_id":"a1","item_id":"i1","ways":["use"]}]}""".toByteArray())!!
        assertFalse(ok.single().readOnly)
        // Another agent's item is read only.
        assertTrue(ItemWaysRules.list("""{"items":[{"agent_id":"a2","item_id":"i1","ways":["use"]}]}""".toByteArray(), "a1")!!.single().readOnly)
    }

    /** One item's settings are never shown under another item's name. */
    @Test
    fun anEntryThatIsNotAnObjectMakesTheAnswerUnreadable() {
        val shifted = """{"items":[null,{"agent_id":"a1","item_id":"B","ways":["use"]},{"agent_id":"a1","item_id":"C","version":5,"ways":["use","reveal"]}]}"""
        assertNull(ItemWaysRules.list(shifted.toByteArray()))
        for (bad in listOf("{}", """{"items":{}}""", "[]", "not json", """{"items":[1]}""")) assertNull(bad, ItemWaysRules.list(bad.toByteArray()))
        assertEquals(emptyList<ItemWaysRead>(), ItemWaysRules.list("""{"items":[]}""".toByteArray()))
        // Every strict reading is of the item the lenient reading names.
        val two = ItemWaysRules.list("""{"items":[{"agent_id":"a1","item_id":"B","ways":["use"]},{"agent_id":"a1","item_id":"C","version":5,"ways":["use","reveal"]}]}""".toByteArray())!!
        assertEquals(listOf("B", "C"), two.map { it.ways!!.itemId })
        assertEquals(5L, two[1].ways!!.version)
    }

    @Test
    fun programKeysLowerCaseRuneByRuneAsTheVaultDoes() {
        // Go's strings.ToLower: no final sigma, İ one rune.
        assertEquals("ασ", ItemWaysRules.programKey("ΑΣ"))
        assertEquals(ItemWaysRules.programKey("ασ"), ItemWaysRules.programKey("ΑΣ"))
        assertTrue(ItemWaysRules.duplicates(listOf("ασ"), "ΑΣ"))
        assertFalse(ItemWaysRules.duplicates(listOf("ας"), "ΑΣ"))
        assertEquals(3, ItemWaysRules.programKey("gİt").length)
    }

    @Test
    fun aBareNameAndAPathEndingInItAreReadOnlyAsTheVaultRefusesThem() {
        val r = ItemWaysRules.one(item(""""ways":["use","run"],"run":{"programs":[{"program":"git"},{"program":"/usr/bin/Git.exe"}],"each":true}""").toByteArray())!!
        assertTrue(r.readOnly)
    }

    @Test
    fun turningOffAnUnknownWayKeepsTheItemsOwnMembers() {
        val r = ItemWaysRules.one(item(""""version":2,"ways":["use","teleport","version"],"teleport":{"k":1},"other":{"k":2},"updated_at":"2026-11-22T10:00:00Z"""").toByteArray())!!
        assertTrue(r.readOnly)
        val off = ItemWaysRules.withoutWay(r, "teleport")!!
        assertNull(off["teleport"])
        assertEquals(r.raw["other"], off["other"])
        assertNull(off["updated_at"])
        // A way named after one of the item's own members leaves that member alone.
        assertEquals(JsonPrimitive(2), ItemWaysRules.withoutWay(r, "version")!!["version"])
    }

    /** A pin the vault recorded is sent only as read; a program without one sends none. */
    @Test
    fun aForgottenPinNarrowsAndIsNotSent() {
        val cur = bound(RunProgram("git", sha, "/usr/bin/git"))
        val next = bound(RunProgram("git"))
        assertFalse(ItemWaysRules.widens(cur, next))
        val p = ((next.setBody()["run"] as JsonObject)["programs"] as JsonArray)[0] as JsonObject
        assertNull(p["sha256"])
        assertNull(p["path"])
    }

    @Test
    fun programChecks() {
        val ok = listOf("git", "git.exe", "/usr/bin/git", win("C:", "Program Files", "Git", "cmd", "git.exe"), "C:/x/y.exe", "my tool")
        ok.forEach { assertNull(it, ItemWaysRules.programProblem(it)) }
        val bad = mapOf(
            "" to ProgramProblem.NOT_PATH_OR_NAME,
            "bin/git" to ProgramProblem.NOT_PATH_OR_NAME,
            win("bin", "git") to ProgramProblem.NOT_PATH_OR_NAME,
            "..." to ProgramProblem.NOT_PATH_OR_NAME,
            " . " to ProgramProblem.NOT_PATH_OR_NAME,
            "/usr/bin/. " to ProgramProblem.NOT_PATH_OR_NAME,
            "git\u0001" to ProgramProblem.CHARACTERS,
            "gi\u200bt" to ProgramProblem.CHARACTERS,
            "/" + "a".repeat(4096) to ProgramProblem.TOO_LONG,
            "/usr//bin/git" to ProgramProblem.PATH_PARTS,
            "/usr/bin/../git" to ProgramProblem.PATH_PARTS,
            "/usr/bin/./git" to ProgramProblem.PATH_PARTS,
            "/usr/bin/" to ProgramProblem.PATH_PARTS,
            "C:/x/git.exe::\$DATA" to ProgramProblem.PATH_PARTS,
            "C:git" to ProgramProblem.PATH_PARTS,
            "//server/share/git" to ProgramProblem.NETWORK_PATH,
            win("", "", "server", "share", "git.exe") to ProgramProblem.NETWORK_PATH,
            win("C:", "PROGRA~1", "BASH~1.EXE") to ProgramProblem.TILDE,
        )
        for ((p, want) in bad) assertEquals(p, want, ItemWaysRules.programProblem(p))
        // The no-two-match rule: case, .exe, trailing dots and spaces, / for \ in a drive path, a name against a path's last part.
        val listed = listOf(RunProgram("/usr/bin/git"), RunProgram(win("C:", "x", "tool.exe")))
        for (p in listOf("/usr/bin/GIT", "/usr/bin/git.exe", "git", "Git.EXE", "C:/x/TOOL", "tool", win("C:", "x", "tool. "))) {
            assertEquals(p, ProgramProblem.DUPLICATE, ItemWaysRules.addProgramProblem(listed, p))
        }
        assertNull(ItemWaysRules.addProgramProblem(listed, "/usr/local/bin/gh"))
        assertEquals(ProgramProblem.TOO_MANY, ItemWaysRules.addProgramProblem((1..16).map { RunProgram("p$it") }, "q"))
        assertNull(ItemWaysRules.addProgramProblem((1..15).map { RunProgram("p$it") }, "q"))
    }

    @Test
    fun variableChecks() {
        assertNull(ItemWaysRules.variableProblem(emptyList(), "GH_HOST"))
        assertNull(ItemWaysRules.variableProblem(emptyList(), "_A1"))
        assertNull(ItemWaysRules.variableProblem(emptyList(), "A" + "B".repeat(63)))
        for (n in listOf("", "lower", "1ABC", "A-B", "A B", "A" + "B".repeat(64))) {
            assertEquals(n, VariableProblem.FORM, ItemWaysRules.variableProblem(emptyList(), n))
        }
        // The names the vault refuses by name are not the app's to list: they pass the form.
        assertNull(ItemWaysRules.variableProblem(emptyList(), "VETTID_IN"))
        val full = (1..32).map { "V$it" }
        assertEquals(VariableProblem.TOO_MANY, ItemWaysRules.variableProblem(full, "W"))
        assertNull(ItemWaysRules.variableProblem(full, "V1"))
    }

    @Test
    fun hostChecks() {
        for (h in listOf("api.example.com", "a.b.example.org:8443", "x-y.example.com", "printer.local")) assertNull(h, ItemWaysRules.hostProblem(h))
        val bad = mapOf(
            "*.example.com" to HostProblem.WILDCARD,
            "10.0.0.1" to HostProblem.IP_ADDRESS,
            "10.0.0.1:443" to HostProblem.IP_ADDRESS,
            "[::1]" to HostProblem.IP_ADDRESS,
            "::1" to HostProblem.IP_ADDRESS,
            "1.2.3" to HostProblem.IP_ADDRESS,
            "a.0x7f" to HostProblem.IP_ADDRESS,
            "localhost" to HostProblem.LOCAL,
            "api.localhost" to HostProblem.LOCAL,
            "intranet" to HostProblem.LOCAL,
            "api.example.com:0" to HostProblem.NOT_A_HOST,
            "api.example.com:65536" to HostProblem.NOT_A_HOST,
            "api.example.com:" to HostProblem.NOT_A_HOST,
            "-a.example.com" to HostProblem.NOT_A_HOST,
            "a..example.com" to HostProblem.NOT_A_HOST,
            "a_b.example.com" to HostProblem.NOT_A_HOST,
            "" to HostProblem.NOT_A_HOST,
        )
        for ((h, want) in bad) assertEquals(h, want, ItemWaysRules.hostProblem(h))
        assertEquals("api.example.com", ItemWaysRules.normalizeHost(" API.Example.COM "))
        assertEquals(HostProblem.DUPLICATE, ItemWaysRules.addHostProblem(listOf("api.example.com"), "api.example.com:443"))
        assertEquals(HostProblem.TOO_MANY, ItemWaysRules.addHostProblem((1..8).map { "h$it.example.com" }, "x.example.com"))
    }

    @Test
    fun headerAndPrefixChecks() {
        for (h in listOf("Authorization", "X-Api-Key", "x_token", "a".repeat(64))) assertTrue(h, ItemWaysRules.headerValid(h))
        for (h in listOf("", "a".repeat(65), "Host", "content-length", "Transfer-Encoding", "Cookie", "Connection", "X Key", "X:Key")) {
            assertFalse(h, ItemWaysRules.headerValid(h))
        }
        for (p in listOf("", "Bearer ", "Token ", " ".repeat(32))) assertTrue(p, ItemWaysRules.prefixValid(p))
        for (p in listOf("x".repeat(33), "Bé ", "a\tb")) assertFalse(p, ItemWaysRules.prefixValid(p))
    }

    private fun bound(vararg p: RunProgram, each: Boolean = true) =
        ItemWays("a1", "i1", 1, setOf(ItemWay.USE, ItemWay.RUN), RunSettings(p.toList(), each))

    /** AA7 "Saving": what widens asks for the password; what narrows does not. */
    @Test
    fun wideningAndNarrowing() {
        val cur = bound(RunProgram("git", env = listOf("GH_HOST")), RunProgram("/usr/bin/gh"), each = false)
            .copy(ways = setOf(ItemWay.USE, ItemWay.RUN, ItemWay.PROXY), proxy = ProxySettings(listOf("api.example.com"), "Authorization", "Bearer "))
        val widen = mapOf(
            "a way" to cur.copy(ways = cur.ways + ItemWay.REVEAL),
            "a program" to cur.copy(run = cur.run!!.copy(programs = cur.run!!.programs + RunProgram("curl"))),
            "programs emptied" to cur.copy(run = cur.run!!.copy(programs = emptyList(), each = true)),
            "a variable" to cur.copy(run = cur.run!!.copy(programs = listOf(RunProgram("git", env = listOf("GH_HOST", "GH_REPO")), RunProgram("/usr/bin/gh")))),
            "a host" to cur.copy(proxy = cur.proxy!!.copy(hosts = listOf("api.example.com", "b.example.com"))),
            "the header" to cur.copy(proxy = cur.proxy!!.copy(header = "X-Api-Key")),
            "a changed program" to cur.copy(run = cur.run!!.copy(programs = listOf(RunProgram("git2", env = listOf("GH_HOST")), RunProgram("/usr/bin/gh")))),
        )
        for ((name, next) in widen) assertTrue(name, ItemWaysRules.widens(cur, next))
        val askAgain = cur.copy(run = cur.run!!.copy(each = true))
        assertTrue("each off", ItemWaysRules.widens(askAgain, cur))
        val narrow = mapOf(
            "a way removed" to cur.copy(ways = cur.ways - ItemWay.PROXY, proxy = null),
            "a program removed" to cur.copy(run = cur.run!!.copy(programs = cur.run!!.programs.take(1))),
            "a variable removed" to cur.copy(run = cur.run!!.copy(programs = listOf(RunProgram("git"), RunProgram("/usr/bin/gh")))),
            "each on" to askAgain,
            "a host removed" to cur.copy(proxy = cur.proxy!!.copy(hosts = listOf("api.example.com"))),
            "the header's case" to cur.copy(proxy = cur.proxy!!.copy(header = "authorization")),
            "the prefix" to cur.copy(proxy = cur.proxy!!.copy(prefix = "Token ")),
            "a path for the name" to cur.copy(run = cur.run!!.copy(programs = listOf(RunProgram("/usr/bin/git", env = listOf("GH_HOST")), RunProgram("/usr/bin/gh")))),
        )
        for ((name, next) in narrow) assertFalse(name, ItemWaysRules.widens(cur, next))
        // A path that replaces a name widens when its last part is not exactly the name, or it adds a variable.
        assertTrue(ItemWaysRules.widens(cur, cur.copy(run = cur.run!!.copy(programs = listOf(RunProgram("/usr/bin/GIT", env = listOf("GH_HOST")), RunProgram("/usr/bin/gh"))))))
        assertTrue(ItemWaysRules.widens(cur, cur.copy(run = cur.run!!.copy(programs = listOf(RunProgram("/usr/bin/git", env = listOf("GH_HOST", "X")), RunProgram("/usr/bin/gh"))))))
        // Binding an unbound item narrows, unless a variable is listed or each is off.
        val unbound = bound()
        assertFalse(ItemWaysRules.widens(unbound, bound(RunProgram("git"))))
        assertTrue(ItemWaysRules.widens(unbound, bound(RunProgram("git", env = listOf("A")))))
        assertTrue(ItemWaysRules.widens(unbound, bound(RunProgram("git"), each = false)))
        // From no settings: any way but use widens.
        assertTrue(ItemWaysRules.widens(null, bound()))
        assertFalse(ItemWaysRules.widens(null, ItemWays("a1", "i1")))
    }

    @Test
    fun emptyFoldersAreTheSameSettingsAsNone() {
        val a = ItemWays("a1", "i1", 1, setOf(ItemWay.USE, ItemWay.RUN), RunSettings(listOf(RunProgram("gh"))))
        assertTrue(ItemWaysRules.sameSettings(a, a.copy(run = a.run!!.copy(folders = emptyList()))))
        assertFalse(ItemWaysRules.sameSettings(a, a.copy(run = a.run!!.copy(each = false))))
        assertFalse(ItemWaysRules.sameSettings(a, a.copy(version = 2)))
    }
}
