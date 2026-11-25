package com.vettid.core.notify

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule 3 (the release gate): the notifications follow the vault manager's own `releaseUpdate.required` (read from the
 * source; [shellOpen]'s rule is tested in `NotifyShellOpenTest`).
 */
class NotifyReleaseGateWiringTest {
    @Test
    fun openFollowsTheGate() {
        val c = File("src/main/kotlin/com/vettid/core/notify/VaultNotifySource.kt").readLines()
            .map { it.substringBefore("//").trim() }
        val open = "override val open: StateFlow<Boolean> = combine(vault.phase, vault.ownerCheck.ownerCheck, " +
            "vault.releaseUpdate.required, ::shellOpen)"
        assertTrue(c.contains(open))
        val now = "override fun openNow(): Boolean = shellOpen(vault.phase.value, vault.ownerCheck.ownerCheck.value, " +
            "vault.releaseUpdate.required.value)"
        assertTrue(c.contains(now))
        // The notifier judges an arrival on that state now.
        val n = File("src/main/kotlin/com/vettid/core/notify/FeedNotifier.kt").readLines().map { it.substringBefore("//").trim() }
        assertTrue(n.contains("on && source.openNow() && item.status == FeedManager.STATUS_ACTIVE && !shownOnScreen(item)"))
    }

    /** R33: the app's source tells the notifier when the connections change, so an unnamed `connection.added` gets its name. */
    @Test
    fun connectionsChangedFollowsTheConnections() {
        val c = File("src/main/kotlin/com/vettid/core/notify/VaultNotifySource.kt").readLines()
            .map { it.substringBefore("//").trim() }
        assertTrue(c.contains("override val connectionsChanged: Flow<Unit> = vault.social.connections.map { }"))
    }
}
