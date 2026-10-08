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
}
