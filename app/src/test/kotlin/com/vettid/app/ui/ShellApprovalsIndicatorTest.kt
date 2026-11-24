package com.vettid.app.ui

import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.social.CriticalApproval
import com.vettid.core.data.social.OpenApprovals
import com.vettid.core.data.wallet.BackupRoute
import com.vettid.core.data.wallet.ImportWalletRoute
import com.vettid.core.data.wallet.NewWalletRoute
import com.vettid.core.data.wallet.SendRoute
import com.vettid.feature.settings.TransferOutRoute
import com.vettid.feature.approvals.ApprovalDetailRoute
import com.vettid.feature.approvals.ApprovalsRoute
import com.vettid.feature.connections.AcceptRoute
import com.vettid.feature.connections.ConnectionsRoute
import com.vettid.feature.connections.ScanRoute
import com.vettid.feature.devices.PairRoute
import com.vettid.feature.messages.ConversationRoute
import com.vettid.feature.messages.PhotoViewerRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.reflect.KClass

/** Where the shell provides the approvals indicator (ANDROID-PLAN §4 "The approvals indicator" 2, 8). */
@RunWith(RobolectricTestRunner::class) // AccountNames reads ICU
class ShellApprovalsIndicatorTest {
    private val deadline = Instant.parse("2026-11-24T12:05:00Z")
    private val open = OpenApprovals(2, CriticalApproval(deadline, urgent = true, label = "Bank"))

    private fun on(route: KClass<*>): (KClass<*>) -> Boolean = { it == route }

    @Test
    fun shownElsewhereWithTheCountdownAndATapToTheList() {
        var taps = 0
        val shown = approvalsIndicator(open, gated = false, on(ConversationRoute::class)) { taps++ }
        assertNotNull(shown)
        assertEquals(2, shown!!.count)
        assertEquals(true, shown.critical)
        assertEquals(true, shown.urgent)
        assertEquals(deadline, shown.deadline)
        assertEquals(AccountNames.isolate("Bank"), shown.label)
        shown.onClick()
        assertEquals(1, taps)
        assertNotNull(approvalsIndicator(open, gated = false, on(ConnectionsRoute::class)) {})
    }

    /**
     * Every wallet route the indicator's tap would pop with its secrets (popWalletSecrets) and that holds a request in flight
     * is one it is never shown on; the transactions hold nothing in flight.
     */
    @Test
    fun theWalletRoutesItsTapWouldPopAreHidden() {
        val shell = java.io.File("src/main/kotlin/com/vettid/app/ui/AppShell.kt").readText()
        val popped = Regex("""popIfInGraph<(\w+)>\(\)""").findAll(shell.substringAfter("fun NavHostController.popWalletSecrets()"))
            .map { it.groupValues[1] }.takeWhile { it != "TransactionsRoute" }.toList()
        assertEquals(listOf("NewWalletRoute", "ImportWalletRoute", "BackupRoute", "SendRoute"), popped)
        val hidden = INDICATOR_HIDDEN_ON.mapNotNull { it.simpleName }
        popped.forEach { assertEquals(it, true, it in hidden) }
    }

    /** The shell reads the time again when the app comes back to the front (a sleep does not run in deep sleep). */
    @Test
    fun aResumeIsPassedToTheClock() {
        val shell = java.io.File("src/main/kotlin/com/vettid/app/ui/AppShell.kt").readLines().map { it.trim() }
        assertEquals(true, shell.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { shell.resumed() }"))
    }

    @Test
    fun hiddenWhileHeldOrDueAndAtZero() {
        assertNull(approvalsIndicator(open, gated = true, on(ConversationRoute::class)) {})
        assertNull(approvalsIndicator(OpenApprovals(0), gated = false, on(ConversationRoute::class)) {})
    }

    @Test
    fun hiddenOnApprovalsARequestsScreenACameraAndAPhoto() {
        val hidden = listOf(
            ApprovalsRoute::class, ApprovalDetailRoute::class, AcceptRoute::class, PairRoute::class, ScanRoute::class,
            PhotoViewerRoute::class, NewWalletRoute::class, ImportWalletRoute::class, BackupRoute::class, SendRoute::class,
            TransferOutRoute::class,
        )
        for (route in hidden) assertNull(route.simpleName, approvalsIndicator(open, gated = false, on(route)) {})
    }
}
