package com.vettid.feature.connections

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.RequestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * 2026-11-01 (B, `302d29e1`): an edge swipe as a heads-up opened was taken as Back and left the safety code, the request
 * surviving only in Approvals. While a request is open, system Back and the top-bar arrow ask first; Leave goes, Stay stays, and
 * the request is never touched. Steps that hold no open request leave at once.
 */
@RunWith(RobolectricTestRunner::class)
class LeaveRequestTest {
    @get:Rule
    val rule = createComposeRule()

    private var left = 0
    private lateinit var dispatcher: OnBackPressedDispatcher

    private var acceptStep by mutableStateOf(AcceptStep.INPUT)

    private fun accept(step: AcceptStep) {
        acceptStep = step
        rule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            AcceptScreen(
                AcceptUiState(step = acceptStep, connectionId = "c1", name = "Sam Lee", sas = "123 456", input = "link"),
                AcceptActions(onBack = { left++ }),
            )
        }
    }

    private val request = Approval.ConnectionRequest(
        pendingId = "p1", inviteId = "i1", sas = "123 456", remote = false, name = "Sam Lee", introducedBy = null,
        receivedAt = Instant.EPOCH, exp = null,
    )

    private fun invite(step: InviteStep) = rule.setContent {
        dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        val req = if (step == InviteStep.CONNECTING) request.copy(state = RequestState.APPROVED) else request
        InviteScreen(InviteUiState(step = step, request = req), InviteActions(onBack = { left++ }))
    }

    private fun systemBack() = rule.runOnUiThread { dispatcher.onBackPressed() }

    private fun backAsksThenStayAndLeave() {
        systemBack()
        rule.onNodeWithText("Leave this request?").assertExists()
        rule.onNodeWithText("It stays in Approvals, where you can finish it.").assertExists()
        rule.onNodeWithText("Stay").performClick()
        rule.onNodeWithTag("confirm_leave").assertDoesNotExist()
        assertEquals(0, left)
        // The top-bar arrow asks the same.
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Leave").performClick()
        assertEquals(1, left)
    }

    @Test
    fun waitingForTheCodeAsksFirst() {
        accept(AcceptStep.WAITING)
        backAsksThenStayAndLeave()
    }

    @Test
    fun comparingTheCodeAsksFirst() {
        accept(AcceptStep.COMPARE)
        backAsksThenStayAndLeave()
    }

    @Test
    fun approvedAndWaitingForTheInviterAsksFirst() {
        accept(AcceptStep.APPROVED)
        backAsksThenStayAndLeave()
    }

    @Test
    fun theInvitersRequestAsksFirst() {
        invite(InviteStep.REQUEST)
        backAsksThenStayAndLeave()
    }

    @Test
    fun theInviterApprovedAndConnectingAsksFirst() {
        invite(InviteStep.CONNECTING)
        backAsksThenStayAndLeave()
    }

    @Test
    fun withNoOpenRequestBackLeavesAtOnce() {
        accept(AcceptStep.ENDED)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag("confirm_leave").assertDoesNotExist()
        assertEquals(1, left)
    }

    @Test
    fun sendingTheRequestAsksFirst() {
        accept(AcceptStep.ACCEPTING)
        backAsksThenStayAndLeave()
    }

    @Test
    fun systemBackThenLeaveGoes() {
        accept(AcceptStep.COMPARE)
        systemBack()
        rule.onNodeWithText("Leave").performClick()
        assertEquals(1, left)
    }

    @Test
    fun withNoOpenRequestSystemBackIsNotTaken() {
        accept(AcceptStep.INPUT)
        // Nothing on the screen takes Back: the app's NavHost pops it, as for any screen.
        rule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
        systemBack()
        rule.onNodeWithTag("confirm_leave").assertDoesNotExist()
    }

    @Test
    fun aStepThatEndsTheRequestDropsTheQuestionAndBackLeaves() {
        accept(AcceptStep.COMPARE)
        systemBack()
        rule.onNodeWithTag("confirm_leave").assertExists()
        acceptStep = AcceptStep.CONNECTED
        rule.waitForIdle()
        rule.onNodeWithTag("confirm_leave").assertDoesNotExist()
        // Back is no longer taken by the guard (the NavHost pops the screen), and the question does not come back.
        rule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
        systemBack()
        rule.onNodeWithTag("confirm_leave").assertDoesNotExist()
        assertEquals(0, left)
    }

    @Test
    fun doneAfterApprovingLeavesWithoutTheQuestion() {
        accept(AcceptStep.APPROVED)
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("confirm_leave").assertDoesNotExist()
        assertEquals(1, left)
    }
}
