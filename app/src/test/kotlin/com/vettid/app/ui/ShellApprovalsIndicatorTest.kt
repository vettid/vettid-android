package com.vettid.app.ui

import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.social.CriticalApproval
import com.vettid.core.data.social.OpenApprovals
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

    @Test
    fun hiddenWhileHeldOrDueAndAtZero() {
        assertNull(approvalsIndicator(open, gated = true, on(ConversationRoute::class)) {})
        assertNull(approvalsIndicator(OpenApprovals(0), gated = false, on(ConversationRoute::class)) {})
    }

    @Test
    fun hiddenOnApprovalsARequestsScreenACameraAndAPhoto() {
        val hidden = listOf(
            ApprovalsRoute::class, ApprovalDetailRoute::class, AcceptRoute::class, PairRoute::class, ScanRoute::class,
            PhotoViewerRoute::class,
        )
        for (route in hidden) assertNull(route.simpleName, approvalsIndicator(open, gated = false, on(route)) {})
    }
}
