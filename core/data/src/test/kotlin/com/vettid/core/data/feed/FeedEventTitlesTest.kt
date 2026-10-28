// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.feed

import com.vettid.core.data.R
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AuthPurpose
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feed titles of ANDROID-PLAN 0.1.52 §4 "Event titles" (approved by the owner 2026-10-19). */
class FeedEventTitlesTest {
    private fun item(kind: String, ref: String? = null, priority: String = "normal", connectionId: String? = null) =
        FeedItem("i", 1, kind, "2026-10-19T12:00:00Z", "active", priority, connectionId = connectionId, ref = ref)

    private val names = FeedNames(
        connections = mapOf("c1" to ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")),
        items = mapOf("01ITEM" to "GitHub token"),
    )

    private fun title(i: FeedItem, details: Boolean = true) = FeedKinds.title(i, names, details)

    @Test
    fun theReleaseRKindsHaveTheirTitles() {
        assertEquals(FeedText(R.string.data_feed_secret_renewed), title(item("device.secret_renewed")))
        assertEquals(FeedText(R.string.data_feed_recovery_completed), title(item("recovery.completed", "01REC")))
        assertEquals(FeedText(R.string.data_feed_counter_unavailable), title(item("recovery.counter_unavailable", priority = "urgent")))
        assertEquals(FeedText(R.string.data_feed_recovery_rollback), title(item("recovery.generation_closed", "4/rollback", "urgent")))

        assertEquals(FeedText(R.string.data_feed_rotation_failed), title(item("identity.rotation_failed", "recovery", "high")))
        assertEquals(
            FeedText(R.string.data_feed_rotate_conflict, listOf(FeedText(literal = "Sam Lee"))),
            title(item("identity.rotate_conflict", priority = "high", connectionId = "c1")),
        )
        // Not listed, but titled for the detail sheet of an older list.
        assertEquals(FeedText(R.string.data_feed_pin_changed), title(item("vault.pin_changed")))
        assertEquals(FeedText(R.string.data_feed_credential_recovered), title(item("credential.recovered")))
        assertEquals(FeedText(R.string.data_feed_recovery_device_paired), title(item("recovery.device_paired")))
    }

    /** Decision 2: a failed attempt raises the alarming title; after the member's own recovery, the plain count. */
    @Test
    fun aRecoveryAttemptsTitleFollowsItsPriorityAndOutcome() {
        assertEquals(
            FeedText(R.string.data_feed_recovery_attempt_someone, listOf(3, 10)),
            title(item("recovery.attempt", "2/3/not_success/counter", "urgent")),
        )
        assertEquals(
            FeedText(R.string.data_feed_recovery_attempt_recovered, listOf(4, 10)),
            title(item("recovery.attempt", "2/4/success/counter", "urgent")),
        )
        assertEquals(FeedText(R.string.data_feed_recovery_attempt, listOf(4, 10)), title(item("recovery.attempt", "2/4/success/counter")))
        assertEquals(FeedText(R.string.data_feed_recovery_attempt, listOf(1, 10)), title(item("recovery.attempt", "1/1/not_success/counter")))
        // A ref that does not read: the words without the number.
        assertEquals(FeedText(R.string.data_feed_recovery_attempt_someone_any), title(item("recovery.attempt", "2/3", "urgent")))
        assertEquals(FeedText(R.string.data_feed_recovery_attempt_any), title(item("recovery.attempt", null)))
    }

    @Test
    fun changeRequiredReadsItsRef() {
        val password = item("recovery.change_required", "password", "high")
        val used = item("recovery.change_required", "3", "high")
        assertEquals(FeedText(R.string.data_feed_change_required_password), title(password))
        assertEquals(FeedText(R.string.data_feed_change_required_password_body), FeedKinds.supporting(password, names))
        assertEquals(FeedText(R.string.data_feed_change_required), title(used))
        assertEquals(FeedText(R.string.data_feed_change_required_body), FeedKinds.supporting(used, names))
        assertEquals(FeedText(R.string.data_feed_rotation_failed_body), FeedKinds.supporting(item("identity.rotation_failed"), names))
        assertEquals(
            FeedText(R.string.data_feed_rotate_conflict_body),
            FeedKinds.supporting(item("identity.rotate_conflict", connectionId = "c1"), names),
        )
        assertNull(FeedKinds.supporting(item("recovery.counter_unavailable"), names))
        // The rollback notification's approved second line.
        assertEquals(
            FeedText(R.string.data_feed_recovery_rollback_body),
            FeedKinds.supporting(item("recovery.generation_closed", "4/rollback", "urgent"), names),
        )
    }

    /** Decision 8: "An agent used <item>" for a run and a proxy; without details (Names only), no item name. */
    @Test
    fun anAgentsRunAndProxyNameTheItem() {
        for (k in listOf("leash.item.run", "leash.item.proxy")) {
            assertEquals(FeedText(R.string.data_feed_leash_item_used, listOf("GitHub token")), title(item(k, "01ITEM")))
            assertEquals(FeedText(R.string.data_feed_leash_item_used_any), title(item(k, "01ITEM"), details = false))
            assertEquals(FeedText(R.string.data_feed_leash_item_used_any), title(item(k, "01GONE")))
        }
    }

    @Test
    fun theNewKindsAreKnownAndTheHistoryOnlyOnesAreFlagged() {
        val added = listOf(
            "vault.pin_changed", "device.secret_renewed", "credential.recovered", "recovery.device_paired", "recovery.completed",
            "recovery.attempt", "recovery.change_required", "recovery.counter_unavailable", "identity.rotation_failed",
            "identity.rotate_conflict", "leash.item.run", "leash.item.proxy", "recovery.generation_closed",
        )
        for (k in added) {
            assertTrue(k, FeedKinds.known(k))
            assertFalse(k, title(item(k)).res == R.string.data_feed_unknown)
        }
        assertEquals(setOf("vault.pin_changed", "credential.recovered", "recovery.device_paired"), FeedKinds.HISTORY_ONLY)
        assertFalse(FeedKinds.historyOnly("recovery.completed"))
    }
}

/** VAULT-MESSAGING 0.65.0 §10.4: a challenge's feed text per purpose (as vettid-ios #154). */
class FeedAuthPurposeTest {
    private val t = java.time.Instant.parse("2026-10-27T12:00:00Z")
    private val sam = ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")

    private fun item(count: Int? = null, connectionId: String? = "c1") =
        FeedItem("i", 1, "connection.authenticate.requested", "2026-10-27T12:00:00Z", "active", "normal", connectionId = connectionId, ref = "A1", count = count)

    private fun names(purpose: AuthPurpose, connection: Boolean = true) = FeedNames(
        connections = if (connection) mapOf("c1" to sam) else emptyMap(),
        approvals = mapOf("auth:A1" to Approval.Authentication("A1", "c1", null, t, null, purpose = purpose)),
    )

    private val isolated = "⁨Sam Lee⁩"

    @Test
    fun signinReadsSignInToTheNameWithItsBody() {
        val n = names(AuthPurpose.SIGNIN)
        assertEquals(FeedText(R.string.data_feed_authenticate_signin, listOf(FeedText(literal = isolated))), FeedKinds.title(item(), n))
        assertEquals(FeedText(R.string.data_feed_authenticate_signin_body), FeedKinds.supporting(item(), n))
    }

    @Test
    fun approvalReadsTheNameAsksYouToApprove() {
        val n = names(AuthPurpose.APPROVAL)
        assertEquals(FeedText(R.string.data_feed_authenticate_approval, listOf(FeedText(literal = isolated))), FeedKinds.title(item(), n))
        assertNull(FeedKinds.supporting(item(), n))
    }

    @Test
    fun noPurposeOrNoPendingApprovalIsToday() {
        val today = FeedText(R.string.data_feed_authenticate, listOf(FeedText(literal = "Sam Lee")))
        assertEquals(today, FeedKinds.title(item(), names(AuthPurpose.NONE)))
        assertEquals(today, FeedKinds.title(item(), FeedNames(connections = mapOf("c1" to sam))))
    }

    @Test
    fun aBatchIsNeverTitledAsASignIn() {
        val title = FeedKinds.title(item(count = 3), names(AuthPurpose.SIGNIN))
        assertEquals(
            FeedText(plural = R.plurals.data_feed_grant_request_batch, quantity = 3, args = listOf(FeedText(literal = "Sam"), 3)),
            title,
        )
        // Its second line is the count, not the sign-in body.
        assertEquals(FeedText(plural = R.plurals.data_feed_things, quantity = 3, args = listOf(3)), FeedKinds.supporting(item(count = 3), names(AuthPurpose.SIGNIN)))
    }

    @Test
    fun withoutAnyNameASignInSaysAConnection() {
        val n = names(AuthPurpose.SIGNIN, connection = false)
        assertEquals(FeedText(R.string.data_feed_authenticate_signin, listOf(FeedText(R.string.data_feed_a_connection_inline))), FeedKinds.title(item(), n))
    }

    @Test
    fun bothPurposesFallBackToAConnection() {
        val i = item()
        val inline = listOf(FeedText(R.string.data_feed_a_connection_inline))
        assertEquals(FeedText(R.string.data_feed_authenticate_approval, inline), FeedKinds.title(i, names(AuthPurpose.APPROVAL, connection = false)))
        // A connection that was removed (id known, not in the map) reads the same, not "Removed connection".
        val gone = item(connectionId = "c1")
        val n = FeedNames(approvals = mapOf("auth:A1" to Approval.Authentication("A1", "c1", null, t, null, purpose = AuthPurpose.APPROVAL)))
        assertEquals(FeedText(R.string.data_feed_authenticate_approval, inline), FeedKinds.title(gone, n))
    }

    @Test
    fun aPendingApprovalOfAnotherConnectionDoesNotCarryItsPurpose() {
        val n = names(AuthPurpose.SIGNIN)
        assertEquals(AuthPurpose.NONE, FeedKinds.authenticationPurpose(item(connectionId = "c9"), n))
    }
}
