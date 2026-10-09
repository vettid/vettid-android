// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.items

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.GrantFetched
import com.vettid.core.vault.TagInfo
import com.vettid.core.vault.TagPage
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Tags, share rules and grants (VAULT-MESSAGING §10.8, §10.12): request bodies, parsing and the fetch. */
class SharingManagerTest {
    private fun o(s: String) = VaultJson.json.parseToJsonElement(s).jsonObject

    private class Ops : SharingOps {
        val calls = mutableListOf<String>()
        var tagPages = ArrayDeque<TagPage>()
        var ruleSets = mutableListOf<JsonObject>()
        var ruleAnswer: JsonObject = JsonObject(emptyMap())
        var rulePages = ArrayDeque<JsonObject>()
        var fetched: GrantFetched? = null
        var deleteError: String? = null

        override suspend fun tagList(after: String?): TagPage {
            calls += "tagList:$after"
            return tagPages.removeFirstOrNull() ?: TagPage(1)
        }

        override suspend fun tagSet(version: Long, tag: String, color: String?, description: String?): Long {
            calls += "tagSet:$version:$tag:$description"
            return version + 1
        }

        override suspend fun tagMerge(version: Long, from: List<String>, into: String, dryRun: Boolean): JsonObject {
            calls += "tagMerge:$version:$from:$into:$dryRun"
            return VaultJson.json.parseToJsonElement(
                """{"version":$version,"items":3,"rules":1,"shares":[{"rule_id":"r1","item_id":"i1","mode":"auto"},{"rule_id":"r2","item_id":"i2","mode":"ask"}],"shares_total":2}""",
            ).jsonObject
        }

        override suspend fun tagDelete(version: Long, tag: String, dryRun: Boolean): JsonObject {
            calls += "tagDelete:$version:$tag:$dryRun"
            deleteError?.let { throw VaultOpException("tag.delete", it) }
            return VaultJson.json.parseToJsonElement("""{"version":$version,"items":4}""").jsonObject
        }

        override suspend fun ruleList(connectionId: String?, after: String?): JsonObject {
            calls += "ruleList:$connectionId:$after"
            return rulePages.removeFirstOrNull() ?: JsonObject(emptyMap())
        }

        override suspend fun ruleSet(body: JsonObject): JsonObject {
            ruleSets += body
            return ruleAnswer
        }

        override suspend fun ruleDelete(ruleId: String) {
            calls += "ruleDelete:$ruleId"
        }

        override suspend fun grantList(): JsonObject = VaultJson.json.parseToJsonElement(
            """{"given":[{"grant_id":"g1","connection_id":"c1","direction":"given","kind":"item","ref":"i1","rule_id":"r1","name":"Allergies","category":"medical","uses":3,"used":1,"state":"active"}],
               "received":[{"grant_id":"g2","connection_id":"c2","direction":"received","kind":"item","ref":"x9","name":"Insurance card","category":"insurance","expires_at":"2026-10-14T00:00:00.000Z","state":"active","labels":[{"field_id":"f1","label":"Number","kind":"text"},{"label":"no id"},{"field_id":"f3","label":"Valid to","kind":"date"}]}],
               "pending":[],"requested":[{"request_id":"q1","connection_id":"c2","items":[{"kind":"category","ref":"medical","label":"Your vaccination record"},{"kind":"item","ref":"01ITEM"},{"ref":"no-kind"}],"state":"granted"},{"request_id":"q2","connection_id":"c2","items":[]}]}""",
        ).jsonObject

        override suspend fun grantRevoke(grantId: String) {
            calls += "revoke:$grantId"
        }

        override suspend fun grantFetch(grantId: String): GrantFetched = fetched!!

        var asked: Pair<String, JsonObject>? = null

        override suspend fun grantRequest(connectionId: String, items: kotlinx.serialization.json.JsonArray, reason: String?): String {
            asked = connectionId to (items.single() as JsonObject)
            return "01REQ"
        }
    }

    private val ops = Ops()
    private var itemsRefreshed = 0
    private val m = SharingManager(ops = { ops }, onItemsChanged = { itemsRefreshed++ })

    @Test
    fun theRegistryIsListedPageByPage() = runTest {
        ops.tagPages = ArrayDeque(
            listOf(
                TagPage(7, listOf(TagInfo("@profile", items = 2), TagInfo("medical", items = 3, rules = listOf("r1"))), next = "medical"),
                TagPage(7, listOf(TagInfo("travel", description = "Trips"))),
            ),
        )
        val r = m.refreshTags()
        assertEquals(7, r.version)
        assertEquals(listOf("@profile", "medical", "travel"), r.tags.map { it.tag })
        assertTrue(r.tags[0].reserved)
        assertEquals(listOf("r1"), r.tags[1].rules)
        assertEquals(listOf("tagList:null", "tagList:medical"), ops.calls)
    }

    @Test
    fun renameIsAMergeOfOneTagAndShowsWhatItShares() = runTest {
        ops.tagPages = ArrayDeque(listOf(TagPage(5)))
        val c = m.renameTag("health", " Medical ", dryRun = true)
        assertTrue("tagMerge:5:[health]:medical:true" in ops.calls)
        assertEquals(3, c.items)
        assertEquals(2, c.sharesTotal)
        assertEquals(ShareMode.AUTO, c.shares[0].mode)
        assertEquals(0, itemsRefreshed)
        m.renameTag("health", "medical", dryRun = false)
        assertEquals(1, itemsRefreshed)
    }

    @Test
    fun reservedTagsAreNeitherRenamedNorDeleted() = runTest {
        for (block in listOf<suspend () -> Unit>({ m.renameTag("@profile", "x", false) }, { m.deleteTag("@profile", false) }, { m.renameTag("a", "@profile", false) })) {
            try {
                block()
                fail()
            } catch (e: VaultFailure) {
                assertEquals(FailureKind.OTHER, e.kind)
            }
        }
        assertTrue(ops.calls.none { it.startsWith("tagMerge") || it.startsWith("tagDelete") })
    }

    @Test
    fun aTagARuleNamesIsInUse() = runTest {
        ops.deleteError = "in_use"
        try {
            m.deleteTag("medical", dryRun = true)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.IN_USE, e.kind)
        }
    }

    @Test
    fun aRuleBodyNamesTheConnectionAndReadAccess() {
        val at = Instant.parse("2027-01-01T00:00:00Z")
        val b = SharingManager.ruleBody(RuleDraft("c1", listOf("medical"), TagMatch.ALL, ShareMode.AUTO, uses = 5, expiresAt = at, includeExisting = false), dryRun = true)
        assertEquals(
            """{"subject":{"connection_id":"c1"},"tags":["medical"],"match":"all","access":"read","mode":"auto","uses":5,"expires_at":"2027-01-01T00:00:00.000Z","include_existing":false,"dry_run":true}""",
            VaultJson.json.encodeToString(JsonObject.serializer(), b),
        )
        val replace = SharingManager.ruleBody(RuleDraft("c1", listOf("a"), ruleId = "r1", version = 4), dryRun = false)
        assertEquals("r1", VaultJson.str(replace, "rule_id"))
        assertEquals(4L, VaultJson.long(replace, "version"))
        assertEquals("ask", VaultJson.str(replace, "mode"))
        assertNull(replace["dry_run"])
    }

    /**
     * Owner feedback 2026-10-09 (rules per tag): `share.rule.set` for a connection carries exactly the §10.12 fields a
     * connection rule takes — never `per_hour`, `per_day` or `status_ttl`, which are for agent rules only.
     */
    @Test
    fun aSavedConnectionRuleSendsExactlyTheSpecFields() = runTest {
        ops.ruleAnswer = o("""{"rule_id":"r7","version":1,"subject":{"connection_id":"c1"},"tags":["address"],"mode":"auto"}""")
        val end = Instant.parse("2027-01-01T04:59:00Z")
        m.saveRule(RuleDraft("c1", listOf("address", "home"), TagMatch.ANY, ShareMode.AUTO, uses = 25, expiresAt = end, includeExisting = false))
        assertEquals(
            """{"subject":{"connection_id":"c1"},"tags":["address","home"],"match":"any","access":"read","mode":"auto","uses":25,"expires_at":"2027-01-01T04:59:00.000Z","include_existing":false}""",
            VaultJson.json.encodeToString(JsonObject.serializer(), ops.ruleSets.single()),
        )
        ops.ruleSets.clear()
        m.saveRule(RuleDraft("c1", listOf("drivers-license"), ruleId = "r2", version = 3))
        assertEquals(
            """{"rule_id":"r2","version":3,"subject":{"connection_id":"c1"},"tags":["drivers-license"],"match":"any","access":"read","mode":"ask","include_existing":true}""",
            VaultJson.json.encodeToString(JsonObject.serializer(), ops.ruleSets.single()),
        )
        listOf("per_hour", "per_day", "status_ttl").forEach { k -> assertNull(ops.ruleSets.single()[k]) }
        // The dry run is the same body with dry_run (§10.12).
        ops.ruleSets.clear()
        ops.ruleAnswer = o("""{"matches":[],"total":0}""")
        m.preview(RuleDraft("c1", listOf("drivers-license"), ruleId = "r2", version = 3))
        assertEquals(
            """{"rule_id":"r2","version":3,"subject":{"connection_id":"c1"},"tags":["drivers-license"],"match":"any","access":"read","mode":"ask","include_existing":true,"dry_run":true}""",
            VaultJson.json.encodeToString(JsonObject.serializer(), ops.ruleSets.single()),
        )
    }

    @Test
    fun anEndAndUsesOutsideTheSpecRangesAreNotSent() = runTest {
        val now = Instant.now()
        assertTrue(RuleDraft("c1", listOf("a"), uses = 1).valid(now))
        assertTrue(RuleDraft("c1", listOf("a"), uses = 10_000).valid(now))
        assertFalse(RuleDraft("c1", listOf("a"), uses = 10_001).valid(now))
        assertTrue(RuleDraft("c1", listOf("a"), expiresAt = now.plusSeconds(3_649L * 86_400)).valid(now))
        assertFalse(RuleDraft("c1", listOf("a"), expiresAt = now.plusSeconds(3_651L * 86_400)).valid(now))
        assertFalse(RuleDraft("c1", (1..17).map { "t$it" }).valid(now))
        assertEquals(64, RuleDraft.MAX_RULES_PER_SUBJECT)
        assertEquals(512, RuleDraft.MAX_RULES)
    }

    /** §10.12: no precedence between a connection's rules; overlaps are the same tag or the same item. */
    @Test
    fun overlapsAreRulesNamingTheSameTagOrHoldingTheSameItem() {
        val address = ShareRule("r1", 1, "c1", tags = listOf("address"), mode = ShareMode.AUTO, included = listOf("i1"))
        val license = ShareRule("r2", 1, "c1", tags = listOf("drivers-license"), included = listOf("i2"))
        val travel = ShareRule("r3", 1, "c1", tags = listOf("travel", "drivers-license"), match = TagMatch.ALL, pending = listOf("i2"))
        val home = ShareRule("r4", 1, "c1", tags = listOf("home"), included = listOf("i1"))
        val o = RuleOverlaps.of(listOf(address, license, travel, home))
        assertEquals(listOf("r4"), o.getValue("r1").map { it.ruleId }) // same item i1
        assertEquals(listOf("r3"), o.getValue("r2").map { it.ruleId }) // same tag and item
        assertEquals(listOf("r2"), o.getValue("r3").map { it.ruleId })
        assertEquals(listOf("r1"), o.getValue("r4").map { it.ruleId })
        // A draft: its own rule never counts; matched items count.
        assertEquals(listOf("r3"), RuleOverlaps.forDraft("r2", listOf("drivers-license"), emptyList(), listOf(address, license, travel)).map { it.ruleId })
        assertEquals(listOf("r1"), RuleOverlaps.forDraft(null, listOf("x"), listOf("i1"), listOf(address, license)).map { it.ruleId })
        assertEquals(listOf("drivers-license"), RuleOverlaps.sharedTags(listOf("drivers-license", "x"), travel))
        assertEquals(setOf("i2"), RuleOverlaps.sharedItems(listOf("i1", "i2"), travel))
    }

    @Test
    fun aRuleIsParsedWithItsItemStates() {
        val r = SharingManager.rule(
            o("""{"rule_id":"r1","version":2,"subject":{"connection_id":"c1"},"tags":["medical"],"match":"any","access":"read","mode":"ask","include_existing":true,"included":["i1"],"pending":["i2","i3"],"declined":[],"expires_at":"2027-01-01T00:00:00.000Z"}"""),
        )!!
        assertEquals("c1", r.connectionId)
        assertEquals(ShareMode.ASK, r.mode)
        assertEquals(listOf("i2", "i3"), r.pending)
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), r.expiresAt)
        assertTrue(r.matches(listOf("medical", "x")))
        assertFalse(r.copy(match = TagMatch.ALL, tags = listOf("medical", "travel")).matches(listOf("medical")))
        assertNull(SharingManager.rule(o("""{"version":1}""")))
    }

    @Test
    fun rulesAreListedPageByPage() = runTest {
        ops.rulePages = ArrayDeque(
            listOf(o("""{"rules":[{"rule_id":"r1","tags":["a"]}],"next":"r1"}"""), o("""{"rules":[{"rule_id":"r2","tags":["b"]}]}""")),
        )
        assertEquals(listOf("r1", "r2"), m.rules("c1").map { it.ruleId })
        assertEquals(listOf("ruleList:c1:null", "ruleList:c1:r1"), ops.calls)
    }

    @Test
    fun aPreviewListsTheMatches() = runTest {
        ops.ruleAnswer = o("""{"matches":[{"item_id":"i1","name":"Allergies","category":"medical","sensitivity":"data"},{"item_id":"i2","name":"Key","category":"crypto_wallet","sensitivity":"critical"}],"total":5}""")
        val p = m.preview(RuleDraft("c1", listOf("medical")))
        assertEquals(5, p.total)
        assertEquals(Sensitivity.CRITICAL, p.matches[1].sensitivity)
        assertEquals(true, (ops.ruleSets.single()["dry_run"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
    }

    @Test
    fun anInvalidRuleIsNotSent() = runTest {
        try {
            m.saveRule(RuleDraft("c1", emptyList()))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OTHER, e.kind)
        }
        assertFalse(RuleDraft("c1", listOf("@profile")).valid(Instant.now()))
        assertFalse(RuleDraft("c1", listOf("a"), uses = 0).valid(Instant.now()))
        assertFalse(RuleDraft("c1", listOf("a"), expiresAt = Instant.now().minusSeconds(1)).valid(Instant.now()))
        assertTrue(ops.ruleSets.isEmpty())
    }

    @Test
    fun grantsAreSplitGivenAndReceived() = runTest {
        val g = m.grants()
        assertEquals("Allergies", g.given.single().name)
        assertEquals(2, g.given.single().usesLeft)
        assertEquals("r1", g.given.single().ruleId)
        assertEquals(GrantDirection.RECEIVED, g.received.single().direction)
        assertNull(g.received.single().usesLeft)
        // 0.21.0: a received grant's labels as its descriptor had them (a malformed one skipped); a given one has none.
        assertEquals(listOf(FieldLabel("f1", "Number", "text"), FieldLabel("f3", "Valid to", "date")), g.received.single().labels)
        assertTrue(g.given.single().labels.isEmpty())
        // `requested`: the entries exactly as sent, and the state (pending when absent).
        assertEquals(
            listOf(GrantAskEntry("category", "medical", "Your vaccination record"), GrantAskEntry("item", "01ITEM")),
            g.requested[0].entries,
        )
        assertEquals("granted", g.requested[0].state)
        assertEquals("pending", g.requested[1].state)
    }

    @Test
    fun aFetchedValueIsParsedAndWiped() = runTest {
        val pt = """{"item_id":"x9","version":2,"name":"Insurance card","category":"insurance","fields":[{"field_id":"f1","label":"Policy","kind":"text","value":"P-42"},{"field_id":"f2","label":"Address","kind":"address","value":{"city":"Paris"}}],"notes":"n"}""".toByteArray()
        ops.fetched = GrantFetched("g2", pt, 4, null)
        val c = (m.fetchShared("g2") as FetchOutcome.Shared).content
        assertEquals("Insurance card", c.name)
        assertEquals(FieldValue.Text("P-42"), c.fields[0].value)
        assertEquals(FieldValue.Address(AddressValue(city = "Paris")), c.fields[1].value)
        assertEquals(4L, c.usesLeft)
        assertArrayEquals(ByteArray(pt.size), pt)
        ops.fetched = GrantFetched("g2", null, null, "exhausted")
        assertEquals(FetchOutcome.Refused("exhausted"), m.fetchShared("g2"))
    }

    @Test
    fun aRequestAsksForACategory() = runTest {
        assertEquals("01REQ", m.requestGrant("c1", "insurance", " Your insurance card ", ""))
        assertEquals("""{"kind":"category","ref":"insurance","label":"Your insurance card"}""", VaultJson.json.encodeToString(JsonObject.serializer(), ops.asked!!.second))
        try {
            m.requestGrant("c1", "Not A Category", null, null)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OTHER, e.kind)
        }
    }

    // --- VAULT-MESSAGING 0.23.0 ---

    /** §10.12: one tag, and `per_hour`/`per_day` on a connection rule only when set (absent: no limit). */
    @Test
    fun aConnectionRuleSendsItsOneTagAndItsRateLimits() = runTest {
        val d = RuleDraft("c1", listOf("medical"), mode = ShareMode.AUTO, uses = 3, perHour = 5, perDay = 20)
        assertEquals(
            """{"subject":{"connection_id":"c1"},"tags":["medical"],"match":"any","access":"read","mode":"auto","uses":3,"per_hour":5,"per_day":20,"include_existing":true}""",
            VaultJson.json.encodeToString(JsonObject.serializer(), SharingManager.ruleBody(d, dryRun = false)),
        )
        val bare = SharingManager.ruleBody(RuleDraft("c1", listOf("medical")), dryRun = true)
        assertFalse("per_hour" in bare)
        assertFalse("per_day" in bare)
        // The ranges as for agents: 1-3,600 and 1-86,400.
        val now = Instant.parse("2026-10-09T10:00:00Z")
        assertTrue(d.copy(perHour = 3_600, perDay = 86_400).valid(now))
        assertFalse(d.copy(perHour = 3_601).valid(now))
        assertFalse(d.copy(perHour = 0).valid(now))
        assertFalse(d.copy(perDay = 86_401).valid(now))
        // A rule as listed carries its limits.
        val r = SharingManager.rule(o("""{"rule_id":"r1","version":2,"subject":{"connection_id":"c1"},"tags":["medical"],"per_hour":5,"per_day":20}"""))!!
        assertEquals(5, r.perHour)
        assertEquals(20, r.perDay)
        assertEquals(5, RuleDraft.of(r).perHour)
        assertNull(SharingManager.rule(o("""{"rule_id":"r2","tags":["x"]}"""))!!.perHour)
    }

    /** §10.12 dry run (0.23.0): `outcome` and `ask_rule_id` per match; an older vault's matches have neither. */
    @Test
    fun theDryRunSaysWhatSavingDoesToEachItem() = runTest {
        ops.ruleAnswer = o(
            """{"matches":[{"item_id":"i1","name":"Allergies","category":"medical","sensitivity":"data","outcome":"ask","ask_rule_id":"r1"},{"item_id":"i2","name":"Bank","category":"bank_account","sensitivity":"data","outcome":"include"},{"item_id":"i3","name":"Old","category":"note","sensitivity":"data","state":"included"}],"total":3}""",
        )
        val p = m.preview(RuleDraft("c1", listOf("money"), mode = ShareMode.AUTO))
        assertEquals(listOf("ask", "include", null), p.matches.map { it.outcome })
        assertEquals(listOf("r1", null, null), p.matches.map { it.askRuleId })
        assertEquals("included", p.matches[2].state)
        assertEquals("true", ops.ruleSets.last()["dry_run"].toString())
    }

    /** §10.12: a grant's `limits` (the rule's, as of issue for a received one); a `rate_limited` fetch's `retry_after`. */
    @Test
    fun grantsCarryTheirRateLimitsAndARefusalItsRetry() = runTest {
        val g = SharingManager.grant(
            o("""{"grant_id":"g1","connection_id":"c1","direction":"received","ref":"x","name":"Card","category":"insurance","limits":{"per_hour":5,"per_day":20}}"""),
            GrantDirection.RECEIVED,
        )!!
        assertEquals(5, g.perHour)
        assertEquals(20, g.perDay)
        val none = SharingManager.grant(o("""{"grant_id":"g2","connection_id":"c1","ref":"x","name":"Card"}"""), GrantDirection.RECEIVED)!!
        assertNull(none.perHour)
        assertNull(none.perDay)
        ops.fetched = GrantFetched("g1", null, null, "rate_limited", 720)
        assertEquals(FetchOutcome.Refused("rate_limited", 720), m.fetchShared("g1"))
        // An older vault's unknown error has no retry.
        ops.fetched = GrantFetched("g1", null, null, "exhausted")
        assertEquals(FetchOutcome.Refused("exhausted"), m.fetchShared("g1"))
    }

    /** §10.12 (owner's review of #181): a rule is named by its tags; two that read the same differ by mode, then end. */
    @Test
    fun rulesAreNamedByTheirTags() {
        assertEquals("medical", RuleNames.text(listOf("medical"), TagMatch.ANY))
        assertEquals("medical + id", RuleNames.text(listOf("medical", "id"), TagMatch.ALL))
        assertEquals("medical or id", RuleNames.text(listOf("medical", "id"), TagMatch.ANY))
        val a = ShareRule("r1", 1, "c1", tags = listOf("medical"))
        val b = ShareRule("r2", 1, "c1", tags = listOf("medical"), mode = ShareMode.AUTO)
        val c = ShareRule("r3", 1, "c1", tags = listOf("money"))
        val names = RuleNames.of(listOf(a, b, c))
        assertEquals(RuleNames.Name("medical", RuleNames.Qualifier.Mode(ShareMode.ASK)), names["r1"])
        assertEquals(RuleNames.Name("medical", RuleNames.Qualifier.Mode(ShareMode.AUTO)), names["r2"])
        assertEquals(RuleNames.Name("money"), names["r3"])
        val end = Instant.parse("2027-01-01T00:00:00Z")
        val same = RuleNames.of(listOf(a, a.copy(ruleId = "r4", expiresAt = end)))
        assertEquals(RuleNames.Qualifier.Ends(null), same.getValue("r1").qualifier)
        assertEquals(RuleNames.Qualifier.Ends(end), same.getValue("r4").qualifier)
    }

    /** §10.12 Overlapping rules (0.23.0): an `auto` rule asks first for what an overlapping `ask` rule covers. */
    @Test
    fun anAutoRuleAsksFirstWhereAnAskRuleOverlaps() {
        val ask = ShareRule("r1", 1, "c1", tags = listOf("medical"), included = listOf("i1"))
        val auto = ShareRule("r2", 1, "c1", tags = listOf("insurance"), mode = ShareMode.AUTO, included = listOf("i1"))
        val other = ShareRule("r3", 1, "c1", tags = listOf("money"), mode = ShareMode.AUTO)
        val o = RuleOverlaps.of(listOf(ask, auto, other))
        assertEquals(listOf("r2"), o.getValue("r1").map { it.ruleId })
        assertEquals(listOf("r1"), RuleOverlaps.asksFirst(ShareMode.AUTO, o.getValue("r2")).map { it.ruleId })
        assertTrue(RuleOverlaps.asksFirst(ShareMode.ASK, o.getValue("r1")).isEmpty())
        assertTrue(o.getValue("r3").isEmpty())
        assertTrue(ShareRule("r9", 1, "c1", tags = listOf("a", "b")).multiTag)
        assertFalse(ask.multiTag)
    }
}
