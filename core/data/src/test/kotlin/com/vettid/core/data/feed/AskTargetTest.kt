package com.vettid.core.data.feed

import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AuthPurpose
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Where a tap on an ask's item leads when its first ask may be gone (VAULT-MESSAGING 0.80.0 §10.4). */
class AskTargetTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private fun ask(id: String, conn: String = "c1") = Approval.Authentication(id, conn, null, now, null, purpose = AuthPurpose.SIGNIN)
    private fun item(ref: String, count: Int? = null) =
        FeedItem("f", 1, "connection.authenticate.requested", now.toString(), "active", connectionId = "c1", ref = ref, count = count)

    @Test
    fun aSingleOpenAskOpensItself() {
        assertEquals(FeedTarget.ApprovalEntry("auth:a1"), FeedKinds.target(item("a1"), listOf(ask("a1"))))
    }

    @Test
    fun aBatchOpensTheListNotItsFirstAsk() {
        assertEquals(FeedTarget.Approvals, FeedKinds.target(item("a1", count = 2), listOf(ask("a1"), ask("a2"))))
    }

    @Test
    fun aWithdrawnFirstAskLeadsToTheOneAskLeftOrTheList() {
        assertEquals(FeedTarget.ApprovalEntry("auth:a2"), FeedKinds.target(item("a1", count = 2), listOf(ask("a2"), ask("x", conn = "c2"))))
        assertEquals(FeedTarget.Approvals, FeedKinds.target(item("a1", count = 3), listOf(ask("a2"), ask("a3"))))
        assertEquals(FeedTarget.Approvals, FeedKinds.target(item("a1"), listOf(ask("a2"), ask("a3"))))
    }

    @Test
    fun withNothingOpenOrNoListItIsTheAskAsBefore() {
        assertEquals(FeedTarget.ApprovalEntry("auth:a1"), FeedKinds.target(item("a1", count = 2), emptyList()))
        assertEquals(FeedTarget.ApprovalEntry("auth:a1"), FeedKinds.target(item("a1", count = 2)))
    }
}
