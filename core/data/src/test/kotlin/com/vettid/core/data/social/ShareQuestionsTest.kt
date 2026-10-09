package com.vettid.core.data.social

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** VAULT-MESSAGING 0.23.0 §10.12: one question per item and subject, however many of its rules wait for it. */
class ShareQuestionsTest {
    private val t = Instant.parse("2026-10-09T12:00:00Z")

    private fun d(rule: String, conn: String?, vararg items: ShareItem, agent: String? = null) =
        Approval.ShareDecision(rule, conn, agent, items.toList(), null, t)

    private fun i(id: String, ask: String? = null) = ShareItem(id, id, "other", "data", askRuleId = ask)

    @Test
    fun anItemWaitingInSeveralRulesOfOneConnectionIsAskedOnce() {
        val out = ShareQuestions.dedupe(
            listOf(
                d("r2", "c1", i("i1", ask = "r1"), i("i2")),
                d("r1", "c1", i("i1"), i("i3")),
                // Another connection's rule is another subject: asked there too.
                d("r5", "c2", i("i1")),
                Approval.Authentication("a1", "c1", null, t, null),
            ),
        ).filterIsInstance<Approval.ShareDecision>().associateBy { it.ruleId }
        // r1 comes first by rule_id: it asks about i1 (noting r2), and r2 keeps only i2.
        assertEquals(listOf("i1", "i3"), out.getValue("r1").items.map { it.itemId })
        assertEquals(listOf("r2"), out.getValue("r1").items[0].alsoIn)
        assertEquals(listOf("i2"), out.getValue("r2").items.map { it.itemId })
        assertEquals(listOf("i1"), out.getValue("r5").items.map { it.itemId })
        assertEquals(emptyList<String>(), out.getValue("r5").items[0].alsoIn)
    }

    @Test
    fun aRuleLeftWithNothingOfItsOwnIsNotShownAndOtherApprovalsStay() {
        val auth = Approval.Authentication("a1", "c1", null, t, null)
        val out = ShareQuestions.dedupe(listOf(d("r1", "c1", i("i1")), d("r2", "c1", i("i1", ask = "r1")), auth))
        assertEquals(listOf("share:r1", "auth:a1"), out.map { it.key })
        val kept = out[0] as Approval.ShareDecision
        assertEquals(listOf("r2"), kept.items.single().alsoIn)
        // Agents are subjects too; a single decision is left as it is.
        val one = listOf(d("r9", null, i("i1"), agent = "ag1"))
        assertEquals(one, ShareQuestions.dedupe(one))
    }
}
