// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** VAULT-MESSAGING 0.21.0 §10.12: pending share decisions from `share.pending.list`, and one `share.decide` for both lists. */
class SharePendingTest {
    private val t = Instant.parse("2026-10-08T12:00:00Z")

    private fun o(s: String): JsonObject = VaultJson.json.parseToJsonElement(s).jsonObject

    private val rules = o(
        """{"rules":[{"rule_id":"01R1","subject":{"connection_id":"c1"},"tags":["medical"],"pending":["01I9"]},{"rule_id":"01R2","subject":{"connection_id":"c2"},"tags":["travel","id"],"pending":[]}]}""",
    )

    @Test
    fun theVaultsListIsReadPageByPageAndGroupedByRule() = runTest {
        val asked = mutableListOf<String?>()
        val pages = ArrayDeque(
            listOf(
                o("""{"pending":[{"rule_id":"01R1","subject":{"connection_id":"c1"},"item_id":"01I1","name":"Allergies","category":"medical","sensitivity":"data","at":"2026-10-08T10:00:00.000Z"}],"next":"opaque-1"}"""),
                o("""{"pending":[{"rule_id":"01R1","subject":{"connection_id":"c1"},"item_id":"01I2","name":"Signing key","category":"crypto_wallet","sensitivity":"critical","at":"2026-10-08T11:00:00.000Z"},{"rule_id":"01R2","subject":{"connection_id":"c2"},"item_id":"01I3","name":"Passport","category":"identity_document","sensitivity":"data","at":"2026-10-07T09:00:00.000Z"}]}"""),
            ),
        )
        val d = SharePending.decisions(
            pendingPage = { after ->
                asked += after
                pages.removeFirst()
            },
            rulePage = { rules },
            names = emptyMap(),
            t = t,
        )
        assertEquals(listOf(null, "opaque-1"), asked)
        assertEquals(listOf("01R1", "01R2"), d.map { it.ruleId })
        val r1 = d[0]
        assertEquals("c1", r1.subjectConnectionId)
        assertEquals(listOf(ShareItem("01I1", "Allergies", "medical", "data"), ShareItem("01I2", "Signing key", "crypto_wallet", "critical")), r1.items)
        // The newest item's time; the rule's tags from share.rule.list (its own `pending` ids are not used).
        assertEquals(Instant.parse("2026-10-08T11:00:00Z"), r1.receivedAt)
        assertEquals(listOf("medical"), r1.tags)
        assertEquals(listOf("travel", "id"), d[1].tags)
    }

    @Test
    fun theRulesTagsAreOptional() = runTest {
        val d = SharePending.decisions(
            pendingPage = { o("""{"pending":[{"rule_id":"01R1","item_id":"01I1","name":"A","category":"medical","sensitivity":"data"}]}""") },
            rulePage = { throw VaultFailure(FailureKind.NETWORK) },
            names = emptyMap(),
            t = t,
        )
        assertTrue(d.single().tags.isEmpty())
        assertEquals(t, d.single().receivedAt)
    }

    @Test
    fun anOlderVaultsRulesAreReadInstead() = runTest {
        listOf(VaultFailure(FailureKind.NOT_SUPPORTED, "unsupported_type"), VaultFailure(FailureKind.OTHER, "bad_request")).forEach { refusal ->
            val d = SharePending.decisions(
                pendingPage = { throw refusal },
                rulePage = { rules },
                names = mapOf("01I9" to ShareItem("01I9", "Blood type", "medical", "data")),
                t = t,
            )
            assertEquals("01R1", d.single().ruleId)
            assertEquals("Blood type", d.single().items.single().name)
        }
    }

    @Test
    fun anotherFailureIsNotHidden() = runTest {
        try {
            SharePending.decisions({ throw VaultFailure(FailureKind.NETWORK) }, { rules }, emptyMap(), t)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NETWORK, e.kind)
        }
    }

    @Test
    fun aMixedDecisionIsOneCall() = runTest {
        val calls = mutableListOf<String>()
        SharePending.decide(listOf("a", "b"), listOf("c"), both = { calls += "both" }, one = { i, ok -> calls += "$i:$ok" })
        assertEquals(listOf("both"), calls)
    }

    @Test
    fun anOlderVaultGetsTwoCalls() = runTest {
        val calls = mutableListOf<String>()
        SharePending.decide(
            listOf("a"), listOf("c"),
            both = { throw VaultFailure(FailureKind.OTHER, "bad_request") },
            one = { i, ok -> calls += "$i:$ok" },
        )
        assertEquals(listOf("[a]:true", "[c]:false"), calls)
        calls.clear()
        SharePending.decide(emptyList(), listOf("c"), both = { throw VaultFailure(FailureKind.OTHER, "bad_request") }, one = { i, ok -> calls += "$i:$ok" })
        assertEquals(listOf("[c]:false"), calls)
        // A limit (say) changed nothing and is the member's to see: not retried.
        try {
            SharePending.decide(listOf("a"), emptyList(), both = { throw VaultFailure(FailureKind.LIMIT, "limit") }, one = { _, _ -> fail() })
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.LIMIT, e.kind)
        }
    }
}
