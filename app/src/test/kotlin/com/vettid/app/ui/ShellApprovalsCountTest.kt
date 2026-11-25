package com.vettid.app.ui

import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AuthPurpose
import com.vettid.core.data.social.CriticalApproval
import com.vettid.core.data.social.InviteLinkInbox
import com.vettid.core.data.votes.VoteState
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.data.wallet.NoWalletAccess
import com.vettid.core.notify.NotificationOpenInbox
import com.vettid.core.notify.NotifyVisibility
import com.vettid.core.testing.FakeFeed
import com.vettid.core.testing.FakePresence
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import com.vettid.core.testing.FakeVotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The approvals indicator's count and the drawer's Approvals badge are one source (ANDROID-PLAN §4 "The approvals
 * indicator" 8): new, decided and expired entries change both, at the deadline with no read; votes are not in it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellApprovalsCountTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val t0: Instant = Instant.parse("2026-11-24T12:00:00Z")
    private val social = FakeSocial()
    private val votes = FakeVotes()

    private fun vm() = ShellViewModel(
        social, social, InviteLinkInbox(), FakeVault(), FakeFeed(), InMemoryPreferencesRepository(), NotificationOpenInbox(),
        NotifyVisibility(), votes, NoWalletAccess, FakePresence(),
    ).also { v -> v.clock = { t0.plusMillis(main.scheduler.currentTime + slept) } }

    /** Deep sleep: the wall clock moves on, a delay does not. */
    private var slept = 0L

    private fun location(id: String, exp: Instant?) = Approval.LocationRequest(id, "c", null, t0, exp, connectionName = "Kim")

    private fun signIn(id: String, exp: Instant?) =
        Approval.Authentication(id, "c", null, t0, exp, connectionName = "Bank", purpose = AuthPurpose.SIGNIN)

    private fun ShellViewModel.counts() = openApprovals.value.count to badges.value.approvals

    @Test
    fun newDecidedAndExpiredChangeBothCounts() = runTest(main) {
        val vm = vm()
        assertEquals(0 to 0, vm.counts())
        social.approvals.value = listOf(location("soon", t0.plusSeconds(300)), location("later", t0.plusSeconds(86_400)))
        runCurrent()
        assertEquals(2 to 2, vm.counts())
        assertNull(vm.openApprovals.value.critical)
        // Its last 2 minutes: time-critical, with no read.
        advanceTimeBy(180_001)
        assertEquals(CriticalApproval(t0.plusSeconds(300), urgent = true, label = "Kim"), vm.openApprovals.value.critical)
        // Its deadline: gone from both counts, with no read.
        advanceTimeBy(120_000)
        assertEquals(1 to 1, vm.counts())
        assertNull(vm.openApprovals.value.critical)
        // Decided (the repository drops it): 0, the indicator hidden.
        social.approvals.value = emptyList()
        runCurrent()
        assertEquals(0 to 0, vm.counts())
    }

    @Test
    fun aSignInCountsDownNeutrally() = runTest(main) {
        val vm = vm()
        social.approvals.value = listOf(signIn("s", t0.plusSeconds(240)))
        runCurrent()
        assertEquals(CriticalApproval(t0.plusSeconds(240), urgent = false, label = "Bank"), vm.openApprovals.value.critical)
        advanceTimeBy(240_001)
        assertEquals(0 to 0, vm.counts())
    }

    @Test
    fun aResumeAfterDeepSleepReadsTheTimeAgain() = runTest(main) {
        val vm = vm()
        social.approvals.value = listOf(location("soon", t0.plusSeconds(300)), location("later", t0.plusSeconds(86_400)))
        runCurrent()
        assertEquals(2 to 2, vm.counts())
        // The phone slept past the deadline: nothing woke, so the count stands until the app comes back.
        slept = 301_000
        runCurrent()
        assertEquals(2 to 2, vm.counts())
        vm.resumed()
        runCurrent()
        assertEquals(1 to 1, vm.counts())
    }

    @Test
    fun votesAreNotCounted() = runTest(main) {
        val vm = vm()
        votes.votes.value = listOf(VoteSummary(proposalId = "p", connectionId = "c", title = "Budget", state = VoteState.OPEN))
        runCurrent()
        assertEquals(0 to 0, vm.counts())
        assertEquals(1, vm.badges.value.votes)
    }
}
