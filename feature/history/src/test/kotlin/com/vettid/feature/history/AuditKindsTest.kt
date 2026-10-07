package com.vettid.feature.history

import com.vettid.core.data.vault.AuditCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kinds of VAULT-MESSAGING §10.9 have titles and groups; unknown kinds read generically. */
class AuditKindsTest {
    /** Every kind §10.9 lists (the `drop.<reason>` family and `<kind>.summary` aside). */
    private val specKinds = listOf(
        "vault.unlocked", "vault.locked", "device.paired", "device.unlinked", "connection.added", "connection.removed",
        "connection.stale", "connection.reconnected", "identity.rotated", "credential.created", "credential.rotated",
        "credential.password_changed", "credential.password_failed", "credential.unlocked", "credential.recovered",
        "credential.clone_detected", "credential.alarm.confirmed", "credential.alarm.resolved", "credential.reset",
        "vault.pin_failed", "owner_check.passed", "owner_check.held", "owner_check.failed", "owner_check.locked",
        "owner_check.hold_changed", "device.transfer.started", "device.transfer.approved", "device.transferred",
        "device.transfer.aborted", "device.transfer.attestation_failed", "device.replaced", "item.added", "item.updated",
        "item.deleted", "item.sensitivity_changed", "item.revealed", "tag.changed", "settings.changed",
        "account.name_requested", "account.name_applied", "account.name_refused", "profile.core_missing",
        "recovery.requested", "recovery.replaced", "recovery.bad_code", "recovery.attestation_failed",
        "recovery.registered", "recovery.device_paired", "recovery.completed", "recovery.cancelled", "recovery.expired",
        "recovery.voided", "message.sent", "message.received", "connection.blocked", "connection.unblocked",
        "connection.request.peer_declined", "connection.authenticate.requested", "connection.authenticate.signed",
        "connection.authenticate.denied", "connection.authenticated", "connection.authenticate_failed",
        "device.session.granted", "device.session.ended", "approval.granted", "approval.denied",
        "connection.authenticate.key_rotated", "connection.authenticate.rotation_rejected", "call.outgoing",
        "call.incoming", "call.answered", "call.ended", "leash.grant.issued", "leash.grant.updated", "leash.grant.revoked",
        "leash.rate_limited", "leash.agent.suspended", "leash.agent.resumed", "leash.referrals_limited", "leash.allowed",
        "leash.refused", "leash.item.read", "leash.item.used", "leash.throttled", "grant.requested", "grant.denied",
        "grant.issued", "grant.received", "grant.fetched", "grant.revoked", "share.rule.created", "share.rule.updated",
        "share.rule.deleted", "share.included", "share.declined", "share.withdrawn", "critical-secret.use.requested",
        "critical-secret.used", "critical-secret.use.denied", "critical-secret.use.result", "action.configured",
        "action.invoked", "action.approved", "action.denied", "action.completed", "intro.created", "intro.offered",
        "intro.accepted", "intro.declined", "intro.connecting", "intro.closed", "location.share.started",
        "location.share.stopped", "location.share.received", "location.share.ended", "location.requested",
        "location.history.deleted", "location.history.shared", "location.history.received", "wallet.created",
        "wallet.deleted", "wallet.address_issued", "wallet.signed", "drop.suppressed",
    )

    @Test
    fun everySpecKindHasATitleAndAGroup() {
        for (k in specKinds) {
            assertTrue(k, AuditKinds.title(k) is AuditKinds.Title.Known)
            assertTrue(k, AuditCategory.of(k) != AuditCategory.OTHER)
        }
    }

    @Test
    fun theTitlesAreDistinct() {
        val res = specKinds.map { (AuditKinds.title(it) as AuditKinds.Title.Known).res }
        assertEquals(res.size, res.toSet().size)
    }

    @Test
    fun categoriesFollowThePlansTable() {
        val expected = mapOf(
            "vault.unlocked" to AuditCategory.VAULT_ACCESS, "owner_check.held" to AuditCategory.VAULT_ACCESS,
            "vault.pin_failed" to AuditCategory.VAULT_ACCESS,
            "credential.clone_detected" to AuditCategory.SECURITY, "identity.rotated" to AuditCategory.SECURITY,
            "recovery.bad_code" to AuditCategory.SECURITY,
            "device.transfer.started" to AuditCategory.DEVICES, "approval.denied" to AuditCategory.DEVICES,
            "connection.authenticate.signed" to AuditCategory.CONNECTIONS, "connection.blocked" to AuditCategory.CONNECTIONS,
            "intro.closed" to AuditCategory.CONNECTIONS, "profile.core_missing" to AuditCategory.CONNECTIONS,
            "message.received" to AuditCategory.MESSAGES, "call.ended" to AuditCategory.MESSAGES,
            "item.revealed" to AuditCategory.ITEMS, "tag.changed" to AuditCategory.ITEMS, "share.rule.created" to AuditCategory.ITEMS,
            "grant.issued" to AuditCategory.ITEMS, "critical-secret.used" to AuditCategory.ITEMS, "wallet.signed" to AuditCategory.ITEMS,
            "leash.item.read.summary" to AuditCategory.AGENTS, "action.invoked" to AuditCategory.AGENTS,
            "location.share.started" to AuditCategory.LOCATION,
            "account.name_applied" to AuditCategory.ACCOUNT, "settings.changed" to AuditCategory.ACCOUNT,
            "drop.rate_limited" to AuditCategory.DROPPED, "drop.suppressed" to AuditCategory.DROPPED,
        )
        expected.forEach { (k, c) -> assertEquals(k, c, AuditCategory.of(k)) }
        // The prefix rule (§10.9): equal, or the prefix followed by ".".
        assertEquals(AuditCategory.OTHER, AuditCategory.of("vaultish.thing"))
        assertEquals(AuditCategory.OTHER, AuditCategory.of("zebra.new"))
        // A kind outside the prefixes is under "All" only: OTHER is not a filter.
        assertTrue(AuditCategory.OTHER !in AuditCategory.filters)
        assertEquals(10, AuditCategory.filters.size)
    }

    @Test
    fun everyKindFallsInExactlyOneCategory() {
        for (k in specKinds) {
            val n = AuditCategory.filters.count { c -> c.prefixes.any { AuditCategory.matches(k, it) } }
            assertEquals(k, 1, n)
        }
    }

    @Test
    fun droppedSummaryAndUnknown() {
        assertEquals(AuditKinds.Title.Dropped("rate limited"), AuditKinds.title("drop.rate_limited"))
        assertEquals(AuditKinds.Title.Dropped("owner check"), AuditKinds.title("drop.owner_check"))
        assertEquals(AuditKinds.Title.Summary(R.string.history_kind_leash_item_read), AuditKinds.title("leash.item.read.summary"))
        assertEquals(AuditKinds.Title.Unknown("zebra.new"), AuditKinds.title("zebra.new"))
        assertEquals(AuditKinds.Title.Unknown("drop."), AuditKinds.title("drop."))
    }

    @Test
    fun everyGroupHasALabel() {
        val labels = AuditCategory.entries.map { AuditKinds.categoryLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
    }

    /** §10.9: the kinds whose `ref` names an item (rows show its name; the detail opens it). */
    @Test
    fun itemEntriesNameTheirItem() {
        assertEquals("01ITEM", AuditKinds.itemOf("item.revealed", "01ITEM"))
        assertEquals("01ITEM", AuditKinds.itemOf("share.withdrawn", "01ITEM"))
        assertEquals(null, AuditKinds.itemOf("message.received", "m-1"))
        assertEquals(null, AuditKinds.itemOf("item.added", null))
        assertEquals(null, AuditKinds.itemOf("share.pending", "01RULE"))
    }
}
