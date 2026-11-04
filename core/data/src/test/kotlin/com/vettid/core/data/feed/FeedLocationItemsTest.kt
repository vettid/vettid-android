// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.feed

import com.vettid.core.data.R
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The feed items of Location (ANDROID-PLAN §4 "Location", Notifications, L1b): their titles and where they lead. */
class FeedLocationItemsTest {
    private fun item(kind: String, ref: String? = "r1") =
        FeedItem("i", 1, kind, "2026-11-04T12:00:00Z", "active", connectionId = "c1", ref = ref)

    private val names = FeedNames(
        connections = mapOf("c1" to ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")),
    )

    @Test
    fun theTitlesNameTheFirstName() {
        assertEquals(FeedText(R.string.data_feed_location_request, listOf(FeedText(literal = "⁨Sam⁩"))), FeedKinds.title(item("location.request"), names))
        assertEquals(FeedText(R.string.data_feed_location_shared, listOf(FeedText(literal = "⁨Sam⁩"))), FeedKinds.title(item("location.shared", "s1"), names))
    }

    @Test
    fun theyAreNoLongerNotAvailable() {
        assertFalse(FeedKinds.notAvailable("location.request"))
        assertFalse(FeedKinds.notAvailable("location.shared"))
    }

    @Test
    fun aRequestLeadsToItsApprovalAndASharedLocationToTheConnection() {
        assertEquals(FeedTarget.ApprovalEntry("location:r1"), FeedKinds.target(item("location.request")))
        assertEquals(FeedTarget.Connection("c1"), FeedKinds.target(item("location.shared", "s1")))
        assertEquals(FeedTarget.Sheet, FeedKinds.target(FeedItem("i", 1, "location.shared", "2026-11-04T12:00:00Z", "active", ref = "s1")))
        assertTrue(FeedKinds.isAsk("location.request"))
        assertFalse(FeedKinds.isAsk("location.shared"))
    }

    @Test
    fun theSendersNameComesFromTheOpenRequestBeforeTheConnectionIsKnown() {
        val ask = Approval.LocationRequest("r1", "c9", null, Instant.EPOCH, null, connectionName = "Kim Park")
        val n = FeedNames(approvals = mapOf(ask.key to ask))
        val t = FeedKinds.title(FeedItem("i", 1, "location.request", "2026-11-04T12:00:00Z", "active", connectionId = "c9", ref = "r1"), n)
        assertEquals(R.string.data_feed_location_request, t.res)
    }
}
