package com.vettid.core.data.feed

import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ANDROID-PLAN 0.1.124 §4 "Notifications": "Settings → Security (the check)" opens Advanced security at the interval row,
 * "(the hold)" at the hold row; the lock after 10 failed checks stays Settings → Security.
 */
class FeedOwnerCheckTargetTest {
    private fun item(kind: String, ref: String?) = FeedItem("i", 1, kind, "2026-11-11T12:00:00Z", "active", "high", ref = ref)

    @Test
    fun theCheckAndTheHoldOpenTheirRowsInAdvancedSecurity() {
        assertEquals(FeedTarget.AdvancedSecurity(AdvancedSecurityRow.INTERVAL), FeedKinds.target(item("owner_check.failed", "pin")))
        assertEquals(FeedTarget.AdvancedSecurity(AdvancedSecurityRow.INTERVAL), FeedKinds.target(item("owner_check.failed", "password")))
        assertEquals(FeedTarget.AdvancedSecurity(AdvancedSecurityRow.HOLD), FeedKinds.target(item("owner_check.hold_changed", "off")))
        assertEquals(FeedTarget.Security, FeedKinds.target(item("owner_check.locked", null)))
    }
}
