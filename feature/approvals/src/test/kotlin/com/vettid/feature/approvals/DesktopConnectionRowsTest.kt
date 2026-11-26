// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.HeldRequest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ANDROID-PLAN 0.1.145 §4 "Requests held for approval": a desktop's held block and removal of a connection, the owner's
 * texts (GC #19089) word for word, Approve and Deny, and the deny-only row when the connection is not resolved.
 */
@RunWith(RobolectricTestRunner::class)
class DesktopConnectionRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.now()
    private val laptop = "⁨Laptop⁩"
    private val sam = "⁨Sam Lee⁩"

    private fun held(h: HeldRequest, name: String? = "Sam Lee", service: Boolean = false, type: String = if ((h as? HeldRequest.DesktopConnection)?.remove == true) "connection.remove" else "block.add") =
        Approval.DeviceRequest(
            "approval.pending", "h1", "Laptop", "desktop", type, now, now.plusSeconds(4 * 60 + 30),
            heldConnection = name, deviceId = "desk1", held = h, heldService = service,
        )

    private fun state(a: Approval.DeviceRequest) = ApprovalDetailUiState(a.key, a, items = emptyList())

    private fun show(s: ApprovalDetailUiState, actions: DecisionActions = DecisionActions()) = rule.setContent { ApprovalDetailScreen(s, actions) }

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aBlockShowsTheOwnersTextsAndTheNoteApartWithApproveAndDeny() {
        var approved = 0
        var denied = 0
        show(
            state(held(HeldRequest.DesktopConnection(false, "c1", "  spam\r\nagain‮ "))),
            DecisionActions(onApprove = { approved++ }, onDeny = { denied++ }),
        )
        rule.onNodeWithText("$laptop wants to block $sam").assertExists()
        rule.onNodeWithText("Blocking ends your connection with $sam. They can't connect with you again until you unblock them.").assertExists()
        // The note, cleaned with its lines kept (the override gone), each line isolated, in its own box.
        rule.onNodeWithTag("desktop_note").assertExists()
        rule.onNodeWithText("⁨spam⁩\n⁨again⁩").assertExists()
        assertTrue("not a service", !has("Service"))
        rule.onNodeWithText("Approve").performClick()
        rule.onNodeWithText("Deny").performClick()
        assertEquals(1, approved)
        assertEquals(1, denied)
    }

    @Test
    fun aRemovalShowsTheOwnersTextsAndNoNoteBox() {
        show(state(held(HeldRequest.DesktopConnection(true, "c1", null))))
        rule.onNodeWithText("$laptop wants to remove $sam").assertExists()
        rule.onNodeWithText("Removing ends your connection with $sam.").assertExists()
        rule.onNodeWithTag("desktop_note").assertDoesNotExist()
        rule.onNodeWithText("Approve").assertExists()
        rule.onNodeWithText("Deny").assertExists()
    }

    @Test
    fun aBlockWithANoteEmptyOnceCleanedHasNoBox() {
        show(state(held(HeldRequest.DesktopConnection(false, "c1", " ​\n\t "))))
        rule.onNodeWithText("$laptop wants to block $sam").assertExists()
        rule.onNodeWithTag("desktop_note").assertDoesNotExist()
    }

    @Test
    fun aServiceIsNamedByItsNameWithTheServiceLabel() {
        show(state(held(HeldRequest.DesktopConnection(true, "svc", null), name = "Shop", service = true)))
        rule.onNodeWithText("$laptop wants to remove ⁨Shop⁩").assertExists()
        rule.onNodeWithText("Service").assertExists()
    }

    @Test
    fun anUnresolvedConnectionKeepsTheDenyOnlyRow() {
        show(state(held(HeldRequest.DesktopConnection(false, "gone", "spam"), name = null)))
        rule.onNodeWithText("$laptop asks to approve a request").assertExists()
        rule.onNodeWithText("Desktop requests can't be shown in this version of VettID, so you can only deny them.").assertExists()
        assertTrue("no Approve", !has("Approve"))
        rule.onNodeWithText("Deny").assertExists()
        rule.onNodeWithTag("desktop_note").assertDoesNotExist()
        // The removal, and a name that is empty once cleaned, are the same row.
        assertEquals(HeldShow.Desktop, DeviceAsks.show(held(HeldRequest.DesktopConnection(true, "gone", null), name = null), emptyList()))
        assertEquals(HeldShow.Desktop, DeviceAsks.show(held(HeldRequest.DesktopConnection(false, "c1", null), name = " ​ "), emptyList()))
    }

    @Test
    fun anApprovalsAnswersReadAsThePlanSays() {
        val a = held(HeldRequest.DesktopConnection(false, "c1", null))
        var outcome by androidx.compose.runtime.mutableStateOf<DeviceOutcome>(DeviceOutcome.Approved("ok"))
        rule.setContent { ApprovalDetailScreen(state(a).copy(outcome = outcome), DecisionActions()) }
        rule.onNodeWithText("Approved").assertExists()
        // The held request's own answer when it ran is not the error not_found: "Any other result".
        for (code in listOf("not_found", "exists", "limit", "bad_request")) {
            outcome = DeviceOutcome.Approved(code)
            rule.onNodeWithText("Approved, but your vault answered: ⁨$code⁩.").assertExists()
        }
        outcome = DeviceOutcome.NoLongerWaiting
        rule.onNodeWithText("This request is no longer waiting.").assertExists()
    }

    @Test
    fun theListRowIsTitledAsTheScreen() {
        rule.setContent { androidx.compose.material3.Text(titleOf(held(HeldRequest.DesktopConnection(false, "c1", null)))) }
        rule.onNodeWithText("$laptop wants to block $sam").assertExists()
    }
}
