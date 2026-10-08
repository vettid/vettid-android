// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "DestructuringDeclarationWithTooManyEntries")

package com.vettid.core.data.items

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.Item
import com.vettid.core.vault.ItemContent
import com.vettid.core.vault.ItemField
import com.vettid.core.vault.ItemPage
import com.vettid.core.vault.ItemRef
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The items repository over the vault calls (VAULT-MESSAGING §10.7): parsing, the critical path, checks and sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemsManagerTest {
    private class Ops : ItemsOps {
        val calls = mutableListOf<String>()
        var pages = ArrayDeque<ItemPage>()
        var items = mutableMapOf<String, Item>()
        var revealed = mutableMapOf<String, Item>()
        var plaintext: ByteArray = ByteArray(0)
        var error: String? = null
        var errorBody: JsonObject? = null

        /** The next critical `item.put` fails with this code (an older vault refusing kept values: `bad_request`). */
        var criticalError: String? = null
        var effect: JsonObject = JsonObject(emptyMap())
        val dryRuns = mutableListOf<List<Any?>>()
        val puts = mutableListOf<List<Any?>>()
        val deletes = mutableListOf<Pair<String, String?>>()
        val sensitivities = mutableListOf<List<Any?>>()

        private fun err(t: String) = error?.let { throw VaultOpException(t, it, "", errorBody) }

        override suspend fun list(after: String?, limit: Int): ItemPage {
            calls += "list:$after:$limit"
            return pages.removeFirstOrNull() ?: ItemPage()
        }

        override suspend fun get(itemId: String): Item {
            calls += "get"
            err("item.get")
            return items[itemId] ?: throw VaultOpException("item.get", "not_found", "")
        }

        override suspend fun reveal(itemId: String): Item {
            calls += "reveal"
            return revealed.getValue(itemId)
        }

        override suspend fun revealCritical(password: String, itemId: String): ByteArray {
            calls += "revealCritical:$password"
            err("item.reveal")
            return plaintext
        }

        override suspend fun put(content: ItemContent, sensitivity: String?, tags: List<String>?, itemId: String?, version: Long?): ItemRef {
            calls += "put"
            err("item.put")
            puts += listOf(content, sensitivity, tags, itemId, version)
            return ItemRef(itemId ?: "01NEW", (version ?: 0) + 1)
        }

        override suspend fun putCritical(password: String, content: ItemContent, tags: List<String>?, itemId: String?, version: Long?): ItemRef {
            calls += "putCritical:$password"
            criticalError?.let {
                criticalError = null
                throw VaultOpException("item.put", it, "")
            }
            puts += listOf(content, "critical", tags, itemId, version)
            return ItemRef(itemId ?: "01CRIT", (version ?: 0) + 1, credentialVersion = 9)
        }

        override suspend fun tag(itemId: String, version: Long, tags: List<String>): Long {
            calls += "tag:$tags"
            return version + 1
        }

        override suspend fun putDryRun(itemId: String?, version: Long?, sensitivity: String?, tags: List<String>?): JsonObject {
            calls += "dryRun"
            err("item.put")
            dryRuns += listOf(itemId, version, sensitivity, tags)
            return effect
        }

        override suspend fun sensitivity(itemId: String, version: Long, sensitivity: String, password: String?): Long {
            sensitivities += listOf(itemId, version, sensitivity, password)
            return version + 1
        }

        override suspend fun delete(itemId: String, password: String?) {
            deletes += itemId to password
        }
    }

    private val ops = Ops()
    private val scope = TestScope()
    private val m = ItemsManager(scope, ops = { ops })

    private fun item(id: String, name: String, s: String = "data", vararg fields: ItemField) =
        Item(itemId = id, version = 2, name = name, category = "identity_document", sensitivity = s, tags = listOf("travel"), fields = fields.toList())

    private fun json(s: String) = VaultJson.json.parseToJsonElement(s)

    @Test
    fun theListIsReadPageByPage() = scope.runTest {
        ops.pages = ArrayDeque(
            listOf(ItemPage(listOf(item("01A", "Passport")), next = "01A"), ItemPage(listOf(item("01B", "Login", "secret")))),
        )
        m.refresh()
        assertEquals(listOf("list:null:500", "list:01A:500"), ops.calls)
        assertEquals(listOf("Passport", "Login"), m.items.value.map { it.name })
        assertEquals(Sensitivity.SECRET, m.items.value[1].sensitivity)
        assertEquals(ListLoad.LOADED, m.load.value)
    }

    @Test
    fun aDataItemComesWithItsValuesAndAddresses() = scope.runTest {
        ops.items["01A"] = item(
            "01A", "Licence", "data",
            ItemField("f1", "Number", "text", JsonPrimitive("X1")),
            ItemField("f2", "Address", "address", json("""{"city":"Berlin","country":"DE"}""")),
        )
        val d = m.get("01A")
        assertTrue(d.revealed)
        assertEquals(FieldValue.Text("X1"), d.fields[0].value)
        assertEquals(FieldValue.Address(AddressValue(city = "Berlin", country = "DE")), d.fields[1].value)
    }

    @Test
    fun aSecretItemComesWithoutValuesUntilRevealed() = scope.runTest {
        ops.items["01S"] = item("01S", "Login", "secret", ItemField("f1", "Password", "password")).copy(hasNotes = true)
        ops.revealed["01S"] = item("01S", "Login", "secret", ItemField("f1", "Password", "password", JsonPrimitive("hunter2"))).copy(notes = "n")
        val hidden = m.get("01S")
        assertFalse(hidden.revealed)
        assertNull(hidden.fields[0].value)
        assertTrue(hidden.hasNotes)
        val shown = m.reveal("01S")
        assertTrue(shown.revealed)
        assertEquals(FieldValue.Text("hunter2"), shown.fields[0].value)
        assertEquals("n", shown.notes)
        assertFalse(shown.hidden().revealed)
        assertNull(shown.hidden().fields[0].value)
        assertNull(shown.hidden().notes)
    }

    @Test
    fun aCriticalItemOpensWithThePasswordAndThePlaintextIsWiped() = scope.runTest {
        ops.items["01C"] = item("01C", "Phrase", "critical", ItemField("f1", "Words", "multiline"), ItemField("f2", "Passphrase", "password"))
        val pt = """{"fields":[{"field_id":"f1","value":"abandon ability"},{"field_id":"f2","value":""}],"notes":"cold"}""".toByteArray()
        ops.plaintext = pt
        val d = m.revealCritical("01C", "pw")
        assertTrue("revealCritical:pw" in ops.calls)
        assertEquals(FieldValue.Text("abandon ability"), d.fields[0].value)
        assertEquals(FieldValue.Text(""), d.fields[1].value)
        assertEquals("cold", d.notes)
        assertTrue(d.revealed)
        assertArrayEquals(ByteArray(pt.size), pt)
    }

    @Test
    fun aWrongPasswordIsBadPassword() = scope.runTest {
        ops.items["01C"] = item("01C", "Phrase", "critical")
        ops.error = null
        ops.plaintext = ByteArray(0)
        val failing = object : ItemsOps by ops {
            override suspend fun revealCritical(password: String, itemId: String): ByteArray =
                throw VaultOpException("item.reveal", "backoff", "", JsonObject(mapOf("retry_after" to JsonPrimitive(30))))
        }
        val m2 = ItemsManager(scope, ops = { failing })
        try {
            m2.revealCritical("01C", "x")
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.BACKOFF, e.kind)
            assertEquals(30, e.retryAfterSeconds)
        }
    }

    @Test
    fun createSendsTheCheckedContent() = scope.runTest {
        val draft = ItemDraft(
            name = " Passport ", category = "identity_document", template = "passport", sensitivity = Sensitivity.SECRET,
            tags = listOf("Travel", "travel", "ID"), fields = listOf(DraftField(label = " Number ", kind = "text", text = "X1")),
        )
        assertEquals("01NEW", m.create(draft))
        val (content, s, tags, id, v) = ops.puts.single()
        content as ItemContent
        assertEquals("Passport", content.name)
        assertEquals("passport", content.template)
        assertEquals(listOf(ItemField(null, "Number", "text", JsonPrimitive("X1"))), content.fields)
        assertNull(content.notes)
        assertEquals("secret", s)
        assertEquals(listOf("id", "travel"), tags)
        assertNull(id)
        assertNull(v)
    }

    @Test
    fun anInvalidDraftIsNeverSent() = scope.runTest {
        try {
            m.create(ItemDraft(name = "", fields = listOf(DraftField(label = "D", kind = "date", text = "tomorrow"))))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OTHER, e.kind)
        }
        assertTrue(ops.puts.isEmpty())
    }

    @Test
    fun anUpdateNamesTheVersionAndKeepsTheSensitivity() = scope.runTest {
        val d = ItemDraft(name = "P", fields = listOf(DraftField("f1", "N", "text", "1")))
        assertEquals(4, m.update("01A", 3, d))
        val (content, s, _, id, v) = ops.puts.single()
        assertNull(s)
        assertEquals("01A", id)
        assertEquals(3L, v)
        assertEquals("f1", (content as ItemContent).fields!!.single().fieldId)
    }

    @Test
    fun criticalItemsAreWrittenWithThePassword() = scope.runTest {
        val d = ItemDraft(name = "Phrase", category = "crypto_wallet", sensitivity = Sensitivity.CRITICAL, fields = listOf(DraftField(label = "W", kind = "multiline", text = "a b")))
        assertEquals("01CRIT", m.createCritical(d, "pw"))
        m.updateCritical("01CRIT", 1, d, "pw")
        assertEquals(listOf("putCritical:pw", "list:null:500", "putCritical:pw", "list:null:500"), ops.calls)
    }

    @Test
    fun aCriticalDeleteNeedsThePassword() = scope.runTest {
        try {
            m.delete("01C", Sensitivity.CRITICAL)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.BAD_PASSWORD, e.kind)
        }
        m.delete("01C", Sensitivity.CRITICAL, "pw")
        m.delete("01A", Sensitivity.DATA, "ignored")
        assertEquals(listOf("01C" to "pw", "01A" to null), ops.deletes)
    }

    @Test
    fun onlyMovesToOrFromCriticalCarryThePassword() = scope.runTest {
        m.setSensitivity("01A", 2, Sensitivity.DATA, Sensitivity.SECRET, "ignored")
        m.setSensitivity("01A", 3, Sensitivity.SECRET, Sensitivity.CRITICAL, "pw")
        m.setSensitivity("01A", 4, Sensitivity.CRITICAL, Sensitivity.DATA, "pw")
        assertEquals(listOf(null, "pw", "pw"), ops.sensitivities.map { it[3] })
        assertEquals(listOf("secret", "critical", "data"), ops.sensitivities.map { it[2] })
        try {
            m.setSensitivity("01A", 5, Sensitivity.DATA, Sensitivity.CRITICAL)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.BAD_PASSWORD, e.kind)
        }
    }

    @Test
    fun tagsAreNormalisedBeforeTheyAreSent() = scope.runTest {
        m.setTags("01A", 2, listOf("Medical ", "@profile"))
        assertTrue("tag:[@profile, medical]" in ops.calls)
    }

    @Test
    fun theVaultsLimitIsALimitFailure() = scope.runTest {
        ops.error = "limit"
        try {
            m.create(ItemDraft(name = "One too many"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.LIMIT, e.kind)
        }
    }

    @Test
    fun anotherDevicesChangeReReadsTheList() = scope.runTest {
        ops.pages = ArrayDeque(listOf(ItemPage(listOf(item("01A", "Passport"), item("01B", "Visa")))))
        m.refresh()
        ops.pages = ArrayDeque(listOf(ItemPage(listOf(item("01A", "Passport")))))
        m.onEvent("sync.event", json("""{"kind":"item.deleted","item_id":"01B"}""").jsonObject)
        assertEquals(listOf("01A"), m.items.value.map { it.itemId })
        m.onEvent("sync.event", json("""{"kind":"item.changed","item_id":"01A","version":3}""").jsonObject)
        m.onEvent("sync.event", json("""{"kind":"tag.changed","version":3}""").jsonObject)
        m.onEvent("sync.event", json("""{"kind":"message.read"}""").jsonObject)
        advanceUntilIdle()
        // The vault call itself runs on the IO dispatcher (vaultGuard): wait for it in real time.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while (ops.calls.count { it.startsWith("list:null") } < 2) kotlinx.coroutines.delay(10) }
        }
        assertEquals(2, ops.calls.count { it.startsWith("list:null") })
    }

    // --- VAULT-MESSAGING 0.21.0: kept values, the size, the dry run, named limits ---

    private fun secretLogin(size: Long?) = item("01S", "Login", "secret", ItemField("f1", "User", "text"), ItemField("f2", "Password", "password"))
        .copy(hasNotes = true, size = size)

    @Test
    fun aSecretItemIsEditedWithItsValuesKeptAndNothingRevealed() = scope.runTest {
        ops.items["01S"] = secretLogin(size = 180)
        val d = m.get("01S")
        assertEquals(180, d.size)
        val draft = ItemDraft.of(d)
        assertTrue(draft.fields.all { it.kept })
        assertTrue(draft.keepNotes)
        // The member renames one field and types a new password; the user name stays kept.
        val edited = draft.copy(fields = listOf(draft.fields[0].copy(label = "Login name"), draft.fields[1].copy(text = "new-pass", kept = false)))
        m.update("01S", 2, edited)
        assertFalse("reveal" in ops.calls)
        val content = ops.puts.single()[0] as ItemContent
        assertEquals(listOf(ItemField("f1", "Login name", "text", null), ItemField("f2", "Password", "password", JsonPrimitive("new-pass"))), content.fields)
        assertTrue(content.keepNotes)
        assertNull(content.notes)
    }

    @Test
    fun aCriticalItemIsEditedInOneCredentialOperation() = scope.runTest {
        ops.items["01C"] = item("01C", "Phrase", "critical", ItemField("f1", "Words", "multiline")).copy(hasNotes = true, size = 150)
        val draft = ItemDraft.of(m.get("01C")).let { it.copy(name = "Cold phrase") }
        m.updateCritical("01C", 2, draft, "pw")
        assertEquals(1, ops.calls.count { it.startsWith("putCritical") })
        assertFalse(ops.calls.any { it.startsWith("revealCritical") })
        val content = ops.puts.single()[0] as ItemContent
        assertEquals(listOf(ItemField("f1", "Words", "multiline", null)), content.fields)
        assertTrue(content.keepNotes)
    }

    @Test
    fun aVaultWithoutSizeGetsASecretItemsValuesInFull() = scope.runTest {
        // Before 0.21.0 a vault keeps no values: the app reads them (never shown) and sends them all.
        ops.items["01S"] = secretLogin(size = null)
        ops.revealed["01S"] = item(
            "01S", "Login", "secret", ItemField("f1", "User", "text", JsonPrimitive("sam")), ItemField("f2", "Password", "password", JsonPrimitive("old")),
        ).copy(notes = "branch")
        val draft = ItemDraft.of(m.get("01S"))
        assertFalse(ItemChecks.check(draft).sizeKnown)
        m.update("01S", 2, draft.copy(fields = listOf(draft.fields[0], draft.fields[1].copy(text = "new", kept = false))))
        assertTrue("reveal" in ops.calls)
        val content = ops.puts.single()[0] as ItemContent
        assertEquals(listOf(JsonPrimitive("sam"), JsonPrimitive("new")), content.fields!!.map { it.value })
        assertEquals("branch", content.notes)
        assertFalse(content.keepNotes)
    }

    @Test
    fun anOlderVaultRefusingKeptValuesGetsThemWithTheSamePassword() = scope.runTest {
        ops.items["01C"] = item("01C", "Phrase", "critical", ItemField("f1", "Words", "multiline"))
        ops.plaintext = """{"fields":[{"field_id":"f1","value":"abandon ability"}]}""".toByteArray()
        ops.criticalError = "bad_request"
        m.updateCritical("01C", 2, ItemDraft.of(m.get("01C")), "pw")
        assertEquals(listOf("putCritical:pw", "revealCritical:pw", "putCritical:pw"), ops.calls.filter { it.contains(":pw") })
        val content = ops.puts.single()[0] as ItemContent
        assertEquals(JsonPrimitive("abandon ability"), content.fields!!.single().value)
    }

    @Test
    fun keptNotesAloneOnAnItemWithoutSizeAreOpenedFirst() = scope.runTest {
        // An older vault ignores keep_notes: with every field typed, the notes would be lost. The values come first.
        ops.items["01C"] = item("01C", "Phrase", "critical", ItemField("f1", "Words", "multiline")).copy(hasNotes = true)
        ops.plaintext = """{"fields":[{"field_id":"f1","value":"old"}],"notes":"cold"}""".toByteArray()
        val draft = ItemDraft.of(m.get("01C"))
        m.updateCritical("01C", 2, draft.copy(fields = listOf(draft.fields[0].copy(text = "new", kept = false))), "pw")
        assertEquals(listOf("revealCritical:pw", "putCritical:pw"), ops.calls.filter { it.contains(":pw") })
        val content = ops.puts.single()[0] as ItemContent
        assertEquals("cold", content.notes)
        assertEquals(JsonPrimitive("new"), content.fields!!.single().value)
    }

    @Test
    fun anotherRefusalOfACriticalEditIsNotRetried() = scope.runTest {
        ops.items["01C"] = item("01C", "Phrase", "critical", ItemField("f1", "Words", "multiline")).copy(size = 120)
        ops.criticalError = "bad_request"
        try {
            m.updateCritical("01C", 2, ItemDraft.of(m.get("01C")), "pw")
            fail()
        } catch (e: VaultFailure) {
            assertEquals("bad_request", e.code)
        }
        assertFalse(ops.calls.any { it.startsWith("revealCritical") })
    }

    @Test
    fun theDryRunNeverCarriesContentAndIsParsed() = scope.runTest {
        ops.effect = json(
            """{"version":3,"shares":[{"rule_id":"01R1","subject":{"connection_id":"c1"},"mode":"auto"},{"rule_id":"01R2","subject":{"connection_id":"c2"},"mode":"ask","usable":true},{"subject":{}}],"withdrawals":[{"rule_id":"01R3","subject":{"agent_id":"a1"},"state":"pending"}]}""",
        ).jsonObject
        val e = m.shareEffect("01A", 3, Sensitivity.DATA, listOf("Medical"))
        assertEquals(listOf("01A", 3L, null, listOf("medical")), ops.dryRuns.single())
        assertEquals(3L, e.version)
        assertEquals(listOf(EffectShare("01R1", ShareSubject("c1"), ShareMode.AUTO), EffectShare("01R2", ShareSubject("c2"), ShareMode.ASK, usable = true)), e.shares)
        assertEquals(listOf(EffectWithdrawal("01R3", ShareSubject(agentId = "a1"), "pending")), e.withdrawals)
        m.shareEffect(null, 9, Sensitivity.CRITICAL, emptyList())
        assertEquals(listOf(null, null, "critical", emptyList<String>()), ops.dryRuns[1])
    }

    @Test
    fun aLimitNamesItsLimit() = scope.runTest {
        ops.error = "limit"
        ops.errorBody = json("""{"limit":"item_size","max":65536,"size":70001}""").jsonObject
        try {
            m.create(ItemDraft(name = "Big"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.LIMIT, e.kind)
            assertEquals(com.vettid.core.data.vault.VaultLimit("item_size", 65_536, 70_001), e.limit)
        }
        ops.errorBody = null
        try {
            m.create(ItemDraft(name = "Older vault"))
            fail()
        } catch (e: VaultFailure) {
            assertNull(e.limit)
        }
    }

    @Test
    fun clearForgetsTheList() = scope.runTest {
        ops.pages = ArrayDeque(listOf(ItemPage(listOf(item("01A", "Passport")))))
        m.refresh()
        m.clear()
        assertTrue(m.items.value.isEmpty())
        assertEquals(ListLoad.NOT_LOADED, m.load.value)
    }
}
